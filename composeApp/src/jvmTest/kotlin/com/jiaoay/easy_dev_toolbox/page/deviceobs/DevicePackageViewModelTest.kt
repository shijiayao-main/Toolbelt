package com.jiaoay.easy_dev_toolbox.page.deviceobs

import com.jiaoay.toolbelt.device.ShellOutput
import com.jiaoay.toolbelt.device.ShellOutputSource
import com.jiaoay.toolbelt.device.ShellResult
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import com.jiaoay.toolbelt.device.AdbProcessRunner
import com.jiaoay.toolbelt.device.AdbPullGateway
import com.jiaoay.toolbelt.device.AdbPullHandle
import com.jiaoay.toolbelt.device.DeviceApkExporter
import com.jiaoay.toolbelt.device.DeviceFilePuller
import com.jiaoay.toolbelt.device.DeviceFileStat
import com.jiaoay.toolbelt.device.DeviceFileType
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DevicePackageDetails
import com.jiaoay.toolbelt.device.DevicePackageEntry
import com.jiaoay.toolbelt.device.DevicePackageToggler
import com.jiaoay.toolbelt.device.DevicePerfSample
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.device.MemoryStats
import com.jiaoay.toolbelt.device.PerfField
import com.jiaoay.toolbelt.device.PerfSamplerState
import com.jiaoay.toolbelt.device.PerfSessionState
import com.jiaoay.toolbelt.device.ProcessEntry
import com.jiaoay.toolbelt.adb.AdbEndpoint

/**
 * 应用面板 VM 回归网（fake 设备源 + fake 浏览内核注入，零真子进程）：
 * - 装载语义：目标就绪自动装载一次、系统标记集合差透传、失败回执人话原样、
 *   目标不可用三态同步拒绝零 spawn；
 * - 过滤语义：查询词包含匹配 + 系统过滤三态是纯投影（零内核请求），未装载列表
 *   过滤恒空；
 * - 详情懒加载：选中才拉取、命中缓存零二次 spawn、同包再点收起、在途详情被
 *   新选中作废（交错回执不上卡）；
 * - 缓存与串号语义：详情缓存按设备串号会话级生效，换设备清空（同包重拉）；
 *   串号实变自动重载、目标从有到无清读数回执失效原因；
 * - 动作区（导出/禁启）：禁启确认对话接线——确认才执行、取消零动作零 spawn
 *   （fake 网关命令记录断言）；禁启成功回执落地 + 详情刷新 + 禁用态重探；导出
 *   busy 进度推进 + 完成回执逐 split；目标不可用导出同步拒绝零 spawn；换选中
 *   复位动作态；
 * - 运行态融合（fake 采样 StateFlow 注入——VM 只读消费零 spawn）：匹配矩阵
 *   （精确/子进程冒号剥离/native 与大小写差异不猜）、聚合投影（CPU 与 RSS
 *   双维求和）、排序档（按 CPU/按内存读数降序无读数沉底、按包名原序零回归）、
 *   未采样零投影、串号不匹配零投影、会话非活跃/进程域失败零投影、采样引导
 *   hint 两态（零投影显示/有投影隐藏）。
 * - 快照对比（会话级内存快照不落盘）：diff 三分类矩阵（新增/移除/版本变更/
 *   混合/无差异/空快照/空列表/uid 与系统标记差异不计差异）+ 稳定排序；保存
 *   定格装载面、未装载拒绝、新快照顶替并作废回执、刷新后对比覆盖语义、
 *   跨串号确认（取消零回执、确认才对比）、快照跨串号保留。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicePackageViewModelTest {

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

    /**
     * fake 浏览内核：记录调用序（断言锚），列表按路由表回执，详情按包名路由到
     * 可挂起脚本（在途交错用例经 CompletableDeferred 卡住在途请求）。
     */
    private class FakePackageKernel(
        private val listRespond: suspend (serial: String) -> Result<List<DevicePackageEntry>>,
        private val detailsScript: suspend (packageName: String, serial: String) -> Result<DevicePackageDetails>,
    ) {
        val listCalls = mutableListOf<String>()
        val detailsCalls = mutableListOf<Pair<String, String>>()

        val listPackages: suspend (String) -> Result<List<DevicePackageEntry>> = { serial ->
            listCalls += serial
            listRespond(serial)
        }

        val packageDetails: suspend (String, String) -> Result<DevicePackageDetails> = { packageName, serial ->
            detailsCalls += packageName to serial
            detailsScript(packageName, serial)
        }
    }

    private fun entry(name: String, system: Boolean, versionCode: Long = 100, uid: Long = 10000) =
        DevicePackageEntry(packageName = name, versionCode = versionCode, uid = uid, isSystem = system)

    private fun okPackages() = listOf(
        entry("com.demo.media", system = true, versionCode = 60400000, uid = 10061),
        entry("com.example.userapp", system = false, versionCode = 7, uid = 10234),
        entry("com.android.cts.priv.ctsshim", system = true, versionCode = 31, uid = 10022),
    )

    private fun detailsOf(packageName: String) = DevicePackageDetails(
        packageName = packageName,
        versionCode = 60400000,
        versionName = "V6.4.0_Release",
        uid = 10061,
        codePath = "/system/app/DemoDvrApp",
        firstInstallTime = "2025-01-01 08:00:00",
        lastUpdateTime = "2025-01-01 08:00:00",
        requestedPermissionsCount = 4,
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
    private fun DevicePackageViewModel.settle() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `single device auto loads package list once with system flags`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()

        assertEquals(listOf("S1"), kernel.listCalls, "目标就绪即自动装载一次")
        val state = vm.state.value
        assertEquals(listOf("com.demo.media", "com.example.userapp", "com.android.cts.priv.ctsshim"), state.entries?.map { it.packageName })
        assertEquals(true, state.entries?.first { it.packageName == "com.demo.media" }?.isSystem, "系统标记由内核集合差透传")
        assertEquals(state.entries, state.filtered, "无过滤时投影 = 全量")
        assertNull(state.error)
    }

    @Test
    fun `list failure surfaces human receipt and retry respawns`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = {
                Result.failure(IllegalStateException("系统包列举失败（adb 退出码 1）：Error: Unknown option: -x"))
            },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()
        assertTrue(vm.state.value.error!!.contains("Unknown option"), "失败回执人话原样透传（实际：${vm.state.value.error}）")
        assertNull(vm.state.value.entries, "失败不落半截读数")

        vm.refresh()
        vm.settle()
        assertEquals(2, kernel.listCalls.size, "手动重试再次发起列举")
    }

    @Test
    fun `refresh without target surfaces blocked receipt and no spawn`() {
        val devices = FakeDeviceSource()
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )

        // 无设备
        val noneVm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails).also { it.settle() }
        noneVm.refresh()
        assertTrue(noneVm.state.value.error!!.contains("未检测到设备"), "无设备回执人话（实际：${noneVm.state.value.error}）")

        // 检测失败（原文透传）
        devices.detectErrorFlow.value = "设备检测失败：adb not found"
        val errVm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails).also { it.settle() }
        errVm.refresh()
        assertTrue(errVm.state.value.error!!.contains("adb not found"), "检测失败回执含原因原文")

        // 多设备未选定
        devices.detectErrorFlow.value = null
        devices.deviceStateFlow.value = DeviceState.Multi(listOf(DeviceInfo("S1", "Demo-1"), DeviceInfo("S2", "M1")))
        val multiVm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails).also { it.settle() }
        multiVm.refresh()
        assertTrue(multiVm.state.value.error!!.contains("多设备连接"), "多设备未选回执指引设备栏（实际：${multiVm.state.value.error}）")

        assertEquals(0, kernel.listCalls.size, "三态目标不可用均零 spawn")
    }

    @Test
    fun `query and system filter project without extra kernel calls`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()

        // 前缀输入（厂商前缀找包高频动作）+ 大小写不敏感
        vm.setQuery("com.demo")
        assertEquals(listOf("com.demo.media"), vm.state.value.filtered.map { it.packageName }, "包含匹配命中前缀输入")

        vm.setQuery("DEMO.MEDIA")
        assertEquals(listOf("com.demo.media"), vm.state.value.filtered.map { it.packageName }, "大小写不敏感")

        // 系统过滤三态：集合差现成数据面
        vm.setQuery("")
        vm.setSystemFilter(DevicePackageSystemFilter.SYSTEM_ONLY)
        assertEquals(
            listOf("com.demo.media", "com.android.cts.priv.ctsshim"),
            vm.state.value.filtered.map { it.packageName },
            "仅系统 = 系统标记成员",
        )
        vm.setSystemFilter(DevicePackageSystemFilter.NON_SYSTEM)
        assertEquals(listOf("com.example.userapp"), vm.state.value.filtered.map { it.packageName }, "仅非系统 = 集合差")
        vm.setSystemFilter(DevicePackageSystemFilter.ALL)
        assertEquals(3, vm.state.value.filtered.size, "全部 = 全量")

        // 无命中形态
        vm.setQuery("com.absent.vendor")
        assertEquals(0, vm.state.value.filtered.size, "无命中投影为空")

        assertEquals(1, kernel.listCalls.size, "过滤是纯投影零内核请求")
    }

    @Test
    fun `filterPackageEntries handles unloaded list and query trimming`() {
        assertEquals(emptyList(), filterPackageEntries(null, "com.android", DevicePackageSystemFilter.ALL), "未装载列表过滤恒空")
        val all = okPackages()
        assertEquals(3, filterPackageEntries(all, "  ", DevicePackageSystemFilter.ALL).size, "查询词空白视同不过滤")
        assertEquals(
            listOf("com.android.cts.priv.ctsshim"),
            filterPackageEntries(all, " cts ", DevicePackageSystemFilter.ALL).map { it.packageName },
            "查询词首尾空白剥除",
        )
    }

    // ── 运行态融合（fake 采样 StateFlow 注入，VM 只读消费零 spawn） ──

    /** 单条 top 进程 fixture（列面与 PerfParsers.parseTopProcesses 产物一致） */
    private fun processOf(pid: Int, cpuPercent: Float, name: String, rssKb: Long = 1024L) = ProcessEntry(
        pid = pid,
        user = "u0_a55",
        cpuPercent = cpuPercent,
        memPercent = 1f,
        rssKb = rssKb,
        name = name,
    )

    /** 单拍采样 fixture：仅进程域按入参落值，其余域合法占位（不参与本断言面） */
    private fun perfSampleOf(processes: PerfField<List<ProcessEntry>>) = DevicePerfSample(
        timestampMs = 1L,
        cpuPercent = PerfField.ok(10f),
        memory = PerfField.ok(MemoryStats(totalKb = 1024L, availableKb = 512L, freeKb = 0L)),
        temperatureC = PerfField.ok(35f),
        batteryPercent = PerfField.ok(80),
        processes = processes,
        fps = PerfField.ok(60f),
    )

    /** 活跃会话 + 最近一拍的采样器快照（keyed 形态——与真实采样环发布面同构） */
    private fun activePerf(serial: String, sample: DevicePerfSample) = PerfSamplerState(
        sessions = mapOf(serial to PerfSessionState.Active(serial)),
        latestBySerial = mapOf(serial to sample),
    )

    /** 融合断言面包清单（列举序刻意与 CPU 序不同——排序档才有可观测差） */
    private fun fusionPackages() = listOf(
        entry("com.android.cts.priv.ctsshim", system = true),
        entry("com.example.userapp", system = false),
        entry("com.demo.media", system = true),
    )

    @Test
    fun `process matching matrix covers exact, child strip, native and case forms`() {
        assertTrue(processMatchesPackage("com.foo", "com.foo"), "进程名即包名：精确匹配")
        assertTrue(processMatchesPackage("com.foo:service", "com.foo"), "子进程冒号后缀剥离匹配")
        assertFalse(processMatchesPackage("surfaceflinger", "com.foo"), "native 服务名不匹配（不猜）")
        assertFalse(processMatchesPackage("[kworker/0:1H-hgsl-wq]", "com.foo"), "内核线程名不匹配")
        assertFalse(processMatchesPackage("Com.Foo", "com.foo"), "大小写差异保守不猜")
        assertFalse(processMatchesPackage("com.foo.sub", "com.foo"), "前缀近似不猜（仅精确/子进程两形态成立）")
        assertFalse(processMatchesPackage(":service", "com.foo"), "冒号在首位的私有进程名无名包可依")
        assertFalse(processMatchesPackage("", "com.foo"), "空进程名不匹配")
    }

    @Test
    fun `projectPackageRuntime aggregates matched processes and drops unmatched`() {
        val packages = listOf(
            entry("com.foo", system = false),
            entry("com.bar", system = false),
            entry("com.unused", system = false),
        )
        val runtime = projectPackageRuntime(
            processes = listOf(
                processOf(1, 20f, "com.foo", rssKb = 300_000L),
                processOf(2, 5f, "com.foo:svc", rssKb = 40_000L),
                processOf(3, 30f, "surfaceflinger", rssKb = 900_000L),
                processOf(4, 10f, "com.bar", rssKb = 80_000L),
                processOf(5, 1f, "Com.foo", rssKb = 500_000L),
            ),
            packages = packages,
        )
        assertEquals(
            mapOf(
                "com.foo" to DevicePackageRuntime(cpuPercent = 25f, memKb = 340_000L, processCount = 2),
                "com.bar" to DevicePackageRuntime(cpuPercent = 10f, memKb = 80_000L, processCount = 1),
            ),
            runtime,
            "主+子进程求和聚合（CPU 与 RSS 双维）；native/大小写差异零投影；无进程的包不出键",
        )
        assertEquals(emptyMap(), projectPackageRuntime(emptyList(), packages), "零进程零投影")
        assertEquals(emptyMap(), projectPackageRuntime(listOf(processOf(1, 1f, "com.foo")), emptyList()), "零包零投影")
    }

    @Test
    fun `sortPackageEntries by name passthrough and by cpu sinks unread`() {
        val entries = fusionPackages()
        val runtime = mapOf("com.example.userapp" to DevicePackageRuntime(cpuPercent = 10f, memKb = 1024L, processCount = 1))
        assertEquals(entries, sortPackageEntries(entries, runtime, DevicePackageSortMode.BY_NAME), "按包名 = 原序直通")
        assertEquals(
            listOf("com.example.userapp", "com.android.cts.priv.ctsshim", "com.demo.media"),
            sortPackageEntries(entries, runtime, DevicePackageSortMode.BY_CPU).map { it.packageName },
            "按 CPU = 读数降序，无读数沉底且保原相对序",
        )
        assertEquals(
            entries.map { it.packageName },
            sortPackageEntries(entries, emptyMap(), DevicePackageSortMode.BY_CPU).map { it.packageName },
            "未采样时按 CPU 档与原序等价（零回归）",
        )
    }

    @Test
    fun `sortPackageEntries by memory orders by rss sum and sinks unread`() {
        val entries = fusionPackages()
        // RSS 序与 CPU 序刻意倒挂（xmedia CPU 高但 RSS 低）——内存档才有可观测差
        val runtime = mapOf(
            "com.demo.media" to DevicePackageRuntime(cpuPercent = 25f, memKb = 500_000L, processCount = 2),
            "com.example.userapp" to DevicePackageRuntime(cpuPercent = 10f, memKb = 2_000_000L, processCount = 1),
        )
        assertEquals(
            listOf("com.example.userapp", "com.demo.media", "com.android.cts.priv.ctsshim"),
            sortPackageEntries(entries, runtime, DevicePackageSortMode.BY_MEMORY).map { it.packageName },
            "按内存 = RSS 聚合降序，无读数沉底且保原相对序",
        )
        assertEquals(
            listOf("com.demo.media", "com.example.userapp", "com.android.cts.priv.ctsshim"),
            sortPackageEntries(entries, runtime, DevicePackageSortMode.BY_CPU).map { it.packageName },
            "同投影下 CPU 档仍按 CPU 读数降序（两档判据互不混洗）",
        )
        assertEquals(
            entries.map { it.packageName },
            sortPackageEntries(entries, emptyMap(), DevicePackageSortMode.BY_MEMORY).map { it.packageName },
            "未采样时按内存档与原序等价（零投影条件同 CPU）",
        )
    }

    @Test
    fun `runtimeBadgeText carries cpu main and memory secondary data`() {
        assertEquals(
            "CPU 25.0% · 内存 1.6M ×2",
            runtimeBadgeText(DevicePackageRuntime(cpuPercent = 25f, memKb = 1638L, processCount = 2)),
            "CPU 主数据 + 内存副数据（rssHumanText 同口径）+ 多进程旁证",
        )
        assertEquals(
            "CPU 3.2% · 内存 512K",
            runtimeBadgeText(DevicePackageRuntime(cpuPercent = 3.2f, memKb = 512L, processCount = 1)),
            "单进程无 ×N 后缀",
        )
        assertEquals(
            "CPU 0.1% · 内存 3.0G",
            runtimeBadgeText(DevicePackageRuntime(cpuPercent = 0.1f, memKb = 3L shl 20, processCount = 1)),
            "G 档缩写",
        )
    }

    @Test
    fun `sampler sample projects runtime badges through VM and cpu sort reorders`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(fusionPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val perf = MutableStateFlow(PerfSamplerState())
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails, perfState = perf)
        vm.settle()

        // 未采样零投影 + 默认包名序零回归（既有面板形态不因融合接线改变）
        assertEquals(emptyMap(), vm.state.value.runtimeByPackage, "采样器无数据零投影（负断言）")
        assertEquals(
            listOf("com.android.cts.priv.ctsshim", "com.example.userapp", "com.demo.media"),
            vm.state.value.filtered.map { it.packageName },
            "默认按包名 = 列举原序",
        )

        // 一拍到达：主+子进程聚合投影（CPU 与 RSS 双维），native 进程零投影
        perf.value = activePerf(
            "S1",
            perfSampleOf(
                PerfField.ok(
                    listOf(
                        processOf(1, 20f, "com.demo.media", rssKb = 1024L),
                        processOf(2, 5f, "com.demo.media:control", rssKb = 1024L),
                        processOf(3, 30f, "surfaceflinger", rssKb = 900_000L),
                        processOf(4, 10f, "com.example.userapp", rssKb = 4096L),
                    ),
                ),
            ),
        )
        vm.settle()
        assertEquals(
            mapOf(
                "com.demo.media" to DevicePackageRuntime(cpuPercent = 25f, memKb = 2048L, processCount = 2),
                "com.example.userapp" to DevicePackageRuntime(cpuPercent = 10f, memKb = 4096L, processCount = 1),
            ),
            vm.state.value.runtimeByPackage,
            "投影 = 匹配进程聚合面（native 未匹配不出现）",
        )
        assertEquals(
            listOf("com.android.cts.priv.ctsshim", "com.example.userapp", "com.demo.media"),
            vm.state.value.filtered.map { it.packageName },
            "投影到达不改变默认序（徽标是行内信息非排序输入）",
        )

        // 排序档切换：按 CPU 读数降序、无读数沉底保原序
        vm.setSortMode(DevicePackageSortMode.BY_CPU)
        assertEquals(
            listOf("com.demo.media", "com.example.userapp", "com.android.cts.priv.ctsshim"),
            vm.state.value.filtered.map { it.packageName },
            "按 CPU = 运行态读数降序，无读数包沉底",
        )

        // 按内存档：RSS 聚合降序（userapp RSS 大于 xmedia 两进程之和——档间序差可观测）
        vm.setSortMode(DevicePackageSortMode.BY_MEMORY)
        assertEquals(
            listOf("com.example.userapp", "com.demo.media", "com.android.cts.priv.ctsshim"),
            vm.state.value.filtered.map { it.packageName },
            "按内存 = RSS 聚合降序，无读数包沉底",
        )
        vm.setSortMode(DevicePackageSortMode.BY_NAME)
        assertEquals(
            listOf("com.android.cts.priv.ctsshim", "com.example.userapp", "com.demo.media"),
            vm.state.value.filtered.map { it.packageName },
            "回包名序零回归",
        )
        assertEquals(1, kernel.listCalls.size, "投影与排序是纯投影零内核请求")
    }

    @Test
    fun `serial mismatched sample projects nothing until serial matches`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(fusionPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val perf = MutableStateFlow(PerfSamplerState())
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails, perfState = perf)
        vm.settle()

        // 采样数据挂在另一台串号上：零投影（设备切换瞬间不显示错配数据）
        val sample = perfSampleOf(
            PerfField.ok(listOf(processOf(1, 20f, "com.demo.media"), processOf(2, 10f, "com.example.userapp"))),
        )
        perf.value = activePerf("S2", sample)
        vm.settle()
        assertEquals(emptyMap(), vm.state.value.runtimeByPackage, "串号不匹配零投影（防错配）")

        // 同一拍数据挂到当前串号：投影成立
        perf.value = activePerf("S1", sample)
        vm.settle()
        assertEquals(
            setOf("com.demo.media", "com.example.userapp"),
            vm.state.value.runtimeByPackage.keys,
            "串号对上后投影成立",
        )
    }

    @Test
    fun `sampling hint visible without projection and hidden once projection lands`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(fusionPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val perf = MutableStateFlow(PerfSamplerState())
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails, perfState = perf)
        vm.settle()

        fun hintOfVm(): String? = samplingHintText(
            entriesLoaded = vm.state.value.entries != null,
            runtimeByPackage = vm.state.value.runtimeByPackage,
        )

        // 未采样：列表已装载 + 零投影 → 显示
        assertTrue(hintOfVm() != null, "未采样显示采样引导 hint")
        assertEquals(
            null,
            samplingHintText(entriesLoaded = false, runtimeByPackage = emptyMap()),
            "列表未装载不显示（无可采目标另有目标横幅，不叠加）",
        )

        val sample = perfSampleOf(PerfField.ok(listOf(processOf(1, 20f, "com.demo.media"))))

        // 串号不匹配：零投影视同无采样 → 显示
        perf.value = activePerf("S2", sample)
        vm.settle()
        assertTrue(hintOfVm() != null, "串号不匹配零投影视同无采样 → 显示")

        // 同串号活跃会话有投影 → 隐藏
        perf.value = activePerf("S1", sample)
        vm.settle()
        assertEquals(null, hintOfVm(), "有活跃投影隐藏 hint")

        // 会话收束：留存读数零投影 → 显示
        perf.value = PerfSamplerState(
            sessions = mapOf("S1" to PerfSessionState.Ended("目标设备已断开")),
            latestBySerial = mapOf("S1" to sample),
        )
        vm.settle()
        assertTrue(hintOfVm() != null, "会话收束零投影 → 显示")
    }

    @Test
    fun `inactive session or failed process field projects nothing`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(fusionPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val perf = MutableStateFlow(PerfSamplerState())
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails, perfState = perf)
        vm.settle()
        val sample = perfSampleOf(PerfField.ok(listOf(processOf(1, 20f, "com.demo.media"))))

        // 有最近一拍但无会话（未采过）：零投影
        perf.value = PerfSamplerState(latestBySerial = mapOf("S1" to sample))
        vm.settle()
        assertEquals(emptyMap(), vm.state.value.runtimeByPackage, "无活跃会话零投影")

        // 会话已收束：留存读数不冒充实时运行态
        perf.value = PerfSamplerState(
            sessions = mapOf("S1" to PerfSessionState.Ended("目标设备已断开")),
            latestBySerial = mapOf("S1" to sample),
        )
        vm.settle()
        assertEquals(emptyMap(), vm.state.value.runtimeByPackage, "会话收束零投影（留存读数是历史面）")

        // 活跃会话但进程域失败（PerfField.error）：按无数据处理
        perf.value = activePerf("S1", perfSampleOf(PerfField.fail("未见 top 表头行（PID/USER/%CPU）")))
        vm.settle()
        assertEquals(emptyMap(), vm.state.value.runtimeByPackage, "进程域失败零投影不猜")

        // 目标失效后列表清空：投影随列表归零
        perf.value = activePerf("S1", sample)
        vm.settle()
        assertEquals(setOf("com.demo.media"), vm.state.value.runtimeByPackage.keys, "前置：活跃会话投影成立")
        devices.deviceStateFlow.value = DeviceState.None
        vm.settle()
        assertEquals(emptyMap(), vm.state.value.runtimeByPackage, "目标失效列表清空后零投影")
    }

    @Test
    fun `detail lazy load on select, cache reuse on reselect, toggle collapse`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()
        assertEquals(0, kernel.detailsCalls.size, "未选中零详情 spawn（懒加载缝）")

        val target = vm.state.value.entries!!.first()
        vm.selectPackage(target)
        vm.settle()
        assertEquals(listOf("com.demo.media" to "S1"), kernel.detailsCalls, "选中后才拉取详情")
        val ready = vm.state.value.details
        assertTrue(ready is DevicePackageDetailsUiState.Ready, "详情完成态（实际：$ready）")
        assertEquals("V6.4.0_Release", ready.details.versionName)
        assertEquals("com.demo.media", vm.state.value.selected)

        // 同包再点收起（选中清空、详情回 Idle、缓存保留）
        vm.selectPackage(target)
        assertEquals(null, vm.state.value.selected, "再点同包收起展开")
        assertEquals(DevicePackageDetailsUiState.Idle, vm.state.value.details)

        // 重展开命中缓存零二次 spawn
        vm.selectPackage(target)
        vm.settle()
        assertEquals(1, kernel.detailsCalls.size, "同设备重复展开命中会话级缓存")
        assertTrue(vm.state.value.details is DevicePackageDetailsUiState.Ready)
    }

    @Test
    fun `in-flight detail superseded by newer selection lands only latest`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val gates = mutableMapOf<String, CompletableDeferred<Result<DevicePackageDetails>>>()
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ ->
                gates.getOrPut(name) { CompletableDeferred() }.await()
            },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()
        val entries = vm.state.value.entries!!
        val xmedia = entries.first { it.packageName == "com.demo.media" }
        val userapp = entries.first { it.packageName == "com.example.userapp" }

        // 两个选中背靠背发出：首个在途被次新作废
        vm.selectPackage(xmedia)
        vm.selectPackage(userapp)
        assertTrue(vm.state.value.details is DevicePackageDetailsUiState.Busy, "在途详情 busy 态")
        // 推进到两支在途协程都挂起在各自己的门上（fake 门此刻才登记）
        dispatcher.scheduler.advanceUntilIdle()

        gates.getValue("com.demo.media").complete(Result.success(detailsOf("com.demo.media")))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            vm.state.value.details is DevicePackageDetailsUiState.Busy,
            "被作废的交错回执不上卡（实际：${vm.state.value.details}）",
        )

        gates.getValue("com.example.userapp").complete(Result.success(detailsOf("com.example.userapp")))
        dispatcher.scheduler.advanceUntilIdle()
        val ready = vm.state.value.details
        assertTrue(ready is DevicePackageDetailsUiState.Ready, "最新选中的回执落地（实际：$ready）")
        assertEquals("com.example.userapp", ready.details.packageName, "落地的是次新选中不是首个")
    }

    @Test
    fun `serial change clears detail cache and reloads list`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()
        val target = vm.state.value.entries!!.first()
        vm.selectPackage(target)
        vm.settle()
        assertEquals(1, kernel.detailsCalls.size, "S1 上首读详情一次")
        assertEquals(1, kernel.listCalls.size)

        // 单台 → 多台并选另一台：串号实变（列表重载 + 缓存清空 + 选中复位）
        devices.deviceStateFlow.value = DeviceState.Multi(listOf(DeviceInfo("S1", "Demo-1"), DeviceInfo("S2", "M1")))
        devices.selectedSerialFlow.value = "S2"
        vm.settle()
        assertEquals(listOf("S1", "S2"), kernel.listCalls, "串号实变自动重载")
        assertEquals(null, vm.state.value.selected, "换设备选中态复位")
        assertEquals(DevicePackageDetailsUiState.Idle, vm.state.value.details)

        // 同包在 S2 上重选：缓存已清空 → 重新拉取（同包不同设备不可互推）
        vm.selectPackage(vm.state.value.entries!!.first())
        vm.settle()
        assertEquals(listOf("com.demo.media" to "S1", "com.demo.media" to "S2"), kernel.detailsCalls, "换设备后同包详情重拉")

        // 串号未变零重载（StateFlow 同值不重发）
        val listCallsAfter = kernel.listCalls.size
        devices.selectedSerialFlow.value = "S2"
        vm.settle()
        assertEquals(listCallsAfter, kernel.listCalls.size, "串号未变零重载")
    }

    @Test
    fun `target becoming unavailable invalidates entries and device back online reloads`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()
        assertTrue(vm.state.value.entries != null, "前置：已装载")

        devices.deviceStateFlow.value = DeviceState.None
        vm.settle()
        assertNull(vm.state.value.entries, "目标失效清空陈旧读数（不冒充可读数）")
        assertTrue(vm.state.value.error!!.contains("目标设备已不可用"), "失效回执人话（实际：${vm.state.value.error}）")

        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        vm.settle()
        assertTrue(vm.state.value.entries != null, "回线后重载")
        assertEquals(2, kernel.listCalls.size)
    }

    @Test
    fun `select without target lands failed receipt without spawn`() {
        val devices = FakeDeviceSource() // None：无设备
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()

        vm.selectPackage(entry("com.demo.media", system = true))
        vm.settle()
        assertEquals(0, kernel.detailsCalls.size, "目标不可用零详情 spawn")
        val details = vm.state.value.details
        assertTrue(details is DevicePackageDetailsUiState.Failed, "同步拒绝落失败回执（实际：$details）")
        assertTrue(details.message.contains("未检测到设备"), "拒绝回执人话（实际：${details.message}）")
    }

    // ── 动作区（导出 APK / 禁启）：真内核 + fake 网关驱动 ──

    /** pm/dumpsys 通道 fake（禁启/导出共用）：记录命令全文（零 spawn 断言锚），按脚本回执 */
    private class FakeDetectGateway(private val respond: suspend (String) -> ShellResult) : AdbProcessRunner {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return Result.success(respond(command))
        }
    }

    /** 拉取通道 fake：模拟 adb 写 64 字节 .part 并吐摘要行（导出走真拉取内核管线） */
    private class FakePullGateway : AdbPullGateway {
        val commands = mutableListOf<String>()
        override suspend fun execute(
            command: String,
            onStarted: (AdbPullHandle) -> Unit,
            onLine: suspend (ShellOutput) -> Unit,
        ): Result<Int> {
            commands += command
            val part = java.io.File(command.substringAfterLast(" '").removeSuffix("'"))
            part.writeBytes(ByteArray(64))
            onLine(ShellOutput("1 file pulled, 0 skipped. (64 bytes in 1.0s)", ShellOutputSource.STDOUT))
            onStarted(AdbPullHandle { })
            return Result.success(0)
        }
    }

    /** 拉取/导出前置 stat fake（64B 普通文件——与 FakePullGateway 写盘字节数一致） */
    private val fakeStat: suspend (String, String) -> Result<DeviceFileStat> = { _, _ ->
        Result.success(
            DeviceFileStat(
                type = DeviceFileType.FILE,
                sizeBytes = 64L,
                modifiedEpochSeconds = 1_700_000_000L,
                permissions = "-rw-r--r--",
                owner = "root",
                group = "root",
            ),
        )
    }

    /** 多 split pm path fixture（base + arm64 split） */
    private val pmPathFixture = """
        package:/data/app/~~aXb/com.demo.media-Q1w2e3/base.apk
        package:/data/app/~~aXb/com.demo.media-Q1w2e3/split_config.arm64_v8a.apk
    """.trimIndent()

    /**
     * 动作区 VM 装配：真导出/禁启内核 + fake 通道（导出目标目录 = 临时目录，🔴 单测
     * 不碰用户数据根）。
     */
    private fun vmWithActions(
        devices: DeviceStateSource,
        kernel: FakePackageKernel,
        detect: AdbProcessRunner,
        pull: AdbPullGateway = FakePullGateway(),
        exportTargetDir: java.io.File = Files.createTempDirectory("pkg-vm-export").toFile(),
    ) = DevicePackageViewModel(
        devices,
        kernel.listPackages,
        kernel.packageDetails,
        exporter = DeviceApkExporter(
            runner = detect,
            puller = DeviceFilePuller(runner = NoopRunner, gateway = pull, statEntry = fakeStat, endpointReader = { AdbEndpoint("") }),
            statEntry = fakeStat,
            endpointReader = { AdbEndpoint("") },
        ),
        toggler = DevicePackageToggler(runner = detect, endpointReader = { AdbEndpoint("") }),
        exportTargetDir = exportTargetDir,
    )

    @Test
    fun `disable requires confirmation, cancel is zero action zero spawn`() {
        val devices = FakeDeviceSource().apply { deviceStateFlow.value = DeviceState.Single("S1", "Demo-1") }
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val detect = FakeDetectGateway { ShellResult(0, "", "") } // -d 探测回空集合
        val vm = vmWithActions(devices, kernel, detect)
        vm.settle()
        vm.selectPackage(vm.state.value.entries!!.first())
        vm.settle()
        assertEquals(false, vm.state.value.selectedDisabled, "探测：-d 集合不含选中包 → 未禁用态")
        assertEquals(1, detect.commands.size, "选中触发一次禁用态探测")

        vm.requestDisable("com.demo.media")
        val pending = vm.state.value.toggle
        assertTrue(pending is DevicePackageToggleUiState.DisableConfirmPending, "请求禁用落确认等待态（未执行）（实际：$pending）")
        assertEquals(1, detect.commands.size, "确认前零禁启 spawn")

        vm.dismissToggleConfirm()
        assertEquals(DevicePackageToggleUiState.Idle, vm.state.value.toggle, "取消回 Idle")
        assertEquals(1, detect.commands.size, "取消零动作零 spawn（设备状态未被触碰）")
        assertTrue(detect.commands.none { it.contains("disable-user") }, "全程无禁用命令")
    }

    @Test
    fun `confirmed disable executes, refreshes details and re-probes disabled state`() {
        val devices = FakeDeviceSource().apply { deviceStateFlow.value = DeviceState.Single("S1", "Demo-1") }
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        var disabledSet = ""
        val detect = FakeDetectGateway { command ->
            if (command.contains("disable-user")) {
                ShellResult(0, "Package com.demo.media new state: disabled-user", "")
            } else {
                ShellResult(0, disabledSet, "")
            }
        }
        val vm = vmWithActions(devices, kernel, detect)
        vm.settle()
        val target = vm.state.value.entries!!.first()
        vm.selectPackage(target)
        vm.settle()
        assertEquals(false, vm.state.value.selectedDisabled, "前置：未禁用态（探测集合不含选中包）")

        vm.requestDisable(target.packageName)
        disabledSet = "package:${target.packageName} versionCode:60400000 uid:10061"
        vm.confirmToggle()
        vm.settle()

        val done = vm.state.value.toggle
        assertTrue(done is DevicePackageToggleUiState.Done, "确认后禁用完成回执（实际：$done）")
        assertTrue(done.message.contains("已禁用"), "完成回执人话（实际：${done.message}）")
        assertEquals(
            listOf("""adb -s S1 shell 'pm disable-user --user 0 '\''com.demo.media'\'''"""),
            detect.commands.filter { it.contains("disable-user") },
            "确认才执行：唯一一条禁用命令逐字节成立",
        )
        assertEquals(true, vm.state.value.selectedDisabled, "禁用后重探 → 已禁用态（禁启钮翻面）")
        assertEquals(2, kernel.detailsCalls.size, "禁启成功后详情缓存作废重读（dumpsys 状态已变）")
    }

    @Test
    fun `toggle failure surfaces human receipt without re-probe`() {
        val devices = FakeDeviceSource().apply { deviceStateFlow.value = DeviceState.Single("S1", "Demo-1") }
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val detect = FakeDetectGateway { command ->
            if (command.contains("disable-user")) {
                ShellResult(1, "", "Error: not allowed to disable")
            } else {
                ShellResult(0, "", "")
            }
        }
        val vm = vmWithActions(devices, kernel, detect)
        vm.settle()
        vm.selectPackage(vm.state.value.entries!!.first())
        vm.settle()
        val probeCount = detect.commands.size

        vm.requestDisable("com.demo.media")
        vm.confirmToggle()
        vm.settle()

        val failed = vm.state.value.toggle
        assertTrue(failed is DevicePackageToggleUiState.Failed, "失败态回执（实际：$failed）")
        assertTrue(failed.message.contains("Error: not allowed to disable"), "设备侧原文透传（实际：${failed.message}）")
        assertEquals(probeCount, detect.commands.size - 1, "失败路径不重探（探测次数只加执行命令一条）")
        assertEquals(1, kernel.detailsCalls.size, "失败不刷新详情（设备状态未变）")
    }

    @Test
    fun `export walks real pull pipeline to per-split done receipt in temp target dir`() {
        val devices = FakeDeviceSource().apply { deviceStateFlow.value = DeviceState.Single("S1", "Demo-1") }
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val detect = FakeDetectGateway { command ->
            if (command.contains("pm path")) ShellResult(0, pmPathFixture, "") else ShellResult(0, "", "")
        }
        val pull = FakePullGateway()
        val targetDir = Files.createTempDirectory("pkg-vm-export").toFile()
        val vm = vmWithActions(devices, kernel, detect, pull, targetDir)
        vm.settle()
        vm.selectPackage(vm.state.value.entries!!.first())
        vm.settle()

        vm.requestExport("com.demo.media")
        vm.settle()

        val export = vm.state.value.export
        assertTrue(export is DevicePackageExportUiState.Done, "导出完成回执（实际：$export）")
        assertEquals("com.demo.media", export.receipt.packageName)
        assertEquals(2, export.receipt.files.size, "多 split 逐文件回执")
        assertEquals(
            listOf(
                "${targetDir.absolutePath}/com.demo.media.apk",
                "${targetDir.absolutePath}/com.demo.media-2.apk",
            ),
            export.receipt.files.map { it.localPath },
            "目标名 = 包名 + 序号后缀防覆盖，落在注入的导出目录（不碰用户数据根）",
        )
        assertEquals(2, pull.commands.size, "逐 split 两次拉取命令")
        assertTrue(java.io.File("${targetDir.absolutePath}/com.demo.media.apk").exists(), "首个 split 转正在位")
        assertTrue(java.io.File("${targetDir.absolutePath}/com.demo.media-2.apk").exists(), "后续 split 转正在位")
    }

    @Test
    fun `export without target lands blocked receipt and no spawn`() {
        val devices = FakeDeviceSource() // None：无设备
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val detect = FakeDetectGateway { ShellResult(0, pmPathFixture, "") }
        val pull = FakePullGateway()
        val vm = vmWithActions(devices, kernel, detect, pull)
        vm.settle()

        vm.requestExport("com.demo.media")
        assertTrue(vm.state.value.error!!.contains("未检测到设备"), "目标不可用同步拒绝人话回执（实际：${vm.state.value.error}）")
        assertEquals(DevicePackageExportUiState.Idle, vm.state.value.export, "拒绝路径动作态不动")
        assertEquals(0, detect.commands.size, "pm 通道零 spawn")
        assertEquals(0, pull.commands.size, "拉取通道零 spawn")
    }

    @Test
    fun `selection change resets action states and re-probes new package`() {
        val devices = FakeDeviceSource().apply { deviceStateFlow.value = DeviceState.Single("S1", "Demo-1") }
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val detect = FakeDetectGateway { ShellResult(0, "", "") }
        val vm = vmWithActions(devices, kernel, detect)
        vm.settle()
        val xmedia = vm.state.value.entries!!.first { it.packageName == "com.demo.media" }
        vm.selectPackage(xmedia)
        vm.settle()
        vm.requestDisable("com.demo.media")
        assertTrue(vm.state.value.toggle is DevicePackageToggleUiState.DisableConfirmPending)

        val other = vm.state.value.entries!!.first { it.packageName == "com.example.userapp" }
        vm.selectPackage(other)
        assertEquals(DevicePackageToggleUiState.Idle, vm.state.value.toggle, "换选中复位禁启动作态（回执是卡内语境产物）")
        assertEquals(DevicePackageExportUiState.Idle, vm.state.value.export)
        assertEquals(null, vm.state.value.selectedDisabled, "换选中禁用态回未探测")
        vm.settle()
        assertEquals(false, vm.state.value.selectedDisabled, "新选中包重探")
    }

    // ── 快照对比（会话级内存快照不落盘，纯函数 diff + VM 动作面） ──

    @Test
    fun `diffPackageEntries covers three-way matrix and boundaries`() {
        val a = entry("com.a", system = true, versionCode = 1)
        val b = entry("com.b", system = false, versionCode = 2)
        val bUpgraded = entry("com.b", system = false, versionCode = 3)
        val c = entry("com.c", system = true, versionCode = 9)

        // 新增 only：当前有快照无
        assertEquals(
            listOf("com.c"),
            diffPackageEntries(listOf(a, b), listOf(a, b, c)).added.map { it.packageName },
            "新增 only",
        )
        // 移除 only：快照有当前无
        assertEquals(
            listOf("com.c"),
            diffPackageEntries(listOf(a, b, c), listOf(a, b)).removed.map { it.packageName },
            "移除 only",
        )
        // 版本变更 only：同包名 versionCode 不同
        assertEquals(
            listOf(DevicePackageVersionChange("com.b", 2, 3)),
            diffPackageEntries(listOf(a, b), listOf(a, bUpgraded)).versionChanged,
            "版本变更 only（旧值→新值成对）",
        )
        // 三者混合
        val mixed = diffPackageEntries(listOf(a, b, c), listOf(a, bUpgraded))
        assertEquals(
            DevicePackageDiff(
                added = emptyList(),
                removed = listOf(c),
                versionChanged = listOf(DevicePackageVersionChange("com.b", 2, 3)),
            ),
            mixed,
            "混合：移除 + 版本变更",
        )

        // 无差异边界：同列表（含列举序抖动）全空三分类
        val sameDiff = diffPackageEntries(okPackages(), okPackages().reversed())
        assertEquals(DevicePackageDiff(emptyList(), emptyList(), emptyList()), sameDiff, "同列表（乱序）= 无差异")

        // 空快照边界：全部计新增（快照缺席 = 基线零包）
        assertEquals(
            listOf("com.a", "com.b", "com.c"),
            diffPackageEntries(emptyList(), listOf(c, b, a)).added.map { it.packageName },
            "空快照 = 全新增且按包名升序",
        )
        assertEquals(0, diffPackageEntries(emptyList(), listOf(a)).removed.size, "空快照无移除")
        assertEquals(0, diffPackageEntries(emptyList(), listOf(a)).versionChanged.size, "空快照无版本变更")

        // 空当前列表边界：全部计移除
        assertEquals(
            listOf("com.a", "com.b"),
            diffPackageEntries(listOf(b, a), emptyList()).removed.map { it.packageName },
            "空列表 = 全移除且按包名升序",
        )

        // uid/isSystem 差异单独出现不计差异（diff 键 = 包名 + versionCode）
        val bRelabeled = entry("com.b", system = true, versionCode = 2, uid = 999)
        assertEquals(
            DevicePackageDiff(emptyList(), emptyList(), emptyList()),
            diffPackageEntries(listOf(b), listOf(bRelabeled)),
            "uid/系统标记差异不计差异",
        )
    }

    @Test
    fun `diffPackageEntries sorts each category stably by package name`() {
        val snap = listOf(
            entry("com.z", system = true, versionCode = 1),
            entry("com.m", system = true, versionCode = 1),
            entry("com.a", system = true, versionCode = 1),
        )
        val current = listOf(
            entry("com.y", system = false, versionCode = 5),
            entry("com.k", system = false, versionCode = 5),
            entry("com.a", system = true, versionCode = 2),
            entry("com.z", system = true, versionCode = 7),
        )
        val diff = diffPackageEntries(snap, current)
        assertEquals(
            listOf("com.k", "com.y"),
            diff.added.map { it.packageName },
            "新增按包名升序（与输入列举序无关）",
        )
        assertEquals(listOf("com.m"), diff.removed.map { it.packageName }, "移除按包名升序")
        assertEquals(
            listOf("com.a", "com.z"),
            diff.versionChanged.map { it.packageName },
            "版本变更按包名升序",
        )
        // 同输入集任意列举序得同一输出（稳定排序防抖动）
        assertEquals(diff, diffPackageEntries(snap.reversed(), current.reversed()), "列举序抖动不传染输出")
    }

    @Test
    fun `saveSnapshot freezes loaded list and new snapshot invalidates stale diff`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()

        vm.saveSnapshot()
        val snapshot = vm.state.value.snapshot
        assertTrue(snapshot != null, "保存快照落位")
        assertEquals("S1", snapshot!!.serial, "快照携带来源串号")
        assertEquals(okPackages(), snapshot.entries, "快照 = 保存时刻的全量装载面（视图投影不进快照）")
        assertTrue(snapshot.timestampMillis > 0, "快照携带保存时刻")

        vm.requestCompare()
        vm.settle()
        assertTrue(vm.state.value.diff is DevicePackageDiffUiState.Result, "同串号直接出对比回执")

        // 新快照顶替并作废既有回执（回执的基线已不存在）
        vm.saveSnapshot()
        val second = vm.state.value.snapshot!!
        assertEquals(DevicePackageDiffUiState.Idle, vm.state.value.diff, "新快照作废既有对比回执")
        assertTrue(second.timestampMillis >= snapshot.timestampMillis, "新快照顶替")
        assertEquals(3, second.entries.size, "顶替后的快照是当前装载面")
        assertEquals(1, kernel.listCalls.size, "快照存取零内核请求")
    }

    @Test
    fun `saveSnapshot without loaded list lands receipt and no snapshot`() {
        val devices = FakeDeviceSource() // None：无设备
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()

        vm.saveSnapshot()
        assertNull(vm.state.value.snapshot, "未装载零快照")
        assertTrue(vm.state.value.error!!.contains("未装载"), "拒绝回执人话（实际：${vm.state.value.error}）")

        vm.requestCompare()
        assertEquals(DevicePackageDiffUiState.Idle, vm.state.value.diff, "无快照对比零动作")
        assertNull(vm.state.value.entries, "全程零装载")
    }

    @Test
    fun `compare after refresh reflects current list per package-version key`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        var currentList = okPackages()
        val kernel = FakePackageKernel(
            listRespond = { Result.success(currentList) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()
        vm.saveSnapshot()

        // 刷新后列表实变：userapp 移除 + xmedia 升版本 + 新包加入
        currentList = listOf(
            entry("com.demo.media", system = true, versionCode = 60400001, uid = 10061),
            entry("com.android.cts.priv.ctsshim", system = true, versionCode = 31, uid = 10022),
            entry("com.new.preinstalled", system = true, versionCode = 1, uid = 10500),
        )
        vm.refresh()
        vm.settle()

        vm.requestCompare()
        vm.settle()
        val result = vm.state.value.diff
        assertTrue(result is DevicePackageDiffUiState.Result, "同串号对比直接出回执（实际：$result）")
        assertEquals("S1", result.currentSerial, "回执携带对比时刻当前串号")
        assertEquals(listOf("com.new.preinstalled"), result.diff.added.map { it.packageName }, "新增 = 当前有快照无")
        assertEquals(listOf("com.example.userapp"), result.diff.removed.map { it.packageName }, "移除 = 快照有当前无")
        assertEquals(
            listOf(DevicePackageVersionChange("com.demo.media", 60400000, 60400001)),
            result.diff.versionChanged,
            "版本变更 = 同包名 versionCode 不同",
        )

        vm.dismissDiff()
        assertEquals(DevicePackageDiffUiState.Idle, vm.state.value.diff, "关闭收起回执（快照保留）")
        assertTrue(vm.state.value.snapshot != null, "关闭不是丢弃基线")
    }

    @Test
    fun `cross serial compare requires confirmation and cancel is zero diff`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()
        vm.saveSnapshot()

        // 换设备（快照保留——跨串号对比是场景本体）
        devices.deviceStateFlow.value = DeviceState.Multi(listOf(DeviceInfo("S1", "Demo-1"), DeviceInfo("S2", "M1")))
        devices.selectedSerialFlow.value = "S2"
        vm.settle()
        assertEquals("S1", vm.state.value.snapshot?.serial, "快照跨串号保留")

        vm.requestCompare()
        vm.settle()
        val pending = vm.state.value.diff
        assertTrue(
            pending is DevicePackageDiffUiState.CrossSerialConfirmPending,
            "跨串号对比落确认等待态（实际：$pending）",
        )
        assertEquals("S1" to "S2", pending.snapshotSerial to pending.currentSerial, "确认面呈现两串号供判断")

        vm.dismissDiff()
        assertEquals(DevicePackageDiffUiState.Idle, vm.state.value.diff, "取消零回执（对比未执行）")

        vm.requestCompare()
        vm.confirmCompare()
        vm.settle()
        val result = vm.state.value.diff
        assertTrue(result is DevicePackageDiffUiState.Result, "确认后对比回执落地（实际：$result）")
        assertEquals("S1", result.snapshot.serial, "回执标注快照来源串号")
        assertEquals("S2", result.currentSerial, "回执标注当前串号")
        assertEquals(
            DevicePackageDiff(emptyList(), emptyList(), emptyList()),
            result.diff,
            "S2 列表与 S1 快照同 fixtures = 无差异回执（合法结果非失败）",
        )
    }

    @Test
    fun `target change resets diff receipt but snapshot survives session`() {
        val devices = FakeDeviceSource()
        devices.deviceStateFlow.value = DeviceState.Single("S1", "Demo-1")
        val kernel = FakePackageKernel(
            listRespond = { Result.success(okPackages()) },
            detailsScript = { name, _ -> Result.success(detailsOf(name)) },
        )
        val vm = DevicePackageViewModel(devices, kernel.listPackages, kernel.packageDetails)
        vm.settle()
        vm.saveSnapshot()
        vm.requestCompare()
        vm.settle()
        assertTrue(vm.state.value.diff is DevicePackageDiffUiState.Result, "前置：对比回执在位")

        // 目标从有到无：读数与回执失效，快照保留（会话级内存）
        devices.deviceStateFlow.value = DeviceState.None
        vm.settle()
        assertEquals(DevicePackageDiffUiState.Idle, vm.state.value.diff, "目标失效回执复位")
        assertNull(vm.state.value.entries, "目标失效清空陈旧读数")
        assertEquals("S1", vm.state.value.snapshot?.serial, "快照会话级保留")

        // 串号实变：对比回执随换目标复位
        devices.deviceStateFlow.value = DeviceState.Single("S2", "M1")
        vm.settle()
        vm.requestCompare()
        vm.settle()
        assertTrue(
            vm.state.value.diff is DevicePackageDiffUiState.CrossSerialConfirmPending,
            "换设备后的对比是显式新动作（跨串号先确认）",
        )
    }
}
