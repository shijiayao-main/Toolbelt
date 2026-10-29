package com.jiaoay.easy_dev_toolbox.page.logcat

import java.util.regex.PatternSyntaxException

/**
 * logcat 行级谓词树：统一过滤模型的内核。两类输入面（旧裸串构造、查询语法）都编译到
 * 本树；消费面单通道 [LogcatLineMatcher.matches]（引擎行过滤）。武装捕获正则是独立
 * 判定通道，不经本模型——查询只进过滤，永不触发武装。
 *
 * 命中契约（[matchesParsed] 为唯一完整裁决入口）：
 * - 树内不含任何行级原子（[MatchAll]）→ 恒命中，含不可解析行；
 * - 行不可解析（级别与 tag 双 null）且树内含任一原子 → 不命中（宁拒不猜：只留可
 *   确认命中的行，不可确认即丢弃。msg 原子同理是自然延伸不是遗漏——不可解析行无
 *   消息体可确认）；
 * - 可解析行按 [matches] 判定：tag 原子看解析出的 tag，级别原子看解析出的级别，
 *   msg 原子看解析出的 message 余部（边界契约见 [LogcatLineMatcher.ParsedLine]）。
 */
internal sealed interface LogcatLinePredicate {

    /** 树内是否含任一行级原子（tag/级别/消息体）——不可解析行的裁决依据 */
    fun hasConstraint(): Boolean

    /** 可解析行的判定（入参经 [matchesParsed] 保证 level/tag 至少一非空，实现方仍须自理 null） */
    fun matches(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean

    /** 完整命中裁决：不可解析行（level/tag 双 null）按 [hasConstraint] 拒收，可解析行按 [matches] 判定 */
    fun matchesParsed(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean =
        if (lineLevel == null && lineTag == null) !hasConstraint() else matches(lineLevel, lineTag, lineMessage)
}

internal data object MatchAll : LogcatLinePredicate {
    override fun hasConstraint(): Boolean = false
    override fun matches(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean = true
}

/** tag 包含原子：忽略大小写由匹配时 contains(ignoreCase) 保证 */
internal data class TagContains(private val needle: String) : LogcatLinePredicate {
    override fun hasConstraint(): Boolean = true
    override fun matches(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean =
        lineTag != null && lineTag.contains(needle, ignoreCase = true)
}

/** tag 正则原子：containsMatchIn 子串命中（grep 语义，非整串全匹配），大小写敏感 */
internal data class TagRegex(private val pattern: Regex) : LogcatLinePredicate {
    override fun hasConstraint(): Boolean = true
    override fun matches(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean =
        lineTag != null && pattern.containsMatchIn(lineTag)
}

/**
 * 消息体包含原子（与 [TagContains] 同族）：忽略大小写由匹配时 contains(ignoreCase)
 * 保证，匹配域 = [LogcatLineMatcher.ParsedLine.message] 余部（「TAG: 」分隔符后，
 * 不含 tag 本身）。
 */
internal data class MsgContains(private val needle: String) : LogcatLinePredicate {
    override fun hasConstraint(): Boolean = true
    override fun matches(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean =
        lineMessage != null && lineMessage.contains(needle, ignoreCase = true)
}

/** 消息体正则原子（与 [TagRegex] 同族）：containsMatchIn 子串命中（grep 语义），大小写敏感 */
internal data class MsgRegex(private val pattern: Regex) : LogcatLinePredicate {
    override fun hasConstraint(): Boolean = true
    override fun matches(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean =
        lineMessage != null && pattern.containsMatchIn(lineMessage)
}

/** 级别档原子：行级别严重度 ≥ [level]（ordinal 序即严重度序） */
internal data class MinLevel(internal val level: LogcatLevel) : LogcatLinePredicate {
    override fun hasConstraint(): Boolean = true
    override fun matches(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean =
        lineLevel != null && lineLevel.ordinal >= level.ordinal
}

/** 排除（非）：约束存在性透传内层——排除项含原子时不可解析行同样拒收 */
internal data class Not(private val inner: LogcatLinePredicate) : LogcatLinePredicate {
    override fun hasConstraint(): Boolean = inner.hasConstraint()
    override fun matches(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean =
        !inner.matches(lineLevel, lineTag, lineMessage)
}

internal data class And(
    private val left: LogcatLinePredicate,
    private val right: LogcatLinePredicate,
) : LogcatLinePredicate {
    override fun hasConstraint(): Boolean = left.hasConstraint() || right.hasConstraint()
    override fun matches(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean =
        left.matches(lineLevel, lineTag, lineMessage) && right.matches(lineLevel, lineTag, lineMessage)
}

internal data class Or(
    private val left: LogcatLinePredicate,
    private val right: LogcatLinePredicate,
) : LogcatLinePredicate {
    override fun hasConstraint(): Boolean = left.hasConstraint() || right.hasConstraint()
    override fun matches(lineLevel: LogcatLevel?, lineTag: String?, lineMessage: String?): Boolean =
        left.matches(lineLevel, lineTag, lineMessage) || right.matches(lineLevel, lineTag, lineMessage)
}

/**
 * 查询解析结果：显式成功/失败——失败不抛异常、不静默降级，reason 为人话（消费面回执
 * 直接消费），消费面拒收并保持上一有效谓词。
 */
internal sealed interface LogcatQueryParseResult {
    data class Ok(internal val spec: LogcatFilterSpec) : LogcatQueryParseResult
    data class Err(val reason: String) : LogcatQueryParseResult
}

/**
 * logcat 查询语法解析器：把查询串编译为 [LogcatFilterSpec]（统一过滤模型），纯函数。
 *
 * 语法分层契约（回退规则）：
 * - 无算子裸串 → 旧语义（tag 多词 contains-any + 级别档 AND），逐字等价向后兼容；
 * - 含算子 → 查询语法。裸串判定 = 去首尾空白后为空，且不以 `-` 开头，且不含
 *   `~/`（正则修饰符形态），且不含任一算子字符 `&` `|` `(` `)` `:`。
 *
 * 文法（EBNF，本仓独立定义）：
 * ```
 * query    = or
 * or       = and { "|" and }
 * and      = unary { "&" unary | unary }        // 相邻并列与显式 & 同为 AND
 * unary    = "-" unary | primary                // "-" 前缀 = 排除（非）
 * primary  = "(" or ")" | atom
 * atom     = key ":" operand | operand          // key 大小写不敏感：tag / level / msg
 * operand  = "~/" regex "/" | word              // ~/…/ = 正则修饰符
 * word     = 非分隔符字符序列；分隔符 = 空白与 `&` `|` `(` `)` `:`
 * ```
 *
 * 算子语义：
 * - `tag:词` = tag 包含词（子串、忽略大小写）；`tag:~/…/` = Kotlin 正则子串命中
 *   （containsMatchIn，大小写敏感——需忽略大小写用 `(?i)` 前缀；正则内要 `/` 字符
 *   用字符类 `[/]`，`\/` 在 Java 正则里是非法转义）；
 * - `level:档` = 级别严重度 ≥ 档（档 = V/D/I/W/E/F 单字母或级别全名，大小写不敏感）；
 * - `msg:词` = 消息体包含词（子串、忽略大小写）；`msg:~/…/` = 消息体正则子串命中
 *   （containsMatchIn，大小写敏感）。消息体 = [LogcatLineMatcher.ParsedLine.message]
 *   余部（「TAG: 」分隔符后，两形态边界逐形态钉死）——tag 本身不在 msg 匹配域；
 * - 无 key 裸词（查询语法内）= `tag:` 简写——混写裁定：裸词与算子同现时裸词一律按
 *   tag 包含语义（不按级别解释、不报错）；无 key 正则修饰符 `~/…/` 同为 tag 简写；
 * - 优先级：`-`（非）> 相邻并列/`&`（与）> `|`（或）；括号强制分组。
 *
 * 级别 chips 合成规则（钉死）：[parse] 的 `chipMinLevel` 参数（级别 chips 当前档）
 * 与查询谓词按**顶层 AND** 合成——查询含显式 `level:` 时行须同时满足两者（有效档 =
 * 两者中较严者）。裸串回退路径同规则，即旧构造语义。
 *
 * 算子集合的形态参考自外部开源工具的公开语法描述（AGPL 授权项目——只学算子集合
 * 形态，未读其实现，无代码借用）；上文法、优先级与混写规则为本仓独立定义。
 */
internal object LogcatQueryParser {

    /** 语法分层判定的算子字符面（任一出现即进入查询语法） */
    private const val OPERATOR_CHARS = "&|():"

    /**
     * key 算子的 key 面：解析分支（keyedOperand）与查询输入提示（[logcatQueryHint]）
     * 同源共用——提示文案的 key 与值域不得另立第二套口径。
     */
    internal const val QUERY_KEY_TAG = "tag"
    internal const val QUERY_KEY_LEVEL = "level"
    internal const val QUERY_KEY_MSG = "msg"

    fun isBareLegacyQuery(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (q.startsWith("-")) return false
        if (q.contains("~/")) return false
        return q.none { it in OPERATOR_CHARS }
    }

    fun parse(query: String, chipMinLevel: LogcatLevel? = null): LogcatQueryParseResult {
        if (isBareLegacyQuery(query)) {
            return LogcatQueryParseResult.Ok(
                LogcatFilterSpec(legacyTagPredicate(query, chipMinLevel)),
            )
        }
        val body = try {
            Cursor(query).parseQuery()
        } catch (e: ParseFailure) {
            return LogcatQueryParseResult.Err(e.message ?: "查询语法错误")
        }
        val combined = if (chipMinLevel == null) body else And(body, MinLevel(chipMinLevel))
        return LogcatQueryParseResult.Ok(LogcatFilterSpec(combined))
    }

    /**
     * 旧语义编译器（裸串回退与 [LogcatFilterSpec] 兼容构造共用）：空白/中英文逗号
     * 分隔多词 contains-any（忽略大小写）+ 级别档，两条件 AND；双空 = 无约束恒命中。
     */
    internal fun legacyTagPredicate(tagQuery: String, minLevel: LogcatLevel?): LogcatLinePredicate {
        val tokens = tagQuery.split(',', '，', ' ', '\t')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val tagPredicate: LogcatLinePredicate? = when (tokens.size) {
            0 -> null
            1 -> TagContains(tokens[0])
            else -> tokens.drop(1).fold(TagContains(tokens[0]) as LogcatLinePredicate) { acc, token ->
                Or(acc, TagContains(token))
            }
        }
        val levelPredicate = minLevel?.let { MinLevel(it) }
        return when {
            tagPredicate != null && levelPredicate != null -> And(tagPredicate, levelPredicate)
            tagPredicate != null -> tagPredicate
            levelPredicate != null -> levelPredicate
            else -> MatchAll
        }
    }

    private class ParseFailure(message: String) : Exception(message)

    /** 递归下降游标：文法见 [LogcatQueryParser] KDoc；一切语法错误经 [abort] 显式失败 */
    private class Cursor(private val src: String) {
        private var pos = 0

        fun parseQuery(): LogcatLinePredicate {
            val expr = parseOr()
            skipWs()
            if (pos < src.length) abort("「${src.substring(pos)}」无法解析")
            return expr
        }

        private fun parseOr(): LogcatLinePredicate {
            var left = parseAnd()
            while (true) {
                skipWs()
                if (peek() != '|') return left
                pos++
                left = Or(left, parseAnd())
            }
        }

        private fun parseAnd(): LogcatLinePredicate {
            var left = parseUnary()
            while (true) {
                skipWs()
                when (peek()) {
                    null, '|', ')' -> return left
                    '&' -> {
                        pos++
                        left = And(left, parseUnary())
                    }
                    // 相邻并列 = 隐式 AND（文法同等价显式 &）
                    else -> left = And(left, parseUnary())
                }
            }
        }

        private fun parseUnary(): LogcatLinePredicate {
            skipWs()
            if (peek() == '-') {
                pos++
                return Not(parseUnary())
            }
            return parsePrimary()
        }

        private fun parsePrimary(): LogcatLinePredicate {
            skipWs()
            return when (peek()) {
                null -> abort("表达式意外结束（缺操作数或右括号）")
                ')' -> abort("括号位置错误或空括号")
                '(' -> {
                    pos++
                    val expr = parseOr()
                    skipWs()
                    if (peek() != ')') abort("缺右括号")
                    pos++
                    expr
                }
                else -> parseAtom()
            }
        }

        private fun parseAtom(): LogcatLinePredicate {
            // 正则修饰符形态（~/…/）先于词读取：`/` 是词字符，先读词会把修饰符吞进包含词。
            // 无 key 的修饰符形态 = tag 正则简写（混写裁定同裸词，不按 msg 解释）
            if (looksAtRegexModifier()) return TagRegex(regexOperand())
            val word = readWord()
            if (word.isEmpty()) abort("此处应有算子或词项")
            if (peek() == ':') {
                pos++
                return keyedOperand(word)
            }
            // 混写裁定：无 key 裸词一律按 tag 包含语义
            return TagContains(word.lowercase())
        }

        private fun keyedOperand(key: String): LogcatLinePredicate {
            skipWs()
            return when (key.lowercase()) {
                QUERY_KEY_TAG -> if (looksAtRegexModifier()) {
                    TagRegex(regexOperand())
                } else {
                    TagContains(readOperandWord("$QUERY_KEY_TAG:").lowercase())
                }

                QUERY_KEY_LEVEL -> MinLevel(
                    levelOf(readOperandWord("$QUERY_KEY_LEVEL:"))
                        ?: abort("level: 档位无效（可用 V/D/I/W/E/F 或级别全名）"),
                )

                QUERY_KEY_MSG -> if (looksAtRegexModifier()) {
                    MsgRegex(regexOperand())
                } else {
                    MsgContains(readOperandWord("$QUERY_KEY_MSG:").lowercase())
                }

                else -> abort(
                    "未知算子 ${key}:（可用算子：$QUERY_KEY_TAG: $QUERY_KEY_LEVEL: $QUERY_KEY_MSG:）",
                )
            }
        }

        private fun levelOf(operand: String): LogcatLevel? =
            LogcatLevel.entries.firstOrNull {
                it.letter.equals(operand, ignoreCase = true) || it.name.equals(operand, ignoreCase = true)
            }

        private fun readOperandWord(operator: String): String {
            val word = readWord()
            if (word.isEmpty()) abort("$operator 缺操作数")
            return word
        }

        /** 正则修饰符操作数 `~/…/`：源取至下一个 `/`；未闭合、空源、非法正则均显式失败 */
        private fun regexOperand(): Regex {
            val start = pos + 2
            val end = src.indexOf('/', startIndex = start)
            if (end < 0) abort("正则未闭合（缺右 /）")
            val pattern = src.substring(start, end)
            pos = end + 1
            if (pattern.isEmpty()) abort("正则为空")
            return try {
                Regex(pattern)
            } catch (e: PatternSyntaxException) {
                abort("正则无效（${e.description ?: "语法错误"}）")
            }
        }

        private fun looksAtRegexModifier(): Boolean =
            pos + 1 < src.length && src[pos] == '~' && src[pos + 1] == '/'

        private fun readWord(): String {
            val start = pos
            while (pos < src.length && !isSeparator(src[pos])) pos++
            return src.substring(start, pos)
        }

        private fun isSeparator(c: Char): Boolean = c.isWhitespace() || c in OPERATOR_CHARS

        private fun peek(): Char? = src.getOrNull(pos)

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun abort(message: String): Nothing = throw ParseFailure(message)
    }
}
