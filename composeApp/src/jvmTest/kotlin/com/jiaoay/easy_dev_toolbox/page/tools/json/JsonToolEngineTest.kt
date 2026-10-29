package com.jiaoay.easy_dev_toolbox.page.tools.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * JSON 工具引擎单测：格式化/压缩 / 坏 JSON 行列定位显式失败 /
 * JSONPath 最小子集命中矩阵（缺键零命中、语法子集外显式拒绝、硬上界截断）。
 */
class JsonToolEngineTest {

    private val fixture = """
        {"store":{"book":[{"title":"A","price":10},{"title":"B","price":20}],
        "owner":{"name":"car","odd.key":true}}}
    """.trimIndent()

    private fun parsed(text: String = fixture): JsonContent.Ok =
        parseJsonContent(text) as JsonContent.Ok

    // ── 解析 / 校验 ──

    @Test
    fun `blank input yields guidance state not error`() {
        assertIs<JsonContent.Empty>(parseJsonContent("   "))
    }

    @Test
    fun `bad json fails explicitly with line and column`() {
        // 第 2 行缺逗号：解析器在该行报错
        val bad = "{\n  \"a\": 1 \"b\": 2\n}"
        val result = parseJsonContent(bad)
        val invalid = assertIs<JsonContent.Invalid>(result)
        assertTrue("第 2 行" in invalid.message, "回执须含行号，实得：${invalid.message}")
        assertTrue("列" in invalid.message, "回执须含列号，实得：${invalid.message}")
    }

    @Test
    fun `line col conversion is one-based and clamped`() {
        val text = "ab\ncd"
        assertEquals(1 to 1, lineColOf(text, 0))
        assertEquals(1 to 3, lineColOf(text, 2))
        assertEquals(2 to 1, lineColOf(text, 3))
        assertEquals(2 to 2, lineColOf(text, 4))
        // offset 越界钳制到文本范围（不炸不猜）
        assertEquals(2 to 3, lineColOf(text, 99))
    }

    // ── 格式化 / 压缩 ──

    @Test
    fun `format json pretty prints with two space indent`() {
        val formatted = formatJson(parsed("""{"a":1,"b":[1,2]}""").element)
        assertEquals(
            """
            {
              "a": 1,
              "b": [
                1,
                2
              ]
            }
            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun `minify json collapses to single line`() {
        val minified = minifyJson(parsed("{\n  \"a\": 1,\n  \"b\": 2\n}").element)
        assertEquals("{\"a\":1,\"b\":2}", minified)
    }

    @Test
    fun `format round trip preserves content`() {
        val element = parsed().element
        val roundTrip = parseJsonContent(formatJson(element))
        val back = assertIs<JsonContent.Ok>(roundTrip)
        assertEquals(element, back.element, "格式化重排不得丢内容或改语义")
    }

    // ── JSONPath 命中矩阵 ──

    @Test
    fun `blank query yields guidance state not error`() {
        assertIs<JsonPathQueryResult.Empty>(queryJsonPath(parsed().element, "   "))
    }

    @Test
    fun `root child and nested child resolve`() {
        val hits = queryJsonPath(parsed().element, "$.store.owner.name")
        val ok = assertIs<JsonPathQueryResult.Ok>(hits)
        assertEquals(listOf("\"car\""), ok.values, "命中值以紧凑 JSON 文本呈现（字符串含引号）")
        assertTrue(!ok.truncated)
    }

    @Test
    fun `array index resolves element`() {
        val hits = queryJsonPath(parsed().element, "$.store.book[1].title")
        val ok = assertIs<JsonPathQueryResult.Ok>(hits)
        assertEquals(listOf("\"B\""), ok.values)
    }

    @Test
    fun `array wildcard fans out all items`() {
        val hits = queryJsonPath(parsed().element, "$.store.book[*].price")
        val ok = assertIs<JsonPathQueryResult.Ok>(hits)
        assertEquals(listOf("10", "20"), ok.values)
    }

    @Test
    fun `object wildcard fans out all values`() {
        val hits = queryJsonPath(parsed().element, "$.store.owner.*")
        val ok = assertIs<JsonPathQueryResult.Ok>(hits)
        assertEquals(listOf("\"car\"", "true"), ok.values, "对象成员值按插入序展开")
    }

    @Test
    fun `dot wildcard matches bracket wildcard`() {
        val element = parsed().element
        val byDot = queryJsonPath(element, "$.store.book.*.title")
        val byBracket = queryJsonPath(element, "$.store.book[*].title")
        assertEquals(byDot, byBracket, ".* 与 [*] 是同一 token 的两种写法")
    }

    @Test
    fun `quoted bracket child resolves keys with dots`() {
        val hits = queryJsonPath(parsed().element, "$['store']['owner']['odd.key']")
        val ok = assertIs<JsonPathQueryResult.Ok>(hits)
        assertEquals(listOf("true"), ok.values)
    }

    @Test
    fun `missing key out of range index and type mismatch are zero hits not errors`() {
        val element = parsed().element
        assertIs<JsonPathQueryResult.Ok>(queryJsonPath(element, "$.missing.also.missing"))
        val zeroHits = assertIs<JsonPathQueryResult.Ok>(queryJsonPath(element, "$.store.book[9]"))
        assertTrue(zeroHits.values.isEmpty())
        // 对对象用数组下标 / 对数组用子代名 = 类型不符，零命中非错误
        val typeMismatch = assertIs<JsonPathQueryResult.Ok>(queryJsonPath(element, "$.store[0]"))
        assertTrue(typeMismatch.values.isEmpty())
    }

    // ── 语法子集外显式拒绝 ──

    @Test
    fun `path without root anchor is rejected with supported syntax`() {
        val result = queryJsonPath(parsed().element, "store.book")
        val invalid = assertIs<JsonPathQueryResult.Invalid>(result)
        assertTrue("须以 \$ 开头" in invalid.message, "实得：${invalid.message}")
    }

    @Test
    fun `recursive descent is outside the subset and rejected explicitly`() {
        val result = queryJsonPath(parsed().element, "$..title")
        val invalid = assertIs<JsonPathQueryResult.Invalid>(result)
        assertTrue("递归下降" in invalid.message, "实得：${invalid.message}")
        assertTrue("支持的语法子集" in invalid.message, "拒绝回执须列出支持面，实得：${invalid.message}")
    }

    @Test
    fun `slice filter and negative index are rejected explicitly`() {
        val element = parsed().element
        assertTrue(
            "区间" in (queryJsonPath(element, "$.store.book[0:1]") as JsonPathQueryResult.Invalid).message,
        )
        assertTrue(
            "过滤器" in (queryJsonPath(element, "$.store.book[?(@.price)]") as JsonPathQueryResult.Invalid).message,
        )
        assertTrue(
            "负数下标" in (queryJsonPath(element, "$.store.book[-1]") as JsonPathQueryResult.Invalid).message,
        )
    }

    // ── 硬上界 ──

    @Test
    fun `hit limit truncates and reports`() {
        val big = (0 until 10).joinToString(",", "[", "]") { "{\"v\":$it}" }
        val element = parsed(big).element
        val result = queryJsonPath(element, "$[*].v", limit = 3)
        val ok = assertIs<JsonPathQueryResult.Ok>(result)
        assertEquals(listOf("0", "1", "2"), ok.values)
        assertTrue(ok.truncated, "超限截断必须明示，不得静默吞命中")
    }

    @Test
    fun `query on invalid json is unreachable from panel contract`() {
        // 面板对 Invalid 内容不发起查询（queryResult = null）；引擎面校验
        // Ok 元素查询不受输入文本影响——坏 JSON 的显式失败在 parseJsonContent 承担
        val bad = parseJsonContent("{not json")
        assertIs<JsonContent.Invalid>(bad, "坏 JSON 必须显式失败，不得静默")
    }
}
