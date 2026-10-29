package com.jiaoay.toolbelt.device

// OPEN(真机门禁): 真机 dumpsys package 单包记录格式跨 ROM 校准（本层 fixture
// 按实测基线实现：2 缩进 Package [名] 记录头 + 4 缩进 key=value 平面字段 + 6 缩进
// 权限行）——其他 ROM 格式不符显式失败不静默，格式样本扩充须带真机实测记录。

/**
 * 设备应用域纯解析层（字符串 → 模型，零副作用零 IO）：命令面语义 = adb shell
 * 程序语义，命令构造与执行调度在 [DevicePackageBrowser]。本层只吃已定形态。
 *
 * 失败语义：对不认识的输出抛 [IllegalArgumentException]（message 面向用户呈现），
 * 禁静默回空——包列表行形态单一（同一 pm 命令产出同构行），与 ls 多形态行防御
 * 跳过不同，**任何**不可识别行都显式失败（pm list 输出无「合法异形行」）。adb
 * shell 走 pty 时行尾可能带 `\r`，所有入口统一剥除。
 *
 * 格式基线（真机实测）：`pm list packages --show-versioncode -U` 一行一包
 * `package:<名> versionCode:<N> uid:<N>` 三段定序；`dumpsys package <pkg>` 的
 * Packages 记录 = 2 缩进 `Package [<名>] (<hash>):` 头 + 4 缩进 `key=value` 平面
 * 字段 + 子段头（4 缩进 `名称:` 形态）下 6 缩进子行（`requested permissions:`
 * 子行即声明权限清单）。
 */
internal object DevicePackageParsers {

    /**
     * 包列表行完整形态：三段定序 `package:<名> versionCode:<N> uid:<N>`（包名
     * 不含空白；versionCode/uid 为纯数字）。段序是真机实测形态，容错面 = 整行
     * 匹配单形态——段序互换/段缺席都是不可识别行，显式失败不猜段位。
     */
    private val packageLine = Regex("""^package:(\S+) versionCode:(\d+) uid:(\d+)$""")

    /** Packages 记录头：`  Package [<名>] (<hash>):`（hash 为 16 进制串） */
    private val packageHeader = Regex("""^ {2}Package \[([^\]]+)\] \([0-9a-f]+\):$""")

    /** pm path 行完整形态：`package:<设备侧路径>`（路径不含空白；多 split 一行一路径） */
    private val apkPathLine = Regex("""^package:(\S+)$""")

    private const val REQUESTED_PERMISSIONS_HEADER = "requested permissions:"

    /** 版本名未设置的设备侧字面量（manifest versionName 缺席的合法形态，映射为 null 不编默认值） */
    private const val NULL_LITERAL = "null"

    /**
     * 解析 `pm list packages --show-versioncode -U` 输出为包名/版本码/uid 三元组
     * 列表（系统标记由调用方集合差写入，本函数不区分 -s 旗标——同一命令形态
     * 两种列举共用本解析）。空输出 = 空列表（合法读数）；任何不可识别非空行
     * 显式失败，message 带原行样例。
     */
    internal fun parsePackageList(output: String): List<Triple<String, Long, Long>> {
        val lines = output.replace("\r", "").lines()
        val packages = mutableListOf<Triple<String, Long, Long>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val match = packageLine.matchEntire(line)
                ?: throw IllegalArgumentException("包列表行格式不符：${line.take(80)}")
            packages += Triple(
                match.groupValues[1],
                match.groupValues[2].toLong(),
                match.groupValues[3].toLong(),
            )
        }
        return packages
    }

    /**
     * 解析 `pm path <pkg>` 输出为设备侧 APK 路径列表（多 split 一行一 `package:` 前缀
     * 行，按行收集保持输出序）。空输出 = 空列表（「包未安装或无 APK」的显式失败
     * 判定在 [DeviceApkExporter.listApkPaths]——空集不冒充合法读数）；任何不可识别
     * 非空行显式失败带原行样例。pty `\r` 统一剥除。
     */
    internal fun parseApkPaths(output: String): List<String> {
        val lines = output.replace("\r", "").lines()
        val paths = mutableListOf<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val match = apkPathLine.matchEntire(line)
                ?: throw IllegalArgumentException("APK 路径行格式不符：${line.take(80)}")
            paths += match.groupValues[1]
        }
        return paths
    }

    /**
     * 解析 `dumpsys package <pkg>` 输出中的 Packages 记录（全量 JVM 解析，零
     * grep 子进程——单包输出约 100KB，外部 grep 截断读会产 Broken pipe 噪音）。
     *
     * 防御与失败语义：
     * - 记录头 `[expectedPackage]` 与请求包名不符（ROM 打印了别的记录）显式失败；
     * - 无 `Package [` 记录头（包未安装——dumpsys 对缺席包仍退出 0 只打印
     *   Queries/Dexopt 等无关段）显式失败，不误当「详情为空」；
     * - 记录体 = 记录头之后首个 4 缩进层内的行；遇缩进 < 4 的非空行（记录外
     *   顶层段如 `Shared users:`）即记录结束；
     * - 必备字段（versionCode/uid/codePath/firstInstallTime/lastUpdateTime）缺席
     *   显式失败并列缺席字段名（跨 ROM 格式不符须一眼定位缺哪段）；
     * - [versionName] 为可空字段：`versionName=null` 字面量 → null。
     */
    internal fun parsePackageDetails(output: String, expectedPackage: String): DevicePackageDetails {
        val lines = output.replace("\r", "").lines()

        val headerIndex = lines.indexOfFirst { packageHeader.matches(it) }
        if (headerIndex < 0) {
            throw IllegalArgumentException(
                "dumpsys 输出未找到包记录（$expectedPackage 未安装或格式不符），无 Package [记录头] 行",
            )
        }
        val headerName = packageHeader.matchEntire(lines[headerIndex])!!.groupValues[1]
        if (headerName != expectedPackage) {
            throw IllegalArgumentException(
                "dumpsys 包记录名不符（请求 $expectedPackage，记录 $headerName）",
            )
        }

        var versionCode: Long? = null
        var versionName: String? = null
        var uid: Long? = null
        var codePath: String? = null
        var firstInstallTime: String? = null
        var lastUpdateTime: String? = null
        var requestedPermissionsCount = 0

        var inRequestedPermissions = false
        for (index in headerIndex + 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (indent < 4) break // 记录外顶层段（如 Shared users:）——记录结束
            val body = line.trim()
            if (indent == 4) {
                inRequestedPermissions = body == REQUESTED_PERMISSIONS_HEADER
                if (inRequestedPermissions) continue
                val eq = body.indexOf('=')
                if (eq <= 0) continue // 非 key=value 形态的记录体行（无关字段）跳过
                val key = body.substring(0, eq)
                val value = body.substring(eq + 1).trim()
                when (key) {
                    "versionCode" -> versionCode = value.split(' ').first().toLongOrNull()
                    "versionName" -> versionName = if (value == NULL_LITERAL) null else value
                    "userId" -> uid = value.toLongOrNull()
                    "codePath" -> codePath = value
                    "firstInstallTime" -> firstInstallTime = value
                    "lastUpdateTime" -> lastUpdateTime = value
                }
            } else if (inRequestedPermissions) {
                requestedPermissionsCount++
            }
        }

        val missing = buildList {
            if (versionCode == null) add("versionCode")
            if (uid == null) add("userId")
            if (codePath == null) add("codePath")
            if (firstInstallTime == null) add("firstInstallTime")
            if (lastUpdateTime == null) add("lastUpdateTime")
        }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("dumpsys 包记录格式不符，缺席字段：${missing.joinToString("、")}")
        }
        return DevicePackageDetails(
            packageName = expectedPackage,
            versionCode = versionCode!!,
            versionName = versionName,
            uid = uid!!,
            codePath = codePath!!,
            firstInstallTime = firstInstallTime!!,
            lastUpdateTime = lastUpdateTime!!,
            requestedPermissionsCount = requestedPermissionsCount,
        )
    }
}
