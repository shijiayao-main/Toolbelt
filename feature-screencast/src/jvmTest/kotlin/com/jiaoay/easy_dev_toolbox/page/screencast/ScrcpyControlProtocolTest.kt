package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 控制消息编码逐字节断言 + 设备消息读回断言。编码真源 = 官方客户端
 * `app/src/control_msg.c` 的 sc_control_msg_serialize 字段序（服务端
 * ControlMessageReader 同序消费）——错序在协议上静默失效（服务端无逐消息回执），
 * 故每型以手工构造的期望字节阵逐字节钉死。
 */
class ScrcpyControlProtocolTest {

    /** 期望字节阵的构造 DSL（大端，与 DataOutputStream 写序一致） */
    private fun expected(build: DataOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(build)
        return buffer.toByteArray()
    }

    @Test
    fun `keycode message is 14 bytes in official field order`() {
        val actual = ScrcpyControlEncoder.injectKeycode(
            action = ScrcpyControlProtocol.KEY_ACTION_DOWN,
            keycode = 24, // KEYCODE_VOLUME_UP
            repeat = 0,
            metaState = 0,
        )
        val expected = expected {
            writeByte(ScrcpyControlProtocol.TYPE_INJECT_KEYCODE) // 型别
            writeByte(0) // action DOWN
            writeInt(24) // keycode
            writeInt(0) // repeat
            writeInt(0) // metaState
        }
        assertEquals(14, actual.size)
        assertContentEquals(expected, actual)
    }

    @Test
    fun `text message carries 4-byte length and utf-8 payload`() {
        val actual = ScrcpyControlEncoder.injectText("héllo→")
        val payload = "héllo→".toByteArray(Charsets.UTF_8)
        val expected = expected {
            writeByte(ScrcpyControlProtocol.TYPE_INJECT_TEXT)
            writeInt(payload.size)
            write(payload)
        }
        assertContentEquals(expected, actual)
    }

    @Test
    fun `text message truncates over-long payload at utf-8 boundary`() {
        // 2 字节字符 × 151 = 302 字节 > 300 上限——按字符边界截到 300（150 字符整）
        val text = "é".repeat(151)
        val actual = ScrcpyControlEncoder.injectText(text)
        val input = ByteArrayInputStream(actual)
        val data = java.io.DataInputStream(input)
        assertEquals(ScrcpyControlProtocol.TYPE_INJECT_TEXT, data.readUnsignedByte())
        val length = data.readInt()
        assertEquals(ScrcpyControlProtocol.INJECT_TEXT_MAX_BYTES, length)
        val payload = ByteArray(length)
        data.readFully(payload)
        // 截断必须落在字符边界（不是合法 UTF-8 解码的拦腰截断即红）
        assertEquals("é".repeat(150), String(payload, Charsets.UTF_8))
        assertEquals(-1, data.read(), "截断后载荷不得有尾巴")
    }

    @Test
    fun `touch message is 32 bytes with generic finger id and fixed-point pressure`() {
        val down = ScrcpyControlEncoder.injectTouch(
            action = ScrcpyControlProtocol.MOTION_ACTION_DOWN,
            pointerId = ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER,
            x = 100,
            y = 200,
            screenWidth = 2880,
            screenHeight = 1620,
            pressure = 1f,
        )
        val expectedDown = expected {
            writeByte(ScrcpyControlProtocol.TYPE_INJECT_TOUCH_EVENT)
            writeByte(0) // action DOWN
            writeLong(-2L) // generic finger（u64 位形态 0xFFFF...FE）
            writeInt(100)
            writeInt(200)
            writeShort(2880) // 屏宽 u16
            writeShort(1620) // 屏高 u16
            writeShort(0xffff) // 压力 1.0 → u16 定点收口值
            writeInt(0) // actionButton
            writeInt(0) // buttons
        }
        assertEquals(32, down.size)
        assertContentEquals(expectedDown, down)

        // UP 事件压力 0.0 → 0x0000（官方客户端 input_manager 同款：up 恒零压）
        val up = ScrcpyControlEncoder.injectTouch(
            action = ScrcpyControlProtocol.MOTION_ACTION_UP,
            pointerId = ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER,
            x = 100,
            y = 200,
            screenWidth = 2880,
            screenHeight = 1620,
            pressure = 0f,
        )
        assertEquals(0x0000, ((up[22].toInt() and 0xFF) shl 8) or (up[23].toInt() and 0xFF))
    }

    @Test
    fun `scroll message is 21 bytes with wire-range normalized fixed-point deltas`() {
        val actual = ScrcpyControlEncoder.injectScroll(
            x = 50,
            y = 60,
            screenWidth = 2880,
            screenHeight = 1620,
            hScroll = 0f,
            vScroll = 1f, // 一个滚轮向上的刻度
        )
        val expected = expected {
            writeByte(ScrcpyControlProtocol.TYPE_INJECT_SCROLL_EVENT)
            writeInt(50)
            writeInt(60)
            writeShort(2880)
            writeShort(1620)
            // hScroll=0 → 0x0000；vScroll=1 归一 1/16 → i16 定点 0x0800（对端解码 ×16 还原 1.0）
            writeShort(0x0000)
            writeShort(0x0800)
            writeInt(0) // buttons
        }
        assertEquals(21, actual.size)
        assertContentEquals(expected, actual)
    }

    @Test
    fun `scroll message clamps out-of-range deltas to wire range`() {
        // 超值域输入按 ±16 收口（对端还原恒 ≤ ±16，不产溢出截断）
        val actual = ScrcpyControlEncoder.injectScroll(
            x = 0,
            y = 0,
            screenWidth = 100,
            screenHeight = 100,
            hScroll = -100f,
            vScroll = 100f,
        )
        val hWord = ((actual[13].toInt() and 0xFF) shl 8) or (actual[14].toInt() and 0xFF)
        val vWord = ((actual[15].toInt() and 0xFF) shl 8) or (actual[16].toInt() and 0xFF)
        assertEquals(-0x8000, hWord.toShort().toInt(), "超下界收口到 i16 最小定点")
        assertEquals(0x7fff, vWord.toShort().toInt(), "超上界收口到 i16 最大定点")
    }

    @Test
    fun `get clipboard request is type byte plus none copy key`() {
        assertContentEquals(
            byteArrayOf(ScrcpyControlProtocol.TYPE_GET_CLIPBOARD.toByte(), 0),
            ScrcpyControlEncoder.getClipboard(),
        )
    }

    @Test
    fun `reset video is single type byte without payload`() {
        assertContentEquals(
            byteArrayOf(ScrcpyControlProtocol.TYPE_RESET_VIDEO.toByte()),
            ScrcpyControlEncoder.resetVideo(),
        )
    }

    @Test
    fun `set clipboard message carries sequence paste flag and length-prefixed utf-8`() {
        val actual = ScrcpyControlEncoder.setClipboard(sequence = 7L, text = "剪贴板", paste = false)
        val payload = "剪贴板".toByteArray(Charsets.UTF_8)
        val expected = expected {
            writeByte(ScrcpyControlProtocol.TYPE_SET_CLIPBOARD)
            writeLong(7L)
            writeByte(0) // paste = false
            writeInt(payload.size)
            write(payload)
        }
        assertContentEquals(expected, actual)
    }

    @Test
    fun `device message reader parses clipboard ack and uhid skipping`() {
        val clipboardPayload = "设备剪贴板".toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(
            ByteArrayOutputStream().let { buffer ->
                DataOutputStream(buffer).apply {
                    // clipboard(0)：4B 长 + UTF-8
                    writeByte(ScrcpyControlProtocol.DEVICE_MSG_CLIPBOARD)
                    writeInt(clipboardPayload.size)
                    write(clipboardPayload)
                    // uhid_output(2)：u16 id + u16 长 + 数据（本端无 uhid 设备——必须防御消费后继续）
                    writeByte(ScrcpyControlProtocol.DEVICE_MSG_UHID_OUTPUT)
                    writeShort(1) // id
                    writeShort(2) // 数据长
                    write(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
                    // ack_clipboard(1)：8B sequence
                    writeByte(ScrcpyControlProtocol.DEVICE_MSG_ACK_CLIPBOARD)
                    writeLong(42L)
                }.flush()
                buffer.toByteArray()
            },
        )
        val reader = ScrcpyDeviceMessageReader(stream)
        val clipboard = reader.next() as ScrcpyDeviceEvent.Clipboard
        assertEquals("设备剪贴板", clipboard.text)
        val ack = reader.next() as ScrcpyDeviceEvent.AckClipboard
        assertEquals(42L, ack.sequence)
        assertNull(reader.next(), "干净 EOF 返回 null")
    }

    @Test
    fun `device message reader fails explicitly on unknown type and truncated payload`() {
        val unknown = ScrcpyDeviceMessageReader(ByteArrayInputStream(byteArrayOf(99)))
        assertFailsWith<IOException> { unknown.next() }

        val truncated = ScrcpyDeviceMessageReader(
            ByteArrayInputStream(byteArrayOf(ScrcpyControlProtocol.DEVICE_MSG_ACK_CLIPBOARD.toByte(), 0x00)),
        )
        assertFailsWith<EOFException> { truncated.next() }
    }

    @Test
    fun `fixed point helpers match official codec endpoints`() {
        // u16 定点端点：0 → 0，1.0 → 0xffff（对端 0xffff 特判回 1.0）
        assertEquals(0, ScrcpyControlProtocol.floatToU16FixedPoint(0f))
        assertEquals(0xffff, ScrcpyControlProtocol.floatToU16FixedPoint(1f))
        assertEquals(0xffff, ScrcpyControlProtocol.floatToU16FixedPoint(2f), "超上界收口")
        // i16 定点端点：-1 → -0x8000，1 → 0x7fff（对端 0x7fff 特判回 1.0）
        assertEquals(-0x8000, ScrcpyControlProtocol.floatToI16FixedPoint(-1f))
        assertEquals(0x7fff, ScrcpyControlProtocol.floatToI16FixedPoint(1f))
        assertEquals(0x7fff, ScrcpyControlProtocol.floatToI16FixedPoint(5f), "超上界收口")
        assertTrue(ScrcpyControlProtocol.floatToI16FixedPoint(0f) == 0)
    }
}
