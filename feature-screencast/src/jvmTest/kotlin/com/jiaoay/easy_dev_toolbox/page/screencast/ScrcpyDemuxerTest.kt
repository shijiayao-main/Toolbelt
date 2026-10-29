package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * demuxer 假流单测：ByteArray 钉死 12B 帧头判型（会话包 / config / 帧）、flags 位语义、
 * pts 掩码、坏流防御（size ≤ 0、包中途截断、干净 EOF）与禁流双码消费语义。
 */
class ScrcpyDemuxerTest {

    private fun intBE(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun longBE(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()

    private fun stream(vararg chunks: ByteArray): InputStream =
        ByteArrayInputStream(chunks.reduce { acc, bytes -> acc + bytes })

    private fun sessionPacket(width: Int, height: Int, clientResize: Boolean): ByteArray {
        val flags = 0x80000000.toInt() or if (clientResize) ScrcpyProtocol.SESSION_FLAG_CLIENT_RESIZE else 0
        return intBE(flags) + intBE(width) + intBE(height)
    }

    private fun packet(ptsFlags: Long, payload: ByteArray): ByteArray =
        longBE(ptsFlags) + intBE(payload.size) + payload

    @Test
    fun `session packet parses width height and client resize flag from header slot itself`() {
        val demuxer = ScrcpyDemuxer(stream(sessionPacket(1920, 1080, clientResize = false)))
        val event = demuxer.next()
        assertTrue(event is ScrcpyStreamEvent.SessionMeta)
        event as ScrcpyStreamEvent.SessionMeta
        assertEquals(1920, event.width)
        assertEquals(1080, event.height)
        assertEquals(false, event.isClientResize)

        val resized = ScrcpyDemuxer(stream(sessionPacket(800, 600, clientResize = true))).next()
        assertTrue(resized is ScrcpyStreamEvent.SessionMeta)
        assertEquals(true, (resized as ScrcpyStreamEvent.SessionMeta).isClientResize)
    }

    @Test
    fun `session packet is consumed without extra payload bytes`() {
        // 会话包后紧跟一帧：会话包消费后下一事件必须是该帧（若多读 1 字节即错位）
        val frame = byteArrayOf(1, 2, 3)
        val demuxer = ScrcpyDemuxer(stream(sessionPacket(640, 480, false), packet(5L, frame)))
        assertTrue(demuxer.next() is ScrcpyStreamEvent.SessionMeta)
        val frameEvent = demuxer.next()
        assertTrue(frameEvent is ScrcpyStreamEvent.Frame)
        assertTrue(frame.contentEquals((frameEvent as ScrcpyStreamEvent.Frame).payload))
    }

    @Test
    fun `config packet carries csd payload with config flag`() {
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x1f)
        val demuxer = ScrcpyDemuxer(stream(packet(ScrcpyProtocol.FLAG_CONFIG, csd)))
        val event = demuxer.next()
        assertTrue(event is ScrcpyStreamEvent.Config)
        assertTrue(csd.contentEquals((event as ScrcpyStreamEvent.Config).payload))
    }

    @Test
    fun `frame extracts pts keyframe and payload under pts mask`() {
        // pts 取低 61 位；KEY_FRAME 位置位不污染 pts；再验无 keyframe 形态
        val pts = 0x1FFF_FFFF_FFFF_FFFFL // 61 位全 1
        val payload = ByteArray(9) { it.toByte() }
        val demuxer = ScrcpyDemuxer(stream(packet(ScrcpyProtocol.FLAG_KEY_FRAME or pts, payload)))
        val keyEvent = demuxer.next() as ScrcpyStreamEvent.Frame
        assertEquals(pts, keyEvent.ptsUs)
        assertEquals(true, keyEvent.keyFrame)
        assertTrue(payload.contentEquals(keyEvent.payload))

        val plain = ScrcpyDemuxer(stream(packet(42L, byteArrayOf(9)))).next() as ScrcpyStreamEvent.Frame
        assertEquals(42L, plain.ptsUs)
        assertEquals(false, plain.keyFrame)
    }

    @Test
    fun `stream order session then config then frames`() {
        val csd = byteArrayOf(0x67, 0x64)
        val f1 = byteArrayOf(0x65)
        val f2 = byteArrayOf(0x41, 0x42)
        val demuxer = ScrcpyDemuxer(
            stream(
                sessionPacket(2560, 1440, false),
                packet(ScrcpyProtocol.FLAG_CONFIG, csd),
                packet(ScrcpyProtocol.FLAG_KEY_FRAME or 10L, f1),
                packet(20L, f2),
            ),
        )
        assertTrue(demuxer.next() is ScrcpyStreamEvent.SessionMeta)
        assertTrue(demuxer.next() is ScrcpyStreamEvent.Config)
        val firstFrame = demuxer.next() as ScrcpyStreamEvent.Frame
        assertTrue(firstFrame.keyFrame)
        assertEquals(10L, firstFrame.ptsUs)
        val secondFrame = demuxer.next() as ScrcpyStreamEvent.Frame
        assertEquals(false, secondFrame.keyFrame)
        assertEquals(20L, secondFrame.ptsUs)
        assertEquals(null, demuxer.next())
    }

    @Test
    fun `zero or negative packet size fails with IOException`() {
        val zero = ScrcpyDemuxer(stream(longBE(0L) + intBE(0)))
        assertFailsWith<java.io.IOException> { zero.next() }
        val negative = ScrcpyDemuxer(stream(longBE(0L) + intBE(-1)))
        assertFailsWith<java.io.IOException> { negative.next() }
    }

    @Test
    fun `truncated payload fails with EOFException`() {
        // 帧头声明 10 字节载荷，流只给 3 字节
        val truncated = longBE(0L) + intBE(10) + byteArrayOf(1, 2, 3)
        val demuxer = ScrcpyDemuxer(ByteArrayInputStream(truncated))
        assertFailsWith<java.io.EOFException> { demuxer.next() }
    }

    @Test
    fun `truncated header fails with EOFException`() {
        val demuxer = ScrcpyDemuxer(ByteArrayInputStream(longBE(0L))) // 只给 8B 不足 12B 帧头
        assertFailsWith<java.io.EOFException> { demuxer.next() }
    }

    @Test
    fun `clean eof returns null`() {
        val demuxer = ScrcpyDemuxer(ByteArrayInputStream(ByteArray(0)))
        assertEquals(null, demuxer.next())
    }

    @Test
    fun `disable stream codes map to non fatal and fatal branches`() {
        // 禁流码出现在 codec id 槽位（握手段消费），demuxer 侧不触达——这里钉语义判定供调用方复用
        assertTrue(ScrcpyHandshake("d", ScrcpyProtocol.STREAM_DISABLED).streamDisabled)
        assertTrue(ScrcpyHandshake("e", ScrcpyProtocol.STREAM_CONFIG_ERROR).streamConfigError)
        assertTrue(!ScrcpyHandshake("ok", ScrcpyProtocol.CODEC_H264).streamDisabled)
    }

    @Test
    fun `large frame round trip keeps byte identity`() {
        val payload = ByteArray(256 * 1024) { (it * 31).toByte() }
        val demuxer = ScrcpyDemuxer(stream(packet(ScrcpyProtocol.FLAG_KEY_FRAME or 99L, payload)))
        val frame = demuxer.next() as ScrcpyStreamEvent.Frame
        assertTrue(payload.contentEquals(frame.payload))
        assertEquals(99L, frame.ptsUs)
    }

    @Test
    fun `frame payload bytes are annex-b start code prefixed in real capture shape`() {
        // 真实 MediaCodec 码流以 0001 起始码开头（0x00000001 = 4 字节大端 1）——形态校验防误读
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65)
        val demuxer = ScrcpyDemuxer(stream(packet(1L, annexB)))
        val frame = demuxer.next() as ScrcpyStreamEvent.Frame
        assertTrue(annexB.contentEquals(frame.payload))
        val out = ByteArrayOutputStream()
        frame.payload.forEach { out.write(it.toInt()) }
        assertTrue(annexB.contentEquals(out.toByteArray()))
    }

    @Test
    fun `local socket close mid-read terminates with null instead of throwing`() {
        // 终止契约：停止方关 socket（用户 stop / 连接消亡）令阻塞读抛 SocketException——
        // 这是终止信号非坏流，next() 必须归 null，不得向上抛
        val listener = java.net.ServerSocket(0)
        val port = listener.localPort
        val client = java.net.Socket("127.0.0.1", port)
        val serverSide = listener.accept()
        client.getOutputStream().apply {
            write(sessionMetaBytesForTest())
            flush()
        }
        val demuxer = ScrcpyDemuxer(serverSide.getInputStream())
        // 会话包先正常消费（证明读路径活着）
        assertTrue(demuxer.next() is ScrcpyStreamEvent.SessionMeta)
        // 后台线程阻塞在下一包的读上；主线程关本端 socket → SocketException → null
        val result = java.util.concurrent.CompletableFuture.supplyAsync<Exception?> {
            try {
                demuxer.next()
                null
            } catch (e: Exception) {
                e
            }
        }
        // 等读阻塞就位再关本端 socket（无同步原语可见读阻塞，短睡妥协）
        Thread.sleep(100)
        serverSide.close()
        val thrown = result.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(thrown == null, "SocketException 终止必须归 null，实际抛出: $thrown")
        runCatching { client.close() }
        runCatching { listener.close() }
    }

    private fun sessionMetaBytesForTest(): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(ScrcpyProtocol.FRAME_HEADER_SIZE)
        buf.put(0, 0x80.toByte())
        buf.putInt(4, 1920)
        buf.putInt(8, 1080)
        return buf.array()
    }
}
