package com.jiaoay.easy_dev_toolbox.page.ai.distill

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.KnowledgeRepository
import com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools
import com.jiaoay.easy_dev_toolbox.core.navigation.SpaceAttribution
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * AG-04 蒸馏产物自动归属单测（重设计批 1 · knowledge:// 轻归属拍板 2026-09-29）：
 * - 挂点 4（acceptProposal written 分支）：入库成功 → 「knowledge://<repo>」URI 串落
 *   当前空间 filePaths（零 schema 变更轻归属）；unknown repo 被 DistillIngest 拒
 *   （written=false）→ 不归属不混入；同 repo 多候选接受 → addFiles distinct 幂等；
 * - 挂点 5（exportKbPack）：导出包 absolutePath 归属当前空间（onDone 前时序）。
 *
 * ## 测试缝
 * - 🔴 VM 构造触达 Database.database() 真单例（DistillSession ledger 装配）——user.home
 *   隔离（UserHomeIsolation 共享件）后真单例落临时 home，SpaceAttribution 默认走的同一
 *   Database.database() 即闭环断言面（DistillViewModelTest「不构造 VM」的顾虑经隔离解除；
 *   Database 单例进程缓存 = @Volatile 幂等，本类多用例共用隔离库是同库语义，无泄漏）；
 * - [DistillViewModel.setReviewFixtureForTest]：计划 repo + 审阅行直灌（生产唯一写点
 *   startExplore/snapshotReviewRows 需真实 LLM 不可单测驱动）；
 * - Main dispatcher 替换（viewModelScope = Main.immediate）+ VM 注入共享 TestScheduler
 *   的 ioDispatcher（VM 级归属写续体确定性推进，teardown 无竞态宽限）；settle 轮询仅
 *   承接 :core 内部真实 IO（ingest/exportPack 自带 withContext(IO)）——
 *   advanceUntilIdle 排 Main 队列 + 轮询等真实 IO 续体落回。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DistillAttributionTest {

    private val dispatcher = StandardTestDispatcher()
    private val home = com.jiaoay.testfixtures.UserHomeIsolation("edt-distill-attr-test")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect() // Database.database() 真单例落临时 home（隔离真库）
        // 🔴 Database.instance 是 JVM 级缓存——跨用例串库（上一用例的临时库已随
        //    tearDown 删除，缓存连接指向已删文件 → SQLITE_CANTOPEN）：重定向后必须
        //    resetForTest 清缓存，db() 才会针对新 home 懒重建
        Database.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        SpaceAttribution.currentSpaceIdProvider = null
        dispatcher.scheduler.advanceUntilIdle() // 排空在途协程再 resetMain（drain 纪律）——VM 级 IO 已注入测试调度器，无在途真实 IO 续体
        Dispatchers.resetMain()
        home.restore()
        Database.resetForTest() // 清指向临时库的缓存连接（先还原 user.home 再清）
    }

    /** Database.database() 真单例（隔离 home 内）——SpaceAttribution 默认写点同源 */
    private fun db() = Database.database()

    private fun proposal(seq: Int) = DistillTools.DistillProposal(
        kind = DistillTools.DistillProposal.Kind.TAG,
        title = "SettingsPresenter",
        body = "入口/状态/异常/结果四类日志模式",
        sourceRefs = listOf("src/SettingsPresenter.kt:42"),
        seq = seq,
    )

    /** settle 轮询：Main 队列排空 + 真实 IO（ingest/归属写）完成轮询，双段都落定才过 */
    private fun settle(victim: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!victim() && System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            if (victim()) break
            Thread.sleep(50)
        }
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `accept proposal attributes knowledge repo uri into current space`() {
        val spaceStore = SpaceStore(db())
        spaceStore.create(id = "space-distill", title = "蒸馏归属测试")
        SpaceAttribution.currentSpaceIdProvider = { "space-distill" }

        val vm = DistillViewModel(ioDispatcher = dispatcher)
        vm.setReviewFixtureForTest(repo = "settings", rows = listOf(DistillViewModel.ReviewRow(proposal(1))))

        vm.acceptProposal(seq = 1)
        settle { vm.reviewRows.value.firstOrNull { it.proposal.seq == 1 }?.state == DistillViewModel.ReviewState.ACCEPTED }

        assertEquals(
            DistillViewModel.ReviewState.ACCEPTED,
            vm.reviewRows.value.firstOrNull { it.proposal.seq == 1 }?.state,
            "入库成功置 ACCEPTED（主链现状不动）",
        )
        settle {
            spaceStore.spaceById("space-distill")?.filePaths == listOf("knowledge://settings")
        }
        assertEquals(
            listOf("knowledge://settings"),
            spaceStore.spaceById("space-distill")?.filePaths,
            "挂点 4：入库成功后 knowledge://repo URI 串归属当前空间（轻归属——零 schema 变更）",
        )
        // 入库本体在位（归属不拖垮主链）：TAG 行四表可查
        assertTrue(
            KnowledgeRepository(db()).lookupTag(tag = "SettingsPresenter", repo = "settings").isNotEmpty(),
            "知识本体已入库（归属是旁路增强面）",
        )
    }

    @Test
    fun `accept proposal twice dedupes knowledge member`() {
        val spaceStore = SpaceStore(db())
        spaceStore.create(id = "space-distill-2", title = "幂等归属测试")
        SpaceAttribution.currentSpaceIdProvider = { "space-distill-2" }

        val vm = DistillViewModel(ioDispatcher = dispatcher)
        vm.setReviewFixtureForTest(
            repo = "settings",
            rows = listOf(
                DistillViewModel.ReviewRow(proposal(1)),
                DistillViewModel.ReviewRow(proposal(2)),
            ),
        )

        vm.acceptProposal(seq = 1)
        vm.acceptProposal(seq = 2)
        settle { vm.reviewRows.value.count { it.state == DistillViewModel.ReviewState.ACCEPTED } == 2 }

        settle {
            spaceStore.spaceById("space-distill-2")?.filePaths == listOf("knowledge://settings")
        }
        assertEquals(
            listOf("knowledge://settings"),
            spaceStore.spaceById("space-distill-2")?.filePaths,
            "同 repo 多候选接受 → knowledge://settings 去重不重复记（addFiles distinct 幂等）",
        )
    }

    @Test
    fun `unknown repo rejected by ingest does not attribute`() {
        val spaceStore = SpaceStore(db())
        spaceStore.create(id = "space-distill-3", title = "unknown 拒绝测试")
        SpaceAttribution.currentSpaceIdProvider = { "space-distill-3" }

        val vm = DistillViewModel(ioDispatcher = dispatcher)
        // repo=unknown 是 DistillIngest 显式拒绝面（探索产物缺 repo → written=false）
        vm.setReviewFixtureForTest(repo = "unknown", rows = listOf(DistillViewModel.ReviewRow(proposal(1))))

        vm.acceptProposal(seq = 1)
        // settle 等主链失败分支落定（ingestError 置值 = 协程已跑完，归属不可能再写）
        settle { vm.ingestError.value != null }

        assertEquals(
            emptyList(),
            spaceStore.spaceById("space-distill-3")?.filePaths,
            "unknown repo 被 DistillIngest 拒 → 不归属不混入（天然防混入）",
        )
        assertEquals(
            DistillViewModel.ReviewState.PENDING,
            vm.reviewRows.value.firstOrNull { it.proposal.seq == 1 }?.state,
            "失败保持 PENDING 供重试（主链现状不动）",
        )
    }

    @Test
    fun `export kb pack attributes export file into current space`() {
        val spaceStore = SpaceStore(db())
        spaceStore.create(id = "space-distill-4", title = "导出归属测试")
        SpaceAttribution.currentSpaceIdProvider = { "space-distill-4" }

        val vm = DistillViewModel(ioDispatcher = dispatcher)
        var exportedPath: String? = null
        vm.exportKbPack { path -> exportedPath = path }

        settle { exportedPath != null }
        assertTrue(exportedPath != null, "onDone 回调到达（导出完成）")
        assertTrue(File(exportedPath!!).isFile, "导出包文件已落盘")

        settle {
            spaceStore.spaceById("space-distill-4")?.filePaths == listOf(File(exportedPath!!).absolutePath)
        }
        assertEquals(
            listOf(File(exportedPath!!).absolutePath),
            spaceStore.spaceById("space-distill-4")?.filePaths,
            "挂点 5：导出包 absolutePath 归属当前空间",
        )
    }
}
