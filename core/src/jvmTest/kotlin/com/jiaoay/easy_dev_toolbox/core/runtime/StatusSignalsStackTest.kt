package com.jiaoay.easy_dev_toolbox.core.runtime

import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [StatusSignals] toast 三槽堆叠单测。
 *
 * 钉死列表语义五件事（渲染结构由 composeApp 侧
 * StatusHostStackGuardTest 源码锚守护）：
 * 1. show/showUndo = append（头旧尾新），连续 3 条列表 3 条不互相顶掉；
 * 2. 第 4 条挤掉最旧（[Dimens.toastMaxStack] 封顶——容量档 token 是挤压闸真源）；
 * 3. dismiss(id) 只移除目标条，其余条原序保留；
 * 4. undo 条与普通条混排（payload 不串扰——undoLabel/onUndo 只落在 showUndo 条上）；
 * 5. id 单调唯一（AtomicLong 自增——dismiss 定位与渲染 key 的稳定锚）。
 *
 * clearForTest：单测隔离缝（toast 断言类的前置清场）——顺带钉死清空语义。
 */
class StatusSignalsStackTest {

    @BeforeTest
    fun setUp() {
        StatusSignals.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        StatusSignals.clearForTest()
    }

    @Test
    fun `three consecutive shows stack without evicting each other`() {
        StatusSignals.show("复制成功")
        StatusSignals.show("保存完成")
        StatusSignals.show("已导出")

        val entries = StatusSignals.toast.value
        assertEquals(3, entries.size, "连续 3 条 show 应堆满 3 槽不互相顶掉（单槽形态下后到会顶掉先到）")
        assertEquals(
            listOf("复制成功", "保存完成", "已导出"),
            entries.map { it.text },
            "堆叠顺序应为 append 序（头旧尾新）",
        )
        assertTrue(entries.all { it.undoLabel == null && it.onUndo == null }, "show 通道不得携带撤销 payload")
    }

    @Test
    fun `fourth show evicts oldest and keeps latest three`() {
        StatusSignals.show("一")
        StatusSignals.show("二")
        StatusSignals.show("三")
        val evictedId = StatusSignals.toast.value.first().id

        StatusSignals.show("四")

        val entries = StatusSignals.toast.value
        assertEquals(Dimens.toastMaxStack, entries.size, "第 4 条到达时列表封顶在 toastMaxStack（容量档 token 是挤压闸真源）")
        assertEquals(listOf("二", "三", "四"), entries.map { it.text }, "满槽后新到应挤掉最旧（头出局、append 在尾）")
        assertTrue(
            entries.none { it.id == evictedId },
            "被挤掉的最旧条不得残留（id 锚核实出局的是头而非随机条）",
        )
    }

    @Test
    fun `dismiss by id removes only the target entry`() {
        StatusSignals.show("一")
        StatusSignals.show("二")
        StatusSignals.show("三")
        val target = StatusSignals.toast.value[1]

        StatusSignals.dismiss(target.id)

        val entries = StatusSignals.toast.value
        assertEquals(listOf("一", "三"), entries.map { it.text }, "dismiss(id) 只移除目标条，其余条原序保留")
        assertTrue(entries.none { it.id == target.id }, "目标条按 id 精确出局")
    }

    @Test
    fun `undo entries interleave with plain entries without payload crosstalk`() {
        var undoCalls = 0
        StatusSignals.show("普通反馈")
        StatusSignals.showUndo("已删除 3 行日志") { undoCalls++ }

        val entries = StatusSignals.toast.value
        assertEquals(2, entries.size, "undo 条与普通条应混排共存（各占一槽）")
        assertEquals("普通反馈", entries[0].text)
        assertTrue(entries[0].onUndo == null, "普通条不带撤销回调")
        assertEquals("撤销", entries[1].undoLabel, "undo 条撤销标签落位（不串扰到普通条）")
        entries[1].onUndo?.invoke()
        assertEquals(1, undoCalls, "undo 条回调可触发（payload 随槽保留）")
    }

    @Test
    fun `ids are unique and monotonically increasing across eviction`() {
        repeat(5) { StatusSignals.show("第${it + 1}条") }

        val entries = StatusSignals.toast.value
        assertEquals(3, entries.size)
        assertEquals(
            entries.sortedBy { it.id }.map { it.id },
            entries.map { it.id },
            "列表内 id 应单调递增（append 序与 id 序一致）",
        )
        // 跨出局唯一性：重新入栈的新条 id 不得与任何历史条撞号（AtomicLong 全局单调）
        val seenIds = mutableSetOf<Long>()
        repeat(Dimens.toastMaxStack + 2) { index ->
            StatusSignals.show("轮次$index")
            val latest = StatusSignals.toast.value.last()
            assertTrue(seenIds.add(latest.id), "toast id 必须全局唯一（dismiss 定位与渲染 key 依赖不撞号）")
        }
        // 同刻 show 两条也不得同 id（自增计数器 vs 时间戳的关键差异）
        StatusSignals.show("同刻甲")
        StatusSignals.show("同刻乙")
        val pair = StatusSignals.toast.value.takeLast(2)
        assertNotEquals(pair[0].id, pair[1].id, "同毫秒内连续 show 的两条必须拿到不同 id")
    }

    @Test
    fun `clearForTest empties the stack`() {
        StatusSignals.show("残留候选")
        StatusSignals.showUndo("撤销候选") {}

        StatusSignals.clearForTest()

        assertTrue(StatusSignals.toast.value.isEmpty(), "clearForTest 后堆叠须为空（其他测试类的前置清场缝）")
    }
}
