package com.jiaoay.easy_dev_toolbox.page.debug

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellOutputSource
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SYS-03（Simple 模式可中止）VM 级验收：
 *  - 中止：stopSimple 即时恢复输入（isSendEnabled=true）并追加「已中止」STDERR 行；
 *  - 超时兜底：执行 30s 未退出（SIMPLE_RUN_TIMEOUT_MS 对齐 MARKER_DETECT_TIMEOUT_MS）
 *    自动恢复输入并出超时提示行。
 *
 * 接缝：[ShellDebugViewModel.simpleExec] 构造注入（生产默认 SimpleShell；测试注入
 * 悬挂不返回的 fake 模拟 adb logcat 类长命令，零真实进程）。
 *
 * 🔴 Main dispatcher 必须替换（viewModelScope = Dispatchers.Main.immediate）：不 setMain
 *    时 viewModelScope.launch 的协程不执行，凡断言协程结果的用例必假失败。
 *    超时用虚拟时钟推进（等待 Job 不指定 dispatcher 的设计前提，见实现处注释）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellDebugViewModelTest {

    // 🔴 setUp 持有调度器引用（drain 纪律先例：AiSettingsViewModelTest）：
    //    tearDown 先排空滞留协程再 resetMain，撞并发异常的既有 flaky 教训
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUpMain() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDownMain() {
        dispatcher.scheduler.advanceUntilIdle() // 排空滞留协程——resetMain 前必须静默
        Dispatchers.resetMain()
    }

    private fun advanceUntilIdle() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    /**
     * 只执行当前虚拟时刻已就绪的任务、不推进时钟：等待 Job 跑到挂起点即停。
     * 🔴 不能用 advanceUntilIdle——它会一路推进虚拟时钟把 30s 超时定时器也吞掉，
     *    等待 Job 直接超时完成（首轮红正是此因：执行态断言时输入已被超时分支恢复）。
     */
    private fun runCurrent() {
        dispatcher.scheduler.runCurrent()
    }

    /** fake：执行悬挂永不返回（模拟 adb logcat——waitFor 永不退出；SimpleShell 不可注入的诚实替身） */
    private fun hangingExec(): suspend (Array<String>) -> Result<ShellResult> = { awaitCancellation() }

    /** fake 执行 + 受控 IO 调度器（与 Main 共享 TestScheduler）双注入：孤儿 launcher 不飞真实 IO 线程 */
    private fun viewModelWith(exec: suspend (Array<String>) -> Result<ShellResult>) =
        ShellDebugViewModel(simpleExec = exec, ioDispatcher = dispatcher)

    private fun lastOutput(vm: ShellDebugViewModel) =
        vm.simpleMessages.value.last { it.type == DebugMessageType.OUTPUT }

    // ── 1 · 中止：停止钮即时恢复输入并追加「已中止」行 ──

    @Test
    fun `stopSimple restores input immediately and appends aborted line`() {
        val viewModel = viewModelWith(hangingExec())
        viewModel.executeSimpleCommand("adb logcat")
        runCurrent()

        // 执行中：输入锁定（停止钮可见性即 !isSendEnabled）
        assertFalse(viewModel.isSendEnabled.value, "执行中发送应锁定")
        assertTrue(
            viewModel.simpleMessages.value.any { it.type == DebugMessageType.INPUT && it.content == "adb logcat" },
            "INPUT 行应已入列",
        )

        viewModel.stopSimple()
        advanceUntilIdle()

        assertEquals(true, viewModel.isSendEnabled.value, "停止后输入应即时恢复（不等 OS 进程退出）")
        val last = lastOutput(viewModel)
        assertEquals("已中止", last.content, "应追加「已中止」输出行")
        assertEquals(ShellOutputSource.STDERR, last.source, "「已中止」行应为 STDERR 源")
        viewModel.viewModelScope.cancel()
    }

    // ── 2 · 超时兜底：30s 未退出自动恢复输入并出超时提示 ──

    @Test
    fun `simple run timeout restores input and appends timeout hint`() {
        val viewModel = viewModelWith(hangingExec())
        viewModel.executeSimpleCommand("adb logcat")
        runCurrent()
        assertFalse(viewModel.isSendEnabled.value, "执行中发送应锁定")

        // 虚拟时钟推进 30s（SIMPLE_RUN_TIMEOUT_MS = MARKER_DETECT_TIMEOUT_MS = 30_000L）
        dispatcher.scheduler.advanceTimeBy(30_000L)
        dispatcher.scheduler.runCurrent()

        assertEquals(true, viewModel.isSendEnabled.value, "超时应自动恢复输入（不杀 OS 进程，仅解锁）")
        val last = lastOutput(viewModel)
        assertTrue(
            last.content.contains("命令超时 30s 未退出"),
            "应追加超时提示 STDERR 行，实际=${last.content}",
        )
        assertEquals(ShellOutputSource.STDERR, last.source, "超时提示行应为 STDERR 源")
        viewModel.viewModelScope.cancel()
    }

    // ── 3 · SYS-13：会话进程死亡自动收口（真短命进程验收） ──

    /**
     * 🔴 Interactive 会话链不可注入 fake（startSession 内固定 new InteractiveShell → 真进程），
     *    用真短命进程 `/bin/sh -c 'echo hi; exit'` 验收整链：进程退出 → exited 单信号 →
     *    isSessionActive=false + 「会话已退出」行 + 模式切换过闸。
     *
     * ⚠️ 真进程挂在真实 IO scope，不随虚拟时钟走：泵 = 轮询真实状态 + advanceUntilIdle
     *    排空 Main 侧续体（SIGALRM 式异步多态：waitFor 阻塞在 IO 线程、信号经 SharedFlow
     *    跨到 Main 需多次泵）。带死线（防环境异常挂死测试）。
     */
    @Test
    fun `session process death deactivates session and unblocks mode switch`() {
        val viewModel = ShellDebugViewModel()
        viewModel.switchMode(DebugShellMode.Interactive)
        // Interactive 初始 isSendEnabled=false（switchMode 分支），startSession 会重置为 true
        viewModel.startSession("echo hi")
        runCurrent()
        assertTrue(viewModel.isSessionActive.value, "startSession 后会话应活跃")

        // 泵真实退出信号：进程 'echo hi; exit' 秒退，watchJob 消费后自动收口
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.isSessionActive.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            advanceUntilIdle()
        }

        assertFalse(viewModel.isSessionActive.value, "进程死后 isSessionActive 应回 false")
        assertTrue(
            viewModel.interactiveMessages.value.any {
                it.type == DebugMessageType.OUTPUT && it.content == "会话已退出（进程结束）"
            },
            "应追加「会话已退出（进程结束）」行，实际=${viewModel.interactiveMessages.value}",
        )
        assertTrue(
            viewModel.interactiveMessages.value.any { it.content == "hi" },
            "短命进程的 stdout 行应先入列（输出收集不被监测收口截胡）",
        )

        // 死亡后模式切换不再被拒（卡面 acceptance：切换过闸 + 无阻塞文案）
        viewModel.switchMode(DebugShellMode.Simple)
        assertEquals(DebugShellMode.Simple, viewModel.currentMode.value, "死亡后切换应过闸")
        viewModel.closeSession() // 收尾：清残留 Job（进程已死，close 幂等）
        viewModel.viewModelScope.cancel()
    }

    // ── 4 · SYS-13：主动关闭不追加「会话已退出」行（closeSession 先 cancel watchJob） ──

    @Test
    fun `closeSession does not append session-exited line`() {
        val viewModel = ShellDebugViewModel()
        viewModel.switchMode(DebugShellMode.Interactive)
        viewModel.startSession("sleep 30") // 长命进程：排除自然死亡竞态
        runCurrent()
        assertTrue(viewModel.isSessionActive.value, "startSession 后会话应活跃")

        viewModel.closeSession()
        // close 触发 destroy → waitFor 返回——若 watchJob 未先 cancel 会异步补「会话已退出」行
        Thread.sleep(300)
        advanceUntilIdle()

        assertFalse(
            viewModel.interactiveMessages.value.any { it.content == "会话已退出（进程结束）" },
            "主动关闭不应追加「会话已退出」行（Session closed 独占主动关闭语义），" +
                "实际=${viewModel.interactiveMessages.value.map { it.content }}",
        )
        assertTrue(
            viewModel.interactiveMessages.value.any { it.content == "Session closed" },
            "主动关闭应追加「Session closed」行",
        )
        viewModel.viewModelScope.cancel()
    }

    // ── 5 · SYS-14：marker 行不进输出流 + 完成检测仍触发（真进程整链） ──

    /**
     * 真交互会话整链验收（InteractiveShell 走真进程不可注入 fake）：启动会话须是**读 stdin 的
     * 内层 shell**（`sh`——wrap 后为 `bash -l -c 'sh'`，内层 sh 逐行执行 stdin 命令；
     * 🔴 不能用一次性命令如 `sleep 5`：bash -c 跑完即退、不读 stdin，写入的命令永不执行）。
     * 发送 `echo out` → VM 链式拼 `&& echo __DONE__` → 内层 sh 回显 marker 行：
     *  - 输出区**不得**出现 `__DONE__` 行（outputCollectJob 前置过滤，SYS-14）；
     *  - 完成检测**仍触发**：marker 照常驱动 markerDetectJob → isSendEnabled 恢复且**非超时路径**
     *    （超时路径会追加「命令超时」行——其缺席 = 检测侧命中）。
     * 🔴 泵必须用 runCurrent 不用 advanceUntilIdle：后者会推进虚拟时钟把 30s marker 超时
     *    定时器先于真实回显（百 ms 级）触发——首轮红正是此因（超时行抢跑、真输出未到）。
     * 真进程信号不走虚拟时钟：轮询真实状态 + runCurrent 排空 Main 侧续体，带死线。
     */
    @Test
    fun `marker line is filtered from output while completion detection still fires`() {
        val viewModel = ShellDebugViewModel()
        viewModel.switchMode(DebugShellMode.Interactive)
        viewModel.startSession("sh") // 内层 sh 读 stdin 执行逐条命令
        runCurrent()
        assertTrue(viewModel.isSessionActive.value, "startSession 后会话应活跃")

        viewModel.sendInteractiveCommand("echo out")
        runCurrent()
        assertFalse(viewModel.isSendEnabled.value, "发送后输入应锁定（等 marker）")

        // 泵真实回显：'out' 行 + marker 行到流——检测侧命中恢复输入、显示侧滤掉 marker
        val deadline = System.currentTimeMillis() + 10_000
        while (!viewModel.isSendEnabled.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            runCurrent()
        }
        // 排空末批续体（marker 与 'out' 同批到达时跨泵次处理）
        Thread.sleep(100)
        runCurrent()
        runCurrent()

        val contents = viewModel.interactiveMessages.value.map { it.content }
        assertTrue(
            viewModel.isSendEnabled.value,
            "marker 命中后完成检测应恢复输入（marker 仍驱动检测，仅显示侧滤除），实际消息=$contents",
        )
        assertTrue("out" in contents, "命令 stdout 行应入列，实际=$contents")
        assertFalse(
            contents.any { it.contains("命令超时") },
            "输入恢复必须是 marker 命中路径而非 30s 超时兜底（超时行缺席 = 完成检测仍触发），实际=$contents",
        )
        assertFalse(
            contents.any { it.trim() == "__DONE__" },
            "输出区不应出现 __DONE__ 协议噪音行（SYS-14 过滤），实际=$contents",
        )

        viewModel.closeSession()
        viewModel.viewModelScope.cancel()
    }

    // ── 6 · SYS-11/SYS-12 源码锚守护（输出台控制台语言 + 工具行接线）──

    /** 定位项目根（GuardTestSupport 单源同款上溯配方；本文件不放 arch 包，就地内联） */
    private fun locateProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun shellDebugPageSource(): String =
        File(
            locateProjectRoot(),
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/debug/ShellDebugPage.kt",
        ).readText()

    @Test
    fun `output console language anchored to consoleBg mono and gated empty state`() {
        // SYS-11 源码锚：输出区控制台语言（DL-M22 恒深底 + mono），断言三个不可回退点：
        //  1. 深底容器 = consoleBg（EasyToolboxColors token，亮 #1E1E1E/暗 #141416 明暗分治）；
        //  2. 🔴 深底 background 以 messages.isNotEmpty() 门控——空态 item 在 LazyColumn 内，
        //     无门控则空态混深底（卡面 acceptance「空态形态不混入深底容器」）；
        //  3. OUTPUT 行 mono 等宽 + STDERR 色差走 logError（深底正源）+ 前缀保留。
        val source = shellDebugPageSource()
        assertTrue("consoleBg" in source, "输出容器深底必须走 EasyToolboxColors.consoleBg（DL-M22）")
        assertTrue(
            "messages.isNotEmpty()" in source,
            "深底 background 必须以 messages.isNotEmpty() 门控（空态不混控制台底）",
        )
        assertTrue("DesktopTypography.mono" in source, "OUTPUT 行必须 mono 等宽正文")
        assertTrue("logError" in source, "STDERR 色差必须走 logError（深底日志四色正源）")
        assertTrue("[STDERR] " in source, "STDERR 前缀标签必须保留（行文本 lineText() 单源）")
        assertFalse(
            "errorContainer" in source,
            "OUTPUT 行不得再消费 errorContainer（深底不可读对，SYS-11 已弃用）",
        )
    }

    @Test
    fun `output console toolbar wires clear and copy actions`() {
        // SYS-12 源码锚：输出台工具行接线——清空走 clearMessages()、复制走 CopyButton
        // （终验 P3 对稿：icon-only CopyIconButton → 带文字 CopyButton；join 惰性语义
        // 由 enabled=false 空列表禁用承接——直传参数非 textProvider，行数固定场景
        // 无流式惰性需求）。
        val source = shellDebugPageSource()
        assertTrue("clearMessages" in source, "「清空」必须接 viewModel.clearMessages()")
        assertTrue(
            "CopyButton(" in source,
            "「复制全部」必须走 CopyButton 带可见文字形态（SYS-12 + 终验 P3 对稿）",
        )
        assertTrue("lineText()" in source, "复制文本必须经 lineText() 单源（source 标签 + content，id 不入文本）")
    }

    // ── 7 · SYS-12 clearMessages：只清当前模式列表 + version 递增 ──

    @Test
    fun `clearMessages clears only the current mode list and bumps version`() {
        val viewModel = viewModelWith(hangingExec())
        viewModel.executeSimpleCommand("adb logcat")
        runCurrent()
        advanceUntilIdle()
        // Simple 侧因 hangingExec 零输出，补一条交互侧消息后切回——制造两侧非空的稳态
        //（Interactive 链不可 fake，用 switchMode 后 startSession 真短命进程太重；
        // 直接验证两侧列表语义：Simple 侧已有 INPUT 行，Interactive 侧构造期保持空）
        val versionBefore = viewModel.messagesVersion.value

        viewModel.clearMessages()
        advanceUntilIdle()

        assertEquals(
            emptyList(),
            viewModel.simpleMessages.value,
            "清空必须清掉当前模式（Simple）消息列表",
        )
        assertTrue(
            viewModel.messagesVersion.value > versionBefore,
            "清空必须 messagesVersion+1（贴底跟随 effect key 语义守恒）",
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `clearMessages does not touch the other mode list`() {
        // 交互侧非空构造：startSession 真短命进程链追加「Session started」行后秒退，
        // Interactive 列表非空即达语义（另一模式列表不受清空影响的判定前提）。
        val viewModel = ShellDebugViewModel()
        viewModel.switchMode(DebugShellMode.Interactive)
        viewModel.startSession("echo hi")
        runCurrent()
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.isSessionActive.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            advanceUntilIdle()
        }
        assertTrue(
            viewModel.interactiveMessages.value.isNotEmpty(),
            "会话启动应已追加交互侧消息行（清空互不串扰判定的前提）",
        )

        // 切回 Simple（进程已死、切换过闸）后清空：只清 Simple 侧，交互侧原样保留
        viewModel.switchMode(DebugShellMode.Simple)
        viewModel.clearMessages()

        assertEquals(
            emptyList(),
            viewModel.simpleMessages.value,
            "清空只清当前模式（Simple 侧）",
        )
        assertTrue(
            viewModel.interactiveMessages.value.isNotEmpty(),
            "清空不得串扰另一模式（Interactive）的消息列表",
        )
        viewModel.closeSession()
        viewModel.viewModelScope.cancel()
    }
}
