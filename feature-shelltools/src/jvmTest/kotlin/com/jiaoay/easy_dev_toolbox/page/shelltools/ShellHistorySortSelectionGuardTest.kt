package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 历史多选执行序保障源码锚守护。
 *
 * 多选中切排序会让 [ShellToolsViewModel.executeSelectedHistory] 的
 * 执行序随 displayHistory 重排静默改变（勾选集合锚定展示序）——三面防线：
 * ① VM setHistorySortMode 多选时清空勾选（行为断言见
 * [ShellToolsViewModelSortSelectionTest]，此处守 UI 装配面）；
 * ② 排序段多选时禁用 + 置灰原因 tooltip（「禁止静默
 *    置灰」纪律在 SegmentedControl 上的承接形态——无该参数故 hover 气泡承接）；
 * ③ 执行条「串行执行选中项（N）」数量常显（数量只在「已选 N 项」处可见不够）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellHistorySortSelectionGuardTest {

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

    private fun panelFile(): File {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/history/ShellHistoryPanel.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file
    }

    private fun source(): String = panelFile().readText()

    @Test
    fun `sort segmented row is disabled while selection mode is active`() {
        val src = source()
        assertTrue(
            "SortModeSegmentedRow" in src,
            "SH-07 守护：排序段应经 SortModeSegmentedRow 承载（selectionMode 禁用逻辑的锚点组件）",
        )
        // enabled 参数须由 selectionMode 派生——写死 true / 缺参数即禁用语义脱钩
        val enabledLine = src.lineSequence().firstOrNull { it.trim().startsWith("enabled = !selectionMode") }
        assertTrue(
            enabledLine != null,
            "SH-07 守护：SegmentedControl 的 enabled 必须写 `enabled = !selectionMode`（多选即禁用；" +
                "缺该行 = 多选中可切排序，执行序保障失守）",
        )
    }

    @Test
    fun `disabled sort row carries reason tooltip not silent grey`() {
        val src = source()
        // 「禁止静默置灰」：多选禁用态必须带 tooltip 说明原因与解除方式
        assertTrue(
            "多选中切换排序将清空已选" in src,
            "SH-07 守护：置灰原因 tooltip 文案锚缺失（静默哑按钮是 AppButton disabledReason 纪律明令禁止）",
        )
        // tooltip 须经 hover 驱动（HoverTooltipBox 配方单源），且只在 selectionMode 时展示
        // （气泡驱动逻辑已单源化到 :ui 共享件，本锚钉消费点的 showWhen 门控形态）
        assertTrue(
            "showWhen = selectionMode" in src,
            "SH-07 守护：tooltip 须 hover 且仅多选态展示（非多选时排序可用，不得误弹禁用说明）",
        )
        // 🔴 挂载锚：interactionSource 必须真正挂载到组件上——
        // 只 collect 不挂载是死接线（hovered 恒 false → tooltip 永不显示，
        // 「hovered && selectionMode」文本锚在、功能死，此锚防死接线回潮）
        assertTrue(
            ".hoverable(interactionSource)" in src,
            "SH-07 守护：interactionSource 必须经 .hoverable(interactionSource) 挂载到" +
                "排序段容器（hoverable 挂控件容器覆盖整个禁用面，是 tooltip 驱动的唯一通路；" +
                "只创建/collect 不挂载 = 死接线，tooltip 永不显示）",
        )
    }

    @Test
    fun `selection action bar always shows selected count in execute label`() {
        val src = source()
        // 数量常显锚：执行钮文案插值 selectedCount（写死字面量 0/缺插值即回潮）
        val labelLine = src.lineSequence().firstOrNull { it.contains("串行执行选中项（\$selectedCount）") }
        assertTrue(
            labelLine != null,
            "SH-07 守护：执行条文案必须为「串行执行选中项（\$selectedCount）」数量常显形态" +
                "（无插值形态数量仅在「已选 N 项」处可见，回潮即本断言挂）",
        )
        // 无数量文案形态不得残留
        assertTrue(
            src.lineSequence().none { it.contains("\"串行执行选中项\"") },
            "SH-07 守护：无数量文案「串行执行选中项」不得回潮",
        )
    }

    @Test
    fun `view model sort mode switch guards selection clearing`() {
        // VM 侧兜底断言（行为面见 ShellToolsViewModelSortSelectionTest；此处守
        // 源码形态防装配脱钩——未来新增排序入口不经面板时纵深防御仍在）
        val vmFile = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsViewModel.kt",
        )
        assertTrue(vmFile.isFile, "源码锚文件缺失：${vmFile.path}")
        val src = vmFile.readText()
        val fn = src.substringAfter("fun setHistorySortMode(").substringBefore("\n    }")
        assertTrue(
            "_historySelectionMode.value" in fn && "clearHistorySelection()" in fn,
            "SH-07 守护：setHistorySortMode 必须先查 _historySelectionMode 并 clearHistorySelection()" +
                "（兜底覆盖面板禁用之外的全部排序入口）",
        )
    }
}
