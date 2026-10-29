package com.jiaoay.toolbelt.device

/**
 * 窗口节点边界矩形（设备像素坐标）：bounds 属性 `[left,top][right,bottom]` 的
 * 解析产物。坐标可为负（屏幕外裁剪节点的合法形态），本类型不做几何校验——
 * 设备侧给什么记什么，越界裁剪由消费面按需判定。
 */
data class DeviceLayoutBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /** 宽度（像素）；right < left 的退化矩形允许负值，消费面自行容忍 */
    val width: Int get() = right - left

    /** 高度（像素）；同 [width] 口径 */
    val height: Int get() = bottom - top

    /** 中心点 X（像素）：触摸/点击定位的落点锚 */
    val centerX: Int get() = (left + right) / 2

    /** 中心点 Y（像素）：同 [centerX] 口径 */
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * 布局树节点最小公共面（树投影/搜索/点亮换算的源无关契约）：a11y 源
 * [DeviceLayoutNode] 与 View 内部源 [WindowViewNode] 的共同子集——消费面
 * （层级树懒展开、过滤词命中、投屏点亮落点）只依赖本接口，不感知数据源差异。
 *
 * [globalBounds] 是点亮换算的统一坐标面：a11y 树的 bounds 本就是屏幕全局坐标
 * （恒等 [bounds]）；View 源的 layout:* 是**父相对坐标**（View.getLeft 语义），
 * 全局面 = 自身 bounds + 祖先链累加偏移 + 所属窗口全局偏移（[translateBounds]，
 * 窗口偏移真源 = dvwv 流头 window:left/top 散键，祖先累加面 = 解码器递归下传的
 * Σ祖先 layout:left/top）。多显示设备上偏移归属节点所在 display，投屏帧跨
 * display 的落点校准属真门禁欠账（采样与换算校准记录
 * 检查器调研报告）。
 */
interface LayoutTreeNode {
    val className: String

    /** 节点文本（a11y 源为 a11y text；View 内部源无文本内容恒空串） */
    val text: String

    /** 资源 id（a11y 源 `pkg:id/name`；View 源 `id/name` 或 `NO_ID`） */
    val resourceId: String
    val clickable: Boolean

    /** 数据源原生坐标：a11y 源 = 屏幕全局；View 源 = 父相对（View.getLeft 语义） */
    val bounds: DeviceLayoutBounds

    /** 屏幕全局坐标 bounds（点亮落点真源；口径见本接口 KDoc） */
    val globalBounds: DeviceLayoutBounds

    /** 直接子节点（文档序 = 树投影展开序） */
    val children: List<LayoutTreeNode>

    /** 子孙节点总数（含自身）：树规模速览 */
    val descendantCount: Int
}

/**
 * bounds 平移纯函数（点亮换算的窗口偏移面）：局部 bounds + 窗口全局偏移 → 屏幕
 * 全局 bounds。允许负偏移与越界（多显示/屏外窗口的合法形态），不做几何钳制。
 */
fun translateBounds(bounds: DeviceLayoutBounds, dx: Int, dy: Int): DeviceLayoutBounds =
    DeviceLayoutBounds(bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy)

/**
 * 单个窗口层级节点（uiautomator dump 的 `node` 元素）：属性面 = dump 产物的
 * 全属性集，缺席属性按 uiautomator 自身空串/假值惯例归一（[DeviceLayoutParsers]
 * 的缺席契约）；[bounds] 为非空硬契约——bounds 缺席或形态不符的节点显式失败，
 * 不产 bounds=null 的半残模型（坐标是本域核心载荷，缺坐标的节点对定位无意义）。
 */
data class DeviceLayoutNode(
    val index: Int,
    override val text: String,
    override val resourceId: String,
    override val className: String,
    val packageName: String,
    val contentDesc: String,
    val checkable: Boolean,
    val checked: Boolean,
    override val clickable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val longClickable: Boolean,
    val password: Boolean,
    val selected: Boolean,
    override val bounds: DeviceLayoutBounds,
    override val children: List<DeviceLayoutNode> = emptyList(),
) : LayoutTreeNode {
    /** a11y 树坐标即屏幕全局坐标，全局面恒等原生 bounds */
    override val globalBounds: DeviceLayoutBounds get() = bounds

    /** 子孙节点总数（含自身）：树规模速览，零遍历成本提示 */
    override val descendantCount: Int get() = 1 + children.sumOf { it.descendantCount }
}

/**
 * 一次窗口层级 dump 的解析产物：[rotation] 为 hierarchy 根的屏幕旋转读数
 * （缺席归 0，信息面不参与校验）；[root] 为层级树根节点。
 */
data class DeviceLayoutDump(
    val rotation: Int,
    val root: DeviceLayoutNode,
) {
    /** 全树节点总数（含根）：dump 产物规模速览 */
    val nodeCount: Int get() = root.descendantCount
}
