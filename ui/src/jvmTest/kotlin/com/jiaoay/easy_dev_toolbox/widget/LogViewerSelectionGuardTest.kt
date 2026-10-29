package com.jiaoay.easy_dev_toolbox.widget

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 查看器行文本可选中复制守护：窗口行流须进 SelectionContainer 组合
 * （「找到一行 → 带走」产出动线——证据流行流 / 会话消息流同款配方的查看器补位）。
 *
 * 渲染件无 Compose 运行时直测缝（:ui 无 skiko 运行时），源码切片是一等守护形态：
 *
 * - **整容器粒度**：SelectionContainer 包整个 LazyColumn（同款先例均为整容器
 *   包裹，非行级）；
 * - **包裹单点在 ViewerLineList 内**：Ready 与 Drifted（冻结内容）两条渲染路径
 *   共用同件，单点包裹两态同覆盖；
 * - **行 Text 渲染参数不动**：只加选中能力不改视觉（级别语义色 + 等宽排版
 *   保持原样，视觉深浅待真机终裁）。
 */
class LogViewerSelectionGuardTest {

    @Test
    fun `viewer line list wraps lazy column in selection container`() {
        val source = logViewerDialogSource()
        assertTrue(
            "import androidx.compose.foundation.text.selection.SelectionContainer" in source,
            "查看器须引入 SelectionContainer（行文本可选中复制的组合前提）",
        )

        val listBody = source.substringAfter("private fun ViewerLineList(")
        val selectionIdx = listBody.indexOf("SelectionContainer {")
        val lazyIdx = listBody.indexOf("LazyColumn(")
        assertTrue(selectionIdx >= 0, "ViewerLineList 须有 SelectionContainer 组合（行流可选中复制）")
        assertTrue(
            lazyIdx > selectionIdx,
            "SelectionContainer 须包住 LazyColumn（整容器粒度——证据流行流 / 会话消息流同款配方）",
        )
    }

    @Test
    fun `selection wrap keeps line text render params untouched`() {
        val listBody = logViewerDialogSource().substringAfter("private fun ViewerLineList(")
        listOf(
            "style = DesktopTypography.mono" to "行文本等宽排版（行号列位稳定的契约前提）",
            "?: levelColor(line.level)" to "级别语义色按行渲染（并行高亮未命中行的回落档，只加选中能力不改视觉）",
        ).forEach { (needle, why) ->
            assertTrue(needle in listBody, "ViewerLineList 行渲染要素「$needle」丢失——$why")
        }
    }

    private fun logViewerDialogSource(): String = sourceAt(
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/LogViewerDialog.kt",
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
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
