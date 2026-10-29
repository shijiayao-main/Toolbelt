package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.compose.runtime.Composable
import com.jiaoay.easy_dev_toolbox.arch.assertBandsNotOverlapping
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.renderPixels
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodeBounds
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.jiaoay.toolbelt.device.ShellResult
import com.jiaoay.testfixtures.UserHomeIsolation
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.jiaoay.toolbelt.device.AdbProcessRunner
import com.jiaoay.toolbelt.device.DeviceApkExporter
import com.jiaoay.toolbelt.device.DeviceFileBrowser
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceLayoutInspector
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.device.DevicePackageBrowser
import com.jiaoay.toolbelt.device.DevicePackageToggler
import com.jiaoay.toolbelt.device.DeviceWindowViewsInspector
import com.jiaoay.toolbelt.device.KeyEventInjector
import com.jiaoay.toolbelt.device.PerfSampler
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.device.DeviceFilePuller

/**
 * 观测页子页切换场景渲染像素带守护（[SceneGuardSupport] 单源配方，设备观测
 * 域面板场景守护同款）：状态注入走生产链——真实 [DeviceFileBrowser] + fake
 * gateway（真解析管线全跑通，免真 adb），整页组合（含页级设备栏与子页签行）。
 *
 * 验证面（子页两态回归网）：
 * - 观测台子页在位：编排行与成员面板带进渲染面（子页改造后观测台内容不被
 *   签行/分发链挤出渲染面）；
 * - 文件子页在位：浏览面板头部/路径行/条目行进渲染面（VM 装载链 + LazyColumn
 *   有界高度链断裂即空壳红）；
 * - 应用子页在位：应用面板头部/过滤行/包条目行进渲染面（VM 装载链 + 全部包
 *   视角两次列举经 fake 网关真解析管线跑通）；详情展开卡是独立两态断言——
 *   展开前后像素带对比（选中懒加载链 + 详情字段面断裂即空壳红）；
 * - 布局子页在位（显式抓取契约两态 + 降级）：整页空态渲染面（头部/签行/未抓取
 *   占位，且零自动抓取 spawn）；VM 显式抓取后主源渲染面（全窗口视图 ZIP 经
 *   pull 仿真全管线——窗口选择行 + 层级树行进渲染面）；跨 ROM 无此命令的显式
 *   降级面（人话标注 + uiautomator 树行进渲染面）——装载链/降级链/懒展开投影
 *   链断裂即空壳红。
 *
 * 两子页内容带进渲染面且聚类不倒挂（像素级带间间隙钉子归各成员面板自己的
 * 场景守护——整页多面板排布存在合法紧凑行距）；组合期异常按场景守护配方直抛
 * （渲染面空白的部分真因）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceObsSubpageSceneGuardTest {

    private val homeIsolation = UserHomeIsolation("edt-obs-subpage-scene")
    private val createdScopes = mutableListOf<CoroutineScope>()

    /** VM init 挂起体（viewModelScope Main 档）的推进调度器 */
    private val dispatcher = StandardTestDispatcher()

    /** 真机（toybox 0.8.4）八列长格式实测基线样例（:core 浏览器测试同基线） */
    private val lsFixture = """
        total 24
        drwxrwx--x 2 root root 4096 2026-10-11 14:23 Movies
        -rw-rw---- 1 root root 512 2026-10-11 08:01 build.log
        lrwxrwxrwx 1 root root 12 2026-09-30 18:00 sdcard -> /storage/emulated/0
    """.trimIndent()

    /** 设备串号（页级设备栏/目标尾号/装载目标共用同一台） */
    private val serial = "SN-TEST-0001"

    /** 包列表全部包列举 fixture（五包：四系统 + 一非系统，系统标记走集合差） */
    private val pkgAllFixture = """
        package:com.demo.media versionCode:60400000 uid:10061
        package:com.demo.oem.launcher versionCode:30100000 uid:10062
        package:com.android.systemui versionCode:31 uid:10213
        package:com.android.cts.priv.ctsshim versionCode:31 uid:10022
        package:com.example.userapp versionCode:7 uid:10234
    """.trimIndent()

    /** 系统包集合 fixture（全部包的前四条——集合差后 userapp 为非系统） */
    private val pkgSystemFixture = """
        package:com.demo.media versionCode:60400000 uid:10061
        package:com.demo.oem.launcher versionCode:30100000 uid:10062
        package:com.android.systemui versionCode:31 uid:10213
        package:com.android.cts.priv.ctsshim versionCode:31 uid:10022
    """.trimIndent()

    /** 单包详情 fixture（真机实测结构裁样本，:core 应用浏览器测试同基线） */
    private val pkgDumpsysFixture = """
        Packages:
          Package [com.demo.media] (72b58e2):
            userId=10061
            codePath=/system/app/DemoDvrApp
            versionCode=60400000 minSdk=28 targetSdk=30
            versionName=V9.9.9_20990101000000000_Release
            firstInstallTime=2025-01-01 08:00:00
            lastUpdateTime=2025-01-01 08:00:00
            requested permissions:
              android.permission.RECORD_AUDIO
              android.permission.CAMERA
    """.trimIndent()

    /**
     * 窗口层级 dump fixture（uiautomator 降级源；真机实测成功形态：进度行与 XML
     * 同流 stdout——:core 布局检查内核测试同基线；根下两层节点供懒展开默认深度
     * 渲染面）
     */
    private val layoutDumpFixture = """
        UI hierchary dumped to: /data/local/tmp/toolbelt_uidump.xml
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.demo" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1280,720]"><node index="0" text="主界面" resource-id="" class="android.widget.LinearLayout" package="com.demo" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1280,400]"><node index="0" text="设置" resource-id="com.demo:id/settings" class="android.widget.TextView" package="com.demo" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[10,20][200,80]"/></node><node index="1" text="状态栏" resource-id="" class="android.view.View" package="com.demo" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1280,40]"/></node></hierarchy>
    """.trimIndent()

    /**
     * 全窗口视图 ZIP fixture（主源；两窗口小树，线格式 = AOSP ViewHierarchyEncoder
     * 契约：'S'+uint16 键+类型标签值 / 'M' map 开 / 'S' 0x0000 收尾 / 流末对照表，
     * 与 :core 夹具同基线——此处独立内联供 fake gateway 仿真 adb pull 文件通道）
     */
    private val windowViewsZipFixture: ByteArray by lazy {
        class Enc {
            val out = ByteArrayOutputStream()
            val names = LinkedHashMap<String, Int>()
            var nextId = 1
            fun key(name: String): Int {
                val id = names.getOrPut(name) { nextId++ }
                out.write(0x53); out.write((id ushr 8) and 0xFF); out.write(id and 0xFF); return id
            }
            fun str(name: String, v: String) { key(name); out.write(0x52); val b = v.toByteArray(); out.write((b.size ushr 8) and 0xFF); out.write(b.size and 0xFF); out.write(b) }
            fun int(name: String, v: Int) { key(name); out.write(0x49); out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
            fun shortv(name: String, v: Int) { key(name); out.write(0x53); out.write((v and 0xFFFF) ushr 8); out.write(v and 0xFF) }
            fun endMap() { out.write(0x53); out.write(0x00); out.write(0x00) }
            fun view(cls: String, id: String, l: Int, t: Int, r: Int, b: Int, children: List<() -> Unit>) {
                out.write(0x4D)
                str("meta:__name__", cls); str("meta:__hash__", "hh")
                int("misc:visibility", 0); int("layout:left", l); int("layout:top", t); int("layout:right", r); int("layout:bottom", b)
                str("id", id); shortv("meta:__childCount__", children.size)
                children.forEachIndexed { i, c -> key("meta:__child__$i"); c() }
                endMap()
            }
            fun endStream() {
                out.write(0x4D)
                str("__name__", "propertyIndex")
                names.forEach { (n, id) -> out.write(0x53); out.write((id ushr 8) and 0xFF); out.write(id and 0xFF); out.write(0x52); val b = n.toByteArray(); out.write((b.size ushr 8) and 0xFF); out.write(b.size and 0xFF); out.write(b) }
                endMap()
            }
        }
        fun stream(title: String, left: Int, top: Int, childId: String): ByteArray {
            val enc = Enc()
            enc.int("window:left", left); enc.int("window:top", top)
            enc.view(
                "android.widget.FrameLayout", "NO_ID", 0, 0, 1280, 720,
                children = listOf(
                    {
                        enc.view(
                            "android.widget.TextView", childId, 10, 20, 200, 80,
                            children = emptyList(),
                        )
                    },
                ),
            )
            enc.endStream()
            return enc.out.toByteArray()
        }
        val zipBytes = ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use { zip ->
            listOf(
                "1151e7a StatusBar" to stream("StatusBar", 0, 0, "id/clock"),
                "f23068e com.demo.app/com.demo.app.MainActivity" to stream("MainActivity", 0, 0, "id/action"),
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        zipBytes.toByteArray()
    }

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        createdScopes.forEach { it.cancel() }
        homeIsolation.restoreOnly()
        Dispatchers.resetMain()
    }

    /**
     * 设备域 fake 底座：adb 检测回单台设备、ls 回基线样例、pm 列举/详情回包
     * fixture、uiautomator dump 回层级 fixture、全窗口视图 dump 回退出码行 +
     * pull 仿真文件落盘（真解析管线全跑通——pm 须先匹配 `-s` 旗标分支再落通用
     * 分支；[windowViewsUnsupported] 为 true 时全窗口视图按跨 ROM 无此命令失败，
     * 供降级链路场景）。gateway 记录命令全文（自动 spawn 断言锚）。
     */
    private inner class RecordingGateway(
        private val windowViewsUnsupported: Boolean = false,
    ) : AdbProcessRunner {
        val commands = mutableListOf<String>()

        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return Result.success(
                when {
                    command.contains("devices") -> ShellResult(
                        0,
                        "List of devices attached\n$serial\tdevice product:demo model:demo1 device:demo\n",
                        "",
                    )

                    command.contains("dump-visible-window-views") ->
                        if (windowViewsUnsupported) {
                            // 跨 ROM 无此命令：远端非零退出 + 错误原文（错误经 stderr，
                            // stdout 只剩回执文本）
                            ShellResult(1, "", "Error: unknown command 'dump-visible-window-views'")
                        } else {
                            ShellResult(0, "TWV_DUMP_RC:0\n", "")
                        }

                    command.contains("uiautomator dump") -> ShellResult(0, layoutDumpFixture, "")

                    // pull 仿真（文件通道不经文本——fake 直接落本地产物）；主源不可用
                    // 形态下设备侧产物不存在，pull 如实失败（真机同形态）。注意路由序：
                    // uiautomator payload 内含 `rm -f` 段，须先于 rm 分支命中
                    command.contains(" pull ") -> {
                        if (windowViewsUnsupported) {
                            ShellResult(1, "", "adb: no such file or directory")
                        } else {
                            val localPath = command.trim().substringAfterLast(" ").trim('\'', '"')
                            File(localPath).writeBytes(windowViewsZipFixture)
                            ShellResult(0, "2 files pulled, 0 skipped. 12.3 MB/s (${windowViewsZipFixture.size} bytes in 0.000s)", "")
                        }
                    }

                    command.contains("rm -f") -> ShellResult(0, "", "")

                    command.contains("ls -l") -> ShellResult(0, lsFixture, "")

                    command.contains("pm list packages -s") -> ShellResult(0, pkgSystemFixture, "")

                    command.contains("pm list packages") -> ShellResult(0, pkgAllFixture, "")

                    command.contains("dumpsys package") -> ShellResult(0, pkgDumpsysFixture, "")

                    else -> ShellResult(0, "", "")
                },
            )
        }
    }

    private fun deviceFakes(windowViewsUnsupported: Boolean = false): Pair<DeviceMonitor, RecordingGateway> {
        val gateway = RecordingGateway(windowViewsUnsupported)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it }
        val monitor = DeviceMonitor(
            scope = scope,
            runner = gateway,
            endpointReader = { AdbEndpoint("") },
        )
        // 页面进台 LaunchedEffect 的进页即刷会经 fake 网关解析回同一台（防状态翻转抖动）；
        // 预置让 VM init 装载在场景首帧前即可起跑
        monitor.applyDeviceList(listOf(DeviceInfo(serial, "Demo-1")))
        return monitor to gateway
    }

    private fun samplerOf(monitor: DeviceMonitor, gateway: AdbProcessRunner): PerfSampler =
        PerfSampler(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it },
            devices = monitor,
            runner = gateway,
            endpointReader = { AdbEndpoint("") },
        )

    private fun injectorOf(monitor: DeviceMonitor, gateway: AdbProcessRunner): KeyEventInjector =
        KeyEventInjector(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it },
            devices = monitor,
            runner = gateway,
            endpointReader = { AdbEndpoint("") },
        )

    /** 空 ViewModelStore 的 owner：页级 viewModel(key){factory} 走初建路径（同 key 同实例） */
    private fun emptyOwner(): ViewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
    }

    /**
     * 单源渲染的 Main 档任务推进（onFrame 钩子实参）：VM 的装载请求挂起在
     * viewModelScope Main 档，逐帧推进令装载链与重组交替收敛，条目行进渲染面
     * （不推进则 VM init 永挂起、装载链与渲染面断流）。
     */
    private fun pumpMainTasks() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** 整页组合包 ViewModelStoreOwner（页级 viewModel() 消费）与目标内容 */
    @Composable
    private fun CompositionLocalProviderWrapper(content: @Composable () -> Unit) {
        // remember 单一 owner：重组间同 store（否则逐重组重建 VM 实例，装载链反复重跑）
        val owner = androidx.compose.runtime.remember { emptyOwner() }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalViewModelStoreOwner provides owner,
        ) {
            content()
        }
    }

    @Test
    fun `console subpage renders orchestrator row and member panels`() {
        val (monitor, gateway) = deviceFakes()
        val pixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            CompositionLocalProviderWrapper {
                DeviceObsPage(
                    monitor = monitor,
                    sampler = samplerOf(monitor, gateway),
                    keyInjector = injectorOf(monitor, gateway),
                    fileBrowser = DeviceFileBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    packageBrowser = DevicePackageBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    initialSubpage = OBS_SUBPAGE_CONSOLE,
                )
            }
        }
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(
            bands.size >= 5,
            "观测台子页应渲染 ≥5 内容带（页头/设备栏/子页签行/编排行/成员面板），实际 ${bands.size}：$bands" +
                "——观测台内容未进渲染面",
        )
        assertBandsNotOverlapping(bands)
    }

    @Test
    fun `files subpage renders browser panel with loaded entries`() {
        val (monitor, gateway) = deviceFakes()
        val pixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            CompositionLocalProviderWrapper {
                DeviceObsPage(
                    monitor = monitor,
                    sampler = samplerOf(monitor, gateway),
                    keyInjector = injectorOf(monitor, gateway),
                    fileBrowser = DeviceFileBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    packageBrowser = DevicePackageBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    initialSubpage = OBS_SUBPAGE_FILES,
                )
            }
        }
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(
            bands.size >= 5,
            "文件子页应渲染 ≥5 内容带（页头/设备栏/子页签行/面板头与路径行/条目行），实际 ${bands.size}：$bands" +
                "——装载链或 LazyColumn 有界高度链断裂（条目行未进渲染面）",
        )
        assertBandsNotOverlapping(bands)
        // 文件子页专属断言：渲染面下半区须有条目行内容带（VM 经 fake 网关真解析
        // 管线装载 3 条目——空壳/占位面板即红）
        val midY = pixels.indices.last / 2
        assertTrue(
            bands.any { it.first > midY },
            "渲染面下半区应有条目行内容带，实际 bands=$bands——浏览面板空壳（装载链断裂）",
        )
    }

    @Test
    fun `apps subpage renders package panel with loaded entries`() {
        val (monitor, gateway) = deviceFakes()
        val pixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            CompositionLocalProviderWrapper {
                DeviceObsPage(
                    monitor = monitor,
                    sampler = samplerOf(monitor, gateway),
                    keyInjector = injectorOf(monitor, gateway),
                    fileBrowser = DeviceFileBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    packageBrowser = DevicePackageBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    initialSubpage = OBS_SUBPAGE_APPS,
                )
            }
        }
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(
            bands.size >= 5,
            "应用子页应渲染 ≥5 内容带（页头/设备栏/子页签行/面板头与过滤行/条目行），实际 ${bands.size}：$bands" +
                "——装载链或 LazyColumn 有界高度链断裂（条目行未进渲染面）",
        )
        assertBandsNotOverlapping(bands)
        // 应用子页专属断言：渲染面下半区须有包条目行内容带（VM 经 fake 网关真解析
        // 管线装载全部包视角五条——空壳/占位面板即红）
        val midY = pixels.indices.last / 2
        assertTrue(
            bands.any { it.first > midY },
            "渲染面下半区应有包条目行内容带，实际 bands=$bands——应用面板空壳（装载链断裂）",
        )
    }

    /**
     * 应用面板详情展开两态（FM-2 子页场景先例的详情面）：同一 fake 设备源直驱
     * 面板（页级签行不在本断言面），选中前（收起态）与选中后（展开态）各渲染一帧
     * ——详情懒加载链（选中 → dumpsys 经 fake 网关真解析 → 字段面）断裂即两帧
     * 同带数红。
     */
    @Test
    fun `apps panel detail expansion adds detail card bands`() {
        val (monitor, gateway) = deviceFakes()
        val browser = DevicePackageBrowser(runner = gateway, endpointReader = { AdbEndpoint("") })
        // 动作区内核同走 fake 网关（选中会触发禁用态探测——真网关会 spawn 真 adb）
        val vm = DevicePackageViewModel(
            monitor,
            browser::listPackages,
            browser::packageDetails,
            exporter = DeviceApkExporter(
                runner = gateway,
                puller = DeviceFilePuller(runner = gateway, endpointReader = { AdbEndpoint("") }),
                statEntry = { _, _ -> Result.failure(IllegalStateException("场景测试不触导出")) },
                endpointReader = { AdbEndpoint("") },
            ),
            toggler = DevicePackageToggler(runner = gateway, endpointReader = { AdbEndpoint("") }),
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.entries != null, "前置：包列表经 fake 网关真解析管线装载")

        val collapsedPixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            CompositionLocalProviderWrapper {
                DevicePackagePanel(devices = monitor, sharedViewModel = vm, onGoSettings = {})
            }
        }
        val collapsedBands = contentRows(collapsedPixels, SCENE_W)
        assertBandsNotOverlapping(collapsedBands)

        vm.selectPackage(vm.state.value.entries!!.first())
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.details is DevicePackageDetailsUiState.Ready, "前置：详情懒加载完成")

        val expandedPixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            CompositionLocalProviderWrapper {
                DevicePackagePanel(devices = monitor, sharedViewModel = vm, onGoSettings = {})
            }
        }
        val expandedBands = contentRows(expandedPixels, SCENE_W)
        assertBandsNotOverlapping(expandedBands)
        assertTrue(
            expandedBands.size > collapsedBands.size,
            "详情展开卡应新增内容带（收起 ${collapsedBands.size} 带 → 展开 ${expandedBands.size} 带）" +
                "——详情字段面未进渲染面（懒加载链或展开卡断裂）",
        )
    }

    /**
     * 布局子页空态（显式抓取契约）：整页组合渲染面在位（页头/设备栏/签行/面板
     * 头/未抓取占位）且**零自动抓取 spawn**——层级抓取是显式动作，进页/切签不得
     * 自动发起秒级命令（gateway 命令记录直证：全窗口视图/降级 dump/pull 三形态
     * 均零出现）。
     */
    @Test
    fun `layout subpage renders explicit dump entry without auto spawn`() {
        val (monitor, gateway) = deviceFakes()
        val pixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            CompositionLocalProviderWrapper {
                DeviceObsPage(
                    monitor = monitor,
                    sampler = samplerOf(monitor, gateway),
                    keyInjector = injectorOf(monitor, gateway),
                    fileBrowser = DeviceFileBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    packageBrowser = DevicePackageBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    windowViewsInspector = DeviceWindowViewsInspector(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    layoutInspector = DeviceLayoutInspector(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    initialSubpage = OBS_SUBPAGE_LAYOUT,
                )
            }
        }
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(
            bands.size >= 5,
            "布局子页空态应渲染 ≥5 内容带（页头/设备栏/子页签行/面板头/未抓取占位），实际 ${bands.size}：$bands" +
                "——面板接线或空态占位断裂",
        )
        assertBandsNotOverlapping(bands)
        assertTrue(
            gateway.commands.none {
                it.contains("uiautomator dump") || it.contains("dump-visible-window-views") || it.contains(" pull ")
            },
            "进页零自动抓取 spawn（显式抓取契约），实际命令：${gateway.commands}",
        )
    }

    /**
     * 布局面板层级树两态（主源端到端）：VM 显式抓取（真 [DeviceWindowViewsInspector]
     * + fake 网关，设备侧落 ZIP → pull 仿真落盘 → ZIP 解码全管线）后渲染——窗口
     * 选择行与树行进渲染面（装载链 + 懒展开投影链断裂即空壳红）；空态与树态带数
     * 对比直证树行确已上屏。
     */
    @Test
    fun `layout panel renders tree rows after explicit dump`() {
        val (monitor, gateway) = deviceFakes()
        val vm = DeviceLayoutViewModel(
            monitor,
            DeviceWindowViewsInspector(runner = gateway, endpointReader = { AdbEndpoint("") })::dumpWindowViews,
            DeviceLayoutInspector(runner = gateway, endpointReader = { AdbEndpoint("") })::dumpHierarchy,
        )
        dispatcher.scheduler.advanceUntilIdle()

        val emptyPixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            CompositionLocalProviderWrapper {
                DeviceLayoutPanel(devices = monitor, sharedViewModel = vm)
            }
        }
        val emptyBands = contentRows(emptyPixels, SCENE_W)
        assertBandsNotOverlapping(emptyBands)

        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state.root != null, "前置：显式抓取经 fake 网关真解析管线装载")
        assertEquals(LayoutSnapshotSource.WINDOW_VIEWS, state.source, "主源快照（全窗口视图）")
        assertEquals(2, state.windows.size, "fixture 两窗口入清单")
        assertNull(state.degradedNotice, "主源成功零降级标注")

        val treePixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            CompositionLocalProviderWrapper {
                DeviceLayoutPanel(devices = monitor, sharedViewModel = vm)
            }
        }
        val treeBands = contentRows(treePixels, SCENE_W)
        assertBandsNotOverlapping(treeBands)
        val midY = treePixels.indices.last / 2
        assertTrue(
            treeBands.any { it.first > midY },
            "渲染面下半区应有层级树行内容带，实际 bands=$treeBands——树投影链断裂（空壳）",
        )
        assertTrue(
            treeBands.size > emptyBands.size,
            "树态应比空态多内容带（空态 ${emptyBands.size} 带 → 树态 ${treeBands.size} 带）" +
                "——树行未进渲染面",
        )
    }

    /**
     * 布局面板降级两态（全窗口视图不可用 → 显式降级不静默）：fake 网关按跨 ROM
     * 无此命令回执——显式抓取后降级标注人话上屏（原因原文 + 降级源标注）且降级
     * 源树行进渲染面（uiautomator fixture 经真解析管线）。
     */
    @Test
    fun `layout panel degrades to uiautomator dump when window views unsupported`() {
        val (monitor, gateway) = deviceFakes(windowViewsUnsupported = true)
        val vm = DeviceLayoutViewModel(
            monitor,
            DeviceWindowViewsInspector(runner = gateway, endpointReader = { AdbEndpoint("") })::dumpWindowViews,
            DeviceLayoutInspector(runner = gateway, endpointReader = { AdbEndpoint("") })::dumpHierarchy,
        )
        dispatcher.scheduler.advanceUntilIdle()

        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value
        assertEquals(LayoutSnapshotSource.UI_AUTOMATOR, state.source, "降级快照源显式标注")
        assertTrue(
            state.degradedNotice?.contains("unknown command") == true &&
                state.degradedNotice?.contains("uiautomator") == true,
            "降级人话透传原因并标注降级源：${state.degradedNotice}",
        )
        assertTrue(state.windows.isEmpty(), "降级快照无窗口清单")
        assertTrue(state.root != null, "降级快照树在位")

        val treePixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            CompositionLocalProviderWrapper {
                DeviceLayoutPanel(devices = monitor, sharedViewModel = vm)
            }
        }
        val treeBands = contentRows(treePixels, SCENE_W)
        assertBandsNotOverlapping(treeBands)
        assertTrue(
            treeBands.size >= 5,
            "降级态应渲染面板头/降级标注/树行等内容带，实际 bands=$treeBands——降级渲染链断裂",
        )
    }

    /**
     * 观测台子页滚动可达性（子页 [androidx.compose.foundation.verticalScroll] 的像素
     * 背书）：小视口（内容必超窗）渲染两帧——未滚动 vs 首帧测量后滚到底（注入缝
     * [DeviceObsPage] consoleScrollState）。状态面直证：内容超窗（maxValue > 0）且
     * 已滚到底（value == maxValue）；像素面背书：滚动前后渲染带分布改变（内容确已
     * 位移）且内容末端带抵达滚动视口底缘。KeyPadPanel 是观测台最后成员且默认展开
     * ——内容末端可达即其全貌可达。
     */
    @Test
    fun `console subpage scrolls to reach key pad panel at bottom`() {
        val (monitor, gateway) = deviceFakes()
        val unscrolledPixels = renderPixels(SCENE_W, SCENE_H_SCROLL, onFrame = ::pumpMainTasks) {
            CompositionLocalProviderWrapper {
                DeviceObsPage(
                    monitor = monitor,
                    sampler = samplerOf(monitor, gateway),
                    keyInjector = injectorOf(monitor, gateway),
                    fileBrowser = DeviceFileBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    packageBrowser = DevicePackageBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    initialSubpage = OBS_SUBPAGE_CONSOLE,
                )
            }
        }
        val unscrolledBands = contentRows(unscrolledPixels, SCENE_W)

        val scrollState = ScrollState(0)
        val scrolledPixels = renderPixels(SCENE_W, SCENE_H_SCROLL, onFrame = ::pumpMainTasks) {
            LaunchedEffect(Unit) {
                // 双帧等待：首帧 tick 后布局尚未结算（maxValue 未就绪，scrollTo 会被
                // 钳到 0）——次帧 tick 时上一帧测量已完成，滚动值才落得住
                withFrameNanos { }
                withFrameNanos { }
                scrollState.scrollTo(Int.MAX_VALUE)
            }
            CompositionLocalProviderWrapper {
                DeviceObsPage(
                    monitor = monitor,
                    sampler = samplerOf(monitor, gateway),
                    keyInjector = injectorOf(monitor, gateway),
                    fileBrowser = DeviceFileBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    packageBrowser = DevicePackageBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                    initialSubpage = OBS_SUBPAGE_CONSOLE,
                    consoleScrollState = scrollState,
                )
            }
        }
        val scrolledBands = contentRows(scrolledPixels, SCENE_W)

        // 滚动链直证（状态面）：内容超窗（可滚动量 > 0）且已滚到底（value == maxValue
        // ——KeyPadPanel 为最后成员且默认展开，内容末端可达即其全貌可达）
        assertTrue(
            scrollState.maxValue > 0,
            "观测台子页内容应超窗（可滚动量 ${scrollState.maxValue}）——内容未超窗或滚动链断裂",
        )
        assertEquals(
            scrollState.maxValue,
            scrollState.value,
            "应已滚动到底（value=${scrollState.value} maxValue=${scrollState.maxValue}）——内容末端不可达",
        )
        // 像素面：滚动后渲染带分布改变（内容确已位移，非状态自说自话）
        assertTrue(
            scrolledBands != unscrolledBands,
            "滚动前后渲染带应不同（未滚动 $unscrolledBands / 滚动后 $scrolledBands）——滚动未位移内容",
        )
        val viewportBottom = SCENE_H_SCROLL - SCROLL_VIEWPORT_BOTTOM_PADDING_PX
        assertTrue(
            scrolledBands.last().last >= viewportBottom - BOTTOM_EDGE_TOLERANCE_PX,
            "滚到底后内容末端带应抵达滚动视口底缘（KeyPadPanel 全貌可达），实际 ${scrolledBands.last()}",
        )
    }

    /**
     * 面板头行展开钮贴行尾（U14 回归锚）：三个展开式面板头行（logcat/性能/按键）
     * 的展开图标右缘必须接近场景右缘——头行标题 Text 若回退 `weight(1f, fill = false)`
     * +补位 Spacer 形态，fill=false 未吃满的配额会悬空在行尾把展开钮推离右缘
     * （窗口越大/标题越短越明显，维护者真机报告「箭头卡在中间」的根因）。语义树
     * 坐标定位（contentDescription 精准命中图标节点）免像素扫描假阳性。投屏面板
     * 已改单行入口卡（无展开钮），不在本断言面。
     */
    @Test
    fun `panel header expand icons hug the trailing edge`() {
        val (monitor, gateway) = deviceFakes()
        com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle(SCENE_W, SCENE_H).use { handle ->
            handle.setContent {
                CompositionLocalProviderWrapper {
                    DeviceObsPage(
                        monitor = monitor,
                        sampler = samplerOf(monitor, gateway),
                        keyInjector = injectorOf(monitor, gateway),
                        fileBrowser = DeviceFileBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                        packageBrowser = DevicePackageBrowser(runner = gateway, endpointReader = { AdbEndpoint("") }),
                        initialSubpage = OBS_SUBPAGE_CONSOLE,
                    )
                }
            }
            handle.renderPixels(onFrame = ::pumpMainTasks)
            // KeyPadPanel 默认展开态，钮语义为「收起」；其余两面板默认收起态为「展开」
            for (label in listOf("展开抓取面板", "展开性能面板", "收起按键遥控面板")) {
                val bounds = handle.semanticsNodeBounds { node: androidx.compose.ui.semantics.SemanticsNode ->
                    node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                        ?.any { it == label } == true
                }
                assertTrue(
                    bounds.right >= SCENE_W - EXPAND_ICON_TRAILING_TOLERANCE_PX,
                    "「$label」图标右缘 ${bounds.right} 应贴行尾（≥ ${SCENE_W - EXPAND_ICON_TRAILING_TOLERANCE_PX}）" +
                        "——头行标题未吃满配额（weight fill=false 悬空回潮）把展开钮推离右缘",
                )
            }
        }
    }

    companion object {
        private const val SCENE_W = 1096
        private const val SCENE_H = 900

        /** 滚动可达性用例视口高（内容必超窗——KeyPadPanel 默认展开 + 三面板头部已超） */
        private const val SCENE_H_SCROLL = 480

        /** 滚动视口底缘 = 场景高 − PageScaffold Comfortable 密度底距（24dp，密度 1） */
        private const val SCROLL_VIEWPORT_BOTTOM_PADDING_PX = 24

        /** 底缘命中容差（像素带聚类边缘 + 布局取整 + 成员卡内距） */
        private const val BOTTOM_EDGE_TOLERANCE_PX = 16

        /** 展开钮贴行尾容差 = 页边距 + 卡片水平内距(16) + IconButton 内图标空隙((40-18)/2) 的上界余量 */
        private const val EXPAND_ICON_TRAILING_TOLERANCE_PX = 72
    }
}
