package com.jiaoay.easy_dev_toolbox.page.logworkbench

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 死代码防回潮守护（pickLogFile）：
 *
 * pickLogFile 曾是零调用点死代码（FileDropZone 自带 pickOpenFiles 选择器——
 * 文件选择面统一由 UI 层直调共享挂起选择器，不经 VM），已删。本守护钉死：
 * 1. WorkbenchViewModel.kt 不再含 `pickLogFile` 定义/调用（防回潮）；
 * 2. `pickOpenFiles` import 一并清退（当时该 import 仅 pickLogFile 消费——
 *    若后续 VM 重新需要文件选择，应经 UI 层选择后调 addTargetFile 注入，
 *    而非 VM 自持系统对话框）。
 *
 * 源码锚断言形态（NavTest substring 同族——功能面无 Compose 运行时，
 * 死代码删除只有源码切片可守护）。
 */
class WorkbenchDeadCodeGuardTest {

    @Test
    fun `workbench view model drops dead pick log file and its import`() {
        val source = workbenchViewModelSource()
        assertFalse(
            "pickLogFile" in source,
            "WorkbenchViewModel 不得复活 pickLogFile 死代码（文件选择统一由 " +
                "UI 层 FileDropZone/pickOpenFiles 直调，VM 不自持系统对话框）",
        )
        assertFalse(
            "pickOpenFiles" in source,
            "WorkbenchViewModel 不得 import pickOpenFiles（随 pickLogFile 一并清退——" +
                "VM 侧零消费点；选择器消费面在 UI 层）",
        )
    }

    @Test
    fun `workbench page keeps no dead pick log file reference`() {
        // Page 侧最小活锚：只钉 pickLogFile 零引用（删除面跨文件一致性）；
        // Page KDoc 失真守护不在此断言面（由 WorkbenchReportPanelRenderGuardTest
        // 的 d2 用例承担）。
        val source = logWorkbenchPageSource()
        assertFalse(
            "pickLogFile" in source,
            "LogWorkbenchPage 不得引用已删的 VM pickLogFile（删除面跨文件一致性）",
        )
    }

    // ── 源码锚 ──

    private fun sourceAt(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, relativePath)
        assertTrue(file?.isFile == true, "源码锚文件缺失：$relativePath（重命名/移动后请同步本测试）")
        return file.readText()
    }

    private fun workbenchViewModelSource(): String = sourceAt(
        "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchViewModel.kt",
    )

    private fun logWorkbenchPageSource(): String = sourceAt(
        "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt",
    )
}
