package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * 窗口尺寸类别（响应式四档）：
 * [Compact] < 1024 / [Medium] 1024–1440 / [Expanded] 1440–1760 / [Wide] > 1760
 * （阈值常量在 [Dimens]，由宿主 AppShell 的 BoxWithConstraints 换算后参数化传入
 * [classifyWindowSize]——本文件零 dp 字面量，不触 RAW_DP 守护）。
 * 各档行为契约（侧栏浮层/拖宽/限宽）见 docs/ui-style-guide.md「响应式四档」表。
 */
enum class WindowSizeClass {
    Compact,
    Medium,
    Expanded,
    Wide,
}

/**
 * 断点分类纯函数：宽度 → 尺寸档。阈值经参数传入（[Dimens] 持有常量），
 * 本函数不持任何 dp 字面量；三段开区间边界（恰等于阈值归上一档）。
 */
fun classifyWindowSize(
    widthDp: Float,
    compactMaxDp: Float,
    mediumMaxDp: Float,
    wideMaxDp: Float,
): WindowSizeClass = when {
    widthDp < compactMaxDp -> WindowSizeClass.Compact
    widthDp < mediumMaxDp -> WindowSizeClass.Medium
    widthDp < wideMaxDp -> WindowSizeClass.Expanded
    else -> WindowSizeClass.Wide
}

/**
 * 当前窗口尺寸类别（宿主 AppShell 装配点 provide；feature 页只读）。
 * 默认 [WindowSizeClass.Medium]——未装配（测试/预览）时按常规桌面形态渲染，
 * 缺省值即现状视觉。
 */
val LocalWindowSizeClass = compositionLocalOf { WindowSizeClass.Medium }

/** 便捷读取：当前窗口尺寸类别 */
@Composable
fun currentWindowSizeClass(): WindowSizeClass = LocalWindowSizeClass.current
