package com.jiaoay.easy_dev_toolbox.page.deviceobs

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalWindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.WindowSizeClass
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SearchField
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.easy_dev_toolbox.widget.interactions.TooltipIconButton
import com.jiaoay.toolbelt.device.DeviceLayoutBounds
import com.jiaoay.toolbelt.device.DeviceLayoutInspector
import com.jiaoay.toolbelt.device.DeviceLayoutNode
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.device.DeviceWindowViewsEntry
import com.jiaoay.toolbelt.device.DeviceWindowViewsInspector
import com.jiaoay.toolbelt.device.LayoutTreeNode
import com.jiaoay.toolbelt.device.WindowViewNode

/** class 简名（internal 供单测）：`android.widget.Button` → `Button`；无名兜底全串 */
internal fun layoutClassShortName(className: String): String =
    className.substringAfterLast('.').ifEmpty { className }

/**
 * resource-id 简名（internal 供单测）：`com.pkg:id/title` → `title`；无 `id/`
 * 分隔的非常规形态回退原串（不猜不截断）；空串与 `NO_ID`（View 体系的无 id
 * 标准标记，全窗口视图源的无名节点常态）返回 null（摘要行不出该段）。
 */
internal fun layoutResourceIdShort(resourceId: String): String? =
    resourceId.takeIf { it.isNotEmpty() && it != "NO_ID" }?.let { id ->
        if (id.contains("id/")) id.substringAfterLast("id/") else id
    }

/** bounds 坐标原文（dump 产物口径）：`[100,200][300,400]` */
internal fun layoutBoundsText(bounds: DeviceLayoutBounds): String =
    "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"

/**
 * 引擎渲染面判定（internal 供单测）：类名以 SurfaceView/TextureView 结尾 = 内容由
 * 渲染引擎自绘于独立表面，不产生 View/无障碍子节点——层级检查（含 Android Studio
 * Layout Inspector 同款）对其内部控件物理不可见；树行/详情卡据此出「引擎面」提示，
 * 防「为什么没有子节点」误判为工具漏抓。后缀表封闭：scene 容器等非 Surface 命名的
 * 引擎宿主不硬猜（不实锤不虚标）。
 */
internal fun isEngineSurfaceClassName(className: String): Boolean =
    className.endsWith("SurfaceView") || className.endsWith("TextureView")

/**
 * 节点标记行文本（internal 供单测，a11y 降级源）：只列真值为真的标记 + 非启用态
 * 显式呈现，零标记回空串（详情卡不出该行）。标记名与 uiautomator 属性一一对应
 * 不翻译语义。
 */
internal fun layoutFlagsText(node: DeviceLayoutNode): String = buildList {
    if (!node.enabled) add("已禁用")
    if (node.clickable) add("可点")
    if (node.longClickable) add("可长按")
    if (node.scrollable) add("可滚动")
    if (node.focusable) add("可聚焦")
    if (node.focused) add("已聚焦")
    if (node.checkable) add("可勾选")
    if (node.checked) add("已勾选")
    if (node.selected) add("已选中")
    if (node.password) add("密码遮蔽")
}.joinToString(" ")

/**
 * 可见性读数人话（internal 供单测，全窗口视图源）：0 可见为正常态不列（零标记
 * 回空串惯例），4/8 与 ROM 自定义值显式呈现。
 */
internal fun windowViewVisibilityText(visibility: Int): String = when (visibility) {
    0 -> ""
    4 -> "不可见（占位）"
    8 -> "不占位（GONE）"
    else -> "visibility=$visibility"
}

/**
 * 节点标记行文本（internal 供单测，全窗口视图源）：真值标记 + 非可见态显式
 * 呈现，零标记回空串。View 体系无 a11y 长按/可滚动等面，标记集按 misc:* 属性面。
 */
internal fun windowViewFlagsText(node: WindowViewNode): String = buildList {
    if (!node.enabled) add("已禁用")
    if (node.clickable) add("可点")
    if (node.selected) add("已选中")
    if (node.activated) add("已激活")
    windowViewVisibilityText(node.visibility).takeIf { it.isNotEmpty() }?.let { add(it) }
}.joinToString(" ")

/** 窗口偏移显示文本（internal 供单测）：散键缺席 = 未知（点亮落点校准风险面如实标注） */
internal fun windowOffsetText(left: Int?, top: Int?): String? =
    if (left == null && top == null) null else "(${left ?: 0}, ${top ?: 0})"

/**
 * 窗口布局面板（设备观测空间「布局」子页承载，宿主层内容组件——只服务观测页，
 * 不入 feature 模块）：全窗口视图抓取（`cmd window dump-visible-window-views`，
 * 多窗口 View 内部层级）+ 窗口选择 + 搜索过滤 + 懒展开层级树 + 节点详情卡；
 * 全窗口视图不可用（跨 ROM 无此命令等）显式降级 uiautomator dump 并人话提示。
 *
 * - VM/面板分离（文件/应用面板同配方）：快照/投影/展开/窗口选择态在
 *   [DeviceLayoutViewModel]（请求-回执模型无轮询环，契约见其类头），本面板只做
 *   呈现与动作转发；
 * - 抓取是显式动作（头部刷新钮）：秒级命令不随进页自动发起；busy 期间再点被拒
 *   （loading 单飞）；
 * - 窗口选择行（全窗口视图源）：可见窗口清单下拉 + 包名归属徽标——Activity 窗口
 *   条目名即组件名，系统窗归属如实不猜；
 * - 懒展开：默认只展开根下两层，深层节点点箭头逐层展开；设备层级上千节点时
 *   树不全量铺开；
 * - 工作区（快照在位，Compact 档纵向回落）：Wide/Medium 档三栏——左舱预览图
 *   （[LayoutDiagramPane]——整树全层级直出的示意渲染，数据面恒全窗不随子树重根：
 *   根窗铺预览纸、节点按类族洗底、有实色画实色、a11y 源有文本画文本；点击命中走
 *   揭示动作 [DeviceLayoutViewModel.revealNode]——展开祖先路径 + 选中，命中当前
 *   子树外节点自动退出子树保「图点击 → 树内可见定位」；双击命中节点进子树
 *   [DeviceLayoutViewModel.enterSubtree]、双击空白重置视口）+ 子树视图栈返回行
 *   （栈空不渲染）+ 中舱层级树（选中行
 *   不在树视口时即时定位跳转，已在视口零动作）+ 右舱详情列（[LayoutDetailColumn]
 *   ——选中详情独立成列，树行/展开箭头不被内嵌详情卡推挤；Compact 档详情回落
 *   树内嵌形态、图固定高纵向单列）；
 * - 搜索过滤（[SearchField] 统一件）：text/resource-id/class 包含匹配（大小写
 *   不敏感，纯投影零内核请求），命中节点的祖先路径自动展开直达；全窗口视图源
 *   无 text 面，过滤实际作用于 id/class；
 * - 节点行 = 缩进 + 展开箭头 + class 简名 + text（降级源）+ resource-id 简名 +
 *   可点徽标，点行展开详情卡（按快照源分组：a11y 面与 View 内部面各自成组，
 *   bounds/类名/id 沿用既有展示形态）；View 源详情卡追加嵌套属性组（有则呈现）
 *   与「按需补抓文本」钮（本源 text 恒空串，经降级源 uiautomator dump 按 bounds
 *   join，秒级 busy 档 + 可取消）；
 * - 详情卡「投屏点亮」钮（[onLightUp] 非 null 才渲染）：一键触发节点全局中心
 *   （局部 bounds + 窗口偏移）的投屏触摸注入——判定与注入 wiring 在宿主观测页
 *   （本面板零投屏域依赖）；
 * - 目标设备 = 设备域选定串号（与文件/应用同源）；无可采目标时按 [perfPanelHint]
 *   横幅提示（动作入口在页级设备栏，与文件面板同口径）。
 *
 * 视觉层为保守默认（等宽节点行 + 令牌化缩进/徽标 + 既有下拉/横幅件），真机手感
 * 终裁归维护者。
 */
@Composable
fun DeviceLayoutPanel(
    devices: DeviceStateSource,
    sharedViewModel: DeviceLayoutViewModel? = null,
    onGoSettings: (() -> Unit)? = null,
    onLightUp: ((LayoutTreeNode) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 观测页页级持有并传入（与文件/应用 VM 同配方）；独立组合形态下自建同 key
    // 实例。检查内核是无状态请求-回执件（无常驻环），独立形态直接构造零泄漏。
    val viewModel = sharedViewModel ?: viewModel(key = "device-layout-inspector") {
        DeviceLayoutViewModel(
            devices,
            com.jiaoay.easy_dev_toolbox.core.device.DeviceHub.windowViewsInspector::dumpWindowViews,
            com.jiaoay.easy_dev_toolbox.core.device.DeviceHub.layoutInspector::dumpHierarchy,
        )
    }
    val state by viewModel.state.collectAsState()
    val deviceState by devices.deviceState.collectAsState()
    val detectError by devices.detectError.collectAsState()
    val selectedSerial by devices.selectedSerial.collectAsState()

    val hint = perfPanelHint(deviceState, detectError, selectedSerial)
    val targetSerial = when (val ds = deviceState) {
        is DeviceState.Single -> ds.serial
        is DeviceState.Multi -> selectedSerial
        is DeviceState.None -> null
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        TonalCard(
            contentPaddingVertical = Dimens.space12,
            contentPaddingHorizontal = Dimens.space16,
            contentSpacing = Dimens.space8,
        ) {
            // 头部行：图标 + 标题 + 节点数 + 目标尾号 + 抓取
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSymbolIcon(
                    icon = MaterialSymbols.ACCOUNT_TREE,
                    contentDescription = null,
                    size = Dimens.iconMedium,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(Dimens.space8))
                // 标题恒内容宽——禁再挂 weight(fill=false)：与行尾 Spacer 的配额
                // 平分后未吃满的半份配额不推挤后续元素（徽标/刷新钮堆在行中部，
                // 右缘悬空）；Spacer 独占配额使尾件恒贴右缘
                Text(
                    text = "窗口布局",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.nodeCount > 0) {
                    Spacer(modifier = Modifier.width(Dimens.space8))
                    Text(
                        text = "${state.nodeCount} 节点",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                targetSerial?.let { serial ->
                    Text(
                        text = "目标 ${serialTailLabel(serial)}",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(Dimens.space8))
                }
                IconButton(
                    onClick = { viewModel.refresh() },
                    enabled = !state.loading && targetSerial != null,
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.REFRESH,
                        contentDescription = "抓取可见窗口层级",
                        size = Dimens.iconLarge,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 无可采目标提示（动作入口与面板外资源对齐——去设置经中立槽接线，
            // 选设备/刷新在页级设备栏，消息文案自指引）
            hint?.let { h ->
                StatusBanner(
                    status = StatusBannerStatus.Error,
                    text = h.message,
                    trailing = {
                        if (h.action == PerfPanelHintAction.GO_SETTINGS) {
                            onGoSettings?.let { go ->
                                TextButton(onClick = go) { Text("去设置配置 adb 路径") }
                            }
                        }
                    },
                )
            }

            // 失败回执：内核人话原文 + 原地重试（旧快照留屏可继续查，重试不弃树）
            state.error?.let { error ->
                StatusBanner(
                    status = StatusBannerStatus.Warning,
                    text = error,
                    trailing = {
                        TextButton(
                            onClick = { viewModel.refresh() },
                            enabled = targetSerial != null && !state.loading,
                        ) { Text("重试") }
                    },
                )
            }

            // 降级标注（全窗口视图不可用 → uiautomator dump）：原因原文 + 能力差
            // 如实上屏，不静默
            state.degradedNotice?.let { notice ->
                StatusBanner(status = StatusBannerStatus.Info, text = notice)
            }

            // 抓取 busy 位（秒级命令，显式进度防误死机）
            if (state.loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(Dimens.iconSmall))
                    Text(
                        text = "抓取中…（全窗口视图，多窗口数秒）",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 窗口选择行（全窗口视图源；降级源只有聚焦窗口无窗可选）
            if (state.source == LayoutSnapshotSource.WINDOW_VIEWS && state.windows.isNotEmpty()) {
                WindowSelectorRow(
                    windows = state.windows,
                    selectedIndex = state.selectedWindowIndex,
                    onSelect = viewModel::selectWindow,
                )
            }

            // 过滤行（应用/文件子页同款 SearchField 形态；纯投影零内核请求，
            // 重抓不清词）
            SearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = "按 text / resource-id / class 过滤",
                enabled = state.root != null,
            )

            // 子树视图返回行（左图双击进子树后出；空栈不渲染）：一次退一层，目标名 =
            // 退栈后投影根节点的 class 简名（退到空栈 = 窗口根）。占位于过滤行与工作
            // 区之间——查询态投影为空（树列不组合）时也保有退出子树的入口
            state.root?.takeIf { state.subtreeStack.isNotEmpty() }?.let { root ->
                val backTargetId = state.subtreeStack.dropLast(1).lastOrNull() ?: LAYOUT_ROOT_ID
                val backTargetName = findLayoutNode(root, backTargetId)
                    ?.let { layoutClassShortName(it.className) }
                    ?: "全部层级"
                TextButton(onClick = viewModel::exitSubtree) {
                    Text("← 返回 $backTargetName")
                }
            }

            when {
                state.root == null -> if (!state.loading) {
                    Text(
                        "（尚未抓取——点右上「抓取可见窗口层级」拿当前界面树）",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.rows.isEmpty() -> Text(
                    if (state.query.isBlank()) "（空层级）" else "（无匹配节点——调整过滤词）",
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    val treeListState = rememberLazyListState()
                    // 树滚动定位：选中行已在树视口可见零动作（不劫持用户视点），不可见
                    // 才即时跳转（禁动画滚动）；被过滤挤出投影（-1）跳过零动作。key 补
                    // 子树栈维度：进/出子树时行集合变化也须重定位（选中行投影下标随
                    // 重定漂移）
                    LaunchedEffect(state.selectedNodeId, state.subtreeStack) {
                        val id = state.selectedNodeId ?: return@LaunchedEffect
                        val index = state.rows.indexOfFirst { it.id == id }
                        if (index < 0) return@LaunchedEffect
                        if (treeListState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                            treeListState.scrollToItem(index)
                        }
                    }
                    val diagramPane: @Composable (Modifier) -> Unit = { paneModifier ->
                        LayoutDiagramPane(
                            root = state.root,
                            query = state.query,
                            selectedId = state.selectedNodeId,
                            onReveal = viewModel::revealNode,
                            onEnterSubtree = viewModel::enterSubtree,
                            diagramLinked = state.diagramLinked,
                            onToggleDiagramLinked = { viewModel.setDiagramLinked(!state.diagramLinked) },
                            drawnOnly = state.diagramDrawnOnly,
                            onToggleDrawnOnly = { viewModel.setDiagramDrawnOnly(!state.diagramDrawnOnly) },
                            modifier = paneModifier,
                        )
                    }
                    if (LocalWindowSizeClass.current == WindowSizeClass.Compact) {
                        // Compact 档回落：图固定高 + 树独立滚动（纵向单列；详情走树内嵌
                        // 形态——窄屏无第三列空间）
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                            diagramPane(Modifier.fillMaxWidth().height(Dimens.layoutDiagramHeightCompact))
                            LayoutTreeColumn(
                                state = state,
                                viewModel = viewModel,
                                onLightUp = onLightUp,
                                showInlineDetail = true,
                                treeListState = treeListState,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        // 三栏：预览图 + 层级树 + 详情列——选中详情从树行内嵌区上提为
                        // 独立右舱，树行/展开箭头不被详情卡推挤遮挡；三舱均 weight(1f)
                        // 有界高度（吸顶骨架给 content 有界高度）
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                            diagramPane(Modifier.weight(1f).widthIn(min = Dimens.layoutDiagramMinWidth))
                            LayoutTreeColumn(
                                state = state,
                                viewModel = viewModel,
                                onLightUp = onLightUp,
                                showInlineDetail = false,
                                treeListState = treeListState,
                                modifier = Modifier.weight(1f),
                            )
                            LayoutDetailColumn(
                                state = state,
                                viewModel = viewModel,
                                onLightUp = onLightUp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 层级树列（三栏中舱 / Compact 档下半列）：行渲染零改动，仅上提
 * [treeListState] 供选中定位（即时跳转零动画——禁动画滚动）。[showInlineDetail]
 * = true 时选中详情卡嵌在选中行正下方（Compact 窄屏无第三列空间的形态）；
 * Wide/Medium 档传 false——详情走独立右列（[LayoutDetailColumn]），树行/展开
 * 箭头不被详情卡推挤遮挡。
 */
@Composable
private fun LayoutTreeColumn(
    state: DeviceLayoutPanelState,
    viewModel: DeviceLayoutViewModel,
    onLightUp: ((LayoutTreeNode) -> Unit)?,
    showInlineDetail: Boolean,
    treeListState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // 工作区型占满子页剩余高度（weight 链由吸顶骨架供有界高度）
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        if (state.rows.isNotEmpty()) {
            // 树工具行：整树展开/收起——真机多层树常见 7~12 层深，默认 2 层下
            // fragment 元素不可见，一键铺开免逐层手点
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                TooltipIconButton(
                    icon = MaterialSymbols.UNFOLD_MORE,
                    contentDescription = "全部展开",
                    tip = "整树展开——深层 fragment/嵌套容器一键铺开（查询态本就恒全展开）",
                    onClick = viewModel::expandAllNodes,
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                )
                TooltipIconButton(
                    icon = MaterialSymbols.UNFOLD_LESS,
                    contentDescription = "全部收起",
                    tip = "整树收起（回到只剩根行，再逐层点开）",
                    onClick = viewModel::collapseAllNodes,
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                )
            }
        }
        // 整树横向滑动（整块联动）：滚动层包住整棵 LazyColumn——树行恒单行恒内容宽
        //（无省略无换行，组件名恒读得全），整块放不下时左右滑动、所有行同一偏移
        // 左缘恒对齐。行级独立滚动（每行各自 rememberScrollState）已被否：行间
        // 错位、逐行分别拖动不可用。LazyColumn 在滚动层的无限宽约束下自取最宽
        // 可见行为块宽；行内 fillMaxWidth 在无限宽下为 no-op、行恒内容宽。
        // 工具行（全部展开/收起）在滚动层外恒驻。
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        ) {
            LazyColumn(
                state = treeListState,
                verticalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                items(state.rows, key = { it.id }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                DeviceLayoutNodeRow(
                    row = row,
                    selected = state.selectedNodeId == row.id,
                    onToggle = viewModel::toggleNode,
                    onSelect = viewModel::selectNode,
                )
                if (showInlineDetail && state.selectedNodeId == row.id) {
                    when (val node = row.node) {
                        is DeviceLayoutNode -> DeviceLayoutNodeDetailCard(
                            node = node,
                            onLightUp = onLightUp,
                        )

                        is WindowViewNode -> DeviceWindowViewDetailCard(
                            node = node,
                            onLightUp = onLightUp,
                            textLoading = state.textLoading,
                            textResult = state.textResult,
                            dumpSerialKnown = state.dumpSerial != null,
                            snapshotLoading = state.loading,
                            onFetchText = { viewModel.fetchNodeText(node) },
                            onCancelFetchText = viewModel::cancelFetchText,
                        )
                    }
                }
            }
        }
        }
            }
    }
}

/**
 * 选中节点详情列（三栏右舱，Wide/Medium 档）：消费 [DeviceLayoutPanelState.selectedNode]
 * 单源渲染详情卡（a11y 源与 View 内部源各自成组）；无选中出占位指引。选中被过滤/
 * 收起挤出树投影时路径仍可解析（selectedNode 经 VM 派生保留），树侧重选即回看——
 * 列不因投影缺席丢内容。长属性面经 verticalScroll 独立滚动，不占树滚动视点。
 */
@Composable
private fun LayoutDetailColumn(
    state: DeviceLayoutPanelState,
    viewModel: DeviceLayoutViewModel,
    onLightUp: ((LayoutTreeNode) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        when (val node = state.selectedNode) {
            null -> Text(
                text = "（点击预览或树行，在此查看节点配置）",
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is DeviceLayoutNode -> DeviceLayoutNodeDetailCard(
                node = node,
                onLightUp = onLightUp,
            )

            is WindowViewNode -> DeviceWindowViewDetailCard(
                node = node,
                onLightUp = onLightUp,
                textLoading = state.textLoading,
                textResult = state.textResult,
                dumpSerialKnown = state.dumpSerial != null,
                snapshotLoading = state.loading,
                onFetchText = { viewModel.fetchNodeText(node) },
                onCancelFetchText = viewModel::cancelFetchText,
            )
        }
    }
}

/**
 * 可见窗口选择行（保守形态：标签 + 下拉触发区 + 包名徽标，既有件零新视觉）：
 * 下拉项 = 窗口标题 + hash 尾注；包名归属仅 Activity 窗口有（系统窗不猜），有则
 * 显徽标。触发区底色走 surfaceDeep（详情卡同族），不做浮层拟态。
 */
@Composable
private fun WindowSelectorRow(
    windows: List<DeviceWindowViewsEntry>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = windows.getOrNull(selectedIndex)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Text(
            text = "可见窗口",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(AppShapes.medium)
                .background(
                    color = LocalToolboxSurfaceColors.current.surfaceDeep,
                    shape = AppShapes.medium,
                )
                .clickable { expanded = true }
                .padding(horizontal = Dimens.space12, vertical = Dimens.space8),
        ) {
            Text(
                text = selected?.let { "${it.title}（${it.windowHash}）" } ?: "（无窗口）",
                style = DesktopTypography.mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 吃满剩余宽：尾部箭头恒贴行尾（勿用 fill=false + 尾部 Spacer
                // 双 weight：配额平分致箭头堆行中部）
                modifier = Modifier.weight(1f),
            )
            AppSymbolIcon(
                icon = MaterialSymbols.ARROW_DROP_DOWN,
                contentDescription = "选择窗口",
                size = Dimens.iconSmall,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                windows.forEachIndexed { index, window ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${window.title}（${window.windowHash}）",
                                style = DesktopTypography.mono,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(index)
                        },
                    )
                }
            }
        }
        selected?.packageName?.let { pkg ->
            PillLabel(
                text = pkg,
                container = MaterialTheme.colorScheme.secondary.copy(alpha = AppAlpha.hotWash),
                contentColor = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/**
 * 单节点行：层级缩进 + 展开箭头（有子节点才出）+ class 简名 + text（降级源）+
 * resource-id 简名 + 可点徽标；点行选中展开详情卡（再点收起——切换语义在 VM）；
 * 点展开箭头 = 展开态取反 + 同时选中该行（不取反——选中即见详情与图上定位，
 * 箭头是展开 affordance 非选中开关）。
 * 缩进按深度走 token 步进，命中过滤词的节点主体着主色。
 */
@Composable
private fun DeviceLayoutNodeRow(
    row: DeviceLayoutRow,
    selected: Boolean,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    // 树行恒单行恒内容宽（无省略无换行——组件名恒读得全）。整块放不下时由
    // LayoutTreeColumn 的滚动层左右滑动（整块联动，勿在行级另挂 horizontalScroll
    // ——每行独立状态会行间错位、逐行分别拖动不可用）。旧「三文字 + 尾 Spacer
    // 四 weighted 平分」把每段封顶 ~1/4 行宽，长类名大面积省略不可读。
    // 缩进 padding 为层级视觉锚（滚动层内，随块滑动属预期）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(row.id) }
            .padding(start = Dimens.space12 * row.depth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
    ) {
        if (row.hasChildren) {
            IconButton(
                onClick = { onToggle(row.id) },
                modifier = Modifier.size(Dimens.minimumInteractiveSize),
            ) {
                AppSymbolIcon(
                    icon = if (row.expanded) MaterialSymbols.EXPAND_MORE else MaterialSymbols.CHEVRON_RIGHT,
                    contentDescription = if (row.expanded) "收起子层" else "展开子层",
                    size = Dimens.iconSmall,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(modifier = Modifier.width(Dimens.minimumInteractiveSize))
        }
        val highlight = selected || row.matchesQuery
        Text(
            text = layoutClassShortName(row.node.className),
            style = DesktopTypography.mono,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (row.node.text.isNotEmpty()) {
            Text(
                text = "「${row.node.text}」",
                style = DesktopTypography.mono,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        layoutResourceIdShort(row.node.resourceId)?.let { shortId ->
            Text(
                text = shortId,
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.node.clickable) {
            PillLabel(
                text = "可点",
                container = MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.hotWash),
                contentColor = MaterialTheme.colorScheme.primary,
            )
        }
        if (isEngineSurfaceClassName(row.node.className)) {
            PillLabel(
                text = "引擎面",
                container = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AppAlpha.selectedWash),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = layoutBoundsText(row.node.bounds),
            style = DesktopTypography.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 选中节点的详情卡（a11y 降级源，选中行正下方的深底内嵌区，文件/应用详情卡同族
 * 形态）：全属性面 + bounds 坐标/宽高/中心点（排查「这个控件在哪/多大/点哪」的
 * 主载荷）+ 可选「投屏点亮」钮（[onLightUp] null 不渲染）。空字段如实呈现空串
 * 不编默认值。
 */
@Composable
private fun DeviceLayoutNodeDetailCard(
    node: DeviceLayoutNode,
    onLightUp: ((LayoutTreeNode) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = LocalToolboxSurfaceColors.current.surfaceDeep,
                shape = AppShapes.medium,
            )
            .padding(Dimens.space12),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        LayoutDetailFieldRow("class", node.className)
        if (isEngineSurfaceClassName(node.className)) {
            LayoutDetailFieldRow(
                "引擎面",
                "内容由渲染引擎自绘于本表面，不产生子层级——内部控件对层级检查不可见",
            )
        }
        LayoutDetailFieldRow("text", node.text)
        LayoutDetailFieldRow("resource-id", node.resourceId)
        LayoutDetailFieldRow("content-desc", node.contentDesc)
        LayoutDetailFieldRow("package", node.packageName)
        LayoutDetailFieldRow("bounds", layoutBoundsText(node.bounds))
        LayoutDetailFieldRow("尺寸", "${node.bounds.width} × ${node.bounds.height} px")
        LayoutDetailFieldRow("中心点", "(${node.bounds.centerX}, ${node.bounds.centerY})")
        layoutFlagsText(node).takeIf { it.isNotEmpty() }?.let { flags ->
            LayoutDetailFieldRow("标记", flags)
        }
        onLightUp?.let { lightUp ->
            TextButton(onClick = { lightUp(node) }) { Text("投屏点亮") }
        }
    }
}

/**
 * 选中节点的详情卡（全窗口视图源，同族深底内嵌区）：基础面（class/id/bounds/
 * 尺寸/窗口偏移/全局中心）+ 标记面 + View 内部行（padding 与属性表标量全集——
 * a11y 树没有的 measured/drawing/scroll 面在此）分组呈现；嵌套属性组按「有则
 * 呈现」追加（流内有 layoutParams 等 'M' 嵌套 map 组才渲染组头，空面零渲染——
 * 数据实有性决定能力面，不虚标）；text 按需补抓钮（View 源专属——本源 text 恒
 * 空串，经 uiautomator dump 按 bounds join）；「投屏点亮」钮同族。
 */
@Composable
private fun DeviceWindowViewDetailCard(
    node: WindowViewNode,
    onLightUp: ((LayoutTreeNode) -> Unit)? = null,
    textLoading: Boolean = false,
    textResult: String? = null,
    dumpSerialKnown: Boolean = true,
    snapshotLoading: Boolean = false,
    onFetchText: () -> Unit = {},
    onCancelFetchText: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = LocalToolboxSurfaceColors.current.surfaceDeep,
                shape = AppShapes.medium,
            )
            .padding(Dimens.space12),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        LayoutDetailFieldRow("class", node.className)
        if (isEngineSurfaceClassName(node.className)) {
            LayoutDetailFieldRow(
                "引擎面",
                "内容由渲染引擎自绘于本表面，不产生子层级——内部控件对层级检查不可见",
            )
        }
        LayoutDetailFieldRow("id", node.viewId)
        LayoutDetailFieldRow("bounds", layoutBoundsText(node.bounds))
        LayoutDetailFieldRow("尺寸", "${node.bounds.width} × ${node.bounds.height} px")
        windowOffsetText(node.windowLeft, node.windowTop)?.let { offset ->
            LayoutDetailFieldRow("窗口偏移", offset)
        }
        LayoutDetailFieldRow("全局中心", "(${node.globalBounds.centerX}, ${node.globalBounds.centerY})")
        windowViewFlagsText(node).takeIf { it.isNotEmpty() }?.let { flags ->
            LayoutDetailFieldRow("标记", flags)
        }
        node.padding?.let { p ->
            LayoutDetailFieldRow("padding", "l ${p.left} / t ${p.top} / r ${p.right} / b ${p.bottom}")
        }
        if (node.properties.isNotEmpty()) {
            Text(
                text = "View 内部",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            node.properties.forEach { (name, value) ->
                LayoutDetailFieldRow(name, value)
            }
        }
        // 嵌套属性组（组名 + 组内键值行，与「View 内部」组头同形态）
        node.nestedProperties.forEach { (group, entries) ->
            Text(
                text = group,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.forEach { (name, value) ->
                LayoutDetailFieldRow(name, value)
            }
        }
        // text 按需补抓（秒级命令走 busy 圈 + 统一停止钮档；置灰必附人话原因）
        AppButton(
            label = "按需补抓文本",
            busy = textLoading,
            busyLabel = "补抓文本中…",
            onClick = onFetchText,
            cancel = onCancelFetchText,
            kind = AppButtonKind.Secondary,
            disabledReason = when {
                !dumpSerialKnown -> "快照来源不明——重新抓取层级后再补抓文本"
                snapshotLoading -> "层级抓取进行中，稍后再补抓文本"
                else -> null
            },
        )
        if (textLoading) {
            Text(
                text = "uiautomator dump 约需数秒（真机实测 6.4s），仅覆盖当前聚焦窗口",
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        textResult?.let { result ->
            Text(
                text = result,
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        onLightUp?.let { lightUp ->
            TextButton(onClick = { lightUp(node) }) { Text("投屏点亮") }
        }
    }
}

@Composable
private fun LayoutDetailFieldRow(label: String, value: String) {
    Text(
        text = "$label  $value",
        style = DesktopTypography.mono,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
