package com.jiaoay.easy_dev_toolbox.page.logtag

import java.io.File
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 分布统计导出器：[LogTagStatsState.Ready] 快照 → 可分享 Markdown 交付物
 * （把「哪个 Tag 刷屏、ERROR 占比、时间跨度」的侦察结论变成可贴工单/发同事的
 * 可引用材料——屏上面板只能截图）。
 *
 * 纯函数纪律：数据源 = 面板同款统计快照（调用方持快照直调，**不重扫文件**——
 * 导出与面板展示同源单数据）；[exportDir]/[exportFileOf] 只做路径决策，写盘在调用方。
 *
 * 导出目录 = `~/Documents/Toolbelt/reports/`：用户主目录下可见的工具产物树（与日志工作台
 * 报告导出同根）。不落应用内部数据根——内部根混有敏感配置，分享产物必须可见可拖拽。
 *
 * 文件名 = `stats-<秒级时间键>.md`：统计快照无天然唯一键（不像 run 报告有 runId），
 * 以导出时刻为键——按时间排序自然成史、无文件名清洗问题；同秒重复导出原位覆写
 * （同秒内统计快照相同，覆写即最新）。
 */
object StatsMarkdownExporter {

    /**
     * 导出目录：`<home>/Documents/Toolbelt/reports/`（父目录不存在则创建；创建失败留给
     * 调用方写盘报错——路径决策不吞错）。[home] 参数化供单测注入临时根。
     * 路径真源 = [com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getReportsExportDir]。
     */
    fun exportDir(home: File = File(System.getProperty("user.home"))): File =
        com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getReportsExportDir(home)

    /**
     * 文件名时间键格式：本地时区秒级。秒级粒度 = 同秒导出覆写、异秒导出留史。
     * [DateTimeFormatter] 不可变线程安全（导出协程在 IO 线程池执行）。
     */
    private val FILE_NAME_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    /** 导出时间行格式：本地时区 `yyyy-MM-dd HH:mm:ss`（分享场景人读格式） */
    private val EXPORT_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /** 导出文件：`stats-<秒级时间键>.md`（覆写语义见本对象 KDoc） */
    fun exportFileOf(exportedAtMillis: Long, dir: File = exportDir()): File =
        File(dir, "stats-${FILE_NAME_TIME_FORMAT.format(Instant.ofEpochMilli(exportedAtMillis))}.md")

    /** 毫秒时间戳 → 导出时间行文本 */
    fun formatExportTime(millis: Long): String = EXPORT_TIME_FORMAT.format(Instant.ofEpochMilli(millis))

    /**
     * 统计快照 → Markdown 交付物。结构四段：
     * 1. 统计对象元信息（文件名/块数/未识别面计数/导出时间）；
     * 2. 时间跨度（两文本缺席时如实呈现不可得，不猜零值——与屏上面板同一口径）；
     * 3. 级别计数表（Markdown 表格——贴工单可保留列对齐）；
     * 4. Tag 频次表（[LogTagStatsState.Ready.topTags] 快照自带前 N 截断，导出不再
     *    截断也不补全——与面板呈现同源同范围，防「导出比屏上多/少」两套口径）。
     */
    fun exportMarkdown(stats: LogTagStatsState.Ready, exportedAtMillis: Long): String = buildString {
        appendLine("# 日志分布统计")
        appendLine()
        appendLine("> 由 Toolbelt Tag 过滤「看分布」导出（Tag 频次 / 级别计数 / 时间跨度，全文件口径）")
        appendLine()
        appendLine("## 统计对象")
        appendLine()
        appendLine("- 日志文件：${stats.fileName}")
        appendLine("- 完整块数：${stats.totalBlocks}")
        // 未识别面仅非零时呈现——零值行是噪音（与屏上面板同口径）
        if (stats.unparsedTagHeads > 0) appendLine("- 头行未识别出 Tag 的块数：${stats.unparsedTagHeads}")
        if (stats.unparsedLevelHeads > 0) appendLine("- 头行未识别出级别的块数：${stats.unparsedLevelHeads}")
        appendLine("- 导出时间：${formatExportTime(exportedAtMillis)}")
        appendLine()
        appendLine("## 时间跨度")
        appendLine()
        if (stats.firstTimestampText != null && stats.lastTimestampText != null) {
            appendLine("- ${stats.firstTimestampText} → ${stats.lastTimestampText}（${formatSpanMillis(stats.spanMillis)}）")
        } else {
            appendLine("- 未识别到行首时间戳，跨度不可得")
        }
        appendLine()
        appendLine("## 级别块数")
        appendLine()
        if (stats.levelCounts.isEmpty()) {
            appendLine("（未识别出任何级别）")
        } else {
            appendLine("| 级别 | 块数 |")
            appendLine("|---|---|")
            stats.levelCounts.forEach { (level, count) ->
                appendLine("| ${escapeTableCell(level)} | $count |")
            }
        }
        appendLine()
        if (stats.topTags.isNotEmpty()) {
            appendLine("## Tag 频次（前 ${stats.topTags.size}，共 ${stats.totalTagCount} 个去重 Tag）")
            appendLine()
            appendLine("| Tag | 块数 |")
            appendLine("|---|---|")
            stats.topTags.forEach { (tag, count) ->
                appendLine("| ${escapeTableCell(tag)} | $count |")
            }
        } else {
            appendLine("## Tag 频次")
            appendLine()
            appendLine("（未识别出任何 Tag——头行形态须为 E/Tag: 或 threadtime 形态）")
        }
        appendLine()
    }

    /** 表格单元格转义：`|` 是表格列分隔符，Tag 取自日志原文可含竖线——不转义则列数漂移 */
    private fun escapeTableCell(text: String): String = text.replace("|", "\\|")

    /**
     * 跨度毫秒的人性化呈现（毫秒/秒/分秒三档）。屏上面板与导出共用本实现——
     * 两处各自实现会漂移出两套换算口径。
     */
    fun formatSpanMillis(millis: Long?): String {
        val value = millis ?: return "跨度未知"
        return when {
            value < 1_000 -> "$value 毫秒"
            value < 60_000 -> String.format(Locale.ROOT, "%.1f 秒", value / 1000.0)
            else -> {
                val minutes = value / 60_000
                val seconds = (value % 60_000) / 1_000
                "$minutes 分 $seconds 秒"
            }
        }
    }
}
