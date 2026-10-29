package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * 握手 reader 假流单测：不依赖真机，ByteArray 流钉死三段读序（探活 / 64B 设备名 / 4B codec id）、
 * 隧道模式分型（forward 有探活重试段，reverse 无）、坏流防御与重试契约。
 */
class ScrcpyHandshakeTest {

    private fun deviceMeta(name: String): ByteArray {
        val buf = ByteArray(ScrcpyProtocol.DEVICE_NAME_FIELD_LENGTH)
        name.toByteArray(Charsets.UTF_8).copyInto(buf)
        return buf
    }

    private fun codecInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).putInt(value).array()

    /** forward 成功流：探活 0x00 + 64B 设备名 + codec 'h264' */
    private fun forwardStream(deviceName: String = "Pixel 8", codec: Int = ScrcpyProtocol.CODEC_H264): InputStream {
        val out = ByteArrayOutputStream()
        out.write(ScrcpyProtocol.PROBE_BYTE)
        out.write(deviceMeta(deviceName))
        out.write(codecInt(codec))
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `forward handshake reads probe device meta and codec id in order`() = runBlocking {
        val reader = ScrcpyHandshakeReader()
        val connection = reader.connectForward { forwardStream() }
        assertEquals("Pixel 8", connection.handshake.deviceName)
        assertEquals(ScrcpyProtocol.CODEC_H264, connection.handshake.codecId)
    }

    @Test
    fun `reverse handshake reads device meta and codec id without probe byte`() {
        val reader = ScrcpyHandshakeReader()
        // reverse 流无探活字节：直接从 64B 设备名起
        val out = ByteArrayOutputStream()
        out.write(deviceMeta("Maimo CarDisplay"))
        out.write(codecInt(ScrcpyProtocol.CODEC_H264))
        val handshake = reader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("Maimo CarDisplay", handshake.deviceName)
        assertEquals(ScrcpyProtocol.CODEC_H264, handshake.codecId)
    }

    @Test
    fun `probe retries on connect failure garbage byte and eof before success`() = runBlocking {
        val reader = ScrcpyHandshakeReader(ScrcpyHandshakeReader.ProbeRetryPolicy(maxAttempts = 5, retryDelayMs = 0))
        var attempts = 0
        val connection = reader.connectForward {
            when (attempts++) {
                0 -> null // 建连失败（adb 隧道后 server 未就绪）
                1 -> ByteArrayInputStream(byteArrayOf(0x7F)) // 垃圾探活字节，非 0x00
                2 -> ByteArrayInputStream(ByteArray(0)) // 探活读 EOF
                else -> forwardStream(deviceName = "Retried Device")
            }
        }
        assertEquals(4, attempts)
        assertEquals("Retried Device", connection.handshake.deviceName)
    }

    @Test
    fun `probe read exception retries within contract instead of format crash`() = runBlocking {
        // 读抛异常（连接被对端重置等）必须计入 lastFailure 走重试——不得折 String 落字节
        // 格式化分支（对 String 施 %02x 抛 IllegalFormatConversionException，击穿重试契约）
        val reader = ScrcpyHandshakeReader(ScrcpyHandshakeReader.ProbeRetryPolicy(maxAttempts = 4, retryDelayMs = 0))
        var attempts = 0
        val connection = reader.connectForward {
            attempts++
            if (attempts <= 2) {
                object : InputStream() {
                    override fun read(): Int = throw IOException("connection reset by peer")
                }
            } else {
                forwardStream(deviceName = "After Read Error")
            }
        }
        assertEquals(3, attempts)
        assertEquals("After Read Error", connection.handshake.deviceName)
    }

    @Test
    fun `probe read exception exhausts into IOException carrying last error`() = runBlocking {
        val reader = ScrcpyHandshakeReader(ScrcpyHandshakeReader.ProbeRetryPolicy(maxAttempts = 3, retryDelayMs = 0))
        val error = assertFailsWith<IOException> {
            reader.connectForward {
                object : InputStream() {
                    override fun read(): Int = throw IOException("connection reset by peer")
                }
            }
        }
        // 终态是人话真因（含异常消息），不是 IllegalFormatConversionException 密文
        assertTrue("probe read error" in error.message ?: "", "实际消息: ${error.message}")
        assertTrue("connection reset" in error.message ?: "")
    }

    @Test
    fun `total timeout gate caps probe retry budget`() = runTest {
        // 虚拟时间用例：重试预算 1000 次 × 100s 间隔量级，总闸 10s 到点即断——无总闸实现
        // 会吃满全部重试预算并以 attempts 耗尽 IOException 收场（本用例对无总闸实现即红）
        val reader = ScrcpyHandshakeReader(ScrcpyHandshakeReader.ProbeRetryPolicy(maxAttempts = 1_000, retryDelayMs = 100_000))
        var attempts = 0
        assertFailsWith<TimeoutCancellationException> {
            reader.connectForward(totalTimeoutMs = 10_000) { attempts++; null }
        }
        assertEquals(1, attempts, "总闸须在首个重试间隔内到点（虚拟时间 10s < 100s 间隔）")
    }

    @Test
    fun `probe retries exhaust into IOException with attempt count`() = runBlocking {
        val reader = ScrcpyHandshakeReader(ScrcpyHandshakeReader.ProbeRetryPolicy(maxAttempts = 3, retryDelayMs = 0))
        var attempts = 0
        val error = assertFailsWith<IOException> {
            reader.connectForward {
                attempts++
                ByteArrayInputStream(ByteArray(0))
            }
        }
        assertEquals(3, attempts)
        assertTrue("after 3 attempts" in error.message ?: "")
    }

    @Test
    fun `truncated device name fails with EOFException`() {
        val reader = ScrcpyHandshakeReader()
        val truncated = byteArrayOf(ScrcpyProtocol.PROBE_BYTE.toByte()) + ByteArray(30) { 0x41 }
        assertFailsWith<java.io.EOFException> {
            reader.read(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun `device name parses to first NUL with padding defense`() {
        val reader = ScrcpyHandshakeReader()
        // 尾字节强制 NUL 防御：64B 全 'x' 无 NUL 时解析取 63 字节
        val noNul = ByteArrayInputStream(ByteArray(64) { 0x78 } + codecInt(ScrcpyProtocol.CODEC_H264))
        assertEquals(63, reader.read(noNul).deviceName.length)
        // NUL 填充后的残渣不进名字
        val padded = deviceMeta("abc") // "abc" 后恒 NUL 填充
        val withGarbage = ByteArrayInputStream(padded + codecInt(ScrcpyProtocol.CODEC_H264))
        assertEquals("abc", reader.read(withGarbage).deviceName)
    }

    @Test
    fun `codec id slot surfaces disable stream codes`() {
        val reader = ScrcpyHandshakeReader()
        val disabled = reader.read(
            ByteArrayInputStream(deviceMeta("d") + codecInt(ScrcpyProtocol.STREAM_DISABLED)),
        )
        assertTrue(disabled.streamDisabled)
        val configError = reader.read(
            ByteArrayInputStream(deviceMeta("e") + codecInt(ScrcpyProtocol.STREAM_CONFIG_ERROR)),
        )
        assertTrue(configError.streamConfigError)
    }

    @Test
    fun `device socket name derives from scid hex`() {
        assertEquals("scrcpy_1a2b3c4d", ScrcpyProtocol.deviceSocketName(0x1A2B3C4D))
        assertEquals("scrcpy_00000001", ScrcpyProtocol.deviceSocketName(1L))
        assertEquals("scrcpy", ScrcpyProtocol.deviceSocketName(-1L))
    }

    @Test
    fun `full forward stream chains handshake into demuxer`() {
        // 端到端：一条 ByteArrayInputStream 承载 forward 全序（探活 → 设备名 → codec → 会话包 → config → 帧）
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0x64) // SPS 头两字节形态
        val frame = byteArrayOf(0, 0, 1, 0x65, 0x11)
        val out = ByteArrayOutputStream()
        out.write(ScrcpyProtocol.PROBE_BYTE)
        out.write(deviceMeta("Chain"))
        out.write(codecInt(ScrcpyProtocol.CODEC_H264))
        out.write(sessionPacket(width = 1920, height = 1080, clientResize = false))
        out.write(longBE(ScrcpyProtocol.FLAG_CONFIG) + intBE(csd.size) + csd)
        out.write(longBE(ScrcpyProtocol.FLAG_KEY_FRAME or 1000L) + intBE(frame.size) + frame)
        val input = ByteArrayInputStream(out.toByteArray())
        val reader = ScrcpyHandshakeReader()
        val probe = input.read()
        assertEquals(ScrcpyProtocol.PROBE_BYTE, probe)
        val handshake = reader.read(input)
        assertEquals("Chain", handshake.deviceName)
        val demuxer = ScrcpyDemuxer(input)
        val session = demuxer.next()
        assertTrue(session is ScrcpyStreamEvent.SessionMeta)
        session as ScrcpyStreamEvent.SessionMeta
        assertEquals(1920, session.width)
        assertEquals(1080, session.height)
        val config = demuxer.next()
        assertTrue(config is ScrcpyStreamEvent.Config)
        config as ScrcpyStreamEvent.Config
        assertTrue(csd.contentEquals(config.payload))
        val frameEvent = demuxer.next()
        assertTrue(frameEvent is ScrcpyStreamEvent.Frame)
        frameEvent as ScrcpyStreamEvent.Frame
        assertEquals(1000L, frameEvent.ptsUs)
        assertTrue(frameEvent.keyFrame)
        assertEquals(null, demuxer.next())
    }

    private fun sessionPacket(width: Int, height: Int, clientResize: Boolean): ByteArray {
        val flags = 0x80000000.toInt() or if (clientResize) ScrcpyProtocol.SESSION_FLAG_CLIENT_RESIZE else 0
        return intBE(flags) + intBE(width) + intBE(height)
    }

    private fun intBE(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun longBE(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
}
