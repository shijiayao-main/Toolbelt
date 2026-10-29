package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.jiaoay.toolbelt.device.DeviceLayoutBounds
import com.jiaoay.toolbelt.device.DeviceLayoutDump
import com.jiaoay.toolbelt.device.DeviceLayoutNode
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.device.DeviceWindowViewsDump
import com.jiaoay.toolbelt.device.DeviceWindowViewsEntry
import com.jiaoay.toolbelt.device.LayoutTreeNode

/**
 * 层级树默认展开深度：深度 < 2 的节点默认展开（根与其直接子层展开，孙层收起——
 * 懒展开策略：设备层级 nodeCount 可上千，全量展开默认会渲染整棵树，用户逐层
 * 点开）。深度以根为 0 计。
 */
internal const val LAYOUT_DEFAULT_EXPAND_DEPTH = 2

/** 根节点行 id（子层 id = 父 id + `/` + 子索引，如 `0/1/2`） */
internal const val LAYOUT_ROOT_ID = "0"

/**
 * 快照数据源（树投影/详情卡分组按源分派；面板态可观察面的一部分故公开）：
 * [WINDOW_VIEWS] = 全窗口视图源（`cmd window dump-visible-window-views`，View
 * 内部属性面 + 窗口清单）；[UI_AUTOMATOR] = uiautomator dump 降级源（a11y 属性面
 * + text 内容，仅聚焦窗口）。
 */
enum class LayoutSnapshotSource { WINDOW_VIEWS, UI_AUTOMATOR }

/**
 * 层级树单行渲染投影：[id] 是根到节点的子索引路径（树内唯一且稳定，作为
 * 展开/选中态的键）；[expanded] 已按展开规则归并（无子节点恒 false）；
 * [matchesQuery] = 本节点命中过滤词（自由浏览态恒 false，不参与呈现语义）。
 */
data class DeviceLayoutRow(
    val id: String,
    val depth: Int,
    val node: LayoutTreeNode,
    val expanded: Boolean,
    val hasChildren: Boolean,
    val matchesQuery: Boolean,
)

/**
 * 单节点是否命中过滤词（internal 供单测）：空词恒命中（不过滤）；非空 = text /
 * resource-id / class 三字段**包含**匹配（大小写不敏感——设备控件排查「这个文本/
 * 这个 id 是谁」是主路径，class 兜底找同类控件；全窗口视图源无 text 字段，
 * 该面恒空串不参与命中）。
 */
internal fun layoutNodeMatches(node: LayoutTreeNode, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return node.text.lowercase().contains(q) ||
        node.resourceId.lowercase().contains(q) ||
        node.className.lowercase().contains(q)
}

/**
 * 按路径 id 解析节点（internal 供单测）：`0` = 根；`0/1/2` = 根第 1 子的第 2
 * 子。路径与节点树结构不符（越界/非数字段）返回 null——查询面不猜不修正。
 */
internal fun findLayoutNode(root: LayoutTreeNode, id: String): LayoutTreeNode? {
    var node: LayoutTreeNode = root
    val segments = id.split('/')
    for (i in 1 until segments.size) {
        val index = segments[i].toIntOrNull() ?: return null
        node = node.children.getOrNull(index) ?: return null
    }
    return node
}

/**
 * 层级树的可见行投影（internal 供单测）：深度优先展开序输出可渲染行。
 *
 * 投影 base 参数化（子树视图的收口点）：[baseNode] = 投影根节点，[baseId] = 该
 * 节点的**窗口绝对路径 id**（全树态 = 窗口根与 [LAYOUT_ROOT_ID]）；行 id 恒保持
 * 窗口绝对路径（baseId + 子索引段），与选中/展开记忆/revealNode 同 id 体系——
 * 进出子树零 id 换制；[DeviceLayoutRow.depth] 相对 base 计（base 自身 = 0，缩进
 * 随子树重定）。
 *
 * 展开规则（懒展开策略的两态契约）：
 * - 自由浏览态（空词）：默认展开深度 < [LAYOUT_DEFAULT_EXPAND_DEPTH] 的节点
 *  （深度相对 base 计），用户点开/收起经 [expandedMap] 记忆覆盖（true 展开 /
 *   false 收起，未记录走默认）；
 * - 查询态（非空词）：命中节点的祖先路径自动展开（不要求用户逐层点开），覆盖
 *   顺序 = [expandedMap] 记忆优先、未记录恒展开——用户在查询态收起的子树不被
 *   命中重新顶开；无命中子树整枝不进投影（含根——全树零命中即投影为空，面板
 *   出「无匹配」占位）。
 */
internal fun visibleLayoutRows(
    baseNode: LayoutTreeNode,
    expandedMap: Map<String, Boolean>,
    query: String,
    baseId: String = LAYOUT_ROOT_ID,
): List<DeviceLayoutRow> {
    val q = query.trim()
    val queryActive = q.isNotEmpty()
    val rows = mutableListOf<DeviceLayoutRow>()

    fun subtreeMatches(node: LayoutTreeNode): Boolean =
        layoutNodeMatches(node, q) || node.children.any { subtreeMatches(it) }

    fun emit(node: LayoutTreeNode, id: String, depth: Int) {
        if (queryActive && !subtreeMatches(node)) return
        val expanded = when {
            queryActive -> expandedMap[id] ?: true
            else -> expandedMap[id] ?: (depth < LAYOUT_DEFAULT_EXPAND_DEPTH)
        }
        rows += DeviceLayoutRow(
            id = id,
            depth = depth,
            node = node,
            expanded = expanded && node.children.isNotEmpty(),
            hasChildren = node.children.isNotEmpty(),
            matchesQuery = queryActive && layoutNodeMatches(node, q),
        )
        if (node.children.isNotEmpty() && expanded) {
            node.children.forEachIndexed { index, child -> emit(child, "$id/$index", depth + 1) }
        }
    }

    emit(baseNode, baseId, 0)
    return rows
}

/** 降级人话（dvwv 失败原文 + 降级源能力差如实标注，不静默） */
internal fun layoutDegradedNotice(windowViewsError: String): String =
    "全窗口视图抓取不可用（$windowViewsError）——已降级 uiautomator dump：" +
        "仅聚焦窗口、无 View 内部属性"

/**
 * a11y 树 × 目标 bounds → 节点文本 join（internal 供单测）：第一优先 = 树内 bounds
 * 与 [target] **逐值相等**且 text 非空的节点；兜底 = 无逐值相等时取重叠面积比
 * （target ∩ node / target 面积）最大且比值 ≥ 0.5、text 非空的节点。双条件皆空
 * 返回 null（消费面人话兜底）。重叠匹配策略未做真机验证（真源见
 * 采样与换算的落点校准记录）。
 */
internal fun joinTextByBounds(
    a11yRoot: DeviceLayoutNode,
    target: DeviceLayoutBounds,
): String? {
    var exact: String? = null
    var fallback: String? = null
    var fallbackRatio = 0.5f

    fun visit(node: DeviceLayoutNode) {
        if (exact == null && node.text.isNotEmpty()) {
            val b = node.bounds
            if (b.left == target.left && b.top == target.top &&
                b.right == target.right && b.bottom == target.bottom
            ) {
                exact = node.text
            } else if (target.width > 0 && target.height > 0) {
                val intersectionW = minOf(b.right, target.right) - maxOf(b.left, target.left)
                val intersectionH = minOf(b.bottom, target.bottom) - maxOf(b.top, target.top)
                if (intersectionW > 0 && intersectionH > 0) {
                    val ratio = intersectionW.toFloat() * intersectionH /
                        (target.width.toFloat() * target.height)
                    if (ratio >= 0.5f && (fallback == null || ratio > fallbackRatio)) {
                        fallbackRatio = ratio
                        fallback = node.text
                    }
                }
            }
        }
        node.children.forEach { visit(it) }
    }

    visit(a11yRoot)
    return exact ?: fallback
}

/**
 * 布局面板状态（请求-回执模型）：层级快照只在用户动作时抓取，无轮询环——
 * 「窗口内容变了」不被本 VM 感知，重新抓取是显式动作。
 *
 * 契约：
 * - [source] null = 尚未抓取（无快照）；[WINDOW_VIEWS] 快照 = 会话级（不落盘，
 *   重抓替换）；[UI_AUTOMATOR] 快照 = dvwv 失败后的显式降级（[degradedNotice]
 *   非空并上屏，降级原因人话原文透传）；失败不覆盖旧快照（重试语义，旧树留屏）；
 * - [windows] = 全窗口视图源的窗口清单（ZIP 条目序）；[UI_AUTOMATOR] 源恒空；
 *   [selectedWindowIndex] 是当前查看窗口的下标（重抓成功复位 0）；
 * - [root] = 当前查看窗口的树根（全窗口视图源 = 选中窗口根；降级源 = dump 根），
 *   由 [windows]/[legacyRoot]/[selectedWindowIndex] 派生，面板只读；
 * - [dumpSerial] = 快照的抓取来源串号（refresh 成功落值、失败随旧快照保留、
 *   换目标随快照清空；快照非空而 dumpSerial null 生产面不可达——投屏点亮门控
 *   以「快照来源不明」拒绝指路，见 DeviceObsPage.layoutLightUpDecision）；
 * - [rows] 是 [root] 按投影 base（[subtreeStack] 栈顶，空栈 = 窗口根）的可见行
 *   投影（[visibleLayoutRows]），行 id 恒窗口绝对路径；快照缺席恒空列表（面板只
 *   消费 rows）；
 * - [subtreeStack] 是子树视图栈（尾元素 = 当前树投影根的窗口绝对路径 id；空 =
 *   全树态）：左图双击命中节点入栈（[enterSubtree]——栈式视图历史，一次退一层，
 *   树列返回钮 [exitSubtree] 出栈）；行 id/展开记忆/选中不随进出子树换体系。
 *   **selectWindow/refresh 成功/目标变更（两分支）随快照与选中态一并复位**；
 *   refresh 失败随旧快照契约保留；栈顶路径在当前树上不可解析（复位集已覆盖
 *   换树面的理论不可达态）经 [updateState] 自愈清栈。
 * - [expanded] 是展开态记忆（key = 行路径 id；true 展开 / false 收起，未记录走
 *   默认深度），**重抓成功与切换窗口即清空**（展开记忆对另一棵树无意义）；
 * - [query] 是过滤词（纯显示层投影零 spawn）；重抓与切窗保留过滤词（与文件/应用
 *   子页「翻页不清词」同口径——过滤词是显示偏好）；
 * - [selectedNodeId]/[selectedNode] 是选中详情卡的节点（null = 无展开）；选中
 *   不因收起祖先/过滤挤出投影而丢（路径在树上仍可解析，清过滤即回看）；重抓、
 *   换窗口与换设备复位；
 * - [loading] = 抓取在途（busy 期间再点被拒——单飞防双发）；[error] 为面向
 *   用户的失败回执（人话原样呈现），下一动作开始即清零；
 * - [textLoading]/[textResult] = 按需补抓 text 的在途位与结果行（View 内部源无
 *   文本，显式动作经 [fetchNodeText] 复用降级源 uiautomator dump 按 bounds join；
 *   竞态契约见 [fetchNodeText] KDoc）；[diagramLinked] = 选中节点上投屏帧画框
 *   的联动开关（纯投影态不落盘，会话级偏好，关 = 选中不上帧）；
 *   [diagramDrawnOnly] = 预览绘制面开关（true = 只画实际绘制节点，isDrawn 级联
 *   收窄；默认关 = 全层级直出，不可见节点的淡化呈现是排查入口恒保留）。
 */
data class DeviceLayoutPanelState(
    val source: LayoutSnapshotSource? = null,
    val degradedNotice: String? = null,
    val windows: List<DeviceWindowViewsEntry> = emptyList(),
    val selectedWindowIndex: Int = 0,
    val legacyRoot: DeviceLayoutNode? = null,
    val root: LayoutTreeNode? = null,
    val nodeCount: Int = 0,
    val dumpSerial: String? = null,
    val rows: List<DeviceLayoutRow> = emptyList(),
    val query: String = "",
    val expanded: Map<String, Boolean> = emptyMap(),
    val subtreeStack: List<String> = emptyList(),
    val selectedNodeId: String? = null,
    val selectedNode: LayoutTreeNode? = null,
    val loading: Boolean = false,
    val error: String? = null,

    /** 按需补抓 text 的在途位（单飞：busy 期间再点拒） */
    val textLoading: Boolean = false,

    /** 按需补抓 text 的结果行（成功 = 节点 text 原文；失败/未抓到 = 人话回执），null = 无 */
    val textResult: String? = null,

    /** 选中节点上投屏帧画框的联动开关（true = 联动上帧；会话级偏好不落盘） */
    val diagramLinked: Boolean = true,

    /** 预览绘制面开关（true = 只画实际绘制到屏幕的节点；会话级偏好不落盘，默认全层级直出） */
    val diagramDrawnOnly: Boolean = false,
)

/** 抓取编排的内部产物（快照态装配面；成功路径经 [DeviceLayoutViewModel.refresh] 落状态） */
private data class LayoutSnapshot(
    val source: LayoutSnapshotSource,
    val windows: List<DeviceWindowViewsEntry>,
    val legacyRoot: DeviceLayoutNode?,
    val degradedNotice: String?,
) {
    companion object {
        fun ofWindowViews(dump: DeviceWindowViewsDump) =
            LayoutSnapshot(LayoutSnapshotSource.WINDOW_VIEWS, dump.windows, null, null)

        fun ofLegacy(dump: DeviceLayoutDump, notice: String) =
            LayoutSnapshot(LayoutSnapshotSource.UI_AUTOMATOR, emptyList(), dump.root, notice)
    }
}

/**
 * 窗口布局 VM（设备观测空间「布局」子页的层级快照与投影状态源）：把层级抓取请求
 * 派发给注入的检查内核——主源 [dumpWindowViews]（全窗口视图，多窗口 + View 内部
 * 属性），降级源 [dumpHierarchy]（uiautomator dump，仅聚焦窗口 a11y 面）；挂起函数
 * 注入缝——单测 fake 即可离线驱动，零真子进程。
 *
 * - 抓取是**显式动作**（[refresh]）：dvwv/降级 dump 在设备上是秒级命令，不随进页/
 *   换目标自动发起（与文件/应用子页的轻量列举不同口径）；目标不可用（无设备/多设备
 *   未选/检测失败）时 [refresh] 落人话回执（不静默不 spawn）；
 * - 降级编排（[loadSnapshot]）：主源失败 → 降级源重试一次，成功落
 *   [LayoutSnapshotSource.UI_AUTOMATOR] 快照 + [layoutDegradedNotice] 人话（原因
 *   原文透传，不静默）；降级也失败 → 组合两条原因的失败回执；
 * - 竞态收束：在途抓取被目标变更作废（请求序号守卫，迟到快照不上屏）；loading
 *   单飞——在途抓取中再点被拒（防双发）；
 * - 目标串号变更 → 快照清空（不同设备的窗口层级不可互推），不自动重抓（回执
 *   无语，显式再抓即可）；目标从有到无 → 快照清空并回执失效原因——陈旧设备的
 *   层级树不得继续冒充可读数；
 * - [refresh] 成功记录 [loadedSerial]：设备流的启动期首拍不把用户已抓的快照
 *   误清（首拍串号与抓取串号一致即零动作）。
 */
class DeviceLayoutViewModel(
    private val devices: DeviceStateSource,
    private val dumpWindowViews: suspend (serial: String) -> Result<DeviceWindowViewsDump>,
    private val dumpHierarchy: suspend (serial: String) -> Result<DeviceLayoutDump>,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceLayoutPanelState())
    val state: StateFlow<DeviceLayoutPanelState> = _state.asStateFlow()

    /** 当前快照的抓取串号（串号变更检测锚；null = 从未抓取或目标已失效） */
    private var loadedSerial: String? = null

    /** 在途抓取请求序号（回写前比对，被目标变更作废） */
    private var requestSeq = 0

    /**
     * 按需补抓 text 的独立请求序号。不得与 [requestSeq] 共用：补抓若自增共享序号，
     * refresh 在途时发起补抓会作废 refresh 回执致 loading 永久 true；不自增则补抓回执
     * 又不受作废保护——独立序号两侧各自成立（作废方与复位责任绑定见
     * [invalidateTextFetch]）。
     */
    private var textRequestSeq = 0

    /** 在途补抓协程（供取消；null = 无在途补抓） */
    private var textJob: Job? = null

    init {
        viewModelScope.launch {
            combine(devices.deviceState, devices.selectedSerial) { _, _ -> resolveTargetSerial() }
                .distinctUntilChanged()
                .collect { target -> onTargetChanged(target) }
        }
    }

    /** 更新过滤词（纯显示层投影零 spawn，重抓不清词） */
    fun setQuery(query: String) {
        updateState { it.copy(query = query) }
    }

    /** 线框图 ↔ 投屏帧联动开关（纯投影态不落盘，会话级偏好） */
    fun setDiagramLinked(linked: Boolean) {
        updateState { it.copy(diagramLinked = linked) }
    }

    /** 预览绘制面开关（纯投影态不落盘，会话级偏好；默认关 = 全层级直出排查语义保留） */
    fun setDiagramDrawnOnly(enabled: Boolean) {
        updateState { it.copy(diagramDrawnOnly = enabled) }
    }

    /**
     * 切换查看窗口（纯显示投影零 spawn）：全窗口视图快照内在途下标切换——树随窗
     * 换（展开记忆/选中态对新树无意义即清空，过滤词保留）；非全窗口视图快照或
     * 下标越界为无动作（查询面不猜不修正）。
     */
    fun selectWindow(index: Int) {
        invalidateTextFetch()
        updateState { current ->
            val valid = current.source == LayoutSnapshotSource.WINDOW_VIEWS &&
                index in current.windows.indices
            if (!valid) {
                current
            } else {
                current.copy(
                    selectedWindowIndex = index,
                    expanded = emptyMap(),
                    subtreeStack = emptyList(),
                    selectedNodeId = null,
                    textResult = null,
                )
            }
        }
    }

    /**
     * 抓取当前可见窗口层级（手动抓取/失败重试共用入口）：目标不可用时落人话回执
     * 不 spawn；在途抓取中再点被拒（单飞）。成功替换快照并清空展开/选中态（窗口
     * 选择复位首窗），失败保留旧快照只落回执。
     */
    fun refresh() {
        if (_state.value.loading) return
        // 在途补抓被新抓取作废（互斥矩阵单向面：refresh 照常放行，进入即作废补抓；
        // busy 位随作废方复位——迟到补抓回执经 textRequestSeq 失配静默丢弃）
        invalidateTextFetch()
        val serial = loadedSerial ?: resolveTargetSerial()
        if (serial == null) {
            updateState { it.copy(error = targetBlockedReceipt()) }
            return
        }
        loadedSerial = serial
        val seq = ++requestSeq
        updateState { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = loadSnapshot(serial)
            if (seq != requestSeq) return@launch
            result.fold(
                onSuccess = { snapshot ->
                    updateState {
                        it.copy(
                            source = snapshot.source,
                            degradedNotice = snapshot.degradedNotice,
                            windows = snapshot.windows,
                            legacyRoot = snapshot.legacyRoot,
                            selectedWindowIndex = 0,
                            dumpSerial = serial,
                            loading = false,
                            error = null,
                            expanded = emptyMap(),
                            subtreeStack = emptyList(),
                            selectedNodeId = null,
                        )
                    }
                },
                onFailure = { e ->
                    updateState { it.copy(loading = false, error = e.message ?: "窗口层级抓取失败（未知错误）") }
                },
            )
        }
    }

    /**
     * 降级编排：主源（全窗口视图）失败 → 降级源（uiautomator dump）重试一次；
     * 两者都失败 = 组合人话（主源原因 + 降级原因，原文透传供跨 ROM 排障）。
     */
    private suspend fun loadSnapshot(serial: String): Result<LayoutSnapshot> {
        val windowViews = dumpWindowViews(serial)
        windowViews.getOrNull()?.let { return Result.success(LayoutSnapshot.ofWindowViews(it)) }
        val windowViewsError = windowViews.exceptionOrNull()?.message ?: "未知原因"

        val legacy = dumpHierarchy(serial)
        return legacy.fold(
            onSuccess = { Result.success(LayoutSnapshot.ofLegacy(it, layoutDegradedNotice(windowViewsError))) },
            onFailure = { e ->
                Result.failure(
                    IllegalStateException(
                        "全窗口视图抓取失败（$windowViewsError）；降级 uiautomator dump 也失败：${e.message ?: "未知错误"}",
                    ),
                )
            },
        )
    }

    /**
     * 展开箭头动作：把节点当前展开态取反写进展开记忆（自由浏览态覆盖默认深度，
     * 查询态覆盖命中自动展开），**同时选中该行**（不取反——选中消费既有
     * selectedId→diagramEnsureVisible/详情卡管道，点箭头即见该节点详情与图上定位；
     * 与 [selectNode] 的取反语义并存：箭头是展开 affordance 非选中开关）。行不在
     * 当前投影内为无动作（不可见行无箭头）。选中变化即在途补抓作废 + 结果行清空
     * （与 [selectNode] 同契约）。
     */
    fun toggleNode(id: String) {
        val row = _state.value.rows.firstOrNull { it.id == id } ?: return
        if (_state.value.selectedNodeId != id) invalidateTextFetch()
        updateState {
            it.copy(
                expanded = it.expanded + (id to !row.expanded),
                selectedNodeId = id,
            )
        }
    }

    /**
     * 进入子树（左图双击命中节点的动作）：把节点的窗口绝对路径 id 压入
     * [DeviceLayoutPanelState.subtreeStack]（栈式视图历史——树投影重定到该节点
     * 为 base，行 id 恒绝对路径零换制）。栈顶已是该节点为无动作（同点连击不长栈）；
     * 路径在当前树上不可解析为无动作（查询面不猜不修正）；无快照无动作。选中态
     * 与展开记忆不动（选中行进入子树后是 base 行，恒在投影首行）。
     */
    fun enterSubtree(id: String) {
        val root = _state.value.root ?: return
        if (findLayoutNode(root, id) == null) return
        updateState { current ->
            if (current.subtreeStack.lastOrNull() == id) {
                current
            } else {
                current.copy(subtreeStack = current.subtreeStack + id)
            }
        }
    }

    /** 退出子树一层（树列返回钮）：栈顶出栈；空栈无动作（全树态无上层可退）。 */
    fun exitSubtree() {
        if (_state.value.subtreeStack.isEmpty()) return
        updateState { it.copy(subtreeStack = it.subtreeStack.dropLast(1)) }
    }

    /**
     * 整树展开：对每个有子节点的行路径写展开记忆 true（真机多层树常见 7~12 层深，
     * 默认 2 层下 fragment 元素不可见——一键铺开免逐层手点）。无快照零动作。
     */
    fun expandAllNodes() = setAllNodesExpanded(true)

    /** 整树收起：展开记忆全量覆写 false（收回到只剩根行）。无快照零动作。 */
    fun collapseAllNodes() = setAllNodesExpanded(false)

    private fun setAllNodesExpanded(expanded: Boolean) {
        val root = _state.value.root ?: return
        val all = buildMap {
            fun walk(node: LayoutTreeNode, id: String) {
                if (node.children.isNotEmpty()) {
                    put(id, expanded)
                    node.children.forEachIndexed { index, child -> walk(child, "$id/$index") }
                }
            }
            walk(root, LAYOUT_ROOT_ID)
        }
        updateState { it.copy(expanded = all) }
    }

    /**
     * 选中节点展开详情卡（再点同节点 = 收起）：选中不即时展开祖先（收起态下被
     * 过滤/折叠挤出的选中保留，路径在树上仍可解析——清过滤即回看）。选中节点
     * 变化即在途补抓作废 + [DeviceLayoutPanelState.textResult] 清空（不跨节点残留）。
     */
    fun selectNode(id: String) {
        invalidateTextFetch()
        updateState { it.copy(selectedNodeId = if (it.selectedNodeId == id) null else id, textResult = null) }
    }

    /**
     * 预览图点击揭示（预览是全层级直出，命中节点可能在树投影外）：展开 [id] 的全部
     * 祖先路径（[DeviceLayoutPanelState.expanded] 写 true——查询态与自由浏览态的展开
     * 语义同归并，树投影即时纳入该节点）+ 直接选中不取反（再点同节点保持选中——
     * 预览命中的意图是「看这个节点」，收起是树行的显式 affordance）。命中节点在
     * 当前子树外（不等于栈顶也不在栈顶路径下）时**先清子树栈回全树**——不退出则
     * 展开的祖先不进投影，「图点击 → 树内可见定位」链路静默失效。路径在当前树上
     * 不可解析（越界/非数字段）为无动作（查询面不猜不修正）；选中变化即在途补抓
     * 作废 + 结果行清空（与 [selectNode] 同契约）。
     */
    fun revealNode(id: String) {
        val root = _state.value.root ?: return
        if (findLayoutNode(root, id) == null) return
        invalidateTextFetch()
        updateState { current ->
            val baseId = current.subtreeStack.lastOrNull() ?: LAYOUT_ROOT_ID
            val insideSubtree = id == baseId || id.startsWith("$baseId/")
            val ancestors = buildMap {
                put(LAYOUT_ROOT_ID, true)
                var path = LAYOUT_ROOT_ID
                id.split('/').drop(1).dropLast(1).forEach { segment ->
                    path = "$path/$segment"
                    put(path, true)
                }
            }
            current.copy(
                subtreeStack = if (insideSubtree) current.subtreeStack else emptyList(),
                expanded = current.expanded + ancestors,
                selectedNodeId = id,
                textResult = null,
            )
        }
    }

    /**
     * 按需补抓 text（显式动作，View 内部源详情卡专属）：复用注入缝
     * [dumpHierarchy]（uiautomator dump，真机实测秒级——按需不自动），按
     * [LayoutTreeNode.globalBounds] 与 a11y 树 join（[joinTextByBounds]）。
     *
     * 前置拒绝（不静默，人话落 [DeviceLayoutPanelState.textResult]）：快照来源
     * 串号缺席 / 抓取在途 / 补抓在途（单飞）。
     *
     * 竞态与互斥契约（独立序号 [textRequestSeq]，不复用抓取的 requestSeq——共用
     * 会把 refresh 在途时的补抓回执作废逻辑搅成互相清位）：
     * - 互斥矩阵：[DeviceLayoutPanelState.loading] 在途 → 补抓拒；补抓在途 →
     *   [refresh] 照常放行但进入即作废在途补抓——单向拒 + 作废清理，无双向死锁面；
     * - 作废方与复位责任绑定：refresh/切窗/选中变化/目标变更进入时调
     *   [invalidateTextFetch]（序号自增 + 取消协程 + busy 位复位——迟到回执经
     *   序号失配静默丢弃零状态写入，不残留卡死 busy）；
     * - 成功 → `text  <原文>`；join 不到 → 聚焦窗口边界人话（uiautomator dump 仅
     *   覆盖当前聚焦窗口）；失败 → 人话原文。
     */
    fun fetchNodeText(node: LayoutTreeNode) {
        val current = _state.value
        when {
            current.dumpSerial == null -> {
                updateState { it.copy(textResult = "快照来源不明——重新抓取层级后再补抓文本") }
                return
            }

            current.loading -> {
                updateState { it.copy(textResult = "层级抓取进行中，稍后再补抓文本") }
                return
            }

            current.textLoading -> return
        }
        val serial = current.dumpSerial ?: return
        val seq = ++textRequestSeq
        updateState { it.copy(textLoading = true, textResult = null) }
        textJob = viewModelScope.launch {
            val receipt = dumpHierarchy(serial).fold(
                onSuccess = { dump ->
                    val joined = joinTextByBounds(dump.root, node.globalBounds)
                    if (joined != null) {
                        "text  $joined"
                    } else {
                        "未在聚焦窗口 a11y 树中找到该节点的文本（uiautomator dump 仅覆盖当前聚焦窗口）"
                    }
                },
                onFailure = { e -> e.message ?: "文本补抓失败（未知错误）" },
            )
            if (seq != textRequestSeq) return@launch
            updateState { it.copy(textLoading = false, textResult = receipt) }
        }
    }

    /** 取消补抓（取消即无回执，静默归零——按钮回归可点态） */
    fun cancelFetchText() {
        invalidateTextFetch()
    }

    /**
     * 在途补抓作废收口（作废方与复位责任绑定）：序号自增（迟到回执失配静默丢弃
     * 零状态写入）+ 取消协程 + busy 位与结果行复位。无在途补抓时仅清结果行（幂等）。
     */
    private fun invalidateTextFetch() {
        if (_state.value.textLoading) {
            textRequestSeq++
            textJob?.cancel()
            textJob = null
        }
        updateState { it.copy(textLoading = false, textResult = null) }
    }

    /**
     * 目标变更消费：串号实变 → 在途作废 + 快照/选中态清空（不自动重抓——显式
     * 动作契约见类头）；目标从有到无 → 同样清空并回执失效原因。
     */
    private fun onTargetChanged(target: String?) {
        if (target != null && target != loadedSerial) {
            loadedSerial = target
            requestSeq++
            invalidateTextFetch()
            updateState {
                it.copy(
                    source = null,
                    degradedNotice = null,
                    windows = emptyList(),
                    legacyRoot = null,
                    selectedWindowIndex = 0,
                    dumpSerial = null,
                    selectedNodeId = null,
                    loading = false,
                    error = null,
                    expanded = emptyMap(),
                    subtreeStack = emptyList(),
                )
            }
        } else if (target == null && loadedSerial != null) {
            loadedSerial = null
            requestSeq++
            invalidateTextFetch()
            updateState {
                it.copy(
                    source = null,
                    degradedNotice = null,
                    windows = emptyList(),
                    legacyRoot = null,
                    selectedWindowIndex = 0,
                    dumpSerial = null,
                    selectedNodeId = null,
                    loading = false,
                    expanded = emptyMap(),
                    subtreeStack = emptyList(),
                    error = "目标设备已不可用（断开或未选定），窗口层级快照已失效",
                )
            }
        }
    }

    /**
     * 唯一状态写入点：统一重算当前查看窗口树根、投影 base、节点数、可见行投影与
     * 选中节点解析——快照/窗口选择/过滤词/展开记忆/选中键/子树栈任何之一变更都
     * 收敛到同一管道，防投影漏算。投影 base = [DeviceLayoutPanelState.subtreeStack]
     * 栈顶解析（空栈 = 窗口根）；栈顶不可解析（换树面已被复位集覆盖的理论不可达
     * 态）自愈清栈按全树投影，不猜不修。
     */
    private fun updateState(transform: (DeviceLayoutPanelState) -> DeviceLayoutPanelState) {
        _state.update { current ->
            val next = transform(current)
            val root: LayoutTreeNode? = when (next.source) {
                LayoutSnapshotSource.WINDOW_VIEWS -> next.windows.getOrNull(next.selectedWindowIndex)?.root
                LayoutSnapshotSource.UI_AUTOMATOR -> next.legacyRoot
                null -> null
            }
            val baseId = next.subtreeStack.lastOrNull()
            val baseNode = baseId?.let { id -> root?.let { r -> findLayoutNode(r, id) } }
            val effectiveBaseId = if (baseNode != null) baseId else LAYOUT_ROOT_ID
            val effectiveBase = baseNode ?: root
            next.copy(
                subtreeStack = if (baseNode != null || baseId == null) next.subtreeStack else emptyList(),
                root = root,
                nodeCount = root?.descendantCount ?: 0,
                rows = effectiveBase?.let {
                    visibleLayoutRows(it, next.expanded, next.query, effectiveBaseId)
                } ?: emptyList(),
                selectedNode = root
                    ?.let { r -> next.selectedNodeId?.let { id -> findLayoutNode(r, id) } },
            )
        }
    }

    /** 目标不可用人话回执（无设备/检测失败/多设备未选三分支，与文件/应用子页同配方） */
    private fun targetBlockedReceipt(): String = when {
        devices.detectError.value != null ->
            "设备检测失败：${devices.detectError.value}——请检查设置页 ADB 路径"

        devices.deviceState.value is DeviceState.None ->
            "未检测到设备，请连接设备（或刷新检测）"

        devices.deviceState.value is DeviceState.Multi && devices.selectedSerial.value == null ->
            "多设备连接，请先在上方设备栏选择目标设备"

        else -> "目标设备不可用（未检测到设备或未选定）"
    }

    /** 目标串号解析：Multi 用会话级选择；Single 回退唯一台；None = null */
    private fun resolveTargetSerial(): String? = when (val device = devices.deviceState.value) {
        is DeviceState.Single -> device.serial
        is DeviceState.Multi -> devices.selectedSerial.value
        is DeviceState.None -> null
    }
}
