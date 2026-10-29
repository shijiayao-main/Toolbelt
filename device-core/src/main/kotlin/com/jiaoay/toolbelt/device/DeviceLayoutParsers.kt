package com.jiaoay.toolbelt.device

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

// OPEN(真机门禁): 真机 uiautomator dump 单行 XML 格式跨 ROM 校准（本层 fixture
// 按实测基线实现：`<?xml ...?><hierarchy rotation="N"><node ...>`，node 属性面
// index/text/resource-id/class/package/content-desc/checkable/checked/clickable/
// enabled/focusable/focused/scrollable/long-clickable/password/selected/bounds）——
// 其他 ROM 格式不符显式失败不静默，格式样本扩充须带真机实测记录。

/**
 * 窗口层级纯解析层（XML 字符串 → [DeviceLayoutDump]，零副作用零 IO）：输入是
 * **不可信的设备侧 XML**（uiautomator 产物可被设备侧 ROM 改造/被恶意应用占位
 * 窗口注入内容），解析器经 XXE 加固——禁 DOCTYPE 声明（外部实体随之不可能），
 * 这是解析不可信 XML 的标准加固，非可选优化。
 *
 * 失败语义：任何格式不符抛 [IllegalArgumentException]（message 面向用户呈现），
 * 禁静默回空：
 * - 非 XML / 根元素非 `hierarchy` / `hierarchy` 下 node 子元素数非 1 → 显式失败
 *   （真机 dump 恒产单根节点树，多根/零根都是格式异常）；
 * - node 缺 bounds 属性或 bounds 形态不符 → 显式失败并带节点标识样例
 *   （坐标是本域核心载荷，缺坐标节点不产半残模型）；
 * - 未知元素（node/hierarchy 之外的子元素）容忍跳过——前向兼容取舍，ROM 加
 *   新元素不击穿整棵树；已知属性集之外的属性同样容忍缺席与未知名。
 *
 * 属性缺席契约（与 uiautomator 自身空值惯例对齐）：text/resource-id/class/
 * package/content-desc 缺席归空串；布尔旗标缺席归 false；index 缺席归 0；
 * rotation 缺席归 0。
 */
internal object DeviceLayoutParsers {

    private const val HIERARCHY_ELEMENT = "hierarchy"
    private const val NODE_ELEMENT = "node"

    /**
     * bounds 属性完整形态：`[left,top][right,bottom]`，坐标为十进制整数允许
     * 负值（屏幕外裁剪节点的合法形态）。整值匹配，部分形态（缺括号/缺半边/
     * 非整数坐标）不命中即失败。
     */
    private val boundsPattern = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    /**
     * 解析窗口层级 XML（uiautomator dump 产物全文）为 [DeviceLayoutDump]。
     *
     * @throws IllegalArgumentException 格式不符/含 DOCTYPE（XXE 拒绝）时
     */
    internal fun parseLayoutDump(xml: String): DeviceLayoutDump {
        val root = try {
            newSecureFactory().newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        } catch (e: Exception) {
            // XXE 拒绝（DOCTYPE 声明被加固配置拦下）与垃圾输入同走此路：设备侧
            // 产物不解析就是格式错误，异常原文透传供定位
            throw IllegalArgumentException("窗口层级 XML 解析失败（含 DOCTYPE/外部实体的输入被拒）：${e.message?.take(120) ?: "未知异常"}")
        }
        if (root.nodeName != HIERARCHY_ELEMENT) {
            throw IllegalArgumentException("窗口层级 XML 根元素不是 $HIERARCHY_ELEMENT：${root.nodeName.take(40)}")
        }
        val nodeElements = root.childNodeElements().filter { it.nodeName == NODE_ELEMENT }
        if (nodeElements.size != 1) {
            throw IllegalArgumentException("窗口层级 XML $HIERARCHY_ELEMENT 下 node 子元素数非 1（${nodeElements.size}）")
        }
        return DeviceLayoutDump(
            rotation = root.getAttribute("rotation").toIntOrNull() ?: 0,
            root = parseNode(nodeElements.first()),
        )
    }

    /**
     * 单个 node 元素 → [DeviceLayoutNode]：属性缺席按空串/假值/0 归一（见本层
     * KDoc 缺席契约）；bounds 缺席或形态不符显式失败带节点标识样例。
     */
    private fun parseNode(element: Element): DeviceLayoutNode {
        val boundsRaw = element.getAttribute("bounds")
        val bounds = parseBounds(boundsRaw)
            ?: throw IllegalArgumentException(
                "窗口层级节点 bounds 格式不符（class=${element.getAttribute("class").take(60)}，" +
                    "text=${element.getAttribute("text").take(40)}，bounds=${boundsRaw.take(40)}）",
            )
        return DeviceLayoutNode(
            index = element.getAttribute("index").toIntOrNull() ?: 0,
            text = element.getAttribute("text"),
            resourceId = element.getAttribute("resource-id"),
            className = element.getAttribute("class"),
            packageName = element.getAttribute("package"),
            contentDesc = element.getAttribute("content-desc"),
            checkable = element.getAttribute("checkable") == TRUE,
            checked = element.getAttribute("checked") == TRUE,
            clickable = element.getAttribute("clickable") == TRUE,
            enabled = element.getAttribute("enabled") == TRUE,
            focusable = element.getAttribute("focusable") == TRUE,
            focused = element.getAttribute("focused") == TRUE,
            scrollable = element.getAttribute("scrollable") == TRUE,
            longClickable = element.getAttribute("long-clickable") == TRUE,
            password = element.getAttribute("password") == TRUE,
            selected = element.getAttribute("selected") == TRUE,
            bounds = bounds,
            children = element.childNodeElements()
                .filter { it.nodeName == NODE_ELEMENT }
                .map { parseNode(it) },
        )
    }

    /**
     * bounds 属性解析（internal 供单测矩阵断言）：完整形态命中返回坐标对，
     * 空串/残缺/非整数形态返回 null（调用方显式失败，不猜坐标）。
     */
    internal fun parseBounds(value: String): DeviceLayoutBounds? {
        val match = boundsPattern.matchEntire(value) ?: return null
        val (left, top, right, bottom) = match.destructured
        return DeviceLayoutBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /**
     * XXE 加固解析器工厂（本层唯一构造点）：禁 DOCTYPE 声明（外部实体不可入）+
     * 安全处理特性。设备侧 XML 不可信，任何放宽须先过 XXE 负面单测。
     */
    private fun newSecureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
        isXIncludeAware = false
    }

    private const val TRUE = "true"
}

/** 元素的元素类型子节点序列（文本节点/注释跳过，文档序保持） */
private fun org.w3c.dom.Element.childNodeElements(): List<Element> {
    val children = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        (nodes.item(i) as? Element)?.let { children += it }
    }
    return children
}
