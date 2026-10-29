package com.jiaoay.log

import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 大文件管线性能基线 harness：确定性生成 20 万行日志 fixture（固定 seed，同机同内容，
 * 防测量面自身漂移），对五个管线引擎分段计时并输出耗时数字到 stdout（测试报告
 * system-out 亦可取数）。
 *
 * 断言口径 = 数量级粗上界：各段阈值常量为本机实测基线的 30 倍向上取整（基线数字与
 * 机器口径落账在 docs/log-tools.md「性能基线」节），只在算法级退化（如 O(n²)）时红；
 * 不断言精确毫秒值——精确值随机器与负载漂移，断精确值必 flaky。失败信息附实测值供归因。
 *
 * fixture 生成与全部引擎段合于一个常规跑测试类（不设手动形态）：生成约 20 万行两份
 * 仅数十毫秒，各引擎段均为单遍线性扫描，类总时长秒级，与既有 6 万行 diff 用例同数量级。
 * fixture 经 companion 持有单例：JUnit 每测试方法新建实例，实例级 lazy 会让每个
 * 方法各生成一份，测量面与时长都翻倍。
 */
class LogPerfBaselineTest {

    private companion object {

        const val TOTAL_LINES = 200_000

        // 各段数量级粗上界（ms）：= 本机实测基线 ×30 向上取整；基线数字与实测口径
        // 落账在 docs/log-tools.md「性能基线」节，改阈值须同步更新该节。
        const val FIXTURE_GEN_BUDGET_MS = 2_000L
        const val VIEWER_OPEN_BUDGET_MS = 3_000L
        const val VIEWER_WINDOW_BUDGET_MS = 1_000L
        const val TAG_FILTER_BUDGET_MS = 5_000L
        const val DIFF_BUDGET_MS = 6_000L
        const val STATS_BUDGET_MS = 15_000L
        const val TIME_FILTER_BUDGET_MS = 3_000L

        private val HEAD_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

        private val fixture: Fixture by lazy { buildFixture() }

        /** 生成产物：基准文件 + diff 变体文件 + 块头行号与时间锚（断言与选窗共用，不二次扫描） */
        private class Fixture(
            val baseFile: File,
            val variantFile: File,
            val lines: List<String>,
            val headIndexes: IntArray,
            val headTimes: Array<LocalDateTime>,
            val diffChangeIndexes: IntArray,
        )

        private fun <T> timedBudget(stage: String, budgetMs: Long, block: () -> T): T {
            val start = System.nanoTime()
            val result = block()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            println("[perf-baseline] $stage: ${elapsedMs}ms（数量级粗上界 ${budgetMs}ms）")
            assertTrue(
                elapsedMs < budgetMs,
                "$stage 实测 ${elapsedMs}ms 超过数量级粗上界 ${budgetMs}ms" +
                    "（基线数字见 docs/log-tools.md「性能基线」节）——疑似算法级退化，须复测归因后再处理",
            )
            return result
        }

        private fun writeLines(file: File, lines: List<String>) {
            file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer: BufferedWriter ->
                for (line in lines) {
                    writer.write(line)
                    writer.write('\n'.code)
                }
            }
        }

        /**
         * 确定性 fixture：块 = 时间戳头行 + 0..3 行无时间戳续行（ERROR 块带异常堆栈形态），
         * 行内容全部由 Random(42) 推导——同 seed 同内容。时间锚只前进不回跳，保证
         * LogTimeFilter 的块序与提前结束路径可预期。
         */
        private fun buildFixture(): Fixture {
            val random = Random(42)
            val tags = listOf(
                "MainActivity", "SystemUI", "CameraService", "MediaEngine", "MediaSession",
                "AudioFocus", "CameraHAL", "WifiService", "PowerKeeper", "BootReceiver",
            )
            val levels = "DDDDDDDDIIIIIWWWEEV"
            val lines = ArrayList<String>(TOTAL_LINES + 4096)
            val headIndexes = ArrayList<Int>()
            val headTimes = ArrayList<LocalDateTime>()
            var millis = 0L
            while (lines.size < TOTAL_LINES) {
                millis += random.nextInt(1, 21)
                val time = LocalDateTime.of(2026, 8, 27, 9, 0, 0, 0).plusNanos(millis * 1_000_000)
                val level = levels[random.nextInt(levels.length)]
                val tag = tags[random.nextInt(tags.size)]
                val pid = 1000 + random.nextInt(9000)
                headIndexes.add(lines.size)
                headTimes.add(time)
                lines.add(
                    "${time.format(HEAD_TIME)} $level/$tag($pid): state frame ${lines.size} seq $millis",
                )
                val continuations = random.nextInt(4)
                if (level == 'E' && continuations > 0) {
                    lines.add("java.lang.IllegalStateException: bad transition at seq $millis")
                    repeat(continuations - 1) {
                        lines.add("    at com.example.demo.Handler.frame$it(Handler.kt:${random.nextInt(2000)})")
                    }
                } else {
                    repeat(continuations) {
                        lines.add("    detail continuation $it of block seq $millis")
                    }
                }
            }
            // 尾块可能越过规模锚：截到恰好 TOTAL_LINES 行；截掉的只能是尾块续行或尾块整体，
            // 块头计数同步收缩（headIndex >= TOTAL_LINES 的块头连带丢弃）
            if (lines.size > TOTAL_LINES) {
                lines.subList(TOTAL_LINES, lines.size).clear()
                while (headIndexes.isNotEmpty() && headIndexes.last() >= TOTAL_LINES) {
                    headIndexes.removeAt(headIndexes.size - 1)
                    headTimes.removeAt(headTimes.size - 1)
                }
            }
            assertEquals(TOTAL_LINES, lines.size, "fixture 规模锚：应恰好生成 TOTAL_LINES 行")

            val changeAt = intArrayOf(
                headIndexes[headIndexes.size / 7],
                headIndexes[headIndexes.size / 2],
                headIndexes[headIndexes.size - headIndexes.size / 7],
            )
            val changed = lines.toMutableList()
            for (index in changeAt) {
                changed[index] = "${lines[index]} CHANGED"
            }

            val dir = File(System.getProperty("java.io.tmpdir"), "log-perf-baseline-${System.nanoTime()}")
            dir.mkdirs()
            dir.deleteOnExit()

            val fixture = Fixture(
                baseFile = File(dir, "baseline.log"),
                variantFile = File(dir, "variant.log"),
                lines = lines,
                headIndexes = headIndexes.toIntArray(),
                headTimes = headTimes.toTypedArray(),
                diffChangeIndexes = changeAt,
            )
            timedBudget("fixture 生成（${TOTAL_LINES} 行写盘 ×2 份）", FIXTURE_GEN_BUDGET_MS) {
                writeLines(fixture.baseFile, lines)
                writeLines(fixture.variantFile, changed)
            }
            return fixture
        }
    }

    // ── 查看器：行偏移索引构建 + 任意行号窗口回读 ──

    @Test
    fun `查看器行偏移索引构建与窗口回读`() {
        val session = timedBudget("LogViewer.open（${TOTAL_LINES} 行偏移索引构建）", VIEWER_OPEN_BUDGET_MS) {
            val result = LogViewer.open(fixture.baseFile)
            assertIs<LogViewer.OpenResult.Ok>(result).session
        }
        assertEquals(fixture.lines.size, session.totalLines)

        val mid = fixture.headIndexes[fixture.headIndexes.size / 2]
        val window: List<ViewerLine> = timedBudget("LogViewer.readWindow（窗口回读 ×3）", VIEWER_WINDOW_BUDGET_MS) {
            val windows: List<ViewerLine> = session.readWindow(0, 5000) +
                session.readWindow(mid, 4000) +
                session.readWindow(fixture.lines.size - 100, 100)
            windows
        }
        assertEquals(
            fixture.lines[mid],
            window[5000].text,
            "中段块头行应与 fixture 生成内容逐字一致（索引正确性锚）",
        )
    }

    // ── Tag 块过滤：全量块扫描 + 命中块写盘 ──

    @Test
    fun `Tag 块过滤全量扫描`() {
        val outFile = File(fixture.baseFile.parentFile, "baseline.filter-tag_perf.txt")
        outFile.deleteOnExit()
        timedBudget("LogTagFilter.filterByTagBlock（全量块扫描 + 命中块写盘）", TAG_FILTER_BUDGET_MS) {
            LogTagFilter.filterByTagBlock(fixture.baseFile, outFile, "CameraService|MediaEngine")
        }
        assertTrue(outFile.isFile && outFile.length() > 0L, "命中块产物应非空")
    }

    // ── diff：20 万行三处差异的 Myers 对齐 ──

    @Test
    fun `diff 两份二十万行日志三处差异`() {
        val result = timedBudget("LogDiff.diff（${TOTAL_LINES} 行 ×2 份，3 处编辑）", DIFF_BUDGET_MS) {
            LogDiff.diff(fixture.baseFile, fixture.variantFile)
        }
        val ok = assertIs<LogDiff.DiffResult.Ok>(result)
        assertEquals(false, ok.degraded, "3 处编辑远低于退化上限，不应退化")
        assertEquals(fixture.lines.size, ok.oldLineCount)
        assertEquals(fixture.lines.size, ok.newLineCount)
        assertEquals(fixture.diffChangeIndexes.size, ok.hunks.size, "3 处分散编辑应恰好 3 个 hunk")
    }

    // ── 分布统计：单遍扫描聚合 ──

    @Test
    fun `分布统计全量单遍扫描`() {
        val result = timedBudget("LogStatsAnalyzer.analyze（全量单遍聚合）", STATS_BUDGET_MS) {
            LogStatsAnalyzer.analyze(fixture.baseFile)
        }
        val ok = assertIs<LogStatsResult.Ok>(result)
        assertEquals(fixture.lines.size, ok.scannedLines)
        assertEquals(fixture.headIndexes.size, ok.totalBlocks, "块数应与生成的时间戳头行数一致")
        assertNotNull(ok.firstTimestamp, "fixture 头行全部带时间戳，首时间戳不应缺席")
        assertEquals(fixture.headTimes.first(), ok.firstTimestamp)
    }

    // ── 时间窗口过滤：提前结束路径 ──

    @Test
    fun `时间窗口过滤命中中段区间`() {
        val anchor = fixture.headTimes[fixture.headTimes.size / 2]
        val outFile = File(fixture.baseFile.parentFile, "baseline.filter-time_perf.txt")
        outFile.deleteOnExit()
        val stats = timedBudget(
            "LogTimeFilter.filterByTime（中段 ±3 分钟窗口，提前结束路径）",
            TIME_FILTER_BUDGET_MS,
        ) {
            LogTimeFilter.filterByTime(
                inputFile = fixture.baseFile,
                outputFile = outFile,
                startTime = anchor.minusMinutes(3),
                endTime = anchor.plusMinutes(3),
                currentYear = "2026",
            )
        }
        // timeLines 只统计到超窗 break 为止（后续行不再解析），故恒小于全量块头数——
        // 相等即提前结束路径失效，应追查
        assertTrue(
            stats.timeLines < fixture.headIndexes.size.toLong(),
            "timeLines=${stats.timeLines} 等于全量块头数 ${fixture.headIndexes.size}，提前结束路径未生效",
        )
        assertTrue(stats.writtenLines > 0L, "中段锚点 ±3 分钟窗口应命中非零行")
    }
}
