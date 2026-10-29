package com.jiaoay.easy_dev_toolbox.core.log

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CrashReportDialog「打开日志文件夹」源码锚守护：
 *
 * AWT [java.awt.Desktop] 依赖本机桌面会话，本模块测试 JVM 无头不可直测开目录动作，
 * 故落配方源断言（同 core/data 的 DesktopUtilsOpenFileGuardTest 先例）：
 * 1. 第三钮存在且打开动作指向 [FileUtils.getLogDir] 的目录本体（非 parent 目录形态）；
 * 2. 自保护契约：按钮回调不得向 EDT 抛异常（EDT 未捕获异常会二次进入全局兜底），
 *    失败降级为按钮文案回显目录路径；
 * 3. show() 永不抛硬契约与既有两钮（复制诊断信息/关闭）不因本钮回归。
 */
class CrashReportDialogOpenLogDirGuardTest {

    @Test
    fun `open log folder button exists and targets getLogDir directory itself`() {
        val source = crashReportDialogSource()
        assertTrue(
            "JButton(\"打开日志文件夹\")" in source,
            "「打开日志文件夹」按钮必须存在（反馈方排障要完整日志文件，目录对用户是不可见心智）",
        )
        assertTrue(
            "FileUtils.getLogDir()" in source,
            "打开动作必须指向 FileUtils.getLogDir()（日志目录唯一真源）",
        )
        assertTrue(
            "Desktop.getDesktop().open(logDir)" in source,
            "必须打开日志目录本体（open(logDir)——不是 openInFolder 的 open(parentDir) 父目录形态）",
        )
        assertTrue(
            "if (!logDir.exists()) logDir.mkdirs()" in source,
            "目录缺失必须按需补建再打开（文件日志开关从未开启过时目录可能不存在，" +
                "按钮语义恒为「打开目录」）",
        )
    }

    @Test
    fun `open log folder action never throws and degrades to button text with path`() {
        val source = crashReportDialogSource()
        val buttonBody = source
            .substringAfter("JButton(\"打开日志文件夹\")", "")
            .substringBefore("val closeButton", "")
        assertTrue(buttonBody.isNotEmpty(), "按钮体切片锚缺失（重命名须同步本守护）")
        assertTrue(
            "catch (_: Throwable)" in buttonBody,
            "打开动作必须整体 try-catch 吞异常（EDT 回调抛异常会二次进入全局兜底）",
        )
        assertTrue(
            "打开失败：\${logDir.absolutePath}" in buttonBody,
            "失败必须降级为按钮文案回显目录绝对路径（保住用户自找日志的线索，不静默不抛）",
        )
    }

    @Test
    fun `show never-throws contract and existing buttons remain intact`() {
        val source = crashReportDialogSource()
        // show() 永不抛硬契约：外层 catch Throwable 降级 fallbackPrint（呈现通道不能向崩溃线程抛）
        assertTrue(
            "GraphicsEnvironment.isHeadless()" in source && "fallbackPrint(report)" in source,
            "show() 的无头降级与永不抛契约必须保留（呈现故障降级 stderr，报告内容不丢）",
        )
        assertTrue(
            "JButton(\"复制诊断信息\")" in source && "JButton(\"关闭\")" in source,
            "既有「复制诊断信息」「关闭」两钮不得因新增按钮回归",
        )
    }

    // ── 源码锚 ──

    private fun crashReportDialogSource(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/log/CrashReportDialog.kt")
        assertTrue(file?.isFile == true, "源码锚文件缺失：core/log/CrashReportDialog.kt（移动后请同步本测试）")
        return file.readText()
    }
}
