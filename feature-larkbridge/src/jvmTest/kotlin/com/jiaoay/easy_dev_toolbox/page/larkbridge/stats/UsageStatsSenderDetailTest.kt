package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.bridge.observe.AuditEntryJson
import com.jiaoay.lark.bridge.observe.SenderStatsEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 2026-09-14 用户身份链批：[UsageStats] 二级页纯函数扩展的契约测试。
 *
 * 覆盖：[UsageStats.aggregateSenders] limit 参数化与 avatarUrl 聚合、
 * [UsageStats.buildSenderDetail] 单遍聚合（首次/最近活跃、计数、截断、空组 null）、
 * [UsageStats.applySenderAccess] 四动作（互斥纪律、distinct 去重、幂等）。
 */
class UsageStatsSenderDetailTest {

    private fun stat(
        ts: Long,
        openId: String = "ou_a",
        type: String = UsageStats.SENDER_TYPE_MESSAGE,
        userName: String = "",
        avatarUrl: String = "",
    ) = SenderStatsEntry(
        ts = ts, senderOpenId = openId, chatId = "oc", type = type, content = "x",
        userName = userName, avatarUrl = avatarUrl,
    )

    private fun audit(ts: Long, openId: String = "ou_a", prompt: String = "p") = AuditEntryJson(
        ts = ts, chatId = "oc", senderOpenId = openId, prompt = prompt, command = "cmd",
        status = "SUCCESS", elapsedMillis = 10L, kind = "AGENT", backend = "claude",
    )

    // ── aggregateSenders：limit 参数化 + avatarUrl 聚合 ──

    @Test
    fun `limit 默认 10 既有行为零改动`() {
        val entries = (1..12).map { stat(it.toLong(), openId = "ou_$it") }
        assertEquals(10, UsageStats.aggregateSenders(entries).size, "默认截断 MAX_SENDERS=10")
    }

    @Test
    fun `limit Int MAX VALUE 列表态看全部用户`() {
        val entries = (1..12).map { stat(it.toLong(), openId = "ou_$it") }
        val all = UsageStats.aggregateSenders(entries, limit = Int.MAX_VALUE)
        assertEquals(12, all.size, "用户列表态不截断——二级页要看全部用户")
    }

    @Test
    fun `avatarUrl 取该发送者最后一条非空`() {
        val entries = listOf(
            stat(1L, userName = "张三", avatarUrl = "https://a/1.png"),
            stat(2L, userName = "张三"),           // 头像空 ⇒ 沿用前一条非空
            stat(3L, userName = "张三", avatarUrl = "https://a/3.png"),
        )
        val usage = UsageStats.aggregateSenders(entries).single()
        assertEquals("https://a/3.png", usage.avatarUrl, "末条非空头像胜出（同 userName 取最后非空语义）")
        assertEquals("张三", usage.userName)
    }

    // ── buildSenderDetail ──

    @Test
    fun `空组返回 null（两流都无该 openId）`() {
        assertNull(UsageStats.buildSenderDetail("ou_none", stats = emptyList(), audit = emptyList()))
        assertNull(UsageStats.buildSenderDetail("ou_none", stats = listOf(stat(1L)), audit = emptyList()))
    }

    @Test
    fun `首次与最近活跃取 stats 流端点`() {
        val stats = listOf(stat(100L), stat(200L), stat(300L))
        val detail = UsageStats.buildSenderDetail("ou_a", stats, audit = emptyList())!!
        assertEquals(100L, detail.firstActiveMillis)
        assertEquals(300L, detail.lastActiveMillis)
        assertEquals(3, detail.messageCount)
        assertEquals(0, detail.rejectedCount)
    }

    @Test
    fun `被拒计数等于组大小减 MESSAGE 数`() {
        val stats = listOf(
            stat(1L, type = UsageStats.SENDER_TYPE_MESSAGE),
            stat(2L, type = "REJECTED_BLACKLIST"),
            stat(3L, type = "REJECTED_WHITELIST"),
        )
        val detail = UsageStats.buildSenderDetail("ou_a", stats, audit = emptyList())!!
        assertEquals(1, detail.messageCount, "onRejected 接线修复后拒绝事件有了独立分型，MESSAGE 只算接受消息")
        assertEquals(2, detail.rejectedCount)
    }

    @Test
    fun `对话记录截断到尾部 50 条并置 truncated 标记`() {
        val audits = (1..60).map { audit(it.toLong(), prompt = "指令$it") }
        val detail = UsageStats.buildSenderDetail("ou_a", stats = emptyList(), audit = audits)!!
        assertEquals(50, detail.records.size, "截断到 SENDER_DETAIL_MAX_AUDIT=50")
        assertTrue(detail.auditTruncated, "60 > 50 ⇒ 截断标记")
        assertEquals("指令11", detail.records.first().prompt, "takeLast 语义——截断后首条是原第 11 条")
        assertEquals("指令60", detail.records.last().prompt)
    }

    @Test
    fun `记录字段派生映射（kindLabel 与 backendLabel 友好名）`() {
        val detail = UsageStats.buildSenderDetail(
            "ou_a",
            stats = emptyList(),
            audit = listOf(audit(1L).copy(kind = "COMMAND", backend = null)),
        )!!
        val record = detail.records.single()
        assertEquals("指令", record.kindLabel)
        assertEquals("未知", record.backendLabel, "backend null（旧行）归「未知」")
        assertEquals("SUCCESS", record.status)
        assertEquals(10L, record.elapsedMillis)
    }

    // ── applySenderAccess：两动作 + 幂等 ──

    private val base = AccessConfig(trustedUsers = listOf("ou_vip"))

    @Test
    fun `加入信任名单`() {
        val next = UsageStats.applySenderAccess(base, "ou_new", SenderAccessAction.ADD_TO_TRUSTED)
        assertTrue("ou_new" in next.trustedUsers)
        assertTrue("ou_vip" in next.trustedUsers, "既有成员不受影响")
    }

    @Test
    fun `移出信任名单只动目标`() {
        val removed = UsageStats.applySenderAccess(base, "ou_vip", SenderAccessAction.REMOVE_FROM_TRUSTED)
        assertTrue("ou_vip" !in removed.trustedUsers)
        assertEquals(emptyList(), removed.trustedUsers)
    }

    @Test
    fun `重复加入幂等（distinct 去重）`() {
        val once = UsageStats.applySenderAccess(base, "ou_new", SenderAccessAction.ADD_TO_TRUSTED)
        val twice = UsageStats.applySenderAccess(once, "ou_new", SenderAccessAction.ADD_TO_TRUSTED)
        assertEquals(1, twice.trustedUsers.count { it == "ou_new" }, "同 openId 不得重复出现在名单")
        assertEquals(once, twice, "重复动作幂等")
    }
}
