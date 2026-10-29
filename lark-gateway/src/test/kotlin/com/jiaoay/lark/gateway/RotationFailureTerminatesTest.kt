// :lark-gateway 测试（task 26 · P0-1 回归钉死）。零 SDK / 零真机 / 零网络：FakeCardStreamPort seam + 虚拟时间。
// 🔴 靶子：主循环分段轮换（budgetHit / deadlineHit 分支）曾**丢弃 rotateSegment 的返回值**——
//    当轮换时新建卡片实体失败（rotateSegment 返回 false，且它在重置 slot/segmentStartedAt/cardId **之前**返回），
//    open 不置 false、快照不变 ⇒ 25ms 后 budgetHit/deadlineHit 仍成立 ⇒ 对已定稿的死卡反复 finishSettings + 反复建卡
//    ⇒ 自维持活锁（约 80 次/秒轰飞书网关侧 API，绕过 bridge 侧 RateGovernor），且失败窗口内累积内容永不再显示。
//    本文件构造「累计字节超预算触发轮换 + 新段建卡失败」，断言主循环**终止**、open==false、调用次数**有界且不再增长**。
// 🔴 与 SegmentRotationTest（fake 建卡恒成功 ⇒ 轮换必成 ⇒ 碰不到本缺陷）互补：这里让第 2 次建卡失败。
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** P0-1：分段轮换时新建卡片实体失败必须终止主循环，绝不 25ms 无限重试。 */
class RotationFailureTerminatesTest {

    // ①②③④ 累计字节超预算触发轮换 + 新段建卡失败 ⇒ 主循环终止、open=false、建卡/定稿次数有界且不再增长
    @Test
    fun `byte budget rotation with failing create terminates loop instead of retrying forever`() = runTest {
        val port = FakeCardStreamPort()
        // 第 2 次建卡（首次轮换的新段）返回失败码 ⇒ createCardEntity 返回 null ⇒ rotateSegment 返回 false
        port.createResponder = { idx -> if (idx == 2) CardCallOutcome(success = false, code = 300302, msg = "invalid card json") else null }
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()                       // 建第 1 段成功（createCount==1）
        assertEquals(1, port.createCount)

        sink.tryReplace("x".repeat(40_000))               // 40_000 字节 > cardByteBudget(24576) ⇒ budgetHit
        testScheduler.advanceTimeBy(50); testScheduler.runCurrent()

        // ① 主循环终止（job 完成，不是永远活跃）
        assertTrue(sink.job.isCompleted, "建卡失败后主循环必须终止（不得 25ms 无限重试活锁）")
        // ② open == false（与首段构建 :108-111 及 rotateSegment 自身契约一致）
        assertFalse(sink.isOpen, "建卡失败后 sink 必须关闭（open=false）")
        // ③ 建卡/定稿调用次数有界：只应尝试建卡 2 次（首段 + 1 次失败的轮换），失败即终止
        val createsBounded = port.createCount
        val settingsBounded = port.settingsCount
        assertEquals(2, createsBounded, "只应尝试建卡 2 次（首段 + 1 次失败轮换），失败即终止；实际=$createsBounded")

        // ④ 推进远超活锁周期的虚拟时间后，建卡/定稿次数仍不再增长（钉死「不自维持活锁」）
        repeat(400) { testScheduler.advanceTimeBy(25); testScheduler.runCurrent() }
        assertEquals(createsBounded, port.createCount, "推进 10s 虚拟时间后建卡次数不得再增长（无活锁）")
        assertEquals(settingsBounded, port.settingsCount, "推进后对死卡的定稿次数不得再增长（无活锁）")
        assertTrue(sink.job.isCompleted, "推进后主循环仍必须处于终止态")
    }

    // 🔴 单卡收敛（2026-09-12）：主动 TimeDeadline 轮换退役后，deadlineHit 分支不复存在——
    //    「未登记码 → REBUILD_STREAM → 建卡失败」是新的重建失败路径，改用注入未知码触发（语义同构：
    //    重建失败必须终止，不得 25ms 无限重试）。
    @Test
    fun `passive rebuild with failing create terminates loop instead of retrying forever`() = runTest {
        val port = FakeCardStreamPort()
        // 首段建卡成功；此后（被动重建的新段）建卡一律失败
        port.createResponder = { idx -> if (idx >= 2) CardCallOutcome(success = false, code = 300302, msg = "invalid card json") else null }
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        assertEquals(1, port.createCount)

        // 文本远小于字节预算（budgetHit 不成立）；注入未登记业务码 ⇒ else 分支 REBUILD_STREAM ⇒ 建卡失败
        port.patchResponder = { idx -> if (idx == 1) CardCallOutcome(success = false, code = 47001) else null }
        sink.tryReplace("small")
        // 250ms 节流窗到点触发 patch → 47001 → REBUILD_STREAM → 建卡失败 → open=false；再推进一个 poll 周期
        // 让主循环走完最后一轮 sleeper(25) 从 while 退出（job 完成）。
        testScheduler.advanceTimeBy(250 + Tuning.sinkPollIntervalMs); testScheduler.runCurrent()

        assertTrue(sink.job.isCompleted, "被动重建建卡失败后主循环必须终止（REBUILD_STREAM 失败路径，不得无限重试）")
        assertFalse(sink.isOpen, "被动重建建卡失败后 sink 必须关闭")
        val createsBounded = port.createCount
        val settingsBounded = port.settingsCount
        assertEquals(2, createsBounded, "重建只应尝试建卡 2 次（首段 + 1 次失败重建），失败即终止；实际=$createsBounded")

        repeat(400) { testScheduler.advanceTimeBy(25); testScheduler.runCurrent() }
        assertEquals(createsBounded, port.createCount, "推进后建卡次数不得再增长（REBUILD_STREAM 失败无活锁）")
        assertEquals(settingsBounded, port.settingsCount, "推进后定稿次数不得再增长")
    }
}
