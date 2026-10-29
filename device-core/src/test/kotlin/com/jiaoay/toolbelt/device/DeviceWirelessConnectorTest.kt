package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 转无线连接器守护（fake runner + runTest 虚拟时钟，零真子进程）：
 * - 编排纪律：先探后切（探测 → tcpip → settle → connect），命令全文逐字节断言
 *  （payload 单引号包裹 + AdbCommandRewriter 路径/serial 注入，connect 恒不注入 serial）；
 * - 失败语义：探测零命中/命令失败 → 引导手填兜底回执（不盲连、USB 未动）；tcpip 失败
 *  → 中止（零 connect spawn）；connect 失败 → 人话回执；busy 期连点防抖 + finally 复位；
 * - 目标解析：选定优先/单设备回退/空串号守门/无目标零 spawn 人话拒绝；
 * - 解析矩阵：ip route（default src 优先/无 default 兜底/无 src）与 ip addr
 *  （lo 排除/secondary 跳过/wlan 优先/多 IP/无全局 IPv4）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceWirelessConnectorTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope

    /** 设备三流 fake：直接驱动三个 StateFlow（免 DeviceMonitor 轮询链） */
    private class FakeDevices : DeviceStateSource {
        override val deviceState = MutableStateFlow<DeviceState>(DeviceState.None)
        override val detectError = MutableStateFlow<String?>(null)
        override val selectedSerial = MutableStateFlow<String?>(null)
    }

    /** runner fake：记录命令全文（通道纪律断言锚），按内容路由回执（suspend——编排链单步门控用） */
    private class FakeGateway(private val respond: suspend (String) -> ShellResult) : AdbProcessRunner {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return Result.success(respond(command))
        }
    }

    private val devices = FakeDevices()

    /** 设备有线形态（无 default 路由、eth0 主+副双 IP、行尾带空白——真机实采形态） */
    private val wiredRoute = listOf(
        "172.20.1.0/24 dev eth0 proto kernel scope link src 172.20.1.45 ",
        "172.168.1.0/24 dev eth1 proto kernel scope link src 172.168.1.2 ",
    ).joinToString("\n")

    /** 手机无线形态（default 路由带 src） */
    private val defaultRoute = listOf(
        "default via 192.168.1.1 dev wlan0 src 192.168.1.100 ",
        "192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.100 ",
    ).joinToString("\n")

    /** 设备 ip addr 形态（lo/dummy/eth0 主+副/eth1——真机实采形态） */
    private val wiredAddr = listOf(
        "1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000",
        "    inet 127.0.0.1/8 scope host lo",
        "2: dummy0: <BROADCAST,NOARP,UP,LOWER_UP> mtu 1500 qdisc noqueue state UP group default qlen 1000",
        "    inet6 fe80::ec8a:19ff:fe95:4420/64 scope link",
        "3: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default qlen 1000",
        "    inet 172.20.1.45/24 brd 172.20.1.255 scope global eth0",
        "    inet 172.20.1.3/24 brd 172.20.1.255 scope global secondary eth0",
        "4: eth1: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default qlen 1000",
        "    inet 172.168.1.2/24 brd 172.168.1.255 scope global eth1",
    ).joinToString("\n")

    /** 仅回环/链路本地（无 scope global IPv4） */
    private val loOnlyAddr = listOf(
        "1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000",
        "    inet 127.0.0.1/8 scope host lo",
        "2: dummy0: <BROADCAST,NOARP,UP,LOWER_UP> mtu 1500 qdisc noqueue state UP group default qlen 1000",
        "    inet6 fe80::ec8a:19ff:fe95:4420/64 scope link",
    ).joinToString("\n")

    private fun okGateway(
        route: ShellResult = ShellResult(0, wiredRoute, ""),
        addr: ShellResult = ShellResult(0, wiredAddr, ""),
        tcpip: ShellResult = ShellResult(0, "restarting in TCP mode port: 5555", ""),
        connect: ShellResult = ShellResult(0, "connected to 172.20.1.45:5555", ""),
    ): FakeGateway = FakeGateway { command ->
        when {
            command.contains("ip route") -> route
            command.contains("ip addr") -> addr
            command.contains("tcpip") -> tcpip
            command.contains("connect ") -> connect
            else -> ShellResult(0, "", "")
        }
    }

    private fun connector(
        runner: AdbProcessRunner = okGateway(),
        endpointReader: () -> AdbEndpoint = { AdbEndpoint("") },
    ): DeviceWirelessConnector = DeviceWirelessConnector(
        scope = scope,
        devices = devices,
        runner = runner,
        endpointReader = endpointReader,
    )

    /** 推进虚拟时钟过 settle 延迟（tcpip 与 connect 之间的固定等待），使 connect 落地 */
    private fun TestScope.settleAndRun() {
        advanceTimeBy(DeviceWirelessConnector.CONNECT_SETTLE_DELAY_MS + 1)
        runCurrent()
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    // ── 编排纪律：先探后切 + 命令逐字节断言 ─────────────────────

    @Test
    fun `full chain probes route first then tcpip then connect with byte-exact commands`() =
        runTest(dispatcher.scheduler) {
            devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
            val runner = okGateway()
            val connector = connector(runner)
            connector.startWirelessConnect()
            runCurrent()

            // 先探后切：探测完成、tcpip 已发，settle 前零 connect
            assertEquals(
                listOf("adb -s S1 shell 'ip route'", "adb -s S1 tcpip 5555"),
                runner.commands,
                "探测（payload 单引号包裹 + -s 注入）须先于 tcpip，settle 前零 connect",
            )
            assertTrue(connector.state.value.connecting, "编排在途 busy 态")

            settleAndRun()
            assertEquals(
                listOf("adb -s S1 shell 'ip route'", "adb -s S1 tcpip 5555", "adb connect 172.20.1.45:5555"),
                runner.commands,
                "connect 命令须为 adb connect <探测IP>:5555（主机侧命令，恒不注入 -s）",
            )
            val receipt = connector.state.value.lastReceipt!!
            assertTrue(receipt.success, "全链成功须成功回执")
            assertEquals("172.20.1.45:5555", receipt.newSerial, "回执须含无线新串号")
            assertTrue(receipt.message.contains("已切无线"), "成功回执须含人话去向（实际：${receipt.message}）")
            assertFalse(connector.state.value.connecting, "编排完成 busy 复位")
            assertNull(connector.state.value.rejectError, "执行路径不产拒绝回执")
            assertEquals(1L, connector.state.value.eventSeq, "每事件恰 +1（toast 去重键）")
        }

    @Test
    fun `addr fallback and custom adb path rewrite every command`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        // route 形态无 src（default 无 src 且无其他带 src 行）→ addr 兜底；wlan 优先于接口序更早的 eth
        val addrWithWlan = listOf(
            "3: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP",
            "    inet 172.20.1.45/24 brd 172.20.1.255 scope global eth0",
            "9: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc noqueue state UP",
            "    inet 192.168.43.10/24 brd 192.168.43.255 scope global wlan0",
        ).joinToString("\n")
        val runner = okGateway(
            route = ShellResult(0, "default via 10.0.0.1 dev eth0", ""),
            addr = ShellResult(0, addrWithWlan, ""),
            connect = ShellResult(0, "connected to 192.168.43.10:5555", ""),
        )
        val connector = connector(runner, endpointReader = { AdbEndpoint("/opt/my adb") })
        connector.startWirelessConnect()
        runCurrent()
        settleAndRun()

        assertEquals(
            listOf(
                "'/opt/my adb' -s S1 shell 'ip route'",
                "'/opt/my adb' -s S1 shell 'ip addr'",
                "'/opt/my adb' -s S1 tcpip 5555",
                "'/opt/my adb' connect 192.168.43.10:5555",
            ),
            runner.commands,
            "route 零命中须落 addr 兜底；自定义 adb 路径（含空白）按 Unix 单引号包裹替换",
        )
        val receipt = connector.state.value.lastReceipt!!
        assertTrue(receipt.success)
        assertEquals("192.168.43.10:5555", receipt.newSerial, "connect 须用 addr 兜底解析出的 wlan IP")
    }

    // ── 失败语义：每步显式人话回执 ───────────────────────────────

    @Test
    fun `tcpip failure aborts before any connect spawn`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val runner = okGateway(tcpip = ShellResult(1, "", "error: closed"))
        val connector = connector(runner)
        connector.startWirelessConnect()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(
            listOf("adb -s S1 shell 'ip route'", "adb -s S1 tcpip 5555"),
            runner.commands,
            "tcpip 失败即中止——推进任意时长后仍零 connect spawn",
        )
        val receipt = connector.state.value.lastReceipt!!
        assertFalse(receipt.success)
        assertTrue(
            receipt.message.contains("tcpip") && receipt.message.contains("已中止") &&
                receipt.message.contains("退出码 1") && receipt.message.contains("closed"),
            "失败回执须含步骤定位 + 退出码 + 错误输出人话（实际：${receipt.message}）",
        )
        assertNull(receipt.newSerial)
        assertFalse(connector.state.value.connecting, "失败路径 busy 也须复位")
    }

    @Test
    fun `probe zero hit lands manual fallback guidance without tcpip spawn`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val runner = okGateway(
            route = ShellResult(0, "default via 10.0.0.1 dev eth0", ""),
            addr = ShellResult(0, loOnlyAddr, ""),
        )
        val connector = connector(runner)
        connector.startWirelessConnect()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(
            listOf("adb -s S1 shell 'ip route'", "adb -s S1 shell 'ip addr'"),
            runner.commands,
            "双形态探测逐级兜底，零命中后零 tcpip/connect spawn",
        )
        val receipt = connector.state.value.lastReceipt!!
        assertFalse(receipt.success)
        assertTrue(
            receipt.message.contains("IP 探测失败") &&
                receipt.message.contains("连接无线设备") && receipt.message.contains("手动"),
            "零命中须引导既有「连接无线设备」手动命令面兜底（实际：${receipt.message}）",
        )
        assertTrue(receipt.message.contains("USB 通道未动"), "探测失败回执须明示 USB 完好（先探后切的安全收益）")
    }

    @Test
    fun `probe command failure surfaces exit code in guidance receipt`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val runner = okGateway(
            route = ShellResult(1, "", "error: device offline"),
            addr = ShellResult(1, "", "error: device offline"),
        )
        val connector = connector(runner)
        connector.startWirelessConnect()
        runCurrent()

        val receipt = connector.state.value.lastReceipt!!
        assertFalse(receipt.success)
        assertTrue(
            receipt.message.contains("ip addr 退出码 1"),
            "探测命令也失败时回执优先报命令失败根因（实际：${receipt.message}）",
        )
    }

    @Test
    fun `connect failure lands human receipt`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        // adb 旧形态：connect 失败仍回 0 退出码、错误信息在 stdout——仅靠退出码会误报成功
        val runner = okGateway(connect = ShellResult(0, "cannot connect to 172.20.1.45:5555: No route to host (113)", ""))
        val connector = connector(runner)
        connector.startWirelessConnect()
        runCurrent()
        settleAndRun()

        val receipt = connector.state.value.lastReceipt!!
        assertFalse(receipt.success, "退出码 0 但无 connected to 须判失败")
        assertTrue(
            receipt.message.contains("connect") && receipt.message.contains("No route to host"),
            "connect 失败回执须含输出人话（实际：${receipt.message}）",
        )
        assertNull(receipt.newSerial)
    }

    @Test
    fun `connect runner process failure lands human receipt`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val inner = okGateway()
        val boom = AdbProcessRunner { command ->
            if (command.contains("connect ")) Result.failure(IllegalStateException("adb not found"))
            else inner.execute(command)
        }
        val connector = connector(boom)
        connector.startWirelessConnect()
        runCurrent()
        settleAndRun()

        val receipt = connector.state.value.lastReceipt!!
        assertFalse(receipt.success)
        assertTrue(
            receipt.message.contains("adb not found"),
            "connect 进程真失败须落人话回执（实际：${receipt.message}）",
        )
    }

    // ── 目标解析与零 spawn 拒绝 ──────────────────────────────────

    @Test
    fun `unresolvable target rejects without spawn`() = runTest(dispatcher.scheduler) {
        // 形态一：无设备
        devices.deviceState.value = DeviceState.None
        val g1 = okGateway()
        val c1 = connector(g1)
        c1.startWirelessConnect()
        runCurrent()
        assertTrue(g1.commands.isEmpty(), "无设备不得 spawn")
        assertTrue(
            c1.state.value.rejectError?.contains("无可用目标设备") == true,
            "无设备拒绝须人话回执（实际：${c1.state.value.rejectError}）",
        )

        // 形态二：多设备未选定
        devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        devices.selectedSerial.value = null
        val g2 = okGateway()
        val c2 = connector(g2)
        c2.startWirelessConnect()
        runCurrent()
        assertTrue(g2.commands.isEmpty(), "多设备未选定不得 spawn")
        assertTrue(
            c2.state.value.rejectError?.contains("无可用目标设备") == true,
            "拒绝须人话回执（实际：${c2.state.value.rejectError}）",
        )
        assertNull(c2.state.value.lastReceipt, "拒绝路径不产回执")
        assertEquals(1L, c2.state.value.eventSeq)

        // 形态三：检测失败（设备态未知）
        devices.detectError.value = "设备检测失败：adb 路径无效"
        val g3 = okGateway()
        val c3 = connector(g3)
        c3.startWirelessConnect()
        runCurrent()
        assertTrue(g3.commands.isEmpty(), "检测失败不得 spawn")
        devices.detectError.value = null

        // 形态四：选定残留但设备已消失
        devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("S2", "M2")))
        devices.selectedSerial.value = "S1"
        val g4 = okGateway()
        connector(g4).startWirelessConnect()
        runCurrent()
        assertTrue(g4.commands.isEmpty(), "选定失效（设备已消失）不得 spawn，防对空串号注入 -s")
    }

    @Test
    fun `blank serial resolves to no target without spawn`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single(" ", "M")
        val runner = okGateway()
        val connector = connector(runner)
        connector.startWirelessConnect()
        runCurrent()

        assertTrue(runner.commands.isEmpty(), "空串号须按无目标处理（注入缝预置守门）")
        assertTrue(
            connector.state.value.rejectError?.contains("无可用目标设备") == true,
            "空串号拒绝须人话回执（实际：${connector.state.value.rejectError}）",
        )
    }

    @Test
    fun `multi device selection targets the selected serial`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        devices.selectedSerial.value = "S2"
        val runner = okGateway()
        connector(runner).startWirelessConnect()
        runCurrent()

        assertEquals(
            "adb -s S2 shell 'ip route'",
            runner.commands.first(),
            "多设备态须对选定串号注入 -s（选定优先）",
        )
    }

    // ── busy 防抖与复位 ─────────────────────────────────────────

    @Test
    fun `busy period drops new starts and resets in finally`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val gate = CompletableDeferred<Unit>()
        var gatedOnce = false
        val gated = FakeGateway { command ->
            if (!gatedOnce) {
                gatedOnce = true
                gate.await()
            }
            when {
                command.contains("ip route") -> ShellResult(0, wiredRoute, "")
                command.contains("ip addr") -> ShellResult(0, wiredAddr, "")
                command.contains("tcpip") -> ShellResult(0, "restarting in TCP mode port: 5555", "")
                command.contains("connect ") -> ShellResult(0, "connected to 172.20.1.45:5555", "")
                else -> ShellResult(0, "", "")
            }
        }
        val connector = connector(gated)
        connector.startWirelessConnect()
        runCurrent()
        assertTrue(connector.state.value.connecting, "编排在途 busy 态")

        // busy 期连点：直接忽略（不排队、零追加）
        connector.startWirelessConnect()
        runCurrent()

        gate.complete(Unit)
        runCurrent()
        settleAndRun()

        assertEquals(
            listOf("adb -s S1 shell 'ip route'", "adb -s S1 tcpip 5555", "adb connect 172.20.1.45:5555"),
            gated.commands,
            "busy 期动作被丢弃，整链只跑一次（无重复 spawn）",
        )
        assertFalse(connector.state.value.connecting, "gate 放行后 busy 在 finally 复位")
    }

    // ── 解析矩阵（纯函数） ──────────────────────────────────────

    @Test
    fun `parseRouteSrc prefers default route src and falls back to any src line`() {
        assertEquals(
            "192.168.1.100",
            DeviceWirelessConnector.parseRouteSrc(defaultRoute),
            "default 行 src 优先",
        )
        assertEquals(
            "172.20.1.45",
            DeviceWirelessConnector.parseRouteSrc(wiredRoute),
            "无 default 时按行序取首个带 src 的路由行",
        )
        assertEquals(
            "10.0.0.5",
            DeviceWirelessConnector.parseRouteSrc(
                listOf(
                    "default via 10.0.0.1 dev eth0",
                    "10.0.0.0/24 dev eth0 proto kernel scope link src 10.0.0.5",
                ).joinToString("\n"),
            ),
            "default 无 src 时兜底其余路由行",
        )
        assertNull(DeviceWirelessConnector.parseRouteSrc("default via 10.0.0.1 dev eth0"), "无任何 src → null")
        assertNull(DeviceWirelessConnector.parseRouteSrc(""), "空输出 → null")
    }

    @Test
    fun `parseAddrIpv4 skips lo and secondary and prefers wlan with multi-IP tolerance`() {
        assertEquals(
            "172.20.1.45",
            DeviceWirelessConnector.parseAddrIpv4(wiredAddr),
            "设备有线形态：eth0 主地址命中（secondary 副地址跳过、lo/dummy 排除）",
        )
        assertEquals(
            "192.168.43.10",
            DeviceWirelessConnector.parseAddrIpv4(
                listOf(
                    "3: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> state UP",
                    "    inet 172.20.1.45/24 brd 172.20.1.255 scope global eth0",
                    "9: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> state UP",
                    "    inet 192.168.43.10/24 brd 192.168.43.255 scope global wlan0",
                ).joinToString("\n"),
            ),
            "wlan 接口优先于接口序更早的 eth",
        )
        assertNull(DeviceWirelessConnector.parseAddrIpv4(loOnlyAddr), "无 scope global IPv4 → null")
        assertNull(
            DeviceWirelessConnector.parseAddrIpv4(
                "    inet 172.20.1.3/24 brd 172.20.1.255 scope global secondary eth0",
            ),
            "仅 secondary 全局地址 → null（副地址不作为连接目标）",
        )
    }

    @Test
    fun `settle delay and port are single-source constants`() {
        assertEquals(5555, DeviceWirelessConnector.WIRELESS_ADB_PORT, "tcpip 与 connect 共用同端口（单一真源）")
        assertEquals(500L, DeviceWirelessConnector.CONNECT_SETTLE_DELAY_MS, "settle 档变更须连同真机端到端验收一起评审")
    }
}
