// CLI 纯函数助手（无 clikt 依赖，便于单测直接调用）。
// 🔴 纪律：零 com.jiaoay.easy_dev_toolbox（连注释）、零 ProcessBuilder、零 @Volatile var、零 object/companion 内可变状态——
//    违反即出错：ProcessBuilder 走 shell 语义、Windows ANSI charset 会破坏 UTF-8 流解析；@Volatile/可变静态在并发路径产生撕裂读；
//    不吞 CancellationException；lib 侧 jvmTarget 11，数据根由宿主注入（不引用 :core FileUtils）。
// 🔴 --set 覆写一律走 BridgeFields.ALL 的 field.set lambda（不写反射）；secret 掩码前 4 后 4（附录 C / R17）。
package com.jiaoay.lark.bridge.cli

import com.jiaoay.lark.bridge.PrintStreamBridgeLog
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.BridgeFields
import com.jiaoay.lark.bridge.config.BridgeJson
import com.jiaoay.lark.bridge.config.ConfigLoadResult
import com.jiaoay.lark.bridge.config.ConfigMigrations
import com.jiaoay.lark.bridge.config.Editor
import java.io.File
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject

/** CLI 缺省数据根：`<user.home>/.toolbelt/lark`（[ConfigPaths] KDoc 的 CLI 侧注入约定；与 App 侧 FileUtils.getLarkDir() 同根——两处拼写的契约由宿主侧守护测试钉死）。 */
internal fun defaultDataRoot(): Path = File(System.getProperty("user.home"), ".toolbelt/lark").toPath()

/**
 * secret 掩码：前 4 后 4，中间 `*`（附录 C line 1033 / 1336、[BridgeField.secret] KDoc、R17）。
 * 长度 <= 8 时全掩码（避免短密钥被前后 4 位完全还原）。空串原样返回空。
 */
internal fun maskSecret(value: String): String {
    if (value.isEmpty()) return value
    if (value.length <= 8) return "*".repeat(value.length)
    return value.take(4) + "*".repeat(value.length - 8) + value.takeLast(4)
}

/**
 * 把 CLI 的 `--verbose` 意图翻译成**桥接侧 console 输出下限**。
 * `--verbose` ⇒ [PrintStreamBridgeLog.Level.DEBUG]；否则维持 [PrintStreamBridgeLog.Level.INFO]
 * （不加 `--verbose` 时行为逐字不变，控制台绝不被 DEBUG 刷屏）。
 *
 * ## 为什么需要它
 * 桥接侧 `BridgeLog`（[com.jiaoay.lark.bridge.observe.ObservingBridgeLog] → [PrintStreamBridgeLog] 委托）
 * 的下限是**自建的一套门控**（`PrintStreamBridgeLog.minLevel` 直接 `out.println`，不经 slf4j/logback），
 * 缺省 INFO ⇒ 拒绝路径既有的 `log.debug`（`BridgeAssembler` 的 `PolicyGate.onRejected`、`EventIntake` 的 sender 准入）
 * **一直被吞**。而 SDK 自己（oapi SDK 内部 logger）的 DEBUG 走的是**另一套**（slf4j → logback；fat-jar 无 logback.xml
 * ⇒ logback 缺省 root=DEBUG），所以真机上「SDK DEBUG 可见、桥接 DEBUG 不可见」是两套独立级别控制的结果。
 * 本函数让 `--verbose` 显式把桥接侧那套门控也降到 DEBUG，既有拒绝诊断即刻浮现（无需新增并行日志路径）。
 *
 * 纯函数（无副作用、无 IO），供 `serve` / `handle-event` 把它传进 `BridgeAssembler.assemble(consoleMinLevel=…)`；
 *    刻意**不改** [PrintStreamBridgeLog] 的缺省值（那会波及所有调用方，含 App 侧 `consoleLog=false`）。
 */
internal fun consoleLevelFor(verbose: Boolean): PrintStreamBridgeLog.Level =
    if (verbose) PrintStreamBridgeLog.Level.DEBUG else PrintStreamBridgeLog.Level.INFO

/**
 * 读取任意路径的配置文档（[ConfigRepository] 是 dataRoot 绑定的，不适配 `--config <任意路径>`）。
 * 复用 public 的 [BridgeJson] + [ConfigMigrations]：迁移链 + 未知顶层键折入 experimental，与仓库读取同形。
 * 文件缺失 ⇒ [ConfigLoadResult.Failed]（ConfigNotFound）；损坏 ⇒ Failed（ConfigParseFailed），🔴 不覆写、不抛。
 */
internal fun readConfigFile(file: Path): ConfigLoadResult {
    val f = file.toFile()
    if (!f.exists()) {
        return ConfigLoadResult.Failed(BridgeErrorCode.ConfigNotFound, "配置文件不存在：${f.path}")
    }
    return try {
        val raw = f.readText(Charsets.UTF_8)
        val doc = BridgeJson.parseToJsonElement(raw).jsonObject
        val migrated = ConfigMigrations.foldUnknownIntoExperimental(ConfigMigrations.migrate(doc))
        ConfigLoadResult.Loaded(BridgeJson.decodeFromJsonElement(BridgeConfig.serializer(), migrated))
    } catch (e: Throwable) {
        ConfigLoadResult.Failed(BridgeErrorCode.ConfigParseFailed, "配置解析失败：${e.message}")
    }
}

/** `--set` 覆写结果。 */
internal sealed interface OverrideResult {
    data class Ok(val config: BridgeConfig) : OverrideResult
    data class Err(val message: String) : OverrideResult
}

/**
 * 应用 `--set <点路径>=<值>` 覆写（可重复）。用 [BridgeFields.ALL] 的 `field.set` lambda 定位并写回，
 * **不写反射**；点路径可命中全部字段任意一个（含嵌套如 `agent.confirm.timeoutSeconds`；计数以
 * BridgeFieldsTest 为唯一真源——「56 个」曾漂移，本注释不再复述具体数字）。
 * 未知路径 / 缺 `=` / 类型不匹配 ⇒ [OverrideResult.Err]（明确错误，绝不静默忽略）。
 */
internal fun applyOverrides(config: BridgeConfig, sets: List<String>): OverrideResult {
    var current = config
    for (raw in sets) {
        val idx = raw.indexOf('=')
        if (idx <= 0) {
            return OverrideResult.Err("--set 格式应为 <点路径>=<值>，收到：\"$raw\"")
        }
        val path = raw.substring(0, idx).trim()
        val value = raw.substring(idx + 1)
        val field = BridgeFields.ALL.firstOrNull { it.id == path }
            ?: return OverrideResult.Err(
                "未知的配置路径：\"$path\"（可用路径见 check 子命令的全字段表；共 ${BridgeFields.ALL.size} 个字段）",
            )
        val coerced = coerceValue(field, current, value)
            ?: return OverrideResult.Err("字段 \"$path\" 的值 \"$value\" 与其类型不匹配")
        @Suppress("UNCHECKED_CAST")
        val f = field as BridgeField<Any?>
        current = f.set(current, coerced)
    }
    return OverrideResult.Ok(current)
}

/** 按字段当前值的运行时类型强制转换 raw 字符串（Boolean/Int/Long/List<String>逗号分隔/String）；不匹配返回 null。 */
private fun coerceValue(field: BridgeField<*>, config: BridgeConfig, raw: String): Any? {
    val current = field.get(config)
    return when (current) {
        is Boolean -> raw.trim().toBooleanStrictOrNull()
        is Int -> raw.trim().toIntOrNull()
        is Long -> raw.trim().toLongOrNull()
        is List<*> -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        is String -> raw
        null -> raw // 当前值为 null 的字段按字符串处理（全字段集以 BridgeFieldsTest 为真源）
        else -> null
    }
}

/**
 * 单字段的 check 输出值渲染：secret 掩码（前 4 后 4）；Number(displayScale>1) 附显示单位（config-domain-spec.md 附录 C / §16.2「字节原值 (MB)」）；
 * List 逗号连接；空白串渲染 `(空)`。
 */
internal fun formatFieldValue(field: BridgeField<*>, config: BridgeConfig): String {
    val value = field.get(config)
    if (field.secret) return maskSecret(value?.toString().orEmpty())
    val editor = field.editor
    if (editor is Editor.Number && editor.displayScale > 1 && value is Long) {
        return "$value (${value / editor.displayScale}${editor.unitSuffix})"
    }
    return when (value) {
        is List<*> -> if (value.isEmpty()) "(空)" else value.joinToString(", ")
        is String -> if (value.isBlank()) "(空)" else value
        null -> "(空)"
        else -> value.toString()
    }
}

/**
 * 错误码 → 进程退出码。
 * 配置域（NotFound/ParseFailed/Invalid/WriteFailed）⇒ 2；单实例锁被占 ⇒ 3；连接失败 ⇒ 4；其余/null ⇒ 1。
 */
internal fun exitCodeFor(code: BridgeErrorCode?): Int = when (code) {
    BridgeErrorCode.ConfigNotFound,
    BridgeErrorCode.ConfigParseFailed,
    BridgeErrorCode.ConfigInvalid,
    BridgeErrorCode.ConfigWriteFailed,
    -> EXIT_CONFIG
    BridgeErrorCode.BlockedByPeerInstance -> EXIT_BLOCKED
    BridgeErrorCode.ConnectFailed -> EXIT_CONNECT
    null -> EXIT_GENERIC
    else -> EXIT_GENERIC
}

internal const val EXIT_OK: Int = 0
internal const val EXIT_GENERIC: Int = 1
internal const val EXIT_CONFIG: Int = 2
internal const val EXIT_BLOCKED: Int = 3
internal const val EXIT_CONNECT: Int = 4
