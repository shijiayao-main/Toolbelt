// 内部工具集：Koog 调度 agent 的 per-message 能力面载体。
// 🔴 统一载体：恒包 [DispatchPayload]——无「allowedFiles 为空时返回
//    裸 ReplySink」的快路径。裸快路径阻断的正是纯文本闲聊场景（react_emoji / fetch_quoted_context /
//    update_own_card 的主场景——无引用文件但需要会话作用域能力面）。
package com.jiaoay.lark.bridge.pipeline

import java.io.File

/**
 * 进程内调度 agent（Koog）的 per-message 载体（[com.jiaoay.lark.bridge.agent.AgentRequest.opaque]）。
 *
 * ## 载体字段（恒包——三组能力面）
 * - **出站能力面**：[reply]（[ReplySink]）——send_file / send_card / reply_direct 工具的原始依赖；
 * - **工具围栏**：[allowedFiles]（本消息引用文件 + 其下载目录/预处理产物——防 LLM 幻觉路径与
 *   文件内容注入，语义对齐 :core ToolExecutionContext；空 = 无文件上下文，文件工具不注册）；
 * - **会话作用域**（三新工具的钉子）：
 *   - [messageId] / [chatId]：用户本条消息的标识与会话——工具「当前会话作用域」校验依据
 *     （react_emoji 的目标消息、update_own_card 的目标会话、fetch_quoted_context 的账本查询维度）；
 *   - [quotedMessageId]：用户本条消息引用的 messageId（null = 无引用）——fetch_quoted_context
 *     的初始目标；
 *   - [replyLedger]：机器人回复账本引用（fetch_quoted_context 读它；null = 上下文取回能力关闭）。
 *
 * 🔴 **会话作用域硬约束（写进 KDoc 防误扩）**：任何经本载体的工具**不得**跨出 [chatId] 标识的
 *    当前会话——不发任意 chat 消息、不拉群历史/成员、不做全局搜索、不碰生命周期与凭证。
 *    跨会话能力（proactive 推送、跨会话引用）被明确排除。
 *
 * **零侵入兼容**：[replySinkOf] 剥壳读 [reply]；不读 opaque 的执行器实现（子进程形态）不受影响，
 *   两形态互不感知。存量测试用裸 ReplySink 构造 request 的，`replySinkOf` 的 `is ReplySink`
 *   分支照旧兼容。
 */
data class DispatchPayload(
    /** 出站能力面（send_file / send_card / reply_direct 工具的原始依赖）。 */
    val reply: ReplySink,
    /** 工具围栏初始允许集（空 = 无文件上下文，文件工具不应注册）。 */
    val allowedFiles: List<File>,
    /** 桥接 xlog 私钥（decrypt_log 用；空 = 未配置，工具应如实告知 LLM）。 */
    val xlogPrivateKey: String,
    /** 用户本条消息的 messageId（react_emoji 默认目标）。 */
    val messageId: String,
    /** 当前会话 chatId（会话作用域校验维度；null 罕见——仅测试窄面）。 */
    val chatId: String?,
    /** 用户本条消息引用的 messageId（null = 无引用）——fetch_quoted_context 初始目标。 */
    val quotedMessageId: String?,
    /** 机器人回复账本引用（fetch_quoted_context 查它；null = 能力关闭，工具应如实告知）。 */
    val replyLedger: com.jiaoay.lark.bridge.files.CardReplyLedger?,
    /**
     * 当前会话类型（reply_in_thread 工具的群/单聊分流判据；
     * null = 未知——存量测试窄面，工具按「非群聊」回落普通回复）。
     */
    val chatType: com.jiaoay.lark.gateway.ChatType? = null,
    /**
     * L2 跨会话原生能力面（fetch_chat_history 等五工具的读写面；
     * chatId 已钉死在实现闭包里——工具 Args 无 chat_id）。null = 未接线（CLI 形态/测试窄面）⇒
     * L2 五工具全不注册（fail-closed）。
     */
    val nativeSurface: NativeChatSurface? = null,
    /**
     * L2 写操作确认门（create_group_chat 的强制确认面；chatId/senderOpenId 已绑定）。
     * null = 确认链不可用 ⇒ 写操作 fail-closed 拒绝。
     */
    val confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate? = null,
) {
    companion object {
        /**
         * 载体组装（恒包——不再有裸 ReplySink 快路径）。
         * 会话作用域四件 + 账本引用由 runAgentPipeline 装配点从 RequestContext 现取。
         * nativeSurface/confirmGate 两件同点现造传入；默认 null ⇒ 存量调用零改动。
         */
        fun of(
            reply: ReplySink,
            allowedFiles: List<File>,
            xlogPrivateKey: String,
            messageId: String,
            chatId: String?,
            quotedMessageId: String?,
            replyLedger: com.jiaoay.lark.bridge.files.CardReplyLedger?,
            chatType: com.jiaoay.lark.gateway.ChatType? = null,
            nativeSurface: NativeChatSurface? = null,
            confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate? = null,
        ): DispatchPayload = DispatchPayload(
            reply = reply,
            allowedFiles = allowedFiles,
            xlogPrivateKey = xlogPrivateKey,
            messageId = messageId,
            chatId = chatId,
            quotedMessageId = quotedMessageId,
            replyLedger = replyLedger,
            chatType = chatType,
            nativeSurface = nativeSurface,
            confirmGate = confirmGate,
        )
    }
}
