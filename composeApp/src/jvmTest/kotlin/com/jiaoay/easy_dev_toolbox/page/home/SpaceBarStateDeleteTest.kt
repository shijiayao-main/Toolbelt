package com.jiaoay.easy_dev_toolbox.page.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore

/**
 * SpaceBarState 删除语义直测：
 * - 删除只删聚合根行不级联成员（[SpaceStore.delete] 契约——危险操作
 *   危险态确认对话框承接，本测只钉状态机语义）；
 * - 删当前选中空间 → 选中态回落（列表非空落首个 / 空则 null）；
 * - 删非选中空间 → 选中态不动。
 *
 * DB 环境：SpaceStore 直连 Database.database()——与 SpaceBarState 同源；
 * 测试内创建的空间用唯一 id 前缀（space-test-）避免与真库数据互踩。
 */
class SpaceBarStateDeleteTest {

    @Test
    fun `delete removes row and falls back selection to first remaining`() = runBlocking<Unit> {
        val state = SpaceBarState()
        val store = SpaceStore(Database.database())
        val a = "space-test-del-a-${System.currentTimeMillis()}"
        val b = "space-test-del-b-${System.currentTimeMillis()}"
        try {
            store.create(id = a, title = "测试甲")
            store.create(id = b, title = "测试乙")
            state.refresh()
            // 选中 a（列表首项）后删除 a → 选中态应回落到首个剩余空间 b
            state.select(a)
            assertEquals(a, state.currentSpaceId.value)
            state.consume(SpaceBarState.SpaceIntent.Delete(a))
            assertNull(store.spaceById(a), "删除只删聚合根行——行应消失")
            assertEquals(b, state.currentSpaceId.value, "删当前选中 → 回落列表首个剩余空间")
            assertTrue(state.spaces.value.none { it.id == a }, "活跃列表不再含已删空间")
        } finally {
            store.delete(a)
            store.delete(b)
        }
    }

    @Test
    fun `delete non-selected keeps current selection`() = runBlocking<Unit> {
        val state = SpaceBarState()
        val store = SpaceStore(Database.database())
        val a = "space-test-keep-a-${System.currentTimeMillis()}"
        val b = "space-test-keep-b-${System.currentTimeMillis()}"
        try {
            store.create(id = a, title = "测试甲")
            store.create(id = b, title = "测试乙")
            state.refresh()
            state.select(a)
            state.consume(SpaceBarState.SpaceIntent.Delete(b))
            assertEquals(a, state.currentSpaceId.value, "删非选中空间 → 选中态不动")
            assertNull(store.spaceById(b), "行已删")
            assertNotNull(store.spaceById(a), "选中空间行保留")
        } finally {
            store.delete(a)
            store.delete(b)
        }
    }

    @Test
    fun `delete last test space deselects it and falls back`() = runBlocking<Unit> {
        val state = SpaceBarState()
        val store = SpaceStore(Database.database())
        val only = "space-test-last-${System.currentTimeMillis()}"
        try {
            store.create(id = only, title = "仅存测试空间")
            state.refresh()
            state.select(only)
            state.consume(SpaceBarState.SpaceIntent.Delete(only))
            // 🔴 真库可能非空（测试与真机数据同库）：删除语义 = 已删空间不再被选中
            //    + 列表回落（非空落首个 / 空则 null）——不硬断 null，防真库数据互踩
            assertNull(store.spaceById(only), "聚合根行已删")
            assertTrue(
                state.spaces.value.none { it.id == only },
                "活跃列表不再含已删空间",
            )
            if (state.spaces.value.isEmpty()) {
                assertNull(state.currentSpaceId.value, "删至列表空 → 选中态 null")
            } else {
                assertEquals(
                    state.spaces.value.first().id,
                    state.currentSpaceId.value,
                    "删后列表非空 → 选中态回落列表首个（真库共存时回落真库空间是正确行为）",
                )
            }
        } finally {
            store.delete(only)
        }
    }

    // ── 空间重命名 ──

    @Test
    fun `rename updates list title immediately and persists in store`() = runBlocking<Unit> {
        val state = SpaceBarState()
        val store = SpaceStore(Database.database())
        val a = "space-test-rename-a-${System.currentTimeMillis()}"
        try {
            store.create(id = a, title = "旧名空间")
            state.refresh()
            state.consume(SpaceBarState.SpaceIntent.Rename(a, "新名空间"))
            // 列表行标题即时更新（consume 分支 refreshList 全量拉取）
            assertEquals(
                "新名空间",
                state.spaces.value.firstOrNull { it.id == a }?.title,
                "重命名后活跃列表行标题应即时更新（consume → refreshList）",
            )
            // 持久化：store 直查同为新名（重启后仍为新名——走 SQLite 落盘）
            assertEquals("新名空间", store.spaceById(a)?.title, "重命名必须持久化（store.rename 落 SQLite）")
        } finally {
            store.delete(a)
        }
    }

    @Test
    fun `rename blank title is rejected without touching store`() = runBlocking<Unit> {
        val state = SpaceBarState()
        val store = SpaceStore(Database.database())
        val a = "space-test-rename-blank-${System.currentTimeMillis()}"
        try {
            store.create(id = a, title = "原名")
            state.refresh()
            state.consume(SpaceBarState.SpaceIntent.Rename(a, "   "))
            assertEquals(
                "原名",
                store.spaceById(a)?.title,
                "空/纯空白名防呆不落库——原名保留",
            )
        } finally {
            store.delete(a)
        }
    }

    @Test
    fun `rename archived space also persists`() = runBlocking<Unit> {
        // 归档区行也挂重命名钮（侦察荐）——重命名语义对归档行一致
        val state = SpaceBarState()
        val store = SpaceStore(Database.database())
        val a = "space-test-rename-arch-${System.currentTimeMillis()}"
        try {
            store.create(id = a, title = "归档旧名")
            store.archive(a, archived = true)
            state.refresh()
            state.consume(SpaceBarState.SpaceIntent.Rename(a, "归档新名"))
            assertEquals("归档新名", store.spaceById(a)?.title, "归档行重命名同样持久化")
            assertEquals(
                "归档新名",
                state.archived.value.firstOrNull { it.id == a }?.title,
                "归档列表行标题同步更新",
            )
        } finally {
            store.delete(a)
        }
    }
}
