package com.jiaoay.log

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 日志三工具（LogSplitter/LogTagFilter/LogTimeFilter）编码契约特征测试：
 * 输入读取与输出写出一律显式 UTF-8，中文内容跨平台读写逐行一致（不随平台默认编码漂移）。
 * 另覆盖 LogTagFilter 输出文件名清洗（非法字符与 Windows 保留名）。
 */
class LogToolsEncodingTest {

    /** 构造临时 UTF-8 日志文件（时间行开块，中文内容行） */
    private fun tempLog(lines: List<String>): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "log-tools-enc-test-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return File(dir, "app.log").apply {
            writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            deleteOnExit()
        }
    }

    // ── LogTagFilter：中文往返 ──

    @Test
    fun `LogTagFilter 中文内容往返一致`() {
        val lines = listOf(
            "05-09 10:00:00.000 D/MainActivity: onStart 中文开始",
            "05-09 10:00:01.000 D/MainActivity: 中文内容行 —— 混排 mixed",
            "05-09 10:00:02.000 I/Worker: 其他行",
        )
        val input = tempLog(lines)
        val result = LogTagFilter.filterByTag(input, tagRegex = "MainActivity", tagName = "主界面")
        assertTrue(result is ToolsResult.Success, "过滤应成功：$result")

        val output = (result as ToolsResult.Success).outputFile
        // 读回逐行一致（显式 UTF-8 编解码）
        val roundTrip = output.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(lines.take(2), roundTrip, "中文行应逐行一致")
    }

    // ── LogTagFilter：输出文件名清洗 ──

    @Test
    fun `tagName 非法字符被替换为下划线`() {
        assertEquals("a_b", LogTagFilter.sanitizeTagName("a:b"))
        assertEquals("a_b__c", LogTagFilter.sanitizeTagName("a<b>|c"))
        assertEquals("a_b", LogTagFilter.sanitizeTagName("a/b"))
        assertEquals("a_b", LogTagFilter.sanitizeTagName("a\\b"))
    }

    @Test
    fun `tagName Windows 保留名追加后缀`() {
        assertEquals("CON_", LogTagFilter.sanitizeTagName("CON"))
        assertEquals("nul_", LogTagFilter.sanitizeTagName("nul"))
    }

    @Test
    fun `tagName 尾部空白与点号被去除`() {
        assertEquals("tag", LogTagFilter.sanitizeTagName("tag. "))
        assertEquals("tag", LogTagFilter.sanitizeTagName(" tag .."))
    }

    // ── LogTimeFilter：中文往返 ──

    @Test
    fun `LogTimeFilter 中文内容往返`() {
        val lines = listOf(
            "05-09 10:00:00.000 D/Main: 中文开始",
            "05-09 10:01:00.000 D/Main: 中文结束",
            "05-09 10:05:00.000 D/Main: 窗口外中文",
        )
        val input = tempLog(lines)
        // filterByTime(file, target, rangeMinute)：target 格式 yyyy-MM-dd_HH:mm:ss.SSS，窗口 ±rangeMinute
        val result = LogTimeFilter.filterByTime(input, target = "2026-05-09_10:00:00.000", rangeMinute = 1)
        assertTrue(result is ToolsResult.Success, "过滤应成功：$result")
        val output = (result as ToolsResult.Success).outputFile
        val roundTrip = output.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        // 窗口 [09:59, 10:01]：前两行命中原样保留（含中文），窗口外行被过滤
        assertEquals(lines.take(2), roundTrip, "时间窗内的中文行应原样保留")
    }
}
