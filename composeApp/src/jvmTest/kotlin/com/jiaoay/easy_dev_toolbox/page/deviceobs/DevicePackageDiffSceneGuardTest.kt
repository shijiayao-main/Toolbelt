package com.jiaoay.easy_dev_toolbox.page.deviceobs

import com.jiaoay.easy_dev_toolbox.arch.assertBandsNotOverlapping
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.isContentPx
import com.jiaoay.easy_dev_toolbox.arch.renderPixels
import com.jiaoay.toolbelt.device.ShellResult
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import com.jiaoay.toolbelt.device.AdbProcessRunner
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.device.DevicePackageBrowser
import com.jiaoay.toolbelt.adb.AdbEndpoint

/**
 * 应用面板快照对比回执区场景渲染像素带守护（[SceneGuardSupport] 单源配方，应用
 * 面板场景守护同款）：状态注入走生产链——真实 [DevicePackageBrowser] + fake 网关
 * （包列举真解析管线跑通，免真 adb），diff 动作走 VM 真链（保存快照 → fake 网关换
 * 列表 → 刷新 → 对比），回执区按三分类态渲染。
 *
 * 验证面（回执区回归网；三段清单排版与深底观感归维护者真机终裁）：
 * - 三分类回执在场：对比回执区（深底整块 + 计数行 + 新增/移除/版本变更三段清单行）
 *   进渲染面——全帧内容像素差分计数直证整块在场（回执区塌陷/未渲染即红；区内文字
 *   行 4px 排距合法并带，带数只作 ≥1 的粗锚）；
 * - 态区分差分：三分类回执比无差异回执多出内容像素（三段清单行簇 vs 计数行 +
 *   「无差异」单行——分类塌并/单行伪装三分类即差分见底红）；
 * - 可收起：关闭回执后内容像素增量清零（收起不是丢弃基线——快照元数据行保留，
 *   回执面必须随关闭退场）。
 *
 * 跨设备对比确认对话框是弹层（Popup 不栅格化），本守护不覆盖，留真机走查。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicePackageDiffSceneGuardTest {

    private val homeIsolation = UserHomeIsolation("edt-pkg-diff-scene")
    private val createdScopes = mutableListOf<CoroutineScope>()

    /** VM init 挂起体（viewModelScope Main 档）的推进调度器 */
    private val dispatcher = StandardTestDispatcher()

    /** 设备串号（fake 网关回单台设备与快照元数据共用同一台） */
    private val serial = "SN-TEST-0001"

    /** 快照基线列表（五包，:core 应用浏览器测试同基线形态） */
    private val pkgAllFirst = """
        package:com.demo.media versionCode:60400000 uid:10061
        package:com.demo.oem.launcher versionCode:30100000 uid:10062
        package:com.android.systemui versionCode:31 uid:10213
        package:com.android.cts.priv.ctsshim versionCode:31 uid:10022
        package:com.example.userapp versionCode:7 uid:10234
    """.trimIndent()

    /** 对比时刻列表：移除 ctsshim / userapp 版本 7→8 / 新增 newapp（三分类各一条） */
    private val pkgAllSecond = """
        package:com.demo.media versionCode:60400000 uid:10061
        package:com.demo.oem.launcher versionCode:30100000 uid:10062
        package:com.android.systemui versionCode:31 uid:10213
        package:com.example.userapp versionCode:8 uid:10234
        package:com.example.newapp versionCode:9 uid:10250
    """.trimIndent()

    /** 系统包集合 fixture（基线列表前四条——集合差后 userapp/newapp 为非系统） */
    private val pkgSystemFixture = """
        package:com.demo.media versionCode:60400000 uid:10061
        package:com.demo.oem.launcher versionCode:30100000 uid:10062
        package:com.android.systemui versionCode:31 uid:10213
        package:com.android.cts.priv.ctsshim versionCode:31 uid:10022
    """.trimIndent()

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
     * 设备域 fake 底座：adb 检测回单台设备、pm 列举回可变 fixture（保存快照后换列表
     * 制造三分类差异——真解析管线全跑通；pm 须先匹配 `-s` 旗标分支再落通用分支）。
     */
    private inner class RecordingGateway : AdbProcessRunner {

        /** 全部包列举 fixture（可变——快照基线 vs 对比时刻两份列表的切换位） */
        var allPackages: String = pkgAllFirst

        override suspend fun execute(command: String): Result<ShellResult> =
            Result.success(
                when {
                    command.contains("devices") -> ShellResult(
                        0,
                        "List of devices attached\n$serial\tdevice product:demo model:demo1 device:demo\n",
                        "",
                    )

                    command.contains("pm list packages -s") -> ShellResult(0, pkgSystemFixture, "")

                    command.contains("pm list packages") -> ShellResult(0, allPackages, "")

                    else -> ShellResult(0, "", "")
                },
            )
    }

    private fun deviceFakes(): Pair<DeviceMonitor, RecordingGateway> {
        val gateway = RecordingGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it }
        val monitor = DeviceMonitor(
            scope = scope,
            runner = gateway,
            endpointReader = { AdbEndpoint("") },
        )
        monitor.applyDeviceList(listOf(DeviceInfo(serial, "Demo-1")))
        return monitor to gateway
    }

    /** 单源渲染的 Main 档任务推进（onFrame 钩子实参）：装载/对比请求挂起体逐帧收敛 */
    private fun pumpMainTasks() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** 全帧内容像素计数（与背景差值超阈即计——回执区深底整块与文字行都入计） */
    private fun totalContentPx(pixels: Array<IntArray>): Int {
        var cnt = 0
        for (row in pixels) {
            for (p in row) {
                if (isContentPx(p)) cnt++
            }
        }
        return cnt
    }

    @Test
    fun `three category diff receipt renders deep block and collapses on dismiss`() {
        val (monitor, gateway) = deviceFakes()
        val browser = DevicePackageBrowser(runner = gateway, endpointReader = { AdbEndpoint("") })
        val vm = DevicePackageViewModel(monitor, browser::listPackages, browser::packageDetails)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.entries != null, "前置：基线列表经 fake 网关真解析管线装载")

        vm.saveSnapshot()
        gateway.allPackages = pkgAllSecond
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        vm.requestCompare()
        val diff = vm.state.value.diff as? DevicePackageDiffUiState.Result
        assertTrue(diff != null, "前置：同串号对比直接出三分类回执（实际：${vm.state.value.diff}）")
        assertEquals(1, diff.diff.added.size, "前置：新增一类（newapp）")
        assertEquals(1, diff.diff.removed.size, "前置：移除一类（ctsshim）")
        assertEquals(1, diff.diff.versionChanged.size, "前置：版本变更一类（userapp 7→8）")

        // 三分类回执在场：深底整块 + 计数行 + 三段清单行进渲染面
        val diffPixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            DevicePackagePanel(devices = monitor, sharedViewModel = vm, onGoSettings = {})
        }
        val diffBands = contentRows(diffPixels, SCENE_W)
        assertBandsNotOverlapping(diffBands)

        // 关闭回执：回执面退场（快照基线保留——收起不是丢弃基线）
        vm.dismissDiff()
        val closedPixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            DevicePackagePanel(devices = monitor, sharedViewModel = vm, onGoSettings = {})
        }
        val closedBands = contentRows(closedPixels, SCENE_W)
        assertBandsNotOverlapping(closedBands)

        assertTrue(
            totalContentPx(diffPixels) >= totalContentPx(closedPixels) + DIFF_SECTION_CONTENT_GAIN_MIN,
            "三分类回执区应整块进渲染面（全帧内容像素差分 < $DIFF_SECTION_CONTENT_GAIN_MIN 即回执区缺席/塌陷）——" +
                "有回执 ${totalContentPx(diffPixels)} px / 已关闭 ${totalContentPx(closedPixels)} px",
        )
        assertTrue(
            diffBands.size >= closedBands.size + 1,
            "三分类回执应比关闭态多至少一条内容带（回执区行簇——区内文字行 4px 排距合法并带），实际" +
                "有回执 ${diffBands.size} 带 / 已关闭 ${closedBands.size} 带——回执区未进渲染面",
        )

        // 态区分差分：以当前列表再存快照后对比——回执区在场但清单塌成「无差异」单行
        vm.saveSnapshot()
        vm.requestCompare()
        val emptyDiff = vm.state.value.diff as? DevicePackageDiffUiState.Result
        assertTrue(
            emptyDiff != null &&
                emptyDiff.diff.added.isEmpty() &&
                emptyDiff.diff.removed.isEmpty() &&
                emptyDiff.diff.versionChanged.isEmpty(),
            "前置：无差异回执（快照 = 当前列表，实际：${vm.state.value.diff}）",
        )
        val noDiffPixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            DevicePackagePanel(devices = monitor, sharedViewModel = vm, onGoSettings = {})
        }
        val noDiffBands = contentRows(noDiffPixels, SCENE_W)
        assertBandsNotOverlapping(noDiffBands)
        assertTrue(
            totalContentPx(noDiffPixels) >= totalContentPx(closedPixels) + DIFF_SECTION_CONTENT_GAIN_MIN,
            "无差异回执区同样应整块进渲染面（深底块 + 计数行 + 无差异行）",
        )
        assertTrue(
            totalContentPx(diffPixels) >= totalContentPx(noDiffPixels) + DIFF_SECTION_CATEGORY_GAIN_MIN,
            "三分类清单行簇应比无差异单行形态多出内容像素（差分 < $DIFF_SECTION_CATEGORY_GAIN_MIN 即" +
                "分类塌并/单行伪装三分类）——三分类 ${totalContentPx(diffPixels)} px / 无差异 ${totalContentPx(noDiffPixels)} px",
        )
    }

    companion object {
        private const val SCENE_W = 1096
        private const val SCENE_H = 900

        /** 回执区深底整块 + 清单行的全帧内容像素差分下限（快照元数据行位数抖动远小于此） */
        private const val DIFF_SECTION_CONTENT_GAIN_MIN = 2_000

        /** 三分类清单行簇 vs 无差异单行的内容像素差分下限（态区分锚；回执区文字行 4px 排距合法并带，带数不可判） */
        private const val DIFF_SECTION_CATEGORY_GAIN_MIN = 1_500
    }
}
