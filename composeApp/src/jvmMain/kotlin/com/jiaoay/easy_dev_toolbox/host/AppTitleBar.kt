package com.jiaoay.easy_dev_toolbox.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.MouseInfo
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalTrafficBackdrop
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassChrome
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverTooltipBox
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自绘标题栏（edge-to-edge——`.win` 圆角窗 + 窗内标题栏实机化）。
 *
 * - **拖拽**：手写位移跟随（AWT `setLocation`）——CMP 内建 `WindowDraggableArea`
 *   （JBR `startMovingTogetherWithMouse`）真机实测在 XWayland+透明窗+合成输入下不跟手；
 *   位移跟随语义确定，对合成与真实输入一致，全程零 consume；
 * - **双击最大化**：自写零消费双击检测（`detectTapGestures` 会 consume down——饿死同面
 *   `awaitFirstDown` 的邻接手势）；
 * - **三窗口控制圆点**（.titlebar .dot，左置）：12dp **产品语义三色**
 *   （关闭红 #FF5F57 / 最小化黄 #FEBC2E / 最大化绿 #28C840——「红绿灯三色=产品语义」，
 *   三色恒常量不随 seed）。hover 命中区升 surfaceDeep
 *   实底 + hover 气泡（配方单源 = widget/interactions/HoverTooltipBox）；关闭 = [onClose]
 *   （= exitApplication，AppHost.shutdown 退出序经 application 作用域 onDispose 兜底）、
 *   最小化/最大化走 [WindowState.placement] 切换（最大化时圆角由 AppTheme 的
 *   windowShape 回正）；
 * - **窗标题**：与任务栏同名（App() 动态页面标题同源字符串）；
 * - **材质**：chrome 档实底预混（opaqueSlotChrome——background:
 *   var(--chrome-tint)，与 dock/状态栏同带一色）+ 顶缘 rim +
 *   底缘 glass-brd 发丝线。
 *
 * 🔴 零白名单：尺寸走 [Dimens]（titleBarHeight/trafficLightDot/titleBarPaddingH/
 * minimumInteractiveSize/glassStrokeHairline）、透明度走 [AppAlpha]（glassRimLight）、
 * 形状走 [AppShapes]，无裸 dp/裸 alpha/裸动画。
 */
@Composable
fun AppTitleBar(
    windowState: WindowState,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowScope = LocalAppWindowScope.current
    val onToggleMaximize = {
        windowState.placement =
            if (windowState.placement == WindowPlacement.Maximized) {
                WindowPlacement.Floating
            } else {
                WindowPlacement.Maximized
            }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.titleBarHeight)
            // .titlebar：background: var(--chrome-tint)
            // 实底预混——与 dock/状态栏同带一色（onSurface 6% 罩非本档，勿取）
            .appGlassChrome(shape = RectangleShape),
    ) {
        // 拖拽 = 自绘冻结快照算法（CMP 内建 WindowDraggableArea 的
        // JBR startMovingTogetherWithMouse 真机拖拽【抖动】、合成输入下完全不跟手——双输；
        // 本算法 = MouseInfo 全局指针 + DOWN 时刻窗口位快照差分，自校正无积累，1:1 无抖动）。
        // 🔴 松手即停：主键松开/指针 up/消失 break——缺此判断循环永不退出，松手后鼠标
        // 掠过标题栏窗口仍被搬运（真机反馈实锤）。🔴 禁用事件内 change.position 直接差分
        // （相对窗口而窗口正被移动，ConfigureNotify 晚一帧成反馈回路）。零 consume。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPointer = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                        val startWindowLoc = windowScope.window.location
                        while (true) {
                            val event = awaitPointerEvent()
                            if (!event.buttons.isPrimaryPressed) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) break
                            val now = MouseInfo.getPointerInfo()?.location ?: break
                            windowScope.window.setLocation(
                                startWindowLoc.x + (now.x - startPointer.x),
                                startWindowLoc.y + (now.y - startPointer.y),
                            )
                        }
                    }
                }
                .pointerInput(Unit) {
                    // 双击最大化：零消费检测（detectTapGestures 会 consume down——饿死
                    // awaitFirstDown(requireUnconsumed=true) 的邻接手势，故自写零消费版）
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val firstUp = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            waitForUpOrCancellation()
                        } ?: return@awaitEachGesture
                        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        if (secondDown != null) {
                            onToggleMaximize()
                        }
                    }
                },
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Dimens.titleBarPaddingH),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ── 三窗口控制圆点（.titlebar .dot：关闭红/最小化黄/最大化绿
                    // ——产品语义三色）──
                    // 点距 8（.titlebar gap sp-8）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 昼战红绿灯底衬（design-doc.md §5.3#1，LocalTrafficBackdrop 单源）：昼战形态
                        // 圆点外加近黑底衬圆（三色直落昼底 1.48/2.63/1.99 破图形线，坐
                        // 底衬 6.11/10.83/8.20 达标）；非昼战 null = 裸放现状。
                        val trafficBackdrop = LocalTrafficBackdrop.current
                        WindowControlDot(
                            label = "关闭",
                            color = TitlebarTrafficColors.close,
                            backdrop = trafficBackdrop,
                            onClick = onClose,
                        )
                        WindowControlDot(
                            label = "最小化",
                            color = TitlebarTrafficColors.minimize,
                            backdrop = trafficBackdrop,
                            onClick = { windowState.isMinimized = true },
                        )
                        WindowControlDot(
                            label = if (windowState.placement == WindowPlacement.Maximized) "还原" else "最大化",
                            color = TitlebarTrafficColors.maximize,
                            backdrop = trafficBackdrop,
                            onClick = onToggleMaximize,
                        )
                    }

                    // 标题字左距 = .t margin-left sp-12 + 容器 gap 8 = 20
                    Spacer(modifier = Modifier.size(Dimens.space12))
                    Text(
                        text = title,
                         // .t：fs-13；标题栏 = chrome 带取源井槽（Lowest）——
                        // 文字走卡域链（井槽分家后基域深字对井面破 4.5；非覆写套 onCardMid
                        // 回落 onSurfaceVariant 逐位不变）
                        style = MaterialTheme.typography.bodyMedium,
                        color = com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors.current.onCardMid,
                        maxLines = 1,
                    )
                }
                // 底缘线（.titlebar border-bottom 1px；压在拖拽区上层 1dp 条）
                val glassBorderAlpha = if (com.jiaoay.easy_dev_toolbox.core.theme.LocalIsDarkTheme.current) {
                    AppAlpha.glassBorderDark
                } else {
                    AppAlpha.glassBorderLight
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(Dimens.glassStrokeHairline)
                                                // 稿 --glass-brd 亮 .08/暗 .10 明暗分治
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = glassBorderAlpha)),
                )
            }
        }
    }

/**
 * 标题栏红绿灯三色（产品语义——「红绿灯三色=产品语义」；
 * 三色恒常量 #FF5F57/#FEBC2E/#28C840，不随 seed）。
 * 恒常量不随 seed（同 aiAccent「内容语义色恒常量」先例）。
 */
private object TitlebarTrafficColors {
    val close = Color(0xFFFF5F57)
    val minimize = Color(0xFFFEBC2E)
    val maximize = Color(0xFF28C840)
}

/**
 * 单个窗口控制圆点：12dp 视觉圆点（[Dimens.trafficLightDot]，产品语义三色）+
 * 32dp 命中区（[Dimens.minimumInteractiveSize]，比视觉点距 17px 宽——可用性优先，
 * theme.md 登记收敛）。hover 命中区升 surfaceDeep 实底 + 圆点微降透明度示意按压层。
 *
 * [backdrop] = 昼战近黑底衬（[LocalTrafficBackdrop] 下发，design-doc.md §5.3#1）：非 null 时
 * 圆点外加 [Dimens.trafficLightBackdrop] 底衬圆——三色对昼底全破图形线、对底衬达标；
 * hover 按压示意（alpha 0.38）仅非昼战生效——昼战点色在底衬上本就贴线，降透明度
 * 会跌破承载对比度，hover 反馈由命中区 surfaceDeep 实底独自承担。
 */
@Composable
private fun WindowControlDot(
    label: String,
    color: Color,
    backdrop: Color?,
    onClick: () -> Unit,
) {
    val surface = LocalToolboxSurfaceColors.current

    // hover 气泡配方单源 = interactions.HoverTooltipBox；Below 定位——圆点贴窗口
    // 顶缘，水平定位垂直无兜底会把气泡顶出窗外
    HoverTooltipBox(
        positioning = TooltipAnchorPosition.Below,
        tooltip = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
    ) { interactionSource ->
        val hovered by interactionSource.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .size(Dimens.minimumInteractiveSize)
                .clip(CircleShape)
                .background(if (hovered) surface.surfaceDeep else Color.Transparent)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (backdrop != null) {
                Box(
                    modifier = Modifier
                        .size(Dimens.trafficLightBackdrop)
                        .clip(CircleShape)
                        .background(backdrop),
                )
            }
            Box(
                modifier = Modifier
                    .size(Dimens.trafficLightDot)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (hovered && backdrop == null) AppAlpha.contentSoft else 1f)),
            )
        }
    }
}

