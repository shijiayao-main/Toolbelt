package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors

/**
 * 查看器右侧命中小地图（match overview 形态）：按行号比例整列绘刻度——搜索命中行
 * error 语义色、书签行 primary 语义色（零硬编码色值 token，视觉深浅待真机终裁），
 * 点击按比例跳行（0 基行下标经 [onSeekLine] 上抛，由编排层复用行号跳转链路）。
 *
 * 刻度物种与层序契约（后绘覆盖同行先绘，KDoc 单源）：
 * 1. E/F 级别密度刻度（[LevelTicks.errorLineIndexes]，error 语义色）；
 * 2. W 级别密度刻度（[LevelTicks.warnLineIndexes]，warning 语义色）；
 * 3. 搜索命中（error 语义色——与 E 刻度同色：级别密度与搜索命中同为「看这里」
 *    信号，同色叠绘无歧义，层级上命中后绘覆盖 E 刻度）；
 * 4. 并行高亮通道刻度（[highlightTicks]，通道语义色经 [highlightChannelColor]）；
 * 5. 书签刻度（primary 语义色，最后绘——同一行叠绘时导航标记恒最上层）。
 *
 * 行集来源均为会话级现成数据（引擎搜索命中集 / 级别扫描集 / 通道命中组 / 书签行集），
 * 绘制零索引开销。比例映射收口在纯函数 [lineFraction] / [fractionToLineIndex] 与
 * 级别密度槽映射 [levelSlotOf]（边界与钳制可单测）。列高取 [Dimens.consoleHeightMax]
 * 定值对齐窗口行流的常规渲染高度；矮文件下小地图高于行流的排布形态待真机终裁。
 *
 * public 可见性仅为 composeApp 侧场景守护渲染锚服务（:ui 测试源集无 skiko 渲染
 * 运行时）；消费面仍是 [LogViewerDialog]。
 */
@Composable
fun LogViewerMinimap(
    totalLines: Int,
    matchedLineIndexes: List<Int>,
    bookmarkLineIndexes: List<Int>,
    levelTicks: LevelTicks = LevelTicks(),
    highlightTicks: List<HighlightTickGroup> = emptyList(),
    onSeekLine: (Int) -> Unit,
) {
    val matchColor = MaterialTheme.colorScheme.error
    val bookmarkColor = MaterialTheme.colorScheme.primary
    val warnTickColor = LocalEasyToolboxColors.current.warning
    // Canvas 绘制块非组合语境：通道语义色须在组合期预解析成槽位 → 色值映射
    val channelTickColors = highlightTicks.associate { it.colorSlot to highlightChannelColor(it.colorSlot) }
    Canvas(
        modifier = Modifier
            .width(Dimens.viewerMinimapWidth)
            .height(Dimens.consoleHeightMax)
            .pointerInput(totalLines) {
                detectTapGestures { offset ->
                    fractionToLineIndex(offset.y / size.height, totalLines)?.let(onSeekLine)
                }
            },
    ) {
        val tickHeight = size.height
        val tickStroke = Dimens.strokeThin.toPx()
        // 层序 = 本函数 KDoc「刻度物种与层序契约」：密度底图 → 搜索命中 → 通道 → 书签
        levelTicks.errorLineIndexes.forEach { lineIndex ->
            val y = lineFraction(lineIndex, totalLines) * tickHeight
            drawLine(matchColor, Offset(0f, y), Offset(size.width, y), tickStroke)
        }
        levelTicks.warnLineIndexes.forEach { lineIndex ->
            val y = lineFraction(lineIndex, totalLines) * tickHeight
            drawLine(warnTickColor, Offset(0f, y), Offset(size.width, y), tickStroke)
        }
        matchedLineIndexes.forEach { lineIndex ->
            val y = lineFraction(lineIndex, totalLines) * tickHeight
            drawLine(matchColor, Offset(0f, y), Offset(size.width, y), tickStroke)
        }
        highlightTicks.forEach { group ->
            val color = channelTickColors[group.colorSlot] ?: return@forEach
            group.lineIndexes.forEach { lineIndex ->
                val y = lineFraction(lineIndex, totalLines) * tickHeight
                drawLine(color, Offset(0f, y), Offset(size.width, y), tickStroke)
            }
        }
        bookmarkLineIndexes.forEach { lineIndex ->
            val y = lineFraction(lineIndex, totalLines) * tickHeight
            drawLine(bookmarkColor, Offset(0f, y), Offset(size.width, y), tickStroke)
        }
    }
}

/**
 * 级别密度刻度集（小地图级别刻度数据面）：E/F 与 W 两档各持「每密度槽首个命中行」
 * 的 0 基升序行下标集。行集来源 = 打开就绪后的一次全文件级别扫描（[LogViewerDialog]
 * 编排，级别判定单源 = 引擎行集自带级别，无第二套判定）。行下标集大小以扫描槽位数
 * 为上界（槽位数与搜索命中集同档，真源 = 引擎 LogViewer.MAX_SEARCH_RESULTS）——
 * 刻度绘制复用 [lineFraction] 行位映射，故行下标本身就是刻度锚。
 */
@Immutable
data class LevelTicks(
    /** E/F 级行的槽首命中行下标（0 基升序；大小 ≤ 扫描槽位数） */
    val errorLineIndexes: List<Int> = emptyList(),
    /** W 级行的槽首命中行下标（0 基升序；大小 ≤ 扫描槽位数） */
    val warnLineIndexes: List<Int> = emptyList(),
)

/**
 * 单个并行高亮通道的小地图刻度组：该通道持色的命中行下标集（同一行多通道命中时
 * 只入持色通道的组——归并在 [LogViewerDialog] 的 buildHighlightLayers 单源）+
 * 通道色槽序（语义色经 [highlightChannelColor] 按 [colorSlot] 取）。
 */
@Immutable
data class HighlightTickGroup(
    /** 本通道持色的命中行下标（0 基升序） */
    val lineIndexes: List<Int>,
    /** 通道色槽序（0 起，通道序绑定） */
    val colorSlot: Int,
)

/**
 * 行下标 → 列内比例位置（[fractionToLineIndex] 的逆映射）。单行文件恒 0f（唯一行
 * 落在列顶）；空文件无行可绘恒 0f（调用方以行集空判跳过绘制）。
 */
internal fun lineFraction(lineIndex: Int, totalLines: Int): Float =
    if (totalLines <= 1) 0f else lineIndex.toFloat() / (totalLines - 1)

/**
 * 列内比例位置 → 行下标。空文件返回 null（无可跳目标，调用方不动窗口）；单行文件
 * 恒返回 0；落点比例钳制进 [0, 1]（点击越出列体的坐标按边界行计）后四舍五入取行。
 */
internal fun fractionToLineIndex(fraction: Float, totalLines: Int): Int? {
    if (totalLines <= 0) return null
    if (totalLines == 1) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return (clamped * (totalLines - 1) + 0.5f).toInt().coerceIn(0, totalLines - 1)
}

/**
 * 级别密度槽映射（行下标 → 槽位）：均匀分槽 `行下标 × 槽位数 ÷ 总行数`，行下标
 * 两端钳制进 [0, 总行数)（越界输入按边界行计），槽位钳制进 [0, 槽位数)——首行恒
 * 槽 0、末行恒末槽。空文件（totalLines ≤ 0）或零槽位无槽可映射返回 null。
 * 升序扫描下每槽首个落入的行即槽首命中行（刻度锚），刻度绘制仍按行下标走
 * [lineFraction]——槽映射只做数量上界，不改变列内落点几何。
 */
internal fun levelSlotOf(lineIndex: Int, totalLines: Int, slotCount: Int): Int? {
    if (totalLines <= 0 || slotCount <= 0) return null
    val idx = lineIndex.coerceIn(0, totalLines - 1)
    return ((idx.toLong() * slotCount) / totalLines).toInt().coerceIn(0, slotCount - 1)
}
