package com.jiaoay.easy_dev_toolbox.page.tools.base64

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * base64 行内提取引擎契约回归（纯函数，无 IO）：
 * 嗅探（长度阈值参数化、padding 并入候选、超上限截断明示、短串噪声不进列表）、
 * 解码三分支（图片魔数 PNG/JPEG/GIF/WebP、可读文本回显与超长截断明示、
 * 显式失败不静默——坏 base64 / 未知二进制 / 超长拒收、url-safe 字母表兜底）。
 */
class Base64ExtractorEngineTest {

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `sniff extracts long tokens and threshold is parameterized`() {
        val long1 = "QUJDREVGR0g".repeat(8) // 96 字符
        val long2 = "aGlqbWtubW8".repeat(10) // 120 字符
        val text = "10-11 12:00:00.001  100  200 I Cam: frame=$long1\n" +
            "10-11 12:00:00.002  100  200 I Cam: thumb=$long2==\n" +
            "noise session-id=QUJDRE style-uuid=aGk"

        val sniffed = sniffCandidates(text)
        assertEquals(2, sniffed.candidates.size, "短串（阈值以下）不构成候选，两个长 token 各一候选")
        assertEquals(false, sniffed.truncated, "候选数未超上限不截断")
        assertEquals(long1, sniffed.candidates[0].token, "候选 token = 原文连续子串")
        assertEquals("$long2==", sniffed.candidates[1].token, "尾部 = padding 并入候选（解码需完整 padding）")
        assertEquals(0, sniffed.candidates[0].index, "候选序 0 基自增")

        val lenient = sniffCandidates(text, minTokenLength = 4)
        assertTrue(
            lenient.candidates.size > 2,
            "阈值参数化：低阈值下短串（session-id/uuid 段）也进候选",
        )
    }

    @Test
    fun `sniff caps candidates beyond limit and reports truncation explicitly`() {
        val text = (1..MAX_CANDIDATES + 6).joinToString(" ") { "token$it-" + "A".repeat(DEFAULT_MIN_TOKEN_LENGTH) }
        val sniffed = sniffCandidates(text)
        assertEquals(MAX_CANDIDATES, sniffed.candidates.size, "候选数封顶在 MAX_CANDIDATES")
        assertTrue(sniffed.truncated, "超限截断必须明示（不静默吞候选）")
    }

    @Test
    fun `sniff on empty text or non-positive threshold yields no candidates`() {
        assertEquals(0, sniffCandidates("").candidates.size)
        assertEquals(0, sniffCandidates("anything", minTokenLength = 0).candidates.size)
    }

    @Test
    fun `decode identifies image formats by magic bytes`() {
        val png = byteArrayOf(0x89.toByte()) + "PNG\r\n\u001A\n".toByteArray(Charsets.US_ASCII) +
            ByteArray(32) { (it * 3).toByte() }
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(16) { 0x11 }
        val gif = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(8) { 0x00 }
        val webp = "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) { 0x00 } +
            "WEBP".toByteArray(Charsets.US_ASCII) + ByteArray(6) { 0x00 }

        for ((payload, expected) in listOf(
            png to Base64ImageFormat.PNG,
            jpeg to Base64ImageFormat.JPEG,
            gif to Base64ImageFormat.GIF,
            webp to Base64ImageFormat.WEBP,
        )) {
            val result = decodeCandidate(encode(payload))
            val image = assertIs<Base64DecodeResult.Image>(result, "魔数识别 ${expected.label} 不落显式失败")
            assertEquals(expected, image.format)
            assertTrue(image.bytes.size == payload.size, "解码字节与原文等长（魔数识别 + 字节透传）")
        }
    }

    @Test
    fun `decode returns utf-8 text content and truncates long content explicitly`() {
        val short = "屏幕已点亮 brightness=200 OK"
        val shortResult = assertIs<Base64DecodeResult.Text>(decodeCandidate(encode(short.toByteArray())))
        assertEquals(short, shortResult.content, "可读文本原样回显")
        assertEquals(false, shortResult.truncated)

        val long = "x".repeat(MAX_TEXT_PREVIEW_CHARS + 500)
        val longResult = assertIs<Base64DecodeResult.Text>(decodeCandidate(encode(long.toByteArray())))
        assertEquals(MAX_TEXT_PREVIEW_CHARS, longResult.content.length, "文本回显截到字符上限")
        assertTrue(longResult.truncated, "截断必须明示")
    }

    @Test
    fun `decode fails explicitly on malformed base64`() {
        // 长度 % 4 == 1：标准与 url-safe 两个解码器都拒收——坏 base64 显式失败
        val malformed = "A".repeat(65)
        val result = decodeCandidate(malformed)
        val failure = assertIs<Base64DecodeResult.Failure>(result)
        assertTrue("base64 非法" in failure.reason, "失败 reason 须指明坏 base64：${failure.reason}")
    }

    @Test
    fun `decode falls back to url-safe alphabet`() {
        // url-safe 字母表（-/ 替换 +/）：标准解码器拒收，须兜底解码成功
        val payload = "url-safe payload 设备日志嵌段".repeat(4)
        val token = Base64.getUrlEncoder().encodeToString(payload.toByteArray())
        val result = decodeCandidate(token)
        val text = assertIs<Base64DecodeResult.Text>(result, "url-safe token 须经兜底解码成功")
        assertEquals(payload, text.content)
    }

    @Test
    fun `decode fails explicitly on unknown binary payload`() {
        // 非图片魔数 + 非 UTF-8（0xFF 0xFE 引导）：不得伪装成文本或图片，显式未知格式
        val payload = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x01, 0x02, 0x03) + ByteArray(64) { (it * 7).toByte() }
        val result = decodeCandidate(encode(payload))
        val failure = assertIs<Base64DecodeResult.Failure>(result)
        assertTrue("未知格式" in failure.reason, "未知二进制显式失败不静默：${failure.reason}")
    }

    @Test
    fun `decode refuses oversized token explicitly`() {
        val oversized = "A".repeat(MAX_DECODE_TOKEN_LENGTH + 1)
        val result = decodeCandidate(oversized)
        val failure = assertIs<Base64DecodeResult.Failure>(result)
        assertTrue("超过解码上限" in failure.reason, "超长 token 拒收必须显式明示：${failure.reason}")
    }

    @Test
    fun `image magic sniffer rejects empty and unknown heads`() {
        assertNull(sniffImageFormat(ByteArray(0)), "空字节无格式")
        assertNull(sniffImageFormat("plain text bytes!".toByteArray()), "纯文本头无格式")
        assertNull(
            sniffImageFormat(byteArrayOf(0x89.toByte()) + "PNX".toByteArray()),
            "PNG 魔数缺一不可（89 后必须跟 PNG）",
        )
    }
}
