package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.core.evidence.LogLine
import com.jiaoay.easy_dev_toolbox.core.evidence.ReadLogLinesResult
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.widget.evidence.evidenceTimelineEntryItemOffset
import com.jiaoay.easy_dev_toolbox.widget.evidence.evidenceTimelineLineItemOffset
import com.jiaoay.easy_dev_toolbox.widget.evidence.evidenceTruncationVisible
import com.jiaoay.easy_dev_toolbox.widget.evidence.filterEvidenceLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 证据流块化偏移纯函数直测（评审修复批 2026-09-23——P2 itemCount 过计修复的锚）。
 *
 * 修复前：lineItemOffset 恒 +1 琥珀块头（不问实际渲染与否），单块场景
 * （lines 非空 + entries 空）itemCount 比真实 item 数多 1——scrollToItem(last)
 * 越界被 Compose 钳制，视觉等效但偏移契约失真。修复后偏移按「块头实际渲染
 * 与否」动态计（本组用例钉死四形态）。
 *
 * 直测落位说明：函数本体在 :ui/widget/evidence（:ui 模块无 jvmTest 源集基建）；
 * 本模块是它的第一个 feature 消费方（jumpToTimestamp），jvmTest 消费 :ui
 * implementation 依赖有先例（feature-archive ArchiveViewModelTest import
 * widget.TaskUiStatus）。
 *
 * WB-03（重设计批 1 · 2026-09-30）增 [evidenceTruncationVisible] 判据用例——
 * 截断提示条 + 总行数徽标的显示/零渲染双分支。
 *
 * WB-13（重设计批 7 · 2026-10-01）增 [filterEvidenceLines] 过滤纯函数用例——
 * 空 query 短路原引用 / 子串命中 / 清除恢复全量。🔴 offset 契约裁量：过滤态
 * 渲染行 ≠ 全量索引，页面侧 jumpToTimestamp 过滤态跳过滚动（高亮仍设）。
 */
class EvidenceTimelineOffsetTest {

    private fun line(seq: Int = 0) = LogLine(
        seq = seq,
        raw = "line-$seq",
        timestamp = null,
        level = 'I',
        tag = null,
        message = null,
    )

    private fun entry(id: String = "e1") = ChatEntry.Activity(id = id, text = "t", running = false)

    @Test
    fun `两流皆空——空态提示占 1 位无块头`() {
        // 布局：仅空态提示项——行流偏移 = 1（空态提示本身）
        assertEquals(1, evidenceTimelineLineItemOffset(emptyList(), emptyList()))
        // entryItemOffset 调用前提是 entries 非空——空态形态只验 lineItemOffset
    }

    @Test
    fun `单琥珀块——偏移恰为 1 个块头`() {
        val lines = listOf(line(1), line(2), line(3))
        // lines 非空 ⇒ 空态提示不渲染（两流互斥）；琥珀块头渲染占 1 位
        assertEquals(1, evidenceTimelineLineItemOffset(lines, emptyList()))
        // 紫块跳转前提不满足（entries 空）——entryItemOffset 不再有紫块头虚位：
        // 修复前恒 +1 过计；修复后 aiHeader = 0
        assertEquals(1 + lines.size + 0, evidenceTimelineEntryItemOffset(lines, emptyList()))
    }

    @Test
    fun `单紫块——偏移含空态提示替代位`() {
        val entries = listOf(entry("a"), entry("b"))
        // lines 空 + entries 非空：空态提示不渲染（非两流皆空）；琥珀块头不渲染
        assertEquals(0, evidenceTimelineLineItemOffset(emptyList(), entries))
        // entryItemOffset = 0（空态+琥珀块头都不在）+ 0 行 + 1 紫块头 = 1
        assertEquals(1, evidenceTimelineEntryItemOffset(emptyList(), entries))
    }

    @Test
    fun `双块同屏——琥珀块头与紫块头各占 1 位`() {
        val lines = listOf(line(1), line(2))
        val entries = listOf(entry("a"))
        // 1（琥珀块头）+ 2（行）+ 1（紫块头）——与修复前取值一致（回归锚）
        assertEquals(1, evidenceTimelineLineItemOffset(lines, entries))
        assertEquals(1 + lines.size + 1, evidenceTimelineEntryItemOffset(lines, entries))
    }

    // ── WB-03（重设计批 1 · 2026-09-30）：截断提示可见判据 ──

    @Test
    fun `截断产物提示与徽标可见`() {
        // 2500 行超窗截断：提示条 + 总行数徽标双双渲染
        val truncated = ReadLogLinesResult(
            lines = List(2000) { line(it) },
            truncated = true,
            totalLines = 2500,
        )
        assertTrue(evidenceTruncationVisible(truncated), "真截断 ⇒ 提示条+徽标可见（显示分支）")
    }

    @Test
    fun `未截断产物零提示零徽标`() {
        // ≤2000 行常规产物（恰等于上限也不截断）：既有行为零回归
        val notTruncated = ReadLogLinesResult(
            lines = List(2000) { line(it) },
            truncated = false,
            totalLines = 2000,
        )
        assertFalse(evidenceTruncationVisible(notTruncated), "未截断 ⇒ 提示条+徽标零渲染（零回归分支）")
    }

    @Test
    fun `null 探测结果零提示零徽标`() {
        // 无产物/未探测（null）：两件零渲染——空态行为不变
        assertFalse(evidenceTruncationVisible(null), "null ⇒ 提示条+徽标零渲染")
    }

    // ── WB-13（重设计批 7 · 2026-10-01）：行内搜索过滤纯函数 ──

    @Test
    fun `wb13 空 query 返回原列表引用`() {
        val lines = listOf(line(1), line(2))
        // 空/空白 query = 零过滤开销路径（isBlank 短路不拷贝——清除后恢复全量行流）
        assertTrue(filterEvidenceLines(lines, "") === lines, "空串必须返回原列表引用（零拷贝短路）")
        assertTrue(filterEvidenceLines(lines, "   ") === lines, "纯空白 query 同款短路")
    }

    @Test
    fun `wb13 子串命中过滤且保全量数据面`() {
        val a = LogLine(seq = 1, raw = "09-19 10:29:50.000 E/CameraManager: camera route failed", timestamp = null, level = 'E', tag = "CameraManager", message = "camera route failed")
        val b = LogLine(seq = 2, raw = "09-19 10:29:51.000 I/PowerManager: screen on", timestamp = null, level = 'I', tag = "PowerManager", message = "screen on")
        val lines = listOf(a, b)
        // 子串匹配 raw 全行（TAG/message/时间戳三段单域全覆盖）
        assertEquals(listOf(a), filterEvidenceLines(lines, "CameraManager"))
        assertEquals(listOf(a), filterEvidenceLines(lines, "camera route"))
        assertEquals(listOf(b), filterEvidenceLines(lines, "screen on"))
        // 无命中 = 空渲染面（组件显「无匹配行」空态——数据面 evidenceLines 全量保留）
        assertTrue(filterEvidenceLines(lines, "不存在串").isEmpty(), "无命中 ⇒ 渲染面空")
    }

    @Test
    fun `wb13 清除后恢复全量`() {
        val lines = listOf(line(1), line(2), line(3))
        val filtered = filterEvidenceLines(lines, "line-1")
        assertEquals(1, filtered.size, "过滤命中单行")
        // 清除（query 置空）⇒ 对全量数据面重新过滤 = 恢复全量行流
        //（数据面 evidenceLines 恒全量——清除只动 query；acceptance 第二项）
        assertEquals(lines, filterEvidenceLines(lines, ""), "清除后恢复全量（空 query 短路原引用语义）")
    }
}
