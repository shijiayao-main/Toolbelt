// ④ Reply 阶段的**唯一出口**。
// 🔴 所有出站一律经本接口 ⇒ 一律经 RateGovernor（「RateGovernor 放行后才发起 SDK 调用」——绕过即频控失效、服务端限流惩罚）;
//    handler 内**不得**直接持有 MessageGateway。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.exec.SensitiveFilePolicy
import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.CardStreamSink
import com.jiaoay.lark.gateway.GatewayErrorCode
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.MessageGateway
import com.jiaoay.lark.gateway.Outbound
import com.jiaoay.lark.gateway.SendResult
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * 出站唯一出口（每消息一个实例，绑定一个 [target]）。
 *
 * **产物回传链**：[file]（文件上传出站）经网关契约扩展提供：
 * [OutboundKind.FILE] + [MessageGateway.uploadFile]（两步式：上传得 file_key → send msg_type=file）。
 * 本接口据此提供 [file]；[HandlerOutcome.attachments] 由产物上传接线真实写入。
 */
interface ReplySink {

    /** 目标会话（`LarkTarget.Chat` / `LarkTarget.User`）。 */
    val target: LarkTarget

    /** 频控用的 chatId（`LarkTarget.Chat.chatId`；User 目标为 null ⇒ 只受全局桶约束）。 */
    val chatId: String?

    /** 发纯文本（超 `GatewayLimits.textChunkLimit`=3500 的分片由网关负责，见 `SendResult.chunkMessageIds`）。 */
    suspend fun text(body: String): SendResult

    /** 发 markdown 文本。 */
    suspend fun markdown(body: String): SendResult

    /** 发卡片（若 `spec.streaming==true` 且带按钮会被 [CardJson.requireNonInteractiveStreaming] 拦下）。 */
    suspend fun card(spec: CardSpec): SendResult

    /**
     * 发文件消息（产物回传）：上传 [file]（经 RateGovernor）得 file_key → 再发 msg_type=file
     * 消息（再经 RateGovernor）。两步是**两次 API 调用**，各自过频控。
     * 失败返回 `SendResult(error=UploadFailed)`，绝不抛——调用方（ArtifactDelivery）按降级策略处理。
     *
     * @param file 本地文件（须已通过调用方沙箱/大小/扩展名校验）
     * @param displayName 飞书侧展示文件名；null 时取文件名
     */
    suspend fun file(file: Path, displayName: String? = null): SendResult

    /**
     * 开一条流式卡（`累积 → 卡片 → 飞书` 两行的起点）。
     * 🔴 [initial] 必须是**非交互**流式卡（200810）；确认/裁决请另发 [card]。
     * 🔴 调用方负责 `close()`（引擎的关停第 ④ 步会遍历未关的 sink 发终态卡并 close，网关侧另有兜底）。
     */
    fun stream(initial: CardSpec): CardStreamSink

    /** 表情回应（不经 RateGovernor 的令牌桶——它是轻量 API，但仍受全局退避窗口约束）。 */
    suspend fun react(messageId: String, emojiKey: String, add: Boolean = true): Boolean

    /**
     * 原位更新一条此前发出的**卡片消息**（answeredQuestionCard 已答卡语义）。
     *
     * 消费方：确认卡裁决后把原卡替换为已答态（按钮消失、标注裁决结果），消灭「原卡按钮滞留 + 新发文字回执」
     * 的状态脱节。🔴 更新失败**只降级不阻断**（调用方 log 后继续，行为回落到现状「文字回执」）——
     * 已答态是增强体验，不是裁决链路的一部分。
     *
     * **带默认实现**（返回失败 SendResult）：既有测试替身（RecordingReplySink / ThrowingReplySink 等）
     *    零改动、编译不破（与 [com.jiaoay.lark.gateway.MessageGateway.updateCard] 的默认实现先例同款）。
     *    真实实现见 [GatewayReplySink.updateExistingCard]。
     *
     * @param messageId 目标卡片消息 id（发送时 [SendResult.messageId]）
     * @param spec 整卡替换的新内容（旧卡交互按钮自然消失）
     */
    suspend fun updateExistingCard(messageId: String, spec: CardSpec): SendResult =
        SendResult(ok = false, messageId = null, error = GatewayErrorCode.SendFailed)

    // =====================================================================================
    // L1 三能力位（send_image / reply_in_thread / recallMessage）。
    // 全部**带默认实现**（失败返回）——既有测试替身零改动编译（updateExistingCard 同款先例）；
    //    「默认实现 = 能力缺席」，调用方（agent 工具）按失败降级，不炸主链路。真实实现见 [GatewayReplySink]。
    // =====================================================================================

    /**
     * 发图片（L1 send_image 的出站面）：两步式
     * `uploadImage → Outbound.image`（与 [file] 完全同构——两次 API 调用各过一次频控）。
     *
     * **带默认实现**（返回失败 SendResult）：既有测试替身零改动编译。
     *
     * @param source 本地图片文件路径（须已通过调用方围栏/敏感文件校验）
     * @return 成功返回 ok=true 的 SendResult；上传/发送失败返回失败结果，**绝不抛异常**
     */
    suspend fun image(source: Path): SendResult = SendResult(ok = false, messageId = null, error = GatewayErrorCode.UploadFailed)

    /**
     * 话题内回复（L1 reply_in_thread 的出站面）：群聊把回复发进话题
     * （保持讨论线索）。锚定语义与 [text] 统一——replyTo 由 send 通路统一注入，本成员只负责置
     * `replyInThread` 标记。
     *
     * **带默认实现**（返回失败 SendResult）：既有测试替身零改动编译。
     *
     * @param body 回复正文
     * @return 成功返回 ok=true 的 SendResult；失败返回失败结果，**绝不抛异常**
     */
    suspend fun replyInThread(body: String): SendResult = SendResult(ok = false, messageId = null, error = GatewayErrorCode.SendFailed)

    /**
     * 撤回机器人自己发出的消息（L1 recall_own_message 的出站面）。
     * 🔴 只能撤回**机器人自己**发的消息——他人消息平台侧恒无权限；工具面另有账本双重守护。
     *
     * **带默认实现**（返回 false）：既有测试替身零改动编译。
     *
     * @param messageId 目标消息 id（机器人发出时由 [SendResult.messageId] 返回）
     * @return 撤回成功 true；任何失败返回 false，**绝不抛异常**
     */
    suspend fun recallMessage(messageId: String): Boolean = false
}

/**
 * [ReplySink] 的网关实现。
 *
 * 🔴 **退避挂起在自有 net dispatcher，不阻塞 Default**：[guard] 整体包在
 * `withContext(netDispatcher)` 里 ⇒ RateGovernor 的 `delay` 与 SDK 的阻塞 IO 都落在 `bridge-net` 池（8 槽），
 * 既不阻塞 intake（`Dispatchers.Default` 上的单协程），也不污染 commonPool。
 * 网关的 `send()` 内部**已**做过一次 `withContext(netDispatcher)`；
 *    这里再包一层是**幂等**的（同一 dispatcher 的 withContext 不会二次切线程），
 *    目的是让 **RateGovernor 的挂起**也在 net 池——网关管不到这一段。
 *
 * @param rateGovernor 频控器（全局 + 每 chat 双桶）。
 * @param netDispatcher `BridgeDispatchers.net`（构造注入，测试可换成 StandardTestDispatcher）。
 * @param onRateLimited 收到 `RateLimited` 时的旁路回调（T9 转发成 `GatewaySignal` / UI 提示）。
 */
class GatewayReplySink(
    private val gateway: MessageGateway,
    override val target: LarkTarget,
    private val rateGovernor: RateGovernor,
    private val netDispatcher: CoroutineDispatcher,
    private val log: BridgeLog = BridgeLog.Silent,
    // uploadMemo（上传 file_key 记忆账本）已退役删除：上行恒走「上传 → send file_key」两步。
    private val onRateLimited: ((chatId: String?, resetSec: Int?) -> Unit)? = null,
    /** 引用锚定：本 sink 的全部出站回复此消息（飞书引用气泡定位「谁的提问」）。
     *  null = 不锚定（私聊豁免——主聊引用样式冗余；构造点按 chatType==P2P 条件传 null）。 */
    private val replyToMessageId: String? = null,
    /** 宿主机密根（文本出站面敏感过滤的判据输入，装配点传 `deps.dataRootProvider()` 与文件通道同源；
     *  null = 机密根判据跳过——默认值 ⇒ 既有测试替身/构造点零破坏）。 */
    private val homeDataRoot: java.io.File? = null,
) : ReplySink {

    override val chatId: String? get() = (target as? LarkTarget.Chat)?.chatId

    // 文本出站面敏感过滤：text/markdown/card/replyInThread 四成员、流式卡三面与已答卡更新
    //    （updateExistingCard 承载确认卡正文回显——inputSummary 属真实出站文本）在
    //    [send]/[stream]/[updateCard] 前统一过 [SensitiveFilePolicy.sanitizeOutboundText]
    //    （打码是内容变换不是交付失败——SendResult.ok 语义不变；file/image 是文件通道
    //    已有文件面围栏，react/recallMessage 无文本面，均不过本口）。
    private fun sanitizeText(body: String): String =
        SensitiveFilePolicy.sanitizeOutboundText(body, homeDataRoot)

    /**
     * 卡片文本面过滤（title + markdown 双面）。
     *
     * API 级缺口登记：[CardSpec] 的 summary 与 buttons 两字段**不在打码面**——当前生产
     * 路径安全依据：summary 缺省由网关从已打码 markdown 前 80 字派生（CardSpec.summary
     * 为 null 时的取值语义），带按钮卡走确认卡装配（按钮只承载选项 id 与固定文案，不承载
     * 用户内容）。未来出现动态 summary 生产点（不经 markdown 派生）或按钮文案承载用户
     * 内容时，打码面必须同步扩到该两字段——遗漏即同宽旁路（与
     * outbound-text-sensitive-filter 提案 design.md「出站单写点收口」节的缺口登记互为双锚）。
     */
    private fun sanitizeCard(spec: CardSpec): CardSpec {
        val title = sanitizeText(spec.title)
        val markdown = sanitizeText(spec.markdown)
        return if (title === spec.title && markdown === spec.markdown) spec
        else spec.copy(title = title, markdown = markdown)
    }

    override suspend fun text(body: String): SendResult = send(Outbound.text(sanitizeText(body)))

    override suspend fun markdown(body: String): SendResult = send(Outbound.markdown(sanitizeText(body)))

    override suspend fun card(spec: CardSpec): SendResult =
        send(Outbound.card(CardJson.requireNonInteractiveStreaming(sanitizeCard(spec))))

    override suspend fun file(file: Path, displayName: String?): SendResult = withContext(netDispatcher) {
        // 两步各过一次 RateGovernor（上传 + 发消息是两次 API 调用）：
        //    1) 上传得 file_key（失败 ⇒ UploadFailed 返回，不抛）；
        //    2) 发 msg_type=file 消息（复用统一 send 通路，含限流退避与失败日志）。
        // 🔴 P2 修复：上传步**不走 guard**——guard 的 onRateLimited 回调基于返回值判限流，
        //    uploadFile 返回 String? 无法区分「失败种类」，恒 null 回调会让 guard 误调 notifySuccess()
        //    （清空在途退避窗口并重置阶梯）。改为 acquire 计点 + 直调；限流识别由网关侧
        //    SdkLarkGateway.uploadFile 的 GatewaySignal.RateLimited 双信号驱动（镜像 LarkCardStreamSink.check）。
        val name = displayName ?: file.fileName?.toString().orEmpty()
        rateGovernor.acquire(chatId)
        val fileKey = gateway.uploadFile(file, displayName)
        if (fileKey == null) {
            log.warn("产物上传失败 target=$target file=$file")
            SendResult(ok = false, messageId = null, error = GatewayErrorCode.UploadFailed)
        } else {
            send(Outbound.file(fileKey, name))
        }
    }

    override suspend fun image(source: Path): SendResult = withContext(netDispatcher) {
        // 逐字镜像 [file] 的实现纪律（L1 send_image 出站面）：
        //    两步各过一次 RateGovernor（uploadImage + send 是两次 API 调用）；
        //    上传步**不走 guard**（同款理由：uploadImage 返回 String? 无法区分失败种类，恒 null
        //    回调会让 guard 误调 notifySuccess() 清空在途退避窗口；限流识别由网关侧
        //    SdkLarkGateway.uploadImage 的 GatewaySignal.RateLimited 双信号驱动）。
        rateGovernor.acquire(chatId)
        val imageKey = gateway.uploadImage(source)
        if (imageKey == null) {
            log.warn("图片上传失败 target=$target file=$source")
            SendResult(ok = false, messageId = null, error = GatewayErrorCode.UploadFailed)
        } else {
            send(Outbound.image(imageKey))
        }
    }

    override suspend fun replyInThread(body: String): SendResult =
        // 话题标记置位后走统一 send 通路——锚定（replyToMessageId）由 send 的一次 copy 统一注入
        // （保留 out.replyInThread：copy 只覆写 replyToMessageId，话题标记原样透传到网关层）。
        send(Outbound.text(sanitizeText(body)).copy(replyInThread = true))

    override suspend fun recallMessage(messageId: String): Boolean = withContext(netDispatcher) {
        // 与 react 同款纪律：撤回不是新出站，不占令牌桶（同 [MessageGateway.recallMessage]
        //    的契约注释），但退避窗口内仍要等——限流期间继续打 API 只会延长退避。
        while (rateGovernor.isBackingOff) {
            kotlinx.coroutines.delay(rateGovernor.backoffRemainingMillis.coerceAtLeast(1L))
        }
        gateway.recallMessage(messageId)
    }

    override fun stream(initial: CardSpec): CardStreamSink =
        // 流式卡文本面（受理卡/运行帧/终态卡）：渲染源（ProgressCardRenderer 消费的是未过滤的
        //    agent 原始事件文本）不在本口射程内，过滤收口在出站点——装饰网关 sink，使全部
        //    上卡文本（initial/tryReplace 帧/flushTerminal 终态卡）过同一文本过滤入口。
        SanitizingCardStreamSink(
            delegate = gateway.openCardStream(target, CardJson.requireNonInteractiveStreaming(sanitizeCard(initial))),
            sanitizeText = ::sanitizeText,
            sanitizeCard = ::sanitizeCard,
        )

    override suspend fun react(messageId: String, emojiKey: String, add: Boolean): Boolean =
        withContext(netDispatcher) {
            // 表情回应不占令牌桶（轻量），但退避窗口内仍要等：避免限流期间继续打 API。
            while (rateGovernor.isBackingOff) {
                kotlinx.coroutines.delay(rateGovernor.backoffRemainingMillis.coerceAtLeast(1L))
            }
            gateway.setReaction(messageId, emojiKey, add)
        }

    override suspend fun updateExistingCard(messageId: String, spec: CardSpec): SendResult =
        withContext(netDispatcher) {
            // 与 react 同款纪律：更新已有消息不是新出站，不占令牌桶（同 [MessageGateway.updateCard]
            //    的契约注释），但退避窗口内仍要等——限流期间继续打 API 只会延长退避。
            // 已答卡正文（确认卡裁决后的 inputSummary 回显）属真实出站文本——经 [sanitizeCard]
            //    与其余文本成员同一过滤入口。
            while (rateGovernor.isBackingOff) {
                kotlinx.coroutines.delay(rateGovernor.backoffRemainingMillis.coerceAtLeast(1L))
            }
            gateway.updateCard(messageId, sanitizeCard(spec)).also { result ->
                if (!result.ok) {
                    log.warn("卡片更新失败 id=$messageId code=${result.error} lark=${result.larkCode} msg=${result.larkMsg}")
                }
            }
        }

    private suspend fun send(out: Outbound): SendResult = withContext(netDispatcher) {
        // 引用锚定：sink 级一次 copy——text/markdown/card/file 四条链
        //    （全部经本 send）统一继承锚定；Outbound.replyToMessageId 此前是死字段（消费方在 SdkLarkGateway、
        //    零生产者）。显式传入的锚定优先（?: 保已有值，理论不可达——工厂不填）。
        val anchored = if (replyToMessageId != null) out.copy(replyToMessageId = replyToMessageId) else out
        rateGovernor.guard(
            chatId = chatId,
            block = { gateway.send(target, anchored) },
            // 🔴 返回非 null 即视为「本次出站被限流」，RateGovernor.guard 会据此调 notifyRateLimited。
            //    `SendResult` **不携带** `x-ogw-ratelimit-reset` 头值（网关只把它透传成
            //    `GatewaySignal.RateLimited.resetSec`）⇒ 这里恒返 null，即「头缺失 ⇒ 固定阶梯 1s→2s→4s，上限 30s」。
            onRateLimited = { result ->
                if (result.error == GatewayErrorCode.RateLimited || result.larkCode == LARK_CODE_RATE_LIMITED) {
                    if (onRateLimited != null) onRateLimited.invoke(chatId, null)
                    NO_RESET_HEADER
                } else {
                    null
                }
            },
        ).also { result ->
            if (!result.ok) {
                log.warn(
                    "出站失败 target=$target code=${result.error} larkCode=${result.larkCode} msg=${result.larkMsg}",
                )
            }
        }
    }

    companion object {
        /** HTTP 429（飞书整点压力限流的传输层表现）。 */
        const val HTTP_TOO_MANY_REQUESTS: Int = 429

        /** 飞书业务码 11232（触发退避）。 */
        const val LARK_CODE_RATE_LIMITED: Int = 11232

        /** 哨兵值：「限流已识别，但无 reset 头可读」⇒ RateGovernor 走固定阶梯。 */
        private const val NO_RESET_HEADER: Int = -1
    }
}

/**
 * 流式卡出站点的文本过滤装饰器（[GatewayReplySink.stream] 专属）。
 *
 * ## 为什么装饰在 sink 而不是渲染源
 * 渲染源（ProgressCardRenderer / ProgressAccumulator）消费的是 agent **原始事件文本**
 * （Thinking/ToolUse/AssistantText 片段——未过任何判据），「渲染源恒来自已过滤源」不成立；
 * 而全部上卡文本的必经点是本装饰器（受理卡 initial、运行帧 tryReplace、终态卡 flushTerminal
 * 三面全量）——收口在此即全覆盖，未来新增流式卡消费者天然继承。
 *
 * 全部成员纯委托（isOpen/segmentIndex/close/onFinished 透传网关 sink，openStreams 登记与
 * 收尾链不受装饰影响）。
 */
private class SanitizingCardStreamSink(
    private val delegate: CardStreamSink,
    private val sanitizeText: (String) -> String,
    private val sanitizeCard: (CardSpec) -> CardSpec,
) : CardStreamSink {

    override val isOpen: Boolean get() = delegate.isOpen

    override val segmentIndex: Int get() = delegate.segmentIndex

    override fun tryReplace(text: String): Boolean = delegate.tryReplace(sanitizeText(text))

    override suspend fun flushTerminal(card: CardSpec) = delegate.flushTerminal(sanitizeCard(card))

    override suspend fun close() = delegate.close()

    override fun onFinished(callback: (() -> Unit)?) = delegate.onFinished(callback)
}

/**
 * 未命中兜底出站（⑤ Observe 之前的一步）：把给定文本发出。
 * 现载荷 = 非 TEXT 未支持 kind 的显式指引文案（真源 [unsupportedKindNotice]）；
 * TEXT 未命中恒静默，不进本函数。
 *
 * **静默语义**：文本为空或全空白 ⇒ **静默**（返回 false，不发任何消息）。
 * 历史载荷「`unhandledReply` 兜底回复模板」配置域已删除（TEXT 未命中改由调度 agent 承接）；
 * 本函数保留，仅因非 TEXT 指引文案仍需同一出站通道。
 *
 * @return true 表示确实发了一条兜底回复。
 */
suspend fun ReplySink.replyUnhandled(template: String): Boolean {
    if (template.isBlank()) return false
    markdown(template)
    return true
}
