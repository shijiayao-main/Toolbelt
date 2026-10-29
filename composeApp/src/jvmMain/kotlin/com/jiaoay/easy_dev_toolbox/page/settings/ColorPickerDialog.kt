package com.jiaoay.easy_dev_toolbox.page.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import kotlin.math.roundToInt
import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim

/**
 * 颜色选择对话框：HSV 色相条 + 饱和度/明度方形渐变区 + Hex 输入。
 * 确认回调 [onConfirm] 返回 ARGB [Color]。
 */
@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onConfirm: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialHsv = remember { rgbToHsv(initialColor) }

    var hue by remember { mutableFloatStateOf(initialHsv[0]) }        // 0..360
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) } // 0..1
    var value by remember { mutableFloatStateOf(initialHsv[2]) }      // 0..1

    // Hex 文本与 HSV 双向同步：用户编辑 hex 时解析回写 HSV；HSV 变化时刷新显示
    var hexEditing by remember { mutableStateOf(false) }
    var hexText by remember { mutableStateOf(colorToHex(currentColor(hue, saturation, value))) }

    fun syncHexFromHsv() {
        if (!hexEditing) {
            hexText = colorToHex(currentColor(hue, saturation, value))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = { Text("选择主题颜色", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space16)) {
                // 预览
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(currentColor(hue, saturation, value))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Text(
                        text = "拖动选取种子色，将据此生成整套深浅配色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Dimens.space12).weight(1f),
                    )
                }

                // 饱和度/明度方形渐变区
                SaturationValueArea(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onChange = { s, v ->
                        saturation = s.coerceIn(0f, 1f)
                        value = v.coerceIn(0f, 1f)
                        syncHexFromHsv()
                    },
                )

                // 色相条
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .clip(AppShapes.small)
                            .background(hueRainbowBrush()),
                    )
                    Slider(
                        value = hue,
                        onValueChange = {
                            hue = it
                            syncHexFromHsv()
                        },
                        valueRange = 0f..360f,
                    )
                }

                // Hex 输入
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        hexText = input
                        parseHex(input)?.let { argb ->
                            val hsv = rgbToHsv(Color(argb))
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                        }
                    },
                    label = { Text("Hex（如 #4F55BA）") }, // 示例文案随 Default seed 同步（seed 变更须同改）
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(currentColor(hue, saturation, value)) }) {
                Text("应用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun currentColor(hue: Float, saturation: Float, value: Float): Color =
    Color.hsv(hue, saturation, value)

// ── 饱和度/明度渐变区 ──

@Composable
private fun SaturationValueArea(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    fun fractionsAt(offset: androidx.compose.ui.geometry.Offset): Pair<Float, Float> {
        if (areaSize.width == 0 || areaSize.height == 0) return saturation to value
        val s = (offset.x / areaSize.width).coerceIn(0f, 1f)
        val v = 1f - (offset.y / areaSize.height).coerceIn(0f, 1f)
        return s to v
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .onSizeChanged { areaSize = it }
            .clip(AppShapes.medium)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.hsv(hue, 0f, 1f), Color.hsv(hue, 1f, 1f))
                )
            )
            .pointerInput(hue) {
                detectTapGestures { offset ->
                    val (s, v) = fractionsAt(offset)
                    onChange(s, v)
                }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val (s, v) = fractionsAt(change.position)
                    onChange(s, v)
                }
            },
    ) {
        // 明度垂直渐变叠加（透明到黑）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
        )
        // 指示圈：位置 = (s, 1-v) 分数 × 区域尺寸
        if (areaSize.width > 0) {
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { (saturation * areaSize.width - 8).toDp() },
                        y = with(density) { ((1f - value) * areaSize.height - 8).toDp() },
                    )
                    .size(16.dp)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
    }
}

// ── 色相彩虹渐变 ──

private fun hueRainbowBrush(): Brush = Brush.horizontalGradient(
    colors = List(13) { i -> Color.hsv(i * 30f, 1f, 1f) },
)

// ── HSV / Hex 转换（纯 Kotlin，避免 AWT/Android 依赖差异）──

/** RGB(0..1) → [H(0..360), S(0..1), V(0..1)] */
internal fun rgbToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0) it + 360f else it }

    val s = if (max == 0f) 0f else delta / max
    return floatArrayOf(h, s, max)
}

internal fun colorToHex(color: Color): String {
    val rgb = ((color.red * 255).roundToInt() shl 16) or
        ((color.green * 255).roundToInt() shl 8) or
        (color.blue * 255).roundToInt()
    return "#%06X".format(rgb)
}

/** "#RRGGBB" → ARGB Int；非法返回 null */
internal fun parseHex(input: String): Int? {
    val trimmed = input.removePrefix("#").trim()
    if (trimmed.length != 6) return null
    return trimmed.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
}
