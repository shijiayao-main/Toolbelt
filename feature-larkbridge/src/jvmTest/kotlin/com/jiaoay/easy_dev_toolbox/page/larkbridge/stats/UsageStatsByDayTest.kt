package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import com.jiaoay.lark.bridge.observe.AuditEntryJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `UsageStats.aggregateByDay` 按天分桶契约（BC-520..523，D17）。
 *
 * 🔴 新实现改用 UTC epoch-day（`millis.floorDiv(DAY_MILLIS)`）+ `forEachIndexed`，
 * 故本测试以**固定 `nowMillis`** 精确锁定 7 个桶的边界（跨时区确定，不依赖运行机器本地时区）。
 */
class UsageStatsByDayTest {

    /** 固定「现在」：1_700_000_000_000ms（2023-11-14T22:13:20Z），落在当日 UTC 桶内。 */
    private val nowMillis = 1_700_000_000_000L

    /** 今日 UTC epoch-day 序号。 */
    private val today = UsageStats.epochDay(nowMillis)

    /** 构造落在「今日 + [offset] 天」正午（确保在该 UTC 日桶内）的审计条目。 */
    private fun entryAt(offset: Long, status: String, kind: String? = "COMMAND") = AuditEntryJson(
        ts = (today + offset) * UsageStats.DAY_MILLIS + UsageStats.DAY_MILLIS / 2,
        chatId = "chat",
        senderOpenId = "ou_sender",
        prompt = "p",
        command = "cmd",
        status = status,
        elapsedMillis = 100L,
        kind = kind,
    )

    /** BC-520：恰好 7 桶，末位今日、首位今日-6；空输入 → 7 个全零桶。 */
    @Test
    fun producesExactlySevenBucketsEndingToday() {
        val buckets = UsageStats.aggregateByDay(emptyList(), nowMillis)
        assertEquals(7, buckets.size, "默认窗口应为 7 桶")
        assertEquals(today, buckets.last().dayIndex, "末位桶必须是今日")
        assertEquals(today - 6, buckets.first().dayIndex, "首位桶必须是今日-6")
        assertTrue(buckets.all { it.total == 0 }, "空输入时全部桶应为零")
        // dayIndex 必须严格递增连续（forEachIndexed 顺序保证）
        buckets.map { it.dayIndex }.zipWithNext().forEach { (a, b) -> assertEquals(1L, b - a, "桶应按天连续递增") }
    }

    /** BC-521：按天分类计数 success/failure/skipped，窗口外条目丢弃（窗口内总和 == 6）。 */
    @Test
    fun countsStatusesPerDayAndDropsEntriesOutsideWindow() {
        val entries = listOf(
            entryAt(0, "SUCCESS"),
            entryAt(0, "SUCCESS"),
            entryAt(-1, "FAILURE"),
            entryAt(-3, "SKIPPED"),
            entryAt(-6, "SUCCESS"),
            entryAt(-6, "SUCCESS"),
            // 窗口外（今日-10）：必须被丢弃，不计入任何桶
            entryAt(-10, "SUCCESS"),
        )
        val buckets = UsageStats.aggregateByDay(entries, nowMillis)
        assertEquals(2, buckets[6].success, "今日 2 条成功")
        assertEquals(1, buckets[5].failure, "昨日 1 条失败")
        assertEquals(1, buckets[3].skipped, "今日-3 有 1 条跳过")
        assertEquals(2, buckets[0].success, "今日-6 有 2 条成功")
        // 窗口内总和 == 6（窗口外那条被丢弃）
        assertEquals(6, buckets.sumOf { it.total }, "窗口外条目必须丢弃，窗口内总和为 6")
    }

    /** BC-522：事件之间的空白天保持全零。 */
    @Test
    fun emptyDaysBetweenEventsStayZero() {
        val entries = listOf(entryAt(0, "SUCCESS"), entryAt(-6, "SUCCESS"))
        val buckets = UsageStats.aggregateByDay(entries, nowMillis)
        // 中间 index 1..5（今日-5 .. 昨日）无事件 → 全零
        (1..5).forEach { i -> assertEquals(0, buckets[i].total, "中间空白天（index=$i）应为零") }
        assertEquals(1, buckets[6].total)
        assertEquals(1, buckets[0].total)
    }

    /** BC-523：自定义 days 参数控制桶数（days=3 → 3 桶，末位仍今日）。 */
    @Test
    fun customDaysParameterControlsBucketCount() {
        val buckets = UsageStats.aggregateByDay(listOf(entryAt(0, "SUCCESS")), nowMillis, days = 3)
        assertEquals(3, buckets.size, "days=3 应产出 3 桶")
        assertEquals(today, buckets.last().dayIndex, "末位仍为今日")
        assertEquals(today - 2, buckets.first().dayIndex, "首位为今日-2")
    }
}
