package com.jiaoay.easy_dev_toolbox.core.smartdetect

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 剪贴板探测器纯函数单测：六类探测器各含正例 + 否定例（普通文本不误报——
 * 误报纪律「宁可漏报」的逐档断言）。
 */
class SmartDetectionTest {

    // ── epoch 时间戳 ──

    @Test
    fun `epoch detector hits 10 digit seconds and 13 digit millis`() {
        assertIs<SmartDetection>(detectEpochTimestamp("1728547200"))
        assertIs<SmartDetection>(detectEpochTimestamp("1728547200000"))
    }

    @Test
    fun `epoch detector rejects other lengths letters and out of range values`() {
        // 长度不档：9/11/12/14 位
        assertNull(detectEpochTimestamp("172854720"), "9 位不收")
        assertNull(detectEpochTimestamp("17285472000"), "11 位不收（电话号形态）")
        assertNull(detectEpochTimestamp("172854720000"), "12 位不收")
        assertNull(detectEpochTimestamp("17285472000000"), "14 位不收")
        // 非纯数字
        assertNull(detectEpochTimestamp("1728547200x"), "带字母不收")
        assertNull(detectEpochTimestamp("-1728547200"), "带符号不收")
        // 数值出窗：全零 / 远未来（2286 年）/ 全 9
        assertNull(detectEpochTimestamp("0000000000"), "全零不收")
        assertNull(detectEpochTimestamp("9999999999"), "9999999999 秒（2286 年）出窗不收")
        assertNull(detectEpochTimestamp("9999999999999"), "13 位全 9 出窗不收")
    }

    // ── .xlog / .alog 路径 ──

    @Test
    fun `xlog path detector hits xlog and alog suffix single tokens`() {
        assertIs<SmartDetection>(detectXLogPath("/tmp/logs/capture_0529.xlog"))
        assertIs<SmartDetection>(detectXLogPath("/tmp/logs/capture_0529.alog"))
        assertIs<SmartDetection>(detectXLogPath("C:\\logs\\app.alog"), "Windows 路径同收")
        assertIs<SmartDetection>(detectXLogPath("capture_0529.XLOG"), "后缀大小写不敏感")
        assertIs<SmartDetection>(detectXLogPath("capture.alog"), "裸文件名（无分隔符）同收")
    }

    @Test
    fun `xlog path detector rejects whitespace compounds and other suffixes`() {
        assertNull(detectXLogPath("/tmp/capture_0529.xlog extra text"), "带空白不收（非单 token）")
        assertNull(detectXLogPath("/tmp/a.xlog\n/tmp/b.alog"), "多行多路径不收（保守面）")
        assertNull(detectXLogPath("/tmp/logs/capture.xlog.txt"), ".xlog.txt 结尾不收")
        assertNull(detectXLogPath("/tmp/logs/capture.zlog"), ".zlog 不收")
        assertNull(detectXLogPath("日志文件为 app.xlog 请解密"), "句中提及不收（含空白）")
        assertNull(detectXLogPath(".xlog"), "裸后缀不收（无文件名主体）")
        assertNull(detectXLogPath("/tmp/.xlog"), "目录前缀结尾不收（无文件名主体）")
        assertNull(detectXLogPath("app..xlog"), "点结尾 stem 不收（非现实文件名）")
    }

    // ── JSON 对象 ──

    @Test
    fun `json detector hits parseable objects`() {
        assertIs<SmartDetection>(detectJsonObject("{\"a\": 1}"))
        assertIs<SmartDetection>(detectJsonObject("{\n  \"name\": \"toolbelt\",\n  \"version\": 2\n}"))
        assertIs<SmartDetection>(detectJsonObject("{\"nested\": {\"list\": [1, 2, 3]}}"))
    }

    @Test
    fun `json detector rejects arrays fragments and non objects`() {
        assertNull(detectJsonObject("[1, 2, 3]"), "数组不收（保守面：spec 只认对象）")
        assertNull(detectJsonObject("{\"a\": 1"), "截断花括号不收")
        assertNull(detectJsonObject("{not json}"), "不可解析不收")
        assertNull(detectJsonObject("hello {world}"), "非 { 开头不收")
        assertNull(detectJsonObject("fun main() {}"), "代码片段不收（非 { 开头）")
    }

    // ── base64 ──

    @Test
    fun `base64 detector hits standard and url safe tokens`() {
        assertIs<SmartDetection>(detectBase64Text("SGVsbG8gd29ybGQgdGhpcyBpcyBhIHRlc3Q="))
        assertIs<SmartDetection>(detectBase64Text("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), "JWT 头部 token（无填充）")
        assertIs<SmartDetection>(detectBase64Text("aGVsbG8gd29ybGQgaXMgZ3JlYXQ"), "27 字符无填充 token")
        assertIs<SmartDetection>(
            detectBase64Text("cS4jfkM_PDF-LXZ-ZStrViQj"),
            "URL-safe 字母表 token（含 -/_，走 URL decoder）",
        )
    }

    @Test
    fun `base64 detector rejects short tokens spaces and undecodable garbage`() {
        assertNull(detectBase64Text("SGVsbG8="), "短 token 不收（< 24 字符，宁可漏报）")
        assertNull(detectBase64Text("123456789012345678901234"), "纯数字长串解码乱码不收")
        assertNull(detectBase64Text("deadbeefdeadbeefdeadbeef"), "hex 长串解码乱码不收")
        assertNull(detectBase64Text("TheQuickBrownFoxJumpsOverTheLazyDog"), "英文单词连串解码乱码不收")
        assertNull(detectBase64Text("YWJjZGVm/Z2hp-amtsbW5vcHFyc3R1dnd4eXo"), "标准与 URL-safe 字母表混用不收")
        assertNull(detectBase64Text("aGVs bG8g d29y bGQ="), "含空格不收（非单 token）")
    }

    // ── 图片 base64 ──

    /** 构造图片魔数 token（魔数头 + 补位字节凑足接受域下限，标准字母表编码） */
    private fun imageTokenOf(vararg magic: Int, fillTo: Int = 18): String {
        val bytes = ByteArray(fillTo)
        magic.forEachIndexed { i, b -> bytes[i] = b.toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `image base64 detector hits png jpeg gif and webp magic tokens`() {
        val pngToken = imageTokenOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val png = detectImageBase64(pngToken)!!
        assertEquals(SmartDetectionKind.IMAGE_BASE64, png.kind)
        assertEquals(85, png.confidence)
        assertTrue("PNG" in png.preview && "18 字节" in png.preview, "预览=魔数档+解码字节数")
        assertEquals(pngToken, png.prefillText, "图片 base64 预填=token 原文（页内嗅探链自行解码）")

        assertIs<SmartDetection>(detectImageBase64(imageTokenOf(0xFF, 0xD8, 0xFF)), "JPEG 魔数同收")
        assertIs<SmartDetection>(
            detectImageBase64(imageTokenOf('G'.code, 'I'.code, 'F'.code, '8'.code, '9'.code, 'a'.code)),
            "GIF89a 魔数同收",
        )
        val webpBytes = ByteArray(18)
        "RIFF".toByteArray().copyInto(webpBytes, 0)
        "WEBP".toByteArray().copyInto(webpBytes, 8)
        assertIs<SmartDetection>(detectImageBase64(Base64.getEncoder().encodeToString(webpBytes)), "WebP 魔数同收")
    }

    @Test
    fun `image base64 detector rejects text tokens short tokens and non magic bytes`() {
        assertNull(detectImageBase64("SGVsbG8gd29ybGQgdGhpcyBpcyBhIHRlc3Q="), "可读文本 base64 不判图片（互斥判型）")
        assertNull(
            detectImageBase64(Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))),
            "短 token 不收（< 24 字符）",
        )
        assertNull(
            detectImageBase64(Base64.getEncoder().encodeToString("not an image at all!".toByteArray())),
            "解码成功但非图片魔数不收",
        )
        assertNull(detectImageBase64("123456789012345678901234"), "纯数字长串不收")
        assertNull(detectImageBase64("aGVs bG8g d29y bGQ="), "含空格不收（非单 token）")
    }

    // ── GZip ──

    /** 构造文本通道承载的 gzip 字符串（gzip 字节按 Latin-1 回字符——探测器接受域同链路） */
    private fun gzipStringOf(content: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    @Test
    fun `gzip detector hits magic headed streams and previews decompressed head`() {
        val hit = detectGzipData(gzipStringOf("1760000000.123  E/MyTag: boot completed line for preview"))!!
        assertEquals(SmartDetectionKind.GZIP_DATA, hit.kind)
        assertEquals(95, hit.confidence)
        assertTrue(hit.preview.startsWith("1760000000"), "预览=解压产物首段文本（非二进制原文）")
        assertNull(hit.prefillText, "gzip 无预填面（承载内容字节而非路径）")
    }

    @Test
    fun `gzip detector rejects short heads wrong magic bad flags and corrupt streams`() {
        assertNull(detectGzipData(""), "空串不收")
        assertNull(detectGzipData("\u001F\u008B\u0008"), "3 字节裸魔数头不收")
        assertNull(detectGzipData("regular text content here"), "非魔数不收")
        assertNull(detectGzipData("x".repeat(64)), "普通长文本不收")
        // 短头：魔数碎片长度不足接受域下限
        assertNull(detectGzipData("\u001F\u008B\u0008\u0000"), "4 字节裸短头不收")
        assertNull(detectGzipData("\u001F\u008B\u0008\u0000" + "x".repeat(32)), "魔数后无有效 deflate 流不收")
        assertNull(detectGzipData("\u001F\u008B\u0008\u0000" + "garbage content bytes" + "x".repeat(16)), "损坏流不收")
        // FLG 保留位非零（0xE0 = bit5-7 置位）
        assertNull(detectGzipData("\u001F\u008B\u0008\u00E0" + "x".repeat(32)), "FLG 保留位非零不收")
        // 文本链路损坏：超出 Latin-1 域的字符混入（回字节失真）
        assertNull(detectGzipData(gzipStringOf("content") + "中文"), "超出 0xFF 字符域不收")
    }

    // ── 预填载荷 ──

    @Test
    fun `prefill payload is carried by epoch xlog json base64 and image base64 detections`() {
        assertEquals("1728547200", detectEpochTimestamp("1728547200")!!.prefillText, "epoch 预填=原文整串")
        assertEquals("/tmp/a.xlog", detectXLogPath("/tmp/a.xlog")!!.prefillText, "路径预填=原文整串")
        assertEquals("{\"a\": 1}", detectJsonObject("{\"a\": 1}")!!.prefillText, "JSON 预填=原文整串（输入框粘贴即校验链消费）")
        val base64Token = "SGVsbG8gd29ybGQgdGhpcyBpcyBhIHRlc3Q="
        assertEquals(base64Token, detectBase64Text(base64Token)!!.prefillText, "base64 文本预填=token 原文")
        assertNull(detectGzipData(gzipStringOf("content for gzip"))!!.prefillText, "gzip 无预填面（承载内容字节而非路径，解压页输入面是文件拖入）")
        val imageToken = imageTokenOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(imageToken, detectImageBase64(imageToken)!!.prefillText, "图片 base64 预填=token 原文（原文即页内合法输入）")
    }

    // ── XML 片段不误报（工具面无 XML 消费签——宁可漏报）──

    @Test
    fun `xml code snippets produce no detections`() {
        assertEquals(
            emptyList(),
            detectClipboard("<manifest package=\"com.demo\"><application android:label=\"demo\"/></manifest>"),
            "XML 片段不产生任何候选",
        )
        assertEquals(emptyList(), detectClipboard("<a><b>value</b></a>"), "简短 XML 不产生候选")
        assertEquals(
            emptyList(),
            detectClipboard("<View\n  android:id=\"@+id/title\"\n  android:text=\"hello\"/>"),
            "多行 XML 布局片段不产生候选",
        )
    }

    // ── 入口聚合与排序 ──

    @Test
    fun `detectClipboard returns empty for null blank and plain text`() {
        assertEquals(emptyList(), detectClipboard(null))
        assertEquals(emptyList(), detectClipboard(""))
        assertEquals(emptyList(), detectClipboard("   \n  "))
        // 普通文本全族不误报：自然语言 / 短语 / 常见命令行
        assertEquals(emptyList(), detectClipboard("hello world 你好世界"))
        assertEquals(emptyList(), detectClipboard("The quick brown fox jumps over the lazy dog"))
        assertEquals(emptyList(), detectClipboard("adb logcat -s MyTag:V *:S"))
        assertEquals(emptyList(), detectClipboard("2025-10-12 14:00:00.123"))
    }

    @Test
    fun `detectClipboard orders hits by confidence and stays single hit per input`() {
        // 六类接受域互斥（epoch 定长 10/13 < base64 下限 24；路径含 '.' 不进 base64
        // 字母表；JSON 以 '{' 开头与 base64/epoch 互斥；gzip 魔数/图片魔数与文本
        // base64 按产物判型分流）——现实输入恒单命中，
        // 单命中断言即「不串台」的全量锚；置信降序排序由各正例的 kind 唯一性背书。
        val epoch = detectClipboard("1728547200000")
        assertEquals(listOf(SmartDetectionKind.EPOCH_TIMESTAMP), epoch.map { it.kind })

        val xlog = detectClipboard("/tmp/logs/capture_0529.alog")
        assertEquals(listOf(SmartDetectionKind.XLOG_PATH), xlog.map { it.kind })

        val json = detectClipboard("{\"level\": \"ERROR\", \"tag\": \"MyTag\"}")
        assertEquals(listOf(SmartDetectionKind.JSON_OBJECT), json.map { it.kind })

        val b64 = detectClipboard("SGVsbG8gd29ybGQgdGhpcyBpcyBhIHRlc3Q=")
        assertEquals(listOf(SmartDetectionKind.BASE64_TEXT), b64.map { it.kind })

        val gz = detectClipboard(gzipStringOf("log line content for gzip detection"))
        assertEquals(listOf(SmartDetectionKind.GZIP_DATA), gz.map { it.kind }, "gzip 字节流单命中")

        val img = detectClipboard(imageTokenOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        assertEquals(listOf(SmartDetectionKind.IMAGE_BASE64), img.map { it.kind }, "图片 base64 单命中（与文本判型互斥）")
    }

    // ── 预览构造 ──

    @Test
    fun `preview flattens newlines and truncates with ellipsis`() {
        assertEquals("{ \"a\": 1 }", buildPreview("{\n\"a\": 1\n}"), "换行压平为单空格")
        val long = "x".repeat(60)
        val preview = buildPreview(long)
        assertTrue(preview.endsWith("…"), "超长预览必须以省略号截断")
        assertEquals(49, preview.length, "预览长度 = 截断长度 + 省略号")
    }

    @Test
    fun `previews are carried through detection results`() {
        // 探测器入参 = detectClipboard 已 trim 的文本；预览承载 trim 后原文
        assertEquals("1728547200", detectEpochTimestamp("1728547200")!!.preview)
    }
}
