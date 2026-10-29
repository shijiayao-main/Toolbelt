// EventIntake 第 3 步（sender 准入）在「恒放行」语义下的行为测试。
// 原 onAccessRejected seam 已随准入白名单退役整块删除——拒绝路径不存在，seam 无被测对象。
package com.jiaoay.lark.bridge.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class IntakeOnRejectedTest {

    @Test
    fun `准入恒放行——任意发送者的消息都进管线`() = runBlocking {
        val harness = Harness(
            Dispatchers.Default,
            initialConfig = testConfig(trustedUsers = listOf("ou_someone")),
        )
        harness.use { it.process(msg(senderId = "ou_stranger", text = "让我进去")) }

        assertEquals(0L, harness.counters.accessRejected, "准入不再产生拒绝")
        assertEquals(1L, harness.counters.intakeReceived, "消息必须被 intake 接收")
    }

    @Test
    fun `准入拒绝计数恒零（多轮消息也是零）`() = runBlocking {
        val harness = Harness(Dispatchers.Default, initialConfig = testConfig())
        harness.use {
            repeat(5) { i -> it.process(msg(messageId = "om_$i", senderId = "ou_anyone_$i")) }
        }
        assertEquals(0L, harness.counters.accessRejected)
    }
}
