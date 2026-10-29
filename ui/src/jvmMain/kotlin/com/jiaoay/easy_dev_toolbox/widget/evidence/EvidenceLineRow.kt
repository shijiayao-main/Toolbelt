package com.jiaoay.easy_dev_toolbox.widget.evidence

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.core.evidence.LogLine
import com.jiaoay.easy_dev_toolbox.core.evidence.contextLinesOf
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.ChipTextButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor

/**
 * 证据流单行渲染件（琥珀块行流）：三段解剖（ts + 级别 pill + msg + TAG chip），
 * 点击无时间戳行为零 affordance；±N 上下文展开走 AnimatedVisibility(softSpring)
 * ——交互件非流式件（ToolCallCard 折叠先例）。[onHighlightConsumed]：高亮行被
 * 点击（展开）即视为引用已消费，调用方清残留防「这行还在被引用」误判。
 */
@Composable
fun EvidenceLineRow(
    line: LogLine,
    evidenceFiles: List<String>,
    activeEvidence: String?,
    highlighted: Boolean,
    onTagFilter: (String) -> Unit,
    onHighlightConsumed: () -> Unit = {},
) {
    val colors = LocalEasyToolboxColors.current
    var expanded by remember(line.seq) { mutableStateOf(false) }
    // hover 洗底 primary×hoverWash（.evline 行悬停态）；enabled 与下方 clickable
    // 对齐——无时间戳行不可展开，hover 洗底即是假 affordance
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverSource, enabled = line.timestamp != null)
            .background(
                // 高亮压过 hover（档位与优先序单源 = selectionHoverWashColor）
                selectionHoverWashColor(hovered = hovered, selected = highlighted),
                shape = AppShapes.small,
            )
            .clickable(interactionSource = hoverSource, indication = null, enabled = line.timestamp != null) {
                expanded = !expanded
                // 高亮行被点击（展开动作）即视为高亮已消费——清残留
                if (highlighted) onHighlightConsumed()
            }
            .padding(horizontal = Dimens.space8, vertical = Dimens.space2),
    ) {
        // 三段解剖（.evline 结构：ts + 级别 pill + msg + tag chip）
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
            val ts = line.timestamp
            if (ts != null) {
                Text(
                    ts,
                    style = DesktopTypography.mono,
                    // .ts = on-surface-faint flex:none
                    color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                )
            }
            val level = line.level
            if (level != null && level in "EWIDV") {
                // 级别 pill（.lvl：fs-11 + r-sm 6——E/W 走语义 container 固定槽对，
                // 其余级别 surface-deep 灰底）。旧取色 logError×scheme.errorContainer
                // 浅 2.70/暗 2.61、logWarn×surfaceDeep 1.37——双双低于 AA 4.5:1，
                // 改槽对后由 SlotContrastGuardTest 在 token 单源处钉死
                Text(
                    level.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (level) {
                        'E' -> colors.errorFixed
                        'W' -> colors.warning
                        'I' -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> LocalToolboxSurfaceColors.current.onSurfaceFaint
                    },
                    modifier = Modifier
                        .background(
                            when (level) {
                                'E' -> colors.errorContainerFixed
                                'W' -> colors.warningContainer
                                else -> LocalToolboxSurfaceColors.current.surfaceDeep
                            },
                            AppShapes.small,
                        )
                        .padding(horizontal = Dimens.space6),
                )
            }
            Text(
                // msg 段（.msg = ellipsis；无结构行（message=null）回退 raw 全行）；
                // 正文恒 on-surface-var（级别色只出现在级别 pill 上）——hl 行整行文字转 on-surface
                line.message ?: line.raw,
                style = DesktopTypography.mono,
                color = when {
                    highlighted -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // TAG 一键过滤双通道（点 TAG chip → 写 Tag 正则〔下次研判生效〕
            // + 写行内搜索过滤词〔当前证据流就地 contains 过滤，即时可见〕）
            val tag = line.tag
            if (tag != null) {
                // TAG chip = ChipTextButton 单源承载：--surface 中性底（非 surfaceRaised）
                // + monoTag 字档 + 4×2 内边距经 textStyle/contentPadding 参数传入，
                // 形态与原手拼逐项同构（换件即得 hover 洗底 + indication=null）
                ChipTextButton(
                    label = tag,
                    onClick = { onTagFilter(tag) },
                    modifier = Modifier.padding(start = Dimens.space4),
                    container = LocalToolboxSurfaceColors.current.surface,
                    textStyle = DesktopTypography.monoTag,
                    contentPadding = PaddingValues(horizontal = Dimens.space4, vertical = Dimens.space2),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = AppMotion.softSpring()),
            exit = shrinkVertically(animationSpec = AppMotion.softSpring()),
        ) {
            // ±N 上下文异步取数：contextLinesOf 是 suspend IO（流式定位）——组合期直调
            // 会卡调用线程。null = 取数中；空表 = 取数完成无命中（两者提示文案区分）
            var contextLines by remember(line.seq) { mutableStateOf<List<String>?>(null) }
            LaunchedEffect(line.seq, evidenceFiles, activeEvidence) {
                contextLines = if (line.timestamp != null && activeEvidence != null) {
                    contextLinesOf(line.timestamp!!, evidenceFiles, activeEvidence, n = 5)
                } else {
                    emptyList()
                }
            }
            Column(modifier = Modifier.padding(start = Dimens.space12, top = Dimens.space2)) {
                when (val lines = contextLines) {
                    null -> Text(
                        "（上下文回读中…）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        lines.forEach { ctx ->
                            Text(
                                ctx,
                                style = DesktopTypography.mono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (lines.isEmpty()) {
                            Text(
                                "（上下文回读无命中——产物行间断档或文件不可读）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
