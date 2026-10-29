package com.jiaoay.log

import com.github.ajalt.clikt.core.parse
import com.jiaoay.log.cli.LogUtilsCommand
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LogStatsAnalyzer] 确定性契约测试：fixture 日志 → Tag 频次 / 级别计数 / 首末时间戳
 * 跨度逐项断言；另钉死「块识别复用 [LogTagFilter.scanBlocks] 共享内核、不另造第二套
 * 块解析器」的源码锚。
 *
 * CLI `--type stats` 端到端：临时 fixture 跑命令断言 stdout 输出锚（Tag 频次 / 级别 /
 * 时间跨度逐项）与 checkRequired 扩展校验（txt/log 后缀白名单）。
 */
class LogStatsAnalyzerTest {

    private fun tempDir(): File = createTempDirectory("log-stats-test").toFile()

    /**
     * 7 块 fixture（每块 1-2 行）：块 1 = 无时间戳/级别的裸文本头（未识别面）；
     * 块 2/4 = E/Alpha（brief 形态）；块 3 = W/Beta(1234)（brief 带 pid 形态）；
     * 块 5 = D/Gamma；块 6 = threadtime 形态（`pid tid F ThreadTag:`）；
     * 块 7 = 时间上最早但出现最晚（钉死 first 按 min 取值而非首见）。
     */
    private fun statsFixtureFile(dir: File): File {
        val content = buildString {
            appendLine("无时间戳与级别的裸文本头")
            appendLine("2026-10-11 08:00:02.000 E/Alpha: block-2 head")
            appendLine("continuation block-2")
            appendLine("2026-10-11 08:00:00.000 W/Beta(1234): block-3 head")
            appendLine("2026-10-11 08:00:03.000 E/Alpha: block-4 head")
            appendLine("2026-10-11 08:00:04.000 D/Gamma: block-5 head")
            appendLine("2026-10-11 08:00:05.000 100 200 F ThreadTag: block-6 head")
            appendLine("2026-10-11 07:59:00.000 I/Backtrack: block-7 head")
        }
        return File(dir, "stats-fixture.txt").apply { writeText(content) }
    }

    @Test
    fun `analyze counts tags levels and timestamp span block by block`() {
        val ok = assertIs<LogStatsResult.Ok>(LogStatsAnalyzer.analyze(statsFixtureFile(tempDir())))

        assertEquals(8, ok.scannedLines)
        assertEquals(7, ok.totalBlocks, "fixture 共 7 块（含裸文本头块）")

        // Tag 频次：Alpha 2 块居首，其余并列按字典序
        assertEquals(
            listOf(
                TagCount("Alpha", 2),
                TagCount("Backtrack", 1),
                TagCount("Beta", 1),
                TagCount("Gamma", 1),
                TagCount("ThreadTag", 1),
            ),
            ok.tagCounts,
        )
        // 头行解析不出 Tag 的块数 = totalBlocks - 合计（块 1 裸文本头）
        assertEquals(1, ok.totalBlocks - ok.tagCounts.sumOf { it.count }, "仅块 1 头行解析不出 Tag")

        // 级别计数：E 2（块 2/4），W/I/D/F 各 1；块 1 头行解析不出级别
        assertEquals(2, ok.levelCounts[LogStatsAnalyzer.Level.ERROR])
        assertEquals(1, ok.levelCounts[LogStatsAnalyzer.Level.WARN])
        assertEquals(1, ok.levelCounts[LogStatsAnalyzer.Level.INFO])
        assertEquals(1, ok.levelCounts[LogStatsAnalyzer.Level.DEBUG])
        assertEquals(1, ok.levelCounts[LogStatsAnalyzer.Level.FATAL])
        assertNull(ok.levelCounts[LogStatsAnalyzer.Level.VERBOSE], "fixture 无 V 级块（仅非零档入账）")
        assertEquals(1, ok.totalBlocks - ok.levelCounts.values.sum(), "仅块 1 头行解析不出级别")

        // 首末时间戳：first 按 min 取值（块 7 出现最晚但时间最早），末值按 max
        assertEquals(LocalDateTime.parse("2026-10-11T07:59:00.000"), ok.firstTimestamp)
        assertEquals(LocalDateTime.parse("2026-10-11T08:00:05.000"), ok.lastTimestamp)
        assertEquals(65_000L, ok.spanMillis, "07:59:00.000 → 08:00:05.000 = 65 秒")
    }

    @Test
    fun `analyze file without any timestamp yields null span`() {
        val content = "E/TagA: no timestamp head\ncontinuation\nE/TagB: another\n"
        val file = File(tempDir(), "no-ts.txt").apply { writeText(content) }
        val ok = assertIs<LogStatsResult.Ok>(LogStatsAnalyzer.analyze(file))

        // 无时间行 → 全文一块（无时间行触发切分），首末/跨度缺席
        assertEquals(1, ok.totalBlocks)
        assertNull(ok.firstTimestamp)
        assertNull(ok.lastTimestamp)
        assertNull(ok.spanMillis)
        // 头行无时间戳前缀也照常解析 brief 形态
        assertEquals(TagCount("TagA", 1), ok.tagCounts.single())
    }

    @Test
    fun `analyze on missing file folds to Failure`() {
        val result = LogStatsAnalyzer.analyze(File(tempDir(), "not-exist.txt"))
        assertTrue(result is LogStatsResult.Failure, "读取异常折叠为 Failure（调用方免 try/catch）")
    }

    // ── parseHead：级别/Tag 双形态识别 ──────────────────────

    @Test
    fun `parseHead recognizes brief time form with bare timestamp`() {
        val info = LogStatsAnalyzer.parseHead("10-11 12:00:06.001 V/BareTag: msg")
        assertEquals(LogStatsAnalyzer.Level.VERBOSE, info.level)
        assertEquals("BareTag", info.tag)
    }

    @Test
    fun `parseHead recognizes brief form with pid and threadtime form`() {
        // brief 带 pid：Tag 取 `(` 前
        val brief = LogStatsAnalyzer.parseHead("2026-09-29 10:00:00.000 W/Tag(1234): msg")
        assertEquals(LogStatsAnalyzer.Level.WARN, brief.level)
        assertEquals("Tag", brief.tag)
        // threadtime：独立单字母级别 token + 后随 Tag token（`: msg` 余部并入）
        val threadtime = LogStatsAnalyzer.parseHead("05-09 10:00:00.000 1234 5678 D Main: msg")
        assertEquals(LogStatsAnalyzer.Level.DEBUG, threadtime.level)
        assertEquals("Main", threadtime.tag)
    }

    @Test
    fun `parseHead without tag token keeps level and marks tag absent`() {
        val info = LogStatsAnalyzer.parseHead("05-09 10:00:00.000 1 2 E")
        assertEquals(LogStatsAnalyzer.Level.ERROR, info.level, "threadtime 级别 token 后无 Tag token——级别照常入账")
        assertNull(info.tag, "Tag 缺席计未识别")
    }

    @Test
    fun `parseHead on unrecognizable head returns both null`() {
        val info = LogStatsAnalyzer.parseHead("随便一行没有级别与 Tag")
        assertNull(info.level)
        assertNull(info.tag)
    }

    // ── 单源解析器守护（扫盘型：验收须 --rerun-tasks --no-build-cache） ──

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    @Test
    fun `stats reuses the shared scanBlocks kernel and declares no second block parser`() {
        val source = File(
            projectRoot(),
            "log-tools/src/main/kotlin/com/jiaoay/log/LogStatsAnalyzer.kt",
        ).readText()
        assertTrue(
            "LogTagFilter.scanBlocks" in source,
            "统计的块识别必须复用 LogTagFilter.scanBlocks 共享内核（防两套块规则漂移）",
        )
        assertFalse(
            "fun scanBlocks" in source,
            "统计器内不得声明第二套块扫描实现",
        )
        assertFalse(
            "isTimeLine" in source,
            "块切分时间行判定只能经共享内核——统计器内不得自行判时间行切块",
        )
    }

    // ── CLI 端到端（--type stats） ──────────────────────────

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8.name()))
        return try {
            block()
            buffer.toString(Charsets.UTF_8.name())
        } finally {
            System.setOut(original)
        }
    }

    @Test
    fun `CLI stats 成功路径输出 Tag 级别 跨度逐项锚`() {
        val file = statsFixtureFile(tempDir())
        val output = captureStdout {
            LogUtilsCommand().parse(listOf("--type", "stats", "-f", file.absolutePath))
        }
        // 成功锚（脚本化退出判定口径与既有 type 一致）
        assertTrue("success! stats:" in output, "stdout 应含成功锚: $output")
        assertTrue("无产物文件" in output, "stats 为纯输出型，应明示无产物文件: $output")
        // 总量锚
        assertTrue("- 扫描行数: 8" in output, "扫描行数锚: $output")
        assertTrue("- 日志块总数: 7" in output, "块总数锚: $output")
        // 时间跨度锚：呈现格式与 --targetTime 入参口径一致；first 按 min 取值（块 7 出现最晚但时间最早）
        assertTrue(
            "- 时间跨度: 2026-10-11_07:59:00.000 ~ 2026-10-11_08:00:05.000（共 65000 毫秒）" in output,
            "时间跨度锚: $output",
        )
        // 级别计数锚：仅非零档入账，缺席档显零；块 1 裸文本头计入未识别
        assertTrue("V=0 D=1 I=1 W=1 E=2 F=1" in output, "级别计数锚: $output")
        assertTrue("未识别级别块数: 1" in output, "未识别级别块数锚: $output")
        // Tag 频次锚：降序 + 并列字典序；块 1 头行解析不出 Tag 计未识别
        assertTrue("Tag 频次 Top 5/5（未识别 Tag 块数: 1）" in output, "Tag 频次标题锚: $output")
        val alphaLine = output.lineSequence().firstOrNull { it.trim() == "- Alpha: 2" }
        assertTrue(alphaLine != null, "Tag 频次行锚（Alpha: 2）: $output")
        assertTrue(
            output.lineSequence().map { it.trim() }.toList().let { lines ->
                lines.indexOf("- Alpha: 2") in 0..lines.indexOf("- Backtrack: 1")
            },
            "Tag 频次须降序（Alpha 居首）: $output",
        )
    }

    @Test
    fun `CLI stats 无时间行文件明示跨度不可得`() {
        val file = File(tempDir(), "no-ts-cli.txt").apply {
            writeText("E/TagA: head\ncontinuation\nE/TagB: another\n")
        }
        val output = captureStdout {
            LogUtilsCommand().parse(listOf("--type", "stats", "-f", file.absolutePath))
        }
        assertTrue("- 时间跨度: 无可识别时间行" in output, "全文无时间行应如实呈现不可得，不猜零值: $output")
        assertTrue("- Tag 频次: 无可识别 Tag" !in output, "brief 形态头行可识别 Tag: $output")
    }

    @Test
    fun `CLI stats 不支持后缀按既有扩展校验失败`() {
        val file = File(tempDir(), "stats.bin").apply { writeText("whatever\n") }
        assertFailsWith<IllegalArgumentException> {
            LogUtilsCommand().parse(listOf("--type", "stats", "-f", file.absolutePath))
        }
    }

    @Test
    fun `CLI stats 对不存在文件报参数错误`() {
        val dir = tempDir()
        assertFailsWith<com.github.ajalt.clikt.core.UsageError> {
            LogUtilsCommand().parse(listOf("--type", "stats", "-f", File(dir, "missing.txt").absolutePath))
        }
    }
}
