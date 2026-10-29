package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.KnowledgeRepository
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 领域知识库工具域：`knowledge.*` 三件——agent 根因研判的检索面。
 *
 * ## 定位
 * - 真源 = `core/data/db/Knowledge.sq` 四表（TAG 反查/决策树/黄金样本/知识文档）；
 * - 检索是**精确路由**（E4/G2 裁定：结构化表 + 定向查询，向量不做），语义研判归 agent；
 * - 与 feature-larkbridge 的 `search_knowledge`（手册 RAG）是两个域，命名刻意区分。
 *
 * ## 四件工具
 * - [SearchGoldenSamplesTool]（`knowledge.search_cases`）：历史案例检索（黄金样本）——
 *   自含终态四要素（症状/根因/修法/证据日志）一次给全，无第二级 read；研判链消费流程
 *   的**首步**（先案例后文档）；
 * - [SearchKnowledgeDocsTool]（`knowledge.search`）：文档索引检索（title/summary）——
 *   渐进披露第一级，命中后拿 name 经 [ReadKnowledgeDocTool] 读正文；
 * - [ReadKnowledgeDocTool]（`knowledge.read`）：文档正文读取——渐进披露第二级；
 * - [LookupTagTool]（`knowledge.lookup_tag`）：TAG 反查（TAG_REGISTRY 核心场景——
 *   日志里发现陌生 TAG → 定位所属文档）。
 *
 * ## 装配门控
 * 知识库表空（[KnowledgeRepository.stats] 四计数全零）时 [hasContent] 为 false——
 * 消费方（P4 日志工作台/桥接链）据此不注册，LLM 不会看到调用必失败的工具。
 * [SearchGoldenSamplesTool] 单独收窄门控 [hasCases]（stats.samples > 0 单项判）——
 * 空样本库时其余三件照常注册，本件不注册（恒 NOT_FOUND 的工具不暴露给 LLM）。
 */
object KnowledgeTools {

    /** 库是否有内容（装配门控——空库不注册工具面） */
    fun hasContent(): Boolean {
        val stats = KnowledgeRepository(Database.database()).stats()
        return stats.tags + stats.decisions + stats.samples + stats.docs > 0
    }

    /**
     * 案例库是否有黄金样本（search_cases 装配门控——独立于 [hasContent] 的单项判：
     * stats.samples > 0）。空样本库时其余三件可照常注册，本件必须不注册——
     * 恒 NOT_FOUND 的工具暴露给 LLM 只浪费迭代。
     */
    fun hasCases(repository: KnowledgeRepository = KnowledgeRepository(Database.database())): Boolean =
        repository.stats().samples > 0

    // ── 工具 1：历史案例检索（黄金样本——研判链记忆面，消费流程首步） ──

    class SearchGoldenSamplesTool(
        private val repository: KnowledgeRepository,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<SearchGoldenSamplesTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = NAME,
        description = "Search historical golden case samples (symptom -> root cause -> fix) by keyword. " +
            "Each hit returns the full self-contained case (symptom/rootCause/fixSummary/evidenceLog). " +
            "Use FIRST for a similar symptom before doc search — a fixed case beats re-analyzing.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Symptom keyword to search historical cases (e.g. 页面不显示). " +
                "Space-separated words are ANDed: all must match; a word equal to a stored tag also matches")
            val keyword: String,
            @property:LLMDescription("Repository name to narrow the search (e.g. repo-alpha); empty = all repos")
            val repo: String = "",
            @property:LLMDescription("Max cases to return (default 5, hard cap 20)")
            val limit: Int = 5,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            if (args.keyword.isBlank()) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "keyword 必填")
            }
            val limit = args.limit.coerceIn(1, MAX_CASES_LIMIT)
            // repo 空 = 跨仓全域（含专用案例仓——它经 repos() 四表 UNION 自动进迭代域）
            val repos = if (args.repo.isBlank()) repository.repos() else listOf(args.repo)
            val matched = repos.flatMap { r -> repository.searchGoldenSamples(r, args.keyword, limit) }
                .take(limit)
            if (matched.isEmpty()) {
                ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "案例库无「${args.keyword}」命中（repo=${args.repo.ifBlank { "全库" }}）",
                    hint = "历史案例零命中时改用 knowledge.search 按症状词检索领域文档，" +
                        "或 knowledge.lookup_tag 反查 TAG",
                )
            } else {
                ToolResult.Ok(render(matched, limit, repos))
            }
        }

        companion object {
            const val NAME = "knowledge.search_cases"

            /** limit 硬上限（防全表倾倒；命中面按 created_at 倒序即最新案例优先） */
            const val MAX_CASES_LIMIT = 20

            /** 单条字段截断档（症状/根因/修法/证据——四要素保真优先，超长单字段截断） */
            const val FIELD_MAX_CHARS = 600

            /**
             * 检索输出硬上界（与 log.stats/log.diff/log.view/log.cluster 同档）：
             * 多案例 × 多字段的渲染面无结构性长度推导，恒经 capAt 收口——
             * 含截断标记在内总长恒 ≤ 本值，不依赖渲染推导成立。
             */
            const val MAX_OUTPUT_CHARS = 16384

            /** 命中渲染：每案例四要素逐行给全 + limit 截断明示 + 整体 capAt 硬上界 */
            internal fun render(samples: List<KnowledgeRepository.GoldenSample>, limit: Int, repos: List<String>): String {
                val body = buildString {
                    appendLine("命中 ${samples.size} 条历史案例（repo 域=${repos.joinToString("、").ifBlank { "全库" }}）：")
                    samples.forEachIndexed { index, s ->
                        appendLine("${index + 1}. [${s.repo}] ${s.title}")
                        appendLine("   症状: ${truncate(s.symptom)}")
                        appendLine("   根因: ${truncate(s.rootCause)}")
                        appendLine("   修法: ${truncate(s.fixSummary)}")
                        s.evidenceLog?.let { appendLine("   证据: ${truncate(it)}") }
                        if (s.tags.isNotEmpty()) appendLine("   tags: ${s.tags.joinToString("、")}")
                    }
                    if (samples.size == limit) {
                        appendLine("（结果已达 limit=$limit 上界——可能还有未列出案例，可缩小关键词或指定 repo 重试）")
                    }
                }.trim()
                // capAt 单源复用（LogStatsDiffTools 侦察四件同款截断口径——同模块 internal 缝）
                return LogStatsDiffTools.capAt(body, MAX_OUTPUT_CHARS)
            }

            private fun truncate(text: String): String =
                if (text.length <= FIELD_MAX_CHARS) text else text.take(FIELD_MAX_CHARS) + "…（已截断）"
        }
    }

    // ── 工具 1：文档索引检索（渐进披露第一级） ──

    class SearchKnowledgeDocsTool(
        private val repository: KnowledgeRepository,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<SearchKnowledgeDocsTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = NAME,
        description = "Search domain knowledge docs (device log analysis knowledge base) by keyword " +
            "against doc titles and summaries. Returns matched docs with name/title/summary — " +
            "read the full body of a doc via knowledge.read. Use for log analysis questions: " +
            "find the relevant knowledge doc first, then read it.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Search keyword (symptom word, module word, e.g. 桌面黑屏)")
            val keyword: String,
            @property:LLMDescription("Repository name to search in (e.g. repo-alpha); empty = all repos")
            val repo: String = "",
            @property:LLMDescription("Max results to return (default 10)")
            val limit: Int = 10,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            if (args.keyword.isBlank()) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "keyword 必填")
            }
            val repos = if (args.repo.isBlank()) repository.repos() else listOf(args.repo)
            val matched = repos.flatMap { r ->
                repository.searchDocs(r, args.keyword, args.limit).map { d ->
                    // repo 压缩进结果行（name 含 repo 前缀，与 TAG_REGISTRY 第一列对齐）
                    "[${d.repo}] ${d.name} — ${d.title}\n  ${d.summary}"
                }
            }
            if (matched.isEmpty()) {
                ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "知识库无文档命中「${args.keyword}」（repo=${args.repo.ifBlank { "全库" }}）",
                    hint = "可用知识库仓库：${repository.repos().joinToString("、").ifBlank { "（空）" }}",
                )
            } else {
                ToolResult.Ok(
                    "命中 ${matched.size} 篇知识文档：\n" + matched.joinToString("\n"),
                )
            }
        }

        companion object {
            const val NAME = "knowledge.search"
        }
    }

    // ── 工具 2：文档正文读取（渐进披露第二级） ──

    class ReadKnowledgeDocTool(
        private val repository: KnowledgeRepository,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<ReadKnowledgeDocTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = NAME,
        description = "Read the full Markdown body of one knowledge doc. Get doc names from " +
            "knowledge.search or knowledge.lookup_tag results first.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Repository name of the doc (e.g. repo-alpha)")
            val repo: String,
            @property:LLMDescription("Doc name to read (from knowledge.search/lookup_tag results)")
            val name: String,
            @property:LLMDescription("Max characters to return (default 8000, doc truncated with a marker if longer)")
            val maxChars: Int = 8000,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val body = repository.readDoc(args.repo, args.name)
                ?: return@withContext ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "知识文档不存在：[${args.repo}] ${args.name}",
                    hint = "先用 knowledge.search 按关键词检索，或 knowledge.lookup_tag 反查 TAG",
                )
            if (body.length <= args.maxChars) {
                ToolResult.Ok(body)
            } else {
                ToolResult.Ok(body.take(args.maxChars) + "\n…（已截断，原文 ${body.length} 字）")
            }
        }

        companion object {
            const val NAME = "knowledge.read"
        }
    }

    // ── 工具 3：TAG 反查 ──

    class LookupTagTool(
        private val repository: KnowledgeRepository,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<LookupTagTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = NAME,
        description = "Look up a log TAG (class/tag name found in device logs) in the knowledge base " +
            "to find which knowledge docs cover it. Handles prefix variants (NAV_/N_/360_/xSubr_) " +
            "automatically. Use when you meet an unfamiliar TAG while analyzing logs.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("The log TAG or class name to look up (bare class name works)")
            val tag: String,
            @property:LLMDescription("Repository name to narrow the search (e.g. repo-alpha); empty = all repos")
            val repo: String = "",
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            if (args.tag.isBlank()) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "tag 必填")
            }
            val hits = repository.lookupTag(args.tag, args.repo.ifBlank { null })
            if (hits.isEmpty()) {
                ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "TAG「${args.tag}」未命中知识库（含前缀变体展开）",
                    hint = "可先 knowledge.search 按症状关键词检索文档",
                )
            } else {
                ToolResult.Ok(
                    "TAG「${args.tag}」命中 ${hits.size} 条：\n" + hits.joinToString("\n") { h ->
                        val alias = if (h.aliases.isEmpty()) "" else "｜别名: ${h.aliases.joinToString("、")}"
                        val trunc = h.truncatedTag?.let { "｜截断形式: $it" } ?: ""
                        val note = h.note?.let { "｜注: ${it.take(120)}" } ?: ""
                        "[${h.repo}] ${h.tag} → ${h.docName}$alias$trunc$note"
                    },
                )
            }
        }

        companion object {
            const val NAME = "knowledge.lookup_tag"
        }
    }

    // ── AgentToolCatalog 描述符（P3-D：装配门控 = 知识库有内容） ──

    /** 四件工具名（装配选择集门控——[DecryptTools] 的 MEMORY_NAMES 同款形态；
     *  public：跨模块消费（feature-larkbridge 的 BridgeLogTools）经 catalog 名单装配 */
    val KNOWLEDGE_NAMES = setOf(
        SearchGoldenSamplesTool.NAME,
        SearchKnowledgeDocsTool.NAME,
        ReadKnowledgeDocTool.NAME,
        LookupTagTool.NAME,
    )

    val SEARCH_KNOWLEDGE_DOCS: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = SearchKnowledgeDocsTool.NAME,
        description = "Search domain knowledge docs (device log analysis) by keyword in titles/summaries",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        uiHint = AgentToolCatalog.UiHint(
            title = "检索领域知识库",
            keywords = listOf("知识库", "knowledge", "日志知识"),
            description = "按关键词检索设备日志分析知识文档",
        ),
        params = listOf(
            ParamSpec(
                id = "keyword",
                label = "检索关键词",
                type = ParamType.TEXT,
                placeholder = "症状词 / 模块词，如 桌面黑屏",
            ),
            ParamSpec(id = "repo", label = "限定仓库（可选）", type = ParamType.TEXT, required = false, placeholder = "如 repo-alpha；留空检索全库"),
            ParamSpec(id = "limit", label = "结果上限", type = ParamType.NUMBER, default = "10"),
        ),
    ) { _, sink, _ ->
        if (!hasContent()) return@AgentToolDescriptor null
        SearchKnowledgeDocsTool(KnowledgeRepository(Database.database()), sink)
    }

    val READ_KNOWLEDGE_DOC: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = ReadKnowledgeDocTool.NAME,
        description = "Read the full Markdown body of one knowledge doc (get names via knowledge.search)",
        risk = AgentToolCatalog.Risk.READ_ONLY,
    ) { _, sink, _ ->
        if (!hasContent()) return@AgentToolDescriptor null
        ReadKnowledgeDocTool(KnowledgeRepository(Database.database()), sink)
    }

    val LOOKUP_TAG: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = LookupTagTool.NAME,
        description = "Look up a log TAG in the knowledge base to find covering docs (prefix variants handled)",
        risk = AgentToolCatalog.Risk.READ_ONLY,
    ) { _, sink, _ ->
        if (!hasContent()) return@AgentToolDescriptor null
        LookupTagTool(KnowledgeRepository(Database.database()), sink)
    }

    /**
     * `knowledge.search_cases` 目录描述符。uiHint = null：纯 agent 工具（研判链自用），
     * 不进命令面板（log.cluster 同款待遇）。装配门控独立收窄 = [hasCases]
     * （samples > 0 单项判）——空样本库时其余三件照常注册，本件不注册。
     */
    val SEARCH_GOLDEN_SAMPLES: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = SearchGoldenSamplesTool.NAME,
        description = "Search historical golden case samples (symptom -> root cause -> fix, " +
            "self-contained four elements per hit) by keyword — check fixed cases first",
        risk = AgentToolCatalog.Risk.READ_ONLY,
    ) { _, sink, _ ->
        if (!hasCases()) return@AgentToolDescriptor null
        SearchGoldenSamplesTool(KnowledgeRepository(Database.database()), sink)
    }
}
