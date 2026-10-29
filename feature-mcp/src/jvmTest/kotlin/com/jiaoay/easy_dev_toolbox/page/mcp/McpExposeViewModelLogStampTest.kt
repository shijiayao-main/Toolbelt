package com.jiaoay.easy_dev_toolbox.page.mcp

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.testfixtures.UserHomeIsolation
import java.time.LocalTime
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
import kotlin.test.assertTrue

/**
 * SYS-07 · 重设计批 4：MCP 服务日志行加 HH:mm:ss 时间戳。
 *
 * 旧缺陷：appendLog 内 `val stamped = line` 原样透传——变量名暗示加时间戳意图未
 * 实现，端口变更/启停/失败事件无时间标记，排障时无法与 JLog/audit 时间线对齐。
 *
 * 新形态：[stampLogLine] 单点收口（HH:mm:ss 前缀 + 空格 + 原文案）——VM 五个事件
 * 出口（开启/端点就绪/关闭/端口变更重启/端口已保存）全部经 appendLog 走此函数。
 *
 * 🔴 纪律同 McpExposeViewModelActiveRunsTest：setMain（viewModelScope = Main）+
 * user.home 隔离（VM init 读 LlmConfigStore DataStore）+ restoreOnly 不删目录。
 *
 * 🔴 双时钟轮询：commitPort 链经注入 Unconfined datastoreScope 同步落定 DataStore
 * 写——轮询 = 真实 sleep + 虚拟时钟推进双管齐下，环本体保留作跨调度器链路兜底
 * （超时上限 5s 防死等）。
 *
 * 🔴 时间敏感断言规避：纯函数直测注入固定 [LocalTime]（不走墙钟）；VM 间接路径
 * 只断言 HH:mm:ss 正则形态（不锁具体时刻——跨午夜/慢机器均不 flaky）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpExposeViewModelLogStampTest {

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
    private val home = UserHomeIsolation("edt-mcp-log-stamp-test")

    /** VM 事件日志行的时间戳形态（恒 HH:mm:ss 无日期——页内环形 200 行语境）。 */
    private val stampedLinePattern = Regex("""^\d{2}:\d{2}:\d{2} .+""")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
        originalStore = LlmConfigStore.store
        // redirect 窗口内 eager 解析并钉死目录（LocalDataManager 构造参数契约），
        // 还原 user.home 后的迟到首操作不得落错目录
        LlmConfigStore.store = LocalDataManager(
            dataStoreName = "test_mcp_logstamp_${java.util.UUID.randomUUID()}",
            datastoreDir = FileUtils.getDatastoreDir(),
            datastoreScope = datastoreScope,
        )
        McpServerManager.resetForTest() // 状态面回 STOPPED → commitPort 走「未运行」分支（零真 server）
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

    // ── 纯函数直测（固定时刻注入——不依赖墙钟） ──

    @Test
    fun `stampLogLine prefixes HH mm ss timestamp keeping original text`() {
        val stamped = stampLogLine("开启服务（端口 8765）…", LocalTime.of(9, 30, 5))

        assertEquals(
            "09:30:05 开启服务（端口 8765）…",
            stamped,
            "时间戳前缀 + 单空格 + 原文案（SYS-07：既有事件文案不变）",
        )
    }

    @Test
    fun `stampLogLine keeps midnight single digit hour zero padded`() {
        val stamped = stampLogLine("端口已保存（9000，服务未运行）", LocalTime.of(0, 0, 0))

        assertEquals(
            "00:00:00 端口已保存（9000，服务未运行）",
            stamped,
            "时/分/秒均两位补零（DateTimeFormatter HH:mm:ss 契约）",
        )
    }

    // ── VM 间接路径（appendLog private——经公开动作触发后断言日志台形态） ──

    @Test
    fun `vm event log lines carry HH mm ss prefix`() {
        val vm = McpExposeViewModel()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.logLines.value.isEmpty(), "前置：构造后日志台为空")

        // 间接触发：未运行态提交合法端口 → appendLog（"端口已保存"行经 stampLogLine）
        vm.commitPort(9000)
        awaitCondition("未运行态端口提交产生日志行") { vm.logLines.value.isNotEmpty() }

        assertEquals(1, vm.logLines.value.size, "未运行态端口提交 = 恰一行日志")
        assertTrue(
            stampedLinePattern.matches(vm.logLines.value.single()),
            "日志行必须带 HH:mm:ss 前缀：实际=${vm.logLines.value.single()}",
        )
        assertTrue(
            "端口已保存（9000，服务未运行）" in vm.logLines.value.single(),
            "时间戳前缀不得吞改事件文案（SYS-07：既有文案不变）",
        )
    }

    @Test
    fun `vm event log stamps every line across repeated commits`() {
        val vm = McpExposeViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        // 多次端口提交（未运行态）：每条都走 stampLogLine
        repeat(3) { i ->
            vm.commitPort(9000 + i)
            awaitCondition("第 ${i + 1} 次提交产生日志行") { vm.logLines.value.size == i + 1 }
        }

        assertEquals(3, vm.logLines.value.size, "三次提交 = 三行日志")
        vm.logLines.value.forEach { line ->
            assertTrue(
                stampedLinePattern.matches(line),
                "每行日志都须带 HH:mm:ss 前缀：实际=$line",
            )
        }
    }

    /**
     * 双时钟轮询：advanceUntilIdle 推进虚拟时钟消费 Main 队列，真实 sleep 让出
     * 线程给跨调度器链路兜底——条件满足即返回，超时上限 5s 防死等。
     */
    private fun awaitCondition(message: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待超时：$message" }
            Thread.sleep(50)
            dispatcher.scheduler.advanceUntilIdle()
        }
    }
}
