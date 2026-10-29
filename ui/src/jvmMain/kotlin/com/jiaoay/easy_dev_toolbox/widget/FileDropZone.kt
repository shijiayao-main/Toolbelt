package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.interactions.onEnterKey
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.delay
import java.io.File
import java.net.URI
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/**
 * 文件拖放/点击选择区：统一所有页面的文件输入入口。
 *
 * - 拖入文件：顶层直拖条目由 [acceptsDroppedFiles] 过滤（null 回落 [accepts]——
 *   直拖与点击同属「用户点名文件」入口，可放宽后缀粗滤交下游内容判型）；拖入目录：
 *   [acceptsDirectories] 开启时目录条目经 [expandDroppedEntries] 递归枚举内部文件，
 *   展开产物恒由 [accepts] 过滤（批量场景后缀粗滤，双轨语义见该函数契约）并入输入批
 *   （产物目录一等输入——枚举上限与整批判拒语义见该函数契约）；关闭时目录按
 *   「类型不符」跳过。点击路径（文件选择器）仍是单文件多选形态，不提供目录选择——
 *   目录仅经拖入进入
 * - 点击：弹出系统文件选择器（多选），由 [acceptsDroppedFiles] 过滤（null 回落
 *   [accepts]）
 *
 * 拖入全军覆没（无过滤命中或全部解析失败）时不再静默忽略：
 * 组件进入短促的「拒绝态」——文案切换为 [rejectText] 并以 error 色系呈现，
 * [REJECT_HINT_DISMISS_MS] 后自动消退回常态。点击路径（文件选择器）自带
 * 文件类型过滤，无需此反馈。
 *
 * busy 门禁：[busy] 为 true 时拖入/点击一律拒绝（文案 [busyText]，error 色系，
 * 同拒绝态自动消退）——VM 侧另有按产物目标的在途去重（FileTaskViewModel 单飞），
 * 门禁是输入入口的就近防重入层（palette 等程序化提交路径不经本组件，靠 VM 侧去重）。
 *
 * 组件内部完成 DragData → File 的解析，页面无需再处理事件。
 */
private const val REJECT_HINT_DISMISS_MS = 2_400L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FileDropZone(
    title: String,
    dragTitle: String,
    desc: String,
    onFilesSelected: (List<File>) -> Unit,
    modifier: Modifier = Modifier,
    icon: String = MaterialSymbols.FILE_UPLOAD,
    height: androidx.compose.ui.unit.Dp = 140.dp,
    multiple: Boolean = true,
    accepts: (File) -> Boolean = { true },
    /**
     * 直拖顶层条目与点击选择器（用户点名文件入口）的过滤通道；null 回落 [accepts]
     * （既有消费点零改动）。目录展开产物恒走 [accepts]——批量场景后缀粗滤，
     * 双轨取舍见 [expandDroppedEntries] 契约。
     */
    acceptsDroppedFiles: ((File) -> Boolean)? = null,
    acceptsDirectories: Boolean = false,
    rejectText: String = "不支持的文件类型，已忽略",
    busy: Boolean = false,
    busyText: String = "任务处理中，请等待完成或取消后再拖入",
) {
    var isDragging by remember { mutableStateOf(false) }
    // 拒绝/门禁反馈态：非空 = 文案/前景切 error 色系，超时自动消退。
    // 消息动态（类型不符 → rejectText；busy 门禁 → busyText），故由布尔改持文案。
    var rejectMessage by remember { mutableStateOf<String?>(null) }
    // 选择器协程（pickFiles 走共享挂起版 pickOpenFiles——
    // 模态弹窗切 IO 线程，不再直弹冻结 UI 线程）
    val pickerScope = androidx.compose.runtime.rememberCoroutineScope()

    // busy 最新值读态：dropTarget 是 remember 单例，直捕 busy/busyText 会陈旧
    val currentBusy by androidx.compose.runtime.rememberUpdatedState(busy)
    val currentBusyText by androidx.compose.runtime.rememberUpdatedState(busyText)

    fun resolveFiles(event: DragAndDropEvent): DropExpansionOutcome? {
        val dragData = event.dragData()
        if (dragData !is DragData.FilesList) return null
        val existing = dragData.readFiles()
            .mapNotNull { runCatching { File(URI.create(it)) }.getOrNull() }
            .filter { it.exists() }
        // 混批展开（目录 opt-in 枚举）：命中文件 + 跳过数 + 整批判拒原因统一由
        // 展开层给足——本组件只呈现，不自带第二套判定。直拖顶层条目走
        // acceptsDroppedFiles 双轨通道（null 回落 accepts），目录展开产物恒走 accepts
        return expandDroppedEntries(
            entries = existing,
            accepts = accepts,
            expandDirectories = acceptsDirectories,
            fallbackRejectText = rejectText,
            acceptsDirectEntry = acceptsDroppedFiles,
        )
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                isDragging = true
            }

            override fun onEnded(event: DragAndDropEvent) {
                isDragging = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDragging = false
                if (currentBusy) {
                    // busy 门禁拒绝：明示原因（非静默）；吃掉 drop 事件（有意拒收非失败）
                    rejectMessage = currentBusyText
                    return true
                }
                val outcome = resolveFiles(event) ?: return false
                if (outcome.files.isNotEmpty()) {
                    // 部分命中明示跳过数（R14 拒绝必带原因——混批拖入被滤项静默丢失
                    // 让「拖了 5 个只有 3 个进列表」无解释；目录内非匹配文件同口径计入）
                    if (outcome.skippedCount > 0) {
                        com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                            "已接收 ${outcome.files.size} 个，跳过 ${outcome.skippedCount} 个（类型不符）",
                        )
                    }
                    onFilesSelected(outcome.files)
                    return true
                }
                // 全军覆没：拒绝原因由展开层给足（目录零匹配/超限/页面回退文案），
                // 拖拽已结束，isDragging=false 下文案独立于拖拽态呈现
                rejectMessage = outcome.rejectReason ?: rejectText
                return false
            }
        }
    }

    // 拒绝/门禁态自动消退（状态过渡定时器，非动效）：到期回常态，用户重拖可再次触发
    LaunchedEffect(rejectMessage) {
        if (rejectMessage != null) {
            delay(REJECT_HINT_DISMISS_MS)
            rejectMessage = null
        }
    }

    // 拖入/离开三色渐变（fast 档）：border / 背景 / 前景（icon 与标题文字共用同一语义色）。
    // 仅渲染层突变 → 动画，isDragging 的状态语义与回调逻辑不变。
    // 拒绝/门禁态优先于常态：拒绝或 busy 拒收时边框/前景切 error 色系。
    val borderColor by animateColorAsState(
        targetValue = when {
            rejectMessage != null -> MaterialTheme.colorScheme.error
            isDragging -> MaterialTheme.colorScheme.primary
            // 常态 = .zone inset 1px hairline（非 2dp outline 实色；拖入/拒绝语义色保留）
            else -> LocalToolboxSurfaceColors.current.borderHairline
        },
        animationSpec = AppMotion.fast(),
        label = "dropZoneBorder",
    )
    val backgroundColor by animateColorAsState(
        targetValue = when {
            rejectMessage != null -> MaterialTheme.colorScheme.errorContainer.copy(alpha = AppAlpha.bgSubtle)
            // 拖入悬停 = primary×selected 0.08
            isDragging -> MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.selectedWash)
            // .zone background = var(--surface-raised) 实底——非 alpha 合成
            else -> LocalToolboxSurfaceColors.current.surfaceRaised
        },
        animationSpec = AppMotion.fast(),
        label = "dropZoneBackground",
    )
    val foregroundColor by animateColorAsState(
        targetValue = when {
            rejectMessage != null -> MaterialTheme.colorScheme.error
            isDragging -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = AppMotion.fast(),
        label = "dropZoneForeground",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(AppShapes.large)
            .border(
                // 1dp 发丝（.zone inset 1px）
                width = Dimens.glassStrokeHairline,
                color = borderColor,
                // 拖放容器圆角 16dp（与 border/background 两用点同步）
                shape = AppShapes.large
            )
            .background(
                color = backgroundColor,
                shape = AppShapes.large
            )
            .clickable {
                // busy 门禁：点击选择器同样拒收（与拖入同因同文案），非静默
                if (currentBusy) {
                    rejectMessage = currentBusyText
                } else {
                    pickerScope.launch {
                        val files = pickOpenFiles(title = "选择文件", multiple = multiple, filter = acceptsDroppedFiles ?: accepts)
                        if (files.isNotEmpty()) onFilesSelected(files)
                    }
                }
            }
            // P4 · 键盘可达：Enter/Space 开文件选择器（clickable 已带 Space 激活，
            // Enter 补齐——焦点态视觉由 clickable 自带 ripple 承载）
            .onEnterKey {
                if (currentBusy) {
                    rejectMessage = currentBusyText
                } else {
                    pickerScope.launch {
                        val files = pickOpenFiles(title = "选择文件", multiple = multiple, filter = acceptsDroppedFiles ?: accepts)
                        if (files.isNotEmpty()) onFilesSelected(files)
                    }
                }
            }
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
                target = dropTarget
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.space6)
        ) {
            AppSymbolIcon(
                icon = icon,
                contentDescription = null,
                size = Dimens.iconHero,
                tint = foregroundColor
            )
            Text(
                text = rejectMessage ?: when {
                    isDragging -> dragTitle
                    else -> title
                },
                style = MaterialTheme.typography.bodyMedium,
                color = foregroundColor
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = LocalToolboxSurfaceColors.current.onSurfaceFaint
            )
        }
    }
}

