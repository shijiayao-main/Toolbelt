// 能力分级模型 lib 侧真源。
// 纯函数零状态——L1 恒注册（代码里写死）；L2 跨会话能力恒注册（授权闸收敛为
//    飞书后台 scope 单一闸——scope 未开通时工具回落引导文案，fail-closed 语义由
//    工具面承接而非注册面）；L3 写死排除（本文件不表达，守护测试钉）。
package com.jiaoay.lark.bridge.config

import kotlinx.serialization.Serializable

/**
 * L2 细分授权 token（与后台 scope 申请一一对应；工具名→token 映射见 [NativeCapability.scopeHintOf]）。
 * token 串 = 枚举 name（`agent.nativeTools.l2Grants` 配置段已随 v11→v12 迁移退役——
 * 本枚举保留为 scope hint 与 UI 文案单源）。
 *
 * ## 六行文案下沉（设计稿锚）
 * [label]（中文名）与 [description]（一句人话）与 [scopeHint] 同源 lib 单点——
 * 配置页能力中心的勾选清单（GrantChecklistRow）直读本枚举，UI 层零文案复制（防两处漂移）。
 * 文案真源 = 本枚举六行全文案表（UI 层零文案复制；原稿出处登记文件见 docs/architecture.md「注释治理」节）。
 */
enum class NativeToolGrant(val label: String, val description: String, val scopeHint: String) {
    /** 拉取会话历史消息（im.v1.messages list）。 */
    CHAT_HISTORY(
        label = "拉取会话历史",
        description = "让机器人读取本会话之外的历史消息",
        scopeHint = "im:message.history:readonly",
    ),

    /** 查询会话信息（im.v1.chats get）。 */
    CHAT_INFO(
        label = "查群信息",
        description = "群名称、成员列表",
        scopeHint = "im:chat",
    ),

    /** 建群/拉人（im.v1.chats create / members）。 */
    CHAT_MANAGE(
        label = "建群拉人",
        description = "新群自动把机器人拉进去（建群须用户确认卡）",
        scopeHint = "im:chat",
    ),

    /** 通讯录用户基础信息（contact.v3.user.get 基础面）。 */
    LOOKUP_USER(
        label = "查用户",
        description = "显示用户名字与头像（PII 最小化）",
        scopeHint = "contact:contact.base:readonly",
    ),

    /** 读知识库文档（wiki 节点解析）。 */
    READ_WIKI(
        label = "读知识库",
        description = "发一条 wiki 链接给机器人即可验证",
        scopeHint = "wiki:wiki:readonly",
    ),

    /** 读文档正文（docx 原文；/docx/ 链接分流后需要）。 */
    READ_DOC(
        label = "读文档",
        description = "飞书文档链接 → 纯文本正文",
        scopeHint = "docx:document:readonly",
    ),
}

/**
 * 能力分级真源（纯函数，lib 侧）：
 * - L1 工具名单 = [l1Tools]（agent 工具注册面与 UI 能力卡共同消费）；
 * - L2 工具名单 = [l2Tools]（恒注册名单——授权闸收敛为后台 scope 单一闸）；
 * - 工具名 → grant 映射 = [scopeHintOf]（UI 能力卡注脚与工具降级文案共同消费）；
 * - 六行中文文案（label/description）= 配置页能力中心直读源。
 */
object NativeCapability {

    /**
     * L1 三件（当前会话内原生操作，**定义即恒注册**）：
     * - send_image：发图片产物（两步 uploadImage→Outbound.image）；
     * - recall_own_message：撤回机器人自己在当前会话发的消息（账本双重守护）；
     * - reply_in_thread：群聊话题内回复（非群聊回落普通回复）。
     * 🔴 名单与 KoogAgentRunner 的工具 name 字符串逐字一致——由 KoogAgentRunnerTest 名称一致性断言钉死。
     */
    fun l1Tools(): List<String> = listOf("send_image", "recall_own_message", "reply_in_thread")

    /**
     * L2 五件（跨会话能力，**恒注册**——`agent.nativeTools` 双闸配置段已退役，
     * 授权闸收敛为飞书后台 scope 单一闸：scope 未开通时工具在册并回落 scope 引导文案）：
     * fetch_chat_history / get_chat_info / create_group_chat / lookup_user / read_wiki_document。
     * 🔴 名单与 KoogAgentRunner 的工具 name 字符串逐字一致——由 KoogAgentRunnerTest 名称一致性断言钉死。
     */
    fun l2Tools(): List<String> = listOf(
        "fetch_chat_history",
        "get_chat_info",
        "create_group_chat",
        "lookup_user",
        "read_wiki_document",
    )

    /**
     * 工具名 → 需申请的 scope hint（UI 能力卡「需后台开通 <scope> 并发版」注脚 +
     * 工具降级文案消费）；未知工具名返回 null。L1 三件不需要额外 scope（出站/撤回
     * 走 send_as_bot 族），不进表。
     */
    fun scopeHintOf(toolName: String): String? = L2_TOOL_GRANTS[toolName]?.scopeHint

    /** L2 工具名 → grant token（单一映射源，防 UI 与降级文案漂移）。 */
    private val L2_TOOL_GRANTS: Map<String, NativeToolGrant> = mapOf(
        "fetch_chat_history" to NativeToolGrant.CHAT_HISTORY,
        "get_chat_info" to NativeToolGrant.CHAT_INFO,
        "create_group_chat" to NativeToolGrant.CHAT_MANAGE,
        "lookup_user" to NativeToolGrant.LOOKUP_USER,
        "read_wiki_document" to NativeToolGrant.READ_WIKI,
    )
}
