package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XA-05 · DeviceSelectDialog 确认钮动词化 + Enter/Esc 键面源码锚守护
 * （重设计批 10 · 2026-10-02）。
 *
 * 卡面 acceptance 两条：
 * 1. 对话框主钮文案为「使用此设备」（design-language §4.2 主操作用动宾，
 *    禁「确定」泛化词——全仓唯一残留清零）；
 * 2. Enter 直接确认当前选中设备、Esc 关闭（PathConfirmCard.kt 同款
 *    onPreviewKeyEvent 配方：KeyDown 过滤 + Enter/NumPadEnter + Escape），
 *    卡底键面提示行「Enter 确认 · Esc 取消」。
 *
 * 施工序：SH-05 先（内联直选接管 ≤3 台）、本卡后——对话框降级为 >3 台
 * 路径，动词化与键盘面在该路径上仍成立（PM 合成裁定）。
 *
 * 防回潮锚：`Text("确定")` 精确形态全文件零命中（「确定删除/确定清空」
 * 等 AppConfirmDialog 语义文案不在此清账面——切片锚限定 DeviceSelectDialog
 * 函数区 + 精确逗号收尾形态双保险）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellToolsDeviceSelectDialogVerbGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")})")
    }

    private fun source(): String {
        val file = File(
            projectRoot(),
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/device/DeviceSelectDialog.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    /** DeviceSelectDialog 函数区切片（单函数文件——声明起到文件尾） */
    private fun dialogBlock(): String {
        val src = source()
        val start = src.indexOf("fun DeviceSelectDialog(")
        assertTrue(start >= 0, "XA-05 守护：DeviceSelectDialog 函数缺失（重构后请同步本守护）")
        return src.substring(start)
    }

    @Test
    fun `confirm button uses verb phrase`() {
        val block = dialogBlock()
        assertTrue(
            "Text(\"使用此设备\")" in block,
            "XA-05 守护：确认钮文案必须为「使用此设备」（动宾，design-language §4.2）",
        )
    }

    @Test
    fun `generic confirm label fully removed`() {
        val block = dialogBlock()
        assertFalse(
            "Text(\"确定\")" in block,
            "XA-05 守护：DeviceSelectDialog 不得回潮 Text(\"确定\") 泛化词" +
                "（全仓唯一残留已清零）",
        )
        // 全文件面：精确 Text("确定") 形态零命中（「确定删除」等他域文案不误伤）
        val src = source()
        assertFalse(
            "Text(\"确定\")" in src,
            "XA-05 守护：本文件不得残留 Text(\"确定\") 精确形态",
        )
    }

    @Test
    fun `enter confirms and escape dismisses via preview key handler`() {
        val block = dialogBlock()
        assertTrue(
            "onPreviewKeyEvent" in block,
            "XA-05 守护：对话框必须挂 onPreviewKeyEvent 键盘面（Enter 确认/Esc 关闭）",
        )
        assertTrue(
            "Key.Enter, Key.NumPadEnter" in block,
            "XA-05 守护：Enter 须覆盖主键与 NumPadEnter（SubmitKey 同款双通道）",
        )
        assertTrue(
            "Key.Escape" in block,
            "XA-05 守护：Esc 必须接 onDismiss（键盘用户免 Tab 找取消钮）",
        )
        assertTrue(
            "KeyEventType.KeyDown" in block,
            "XA-05 守护：键处理必须 KeyDown 过滤（KeyUp 时 IME 组合未提交，SubmitKey KDoc 契约）",
        )
    }

    @Test
    fun `keyboard hint row present`() {
        val block = dialogBlock()
        assertTrue(
            "Enter 确认 · Esc 取消" in block,
            "XA-05 守护：卡底必须带键面提示行「Enter 确认 · Esc 取消」" +
                "（PathConfirmCard 逐字同款配方）",
        )
    }

    // ── 终验蜂群 P2 · XA-05 焦点初始化守护（2026-10-02）──

    @Test
    fun `dialog focus initialization double-fires request focus`() {
        // 焦点初始化三符号锚（device/DeviceSelectDialog.kt 函数区）：FocusRequester
        // 挂 text Column + LaunchedEffect 内双发 requestFocus（立即一发 + withFrameNanos
        // 下一帧补一发——🔴 CMP popup 可聚焦窗异步落成，单发稳定落空；无此初始化时
        // 对话框内无焦点组件，第一下 Enter/Esc 被丢弃）。删任一符号 = 键盘面静默失效。
        val block = dialogBlock()
        assertTrue(
            "dialogFocusRequester = remember { FocusRequester() }" in block,
            "XA-05 守护：对话框必须持有 FocusRequester（焦点初始化宿主——删后 Enter/Esc 无焦点落点）",
        )
        assertTrue(
            Regex("""runCatching \{ dialogFocusRequester\.requestFocus\(\) \}""").findAll(block).count() == 2,
            "XA-05 守护：requestFocus 必须双发（立即 + 下一帧——CMP popup 异步落成单发稳定落空，" +
                "CommandPalette.kt 双发配方）",
        )
        assertTrue(
            "androidx.compose.runtime.withFrameNanos { }" in block,
            "XA-05 守护：双发之间必须有 withFrameNanos 等帧（双发时序锚——删掉即退化单发）",
        )
        assertTrue(
            ".focusRequester(dialogFocusRequester)" in block && ".focusable()" in block,
            "XA-05 守护：text Column 必须挂 focusRequester + focusable（键盘裁决节点 = preview 链可达前提）",
        )
    }
}
