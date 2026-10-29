package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 投屏引擎单测（fake adb / fake runner / fake decoder + 真实本地 socket 承载 reverse
 * 隧道与握手段，见 ScreencastTestFakes）：状态机全迁移、世代号防复活、accept soTimeout
 * 超时显式失败、探活重试耗尽显式失败、重入拒绝、收尾命令核对。真机全链路验收走
 * ScrcpySpikeRunner（runbook 门控）。
 */
class ScreencastEngineTest {

    @Test
    fun `reverse session streams frames lands streaming status and cleans up on stop`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            val streaming = harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            streaming as ScreencastStatus.Streaming
            assertEquals("TestDevice", streaming.deviceName)
            assertEquals("TEST-SERIAL", streaming.serial, "Streaming 串号 = start 入参（会话绑定串号透传——跨串号门控比对锚）")
            assertEquals(800, streaming.width)
            assertEquals(600, streaming.height)

            // config + 首帧两包都到达解码器（demuxer → decoder 通道）
            val first = harness.decoder.packets.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            val second = harness.decoder.packets.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(4, first?.size)
            assertEquals(4, second?.size)

            val frame1 = plainFrame()
            harness.decoder.offer(frame1)
            val published = harness.engine.frame.awaitUntil { it != null }
            assertEquals(1L, published!!.generation)

            // 帧率窗口：第二帧落在下一窗口（clock 前进 1500ms）——2 帧 / 1.5s 折 1 fps
            harness.nowMs = 1_500
            harness.decoder.offer(plainFrame())
            harness.engine.frame.first { it != null && it.generation == 1L && it.image !== published.image }
            assertEquals(1, harness.engine.fps.value)

            harness.engine.stop()
            val stopped = harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertEquals("已停止", stopped.message)
            assertFalse(stopped.failed)
            assertNull(harness.engine.frame.value)
            assertEquals(0, harness.engine.fps.value)

            // 收尾：reverse 规则显式 remove（NonCancellable 段执行，不被取消短路）
            assertTrue(harness.adb.commands.any { "adb reverse --remove localabstract:scrcpy_" in it })
            // serve 命令过 AdbCommandRewriter 后经 runner 长跑（串号注入）
            assertTrue(
                harness.runner.commands.any { "tunnel_forward=false" in it && "-s TEST-SERIAL" in it },
            )
            // adb 路径与串号经 adb 工厂透传（三条命令链同源注入）
            assertTrue(harness.gatewayCalls.contains("" to "TEST-SERIAL"))
        }
    }

    @Test
    fun `stop during tunnel open still removes outstanding reverse rule`(): Unit = runBlocking {
        // 开链中段停止：建 reverse 规则命令的结果被取消吞没（adb 子进程可能已完成建规则，
        // 结果未观测）——会话按取消收口，收口的挂账面摘除仍必达（tunnel 未建立也不例外）
        ScreencastHarness(cancelOn = Regex("adb reverse localabstract:")).use { harness ->
            assertTrue(harness.engine.start("", "TEST-SERIAL"))
            val stopped = harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertEquals("已停止", stopped.message)
            assertFalse(stopped.failed)
            assertTrue(
                harness.adb.commands.any { "adb reverse --remove localabstract:scrcpy_" in it },
                "开链夭折会话的收口仍必须摘除挂账规则，实际命令序列: ${harness.adb.commands.toList()}",
            )
        }
    }

    @Test
    fun `late frame from stopped session does not revive into frame flow`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            harness.decoder.offer(plainFrame())
            harness.engine.frame.awaitUntil { it != null }

            // 帧流观察者在停止**前**挂上——停机前/中/后的全部发布都在观察面内
            // （突变敏感：拆掉发布闸，迟到帧会进 recorder，本用例即红）
            val recorded = CopyOnWriteArrayList<Long>()
            val recorder = launch {
                harness.engine.frame.collect { frame -> frame?.let { recorded.add(it.generation) } }
            }

            // 预置迟到帧：停止解锁僵尸解码协程后第一次 grab 产出旧世代帧
            harness.decoder.armLateFrame(plainFrame())
            harness.engine.stop()
            val stopped = harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertEquals("已停止", stopped.message)

            // 等僵尸醒来处理迟到帧（grab poll 周期 100ms 内必醒）——闸后不得复活
            Thread.sleep(300)
            assertEquals(listOf(1L), recorded.toList(), "停止后迟到帧不得进帧流（发布闸失效即红）")
            assertNull(harness.engine.frame.value)
            assertTrue(harness.engine.status.value is ScreencastStatus.Stopped)
            recorder.cancel()
        }
    }

    @Test
    fun `decoder failure lands stopped with real cause not disconnect misattribution`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            harness.decoder.offer(plainFrame())
            harness.engine.frame.awaitUntil { it != null }

            // 坏流注入：解码器 grab 抛——真因必须进终态，不得误归「设备端连接断开」
            harness.decoder.failWith = IOException("坏流：SPS 解析失败")

            val stopped = harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed)
            assertTrue("解码：坏流：SPS 解析失败" in stopped.message, "终态必须承载解码真因 + 段名语境（实际: ${stopped.message}）")
            assertTrue("投屏流已结束" !in stopped.message, "坏流不得误归为连接断开")
        }
    }

    @Test
    fun `failure with null message keeps stage context instead of bare class name`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            harness.decoder.offer(plainFrame())
            harness.engine.frame.awaitUntil { it != null }

            // message 为 null 的异常（EOFException/FFmpeg natives 常态）：终态不得只落
            // 类名裸串——段名语境（解码）必须前缀定位
            harness.decoder.failWith = IOException(null as String?)

            val stopped = harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed)
            assertTrue(
                stopped.message.startsWith("解码："),
                "message 为 null 时终态须带链段语境前缀（实际: ${stopped.message}）",
            )
            assertTrue(
                stopped.message != "IOException",
                "类名裸串无链段定位即红（实际: ${stopped.message}）",
            )
        }
    }

    @Test
    fun `mid-stream client disconnect lands explicit failed stopped`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            harness.decoder.offer(plainFrame())
            harness.engine.frame.awaitUntil { it != null }

            // 测试侧「server 角色」连接全部断开（拔线等价）→ EOF 收口显式失败终态
            harness.clients.forEach { runCatching { it.close() } }

            val stopped = harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed)
            assertTrue("投屏流已结束" in stopped.message)
        }
    }

    @Test
    fun `restart stops current session awaits terminal then starts with last accepted params`(): Unit = runBlocking {
        // 真重试契约：restart = 停当前会话 → 等终态（终态由会话协程 finally 异步落定，
        // 不 await 即重入被拒——假动作即红）→ 按上次受理参数重启；options 覆盖面由
        // 停投重开用例消费，本用例验原参重启
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            val frame1 = plainFrame()
            harness.decoder.offer(frame1)
            harness.engine.frame.awaitUntil { it != null }

            // restart（挂起等终态）：终态落定后同参重启——新会话新世代直落 Streaming
            val restarted = harness.engine.restart()
            assertTrue(restarted, "有历史受理参数时 restart 须完成真重试（等终态后重启受理）")
            harness.serverRoleHandshake()
            val streaming2 = harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            streaming2 as ScreencastStatus.Streaming
            assertEquals("TEST-SERIAL", streaming2.serial, "重启按上次受理参数（串号透传锚）")
            harness.decoder.offer(plainFrame())
            assertEquals(2L, (harness.engine.frame.awaitUntil { it != null })!!.generation, "重启会话新世代")
        }
    }

    @Test
    fun `restart without history params returns false without side effects`(): Unit = runBlocking {
        // 无历史受理参数（进程内从未受理过）：restart 恒 false——窗内重开编排按它回
        // 执并恢复常规生命周期，不得误报成功
        ScreencastHarness().use { harness ->
            assertFalse(harness.engine.restart(), "无历史参数的 restart 须显式失败（不静默误报）")
            assertTrue(harness.engine.status.value is ScreencastStatus.Idle, "失败路径零会话副作用")
        }
    }

    @Test
    fun `second session after stop owns new generation without old frame pollution`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            harness.decoder.offer(plainFrame())
            val first = harness.engine.frame.awaitUntil { it != null }
            assertEquals(1L, first!!.generation)

            harness.engine.stop()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }

            // 帧流观察者先挂上再起第二会话——B 起点之后的任何发布都在观察面内
            val recorded = CopyOnWriteArrayList<Long>()
            val recorder = launch {
                harness.engine.frame.collect { frame -> frame?.let { recorded.add(it.generation) } }
            }

            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            assertNull(harness.engine.frame.value, "新会话起点帧流必须清屏")

            harness.decoder.offer(plainFrame()) // decoders.last() = 第二会话的实例
            val second = harness.engine.frame.awaitUntil { it != null }
            assertEquals(2L, second!!.generation)

            // B 出图后帧流只见世代 2（旧世代帧污染即红）
            delay(300)
            assertTrue(recorded.all { it == 2L }, "第二会话期帧流不得出现旧世代（实际 $recorded）")
            recorder.cancel()
        }
    }

    @Test
    fun `concurrent double start admits exactly one session`(): Unit = runBlocking {
        // 真实空置监听：受胜会话停在 accept（保持 Connecting 态），并发其余各路必见拒绝
        val listener = ServerSocket(0)
        val port = listener.localPort
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ScreencastEngine(
            scope = scope,
            adbGatewayFactory = { _, _ -> FakeAdbGateway() },
            runnerFactory = { FakeRunner() },
            listenerFactory = { candidate -> if (candidate == port) listener else null },
            serverJarFile = File.createTempFile("scrcpy-server", ".jar").apply { deleteOnExit() },
            // 释放器四态由 ScrcpyServerJarReleaserTest 钉死——本用例只验并发 start 重入位
            serverJarReleaser = { ScrcpyServerJarReleaser.Outcome.Released },
            portRange = port..port,
            connectTimeoutMs = 10_000,
            acceptSoTimeoutMs = 15_000,
        )
        try {
            val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
            repeat(8) {
                scope.launch { results.add(engine.start("", "S")) }
            }
            withTimeout(5_000) { while (results.size < 8) delay(10) }
            assertEquals(1, results.count { it }, "并发 8 路 start 必须恰一个成功（实际 ${results.count { it }}）")
            // 受胜会话停在 accept 保持 Connecting——终态前任何 start 恒拒绝
            assertFalse(engine.start("", "S"))
        } finally {
            engine.stop()
            runCatching { listener.close() }
            scope.cancel()
        }
    }

    @Test
    fun `stream end without user stop lands explicit failed stopped`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            harness.decoder.offer(plainFrame())
            harness.engine.frame.awaitUntil { it != null }
            harness.decoder.endStream()

            val stopped = harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed)
            assertTrue("投屏流已结束" in stopped.message)
        }
    }

    @Test
    fun `missing server jar fails explicitly before any adb command`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adb = FakeAdbGateway()
        val engine = ScreencastEngine(
            scope = scope,
            adbGatewayFactory = { _, _ -> adb },
            runnerFactory = { FakeRunner() },
            serverJarFile = File(System.getProperty("java.io.tmpdir"), "no-such-scrcpy-server.jar"),
            // 注入缺失资源面（真释放器 + null 资源流）——释放失败必须显式落终态且零 adb 命令
            serverJarReleaser = { target -> ScrcpyServerJarReleaser.release(target) { null } },
        )
        try {
            assertTrue(engine.start("", null))
            val stopped = engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed)
            assertTrue("资源缺失" in stopped.message, "消息应为资源缺失显式失败：${stopped.message}")
            // 前置失败在链路最前——零 adb 命令、零残留
            assertTrue(adb.commands.isEmpty())
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    @Test
    fun `stale version residue conflicts explicitly without touching files`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adb = FakeAdbGateway()
        val engine = ScreencastEngine(
            scope = scope,
            adbGatewayFactory = { _, _ -> adb },
            runnerFactory = { FakeRunner() },
            serverJarFile = File(System.getProperty("java.io.tmpdir"), "no-such-scrcpy-server.jar"),
            // 直接注入 Conflict（释放器的残留判型由 ScrcpyServerJarReleaserTest 钉死）——
            // 引擎接线面只验「冲突人话提示显式落终态 + 零 adb 命令」
            serverJarReleaser = {
                ScrcpyServerJarReleaser.Outcome.Conflict(
                    listOf(File("scrcpy-server-v4.0")),
                    "检测到 scrcpy 目录存在不同版本 server jar（scrcpy-server-v4.0）——" +
                        "不会自动覆盖你手动置入的文件，请删除旧文件后重试",
                )
            },
        )
        try {
            assertTrue(engine.start("", null))
            val stopped = engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed)
            assertTrue("删除旧文件后重试" in stopped.message, "实际消息: ${stopped.message}")
            assertTrue(adb.commands.isEmpty())
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    @Test
    fun `adb push failure lands explicit stopped`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adb = FakeAdbGateway(failOn = Regex("adb push"))
        val engine = ScreencastEngine(
            scope = scope,
            adbGatewayFactory = { _, _ -> adb },
            runnerFactory = { FakeRunner() },
            serverJarFile = File.createTempFile("scrcpy-server", ".jar").apply { deleteOnExit() },
            // 释放器四态由 ScrcpyServerJarReleaserTest 钉死——本用例只验 push 段
            serverJarReleaser = { ScrcpyServerJarReleaser.Outcome.Released },
        )
        try {
            assertTrue(engine.start("", "SERIAL"))
            val stopped = engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed)
            assertTrue("adb push 失败" in stopped.message)
            // push 失败即止——隧道/serve 零执行
            assertTrue(adb.commands.none { "forward" in it || "reverse" in it || "app_process" in it })
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    @Test
    fun `start rejects reentry while connecting`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ScreencastEngine(
            scope = scope,
            adbGatewayFactory = { _, _ -> FakeAdbGateway() },
            runnerFactory = { FakeRunner() },
            serverJarFile = File(System.getProperty("java.io.tmpdir"), "no-such-scrcpy-server.jar"),
            // 同款注入缺失资源面——本用例只需快速显式失败占住 Connecting 槽
            serverJarReleaser = { target -> ScrcpyServerJarReleaser.release(target) { null } },
        )
        try {
            assertTrue(engine.start("", null))
            assertFalse(engine.start("", null), "Connecting 中必须拒绝重入")
            engine.status.awaitUntil { it is ScreencastStatus.Stopped }
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    @Test
    fun `reverse accept without server reverse connect fails explicitly without hanging`(): Unit = runBlocking {
        // 真实空置监听口（无人反连）——两条醒超路径（withTimeout / soTimeout 兜底）任一
        // 显式落失败终态；本用例同时证明阻塞 accept 不吞测试线程（测试在时限内收场即通过）
        val listener = ServerSocket(0)
        val port = listener.localPort
        val adb = FakeAdbGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ScreencastEngine(
            scope = scope,
            adbGatewayFactory = { _, _ -> adb },
            runnerFactory = { FakeRunner() },
            listenerFactory = { candidate -> if (candidate == port) listener else null },
            serverJarFile = File.createTempFile("scrcpy-server", ".jar").apply { deleteOnExit() },
            // 释放器四态由 ScrcpyServerJarReleaserTest 钉死——本用例只验 accept 超时路径
            serverJarReleaser = { ScrcpyServerJarReleaser.Outcome.Released },
            portRange = port..port,
            connectTimeoutMs = 300,
            acceptSoTimeoutMs = 800,
        )
        try {
            val startAt = System.nanoTime()
            assertTrue(engine.start("", null))
            val stopped = engine.status.awaitUntil(2_000) { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed, "连接超时必须显式失败，不得冒充「已停止」")
            // 两条醒超路径均显式：withTimeout 先醒（连接超时）或 soTimeout 兜底先醒（未反连）
            assertTrue("连接超时" in stopped.message || "未反连" in stopped.message, "实际消息: ${stopped.message}")
            val elapsedMs = (System.nanoTime() - startAt) / 1_000_000
            assertTrue(elapsedMs < 2_000, "阻塞 accept 不得挂死会话（实测 ${elapsedMs}ms）")
            // 失败终态同样摘除规则（reverse 隧道已建立——失败收口的 remove 必达）
            assertTrue(
                adb.commands.any { "adb reverse --remove localabstract:scrcpy_" in it },
                "失败收口仍必须摘除 reverse 规则，实际命令序列: ${adb.commands.toList()}",
            )
        } finally {
            engine.stop()
            runCatching { listener.close() }
            scope.cancel()
        }
    }

    @Test
    fun `forward probe retry exhausted fails explicitly`(): Unit = runBlocking {
        // 空置端口（无人监听）：reverse bind 失败转 forward，forward 命令成立但无人 accept——
        // 探活重试耗尽显式失败
        val probe = ServerSocket(0)
        val port = probe.localPort
        probe.close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ScreencastEngine(
            scope = scope,
            adbGatewayFactory = { _, _ -> FakeAdbGateway() },
            runnerFactory = { FakeRunner() },
            listenerFactory = { null },
            serverJarFile = File.createTempFile("scrcpy-server", ".jar").apply { deleteOnExit() },
            // 释放器四态由 ScrcpyServerJarReleaserTest 钉死——本用例只验探活重试耗尽路径
            serverJarReleaser = { ScrcpyServerJarReleaser.Outcome.Released },
            portRange = port..port,
            probeRetry = ScrcpyHandshakeReader.ProbeRetryPolicy(maxAttempts = 2, retryDelayMs = 10),
            connectTimeoutMs = 500,
        )
        try {
            assertTrue(engine.start("", null))
            val stopped = engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed)
            assertTrue("probe failed after 2 attempts" in stopped.message)
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    @Test
    fun `control injection lands official wire bytes on the control socket`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            // serve 命令开控制通道（server 侧才会建第二条 socket）
            assertTrue(harness.runner.commands.any { "control=true" in it })

            // 单键 = down + up 成对两笔（28B）
            assertTrue(harness.engine.controlInjectKey(24))
            val keyBytes = harness.readControlBytes(28)
            assertContentEquals(
                ScrcpyControlEncoder.injectKeycode(ScrcpyControlProtocol.KEY_ACTION_DOWN, 24) +
                    ScrcpyControlEncoder.injectKeycode(ScrcpyControlProtocol.KEY_ACTION_UP, 24),
                keyBytes,
            )

            // 文本注入（IME 语义）
            assertTrue(harness.engine.controlInjectText("你好 scrcpy"))
            assertEquals(
                ScrcpyControlEncoder.injectText("你好 scrcpy").toList(),
                harness.readControlBytes(
                    ScrcpyControlEncoder.injectText("你好 scrcpy").size,
                ).toList(),
            )

            // 触摸三段（down/move/up——up 恒零压由引擎收口）
            assertTrue(
                harness.engine.controlInjectTouch(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 100, 200, 2880, 1620),
            )
            assertEquals(
                ScrcpyControlEncoder.injectTouch(
                    ScrcpyControlProtocol.MOTION_ACTION_DOWN,
                    ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER,
                    100, 200, 2880, 1620, 1f,
                ).toList(),
                harness.readControlBytes(32).toList(),
            )

            // 虚拟指 id 透传（多指通道：显式 pointerId 原样落 wire；缺省 = 上方 -2 恒等）
            assertTrue(
                harness.engine.controlInjectTouch(
                    ScrcpyControlProtocol.MOTION_ACTION_DOWN, 300, 400, 2880, 1620,
                    ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST,
                ),
            )
            assertEquals(
                ScrcpyControlEncoder.injectTouch(
                    ScrcpyControlProtocol.MOTION_ACTION_DOWN,
                    ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST,
                    300, 400, 2880, 1620, 1f,
                ).toList(),
                harness.readControlBytes(32).toList(),
            )

            // 滚动 + 剪贴板（sequence 由引擎自增——回读端按序号对齐）
            assertTrue(harness.engine.controlInjectScroll(50, 60, 2880, 1620, 0f, 1f))
            assertEquals(
                ScrcpyControlEncoder.injectScroll(50, 60, 2880, 1620, 0f, 1f).toList(),
                harness.readControlBytes(21).toList(),
            )
            assertTrue(harness.engine.controlSetClipboard("e2e 剪贴板"))
            assertEquals(
                ScrcpyControlEncoder.setClipboard(1L, "e2e 剪贴板", paste = false).toList(),
                harness.readControlBytes(
                    ScrcpyControlEncoder.setClipboard(1L, "e2e 剪贴板", paste = false).size,
                ).toList(),
            )
        }
    }

    @Test
    fun `set clipboard ack flows back through controlAck flow`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            assertEquals(0L, harness.engine.controlAck.value)

            assertTrue(harness.engine.controlSetClipboard("第一笔"))
            assertTrue(harness.engine.controlSetClipboard("第二笔"))
            // 测试侧扮演 server 回执 ack(sequence=2)（8B 大端）
            val controlOut = harness.controlClient!!.getOutputStream()
            controlOut.write(ScrcpyControlProtocol.DEVICE_MSG_ACK_CLIPBOARD)
            val sequence = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 2)
            controlOut.write(sequence)
            controlOut.flush()

            val ack = harness.engine.controlAck.awaitUntil(3_000) { it == 2L }
            assertEquals(2L, ack)

            // 剪贴板快照（设备消息 pump 消费面）：server 推送 TYPE_CLIPBOARD → 引擎快照流
            assertNull(harness.engine.deviceClipboard.value, "快照未到时为 null")
            val controlStream = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(controlStream).apply {
                writeByte(ScrcpyControlProtocol.DEVICE_MSG_CLIPBOARD)
                val payload = "设备快照".toByteArray(Charsets.UTF_8)
                writeInt(payload.size)
                write(payload)
            }.flush()
            controlOut.write(controlStream.toByteArray())
            controlOut.flush()
            assertEquals("设备快照", harness.engine.deviceClipboard.awaitUntil(3_000) { it != null })

            // 显式读回请求：GET_CLIPBOARD(copyKey=NONE) 两字节落控制通道——先消费在途的
            // 两笔 set_clipboard 请求字节，读回面才与请求序对齐
            assertTrue(harness.engine.controlRequestClipboard())
            harness.readControlBytes(ScrcpyControlEncoder.setClipboard(1L, "第一笔", paste = false).size)
            harness.readControlBytes(ScrcpyControlEncoder.setClipboard(2L, "第二笔", paste = false).size)
            assertContentEquals(
                byteArrayOf(ScrcpyControlProtocol.TYPE_GET_CLIPBOARD.toByte(), 0),
                harness.readControlBytes(2),
            )
        }
    }

    @Test
    fun `injection after stop returns false and control socket closes with session`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }

            harness.engine.stop()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            // 终态后注入入口必须显式 false（通道已收口，不抛不静默复活）
            assertFalse(harness.engine.controlInjectKey(24), "停止后注入不得返回 true")
            assertFalse(harness.engine.controlInjectText("x"), "停止后文本注入不得返回 true")
            // 停止收口关闭控制 socket——server 侧读到 EOF
            val controlInput = harness.controlClient!!.getInputStream()
            assertEquals(-1, controlInput.read(), "停止后控制 socket 必须已关闭（EOF）")

            // 第二会话：注入只走新会话的通道（旧通道零复用）
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            assertTrue(harness.engine.controlInjectKey(25))
            assertContentEquals(
                ScrcpyControlEncoder.injectKeycode(ScrcpyControlProtocol.KEY_ACTION_DOWN, 25) +
                    ScrcpyControlEncoder.injectKeycode(ScrcpyControlProtocol.KEY_ACTION_UP, 25),
                harness.readControlBytes(28),
                "第二会话注入必须落在新会话的控制通道",
            )
            assertEquals(0L, harness.engine.controlAck.value, "新会话回执态必须重置")
            assertNull(harness.engine.deviceClipboard.value, "新会话剪贴板快照态必须重置")
        }
    }

    @Test
    fun `injection without session returns false`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            assertFalse(harness.engine.controlInjectKey(24), "Idle 态无控制通道，注入必须显式 false")
        }
    }

    @Test
    fun `missing second reverse connect fails session explicitly without hanging`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            // server 角色永不反连第二条（控制通道）——会话必须显式失败收场，不挂死
            harness.startAndHandshake(connectControl = false)
            val stopped = harness.engine.status.awaitUntil(10_000) { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed, "控制通道未建立必须显式失败，不得冒充「已停止」")
            assertTrue(
                "控制通道" in stopped.message || "连接超时" in stopped.message,
                "实际消息: ${stopped.message}",
            )
        }
    }

    @Test
    fun `device message stream failure does not tear down the video face`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            harness.decoder.offer(plainFrame())
            harness.engine.frame.awaitUntil { it != null }

            // 控制面投毒：未知设备消息型别令设备消息 pump 退出（控制回执面结束）
            val controlOut = harness.controlClient!!.getOutputStream()
            controlOut.write(99)
            controlOut.flush()

            // 画面面不受牵连：帧继续发布
            harness.decoder.offer(plainFrame())
            val second = harness.engine.frame.awaitUntil { it != null && it.generation == 1L }
            assertTrue(second != null, "控制面故障后帧流必须继续（只读回退契约）")
            assertTrue(harness.engine.status.value is ScreencastStatus.Streaming)
        }
    }

    @Test
    fun `forward probe retry loop is capped by connect timeout gate`() = runBlocking {
        // forward 隧道（无监听口）+ 大重试预算：总闸（connectTimeoutMs）必须先于重试耗尽
        // 收口——无总闸实现会按 attempts × 间隔吃满重试预算（本用例对无总闸实现即红）
        val probe = ServerSocket(0)
        val port = probe.localPort
        probe.close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ScreencastEngine(
            scope = scope,
            adbGatewayFactory = { _, _ -> FakeAdbGateway() },
            runnerFactory = { FakeRunner() },
            listenerFactory = { null },
            serverJarFile = File.createTempFile("scrcpy-server", ".jar").apply { deleteOnExit() },
            // 释放器四态由 ScrcpyServerJarReleaserTest 钉死——本用例只验探活总闸路径
            serverJarReleaser = { ScrcpyServerJarReleaser.Outcome.Released },
            portRange = port..port,
            probeRetry = ScrcpyHandshakeReader.ProbeRetryPolicy(maxAttempts = 10_000, retryDelayMs = 50),
            connectTimeoutMs = 300,
        )
        try {
            assertTrue(engine.start("", null))
            val stopped = engine.status.awaitUntil(10_000) { it is ScreencastStatus.Stopped }
            stopped as ScreencastStatus.Stopped
            assertTrue(stopped.failed)
            assertTrue(
                "连接超时" in stopped.message,
                "总闸必须先于重试耗尽收口为连接超时（实际: ${stopped.message}）",
            )
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    @Test
    fun `quality options land verbatim in serve command per tier`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            // 各档位逐字断言：serve 命令 key=value 段（tunnel_forward 之后全确定，scid 随机
            // 另有格式用例覆盖）逐字节核对；追加序 = max_size → max_fps → video_bit_rate
            val cases = listOf(
                ScreencastOptions(videoBitRate = 2_000_000L) to
                    "false control=true audio=false clipboard_autosync=false max_fps=30.0 video_bit_rate=2000000",
                ScreencastOptions(videoBitRate = 16_000_000L) to
                    "false control=true audio=false clipboard_autosync=false max_fps=30.0 video_bit_rate=16000000",
                ScreencastOptions(videoBitRate = 40_000_000L) to
                    "false control=true audio=false clipboard_autosync=false max_fps=30.0 video_bit_rate=40000000",
                ScreencastOptions(maxFps = 15f) to
                    "false control=true audio=false clipboard_autosync=false max_fps=15.0",
                ScreencastOptions(maxFps = 60f) to
                    "false control=true audio=false clipboard_autosync=false max_fps=60.0",
                ScreencastOptions(maxSize = 1080) to
                    "false control=true audio=false clipboard_autosync=false max_size=1080 max_fps=30.0",
                ScreencastOptions(maxSize = 720) to
                    "false control=true audio=false clipboard_autosync=false max_size=720 max_fps=30.0",
            )
            cases.forEachIndexed { index, (options, expectedTail) ->
                harness.startAndHandshake(options = options)
                harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
                val command = harness.serveCommandOfSession(index + 1)
                assertEquals(
                    expectedTail,
                    command.substringAfter("tunnel_forward="),
                    "档位 $options 未逐字落 serve 命令（实际 $command）",
                )
                harness.engine.stop()
                harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            }
        }
    }

    @Test
    fun `default options keep serve command identical to pre-options behavior`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            // 不选全默认 = 现行为零回归：max_fps 恒 30 追加、max_size/video_bit_rate/display_id 零追加
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            val command = harness.serveCommandOfSession(1)
            assertEquals(
                "false control=true audio=false clipboard_autosync=false max_fps=30.0",
                command.substringAfter("tunnel_forward="),
                "全默认档必须与扩参前 serve 命令逐字节一致（实际 $command）",
            )
            assertTrue(!command.contains("max_size"))
            assertTrue(!command.contains("video_bit_rate"))
            assertTrue(!command.contains("display_id"))
        }
    }

    @Test
    fun `source anchors - fps fold sits behind publish gate and video sockets set tcpNoDelay`() {
        // 源码锚用例：fps 折算的瞬态脏值只在闸前折算时才出现（StateFlow 合流令行为面
        // 不可确定性观察），TCP_NODELAY 是本端 socket 选项对端不可观测——两面只能源码锚钉死
        val source = engineSource()
        val gateIdx = source.indexOf("if (!isPublishable(generation)) break")
        val foldIdx = source.indexOf("_fps.value = ((windowFrames * FPS_WINDOW_MS)")
        assertTrue(gateIdx >= 0, "帧循环的发布闸调用缺失")
        assertTrue(foldIdx >= 0, "fps 窗口折算语句缺失")
        assertTrue(
            gateIdx < foldIdx,
            "fps 折算必须位于发布闸之后——闸前折算会在停止已落后发布瞬态帧率",
        )
        val reverseBranch = source.substringAfter("is ScrcpyTunnel.Reverse -> {")
            .substringBefore("is ScrcpyTunnel.Forward -> {")
        val forwardBranch = source.substringAfter("is ScrcpyTunnel.Forward -> {")
            .substringBefore("handshake.streamConfigError")
        // 锚收紧到 video socket 建连段（控制 socket 同分支本就置位，分支级锚会被误背书）
        val reverseVideo = reverseBranch.substringAfter("accepted.also {")
            .substringBefore("控制通道 = 第二条反连")
        val forwardVideo = forwardBranch.substringAfter("connectForward(")
            .substringBefore("控制通道 = 第二条连接")
        assertTrue(
            "tcpNoDelay" in reverseVideo,
            "reverse 分支 video socket 必须设 tcpNoDelay（与 spike 验证配置同源）",
        )
        assertTrue(
            "tcpNoDelay" in forwardVideo,
            "forward 分支 video socket 必须设 tcpNoDelay（与 spike 验证配置同源）",
        )
    }

    @Test
    fun `source anchors - scid random source is SecureRandom`() {
        // 源码锚用例：scid 进 socket 名与 serve 命令两处，随机源可预测性（socket 名可
        // 指名抢占）属源码层性质——行为面（SecureRandom 序列不可断言）不可观察
        val source = engineSource()
        val scidLine = source.substringAfter("val scid = ").substringBefore("\n")
        assertTrue(
            "scidRandom" in scidLine,
            "scid 必须取自专用安全随机源成员（实际行: $scidLine）",
        )
        assertTrue(
            "SecureRandom" in source.substringAfter("private val scidRandom"),
            "scid 随机源成员必须是 SecureRandom",
        )
        assertTrue(
            "kotlin.random.Random" !in source,
            "可预测的 kotlin.random.Random 不得再作为 scid 随机源",
        )
        val randomDecl = source.substringAfter("private val scidRandom")
        assertTrue(
            "2^31" in source,
            "值域契约须与 SCID_MAX_EXCLUSIVE 同源钉在成员 KDoc（防 nextInt 截位漂移无据）",
        )
    }

    private fun engineSource(): String {
        // 测试工作目录是模块目录，向上找 settings.gradle.kts 定位项目根
        var dir = File(".").absoluteFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                return File(
                    dir,
                    "feature-screencast/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/ScreencastEngine.kt",
                ).readText()
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("项目根未定位（settings.gradle.kts 未在祖先目录找到）")
    }

    private fun plainFrame(): ScreencastDecodedFrame {
        val image = ImageBitmap(4, 4)
        return ScreencastDecodedFrame(image = image, width = 4, height = 4)
    }

    private suspend fun <T> StateFlow<T>.awaitUntil(
        timeoutMs: Long = 5_000,
        predicate: (T) -> Boolean,
    ): T = withTimeout(timeoutMs) { first { predicate(it) } }
}
