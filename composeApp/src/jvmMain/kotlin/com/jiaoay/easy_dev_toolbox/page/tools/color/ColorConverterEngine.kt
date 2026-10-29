package com.jiaoay.easy_dev_toolbox.page.tools.color

/**
 * Android 颜色三向转换引擎（纯函数面，无 IO、无 Compose 依赖）。
 *
 * ## 动机
 * 设备 UI/HUD 调日志颜色值场景：代码里是 `0xFF1B6EF3` 字面量、logcat/HUD 打出的
 * 是带符号 int（如 `-14979341`）、设计稿给的是 `#FF1B6EF3` 或 `(27, 110, 243)`——
 * 四种口径肉眼互算容易错，本引擎收口为单一 [ColorComponents] 中间态做三向互转。
 *
 * ## 契约
 * - 三个解析入口各管一种输入形态，均回 [ColorParseResult]：空白输入回
 *   [ColorParseResult.Empty]（引导态非错误）；非法输入回
 *   [ColorParseResult.Invalid] 携带人话 reason（说清哪里错、合法形态是什么），不静默。
 * - [parseArgbIntText]：`0x`/`#` 前缀按十六进制；含十六进制字母（A-F）且无负号
 *   按十六进制；纯数字按十进制（允许负数——Android `Color.toInt()` 的带符号
 *   打印形态）。值域 0..0xFFFFFFFF，超出显式拒绝。
 * - [parseHexText]：6 位（alpha 默认 FF）或 8 位十六进制（带 `#` 或裸写均可，
 *   大小写不敏感）；3/4/5/7 位等其余长度显式拒绝（不猜短写展开规则）。
 * - [parseRgbText]：`(r, g, b)` 或 `(a, r, g, b)`——括号可省，逗号分隔，
 *   每项 0..255（越界/非整数/分量数不对均人话回执）。
 * - 格式化出口：[toHexLiteral]（`0xFF1B6EF3`，代码字面量口径）、[toHexHash]
 *   （`#FF1B6EF3`，设计稿口径）、[toRgbTriple] / [toArgbTriple]（alpha 非 FF 时
 *   用四分量形态保真）、[toSignedInt]（带符号 int，logcat 打印口径）。
 */

/** ARGB 各分量恒 0..255（解析出口已校验，格式化入口可直接位移） */
data class ColorComponents(val alpha: Int, val red: Int, val green: Int, val blue: Int) {

    /** 打包为 Android ARGB int（带符号 32 位——logcat/`Color.toInt()` 同口径） */
    fun toArgbInt(): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

/** 解析结果三分支：空白引导态 / 人话失败 / 成功分量 */
sealed interface ColorParseResult {

    /** 空白输入：引导态，面板不报错不产出 */
    data object Empty : ColorParseResult

    /** 非法输入：reason 说清哪里错与合法形态 */
    data class Invalid(val reason: String) : ColorParseResult

    /** 解析成功：0..255 已校验的四分量 */
    data class Ok(val components: ColorComponents) : ColorParseResult
}

/**
 * 解析 ARGB int 文本：`0xFF1B6EF3` / `#FF1B6EF3` / `FF1B6EF3`（十六进制）或
 * `-14979341` / `4279987955`（十进制，带符号/无符号均可）。纯数字串按十进制——
 * 想按十六进制须带 `0x`/`#` 前缀或含 A-F 字母。
 */
fun parseArgbIntText(text: String): ColorParseResult {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ColorParseResult.Empty
    val isHex = trimmed.startsWith("0x", ignoreCase = true) ||
        trimmed.startsWith("#") ||
        (trimmed.any { it in 'a'..'f' || it in 'A'..'F' })
    val cleaned = trimmed.removePrefix("#")
        .removePrefix("0x").removePrefix("0X")
        .removePrefix("x").removePrefix("X")
    if (isHex) {
        if (cleaned.isEmpty() || cleaned.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
            return ColorParseResult.Invalid("ARGB int 十六进制形态须为 0..8 位十六进制字符（如 0xFF1B6EF3）——「$trimmed」含非法字符")
        }
        val value = cleaned.toULongOrNull(16)
            ?: return ColorParseResult.Invalid("ARGB int 超出 32 位值域（0..0xFFFFFFFF）——「$trimmed」")
        if (value > 0xFFFFFFFFUL) {
            return ColorParseResult.Invalid("ARGB int 超出 32 位值域（0..0xFFFFFFFF）——「$trimmed」")
        }
        return ColorParseResult.Ok(value.toColorComponents())
    }
    val value = trimmed.toLongOrNull()
        ?: return ColorParseResult.Invalid("ARGB int 十进制须为 -2147483648..4294967295 的整数——「$trimmed」不是有效整数")
    return if (value >= Int.MIN_VALUE && value <= 0xFFFFFFFFL) {
        ColorParseResult.Ok(value.toColorComponents())
    } else {
        return ColorParseResult.Invalid("ARGB int 十进制超出 32 位 ARGB 值域（-2147483648..4294967295）——「$trimmed」")
    }
}

/**
 * 解析 #hex 文本：6 位（alpha 默认 FF——`#1B6EF3` 与 `#FF1B6EF3` 同色）或
 * 8 位（含 alpha）；`#` 可省、大小写不敏感；其余长度显式拒绝不猜短写。
 */
fun parseHexText(text: String): ColorParseResult {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ColorParseResult.Empty
    val cleaned = trimmed.removePrefix("#")
    if (cleaned.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
        return ColorParseResult.Invalid("hex 颜色须为十六进制字符（0-9/A-F）——「$trimmed」含非法字符")
    }
    return when (cleaned.length) {
        6 -> ColorParseResult.Ok(ColorComponents(alpha = 255, red = cleaned.hexAt(0), green = cleaned.hexAt(2), blue = cleaned.hexAt(4)))
        8 -> ColorParseResult.Ok(
            ColorComponents(
                alpha = cleaned.hexAt(0),
                red = cleaned.hexAt(2),
                green = cleaned.hexAt(4),
                blue = cleaned.hexAt(6),
            ),
        )
        3, 4, 5, 7 -> ColorParseResult.Invalid(
            "hex 颜色须为 6 位（如 #1B6EF3，alpha 默认 FF）或 8 位（如 #FF1B6EF3）——「$trimmed」是 ${cleaned.length} 位，不支持短写展开",
        )
        else -> ColorParseResult.Invalid(
            "hex 颜色须为 6 位（如 #1B6EF3，alpha 默认 FF）或 8 位（如 #FF1B6EF3）——「$trimmed」是 ${cleaned.length} 位",
        )
    }
}

/**
 * 解析 (r, g, b) / (a, r, g, b) 文本：外层圆/方括号可省，逗号分隔（空格容忍），
 * 分量须为 0..255 整数；分量数 3 或 4（3 分量 alpha 默认 FF）。
 */
fun parseRgbText(text: String): ColorParseResult {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ColorParseResult.Empty
    val cleaned = trimmed
        .removePrefix("(").removeSuffix(")")
        .removePrefix("[").removeSuffix("]")
        .trim()
    if (cleaned.isEmpty()) return ColorParseResult.Empty
    val parts = cleaned.split(',').map { it.trim() }
    if (parts.any { it.isEmpty() }) {
        return ColorParseResult.Invalid("RGB 分量为逗号分隔的整数（如 27, 110, 243）——「$trimmed」有空分量")
    }
    val numbers = IntArray(parts.size)
    for ((index, part) in parts.withIndex()) {
        val value = part.toIntOrNull()
            ?: return ColorParseResult.Invalid("RGB 分量须为 0..255 整数——第 ${index + 1} 个分量「$part」不是整数")
        if (value !in 0..255) {
            return ColorParseResult.Invalid("RGB 分量须为 0..255 整数——第 ${index + 1} 个分量「$part」越界")
        }
        numbers[index] = value
    }
    return when (numbers.size) {
        3 -> ColorParseResult.Ok(ColorComponents(alpha = 255, red = numbers[0], green = numbers[1], blue = numbers[2]))
        4 -> ColorParseResult.Ok(ColorComponents(alpha = numbers[0], red = numbers[1], green = numbers[2], blue = numbers[3]))
        else -> ColorParseResult.Invalid("RGB 分量数须为 3（r,g,b）或 4（a,r,g,b）——「$trimmed」有 ${numbers.size} 个分量")
    }
}

// ── 格式化出口（分量已校验 0..255，纯位移无失败面）──

/** `0xFF1B6EF3`——代码字面量口径（大写） */
fun toHexLiteral(components: ColorComponents): String = "0x${components.toArgbInt().toUInt().toHex8()}"

/** `#FF1B6EF3`——设计稿口径（大写，恒 8 位含 alpha） */
fun toHexHash(components: ColorComponents): String = "#${components.toArgbInt().toUInt().toHex8()}"

/** `(27, 110, 243)`——RGB 三分量（alpha 不在此口径，见 [toArgbTriple]） */
fun toRgbTriple(components: ColorComponents): String =
    "(${components.red}, ${components.green}, ${components.blue})"

/** `(255, 27, 110, 243)`——ARGB 四分量（alpha 非 FF 时保真用） */
fun toArgbTriple(components: ColorComponents): String =
    "(${components.alpha}, ${components.red}, ${components.green}, ${components.blue})"

/** 带符号 int——logcat/Android `Color` 打印口径（如 -14979341） */
fun toSignedInt(components: ColorComponents): Int = components.toArgbInt()

/** 无符号 32 位 → 8 位大写十六进制（零填充） */
private fun UInt.toHex8(): String = "%08X".format(this.toLong())

// ── 内部工具 ──

/** [value]（带符号 int 值域，负数按 2^32 环回）拆四分量 */
private fun Long.toColorComponents(): ColorComponents {
    val unsigned = if (this < 0) this + 0x1_0000_0000L else this
    return ColorComponents(
        alpha = ((unsigned ushr 24) and 0xFF).toInt(),
        red = ((unsigned ushr 16) and 0xFF).toInt(),
        green = ((unsigned ushr 8) and 0xFF).toInt(),
        blue = (unsigned and 0xFF).toInt(),
    )
}

/** [value]（0..0xFFFFFFFF）拆四分量 */
private fun ULong.toColorComponents(): ColorComponents = ColorComponents(
    alpha = ((this shr 24) and 0xFFu).toInt(),
    red = ((this shr 16) and 0xFFu).toInt(),
    green = ((this shr 8) and 0xFFu).toInt(),
    blue = (this and 0xFFu).toInt(),
)

/** 十六进制串从 [offset] 起 2 位 → 0..255 分量 */
private fun String.hexAt(offset: Int): Int = substring(offset, offset + 2).toInt(16)
