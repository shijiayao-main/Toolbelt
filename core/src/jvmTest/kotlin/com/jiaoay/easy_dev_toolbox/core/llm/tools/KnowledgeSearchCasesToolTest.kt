package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.KnowledgeRepository
import com.jiaoay.easy_dev_toolbox.core.llm.WorkbenchPrompts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `knowledge.search_cases`（历史案例检索）工具面验收：
 * - fixture 样本按症状词命中，四要素（症状/根因/修法/证据）一次给全；
 * - 空 repo 迭代 [KnowledgeRepository.repos] 全仓域（专用案例仓自动进迭代域）；
 * - 零命中 NOT_FOUND 且 hint 指回 knowledge.search（先案例后文档的动线闭环）；
 * - limit 档钳制 + 截断明示 + 输出硬上界（capAt 同款真门禁——含标记总长恒 ≤ 上界）；
 * - 装配门控 [KnowledgeTools.hasCases] 单项判（samples > 0——空样本库不注册）；
 * - prompt 引导面：ANALYZE_PROMPT 工具清单行 + disciplineSection 消费流程首步（两语境单源）。
 *
 * 临时库直注（Database.forFile）——不触生产库单例；工厂接线（catalog factory 走
 * hasCases() 单例门控）不在本测试面（hasContent 同款边界，判别面在此直测）。
 */
class KnowledgeSearchCasesToolTest {

    private fun tempDbFile(): File =
        File(System.getProperty("java.io.tmpdir"), "toolbelt-cases-${System.nanoTime()}.db")
            .apply { deleteOnExit() }

    /** 种子库：两仓各一条样本 + 一条跨仓同症状样本（repo 迭代域验收用） */
    private fun seededRepo(): KnowledgeRepository {
        val db = Database.forFile(tempDbFile())
        val queries = db.knowledgeQueries
        queries.insertGoldenSample(
            "repo-alpha", "拍照模式进入但影像完全不显示", "拍照模式后相机无影像", "CameraViewActivity 未启动",
            "检查 Activity 声明与 Intent 过滤器", "09-19 10:29:50.000 E/CameraManager: route failed",
            """["相机","拍照模式"]""", 200L,
        )
        queries.insertGoldenSample(
            "repo-beta", "拍照模式影像黑屏", "拍照模式预览影像黑屏", "相机服务未注册", "先查相机 HAL 注册日志",
            null, null, 300L,
        )
        queries.insertGoldenSample(
            "repo-alpha", "黑屏且无响应", "设置页面黑屏无响应", "主线程阻塞在信号等待",
            "核对信号回调线程模型", null, """["ANR"]""", 100L,
        )
        return KnowledgeRepository(db)
    }

    @Test
    fun `症状词命中且四要素一次给全`() = runBlocking {
        val repository = seededRepo()
        val result = KnowledgeTools.SearchGoldenSamplesTool(repository).executeTyped(
            KnowledgeTools.SearchGoldenSamplesTool.Args(keyword = "拍照模式"),
        )
        val ok = result as ToolResult.Ok
        assertTrue("命中" in ok.summary, "命中计数首行")
        assertTrue("[repo-alpha] 拍照模式进入但影像完全不显示" in ok.summary, "仓前缀 + 案例标题")
        assertTrue("症状: 拍照模式后相机无影像" in ok.summary, "症状要素给全")
        assertTrue("根因: CameraViewActivity 未启动" in ok.summary, "根因要素给全")
        assertTrue("修法: 检查 Activity 声明与 Intent 过滤器" in ok.summary, "修法要素给全")
        assertTrue("证据: 09-19 10:29:50.000 E/CameraManager: route failed" in ok.summary, "证据日志要素给全")
        assertTrue("tags: 相机、拍照模式" in ok.summary, "tags 列表渲染")
        // 黄金样本是自含终态：命中行不含「第二级 read」指路（与文档检索渐进披露的契约区分）
    }

    @Test
    fun `空 repo 迭代全仓域跨仓命中`() = runBlocking {
        val repository = seededRepo()
        val result = KnowledgeTools.SearchGoldenSamplesTool(repository).executeTyped(
            KnowledgeTools.SearchGoldenSamplesTool.Args(keyword = "影像", repo = ""),
        )
        val ok = result as ToolResult.Ok
        assertTrue("[repo-alpha]" in ok.summary && "[repo-beta]" in ok.summary, "repo 空 = 四表 UNION 全仓域（含案例仓）")
    }

    @Test
    fun `指定 repo 时只在该仓命中`() = runBlocking {
        val repository = seededRepo()
        val result = KnowledgeTools.SearchGoldenSamplesTool(repository).executeTyped(
            KnowledgeTools.SearchGoldenSamplesTool.Args(keyword = "影像", repo = "repo-beta"),
        )
        val ok = result as ToolResult.Ok
        assertTrue("[repo-beta]" in ok.summary, "指定仓命中")
        assertTrue("[repo-alpha]" !in ok.summary, "指定仓外样本不进结果")
    }

    @Test
    fun `零命中 NOT_FOUND 且 hint 指回 knowledge-search`() = runBlocking {
        val repository = seededRepo()
        val result = KnowledgeTools.SearchGoldenSamplesTool(repository).executeTyped(
            KnowledgeTools.SearchGoldenSamplesTool.Args(keyword = "不存在的症状词"),
        )
        val err = result as ToolResult.Err
        assertEquals(ToolResult.ErrCode.NOT_FOUND, err.code)
        assertTrue(err.hint != null && "knowledge.search" in err.hint!!, "hint 必须指回 knowledge.search（先案例后文档动线）")
    }

    @Test
    fun `空 keyword BAD_ARGS`() = runBlocking {
        val repository = seededRepo()
        val result = KnowledgeTools.SearchGoldenSamplesTool(repository).executeTyped(
            KnowledgeTools.SearchGoldenSamplesTool.Args(keyword = "  "),
        )
        assertEquals(ToolResult.ErrCode.BAD_ARGS, (result as ToolResult.Err).code)
    }

    @Test
    fun `limit 档钳制且截断明示`() = runBlocking {
        val repository = seededRepo()
        // limit=0 钳到 1：只出一条且带「已达上界」截断明示
        val clamped = KnowledgeTools.SearchGoldenSamplesTool(repository).executeTyped(
            KnowledgeTools.SearchGoldenSamplesTool.Args(keyword = "拍照模式", limit = 0),
        ) as ToolResult.Ok
        assertTrue("命中 1 条历史案例" in clamped.summary, "limit 钳制到 1（下界档）")
        assertTrue("已达 limit=1 上界" in clamped.summary, "截断必须明示（可能还有未列出案例）")
        // 超上限档：钳到 MAX_CASES_LIMIT（20）——渲染函数单测直证，不建 21 条种子
        val samples = List(25) { i ->
            KnowledgeRepository.GoldenSample(
                "repo-alpha", "case-$i", "s", "r", "f", null, emptyList(), 0L,
            )
        }
        val rendered = KnowledgeTools.SearchGoldenSamplesTool.render(
            samples.take(KnowledgeTools.SearchGoldenSamplesTool.MAX_CASES_LIMIT),
            KnowledgeTools.SearchGoldenSamplesTool.MAX_CASES_LIMIT,
            listOf("repo-alpha"),
        )
        assertTrue("命中 20 条历史案例" in rendered, "limit 硬上限 20（防全表倾倒）")
        assertTrue("case-0" in rendered && "case-24" !in rendered, "超上限样本不进输出")
    }

    @Test
    fun `输出硬上界-超长字段截断且总长恒不越界`() {
        // 单字段超 FIELD_MAX_CHARS 截断明示
        val longSample = KnowledgeRepository.GoldenSample(
            "repo-alpha", "超长案例", "症".repeat(5000), "因".repeat(5000),
            "修".repeat(5000), "证".repeat(5000), listOf("t"), 0L,
        )
        val rendered = KnowledgeTools.SearchGoldenSamplesTool.render(listOf(longSample), 5, listOf("repo-alpha"))
        assertTrue(rendered.length <= KnowledgeTools.SearchGoldenSamplesTool.MAX_OUTPUT_CHARS, "输出总长恒 ≤ 硬上界（capAt 收口）")
        // 整体超上界时带截断标记收尾（LogStatsDiffTools.capAt 同款口径）——
        // 20 案例 × 4 字段各 5000 字 ≈ 10 万字，结构性必越 16384 上界
        val many = List(30) { i -> longSample.copy(title = "case-$i") }
        val capped = KnowledgeTools.SearchGoldenSamplesTool.render(many.take(20), 20, listOf("repo-alpha"))
        assertEquals(
            KnowledgeTools.SearchGoldenSamplesTool.MAX_OUTPUT_CHARS,
            capped.length,
            "capAt 硬上界不依赖渲染推导成立（含标记总长恒 = 上界）",
        )
        assertTrue("…[输出超上界已截断]" in capped, "超上界截断必须带明示标记")
    }

    @Test
    fun `门控 hasCases 单项判-samples 大于 0 才注册`() {
        // 空库：samples = 0 ⇒ 不注册（其余计数非零也不放行——空样本库不得注册恒 NOT_FOUND 件；
        // 与 hasContent 四计数 OR 门语义区分：docs 非零时三件照常注册，本件仍不注册）
        val emptyDb = Database.forFile(tempDbFile())
        emptyDb.knowledgeQueries.insertDoc("repo-alpha", "d.md", "文档", "摘要", "正文", 1L)
        val onlyDocs = KnowledgeRepository(emptyDb)
        assertTrue(!KnowledgeTools.hasCases(onlyDocs), "docs 非零但 samples=0 ⇒ search_cases 不注册（单项判）")
        // 种子库：samples > 0 ⇒ 注册
        assertTrue(KnowledgeTools.hasCases(seededRepo()), "samples > 0 ⇒ 可注册")
    }

    @Test
    fun `名集单源-四件工具名与目录注册面一致`() {
        assertEquals(
            setOf("knowledge.search_cases", "knowledge.search", "knowledge.read", "knowledge.lookup_tag"),
            KnowledgeTools.KNOWLEDGE_NAMES,
            "KNOWLEDGE_NAMES = 案例检索 + 知识库三件（跨模块装配选择集的稳定契约）",
        )
        val registered = AgentToolCatalog.all.map { it.name }.toSet()
        assertTrue(
            KnowledgeTools.KNOWLEDGE_NAMES.all { it in registered },
            "「列了没注册」零容忍——KNOWLEDGE_NAMES 必须全部在 AgentToolCatalog 可解析面",
        )
        val descriptor = AgentToolCatalog.all.first { it.name == "knowledge.search_cases" }
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, descriptor.risk, "只读检索件 risk = READ_ONLY")
        assertEquals(null, descriptor.uiHint, "纯 agent 工具不进命令面板（log.cluster 同款待遇）")
    }

    @Test
    fun `prompt 引导面含案例检索条款（两语境单源）`() {
        assertTrue(
            "knowledge.search_cases" in WorkbenchPrompts.ANALYZE_PROMPT,
            "ANALYZE_PROMPT 工具清单必须引导 knowledge.search_cases（名集与 prompt 引导面同批）",
        )
        assertTrue(
            "先用症状词 knowledge.search_cases" in WorkbenchPrompts.disciplineSection(),
            "disciplineSection 消费流程首步 = 先案例后文档（独立研判语境）",
        )
        assertTrue(
            "先用症状词 knowledge.search_cases" in WorkbenchPrompts.disciplineSection(heading = "###", subject = "本流程"),
            "Quest 协作语境共用同一单源首步（heading/subject 之外零漂移）",
        )
    }
}
