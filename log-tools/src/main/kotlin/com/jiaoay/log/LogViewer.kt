package com.jiaoay.log

import com.jiaoay.xlog.LogFormatDetector
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.time.LocalDateTime

/**
 * 大文件日志查看引擎：按行号窗口 seek 读取，任意行号跳转直达（行偏移索引 + 随机访问回读）。
 *
 * ## 内存上界（大文件不整文件驻留）
 * 打开为两遍流式字节扫描（先数行、后按精确行数填充，内容不驻留），会话驻留只有两块：
 * - 行起始偏移索引：8 字节/行（[LogViewSession.indexMemoryBytes]），与行长、文件字节总量
 *   无关；行数超 [MAX_INDEX_LINES] 在打开时即拒绝（索引内存由此封顶，见该常量值依据）；
 * - 单次窗口行集：≤ [MAX_WINDOW_LINES] 行原文（请求行数超限被钳制——窗口外行永不驻留）。
 * 百 MB 量级顺序扫描亚秒级；打开后无常驻文件句柄（每次窗口读取独立开随机访问流）。
 *
 * ## 行语义（与 [LogDiff] 同一套 readLine 口径，单源不漂移）
 * `\n`、`\r\n`、孤立 `\r` 均为行终止；文末无终止符的尾行计一行；空文件零行。
 *
 * ## 格式路由（判型单源复用 [LogFormatDetector]，不造第二套判型）
 * - XLOG_SEGMENTS：拒绝（加密段结构需先解密产出 txt 再查看）；
 * - PLAINTEXT / UNKNOWN：按 UTF-8 文本查看（查看只读不改写；UNKNOWN 形态的不可解码
 *   字节按替换符呈现，无害）。
 *
 * ## 并发与漂移
 * 引擎非线程安全（单调用方串行使用）；查看期间文件长度变化按失效处理
 * （[LogViewSession.readWindow] 抛 [IllegalStateException]，调用方重新打开）。
 * 同长度的原地改写不可探测——索引只锚定行起始偏移与文件长度。
 *
 * 阻塞 IO：调用方自行包 Dispatchers.IO。
 */
object LogViewer {

    /**
     * 单窗口行数硬上界（真门禁：请求超出被钳制到本值，窗口行集内存由此封顶）。
     * 值依据：桌面日志页一屏约数十行，5000 行已覆盖无滚动的全量检视需求，
     * 按 1KB/行的病态行长估算行集上限约 5MB。
     */
    const val MAX_WINDOW_LINES: Int = 5000

    /**
     * 单次搜索结果集硬上界（真门禁：匹配行数达到本值即停止扫描并置 truncated）。
     * 值依据：大文件高频词匹配可达百万行，无界收集会随匹配数线性膨胀驻留内存；
     * 1000 处已覆盖人工逐处跳转的全部实际使用量，超限明示截断而非静默丢精度。
     */
    const val MAX_SEARCH_RESULTS: Int = 1000

    /**
     * 可打开文件总行数硬上界（真门禁：超限文件开门拒绝，不静默截断——行偏移索引是
     * O(1) 行号直达的唯一支撑，截断即行号与内容错位失真）。
     * 值依据（显式算术）：宿主桌面应用运行堆固定 -Xmx2g（composeApp/build.gradle.kts
     * run 任务 jvmArgs）；行偏移索引驻留 8 字节/行（[LogViewSession.indexMemoryBytes]），
     * 索引内存预算取堆的 1/4 = 512MB，余量留给窗口行集、搜索结果集与宿主其余驻留。
     * 512MB ÷ 8 字节/行 = 67,108,864 行（2^26）。超限档在 2g 堆内索引单块分配即 OOM
     * （亿行档索引 8 亿字节 ≈ 800MB，且为不可分割的连续 LongArray）——拒绝门把
     * 崩溃转为与 XLOG_SEGMENTS 判型拒绝同形态的明确失败。
     */
    const val MAX_INDEX_LINES: Int = 67_108_864

    /**
     * 打开文件建立查看会话（两遍流式扫描建行偏移索引：先数行、后按精确行数一次分配填充）。
     * 行数超 [MAX_INDEX_LINES] 开门拒绝（失败形态与 XLOG_SEGMENTS 判型拒绝一致，
     * 均为带可操作指引的 [OpenResult.Failure]，非异常路径、不静默截断）。
     * @throws 无——读取/判型异常均折叠为 [OpenResult.Failure]
     */
    fun open(file: File): OpenResult {
        if (!file.isFile) return OpenResult.Failure("文件不存在或不是普通文件: $file")
        return try {
            when (LogFormatDetector.detect(file)) {
                LogFormatDetector.Format.XLOG_SEGMENTS ->
                    OpenResult.Failure(
                        reason = "加密段结构文件需先解密产出 txt 后再查看: ${file.name}",
                        encrypted = true,
                    )

                else -> when (val outcome = LogViewSession.open(file)) {
                    is SessionOpenOutcome.Session -> OpenResult.Ok(outcome.session)
                    is SessionOpenOutcome.Rejected -> OpenResult.Failure(outcome.reason)
                }
            }
        } catch (e: Throwable) {
            // 零日志门面依赖（fat jar 纪律）：统一走 System.err 单行结构化输出，异常链逐层展开
            ToolsLog.reportFailure("LogViewer", e)
            OpenResult.Failure("打开失败, message: ${e.message}")
        }
    }

    /**
     * 打开结果：会话（含行偏移索引）或失败原因。
     *
     * [Failure.encrypted] = true 仅当 XLOG_SEGMENTS 判型拒绝（加密段结构）——构造点
     * 唯一（[LogViewer.open] 格式路由分支），消费方以此档位渲染「送解密」衔接入口，
     * 不做失败文案字符串匹配（判型单源）。
     */
    sealed class OpenResult {
        data class Ok(val session: LogViewSession) : OpenResult()

        /** @param encrypted true = 加密段结构被拒（先解密产出 txt）；其余失败恒 false */
        data class Failure(val reason: String, val encrypted: Boolean = false) : OpenResult()
    }
}

/**
 * [LogViewSession.open] 的内部装配结果（模块内消费，公共门面是 [LogViewer.OpenResult]）：
 * 会话，或行偏移索引内存超限的拒绝原因——用户可见失败由 [LogViewer.open] 包装为
 * [LogViewer.OpenResult.Failure]（与 XLOG_SEGMENTS 判型拒绝同形态，非异常路径）。
 */
internal sealed interface SessionOpenOutcome {
    class Session(val session: LogViewSession) : SessionOpenOutcome
    class Rejected(val reason: String) : SessionOpenOutcome
}

/**
 * 查看会话：持行偏移索引，按行号窗口 seek 回读；无常驻文件句柄（[readWindow] 每次独立开流）。
 * 经 [LogViewer.open] 构造，不直接实例化。
 */
class LogViewSession private constructor(
    private val file: File,
    // internal：两遍扫描产出与倍增参考实现的等价锚测试需逐元素读取（模块内可见，不入公共门面）
    internal val lineStarts: LongArray,
    private val indexedLength: Long,
) {

    /** 总行数（readLine 语义，见 [LogViewer] 对象 KDoc「行语义」） */
    val totalLines: Int get() = lineStarts.size

    /** 行偏移索引驻留字节（内存上界契约自证口径：8 字节/行） */
    val indexMemoryBytes: Long get() = lineStarts.size.toLong() * 8L

    /**
     * 按行号窗口读取（行号跳转入口：任意合法 startLine 直达，无从头顺序读）。
     *
     * @param startLine 窗口起点（0 基行下标）；须 ≥ 0，越上界（≥ [totalLines]）返回空表
     * @param maxLines 请求行数；> 0，实际返回 min(maxLines, [LogViewer.MAX_WINDOW_LINES],
     *   剩余行数) 行
     * @return 窗口行集（[ViewerLine.number] 为 1 基展示行号，随 startLine 连续递增）
     * @throws IllegalArgumentException startLine < 0 或 maxLines ≤ 0（调用方编程错误）
     * @throws IllegalStateException 文件长度与索引建立时不一致（漂移失效，重新打开）
     */
    fun readWindow(startLine: Int, maxLines: Int): List<ViewerLine> {
        val lines = readWindowTexts(startLine, maxLines)
        return List(lines.size) { i ->
            val raw = lines[i]
            ViewerLine(
                number = startLine + i + 1,
                text = raw,
                // 级别提取单源复用块头解析（不造第二套解析器）；续行/形态不命中为 null
                level = LogStatsAnalyzer.parseHead(raw).level,
            )
        }
    }

    /**
     * 裸窗读取（无级别解析）：参数校验、漂移失效与行集切分与 [readWindow] 同一实现，
     * 唯一差别是不做块头级别解析。为什么：搜索与时间戳导航只消费行原文，级别解析
     * 对它们是纯无效功（块头正则逐行执行，占大文件扫描成本大头）；级别着色所需的
     * 行号跳转窗口读取仍走 [readWindow]（级别提取保持与统计面板同一单源）。
     */
    private fun readWindowTexts(startLine: Int, maxLines: Int): List<String> {
        require(startLine >= 0) { "startLine must be >= 0, got $startLine" }
        require(maxLines > 0) { "maxLines must be > 0, got $maxLines" }
        if (startLine >= totalLines) return emptyList()
        val count = minOf(maxLines, LogViewer.MAX_WINDOW_LINES, totalLines - startLine)
        val currentLength = file.length()
        if (currentLength != indexedLength) {
            throw IllegalStateException(
                "文件在查看期间发生变化（长度 $indexedLength -> $currentLength），索引已失效: ${file.name}",
            )
        }
        val from = lineStarts[startLine]
        val endExclusive = if (startLine + count < totalLines) lineStarts[startLine + count] else indexedLength
        val text = String(readRange(from, endExclusive), Charsets.UTF_8)
        val lines = splitAtTerminators(text)
        check(lines.size == count) { "窗口行数不一致: 期望 $count, 实得 ${lines.size}（文件内容已变化？）" }
        return lines
    }

    /**
     * 级别导航查询：自 [startLine]（0 基，含）起向后找首个级别在 [levels] 中的行。
     *
     * 扫描形态 = 逐窗 seek（步长 [LogViewer.MAX_WINDOW_LINES]），不预建级别偏移表：
     * 预表要求打开时全量文本解码 + 逐行级别解析，把打开路径从单遍字节扫描拖成全文本
     * 解析，且新增按行常驻内存；逐窗扫描把该成本推迟到实际导航点击时按需支付，且
     * 经 [readWindow] 行偏移索引 seek 逐窗读取——级别提取与窗口读取同一单源
     * （[LogStatsAnalyzer.parseHead]），无第二套解析路径。单窗驻留与窗口读取同档
     * 有界；单次查询最坏自 [startLine] 顺序走到文件末行（无命中即如此）。
     *
     * @return 命中行的 0 基行下标；[startLine] 起无命中或 [levels] 为空返回 null
     * @throws IllegalArgumentException startLine < 0（调用方编程错误）
     * @throws IllegalStateException 文件长度与索引建立时不一致（漂移失效，重新打开）
     */
    fun nextLevelLine(startLine: Int, levels: Set<LogStatsAnalyzer.Level>): Int? {
        require(startLine >= 0) { "startLine must be >= 0, got $startLine" }
        if (levels.isEmpty() || startLine >= totalLines) return null
        var from = startLine
        while (from < totalLines) {
            val window = readWindow(from, LogViewer.MAX_WINDOW_LINES)
            for (i in window.indices) {
                if (window[i].level in levels) return from + i
            }
            from += window.size
        }
        return null
    }

    /**
     * 级别导航反向查询：自 [startLine]（0 基，**不含**）起向前找最近的级别命中行。
     * 与 [nextLevelLine] 的含起点成对：next 自起点（含）向后找首个，本函数找起点
     * **之前**（不含）的最近命中——起点行本身不参与，连点回退不重复命中当前行。
     *
     * 扫描形态与 [nextLevelLine] 同款逐窗 seek（复用行偏移索引，无全文重扫、无
     * 预建反向表），窗序自 [startLine] 往回：末窗 `[.., startLine)` 先扫、按
     * [LogViewer.MAX_WINDOW_LINES] 步长逐窗向文件头推进，窗内自尾行向前找；
     * 命中即返回（恒为已扫范围内的最近者）。单窗驻留与窗口读取同档有界；
     * 单次查询最坏自 [startLine] 逆序走到文件首行（无命中即如此）。
     *
     * @param startLine 反向扫描上界（0 基，不含）；大于 [totalLines] 按末行计
     * @return 命中行的 0 基行下标；[startLine] 之前无命中或 [levels] 为空返回 null
     * @throws IllegalArgumentException startLine < 0（调用方编程错误）
     * @throws IllegalStateException 文件长度与索引建立时不一致（漂移失效，重新打开）
     */
    fun prevLevelLine(startLine: Int, levels: Set<LogStatsAnalyzer.Level>): Int? {
        require(startLine >= 0) { "startLine must be >= 0, got $startLine" }
        if (levels.isEmpty()) return null
        var until = minOf(startLine, totalLines)
        while (until > 0) {
            val from = (until - LogViewer.MAX_WINDOW_LINES).coerceAtLeast(0)
            val window = readWindow(from, until - from)
            for (i in window.indices.reversed()) {
                if (window[i].level in levels) return from + i
            }
            until = from
        }
        return null
    }

    /**
     * 文本搜索查询：自 [startLine]（0 基，含）起逐窗 seek 扫描，收集含 [keyword]
     * 的行下标（升序），结果集有界（达 [LogViewer.MAX_SEARCH_RESULTS] 即停止扫描
     * 并置 truncated——大文件高频词匹配可达百万行，匹配集不可无界驻留）。
     *
     * 匹配口径 = 大小写不敏感子串（非正则；日志关键词大小写混杂，敏感匹配易
     * 假性无命中）。扫描形态与 [nextLevelLine] 同款：行偏移索引逐窗 seek（裸窗
     * 读取，只消费行原文不做级别解析），无预建索引、无额外常驻；单次查询最坏自
     * [startLine] 顺序走到文件末行（无命中即如此），达结果集上界则提前止步。
     *
     * @return 匹配行 0 基下标集；[startLine] 起无匹配返回空集（truncated = false）
     * @throws IllegalArgumentException startLine < 0 或 keyword 为空（调用方编程错误）
     * @throws IllegalStateException 文件长度与索引建立时不一致（漂移失效，重新打开）
     */
    fun searchLines(startLine: Int, keyword: String): LineSearchResult {
        require(startLine >= 0) { "startLine must be >= 0, got $startLine" }
        require(keyword.isNotEmpty()) { "keyword must not be empty" }
        if (startLine >= totalLines) return LineSearchResult(emptyList(), truncated = false)
        val matches = ArrayList<Int>()
        var from = startLine
        while (from < totalLines) {
            val window = readWindowTexts(from, LogViewer.MAX_WINDOW_LINES)
            for (i in window.indices) {
                if (window[i].contains(keyword, ignoreCase = true)) {
                    matches.add(from + i)
                    if (matches.size >= LogViewer.MAX_SEARCH_RESULTS) {
                        return LineSearchResult(matches, truncated = true)
                    }
                }
            }
            from += window.size
        }
        return LineSearchResult(matches, truncated = false)
    }

    /**
     * 时间戳导航查询：自 [startLine]（0 基，含）起向后找首个行首时间戳 ≥ [target] 的行。
     * 含起点口径与 [nextLevelLine] 同款：起点行自身参与判定——起点恰为命中行时返回起点。
     *
     * 行首时间戳解析单源 [LogTimestamp.parseLine]（与 LogTimeFilter 时间过滤同一
     * 契约，不造第二套解析口径）；裸格式 `MM-dd` 行无年份，锚定年份取 [target] 的
     * 年份——与 LogTimeFilter 以目标时间年份锚定的既有口径一致。
     *
     * 扫描形态与 [nextLevelLine] 同款逐窗 seek（行偏移索引逐窗裸读——只消费行原文
     * 不做级别解析；不预建时间索引——预表要求打开时全文本解码，把单遍字节扫描的
     * 打开路径拖成全文本解析）；单窗驻留与窗口读取同档有界；单次查询最坏自
     * [startLine] 顺序走到文件末行（无命中即如此）。
     *
     * @return 命中行的 0 基行下标；[startLine] 起无时间行、或全部时间行早于 [target]
     *   （目标超尾）返回 null，不猜落点（与统计面板跨度不可得不猜零值同口径）
     * @throws IllegalArgumentException startLine < 0（调用方编程错误）
     * @throws IllegalStateException 文件长度与索引建立时不一致（漂移失效，重新打开）
     */
    fun nextTimestampLine(startLine: Int, target: LocalDateTime): Int? {
        require(startLine >= 0) { "startLine must be >= 0, got $startLine" }
        if (startLine >= totalLines) return null
        // 裸格式行年份锚定取目标年份（LogTimeFilter 时间过滤同口径，勿另造锚定年份）
        val fallbackYear = target.year.toString()
        var from = startLine
        while (from < totalLines) {
            val window = readWindowTexts(from, LogViewer.MAX_WINDOW_LINES)
            for (i in window.indices) {
                val ts = LogTimestamp.parseLine(window[i], fallbackYear)
                if (ts != null && !ts.isBefore(target)) return from + i
            }
            from += window.size
        }
        return null
    }

    /** 随机访问回读字节区间 [from, until)；EOF 早于区间终点按失效处理 */
    private fun readRange(from: Long, until: Long): ByteArray {
        val size = until - from
        check(size >= 0 && size <= Int.MAX_VALUE.toLong()) { "窗口字节区间越界: $from..$until" }
        val out = ByteArray(size.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            var read = 0
            while (read < out.size) {
                val n = raf.read(out, read, out.size - read)
                if (n < 0) throw IllegalStateException("文件在查看期间被截断（EOF 早于索引区间）: ${file.name}")
                read += n
            }
        }
        return out
    }

    companion object {

        /**
         * 打开会话：两遍扫描精确建行偏移索引。第一遍纯计数（零分配）；第二遍按精确
         * 行数一次分配 LongArray 填充——精确分配后构建期驻留恒为单份索引，无倍增
         * 扩容的整数组拷贝 churn。行数超 [LogViewer.MAX_INDEX_LINES] 开门拒绝。
         */
        internal fun open(file: File): SessionOpenOutcome {
            val length = file.length()
            // countLimit 取上界 + 2：产出条数达上界 + 2 即提前止步判拒（超限文件无需
            // 扫完，同时封死条数计数的 Int 溢出）；上界 + 1 亦为正确实现（止步检查在
            // 缓冲区间隙执行、哨兵条目恒在末数据缓冲区内先入账，各边界判定等价），
            // 取 + 2 为保守档
            val (rawCount, lastStart) = scanLineStarts(file, null, 0, LogViewer.MAX_INDEX_LINES + 2)
            // 末条起始偏移等于文件长度 = 文末终止符的哨兵条目，该位置无内容不构成行
            val lineCount = rawCount - if (lastStart == length) 1 else 0
            if (lineCount > LogViewer.MAX_INDEX_LINES) {
                return SessionOpenOutcome.Rejected(
                    "文件行数超过可查看上限 ${LogViewer.MAX_INDEX_LINES}（行偏移索引内存超 -Xmx2g 档预算），" +
                        "请先用分割工具切割后再查看: ${file.name}",
                )
            }
            val starts = LongArray(lineCount)
            scanLineStarts(file, starts, lineCount, lineCount + 1)
            return SessionOpenOutcome.Session(LogViewSession(file, starts, length))
        }

        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D

        /**
         * 单遍行起始偏移扫描状态机（[open] 的两遍共用同一状态机——第一遍数行、第二遍
         * 填充，产出逐元素一致）。carryCr 状态跨缓冲区保持：缓冲区末尾的 `\r` 是否与
         * 下一缓冲区首字节组成 `\r\n` 由该状态在下一缓冲区解析。
         *
         * @param out null = 第一遍纯计数；非 null = 第二遍填充，按序写入前 [outLimit] 条
         *   （仅末条 == 文件长度的哨兵条目落在 [outLimit] 之外被丢弃，丢弃口径与
         *   [open] 的行数计算一致）
         * @param countLimit 产出条数上界：达到即在下一缓冲区前止步返回（超限文件无需
         *   扫完即可判拒，并封死条数计数的 Int 溢出——止步检查在缓冲区间隙执行，单个
         *   64KB 缓冲区内的越过量以缓冲区字节量为上界，恒远小于 Int 上界）
         * @return (产出条数, 末条起始偏移)；提前止步时末条偏移无消费意义（调用方按条数判拒）
         */
        private fun scanLineStarts(file: File, out: LongArray?, outLimit: Int, countLimit: Int): Pair<Int, Long> {
            FileInputStream(file).use { input ->
                var count = 0
                var lastStart = -1L
                fun addStart(offset: Long) {
                    if (out != null && count < outLimit) out[count] = offset
                    count++
                    lastStart = offset
                }
                addStart(0L)
                val buf = ByteArray(64 * 1024)
                var base = 0L
                var carryCr = false
                while (count < countLimit) {
                    val n = input.read(buf)
                    if (n < 0) break
                    var i = 0
                    while (i < n) {
                        val b = buf[i]
                        val pos = base + i
                        if (carryCr) {
                            // 前一字节 '\r' 已终止上一行；本字节为 '\n' 时属同一终止符余部
                            addStart(pos + if (b == LF) 1L else 0L)
                            carryCr = b == CR
                            i++
                        } else {
                            when (b) {
                                LF -> {
                                    addStart(pos + 1L)
                                    i++
                                }

                                CR -> {
                                    carryCr = true
                                    i++
                                }

                                else -> i++
                            }
                        }
                    }
                    base += n
                }
                return count to lastStart
            }
        }

        /** 文本按行终止符三形态切分（与 [scanLineStarts] 同一套终止符判定） */
        private fun splitAtTerminators(text: String): List<String> {
            val lines = ArrayList<String>()
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n') {
                    lines.add(sb.toString())
                    sb.setLength(0)
                    i++
                } else if (c == '\r') {
                    lines.add(sb.toString())
                    sb.setLength(0)
                    i++
                    if (i < text.length && text[i] == '\n') i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            // 区间终点在行起始偏移上：末行带终止符时 sb 已清空，不追加空串
            if (sb.isNotEmpty()) lines.add(sb.toString())
            return lines
        }
    }
}

/**
 * 窗口单行：1 基展示行号 + 行原文 + 级别。
 *
 * @property number 展示行号（1 基，窗口内连续递增）
 * @property text 行原文（终止符已剥离，内容逐字保真不截断）
 * @property level 级别（复用 [LogStatsAnalyzer.parseHead] 块头解析）；续行/形态不命中为
 *   null——消费方按中性色渲染，不得视为级别缺失的错误
 */
data class ViewerLine(
    val number: Int,
    val text: String,
    val level: LogStatsAnalyzer.Level?,
)

/**
 * 文本搜索结果：匹配行 0 基下标集（升序、有界）+ 截断标记。
 *
 * @property matchedLineIndexes 匹配行 0 基下标，升序；大小 ≤ [LogViewer.MAX_SEARCH_RESULTS]
 * @property truncated true = 匹配数已达 [LogViewer.MAX_SEARCH_RESULTS] 上界即止（文件中
 *   可能还有更多匹配，消费方须明示截断，不得把上界当作匹配总数）
 */
data class LineSearchResult(
    val matchedLineIndexes: List<Int>,
    val truncated: Boolean,
)
