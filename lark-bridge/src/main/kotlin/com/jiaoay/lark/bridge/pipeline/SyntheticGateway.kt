// SyntheticGateway——「dry-run 与测试共用替身」。
// 放 main 源集：CLI 的 `handle-event` 子命令（--dry-run）与各测试共用同一个替身。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.CardStreamSink
import com.jiaoay.lark.gateway.ChatCreation
import com.jiaoay.lark.gateway.ChatHistoryItem
import com.jiaoay.lark.gateway.ChatHistoryPage
import com.jiaoay.lark.gateway.ChatInfo
import com.jiaoay.lark.gateway.ChatMemberOpResult
import com.jiaoay.lark.gateway.ChatMembersPage
import com.jiaoay.lark.gateway.GatewayConnectResult
import com.jiaoay.lark.gateway.GatewayConnectionState
import com.jiaoay.lark.gateway.GatewayErrorCode
import com.jiaoay.lark.gateway.GatewayException
import com.jiaoay.lark.gateway.GatewaySignal
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkCredential
import com.jiaoay.lark.gateway.LarkPolicy
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.MessageGateway
import com.jiaoay.lark.gateway.Outbound
import com.jiaoay.lark.gateway.DownloadResult
import com.jiaoay.lark.gateway.ReactionBrief
import com.jiaoay.lark.gateway.ReadReceiptPage
import com.jiaoay.lark.gateway.SendResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

/** 一次 `send()` 的记录。 */
data class RecordedSend(val target: LarkTarget, val out: Outbound, val atMillis: Long) {
    /** 便于断言的便捷视图：纯文本/markdown 正文。 */
    val text: String? get() = out.text
    val card: CardSpec? get() = out.card
}

/** 一次 `setReaction()` 的记录。 */
data class RecordedReaction(val messageId: String, val emojiKey: String, val add: Boolean)

/** 一次 `downloadResource()` 的记录。 */
data class RecordedDownload(val messageId: String, val fileKey: String, val dest: Path)

/** 一次 `uploadFile()` 的记录。 */
data class RecordedUpload(val source: Path, val displayName: String?)

/** 一次 `updateCard()` 的记录。 */
data class RecordedCardUpdate(val messageId: String, val spec: CardSpec)

/** 一次 `recallMessage()` 的记录。 */
data class RecordedRecall(val messageId: String)

/** 一次 `uploadImage()` 的记录。 */
data class RecordedImageUpload(val source: Path)

/** 一次 `createChat()` 的记录。 */
data class RecordedChatCreation(val name: String, val memberOpenIds: List<String>)

/** 一次 `inviteChatMembers()` 的记录。 */
data class RecordedChatInvite(val chatId: String, val openIds: List<String>)

/**
 * [CardStreamSink] 的替身：记录每一次 replace / 终态卡 / close，**不**做任何节流与字节预算轮换
 * （那是 `:lark-gateway` 的 `LarkCardStreamSink` 的职责，已由网关自己的 `CardStreamTest` 覆盖）。
 * 滚动窗口语义：与生产 sink 同形——tryReplace 全量覆盖，`latest()` 返回最新快照。
 * onFinished 与生产实现同语义（恰一次）——本类在生产树里，Synthetic 网关下
 *    TrackingReplySink 同样登记 openStreams，摘除链不能是假实现。
 */
class SyntheticCardStream(
    val target: LarkTarget,
    val initial: CardSpec,
    private val onReplace: ((String) -> Unit)? = null,
) : CardStreamSink {

    private val open = AtomicInteger(1)
    private val replaces = CopyOnWriteArrayList<String>()
    private val terminalRef = AtomicReference<CardSpec?>(null)
    private val closeCount = AtomicInteger(0)
    private val segment = AtomicInteger(1)
    private val finished = com.jiaoay.lark.gateway.OnceCallback()

    override val isOpen: Boolean get() = open.get() == 1

    override fun tryReplace(text: String): Boolean {
        if (!isOpen) return false
        replaces.add(text)
        onReplace?.invoke(text)
        return true
    }

    override suspend fun flushTerminal(card: CardSpec) {
        terminalRef.set(card)
        open.set(0)
        finished.fire()
    }

    override suspend fun close() {
        closeCount.incrementAndGet()
        if (open.getAndSet(0) == 1 && terminalRef.get() == null) {
            // 平移网关语义：未发终态卡就 close ⇒ 用当前快照补一次终态（幂等）
            terminalRef.set(initial.copy(markdown = latest()))
        }
        finished.fire()
    }

    override val segmentIndex: Int get() = segment.get()

    /** 与生产 [com.jiaoay.lark.gateway.LarkCardStreamSink] 同语义（恰一次）。 */
    override fun onFinished(callback: (() -> Unit)?) {
        finished.set(callback)
    }

    /** 最新一帧快照（滚动窗口语义：恒等于最后一次 tryReplace 的全文）。 */
    fun latest(): String = replaces.lastOrNull() ?: ""

    /** 终态卡（null ⇒ 尚未发终态）。 */
    val terminal: CardSpec? get() = terminalRef.get()

    /** replace 次数。 */
    val replaceCount: Int get() = replaces.size

    /** 全部替换帧（按序）。 */
    val frames: List<String> get() = replaces.toList()

    /** close 调用次数（幂等性断言用）。 */
    val closedTimes: Int get() = closeCount.get()

    /** 模拟一次分段轮换（测试 `StreamSegmentRotated` 链路用）。 */
    fun rotateSegment() {
        segment.incrementAndGet()
        replaces.clear()
    }
}

/**
 * 不依赖飞书 SDK 的 [MessageGateway] 替身（**dry-run 与测试共用**）。
 *
 * ## 为什么必须有它
 * 1. **自动化测试**需要一个可编程、可断言的网关：真连飞书既不可能在 CI 里做，也无法制造
 *    「限流」「分段轮换」「连接被对端实例占用」这些边界。
 * 2. **CLI 的 `handle-event --dry-run`** 要在**不连网**的前提下把一条合成事件跑完五阶段，
 *    让用户离线验证路由/准入/回复是否符合预期。
 * ⇒ 两者共用同一个 main 源集替身，避免重复造轮子。
 *
 * ## 契约完整性
 * 实现 [MessageGateway] 全部 **29 个成员**（含 4+11 个带默认实现的扩展成员），一个不少
 * （编译期强制：接口新增成员时本文件红构建；SyntheticGatewayTest 反射穷尽断言守护名单）。
 * 与真网关的行为差异只有两处，且都是刻意的：
 * - `send()` **不**做 3500 字分片（那是网关职责）；
 * - `openCardStream()` 返回的 [SyntheticCardStream] **不**做节流与 24KB 预算轮换（同上）。
 *
 * ## 可编程注入面（全部 `AtomicReference`：零静态可变状态）
 * | 注入点 | 用途 |
 * |---|---|
 * | [emitInbound] / [emitAll] | 灌入合成入站消息 |
 * | [emitSignal] | 灌入 `Rejected` / `RateLimited` / `Dropped` 等信号 |
 * | [setState] | 直接摆连接态（测 UI 的 6 态渲染） |
 * | [connectResult] | `connect()` 的返回（Connected / Failed / BlockedByPeerInstance） |
 * | [sendResult] / [sendHandler] | `send()` 的返回；handler 可按 out 内容动态决定（测限流） |
 * | [sendDelayMillis] | `send()` 的挂起时长（测「频控挂起不阻塞 intake」） |
 * | [downloadResult] / [reactionResult] | 两个幂等成员的返回 |
 * | [quotedMessageResult] / [recordedQuotedFetches] | `fetchQuotedMessage()` 的返回与调用记录（引用即上下文；null = 未取回落落 hint） |
 * | [updateCardResult] | `updateCard()` 的返回（默认成功） |
 * | [recallResult] / [uploadImageResult] | 撤回 / 图片上传的成败开关 |
 * | [listMessagesResult] / [getChatInfoResult] / [listChatMembersResult] / [createChatResult] / [inviteChatMembersResult] / [listReactionsResult] / [readReceiptsResult] | 七个查询/操作型成员的可编程返回（默认 null = 能力缺席） |
 * | [wikiNodeResult] / [documentTextResult] | wiki/docx 两成员的可编程返回（默认 null = 能力缺席） |
 * | [registerHandler] | `registerApp()` 的行为（默认回一个合成凭据） |
 *
 * ## 出站记录面（供断言）
 * [recordedSends] / [recordedReactions] / [recordedDownloads] / [recordedCardUpdates] / [recordedStreams] /
 * [recordedPolicies] / [recordedCredentials] / [recordedRecalls] / [recordedImageUploads] /
 * [recordedChatCreations] / [recordedChatInvites] / [connectCount] / [disconnectCount] / [closeCount]。
 *
 * @param inboundCapacity 入站通道容量（默认与 `InboundQueue.CAPACITY` 同值 512，DROP_OLDEST）。
 * @param signalCapacity 信号通道容量（默认 256，与网关 `Tuning.signalChannelCapacity` 同值）。
 */
class SyntheticGateway(
    private val inboundCapacity: Int = INBOUND_CAPACITY,
    signalCapacity: Int = SIGNAL_CAPACITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : MessageGateway {

    private val inboundChannel = Channel<InboundMessage>(inboundCapacity, BufferOverflow.DROP_OLDEST)
    private val signalChannel = Channel<GatewaySignal>(signalCapacity, BufferOverflow.DROP_OLDEST)

    // 入站占用计数：DROP_OLDEST 的 trySend 恒成功，无法从返回值判丢弃 ⇒ 与 SdkLarkGateway.offerInbound
    //    同法用占用计数推断（见 gateway-contract.md「DROP_OLDEST 丢弃可观测」节）。替身必须与生产同形，否则 dry-run 与测试都走不到 Dropped 分支。
    private val inboundOccupancy = AtomicInteger(0)
    private val stateFlow = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)

    private val sends = CopyOnWriteArrayList<RecordedSend>()
    private val reactions = CopyOnWriteArrayList<RecordedReaction>()
    private val downloads = CopyOnWriteArrayList<RecordedDownload>()
    private val uploads = CopyOnWriteArrayList<RecordedUpload>()
    private val cardUpdates = CopyOnWriteArrayList<RecordedCardUpdate>()
    private val recalls = CopyOnWriteArrayList<RecordedRecall>()
    private val imageUploads = CopyOnWriteArrayList<RecordedImageUpload>()
    private val chatCreations = CopyOnWriteArrayList<RecordedChatCreation>()
    private val chatInvites = CopyOnWriteArrayList<RecordedChatInvite>()
    private val streams = CopyOnWriteArrayList<SyntheticCardStream>()
    private val policies = CopyOnWriteArrayList<LarkPolicy>()
    private val credentials = CopyOnWriteArrayList<LarkCredential>()

    private val connectResultRef =
        AtomicReference<GatewayConnectResult>(GatewayConnectResult.Connected(DEFAULT_BOT_OPEN_ID, DEFAULT_BOT_NAME))
    private val sendResultRef = AtomicReference(SendResult(ok = true, messageId = DEFAULT_MESSAGE_ID))
    private val sendHandlerRef = AtomicReference<((LarkTarget, Outbound) -> SendResult)?>(null)
    private val sendDelayRef = AtomicLong(0L)
    private val downloadResultRef = AtomicReference(true)
    private val downloadErrorRef = AtomicReference<GatewayErrorCode?>(null)
    private val reactionResultRef = AtomicReference(true)
    private val updateCardResultRef = AtomicReference(SendResult(ok = true, messageId = null))
    private val quotedMessageRef = AtomicReference<com.jiaoay.lark.gateway.QuotedMessage?>(null)
    private val quotedFetches = CopyOnWriteArrayList<String>()
    private val downloadPayloadRef = AtomicReference<ByteArray?>(null)
    private val uploadFileResultRef = AtomicBoolean(true)
    private val recallResultRef = AtomicBoolean(true)
    private val uploadImageResultRef = AtomicBoolean(true)
    private val listMessagesResultRef = AtomicReference<ChatHistoryPage?>(null)
    private val getChatInfoResultRef = AtomicReference<ChatInfo?>(null)
    private val historyPulls = CopyOnWriteArrayList<Triple<String, Int, String?>>()
    private val chatInfoLookups = CopyOnWriteArrayList<String>()
    private val listChatMembersResultRef = AtomicReference<ChatMembersPage?>(null)
    private val createChatResultRef = AtomicReference<ChatCreation?>(null)
    private val inviteChatMembersResultRef = AtomicReference<ChatMemberOpResult?>(null)
    private val listReactionsResultRef = AtomicReference<List<ReactionBrief>?>(null)
    private val readReceiptsResultRef = AtomicReference<ReadReceiptPage?>(null)
    // wiki/docx 两成员的可编程返回与调用记录（L2 能力面）。
    private val wikiNodeResultRef = AtomicReference<com.jiaoay.lark.gateway.WikiNodeRef?>(null)
    private val wikiNodeLookups = CopyOnWriteArrayList<String>()
    private val documentTextResultRef = AtomicReference<String?>(null)
    private val documentTextLookups = CopyOnWriteArrayList<String>()
    // resolveSenderProfile 的替身返回（lookupUser 工具链测试用；此前接口默认实现恒 null 不可编程）
    private val senderProfileResultRef = AtomicReference<com.jiaoay.lark.gateway.SenderProfile?>(null)
    private val registerHandlerRef =
        AtomicReference<(suspend (onQr: (String, Int) -> Unit) -> LarkCredential)?>(null)

    private val connects = AtomicInteger(0)
    private val disconnects = AtomicInteger(0)
    private val closes = AtomicInteger(0)
    private val inboundEmits = AtomicLong(0)
    private val inboundDrops = AtomicLong(0)
    private val signalEmits = AtomicLong(0)

    // ------------------------------------------------------------------ MessageGateway 12 成员

    override val connectionState: StateFlow<GatewayConnectionState> get() = stateFlow

    // 自定义 flow 而非 receiveAsFlow：接收时递减占用计数，与 emitInbound 的递增配对（与 SdkLarkGateway 同形）。
    override val inbound: Flow<InboundMessage> = flow {
        for (m in inboundChannel) {
            inboundOccupancy.decrementAndGet()
            emit(m)
        }
    }

    override val signals: Flow<GatewaySignal> = signalChannel.receiveAsFlow()

    override suspend fun connect(cred: LarkCredential, policy: LarkPolicy): GatewayConnectResult {
        connects.incrementAndGet()
        credentials.add(cred)
        policies.add(policy)
        stateFlow.value = GatewayConnectionState.Connecting
        val result = connectResultRef.get()
        stateFlow.value = when (result) {
            is GatewayConnectResult.Connected -> GatewayConnectionState.Connected(result.botOpenId, result.botName)
            is GatewayConnectResult.Failed -> GatewayConnectionState.Disconnected(result.error, result.larkMsg ?: "")
            GatewayConnectResult.BlockedByPeerInstance -> GatewayConnectionState.BlockedByPeerInstance
        }
        return result
    }

    override suspend fun disconnect() {
        disconnects.incrementAndGet()
        stateFlow.value = GatewayConnectionState.Idle
    }

    override suspend fun updatePolicy(policy: LarkPolicy) {
        policies.add(policy)
    }

    override suspend fun send(target: LarkTarget, out: Outbound): SendResult {
        val delayMillis = sendDelayRef.get()
        if (delayMillis > 0L) delay(delayMillis)
        sends.add(RecordedSend(target, out, clock()))
        val handler = sendHandlerRef.get()
        val result = handler?.invoke(target, out) ?: sendResultRef.get()
        if (!result.ok) {
            // 平移真网关语义：发送失败同时 emit 一条 SendFailed 信号（可观测性一致）
            emitSignal(GatewaySignal.SendFailed(result.error ?: GatewayErrorCode.SendFailed, result.larkMsg ?: "", chatIdOf(target)))
        }
        return result
    }

    override fun openCardStream(target: LarkTarget, initial: CardSpec): CardStreamSink {
        val sink = SyntheticCardStream(target, initial)
        streams.add(sink)
        return sink
    }

    override suspend fun downloadResource(messageId: String, fileKey: String, dest: Path): DownloadResult {
        downloads.add(RecordedDownload(messageId, fileKey, dest))
        val ok = downloadResultRef.get()
        if (ok) {
            val payload = downloadPayloadRef.get() ?: DEFAULT_DOWNLOAD_PAYLOAD.toByteArray(Charsets.UTF_8)
            runCatching {
                dest.parent?.let { Files.createDirectories(it) }
                Files.write(dest, payload)
            }
        }
        // 失败时透传注入的错误码；未注入 ⇒ error=null（合成失败不虚构错误码，resolver 回落通用文案）
        return if (ok) DownloadResult.OK else DownloadResult(false, downloadErrorRef.get(), null, "synthetic")
    }

    /** 引用补查（引用即上下文）：记录调用并返回 [quotedMessageResult]（默认 null = 未取回）。 */
    override suspend fun fetchQuotedMessage(messageId: String): com.jiaoay.lark.gateway.QuotedMessage? {
        quotedFetches.add(messageId)
        return quotedMessageRef.get()
    }

    /**
     * 文件上传（产物回传链）：记录调用并返回可编程结果。
     * 默认「合成 file_key」模拟上传成功；设 [uploadFileResult] = null 模拟失败。
     */
    override suspend fun uploadFile(source: Path, displayName: String?): String? {
        uploads.add(RecordedUpload(source, displayName))
        val ok = uploadFileResultRef.get()
        return if (ok) {
            "file_key_synthetic_${uploads.size}"
        } else {
            null
        }
    }

    override suspend fun setReaction(messageId: String, emojiKey: String, add: Boolean): Boolean {
        reactions.add(RecordedReaction(messageId, emojiKey, add))
        return reactionResultRef.get()
    }

    /**
     * 卡片原位更新：记录调用并返回可编程结果。
     * 默认成功（ok=true，messageId 回显入参——已答态链的默认路径是「更新成功」）；
     * 设 [updateCardResult] = 失败 SendResult 模拟更新失败（裁决链降级路径）。
     */
    override suspend fun updateCard(messageId: String, spec: CardSpec): SendResult {
        cardUpdates.add(RecordedCardUpdate(messageId, spec))
        return updateCardResultRef.get()
    }

    // ------------------------------------------------------------------ L1 能力 9 个新成员的替身实现
    // 全部对齐 updateCard / uploadFile 先例：记录调用 + 可编程返回。

    /** 用户完整身份（替身实现）：返回 [senderProfileResult]（默认 null = 查不到）。 */
    override suspend fun resolveSenderProfile(openId: String): com.jiaoay.lark.gateway.SenderProfile? =
        senderProfileResultRef.get()

    /** 撤回：记录调用并返回 [recallResult]（默认 true = 合成撤回成功）。 */
    override suspend fun recallMessage(messageId: String): Boolean {
        recalls.add(RecordedRecall(messageId))
        return recallResultRef.get()
    }

    /** 图片上传：记录调用并返回可编程结果。默认「合成 image_key」模拟上传成功；设 [uploadImageResult] = false 模拟失败。 */
    override suspend fun uploadImage(source: java.nio.file.Path): String? {
        imageUploads.add(RecordedImageUpload(source))
        return if (uploadImageResultRef.get()) "image_key_synthetic_${imageUploads.size}" else null
    }

    /** 历史消息：记录调用并返回 [listMessagesResult]（默认 null = 能力缺席，上层回落降级文案）。 */
    override suspend fun listMessages(chatId: String, pageSize: Int, pageToken: String?): ChatHistoryPage? {
        historyPulls.add(Triple(chatId, pageSize, pageToken))
        return listMessagesResultRef.get()
    }

    /** 群信息：记录调用并返回 [getChatInfoResult]（默认 null）。 */
    override suspend fun getChatInfo(chatId: String): ChatInfo? {
        chatInfoLookups.add(chatId)
        return getChatInfoResultRef.get()
    }

    /** 群成员：返回 [listChatMembersResult]（默认 null）。 */
    override suspend fun listChatMembers(chatId: String, pageSize: Int, pageToken: String?): ChatMembersPage? =
        listChatMembersResultRef.get()

    /** 建群：记录调用并返回 [createChatResult]（默认 null）。 */
    override suspend fun createChat(name: String, memberOpenIds: List<String>): ChatCreation? {
        chatCreations.add(RecordedChatCreation(name, memberOpenIds))
        return createChatResultRef.get()
    }

    /** 拉人：记录调用并返回 [inviteChatMembersResult]（默认 null）。 */
    override suspend fun inviteChatMembers(chatId: String, openIds: List<String>): ChatMemberOpResult? {
        chatInvites.add(RecordedChatInvite(chatId, openIds))
        return inviteChatMembersResultRef.get()
    }

    /** 表情回应列表：返回 [listReactionsResult]（默认 null）。 */
    override suspend fun listReactions(messageId: String): List<ReactionBrief>? = listReactionsResultRef.get()

    /** 已读回执：返回 [readReceiptsResult]（默认 null）。 */
    override suspend fun readReceipts(messageId: String, pageSize: Int, pageToken: String?): ReadReceiptPage? =
        readReceiptsResultRef.get()

    /**
     * wiki 节点解析：记录调用并返回 [wikiNodeResult]（默认 null = 能力缺席）。
     */
    override suspend fun resolveWikiNode(token: String): com.jiaoay.lark.gateway.WikiNodeRef? {
        wikiNodeLookups.add(token)
        return wikiNodeResultRef.get()
    }

    /**
     * docx 正文读取：记录调用并返回 [documentTextResult]（默认 null = 能力缺席）。
     */
    override suspend fun readDocumentText(documentId: String): String? {
        documentTextLookups.add(documentId)
        return documentTextResultRef.get()
    }

    override suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): LarkCredential {
        val custom = registerHandlerRef.get()
        if (custom != null) return custom.invoke(onQr)
        onQr(DEFAULT_QR_URL, DEFAULT_QR_EXPIRE_SECONDS)
        return LarkCredential(DEFAULT_APP_ID, DEFAULT_APP_SECRET)
    }

    override fun close() {
        closes.incrementAndGet()
        inboundChannel.close()
        signalChannel.close()
        stateFlow.value = GatewayConnectionState.Idle
    }

    // ------------------------------------------------------------------ 可编程注入面

    /** `connect()` 的返回值（三态任选）。 */
    var connectResult: GatewayConnectResult
        get() = connectResultRef.get()
        set(value) = connectResultRef.set(value)

    /** `send()` 的默认返回值。 */
    var sendResult: SendResult
        get() = sendResultRef.get()
        set(value) = sendResultRef.set(value)

    /** `send()` 的动态返回（非 null 时**覆盖** [sendResult]；测限流/分场景失败用）。 */
    var sendHandler: ((LarkTarget, Outbound) -> SendResult)?
        get() = sendHandlerRef.get()
        set(value) = sendHandlerRef.set(value)

    /** `send()` 的挂起时长（毫秒）；测「频控挂起不阻塞 intake」时把它调大。 */
    var sendDelayMillis: Long
        get() = sendDelayRef.get()
        set(value) = sendDelayRef.set(value)

    /** `fetchQuotedMessage()` 的可编程返回（默认 null = 补查未取回）。 */
    var quotedMessageResult: com.jiaoay.lark.gateway.QuotedMessage?
        get() = quotedMessageRef.get()
        set(value) { quotedMessageRef.set(value) }

    /** `fetchQuotedMessage()` 的调用记录（messageId 序列），供断言「恰好一次」。 */
    val recordedQuotedFetches: List<String> get() = quotedFetches.toList()

    /** `downloadResource()` 的返回值。 */
    var downloadResult: Boolean
        get() = downloadResultRef.get()
        set(value) = downloadResultRef.set(value)

    /**
     * `downloadResource()` 失败时透传的网关错误码。
     * null（默认）⇒ 合成失败不虚构错误码，resolver 回落通用文案。
     */
    var downloadError: GatewayErrorCode?
        get() = downloadErrorRef.get()
        set(value) { downloadErrorRef.set(value) }

    /** `uploadFile()` 的成败可编程开关（true = 返回合成 file_key；false = 返回 null）。 */
    var uploadFileResult: Boolean
        get() = uploadFileResultRef.get()
        set(value) = uploadFileResultRef.set(value)

    /** 全部 `uploadFile()` 记录（产物回传断言用）。 */
    val recordedUploads: List<RecordedUpload> get() = uploads.toList()

    /** `downloadResource()` 成功时写进 dest 的字节（null ⇒ 写 [DEFAULT_DOWNLOAD_PAYLOAD]）。 */
    var downloadPayload: ByteArray?
        get() = downloadPayloadRef.get()
        set(value) = downloadPayloadRef.set(value)

    /** `setReaction()` 的返回值。 */
    var reactionResult: Boolean
        get() = reactionResultRef.get()
        set(value) = reactionResultRef.set(value)

    /** `updateCard()` 的可编程返回（默认 ok=true——已答态链默认「更新成功」；注入失败测降级路径）。 */
    var updateCardResult: SendResult
        get() = updateCardResultRef.get()
        set(value) = updateCardResultRef.set(value)

    /** 全部 `updateCard()` 记录（已答态链断言用）。 */
    val recordedCardUpdates: List<RecordedCardUpdate> get() = cardUpdates.toList()

    // ------------------------------------------------------------------ L1 能力注入面与记录面

    /** `recallMessage()` 的返回值（默认 true = 合成撤回成功）。 */
    var recallResult: Boolean
        get() = recallResultRef.get()
        set(value) = recallResultRef.set(value)

    /** 全部 `recallMessage()` 记录（撤回守护断言用）。 */
    val recordedRecalls: List<RecordedRecall> get() = recalls.toList()

    /** `uploadImage()` 的成败可编程开关（true = 返回合成 image_key；false = 返回 null）。 */
    var uploadImageResult: Boolean
        get() = uploadImageResultRef.get()
        set(value) = uploadImageResultRef.set(value)

    /** 全部 `uploadImage()` 记录（send_image 工具链断言用）。 */
    val recordedImageUploads: List<RecordedImageUpload> get() = imageUploads.toList()

    /** `listMessages()` 的可编程返回（默认 null = 能力缺席）。 */
    var listMessagesResult: ChatHistoryPage?
        get() = listMessagesResultRef.get()
        set(value) = listMessagesResultRef.set(value)

    /** `getChatInfo()` 的可编程返回（默认 null）。 */
    var getChatInfoResult: ChatInfo?
        get() = getChatInfoResultRef.get()
        set(value) = getChatInfoResultRef.set(value)

    /** `listChatMembers()` 的可编程返回（默认 null）。 */
    var listChatMembersResult: ChatMembersPage?
        get() = listChatMembersResultRef.get()
        set(value) = listChatMembersResultRef.set(value)

    /** `createChat()` 的可编程返回（默认 null = 能力缺席，测 L2 未授权降级）。 */
    var createChatResult: ChatCreation?
        get() = createChatResultRef.get()
        set(value) = createChatResultRef.set(value)

    /** 全部 `createChat()` 记录。 */
    val recordedChatCreations: List<RecordedChatCreation> get() = chatCreations.toList()

    /** `inviteChatMembers()` 的可编程返回（默认 null）。 */
    var inviteChatMembersResult: ChatMemberOpResult?
        get() = inviteChatMembersResultRef.get()
        set(value) = inviteChatMembersResultRef.set(value)

    /** 全部 `inviteChatMembers()` 记录。 */
    val recordedChatInvites: List<RecordedChatInvite> get() = chatInvites.toList()

    /** `listMessages()` 调用记录（probeNativeGrant 探测断言用；(chatId, pageSize, pageToken)）。 */
    val recordedHistoryPulls: List<Triple<String, Int, String?>> get() = historyPulls.toList()

    /** `getChatInfo()` 调用记录（probeNativeGrant 探测断言用）。 */
    val recordedChatInfoLookups: List<String> get() = chatInfoLookups.toList()

    /** `listReactions()` 的可编程返回（默认 null）。 */
    var listReactionsResult: List<ReactionBrief>?
        get() = listReactionsResultRef.get()
        set(value) = listReactionsResultRef.set(value)

    /** `readReceipts()` 的可编程返回（默认 null）。 */
    var readReceiptsResult: ReadReceiptPage?
        get() = readReceiptsResultRef.get()
        set(value) = readReceiptsResultRef.set(value)

    // ------------------------------------------------------------------ L2 能力（wiki/docx）注入面与记录面

    /** `resolveWikiNode()` 的可编程返回（默认 null = 能力缺席，测 L2 未授权降级）。 */
    var wikiNodeResult: com.jiaoay.lark.gateway.WikiNodeRef?
        get() = wikiNodeResultRef.get()
        set(value) = wikiNodeResultRef.set(value)

    /** 全部 `resolveWikiNode()` 调用记录（token 序列，断言 URL 分流用）。 */
    val recordedWikiNodeLookups: List<String> get() = wikiNodeLookups.toList()

    /** `readDocumentText()` 的可编程返回（默认 null = 能力缺席）。 */
    var documentTextResult: String?
        get() = documentTextResultRef.get()
        set(value) = documentTextResultRef.set(value)

    /** 全部 `readDocumentText()` 调用记录（documentId 序列，断言分流/截断链路用）。 */
    val recordedDocumentTextLookups: List<String> get() = documentTextLookups.toList()

    /** `resolveSenderProfile()` 的可编程返回（默认 null = 查不到；lookupUser 工具链测试用）。 */
    var senderProfileResult: com.jiaoay.lark.gateway.SenderProfile?
        get() = senderProfileResultRef.get()
        set(value) = senderProfileResultRef.set(value)

    /** `registerApp()` 的自定义行为（null ⇒ 走默认的「回调 QR + 返回合成凭据」）。 */
    var registerHandler: (suspend (onQr: (String, Int) -> Unit) -> LarkCredential)?
        get() = registerHandlerRef.get()
        set(value) = registerHandlerRef.set(value)

    /**
     * 灌一条合成入站消息。
     * **DROP_OLDEST 的 `trySend` 恒成功**（挤掉最旧），故丢弃只能由占用计数推断——与
     *    `SdkLarkGateway.offerInbound` 同形：`getAndIncrement` 的前值
     *    >= 容量 ⇒ 本次挤掉了最旧一条 ⇒ 净占用回落到容量 + 计数 + 发 [INBOUND_WHERE] 丢弃信号。
     *    全程 O(1) 非阻塞、零锁（SDK 回调线程纪律：不得阻塞）。
     * @return false ⇒ 本次挤掉了最旧一条（已计数、已发信号），或通道已 close；true ⇒ 正常入队。
     */
    fun emitInbound(message: InboundMessage): Boolean {
        inboundEmits.incrementAndGet()
        val prev = inboundOccupancy.getAndIncrement()
        if (prev >= inboundCapacity) {
            inboundOccupancy.decrementAndGet()
            val total = inboundDrops.incrementAndGet()
            emitSignal(GatewaySignal.Dropped(INBOUND_WHERE, total))
            inboundChannel.trySend(message)
            return false
        }
        return inboundChannel.trySend(message).isSuccess
    }

    /** 批量灌入（`IntakeGuardTest` 的 1 万条合成消息用）。@return 未被挤掉的入队条数。 */
    fun emitAll(messages: List<InboundMessage>): Int = messages.count { emitInbound(it) }

    /** 灌一条信号（🔴 O(1) 非挂起：满即丢，不递归再发 Dropped——防「Dropped 自身触发 Dropped」无限递归）。 */
    fun emitSignal(signal: GatewaySignal): Boolean {
        signalEmits.incrementAndGet()
        return signalChannel.trySend(signal).isSuccess
    }

    /** 直接摆连接态（测 UI 6 态渲染 / 测 `BlockedByPeerInstance` 联动）。 */
    fun setState(state: GatewayConnectionState) {
        stateFlow.value = state
    }

    // ------------------------------------------------------------------ 出站记录面

    /** 全部 `send()` 记录（按调用序）。 */
    val recordedSends: List<RecordedSend> get() = sends.toList()

    /** 全部 `setReaction()` 记录。 */
    val recordedReactions: List<RecordedReaction> get() = reactions.toList()

    /** 全部 `downloadResource()` 记录。 */
    val recordedDownloads: List<RecordedDownload> get() = downloads.toList()

    /** 全部 `openCardStream()` 产出的替身 sink。 */
    val recordedStreams: List<SyntheticCardStream> get() = streams.toList()

    /** 全部下推过的策略（`connect` 与 `updatePolicy` 都记）。 */
    val recordedPolicies: List<LarkPolicy> get() = policies.toList()

    /** 全部传入过的凭据。 */
    val recordedCredentials: List<LarkCredential> get() = credentials.toList()

    /** 出站正文一览（断言最常用：`assertEquals(listOf("pong"), gateway.outboundTexts)`）。 */
    val outboundTexts: List<String> get() = sends.mapNotNull { it.out.text }

    /** 出站卡片一览。 */
    val outboundCards: List<CardSpec> get() = sends.mapNotNull { it.out.card }

    val connectCount: Int get() = connects.get()
    val disconnectCount: Int get() = disconnects.get()
    val closeCount: Int get() = closes.get()
    val inboundEmitCount: Long get() = inboundEmits.get()
    val inboundDroppedCount: Long get() = inboundDrops.get()
    val signalEmitCount: Long get() = signalEmits.get()

    /** 清空全部出站记录（一个测试内多轮断言用；不影响通道与连接态）。 */
    fun resetRecords() {
        sends.clear()
        reactions.clear()
        downloads.clear()
        streams.clear()
        policies.clear()
        credentials.clear()
        recalls.clear()
        imageUploads.clear()
        chatCreations.clear()
        chatInvites.clear()
    }

    override fun toString(): String =
        "SyntheticGateway(state=${stateFlow.value}, sends=${sends.size}, streams=${streams.size}, " +
            "inbound=$inboundEmitCount, dropped=$inboundDroppedCount)"

    companion object {
        /** 入站通道容量（与 [InboundQueue.CAPACITY] 同值）。 */
        const val INBOUND_CAPACITY: Int = 512

        /**
         * 入站丢弃信号的 `where` 标签，与 `SdkLarkGateway` 的 `Dropped("inbound", n)` 逐字一致。
         * 替身必须产出与生产同一张 where 表，否则 dry-run 与真机的可观测行为不同形。
         * （桥接侧 `InboundQueue.WHERE == "intake"` 是**第二个**有界队列的标签，两者不得混用。）
         */
        const val INBOUND_WHERE: String = "inbound"

        /** 信号通道容量（与网关 `Tuning.signalChannelCapacity` 同值）。 */
        const val SIGNAL_CAPACITY: Int = 256

        const val DEFAULT_APP_ID: String = "cli_synthetic_app"
        const val DEFAULT_APP_SECRET: String = "synthetic_secret_do_not_use"
        const val DEFAULT_BOT_OPEN_ID: String = "ou_synthetic_bot"
        const val DEFAULT_BOT_NAME: String = "SyntheticBot"
        const val DEFAULT_MESSAGE_ID: String = "om_synthetic_1"
        const val DEFAULT_QR_URL: String = "https://open.feishu.cn/synthetic/qr"
        const val DEFAULT_QR_EXPIRE_SECONDS: Int = 600
        const val DEFAULT_DOWNLOAD_PAYLOAD: String = "synthetic payload"

        /** 让 `registerApp` 抛 [GatewayException]（测 `RegisterAppFailed` 映射用）。 */
        fun failingRegisterHandler(error: GatewayErrorCode, message: String): suspend (onQr: (String, Int) -> Unit) -> LarkCredential =
            { throw GatewayException(error, message) }

        private fun chatIdOf(target: LarkTarget): String? = (target as? LarkTarget.Chat)?.chatId
    }
}
