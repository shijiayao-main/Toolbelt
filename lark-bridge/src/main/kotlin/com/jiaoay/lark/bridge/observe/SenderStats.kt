// observe 发送者统计域：schema + 采集器。
// 🔴 采集器全 suspend、**零 `runBlocking`**：用户名解析若在写路径内 runBlocking 桥接，
//    缓存未命中即同步网络调用，会把整个统计回调（乃至任务协程所在线程）钉死在网络 RTT 上。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.config.AuditJson
import com.jiaoay.lark.bridge.pipeline.AccessDecision
import com.jiaoay.lark.gateway.SenderProfile
import java.io.File
import kotlinx.serialization.Serializable

/**
 * 🔒 发送者统计记录（JSONL 单行对象，追加写入）。**6 个字段名/顺序/默认值与存量
 * `~/.toolbelt/lark/sender_stats.jsonl` 的旧 schema 逐字一致**。
 *
 * 每条记录一次事件（消息被接受 / 被拒绝），对话量由宿主侧按 senderOpenId 聚合得出。
 */
@Serializable
data class SenderStatsEntry(
    /** 事件时间（epoch millis） */
    val ts: Long,
    /** 发送者 openId */
    val senderOpenId: String,
    /** 会话 chatId */
    val chatId: String,
    /** 事件类型：MESSAGE（agent 消息被接受）/ REJECTED_WHITELIST / REJECTED_BLACKLIST */
    val type: String,
    /** 消息内容摘要（前 120 字符；拒绝事件为触发文本） */
    val content: String = "",
    /** 解析到的用户名（解析失败为空串；宿主侧可后续补齐） */
    val userName: String = "",
    /** 发送者头像 URL：空串=无头像；后加字段带默认值 + encodeDefaults=false ⇒ 空串缺键，旧行读回 ""，双向兼容 */
    val avatarUrl: String = "",
)

/** [SinkRecord.SenderStat]（内存 seam）→ [SenderStatsEntry]（磁盘 schema）。 */
fun SinkRecord.SenderStat.toEntry(): SenderStatsEntry = SenderStatsEntry(
    ts = ts,
    senderOpenId = senderOpenId,
    chatId = chatId,
    type = type,
    content = contentSummary,
    userName = userName,
    avatarUrl = avatarUrl,
)

/**
 * 统计域常量与纯函数。
 *
 * 🔒 三个字面值全部**大写**，且 `WHITELIST`/`BLACKLIST` 是**刻意保留的旧拼写**：
 * 它们是已落盘的历史数据里的字面值，改成 allowlist/blocklist 会让同一份统计文件出现两种 type 取值，
 * 宿主侧按 type 分组直接错。
 * 新配置域 `access.mode` 用的是 `allowlist`/`blocklist`（CDS §3.2.2），两者是**不同层**的字面值，不冲突。
 *
 * 本 object 体内只有 `const val` 与纯函数。
 */
object SenderStatsRecords {

    /** 🔒 统计文件名。 */
    const val FILE_NAME: String = SinkRecord.SENDER_STATS_FILE_NAME

    /** 🔒 内容摘要截断长度。 */
    const val CONTENT_SUMMARY_CHARS: Int = SinkRecord.CONTENT_SUMMARY_CHARS

    /** 🔒 见 [SinkRecord.TYPE_MESSAGE]。 */
    const val TYPE_MESSAGE: String = SinkRecord.TYPE_MESSAGE

    /** 🔒 见 [SinkRecord.TYPE_REJECTED_ALLOWLIST]（刻意保留旧拼写 WHITELIST）。 */
    const val TYPE_REJECTED_WHITELIST: String = SinkRecord.TYPE_REJECTED_ALLOWLIST

    /** 🔒 见 [SinkRecord.TYPE_REJECTED_BLOCKLIST]（刻意保留旧拼写 BLACKLIST）。 */
    const val TYPE_REJECTED_BLACKLIST: String = SinkRecord.TYPE_REJECTED_BLOCKLIST

    /**
     * [AccessDecision] → 统计 type 字面值（🔴 穷尽 `when` 无 `else`；新增第 4 个判定值时本文件编译失败）。
     * 等价规则：`BLOCKED → BLACKLIST`，其余 → `WHITELIST`。
     */
    fun typeOf(decision: AccessDecision): String = when (decision) {
        AccessDecision.BLOCKED -> TYPE_REJECTED_BLACKLIST
        AccessDecision.NOT_IN_ALLOWLIST -> TYPE_REJECTED_WHITELIST
        AccessDecision.ALLOWED -> TYPE_MESSAGE
    }

    /** 🔒 内容摘要：`content.take(CONTENT_SUMMARY_CHARS)`——**用 `take` 不用 StringTruncation**（无省略号）。 */
    fun summarizeContent(content: String): String = content.take(CONTENT_SUMMARY_CHARS)

    /** 统计 sink 的编码器。 */
    fun encoder(): JsonlEncoder = JsonlEncoder { record ->
        (record as? SinkRecord.SenderStat)
            ?.let { AuditJson.encodeToString(SenderStatsEntry.serializer(), it.toEntry()) }
    }

    /** 解析统计文件所在目录 → 文件；blank 返回 null（= 禁用统计）。 */
    fun resolveFile(dir: String): File? = JsonlSink.resolveFile(dir, FILE_NAME)
}

/**
 * 发送者统计采集器（全 suspend，**零 `runBlocking`**）。
 *
 * ## 与旧版同步形态的差异（保留为契约说明）
 * | 维度 | 行为 |
 * |---|---|
 * | 接口 | **suspend**（可直接挂起解析用户名） |
 * | 用户名解析 | 直接 `userNameResolver(openId)`（挂起，不占线程） |
 * | 写盘 | 只 `offerStats` 投递到通道（O(1) 非挂起），落盘在 sink 协程 |
 * | 失败 | `runCatching` 包住解析与投递，另由 `SinkChannels` 计数溢出 |
 *
 * 「移出写路径」的落点：本类**不碰文件**。落盘全部交给 [JsonlSink]（跑在 `dispatchers.sink`），
 *    用户名解析全部交给 [userNameResolver]（应在 `dispatchers.net` 上挂起，装配时决定）。
 *
 * **调用时机**：应在 ⑤ Observe 阶段调用，即 ④ Reply **之后**——这样即便冷缓存触发一次网络解析，
 *    也不会推迟用户可见的回复（同步解析在回复之前跑，正是用户可感的延迟来源）。
 *
 * @param channels 落盘通道（[SinkChannels.offerStats] 是 O(1) 非挂起、DROP_OLDEST，绝不阻塞调用方）
 * @param userNameResolver openId → 用户名；返回 null / 抛异常 ⇒ `userName` 留空（旁路语义，绝不影响主链路）。
 *   实现见 [UserNameResolver]；**缓存命中零网络**。
 * @param profileResolver openId → 完整身份（名字+头像）：主路——非空命中时
 *   一次解析双产出；null / 抛异常 ⇒ 回落 [userNameResolver] 降级路（名字-only，头像空串）。
 *   **不设时（默认）行为与名字-only 版本逐字一致**——既有测试与单测直装配零改动。
 * @param enabled `observability.senderStatsEnabled`；false ⇒ 两个回调直接返回（连解析都不做）
 * @param clock 时间源（可注入，测试用固定时钟）
 */
class SenderStatsRecorder(
    private val channels: SinkChannels,
    private val userNameResolver: suspend (openId: String) -> String? = { null },
    private val profileResolver: suspend (openId: String) -> SenderProfile? = { null },
    private val enabled: Boolean = true,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 累计采集条数（含被通道 DROP_OLDEST 挤掉的；与 `statsOfferedCount` 对齐）。 */
    var collectedTotal: Long = 0L
        private set

    /** 用户名解析失败/为空次数（可观测，非 0 说明 contact 权限未开通）。 */
    var nameUnresolvedTotal: Long = 0L
        private set

    /**
     * 消息被接受。
     * suspend ⇒ 用户名解析不再需要 `runBlocking`。
     */
    suspend fun onMessage(senderOpenId: String, chatId: String, content: String) {
        if (!enabled) return
        val profile = resolveProfile(senderOpenId)
        offer(
            SinkRecord.SenderStat(
                ts = clock(),
                senderOpenId = senderOpenId,
                userName = profile.name,
                avatarUrl = profile.avatarUrl,
                chatId = chatId,
                type = SenderStatsRecords.TYPE_MESSAGE,
                contentSummary = SenderStatsRecords.summarizeContent(content),
            ),
        )
    }

    /**
     * 消息被准入拒绝。
     *
     * 🔒 `chatId` 写**空串**、`content` 写**空串**（保持存量落盘形态——旧行该两键恒缺/空）。
     * @param content 可选的触发文本；默认空串以保持旧落盘形态（旧行 `content` 缺键）。
     */
    suspend fun onRejected(senderOpenId: String, decision: AccessDecision, content: String = "") {
        if (!enabled) return
        val profile = resolveProfile(senderOpenId)
        offer(
            SinkRecord.SenderStat(
                ts = clock(),
                senderOpenId = senderOpenId,
                userName = profile.name,
                avatarUrl = profile.avatarUrl,
                chatId = "",
                type = SenderStatsRecords.typeOf(decision),
                contentSummary = if (content.isEmpty()) "" else SenderStatsRecords.summarizeContent(content),
            ),
        )
    }

    /**
     * 解析完整身份（名字+头像；🔴 旁路语义：失败/为空一律留空，**绝不抛给主链路**）。
     * 双路降级：[profileResolver] 主路非空命中 ⇒ 一次解析双产出；
     * null / 抛异常 ⇒ 回落 [userNameResolver]（名字-only，头像空串）。
     */
    private suspend fun resolveProfile(openId: String): SenderProfile {
        val profile = runCatching { profileResolver(openId) }.getOrNull()
        if (profile != null) return profile
        // 降级路：名字-only（双链路防重复打 API 由装配侧单路化保证）
        val name = runCatching { userNameResolver(openId) }.getOrNull()?.takeIf { it.isNotBlank() }
        // 两路都解析不出 ⇒ 记一次失败（非 0 通常意味着应用未开通 UserNameResolver.CONTACT_BASE_SCOPE）
        if (name == null) nameUnresolvedTotal++
        return SenderProfile(name.orEmpty(), "")
    }

    private fun offer(record: SinkRecord.SenderStat) {
        collectedTotal++
        channels.offerStats(record)
    }
}
