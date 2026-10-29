package com.jiaoay.easy_dev_toolbox.host

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Window

/**
 * 自绘缩放边条 overlay（edge-to-edge）：undecorated 窗的八向边缘缩放。
 *
 * 🔴 为什么自绘：CMP 1.12 内建 `UndecoratedWindowResizer` 真机实测增益异常
 * （四边拖拽均只产生 1px 级变化——同一会话里自绘拖拽用同款 MouseInfo 算法 1:1 跟手）。
 * 本件复用**已验证 1:1 的冻结快照算法**：DOWN 时刻取 MouseInfo 全局指针位 + 窗口位/
 * 尺寸快照，拖拽中按 `now - start` 差分 setBounds——快照不随窗口移动/缩放变化，
 * 无反馈回路；全程零 consume（下层滚动条/边缘交互件照常收事件）。
 *
 * - 渲染为窗口根的**顶层透明 overlay**（AppTheme 内最后兄弟 = 最上层）：边条本身不可见
 *   （Color.Transparent），只有命中带与 resize cursor 形状；
 * - [WindowState.placement] == Maximized 时整层退出渲染（最大化不可缩放）；
 * - 最小尺寸钳制 = `window.minimumSize`（960×640，防 Shell 页破版）；
 * - 边条厚 [Dimens.resizerThickness]（8dp，CMP 内建默认档同值）。
 *
 * 🔴 零白名单：尺寸走 [Dimens]，无裸 dp/裸 alpha/裸动画/玻璃（透明条非玻璃）。
 */
@Composable
fun UndecoratedResizeOverlay(
    window: Window,
    windowState: WindowState,
    modifier: Modifier = Modifier,
) {
    if (windowState.placement == WindowPlacement.Maximized) return
    val thickness = Dimens.resizerThickness
    val minimum = window.minimumSize

    Box(modifier = modifier.fillMaxSize()) {
        // ── 四边（先放，角条后放覆盖之）──
        ResizeStrip(
            window = window, sides = SIDE_LEFT, minimum = minimum,
            cursor = Cursor.W_RESIZE_CURSOR,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().width(thickness),
        )
        ResizeStrip(
            window = window, sides = SIDE_RIGHT, minimum = minimum,
            cursor = Cursor.E_RESIZE_CURSOR,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(thickness),
        )
        ResizeStrip(
            window = window, sides = SIDE_TOP, minimum = minimum,
            cursor = Cursor.N_RESIZE_CURSOR,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(thickness),
        )
        ResizeStrip(
            window = window, sides = SIDE_BOTTOM, minimum = minimum,
            cursor = Cursor.S_RESIZE_CURSOR,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(thickness),
        )
        // ── 四角（后放 = 置顶，角落命中归角条）──
        ResizeStrip(
            window = window, sides = SIDE_LEFT or SIDE_TOP, minimum = minimum,
            cursor = Cursor.NW_RESIZE_CURSOR,
            modifier = Modifier.align(Alignment.TopStart).size(thickness),
        )
        ResizeStrip(
            window = window, sides = SIDE_RIGHT or SIDE_TOP, minimum = minimum,
            cursor = Cursor.NE_RESIZE_CURSOR,
            modifier = Modifier.align(Alignment.TopEnd).size(thickness),
        )
        ResizeStrip(
            window = window, sides = SIDE_LEFT or SIDE_BOTTOM, minimum = minimum,
            cursor = Cursor.SW_RESIZE_CURSOR,
            modifier = Modifier.align(Alignment.BottomStart).size(thickness),
        )
        ResizeStrip(
            window = window, sides = SIDE_RIGHT or SIDE_BOTTOM, minimum = minimum,
            cursor = Cursor.SE_RESIZE_CURSOR,
            modifier = Modifier.align(Alignment.BottomEnd).size(thickness),
        )
    }
}

/** 方向位标记（UndecoratedWindowResizer 的 Side 常量同构） */
private const val SIDE_LEFT = 0x0001
private const val SIDE_TOP = 0x0010
private const val SIDE_RIGHT = 0x0100
private const val SIDE_BOTTOM = 0x1000

/**
 * 单条缩放命中带：透明 + resize cursor + 冻结快照拖拽（[AppTitleBar] 拖拽同算法）。
 * [sides] 决定差分作用到哪条边（角条 = 两方向按位或）。
 */
@Composable
private fun ResizeStrip(
    window: Window,
    sides: Int,
    minimum: java.awt.Dimension,
    cursor: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .pointerHoverIcon(PointerIcon(Cursor(cursor)))
            .pointerInput(sides) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPointer = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                    val startX = window.x
                    val startY = window.y
                    val startW = window.width
                    val startH = window.height
                    while (true) {
                        val event = awaitPointerEvent()
                        // 🔴 松手即停（真机反馈修复）：主键松开/指针 up/消失 = 手势结束——
                        // 缺此判断时循环永不退出，鼠标掠过边缘条（不按键）窗口也会被继续缩放
                        // （「一直在边缘处于拖拽状态」的根因）。
                        if (!event.buttons.isPrimaryPressed) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) break
                        val now = MouseInfo.getPointerInfo()?.location ?: break
                        val dx = now.x - startPointer.x
                        val dy = now.y - startPointer.y
                        var newX = startX
                        var newY = startY
                        var newW = startW
                        var newH = startH
                        if (sides and SIDE_LEFT != 0) {
                            newW = (startW - dx).coerceAtLeast(minimum.width)
                            newX = startX + startW - newW
                        }
                        if (sides and SIDE_RIGHT != 0) {
                            newW = (startW + dx).coerceAtLeast(minimum.width)
                        }
                        if (sides and SIDE_TOP != 0) {
                            newH = (startH - dy).coerceAtLeast(minimum.height)
                            newY = startY + startH - newH
                        }
                        if (sides and SIDE_BOTTOM != 0) {
                            newH = (startH + dy).coerceAtLeast(minimum.height)
                        }
                        window.setBounds(newX, newY, newW, newH)
                    }
                }
            },
    )
}
