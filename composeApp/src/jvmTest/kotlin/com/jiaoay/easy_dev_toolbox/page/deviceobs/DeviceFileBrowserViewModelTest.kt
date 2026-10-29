package com.jiaoay.easy_dev_toolbox.page.deviceobs

import com.jiaoay.toolbelt.device.ShellOutput
import com.jiaoay.toolbelt.device.ShellOutputSource
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest
import com.jiaoay.easy_dev_toolbox.page.tools.base64.Base64ImageFormat
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.device.AdbProcessRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import com.jiaoay.toolbelt.device.AdbPullGateway
import com.jiaoay.toolbelt.device.AdbPullHandle
import com.jiaoay.toolbelt.device.AdbPushGateway
import com.jiaoay.toolbelt.device.AdbPushHandle
import com.jiaoay.toolbelt.device.DeviceDirectoryPullReceipt
import com.jiaoay.toolbelt.device.DeviceFileEntry
import com.jiaoay.toolbelt.device.DeviceFilePullReceipt
import com.jiaoay.toolbelt.device.DeviceFilePuller
import com.jiaoay.toolbelt.device.DeviceFilePusher
import com.jiaoay.toolbelt.device.DeviceFileStat
import com.jiaoay.toolbelt.device.DeviceFileType
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource

/**
 * 文件浏览 VM 回归网（fake 设备源 + fake 列举内核注入，零真子进程）：
 * - 导航栈语义：进入子目录成功才提交栈（失败停原目录）、返回上级弹一层、
 *   浏览起点根上返回无动作；
 * - 请求-回执：失败回执人话原样落状态、下一动作清零；装载中进入/返回被拒；
 * - 目标设备语义：无设备/多设备未选/检测失败的同步动作拒绝回执；串号实变
 *   → 栈重置回根并重载；目标从有到无 → 读数失效清空；
 * - symlink 真值面（fake 解引用 stat 注入）：真值目录尾斜杠穿透进入/真值文件
 *   进入回执指引拉取、拉取放行/断链回执不静默（进入回执走错误槽、拉取回执走
 *   pull 槽）；
 * - 条目过滤投影：查询词是纯显示层投影（零 spawn 断言），翻目录/目标失效不清词；
 * - 拉回产物会话缓存：多产物共存最近在上、上界淘汰最旧、产物被外部删除时
 *   衔接/预览动作落人话回执不静默（负断言：不投递衔接载荷）；
 * - 同名撞车避让：异源（异设备/同设备异目录）同名拉取落 `-N` 避让位（缓存
 *   两行路径互异、首行查看打开首拉内容、内核收到避让后路径原样执行——路径
 *   计算全在 VM 层），同源重拉原名覆盖刷新 + 缓存原位替换单行，目录链同口径；
 * - 产物行查看/打开所在文件夹：文件产物查看直投查看器请求槽（产物路径、零定位
 *   锚点）、缺席产物两动作均人话回执零执行、目录产物查看缺席 by design（无查看
 *   钮负断言 + 守卫零投递兜底）而打开所在文件夹可用；
 * - 目录条目拉取链：目录条目放行递归拉取（busy 目录标记 + 两链共用单飞 +
 *   取消清理 .part 目录）、目录产物登记进会话缓存且衔接投递缺席 by design。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceFileBrowserViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** fake 设备三流直驱（无轮询环无 DataStore——VM 只吃三流读值） */
    private class FakeDeviceSource : DeviceStateSource {
        val deviceStateFlow = MutableStateFlow<DeviceState>(DeviceState.None)
        override val deviceState: StateFlow<DeviceState> = deviceStateFlow.asStateFlow()

        val detectErrorFlow = MutableStateFlow<String?>(null)
        override val detectError: StateFlow<String?> = detectErrorFlow.asStateFlow()

        val selectedSerialFlow = MutableStateFlow<String?>(null)
        override val selectedSerial: StateFlow<String?> = selectedSerialFlow.asStateFlow()
    }

    /** fake 列举内核：记录调用序（路径+串号断言锚），按路由表回执 */
    private class FakeBrowser(
        private val respond: suspend (path: String, serial: String) -> Result<List<DeviceFileEntry>>,
    ) {
        val calls = mutableListOf<Pair<String, String>>()
        val listDirectory: suspend (String, String) -> Result<List<DeviceFileEntry>> = { path, serial ->
            calls += path to serial
            respond(path, serial)
        }
    }

    /** fake 解引用 stat：记录调用序（路径断言锚），按路由表回执 */
    private class FakeStat(
        private val respond: suspend (path: String, serial: String) -> Result<DeviceFileStat>,
    ) {
        val calls = mutableListOf<Pair<String, String>>()
        val statEntry: suspend (String, String) -> Result<DeviceFileStat> = { path, serial ->
            calls += path to serial
            respond(path, serial)
        }
    }

    private fun dirStat(size: Long = 4096) = DeviceFileStat(
        type = DeviceFileType.DIRECTORY,
        sizeBytes = size,
        modifiedEpochSeconds = 1_700_000_000L,
        permissions = "drwxrwx--x",
        owner = "root",
        group = "root",
    )

    private fun fileStat(size: Long) = DeviceFileStat(
        type = DeviceFileType.FILE,
        sizeBytes = size,
        modifiedEpochSeconds = 1_700_000_000L,
        permissions = "-rw-rw----",
        owner = "root",
        group = "root",
    )

    private fun entry(name: String, type: DeviceFileType = DeviceFileType.FILE) = DeviceFileEntry(
        name = name,
        type = type,
        symlinkTarget = if (type == DeviceFileType.SYMLINK) "/storage/emulated/0" else null,
        sizeBytes = 4096,
        permissions = "drwxrwx--x",
        owner = "root",
        group = "root",
        modifiedTime = "2026-10-11 14:23",
    )

    private fun okEntries() = listOf(
        entry("Movies", DeviceFileType.DIRECTORY),
        entry("build.log"),
        entry("sdcard", DeviceFileType.SYMLINK),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 装载并前进虚拟时钟到收敛（fake 内核同步回执，一轮推进即收敛） */
    private fun DeviceFileBrowserViewModel.settle() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `single device auto loads root listing once`() {
        val devices = FakeDeviceSource()
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = DeviceFileBrowserViewModel(devices, browser.listDirectory)
        vm.settle()

        assertEquals(listOf(DEVICE_FILE_BROWSER_ROOT to "S1"), browser.calls, "目标就绪即自动装载起点根一次")
        val state = vm.state.value
        assertEquals(DEVICE_FILE_BROWSER_ROOT, state.path)
        assertEquals(listOf("Movies", "build.log", "sdcard"), state.entries?.map { it.name })
        assertEquals(false, state.canNavigateUp, "起点根上无上级")
        assertNull(state.error)
    }

    @Test
    fun `enter directory commits stack only on success and failure keeps current path`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val browser = FakeBrowser { path, _ ->
            if (path.endsWith("/denied")) {
                Result.failure(IllegalStateException("目录读取失败（adb 退出码 1）：ls: /sdcard/denied: Permission denied"))
            } else {
                Result.success(okEntries())
            }
        }
        val vm = DeviceFileBrowserViewModel(devices, browser.listDirectory)
        vm.settle()

        vm.enterDirectory(entry("Movies", DeviceFileType.DIRECTORY))
        vm.settle()
        assertEquals("/sdcard/Movies", vm.state.value.path, "进入成功提交导航栈")
        assertTrue(vm.state.value.canNavigateUp)

        vm.enterDirectory(entry("denied", DeviceFileType.DIRECTORY))
        vm.settle()
        assertEquals("/sdcard/Movies", vm.state.value.path, "进入失败停在原目录，不落半截导航")
        assertTrue(
            vm.state.value.error!!.contains("Permission denied"),
            "失败回执人话原样透传（实际：${vm.state.value.error}）",
        )
        assertEquals(
            listOf(DEVICE_FILE_BROWSER_ROOT, "/sdcard/Movies", "/sdcard/Movies/denied"),
            browser.calls.map { it.first },
            "初始装载 + 两次进入各发一次 ls（第二次以当前目录为父）",
        )

        // 失败后的成功动作清零回执
        vm.enterDirectory(entry("Movies", DeviceFileType.DIRECTORY))
        vm.settle()
        assertNull(vm.state.value.error, "下一动作开始清零旧回执")
    }

    @Test
    fun `enter rejects non-directory entry and in-flight navigation`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(devices, browser.listDirectory)
        vm.settle()

        vm.enterDirectory(entry("build.log"))
        vm.settle()
        assertEquals(listOf(DEVICE_FILE_BROWSER_ROOT to "S1"), browser.calls, "非目录条目零 spawn")

        // 装载中（在途请求未收敛）进入被拒——虚拟时钟不推进即在途
        vm.enterDirectory(entry("Movies", DeviceFileType.DIRECTORY))
        val inFlightCalls = browser.calls.size
        vm.enterDirectory(entry("Movies", DeviceFileType.DIRECTORY))
        assertEquals(inFlightCalls, browser.calls.size, "装载中重复进入被拒（防对同目录双发 ls）")
        vm.settle()
    }

    // —— symlink 真值面（fake 解引用 stat 注入，覆盖进目录尾斜杠/拉取放行/断链回执） ——

    private fun symlinkVm(
        devices: FakeDeviceSource,
        lister: FakeBrowser,
        stat: FakeStat,
    ): DeviceFileBrowserViewModel {
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        return DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            statEntryDereferenced = stat.statEntry,
        )
    }

    @Test
    fun `symlink entry enters target directory with trailing slash penetration`() {
        val devices = FakeDeviceSource()
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val stat = FakeStat { _, _ -> Result.success(dirStat()) }
        val vm = symlinkVm(devices, browser, stat)
        vm.settle()

        vm.enterDirectory(entry("sdcard", DeviceFileType.SYMLINK))
        vm.settle()

        assertEquals(listOf("/sdcard/sdcard" to "S1"), stat.calls, "解引用 stat 以无尾斜杠子路径判真值")
        assertEquals(
            listOf(DEVICE_FILE_BROWSER_ROOT, "/sdcard/sdcard/"),
            browser.calls.map { it.first },
            "真值目录以尾斜杠路径装载（ls -la 对 symlink 终段只列链接自身，尾斜杠强制穿透）",
        )
        assertEquals("/sdcard/sdcard/", vm.state.value.path, "导航栈记尾斜杠形态")
        assertTrue(vm.state.value.canNavigateUp, "symlink 进入后可返回上级")
        assertNull(vm.state.value.error)

        vm.navigateUp()
        vm.settle()
        assertEquals(DEVICE_FILE_BROWSER_ROOT, vm.state.value.path, "从 symlink 目录返回上级")
    }

    @Test
    fun `symlink entry pointing to file surfaces guidance receipt without ls spawn`() {
        val devices = FakeDeviceSource()
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val stat = FakeStat { _, _ -> Result.success(fileStat(512)) }
        val vm = symlinkVm(devices, browser, stat)
        vm.settle()

        vm.enterDirectory(entry("sdcard", DeviceFileType.SYMLINK))
        vm.settle()

        assertEquals(1, browser.calls.size, "真值文件不进目录——零 ls spawn")
        assertEquals(false, vm.state.value.loading, "回执后装载态复位")
        val error = vm.state.value.error
        assertTrue(error!!.contains("指向普通文件的符号链接"), "真值文件人话回执不静默（实际：$error）")
        assertTrue(error.contains("拉取到本地"), "回执指引拉取动作（实际：$error）")
    }

    @Test
    fun `dangling symlink enter surfaces broken-link receipt`() {
        val devices = FakeDeviceSource()
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val stat = FakeStat { _, _ ->
            Result.failure(IllegalStateException("stat 读取失败（adb 退出码 1）：stat: '/sdcard/dead': No such file or directory"))
        }
        val vm = symlinkVm(devices, browser, stat)
        vm.settle()

        vm.enterDirectory(entry("dead", DeviceFileType.SYMLINK))
        vm.settle()

        assertEquals(1, browser.calls.size, "断链不进目录——零 ls spawn")
        val error = vm.state.value.error
        assertTrue(error!!.contains("断链"), "断链人话回执不静默（实际：$error）")
        assertTrue(error.contains("No such file or directory"), "断链回执透传设备侧原文（实际：$error）")
    }

    @Test
    fun `symlink entry pull resolves truth face and pulls target file`() {
        val dir = pullTempDir()
        val gatewayScript: suspend (String, AdbPullHandle, suspend (ShellOutput) -> Unit) -> Int =
            { command, _, onLine ->
                val partPath = command.substringAfterLast(" ").trim('\'')
                java.io.File(partPath).writeBytes(ByteArray(4948))
                onLine(ShellOutput("1 file pulled, 0 skipped. 38.4 MB/s (4948 bytes in 1.0s)", ShellOutputSource.STDOUT))
                0
            }
        val gateway = FakePullGateway(gatewayScript)
        val devices = FakeDeviceSource()
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val stat = FakeStat { _, _ -> Result.success(fileStat(4948)) }
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val vm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            statEntryDereferenced = stat.statEntry,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = gateway, statEntry = pullStat(4948), endpointReader = { AdbEndpoint("") }),
            pullTargetDir = dir,
        )
        vm.settle()

        vm.pullEntry(entry("cat", DeviceFileType.SYMLINK))
        vm.settle()

        assertEquals(listOf("/sdcard/cat" to "S1"), stat.calls, "拉取前以无尾斜杠子路径判真值")
        assertEquals(1, gateway.commands.size, "真值文件放行拉取——单次 pull spawn")
        assertTrue(
            gateway.commands.single().contains("adb -s S1 pull '/sdcard/cat'"),
            "命令以 symlink 子路径为设备源（实际：${gateway.commands.single()}）",
        )
        val pull = vm.state.value.pull
        assertTrue(pull is DeviceFilePullUiState.Done, "真值文件拉取完成态（实际：$pull）")
        assertEquals(4948L, (pull.receipt as DeviceFilePullReceipt).sizeBytes, "校验通过的大小 = 解引用真值尺寸")
        assertEquals(4948L, java.io.File(dir, "cat").length(), "终态文件以链接名落本地")
    }

    @Test
    fun `symlink pointing to directory or dangling link pull surfaces receipts without spawn`() {
        val dir = pullTempDir()
        val devices = FakeDeviceSource()
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")

        // 真值目录：人话拒绝 + 零 pull spawn
        val dirStatRouter = FakeStat { _, _ -> Result.success(dirStat()) }
        val dirGateway = FakePullGateway { _, _, _ -> 0 }
        val dirVm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            statEntryDereferenced = dirStatRouter.statEntry,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = dirGateway, statEntry = pullStat(4096), endpointReader = { AdbEndpoint("") }),
            pullTargetDir = dir,
        )
        dirVm.settle()
        dirVm.pullEntry(entry("sdcard", DeviceFileType.SYMLINK))
        dirVm.settle()
        assertEquals(0, dirGateway.commands.size, "真值目录零 pull spawn")
        val dirPull = dirVm.state.value.pull
        assertTrue(dirPull is DeviceFilePullUiState.Failed, "真值目录人话拒绝（实际：$dirPull）")
        assertTrue(dirPull.message.contains("指向目录的符号链接"), "拒绝回执明示真值（实际：${dirPull.message}）")

        // 断链：人话回执不静默 + 零 pull spawn
        val deadStatRouter = FakeStat { _, _ ->
            Result.failure(IllegalStateException("stat 读取失败（adb 退出码 1）：stat: '/sdcard/dead': No such file or directory"))
        }
        val deadGateway = FakePullGateway { _, _, _ -> 0 }
        val deadVm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            statEntryDereferenced = deadStatRouter.statEntry,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = deadGateway, statEntry = pullStat(4096), endpointReader = { AdbEndpoint("") }),
            pullTargetDir = dir,
        )
        deadVm.settle()
        deadVm.pullEntry(entry("dead", DeviceFileType.SYMLINK))
        deadVm.settle()
        assertEquals(0, deadGateway.commands.size, "断链零 pull spawn")
        val deadPull = deadVm.state.value.pull
        assertTrue(deadPull is DeviceFilePullUiState.Failed, "断链人话回执（实际：$deadPull）")
        assertTrue(deadPull.message.contains("断链"), "断链回执不静默（实际：${deadPull.message}）")
        assertTrue(deadPull.message.contains("未执行拉取"), "断链回执明示未执行（实际：${deadPull.message}）")
    }

    @Test
    fun `navigate up pops one level and root up is no action`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(devices, browser.listDirectory)
        vm.settle()

        vm.enterDirectory(entry("Movies", DeviceFileType.DIRECTORY))
        vm.settle()
        vm.enterDirectory(entry("nested", DeviceFileType.DIRECTORY))
        vm.settle()
        assertEquals("/sdcard/Movies/nested", vm.state.value.path)

        vm.navigateUp()
        vm.settle()
        assertEquals("/sdcard/Movies", vm.state.value.path, "返回上级弹一层")

        vm.navigateUp()
        vm.settle()
        assertEquals(DEVICE_FILE_BROWSER_ROOT, vm.state.value.path)
        assertEquals(false, vm.state.value.canNavigateUp)

        val callsAtRoot = browser.calls.size
        vm.navigateUp()
        vm.settle()
        assertEquals(callsAtRoot, browser.calls.size, "起点根上返回为无动作（零 spawn）")
    }

    @Test
    fun `refresh reloads current path in place`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(devices, browser.listDirectory)
        vm.settle()
        vm.enterDirectory(entry("Movies", DeviceFileType.DIRECTORY))
        vm.settle()

        vm.refresh()
        vm.settle()
        assertEquals("/sdcard/Movies", vm.state.value.path, "刷新不换目录（重试语义）")
        assertEquals(
            listOf(DEVICE_FILE_BROWSER_ROOT, "/sdcard/Movies", "/sdcard/Movies"),
            browser.calls.map { it.first },
            "刷新对当前路径再发一次 ls",
        )
    }

    @Test
    fun `refresh failure surfaces human receipt`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val browser = FakeBrowser { _, _ ->
            Result.failure(IllegalStateException("目录读取失败（adb 退出码 1）：ls: /sdcard: No such file or directory"))
        }
        val vm = DeviceFileBrowserViewModel(devices, browser.listDirectory)
        vm.settle()
        assertTrue(vm.state.value.error!!.contains("No such file or directory"), "失败回执含设备侧原文")

        vm.refresh()
        vm.settle()
        assertEquals(2, browser.calls.size, "手动重试再次发 ls")
    }

    @Test
    fun `refresh without target surfaces blocked receipt and no spawn`() {
        val devices = FakeDeviceSource()
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }

        // 无设备
        val noneVm = DeviceFileBrowserViewModel(devices, browser.listDirectory).also { it.settle() }
        noneVm.refresh()
        assertTrue(noneVm.state.value.error!!.contains("未检测到设备"), "无设备回执人话（实际：${noneVm.state.value.error}）")

        // 检测失败（原文透传）
        devices.detectErrorFlow.value = "设备检测失败：adb not found"
        val errVm = DeviceFileBrowserViewModel(devices, browser.listDirectory).also { it.settle() }
        errVm.refresh()
        assertTrue(errVm.state.value.error!!.contains("adb not found"), "检测失败回执含原因原文")

        // 多设备未选定
        devices.detectErrorFlow.value = null
        devices.deviceStateFlow.value = DeviceState.Multi(listOf(DeviceInfo("S1", "Demo-1"), DeviceInfo("S2", "M1")))
        val multiVm = DeviceFileBrowserViewModel(devices, browser.listDirectory).also { it.settle() }
        multiVm.refresh()
        assertTrue(multiVm.state.value.error!!.contains("多设备连接"), "多设备未选回执指引设备栏（实际：${multiVm.state.value.error}）")

        assertEquals(0, browser.calls.size, "三态目标不可用均零 spawn")
    }

    @Test
    fun `target serial change reloads from root and resets navigation stack`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(devices, browser.listDirectory)
        vm.settle()
        vm.enterDirectory(entry("Movies", DeviceFileType.DIRECTORY))
        vm.settle()
        assertEquals("/sdcard/Movies", vm.state.value.path)

        // 单台 → 多台并选另一台：串号实变
        devices.deviceStateFlow.value = DeviceState.Multi(listOf(DeviceInfo("S1", "Demo-1"), DeviceInfo("S2", "M1")))
        devices.selectedSerialFlow.value = "S2"
        vm.settle()
        assertEquals(
            listOf(DEVICE_FILE_BROWSER_ROOT to "S1", "/sdcard/Movies" to "S1", DEVICE_FILE_BROWSER_ROOT to "S2"),
            browser.calls,
            "串号实变 → 栈重置回根并对新串号重载（导航栈不跨设备互推）",
        )
        assertEquals(DEVICE_FILE_BROWSER_ROOT, vm.state.value.path)
        assertEquals(false, vm.state.value.canNavigateUp)

        // 同串号重选（StateFlow 同值不重发）与目标流抖动零重载：末次装载后无新调用
        val callsAfterSwitch = browser.calls.size
        devices.selectedSerialFlow.value = "S2"
        vm.settle()
        assertEquals(callsAfterSwitch, browser.calls.size, "串号未变零重载")
    }

    @Test
    fun `target becoming unavailable invalidates entries`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(devices, browser.listDirectory)
        vm.settle()
        assertTrue(vm.state.value.entries != null, "前置：已装载")

        devices.deviceStateFlow.value = DeviceState.None
        vm.settle()
        assertNull(vm.state.value.entries, "目标失效清空陈旧读数（不冒充可读数）")
        assertTrue(vm.state.value.error!!.contains("目标设备已不可用"), "失效回执人话（实际：${vm.state.value.error}）")

        // 设备回线（同串号）：从失效态重新装载
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        vm.settle()
        assertTrue(vm.state.value.entries != null, "回线后重载")
        assertEquals(2, browser.calls.size)
    }

    @Test
    fun `size text formats bytes in human units`() {
        assertEquals("512B", deviceFileSizeText(512))
        assertEquals("4.0KB", deviceFileSizeText(4096))
        assertEquals("1.5MB", deviceFileSizeText(1024 * 1024 + 512 * 1024))
        assertEquals("2.0GB", deviceFileSizeText(2L * 1024 * 1024 * 1024))
    }

    // —— 拉取动作回归网（fake pull gateway 注入真拉取内核，零真子进程；
    //    本地目标一律临时目录，🔴 不碰用户数据根） ——

    /** fake pull gateway：记录命令，按脚本回执（脚本可挂起模拟在途拉取） */
    private class FakePullGateway(
        private val script: suspend (command: String, handle: AdbPullHandle, onLine: suspend (ShellOutput) -> Unit) -> Int,
    ) : AdbPullGateway {
        val commands = mutableListOf<String>()

        override suspend fun execute(
            command: String,
            onStarted: (AdbPullHandle) -> Unit,
            onLine: suspend (ShellOutput) -> Unit,
        ): Result<Int> {
            commands += command
            val handle = object : AdbPullHandle {
                var killed = false
                override fun kill() {
                    killed = true
                }
            }
            onStarted(handle)
            return Result.success(script(command, handle, onLine))
        }
    }

    private fun pullStat(size: Long): suspend (String, String) -> Result<DeviceFileStat> = { _, _ ->
        Result.success(
            DeviceFileStat(
                type = DeviceFileType.FILE,
                sizeBytes = size,
                modifiedEpochSeconds = 1_700_000_000L,
                permissions = "-rw-rw----",
                owner = "root",
                group = "root",
            ),
        )
    }

    private fun pullTempDir(): java.io.File = Files.createTempDirectory("vm-pull").toFile()

    private fun pullVm(
        devices: FakeDeviceSource,
        script: suspend (String, AdbPullHandle, suspend (ShellOutput) -> Unit) -> Int,
        targetDir: java.io.File,
        statSize: Long,
    ): DeviceFileBrowserViewModel {
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        return DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            puller = DeviceFilePuller(
                runner = NoopRunner,
                gateway = FakePullGateway(script),
                statEntry = pullStat(statSize),
                endpointReader = { AdbEndpoint("") },
            ),
            pullTargetDir = targetDir,
        )
    }

    @Test
    fun `pull entry busy then done receipt with local path and summary`() {
        val dir = pullTempDir()
        val gatewayScript: suspend (String, AdbPullHandle, suspend (ShellOutput) -> Unit) -> Int =
            { command, _, onLine ->
                val partPath = command.substringAfterLast(" ").trim('\'')
                java.io.File(partPath).writeBytes(ByteArray(512))
                onLine(ShellOutput("1 file pulled, 0 skipped. 38.4 MB/s (512 bytes in 1.0s)", ShellOutputSource.STDOUT))
                0
            }
        val vm = pullVm(FakeDeviceSource(), gatewayScript, dir, statSize = 512)
        vm.settle()

        vm.pullEntry(entry("build.log"))
        vm.settle()

        val pull = vm.state.value.pull
        assertTrue(pull is DeviceFilePullUiState.Done, "拉取完成态（实际：$pull）")
        assertEquals("build.log", pull.fileName)
        assertEquals(java.io.File(dir, "build.log").absolutePath, pull.receipt.localPath, "产物落默认目标目录")
        val receipt = pull.receipt as DeviceFilePullReceipt
        assertEquals(512L, receipt.sizeBytes, "校验通过的大小")
        assertEquals(512L, java.io.File(dir, "build.log").length(), "终态文件在位")
        assertTrue(!java.io.File(dir, "build.log.part").exists(), "转正后 .part 不存在")
        assertEquals("38.4 MB/s", receipt.speedText, "完成回执携带速度")
    }

    @Test
    fun `second pull while busy is rejected and cancelled pull lands cleaned receipt`() {
        val dir = pullTempDir()
        lateinit var vm: DeviceFileBrowserViewModel
        val gate = CompletableDeferred<Int>()
        val gateway = FakePullGateway { command, _, _ ->
            val partPath = command.substringAfterLast(" ").trim('\'')
            java.io.File(partPath).writeBytes(ByteArray(85))
            gate.await() // 挂起模拟在途拉取
        }
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        vm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = gateway, statEntry = pullStat(349), endpointReader = { AdbEndpoint("") }),
            pullTargetDir = dir,
        )
        vm.settle()

        vm.pullEntry(entry("build.log"))
        vm.settle()
        assertTrue(vm.state.value.pull is DeviceFilePullUiState.Busy, "在途拉取 busy 态（期望大小上屏）")

        val callsInFlight = gateway.commands.size
        vm.pullEntry(entry("build.log"))
        assertEquals(callsInFlight, gateway.commands.size, "busy 期间再点拉取被拒（单飞行）")

        vm.cancelPull()
        gate.complete(1) // kill 后 adb 非零退出
        vm.settle()
        val pull = vm.state.value.pull
        assertTrue(pull is DeviceFilePullUiState.Failed, "取消后失败回执（实际：$pull）")
        assertTrue(pull.message.contains("拉取已取消"), "取消人话回执（实际：${pull.message}）")
        assertTrue(!java.io.File(dir, "build.log.part").exists(), "取消同走 .part 清理路径")
        assertTrue(!java.io.File(dir, "build.log").exists(), "取消不落终态文件")
    }

    @Test
    fun `pull failure surfaces kernel receipt verbatim`() {
        val dir = pullTempDir()
        val gatewayScript: suspend (String, AdbPullHandle, suspend (ShellOutput) -> Unit) -> Int =
            { command, _, onLine ->
                val partPath = command.substringAfterLast(" ").trim('\'')
                java.io.File(partPath).writeBytes(ByteArray(85))
                onLine(ShellOutput("adb: error: failed to stat remote object '/sdcard/build.log': No such file or directory", ShellOutputSource.STDERR))
                1
            }
        val devices = FakeDeviceSource()
        val gateway = FakePullGateway(gatewayScript)
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = gateway, statEntry = pullStat(349), endpointReader = { AdbEndpoint("") }),
            pullTargetDir = dir,
        )
        vm.settle()

        vm.pullEntry(entry("build.log"))
        vm.settle()
        val pull = vm.state.value.pull
        assertTrue(pull is DeviceFilePullUiState.Failed, "失败回执（实际：$pull）")
        assertTrue(pull.message.contains("No such file or directory"), "失败回执透传设备侧原文（实际：${pull.message}）")
        assertTrue(!java.io.File(dir, "build.log").exists(), "失败不落终态文件")
    }

    // —— 目录条目拉取链（fake gateway 注入真拉取内核，零真子进程） ——

    /** 模拟 adb 把递归产物写进 .part 目录并吐多文件摘要行的成功脚本 */
    private val writePartDirScript: suspend (String, AdbPullHandle, suspend (ShellOutput) -> Unit) -> Int =
        { command, _, onLine ->
            val partDir = java.io.File(command.substringAfterLast(" ").trim('\'')).apply { mkdirs() }
            java.io.File(partDir, "a.alog").writeBytes(ByteArray(512))
            java.io.File(partDir, "sub").mkdirs()
            java.io.File(partDir, "sub/b.alog").writeBytes(ByteArray(100))
            onLine(ShellOutput("2 files pulled, 0 skipped. 38.4 MB/s (612 bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }

    @Test
    fun `directory entry pull spawns recursive pull and registers directory product`() {
        val dir = pullTempDir()
        TaskPipelineHandoff.clearForTest()
        TaskPipelineHandoff.wire {} // 衔接门接线后目录产物仍须缺席（by design）
        try {
            val devices = FakeDeviceSource()
            devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
            val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
            val gateway = FakePullGateway(writePartDirScript)
            val vm = DeviceFileBrowserViewModel(
                devices,
                lister.listDirectory,
                puller = DeviceFilePuller(runner = NoopRunner, gateway = gateway, statEntry = pullStat(4096), endpointReader = { AdbEndpoint("") }),
                pullTargetDir = dir,
            )
            vm.settle()

            vm.pullEntry(entry("Movies", DeviceFileType.DIRECTORY))
            vm.settle()

            assertEquals(
                listOf("adb -s S1 pull '/sdcard/Movies' '${java.io.File(dir, "Movies.part").absolutePath}'"),
                gateway.commands,
                "目录条目放行目录递归拉取（设备源为目录路径，本地目标为 .part 目录）",
            )
            val pull = vm.state.value.pull
            assertTrue(pull is DeviceFilePullUiState.Done, "目录拉取完成态（实际：$pull）")
            val receipt = pull.receipt
            assertTrue(receipt is DeviceDirectoryPullReceipt, "目录回执形态（实际：$receipt）")
            assertEquals(2L, receipt.fileCount, "摘要行文件数（信息面）")
            assertEquals(612L, receipt.totalBytes, "摘要行总字节（信息面）")
            assertEquals(java.io.File(dir, "Movies").absolutePath, receipt.localPath, "终态目录落默认目标目录")
            assertTrue(java.io.File(dir, "Movies/a.alog").isFile, "递归产物在位")
            assertTrue(java.io.File(dir, "Movies/sub/b.alog").isFile, "子目录递归产物在位")
            assertTrue(!java.io.File(dir, "Movies.part").exists(), "转正后 .part 目录消失")

            val products = vm.state.value.pullProducts
            assertEquals(listOf("Movies"), products.map { it.fileName }, "目录产物登记进会话缓存")
            vm.handoffPullProduct(products.single())
            assertNull(TaskPipelineHandoff.consumeSeed(), "目录产物无衔接投递（衔接钮缺席 by design，守卫兜底）")
            assertNull(vm.state.value.pullProductError, "目录产物衔接拒绝静默（面板本无钮可点）")
        } finally {
            TaskPipelineHandoff.clearForTest()
        }
    }

    @Test
    fun `directory pull busy marks directory flag, rejects second action, cancel cleans part dir`() {
        val dir = pullTempDir()
        lateinit var vm: DeviceFileBrowserViewModel
        val gate = CompletableDeferred<Int>()
        val gateway = FakePullGateway { command, _, _ ->
            val partDir = java.io.File(command.substringAfterLast(" ").trim('\'')).apply { mkdirs() }
            java.io.File(partDir, "half.alog").writeBytes(ByteArray(85))
            gate.await() // 挂起模拟在途目录拉取
        }
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        vm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = gateway, statEntry = pullStat(4096), endpointReader = { AdbEndpoint("") }),
            pullTargetDir = dir,
        )
        vm.settle()

        vm.pullEntry(entry("Movies", DeviceFileType.DIRECTORY))
        vm.settle()
        val busy = vm.state.value.pull
        assertTrue(busy is DeviceFilePullUiState.Busy && busy.isDirectory, "目录拉取 busy 态置位目录标记（实际：$busy）")

        val callsInFlight = gateway.commands.size
        vm.pullEntry(entry("build.log"))
        assertEquals(callsInFlight, gateway.commands.size, "busy 期间再点（含另一链的文件条目）被拒——两链共用单飞")

        vm.cancelPull()
        gate.complete(1) // kill 后 adb 非零退出
        vm.settle()
        val pull = vm.state.value.pull
        assertTrue(pull is DeviceFilePullUiState.Failed, "取消后失败回执（实际：$pull）")
        assertTrue(pull.message.contains("拉取已取消"), "取消人话回执（实际：${pull.message}）")
        assertTrue(!java.io.File(dir, "Movies").exists(), "取消不落终态目录")
        assertTrue(!java.io.File(dir, "Movies.part").exists(), "取消同走 .part 目录递归清理路径")
    }

    @Test
    fun `pull without target surfaces blocked receipt and no spawn`() {
        val dir = pullTempDir()
        val gateway = FakePullGateway { _, _, _ -> 0 }
        val devices = FakeDeviceSource() // None：无设备
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = gateway, statEntry = pullStat(512), endpointReader = { AdbEndpoint("") }),
            pullTargetDir = dir,
        )
        vm.settle()

        vm.pullEntry(entry("build.log"))
        vm.settle()
        assertEquals(0, gateway.commands.size, "目标不可用零 spawn")
        assertEquals(DeviceFilePullUiState.Idle, vm.state.value.pull, "同步拒绝不进 busy 态")
        assertTrue(vm.state.value.error!!.contains("未检测到设备"), "拒绝回执走目录读数错误槽（实际：${vm.state.value.error}）")
    }

    @Test
    fun `pull product handoff target is resolved by extension semantics`() {
        val vm = pullVm(
            FakeDeviceSource(),
            { _, _, _ -> 0 },
            pullTempDir(),
            statSize = 4,
        )

        assertEquals("logkit.tag-filter", vm.nextToolTargetForPullProduct("build.log.txt")?.commandId, ".txt 文本产物 → Tag 过滤")
        assertEquals("logkit.tag-filter", vm.nextToolTargetForPullProduct("plainlog")?.commandId, "无后缀产物 → Tag 过滤")
        assertEquals(
            "logkit.xlog-decrypt",
            vm.nextToolTargetForPullProduct("car.alog")?.commandId,
            ".alog 加密件 → XLog 解密",
        )
        assertEquals(
            "logkit.xlog-decrypt",
            vm.nextToolTargetForPullProduct("car.xlog")?.commandId,
            ".xlog 加密件 → XLog 解密",
        )
        assertNull(vm.nextToolTargetForPullProduct("photo.png"), "未映射后缀无衔接（条目不出现）")
        assertEquals("送往 Tag 过滤", vm.nextToolTargetForPullProduct("build.log.txt")?.label, "衔接条目文案随目标")
        assertEquals("送 XLog 解密", vm.nextToolTargetForPullProduct("car.alog")?.label, "衔接条目文案随目标")
    }

    // —— 条目过滤投影（纯函数矩阵 + VM 投影面：显示层零 spawn、翻目录不清词） ——

    @Test
    fun `filterFileEntries matrix covers trim case and empty projection`() {
        val all = okEntries()
        assertEquals(emptyList(), filterFileEntries(null, "log"), "未装载列表过滤恒空")
        assertEquals(3, filterFileEntries(all, "  ").size, "查询词空白视同不过滤")
        assertEquals(
            listOf("build.log"),
            filterFileEntries(all, "BUILD").map { it.name },
            "大小写不敏感包含匹配",
        )
        assertEquals(
            listOf("Movies"),
            filterFileEntries(all, " movies ").map { it.name },
            "查询词两端空白修剪",
        )
        assertEquals(
            listOf("sdcard"),
            filterFileEntries(all, "card").map { it.name },
            "包含语义（中缀命中）",
        )
        assertEquals(emptyList(), filterFileEntries(all, "zip"), "无匹配过滤为空列表")
    }

    @Test
    fun `setQuery is display layer projection with zero spawn and survives navigation`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val browser = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(devices, browser.listDirectory)
        vm.settle()

        val callsBeforeFilter = browser.calls.size
        vm.setQuery("log")
        assertEquals(callsBeforeFilter, browser.calls.size, "过滤是显示层纯投影——零 ls spawn")
        assertEquals(listOf("build.log"), vm.state.value.filtered.map { it.name }, "过滤词即时投影到 filtered")
        assertEquals(3, vm.state.value.entries?.size, "entries 全量读数不被过滤裁剪（过滤只裁显示面）")

        vm.setQuery("zip")
        assertEquals(emptyList(), vm.state.value.filtered, "无匹配过滤为空列表回执")

        vm.setQuery("")
        assertEquals(3, vm.state.value.filtered.size, "清空过滤词恢复全量列表")

        vm.setQuery("log")
        vm.enterDirectory(entry("Movies", DeviceFileType.DIRECTORY))
        vm.settle()
        assertEquals("log", vm.state.value.query, "翻目录保留过滤词")
        assertEquals(
            listOf("build.log"),
            vm.state.value.filtered.map { it.name },
            "新目录读数继续按保留的过滤词投影",
        )
        assertEquals(callsBeforeFilter + 1, browser.calls.size, "进入目录正常装载（过滤不吞装载也不多发）")

        // 目标失效清读数不清词：过滤词是显示偏好，回线后新读数继续投影
        devices.deviceStateFlow.value = DeviceState.None
        vm.settle()
        assertNull(vm.state.value.entries, "目标失效清空读数")
        assertEquals(emptyList(), vm.state.value.filtered, "未装载时过滤投影恒空列表")
        assertEquals("log", vm.state.value.query, "目标失效不清过滤词")
    }

    // —— 拉回产物会话缓存（多产物共存/上界淘汰/外部删除人话回执/衔接投递） ——

    /** 通用拉取脚本：落 512 字节 .part 转正 + adb 完成摘要行（statSize=512 配套） */
    private val writePartScript: suspend (String, AdbPullHandle, suspend (ShellOutput) -> Unit) -> Int =
        { command, _, onLine ->
            val partPath = command.substringAfterLast(" ").trim('\'')
            java.io.File(partPath).writeBytes(ByteArray(512))
            onLine(ShellOutput("1 file pulled, 0 skipped. 38.4 MB/s (512 bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }

    @Test
    fun `pull products coexist with latest first`() {
        val dir = pullTempDir()
        val vm = pullVm(FakeDeviceSource(), writePartScript, dir, statSize = 512)
        vm.settle()

        vm.pullEntry(entry("a.log"))
        vm.settle()
        vm.pullEntry(entry("b.txt"))
        vm.settle()

        val products = vm.state.value.pullProducts
        assertEquals(listOf("b.txt", "a.log"), products.map { it.fileName }, "拉 B 不丢 A——多产物共存，最近在上")
        assertEquals(java.io.File(dir, "a.log").absolutePath, products[1].receipt.localPath, "首拉产物回执保留")
        val receipt = products[0].receipt as DeviceFilePullReceipt
        assertEquals(512L, receipt.sizeBytes, "每行回执携带校验大小")
        assertEquals("38.4 MB/s", receipt.speedText, "每行回执携带速度")
    }

    @Test
    fun `pull product cache is bounded and evicts oldest`() {
        val dir = pullTempDir()
        val vm = pullVm(FakeDeviceSource(), writePartScript, dir, statSize = 512)
        vm.settle()

        val names = (1..(PULL_PRODUCT_CACHE_LIMIT + 1)).map { "f" + it.toString().padStart(2, '0') + ".log" }
        names.forEach { name ->
            vm.pullEntry(entry(name))
            vm.settle()
        }

        val products = vm.state.value.pullProducts
        assertEquals(PULL_PRODUCT_CACHE_LIMIT, products.size, "缓存有界（上界 ${PULL_PRODUCT_CACHE_LIMIT}）")
        assertEquals("f21.log", products.first().fileName, "最近拉取在头")
        assertEquals("f02.log", products.last().fileName, "最旧产物（f01）被淘汰")
    }

    @Test
    fun `externally deleted product surfaces human receipt on handoff and preview`() {
        val dir = pullTempDir()
        TaskPipelineHandoff.clearForTest()
        TaskPipelineHandoff.wire {} // 面板送分析条目只在接线后出现——测试对齐该门
        try {
            val vm = pullVm(FakeDeviceSource(), writePartScript, dir, statSize = 512)
            vm.settle()
            vm.pullEntry(entry("a.txt"))
            vm.settle()
            val product = vm.state.value.pullProducts.single()
            assertNull(vm.state.value.pullProductError, "在位产物衔接零回执（前置）")

            java.io.File(dir, "a.txt").delete()
            vm.handoffPullProduct(product)
            val receipt = vm.state.value.pullProductError
            assertTrue(receipt!!.contains("已不在原位"), "外部删除人话回执不静默（实际：$receipt）")
            assertTrue(receipt.contains("a.txt"), "回执点名产物文件")
            assertNull(TaskPipelineHandoff.consumeSeed(), "删除产物不投递衔接载荷")

            assertEquals(false, vm.requestPullProductPreview(product), "删除产物预览闸拒绝展开")
            assertTrue(
                vm.state.value.pullProductError!!.contains("已不在原位"),
                "预览闸同样落人话回执（实际：${vm.state.value.pullProductError}）",
            )

            // 下一次拉取清零旧回执（动作时刻清零纪律）
            vm.pullEntry(entry("b.txt"))
            vm.settle()
            assertNull(vm.state.value.pullProductError, "新拉取开始清零产物衔接回执")
            assertEquals(2, vm.state.value.pullProducts.size, "删除产物行保留在会话缓存（回执快照不因缺席摘除）")
        } finally {
            TaskPipelineHandoff.clearForTest()
        }
    }

    @Test
    fun `handoff offers pipeline seed for present product`() {
        val dir = pullTempDir()
        TaskPipelineHandoff.clearForTest()
        TaskPipelineHandoff.wire {}
        try {
            val vm = pullVm(FakeDeviceSource(), writePartScript, dir, statSize = 512)
            vm.settle()
            vm.pullEntry(entry("a.txt"))
            vm.settle()

            vm.handoffPullProduct(vm.state.value.pullProducts.single())
            val seed = TaskPipelineHandoff.consumeSeed()
            assertEquals("logkit.tag-filter", seed?.commandId, ".txt 产物 → Tag 过滤（查表复用）")
            assertEquals(
                java.io.File(dir, "a.txt").absolutePath,
                seed?.args?.get("file_path"),
                "产物路径落目标命令文件参数槽",
            )
            assertNull(vm.state.value.pullProductError, "在位产物衔接零失败回执")
        } finally {
            TaskPipelineHandoff.clearForTest()
        }
    }

    // —— 同名撞车避让（落盘前撞名判定：异源 -N 避让 / 同源覆盖刷新；文件/目录两链同口径） ——

    /** 分内容拉取脚本：按调用序写互异定长 payload（statSize=10 配套）——覆盖/避让语义的内容断言锚 */
    private fun distinctContentScript(
        counter: AtomicInteger,
    ): suspend (String, AdbPullHandle, suspend (ShellOutput) -> Unit) -> Int = { command, _, onLine ->
        val partPath = command.substringAfterLast(" ").trim('\'')
        val payload = "payload-" + counter.incrementAndGet().toString().padStart(2, '0')
        java.io.File(partPath).writeBytes(payload.toByteArray())
        onLine(ShellOutput("1 file pulled, 0 skipped. 38.4 MB/s (10 bytes in 1.0s)", ShellOutputSource.STDOUT))
        0
    }

    @Test
    fun `resolvePullTargetFile matrix covers refresh, avoidance and disk occupancy`() {
        val dir = pullTempDir()
        fun row(localName: String, serial: String, devicePath: String) = DeviceFilePullProduct(
            fileName = localName,
            receipt = DeviceFilePullReceipt(java.io.File(dir, localName).absolutePath, 1),
            sourceSerial = serial,
            sourceDevicePath = devicePath,
        )

        assertEquals(
            java.io.File(dir, "log.txt"),
            resolvePullTargetFile(dir, "log.txt", "S1", "/sdcard/log.txt", emptyList()),
            "缓存无占位行 → 原名落盘（跨会话旧文件不感知——会话级缓存既有契约）",
        )
        assertEquals(
            java.io.File(dir, "log.txt"),
            resolvePullTargetFile(dir, "log.txt", "S1", "/sdcard/log.txt", listOf(row("log.txt", "S1", "/sdcard/log.txt"))),
            "同源重拉 → 原名覆盖刷新（重拉最新日志是正动作）",
        )
        assertEquals(
            java.io.File(dir, "log-1.txt"),
            resolvePullTargetFile(dir, "log.txt", "S1", "/sdcard/log.txt", listOf(row("log.txt", "S2", "/sdcard/log.txt"))),
            "异设备同名（设备侧路径相同、串号异）→ -1 避让（串号分源）",
        )
        assertEquals(
            java.io.File(dir, "log-1.txt"),
            resolvePullTargetFile(dir, "log.txt", "S1", "/sdcard/log.txt", listOf(row("log.txt", "S1", "/sdcard/other/log.txt"))),
            "同设备异目录同名 → -1 避让（设备侧路径分源）",
        )
        assertEquals(
            java.io.File(dir, "log-2.txt"),
            resolvePullTargetFile(
                dir, "log.txt", "S1", "/sdcard/log.txt",
                listOf(row("log.txt", "S2", "/sdcard/log.txt"), row("log-1.txt", "S3", "/sdcard/y/log.txt")),
            ),
            "避让位被异源行占据 → 序号递增直到空闲",
        )
        assertEquals(
            java.io.File(dir, "log-1.txt"),
            resolvePullTargetFile(
                dir, "log.txt", "S1", "/sdcard/y/log.txt",
                listOf(row("log.txt", "S2", "/sdcard/log.txt"), row("log-1.txt", "S1", "/sdcard/y/log.txt")),
            ),
            "避让位是同源行 → 落该位覆盖刷新（同源重拉刷新既有避让位）",
        )
        java.io.File(dir, "log-1.txt").writeBytes(ByteArray(1))
        assertEquals(
            java.io.File(dir, "log-2.txt"),
            resolvePullTargetFile(dir, "log.txt", "S1", "/sdcard/log.txt", listOf(row("log.txt", "S2", "/sdcard/log.txt"))),
            "避让位被磁盘既有文件占据（无缓存行）→ 跳过（避让落点不顶替无主文件）",
        )
        assertEquals(
            java.io.File(dir, "aplog-1"),
            resolvePullTargetFile(dir, "aplog", "S1", "/sdcard/aplog", listOf(row("aplog", "S2", "/sdcard/aplog"))),
            "无扩展名整体加尾（aplog → aplog-1）",
        )
    }

    @Test
    fun `cross-directory same-name pull avoids overwrite and first row views first pull content`() {
        val dir = pullTempDir()
        LogViewerRequest.clearForTest()
        try {
            val gateway = FakePullGateway(distinctContentScript(AtomicInteger()))
            val devices = FakeDeviceSource()
            devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
            val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
            val vm = DeviceFileBrowserViewModel(
                devices,
                lister.listDirectory,
                puller = DeviceFilePuller(runner = NoopRunner, gateway = gateway, statEntry = pullStat(10), endpointReader = { AdbEndpoint("") }),
                pullTargetDir = dir,
            )
            vm.settle()

            vm.pullEntry(entry("log.txt"))
            vm.settle()
            vm.enterDirectory(entry("Movies", DeviceFileType.DIRECTORY))
            vm.settle()
            vm.pullEntry(entry("log.txt"))
            vm.settle()

            assertTrue(
                gateway.commands[1].endsWith("'${java.io.File(dir, "log-1.txt.part").absolutePath}'"),
                "内核收到避让后目标路径原样执行（路径计算全在 VM 层，实际：${gateway.commands[1]}）",
            )
            assertEquals("payload-01", java.io.File(dir, "log.txt").readText(), "首拉内容在原位未被顶替")
            assertEquals("payload-02", java.io.File(dir, "log-1.txt").readText(), "第二拉取落 -1 避让位")

            val products = vm.state.value.pullProducts
            assertEquals(2, products.size, "异源同名两行共存（最近在上）")
            assertEquals(
                listOf(java.io.File(dir, "log-1.txt").absolutePath, java.io.File(dir, "log.txt").absolutePath),
                products.map { it.receipt.localPath },
                "缓存两行路径互异——产物行动作不再张冠李戴",
            )

            vm.viewPullProduct(products[1])
            assertEquals(
                java.io.File(dir, "log.txt").absolutePath,
                LogViewerRequest.pending.value?.path,
                "首行查看动作打开首拉内容（原位路径）",
            )
        } finally {
            LogViewerRequest.clearForTest()
        }
    }

    @Test
    fun `cross-serial same-name pull avoids overwrite`() {
        val dir = pullTempDir()
        val gateway = FakePullGateway(distinctContentScript(AtomicInteger()))
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Multi(listOf(DeviceInfo("S1", "Demo-1"), DeviceInfo("S2", "M1")))
        devices.selectedSerialFlow.value = "S1"
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = gateway, statEntry = pullStat(10), endpointReader = { AdbEndpoint("") }),
            pullTargetDir = dir,
        )
        vm.settle()

        vm.pullEntry(entry("log.txt"))
        vm.settle()
        devices.selectedSerialFlow.value = "S2"
        vm.settle()
        vm.pullEntry(entry("log.txt"))
        vm.settle()

        assertEquals("payload-01", java.io.File(dir, "log.txt").readText(), "S1 首拉内容未被 S2 拉取顶替")
        val products = vm.state.value.pullProducts
        assertEquals(listOf("S2", "S1"), products.map { it.sourceSerial }, "两行来源串号互异")
        assertEquals(
            listOf(java.io.File(dir, "log-1.txt").absolutePath, java.io.File(dir, "log.txt").absolutePath),
            products.map { it.receipt.localPath },
            "B 设备同名拉取落 -1 避让位（设备侧路径相同，串号分源）",
        )
    }

    @Test
    fun `same-source re-pull overwrites in place and keeps single cache row`() {
        val dir = pullTempDir()
        val gateway = FakePullGateway(distinctContentScript(AtomicInteger()))
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = gateway, statEntry = pullStat(10), endpointReader = { AdbEndpoint("") }),
            pullTargetDir = dir,
        )
        vm.settle()

        vm.pullEntry(entry("log.txt"))
        vm.settle()
        vm.pullEntry(entry("log.txt"))
        vm.settle()

        assertTrue(
            gateway.commands[1].endsWith("'${java.io.File(dir, "log.txt.part").absolutePath}'"),
            "同源重拉原名落盘——覆盖刷新（实际：${gateway.commands[1]}）",
        )
        assertEquals("payload-02", java.io.File(dir, "log.txt").readText(), "终态被本次结果替换（刷新）")
        val products = vm.state.value.pullProducts
        assertEquals(1, products.size, "同源重拉缓存原位替换单行（防双行同路径）")
        assertEquals(java.io.File(dir, "log.txt").absolutePath, products.single().receipt.localPath)
    }

    @Test
    fun `directory pull same-name collision avoids with -N suffix on directory chain`() {
        val dir = pullTempDir()
        val gateway = FakePullGateway(writePartDirScript)
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = gateway, statEntry = pullStat(4096), endpointReader = { AdbEndpoint("") }),
            pullTargetDir = dir,
        )
        vm.settle()

        vm.pullEntry(entry("Movies", DeviceFileType.DIRECTORY))
        vm.settle()
        vm.enterDirectory(entry("Movies", DeviceFileType.DIRECTORY))
        vm.settle()
        vm.pullEntry(entry("Movies", DeviceFileType.DIRECTORY))
        vm.settle()

        assertTrue(
            gateway.commands[1].endsWith("'${java.io.File(dir, "Movies-1.part").absolutePath}'"),
            "目录链同口径——异源同名目录落 -1 避让位（实际：${gateway.commands[1]}）",
        )
        val products = vm.state.value.pullProducts
        assertEquals(
            listOf(java.io.File(dir, "Movies-1").absolutePath, java.io.File(dir, "Movies").absolutePath),
            products.map { it.receipt.localPath },
            "目录产物两行路径互异",
        )
        assertTrue(java.io.File(dir, "Movies/a.alog").isFile, "首拉目录内容在位未被顶替")
        assertTrue(java.io.File(dir, "Movies-1/a.alog").isFile, "避让位目录产物在位")
    }

    // —— 产物行查看/打开所在文件夹（查看器直投 + 存在性闸 + 目录产物动作门；
    //    查看槽是全局单例，clearForTest 前置清场 + finally 复位防跨用例泄漏） ——

    @Test
    fun `view pull product offers viewer request with product path for present file product`() {
        val dir = pullTempDir()
        LogViewerRequest.clearForTest()
        try {
            val vm = pullVm(FakeDeviceSource(), writePartScript, dir, statSize = 512)
            vm.settle()
            vm.pullEntry(entry("a.txt"))
            vm.settle()

            vm.viewPullProduct(vm.state.value.pullProducts.single())
            val request = LogViewerRequest.pending.value
            assertEquals(
                java.io.File(dir, "a.txt").absolutePath,
                request?.path,
                "查看动作直投查看器请求槽（产物本地路径）",
            )
            assertNull(request?.initialLine, "产物行查看不带行号定位锚")
            assertNull(request?.initialTimestamp, "产物行查看不带时间戳定位锚")
            assertNull(vm.state.value.pullProductError, "在位产物查看零失败回执")
        } finally {
            LogViewerRequest.clearForTest()
        }
    }

    @Test
    fun `missing product view and open folder surface human receipts without execution`() {
        val dir = pullTempDir()
        val openedPaths = mutableListOf<String>()
        LogViewerRequest.clearForTest()
        try {
            val devices = FakeDeviceSource()
            devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
            val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
            val vm = DeviceFileBrowserViewModel(
                devices,
                lister.listDirectory,
                puller = DeviceFilePuller(
                    runner = NoopRunner,
                    gateway = FakePullGateway(writePartScript),
                    statEntry = pullStat(512),
                    endpointReader = { AdbEndpoint("") },
                ),
                folderOpener = { openedPaths += it },
                pullTargetDir = dir,
            )
            vm.settle()
            vm.pullEntry(entry("a.txt"))
            vm.settle()
            val product = vm.state.value.pullProducts.single()

            java.io.File(dir, "a.txt").delete()
            vm.viewPullProduct(product)
            val viewReceipt = vm.state.value.pullProductError
            assertTrue(viewReceipt!!.contains("已不在原位"), "外部删除查看人话回执不静默（实际：$viewReceipt）")
            assertTrue(viewReceipt.contains("查看未执行"), "查看回执点名未执行动作（实际：$viewReceipt）")
            assertNull(LogViewerRequest.pending.value, "缺席产物查看零投递（不静默失效）")

            vm.openPullProductFolder(product)
            val folderReceipt = vm.state.value.pullProductError
            assertTrue(folderReceipt!!.contains("已不在原位"), "外部删除打开所在文件夹人话回执不静默（实际：$folderReceipt）")
            assertTrue(folderReceipt.contains("打开所在文件夹未执行"), "回执点名未执行动作（实际：$folderReceipt）")
            assertEquals(emptyList(), openedPaths, "缺席产物打开所在文件夹零执行")
        } finally {
            LogViewerRequest.clearForTest()
        }
    }

    @Test
    fun `directory product has no view action while open folder stays available`() {
        val dir = pullTempDir()
        val openedPaths = mutableListOf<String>()
        LogViewerRequest.clearForTest()
        try {
            val devices = FakeDeviceSource()
            devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
            val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
            val vm = DeviceFileBrowserViewModel(
                devices,
                lister.listDirectory,
                puller = DeviceFilePuller(
                    runner = NoopRunner,
                    gateway = FakePullGateway(writePartDirScript),
                    statEntry = pullStat(4096),
                    endpointReader = { AdbEndpoint("") },
                ),
                folderOpener = { openedPaths += it },
                pullTargetDir = dir,
            )
            vm.settle()
            vm.pullEntry(entry("Movies", DeviceFileType.DIRECTORY))
            vm.settle()
            val product = vm.state.value.pullProducts.single()

            assertEquals(
                false,
                isViewablePullProduct(product),
                "目录产物无查看钮（by design——目录不是查看器的单文件输入载体，与衔接查表同口径）",
            )
            vm.viewPullProduct(product)
            assertNull(LogViewerRequest.pending.value, "目录产物查看守卫兜底——零投递")
            assertNull(vm.state.value.pullProductError, "守卫 no-op 不落回执")

            vm.openPullProductFolder(product)
            assertEquals(
                listOf(java.io.File(dir, "Movies").absolutePath),
                openedPaths,
                "目录产物打开所在文件夹可用（产物路径透传，父目录语义由 openInFolder 承接）",
            )
            assertNull(vm.state.value.pullProductError, "在位目录产物零失败回执")
        } finally {
            LogViewerRequest.clearForTest()
        }
    }

    // —— 推送动作回归网（fake push gateway 注入真推送内核，零真子进程；
    //    本地源文件一律临时目录，🔴 不碰用户数据根） ——

    /** fake push gateway：记录命令，按脚本回执（脚本可挂起模拟在途推送） */
    private class FakePushGateway(
        private val script: suspend (command: String, handle: AdbPushHandle, onLine: suspend (ShellOutput) -> Unit) -> Int,
    ) : AdbPushGateway {
        val commands = mutableListOf<String>()

        override suspend fun execute(
            command: String,
            onStarted: (AdbPushHandle) -> Unit,
            onLine: suspend (ShellOutput) -> Unit,
        ): Result<Int> {
            commands += command
            val handle = RecordingPushHandle()
            onStarted(handle)
            return Result.success(script(command, handle, onLine))
        }
    }

    /** fake 句柄：killed 标记供取消用例断言「kill 经句柄触达」 */
    private class RecordingPushHandle : AdbPushHandle {
        var killed = false
        override fun kill() {
            killed = true
        }
    }

    /**
     * 推送 fake stat（按调用序路由）：targetExists=true 时每轮预检回「已存在」
     * 成功值（拒绝态→确认态的重推轮次起改回后置校验值）；否则首轮预检回
     * No such file 原文、后置校验轮回 success(size)。
     */
    private class PushStatScript(private val size: Long, private val targetExists: Boolean) {
        var calls = 0
        var existChecks = 0
        val statEntry: suspend (String, String) -> Result<DeviceFileStat> = { _, _ ->
            calls++
            if (targetExists && existChecks < 1) {
                existChecks++
                Result.success(deviceStat(4096))
            } else if (!targetExists && existChecks < 1) {
                existChecks++
                Result.failure(IllegalStateException("stat 读取失败（adb 退出码 1）：stat: '/sdcard/x': No such file or directory"))
            } else {
                Result.success(deviceStat(size))
            }
        }

        private fun deviceStat(size: Long) = DeviceFileStat(
            type = DeviceFileType.FILE,
            sizeBytes = size,
            modifiedEpochSeconds = 1_700_000_000L,
            permissions = "-rw-rw----",
            owner = "root",
            group = "root",
        )
    }

    private fun pushTempDir(): java.io.File = Files.createTempDirectory("vm-push").toFile()

    private fun pushVm(
        devices: FakeDeviceSource,
        script: suspend (String, AdbPushHandle, suspend (ShellOutput) -> Unit) -> Int,
        stat: PushStatScript,
    ): DeviceFileBrowserViewModel {
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        return DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            puller = DeviceFilePuller(
                runner = NoopRunner,
                gateway = FakePullGateway { _, _, _ -> 0 },
                statEntry = pullStat(512),
                endpointReader = { AdbEndpoint("") },
            ),
            pusher = DeviceFilePusher(
                runner = NoopRunner,
                gateway = FakePushGateway(script),
                statEntry = stat.statEntry,
                endpointReader = { AdbEndpoint("") },
            ),
            pullTargetDir = pushTempDir(),
        )
    }

    @Test
    fun `push lands done receipt with verified size and byte-exact command`() {
        val local = java.io.File(pushTempDir(), "build.log").apply { writeBytes(ByteArray(512)) }
        val stat = PushStatScript(size = 512, targetExists = false)
        val devices = FakeDeviceSource()
        val commands = mutableListOf<String>()
        val vm = pushVm(
            devices,
            { command, _, onLine ->
                commands += command
                onLine(ShellOutput("1 file pushed, 0 skipped. 38.4 MB/s (512 bytes in 1.0s)", ShellOutputSource.STDOUT))
                0
            },
            stat,
        )
        vm.settle()

        vm.requestPush(local.absolutePath)
        vm.settle()

        val push = vm.state.value.push
        assertTrue(push is DeviceFilePushUiState.Done, "推送完成态（实际：$push）")
        assertEquals("build.log", push.fileName)
        assertEquals("/sdcard/build.log", push.receipt.devicePath, "设备目标 = 当前目录 + 本地名")
        assertEquals(512L, push.receipt.sizeBytes, "校验通过的大小")
        assertEquals("38.4 MB/s", push.receipt.speedText, "完成回执携带速度")
        assertEquals(
            listOf("adb -s S1 push '${local.absolutePath}' '/sdcard/build.log'"),
            commands,
            "命令 = adb -s <serial> push + 双路径单引号零展开",
        )
        assertEquals(2, stat.calls, "预检 stat（不存在）+ 推送后校验 stat 各一次")
    }

    @Test
    fun `existing target lands confirm state, dismiss is zero action, confirm pushes with overwrite`() {
        val local = java.io.File(pushTempDir(), "build.log").apply { writeBytes(ByteArray(512)) }
        val stat = PushStatScript(size = 512, targetExists = true)
        val commands = mutableListOf<String>()
        val vm = pushVm(FakeDeviceSource(), { command, _, _ -> commands += command; 0 }, stat)
        vm.settle()

        vm.requestPush(local.absolutePath)
        vm.settle()

        val pending = vm.state.value.push
        assertTrue(pending is DeviceFilePushUiState.ConfirmPending, "已存在落确认等待态（实际：$pending）")
        assertEquals("build.log", pending.fileName)
        assertEquals("/sdcard/", pending.deviceDir)
        assertEquals(512L, pending.sizeBytes)
        assertEquals(0, commands.size, "确认前零 push spawn——不静默覆盖")

        // 取消 = 零动作：回 Idle，设备侧未被触碰
        vm.dismissPushConfirm()
        assertEquals(DeviceFilePushUiState.Idle, vm.state.value.push, "取消回 Idle")
        assertEquals(0, commands.size, "取消后零 push spawn")

        // 确认才推：重发起（预检再判存在）→ 确认 → 覆盖推送真正执行
        vm.requestPush(local.absolutePath)
        vm.settle()
        assertTrue(vm.state.value.push is DeviceFilePushUiState.ConfirmPending, "重发起仍落确认等待态")
        assertEquals(0, commands.size, "确认前依然零 push spawn")
        vm.confirmPushOverwrite()
        vm.settle()

        val push = vm.state.value.push
        assertTrue(push is DeviceFilePushUiState.Done, "确认后覆盖推送完成（实际：$push）")
        assertEquals(1, commands.size, "确认后才推一次")
        assertEquals("/sdcard/build.log", push.receipt.devicePath)
    }

    @Test
    fun `push failure surfaces stderr verbatim`() {
        val local = java.io.File(pushTempDir(), "build.log").apply { writeBytes(ByteArray(512)) }
        val vm = pushVm(
            FakeDeviceSource(),
            { _, _, onLine ->
                onLine(ShellOutput("adb: error: failed to copy '${local.absolutePath}' to '/sdcard/build.log': Read-only file system", ShellOutputSource.STDERR))
                1
            },
            PushStatScript(size = 512, targetExists = false),
        )
        vm.settle()

        vm.requestPush(local.absolutePath)
        vm.settle()

        val push = vm.state.value.push
        assertTrue(push is DeviceFilePushUiState.Failed, "失败回执（实际：$push）")
        assertTrue(push.message.contains("Read-only file system"), "失败回执透传设备侧原文（实际：${push.message}）")
        assertTrue(push.message.contains("可能残留部分文件"), "失败回执明示设备侧残留面（实际：${push.message}）")
    }

    @Test
    fun `busy push rejects second request and cancel lands cancelled receipt`() {
        val local = java.io.File(pushTempDir(), "build.log").apply { writeBytes(ByteArray(512)) }
        val stat = PushStatScript(size = 512, targetExists = false)
        lateinit var vm: DeviceFileBrowserViewModel
        val gate = CompletableDeferred<Int>()
        val gateway = FakePushGateway { _, handle, _ ->
            gate.await() // 挂起模拟在途推送（kill 后非零退出）
            if ((handle as RecordingPushHandle).killed) 1 else 0
        }
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        vm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            puller = DeviceFilePuller(
                runner = NoopRunner,
                gateway = FakePullGateway { _, _, _ -> 0 },
                statEntry = pullStat(512),
                endpointReader = { AdbEndpoint("") },
            ),
            pusher = DeviceFilePusher(runner = NoopRunner, gateway = gateway, statEntry = stat.statEntry, endpointReader = { AdbEndpoint("") }),
            pullTargetDir = pushTempDir(),
        )
        vm.settle()

        vm.requestPush(local.absolutePath)
        vm.settle()
        assertTrue(vm.state.value.push is DeviceFilePushUiState.Busy, "在途推送 busy 态（期望大小上屏）")

        val callsInFlight = gateway.commands.size
        vm.requestPush(local.absolutePath)
        assertEquals(callsInFlight, gateway.commands.size, "busy 期间再点推送被拒（单飞行）")

        vm.cancelPush()
        gate.complete(1) // kill 后 adb 非零退出
        vm.settle()
        val push = vm.state.value.push
        assertTrue(push is DeviceFilePushUiState.Failed, "取消后失败回执（实际：$push）")
        assertTrue(push.message.contains("推送已取消"), "取消人话回执（实际：${push.message}）")
    }

    @Test
    fun `requestPush rejects missing local file without spawn`() {
        val stat = PushStatScript(size = 512, targetExists = false)
        val vm = pushVm(FakeDeviceSource(), { _, _, _ -> 0 }, stat)
        vm.settle()

        vm.requestPush("/nonexistent/absent.log")
        vm.settle()

        val push = vm.state.value.push
        assertTrue(push is DeviceFilePushUiState.Failed, "本地源缺席人话拒绝（实际：$push）")
        assertTrue(push.message.contains("本地文件不存在"), "拒绝回执（实际：${push.message}）")
        assertEquals(0, stat.calls, "本地侧拒绝先于设备侧探测（零 stat 调用）")
    }

    @Test
    fun `push without target surfaces blocked receipt and no spawn`() {
        val stat = PushStatScript(size = 512, targetExists = false)
        val local = java.io.File(pushTempDir(), "build.log").apply { writeBytes(ByteArray(512)) }
        val devices = FakeDeviceSource() // None：无设备
        val lister = FakeBrowser { _, _ -> Result.success(okEntries()) }
        val vm = DeviceFileBrowserViewModel(
            devices,
            lister.listDirectory,
            pusher = DeviceFilePusher(
                runner = NoopRunner,
                gateway = FakePushGateway { _, _, _ -> 0 },
                statEntry = stat.statEntry,
                endpointReader = { AdbEndpoint("") },
            ),
            pullTargetDir = pushTempDir(),
        )
        vm.settle()

        vm.requestPush(local.absolutePath)
        vm.settle()
        assertEquals(DeviceFilePushUiState.Idle, vm.state.value.push, "同步拒绝不进推送状态机")
        assertEquals(0, stat.calls, "目标不可用零 spawn")
        assertTrue(vm.state.value.error!!.contains("未检测到设备"), "拒绝回执走目录读数错误槽（实际：${vm.state.value.error}）")
    }

    // —— 图片预览动作门 + 魔数嗅探纯函数（复用 base64 提取器四魔数表） ——

    @Test
    fun `image extension gate admits only image suffixes for entry preview action`() {
        assertTrue(isImageFileName("photo.png"))
        assertTrue(isImageFileName("IMG_001.JPG"), "大小写不敏感")
        assertTrue(isImageFileName("anim.Gif"))
        assertTrue(isImageFileName("frame.webp"))
        assertTrue(isImageFileName("shot.jpeg"))
        assertEquals(false, isImageFileName("build.log"), "日志文件不出预览动作")
        assertEquals(false, isImageFileName("car.alog"), "加密日志不出预览动作")
        assertEquals(false, isImageFileName("无后缀文件名"), "无后缀不出预览动作")
    }

    @Test
    fun `local image header sniffing reuses four magic-number tables`() {
        val dir = pushTempDir()

        val png = java.io.File(dir, "shot.png").apply {
            writeBytes(byteArrayOf(0x89.toByte()) + "PNG\r\n\u001A\n".toByteArray() + ByteArray(32))
        }
        assertEquals(Base64ImageFormat.PNG, sniffLocalImageHeader(png), "PNG 魔数（0x89 + PNG 签名）")

        val jpeg = java.io.File(dir, "shot.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(16))
        }
        assertEquals(Base64ImageFormat.JPEG, sniffLocalImageHeader(jpeg), "JPEG 魔数（FF D8 FF）")

        val gif = java.io.File(dir, "anim.gif").apply { writeBytes("GIF89a".toByteArray() + ByteArray(16)) }
        assertEquals(Base64ImageFormat.GIF, sniffLocalImageHeader(gif), "GIF 魔数（GIF89a/GIF87a）")

        val webp = java.io.File(dir, "frame.webp").apply {
            writeBytes("RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(8))
        }
        assertEquals(Base64ImageFormat.WEBP, sniffLocalImageHeader(webp), "WebP 魔数（RIFF + WEBP）")

        val text = java.io.File(dir, "build.log").apply { writeBytes("2026-10-11 14:23:00 plain log line".toByteArray()) }
        assertNull(sniffLocalImageHeader(text), "非图片文件不出预览面（null）")

        val empty = java.io.File(dir, "empty.log").apply { writeBytes(ByteArray(0)) }
        assertNull(sniffLocalImageHeader(empty), "空文件不出预览面（null）")

        // 后缀会撒谎：图片后缀的文本文件魔数嗅探为 null——预览面由魔数真值决定
        val liar = java.io.File(dir, "fake.png").apply { writeBytes("not an image".toByteArray()) }
        assertNull(sniffLocalImageHeader(liar), "伪图片后缀被魔数嗅探拒绝")
    }
}
