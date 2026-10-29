package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.evidence.EvidenceRef
import com.jiaoay.easy_dev_toolbox.core.evidence.TIMESTAMP_PATTERN
import com.jiaoay.easy_dev_toolbox.core.evidence.parseReport
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 研判报告导出器：run 快照 → 可分享 Markdown 交付物（发同事/贴工单的载体）。
 *
 * 纯函数纪律：文件 IO 一律由调用方承担——[resolveEvidenceLines] 收行读取器
 * [linesOf] 注入缝（`evidenceSnippetOf` 同款配方），[exportDir]/[exportFileOf]
 * 只做路径决策，写盘在调用方。
 *
 * 导出目录 = `~/Documents/Toolbelt/reports/`：用户主目录下可见的工具产物目录（与 logcat
 * 抓取产物同根——`~/Documents/Toolbelt/logcat/`）。不落 `~/.toolbelt` 内部数据根：
 * 内部根是应用私有数据且混有敏感配置文件，分享产物必须可见可拖拽。注意区分
 * VM 内部报告自动落盘铁律（自动落盘恒在 run 目录）——导出是**用户发起的交付物
 * 落点**，两者不冲突。
 *
 * 文件名 = `report-<runId>.md`：runId 是 run 的天然唯一键，同 run 重复导出
 * **原位覆写**（导出恒反映最新报告文本，含追问增量）；不同 run 文件名天然不撞。
 */
object ReportExporter {

    /**
     * 证据行原文条目：[timestamp]（报告关键依据引用）→ [rawLine]（产物原文，逐字
     * 零截断；未命中 = null）——时间戳兜底路径 = 命中行整行，行区间回读路径 =
     * 引用区间内原文行按序拼接（多行内嵌换行，fenced code block 内逐字保真）。
     * [citationNote] = 行区间引用的 `文件名:L起-止` 注记（条目头标注回读出处；
     * 旧格式引用 / 兜底路径 = null——条目头不附注记）。
     */
    data class EvidenceLine(
        val timestamp: String,
        val rawLine: String?,
        val citationNote: String? = null,
    )

    /**
     * 导出目录：`<home>/Documents/Toolbelt/reports/`（父目录不存在则创建；创建失败留给
     * 调用方写盘报错——路径决策不吞错）。[home] 参数化供单测注入临时根。
     * 路径真源 = [FileUtils.getReportsExportDir]。
     */
    fun exportDir(home: File = File(System.getProperty("user.home"))): File =
        com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getReportsExportDir(home)

    /**
     * 导出时间行格式：本地时区 `yyyy-MM-dd HH:mm:ss`（分享场景人读格式）。
     * 时区在 format 调用时解析（[formatExportTime] 内 withZone）——formatter 若在
     * 对象初始化期带 ZoneId.systemDefault()，进程内后设的默认时区（测试隔离/宿主
     * 配置变更）不再生效；pattern 本体不可变线程安全（导出协程在 IO 线程池执行）。
     */
    private val EXPORT_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 导出文件：`report-<runId>.md`（覆写语义见本对象 KDoc） */
    fun exportFileOf(runId: String, dir: File = exportDir()): File = File(dir, "report-$runId.md")

    /** 毫秒时间戳 → 导出时间行文本（时区逐调用解析——见 [EXPORT_TIME_FORMAT] 契约） */
    fun formatExportTime(millis: Long): String =
        EXPORT_TIME_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

    /**
     * run 快照 → Markdown 交付物。结构三段：
     * 1. 导出元信息（runId/标题/状态/分析文件/时间点/分析发起/导出时间）；
     * 2. 报告全文**逐字保真**（原文原样嵌入，零截断零改写）；
	 * 3. 证据行原文（关键依据引用逐字回读段，零截断——报告内引用是时间戳（新格式
	 *    另附 `[证据 文件名:L起-止]` 标记，条目头随之标注回读出处），原文行在产物
	 *    文件里；卡面摘要的 60 字截断档是屏显配方，分享交付物不适用）。
     *
     * 证据行逐字嵌入 fenced code block：行内含三连反引号时渲染器会截断该块，
     * 导出文件字节仍逐字保真（渲染截断 ≠ 内容截断）。
     */
    fun exportMarkdown(
        run: RunStore.RunSummary,
        reportText: String,
        evidenceLines: List<EvidenceLine>,
        exportedAtMillis: Long,
    ): String = buildString {
        appendLine("# 日志分析报告")
        appendLine()
        appendLine("> 由 Toolbelt 日志工作台导出（结论 + 证据行 + 运行元信息）")
        appendLine()
        appendLine("## 导出元信息")
        appendLine()
        appendLine("- runId：${run.id}")
        appendLine("- 标题：${run.title}")
        appendLine("- 状态：${RunStore.RunStatus.label(run.status)}")
        appendLine("- 分析文件：")
        if (run.logFiles.isEmpty()) {
            appendLine("  - （无）")
        } else {
            run.logFiles.forEach { path -> appendLine("  - $path") }
        }
        if (run.targetTime != null) {
            appendLine(
                if (run.rangeMinutes != null) {
                    "- 时间点：${run.targetTime}（前后 ${run.rangeMinutes} 分钟）"
                } else {
                    "- 时间点：${run.targetTime}"
                },
            )
        }
        appendLine("- 分析发起：${formatExportTime(run.createdAt)}")
        appendLine("- 导出时间：${formatExportTime(exportedAtMillis)}")
        appendLine()
        appendLine("## 报告全文（原文保真）")
        appendLine()
        appendLine(reportText)
        appendLine()
        appendLine("## 证据行原文（关键依据逐字回读）")
        appendLine()
        if (evidenceLines.isEmpty()) {
            appendLine("（报告无关键依据时间戳引用）")
        } else {
            evidenceLines.forEach { line ->
                val note = line.citationNote?.let { citation -> "（$citation）" }.orEmpty()
                appendLine("### ${line.timestamp}$note")
                appendLine()
                if (line.rawLine != null) {
                    appendLine("```")
                    appendLine(line.rawLine)
                    appendLine("```")
                } else {
                    appendLine("（产物文件中未命中该时间戳）")
                }
                appendLine()
            }
        }
    }

    /**
     * 证据行原文解析（[exportMarkdown] 的数据源）：时间戳集 = [parseReport] 提取的
     * 关键依据引用；每条按 [evidenceFiles] 列表序（管线到达序 = 完整度递减，
     * `evidenceSnippetOf` 同款查找序）找首个含该时间戳的产物，取命中行**整行原文**。
     *
     * 带行区间引用（[EvidenceRef.fileName]/[EvidenceRef.startLine]/[EvidenceRef.endLine]
     * 均非空的新格式标记）优先走行区间回读，不走时间戳扫描：文件名按裸名对
     * [evidenceFiles] 精确匹配（[validateEvidenceCitation] 同款口径——剥 `/`/`\`
     * 路径分隔符后全等），命中产物经 [linesRangeOf] 只读 `[startLine, endLine]`
     * 闭区间行段（1 基），条目注记 `文件名:L起-止`。任一环失败（seam 未注入 /
     * 名称不在产物集 / 读取抛错 / 返回行数不足请求区间 = 越出文件尾）整条回退
     * 时间戳扫描兜底——导出不因引用标记炸裂；旧格式引用（无区间字段）恒走
     * 兜底路径零回归。
     *
     * 时间戳兜底命中判定与 `evidenceSnippetOf` 同式（[TIMESTAMP_PATTERN] 行内提取
     * 值比对）——单一命中口径，防两套判定漂移。全部文件无命中 =
     * [EvidenceLine.rawLine] null（渲染面回退「未命中」注记，时间戳引用本身保留）。
     */
    fun resolveEvidenceLines(
        reportText: String,
        evidenceFiles: List<String>,
        linesOf: (path: String) -> List<String>,
        linesRangeOf: ((path: String, startLine: Int, endLine: Int) -> List<String>)? = null,
        lineFinderOf: ((path: String, timestamp: String) -> String?)? = null,
    ): List<EvidenceLine> =
        parseReport(reportText).evidenceRefs.map { ref ->
            val ranged = rangeEvidenceLineOf(ref, evidenceFiles, linesRangeOf)
            EvidenceLine(
                timestamp = ref.timestamp,
                rawLine = ranged?.rawLine
                    ?: timestampLineOf(ref.timestamp, evidenceFiles, lineFinderOf, linesOf),
                citationNote = ranged?.citationNote,
            )
        }

    /**
     * 时间戳兜底回读：注入 [lineFinderOf] 时走逐行流式扫描（命中即停，内存只驻留一行），
     * 未注入才回退 [linesOf] 全量读——大日志（GB 级）下全量读会把整份文件摊成
     * `List<String>` 直接压垮 2g 堆，流式路径是导出可用的前提。
     *
     * 两条路径都按 [evidenceFiles] 顺序逐个文件尝试、命中即返回，判定口径同源
     * （[TIMESTAMP_PATTERN] 行内提取值比对），不漂移。
     */
    private fun timestampLineOf(
        timestamp: String,
        evidenceFiles: List<String>,
        lineFinderOf: ((path: String, timestamp: String) -> String?)?,
        linesOf: (path: String) -> List<String>,
    ): String? {
        if (lineFinderOf != null) {
            for (path in evidenceFiles) {
                val hit = runCatching { lineFinderOf(path, timestamp) }.getOrNull() ?: continue
                return hit
            }
            return null
        }
        return evidenceLineOf(timestamp, evidenceFiles, linesOf)
    }

    /**
     * 行区间引用单条回读：[EvidenceRef] 三区间字段齐备且 [linesRangeOf] 已注入时
     * 按区间取原文行，返回注记 `文件名:L起-止` 的条目；其余情况返回 null（调用方
     * 回退时间戳扫描）。[linesRangeOf] 契约与 [validateEvidenceCitation] 的行窗缝
     * 一致——返回 `[startLine, endLine]` 闭区间内实际存在的行（1 基），行数不足
     * 请求区间 = 区间越出文件尾。
     */
    private fun rangeEvidenceLineOf(
        ref: EvidenceRef,
        evidenceFiles: List<String>,
        linesRangeOf: ((path: String, startLine: Int, endLine: Int) -> List<String>)?,
    ): EvidenceLine? {
        if (linesRangeOf == null) return null
        val fileName = ref.fileName ?: return null
        val start = ref.startLine ?: return null
        val end = ref.endLine ?: return null
        val path = evidenceFiles.firstOrNull { candidate ->
            candidate.substringAfterLast('/').substringAfterLast('\\') == fileName
        } ?: return null
        val window = runCatching { linesRangeOf(path, start, end) }.getOrNull() ?: return null
        if (window.size < end - start + 1) return null
        return EvidenceLine(
            timestamp = ref.timestamp,
            rawLine = window.joinToString(separator = "\n"),
            citationNote = "$fileName:L$start-$end",
        )
    }

    private fun evidenceLineOf(
        timestamp: String,
        evidenceFiles: List<String>,
        linesOf: (path: String) -> List<String>,
    ): String? {
        for (path in evidenceFiles) {
            val lines = runCatching { linesOf(path) }.getOrNull() ?: continue
            val hit = lines.firstOrNull { TIMESTAMP_PATTERN.find(it)?.value == timestamp } ?: continue
            return hit
        }
        return null
    }
}
