package com.jiaoay.easy_dev_toolbox.widget.interactions

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.delay

/**
 * 「已复制」反馈时长（ms）：来源 TimestampPage 现状值（`delay(1500)`）等价迁移，
 * 全部复制交互共用同一反馈窗口。
 */
private const val COPY_FEEDBACK_MS = 1500L

/**
 * 复制反馈状态句柄：把「写剪贴板 + 已复制反馈计时」收口到组件层。
 *
 * 适用两类场景：
 * 1. 简单「点击复制静态文本」——直接用 [CopyButton] / [CopyIconButton]，内部自动持有本状态；
 * 2. 文本需点击时才生成（如 ViewModel 异步回调导出 JSON）——调用方自持本句柄，
 *    在回调中调用 [copy]，配合自有图标呈现反馈（见 ShellToolsChildNode 分享按钮）。
 *
 * 连续点击时反馈计时自动重置（以最后一次点击起算），不闪烁、不残留。
 */
@Stable
class CopyFeedbackState internal constructor(
    private val clipboard: ClipboardManager,
) {
    /** 反馈轮次：每次 [copy] 自增，0 表示当前无「已复制」反馈 */
    internal var copyTick by mutableStateOf(0)
        private set

    /** 是否处于「已复制」反馈期 */
    val isCopied: Boolean get() = copyTick > 0

    /** 将文本写入系统剪贴板并（重新）触发 [COPY_FEEDBACK_MS] 的「已复制」反馈 */
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        copyTick++
    }

    internal fun reset() {
        copyTick = 0
    }
}

/**
 * 创建 [CopyFeedbackState] 并挂接反馈计时副作用：以 [CopyFeedbackState.copyTick] 为 key，
 * 连点时旧计时协程自动取消并重置全量 [COPY_FEEDBACK_MS] 窗口。
 */
@Composable
fun rememberCopyFeedbackState(): CopyFeedbackState {
    val clipboard = LocalClipboardManager.current
    val state = remember(clipboard) { CopyFeedbackState(clipboard) }
    LaunchedEffect(state.copyTick) {
        if (state.copyTick > 0) {
            delay(COPY_FEEDBACK_MS)
            state.reset()
        }
    }
    return state
}

/**
 * 复制按钮（OutlinedButton 形态）：点击将 [text] 写入剪贴板，随后进入 1.5s「已复制」反馈
 * （文案「复制」↔「已复制」，图标 ContentCopy ↔ Check，[Crossfade] 过渡走 AppMotion.fast()）。
 *
 * 泛化自 TimestampPage 结果行的私有实现（值等价迁移：紧凑 contentPadding 与原图标尺寸保持一致）。
 *
 * @param text 要复制的文本
 * @param modifier 外部修饰
 * @param enabled 禁用时不可点击（如无可复制内容）
 * @param contentDescription 图标无障碍描述；按钮自带文字标签，图标默认按装饰性处理（null）
 */
@Composable
fun CopyButton(
    text: String,
    modifier: Modifier = Modifier,
    /**
     * 文案覆写（缺省 null = 通用「复制」；扫码对话框等语境传「复制链接」）：
     * 恒「复制/已复制」；传值 = 静态标签（已复制态仍换「已复制」）。
     */
    label: String? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    CopyButtonImpl({ text }, modifier, label, enabled, contentDescription)
}

/**
 * 复制按钮惰性取值重载：[textProvider] 仅在点击瞬间调用一次，不在组合/重组期求值——
 * 覆盖流式输出等大文本场景（全量 join 上限 5000 行），高频重组/高频新行时零格式化分配。
 */
@Composable
fun CopyButton(
    textProvider: () -> String,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    CopyButtonImpl(textProvider, modifier, label, enabled, contentDescription)
}

@Composable
private fun CopyButtonImpl(
    textProvider: () -> String,
    modifier: Modifier,
    label: String?,
    enabled: Boolean,
    contentDescription: String?,
) {
    val state = rememberCopyFeedbackState()
    val successColor = LocalEasyToolboxColors.current.success

    OutlinedButton(
        onClick = { state.copy(textProvider()) },
        modifier = modifier,
        enabled = enabled,
        // .chipbtn 形态：fs-12/600 + padding
        // 6×14（sp-14）——旧 M3 默认 contentPadding 24×8 与稿两档全漂
        contentPadding = PaddingValues(horizontal = Dimens.space14, vertical = Dimens.space6),
    ) {
        Crossfade(
            targetState = state.isCopied,
            animationSpec = AppMotion.fast(),
            label = "copyButtonIcon",
        ) { copied ->
            AppSymbolIcon(
                icon = if (copied) MaterialSymbols.CHECK else MaterialSymbols.CONTENT_COPY,
                contentDescription = contentDescription,
                size = Dimens.iconSmall,
                tint = if (copied) successColor else LocalContentColor.current,
            )
        }
        Spacer(modifier = Modifier.width(Dimens.space4))
        Text(
            if (state.isCopied) "已复制" else (label ?: "复制"),
            // 已复制态整钮同 success 色（chipbtn success 形态）——
            // 图标/文字须同色（仅图标变色、文字留默认色是半反馈）
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ),
            color = if (state.isCopied) successColor else LocalContentColor.current,
        )
    }
}

/**
 * 复制图标按钮（IconButton 形态）：与 [CopyButton] 同一复制/反馈行为，无文字标签。
 *
 * 文本经 [textProvider] 惰性取值：仅在点击瞬间调用一次，不在组合/重组期求值——
 * 覆盖流式输出等大文本场景（全量 join 上限 5000 行），高频重组时零格式化分配
 * （[CopyButton] 文本形态面向小字符串直传；带文字标签的大文本场景用其惰性取值重载）。
 *
 * 图标 ContentCopy ↔ Check 切换（[Crossfade] + AppMotion.fast()），已复制态 tint 为语义
 * success 色；覆盖输出面板等工具栏图标按钮场景（尺寸与 tint 取值对齐现状）。
 *
 * @param textProvider 点击时调用、返回要复制的文本（惰性求值：避免流式场景组合期全量格式化）
 * @param contentDescription 图标无障碍描述（IconButton 无文字标签，调用方应提供）
 * @param modifier 外部修饰（如尺寸约束）
 * @param enabled 禁用时不可点击
 */
@Composable
fun CopyIconButton(
    textProvider: () -> String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val state = rememberCopyFeedbackState()
    val successColor = LocalEasyToolboxColors.current.success

    IconButton(
        onClick = { state.copy(textProvider()) },
        modifier = modifier,
        enabled = enabled,
    ) {
        Crossfade(
            targetState = state.isCopied,
            animationSpec = AppMotion.fast(),
            label = "copyIconButtonIcon",
        ) { copied ->
            AppSymbolIcon(
                icon = if (copied) MaterialSymbols.CHECK else MaterialSymbols.CONTENT_COPY,
                contentDescription = if (copied) "已复制" else contentDescription,
                size = Dimens.iconSmall,
                tint = if (copied) successColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
