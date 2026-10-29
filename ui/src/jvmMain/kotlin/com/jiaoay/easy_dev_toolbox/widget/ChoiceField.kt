package com.jiaoay.easy_dev_toolbox.widget

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors

/**
 * 单选枚举选择器（从飞书桥接页下沉 `:ui`——性格模式/安全约束等跨域配置页共用）。
 *
 * 策略：**≤3 项**用 [SegmentedControl]（一屏可见、单击即选，桌面键鼠友好）；
 * **≥4 项**用 [FlowRow] + [FilterChip] 标签流（原 Column+Check 垂直列表
 * 选项多时须靠调用方封顶滚动——嵌套在页面滚动里体验差且不利扩展；chip 流式自动换行，
 * 档位增长零布局改动。消费方 AI 设置页全局档与桥接用户详情 per-user 档自动同形复用）。
 *
 * 展示文案来自 [labels]（声明式数据映射），缺失 label 时以 raw 值 fallback。
 * 零白名单：间距 [Dimens]、选中色 [LocalEasyToolboxColors]，无裸 `.dp` / 动画 / alpha。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChoiceField(
    label: String?,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    labels: Map<String, String> = emptyMap(),
) {
    val colors = LocalEasyToolboxColors.current
    // G-E：展示文案来自声明式字段表（Editor.Choice.labels），非 UI 硬编码；缺失 label 时以 raw 值 fallback。
    fun displayOf(option: String): String = labels[option] ?: option
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        if (label != null) Text(text = label, style = MaterialTheme.typography.bodySmall)
        if (options.size <= 3) {
            // ≤3 选项走本仓 SegmentedControl 单源（选中 surface-deep 底 + onSurface 字 + hairline）
            SegmentedControl(
                options = options.map { displayOf(it) },
                selectedIndex = options.indexOfFirst { it == selected }.coerceAtLeast(0),
                onSelect = { onSelect(options[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                verticalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelect(option) },
                        // 选中态 = secondary 实底 + onSecondary 字（M3 selected chip 的原生
                        // 槽对——容器/文字/前导图标三者同链，实底比 hotWash 洗底对比跳变大，
                        // 覆写套与非覆写套两态都由 M3 配套保证）
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
                                    // 校验图标随 label 同色（onSecondary）——图标与文字对
                                    // 同一实底，分色会出现图形档独走
                                    tint = MaterialTheme.colorScheme.onSecondary,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
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
