package com.jiaoay.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 书签行集与时间差测量内核单测（纯函数面：无文件 IO——行原文由调用方回读的契约
 * 使本件零临时文件）。
 */
class LogBookmarkTest {

    // ── LogBookmarkSet ──

    @Test
    fun `toggle marks and unmarks in range and rejects out of range`() {
        val set = LogBookmarkSet(totalLines = 100)

        assertTrue(set.toggle(41), "域内下标首次翻转应置为已标记")
        assertTrue(set.isMarked(41))
        assertEquals(1, set.count)
        assertEquals(listOf(41), set.sortedMarks())
        assertEquals(listOf(41), set.markedInOrder())

        assertFalse(set.toggle(41), "已标记行再翻转应取消标记")
        assertFalse(set.isMarked(41))
        assertEquals(0, set.count)

        assertFalse(set.toggle(-1), "负下标应拒绝（状态不变）")
        assertFalse(set.toggle(100), "达总行数的下标应拒绝（0 基上界不含）")
        assertFalse(set.toggle(999), "超上界下标应拒绝")
        assertEquals(0, set.count, "拒绝的翻转不得入集")
    }

    @Test
    fun `clear empties both orders and snapshots survive later toggles`() {
        val set = LogBookmarkSet(totalLines = 10)
        set.toggle(3)
        set.toggle(7)

        val snapshot = set.markedInOrder()
        set.clear()
        assertEquals(0, set.count, "clear 应清空全部书签")
        assertEquals(listOf(3, 7), snapshot, "已取快照不随 clear 变化")

        set.toggle(1)
        assertEquals(listOf(3, 7), snapshot, "快照不随后续翻转变化（返回副本非活引用）")
    }

    @Test
    fun `marked order keeps marking sequence while sorted snapshot is ascending`() {
        val set = LogBookmarkSet(totalLines = 100)
        set.toggle(90)
        set.toggle(10)
        set.toggle(50)

        assertEquals(listOf(90, 10, 50), set.markedInOrder(), "标记序按标记先后追加（时间差取最近两签的依据）")
        assertEquals(listOf(10, 50, 90), set.sortedMarks(), "行序恒升序（跳转与小地图绘制的依据）")

        // 取消中段书签：标记序移除该签、其余签先后关系保持
        set.toggle(10)
        assertEquals(listOf(90, 50), set.markedInOrder())
        assertEquals(listOf(50, 90), set.sortedMarks())
    }

    @Test
    fun `zero line session rejects every toggle`() {
        val set = LogBookmarkSet(totalLines = 0)
        assertFalse(set.toggle(0), "零行会话无行可标记")
        assertEquals(0, set.count)
    }

    // ── LogTimeDiff ──

    @Test
    fun `measure computes positive diff between two timestamped lines`() {
        val result = LogTimeDiff.measure(
            fromLineText = "05-09 10:00:00.000 E/Main: start",
            toLineText = "05-09 10:00:01.500 E/Main: end",
            fallbackYear = "2026",
        )
        assertEquals(LogTimeDiff.Result.Ok(1500, "1.500 秒"), result)
    }

    @Test
    fun `measure accepts year-anchored format lines with identical diff`() {
        // 年份格式行（外部解码器产物契约）与裸格式行同链路解析，锚定年份只补裸格式
        val result = LogTimeDiff.measure(
            fromLineText = "2026-05-09 10:00:00.000 start",
            toLineText = "2026-05-09 09:59:58.750 end",
            fallbackYear = "2026",
        )
        assertEquals(LogTimeDiff.Result.Ok(-1250, "时间倒流：后签早于前签 1.250 秒"), result)
    }

    @Test
    fun `measure reports reversed time order explicitly instead of absolute value`() {
        // 跨行序负值：行序在后时间在先——差值为负且明示倒流，不静默取绝对值
        val result = LogTimeDiff.measure(
            fromLineText = "05-09 10:00:05.000 later-line",
            toLineText = "05-09 10:00:00.000 earlier-line",
            fallbackYear = "2026",
        )
        val ok = result as? LogTimeDiff.Result.Ok
        assertTrue(ok != null, "两端可解析应落 Ok：$result")
        assertEquals(-5000, ok.millis)
        assertTrue("时间倒流" in ok.text, "负差值须明示倒流：${ok.text}")
        assertTrue("5.000 秒" in ok.text, "量级段须保留（非绝对值吞号）：${ok.text}")
    }

    @Test
    fun `measure rejects lines without head timestamp with explicit end`() {
        val noTimestampFrom = LogTimeDiff.measure(
            fromLineText = "plain line without head timestamp",
            toLineText = "05-09 10:00:00.000 end",
            fallbackYear = "2026",
        )
        assertEquals(LogTimeDiff.Result.NoTimestamp(LogTimeDiff.End.FROM), noTimestampFrom)

        val noTimestampTo = LogTimeDiff.measure(
            fromLineText = "05-09 10:00:00.000 start",
            toLineText = "E/Main: 续行无时间戳",
            fallbackYear = "2026",
        )
        assertEquals(LogTimeDiff.Result.NoTimestamp(LogTimeDiff.End.TO), noTimestampTo)

        // 前签先判：两端均无时间戳报 FROM（判序确定，不随机）
        val bothMissing = LogTimeDiff.measure(
            fromLineText = "plain",
            toLineText = "also plain",
            fallbackYear = "2026",
        )
        assertEquals(LogTimeDiff.Result.NoTimestamp(LogTimeDiff.End.FROM), bothMissing)
    }

    @Test
    fun `format duration tiers by magnitude with three digit millis part`() {
        assertEquals("0 毫秒", LogTimeDiff.formatDuration(0))
        assertEquals("999 毫秒", LogTimeDiff.formatDuration(999))
        assertEquals("1.000 秒", LogTimeDiff.formatDuration(1000))
        assertEquals("1.500 秒", LogTimeDiff.formatDuration(1500))
        assertEquals("1 分 0.500 秒", LogTimeDiff.formatDuration(60_500))
        assertEquals("1 时 0 分 0.000 秒", LogTimeDiff.formatDuration(3_600_000))
        assertEquals("2 时 3 分 4.500 秒", LogTimeDiff.formatDuration(7_384_500))
        assertEquals("时间倒流：后签早于前签 250 毫秒", LogTimeDiff.formatDuration(-250))
    }
}
