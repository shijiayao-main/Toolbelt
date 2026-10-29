package com.jiaoay.easy_dev_toolbox.page.tools.regex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 正则测试器引擎单测：无效正则可操作回执 / 命中区间 / 分组编号 /
 * 零宽命中不死循环 / 硬上界截断明示。
 */
class RegexTesterEngineTest {

    // ── 编译回执 ──

    @Test
    fun `empty pattern yields guidance state not error`() {
        assertIs<RegexCompileResult.Empty>(compileRegex(""))
    }

    @Test
    fun `valid pattern compiles`() {
        val result = compileRegex("""\[(\w+)\]""")
        assertIs<RegexCompileResult.Valid>(result)
    }

    @Test
    fun `unclosed character class gives actionable message with position`() {
        val result = compileRegex("[abc")
        val invalid = assertIs<RegexCompileResult.Invalid>(result)
        assertTrue("「[」未闭合" in invalid.message, "回执须含修法提示，实得：${invalid.message}")
        // 出错位置由 java.util.regex 定位（未闭合类报在发现点，1 基呈现）——
        // 只断言位置子句在位，不断言具体字符号（JDK 内部位点非本引擎契约）
        assertTrue(
            Regex("""出错位置：第 \d+ 个字符附近""").containsMatchIn(invalid.message),
            "回执须含出错位置子句，实得：${invalid.message}",
        )
    }

    @Test
    fun `unmatched closing paren gives actionable message`() {
        val result = compileRegex("a)")
        val invalid = assertIs<RegexCompileResult.Invalid>(result)
        assertTrue("多余的「)」" in invalid.message, "回执须含修法提示，实得：${invalid.message}")
    }

    @Test
    fun `dangling metacharacter gives escaping hint`() {
        val result = compileRegex("*x")
        val invalid = assertIs<RegexCompileResult.Invalid>(result)
        assertTrue("需转义" in invalid.message, "回执须含转义提示，实得：${invalid.message}")
    }

    // ── 命中区间 ──

    @Test
    fun `match intervals are end-inclusive and ordered`() {
        val regex = (compileRegex("""(\w+)@(\w+)""") as RegexCompileResult.Valid).regex
        val sample = "mail: a@b and x@y!"
        val matches = findMatches(regex, sample).matches
        assertEquals(2, matches.size)
        // "a@b" 占位 6..8（含端）——高亮渲染区间 = 6 .. 8+1
        assertEquals(IntRange(6, 8), matches[0].range)
        assertEquals("a@b", matches[0].value)
        assertEquals(IntRange(14, 16), matches[1].range)
        assertEquals("x@y", matches[1].value)
    }

    @Test
    fun `group numbering starts at one and unmatched optional group is explicit`() {
        val regex = (compileRegex("(a)(b)?") as RegexCompileResult.Valid).regex
        val matches = findMatches(regex, "a").matches
        assertEquals(1, matches.size)
        val groups = matches[0].groups
        assertEquals(2, groups.size, "分组编号 1..2（编号 0 是整命中不入列）")
        assertEquals(1, groups[0].index)
        assertEquals("a", groups[0].value)
        assertTrue(groups[0].matched)
        assertEquals(2, groups[1].index)
        assertTrue(!groups[1].matched, "可选分组未参与须显式（matched=false），不得伪装成空串参与")
    }

    @Test
    fun `pattern without capture groups yields empty group list`() {
        val regex = (compileRegex("ab") as RegexCompileResult.Valid).regex
        val matches = findMatches(regex, "ab").matches
        assertEquals(1, matches.size)
        assertEquals(0, matches[0].groups.size)
    }

    // ── 零宽命中 ──

    @Test
    fun `zero-width matches terminate and keep empty ranges`() {
        val regex = (compileRegex("a*") as RegexCompileResult.Valid).regex
        val matches = findMatches(regex, "bab").matches
        // 手工前进一位的续扫语义：空@0 → "a"@1 → 空@2 → 空@3（末尾零宽入列即终止）
        assertEquals(4, matches.size)
        assertEquals("", matches[0].value)
        assertTrue(matches[0].range.isEmpty(), "零宽命中区间 first > last（空区间口径）")
        assertEquals("a", matches[1].value)
        assertEquals(IntRange(1, 1), matches[1].range)
        assertTrue(matches[3].range.isEmpty())
        assertEquals(3, matches[3].range.first, "末尾零宽命中位于样本末端")
    }

    // ── 硬上界 ──

    @Test
    fun `match limit truncates and reports`() {
        val regex = (compileRegex("a") as RegexCompileResult.Valid).regex
        val result = findMatches(regex, "a".repeat(10), limit = 3)
        assertEquals(3, result.matches.size, "命中列表封顶在 limit")
        assertTrue(result.truncated, "超限截断必须明示，不得静默吞命中")
        assertEquals(IntRange(2, 2), result.matches.last().range)
    }

    @Test
    fun `empty sample yields no matches without truncation`() {
        val regex = (compileRegex("a") as RegexCompileResult.Valid).regex
        val result = findMatches(regex, "")
        assertTrue(result.matches.isEmpty())
        assertTrue(!result.truncated)
    }
}
