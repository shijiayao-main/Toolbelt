// 机器人回复账本（2026-09-11 晚）单测：覆盖写读/幂等/空白拒记/FIFO 溢出——FileRegistry 同款断言风格。
package com.jiaoay.lark.bridge.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardReplyLedgerTest {

    @Test
    fun `记录后按 messageId 查回终态全文`() {
        val ledger = CardReplyLedger()
        ledger.record("om_card_1", "oc_c", "排查结论：崩溃源于空指针")
        assertEquals("排查结论：崩溃源于空指针", ledger.lookup("om_card_1", "oc_c"))
        assertEquals(1, ledger.size)
    }

    @Test
    fun `同 messageId 覆写幂等且不重复占位`() {
        val ledger = CardReplyLedger()
        ledger.record("om_card_1", "oc_c", "第一版")
        ledger.record("om_card_1", "oc_c", "终版")
        assertEquals("终版", ledger.lookup("om_card_1", "oc_c"))
        assertEquals(1, ledger.size, "覆写不应增加条目数")
    }

    @Test
    fun `空白 messageId 或空白 markdown 拒记`() {
        val ledger = CardReplyLedger()
        ledger.record("", "oc_c", "有正文")
        ledger.record("   ", "oc_c", "有正文")
        ledger.record("om_card_1", "oc_c", "")
        ledger.record("om_card_2", "oc_c", "   ")
        assertEquals(0, ledger.size)
        assertNull(ledger.lookup("om_card_1", "oc_c"))
    }

    @Test
    fun `未记录的 messageId 查回 null`() {
        assertNull(CardReplyLedger().lookup("om_never"))
    }

    @Test
    fun `FIFO 溢出逐出最旧`() {
        val ledger = CardReplyLedger(maxEntries = 3)
        ledger.record("om_1", "oc_c", "a")
        ledger.record("om_2", "oc_c", "b")
        ledger.record("om_3", "oc_c", "c")
        ledger.record("om_4", "oc_c", "d") // 溢出：om_1 被逐出
        assertEquals(3, ledger.size)
        assertNull(ledger.lookup("om_1", "oc_c"), "最旧应被逐出")
        assertEquals("b", ledger.lookup("om_2", "oc_c"))
        assertEquals("d", ledger.lookup("om_4", "oc_c"), "最新保留")
    }

    @Test
    fun `覆写后队位重排不误逐`() {
        val ledger = CardReplyLedger(maxEntries = 2)
        ledger.record("om_1", "oc_c", "a")
        ledger.record("om_2", "oc_c", "b")
        ledger.record("om_1", "oc_c", "a2") // 覆写 om_1 ⇒ 它变最新
        ledger.record("om_3", "oc_c", "c")  // 溢出应逐 om_2（最旧），非 om_1
        assertEquals("a2", ledger.lookup("om_1", "oc_c"), "🔴 覆写过的条目不应被误逐")
        assertNull(ledger.lookup("om_2", "oc_c"))
        assertEquals("c", ledger.lookup("om_3", "oc_c"))
        assertTrue(ledger.size <= 2)
    }

        // ==================== 持久化（2026-09-12 真机批次：重启丢账 → 落盘）====================

    private fun tempLedgerFile(): java.nio.file.Path =
    java.nio.file.Files.createTempDirectory("card-ledger").resolve("card-reply-ledger.jsonl")

    @Test
    fun `持久化-record 后新实例 load 可查回（跨进程重启语义）`() {
    val file = tempLedgerFile()
    CardReplyLedger(persistenceFile = file).apply {
        record("om_card_9", null, "跨重启应可见的终态全文")
    }
    // 新实例模拟进程重启（内存态清空、只靠文件）
    val reborn = CardReplyLedger(persistenceFile = file).apply { load() }
    assertEquals("跨重启应可见的终态全文", reborn.lookup("om_card_9", null), "🔴 落盘丢失或 load 未装载——重启后引用机器人回复必 miss")
    assertEquals(1, reborn.size)
    }

    @Test
    fun `持久化-FIFO 淘汰同步收缩文件`() {
    val file = tempLedgerFile()
    val ledger = CardReplyLedger(maxEntries = 3, persistenceFile = file)
    ledger.record("om_1", "oc_c", "a")
    ledger.record("om_2", "oc_c", "b")
    ledger.record("om_3", "oc_c", "c")
    ledger.record("om_4", "oc_c", "d") // 淘汰 om_1
    val reborn = CardReplyLedger(maxEntries = 3, persistenceFile = file).apply { load() }
    assertNull(reborn.lookup("om_1", "oc_c"), "淘汰条目不得从文件复活")
    assertEquals(3, reborn.size)
    assertTrue(java.nio.file.Files.readAllLines(file).size <= 3, "🔴 文件必须随淘汰收缩（防无上界增长）：行数=${java.nio.file.Files.readAllLines(file).size}")
    }

    @Test
    fun `持久化-坏行跳过不炸且活条目照常装载`() {
    val file = tempLedgerFile()
    java.nio.file.Files.write(file, listOf("损坏行{{", """{"messageId":"om_ok","markdown":"活条目"}""").joinToString("\n").toByteArray())
    val reborn = CardReplyLedger(persistenceFile = file).apply { load() }
    assertEquals("活条目", reborn.lookup("om_ok", null), "坏行不得阻断后续装载（旧格式行 chatId 落空串，null 查询兼容）")
    assertEquals(1, reborn.size)
    }

    @Test
    fun `持久化-null 文件时退回纯内存（既有测试零改动语义）`() {
    val ledger = CardReplyLedger()  // 不传 persistenceFile
    ledger.record("om_1", "oc_c", "纯内存")
    assertEquals("纯内存", ledger.lookup("om_1", "oc_c"))
    assertEquals(1, ledger.size)
    }

    @Test
    fun `持久化-clear 同步清落盘`() {
    val file = tempLedgerFile()
    val ledger = CardReplyLedger(persistenceFile = file).apply { record("om_1", "oc_c", "x") }
    ledger.clear()
    assertEquals(0, CardReplyLedger(persistenceFile = file).apply { load() }.size, "clear 后文件不得留可装载条目")
    }

    // ==================== P4 chatId 本会话校验（2026-09-26 评审修正 3）====================

    @Test
    fun `chatId 不匹配即 miss-跨会话引用被拒`() {
        val ledger = CardReplyLedger()
        ledger.record("om_card_1", "oc_a", "A 会话的机器人回复")
        assertEquals("A 会话的机器人回复", ledger.lookup("om_card_1", "oc_a"), "同会话查回")
        assertNull(ledger.lookup("om_card_1", "oc_b"), "🔴 跨会话必须 miss（B 会话读不到 A 会话正文）")
    }

    @Test
    fun `chatId null 只匹配未知（空串）条目-防跨会话穿透`() {
        val ledger = CardReplyLedger()
        ledger.record("om_known", "oc_a", "已知会话")
        ledger.record("om_unknown", null, "未知会话（旧格式载入态）")
        assertNull(ledger.lookup("om_known", null), "null 查询不得穿透已知会话条目")
        assertEquals("未知会话（旧格式载入态）", ledger.lookup("om_unknown", null), "null 查询兼容未知（空串）条目")
    }

    @Test
    fun `持久化行携带 chatId-新实例 load 后本会话校验仍成立`() {
        val file = tempLedgerFile()
        CardReplyLedger(persistenceFile = file).apply {
            record("om_p4", "oc_a", "P4 会话维度的正文")
        }
        val reborn = CardReplyLedger(persistenceFile = file).apply { load() }
        assertEquals("P4 会话维度的正文", reborn.lookup("om_p4", "oc_a"), "落盘含 chatId，重启后同会话可查")
        assertNull(reborn.lookup("om_p4", "oc_b"), "重启后跨会话仍拒")
    }
}