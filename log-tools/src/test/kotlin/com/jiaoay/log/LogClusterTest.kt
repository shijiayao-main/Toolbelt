package com.jiaoay.log

import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [LogCluster] 确定性契约测试（Drain 语义独立实现）：
 * 1. 模板归并正确性：同型异常（仅可变 ID/序号不同）归一簇，变量折叠 `<*>`；
 * 2. 相似度阈值行为：同叶候选 ≥ 阈值归并 / < 阈值立新簇 / 阈值参数化生效（含 0.4 边界）；
 * 3. 硬上界三面：模板簇数 ≤ [LogCluster.MAX_TEMPLATES]（溢出显式计数）、行号引用 ≤
 *    [LogCluster.MAX_LINE_REFERENCES]（截断明示且 count 不失真）、超长行跳过显式计数；
 * 4. 行号列表正确：块首行 1 基全文件行号（continuation 行不进列表）；
 * 5. 首末时间戳按值取 min/max（非单调日志）；
 * 6. 坏输入显式失败：非普通文件/非法阈值返回 [ClusterResult.Failure]，不抛不静默；
 * 7. 会计恒等式：candidateBlocks = templateBlocks + overflow + 空头跳过 + 超长跳过。
 */
class LogClusterTest {

    private fun tempDir(): File = createTempDirectory("log-cluster-test").toFile()

    private fun fixtureFile(dir: File, name: String, content: String): File =
        File(dir, name).apply { writeText(content) }

    /** 混合形态 fixture：无时间戳裸头块 + brief 带 pid 同型异常 ×4（时间非单调）+ threadtime W 块 */
    private fun mixedFixture(dir: File): File = fixtureFile(
        dir,
        "cluster-mixed.txt",
        buildString {
            appendLine("camera init start")
            appendLine("2026-10-11 08:00:01.000 E/CamService(1234): failed to open camera 0")
            appendLine("2026-10-11 08:00:05.000 E/CamService(1235): failed to open camera 1")
            appendLine("2026-10-11 08:00:09.000 E/CamService(1236): failed to open camera 2")
            appendLine("2026-10-11 08:00:02.000 W/SensorHub: heartbeat lost 3")
            appendLine("2026-10-11 07:59:00.000 E/CamService(1200): failed to open camera 9")
        },
    )

    /** 会计恒等式 + 计数一致性（所有 Ok 断面的公共不变量） */
    private fun assertAccounting(ok: ClusterResult.Ok) {
        assertEquals(
            ok.templateBlocks + ok.overflowBlocks + ok.skippedEmptyHeadBlocks + ok.oversizedLineBlocks,
            ok.candidateBlocks,
            "会计恒等式：candidateBlocks = 归并 + 溢出 + 空头跳过 + 超长跳过（无静默丢弃）",
        )
        assertEquals(
            ok.templates.sumOf { it.count },
            ok.templateBlocks,
            "templateBlocks = 各簇 count 之和",
        )
        assertTrue(ok.templates.size <= LogCluster.MAX_TEMPLATES, "模板簇数硬上界 ${LogCluster.MAX_TEMPLATES}")
        ok.templates.forEach {
            assertTrue(it.lineNumbers.size <= LogCluster.MAX_LINE_REFERENCES, "行号引用硬上界")
            if (it.lineNumbers.size < LogCluster.MAX_LINE_REFERENCES) {
                assertTrue(!it.lineNumbersTruncated, "引用未满则截断标记必为 false")
            }
        }
    }

    // ── 归并正确性 + 变量折叠 + 行号 + 首末时间戳 ──

    @Test
    fun `analyze merges same-shape errors folds variables and tracks head line numbers`() {
        val ok = assertIs<ClusterResult.Ok>(LogCluster.analyze(mixedFixture(tempDir())))

        assertEquals(6, ok.scannedLines)
        assertEquals(6, ok.totalBlocks, "fixture 共 6 块（含无时间戳裸头块）")
        assertAccounting(ok)

        // 簇数降序、并列按模板字典序（确定性排序锚）
        assertEquals(
            listOf(
                // pid/相机号折叠 <*>，同型异常 4 块归一簇；首末时间按值取 min/max（末行时间最早）
                TemplateCluster(
                    template = "E/CamService(<*>): failed to open camera <*>",
                    count = 4,
                    lineNumbers = listOf(2, 3, 4, 6),
                    lineNumbersTruncated = false,
                    firstTimestamp = LocalDateTime.parse("2026-10-11T07:59:00.000"),
                    lastTimestamp = LocalDateTime.parse("2026-10-11T08:00:09.000"),
                ),
                // 'W' < 'c'（字典序）：计数并列时 W 簇排在裸头簇前
                TemplateCluster(
                    template = "W/SensorHub: heartbeat lost <*>",
                    count = 1,
                    lineNumbers = listOf(5),
                    lineNumbersTruncated = false,
                    firstTimestamp = LocalDateTime.parse("2026-10-11T08:00:02.000"),
                    lastTimestamp = LocalDateTime.parse("2026-10-11T08:00:02.000"),
                ),
                TemplateCluster(
                    template = "camera init start",
                    count = 1,
                    lineNumbers = listOf(1),
                    lineNumbersTruncated = false,
                    firstTimestamp = null,
                    lastTimestamp = null,
                ),
            ),
            ok.templates,
        )
    }

    @Test
    fun `continuation lines do not enter the line number list`() {
        val file = fixtureFile(
            tempDir(),
            "continuation.txt",
            buildString {
                appendLine("2026-10-11 08:00:01.000 E/A: boom one")
                appendLine("stack continuation")
                appendLine("2026-10-11 08:00:02.000 E/A: boom two")
            },
        )
        val ok = assertIs<ClusterResult.Ok>(LogCluster.analyze(file))

        assertAccounting(ok)
        assertEquals(2, ok.totalBlocks, "2 块（首块含 continuation 行）")
        val cluster = ok.templates.single()
        assertEquals("E/A: boom <*>", cluster.template, "one/two 为非数字变量位，归并后折叠 <*>")
        assertEquals(2, cluster.count)
        // 块首行行号 = 1 与 3（continuation 行不进列表——块首行口径）
        assertEquals(listOf(1, 3), cluster.lineNumbers)
    }

    @Test
    fun `threadtime form clusters across varying pid and level filter applies`() {
        val file = fixtureFile(
            tempDir(),
            "threadtime.txt",
            buildString {
                appendLine("2026-10-11 08:00:01.000 100 200 E ThreadTag: socket timeout after 5000 ms")
                appendLine("2026-10-11 08:00:02.000 101 201 E ThreadTag: socket timeout after 5000 ms")
                appendLine("2026-10-11 08:00:03.000 100 200 W ThreadTag: heartbeat lost 3")
            },
        )
        val ok = assertIs<ClusterResult.Ok>(
            LogCluster.analyze(file, levels = setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)),
        )

        assertAccounting(ok)
        assertEquals(3, ok.totalBlocks)
        assertEquals(2, ok.candidateBlocks, "W 块被级别过滤拦下（差值 = totalBlocks - candidateBlocks）")
        val cluster = ok.templates.single()
        assertEquals("<*> <*> E ThreadTag: socket timeout after <*> ms", cluster.template, "pid/tid/毫秒数折叠 <*>")
        assertEquals(2, cluster.count)
        assertEquals(listOf(1, 2), cluster.lineNumbers)
    }

    // ── 相似度阈值行为（纯内核直测） ──

    private fun treeOf(threshold: Double = LogCluster.DEFAULT_SIMILARITY_THRESHOLD): DrainTree =
        DrainTree(threshold, LogCluster.MAX_TEMPLATES, LogCluster.MAX_LINE_REFERENCES)

    @Test
    fun `leaf similarity below threshold starts a new cluster`() {
        val tree = treeOf()
        // 同叶（共享前 3 token）但仅 3/8 = 0.375 < 0.4 → 不归并
        tree.add("a b c d e f g h", 1, null)
        tree.add("a b c x y z w v", 2, null)
        assertEquals(2, tree.templates().size, "相似度 0.375 低于默认阈值 0.4——立新簇")
    }

    @Test
    fun `leaf similarity at exactly threshold merges`() {
        val tree = treeOf()
        // 同叶 10 token：前 3 位 + 末位相同 = 4/10 = 0.4（边界，≥ 阈值归并）
        tree.add("a b c d e f g h i j", 1, null)
        tree.add("a b c e f g h i d j", 2, null)
        val templates = tree.templates()
        assertEquals(1, templates.size, "相似度恰为 0.4 时归并（阈值语义为 ≥）")
        assertEquals("a b c <*> <*> <*> <*> <*> <*> j", templates.single().template, "差异位折叠 <*>")
        assertEquals(2, templates.single().count)
    }

    @Test
    fun `threshold parameter changes merge behavior`() {
        // 0.375 在 0.3 档归并、在 1.0 档全异不归并
        val loose = treeOf(0.3)
        loose.add("a b c d e f g h", 1, null)
        loose.add("a b c x y z w v", 2, null)
        assertEquals(1, loose.templates().size, "阈值 0.3 下 0.375 ≥ 0.3 归并")

        val strict = treeOf(1.0)
        strict.add("a b c d e f g h", 1, null)
        strict.add("a b c d e f g h", 2, null)
        strict.add("a b c x y z w v", 3, null)
        assertEquals(2, strict.templates().size, "阈值 1.0 下全同才归并（0.875 与 0.375 均不达）")
        assertEquals(2, strict.templates().first().count, "全同消息归并后计数 2")
    }

    // ── 硬上界三面（模板容量 / 行号引用 / 超长行） ──

    /** 索引 → 纯字母标识（避免数字被变量掩蔽折叠成同串） */
    private fun letters(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do {
            sb.append('a' + (n % 26))
            n /= 26
        } while (n > 0)
        return sb.toString()
    }

    @Test
    fun `template count hard cap reports overflow explicitly`() {
        val dir = tempDir()
        val content = (0..200).joinToString("\n") { i ->
            "2026-10-11 08:00:00.000 E/T: unique${letters(i)} marker tail"
        }
        val ok = assertIs<ClusterResult.Ok>(LogCluster.analyze(fixtureFile(dir, "overflow.txt", content + "\n")))

        assertAccounting(ok)
        assertEquals(201, ok.totalBlocks)
        assertEquals(201, ok.candidateBlocks)
        assertEquals(LogCluster.MAX_TEMPLATES, ok.templates.size, "模板簇数恒 ≤ 硬上界（字面值 200）")
        assertEquals(1, ok.overflowBlocks, "第 201 个新形态块显式计入溢出，不静默吞掉")
        assertEquals(200, ok.templateBlocks)
    }

    @Test
    fun `line reference cap truncates explicitly while count stays exact`() {
        val dir = tempDir()
        val content = (1..501).joinToString("\n") { "2026-10-11 08:00:00.000 E/Rep: boom" }
        val ok = assertIs<ClusterResult.Ok>(LogCluster.analyze(fixtureFile(dir, "repeat.txt", content + "\n")))

        assertAccounting(ok)
        val cluster = ok.templates.single()
        assertEquals(501, cluster.count, "count 为全量计数——不因引用截断失真")
        assertEquals(LogCluster.MAX_LINE_REFERENCES, cluster.lineNumbers.size, "行号引用恒 ≤ 硬上界（字面值 500）")
        assertEquals((1..500).toList(), cluster.lineNumbers, "保留最早 500 次出现的首行行号")
        assertTrue(cluster.lineNumbersTruncated, "截断必须明示（lineNumbersTruncated=true）")
    }

    @Test
    fun `oversized head line is skipped and counted explicitly`() {
        val dir = tempDir()
        val longMsg = "x".repeat(LogCluster.MAX_TEMPLATE_LINE_CHARS)
        val content = "2026-10-11 08:00:00.000 E/T: $longMsg\n"
        val ok = assertIs<ClusterResult.Ok>(LogCluster.analyze(fixtureFile(dir, "oversize.txt", content)))

        assertAccounting(ok)
        assertEquals(1, ok.totalBlocks)
        assertEquals(1, ok.oversizedLineBlocks, "超长行不入聚类、显式计数")
        assertEquals(0, ok.templates.size)
    }

    @Test
    fun `timestamp-only head line is skipped and counted explicitly`() {
        val dir = tempDir()
        val ok = assertIs<ClusterResult.Ok>(
            LogCluster.analyze(fixtureFile(dir, "empty-head.txt", "2026-10-11 08:00:00.000\n")),
        )

        assertAccounting(ok)
        assertEquals(1, ok.totalBlocks)
        assertEquals(1, ok.skippedEmptyHeadBlocks, "纯时间戳行抹除后消息体为空——显式跳过计数")
        assertEquals(0, ok.templates.size)
    }

    // ── 坏输入显式失败 ──

    @Test
    fun `bad inputs fail explicitly instead of throwing or clamping`() {
        assertIs<ClusterResult.Failure>(LogCluster.analyze(File(tempDir(), "missing.txt")))
        assertIs<ClusterResult.Failure>(LogCluster.analyze(tempDir()), "目录不是聚类输入（须普通文件）")
        val file = fixtureFile(tempDir(), "ok.txt", "2026-10-11 08:00:00.000 E/A: boom\n")
        assertIs<ClusterResult.Failure>(LogCluster.analyze(file, similarityThreshold = 0.0), "阈值 0 非法")
        assertIs<ClusterResult.Failure>(LogCluster.analyze(file, similarityThreshold = 1.5), "阈值 > 1 非法")
        assertIs<ClusterResult.Failure>(LogCluster.analyze(file, similarityThreshold = -0.1), "负阈值非法")
    }

    @Test
    fun `empty file yields ok with zero blocks`() {
        val ok = assertIs<ClusterResult.Ok>(LogCluster.analyze(fixtureFile(tempDir(), "empty.txt", "")))
        assertAccounting(ok)
        assertEquals(0, ok.scannedLines)
        assertEquals(0, ok.totalBlocks)
        assertEquals(0, ok.templates.size)
    }
}
