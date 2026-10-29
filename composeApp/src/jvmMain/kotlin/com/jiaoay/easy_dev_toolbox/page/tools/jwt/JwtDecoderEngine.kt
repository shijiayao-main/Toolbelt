package com.jiaoay.easy_dev_toolbox.page.tools.jwt

import com.jiaoay.easy_dev_toolbox.page.tools.json.formatJson
import com.jiaoay.easy_dev_toolbox.page.tools.json.jsonInvalidMessage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * JWT 解码引擎（纯函数面，kotlinx.serialization 单源——JSON 解析复用 JSON 工具
 * 引擎同栈）。
 *
 * ## 动机
 * 云端下发/token 过期判读场景：抓包或日志里拿到一串 JWT，肉眼看是三段 base64url，
 * exp/iat 是 Unix 秒——本引擎把 header/payload 解码成 JSON 明文并展开时间声明。
 *
 * ## 契约（解析器非验证器）
 * 本引擎**只解码不验签**：没有任何密钥验证，签名段仅原样展示。它不能给出
 * 「签名有效」的判断——据此信任令牌内容是误用；验签须由签发方密钥的验证服务承担。
 *
 * - [decodeJwt] 回 [JwtDecodeResult]：空白输入回 [JwtDecodeResult.Empty]（引导态
 *   非错误）；一切失败显式 [JwtDecodeResult.Invalid] 携带定位到段的人话 reason
 *   （坏段指明第几段、是 header 还是 payload），不静默。
 * - 段数契约：JWS 三段（header.payload.signature）。五段是加密令牌（JWE）——
 *   payload 本身是密文，解码无意义，显式拒绝并说明；其余段数同显式拒绝。
 * - 时间声明（exp / iat / nbf）：payload 中的整数秒展开为 `yyyy-MM-dd HH:mm:ss`
 *   本地时区人类时间；exp 给过期判定、nbf 给生效判定（判定基准 [nowEpochSeconds]
 *   参数化——默认当前时间，测试可注入定点）。键存在但值非整数秒时显式回执非静默跳过。
 *
 * ## 硬上界
 * 输入超过 [MAX_JWT_INPUT_CHARS] 拒绝解码并明示（超长粘贴的逐键解码阻塞 UI 线程）。
 */

/** 解码结果三分支：空白引导态 / 定位到段的人话失败 / 明文展开成功 */
sealed interface JwtDecodeResult {

    /** 空白输入：引导态，面板不报错不产出 */
    data object Empty : JwtDecodeResult

    /** 显式失败：reason 指明第几段（header/payload）与原因 */
    data class Invalid(val reason: String) : JwtDecodeResult

    /** 成功：header/payload 已格式化 JSON 明文；[signatureSegment] 原样未验证 */
    data class Ok(
        val headerJson: String,
        val payloadJson: String,
        val signatureSegment: String,
        val timeClaims: List<JwtTimeClaim>,
    ) : JwtDecodeResult
}

/** payload 中的时间声明展开项 */
data class JwtTimeClaim(
    /** 声明名（exp / iat / nbf） */
    val name: String,
    /** 原 Unix 秒（值非整数秒时为 null，看 [humanTime] 回执） */
    val rawSeconds: Long?,
    /** 人类时间（`yyyy-MM-dd HH:mm:ss` + 时区；值非整数秒时为说明回执） */
    val humanTime: String,
    /** 判定（exp 过期判定 / nbf 生效判定；iat 与非数值项为 null） */
    val verdict: String?,
)

/** 输入字符上限：超出由 [decodeJwt] 显式拒绝（超长粘贴逐键解码阻塞 UI） */
const val MAX_JWT_INPUT_CHARS: Int = 1_000_000

/** JWS 三段的段名（1 基序，错误回执定位用） */
private val SEGMENT_NAMES = listOf("header", "payload", "signature")

/** 时间声明展开顺序（exp 判读优先——过期判读是主场景） */
private val TIME_CLAIM_NAMES = listOf("exp", "iat", "nbf")

/** 人类时间格式（本地时区——面板判读与日志时区同口径） */
private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * 解码 JWT：三段拆分 → base64url 解码 → JSON 明文 → 时间声明展开。
 * [nowEpochSeconds] 是 exp/nbf 判定的基准（默认当前时间，秒）；[zone] 是人类时间
 * 展开时区（默认本地）。两者参数化——引擎内不读系统时钟，测试可注入定点。
 */
fun decodeJwt(
    token: String,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    zone: ZoneId = ZoneId.systemDefault(),
): JwtDecodeResult {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return JwtDecodeResult.Empty
    if (trimmed.length > MAX_JWT_INPUT_CHARS) {
        return JwtDecodeResult.Invalid("输入 ${trimmed.length} 字符超过上限 $MAX_JWT_INPUT_CHARS——拒绝解码（超长粘贴逐键解码会阻塞界面）")
    }
    val segments = trimmed.split('.')
    when (segments.size) {
        3 -> Unit
        5 -> return JwtDecodeResult.Invalid(
            "五段令牌是加密令牌（JWE）——payload 本身是密文，解码无意义；本工具只解明文 JWS（header.payload.signature 三段）",
        )
        else -> return JwtDecodeResult.Invalid(
            "JWT 须为 header.payload.signature 三段（以「.」分隔）——实际 ${segments.size} 段",
        )
    }
    val headerOutcome = decodeSegmentJson(segments[0], segmentIndex = 1)
    val headerElement = headerOutcome.element
        ?: return headerOutcome.error ?: JwtDecodeResult.Invalid("第 1 段（header）解码失败")
    headerElement as? JsonObject
        ?: return JwtDecodeResult.Invalid("第 1 段（header）JSON 须为对象——实际是 ${headerElement::class.simpleName}")
    val payloadOutcome = decodeSegmentJson(segments[1], segmentIndex = 2)
    val payloadElement = payloadOutcome.element
        ?: return payloadOutcome.error ?: JwtDecodeResult.Invalid("第 2 段（payload）解码失败")

    val payloadObject = payloadElement as? JsonObject
        ?: return JwtDecodeResult.Invalid("第 2 段（payload）JSON 须为对象——实际是 ${payloadElement::class.simpleName}")

    return JwtDecodeResult.Ok(
        headerJson = formatJson(headerElement),
        payloadJson = formatJson(payloadElement),
        signatureSegment = segments[2],
        timeClaims = extractTimeClaims(payloadObject, nowEpochSeconds, zone),
    )
}

// ── 段解码（错误信息带段定位，逐段短路）──

/** 单段解码结果：成功携带元素；失败时 [error] 携带定位到段的人话回执 */
private data class SegmentDecodeOutcome(val element: kotlinx.serialization.json.JsonElement?, val error: JwtDecodeResult.Invalid?)

private fun decodeSegmentJson(segment: String, segmentIndex: Int): SegmentDecodeOutcome {
    val name = SEGMENT_NAMES[segmentIndex - 1]
    if (segment.isEmpty()) {
        return SegmentDecodeOutcome(null, JwtDecodeResult.Invalid("第 $segmentIndex 段（$name）为空段——段内容缺失"))
    }
    val bytes = try {
        java.util.Base64.getUrlDecoder().decode(padBase64Url(segment))
    } catch (e: IllegalArgumentException) {
        return SegmentDecodeOutcome(
            null,
            JwtDecodeResult.Invalid("第 $segmentIndex 段（$name）base64url 解码失败——${e.message ?: "字符集或长度非法"}"),
        )
    }
    val text = String(bytes, Charsets.UTF_8)
    return try {
        SegmentDecodeOutcome(Json.parseToJsonElement(text), null)
    } catch (e: SerializationException) {
        SegmentDecodeOutcome(null, JwtDecodeResult.Invalid("第 $segmentIndex 段（$name）不是合法 JSON——${jsonInvalidMessage(text, e)}"))
    }
}

/** base64url 无 padding 形态补齐到 4 的倍数（JWT 规范省 padding，解码器要求补齐） */
private fun padBase64Url(segment: String): String {
    val remainder = segment.length % 4
    return if (remainder == 0) segment else segment + "=".repeat(4 - remainder)
}

/** payload 时间声明展开：数值项人类时间 + 判定；非数值项显式回执不静默跳过 */
private fun extractTimeClaims(
    payload: JsonObject,
    nowEpochSeconds: Long,
    zone: ZoneId,
): List<JwtTimeClaim> = TIME_CLAIM_NAMES.mapNotNull { name ->
    val value = payload[name] ?: return@mapNotNull null
    val primitive = value as? JsonPrimitive
        ?: return@mapNotNull JwtTimeClaim(
            name = name,
            rawSeconds = null,
            humanTime = "值非整数秒（JSON ${value::class.simpleName} 类型）",
            verdict = null,
        )
    val seconds = primitive.content.toLongOrNull()
        ?: return@mapNotNull JwtTimeClaim(
            name = name,
            rawSeconds = null,
            humanTime = "值非整数秒（原样：「${primitive.content}」）",
            verdict = null,
        )
    // Instant 秒界 ±31556889864403199：17~18 位整数十进制合法、toLongOrNull 收得下，
    // 直接展开会抛 DateTimeException 穿透组合期（面板 remember 直调本引擎）——前置拦截
    if (seconds > Instant.MAX.epochSecond || seconds < Instant.MIN.epochSecond) {
        return@mapNotNull JwtTimeClaim(
            name = name,
            rawSeconds = seconds,
            humanTime = "时间戳超出可表达范围（$seconds）",
            verdict = null,
        )
    }
    JwtTimeClaim(
        name = name,
        rawSeconds = seconds,
        humanTime = humanTime(seconds, zone),
        verdict = when (name) {
            "exp" -> if (nowEpochSeconds >= seconds) {
                "已过期（${humanizeSeconds(nowEpochSeconds - seconds)}前过期）"
            } else {
                "未过期（剩余 ${humanizeSeconds(seconds - nowEpochSeconds)}）"
            }
            "nbf" -> if (nowEpochSeconds < seconds) {
                "尚未生效（${humanizeSeconds(seconds - nowEpochSeconds)}后生效）"
            } else {
                "已生效"
            }
            else -> null
        },
    )
}

/**
 * Unix 秒 → `yyyy-MM-dd HH:mm:ss（时区）`。秒值已在 [extractTimeClaims] 过 Instant 秒界
 * 前置拦截；秒界内的值仍可能越格式化器的 EpochDay 界（两界不同宽）——runCatching
 * 折叠为人话回执，异常不穿透组合期。
 */
private fun humanTime(seconds: Long, zone: ZoneId): String = try {
    "${timeFormatter.withZone(zone).format(Instant.ofEpochSecond(seconds))}（${zone.id}）"
} catch (e: java.time.DateTimeException) {
    "时间戳超出可表达范围（$seconds）"
}

/** 秒数 → 人话时长（向下取整到秒/分/时/天，判读粒度到天为止） */
private fun humanizeSeconds(seconds: Long): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> "${seconds / 60} 分钟"
    seconds < 86_400 -> "${seconds / 3600} 小时"
    else -> "${seconds / 86_400} 天"
}
