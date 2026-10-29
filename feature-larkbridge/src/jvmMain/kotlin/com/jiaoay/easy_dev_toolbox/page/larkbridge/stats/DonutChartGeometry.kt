package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import kotlin.math.abs

/**
 * 环形图（O7 按类型占比）**纯几何 / 数据计算**（从 [UsageStatsPanel] 的 `UsageDonutChart` Composable 抽出）。
 *
 * 本文件零 Compose、零 IO、零 `.dp`——只算角度（[Float] 度数）与槽位序号，绘制在 Composable 里以
 * `DrawScope`/`Dimens.chart*` token 完成。抽出纯函数是为了让「扇区角度分配」这一最易出错、且**从未在真机
 * 组合中被执行过**（统计页 tab 恒为 0）的逻辑可被 `DonutSlicesTest` 以内存输入直接断言：
 * - 总量为 0 时不除零、不产 [Float.NaN]（`drawArc` 最经典的崩溃源）；
 * - 种类 > [UsageStats.MAX_KINDS] 时截断并把尾部归并为「其他」扇，扇数恰好 ≤ 上限；
 * - 所有 sweep 之和 == 360°（±浮点容差），每个 sweep ≥ 0，起始角 -90°（12 点方向）顺时针累加。
 *
 * ## 与 Composable 的分工（动画进度不进纯函数）
 * [donutSlices] 计算的是**进度 = 1（入场动画终态）**的完整几何。入场缩放（[UsageStatsPanel] 的
 * `rememberChartProgress`，0f→1f 单值动画）是**绘制期**关注点：Composable 拿 [DonutSlice.sweepDegrees]
 * 乘以当前 progress 再累加起始角，因此 progress=1 时累加结果与 [DonutSlice.startDegrees] 逐值相等
 * （行为与抽出前完全一致），progress<1 时整环从 -90° 生长——纯函数不承载动画态，才可被确定性单测。
 */
object DonutChartGeometry {

    /** 起始角：-90°（12 点方向），顺时针累加（与抽出前 `UsageDonutChart` 一致）。 */
    const val START_DEGREES: Float = -90f

    /** 整圆角度。 */
    const val FULL_CIRCLE_DEGREES: Float = 360f

    /** 尾部归并扇的标签（与 [UsageStats] 的 `collapseTail` 一致）。 */
    const val TAIL_LABEL: String = "其他"

    /**
     * 把按类型聚合的用量算成环形图扇区几何（终态，progress=1）。
     *
     * @param usages 已聚合的按类型用量（正常来自 [UsageStats.aggregateByKind]，其自身已截断 ≤ [UsageStats.MAX_KINDS]）
     * @param maxSectors 扇数上限；输入超过它时**防御性**再截断一次（尾部归并为 [TAIL_LABEL]），
     *   ≤0 视为不限制。因 [UsageStats.aggregateByKind] 已保证 ≤ [UsageStats.MAX_KINDS]，此处对正常输入是幂等 no-op。
     * @return 扇区列表；空输入 → 空列表。每个扇区 [DonutSlice.sweepDegrees] = `value / total * 360`，
     *   total = 各扇 value 之和（**为 0 时钳到 1** 以避免除零，此时所有 sweep 均为 0、无 [Float.NaN]）。
     */
    fun donutSlices(usages: List<KindUsage>, maxSectors: Int = UsageStats.MAX_KINDS): List<DonutSlice> {
        if (usages.isEmpty()) return emptyList()
        val capped = if (maxSectors > 0 && usages.size > maxSectors) collapseTail(usages, maxSectors) else usages
        val total = capped.sumOf { it.total.toLong() }.coerceAtLeast(1L)
        val slices = ArrayList<DonutSlice>(capped.size)
        var start = START_DEGREES
        capped.forEachIndexed { index, usage ->
            val sweep = usage.total.toFloat() / total.toFloat() * FULL_CIRCLE_DEGREES
            slices +=
                DonutSlice(
                    startDegrees = start,
                    sweepDegrees = sweep,
                    colorSlot = index,
                    label = usage.label,
                    value = usage.total.toLong(),
                )
            start += sweep
        }
        return slices
    }

    /**
     * 起始角是否按约定单调累加（第 0 扇 == [START_DEGREES]，其后每扇 == 前扇起始 + 前扇 sweep）。
     * 供测试与自检复用；[tolerance] 为浮点容差（度）。
     */
    fun isStartAngleConsistent(slices: List<DonutSlice>, tolerance: Float = 0.01f): Boolean {
        if (slices.isEmpty()) return true
        if (abs(slices.first().startDegrees - START_DEGREES) > tolerance) return false
        for (i in 1 until slices.size) {
            val expected = slices[i - 1].startDegrees + slices[i - 1].sweepDegrees
            if (abs(slices[i].startDegrees - expected) > tolerance) return false
        }
        return true
    }

    /** 把超出 [keep] 的尾部分组合并进一条「[TAIL_LABEL]」（保持降序、总量守恒；与 [UsageStats] 同构）。 */
    private fun collapseTail(list: List<KindUsage>, keep: Int): List<KindUsage> {
        val head = list.take(keep - 1)
        val tail = list.drop(keep - 1)
        val merged =
            KindUsage(
                kind = null,
                label = TAIL_LABEL,
                total = tail.sumOf { it.total },
                success = tail.sumOf { it.success },
                failure = tail.sumOf { it.failure },
                skipped = tail.sumOf { it.skipped },
            )
        return head + merged
    }
}

/**
 * 环形图单个扇区的纯几何 / 数据描述（不含任何 Compose 类型）。
 *
 * @param startDegrees 起始角（度，-90° = 12 点方向，顺时针）
 * @param sweepDegrees 扫过角（度，≥ 0；总量为 0 时为 0）
 * @param colorSlot 主题色槽位序号（= 扇区在列表中的下标）：Composable 据此取 `slotColors[colorSlot]`，
 *   超出槽位数（3）则归弱化色 `onSurfaceVariant`
 * @param label 图例文本（类型友好名或「其他」）
 * @param value 该扇代表的用量总数
 */
data class DonutSlice(
    val startDegrees: Float,
    val sweepDegrees: Float,
    val colorSlot: Int,
    val label: String,
    val value: Long,
)
