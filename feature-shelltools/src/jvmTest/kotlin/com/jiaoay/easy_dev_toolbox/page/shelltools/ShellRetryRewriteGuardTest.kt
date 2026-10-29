package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 重试链路重写守护：失败重试必须经 VM 统一重写层按当前 adb 路径/串号重写原串。
 *
 * 历史缺陷面：重试曾直连引擎提交已重写串（重写器对已重写串 no-op）——多设备切换
 * 后重试静默打到旧串号、超时被硬编码 30s 压扁、节点 loading 键丢失。本守护钉死
 * 三件事：①重试块内含 rewriteCommands（统一重写层）；②重试块消费原串与原超时口径；
 * ③引擎无 startIndex 引擎级暂存残留（起始步随任务携带，防止队列非空时污染无辜任务）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellRetryRewriteGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun sourceOf(relativePath: String): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/$relativePath",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    /** 函数切片：从签名行到下一个顶格 } */
    private fun functionSlice(src: String, signature: String): String {
        val lines = src.lines()
        val startLine = lines.indexOfFirst { it.contains(signature) }
        assertTrue(startLine >= 0, "函数切片锚失效：$signature（重构后请同步本守护）")
        val end = lines.drop(startLine).indexOfFirst { it.trim() == "}" && it.startsWith("}") }
        assertTrue(end > 0, "函数切片结束锚失效：$signature")
        return lines.drop(startLine).take(end + 1).joinToString("\n")
    }

    @Test
    fun `retry goes through rewrite layer with original commands preserved`() {
        val retryBlock = functionSlice(sourceOf("ShellToolsViewModel.kt"), "fun retryExecution()")
        // 统一重写层：重试必须按当前 adb 路径/串号重写（直连已重写串 = 旧串号烘死）
        assertTrue(
            "rewriteCommands(originals)" in retryBlock,
            "重试链路守护：retryExecution 必须经 rewriteCommands 重写原串（绕过重写层 = 旧串号/旧路径永久烘进重试）",
        )
        assertTrue(
            "failed.originalCommands.ifEmpty" in retryBlock,
            "重试链路守护：重试必须消费 ExecStatus.Failed.originalCommands（已重写串不可复用）",
        )
        assertTrue(
            "failed.timeoutMs" in retryBlock && "failed.trackingKey" in retryBlock,
            "重试链路守护：原超时口径与追踪键必须随重试保真（30s 硬编码会误杀长传输、trackingKey 丢失 = 节点 loading 失锚）",
        )
        assertTrue(
            "startIndex = startIndex" in retryBlock || Regex("""startIndex\s*=\s*startIndex""").containsMatchIn(retryBlock),
            "重试链路守护：重试必须从失败步继续（startIndex 随任务携带）",
        )
    }

    @Test
    fun `engine carries start index per task without pending staging field`() {
        val engineSrc = sourceOf("ShellExecutionEngine.kt")
        assertFalse(
            engineSrc.contains("pendingStartIndex"),
            "重试链路守护：引擎级 pendingStartIndex 暂存必须退役（队列非空时无辜任务被跳步污染）",
        )
        assertTrue(
            Regex("""val startIndex = task\.startIndex""").containsMatchIn(engineSrc),
            "重试链路守护：起始步必须从 QueuedCommand.startIndex 读取（随任务携带）",
        )
        assertTrue(
            "originalCommands = task.originalCommands" in engineSrc,
            "重试链路守护：失败落态必须透传原串给 ExecStatus.Failed（重试可信原串唯一来源）",
        )
        // 面板接线：重试经 VM（SH-01 统一执行链方向），不得直连引擎
        val panelSrc = sourceOf("output/ShellOutputPanel.kt")
        assertFalse(
            panelSrc.contains("retryLastFailure"),
            "重试链路守护：面板不得直连引擎重试（走 VM.retryExecution 统一重写层）",
        )
        assertTrue(
            "viewModel.retryExecution" in panelSrc,
            "重试链路守护：面板重试钮必须接线 viewModel.retryExecution",
        )
    }
}
