package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiaoay.easy_dev_toolbox.core.theme.AppCardStyles
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import androidx.compose.foundation.border
import androidx.compose.foundation.text.selection.SelectionContainer
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.glass.appPanelRimTop
import com.jiaoay.easy_dev_toolbox.widget.task.buildTaskContextMenuEntries
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 文件处理任务（XLog 解码 / 文件分割共用）的 UI 模型。
 */
data class TaskUi(
    val id: String,
    val fileName: String,
    val status: TaskUiStatus,
    /** 组卡类型/卷数徽章文案（.pill.mute 中性弱底形态；null 零渲染） */
    val groupBadge: String? = null,
)

sealed class TaskUiStatus {
    data object Running : TaskUiStatus()

    /**
     * [note] 非空 = 成功但带降级标注（如转写超长截断「已截断至 N 分钟」）——
     * 静默丢数据零容忍，卡面在产物路径下一行呈现。
     */
    data class Success(val outputPath: String, val note: String? = null) : TaskUiStatus()

    /**
     * 失败态。[folderPath] = 输入文件所在目录（产物同目录约定）——Error 卡
     * 「打开文件夹」条目的路径锚；null = 不可预知（不渲染该条目）。
     */
    data class Error(val message: String, val folderPath: String? = null) : TaskUiStatus()

    /**
     * 用户主动取消的任务态（CancellationException 语义——不记失败）。
     * [partialOutput] 非空 = 已有部分产物保留（如分卷解压已解出的分片），卡片
     * 展示「已取消 · 保留已下载分片」；null = 无产物。
     */
    data class Cancelled(val partialOutput: String? = null) : TaskUiStatus()
}

/**
 * 统一任务卡：状态图标 + 文件名 + 状态描述 + 成功后"打开文件夹"。
 * 替代 XLog 与 FileSplitter 各自实现的几乎相同的任务卡。
 *
 * @param onDeepAnalyze 深度分析情境动作槽（成功态产物；null = 不渲染——宿主未接线时
 *        XLog 页传 null，接线后传 DeepAnalyzeHook 包装）。
 *        默认参数不影响存量调用方（filesplit/logtag 不传即不渲染）。
 *
 * 行尾 ⋯ 溢出菜单钮（右键等效可视化——右键菜单不可发现，
 * 触控板/单键鼠标用户找不到 affordance）。M3 DropdownMenu onClick 直弹
 * （AppContextMenu 仅右键触发无程序化展开口）；容器视觉（surfaceDeep + borderHairline
 * 发丝描边）抄 AppContextMenu :77-86 同族；菜单条目与右键菜单共享
 * [buildTaskContextMenuEntries]（TaskListSection 同包 internal 单源——两处入口
 * 冗余但语义一致，桌面惯例）。仅 Success/Running/Error 渲染（Cancelled 无条目、
 * 无钮——两入口同判）。
 */
@Composable
fun TaskCard(
    task: TaskUi,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDeepAnalyze: ((String) -> Unit)? = null,
    onAiSummarize: ((TaskUi) -> Unit)? = null,
    onCancel: ((String) -> Unit)? = null,
) {
    // 任务卡 = 完整内容卡 chrome（r-lg 16 + surface-raised 实底 + inset hairline
    // + panel-rim + padding 16×20）；发丝边 + panel-rim 配方复刻 SectionCard tonal 分支。
    val surfaceColors = LocalToolboxSurfaceColors.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = Dimens.glassStrokeHairline,
                color = surfaceColors.borderHairline,
                shape = AppShapes.large,
            )
            .appPanelRimTop(AppShapes.large),
        colors = AppCardStyles.tonal(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = AppShapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.space20, vertical = Dimens.space16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space10)
        ) {
            // ⋯ 钮展开态 + 条目即时求值（不 remember——页面回调
            // lambda 每重组重建，remember 键控会留陈旧闭包；建表零成本，重组即最新）
            var menuExpanded by remember { mutableStateOf(false) }
            val menuEntries = buildTaskContextMenuEntries(task, onDeepAnalyze, onAiSummarize)
            TaskStatusIcon(status = task.status)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimens.space2)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 组卡类型/卷数徽章（.pill.mute）
                    if (task.groupBadge != null) {
                        Spacer(modifier = Modifier.width(Dimens.space8))
                        PillLabel(
                            text = task.groupBadge,
                            container = LocalToolboxSurfaceColors.current.surfaceDeep,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when (val s = task.status) {
                    is TaskUiStatus.Running -> Text(
                        text = "处理中...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    is TaskUiStatus.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
                        // 产物路径 = 产出「最后一跳」的可带走面：SelectionContainer 拖选
                        // + 行尾复制钮（CopyIconButton 已下沉 :ui——adb pull 场景直接取路径）；
                        // weight(1f) 吃满剩余宽——复制钮恒贴行尾（旧 fill=false 把钮
                        // 悬在路径文字旁）
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (s.note != null) "${s.outputPath}（${s.note}）" else s.outputPath,
                                style = DesktopTypography.monoTag,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        CopyIconButton(
                            textProvider = { s.outputPath },
                            contentDescription = "复制产物路径",
                        )
                    }

                    is TaskUiStatus.Error -> Text(
                        text = s.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    is TaskUiStatus.Cancelled -> Text(
                        // 取消态文案四要素：状态 + 产物去留（非静默——产物保留与否必须明示）
                        text = if (s.partialOutput != null) {
                            "已取消 · 部分产物已保留：${s.partialOutput}"
                        } else {
                            "已取消（未产生产物）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (task.status is TaskUiStatus.Success) {
                Spacer(modifier = Modifier.width(Dimens.space4))
                if (onDeepAnalyze != null) {
                    // 「深度分析」= ChipTextButton 单源（capsule + raised 底 + 发丝边 +
                    // labelMedium SemiBold——几何与本卡原手拼形态逐项同构）
                    ChipTextButton(
                        label = "深度分析",
                        onClick = { onDeepAnalyze((task.status as TaskUiStatus.Success).outputPath) },
                    )
                }
                if (onAiSummarize != null) {
                    TextButton(
                        onClick = { onAiSummarize(task) },
                    ) {
                        Text(
                            text = "AI 整理",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // 「打开文件夹」= raised 底 + 发丝边小钮；tint 稿中性（onSurfaceVar）非 primary
                IcBtnContainer(contentDescription = "打开文件夹", onClick = { onOpenFolder(task.id) }) {
                    AppSymbolIcon(
                        // 字形钉 open_in_new（↗ 语义——FOLDER_OPEN 是旧自选）
                        icon = "open_in_new",
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = Dimens.iconSmall,
                    )
                }
            }
            // Running 态取消钮：cancel 圆环内 X（明文排除 stop_circle；中性 tint）
            if (task.status is TaskUiStatus.Running && onCancel != null) {
                IcBtnContainer(contentDescription = "取消任务", onClick = { onCancel(task.id) }) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.CANCEL,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = Dimens.iconSmall,
                    )
                }
            }
            // ⋯ 溢出菜单钮（右键等效可视化）——有情境条目才渲染
            // （Success/Running/Error；Cancelled 空列表无钮，与右键「空列表不弹」同判）。
            // 命中区直接落 MICS 32（不引入新 36dp 点）；
            // 菜单视觉参数 = AppContextMenu :77-86 同族（surfaceDeep + borderHairline）。
            if (menuEntries.isNotEmpty()) {
                // 菜单锚 = 本 Box：Compose Popup 锚定父布局节点——DropdownMenu 与按钮
                // 平级会锚到整卡 Row，菜单开在卡片左缘而非按钮下；必须与按钮同容器才能锚到按钮本体。
                Box {
                    IcBtnContainer(contentDescription = "更多操作", onClick = { menuExpanded = true }) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.MORE_VERT,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = Dimens.iconSmall,
                        )
                    }
                    val surfaceColors = LocalToolboxSurfaceColors.current
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = surfaceColors.surfaceDeep,
                        tonalElevation = 0.dp,
                        border = BorderStroke(Dimens.glassStrokeHairline, surfaceColors.borderHairline),
                    ) {
                        menuEntries.forEach { entry ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = entry.label,
                                        color = if (entry.danger) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    if (entry.enabled) entry.perform()
                                },
                                enabled = entry.enabled,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskStatusIcon(status: TaskUiStatus) {
    // 四态 tint 过渡（fast 档）：Running 进度圈沿用默认 primary 色（显式传入保持四态统一走动画）
    // 昼战（design-doc.md §5.3，dayBattleTint）：语义档（success/error/info）直落昼底/昼卡破图形线——
    // 图标/进度圈坐语义 container 档（3.0 线）；非昼战原值逐位不变
    val toolboxColors = LocalEasyToolboxColors.current
    val tint by animateColorAsState(
        targetValue = when (status) {
            is TaskUiStatus.Running -> MaterialTheme.colorScheme.primary
            // Success 态钉 success 绿——旧 tertiary 在暗色 Default(Fidelity) 下派生橙，
            // 与「完成=绿」语义色相冲突
            is TaskUiStatus.Success -> dayBattleTint(toolboxColors.success, toolboxColors.successContainer)
            is TaskUiStatus.Error -> dayBattleTint(MaterialTheme.colorScheme.error, toolboxColors.errorContainerFixed)
            is TaskUiStatus.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = AppMotion.fast(),
        label = "taskStatusTint",
    )
    // 四态图标 Crossfade 渐变切换（fast 档）；TaskCard 为 xlog/filesplit/logtag 共用组件，
    // 仅改图标区渲染，签名与布局行为不变，图标尺寸 token 档位（iconMedium）不变
    Crossfade(
        targetState = status,
        animationSpec = AppMotion.fast(),
        label = "taskStatusIcon",
    ) { s ->
        when (s) {
            is TaskUiStatus.Running -> CircularProgressIndicator(
                modifier = Modifier.size(Dimens.iconMedium),
                strokeWidth = 2.dp,
                // Running 进度圈 = --info 蓝（.dotrun 同源语义）——非 primary；
                // 昼战坐 infoContainer 档（design-doc.md §5.3，图形 3.0 线）
                color = dayBattleTint(LocalEasyToolboxColors.current.info, LocalEasyToolboxColors.current.infoContainer),
            )

            is TaskUiStatus.Success -> AppSymbolIcon(
                icon = MaterialSymbols.CHECK_CIRCLE,
                contentDescription = "成功",
                size = Dimens.iconMedium,
                tint = tint
            )

            is TaskUiStatus.Error -> AppSymbolIcon(
                icon = MaterialSymbols.ERROR,
                contentDescription = "失败",
                size = Dimens.iconMedium,
                tint = tint
            )

            is TaskUiStatus.Cancelled -> AppSymbolIcon(
                // 已取消态 = cancel 圆环内 X（非 stop_circle）
                icon = MaterialSymbols.CANCEL,
                contentDescription = "已取消",
                size = Dimens.iconMedium,
                tint = tint
            )
        }
    }
}
