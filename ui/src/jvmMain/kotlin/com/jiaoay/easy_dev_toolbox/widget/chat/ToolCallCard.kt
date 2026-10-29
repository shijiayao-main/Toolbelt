package com.jiaoay.easy_dev_toolbox.widget.chat

import com.jiaoay.easy_dev_toolbox.core.data.pathFileName
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassPanel
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 工具调用卡（消息流核心件——OpenHands Action→Observation 双阶段同款）：
 *
 * - RUNNING：工具名 + argsSummary + 内联进度圈（progressInline）；
 * - COMPLETED：单行摘要 + observation 折叠展开（softSpring）+ artifacts chips；
 * - FAILED/CANCELLED：error/outline 色态 + 失败文案。
 *
 * 流式/高频更新零动画铁律：状态翻转不加过渡动画（消息出现零动画约定），
 *    折叠展开是唯一动效点（softSpring 与 SectionCard 折叠先例一致）。
 * 专属可视化：[ToolVisualizerRegistry] 命中则替渲染，未命中走本默认卡。
 * 工具名显示：[ToolDisplayNames.display] 单源中文化（E1——旧裸英文蛇形串退役）。
 */
@Composable
fun ToolCallCard(
    entry: ChatEntry.ToolCall,
    onOpenArtifact: (String) -> Unit,
) {
    val custom = ToolVisualizerRegistry.visualizerOrNull(entry.toolName)
    if (custom != null) {
        custom.Body(entry)
        return
    }
    DefaultToolCallCard(entry, onOpenArtifact)
}

@Composable
private fun DefaultToolCallCard(
    entry: ChatEntry.ToolCall,
    onOpenArtifact: (String) -> Unit,
) {
    val surface = LocalToolboxSurfaceColors.current
    var expanded by remember(entry.id) { mutableStateOf(false) }
    // 展开行 hover 洗底（selectionHoverWashColor 单源）+ indication=null
    val expandSource = remember(entry.id) { MutableInteractionSource() }
    val expandHovered by expandSource.collectIsHoveredAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            modifier = Modifier
                .fillMaxWidth()
                // 四状态格都是
                // panel 实底容器（panel-tint + r-md 12 + glass-brd 内描边 + panel-rim
                 // 顶缘——.panel 规格三件）——裸 Row 无容器无底色，容器三件补齐
                .padding(vertical = Dimens.space8, horizontal = Dimens.space10)
                .appGlassPanel(AppShapes.medium)
                .let { m ->
                    if (entry.observation != null) {
                        m
                            .background(selectionHoverWashColor(hovered = expandHovered), AppShapes.medium)
                            .clickable(interactionSource = expandSource, indication = null) { expanded = !expanded }
                    } else {
                        m
                    }
                },
        ) {
            when (entry.state) {
                ToolState.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(Dimens.progressInline))
                ToolState.COMPLETED -> AppSymbolIcon(
                    icon = MaterialSymbols.CHECK_CIRCLE,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                    tint = MaterialTheme.colorScheme.primary,
                )

                ToolState.FAILED -> AppSymbolIcon(
                    icon = MaterialSymbols.ERROR,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                    tint = MaterialTheme.colorScheme.error,
                )

                ToolState.CANCELLED -> AppSymbolIcon(
                    icon = MaterialSymbols.CANCEL,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                    tint = surface.onSurfaceFaint,
                )
            }
            Text(
                // E1 命名单源：工具名经 ToolDisplayNames 中文化（未登记兜底原始名）
                text = ToolDisplayNames.display(entry.toolName) ?: entry.toolName,
                style = DesktopTypography.mono.copy(fontWeight = FontWeight.Medium),
                color = surface.onSurfaceStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.argsSummary,
                style = MaterialTheme.typography.bodySmall,
                color = surface.onSurfaceMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (entry.observation != null) {
                AppSymbolIcon(
                    icon = if (expanded) MaterialSymbols.EXPAND_LESS else MaterialSymbols.EXPAND_MORE,
                    contentDescription = if (expanded) "收起" else "展开",
                    size = Dimens.iconSmall,
                    tint = surface.onSurfaceFaint,
                )
            }
        }

        // artifacts chips（产物精确路径——点击打开所在目录）
        if (entry.artifacts.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                modifier = Modifier.padding(top = Dimens.space4),
            ) {
                entry.artifacts.forEach { artifact ->
                    // 产物 chip hover 洗底（selectionHoverWashColor 单源）+ indication=null
                    val artifactSource = remember(entry.id, artifact.path) { MutableInteractionSource() }
                    val artifactHovered by artifactSource.collectIsHoveredAsState()
                    Text(
                        text = pathFileName(artifact.path),
                        style = DesktopTypography.monoTag,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .background(
                                selectionHoverWashColor(hovered = artifactHovered),
                                AppShapes.small,
                            )
                            .clickable(
                                interactionSource = artifactSource,
                                indication = null,
                            ) { onOpenArtifact(artifact.path) }
                            .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
                    )
                }
            }
        }

        // observation 折叠区（softSpring——唯一动效点）
        AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(animationSpec = AppMotion.softSpring()),
            exit = androidx.compose.animation.shrinkVertically(animationSpec = AppMotion.softSpring()),
        ) {
            Column(modifier = Modifier.padding(top = Dimens.space4)) {
                HorizontalDivider(color = surface.borderSubtle, thickness = Dimens.strokeThin)
                Text(
                    text = entry.observation ?: "",
                    style = DesktopTypography.mono,
                    color = surface.onSurfaceMid,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.space4),
                )
            }
        }
    }
}
