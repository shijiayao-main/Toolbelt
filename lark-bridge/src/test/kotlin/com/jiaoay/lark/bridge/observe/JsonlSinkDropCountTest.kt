// M4-T8 第一批 · observe 组测试（2/8）：🔴 NF-09「DROP_OLDEST 丢弃必须计数可观测」。
// 方案「有界内存链路 8 行」表的硬要求：任何一处丢弃（通道溢出 / IO 失败 / 编码类型不匹配 /
// 日志台环形覆盖）都必须有**可读计数器**，且能被 1Hz 采样汇聚成单一指标。
// 🔴 BC 对应：NF-09 + BC-467..BC-471（Idle 零资源）+ D18（丢弃点全计数）。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 记录 warn 的日志替身（验证「丢弃必有 WARN」）。 */
private class RecordingLog : BridgeLog {
    val lines = mutableListOf<String>()
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String, error: Throwable?) {
        lines.add(message)
    }

    override fun error(message: String, error: Throwable?) {
        lines.add(message)
    }
}

class JsonlSinkDropCountTest {

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    private fun audit(ts: Long) = SinkRecord.Audit(
        ts = ts, chatId = "oc", senderOpenId = "ou", prompt = "p", command = "c",
        status = "SUCCESS", errorCode = null, exitCode = 0, elapsedMillis = 1L, backend = null,
    )

    private fun stat(ts: Long) = SinkRecord.SenderStat(
        ts = ts, senderOpenId = "ou", userName = "", chatId = "oc",
        type = SinkRecord.TYPE_MESSAGE, contentSummary = "c",
    )

    // ── 第 4 行链路：通道 DROP_OLDEST 溢出 ──

    @Test
    fun `通道溢出丢弃被计数并 WARN`() {
        val log = RecordingLog()
        val channels = SinkChannels(capacity = 4, log = log)
        assertEquals(4, channels.capacity)
        // 投 10 条、不消费 ⇒ 后 6 条挤掉最旧的 6 条
        repeat(10) { assertTrue(channels.offerAudit(audit(it.toLong())) || it >= 4) }
        assertEquals(6L, channels.auditDropped, "DROP_OLDEST 溢出必须计数（NF-09）")
        assertEquals(0L, channels.statsDropped, "两条通道计数互相独立")
        assertEquals(6L, channels.droppedRecords)
        assertEquals(10L, channels.auditOfferedCount)
        // 🔴 每次溢出都 WARN（用户必须能在日志台看见「审计被丢了」）
        assertEquals(6, log.lines.size)
        assertTrue(log.lines.all { it.contains("audit-sink") }, "WARN 必须带 where 字面值：${log.lines}")
        assertTrue(log.lines.all { it.contains("droppedTotal") })
        // 🔴 offerAudit 的返回值就是「本次是否触发丢弃」（调用方可直接判）
        val fresh = SinkChannels(capacity = 2)
        assertTrue(fresh.offerAudit(audit(1)))
        assertTrue(fresh.offerAudit(audit(2)))
        assertFalse(fresh.offerAudit(audit(3)), "第 3 条挤掉最旧 ⇒ 返回 false")
        assertEquals(1L, fresh.auditDropped)
    }

    @Test
    fun `通道容量下限为 1 且默认 8192`() {
        assertEquals(8192, SinkChannels.CAPACITY)
        assertEquals(1, SinkChannels(capacity = 0).capacity)
        assertEquals(1, SinkChannels(capacity = -5).capacity)
        assertEquals("audit-sink", SinkChannels.AUDIT_WHERE)
        assertEquals("stats-sink", SinkChannels.STATS_WHERE)
    }

    @Test
    fun `统计通道溢出同样计数`() {
        val channels = SinkChannels(capacity = 2)
        repeat(5) { channels.offerStats(stat(it.toLong())) }
        assertEquals(3L, channels.statsDropped)
        assertEquals(0L, channels.auditDropped)
        assertEquals(3L, channels.droppedRecords)
    }

    @Test
    fun `消费后在途计数归零且不再误报溢出`() {
        runBlocking {
            val channels = SinkChannels(capacity = 2)
            channels.offerAudit(audit(1))
            channels.offerAudit(audit(2))
            assertEquals(2L, channels.auditPending)
            assertEquals(0L, channels.auditDropped)
            assertEquals(1L, (channels.receiveAudit() as? SinkRecord.Audit)?.ts)
            assertEquals(1L, channels.auditPending)
            // 消费掉一条后又有空位 ⇒ 第 3 条不触发丢弃
            assertTrue(channels.offerAudit(audit(3)))
            assertEquals(0L, channels.auditDropped, "已消费腾出的空位不得被算成溢出")
            channels.close()
            // 🔴 契约：close 且排空后 receiveAudit 返回 null（sink 据此正常收尾，D20）
            assertEquals(2L, (channels.receiveAudit() as? SinkRecord.Audit)?.ts)
            assertEquals(3L, (channels.receiveAudit() as? SinkRecord.Audit)?.ts)
            assertEquals(null, channels.receiveAudit())
            assertEquals(null, channels.receiveAudit(), "已排空后重复调用仍返回 null（幂等）")
            channels.close() // 幂等
        }
    }

    // ── sink 侧两类丢弃 ──

    @Test
    fun `编码类型不匹配计入 typeMismatchDropped 而非静默丢弃`() {
        runBlocking {
            val dir = tempDir("mismatch")
            val channels = SinkChannels()
            // 🔴 故意把统计记录投进**审计**通道（接线错误的模拟）
            channels.offerAudit(stat(1))
            channels.offerAudit(audit(2))
            channels.close()
            val sink = JsonlSink.audit(channels, dir.path)
            sink.run()
            assertEquals(1L, sink.typeMismatchDropped, "类型不匹配必须计数（D18）")
            assertEquals(1L, sink.writtenTotal)
            assertEquals(0L, sink.writeDropped)
            assertEquals(1L, sink.droppedTotal)
            // 只有匹配的那条落盘
            val lines = File(dir, AuditRecords.FILE_NAME).readLines().filter { it.isNotBlank() }
            assertEquals(1, lines.size)
            assertEquals(2L, com.jiaoay.lark.bridge.config.AuditJson
                .decodeFromString(AuditEntryJson.serializer(), lines.single()).ts)
        }
    }

    @Test
    fun `IO 失败计入 writeDropped 与 writeFailures`() {
        runBlocking {
            val fileAsDir = File(tempDir("io"), "not-a-dir").apply { parentFile.mkdirs(); createNewFile() }
            val log = RecordingLog()
            val channels = SinkChannels()
            repeat(3) { channels.offerAudit(audit(it.toLong())) }
            channels.close()
            val sink = JsonlSink.audit(channels, fileAsDir.path, log = log)
            sink.run() // 🔴 恒不抛
            assertEquals(0L, sink.writtenTotal)
            assertEquals(3L, sink.writeDropped, "IO 失败的每一条都必须计数")
            assertEquals(3L, sink.droppedTotal)
            assertTrue(sink.writeFailures >= 1L)
            assertTrue(log.lines.isNotEmpty(), "IO 失败必须 WARN")
        }
    }

    @Test
    fun `禁用态下 droppedTotal 与 enabled 一致可观测`() {
        runBlocking {
            val channels = SinkChannels()
            channels.offerAudit(audit(1))
            channels.close()
            val sink = JsonlSink.audit(channels, "")
            sink.run()
            assertFalse(sink.enabled)
            assertEquals(1L, sink.writeDropped)
            assertEquals(1L, sink.droppedTotal)
            assertEquals(0L, sink.writtenTotal)
            assertTrue(sink.finished)
        }
    }

    // ── 第 5 行链路：日志台环形覆盖 ──

    @Test
    fun `日志台环形覆盖计入 droppedTotal`() {
        val bus = RingLogBus(capacity = 3)
        repeat(3) { bus.publish(RingLogBus.LEVEL_INFO, "t", "m$it") }
        assertEquals(0L, bus.droppedTotal, "未满时覆盖计数为 0")
        assertEquals(3, bus.size)
        repeat(2) { bus.publish(RingLogBus.LEVEL_INFO, "t", "over$it") }
        assertEquals(2L, bus.droppedTotal, "🔴 覆盖最旧 = 丢弃，必须计数（D18）")
        assertEquals(3, bus.size, "环形容量恒定")
        assertEquals(5L, bus.publishedTotal)
        assertEquals(listOf("m2", "over0", "over1"), bus.snapshot().map { it.message })
    }

    // ── 汇聚：1Hz 采样把丢弃面加总 ──

    @Test
    fun `采样把全部丢弃面汇聚成单一 droppedTotal 并只在增量时 WARN`() {
        runBlocking {
            val log = RecordingLog()
            val counters = PipelineCounters()
            val channels = SinkChannels(capacity = 1)
            val bus = RingLogBus(capacity = 1)
            val sampler = MetricsSampler(
                counters = counters,
                sinkChannels = channels,
                logBus = bus,
                intervalMillis = 10L,
                log = log,
            )
            // 构造时即采一次（UI 首帧不必等 1s）
            assertEquals(0L, sampler.latest.droppedTotal)
            assertEquals(0L, sampler.dropWarningsTotal)

            // 制造 3 处丢弃：通道溢出 2 + 日志台覆盖 1
            repeat(3) { channels.offerAudit(audit(it.toLong())) }
            bus.publish(RingLogBus.LEVEL_INFO, "t", "a")
            bus.publish(RingLogBus.LEVEL_INFO, "t", "b")
            val snapshot = sampler.sample(atMillis = 123L)
            assertEquals(2L, snapshot.sinkDropped)
            assertEquals(1L, snapshot.logDropped)
            assertEquals(3L, snapshot.droppedTotal, "🔴 D18 单一可读指标")
            assertEquals(123L, snapshot.sampledAtMillis)
            assertEquals(1L, sampler.dropWarningsTotal)
            assertEquals(1, log.lines.count { it.contains("丢弃增量") })
            assertTrue(log.lines.first().contains("delta=3"), "WARN 必须报**增量**：${log.lines}")

            // 🔴 同一丢弃量再采样 ⇒ **不重复** WARN（否则 1Hz 会刷屏）
            sampler.sample()
            sampler.sample()
            assertEquals(1L, sampler.dropWarningsTotal)
            assertEquals(1, log.lines.count { it.contains("丢弃增量") })

            // 新增丢弃 ⇒ 再报一次，且 delta 只算增量
            bus.publish(RingLogBus.LEVEL_INFO, "t", "c")
            val second = sampler.sample()
            assertEquals(4L, second.droppedTotal)
            assertEquals(2L, sampler.dropWarningsTotal)
            assertTrue(log.lines.last().contains("delta=1"), "delta 必须是增量而非总量：${log.lines.last()}")

            // runLoop 在 1Hz（此处 10ms）下持续采样且可被取消
            val before = sampler.samplesTotal
            val job = launch { sampler.runLoop() }
            delay(60)
            job.cancel()
            job.join()
            assertTrue(sampler.samplesTotal > before, "runLoop 必须持续采样")
            assertEquals(10L, sampler.interval)
        }
    }

    @Test
    fun `采样器在全部数据源缺失时仍产出可用快照`() {
        val sampler = MetricsSampler(counters = PipelineCounters())
        val snapshot = sampler.sample()
        assertFalse(snapshot.running)
        assertEquals(BridgeConnectionStatus.Idle, snapshot.connection)
        assertEquals(com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus.Disabled, snapshot.confirm)
        assertEquals(0L, snapshot.droppedTotal)
        assertEquals(0, snapshot.liveProcesses)
        assertFalse(snapshot.serving)
        assertFalse(snapshot.needsAttention)
        // 🔴 构造时已采一次（UI 首帧不必等 1s），再显式 sample() 一次 ⇒ 2
        assertEquals(1L, MetricsSampler(counters = PipelineCounters()).samplesTotal)
        assertEquals(2L, sampler.samplesTotal)
        // 🔴 采样间隔下限（防误配 0 造成忙轮询）
        assertEquals(1_000L, MetricsSampler.DEFAULT_INTERVAL_MILLIS)
        assertEquals(50L, MetricsSampler.MIN_INTERVAL_MILLIS)
    }

    @Test
    fun `采样恒不抛（坏数据源只让对应字段退化）`() {
        val broken = MetricsSampler(
            counters = PipelineCounters(),
            connectionProvider = { throw IllegalStateException("网关已释放") },
            confirmProvider = { throw RuntimeException("boom") },
            runningProvider = { throw Error("worse") },
        )
        val snapshot = broken.sample() // 🔴 不得抛（含构造期的首采样）
        assertEquals(BridgeConnectionStatus.Idle, snapshot.connection)
        assertFalse(snapshot.running)
        assertEquals(2L, broken.samplesTotal)
    }
}
