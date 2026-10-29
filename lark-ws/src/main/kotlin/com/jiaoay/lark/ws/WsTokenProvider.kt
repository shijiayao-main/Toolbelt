// :lark-ws —— tenant_access_token 获取 + 内存缓存（供 OpenAPI HTTP 调用复用）。
// 对照 oapi-sdk-java v2.8.5：core/token/TokenManager.getInternalTenantAccessToken +
// core/cache/LocalCache——缓存写入 `expire - 300s`（源码 expiryDeltaOfSecond）。
// 长连接本身不消费 token（bootstrap 请求体不带 Authorization）；本 provider
//    只服务后续接线批：gateway 的下载/补查/上传 raw HTTP 调用借此替换「自建 Config 留存凭据」。
package com.jiaoay.lark.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** `POST /open-apis/auth/v3/tenant_access_token/internal` 响应。 */
@Serializable
private data class TenantTokenResp(
    val code: Int = -1,
    val msg: String? = null,
    @SerialName("tenant_access_token") val tenantAccessToken: String? = null,
    val expire: Int = 0,
)

/**
 * tenant token 提供者：获取 + 进程内缓存（单飞）。
 * HTTP 传输经 [httpPost] 注入缝——生产传 JDK HttpClient 阻塞实现，测试传 lambda（零网络）。
 * F 组纪律：无 object / 顶层可变态；缓存值经 [AtomicReference]（零 @Volatile var）。
 */
class WsTokenProvider(
    private val domain: String,
    private val appId: String,
    private val appSecret: String,
    /** 注入缝：`suspend (url, jsonBody) -> 响应体文本`。 */
    private val httpPost: suspend (String, String) -> String,
    /** 时钟注入（测试虚拟时间）；默认 System::currentTimeMillis。 */
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val cached = AtomicReference<CachedToken?>(null)
    private val lock = Mutex()

    /** 取有效 token；无缓存/过期/获取失败时发起新请求。失败返回 null（旁路语义，不抛）。 */
    suspend fun tenantTokenOrNull(): String? {
        cached.get()?.let { if (it.validAt(clock())) return it.token }
        // Mutex 单飞：锁外快检 + 锁内双检与取数——fetch 的挂起点**在锁内**（单飞语义的
        // 代价：并发调用方等锁而非旁路，首个进入者取数、其余等锁后命中缓存）
        return lock.withLock {
            cached.get()?.let { if (it.validAt(clock())) return it.token }
            fetch()?.also { cached.set(it) }?.token
        }
    }

    private suspend fun fetch(): CachedToken? = runCatching {
        val body = """{"app_id":${json.encode(appId)},"app_secret":${json.encode(appSecret)}}"""
        val respText = httpPost("$domain$TOKEN_PATH", body)
        val resp = json.decodeFromString(TenantTokenResp.serializer(), respText)
        if (resp.code != 0 || resp.tenantAccessToken.isNullOrEmpty()) return@runCatching null
        // 源码语义：缓存 expire - expiryDelta（300s 前置失效，防边界过期）；下限 60s 防负值
        val ttlSec = max(resp.expire - EXPIRY_DELTA_SEC, MIN_TTL_SEC)
        CachedToken(resp.tenantAccessToken, clock() + ttlSec * 1000)
    }.getOrNull()

    /** 主动作废（如 401 重试场景）。 */
    fun invalidate() {
        cached.set(null)
    }

    private data class CachedToken(val token: String, val expireAtMillis: Long) {
        fun validAt(now: Long): Boolean = now < expireAtMillis
    }

    companion object {
        private const val TOKEN_PATH = "/open-apis/auth/v3/tenant_access_token/internal"

        /** 源码 TokenManager.expiryDeltaOfSecond = 300（秒）。 */
        private const val EXPIRY_DELTA_SEC = 300
        private const val MIN_TTL_SEC = 60

        private fun Json.encode(s: String): String =
            kotlinx.serialization.json.JsonPrimitive(s).toString()
    }
}
