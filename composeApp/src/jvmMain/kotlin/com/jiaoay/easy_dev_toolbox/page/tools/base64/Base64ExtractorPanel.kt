package com.jiaoay.easy_dev_toolbox.page.tools.base64

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.jiaoay.easy_dev_toolbox.core.navigation.SmartPrefillHook
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

/**
 * base64 行内提取面板：「工具」聚合页子签内容体（聚合页原生签——无 palette
 * 深入模式候选、无持久化，全部状态会话内）。
 *
 * 三段：粘贴区（日志文本）/ 候选列表（长 token 嗅探，超上限截断明示；点击
 * 选中）/ 选中候选预览（图片内联缩放预览或文本回显）。
 *
 * 解码全走后台线程（produceState + [Dispatchers.Default]，含 skia 图像解码）——
 * 超长 token 解码不阻塞 UI 线程；坏 base64 / 未知格式 / 图像字节损坏均显式
 * 失败不静默；图片预览按 [Dimens.imagePreviewHeightMax] 上限缩放（超大截图
 * 不原尺寸铺开撑破页面）。视觉层为保守默认，待真机终裁。
 */
@Composable
fun Base64ExtractorPanel() {
    var text by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Base64Candidate?>(null) }

    val sniffed = remember(text) { sniffCandidates(text) }

    // 文本变更即旧候选失效（选中指向旧文本的 token，序位已漂移）——清空选中防串位预览
    LaunchedEffect(text) { selected = null }

    SectionCard(
        title = "日志文本",
        description = "粘贴含 base64 段的日志行——长 token 自动嗅探为候选，点击候选解码预览",
        headerTrailing = {
            PillLabel(
                text = "行内嗅探 · 魔数识别",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("待嗅探文本（粘贴日志片段）") },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
            textStyle = DesktopTypography.monoInput,
        )
    }

    if (text.isNotEmpty()) {
        SectionCard(
            title = "提取候选",
            description = "长度 ≥ ${DEFAULT_MIN_TOKEN_LENGTH} 字符的 base64 token",
        ) {
            if (sniffed.candidates.isEmpty()) {
                Text(
                    text = "未嗅探到长 base64 token（阈值 ${DEFAULT_MIN_TOKEN_LENGTH} 字符）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (sniffed.truncated) {
                    Text(
                        text = "候选超过 $MAX_CANDIDATES 个已截断（仅呈现截断前列表）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                    sniffed.candidates.forEach { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            selected = candidate.index == selected?.index,
                            onClick = { selected = if (selected?.index == candidate.index) null else candidate },
                        )
                    }
                }
            }
        }
    }

    selected?.let { candidate ->
        val preview by produceState<CandidatePreview?>(initialValue = null, candidate) {
            value = decodePreview(candidate.token)
        }
        SectionCard(
            title = "候选 #${candidate.index + 1} 预览",
        ) {
            when (val state = preview) {
                null -> Text(
                    text = "解码中……",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> PreviewBody(state)
            }
        }
    }

    // ── palette 剪贴板预填消费（base64 → 粘贴区）：双 effect 配方（组合期初值承担
    // 「跳页/切签后首次落页」，在页订阅承担「本页保持组合时 palette 再投递」）。
    // 覆盖语义 = 覆写粘贴区；候选嗅探由既有 remember(text) 纯显示链承担，解码
    // 预览仍须用户点击候选（预填不自动执行）。
    LaunchedEffect(Unit) {
        SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.Base64Text>()
            ?.let { text = it.value }
    }
    LaunchedEffect(Unit) {
        SmartPrefillHook.pending.filterNotNull().collect {
            SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.Base64Text>()
                ?.let { payload -> text = payload.value }
        }
    }
}

/** 选中候选的解码落定态：引擎三分支 + 面板侧图像解码失败显式接住的第四分支 */
private data class CandidatePreview(
    val result: Base64DecodeResult,
    val bitmap: ImageBitmap?,
)

/** 后台线程解码候选 + 图像字节转 ImageBitmap（两层解码都在 UI 线程之外） */
private suspend fun decodePreview(token: String): CandidatePreview = withContext(Dispatchers.Default) {
    val result = decodeCandidate(token)
    val bitmap = (result as? Base64DecodeResult.Image)?.let { image ->
        // 魔数识别只看文件头：数据中段截断/损坏时 skia 解码失败——显式失败不静默
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(image.bytes).toComposeImageBitmap() }
            .getOrNull()
    }
    CandidatePreview(result, bitmap)
}

@Composable
private fun PreviewBody(preview: CandidatePreview) {
    when (val result = preview.result) {
        is Base64DecodeResult.Image -> {
            val bitmap = preview.bitmap
            if (bitmap == null) {
                Text(
                    text = "已识别为 ${result.format.label} 图片（${result.bytes.size} 字节），但图像解码失败——数据可能截断或损坏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = "${result.format.label} · ${result.bytes.size} 字节（预览已按高度上限缩放）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Image(
                    bitmap = bitmap,
                    contentDescription = "base64 候选图片预览（${result.format.label}）",
                    contentScale = ContentScale.Fit,
                    // 大图（如 4K 截图）缩放进上限高度内预览，不原尺寸铺开
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = Dimens.imagePreviewHeightMax),
                )
            }
        }

        is Base64DecodeResult.Text -> {
            Text(
                text = result.content,
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (result.truncated) {
                Text(
                    text = "文本超过 $MAX_TEXT_PREVIEW_CHARS 字符已截断（仅回显前 $MAX_TEXT_PREVIEW_CHARS 字符）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is Base64DecodeResult.Failure -> Text(
            text = result.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 候选行：序号 + token 长度 + 首尾片段（长 token 不整段铺开，只给辨认用的两端） */
@Composable
private fun CandidateRow(
    candidate: Base64Candidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.hotWash))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.space4),
    ) {
        Text(
            text = "#${candidate.index + 1}",
            style = DesktopTypography.monoTag,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "${candidate.token.length} 字符",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = candidate.token.headTailSnippet(),
            style = DesktopTypography.monoTag,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** 长 token 首尾片段：两端各给一窗，中间以省略号连接（短 token 原样） */
private fun String.headTailSnippet(head: Int = 24, tail: Int = 12): String =
    if (length <= head + tail + 1) this else "${take(head)}…${takeLast(tail)}"
