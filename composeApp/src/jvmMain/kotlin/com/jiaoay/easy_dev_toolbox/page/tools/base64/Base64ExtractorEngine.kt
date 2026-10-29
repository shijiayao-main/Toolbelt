package com.jiaoay.easy_dev_toolbox.page.tools.base64

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * base64 日志行内提取引擎（纯函数面，无 IO、无 Compose 依赖）。
 *
 * ## 动机
 * 设备日志会把截图帧以 base64 形态整段嵌在日志行里——肉眼比对字符串
 * 不可能，通用转换工具（整段输入 → 整段输出）对「日志行内提取」场景失效：
 * 本引擎从粘贴的日志文本中嗅探长 base64 token，逐候选解码并按魔数识别图片
 * 格式，供面板做内联图片预览或文本回显。
 *
 * ## 契约
 * - [sniffCandidates] 无状态：长度阈值参数化（[minTokenLength]）——低于阈值的
 *   短串不构成候选（日志里的短 hash/session id 噪声不进列表）；候选数硬上界
 *   [MAX_CANDIDATES]，超限截断并以 [Base64SniffResult.truncated] 明示——截断
 *   必须可见，不得静默吞候选。
 * - [decodeCandidate] 显式三分支：图片（魔数识别 [Base64ImageFormat]）/ 可读
 *   文本 / 失败（[Base64DecodeResult.Failure] 携带可读 reason——坏 base64 与
 *   未知格式均显式失败，不静默）。标准 base64 字母表优先，url-safe 字母表
 *   （`-`/`_` 替换 `+`/`/`）兜底，两者都拒收才判非法。
 * - 超长解码防线：token 超过 [MAX_DECODE_TOKEN_LENGTH] 直接拒绝解码并明示
 *   （超长串解码耗时不可控；面板侧另在后台线程解码，本上限是引擎侧硬底线）。
 */

/** 嗅探出的候选 token（[token] 是原文连续子串，含尾部 `=` padding） */
data class Base64Candidate(val index: Int, val token: String)

/** 嗅探结果：[truncated] = true 表示超过 [MAX_CANDIDATES] 被截断 */
data class Base64SniffResult(val candidates: List<Base64Candidate>, val truncated: Boolean)

/** 魔数识别出的图片格式档 */
enum class Base64ImageFormat(val label: String) {
    PNG("PNG"),
    JPEG("JPEG"),
    GIF("GIF"),
    WEBP("WebP"),
}

/** 单候选解码结果：显式三分支，失败不静默 */
sealed interface Base64DecodeResult {

    /** 图片字节（已按魔数定格 [format]；面板据此解码 ImageBitmap 内联预览） */
    data class Image(val bytes: ByteArray, val format: Base64ImageFormat) : Base64DecodeResult

    /** 可读文本：[truncated] = true 表示超过 [MAX_TEXT_PREVIEW_CHARS] 被截断 */
    data class Text(val content: String, val truncated: Boolean) : Base64DecodeResult

    /** 显式失败：reason 是可直接呈现给人的失败原因 */
    data class Failure(val reason: String) : Base64DecodeResult
}

/** 嗅探的最小 token 长度默认档：低于此长度的串不构成候选 */
const val DEFAULT_MIN_TOKEN_LENGTH: Int = 64

/** 候选数硬上界：超出即截断（[Base64SniffResult.truncated] 明示） */
const val MAX_CANDIDATES: Int = 64

/** 单 token 解码长度上限（字符）：超长串解码阻塞的硬底线，拒绝时显式明示 */
const val MAX_DECODE_TOKEN_LENGTH: Int = 8 * 1024 * 1024

/** 文本回显字符上限：超出截断并以 [Base64DecodeResult.Text.truncated] 明示 */
const val MAX_TEXT_PREVIEW_CHARS: Int = 10_000

/**
 * 嗅探文本中的长 base64 token。token 字母表 = 标准 + url-safe 两字符集的并集
 * （`A-Za-z0-9+/=_-`，日志里无法预知编码方用的字母表变体），尾部 `=` padding
 * 并入 token（解码需完整 padding）。空文本 / 非正阈值返回空结果不截断。
 */
fun sniffCandidates(text: String, minTokenLength: Int = DEFAULT_MIN_TOKEN_LENGTH): Base64SniffResult {
    if (text.isEmpty() || minTokenLength <= 0) return Base64SniffResult(emptyList(), truncated = false)
    val tokenPattern = Regex("[A-Za-z0-9+/\\-_]{$minTokenLength,}={0,2}")
    val found = ArrayList<Base64Candidate>()
    var truncated = false
    for (match in tokenPattern.findAll(text)) {
        if (found.size >= MAX_CANDIDATES) {
            truncated = true
            break
        }
        found.add(Base64Candidate(found.size, match.value))
    }
    return Base64SniffResult(found, truncated)
}

/**
 * 解码单候选：魔数识别图片 → 可读文本回显 → 显式失败（坏 base64 / 未知格式 /
 * 超长拒收），三分支穷尽、失败必带可读 reason。
 */
fun decodeCandidate(token: String): Base64DecodeResult {
    if (token.length > MAX_DECODE_TOKEN_LENGTH) {
        return Base64DecodeResult.Failure(
            "候选长度 ${token.length} 字符，超过解码上限 $MAX_DECODE_TOKEN_LENGTH——已拒绝解码（超长串解码会阻塞界面）",
        )
    }
    val bytes = try {
        decodeBytes(token)
    } catch (e: IllegalArgumentException) {
        return Base64DecodeResult.Failure(
            "base64 非法（${e.message ?: "长度或字符集不合法"}）——候选无法解码",
        )
    }
    sniffImageFormat(bytes)?.let { return Base64DecodeResult.Image(bytes, it) }
    return decodeAsText(bytes) ?: Base64DecodeResult.Failure(
        "解码成功（${bytes.size} 字节）但既非已知图片魔数也非可读文本——未知格式",
    )
}

/** 标准字母表优先、url-safe 字母表兜底；两者都拒收才抛（非法判定见 [decodeCandidate]） */
private fun decodeBytes(token: String): ByteArray = try {
    java.util.Base64.getDecoder().decode(token)
} catch (e: IllegalArgumentException) {
    java.util.Base64.getUrlDecoder().decode(token)
}

/**
 * 图片魔数嗅探：PNG / JPEG / GIF / WebP 四档。只看文件头不校验完整性——
 * 完整性失败由面板的图像解码层显式接住（魔数识别与图像解码是两层，各管各的）。
 */
internal fun sniffImageFormat(bytes: ByteArray): Base64ImageFormat? {
    if (bytes.isEmpty()) return null
    fun asciiAt(offset: Int, length: Int): String? =
        if (bytes.size >= offset + length) String(bytes, offset, length, Charsets.US_ASCII) else null

    return when {
        bytes[0] == 0x89.toByte() && asciiAt(1, 3) == "PNG" -> Base64ImageFormat.PNG
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            Base64ImageFormat.JPEG

        asciiAt(0, 6) == "GIF87a" || asciiAt(0, 6) == "GIF89a" -> Base64ImageFormat.GIF
        asciiAt(0, 4) == "RIFF" && asciiAt(8, 4) == "WEBP" -> Base64ImageFormat.WEBP
        else -> null
    }
}

/**
 * 按 UTF-8 严格解码并做可打印率门槛（≥ 90%）：全零/控制字节占多的二进制串
 * 技术上能过 UTF-8 解码，但回显给人是乱码刷屏——判为非文本走显式失败分支。
 * 90% 而非 100%：合法文本可含少量控制字符（换行/制表符已豁免，余量留给零星
 * 控制符）。超 [MAX_TEXT_PREVIEW_CHARS] 截断并明示。
 */
private fun decodeAsText(bytes: ByteArray): Base64DecodeResult.Text? {
    if (bytes.isEmpty()) return null
    val content = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        return null
    }
    val printable = content.count { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }
    if (printable * 10 < content.length * 9) return null
    return Base64DecodeResult.Text(
        content = content.take(MAX_TEXT_PREVIEW_CHARS),
        truncated = content.length > MAX_TEXT_PREVIEW_CHARS,
    )
}
