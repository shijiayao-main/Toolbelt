// S1 · 「不静默丢失」的行为与穷尽性守护。
// 🔴 三个用例组：
//  - A：unsupportedKindNotice/kindLabel 对 InboundKind 12 值的穷尽性（12 个 kind 逐一断言，
//    锁死「TEXT/FILE/CARD_ACTION/UNKNOWN ⇒ null、其余 8 种 ⇒ 非空文案」的分界）；
//  - B：端到端行为——发图 @机器人（IMAGE kind 未命中）必须收到明确降级文案
//    （改造前：零响应零提示；审计 summary 带 kind 文案可与 unmatched_silent 区分）；
//  - C：既有静默语义回归锚——TEXT kind 未命中 + 空模板 ⇒ 仍完全静默且 summary 逐字
//    `unmatched_silent`（「路由没配」≠「类型不支持」，两条路径必须可分）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.gateway.InboundKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

class UnsupportedKindNoticeTest {

    // ---------------------------------------------------------------- A：穷尽性与分界

    @Test
    fun `A unsupported kind notice is null exactly for handled and unknown kinds`() {
        val expectNull = setOf(InboundKind.TEXT, InboundKind.FILE, InboundKind.CARD_ACTION, InboundKind.UNKNOWN)
        // 🔴 穷尽 12 值（新增第 13 值时本断言天然要求补行——InboundKind.entries 会让遗漏直接红）
        assertEquals(12, InboundKind.entries.size, "InboundKind 值数变化 ⇒ 同步检查 HANDLED_KINDS 分界与文案")
        InboundKind.entries.forEach { kind ->
            val notice = unsupportedKindNotice(kind)
            if (kind in expectNull) {
                assertEquals(null, notice, "$kind 已有 handler 或属 UNKNOWN ⇒ 必须维持原语义（null）")
            } else {
                assertTrue(notice != null && notice.isNotBlank(), "$kind 属可识别未支持类型 ⇒ 必须有降级文案")
            }
        }
    }

    @Test
    fun `A notice text names the kind and points to a usable path`() {
        val notice = unsupportedKindNotice(InboundKind.IMAGE)!!
        // 文案三要素：说明收到了什么 + 说明不支持 + 给出路（引导转文字/文件）
        assertTrue("图片" in notice, "文案必须点明消息类型（人读标签来自 kindLabel）")
        assertTrue("暂不支持" in notice, "文案必须明确说明不支持，而非含糊拒绝")
        assertTrue("文字" in notice && "文件" in notice, "文案必须给出可用替代路径")
        // kindLabel 穷尽性旁证：9 种未支持 kind 的文案互不相同（标签精确，不串台）
        val labels = InboundKind.entries
            .filter { it !in setOf(InboundKind.TEXT, InboundKind.FILE, InboundKind.CARD_ACTION, InboundKind.UNKNOWN) }
            .map { kindLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "9 种未支持 kind 的标签必须互不相同")
    }

    // ---------------------------------------------------------------- B：端到端行为（改造的核心价值）

    @Test
    fun `B image mention gets explicit degrade notice`() {
        val config = testConfig(routes = listOf(SystemRoute()))
        Harness(dispatcher = Dispatchers.Default, initialConfig = config, kinds = HandlerKinds.EMPTY).use { h ->
            h.process(msg(messageId = "om_img1", kind = InboundKind.IMAGE, text = ""))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            // 🔴 改造前此处是零回复（群里发图 @机器人完全无响应）；现在必须有明确降级文案
            assertEquals(1, h.allReplyBodies().size, "发图未命中 ⇒ 必须有一条降级回复（不再是零响应）")
            val body = h.allReplyBodies().first()
            assertTrue("图片" in body, "降级文案必须点明类型：$body")
            // 审计留痕：kind 降级路径的 summary = 实发文案（与 unmatched_silent 可区分，排障可分型）
            val outcome = h.outcomes[0].outcome
            assertEquals(TaskStatus.SKIPPED, outcome.status)
            assertEquals(body, outcome.summary, "kind 降级路径的 summary = 实发文案（与 unmatched_silent 可分）")
        }
    }

    @Test
    fun `B sticker kind gets its own kind label in degrade notice`() {
        // P3：unhandledReply 用户模板已删（TEXT 兜底归调度 agent）⇒ 未支持 kind 恒走固定降级文案，
        // 各 kind 文案由 kindLabel 区分（原「用户模板优先」分支已随字段退役）。
        val config = testConfig(routes = listOf(SystemRoute()))
        Harness(dispatcher = Dispatchers.Default, initialConfig = config, kinds = HandlerKinds.EMPTY).use { h ->
            h.process(msg(messageId = "om_img2", kind = InboundKind.STICKER, text = ""))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            val body = h.allReplyBodies().single()
            assertTrue("表情包" in body, "降级文案必须点明 STICKER 的 kind 标签：$body")
        }
    }

    // ---------------------------------------------------------------- C：既有静默语义回归锚

    @Test
    fun `C text unmatched stays fully silent`() {
        val config = testConfig(routes = listOf(SystemRoute()))
        Harness(dispatcher = Dispatchers.Default, initialConfig = config, kinds = HandlerKinds.EMPTY).use { h ->
            h.process(msg(messageId = "om_txt1", text = "不在任何路由里的普通文字"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertTrue(h.awaitUntil { h.replies.sumOf { it.calls.get() } == 0 })
            assertEquals(emptyList(), h.allReplyBodies(), "TEXT 未命中恒静默（P3：模板已删；「路由没配」≠「类型不支持」）")
            assertEquals(Admission.UNHANDLED_SILENT_SUMMARY, h.outcomes[0].summary)
        }
    }

    @Test
    fun `C unknown kind unmatched stays silent`() {
        val config = testConfig(routes = listOf(SystemRoute()))
        Harness(dispatcher = Dispatchers.Default, initialConfig = config, kinds = HandlerKinds.EMPTY).use { h ->
            h.process(msg(messageId = "om_unk1", kind = InboundKind.UNKNOWN, text = ""))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertTrue(h.awaitUntil { h.replies.sumOf { it.calls.get() } == 0 })
            assertEquals(emptyList(), h.allReplyBodies(), "UNKNOWN ⇒ 不发猜测性文案，维持静默（审计已留痕）")
        }
    }
}
