// M4-T8 第一批 · B 组 confirm 测试：确认状态机 + 🔴 BC-476 超时钳制 + 🔴 BC-474/475 注销语义。
// 来源：legacy `confirm/ConfirmServiceTest.kt`（107 行 / 8 条）**逐条移植** + BC §10.3 抢救断言。
// 批 ④（2026-09-27 屏④）：riskLevel 透传 + 裁决 Ok 快照捎带 requestId（短标识对账）。
package com.jiaoay.lark.bridge.confirm

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ConfirmService]：BC §3 主契约表 `confirm/ConfirmServiceTest` 条目 + BC §10.3 三项抢救断言。
 *
 * 状态流转：PENDING → APPROVED / DENIED / TIMEOUT（等同拒绝）/ CANCELLED（随停止 kill 进程）。
 * 防替答：裁决校验 chatId + senderOpenId 与发起时一致。
 */
class ConfirmServiceTest {

    // ── 旧 8 条逐条移植 ──

    @Test
    fun `批准后等待方收到 APPROVED`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "rm -rf build", effectiveTimeoutMillis = 5_000)
        val waiter = async { service.awaitDecision(request, 5_000) }
        delay(50)
        val result = service.decide(request.requestId.takeLast(4), "oc_1", "ou_a", true)
        assertTrue(result is ConfirmService.DecideResult.Ok, "原发送者裁决应成功：$result")
        assertEquals(ConfirmService.Decision.APPROVED, waiter.await())
        assertEquals(0, service.pendingCount(), "裁决后应注销在途条目")
    }

    @Test
    fun `拒绝后等待方收到 DENIED`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Write", "/etc/passwd", effectiveTimeoutMillis = 5_000)
        val waiter = async { service.awaitDecision(request, 5_000) }
        delay(50)
        val result = service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_a", false)
        assertTrue(result is ConfirmService.DecideResult.Ok)
        assertEquals(ConfirmService.Decision.DENIED, waiter.await())
        assertEquals(0, service.pendingCount())
    }

    @Test
    fun `超时返回 TIMEOUT 等同拒绝不抛异常`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "rm -rf /tmp/x", effectiveTimeoutMillis = 5_000)
        val decision = service.awaitDecision(request, 100)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision, "超时应返回 TIMEOUT 而非抛异常")
        assertEquals(0, service.pendingCount(), "🔴 超时后必须注销在途条目（否则内存泄漏）")
    }

    @Test
    fun `非原发送者确认被拒（防替答）`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "rm -rf build", effectiveTimeoutMillis = 5_000)
        val waiter = async { service.awaitDecision(request, 300) }
        delay(30)
        // 同群他人裁决
        assertEquals(
            ConfirmService.DecideResult.Forbidden,
            service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_other", true),
            "非原发送者应被拒",
        )
        // 同人在别的会话裁决
        assertEquals(
            ConfirmService.DecideResult.Forbidden,
            service.decide(ConfirmService.shortId(request.requestId), "oc_other", "ou_a", true),
            "跨会话裁决应被拒",
        )
        assertEquals(ConfirmService.Decision.TIMEOUT, waiter.await(), "防替答后等待方最终超时")
    }

    @Test
    fun `未知短标识返回 NotFound`() {
        val service = ConfirmService()
        service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val result = service.decide("ffff", "oc_1", "ou_a", true)
        assertEquals(ConfirmService.DecideResult.NotFound, result)
    }

    @Test
    fun `cancelAll 后等待方收到 CANCELLED`() = runBlocking {
        val service = ConfirmService()
        val r1 = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val r2 = service.newRequest("oc_1", "ou_a", "Write", "y", effectiveTimeoutMillis = 5_000)
        assertEquals(2, service.pendingCount())
        val w1 = async { service.awaitDecision(r1, 5_000) }
        val w2 = async { service.awaitDecision(r2, 5_000) }
        delay(30)
        service.cancelAll()
        assertEquals(ConfirmService.Decision.CANCELLED, w1.await())
        assertEquals(ConfirmService.Decision.CANCELLED, w2.await())
        assertEquals(0, service.pendingCount(), "cancelAll 后在途表应清空")
    }

    @Test
    fun `短标识提取与匹配`() {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val shortId = ConfirmService.shortId(request.requestId)
        assertTrue(shortId.length <= ConfirmService.SHORT_ID_CHARS, "短标识应不超过 4 字符：$shortId")
        assertTrue(request.requestId.endsWith(shortId), "短标识应是 requestId 末段：${request.requestId}")
        // 匹配：短标识本身、完整 requestId 都能命中
        assertTrue(service.decide(shortId, "oc_1", "ou_a", true) is ConfirmService.DecideResult.Ok)
    }

    @Test
    fun `入参摘要截断`() {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x".repeat(500), effectiveTimeoutMillis = 5_000)
        assertTrue(
            request.inputSummary.length <= ConfirmService.INPUT_SUMMARY_CHARS,
            "摘要应截断到 ${ConfirmService.INPUT_SUMMARY_CHARS}，实际 ${request.inputSummary.length}",
        )
        assertEquals(120, ConfirmService.INPUT_SUMMARY_CHARS)
    }

    // ── 🔴 BC-476 抢救：确认超时钳制（旧坐标 LarkBridge.kt:472 / :478-479） ──

    /**
     * 🔒 BC-476 实测断言：`(0)==10_000`、`(-30)==10_000`、`(120)==120_000`。
     * 🔴 BC-476 的失败后果：不钳制 ⇒ 确认卡片刚发出即超时拒绝 ⇒ 「agent 什么都不敢做」。
     */
    @Test
    fun `BC-476 确认超时下限 10 秒钳制`() {
        assertEquals(10L, ConfirmService.MIN_CONFIRM_TIMEOUT_SECONDS)
        assertEquals(10_000L, ConfirmService.clampedConfirmTimeoutMillis(0), "0 秒必须钳到下限")
        assertEquals(10_000L, ConfirmService.clampedConfirmTimeoutMillis(-30), "负值必须钳到下限")
        assertEquals(10_000L, ConfirmService.clampedConfirmTimeoutMillis(Long.MIN_VALUE), "Long.MIN_VALUE 必须钳到下限")
        assertEquals(10_000L, ConfirmService.clampedConfirmTimeoutMillis(-1_000L), "-1000 秒必须钳到下限")
        assertEquals(10_000L, ConfirmService.clampedConfirmTimeoutMillis(-1L), "-1 秒必须钳到下限")
        assertEquals(10_000L, ConfirmService.clampedConfirmTimeoutMillis(9), "9 秒仍低于下限")
        assertEquals(10_000L, ConfirmService.clampedConfirmTimeoutMillis(10), "恰在下限不被改写")
        assertEquals(120_000L, ConfirmService.clampedConfirmTimeoutMillis(120), "合法值原样换算")
        assertEquals(3_600_000L, ConfirmService.clampedConfirmTimeoutMillis(3_600))
    }

    /** 🔒 上限字面值与 `resolveConfirmTimeoutUpperBound`（旧坐标 BridgeSecurityCard.kt:34/:40-41） */
    @Test
    fun `确认超时上限随 agent 总超时收敛`() {
        assertEquals(3_600L, ConfirmService.MAX_CONFIRM_TIMEOUT_SECONDS)
        // `minOf(3600, (minutes * 60).coerceAtLeast(1))`
        assertEquals(600L, ConfirmService.resolveConfirmTimeoutUpperBound(10), "10 分钟 → 600 秒")
        assertEquals(60L, ConfirmService.resolveConfirmTimeoutUpperBound(1), "1 分钟 → 60 秒")
        assertEquals(1L, ConfirmService.resolveConfirmTimeoutUpperBound(0), "0 分钟 → coerceAtLeast(1)")
        assertEquals(1L, ConfirmService.resolveConfirmTimeoutUpperBound(-5), "负值 → coerceAtLeast(1)")
        assertEquals(3_600L, ConfirmService.resolveConfirmTimeoutUpperBound(60), "60 分钟恰为上限")
        assertEquals(3_600L, ConfirmService.resolveConfirmTimeoutUpperBound(10_000), "超上限被 minOf 收敛")
        // 语义：确认等待不得超过 agent 总超时（否则 agent 已被 kill 而确认还挂着）
        assertTrue(
            ConfirmService.resolveConfirmTimeoutUpperBound(2) <= ConfirmService.MAX_CONFIRM_TIMEOUT_SECONDS,
        )
    }

    /** 🔴 钳制后的值确实作用于 awaitDecision（超时下限端到端生效，不只是纯函数） */
    @Test
    fun `钳制后的超时值实际作用于等待`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val timeoutMillis = ConfirmService.clampedConfirmTimeoutMillis(0)
        val started = System.currentTimeMillis()
        val decision = service.awaitDecision(request, timeoutMillis)
        val elapsed = System.currentTimeMillis() - started
        assertEquals(ConfirmService.Decision.TIMEOUT, decision)
        assertTrue(elapsed >= 10_000L - 500L, "🔴 钳制后至少等满 10s，实际 ${elapsed}ms")
    }.also { /* 单条约 10s，可接受（这是 BC-476 的端到端证据） */ }

    // ── 🔴 BC-474/475 抢救：注销语义（旧 companion `rejectionHolder` 静态全局可变状态） ──

    /**
     * 🔴 D16 + BC-474：旧实现把「最近一次拒绝」挂在 companion `@Volatile var rejectionHolder` 上
     * （旧声明 :604-605），导致**跨实例串味**——stop() 之后旧实例仍能被静态字段触达并产生副作用。
     * 新实现改构造注入（`pending` 是实例级 ConcurrentHashMap），等价断言：**两个实例互不干扰**。
     */
    @Test
    fun `BC-474 两个实例互不干扰（旧 rejectionHolder 静态串味已消除）`() = runBlocking {
        val a = ConfirmService()
        val b = ConfirmService()
        val ra = a.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val rb = b.newRequest("oc_1", "ou_a", "Bash", "y", effectiveTimeoutMillis = 5_000)
        assertNotEquals(ra.requestId, rb.requestId, "实例各自的 seq 独立 ⇒ requestId 不共享序列")

        // a 的短标识在 b 上必须 NotFound（不是被静态字段接住）
        assertEquals(ConfirmService.DecideResult.NotFound, b.decide(ConfirmService.shortId(ra.requestId), "oc_1", "ou_a", true))
        assertEquals(ConfirmService.DecideResult.NotFound, a.decide(ConfirmService.shortId(rb.requestId), "oc_1", "ou_a", true))

        // a 裁决成功，b 的等待方不受影响仍超时
        val wa = async { a.awaitDecision(ra, 5_000) }
        val wb = async { b.awaitDecision(rb, 200) }
        delay(20)
        assertTrue(a.decide(ConfirmService.shortId(ra.requestId), "oc_1", "ou_a", true) is ConfirmService.DecideResult.Ok)
        assertEquals(ConfirmService.Decision.APPROVED, wa.await())
        assertEquals(ConfirmService.Decision.TIMEOUT, wb.await())
        // 🔴 注销发生在 `awaitDecision` 返回时（与旧实现一致：`decide` 只 complete deferred）
        assertEquals(0, a.pendingCount())
        assertEquals(0, b.pendingCount())
    }

    /** 🔴 BC-475：`cancelAll()` 之后旧实例不得再产生副作用（等价于旧的「注销」语义） */
    @Test
    fun `BC-475 cancelAll 后旧实例不再接受裁决`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val waiter = async { service.awaitDecision(request, 5_000) }
        delay(30)
        service.cancelAll()
        assertEquals(ConfirmService.Decision.CANCELLED, waiter.await())
        // 🔴 注销后同一短标识必须 NotFound（不得残留可裁决条目）
        assertEquals(
            ConfirmService.DecideResult.NotFound,
            service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_a", true),
            "🔴 BC-475：cancelAll 后不得再接受裁决",
        )
        assertEquals(0, service.pendingCount())
        assertTrue(service.pendingRequests().isEmpty())
    }

    /** 🔴 BC-475 补充：`awaitDecision` 返回后条目必被移除（三条返回路径都注销） */
    @Test
    fun `三条返回路径都注销在途条目`() = runBlocking {
        val service = ConfirmService()

        // ① 正常裁决
        val r1 = service.newRequest("oc_1", "ou_a", "Bash", "1", effectiveTimeoutMillis = 5_000)
        val w1 = async { service.awaitDecision(r1, 5_000) }
        delay(20)
        service.decide(ConfirmService.shortId(r1.requestId), "oc_1", "ou_a", true)
        w1.await()
        assertEquals(0, service.pendingCount(), "路径①：裁决后注销")

        // ② 超时
        val r2 = service.newRequest("oc_1", "ou_a", "Bash", "2", effectiveTimeoutMillis = 5_000)
        service.awaitDecision(r2, 100)
        assertEquals(0, service.pendingCount(), "路径②：超时后注销")

        // ③ 取消
        val r3 = service.newRequest("oc_1", "ou_a", "Bash", "3", effectiveTimeoutMillis = 5_000)
        val w3 = async { service.awaitDecision(r3, 5_000) }
        delay(20)
        service.cancelAll()
        w3.await()
        assertEquals(0, service.pendingCount(), "路径③：取消后注销")
    }

    /** 🔴 `awaitDecision` 对已注销（未知）请求直接返回 CANCELLED，不挂起 */
    @Test
    fun `对未知请求 awaitDecision 直接返回 CANCELLED`() = runBlocking {
        val service = ConfirmService()
        val ghost = ConfirmService.ConfirmRequest(
            requestId = "cf-999-dead",
            chatId = "oc_1",
            senderOpenId = "ou_a",
            toolName = "Bash",
            inputSummary = "x",
            createdAt = System.currentTimeMillis(),
        )
        val started = System.currentTimeMillis()
        assertEquals(ConfirmService.Decision.CANCELLED, service.awaitDecision(ghost, 60_000))
        assertTrue(
            System.currentTimeMillis() - started < 1_000L,
            "🔴 未知请求不得挂起（否则会挂满 60s 超时）",
        )
    }

    // ── 契约形态 ──

    /** 🔴 Decision 4 值字面值锁死（MCP server 返回给 claude 的语义 + 审计分桶） */
    @Test
    fun `Decision 四值字面值与顺序锁死`() {
        assertEquals(
            listOf("APPROVED", "DENIED", "TIMEOUT", "CANCELLED"),
            ConfirmService.Decision.entries.map { it.name },
        )
    }

    /** 🔴 requestId 形态：`cf-<seq>-<hex4>`，seq 单调递增，hex4 定长补零 */
    @Test
    fun `requestId 形态与序列单调性`() {
        val service = ConfirmService()
        val ids = (1..50).map { service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000).requestId }
        assertEquals(50, ids.distinct().size, "requestId 不得重复")
        ids.forEach { id ->
            assertTrue(id.startsWith("cf-"), "前缀逐字：$id")
            val parts = id.removePrefix("cf-").split('-')
            assertEquals(2, parts.size, "应为 cf-<seq>-<hex4>：$id")
            assertTrue(parts[0].toLongOrNull() != null, "seq 段应为数字：$id")
            assertEquals(4, parts[1].length, "🔒 hex 后缀定长 4（padStart）：$id")
            assertTrue(parts[1].all { it in "0123456789abcdef" }, "hex 后缀应为小写十六进制：$id")
        }
        val seqs = ids.map { it.removePrefix("cf-").split('-')[0].toLong() }
        assertEquals(seqs.sorted(), seqs, "seq 必须单调递增")
        assertEquals((1L..50L).toList(), seqs, "seq 从 1 开始且无跳号")
    }

    /** 🔴 `matchesIdPrefix` 的容错：带 `cf-` 前缀的输入也能匹配；空白输入永不匹配 */
    @Test
    fun `短标识匹配容错与空白拒绝`() {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val shortId = ConfirmService.shortId(request.requestId)
        assertTrue(service.decide("cf-$shortId", "oc_1", "ou_a", true) is ConfirmService.DecideResult.Ok)

        val service2 = ConfirmService()
        service2.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        assertEquals(ConfirmService.DecideResult.NotFound, service2.decide("", "oc_1", "ou_a", true), "空白短标识不得匹配")
        assertEquals(ConfirmService.DecideResult.NotFound, service2.decide("   ", "oc_1", "ou_a", true))
        assertEquals(ConfirmService.DecideResult.NotFound, service2.decide("cf-", "oc_1", "ou_a", true))
    }

    /** 🔴 短标识前缀匹配语义：1-2 字符前缀也能命中（单 chat 串行下同时最多 1 个 PENDING） */
    @Test
    fun `短标识按前缀匹配而非全等`() {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val shortId = ConfirmService.shortId(request.requestId)
        val prefix = shortId.take(1)
        assertTrue(
            service.decide(prefix, "oc_1", "ou_a", true) is ConfirmService.DecideResult.Ok,
            "1 字符前缀应能命中（tail.startsWith）：$shortId / $prefix",
        )
    }

    /** 🔴 `newRequest` 立即登记在途条目（不等 awaitDecision） */
    @Test
    fun `newRequest 立即登记在途条目`() {
        val service = ConfirmService()
        assertEquals(0, service.pendingCount())
        val r1 = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        assertEquals(1, service.pendingCount())
        service.newRequest("oc_1", "ou_a", "Write", "y", effectiveTimeoutMillis = 5_000)
        assertEquals(2, service.pendingCount())
        val snapshot = service.pendingRequests()
        assertEquals(2, snapshot.size)
        assertTrue(snapshot.any { it.requestId == r1.requestId })
        assertTrue(snapshot.all { it.createdAt > 0L })
        assertTrue(snapshot.all { it.chatId == "oc_1" && it.senderOpenId == "ou_a" })
    }

    /** 🔴 ConfirmRequest 字段快照语义（防替答依据必须与发起时一致，不随后续变化） */
    @Test
    fun `ConfirmRequest 是发起时快照`() {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "rm -rf build", effectiveTimeoutMillis = 5_000)
        assertEquals("oc_1", request.chatId)
        assertEquals("ou_a", request.senderOpenId)
        assertEquals("Bash", request.toolName)
        assertEquals("rm -rf build", request.inputSummary)
        assertEquals("", request.riskLevel, "默认构造（4 参形态）riskLevel 为空串 = 未分级")
        assertTrue(request.requestId.isNotBlank())
        assertTrue(request.createdAt in (System.currentTimeMillis() - 5_000)..(System.currentTimeMillis() + 5_000))
        // data class：等值语义稳定（UI/审计按值比较）
        assertEquals(request, request.copy())
    }

    /** 🔴 `shortId` 对非 `cf-` 形态的输入不抛异常（回退原串） */
    @Test
    fun `shortId 对异常输入安全`() {
        assertEquals("", ConfirmService.shortId("cf-"))
        assertEquals("abcd", ConfirmService.shortId("cf-1-abcdef"))
        assertEquals("x", ConfirmService.shortId("x"))
        assertEquals("", ConfirmService.shortId(""))
        assertEquals(4, ConfirmService.SHORT_ID_CHARS)
    }

    /** 🔴 并发裁决：同一请求被并发裁决只有一次生效，等待方拿到确定值 */
    @Test
    fun `并发裁决只生效一次且等待方不悬挂`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val waiter = async { service.awaitDecision(request, 5_000) }
        delay(30)
        val shortId = ConfirmService.shortId(request.requestId)
        val results = (1..8).map { i ->
            async { service.decide(shortId, "oc_1", "ou_a", i % 2 == 0) }
        }.map { it.await() }
        val okCount = results.count { it is ConfirmService.DecideResult.Ok }
        assertTrue(okCount >= 1, "至少一次裁决应成功：$results")
        val decision = waiter.await()
        assertTrue(
            decision == ConfirmService.Decision.APPROVED || decision == ConfirmService.Decision.DENIED,
            "等待方必须拿到确定裁决，不得 TIMEOUT/CANCELLED：$decision",
        )
        assertEquals(0, service.pendingCount())
    }

    // ── 2026-09-15 确认卡已答态批：cardMessageId 回填 + 裁决快照捎带 ──

    /** 🔴 发卡后回填 ⇒ 裁决 Ok 快照捎带 cardMessageId/toolName/inputSummary（已答态更新的定位依据）。 */
    @Test
    fun `裁决 Ok 捎带确认卡 messageId 与请求上下文`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "rm -rf build", effectiveTimeoutMillis = 5_000)
        service.recordCardMessageId(request.requestId, "om_confirm_card_1")
        val waiter = async { service.awaitDecision(request, 5_000) }
        delay(30)
        val result = service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_a", true)
        assertTrue(result is ConfirmService.DecideResult.Ok, "裁决应成功：$result")
        result as ConfirmService.DecideResult.Ok
        assertEquals("om_confirm_card_1", result.cardMessageId, "Ok 须捎带回填的确认卡 messageId")
        assertEquals("Bash", result.toolName, "Ok 须捎带原工具名（已答卡保留原问题上下文）")
        assertEquals("rm -rf build", result.inputSummary, "Ok 须捎带原参数摘要")
        assertEquals(ConfirmService.Decision.APPROVED, waiter.await())
    }

    /** 🔴 未回填（未发卡/SyntheticGateway 默认实现）⇒ Ok.cardMessageId 为 null ⇒ 调用方跳过已答态更新。 */
    @Test
    fun `未回填卡时裁决 Ok 捎带 null messageId`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val waiter = async { service.awaitDecision(request, 5_000) }
        delay(20)
        val result = service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_a", false)
        assertTrue(result is ConfirmService.DecideResult.Ok)
        assertEquals(null, (result as ConfirmService.DecideResult.Ok).cardMessageId, "未回填 ⇒ null（跳过已答态）")
        assertEquals(ConfirmService.Decision.DENIED, waiter.await())
    }

    /** 🔴 回填口对未知 requestId 静默忽略（裁决竞态下条目已注销——绝不抛）。 */
    @Test
    fun `回填对已注销条目静默忽略`() {
        val service = ConfirmService()
        service.recordCardMessageId("cf-999-dead", "om_x") // 不存在 ⇒ 无异常
        assertEquals(0, service.pendingCount())
    }

    // ── 批 ④（2026-09-27 屏④）：riskLevel 透传 + Ok.requestId 快照捎带 ──

    /** 🔴 newRequest 第 5 参 riskLevel 透传进 ConfirmRequest 快照（确认卡风险徽标行数据源）。 */
    @Test
    fun `批④ newRequest 透传 riskLevel 到请求快照`() {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "rm -rf build", riskLevel = "HIGH", effectiveTimeoutMillis = 5_000)
        assertEquals("HIGH", request.riskLevel, "riskLevel 须透传进快照：${request.riskLevel}")
        // 默认空串 = 未知/未分级（既有 4 参调用点零改动）
        assertEquals("", service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000).riskLevel)
    }

    /** 🔴 decide 的 Ok 快照捎带完整 requestId（已答态「单据：cf-xxxx」短标识对账的数据源）。 */
    @Test
    fun `批④ 裁决 Ok 快照捎带完整 requestId`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", riskLevel = "MEDIUM", effectiveTimeoutMillis = 5_000)
        val waiter = async { service.awaitDecision(request, 5_000) }
        delay(30)
        val result = service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_a", true)
        assertTrue(result is ConfirmService.DecideResult.Ok, "裁决应成功：$result")
        assertEquals(request.requestId, (result as ConfirmService.DecideResult.Ok).requestId, "Ok 须捎带完整 requestId")
        waiter.await()
        Unit
    }

    /** 🔴 cf- + shortId 与完整 requestId 的关系：前缀匹配语义（末段 hex4），对账用非唯一键。 */
    @Test
    fun `批④ cf 加 shortId 是末段前缀对账键而非唯一键`() {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        val shortId = ConfirmService.shortId(request.requestId)
        // cf-<shortId> 与完整 requestId 的构成一致：cf-<seq>-<hex4> 中的 hex4
        assertTrue(
            request.requestId.startsWith("cf-") && request.requestId.endsWith(shortId),
            "cf- + shortId 应与完整 requestId 末段对账一致：${request.requestId} / $shortId",
        )
        // seq 不同 hex4 相同 ⇒ cf-+shortId 非唯一（两条都能被 matchesIdPrefix 命中）——对账语义的边界如实钉住
        val twin = ConfirmService.ConfirmRequest(
            requestId = "cf-999-$shortId",
            chatId = "oc_1",
            senderOpenId = "ou_a",
            toolName = "Bash",
            inputSummary = "x",
            createdAt = 0L,
        )
        assertTrue(
            twin.requestId != request.requestId && twin.requestId.endsWith(shortId),
            "seq 不同 hex4 相同 ⇒ 同一 cf-shortId 可对应多个完整 id（非唯一键）",
        )
    }

    // ── 批 ⑤（2026-09-27 屏⑤）：pendingViews 只读投影（驾驶舱「待你确认」区块数据面） ──

    /** 🔴 pendingViews 快照映射：全字段透传（含 effectiveTimeoutMillis 记账值与未回填的 null 卡锚点）。 */
    @Test
    fun `批⑤ pendingViews 快照映射全字段`() {
        val service = ConfirmService()
        val request = service.newRequest(
            "oc_1", "ou_a", "Bash", "rm -rf build",
            riskLevel = "HIGH",
            effectiveTimeoutMillis = 120_000L,
        )
        val views = service.pendingViews()
        assertEquals(1, views.size, "一条在途请求 ⇒ 一条视图")
        val view = views.single()
        assertEquals(request.requestId, view.requestId, "requestId 透传")
        assertEquals("oc_1", view.chatId)
        assertEquals("ou_a", view.senderOpenId)
        assertEquals("Bash", view.toolName)
        assertEquals("rm -rf build", view.inputSummary)
        assertEquals("HIGH", view.riskLevel)
        assertEquals(request.createdAt, view.createdAt)
        assertEquals(120_000L, view.effectiveTimeoutMillis, "生效超时记账值须透传（剩余秒的计算依据）")
        assertEquals(null, view.cardMessageId, "未发卡 ⇒ 卡锚点 null（UI 显示「已读 —」）")
    }

    /** 🔴 发卡回填后 pendingViews 的 cardMessageId 可见（已读回执查询锚点）；裁决注销后视图同步消失。 */
    @Test
    fun `批⑤ 回填后视图含卡锚点且裁决后视图消失`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 60_000L)
        service.recordCardMessageId(request.requestId, "om_confirm_card_9")
        val before = service.pendingViews().single()
        assertEquals("om_confirm_card_9", before.cardMessageId, "回填后视图须可见卡锚点（已读查询用）")
        val waiter = async { service.awaitDecision(request, 5_000) }
        delay(20)
        service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_a", true)
        waiter.await()
        assertTrue(service.pendingViews().isEmpty(), "裁决注销后视图同步消失（PENDING 非空才渲染的缺席形态）")
    }

    /** 🔴 newRequest 第 6 参无默认值：省略即编译错（两调用点显式传的契约由编译器背书——本用例钉全字段形态）。 */
    @Test
    fun `批⑤ 两条路径的生效超时记账互相独立`() {
        val service = ConfirmService()
        val mcpPath = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 300_000L)
        val gatePath = service.newRequest("oc_2", "ou_b", "create_group_chat", "建群", effectiveTimeoutMillis = 60_000L)
        val views = service.pendingViews().associateBy { it.requestId }
        assertEquals(300_000L, views[mcpPath.requestId]!!.effectiveTimeoutMillis, "MCP server 路径记账值")
        assertEquals(60_000L, views[gatePath.requestId]!!.effectiveTimeoutMillis, "L2 工具路径记账值")
    }

    // ── LB-06（重设计批 5 2026-10-01）：超时回调（原确认卡已超时态的数据面） ──

    /** 🔒 测试 seam：记录回调快照的收集器（payload 断言用）。 */
    private class RecordingTimedOut {
        val snapshots = CopyOnWriteArrayList<ConfirmService.TimedOutConfirm>()
        val invoke = { snapshot: ConfirmService.TimedOutConfirm ->
            snapshots.add(snapshot)
            Unit
        }
    }

    /**
     * 🔴 LB-06：超时后回调被调且 payload 是完整快照（request 全量 + cardMessageId 现读）。
     * 单据行要 requestId、发卡目标要 chatId——三参散传不足，故断言 request 对象整体捎带。
     */
    @Test
    fun `LB-06 超时后回调被调且快照捎带 request 全量与卡锚点`() = runBlocking {
        val recorder = RecordingTimedOut()
        val service = ConfirmService(onTimedOut = recorder.invoke)
        val request = service.newRequest("oc_t", "ou_a", "Bash", "rm -rf build", riskLevel = "HIGH", effectiveTimeoutMillis = 300_000L)
        service.recordCardMessageId(request.requestId, "om_confirm_card_timeout")
        val decision = service.awaitDecision(request, 100)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision, "超时返回不变（回调是增强面）")
        assertEquals(1, recorder.snapshots.size, "超时回调恰被调 1 次")
        val snapshot = recorder.snapshots.single()
        assertEquals(request.requestId, snapshot.request.requestId, "快照捎带完整 requestId（单据行数据源）")
        assertEquals("oc_t", snapshot.request.chatId, "快照捎带 chatId（发卡目标）")
        assertEquals("ou_a", snapshot.request.senderOpenId)
        assertEquals("Bash", snapshot.request.toolName)
        assertEquals("rm -rf build", snapshot.request.inputSummary)
        assertEquals("HIGH", snapshot.request.riskLevel)
        assertEquals("om_confirm_card_timeout", snapshot.cardMessageId, "快照捎带回填的确认卡 messageId（已超时态更新定位依据）")
        assertEquals(0, service.pendingCount(), "超时后照旧注销在途条目")
    }

    /** 🔴 LB-06：未回填（未发卡/SyntheticGateway 默认实现）⇒ 快照 cardMessageId 为 null ⇒ 调用方跳过更新。 */
    @Test
    fun `LB-06 未回填卡时超时快照捎带 null 卡锚点`() = runBlocking {
        val recorder = RecordingTimedOut()
        val service = ConfirmService(onTimedOut = recorder.invoke)
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 300_000L)
        val decision = service.awaitDecision(request, 100)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision)
        assertEquals(null, recorder.snapshots.single().cardMessageId, "未发卡 ⇒ null（调用方跳过已超时态更新，行为同旧版）")
    }

    /**
     * 🔴 LB-06：回调抛异常 ⇒ awaitDecision 仍返回 TIMEOUT 不抛（「卡更新失败不阻断主流程」）。
     * 回调侧的 runCatching 只 log 降级由装配闭包负责（本用例直测 service 层的双保险：service 层绝不外抛）。
     */
    @Test
    fun `LB-06 回调抛异常不阻断超时返回`() = runBlocking {
        val service = ConfirmService(
            onTimedOut = { throw IllegalStateException("卡片更新通道炸了") },
        )
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 300_000L)
        val decision = service.awaitDecision(request, 100)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision, "🔴 回调异常必须被吞（已超时卡是增强面，绝不阻断裁决语义）")
        assertEquals(0, service.pendingCount(), "超时后照旧注销（异常不破坏注销路径）")
    }

    /** 🔴 LB-06：裁决/取消返回路径回调不被调（acceptance「裁决/文字回执链路零变化」）。 */
    @Test
    fun `LB-06 裁决与取消路径回调零触发`() = runBlocking {
        val recorder = RecordingTimedOut()
        val service = ConfirmService(onTimedOut = recorder.invoke)

        // ① 裁决路径
        val r1 = service.newRequest("oc_1", "ou_a", "Bash", "1", effectiveTimeoutMillis = 300_000L)
        val w1 = async { service.awaitDecision(r1, 5_000) }
        delay(20)
        service.decide(ConfirmService.shortId(r1.requestId), "oc_1", "ou_a", true)
        assertEquals(ConfirmService.Decision.APPROVED, w1.await())

        // ② 取消路径
        val r2 = service.newRequest("oc_1", "ou_a", "Bash", "2", effectiveTimeoutMillis = 300_000L)
        val w2 = async { service.awaitDecision(r2, 5_000) }
        delay(20)
        service.cancelAll()
        assertEquals(ConfirmService.Decision.CANCELLED, w2.await())

        assertEquals(0, recorder.snapshots.size, "🔴 裁决/取消路径不得触发超时回调（decide/cancelAll 语义零改动）")
    }

    /** 🔴 LB-06：默认构造（无注入）超时行为同旧版零变化（既有 10+ 处 ConfirmService() 构造点零破坏）。 */
    @Test
    fun `LB-06 默认构造超时行为与旧版逐字一致`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 300_000L)
        val decision = service.awaitDecision(request, 100)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision, "默认 {} 回调 ⇒ 超时路径行为与旧版一致")
        assertEquals(0, service.pendingCount())
    }

    // ── LB-20（重设计批 5 2026-10-01）：未读催办 + 窗口顺延（二段等待状态机） ──

    /** 🔒 测试 seam：催办收集器（ReminderNotice 载荷断言用）。 */
    private class RecordingReminder {
        val notices = CopyOnWriteArrayList<ConfirmService.ReminderNotice>()
        val invoke = { notice: ConfirmService.ReminderNotice ->
            notices.add(notice)
            Unit
        }
    }

    /**
     * 🔴 LB-20：未读确认（readCount==0）在催办点收恰 1 次文字催办 + 窗口顺延 + 总时长 > 原窗口 + 超时返回 TIMEOUT。
     * 断言面：催办恰发一次 / 顺延段后仍 TIMEOUT / 总等待 = firstLeg + extension（> 原窗口）。
     */
    @Test
    fun `LB-20 未读确认催办恰一次且窗口顺延后仍超时`() = runBlocking {
        val reminders = RecordingReminder()
        val service = ConfirmService(
            readCountProvider = { 0 },  // 未读
            onReminder = reminders.invoke,
        )
        val request = service.newRequest("oc_1", "ou_a", "Bash", "rm -rf build", effectiveTimeoutMillis = 400L)
        service.recordCardMessageId(request.requestId, "om_card_unread")
        val started = System.currentTimeMillis()
        val decision = service.awaitDecision(request, 400)
        val elapsed = System.currentTimeMillis() - started
        assertEquals(ConfirmService.Decision.TIMEOUT, decision, "顺延后仍超时 ⇒ TIMEOUT（等同拒绝）")
        assertEquals(1, reminders.notices.size, "🔴 催办每请求至多 1 次（防刷屏）——恰一次")
        val notice = reminders.notices.single()
        assertEquals("om_card_unread", notice.cardMessageId, "催办载荷捎带卡锚点")
        assertEquals("oc_1", notice.request.chatId, "催办载荷捎带 chatId（发催办目标）")
        assertEquals(200L, notice.remainingMillis, "顺延段剩余 = 窗口 1/2（400/2）")
        // 🔴 顺延后总时长 > 原窗口（催办点在 3/4 处 + 顺延 1/2 窗 ⇒ 300ms + 200ms = 500ms > 400ms）。
        assertTrue(elapsed >= 400L, "总等待必须超过原窗口（顺延生效）——实际 ${elapsed}ms")
        assertTrue(elapsed < 1_500L, "总等待有界（顺延只一次，不无限续期）——实际 ${elapsed}ms")
        assertEquals(0, service.pendingCount(), "超时后照旧注销在途条目")
    }

    /** 🔴 LB-20：顺延记账同步（批⑤「记账值与生效值同源」契约守护）——pendingViews 的剩余秒随顺延续走。 */
    @Test
    fun `LB-20 顺延记账同步进 pendingViews 剩余秒`() = runBlocking {
        val reminders = RecordingReminder()
        val service = ConfirmService(
            readCountProvider = { 0 },
            onReminder = reminders.invoke,
        )
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 400L)
        service.recordCardMessageId(request.requestId, "om_card_view")
        // 催办发生在第一段末（约 300ms 处）——顺延后记账值应 = 原窗口 + 顺延量。
        val waiter = async { service.awaitDecision(request, 400) }
        // 轮询等催办落地（记账 addAndGet 与回调同点发生）。
        var view: com.jiaoay.lark.bridge.confirm.PendingConfirmView? = null
        val deadline = System.nanoTime() + 2_000_000_000
        while (System.nanoTime() < deadline) {
            val candidate = service.pendingViews().firstOrNull()
            if (candidate != null && candidate.effectiveTimeoutMillis == 600L) {
                view = candidate
                break
            }
            delay(10)
        }
        assertEquals(ConfirmService.Decision.TIMEOUT, waiter.await())
        assertEquals(600L, view?.effectiveTimeoutMillis, "🔴 顺延必须 addAndGet 进记账值（400 + 200）——否则驾驶舱剩余秒在原到期点提前归零")
    }

    /** 🔴 LB-20：已读未决（readCount>0）⇒ 静默到期零催办零顺延（对已读用户刷屏的风险 > 漏催一次）。 */
    @Test
    fun `LB-20 已读未决静默到期零催办`() = runBlocking {
        val reminders = RecordingReminder()
        val service = ConfirmService(
            readCountProvider = { 1 },  // 已读
            onReminder = reminders.invoke,
        )
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 400L)
        service.recordCardMessageId(request.requestId, "om_card_read")
        val decision = service.awaitDecision(request, 400)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision, "已读未决静默到期")
        assertEquals(0, reminders.notices.size, "已读 ⇒ 零催办（不刷屏）")
        assertEquals(0, service.pendingCount())
    }

    /** 🔴 LB-20：readCount null（能力缺席/查询失败）⇒ fail-safe 静默到期不催不延（SyntheticGateway 默认）。 */
    @Test
    fun `LB-20 readCount 缺席 fail-safe 静默到期`() = runBlocking {
        val reminders = RecordingReminder()
        val service = ConfirmService(
            readCountProvider = { null },  // 能力缺席/查询失败
            onReminder = reminders.invoke,
        )
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 400L)
        service.recordCardMessageId(request.requestId, "om_card_absent")
        val decision = service.awaitDecision(request, 400)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision)
        assertEquals(0, reminders.notices.size, "null ⇒ fail-safe 不催不延")
        assertEquals(0, service.pendingCount())
    }

    /** 🔴 LB-20：readCountProvider 抛异常 ⇒ runCatching 折叠 null = 能力缺席语义（fail-safe 不外抛）。 */
    @Test
    fun `LB-20 已读查询抛异常折叠为缺席静默`() = runBlocking {
        val reminders = RecordingReminder()
        val service = ConfirmService(
            readCountProvider = { throw IllegalStateException("read_users 炸了") },
            onReminder = reminders.invoke,
        )
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 400L)
        service.recordCardMessageId(request.requestId, "om_card_err")
        val decision = service.awaitDecision(request, 400)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision, "查询异常不得炸裁决链路")
        assertEquals(0, reminders.notices.size, "异常折叠 null ⇒ fail-safe 静默")
    }

    /** 🔴 LB-20：催办后裁决立即返回（已答竞态）——催办段不吞裁决，二次回调不误触发超时语义。 */
    @Test
    fun `LB-20 催办后裁决立即返回不误超时`() = runBlocking {
        val reminders = RecordingReminder()
        val service = ConfirmService(
            readCountProvider = { 0 },
            onReminder = reminders.invoke,
        )
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 400L)
        service.recordCardMessageId(request.requestId, "om_card_remind_decide")
        val waiter = async { service.awaitDecision(request, 400) }
        // 轮询等催办落地（顺延段刚开），随后裁决——顺延段内裁决必须立即返回 APPROVED。
        val deadline = System.nanoTime() + 2_000_000_000
        while (System.nanoTime() < deadline && reminders.notices.isEmpty()) delay(5)
        assertEquals(1, reminders.notices.size, "催办先落地（前置）")
        val result = service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_a", true)
        assertTrue(result is ConfirmService.DecideResult.Ok, "顺延窗口内裁决照常受理：$result")
        assertEquals(ConfirmService.Decision.APPROVED, waiter.await(), "🔴 催办/顺延不得吞裁决——立即返回 APPROVED")
        assertEquals(0, service.pendingCount())
    }

    /** 🔴 LB-20：催办闸单向——同一请求的等待全程至多催 1 次（remindedRef CAS 语义直测）。 */
    @Test
    fun `LB-20 催办闸单向每请求至多一次`() = runBlocking {
        var readCountCalls = 0
        val reminders = RecordingReminder()
        val service = ConfirmService(
            readCountProvider = { messageId ->
                readCountCalls++
                0
            },
            onReminder = reminders.invoke,
        )
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 400L)
        service.recordCardMessageId(request.requestId, "om_card_once")
        val decision = service.awaitDecision(request, 400)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision)
        assertEquals(1, reminders.notices.size, "整段等待（两段）全程催办恰 1 次——CAS 闸单向")
        assertEquals(1, readCountCalls, "已读查询也只发生 1 次（催办闸短路后续查询）")
    }

    /** 🔴 LB-20：未回填卡（cardMessageId null）⇒ 跳过已读查询与催办（行为同旧版静默到期）。 */
    @Test
    fun `LB-20 未发卡跳过催办静默到期`() = runBlocking {
        var readCountCalls = 0
        val reminders = RecordingReminder()
        val service = ConfirmService(
            readCountProvider = {
                readCountCalls++
                0
            },
            onReminder = reminders.invoke,
        )
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 400L)
        // 不回填 cardMessageId
        val decision = service.awaitDecision(request, 400)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision)
        assertEquals(0, reminders.notices.size, "无卡锚点 ⇒ 无从判读 ⇒ 不催")
        assertEquals(0, readCountCalls, "无卡锚点 ⇒ 已读查询都不发起")
    }

    /** 🔴 LB-20：催办回调抛异常 ⇒ 不阻断顺延与超时语义（增强面 runCatching 双保险）。 */
    @Test
    fun `LB-20 催办回调抛异常不阻断超时返回`() = runBlocking {
        val service = ConfirmService(
            readCountProvider = { 0 },
            onReminder = { throw IllegalStateException("催办通道炸了") },
        )
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 400L)
        service.recordCardMessageId(request.requestId, "om_card_throw")
        val decision = service.awaitDecision(request, 400)
        assertEquals(ConfirmService.Decision.TIMEOUT, decision, "🔴 催办异常必须被吞（顺延已记账，超时语义不受影响）")
        assertEquals(0, service.pendingCount())
    }
}
