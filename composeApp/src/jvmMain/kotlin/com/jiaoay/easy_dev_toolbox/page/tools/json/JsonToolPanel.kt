package com.jiaoay.easy_dev_toolbox.page.tools.json

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.navigation.SmartPrefillHook
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyButton
import kotlinx.coroutines.flow.filterNotNull

/**
 * JSON 工具面板：「工具」聚合页子签内容体（聚合页原生签——无 palette 深入
 * 模式候选、无持久化，全部状态会话内）。
 *
 * 两段：格式化/压缩（粘贴即校验——坏 JSON 给行/列人话回执，有效则按档重排
 * 输出可复制）；JSONPath 抽取（最小子集查询——见 [queryJsonPath] KDoc 能力
 * 边界，语法子集外的查询显式报错不静默）。
 *
 * 视觉层（输出框密度、命中行排布）为保守默认，待真机终裁。
 */
@Composable
fun JsonToolPanel() {
    var input by remember { mutableStateOf("") }
    var prettyMode by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    val content = remember(input) { parseJsonContent(input) }
    val output = remember(content, prettyMode) {
        val parsed = content as? JsonContent.Ok
        if (parsed == null) null else if (prettyMode) formatJson(parsed.element) else minifyJson(parsed.element)
    }
    val queryResult = remember(content, query) {
        val parsed = content as? JsonContent.Ok
        if (parsed == null) null else queryJsonPath(parsed.element, query)
    }

    SectionCard(
        title = "格式化 / 压缩",
        titleStyle = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        headerTrailing = {
            PillLabel(
                text = "粘贴即校验 · 坏 JSON 定位到行列",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("JSON 文本") },
            placeholder = { Text("粘贴日志行里的 JSON（信号 / 埋点 / 配置）") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
            textStyle = DesktopTypography.monoInput,
        )
        when (val parsed = content) {
            is JsonContent.Empty -> Unit

            is JsonContent.Invalid -> Text(
                text = parsed.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            is JsonContent.Ok -> Text(
                text = "JSON 有效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SegmentedControl(
            options = listOf("格式化", "压缩"),
            selectedIndex = if (prettyMode) 0 else 1,
            onSelect = { prettyMode = it == 0 },
            modifier = Modifier.fillMaxWidth(),
        )
        if (output != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                OutlinedTextField(
                    value = output,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (prettyMode) "格式化输出（2 空格缩进）" else "压缩输出（单行）") },
                    minLines = 4,
                    modifier = Modifier.weight(1f),
                    textStyle = DesktopTypography.mono,
                )
                CopyButton(text = output)
            }
        }
    }

    SectionCard(
        title = "JSONPath 抽取",
        titleStyle = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        headerTrailing = {
            PillLabel(
                text = "\$ · .name · ['name'] · [n] · .* / [*]",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("JSONPath 查询") },
            placeholder = { Text("""如 $.data.items[0].name 或 $.items[*].id""") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = DesktopTypography.monoInput,
        )
        when (val result = queryResult) {
            null -> if (content is JsonContent.Invalid) {
                Text(
                    text = "JSON 未通过校验，先修复解析错误再查询",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is JsonPathQueryResult.Empty -> Unit

            is JsonPathQueryResult.Invalid -> Text(
                text = result.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            is JsonPathQueryResult.Ok -> {
                Text(
                    text = if (result.values.isEmpty()) {
                        "0 处命中（缺键 / 下标越界 / 类型不符均算零命中，非错误）"
                    } else {
                        "${result.values.size} 处命中"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.truncated) {
                    Text(
                        text = "命中超过 $MAX_JSON_PATH_HITS 处已截断",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (result.values.isNotEmpty()) {
                    if (result.values.size > RENDER_HIT_ROW_CAP) {
                        Text(
                            text = "命中 ${result.values.size} 处，仅展示前 $RENDER_HIT_ROW_CAP 处",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                        result.values.take(RENDER_HIT_ROW_CAP).forEachIndexed { number, value ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                            ) {
                                Text(
                                    text = "#${number + 1}",
                                    style = DesktopTypography.monoTag,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = value,
                                    style = DesktopTypography.mono,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── palette 剪贴板预填消费（JSON → 输入框）：双 effect 配方（组合期初值承担
    // 「跳页/切签后首次落页」，在页订阅承担「本页保持组合时 palette 再投递」）。
    // 覆盖语义 = 覆写（输入框本是一次性粘贴区）；预填只写输入框，校验/格式化由
    // 既有 remember(input) 纯显示链承担（无自动执行面）。
    LaunchedEffect(Unit) {
        SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.JsonText>()
            ?.let { input = it.value }
    }
    LaunchedEffect(Unit) {
        SmartPrefillHook.pending.filterNotNull().collect {
            SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.JsonText>()
                ?.let { payload -> input = payload.value }
        }
    }
}

/** 命中渲染行上限（查询上界见 [MAX_JSON_PATH_HITS]，此处是渲染面独立防线） */
private const val RENDER_HIT_ROW_CAP = 100
