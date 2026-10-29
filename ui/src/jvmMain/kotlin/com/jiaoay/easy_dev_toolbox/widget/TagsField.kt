package com.jiaoay.easy_dev_toolbox.widget

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow
import com.jiaoay.easy_dev_toolbox.widget.pickDirectory
import kotlinx.coroutines.launch

/**
 * 列表型（逗号分隔）字段渲染器（自 feature-larkbridge field/ 下沉 :ui 共享——
 * feature-ai「源码目录」卡片消费；R-6 必须自写：`Editor.Tags` 无现成组件）。
 *
 * 其中 `workspace.extraRoots` 带 `pickDirectory = true` ⇒ 额外渲染「添加目录」按钮；
 * [pickDirectory] 是 **suspend**，必须在 [rememberCoroutineScope].launch 内调用（返回 null = 用户取消）。
 *
 * 交互：已有项逐行展示（hover 渐显删除按钮，走 [HoverableRow] 的防误点语义）；底部一行「输入 + 添加」，
 * 回车或点按钮即追加（去空白、去重）；[allowPickDirectory] 时追加目录选择按钮。
 *
 * [enabled] = false 置灰整组交互（当前无生产调用点传 false，保留通用置灰语义）。
 *
 * [focusRequester] 非空时挂到内部 input 输入框（深链聚焦白名单输入框——飞书桥接配置页基础字段行的 access.trustedUsers 等 Tags 字段；默认 null 零行为变化）。
 *
 * 零白名单：圆角 [AppShapes]、间距 [Dimens]，无裸 `.dp` / 动画 / alpha。
 */
@Composable
fun TagsField(
    label: String?,
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    allowPickDirectory: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    // enabled=false 时 no-op（不只禁 UI——键盘回车的 KeyboardActions 也在场，须双保险）
    val addTag: () -> Unit = {
        if (enabled) {
            val trimmed = input.trim()
            if (trimmed.isNotEmpty() && trimmed !in tags) {
                onTagsChange(tags + trimmed)
            }
            input = ""
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        if (label != null) Text(text = label, style = MaterialTheme.typography.bodySmall)

        if (tags.isEmpty()) {
            Text(
                text = "（空）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                tags.forEachIndexed { index, tag ->
                    HoverableRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 行体 focusable：键盘 Tab 进行 → 行获焦 → revealed=true →
                            // 删除钮 enabled 可接收焦点（HoverableRow 契约——缺行焦点分支
                            // = 键盘用户对删除零通路；删除是安全白名单项的唯一撤出口）
                            .focusable()
                            .padding(vertical = Dimens.space2),
                        shape = AppShapes.small,
                        actionContent = { hovered ->
                            IconButton(
                                onClick = { onTagsChange(tags.filterIndexed { i, _ -> i != index }) },
                                enabled = hovered && enabled,
                            ) {
                                AppSymbolIcon(
                                    icon = MaterialSymbols.CLOSE,
                                    contentDescription = "删除 $tag",
                                    size = Dimens.iconSmall,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = Dimens.space8),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).weight(1f),
                singleLine = true,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("输入后回车或点添加", style = MaterialTheme.typography.bodySmall) },
                keyboardActions = KeyboardActions(onDone = { addTag() }),
            )
            Button(onClick = addTag, enabled = enabled) { Text("添加") }
            if (allowPickDirectory) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val picked = pickDirectory(title = "选择目录")
                            if (picked != null) {
                                val path = picked.absolutePath
                                if (path !in tags) onTagsChange(tags + path)
                            }
                        }
                    },
                    enabled = enabled,
                ) {
                    Text("添加目录")
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
