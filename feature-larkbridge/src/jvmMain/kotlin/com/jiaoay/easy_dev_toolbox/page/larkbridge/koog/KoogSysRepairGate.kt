package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.tools.SysRepairTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.lark.bridge.confirm.RiskClassifier
import com.jiaoay.lark.bridge.confirm.ToolConfirmGate
import com.jiaoay.lark.bridge.pipeline.ReplySink
import com.jiaoay.lark.gateway.CardSpec

/**
 * sys.repair 逐动作授权面（桥接链实现，[SysRepairTools.SysRepairGate] 契约的装配侧）。
 *
 * ## 会话档与动作卡分层（L0 ≠ L1b）
 * [requireSessionAuthorization] 委托 [KoogSysSessionGate]——sys.* 诊断域同一会话授权档
 * （一次批准 = 一个会话，不持久化）；[requireActionApproval] 是 L1b 逐动作确认卡——
 * **会话授权不自动覆盖修复动作**，每动作必经本卡批准。
 *
 * ## 动作卡形态（两卡：说明卡 + 确认卡）
 * 完整 argv/语义说明/回滚配方装不下确认卡被服务侧截断到 120 字符的 inputSummary——
 * 信任用户免卡直接执行（[com.jiaoay.lark.bridge.confirm.TrustPolicy]）；其余用户
 * 先经说明卡发全文（回滚配方含执行前捕获的原值），再经 [ToolConfirmGate] 发确认卡：
 * 防替答（chatId+senderOpenId 只有发起者能裁决）/ 超时=拒绝 / 链不可用立即拒绝——
 * 既有语义零新发明。说明卡发送失败 = fail-closed 拒绝（用户没看到完整命令与回滚配方的
 * 批准违背「全文独立字段」的卡面契约）。
 */
internal class KoogSysRepairGate(
    private val sessionGate: KoogSysSessionGate,
    private val confirmGate: ToolConfirmGate,
    /** 动作说明卡发送口；null = 发不出说明卡 ⇒ fail-closed 拒绝 */
    private val reply: ReplySink?,
) : SysRepairTools.SysRepairGate {

    override suspend fun requireSessionAuthorization(toolName: String): Boolean =
        sessionGate.requireSessionAuthorization(toolName)

    override val familyEscapeGate: ToolExecutionContext.PathEscapeGate
        get() = sessionGate.familyEscapeGate

    override val trigger: String? get() = sessionGate.trigger

    override suspend fun requireActionApproval(card: SysRepairTools.RepairActionCard): Boolean {
        if (sessionGate.isTrustedSender()) return true
        val reply = reply
        val explained = reply != null &&
            runCatching {
                reply.card(CardSpec(title = ACTION_CARD_TITLE, markdown = actionCardMarkdown(card)))
            }.getOrDefault(null)?.ok == true
        if (!explained) {
            return false
        }
        val verdict = confirmGate.requireConfirmation(
            toolName = "sys.repair 修复动作（${card.label}）",
            inputSummary = "将执行：${card.argv.joinToString(" ")}——完整命令、风险与回滚配方见上方说明卡",
            riskLevel = card.riskBadge,
        )
        return verdict == ToolConfirmGate.Verdict.APPROVED
    }

    private companion object {
        const val ACTION_CARD_TITLE = "🛠 sys.repair 修复动作确认说明"

        /** 动作说明卡正文：完整 argv + 语义 + 风险 + 回滚配方（全文独立字段，不靠 120 字符摘要） */
        fun actionCardMarkdown(card: SysRepairTools.RepairActionCard): String = buildString {
            appendLine("**动作**：${card.label}（${card.actionKey}）")
            appendLine()
            appendLine("**将执行的完整命令**：")
            appendLine("`${card.argv.joinToString(" ")}`")
            appendLine()
            appendLine("**说明**：${card.semantics}")
            appendLine()
            appendLine("**风险**：${card.riskBadge}（写系统状态动作，执行前已捕获原值快照）")
            appendLine()
            appendLine("**回滚配方**：${card.rollback}")
            appendLine()
            append("本卡对应紧随其后的确认卡；一次确认仅本动作有效（会话授权不覆盖修复动作），超时按拒绝处理。")
        }
    }
}
