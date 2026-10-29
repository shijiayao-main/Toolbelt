package com.jiaoay.easy_dev_toolbox.core.smartdetect

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 剪贴板内容智能探测（palette 推荐行的数据源）：纯函数族——字符串进、
 * 探测结果出，无环境依赖（剪贴板读取由宿主域承接，见 CommandPalette）。
 *
 * ## 探测面（六类）
 * - [SmartDetectionKind.EPOCH_TIMESTAMP]：纯数字 10 位（秒级）/ 13 位（毫秒级）
 *   epoch 时间戳 → 时间戳工具候选；
 * - [SmartDetectionKind.XLOG_PATH]：`.xlog` / `.alog` 结尾的单一路径 token
 *   → XLog 解密候选；
 * - [SmartDetectionKind.JSON_OBJECT]：`{` 开头且可整体解析的 JSON 对象
 *   → JSON 工具候选；
 * - [SmartDetectionKind.BASE64_TEXT]：长 base64 token（标准或 URL-safe 字母表）
 *   且解码产物为可打印文本 → Base64 提取候选；
 * - [SmartDetectionKind.GZIP_DATA]：魔数 `1F 8B 08` 开头且可解压出首段内容的
 *   GZip 字节流（文本通道承载）→ 解压工具候选；
 * - [SmartDetectionKind.IMAGE_BASE64]：base64 token 且解码产物命中图片魔数
 *   （PNG/JPEG/GIF/WebP）→ Base64 提取（图片内联预览）候选。
 *
 * ## 误报纪律（宁可漏报）
 * 每个探测器自带保守接受域：长度不在档、字符集不纯、数值不在 2001-2096 年
 * 合理窗、解码产物不可读（UTF-8 替换符/控制符占比超阈）、魔数缺位一律判否——
 * 普通文本（自然语言/代码片段/短单词）不得产生任何候选。
 *
 * ## 置信与排序
 * [detectClipboard] 汇总全部命中并按 [SmartDetection.confidence] 降序稳定排序
 * （同置信保持探测器注册序）。
 */
enum class SmartDetectionKind {
    EPOCH_TIMESTAMP,
    BASE64_TEXT,
    JSON_OBJECT,
    XLOG_PATH,
    GZIP_DATA,
    IMAGE_BASE64,
}

/**
 * 单个探测命中：种类 + 置信分（0..100，越高越可信）+ 原文预览（已压平换行、
 * 截断到 [PREVIEW_MAX_CHARS]——推荐行 meta 展示用，非原文回放）。
 *
 * [prefillText]：预填载荷原文（null = 该类识别无预填面，推荐行只跳页不带内容）。
 * 完整非截断原文，palette 推荐行经 :core 导航域 SmartPrefillHook 槽捎带到目标
 * 工具页输入区。预填不自动执行——载荷只送进输入区待用户确认，任何会动任务/
 * 写盘的落地必须由用户显式动作触发（违反即产生「复制即执行」误动作）。
 */
data class SmartDetection(
    val kind: SmartDetectionKind,
    val confidence: Int,
    val preview: String,
    val prefillText: String? = null,
)

/** 探测输入长度硬上限：超长剪贴板（如整段日志）只对头部探测，整体判否即漏报（保守面） */
private const val MAX_INPUT_CHARS = 65_536

/** base64 token 长度接受域：短 token（< 24 字符）几乎全是普通单词，宁可漏报 */
private const val BASE64_MIN_CHARS = 24
private const val BASE64_MAX_CHARS = 8_192

/** base64 解码产物可读性阈值：UTF-8 替换符/控制符占比超过 10% 判否（乱码=误报源） */
private const val BASE64_MAX_SUSPICIOUS_RATIO = 0.1

/** epoch 合理窗：1e9 秒（2001 年）至 4e9 秒前段（2096 年附近）——首位数字 1-3 的现实时间戳域 */
private const val EPOCH_SECONDS_MIN = 1_000_000_000L
private const val EPOCH_SECONDS_MAX = 3_999_999_999L
private const val EPOCH_MILLIS_MIN = 1_000_000_000_000L
private const val EPOCH_MILLIS_MAX = 3_999_999_999_999L

/** 路径 token 长度硬上限：超长路径不可能是剪贴板里的单个文件路径 */
private const val PATH_MAX_CHARS = 1_024

/**
 * GZip 接受域下限（字符数）：任何现实 gzip（10 字节头 + 最小 deflate 块 + 8 字节
 * CRC/长度尾）都不短于此——裸短头（3-4 字节魔数碎片）一律判否
 */
private const val GZIP_MIN_CHARS = 16

/** GZip 解压预览读取上限（字节）：只解头段做预览，不解整流（大流/炸弹流成本封顶） */
private const val GZIP_PREVIEW_BYTES = 256

/** 推荐行 meta 预览截断长度（压平换行后） */
private const val PREVIEW_MAX_CHARS = 48

private val JSON_PARSER = Json

/**
 * 剪贴板探测入口：null / 空白输入返回空表（宿主读不到剪贴板时静默无推荐）。
 * 结果按置信降序稳定排序；多探测器命中时高置信在前。
 */
fun detectClipboard(raw: String?): List<SmartDetection> {
    if (raw == null) return emptyList()
    val text = raw.trim()
    if (text.isEmpty()) return emptyList()
    if (text.length > MAX_INPUT_CHARS) return emptyList()
    val preview = buildPreview(text)
    val hits = listOfNotNull(
        detectXLogPath(text, preview),
        detectEpochTimestamp(text, preview),
        detectJsonObject(text, preview),
        detectBase64Text(text, preview),
        // gzip 魔数首字节 0x1F 属 trim 剥离域（ISO 控制符 0x1C-0x1F 计入
        // isWhitespace）——gzip 探测必须喂未 trim 原文，喂 trim 产物即恒漏报
        detectGzipData(raw),
        detectImageBase64(text),
    )
    // sortedByDescending 是稳定排序：同置信保持注册序（探测面演进不需再排第二键）
    return hits.sortedByDescending { it.confidence }
}

/** epoch 时间戳探测：整串恰 10 位（秒）或 13 位（毫秒）纯数字，且数值在现实时间窗内 */
fun detectEpochTimestamp(text: String, preview: String = buildPreview(text)): SmartDetection? {
    if (text.length != 10 && text.length != 13) return null
    if (text.any { !it.isDigit() }) return null
    val value = text.toLongOrNull() ?: return null
    val inRange = if (text.length == 10) {
        value in EPOCH_SECONDS_MIN..EPOCH_SECONDS_MAX
    } else {
        value in EPOCH_MILLIS_MIN..EPOCH_MILLIS_MAX
    }
    if (!inRange) return null
    // 秒/毫秒档的展示文案由 kind 在推荐行侧推导，预览承载原文——不构造中间文案；
    // 预填载荷 = 原文整串（时间戳页输入框消费）
    return SmartDetection(
        kind = SmartDetectionKind.EPOCH_TIMESTAMP,
        confidence = 90,
        preview = preview,
        prefillText = text,
    )
}

/**
 * `.xlog` / `.alog` 路径探测：单 token（零空白字符）、后缀整段匹配（大小写不敏感）。
 * 裸后缀（`.xlog`）/ 目录前缀结尾（`/tmp/.xlog`）/ 点结尾的 stem 均不收——
 * 后缀前必须有真实文件名主体。
 */
fun detectXLogPath(text: String, preview: String = buildPreview(text)): SmartDetection? {
    if (text.length > PATH_MAX_CHARS) return null
    if (text.any { it.isWhitespace() }) return null
    val lower = text.lowercase()
    val extLength = when {
        lower.endsWith(".xlog") -> 5
        lower.endsWith(".alog") -> 5
        else -> return null
    }
    val stem = text.dropLast(extLength)
    if (stem.isEmpty() || stem.last() == '/' || stem.last() == '\\' || stem.last() == '.') return null
    // 预填载荷 = 原文整串路径（XLog 解密页待确认面消费）
    return SmartDetection(
        kind = SmartDetectionKind.XLOG_PATH,
        confidence = 95,
        preview = preview,
        prefillText = text,
    )
}

/** JSON 对象探测：`{` 开头 `}` 结尾且可整体解析为 JSON 对象（数组不收——保守面） */
fun detectJsonObject(text: String, preview: String = buildPreview(text)): SmartDetection? {
    if (!text.startsWith("{") || !text.endsWith("}")) return null
    val parsed = runCatching { JSON_PARSER.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    // 预填载荷 = 原文整串（JSON 工具页输入框消费——粘贴即校验链）
    return SmartDetection(
        kind = SmartDetectionKind.JSON_OBJECT,
        confidence = 90,
        preview = preview,
        prefillText = text,
    )
}

/**
 * base64 探测：长 token（[BASE64_MIN_CHARS] 起）+ 纯 base64 字母表（标准或
 * URL-safe，混用两种字母表判否）+ 尾部至多 2 个 `=` 填充 + 解码成功且产物可读
 * （UTF-8 替换符/控制符占比 ≤ [BASE64_MAX_SUSPICIOUS_RATIO]）。
 * 图片魔数产物（二进制不可读）走 [detectImageBase64] 分流，两判型互斥。
 */
fun detectBase64Text(text: String, preview: String = buildPreview(text)): SmartDetection? {
    val decoded = decodeBase64Token(text) ?: return null
    if (decoded.isEmpty()) return null
    val decodedText = String(decoded, Charsets.UTF_8)
    val suspicious = decodedText.count { it == REPLACEMENT_CHAR || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }
    if (suspicious.toDouble() / decodedText.length > BASE64_MAX_SUSPICIOUS_RATIO) return null
    // 预填载荷 = token 原文（Base64 提取页粘贴区消费——页内嗅探链自行解码）
    return SmartDetection(
        kind = SmartDetectionKind.BASE64_TEXT,
        confidence = 75,
        preview = preview,
        prefillText = text,
    )
}

/**
 * 图片 base64 探测：与 [detectBase64Text] 同一接受域（长度档/字母表纯度/填充），
 * 判型分流点在解码产物——命中图片魔数（PNG/JPEG/GIF/WebP）即本类，可读文本
 * 归文本类，两判型互斥不双命中。Base64 提取工具页对图片产物有专门的内联预览
 * 消费面，故单列 kind 而非并入文本类。
 *
 * 魔数四档与 Base64 提取引擎的魔数嗅探同口径（单源真身：
 * `composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/base64/Base64ExtractorEngine.kt`
 * 的 `sniffImageFormat`——模块依赖方向不容 :core 反向共享，口径漂移由两侧单测
 * 共同锚定）。
 */
fun detectImageBase64(text: String): SmartDetection? {
    val decoded = decodeBase64Token(text) ?: return null
    val format = imageFormatLabelOf(decoded) ?: return null
    // 预填载荷 = base64 原文（与文本类同面——原文即 Base64 提取页合法输入，产物分流在页内嗅探链）
    return SmartDetection(
        kind = SmartDetectionKind.IMAGE_BASE64,
        confidence = 85,
        preview = "$format 图片 base64（${decoded.size} 字节）",
        prefillText = text,
    )
}

/**
 * base64 接受域判定 + 解码一步收口（文本/图片两判型共用）：长度档
 * [BASE64_MIN_CHARS]..[BASE64_MAX_CHARS]、字母表纯度（标准与 URL-safe 混用判否）、
 * 尾部至多 2 个 `=` 填充、解码失败判否。
 */
private fun decodeBase64Token(text: String): ByteArray? {
    if (text.length !in BASE64_MIN_CHARS..BASE64_MAX_CHARS) return null
    val hasStandardSymbols = '+' in text || '/' in text
    val hasUrlSafeSymbols = '-' in text || '_' in text
    if (hasStandardSymbols && hasUrlSafeSymbols) return null
    val body = text.trimEnd('=')
    if (text.length - body.length > 2) return null
    if ('=' in body) return null
    if (body.any { ch -> !ch.isBase64Char() }) return null
    val decoder = if (hasUrlSafeSymbols) Base64.getUrlDecoder() else Base64.getDecoder()
    return runCatching { decoder.decode(body) }.getOrNull()
}

/** 图片魔数四档（PNG/JPEG/GIF/WebP）：只看文件头不校验完整性 */
private fun imageFormatLabelOf(bytes: ByteArray): String? = when {
    bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "PNG"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte() -> "JPEG"
    bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" } -> "GIF"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "WebP"
    else -> null
}

/**
 * GZip 探测（文本通道承载二进制的场景——二进制查看器/复制工具把 gzip 字节按
 * Latin-1 塞进剪贴板）：魔数 `1F 8B` + 压缩方法 08（deflate）+ FLG 保留位为零
 * + 全字符 ≤ 0xFF（ISO-8859-1 可无损回字节的前提——超出即文本链路已损坏判否）
 * + 首段可解压。
 *
 * 预览语义 = 解压产物首段文本：gzip 原文是二进制不可读，直接展示无意义——
 * 解压首段（[GZIP_PREVIEW_BYTES] 封顶）才是用户关心的内容。命中推荐「解压」
 * 工具；无预填面（剪贴板承载的是内容字节而非路径，解压页输入面是文件拖入）。
 *
 * 入参契约：**未 trim 原文**（魔数首字节 0x1F 自身会被 trim 剥掉——见
 * [detectClipboard] 内警示）。首部空白包裹的 gzip 不收（宁可漏报）；尾部
 * 多余字节（换行等复制残留）无害——解压只读首段，不校验流尾。
 */
fun detectGzipData(text: String): SmartDetection? {
    if (text.length < GZIP_MIN_CHARS) return null
    if (text[0] != '\u001F' || text[1] != '\u008B' || text[2] != '\b') return null
    if (text[3].code and 0xE0 != 0) return null
    if (text.any { it.code > 0xFF }) return null
    val bytes = text.toByteArray(Charsets.ISO_8859_1)
    val head = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readNBytes(GZIP_PREVIEW_BYTES) }
    }.getOrNull() ?: return null
    if (head.isEmpty()) return null
    return SmartDetection(
        kind = SmartDetectionKind.GZIP_DATA,
        confidence = 95,
        preview = buildPreview(String(head, Charsets.UTF_8)),
    )
}

private const val REPLACEMENT_CHAR = '�'

/** base64 字母表并集（标准 + URL-safe 的字母数字部分；`+/-_` 由字母表混用判定先行收口） */
private fun Char.isBase64Char(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '+' || this == '/' ||
        this == '-' || this == '_'

/** 预览构造：换行压平为单空格 + 截断加省略号（多行 JSON/长 token 的行内展示形态） */
internal fun buildPreview(text: String): String {
    val flattened = text.replace('\n', ' ').replace('\r', ' ')
    return if (flattened.length <= PREVIEW_MAX_CHARS) flattened else flattened.take(PREVIEW_MAX_CHARS) + "…"
}
