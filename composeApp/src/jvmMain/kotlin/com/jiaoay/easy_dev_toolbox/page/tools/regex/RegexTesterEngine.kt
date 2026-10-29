package com.jiaoay.easy_dev_toolbox.page.tools.regex

import java.util.regex.Matcher
import java.util.regex.PatternSyntaxException

/**
 * 正则测试器引擎（纯函数面，java.util.regex 方言）。
 *
 * ## 动机
 * 写过滤正则的人正是写错正则损失最大的人——大日志跑一遍很贵。Tag 过滤 /
 * 工作台分析前先在此沙盒验证：编译回执 + 命中区间 + 分组捕获。
 *
 * ## 契约
 * - [compileRegex] 无状态无 IO：空模式回 [RegexCompileResult.Empty]（引导态，非错误）；
 *   无效正则回 [RegexCompileResult.Invalid]，message 含出错位置与人话修法提示，不静默。
 * - [findMatches] 命中区间用 [RegexMatch.range]（endInclusive 含端，供高亮渲染
 *   `start .. last + 1` 半开区间）；零宽匹配（如 `a*`、`\b`）照实返回，空区间以
 *   `first > last` 表达（Kotlin [kotlin.ranges.IntRange] 同口径）。
 * - 命中数硬上界 [MAX_MATCHES]：超限截断并以 [RegexMatches.truncated] 明示——
 *   超长样本把 UI 组合卡死的防线，截断必须可见不得静默吞命中。
 */
sealed interface RegexCompileResult {

    /** 空模式：引导态，面板不报错也不给命中 */
    data object Empty : RegexCompileResult

    /** 无效正则：message 含位置 + 修法提示（可操作回执） */
    data class Invalid(val message: String) : RegexCompileResult

    data class Valid(val regex: Regex) : RegexCompileResult
}

/** 单个捕获分组：[matched] = false 表示该分组在本次命中中未参与（可选分组未匹配） */
data class RegexGroup(val index: Int, val value: String, val matched: Boolean)

/** 单次命中：[range] 为半开区间换算的含端区间（零宽命中时 first > last） */
data class RegexMatch(val range: IntRange, val value: String, val groups: List<RegexGroup>)

/** 命中集合：[truncated] = true 表示超过 [MAX_MATCHES] 被截断 */
data class RegexMatches(val matches: List<RegexMatch>, val truncated: Boolean)

/** 命中数硬上界：超出即截断（[RegexMatches.truncated] 明示） */
const val MAX_MATCHES: Int = 2000

/**
 * 即时编译回执。正则原文即输入（不 trim——空格可能是有意的匹配目标，
 * 只有长度为 0 才算空模式）。
 */
fun compileRegex(pattern: String): RegexCompileResult = when {
    pattern.isEmpty() -> RegexCompileResult.Empty
    else -> try {
        RegexCompileResult.Valid(Regex(pattern))
    } catch (e: PatternSyntaxException) {
        RegexCompileResult.Invalid(regexInvalidMessage(e))
    }
}

/**
 * 全量命中扫描。
 *
 * 零宽命中（如 `a*`、`\b`）的续扫依赖 [Matcher.find] 原生语义：空命中后
 * 下一轮搜索位置自动前进一位（实测 JDK 行为，`a*` 在 "bab" 上命中
 * 空@0 / "a"@1 / 空@2 / 空@3 后自然终止）——不手工 region 干预，保持
 * 默认锚定语义不被改写。
 */
fun findMatches(regex: Regex, sample: String, limit: Int = MAX_MATCHES): RegexMatches {
    if (sample.isEmpty()) return RegexMatches(emptyList(), truncated = false)
    val matcher = regex.toPattern().matcher(sample)
    val found = ArrayList<RegexMatch>()
    var truncated = false
    while (matcher.find()) {
        if (found.size >= limit) {
            truncated = true
            break
        }
        found.add(matchOf(matcher))
    }
    return RegexMatches(found, truncated)
}

private fun matchOf(matcher: Matcher): RegexMatch {
    val groups = (1..matcher.groupCount()).map { i ->
        val raw = matcher.group(i)
        RegexGroup(index = i, value = raw ?: "", matched = raw != null)
    }
    return RegexMatch(
        range = IntRange(matcher.start(), matcher.end() - 1),
        value = matcher.group() ?: "",
        groups = groups,
    )
}

/**
 * 无效正则的可操作回执：出错位置（1 基字符位）+ 按错误类型给修法提示。
 * 不含正则原文回显（PatternSyntaxException 的原始 message 会整段回显模式串，
 * 长模式会淹没关键信息；description + index 已是定位三要素）。
 */
internal fun regexInvalidMessage(e: PatternSyntaxException): String {
    val hint = when {
        e.description.contains("Unclosed character class") -> "「[」未闭合"
        e.description.contains("Unclosed group") -> "「(」未闭合"
        e.description.contains("Unmatched closing") -> "存在多余的「)」"
        e.description.contains("Dangling meta character") -> "元字符（如 . * + ?）作字面量需转义，如 \\. 或放进字符类 [.]"
        e.description.contains("Illegal repetition") -> "重复符（* + ? {n}）前缺少可重复单元"
        else -> null
    }
    return buildString {
        append("正则无效：").append(e.description)
        if (e.index >= 0) append("（出错位置：第 ").append(e.index + 1).append(" 个字符附近）")
        if (hint != null) {
            append("——")
            append(hint)
            append("。")
        }
    }
}
