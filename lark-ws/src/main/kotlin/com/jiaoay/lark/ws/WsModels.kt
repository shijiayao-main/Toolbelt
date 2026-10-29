// :lark-ws —— bootstrap / endpoint / 回执 的 JSON 模型（kotlinx.serialization；不碰 Gson）。
// 字段名逐一对照 oapi-sdk-java v2.8.5 源码 ws/model/*.java 的 @SerializedName（PascalCase 保留）。
package com.jiaoay.lark.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /callback/ws/endpoint` 请求体（ws/model/BootstrapRequest.java：PascalCase 三字段）。 */
@Serializable
data class WsBootstrapRequest(
    @SerialName("AppID") val appId: String,
    @SerialName("AppSecret") val appSecret: String,
    @SerialName("ClientAssertion") val clientAssertion: String = "",
)

/** endpoint 响应（ws/model/EndpointResp.java）。code：0=OK / 1=SYSTEM_BUSY / 1000040343=INTERNAL_ERROR。 */
@Serializable
data class WsEndpointResp(
    val code: Int,
    val msg: String? = null,
    val data: WsEndpoint? = null,
)

/** endpoint 数据体（ws/model/Endpoint.java——🔴 `URL`/`ClientConfig` 是 PascalCase key；改成 camelCase 即 required 字段读不到、bootstrap 解析直接抛）。 */
@Serializable
data class WsEndpoint(
    @SerialName("URL") val url: String,
    @SerialName("ClientConfig") val clientConfig: WsClientConfig? = null,
)

/** 服务端下发的连接参数（ws/model/ClientConfig.java——PascalCase；对照 SDK 默认值注释）。 */
@Serializable
data class WsClientConfig(
    @SerialName("ReconnectCount") val reconnectCount: Int? = null,
    @SerialName("ReconnectInterval") val reconnectInterval: Int? = null,
    @SerialName("ReconnectNonce") val reconnectNonce: Int? = null,
    @SerialName("PingInterval") val pingInterval: Int? = null,
)

/** DATA 帧回执 JSON（ws/model/Response.java——`data` 是 Base64 编码的 handler 返回）。 */
@Serializable
data class WsAckResponse(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val data: String? = null,
)

// ==================== 业务错误码（ws/Constant.java ErrorCode 对照）====================

/** endpoint/握手阶段业务码（对照 com.lark.oapi.ws.Constant 的 ErrorCode 常量）。 */
object WsEndpointCodes {
    const val OK = 0
    const val SYSTEM_BUSY = 1
    const val FORBIDDEN = 403
    const val AUTH_FAILED = 514
    const val INTERNAL_ERROR = 1000040343
    const val EXCEED_CONN_LIMIT = 1000040350

    /** 握手失败时响应 header 名（对照 Constant：HEADER_HANDSHAKE_*）。 */
    const val HEADER_HANDSHAKE_STATUS = "handshake-status"
    const val HEADER_HANDSHAKE_MSG = "handshake-msg"
    const val HEADER_HANDSHAKE_AUTH_ERRCODE = "handshake-autherrcode"
}
