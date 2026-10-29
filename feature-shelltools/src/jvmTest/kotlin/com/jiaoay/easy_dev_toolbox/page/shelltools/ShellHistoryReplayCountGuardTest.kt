package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 历史回放计数守护：单条回放必须写入历史计数（useCount+1 / Recent 浮顶）。
 *
 * 历史缺陷面：executeHistoryNormal 曾是全 VM 唯一不调 historyManager.add 的执行入口
 * ——「最常用」动线的 ×N 计数与 MostUsed/Recent 排序静默失效，靠回放使用的条目计数冻结。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellHistoryReplayCountGuardTest {

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

    @Test
    fun `single history replay writes usage count`() {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsViewModel.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}")
        val lines = file.readText().split('\n')
        val start = lines.indexOfFirst { it.contains("fun executeHistoryNormal(") }
        assertTrue(start >= 0, "executeHistoryNormal 切片锚失效——重构后请同步本守护")
        val end = lines.drop(start).indexOfFirst { it.trim() == "}" && it.startsWith("}") }
        assertTrue(end > 0, "executeHistoryNormal 切片结束锚失效")
        val block = lines.drop(start).take(end + 1).joinToString("\n")
        assertTrue(
            "historyManager.add(item)" in block,
            "回放计数守护：单条历史回放必须 historyManager.add(item)（漏写 = ×N 计数与 MostUsed/Recent 排序对回放动线静默失效）",
        )
    }
}
