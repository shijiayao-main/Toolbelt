package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens

/**
 * 带缩放与单位后缀的整数输入框（R-4 必须自写：`:ui` 的 `CommitNumberField` **只吃 Int、无 scale 参数**，
 * 无法承载 `Editor.Number` 的 `Long` 值域 + `displayScale` + `unitSuffix`）。
 *
 * 唯一带 scale 的字段是 `fileMessage.maxFileSizeBytes`（`displayScale=1048576, unitSuffix=" MB"`，
 * 存储字节、显示 MB）——它必须走本组件。全部 19 个 Number 字段的 `max` 均 < `Int.MAX_VALUE`，
 * 但**换算一律在 [Long] 域算完**（[value] / [onCommit] 均为显示域 Long），避免中间态溢出。
 *
 * 交互语义对齐 `CommitNumberField`：**失焦提交**、非数字输入不进编辑态、非法终值回退当前值。
 * 布局：**标签置于字段上方、字段满宽**（同时消解固定标签列宽）。
 *
 * 零白名单：间距只用 [Dimens]，无任何裸 dp 字面量、裸动画规格或裸 alpha 值。
 */
@Composable
fun ScaledNumberField(
    label: String?,
    value: Long,
    onCommit: (Long) -> Unit,
    modifier: Modifier = Modifier,
    unitSuffix: String = "",
    isError: Boolean = false,
    supportingText: String? = null,
) {
    // 曾获焦点标记（语义同 CommitTextField：进入组合时 onFocusChanged 会以 isFocused=false 回调一次）
    var hasBeenFocused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString()) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!isFocused) text = value.toString()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        if (label != null) Text(text = label, style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    // 非数字形态不进入编辑态（负号 / 小数点均不允许，本变体仅非负整数）
                    if (input.all { it.isDigit() }) text = input
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { state ->
                        isFocused = state.isFocused
                        if (state.isFocused) {
                            hasBeenFocused = true
                        } else if (hasBeenFocused) {
                            hasBeenFocused = false
                            val parsed = text.toLongOrNull()
                            if (parsed != null) onCommit(parsed) else text = value.toString()
                        }
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = isError,
            )
            if (unitSuffix.isNotEmpty()) {
                Text(text = unitSuffix.trim(), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
