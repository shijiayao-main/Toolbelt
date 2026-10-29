package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 历史删除图标 CLOSE → DELETE 源码锚守护。
 *
 * 同域两处删除必须一套图标（工具树删除钮 ShellToolsChildNode.kt 用 DELETE）——
 * 历史删除钮归一 DELETE + error 色
 * （design-language §4.3 图标语义表：删除（危险）= delete）。
 *
 * 防回潮锚：删除钮块（contentDescription = "删除该条历史" 上下文）内不得
 * 再出现 CLOSE；多选「退出」钮 CLOSE 语义合法，不在清账面。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellHistoryDeleteIconGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")})")
    }

    private fun source(): String {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/history/ShellHistoryPanel.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `history delete button uses delete icon`() {
        val src = source()
        // 删除钮切片：以「删除该条历史」contentDescription 为锚向后找 icon 行
        val descIndex = src.indexOf("删除该条历史")
        assertTrue(descIndex >= 0, "SH-11 守护：历史删除钮 contentDescription 锚缺失（重构后请同步本守护）")
        val before = src.take(descIndex)
        val iconLine = before.lineSequence().lastOrNull { it.contains("MaterialSymbols.") }
        assertTrue(
            iconLine != null && "MaterialSymbols.DELETE" in iconLine,
            "SH-11 守护：历史删除钮图标必须为 MaterialSymbols.DELETE（对齐工具树删除钮；" +
                "实际锚到：${iconLine?.trim()}）",
        )
    }

    @Test
    fun `delete button block carries no close icon relapse`() {
        val src = source()
        // 防回潮：删除钮块内（pendingDeleteId 赋值 → HoverableRow actionContent 闭包）
        // 不得出现 CLOSE（CLOSE 残留即图标语义回潮；:92 多选退出钮的 CLOSE 不在此切片）
        val blockStart = src.indexOf("pendingDeleteId = item.id")
        val blockEnd = src.indexOf("删除该条历史")
        assertTrue(blockStart in 0 until blockEnd, "SH-11 守护：删除钮块切片失效（结构重构后请同步本守护）")
        val block = src.substring(blockStart, blockEnd + 200)
        assertTrue(
            "MaterialSymbols.CLOSE" !in block,
            "SH-11 守护：历史删除钮不得回潮 CLOSE（删除 = 破坏性语义归一 DELETE，" +
                "design-language.md §4.3 图标语义表）",
        )
    }
}
