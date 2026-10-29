// M4-T8 第一批 · observe 组测试（6/8）：桥接状态面（合并网关连接态 + 新增 Blocked/Draining）。
// 🔴 无 BC 行对应（BC-341..342=ProgressAccumulator、343..346=LogBus、347..352=SkillRegistry，均非状态面）；
//    本文件为新架构新增断言：D18（丢弃全计数）+ D19（Blocked 两源可区分）
//    + D20（Draining 是「已停收新消息、在途任务仍在跑」的中间态）。
// 🔴 旧 `model/BridgeState.kt` 只有「未连接/已连接」二态，无法区分「凭据错」与「被另一实例挡住」；
//    新架构合并 `GatewayConnectionState`（6 子类）并**新增两态**，穷尽 when 无 else ⇒ 网关加态即编译失败。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import com.jiaoay.lark.gateway.GatewayConnectionState
import com.jiaoay.lark.gateway.GatewayErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObserveStatusTest {

    /** 就绪态样本（`ConfirmRuntimeStatus.Ready` 带 port + scriptPath，供排障时手工执行脚本）。 */
    private val READY = ConfirmRuntimeStatus.Ready(port = 12_345, scriptPath = "/tmp/mcp-perm.sh")

    // ── BridgeConnectionStatus：合并网关 6 态 + 新增 2 态 ──

    @Test
    fun `网关连接态到桥接连接态的穷尽映射`() {
        assertEquals(BridgeConnectionStatus.Idle, GatewayConnectionState.Idle.toBridgeConnectionStatus())
        assertEquals(BridgeConnectionStatus.Connecting, GatewayConnectionState.Connecting.toBridgeConnectionStatus())
        assertEquals(BridgeConnectionStatus.Reconnecting, GatewayConnectionState.Reconnecting.toBridgeConnectionStatus())
        // Connected 带 bot 身份
        val connected = GatewayConnectionState.Connected("ou_bot", "小助手").toBridgeConnectionStatus()
        assertTrue(connected is BridgeConnectionStatus.Connected)
        assertEquals("ou_bot", connected.botOpenId)
        assertEquals("小助手", connected.botName)
        // botName 可空
        val noName = GatewayConnectionState.Connected("ou_bot").toBridgeConnectionStatus()
        assertNull((noName as BridgeConnectionStatus.Connected).botName)
        // Disconnected 保留 code + msg（SPI 类型，非 SDK 类型 ⇒ 不违反隔离纪律）
        val disconnected = GatewayConnectionState.Disconnected(GatewayErrorCode.ConnectFatal, "凭据错误").toBridgeConnectionStatus()
        assertTrue(disconnected is BridgeConnectionStatus.Disconnected)
        assertEquals(GatewayErrorCode.ConnectFatal, disconnected.code)
        assertEquals("凭据错误", disconnected.msg)
        // 🔴 D19：网关的「被同 appId 另一条长连接挡住」映射到新增 Blocked，原因是 PEER_INSTANCE
        val blocked = GatewayConnectionState.BlockedByPeerInstance.toBridgeConnectionStatus()
        assertTrue(blocked is BridgeConnectionStatus.Blocked)
        assertEquals(BlockedReason.PEER_INSTANCE, blocked.reason)
        assertTrue(blocked.detail.isNotBlank(), "Blocked 必须带可展示的原因文本")
        // 🔴 穷尽性证据：网关 6 子类**逐个**列出并全部映射成功（`toBridgeConnectionStatus` 是无 `else` 的
        //    穷尽 `when`，网关新增第 7 个子类时**编译器**会报错；lib 无 kotlin-reflect，故不用反射数子类）
        val all: List<GatewayConnectionState> = listOf(
            GatewayConnectionState.Idle,
            GatewayConnectionState.Connecting,
            GatewayConnectionState.Connected("ou_bot", "小助手"),
            GatewayConnectionState.Reconnecting,
            GatewayConnectionState.Disconnected(GatewayErrorCode.ConnectRetryable, "抖动"),
            GatewayConnectionState.BlockedByPeerInstance,
        )
        assertEquals(6, all.size)
        all.forEach { state -> assertNotNull(state.toBridgeConnectionStatus(), "$state 未被映射") }
    }

    @Test
    fun `isServing 只在 Connected 为真`() {
        assertTrue(BridgeConnectionStatus.Connected("ou").isServing)
        assertFalse(BridgeConnectionStatus.Idle.isServing)
        assertFalse(BridgeConnectionStatus.Connecting.isServing)
        assertFalse(BridgeConnectionStatus.Reconnecting.isServing)
        assertFalse(BridgeConnectionStatus.Disconnected(GatewayErrorCode.ConnectRetryable, "网络抖动").isServing)
        assertFalse(BridgeConnectionStatus.Blocked(BlockedReason.SINGLE_INSTANCE_LOCK).isServing)
        // 🔴 D20：排空中已停止收发 ⇒ 不算 serving（UI 不得显示「运行中」）
        assertFalse(BridgeConnectionStatus.Draining(3).isServing)
    }

    @Test
    fun `needsAttention 只在不可自愈态为真`() {
        // 🔴 Blocked 两源都算（D19：单实例锁与网关互斥是两个不同的阻断源）
        assertTrue(BridgeConnectionStatus.Blocked(BlockedReason.PEER_INSTANCE).needsAttention)
        assertTrue(BridgeConnectionStatus.Blocked(BlockedReason.SINGLE_INSTANCE_LOCK, "同机已有第二个进程").needsAttention)
        // Disconnected 里**仅** ConnectFatal 算（可重试码会自愈）
        assertTrue(BridgeConnectionStatus.Disconnected(GatewayErrorCode.ConnectFatal, "凭据错误").needsAttention)
        assertFalse(BridgeConnectionStatus.Disconnected(GatewayErrorCode.ConnectRetryable, "网络抖动").needsAttention)
        assertFalse(BridgeConnectionStatus.Idle.needsAttention)
        assertFalse(BridgeConnectionStatus.Connecting.needsAttention)
        assertFalse(BridgeConnectionStatus.Reconnecting.needsAttention)
        assertFalse(BridgeConnectionStatus.Connected("ou").needsAttention)
        assertFalse(BridgeConnectionStatus.Draining().needsAttention)
        assertEquals(2, BlockedReason.entries.size)
    }

    @Test
    fun `快照的 droppedTotal 汇总四处丢弃面且 serving 与 needsAttention 转发`() {
        val counters = PipelineCounters()
        // 造 1 处管线丢弃（重复消息）
        counters.recordDuplicateDropped()
        val base = BridgeStatusSnapshot(
            running = true,
            connection = BridgeConnectionStatus.Connected("ou_bot"),
            confirm = READY,
            counters = counters.snapshot(),
        )
        assertEquals(1L, base.counters.droppedTotal)
        assertEquals(1L, base.droppedTotal)
        assertTrue(base.serving)
        assertFalse(base.needsAttention)

        // 🔴 D18：单一可读指标 = 管线 + 落盘通道 + 落盘 sink + 日志台
        val full = base.copy(
            sinkDropped = 2L,
            auditSinkDropped = 3L,
            logDropped = 4L,
            auditWritten = 10L,
            statsWritten = 5L,
            logVersion = 99L,
            logSize = 42,
            liveProcesses = 1,
            sampledAtMillis = 1_700_000_000_000L,
        )
        assertEquals(1L + 2L + 3L + 4L, full.droppedTotal)
        assertEquals(10L, full.auditWritten)
        assertEquals(5L, full.statsWritten)
        assertEquals(99L, full.logVersion)
        assertEquals(42, full.logSize)
        assertEquals(1, full.liveProcesses)
        assertEquals(1_700_000_000_000L, full.sampledAtMillis)
        // data class 语义：全 val ⇒ 可安全跨线程传递（UI 只读快照，不持有 lib 对象）
        assertEquals(full, full.copy())
        // Draining 时 activeTasks 是 UI 的关键读数
        val draining = base.copy(connection = BridgeConnectionStatus.Draining(activeTasks = 7))
        assertEquals(7, (draining.connection as BridgeConnectionStatus.Draining).activeTasks)
        assertFalse(draining.serving)
        assertEquals(0, BridgeConnectionStatus.Draining().activeTasks)
    }

    @Test
    fun `采样器把全部数据源汇聚进快照`() {
        val counters = PipelineCounters()
        val channels = SinkChannels(capacity = 1)
        val bus = RingLogBus(capacity = 2)
        val sampler = MetricsSampler(
            counters = counters,
            sinkChannels = channels,
            logBus = bus,
            connectionProvider = { BridgeConnectionStatus.Connected("ou_bot", "小助手") },
            confirmProvider = { READY },
            runningProvider = { true },
            clock = { 5_000L },
        )
        // 造数据
        repeat(3) { channels.offerAudit(SinkRecord.Audit(
            ts = it.toLong(), chatId = "oc", senderOpenId = "ou", prompt = "p", command = "c",
            status = "SUCCESS", errorCode = null, exitCode = 0, elapsedMillis = 1L, backend = null,
        )) }
        repeat(4) { bus.publish(RingLogBus.LEVEL_INFO, "t", "m$it") }

        val snapshot = sampler.sample()
        assertTrue(snapshot.running)
        assertEquals(BridgeConnectionStatus.Connected("ou_bot", "小助手"), snapshot.connection)
        assertEquals(READY, snapshot.confirm)
        assertTrue(snapshot.serving)
        assertEquals(2L, snapshot.sinkDropped, "容量 1 投 3 条 ⇒ 丢 2")
        assertEquals(2L, snapshot.logDropped, "容量 2 投 4 条 ⇒ 丢 2")
        assertEquals(4L, snapshot.droppedTotal)
        assertEquals(2, snapshot.logSize)
        assertEquals(5_000L, snapshot.sampledAtMillis)
        assertTrue(snapshot.logVersion > 0L)
        // 🔴 latest 与最后一次 sample 一致（UI 首帧即可读）
        assertEquals(snapshot.droppedTotal, sampler.latest.droppedTotal)
    }
}
