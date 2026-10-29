package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * 蒸馏工具域（`distill.*` 两件）。
 *
 * ## 设计（agent-run-ux 调研：Claude Code TaskUpdate 模式 + Devin knowledge 模式）
 * - [ReportStepTool]：结构化进度上报——agent 每完成一个文件/阶段调用一次；进度数据经
 *   [ToolResultSink] 旁路直达运行面板（**不占 LLM 上下文**），render 文案喂 LLM 作确认
 *   回执。面板据此渲染「正在读取 <文件> / 已提取 N 条 TAG 候选」（过程可感知的承重件）。
 * - [ProposeKnowledgeTool]：候选知识卡上报——agent 产出 TAG/约束/决策树节点/黄金样本
 *   候选，**只暂存不落库**（会话态列表，用户审阅卡的唯一数据源）。「不静默入库」铁律：
 *   幻觉 TAG 在暂存层可拦截——审阅面经 [DistillProposal] 的 sourceRefs
 *   citation 核对源码位置，用户逐条 接受/编辑/驳回 才写库。
 *
 * ## 注册面（反向门控）
 * 两件经 [AgentToolCatalog] 注册（描述符**不入 base names**——蒸馏链选择集显式点名，
 * 桥接/桌面旧链恒不注册）；会话装配时暂存集注入工具构造（会话级隔离——多次蒸馏
 * 的候选不互串）。
 */
object DistillTools {

    /** 蒸馏域两件工具名（装配选择集点名用——不入任何 base names：桥接/桌面旧链恒不装） */
    val DISTILL_NAMES = setOf(ReportStepTool.NAME, ProposeKnowledgeTool.NAME)

    /** 进度上报快照（面板渲染数据源——sink 旁路产物） */
    data class DistillProgress(
        val subject: String,       // 进度主体（正在读的文件路径/阶段名）
        val activeForm: String,    // 进行时短语（「正在读取 xxx.kt」）
        val stage: String,         // 阶段标记（explore/extract/distill/verify——探索期即计划卡数据源）
        val progressHint: String,  // 累计量提示（「已提取 12 条 TAG 候选」）
    )

    /** 候选知识卡（审阅卡数据源——会话态暂存，未入库态） */
    data class DistillProposal(
        val kind: Kind,
        val title: String,
        val body: String,
        val sourceRefs: List<String>, // file:line citation（审阅面核对源码位置——幻觉拦截面）
        val seq: Int,                 // 上报序（审阅卡排序锚）
    ) {
        enum class Kind { TAG, CONSTRAINT, DECISION_NODE, GOLDEN_SAMPLE }
    }

    /** 会话级暂存集（DistillSession/VM 持有；工具构造注入——多次蒸馏不互串） */
    class ProposalBuffer {
        internal val proposals = mutableListOf<DistillProposal>()

        val size: Int get() = proposals.size

        /** 快照（审阅面拉取——返回副本，外部不可变集） */
        fun snapshot(): List<DistillProposal> = proposals.toList()

        /** 清空（新蒸馏任务开始） */
        fun reset() = proposals.clear()
    }

    /**
     * 进度上报工具：agent 结构化上报当前步骤。
     * - sink 旁路 [DistillProgress]（面板数据）——LLM 只见确认文案（token 面友好）；
     * - 参数全必填（agent 不猜默认——stage 用固定枚举串：explore/extract/distill/verify）。
     */
    class ReportStepTool(
        private val sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<ReportStepTool.Args>(
        sink = sink,
        argsType = typeToken<ReportStepTool.Args>(),
        name = NAME,
        description = "Report distillation progress: call after completing each file read or " +
            "each extraction milestone, so the user can see what you are doing in real time.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("What this step is about (e.g. the file path being read)")
            val subject: String,
            @property:LLMDescription("Present-continuous phrase shown to the user, e.g. 正在读取 XxxService.kt")
            val activeForm: String,
            @property:LLMDescription("Stage marker: one of explore / extract / distill / verify")
            val stage: String,
            @property:LLMDescription("Cumulative progress hint, e.g. 已提取 12 条 TAG 候选")
            val progressHint: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            sink.onResult(
                name,
                ToolResult.Ok(
                    summary = "[distill-progress] ${args.activeForm}（${args.progressHint}）",
                    data = ToolPayload.DistillProgressPayload(
                        subject = args.subject,
                        activeForm = args.activeForm,
                        stage = args.stage,
                        progressHint = args.progressHint,
                    ),
                ),
            )
            return ToolResult.Ok("进度已上报：${args.activeForm}（${args.progressHint}）")
        }

        companion object { const val NAME = "distill.report_step" }
    }

    /**
     * 候选知识上报工具：agent 产出候选卡**暂存不落库**。
     * - kind 四值：TAG（日志 TAG 登记）/ CONSTRAINT（行为约束/门控）/ DECISION_NODE
     *   （排查决策树节点）/ GOLDEN_SAMPLE（黄金样本：症状-根因-修复）；
     * - sourceRefs 必填非空（citation 是审阅面幻觉拦截的承重面）；
     * - render 文案带序号（LLM 后续上报知道累计量）。
     */
    class ProposeKnowledgeTool(
        private val buffer: ProposalBuffer,
        private val sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<ProposeKnowledgeTool.Args>(
        sink = sink,
        argsType = typeToken<ProposeKnowledgeTool.Args>(),
        name = NAME,
        description = "Submit a distilled knowledge candidate for user review. Proposals are " +
            "held in a session buffer and NEVER written to the knowledge base directly — " +
            "the user accepts/edits/rejects each one. Always include sourceRefs (file:line).",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Candidate kind: TAG / CONSTRAINT / DECISION_NODE / GOLDEN_SAMPLE")
            val kind: String,
            @property:LLMDescription("Short title (log TAG name, constraint summary, decision question, or sample symptom)")
            val title: String,
            @property:LLMDescription("Full content: log patterns for TAG, rule text for CONSTRAINT, condition+action for DECISION_NODE, symptom/rootCause/fix for GOLDEN_SAMPLE")
            val body: String,
            @property:LLMDescription("Source citations, one per line, format: path/to/File.kt:123 — required for user verification")
            val sourceRefs: List<String>,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val kind = runCatching { DistillProposal.Kind.valueOf(args.kind.trim().uppercase()) }
                .getOrElse {
                    return ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "kind 必须是 TAG / CONSTRAINT / DECISION_NODE / GOLDEN_SAMPLE 之一（收到：${args.kind}）",
                    )
                }
            if (args.title.isBlank()) return ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "title 不能为空")
            if (args.sourceRefs.isEmpty()) {
                return ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "sourceRefs 不能为空——候选卡必须携带源码位置（file:line），用户要凭它核对来源",
                )
            }
            val proposal = DistillProposal(
                kind = kind,
                title = args.title.trim(),
                body = args.body.trim(),
                sourceRefs = args.sourceRefs,
                seq = buffer.size + 1,
            )
            synchronized(buffer.proposals) { buffer.proposals.add(proposal) }
            sink.onResult(
                name,
                ToolResult.Ok(
                    summary = "[distill-proposal #${proposal.seq}] ${proposal.kind}: ${proposal.title}",
                    data = ToolPayload.DistillProposalPayload(seq = proposal.seq, kind = kind.name, title = proposal.title),
                ),
            )
            return ToolResult.Ok(
                "候选已暂存（#${proposal.seq}，共 ${buffer.size} 条待审阅——不会自动入库，等待用户逐条审阅）",
            )
        }

        companion object { const val NAME = "distill.propose_knowledge" }
    }
}
