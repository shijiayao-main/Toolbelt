package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.tools.SysServiceTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.SysWriteTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.lark.bridge.confirm.ToolConfirmGate
import com.jiaoay.lark.bridge.pipeline.ReplySink
import com.jiaoay.lark.gateway.CardSpec

/**
 * sys.write_config / sys.service 逐动作授权面（桥接链实现，两个 core 侧门契约的装配侧）。
 *
 * ## 信任用户免卡与两卡兜底
 * [requireSessionAuthorization] 委托 [KoogSysSessionGate]（信任用户名单判定：名单内放行、
 * 名单外进不了域）；[requireActionApproval] 对信任用户直接放行（免卡）；非信任用户被域准入
 * 拒绝、到不了本卡——两卡（说明卡 + 确认卡）仅作装配兜底。
 *
 * ## 动作卡形态（两卡：说明卡 + 确认卡——全文/diff 独立字段）
 * 完整 diff/argv/回滚配方装不下确认卡被服务侧截断到 120 字符的 inputSummary——先经
 * 说明卡发全文（diff 已在工具层过脱敏器且超展示预算即拒执，卡面恒完整无截断），再经
 * [ToolConfirmGate] 发确认卡：防替答（chatId+senderOpenId 只有发起者能裁决）/ 超时=拒绝 /
 * 链不可用立即拒绝——既有语义零新发明。说明卡发送失败 = fail-closed 拒绝（用户没看到
 * 完整载荷的批准违背「全文独立字段」的卡面契约）。
 */
internal class KoogSysWriteGate(
    private val sessionGate: KoogSysSessionGate,
    private val confirmGate: ToolConfirmGate,
    /** 动作说明卡发送口；null = 发不出说明卡 ⇒ fail-closed 拒绝 */
    private val reply: ReplySink?,
) : SysWriteTools.SysWriteGate {

    override suspend fun requireSessionAuthorization(toolName: String): Boolean =
        sessionGate.requireSessionAuthorization(toolName)

    override val familyEscapeGate: ToolExecutionContext.PathEscapeGate
        get() = sessionGate.familyEscapeGate

    override val trigger: String? get() = sessionGate.trigger

    override suspend fun requireActionApproval(card: SysWriteTools.WriteActionCard): Boolean {
        if (sessionGate.isTrustedSender()) return true
        return twoCardApproval(
            reply = reply,
            confirmGate = confirmGate,
            title = WRITE_CARD_TITLE,
            markdown = writeCardMarkdown(card),
            toolName = "sys.write_config 配置写入（${card.targetPath}）",
            inputSummary = "将覆写 ${card.targetPath}——完整前后 diff 与回滚配方见上方说明卡",
            riskLevel = "HIGH",
        )
    }

    private companion object {
        const val WRITE_CARD_TITLE = "✍️ sys.write_config 配置写入确认说明"

        /** 写入说明卡正文：完整 diff（工具层已脱敏、恒无截断）+ 快照 + 回滚配方 */
        fun writeCardMarkdown(card: SysWriteTools.WriteActionCard): String = buildString {
            appendLine("**写入目标**：`${card.targetPath}`（族 ${card.family}）")
            appendLine()
            appendLine("**写前快照**：${card.backupPath?.let { "`$it`（时间戳 .bak，可恢复）" } ?: "无（本次为新建文件）"}")
            appendLine()
            appendLine("**将写入的完整前后 diff**（凭据形态已脱敏为 ***；本卡即全文，无截断）：")
            appendLine("```")
            appendLine(card.diff)
            appendLine("```")
            appendLine()
            appendLine("**回滚配方**：${card.rollback}")
            appendLine()
            append("本卡对应紧随其后的确认卡；一次确认仅本次写入有效（会话授权不覆盖写动作），超时按拒绝处理。")
        }
    }
}

/** sys.service 逐动作授权面（桥接链实现，形态与 [KoogSysWriteGate] 同构——两卡 + 逐动作卡）。 */
internal class KoogSysServiceGate(
    private val sessionGate: KoogSysSessionGate,
    private val confirmGate: ToolConfirmGate,
    private val reply: ReplySink?,
) : SysServiceTools.SysServiceGate {

    override suspend fun requireSessionAuthorization(toolName: String): Boolean =
        sessionGate.requireSessionAuthorization(toolName)

    override val familyEscapeGate: ToolExecutionContext.PathEscapeGate
        get() = sessionGate.familyEscapeGate

    override val trigger: String? get() = sessionGate.trigger

    override suspend fun requireActionApproval(card: SysServiceTools.ServiceActionCard): Boolean {
        if (sessionGate.isTrustedSender()) return true
        return twoCardApproval(
            reply = reply,
            confirmGate = confirmGate,
            title = SERVICE_CARD_TITLE,
            markdown = serviceCardMarkdown(card),
            toolName = "sys.service 服务动作（${card.actionKey} ${card.service}）",
            inputSummary = "将执行：${card.argv.joinToString(" ")}——完整命令、风险与回滚配方见上方说明卡",
            riskLevel = card.riskBadge,
        )
    }

    private companion object {
        const val SERVICE_CARD_TITLE = "🔁 sys.service 服务动作确认说明"

        /** 服务动作说明卡正文：完整 argv + 语义 + DESTRUCTIVE 徽标 + 原状态 + 回滚配方 */
        fun serviceCardMarkdown(card: SysServiceTools.ServiceActionCard): String = buildString {
            appendLine("**动作**：${card.label}（${card.actionKey}，服务 ${card.service}）")
            appendLine()
            appendLine("**将执行的完整命令**：")
            appendLine("`${card.argv.joinToString(" ")}`")
            appendLine()
            appendLine("**说明**：${card.semantics}")
            appendLine()
            appendLine("**风险**：${card.riskBadge}（用户级服务状态变更动作，执行前已捕获服务原状态）")
            if (card.preState.isNotBlank()) {
                appendLine()
                appendLine("**执行前服务状态**（is-active）：${card.preState}")
            }
            appendLine()
            appendLine("**回滚配方**：${card.rollback}")
            appendLine()
            append("本卡对应紧随其后的确认卡；一次确认仅本动作有效（会话授权不覆盖服务动作），超时按拒绝处理。")
        }
    }
}

/**
 * 两卡批准共用缝：先发说明卡（全文载体）再经确认卡裁决——说明卡发送失败 = fail-closed
 * 拒绝（用户没看到完整载荷的批准不成立）。与 [KoogSysRepairGate] 的同款形态共享实现。
 */
private suspend fun twoCardApproval(
    reply: ReplySink?,
    confirmGate: ToolConfirmGate,
    title: String,
    markdown: String,
    toolName: String,
    inputSummary: String,
    riskLevel: String,
): Boolean {
    val explained = reply != null &&
        runCatching {
            reply.card(CardSpec(title = title, markdown = markdown))
        }.getOrDefault(null)?.ok == true
    if (!explained) {
        return false
    }
    val verdict = confirmGate.requireConfirmation(
        toolName = toolName,
        inputSummary = inputSummary,
        riskLevel = riskLevel,
    )
    return verdict == ToolConfirmGate.Verdict.APPROVED
}
