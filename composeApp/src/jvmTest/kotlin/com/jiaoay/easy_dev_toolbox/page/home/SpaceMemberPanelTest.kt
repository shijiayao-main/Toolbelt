package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * AG-02 空间成员明细面单测（重设计批 1）：
 * - [SpaceBarState] 扩展面：toggleExpand 单展开语义 + RemoveMember 意图 round-trip
 *   （移除后计数一致、展开态保持、幂等无操作）；
 * - [memberSectionsOf] 纯函数：三类成员分组逐条可见 + 计数一致；
 * - [memberDisplayName] 纯函数：knowledge:// 剥前缀（AG-02 拍板条件②——面板不显示
 *   裸串，AG-04 蒸馏归属成员的面板承载面）+ 文件路径剥目录。
 *
 * DB 环境：与 SpaceBarStateDeleteTest 同款——SpaceStore 直连 Database.database()，
 * 测试空间用唯一 id 前缀（space-test-mem-）避免与真库数据互踩（真库共存断言法）。
 */
class SpaceMemberPanelTest {

    private fun summaryOf(
        filePaths: List<String>,
        flowIds: List<String>,
        runIds: List<String>,
    ): SpaceStore.SpaceSummary = SpaceStore.SpaceSummary(
        id = "space-fixture",
        title = "fixture",
        status = SpaceStore.SpaceStatus.ACTIVE,
        filePaths = filePaths,
        flowIds = flowIds,
        runIds = runIds,
        createdAt = 0,
        updatedAt = 0,
    )

    // ── memberSectionsOf：三类成员逐节可见 + 计数一致 ──

    @Test
    fun `member sections group three kinds with consistent counts`() {
        val space = summaryOf(
            filePaths = listOf("/logs/a.xlog", "/logs/report.md"),
            flowIds = listOf("flow-1", "flow-2"),
            runIds = listOf("run-1"),
        )
        val sections = memberSectionsOf(space)

        assertEquals(3, sections.size, "三列非空 → 文件/会话/产物三节")
        assertEquals("文件", sections[0].label)
        assertEquals(listOf("/logs/a.xlog", "/logs/report.md"), sections[0].entries)
        assertEquals(SpaceStore.MemberKind.FILE, sections[0].kind)
        assertEquals("会话", sections[1].label)
        assertEquals(2, sections[1].entries.size, "会话计数与 flowIds 一致")
        assertEquals("产物", sections[2].label)
        assertEquals(listOf("run-1"), sections[2].entries)
        assertEquals(SpaceStore.MemberKind.RUN, sections[2].kind)
    }

    @Test
    fun `empty member kinds yield no section`() {
        assertTrue(memberSectionsOf(summaryOf(emptyList(), emptyList(), emptyList())).isEmpty())
        // 只有一列非空 → 只出一节（空节不出）
        val onlyFiles = memberSectionsOf(summaryOf(listOf("/logs/a.txt"), emptyList(), emptyList()))
        assertEquals(listOf("文件"), onlyFiles.map { it.label })
    }

    // ── knowledge:// 独立分组（AG-02 拍板条件②——AG-04 成员的面板承载） ──

    @Test
    fun `knowledge uri members split into independent section without raw prefix`() {
        val space = summaryOf(
            filePaths = listOf("/logs/a.xlog", "knowledge://settings"),
            flowIds = emptyList(),
            runIds = emptyList(),
        )
        val sections = memberSectionsOf(space)

        assertEquals(2, sections.size, "普通文件 + knowledge 成员各一节")
        assertEquals("文件", sections[0].label)
        assertEquals(listOf("/logs/a.xlog"), sections[0].entries)
        assertEquals("知识库", sections[1].label, "knowledge 成员独立小签")
        assertEquals(listOf("knowledge://settings"), sections[1].entries, "移除回传原始值（剥前缀只发生在显示层）")
        assertEquals(SpaceStore.MemberKind.FILE, sections[1].kind, "knowledge 成员物理在 FILE 列表——移除按 FILE 定位")
    }

    @Test
    fun `member display name strips knowledge prefix and directory`() {
        assertEquals("settings", memberDisplayName("knowledge://settings"))
        assertEquals("report.md", memberDisplayName("/home/u/logs/report.md"))
        assertEquals("flow-1", memberDisplayName("flow-1"))
    }

    // ── SpaceBarState 扩展面：toggleExpand + RemoveMember round-trip ──

    @Test
    fun `toggleExpand is single-expand and second tap collapses`() {
        val state = SpaceBarState()
        assertNull(state.expandedSpaceId.value, "初始全收起")
        state.toggleExpand("space-a")
        assertEquals("space-a", state.expandedSpaceId.value)
        state.toggleExpand("space-b")
        assertEquals("space-b", state.expandedSpaceId.value, "单展开——新展开替换旧展开")
        state.toggleExpand("space-b")
        assertNull(state.expandedSpaceId.value, "再次点击同 id 收起")
    }

    @Test
    fun `removeMember intent removes one member and keeps expanded state`() = runBlocking<Unit> {
        val state = SpaceBarState()
        val store = SpaceStore(Database.database())
        val id = "space-test-mem-${System.currentTimeMillis()}"
        try {
            store.create(id = id, title = "成员明细测试")
            store.addFiles(id, listOf("/logs/a.xlog", "/logs/b.xlog"))
            store.addFlows(id, listOf("flow-1"))
            store.addRuns(id, listOf("run-1", "run-2"))
            state.refresh()

            state.toggleExpand(id)
            state.consume(SpaceBarState.SpaceIntent.RemoveMember(id, SpaceStore.MemberKind.FILE, "/logs/a.xlog"))
            state.consume(SpaceBarState.SpaceIntent.RemoveMember(id, SpaceStore.MemberKind.FLOW, "flow-1"))
            state.consume(SpaceBarState.SpaceIntent.RemoveMember(id, SpaceStore.MemberKind.RUN, "run-1"))

            val after = store.spaceById(id)
            assertEquals(listOf("/logs/b.xlog"), after?.filePaths, "FILE 成员单点移除")
            assertEquals(emptyList(), after?.flowIds, "FLOW 成员单点移除")
            assertEquals(listOf("run-2"), after?.runIds, "RUN 成员单点移除、其余保留")
            assertEquals(id, state.expandedSpaceId.value, "移除成员不收起明细面（连续移除场景）")
            assertTrue(state.spaces.value.any { it.id == id }, "聚合根行保留（移除成员不删空间）")
        } finally {
            store.delete(id)
        }
    }

    @Test
    fun `removeMember of absent value is idempotent no-op`() = runBlocking<Unit> {
        val state = SpaceBarState()
        val store = SpaceStore(Database.database())
        val id = "space-test-idem-${System.currentTimeMillis()}"
        try {
            store.create(id = id, title = "幂等移除测试")
            store.addFiles(id, listOf("/logs/a.xlog"))
            state.refresh()

            // 不存在的值（SpaceStore.removeMember 幂等契约）+ 不存在的空间
            state.consume(SpaceBarState.SpaceIntent.RemoveMember(id, SpaceStore.MemberKind.FILE, "/logs/不存在"))
            state.consume(SpaceBarState.SpaceIntent.RemoveMember("space-test-不存在", SpaceStore.MemberKind.FILE, "/logs/a.xlog"))

            assertEquals(listOf("/logs/a.xlog"), store.spaceById(id)?.filePaths, "不存在值移除 = 无操作")
        } finally {
            store.delete(id)
        }
    }

    @Test
    fun `delete expanded space collapses the member panel`() = runBlocking<Unit> {
        val state = SpaceBarState()
        val store = SpaceStore(Database.database())
        val id = "space-test-delmem-${System.currentTimeMillis()}"
        try {
            store.create(id = id, title = "展开删除测试")
            state.refresh()
            state.toggleExpand(id)
            assertEquals(id, state.expandedSpaceId.value)

            state.consume(SpaceBarState.SpaceIntent.Delete(id))
            assertNull(state.expandedSpaceId.value, "空间删除后展开态收起（防指向已删空间）")
        } finally {
            store.delete(id)
        }
    }
}
