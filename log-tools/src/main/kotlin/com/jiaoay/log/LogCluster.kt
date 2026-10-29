package com.jiaoay.log

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 日志模板聚类（Drain 语义的独立 Kotlin 实现——按 ICWS'17 论文的算法思想重写，不与
 * 任何参考实现共享代码；聚类核心 [DrainTree] 纯函数零 IO，IO 只在 [analyze] 单遍流式读，
 * 行不驻留内存）。
 *
 * 用户价值：同一异常刷几百遍的日志逐行看不可能——「每类模板各刷多少次 / 首末在几点」
 * 一眼定优先级；产物的行号列表是块首行 1 基全文件行号，供证据引用跳转链消费。
 *
 * 算法（论文语义四步）：
 * 1. 预处理消息体：行首时间戳经 [LogTimestamp.maskLineTimestamp] 抹去（时间恒为变量）；
 *    连续数字段折叠为 `<*>`（logcat 头部的 pid/序号/相机号等可变 ID 出现在 token
 *    前缀里，不折叠则同型异常按 ID 炸开成碎簇——这是论文 preprocess 变量掩蔽思想对本
 *    仓日志形态的适配）；
 * 2. 按 token 数分组（不同长度的消息不进同一簇）；
 * 3. 前缀 fixed-depth 树路由（[DrainTree.TREE_DEPTH]-1 个 token 作路径；树只做候选剪枝，
 *    不参与归并判定）；
 * 4. 叶子上与既有模板逐位置比对：相同或模板位为 `<*>` 记匹配，相似度 = 匹配数 / token 数；
 *    最高相似度 ≥ 阈值则归并并把差异位折叠为 `<*>`，否则立新簇。
 *
 * 块识别复用 [LogTagFilter.scanBlocks] 共享内核（与统计/Tag 过滤同一套块规则，不另造
 * 第二套解析器）；聚类单元 = 块首行（同一异常每次刷屏各成一个块）。
 *
 * ## 相似度阈值校准记录（改 [DEFAULT_SIMILARITY_THRESHOLD] 前必读）
 * 默认值 0.4 是 ICWS'17 论文值，**尚未经真实日志实测校准**。消费方在真实日志上观察到
 * 过归并（不同异常吸进一簇）或过分裂（同一异常拆成多簇）时，先把实测观察与调整结论
 * 登记进本节，再改默认值。
 *
 * ## 硬上界（内存与产物双有界——跳过/截断全部显式计数，无静默丢弃）
 * - 簇数恒 ≤ [MAX_TEMPLATES]：模板容量尽后新形态的块不再立簇，计入
 *   [ClusterResult.Ok.overflowBlocks]（消费方须呈现该差值）；
 * - 单簇行号引用恒 ≤ [MAX_LINE_REFERENCES]：超限置 [TemplateCluster.lineNumbersTruncated]，
 *   [TemplateCluster.count] 仍是全量计数（计数不失真，只有引用列表截断）；
 * - 消息体长于 [MAX_TEMPLATE_LINE_CHARS] 的块不入聚类，计入
 *   [ClusterResult.Ok.oversizedLineBlocks]（防单条超长行驻留内存失控）；
 * - 会计恒等式（消费方呈现时不得丢项）：
 *   candidateBlocks = templateBlocks + overflowBlocks + skippedEmptyHeadBlocks + oversizedLineBlocks。
 */
object LogCluster {

    /** Drain 叶子归并相似度阈值默认值（ICWS'17 论文值）——改前先见类 KDoc「阈值校准记录」 */
    const val DEFAULT_SIMILARITY_THRESHOLD = 0.4

    /** 模板簇数硬上界——容量尽后新形态块计入 overflowBlocks，不再立簇 */
    const val MAX_TEMPLATES = 200

    /** 单簇行号引用硬上界——超限置 lineNumbersTruncated，count 仍全量 */
    const val MAX_LINE_REFERENCES = 500

    /** 单行消息体长度硬上界（字符，时间戳抹除后）——超长行不入聚类、显式计数 */
    const val MAX_TEMPLATE_LINE_CHARS = 4000

    /** 预处理变量掩蔽：连续数字段折叠为通配符（类 KDoc 算法第 1 步——头部 pid/序号防炸簇） */
    private val DIGIT_RUN = Regex("\\d+")

    /**
     * 全文件单遍聚类（流式读；常驻状态只有模板簇集，尺寸受三项硬上界约束）。
     *
     * @param file 纯文本日志文件；不存在或非普通文件返回 [ClusterResult.Failure]（显式失败）
     * @param levels 级别过滤集：非 null 时只聚类块首行级别命中集合的块（级别解析不出的块
     *   跳过，差值 = totalBlocks − candidateBlocks）；null = 不过滤、全部块入聚类
     * @param similarityThreshold 叶子归并阈值，默认 [DEFAULT_SIMILARITY_THRESHOLD]；
     *   值域 (0, 1] 之外返回 [ClusterResult.Failure]（显式失败，不静默钳制）
     * @throws 无——读取异常均折叠为 [ClusterResult.Failure]
     */
    fun analyze(
        file: File,
        levels: Set<LogStatsAnalyzer.Level>? = null,
        similarityThreshold: Double = DEFAULT_SIMILARITY_THRESHOLD,
    ): ClusterResult {
        if (similarityThreshold <= 0.0 || similarityThreshold > 1.0) {
            return ClusterResult.Failure("相似度阈值非法: $similarityThreshold（值域 (0, 1]）")
        }
        if (!file.isFile) {
            return ClusterResult.Failure("聚类输入不是可读的普通文件: ${file.absolutePath}")
        }
        return try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 1024 * 1024).use { reader ->
                val fallbackYear = LocalDate.now().year.toString()
                val tree = DrainTree(similarityThreshold, MAX_TEMPLATES, MAX_LINE_REFERENCES)
                var skippedByLevel = 0
                var skippedEmptyHead = 0
                var oversized = 0
                val summary = LogTagFilter.scanBlocks(
                    reader = reader,
                    tagPattern = null,
                    maxLines = null,
                    onMatchedBlock = {},
                ) { block, headLineNumber ->
                    val head = block.first()
                    if (levels != null && LogStatsAnalyzer.parseHead(head).level !in levels) {
                        skippedByLevel++
                        return@scanBlocks
                    }
                    val payload =
                        DIGIT_RUN.replace(LogTimestamp.maskLineTimestamp(head, ""), DrainTree.WILDCARD).trim()
                    if (payload.isEmpty()) {
                        skippedEmptyHead++
                        return@scanBlocks
                    }
                    if (payload.length > MAX_TEMPLATE_LINE_CHARS) {
                        oversized++
                        return@scanBlocks
                    }
                    tree.add(payload, headLineNumber, LogTimestamp.parseLine(head, fallbackYear))
                }
                val templates = tree.templates()
                ClusterResult.Ok(
                    scannedLines = summary.scannedLines,
                    totalBlocks = summary.totalBlocks,
                    candidateBlocks = summary.totalBlocks - skippedByLevel,
                    templateBlocks = templates.sumOf { it.count },
                    overflowBlocks = tree.overflowCount,
                    skippedEmptyHeadBlocks = skippedEmptyHead,
                    oversizedLineBlocks = oversized,
                    templates = templates,
                )
            }
        } catch (e: Throwable) {
            // 零日志门面依赖（fat jar 纪律）：统一走 System.err 单行结构化输出，异常链逐层展开
            ToolsLog.reportFailure("LogCluster", e)
            ClusterResult.Failure("聚类失败, message: ${e.message}")
        }
    }
}

/**
 * Drain 聚类内核（纯函数零 IO）：token 化 + token 数分组 + fixed-depth 前缀树路由 +
 * 叶子相似度归并。树路径取前 [TREE_DEPTH]-1 个 token 的字面值——可变数字段已在入口
 * 折叠成 `<*>`（见 [LogCluster] 类 KDoc），故路径天然按模板骨架分流；归并判据只在叶子
 * 相似度，树只做候选剪枝。
 */
internal class DrainTree(
    private val similarityThreshold: Double,
    private val maxTemplates: Int,
    private val maxLineReferences: Int,
) {

    internal class Group(val tokens: MutableList<String>) {
        internal var count = 0
        internal val lineNumbers = ArrayList<Int>()
        internal var lineNumbersTruncated = false
        internal var firstTimestamp: LocalDateTime? = null
        internal var lastTimestamp: LocalDateTime? = null
    }

    /** token 数 → 该长度组的前缀树入口（首层分组：不同长度的消息永不比对） */
    private val roots = HashMap<Int, Node>()

    /** 全簇登记面（与树结构无关的平面索引——模板收割与容量判定用） */
    private val allGroups = ArrayList<Group>()

    private class Node {
        internal val children = HashMap<String, Node>()
        internal val groups = ArrayList<Group>()
    }

    /** 模板容量尽后被丢弃（不入簇）的块数——消费方显式呈现，不得静默吞掉 */
    internal var overflowCount = 0
        private set

    /**
     * 消息体入树（时间戳须已抹除）。归并路径：最高相似度 ≥ 阈值 → 折叠差异位并入该簇；
     * 否则容量内立新簇、容量尽计 overflow。
     *
     * @param headLineNumber 该块首行的 1 基全文件行号（超 [maxLineReferences] 截断并置标记）
     * @param timestamp 块首行时间戳；null = 无可识别时间戳（首末时间戳缺席，计数不受影响）
     */
    internal fun add(payload: String, headLineNumber: Int, timestamp: LocalDateTime?) {
        val tokens = tokenize(payload)
        var node = roots.getOrPut(tokens.size) { Node() }
        // 路径只走前 TREE_DEPTH-1 个 token（论文 fixed-depth 形态）；单 token 消息直接落根叶子
        val pathLength = minOf(TREE_DEPTH - 1, tokens.size - 1)
        for (i in 0 until pathLength) {
            node = node.children.getOrPut(tokens[i]) { Node() }
        }

        var best: Group? = null
        var bestSimilarity = 0.0
        for (group in node.groups) {
            val similarity = similarityOf(group.tokens, tokens)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                best = group
            }
        }
        val target = if (best != null && bestSimilarity >= similarityThreshold) {
            best
        } else {
            if (allGroups.size >= maxTemplates) {
                overflowCount++
                return
            }
            Group(tokens.toMutableList()).also { node.groups.add(it); allGroups.add(it) }
        }

        // 差异位折叠 <*>（模板骨架与消息逐位置不等即视为变量位）
        for (i in tokens.indices) {
            if (target.tokens[i] != tokens[i]) target.tokens[i] = WILDCARD
        }
        target.count++
        if (target.lineNumbers.size < maxLineReferences) {
            target.lineNumbers.add(headLineNumber)
        } else {
            target.lineNumbersTruncated = true
        }
        // 非单调日志按值取 min/max（后出现的更早时间须修正 first）
        val curFirst = target.firstTimestamp
        val curLast = target.lastTimestamp
        if (timestamp != null) {
            if (curFirst == null || timestamp.isBefore(curFirst)) target.firstTimestamp = timestamp
            if (curLast == null || timestamp.isAfter(curLast)) target.lastTimestamp = timestamp
        }
    }

    /** 模板收割：簇数降序、并列按模板字典序（确定性排序——消费方渲染免二次排序） */
    internal fun templates(): List<TemplateCluster> = allGroups
        .map { group ->
            TemplateCluster(
                template = group.tokens.joinToString(" "),
                count = group.count,
                lineNumbers = group.lineNumbers.toList(),
                lineNumbersTruncated = group.lineNumbersTruncated,
                firstTimestamp = group.firstTimestamp,
                lastTimestamp = group.lastTimestamp,
            )
        }
        .sortedWith(compareByDescending<TemplateCluster> { it.count }.thenBy { it.template })

    /**
     * 逐位置相似度：模板位与消息位相等、或模板位已折叠为 [WILDCARD] 记匹配；
     * 相似度 = 匹配数 / token 数。双方同在 token 数分组内，长度恒相等。
     */
    private fun similarityOf(template: List<String>, tokens: List<String>): Double {
        var matched = 0
        for (i in tokens.indices) {
            if (template[i] == WILDCARD || template[i] == tokens[i]) matched++
        }
        return matched.toDouble() / tokens.size
    }

    private fun tokenize(payload: String): List<String> = payload.split(WHITESPACE).filter { it.isNotEmpty() }

    companion object {
        /** 前缀树深度（论文默认形态：前 3 个 token 作路径） */
        internal const val TREE_DEPTH = 4

        internal const val WILDCARD = "<*>"

        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * 聚类结果（[LogCluster.analyze] 专用）。
 *
 * [Ok] 的会计恒等式：candidateBlocks = templateBlocks + overflowBlocks +
 * skippedEmptyHeadBlocks + oversizedLineBlocks（入聚类的块要么归并进某簇、要么进了
 * 某个显式跳过/溢出计数——无静默丢弃）。
 */
sealed class ClusterResult {

    /**
     * @property scannedLines 扫描的总行数
     * @property totalBlocks 完整块总数
     * @property candidateBlocks 通过级别过滤、进入聚类管线的块数（无过滤时恒等于 totalBlocks）
     * @property templateBlocks 已归并进模板簇的块数（恒等于 templates 各项 count 之和）
     * @property overflowBlocks 模板容量尽后被丢弃的块数（簇数已到 [LogCluster.MAX_TEMPLATES]）
     * @property skippedEmptyHeadBlocks 块首行抹除时间戳后为空（无可聚类消息体）的块数
     * @property oversizedLineBlocks 块首行消息体超 [LogCluster.MAX_TEMPLATE_LINE_CHARS] 被跳过的块数
     * @property templates 模板簇集（簇数降序、并列按模板字典序；大小恒 ≤ [LogCluster.MAX_TEMPLATES]）
     */
    data class Ok(
        val scannedLines: Int,
        val totalBlocks: Int,
        val candidateBlocks: Int,
        val templateBlocks: Int,
        val overflowBlocks: Int,
        val skippedEmptyHeadBlocks: Int,
        val oversizedLineBlocks: Int,
        val templates: List<TemplateCluster>,
    ) : ClusterResult()

    data class Failure(val reason: String) : ClusterResult()
}

/**
 * 单个模板簇（[ClusterResult.Ok.templates] 的元素）。
 *
 * @property template 模板骨架串（token 以单空格连接，变量位为 `<*>`——时间戳与连续数字段
 *   在预处理即折叠，归并时的非数字差异位再折叠）
 * @property count 该簇累计块数（全量计数，不因行号引用截断而失真）
 * @property lineNumbers 块首行 1 基全文件行号列表（供证据引用跳转链），大小恒 ≤
 *   [LogCluster.MAX_LINE_REFERENCES]
 * @property lineNumbersTruncated true = 行号引用超上限被截断（后续命中只计 count 不再记引用）
 * @property firstTimestamp 簇内最早时间戳（按值取 min）；簇内无可识别时间戳行为 null
 * @property lastTimestamp 簇内最晚时间戳（按值取 max）；簇内无可识别时间戳行为 null
 */
data class TemplateCluster(
    val template: String,
    val count: Int,
    val lineNumbers: List<Int>,
    val lineNumbersTruncated: Boolean,
    val firstTimestamp: LocalDateTime?,
    val lastTimestamp: LocalDateTime?,
)
