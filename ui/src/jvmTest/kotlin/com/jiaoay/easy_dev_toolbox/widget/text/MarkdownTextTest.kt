package com.jiaoay.easy_dev_toolbox.widget.text

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MarkdownText 轻量 markdown 渲染件验收断言（AG-16 · 重设计批 4 · 2026-10-01）：
 *
 * 格式闭集真源 = [com.jiaoay.easy_dev_toolbox.core.llm.agent.QuestAbilityPrompts.VOICE_MINUTES_SECTION]
 * （三段纪要：`# 纪要` + `## 要点/待办/结论` + `-` 列表）——本件是闭集渲染件非通用
 * markdown 引擎（方案 A 零新依赖，卡面推荐）。验收面三钉：
 *
 * 1. **解析闭集直测**：[parseMarkdownBlocks] 纯函数（验收前提——无 Compose 依赖可直测），
 *    `#/##/-`/正文/空行四类行 + 边界（无空格标记不识别、`###` 不在闭集按正文、标记剥离）；
 * 2. **编译可达**：本文件引用 [MarkdownText] 可组合项——签名改写/文件删除即 :ui:jvmTest
 *    编译失败（渲染分层 = 标题 titleMedium/titleSmall + 列表 "·" bullet 行 + 正文
 *    bodyMedium 的档位分配钉在渲染件内）；
 * 3. **配方源断言**：MarkdownText.kt 必须钉住「纯函数解析层 + remember 缓存 + 闭集
 *    四类行渲染 + 不持切换态」结构——防后续批接线时配方被顺手改动。
 *
 * （渲染层像素验证不落 :ui——GhostCardTest 同款口径：:ui 无 skiko 运行时；分层可见性
 *  由渲染件档位分配 + 消费面 feature-voice 侧接线断言承载。）
 */
class MarkdownTextTest {

    /** 编译可达探针：签名改写/文件删除即 :ui:jvmTest 编译失败（不执行，只编译） */
    @Composable
    private fun compileReachabilityProbe() {
        MarkdownText(
            markdown = "# 纪要\n\n## 要点\n- 编译可达探针",
            paragraphSpacing = Dimens.space4,
        )
        // MdBlock 模型同样钉进编译面（sealed 闭集——新增块类型须同步本测试闭集用例）
        @Suppress("UNUSED_EXPRESSION")
        MdBlock.BlankLine
    }

    // ── 1. 解析闭集直测（四类行 + 标记剥离）──

    @Test
    fun `parse covers heading bullet paragraph and blank line closure`() {
        val markdown = """
            # 纪要
            ## 要点
            - 第一条要点
            正文行不带头标记
            ## 待办
            ## 结论
        """.trimIndent()

        assertEquals(
            listOf(
                MdBlock.Heading1("纪要"),
                MdBlock.Heading2("要点"),
                MdBlock.Bullet("第一条要点"),
                MdBlock.Paragraph("正文行不带头标记"),
                MdBlock.Heading2("待办"),
                MdBlock.Heading2("结论"),
            ),
            parseMarkdownBlocks(markdown),
            "四类行闭集：# → Heading1、## → Heading2、- → Bullet（标记剥离）、其余 → Paragraph",
        )
    }

    @Test
    fun `parse maps blank lines to blank line blocks`() {
        // 尾部换行产生末空行（lineSequence 语义）——BlankLine 一并产出，渲染层零占位
        assertEquals(
            listOf(
                MdBlock.Heading2("要点"),
                MdBlock.BlankLine,
                MdBlock.Bullet("空行分段"),
                MdBlock.BlankLine,
            ),
            parseMarkdownBlocks("## 要点\n\n- 空行分段\n"),
            "空行（含纯空白行）必须解析为 BlankLine（渲染层转段距——闭集第四类）",
        )
    }

    @Test
    fun `parse keeps non closure markers as plain paragraphs`() {
        // 闭集边界：无空格标记不识别（#注释/-无内容）；三级标题/有序列表不在闭集——
        // 原文按正文行呈现不吞内容（纪要是 prompt 约束产出，误写形态保底不丢字）
        val markdown = "#无空格不是标题\n### 三级不在闭集\n1. 有序列表不在闭集\n-无空格不是列表"
        assertEquals(
            listOf(
                MdBlock.Paragraph("#无空格不是标题"),
                MdBlock.Paragraph("### 三级不在闭集"),
                MdBlock.Paragraph("1. 有序列表不在闭集"),
                MdBlock.Paragraph("-无空格不是列表"),
            ),
            parseMarkdownBlocks(markdown),
            "闭集外形态必须按正文行原文呈现（不吞内容、不误解析）",
        )
    }

    @Test
    fun `parse strips marker and trims heading and bullet text`() {
        // trim 全向（非只剥标记后首段）——行内连续空格保留（文本单存不残留标记、
        // 不误吞内部空格）
        assertEquals(
            listOf(
                MdBlock.Heading1("内侧空格保留  外侧剥离"),
                MdBlock.Heading2("要点"),
                MdBlock.Bullet("条目"),
            ),
            parseMarkdownBlocks("#   内侧空格保留  外侧剥离  \n## 要点\n- 条目"),
            "标记剥离 + trim：标题/列表文本首尾空白归一，行内连续空格保留",
        )
    }

    @Test
    fun `parse of empty and blank only input yields blank lines only`() {
        // lineSequence 语义：串按换行切分（空串 = 1 空行；"\n\n" = 3 空行）——
        // BlankLine 逐行对应，渲染层零占位
        assertEquals(
            listOf(MdBlock.BlankLine),
            parseMarkdownBlocks(""),
            "空串 → 单 BlankLine（lineSequence 恰一行空行；渲染零占位）",
        )
        assertEquals(
            listOf(MdBlock.BlankLine, MdBlock.BlankLine, MdBlock.BlankLine),
            parseMarkdownBlocks("\n\n"),
            "纯空行输入 → BlankLine 序列（按换行切分数逐行对应，无文本块）",
        )
    }

    @Test
    fun `parse handles voice minutes full closure sample`() {
        // 格式闭集真源样例（VOICE_MINUTES_SECTION 三段纪要产出形态）端到端走查：
        // 解析后标题层/列表层/正文层分层可见的先决条件 = 块序列结构正确
        val markdown = """
            # 纪要
            ## 要点
            - 会议讨论了版本发布计划
            - 确认了两项阻塞性问题
            ## 待办
            - 明日同步修复进展
            ## 结论
            发布窗口保持不变，先解决阻塞项
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)
        assertEquals(8, blocks.size, "样例块数 = 8（1 总题 + 3 段题 + 3 列表 + 1 结论正文）")
        assertTrue(
            blocks[0] is MdBlock.Heading1 && (blocks[0] as MdBlock.Heading1).text == "纪要",
            "首块必须是「纪要」总题（Heading1）",
        )
        assertEquals(
            3,
            blocks.count { it is MdBlock.Heading2 },
            "三段题（要点/待办/结论）必须是 Heading2",
        )
        assertEquals(
            3,
            blocks.count { it is MdBlock.Bullet },
            "三条列表项必须是 Bullet（要点 2 + 待办 1）",
        )
        assertTrue(
            blocks.last() is MdBlock.Paragraph,
            "结论正文行必须是 Paragraph（无 - 标记的收束句）",
        )
    }

    // ── 2. 配方源断言（解析纯函数 + remember 缓存 + 闭集档位 + 不持切换态）──

    @Test
    fun `renderer recipe stays pinned to closure tiers and pure parse layer`() {
        val source = markdownTextSource()
        listOf(
            // 解析层纯函数 + remember 缓存（渲染件结构钉死）
            "remember(markdown) { parseMarkdownBlocks(markdown) }" to
                "解析必须纯函数 + remember(markdown) 缓存（同输入零重复解析）",
            // 闭集四类行 → 排版档分配（分层可见性的档位真源）
            "MaterialTheme.typography.titleMedium" to "# 标题档必须 titleMedium（卡面方案 A 配方）",
            "MaterialTheme.typography.titleSmall" to "## 标题档必须 titleSmall（卡面方案 A 配方）",
            "MaterialTheme.typography.bodyMedium" to "正文/列表档必须 bodyMedium（卡面方案 A 配方）",
            // 列表 bullet 行结构（"·" + 正文同档）
            "Text(\"·\", style = MaterialTheme.typography.bodyMedium)" to
                "列表符必须 · + bodyMedium 同档（结构标记非独立语义色）",
            "verticalAlignment = Alignment.Top" to "bullet 行必须顶对齐（多行条目悬挂缩进语义）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in source,
                "MarkdownText.kt 丢失配方要素「$needle」——$why",
            )
        }
    }

    @Test
    fun `markdown text stays a closure renderer not a general engine`() {
        val source = stripComments(markdownTextSource())
        // 闭集纪律：不引通用 markdown 三方（方案 B 已否决——零新依赖铁律）；
        // 闭集外能力（代码块/链接/表格）不解析
        listOf(
            "multiplatform-markdown-renderer" to "禁引 markdown 三方库（卡面方案 B 已否决——零新依赖）",
            "```" to "代码块围栏不在闭集（纪要格式无代码块——原文行呈现即可）",
            "](" to "链接语法不在闭集（纪要格式无链接——原文行呈现即可）",
        ).forEach { (needle, why) ->
            assertFalse(
                needle in source,
                "MarkdownText 不得实现闭集外能力「$needle」——$why",
            )
        }
    }

    // ── 源码锚与注释剥离 ──

    private fun markdownTextSource(): String {
        val root = locateProjectRoot()
        val file = File(root, MARKDOWN_TEXT_PATH)
        assertTrue(file.isFile, "$MARKDOWN_TEXT_PATH 不存在——AG-16 新件被移动须同步本测试路径")
        return file.readText()
    }

    /** 定位项目根（自测试工作目录向上找 settings.gradle.kts——GhostCardTest 同款配方） */
    private fun locateProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("未定位项目根（settings.gradle.kts）——:ui:jvmTest 工作目录异常")
    }

    /** 剥 KDoc/行注释/块注释（字符串内容保留——本断言面只关心 API 调用） */
    private fun stripComments(source: String): String = buildString {
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }

                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) {
                        if (source[i] == '\n') append('\n')
                        i++
                    }
                    i += 2
                }

                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

    private companion object {
        const val MARKDOWN_TEXT_PATH =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/text/MarkdownText.kt"
    }
}
