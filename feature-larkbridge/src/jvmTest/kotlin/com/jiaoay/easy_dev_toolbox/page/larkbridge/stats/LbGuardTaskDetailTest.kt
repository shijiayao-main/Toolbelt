package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守护：统计签「任务明细」卡 + 驾驶舱「全部记录 →」联动。
 *
 * 验收面：
 * 1. 统计签列表态顶部「任务明细」SectionCard（默认收起、倒序、take(200) 截断——
 *    🔴 auditEntries 可达 20000 条直渲染卡死）；
 * 2. RecentTaskRow internal 化（统计签复用行配方——零传徽标参 = 无表情徽标）；
 * 3. 驾驶舱最近任务卡头「全部记录 →」+ onAllRecords 回调 + usageStatsFocusTicket
 *    票据接线（configFocusTicket 同款先例）+ detailExpandTicket 消费；
 * 4. 壳单级受控——expandedOverride 接线 + onExpandedChange 回写，
 *    内层 AnimatedVisibility 二次门控与引导行不得回潮（票据直开壳，双级死结）；
 * 5. 明细行补 kind 人话（UsageStats.kindLabel）+ 耗时
 *    （UsageStats.formatElapsed）——「带状态与耗时」兑现。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class LbGuardTaskDetailTest {

    @Test
    fun `stats tab has collapsed task detail card with truncation`() {
        val text = source(USAGE_STATS_PANEL)
        assertTrue("\"任务明细\"" in text, "「任务明细」卡缺失（验收 1）")
        // 默认收起 = 受控态初值 false（rememberSaveable）；壳经 expandedOverride 受控
        assertTrue("detailExpanded by rememberSaveable { mutableStateOf(false) }" in text, "任务明细卡必须默认收起（受控态初值 false）")
        assertTrue("expandedOverride = expanded" in text, "SectionCard 壳必须 expandedOverride 受控接线（票据直开壳）")
        assertTrue("onExpandedChange = onExpandedChange" in text, "壳标题点击必须经 onExpandedChange 回写受控态")
        // 旧双级结构清零——内层 AnimatedVisibility 二次门控与引导行不得回潮
        val cardIdx = text.indexOf("private fun RecentTaskDetailCard")
        assertTrue(cardIdx > 0, "RecentTaskDetailCard 缺失")
        val cardSlice = text.substring(cardIdx)
        assertTrue("AnimatedVisibility" !in cardSlice, "明细卡内层 AnimatedVisibility 二次门控必须清零（单级受控）")
        assertTrue("点击「全部记录 →」或此处展开明细" !in cardSlice, "引导行必须清零（单级受控后无存在理由）")
        assertTrue("take(RECENT_TASK_DETAIL_MAX_ROWS)" in text, "明细行必须 take(200) 截断（auditEntries 可达 20000——直渲染卡死）")
        assertTrue("detailExpandTicket" in text, "明细展开票据参数缺失（验收 2：全部记录联动）")
        assertTrue("sortedByDescending" in text, "明细必须倒序（验收：倒序带状态与耗时）")
    }

    @Test
    fun `recent task row is internal and cockpit links to all records`() {
        // RecentTaskRow 已从 CockpitPanel 尾部独立成文件（行件单文件）——本体 needle 随迁本守护
        val row = source(RECENT_TASK_ROW)
        assertTrue("internal fun RecentTaskRow" in row, "RecentTaskRow 必须 internal 化（统计签复用）")
        // 明细行补 kind 人话 + 耗时（验收「带状态与耗时」）
        assertTrue("UsageStats.kindLabel(entry.kind)" in row, "明细行必须渲染 kind 人话（kindLabel）")
        assertTrue("UsageStats.formatElapsed(entry.elapsedMillis)" in row, "明细行必须渲染耗时（formatElapsed）")
        val cockpit = source(COCKPIT_PANEL)
        assertTrue("\"全部记录 →\"" in cockpit, "最近任务卡头「全部记录 →」缺失（验收 2）")
        assertTrue("onAllRecords" in cockpit, "onAllRecords 回调缺失（接线）")
        // 统计签复用零徽标：RecentTaskRow 调用点（stats 侧）不传 reactions/botOpenId
        val stats = source(USAGE_STATS_PANEL)
        val callIdx = stats.indexOf("cockpit.RecentTaskRow(entry = entry)")
        assertTrue(callIdx > 0, "统计签必须复用 RecentTaskRow（internal 共享行配方）")
        val callSlice = stats.substring(callIdx, (callIdx + 120).coerceAtMost(stats.length))
        assertTrue("reactions" !in callSlice && "botOpenId" !in callSlice, "统计签明细行零传徽标参（无表情徽标）")
        // Page 接线：切统计签 + 递票据
        val page = source(LARK_BRIDGE_PAGE)
        assertTrue("usageStatsFocusTicket" in page, "usageStatsFocusTicket 票据缺失（页面接线）")
        assertTrue("detailExpandTicket = usageStatsFocusTicket" in page, "UsageStatsPanel 必须消费明细展开票据")
    }

    /** 定位源文件（PillLabelGuardTest 同款：user.dir 向上找 settings.gradle.kts 定项目根）。 */
    private fun source(relPath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val file = File(dir, relPath)
                assertTrue(file.isFile, "$relPath 不存在——文件被移动须同步更新本测试路径")
                return file.readText()
            }
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val USAGE_STATS_PANEL =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/UsageStatsPanel.kt"
        const val COCKPIT_PANEL =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/CockpitPanel.kt"
        const val RECENT_TASK_ROW =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/RecentTaskRow.kt"
        const val LARK_BRIDGE_PAGE =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LarkBridgePage.kt"
    }
}
