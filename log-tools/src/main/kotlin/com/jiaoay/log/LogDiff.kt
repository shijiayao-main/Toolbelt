package com.jiaoay.log

import com.jiaoay.xlog.parser.MagicStartMark
import com.jiaoay.xlog.postprocess.SegmentContext
import com.jiaoay.xlog.postprocess.TimestampNormalizer
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 两份日志的行级 diff 引擎（CLI `--type diff` 入口消费；结构化结果供宿主 UI 直接消费）。
 *
 * ## 行恒等契约
 * - 默认：行原文逐字符相等才视为同一行（readLine 语义：`\n` 与 `\r\n` 均为行终止，
 *   文末无换行的尾行计一行）。
 * - normalizeTimestamps（「时间戳归一化对齐」）：比对键先经 [TimestampNormalizer] 把
 *   行首 `[时区]-epoch毫秒` 归一化为可读格式（行首时间戳形态契约单源在 [LogTimestamp]），
 *   再把行首可读时间戳整体替换为占位符——此后**仅行首时间戳不同的行视为同一行**
 *   （两次复现/两个版本直接比行为不比时钟）。hunk 与产物始终显示行原文；上下文行取
 *   old 侧原文（归一化只参与比对，不改写产物）。
 *
 * ## 内存上界（大文件不整文件驻留）
 * - 两份文件各两遍流式扫描（数行 + 行哈希），驻留只有**行哈希**：
 *   8 字节/行（64 位 FNV-1a）× 两文件行数之和，与行长、文件字节总量无关；
 * - 对齐追踪快照 ≤ Σ(d+1) 个 int（d ≤ [MAX_ALIGN_EDITS]），上限约 34 MB；
 * - 行内容按 hunk 窗口第三遍流式回读，驻留 = 差异窗口行集，规模 O(编辑距离 D)；
 *   全异极端输入下 D 与文件同阶——此时产物本身与文件同阶，驻留与产物同阶。
 * 行恒等以 64 位哈希判定：千万行量级碰撞概率仍在 1e-6 量级（生日界），
 * 极端碰撞只造成局部错位，不崩溃不丢行。
 *
 * ## 超限退化契约
 * 编辑距离超过 [MAX_ALIGN_EDITS] 时停止逐行对齐（快照内存封顶），中间区域退化为
 * 单一整体替换 hunk（保留已探明的公共前缀），[DiffResult.Ok.degraded] 置位——
 * 调用方必须把该标记呈现给用户。
 */
object LogDiff {

    /** unified 风格 hunk 的上下文行数；相邻编辑间隔 < 2×本值 时合并进同一 hunk */
    const val CONTEXT_LINES: Int = 3

    /** 逐行对齐的编辑距离上限（超出即整体替换退化；快照内存上界约 34 MB） */
    const val MAX_ALIGN_EDITS: Int = 4096

    /** hunk 单行的归属：上下文（两份共有）/ 删除（仅 old）/ 新增（仅 new） */
    enum class LineKind { CONTEXT, REMOVED, ADDED }

    /** hunk 窗口内的单行内容（kind + 行原文；unified 前缀在渲染时按 kind 加） */
    data class HunkLine(val kind: LineKind, val text: String)

    /**
     * 单个 hunk：unified 风格窗口。
     * @property oldStart old 侧窗口起点（0 基行下标，含上下文行）
     * @property oldLines old 侧窗口行数（含上下文行）
     * @property newStart new 侧窗口起点（0 基行下标，含上下文行）
     * @property newLines new 侧窗口行数（含上下文行）
     * @property lines 窗口内逐行内容（上下文/删除/新增 按文件顺序交错）
     */
    data class Hunk(
        val oldStart: Int,
        val oldLines: Int,
        val newStart: Int,
        val newLines: Int,
        val lines: List<HunkLine>,
    )

    /** diff 结果：结构化 hunk 序列（UI/导出消费）或失败原因 */
    sealed class DiffResult {

        /**
         * @property hunks hunk 序列（按行序）；空表 = 两份文件在当前行恒等契约下全等
         * @property oldLineCount old 文件行数（readLine 语义）
         * @property newLineCount new 文件行数
         * @property degraded true = 编辑距离超 [MAX_ALIGN_EDITS]，中间区域整体替换未逐行对齐
         */
        data class Ok(
            val hunks: List<Hunk>,
            val oldLineCount: Int,
            val newLineCount: Int,
            val degraded: Boolean,
        ) : DiffResult()

        data class Failure(val reason: String) : DiffResult()
    }

    // ── 对齐内核（行哈希上的操作游程） ──

    internal enum class OpKind { MATCH, DELETE, INSERT }

    /**
     * 单段操作游程：MATCH 从 (aStart, bStart) 起两消费 count 行；DELETE 只消费 a 侧；
     * INSERT 只消费 b 侧。游程序列在两坐标系上均连续且恰好覆盖两文件全部行。
     */
    internal data class Op(val kind: OpKind, val aStart: Int, val bStart: Int, val count: Int) {
        val aExtent: Int get() = if (kind == OpKind.INSERT) 0 else count
        val bExtent: Int get() = if (kind == OpKind.DELETE) 0 else count
    }

    internal class AlignedOps(val ops: List<Op>, val degraded: Boolean)

    /**
     * 行级 diff 主入口。
     * @param oldFile 基准文件
     * @param newFile 对比文件
     * @param normalizeTimestamps true = 「时间戳归一化对齐」（见对象 KDoc「行恒等契约」）
     */
    fun diff(oldFile: File, newFile: File, normalizeTimestamps: Boolean = false): DiffResult {
        return try {
            val oldCount = countLines(oldFile)
            val newCount = countLines(newFile)
            val oldHashes = LongArray(oldCount)
            val newHashes = LongArray(newCount)
            hashLines(oldFile, oldHashes, normalizeTimestamps)
            hashLines(newFile, newHashes, normalizeTimestamps)
            val aligned = alignOps(oldHashes, newHashes)
            val hunks = readHunks(oldFile, newFile, aligned, oldCount, newCount)
            DiffResult.Ok(hunks, oldCount, newCount, degraded = aligned.degraded)
        } catch (e: Throwable) {
            ToolsLog.reportFailure("LogDiff", e)
            DiffResult.Failure("执行失败, message: ${e.message}")
        }
    }

    /**
     * 把 [DiffResult.Ok] 渲染为 unified diff 文本写入 outputFile。
     * 产物覆盖旧文件（对比常需改一份再比一次地反复重跑——对齐解密产物覆盖语义，
     * 区别于 filter/split 的存在性拒绝）。无差异时只写两行文件头。
     */
    fun writeUnified(
        result: DiffResult.Ok,
        oldLabel: String,
        newLabel: String,
        outputFile: File,
    ): ToolsResult {
        return try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(outputFile), Charsets.UTF_8), 1024 * 1024).use { writer ->
                writer.write("--- $oldLabel")
                writer.newLine()
                writer.write("+++ $newLabel")
                writer.newLine()
                for (hunk in result.hunks) {
                    writer.write("@@ -${rangeHeader(hunk.oldStart, hunk.oldLines)} +${rangeHeader(hunk.newStart, hunk.newLines)} @@")
                    writer.newLine()
                    for (line in hunk.lines) {
                        val prefix = when (line.kind) {
                            LineKind.CONTEXT -> " "
                            LineKind.REMOVED -> "-"
                            LineKind.ADDED -> "+"
                        }
                        writer.write(prefix + line.text)
                        writer.newLine()
                    }
                }
            }
            ToolsResult.Success(outputFile)
        } catch (e: Throwable) {
            ToolsLog.reportFailure("LogDiff", e)
            ToolsResult.Failure("执行失败, message: ${e.message}")
        }
    }

    /**
     * unified 头部区间表示：非空区间显示 1 基起点；空区间显示其 0 基起点
     * （即插入点前一行的行号，对齐 GNU diff 展示习惯）。
     */
    private fun rangeHeader(start0: Int, len: Int): String =
        if (len == 0) "$start0,0" else "${start0 + 1},$len"

    // ── 流式扫描：数行 / 行哈希 ──

    private fun readerOf(file: File): BufferedReader =
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 1024 * 1024)

    private fun countLines(file: File): Int {
        readerOf(file).use { reader ->
            var count = 0
            while (reader.readLine() != null) count++
            return count
        }
    }

    /** 流式填行哈希；扫描期间文件行数变化（被并发写入）按失败处理 */
    private fun hashLines(file: File, out: LongArray, normalize: Boolean) {
        readerOf(file).use { reader ->
            var index = 0
            while (true) {
                val line = reader.readLine() ?: break
                require(index < out.size) { "line count grew during scan: $file" }
                out[index++] = hashOf(if (normalize) normalizedKey(line) else line)
            }
            require(index == out.size) { "line count shrank during scan: $file" }
        }
    }

    /** 行摘要 = 64 位 FNV-1a（UTF-16 码元逐位）；碰撞界见对象 KDoc */
    private fun hashOf(line: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 位偏移基 0xcbf29ce484222325（补码字面量）
        for (ch in line) {
            hash = (hash xor ch.code.toLong()) * 0x100000001b3L
        }
        return hash
    }

    // ── 时间戳归一化对齐（比对键变换） ──

    private val normalizer = TimestampNormalizer()

    /** 行级变换不读段元数据——占位上下文仅为满足 [LogPostProcessor.processLine] 形参契约 */
    private val inertContext = SegmentContext(
        segmentIndex = 0,
        magicStartMark = MagicStartMark.NoCompressStart,
        seq = 0,
        beginHour = 0,
        endHour = 0,
    )

    private const val TS_PLACEHOLDER = "<ts>"

    /** 比对键：先 epoch→可读归一化（[TimestampNormalizer] 单源），再掩码行首可读时间戳 */
    private fun normalizedKey(line: String): String {
        val readable = normalizer.processLine(line, inertContext)
        return LogTimestamp.maskLineTimestamp(readable, TS_PLACEHOLDER)
    }

    // ── 对齐：Myers 贪心 + 深度快照回溯 ──

    /**
     * 在行哈希序列上计算最小编辑脚本（Myers 贪心前向搜索，每深度存一份紧凑 V 快照，
     * 从终点按同规则回溯出操作游程）。编辑距离超 [MAX_ALIGN_EDITS] 时不再搜索，
     * 保留深度 0 探明的公共前缀后整体替换退化。
     *
     * 确定性契约：同一输入恒返回同一游程序列（平局固定取向新增侧）。
     */
    internal fun alignOps(a: LongArray, b: LongArray): AlignedOps {
        val n = a.size
        val m = b.size
        val snapshots = ArrayList<IntArray>(MAX_ALIGN_EDITS + 1)

        // 深度 0：对角线 0 上的公共前缀蛇
        var x = 0
        while (x < n && x < m && a[x] == b[x]) x++
        snapshots.add(intArrayOf(x))
        if (x >= n && x >= m) {
            val prefix = if (x > 0) listOf(Op(OpKind.MATCH, 0, 0, x)) else emptyList()
            return AlignedOps(prefix, degraded = false)
        }

        var prev = snapshots[0]
        for (d in 1..MAX_ALIGN_EDITS) {
            // 深度 d 的紧凑快照：j = (k+d)/2，k = 2j-d ∈ [-d, d]
            val cur = IntArray(d + 1)
            var reached = false
            var j = 0
            while (j <= d) {
                val k = 2 * j - d
                val cx: Int = when {
                    // 只能来自对角线 k+1（下移 = 新增 b 行）
                    j == 0 -> prev[0]
                    // 只能来自对角线 k-1（右移 = 删除 a 行）
                    j == d -> prev[d - 1] + 1
                    // 平局取向 k+1（回溯侧同规则镜像）
                    prev[j - 1] < prev[j] -> prev[j]
                    else -> prev[j - 1] + 1
                }
                var px = cx
                var py = px - k
                while (px < n && py < m && a[px] == b[py]) {
                    px++
                    py++
                }
                cur[j] = px
                if (px >= n && py >= m) {
                    reached = true
                    break
                }
                j++
            }
            snapshots.add(cur)
            if (reached) {
                return AlignedOps(backtrack(snapshots, d, a, b), degraded = false)
            }
            prev = cur
        }

        // 超限退化：公共前缀之外的中间区域整体替换（degraded 标记交调用方呈现）
        val prefix = snapshots[0][0]
        val ops = ArrayList<Op>(3)
        if (prefix > 0) ops.add(Op(OpKind.MATCH, 0, 0, prefix))
        if (prefix < n) ops.add(Op(OpKind.DELETE, prefix, prefix, n - prefix))
        if (prefix < m) ops.add(Op(OpKind.INSERT, n, prefix, m - prefix))
        return AlignedOps(ops, degraded = true)
    }

    /**
     * 从终点 (a.size, b.size) 沿快照回溯最小编辑路径。
     * 前驱判定与贪心前向规则逐字镜像（含平局取向），保证回出的路径就是搜索到的路径：
     * 每步先记该步蛇（对角匹配段），再记一步编辑；逆序收集后反转即得正向游程。
     */
    private fun backtrack(snapshots: List<IntArray>, depth: Int, a: LongArray, b: LongArray): List<Op> {
        val reversed = ArrayList<Op>()
        var x = a.size
        var y = b.size
        for (d in depth downTo 1) {
            val prev = snapshots[d - 1]
            val k = x - y
            val j = (k + d) / 2
            if (j == 0 || (j != d && prev[j - 1] < prev[j])) {
                // 来自对角线 k+1：新增 b[py] 后沿对角线匹配 s 行到 (x, y)
                val px = prev[j]
                val py = px - k - 1
                val snake = x - px
                if (snake > 0) reversed.add(Op(OpKind.MATCH, px, py + 1, snake))
                reversed.add(Op(OpKind.INSERT, px, py, 1))
                x = px
                y = py
            } else {
                // 来自对角线 k-1：删除 a[px] 后沿对角线匹配 s 行到 (x, y)
                val px = prev[j - 1]
                val py = px - k + 1
                val snake = x - px - 1
                if (snake > 0) reversed.add(Op(OpKind.MATCH, px + 1, py, snake))
                reversed.add(Op(OpKind.DELETE, px, py, 1))
                x = px
                y = py
            }
        }
        // 深度 0：原点到 (x, y) 的公共前缀蛇（对角线 0 上 x == y）
        if (x > 0) reversed.add(Op(OpKind.MATCH, 0, 0, x))
        return mergeRuns(reversed.asReversed())
    }

    /** 合并相邻同类游程；顺带以连续性要求验证回溯产物（防索引演算回归静默漂移） */
    private fun mergeRuns(ops: List<Op>): List<Op> {
        val merged = ArrayList<Op>(ops.size)
        var aPos = 0
        var bPos = 0
        for (op in ops) {
            require(op.aStart == aPos && op.bStart == bPos) {
                "alignment continuity broken: expected ($aPos, $bPos), got (${op.aStart}, ${op.bStart})"
            }
            val last = merged.lastOrNull()
            if (last != null && last.kind == op.kind) {
                merged[merged.size - 1] = last.copy(count = last.count + op.count)
            } else {
                merged.add(op)
            }
            aPos += op.aExtent
            bPos += op.bExtent
        }
        return merged
    }

    private fun readHunks(
        oldFile: File,
        newFile: File,
        aligned: AlignedOps,
        oldCount: Int,
        newCount: Int,
    ): List<Hunk> {
        val groups = groupEdits(aligned.ops)
        if (groups.isEmpty()) return emptyList()
        val hunks = ArrayList<Hunk>(groups.size)
        readerOf(oldFile).use { oldReader ->
            readerOf(newFile).use { newReader ->
                val oldCursor = LineCursor(oldReader)
                val newCursor = LineCursor(newReader)
                for (group in groups) {
                    val first = aligned.ops[group.firstEdit]
                    val last = aligned.ops[group.lastEdit]
                    // 窗口起点/终点沿对角线向两侧扩上下文；文件边界处钳制。
                    // 前后置上下文段恒为对角公共段（lead/trail 两侧等长——组边界由
                    // ≥2×上下文 的公共游程或文件边界界定，钳制只发生在对角段内）
                    val oldStart = (first.aStart - CONTEXT_LINES).coerceAtLeast(0)
                    val newStart = (first.bStart - CONTEXT_LINES).coerceAtLeast(0)
                    val oldEnd = (last.aStart + last.aExtent + CONTEXT_LINES).coerceAtMost(oldCount)
                    val newEnd = (last.bStart + last.bExtent + CONTEXT_LINES).coerceAtMost(newCount)
                    oldCursor.skipTo(oldStart)
                    newCursor.skipTo(newStart)
                    val lines = ArrayList<HunkLine>()
                    fun context(count: Int) {
                        repeat(count) {
                            lines.add(HunkLine(LineKind.CONTEXT, oldCursor.next()))
                            newCursor.skip(1)
                        }
                    }
                    context(first.aStart - oldStart)
                    for (i in group.firstEdit..group.lastEdit) {
                        val op = aligned.ops[i]
                        when (op.kind) {
                            OpKind.MATCH -> context(op.count)
                            OpKind.DELETE -> repeat(op.count) {
                                lines.add(HunkLine(LineKind.REMOVED, oldCursor.next()))
                            }
                            OpKind.INSERT -> repeat(op.count) {
                                lines.add(HunkLine(LineKind.ADDED, newCursor.next()))
                            }
                        }
                    }
                    val lastAEnd = last.aStart + last.aExtent
                    context(oldEnd - lastAEnd)
                    hunks.add(
                        Hunk(
                            oldStart = oldStart,
                            oldLines = oldEnd - oldStart,
                            newStart = newStart,
                            newLines = newEnd - newStart,
                            lines = lines,
                        ),
                    )
                }
            }
        }
        return hunks
    }

    /** 编辑分组：相邻编辑间公共游程 ≥ 2×[CONTEXT_LINES] 时切开（其内上下文不交叠） */
    private class EditGroup(val firstEdit: Int, val lastEdit: Int)

    private fun groupEdits(ops: List<Op>): List<EditGroup> {
        val groups = ArrayList<EditGroup>()
        var open = -1
        var lastEdit = -1
        for (i in ops.indices) {
            when (ops[i].kind) {
                OpKind.MATCH -> if (ops[i].count >= 2 * CONTEXT_LINES && open >= 0) {
                    groups.add(EditGroup(open, lastEdit))
                    open = -1
                }
                OpKind.DELETE, OpKind.INSERT -> {
                    if (open < 0) open = i
                    lastEdit = i
                }
            }
        }
        if (open >= 0) groups.add(EditGroup(open, lastEdit))
        return groups
    }

    /** 行游标：只前进；0 基行下标，EOF 提前到达视为文件被并发修改（按失败处理） */
    private class LineCursor(private val reader: BufferedReader) {
        var pos: Int = 0
            private set

        fun skipTo(target: Int) {
            require(target >= pos) { "cursor cannot go back: pos=$pos, target=$target" }
            while (pos < target) {
                if (reader.readLine() == null) throw IllegalStateException("unexpected EOF at line $pos")
                pos++
            }
        }

        fun skip(count: Int) = skipTo(pos + count)

        fun next(): String {
            val line = reader.readLine() ?: throw IllegalStateException("unexpected EOF at line $pos")
            pos++
            return line
        }
    }
}
