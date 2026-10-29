// CLI 五子命令的统一选项基类（clikt option delegate 必须挂在 CliktCommand 上，故用基类）。
// 本基类**只保留运行期开关**（--dry-run/--verbose/--once）
//    + 统一的 --config <path> + --data-dir <path> + --set <点路径>=<值>；配置全部来自单一 JSON 文档，
//    绝不复活任何业务字段旗标（--app-id/--workspace-dir/--agent-backend 等一律不许有）。
// 可测性 seam：out（PrintStream）与 exit（(Int)->Unit）由构造注入，测试注入 recorder 断言输出与退出码，绝不真退出 JVM。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.config.ConfigLoadResult
import com.jiaoay.lark.bridge.config.ConfigPaths
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess

/** 配置解析结果（`--config`/`--data-dir`/`--set` 三者合成一份生效配置）。 */
internal sealed interface ConfigResolution {
    /**
     * @param config 生效配置（文档 + `--set` 覆写）
     * @param source 配置来源描述（供 check/日志打印）
     * @param fromDisk 是否来自磁盘文档（false ⇒ 文件不存在，回退 [DefaultBridgeConfig]）
     */
    data class Ok(val config: BridgeConfig, val source: String, val fromDisk: Boolean) : ConfigResolution

    data class Err(val code: BridgeErrorCode, val message: String) : ConfigResolution
}

/**
 * 五子命令共享的选项基类。
 *
 * @param out 输出流（默认 [System.out]；测试注入以捕获输出）。
 * @param exit 退出 seam（默认 [exitProcess]；测试注入 recorder，测试里绝不真退出 JVM）。
 */
abstract class BridgeConfigCommand(
    name: String,
    private val out: PrintStream = System.out,
    private val exit: (Int) -> Unit = { exitProcess(it) },
) : CliktCommand(name = name) {

    /** 配置文档路径（默认 `<data-dir>/bridge-config.json`）。 */
    protected val configPath: Path? by option(
        "--config",
        help = "配置文档路径（默认 <data-dir>/bridge-config.json）",
    ).path(mustExist = false, canBeDir = false)

    /** 数据根目录注入（默认 `<user.home>/.toolbelt/lark`）。 */
    protected val dataDir: Path? by option(
        "--data-dir",
        help = "数据根目录（默认 <user.home>/.toolbelt/lark）",
    ).path(mustExist = false, canBeDir = true)

    /** `--set <点路径>=<值>`（可重复），覆写文档字段（走 BridgeFields 的 set lambda，不写反射）。 */
    protected val overrides: List<String> by option(
        "--set",
        help = "覆写配置字段 <点路径>=<值>（可重复；点路径见 check 全字段表）。ephemeral：永不落盘，只影响本次进程内存（不写 bridge-config.json）",
    ).multiple()

    /** 干跑：用 SyntheticGateway，不连飞书。 */
    protected val dryRun: Boolean by option(
        "--dry-run",
        help = "干跑：用 SyntheticGateway 不连飞书（离线演练/自检）",
    ).flag(default = false)

    /** 输出调试日志（装配 consoleLog）。 */
    protected val verbose: Boolean by option(
        "--verbose",
        help = "输出调试级日志到控制台",
    ).flag(default = false)

    /** 启动后立即优雅停止并退出（自检用；使 serve 可离线验证且不阻塞）。 */
    protected val once: Boolean by option(
        "--once",
        help = "启动成功后立即优雅停止并退出（自检用）",
    ).flag(default = false)

    /** 输出一行（走注入的 [out]，不直接 println，便于测试捕获）。 */
    protected fun emit(line: String = "") {
        out.println(line)
    }

    /** 触发退出 seam（🔴 网关内部绝不 exitProcess，那是宿主的责任——宿主就是本 CLI，见 gateway-contract.md §16.6）。 */
    protected fun exit(code: Int) {
        exit.invoke(code)
    }

    /** 数据根：`--data-dir` 优先，否则缺省 `<user.home>/.toolbelt/lark`。 */
    protected fun resolveDataRoot(): Path = dataDir ?: defaultDataRoot()

    /**
     * 合成生效配置：
     * - 显式 `--config <path>`：文件缺失/损坏 ⇒ [ConfigResolution.Err]（用户显式指定却拿不到，必须报错）；
     * - 隐式默认路径：缺失 ⇒ 优雅回退 [DefaultBridgeConfig.default]（首次运行无文档是正常态）；损坏 ⇒ Err（保留现场）；
     * - `--set` 覆写：未知路径/类型不匹配 ⇒ Err（ConfigInvalid，明确错误不静默）。
     *
     * 🔴 **`--set` 是 ephemeral（本方法只合成内存配置，绝不落盘）**：`--set` 是「本次调用」的一次性覆写，
     * 用于离线演练/自检（如 `serve --dry-run --once --set connection.appId=…`）。若把它写回 `bridge-config.json`，
     * 就等于让一条命令行参数**静默篡改用户的持久配置文档**——下次不带 `--set` 启动会读到被污染的文档，属意外副作用。
     * 故本方法是**纯函数**（只读文档 + `applyOverrides` 内存覆写，不 save）；且下游 [com.jiaoay.lark.bridge.Bridge.applyConfig]
     * 由 CLI 一律以 `persist = false` 调用，stop() 第⑧步 `flushNow()` 因无 pending draft 天然 no-op ⇒
     * **任何情况下 CLI 都不写 bridge-config.json**。持久化是 App UI 编辑器（`applyConfig(persist = true)`）的专属职责。
     */
    internal fun resolveConfig(): ConfigResolution {
        val dataRoot = resolveDataRoot()
        val explicit = configPath
        val target = explicit ?: ConfigPaths(dataRoot).configFile
        val base: BridgeConfig
        val fromDisk: Boolean
        val source: String
        when (val loaded = readConfigFile(target)) {
            is ConfigLoadResult.Loaded -> {
                base = loaded.config
                fromDisk = true
                source = target.toString()
            }
            is ConfigLoadResult.Failed -> {
                if (explicit != null || loaded.code != BridgeErrorCode.ConfigNotFound) {
                    // 显式指定却失败，或隐式但文件损坏 ⇒ 报错（损坏文件不覆写、保留现场）。
                    return ConfigResolution.Err(loaded.code, loaded.message)
                }
                // 隐式默认路径缺失 ⇒ 优雅回退默认配置（首次运行）。
                base = DefaultBridgeConfig.default()
                fromDisk = false
                source = "${target}（不存在，使用内置默认配置）"
            }
        }
        return when (val overridden = applyOverrides(base, overrides)) {
            is OverrideResult.Ok -> ConfigResolution.Ok(overridden.config, source, fromDisk)
            is OverrideResult.Err -> ConfigResolution.Err(BridgeErrorCode.ConfigInvalid, overridden.message)
        }
    }
}
