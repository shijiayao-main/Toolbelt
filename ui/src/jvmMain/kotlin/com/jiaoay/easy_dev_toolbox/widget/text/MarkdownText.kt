package com.jiaoay.easy_dev_toolbox.widget.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens

/**
 * 轻量 markdown 渲染件：`#/##` 标题、`-` 列表、正文、空行四类行
 * 的分层渲染——**非通用 markdown 引擎**。
 *
 * ## 格式闭集（唯一消费面 = 语音纪要审阅卡）
 *
 * [com.jiaoay.easy_dev_toolbox.core.llm.agent.QuestAbilityPrompts.VOICE_MINUTES_SECTION]
 * 三段纪要格式约束的产出形态：
 *
 * ```
 * # 纪要
 * ## 要点
 * - 第一条要点
 * - 第二条要点
 * ## 待办
 * ## 结论
 * ```
 *
 * 即 `# `（一级标题）+ `## `（二级标题）+ `- `（列表项）+ 普通正文行 + 空行——闭集
 * 恰四类；嵌套列表/代码块/链接/表格等 markdown 能力一律不解析（原文按正文行呈现，
 * 不吞内容）。纪要是自家 prompt 约束下的产出（非自由输入），闭集覆盖即全量覆盖。
 *
 * ## 结构
 * - 解析层 [parseMarkdownBlocks]：**纯函数**（String → List<[MdBlock]>），同包直测缝
 *   （MarkdownTextTest 钉死四类行闭集），无 Compose 依赖；
 * - 渲染层 [MarkdownText]：块序列 → `# ` titleMedium / `## ` titleSmall /
 *   `- ` bullet 行（"·" + bodyMedium）/ 正文 bodyMedium / 空行段距。渲染经
 *   [remember] 缓存解析结果（同输入零重复解析）。
 *
 * 消费面：feature-voice 审阅卡；「查看原文」mono 态由消费方切换
 * （核验原文 vs 纪要双态），本件不持切换态。
 *
 * @param markdown 源文本（VOICE_MINUTES_SECTION 闭集格式）
 * @param modifier 修饰符
 * @param paragraphSpacing 段间距（默认 space4——块间距小一档，列表行内更紧凑）
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    paragraphSpacing: Dp = Dimens.space4,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(paragraphSpacing),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading1 -> Text(
                    block.text,
                    style = MaterialTheme.typography.titleMedium,
                )

                is MdBlock.Heading2 -> Text(
                    block.text,
                    style = MaterialTheme.typography.titleSmall,
                )

                is MdBlock.Bullet -> Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                ) {
                    // 列表符走正文色（与正文同源——bullet 是结构标记非独立语义色）
                    Text("·", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Paragraph -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodyMedium,
                )

                MdBlock.BlankLine -> Unit // 空行 = 段距（spacedBy 承载，零占位渲染）
            }
        }
    }
}

/**
 * markdown 块模型（[parseMarkdownBlocks] 产出——格式闭集四类，非通用引擎）：
 * [Heading1]（`# `）/ [Heading2]（`## `）/ [Bullet]（`- `）/ [Paragraph]（正文行）/
 * [BlankLine]（空行——渲染层转段距，不产文本）。
 */
sealed interface MdBlock {
    /** `# ` 一级标题（VOICE_MINUTES_SECTION 的「纪要」总题） */
    data class Heading1(val text: String) : MdBlock

    /** `## ` 二级标题（「要点 / 待办 / 结论」三段题） */
    data class Heading2(val text: String) : MdBlock

    /** `- ` 列表项（要点/待办/结论条目——标记剥离，文本单存） */
    data class Bullet(val text: String) : MdBlock

    /** 正文行（非闭集标记的普通行——含被误写的自由文本，原文呈现不吞内容） */
    data class Paragraph(val text: String) : MdBlock

    /** 空行（渲染层转段距） */
    data object BlankLine : MdBlock
}

/**
 * markdown 块解析（**纯函数**——无 Compose 依赖，同包直测缝）。
 *
 * 行级扫描：`# ` → [MdBlock.Heading1]、`## ` → [MdBlock.Heading2]、`- ` →
 * [MdBlock.Bullet]、空行 → [MdBlock.BlankLine]、其余 → [MdBlock.Paragraph]。
 * 仅识别行首标记（首空格后全部按文本，`#`/`-` 无后续空格不视为标记——按正文呈现）；
 * 三级及以上标题（`###`）不在闭集内，按正文行呈现不吞内容。
 */
internal fun parseMarkdownBlocks(markdown: String): List<MdBlock> =
    markdown.lineSequence().map { line ->
        when {
            line.isBlank() -> MdBlock.BlankLine
            line.startsWith("# ") -> MdBlock.Heading1(line.removePrefix("# ").trim())
            line.startsWith("## ") -> MdBlock.Heading2(line.removePrefix("## ").trim())
            line.startsWith("- ") -> MdBlock.Bullet(line.removePrefix("- ").trim())
            else -> MdBlock.Paragraph(line)
        }
    }.toList()
