// :lark-ws —— pbbp2 帧协议（飞书长连接二进制 protobuf 私有协议）。
// 唯一真源：oapi-sdk-java v2.8.5 源码 ws/pb/Pbbp2.java（生成代码）+ ws/Client.java 的用法。
// 不引 protobuf-java：pbbp2 仅 2 个 message（Header 2 字段 / Frame 9 字段），
//    全部字段为 varint / fixed64 / length-delimited 标量——wire format 手写编解码充分覆盖，
//    未知字段按 wire format 天然跳过（field 9 LogIDNew 等服务端新增字段不炸解码器）。
package com.jiaoay.lark.ws

/** 帧类型（pbbp2.Frame.method 字段取值；对照 ws/enums/FrameType）。 */
enum class WsFrameMethod(val code: Int) {
    CONTROL(0),
    DATA(1),
    ;

    companion object {
        fun of(code: Int): WsFrameMethod? = entries.firstOrNull { it.code == code }
    }
}

/** 消息类型（pbbp2 header `type` 字段取值；对照 ws/enums/MessageType）。 */
enum class WsMessageType(val wireName: String) {
    EVENT("event"),
    CARD("card"),
    PING("ping"),
    PONG("pong"),
    ;

    companion object {
        fun of(name: String?): WsMessageType? = entries.firstOrNull { it.wireName == name }
    }
}

/** pbbp2.Frame 的 Kotlin 镜像（字段号照 pbbp2.proto：1~9）。 */
data class WsFrame(
    /** field 1：SeqID（uint64 varint wire type 0——SDK Pbbp2 writeTo=writeUInt64/解码 case 8 实锤，**非 fixed64**）。 */
    val seqId: Long,
    /** field 2：LogID（uint64 varint，required——同上）。 */
    val logId: Long,
    /** field 3：service（int32 varint，required）——ping 帧用连接 URL 的 service_id。 */
    val service: Int,
    /** field 4：method（int32 varint，required）——[WsFrameMethod.code]。 */
    val method: Int,
    /** field 5：headers（repeated message Header）。 */
    val headers: List<WsHeader>,
    /** field 6：payload_encoding（string，optional）——解码未实证出现，编码不写。 */
    val payloadEncoding: String? = null,
    /** field 7：payload_type（string，optional）——同上。 */
    val payloadType: String? = null,
    /** field 8：payload（bytes，optional）。 */
    val payload: ByteArray? = null,
) {
    /** header 检索（每 key 至多一个；缺返回 null）。 */
    fun header(key: String): String? = headers.firstOrNull { it.key == key }?.value

    override fun equals(other: Any?): Boolean = this === other ||
        (other is WsFrame && other.seqId == seqId && other.logId == logId && other.service == service &&
            other.method == method && other.headers == headers && other.payloadEncoding == payloadEncoding &&
            other.payloadType == payloadType && other.payload.contentEquals(payload))

    override fun hashCode(): Int {
        var result = seqId.hashCode()
        result = 31 * result + logId.hashCode()
        result = 31 * result + service
        result = 31 * result + method
        result = 31 * result + headers.hashCode()
        result = 31 * result + (payloadEncoding?.hashCode() ?: 0)
        result = 31 * result + (payloadType?.hashCode() ?: 0)
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
}

/** pbbp2.Header 的 Kotlin 镜像（key=field 1，value=field 2，均 required string）。 */
data class WsHeader(val key: String, val value: String)

// ==================== wire format 编解码（protobuf 官方 wire format 手写）====================

/** pbbp2 wire-format 编解码器。顶层 class + 无状态（F 组纪律：禁 object 静态可变态）。 */
class WsFrameCodec {

    fun encode(frame: WsFrame): ByteArray {
        val out = ByteAccumulator()
        // field 1 / 2：varint（wire type 0）——proto 声明 required uint64 ⇒ varint。
        // 🔴 协议契约（勿改 fixed64）：SDK Pbbp2.java writeTo 用 output.writeUInt64(1, seqID_)
        // = writeTag(field,0)，解码 switch 只有 case 8/case 16（varint tag；fixed64 会是
        // case 9/case 17——不存在）。帧 tag 若写 9/17 会被服务端当未知字段跳过（required
        // 缺失判非法），事件帧零解码成功。
        writeTag(out, FIELD_SEQ_ID, WIRE_VARINT)
        out.varint(frame.seqId)
        writeTag(out, FIELD_LOG_ID, WIRE_VARINT)
        out.varint(frame.logId)
        // field 3 / 4：varint（wire type 0）
        writeTag(out, FIELD_SERVICE, WIRE_VARINT)
        out.varint(frame.service.toLong())
        writeTag(out, FIELD_METHOD, WIRE_VARINT)
        out.varint(frame.method.toLong())
        // field 5：repeated message（wire type 2，每元素一 tag）
        for (h in frame.headers) {
            writeTag(out, FIELD_HEADERS, WIRE_LEN)
            val inner = ByteAccumulator()
            writeTag(inner, FIELD_HEADER_KEY, WIRE_LEN)
            inner.bytes(h.key.toByteArray(Charsets.UTF_8))
            writeTag(inner, FIELD_HEADER_VALUE, WIRE_LEN)
            inner.bytes(h.value.toByteArray(Charsets.UTF_8))
            out.bytes(inner.toByteArray())
        }
        // field 6 / 7：optional string（wire type 2）——只在非 null 时写（proto3 optional 语义）
        frame.payloadEncoding?.let {
            writeTag(out, FIELD_PAYLOAD_ENCODING, WIRE_LEN)
            out.bytes(it.toByteArray(Charsets.UTF_8))
        }
        frame.payloadType?.let {
            writeTag(out, FIELD_PAYLOAD_TYPE, WIRE_LEN)
            out.bytes(it.toByteArray(Charsets.UTF_8))
        }
        // field 8：optional bytes（wire type 2）
        frame.payload?.let {
            writeTag(out, FIELD_PAYLOAD, WIRE_LEN)
            out.bytes(it)
        }
        // field 9（LogIDNew）：未实证服务端需要，编码不写（解码按未知字段跳过）
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): WsFrame {
        require(bytes.isNotEmpty()) { "empty frame" }
        var seqId: Long? = null
        var logId: Long? = null
        var service: Int? = null
        var method: Int? = null
        val headers = ArrayList<WsHeader>()
        var payloadEncoding: String? = null
        var payloadType: String? = null
        var payload: ByteArray? = null
        var i = 0
        while (i < bytes.size) {
            val tagStart = i
            val tag = readVarint(bytes, tagStart)
            i = tagEnd(bytes, tagStart)
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = tag and 0x7
            when (fieldNumber) {
                FIELD_SEQ_ID -> {
                    // required uint64 = wire type 0（SDK 解码 case 8）
                    requireWireType(wireType, WIRE_VARINT, FIELD_SEQ_ID)
                    val v = readVarint(bytes, i)
                    i = tagEnd(bytes, i)
                    seqId = v
                }
                FIELD_LOG_ID -> {
                    requireWireType(wireType, WIRE_VARINT, FIELD_LOG_ID)
                    val v = readVarint(bytes, i)
                    i = tagEnd(bytes, i)
                    logId = v
                }
                FIELD_SERVICE -> {
                    requireWireType(wireType, WIRE_VARINT, FIELD_SERVICE)
                    val v = readVarint(bytes, i)
                    i = tagEnd(bytes, i)
                    service = v.toInt()
                }
                FIELD_METHOD -> {
                    requireWireType(wireType, WIRE_VARINT, FIELD_METHOD)
                    val v = readVarint(bytes, i)
                    i = tagEnd(bytes, i)
                    method = v.toInt()
                }
                FIELD_HEADERS -> {
                    requireWireType(wireType, WIRE_LEN, FIELD_HEADERS)
                    val len = readVarint(bytes, i).toInt()
                    i = tagEnd(bytes, i)
                    val end = i + len
                    require(end <= bytes.size) { "header length overflow: $len" }
                    headers += decodeHeader(bytes, i, end)
                    i = end
                }
                FIELD_PAYLOAD_ENCODING -> {
                    requireWireType(wireType, WIRE_LEN, FIELD_PAYLOAD_ENCODING)
                    val len = readVarint(bytes, i).toInt()
                    i = tagEnd(bytes, i)
                    val end = i + len
                    require(end <= bytes.size) { "payload_encoding length overflow" }
                    payloadEncoding = String(bytes, i, end - i, Charsets.UTF_8)
                    i = end
                }
                FIELD_PAYLOAD_TYPE -> {
                    requireWireType(wireType, WIRE_LEN, FIELD_PAYLOAD_TYPE)
                    val len = readVarint(bytes, i).toInt()
                    i = tagEnd(bytes, i)
                    val end = i + len
                    require(end <= bytes.size) { "payload_type length overflow" }
                    payloadType = String(bytes, i, end - i, Charsets.UTF_8)
                    i = end
                }
                FIELD_PAYLOAD -> {
                    requireWireType(wireType, WIRE_LEN, FIELD_PAYLOAD)
                    val len = readVarint(bytes, i).toInt()
                    i = tagEnd(bytes, i)
                    val end = i + len
                    require(end <= bytes.size) { "payload length overflow: $len" }
                    payload = bytes.copyOfRange(i, end)
                    i = end
                }
                else -> i = skipField(bytes, i, wireType)
            }
        }
        // proto2 required 语义：四个 required 字段缺失即拒绝（与服务端协议一致，不宽容）
        return WsFrame(
            seqId = requireNotNull(seqId) { "missing required field seq_id" },
            logId = requireNotNull(logId) { "missing required field log_id" },
            service = requireNotNull(service) { "missing required field service" },
            method = requireNotNull(method) { "missing required field method" },
            headers = headers,
            payloadEncoding = payloadEncoding,
            payloadType = payloadType,
            payload = payload,
        )
    }

    // ---------- 内部：wire format 原语 ----------

    private fun decodeHeader(bytes: ByteArray, start: Int, end: Int): WsHeader {
        var key: String? = null
        var value: String? = null
        var i = start
        while (i < end) {
            val tag = readVarint(bytes, i)
            i = tagEnd(bytes, i)
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = tag and 0x7
            when (fieldNumber) {
                FIELD_HEADER_KEY -> {
                    requireWireType(wireType, WIRE_LEN, FIELD_HEADER_KEY)
                    val len = readVarint(bytes, i).toInt()
                    i = tagEnd(bytes, i)
                    key = String(bytes, i, len, Charsets.UTF_8)
                    i += len
                }
                FIELD_HEADER_VALUE -> {
                    requireWireType(wireType, WIRE_LEN, FIELD_HEADER_VALUE)
                    val len = readVarint(bytes, i).toInt()
                    i = tagEnd(bytes, i)
                    value = String(bytes, i, len, Charsets.UTF_8)
                    i += len
                }
                else -> i = skipField(bytes, i, wireType)
            }
            require(i <= end) { "header sub-field overflow" }
        }
        return WsHeader(
            key = requireNotNull(key) { "missing header key" },
            value = requireNotNull(value) { "missing header value" },
        )
    }

    /** 未知字段跳过（wire format 天然自描述；field 9 LogIDNew 及未来新增字段安全通过）。 */
    private fun skipField(bytes: ByteArray, start: Int, wireType: Long): Int = when (wireType.toInt()) {
        WIRE_VARINT -> tagEnd(bytes, start)
        WIRE_FIXED64 -> start + 8
        WIRE_LEN -> {
            val len = readVarint(bytes, start).toInt()
            tagEnd(bytes, start) + len
        }
        WIRE_FIXED32 -> start + 4
        // wire type 3/4（group，proto2 遗留）与 5+：协议里未出现，拒绝
        else -> throw IllegalArgumentException("unsupported wire type $wireType")
    }

    private fun writeTag(out: ByteAccumulator, fieldNumber: Int, wireType: Int) {
        out.varint(((fieldNumber shl 3) or wireType).toLong())
    }

    private fun requireWireType(actual: Long, expected: Int, fieldNumber: Int) {
        require(actual == expected.toLong()) { "field $fieldNumber wire type $actual != $expected" }
    }

    private fun readVarint(bytes: ByteArray, start: Int): Long {
        var shift = 0
        var result = 0L
        var i = start
        while (true) {
            require(i < bytes.size) { "varint truncated" }
            val b = bytes[i].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }

    /** varint 结束后的下一索引（readVarint 不返回消费长度，用本函数补齐索引推进）。 */
    private fun tagEnd(bytes: ByteArray, varintStart: Int): Int {
        var i = varintStart
        while (i < bytes.size) {
            if (bytes[i].toInt() and 0x80 == 0) return i + 1
            i++
        }
        throw IllegalArgumentException("varint truncated")
    }

    private fun readFixed64(bytes: ByteArray, start: Int): Long {
        require(start + 8 <= bytes.size) { "fixed64 truncated" }
        var result = 0L
        for (idx in 7 downTo 0) {
            result = (result shl 8) or (bytes[start + idx].toLong() and 0xFF)
        }
        return result
    }

    /** 可增长字节缓冲（编码侧；避免为 4 个原语引入 ByteArrayOutputStream 满配）。 */
    private class ByteAccumulator {
        private var buf = ByteArray(64)
        private var len = 0

        fun varint(v: Long) {
            var value = v
            while (true) {
                if (value and 0x7FL.inv() == 0L) {
                    push((value and 0x7F).toInt())
                    return
                }
                push(((value and 0x7F) or 0x80).toInt())
                value = value ushr 7
            }
        }

        fun fixed64(v: Long) {
            for (shift in 0 until 64 step 8) push(((v ushr shift) and 0xFF).toInt())
        }

        fun bytes(bs: ByteArray) {
            varint(bs.size.toLong())
            ensure(len + bs.size)
            System.arraycopy(bs, 0, buf, len, bs.size)
            len += bs.size
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)

        private fun push(b: Int) {
            ensure(len + 1)
            buf[len] = b.toByte()
            len++
        }

        private fun ensure(capacity: Int) {
            if (capacity > buf.size) {
                var newSize = buf.size
                while (newSize < capacity) newSize = newSize shl 1
                buf = buf.copyOf(newSize)
            }
        }
    }

    companion object {
        // pbbp2.proto 字段号（生成代码 FIELD_NUMBER_* 逐一对照）
        private const val FIELD_SEQ_ID = 1
        private const val FIELD_LOG_ID = 2
        private const val FIELD_SERVICE = 3
        private const val FIELD_METHOD = 4
        private const val FIELD_HEADERS = 5
        private const val FIELD_PAYLOAD_ENCODING = 6
        private const val FIELD_PAYLOAD_TYPE = 7
        private const val FIELD_PAYLOAD = 8
        private const val FIELD_HEADER_KEY = 1
        private const val FIELD_HEADER_VALUE = 2

        // protobuf wire types
        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_LEN = 2
        private const val WIRE_FIXED32 = 5
    }
}
