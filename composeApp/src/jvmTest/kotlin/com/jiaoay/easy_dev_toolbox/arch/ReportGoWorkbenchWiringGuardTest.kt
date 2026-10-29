package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 报告卡「去工作台」runId 深链接线源码锚——ToolPageRegistry.kt 的 PageReport
 * 装配块（registrations() 内）。
 *
 * 失效形态：接线被删/被改后报告卡「去工作台」**静默退回旧行为**
 *（只切页不 restore run）——PageReport 有 `onGoWorkbench: (runId: String?) -> Unit
 * = {}` 默认空实现兜底，漏接线在编译期不可见、UI 层不可测（GoReportHookWiringGuardTest
 * 同款「漏 wire = 静默 no-op」失效形态），必须有源码锚。
 *
 * 钉死三锚（ToolPageRegistry.kt 装配块）：
 * 1. `onGoWorkbench = { runId ->` 双参回调形态（签名锚）；
 * 2. `LogWorkbenchDeepLink.offerRunReview(runId)`（runId 非 null 时 offer 回看深链）；
 * 3. `appViewModel.setCurrentPage(ToolPage.LogWorkbench)`（切页动作恒在——null 分支
 *    旧语义保留）。
 *
 * 源码锚断言形态（GoReportHookWiringGuardTest 同族——接线面经源码切片钉死，
 * 深链槽本体行为在 :feature-logworkbench LogWorkbenchDeepLinkTest 背书）。
 */
class ReportGoWorkbenchWiringGuardTest {

    @Test
    fun `page report registration wires run review deep link`() {
        val source = toolPageRegistrySource()
        // 装配块切片：ToolPage.Report 注册项的 PageReport(...) 实参区
        val block = source
            .substringAfter("ToolPageRegistration(page = ToolPage.Report")
            .substringBefore("ToolPageRegistration(page = ToolPage.Debug")
        assertTrue(
            "PageReport(" in block,
            "ToolPageRegistry Report 注册项必须装配 PageReport（装配块切片锚失效——" +
                "重构后请同步本守护切片边界）",
        )
        assertTrue(
            "onGoWorkbench = { runId ->" in block,
            "PageReport 装配必须传 onGoWorkbench 双参回调（漏接 = 报告卡" +
                "「去工作台」静默退回只切页旧行为——默认空实现兜底使编译期不可见）",
        )
        assertTrue(
            "LogWorkbenchDeepLink.offerRunReview(runId)" in block,
            "onGoWorkbench 的 runId 非 null 分支必须 offerRunReview 回看深链" +
                "（报告卡 → 工作台 restore 该 run 的唯一通道）",
        )
        assertTrue(
            "appViewModel.setCurrentPage(ToolPage.LogWorkbench)" in block,
            "onGoWorkbench 必须切页到工作台（null 分支旧语义 + offer 后落点）",
        )
    }

    private fun toolPageRegistrySource(): String {
        val file = File(
            locateProjectRoot(),
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/ToolPageRegistry.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
