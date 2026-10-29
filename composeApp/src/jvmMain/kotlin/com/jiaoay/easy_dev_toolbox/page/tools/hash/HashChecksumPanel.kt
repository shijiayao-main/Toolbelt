package com.jiaoay.easy_dev_toolbox.page.tools.hash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.easy_dev_toolbox.widget.pickOpenFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 哈希/校验和面板：「工具」聚合页子签内容体（聚合页原生签——无 palette 深入
 * 模式候选、无持久化，全部状态会话内）。
 *
 * 两段：文本哈希（粘贴即算四算法，逐键重算）；文件校验和（路径输入 + 浏览选文件
 * + 显式计算钮——流式摘要放 Dispatchers.IO，大固件包/日志包不冻结 UI）。
 * 结果逐算法一行，行尾复制钮（核对场景要把算出的值贴出去比对）。
 *
 * 文本超 [MAX_TEXT_HASH_CHARS] 拒算明示（引擎硬上限防线）。视觉层为保守默认，
 * 待真机终裁。
 */
@Composable
fun HashChecksumPanel() {
    HashTextSection()
    HashFileSection()
}

@Composable
private fun HashTextSection() {
    var text by remember { mutableStateOf("") }

    SectionCard(
        title = "文本哈希",
        headerTrailing = {
            PillLabel(
                text = "MD5 · SHA-1 · SHA-256 · CRC32",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("待哈希文本（粘贴字符串 / 日志片段）") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
            textStyle = DesktopTypography.monoInput,
        )
        if (text.isNotEmpty()) {
            if (text.length > MAX_TEXT_HASH_CHARS) {
                Text(
                    text = "文本 ${text.length} 字符超过上限 $MAX_TEXT_HASH_CHARS——拒绝计算（超长文本逐键重算会阻塞界面）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                // 后台计算 + 防抖：8M 字符内跑 4 个摘要算法在主线程可达百毫秒级停顿，
                // 逐键重算会直接卡住输入。防抖后只在停止输入 150ms 才算，且计算在
                // Default 调度器——算法之间检查取消，避免连续键入堆积任务。
                val hashes by produceState<Map<HashAlgorithm, String>?>(null, text) {
                    value = null
                    delay(HASH_DEBOUNCE_MS)
                    val target = text
                    value = withContext(Dispatchers.Default) {
                        val result = LinkedHashMap<HashAlgorithm, String>()
                        for (algorithm in HashAlgorithm.entries) {
                            ensureActive()
                            result[algorithm] = hashText(target, algorithm)
                        }
                        result
                    }
                }
                val current = hashes
                if (current == null) {
                    Text(
                        text = "计算中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    HashResultRows(current)
                }
            }
        }
    }
}

@Composable
private fun HashFileSection() {
    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HashFileResult?>(null) }

    val scope = rememberCoroutineScope()

    SectionCard(
        title = "文件校验和",
        description = "核对固件包 / 日志包下载完整性——64KB 流式摘要，大文件不占满内存",
        headerTrailing = {
            PillLabel(
                text = "四算法一次遍历",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            OutlinedTextField(
                value = path,
                onValueChange = {
                    path = it
                    // 路径变更即旧结果失效（结果指向旧文件，防串位呈现）
                    result = null
                },
                label = { Text("文件路径") },
                placeholder = { Text("固件包 / 日志包绝对路径") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = DesktopTypography.monoInput,
            )
            OutlinedButton(
                onClick = {
                    // AWT FileDialog 模态阻塞，放 IO 协程避免冻结 Compose UI 线程
                    //（AdbPathSetting 浏览钮同款配方）
                    scope.launch(Dispatchers.IO) {
                        pickOpenFiles(title = "选择要校验的文件").firstOrNull()?.let { file ->
                            path = file.absolutePath
                            result = null
                        }
                    }
                },
            ) { Text("浏览") }
        }
        AppButton(
            label = "计算校验和",
            busy = busy,
            busyLabel = "计算中…",
            kind = AppButtonKind.Secondary,
            enabled = path.isNotBlank(),
            onClick = {
                busy = true
                result = null
                val pathAtStart = path
                scope.launch(Dispatchers.IO) {
                    val outcome = hashFile(pathAtStart)
                    // 一致性守卫：计算期间路径已变更时丢弃陈旧结果（结果面只呈现
                    // 当前路径的结论，busy 态同理归新一轮计算管）
                    if (path == pathAtStart) {
                        busy = false
                        result = outcome
                    } else {
                        busy = false
                    }
                }
            },
        )
        when (val outcome = result) {
            null -> Unit
            is HashFileResult.Failure -> Text(
                text = outcome.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            is HashFileResult.Ok -> {
                Text(
                    text = "已读 ${outcome.byteCount} 字节",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HashResultRows(outcome.hashes)
            }
        }
    }
}

/** 逐算法结果行：算法名 + 哈希值 + 行尾复制钮（核对场景的「算出 → 带走比对」动线） */
@Composable
private fun HashResultRows(hashes: Map<HashAlgorithm, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        HashAlgorithm.entries.forEach { algorithm ->
            val value = hashes[algorithm] ?: return@forEach
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                Text(
                    text = algorithm.label,
                    style = DesktopTypography.monoTag,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = value,
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                CopyIconButton(
                    textProvider = { value },
                    contentDescription = "复制 ${algorithm.label} 哈希值",
                )
            }
        }
    }
}

/** 文本哈希的输入防抖窗口（毫秒）：停止输入后才开始计算，避免逐键堆积后台任务。 */
private const val HASH_DEBOUNCE_MS = 150L
