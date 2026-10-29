package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.page.ToolPage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * navItem/dock desc 一致性守护：desc 是 Ctrl+K palette 命中面
 * （CommandPalette 按 label/desc/keywords 子串过滤——desc 缺词 = 搜该词搜不到页）
 * 与 dock 悬停指引面，失真即搜索直达断链 + 指引误导。
 *
 * 守护两族断言（两 desc 位点曾各持字面量互相漂移，故全部收敛单源常量）：
 * 1. Tools：dock [DOCK_ENTRIES].hint 与 ToolPageRegistry navItem.desc 两面
 *    与 [TOOLS_DESC] 单源逐字同文，且 desc 全包含 ToolsPage
 *    TOOLS_TAB_LABELS_FULL 真源全部子签标签——真源动态解析，改子签清单
 *    不改 desc 即红；
 * 2. 设备观测：两面与 [DEVICE_OBS_DESC] 单源逐字同文，且 desc 含实装成员
 *    关键词（logcat/投屏/性能/按键/文件/应用/布局——漏词 = 该能力搜索不可达）。
 *
 * 与 ToolsCompactTabGuardTest 分工不撞：彼锚 ToolsPage 本体短名/全名表形态，
 * 本锚 desc 位点对真源清单的全包含。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :composeApp:jvmTest --rerun-tasks --no-build-cache`。
 */
class NavDescConsistencyGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun sourceFile(relativePath: String): File {
        val file = File(projectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file
    }

    /** 从 ToolsPage 源码解析 TOOLS_TAB_LABELS_FULL 全部子签标签（真源动态锚） */
    private fun toolsTabLabels(): List<String> {
        val text = sourceFile(
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/ToolsPage.kt",
        ).readText()
        val declaration = Regex("""TOOLS_TAB_LABELS_FULL\s*=\s*listOf\(([^)]*)\)""").find(text)
        assertNotNull(declaration, "desc 守护：TOOLS_TAB_LABELS_FULL 真源锚缺失（改名/移动后请同步本守护）")
        val labels = Regex(""""([^"]*)"""").findAll(declaration.groupValues[1])
            .map { it.groupValues[1] }
            .toList()
        assertTrue(
            labels.size >= 13,
            "desc 守护：子签真源解析异常（得 ${labels.size} 项，应 ≥ 13）——检查 TOOLS_TAB_LABELS_FULL 字面量形态",
        )
        return labels
    }

    @Test
    fun `tools desc is single-sourced and identical across nav item and dock entry`() {
        val navItem = ToolPageRegistry.navItems().firstOrNull { it.page == ToolPage.Tools }
        assertNotNull(navItem, "工具聚合页必须有 navItem（palette 候选可达性锚）")
        assertEquals(
            TOOLS_DESC,
            navItem.desc,
            "navItem.desc 必须与 TOOLS_DESC 单源逐字相同（desc 双源漂移 = palette 与 dock 指引不一致）",
        )
        assertEquals(
            TOOLS_DESC,
            DOCK_ENTRIES.first { it.page == ToolPage.Tools }.hint,
            "dock DOCK_ENTRIES.hint 必须与 TOOLS_DESC 单源逐字相同（desc 双源漂移 = palette 与 dock 指引不一致）",
        )
    }

    @Test
    fun `tools desc carries every tab label from the true source`() {
        val missing = toolsTabLabels().filter { it !in TOOLS_DESC }
        assertTrue(
            missing.isEmpty(),
            "TOOLS_DESC 缺子签标签 $missing——palette 搜该词无法命中工具聚合页；" +
                "改 TOOLS_TAB_LABELS_FULL 子签清单必须同步回写 TOOLS_DESC",
        )
    }

    @Test
    fun `device obs desc is single-sourced and carries live member keywords`() {
        val navItem = ToolPageRegistry.navItems().firstOrNull { it.page == ToolPage.DeviceObservation }
        assertNotNull(navItem, "设备观测空间必须有 navItem（palette 候选可达性锚）")
        assertEquals(
            DEVICE_OBS_DESC,
            navItem.desc,
            "navItem.desc 必须与 DEVICE_OBS_DESC 单源逐字相同（desc 双源漂移 = palette 与 dock 指引不一致）",
        )
        assertEquals(
            DEVICE_OBS_DESC,
            DOCK_ENTRIES.first { it.page == ToolPage.DeviceObservation }.hint,
            "dock DOCK_ENTRIES.hint 必须与 DEVICE_OBS_DESC 单源逐字相同（desc 双源漂移 = palette 与 dock 指引不一致）",
        )
        // 关键词锚：实装成员面（观测台四成员 + 复现编排）与三子页名——
        // 漏词 = palette 搜该词无法命中设备观测空间
        listOf("logcat", "投屏", "性能", "按键", "文件", "应用", "布局").forEach { keyword ->
            assertTrue(
                keyword in DEVICE_OBS_DESC,
                "DEVICE_OBS_DESC 缺实装关键词「$keyword」——palette 搜该词无法命中设备观测空间；" +
                    "空间成员/子页增改必须同步回写 DEVICE_OBS_DESC",
            )
        }
    }
}
