// M5-T10 测试：CLI 配置注入契约（承接旧 CommonOptionsBridgeConfigTest → CliConfigOverrideTest，见 behavior-contract §3）。
// 🔴 断言三件事：
//   (1) CLI **不再逐字段映射**——旧 CommonOptions.kt 的 30+ 业务字段旗标一律不被识别（parse 抛 unknown option）；
//   (2) 配置全部来自单一 JSON 文档 + `--set <点路径>=<值>` 覆写（走 BridgeFields.set lambda，不写反射）；
//   (3) `--set` 未知路径给明确错误而非静默忽略；`--config` 指向不存在文件 ⇒ ConfigNotFound。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.parse
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.config.BridgeJson
import com.jiaoay.lark.bridge.config.ConfigPaths
import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("cli-cfg-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

/** 捕获 [BridgeConfigCommand.resolveConfig] 结果的探针命令（对齐旧 ProbeCommand 手法）。 */
private class ProbeCommand(
    out: PrintStream,
    exit: (Int) -> Unit,
) : BridgeConfigCommand("probe", out, exit) {
    var captured: ConfigResolution? = null
        private set

    override fun run() {
        captured = resolveConfig()
    }
}

private class Recorder {
    val out = ByteArrayOutputStream()
    val exits = mutableListOf<Int>()
    val printStream: PrintStream get() = PrintStream(out, true)
    fun exit(code: Int) {
        exits.add(code)
    }
}

class CliConfigOverrideTest {

    /** 在临时数据根上跑一次 probe，返回 resolveConfig 结果。 */
    private fun resolve(root: Path, vararg sets: String): ConfigResolution {
        val rec = Recorder()
        val cmd = ProbeCommand(rec.printStream, rec::exit)
        cmd.parse((listOf("--data-dir", root.toString()) + sets).toList())
        return cmd.captured ?: error("probe 未捕获配置")
    }

    // ------------------------------------------------------------------ (2) --set 覆写生效

    @Test
    fun `--set 标量字符串覆写生效（connection-appId）`() {
        val config = (resolve(tempRoot("scalar"), "--set", "connection.appId=cli_scalar_app") as ConfigResolution.Ok).config
        assertEquals("cli_scalar_app", config.connection.appId)
    }

    @Test
    fun `--set 布尔覆写生效（connection-autoStart）`() {
        val config = (resolve(tempRoot("bool"), "--set", "connection.autoStart=true") as ConfigResolution.Ok).config
        assertTrue(config.connection.autoStart, "autoStart 应被覆写为 true")
    }

    @Test
    fun `--set 枚举-Choice 覆写生效（agent-groupMode）`() {
        val config = (resolve(tempRoot("enum"), "--set", "agent.groupMode=all") as ConfigResolution.Ok).config
        assertEquals("all", config.agent.groupMode)
    }

    @Test
    fun `--set 嵌套 Long 覆写生效（agent-confirm-timeoutSeconds）`() {
        val config =
            (resolve(tempRoot("nested"), "--set", "agent.confirm.timeoutSeconds=42") as ConfigResolution.Ok).config
        assertEquals(42L, config.agent.confirm.timeoutSeconds)
    }

    @Test
    fun `--set 数组覆写生效（access-trustedUsers 逗号分隔）`() {
        val config =
            (resolve(tempRoot("list"), "--set", "access.trustedUsers=ou_a,ou_b,ou_c") as ConfigResolution.Ok).config
        assertEquals(listOf("ou_a", "ou_b", "ou_c"), config.access.trustedUsers)
    }

    @Test
    fun `--set 可重复且多字段叠加生效`() {
        val config = (
            resolve(
                tempRoot("multi"),
                "--set", "connection.appId=cli_multi",
                "--set", "connection.autoStart=true",
                "--set", "runtime.maxConcurrentAgents=7",
            ) as ConfigResolution.Ok
            ).config
        assertEquals("cli_multi", config.connection.appId)
        assertTrue(config.connection.autoStart)
        assertEquals(7, config.runtime.maxConcurrentAgents)
    }

    // ------------------------------------------------------------------ (3) 错误处理

    @Test
    fun `--set 未知路径 ⇒ 明确错误 Err-ConfigInvalid（不静默忽略）`() {
        val result = resolve(tempRoot("unknown"), "--set", "bogus.not.a.field=1")
        assertTrue(result is ConfigResolution.Err, "未知路径必须报错，实际=$result")
        assertEquals(BridgeErrorCode.ConfigInvalid, (result as ConfigResolution.Err).code)
        assertTrue(result.message.contains("未知的配置路径"), "错误文案应指明未知路径，实际=${result.message}")
    }

    @Test
    fun `--set 缺等号 ⇒ 明确错误 Err`() {
        val result = resolve(tempRoot("noeq"), "--set", "connection.appId")
        assertTrue(result is ConfigResolution.Err, "缺 = 必须报错，实际=$result")
    }

    @Test
    fun `--set 类型不匹配 ⇒ 明确错误 Err（布尔字段传非布尔）`() {
        val result = resolve(tempRoot("badtype"), "--set", "connection.autoStart=notabool")
        assertTrue(result is ConfigResolution.Err, "类型不匹配必须报错，实际=$result")
    }

    @Test
    fun `--config 指向不存在文件 ⇒ Err-ConfigNotFound`() {
        val rec = Recorder()
        val cmd = ProbeCommand(rec.printStream, rec::exit)
        cmd.parse(listOf("--config", "/no/such/bridge-config-${System.nanoTime()}.json"))
        val result = cmd.captured
        assertTrue(result is ConfigResolution.Err, "显式 --config 缺失必须报错，实际=$result")
        assertEquals(BridgeErrorCode.ConfigNotFound, (result as ConfigResolution.Err).code)
    }

    @Test
    fun `隐式默认路径缺失 ⇒ 优雅回退内置默认配置（不报错）`() {
        val result = resolve(tempRoot("fallback"))
        assertTrue(result is ConfigResolution.Ok, "首次运行无文档应回退默认，实际=$result")
        assertTrue((result as ConfigResolution.Ok).fromDisk.not(), "回退默认时 fromDisk 应为 false")
    }

    // ------------------------------------------------------------------ (1) 已删除 30 个业务字段旗标

    @Test
    fun `🔴 旧业务字段旗标一律不被识别（CLI 不再逐字段映射，配置全部来自文档）`() {
        // 抽样旧 CommonOptions.kt 的业务旗标：每个都应导致 parse 抛 unknown option。
        val removedFlags = listOf(
            "--app-id", "--app-secret", "--region", "--workspace-dir", "--agent-backend",
            "--agent-enable", "--access-mode", "--max-concurrent", "--task-timeout", "--transport",
            "--skill", "--audit-dir", "--sender-stats-dir", "--static-reply", "--agent-work-dir",
            "--agent-system-prompt", "--blocked-senders", "--rejection-reply", "--unhandled-reply",
            "--workspace-whitelist", "--token-dir", "--event-key", "--lark-cli-path",
        )
        for (flag in removedFlags) {
            val rec = Recorder()
            val cmd = ProbeCommand(rec.printStream, rec::exit)
            // probe 基类不含这些旗标（--workspace-whitelist 仅在 permission-server 局部存在，见 PermissionServerCommand）。
            val failed = runCatching {
                cmd.parse(listOf("--data-dir", tempRoot("removed").toString(), flag, "x"))
            }.isFailure
            assertTrue(failed, "已删除的业务旗标 $flag 不应被基类识别")
        }
    }

    @Test
    fun `🔴 运行期开关与统一选项被识别（--dry-run --verbose --once --config --data-dir --set）`() {
        val root = tempRoot("kept")
        val rec = Recorder()
        val cmd = ProbeCommand(rec.printStream, rec::exit)
        cmd.parse(
            listOf(
                "--data-dir", root.toString(),
                "--dry-run", "--verbose", "--once",
                "--set", "connection.appId=cli_kept",
            ),
        )
        val result = cmd.captured
        assertTrue(result is ConfigResolution.Ok, "统一选项应被识别，实际=$result")
        assertEquals("cli_kept", (result as ConfigResolution.Ok).config.connection.appId)
    }

    // ---------------------------------------------------- (4) 🔴 --set ephemeral：任何情况都不写 bridge-config.json

    @Test
    fun `🔴 --set ephemeral：fresh 数据根跑 serve --dry-run --once --set 后 bridge-config-json 仍不存在（绝不落盘）`() {
        val root = tempRoot("ephemeral-absent")
        val rec = Recorder()
        // 经真实 serve 子命令（--dry-run 用 SyntheticGateway 离线，--once 启动后即优雅停止），跑完整条 applyConfig(persist=false)+stop⑧ 路径。
        ServeCommand(rec.printStream, rec::exit, gatewayFactoryOverride = { SyntheticGateway() }).parse(
            listOf(
                "--data-dir", root.toString(),
                "--dry-run", "--once",
                "--set", "connection.appId=cli_eph",
                "--set", "connection.appSecret=s",
            ),
        )
        assertEquals(listOf(0), rec.exits, "serve --once 正常停止 ⇒ exit(0)，实际=${rec.exits}")
        val configFile = ConfigPaths(root).configFile.toFile()
        assertTrue(
            !configFile.exists(),
            "🔴 --set 是 ephemeral：跑完带 --set 的命令后**不得创建** bridge-config.json，实际存在=${configFile.absolutePath}",
        )
    }

    @Test
    fun `🔴 --set ephemeral：已有 bridge-config-json 跑 serve --dry-run --once --set 后磁盘字节逐字未变（不篡改持久文档）`() {
        val root = tempRoot("ephemeral-unchanged")
        // 预置一份持久配置文档（appId=cli_orig），记录其字节。
        val original = DefaultBridgeConfig.default()
            .copy(connection = ConnectionConfig(appId = "cli_orig", appSecret = "orig_secret"))
        val configFile = ConfigPaths(root).configFile.toFile()
        configFile.parentFile.mkdirs()
        configFile.writeText(BridgeJson.encodeToString(BridgeConfig.serializer(), original), Charsets.UTF_8)
        val before = configFile.readBytes()

        val rec = Recorder()
        ServeCommand(rec.printStream, rec::exit, gatewayFactoryOverride = { SyntheticGateway() }).parse(
            listOf(
                "--data-dir", root.toString(),
                "--dry-run", "--once",
                "--set", "connection.appId=cli_changed",
                "--set", "connection.appSecret=s",
            ),
        )
        assertEquals(listOf(0), rec.exits, "serve --once 正常停止 ⇒ exit(0)，实际=${rec.exits}")
        val after = configFile.readBytes()
        assertTrue(
            before.contentEquals(after),
            "🔴 --set 覆写只在本进程内存生效：磁盘上的 bridge-config.json 必须**逐字未变**，before=${before.size}B after=${after.size}B",
        )
        assertTrue(
            configFile.readText(Charsets.UTF_8).contains("cli_orig"),
            "持久文档应仍含原始 appId=cli_orig（未被 --set 污染），实际=${configFile.readText(Charsets.UTF_8)}",
        )
    }
}
