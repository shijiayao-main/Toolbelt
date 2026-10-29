package com.jiaoay.toolbelt.device

/**
 * 设备连接了但不可用的条目（未授权 / offline / 无 USB 权限等）。
 *
 * [state] 保留 adb 报出的**完整状态描述**而不是首个词——`no permissions (user in
 * plugdev group; are your udev rules wrong?); see [...]` 这类多词状态被截成 `no`
 * 之后就再也匹配不上任何引导分支，用户只会看到「没有设备」。
 */
data class DeviceIssue(
    val serial: String,
    /** adb 报出的状态原文（多词保留，去掉末尾的 `usb:` 一类属性段） */
    val state: String,
    val model: String = "",
) {
    /** 展示标识：型号优先，缺失回退串号 */
    val displayLabel: String get() = model.ifEmpty { serial }
}

/** `adb devices -l` 的解析结果：可用设备 + 不可用设备。 */
data class AdbDevicesParseResult(
    val devices: List<DeviceInfo>,
    val issues: List<DeviceIssue>,
)

/**
 * `adb devices -l` 输出解析。
 *
 * 输出形如（每行一个设备）：
 * ```
 * List of devices attached
 * ABC123                 device usb:1-1 product:x model:Phone device:y
 * DEF456                 unauthorized usb:1-2
 * GHI789                 no permissions (user in plugdev group; are your udev rules wrong?); see [http://...]
 * ```
 *
 * 行结构 = `串号` + 空白 + `状态（可多词）` + 可选的 `键:值` 属性段。状态与属性段的
 * 分界是首个形如 `key:` 的 token（`usb:` / `product:` / `model:` / `device:` /
 * `transport_id:` 都是这个形状），而状态本身可能含括号与分号，不能按空白切词取首项。
 */
object DeviceMonitorParser {

    /** 属性段 token：`usb:` 一类的小写键加冒号；状态词里不会出现这个形状 */
    private val PROPERTY_TOKEN = Regex("""^[a-z_]+:""")

    /** 属性 token 判定：只要求**前缀**匹配（`Regex.matches` 是全串匹配，用不了） */
    private fun isPropertyToken(token: String): Boolean = PROPERTY_TOKEN.containsMatchIn(token)

    fun parse(output: String): AdbDevicesParseResult {
        val devices = mutableListOf<DeviceInfo>()
        val issues = mutableListOf<DeviceIssue>()
        for (raw in output.lines().drop(1)) { // 跳过 "List of devices attached"
            val line = raw.trim()
            // 空行、守护进程重启提示（`* daemon not running; starting now at tcp:5037`）跳过
            if (line.isEmpty() || line.startsWith("*")) continue
            // 串号与状态之间是 TAB 不是空格（`adb devices` 原样输出）：只按空格切会把
            // 串号连带状态一起吞掉，任何设备都解析不出来
            val split = line.indexOfFirst { it.isWhitespace() }
            if (split <= 0) continue
            val serial = line.substring(0, split).trim()
            val remainder = line.substring(split).trim()
            if (serial.isEmpty() || remainder.isEmpty()) continue

            val (state, properties) = splitStateAndProperties(remainder)
            if (state == "device") {
                devices += DeviceInfo(serial = serial, model = parseModel(properties))
            } else if (state.isNotEmpty()) {
                issues += DeviceIssue(serial = serial, state = state, model = parseModel(properties))
            }
        }
        return AdbDevicesParseResult(devices = devices, issues = issues)
    }

    /**
     * 把「状态 + 属性段」拆开：属性段从首个 `key:` 形状的 token 开始。
     *
     * 只看 token 形状而非固定词表——adb 新增属性键时不需要改这里。
     */
    internal fun splitStateAndProperties(remainder: String): Pair<String, List<String>> {
        val tokens = remainder.split("\\s+".toRegex())
        val boundary = tokens.indexOfFirst { isPropertyToken(it) }
        return if (boundary < 0) {
            remainder to emptyList()
        } else {
            tokens.subList(0, boundary).joinToString(" ") to tokens.subList(boundary, tokens.size)
        }
    }

    /** 从属性段提取 `model:xxx`，缺失返回空串 */
    private fun parseModel(properties: List<String>): String =
        properties.firstOrNull { it.startsWith("model:") }?.removePrefix("model:").orEmpty()
}
