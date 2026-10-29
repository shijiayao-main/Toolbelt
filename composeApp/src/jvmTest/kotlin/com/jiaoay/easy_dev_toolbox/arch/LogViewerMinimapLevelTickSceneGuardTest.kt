package com.jiaoay.easy_dev_toolbox.arch

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.HighlightTickGroup
import com.jiaoay.easy_dev_toolbox.widget.LevelTicks
import com.jiaoay.easy_dev_toolbox.widget.LogViewerMinimap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 查看器小地图级别刻度 + 并行通道刻度场景守护（ImageComposeScene 零显示器渲染，
 * [SceneGuardSupport] 配方单源）：E/F 密度刻度（error 语义色）与 W 刻度（warning
 * 语义色）按行位比例落列；并行通道刻度持通道语义色（色槽 0 = success）；同行
 * 叠绘层序后绘覆盖（书签 > 通道 > 搜索命中 > 级别密度刻度）。
 *
 * [LogViewerMinimap] 的 public 可见性即为本渲染锚服务（:ui 测试源集无 skiko 渲染
 * 运行时，源码切片守护无法背书像素落列）。像素断言口径 = 密度恒 1f（1px = 1dp），
 * 列高 = [Dimens.consoleHeightMax]、列宽 = [Dimens.viewerMinimapWidth]，行位比例
 * = 行下标 ÷ (总行数 - 1)，±4px 带内精确色计数（刻度线宽 [Dimens.strokeThin]）。
 */
class LogViewerMinimapLevelTickSceneGuardTest {

    @Test
    fun `level and channel ticks render in semantic colors at line fractions`() {
        val errorColor = probeSchemeColor { MaterialTheme.colorScheme.error }
        val warnColor = probeSchemeColor { LocalEasyToolboxColors.current.warning }
        val channelColor = probeSchemeColor { LocalEasyToolboxColors.current.success }
        val bookmarkColor = probeSchemeColor { MaterialTheme.colorScheme.primary }

        val pixels = renderPixels(SCENE_W, SCENE_H) { minimapProbeContent() }

        // W 刻度（行 10）：warning 语义色落列，行带无 error 色混入
        assertTrue(
            countInColumn(pixels, warnColor, yOf(9)) > 0,
            "W 刻度须以 warning 语义色落在行位比例处",
        )
        assertTrue(
            countInColumn(pixels, errorColor, yOf(9)) == 0,
            "W 刻度行带不得混入 error 色（刻度物种错色即红）",
        )
        // E 密度刻度（行 80）：error 语义色落列
        assertTrue(
            countInColumn(pixels, errorColor, yOf(79)) > 0,
            "E 密度刻度须以 error 语义色落在行位比例处",
        )
        // 并行通道刻度（行 50，色槽 0 = success）：通道语义色落列
        assertTrue(
            countInColumn(pixels, channelColor, yOf(49)) > 0,
            "并行通道刻度须持通道语义色（success 色槽）",
        )
        // 同行叠绘层序：行 30 既是 E 密度刻度行又是书签行——书签后绘覆盖，行带只见 primary
        assertTrue(
            countInColumn(pixels, bookmarkColor, yOf(29)) > 0,
            "书签刻度须落列并覆盖同行 E 密度刻度（后绘优先）",
        )
        assertTrue(
            countInColumn(pixels, errorColor, yOf(29)) == 0,
            "书签覆盖后该行带不得残留 error 色刻度",
        )
    }

    /**
     * 探针：仅小地图本体 + 合成刻度数据（行流与状态机不在锚面——级别扫描与通道
     * 行为由 :ui 单测背书）。Row 宿主面先行吸收 min constraints 再下发（场景根
     * 不直挂内容件）。
     */
    @Composable
    private fun minimapProbeContent() {
        Row {
            LogViewerMinimap(
                totalLines = TOTAL_LINES,
                matchedLineIndexes = emptyList(),
                bookmarkLineIndexes = listOf(29),
                levelTicks = LevelTicks(
                    errorLineIndexes = listOf(29, 79),
                    warnLineIndexes = listOf(9),
                ),
                highlightTicks = listOf(HighlightTickGroup(lineIndexes = listOf(49), colorSlot = 0)),
                onSeekLine = { },
            )
        }
    }

    /** 列内某行带（±4px）的精确色像素计数（刻度线核心像素即语义色精确值） */
    private fun countInColumn(pixels: Array<IntArray>, color: Int, centerY: Int): Int {
        val columnW = Dimens.viewerMinimapWidth.value.toInt()
        val columnH = Dimens.consoleHeightMax.value.toInt()
        var cnt = 0
        for (y in (centerY - 4)..(centerY + 4)) {
            if (y !in 0 until columnH || y !in pixels.indices) continue
            for (x in 0 until columnW) {
                if (x !in pixels[y].indices) continue
                if (pixels[y][x] == color) cnt++
            }
        }
        return cnt
    }

    /** 行位比例 → 列内像素行（行下标 × 列高 ÷ (总行数 - 1)，密度 1f 下 1px = 1dp） */
    private fun yOf(lineIndex: Int): Int =
        (lineIndex * Dimens.consoleHeightMax.value.toInt()) / (TOTAL_LINES - 1)

    private companion object {
        /** 探针场景总行数（行位比例的分母） */
        const val TOTAL_LINES = 100
        const val SCENE_W = 48
        const val SCENE_H = 480
    }
}
