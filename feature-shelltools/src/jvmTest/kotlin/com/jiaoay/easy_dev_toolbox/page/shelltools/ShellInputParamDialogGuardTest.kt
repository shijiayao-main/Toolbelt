package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Input 参数对话框收编 AppDialogDefaults + disabledReason 源码锚守护。
 *
 * InputParamDialog 不得再是裸对话框（Dialog + Card 手拼违 AppDialogDefaults
 * 家族纪律），执行钮不得 `Button(enabled = allFilled)` 静默置灰
 * （违 §4.7h「禁止静默置灰」）。
 *
 * 守护配方 = AlertDialog(AppDialogDefaults.shape/containerColor()/appDialogRim)
 * 标准配方（与编辑器/分组命名/跨组移动/导入/设备选择五座先例一致）+
 * AppButton disabledReason when（allFilled 折进分支，填齐返回 null 才可点——
 * 🔴 disabledReason 非 null 即置灰，enabled 双轨 = 永不可点）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellInputParamDialogGuardTest {

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
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/list/node/ShellToolsChildNode.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private fun dialogBlock(): String {
        val src = source()
        val start = src.indexOf("private fun InputParamDialog(")
        assertTrue(start >= 0, "SH-10 守护：InputParamDialog 函数缺失")
        return src.substring(start)
    }

    @Test
    fun `input dialog uses app dialog defaults recipe`() {
        val block = dialogBlock()
        assertTrue(
            "AppDialogDefaults.shape" in block,
            "SH-10 守护：Input 对话框外壳必须走 AppDialogDefaults.shape（本域唯一裸对话框收编）",
        )
        assertTrue(
            "AppDialogDefaults.containerColor()" in block,
            "SH-10 守护：Input 对话框必须走 AppDialogDefaults.containerColor()",
        )
        assertTrue(
            "appDialogRim()" in block,
            "SH-10 守护：Input 对话框必须挂 appDialogRim 发丝定缘（标准配方三件套）",
        )
    }

    @Test
    fun `bare dialog card shell retired`() {
        // 防回潮：InputParamDialog 内不得再有裸 Dialog(onDismissRequest + Card 手拼壳
        val block = dialogBlock()
        assertTrue(
            "Dialog(onDismissRequest" !in block,
            "SH-10 守护：Input 对话框裸 Dialog+Card 壳不得回潮（须走 AlertDialog 标准配方）",
        )
    }

    @Test
    fun `execute button carries disabled reason`() {
        val block = dialogBlock()
        assertTrue(
            "AppButton(" in block,
            "SH-10 守护：执行钮必须走 AppButton（内建 TooltipBox 承载置灰原因）",
        )
        assertTrue(
            "请填写全部参数" in block,
            "SH-10 守护：执行钮置灰原因文案锚缺失（禁止静默置灰）",
        )
        assertTrue(
            "else -> null" in block,
            "SH-10 守护：disabledReason when 必须有 else -> null（填齐后可点）",
        )
        // 双轨回潮锚：enabled = allFilled 与 disabledReason 并存 = 永不可点
        assertTrue(
            block.lineSequence().none { it.trim().startsWith("enabled = allFilled") },
            "SH-10 守护：enabled = allFilled 双轨不得回潮（AppButton disabledReason 非 null 即置灰）",
        )
    }

    @Test
    fun `input dialog carries focus init and keyboard adjudication`() {
        // 焦点/键盘锚：首参数框双发 requestFocus + Enter（填齐才执行）/Esc 裁决 +
        // 键面提示（对照 DeviceSelectDialog「无此初始化时第一下 Enter/Esc 被丢弃」框架行为）
        val block = dialogBlock()
        assertTrue(
            "FocusRequester()" in block && "focusRequester(firstParamFocus)" in block,
            "SH-10 守护：Input 对话框首参数框必须焦点初始化（双发配方）",
        )
        assertTrue(
            "onPreviewKeyEvent" in block && "Key.Escape" in block,
            "SH-10 守护：Input 对话框必须接 Enter/Esc 键盘裁决",
        )
        assertTrue(
            "全部填写完成后 Enter 执行" in block,
            "SH-10 守护：键面提示锚缺失（禁止无键面提示的隐形键盘裁决）",
        )
        assertTrue(
            "if (allFilled) {" in block,
            "SH-10 守护：Enter 执行必须门控 allFilled（未填齐 Enter 不触发执行）",
        )
    }
}
