// :lark-gateway 纯 Kotlin 值类型层（全仓唯一 SDK 依赖模块的契约面）；本文件签名禁 SDK 类型（SDK 隔离三重强制之三：可测性）。
// 包根 com.jiaoay.lark.gateway（非 com.jiaoay.easy_dev_toolbox.*）：lib 侧 jvmTarget 11 不得依赖 :core jvmTarget 25（R19）。
package com.jiaoay.lark.gateway

/**
 * 飞书自建应用凭据。字段与配置域 connection.appId / appSecret / region 一一对应。
 * 🔴 PII 最小化：只含 appId/appSecret/region 三字段，扫码人身份（userInfo）不进契约——多进一个字段即多一分敏感信息落桥接域。
 */
data class LarkCredential(
    val appId: String,             // connection.appId，形如 cli_xxx
    val appSecret: String,         // connection.appSecret。🔴 日志/审计/check 输出一律掩码——明文即凭据泄漏
    val region: String = "feishu", // connection.region，Choice(feishu, lark)
) {
    /** 掩码形态，供日志与 UI 显示。绝不 toString 出 appSecret。 */
    fun masked(): String = "LarkCredential(appId=$appId, appSecret=${appSecret.take(4)}***, region=$region)"

    // 🔴 覆写 toString，防止任何字符串插值泄漏 secret。
    override fun toString(): String = masked()
}

/**
 * 出站目标。receiveIdType 字面值直接喂给 SDK CreateMessageReq.newBuilder().receiveIdType(String)。
 * 做成 sealed 而非 (id, type) 二元组，让「传错 idType」在编译期就不可能。
 */
sealed interface LarkTarget {
    val receiveId: String
    val receiveIdType: String // "chat_id" | "open_id"

    /** 群聊会话（含 p2p 会话，飞书 p2p 也有 oc_ 开头的 chat_id）。 */
    data class Chat(val chatId: String) : LarkTarget {
        override val receiveId: String get() = chatId
        override val receiveIdType: String get() = "chat_id"
    }

    /** 直接按用户 open_id 发（用于 rejectionReply 等尚无 chatId 的场景）。 */
    data class User(val openId: String) : LarkTarget {
        override val receiveId: String get() = openId
        override val receiveIdType: String get() = "open_id"
    }

    companion object {
        /** 由 InboundMessage 反推回复目标：一律用 Chat（chatId 已由 SDK 给出，p2p/group 通用）。 */
        fun ofInbound(msg: InboundMessage): LarkTarget = Chat(msg.chatId)
    }
}

/** 会话类型。SDK 字面值 "p2p"/"group" → 枚举；其它/null → UNKNOWN（大小写敏感精确匹配）。 */
enum class ChatType { P2P, GROUP, UNKNOWN }

/**
 * 入站消息种类。CARD_ACTION 来自独立事件流 "cardAction"（不是 message 的 rawContentType，gateway-contract.md §2.3）；
 * UNKNOWN 是 rawContentType 出现未登记取值时的 fallback，绝不抛异常。
 */
enum class InboundKind {
    TEXT, IMAGE, FILE, AUDIO, VIDEO, POST, MERGE_FORWARD, STICKER, SHARE_CHAT, SHARE_USER,
    CARD_ACTION,
    UNKNOWN,
}

/** 消息附件描述。字段对齐 ResourceDescriptor 的 4 个 getter。 */
data class InboundResource(
    val type: String?,     // ResourceDescriptor.getType()
    val fileKey: String?,  // ResourceDescriptor.getFileKey()
    val fileName: String?, // ResourceDescriptor.getFileName()
    val durationMs: Long,  // ResourceDescriptor.getDurationMs()
)

/**
 * 现查的被引用消息（`im.v1.message.get`）：引用流 miss 时补查，原文作 agent 的引用上下文。
 * sealed 两子类：文件消息产出结构化 fileKey/fileName；文本消息产出原文（agent 上下文）——
 * content 的解析（`<file …/>` 类 XML / `{"text":…}` JSON）由网关实现侧完成，桥侧零解析依赖
 * （SDK 类型不出 :lark-gateway 的隔离铁律不破）。
 */
sealed interface QuotedMessage {
    val messageId: String
    val chatId: String
    val senderId: String // open_id（请求带 userIdType="open_id"，配额目录归属对齐 FileRecord.senderId）

    /** 被引用的是文件消息：补登记进 FileRegistry 后走懒下载。 */
    data class File(
        override val messageId: String,
        override val chatId: String,
        override val senderId: String,
        val fileKey: String,
        val fileName: String,
    ) : QuotedMessage

    /** 被引用的是文本消息：原文直接作为 agent 上下文拼接（不登记、不下载）。 */
    data class Text(
        override val messageId: String,
        override val chatId: String,
        override val senderId: String,
        val text: String,
    ) : QuotedMessage
}

/**
 * contact.v3.user.get 解析出的发送者完整身份（统计页用户列表/详情页的头像与名字数据源）。
 * 纯 Kotlin 值类型（SDK 零泄漏）；PII 最小化——只取展示名与头像 URL。
 */
data class SenderProfile(
    /** 展示名；解析失败为空串。 */
    val name: String,
    /** 头像 URL（avatar_240 → avatar_72 → avatar_640 择优）；无头像为空串。 */
    val avatarUrl: String,
)

/** @提及信息。字段对齐 MentionInfo 的 4 个 getter。 */
data class InboundMention(
    val key: String?,    // MentionInfo.getKey()，形如 @_user_1
    val openId: String?, // MentionInfo.getOpenId()
    val userId: String?, // MentionInfo.getUserId()
    val name: String?,   // MentionInfo.getName()
)

/** 卡片回传（card.action.trigger）。CardActionEvent 的 5 个 getter。 */
data class CardAction(
    val operatorId: String,    // CardActionEvent.getOperatorId()
    val actionTag: String?,    // getActionTag()
    val actionName: String?,   // getActionName()
    val actionOption: String?, // getActionOption()
    val actionValue: String?,  // getActionValue()
)

/**
 * 已归一化的入站消息。字段名严格对齐 NormalizedMessage 的 16 个 getter。
 * 无 eventId 字段（幂等键是 messageId，误用 eventId 在类型层面即不可能）；无 raw 字段。
 */
data class InboundMessage(
    val messageId: String,        // 幂等键
    val chatId: String,
    val chatType: ChatType,       // "p2p"/"group" → 枚举；其它 → UNKNOWN
    val senderId: String,
    val senderName: String,       // SDK 已从事件本身解析（不额外发 HTTP）
    val text: String,             // NormalizedMessage.getContent()：已转换 + 已剥离 bot mention（stripBotMentions=true 硬编码）
    val kind: InboundKind,
    val rawContentType: String?,  // 原样透传（不是 messageType），供可观测与 P11 回填
    val resources: List<InboundResource>,
    val mentions: List<InboundMention>,
    val mentionAll: Boolean,      // NormalizedMessage.isMentionAll()
    val mentionedBot: Boolean,    // NormalizedMessage.isMentionedBot()
    val rootId: String?,
    val threadId: String?,
    val replyToMessageId: String?,
    val createTimeMillis: Long,   // NormalizedMessage.getCreateTime()：long 毫秒（不是字符串）
    val cardAction: CardAction? = null,       // kind == CARD_ACTION 时非空
    val gatewayReceivedAtMillis: Long = 0L,   // 网关本地时钟，供 StaleFilter 交叉校验（NF-A7）
) {
    /** 复合会话键：chatId + (threadId ?: rootId ?: "")。SDK 原生提供三字段，无需从 raw JSON 挖。 */
    val sessionKey: String get() = chatId + (threadId ?: rootId ?: "")
}

/**
 * 出站消息种类。IMAGE：以 image_key 直发的图片消息——
 * image_key 由 [MessageGateway.uploadImage] 上传取得（两步式，与 FILE 同构）。
 */
enum class OutboundKind { TEXT, MARKDOWN, CARD, FILE, IMAGE }

/** FILE 出站的载荷：file_key 由 [MessageGateway.uploadFile] 上传后取得，fileName 为展示文件名。 */
data class OutboundFileSpec(
    val fileKey: String,
    val fileName: String,
)

/** IMAGE 出站的载荷：image_key 由 [MessageGateway.uploadImage] 上传后取得。 */
data class OutboundImageSpec(
    val imageKey: String,
)

/**
 * 出站消息。契约级不变式：CARD 当且仅当 card != null；TEXT/MARKDOWN 当且仅当 text != null；
 * FILE 当且仅当 file != null；IMAGE 当且仅当 image != null。
 * idempotencyKey 保留契约字段，但 send() 不喂 SDK 的 uuid()（sequence 已是幂等键）。
 */
data class Outbound(
    val kind: OutboundKind,
    val text: String? = null,                     // kind == TEXT | MARKDOWN 时非空
    val card: CardSpec? = null,                   // kind == CARD 时非空
    val file: OutboundFileSpec? = null,           // kind == FILE 时非空
    val image: OutboundImageSpec? = null,         // kind == IMAGE 时非空
    val replyToMessageId: String? = null,         // → SendOptions.Builder.replyTo(String)
    val replyInThread: Boolean = false,           // → SendOptions.Builder.replyInThread(Boolean)
    val mentionOpenIds: List<String> = emptyList(), // → SendOptions.Builder.mentions(List<String>)
    val idempotencyKey: String? = null,           // 仅为飞书侧去重与排障留痕；send() 本身不重试（非幂等）
) {
    init {
        require((kind == OutboundKind.CARD) == (card != null)) { "CARD 当且仅当 card != null" }
        require((kind == OutboundKind.FILE) == (file != null)) { "FILE 当且仅当 file != null" }
        require((kind == OutboundKind.IMAGE) == (image != null)) { "IMAGE 当且仅当 image != null" }
        // IMAGE 不带正文（msg_type=image 的 content 只有 image_key）：TEXT/MARKDOWN 的 text 不变式
        //    相应从「非 CARD/FILE」收窄为「TEXT 或 MARKDOWN」——与旧式 `(kind != CARD && kind != FILE)`
        //    逐值等价（旧式对 IMAGE 恒 false 恒成立），不改变既有 4 类的任何行为。
        require((kind == OutboundKind.TEXT || kind == OutboundKind.MARKDOWN) == (text != null)) { "TEXT/MARKDOWN 当且仅当 text != null" }
    }

    companion object {
        fun text(s: String) = Outbound(OutboundKind.TEXT, text = s)
        fun markdown(s: String) = Outbound(OutboundKind.MARKDOWN, text = s)
        fun card(c: CardSpec) = Outbound(OutboundKind.CARD, card = c)

        /**
         * FILE 出站工厂。`uuid`：填非 null 时网关把它喂给
         * `CreateMessageReqBody.uuid`（相同 uuid 1 小时内至多成功发一条）——用于「memo 复用命中发送
         * 失败 ⇒ 回退两步上传重发」的两连发防双投递（第一次假失败时服务端已受理，重发被幂等拒绝）。
         * null = 不带 uuid（既有行为，含首次上传后的正常发送）。
         */
        fun file(fileKey: String, fileName: String, uuid: String? = null) =
            Outbound(OutboundKind.FILE, file = OutboundFileSpec(fileKey, fileName), idempotencyKey = uuid)

        /**
         * IMAGE 出站工厂：imageKey 由 [MessageGateway.uploadImage]
         * 上传取得。两步式（上传 + 发送是两次 API 调用，调用方各过一次频控）与 FILE 完全同构。
         */
        fun image(imageKey: String) = Outbound(OutboundKind.IMAGE, image = OutboundImageSpec(imageKey))
    }
}

/** 卡片标题配色 → 飞书 header.template 字面值。 */
enum class CardTitleColor(val template: String) {
    BLUE("blue"),   // 受理态
    GREEN("green"), // DONE
    RED("red"),     // FAILED
    GREY("grey"),   // 进行中 / 中性
}

/** 卡片按钮。回传时 value 出现在 CardActionEvent.getActionValue()。 */
data class CardButtonSpec(
    val text: String,
    val value: String,
    val primary: Boolean = false, // → behaviors[0].type=="callback" + button type primary/default
)

/**
 * 卡片规格。🔴 契约级约束：streaming == true 时 buttons 必须为空——飞书 200810「流式期间无法响应卡片回传」。
 * 该约束由 LarkOutboundMapper 强制（非空即抛 IllegalArgumentException），NF-A6 ⑥ 负向断言守护。
 */
data class CardSpec(
    val title: String,
    val titleColor: CardTitleColor = CardTitleColor.GREY,
    val markdown: String,                            // → body.elements[0].tag=="markdown" 的 content
    val buttons: List<CardButtonSpec> = emptyList(), // → behaviors 数组，禁 action 包裹（否则 230099）
    val streaming: Boolean = false,                  // true → config.streaming_mode = true
    val summary: String? = null,                     // → config.summary.content；null 时取 markdown.take(80)
)

/** 出站结果。失败返回而不抛异常。 */
data class SendResult(
    val ok: Boolean,
    val messageId: String?,                          // ok == true 时非空
    val error: GatewayErrorCode? = null,             // ok == false 时非空
    val larkCode: Int? = null,                       // 飞书业务码原值（BaseResponse.getCode()）
    val larkMsg: String? = null,                     // BaseResponse.getMsg()
    val httpStatus: Int? = null,                     // RawResponse.getStatusCode()
    val chunkMessageIds: List<String> = emptyList(), // 文本超 3500 分片时的多 messageId
)

/**
 * downloadResource 的结果。失败返回而不抛。
 *
 * **双轨设计**：网关信号（SendFailed/RateLimited，经 `signals` Flow）服务**运维日志**（每进程单收集器，
 * BridgeAssembler.handleSignal 打 WARN + 驱动 RateGovernor）；本对象服务**调用方决策**（resolver 据此渲染
 * 用户可读文案）。二者并行不替代——所有 offerSignal 原样保留，可观测只有增量没有回退。
 * `larkCode` 是「平台限 vs 我方限」的**唯一判据**：我方 downloadMaxBytes 超限发生在 HTTP 200 之后
 * （error=DownloadTooLarge 且 larkCode=null）；飞书平台限在下载中被拒、必带业务码（234037 ⇒ error=
 * DownloadTooLarge 且 larkCode=234037）。调用方不得复制 234037 魔数——`larkCode != null` 即平台侧。
 */
data class DownloadResult(
    val ok: Boolean,
    val error: GatewayErrorCode? = null, // ok == false 时非空（downloadMaxBytes<=0 预检时亦非空）
    val larkCode: Int? = null,           // 飞书业务码原值（234037 平台下载大小限 / 11232 限流 / …）
    val detail: String? = null,          // 诊断原文（进日志与 WARN；非用户文案——用户文案归 :lark-bridge）
) {
    companion object {
        /** 成功的惯用单例（GatewayModel.kt 已有 data object/companion 且无 @Volatile var ⇒ F 组门禁安全）。 */
        val OK = DownloadResult(ok = true)
    }
}

/**
 * 网关自有错误语义（24 值，gateway-contract.md §15.2 M-1：文档多处声称的「26」为误 count，以 gateway-contract.md §2.5 枚举字面 + gateway-contract.md §6.3.4 穷尽 when 为权威）。
 * 命名与 SDK LarkChannelErrorCode 对齐，并补齐 cardkit 侧业务码语义。
 * 网关码 → BridgeErrorCode 的映射定义在 :lark-bridge，本模块只产出 GatewayErrorCode。
 */
enum class GatewayErrorCode {
    // —— 连接域 ——
    ConnectFatal,          // 不可重试：凭据错误 / 应用未发版 / scope 缺失 / 99991672 / HTTP 404 纯文本
    ConnectRetryable,      // 可重试：网络抖动 / 缺 URL 字段 / 5xx
    BlockedByPeerInstance, // 同 appId 单实例互斥（由注入的 appIdLockProbe 报告）
    NotConnected,          // SDK NOT_CONNECTED：未 connect 就调用出站 API

    // —— 出站域 ——
    FormatError,           // SDK FORMAT_ERROR
    TargetRevoked,         // SDK TARGET_REVOKED（会话被解散 / 用户注销）
    PermissionDenied,      // SDK PERMISSION_DENIED（scope 不足）
    UploadFailed,          // SDK UPLOAD_FAILED
    SsrfBlocked,           // SDK SSRF_BLOCKED（OutboundConfig.ssrfGuardEnabled 拦截）
    SendTimeout,           // SDK SEND_TIMEOUT
    SendFailed,            // 兜底：LarkChannelErrorCode.UNKNOWN 或未登记业务码

    // —— 频控域 ——
    RateLimited,           // SDK RATE_LIMITED / HTTP 429 / 业务码 11232（整点压力限流）

    // —— 卡片流式域（🔴 SDK 侧全部静默失败，必须由我方逐次检查响应码后产出）——
    CardSequenceConflict,  // 300317 sequence 不连续 / 乱序 → 重建流
    CardTooLarge,          // 200860 单卡 30KB 超限 → 触发分段轮换
    CardStreamBusy,        // 200810 流式期间无法响应卡片回传 → 确认卡必须走独立非流式卡
    CardEntityExpired,     // 200750 卡片实体 14 天过期 / 仅可发送一次 → 新建实体
    CardJsonInvalid,       // 300302 / 230099 / 230001(invalid msg_type) / 300312(tag cannot be updated) / 11311 / 10002 → 卡片/请求 JSON 结构错误（S6 真机补录，gateway-contract.md §16）

    // —— 资源域 ——
    DownloadFailed,        // 下载失败（含 IO / 非 0 业务码）
    DownloadTooLarge,      // 超 maxFileSizeBytes 上限（下载后拒绝阈值）
    ReactionFailed,        // addReaction / removeReactionByEmoji 失败

    // —— 扫码建应用域 ——
    RegisterQrExpired,       // SDK ExpiredException / QRCodeInfo.expireIn 到期
    RegisterInvalidArgument, // SDK RegisterAppException(ERROR_INVALID_ARGUMENT)
    RegisterFailed,          // 其它 RegisterAppException

    Unknown,               // 兜底
}

/**
 * 网关异常。仅 [MessageGateway.registerApp] 一个成员允许抛出（gateway-contract.md §2.6 第 11 成员 / gateway-contract.md §8.3 规格 4）：
 * 它是用户主动发起的一次性交互，无「静默失败」风险，抛出让 UI 直接 catch 展示文案比返回 Result 更简洁。
 * 其余 11 个成员一律「失败返回值 + signals 信号」，绝不抛。
 */
class GatewayException(
    val code: GatewayErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** connect() 的结果。异常一律捕获后映射，不向上抛（gateway-contract.md §2.6 第 4 成员）。 */
sealed interface GatewayConnectResult {
    /** botOpenId 来自 BotIdentity.getOpenId()；botName 来自 getName()，可能为 null。 */
    data class Connected(val botOpenId: String, val botName: String? = null) : GatewayConnectResult
    data class Failed(
        val error: GatewayErrorCode,
        val larkCode: Int? = null,
        val larkMsg: String? = null,
        val retryable: Boolean = error == GatewayErrorCode.ConnectRetryable,
    ) : GatewayConnectResult
    data object BlockedByPeerInstance : GatewayConnectResult
}

// =====================================================================================
// 群信息 / 群历史 / 群成员 / 建群 / 拉人 / 撤回 / 表情回应 / 已读回执的渠道中立值类型。
// 命名不带 Lark 前缀——它们是 gateway 契约面的中立描述（为未来多 IM 渠道中立层减负）；
// 飞书专属语义只出现在 [SdkLarkGateway] 的实现侧与各成员 KDoc 的 scope 说明里。
// =====================================================================================

/**
 * 群/会话信息（`im.v1.chat.get`）。渠道中立命名。
 * [chatType] 原样透传飞书字面值（"private"/"group"/"external" 等），不做枚举收口——
 * 渠道间的类型学不齐，收口归更上层的归一化域。
 */
data class ChatInfo(
    val chatId: String,
    val name: String,
    val description: String,
    /** 群主 open_id（原样透传 ownerId；ownerIdType 恒按 open_id 请求）。 */
    val ownerOpenId: String,
    /** 成员数（SDK 返回的是 String 形态的数字；解析失败为 -1）。 */
    val memberCount: Int,
    val chatType: String,
)

/** 群成员简报（`im.v1.chatMembers.get`，member_id_type=open_id）。渠道中立命名。 */
data class ChatMemberBrief(
    val openId: String,
    val name: String,
)

/** 群成员分页（`im.v1.chatMembers.get`）。渠道中立命名；memberTotal 为 -1 表示响应缺该字段。 */
data class ChatMembersPage(
    val items: List<ChatMemberBrief>,
    val hasMore: Boolean,
    val nextPageToken: String?,
    val memberTotal: Int = -1,
)

/** 建群结果（`im.v1.chat.create`）。渠道中立命名。 */
data class ChatCreation(
    val chatId: String,
    /** 建群时未成功的 open_id 名单（无 InvalidIdList 字段 ⇒ 恒空，保留给将来渠道实现）。 */
    val failedOpenIds: List<String>,
)

/** 群成员操作结果（`im.v1.chatMembers.create` 拉人 / 同构的踢人）。渠道中立命名。 */
data class ChatMemberOpResult(
    val ok: Boolean,
    /** 未成功的 open_id 名单（invalid + notExisted + pendingApproval 合并——上层只关心「谁没进来」）。 */
    val failedOpenIds: List<String>,
)

/** 历史消息条目（`im.v1.message.list`）。渠道中立命名；正文摘要由 [parseHistoryTextBrief] 解析。 */
data class ChatHistoryItem(
    val messageId: String,
    /** 发送者 open_id（sender.id，请求带 userIdType/open_id 口径）。 */
    val senderOpenId: String,
    /** 创建时间毫秒（SDK 的 createTime 字符串形态数字 → long；解析失败为 0）。 */
    val createTimeMillis: Long,
    /** 消息类型字面值（"text"/"post"/"file"/"image"/… 原样透传）。 */
    val msgType: String,
    /** 正文摘要（[parseHistoryTextBrief] 产出；不支持类型落 `[不支持类型 <msgType>]`）。 */
    val textBrief: String,
)

/** 历史消息分页（`im.v1.message.list`）。渠道中立命名。 */
data class ChatHistoryPage(
    val items: List<ChatHistoryItem>,
    val hasMore: Boolean,
    val nextPageToken: String?,
)

/** 一次表情回应的简报（`im.v1.messageReaction.list`）。渠道中立命名。 */
data class ReactionBrief(
    val emojiKey: String,
    val operatorOpenId: String,
)

/** 已读回执分页（`im.v1.message.readUsers`，user_id_type=open_id）。渠道中立命名。 */
data class ReadReceiptPage(
    val readOpenIds: List<String>,
    val hasMore: Boolean,
    val nextPageToken: String?,
)

/**
 * 知识库节点解析结果（`wiki.v2.spaces.getNode`）。
 * 渠道中立命名。objToken 是节点对应云文档的实际 token——objType=="docx" 时即
 * `readDocumentText` 的 documentId（其余 objType 如 sheet/base 本轮工具面不消费，调用方按类型分流）。
 */
data class WikiNodeRef(
    /** 节点对应云文档的实际 token（objType=="docx" 时 = docx documentId）。 */
    val objToken: String,
    /** 云文档类型字面值（"docx"/"sheet"/"base"/… 原样透传）。 */
    val objType: String,
    /** 节点标题（原样透传；空可缺）。 */
    val title: String,
)
