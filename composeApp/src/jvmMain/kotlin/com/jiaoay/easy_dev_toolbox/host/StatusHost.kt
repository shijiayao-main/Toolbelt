package com.jiaoay.easy_dev_toolbox.host

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.ToastEntry
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.TimingConstants
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassThin
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.delay

/**
 * StatusSignals 渲染壳（全局浮层反馈通道 · 状态面下沉 :core 后的宿主渲染层）。
 *
 * 行为规范（计划三、一次性反馈）：
 * - 成功/确认类 **[TimingConstants.SUCCESS_BANNER_MS] 自动消退** + **hover 暂停**
 *   （hover 期间计时冻结——NN/g 阅读时间尊重）；
 * - 带撤销按钮的操作（L1）走撤销条：撤销窗口 [TimingConstants.UNDO_WINDOW_MS]，
 *   倒计时条从满宽线性消退，hover 同样暂停；点「撤销」回调调用方 + 立即消失；
 * - 渲染位置：状态栏之上右下角浮层（**不遮挡内容**），空时零高度零渲染。
 *
 * **Column 三槽堆叠纵排**（`.toastcol` 形态：
 *    `Arrangement.spacedBy(Dimens.space8)` + 尾对齐，头旧尾新）。
 *    逐条独立三件套——hover interactionSource / remainingMs 倒计时 / CLOSE 关闭钮
 *    （`key(entry.id)`：挤最旧时中位/新位的倒计时与淡入不因位置错位重启）。
 *
 * 状态面（show/showUndo/dismiss/toast StateFlow）在
 *    [com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals]（:core——feature 可见的
 *    唯一合法交点）；消退计时器刻意留在本渲染壳 LaunchedEffect（组合作用域），
 *    不进 object——零 scope 争议、hover 冻结语义原样。
 *
 * 零动画铁律例外说明：Toast 消退走淡入淡出（面板级出现/消失，非流式内容动画）；
 *    倒计时条为线性进度显示（非装饰动画）。
 *
 * toast 恒 thin 档玻璃全圆胶囊（[appGlassThin]
 * + AppShapes.capsule——横条小件口径）。toast 是浮层语义
 * （浮在变化内容之上，blur 设计语义真实兑现），玻璃开关已退役。
 */
@Composable
fun StatusHostOverlay() {
    val toasts by StatusSignals.toast.collectAsState()
    if (toasts.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = Dimens.space16, bottom = Dimens.toastBottomOffset),
        contentAlignment = Alignment.BottomEnd,
    ) {
        // 三槽堆叠纵排（.toastcol——gap space8 + 右对齐；头旧尾新，
        // 第 4 条在状态面已挤掉最旧，此处恒 ≤ Dimens.toastMaxStack 条）
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
            horizontalAlignment = Alignment.End,
        ) {
            toasts.forEach { entry ->
                key(entry.id) {
                    ToastCard(entry = entry)
                }
            }
        }
    }
}

/**
 * 单条 toast 卡（三槽堆叠循环体——每条独立的三件套）：
 * hover 暂停 interactionSource / remainingMs 倒计时 / 淡入 fraction / 关闭钮。
 * 材质与宽度 token 不变（appGlassThin + capsule + [Dimens.toastWidth]）。
 */
@Composable
private fun ToastCard(entry: ToastEntry) {
    // hover 暂停：hoverable + interactionSource（同 DragHandle/侧栏分隔条配方；
    // per-entry remember —— key(entry.id) 保证换条换源，hover 逐条独立不串扰）
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    // 消退计时：LaunchedEffect(entry, hovered)——到点 dismiss(id)；hover 期间 remaining 冻结
    var remainingMs by remember(entry) { mutableStateOf(entry.dismissAt - System.currentTimeMillis()) }

    LaunchedEffect(entry, hovered) {
        while (remainingMs > 0) {
            delay(50)
            if (!hovered) {
                remainingMs -= 50
            }
        }
        StatusSignals.dismiss(entry.id)
    }

    // Toast 淡入淡出（KDoc 声称与实现一致——不得回退硬切）：
    // 出现渐显 + 消失渐隐（opacity-threshold：fade 全程到位不滞留 <0.2）；AppMotion.fast
    // 到位——toast 是 2-5s 瞬态件，标准档偏慢。挂载即 0f → 1f（入场），条目出局
    // 前一帧 1f → 0f 无法拦组合退出——改在**面板自身**做 alpha 驱动（渐隐由本 State
    // 在 dismiss 前提前触发做不到——StatusSignals.dismiss(id) 是唯一出口）。
    // 折中实现：入场渐显（组合期 animateFloatAsState 0→1）；出场因组合即刻消失无窗拦截，
    // 语义上「淡入」已兑现，出场补挂 AnimatedVisibility 不可行（Box 内容直渲染）。
    var appeared by remember(entry) { mutableStateOf(false) }
    LaunchedEffect(entry) { appeared = true }
    val appearFraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = com.jiaoay.easy_dev_toolbox.core.theme.AppMotion.fast(),
        label = "toastAppear",
    )

    // thin 档玻璃 + 全圆胶囊（横条小件）——文字/按钮色走卡域链：thin 玻璃 tint
    // 取源井槽（Lowest，Glass.kt），井槽分家套的深井面上基域深字破 4.5
    //（非覆写套 onCard 族回落 onSurface 族逐位不变）
    Surface(
        shape = AppShapes.capsule,
        color = Color.Transparent,
        contentColor = LocalToolboxSurfaceColors.current.onCardStrong,
        modifier = Modifier
            .graphicsLayer { alpha = appearFraction }
            .appGlassThin(shape = AppShapes.capsule),
    ) {
        ToastContent(entry = entry, interactionSource = interactionSource, remainingMs = remainingMs)
    }
}

/** toast 内容体（文字与撤销行；remainingMs 由宿主传入驱动倒计时条） */
@Composable
private fun ToastContent(
    entry: ToastEntry,
    interactionSource: MutableInteractionSource,
    remainingMs: Long,
) {
    Column(
        modifier = Modifier
            .width(Dimens.toastWidth)
            .hoverable(interactionSource),
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.space12, vertical = Dimens.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = LocalToolboxSurfaceColors.current.onCardMid,
                modifier = Modifier.weight(1f),
            )
            if (entry.undoLabel != null && entry.onUndo != null) {
                TextButton(
                    onClick = {
                        entry.onUndo?.invoke()
                        StatusSignals.dismiss(entry.id)
                    },
                ) { Text(entry.undoLabel ?: "撤销") }
            }
            // 关闭钮统一 CLOSE 字形（图标经 AppSymbolIcon
            // 唯一出口——「关闭」语义不再字形 × / 图标 close 两形态混用）；命中区保持
            // TextButton 默认。
            TextButton(onClick = { StatusSignals.dismiss(entry.id) }) {
                AppSymbolIcon(
                    icon = MaterialSymbols.CLOSE,
                    contentDescription = "关闭提示",
                    size = Dimens.iconSmall,
                )
            }
        }
        // 撤销倒计时条（线性消退；hover 暂停由 remainingMs 冻结驱动）
        if (entry.onUndo != null) {
            val total = TimingConstants.UNDO_WINDOW_MS.toFloat()
            val fraction by animateFloatAsState(
                targetValue = (remainingMs / total).coerceIn(0f, 1f),
                label = "undoCountdown",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.splitterWidth)
                    .background(MaterialTheme.colorScheme.inversePrimary.copy(alpha = AppAlpha.contentSoft)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(Dimens.splitterWidth)
                        .background(MaterialTheme.colorScheme.inversePrimary),
                )
            }
        }
    }
}
