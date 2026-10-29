package com.jiaoay.easy_dev_toolbox.page.shelltools.output

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.widget.pickSaveFile
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.page.shelltools.ExecOutputItem
import com.jiaoay.easy_dev_toolbox.page.shelltools.ExecOutputType
import com.jiaoay.easy_dev_toolbox.page.shelltools.ExecStatus
import com.jiaoay.easy_dev_toolbox.page.shelltools.OutputState
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsRuntime
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsViewModel
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.SearchField
import com.jiaoay.easy_dev_toolbox.widget.VerticalScrollbarSlot
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.TooltipIconButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/** 贴底跟随像素容差：判定「最后一可见项底边接近视口底边」时吸收亚像素/微偏移 */
private const val BOTTOM_FOLLOW_TOLERANCE_PX = 8

@Composable
fun ShellOutputPanelContent(
    viewModel: ShellToolsViewModel,
    maxHeight: Dp = Dimens.consoleHeightMax,
) {
    val engine = ShellToolsRuntime.engine
    val outputState by engine.output.collectAsState()
    val execStatus by engine.status.collectAsState()
    val queuedCount by engine.queuedCount.collectAsState()
    val exportScope = rememberCoroutineScope()

    // 过滤词进程级持有（ShellToolsRuntime）：与 engine.output 同生命周期，
    // 切页不丢、清空输出不清词（docs/pages/shell-tools.md 既有语义「清空输出时过滤词保留」）
    val filterKeyword by ShellToolsRuntime.outputFilterKeyword.collectAsState()

    // 滚动态上提到收起门控之外：AnimatedVisibility 退出动画结束即内容移出组合
    //（无 SaveableStateHolder 承接）——内联 remember 的滚动态会随销毁丢失，重新
    // 展开后停顶部、贴底跟随失效；上提后收起前贴底 → 展开仍在底部，上翻过 → 保位
    val listState = rememberLazyListState()

    // 收起/展开开关（进程级会话态，与封顶高度同层同口径；不持久化）
    val consoleExpanded by ShellToolsRuntime.consoleExpanded.collectAsState()

    val visibleItems = remember(filterKeyword, outputState) {
        if (filterKeyword.isBlank()) outputState.items
        else outputState.items.filter { it.content.contains(filterKeyword, ignoreCase = true) }
    }

    // 面板必须「空闲时紧裹」：非 weight 子项测量期吞满整列会把 weight(1f)
    // 工具树区饿成 0 高（树整体不可见）。树在上、输出台沉底；空闲时无 heightIn、
    // 无列表区（只剩头部/输入条）；**有输出时**高度随内容增长、heightIn 封顶内滚
    // （短输出只占内容高，树区不被无谓挤占；长输出涨到封顶后列表内部滚动）。
    val hasOutput = outputState.items.isNotEmpty()
    // 深底从列表区上移到根 Column（稿输出台整台 .console 恒深）——头部/过滤栏/
    // 输入栏全在深底上（控制台语义整台承载）；空闲紧裹分支同样带深底（Idle 态
    // 也是控制台）。列表区不再重复画底（防双叠——根已带）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current.consoleBg,
                shape = AppShapes.medium,
            )
            .then(
                if (hasOutput) {
                    // 封顶高度受控（宿主拖拽调节），不固定 consoleHeightMax；
                    // 封顶约束在 padding 外侧——整台实高（含纵向 padding）≤ 封顶，
                    // 调封顶值不必另加 padding 余量（台面几何与封顶语义对齐）
                    Modifier.heightIn(max = maxHeight)
                } else {
                    Modifier
                }
            )
            // 整台 padding 12×16（.console sp-12/sp-16）
            .padding(vertical = Dimens.space12, horizontal = Dimens.space16),
    ) {
        OutputPanelHeader(
            execStatus = execStatus,
            outputState = outputState,
            queuedCount = queuedCount,
            visibleItems = visibleItems,
            filterKeyword = filterKeyword,
            consoleExpanded = consoleExpanded,
            onToggleExpanded = { ShellToolsRuntime.consoleExpanded.value = !consoleExpanded },
            onStop = { engine.stop() },
            onClear = { engine.clearOutput() },
            // 重试走 VM 统一重写层（当前 adb 路径/串号重写原串）——面板不得直连引擎提交
            onRetry = { viewModel.retryExecution() },
            onExport = {
                exportScope.launch(Dispatchers.IO) {
                    // 导出按过滤口径：过滤出关心的行 → 导出的就是这些行（全量口径让「过滤→带走」断链）
                    exportOutputToFile({ engine.appendStatusLine(it) }, visibleItems)
                }
            },
        )

        // 头部之下走 ConsoleFlexBody：弹性区（过滤栏+列表）与固定件（输入条）同台，
        // 输出增长到封顶时由列表内滚吸收增长压力，输入条恒驻（执行入口不退场）
        ConsoleFlexBody(
            flex = {
                // 收起/展开门控：外层 if (hasOutput) 与列表同判据同位置（过滤栏与
                // 列表同进退；空闲紧裹目标下无输出时过滤框是死控件噪音），内层
                // AnimatedVisibility 承载收起开关——fadeIn+expandVertically standard
                // 档（与管理行同配方，enter/exit 各单行）；收起态 = 头部 + 输入条
                Column {
                    if (hasOutput) {
                        AnimatedVisibility(
                            visible = consoleExpanded,
                            enter = fadeIn(AppMotion.standard()) + expandVertically(AppMotion.standard()),
                            exit = fadeOut(AppMotion.standard()) + shrinkVertically(AppMotion.standard()),
                        ) {
                            Column {
                                // 过滤词进程级持有不清空，恢复输出后过滤态自动回归
                                //（docs/pages/shell-tools.md 既有语义「清空输出时过滤词保留」）
                                OutputFilterBar(
                                    keyword = filterKeyword,
                                    onKeywordChange = { ShellToolsRuntime.outputFilterKeyword.value = it },
                                )

                                // 空闲零占位：无输出不渲染列表区，空闲面板自然紧裹。
                                // 列表区不得用 weight(1f)——列内 weight 语义 min=max=分配空间，一行
                                // 输出也会把面板钉满封顶；列表保持内容自适应（见 OutputList），面板高度
                                // 才有「随内容增长、封顶内滚」语义。
                                if (visibleItems.isEmpty()) {
                                    // 过滤无匹配行内轻提示：全部行被关键字隐藏时列表区空白会误读为
                                    // 内容丢失——显式提示（过滤词仅作用于显示层，清空关键字即恢复）
                                    Text(
                                        text = "关键字无匹配，全部 ${outputState.items.size} 条已隐藏",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = LocalEasyToolboxColors.current.consoleText.copy(
                                            alpha = AppAlpha.contentFaint,
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = Dimens.space12, vertical = Dimens.space8),
                                    )
                                } else {
                                    OutputList(
                                        visibleItems = visibleItems,
                                        modifier = Modifier.fillMaxWidth(),
                                        listState = listState,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            fixed = {
                ShellInputBar(
                    // 统一执行链：提交经 VM.submitManualCommand——写历史 +
                    // rewriteCommands（自定义 adb 路径 + 选定设备 -s 串号注入）
                    onSubmit = viewModel::submitManualCommand,
                )
            },
        )
    }
}

/**
 * 控制台弹性体：固定件先测（全额高度上限）、弹性件上限 = 约束上限 − 固定件实高
 * （不小于 0），报告高 = 两者之和、弹性件在上固定件在下。
 *
 * 测量契约：主轴约束必须有界（装配点处于有界高度容器；无界传入 fail-fast，
 * 不得把无界高放行给弹性件）；[flex] 须恒发射恰一个根容器（按下标定位）。
 * 输出增长到封顶时由弹性件内部滚动吸收，固定件不退场。
 */
@Composable
private fun ConsoleFlexBody(
    modifier: Modifier = Modifier,
    flex: @Composable () -> Unit,
    fixed: @Composable () -> Unit,
) {
    Layout(
        content = {
            flex()
            fixed()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        check(constraints.maxHeight != Constraints.Infinity) {
            "ConsoleFlexBody 主轴约束必须有界（装配点须处于有界高度容器）"
        }
        val fixedPlaceable = measurables[1].measure(constraints.copy(minHeight = 0))
        val flexPlaceable = measurables[0].measure(
            constraints.copy(
                minHeight = 0,
                maxHeight = (constraints.maxHeight - fixedPlaceable.height).coerceAtLeast(0),
            ),
        )
        layout(constraints.maxWidth, flexPlaceable.height + fixedPlaceable.height) {
            flexPlaceable.placeRelative(0, 0)
            fixedPlaceable.placeRelative(0, flexPlaceable.height)
        }
    }
}

/**
 * 导出全部输出到 .txt 文件：
 * AWT FileDialog（SAVE 模式）在 IO 协程中模态调用，不冻结 Compose UI 线程；
 * 取消（getFile() == null）静默返回；写入失败经 appendStatus 回写状态提示行。
 */
private suspend fun exportOutputToFile(
    appendStatus: (String) -> Unit,
    items: List<ExecOutputItem>,
) {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    val target = pickSaveFile(title = "导出输出", suggestedName = "shell-output-$timestamp.txt")
        ?: return // 用户取消：静默返回，不生成文件
    try {
        target.writeText(formatOutputText(items))
        // 导出成功反馈走 StatusSignals 全局 toast（成功面用户可见，含文件去向）；
        // StatusSignals 线程安全（AtomicLong + StateFlow.update），IO 协程直调合法（feature-quest 先例）
        StatusSignals.show("输出已导出: ${target.name}（${items.size} 条）")
        JLog.d("ShellOutputPanel") { "output exported to ${target.absolutePath}" }
    } catch (e: Exception) {
        JLog.e("ShellOutputPanel") { "export failed: ${e.message}" }
        appendStatus("导出失败: ${target.name}（${e.message ?: "写入异常"}）")
    }
}

// ── 头部：状态指示 + 操作按钮 ──

@Composable
private fun OutputPanelHeader(
    execStatus: ExecStatus,
    outputState: OutputState,
    queuedCount: Int,
    visibleItems: List<ExecOutputItem>,
    filterKeyword: String,
    consoleExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onExport: () -> Unit,
) {
    val hasOutput = outputState.items.isNotEmpty()
    val outputCount = outputState.items.size
    val droppedCount = outputState.droppedCount

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 表头底线恒在（console-text×18% 混色 1px + padding-bottom 8）
            .let { m ->
                val dividerColor = LocalEasyToolboxColors.current.consoleText
                    .copy(alpha = AppAlpha.consoleHeadDivider)
                    m.drawBehind {
                        drawLine(
                            color = dividerColor,
                            start = Offset(0f, size.height - Dimens.glassStrokeHairline.toPx() / 2),
                            end = Offset(size.width, size.height - Dimens.glassStrokeHairline.toPx() / 2),
                            strokeWidth = Dimens.glassStrokeHairline.toPx(),
                        )
                    }
            }
            .padding(horizontal = Dimens.space12, vertical = Dimens.space4)
            .padding(bottom = Dimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (execStatus) {
            is ExecStatus.Running -> {
                CircularProgressIndicator(
                    // 运行态进度圈 = consoleInfo（恒深值——主题派生 info 对深底 2.61:1 连图形 3:1 档都不达）
                    modifier = Modifier.size(Dimens.progressInline),
                    strokeWidth = Dimens.strokeThin,
                    color = LocalEasyToolboxColors.current.consoleInfo,
                )
                Spacer(modifier = Modifier.width(Dimens.space8))
                Text(
                    text = execStatus.label,
                    style = MaterialTheme.typography.labelMedium,
                    // 深底容器头部文字走 consoleText（头部状态字是控制台 chrome 非强调内容）
                    color = LocalEasyToolboxColors.current.consoleText,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onStop, modifier = Modifier.size(Dimens.minimumInteractiveSize)) {
                    AppSymbolIcon(
                        // 字形按三槽映射表（ui-style-guide.md 6.1a）：会话/流程级停止 = STOP_CIRCLE
                        icon = MaterialSymbols.STOP_CIRCLE,
                        contentDescription = "停止",
                        // 表头图标统一 iconSmall 档（16/18 混排回收）
                        size = Dimens.iconSmall,
                        tint = LocalEasyToolboxColors.current.logError,
                    )
                }
            }

            is ExecStatus.Failed -> {
                AppSymbolIcon(
                    // 字形按三槽映射表（ui-style-guide.md 6.1a）：错误态 = ERROR
                    //（CLOSE 是「关闭」槽字形，不承载失败语义）
                    icon = MaterialSymbols.ERROR,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                    tint = LocalEasyToolboxColors.current.logError,
                )
                Spacer(modifier = Modifier.width(Dimens.space4))
                Text(
                    // 稿头部「执行失败: cmd（exit N）」形态——前缀与 exit 码 + 600 字重；
                    // 恒深值 logError（主题派生 error 浅色档对深底 2.55:1 不达 AA）
                    text = "执行失败: " + execStatus.failedCommand.ifEmpty { execStatus.label } +
                        (execStatus.exitCode?.let { "（exit $it）" } ?: "（启动失败）"),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                    color = LocalEasyToolboxColors.current.logError,
                    // 超长失败命令单行 ellipsis：头部是收起态唯一常驻反馈面，折行会撑破台面基准
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 重试 = consoleText 中性色小文字+icon（深底语境）；hover 洗底 =
                // selectionHoverWashColor 单源（深底语境登记档：primary hoverWash，
                // 与 logcat 崩溃折叠行同档）+ indication=null（矩形 overlay 与圆角对撞）
                val retrySource = remember { MutableInteractionSource() }
                val retryHovered by retrySource.collectIsHoveredAsState()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(selectionHoverWashColor(hovered = retryHovered), AppShapes.small)
                        .clickable(interactionSource = retrySource, indication = null, onClick = onRetry)
                        .padding(Dimens.space4),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.REFRESH,
                        contentDescription = null,
                        size = Dimens.iconSmall,
                        tint = LocalEasyToolboxColors.current.consoleText,
                    )
                    Spacer(modifier = Modifier.width(Dimens.space4))
                    Text(
                        "重试",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalEasyToolboxColors.current.consoleText,
                    )
                }
            }

            is ExecStatus.Completed -> {
                AppSymbolIcon(
                    icon = MaterialSymbols.CHECK,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                    tint = LocalEasyToolboxColors.current.consoleSuccess,
                )
                Spacer(modifier = Modifier.width(Dimens.space4))
                Text(
                    text = "完成",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalEasyToolboxColors.current.consoleSuccess,
                    modifier = Modifier.weight(1f),
                )
            }

            is ExecStatus.Idle -> {
                // 称谓统一「输出台」（design-language.md §4.2 词汇表称谓单源）+ 空闲弱引导
                //（输出台恒驻面板，主文案内嵌引导即可，不上全幅 EmptyStateView 占位）；
                // logDebug 恒深值（主题派生 onSurfaceVariant 浅色档对深底 1.78:1 隐身）
                Text(
                    text = "输出台 · 暂无输出，执行命令或下方输入",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalEasyToolboxColors.current.logDebug,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 折叠钮 = 收起/展开一等开关（仅 hasOutput 渲染——Idle 态弹性区本为空，
        // 折叠钮可见无效属噪音）；收起态反馈由头部既有件承接（状态机四态 + 计数徽标）。
        // tooltip 配方单源 = widget/interactions/HoverTooltipBox——折叠钮是
        // 收起态展开唯一入口，裸图标语义泛化（EXPAND 图标对「输出台开关」辨识度低）
        if (hasOutput) {
            TooltipIconButton(
                icon = if (consoleExpanded) MaterialSymbols.EXPAND_MORE else MaterialSymbols.EXPAND_LESS,
                contentDescription = if (consoleExpanded) "收起输出台" else "展开输出台",
                tip = if (consoleExpanded) "收起输出台" else "展开输出台",
                onClick = onToggleExpanded,
                modifier = Modifier.size(Dimens.minimumInteractiveSize),
                iconSize = Dimens.iconSmall,
                tint = LocalEasyToolboxColors.current.consoleText,
            )
        }
        // 排队深度反馈：串行队列非空时可见——「点了没反应」的可见性解药
        if (queuedCount > 0) {
            Text(
                text = "排队 $queuedCount",
                style = MaterialTheme.typography.labelSmall,
                color = LocalEasyToolboxColors.current.logDebug,
            )
            Spacer(modifier = Modifier.width(Dimens.space4))
        }
        // 计数 = 过滤口径对齐（过滤时 M/N·已过滤——「框里有关键字、计数却显全量」
        // 让用户误读输出量；收起态此行是输出量唯一出口）；行尾是收起态的输出量唯一出口
        val filterActive = filterKeyword.isNotBlank()
        Text(
            text = when {
                filterActive -> "${visibleItems.size}/$outputCount 条 · 已过滤"
                droppedCount > 0 -> "$outputCount 条（已丢弃 $droppedCount）"
                else -> "$outputCount 条"
            },
            style = MaterialTheme.typography.labelSmall,
            // 恒深值 logDebug（onSurfaceFaint 对深底 2.66:1 不达 AA——计数是收起态的输出量唯一出口）
            color = LocalEasyToolboxColors.current.logDebug,
        )
        Spacer(modifier = Modifier.width(Dimens.space4))
        // 复制/导出按过滤口径：「过滤出关心的行 → 复制/导出的就是这些行」；
        // textProvider 点击时才 join，流式输出高频重组不做组合期格式化
        CopyIconButton(
            textProvider = { formatOutputText(visibleItems) },
            contentDescription = if (filterActive) "复制已过滤 ${visibleItems.size} 条" else "复制全部 ${outputState.items.size} 条",
            enabled = hasOutput,
            modifier = Modifier.size(Dimens.minimumInteractiveSize),
        )
        IconButton(onClick = onExport, enabled = hasOutput, modifier = Modifier.size(Dimens.minimumInteractiveSize)) {
            AppSymbolIcon(
                icon = MaterialSymbols.SAVE,
                contentDescription = "导出到文件",
                size = Dimens.iconSmall,
                tint = LocalEasyToolboxColors.current.consoleText,
            )
        }
        // 空态禁用（对齐复制/导出门控）：输出是会话级内存窗口非持久化实体，不加 L2
        // 确认（同页判据 = 持久化实体一律确认）；清空前的兜底 = 复制/导出仍可用
        IconButton(
            onClick = onClear,
            enabled = hasOutput,
            modifier = Modifier.size(Dimens.minimumInteractiveSize),
        ) {
            AppSymbolIcon(
                icon = MaterialSymbols.DELETE,
                contentDescription = "清空",
                // 表头三钮统一 iconSmall 档（与复制/导出基线对齐——16/18 混排回收）
                size = Dimens.iconSmall,
                tint = LocalEasyToolboxColors.current.consoleText,
            )
        }
    }
}

// ── 关键字过滤栏 ──

@Composable
private fun OutputFilterBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
) {
    // 搜索栏走 SearchField（placeholder/trailingIcon/mono 语域全保留）
    SearchField(
        value = keyword,
        onValueChange = onKeywordChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.space12),
        placeholder = "过滤输出关键字（忽略大小写）",
        // console 内 .search 无 mono 覆写 → fs-13 = bodyMedium
        textStyle = MaterialTheme.typography.bodyMedium,
        // 整台恒深容器内——search 换 console 深底语境变体
        consoleContext = true,
            trailingIcon = {
                if (keyword.isNotEmpty()) {
                    // 命中区扩至 minimumInteractiveSize（MICS 32——深底 console 语境下
                    // 20dp 难点中；命中面大于可见面，图标保持 progressInline 视觉档）
                    IconButton(
                        onClick = { onKeywordChange("") },
                        modifier = Modifier.size(Dimens.minimumInteractiveSize),
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.DELETE,
                            contentDescription = "清除关键字",
                            size = Dimens.progressInline,
                            tint = LocalEasyToolboxColors.current.consoleText,
                        )
                    }
                }
            },
    )
    Spacer(modifier = Modifier.height(Dimens.space4))
}

// ── 流式输出列表 ──

@Composable
private fun OutputList(
    visibleItems: List<ExecOutputItem>,
    modifier: Modifier = Modifier,
    listState: LazyListState,
) {

    // 追加时自动滚底（scrollToItem 即时跳转，零动画铁律）。
    // 本 effect 执行时 layoutInfo 仍是旧布局快照（新追加条目尚未布局），索引容差判定
    // （lastVisible == last - 1 || lastVisible == last）在单批追加 ≥2 条时恒假，跟随会永久停摆；
    // 改用稳健贴底判定：
    // 1) canScrollForward == false：旧快照上用户确实贴底（不能再向前滚），批量追加也跟随；
    // 2) 像素判定补充：最后一可见项底边接近视口底边（px 容差吸收亚像素/微偏移）。
    // 用户上翻查看历史（两判定均不满足）或手动滚动中不跟随，避免劫持视点。
    // （本处 key 为「最后一条的 seq」：滚动窗口封顶后 visibleItems.size 恒 5000，
    //  LaunchedEffect(size) 永不重启 ⇒ 新行不再自动贴底。seq 单调递增每次提交必变。）
    LaunchedEffect(visibleItems.lastOrNull()?.seq) {
        val last = visibleItems.lastIndex
        if (last >= 0 && !listState.isScrollInProgress) {
            // 贴底判定：不能继续向前滚（旧布局快照上用户处于底部），
            // 或最后一可见项底边接近视口底边（容差吸收亚像素/微偏移）
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            val atBottom = !listState.canScrollForward ||
                (lastVisible != null &&
                    lastVisible.offset + lastVisible.size >= info.viewportEndOffset - BOTTOM_FOLLOW_TOLERANCE_PX)
            if (atBottom) listState.scrollToItem(last)
        }
    }

    // 列表 + 右缘桌面滚动条：5000 行滚动窗口的位置/总量反馈。
    // 输出台 = 控制台语义，列表区恒深底（consoleBg 明暗分治），对齐
    // LogConsolePanel.kt 同构先例（滚动条留在深底外、浅底语境的 outline 色轨不闯深块）。
    // SelectionContainer = 文本可选中（拖选 + Ctrl+C）——控制台语义面板上
    // 「找到一行 → 带走」是核心产出动线；LazyColumn 滚动回收下跨屏选区有瑕疵
    // （legacy BridgeLogConsole 同款约束），验收判据 = 视口内拖选复制
    SelectionContainer {
        ListScrollbarLayout(
            modifier = modifier,
            list = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        // 深底由根 Column 承载（R-6 整台恒深——此处不再重复画底）
                        .padding(horizontal = Dimens.space12, vertical = Dimens.space8),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space2),
                ) {
                    // key 用单调递增 seq；contentType 按条目类型区分（Command/Stdout 等 5 类行样式不同，避免跨类型复用槽位）
                    items(
                        visibleItems,
                        key = { it.seq },
                        contentType = { it.type },
                    ) { item ->
                        ExecOutputItemRow(item = item)
                    }
                }
            },
            scrollbar = { VerticalScrollbarSlot(listState = listState) },
        )
    }
}

/**
 * 列表与右缘滚动条并排容器：列表决定容器高度，滚动条高度钉到列表实测高。
 *
 * 滚动条槽（VerticalScrollbarSlot）自带 fillMaxHeight（min=max=传入最大高），
 * 在内容自适应容器里会把整体钉满可用高、「随内容增长」失效——本节点把滚动条的
 * 高度约束重钉为列表实测高（槽内 fillMaxHeight 退化为 no-op），通用槽件保持不动
 * （其他消费点不受影响）。列表宽度钉为容器宽减滚动条宽（右缘并排语义不变）。
 * 调用方须保证传入宽度有界。
 */
@Composable
private fun ListScrollbarLayout(
    modifier: Modifier = Modifier,
    list: @Composable () -> Unit,
    scrollbar: @Composable () -> Unit,
) {
    Layout(
        content = {
            list()
            scrollbar()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val barWidthPx = Dimens.scrollBarWidth.roundToPx()
        val listWidthPx = (constraints.maxWidth - barWidthPx).coerceAtLeast(0)
        val listPlaceable = measurables[0].measure(
            constraints.copy(minWidth = listWidthPx, maxWidth = listWidthPx),
        )
        val barPlaceable = measurables[1].measure(
            Constraints(
                minWidth = barWidthPx,
                maxWidth = barWidthPx,
                minHeight = listPlaceable.height,
                maxHeight = listPlaceable.height,
            ),
        )
        layout(listWidthPx + barWidthPx, listPlaceable.height) {
            listPlaceable.placeRelative(0, 0)
            barPlaceable.placeRelative(listWidthPx, 0)
        }
    }
}

// 5.2 输出条目渲染（自 ShellToolsOutputPage 迁移）
// 行底是 consoleBg 恒深底，行色全部走恒深值 token（亮暗两态同值，对深底对比度
// 双态 PASS，WCAG AA 断言见 ShellOutputPanelConsoleGuardTest）：
// - Command  → consoleText 加粗（卡面原 primary 主题派生色对深底不可控）
// - Stdout   → consoleText
// - Stderr   → logError（#EF5350——卡面「error 亮档」#B3261E 深底 2.55 FAIL，禁用）
// - StepMarker → consoleText 加粗（与 Command 同族结构性锚点行；
//   logInfo 与 consoleText 同值无区分度，故随 Command 加粗区分）
// - Status   → logDebug（#90A4AE——onSurfaceFaint 深底合成 1.02:1 隐身，禁用）
@Composable
private fun ExecOutputItemRow(item: ExecOutputItem) {
    val consoleColors = LocalEasyToolboxColors.current
    when (item.type) {
        ExecOutputType.Command -> {
            Text(
                text = "$ ${item.content}",
                style = DesktopTypography.monoConsole.copy(fontWeight = FontWeight.Bold),
                color = consoleColors.consoleText,
                modifier = Modifier.padding(top = Dimens.space4),
            )
        }

        ExecOutputType.Stdout -> {
            Text(
                text = item.content,
                style = DesktopTypography.monoConsole,
                color = consoleColors.consoleText,
            )
        }

        ExecOutputType.Stderr -> {
            Text(
                text = item.content,
                style = DesktopTypography.monoConsole,
                color = consoleColors.logError,
            )
        }

        ExecOutputType.StepMarker -> {
            Text(
                text = item.content,
                style = DesktopTypography.monoConsole.copy(fontWeight = FontWeight.Bold),
                color = consoleColors.consoleText,
                modifier = Modifier.padding(top = Dimens.space6, bottom = Dimens.space2),
            )
        }

        ExecOutputType.Status -> {
            Text(
                text = item.content,
                style = MaterialTheme.typography.labelSmall,
                color = consoleColors.logDebug,
                modifier = Modifier.padding(top = Dimens.space2),
            )
        }
    }
}

// ── 输出文本格式化 / 复制 / 导出 ──

/**
 * 输出条目 → 纯文本（复制与导出共用同一格式）：
 * Command 行带 "$ " 前缀，其余类型原文，逐行 join。
 */
internal fun formatOutputText(items: List<ExecOutputItem>): String = items.joinToString("\n") { item ->
    if (item.type == ExecOutputType.Command) "$ ${item.content}" else item.content
}

// ── 手动输入栏 ──

@Composable
private fun ShellInputBar(
    onSubmit: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }

    fun submitAndClear(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        onSubmit(trimmed)
        value = ""
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Shift+Enter 留行标记：Shift+Enter 经 onPreviewKeyEvent 放行 → TextField 插入 \n →
        // onValueChange 收到含 '\n' 值 → 若无条件放行会走「多行粘贴」分支把换行前内容当批量
        // 命令提交——「留行」留不住。本标记在 Shift+Enter 帧置位，onValueChange 据此区分
        // 「手动换行（保留 \n 不提交）」与「粘贴多行（批量提交）」。
        var shiftEnterLineBreak = false
        OutlinedTextField(
            value = value,
            // 深底语境输入栏（.cmdin）——容器底 consoleText×0.08 提亮 +
            // 描边 consoleText×0.14 + 字/光标 consoleText
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = LocalEasyToolboxColors.current.consoleText,
                unfocusedTextColor = LocalEasyToolboxColors.current.consoleText,
                focusedContainerColor = LocalEasyToolboxColors.current.consoleText
                    .copy(alpha = com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha.consoleFieldBg),
                unfocusedContainerColor = LocalEasyToolboxColors.current.consoleText
                    .copy(alpha = com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha.consoleFieldBg),
                focusedBorderColor = LocalEasyToolboxColors.current.consoleText
                    .copy(alpha = com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha.consoleFieldBorder),
                unfocusedBorderColor = LocalEasyToolboxColors.current.consoleText
                    .copy(alpha = com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha.consoleFieldBorder),
                focusedLabelColor = LocalEasyToolboxColors.current.consoleText,
                unfocusedLabelColor = LocalEasyToolboxColors.current.consoleText
                    .copy(alpha = com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha.contentFaint),
                cursorColor = LocalEasyToolboxColors.current.consoleText,
            ),
            onValueChange = { newValue ->
                if (newValue.contains('\n') && !shiftEnterLineBreak) {
                    // 多行粘贴：逐行提交，末尾无换行的残行保留在输入框
                    val lines = newValue.split('\n')
                    val lastLine = lines.lastOrNull() ?: ""
                    val completeLines = lines.dropLast(1).filter { it.isNotBlank() }
                    if (completeLines.isNotEmpty()) {
                        onSubmit(completeLines.filter { it.isNotBlank() }.joinToString("\n"))
                    }
                    value = lastLine
                } else {
                    // 无换行（普通输入）或 Shift+Enter 手动换行（\n 合法保留）
                    value = newValue
                }
                shiftEnterLineBreak = false
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.space12, vertical = Dimens.space6)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown &&
                        (e.key == Key.Enter || e.key == Key.NumPadEnter)
                    ) {
                        if (e.isShiftPressed) {
                            // Shift+Enter = 留行：放行插入 \n，置标记防「粘贴批量提交」分支误吞
                            shiftEnterLineBreak = true
                            false
                        } else {
                            submitAndClear(value)
                            true
                        }
                    } else {
                        false
                    }
                },
            placeholder = {
                Text(
                    "输入命令，回车执行（粘贴多行将串行执行）",
                    // .cmdin .ph opacity .6 = contentFaint 档——placeholder
                    // 显式 consoleText×contentFaint 非 M3 默认灰）
                    color = LocalEasyToolboxColors.current.consoleText.copy(
                        alpha = com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha.contentFaint,
                    ),
                )
            },
            textStyle = DesktopTypography.mono,
            // .cmdin 圆角 r-md 12px 档
            shape = AppShapes.medium,
            // 稿键位「Shift+Enter 留行」：maxLines 1→3 让「留行」语义真实存在
            //（maxLines=1 下 Shift+Enter 换行不可见，键位提示与行为不符）；
            // onValueChange 的 '\n' 拦截只处理粘贴批量提交，
            // Shift+Enter 手动换行不经 keyEvent 拦截（isShiftPressed 分支 false 放行），
            // 换行符自然入 value。
            maxLines = 3,
            trailingIcon = {
                IconButton(
                    onClick = { submitAndClear(value) },
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.SEND,
                        contentDescription = "执行",
                        size = Dimens.iconMedium,
                        tint = LocalEasyToolboxColors.current.consoleText,
                    )
                }
            },
        )
        // 键位提示行（.cmdin 行内 kbd 键帽——labelSmall faint 右对齐）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.space12),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 键位提示 = .kbd 键帽内嵌（Enter/Shift+Enter 键帽 + 提交/留行 hint 同行）
            Text(
                text = "提交 · ",
                style = MaterialTheme.typography.labelSmall,
                // R-6 整台恒深语境：键位提示文字走 consoleText（深底文字语义源，
                // onSurfaceVariant 在 #1E1E1E 深底对比不足）
                color = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current.consoleText,
            )
            com.jiaoay.easy_dev_toolbox.widget.KbdText("Enter")
            Text(
                text = " · ",
                style = MaterialTheme.typography.labelSmall,
                color = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current.consoleText,
            )
            com.jiaoay.easy_dev_toolbox.widget.KbdText("Shift+Enter")
            Text(
                text = " · 留行",
                style = MaterialTheme.typography.labelSmall,
                color = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current.consoleText,
            )
        }
    }
}
