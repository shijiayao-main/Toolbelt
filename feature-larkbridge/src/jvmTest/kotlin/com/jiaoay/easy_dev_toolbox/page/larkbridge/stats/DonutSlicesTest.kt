package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `DonutChartGeometry.donutSlices` 环形图纯几何契约（F-4）。
 *
 * 🔴 覆盖真实缺陷面：统计页 tab 恒为 0 ⇒ `UsageDonutChart` 的 `drawArc` 从未在真机组合中执行过，
 * 角度分配这一「最易产 NaN / 除零 / 扇数溢出」的逻辑零覆盖。本测试以内存 [KindUsage] 直接断言：
 * - total=0 不产 [Float.NaN]、不除零（`drawArc` 最经典崩溃源）；
 * - 种类 > [UsageStats.MAX_KINDS] 截断为恰好 ≤ 上限并把尾部归并「其他」；
 * - sweep 之和 == 360°（±容差）、每 sweep ≥ 0、起始角 -90° 顺时针单调累加；
 * - 单一占满 360°、两种等分 180°、空输入空列表等边界。
 *
 * 🔴 几何用 [Float] 度数，本文件零 Compose、零 `.dp`。
 */
class DonutSlicesTest {

    /** 浮点容差（度）。 */
    private val eps = 0.01f

    private fun kind(label: String, total: Int) =
        KindUsage(kind = label, label = label, total = total, success = total, failure = 0, skipped = 0)

    /** total=0：不产 NaN、不除零，所有 sweep 均为 0，起始角不漂移。 */
    @Test
    fun zeroTotalProducesNoNaNAndZeroSweeps() {
        val usages = listOf(kind("指令", 0), kind("Agent", 0), kind("快捷指令", 0))
        val slices = DonutChartGeometry.donutSlices(usages)
        assertEquals(3, slices.size)
        slices.forEach { slice ->
            assertFalse(slice.sweepDegrees.isNaN(), "total=0 不得产 NaN sweep")
            assertFalse(slice.startDegrees.isNaN(), "total=0 不得产 NaN 起始角")
            assertEquals(0f, slice.sweepDegrees, "total=0 时每扇 sweep 应为 0")
            assertTrue(slice.sweepDegrees >= 0f, "sweep 必须 ≥ 0")
        }
        // total=0 ⇒ sweep 全 0 ⇒ 起始角全停在 -90°（不累加、不漂移）
        assertTrue(slices.all { abs(it.startDegrees - DonutChartGeometry.START_DEGREES) < eps })
        assertEquals(0f, slices.sumOf { it.sweepDegrees.toDouble() }.toFloat(), eps)
    }

    /** 种类 > MAX_KINDS：截断为恰好 MAX_KINDS 扇，尾部归并「其他」，总量守恒。 */
    @Test
    fun moreThanMaxKindsCollapsesTailIntoOther() {
        // 10 种，total 递减 10..1（保证降序、尾部可辨识）
        val usages = (10 downTo 1).map { kind("k$it", it) }
        val originalTotal = usages.sumOf { it.total }
        val slices = DonutChartGeometry.donutSlices(usages, maxSectors = UsageStats.MAX_KINDS)
        assertEquals(UsageStats.MAX_KINDS, slices.size, "扇数必须恰好截断到 MAX_KINDS")
        assertTrue(slices.size <= UsageStats.MAX_KINDS)
        assertEquals(DonutChartGeometry.TAIL_LABEL, slices.last().label, "末扇应为归并的「其他」")
        // 尾部 3 种（total 3+2+1=6）归并进「其他」
        assertEquals(6L, slices.last().value)
        // 总量守恒：截断不丢用量
        assertEquals(originalTotal.toLong(), slices.sumOf { it.value })
        // total>0 ⇒ sweep 之和仍是 360°
        assertEquals(360f, slices.sumOf { it.sweepDegrees.toDouble() }.toFloat(), 0.5f)
    }

    /** sweep 之和 == 360°（±容差），每 sweep ≥ 0，起始角 -90° 顺时针单调累加。 */
    @Test
    fun sweepsSumTo360AndStartAnglesAccumulate() {
        val usages = listOf(kind("指令", 5), kind("Agent", 3), kind("快捷指令", 2))
        val slices = DonutChartGeometry.donutSlices(usages)
        assertEquals(3, slices.size)
        slices.forEach { assertTrue(it.sweepDegrees >= 0f, "每扇 sweep 必须 ≥ 0") }
        val sum = slices.sumOf { it.sweepDegrees.toDouble() }.toFloat()
        assertTrue(abs(sum - 360f) < eps, "sweep 之和应为 360°，实际 $sum")
        // 起始角：首扇 -90°，其后单调累加
        assertEquals(DonutChartGeometry.START_DEGREES, slices.first().startDegrees, eps)
        assertTrue(DonutChartGeometry.isStartAngleConsistent(slices, eps), "起始角必须按 sweep 单调累加")
        // 比例正确：5/10*360=180、3/10*360=108、2/10*360=72
        assertEquals(180f, slices[0].sweepDegrees, eps)
        assertEquals(108f, slices[1].sweepDegrees, eps)
        assertEquals(72f, slices[2].sweepDegrees, eps)
    }

    /** 单一种类占满 360°。 */
    @Test
    fun singleKindFillsWholeCircle() {
        val slices = DonutChartGeometry.donutSlices(listOf(kind("指令", 7)))
        assertEquals(1, slices.size)
        assertEquals(DonutChartGeometry.START_DEGREES, slices[0].startDegrees, eps)
        assertEquals(360f, slices[0].sweepDegrees, eps)
        assertEquals(0, slices[0].colorSlot)
    }

    /** 两种类等分：各 180°，起始角 -90° 与 90°。 */
    @Test
    fun twoEqualKindsSplitHalfAndHalf() {
        val slices = DonutChartGeometry.donutSlices(listOf(kind("指令", 4), kind("Agent", 4)))
        assertEquals(2, slices.size)
        assertEquals(180f, slices[0].sweepDegrees, eps)
        assertEquals(180f, slices[1].sweepDegrees, eps)
        assertEquals(-90f, slices[0].startDegrees, eps)
        assertEquals(90f, slices[1].startDegrees, eps)
    }

    /** 空输入产空列表。 */
    @Test
    fun emptyInputProducesEmptyList() {
        assertTrue(DonutChartGeometry.donutSlices(emptyList()).isEmpty())
    }

    /** colorSlot 按下标递增（前 3 映射主题槽位，其后归弱化色由 Composable 判定）。 */
    @Test
    fun colorSlotFollowsIndex() {
        val usages = (1..5).map { kind("k$it", it) }
        val slices = DonutChartGeometry.donutSlices(usages)
        slices.forEachIndexed { index, slice ->
            assertEquals(index, slice.colorSlot, "colorSlot 应等于扇区下标")
        }
    }

    /** 输入已 ≤ 上限时截断为幂等 no-op（保留全部扇、标签不变）。 */
    @Test
    fun truncationIsIdempotentWhenWithinLimit() {
        val usages = listOf(kind("指令", 3), kind("Agent", 2), kind("快捷指令", 1))
        val slices = DonutChartGeometry.donutSlices(usages, maxSectors = UsageStats.MAX_KINDS)
        assertEquals(3, slices.size, "未超上限不得截断")
        assertEquals(listOf("指令", "Agent", "快捷指令"), slices.map { it.label })
        assertFalse(slices.any { it.label == DonutChartGeometry.TAIL_LABEL }, "未超上限不应产「其他」扇")
    }
}
