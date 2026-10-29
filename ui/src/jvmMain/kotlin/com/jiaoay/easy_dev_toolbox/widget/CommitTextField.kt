package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 失焦提交输入框（ConfigTextField 机制的跨页通用化）：
 * 内部持编辑态防抖动重构，失焦时上抛终值，由调用方比较短路（值未变化不落盘）。
 *
 * 行为契约（与 larkbridge 域内 ConfigTextField 逐点一致）：
 * - 每键只更新内部 text（零外抛），不驱动外部状态；
 * - 失焦提交：曾获得焦点且离开时才上抛（防虚拟失焦集体提交）；
 * - 外部 [value] 仅初值/重置语义：未聚焦时回写，聚焦中不劫持输入。
 *
 * [trailingIcon] 尾随槽：透传
 * OutlinedTextField 同名装饰槽，承载字段级行内动作（如 API Key 的明文切换/复制）。
 * 默认 null——全既有消费方零回归；槽内容不参与失焦提交状态机（装饰区点击
 * 不改 isFocused/hasBeenFocused，submit 语义零变化）。
 */
@Composable
fun CommitTextField(
    label: String?,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * label 外置形态（frow 化——「CommitTextField（frow+txt）」组件申报）：
     * false = label 不进框（浮标签退役），
     * 由外层 [FormRow] lab 段承载——supportingText 也上移出框（hint 中段/下方）。
     * 默认 true = 既有纵排形态，全仓既有消费方零回归。
     */
    labelInline: Boolean = true,
    onValueChange: ((String) -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    trailingIcon: (@Composable () -> Unit)? = null,
    /**
     * mono 等宽字形（`.txt.secret { font-family: ui-monospace }`）：密文展示
      * 密文展示恒等宽；默认 false = 既有 bodySmall 零回归（CommitNumberField.monoText 同构先例）。
     */
    monoText: Boolean = false,
) {
    // 曾获焦点标记：进入组合时 onFocusChanged 以 isFocused=false 回调一次，
    // 不判「曾聚焦」会触发「虚拟失焦」集体提交
    var hasBeenFocused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!isFocused) text = value
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            onValueChange?.invoke(input)
        },
        label = if (labelInline) label?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } } else null,
        // focus 态描边 = 2px --outline 灰（非 M3 默认 2dp primary——SearchField 同稿先例）
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LocalToolboxSurfaceColors.current.borderEmphasis,
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) {
                    hasBeenFocused = true
                } else if (hasBeenFocused) {
                    hasBeenFocused = false
                    onCommit(text)
                }
            },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        textStyle = if (monoText) DesktopTypography.mono else MaterialTheme.typography.bodySmall,
        visualTransformation = visualTransformation,
        isError = isError,
        trailingIcon = trailingIcon,
        supportingText = if (labelInline) {
            supportingText?.let { supporting ->
                { Text(supporting, style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            null // frow 形态：supportingText 由外层 FormRow hint 段承载（.frow .hint 中段语义）
        },
    )
}

/**
 * 失焦提交数字输入框：[CommitTextField] 的整型变体（数字键盘、非数字输入不进入编辑态、
 * 非法终值不提交并回退当前值）。
 *
 * [isValid] 判定提交值的合法性（默认恒真）：失焦终值不满足谓词时不提交并回退显示
 * 当前值——「非法终值不提交」对数字形态的非法值（如业务下限 0）同样成立，
 * 否则调用方静默拒绝后输入框显示值与生效值不一致。
 */
@Composable
fun CommitNumberField(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    isValid: (Int) -> Boolean = { true },
    /** label 外置形态（frow 化——同 [CommitTextField.labelInline]）；默认 true 零回归 */
    labelInline: Boolean = true,
    /**
     * mono 等宽字形：数值列恒等宽；默认 false = 既有 bodySmall 零回归。
     */
    monoText: Boolean = false,
) {
    // 曾获焦点标记（语义同 [CommitTextField]）
    var hasBeenFocused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString()) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!isFocused) text = value.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            // 非数字形态不进入编辑态（负号/小数点均不允许，本变体仅整型）
            if (input.all { it.isDigit() }) text = input
        },
        label = if (labelInline) {
            { Text(label, style = MaterialTheme.typography.bodySmall) }
        } else {
            null // frow 形态：label 由外层 FormRow lab 段承载
        },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) {
                    hasBeenFocused = true
                } else if (hasBeenFocused) {
                    hasBeenFocused = false
                    val parsed = text.toIntOrNull()
                    if (parsed != null && isValid(parsed)) {
                        onCommit(parsed)
                    } else {
                        // 非数字或非法值：不提交并回退显示当前生效值
                        text = value.toString()
                    }
                }
            },
        singleLine = true,
        textStyle = if (monoText) DesktopTypography.mono else MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        visualTransformation = VisualTransformation.None,
        isError = isError,
        supportingText = if (labelInline) {
            supportingText?.let { supporting ->
                { Text(supporting, style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            null // frow 形态：supportingText 由外层 FormRow hint 段承载（.frow .hint 中段语义）
        },
    )
}
