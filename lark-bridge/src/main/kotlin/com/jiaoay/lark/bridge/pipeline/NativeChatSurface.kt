// L2 跨会话原生能力面（NativeChatSurface）——
// 工具面（feature-larkbridge KoogAgentRunner L2 五工具）与网关（MessageGateway）之间的
// 会话作用域收窄视图。桥接 lib 侧定义契约 + 生产实现；宿主（feature）经 DispatchPayload 载体消费。
package com.jiaoay.lark.bridge.pipeline

/**
 * L2 跨会话原生能力面。
 *
 * ## 渠道中立与职责收窄
 * 本接口**不出现任何飞书类型**——它是对 [com.jiaoay.lark.gateway.MessageGateway] 读写成员的
 * **会话作用域收窄视图**：工具经它操作「当前会话」；跨会话参数（chatId）被装配点钉死在实现闭包里，
 * **不进工具 Args**（反射守护钉死形态：L2 工具 Args 里出现 chat_id 参数即红）。
 * 「跨会话」指能力面覆盖会话的**历史/成员/信息**（当前会话自身的纵深数据），不指可指定任意会话。
 *
 * ## 为什么每消息现造（而非装配期单例）
 * chatId 绑定在实现闭包里，每条消息的 chatId 不同 ⇒ 实例是**每消息件**。装配点
 * （[com.jiaoay.lark.bridge.BridgeAssembler]）只在 [HandlerDeps] 注入**工厂闭包**（按消息现造），
 * 不持有本接口的单例——与 `RequestContextFactory.replyOverride` 每消息造 [ReplySink] 同款先例。
 *
 * ## fail-closed 语义
 * - `HandlerDeps.nativeSurfaceFactory` 为默认 `{ null }`（CLI 形态 / 测试窄面）⇒ L2 五工具**全不注册**；
 * - 工具调用时实现返回 null（scope 未开通 / 未连接）⇒ 工具回落 scope 引导文案，**绝不抛异常**。
 *
 * @see com.jiaoay.lark.bridge.confirm.ToolConfirmGate L2 写操作（create_group_chat）的确认门
 */
interface NativeChatSurface {

    /**
     * 拉取当前会话的历史消息（分页）。
     * @param limit 单页条数（工具面钳制 1..50）
     * @param pageToken 翻页 token（null = 第一页）
     * @return 一页历史；能力缺席（scope 未开通 / 未连接）返回 null
     */
    suspend fun fetchChatHistory(limit: Int, pageToken: String?): com.jiaoay.lark.gateway.ChatHistoryPage?

    /**
     * 查询当前会话信息 + 首 50 名成员，返回**已格式化**的可读文本（含会话名/描述/成员数/成员清单）。
     * 文本形态在实现侧定（工具保持薄壳）；能力缺席返回空串（工具据以回落 scope 引导文案）。
     */
    suspend fun chatInfoWithMembers(): String

    /**
     * 创建群聊（机器人 owner）。🔴 写操作——调用方（create_group_chat 工具）必须先过
     * [com.jiaoay.lark.bridge.confirm.ToolConfirmGate] 确认门，本方法不做确认（单一职责）。
     * @return 建群结果；能力缺席 / 建群失败返回 null
     */
    suspend fun createGroupChat(name: String, memberOpenIds: List<String>): com.jiaoay.lark.gateway.ChatCreation?

    /**
     * 按 open_id 查用户（PII 最小化：只回名字 + 头像，复用 [com.jiaoay.lark.gateway.SenderProfile] 既有面）。
     * @return 用户简报；查不到（scope 未开通 / open_id 无效）返回 null
     */
    suspend fun lookupUser(openId: String): com.jiaoay.lark.gateway.SenderProfile?

    /**
     * 读文档正文（URL 分流在实现侧）：
     * - `/wiki/` 链接或节点 token：先 [com.jiaoay.lark.gateway.MessageGateway.resolveWikiNode]
     *   解析出 documentId 再读正文（两步）；
     * - `/docx/` 链接或 `doxc…` token：直读。
     * 超长截断 + `（内容超长已截断）` 标记在实现侧（gateway 返回全文）。
     * @return 文档正文文本；能力缺席 / 解析失败 / scope 未开通返回空串（工具据以回落引导文案）
     */
    suspend fun readWikiDocument(urlOrToken: String): String
}

/**
 * [NativeChatSurface] 的生产实现：gateway + 当前消息 chatId 捕获进闭包（每消息现造，装配点
 * `BridgeAssembler` 的 `nativeSurfaceFactory` 调用）。零状态（chatId 是 val）——构造即定格，
 * 不存在跨消息串味面。
 *
 * @param gateway 网关（读成员的消费面；本类**只读不写**——建群走 [createGroupChat]，出站消息不经此）。
 * @param chatId 本消息所属会话（🔴 钉死维度：历史/成员/信息查询都落在这里，工具 Args 无 chat_id）。
 */
internal class GatewayNativeChatSurface(
    private val gateway: com.jiaoay.lark.gateway.MessageGateway,
    /** 当前会话 id（构造即定格）。 */
    val chatId: String,
) : NativeChatSurface {

    override suspend fun fetchChatHistory(
        limit: Int,
        pageToken: String?,
    ): com.jiaoay.lark.gateway.ChatHistoryPage? = gateway.listMessages(chatId, limit, pageToken)

    override suspend fun chatInfoWithMembers(): String {
        val info = gateway.getChatInfo(chatId) ?: return ""
        // 首页成员（单页 50；成员很多时以 memberTotal 告知不全——不循环翻页，控制一次调用的 API 成本）
        val members = gateway.listChatMembers(chatId, pageSize = MEMBER_PAGE_SIZE)
        return buildString {
            appendLine("会话名：${info.name.ifBlank { "（未命名）" }}")
            if (info.description.isNotBlank()) appendLine("描述：${info.description}")
            appendLine("类型：${info.chatType.ifBlank { "unknown" }}")
            if (info.memberCount >= 0) appendLine("成员数：${info.memberCount}")
            if (members != null && members.items.isNotEmpty()) {
                appendLine("成员（前 ${members.items.size} 名）：")
                members.items.forEach { m ->
                    append("- ").append(m.openId).append(if (m.name.isNotBlank()) "（${m.name}）" else "")
                    appendLine()
                }
                if (members.hasMore) {
                    appendLine("…（成员未列全，共约 ${if (info.memberCount >= 0) info.memberCount else members.memberTotal} 人）")
                }
            }
        }.trimEnd()
    }

    override suspend fun createGroupChat(
        name: String,
        memberOpenIds: List<String>,
    ): com.jiaoay.lark.gateway.ChatCreation? = gateway.createChat(name, memberOpenIds)

    override suspend fun lookupUser(openId: String): com.jiaoay.lark.gateway.SenderProfile? =
        gateway.resolveSenderProfile(openId)

    override suspend fun readWikiDocument(urlOrToken: String): String {
        val (kind, token) = splitWikiUrl(urlOrToken) ?: return ""
        return when (kind) {
            // /wiki/：节点 token → 两步解析（getNode → objToken 即 documentId）
            "wiki" -> {
                val node = gateway.resolveWikiNode(token) ?: return ""
                if (node.objType != "docx") return "" // 非 docx 云文档（sheet/base 等）本轮不消费
                gateway.readDocumentText(node.objToken)
            }
            // /docx/：直读
            else -> gateway.readDocumentText(token)
        }?.let { truncate(it) } ?: ""
    }

    companion object {
        /** 单页成员数（飞书 getChatMembers 上限 100，取 50 控制一次性返回文本的规模）。 */
        internal const val MEMBER_PAGE_SIZE: Int = 50

        /** 正文截断上限（字符；规划稿语义：30k + truncated 标记，截断在 surface 层不在 gateway 层）。 */
        internal const val DOCUMENT_TEXT_MAX_CHARS: Int = 30_000

        /** 截断标记（原文追加，LLM 可感知「下面还有内容」）。 */
        internal const val TRUNCATED_SUFFIX: String = "\n（内容超长已截断）"

        /**
         * URL/token 分流（纯函数，直测缝）：
         * - 含 `/wiki/` 段：取其后首段为节点 token，返回 `"wiki" to token`；
         * - 含 `/docx/` 段：取其后首段为文档 id，返回 `"docx" to token`；
         * - 无 URL 形态：裸 token 按前缀嗅探（`wik…` 常见形态不可靠——一律当 wiki 节点 token，
         *   解析失败由调用方回落空串；docx 裸 token 场景让 LLM 传完整 URL）。
         * 解析不出返回 null。
         */
        internal fun splitWikiUrl(urlOrToken: String): Pair<String, String>? {
            val s = urlOrToken.trim()
            if (s.isEmpty()) return null
            val takeToken = fun(after: String): String? =
                s.substringAfter(after, "").takeIf { it.isNotEmpty() }
                    ?.split('/', '?', '&', ')', ']')
                    ?.firstOrNull { it.isNotBlank() }
            for ((marker, kind) in listOf("/wiki/" to "wiki", "/docx/" to "docx")) {
                val idx = s.indexOf(marker)
                if (idx >= 0) {
                    val token = s.substring(idx + marker.length)
                        .split('/', '?', '&', ')', ']')
                        .firstOrNull { it.isNotBlank() }
                        ?: return null
                    return kind to token
                }
            }
            // 裸 token：非空且不含 URL 分隔符——按 wiki 节点 token 处理（默认分流口径）
            if (!s.contains('/') && !s.contains('?')) return "wiki" to s
            return takeToken("")?.let { "wiki" to it }
        }

        /** 截断：超上限裁断并追加标记；不超原样返回。 */
        internal fun truncate(text: String): String =
            if (text.length > DOCUMENT_TEXT_MAX_CHARS) {
                text.take(DOCUMENT_TEXT_MAX_CHARS) + TRUNCATED_SUFFIX
            } else {
                text
            }
    }
}
