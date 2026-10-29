// :lark-gateway 网关接口与状态机/信号契约；本文件签名禁 SDK 类型（SDK 隔离三重强制之三：可测性）。
// 包根 com.jiaoay.lark.gateway：lib 侧 jvmTarget 11 不得依赖 :core jvmTarget 25。
package com.jiaoay.lark.gateway

import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 飞书消息网关。全仓唯一允许接触飞书 SDK 的能力面，对 :lark-bridge 暴露纯 Kotlin 契约。
 * 🔴 实现是 [SdkLarkGateway]（gateway-contract.md §3）；SDK 类型绝不泄漏到本接口签名。
 * 共 29 成员（18 基线 + 11 个带默认实现的能力位）。
 */
interface MessageGateway : AutoCloseable {
    /** 连接状态机的唯一权威来源。初值 Idle。 */
    val connectionState: StateFlow<GatewayConnectionState>

    /** 已归一化的入站事件流（message + cardAction 两个 SDK 事件流合流）。冷流。 */
    val inbound: Flow<InboundMessage>

    /** 非消息类的网关事件（拒绝 / 重连 / 频控 / 发送失败 / 分段轮换 / 丢弃）。 */
    val signals: Flow<GatewaySignal>

    /** 建立长连接。必须先 connect 成功再注册 handler。异常一律捕获后映射，不向上抛。 */
    suspend fun connect(cred: LarkCredential, policy: LarkPolicy): GatewayConnectResult

    /** 主动断开长连接，不释放线程池（释放是 close() 的职责）。 */
    suspend fun disconnect()

    /** 热更新策略，不重连。🔴 全量覆盖那 5 个字段而非增量 merge——漏字段即被 SDK 侧清空。 */
    suspend fun updatePolicy(policy: LarkPolicy)

    /**
     * 热更 download 域 settings：原子替换网关内 settings 的
     * `downloadMaxBytes`，download 消费点现读——`fileMessage.maxFileSizeBytes`（ApplyMode.HOT）
     * 保存后即刻生效，不再要求重启桥接。
     *
     * **带默认实现**（no-op）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [resolveSenderName] 系列同款模式）。
     * 非挂起（AtomicReference.set，无网络调用）；未连接时调用安全（download 走 raw client 不经通道）。
     *
     * @param downloadMaxBytes 新的单文件下载上限（字节；<=0 由调用方配置校验拦，网关侧 <=0 = 禁下载）
     */
    fun updateDownloadSettings(downloadMaxBytes: Long) {}

    /** 发一条消息。🔴 不自动重试（发送非幂等——重试即同一条消息双投递）。失败返回而不抛。 */
    suspend fun send(target: LarkTarget, out: Outbound): SendResult

    /** 开一条流式进度卡会话。非挂起：同步返回 sink，建卡/发卡在 sink 内部异步完成。 */
    fun openCardStream(target: LarkTarget, initial: CardSpec): CardStreamSink

    /**
     * 把消息附件落盘到 dest。返回 [DownloadResult]（含失败原因码与飞书业务码），不抛。可重试（幂等）。
     * 调用方（resolver）需要 error/larkCode 渲染用户可读失败原因（如 234037 平台 100MB 限「重试无用」）。
     *    信号（SendFailed/RateLimited）仍照发——信号服务运维日志，本对象服务调用方决策，双轨不混。
     */
    suspend fun downloadResource(messageId: String, fileKey: String, dest: Path): DownloadResult

    /** 加 / 撤一个 emoji 回应。幂等，可重试。add=false 走 removeReactionByEmoji（不保存 reactionId）。 */
    suspend fun setReaction(messageId: String, emojiKey: String, add: Boolean): Boolean

    /**
     * 扫码创建自建应用，返回新凭据。阻塞式轮询最长约 600s，跑在专用单线程 dispatcher。
     * 这是网关里唯一允许抛异常的成员（GatewayException），它是用户主动发起的一次性交互流程。
     */
    suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): LarkCredential

    /** 幂等释放：断连 + 关闭自研入站传输 + cancel 内部 scope。非挂起。 */
    override fun close()

    /**
     * 通过 open_id 解析飞书用户名（`contact.v3.user.get`，`user_id_type = open_id`）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破。
     *
     * 前置条件：应用需在飞书后台授予 `contact:contact.base:readonly` scope（单常量
     * `com.jiaoay.lark.bridge.observe.UserNameResolver.CONTACT_BASE_SCOPE`——旧名
     * contact:user.base:readonly 已被平台更名）；未授权时实现侧应返回 null 且不得刷错误日志。
     *
     * @param openId 飞书用户的 open_id
     * @return 用户显示名；解析失败 / 无权限 / 空结果一律返回 null（**绝不抛异常**）
     */
    suspend fun resolveSenderName(openId: String): String? = null

    /**
     * 通过 open_id 解析发送者完整身份（`contact.v3.user.get` 同一 API，含头像 URL；
     * 统计页用户列表/详情页的头像与名字数据源）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [resolveSenderName] / [fetchQuotedMessage] 同款模式）。
     *
     * 前置条件同 [resolveSenderName]：`contact:contact.base:readonly` scope + 通讯录授权范围覆盖该用户。
     *
     * @param openId 飞书用户的 open_id
     * @return 名字 + 头像 URL；解析失败 / 无权限 / 空结果 / 未连接一律返回 null（**绝不抛异常**）
     */
    suspend fun resolveSenderProfile(openId: String): SenderProfile? = null

    /**
     * 现查被引用的消息（`im.v1.message.get`）：引用流 miss 时补查，原文作 agent 的引用上下文。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [resolveSenderName] 同款模式）。
     *
     * 使用场景：引用流 lookup miss（进程重启清空登记表 / TTL 过期 / 用户引用了旧气泡 / 引用纯文本消息）时，
     * 按被引用 messageId 现查该消息；文件消息返回 [QuotedMessage.File]（content 的 `<file key="…" name="…"/>`
     * 由实现侧解析），文本消息返回 [QuotedMessage.Text]（content 的 `{"text":…}` 由实现侧解析）——
     * 两者都作为 agent 的引用上下文；其余消息类型不支持，返回 null。
     *
     * 前置条件：应用需可读该消息（scope 不足时实现侧返回 null 并 WARN 一次携带真实 code）。
     *
     * @return 该消息为文件/文本消息且可解析时返回结构化描述；其余情况（API 失败 / scope 不足 /
     *         不支持的消息类型 / 已删除 / 未连接）一律返回 null（**绝不抛异常**）
     */
    suspend fun fetchQuotedMessage(messageId: String): QuotedMessage? = null

    /**
     * 上传一个文件到飞书素材库（`im/v1/files`）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [resolveSenderName] / [fetchQuotedMessage] 同款模式）。
     *
     * 两步式文件消息的第一步：上传得 file_key，随后调用方以 [Outbound.file]（msg_type=file）发送。
     * 上传与发送是**两次 API 调用**，调用方（ReplySink.file）须各过一次 RateGovernor。
     *
     * 前置条件：应用需在飞书后台授予 `im:resource`（文件上传）权限；未授权 / 文件缺失 / 未连接 /
     * 文件超飞书侧上限（约 30MB）一律返回 null（**绝不抛异常**）。
     *
     * @param source 本地文件路径（不存在返回 null）
     * @param displayName 飞书侧展示文件名；null 时取 source 的文件名
     * @return 上传成功得到的 file_key；任何失败返回 null
     */
    suspend fun uploadFile(source: Path, displayName: String? = null): String? = null

    /**
     * 原位更新一条已发出的**卡片消息**（`im/v1/messages/:message_id` PATCH）。
     *
     * **带默认实现**（返回失败 SendResult）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [resolveSenderName] / [fetchQuotedMessage] / [uploadFile] 同款模式）。
     *
     * 官方约束（PatchMessageReq javadoc / 线上实证）：**仅支持 interactive 消息**
     * （本仓全部卡片——确认卡/交互卡/终态卡——经 `im.message.create` 的 msg_type 恒为 "interactive"，形态匹配），
     * 更新的卡 JSON ≤ 30KB。它更新的是**既有消息**而非新出站，故不经新消息频控语义；
     * 调用方（GatewayReplySink.updateExistingCard）仍把它放进 RateGovernor 的退避窗口约束（限流期间不打 API）。
     *
     * 主要消费方：确认卡裁决后的**已答态回执**（原卡更新为「已批准 ✓ / 已拒绝 ✗」、按钮消失——
     * answeredQuestionCard 状态一致性语义；文字回执仍照发，群聊后来者可见裁决记录）。
     *
     * @param messageId 目标卡片消息的 messageId（发送时由 SendResult.messageId 返回）
     * @param spec 新卡片内容（整卡替换；旧卡上的交互按钮自然消失）
     * @return 更新成功返回 ok=true 的 SendResult（messageId 同入参）；任何失败（未连接 / 消息非卡片 /
     *         消息已删除 / 权限不足）返回失败结果，**绝不抛异常**
     */
    suspend fun updateCard(messageId: String, spec: CardSpec): SendResult =
        SendResult(ok = false, messageId = null, error = GatewayErrorCode.SendFailed)

    // =====================================================================================
    // 以下 9 个成员全部**带默认实现**（null/false/空返回），沿 [resolveSenderName] / [updateCard] /
    // [uploadFile] 的既有先例——既有实现者零改动编译通过。
    // 「默认实现 = 能力缺席」：替身与未升级的实现侧自然落 null/false，调用方按失败降级，不炸主链路。
    // 🔴 签名全部用纯 Kotlin 值类型 + java.nio.file.Path（SDK 类型绝不出现，LarkSdkIsolationTest 红线）。
    // =====================================================================================

    /**
     * 撤回一条**机器人自己发出的**消息（`im.v1.message.delete`，DELETE 语义即撤回）。
     *
     * REST 语义：`DELETE /im/v1/messages/{message_id}`——飞书侧「删除消息」对机器人自己的消息即撤回，
     *    对他人消息恒无权限（替用户身份操作属 L3，调用方工具面已由账本双重守护挡下）。
     * scope：`send_as_bot` 族（撤回自己消息不需要独立 scope；真机待确认项）。
     *
     * **带默认实现**（返回 false）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [resolveSenderName] / [updateCard] 同款模式）。**默认实现 = 能力缺席**。
     *
     * @param messageId 目标消息 id（机器人发出时由 SendResult.messageId 返回）
     * @return 撤回成功 true；任何失败（未连接 / 非自己消息 / 已删除 / 权限不足）返回 false，**绝不抛异常**
     */
    suspend fun recallMessage(messageId: String): Boolean = false

    /**
     * 上传一张图片到飞书图片素材库（`im.v1.image.create`）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [uploadFile] 完全同款模式——镜像其实现纪律）。**默认实现 = 能力缺席**。
     *
     * 两步式图片消息的第一步：上传得 image_key，随后调用方以 [Outbound.image]（msg_type=image）发送。
     * 上传与发送是**两次 API 调用**，调用方须各过一次 RateGovernor（与 [uploadFile] 同构）。
     *
     * 前置条件：应用需在飞书后台授予 `im:resource`（图片上传）权限；未授权 / 文件缺失 / 未连接 /
     * 文件超飞书侧上限（约 10MB，真机待确认）一律返回 null（**绝不抛异常**）。
     *
     * @param source 本地图片文件路径（不存在返回 null）
     * @return 上传成功得到的 image_key；任何失败返回 null
     */
    suspend fun uploadImage(source: java.nio.file.Path): String? = null

    /**
     * 拉取会话历史消息（`GET /im/v1/messages?container_id_type=chat`）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [resolveSenderName] 同款模式）。**默认实现 = 能力缺席**。
     *
     * 前置条件：应用需在飞书后台授予 `im:message.history:readonly` scope（不在最小 scope 集，
     * 需后台申请+发版）；未授权 / 未连接一律返回 null（**绝不抛异常**）。
     *
     * @param chatId 会话 id（工具面钉死为当前会话，不接受 LLM 传入）
     * @param pageSize 单页条数（飞书上限 50）
     * @param pageToken 分页 token（null = 第一页；翻页用它续）
     * @return 一页历史消息（正文摘要经 [parseHistoryTextBrief] 解析）；任何失败返回 null
     */
    suspend fun listMessages(
        chatId: String,
        pageSize: Int = 20,
        pageToken: String? = null,
    ): ChatHistoryPage? = null

    /**
     * 查询群/会话信息（`GET /im/v1/chats/{chat_id}`）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [resolveSenderName] 同款模式）。**默认实现 = 能力缺席**。
     *
     * 前置条件：应用需在飞书后台授予 `im:chat` 族 scope（不在最小 scope 集，需后台申请+发版）；
     * 未授权 / 会话不在机器人可见范围 / 未连接一律返回 null（**绝不抛异常**）。
     *
     * @param chatId 会话 id
     * @return 会话信息；任何失败返回 null
     */
    suspend fun getChatInfo(chatId: String): ChatInfo? = null

    /**
     * 分页拉取群成员名单（`GET /im/v1/chats/{chat_id}/members`，member_id_type=open_id）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [getChatInfo] 同款模式）。**默认实现 = 能力缺席**。
     *
     * 前置条件：应用需在飞书后台授予 `im:chat` 族 scope（同 [getChatInfo]）；未授权 / 未连接一律返回 null。
     *
     * @param chatId 会话 id
     * @param pageSize 单页人数（飞书上限 100）
     * @param pageToken 分页 token（null = 第一页）
     * @return 一页成员（open_id + 名字）；任何失败返回 null
     */
    suspend fun listChatMembers(
        chatId: String,
        pageSize: Int = 50,
        pageToken: String? = null,
    ): ChatMembersPage? = null

    /**
     * 创建群聊（`POST /im/v1/chats`，owner 为机器人自己）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [getChatInfo] 同款模式）。**默认实现 = 能力缺席**。
     *
     * 新群内机器人不能主动开口（无回话通道的 L1 出站边界）——调用方（create_group_chat 工具）
     *    必须返回 chatId + 「新群内 @ 机器人继续」引导文案；本接口只负责建群本身。
     *
     * 前置条件：应用需在飞书后台授予 `im:chat` scope（需后台申请+发版）；未授权 / 未连接一律返回 null。
     *
     * @param name 群名
     * @param memberOpenIds 初始成员 open_id 名单（机器人自己是 owner，无需传入）
     * @return 建群结果（chatId + 未成功成员名单）；任何失败返回 null
     */
    suspend fun createChat(name: String, memberOpenIds: List<String>): ChatCreation? = null

    /**
     * 向群拉入成员（`POST /im/v1/chats/{chat_id}/members`，member_id_type=open_id）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [getChatInfo] 同款模式）。**默认实现 = 能力缺席**。
     *
     * 前置条件：应用需在飞书后台授予 `im:chat` scope（同 [createChat]）；未授权 / 未连接一律返回 null。
     *
     * @param chatId 目标群 id
     * @param openIds 待拉入的成员 open_id 名单
     * @return 操作结果（ok + 未成功名单）；任何失败返回 null
     */
    suspend fun inviteChatMembers(chatId: String, openIds: List<String>): ChatMemberOpResult? = null

    /**
     * 列出一条消息的全部表情回应（`GET /im/v1/messages/{message_id}/reactions`）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [resolveSenderName] 同款模式）。**默认实现 = 能力缺席**。
     *
     * 卡片交互专项预留（本轮零消费者）；scope 归属（reactions readonly）真机确认中。
     *
     * @param messageId 目标消息 id
     * @return 全部表情回应简报（跨页取齐；简化为一次性聚合，失败/未授权返回 null）
     */
    suspend fun listReactions(messageId: String): List<ReactionBrief>? = null

    /**
     * 拉取一条消息的已读回执（`GET /im/v1/messages/{message_id}/read_users`，user_id_type=open_id）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与 [resolveSenderName] 同款模式）。**默认实现 = 能力缺席**。
     *
     * 卡片交互专项预留（本轮零消费者）。
     *
     * @param messageId 目标消息 id
     * @param pageSize 单页人数（飞书上限 100）
     * @param pageToken 分页 token（null = 第一页）
     * @return 一页已读名单；任何失败返回 null
     */
    suspend fun readReceipts(
        messageId: String,
        pageSize: Int = 50,
        pageToken: String? = null,
    ): ReadReceiptPage? = null

    // =====================================================================================
    // wiki/docx 读写两成员。形态同上——带默认实现（null 返回），「默认实现 = 能力缺席」；
    // 30k 截断不在这里做——gateway 返回全文，截断 + truncated 标记归工具层（NativeChatSurface 实现侧）。
    // =====================================================================================

    /**
     * 解析知识库节点（`GET /wiki/v2/spaces/get_node`；L2 read_wiki_document 的 wiki 分流步）。
     *
     * REST 语义：按 token 查知识库节点信息——`token` 是知识库节点 token（URL `/wiki/` 段后的 token）；
     *    `objType` 不传默认按 wiki 类型查（云文档实际 token 查询需传对应类型，本成员不透传——只服务
     *    wiki 节点 → documentId 的解析链）。
     * scope：`wiki:wiki:readonly`（节点阅读权限；需后台申请+发版）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者（含测试替身 [com.jiaoay.lark.bridge.pipeline.SyntheticGateway]）
     *    零改动、编译不破（与批 A 九成员同款模式）。**默认实现 = 能力缺席**。
     *
     * @param token 知识库节点 token（`wikcnKQ1k3p…` 形态；工具层已从 URL 剥出）
     * @return 节点引用（objToken 即 docx documentId + 标题）；任何失败（未连接 / 无权限 / 非文档节点）
     *         返回 null（**绝不抛异常**）
     */
    suspend fun resolveWikiNode(token: String): WikiNodeRef? = null

    /**
     * 读文档纯文本正文（`GET /docx/v1/documents/{document_id}/raw_content`；L2 read_wiki_document 的正文步）。
     *
     * REST 语义：取文档的**纯文本** content（rich text 降维——表格/图片等块结构不保留）。
     * scope：`docx:document:readonly`（需后台申请+发版）。
     *
     * **带默认实现**（返回 null）⇒ 既有实现者零改动编译（与 [resolveWikiNode] 同款模式）。
     *    **默认实现 = 能力缺席**。
     *
     * @param documentId 文档 id（wiki 链接经 [resolveWikiNode] 解析出 objToken；`/docx/` 链接直取 token）
     * @return 文档纯文本全文（**不截断**——截断归调用方）；任何失败返回 null（**绝不抛异常**）
     */
    suspend fun readDocumentText(documentId: String): String? = null
}

/** 连接状态机（6 子类）。成员与取值构成契约面，不增不减。 */
sealed interface GatewayConnectionState {
    data object Idle : GatewayConnectionState
    data object Connecting : GatewayConnectionState
    data class Connected(val botOpenId: String, val botName: String? = null) : GatewayConnectionState
    data object Reconnecting : GatewayConnectionState
    data class Disconnected(val code: GatewayErrorCode, val msg: String) : GatewayConnectionState
    data object BlockedByPeerInstance : GatewayConnectionState
}

/**
 * 非消息类网关信号（7 子类，含 Dropped）。成员与取值构成契约面，不增不减。
 * 新增子类不破坏契约：sealed 的下游 when 若无 else 分支会编译失败，这正是我们要的强制可见性。
 */
sealed interface GatewaySignal {
    /**
     * SDK PolicyGate 拒绝。reason = RejectEvent.getReason() 的 name。
     * SDK 侧全放行 ⇒ 本信号正常运行时恒不出现；保留是为 SDK 侧策略被误配时可观测。
     *
     * 🔴 SDK `PolicyGate` 的判定颗粒度是**会话级**（不区分消息类型）——若把 requireMention 下推给 SDK，
     * 群文件消息（物理上无法带 @）会在「message」事件之前被拦下，桥接侧针对 `InboundKind.FILE`
     * 的豁免永远执行不到。故 requireMention 恒不下推（见 [LarkPolicy.requireMention]）。
     */
    data class Rejected(val reason: String, val messageId: String?, val chatId: String?, val senderId: String?) : GatewaySignal
    data object Reconnecting : GatewaySignal
    data object Reconnected : GatewaySignal
    /** resetSec 来自 x-ogw-ratelimit-reset 响应头；头缺失时为 null（真机已确认该头常缺）。 */
    data class RateLimited(val resetSec: Int?, val larkCode: Int?, val chatId: String?) : GatewaySignal
    data class SendFailed(val code: GatewayErrorCode, val msg: String, val chatId: String? = null) : GatewaySignal
    /** 流式卡因 24KB 预算或 9min 期限轮换到新卡片实体。segmentIndex 从 1 开始。 */
    data class StreamSegmentRotated(val segmentIndex: Int, val reason: RotationReason, val newMessageId: String?) : GatewaySignal
    /**
     * 流式卡终态落定。flushTerminal/close 成功发出终态卡后发射；
     * markdown = 终态全文（CardSpec.markdown 或流内快照兜底），messageId = 这条卡消息在会话里的
     * messageId（多段轮换时为最后一段）。桥侧收此信号写入卡片回复账本，供后续「引用这条回复」时
     * 以本地账兜底（飞书无按 card_id 读卡片实体的 GET API——正文只在我们自己手里）。
     * 🔴 [chatId] 是账本「本会话校验」的维度（防跨会话引用：agent 工具侧 chatId 不匹配即 miss）。
     * User 目标（单聊接收者非群）为 null ⇒ 账本记 ""（未知）。
     */
    data class StreamTerminal(val messageId: String, val markdown: String, val chatId: String? = null) : GatewaySignal
    /** 🔴 所有丢弃点必须可观测——不可观测即静默丢消息。入站 Channel(512) 或信号 Channel(256) 溢出时 emit。 */
    data class Dropped(val where: String, val droppedTotal: Long) : GatewaySignal
}

/** 流式卡分段轮换的原因。 */
enum class RotationReason { ByteBudget, TimeDeadline, EntityExpired, SequenceConflict }
