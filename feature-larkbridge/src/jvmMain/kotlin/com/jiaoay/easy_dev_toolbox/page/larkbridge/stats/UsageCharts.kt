package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint

/**
 * 共享图表件（自 UsageStatsPanel 抽出）：驾驶舱「今日统计」卡与统计签「近 7 天」
 * 复用同一柱状图语言（成功/失败/跳过三段堆叠 + 轨道），高度参数化——
 * 统计签走 [Dimens.chartHeight]，驾驶舱迷你图走 [Dimens.chartCompactHeight]。
 *
 * 零白名单纪律同 UsageStatsPanel：尺寸走 `Dimens.chart*` token、颜色走
 * [LocalEasyToolboxColors] 与 `MaterialTheme.colorScheme`、入场动画规格走 [AppMotion]。
 *
 * 统计签「按类型」环形图（[UsageDonutChart]）与柱状图图例/日期轴
 * （[ChartLegend] / [BarChartDateAxis]）同归本文件；消费方为 UsageStatsPanel 的列表态编排。
 */

/** 近 7 天堆叠柱状图（成功 / 失败 / 跳过三段）；柱高按 [rememberChartProgress] 入场缩放。 */
@Composable
internal fun UsageBarChart(
    buckets: List<DayBucket>,
    modifier: Modifier = Modifier,
    /** 固定高；null = 吃 modifier 约束高（弹性档——weight 容器传入，卡内余高撑满） */
    // 柱图区高 96（4 行柱位 × space24）
    height: Dp? = Dimens.barChartHeight,
) {
    val colors = LocalEasyToolboxColors.current
    // 昼战（design-doc.md §5.3，dayBattleTint）：语义深档段对昼底/昼卡 1.1~1.5 全不可见——
    // 三段坐语义 container 档（对昼底 3.4~3.5 / 对昼卡 4.4+，同族语义面档）；
    // 非昼战原值逐位不变
    val successColor = dayBattleTint(colors.success, colors.successContainer)
    val failureColor = dayBattleTint(MaterialTheme.colorScheme.error, colors.errorContainerFixed)
    val skippedColor = dayBattleTint(colors.warning, colors.warningContainer)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val maxTotal = (buckets.maxOfOrNull { it.total } ?: 1).coerceAtLeast(1)
    val progress = rememberChartProgress()
    // 7 天全 0 时只画 7 根灰轨道柱——走查读作「图表不
    // 承载任何信息」。全零窗口叠中央空态标注（轨道惯例保留，语义补齐——空窗 ≠ 故障）。
    val allEmpty = buckets.isNotEmpty() && buckets.all { it.total == 0 }
    Box(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = if (height != null) {
                Modifier.fillMaxWidth().height(height)
            } else {
                Modifier.fillMaxWidth().fillMaxHeight()
            },
        ) {
        val barW = Dimens.chartBarWidth.toPx()
        val gapPx = Dimens.chartBarGap.toPx()
        val chartH = size.height
        val n = buckets.size
        val totalW = n * barW + (n - 1).coerceAtLeast(0) * gapPx
        var x = ((size.width - totalW) / 2f).coerceAtLeast(0f)
        buckets.forEach { b ->
            drawRect(color = trackColor, topLeft = Offset(x, 0f), size = Size(barW, chartH))
            val hS = chartH * b.success / maxTotal * progress
            val hF = chartH * b.failure / maxTotal * progress
            val hK = chartH * b.skipped / maxTotal * progress
            var y = chartH
            if (hS > 0f) {
                drawRect(color = successColor, topLeft = Offset(x, y - hS), size = Size(barW, hS))
                y -= hS
            }
            if (hF > 0f) {
                drawRect(color = failureColor, topLeft = Offset(x, y - hF), size = Size(barW, hF))
                y -= hF
            }
            if (hK > 0f) {
                drawRect(color = skippedColor, topLeft = Offset(x, y - hK), size = Size(barW, hK))
            }
            x += barW + gapPx
        }
        }
        if (allEmpty) {
            Text(
                text = "近 7 天无记录",
                style = MaterialTheme.typography.labelMedium,
                // Mid 档非 Faint：标注叠在灰轨道柱(灰度~52)上，Faint(150) 对比 4.21:1
                // 差 AA 一线，Mid(198) = 7.29:1 稳过
                color = LocalToolboxSurfaceColors.current.onSurfaceMid,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = Dimens.space12),
            )
        }
    }
}

/**
 * 图表入场进度（0f→1f 单值动画，standard 档 180ms）：柱状/环形图在 draw 作用域内
 * 以进度缩放图形——属「数据已刷新」的状态认知动效，一次性播放、无逐柱/逐扇编排，
 * 不违反流式零动画铁律。柱状图与环形图共用单实例。
 */
@Composable
internal fun rememberChartProgress(): Float {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { target = 1f }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = AppMotion.standard(),
        label = "chartEntranceProgress",
    )
    return progress
}

/** 柱状图图例（成功 / 失败 / 跳过）。internal：消费方为 UsageStatsPanel 列表态。 */
@Composable
internal fun ChartLegend() {
    val colors = LocalEasyToolboxColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
    ) {
        // 昼战图例点坐语义 container 档（design-doc.md §5.3，dayBattleTint 图形 3.0 线——语义深档点
        // 直落昼底/昼卡不可见）；非昼战原值逐位不变
        LegendDot(color = dayBattleTint(colors.success, colors.successContainer), label = "成功")
        LegendDot(color = dayBattleTint(MaterialTheme.colorScheme.error, colors.errorContainerFixed), label = "失败")
        LegendDot(color = dayBattleTint(colors.warning, colors.warningContainer), label = "跳过")
    }
}

/**
 * 柱状图日期轴：与 [UsageBarChart] 同一布局算法（柱宽 + 间距 + **整体居中**）
 * 铺排每日标签，行宽与 Canvas 一致；末位（今日）标签带「今」标记。
 *
 * 居中约束：Canvas 侧柱组起点 = `(width - totalW) / 2`（[UsageBarChart]），
 * 本行必须用 `spacedBy(gap, CenterHorizontally)` 整组居中与之逐柱对齐——单参 `spacedBy`
 * 默认 Start 左起排，整轴恒偏 `(width - totalW) / 2`。改水平排布前先核对两侧公式。
 *
 * 标签取 dd（日号）：近 7 天窗口内日号唯一可辨（跨月回绕如 31→01 递增可推），且
 * chartBarWidth（24dp）盒内 MM-dd（5 字符）放不下会被剪裁。
 */
@Composable
internal fun BarChartDateAxis(buckets: List<DayBucket>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.chartBarGap, Alignment.CenterHorizontally),
    ) {
        val labeler = java.text.SimpleDateFormat("dd", java.util.Locale.getDefault())
        buckets.forEachIndexed { index, b ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.width(Dimens.chartBarWidth),
                contentAlignment = androidx.compose.ui.Alignment.TopCenter,
            ) {
                Text(
                    text = if (index == buckets.lastIndex) "今" else labeler.format(java.util.Date(b.dayStartMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == buckets.lastIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        // 圆点（统一图例形状：与环形图 DonutLegendDot 同语言，不再方块/圆点两套并存）
        Box(
            modifier = Modifier
                // .dotok 族 6px
                .size(Dimens.space6)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * O7 按类型占比环形图 + 右侧图例（「环 ≤8 扇」）。
 *
 * - 扇区色：chartPalette 分类色板（8 档）按下标取色——少档主题槽在第 4 扇起会全部塌缩同灰
 *   不可辨，色板消解该硬伤；
 * - 环厚 [Dimens.chartStroke]；弧间留 2° 缝隙（Butt cap），起始 -90°（12 点方向）顺时针
 * - 入场 sweep 按 [rememberChartProgress] 缩放；弱化底环做轨道，扇区缝隙处不透背景
 * - 图例带占比百分比（环形图的「占比」语义补全）
 * - 调用方保证 usages 非空且已在聚合层截断 ≤ [UsageStats.MAX_KINDS]（8 扇）
 */
@Composable
internal fun UsageDonutChart(usages: List<KindUsage>) {
    if (usages.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val palette = LocalEasyToolboxColors.current.chartPalette
    val unknownColor = scheme.onSurfaceVariant.copy(alpha = AppAlpha.contentFaint)
    val trackColor = scheme.onSurfaceVariant.copy(alpha = AppAlpha.bgSubtle)
    // 扇区几何走纯函数 DonutChartGeometry（终态 progress=1，可被 DonutSlicesTest 直接单测）；
    // 入场缩放在下方 draw 里乘 progress 再累加起始角——progress=1 时累加结果与 DonutSlice.startDegrees
    // 逐值相等（行为与抽出前完全一致），progress<1 时整环从 -90° 生长。
    val slices = remember(usages) { DonutChartGeometry.donutSlices(usages) }
    val progress = rememberChartProgress()
    val totalValue = slices.sumOf { it.value }.coerceAtLeast(1)
    /** colorSlot → 色板下标（超出色板的扇区归并灰，理论不可达：聚合层已截断 ≤ 8 = 色板长度） */
    fun slotColor(slot: Int) = if (slot < palette.size) palette[slot] else unknownColor

    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(Dimens.chartHeight)) {
            val stroke = Dimens.chartStroke.toPx()
            val arcSize = Size(
                size.width - stroke - Dimens.space4.toPx(),
                size.height - stroke - Dimens.space4.toPx(),
            )
            val topLeft = Offset(stroke / 2f + Dimens.space2.toPx(), stroke / 2f + Dimens.space2.toPx())
            val gapDeg = 2f
            var startAngle = DonutChartGeometry.START_DEGREES

            // 轨道：弱化底环（扇区缝隙处显示轨道色，避免透背景断层）
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = DonutChartGeometry.FULL_CIRCLE_DEGREES,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )

            slices.forEach { slice ->
                val color = slotColor(slice.colorSlot)
                val sweep = slice.sweepDegrees * progress
                if (sweep <= 0f) return@forEach
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = (sweep - gapDeg).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }

        // ── 图例（色点 + 类型名 + 数量 + 占比百分比，纵排居中） ──
        Column(
            modifier = Modifier.size(Dimens.chartHeight),
            verticalArrangement = Arrangement.spacedBy(Dimens.space8, alignment = Alignment.CenterVertically),
        ) {
            slices.forEach { slice ->
                val color = slotColor(slice.colorSlot)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DonutLegendDot(color)
                    Text(
                        text = "${slice.label} ${slice.value}（${slice.value * 100 / totalValue}%）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 环形图图例色点（6dp 同 dotok 族）。 */
@Composable
private fun DonutLegendDot(color: Color) {
    Box(
        modifier = Modifier
            .size(Dimens.space6)
            .clip(CircleShape)
            .background(color),
    )
}
