package com.jiaoay.easy_dev_toolbox.widget.interactions

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Enter 语义唯一配方（SubmitKey）：
 * `Modifier.onEnterKey(allowMultiline, submit)`
 *
 * 🔴 onPreviewKeyEvent + KeyDown 过滤（含 NumPadEnter）——一次物理按键派发
 *    KeyDown + KeyUp 两个事件，KeyUp 时 IME 组合可能未提交、丢组合中未上屏的输入；
 *    页面不得自写 Enter 处理。
 */
fun Modifier.onEnterKey(
    allowMultiline: Boolean = false,
    submit: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
    if (!enter) return@onPreviewKeyEvent false
    if (allowMultiline) {
        // 多行：Ctrl+Enter 提交，裸 Enter 放行走换行
        if (event.isCtrlPressed) {
            submit()
            true
        } else {
            false
        }
    } else {
        // 单行：Enter 一律提交并吞掉（防换行副作用）
        submit()
        true
    }
}
