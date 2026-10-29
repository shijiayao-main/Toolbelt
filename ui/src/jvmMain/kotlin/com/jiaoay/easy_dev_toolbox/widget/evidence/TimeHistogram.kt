package com.jiaoay.easy_dev_toolbox.widget.evidence

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.jiaoay.easy_dev_toolbox.core.evidence.LogLine
import com.jiaoay.easy_dev_toolbox.core.evidence.histogramBuckets
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors

/**
 * 证据流直方图子系统：主直方图 [TimeHistogram]（一次 Canvas 入场 + 桶点击跳时段）
 * 与块头迷你形态 [MiniHistogram]，共享 [drawBuckets] 绘制内核与 60 桶分桶常量。
 * 桶数常量与 :core histogramBuckets 默认分桶数同源锚定（分桶参数变化须同步镜像）。
 */
@Composable
fun TimeHistogram(
    lines: List<LogLine>,
    onPickTimestamp: (String) -> Unit = {},
) {
    val buckets = remember(lines) { histogramBuckets(lines) }
    // 桶 → 首条时间戳映射：一次性预构，点击时零重复扫描
    val bucketFirstTimestamps = remember(lines) { bucketFirstTimestamps(lines) }
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { target = 1f }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = AppMotion.standard(),
        label = "histogramEntrance",
    )
    val barColor = LocalEasyToolboxColors.current.logInfo
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.workbenchHistogramHeight)
            .pointerInput(lines) {
                detectTapGestures { offset ->
                    val idx = (offset.x / size.width * HISTOGRAM_BUCKET_COUNT)
                        .toInt()
                        .coerceIn(0, HISTOGRAM_BUCKET_COUNT - 1)
                    // 空桶/桶内无时间戳行（全部不可解析）= 无可跳目标不上抛
                    bucketFirstTimestamps[idx]?.let(onPickTimestamp)
                }
            },
    ) {
        drawBuckets(buckets, progress, barColor, trackColor, size.width, size.height)
    }
}

/** [TimeHistogram] 桶数常量——与 :core histogramBuckets 默认分桶数同源锚定（60 等距） */
private const val HISTOGRAM_BUCKET_COUNT = 60

private fun bucketFirstTimestamps(lines: List<LogLine>): Array<String?> {
    val times = lines.mapNotNull { it.timestamp }.mapNotNull { parseHistogramEpochMillis(it) }
    val result = arrayOfNulls<String?>(HISTOGRAM_BUCKET_COUNT)
    if (times.isEmpty()) return result
    val min = times.min()
    val max = times.max()
    val span = (max - min).coerceAtLeast(1L)
    // 桶首条 = 按行序首个落入该桶的时间戳行（与 histogramBuckets 计数同序扫描）
    lines.forEach { line ->
        val timestamp = line.timestamp ?: return@forEach
        val t = parseHistogramEpochMillis(timestamp) ?: return@forEach
        val idx = (((t - min) * HISTOGRAM_BUCKET_COUNT) / span).toInt()
            .coerceIn(0, HISTOGRAM_BUCKET_COUNT - 1)
        if (result[idx] == null) result[idx] = timestamp
    }
    return result
}

private fun parseHistogramEpochMillis(timestamp: String): Long? = runCatching {
    val day = timestamp.substring(0, 5).split("-").map { it.toInt() }
    val time = timestamp.substring(6).split(":", ".")
    ((day[0] * 31L + day[1]) * 24L + time[0].toLong()) * 3_600_000L +
        (time[1].toLong() * 60_000L) + (time[2].toLong() * 1000L) + time[3].toLong()
}.getOrNull()

/** 块头迷你直方图（60 桶缩小形态——无入场动画，块头是静态摘要件） */
@Composable
internal fun MiniHistogram(lines: List<LogLine>) {
    val buckets = remember(lines) { histogramBuckets(lines) }
    val barColor = LocalEasyToolboxColors.current.logInfo
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = Modifier.height(Dimens.evidenceBlockHistogramHeight).width(Dimens.space24 * 2)) {
        drawBuckets(buckets, 1f, barColor, trackColor, size.width, size.height)
    }
}

/** 直方图桶绘制内核（主/迷你两形态共用） */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBuckets(
    buckets: IntArray,
    progress: Float,
    barColor: Color,
    trackColor: Color,
    w: Float,
    h: Float,
) {
    val n = buckets.size
    if (n == 0) return
    val maxCount = buckets.max().coerceAtLeast(1)
    val barW = w / n
    buckets.forEachIndexed { idx, count ->
        val x = idx * barW
        drawRect(color = trackColor, topLeft = Offset(x, 0f), size = Size(barW, h))
        if (count > 0) {
            val bh = h * count / maxCount * progress
            drawRect(color = barColor, topLeft = Offset(x, h - bh), size = Size(barW, bh))
        }
    }
}
