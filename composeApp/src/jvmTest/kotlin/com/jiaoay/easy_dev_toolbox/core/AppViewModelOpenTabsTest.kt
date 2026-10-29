package com.jiaoay.easy_dev_toolbox.core

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.host.EasyDataManager
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * openTabs 文档集状态机验收（R1 导航重构 2026-09-25 · 空间/文档分离 · 维护者拍板）：
 * **dock 是空间，tab 是你打开的东西**。
 *
 * 1. 初值 = 空集（旧固定三 tab 随固定栏概念删除；空集 = tab 条 Auto-hide）；
 * 2. setCurrentPage 三分流语义：空间页/单开系统页切换不产生 tab；文档页首次切入保序追加
 *    （🔴 单开系统页批 2026-09-26：设置/AI 设置/MCP/调试随维护者拍板移出 tab 集）；
 * 3. closeTab：文档 tab 全可关；关当前文档页落点回家；
 * 4. 恢复 seeds：last_page 恢复目标是文档页才进 tab 集；空间页只切空间；
 *    AppCenter / Flow / Distill / VoiceTranscribe 存量值改道（家 / AgentCenter）。
 *
 * VM 以隔离 DataStore + Main dispatcher 替换构造（先例：PageUsageTrackerTest）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelOpenTabsTest {

    /** user.home 隔离（共享件 UserHomeIsolation——审计批 2026-09-27 收敛） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-opentabs-test")

    private val dispatcher = StandardTestDispatcher()
    private lateinit var originalStore: LocalDataManager

    /**
     * DataStore IO 受控 scope（LocalDataManager 的 datastoreScope 注入缝）：Unconfined
     * 让 DataStore 文件 IO 在调用线程同步落定——drain 即全静默，无真实 IO 线程续体
     * 与 resetMain/删目录的竞态。🔴 不得改与 Main 共享 StandardTestDispatcher 的
     * scope：DataStore 挂起读写经 withContext 跳到注入 scope，本类 runBlocking 直读
     * 会因调度器无人推进而死锁。
     */
    private val datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
        originalStore = EasyDataManager.store
        EasyDataManager.store = LocalDataManager(
            dataStoreName = "test_opentabs_${UUID.randomUUID()}",
            // redirect 窗口内 eager 解析并钉死目录（LocalDataManager 构造参数契约），
            // 还原 user.home 后的迟到首操作不得落错目录
            datastoreDir = FileUtils.getDatastoreDir(),
            datastoreScope = datastoreScope,
        )
    }

    @AfterTest
    fun tearDown() {
        // DataStore IO 已随 Unconfined scope 同步落定——drain 完整静默后再删目录/还原：
        // 排空在前则无任何在途写会撞被删目录
        dispatcher.scheduler.advanceUntilIdle()
        homeIsolation.restore()
        EasyDataManager.store = originalStore
        Dispatchers.resetMain()
    }

    private fun advanceUntilIdle() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    // ── 1 · 初值 = 空集（零固定栏） ──

    @Test
    fun `open tabs start empty with no fixed tabs`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()
        assertEquals(
            emptyList(),
            viewModel.openTabs.value,
            "openTabs 初值 = 空集（旧固定三 tab 删除——空集是常态，tab 条 Auto-hide）",
        )
        assertEquals(ToolPage.LogWorkbench, viewModel.currentPage.value, "空间态初值 = 工作台（dock 空间直达）")
        viewModel.viewModelScope.cancel()
    }

    // ── 2 · setCurrentPage 分流语义 ──

    @Test
    fun `space page switch never creates a tab`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        // 五空间轮切：openTabs 恒空（空间切换只动 currentPage）
        listOf(ToolPage.AgentCenter, ToolPage.ShellTools, ToolPage.LarkBridge, ToolPage.Tools, ToolPage.LogWorkbench)
            .forEach { page -> viewModel.setCurrentPage(page) }
        advanceUntilIdle()
        assertEquals(
            emptyList(),
            viewModel.openTabs.value,
            "空间页（五空间全集）切换不产生 tab——dock 是唯一空间导航",
        )
        assertEquals(ToolPage.LogWorkbench, viewModel.currentPage.value)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `document page open appends tab in order`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        viewModel.setCurrentPage(ToolPage.Report)
        advanceUntilIdle()
        assertEquals(
            listOf(ToolPage.Report),
            viewModel.openTabs.value,
            "文档页（报告）首次切入保序追加为 tab",
        )

        // 已打开文档再切：不重复追加、不重排
        viewModel.setCurrentPage(ToolPage.AiSettings)
        viewModel.setCurrentPage(ToolPage.Report)
        advanceUntilIdle()
        assertEquals(
            listOf(ToolPage.Report),
            viewModel.openTabs.value,
            "🔴 单开系统页批（2026-09-26 维护者拍板）：AI 设置不进 tab 集——" +
                "「不会多开的页放 tab 有点离谱」；报告（真文档）保留且不重排",
        )
        assertEquals(ToolPage.Report, viewModel.currentPage.value)
        viewModel.viewModelScope.cancel()
    }

    // ── 2b · 单开系统页批（2026-09-26 维护者拍板）──

    @Test
    fun `single session pages never enter tab set`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        // 四单开系统页轮切：openTabs 恒空（同空间页切换语义）——SYS-15 起轮切含 Debug
        //（返回链退役后调试页仍走 setCurrentPage 单开语义，零 previousPage 状态参与）
        listOf(ToolPage.Setting, ToolPage.AiSettings, ToolPage.McpExpose, ToolPage.Debug)
            .forEach { page -> viewModel.setCurrentPage(page) }
        advanceUntilIdle()
        assertEquals(
            emptyList(),
            viewModel.openTabs.value,
            "单开系统页（设置/AI 设置/MCP/调试）切换不产生 tab——不会多开的页不占 tab 位",
        )
        assertEquals(ToolPage.Debug, viewModel.currentPage.value)
        viewModel.viewModelScope.cancel()
    }

    // ── 3 · closeTab 语义（文档 tab 全可关） ──

    @Test
    fun `closeTab of current document falls back home and persists last page`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        viewModel.setCurrentPage(ToolPage.Report)
        viewModel.closeTab(ToolPage.Report)
        advanceUntilIdle()
        assertEquals(
            emptyList(),
            viewModel.openTabs.value,
            "关当前文档 tab——页移出 openTabs（空集回 Auto-hide）",
        )
        assertEquals(ToolPage.LogWorkbench, viewModel.currentPage.value, "关当前文档 tab 落点回家")
        val persisted = kotlinx.coroutines.runBlocking { EasyDataManager.getLastPageName() }
        assertEquals(
            ToolPage.LogWorkbench.name,
            persisted,
            "回家分支经 setCurrentPage 顺带持久化 last_page",
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `closeTab of non-current document keeps current page`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        viewModel.setCurrentPage(ToolPage.Report)
        viewModel.setCurrentPage(ToolPage.Setting)
        viewModel.closeTab(ToolPage.Report)
        advanceUntilIdle()
        assertEquals(
            emptyList(),
            viewModel.openTabs.value,
            "🔴 单开系统页批：Setting 不进 tab 集（关报告后空集）；关非当前文档只移除该页",
        )
        assertEquals(ToolPage.Setting, viewModel.currentPage.value, "关非当前页不动 currentPage")
        viewModel.viewModelScope.cancel()
    }

    // ── 4 · 恢复 seeds（last_page 改道 + 空间/文档分流） ──
    // 恢复协程挂 viewModelScope（Main 测试调度器），DataStore 读写经注入 Unconfined
    //    scope 同步落定——轮询只等 VM 恢复协程被调度器排出（首拍即落定，
    //    循环保留作跨调度器链路兜底，上限 5s 防死等）。

    private fun awaitRestored(viewModel: AppViewModel, expected: ToolPage) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            if (viewModel.currentPage.value == expected) return
            Thread.sleep(50)
        }
        // 落定后 IO 续体回 Main 队列可能尚未排空——最后一次排空后由断言判定
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `restore of document page seeds its tab`() = kotlinx.coroutines.runBlocking {
        // 存量 last_page=Setting（单开系统页）：恢复只切页——🔴 单开系统页批
        // （2026-09-26）起 Setting 不再 seeds 进 openTabs（不会多开的页不占 tab 位）
        EasyDataManager.setLastPageWithUsageCounts(ToolPage.Setting.name, null)

        val viewModel = AppViewModel()
        awaitRestored(viewModel, ToolPage.Setting)

        assertEquals(
            emptyList(),
            viewModel.openTabs.value,
            "恢复目标是单开系统页——只切页不进 tab 集（tab 位语义污染翻转）",
        )
        assertEquals(ToolPage.Setting, viewModel.currentPage.value)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `restore of space page does not seed a tab`() = kotlinx.coroutines.runBlocking {
        // 存量 last_page=ShellTools（空间页）：恢复只切空间，不产生 tab
        EasyDataManager.setLastPageWithUsageCounts(ToolPage.ShellTools.name, null)

        val viewModel = AppViewModel()
        awaitRestored(viewModel, ToolPage.ShellTools)

        assertEquals(
            emptyList(),
            viewModel.openTabs.value,
            "恢复目标是空间页——只切空间不进 tab 集（dock 直达语义）",
        )
        assertEquals(ToolPage.ShellTools, viewModel.currentPage.value)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `restore of retired ai pages redirects to agent center without tab`() = kotlinx.coroutines.runBlocking {
        // 存量 last_page ∈ {Flow/Distill/VoiceTranscribe}（随 AI 聚合批退役）——
        // 改道智能体中心聚合页；改道目标是空间页——只切空间不产生 tab
        EasyDataManager.setLastPageWithUsageCounts(ToolPage.Flow.name, null)

        val viewModel = AppViewModel()
        awaitRestored(viewModel, ToolPage.AgentCenter)

        assertEquals(ToolPage.AgentCenter, viewModel.currentPage.value, "退役 AI 页恢复改道智能体中心")
        assertEquals(
            emptyList(),
            viewModel.openTabs.value,
            "改道目标是空间页——不进 tab 集（旧断言「AgentCenter ∈ openTabs」随空间/文档分离翻转）",
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `restore of app center redirects to workbench home`() = kotlinx.coroutines.runBlocking {
        // 存量 last_page=AppCenter（反馈 6 页退役）：改道工作台家（既有兜底保留）。
        // 🔴 LogWorkbench 是 _currentPage 初值——currentPage 判据失效，改轮询
        //    openTabs 收敛（恢复协程跑完的强信号 = store 读链完成，而
        //    last_page 写被 setCurrentPage 干扰。此处用「恢复协程完成」等价判据：
        //    last_page 读到的改道值只在恢复协程里出现一次，等待 Main 队列排空后
        //    断言 currentPage 恒家即可——AppCenter 改道若失效会停在 AppCenter）
        EasyDataManager.setLastPageWithUsageCounts(ToolPage.AppCenter.name, null)

        val viewModel = AppViewModel()
        awaitRestored(viewModel, ToolPage.LogWorkbench)
        Thread.sleep(300)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ToolPage.LogWorkbench, viewModel.currentPage.value, "AppCenter 恢复改道工作台家（不落 AppCenter）")
        assertTrue(
            viewModel.currentPage.value != ToolPage.AppCenter,
            "AppCenter 枚举值保留但恢复不可落定其上",
        )
        viewModel.viewModelScope.cancel()
    }

    // ── 5 · TL-02 预选子签槽（重设计批 4 · palette 深入模式路由聚合签）──
    // 槽语义：写入（setPendingToolsTab）→ 一次性取用（consumePendingToolsTab 读即清槽）
    // ——ToolsPage 首次组合 LaunchedEffect 读一次设 tab 初值；越界值由消费方回 0
    //（ToolsPage 侧 `if (pending in 0..4)` 防御，槽层透传不拦）。

    @Test
    fun `pending tools tab write then one-shot consume clears the slot`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        assertEquals(null, viewModel.pendingToolsTab.value, "预选槽初值 = null（无预选载荷）")
        viewModel.setPendingToolsTab(4)
        assertEquals(4, viewModel.pendingToolsTab.value, "写入后槽值可读（App.kt 接线换算 hostIndexOf 载荷）")

        assertEquals(4, viewModel.consumePendingToolsTab(), "一次性取用返回槽值")
        assertEquals(null, viewModel.pendingToolsTab.value, "取用即清槽——payload 只被首个 ToolsPage 消费")
        assertEquals(null, viewModel.consumePendingToolsTab(), "二次取用恒 null（清槽后不复活）")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `pending tools tab accepts null and arbitrary int without crashing`() {
        // 槽层透传语义：null = 无预选（palette 深入模式跳非五件套聚合页不带载荷）；
        // 越界 int（手工注入/映射漂移）不拦——消费方 ToolsPage 回 0，槽层不猜语义。
        val viewModel = AppViewModel()
        advanceUntilIdle()

        viewModel.setPendingToolsTab(null)
        assertEquals(null, viewModel.consumePendingToolsTab(), "null 写入透传（无预选）")

        viewModel.setPendingToolsTab(9)
        assertEquals(9, viewModel.consumePendingToolsTab(), "越界值槽层透传（消费方 ToolsPage 回 0 防御）")
        viewModel.viewModelScope.cancel()
    }

    // ── 6 · AG-15 聚合页预选子签槽（重设计批 5 · 三退役 AI 散页深链）──
    // 槽语义与 pendingToolsTab 三件套逐字同构：写入（setPendingAgentCenterTab）→
    // 一次性取用（consumePendingAgentCenterTab 读即清槽）——AgentCenterPage 订阅
    // 槽流消费落子签；越界值由消费方回 0（AgentCenterPage 侧 `if (index in 0..3)`）。

    @Test
    fun `pending agent center tab write then one-shot consume clears the slot`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        assertEquals(null, viewModel.pendingAgentCenterTab.value, "聚合页预选槽初值 = null（无预选载荷）")
        viewModel.setPendingAgentCenterTab(1)
        assertEquals(1, viewModel.pendingAgentCenterTab.value, "写入后槽值可读（App.kt 接线换算 agentCenterIndexOf 载荷）")

        assertEquals(1, viewModel.consumePendingAgentCenterTab(), "一次性取用返回槽值")
        assertEquals(null, viewModel.pendingAgentCenterTab.value, "取用即清槽——payload 只被首个 AgentCenterPage 消费")
        assertEquals(null, viewModel.consumePendingAgentCenterTab(), "二次取用恒 null（清槽后不复活）")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `pending agent center tab accepts null and arbitrary int without crashing`() {
        // 槽层透传语义（pendingToolsTab 同款）：null = 无预选（palette 行点击跳非
        // 散页候选不带载荷）；越界 int 不拦——消费方 AgentCenterPage 回 0。
        val viewModel = AppViewModel()
        advanceUntilIdle()

        viewModel.setPendingAgentCenterTab(null)
        assertEquals(null, viewModel.consumePendingAgentCenterTab(), "null 写入透传（无预选）")

        viewModel.setPendingAgentCenterTab(7)
        assertEquals(7, viewModel.consumePendingAgentCenterTab(), "越界值槽层透传（消费方 AgentCenterPage 回 0 防御）")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `set current page with retired ai page seeds the agent center tab slot`() {
        // AG-15 setCurrentPage 内置置槽（防御性单点）：以三退役 AI 散页枚举直调
        // setCurrentPage 的路径自动带聚合页子签载荷——散页直调漏网时落聚合页对应
        // 子签而非恒第一签。
        val viewModel = AppViewModel()
        advanceUntilIdle()

        viewModel.setCurrentPage(ToolPage.Distill)
        assertEquals(ToolPage.Distill, viewModel.currentPage.value, "setCurrentPage 原样切页（槽不拦截页落定）")
        assertEquals(1, viewModel.consumePendingAgentCenterTab(), "setCurrentPage(Distill) 置槽 = 第 2 签（知识蒸馏）")

        viewModel.setCurrentPage(ToolPage.Flow)
        assertEquals(0, viewModel.consumePendingAgentCenterTab(), "setCurrentPage(Flow) 置槽 = 第 1 签（会话）")

        viewModel.setCurrentPage(ToolPage.AgentCenter)
        assertEquals(null, viewModel.consumePendingAgentCenterTab(), "setCurrentPage(AgentCenter) 直跳聚合页无预选载荷")

        viewModel.setCurrentPage(ToolPage.Tools)
        assertEquals(null, viewModel.consumePendingAgentCenterTab(), "setCurrentPage(Tools) 不误伤聚合页槽")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `restore of retired distill page seeds tab slot one and lands on agent center`() = kotlinx.coroutines.runBlocking {
        // AG-15 恢复路径带签置槽（卡面 problem ① 第二半）：存量 last_page=Distill
        // ——restoredPageTarget 归并落聚合页（既有断言），本卡补「带签」：raw 枚举
        // 在归并前换算子签载荷 → 槽值 1（第二签知识蒸馏），AgentCenterPage 消费
        // 不再恒落第一签（会话）。
        EasyDataManager.setLastPageWithUsageCounts(ToolPage.Distill.name, null)

        val viewModel = AppViewModel()
        awaitRestored(viewModel, ToolPage.AgentCenter)

        assertEquals(ToolPage.AgentCenter, viewModel.currentPage.value, "退役蒸馏页恢复改道智能体中心（既有语义保持）")
        assertEquals(
            1,
            viewModel.pendingAgentCenterTab.value,
            "AG-15：恢复改道必须带签置槽（Distill → 1 第二签），聚合页消费不落第一签",
        )
        assertEquals(
            emptyList(),
            viewModel.openTabs.value,
            "改道目标是空间页——不进 tab 集（既有语义保持）",
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `restore of non retired page leaves the agent center slot empty`() = kotlinx.coroutines.runBlocking {
        // 置槽不误伤：恢复目标是非退役页（空间页/文档页）时槽恒空——AgentCenterPage
        // 首次组合消费 null 落默认第一签（会话），深链语义不泄漏。
        EasyDataManager.setLastPageWithUsageCounts(ToolPage.ShellTools.name, null)

        val viewModel = AppViewModel()
        awaitRestored(viewModel, ToolPage.ShellTools)

        assertEquals(ToolPage.ShellTools, viewModel.currentPage.value)
        assertEquals(null, viewModel.pendingAgentCenterTab.value, "非退役页恢复不置聚合页槽（槽空 = 无预选）")
        viewModel.viewModelScope.cancel()
    }
}
