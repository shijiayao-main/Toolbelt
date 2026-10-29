package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 源码锚守护：输出台称谓统一「输出台」+ 空闲引导。
 *
 * design-language §4.2 词汇表（称谓单源）：Shell 输出区唯一规范名 =
 * 「输出台」，裸「输出」称谓禁用。Idle 态头部文案 =「输出台 · 暂无输出，执行命令或
 * 下方输入」（弱引导四要素轻量版，onSurfaceVariant labelMedium 样式不变）；
 * Running/Failed/Completed 三态文案不动（「完成」/「重试」/执行 label 均无裸「输出」）。
 *
 * 清账口径：「过滤输出关键字」「导出输出」是动作/宾语语义非面板称谓，
 * 保留不动（改「过滤输出台关键字」反而佶屈）——本锚用 `text = "输出",` 逗号收尾
 * 精确锚裸称谓（与「输出台」前缀区分）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellOutputPanelNamingGuardTest {

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

    private fun panelSource(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/output/ShellOutputPanel.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `idle header reads output console with onboarding hint`() {
        val src = panelSource()
        assertTrue(
            "输出台 · 暂无输出，执行命令或下方输入" in src,
            "SH-03 守护：Idle 态头部必须是「输出台 · 暂无输出，执行命令或下方输入」" +
                "（称谓单源 + 空闲弱引导——退回裸「输出」即违 design-language §4.2 词汇表）",
        )
    }

    @Test
    fun `no bare output label remains in panel`() {
        // 裸「输出」称谓锚：text = "输出",（逗号收尾——与「输出台」/「暂无输出，」等
        // 复合文案区分，剥注释后代码区断言）
        val src = panelSource()
        val codeOnly = src.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            Regex("""text = "输出",""").containsMatchIn(codeOnly).not(),
            "SH-03 守护：裸「输出」称谓字面量（text = \"输出\",）必须清零——" +
                "规范名唯一 =「输出台」；「过滤输出关键字」「导出输出」等动作/宾语复合词不算",
        )
    }
}
