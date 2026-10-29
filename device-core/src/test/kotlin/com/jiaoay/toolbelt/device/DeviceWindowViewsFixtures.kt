package com.jiaoay.toolbelt.device

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * dvwv 解码面测试夹具：测试侧流编码器**逐字节镜像** AOSP ViewHierarchyEncoder
 * 线格式（'M' map 开 / 'S'+uint16 键 + 类型标签值 / 'S' 0x0000 map 收尾 / 流末
 * propertyIndex 对照表 map，大端序）——解码器与编码器同契约双向钉死，线格式漂移
 * 由原始字节断言（非仅往返一致）兜底。树样例取自布局检查器真机调研
 * 调研报告的 真机 StatusBar 样本。
 */
internal object DeviceWindowViewsFixtures {

    /**
     * 测试侧流编码器（镜像 AOSP 线格式）：属性 ID 从 1 起按 intern 序分配；
     * [view] 写出序 = dumpEncoded 真实形态（window:left/top 散键 → 根视图 map →
     * 对照表收尾）。
     */
    internal class StreamEncoder {
        private val out = ByteArrayOutputStream()
        private val names = LinkedHashMap<String, Int>()
        private var nextId = 1

        private fun idOf(name: String): Int = names.getOrPut(name) { nextId++ }

        private fun propertyKey(name: String) {
            val id = idOf(name)
            out.write('S'.code)
            out.write((id ushr 8) and 0xFF)
            out.write(id and 0xFF)
        }

        private fun writeShort(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        private fun writeInt(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        private fun writeString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeShort(bytes.size)
            out.write(bytes)
        }

        fun intProperty(name: String, value: Int) {
            propertyKey(name)
            out.write('I'.code)
            writeInt(value)
        }

        fun boolProperty(name: String, value: Boolean) {
            propertyKey(name)
            out.write('Z'.code)
            out.write(if (value) 1 else 0)
        }

        fun floatProperty(name: String, value: Float) {
            propertyKey(name)
            out.write('F'.code)
            writeInt(value.toRawBits())
        }

        fun stringProperty(name: String, value: String) {
            propertyKey(name)
            out.write('R'.code)
            writeString(value)
        }

        fun shortProperty(name: String, value: Int) {
            propertyKey(name)
            out.write('S'.code)
            writeShort(value and 0xFFFF)
        }

        /** 未知类型标签面（容错断言用）：原始标签字节 + 原始载荷 */
        fun rawProperty(name: String, tag: Char, payload: ByteArray) {
            propertyKey(name)
            out.write(tag.code)
            out.write(payload)
        }

        /**
         * 嵌套 map 属性（'M' 标签值面）：body 内写入组内键值对后由本函数收尾——
         * 组内键经 propertyKey 同一 intern 序（对照表可解析）。
         */
        fun mapProperty(name: String, body: StreamEncoder.() -> Unit) {
            propertyKey(name)
            out.write('M'.code)
            body()
            endObject()
        }

        /**
         * 未登记属性 ID 的裸键写入（内层键 `#<id>` 兜底断言用）：键 id 不经 intern，
         * 对照表内无此条目。
         */
        fun unregisteredKeyProperty(id: Int, tag: Char, payload: ByteArray) {
            out.write('S'.code)
            writeShort(id)
            out.write(tag.code)
            out.write(payload)
        }

        fun beginObject(className: String, hash: String) {
            out.write('M'.code)
            stringProperty("meta:__name__", className)
            stringProperty("meta:__hash__", hash)
        }

        fun endObject() {
            out.write('S'.code)
            writeShort(0)
        }

        /** 完整视图 map（含递归子视图内联与 childCount 计数） */
        fun view(fixture: NodeFixture) {
            beginObject(fixture.className, "hash-${fixture.viewId.ifEmpty { "anon" }}")
            intProperty("misc:visibility", fixture.visibility)
            boolProperty("misc:clickable", fixture.clickable)
            boolProperty("misc:enabled", true)
            intProperty("layout:left", fixture.left)
            intProperty("layout:top", fixture.top)
            intProperty("layout:right", fixture.right)
            intProperty("layout:bottom", fixture.bottom)
            fixture.padding?.let { p ->
                intProperty("padding:left", p[0])
                intProperty("padding:top", p[1])
                intProperty("padding:right", p[2])
                intProperty("padding:bottom", p[3])
            }
            stringProperty("id", fixture.viewId)
            floatProperty("drawing:alpha", 1.0f)
            shortProperty("meta:__childCount__", fixture.children.size)
            fixture.children.forEachIndexed { index, child ->
                // 子视图 = 属性值内联 map（view() 的 beginObject 自写 'M'，键后直跟）
                propertyKey("meta:__child__$index")
                view(child)
            }
            endObject()
        }

        /** 自定义子键 + 子视图（子序/计数异常流断言用：键名由调用方直供） */
        fun childProperty(key: String, fixture: NodeFixture) {
            propertyKey(key)
            view(fixture)
        }

        fun endStream() {
            out.write('M'.code)
            stringProperty("__name__", "propertyIndex")
            names.forEach { (name, id) ->
                out.write('S'.code)
                writeShort(id)
                out.write('R'.code)
                writeString(name)
            }
            endObject()
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** DSL 节点（夹具侧树构造）：class 全名 + 资源 id + layout 四值 + 子节点 */
    internal class NodeFixture(
        val className: String,
        val viewId: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val children: List<NodeFixture> = emptyList(),
        val padding: IntArray? = null,
        val visibility: Int = 0,
        val clickable: Boolean = false,
    )

    /** 单条目流：window 偏移散键 + 根视图 map + 对照表收尾（dumpEncoded 形态） */
    internal fun viewStream(fixture: NodeFixture, windowLeft: Int = 0, windowTop: Int = 0): ByteArray {
        val enc = StreamEncoder()
        enc.intProperty("window:left", windowLeft)
        enc.intProperty("window:top", windowTop)
        enc.view(fixture)
        enc.endStream()
        return enc.toByteArray()
    }

    /**
     * 子序/计数异常流（解码校验断言用）：与 [viewStream] 同形态，但根视图的子键
     * 名与 childCount 由调用方直供（乱序下标 / 坏键 / 错计数）——children[i] 挂在
     * childKeys[i] 名下（文档写出序即入参序，与键内下标解耦）。
     */
    internal fun corruptChildStream(
        childKeys: List<String>,
        childCount: Int?,
        children: List<NodeFixture>,
    ): ByteArray {
        val enc = StreamEncoder()
        enc.intProperty("window:left", 0)
        enc.intProperty("window:top", 0)
        enc.beginObject("android.widget.FrameLayout", "hash-corrupt")
        enc.shortProperty("meta:__childCount__", childCount ?: children.size)
        children.forEachIndexed { index, child -> enc.childProperty(childKeys[index], child) }
        enc.endObject()
        enc.endStream()
        return enc.toByteArray()
    }

    /**
     * StatusBar 树样例流（窗口偏移入参供全局坐标断言）：
     * RelativeLayout → NestedScrollLinearLayout → (OemLinearLayout → (View, TextClock),
     * BaseView → ImageView)，附带 padding/int/float/bool/short 类型覆盖面。
     */
    internal fun statusBarStream(windowLeft: Int = 0, windowTop: Int = 0): ByteArray = viewStream(
        NodeFixture(
            className = "android.widget.RelativeLayout",
            viewId = "NO_ID",
            left = 0, top = 0, right = 2880, bottom = 100,
            padding = intArrayOf(16, 0, 16, 0),
            children = listOf(
                NodeFixture(
                    className = "com.demo.oem.widget.NestedScrollLinearLayout",
                    viewId = "id/ll_persist_group",
                    left = 2393, top = 0, right = 2880, bottom = 100,
                    children = listOf(
                        NodeFixture(
                            className = "com.demo.oem.widget.OemLinearLayout",
                            viewId = "NO_ID",
                            left = 360, top = 29, right = 487, bottom = 71,
                            children = listOf(
                                NodeFixture(
                                    className = "android.view.View",
                                    viewId = "id/view_divider",
                                    left = 103, top = 0, right = 127, bottom = 1,
                                ),
                                NodeFixture(
                                    className = "android.widget.TextClock",
                                    viewId = "id/clock",
                                    left = 0, top = 0, right = 103, bottom = 42,
                                    clickable = true,
                                ),
                            ),
                        ),
                        NodeFixture(
                            className = "com.demo.oem.widget.BaseView",
                            viewId = "NO_ID",
                            left = 270, top = 5, right = 360, bottom = 95,
                            children = listOf(
                                NodeFixture(
                                    className = "android.widget.ImageView",
                                    viewId = "id/iv_state_icon",
                                    left = 13, top = 13, right = 77, bottom = 77,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        windowLeft = windowLeft,
        windowTop = windowTop,
    )

    /** 打包 ZIP（条目序保持入参序） */
    internal fun windowViewsZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** 夹具树的期望渲染（class 简名 + id + bounds，两空格缩进；根附 vis 读数） */
    internal fun renderForAssert(node: WindowViewNode, depth: Int = 0, isRoot: Boolean = true): String {
        val line = buildString {
            repeat(depth) { append("  ") }
            append(node.className.substringAfterLast('.'))
            append(" id=").append(node.viewId.ifEmpty { "NO_ID" })
            append(" [${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]")
            if (isRoot) append(" vis=").append(node.visibility)
        }
        return (listOf(line) + node.children.map { renderForAssert(it, depth + 1, isRoot = false) })
            .joinToString("\n")
    }
}
