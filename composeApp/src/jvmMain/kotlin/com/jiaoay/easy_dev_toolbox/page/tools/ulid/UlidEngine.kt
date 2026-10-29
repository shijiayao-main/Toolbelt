package com.jiaoay.easy_dev_toolbox.page.tools.ulid

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ULID 引擎（纯函数面，按公开规格独立实现；规格来源 = GitHub `ulid/spec` 公开
 * 规范 README：Crockford Base32 编码，26 字符 = 48 位毫秒时间戳 + 80 位随机）。
 *
 * ## 动机
 * 日志关联排查场景：ULID 内嵌生成时刻的毫秒时间戳——拿到一串 ULID（本地数据库
 * 主键 / 链路标识）贴进来反解出生成时间；生成侧批量造带时间语义的测试数据。
 *
 * ## 契约
 * - [generateUlids] 批量生成：时间戳取注入参数（默认 `System.currentTimeMillis`），
 *   随机源为进程内共享 `java.security.SecureRandom`（规格明示「尽可能用加密强
 *   随机源」——本引擎恒用 SecureRandom，不提供降级弱随绕开关）；
 * - 同一毫秒内多次生成**不保证有序**：规格单调性（同毫秒随机部分 +1）是可选项，
 *   本引擎不做递增保证——排序场景由消费方按字符串字典序自行排；
 * - [parseUlid] 解析：大小写不敏感（规格「Case insensitive」口径）；字母表外
 *   字符显式拒绝——规格字母表（Crockford Base32）排除 I/L/O/U 防混淆，两形
 *   （含小写）皆非法并逐字符定位回执；连字符非 26 位标准形态，显式拒绝；
 * - 时间戳越界拒绝：26 字符解码值可表达 50 位，超过 48 位上限（首字符 > 7）
 *   的输入显式拒绝——规格要求所有实现拒绝超出 [MAX_ULID_TIMESTAMP_MILLIS]
 *   的值，防溢出 bug；
 * - 人类时间展开为 `yyyy-MM-dd HH:mm:ss.SSS`（时间戳页「标准」格式同口径，
 *   毫秒精度保留——ULID 时间戳粒度即毫秒）；时区参数化（默认本地），引擎内
 *   不读系统时钟，测试可注入定点。
 *
 * ## 硬上界
 * - 单次批量生成上限 [MAX_ULID_GENERATE_COUNT]，超出由 require 拒绝
 *   （防 UI 一次展开海量行）；
 * - [encodeUlid] 的注入参数越界（负数 / 超 48 位 / 随机字节数 ≠ 10）由
 *   require 拒绝——生成链只经它过合法值，解析链不经过它。
 */
sealed interface UlidParseResult {

    /** 空白输入：引导态，面板不报错不产出 */
    data object Empty : UlidParseResult

    /** 显式失败：reason 定位到字符位与违反的规则条目 */
    data class Invalid(val reason: String) : UlidParseResult

    /** 成功：时间戳毫秒 + 人类时间 + 随机部分 hex（20 位小写）+ 大写标准形态 */
    data class Ok(
        val canonical: String,
        val timestampMillis: Long,
        val humanTime: String,
        val randomHex: String,
    ) : UlidParseResult
}

/** 48 位毫秒时间戳上限（2^48 - 1 = 281474976710655——最大合法 ULID 为 `7ZZZZZZZZZZZZZZZZZZZZZZZZZ`） */
const val MAX_ULID_TIMESTAMP_MILLIS: Long = 281_474_976_710_655L

/** 单次批量生成上限：超出由 [generateUlids] 拒绝 */
const val MAX_ULID_GENERATE_COUNT: Int = 100

/** ULID 标准长度：10 位时间戳字符 + 16 位随机字符 */
private const val ULID_LENGTH = 26

/** 时间戳部分字符数（10 字符 × 5 位 = 50 位表达 48 位值，首字符恒 < 8） */
private const val TIMESTAMP_CHARS = 10

/** 随机部分字符数（16 字符 × 5 位 = 80 位） */
private const val RANDOM_CHARS = 16

/** 随机部分字节数（80 位） */
private const val RANDOM_BYTES = 10

/** Crockford Base32 字母表（规格编码真源；排除 I/L/O/U 防混淆） */
private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/**
 * 大写字母 → Crockford 值映射（26 槽，-1 = 规格排除字母）；解码大小写不敏感，
 * 小写按同槽映射。
 */
private val CROCKFORD_LETTER_INDEX = intArrayOf(
    10, 11, 12, 13, 14, 15, 16, 17, -1, 18, 19, -1, 20, 21, -1, 22, 23, 24, 25, 26, -1, 27, 28, 29, 30, 31,
)

/** 进程内共享加密强随机源（规格推荐口径；SecureRandom 线程安全） */
private val secureRandom = SecureRandom()

/** 人类时间格式（本地日志时区同口径，毫秒精度） */
private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/**
 * 批量生成 [count] 个 ULID：全部使用同一 [timestampMillis]（默认当前毫秒），
 * 随机部分各自独立取 `SecureRandom`。
 *
 * count 须在 1..[MAX_ULID_GENERATE_COUNT]；timestampMillis 须在
 * 0..[MAX_ULID_TIMESTAMP_MILLIS]（越界 require 拒绝）。
 */
fun generateUlids(
    count: Int,
    timestampMillis: Long = System.currentTimeMillis(),
): List<String> {
    require(count in 1..MAX_ULID_GENERATE_COUNT) { "count 须在 1..$MAX_ULID_GENERATE_COUNT——实际 $count" }
    require(timestampMillis in 0..MAX_ULID_TIMESTAMP_MILLIS) {
        "timestampMillis 须在 0..$MAX_ULID_TIMESTAMP_MILLIS——实际 $timestampMillis"
    }
    return List(count) {
        val randomBytes = ByteArray(RANDOM_BYTES)
        secureRandom.nextBytes(randomBytes)
        encodeUlid(timestampMillis, randomBytes)
    }
}

/**
 * 确定性编码：48 位毫秒时间戳 + 10 随机字节 → 26 字符标准形态（大写）。
 * 注入参数越界（负时间戳 / 超 48 位 / 字节数 ≠ 10）由 require 拒绝。
 */
fun encodeUlid(timestampMillis: Long, randomBytes: ByteArray): String {
    require(timestampMillis in 0..MAX_ULID_TIMESTAMP_MILLIS) {
        "timestampMillis 须在 0..$MAX_ULID_TIMESTAMP_MILLIS——实际 $timestampMillis"
    }
    require(randomBytes.size == RANDOM_BYTES) { "randomBytes 须为 $RANDOM_BYTES 字节——实际 ${randomBytes.size}" }
    val timestampChars = CharArray(TIMESTAMP_CHARS)
    var value = timestampMillis
    for (i in TIMESTAMP_CHARS - 1 downTo 0) {
        timestampChars[i] = CROCKFORD_ALPHABET[(value and 0x1F).toInt()]
        value = value ushr 5
    }
    val randomChars = CharArray(RANDOM_CHARS)
    for (i in 0 until RANDOM_CHARS) {
        var digit = 0
        for (bit in 0 until 5) {
            val absoluteBit = i * 5 + bit
            digit = (digit shl 1) or ((randomBytes[absoluteBit / 8].toInt() shr (7 - absoluteBit % 8)) and 1)
        }
        randomChars[i] = CROCKFORD_ALPHABET[digit]
    }
    return String(timestampChars) + String(randomChars)
}

/**
 * 解析 ULID：26 位标准形态 → 时间戳毫秒 + 人类时间 + 随机部分 hex。
 * [zone] 是人类时间展开时区（默认本地）。大小写不敏感；一切非法输入显式
 * [UlidParseResult.Invalid] 携带逐字符定位回执，不静默。
 */
fun parseUlid(
    input: String,
    zone: ZoneId = ZoneId.systemDefault(),
): UlidParseResult {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return UlidParseResult.Empty
    if ('-' in trimmed) {
        return UlidParseResult.Invalid("连字符不是 ULID 标准形态——规格只定义 26 位无分隔形态，去掉连字符后再贴")
    }
    if (trimmed.length != ULID_LENGTH) {
        return UlidParseResult.Invalid("ULID 须为 $ULID_LENGTH 位字符（10 位时间戳 + 16 位随机）——实际 ${trimmed.length} 位")
    }
    var timestamp = 0L
    for (i in 0 until TIMESTAMP_CHARS) {
        val digit = decodeCrockfordChar(trimmed[i]) ?: return UlidParseResult.Invalid(illegalCharReason(trimmed[i], i))
        timestamp = timestamp * 32 + digit
    }
    if (timestamp > MAX_ULID_TIMESTAMP_MILLIS) {
        return UlidParseResult.Invalid(
            "时间戳超出 48 位上限 $MAX_ULID_TIMESTAMP_MILLIS（2^48 - 1 毫秒）——规格要求拒绝一切越界值，" +
                "最大合法 ULID 为 7ZZZZZZZZZZZZZZZZZZZZZZZZZ",
        )
    }
    val randomBytes = ByteArray(RANDOM_BYTES)
    for (i in 0 until RANDOM_CHARS) {
        val digit = decodeCrockfordChar(trimmed[TIMESTAMP_CHARS + i])
            ?: return UlidParseResult.Invalid(illegalCharReason(trimmed[TIMESTAMP_CHARS + i], TIMESTAMP_CHARS + i))
        for (bit in 0 until 5) {
            val absoluteBit = i * 5 + bit
            if ((digit shr (4 - bit)) and 1 == 1) {
                randomBytes[absoluteBit / 8] = (randomBytes[absoluteBit / 8].toInt() or (1 shl (7 - absoluteBit % 8))).toByte()
            }
        }
    }
    return UlidParseResult.Ok(
        canonical = trimmed.uppercase(),
        timestampMillis = timestamp,
        humanTime = "${timeFormatter.withZone(zone).format(Instant.ofEpochMilli(timestamp))}（${zone.id}）",
        randomHex = randomBytes.joinToString("") { "%02x".format(it) },
    )
}

/** 单字符解码：合法回 0..31；字母表外（含被排除字母与一切非字母数字）回 null */
private fun decodeCrockfordChar(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'A'..'Z' -> CROCKFORD_LETTER_INDEX[c - 'A'].takeIf { it >= 0 }
    in 'a'..'z' -> CROCKFORD_LETTER_INDEX[c - 'a'].takeIf { it >= 0 }
    else -> null
}

/** 逐字符定位的非法回执：被排除字母（I/L/O/U）与一般非法字符分话术 */
private fun illegalCharReason(c: Char, position: Int): String =
    if (c.uppercaseChar() in "ILOU") {
        "第 ${position + 1} 位字符「$c」非法——规格字母表（Crockford Base32）排除 I/L/O/U 防混淆，大小写两形皆不可用"
    } else {
        "第 ${position + 1} 位字符「$c」不在 ULID 字母表（Crockford Base32 的 0-9 与除 I/L/O/U 外的 A-Z，大小写均可）"
    }
