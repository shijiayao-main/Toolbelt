package com.jiaoay.easy_dev_toolbox.page.logcat

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.toolbelt.device.ShellResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.adb.AdbEndpoint

/**
 * logcat 抓取 VM 提示态回归：无设备 / 检测失败（adb 路径失效）/ 多设备未选定
 * 时不静默——startCapture 落可操作提示态且不启动引擎；前置通过时按当前串号
 * 重写命令启动。另钉进行中过滤变更：tag/级别改动即时换引擎 matcher，只影响
 * 后续行（已落盘行不回溯剔除）且回执去抖单条；清屏转发：运行中清空滚动窗、
 * 落盘与全量计数零变化，终态定格后转发零副作用（清屏钮非运行态不渲染的
 * 防御兜底）。设备态经 :core DeviceMonitor 注入缝直驱（不真跑 adb）。
 * 另钉过滤参数持久化：init 从 store 恢复上次值、变更经 debounce 落盘、
 * 级别档 null ↔ 空串 name 双向还原零歧义。另钉产物送飞书：仅 Stopped 终态
 * 产物可发起（Idle/Running/无产物 no-op），Channel.deliver 的 null = 已送出
 * 成功不得被吞成失败回执，未接线/通道失败人话原样回执。另钉标记复现点：
 * Running 态标记发布锚点（「查看」定位载荷）且跨终态保留、下一轮抓取清零、
 * 空窗标记回执人话拒绝、非运行态转发零动静防御兜底。另钉武装捕获：合法特征
 * 武装置位、空白/无效正则回执拒绝不静默、命中自动落锚 + 回执带命中行时钟、
 * 命中即收网解除等待态、解除武装后命中不落锚零回执、Idle 态武装零动静防御
 * 兜底。另钉送工作台时间点
 * 载荷：标记锚点派生完整串（标记年补全）经深链第三参投递、未标记送 null
 * （工作台不碰时间域）、时间点不挤占 prefillNote 症状串。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogcatViewModelTest {

    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-logcat-vm-test")

    private val dispatcher = StandardTestDispatcher()
    private lateinit var originalStore: LocalDataManager

    private val createdScopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        // ReportDelivery 是全局单例槽——wired fake channel 残留会跨用例/跨测试类
        // 泄漏（送飞书用例前置清场 + 后置复位）
        ReportDelivery.clearForTest()
        StatusSignals.clearForTest()
        Dispatchers.setMain(dispatcher)
        originalStore = LogcatConfigStore.store
        LogcatConfigStore.store = LocalDataManager(
            dataStoreName = "test_logcat_vm_${UUID.randomUUID()}",
            datastoreDir = FileUtils.getDatastoreDir(),
        )
    }

    @AfterTest
    fun tearDown() {
        createdScopes.forEach { it.cancel() }
        homeIsolation.restoreOnly()
        LogcatConfigStore.store = originalStore
        TaskPipelineHandoff.clearForTest()
        DeepAnalyzeHook.clearForTest()
        ReportDelivery.clearForTest()
        StatusSignals.clearForTest()
        Dispatchers.resetMain()
    }

    private fun deviceSource(): DeviceMonitor = CoroutineScope(SupervisorJob() + Dispatchers.Default).let { scope ->
        createdScopes += scope
        DeviceMonitor(
            scope = scope,
            runner = { Result.success(ShellResult(0, "", "")) },
            endpointReader = { AdbEndpoint("") },
        )
    }

    private fun newVm(devices: DeviceMonitor): Pair<FakeRunner, LogcatViewModel> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it }
        val runner = FakeRunner()
        val engine = LogcatCaptureEngine(scope, runner)
        return runner to LogcatViewModel(devices, engine, adbPathReader = { "" })
    }

    @Test
    fun `detect failure surfaces hint with go-settings action and no capture start`() {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDetectError("设备检测失败：adb not found")

        vm.startCapture()

        val hint = vm.hint.value
        assertTrue(hint != null, "检测失败不得静默——必须落提示态")
        assertTrue(hint.message.contains("设备检测失败"), "提示沿用检测失败原文（含原因）")
        assertEquals(LogcatHint.Action.GO_SETTINGS, hint.action, "adb 路径失效给去设置入口")
        assertNull(runner.receivedCommand, "前置不通过不得启动引擎")
        assertIs<LogcatCaptureStatus.Idle>(vm.status.value)
    }

    @Test
    fun `no device surfaces hint instead of silent dead click`() {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(emptyList())

        vm.startCapture()

        val hint = vm.hint.value
        assertTrue(hint != null, "无设备不得静默")
        assertTrue(hint.message.contains("未检测到设备"), "提示文案指路设备连接")
        assertNull(runner.receivedCommand)
        assertIs<LogcatCaptureStatus.Idle>(vm.status.value)
    }

    @Test
    fun `multi device without selection surfaces select-device hint`() {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))

        vm.startCapture()

        val hint = vm.hint.value
        assertTrue(hint != null, "多设备未选定不得静默启动（目标不确定的抓取是废抓取）")
        assertTrue(hint.message.contains("多台设备"))
        assertEquals(LogcatHint.Action.SELECT_DEVICE, hint.action)
        assertNull(runner.receivedCommand)
    }

    @Test
    fun `multi device with selection starts capture on selected serial`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        devices.selectDevice("S2")
        runner.blockAfterEmit = true

        vm.startCapture()
        dispatcher.scheduler.runCurrent() // Main 档任务推进到 IO 挂起点（DataStore 读取后真跑）；不 advanceUntilIdle——debounce/常驻收集链在虚拟时钟下快进无收益
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() } // Running 置位先于捕获协程首跑，命令断言须等 run() 入口

        assertEquals("adb -s S2 logcat", runner.receivedCommand, "多设备注入会话级选定串号")
        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        Unit
    }

    @Test
    fun `single device starts capture injecting its serial`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        runner.blockAfterEmit = true

        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }

        assertEquals("adb -s S1 logcat", runner.receivedCommand, "单设备自动注入其串号")
        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        Unit
    }

    @Test
    fun `output file lands in caller dir with timestamped name and collision avoidance`() {
        val dir = Files.createTempDirectory("logcat-outdir").toFile()
        val first = LogcatViewModel.resolveOutputFile(dir, 1_760_000_000_000L)

        // 名 = logcat-<yyyyMMdd-HHmmss>.txt（长度锚：前缀 + 15 位时间戳 + 扩展，不耦合时区）
        assertEquals("logcat-".length + "yyyyMMdd-HHmmss".length + ".txt".length, first.name.length)
        assertTrue(first.name.startsWith("logcat-") && first.name.endsWith(".txt"))
        assertEquals(dir, first.parentFile, "产物落在调用方给定目录")

        first.createNewFile()
        val second = LogcatViewModel.resolveOutputFile(dir, 1_760_000_000_000L)
        assertTrue(second != first && second.name.endsWith("-1.txt"), "同秒冲突以 -N 后缀避让，不覆盖已有产物")
    }

    @Test
    fun `sendToNextTool rejects without product and seeds product path with one`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))

        // 已接线 + 无产物（未抓取）：动作拒绝且不残留载荷——无产物时衔接条目本就不渲染
        TaskPipelineHandoff.wire { }
        assertFalse(vm.sendToNextTool(), "无产物时衔接动作须拒绝（防御性兜底）")
        assertNull(TaskPipelineHandoff.consumeSeed(), "拒绝投递不得残留载荷")

        runner.blockAfterEmit = true
        runner.emitLines = listOf("10-11 09:00:00.000  100  200 I DemoTag  : hello")
        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }

        val product = (vm.status.value as LogcatCaptureStatus.Stopped).outputFile
        assertTrue(product != null, "命中行落盘后产物存在")
        assertTrue(vm.sendToNextTool(), "有产物 + 已接线时衔接投递须成功")
        val seed = TaskPipelineHandoff.consumeSeed()
        assertTrue(seed != null, "投递后衔接载荷可取")
        assertEquals("logkit.tag-filter", seed.commandId, "目标 = Tag 过滤带参命令")
        assertEquals(
            product!!.absolutePath,
            seed.args["file_path"],
            "产物路径须预填进目标命令的文件参数 file_path",
        )
        Unit
    }

    @Test
    fun `sendToWorkbench rejects without product and delivers product path when wired`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))

        // 已接线 + 无产物（未抓取）：动作拒绝且零投递——无产物时按钮本就不渲染
        var receivedFiles: List<String>? = null
        var receivedNote: String? = null
        var receivedTargetTime: String? = "sentinel"
        DeepAnalyzeHook.wire { files, note, targetTime ->
            receivedFiles = files
            receivedNote = note
            receivedTargetTime = targetTime
        }
        assertFalse(vm.sendToWorkbench(), "无产物时送工作台动作须拒绝（防御性兜底）")
        assertTrue(receivedFiles == null, "拒绝投递不得触达深链回调（零残留）")

        runner.blockAfterEmit = true
        runner.emitLines = listOf("10-11 09:00:00.000  100  200 I DemoTag  : hello")
        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }

        val product = (vm.status.value as LogcatCaptureStatus.Stopped).outputFile
        assertTrue(product != null, "命中行落盘后产物存在")
        assertTrue(vm.sendToWorkbench(), "有产物 + 已接线时深链投递须成功")
        assertEquals(
            listOf(product!!.absolutePath),
            receivedFiles,
            "投递载荷 = 产物路径单文件列表（绝对路径）",
        )
        assertEquals(
            "logcat 抓取产物深度分析",
            receivedNote,
            "预填症状 = 页别产物语义串（XLog/Tag 过滤页同款配方）",
        )
        assertNull(
            receivedTargetTime,
            "未标记（锚点 null）时时间点载荷 = null——工作台不触碰时间域（现行为零回归）",
        )

        // 未接线（宿主缺席）：动作拒绝且不触达——按钮渲染门控的防御性兜底
        DeepAnalyzeHook.clearForTest()
        receivedFiles = null
        assertFalse(vm.sendToWorkbench(), "未接线时送工作台动作须拒绝")
        assertTrue(receivedFiles == null, "未接线投递不得触达任何回调")
        Unit
    }

    /**
     * targetTime 载荷透传：标记过的锚点派生完整串（markedYear 补年）作为问题时间点
     * 随深链投递；未标记送 null。时间点走独立通道不进 prefillNote（症状域是用户
     * 必填可编辑域）。
     */
    @Test
    fun `sendToWorkbench carries marked anchor fullTimestamp as targetTime payload`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))

        var receivedFiles: List<String>? = null
        var receivedNote: String? = null
        var receivedTargetTime: String? = null
        DeepAnalyzeHook.wire { files, note, targetTime ->
            receivedFiles = files
            receivedNote = note
            receivedTargetTime = targetTime
        }

        runner.blockAfterEmit = true
        runner.emitLines = listOf(
            "10-11 09:00:00.000  100  200 I DemoTag: first",
            "10-11 09:00:05.000  100  200 I DemoTag: repro-moment",
        )
        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        withTimeout(5_000) { vm.window.first { it.totalCount == 2L } }

        vm.markAnchor()
        val marked = assertNotNull(vm.anchor.value, "前置：运行中已标记锚点")
        val year = java.time.LocalDate.now().year
        assertEquals("$year-10-11 09:00:05.000", marked.fullTimestamp, "前置：裸月日头补标记年")

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }

        assertTrue(vm.sendToWorkbench(), "有产物 + 已接线时深链投递须成功")
        assertTrue((vm.status.value as LogcatCaptureStatus.Stopped).outputFile != null, "前置：产物存在")
        assertEquals(
            listOf((vm.status.value as LogcatCaptureStatus.Stopped).outputFile!!.absolutePath),
            receivedFiles,
            "产物路径载荷不变（新增时间点载荷不挤占既有通道）",
        )
        assertEquals(
            "logcat 抓取产物深度分析",
            receivedNote,
            "时间点不进 prefillNote——症状串保持页别产物语义配方",
        )
        assertEquals(
            "$year-10-11 09:00:05.000",
            receivedTargetTime,
            "时间点载荷 = 锚点派生完整串（标记过则自动聚焦复现时刻）",
        )
        Unit
    }

    // ── 送飞书（sendOutputToLark：ReportDelivery 第四消费面）────────

    /** 抓取一段命中行并停止（终态带产物）——送飞书用例的前置配方 */
    private suspend fun captureAndStopWithProduct(runner: FakeRunner, vm: LogcatViewModel) {
        runner.blockAfterEmit = true
        runner.emitLines = listOf("10-11 09:00:00.000  100  200 I DemoTag  : hello")
        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
    }

    /** 轮询等待回执 toast 文本命中（送出协程跑在 Main 测试档，runCurrent 推进续体） */
    private fun awaitToastContaining(fragment: String): String {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start <= 5_000) {
            dispatcher.scheduler.runCurrent()
            StatusSignals.toast.value.map { it.text }.firstOrNull { fragment in it }
                ?.let { return it }
            Thread.sleep(20)
        }
        error("回执 toast 未在轮询预算内命中：$fragment")
    }

    @Test
    fun `sendOutputToLark does not initiate in idle or running states`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        var delivered: File? = null
        ReportDelivery.wire { f -> delivered = f; null }

        // Idle 态（从未抓取）：不发起——终态衔接钮行本就不渲染，防御性兜底
        vm.sendOutputToLark()
        dispatcher.scheduler.runCurrent()
        assertNull(delivered, "Idle 态无终态产物，不得发起送出")

        // Running 态（中途产物句柄未关、文件不完整）：不得送出
        runner.blockAfterEmit = true
        runner.emitLines = listOf("10-11 09:00:00.000  100  200 I DemoTag  : hello")
        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        vm.sendOutputToLark()
        repeat(20) { dispatcher.scheduler.runCurrent(); Thread.sleep(10) }
        assertNull(delivered, "Running 中途产物不得送出（送出 = Stopped 终态产物语义）")
        assertTrue(
            StatusSignals.toast.value.isEmpty(),
            "Idle/Running 守卫路径不得产生回执 toast（no-op 须零动静）",
        )

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        Unit
    }

    @Test
    fun `sendOutputToLark is a no-op when stopped without product`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        var delivered: File? = null
        ReportDelivery.wire { f -> delivered = f; null }

        // 零命中：空产物文件被引擎回收，终态产物为 null
        runner.blockAfterEmit = false
        runner.emitLines = emptyList()
        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        val stopped = assertIs<LogcatCaptureStatus.Stopped>(vm.status.value)
        assertNull(stopped.outputFile, "前置：零命中终态产物为 null（空文件已回收）")

        vm.sendOutputToLark()
        repeat(20) { dispatcher.scheduler.runCurrent(); Thread.sleep(10) }
        assertNull(delivered, "Stopped 无产物不得发起送出（产物行不渲染，防御性兜底）")
        assertTrue(StatusSignals.toast.value.isEmpty(), "no-op 守卫路径不得产生回执 toast")
        Unit
    }

    @Test
    fun `sendOutputToLark delivers stopped product to wired channel and success receipt names it`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        captureAndStopWithProduct(runner, vm)
        val product = assertIs<LogcatCaptureStatus.Stopped>(vm.status.value).outputFile
        assertTrue(product != null, "前置：命中行落盘后终态产物存在")
        assertNull(
            logcatLarkSendDisabledReason(larkDeliveryReady = true),
            "就绪态置灰判据须放行（ReportDelivery.ready 单源）",
        )

        var delivered: File? = null
        ReportDelivery.wire { f -> delivered = f; null }
        ReportDelivery.publishReady(true)

        vm.sendOutputToLark()
        val start = System.currentTimeMillis()
        while (delivered == null && System.currentTimeMillis() - start <= 5_000) {
            dispatcher.scheduler.runCurrent()
            Thread.sleep(20)
        }
        val file = assertNotNull(delivered, "就绪通道必须收到终态产物 File")
        assertTrue(file.isFile, "送达产物必须真实在盘")
        assertEquals(
            product!!.absolutePath,
            file.absolutePath,
            "送达 File 与 Stopped 终态产物同源同路径（不中途换文件）",
        )

        val receipt = awaitToastContaining("已送出至飞书会话")
        assertTrue(product.name in receipt, "成功回执须指名送达文件（回执可核对）：$receipt")
        // 反向断言：Channel.deliver 返回 null = 已送出成功——成功不得被吞成失败回执
        assertTrue(
            "不可用" !in receipt && "失败" !in receipt,
            "通道成功（null）不得产生失败形态回执（elvis 会吞成功——先收 reason 再判空分叉）",
        )
        Unit
    }

    @Test
    fun `sendOutputToLark without wired channel surfaces human-readable receipt`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        assertFalse(ReportDelivery.ready.value, "前置：通道未接线（ready 恒 false）")
        val disabledReason = logcatLarkSendDisabledReason(larkDeliveryReady = false)
        assertTrue(
            disabledReason != null && "飞书桥接未运行" in disabledReason,
            "未就绪置灰判据须明示桥接未运行原因（面板置灰文案单源）：$disabledReason",
        )

        captureAndStopWithProduct(runner, vm)
        vm.sendOutputToLark()
        val receipt = awaitToastContaining("不可用")
        assertTrue(
            "报告外发通道不可用" in receipt,
            "未接线送出须回执人话失败而非静默/异常（通道归一），实际：$receipt",
        )
        Unit
    }

    @Test
    fun `sendOutputToLark surfaces channel failure reason verbatim`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        ReportDelivery.wire { "通道实现的失败人话" }
        ReportDelivery.publishReady(true)

        captureAndStopWithProduct(runner, vm)
        vm.sendOutputToLark()
        assertEquals(
            "通道实现的失败人话",
            awaitToastContaining("通道实现的失败人话"),
            "通道失败人话必须原样透传为回执（归一在通道实现方，消费面不二次加工）",
        )
        Unit
    }

    @Test
    fun `filter changes during running capture apply immediately to subsequent lines`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        runner.blockAfterEmit = true
        runner.emitLines = listOf("10-11 09:00:00.000  100  200 I DemoTag: hello")
        // 两道分批门：门 1 前换 tag、门 2 前换级别——后续批各自按新过滤即时判定
        runner.stagedBatches = listOf(
            listOf(
                "10-11 09:00:00.001  100  200 I Camera: frame",
                "10-11 09:00:00.002  100  200 D DemoTag: noise",
            ),
            listOf(
                "10-11 09:00:00.003  100  200 W Camera: warn",
                "10-11 09:00:00.004  100  200 D Camera: debug",
            ),
        )

        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        withTimeout(5_000) { vm.window.first { it.totalCount == 1L } }

        vm.onTagQueryChange("camera")
        runner.releaseNextBatch()
        withTimeout(5_000) { vm.window.first { it.totalCount == 2L } }

        vm.onMinLevelChange(LogcatLevel.WARN)
        runner.releaseNextBatch()
        withTimeout(5_000) { vm.window.first { it.totalCount == 3L } }

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(vm.status.value)
        assertEquals(3L, stopped.totalCount, "进行中变更即时生效：tag 收窄后二批只进 Camera，级别再收紧后三批只进 >= W")
        val product = stopped.outputFile
        assertTrue(product != null, "命中行落盘后产物存在")
        val disk = product!!.readText()
        assertTrue(disk.contains("DemoTag: hello"), "换过滤前已落盘行不回溯剔除")
        assertTrue(disk.contains("Camera: frame"), "tag 收窄后的命中行按新过滤落盘")
        assertTrue(disk.contains("Camera: warn"), "级别收紧后的命中行按新过滤落盘")
        assertFalse(disk.contains("DemoTag: noise"), "tag 收窄后被挡下的行不落盘")
        assertFalse(disk.contains("Camera: debug"), "级别收紧后被挡下的行不落盘")

        val receipts = StatusSignals.toast.value
            .filter { it.text == "过滤已更新，只影响后续行" }
            .map { it.id }
        assertEquals(
            1,
            receipts.size,
            "变更回执须可见且去抖——tag/级别两次变更在去抖窗内只弹一条",
        )
        Unit
    }

    @Test
    fun `query syntax filter compiles and gates capture end to end`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        vm.onTagQueryChange("tag:cam & level:E")
        assertNull(vm.queryError.value, "合法查询不得落错误态")
        runner.emitLines = listOf(
            "10-11 09:00:00.000  100  200 I Camera: frame",
            "10-11 09:00:00.001  100  200 E Camera: fatal",
            "10-11 09:00:00.002  100  201 E DemoTag: fatal-too",
        )

        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(vm.status.value)
        assertEquals(1L, stopped.totalCount, "查询语法过滤端到端只放行 tag+级别双满足行")
        assertEquals(
            listOf("10-11 09:00:00.001  100  200 E Camera: fatal"),
            stopped.outputFile!!.readText().lines().filter { it.isNotEmpty() },
            "落盘口径与查询谓词一致",
        )
        Unit
    }

    @Test
    fun `invalid query is rejected and previous filter keeps applying`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        runner.blockAfterEmit = true
        runner.emitLines = listOf("10-11 09:00:00.000  100  200 I Camera: boot")
        runner.stagedBatches = listOf(
            listOf(
                "10-11 09:00:00.001  100  200 E Camera: kept-by-previous",
                "10-11 09:00:00.002  100  201 E DemoTag: blocked-by-previous",
            ),
            listOf(
                "10-11 09:00:00.003  100  200 E Camera: after-fix",
                "10-11 09:00:00.004  100  201 E DemoTag: after-fix-blocked",
            ),
        )

        vm.onTagQueryChange("tag:camera")
        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        withTimeout(5_000) { vm.window.first { it.totalCount == 1L } }

        // 输入坏查询：解析失败拒收——引擎保持上一有效过滤（tag:camera 继续裁决）
        // （坏查询夹具用未知 key 形态：msg 已是可用算子，不再能当坏例）
        vm.onTagQueryChange("text:boom")
        assertNotNull(vm.queryError.value, "解析失败须发布错误态（即时回执载荷）")
        val receipt = awaitToastContaining("查询无效")
        assertTrue("已保持上一有效过滤" in receipt, "拒收回执须明示保持语义：$receipt")
        runner.releaseNextBatch()
        withTimeout(5_000) { vm.window.first { it.totalCount == 2L } }
        assertEquals(
            listOf("10-11 09:00:00.001  100  200 E Camera: kept-by-previous"),
            vm.window.value.lines.drop(1),
            "拒收后上一有效过滤继续裁决后续行",
        )

        // 修正查询：恢复即时生效 + 错误态清空
        vm.onTagQueryChange("tag:camera & level:E")
        assertNull(vm.queryError.value, "恢复合法查询须清空错误态")
        runner.releaseNextBatch()
        withTimeout(5_000) { vm.window.first { it.totalCount == 3L } }

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        val disk = assertIs<LogcatCaptureStatus.Stopped>(vm.status.value).outputFile!!.readText()
        assertTrue("kept-by-previous" in disk, "拒收期间按上一有效过滤落盘")
        assertFalse("blocked-by-previous" in disk)
        assertTrue("after-fix" in disk, "修正后的查询只影响后续行")
        assertFalse("after-fix-blocked" in disk)
        Unit
    }

    @Test
    fun `persisted query string restores and gates capture without migration`() = runBlocking {
        runBlocking { LogcatConfigStore.setLogcatTagQuery("level:E") }
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        pumpUntilRestored(vm, "level:E", null)
        runner.emitLines = listOf(
            "10-11 09:00:00.000  100  200 I DemoTag: info",
            "10-11 09:00:00.001  100  200 E DemoTag: fatal",
        )

        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(vm.status.value)
        assertEquals(1L, stopped.totalCount, "恢复的查询串照常编译并定型抓取过滤")
        Unit
    }

    @Test
    fun `clear display during running capture empties window and keeps disk full`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        runner.blockAfterEmit = true
        runner.emitLines = listOf("10-11 09:00:00.000  100  200 I DemoTag: pre-clear")
        runner.stagedBatches = listOf(
            listOf("10-11 09:00:00.001  100  200 I DemoTag: post-clear"),
        )

        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        withTimeout(5_000) { vm.window.first { it.totalCount == 1L } }

        vm.clearDisplay()
        assertEquals(0, vm.window.value.lines.size, "清屏转发须清空实时滚动窗")
        assertEquals(1L, vm.window.value.totalCount, "全量计数清屏前后零变化（清显示不清账）")

        runner.releaseNextBatch()
        withTimeout(5_000) { vm.window.first { it.totalCount == 2L } }
        assertEquals(
            listOf("10-11 09:00:00.001  100  200 I DemoTag: post-clear"),
            vm.window.value.lines,
            "清屏后命中行照常追加显示",
        )

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        val stopped = assertIs<LogcatCaptureStatus.Stopped>(vm.status.value)
        assertEquals(2L, stopped.totalCount, "全量计数照常累计")
        val disk = stopped.outputFile!!.readText()
        assertTrue(disk.contains("DemoTag: pre-clear"), "清屏前的行不落盘剔除")
        assertTrue(disk.contains("DemoTag: post-clear"), "清屏后的行照常落盘")

        // 非 Running 态转发零副作用：清屏钮与停止钮同款运行态门控（非运行态不渲染），
        // 终态定格的窗口不因迟到的转发变化
        val windowAtStop = vm.window.value
        vm.clearDisplay()
        assertEquals(windowAtStop, vm.window.value, "终态定格后清屏转发须零副作用")
        Unit
    }

    @Test
    fun `markAnchor during running capture publishes anchor consumed by view after stop`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        runner.blockAfterEmit = true
        runner.emitLines = listOf(
            "10-11 09:00:00.000  100  200 I DemoTag: first",
            "10-11 09:00:05.000  100  200 I DemoTag: repro-moment",
        )

        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        withTimeout(5_000) { vm.window.first { it.totalCount == 2L } }

        assertNull(vm.anchor.value, "未标记时锚点为 null——「查看」不带定位（现行为零回归）")
        vm.markAnchor()
        val marked = assertNotNull(vm.anchor.value, "Running 态标记须发布锚点")
        assertEquals("10-11 09:00:05.000", marked.timestamp, "锚点 = 屏上最新带时间戳行的行首时间戳")
        assertEquals("09:00:05", marked.clockText, "回显时钟段 = 行时间戳的 hh:mm:ss")

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        val stopped = assertIs<LogcatCaptureStatus.Stopped>(vm.status.value)
        assertTrue(stopped.outputFile != null, "前置：命中行落盘后终态产物存在")
        assertEquals(
            "10-11 09:00:05.000",
            vm.anchor.value?.timestamp,
            "锚点跨 Stopped 终态保留——「查看」点击时刻取 anchor.timestamp 透传查看器直达标记处",
        )

        // 下一轮抓取清零：旧产物里的锚点不得跨入新产物误导直达
        runner.blockAfterEmit = false
        runner.emitLines = listOf("10-11 09:30:00.000  100  200 I DemoTag: fresh-capture")
        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) {
            vm.status.first { it is LogcatCaptureStatus.Stopped && it.totalCount == 1L }
        }
        assertNull(vm.anchor.value, "startCapture 须清零上一轮的标记锚点（新产物不带旧锚点）")
        Unit
    }

    @Test
    fun `markAnchor without anchorable line surfaces receipt and is silent no-op outside running`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        runner.blockAfterEmit = true
        runner.emitLines = emptyList()

        // Idle 态：标记钮非运行态不渲染，转发为防御兜底——零动静
        vm.markAnchor()
        assertNull(vm.anchor.value, "Idle 态标记拒绝且锚点不落")
        assertTrue(StatusSignals.toast.value.isEmpty(), "Idle 防御兜底路径零回执（no-op 须零动静）")

        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }

        // Running 但空窗（清屏后未再来行同理）：回执人话拒绝，可见按钮不静默死点
        vm.markAnchor()
        assertNull(vm.anchor.value, "无可锚定行时锚点不落")
        val receipt = awaitToastContaining("暂无可标记")
        assertTrue("带时间戳行" in receipt, "回执须明示拒绝原因（人话指路）：$receipt")

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }

        // Stopped 态：防御兜底零动静（终态窗口已定格，锚点只记运行中所见）
        val toastCountAtStop = StatusSignals.toast.value.size
        vm.markAnchor()
        assertEquals(toastCountAtStop, StatusSignals.toast.value.size, "终态防御兜底零回执")
        Unit
    }

    @Test
    fun `armCapture validates pattern and rejects blank or invalid without arming`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))

        // Idle 态防御兜底：武装钮非运行态不渲染，转发零动静
        vm.onArmPatternChange("FATAL")
        vm.armCapture()
        assertFalse(vm.armed.value, "Idle 态武装拒绝（防御兜底）")
        assertTrue(StatusSignals.toast.value.isEmpty(), "Idle 防御兜底零回执")

        runner.blockAfterEmit = true
        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }

        vm.onArmPatternChange("   ")
        vm.armCapture()
        assertFalse(vm.armed.value, "空白特征拒绝武装（空正则恒命中=秒收网，必是误操作）")
        assertTrue(
            awaitToastContaining("特征正则为空").isNotEmpty(),
            "空白特征回执人话指路不静默",
        )

        vm.onArmPatternChange("(unclosed")
        vm.armCapture()
        assertFalse(vm.armed.value, "无效正则拒绝武装（不把编译异常抛给用户）")
        val invalidReceipt = awaitToastContaining("特征正则无效")
        assertTrue("修正" in invalidReceipt, "无效正则回执指路修正：$invalidReceipt")

        vm.onArmPatternChange("REPRO-MARK")
        vm.armCapture()
        assertTrue(vm.armed.value, "合法特征武装生效（等待态置位）")

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        assertFalse(vm.armed.value, "抓取终态撤销武装（不跨轮残留）")
        Unit
    }

    @Test
    fun `armed capture marks anchor on hit with receipt and flips to disarmed`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        runner.blockAfterEmit = true
        runner.emitLines = listOf("10-11 09:00:00.000  100  200 I DemoTag: warmup")
        runner.stagedBatches = listOf(
            listOf(
                "10-11 09:00:01.000  100  200 I DemoTag: noise",
                "10-11 09:00:02.345  100  200 E Crash: FATAL EXCEPTION",
            ),
        )

        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        withTimeout(5_000) { vm.window.first { it.totalCount == 1L } }

        vm.onArmPatternChange("FATAL")
        vm.armCapture()
        assertTrue(vm.armed.value, "前置：武装等待态置位")

        runner.releaseNextBatch()
        val receipt = awaitToastContaining("特征已命中")
        assertTrue("标记 @" in receipt, "命中回执带锚点标记：$receipt")
        assertTrue("09:00:02" in receipt, "回执时钟 = 命中行自身时间戳的 hh:mm:ss：$receipt")
        val anchor = assertNotNull(vm.anchor.value, "命中自动落锚点（合一覆盖，查看/送工作台载荷就绪）")
        assertEquals("10-11 09:00:02.345", anchor.timestamp, "武装锚点 = 命中行自身行首时间戳")
        assertFalse(vm.armed.value, "命中即收网——等待态自动解除")

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        assertEquals(
            "10-11 09:00:02.345",
            vm.anchor.value?.timestamp,
            "武装锚点跨 Stopped 终态保留（查看/送工作台消费面不变）",
        )
        Unit
    }

    @Test
    fun `disarmCapture revokes trap so later matching lines do not mark`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        runner.blockAfterEmit = true
        runner.emitLines = listOf("10-11 09:00:00.000  100  200 I DemoTag: warmup")
        runner.stagedBatches = listOf(
            listOf("10-11 09:00:01.000  100  200 E Crash: FATAL after disarm"),
        )

        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }

        vm.onArmPatternChange("FATAL")
        vm.armCapture()
        assertTrue(vm.armed.value, "前置：武装等待态置位")
        vm.disarmCapture()
        assertFalse(vm.armed.value, "解除武装转发生效")

        runner.releaseNextBatch()
        withTimeout(5_000) { vm.window.first { it.totalCount == 2L } }
        assertNull(vm.anchor.value, "解除后的命中行不落锚点")
        assertTrue(
            StatusSignals.toast.value.none { "特征已命中" in it.text },
            "解除后命中零回执",
        )

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        Unit
    }

    @Test
    fun `overflow hint is null when droppedCount is zero`() {
        assertNull(
            logcatOverflowHint(LogcatWindow(lines = listOf("line"), totalCount = 1L, droppedCount = 0)),
            "零溢出不得呈现提示（不打扰）",
        )
    }

    @Test
    fun `overflow hint carries count and full-disk caveat and survives clear display`() = runBlocking {
        val devices = deviceSource()
        val (runner, vm) = newVm(devices)
        devices.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        val overflow = 40
        runner.blockAfterEmit = true
        runner.emitLines = (1..LogcatCaptureEngine.MAX_RING_LINES + overflow).map {
            "10-11 09:00:00.000  100  200 I DemoTag: line-$it"
        }

        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }
        withTimeout(5_000) { vm.window.first { it.droppedCount == overflow } }

        val beforeClear = logcatOverflowHint(vm.window.value)
        assertTrue(beforeClear != null, "溢出存量须呈现提示——屏里不是全量是可感知信息，不静默")
        assertTrue(
            beforeClear!!.contains(overflow.toString()),
            "提示须含丢弃计数（用户凭计数才知道丢了多少）",
        )
        assertTrue(
            beforeClear.contains("落盘完整"),
            "提示须含落盘完整口径句——否则「屏里看不到」会被误读成「没抓到」",
        )

        vm.clearDisplay()
        assertEquals(0, vm.window.value.lines.size, "前置：清屏清空显示窗")
        assertEquals(
            beforeClear,
            logcatOverflowHint(vm.window.value),
            "清屏清的是显示窗不是溢出账——提示清屏后照常呈现且计数不变",
        )

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        Unit
    }

    @Test
    fun `arm delay preset is session-level state defaulting to no delay`() {
        // 默认 0 = 不延迟：武装纯等待语义零回归；预设档首项恒为 0（面板回显
        // 「不延迟」芯片）；档位切换是纯会话态——不持久化（与 armPattern 同口径）
        val devices = deviceSource()
        val (_, vm) = newVm(devices)
        assertEquals(0, vm.armDelaySeconds.value, "延迟档默认不延迟")
        assertEquals(0, LogcatViewModel.ARM_DELAY_PRESET_SECONDS.first(), "预设档首项 = 不延迟")
        vm.onArmDelayChange(LogcatViewModel.ARM_DELAY_PRESET_SECONDS.last())
        assertEquals(
            LogcatViewModel.ARM_DELAY_PRESET_SECONDS.last(),
            vm.armDelaySeconds.value,
            "档位切换透传到武装参数态",
        )
    }

    @Test
    fun `arm delay label renders human preset names`() {
        assertEquals("不延迟", armDelayLabel(0))
        assertEquals("30 秒", armDelayLabel(30))
        assertEquals("1 分钟", armDelayLabel(60))
        assertEquals("5 分钟", armDelayLabel(300))
    }

    /**
     * 等 VM init 恢复读落定：恢复协程跑在 Main 测试档上，真实 DataStore IO
     * 完成后续体须 runCurrent 回填。种子恒带非空 tag——空存档的恢复值与
     * 初始默认态不可区分，无判据即无法证明「恢复已落定」。超时静默返回，
     * 由调用方断言给出失败信息。
     */
    private fun pumpUntilRestored(vm: LogcatViewModel, tag: String, level: LogcatLevel?) {
        val start = System.currentTimeMillis()
        while (vm.tagQuery.value != tag || vm.minLevel.value != level) {
            dispatcher.scheduler.runCurrent()
            if (System.currentTimeMillis() - start > 5_000) return
            Thread.sleep(20)
        }
    }

    /**
     * 推进 debounce 窗直至 store 出现期望值：collect 链的续体分批入队，单次
     * advance+runCurrent 不保证走完「新值进 debounce → 计时到 → 落盘」全程，
     * 真实 DataStore IO 完成回填也要逐轮 runCurrent。逐轮 400ms 推进虚拟时钟——
     * 跨过 :core 设备域的 5s 轮询定时也无副作用（本文件注入的 DeviceMonitor 门控
     * 恒 false，静默跳过不 spawn adb）。超时静默返回，由调用方断言给出失败信息。
     */
    private fun fireDebouncePersistUntil(expectedTag: String, expectedLevelName: String) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start <= 5_000) {
            dispatcher.scheduler.advanceTimeBy(LogcatViewModel.FILTER_PERSIST_DEBOUNCE_MS + 1)
            dispatcher.scheduler.runCurrent()
            val (tag, levelName) = runBlocking {
                LogcatConfigStore.getLogcatTagQuery() to LogcatConfigStore.getLogcatMinLevelName()
            }
            if (tag == expectedTag && levelName == expectedLevelName) return
            Thread.sleep(20)
        }
    }

    @Test
    fun `init restores persisted tag query and min level from store`() {
        runBlocking {
            LogcatConfigStore.setLogcatTagQuery("camera preview")
            LogcatConfigStore.setLogcatMinLevelName("WARN")
        }
        val devices = deviceSource()
        val (_, vm) = newVm(devices)

        pumpUntilRestored(vm, "camera preview", LogcatLevel.WARN)

        assertEquals("camera preview", vm.tagQuery.value, "tag 过滤串 init 从 store 恢复上次值")
        assertEquals(LogcatLevel.WARN, vm.minLevel.value, "级别档以 name 落盘、init 还原回枚举档")
    }

    @Test
    fun `filter changes persist through debounce into store`() {
        runBlocking { LogcatConfigStore.setLogcatTagQuery("bootseed") }
        val devices = deviceSource()
        val (_, vm) = newVm(devices)
        pumpUntilRestored(vm, "bootseed", null) // 恢复读落定后再改值——避免迟到回填覆盖改动

        vm.onTagQueryChange("camera preview")
        vm.onMinLevelChange(LogcatLevel.WARN)
        fireDebouncePersistUntil("camera preview", "WARN")

        runBlocking {
            withTimeout(5_000) {
                assertEquals("camera preview", LogcatConfigStore.getLogcatTagQuery(), "tag 变更经 debounce 落进 store")
                assertEquals("WARN", LogcatConfigStore.getLogcatMinLevelName(), "级别变更以 name 落进 store")
            }
        }
    }

    @Test
    fun `min level null and enum names round-trip through store without ambiguity`() {
        runBlocking { LogcatConfigStore.setLogcatTagQuery("bootseed") }
        val devices = deviceSource()
        val (_, first) = newVm(devices)
        pumpUntilRestored(first, "bootseed", null)
        assertNull(first.minLevel.value, "持久层空串还原为 null（无级别过滤）——不是任何枚举档")

        first.onMinLevelChange(LogcatLevel.ERROR)
        fireDebouncePersistUntil("bootseed", "ERROR")
        first.onTagQueryChange("keep")
        fireDebouncePersistUntil("keep", "ERROR")

        // name → 枚举档还原（新 VM = 重启语义）
        val (_, second) = newVm(devices)
        pumpUntilRestored(second, "keep", LogcatLevel.ERROR)
        assertEquals(LogcatLevel.ERROR, second.minLevel.value, "name 经 init 还原回枚举档")

        // null → 空串落盘（清掉级别过滤是有效状态，不得被吞回上次枚举档）
        second.onMinLevelChange(null)
        fireDebouncePersistUntil("keep", "")

        // 空串 → null 还原（闭环：null ↔ 空串双向零歧义）
        val (_, third) = newVm(devices)
        pumpUntilRestored(third, "keep", null)
        assertNull(third.minLevel.value, "空串经 init 还原回 null——闭环零歧义")
    }

    private class FakeRunner : LogcatStreamRunner {
        var blockAfterEmit = false
        var emitLines: List<String> = emptyList()
        var receivedCommand: String? = null

        /**
         * 分批发射锚（进行中变更的时序缝）：stagedBatches 每批发射前挂起等
         * releaseNextBatch 放行——测试在两缝之间改 VM 过滤参数。
         */
        var stagedBatches: List<List<String>> = emptyList()
        private val batchGates = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

        /** 放行下一分批（挂起至该批的门挂上：首批发完前调用会等门而非空放） */
        suspend fun releaseNextBatch() {
            batchGates.receive().complete(Unit)
        }

        /** run() 入口信号：引擎置 Running 与捕获协程首跑之间有窗口，断言命令须先等本信号 */
        val started = CompletableDeferred<Unit>()

        @Volatile private var stopRequested = false
        private var stopSignal = CompletableDeferred<Unit>()

        override suspend fun run(command: String, onLine: suspend (String) -> Unit): Int? {
            receivedCommand = command
            emitLines.forEach { onLine(it) }
            started.complete(Unit)
            stagedBatches.forEach { batch ->
                val gate = CompletableDeferred<Unit>()
                batchGates.trySend(gate)
                gate.await()
                batch.forEach { onLine(it) }
            }
            if (blockAfterEmit && !stopRequested) stopSignal.await()
            return 0
        }

        override fun stop() {
            // stop 可能先于 run 到达（Running 态在捕获协程首跑前即置位）——双通道均记录；
            // 分批门一并放行（stop 后测试不再发批，放行只为不挂死未消费的门）
            stopRequested = true
            while (true) {
                batchGates.tryReceive().getOrNull()?.complete(Unit) ?: break
            }
            stopSignal.complete(Unit)
        }
    }
}
