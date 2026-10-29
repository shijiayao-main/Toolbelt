package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 设备栏「转无线」装配面源码锚守护（FileTaskViewModelLarkSendTest 同款形态——
 * 页面无 Compose 运行时直测缝，源码切片是一等守护形态）。
 *
 * 设备栏是共享渲染件（:ui DeviceStatusBar）两处挂载（Shell 工具页 / 设备观测页）：
 * 转无线动作两参缺省形态（onGoWireless = null / wirelessConnecting = false）=
 * 钮不渲染，任一页调用点缺参即「一页有钮、一页无钮」的用户可见不一致。本守护
 * 钉死 Shell 页装配面，防缺参回潮：
 * 1. 调用点两参非缺省传递——onGoWireless 经 VM 委托、wirelessConnecting 取回执
 *    流在态；
 * 2. VM 委托面——wirelessState 直通连接器单源、startWirelessConnect 转发动作，
 *    连接器构造注入缝与 monitor 同款（目标解析消费同一设备三流，成对注入）；
 * 3. 页级回执 LaunchedEffect 与设备观测页（DeviceObsPage）逐字同构——回执/拒绝
 *    各恰一条 toast，eventSeq 为去重键；回执流是 StateFlow 单源，两页 effect 各自
 *    组合、页面单组合保证不双发 toast。
 *
 * 行为内核（三步编排/先探后切/零 spawn 拒绝）由 :core 的
 * DeviceWirelessConnectorTest 验收，本守护只管装配面。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellToolsWirelessWiringGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun sourceAt(relativePath: String): String {
        val file = File(projectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private val pagePath =
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsPage.kt"
    private val viewModelPath =
        "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsViewModel.kt"
    private val obsPagePath =
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/deviceobs/DeviceObsPage.kt"

    /** 页级回执 effect 块（与 DeviceObsPage 逐字同构的断言对象；页体 4 空格缩进） */
    private val receiptEffectBlock =
        "LaunchedEffect(wirelessState.eventSeq) {\n" +
            "        wirelessState.lastReceipt?.let { StatusSignals.show(it.message) }\n" +
            "        wirelessState.rejectError?.let { StatusSignals.show(it) }\n" +
            "    }"

    @Test
    fun `device status bar call site passes wireless action and connecting state`() {
        val page = sourceAt(pagePath)
        assertTrue(
            "onGoWireless = { viewModel.startWirelessConnect() }," in page,
            "守护：Shell 页设备栏调用必须传 onGoWireless（缺省 null = 钮不渲染，" +
                "「观测页有钮、Shell 页无钮」的用户可见不一致回潮）",
        )
        assertTrue(
            "wirelessConnecting = wirelessState.connecting," in page,
            "守护：Shell 页设备栏调用必须传 wirelessConnecting（缺省 false = " +
                "编排在途钮不禁用，连点防抖失明）",
        )
        assertTrue(
            "viewModel.wirelessState.collectAsState()" in page,
            "守护：Shell 页必须订阅 VM wirelessState（未订阅即上游断线——" +
                "connecting 防抖与回执 effect 双失明）",
        )
    }

    @Test
    fun `view model delegates wireless state and action to connector`() {
        val viewModel = sourceAt(viewModelPath)
        assertTrue(
            "val wirelessState: StateFlow<WirelessConnectorState> get() = wirelessConnector.state" in viewModel,
            "守护：VM 必须直通连接器单源 wirelessState（自建镜像流会与单源漂移）",
        )
        assertTrue(
            "wirelessConnector.startWirelessConnect()" in viewModel,
            "守护：VM 的 startWirelessConnect 必须转发连接器动作（目标解析/三步编排" +
                "归连接器单源，VM 不得复制编排逻辑）",
        )
        assertTrue(
            "private val wirelessConnector: DeviceWirelessConnector = DeviceHub.wirelessConnector" in viewModel,
            "守护：连接器须经构造注入缝进入 VM（与 monitor 同款——目标解析消费同一" +
                "设备三流，测试须能成对注入 fake）",
        )
    }

    @Test
    fun `page level receipt effect is isomorphic with device obs page`() {
        val obsPage = sourceAt(obsPagePath)
        assertTrue(
            receiptEffectBlock in obsPage,
            "守护：同构断言的观测页先例锚缺失（DeviceObsPage 回执 effect 形态变更后" +
                "请同步本守护的 receiptEffectBlock）",
        )
        val page = sourceAt(pagePath)
        assertTrue(
            receiptEffectBlock in page,
            "守护：Shell 页回执 effect 必须与观测页逐字同构（eventSeq 去重键 + 回执/" +
                "拒绝各恰一条 toast——改写任一行即偏离单源配方，两页回执语义漂移）",
        )
    }
}
