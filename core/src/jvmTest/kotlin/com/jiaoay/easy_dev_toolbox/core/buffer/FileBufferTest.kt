package com.jiaoay.easy_dev_toolbox.core.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FileBuffer 暂存区单测（调查工作台 S4——纯内存槽直测）：
 * offer 去重保序 / remove 出暂存（「拖进会话才授权」对偶面）/ clear 清空。
 */
class FileBufferTest {

    @Test
    fun `offer 去重保序`() {
        FileBuffer.clearForTest()
        FileBuffer.offer(listOf("/tmp/a.txt", "/tmp/b.txt"))
        FileBuffer.offer(listOf("/tmp/b.txt", "/tmp/c.txt", ""))
        val paths = FileBuffer.files.value.map { it.path }
        assertEquals(listOf("/tmp/a.txt", "/tmp/b.txt", "/tmp/c.txt"), paths, "去重保序；空路径不入")
    }

    @Test
    fun `offer 空批次与空白路径安全`() {
        FileBuffer.clearForTest()
        FileBuffer.offer(emptyList())
        FileBuffer.offer(listOf("", "  "))
        assertTrue(FileBuffer.files.value.isEmpty(), "空批次/空白路径零副作用")
    }

    @Test
    fun `remove 单件与批量出暂存`() {
        FileBuffer.clearForTest()
        FileBuffer.offer(listOf("/tmp/a.txt", "/tmp/b.txt", "/tmp/c.txt"))
        FileBuffer.remove("/tmp/b.txt")
        assertEquals(listOf("/tmp/a.txt", "/tmp/c.txt"), FileBuffer.files.value.map { it.path })
        FileBuffer.removeAll(listOf("/tmp/a.txt", "/tmp/c.txt"))
        assertTrue(FileBuffer.files.value.isEmpty(), "批量移除后暂存区清空")
    }

    @Test
    fun `remove 不存在路径幂等`() {
        FileBuffer.clearForTest()
        FileBuffer.offer("/tmp/a.txt")
        FileBuffer.remove("/tmp/nope.txt")
        assertEquals(1, FileBuffer.files.value.size, "不存在路径移除无操作")
    }

    @Test
    fun `clear 清空全部`() {
        FileBuffer.offer(listOf("/tmp/x.txt", "/tmp/y.txt"))
        FileBuffer.clear()
        assertTrue(FileBuffer.files.value.isEmpty())
    }

    @Test
    fun `addedAt 记录进入时间`() {
        FileBuffer.clearForTest()
        val before = System.currentTimeMillis()
        FileBuffer.offer("/tmp/a.txt")
        val file = FileBuffer.files.value.single()
        assertTrue(file.addedAt >= before, "BufferedFile 携带 addedAt 时间戳")
    }

    // ── 评审修复批 2026-09-23：P1-2 消费面端到端时序锚（chips 行消费动作语义）──

    @Test
    fun `消费时序——offer 后 files 可见、逐件 remove 清空`() {
        // LogWorkbenchPage FileBufferRow 消费链：chip 点击 = 消费动作（页面侧
        // addTargetFile + FileBuffer.remove 对偶收口）。本测钉 Buffer 侧时序：
        // offer → files 出现 → remove 单件 → 清空
        FileBuffer.clearForTest()
        FileBuffer.offer(listOf("/tmp/decrypted.alog.txt", "/tmp/filtered.txt"))
        assertEquals(2, FileBuffer.files.value.size, "暂存后 Buffer 有 2 件（chips 行渲染依据）")
        // 消费第一件（chip 点击）
        FileBuffer.remove("/tmp/decrypted.alog.txt")
        assertEquals(listOf("/tmp/filtered.txt"), FileBuffer.files.value.map { it.path }, "消费单件后余 1 件")
        // 消费第二件
        FileBuffer.remove("/tmp/filtered.txt")
        assertTrue(FileBuffer.files.value.isEmpty(), "全部消费后 chips 行零渲染（isNotEmpty 门控）")
    }

    @Test
    fun `消费时序——offer 去重防重复消费入口`() {
        // palette 重复暂存同一产物（重复执行命令）不产生重复 chip——
        // 消费动作一次性移除后，再次 offer 才会重新可见
        FileBuffer.clearForTest()
        FileBuffer.offer("/tmp/a.txt")
        FileBuffer.offer("/tmp/a.txt")
        assertEquals(1, FileBuffer.files.value.size, "重复暂存去重——chip 只渲染一件")
        FileBuffer.remove("/tmp/a.txt")
        FileBuffer.offer("/tmp/a.txt")
        assertEquals(1, FileBuffer.files.value.size, "移除后可重新暂存（非永久黑名单语义）")
    }
}
