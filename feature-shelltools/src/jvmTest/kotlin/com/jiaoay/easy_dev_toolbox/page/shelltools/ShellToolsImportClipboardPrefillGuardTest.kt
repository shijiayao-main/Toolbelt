package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 导入对话框自动读剪贴板源码锚守护。
 *
 * 验收面两条：
 * 1. 复制合法 JSON 后打开导入对话框，文本框已预填，一键导入
 *    （clipboard.getText() 在 remember 初始化内——打开即读、非空才预填）；
 * 2. 剪贴板为空时预填空串、行为与现状一致
 *    （takeIf { it.isNotBlank() } ?: "" 兜底形态）。
 *
 * 附带清账：管理行钮名「从剪贴板导入」→「导入自定义命令」
 * （剪贴板动线已自动化，去「从剪贴板」限定、对齐对话框标题）——旧名残留即红。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellToolsImportClipboardPrefillGuardTest {

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

    private fun dialogsSource(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/list/custom/ShellToolsGroupDialogs.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private fun pageSource(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `dialog json field prefills from clipboard on open`() {
        val src = dialogsSource()
        // 锚：remember 初始化内含 clipboard.getText()（打开即读——非按钮触发）
        val jsonIndex = src.indexOf("var json by remember")
        assertTrue(jsonIndex >= 0, "SH-14 守护：json 状态声明锚缺失（重构后请同步本守护）")
        val initBlock = src.substring(jsonIndex, minOf(src.length, jsonIndex + 300))
        assertTrue(
            "clipboard.getText()" in initBlock,
            "SH-14 守护：json 初始值必须读剪贴板（clipboard.getText() 于 remember 初始化内" +
                "——复制后打开即预填，双重动线归一）",
        )
        assertTrue(
            "takeIf { it.isNotBlank() } ?: \"\"" in initBlock,
            "SH-14 守护：空剪贴板/纯空白必须兜底空串（takeIf ?: \"\"）",
        )
    }

    @Test
    fun `paste button retained for mid-flow copy`() {
        val src = dialogsSource()
        assertTrue(
            "从剪贴板粘贴" in src,
            "SH-14 守护：「从剪贴板粘贴」钮必须保留（覆盖打开对话框后中途再复制的场景）",
        )
    }

    @Test
    fun `page button label renamed to import custom commands`() {
        val src = pageSource()
        assertFalse(
            "从剪贴板导入" in src,
            "SH-14 守护：管理行旧钮名「从剪贴板导入」必须清零（动线已自动化，" +
                "改「导入自定义命令」对齐对话框标题）",
        )
        assertTrue(
            "导入自定义命令" in src,
            "SH-14 守护：管理行钮名必须为「导入自定义命令」",
        )
    }
}
