// 批 ④（2026-09-27 IA 重设计屏④）：GatewayConfirmNotifier 的确认卡 markdown 骨架测试。
// 骨架四件：① 风险徽标行（riskLevel 非空才渲染）② 工具名行 ③ 参数摘要行 ④ 信任行 + 短码兜底行。
// 🔴 短码单源（ConfirmService.shortId）；回填语义（2026-09-15 已答态批）此处一并回归。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.confirm.ConfirmService
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GatewayConfirmNotifierTest {

    private fun notifier(gateway: SyntheticGateway, sink: ((String, String) -> Unit)? = null) =
        GatewayConfirmNotifier(gateway, BridgeLog.Silent, cardMessageIdSink = sink)

    /** 🔴 骨架四件齐全：风险徽标 / 工具 / 参数 / 信任行 + 短码兜底行。 */
    @Test
    fun `🔴 批④ 确认卡骨架四件齐全`() = runBlocking {
        val gateway = SyntheticGateway()
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "rm -rf build", riskLevel = "HIGH", effectiveTimeoutMillis = 5_000)
        notifier(gateway).notify(request)

        val card = gateway.recordedSends.single().card
        assertTrue(card != null, "确认卡必须是 card 出站")
        val markdown = card!!.markdown
        assertTrue(markdown.contains("**风险**：HIGH（高风险操作，请仔细核对参数）"), "风险徽标行：$markdown")
        assertTrue(markdown.contains("**工具**：`Bash`"), "工具名行：$markdown")
        assertTrue(markdown.contains("**参数**：rm -rf build"), "参数摘要行：$markdown")
        assertTrue(markdown.contains("仅发起本次操作的用户可裁决（他人回复无效）"), "信任行：$markdown")
        val shortId = ConfirmService.shortId(request.requestId)
        assertTrue(
            markdown.contains("无法点按钮？回复 **确认 $shortId** 或 **拒绝 $shortId**"),
            "短码兜底行：$markdown",
        )
    }

    /** 🔴 空风险等级（未知/未分级）不渲染风险行（默认 4 参构造形态）。 */
    @Test
    fun `🔴 批④ 空风险等级无风险行`() = runBlocking {
        val gateway = SyntheticGateway()
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "ls -la", effectiveTimeoutMillis = 5_000)
        notifier(gateway).notify(request)

        val markdown = gateway.recordedSends.single().card!!.markdown
        assertFalse(markdown.contains("**风险**"), "riskLevel 空串不得渲染风险行：$markdown")
        assertTrue(markdown.contains("**工具**：`Bash`"), "工具名行仍在：$markdown")
    }

    /** 🔴 短码单源：按钮 token 与兜底行短码同走 ConfirmService.shortId（同一 requestId 下同值）。 */
    @Test
    fun `🔴 批④ 按钮 token 与兜底行短码同源`() = runBlocking {
        val gateway = SyntheticGateway()
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", riskLevel = "MEDIUM", effectiveTimeoutMillis = 5_000)
        notifier(gateway).notify(request)

        val card = gateway.recordedSends.single().card!!
        val shortId = ConfirmService.shortId(request.requestId)
        val allowValue = card.buttons.first { it.text == "允许" }.value
        assertEquals("$shortId:allow", allowValue, "按钮 token 必须等于 shortId：${card.buttons}")
        assertTrue(
            card.markdown.contains("确认 $shortId"),
            "兜底行短码与按钮 token 同源：${card.markdown}",
        )
    }

    /** 🔴 发送成功回填 messageId（2026-09-15 已答态批语义回归，批 ④ 不破）。 */
    @Test
    fun `🔴 发送成功回填 messageId（既有语义回归）`() = runBlocking {
        val gateway = SyntheticGateway()
        val backfills = mutableListOf<Pair<String, String>>()
        val service = ConfirmService()
        val request = service.newRequest("oc_1", "ou_a", "Bash", "x", effectiveTimeoutMillis = 5_000)
        notifier(gateway) { id, mid -> backfills.add(id to mid) }.notify(request)

        assertEquals(1, backfills.size, "发送成功须回填一次")
        assertEquals(request.requestId, backfills.single().first)
    }
}
