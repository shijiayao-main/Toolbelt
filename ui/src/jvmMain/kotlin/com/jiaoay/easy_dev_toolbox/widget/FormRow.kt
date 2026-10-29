package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.WindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.currentWindowSizeClass

/**
 * 表单横排三段行（.frow 三段形态）：
 * lab 段 190dp 定宽（fs-13 SemiBold，flex:none）+ hint 中段（fs-11 弱档）+
 * ctl 右缘控件（[content] 居右）。
 *
 * 消费面：SYS 域设置页/AI 设置表单（六行 frow 结构 + note-zone
 * 组件规格「CommitTextField（frow+txt）/ AppSwitchRow（frow+switch）」）；
 * 桥接域 ConfigFieldRenderer 横排三段先例平移（FieldRowLabel/FieldRowHint 同配方）。
 *
 * 表单行统一横排承载（M3 OutlinedTextField 纵排浮框形态不入表单行语言）；
 * [hint] 为 null 时中段空置（ctl 仍居右）。
 *
 * 窄窗回落（[WindowSizeClass.Compact] 档）：三段横排是宽窗语言——lab 190 +
 * ctl 220 + 行尾件三段定宽在窄窗下中段 hint 被挤成一字一行的竖列、行尾 chip
 * 压成发丝竖条，故 Compact 档整行回落纵排：lab + hint 全宽在上、
 * ctl 与 [trailing] 同排沉底。Medium 及以上横排形态不变。
 */
@Composable
fun FormRow(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    hintError: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (currentWindowSizeClass() == WindowSizeClass.Compact) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            // hint（.frow .hint fs-11 on-surface-var / 错误红）——纵排下全宽直铺
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hintError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                content()
                if (trailing != null) {
                    trailing()
                }
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // lab 段（.frow .lab width 190px + fs-13 SemiBold flex:none）
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.width(Dimens.fieldLabelWidth),
            )
            // hint 中段（.frow .hint fs-11 on-surface-var / 错误红）
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hintError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f))
            }
            // ctl 右缘（flex:1 居右；无 trailing 时直接收尾）
            Box(contentAlignment = Alignment.CenterEnd) { content() }
            if (trailing != null) {
                trailing()
            }
        }
    }
}
