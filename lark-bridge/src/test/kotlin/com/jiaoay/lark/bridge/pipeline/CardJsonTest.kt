// M4 收尾（task 18 项 4 · G-07）：CardJson 五个构造/校验函数的契约钉死测试。
// 🔴 头号契约：confirm() 的按钮 value 逐字为 `"$token:$acceptValue"` / `"$token:$rejectValue"`（默认 allow/deny）——
//    T8 confirm 总线据此 split(":") 裁决，格式漂移即整条运行时确认链路断裂，故此处**逐字**断言，不许改。
// 🔴 三条不变式（由签名强制）：streaming 无 buttons；interaction/confirm/terminal 的 streaming 恒 false；
//    requireNonInteractiveStreaming 是流式通道的最后防线（streaming+buttons ⇒ 抛，消解飞书 200810）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.CardButtonSpec
import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.CardTitleColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CardJsonTest {

    // ------------------------------------------------------------------ streaming()

    @Test
    fun `streaming 产出流式卡：streaming=true 且 buttons 恒空（签名无 buttons 参数，200810 结构性消解）`() {
        val spec = CardJson.streaming(title = "受理中", markdown = "正在处理…")
        assertTrue(spec.streaming, "streaming() 产出的卡必须 streaming=true")
        assertTrue(spec.buttons.isEmpty(), "🔴 流式卡内禁放任何交互组件 ⇒ buttons 恒为空")
        assertEquals("受理中", spec.title)
        assertEquals("正在处理…", spec.markdown)
        assertEquals(CardTitleColor.BLUE, spec.titleColor, "streaming 默认标题色为 BLUE")
    }

    @Test
    fun `streaming 可自定义标题色与 summary`() {
        val spec = CardJson.streaming(
            title = "t",
            markdown = "m",
            titleColor = CardTitleColor.GREY,
            summary = "s",
        )
        assertEquals(CardTitleColor.GREY, spec.titleColor)
        assertEquals("s", spec.summary)
        assertTrue(spec.buttons.isEmpty())
        assertTrue(spec.streaming)
    }

    // ------------------------------------------------------------------ interaction()

    @Test
    fun `interaction 产出非流式交互卡：streaming=false 且按钮原样保留`() {
        val buttons = listOf(
            CardButtonSpec(text = "确定", value = "ok", primary = true),
            CardButtonSpec(text = "取消", value = "cancel", primary = false),
        )
        val spec = CardJson.interaction(title = "请选择", markdown = "m", buttons = buttons)
        assertFalse(spec.streaming, "🔴 interaction 的 streaming 恒 false（签名无 streaming 参数）")
        assertEquals(buttons, spec.buttons, "按钮应原样保留")
        assertEquals(CardTitleColor.GREY, spec.titleColor, "interaction 默认标题色为 GREY")
    }

    @Test
    fun `interaction 按钮为空 ⇒ IllegalArgumentException（交互卡没按钮没有意义）`() {
        assertFailsWith<IllegalArgumentException> {
            CardJson.interaction(title = "t", markdown = "m", buttons = emptyList())
        }
    }

    // ------------------------------------------------------------------ confirm() 🔴 裁决契约字符串

    @Test
    fun `🔴 confirm 默认裁决契约：按钮 value 逐字为 token-allow 与 token-deny（acceptValue=allow rejectValue=deny）`() {
        val spec = CardJson.confirm(title = "确认执行", markdown = "rm -rf ?", token = "tok_abc123")
        assertFalse(spec.streaming, "🔴 确认卡是独立非流式卡（绝不把按钮塞进流式进度卡，规避 200810）")
        assertEquals(2, spec.buttons.size, "确认卡恰两个按钮（允许/拒绝）")
        // 🔴🔴 逐字钉死裁决契约字符串格式 `"$token:$acceptValue"`——T8 confirm 总线据此裁决，格式漂移即链路断裂。
        assertEquals("tok_abc123:allow", spec.buttons[0].value, "允许按钮 value 必须逐字为 `<token>:allow`")
        assertEquals("tok_abc123:deny", spec.buttons[1].value, "拒绝按钮 value 必须逐字为 `<token>:deny`")
        assertEquals(CardTitleColor.BLUE, spec.titleColor, "confirm 标题色为 BLUE")
    }

    @Test
    fun `🔴 confirm 按钮 primary 语义：允许=primary true，拒绝=primary false；标签可自定义`() {
        val spec = CardJson.confirm(
            title = "确认",
            markdown = "m",
            token = "t1",
            acceptLabel = "批准",
            rejectLabel = "驳回",
        )
        assertEquals("批准", spec.buttons[0].text)
        assertTrue(spec.buttons[0].primary, "允许按钮应为 primary")
        assertEquals("驳回", spec.buttons[1].text)
        assertFalse(spec.buttons[1].primary, "拒绝按钮不应为 primary")
    }

    @Test
    fun `🔴 confirm 自定义 acceptValue-rejectValue 仍守 token-colon-value 格式`() {
        val spec = CardJson.confirm(
            title = "确认",
            markdown = "m",
            token = "tok_xyz",
            acceptValue = "yes",
            rejectValue = "no",
        )
        assertEquals("tok_xyz:yes", spec.buttons[0].value)
        assertEquals("tok_xyz:no", spec.buttons[1].value)
    }

    // ------------------------------------------------------------------ terminal()

    @Test
    fun `terminal 非流式无按钮，标题色按状态映射 SUCCESS-GREEN FAILURE-RED SKIPPED-GREY`() {
        val ok = CardJson.terminal(title = "完成", markdown = "done", status = TaskStatus.SUCCESS)
        assertFalse(ok.streaming)
        assertTrue(ok.buttons.isEmpty(), "终态卡无按钮")
        assertEquals(CardTitleColor.GREEN, ok.titleColor)

        val fail = CardJson.terminal(title = "失败", markdown = "err", status = TaskStatus.FAILURE)
        assertEquals(CardTitleColor.RED, fail.titleColor)

        val skip = CardJson.terminal(title = "跳过", markdown = "skip", status = TaskStatus.SKIPPED)
        assertEquals(CardTitleColor.GREY, skip.titleColor)
    }

    // ------------------------------------------------------------------ requireNonInteractiveStreaming()

    @Test
    fun `requireNonInteractiveStreaming 放行流式无按钮卡并原样返回`() {
        val spec = CardJson.streaming(title = "t", markdown = "m")
        assertSame(spec, CardJson.requireNonInteractiveStreaming(spec), "合规卡应原样返回（同一实例）")
    }

    @Test
    fun `🔴 requireNonInteractiveStreaming 拦截 streaming+buttons 组合（200810）⇒ IllegalStateException`() {
        val violating = CardSpec(
            title = "t",
            titleColor = CardTitleColor.BLUE,
            markdown = "m",
            buttons = listOf(CardButtonSpec(text = "x", value = "v", primary = true)),
            streaming = true,
            summary = null,
        )
        val ex = assertFailsWith<IllegalStateException> { CardJson.requireNonInteractiveStreaming(violating) }
        assertTrue(ex.message!!.contains("200810"), "异常文案应点名飞书 200810，实际=${ex.message}")
    }
}
