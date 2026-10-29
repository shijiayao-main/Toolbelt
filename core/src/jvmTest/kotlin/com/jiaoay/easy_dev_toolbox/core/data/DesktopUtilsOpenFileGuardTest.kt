package com.jiaoay.easy_dev_toolbox.core.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DesktopUtils.openFile 源码锚守护：
 *
 * 🔴 AWT Desktop 在无头/CI 环境行为面不可直测（本模块测试 JVM 无显示器——
 * Desktop.getDesktop() 依赖本机桌面会话），故落**配方源断言**：
 * 1. openFile 存在且打开文件本体（`Desktop.getDesktop().open(File(path))`——
 *    与 openInFolder 的 `open(dir)` 目录形态相对）；
 * 2. 失败降级链完整：onFailure 内 JLog 落盘 + openInFolder(path) 兜底调用
 *    （「openFile 失败时降级 openInFolder（一次 JLog + 兜底调用），避免
 *    不可用环境按钮全哑」——Linux 无 .md 关联时开目录不哑）。
 */
class DesktopUtilsOpenFileGuardTest {

    @Test
    fun `open file opens the file itself via desktop`() {
        val source = desktopUtilsSource()
        assertTrue(
            "fun openFile(path: String)" in source,
            "openFile 必须存在（『查看』/产物 chip 打开文件本体的承接点）",
        )
        assertTrue(
            "Desktop.getDesktop().open(File(path))" in source,
            "openFile 必须打开文件本体（File(path)——不是 openInFolder 的 open(parentDir) 目录形态）",
        )
        // openFile 与 openInFolder 两个函数并存（弱动作正名——目录诉求保留独立钮）
        assertTrue(
            "fun openInFolder(path: String)" in source,
            "openInFolder 必须保留（『打开所在目录』弱动作的承接函数——不得被 openFile 取代）",
        )
    }

    @Test
    fun `open file falls back to open in folder on failure with jlog`() {
        val source = desktopUtilsSource()
        // openFile 函数体切片：从 fun openFile 到文件尾（末函数）
        val openFileBody = source.substringAfter("fun openFile(path: String)", "")
        assertTrue(openFileBody.isNotEmpty(), "openFile 函数体切片锚缺失（重命名须同步本守护）")
        val onFailureBlock = openFileBody.substringAfter(".onFailure", "").substringBefore("    }\n}", "")
        assertTrue(
            onFailureBlock.isNotEmpty(),
            "openFile 必须 runCatching + onFailure 容错（不向 UI 抛）",
        )
        assertTrue(
            "JLog.e(TAG, e)" in onFailureBlock,
            "失败面必须 JLog 落盘（无头/无关联环境可排查——本仓日志纪律）",
        )
        assertTrue(
            "openInFolder(path)" in onFailureBlock,
            "失败必须降级 openInFolder(path)（兜底链：一次 JLog + 兜底调用，" +
                "避免不可用环境按钮全哑）",
        )
    }

    // ── 源码锚 ──

    private fun desktopUtilsSource(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/DesktopUtils.kt")
        assertTrue(file?.isFile == true, "源码锚文件缺失：core/data/DesktopUtils.kt（移动后请同步本测试）")
        return file.readText()
    }
}
