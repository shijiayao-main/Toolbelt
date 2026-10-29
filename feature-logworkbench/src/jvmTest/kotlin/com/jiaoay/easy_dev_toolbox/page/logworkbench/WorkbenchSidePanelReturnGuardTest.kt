package com.jiaoay.easy_dev_toolbox.page.logworkbench

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 输入表单回归页内左栏守护。
 *
 * 结构迁移面（Row 三栏化）无 Compose 运行时直测缝，源码切片是接线面的
 * 一等守护形态（WorkbenchReportPanelRenderGuardTest/NavTest substring 同族）：
 * - 左栏存在性：Row 内 `width(Dimens.workbenchSidePanelWidth)` + 页内
 *   `WorkbenchSidePanelContent()` 调用（死 token 复活为真实消费点）；
 * - 贡献退役：LogWorkbenchContributions 不再注册 SideBarContribution
 *   （inspector 对本页零贡献——PageHome isNotEmpty 门控天然不渲染）；
 * - 双滚动嵌套防线：外层左栏不得再包 verticalScroll（滚动归
 *   WorkbenchSidePanelContent 内部既有 Column）。
 */
class WorkbenchSidePanelReturnGuardTest {

    @Test
    fun `wb25 left column renders side panel content at workbenchSidePanelWidth`() {
        val source = logWorkbenchPageSource()
        assertTrue(
            "width(Dimens.workbenchSidePanelWidth)" in source,
            "左栏必须定宽 workbenchSidePanelWidth（死 token 复活为真实消费点）",
        )
        assertTrue(
            "WorkbenchSidePanelContent()" in source,
            "左栏必须页内直调 WorkbenchSidePanelContent（表单+run 列表回页内——不经 inspector 槽位）",
        )
    }

    @Test
    fun `wb25 outer left column does not wrap vertical scroll`() {
        val source = logWorkbenchPageSource()
        // 外层左栏 Column 区间切片：自左栏区注起到中栏区注止（注释含「verticalScroll」
        // 说明词，切片从 Column( 起避开——只断言 Column modifier 块本体）
        val leftColumn = source.substringAfter("── 左栏：输入表单 + run 列表", "")
        assertTrue(leftColumn.isNotEmpty(), "左栏区注锚缺失（重命名须同步本守护）")
        val columnSlice = leftColumn
            .substringAfter("Column(", "")
            .substringBefore("── 中栏", leftColumn)
        assertTrue(columnSlice.isNotEmpty(), "左栏 Column 锚缺失")
        assertFalse(
            "verticalScroll" in columnSlice,
            "外层左栏不得包 verticalScroll——WorkbenchSidePanelContent 内部自带滚动，双嵌套会冲突",
        )
    }

    @Test
    fun `wb25 contributions no longer register side bar`() {
        val source = logWorkbenchContributionsSource()
        assertFalse(
            "SideBarContribution(" in source,
            "LogWorkbenchContributions 必须退役 sideBar 贡献（inspector 不再显示工作台贡献——防回潮）",
        )
        assertFalse(
            "运行历史" in source,
            "侧栏槽位 label 文案随贡献一并退役",
        )
    }

    @Test
    fun `wb25 page no longer references inspector migration in layout`() {
        val source = logWorkbenchPageSource()
        assertFalse(
            "移入 Start 侧栏槽位" in source,
            "布局注释不得再称左栏在 inspector 槽位（实际在页内左栏）",
        )
    }

    @Test
    fun `wb25 deep link prefill lands in left column visible vm-backed form`() {
        // 深链预填左栏可见 = prefill 写 VM 态 + 左栏消费同一 VM（viewModel{} 同 key）。
        // 此处钉 VM 接线锚（行为面由 WorkbenchPrefillTest 直测背书）。
        val source = logWorkbenchPageSource()
        assertTrue(
            "vm.prefill(input.logFiles, input.prefillNote, input.targetTime)" in source,
            "深链消费必须经 vm.prefill 写表单态（预填后文件/症状/时间点在左栏可见）",
        )
    }

    @Test
    fun `wb25 960px minimum window floor keeps three columns feasible`() {
        // main.kt 窗口硬下限 960px——三栏可行性走查条件的代码级自证
        val source = mainKtSource()
        assertTrue(
            "window.minimumSize = java.awt.Dimension(960, 640)" in source,
            "窗口最小尺寸 960×640 硬下限必须保持（三栏布局的最窄窗前提）",
        )
    }

    private fun logWorkbenchPageSource(): String = sourceAt(
        "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt",
    )

    private fun logWorkbenchContributionsSource(): String = sourceAt(
        "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchContributions.kt",
    )

    private fun mainKtSource(): String = sourceAt(
        "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/main.kt",
    )

    private fun sourceAt(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, relativePath)
        assertTrue(file?.isFile() == true, "源码锚文件缺失：$relativePath（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
