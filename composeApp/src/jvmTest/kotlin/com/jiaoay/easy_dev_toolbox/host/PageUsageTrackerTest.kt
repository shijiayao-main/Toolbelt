package com.jiaoay.easy_dev_toolbox.host

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工具页使用统计验收（批 3 基建 → 2026-09-23 反馈 4 口径反转）。
 *
 * 口径（反馈 4）：**使用 = 核心动作完成/发起**，非页面浏览——
 * 1. tracker 纯内存行为——信号页计数递增、结构性页（设置/应用中心/报告/调试）不计；
 * 2. **点击页面不计数**（口径反转主体）：setCurrentPage 后 counts 为空；
 * 3. **信号到达计数 +1 且落盘**：ToolUsageSignal.report → 订阅链计数 + setUsageCounts 落盘；
 * 4. 持久化往返——落盘 JSON 重启（新 tracker + load）后计数恢复；
 * 5. 损坏数据回落——page_use_counts 存非法 JSON 时读回空 map 不致命。
 *
 * VM 以隔离 DataStore + Main dispatcher 替换构造（先例：ShellToolsViewModelDeviceSelectionTest）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageUsageTrackerTest {

    /** user.home 隔离（共享件 UserHomeIsolation——审计批 2026-09-27 收敛） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-usage-test")

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
            dataStoreName = "test_usage_${UUID.randomUUID()}",
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
        // init 与信号订阅均挂 viewModelScope（Main 测试调度器）——推进直至无待办任务
        dispatcher.scheduler.advanceUntilIdle()
    }

    // ── 1. tracker 纯内存行为 ──

    @Test
    fun `tool page increments and serializes`() {
        val tracker = PageUsageTracker()

        val first = tracker.incrementAndSerialize(ToolPage.XLogDecoder)
        assertTrue(first != null, "统计页首次使用信号应产出计数快照")

        val second = tracker.incrementAndSerialize(ToolPage.XLogDecoder)
        assertEquals(2, tracker.countOf(ToolPage.XLogDecoder), "两次使用信号后计数应为 2")

        assertTrue(second != null)
    }

    @Test
    fun `structural pages are not counted`() {
        val tracker = PageUsageTracker()

        assertNull(tracker.incrementAndSerialize(ToolPage.Setting), "设置页是结构性页面，不计数")
        assertNull(tracker.incrementAndSerialize(ToolPage.Debug), "调试页是结构性页面，不计数")
        assertNull(tracker.incrementAndSerialize(ToolPage.AppCenter), "应用中心是结构性页面（枚举值三段式保留），不计数")
        assertEquals(0, tracker.countOf(ToolPage.Setting), "结构性页面计数恒 0")
        assertTrue(tracker.snapshot().isEmpty(), "无使用信号时快照为空")
    }

    // ── 2. 反馈 4 口径反转主体：点击页面不计数 ──

    @Test
    fun `setCurrentPage does not count usage anymore`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        viewModel.setCurrentPage(ToolPage.Timestamp)
        viewModel.setCurrentPage(ToolPage.ShellTools)
        advanceUntilIdle()

        // 页面浏览 ≠ 使用：切页后计数必须为空（信号未到，只写 last_page）
        val persisted = kotlinx.coroutines.runBlocking { EasyDataManager.getPageUseCounts() }
        assertTrue(
            persisted.isEmpty(),
            "反馈 4：点击页面不计数——切页后 page_use_counts 必须为空（计数只由核心动作信号驱动）",
        )
        viewModel.viewModelScope.cancel()
    }

    // ── 3. 信号到达计数 +1 且落盘（round-trip）──

    @Test
    fun `usage signal counts and persists via app view model subscription`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        // 信号 → AppViewModel init 订阅 → loadEnum 映射 → tracker +1 → setUsageCounts 落盘
        ToolUsageSignal.report(ToolUsageSignal.PageId.TIMESTAMP)
        advanceUntilIdle()

        val persisted = kotlinx.coroutines.runBlocking { EasyDataManager.getPageUseCounts() }
        assertEquals(
            1,
            persisted[ToolPage.Timestamp.name],
            "反馈 4：核心动作信号到达后计数 +1 并经 setUsageCounts 落盘",
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `unknown usage signal page id is ignored without polluting counts`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        // 拼写漂移/已删枚举名（Default）：订阅链 loadEnum 兜底落 LogWorkbench 与
        // pageId 不符 → JLog 忽略，不计数（防误归因到家页）
        ToolUsageSignal.report("NoSuchPage")
        ToolUsageSignal.report("Default")
        advanceUntilIdle()

        val persisted = kotlinx.coroutines.runBlocking { EasyDataManager.getPageUseCounts() }
        assertTrue(
            persisted.isEmpty(),
            "未知 pageId 信号必须被忽略（不得误计数进 LogWorkbench 或任何页）",
        )
        viewModel.viewModelScope.cancel()
    }

    // ── 4. 持久化往返 ──

    @Test
    fun `counts survive restart via load`() {
        val viewModel = AppViewModel()
        advanceUntilIdle()

        ToolUsageSignal.report(ToolUsageSignal.PageId.XLOG_DECODER)
        ToolUsageSignal.report(ToolUsageSignal.PageId.XLOG_DECODER)
        ToolUsageSignal.report(ToolUsageSignal.PageId.SHELL_TOOLS)
        advanceUntilIdle()

        // 订阅链挂 viewModelScope（Main 测试调度器），DataStore 写经注入 Unconfined
        //    scope 同步落定——首拍 advance 后落盘值即到位；循环保留作跨调度器链路
        //    兜底（上限 5s 防死等）。
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            val snapshot = kotlinx.coroutines.runBlocking { EasyDataManager.getPageUseCounts() }
            if (snapshot[ToolPage.XLogDecoder.name] == 2 && snapshot[ToolPage.ShellTools.name] == 1) break
            Thread.sleep(50)
        }

        // 模拟重启：新 tracker 从 DataStore 回填
        val restarted = PageUsageTracker()
        kotlinx.coroutines.runBlocking { restarted.load() }

        assertEquals(2, restarted.countOf(ToolPage.XLogDecoder), "重启后 XLogDecoder 计数应恢复为 2")
        assertEquals(1, restarted.countOf(ToolPage.ShellTools), "重启后 ShellTools 计数应恢复为 1")
        viewModel.viewModelScope.cancel()
    }

    // ── 5. 损坏数据回落 ──

    @Test
    fun `corrupted counts json falls back to empty map`() = kotlinx.coroutines.runBlocking {
        // 口径反转（反馈 4）后 counts 走 setUsageCounts 独立写：直接写入非法 JSON，
        // 读回必须回落空 map 不抛（setLastPageWithUsageCounts 已不写 counts，用它写等于空转）
        EasyDataManager.setUsageCounts("corrupted-json{{")
        assertTrue(EasyDataManager.getPageUseCounts().isEmpty(), "非法 JSON 应回落空 map 不抛异常")
    }

    @Test
    fun `deleted enum name in last_page falls back to workbench home via loadEnum`() = kotlinx.coroutines.runBlocking {
        // S7 枚举收敛验收：存量用户 last_page 存已删枚举名（Default 欢迎页）——
        // loadEnum 兜底落工作台家（AppViewModel init :100 钉死 LogWorkbench 默认）
        EasyDataManager.setLastPageWithUsageCounts("Default", null)
        assertEquals("Default", EasyDataManager.getLastPageName(), "原始字符串原样读回（解码兜底在 loadEnum 消费侧）")
        val restored = com.jiaoay.easy_dev_toolbox.core.data.loadEnum<ToolPage>("Default", ToolPage.LogWorkbench)
        assertEquals(ToolPage.LogWorkbench, restored, "已删枚举名经 loadEnum 必须落工作台家（禁 valueOf 裸调）")
    }

    @Test
    fun `all ToolUsageSignal page ids resolve to real ToolPage enum entries`() = kotlinx.coroutines.runBlocking {
        // PageId 拼写防漂移全量锚（:core 引不到 ToolPage，一致性在此背书）：
        // 13 个 id 全部必须 loadEnum 解析成功——任一漂移即该页计数静默丢（宿主
        // 订阅链未知 id 忽略 + JLog 兜底），故用全量断言而非只测三样。
        val ids = listOf(
            ToolUsageSignal.PageId.XLOG_DECODER,
            ToolUsageSignal.PageId.LOG_TAG_FILTER,
            ToolUsageSignal.PageId.FILE_SPLITTER,
            ToolUsageSignal.PageId.SHELL_TOOLS,
            ToolUsageSignal.PageId.TIMESTAMP,
            ToolUsageSignal.PageId.ARCHIVE,
            ToolUsageSignal.PageId.LARK_BRIDGE,
            ToolUsageSignal.PageId.MCP_EXPOSE,
            ToolUsageSignal.PageId.AI_SETTINGS,
            ToolUsageSignal.PageId.DISTILL,
            ToolUsageSignal.PageId.LOG_WORKBENCH,
            ToolUsageSignal.PageId.VOICE_TRANSCRIBE,
            ToolUsageSignal.PageId.FLOW,
        )
        val unresolved = ids.filter {
            com.jiaoay.easy_dev_toolbox.core.data.loadEnum<ToolPage>(it, ToolPage.LogWorkbench) == ToolPage.LogWorkbench &&
                it != "LogWorkbench"
        }
        assertTrue(
            unresolved.isEmpty(),
            "PageId 拼写漂移（loadEnum 解析落兜底默认）：$unresolved——修正 ToolUsageSignal 常量拼写",
        )
    }
}
