package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import java.util.Locale
import com.jiaoay.toolbelt.device.PerfHistorySnapshot
import com.jiaoay.toolbelt.device.ProcessEntry

/**
 * 性能曲线折线图 + 进程排行显示纯函数层：映射函数零依赖 Compose（jvmTest 直测），
 * [PerfChart] 只做画布绘制。折线几何语义（30 分钟磁带式时间轴）：
 * - x 轴以环形容量定槽（步长 = 宽度/(容量−1)），最新点恒贴右缘——曲线从右侧
 *   生长，滚动窗期满后整体左移；
 * - y 轴值域恒 0–100（CPU% 与内存 used% 同轴），上缘 = 100；
 * - 采样失败拍留洞（null）——洞处断线成段，禁把失败伪装成 0 画假谷。
 */
internal fun chartPolylineSegments(
    values: List<Float?>,
    width: Float,
    height: Float,
    capacity: Int,
): List<List<Pair<Float, Float>>> {
    if (width <= 0f || height <= 0f || capacity < 2 || values.isEmpty()) return emptyList()
    val step = width / (capacity - 1)
    val segments = mutableListOf<MutableList<Pair<Float, Float>>>()
    // 段连续判据：前一个下标刚入段（洞后开新段；洞在首部/尾部不留空段）
    var lastAddedIndex = -1
    values.forEachIndexed { i, v ->
        if (v == null) return@forEachIndexed
        val x = width - (values.size - 1 - i) * step
        val clamped = v.coerceIn(0f, 100f)
        val y = height - clamped / 100f * height
        val current = segments.lastOrNull()
        if (lastAddedIndex == i - 1 && current != null) {
            current += x to y
        } else {
            segments += mutableListOf(x to y)
        }
        lastAddedIndex = i
    }
    return segments
}

/** 进程排行排序纯函数：CPU% 降序（top 原生序）或 RSS 降序（消费面切换） */
internal fun sortProcesses(entries: List<ProcessEntry>, byCpu: Boolean): List<ProcessEntry> =
    if (byCpu) entries.sortedByDescending { it.cpuPercent } else entries.sortedByDescending { it.rssKb }

/** RSS 显示文本（kB → 缩写，与设备侧 top 的 G/M 缩写同口径 1024 进制） */
internal fun rssHumanText(rssKb: Long): String = when {
    rssKb >= 1L shl 20 -> trimFloat(rssKb / (1L shl 20).toFloat()) + "G"
    rssKb >= 1L shl 10 -> trimFloat(rssKb / (1L shl 10).toFloat()) + "M"
    else -> "${rssKb}K"
}

private fun trimFloat(v: Float): String =
    String.format(Locale.ROOT, "%.1f", v)

/** 进程行显示文本（等宽对齐：pid/cpu/rss 定宽 + 名字收尾） */
internal fun processRowText(entry: ProcessEntry): String =
    String.format(Locale.ROOT, "%6d  %5.1f%%  %6s  %s", entry.pid, entry.cpuPercent, rssHumanText(entry.rssKb), entry.name)

/**
 * CPU/内存/FPS 三曲线画布：CPU 走 primary 实线、内存走 tertiary 实线、FPS 走
 * secondary 点列（域区分 = 色 + 线型双编码，零硬编码色；fps 不用虚线——
 * dashPathEffect 由 decor 域单点收口，曲线域不越界借用，点列是 Canvas 原生
 * drawCircle 的保守编码）。空历史 = 仅底缘基准线（无会话首拍前的空态，不画
 * 假线）。fps 域经 [chartPolylineSegments] 进 0–100 带（超带上缘的高刷读数按
 * 顶缘呈现）。视觉层为保守默认，真机手感由维护者终裁。
 */
@Composable
internal fun PerfChart(history: PerfHistorySnapshot, modifier: Modifier = Modifier) {
    val cpuColor = MaterialTheme.colorScheme.primary
    val memColor = MaterialTheme.colorScheme.tertiary
    val fpsColor = MaterialTheme.colorScheme.secondary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.chartHeight),
    ) {
        val stroke = Dimens.strokeThin.toPx()
        val w = size.width
        val h = size.height
        drawLine(trackColor, Offset(0f, h - stroke / 2), Offset(w, h - stroke / 2), stroke)
        drawPolyline(chartPolylineSegments(history.points.map { it.cpuPercent }, w, h, history.capacity), cpuColor, stroke)
        drawPolyline(chartPolylineSegments(history.points.map { it.memUsedPercent }, w, h, history.capacity), memColor, stroke)
        drawDotSeries(chartPolylineSegments(history.points.map { it.fps }, w, h, history.capacity), fpsColor, stroke)
    }
}

// ── 多曲线形态（多会话：每 serial 一色，CPU 实线 / 内存同色淡化 / FPS 同色点列）──

/** 多曲线序列输入：一个 serial 的 CPU/内存/FPS 三条值序列 + 该 serial 曲线容量档 */
internal data class PerfCurveSeries(
    val serial: String,
    val cpu: List<Float?>,
    val mem: List<Float?>,
    val fps: List<Float?>,
    val capacity: Int,
)

/** 多曲线几何输出：serial + 语义色板槽位 + CPU/内存/FPS 三组折线段 */
internal data class PerfCurveGeometry(
    val serial: String,
    val colorSlot: Int,
    val cpuSegments: List<List<Pair<Float, Float>>>,
    val memSegments: List<List<Pair<Float, Float>>>,
    val fpsSegments: List<List<Pair<Float, Float>>>,
)

/**
 * serial → 语义色板槽位：本批 serial 排序序位（排序消输入顺序抖动——同一批
 * serial 集在多次状态发布间槽位稳定）。曲线画布与图例色标共用本槽位函数，
 * 逐台对色不漂移。
 */
internal fun perfColorSlot(serials: List<String>, serial: String): Int =
    serials.sorted().indexOf(serial).coerceAtLeast(0)

/**
 * 多曲线映射纯函数：逐 serial 逐序列（CPU/内存/FPS）走 [chartPolylineSegments]
 * （磁带时间轴/洞断段语义每序列各自成立——serial 间的洞与域间的洞互不影响）；
 * [PerfCurveGeometry.colorSlot] 由 [perfColorSlot] 产槽位不产色值（色板档位折叠
 * 由消费面定）。
 */
internal fun multiChartCurves(
    series: List<PerfCurveSeries>,
    width: Float,
    height: Float,
): List<PerfCurveGeometry> {
    val serials = series.map { it.serial }
    return series.map { s ->
        PerfCurveGeometry(
            serial = s.serial,
            colorSlot = perfColorSlot(serials, s.serial),
            cpuSegments = chartPolylineSegments(s.cpu, width, height, s.capacity),
            memSegments = chartPolylineSegments(s.mem, width, height, s.capacity),
            fpsSegments = chartPolylineSegments(s.fps, width, height, s.capacity),
        )
    }
}

/** serial 尾号显示上限（长串号取尾段保辨识；短串号原样） */
internal const val SERIAL_TAIL_MAX = 6

/** serial 尾号显示文本：超 [SERIAL_TAIL_MAX] 取尾段并加省略号前缀（图例辨识用，非完整串号） */
internal fun serialTailLabel(serial: String): String =
    if (serial.length <= SERIAL_TAIL_MAX) serial else "…" + serial.takeLast(SERIAL_TAIL_MAX)

/**
 * 多曲线画布（多会话态）：每 serial 一色（chartPalette 语义分类色板——环形图消费
 * 同槽位），CPU 实线 / 内存同色淡化（[AppAlpha.contentFaint]）/ FPS 同色点列
 * ——hue 分 serial、线型分域，双轴多设备下不靠硬编码色；serial 数超色板档位
 * 折叠 onSurfaceVariant 灰槽。视觉层为保守默认，真机手感由维护者终裁。
 */
@Composable
internal fun PerfChartMulti(series: List<PerfCurveSeries>, modifier: Modifier = Modifier) {
    val palette = LocalEasyToolboxColors.current.chartPalette
    val overflowColor = MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.chartHeight),
    ) {
        val stroke = Dimens.strokeThin.toPx()
        val w = size.width
        val h = size.height
        drawLine(trackColor, Offset(0f, h - stroke / 2), Offset(w, h - stroke / 2), stroke)
        multiChartCurves(series, w, h).forEach { curve ->
            val color = if (curve.colorSlot < palette.size) palette[curve.colorSlot] else overflowColor
            drawPolyline(curve.cpuSegments, color, stroke)
            drawPolyline(curve.memSegments, color.copy(alpha = AppAlpha.contentFaint), stroke)
            drawDotSeries(curve.fpsSegments, color, stroke)
        }
    }
}

/**
 * 点列绘制（fps 域线型）：复用 [chartPolylineSegments] 的映射与洞语义，逐采样点
 * 画圆点不连线（洞处无点）。dashPathEffect 由 decor 域单点收口，曲线域的点列
 * 是零越界的保守编码。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDotSeries(
    segments: List<List<Pair<Float, Float>>>,
    color: androidx.compose.ui.graphics.Color,
    stroke: Float,
) {
    segments.forEach { segment ->
        segment.forEach { (x, y) ->
            drawCircle(color, radius = stroke, center = Offset(x, y))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolyline(
    segments: List<List<Pair<Float, Float>>>,
    color: androidx.compose.ui.graphics.Color,
    stroke: Float,
) {
    segments.forEach { segment ->
        if (segment.size == 1) {
            drawCircle(color, radius = stroke, center = Offset(segment[0].first, segment[0].second))
            return@forEach
        }
        val path = Path()
        segment.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
    }
}
