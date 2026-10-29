package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * XA-08 装配面源码锚守护（重设计批 4 · 2026-10-01）：busy 态三消费点接线钉死。
 *
 * 数据源 busy 流（manual-only 置位/复位）由 :core 的 DeviceMonitorDetectingTest
 * 行为验收；[com.jiaoay.easy_dev_toolbox.widget.device.DeviceStatusBar] 是 :ui
 * 渲染件（设备域升位后随域迁 :ui，页面行为无场景测试接缝），装配面按 SH-01
 * （ShellOutputPanelWiringGuardTest）先例用源码锚守护——三个消费点被改回无 busy
 * 形态（参数删除/禁用拆除/后缀丢失）时本守护红：
 * 1. DeviceStatusBar 签名含 `detecting: Boolean`（busy 传导接缝）；
 * 2. 刷新钮 `enabled = !detecting`（连点防抖——busy 期间 adb 在跑，连发叠进程）；
 * 3. 主行状态文字 `if (detecting)` 拼接「（检测中…）」后缀（busy 可见性语言）；
 * 4. 禁用态图标洗 `AppAlpha.contentDisabled`（token 承载——UiArchitectureGuardTest
 *    拦裸 alpha 字面量，此处钉 token 而非数字）；
 * 5. ShellToolsListSection 调用点传 `detecting = detecting`（漏传即编译红，
 *    本断言防的是将来被改回不传的口子——同款语义见 SH-01 装配点断言）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellToolsViewModelDetectingWiringGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun pageSource(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private fun deviceSource(): String {
        val file = File(
            projectRoot(),
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/device/DeviceStatusBar.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private fun viewModelSource(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsViewModel.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `device status bar signature carries detecting parameter`() {
        val text = deviceSource()
        assertTrue(
            "detecting: Boolean," in text,
            "XA-08 守护：DeviceStatusBar 签名必须含 detecting: Boolean（busy 传导接缝，" +
                "删除即状态栏与刷新钮双双失明）",
        )
    }

    @Test
    fun `refresh button disabled while detecting`() {
        val text = deviceSource()
        // 锚为空白容忍正则：图标钮命中区收 MICS 后调用点扩 modifier 参数，单行字面锚
        // 不再成立——接线语义（onClick 目标 + enabled = !detecting）不变，修饰链扩展不破锚
        assertTrue(
            Regex("""IconButton\(\s*onClick = onRefresh,\s*enabled = !detecting,""").containsMatchIn(text),
            "XA-08 守护：刷新钮必须 enabled = !detecting（busy 期间禁用——连点防抖，" +
                "busy 中连发只会排队叠 adb 子进程）",
        )
    }

    @Test
    fun `status text carries detecting suffix`() {
        val text = deviceSource()
        assertTrue(
            "if (detecting) \"\$statusText（检测中…）\" else statusText" in text,
            "XA-08 守护：主行状态文字必须 if (detecting) 拼接「（检测中…）」后缀" +
                "（busy 可见性语言——三分渲染文案不动，busy 只加缀）",
        )
    }

    @Test
    fun `disabled refresh icon tint uses content disabled token`() {
        val text = deviceSource()
        assertTrue(
            "MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.contentDisabled)" in text,
            "XA-08 守护：禁用态刷新图标必须洗 AppAlpha.contentDisabled 0.38（token 承载——" +
                "AppSymbolIcon tint 不随 enabled 自动洗，裸 alpha 字面量另被 UiGuard 拦）",
        )
    }

    @Test
    fun `list section call site passes detecting through`() {
        val text = pageSource()
        assertTrue(
            "detecting = detecting," in text,
            "XA-08 守护：ShellToolsListSection 调用 DeviceStatusBar 必须传 detecting" +
                "（漏传 = busy 流断线，状态栏与刷新钮失明）",
        )
        assertTrue(
            "viewModel.detecting.collectAsState()" in text,
            "XA-08 守护：ShellToolsListSection 必须订阅 viewModel.detecting（未订阅即上游断线）",
        )
    }

    @Test
    fun `queued command build warns on adb without device`() {
        // 未连接执行预警锚：buildQueuedCommand 内做 adb 判定 + StatusSignals 提醒
        //（非阻断——命令照常提交，toast 只做预期管理；手动输入显式键入不拦，不在本块）
        val text = viewModelSource()
        val blockSlice = text.lines()
            .dropWhile { !it.contains("private fun buildQueuedCommand") }
            .takeWhile { !((it.trim() == "}") && it.startsWith("}")) }
            .joinToString("\n")
        assertTrue(
            "AdbCommandRewriter.isAdbCommand(first)" in blockSlice,
            "XA-08 守护：buildQueuedCommand 必须判定首条命令是否 adb 类（词边界单源复用）",
        )
        assertTrue(
            "未检测到设备——adb 命令可能执行失败" in blockSlice &&
                "未选定目标设备——命令不注入 -s 直接执行" in blockSlice,
            "XA-08 守护：无设备/未选定两态必须 StatusSignals 预警（未连接必撞失败-排障循环）",
        )
    }
}
