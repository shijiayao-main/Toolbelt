package com.jiaoay.log

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.regex.Pattern

object LogTagFilter {

    /**
     * [previewByTag] 首个命中块预览的行数上限（防无时间行的大块整块入 UI——
     * 预演块总行数见 [TagPreviewResult.Ok.firstMatchedBlockLineCount]）。
     */
    const val PREVIEW_BLOCK_PREVIEW_LINES: Int = 8

    /**
     * 降级检测头部窗口行数：块扫描开始前只读文件头部这么多行判定时间行密度——
     * 窗口内零时间行即判「无标准时间戳文件」（mmap 转储 / tombstone 等 crash
     * 内容形态），激活段落降级模式（空行参与块切分，见 [scanBlocks]）。阈值理由：
     * 带标准时间戳的日志首个时间行必在前几行，500 行零命中是无时间戳文件的可靠
     * 信号。窗口行经内存前缀队列复读（每行只从流读一次，不 seek 不重读）。
     */
    const val HEADER_WINDOW_LINES: Int = 500

    /**
     * 段落降级激活时的工具诚实性提示（载体 = [ToolsResult.Success.warning]）：
     * 无时间戳文件的块口径与时间戳文件不同（空行分隔 vs 时间行分隔），消费方
     * （CLI echo / agent 工具 summary）须原样呈现——静默吞掉会让调用方误读产物。
     */
    const val PARAGRAPH_FALLBACK_WARNING: String =
        "文件无标准时间戳行首，已降级为段落切分——tag 匹配以空行分隔块为准"

    /** Windows 保留设备名（不分大小写，含带扩展名形态） */
    private val WINDOWS_RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /**
     * 清洗用户输入的 Tag 名称用于输出文件命名：
     * - 替换文件系统非法字符（Windows `< > : " | ? *` 与路径分隔符，POSIX 的 `/`）为 `_`
     *   （防创建失败，`:` 亦防路径穿越语义）
     * - 命中 Windows 保留名时追加 `_` 后缀
     * - 去除尾部空白与点号（Windows 忽略尾部点号，易产生用户无法定位的文件名）
     *
     * public：宿主 feature 层预判产物名（覆盖确认卡按精确产物名检测/清理——
     * 库层 filterByTag 用同一函数命名，两处必须逐字一致）。
     */
    fun sanitizeTagName(raw: String): String {
        var name = raw.replace(Regex("""[<>:"|?*/\\]"""), "_")
        if (name.uppercase() in WINDOWS_RESERVED_NAMES) name += "_"
        // 交替去除尾部「空白与点号」（Windows 忽略尾部点号与空格，混排时须循环清除）
        return name.trim { it.isWhitespace() || it == '.' }
    }

    fun filterByTag(file: File, tagRegex: String, tagName: String): ToolsResult {

        val fileName = file.nameWithoutExtension
        val outputFile = File(file.parent ?: ".", "$fileName.filter-tag_${sanitizeTagName(tagName)}.txt")   // parent null 兜底对齐 LogSplitter

        if (outputFile.exists() && outputFile.isFile) {
            return ToolsResult.Failure("$outputFile 已存在, 不需要再次进行处理")
        }

        try {
            val summary = filterByTagBlockWithSummary(inputFile = file, outputFile, tagRegex)
            val warning = if (summary.fallbackMode) PARAGRAPH_FALLBACK_WARNING else null
            return ToolsResult.Success(outputFile, warning)
        } catch (e: Throwable) {
            // printStackTrace 直写 stderr 无结构（桌面 App 内文件日志收不到堆栈，
            // CLI 模式混流不可 grep）。本库零日志门面依赖（fat jar 纪律），统一走 System.err 单行结构化输出，
            // 桌面宿主经 stderr 重定向/logback 皆可捕获；异常链逐层展开。
            ToolsLog.reportFailure("LogTagFilter", e)
            return ToolsResult.Failure(reason = "执行失败, message: ${e.message}")
        }
    }

    fun filterByTagBlock(
        inputFile: File,
        outputFile: File,
        tagRegex: String
    ) {
        filterByTagBlockWithSummary(inputFile, outputFile, tagRegex)
    }

    /**
     * [filterByTagBlock] 的带扫描摘要形态：公开签名保持 Unit 不变（既有调用方
     * 不感知），[filterByTag] 经 [BlockScanSummary.fallbackMode] 折叠降级提示。
     */
    internal fun filterByTagBlockWithSummary(
        inputFile: File,
        outputFile: File,
        tagRegex: String
    ): BlockScanSummary {
        val tagPattern = Pattern.compile(tagRegex)

        BufferedReader(InputStreamReader(FileInputStream(inputFile), Charsets.UTF_8), 1024 * 1024).use { reader ->
            BufferedWriter(OutputStreamWriter(FileOutputStream(outputFile), Charsets.UTF_8), 1024 * 1024).use { writer ->
                return scanBlocks(reader, tagPattern, maxLines = null, onMatchedBlock = { block -> writeBlock(writer, block) })
            }
        }
    }

    /**
     * 正则命中预演：只扫描文件前 [maxLines] 行（**非全量**），统计完整块中的命中数并
     * 返回首个命中块预览。供宿主 UI 在用户改完正则后即时反馈「抓没抓对」，免去跑
     * 全文件（大文件一轮几十秒）的试错成本。
     *
     * 尾块截断语义：若第 [maxLines] 行恰好落在某块中间（文件还有后续行），该尾块
     * **不参与命中统计并计入 [TagPreviewResult.Ok.skippedTailBlocks]**（=1）——截断
     * 处无法判定该块完整内容是否命中，计入会产生假阳/假阴；文件实际不足 [maxLines]
     * 行时无截断，末块按完整块统计（与全量跑一致）。
     *
     * 块识别与匹配规则与 [filterByTagBlock] 单源同核（[scanBlocks]），两入口口径不漂移。
     *
     * @throws 无——正则语法错误与读取异常均折叠为 [TagPreviewResult.Failure]
     */
    fun previewByTag(file: File, tagRegex: String, maxLines: Int): TagPreviewResult {
        return try {
            val tagPattern = Pattern.compile(tagRegex)
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 1024 * 1024).use { reader ->
                val summary = scanBlocks(reader, tagPattern, maxLines, onMatchedBlock = { })
                val first = summary.firstMatchedBlock
                TagPreviewResult.Ok(
                    scannedLines = summary.scannedLines,
                    truncated = summary.truncated,
                    totalBlocks = summary.totalBlocks,
                    matchedBlocks = summary.matchedBlocks,
                    skippedTailBlocks = summary.skippedTailBlocks,
                    fallbackMode = summary.fallbackMode,
                    firstMatchedBlockPreview = first.orEmpty().take(PREVIEW_BLOCK_PREVIEW_LINES),
                    firstMatchedBlockLineCount = first?.size ?: 0,
                )
            }
        } catch (e: Throwable) {
            ToolsLog.reportFailure("LogTagFilter", e)
            TagPreviewResult.Failure("预演失败, message: ${e.message}")
        }
    }

    /** 块扫描统计（[previewByTag] 的载荷；[filterByTagBlock] 只消费回调不读统计——统计面消费 [totalBlocks] 等） */
    internal class BlockScanSummary(
        val scannedLines: Int,
        /** true = 读满 [maxLines] 行且文件还有后续行（截断态） */
        val truncated: Boolean,
        /** 完整块数（含未命中块；截断丢弃的尾块不计入） */
        val totalBlocks: Int,
        val matchedBlocks: Int,
        /** 截断丢弃的尾块数（恒 0 或 1——截断点至多落在一个块内） */
        val skippedTailBlocks: Int,
        /** 首个命中块的完整行集（无命中为 null） */
        val firstMatchedBlock: List<String>?,
        /** true = 头部窗口（[HEADER_WINDOW_LINES] 行）零时间行，块切分已降级为「时间行或空行」触发 */
        val fallbackMode: Boolean,
    )

    /**
     * 块扫描共享内核：[filterByTagBlock]（全量）、[previewByTag]（前 N 行）与
     * [LogStatsAnalyzer]（统计面，经 [onCompleteBlock] 消费全块）的唯一块识别/匹配
     * 实现，规则四则——①块切分由时间行（[isTimeLine]）触发；②**降级模式**：头部窗口
     * （[HEADER_WINDOW_LINES] 行）零时间行时激活，空行兼作切分触发（保真归块尾——
     * 命中块顺序写出自带空行分隔不粘连；时间行触发**不替换**、降级期间照常切分）；
     * ③空行在块非空时原样入块（数据保真）、块空时丢弃；④块内任一行 `pattern.find()`
     * 命中即整块命中。无时间戳文件（mmap 转储/tombstone 等 crash 内容）不降级时
     * 整份内容攒成单块，tag 命中任意一行 = 全文件倾倒（all-or-nothing 过滤失效）——
     * 降级检测即在扫块开始前消解该形态。新增块规则只能改这里，各入口同步生效。
     *
     * 降级检测的窗口预读经内存前缀队列复读：窗口行只从流读一次（无 mark/reset
     * 回退、无二次开流），扫描循环先耗尽队列再继续读流——大文件无双读。
     *
     * @param tagPattern 块内命中判定的正则；null = 不做命中判定（统计面——
     *   [BlockScanSummary.matchedBlocks] 恒 0，[onMatchedBlock] 不触发）
     * @param maxLines 最多扫描的行数；null = 读到 EOF（全量）。读满后探读一行判定
     *   截断态（[BlockScanSummary.truncated]）。降级检测窗口恒为全文件头部
     *   [HEADER_WINDOW_LINES] 行，不受 maxLines 收窄——预演与全量跑对同一文件
     *   判定同一种模式（两入口口径不漂移）
     * @param onMatchedBlock 完整命中块的回调（块行集副本，回调持有不防外部修改）
     * @param onCompleteBlock 每个完整块的回调（含未命中块；参数 = 块行集副本 + 块首行
     *   的 1 基全文件行号）。与 [onMatchedBlock] 独立——供不关心正则命中的统计/聚类面
     *   消费，缺省不触发
     */
    internal fun scanBlocks(
        reader: BufferedReader,
        tagPattern: Pattern?,
        maxLines: Int?,
        onMatchedBlock: (List<String>) -> Unit,
        onCompleteBlock: ((block: List<String>, headLineNumber: Int) -> Unit)? = null,
    ): BlockScanSummary {
        var scanned = 0
        var truncated = false
        var totalBlocks = 0
        var matchedBlocks = 0
        var firstMatchedBlock: List<String>? = null
        val block = ArrayList<String>()
        var blockMatched = false
        var blockHeadLineNumber = 0

        // 降级检测头部窗口预读：窗口内零时间行 → 段落降级；见到首个时间行即止
        // （带时间戳文件的窗口停在首行附近，预读开销可忽略）
        val headerLines = ArrayList<String>()
        var fallbackMode = true
        while (headerLines.size < HEADER_WINDOW_LINES) {
            val line = reader.readLine() ?: break
            headerLines.add(line)
            if (line.isTimeLine()) {
                fallbackMode = false
                break
            }
        }
        var headerIndex = 0
        fun nextLine(): String? =
            if (headerIndex < headerLines.size) headerLines[headerIndex++] else reader.readLine()

        // 块完结处理：计完整块数；全块回调先行（统计面）；命中块计数、留档首块并回调
        fun finalizeBlock() {
            if (block.isEmpty()) return
            totalBlocks++
            onCompleteBlock?.invoke(ArrayList(block), blockHeadLineNumber)
            if (blockMatched) {
                matchedBlocks++
                val snapshot = ArrayList(block)
                if (firstMatchedBlock == null) firstMatchedBlock = snapshot
                onMatchedBlock(snapshot)
            }
            block.clear()
            blockMatched = false
        }

        while (true) {
            if (maxLines != null && scanned >= maxLines) {
                // 队列尚有预读行 = 文件必长于 maxLines；队列耗尽才探读流（原截断判定路径）
                truncated = headerIndex < headerLines.size || reader.readLine() != null
                break
            }
            val current = nextLine() ?: break
            scanned++
            if (current.isEmpty()) {
                // 空行原样入块（数据保真）；降级模式下兼作切分触发（保真归块尾）
                if (block.isNotEmpty()) {
                    block.add(current)
                    if (fallbackMode) finalizeBlock()
                }
                continue
            }
            if (current.isTimeLine()) {
                // 👉 新块开始 → 先完结旧块
                finalizeBlock()
            }

            // 👉 收集当前块（块空时本行即块首行——记 1 基全文件行号供行号引用面消费）
            if (block.isEmpty()) {
                blockHeadLineNumber = scanned
            }
            block.add(current)

            // 👉 判断是否匹配tag（null = 统计面，不做命中判定）
            if (tagPattern != null && !blockMatched && tagPattern.matcher(current).find()) {
                blockMatched = true
            }
        }

        var skippedTailBlocks = 0
        if (truncated && block.isNotEmpty()) {
            // 截断落在块中间：尾块完整性不可判定，丢弃不计入完整块（计入跳过数）
            skippedTailBlocks = 1
        } else {
            // 👉 最后一块（EOF 完整态；截断但尾块为空时 finalize 是 no-op）
            finalizeBlock()
        }

        return BlockScanSummary(
            scannedLines = scanned,
            truncated = truncated,
            totalBlocks = totalBlocks,
            matchedBlocks = matchedBlocks,
            skippedTailBlocks = skippedTailBlocks,
            firstMatchedBlock = firstMatchedBlock,
            fallbackMode = fallbackMode,
        )
    }

    private fun writeBlock(writer: BufferedWriter, block: List<String>) {
        for (l in block) {
            writer.write(l)
            writer.newLine()
        }
    }
}

/**
 * 正则命中预演结果（[LogTagFilter.previewByTag] 专用；全量跑仍走 [ToolsResult]）。
 *
 * [Ok] 的所有计数**仅基于文件前 maxLines 行**（非全量），消费方 UI 必须把该语义
 * 呈现给用户，防把预演计数误读为全文件结果。
 */
sealed class TagPreviewResult {

    /**
     * @property scannedLines 实际扫描行数（≤ 请求的 maxLines）
     * @property truncated true = 文件长于扫描上限（[Ok] 各计数非全量）
     * @property totalBlocks 扫描范围内识别出的完整块数（含未命中块）
     * @property matchedBlocks 完整块中的命中数
     * @property skippedTailBlocks 截断丢弃的尾块数（0 或 1）
     * @property fallbackMode true = 文件无标准时间戳行首，已降级为段落切分（空行分块）——
     *   块计数口径为段落，宿主 UI 须如实呈现（防用户误读块数含义）
     * @property firstMatchedBlockPreview 首个命中块的前 [LogTagFilter.PREVIEW_BLOCK_PREVIEW_LINES] 行（无命中为空表）
     * @property firstMatchedBlockLineCount 首个命中块的总行数（预览被截断时 > 预览行数）
     */
    data class Ok(
        val scannedLines: Int,
        val truncated: Boolean,
        val totalBlocks: Int,
        val matchedBlocks: Int,
        val skippedTailBlocks: Int,
        val fallbackMode: Boolean,
        val firstMatchedBlockPreview: List<String>,
        val firstMatchedBlockLineCount: Int,
    ) : TagPreviewResult()

    data class Failure(val reason: String) : TagPreviewResult()
}