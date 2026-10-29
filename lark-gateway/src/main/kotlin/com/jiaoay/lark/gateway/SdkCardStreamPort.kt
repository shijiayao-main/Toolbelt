// [CardStreamPort] 的唯一生产实现：cardkit v1 / im v1 的 BaseResponse → 纯 [CardCallOutcome] 翻译。
// 与 SdkLarkGateway 类零状态耦合（rawClient 经 clientProvider 每次现取，本文件零字段状态）；
// com.lark.oapi.* 的 import 只允许出现在本模块（LarkSdkIsolationTest 守护）。
package com.jiaoay.lark.gateway

import com.lark.oapi.Client
import com.lark.oapi.core.response.BaseResponse
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq
import com.lark.oapi.service.cardkit.v1.model.CreateCardReqBody
import com.lark.oapi.service.cardkit.v1.model.PatchCardElementReq
import com.lark.oapi.service.cardkit.v1.model.PatchCardElementReqBody
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReq
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReqBody
import com.lark.oapi.service.im.v1.model.CreateMessageReq
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [CardStreamPort] 的生产实现：把 cardkit v1 / im v1 的 BaseResponse 翻译成纯 [CardCallOutcome]。
 * 每个方法 suspend 且内部 withContext(netDispatcher)（NF-A4：cardkit 四步发起点在 bridge-net）。
 * 异常**不 catch**，直接传播给 sink 的 recordThrow（fromThrowableName）；业务码失败则返回 outcome 交 sink 的 check（fromLarkCode）。
 * 四步调用序列已编译验证；uuid 幂等字段不填（sequence 已是幂等键）。
 */
internal class SdkCardStreamPort(
    private val clientProvider: () -> Client?,
    private val netDispatcher: CoroutineDispatcher,
) : CardStreamPort {

    override suspend fun createCardEntity(cardJson: String): CardCallOutcome = withContext(netDispatcher) {
        val client = clientProvider() ?: throw IllegalStateException("gateway not connected: rawClient unavailable")
        val body = CreateCardReqBody.newBuilder().type("card_json").data(cardJson).build()
        val req = CreateCardReq.newBuilder().createCardReqBody(body).build()
        // 先落具体类型 resp（CreateCardResp）再取 data?.cardId：toOutcome 收 BaseResponse<*>，其 data 是星投影无 cardId 成员。
        val resp = client.cardkit().v1().card().create(req)
        resp.toOutcome(cardId = resp.data?.cardId)
    }

    override suspend fun sendCardByReference(contentJson: String, receiveId: String, receiveIdType: String): CardCallOutcome =
        withContext(netDispatcher) {
            val client = clientProvider() ?: throw IllegalStateException("gateway not connected: rawClient unavailable")
            // 🔴 S6 真机（build/sdk-probe/probe-card.log）：msgType="card" ⇒ 230001 http=400 "invalid msg_type: card"；
            //    msgType="interactive" ⇒ code=0。发送 card_id 引用的卡片实体，msg_type 必须是 "interactive"（非 "card"）。
            //    这也反证 SDK 自带 OutboundSender.sendCardByReference 走的是错误路径（且丢弃返回值静默失败），M3 自建 cardkit 调用 + 逐次查响应码正确。
            val body = CreateMessageReqBody.newBuilder().receiveId(receiveId).msgType("interactive").content(contentJson).build()
            val req = CreateMessageReq.newBuilder().receiveIdType(receiveIdType).createMessageReqBody(body).build()
            val resp = client.im().v1().message().create(req)
            resp.toOutcome(messageId = resp.data?.messageId)
        }

    override suspend fun patchElement(cardId: String, elementId: String, partialJson: String, sequence: Int): CardCallOutcome =
        withContext(netDispatcher) {
            val client = clientProvider() ?: throw IllegalStateException("gateway not connected: rawClient unavailable")
            val body = PatchCardElementReqBody.newBuilder().partialElement(partialJson).sequence(sequence).build()
            val req = PatchCardElementReq.newBuilder().cardId(cardId).elementId(elementId).patchCardElementReqBody(body).build()
            client.cardkit().v1().cardElement().patch(req).toOutcome()
        }

    override suspend fun finishSettings(cardId: String, settingsJson: String, sequence: Int): CardCallOutcome =
        withContext(netDispatcher) {
            val client = clientProvider() ?: throw IllegalStateException("gateway not connected: rawClient unavailable")
            val body = SettingsCardReqBody.newBuilder().settings(settingsJson).sequence(sequence).build()
            val req = SettingsCardReq.newBuilder().cardId(cardId).settingsCardReqBody(body).build()
            client.cardkit().v1().card().settings(req).toOutcome()
        }
}

/**
 * BaseResponse → 纯 [CardCallOutcome]（零 SDK 类型外泄）。
 * cardId/messageId 由调用点在**具体 Resp 类型**上提取后作值传入（`resp.data?.cardId`）：
 *    本扩展的接收者是 `BaseResponse<*>`，其 `data` 是星投影 `Any?`，无 cardId/messageId 成员，故不能在扩展内取。
 */
private fun BaseResponse<*>.toOutcome(
    cardId: String? = null,
    messageId: String? = null,
): CardCallOutcome = CardCallOutcome(
    success = success(),
    code = code,
    httpStatus = rawResponse?.statusCode,
    msg = msg,
    cardId = cardId,
    messageId = messageId,
    rateLimitResetSec = findRateLimitReset(this),
)

/**
 * 从响应头提取 x-ogw-ratelimit-reset（秒）。
 * S6 真机（build/s6-ratelimit.log）已确认：30 条文本 @约 2.3QPS，该响应头**全程缺失**（headerSeen=false），
 *    零 429 / 零 11232。印证 M1「SDK 完全不读该头」。本函数因此实际恒返回 null，退避走 RateGovernor 固定阶梯（防御性保留读取分支）。
 * internal 而非 private：SdkLarkGateway 出站/补查各成员的 RateLimited 信号与 [toOutcome] 共用同一提取口径
 *    （Kotlin 顶层 private 仅本文件可见，跨文件须 internal——两处口径分叉会让同一响应读出两个 reset 值）。
 */
internal fun findRateLimitReset(resp: BaseResponse<*>): Int? {
    val headers = resp.rawResponse?.headers ?: return null
    for ((k, v) in headers) {
        if (k.equals(Tuning.rateLimitResetHeader, ignoreCase = true)) return v?.firstOrNull()?.toIntOrNull()
    }
    return null
}
