package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 单行矮搜索框统一件——搜索 affordance 唯一出口，新搜索框不得回 M3 OutlinedTextField 手拼。
 * design-language.md §3.3 规格 C 单源：capsule（[AppShapes.capsule]）+ 高 [Dimens.controlStandard] 34dp +
 * [OutlinedTextFieldDefaults.DecorationBox] 定制路径（M3 不暴露 contentPadding、行盒最小 56dp 压不到矮框档）；
 * 禁用必须不可聚焦（`focusProperties { canFocus = enabled }`——禁用仍可聚焦会弹空 caret 误导可输入）。
 * 配方表/描边三态/四处消费方语义见 docs/ui-style-guide.md 4.7n；规格真源 = 仓库内
 * design-language.md（现行重设计稿 01-language 目录）§3.3。
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    leadingIcon: String = MaterialSymbols.SEARCH,
    trailingIcon: @Composable (() -> Unit)? = null,
    /**
     * 控制台深底语境变体：底 = consoleText×0.08 提亮、描边 = consoleText×0.14、
     * 字/图标 = consoleText。消费点 = ShellOutputPanel.OutputFilterBar（整台恒深容器内）。
     */
    consoleContext: Boolean = false,
    /**
     * 失焦提交回调（检索语义 = 失焦提交非每键上抛，CommitTextField 同款
     * hasBeenFocused 门控——防虚拟失焦集体提交）。null（默认）= 纯即时过滤形态，
     * 全既有消费方零回归。
     */
    onCommit: ((String) -> Unit)? = null,
) {
    val surface = LocalToolboxSurfaceColors.current
    val toolboxColors = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors()

    // 失焦提交状态机（CommitTextField 同款配方）：曾获焦点且离开时才上抛终值
    if (onCommit != null) {
        var hasBeenFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(focused) {
            if (!focused && hasBeenFocused) {
                hasBeenFocused = false
                onCommit(value)
            } else if (focused) {
                hasBeenFocused = true
            }
        }
    }

    // 文字颜色对齐 OutlinedTextField 官方装配：textStyle 未指定色时以 contentColor 兜底
    val contentColor = when {
        consoleContext -> toolboxColors.consoleText
        !enabled -> surface.onSurfaceStrong.copy(alpha = AppAlpha.contentDisabled)
        else -> surface.onSurfaceStrong
    }
    val textColor = textStyle.color.takeOrElse { contentColor }
    val mergedTextStyle = textStyle.copy(color = textColor)

    // 描边三态：默认 1dp 发丝 / focus 2dp borderEmphasis（规格 C）/ 禁用 borderSubtle 退隐；
    // console 语境：描边 = consoleText×0.14（color-mix 14% 配比）
    val borderStroke = when {
        consoleContext -> BorderStroke(
            Dimens.glassStrokeHairline,
            toolboxColors.consoleText.copy(alpha = AppAlpha.consoleFieldBorder),
        )
        !enabled -> BorderStroke(Dimens.glassStrokeHairline, surface.borderSubtle)
        focused -> BorderStroke(Dimens.strokeThin, surface.borderEmphasis)
        else -> BorderStroke(Dimens.glassStrokeHairline, surface.borderHairline)
    }
    // enabled 底 = --surface 实底（页面基底同档，透明底会在卡面/深浅语境下取色漂移）；
    // console 语境 = consoleText×0.08
    val containerColor = when {
        consoleContext -> toolboxColors.consoleText.copy(alpha = AppAlpha.consoleFieldBg)
        enabled -> surface.surface
        else -> surface.surfaceDeep
    }

    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .height(Dimens.controlStandard)
                .focusProperties { canFocus = enabled },
            enabled = enabled,
            readOnly = false,
            textStyle = mergedTextStyle,
            cursorBrush = SolidColor(if (enabled) colors.cursorColor(isError = false) else Color.Transparent),
            singleLine = true,
            maxLines = 1,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    isError = false,
                    placeholder = if (placeholder.isEmpty()) {
                        null
                    } else {
                        {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (enabled) surface.onSurfaceFaint else contentColor,
                                maxLines = 1,
                            )
                        }
                    },
                    leadingIcon = {
                        AppSymbolIcon(
                            icon = leadingIcon,
                            contentDescription = null,
                            size = Dimens.iconSmall,
                            tint = contentColor,
                        )
                    },
                    trailingIcon = trailingIcon,
                    colors = colors,
                    // 矮框档：上下 space8（行盒 24dp 撑底 ⇒ 34dp 高内不裁字），
                    // 左右 space16 对齐 8×16 规格内边距
                    contentPadding = PaddingValues(
                        start = Dimens.space16,
                        end = Dimens.space16,
                        top = Dimens.space8,
                        bottom = Dimens.space8,
                    ),
                    // 三态自绘 container（覆写 M3 默认 outline 边框）；container 由
                    // material3 内部布局（ContainerId）钉到内容全宽高
                    container = {
                        Box(
                            modifier = Modifier
                                .background(color = containerColor, shape = AppShapes.capsule)
                                .border(border = borderStroke, shape = AppShapes.capsule),
                        )
                    },
                )
            },
        )
    }
}
