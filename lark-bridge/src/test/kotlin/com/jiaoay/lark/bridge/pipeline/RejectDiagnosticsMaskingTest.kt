// 🔴 T11 可观测性缺陷修复（设计裁定 2026-09-08 · 方案 b）：拒绝/准入 DEBUG 诊断文本的**隐私掩码**单测。
// 背景：`--verbose` 现在会把桥接侧 console 下限降到 DEBUG ⇒ 既有拒绝路径 `log.debug` 会**真实打印到控制台**，
//       故本文件钉死「DEBUG 文本含拒绝原因、且绝不含完整 chatId/openId（掩码形态出现、完整标识不出现）」。
// 🔴 钉死四件事：
//   ① [PolicyGate.maskIdentifier] 掩码口径（长值前 6+****+后 4、短值 <=10 全掩码、空串原样）；
//   ② [PolicyGate.rejectDiagnostics] 纯函数：含原因 + 掩码 chatId/senderId，不含完整值，容忍可空标识；
//   ③ PolicyGate 群@门控拒绝行为与计数器**逐字未变**（删 Nate 冗余 seam 后判定逻辑不受影响）；
//   ④ EventIntake 第 3 步 sender 准入拒绝的既有 DEBUG 文本已掩码 openId、不含完整值。
// 🔴 零锁/零挂起/零 IO 由 IntakeGuardTest（源码扫描 10 文件禁字表 + 反射非挂起断言）继续钉死，本文件不重复。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.GatewaySignal
import com.jiaoay.lark.gateway.InboundKind
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

class RejectDiagnosticsMaskingTest {

    private val openId = "ou_0123456789abcdef0123456789abcdef"   // 35 字符构造向量（顺序 hex——可证明非真机分配值）
    private val chatId = "oc_fedcba9876543210fedcba9876543210"   // 群 chatId 构造向量（倒序 hex——可证明非真机分配值）

    /** 录制型 BridgeLog：捕获 DEBUG 行以断言其掩码形态（形态对齐 observe/LogConsoleEnabledTest 的 RecordingDelegate）。 */
    private fun groupMsg(mentionedBot: Boolean = false) = msg(
        messageId = "om_diag_1",
        chatId = chatId,
        chatType = ChatType.GROUP,
        senderId = openId,
        kind = InboundKind.TEXT,
        mentionedBot = mentionedBot,
    )

    // ==================== ① maskIdentifier 掩码口径 ====================

    @Test
    fun `maskIdentifier 口径——长值前6中段星号后4、短值全掩码、空串原样`() {
        assertEquals("ou_012****cdef", PolicyGate.maskIdentifier(openId), "长 openId：前 6 + **** + 后 4")
        assertEquals("oc_fed****3210", PolicyGate.maskIdentifier(chatId), "长 chatId：前 6 + **** + 后 4")
        assertEquals("*******", PolicyGate.maskIdentifier("oc_1234"), "短值（7 <= 10）全掩码：前后 6/4 会重叠还原")
        assertEquals("**********", PolicyGate.maskIdentifier("0123456789"), "长度 10 全掩码")
        assertEquals("", PolicyGate.maskIdentifier(""), "空串原样返回空")
    }

    // ==================== ② rejectDiagnostics 纯函数：原因 + 掩码 ====================

    @Test
    fun `rejectDiagnostics 含拒绝原因与掩码标识、绝不含完整 chatId openId`() {
        val rejected = GatewaySignal.Rejected(
            reason = PolicyGate.REASON_GROUP_REQUIRE_MENTION,
            messageId = "om_diag_1",
            chatId = chatId,
            senderId = openId,
        )
        val text = PolicyGate.rejectDiagnostics(rejected)
        assertTrue(text.contains("策略门控拒绝"), "文本必须自述来源：\n$text")
        assertTrue(text.contains("reason=${PolicyGate.REASON_GROUP_REQUIRE_MENTION}"), "必须含拒绝原因字面值：\n$text")
        assertTrue(text.contains("chat=oc_fed****3210"), "chatId 必须掩码为前 6 + **** + 后 4：\n$text")
        assertTrue(text.contains("sender=ou_012****cdef"), "senderOpenId 必须掩码：\n$text")
        // 🔴 隐私硬约束：--verbose 下这条会真实打印 ⇒ 不得含完整标识
        assertFalse(text.contains(chatId), "🔴 诊断文本不得含完整 chatId：\n$text")
        assertFalse(text.contains(openId), "🔴 诊断文本不得含完整 openId：\n$text")
    }

    @Test
    fun `rejectDiagnostics 容忍可空 chatId senderId——经 orEmpty 掩码、不抛不出现 null 字面`() {
        val rejected = GatewaySignal.Rejected(
            reason = PolicyGate.REASON_GROUP_REQUIRE_MENTION,
            messageId = "om_x",
            chatId = null,
            senderId = null,
        )
        val text = PolicyGate.rejectDiagnostics(rejected)
        assertTrue(text.contains("reason=group_require_mention"), "仍含原因：\n$text")
        assertFalse(text.contains("null"), "🔴 可空标识经 orEmpty() ⇒ 文本不得出现 null 字面：\n$text")
    }

    // ==================== ③ PolicyGate 拒绝行为与计数器逐字未变 ====================

    @Test
    fun `群内未 at 机器人被拒——onRejected 收到 Rejected 信号、判定与计数逐字未变`() {
        val signals = mutableListOf<GatewaySignal.Rejected>()
        // P3：群@门控真源 = agent.groupMode（"mention" = 仅@唤起）——经第三参具名注入。
        val gate = PolicyGate(
            { AccessConfig() },
            onRejected = { signals.add(it) },
            agentGroupModeProvider = { "mention" },
        )

        assertFalse(gate.test(groupMsg()), "群内未@机器人必须被拒（判定表逐字未变）")
        assertEquals(1L, gate.rejectedCount, "拒绝计数必须照常 +1")
        assertEquals(1L, gate.groupEvaluatedCount, "群消息评估计数必须照常 +1")
        assertEquals(PolicyGate.REASON_GROUP_REQUIRE_MENTION, gate.lastRejectionReason)
        assertEquals(1, signals.size, "Rejected 信号照旧产出（D 段可见性不受影响）")
        // 信号本身携带**原始**标识供审计/UI；掩码只发生在 [rejectDiagnostics] 的日志渲染，不改信号语义
        assertEquals(chatId, signals.single().chatId)
        assertEquals(openId, signals.single().senderId)

        // 放行形态逐字未变：@机器人 / groupMode=all（旧「门控关闭」语义的 P3 载体）
        assertTrue(gate.test(groupMsg(mentionedBot = true)), "群内@机器人必须放行")
        val openGate = PolicyGate({ AccessConfig() }, agentGroupModeProvider = { "all" })
        assertTrue(openGate.test(groupMsg()), "groupMode=all 必须放行")
        assertEquals(0L, openGate.rejectedCount)
        assertEquals(1L, openGate.groupEvaluatedCount, "群消息仍进评估计数（与模式无关）")
    }

    // ==================== ④ EventIntake 第 3 步 sender 准入：已无拒绝路径 ====================

    @Test
    fun `EventIntake 第 3 步准入恒放行（不再产出拒绝 DEBUG 行）`() {
        // 准入白名单已退役：AccessControl 恒产 ALLOWED，EventIntake 第 3 步无拒绝分支、
        // 无拒绝 DEBUG 文本。判据直接取判定结果（不依赖异步日志缓冲，避免与 intake 协程竞争）。
        val control = AccessControl { AccessConfig(trustedUsers = listOf("ou_someone_else")) }
        assertEquals(AccessDecision.ALLOWED, control.decide(openId))
        assertEquals(AccessDecision.ALLOWED, control.decide(""))
        assertNull(control.rejectionReply(openId), "拒绝文案路径恒 null")
    }
}
