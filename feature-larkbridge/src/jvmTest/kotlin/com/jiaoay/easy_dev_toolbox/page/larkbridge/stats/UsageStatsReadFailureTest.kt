package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import com.jiaoay.lark.bridge.observe.AuditEntryJson
import com.jiaoay.lark.bridge.observe.SenderStatsEntry
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * XA-03（重设计批 1）读取失败可见化契约：audit/统计「读到 0 条」与「读取抛异常」
 * 两态必须区分——[jsonlReadFailureMessage] 聚合判定（audit 侧失败优先记因、message
 * 缺失落异常类简单名、双成功返回 null）。
 *
 * 纯函数直测（UsageStatsPanel 无渲染测试——纯逻辑抽测先例同 CockpitPanelLogicTest）。
 */
class UsageStatsReadFailureTest {

    private fun okAudit(size: Int = 1): Result<List<AuditEntryJson>> =
        Result.success(List(size) { auditEntry(ts = it.toLong()) })

    private fun okSender(size: Int = 1): Result<List<SenderStatsEntry>> =
        Result.success(List(size) { senderEntry(ts = it.toLong()) })

    @Test
    fun 双成功与读到零条都归为无失败() {
        assertNull(
            jsonlReadFailureMessage(okAudit(), okSender()),
            "两文件都读成功 ⇒ null（无失败态）",
        )
        // 🔴 卡面核心语义：「读到 0 条」是合法空态，不是失败——不产 loadError（不落假空态判定）
        assertNull(
            jsonlReadFailureMessage(Result.success(emptyList()), Result.success(emptyList())),
            "读到 0 条 ⇒ null（空态由 EmptyStateView 表达，与读取失败严格区分）",
        )
    }

    @Test
    fun audit侧失败优先记因() {
        val msg = jsonlReadFailureMessage(
            audit = Result.failure(IOException("Permission denied")),
            sender = okSender(),
        )
        assertEquals("audit.jsonl 读取失败：Permission denied", msg, "audit 失败优先记因")
    }

    @Test
    fun sender侧单独失败也记因() {
        val msg = jsonlReadFailureMessage(
            audit = okAudit(),
            sender = Result.failure(IOException("disk error")),
        )
        assertEquals("sender_stats.jsonl 读取失败：disk error", msg, "仅 stats 失败 ⇒ 记 sender_stats 因")
    }

    @Test
    fun 双失败保留audit首因() {
        val msg = jsonlReadFailureMessage(
            audit = Result.failure(IOException("cause-a")),
            sender = Result.failure(IOException("cause-b")),
        )
        assertTrue(
            msg == "audit.jsonl 读取失败：cause-a",
            "双失败保留 audit 首因（稳定文案可断言）：$msg",
        )
    }

    @Test
    fun 失败原因缺失message时落异常类简单名() {
        val msg = jsonlReadFailureMessage(
            audit = Result.failure(IOException()),
            sender = okSender(),
        )
        assertEquals("audit.jsonl 读取失败：IOException", msg, "message 空缺 ⇒ 异常类简单名（不产空因）")
    }
}

/** 构造一条最小合法 audit 记录（ts 可指定；其余字段取合法占位）。 */
private fun auditEntry(ts: Long) = AuditEntryJson(
    ts = ts,
    chatId = "oc_test",
    senderOpenId = "ou_test",
    prompt = "p",
    command = "c",
    status = "SUCCESS",
    elapsedMillis = 1L,
)

/** 构造一条最小合法 stats 记录（ts 可指定；其余字段取合法占位）。 */
private fun senderEntry(ts: Long) = SenderStatsEntry(
    ts = ts,
    senderOpenId = "ou_test",
    chatId = "oc_test",
    type = "MESSAGE",
)
