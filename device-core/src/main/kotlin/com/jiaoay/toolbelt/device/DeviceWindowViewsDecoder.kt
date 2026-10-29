package com.jiaoay.toolbelt.device

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * ViewHierarchyEncoder 流纯解码层（字节数组 → [DeviceWindowViewsEntry]，零副作用
 * 零 IO）：输入是**不可信的设备侧二进制**，语法契约按 AOSP ViewHierarchyEncoder
 * 逐条自足（真机多窗口全节点实测基线）：
 *
 * - 大端序（DataOutputStream）；属性项 = `'S'`(0x53) + uint16 属性 ID + 类型标签：
 *   `'I'` int32 / `'F'` float32 / `'Z'` bool(1 字节) / `'S'` short / `'R'` 字符串
 *   (uint16 长度 + UTF-8) / `'M'` 嵌套 map；`'B'`/`'J'`/`'D'` 标签上游有定义无
 *   写入方，解码容错一并支持；
 * - map = `'M'` 开头，属性项序列至 `'S' 0x0000` 收尾键；视图 = `'M'` map，子视图
 *   内联在 `meta:__child__N` 属性的值里（N = 子序真源，解码按下标显式排序——文档
 *   写出序不可裸信；`meta:__childCount__` 在场时与实收子数校验，不符 = 显式损坏）；
 * - 条目流 = 窗口偏移散键（window:left/top，map 外）+ 根视图 map + 流末
 *   propertyIndex 对照表 map（属性 ID → 属性名；解码期建双向索引，查询 O(1)）——
 *   散键名依赖对照表回填。
 *
 * 失败语义（结构损坏按条目隔离，不击穿整包）：
 * - 未知类型标签 / 流截断（越界读）/ 非法 map 标签 → 该条目解码失败
 *   （[DecoderFormatException]，ZIP 层跳过该条目保其余窗口）；
 * - 未知属性 ID（对照表缺项）→ 容错不炸：属性名以 `#<id>` 形式入属性面；未知
 *   属性名（ROM 增量）原样保留不丢弃（属性表 144 项为实测基线，ROM 增减均容忍）；
 * - ZIP 无条目或全部条目解码失败 → 调用方按空列表显式失败，不静默回空。
 */
internal object DeviceWindowViewsDecoder {

    /** 结构损坏异常（条目级失败边界；message 面向排障呈现） */
    internal class DecoderFormatException(message: String) : Exception(message)

    private const val TAG_MAP = 'M'
    private const val TAG_PROPERTY = 'S'
    private const val END_MAP_KEY = 0

    private const val KEY_NAME = "meta:__name__"
    private const val KEY_HASH = "meta:__hash__"
    private const val KEY_CHILD_COUNT = "meta:__childCount__"
    private const val KEY_CHILD_PREFIX = "meta:__child__"
    private const val KEY_ID = "id"
    private const val KEY_WINDOW_LEFT = "window:left"
    private const val KEY_WINDOW_TOP = "window:top"

    private const val KEY_VISIBILITY = "misc:visibility"
    private const val KEY_CLICKABLE = "misc:clickable"
    private const val KEY_ENABLED = "misc:enabled"
    private const val KEY_SELECTED = "misc:selected"
    private const val KEY_ACTIVATED = "misc:activated"

    private const val KEY_LAYOUT_LEFT = "layout:left"
    private const val KEY_LAYOUT_TOP = "layout:top"
    private const val KEY_LAYOUT_RIGHT = "layout:right"
    private const val KEY_LAYOUT_BOTTOM = "layout:bottom"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"

    private const val KEY_PADDING_LEFT = "padding:left"
    private const val KEY_PADDING_TOP = "padding:top"
    private const val KEY_PADDING_RIGHT = "padding:right"
    private const val KEY_PADDING_BOTTOM = "padding:bottom"

    private const val UNKNOWN_NAME_PREFIX = "#"

    /** 属性值（类型化载荷）：标量存显示串，嵌套 map 以子属性面递归承载 */
    private sealed interface PropValue {
        data class Scalar(val display: String) : PropValue
        data class Map_(val props: LinkedHashMap<Int, PropValue>) : PropValue
    }

    /**
     * 属性名索引（对照表双射）：id→名正查 + 名→id 反查。反查表解码期一次建表
     * （名重复时首 ID 生效），全程 O(1) 查询——属性表百级键 × 千节点 × 每节点十余次
     * well-known 查询，线性反查是解码性能的主要无谓开销。
     */
    private class NameIndex(
        val nameById: Map<Int, String>,
        val idByName: Map<String, Int>,
    )

    /** 单层 map 的属性面（key = 属性 ID，文档序；名解析统一经对照表） */
    private class MapProps(val props: LinkedHashMap<Int, PropValue>) {
        fun scalarId(index: NameIndex, name: String): PropValue.Scalar? =
            props[index.idByName[name] ?: return null] as? PropValue.Scalar

        /**
         * 子视图项：`meta:__child__N` 后缀数字 = 子序真源（文档写出序不可裸信——ROM
         * 变体乱序写出时文档序会错位层级树），按下标升序返回；下标不可解析 = 显式
         * 结构损坏（与官方 getChildIndex 的 toInt 语义对齐，不静默跳过）。
         */
        fun childEntries(index: NameIndex): List<Pair<Int, MapProps>> =
            props.entries
                .mapNotNull { (id, value) ->
                    val name = index.nameById[id] ?: return@mapNotNull null
                    if (!name.startsWith(KEY_CHILD_PREFIX)) return@mapNotNull null
                    val childIndex = name.removePrefix(KEY_CHILD_PREFIX).toIntOrNull()
                        ?: throw DecoderFormatException("流结构损坏：子视图键下标不可解析（$name）")
                    val childProps = (value as? PropValue.Map_)?.props ?: return@mapNotNull null
                    childIndex to MapProps(childProps)
                }
                .sortedBy { it.first }
    }

    /** 条目流解码产物：窗口偏移散键 + 根视图属性面 + 属性名索引 */
    private class DecodedStream(
        val windowLeft: Int?,
        val windowTop: Int?,
        val rootProps: MapProps,
        val nameIndex: NameIndex,
    )

    /**
     * 解码 dvwv ZIP 全文为窗口条目列表（条目序保持 ZIP 写出序）：目录条目与解码
     * 失败条目跳过（不击穿其余窗口），全失败/无条目由调用方按空列表显式失败。
     */
    fun decodeZip(zipBytes: ByteArray): List<DeviceWindowViewsEntry> {
        val entries = mutableListOf<DeviceWindowViewsEntry>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val bytes = zip.readBytes()
                try {
                    entries += decodeEntry(entry.name, bytes)
                } catch (_: DecoderFormatException) {
                    // 条目级损坏隔离：跳过该窗口保其余条目（失败语义见类头）
                }
            }
        }
        return entries
    }

    /** 单条目解码（internal 供单测逐条目断言）：条目名 → 窗口 hash/标题/包名归属 */
    internal fun decodeEntry(entryName: String, bytes: ByteArray): DeviceWindowViewsEntry {
        val stream = decodeStream(bytes)
        val (hash, title) = parseEntryName(entryName)
        return DeviceWindowViewsEntry(
            windowHash = hash,
            title = title,
            packageName = packageNameOf(title),
            windowLeft = stream.windowLeft,
            windowTop = stream.windowTop,
            // 窗口偏移是流级散键（map 外），随流面下沉到全树节点（子视图同属一个窗口）
            root = buildNode(stream.rootProps, stream.nameIndex, stream.windowLeft, stream.windowTop),
        )
    }

    /** 条目名解析：首 token = 窗口 hash，其余 = 标题（hash 缺席容空，标题可含空格） */
    private fun parseEntryName(entryName: String): Pair<String, String> {
        val trimmed = entryName.trim()
        val space = trimmed.indexOf(' ')
        if (space <= 0) return "" to trimmed
        return trimmed.substring(0, space) to trimmed.substring(space + 1)
    }

    /** 组件名形态（`pkg/类名`）的标题 → 包名归属；系统窗标题返回 null（不猜） */
    private fun packageNameOf(title: String): String? {
        val slash = title.indexOf('/')
        if (slash <= 0) return null
        val pkg = title.substring(0, slash)
        return pkg.takeIf { it.isNotEmpty() }
    }

    /**
     * 条目流三段解码：map 前散键收集（window:left/top，名待对照表回填）→ 首个
     * map = 根视图 → 次 map = propertyIndex 对照表（名表就位即回填散键名）。
     * 对照表缺席（截断流）时散键名不可解析，窗口偏移置 null（容错契约见类头）。
     */
    private fun decodeStream(bytes: ByteArray): DecodedStream {
        val reader = ByteReader(bytes)
        val headProps = mutableListOf<Pair<Int, PropValue.Scalar>>()
        var rootProps: MapProps? = null
        var nameIndex: NameIndex? = null

        while (reader.hasRemaining()) {
            when (reader.readByte().toInt().toChar()) {
                TAG_PROPERTY -> {
                    val key = reader.readUnsignedShort()
                    if (key == END_MAP_KEY) continue // 属性 ID 从 1 起，map 外 0 键 = 坏流收尾键，跳过不消费值
                    val value = reader.readValue()
                    if (value is PropValue.Scalar) headProps += key to value
                }

                TAG_MAP -> {
                    val map = MapProps(reader.readMapBody())
                    when {
                        rootProps == null -> rootProps = map
                        nameIndex == null -> nameIndex = buildNameIndex(map)
                        else -> return finished(reader, headProps, rootProps, nameIndex)
                    }
                }

                else -> throw DecoderFormatException(
                    "流结构损坏：顶层非法标签（offset ${reader.position() - 1}）",
                )
            }
        }
        return finished(reader, headProps, rootProps, nameIndex)
    }

    /** 流收口：对照表回填散键名 + 根视图存在性校验（无根 = 显式损坏） */
    private fun finished(
        reader: ByteReader,
        headProps: List<Pair<Int, PropValue.Scalar>>,
        rootProps: MapProps?,
        nameIndex: NameIndex?,
    ): DecodedStream {
        val root = rootProps ?: throw DecoderFormatException("流结构损坏：无根视图 map（offset ${reader.position()}）")
        val index = nameIndex ?: NameIndex(emptyMap(), emptyMap())
        fun headScalar(name: String): PropValue.Scalar? =
            headProps.firstOrNull { it.first == index.idByName[name] }?.second
        return DecodedStream(
            windowLeft = headScalar(KEY_WINDOW_LEFT)?.display?.toIntOrNull(),
            windowTop = headScalar(KEY_WINDOW_TOP)?.display?.toIntOrNull(),
            rootProps = root,
            nameIndex = index,
        )
    }

    /**
     * 对照表 map → 属性名索引（双向）：`__name__="propertyIndex"` 之外的标量属性
     * 全收；反查表名重复时首 ID 生效（与旧线性反查 firstOrNull 语义逐位等价）。
     */
    private fun buildNameIndex(indexMap: MapProps): NameIndex {
        val nameById = mutableMapOf<Int, String>()
        indexMap.props.forEach { (id, value) ->
            if (id == END_MAP_KEY) return@forEach
            (value as? PropValue.Scalar)?.let { nameById[id] = it.display }
        }
        val idByName = HashMap<String, Int>()
        nameById.forEach { (id, name) -> idByName.putIfAbsent(name, id) }
        return NameIndex(nameById, idByName)
    }

    /**
     * 属性面 → [WindowViewNode]：子视图按 `meta:__child__N` 后缀下标显式排序
     * （文档写出序不可裸信——ROM 变体乱序写出会错位层级树），`meta:__childCount__`
     * 在场时与实收子数不符 = 显式结构损坏（计数校验防半损子面静默入树，与官方
     * Verify 语义对齐）；well-known 属性缺席按模型容错契约归一；
     * [WindowViewNode.properties] 剔除已建类型字段与内部 meta/child 面，其余标量
     * 全保留（详情卡「View 内部行」投影源）；'M' 嵌套 map 值收编进
     * [WindowViewNode.nestedProperties]（内层键经同一对照表解析为 String，组内再
     * 嵌套的 map 值兜底为显示串不丢弃）。窗口偏移由流面下传入参
     * （散键在 map 外，节点自身属性面查不到），子视图全树共享同一窗口偏移。
     *
     * 祖先偏移累加（[WindowViewNode.globalBounds] 合成的祖先面）：layout:left/top
     * 是父相对坐标（View.getLeft 语义），递归下传 accLeft/accTop = 根到父节点的
     * Σ layout:left/top——子视图入参 = 本节点 acc + 本节点 bounds.left/top；累加值
     * 只进 [WindowViewNode.ancestorAccLeft]/[ancestorAccTop] 合成面，不回写 bounds。
     */
    private fun buildNode(
        props: MapProps,
        nameIndex: NameIndex,
        windowLeft: Int?,
        windowTop: Int?,
        accLeft: Int = 0,
        accTop: Int = 0,
    ): WindowViewNode {
        fun scalar(name: String): PropValue.Scalar? = props.scalarId(nameIndex, name)
        fun intOf(name: String): Int? = scalar(name)?.display?.toIntOrNull()
        fun boolOf(name: String): Boolean? = scalar(name)?.display?.toBooleanStrictOrNull()

        val childEntries = props.childEntries(nameIndex)
        val declaredChildCount = intOf(KEY_CHILD_COUNT)
        if (declaredChildCount != null && declaredChildCount != childEntries.size) {
            throw DecoderFormatException(
                "流结构损坏：子视图计数不符（class=${scalar(KEY_NAME)?.display ?: "?"}，" +
                    "meta:__childCount__=$declaredChildCount，实收 ${childEntries.size}）",
            )
        }
        val bounds = DeviceLayoutBounds(
            left = intOf(KEY_LAYOUT_LEFT) ?: 0,
            top = intOf(KEY_LAYOUT_TOP) ?: 0,
            right = intOf(KEY_LAYOUT_RIGHT) ?: (intOf(KEY_WIDTH) ?: 0),
            bottom = intOf(KEY_LAYOUT_BOTTOM) ?: (intOf(KEY_HEIGHT) ?: 0),
        )
        // 子视图祖先面 = 本节点祖先面 + 本节点父相对 bounds 左上角（bounds 先算后递归）
        val children = childEntries.map {
            buildNode(it.second, nameIndex, windowLeft, windowTop, accLeft + bounds.left, accTop + bounds.top)
        }
        val padding = listOf(
            intOf(KEY_PADDING_LEFT),
            intOf(KEY_PADDING_TOP),
            intOf(KEY_PADDING_RIGHT),
            intOf(KEY_PADDING_BOTTOM),
        ).takeIf { it.all { v -> v != null } }
            ?.let { DeviceLayoutPadding(it[0]!!, it[1]!!, it[2]!!, it[3]!!) }

        val rendered = setOf(
            KEY_NAME, KEY_HASH, KEY_ID, KEY_CHILD_COUNT,
            KEY_WINDOW_LEFT, KEY_WINDOW_TOP,
            KEY_LAYOUT_LEFT, KEY_LAYOUT_TOP, KEY_LAYOUT_RIGHT, KEY_LAYOUT_BOTTOM,
            KEY_VISIBILITY, KEY_CLICKABLE, KEY_ENABLED, KEY_SELECTED, KEY_ACTIVATED,
            KEY_PADDING_LEFT, KEY_PADDING_TOP, KEY_PADDING_RIGHT, KEY_PADDING_BOTTOM,
        )
        val properties = LinkedHashMap<String, String>()
        val nestedProperties = LinkedHashMap<String, Map<String, String>>()
        props.props.forEach { (id, value) ->
            val name = nameIndex.nameById[id] ?: "$UNKNOWN_NAME_PREFIX$id"
            if (name.startsWith(KEY_CHILD_PREFIX)) return@forEach
            when (value) {
                is PropValue.Scalar -> properties[name] = value.display

                // 内层键是属性 ID：必须经同一对照表解析为 String（对照表全流共享），
                // Int 键 toString 是错误修法（详情卡会显示数字键）
                is PropValue.Map_ -> nestedProperties[name] =
                    value.props.entries.associate { (innerId, inner) ->
                        val innerName = nameIndex.nameById[innerId] ?: "$UNKNOWN_NAME_PREFIX$innerId"
                        innerName to when (inner) {
                            is PropValue.Scalar -> inner.display

                            // 深嵌套兜底显示串：不猜不丢弃（ROM 变体容忍，与未知标量
                            // `#<id>` 前缀兜底同纪律）
                            is PropValue.Map_ -> inner.props.fallbackDisplay(nameIndex)
                        }
                    }
            }
        }

        return WindowViewNode(
            className = scalar(KEY_NAME)?.display ?: "",
            viewHash = scalar(KEY_HASH)?.display ?: "",
            viewId = scalar(KEY_ID)?.display ?: "",
            bounds = bounds,
            windowLeft = windowLeft,
            windowTop = windowTop,
            ancestorAccLeft = accLeft,
            ancestorAccTop = accTop,
            visibility = intOf(KEY_VISIBILITY) ?: 0,
            clickable = boolOf(KEY_CLICKABLE) ?: false,
            enabled = boolOf(KEY_ENABLED) ?: true,
            selected = boolOf(KEY_SELECTED) ?: false,
            activated = boolOf(KEY_ACTIVATED) ?: false,
            padding = padding,
            childCount = declaredChildCount ?: childEntries.size,
            children = children,
            properties = properties.filterKeys { it !in rendered },
            nestedProperties = nestedProperties.filterKeys { it !in rendered },
        )
    }

    /**
     * 深嵌套 map 的兜底显示串（`名=值` 逗号串）：组内标量直收、更深嵌套递归展开——
     * 值 + 单位 + 语义自足（深嵌套真机形态未实测，按显示串收不丢弃）。
     */
    private fun LinkedHashMap<Int, PropValue>.fallbackDisplay(nameIndex: NameIndex): String =
        entries.joinToString(",") { (id, value) ->
            val name = nameIndex.nameById[id] ?: "$UNKNOWN_NAME_PREFIX$id"
            val display = when (value) {
                is PropValue.Scalar -> value.display
                is PropValue.Map_ -> value.props.fallbackDisplay(nameIndex)
            }
            "$name=$display"
        }

    /** 字节游标（越界读 = 流截断，显式损坏异常不静默回 0） */
    private class ByteReader(private val bytes: ByteArray) {
        private var pos = 0

        fun hasRemaining(): Boolean = pos < bytes.size

        fun position(): Int = pos

        fun readByte(): Byte {
            if (pos >= bytes.size) throw DecoderFormatException("流截断：offset $pos 处缺 1 字节")
            return bytes[pos++]
        }

        fun readUnsignedShort(): Int {
            val hi = readByte().toInt() and 0xFF
            val lo = readByte().toInt() and 0xFF
            return (hi shl 8) or lo
        }

        fun readValue(): PropValue = when (readByte().toInt().toChar()) {
            'Z' -> PropValue.Scalar((readByte().toInt() != 0).toString())
            'S' -> {
                // short 值 = 2 字节（与属性键同为 uint16 线形态，符号按 short 语义还原）
                val raw = readUnsignedShort()
                PropValue.Scalar(raw.toShort().toInt().toString())
            }
            'I' -> PropValue.Scalar(readInt().toString())
            'J' -> PropValue.Scalar(readLong().toString())
            'F' -> PropValue.Scalar(Float.fromBits(readInt()).toString())
            'D' -> PropValue.Scalar(Double.fromBits(readLong()).toString())
            'B' -> PropValue.Scalar((readByte().toInt() and 0xFF).toString())
            'R' -> {
                val len = readUnsignedShort()
                if (pos + len > bytes.size) throw DecoderFormatException("流截断：字符串声明 $len 字节超出流尾（offset $pos）")
                PropValue.Scalar(String(bytes, pos, len, Charsets.UTF_8).also { pos += len })
            }
            'M' -> PropValue.Map_(readMapBody())
            else -> throw DecoderFormatException("未知类型标签（offset ${pos - 1}）")
        }

        /** map 体消费（'M' 已读）：属性序列至 'S'+0 收尾键，非法标签显式损坏 */
        fun readMapBody(): LinkedHashMap<Int, PropValue> {
            val props = LinkedHashMap<Int, PropValue>()
            while (true) {
                if (readByte().toInt().toChar() != TAG_PROPERTY) {
                    throw DecoderFormatException("流结构损坏：map 内非法标签（offset ${pos - 1}）")
                }
                val key = readUnsignedShort()
                if (key == END_MAP_KEY) return props
                props[key] = readValue()
            }
        }

        private fun readInt(): Int {
            var v = 0
            repeat(4) { v = (v shl 8) or (readByte().toInt() and 0xFF) }
            return v
        }

        private fun readLong(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or (readByte().toLong() and 0xFF) }
            return v
        }
    }
}
