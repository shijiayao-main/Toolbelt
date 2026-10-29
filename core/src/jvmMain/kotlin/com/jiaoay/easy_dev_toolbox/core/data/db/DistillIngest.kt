package com.jiaoay.easy_dev_toolbox.core.data.db

import com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 蒸馏候选入库链（P4-B 任务 4.5）：distill.propose_knowledge 候选经审阅**接受**后，
 * 按 kind 直调四表 upsert（UNIQUE 冲突覆写——幂等；写入面与 KnowledgePack.importPack 同缝）。
 *
 * ## kind → 表映射（tasks 4.5）
 * - TAG → tag_registry（tag=title, note=body + sourceRefs 溯源行）；
 * - CONSTRAINT → decision_nodes **附注**（method=约束标题、condition=约束正文——决策树表的
 *   复用形态：约束不是树节点，但检索面能按 method 命中它）；
 * - DECISION_NODE → decision_nodes（method=title、action=body、condition=sourceRefs 溯源）；
 * - GOLDEN_SAMPLE → golden_samples（title/symptom=rootCause=fix 按正则拆 body 三段，拆不动
 *   整段落 symptom）。
 *
 * **不做幻觉拦截**（承重面在 propose_knowledge 的 sourceRefs 必填 + 审阅卡人审）：
 *    本链只做 kind 分派与写入。sourceRefs 定位不到的候选由用户**驳回**（不入库——
 *    库内无脏数据）；写入侧不校验 file:line 真实性（判定权在人）。
 *
 * 🔴 repo 缺失（探索产物 unknown）时写入会混入未知仓库节——**显式拒绝**（返回 written=false，
 *    审阅面提示重探；不静默写脏数据）。doc_name 无真源文档锚点，统一落虚拟文档名
 *    「<title> 知识点」——知识包导入的文档行才是文档真源，蒸馏候选先入表可检索。
 */
object DistillIngest {

    /** 入库结果（审阅面提示） */
    data class IngestResult(
        val kind: DistillTools.DistillProposal.Kind,
        val written: Boolean,
        val detail: String,
    )

    /**
     * 候选写入四表（kind 分派；事务单条——每候选独立事务，单条失败不拖垮整批）。
     *
     * @param db 目标库（测试注入临时库；生产 Database.database()）
     * @param proposal 审阅接受的候选（kind/title/body/sourceRefs 已验非空——工具层契约）
     * @param repo 仓库标识（计划卡顶层 repo——探索产物；unknown/blank 拒绝写入）
     */
    suspend fun ingest(
        db: ToolbeltDb,
        proposal: DistillTools.DistillProposal,
        repo: String,
    ): IngestResult = withContext(Dispatchers.IO) {
        if (repo.isBlank() || repo == "unknown") {
            return@withContext IngestResult(
                kind = proposal.kind,
                written = false,
                detail = "仓库标识缺失（探索产物 repo=unknown）——请重新探索或手工补充仓库标识",
            )
        }
        val queries = db.knowledgeQueries
        try {
            db.transactionWithResult {
                when (proposal.kind) {
                    DistillTools.DistillProposal.Kind.TAG -> queries.insertTag(
                        tag = proposal.title,
                        repo = repo,
                        doc_name = docNameOf(proposal),
                        module = null,
                        aliases = null,
                        truncated_tag = null,
                        note = "${proposal.body}\n[source] ${proposal.sourceRefs.joinToString("、")}",
                    )

                    DistillTools.DistillProposal.Kind.CONSTRAINT -> queries.insertDecisionNode(
                        repo = repo,
                        method = proposal.title,
                        node_key = "constraint-${proposal.seq}",
                        parent_key = null,
                        condition = proposal.body,
                        action = "（约束附注——非决策树节点）",
                        doc_name = docNameOf(proposal),
                    )

                    DistillTools.DistillProposal.Kind.DECISION_NODE -> queries.insertDecisionNode(
                        repo = repo,
                        method = proposal.title,
                        node_key = "node-${proposal.seq}",
                        parent_key = null,
                        condition = sourceTextOf(proposal),
                        action = proposal.body,
                        doc_name = docNameOf(proposal),
                    )

                    DistillTools.DistillProposal.Kind.GOLDEN_SAMPLE -> {
                        val (symptom, rootCause, fix) = splitGoldenBody(proposal.body)
                        queries.insertGoldenSample(
                            repo = repo,
                            title = proposal.title,
                            symptom = symptom,
                            root_cause = rootCause,
                            fix_summary = fix,
                            evidence_log = proposal.sourceRefs.joinToString("\n"),
                            tags = null,
                            created_at = System.currentTimeMillis(),
                        )
                    }
                }
            }
            IngestResult(kind = proposal.kind, written = true, detail = "已入库（${proposal.kind}）")
        } catch (e: Exception) {
            IngestResult(
                kind = proposal.kind,
                written = false,
                detail = "写入失败：${e.message?.take(200) ?: e::class.simpleName}",
            )
        }
    }

    /** 虚拟文档名（蒸馏候选无真源文档锚点——统一落「<title> 知识点」） */
    private fun docNameOf(proposal: DistillTools.DistillProposal): String = "${proposal.title} 知识点"

    /** sourceRefs 溯源文本（决策节点 condition 列） */
    private fun sourceTextOf(proposal: DistillTools.DistillProposal): String =
        "[source] ${proposal.sourceRefs.joinToString("、")}"

    /**
     * 黄金样本 body 三段拆分（prompt 约定 symptom/root_cause/fix 三段——拆不动整段落 symptom）。
     * 约定形态（DISTILL_PROMPT）：`symptom：…\nroot_cause：…\nfix：…`
     */
    internal fun splitGoldenBody(body: String): Triple<String, String, String> {
        val symptom = Regex("symptom[:：]\\s*([\\s\\S]*?)(?=root_cause[:：]|$)").find(body)?.groupValues?.get(1)?.trim()
        val rootCause = Regex("root_cause[:：]\\s*([\\s\\S]*?)(?=fix[:：]|$)").find(body)?.groupValues?.get(1)?.trim()
        val fix = Regex("fix[:：]\\s*([\\s\\S]*)$").find(body)?.groupValues?.get(1)?.trim()
        return if (symptom != null && rootCause != null && fix != null) {
            Triple(symptom, rootCause, fix)
        } else {
            Triple(body, "", "")
        }
    }
}
