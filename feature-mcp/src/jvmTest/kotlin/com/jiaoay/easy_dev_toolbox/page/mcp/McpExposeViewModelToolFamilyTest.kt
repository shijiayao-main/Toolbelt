package com.jiaoay.easy_dev_toolbox.page.mcp

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SYS-20 · 重设计批 6：外放工具清单按四族分节（VM 数据面直测）。
 *
 * 旧形态：19 项平铺 LazyColumn（描述符派生 16 项在前 + agentToolRows 3 项在后拼接），
 * 四族混排扫读困难；族语义（取证/检索/确定性管线/agent 会话）是现成分组维度。
 *
 * 新形态：[ToolRow.family] 经 [ToolFamily] 四族枚举 + 名称前缀映射（fs.extended /
 * knowledge / log / else=agent）——页面按 ToolFamily.entries 显式族序渲染
 * （页面侧源码锚见 McpExposePageGuardTest）。
 *
 * 🔴 纪律同 McpExposeViewModelActiveRunsTest：setMain（viewModelScope = Main）+
 * user.home 隔离（VM init 读 LlmConfigStore DataStore）+ restoreOnly 不删目录。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpExposeViewModelToolFamilyTest {

    // 🔴 setUp 持有调度器引用 + tearDown drain 再 resetMain（flaky 根治纪律）
    private val dispatcher = StandardTestDispatcher()

    /**
     * DataStore IO 受控 scope（LocalDataManager 的 datastoreScope 注入缝）：Unconfined
     * 让 DataStore 文件 IO 在调用线程同步落定——drain 即全静默，无真实 IO 线程续体
     * 与 resetMain 的竞态。🔴 不得改与 Main 共享 StandardTestDispatcher 的 scope：
     * DataStore 挂起读写经 withContext 跳到注入 scope，共享 TestScheduler 会因
     * 调度器无人推进而死锁。
     */
    private val datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var originalStore: LocalDataManager

    /** user.home 隔离：VM init 读 LlmConfigStore（DataStore 真单例——数据根须落临时目录） */
    private val home = UserHomeIsolation("edt-mcp-tool-family-test")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
        originalStore = LlmConfigStore.store
        // redirect 窗口内 eager 解析并钉死目录（LocalDataManager 构造参数契约），
        // 还原 user.home 后的迟到首操作不得落错目录
        LlmConfigStore.store = LocalDataManager(
            dataStoreName = "test_mcp_tool_family_${java.util.UUID.randomUUID()}",
            datastoreDir = FileUtils.getDatastoreDir(),
            datastoreScope = datastoreScope,
        )
        McpServerManager.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        // DataStore IO 已随 Unconfined datastoreScope 同步落定——排空即全静默
        dispatcher.scheduler.advanceUntilIdle()
        LlmConfigStore.store = originalStore
        Dispatchers.resetMain()
        home.restoreOnly() // 🔴 只还原不删（DataStore 实例仍引用该目录——目录留 /tmp）
        McpServerManager.resetForTest()
    }

    @Test
    fun `tools keep total count while grouping into four families`() {
        val vm = McpExposeViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val tools = vm.tools.value
        assertEquals(20, tools.size, "总计数 = 17 描述符派生 + 3 agent = 20 项")

        val byFamily = tools.groupBy { it.family }
        assertEquals(7, byFamily[ToolFamily.FS_EXTENDED].orEmpty().size, "fs.extended 取证族 = 7 件")
        assertEquals(3, byFamily[ToolFamily.KNOWLEDGE].orEmpty().size, "knowledge 检索族 = 3 件")
        assertEquals(
            7,
            byFamily[ToolFamily.LOG].orEmpty().size,
            "log 管线族 = 7 件（analyze_log + decrypt/filter_by_tag/filter_by_time/filter_by_range/split_by_size/probe_file）",
        )
        assertEquals(3, byFamily[ToolFamily.AGENT].orEmpty().size, "agent 会话族 = 3 件")
    }

    @Test
    fun `family mapping follows name prefix`() {
        val vm = McpExposeViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val families = vm.tools.value.associate { it.name to it.family }
        assertEquals(ToolFamily.FS_EXTENDED, families["fs.extended.read_file"], "fs.extended 前缀 → 取证族")
        assertEquals(ToolFamily.FS_EXTENDED, families["fs.extended.convert_timestamp"])
        assertEquals(ToolFamily.KNOWLEDGE, families["knowledge.search"], "knowledge 前缀 → 检索族")
        assertEquals(ToolFamily.LOG, families["log.analyze_log"], "log 前缀 → 管线族")
        assertEquals(ToolFamily.LOG, families["log.decrypt"])
        assertEquals(ToolFamily.LOG, families["log.filter_by_range"], "log 前缀区间截取件 → 管线族")
        assertEquals(ToolFamily.LOG, families["log.probe_file"])
        assertEquals(ToolFamily.AGENT, families["workbench_analyze"], "agent 三件 = agentToolRows 既有分组")
        assertEquals(ToolFamily.AGENT, families["distill_run"])
        assertEquals(ToolFamily.AGENT, families["workbench_status"])
    }
}
