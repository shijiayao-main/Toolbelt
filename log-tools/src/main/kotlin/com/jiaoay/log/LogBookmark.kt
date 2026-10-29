package com.jiaoay.log

import java.time.Duration
import kotlin.math.abs

/**
 * 查看器书签行集（会话级导航标记内核：纯内存行集，不落盘——生命周期与查看会话
 * 同步，由编排层在会话建立时按总行数构造、漂移重开时重建）。
 *
 * 行下标口径：0 基（与 [LogViewSession] 各导航查询同一口径）。两种序并存：
 * - 标记序（[markedInOrder]）：按标记先后追加——时间差测量取「最近两签」的依据；
 * - 行序（[sortedMarks]）：升序行下标——上一签/下一签跳转与小地图绘制的依据。
 *
 * 越界行下标（< 0 或 ≥ [totalLines]）一律拒绝（返回 false、不入集）——书签只锚
 * 本次会话索引内的行。
 *
 * 非线程安全：单调用方串行使用（编排层驱动，口径同 [LogViewSession]）。
 */
class LogBookmarkSet(val totalLines: Int) {

    private val marksInOrder = ArrayList<Int>()
    private val markedIndexes = HashSet<Int>()

    /** 当前书签数 */
    val count: Int get() = markedIndexes.size

    /** [lineIndex] 是否已标记（越界恒 false） */
    fun isMarked(lineIndex: Int): Boolean = markedIndexes.contains(lineIndex)

    /**
     * 翻转标记态：未标记则标记（追加进标记序尾），已标记则取消（从两种序中移除）。
     *
     * @return 翻转后处于已标记态为 true；越界行下标拒绝返回 false（状态不变，
     *   调用方不得据此改呈现态）
     */
    fun toggle(lineIndex: Int): Boolean {
        if (lineIndex < 0 || lineIndex >= totalLines) return false
        if (markedIndexes.contains(lineIndex)) {
            markedIndexes.remove(lineIndex)
            marksInOrder.remove(lineIndex)
            return false
        }
        markedIndexes.add(lineIndex)
        marksInOrder.add(lineIndex)
        return true
    }

    /** 清空全部书签（两种序一并清零） */
    fun clear() {
        markedIndexes.clear()
        marksInOrder.clear()
    }

    /** 标记序快照（按标记先后；快照与后续翻转互不影响） */
    fun markedInOrder(): List<Int> = marksInOrder.toList()

    /** 行序快照（升序行下标） */
    fun sortedMarks(): List<Int> = marksInOrder.sorted()
}

/**
 * 书签时间差测量内核（纯函数：两行原文 → 带符号差值或缺失档位，不触文件 IO——
 * 行原文由调用方经 [LogViewSession.readWindow] 回读）。
 *
 * 时间戳解析单源 [LogTimestamp.parseLine]（与时间戳跳转/时间过滤同一契约，无第二
 * 套解析口径）；裸格式 `MM-dd` 行的锚定年份由调用方传入——单格式文件内相对先后
 * 与锚定年份取值无关（锚定口径同 [LogTimestamp.parseTarget]）。
 */
object LogTimeDiff {

    /** 测量端点档：时间戳缺失发生在前签还是后签（调用方回执定位用） */
    enum class End { FROM, TO }

    sealed interface Result {

        /**
         * 测量成功。[millis] = 后签减前签（带符号：负值 = 后签时间早于前签）；
         * [text] = 人话差值（负值含倒流明示，见 [formatDuration]）
         */
        data class Ok(val millis: Long, val text: String) : Result

        /** 某端行首无时间戳（调用方按人话拒绝组回执，不猜差值） */
        data class NoTimestamp(val end: End) : Result
    }

    /**
     * 测量前签/后签两行行首时间戳差值（后签 − 前签）。
     *
     * @param fallbackYear 裸格式 `MM-dd` 行的锚定年份（两行同锚，差值不受取值影响）
     * @return 任一端行首无时间戳返回 [Result.NoTimestamp]（端点档明示），两端可解析
     *   返回 [Result.Ok]
     */
    fun measure(fromLineText: String, toLineText: String, fallbackYear: String): Result {
        val from = LogTimestamp.parseLine(fromLineText, fallbackYear)
            ?: return Result.NoTimestamp(End.FROM)
        val to = LogTimestamp.parseLine(toLineText, fallbackYear)
            ?: return Result.NoTimestamp(End.TO)
        val millis = Duration.between(from, to).toMillis()
        return Result.Ok(millis, formatDuration(millis))
    }

    /**
     * 人话差值：按量级分档（毫秒 / 秒 / 分 / 时，毫秒段恒 3 位）。负值 = 时间倒流态
     * （后签早于前签），输出倒流明示——差值为负不静默取绝对值。
     */
    fun formatDuration(millis: Long): String {
        val abs = abs(millis)
        val wholeSeconds = abs / 1000
        val msPart = (abs % 1000).toString().padStart(3, '0')
        val magnitude = when {
            abs < 1_000 -> "${abs} 毫秒"
            abs < 60_000 -> "$wholeSeconds.$msPart 秒"
            abs < 3_600_000 -> "${wholeSeconds / 60} 分 ${wholeSeconds % 60}.$msPart 秒"
            else -> "${wholeSeconds / 3600} 时 ${wholeSeconds % 3600 / 60} 分 ${wholeSeconds % 60}.$msPart 秒"
        }
        return if (millis < 0) "时间倒流：后签早于前签 $magnitude" else magnitude
    }
}
