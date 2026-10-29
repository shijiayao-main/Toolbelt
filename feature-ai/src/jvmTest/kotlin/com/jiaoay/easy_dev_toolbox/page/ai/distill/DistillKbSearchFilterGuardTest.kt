package com.jiaoay.easy_dev_toolbox.page.ai.distill

import com.jiaoay.easy_dev_toolbox.core.data.db.KnowledgeRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 知识库检索结果落列表守护：
 *
 * 检索命中后 KbRepoBrowse 须局部过滤：keyword 非空且检索结果有效时黄金样本/文档
 * 两节只渲染命中（检索了不能等于没检索）；TAG/决策节点检索不覆盖（保持全量 +
 * 节头 hint「（检索不覆盖此表）」）；清空提交恢复全量（kbSearch("") → result=null）。
 * 纯 UI 条件渲染零 VM 改动。
 *
 * 双层断言：
 * - **过滤纯函数直测**（[kbRepoBrowseSearching]/[kbRepoBrowseShownSamples]——
 *   顶层纯函数直测缝，三用例：keyword 空=全量 / 命中过滤 / repo 不匹配=全量）；
 * - **源码锚**（渲染件无 Compose 运行时直测缝——DistillAbandonTest 同款口径）：
 *   渲染消费 searchResult.samples/docs + 节头 hint × 2 + repo 守卫 + expandedSample
 *   remember key 加 keyword（防过滤后索引错位）。
 */
class DistillKbSearchFilterGuardTest {

    // ── 过滤纯函数直测 ──

    @Test
    fun `ag14 blank keyword keeps full lists`() {
        val browse = sampleBrowse()
        val searching = kbRepoBrowseSearching(browse, searchResult = null)
        assertFalse(searching, "无检索结果 → 全量")
        assertFalse(
            kbRepoBrowseSearching(browse, sampleResult(keyword = "  ", repo = REPO_A)),
            "keyword 空白 → 全量（kbSearch 空串已清 result，双保险）",
        )
    }

    @Test
    fun `ag14 valid search result filters to hits`() {
        val browse = sampleBrowse()
        val result = sampleResult(keyword = "设置", repo = REPO_A)
        assertTrue(
            kbRepoBrowseSearching(browse, result),
            "keyword 非空 + repo 匹配 → 检索态（黄金样本/文档两节只显命中）",
        )
        assertEquals(listOf("命中样本"), kbRepoBrowseShownSamples(browse, result).map { it.title })
        assertEquals(listOf("命中文档"), kbRepoBrowseShownDocs(browse, result).map { it.title })
    }

    @Test
    fun `ag14 repo mismatch keeps full lists - cross-repo stale guard`() {
        val browse = sampleBrowse()
        // 🔴 跨仓在途脏数据防线：kbSearch 在途协程时 searchResult 可能仍是上一 repo 的
        val staleResult = sampleResult(keyword = "设置", repo = "other-repo")
        assertFalse(
            kbRepoBrowseSearching(browse, staleResult),
            "searchResult.repo != b.repo → 不套用（防跨仓在途脏数据）",
        )
        assertEquals(
            browse.goldenSamples.map { it.title },
            kbRepoBrowseShownSamples(browse, staleResult).map { it.title },
            "repo 不匹配恢复全量",
        )
    }

    // ── 源码锚（扫盘防回潮） ──

    @Test
    fun `ag14 section headers and remember keys wired in distill page`() {
        val source = stripKotlinComments(distillPageSource())
        listOf(
            "searchResult.repo == browse.repo" to "repo 守卫（防跨仓在途脏数据——派生纯函数内）",
            "val searching = kbRepoBrowseSearching(b, searchResult)" to
                "检索态判定接线（派生纯函数消费）",
            "val shownSamples = kbRepoBrowseShownSamples(b, searchResult)" to
                "黄金样本过滤派生（检索态只显命中）",
            "val shownDocs = kbRepoBrowseShownDocs(b, searchResult)" to
                "文档过滤派生（同款）",
            "if (searching) \"（检索不覆盖此表）\" else \"\"" to
                "TAG/决策节头 hint（检索态说明；title 拼接两节复用同串）",
            "remember(b.repo, keyword)" to "展开态 remember key 加 keyword（过滤切换防索引错位）",
            "SectionCard(title = \"黄金样本（${'$'}{shownSamples.size} / ${'$'}{b.goldenSamplesTotal}）\"" to
                "黄金样本节头计数随命中（shownSamples 派生；节标题「命中 / 全量」双数形态——agent-center-2.html:920）",
            "SectionCard(title = \"知识文档（${'$'}{shownDocs.size} / ${'$'}{b.docsTotal}）\"" to
                "文档节头计数随命中（shownDocs 派生；同构双数——:925）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "DistillPage 丢失 AG-14 要素「$needle」——$why")
        }
        // hint 恰 2 处（TAG + 决策节点，黄金样本/文档不挂 hint）
        assertEquals(
            2,
            Regex("""if \(searching\) "（检索不覆盖此表）" else """"").findAll(source).count(),
            "「（检索不覆盖此表）」节头 hint 恰 2 处（TAG 表 + 决策节点——检索只覆盖双路）",
        )
    }

    // ── 基建 ──

    private fun sampleBrowse(): DistillViewModel.KbBrowse = DistillViewModel.KbBrowse(
        repo = REPO_A,
        tags = emptyList(),
        decisionNodes = emptyList(),
        goldenSamples = listOf(
            KnowledgeRepository.GoldenSample(
                repo = REPO_A, title = "全量样本一", symptom = "s", rootCause = "r", fixSummary = "f",
                evidenceLog = null, tags = emptyList(), createdAt = 0L,
            ),
            KnowledgeRepository.GoldenSample(
                repo = REPO_A, title = "全量样本二", symptom = "s", rootCause = "r", fixSummary = "f",
                evidenceLog = null, tags = emptyList(), createdAt = 0L,
            ),
        ),
        docs = listOf(
            KnowledgeRepository.KnowledgeDoc(repo = REPO_A, name = "d1", title = "全量文档一", summary = "sm", body = "b", updatedAt = 0L),
        ),
        // 节标题「命中 / 全量」双数段：快照带全量数（与列表同源 size）
        goldenSamplesTotal = 2,
        docsTotal = 1,
    )

    private fun sampleResult(keyword: String, repo: String): DistillViewModel.KbSearchResult =
        DistillViewModel.KbSearchResult(
            keyword = keyword,
            repo = repo,
            samples = listOf(
                KnowledgeRepository.GoldenSample(
                    repo = repo, title = "命中样本", symptom = "s", rootCause = "r", fixSummary = "f",
                    evidenceLog = null, tags = emptyList(), createdAt = 0L,
                ),
            ),
            docs = listOf(
                KnowledgeRepository.KnowledgeDoc(repo = repo, name = "d2", title = "命中文档", summary = "sm", body = "b", updatedAt = 0L),
            ),
        )

    // 蒸馏页 = DistillPage + KbManagePanel 两文件（管理面拆出后仍同页语义）——拼接扫描
    private fun distillPageSource(): String {
        val page = File(locateProjectRoot(), DISTILL_PAGE)
        assertTrue(page.isFile, "源码锚文件缺失：$DISTILL_PAGE")
        val manage = File(locateProjectRoot(), KB_MANAGE_PANEL)
        assertTrue(manage.isFile, "源码锚文件缺失：$KB_MANAGE_PANEL")
        return page.readText() + manage.readText()
    }

    private fun locateProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    /** 剥除行注释与块注释（字符串感知）——DistillAbandonTest.stripKotlinComments 同款配方 */
    private fun stripKotlinComments(source: String): String {
        val sb = StringBuilder(source.length)
        var inString = false
        var inRawString = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                inRawString -> when {
                    c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        sb.append("\"\"\"")
                        i += 3
                        inRawString = false
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }

                inString -> when {
                    c == '\\' && i + 1 < source.length -> {
                        sb.append(c).append(source[i + 1])
                        i += 2
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = false
                        i++
                    }

                    c == '\n' -> {
                        sb.append(c)
                        inString = false
                        i++
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }

                else -> when {
                    c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        sb.append("\"\"\"")
                        i += 3
                        inRawString = true
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = true
                        i++
                    }

                    c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                        while (i < source.length && source[i] != '\n') i++
                    }

                    c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                        i += 2
                        while (i < source.length) {
                            if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') {
                                i += 2
                                break
                            }
                            if (source[i] == '\n') sb.append('\n')
                            i++
                        }
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
        return sb.toString()
    }

    private companion object {
        const val REPO_A = "repo-a"
        const val DISTILL_PAGE =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillPage.kt"
        const val KB_MANAGE_PANEL =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/KbManagePanel.kt"
    }
}
