// user.ask_user 飞书形态的挂起配对面直测：配对/防替答/超时软反馈/取消注销四语义。
// 提问卡渲染与发卡链由 KoogUserQuestionGate 侧（feature-larkbridge）承担，本文件只钉 bus 本体。
package com.jiaoay.lark.bridge.confirm

import com.jiaoay.lark.bridge.BridgeLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class AskUserBusTest {

    private fun bus() = AskUserBus(BridgeLog.Silent)

    // ── 配对主链：register → awaitAnswer 挂起 → decide 完成 ──

    @Test
    fun `引用回复完成挂起且答案值透传`() = runBlocking {
        val bus = bus()
        val q = bus.register("om_card_1", "oc_1", "ou_a", "选哪个方案？")
        val awaiting = async { bus.awaitAnswer("om_card_1", 10_000) }
        yield()
        assertTrue(bus.decide("om_card_1", "ou_a", mapOf("answer" to "方案 B"), skipped = false), "发起者回答须配对成功")
        val answer = awaiting.await()
        assertFalse(answer.skipped, "已答态不是 skipped")
        assertEquals("方案 B", answer.values["answer"])
    }

    @Test
    fun `未配对的 cardMessageId decide 返回 false（不炸）`() {
        val bus = bus()
        assertFalse(bus.decide("om_ghost", "ou_a", emptyMap(), skipped = false), "未知卡须返回 false 交后续路由")
    }

    // ── 防替答：非发起者的回答不完成挂起 ──

    @Test
    fun `非发起者回答被拒且挂起继续等待`() = runBlocking {
        val bus = bus()
        bus.register("om_card_2", "oc_1", "ou_a", "问题")
        val awaiting = async { bus.awaitAnswer("om_card_2", 300) }
        yield()
        assertFalse(bus.decide("om_card_2", "ou_b", mapOf("answer" to "捣乱"), skipped = false), "他人回答须被拒")
        // 超时收口：未被他人回答污染，最终按 skipped 软反馈完成
        val answer = withTimeout(5_000) { awaiting.await() }
        assertTrue(answer.skipped, "无人作答按超时 skipped 软反馈")
    }

    // ── 超时软反馈：不崩任务（HITL 降级继续哲学） ──

    @Test
    fun `超时回 skipped 且登记项注销（再答不复活）`() = runBlocking {
        val bus = bus()
        bus.register("om_card_3", "oc_1", "ou_a", "问题")
        val answer = bus.awaitAnswer("om_card_3", 50)
        assertTrue(answer.skipped, "超时须 skipped 软反馈")
        assertFalse(bus.decide("om_card_3", "ou_a", mapOf("answer" to "迟到的回答"), skipped = false), "超时注销后回答不复活")
    }

    // ── 取消联动：awaitAnswer 被取消时注销登记项 ──

    @Test
    fun `挂起被取消时登记项随之注销（无泄漏）`() = runBlocking {
        val bus = bus()
        bus.register("om_card_4", "oc_1", "ou_a", "问题")
        val job = launch { bus.awaitAnswer("om_card_4", 60_000) }
        yield()
        job.cancel()
        job.join()
        assertEquals(null, bus.find("om_card_4"), "取消须注销 pending 项")
    }

    // ── 文本/按钮双通道同配对面 ──

    @Test
    fun `按钮回传与引用回复走同一 decide 面`() = runBlocking {
        val bus = bus()
        bus.register("om_card_5", "oc_1", "ou_a", "单选")
        val awaiting = async { bus.awaitAnswer("om_card_5", 10_000) }
        yield()
        assertTrue(bus.decide("om_card_5", "ou_a", mapOf("answer" to "选项一"), skipped = false))
        assertEquals("选项一", awaiting.await().values["answer"])
    }
}
