package com.jiaoay.easy_dev_toolbox.core.navigation

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore
import com.jiaoay.easy_dev_toolbox.core.data.db.ToolbeltDb
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 调查空间自动归属槽契约单测（第二轮反馈批 · 空间归属接线——方案 a 的 round-trip 锚）。
 *
 * ## 测试缝
 * [SpaceAttribution.databaseProvider] 是 internal 测试缝（JInitializerManager
 * .initializersProvider 先例）：注入 [Database.forFile] 临时库——🔴 不触
 * `Database.database()` 真单例（防污染 `~/.easy_dev_toolbox/db/toolbelt.db`；
 * WorkbenchEndToEndTest 同款纪律）。
 *
 * ## 覆盖面
 * - provider null（宿主未接线）→ 全 no-op；
 * - provider 返回 null（无选中空间）→ 静默跳过（非错误——语义契约）；
 * - provider 返回 id → flow/run/文件成员 round-trip 写入 + touch 活跃触达；
 * - 重复归属 → addFlows/addRuns 去重保序幂等（经归属槽间接覆盖 SpaceStore 语义）；
 * - 空间已删（mutate 落空）→ 幂等无操作不抛；
 * - 写库异常 → 不抛（增强面失败不拖垮调用方——槽内 JLog 收口路径）。
 *
 * 🔴 不用 runTest：被测面是同步 SQLite（无真挂起语义、无虚拟时间需求），
 * runBlocking 直调即确定性（ToolUsageSignalTest 同款裁定）。
 */
class SpaceAttributionTest {

    private lateinit var dbFile: File
    private lateinit var db: ToolbeltDb
    private lateinit var spaceStore: SpaceStore

    @BeforeTest
    fun setUp() {
        dbFile = File(System.getProperty("java.io.tmpdir"), "toolbelt-attribution-${System.nanoTime()}.db")
            .apply { deleteOnExit() }
        db = Database.forFile(dbFile)
        spaceStore = SpaceStore(db)
        // 测试缝注入：临时库替换 Database.database() 真单例
        SpaceAttribution.databaseProvider = { db }
    }

    @AfterTest
    fun tearDown() {
        // 还原接缝（JInitializerManager.resetForTest 纪律：测试不得把注入泄漏给生产路径）
        SpaceAttribution.databaseProvider = { Database.database() }
        SpaceAttribution.currentSpaceIdProvider = null
    }

    @Test
    fun `provider null - 宿主未接线全 no-op`() = runBlocking {
        // 前提：槽默认未接线（tearDown 保证跨用例隔离）
        SpaceAttribution.currentSpaceIdProvider = null
        spaceStore.create(id = "space-a", title = "空间A")

        // 不抛 + 不写任何成员
        SpaceAttribution.attributeFlow("flow-x")
        SpaceAttribution.attributeRun("run-x")
        SpaceAttribution.attributeFiles(listOf("/logs/x.txt"))

        assertEquals(emptyList(), spaceStore.spaceById("space-a")?.flowIds)
        assertEquals(emptyList(), spaceStore.spaceById("space-a")?.runIds)
        assertEquals(emptyList(), spaceStore.spaceById("space-a")?.filePaths)
    }

    @Test
    fun `provider 返回 null - 无选中空间静默跳过非错误`() = runBlocking {
        SpaceAttribution.currentSpaceIdProvider = { null }
        spaceStore.create(id = "space-a", title = "空间A")

        // 无选中空间 = 窗口期语义：静默跳过（非错误、不抛、不写）
        SpaceAttribution.attributeFlow("flow-x")
        SpaceAttribution.attributeRun("run-x")
        SpaceAttribution.attributeFiles(listOf("/logs/x.txt"))

        assertEquals(emptyList(), spaceStore.spaceById("space-a")?.flowIds)
        assertEquals(emptyList(), spaceStore.spaceById("space-a")?.runIds)
        assertEquals(emptyList(), spaceStore.spaceById("space-a")?.filePaths)
    }

    @Test
    fun `provider 返回 id - flow-run-文件成员 round-trip 写入`() = runBlocking {
        spaceStore.create(id = "space-live", title = "在办调查")
        SpaceAttribution.currentSpaceIdProvider = { "space-live" }

        // flow 归属 round-trip：attributeFlow → flowIds 落库可查回
        SpaceAttribution.attributeFlow("flow-1")
        assertEquals(listOf("flow-1"), spaceStore.spaceById("space-live")?.flowIds)

        // run 归属 round-trip
        SpaceAttribution.attributeRun("run-1")
        assertEquals(listOf("run-1"), spaceStore.spaceById("space-live")?.runIds)

        // 文件归属 round-trip（报告产物成员）
        SpaceAttribution.attributeFiles(listOf("/logs/report.md"))
        assertEquals(listOf("/logs/report.md"), spaceStore.spaceById("space-live")?.filePaths)

        // touch 活跃触达（SpaceStore 契约——成员写入后刷新列表排序）：
        // updatedAt 必须被推进到建库时刻之后
        val before = System.currentTimeMillis()
        Thread.sleep(2) // 毫秒粒度保序（SpacesMigrationTest 同款）
        SpaceAttribution.attributeFlow("flow-touch")
        val touched = spaceStore.spaceById("space-live")
        assertNotNull(touched)
        assertTrue(touched.updatedAt > touched.createdAt, "成员写入后 touch 应推进 updated_at")
        assertTrue(touched.updatedAt >= before, "updated_at 应为归属时刻（非建库时刻）")
    }

    @Test
    fun `addFlows-addRuns 去重保序 - 重复归属幂等`() = runBlocking {
        // SpaceStore 去重语义经归属槽间接覆盖（重复创建/重放场景不产生重复成员）
        spaceStore.create(id = "space-dedup", title = "去重调查")
        SpaceAttribution.currentSpaceIdProvider = { "space-dedup" }

        SpaceAttribution.attributeFlow("flow-1")
        SpaceAttribution.attributeFlow("flow-1") // 重复 offer
        SpaceAttribution.attributeFlow("flow-2")
        assertEquals(listOf("flow-1", "flow-2"), spaceStore.spaceById("space-dedup")?.flowIds)

        SpaceAttribution.attributeRun("run-1")
        SpaceAttribution.attributeRun("run-1")
        assertEquals(listOf("run-1"), spaceStore.spaceById("space-dedup")?.runIds)
    }

    @Test
    fun `空间已删 - mutate 落空幂等无操作不抛`() = runBlocking {
        // 增强面失败不拖垮调用方：空间创建后被删（归属点与创建点异步竞态）——
        // SpaceStore.mutate 的 spaceById 落空 return 语义经槽面验收
        SpaceAttribution.currentSpaceIdProvider = { "space-gone" }

        SpaceAttribution.attributeFlow("flow-1")
        SpaceAttribution.attributeRun("run-1")
        SpaceAttribution.attributeFiles(listOf("/logs/x.txt"))

        assertNull(spaceStore.spaceById("space-gone"))
    }

    @Test
    fun `写库异常不抛 - 增强面失败不拖垮调用方`() = runBlocking {
        // databaseProvider 抛非 CE 异常 → 槽内 JLog 收口、不外抛
        // （runCatching 失败面——归属失败不得打断 run/flow 的创建主流程）
        SpaceAttribution.currentSpaceIdProvider = { "space-live" }
        SpaceAttribution.databaseProvider = { error("db 故障注入") }

        // 不抛 = 契约：归属是旁路增强面
        SpaceAttribution.attributeFlow("flow-1")
        SpaceAttribution.attributeRun("run-1")
        SpaceAttribution.attributeFiles(listOf("/logs/x.txt"))
    }
}
