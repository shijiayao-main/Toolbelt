package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 投屏录像面单测（fake 录像器注入，见 ScreencastTestFakes）：起录前置闸 / reset_video
 * 即时启动 / Pending→Active 起点关键帧 / 停止落盘帧数对账 / 未出帧显式失败 / 会话终态
 * 随收尾落盘 / config 变化分段 / 写帧失败不牵连画面面。真机全链路（ffprobe/播放）归
 * runbook 门控验收。
 */
class ScreencastRecordingTest {

    private suspend fun <T> kotlinx.coroutines.flow.StateFlow<T>.awaitUntil(
        timeoutMs: Long = 5_000,
        predicate: (T) -> Boolean,
    ): T = withTimeout(timeoutMs) { first { predicate(it) } }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /** 等待录像器累计写入 [count] 帧（pump 异步消费——直接停录会与在途帧竞态） */
    private fun awaitFrames(recorder: FakeRecorder, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && recorder.frames.size < count) Thread.sleep(10)
        assertEquals(count, recorder.frames.size, "录像器应在时限内累计 $count 帧")
    }

    /** 在解码器收包队列中等待首个以 [marker] 开头的载荷（排干积压后按标记确定性对账） */
    private fun awaitPacketPayload(harness: ScreencastHarness, marker: Byte): ByteArray? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val packet = harness.decoder.packets.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (packet.isNotEmpty() && packet[0] == marker) return packet
        }
        return null
    }

    // ── wire 构造（与 ScrcpyProtocol 同源，pts 可注入） ──

    private fun packetWithPts(flags: Long, ptsUs: Long, payload: ByteArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(ScrcpyProtocol.FRAME_HEADER_SIZE)
        buf.putLong(0, flags or ptsUs)
        buf.putInt(8, payload.size)
        return buf.array() + payload
    }

    private fun sendToServer(client: java.net.Socket, bytes: ByteArray) {
        client.getOutputStream().apply {
            write(bytes)
            flush()
        }
    }

    private suspend fun awaitStreaming(harness: ScreencastHarness): ScreencastStatus.Streaming =
        harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            as ScreencastStatus.Streaming

    // ── 起录闸 ──

    @Test
    fun `start recording without streaming session fails immediately`() = runBlocking {
        ScreencastHarness().use { harness ->
            val outcome = io { harness.engine.startRecording(harness.temporaryDir()) }
            assertTrue(outcome is ScreencastRecordingOutcome.Failed, "非投屏态起录应立即失败")
            assertTrue("仅投屏" in (outcome as ScreencastRecordingOutcome.Failed).reason)
            assertEquals(ScreencastRecordingState.Idle, harness.engine.recordingState.value)
        }
    }

    @Test
    fun `double record request is rejected explicitly`() = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            awaitStreaming(harness)
            val dir = harness.temporaryDir()
            assertNull(io { harness.engine.startRecording(dir) }, "首次起录应受理")
            val second = io { harness.engine.startRecording(dir) }
            assertTrue(second is ScreencastRecordingOutcome.Failed, "重复起录应显式拒绝")
        }
    }

    // ── 即时启动与起点关键帧 ──

    @Test
    fun `recording request sends reset video and activates on next keyframe`() = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            val streaming = awaitStreaming(harness)
            val dir = harness.temporaryDir()
            assertNull(io { harness.engine.startRecording(dir) })

            // 即时启动：reset_video（type 17，单字节无载荷）已上控制通道
            val control = io { harness.readControlBytes(1) }
            assertEquals(1, control.size)
            assertEquals(ScrcpyControlProtocol.TYPE_RESET_VIDEO.toByte(), control[0])

            // 起点关键帧：Pending → Active，起步素材 = 流上已缓存的 config + 会话宽高
            assertEquals(ScreencastRecordingState.Pending::class, harness.engine.recordingState.value::class)
            sendToServer(harness.client!!, packetWithPts(ScrcpyProtocol.FLAG_KEY_FRAME, 1_000L, byteArrayOf(9, 9)))
            val active = harness.engine.recordingState.awaitUntil { it is ScreencastRecordingState.Active }
                as ScreencastRecordingState.Active
            assertTrue(active.file.absolutePath.startsWith(dir.absolutePath), "产物应落在注入目录")
            assertEquals("mp4", active.file.extension)

            val recorder = harness.recorder
            assertContentEquals(byteArrayOf(1, 2, 3, 4), recorder.startedConfig)
            assertEquals(streaming.width, recorder.startedWidth)
            assertEquals(streaming.height, recorder.startedHeight)
            assertEquals(1_000L, recorder.frames.poll(5, TimeUnit.SECONDS)?.first, "demux pts 应原样进 mux")
        }
    }

    @Test
    fun `non keyframe packets keep pending until keyframe arrives`() = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            awaitStreaming(harness)
            assertNull(io { harness.engine.startRecording(harness.temporaryDir()) })
            // P 帧不触发起步（解码起点必须是关键帧，否则产物头部即花屏）
            sendToServer(harness.client!!, packetWithPts(0L, 2_000L, byteArrayOf(7)))
            sendToServer(harness.client!!, packetWithPts(0L, 3_000L, byteArrayOf(8)))
            assertEquals(0, harness.recorders.size, "P 帧不得启动录像")
            sendToServer(harness.client!!, packetWithPts(ScrcpyProtocol.FLAG_KEY_FRAME, 4_000L, byteArrayOf(9)))
            harness.engine.recordingState.awaitUntil { it is ScreencastRecordingState.Active }
            assertEquals(1, harness.recorders.size)
        }
    }

    // ── 停止落盘 ──

    @Test
    fun `stop recording finalizes with saved receipt and frame count`() = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            awaitStreaming(harness)
            assertNull(io { harness.engine.startRecording(harness.temporaryDir()) })
            sendToServer(harness.client!!, packetWithPts(ScrcpyProtocol.FLAG_KEY_FRAME, 1_000L, byteArrayOf(9)))
            harness.engine.recordingState.awaitUntil { it is ScreencastRecordingState.Active }
            sendToServer(harness.client!!, packetWithPts(0L, 2_000L, byteArrayOf(1)))
            sendToServer(harness.client!!, packetWithPts(0L, 3_000L, byteArrayOf(2)))
            awaitFrames(harness.recorder, 3)

            val outcome = io { harness.engine.stopRecording() }
            assertTrue(outcome is ScreencastRecordingOutcome.Saved, "停止应落 Saved 回执：$outcome")
            outcome as ScreencastRecordingOutcome.Saved
            assertEquals(3L, outcome.frameCount, "帧数应与 mux 写入数对账（关键帧 + 2 P 帧）")
            assertTrue(harness.recorder.finishCalled)
            assertEquals(ScreencastRecordingState.Idle, harness.engine.recordingState.value)
            assertEquals(outcome, harness.engine.recordingResult.value, "回执应持态在结果流")
            assertNull(io { harness.engine.stopRecording() }, "重复停止 = no-op")
        }
    }

    @Test
    fun `stop recording before any keyframe fails with no-frames receipt`() = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            awaitStreaming(harness)
            assertNull(io { harness.engine.startRecording(harness.temporaryDir()) })
            // 静止画面：不发任何帧（server 端语义——内容变化才出帧）
            val outcome = io { harness.engine.stopRecording() }
            assertTrue(outcome is ScreencastRecordingOutcome.Failed)
            assertTrue("未捕获到任何画面帧" in outcome.reason)
            assertEquals(ScreencastRecordingState.Idle, harness.engine.recordingState.value)
            assertEquals(0, harness.recorders.size, "未起步不得创建录像器实例")
        }
    }

    // ── 会话终态随收尾落盘 ──

    @Test
    fun `session stop finalizes active recording with saved receipt`() = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            awaitStreaming(harness)
            assertNull(io { harness.engine.startRecording(harness.temporaryDir()) })
            sendToServer(harness.client!!, packetWithPts(ScrcpyProtocol.FLAG_KEY_FRAME, 1_000L, byteArrayOf(9)))
            harness.engine.recordingState.awaitUntil { it is ScreencastRecordingState.Active }

            harness.engine.stop()
            val receipt = harness.engine.recordingResult.awaitUntil { it != null }
            assertTrue(receipt is ScreencastRecordingOutcome.Saved, "会话停止应在录录像随收尾落盘：$receipt")
            assertEquals(ScreencastRecordingState.Idle, harness.engine.recordingState.value)
        }
    }

    @Test
    fun `session stop while pending fails recording explicitly`() = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            awaitStreaming(harness)
            assertNull(io { harness.engine.startRecording(harness.temporaryDir()) })
            harness.engine.stop()
            val receipt = harness.engine.recordingResult.awaitUntil { it != null }
            assertTrue(receipt is ScreencastRecordingOutcome.Failed, "Pending 会话收尾 = 未捕获帧失败回执")
        }
    }

    // ── config 变化分段 ──

    @Test
    fun `config change mid recording splits the file with note`() = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            awaitStreaming(harness)
            assertNull(io { harness.engine.startRecording(harness.temporaryDir()) })
            sendToServer(harness.client!!, packetWithPts(ScrcpyProtocol.FLAG_KEY_FRAME, 1_000L, byteArrayOf(9)))
            harness.engine.recordingState.awaitUntil { it is ScreencastRecordingState.Active }

            // 新 config（字节不等 = 编码器重配/分辨率变化）→ 旧文件分段收尾
            sendToServer(harness.client!!, packetWithPts(ScrcpyProtocol.FLAG_CONFIG, 0L, byteArrayOf(9, 9, 9, 9)))
            val receipt = harness.engine.recordingResult.awaitUntil { it != null }
            assertTrue(receipt is ScreencastRecordingOutcome.Saved, "分段收尾应 Saved：$receipt")
            assertNotNull(receipt.note, "分段收尾回执应带如实注记")
            assertEquals(ScreencastRecordingState.Idle, harness.engine.recordingState.value)

            // 分段后画面面继续：后续帧仍到解码器（录像面收口不牵连画面面）
            sendToServer(harness.client!!, packetWithPts(ScrcpyProtocol.FLAG_KEY_FRAME, 5_000L, byteArrayOf(0x55)))
            val next = awaitPacketPayload(harness, 0x55)
            assertNotNull(next, "录像收口后流应继续到解码器")
            assertEquals(1, harness.recorders.size, "分段后不得自动重启录像")
        }
    }

    // ── 写帧失败面 ──

    @Test
    fun `frame write failure fails recording face and keeps streaming`() = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            awaitStreaming(harness)
            assertNull(io { harness.engine.startRecording(harness.temporaryDir()) })
            sendToServer(harness.client!!, packetWithPts(ScrcpyProtocol.FLAG_KEY_FRAME, 1_000L, byteArrayOf(9)))
            harness.engine.recordingState.awaitUntil { it is ScreencastRecordingState.Active }
            harness.recorder.failOnFrame = IOException("disk full")

            sendToServer(harness.client!!, packetWithPts(0L, 2_000L, byteArrayOf(1)))
            val receipt = harness.engine.recordingResult.awaitUntil { it != null }
            assertTrue(receipt is ScreencastRecordingOutcome.Saved, "写帧失败应收口抢救已录部分：$receipt")
            assertEquals(1L, receipt.frameCount, "失败帧不入产物，帧数 = 已成功写入数")
            assertNotNull(receipt.note, "中断抢救回执应带如实注记")

            // 画面面不受牵连：会话仍在 Streaming，后续帧照常到解码器
            sendToServer(harness.client!!, packetWithPts(0L, 3_000L, byteArrayOf(0x66)))
            val next = awaitPacketPayload(harness, 0x66)
            assertNotNull(next, "录像面失败后画面面应继续")
            assertTrue(harness.engine.status.value is ScreencastStatus.Streaming)
        }
    }

    private fun ScreencastHarness.temporaryDir(): File =
        kotlin.io.path.createTempDirectory("screencast-recording-test").toFile()
}
