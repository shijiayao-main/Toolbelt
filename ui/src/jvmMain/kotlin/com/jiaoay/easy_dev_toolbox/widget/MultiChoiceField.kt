package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 多选 chips 字段（Quest 域 change 2——openspec add-flow-page 任务 3.1；ChoiceField 的
 * FilterChip 流分支平移多选语义）。
 *
 * 与 [ChoiceField] 的分工：单选走 SegmentedControl/chip 单值；本组件选中集是
 * `Set<String>`（toggle 进出），供流程页能力挑选等「多选确认」场景。选中文案
 * 带勾选前导图标（ChoiceField ≥4 项分支同款）。
 *
 * 零白名单：间距 [Dimens]、选中色 [LocalEasyToolboxColors]，无裸 `.dp`。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiChoiceField(
    label: String?,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    labels: Map<String, String> = emptyMap(),
) {
    val colors = LocalEasyToolboxColors.current
    fun displayOf(option: String): String = labels[option] ?: option
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        if (label != null) Text(text = label, style = MaterialTheme.typography.bodySmall)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            options.forEach { option ->
                val isSelected = option in selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggle(option) },
                    // 选中态 = secondary 实底 + onSecondary 字（M3 选中 chip 原生槽对，同 ChoiceField）
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                    shape = AppShapes.capsule, // 轻标签胶囊档（M3 默认 8dp 角显式归一）
                    label = { Text(displayOf(option), style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = if (isSelected) {
                        {
                            AppSymbolIcon(
                                icon = MaterialSymbols.CHECK,
                                contentDescription = null,
                                size = Dimens.iconSmall,
                                // 校验图标随字同色（onSecondary）
                                tint = MaterialTheme.colorScheme.onSecondary,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
