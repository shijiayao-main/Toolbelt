package com.jiaoay.easy_dev_toolbox.page.tools.regex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard

/**
 * 正则测试器面板：「工具」聚合页子签内容体（聚合页原生签——无 palette 深入
 * 模式候选、无持久化，全部状态会话内）。
 *
 * 三段：正则输入（即时校验回执——无效给位置 + 修法提示，不静默）/ 样本文本 +
 * 命中高亮（命中区间着色）/ 分组捕获视图（分组编号 + 捕获内容，未参与分组明示）。
 *
 * 视觉层（高亮配色、行密度）为保守默认，待真机终裁。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegexTesterPanel() {
    var pattern by remember { mutableStateOf("") }
    var sample by remember { mutableStateOf("") }

    // 即时校验 + 命中计算（纯函数面，见 RegexTesterEngine；输入是状态直读，
    // remember 键即失效粒度）
    val compileResult = remember(pattern) { compileRegex(pattern) }
    val matches = remember(compileResult, sample) {
        val regex = (compileResult as? RegexCompileResult.Valid)?.regex
        if (regex == null) RegexMatches(emptyList(), truncated = false) else findMatches(regex, sample)
    }

    SectionCard(
        title = "正则",
        titleStyle = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        headerTrailing = {
            PillLabel(
                text = "java.util.regex 方言 · 即时校验",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        OutlinedTextField(
            value = pattern,
            onValueChange = { pattern = it },
            label = { Text("正则表达式") },
            placeholder = { Text("""如 \[(\w+)\]\s+(\d{2}:\d{2}:\d{2})""") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = DesktopTypography.monoInput,
        )
        when (val result = compileResult) {
            is RegexCompileResult.Empty -> Unit

            is RegexCompileResult.Invalid -> Text(
                text = result.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            is RegexCompileResult.Valid -> Text(
                text = buildString {
                    append("已编译 · ")
                    append(matches.matches.size)
                    append(" 处命中")
                    if (matches.truncated) append("（超过 $MAX_MATCHES 处已截断）")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SectionCard(
        title = "样本文本",
        titleStyle = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
    ) {
        OutlinedTextField(
            value = sample,
            onValueChange = { sample = it },
            label = { Text("待匹配文本（粘贴日志片段）") },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
            textStyle = DesktopTypography.monoInput,
        )
    }

    if (sample.isNotEmpty()) {
        SectionCard(
            title = "命中预览",
            titleStyle = MaterialTheme.typography.titleMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ),
            headerTrailing = {
                PillLabel(
                    text = "命中区间着色 · 零宽命中不着色",
                    container = LocalToolboxSurfaceColors.current.surfaceDeep,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            if (matches.matches.isEmpty()) {
                Text(
                    text = if (compileResult is RegexCompileResult.Valid) "0 处命中" else "正则无效，无可命中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val highlight = SpanStyle(
                    background = MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.hotWash),
                )
                Text(
                    text = highlightedSample(sample, matches.matches, highlight),
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (matches.truncated) {
                    Text(
                        text = "命中超过 $MAX_MATCHES 处已截断（预览与分组仅呈现截断前列表）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SectionCard(
            title = "分组捕获",
            titleStyle = MaterialTheme.typography.titleMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ),
        ) {
            when {
                matches.matches.isEmpty() -> Text(
                    text = "无命中，无可展示的分组捕获",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                matches.matches.first().groups.isEmpty() -> Text(
                    text = "正则不含捕获分组（...）——加上括号后此处按分组编号逐组展示捕获内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    if (matches.matches.size > RENDER_MATCH_ROW_CAP) {
                        Text(
                            text = "命中 ${matches.matches.size} 处，仅展示前 $RENDER_MATCH_ROW_CAP 处",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                        matches.matches.take(RENDER_MATCH_ROW_CAP).forEachIndexed { number, match ->
                            MatchRow(number = number + 1, match = match)
                        }
                    }
                }
            }
        }
    }
}

/** 命中行：序号 + 区间（半开呈现，零宽命中单列标记）+ 命中原文 + 分组捕获 */
@Composable
private fun MatchRow(number: Int, match: RegexMatch) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            Text(
                text = "#$number",
                style = DesktopTypography.monoTag,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (match.range.isEmpty()) {
                    "[${match.range.first}, ${match.range.first}) 零宽"
                } else {
                    "[${match.range.first}, ${match.range.last + 1})"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = match.value.ifEmpty { "（零宽命中）" },
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            match.groups.forEach { group ->
                Text(
                    text = if (group.matched) {
                        "分组${group.index}: ${group.value}"
                    } else {
                        "分组${group.index}: 未参与"
                    },
                    style = DesktopTypography.monoTag,
                    color = if (group.matched) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * 命中高亮注解串：非零宽命中区间着 [highlight] 底色。零宽命中无可见区间
 * （start == end 的注解 span 是空操作），着色跳过、分组捕获视图单列标记。
 */
private fun highlightedSample(
    sample: String,
    matches: List<RegexMatch>,
    highlight: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    append(sample)
    matches.forEach { match ->
        if (!match.range.isEmpty()) {
            addStyle(highlight, match.range.first, match.range.last + 1)
        }
    }
}

/** 分组捕获渲染行上限（命中扫描上界见 [MAX_MATCHES]，此处是渲染面独立防线） */
private const val RENDER_MATCH_ROW_CAP = 100
