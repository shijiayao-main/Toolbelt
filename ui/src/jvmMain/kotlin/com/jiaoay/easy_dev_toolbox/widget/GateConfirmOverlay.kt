package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import java.io.File

/**
 * HITL 围栏确认覆盖层：[PathConfirmCard] 的进出场过渡 + 组合期抢焦装配。
 *
 * 契约：
 * - [visible] 与 [path] 分离：退场动画帧内调用方已把 path 置 null，本件内部缓存
 *   末值非空 path，退场期渲染缓存值防闪空（visible=true 期 path 必非 null）。
 * - [focusRequester] 由调用方持有传入（非空——不传焦点则卡体不挂 focusable，
 *   Enter/Esc 键盘裁决结构性失效）；卡组合时本件主动 requestFocus
 *   （runCatching：组合期挂载未完成会抛错）。卡出现即抢走当前焦点
 *   （HITL 是全局注意抢占语义）；卡消失后焦点不自动归还。
 * - 落位（对齐/限宽/阴影/让位）不做假设，由调用方经 [modifier] 注入到卡容器。
 */
@Composable
fun GateConfirmOverlay(
    visible: Boolean,
    path: File?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var lastGate by remember { mutableStateOf<File?>(null) }
    path?.let { lastGate = it }
    // 高频注意抢占件——突现突消反差突兀，进出场恒 fast 档（120ms）
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = AppMotion.fast()) +
            slideInVertically(animationSpec = AppMotion.fast()) { it / 16 },
        exit = fadeOut(animationSpec = AppMotion.fast()),
    ) {
        val shown = path ?: lastGate
        shown?.let { gate ->
            LaunchedEffect(gate) { runCatching { focusRequester.requestFocus() } }
            Box(modifier = modifier) {
                PathConfirmCard(
                    path = gate,
                    onApprove = onApprove,
                    onDeny = onDeny,
                    focusRequester = focusRequester,
                )
            }
        }
    }
}
