// ③ Execute 的入参上下文（每消息一个实例，由 ① intake 创建、② 准入、③ 执行全程复用，零额外分配）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.agent.AgentRunner
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.RouteRule
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget

/**
 * 一次消息处理的全部上下文（方案定义逐字：message / config / route / reply / progress / log / agent / processes）。
 *
 * ## 为什么 [route] 是可写的（且这不是设计瑕疵）
 * `MessageHandler.match(ctx)` 需要 ctx，而 ctx 又要携带命中的 route——先有鸡还是先有蛋。
 * 解法：**ctx 每消息只构造一次**，`PipelineSnapshot.resolve` 在逐个候选 handler 试匹配时把候选 route
 * 写进 [route]，命中即定格（未命中则置 null）。
 * 这样热路径**零查表、零 handler 构造、零 ctx 复制**（方案「预计算 + 热路径零分配」的直接要求），
 * 且 🔴 **无竞态**：resolve 只在 ① intake 的**单协程**里跑（「Intake 单消费者本身就是并发原语」），
 * 到 ③ Execute 时 route 早已定格且任务协程只读。
 *
 * ## HOT 语义
 * [config] 是**构造时刻**的快照引用。配置热替换（`PipelineSnapshot` 原子替换）后，
 * 在途任务继续用旧 config 跑完 ⇒ 不断连、不中断（CDS §4.4 applyMode=HOT 的可观测结果）。
 * handler 若需读「最新」配置，走 `HandlerDeps.configProvider()`，不要用 `ctx.config`。
 */
class RequestContext(
    val message: InboundMessage,
    val config: BridgeConfig,
    route: RouteRule?,
    val reply: ReplySink,
    val progress: ProgressSink,
    val log: BridgeLog,
    val agent: AgentRunner,
    val processes: ProcessRegistry,
) {

    /** 命中的 route；null ⇒ 未命中任何 handler（走 `runUnhandled` 兜底）。 */
    var route: RouteRule? = route
        internal set

    /**
     * 发送者显示名（由 [com.jiaoay.lark.bridge.observe.UserNameResolver] 在 Admission→Execute 边界解析后写入）。
     *
     * 默认空串：SDK 的 `InboundMessage.senderName` 恒为 null（字节码实测，S4 真机确认），
     *    名字一律通过 `MessageGateway.resolveSenderName` 异步补拉。
     * 🔴 不得修改 `InboundMessage`（网关侧归一化模型，不被桥接侧污染）。
     * handler / Observe 阶段读此属性获取已解析的名字（空串 = 未解析/无权限降级）。
     */
    var senderName: String = ""
        internal set

    /** 会话 key = `chatId + (threadId ?: rootId ?: "")`（网关契约已固化该算法，此处直接复用）。 */
    val sessionKey: String get() = message.sessionKey

    val chatId: String get() = message.chatId

    /** 出站目标：群消息回群，私聊回用户（与网关 `LarkTarget` 的两个子类一一对应）。 */
    val target: LarkTarget get() = reply.target

    /** 消息正文（`InboundMessage.text`）。 */
    val text: String get() = message.text

    /** 归一化后的消息种类（TEXT/IMAGE/FILE/…/CARD_ACTION）。 */
    val inboundKind: InboundKind get() = message.kind

    /** 是否群聊。 */
    val isGroup: Boolean get() = message.chatType == com.jiaoay.lark.gateway.ChatType.GROUP

    /** 是否卡片回传（InteractionHandler 的入口判定）。 */
    val isCardAction: Boolean get() = message.cardAction != null || message.kind == InboundKind.CARD_ACTION

    /** 把 route 窄化成具体子类型（handler 内部用：`ctx.typedRoute<CommandRoute>()?.promptTemplate`）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : RouteRule> typedRoute(): T? = route as? T

    /** 便捷：读当前生效配置（HOT，永远最新）。 */
    val liveConfig: BridgeConfig get() = config

    override fun toString(): String =
        "RequestContext(messageId=${message.messageId}, chatId=$chatId, kind=$inboundKind, route=${route?.id})"
}
