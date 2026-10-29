// observe：open_id → 飞书用户名解析。
// 🔴 本类零 runBlocking / 零 synchronized / 零 Mutex —— ConcurrentHashMap + 原子量，全链路无锁（违反即阻塞 intake/task 热路径）。
// Entry 含 avatarUrl + profileFetch seam（名字+头像一次 API 双收益）+ profileOf/peekProfile；
//    fetch/nameOf/peek 全部保留（既有消费链与测试零改动）。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.gateway.SenderProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 用户名解析器：`open_id` → 姓名（`GET /contact/v3/users/{open_id}?user_id_type=open_id`）。
 *
 * 语义：
 * - 内存缓存（open_id → name），命中零网络开销；未命中通过 [fetch] seam 拉取后回填
 * - 正向结果 TTL 12h（用户名极少变更）；负向结果（null/空串）TTL 5min（避免无 scope 时每条消息都打一次 API）
 * - LRU 淘汰：缓存条数超过 [LRU_CAPACITY] 时，清理过期条目 + 按 createdAt 淘汰最旧条目（阈值触发，零锁）
 * - 旁路语义：任何失败静默返回 null，绝不影响消息主链路
 *
 * 权限要求：应用需开通「获取用户基本信息」（[CONTACT_BASE_SCOPE] 单常量）；
 * 未授权时接口报错，缓存空串占位（负向 TTL），统计侧展示 open_id 兜底。
 *
 * ## 全链路无锁纪律
 * 缓存用 [ConcurrentHashMap]；计数器用 [AtomicLong]；LRU 淘汰用 `entries.removeIf {}`（CHM 内部分段锁，
 * 调用方**零 synchronized / 零 Mutex / 零 runBlocking**）。
 *
 * @param fetch 拉取 seam：入参 open_id，返回姓名；**返回 null / 空串 / 抛异常一律按「解析失败」处理**
 * @param profileFetch 完整身份拉取 seam：返回 [SenderProfile]（名字+头像），
 *   一次 API 双收益；[profileFetch] 非空且命中时优先于 [fetch]（装配侧单路化）。
 *   null / 抛异常一律按「解析失败」处理，回落 [fetch]
 * @param clock 时间源（可注入；测试用虚拟时钟断言 TTL 行为）
 */
class UserNameResolver(
    private val fetch: suspend (openId: String) -> String? = { null },
    private val profileFetch: suspend (openId: String) -> SenderProfile? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    // ==================== 缓存结构 ====================

    /** 缓存条目：名字 + 头像 URL + 写入时间 + 是否为负向（解析失败）占位。 */
    private class Entry(val name: String, val avatarUrl: String, val createdAt: Long, val negative: Boolean)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val cacheHitCounter = AtomicLong(0)
    private val fetchTotalCounter = AtomicLong(0)
    private val fetchFailureCounter = AtomicLong(0)

    // ==================== 公开指标 ====================

    /** 缓存条数（含负向占位）。 */
    val cacheSize: Int get() = cache.size

    /** 命中缓存的次数（与 [fetchTotal] 之比即缓存命中率）。 */
    val cacheHits: Long get() = cacheHitCounter.get()

    /** 实际发起拉取的次数。 */
    val fetchTotal: Long get() = fetchTotalCounter.get()

    /** 拉取失败（返回 null/空串/抛异常）的次数（非 0 通常意味着 contact 权限未开通）。 */
    val fetchFailures: Long get() = fetchFailureCounter.get()

    // ==================== 公开方法 ====================

    /**
     * 解析用户名（挂起；缓存未命中走 [resolveCold] 冷路径——profileFetch 优先，null 回落 [fetch]）。
     *
     * 🔴 **只在 Intake 下游的协程里调用**（Admission→Execute 边界的 task 协程，或 submitRejection 的 task 协程）——进 intake 即整链路被网络往返挂死。
     *    Intake 单消费者协程内绝不允许任何挂起 API 调用。
     *
     * @return 姓名；解析失败或无权限返回 null（调用方以 open_id 兜底展示）
     */
    suspend fun nameOf(openId: String): String? {
        if (openId.isBlank()) return null
        val now = clock()

        // 热路径：缓存命中且未过期
        val hit = cache[openId]
        if (hit != null && !isExpired(hit, now)) {
            cacheHitCounter.incrementAndGet()
            return hit.name.ifBlank { null }
        }

        // 冷路径：发起 API 拉取（[resolveCold]：profileFetch 优先——名字+头像一次 API 双收益）
        return resolveCold(openId, now).name.ifBlank { null }
    }

    /**
     * 解析完整身份（挂起）。与 [nameOf] **共享同一缓存条目**（名字+头像）：
     * 任一路径先冷读则另一路径命中零网络——统计/详情页不会重复打 API。
     *
     * @return [SenderProfile]；负向占位（API 失败 / 名字与头像全空）返回 null，调用方以 open_id 兜底
     */
    suspend fun profileOf(openId: String): SenderProfile? {
        if (openId.isBlank()) return null
        val now = clock()

        // 热路径：负向占位 ⇒ 解析不出，返回 null（与 [nameOf] 的 ifBlank 语义对齐）
        val hit = cache[openId]
        if (hit != null && !isExpired(hit, now)) {
            cacheHitCounter.incrementAndGet()
            return if (hit.negative) null else SenderProfile(hit.name, hit.avatarUrl)
        }

        val entry = resolveCold(openId, now)
        return if (entry.negative) null else SenderProfile(entry.name, entry.avatarUrl)
    }

    /**
     * 只读缓存查询（**非挂起、绝不触发 API**）。
     *
     * 用于拒绝路径中没有任何协程可用的降级场景（rejectionReply 为空、只加计数器就结束），
     *    此时 peek 命中则用缓存名字，未命中则调用方写空串。
     *    **已知差异**：相比「解析 WS 载荷对所有消息都有名字」的形态，peek 未命中时名字为空。
     *
     * @return 缓存中的姓名（过期视为未命中返回 null）；绝不发起网络请求
     */
    fun peek(openId: String): String? {
        if (openId.isBlank()) return null
        val entry = cache[openId] ?: return null
        if (isExpired(entry, clock())) return null
        return entry.name.ifBlank { null }
    }

    /**
     * 完整身份的只读缓存查询（**非挂起、绝不触发 API**）。
     *
     * 定位与 [peek] 相同：拒绝路径没有任何协程可用的降级场景；
     * 未命中或负向占位返回 null（调用方以 open_id 兜底）。
     */
    fun peekProfile(openId: String): SenderProfile? {
        if (openId.isBlank()) return null
        val entry = cache[openId] ?: return null
        if (isExpired(entry, clock())) return null
        return if (entry.negative) null else SenderProfile(entry.name, entry.avatarUrl)
    }

    /** 清空缓存（切换 appId / tenant 时；**不**归零各计数器，保证 UI 能察觉「清空过」）。 */
    fun clear() {
        cache.clear()
    }

    override fun toString(): String =
        "UserNameResolver(size=$cacheSize, hits=$cacheHits, fetches=$fetchTotal, failures=$fetchFailures)"

    // ==================== 私有：缓存操作 ====================

    /** 判断条目是否过期（正向 TTL 12h / 负向 TTL 5min）。 */
    private fun isExpired(entry: Entry, now: Long): Boolean {
        val ttl = if (entry.negative) NEGATIVE_TTL_MILLIS else POSITIVE_TTL_MILLIS
        return (now - entry.createdAt) >= ttl
    }

    /**
     * 写缓存 + 阈值触发的 LRU 淘汰（零锁）。
     *
     * 淘汰策略：
     * 1. 先移除所有已过期条目（O(n) 遍历，CHM.entries.removeIf 内部分段安全）
     * 2. 若仍超容量，按 createdAt 升序淘汰最旧的直到容量内（最坏 O(n log n)，但只在阈值触发时执行）
     */
    /**
     * 冷路径统一出口：实际拉取身份（profileFetch 优先，null 回落 fetch），
     * 写缓存并返回写入的条目——[nameOf]/[profileOf] 共享此出口；回落 fetch 产出名字-only 条目（头像空串）。
     */
    private suspend fun resolveCold(openId: String, now: Long): Entry {
        fetchTotalCounter.incrementAndGet()
        val profile = runCatching { profileFetch(openId) }.getOrNull()
            ?: runCatching { fetch(openId)?.let(::extractName) }.getOrNull()
                ?.let { name -> SenderProfile(name, "") }
        // 负向占位语义：API 失败（null）或名字与头像全空 ⇒ 短 TTL 5min（避免每条消息都打一次 API，又允许短暂网络故障后重试）
        val negative = profile == null || (profile.name.isBlank() && profile.avatarUrl.isBlank())
        if (negative) fetchFailureCounter.incrementAndGet()
        val entry = Entry(profile?.name.orEmpty(), profile?.avatarUrl.orEmpty(), now, negative)
        put(openId, entry, now)
        return entry
    }

    private fun put(openId: String, entry: Entry, now: Long) {
        cache[openId] = entry
        if (cache.size > LRU_CAPACITY) {
            evict(now)
        }
    }

    /** 阈值触发的淘汰：先清过期，再按时间淘汰最旧。 */
    private fun evict(now: Long) {
        // 阶段 1：清除所有过期条目
        cache.entries.removeIf { (_, entry) -> isExpired(entry, now) }
        // 阶段 2：若仍超容量，按 createdAt 淘汰最旧的
        val excess = cache.size - LRU_CAPACITY
        if (excess > 0) {
            val sorted = cache.entries.sortedBy { it.value.createdAt }
            for (i in 0 until minOf(excess, sorted.size)) {
                cache.remove(sorted[i].key)
            }
        }
    }

    companion object {
        // ==================== LRU / TTL 常量 ====================

        /**
         * 用户名解析所需 scope 单常量（「获取用户基本信息」）——UI 快速开始清单、
         * 网关报错文案、注释三方对齐同一字面量（旧名 contact:user.base:readonly
         * 已被平台更名，真机实证见 docs/pages/lark-bridge.md 权限清单表）。
         */
        const val CONTACT_BASE_SCOPE: String = "contact:contact.base:readonly"

        /** LRU 缓存容量上限。 */
        const val LRU_CAPACITY: Int = 1024

        /** 正向结果 TTL：12 小时（用户名极少变更）。 */
        const val POSITIVE_TTL_MILLIS: Long = 12L * 60 * 60 * 1000

        /** 负向结果 TTL：5 分钟（避免无 scope 时每条消息都打一次 API，但允许短暂网络故障后重试）。 */
        const val NEGATIVE_TTL_MILLIS: Long = 5L * 60 * 1000

        // ==================== 旧常量逐字保留（测试断言依赖）====================

        /**
         * 用户信息接口路径（open_id 查询）。**旧 :56 逐字保留**——seam 的实现方（网关 resolveSenderName）
         * 必须用同一路径，否则会打到不同的接口版本上。
         */
        const val USER_PATH: String = "/contact/v3/users/"

        /** open_id 类型查询参数（旧调用 `client.getJson(USER_PATH + openId, token)` 时由客户端补全）。 */
        const val USER_ID_TYPE_QUERY: String = "?user_id_type=open_id"

        /** 失败占位（旧 :49 的 `cache[openId] = ""`）：缓存空串表示「已知解析不出」，下次仍返回 null。 */
        const val MISSING: String = ""

        /** 解析用的宽松 Json（`ignoreUnknownKeys`，只取 `data.user.name`）。 */
        val ResponseJson: Json = Json { ignoreUnknownKeys = true }

        /**
         * 从响应文本里取姓名（旧 :43-44 的**逐层 `runCatching`** 语义原样搬进来）：
         * `response["data"]?.jsonObject["user"]?.jsonObject?.get("name")?.jsonPrimitive?.content`，
         * 任何一层形状不符都返回空串，**不抛**。
         *
         * @param response **整包**响应文本（形如 `{"data":{"user":{"name":"张三"}}}`）
         */
        fun extractUserName(response: String): String =
            runCatching { extractUserName(ResponseJson.parseToJsonElement(response)) }.getOrDefault(MISSING)

        /**
         * [extractUserName] 的 `JsonElement` 形态。容忍**两种**层级：
         * 整包响应（含 `data`）与已剥壳的 `data` 层（直接含 `user`）——旧 :43 拿到的是前者，
         * 而宿主侧若已自行剥过一层也不该因此解不出名字。
         */
        fun extractUserName(response: JsonElement): String {
            val root = runCatching { response.jsonObject }.getOrNull() ?: return MISSING
            val dataLayer = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: root
            val user = runCatching { dataLayer["user"]?.jsonObject }.getOrNull()
            return user?.get("name")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        }

        /** seam 返回值的归一化：seam 可能直接返回姓名，也可能返回整包响应文本 ⇒ 先试 JSON 解析。 */
        private fun extractName(raw: String): String {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) return raw
            return extractUserName(trimmed)
        }
    }
}
