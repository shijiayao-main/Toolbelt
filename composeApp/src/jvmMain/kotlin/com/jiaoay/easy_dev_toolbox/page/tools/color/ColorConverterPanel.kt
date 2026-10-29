package com.jiaoay.easy_dev_toolbox.page.tools.color

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton

/**
 * 颜色转换面板：「工具」聚合页子签内容体（聚合页原生签——无 palette 深入
 * 模式候选、无持久化，全部状态会话内）。
 *
 * 三框即时互转：任一框输入合法色值即把另两框同步为该色的规范形态（全联动，
 * 无需点换算钮）；无效输入在该框下方给人话回执，另两框保持上一次合法值
 * （错一处不连坐清空）。附色样块（HUD/UI 调色直观看深浅）与 logcat 带符号
 * int 口径行（设备日志打印形态直接复制比对）。
 *
 * 视觉层（三框排布、色样块尺寸）为保守默认，待真机终裁。
 */
@Composable
fun ColorConverterPanel() {
    var argbText by remember { mutableStateOf("") }
    var hexText by remember { mutableStateOf("") }
    var rgbText by remember { mutableStateOf("") }
    var argbError by remember { mutableStateOf<String?>(null) }
    var hexError by remember { mutableStateOf<String?>(null) }
    var rgbError by remember { mutableStateOf<String?>(null) }
    // 当前色（三框最近一次合法解析的合流态；色样块与 int 口径行的单一真源）
    var components by remember { mutableStateOf<ColorComponents?>(null) }

    fun applyComponents(parsed: ColorComponents) {
        components = parsed
        argbText = toHexLiteral(parsed)
        hexText = toHexHash(parsed)
        rgbText = if (parsed.alpha == 255) toRgbTriple(parsed) else toArgbTriple(parsed)
        argbError = null
        hexError = null
        rgbError = null
    }

    fun clearAll() {
        argbText = ""
        hexText = ""
        rgbText = ""
        argbError = null
        hexError = null
        rgbError = null
        components = null
    }

    SectionCard(
        title = "颜色互转",
        description = "任一框输入即全联动；ARGB int 支持 0x/# 十六进制与带符号十进制",
        headerTrailing = {
            PillLabel(
                text = "Android ARGB 三向",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        ColorFieldRow(
            label = "ARGB int（代码 / logcat）",
            placeholder = "如 0xFF1B6EF3、-14979341 或 4279987955",
            value = argbText,
            error = argbError,
            onValueChange = { text ->
                argbText = text
                when (val parsed = parseArgbIntText(text)) {
                    is ColorParseResult.Empty -> clearAll()
                    is ColorParseResult.Ok -> applyComponents(parsed.components)
                    is ColorParseResult.Invalid -> {
                        components = null
                        argbError = parsed.reason
                    }
                }
            },
        )
        ColorFieldRow(
            label = "#hex（设计稿）",
            placeholder = "如 #FF1B6EF3 或 #1B6EF3（6 位 alpha 默认 FF）",
            value = hexText,
            error = hexError,
            onValueChange = { text ->
                hexText = text
                when (val parsed = parseHexText(text)) {
                    is ColorParseResult.Empty -> clearAll()
                    is ColorParseResult.Ok -> applyComponents(parsed.components)
                    is ColorParseResult.Invalid -> {
                        components = null
                        hexError = parsed.reason
                    }
                }
            },
        )
        ColorFieldRow(
            label = "(r, g, b)（分量）",
            placeholder = "如 (27, 110, 243) 或 (255, 27, 110, 243)",
            value = rgbText,
            error = rgbError,
            onValueChange = { text ->
                rgbText = text
                when (val parsed = parseRgbText(text)) {
                    is ColorParseResult.Empty -> clearAll()
                    is ColorParseResult.Ok -> applyComponents(parsed.components)
                    is ColorParseResult.Invalid -> {
                        components = null
                        rgbError = parsed.reason
                    }
                }
            },
        )

        components?.let { parsed ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                // 色样块：HUD/UI 调色直观看深浅（透明度以色块直接呈现）
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(Dimens.iconDisplay)
                        .background(Color(parsed.toArgbInt()), shape = AppShapes.small),
                )
                Text(
                    text = "带符号 int: ${toSignedInt(parsed)}",
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                CopyIconButton(
                    textProvider = { toSignedInt(parsed).toString() },
                    contentDescription = "复制带符号 int（logcat 打印口径）",
                )
            }
        }
    }
}

/** 单框行：输入框 + 框下人话错误回执（错一处只标注一处） */
@Composable
private fun ColorFieldRow(
    label: String,
    placeholder: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = DesktopTypography.monoInput,
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
