package com.jiaoay.easy_dev_toolbox.widget

import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.widget.interactions.onEnterKey

/**
 * 通用单行重命名对话框（由 LogWorkbenchPage 与 FlowPage 两份逐字相同的私有实现收敛下沉 :ui）。
 *
 * 输入框挂 [onEnterKey]（SubmitKey 单行语义默认档，含 NumPadEnter）——
 * Enter 即保存并关闭（补齐单行对话框族键盘流，AskCard/PathConfirmCard 同族先例；
 * run 重命名与 flow 重命名两个消费点自动获得）；Esc 关闭不保存（AlertDialog 默认
 * 行为零改动）。另补 fillMaxWidth（长标题重命名可视性——非卡面要求，裁量补齐）。
 *
 * 空标题收口（对话框侧单点拦截——VM 对空白标题静默 no-op 的无效提交面就此
 * 消失）：保存钮空值禁用 + Enter 空值不提交不关窗（非空即恢复可存）。
 */
@Composable
fun RenameDialog(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    // 提交门（保存钮与 Enter 共用单点判定——两通道不漂移）；VM 对空白标题
    // 静默 return 的语义在对话框侧收口为「禁提交」，VM 零改动
    val canSave = text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        // 与 AppConfirmDialog 同族同档——统一 shape(r-xl 20)/containerColor/发丝定缘
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onEnterKey { if (canSave) { onSave(text); onDismiss() } },
                )
                // 键面提示行（PathConfirmCard 同配方——labelSmall + onSurfaceVariant + .kbd 键帽）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Dimens.space4),
                ) {
                    KbdText("Enter")
                    Text(" 保存 · ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    KbdText("Esc")
                    Text(" 取消", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onSave(text); onDismiss() },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
