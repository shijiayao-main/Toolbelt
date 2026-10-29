package com.jiaoay.easy_dev_toolbox.page.screencast

import java.net.ServerSocket
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * 隧道策略假网关单测：不真跑 adb，fake [ScrcpyAdbGateway] 记录命令序列并按脚本应答，
 * fake [ScrcpyTunnelOpener.LocalListenerFactory] 模拟端口占用——钉死 reverse 优先 /
 * bind 失败 remove 右移 / forward 逐试 / tcp:0 解析双形态 / remove 收尾契约。
 */
class ScrcpyTunnelTest {

    private val socketName = ScrcpyProtocol.deviceSocketName(0x1A2B3C4D)

    /** 录命令 + 按谓词表应答的 fake 网关 */
    private class FakeAdbGateway(
        private val responders: List<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>,
    ) : ScrcpyAdbGateway {
        val executed = mutableListOf<String>()

        override suspend fun execute(command: String): Result<ShellResult> {
            executed.add(command)
            val responder = responders.firstOrNull { it.first(command) }
                ?: return Result.success(ShellResult(exitCode = 0, stdout = "", stderr = ""))
            return responder.second(command)
        }
    }

    private fun okResult(stdout: String = ""): Result<ShellResult> = Result.success(ShellResult(0, stdout, ""))
    private fun failResult(stderr: String = "adb: failure"): Result<ShellResult> = Result.failure(IllegalStateException(stderr))

    private fun isReverse(port: Int): (String) -> Boolean = { it == ScrcpyServerDeployer.buildReverseCommand(port, socketName) }
    private fun isForward(port: Int): (String) -> Boolean = { it == ScrcpyServerDeployer.buildForwardCommand(port, socketName) }

    private fun fakeListenerFactory(occupied: Set<Int>): Pair<ScrcpyTunnelOpener.LocalListenerFactory, MutableList<ServerSocket>> {
        val created = mutableListOf<ServerSocket>()
        val factory = ScrcpyTunnelOpener.LocalListenerFactory { port ->
            if (port in occupied) {
                null
            } else {
                // 未绑定真实端口的空 socket（close 安全），只为持有实例验证收尾
                ServerSocket().also { created.add(it) }
            }
        }
        return factory to created
    }

    @Test
    fun `reverse succeeds on first port in range`() = runBlocking {
        val (factory, _) = fakeListenerFactory(occupied = emptySet())
        val adb = FakeAdbGateway(responders = emptyList())
        val opener = ScrcpyTunnelOpener(adb, socketName, listenerFactory = factory)
        val tunnel = opener.openReverse()
        assertTrue(tunnel is ScrcpyTunnel.Reverse)
        assertEquals(ScrcpyServerDeployer.PORT_RANGE_FIRST, tunnel.port)
        assertEquals(listOf(ScrcpyServerDeployer.buildReverseCommand(ScrcpyServerDeployer.PORT_RANGE_FIRST, socketName)), adb.executed)
    }

    @Test
    fun `reverse bind failure removes rule and moves to next port`() = runBlocking {
        val first = ScrcpyServerDeployer.PORT_RANGE_FIRST
        val (factory, _) = fakeListenerFactory(occupied = setOf(first))
        val adb = FakeAdbGateway(responders = emptyList())
        val opener = ScrcpyTunnelOpener(adb, socketName, listenerFactory = factory)
        val tunnel = opener.openReverse()
        assertTrue(tunnel is ScrcpyTunnel.Reverse)
        assertEquals(first + 1, tunnel.port)
        assertEquals(
            listOf(
                ScrcpyServerDeployer.buildReverseCommand(first, socketName),
                ScrcpyServerDeployer.buildRemoveReverseCommand(socketName),
                ScrcpyServerDeployer.buildReverseCommand(first + 1, socketName),
            ),
            adb.executed,
        )
    }

    @Test
    fun `reverse command failure aborts reverse path without per-port retry`() = runBlocking {
        val (factory, _) = fakeListenerFactory(occupied = emptySet())
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                { cmd: String -> cmd.startsWith("adb reverse ") } to { failResult() },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName, listenerFactory = factory)
        assertEquals(null, opener.openReverse())
        // 命令级失败只试一端口（无线 adb 场景整段不可用），且无 remove 尾随
        assertEquals(1, adb.executed.size)
    }

    @Test
    fun `forward scans ports until command succeeds`() = runBlocking {
        val first = ScrcpyServerDeployer.PORT_RANGE_FIRST
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                isForward(first) to { failResult("cannot rebind") },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName)
        val tunnel = opener.openForwardAnyPort()
        assertTrue(tunnel is ScrcpyTunnel.Forward)
        assertEquals(first + 1, tunnel.port)
        assertEquals(2, adb.executed.size)
    }

    @Test
    fun `open prefers reverse and falls back to forward`() = runBlocking {
        val (factory, _) = fakeListenerFactory(occupied = emptySet())
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                { cmd: String -> cmd.startsWith("adb reverse ") } to { failResult("no reverse over adb connect") },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName, listenerFactory = factory)
        val tunnel = opener.open()
        assertTrue(tunnel is ScrcpyTunnel.Forward)
        assertEquals(ScrcpyServerDeployer.PORT_RANGE_FIRST, tunnel.port)
        // 先 reverse（1 条命令）后 forward（1 条命令）
        assertTrue(adb.executed[0].startsWith("adb reverse "))
        assertTrue(adb.executed[1].startsWith("adb forward "))
    }

    @Test
    fun `forward only preference skips reverse entirely and opens forward`() = runBlocking {
        val adb = FakeAdbGateway(responders = emptyList())
        val opener = ScrcpyTunnelOpener(
            adb,
            socketName,
            tunnelPreference = TunnelPreference.ForwardOnly,
        )
        val tunnel = opener.open()
        assertTrue(tunnel is ScrcpyTunnel.Forward)
        assertEquals(ScrcpyServerDeployer.PORT_RANGE_FIRST, tunnel.port)
        // ForwardOnly 契约核心：零 reverse 尝试（命令序列里不得出现任何 adb reverse）
        assertTrue(
            adb.executed.none { it.startsWith("adb reverse ") },
            "ForwardOnly 必须零 reverse 尝试，实际命令序列: ${adb.executed}",
        )
        assertTrue(
            adb.executed.isNotEmpty() && adb.executed.all { it.startsWith("adb forward ") },
            "ForwardOnly 必须直走 forward，实际命令序列: ${adb.executed}",
        )
    }

    @Test
    fun `default preference keeps reverse first command sequence`() = runBlocking {
        // 缝参数默认值零漂移背书：不传 tunnelPreference 的 open() 命令序列与
        // 显式传 ReverseFirst 逐条一致（reverse 首命令在位），既有用例零回归的另一面
        val (factory, _) = fakeListenerFactory(occupied = emptySet())
        val adb = FakeAdbGateway(responders = emptyList())
        val opener = ScrcpyTunnelOpener(
            adb,
            socketName,
            listenerFactory = factory,
            tunnelPreference = TunnelPreference.ReverseFirst,
        )
        val tunnel = opener.open()
        assertTrue(tunnel is ScrcpyTunnel.Reverse)
        assertEquals(
            listOf(ScrcpyServerDeployer.buildReverseCommand(ScrcpyServerDeployer.PORT_RANGE_FIRST, socketName)),
            adb.executed,
        )
    }

    @Test
    fun `open exhausts range into IOException`() = runBlocking {
        val (factory, _) = fakeListenerFactory(occupied = emptySet())
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                { cmd: String -> cmd.startsWith("adb reverse ") } to { failResult() },
                { cmd: String -> cmd.startsWith("adb forward ") } to { failResult() },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName, listenerFactory = factory, portRange = 27183..27184)
        assertFailsWith<java.io.IOException> { opener.open() }
        Unit
    }

    @Test
    fun `forward tcp zero probe parses assigned port from forward list`() = runBlocking {
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                { cmd: String -> cmd == "adb forward --list" } to {
                    okResult("EMU1 tcp:27190 localabstract:$socketName\n")
                },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName)
        assertEquals(27190, opener.probeForwardAutoPort())
        // 探针自清理：解析成功同样 remove（探针不作为隧道持有）
        assertEquals(
            listOf(
                ScrcpyServerDeployer.buildForwardCommand(0, socketName),
                "adb forward --list",
                ScrcpyServerDeployer.buildRemoveForwardCommand(27190),
            ),
            adb.executed,
        )
    }

    @Test
    fun `forward tcp zero probe detects literal echo and removes rule`() = runBlocking {
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                { cmd: String -> cmd == "adb forward --list" } to {
                    okResult("EMU1 tcp:0 localabstract:$socketName\n")
                },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName)
        // --list 回显字面量 tcp:0 = 解析断裂（可行性报告待验项的负形态），不得产出端口
        assertEquals(null, opener.probeForwardAutoPort())
        assertTrue(ScrcpyServerDeployer.buildRemoveForwardCommand(0) in adb.executed)
    }

    @Test
    fun `forward tcp zero probe unparseable listing falls back to best-effort remove`() = runBlocking {
        // 行格式不可读（hostSpec 解析不出数值端口）：规则键不可知——按建规则时的 tcp:0
        // 尽力 remove（命中与否不保证，残留由 adb forward --list 核对暴露）
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                { cmd: String -> cmd == "adb forward --list" } to {
                    okResult("EMU1 tcp:??? localabstract:$socketName\n")
                },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName)
        assertEquals(null, opener.probeForwardAutoPort())
        assertTrue(
            ScrcpyServerDeployer.buildRemoveForwardCommand(0) in adb.executed,
            "解析不出端口时必须落尽力 remove（零 remove 即探针规则残留）",
        )
    }

    @Test
    fun `forward tcp zero probe fails when forward command rejected`() = runBlocking {
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                isForward(0) to { failResult() },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName)
        assertEquals(null, opener.probeForwardAutoPort())
        assertEquals(1, adb.executed.size)
    }

    @Test
    fun `close reverse closes listener and removes rule`() = runBlocking {
        val (factory, created) = fakeListenerFactory(occupied = emptySet())
        val adb = FakeAdbGateway(responders = emptyList())
        val opener = ScrcpyTunnelOpener(adb, socketName, listenerFactory = factory)
        val tunnel = opener.openReverse() as ScrcpyTunnel.Reverse
        val listener = tunnel.listener
        opener.close(tunnel)
        assertTrue(listener.isClosed)
        assertEquals(ScrcpyServerDeployer.buildRemoveReverseCommand(socketName), adb.executed.last())
    }

    @Test
    fun `close forward removes forward rule by port`() = runBlocking {
        val first = ScrcpyServerDeployer.PORT_RANGE_FIRST
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                isForward(first) to { failResult("cannot rebind") },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName)
        val tunnel = opener.openForwardAnyPort() as ScrcpyTunnel.Forward
        opener.close(tunnel)
        assertEquals(ScrcpyServerDeployer.buildRemoveForwardCommand(first + 1), adb.executed.last())
    }

    @Test
    fun `cancelled reverse create rethrows without forward fallback`() = runBlocking {
        // 建规则命令结果承载取消（结果未观测——adb 子进程可能已建规则）：不得误读为
        // 「reverse 不可用」而盲走 forward fallback，必须原样抛出交会话按取消收口
        val (factory, _) = fakeListenerFactory(occupied = emptySet())
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                { cmd: String -> cmd.startsWith("adb reverse ") } to {
                    Result.failure(CancellationException("stop during create"))
                },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName, listenerFactory = factory)
        assertFailsWith<CancellationException> { opener.open() }
        assertTrue(
            adb.executed.none { it.startsWith("adb forward ") },
            "取消后不得盲走 forward fallback，实际命令序列: ${adb.executed}",
        )
    }

    @Test
    fun `close without tunnel removes outstanding reverse rule after cancelled create`() = runBlocking {
        // 开链夭折（tunnel 未建立）的收口必达：建规则命令已发但结果被取消吞没——规则可能在档，
        // close(null) 按挂账面补发摘除
        val (factory, _) = fakeListenerFactory(occupied = emptySet())
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                { cmd: String -> cmd.startsWith("adb reverse ") } to {
                    Result.failure(CancellationException("stop during create"))
                },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName, listenerFactory = factory)
        assertFailsWith<CancellationException> { opener.open() }
        opener.close(null)
        assertTrue(
            ScrcpyServerDeployer.buildRemoveReverseCommand(socketName) in adb.executed,
            "取消吞没的建规则命令后，收口摘除仍必达，实际命令序列: ${adb.executed}",
        )
    }

    @Test
    fun `close without tunnel removes outstanding forward port and clears observed failure`() = runBlocking {
        // 挂账核销双面：回执失败的 reverse/forward 建规则命令 = 规则必不在档，close 零补发
        // （无 adb 报错噪音）；被取消吞没的 forward 建规则命令 = 结果未观测，按端口补发摘除
        val first = ScrcpyServerDeployer.PORT_RANGE_FIRST
        val adb = FakeAdbGateway(
            responders = listOf<Pair<(String) -> Boolean, (String) -> Result<ShellResult>>>(
                { cmd: String -> cmd.startsWith("adb reverse ") } to { failResult("no reverse over adb connect") },
                { cmd: String -> cmd.startsWith("adb forward ") } to {
                    Result.failure(CancellationException("stop during create"))
                },
            ),
        )
        val opener = ScrcpyTunnelOpener(adb, socketName)
        assertFailsWith<CancellationException> { opener.open() }
        opener.close(null)
        assertTrue(
            ScrcpyServerDeployer.buildRemoveForwardCommand(first) in adb.executed,
            "取消吞没的 forward 建规则命令后，按端口摘除仍必达，实际命令序列: ${adb.executed}",
        )
        assertTrue(
            adb.executed.none { it.startsWith("adb reverse --remove") },
            "回执失败的 reverse 建规则不得挂账补发（零噪音），实际命令序列: ${adb.executed}",
        )
    }
}
