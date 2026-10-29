package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.easy_dev_toolbox.core.llm.tools.WsRunTaskTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.WsSessionGate
import com.jiaoay.easy_dev_toolbox.core.llm.tools.WsWriteTools
import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.bridge.confirm.RiskClassifier
import com.jiaoay.lark.bridge.confirm.TrustPolicy
import com.jiaoay.lark.bridge.confirm.ToolConfirmGate
import com.jiaoay.lark.bridge.pipeline.ReplySink
import com.jiaoay.lark.gateway.CardSpec
import java.io.File

/**
 * ws 域 / git 域门控面（桥接链实现，[WsSessionGate] 契约的装配侧）。
 *
 * ## 授权模型：按用户信任
 * `requireSessionAuthorization` 只判「本消息发送者是否在信任名单内」
 * （[TrustPolicy.isTrusted]）。取代原「本会话首次触发发说明卡 + 确认卡、按 chatId 键控」的
 * 临时授权模型——那个模型每次重启重批、且群聊任一成员批准即授权整个会话。
 *
 * 信任只覆盖**域准入**：`ws.write_file` / `ws.run_task` 仍各自逐动作确认卡
 * （[KoogWsWriteGate] / [KoogWsRunTaskGate]，与会话档无关）；git.read 只读零副作用。
 *
 * ## [wsEscapeGate] 保留
 * 工作区根内路径的 HITL 缝：路径 canonical 落在工作区根内才放行——路径越界的第二道防线，
 * 与授权模型解耦。
 *
 * @param trustedUsersProvider 读当前 `access.trustedUsers`（HOT）
 * @param senderOpenId 本消息发送者（唯一判定维度）
 * @param wsContext ws 专用围栏 context（工作区根 register 进允许集的落点）
 * @param wsRootProvider 工作区根（装配期从桥接配置解析）
 * @param overallTimeoutMillisProvider 整体预算供体（ws.run_task 软超时换算输入）
 */
internal class KoogWsSessionGate(
    private val trustedUsersProvider: () -> List<String>,
    private val senderOpenId: String,
    /** ws 专用围栏 context（工作区根 register 进允许集的落点） */
    private val wsContext: ToolExecutionContext,
    /** 工作区根（装配期从桥接配置解析；空工作区配置时本 gate 不应被构造——fail-closed 注册门控前置） */
    private val wsRootProvider: () -> File?,
    /** 整体预算供体（ws.run_task 软超时 0.8 系数换算输入；sessionGate 与 runTaskGate 共享） */
    internal val overallTimeoutMillisProvider: () -> Long = { 0L },
) : WsSessionGate {

    override val trigger: String = "sender=$senderOpenId"

    /** 本消息发送者是否信任用户（逐动作 Gate 复用——信任免逐动作卡，见 [TrustPolicy]）。 */
    internal fun isTrustedSender(): Boolean =
        TrustPolicy.isTrusted(AccessConfig(trustedUsers = trustedUsersProvider()), senderOpenId)

    private val escapeGate = WsEscapeGate(wsRootProvider)
    override val wsEscapeGate: ToolExecutionContext.PathEscapeGate get() = escapeGate

    override val wsRoot: File
        get() = wsRootProvider() ?: File("")

    override suspend fun requireSessionAuthorization(
        @Suppress("UNUSED_PARAMETER") toolName: String,
    ): Boolean {
        val access = AccessConfig(trustedUsers = trustedUsersProvider())
        if (!TrustPolicy.isTrusted(access, senderOpenId)) return false
        val root = wsRootProvider() ?: return false
        // 工作区根 register 进 ws 专用允许集（幂等；静态可审计的成员资格，非运行时免检通道）
        wsContext.register(root)
        return true
    }
}

/**
 * 工作区根内路径的 HITL 缝（ws 专用 context 的 escapeGate）：会话已授权 + canonical
 * 落在工作区根内才放行（[KoogSysSessionGate] 的 familyEscapeGate 同构）。
 */
internal class WsEscapeGate(
    private val wsRootProvider: () -> File?,
) : ToolExecutionContext.PathEscapeGate {

    override suspend fun onRequestAccess(path: File): Boolean {
        val root = wsRootProvider() ?: return false
        val rootCanon = runCatching { root.canonicalPath }.getOrNull() ?: return false
        val pathCanon = runCatching { path.canonicalPath }.getOrNull() ?: return false
        return pathCanon == rootCanon || pathCanon.startsWith(rootCanon + File.separator)
    }
}

/**
 * ws.write_file 逐写授权面（桥接链实现，[WsWriteTools.WsWriteGate] 契约装配侧）。
 * 形态与 [KoogSysWriteGate] 同构：两卡（说明卡全文 diff + 确认卡裁决）。
 */
internal class KoogWsWriteGate(
    private val sessionGate: KoogWsSessionGate,
    private val confirmGate: ToolConfirmGate,
    private val reply: ReplySink?,
) : WsWriteTools.WsWriteGate {

    override suspend fun requireSessionAuthorization(toolName: String): Boolean =
        sessionGate.requireSessionAuthorization(toolName)

    override val wsEscapeGate: ToolExecutionContext.PathEscapeGate get() = sessionGate.wsEscapeGate

    override val wsRoot: File get() = sessionGate.wsRoot

    override val trigger: String? get() = sessionGate.trigger

    override suspend fun requireActionApproval(card: WsWriteTools.WsWriteActionCard): Boolean {
        if (sessionGate.isTrustedSender()) return true
        return wsTwoCardApproval(
            reply = reply,
            confirmGate = confirmGate,
            title = WRITE_CARD_TITLE,
            markdown = writeCardMarkdown(card),
            toolName = "ws.write_file 工作区写入（${card.targetPath}）",
            inputSummary = "将写入 ${card.targetPath}——完整前后 diff 与回滚配方见上方说明卡",
            riskLevel = "HIGH",
        )
    }

    private companion object {
        const val WRITE_CARD_TITLE = "✍️ ws.write_file 工作区写入确认说明"

        fun writeCardMarkdown(card: WsWriteTools.WsWriteActionCard): String = buildString {
            appendLine("**写入目标**：`${card.targetPath}`")
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

/**
 * ws.run_task 逐任务授权面（桥接链实现，[WsRunTaskTools.WsRunTaskGate] 契约装配侧）。
 * 形态与 [KoogWsWriteGate] 同构：两卡（说明卡完整 argv + 确认卡裁决）。
 */
internal class KoogWsRunTaskGate(
    private val sessionGate: KoogWsSessionGate,
    private val confirmGate: ToolConfirmGate,
    private val reply: ReplySink?,
    /** 任务超时配置上限（分钟）——装配链从 agent.wsTools.runTaskTimeoutMinutes 现读注入 */
    private val configuredTimeoutMinutesProvider: () -> Int,
) : WsRunTaskTools.WsRunTaskGate {

    override suspend fun requireSessionAuthorization(toolName: String): Boolean =
        sessionGate.requireSessionAuthorization(toolName)

    override val wsEscapeGate: ToolExecutionContext.PathEscapeGate get() = sessionGate.wsEscapeGate

    override val wsRoot: File get() = sessionGate.wsRoot

    override val trigger: String? get() = sessionGate.trigger

    override val overallTimeoutMillisProvider: () -> Long
        get() = sessionGate.overallTimeoutMillisProvider

    override val configuredTimeoutMinutes: Int get() = configuredTimeoutMinutesProvider()

    override suspend fun requireActionApproval(card: WsRunTaskTools.RunTaskActionCard): Boolean {
        if (sessionGate.isTrustedSender()) return true
        return wsTwoCardApproval(
            reply = reply,
            confirmGate = confirmGate,
            title = TASK_CARD_TITLE,
            markdown = taskCardMarkdown(card),
            toolName = "ws.run_task 任务执行（${card.recipe} ${card.argv.drop(1).joinToString(" ")}）",
            inputSummary = "将在 ${card.cwd} 执行：${card.argv.joinToString(" ")}（超时 ${card.timeoutMinutes} 分钟）",
            riskLevel = RiskClassifier.RiskLevel.HIGH.name,
        )
    }

    private companion object {
        const val TASK_CARD_TITLE = "🛠️ ws.run_task 任务执行确认说明"

        fun taskCardMarkdown(card: WsRunTaskTools.RunTaskActionCard): String = buildString {
            appendLine("**任务配方**：`${card.recipe}`")
            appendLine()
            appendLine("**将执行的完整命令**：")
            appendLine("`${card.argv.joinToString(" ")}`")
            appendLine()
            appendLine("**工作目录**：`${card.cwd}`")
            appendLine("**超时**：${card.timeoutMinutes} 分钟（到点进程树级联终止）")
            appendLine()
            append("本卡对应紧随其后的确认卡；一次确认仅本次执行有效（会话授权不覆盖执行动作），超时按拒绝处理。")
        }
    }
}

/**
 * 两卡批准共用缝：先发说明卡（全文载体）再经确认卡裁决——说明卡发送失败 = fail-closed
 * 拒绝。与 KoogSysWriteServiceGates 的 twoCardApproval 同形态（分文件复刻，域间不共享
 * internal 可见性之外的耦合）。
 */
private suspend fun wsTwoCardApproval(
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
