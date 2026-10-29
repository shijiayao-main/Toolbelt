package com.jiaoay.easy_dev_toolbox.core.evidence

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 证据面数据模型与解析器（openspec log-workbench 拆解；
 * 调查工作台 S4 上移 :core——原 feature-logworkbench 页内 internal 件升格 public）。
 *
 * 纯函数直测缝（VM 直测纪律同款——不触 UI 框架、可 jvmTest 直测）。
 *
 * 行格式真源（两处对齐，不另造格式）：
 * - 时间戳：`LogExts.TIME_PATTERN`（log-tools）——`MM-dd HH:mm:ss.SSS` 行首；
 * - 级别/TAG：logcat 风格 `L/Tag: message`（LogAnalysisPipelineTest 夹具同构，
 *   如 `09-19 10:29:50.000 E/CameraManager: camera route failed`）。
 *
 * 消费面：工作台证据流（:ui/widget/evidence 渲染层 + feature-logworkbench 页）与
 * 右栏报告解析——上移 :core 后 :ui 与 feature 均可直接引用（feature 互不依赖铁律
 * 下公共契约落 :core 的既定通道）。
 */
data class LogLine(
    val seq: Int,
    val raw: String,
    /** 行首时间戳（非时间行 = null——如块内续行） */
    val timestamp: String?,
    /** 级别单字 E/W/I/D/V（无级别结构 = null） */
    val level: Char?,
    /** TAG（`L/Tag:` 段；无结构 = null） */
    val tag: String?,
    /** 消息体（`L/Tag: ` 之后；无结构 = null） */
    val message: String?,
)

/** 时间行结构：`09-19 10:29:50.000 E/CameraManager: camera route failed` */
private val LINE_PATTERN =
    Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+([EWIDV])/([^:]*):\s?(.*)""")

/** 时间戳提取（跳转锚点用——行内任意位置） */
val TIMESTAMP_PATTERN = Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")

fun parseLogLine(seq: Int, raw: String): LogLine {
    val m = LINE_PATTERN.find(raw)
    return if (m != null) {
        LogLine(
            seq = seq,
            raw = raw,
            timestamp = m.groupValues[1],
            level = m.groupValues[2].first(),
            tag = m.groupValues[3].takeIf { it.isNotBlank() },
            message = m.groupValues[4].takeIf { it.isNotEmpty() },
        )
    } else {
        LogLine(seq = seq, raw = raw, timestamp = null, level = null, tag = null, message = null)
    }
}

// ── 有界文件读取（内存中有界窗口，懒读） ──

/**
 * 产物文件有界读取结果（截断可见化）。
 *
 * - [lines]：窗口内行流（前 [readLogLines] limit 行——原语义不变）；
 * - [truncated]：文件超窗截断标记（读满 limit 后探测仍有剩余行为 true）；
 * - [totalLines]：文件总行数（截断提示「共 N 行」徽标用；未截断时恒等于
 *   lines.size——不额外遍历）。
 *
 * [totalLines] 只在 [truncated] 为 true 时才数完整个文件（读满 limit 后逐行
 *    数余量）——未截断文件零额外 IO；截断文件本就是 2000+ 行超限产物，数余量
 *    只一次线性流读，可接受。
 */
data class ReadLogLinesResult(
    val lines: List<LogLine>,
    val truncated: Boolean,
    val totalLines: Int,
)

/**
 * 产物文件有界读取（默认 2000 行封顶——超大过滤产物不整载入内存；
 * 封顶时调用方以「前 2000 行」为证据面窗口，直方图/行流/跳转均在该窗口内）。
 */
fun readLogLines(file: File, limit: Int = 2000): List<LogLine> {
    if (!file.isFile) return emptyList()
    val lines = ArrayList<LogLine>(minOf(limit, 1024))
    file.bufferedReader(Charsets.UTF_8).use { reader ->
        var seq = 0
        while (lines.size < limit) {
            val line = reader.readLine() ?: break
            lines += parseLogLine(seq++, line)
        }
    }
    return lines
}

/**
 * 有界读取 + 截断探测：读满 [limit] 后再读一行探测——
 * 仍有剩余 ⇒ truncated=true 并把余量数完得 [ReadLogLinesResult.totalLines]；
 * 恰读完 ⇒ truncated=false（[ReadLogLinesResult.totalLines] = lines.size，零额外遍历）。
 *
 * 消费面：工作台证据流截断提示（「已截断：显示前 2000 行 / 共 N 行」）。
 * ≤ limit 行的常规产物 truncated 恒 false——零提示零回归。
 */
fun readLogLinesResult(file: File, limit: Int = 2000): ReadLogLinesResult {
    if (!file.isFile) return ReadLogLinesResult(emptyList(), truncated = false, totalLines = 0)
    val lines = ArrayList<LogLine>(minOf(limit, 1024))
    var truncated = false
    var total = 0
    file.bufferedReader(Charsets.UTF_8).use { reader ->
        var seq = 0
        while (true) {
            val line = reader.readLine() ?: break
            total++
            if (lines.size < limit) {
                lines += parseLogLine(seq++, line)
            } else {
                // 首条超限行即截断实锤；余量只数不载（内存有界铁律不破）
                truncated = true
            }
        }
    }
    return ReadLogLinesResult(lines, truncated, total)
}

// ── 时间直方图（60 桶） ──

/**
 * 时间分布直方图分桶（默认 60 桶）：时间戳行按 min~max 等距切桶计数。
 * 无时间戳行跳过；时间戳不可解析行跳过；≤ 1 个有效时间戳时全部计入首桶（退化安全）。
 */
fun histogramBuckets(lines: List<LogLine>, buckets: Int = 60): IntArray {
    val times = lines.mapNotNull { it.timestamp }.mapNotNull { parseEpochMillis(it) }
    val result = IntArray(buckets)
    if (times.isEmpty()) return result
    val min = times.min()
    val max = times.max()
    val span = (max - min).coerceAtLeast(1L)
    times.forEach { t ->
        val idx = (((t - min) * buckets) / span).toInt().coerceIn(0, buckets - 1)
        result[idx]++
    }
    return result
}

/** `MM-dd HH:mm:ss.SSS` → epoch millis（固定 2000 年——桶只比相对先后，年份不参与分布） */
private fun parseEpochMillis(timestamp: String): Long? = runCatching {
    val day = timestamp.substring(0, 5).split("-").map { it.toInt() }
    val time = timestamp.substring(6).split(":", ".")
    // 简化解析（月日时分秒毫 → 绝对毫秒）——避免 DateTimeFormatter 依赖面
    ((day[0] * 31L + day[1]) * 24L + time[0].toLong()) * 3_600_000L +
        (time[1].toLong() * 60_000L) + (time[2].toLong() * 1000L) + time[3].toLong()
}.getOrNull()

// ── 报告解析（右栏因果链三段卡 + 三态徽章 + 引用锚） ──

/**
 * 关键依据引用：时间戳 + 该行日志的 TAG 与消息首行摘要
 * （snippet 从证据产物文件回读——[evidenceSnippetOf]；渲染面两行：时间戳
 * （mono primary 可点击）+ tag/snippet（labelSmall onSurfaceVariant））。
 *
 * 引用标记协议（报告格式对偶端——WorkbenchPrompts.disciplineSection「关键依据」条款）：
 * 证据块首行的 `[证据 <产物文件名>:L<起>-<止>]`（单行 `L<起>` 容忍为起止同值；
 * 止号 `L` 前缀双写形态 `L10-L12` 同容忍——见 [EVIDENCE_MARKER] 口径，行区间语义一致）。
 * 三个引用字段由 [parseReport] 从标记解析，全 null = 旧格式报告/坏标记降级
 * （纯时间戳引用——渲染与跳转语义同协议前）。
 */
data class EvidenceRef(
    /** 引用时间戳（跳转锚——与原 evidenceTimestamps 语义一致） */
    val timestamp: String,
    /** 命中行 TAG（`L/Tag:` 段解析；无结构 = null） */
    val tag: String?,
    /** 命中行消息首行截 60 字（无消息 = null） */
    val snippet: String?,
    /** 引用标记的产物文件名（裸文件名不带路径；未附标记 = null） */
    val fileName: String? = null,
    /** 引用行区间起（1 基，与 log.view / fs.extended.read_file 输出口径同基；未附标记 = null） */
    val startLine: Int? = null,
    /** 引用行区间止（1 基闭区间；单行引用 = 起止同值；未附标记 = null） */
    val endLine: Int? = null,
)

/**
 * 报告五部分的结构化解析面（[WorkbenchPrompts] 报告模板的对偶端）：
 * - 结论一句话；
 * - 因果链三段（`根因`、`直接故障点`、`影响面` 三条加粗行）；
 * - 三态列表项（✓ 已确认、? 怀疑点、✗ 已排除——失败假设不抹除）；
 * - 关键依据段的时间戳引用（点击跳中栏对应日志行）；
 * - 增量研判段（追问追加——`## 增量研判（追问）` 每段一张，按序）。
 */
data class ReportSections(
    val conclusion: String,
    /** [(label, text)]——因果链三段（报告未产出时为空） */
    val chain: List<Pair<String, String>>,
    val confirmed: List<String>,
    val suspected: List<String>,
    val excluded: List<String>,
    /**
     * 关键依据段引用（结构化改型：原 `List<String>` 时间戳 → [List<EvidenceRef>]——
     * snippet 数据源须从证据文件回读（parseReport 纯函数无文件访问，tag/snippet
     * 由页面侧 LaunchedEffect 回读拼装）。唯一消费点 LogWorkbenchPage 改型安全。
     */
    val evidenceRefs: List<EvidenceRef>,
    /** 追问增量段全文（每段一卡，序 = 追问次序；无追问为空） */
    val increments: List<String> = emptyList(),
    /** 时间线表格行（首行 header；`## 完整时间线` 段内 `|` 分割行） */
    val timelineRows: List<List<String>> = emptyList(),
)

/** 标题段落提取（`## <title> …` 到下一同级标题；容忍标题后缀说明文字） */
private fun sectionOf(report: String, title: String): String? {
    // 🔴 不用 \b：word boundary 基于 \w，中文字符不在 \w 内——对中文标题 \b 永不成立
    val heading = Regex("""^##\s*$title.*$""", RegexOption.MULTILINE)
    val start = heading.find(report)?.range ?: return null
    val after = report.substring(start.last + 1)
    val next = Regex("""^#{1,3}\s""", RegexOption.MULTILINE).find(after)
    val end = next?.range?.first ?: after.length
    return after.substring(0, end).trim().takeIf { it.isNotEmpty() }
}

/**
 * 同名标题段**多段**提取：追问合并（[mergeFollowUp] 分隔符
 * `## 增量研判（追问）`）每次追问追加一段同名标题——[sectionOf] 只取首段，多次
 * 追问的增量结论全部不可见。本函数按标题出现序逐段提取（每段到下一 `#{1,3}` 标题），
 * 段内 `---` 分隔行剥掉。
 */
private fun sectionsAllOf(report: String, title: String): List<String> {
    val heading = Regex("""^##\s*$title.*$""", RegexOption.MULTILINE)
    val nextHeading = Regex("""^#{1,3}\s""", RegexOption.MULTILINE)
    return heading.findAll(report).map { match ->
        val after = report.substring(match.range.last + 1)
        val end = nextHeading.find(after)?.range?.first ?: after.length
        after.substring(0, end)
            .lineSequence()
            .filterNot { it.trim() == "---" }
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotEmpty() }
    }.filterNotNull().toList()
}

fun parseReport(report: String): ReportSections {
    val conclusion = sectionOf(report, "结论")
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.removePrefix("——")
        ?.trim()
        ?: ""

    val rootCause = sectionOf(report, "问题根因").orEmpty()
    val chainRegex = Regex("""\*\*(根因|直接故障点|影响面)\*\*[：:]\s*(.+)""")
    val chain = chainRegex.findAll(rootCause).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()

    val confirmed = ArrayList<String>()
    val suspected = ArrayList<String>()
    val excluded = ArrayList<String>()
    rootCause.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("-") }
        .forEach { line ->
            when {
                "✓" in line -> confirmed += line.removePrefix("-").replace("✓", "").trim()
                "✗" in line -> excluded += line.removePrefix("-").replace("✗", "").trim()
                Regex("""^-\s*\?""").containsMatchIn(line) ->
                    suspected += line.removePrefix("-").replace("?", "", ignoreCase = false).trim()
            }
        }

    // 引用块级解析——timestamp 从报告提取，(文件名, 行区间) 从引用标记提取；
    // tag/snippet 由页面侧经 evidenceSnippetOf 从证据文件回读后拼装（parseReport
    // 纯函数无文件访问——页面回读缝）。
    val evidenceRefs = parseEvidenceRefs(sectionOf(report, "关键依据").orEmpty())

    // 追问增量段多段提取——`## 增量研判（追问）` 每段一卡
    val increments = sectionsAllOf(report, "增量研判（追问）")

    // 时间线表格行提取——`## 完整时间线` 段内 `|` 分割行
    //（prompt 模板「markdown 表格」的对偶端；`---` 分隔行跳过、格子 trim）
    val timelineRows = sectionOf(report, "完整时间线").orEmpty()
        .lineSequence()
        .filter { '|' in it }
        .filterNot { it.trim().replace("-", "").replace("|", "").isBlank() } // `|---|---|` 分隔行
        .map { line ->
            line.split('|')
                .dropLastWhile { cell -> cell.isBlank() } // 行尾管道符的空尾段
                .dropWhile { cell -> cell.isBlank() } // 行首管道符的空首段
                .map { cell -> cell.trim() }
        }
        .filter { it.isNotEmpty() }
        .toList()

    return ReportSections(
        conclusion = conclusion,
        chain = chain,
        confirmed = confirmed,
        suspected = suspected,
        excluded = excluded,
        evidenceRefs = evidenceRefs,
        increments = increments,
        timelineRows = timelineRows,
    )
}

// ── 证据引用标记（块级解析 + 行区间校验） ──

/**
 * 引用标记：`[证据 <产物文件名>:L<起>-<止>]`（单行 `L<起>` 容忍为起止同值）。
 * 止号 `L` 前缀双写形态（`L10-L12`）与裸止号（`L10-12`）同容忍——宽容进：
 * agent 照 prompt 例示双写任一形态都命中，行区间语义一致（prompt 端例示钉
 * 裸止号形态，见 [WorkbenchPrompts.disciplineSection]「关键依据」条款）。
 */
private val EVIDENCE_MARKER = Regex("""\[证据\s*([^\]\[:]+):L(\d+)(?:-L?(\d+))?\]""")

/** 证据块分隔：空行（标记与时间戳的同块绑定以块为界——跨块时间戳不归属本块引用） */
private val EVIDENCE_BLOCK_SPLIT = Regex("""\n\s*\n""")

/** 单个有效引用标记的解析产物（标记合法性在此收口，见 [parseEvidenceRefs]） */
private data class EvidenceMarker(
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
)

/**
 * 关键依据段引用解析：空行分块，每块内的引用标记与块内时间戳**同块绑定**
 * （块内每条时间戳各产一条 [EvidenceRef]，共享该块的 (文件名, 行区间)——时间戳
 * 归属只看本块，杜绝跨块串绑）。无标记的块回退纯时间戳语义；去重全局口径
 * （与扁平提取的 distinct 一致——旧格式报告（全段无标记）解析结果逐条不变）。
 *
 * 标记合法性（parse 层只做语法判定，文件行数边界归 [validateEvidenceCitation]）：
 * 行号 1 基（start ≥ 1）、止 ≥ 起、行号可装入 Int；坏标记（非数字/未知结构/退化
 * 区间/溢出）不炸解析——跳过该标记按无标记块处理。一块多标记取首个有效标记。
 */
internal fun parseEvidenceRefs(section: String): List<EvidenceRef> {
    val refs = ArrayList<EvidenceRef>()
    val seen = HashSet<String>()
    for (block in section.split(EVIDENCE_BLOCK_SPLIT)) {
        val marker = EVIDENCE_MARKER.findAll(block)
            .mapNotNull { m ->
                val name = m.groupValues[1].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val start = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val end = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: start
                if (start < 1 || end < start) null else EvidenceMarker(name, start, end)
            }
            .firstOrNull()
        for (m in TIMESTAMP_PATTERN.findAll(block)) {
            val timestamp = m.value
            if (!seen.add(timestamp)) continue
            refs += EvidenceRef(
                timestamp = timestamp,
                tag = null,
                snippet = null,
                fileName = marker?.fileName,
                startLine = marker?.startLine,
                endLine = marker?.endLine,
            )
        }
    }
    return refs
}

/** 引用校验四态（[validateEvidenceCitation] 返回值） */
enum class EvidenceCitationCheck {
    /** 两道硬门禁全过：区间在文件行数内 + 区间内存在行首时间戳与引用时间戳相等的行 */
    Verified,
    /** 区间越界：起止退化（起 < 1 / 止 < 起）或请求行数超出的窗口不完整（越出文件尾） */
    LineRangeOutOfBounds,
    /** 区间在文件行数内，但区间内无一行行首时间戳与引用时间戳相等 */
    TimestampMismatch,
    /** 引用不可解析落地：引用字段缺失 / 文件名不在证据产物集 / 行窗口读取失败 */
    FileUnresolved,
}

/**
 * 证据引用行区间校验（纯函数——行读取经 [linesOf] 注入，本函数零文件 IO）。
 *
 * 两道硬门禁全过才 [EvidenceCitationCheck.Verified]：
 * 1. 区间在文件行数内——[linesOf] 契约：返回 `[startLine, endLine]` 闭区间内实际
 *    存在的行（1 基，与 log.view / fs.extended.read_file 输出口径同基）；行数不足
 *    请求区间 = 区间越出文件尾；
 * 2. 区间内任一行**行首**时间戳与 [EvidenceRef.timestamp] 相等（行首提取
 *    [TIMESTAMP_PATTERN] matchAt 行首口径；块内续行无行首时间戳，不参与命中）。
 *
 * 失败态优先级：引用不可解析（[EvidenceCitationCheck.FileUnresolved]）> 区间越界
 * （[EvidenceCitationCheck.LineRangeOutOfBounds]）> 时间戳不匹配
 * （[EvidenceCitationCheck.TimestampMismatch]）——逐级判定，前者不落后者。
 */
fun validateEvidenceCitation(
    ref: EvidenceRef,
    evidenceFiles: List<String>,
    linesOf: (path: String, startLine: Int, endLine: Int) -> List<String>,
): EvidenceCitationCheck {
    val fileName = ref.fileName
    val start = ref.startLine
    val end = ref.endLine
    if (fileName == null || start == null || end == null) return EvidenceCitationCheck.FileUnresolved
    if (start < 1 || end < start) return EvidenceCitationCheck.LineRangeOutOfBounds
    val path = evidenceFiles.firstOrNull { candidate ->
        candidate.substringAfterLast('/').substringAfterLast('\\') == fileName
    } ?: return EvidenceCitationCheck.FileUnresolved
    val window = runCatching { linesOf(path, start, end) }.getOrNull()
        ?: return EvidenceCitationCheck.FileUnresolved
    if (window.size < end - start + 1) return EvidenceCitationCheck.LineRangeOutOfBounds
    val cited = window.any { line -> TIMESTAMP_PATTERN.matchAt(line, 0)?.value == ref.timestamp }
    return if (cited) EvidenceCitationCheck.Verified else EvidenceCitationCheck.TimestampMismatch
}

// ── 行级上下文回读（±N——懒读产物文件对应行段） ──

/**
 * 行级 ±N 上下文（跳过滤产物断档——tag 块过滤产物行间不连续，上下文须回读更完整产物）。
 *
 * 查找序：[evidenceFiles] 中位于 [currentFile] **之前**的产物（管线到达序 = 完整度递减序：
 * 解密 → 时间过滤 → Tag 过滤）从近及远找首个含 [timestamp] 的文件，取命中行 ±[n] 行段；
 * 自身即最完整产物（列表首位）时回读自身前后行。找不到返回空段（行原样保留）。
 *
 * 🔴 suspend + 流式定位（[findEvidenceFileOf] 同款 bufferedReader 配方）+ [Dispatchers.IO]：
 *    产物文件可达百万行级——组合期全量 readLines（每候选一次全量载入）会卡死调用线程；
 *    逐行流读命中即停，内存只持 ±n 窗口。
 */
suspend fun contextLinesOf(
    timestamp: String,
    evidenceFiles: List<String>,
    currentFile: String,
    n: Int,
): List<String> = withContext(Dispatchers.IO) {
    // 候选序 = evidenceFiles 列表序（管线到达序即完整度递减：解密 → 时间过滤 → Tag 过滤）——
    // 从最完整产物开始找（过滤产物行间断档，上下文在更完整文件里更全）
    val candidates = if (currentFile in evidenceFiles) evidenceFiles else evidenceFiles + currentFile
    for (path in candidates) {
        val hit = locateContextWindow(File(path), timestamp, n)
        if (hit != null) return@withContext hit
    }
    // 兜底：自身（不在候选链时——文件不可读/不含时间戳的候选已排除，无需重扫）
    if (currentFile !in candidates) {
        locateContextWindow(File(currentFile), timestamp, n)?.let { return@withContext it }
    }
    emptyList()
}

/**
 * 单文件流式 ±[n] 窗口定位：逐行扫描，命中 [timestamp] 前仅保留最近 [n] 行（滑动
 * 窗口），命中后收满 [n] 行即止。文件不含该时间戳（或不可读）返回 null——候选链继续。
 */
private fun locateContextWindow(file: File, timestamp: String, n: Int): List<String>? {
    if (!file.isFile) return null
    return runCatching {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val before = ArrayDeque<String>(n)
            val after = ArrayList<String>(n)
            var matchLine: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    matchLine != null -> {
                        after.add(line)
                        if (after.size >= n) break
                    }
                    TIMESTAMP_PATTERN.find(line)?.value == timestamp -> matchLine = line
                    else -> {
                        before.addLast(line)
                        if (before.size > n) before.removeFirst()
                    }
                }
            }
            if (matchLine == null) null else before.toList() + matchLine + after
        }
    }.getOrNull()
}

/** 引用跳转的目标证据文件：首个含该时间戳的产物（右栏引用 → 中栏行定位） */
fun findEvidenceFileOf(timestamp: String, evidenceFiles: List<String>): String? {
    for (path in evidenceFiles) {
        val file = File(path)
        if (!file.isFile) continue
        runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (TIMESTAMP_PATTERN.find(line!!)?.value == timestamp) return path
                }
            }
        }
    }
    return null
}

/**
 * 在单个产物文件里流式定位含 [timestamp] 的首个整行（含换行符前的原文，不含 `\n`）。
 *
 * 逐行扫描、命中即停——GB 级日志下全量 `readLines()` 会把整份文件摊成
 * `List<String>` 直接压垮 2g 堆；本函数在任一时刻只驻留一行，是导出路径可用的前提。
 * 判定口径与 [findEvidenceFileOf] 同源（[TIMESTAMP_PATTERN] 行内提取值比对），
 * 两处不得各写一套。
 *
 * @return 命中行原文；文件不存在/不可读/无命中均为 null（调用方按「未命中」注记呈现）。
 */
fun findTimestampLineIn(file: File, timestamp: String): String? {
    if (!file.isFile) return null
    return runCatching {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: return@use null
                if (TIMESTAMP_PATTERN.find(line)?.value == timestamp) return@use line
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }.getOrNull()
}

/**
 * 引用命中行的 TAG + 消息首行摘要：为 [EvidenceRef.tag]/
 * [EvidenceRef.snippet] 提供数据源——按 [contextLinesOf] 同款查找序（evidenceFiles
 * 列表序 = 完整度递减）找首个含 [timestamp] 的产物，取命中行单行。
 *
 * - **纯函数收行列表**（[linesOf]）——文件 IO 由调用方（页面 LaunchedEffect
 *   withContext(IO)）承担，本函数不做文件访问（parseReport 同款纯函数纪律）；
 * - 返回 (tag, snippet)：TAG 取 `L/Tag:` 段（[parseLogLine]），snippet = 消息体
 *   首行截 60 字（[SNIPPET_MAX_CHARS]；消息体缺失时回退整行截 60 字）；
 * - 全部文件无命中返回 null（渲染面回退纯时间戳行——现状语义）。
 */
fun evidenceSnippetOf(
    timestamp: String,
    linesOf: (path: String) -> List<String>,
    evidenceFiles: List<String>,
): Pair<String?, String>? {
    for (path in evidenceFiles) {
        val lines = runCatching { linesOf(path) }.getOrNull() ?: continue
        val hit = lines.firstOrNull { TIMESTAMP_PATTERN.find(it)?.value == timestamp } ?: continue
        val parsed = lines.indexOf(hit).let { idx -> parseLogLine(idx, hit) }
        val tag = parsed.tag
        val snippet = parsed.message?.let { it.take(SNIPPET_MAX_CHARS) }
            ?: hit.take(SNIPPET_MAX_CHARS)
        return tag to snippet
    }
    return null
}

/** 引用摘要截断长度（卡面配方：消息首行截 60 字） */
private const val SNIPPET_MAX_CHARS = 60
