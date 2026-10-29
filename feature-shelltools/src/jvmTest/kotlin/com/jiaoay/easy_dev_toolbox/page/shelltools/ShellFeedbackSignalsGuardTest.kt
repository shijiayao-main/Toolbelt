package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 源码锚守护：导出/入队反馈接 StatusSignals。
 *
 * 守护契约 = 两处各接 StatusSignals.show 全局 toast：
 * ① 导出成功「输出已导出: ${target.name}」（JLog 保留；失败链路 appendStatus 保留）；
 * ② 批量入队末尾「已入队 ${commands.size} 条命令」（显展开后命令条数非选中条目数）。
 *
 * StatusSignals 是 :core 既有通道（feature-shelltools 已依赖 :core 零新增依赖），
 * 消费先例 feature-quest FlowViewModel。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellFeedbackSignalsGuardTest {

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

    private fun shelltoolsSource(relativePath: String): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/$relativePath",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `export success shows toast with file name`() {
        val src = shelltoolsSource("output/ShellOutputPanel.kt")
        assertTrue(
            """StatusSignals.show("输出已导出: ${'$'}{target.name}（${'$'}{items.size} 条）")""" in src,
            "SH-15① 守护：导出成功必须 StatusSignals.show 显目标文件名与条数" +
                "（退回静默 JLog-only 即回潮；条数 = 过滤口径可见量）",
        )
        assertTrue(
            "import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals" in src,
            "SH-15① 守护：ShellOutputPanel 必须显式 import StatusSignals（:core 通道）",
        )
        // 失败链路保留（appendStatus 回写状态提示行不被 toast 替代）
        assertTrue(
            "appendStatus(\"导出失败: " in src,
            "SH-15① 守护：导出失败链路 appendStatus 必须保留（失败走状态行非 toast，双通道各司其职）",
        )
        // JLog 保留（成功日志不因 toast 而删）
        assertTrue(
            "output exported to" in src,
            "SH-15① 守护：成功 JLog.d 必须保留（观测面不因 UI 反馈通道新增而丢失）",
        )
    }

    @Test
    fun `batch enqueue shows toast with command count`() {
        val src = shelltoolsSource("ShellToolsViewModel.kt")
        // executeSelectedHistory 函数体切片内含 StatusSignals.show（末尾入队反馈）
        val fnStart = src.indexOf("fun executeSelectedHistory()")
        assertTrue(fnStart >= 0, "SH-15② 守护：executeSelectedHistory 函数锚失效——签名漂移后请同步本守护")
        val fnBody = src.substring(fnStart, src.indexOf("fun upsertCustomTool", fnStart).takeIf { it > 0 } ?: src.length)
        assertTrue(
            Regex("""StatusSignals\.show\("已入队 \$\{commands\.size\} 条命令"\)""").containsMatchIn(fnBody),
            "SH-15② 守护：executeSelectedHistory 末尾必须 StatusSignals.show 显命令条数" +
                "（入队零反馈回潮 = 用户不知道命令已开始跑）",
        )
        assertTrue(
            "import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals" in src,
            "SH-15② 守护：ShellToolsViewModel 必须显式 import StatusSignals（:core 通道）",
        )
        // toast 位置锚：在 engine.submit 之后（先入队后反馈——顺序反了 = 假反馈）
        val submitIdx = fnBody.indexOf("engine.submit(")
        val toastIdx = fnBody.indexOf("StatusSignals.show(")
        assertTrue(
            submitIdx in 0 until toastIdx,
            "SH-15② 守护：入队 toast 必须在 engine.submit 之后（先入队后反馈——" +
                "submit 前发 toast = 命令未入队先报已入队，假反馈）",
        )
    }
}
