package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.device.DeviceWindowViewsFixtures.viewStream
import com.jiaoay.toolbelt.device.DeviceWindowViewsFixtures.renderForAssert
import com.jiaoay.toolbelt.device.DeviceWindowViewsFixtures.StreamEncoder
import com.jiaoay.toolbelt.device.DeviceWindowViewsFixtures.NodeFixture
import com.jiaoay.toolbelt.device.DeviceWindowViewsDecoder.DecoderFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ViewHierarchyDecoder 守护（夹具编码器与解码器同契约双向钉死，线格式漂移由
 * 原始字节断言兜底）：
 * - 树样例逐字断言：真机 StatusBar 样本（调研报告）经真 ZIP 打包 → 解码 →
 *   渲染与报告样本逐字一致（class 简名/id/bounds/层级缩进）；
 * - 线格式原始字节：流头散键 'S'+id+类型标签大端序、map 收尾 'S' 0x0000、流末
 *   对照表含属性名；
 * - 类型面：int/float/bool/short/string 五标签 + padding 四值齐备产出模型字段；
 * - 嵌套 map 收编：'M' 嵌套 map 值展开进 nestedProperties（内层键经 nameTable 解析
 *   为 String、未登记内层 id `#<id>` 兜底、组内深嵌套兜底为 `名=值` 显示串），
 *   标量面不混入嵌套组；
 * - 点亮换算纯函数：translateBounds 与 WindowViewNode.globalBounds（窗口偏移 +
 *   祖先链累加双面合成，layout:* 父相对语义）双面断言（MapMainActivity 实测偏移
 *   样例锚）；直接构造节点祖先面缺省 0（合成退化为 bounds + 窗口偏移）；
 * - 容错面：未知类型标签/流截断按条目隔离（坏条目跳过保其余窗口）、未知属性 ID
 *   `#<id>` 形式入属性面、padding 缺任一为 null、偏移散键缺席为 null 且
 *   globalBounds 恒等原生 bounds（根节点无祖先面、偏移按 0 回落）；
 * - 条目名归属：组件名形态取包名，系统窗标题归属 null 不猜。
 */
class DeviceWindowViewsDecoderTest {

    // ── 树样例逐字断言（真机 StatusBar 样本） ──

    @Test
    fun `status bar sample decodes to report tree verbatim`() {
        val zip = DeviceWindowViewsFixtures.windowViewsZip(
            "1151e7a StatusBar" to DeviceWindowViewsFixtures.statusBarStream(),
        )
        val windows = DeviceWindowViewsDecoder.decodeZip(zip)

        assertEquals(1, windows.size)
        val window = windows.single()
        assertEquals("1151e7a", window.windowHash)
        assertEquals("StatusBar", window.title)
        assertEquals(null, window.packageName, "系统窗标题无组件名形态，归属不猜")
        assertEquals(0, window.windowLeft)
        assertEquals(0, window.windowTop)

        val expected = """
            RelativeLayout id=NO_ID [0,0][2880,100] vis=0
              NestedScrollLinearLayout id=id/ll_persist_group [2393,0][2880,100]
                OemLinearLayout id=NO_ID [360,29][487,71]
                  View id=id/view_divider [103,0][127,1]
                  TextClock id=id/clock [0,0][103,42]
                BaseView id=NO_ID [270,5][360,95]
                  ImageView id=id/iv_state_icon [13,13][77,77]
        """.trimIndent()
        assertEquals(expected, renderForAssert(window.root), "树样例须与夹具样本逐字一致")
        assertEquals(7, window.nodeCount)
    }

    @Test
    fun `typed fields decode padding flags and view internal properties`() {
        val window = DeviceWindowViewsDecoder.decodeZip(
            DeviceWindowViewsFixtures.windowViewsZip("h root" to DeviceWindowViewsFixtures.statusBarStream()),
        ).single()

        val root = window.root
        assertEquals(DeviceLayoutPadding(16, 0, 16, 0), root.padding)
        assertEquals(0, root.visibility)
        assertEquals(false, root.clickable)
        assertEquals(true, root.enabled)
        assertEquals("android.widget.RelativeLayout", root.className)
        assertEquals("hash-NO_ID", root.viewHash, "hash 面为夹具派生值（fixture view() 以 viewId 派生）")
        assertEquals("NO_ID", root.viewId)
        assertEquals(1, root.childCount)
        assertEquals("", root.text, "文本内容属 a11y 源，View 内部源恒空串（调研契约）")
        assertEquals("1.0", root.properties["drawing:alpha"], "float 标签入 View 内部行投影源")
        assertTrue("misc:visibility" !in root.properties, "已建类型字段不入 View 内部行投影面")
        assertTrue("layout:left" !in root.properties, "bounds 已由 typed 字段承载，不重复入投影面")
        val clock = root.children.single() // NestedScrollLinearLayout
            .children.first() // OemLinearLayout
            .children.first { it.viewId == "id/clock" }
        assertEquals("android.widget.TextClock", clock.className)
        assertEquals(true, clock.clickable)
    }

    // ── 嵌套 map 收编（'M' 值面 → nestedProperties） ──
    // 键形态为假设占位：真机流内嵌套 map 实有内容未取证（施工环境无连接设备），
    // 收编语义按「有则呈现」验证通路。

    @Test
    fun `nested map properties expand with name table resolved inner keys`() {
        val enc = StreamEncoder()
        enc.beginObject("android.widget.FrameLayout", "h")
        enc.intProperty("layout:left", 0)
        enc.intProperty("layout:top", 0)
        enc.intProperty("layout:right", 100)
        enc.intProperty("layout:bottom", 100)
        enc.floatProperty("drawing:alpha", 1.0f)
        enc.mapProperty("layoutParams") {
            intProperty("lp:leftMargin", 4)
            intProperty("lp:topMargin", 8)
            stringProperty("lp:layoutGravityName", "TOP")
        }
        enc.mapProperty("deepGroup") {
            boolProperty("flag", true)
            mapProperty("innerGroup") {
                intProperty("deep", 7)
            }
        }
        enc.endObject()
        enc.endStream()

        val root = DeviceWindowViewsDecoder.decodeEntry("h v", enc.toByteArray()).root
        assertEquals(
            mapOf("lp:leftMargin" to "4", "lp:topMargin" to "8", "lp:layoutGravityName" to "TOP"),
            root.nestedProperties["layoutParams"],
            "组内标量直收，内层键经对照表解析为 String（Int 键 toString 是错误修法）",
        )
        assertEquals(
            mapOf("flag" to "true", "innerGroup" to "deep=7"),
            root.nestedProperties["deepGroup"],
            "组内再嵌套的 map 值兜底为 名=值 逗号显示串不丢弃",
        )
        assertEquals("1.0", root.properties["drawing:alpha"], "标量面不受嵌套收编影响")
        assertTrue("layoutParams" !in root.properties, "嵌套组不混入标量面（两面正交）")
        assertTrue("deepGroup" !in root.properties)
    }

    @Test
    fun `nested map inner key without name table entry falls back to hash id`() {
        val enc = StreamEncoder()
        enc.beginObject("android.widget.FrameLayout", "h")
        enc.mapProperty("unknownInner") {
            unregisteredKeyProperty(999, 'I', byteArrayOf(0, 0, 0, 42))
        }
        enc.endObject()
        enc.endStream()

        val root = DeviceWindowViewsDecoder.decodeEntry("h v", enc.toByteArray()).root
        assertEquals(
            mapOf("#999" to "42"),
            root.nestedProperties["unknownInner"],
            "内层键解析失败以 #<id> 兜底不炸不猜（与外层未知属性名同纪律）",
        )
    }

    @Test
    fun `scalar only stream yields empty nested properties`() {
        val window = DeviceWindowViewsDecoder.decodeZip(
            DeviceWindowViewsFixtures.windowViewsZip("h root" to DeviceWindowViewsFixtures.statusBarStream()),
        ).single()
        assertTrue(
            window.root.nestedProperties.isEmpty(),
            "流内无嵌套 map 时恒空表（收编照做无害）",
        )
    }

    // ── 线格式原始字节断言（漂移兜底） ──

    @Test
    fun `raw wire bytes follow aosp encoder contract`() {
        val stream = DeviceWindowViewsFixtures.statusBarStream()
        // 流头散键：'S'(0x53) + uint16 id=1（window:left 首个 intern）+ 'I'(0x49) + int32 0 大端
        assertEquals(
            listOf(0x53, 0x00, 0x01, 0x49, 0x00, 0x00, 0x00, 0x00),
            stream.take(8).map { it.toInt() and 0xFF },
            "window:left 散键线格式漂移",
        )
        // window:top：'S' + id=2 + 'I' + 0
        assertEquals(
            listOf(0x53, 0x00, 0x02, 0x49, 0x00, 0x00, 0x00, 0x00),
            stream.drop(8).take(8).map { it.toInt() and 0xFF },
            "window:top 散键线格式漂移",
        )
        assertEquals(0x4D, stream[16].toInt() and 0xFF, "散键后紧跟根视图 'M' map 开")
        // 流以对照表 map 收尾键终结；属性名条目存在于流内（propertyIndex 面）
        assertEquals(
            listOf(0x53, 0x00, 0x00),
            stream.takeLast(3).map { it.toInt() and 0xFF },
            "流末对照表 map 以 'S' 0x0000 收尾",
        )
        assertTrue(
            String(stream, Charsets.ISO_8859_1).contains("propertyIndex") &&
                String(stream, Charsets.ISO_8859_1).contains("meta:__name__"),
            "流末 propertyIndex 对照表须含属性名条目",
        )
        // map 收尾键 'S' 0x0000 全流出现次数 ≥ 节点 map 数（8 视图 map + 对照表）
        val terminatorCount = stream.toList().windowed(3)
            .count { it[0].toInt() and 0xFF == 0x53 && it[1].toInt() and 0xFF == 0x00 && it[2].toInt() and 0xFF == 0x00 }
        assertTrue(terminatorCount >= 9, "map 收尾键应覆盖全部节点 map + 对照表，实际 $terminatorCount")
    }

    // ── 点亮换算纯函数（bounds + window:left/top） ──

    @Test
    fun `translate bounds shifts all corners`() {
        assertEquals(
            DeviceLayoutBounds(1932, 102, 2844, 1464),
            translateBounds(DeviceLayoutBounds(0, 0, 912, 1362), 1932, 102),
            "窗口全局偏移换算（调研报告 MapMainActivity 实测样例锚：窗口 left=1932/top=102，局部 912x1362）",
        )
        assertEquals(
            DeviceLayoutBounds(-10, -5, 0, 5),
            translateBounds(DeviceLayoutBounds(0, 0, 10, 10), -10, -5),
            "负偏移（多显示/屏外窗口）合法不钳制",
        )
    }

    @Test
    fun `window view node global bounds apply window offset`() {
        val window = DeviceWindowViewsDecoder.decodeZip(
            DeviceWindowViewsFixtures.windowViewsZip(
                "2815ba9 com.demo.launcher/com.demo.launcher.MapMainActivity" to
                    viewStream(
                        NodeFixture("android.widget.FrameLayout", "NO_ID", 0, 0, 912, 1362),
                        windowLeft = 1932,
                        windowTop = 102,
                    ),
            ),
        ).single()

        assertEquals("com.demo.launcher", window.packageName, "Activity 窗口条目名即包名归属")
        assertEquals(1932, window.windowLeft)
        assertEquals(102, window.windowTop)
        val root = window.root
        assertEquals(DeviceLayoutBounds(0, 0, 912, 1362), root.bounds, "原生 bounds 恒父相对原始值（祖先累加不回写 bounds）")
        assertEquals(DeviceLayoutBounds(1932, 102, 2844, 1464), root.globalBounds, "全局面 = 局部 + 窗口偏移")
        assertEquals(2388, root.globalBounds.centerX, "点亮落点 = 换算后全局面中心")
        assertEquals(783, root.globalBounds.centerY)
    }

    // ── 祖先偏移累加（globalBounds 合成面：layout:* 父相对语义的合成断言） ──

    @Test
    fun `nested child global bounds accumulate parent layout offset`() {
        // 父 [100,50] + 子相对 [20,30] → 子全局面 = 窗口偏移 + (100+20, 50+30)
        val window = DeviceWindowViewsDecoder.decodeZip(
            DeviceWindowViewsFixtures.windowViewsZip(
                "h nested" to viewStream(
                    NodeFixture(
                        className = "android.widget.FrameLayout",
                        viewId = "NO_ID",
                        left = 100, top = 50, right = 900, bottom = 850,
                        children = listOf(
                            NodeFixture("android.widget.Button", "id/btn", 20, 30, 120, 80),
                        ),
                    ),
                    windowLeft = 1000,
                    windowTop = 200,
                ),
            ),
        ).single()

        val root = window.root
        val child = root.children.single()
        assertEquals(DeviceLayoutBounds(100, 50, 900, 850), root.bounds, "根原生 bounds 恒父相对原始值")
        assertEquals(DeviceLayoutBounds(1100, 250, 1900, 1050), root.globalBounds, "根全局面 = 窗口偏移 + 自身 bounds（无祖先面）")
        assertEquals(DeviceLayoutBounds(20, 30, 120, 80), child.bounds, "子原生 bounds 恒父相对原始值（累加不回写 bounds）")
        assertEquals(DeviceLayoutBounds(1120, 280, 1220, 330), child.globalBounds, "子全局面 = 窗口偏移 + 父 layout 偏移 + 自身父相对 bounds")
    }

    @Test
    fun `three level nesting accumulates offsets across the full ancestor chain`() {
        // 三层链：L1 [10,20] → L2 [5,8] → L3 [2,3] → L3 全局面 = 窗口 + (10+5+2, 20+8+3)
        val window = DeviceWindowViewsDecoder.decodeZip(
            DeviceWindowViewsFixtures.windowViewsZip(
                "h deep" to viewStream(
                    NodeFixture(
                        className = "android.widget.FrameLayout",
                        viewId = "NO_ID",
                        left = 10, top = 20, right = 500, bottom = 400,
                        children = listOf(
                            NodeFixture(
                                className = "android.widget.LinearLayout",
                                viewId = "id/l2",
                                left = 5, top = 8, right = 300, bottom = 200,
                                children = listOf(
                                    NodeFixture("android.widget.TextView", "id/l3", 2, 3, 52, 43),
                                ),
                            ),
                        ),
                    ),
                    windowLeft = 500,
                    windowTop = 100,
                ),
            ),
        ).single()

        val l2 = window.root.children.single()
        val l3 = l2.children.single()
        assertEquals(10, l2.ancestorAccLeft, "L2 祖先面 = 根 layout:left（10）")
        assertEquals(20, l2.ancestorAccTop, "L2 祖先面 = 根 layout:top（20）")
        assertEquals(15, l3.ancestorAccLeft, "L3 祖先面 = Σ根与 L2 的 layout:left（10+5）")
        assertEquals(28, l3.ancestorAccTop, "L3 祖先面 = Σ根与 L2 的 layout:top（20+8）")
        assertEquals(DeviceLayoutBounds(515, 128, 810, 320), l2.globalBounds, "L2 全局面 = 窗口偏移 + 根偏移 + 自身 bounds")
        assertEquals(DeviceLayoutBounds(517, 131, 567, 171), l3.globalBounds, "L3 全局面 = 窗口偏移 + 两层祖先累加 + 自身 bounds")
    }

    @Test
    fun `absent window offset falls back to zero in accumulation synthesis`() {
        // 无散键流（跨 ROM 变体）：窗口偏移 null → 0 回落，祖先累加照常合成
        val enc = StreamEncoder()
        enc.view(
            NodeFixture(
                className = "android.widget.FrameLayout",
                viewId = "NO_ID",
                left = 40, top = 60, right = 400, bottom = 360,
                children = listOf(
                    NodeFixture("android.widget.Button", "id/btn", 10, 20, 110, 120),
                ),
            ),
        )
        enc.endStream()
        val window = DeviceWindowViewsDecoder.decodeZip(
            DeviceWindowViewsFixtures.windowViewsZip("h nooffset" to enc.toByteArray()),
        ).single()

        assertEquals(null, window.windowLeft, "偏移散键缺席为 null")
        assertEquals(null, window.windowTop)
        val child = window.root.children.single()
        assertEquals(DeviceLayoutBounds(50, 80, 150, 180), child.globalBounds, "窗口偏移按 0 回落，子全局面 = 父 layout 偏移 + 自身 bounds")
    }

    @Test
    fun `directly constructed node defaults ancestor accumulation to zero`() {
        // 直接构造（不经解码器）无祖先真源，祖先面缺省 0 = 合成退化为 bounds + 窗口偏移
        val node = WindowViewNode(
            className = "android.widget.FrameLayout",
            viewHash = "",
            viewId = "NO_ID",
            bounds = DeviceLayoutBounds(0, 0, 912, 1362),
            windowLeft = 1932,
            windowTop = 102,
            visibility = 0,
            clickable = false,
            enabled = true,
            selected = false,
            activated = false,
            padding = null,
            childCount = 0,
            children = emptyList(),
            properties = emptyMap(),
        )
        assertEquals(0, node.ancestorAccLeft, "直接构造祖先面缺省 0（不编造偏移）")
        assertEquals(0, node.ancestorAccTop)
        assertEquals(DeviceLayoutBounds(1932, 102, 2844, 1464), node.globalBounds, "合成退化为 bounds + 窗口偏移")
    }

    // ── 容错面 ──

    @Test
    fun `property id without name table entry survives with hash name`() {
        // 手工流：根 map 含 key=999 属性，对照表只登记已知名（999 缺席）——容错不炸
        val stream = byteArrayOf(
            0x53, 0x00, 0x01, 0x49, 0x00, 0x00, 0x00, 0x00, // 散键 id1 int 0（名不可解析，偏移缺席面）
            0x4D, // 根视图 map 开
            0x53, 0x00, 0x03, 0x52, 0x00, 0x04, 'V'.code.toByte(), 'i'.code.toByte(), 'e'.code.toByte(), 'w'.code.toByte(), // id3 = "View"
            0x53, 0x03, 0xE7.toByte(), 0x49, 0x00, 0x00, 0x00, 0x2A, // id=999（无表项）int 42
            0x53, 0x00, 0x00, // 根 map 收尾
            0x4D, // 对照表 map 开
            0x53, 0x00, 0x03, 0x52, 0x00, 0x03, 'i'.code.toByte(), 'd'.code.toByte(), '3'.code.toByte(), // id3 → "id3"
            0x53, 0x00, 0x00, // 对照表收尾
        )
        val window = DeviceWindowViewsDecoder.decodeEntry("h v", stream)
        assertEquals("", window.root.className, "对照表缺 meta:__name__ 时 class 容错归空不炸")
        assertEquals(
            "42",
            window.root.properties["#999"],
            "未知属性 ID 以 #<id> 形式入属性面（容错不炸）",
        )
        assertEquals(null, window.windowLeft, "散键名不可解析（对照表缺项）时偏移按缺席面处理")
    }

    @Test
    fun `unknown type tag skips only the corrupt entry`() {
        val good = "z good" to viewStream(NodeFixture("android.view.View", "NO_ID", 0, 0, 10, 10))
        val enc = StreamEncoder()
        enc.intProperty("window:left", 0)
        enc.intProperty("window:top", 0)
        enc.beginObject("android.view.View", "h")
        enc.rawProperty("rogue", 'Q', byteArrayOf(1, 2, 3))
        enc.endObject()
        enc.endStream()
        val bad = "a corrupt" to enc.toByteArray()

        val windows = DeviceWindowViewsDecoder.decodeZip(DeviceWindowViewsFixtures.windowViewsZip(bad, good))
        assertEquals(listOf("z"), windows.map { it.windowHash }, "坏条目隔离跳过，其余窗口存活")
    }

    @Test
    fun `truncated stream fails entry level`() {
        val full = viewStream(NodeFixture("android.view.View", "NO_ID", 0, 0, 10, 10))
        assertFailsWith<DecoderFormatException> {
            DeviceWindowViewsDecoder.decodeEntry("h v", full.copyOfRange(0, (full.size * 0.6).toInt()))
        }
    }

    @Test
    fun `missing padding key yields null padding and missing window keys yield null offset`() {
        // 无散键流（跨 ROM 变体：dumpEncoded 形态不含 window 偏移）
        val enc = StreamEncoder()
        enc.view(NodeFixture("android.view.View", "NO_ID", 0, 0, 10, 10))
        enc.endStream()
        val window = DeviceWindowViewsDecoder.decodeZip(
            DeviceWindowViewsFixtures.windowViewsZip("h v" to enc.toByteArray()),
        ).single()
        assertEquals(null, window.root.padding, "padding 四值不齐为 null")
        assertEquals(null, window.windowLeft, "偏移散键缺席为 null")
        assertEquals(null, window.windowTop)
        assertEquals(window.root.bounds, window.root.globalBounds, "偏移缺席全局面恒等局部 bounds")
    }

    @Test
    fun `entry name parsing covers component and system titles`() {
        val stream = viewStream(NodeFixture("android.view.View", "NO_ID", 0, 0, 10, 10))
        assertEquals(
            null,
            DeviceWindowViewsDecoder.decodeEntry("1151e7a StatusBar", stream).packageName,
            "系统窗标题归属 null 不猜",
        )
        assertEquals(
            null,
            DeviceWindowViewsDecoder.decodeEntry("61ca917 android", stream).packageName,
        )
        val spaced = DeviceWindowViewsDecoder.decodeEntry("2081997 InsTradition Main Window", stream)
        assertEquals("2081997", spaced.windowHash, "hash 取首 token")
        assertEquals("InsTradition Main Window", spaced.title, "标题可含空格（hash 后全部归属标题）")
        assertEquals(
            "com.demo.desktop",
            DeviceWindowViewsDecoder.decodeEntry(
                "f23068e com.demo.desktop/com.demo.desktop.LauncherActivity",
                stream,
            ).packageName,
        )
    }

    @Test
    fun `empty zip decodes to empty list for caller explicit failure`() {
        assertEquals(emptyList(), DeviceWindowViewsDecoder.decodeZip(DeviceWindowViewsFixtures.windowViewsZip()))
    }

    // ── 子序与计数校验 ──

    @Test
    fun `child entries sort by meta child index not document order`() {
        // 文档写出序：A 挂 __child__1、B 挂 __child__0（乱序写出）——解码按下标排序
        val stream = DeviceWindowViewsFixtures.corruptChildStream(
            childKeys = listOf("meta:__child__1", "meta:__child__0"),
            childCount = 2,
            children = listOf(
                NodeFixture("com.demo.ChildA", "NO_ID", 0, 0, 10, 10),
                NodeFixture("com.demo.ChildB", "NO_ID", 0, 0, 10, 10),
            ),
        )
        val root = DeviceWindowViewsDecoder.decodeEntry("h win", stream).root
        assertEquals(
            listOf("com.demo.ChildB", "com.demo.ChildA"),
            root.children.map { it.className },
            "子序真源 = meta:__child__N 后缀下标，非文档写出序（乱序写出防树错位）",
        )
    }

    @Test
    fun `child count mismatch fails entry with explicit corruption`() {
        val stream = DeviceWindowViewsFixtures.corruptChildStream(
            childKeys = listOf("meta:__child__0"),
            childCount = 2,
            children = listOf(NodeFixture("com.demo.ChildA", "NO_ID", 0, 0, 10, 10)),
        )
        val error = assertFailsWith<DecoderFormatException> {
            DeviceWindowViewsDecoder.decodeEntry("h win", stream)
        }
        assertTrue(
            error.message?.contains("子视图计数不符") == true && error.message?.contains("2") == true,
            "计数不符显式失败带节点标识与两侧计数：${error.message}",
        )
        // 条目级隔离：计数不符条目在 ZIP 层跳过保其余窗口
        val good = "z good" to viewStream(NodeFixture("android.view.View", "NO_ID", 0, 0, 10, 10))
        val windows = DeviceWindowViewsDecoder.decodeZip(
            DeviceWindowViewsFixtures.windowViewsZip("a bad" to stream, good),
        )
        assertEquals(listOf("z"), windows.map { it.windowHash }, "计数不符条目隔离跳过，其余窗口存活")
    }

    @Test
    fun `non numeric child suffix fails entry`() {
        val stream = DeviceWindowViewsFixtures.corruptChildStream(
            childKeys = listOf("meta:__child__x"),
            childCount = 1,
            children = listOf(NodeFixture("com.demo.ChildA", "NO_ID", 0, 0, 10, 10)),
        )
        assertFailsWith<DecoderFormatException> {
            DeviceWindowViewsDecoder.decodeEntry("h win", stream)
        }
    }

    @Test
    fun `child count absent falls back to received children`() {
        // childCount 缺席面（对照表缺项 → intOf 归 null）：实收子数兜底，不失败
        val enc = StreamEncoder()
        enc.intProperty("window:left", 0)
        enc.intProperty("window:top", 0)
        enc.beginObject("android.widget.FrameLayout", "h")
        enc.childProperty("meta:__child__0", NodeFixture("com.demo.ChildA", "NO_ID", 0, 0, 10, 10))
        enc.endObject()
        enc.endStream()
        val root = DeviceWindowViewsDecoder.decodeEntry("h win", enc.toByteArray()).root
        assertEquals(1, root.childCount, "childCount 缺席按实收子数兜底")
        assertEquals("com.demo.ChildA", root.children.single().className)
    }
}
