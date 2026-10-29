package com.jiaoay.easy_dev_toolbox.page.tools.json

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSON 工具引擎（纯函数面，kotlinx.serialization 单源——不引第二套 JSON 解析栈）。
 *
 * ## 动机
 * 设备日志大量 JSON 行（信号/埋点/配置）——粘贴即格式化、校验错位定位人话回执、
 * JSONPath 抽字段。
 *
 * ## 契约
 * - [parseJsonContent]：空白输入回 [JsonContent.Empty]（引导态非错误）；坏 JSON
 *   显式失败回 [JsonContent.Invalid]，message 带行/列定位（kotlinx 异常消息含
 *   `at offset` 时换算 1 基行/列，否则原文回执），不静默。
 * - [formatJson] / [minifyJson]：对已解析元素重排——格式化不修复不丢内容，
 *   解析成功后重排不可能失败。
 *
 * ## JSONPath 能力边界（手写最小子集，不引第三方库）
 * 栈内 kotlinx.serialization 无官方 JSONPath；唯一第三方同栈库是预发布版且
 * 语法子集反而更小（无数组下标）、还拖入 Optics 依赖栈——为此 ~百行的求值器
 * 引库不成比例。支持：`$` 根、`.name` 子代、`['name']` / `["name"]` 引号子代
 * （键名含点/空格时用）、`[n]` 数组下标（非负）、`.*` / `[*]` 单层通配。
 * **不在子集内**（显式 [JsonPathQueryResult.Invalid] 列出支持面）：
 * 递归下降 `..`、区间 `[a:b]`、过滤器 `[?(...)]`、负数下标。
 * 缺键 / 下标越界 / 类型不符 = 零命中（[JsonPathQueryResult.Ok] 空表），
 * 不是错误——与 JSONPath 通用语义一致。
 *
 * ## 硬上界
 * 命中数硬上界 [MAX_JSON_PATH_HITS]：超限截断并以
 * [JsonPathQueryResult.Ok.truncated] 明示，不得静默吞命中（UI 卡死防线）。
 */
sealed interface JsonContent {

    /** 空白输入：引导态，面板不报错不产出 */
    data object Empty : JsonContent

    /** 坏 JSON：message 带行/列定位的人话回执（无定位信息时原文回执） */
    data class Invalid(val message: String) : JsonContent

    data class Ok(val element: JsonElement) : JsonContent
}

/** pretty 输出档（2 空格缩进——日志行粘贴场景的常规密度） */
private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** 解析入口：全部异常面收敛为 [JsonContent]（kotlinx 解析异常均继承 [SerializationException]） */
fun parseJsonContent(text: String): JsonContent = when {
    text.isBlank() -> JsonContent.Empty
    else -> try {
        JsonContent.Ok(Json.parseToJsonElement(text))
    } catch (e: SerializationException) {
        JsonContent.Invalid(jsonInvalidMessage(text, e))
    }
}

fun formatJson(element: JsonElement): String =
    prettyJson.encodeToString(JsonElement.serializer(), element)

fun minifyJson(element: JsonElement): String =
    Json.encodeToString(JsonElement.serializer(), element)

/** kotlinx 异常消息中的 `at offset: N`（0 基）定位子句 */
private val offsetClause = Regex("""offset[:：]?\s*(\d+)""")

/**
 * 坏 JSON 人话回执：异常消息含 offset 时前置「第 L 行第 C 列附近」，
 * 异常原文随附（定位与原因并给）。
 */
internal fun jsonInvalidMessage(text: String, e: SerializationException): String {
    val raw = e.message ?: "无法解析"
    val offset = offsetClause.find(raw)?.groupValues?.get(1)?.toIntOrNull()
    return if (offset == null) {
        "JSON 无效：$raw"
    } else {
        val (line, col) = lineColOf(text, offset)
        "JSON 无效：第 $line 行第 $col 列附近——$raw"
    }
}

/** offset（0 基）→ 1 基（行, 列）；offset 越界时钳制到文本范围内 */
internal fun lineColOf(text: String, offset: Int): Pair<Int, Int> {
    val clamped = offset.coerceIn(0, text.length)
    val prefix = text.substring(0, clamped)
    val line = prefix.count { it == '\n' } + 1
    val col = clamped - (prefix.lastIndexOf('\n') + 1) + 1
    return line to col
}

// ── JSONPath 最小子集 ──

sealed interface JsonPathQueryResult {

    /** 查询为空：引导态 */
    data object Empty : JsonPathQueryResult

    /** 语法不解析或不在子集内：message 列出支持面 */
    data class Invalid(val message: String) : JsonPathQueryResult

    /** 命中列表（可为空 = 零命中非错误）；命中值以紧凑 JSON 文本呈现（字符串含引号，保 JSON 语义） */
    data class Ok(val values: List<String>, val truncated: Boolean) : JsonPathQueryResult
}

/** 命中数硬上界：超出即截断（[JsonPathQueryResult.Ok.truncated] 明示） */
const val MAX_JSON_PATH_HITS: Int = 500

private const val SUPPORTED_SYNTAX =
    "支持的语法子集：\$ 根、.name 子代、['name'] 引号子代、[n] 数组下标、.* / [*] 单层通配"

private class JsonPathSyntaxError(message: String) : Exception(message)

private sealed interface PathToken {
    data class Child(val name: String) : PathToken
    data class Index(val index: Int) : PathToken
    data object Wildcard : PathToken
}
/**
 * JSONPath 查询。路径须以 `$` 起锚；空白路径回 [JsonPathQueryResult.Empty]；
 * 语法不解析/不在子集内显式 [JsonPathQueryResult.Invalid]（不静默不猜测）。
 */
fun queryJsonPath(
    root: JsonElement,
    path: String,
    limit: Int = MAX_JSON_PATH_HITS,
): JsonPathQueryResult {
    if (path.isBlank()) return JsonPathQueryResult.Empty
    return try {
        val tokens = parseJsonPath(path)
        val hits = evalTokens(root, tokens)
        JsonPathQueryResult.Ok(
            values = hits.take(limit).map { it.toString() },
            truncated = hits.size > limit,
        )
    } catch (e: JsonPathSyntaxError) {
        JsonPathQueryResult.Invalid(e.message ?: "路径无法解析")
    }
}

/** 路径解析：token 化 + 逐段语法校验（不在子集内的语法给列支持面的可操作回执） */
private fun parseJsonPath(path: String): List<PathToken> {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) throw JsonPathSyntaxError("路径为空")
    if (trimmed.first() != '$') {
        throw JsonPathSyntaxError("路径须以 \$ 开头（根锚）——$SUPPORTED_SYNTAX")
    }
    val tokens = ArrayList<PathToken>()
    var i = 1
    while (i < trimmed.length) {
        when (val c = trimmed[i]) {
            '.' -> {
                when (val next = trimmed.getOrNull(i + 1)) {
                    null -> throw JsonPathSyntaxError("路径以「.」结尾——缺子代名")
                    '.' -> throw JsonPathSyntaxError("不支持的语法「..」（递归下降不在最小子集内）——$SUPPORTED_SYNTAX")
                    '*' -> {
                        tokens.add(PathToken.Wildcard)
                        i += 2
                    }
                    else -> {
                        var j = i + 1
                        while (j < trimmed.length && trimmed[j] != '.' && trimmed[j] != '[') j++
                        val name = trimmed.substring(i + 1, j)
                        if (name.isEmpty()) throw JsonPathSyntaxError("「.」后缺子代名")
                        tokens.add(PathToken.Child(name))
                        i = j
                    }
                }
            }
            '[' -> parseBracket(trimmed, i).let { (token, next) ->
                tokens.add(token)
                i = next
            }
            else -> throw JsonPathSyntaxError("无法解析的语法「$c」——$SUPPORTED_SYNTAX")
        }
    }
    return tokens
}

/** `['name']` / `["name"]` / `[n]` / `[*]` 四形态；其余 `[...]` 段按子集外显式拒绝 */
private fun parseBracket(path: String, start: Int): Pair<PathToken, Int> {
    val after = path.getOrNull(start + 1)
    return when {
        after == '\'' || after == '"' -> {
            var j = start + 2
            while (j < path.length && path[j] != after) j++
            if (j >= path.length) throw JsonPathSyntaxError("引号子代名未闭合")
            if (path.getOrNull(j + 1) != ']') throw JsonPathSyntaxError("引号子代名后须为「]」")
            PathToken.Child(path.substring(start + 2, j)) to j + 2
        }
        after == '*' -> {
            if (path.getOrNull(start + 2) != ']') throw JsonPathSyntaxError("「[*]」内只允许 *")
            PathToken.Wildcard to start + 3
        }
        after != null && after.isDigit() -> {
            var j = start + 1
            while (j < path.length && path[j].isDigit()) j++
            if (path.getOrNull(j) != ']') {
                throw JsonPathSyntaxError("数组下标须为非负整数（不支持区间/过滤器）——$SUPPORTED_SYNTAX")
            }
            PathToken.Index(path.substring(start + 1, j).toInt()) to j + 1
        }
        after == '-' -> throw JsonPathSyntaxError("不支持负数下标——$SUPPORTED_SYNTAX")
        after == '?' -> throw JsonPathSyntaxError("不支持过滤器「[?(...)]」——$SUPPORTED_SYNTAX")
        after == ':' -> throw JsonPathSyntaxError("不支持区间「[a:b]」——$SUPPORTED_SYNTAX")
        else -> throw JsonPathSyntaxError("无法解析的「[...]」段——$SUPPORTED_SYNTAX")
    }
}

/**
 * 逐 token 求值：通配符把当前集合展成多值；缺键/越界/类型不符节点静默跳过
 * （零命中语义）。中途集合为空即短路——后续 token 不可能再产出命中。
 */
private fun evalTokens(root: JsonElement, tokens: List<PathToken>): List<JsonElement> {
    var current = listOf(root)
    for (token in tokens) {
        if (current.isEmpty()) return emptyList()
        current = when (token) {
            is PathToken.Child -> current.mapNotNull { (it as? JsonObject)?.get(token.name) }
            is PathToken.Index -> current.mapNotNull { (it as? JsonArray)?.getOrNull(token.index) }
            is PathToken.Wildcard -> current.flatMap { element ->
                when (element) {
                    is JsonObject -> element.values.toList()
                    is JsonArray -> element.toList()
                    else -> emptyList()
                }
            }
        }
    }
    return current
}
