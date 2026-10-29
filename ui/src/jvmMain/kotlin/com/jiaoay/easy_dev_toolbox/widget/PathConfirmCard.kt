package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import java.io.File

/**
 * HITL 围栏外路径确认卡。
 *
 * ## 形态（统一非模态卡）
 * 桌面多页签工作流下模态打断全局操作过重，非模态卡不打断工作流且可键盘裁决。
 * Esc 拒绝在本卡是**真实实现**（非提示性假声明）。
 */
@Composable
fun PathConfirmCard(
    path: File,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val colors = LocalEasyToolboxColors.current
    TonalCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester).focusable()
                } else {
                    Modifier
                },
            )
            // P4 · 键盘裁决：Enter 允许 / Esc 拒绝（卡级 onPreviewKeyEvent，随卡组合存在）
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { onApprove(); true }
                    Key.Escape -> { onDeny(); true }
                    else -> false
                }
            },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // warn 左缘警示条（border-left 3px var(--warn)；fillMaxHeight
            // 撑满行高——3px 条即整卡高）；
            // 昼战坐 warningContainer 档（design-doc.md §5.3，dayBattleTint 图形 3.0 线——warning 深档
            // 直落昼卡 ~1.1 不可见）
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(Dimens.configModifiedBarWidth)
                    .background(
                        color = dayBattleTint(colors.warning, colors.warningContainer),
                        shape = AppShapes.check,
                    ),
            ) {}
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Dimens.space12),
                verticalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                // 卡头行：标题 + 「围栏外」risk 徽标（.risk：error 字 + errorContainer 底）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    Text(
                        "路径授权",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    PillLabel(
                        text = "围栏外",
                        // 固定槽直取（同 StatusBanner Error 槽）
                        container = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current.errorContainerFixed,
                        contentColor = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
                Text(
                    path.absolutePath,
                    style = DesktopTypography.mono,
                )
                Text(
                    "批准后本会话内该路径免确认；拒绝则工具跳过该访问继续执行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Dimens.space4),
                ) {
                    // 主次分层：允许 = pri 主钮 / 拒绝 = Secondary
                    AppButton(label = "允许", busy = false, onClick = onApprove)
                    AppButton(
                        label = "拒绝",
                        busy = false,
                        kind = AppButtonKind.Secondary,
                        onClick = onDeny,
                    )
                    // 键面提示 = 键帽形态（.kbd）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                    ) {
                        KbdText("Enter")
                        Text("允许 · ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        KbdText("Esc")
                        Text(" 拒绝（非模态——不打断工作流）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
