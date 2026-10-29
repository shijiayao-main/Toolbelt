package com.jiaoay.easy_dev_toolbox.widget

import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim

/**
 * 通用确认对话框：「标题 + 说明 + 确认/取消」既有手写样板（4+ 处）的收敛。
 *
 * - [isDangerous] = true 时确认按钮用 error 色（删除/清空类操作）；
 * - [confirmLabel] / [dismissLabel] 缺省「确认 / 取消」。
 */
@Composable
fun AppConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isDangerous: Boolean = false,
    confirmLabel: String = "确认",
    dismissLabel: String = "取消",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = modifier.appDialogRim(),
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text = text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            // 危险主钮（fbtn.err 形态）=
            // **实底 error 胶囊**（containerColor=error / contentColor=onPrimary 白）——
            // TextButton error 字色是「危险伪装成次钮」——fbtn.err 是实底主钮形态，危险动作须够重
            if (isDangerous) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = AppShapes.capsule,
                ) {
                    Text(text = confirmLabel)
                }
            } else {
                TextButton(onClick = onConfirm) { Text(text = confirmLabel) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = dismissLabel) }
        },
    )
}
