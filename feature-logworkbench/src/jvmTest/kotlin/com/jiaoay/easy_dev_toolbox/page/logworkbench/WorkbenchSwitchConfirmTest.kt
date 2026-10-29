package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.data.db.SqliteSessionStore
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import com.jiaoay.testfixtures.UserHomeIsolation
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 换 run 视图前轻确认 + 表单覆盖提示守护。
 *
 * VM 判定源 [WorkbenchViewModel.hasUncommittedContent] 直测三态 + 页面确认卡
 * 渲染面源码断言（RunHistoryList pendingDelete 同款局部态配方——拦截缝在
 * WorkbenchSidePanelContent，覆盖 openRun 与 startAnalysis 两入口）。
 *
 * 🔴 Main dispatcher 必须替换（WorkbenchViewModelAiRefreshTest 同款纪律）：
 *    VM init 的 refreshRuns 挂 viewModelScope（Main.immediate）。
 * 🔴 VM 构造触 Database.database() 真单例——user.home 隔离（数据根落临时目录，
 *    同款隔离下 RunStore/SqliteSessionStore 经同一 Database 单例互通——run 落库
 *    即 VM 可见）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchSwitchConfirmTest {

    private val dispatcher = StandardTestDispatcher()

    /** user.home 隔离：VM 构造触 Database.database() 真单例——数据根须落临时目录 */
    private val home = UserHomeIsolation("edt-wb-switch-confirm-test")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
    }

    // IO 调度器注入缝：VM 全部 IO 段与 Main 共享同一 TestScheduler——init/收尾
    // 协程不飞真实 IO 线程，用例收尾 advanceUntilIdle 即确定性排空（无跨用例续体）。
    private val createdVms = mutableListOf<WorkbenchViewModel>()

    private fun vmTracked(): WorkbenchViewModel =
        WorkbenchViewModel(ioDispatcher = dispatcher).also { createdVms += it }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        home.restoreOnly() // 🔴 只还原不删（DataStore 在途写 flaky 先例——目录留 /tmp）
        // 🔴 Database 进程单例会在此类的隔离 user.home 上懒初始化钉住空临时库——
        //    不重置会跨测试类污染（WorkbenchSessionTest 的 knowledge 装配门控读到空库、
        //    knowledge 三件不装 → 顺序型 flaky）。
        Database.resetForTest() // 🔴 只还原不删（DataStore 在途写 flaky 先例——目录留 /tmp）
    }

    // ── VM 判定源直测 ──

    /** 轮询推进断言兜底：注入后 advanceUntilIdle 已确定性收口，循环首拍即中，防御性保留 */
    private fun awaitTrue(probe: () -> Boolean): Boolean {
        repeat(100) {
            if (probe()) return true
            Thread.sleep(20)
            dispatcher.scheduler.advanceUntilIdle()
        }
        return probe()
    }

    @Test
    fun `空会话恒无未收口内容——不弹卡`() {
        val vm = vmTracked()
        assertFalse(
            vm.hasUncommittedContent(),
            "构造期占位会话（entries 空 + PENDING run）不得弹确认——acceptance「空会话不弹卡」",
        )
    }

    @Test
    fun `回看有证据流的 run 后判定未收口——弹卡判据成立`() {
        // run 落库 + 时间线落一条 → openRun restore 后 entries 非空且 runId 非 PENDING
        val db = Database.database()
        val store = RunStore(db)
        val timeline = SqliteSessionStore(db)
        store.create(
            id = "run-wb05-1",
            title = "wb05 样本",
            logFiles = listOf("/tmp/sample.alog.txt"),
            status = RunStore.RunStatus.COMPLETED,
        )
        timeline.append(
            "run-wb05-1",
            ChatEntry.Assistant(id = "wb05-entry-1", text = "研判结论样本"),
        )

        val vm = vmTracked()
        vm.openRun("run-wb05-1")

        assertTrue(
            awaitTrue { vm.hasUncommittedContent() },
            "回看含证据流的 run 后视图已是未收口内容——换视图/再分析须先确认（确认卡显示判据）",
        )
    }

    @Test
    fun `openRun 后 currentRunIdForUi 与目标 run 同值——页面目标相同判定可单源对照`() {
        // 「同 run 重开不弹」由页面拦截缝 `id != vm.currentRunIdForUi()` 承担
        // （源码断言钉死）——本用例钉判定源与 currentRunIdForUi 的同源关系
        val db = Database.database()
        val store = RunStore(db)
        val timeline = SqliteSessionStore(db)
        store.create(
            id = "run-wb05-2",
            title = "wb05 同 run 样本",
            logFiles = listOf("/tmp/same.alog.txt"),
            status = RunStore.RunStatus.COMPLETED,
        )
        timeline.append(
            "run-wb05-2",
            ChatEntry.Assistant(id = "wb05-entry-2", text = "同 run 判定样本"),
        )

        val vm = vmTracked()
        vm.openRun("run-wb05-2")

        assertTrue(
            awaitTrue { vm.currentRunIdForUi() == "run-wb05-2" && vm.hasUncommittedContent() },
            "openRun 后 currentRunIdForUi 与目标 run 同值——页面「目标相同不弹」判定可单源对照",
        )
    }

    // ── 页面确认卡渲染面（源码断言——RunHistoryList pendingDelete 同款配方）──

    @Test
    fun `page intercepts openRun and startAnalysis behind confirm dialogs`() {
        val source = logWorkbenchPageSource()
        listOf(
            "hasUncommittedContent()" to "拦截判定必须消费 VM 的 hasUncommittedContent（判定单源）",
            "id != vm.currentRunIdForUi()" to "同 run 重开不弹卡（acceptance 第二项——目标相同判定）",
            "AppConfirmDialog" to "确认卡必须用共享 AppConfirmDialog（pendingDelete 同款配方）",
            "pendingOpen = id to title" to "待切 run 局部态（标题进确认文案）",
            "onOpen(run.id, run.title)" to "行点击须回调节带标题（确认卡文案数据源）",
            "onStartAnalysis = ::startAnalysisWithConfirm" to "开始分析钮须经确认拦截缝（连发新分析同款确认）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in source,
                "LogWorkbenchPage 丢失确认卡要素「$needle」——$why",
            )
        }
    }

    private fun logWorkbenchPageSource(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, PAGE_PATH)
        assertTrue(file?.isFile() == true, "源码锚文件缺失：$PAGE_PATH")
        return file.readText()
    }

    private companion object {
        const val PAGE_PATH =
            "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt"
    }
}
