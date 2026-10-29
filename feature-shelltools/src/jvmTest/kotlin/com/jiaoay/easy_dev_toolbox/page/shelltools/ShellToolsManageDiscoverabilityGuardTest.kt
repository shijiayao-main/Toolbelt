package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 页头管理钮可发现性源码锚守护。
 *
 * 验收面两条：
 * 1. 悬停「更多」钮显示「管理自定义命令」tooltip 且不自动消失
 *    （M3 TooltipBox isPersistent = true——悬停阅读语义）；
 * 2. 首次进页管理行自动展开，四动作带图标可见
 *    （manageExpanded 初值 true + ADD/GROUPS/CONTENT_PASTE/FOLDER_OPEN 四图标）。
 *
 * 本卡不动 DropdownMenu，仍为内联展开行承载
 * （若 DropdownMenu 回潮即本守护红）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellToolsManageDiscoverabilityGuardTest {

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
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `manage button wrapped in persistent tooltip`() {
        val src = source()
        assertTrue(
            "TooltipIconButton(" in src,
            "SH-06 守护：「更多」钮必须走共享 TooltipIconButton（hover 语义提示）",
        )
        // 气泡配方（isPersistent 常驻 + 延迟 + 手动驱动）已单源化到 :ui 共享件——
        // 配方本体锚到共享件文件，本页只锚消费点形态
        val hoverTooltipFile = File(
            projectRoot(),
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/interactions/HoverTooltip.kt",
        )
        assertTrue(hoverTooltipFile.isFile, "源码锚文件缺失：${hoverTooltipFile.path}（重命名/移动后请同步本守护）")
        val hoverTooltipSrc = hoverTooltipFile.readText()
        assertTrue(
            "rememberTooltipState(isPersistent = true)" in hoverTooltipSrc,
            "SH-06 守护：hover 气泡配方必须 isPersistent = true（不自动消失——M3 默认 " +
                "1.5s 自动消失，悬停阅读语义）",
        )
        assertTrue(
            "管理自定义命令" in src,
            "SH-06 守护：tooltip 文案必须为「管理自定义命令」（动作域语义，非「更多」）",
        )
    }

    @Test
    fun `manage row expands on first entry`() {
        // 展开态上提 ShellToolsRuntime（进程级会话记忆，切页不丢）：
        // 锚 = Runtime 初值 true + 页面 collectAsState 订阅
        val src = source()
        assertTrue(
            "val manageExpanded by ShellToolsRuntime.manageExpanded.collectAsState()" in src,
            "SH-06 守护：页面必须从 ShellToolsRuntime.manageExpanded 收集展开态" +
                "（进程级记忆——PageHomeContent 即时切页下组合级保存必丢）",
        )
        val runtimeFile = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsRuntime.kt",
        )
        assertTrue(runtimeFile.isFile, "源码锚文件缺失：${runtimeFile.path}（重命名/移动后请同步本守护）")
        assertTrue(
            Regex("""manageExpanded:\s*MutableStateFlow<Boolean>\s*=\s*MutableStateFlow\(true\)""")
                .containsMatchIn(runtimeFile.readText()),
            "SH-06 守护：manageExpanded 初值必须为 true（首次进页管理行自动展开一次；进程级 MutableStateFlow 持有）",
        )
    }

    @Test
    fun `four manage actions carry leading icons`() {
        val src = source()
        val blockStart = src.indexOf("AnimatedVisibility(\n                visible = manageExpanded")
        assertTrue(blockStart >= 0, "SH-06 守护：管理展开行装配点缺失（重构后请同步本守护）")
        val block = src.substring(blockStart)

        // 四枚符号各一命中（icon = MaterialSymbols.X 精确形态，防空字符串误报）
        assertTrue("icon = MaterialSymbols.ADD," in block, "SH-06 守护：「新增自定义命令」须带 ADD 前导图标")
        assertTrue("icon = MaterialSymbols.GROUPS," in block, "SH-06 守护：「新建自定义分组」须带 GROUPS 前导图标")
        assertTrue("icon = MaterialSymbols.CONTENT_PASTE," in block, "SH-06 守护：「从剪贴板导入」须带 CONTENT_PASTE 前导图标")
        assertTrue("icon = MaterialSymbols.FOLDER_OPEN," in block, "SH-06 守护：「打开配置目录」须带 FOLDER_OPEN 前导图标")
    }
}
