// :lark-ws —— pbbp2 wire-format 编解码单测：round-trip / required 缺失拒绝 / 未知字段跳过。
package com.jiaoay.lark.ws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WsFrameCodecTest {

    private val codec = WsFrameCodec()

    @Test
    fun `全字段 round-trip 逐位一致`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frame = WsFrame(
            seqId = 123456789L,
            logId = 987654321L,
            service = 77,
            method = WsFrameMethod.DATA.code,
            headers = listOf(
                WsHeader("type", "event"),
                WsHeader("message_id", "om_test_123"),
                WsHeader("sum", "1"),
            ),
            payloadEncoding = "plain",
            payloadType = "json",
            payload = payload,
        )
        val decoded = codec.decode(codec.encode(frame))
        assertEquals(frame, decoded)
    }

    @Test
    fun `minimal 帧 round-trip`() {
        val frame = WsFrame(
            seqId = 0,
            logId = 0,
            service = 1,
            method = WsFrameMethod.CONTROL.code,
            headers = listOf(WsHeader("type", "ping")),
        )
        assertEquals(frame, codec.decode(codec.encode(frame)))
    }

    @Test
    fun `未设 optional 字段解码为 null`() {
        val frame = WsFrame(1, 2, 3, 0, listOf(WsHeader("type", "pong")))
        val decoded = codec.decode(codec.encode(frame))
        assertNull(decoded.payload)
        assertNull(decoded.payloadEncoding)
        assertNull(decoded.payloadType)
    }

    @Test
    fun `header 检索命中与缺失`() {
        val frame = WsFrame(1, 2, 3, 0, listOf(WsHeader("type", "event"), WsHeader("message_id", "om_x")))
        assertEquals("event", frame.header("type"))
        assertEquals("om_x", frame.header("message_id"))
        assertNull(frame.header("trace_id"))
    }

    @Test
    fun `未知字段跳过不炸解码器`() {
        // 手工构造：合法帧 + 尾部追加未知 field 15（varint）——服务端新增字段（如 LogIDNew）兼容验证
        val base = codec.encode(WsFrame(1, 2, 3, 0, listOf(WsHeader("type", "event")), payload = byteArrayOf(9)))
        val unknownField = byteArrayOf(
            (15 shl 3 or 0).toByte(), // field 15, wire type 0 (varint)
            0x2A,                     // value 42
        )
        val decoded = codec.decode(base + unknownField)
        assertEquals(1, decoded.seqId)
        assertEquals(3, decoded.service)
        assertTrue(decoded.payload.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun `required 字段缺失拒绝解码`() {
        // 手工构造只有 service 的帧：缺 seq_id/log_id/method
        val malformed = byteArrayOf(
            (3 shl 3 or 0).toByte(), // field 3 (service), varint
            7,
        )
        val e = assertFailsWith<IllegalArgumentException> { codec.decode(malformed) }
        assertTrue(e.message!!.contains("seq_id"))
    }

    @Test
    fun `空输入拒绝`() {
        assertFailsWith<IllegalArgumentException> { codec.decode(ByteArray(0)) }
    }

    @Test
    fun `large payload round-trip`() {
        // 64KB 级 payload（真实事件体量上界附近），验证 varint 长度前缀多字节路径
        val big = ByteArray(64 * 1024) { (it % 251).toByte() }
        val frame = WsFrame(9, 9, 9, 1, listOf(WsHeader("type", "event")), payload = big)
        val decoded = codec.decode(codec.encode(frame))
        assertTrue(decoded.payload!!.contentEquals(big))
    }

    @Test
    fun `多 byte varint 边界值`() {
        // service 用 Int.MAX_VALUE（5 字节 varint）；seqId 用 Long.MAX_VALUE（多字节 varint）
        val frame = WsFrame(
            seqId = Long.MAX_VALUE,
            logId = Long.MIN_VALUE,
            service = Int.MAX_VALUE,
            method = 1,
            headers = emptyList(),
        )
        assertEquals(frame, codec.decode(codec.encode(frame)))
    }

    // ==================== 🔴 SDK 黄金样本（2026-09-13 真机事故防线）====================

    /**
     * 🔴 与飞书 SDK（oapi-sdk-java v2.8.5 `Pbbp2.Frame`）**字节级对齐**的黄金样本：
     * SDK `writeTo`（Pbbp2.java:1796-1799）用 `output.writeUInt64(1, seqID_)` = varint（tag 0x08），
     * 解码 switch 只有 `case 8:`/`case 16:`（varint tag；fixed64 会是 case 9/17——不存在）。
     * 首版 codec 误把 SeqID/LogID 写成 fixed64（tag 0x09/0x11）——自洽 round-trip 全绿但真机每帧
     * 解码即抛（服务端 varint 帧进来 wire type 不匹配），且我方 ping/回执帧被服务端判非法。
     * 🔴 自洽 round-trip **测不出协议错**——必须钉死 SDK 实际字节形态。
     */
    @Test
    fun `SDK 黄金样本字节解码（SeqID-LogID 走 varint tag 08-10 而非 fixed64）`() {
        // 手工按 SDK 生成代码的字节形态构造：
        // field 1 varint 0x08 0x2A（seqId=42）；field 2 varint 0x10 0x63（logId=99）；
        // field 3 varint 0x18 0x01（service=1）；field 4 varint 0x20 0x00（method=CONTROL=0）；
        // field 5 header：len-delimited {key="type",value="ping"}
        val sdkBytes = byteArrayOf(
            0x08, 0x2A, // field 1 (SeqID) varint 42
            0x10, 0x63, // field 2 (LogID) varint 99
            0x18, 0x01, // field 3 (service) varint 1
            0x20, 0x00, // field 4 (method) varint 0
            // field 5 (headers) len-delimited：内含 field1 key + field2 value
            0x2A, 0x0C, // tag(5<<3|2), len=12
            0x0A, 0x04, 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'e'.code.toByte(),
            0x12, 0x04, 'p'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(),
        )
        val decoded = codec.decode(sdkBytes)
        assertEquals(42L, decoded.seqId, "SeqID 必须从 varint（tag 0x08）解码——fixed64 实现在此必抛")
        assertEquals(99L, decoded.logId, "LogID 必须从 varint（tag 0x10）解码")
        assertEquals(1, decoded.service)
        assertEquals(0, decoded.method)
        assertEquals("ping", decoded.header("type"))
    }

    /** 编码侧同款防线：我方 encode 出的首 tag 必须是 0x08（varint）——fixed64 实现产出 0x09 即红。 */
    @Test
    fun `我方编码帧的 SeqID 首字节是 varint tag 08（与 SDK 服务端可读形态一致）`() {
        val encoded = codec.encode(WsFrame(seqId = 42, logId = 99, service = 1, method = 0, headers = emptyList()))
        assertEquals(0x08, encoded[0].toInt() and 0xFF, "🔴 首 tag 必须是 0x08（field1 varint）——fixed64 会是 0x09")
        assertEquals(42, encoded[1].toInt(), "varint 值直接跟 tag")
        assertEquals(0x10, encoded[2].toInt() and 0xFF, "LogID tag 必须是 0x10（field2 varint）——fixed64 会是 0x11")
        assertEquals(99, encoded[3].toInt(), "varint 值直接跟 tag")
    }
}
