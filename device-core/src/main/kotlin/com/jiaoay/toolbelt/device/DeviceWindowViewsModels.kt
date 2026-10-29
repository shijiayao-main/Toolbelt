package com.jiaoay.toolbelt.device

/**
 * 四向内边距（View 内部源的 `padding:*` 属性组解析产物）：设备像素，四向独立
 * 记录不做归一——设备侧给什么记什么。
 */
data class DeviceLayoutPadding(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * 单个 View 节点（dvwv 全窗口视图源，`cmd window dump-visible-window-views` 的
 * ViewHierarchyEncoder 流解码产物）：属性面 = View 内部属性（a11y 树没有的
 * padding/measured/drawing/scroll 全集在 [properties]）。
 *
 * 坐标契约（真机多窗口全节点实测基线）：流内
 * layout:left/top 是**父相对坐标**（AOSP View.getLeft 语义），[bounds] 恒为父相对
 * 原始值不做合成；屏幕全局面 [globalBounds] = 窗口偏移（[windowLeft]/[windowTop]，
 * 缺席按 0）+ 祖先链累加偏移（[ancestorAccLeft]/[ancestorAccTop]，解码器递归下传
 * Σ祖先 layout:left/top）+ 自身 [bounds]。
 *
 * 容错契约（解码器不炸的边界）：[bounds] 缺 layout:* 时按 width/height 回退
 * （left/top 归 0）；[padding]/[windowLeft]/[windowTop] 缺席为 null；[viewId]/
 * [viewHash] 缺席为空串；未知名属性一律进 [properties] 不丢弃（ROM 属性表差异
 * 不击穿模型——属性表 144 项为实测基线，ROM 增减均容忍）。
 *
 * [windowLeft]/[windowTop] 是所属窗口的全局偏移（流头 window:left/top 散键），
 * 随节点下沉使 [globalBounds] 自包含（点亮落点零外部上下文）。多显示设备上该
 * 偏移归属节点所在 display，跨 display 的投屏落点校准属真机门禁欠账。
 */
data class WindowViewNode(
    override val className: String,

    /** View 实例 hash（`meta:__hash__`，混淆类名窗口下区分同class节点的辅助面） */
    val viewHash: String,

    /** 资源 id（View#resolveId 产物：`id/name` 或 `NO_ID`） */
    val viewId: String,

    /** 父相对原始 bounds（layout:* 原值，AOSP View.getLeft 语义；祖先累加不回写本字段） */
    override val bounds: DeviceLayoutBounds,

    /** 所属窗口全局偏移 X（window:left 散键；缺席 = 偏移未知按 0 记） */
    val windowLeft: Int?,

    /** 所属窗口全局偏移 Y（window:top 散键；缺席 = 偏移未知按 0 记） */
    val windowTop: Int?,

    /**
     * 祖先链累计偏移 X（根到父节点的 Σ layout:left，不含窗口偏移与自身 bounds）：
     * 解码器递归下传填充，参与 [globalBounds] 合成。直接构造缺省 0 = 无祖先面
     * （合成退化为 bounds + 窗口偏移）——非解码产物无祖先真源，不编造偏移。
     */
    val ancestorAccLeft: Int = 0,

    /** 祖先链累计偏移 Y（同 [ancestorAccLeft] 口径） */
    val ancestorAccTop: Int = 0,

    /** View 可见性读数（0 可见 / 4 不可见占位 / 8 不占位；ROM 自定义值原样记录） */
    val visibility: Int,
    override val clickable: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val activated: Boolean,

    /** 四向内边距（padding:* 全四项齐才产出，缺任一为 null） */
    val padding: DeviceLayoutPadding?,

    /** 直接子节点声明数（`meta:__childCount__`，信息面；[children] 为结构真源） */
    val childCount: Int,
    override val children: List<WindowViewNode>,

    /**
     * 全量标量属性（名 → 值显示串，文档序）：详情卡「View 内部行」的投影源——只收
     * 标量（含已建类型的冗余项）；嵌套 map 值进 [nestedProperties]，两面正交不重叠。
     */
    val properties: Map<String, String>,

    /**
     * 嵌套 map 属性（组名 → 组内标量属性面）：dvwv 流内 'M' 嵌套 map 值的一层展开
     * 产物——组内值为标量的直收（内层键经流末 propertyIndex 对照表解析），组内再
     * 嵌套的 map 值序列化为显示串兜底（不丢弃）。流内无嵌套 map 时恒空表。
     */
    val nestedProperties: Map<String, Map<String, String>> = emptyMap(),
) : LayoutTreeNode {

    /** View 内部源无文本内容（文本属 a11y 源，本源恒空串） */
    override val text: String get() = ""

    override val resourceId: String get() = viewId

    /**
     * 屏幕全局 bounds = 窗口偏移（缺席按 0）+ 祖先链累加偏移 + 自身父相对 bounds
     * （合成口径见类头坐标契约；偏移缺席按 0 记）
     */
    override val globalBounds: DeviceLayoutBounds
        get() = translateBounds(
            bounds,
            ancestorAccLeft + (windowLeft ?: 0),
            ancestorAccTop + (windowTop ?: 0),
        )

    /** 子孙节点总数（含自身）：树规模速览 */
    override val descendantCount: Int get() = 1 + children.sumOf { it.descendantCount }
}

/**
 * 单个可见窗口的视图层级条目（dvwv ZIP 的一个条目）：条目名 = `<windowHash>
 * <空格><窗口标题或组件名>`。
 *
 * [packageName] 归属契约：条目名为组件名形态（`pkg/类名`，Activity 窗口）时取
 * `/` 前的包名；系统本地窗口（标题如 `StatusBar`/`android`）无组件名形态，
 * 归属为 null——不猜不 join（dumpsys window join 属后续增强，非本模型契约）。
 *
 * [windowLeft]/[windowTop] 为 null 表示设备侧流未带窗口偏移散键（跨 ROM 变体），
 * 消费面的点亮落点按偏移 0 处理并自担校准风险。
 */
data class DeviceWindowViewsEntry(
    val windowHash: String,
    val title: String,
    val packageName: String?,
    val windowLeft: Int?,
    val windowTop: Int?,
    val root: WindowViewNode,
) {
    /** 全树节点总数（含根）：窗口层级规模速览 */
    val nodeCount: Int get() = root.descendantCount
}

/**
 * 一次全窗口视图抓取的解析产物：[windows] 为全部可见窗口（ZIP 条目序——设备侧
 * 写出序，非 Z 序；Z 序真源在 dumpsys window，不在本模型契约内）。
 */
data class DeviceWindowViewsDump(
    val windows: List<DeviceWindowViewsEntry>,
) {
    /** 全窗口节点总数：抓取规模速览 */
    val nodeCount: Int get() = windows.sumOf { it.nodeCount }
}
