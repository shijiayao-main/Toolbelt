package com.jiaoay.easy_dev_toolbox.host

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.WindowScope

/**
 * WindowScope 下发（edge-to-edge）：`WindowDraggableArea` 是 `WindowScope` 的扩展
 * composable（foundation），而自绘标题栏组合在 AppTheme 内部深处——main.kt 的 Window
 * content lambda（receiver = FrameWindowScope）装配时 provides，消费方 [AppTitleBar] 读取。
 *
 * 缺装配显式炸（fail-fast，同 [com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors] 纪律）：
 * 标题栏只在 Window 内容内组合，装配缺失属结构错误。
 */
val LocalAppWindowScope = staticCompositionLocalOf<WindowScope> {
    error("LocalAppWindowScope 未装配——AppTitleBar 必须在 Window content 内组合（main.kt provides）")
}
