// 案例检索增强验收：tags 召回（JSON 定界符词边界）/ 多词 AND / 相关度评分排序
//（症状 > tags > 根因 > 修法加权，同分 created_at 倒序）/ 空结果与工具层硬上界不变。
// 临时库直注（Database.forFile）——不触生产库单例。
package com.jiaoay.easy_dev_toolbox.core.data.db

import com.jiaoay.easy_dev_toolbox.core.llm.tools.KnowledgeTools
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowledgeSearchRankingTest {

    private fun tempDbFile(): File =
        File(System.getProperty("java.io.tmpdir"), "toolbelt-kb-rank-${System.nanoTime()}.db")
            .apply { deleteOnExit() }

    private fun sample(
        title: String,
        symptom: String,
        rootCause: String,
        fixSummary: String,
        tags: List<String>,
        createdAt: Long,
    ): KnowledgeRepository.GoldenSample = KnowledgeRepository.GoldenSample(
        repo = "settings", title = title, symptom = symptom, rootCause = rootCause,
        fixSummary = fixSummary, evidenceLog = null, tags = tags, createdAt = createdAt,
    )

    private fun freshRepo(): KnowledgeRepository = KnowledgeRepository(Database.forFile(tempDbFile()))

    /**
     * 五案 fixture（字段命中面互斥设计，评分断言不串味）：
     * - s1 症状面命中「影像」（且仅症状面）；
     * - s2 仅 tags 命中「黑屏」（文本三字段全不含「黑屏」）；
     * - s3 tags 词边界陷阱：tag =「不黑屏」，文本三字段全不含「黑屏」；
     * - s4 仅修法面命中「影像」（createdAt 最新——评序须压过时间序）；
     * - s5 与 s1 同为症状面命中「影像」（同分 created_at 倒序对照组）。
     */
    private fun seededRepo(): KnowledgeRepository {
        val repository = KnowledgeRepository(Database.forFile(tempDbFile()))
        listOf(
            sample("症状命中", "拍照模式后相机无影像", "相机服务未注册", "检查相机服务注册", listOf("相机"), 300L),
            sample("仅标签命中", "启动耗时过长", "缓存冷启动", "预热关键路径", listOf("黑屏"), 200L),
            sample("词边界陷阱", "设置页面出现横纹", "信号时序错位", "核对信号时序", listOf("不黑屏"), 100L),
            sample("修法命中", "开机偶发卡顿", "调度抖动", "影像链路自检脚本", listOf("卡顿"), 500L),
            sample("同分对照", "画面影像闪断", "链路接触", "重插线束", listOf("闪断"), 400L),
        ).forEach { repository.upsertGoldenSample(it) }
        return repository
    }

    // ── tags 召回 ──

    @Test
    fun `仅 tags 命中的样本可召回`() {
        val repository = seededRepo()
        val hits = repository.searchGoldenSamples("settings", "黑屏")
        assertEquals(listOf("仅标签命中"), hits.map { it.title }, "「黑屏」只在 tags 里出现——须召回")
    }

    @Test
    fun `tags 词边界-黑屏不误中不黑屏`() {
        val repository = seededRepo()
        assertTrue(
            "词边界陷阱" !in repository.searchGoldenSamples("settings", "黑屏").map { it.title },
            "tag「不黑屏」不得被词「黑屏」误中（JSON 定界符口径）",
        )
        assertEquals(
            listOf("词边界陷阱"),
            repository.searchGoldenSamples("settings", "不黑屏").map { it.title },
            "整串相等双向成立：词「不黑屏」命中 tag「不黑屏」",
        )
    }

    @Test
    fun `tags 命中 pattern 经 encodeList 单源构造`() {
        // pattern 含 JSON 引号定界：`%"黑屏"%`——引号夹住整词即元素整串相等口径
        assertEquals("%\"黑屏\"%", freshRepo().tagsTermPattern("黑屏"))
    }

    // ── 多词 AND ──

    @Test
    fun `多词 AND-全部词命中才返回`() {
        val repository = seededRepo()
        assertEquals(
            listOf("症状命中"),
            repository.searchGoldenSamples("settings", "相机 影像").map { it.title },
            "两词同现症状面——命中",
        )
        assertTrue(
            repository.searchGoldenSamples("settings", "相机 黑屏").isEmpty(),
            "无样本同时含「相机」与「黑屏」——AND 语义零返回",
        )
        assertEquals(
            listOf("仅标签命中"),
            repository.searchGoldenSamples("settings", "启动 黑屏").map { it.title },
            "多词 AND 可跨字段合成（症状「启动」+ tags「黑屏」）",
        )
        // 多空白拆分与旧单词输入兼容
        assertEquals(
            repository.searchGoldenSamples("settings", "相机").map { it.title },
            repository.searchGoldenSamples("settings", "  相机  ").map { it.title },
            "首尾空白与词间多空白同一拆分结果",
        )
    }

    // ── 评分排序 ──

    @Test
    fun `相关度排序-症状命中压过修法命中-同分按 created_at 倒序`() {
        val repository = seededRepo()
        val hits = repository.searchGoldenSamples("settings", "影像")
        assertEquals(
            listOf("同分对照", "症状命中", "修法命中"),
            hits.map { it.title },
            "症状命中(同分 400/300 按 created_at 倒序) > 修法命中(500 但权重低)——评序压过时间序",
        )
        // 评分是确定性加权：症状 8 > tags 4 > 根因 2 > 修法 1，逐字段累加
        val s1 = KnowledgeRepository.GoldenSample(
            repo = "settings", title = "x", symptom = "影像症状", rootCause = "影像根因",
            fixSummary = "影像修法", evidenceLog = null, tags = listOf("影像"), createdAt = 0L,
        )
        assertEquals(
            KnowledgeRepository.WEIGHT_SYMPTOM + KnowledgeRepository.WEIGHT_TAGS +
                KnowledgeRepository.WEIGHT_ROOT_CAUSE + KnowledgeRepository.WEIGHT_FIX_SUMMARY,
            freshRepo().relevanceScore(s1, listOf("影像")),
            "四字段全命中 = 四权重累加",
        )
    }

    @Test
    fun `limit 收口结果数`() {
        val repository = seededRepo()
        assertEquals(1, repository.searchGoldenSamples("settings", "影像", limit = 1).size, "limit 只收口最终结果数")
        assertTrue(repository.searchGoldenSamples("settings", "不存在词").isEmpty(), "零命中返回空列表")
    }

    // ── 工具层硬上界与 NOT_FOUND 链不变 ──

    @Test
    fun `工具层硬上界与 NOT_FOUND hint 链不变`() {
        assertEquals(20, KnowledgeTools.SearchGoldenSamplesTool.MAX_CASES_LIMIT, "limit 硬上限 20 不变")
        assertEquals(16384, KnowledgeTools.SearchGoldenSamplesTool.MAX_OUTPUT_CHARS, "capAt 输出硬上界不变")
        assertEquals(
            setOf("knowledge.search_cases", "knowledge.search", "knowledge.read", "knowledge.lookup_tag"),
            KnowledgeTools.KNOWLEDGE_NAMES,
            "工具名集零改动（装配门控/名集不动）",
        )
        assertEquals("casebook", KnowledgeRepository.CASE_REPO, "专用案例仓名不变")
    }
}
