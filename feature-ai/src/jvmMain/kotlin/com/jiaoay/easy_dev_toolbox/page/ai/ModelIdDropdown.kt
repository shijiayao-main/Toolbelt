package com.jiaoay.easy_dev_toolbox.page.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig

/**
 * 模型 ID 选择锚点（下拉为主形态）。
 *
 * 🔴 锚点必须 `readOnly = true`：否则存在失焦提交竞态——用户点开下拉（锚点获焦）
 *    → 选候选（onPick 写入新值）→ 菜单收起但锚点仍持焦、内部编辑文本仍是旧值
 *    → 再点别处失焦时用**旧文本**把刚选的新值覆盖回去（CommitTextField 的
 *    hasBeenFocused/isFocused 状态机）。只读锚点没有内部编辑文本，竞态无从谈起。
 *
 * 四态渲染（ModelCatalog）：
 * - Idle：菜单内是「拉取模型列表」动作项（apiKey 空则置灰 + 提示）；
 * - Loading：锚点显示「拉取中…」；
 * - Loaded：菜单内是候选列表（限高滚动）；
 * - Failed：菜单内是「重试拉取」+ 错误文案。
 * 任何状态下菜单底部都有「手动输入模型 ID」——兜底入口不随拉取状态消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelIdDropdown(
    config: LlmEndpointConfig,
    catalog: ModelCatalog,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (String) -> Unit,
    onFetch: () -> Unit,
    onManualInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
    ) {
        val anchorText = when (catalog) {
            is ModelCatalog.Loading -> "正在拉取模型列表…"
            else -> config.modelId.ifBlank { "点击选择模型" }
        }
        OutlinedTextField(
            value = anchorText,
            onValueChange = {},
            readOnly = true,
            modifier = modifier.fillMaxWidth().menuAnchor(
                // 只读锚点：无内部编辑文本，杜绝「失焦用旧文本覆盖刚选的新值」竞态
                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
            ),
            label = { Text("模型 ID", style = MaterialTheme.typography.bodySmall) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            supportingText = {
                Text(
                    text = when (catalog) {
                        is ModelCatalog.Loaded -> "共 ${catalog.modelIds.size} 个模型可用"
                        is ModelCatalog.Failed -> catalog.message
                        else -> "点击从端点拉取，或切换为手动输入"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
        // 浅色对比增强：容器色 surfaceDeep + 发丝描边——M3 默认
        // surfaceContainerHigh 浅色下与页面底色近同（盲像素审计实拍定罪）
        val surfaceColors = LocalToolboxSurfaceColors.current
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = surfaceColors.surfaceDeep,
            tonalElevation = 0.dp,
            border = BorderStroke(Dimens.glassStrokeHairline, surfaceColors.borderSubtle),
        ) {
            when (val c = catalog) {
                is ModelCatalog.Idle -> DropdownMenuItem(
                    text = { Text(if (config.apiKey.isBlank()) "需先填写 API Key 才能拉取" else "从端点拉取模型列表") },
                    onClick = {
                        onExpandedChange(false)
                        if (config.apiKey.isNotBlank()) onFetch()
                    },
                    enabled = config.apiKey.isNotBlank(),
                )

                is ModelCatalog.Loading -> DropdownMenuItem(
                    text = { Text("拉取中…") },
                    onClick = {},
                    enabled = false,
                )

                is ModelCatalog.Loaded -> {
                    // 候选列表限高：网关可能返回上百个模型，不限高会撑破菜单
                    ModelCatalogList(
                        modelIds = c.modelIds,
                        selected = config.modelId,
                        onPick = onPick,
                        modifier = Modifier.heightIn(max = Dimens.consoleHeightMin),
                    )
                }

                is ModelCatalog.Failed -> {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "重试拉取（${c.message}）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = onFetch,
                    )
                }
            }
            // 兜底入口恒在：拉取失败/网关不支持 /v1/models 时用户仍可手动填
            DropdownMenuItem(
                text = { Text("手动输入模型 ID") },
                onClick = {
                    onExpandedChange(false)
                    onManualInput()
                },
            )
        }
    }
}
