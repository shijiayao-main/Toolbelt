package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * GoReportHook 消费链守护：
 *
 * 槽本体行为单测在 :core（GoReportHookTest）——本守护钉**宿主接线面**：
 * 1. AppViewModel init 必须包含 GoReportHook.wire（首个 AppViewModel 构造即接线——
 *    生产侧行钮 offer 恒可达；漏 wire = 行钮静默 no-op，UI 层不可见不可测）；
 * 2. feature-logworkbench WorkbenchSidePanelContent 必须包含 GoReportHook.offer
 *    消费点（「全部报告」行钮——run 列表与报告页浏览面互指）。
 *
 * 源码锚断言形态（NavTest substringBefore/After 同族——功能面无 Compose 运行时，
 * 行为经 :core 槽测试背书，接线面经源码切片钉死）。
 */
class GoReportHookWiringGuardTest {

    @Test
    fun `app view model wires go report hook in init`() {
        val source = appViewModelSource()
        assertTrue(
            "GoReportHook.wire { setCurrentPage(ToolPage.Report) }" in source,
            "AppViewModel init 必须接线 GoReportHook → 报告页（漏 wire = " +
                "「全部报告」行钮 offer 恒 false 静默 no-op）",
        )
    }

    @Test
    fun `workbench side panel row button offers go report hook`() {
        val source = logWorkbenchPageSource()
        assertTrue(
            "GoReportHook.offer()" in source,
            "WorkbenchSidePanelContent「全部报告」行钮必须经 GoReportHook.offer 跳报告页" +
                "（feature 不得直接 import 宿主导航——中立槽是唯一通道）",
        )
        assertTrue(
            "全部报告" in source,
            "「全部报告」行钮文案锚（用户可感知面——重命名须同步本守护）",
        )
    }

    private fun appViewModelSource(): String = sourceAt(
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/AppViewModel.kt",
    )

    private fun logWorkbenchPageSource(): String = sourceAt(
        "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt",
    )

    private fun sourceAt(relativePath: String): String {
        val file = File(locateProjectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
