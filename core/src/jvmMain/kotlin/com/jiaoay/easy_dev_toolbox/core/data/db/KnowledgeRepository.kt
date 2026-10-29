package com.jiaoay.easy_dev_toolbox.core.data.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 知识库引擎的定向查询面。
 *
 * ## 定位（E4/G2 调研裁定）
 * 「结构化表 + 定向查询为主，向量检索不做」——检索只做**精确路由**（TAG 精确匹配 /
 * 关键词 LIKE 包含），语义模糊研判归 agent（根因研判节点，P4）。与
 * feature-larkbridge 的 [KnowledgeBase]（手册 RAG）并存：本域是**领域日志知识**
 * （设备 TAG/决策树/黄金样本），彼域是产品手册——工具命名区分 `knowledge.*` vs
 * `search_knowledge`（手册）。
 *
 * ## 查询面
 * - [lookupTag]：TAG 反查（TAG_REGISTRY 核心场景——日志里发现陌生 TAG → 定位文档）。
 *   多前缀体系处理：真源知识库的 TAG 有 NAV_/NAV_NAV_/N_/N_NAV_/360_/xSubr_ 等前缀
 *   形态，agent 传入的往往是**剥离前缀后的类名**——repository 层生成派生变体
 *   （裸名 + 各前缀形态）逐路精确匹配，而不是 LIKE '%name%' 全表扫；
 * - [searchGoldenSamples]：症状关键词定向检索（黄金样本 = 历史复发案例）；
 * - [searchDocs]：文档索引检索（title/summary LIKE）+ [readDoc]（正文渐进披露）；
 * - [decisionTree]：决策树节点读取（agent 根因研判按 condition 逐节点走树）。
 *
 * ## 线程模型
 * SQLDelight 生成查询非挂起；本面是纯查询/导入导出 API（suspend 只出现在 IO 密集的
 * 导入导出包裹层——见 [KnowledgePack]）。工具层（KnowledgeTools）用 withContext(IO)
 * 包裹调用。
 */
class KnowledgeRepository(private val db: ToolbeltDb) {

    companion object {
        /**
         * 专用案例仓名（工作台「存案」写路径的固定 repo）。
         *
         * 契约：
         * - 工作台 run 无 repo 概念，而 golden_samples 以 UNIQUE(repo, title) 复合键落盘——
         *   repo 必须非空稳定（[DistillIngest] 对 repo 缺失显式拒绝写入的同款不变量），
         *   固定仓名是「一键存案」产品目标的唯一满足解（排除每次选仓交互）；
         * - 本名是**虚拟仓**，刻意避开真实产品仓名（repo-alpha/repo-beta 等）——
         *   案例的产品归属上下文由 tags 承载（检索路由与蒸馏管理面不得按本仓名做产品过滤）；
         * - KbManage 管理面按 repo 分节呈现——本仓与其他仓库同待遇可浏览/删除，零新管理面。
         */
        const val CASE_REPO = "casebook"

        /** 真源库实际落盘前缀（TAG_REGISTRY「别名与变体」节实证形态） */
        private val PREFIXES = listOf("NAV_", "NAV_NAV_", "N_", "N_NAV_", "360_", "xSubr_")

        /** 关键词多词拆分分隔面（空白拆分；旧单词输入 = 单元素自然兼容） */
        private val KEYWORD_SEPARATOR = Regex("\\s+")

        /**
         * 检索字段评分权重（相关度 = 确定性加权命中分）：症状 > tags > 根因 > 修法。
         * 症状最重 = 检索意图按症状进入；tags 次之 = 结构化路由词；根因/修法递减 =
         * 命中在正文深处时相关度让位给命中在症状面的案例。
         */
        internal const val WEIGHT_SYMPTOM = 8
        internal const val WEIGHT_TAGS = 4
        internal const val WEIGHT_ROOT_CAUSE = 2
        internal const val WEIGHT_FIX_SUMMARY = 1

        /**
         * 单词命中行的取回上界（相关度排序与 limit 收口之前的单词面取数封顶）。
         * 案例库是人工单条存案的小库，本上界只防病态库全表倾倒，正常规模恒触不到；
         * 取回面恒以首词预筛（见 [searchGoldenSamples]），最终结果数由调用方 limit 收口。
         */
        internal const val TERM_FETCH_LIMIT = 500
    }

    // ── 领域模型（与 .sq 行模型解耦——检索/导入导出 API 的稳定契约面） ──

    /** TAG 反查命中行（docName 指向 [KnowledgeDoc.name]） */
    data class TagEntry(
        val tag: String,
        val repo: String,
        val docName: String,
        val module: String?,
        val aliases: List<String>,
        val truncatedTag: String?,
        val note: String?,
    )

    /** 决策树节点（树结构经 parentKey 还原；根节点 parentKey = null） */
    data class DecisionNode(
        val repo: String,
        val method: String,
        val nodeKey: String,
        val parentKey: String?,
        val condition: String,
        val action: String,
    )

    /** 黄金样本（历史复发案例：症状 → 根因 → 修复要点 + 证据日志） */
    data class GoldenSample(
        val repo: String,
        val title: String,
        val symptom: String,
        val rootCause: String,
        val fixSummary: String,
        val evidenceLog: String?,
        val tags: List<String>,
        val createdAt: Long,
    )

    /** 知识文档（name 含 repo 前缀——与 TAG_REGISTRY 主映射第一列对齐） */
    data class KnowledgeDoc(
        val repo: String,
        val name: String,
        val title: String,
        val summary: String,
        val body: String,
        val updatedAt: Long,
    )

    // 库统计直接用生成的 KnowledgeStats（同包顶层类——tags/decisions/samples/docs 全 Long）

    /** 仓库 TAG 表全量（管理面浏览——tagsByRepo SQL 已被 KnowledgePack 导出消费，此处对 UI 面暴露） */
    fun tagsOf(repo: String): List<TagEntry> =
        db.knowledgeQueries.tagsByRepo(repo).executeAsList().map { it.toTagEntry() }

    // ── TAG 反查 ──

    /**
     * TAG 反查（TAG_REGISTRY 核心场景）。多前缀体系变体展开：
     * 真源库的 TAG 带 NAV_/NAV_NAV_/N_/N_NAV_/360_/xSubr_ 等前缀，agent 传裸类名时
     * 剥离/补全前缀生成派生变体，逐路精确匹配（按 id 去重合并）。
     *
     * @param repo 仓库过滤（null/blank = 跨仓全域反查——TAG_REGISTRY「优先取与当前分析
     *        仓库一致的行」的语义由调用方/agent 拿到结果后处理，本面不过滤）
     */
    fun lookupTag(tag: String, repo: String? = null, limit: Int = 10): List<TagEntry> {
        if (tag.isBlank()) return emptyList()
        val variants = tagVariants(tag)
        val seen = LinkedHashMap<Long, TagEntry>()
        val safeLimit = limit.toLong().coerceAtLeast(1)
        for (variant in variants) {
            val rows = if (repo.isNullOrBlank()) {
                db.knowledgeQueries.lookupTagAll(variant, "%${escapeLike(variant)}%", safeLimit).executeAsList()
            } else {
                db.knowledgeQueries.lookupTag(repo, variant, "%${escapeLike(variant)}%", safeLimit).executeAsList()
            }
            rows.forEach { row -> seen.putIfAbsent(row.id, row.toTagEntry()) }
        }
        return seen.values.toList()
    }

    /** TAG 派生变体（前缀剥离/补全——真源库多前缀体系的检索面） */
    internal fun tagVariants(tag: String): List<String> {
        val bare = tag.trim()
        val variants = LinkedHashSet<String>()
        variants.add(bare)
        // 真源库实际落盘前缀（TAG_REGISTRY「别名与变体」节的形态）
        PREFIXES.forEach { prefix ->
            variants.add(prefix + bare)
            if (bare.startsWith(prefix)) variants.add(bare.removePrefix(prefix))
        }
        return variants.filter { it.isNotBlank() }
    }

    // ── 决策树 ──

    /** 决策树读取（agent 根因研判按 condition 逐节点走树） */
    fun decisionTree(repo: String, method: String): List<DecisionNode> =
        db.knowledgeQueries.decisionTree(repo, method).executeAsList().map { it.toDecisionNode() }

    /** 决策树方法枚举（工作台树选择面） */
    fun methodsOf(repo: String): List<String> =
        db.knowledgeQueries.distinctMethods(repo).executeAsList()

    // ── 黄金样本 ──

    /**
     * 症状关键词检索（LIKE 包含 + tags 整串命中；多词 AND；相关度排序）。
     *
     * 语义（确定性精确路由，无模糊匹配——语义研判归 agent）：
     * - keyword 按空白拆分多词，**全部词都命中**（symptom/root_cause/fix_summary 子串
     *   或 tags 元素整串）的样本才返回；旧单词输入 = 单元素自然兼容；
     * - tags 命中按 JSON 定界符口径（词 = 完整 tag 元素，「黑屏」不误中「不黑屏」），
     *   pattern 经 [encodeList] 单源构造（[tagsTermPattern]）；
     * - 排序 = [relevanceScore] 相关度降序，同分按 created_at 降序（稳定排序防抖动）；
     * - limit 只收口最终结果数。单词面取回以**首词** SQL 预筛（created_at 倒序封顶
     *   [TERM_FETCH_LIMIT]），其余词在预筛集上 AND 过滤——上界契约见该常量 KDoc。
     *
     * @param limit 最终结果数上界（负值按 0 处理）
     */
    fun searchGoldenSamples(repo: String, keyword: String, limit: Int = 10): List<GoldenSample> {
        val terms = keyword.trim().split(KEYWORD_SEPARATOR).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val first = terms.first()
        val candidates = db.knowledgeQueries.searchGoldenSamples(
            repo = repo,
            pattern = "%${escapeLike(first)}%",
            tagPattern = tagsTermPattern(first),
            limit = TERM_FETCH_LIMIT.toLong(),
        ).executeAsList().map { it.toGoldenSample() }
        return candidates
            .filter { sample -> terms.drop(1).all { term -> sampleMatchesTerm(sample, term) } }
            .sortedWith(
                compareByDescending<GoldenSample> { relevanceScore(it, terms) }
                    .thenByDescending { it.createdAt },
            )
            .take(limit.coerceAtLeast(0))
    }

    /**
     * 单词命中判定（与 searchGoldenSamples SQL WHERE 同口径）：三文本字段子串包含 +
     * tags 元素整串相等（大小写不敏感对齐 SQLite LIKE 的 ASCII 行为）。
     */
    private fun sampleMatchesTerm(sample: GoldenSample, term: String): Boolean {
        val t = term.lowercase()
        return sample.symptom.lowercase().contains(t) ||
            sample.rootCause.lowercase().contains(t) ||
            sample.fixSummary.lowercase().contains(t) ||
            sample.tags.any { it.equals(term, ignoreCase = true) }
    }

    /**
     * tags 命中 pattern（JSON 定界符口径，[encodeList] 单源构造）：`%"词"%`——词被
     * JSON 引号包夹 ⇒ 命中 = 词与某 tag 元素整串相等（「黑屏」不误中「不黑屏」）。
     * 词内 %/_ 经 [escapeLike] 字面化；词含引号/反斜杠时经同一 JSON 编码两侧对齐。
     */
    internal fun tagsTermPattern(term: String): String {
        val encoded = encodeList(listOf(term))
            ?: error("tagsTermPattern 只接受非空词（encodeList 空列表落 null）")
        val element = encoded.removePrefix("[").removeSuffix("]")
        return "%${escapeLike(element)}%"
    }

    /**
     * 相关度评分（确定性加权纯函数，无模糊匹配）：每词逐命中字段累加权重
     * （[WEIGHT_SYMPTOM] > [WEIGHT_TAGS] > [WEIGHT_ROOT_CAUSE] > [WEIGHT_FIX_SUMMARY]），
     * 总分即排序键；同分排序由 [searchGoldenSamples] 按 created_at 收口。
     */
    internal fun relevanceScore(sample: GoldenSample, terms: List<String>): Int {
        var score = 0
        for (term in terms) {
            val t = term.lowercase()
            if (sample.symptom.lowercase().contains(t)) score += WEIGHT_SYMPTOM
            if (sample.tags.any { it.equals(term, ignoreCase = true) }) score += WEIGHT_TAGS
            if (sample.rootCause.lowercase().contains(t)) score += WEIGHT_ROOT_CAUSE
            if (sample.fixSummary.lowercase().contains(t)) score += WEIGHT_FIX_SUMMARY
        }
        return score
    }

    /** 仓库全量样本（管理面/导入导出转储面） */
    fun goldenSamplesOf(repo: String): List<GoldenSample> =
        db.knowledgeQueries.goldenSamplesByRepo(repo).executeAsList().map { it.toGoldenSample() }

    /**
     * 单条黄金样本写入（公开写路径——工作台存案；蒸馏采纳/知识包导入经各自入口同缝）。
     *
     * 语义：
     * - upsert 直通 [ToolbeltDb] 的 insertGoldenSample（UNIQUE(repo, title) 冲突覆写——
     *   幂等；覆写不更新 created_at，原行登记时间保留）；
     * - 同名覆盖回执：返回 true = 覆盖了既有 (repo, title) 行（调用方据此提示「已覆盖
     *   同名案例」），false = 新增——存在性经 goldenSampleCountByTitle 精确计数，
     *   不在事务内（并发窗内两次存案同标题属可接受竞态——结果仍收敛为单行）；
     * - tags 编解码恒经 [encodeList]/[decodeList] 单源（JSON 数组列契约）——调用方
     *   传 List<String>，禁止自带第二套编码。
     *
     * @param sample 完整样本行（repo 建议传 [CASE_REPO]——工作台存案固定仓名）
     */
    fun upsertGoldenSample(sample: GoldenSample): Boolean {
        val existed = db.knowledgeQueries
            .goldenSampleCountByTitle(sample.repo, sample.title).executeAsOne() > 0
        db.knowledgeQueries.insertGoldenSample(
            repo = sample.repo,
            title = sample.title,
            symptom = sample.symptom,
            root_cause = sample.rootCause,
            fix_summary = sample.fixSummary,
            evidence_log = sample.evidenceLog,
            tags = encodeList(sample.tags),
            created_at = sample.createdAt,
        )
        return existed
    }

    // ── 知识文档 ──

    /** 文档索引检索（渐进披露第一级：title/summary LIKE 命中） */
    fun searchDocs(repo: String, keyword: String, limit: Int = 10): List<KnowledgeDoc> {
        if (keyword.isBlank()) return emptyList()
        val pattern = "%${escapeLike(keyword.trim())}%"
        return db.knowledgeQueries.searchDocs(repo, pattern, limit.toLong())
            .executeAsList().map { it.toKnowledgeDoc() }
    }

    /** 文档清单（工作台浏览面；repo 全量） */
    fun docsOf(repo: String): List<KnowledgeDoc> =
        db.knowledgeQueries.docsByRepo(repo).executeAsList().map { it.toKnowledgeDoc() }

    /** 正文读取（渐进披露第二级：repo + name 精确定位；miss 返回 null） */
    fun readDoc(repo: String, name: String): String? =
        db.knowledgeQueries.docByName(repo, name).executeAsOneOrNull()?.body

    // ── 管理面 ──

    /** repo 清单（四表 UNION——空库时为空） */
    fun repos(): List<String> = db.knowledgeQueries.allRepos().executeAsList()

    /** 库统计 */
    fun stats(): KnowledgeStats = db.knowledgeQueries.knowledgeStats().executeAsOne()

    /**
     * 按 repo 删除四表（管理面「删除仓库节」——蒸馏候选/知识包导入的整组回退；
     * 与 KnowledgePack.clearRepo 同一查询面，这里暴露为管理动作）。
     */
    fun deleteRepo(repo: String) {
        db.transaction {
            db.knowledgeQueries.deleteTagsByRepo(repo)
            db.knowledgeQueries.deleteDecisionNodesByRepo(repo)
            db.knowledgeQueries.deleteGoldenSamplesByRepo(repo)
            db.knowledgeQueries.deleteDocsByRepo(repo)
        }
    }

    /** 删除单条 TAG 行（管理面逐行删除——(tag, repo, doc_name) 复合主键） */
    fun deleteTag(tag: String, repo: String, docName: String) {
        db.knowledgeQueries.deleteTag(tag, repo, docName)
    }

    /** 删除单条黄金样本（(repo, title) 复合主键） */
    fun deleteGoldenSample(repo: String, title: String) {
        db.knowledgeQueries.deleteGoldenSample(repo, title)
    }

    // ── 行模型映射 ──

    private fun Tag_registry.toTagEntry(): TagEntry = TagEntry(
        tag = tag,
        repo = repo,
        docName = doc_name,
        module = module,
        aliases = decodeList(aliases),
        truncatedTag = truncated_tag,
        note = note,
    )

    private fun Decision_nodes.toDecisionNode(): DecisionNode = DecisionNode(
        repo = repo,
        method = method,
        nodeKey = node_key,
        parentKey = parent_key,
        condition = condition,
        action = action,
    )

    private fun Golden_samples.toGoldenSample(): GoldenSample = GoldenSample(
        repo = repo,
        title = title,
        symptom = symptom,
        rootCause = root_cause,
        fixSummary = fix_summary,
        evidenceLog = evidence_log,
        tags = decodeList(tags),
        createdAt = created_at,
    )

    private fun Knowledge_docs.toKnowledgeDoc(): KnowledgeDoc = KnowledgeDoc(
        repo = repo,
        name = name,
        title = title,
        summary = summary,
        body = body,
        updatedAt = updated_at,
    )

    // ── 序列化辅助（JSON 数组列的编解码；[KnowledgePack] 同款语义） ──

    private val json = Json { ignoreUnknownKeys = true }

    /** JSON 数组列解码（aliases/tags；null/空串 = 空列表；坏 JSON 容错为空） */
    internal fun decodeList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    /** JSON 数组列编码（空列表落 null 省空间） */
    internal fun encodeList(list: List<String>): String? =
        if (list.isEmpty()) null else json.encodeToString(ListSerializer(String.serializer()), list)

    /** LIKE 转义（%/_/\——ESCAPE '\' 契约配套；调用方自行包 % 通配） */
    internal fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
