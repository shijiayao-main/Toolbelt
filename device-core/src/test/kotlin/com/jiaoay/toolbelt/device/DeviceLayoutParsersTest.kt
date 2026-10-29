package com.jiaoay.toolbelt.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 窗口层级纯解析层守护（fixture 矩阵直测，零 IO）：
 * - 真机实测基线形态：单行 `<?xml ...?><hierarchy rotation>` 单根 node 树，属性面
 *   全集 + 嵌套 node 子树逐层构建；
 * - 属性缺席归一：text/resource-id 等缺席归空串、布尔缺席归 false、rotation 缺席
 *   归 0、index 缺席归 0（bounds 缺席是显式失败不是归一，见失败矩阵）；
 * - bounds 解析矩阵：完整形态/负坐标/残缺半边/非整数/带空格/非匹配尾巴；
 * - 失败矩阵（禁静默回空）：非 XML 垃圾/根元素非 hierarchy/空树（node 子元素数
 *   0）/多根 node/bounds 缺席/形态不符——均显式失败带定位样例；
 * - XXE 负面：含 DOCTYPE/外部实体的输入被加固配置拒绝（不可信设备侧 XML）；
 * - 未知元素容忍跳过（前向兼容：ROM 加新元素不击穿整棵树）。
 */
class DeviceLayoutParsersTest {

    /** 真机实测结构裁样本（单行形态 + 三层嵌套 + 属性面全集） */
    private val carHmiFixture = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>" +
        "<hierarchy rotation=\"90\">" +
        "<node index=\"0\" text=\"\" resource-id=\"\" class=\"android.widget.FrameLayout\" package=\"com.example.app\" " +
        "content-desc=\"\" checkable=\"false\" checked=\"false\" clickable=\"false\" enabled=\"true\" " +
        "focusable=\"false\" focused=\"false\" scrollable=\"false\" long-clickable=\"false\" password=\"false\" " +
        "selected=\"false\" bounds=\"[984,102][2844,1464]\">" +
        "<node index=\"0\" text=\"设置\" resource-id=\"com.example.app:id/title\" class=\"android.widget.TextView\" " +
        "package=\"com.example.app\" content-desc=\"\" checkable=\"false\" checked=\"false\" clickable=\"true\" " +
        "enabled=\"true\" focusable=\"true\" focused=\"true\" scrollable=\"false\" long-clickable=\"true\" " +
        "password=\"false\" selected=\"true\" bounds=\"[1000,200][1500,300]\">" +
        "<node index=\"0\" text=\"子项\" resource-id=\"\" class=\"android.widget.Button\" package=\"com.example.app\" " +
        "content-desc=\"详情\" checkable=\"true\" checked=\"true\" clickable=\"true\" enabled=\"true\" " +
        "focusable=\"true\" focused=\"false\" scrollable=\"false\" long-clickable=\"false\" password=\"true\" " +
        "selected=\"false\" bounds=\"[0,-50][100,0]\"/>" +
        "</node>" +
        "</node>" +
        "</hierarchy>"

    // ── 正常形态矩阵 ──

    @Test
    fun `parses real-device baseline with full attribute set and nesting`() {
        val dump = DeviceLayoutParsers.parseLayoutDump(carHmiFixture)
        assertEquals(90, dump.rotation)
        assertEquals("android.widget.FrameLayout", dump.root.className)
        assertEquals("com.example.app", dump.root.packageName)
        assertEquals(DeviceLayoutBounds(984, 102, 2844, 1464), dump.root.bounds)
        assertEquals(1914, dump.root.bounds.centerX)
        assertEquals(1860, dump.root.bounds.width)
        assertEquals(3, dump.nodeCount)
        assertEquals(1, dump.root.children.size)
        assertEquals(1, dump.root.children[0].children.size)
    }

    @Test
    fun `parses nested node attributes`() {
        val title = DeviceLayoutParsers.parseLayoutDump(carHmiFixture).root.children[0]
        assertEquals("设置", title.text)
        assertEquals("com.example.app:id/title", title.resourceId)
        assertEquals("android.widget.TextView", title.className)
        assertTrue(title.clickable)
        assertTrue(title.focused)
        assertTrue(title.longClickable)
        assertTrue(title.selected)
        val leaf = title.children[0]
        assertEquals("子项", leaf.text)
        assertEquals("详情", leaf.contentDesc)
        assertTrue(leaf.checkable)
        assertTrue(leaf.checked)
        assertTrue(leaf.password)
        // 负坐标（屏幕外裁剪节点）是合法形态
        assertEquals(DeviceLayoutBounds(0, -50, 100, 0), leaf.bounds)
        assertEquals(50, leaf.bounds.centerX)
    }

    @Test
    fun `absent attributes normalize to empty and false defaults`() {
        val xml = "<hierarchy><node bounds=\"[0,0][10,10]\"><node bounds=\"[1,1][2,2]\"/></node></hierarchy>"
        val dump = DeviceLayoutParsers.parseLayoutDump(xml)
        assertEquals(0, dump.rotation) // rotation 缺席归 0
        val root = dump.root
        assertEquals("", root.text)
        assertEquals("", root.resourceId)
        assertEquals("", root.className)
        assertEquals("", root.packageName)
        assertEquals("", root.contentDesc)
        assertEquals(0, root.index) // index 缺席归 0
        assertEquals(false, root.clickable)
        assertEquals(false, root.enabled)
        assertEquals(1, root.children.size)
    }

    @Test
    fun `unknown child elements are tolerated and node children keep document order`() {
        val xml = "<hierarchy>" +
            "<node bounds=\"[0,0][10,10]\">" +
            "<extra-metadata/>" +
            "<node text=\"first\" bounds=\"[0,0][1,1]\"/>" +
            "<another-thing/>" +
            "<node text=\"second\" bounds=\"[2,2][3,3]\"/>" +
            "</node>" +
            "</hierarchy>"
        val children = DeviceLayoutParsers.parseLayoutDump(xml).root.children
        assertEquals(listOf("first", "second"), children.map { it.text })
    }

    @Test
    fun `hierarchy without xml declaration is accepted`() {
        val xml = "<hierarchy rotation=\"0\"><node text=\"ok\" bounds=\"[0,0][5,5]\"/></hierarchy>"
        assertEquals("ok", DeviceLayoutParsers.parseLayoutDump(xml).root.text)
    }

    // ── bounds 解析矩阵 ──

    @Test
    fun `parseBounds accepts full and negative coordinate forms`() {
        assertEquals(DeviceLayoutBounds(0, 0, 100, 200), DeviceLayoutParsers.parseBounds("[0,0][100,200]"))
        assertEquals(DeviceLayoutBounds(-10, -5, 0, 0), DeviceLayoutParsers.parseBounds("[-10,-5][0,0]"))
        assertEquals(
            DeviceLayoutBounds(2844, 1464, 984, 102),
            DeviceLayoutParsers.parseBounds("[2844,1464][984,102]"),
        ) // 退化矩形不校验，消费面自行容忍
    }

    @Test
    fun `parseBounds rejects malformed forms`() {
        assertNull(DeviceLayoutParsers.parseBounds(""))
        assertNull(DeviceLayoutParsers.parseBounds("[0,0][100")) // 残缺半边
        assertNull(DeviceLayoutParsers.parseBounds("[a,b][c,d]")) // 非整数坐标
        assertNull(DeviceLayoutParsers.parseBounds("[0, 0][10, 10]")) // 内嵌空格
        assertNull(DeviceLayoutParsers.parseBounds("[0,0][100,200]tail")) // 非匹配尾巴
        assertNull(DeviceLayoutParsers.parseBounds("[0,0]")) // 单括号对
    }

    // ── 失败矩阵（禁静默回空）──

    @Test
    fun `non-XML garbage fails explicitly`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DeviceLayoutParsers.parseLayoutDump("UI hierchary dumped to: /data/local/tmp/toolbelt_uidump.xml")
        }
        assertTrue(e.message!!.contains("XML 解析失败"))
    }

    @Test
    fun `wrong root element fails explicitly`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DeviceLayoutParsers.parseLayoutDump("<foo><node bounds=\"[0,0][1,1]\"/></foo>")
        }
        assertTrue(e.message!!.contains("根元素不是 hierarchy"))
    }

    @Test
    fun `empty hierarchy without node fails explicitly`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DeviceLayoutParsers.parseLayoutDump("<hierarchy rotation=\"0\"></hierarchy>")
        }
        assertTrue(e.message!!.contains("node 子元素数非 1（0）"))
    }

    @Test
    fun `multiple root nodes fail explicitly`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DeviceLayoutParsers.parseLayoutDump(
                "<hierarchy><node bounds=\"[0,0][1,1]\"/><node bounds=\"[0,0][1,1]\"/></hierarchy>",
            )
        }
        assertTrue(e.message!!.contains("node 子元素数非 1（2）"))
    }

    @Test
    fun `absent bounds fails explicitly with node identity`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DeviceLayoutParsers.parseLayoutDump(
                "<hierarchy><node text=\"no-bounds\" class=\"android.widget.TextView\"/></hierarchy>",
            )
        }
        assertTrue(e.message!!.contains("bounds 格式不符"))
        assertTrue(e.message!!.contains("android.widget.TextView"))
    }

    @Test
    fun `malformed bounds inside nested node fails explicitly`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DeviceLayoutParsers.parseLayoutDump(
                "<hierarchy><node bounds=\"[0,0][10,10]\" text=\"parent\">" +
                    "<node text=\"bad\" bounds=\"[0,0][100\"/></node></hierarchy>",
            )
        }
        assertTrue(e.message!!.contains("bounds 格式不符"))
        assertTrue(e.message!!.contains("bad"))
    }

    // ── XXE 负面（不可信设备侧 XML 标准加固）──

    @Test
    fun `DOCTYPE declaration is rejected`() {
        val xxe = "<!DOCTYPE hierarchy [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
            "<hierarchy>&xxe;<node bounds=\"[0,0][1,1]\"/></hierarchy>"
        val e = assertFailsWith<IllegalArgumentException> { DeviceLayoutParsers.parseLayoutDump(xxe) }
        assertTrue(e.message!!.contains("DOCTYPE"))
    }

    @Test
    fun `external parameter entity is rejected`() {
        val xxe = "<!DOCTYPE hierarchy [<!ENTITY % ext SYSTEM \"file:///etc/passwd\"> %ext;]>" +
            "<hierarchy><node bounds=\"[0,0][1,1]\"/></hierarchy>"
        assertFailsWith<IllegalArgumentException> { DeviceLayoutParsers.parseLayoutDump(xxe) }
    }
}
