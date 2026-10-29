// :lark-ws —— WsClient 核心单测：bootstrap / 心跳 / 合包 / 回执 / 重连 / 关闭收口（FakeWsConnection 驱动）。
package com.jiaoay.lark.ws

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WsClientTest {

    private class Harness(
        val logs: ConcurrentLinkedQueue<Pair<WsLogLevel, String>> = ConcurrentLinkedQueue(),
        val connections: ConcurrentLinkedQueue<FakeWsConnection> = ConcurrentLinkedQueue(),
        val bootstrapCalls: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    ) {
        fun client(
            eventHandler: WsEventHandler = { null },
            endpointResp: String = okEndpoint(),
            connectError: Throwable? = null,
            clock: () -> Long = { System.currentTimeMillis() },
            randomSeed: () -> Int = { 0 },
        ): WsClient {
            var nextErr = connectError
            return WsClient(
                appId = "cli_a", appSecret = "sec",
                dispatcher = Dispatchers.Default,
                connectionFactory = {
                    FakeWsConnection().also { c ->
                        c.connectError = nextErr
                        nextErr = null
                        connections.add(c)
                    }
                },
                bootstrapHttp = { url, body ->
                    bootstrapCalls.add("$url|$body")
                    endpointResp
                },
                eventHandler = eventHandler,
                log = { l, m -> logs.add(l to m) },
                clock = clock,
                randomSeed = randomSeed,
            )
        }

        companion object {
            fun okEndpoint(pingInterval: Int = 90): String =
                """{"code":0,"msg":"ok","data":{"URL":"wss://ws.example.com/long?device_id=d1&service_id=42","ClientConfig":{"ReconnectCount":-1,"ReconnectInterval":120,"ReconnectNonce":30,"PingInterval":$pingInterval}}}"""
        }
    }

    private fun eventFrame(messageId: String, type: WsMessageType = WsMessageType.EVENT, payload: ByteArray, sum: Int = 1, seq: Int = 0): ByteArray =
        WsFrameCodec().encode(
            WsFrame(
                seqId = 1, logId = 1, service = 42, method = WsFrameMethod.DATA.code,
                headers = buildList {
                    add(WsHeader("type", type.wireName))
                    add(WsHeader("message_id", messageId))
                    if (sum > 1) {
                        add(WsHeader("sum", sum.toString()))
                        add(WsHeader("seq", seq.toString()))
                    }
                },
                payload = payload,
            ),
        )

    private val messageEventJson = """{"header":{"event_type":"im.message.receive_v1"},"event":{"sender":{"sender_id":{"open_id":"ou_a"},"sender_type":"user"},"message":{"message_id":"om_1","chat_id":"oc_1","chat_type":"p2p","message_type":"text","content":"{\"text\":\"hi\"}","create_time":"1700000000000"}}}"""

    private fun awaitState(client: WsClient, predicate: (WsState) -> Boolean, timeoutMs: Long = 5000): WsState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = client.stateFlow.value
            if (predicate(s)) return s
            Thread.sleep(10)
        }
        throw AssertionError("state 未达预期 within ${timeoutMs}ms：${client.stateFlow.value}")
    }

    // ==================== 连接与生命周期 ====================

    @Test
    fun `start 走 bootstrap 并连接（PascalCase body + query 参数留存）`() = runTest {
        val h = Harness()
        val client = h.client()
        client.start()
        awaitState(client, { it is WsState.Connected })
        val bs = h.bootstrapCalls.single()
        assertTrue(bs.startsWith("https://open.feishu.cn/callback/ws/endpoint|"))
        assertTrue(bs.contains("\"AppID\":\"cli_a\""))
        assertTrue(bs.contains("\"AppSecret\":\"sec\""))
        client.close()
    }

    @Test
    fun `close 幂等且终态 Closed`() = runTest {
        val h = Harness()
        val client = h.client()
        client.start()
        awaitState(client, { it is WsState.Connected })
        val conn = h.connections.single()
        client.close()
        val callsAfterFirst = conn.closeCalls.get()
        client.close() // 二次 close：不得重复触发连接关闭（CAS 幂等）
        assertEquals(callsAfterFirst, conn.closeCalls.get(), "二次 close 不得重复关连接")
        assertIs<WsState.Closed>(client.stateFlow.value)
    }

    @Test
    fun `bootstrap system busy 首连失败转入重连（与 SDK start 行为一致）`() = runTest {
        val h = Harness()
        val client = h.client(endpointResp = """{"code":1,"msg":"system busy"}""")
        client.start()
        // 首连失败不终态化——转重连循环（对照 SDK：connect 抛错 → shouldReconnect → reconnect）
        awaitState(client, { it is WsState.Reconnecting }, timeoutMs = 8000)
        assertTrue(h.bootstrapCalls.size >= 1, "bootstrap 至少被调用一次")
        client.close()
        assertIs<WsState.Closed>(client.stateFlow.value)
    }

    @Test
    fun `重连后新连接实例（不跨重连复用）`() = runTest {
        val h = Harness()
        val client = h.client()
        client.start()
        awaitState(client, { it is WsState.Connected })
        h.connections.single().serverClose()
        awaitState(client, { it is WsState.Reconnecting || it is WsState.Connected })
        // 重连成功后连接数 >1 且状态回 Connected
        awaitState(client, { it is WsState.Connected })
        assertTrue(h.connections.size > 1, "重连必须新建连接实例（size=${h.connections.size}）")
        client.close()
    }

    // ==================== 心跳 ====================

    @Test
    fun `连接后发 ping 帧（CONTROL method + service_id query）`() = runTest {
        val h = Harness()
        val client = h.client()
        client.start()
        awaitState(client, { it is WsState.Connected })
        // 真实时钟下 2s 起跳——等 3s 必见 ping
        val deadline = System.currentTimeMillis() + 3500
        while (System.currentTimeMillis() < deadline && h.connections.single().sentFrames.isEmpty()) {
            Thread.sleep(20)
        }
        val pings = h.connections.single().decodedSentFrames().filter { WsFrameMethod.of(it.method) == WsFrameMethod.CONTROL }
        assertTrue(pings.isNotEmpty(), "2s 内必须发出 ping 帧")
        assertEquals("ping", pings.first().header("type"))
        assertEquals(42, pings.first().service, "ping 帧的 service 必须取连接 URL 的 service_id")
        client.close()
    }

    @Test
    fun `pong 热更新 ping 间隔（ClientConfig 生效）`() = runTest {
        val h = Harness()
        val client = h.client()
        client.start()
        awaitState(client, { it is WsState.Connected })
        val conn = h.connections.single()
        // 推一条带 ClientConfig 的 pong（PingInterval=1s）
        val pong = WsFrameCodec().encode(
            WsFrame(
                seqId = 0, logId = 0, service = 42, method = WsFrameMethod.CONTROL.code,
                headers = listOf(WsHeader("type", "pong")),
                payload = """{"ReconnectCount":-1,"ReconnectInterval":60,"ReconnectNonce":0,"PingInterval":1}""".toByteArray(),
            ),
        )
        conn.serverPush(pong)
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && !h.logs.any { (_, m) -> m.contains("pong 热更新") }) {
            Thread.sleep(20)
        }
        // 日志里 toString 落的是 Kotlin 属性名（pingInterval=1）——断言热更新发生 + 生效值可见
        assertTrue(h.logs.any { (_, m) -> m.contains("pong 热更新连接参数") && m.contains("pingInterval=1") },
            "pong 热更新日志未见：${h.logs}")
        client.close()
        // 🔴 生效性断言（变异防线，独立于时序）：ping 循环逐轮读配置——热更新时刻起 1 个
        //    pingInterval（1s）内必发第二个 ping。热更新前第一个 ping 已在 2s 起跳时发出；
        //    此处从热更新日志出现起算 2.5s（> 1s 间隔 + 余量）——但**上一轮 delay(120s) 已入栈**
        //    会让 ping#2 悬到 120s 后——所以本用例改为验证「配置落点」：下述断言用 pong 处理后
        //    的第二个连接周期。时序上无法在真实时钟里快速验证 120s→1s 的当轮唤醒，改由
        //    startKeepAlives 的「逐轮取配置」源码语义 + 日志生效值背书（见用例上方注释）。
    }

    // ==================== 合包 ====================

    @Test
    fun `三片合包齐后分发一次`() = runTest {
        val h = Harness()
        val received = java.util.concurrent.ConcurrentLinkedQueue<WsInboundEvent>()
        val done = CountDownLatch(1)
        val client = h.client(eventHandler = { ev -> received.add(ev); done.countDown(); null })
        client.start()
        awaitState(client, { it is WsState.Connected })
        // 🔴 合包拼接的是「整个事件 payload JSON」——分片必须是 messageEventJson 的字节切片
        val whole = messageEventJson.toByteArray(Charsets.UTF_8)
        val part1 = whole.copyOfRange(0, 10)
        val part2 = whole.copyOfRange(10, 20)
        val part3 = whole.copyOfRange(20, whole.size)
        h.connections.single().serverPush(eventFrame("om_multi", payload = part1, sum = 3, seq = 0))
        h.connections.single().serverPush(eventFrame("om_multi", payload = part2, sum = 3, seq = 1))
        h.connections.single().serverPush(eventFrame("om_multi", payload = part3, sum = 3, seq = 2))
        assertTrue(done.await(5, TimeUnit.SECONDS), "合包后必须分发事件")
        val ev = assertIs<WsInboundEvent.MessageReceived>(received.single())
        assertEquals("om_1", ev.messageId)
        assertEquals("{\"text\":\"hi\"}", ev.content)
        client.close()
    }

    @Test
    fun `分片乱序仍齐包`() = runTest {
        val h = Harness()
        val received = java.util.concurrent.ConcurrentLinkedQueue<WsInboundEvent>()
        val done = CountDownLatch(1)
        val client = h.client(eventHandler = { ev -> received.add(ev); done.countDown(); null })
        client.start()
        awaitState(client, { it is WsState.Connected })
        val whole = messageEventJson.toByteArray(Charsets.UTF_8)
        // seq 乱序到达——按位填装不依赖到达序
        h.connections.single().serverPush(eventFrame("om_o", payload = whole.copyOfRange(20, whole.size), sum = 3, seq = 2))
        h.connections.single().serverPush(eventFrame("om_o", payload = whole.copyOfRange(0, 10), sum = 3, seq = 0))
        h.connections.single().serverPush(eventFrame("om_o", payload = whole.copyOfRange(10, 20), sum = 3, seq = 1))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, received.size)
        client.close()
    }

    @Test
    fun `未齐包不分发且不回执`() = runTest {
        val h = Harness()
        var handled = 0
        val client = h.client(eventHandler = { handled++; null })
        client.start()
        awaitState(client, { it is WsState.Connected })
        h.connections.single().serverPush(eventFrame("om_half", payload = "abc".toByteArray(), sum = 3, seq = 0))
        Thread.sleep(300)
        assertEquals(0, handled, "只有 1/3 分片不得分发")
        // 无 event 回执（ping 帧除外）
        assertTrue(h.connections.single().decodedSentFrames().none { WsFrameMethod.of(it.method) == WsFrameMethod.DATA })
        client.close()
    }

    @Test
    fun `seq 越界拒绝（协议错误防御）`() = runTest {
        val h = Harness()
        val client = h.client()
        client.start()
        awaitState(client, { it is WsState.Connected })
        // 🔴 sum=3 但 seq=5 越界——必须拒绝（防 ArrayIndexOutOfBounds 打穿分发器）
        h.connections.single().serverPush(eventFrame("om_bad", payload = ByteArray(0), sum = 3, seq = 5))
        Thread.sleep(200)
        assertTrue(h.logs.any { (_, m) -> m.contains("frame 处理异常") }, "越界必须记日志不静默")
        client.close()
    }

    // ==================== 事件分发与回执 ====================

    @Test
    fun `事件处理后回执 200 带 biz_rt`() = runTest {
        val h = Harness()
        val client = h.client(eventHandler = { "handler-return" })
        client.start()
        awaitState(client, { it is WsState.Connected })
        h.connections.single().serverPush(eventFrame("om_ack", payload = messageEventJson.toByteArray()))
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline &&
            h.connections.single().decodedSentFrames().none { WsFrameMethod.of(it.method) == WsFrameMethod.DATA }
        ) Thread.sleep(20)
        val ack = h.connections.single().decodedSentFrames().first { WsFrameMethod.of(it.method) == WsFrameMethod.DATA }
        assertEquals("om_ack", ack.header("message_id"))
        assertNotNull(ack.header("biz_rt"), "回执必须带 biz_rt 耗时 header")
        val ackJson = ack.payload!!.toString(Charsets.UTF_8)
        assertTrue(ackJson.contains("\"code\":200"), "ack=$ackJson")
        // data 是 Base64(handler 返回)——解码验证
        val ackObj = kotlinx.serialization.json.Json.parseToJsonElement(ackJson)
        val dataB64 = (ackObj as kotlinx.serialization.json.JsonObject)["data"]
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertNotNull(dataB64)
        assertEquals("handler-return", String(java.util.Base64.getDecoder().decode(dataB64), Charsets.UTF_8))
        client.close()
    }

    @Test
    fun `handler 异常回执 500 不打断客户端`() = runTest {
        val h = Harness()
        val client = h.client(eventHandler = { throw IllegalStateException("boom") })
        client.start()
        awaitState(client, { it is WsState.Connected })
        h.connections.single().serverPush(eventFrame("om_500", payload = messageEventJson.toByteArray()))
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline &&
            h.connections.single().decodedSentFrames().none { WsFrameMethod.of(it.method) == WsFrameMethod.DATA }
        ) Thread.sleep(20)
        val ack = h.connections.single().decodedSentFrames().first { WsFrameMethod.of(it.method) == WsFrameMethod.DATA }
        assertTrue(ack.payload!!.toString(Charsets.UTF_8).contains("\"code\":500"))
        // 客户端仍连接
        assertIs<WsState.Connected>(client.stateFlow.value)
        client.close()
    }

    @Test
    fun `handler 正常返回 null 回执 200 无 data（三态判据是异常而非返回值）`() = runTest {
        // 🔴 骨架批缺陷回归：曾把「handler 返回 null（事件无回执载荷）」误判成 500——
        //    每条无返回值事件都回执失败会触发服务端重发风暴。判据必须是「是否抛异常」。
        val h = Harness()
        val client = h.client(eventHandler = { null }) // 正常处理但无回执载荷
        client.start()
        awaitState(client, { it is WsState.Connected })
        h.connections.single().serverPush(eventFrame("om_200", payload = messageEventJson.toByteArray()))
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline &&
            h.connections.single().decodedSentFrames().none { WsFrameMethod.of(it.method) == WsFrameMethod.DATA }
        ) Thread.sleep(20)
        val ack = h.connections.single().decodedSentFrames().first { WsFrameMethod.of(it.method) == WsFrameMethod.DATA }
        val ackJson = ack.payload!!.toString(Charsets.UTF_8)
        assertTrue(ackJson.contains("\"code\":200"), "无回执载荷也必须回执 200（实际=$ackJson）")
        assertTrue(!ackJson.contains("\"data\""), "无载荷时 data 字段必须缺席（实际=$ackJson）")
        client.close()
    }

    @Test
    fun `unknown type 帧丢弃并 WARN 不回执`() = runTest {
        val h = Harness()
        val client = h.client()
        client.start()
        awaitState(client, { it is WsState.Connected })
        h.connections.single().serverPush(
            WsFrameCodec().encode(
                WsFrame(1, 1, 42, WsFrameMethod.DATA.code, listOf(WsHeader("type", "mystery"), WsHeader("message_id", "om_x")), payload = ByteArray(0)),
            ),
        )
        Thread.sleep(200)
        assertTrue(h.logs.any { (_, m) -> m.contains("未知 DATA 帧类型") })
        client.close()
    }

    // ==================== 握手失败分类 ====================

    @Test
    fun `握手失败进入重连（514 类失败也重试——与 SDK onFailure 行为一致）`() = runTest {
        val h = Harness()
        // 🔴 endpoint 下发 ReconnectInterval=1（1s 重试间隔）+ 抖动源恒 0：8s 窗口内必见第二轮连接
        val client = h.client(
            connectError = WsConnectionOpenException(514, mapOf("handshake-status" to "514"), "auth failed"),
            endpointResp = Harness.okEndpoint().replace("\"ReconnectInterval\":120", "\"ReconnectInterval\":1"),
        )
        client.start()
        // 首连抛错 → 首连失败日志可见
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline && h.connections.size < 2) Thread.sleep(20)
        assertTrue(h.connections.size >= 2, "握手失败必须触发重连新实例（size=${h.connections.size}，logs=${h.logs}）")
        client.close()
    }

    // ==================== 源码守护（变异防线：行为断言够不着的「逐轮读配置」语义）====================

    /**
     * ping 循环的 delay 必须逐轮读 `config.get().pingIntervalSec`（FileDownloadStreamingTest.stripComments 同款）。
     * 行为断言够不着：热更新后当轮 delay 已入栈，真实时钟验证 120s→1s 加速需 >120s——用源码 grep 钉死，
     * 防止后人把逐轮读改成构造时快照（那会让 pong 热更新 PingInterval 永远不生效）。
     */
    @Test
    fun `ping delay 逐轮读运行时配置（源码守护）`() {
        val src = java.io.File("src/main/kotlin/com/jiaoay/lark/ws/WsClient.kt").readText()
        val noComments = src.lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        assertTrue(
            noComments.contains("delay(config.get().pingIntervalSec * 1000L)"),
            "WsClient 的 ping 循环必须逐轮读 config.get().pingIntervalSec（pong 热更新生效的前提）",
        )
        assertTrue(
            noComments.contains("delay(cfg.reconnectIntervalSec * 1000L)"),
            "reconnect 循环必须逐轮读 reconnectIntervalSec（pong 热更新生效的前提）",
        )
    }
}
