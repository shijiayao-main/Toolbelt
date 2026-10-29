package com.jiaoay.easy_dev_toolbox.page.logtag

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [StatsMarkdownExporter] 确定性单测：固定统计快照 → 导出 md 内容锚逐项断言
 * （Tag 频次表 / 级别计数 / 时间跨度 / 元信息 / 目录与文件名规则）。
 */
class StatsMarkdownExporterTest {

    private val originalZone = TimeZone.getDefault()

    @BeforeTest
    fun setUp() {
        // 时间戳格式断言钉 UTC——ZoneId.systemDefault() 随 TimeZone.getDefault 变化
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @AfterTest
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `导出目录在用户可见产物树 reports 子目录`() {
        val home = File("build/tmp/stats-exporter-test-home").absoluteFile
        val dir = StatsMarkdownExporter.exportDir(home)
        assertEquals(
            File(File(File(home, "Documents"), "Toolbelt"), "reports").absolutePath,
            dir.absolutePath,
            "导出目录须与日志工作台报告导出同根（~/Documents/Toolbelt 可见产物树）下 reports 子目录",
        )
        assertTrue(dir.isDirectory, "exportDir 须负责父目录创建（调用方直接写盘）")
    }

    @Test
    fun `导出文件名为 stats 前缀加秒级时间键`() {
        val dir = File("build/tmp/stats-exporter-test-file").absoluteFile
        assertEquals(
            "stats-19700101-000000.md",
            StatsMarkdownExporter.exportFileOf(0L, dir).name,
            "文件名 = stats-<秒级时间键>.md（纪元零点 UTC 钉死）",
        )
        // 同秒重复导出同名 = 原位覆写语义（同秒内统计快照相同，覆写即最新）
        assertEquals(
            StatsMarkdownExporter.exportFileOf(0L, dir),
            StatsMarkdownExporter.exportFileOf(0L, dir),
        )
        // 异秒导出文件名不撞——按时间键自然留史
        assertFalse(
            StatsMarkdownExporter.exportFileOf(0L, dir).name ==
                StatsMarkdownExporter.exportFileOf(1_000L, dir).name,
            "秒级时间键随导出时刻递增，异秒导出不得覆写前一份",
        )
    }

    @Test
    fun `固定统计快照导出 md 内容锚全命中`() {
        val millis = 1_760_000_000_000L
        val exported = StatsMarkdownExporter.exportMarkdown(statsFixture(), millis)
        // 统计对象元信息锚
        assertTrue("- 日志文件：camera-sample.log" in exported, "文件名须进元信息（贴工单可溯源）")
        assertTrue("- 完整块数：6" in exported, "块数须进元信息")
        assertTrue("- 头行未识别出 Tag 的块数：1" in exported, "未识别 Tag 面计数如实呈现")
        assertTrue("- 头行未识别出级别的块数：1" in exported, "未识别级别面计数如实呈现")
        assertEquals(
            "- 导出时间：${
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(millis))
            }",
            exported.lineSequence().firstOrNull { it.startsWith("- 导出时间：") },
            "导出时间行格式锚（独立计算对照）",
        )
        // 时间跨度锚（首末时间戳 + 人性化跨度同现一行）
        assertTrue(
            "2026-10-11 07:59:00.000 → 2026-10-11 08:00:05.000（1 分 5 秒）" in exported,
            "时间跨度行 = 首末时间戳 + 人性化跨度（65 秒 → 1 分 5 秒档）",
        )
        // 级别计数表锚
        assertTrue("| 级别 | 块数 |" in exported, "级别计数走 Markdown 表格（贴工单保留列对齐）")
        assertTrue("| ERROR | 2 |" in exported, "ERROR 计数行")
        assertTrue("| WARN | 1 |" in exported, "WARN 计数行")
        assertTrue("| INFO | 1 |" in exported, "INFO 计数行")
        assertTrue("| DEBUG | 1 |" in exported, "DEBUG 计数行")
        // Tag 频次表锚（前 N 截断范围与快照同源——导出不补全也不截短）
        assertTrue(
            "## Tag 频次（前 4，共 4 个去重 Tag）" in exported,
            "Tag 频次段标题带前 N 与去重总数（与屏上面板同口径）",
        )
        assertTrue("| Tag | 块数 |" in exported, "Tag 频次走 Markdown 表格")
        assertTrue("| Alpha | 2 |" in exported, "频次居首 Tag 行")
        assertTrue("| Backtrack | 1 |" in exported, "并列 Tag 按字典序行")
        assertTrue("| Gamma | 1 |" in exported, "末位 Tag 行")
    }

    @Test
    fun `无时间戳快照导出跨度不可得而非猜零值`() {
        val exported = StatsMarkdownExporter.exportMarkdown(
            statsFixture(
                firstTimestampText = null,
                lastTimestampText = null,
                spanMillis = null,
            ),
            exportedAtMillis = 0L,
        )
        assertTrue(
            "未识别到行首时间戳，跨度不可得" in exported,
            "跨度缺席如实呈现不可得（与屏上面板同一口径），不产伪零值行",
        )
    }

    @Test
    fun `零 Tag 快照导出未识别注记且省略零值未识别面行`() {
        val exported = StatsMarkdownExporter.exportMarkdown(
            statsFixture(
                unparsedTagHeads = 0,
                unparsedLevelHeads = 0,
                topTags = emptyList(),
                totalTagCount = 0,
                levelCounts = emptyList(),
            ),
            exportedAtMillis = 0L,
        )
        assertTrue(
            "（未识别出任何 Tag——头行形态须为 E/Tag: 或 threadtime 形态）" in exported,
            "零 Tag 空态注记保留（指引头行形态），不产空表",
        )
        assertTrue(
            "（未识别出任何级别）" in exported,
            "零级别空态注记保留，不产空表",
        )
        assertFalse(
            exported.lineSequence().any { it.startsWith("- 头行未识别出") },
            "零值未识别面不产元信息行（零值行是噪音）",
        )
    }

    @Test
    fun `Tag 含竖线转义防表格列漂移`() {
        val exported = StatsMarkdownExporter.exportMarkdown(
            statsFixture(topTags = listOf("Tag|Pipe" to 3)),
            exportedAtMillis = 0L,
        )
        assertTrue(
            "| Tag\\|Pipe | 3 |" in exported,
            "Tag 取自日志原文可含竖线——单元格内须转义，否则渲染列数漂移",
        )
    }

    /** 统计快照 fixture（与 VM 统计用例同形态：频次/级别/跨度/未识别面俱全） */
    private fun statsFixture(
        unparsedTagHeads: Int = 1,
        unparsedLevelHeads: Int = 1,
        topTags: List<Pair<String, Int>> =
            listOf("Alpha" to 2, "Backtrack" to 1, "Beta" to 1, "Gamma" to 1),
        totalTagCount: Int = 4,
        levelCounts: List<Pair<String, Int>> =
            listOf("ERROR" to 2, "WARN" to 1, "INFO" to 1, "DEBUG" to 1),
        firstTimestampText: String? = "2026-10-11 07:59:00.000",
        lastTimestampText: String? = "2026-10-11 08:00:05.000",
        spanMillis: Long? = 65_000L,
    ) = LogTagStatsState.Ready(
        fileName = "camera-sample.log",
        totalBlocks = 6,
        unparsedTagHeads = unparsedTagHeads,
        unparsedLevelHeads = unparsedLevelHeads,
        topTags = topTags,
        totalTagCount = totalTagCount,
        levelCounts = levelCounts,
        firstTimestampText = firstTimestampText,
        lastTimestampText = lastTimestampText,
        spanMillis = spanMillis,
    )
}
