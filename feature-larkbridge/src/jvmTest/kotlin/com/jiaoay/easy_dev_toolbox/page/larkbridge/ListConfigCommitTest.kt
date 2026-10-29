package com.jiaoay.easy_dev_toolbox.page.larkbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ListConfigCommit.commitListConfig` 列表提交短路契约（BC-509/510，D8 缺陷修复）。
 *
 * 🔒 D8：相同值提交不得触发任何副作用（旧实现会整桥重建断连）；变化值恰触发一次 commit。
 * 新架构里 routes 顺序 == 优先级，故**顺序变化视为变化**（不可短路）。
 */
class ListConfigCommitTest {

    /** BC-509：相同值 → return false，persist/runtime（commit 回调）计数 == 0。 */
    @Test
    fun sameValueShortCircuitsWithoutSideEffects() {
        val current = listOf("a", "b")
        val next = listOf("a", "b")
        var commits = 0
        val changed = ListConfigCommit.commitListConfig(current, next) { commits++ }
        assertFalse(changed, "相同值须短路返回 false")
        assertEquals(0, commits, "🔒 相同值零副作用（persist/runtime 计数 == 0）")
    }

    /** BC-510：变化值 → return true，commit 恰 1 次且收到新值。 */
    @Test
    fun differentValueTriggersCommitExactlyOnce() {
        val current = listOf("a", "b")
        val next = listOf("a", "b", "c")
        var commits = 0
        var committed: List<String>? = null
        val changed = ListConfigCommit.commitListConfig(current, next) { commits++; committed = it }
        assertTrue(changed, "变化值须返回 true")
        assertEquals(1, commits, "🔒 变化值 commit 恰 1 次（persist + runtime 各 1）")
        assertEquals(next, committed, "commit 回调须收到新值")
    }

    /** 补充：顺序变化（routes 优先级变化）视为变化，不可短路。 */
    @Test
    fun reorderedListCountsAsChanged() {
        var commits = 0
        val changed = ListConfigCommit.commitListConfig(listOf("a", "b"), listOf("b", "a")) { commits++ }
        assertTrue(changed, "顺序变化须视为变化（routes 顺序即优先级）")
        assertEquals(1, commits)
    }

    /** 补充：空 → 空短路（删除到空与初始空相等）。 */
    @Test
    fun emptyToEmptyShortCircuits() {
        var commits = 0
        val changed = ListConfigCommit.commitListConfig(emptyList<String>(), emptyList()) { commits++ }
        assertFalse(changed)
        assertEquals(0, commits)
    }
}
