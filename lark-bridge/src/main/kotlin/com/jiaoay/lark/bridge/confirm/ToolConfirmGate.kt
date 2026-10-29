// L2 写操作确认门——ConfirmService 的 agent 工具侧适配。
// 语义零新发明：防替答（chatId+senderOpenId）/ 超时=拒绝 / CANCELLED 随停止 kill，
//    全部复用 ConfirmService 既有状态机（ConfirmServiceTest 钉死）；本类只做「每消息绑定 + 适配层」。
package com.jiaoay.lark.bridge.confirm

import com.jiaoay.lark.bridge.BridgeLog

/**
 * L2 写操作确认门：`create_group_chat` 等跨会话写操作必须经用户确认后才执行。
 *
 * ## 适配关系
 * [ConfirmService] 的既有语义（原样复用，不重测）：
 * - [ConfirmService.newRequest] 产生确认请求（含防替答快照 chatId+senderOpenId）；
 * - 裁决入口在 [com.jiaoay.lark.bridge.handler.InteractionHandler]（确认卡按钮 / 文字「确认 &lt;id&gt;」）；
 * - [ConfirmService.awaitDecision] 挂起 agent 任务直到用户裁决（与 MCP server 确认卡同款挂起语义）；
 *   TIMEOUT 等同拒绝（钳制下限 10s）；CANCELLED（用户「停止」）随停止逻辑传播。
 *
 * ## fail-closed（红线 4）
 * 确认链不可用（[confirmService] 缺席 / [notifier] 缺席 ⇒ 用户根本看不到确认卡）⇒
 * [requireConfirmation] 返回 false，调用方（工具）必须返回引导文案**不执行写操作**——
 * **绝不静默放行**（没确认的建群是真实副作用，放行即事故）。
 *
 * ## 每消息件
 * chatId/senderOpenId 绑定在构造参数里（由装配点工厂按消息现造，同 [com.jiaoay.lark.bridge.pipeline.NativeChatSurface]
 * 的每消息形态）——工具闭包经 DispatchPayload 载体取用，不得自建（自建会丢防替答快照）。
 *
 * @param confirmService 确认状态机（null = 确认链未启用 ⇒ 恒拒绝）
 * @param notifier 确认卡发送口（[com.jiaoay.lark.bridge.confirm.ConfirmNotifier]；null = 用户看不到卡
 *   ⇒ 挂满超时才拒，体验断裂 ⇒ 同视为链不可用恒拒绝）
 * @param chatId 本消息会话（确认卡目标 + 防替答维度）
 * @param senderOpenId 本消息发送者（防替答维度——只有发起者能裁决）
 * @param timeoutMillis 确认超时毫秒（装配点经 `clampedConfirmTimeoutMillis(config.agent.confirm.timeoutSeconds)`
 *   现读传入——每消息现造时读一次，工具调用处不重复读配置）
 * @param log 桥接日志
 */
class ToolConfirmGate(
    private val confirmService: ConfirmService?,
    private val notifier: ConfirmNotifier?,
    private val chatId: String,
    private val senderOpenId: String,
    private val timeoutMillis: Long,
    private val log: BridgeLog = BridgeLog.Silent,
) {

    /** 判定结果：APPROVED = 放行；其余（DENIED/TIMEOUT/CANCELLED）= 拒绝（工具返回引导文案）。 */
    enum class Verdict { APPROVED, DENIED }

    /**
     * 要求用户确认一次写操作。挂起直到裁决 / 超时 / 取消。
     *
     * 🔴 链不可用（service 或 notifier 缺席 / chatId 或 senderOpenId 空白）⇒ **立即 DENIED**（fail-closed，
     * 不挂起——挂着等一个永远不来的裁决只会耗尽 agent 步数预算）。
     *
     * @param toolName 工具名（确认卡展示 + 审计）
     * @param inputSummary 写操作入参摘要（确认卡展示；服务侧再截断到 120 字符）
     * @param riskLevel 风险等级名（确认卡风险徽标行，取 `RiskClassifier.RiskLevel.name`；
     *   默认空串 = 未知/未分级 ⇒ 确认卡不渲染风险行。本类不做分级判定——工具侧自报等级，
     *   [com.jiaoay.lark.bridge.confirm.RiskClassifier] 的规则分级只服务 MCP server 路径）
     * @return [Verdict.APPROVED] = 放行执行；[Verdict.DENIED] = 用户拒绝 / 超时 / 取消 / 链不可用
     */
    suspend fun requireConfirmation(
        toolName: String,
        inputSummary: String,
        riskLevel: String = "",
    ): Verdict {
        val service = confirmService
        if (service == null || notifier == null) {
            log.warn("L2 写操作确认链不可用，fail-closed 拒绝 tool=$toolName chatId=$chatId")
            return Verdict.DENIED
        }
        if (chatId.isBlank() || senderOpenId.isBlank()) {
            log.warn("L2 写操作确认缺会话/发送者标识，fail-closed 拒绝 tool=$toolName")
            return Verdict.DENIED
        }
        // 生效超时记账（本构造字段就是 awaitDecision 将用的值——记账与生效同源单点）。
        val request = service.newRequest(
            chatId = chatId,
            senderOpenId = senderOpenId,
            toolName = toolName,
            inputSummary = inputSummary,
            riskLevel = riskLevel,
            effectiveTimeoutMillis = timeoutMillis,
        )
        // 发卡次序：newRequest 之后、awaitDecision 之前发确认卡（失败降级不阻断挂起等待——
        // 用户仍可用文字「确认 <id>」裁决；文字通道 id 取自 requestId 短标识，不依赖卡片）。
        val cardNotifier = notifier
        runCatching { cardNotifier.notify(request) }
            .onFailure { log.warn("L2 确认卡发送失败（降级为文字裁决通道）id=${request.requestId}: ${it.message}") }
        return when (service.awaitDecision(request, timeoutMillis)) {
            ConfirmService.Decision.APPROVED -> Verdict.APPROVED
            // DENIED / TIMEOUT / CANCELLED 三态对工具面统一 = 拒绝（语义对齐 MCP server：非批准即不执行）
            else -> Verdict.DENIED
        }
    }
}
