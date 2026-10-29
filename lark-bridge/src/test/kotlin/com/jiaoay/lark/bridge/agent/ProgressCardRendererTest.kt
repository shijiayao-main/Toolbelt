// M4-T8 agent/：🔴 平移旧 `progress/ProgressCardRendererTest` 断言 → 新 `ProgressCardRenderer`，
// 并钉住两条方案硬约束：
//   D1：产出物是 `:lark-gateway` 的 `CardSpec` **纯数据**，流式态经 `CardJson.streaming`（彻底弃用「消息编辑」）；
//   200810：流式卡（receipt/running）恒无交互组件——`requireNonInteractiveStreaming` 不抛，`buttons` 为空。
// BC 锁定文案（RECEIPT_TITLE / OVERFLOW_BODY / formatElapsed / RunStatus.label）逐字断言。
// 批 ④（2026-09-27 屏④）：状态带四态断言 + footer 只在 running + OVERFLOW 无状态带 + elapsed=0 不拼「用时」。
package com.jiaoay.lark.bridge.agent

import com.jiaoay.lark.bridge.pipeline.CardJson
import com.jiaoay.lark.gateway.CardTitleColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressCardRendererTest {

    private fun progress(vararg events: ProgressEvent, status: RunStatus = RunStatus.RUNNING) =
        TaskProgress(startedAtEpochMillis = 0L, events = events.toList(), status = status)

    // ── D1：产出 CardSpec 纯数据 + 弃用消息编辑 ───────────────────────────────

    @Test
    fun `🔴 D1：receipt 产出流式 CardSpec（非消息编辑），标题蓝、无按钮`() {
        val card = ProgressCardRenderer.receipt("帮我修个 bug")
        assertTrue(card.streaming, "🔴 D1：受理卡走 CardKit 流式（streaming_mode），不再逐次 edit 消息")
        assertEquals(CardTitleColor.BLUE, card.titleColor)
        assertEquals(ProgressCardRenderer.RECEIPT_TITLE, card.title)
        assertTrue(card.markdown.contains("帮我修个 bug"), "正文含任务摘要")
        assertTrue(card.buttons.isEmpty(), "🔴 200810：流式卡恒无交互组件")
    }

    @Test
    fun `🔴 D1：running 产出流式 CardSpec，标题含已运行时长`() {
        val card = ProgressCardRenderer.running(
            progress = progress(ProgressEvent.ToolUse("Bash", "ls")),
            elapsedMillis = 65_000L,
        )
        assertTrue(card.streaming)
        assertEquals(CardTitleColor.BLUE, card.titleColor)
        assertTrue(card.title.contains("1m5s"), "标题含 formatElapsed(65s)=1m5s")
        assertTrue(card.buttons.isEmpty())
    }

    // ── 200810：流式卡内禁放任何交互组件 ────────────────────────────────────

    @Test
    fun `🔴 200810：receipt_running 均通过 requireNonInteractiveStreaming（签名层构造不出违规组合）`() {
        val receipt = ProgressCardRenderer.receipt("task")
        val running = ProgressCardRenderer.running(progress(ProgressEvent.AssistantText("x")), 1_000L)
        // 不抛即通过：streaming=true 且 buttons 为空
        CardJson.requireNonInteractiveStreaming(receipt)
        CardJson.requireNonInteractiveStreaming(running)
        assertTrue(receipt.buttons.isEmpty() && running.buttons.isEmpty())
    }

    @Test
    fun `🔴 200810：terminal 是非流式卡（streaming=false），可作流式收尾`() {
        val done = ProgressCardRenderer.terminal(RunStatus.DONE, 3_000L, progress(ProgressEvent.AssistantText("ok")))
        assertFalse(done.streaming, "终态卡非流式")
        assertEquals(CardTitleColor.GREEN, done.titleColor, "DONE ⇒ 绿")
        assertTrue(done.buttons.isEmpty())

        val failed = ProgressCardRenderer.terminal(RunStatus.FAILED, 3_000L, progress())
        assertEquals(CardTitleColor.RED, failed.titleColor, "FAILED ⇒ 红")
        val timeout = ProgressCardRenderer.terminal(RunStatus.TIMEOUT, 3_000L, progress())
        assertEquals(CardTitleColor.RED, timeout.titleColor, "TIMEOUT ⇒ 红")
    }

    // ── BC 锁定文案 ─────────────────────────────────────────────────────────

    @Test
    fun `🔒 BC 锁定文案：RECEIPT_TITLE 与 OVERFLOW_BODY 逐字`() {
        assertEquals("🤖 Agent 已受理，正在思考", ProgressCardRenderer.RECEIPT_TITLE)
        assertEquals("**过程**：（过长已省略）", ProgressCardRenderer.OVERFLOW_BODY)
    }

    @Test
    fun `🔒 formatElapsed：不足 1 分钟只显秒，超过显 m+s`() {
        assertEquals("0s", ProgressCardRenderer.formatElapsed(0L))
        assertEquals("7s", ProgressCardRenderer.formatElapsed(7_000L))
        assertEquals("59s", ProgressCardRenderer.formatElapsed(59_999L))
        assertEquals("1m0s", ProgressCardRenderer.formatElapsed(60_000L))
        assertEquals("2m30s", ProgressCardRenderer.formatElapsed(150_000L))
    }

    @Test
    fun `🔒 RunStatus_label：四态文案逐字`() {
        with(ProgressCardRenderer) {
            assertEquals("思考/执行中", RunStatus.RUNNING.label())
            assertEquals("已完成", RunStatus.DONE.label())
            assertEquals("失败", RunStatus.FAILED.label())
            assertEquals("超时", RunStatus.TIMEOUT.label())
        }
    }

    // ── 三层截断算法（逐字保留旧实现）────────────────────────────────────────

    @Test
    fun `三层截断：整卡超 cardMaxChars 时逐条丢最旧，全丢后回落 OVERFLOW_BODY`() {
        val many = (1..12).map { ProgressEvent.AssistantText("内容$it".repeat(20)) }
        val limits = ProgressCardRenderer.Limits(maxEntries = 12, entryMaxChars = 200, cardMaxChars = 40)
        val body = ProgressCardRenderer.body(progress(*many.toTypedArray()), limits)
        assertEquals(ProgressCardRenderer.OVERFLOW_BODY, body, "🔴 cardMaxChars 极小时全窗口被挤掉 ⇒ 兜底文案")
    }

    @Test
    fun `三层截断：单条 entryMaxChars 截断并折行（singleLine + …）`() {
        val long = ProgressEvent.AssistantText("第一行\n" + "x".repeat(500))
        val limits = ProgressCardRenderer.Limits(maxEntries = 12, entryMaxChars = 20, cardMaxChars = 5000)
        val body = ProgressCardRenderer.body(progress(long), limits)
        assertFalse(body.contains("\n\n> 📝 第一行\n"), "换行被折叠成空格")
        assertTrue(body.contains("…"), "🔴 超 entryMaxChars 以省略号收尾")
        assertTrue(body.contains("最近 1 条"), "正文标注窗口条数")
    }

    @Test
    fun `Limits 默认值 = 2026-09-11 防刷屏裁定（6 条 80 单条 2000 整卡）`() {
        val d = ProgressCardRenderer.Limits()
        assertEquals(6, d.maxEntries)
        assertEquals(80, d.entryMaxChars)
        assertEquals(2000, d.cardMaxChars)
    }

    // ── 批 ④（2026-09-27 屏④）：正文首行状态带 + footer ─────────────────────

    /** 🔴 状态带四态文案逐字（屏④直译）。 */
    @Test
    fun `🔴 批④ 状态带四态文案逐字`() {
        assertEquals("⏳ 进行中", ProgressCardRenderer.STATE_LINE_RUNNING)
        assertEquals("✅ 已完成", ProgressCardRenderer.STATE_LINE_DONE)
        assertEquals("❌ 失败", ProgressCardRenderer.STATE_LINE_FAILED)
        assertEquals("⏱ 已超时", ProgressCardRenderer.STATE_LINE_TIMEOUT)
    }

    /** 🔴 running 卡正文首行 = ⏳ 状态带（原「**状态**：」行保留在其后）。 */
    @Test
    fun `🔴 批④ running 正文首行是进行中状态带且状态行保留`() {
        val card = ProgressCardRenderer.running(
            progress = progress(ProgressEvent.ToolUse("Bash", "ls")),
            elapsedMillis = 65_000L,
        )
        assertTrue(card.markdown.startsWith("⏳ 进行中"), "正文首行必须是状态带：${card.markdown}")
        assertTrue(card.markdown.contains("**状态**：思考/执行中"), "🔴 状态行保留（叠加不是替换）：${card.markdown}")
    }

    /** 🔴 终态 DONE：首行「✅ 已完成 · 用时 X」。 */
    @Test
    fun `🔴 批④ 终态 DONE 状态带拼用时`() {
        val card = ProgressCardRenderer.terminal(RunStatus.DONE, 192_000L, progress(ProgressEvent.AssistantText("ok")))
        assertTrue(card.markdown.startsWith("✅ 已完成 · 用时 3m12s"), "屏④「已完成 · 用时 3m12s」直译：${card.markdown}")
    }

    /** 🔴 elapsedMillis == 0（现调用点形态）不拼「用时」段——拼「用时 0s」是自欺。 */
    @Test
    fun `🔴 批④ elapsed 为零时不拼用时段`() {
        val done = ProgressCardRenderer.terminal(RunStatus.DONE, 0L, progress())
        assertEquals("✅ 已完成", done.markdown.substringBefore("\n"), "elapsed=0 ⇒ 只出状态带短语：${done.markdown}")
        val timeout = ProgressCardRenderer.terminal(RunStatus.TIMEOUT, 0L, progress())
        assertEquals("⏱ 已超时", timeout.markdown.substringBefore("\n"), "elapsed=0 ⇒ 只出状态带短语：${timeout.markdown}")
    }

    /** 🔴 终态 FAILED：errorText 非空拼进状态带、为空只出短语。 */
    @Test
    fun `🔴 批④ 终态 FAILED 状态带拼错误详情或裸短语`() {
        val withError = ProgressCardRenderer.terminal(
            RunStatus.FAILED,
            3_000L,
            progress(),
            errorText = "agent_exit_1（exit=1）",
        )
        assertTrue(
            withError.markdown.startsWith("❌ 失败 · agent_exit_1（exit=1）"),
            "errorText 非空须拼进状态带：${withError.markdown}",
        )
        val bare = ProgressCardRenderer.terminal(RunStatus.FAILED, 3_000L, progress())
        assertEquals("❌ 失败", bare.markdown.substringBefore("\n"), "errorText 为空只出短语：${bare.markdown}")
    }

    /** 🔴 终态 TIMEOUT：首行「⏱ 已超时 · X」。 */
    @Test
    fun `🔴 批④ 终态 TIMEOUT 状态带拼时长`() {
        val card = ProgressCardRenderer.terminal(RunStatus.TIMEOUT, 600_000L, progress())
        assertTrue(card.markdown.startsWith("⏱ 已超时 · 10m0s"), "屏④「已超时 · 10m00s」同构：${card.markdown}")
    }

    /** 🔴 终态 RUNNING 兜底（CANCELLED 未 markStatus 的显式传参路径）→ ⏳ 进行中。 */
    @Test
    fun `🔴 批④ 终态 RUNNING 兜底为进行中状态带`() {
        val card = ProgressCardRenderer.terminal(RunStatus.RUNNING, 0L, progress())
        assertEquals("⏳ 进行中", card.markdown.substringBefore("\n"), "RUNNING 兜底：${card.markdown}")
    }

    /** 🔴 footer 表情提示只挂在 running（receipt 太早、terminal 已换表情再提是噪音）。 */
    @Test
    fun `🔴 批④ 表情提示 footer 只在 running`() {
        val footer = ProgressCardRenderer.REACTION_FOOTER_HINT
        assertEquals("任务完成时，你原来的消息会收到表情提示", footer)
        assertTrue(
            ProgressCardRenderer.running(progress(ProgressEvent.AssistantText("x")), 1_000L).markdown.contains(footer),
            "running 卡正文末尾应有表情提示 footer",
        )
        assertFalse(
            ProgressCardRenderer.receipt("task").markdown.contains(footer),
            "🔴 受理卡不挂 footer（受理期太早）",
        )
        assertFalse(
            ProgressCardRenderer.terminal(RunStatus.DONE, 1_000L, progress()).markdown.contains(footer),
            "🔴 终态卡不挂 footer（已换表情再提是噪音）",
        )
    }

    /** 🔴 OVERFLOW_BODY 兜底路径不叠加状态带（窗口全被挤掉时状态带失去上下文意义）。 */
    @Test
    fun `🔴 批④ OVERFLOW 兜底正文不叠加状态带`() {
        val many = (1..12).map { ProgressEvent.AssistantText("内容$it".repeat(20)) }
        val limits = ProgressCardRenderer.Limits(maxEntries = 12, entryMaxChars = 200, cardMaxChars = 40)
        val card = ProgressCardRenderer.terminal(RunStatus.DONE, 192_000L, progress(*many.toTypedArray()), limits)
        assertEquals(
            ProgressCardRenderer.OVERFLOW_BODY,
            card.markdown,
            "🔴 兜底正文逐字保留、无状态带：${card.markdown}",
        )
    }

    /** 🔴 body()（ProgressAccumulator 快照底座）RUNNING 态首行也插状态带——运行中卡每帧经底座，不挂会被冲掉。 */
    @Test
    fun `🔴 批④ body 快照底座 RUNNING 态首行带状态带`() {
        val body = ProgressCardRenderer.body(progress(ProgressEvent.AssistantText("x")))
        assertTrue(body.startsWith("⏳ 进行中"), "运行中帧首行必须是状态带（底座层叠加）：$body")
        assertTrue(body.contains("**状态**："), "「状态」行保留：$body")
        // markStatus 后（终态帧）底座不再叠加——终态状态带由 terminal 显式传入
        val doneBody = ProgressCardRenderer.body(progress(ProgressEvent.AssistantText("x"), status = RunStatus.DONE))
        assertFalse(doneBody.startsWith("⏳"), "终态快照底座不叠加 RUNNING 状态带：$doneBody")
    }

    /**
     * 🔴 批 ④ 验收修复（orchestrator）：footer 同样下沉 body() 底座——生产链路流式帧全走 body()
     * （running 无生产调用点），footer 若只挂 running() 入口则用户实际看到的运行中卡永远没有 footer。
     */
    @Test
    fun `🔴 批④ body 快照底座 RUNNING 态末尾带表情 footer`() {
        val body = ProgressCardRenderer.body(progress(ProgressEvent.AssistantText("x")))
        assertTrue(
            body.endsWith(ProgressCardRenderer.REACTION_FOOTER_HINT),
            "运行中帧（用户实际看到的流式帧）末尾必须有表情提示 footer：$body",
        )
        // 终态帧不叠 footer（终态已换 ✅/❌ 表情，再提是噪音）
        val doneBody = ProgressCardRenderer.body(progress(ProgressEvent.AssistantText("x"), status = RunStatus.DONE))
        assertFalse(doneBody.contains(ProgressCardRenderer.REACTION_FOOTER_HINT), "终态帧不带 footer：$doneBody")
    }
}
