package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import com.jiaoay.lark.bridge.observe.AuditEntryJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `UsageStats.aggregateByKind` / `kindLabel` 按路由类型分组契约（BC-524..527）。
 *
 * 🔒 友好标签逐字：COMMAND→`指令`、SKILL→`快捷指令`、AGENT→`Agent`；`kind==null`（旧审计行无此键）
 * 与未知字面值一律归 `未知`（向后兼容，BC-525 / R6）。`total` 降序（BC-524）。
 */
class UsageStatsKindTest {

    private fun entry(kind: String?, status: String = "SUCCESS") = AuditEntryJson(
        ts = 1_700_000_000_000L,
        chatId = "chat",
        senderOpenId = "ou_sender",
        prompt = "p",
        command = "cmd",
        status = status,
        elapsedMillis = 100L,
        kind = kind,
    )

    /** BC-524：按 kind 分组 + 友好标签 + total 降序。 */
    @Test
    fun byKindGroupsWithFriendlyLabelsDescending() {
        val entries = listOf(
            entry("COMMAND"), entry("COMMAND"), entry("COMMAND"),
            entry("SKILL"), entry("SKILL"),
            entry("AGENT"),
        )
        val kinds = UsageStats.aggregateByKind(entries)
        // 降序：首位 COMMAND(3)、次位 SKILL(2)、末位 AGENT(1)
        assertEquals("指令", kinds[0].label)
        assertEquals(3, kinds[0].total)
        assertEquals("快捷指令", kinds[1].label)
        assertEquals(2, kinds[1].total)
        assertEquals("Agent", kinds[2].label)
        assertEquals(1, kinds[2].total)
        // 降序不变量
        kinds.map { it.total }.zipWithNext().forEach { (a, b) -> assertTrue(a >= b, "total 必须降序") }
    }

    /** BC-525：无 kind 键（null）的旧审计行落入「未知」桶。 */
    @Test
    fun legacyEntriesWithoutKindFallIntoUnknownBucket() {
        val kinds = UsageStats.aggregateByKind(listOf(entry(null), entry(null)))
        assertEquals(1, kinds.size)
        assertEquals("未知", kinds[0].label)
        assertEquals(null, kinds[0].kind)
        assertEquals(2, kinds[0].total)
    }

    /** BC-526：COMMAND → 「指令」标签（单点确认）。 */
    @Test
    fun commandKindMapsToCommandLabel() {
        assertEquals("指令", UsageStats.kindLabel("COMMAND"))
        assertEquals("快捷指令", UsageStats.kindLabel("SKILL"))
        assertEquals("Agent", UsageStats.kindLabel("AGENT"))
        assertEquals("未知", UsageStats.kindLabel(null))
        assertEquals("未知", UsageStats.kindLabel("SOMETHING_NEW"))
    }

    /** BC-527：空输入 → 空 byKind 列表。 */
    @Test
    fun emptyEntriesYieldsEmptyByKind() {
        assertTrue(UsageStats.aggregateByKind(emptyList()).isEmpty(), "空输入应产出空列表")
    }

    /** 补充：状态分类计数在分组内正确（success/failure/skipped）。 */
    @Test
    fun byKindCountsStatusesWithinGroup() {
        val entries = listOf(
            entry("COMMAND", "SUCCESS"),
            entry("COMMAND", "FAILURE"),
            entry("COMMAND", "SKIPPED"),
        )
        val command = UsageStats.aggregateByKind(entries).first { it.kind == "COMMAND" }
        assertEquals(3, command.total)
        assertEquals(1, command.success)
        assertEquals(1, command.failure)
        assertEquals(1, command.skipped)
    }
}
