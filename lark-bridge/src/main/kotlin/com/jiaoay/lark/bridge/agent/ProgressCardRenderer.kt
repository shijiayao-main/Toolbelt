// agent/：进度卡片渲染（受理/进行中/终态三态）。
// 产出物是 `:lark-gateway` 的 **`CardSpec` 纯数据**，JSON 拼装交网关 `LarkOutboundMapper`；
//    流式态经 `CardJson.streaming()`（签名里没有 buttons 参数 ⇒ 流式卡组合不出按钮），
//    终态经 `CardJson.terminal()`。🔴 弃用「消息编辑」做流式（旧形态每 patch 一次就 edit 一次消息，
//    触发频率上限且用户端闪烁）。
// 标题/正文为锁定文案、三层截断算法、`singleLine`、`formatElapsed`、`RunStatus.label()` —— 勿随手改。
package com.jiaoay.lark.bridge.agent

import com.jiaoay.lark.bridge.pipeline.CardJson
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.CardTitleColor

/**
 * 进度卡片渲染：受理/进行中/终态三态。
 *
 * 产出 `CardSpec`（纯数据），由 `:lark-gateway` 拼 CardKit 卡片实体 + `streaming_mode`；
 * lib 侧不再手搓卡片 JSON，也**不用消息编辑**做流式（每 patch 一次就 edit 一次消息，
 * 触发频率上限且用户端闪烁）。
 *
 * 🔴 **流式卡防按钮**：本渲染器产出的**流式**卡（[receipt]/[running]）恒无按钮——经
 * [CardJson.streaming] 构造，签名层面就拿不到 buttons；确认/裁决卡走 [CardJson.confirm]
 * 发**独立非流式卡**，与进度卡并存。
 *
 * 截断三层：单条 entryMaxChars → 滚动窗口 maxEntries 条 → 整卡 cardMaxChars（超长丢最旧）。
 *
 * ## 正文首行状态带 + footer
 * 流式卡 header 建卡后不可变是平台事实（cardkit 无 header 更新通道，`flushTerminal` 只写正文）——
 * **状态权威落正文首行状态带**，卡头恒蓝是诚实形态。状态带四态文案：
 *
 * | 状态 | 状态带 | 拼接规则 |
 * |------|--------|---------|
 * | RUNNING | [STATE_LINE_RUNNING] | 兜底态（含 CANCELLED 未 markStatus 的显式传参路径） |
 * | DONE | `✅ 已完成 · 用时 X` | elapsedMillis==0 时不拼「用时」段（现调用点传 0——耗时以文字回复为准，拼「用时 0s」是自欺） |
 * | FAILED | `❌ 失败 · $errorText` | errorText 为空（超时/取消路径拿不到 AgentRunResult）时仅出短语；错误码纯码形态，人话映射在 feature 侧 |
 * | TIMEOUT | `⏱ 已超时 · X` | elapsedMillis==0 同 DONE 不拼 |
 *
 * 状态带是**叠加不是替换**：「**状态**：xxx」行保留（承载 RUNNING 态语义、receipt/OVERFLOW_BODY 兜底也用它）；
 *    [OVERFLOW_BODY] 截断兜底路径**不叠加**状态带（窗口全被挤掉时正文只剩兜底一句，状态带失去上下文意义）。
 * footer（[REACTION_FOOTER_HINT]）只挂在 [running]——受理期（[receipt]）太早、终态已换表情再提是噪音。
 * **CardSpec 零新字段**：状态带/footer 全是 markdown 文案层；终态状态带由调用方显式传参钉死
 *    （[terminal] 的 status 形参，而非 `progress.status`——CANCELLED 未 markStatus 时 status 仍是 RUNNING）。
 */
object ProgressCardRenderer {

    /** 渲染限制（App/调用方按 BridgeConfig 换算传入）
     * 默认值 6 条 / 80 字 / 2000 字（防刷屏收窄）。
     */
    data class Limits(
        val maxEntries: Int = 6,
        val entryMaxChars: Int = 80,
        val cardMaxChars: Int = 2000,
    )

    private const val DEFAULT_LIMITS_ENTRY = 200

    // ── 正文首行状态带四态文案 ──
    // emoji 在飞书卡片 markdown 里是平台文案语言（RECEIPT_TITLE/确认卡标题/Responder 既有先例），
    //    不属 Compose 图标矢量铁律射程；状态符号每态恰一个，不新增装饰性 emoji。

    /** RUNNING 态状态带（「⏳ 进行中」）。 */
    const val STATE_LINE_RUNNING: String = "⏳ 进行中"

    /** DONE 态状态带短语（「✅ 已完成」，后拼「 · 用时 X」）。 */
    const val STATE_LINE_DONE: String = "✅ 已完成"

    /** FAILED 态状态带短语（「❌ 失败」，errorText 非空时拼「 · $errorText」）。 */
    const val STATE_LINE_FAILED: String = "❌ 失败"

    /** TIMEOUT 态状态带短语（「⏱ 已超时」，后拼「 · 用时 X」）。 */
    const val STATE_LINE_TIMEOUT: String = "⏱ 已超时"

    /**
     * 🔒 进行中卡 footer（「任务完成时，你原来的消息会收到表情提示」）：
     * 把既有表情信号机制（[com.jiaoay.lark.bridge.handler.BuiltinHandlers] 的 👀 接单/✅/❌ 终态）
     * 写进产品文案。只挂在 [running]——receipt 受理期太早、terminal 终态已换表情再提反而噪。
     */
    const val REACTION_FOOTER_HINT: String = "任务完成时，你原来的消息会收到表情提示"

    /** 🔒 受理卡标题（锁定文案）。 */
    const val RECEIPT_TITLE = "🤖 Agent 已受理，正在思考"

    /**
     * 受理卡片：蓝 header + 任务摘要（流式卡，无按钮）。
     * 受理卡**不参与**三层截断：`task` 已被 [DEFAULT_LIMITS_ENTRY]=200 截断，正文长度有硬上界
     * （200 + 固定文案），恒小于任何合理的 `cardMaxChars`；对它跑滚动窗口截断没有意义。
     */
    fun receipt(task: String, limits: Limits = Limits()): CardSpec {
        val trimmed = task.trim().take(DEFAULT_LIMITS_ENTRY)
        val markdown = "**任务**：$trimmed\n**状态**：排队/思考中，进度将在此更新\n\n$STATE_LINE_RUNNING"
        return CardJson.streaming(
            title = RECEIPT_TITLE,
            markdown = if (markdown.length > limits.cardMaxChars) markdown.take(limits.cardMaxChars) else markdown,
            titleColor = CardTitleColor.BLUE,
        )
    }

    /** 进行中卡片：蓝 header（含已运行时长）+ 最近过程流（流式卡，无按钮）+ footer 表情提示 */
    fun running(
        progress: TaskProgress,
        elapsedMillis: Long,
        limits: Limits = Limits(),
    ): CardSpec = render(
        title = "🤖 Agent 处理中 · 已运行 ${formatElapsed(elapsedMillis)}",
        template = "blue",
        progress = progress,
        limits = limits,
        streaming = true,
        stateLine = STATE_LINE_RUNNING,
        footer = REACTION_FOOTER_HINT,
    )

    /** 终态卡片：绿/红 header + 过程流尾部（非流式，供 `CardStreamSink.flushTerminal` 或独立发送） */
    fun terminal(
        status: RunStatus,
        elapsedMillis: Long,
        progress: TaskProgress,
        limits: Limits = Limits(),
        /** 失败详情（错误码等，可空）——仅 FAILED 态拼进状态带；超时/取消路径拿不到结果时为 null */
        errorText: String? = null,
    ): CardSpec {
        val (title, template) = when (status) {
            RunStatus.DONE -> "✅ 已完成 · 耗时 ${formatElapsed(elapsedMillis)} · 结果见下方回复" to "green"
            RunStatus.TIMEOUT -> "⏱ 已超时 · ${formatElapsed(elapsedMillis)}" to "red"
            else -> "❌ 失败 · ${formatElapsed(elapsedMillis)} · 结果见下方回复" to "red"
        }
        // 🔴 状态带由调用方显式传参（status 形参）决定，不读 progress.status——CANCELLED 未 markStatus
        //    路径 progress.status 仍是 RUNNING，读它会与「已按失败收尾」的终态卡语义自相矛盾。
        // elapsedMillis == 0L 不拼「用时」段：现调用点（runAgentPipeline 收尾）恒传 0（耗时以文字回复
        //    为准），拼「用时 0s」是自欺；有真实耗时的调用方才展示。
        val elapsedText = formatElapsed(elapsedMillis).takeIf { elapsedMillis != 0L }
        val stateLine = when (status) {
            RunStatus.DONE -> STATE_LINE_DONE + (elapsedText?.let { " · 用时 $it" } ?: "")
            RunStatus.TIMEOUT -> STATE_LINE_TIMEOUT + (elapsedText?.let { " · $it" } ?: "")
            RunStatus.FAILED -> STATE_LINE_FAILED + (errorText?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            // RUNNING 兜底（含 CANCELLED 未 markStatus 但显式传参的路径）
            RunStatus.RUNNING -> STATE_LINE_RUNNING
        }
        return render(title, template, progress, limits, streaming = false, stateLine = stateLine)
    }

    /**
     * 卡片正文（markdown）——[com.jiaoay.lark.bridge.pipeline.ProgressSink.renderSnapshot] 的实现底座：
     * 进度累积器把本函数结果喂进 1 槽 conflate，再由 `pumpSnapshotsToCard` 增量推给 `CardStreamSink`。
     *
     * 运行中卡的**每一帧**都经本函数（`ProgressAccumulator.record`/`markStatus` → `delegate.append`），
     * 状态带若只挂在 [running] 入口会被下一帧冲掉——故 RUNNING 态在**底座**首行插 [STATE_LINE_RUNNING]
     * （快照 `progress.status` 承载运行期真值；终态带由 [terminal] 经 [render] 的 `stateLine` 显式传参，
     * 与底座兜底互斥——见 [render] 注释）。OVERFLOW_BODY 兜底不叠加。
     *
     * [REACTION_FOOTER_HINT] 同样下沉底座——生产链路
     * 流式帧全走本函数（[running] 无生产调用点，仅测试直调），footer 若只挂 [running] 入口则
     * **用户实际看到的运行中卡永远没有 footer**。与状态带同条件（RUNNING 态、非 OVERFLOW 兜底）
     * 末尾追加，[running] 入口的 footer 参数保留（API 语义完整，测试直调路径不受影响）。
     */
    fun body(progress: TaskProgress, limits: Limits = Limits()): String {
        val base = truncatedBody(progress, limits)
        // 仅 RUNNING 态叠加（markStatus 后底座快照已是终态——终态帧的状态带由 terminal 显式传入）；
        //    OVERFLOW 兜底不叠加（窗口全被挤掉时状态带失去上下文意义且旧断言锚定逐字）。
        if (progress.status != RunStatus.RUNNING || base == OVERFLOW_BODY) return base
        return "$STATE_LINE_RUNNING\n\n$base\n\n---\n$REACTION_FOOTER_HINT"
    }

    private fun render(
        title: String,
        template: String,
        progress: TaskProgress,
        limits: Limits,
        streaming: Boolean,
        /** 正文首行状态带；null = 不叠加（[OVERFLOW_BODY] 兜底路径） */
        stateLine: String? = null,
        /** 正文末尾 footer（仅 running 传）；null = 不追加 */
        footer: String? = null,
    ): CardSpec {
        val body = truncatedBody(progress, limits)
        val composed = when {
            // OVERFLOW_BODY 兜底路径不叠加状态带/footer：窗口全被挤掉时正文只剩兜底一句，
            //    状态带失去上下文意义（且旧断言锚定 OVERFLOW_BODY 逐字）。
            body == OVERFLOW_BODY -> body
            else -> buildString {
                if (stateLine != null) {
                    append(stateLine)
                    append("\n\n")
                }
                append(body)
                if (footer != null) {
                    append("\n\n---\n").append(footer)
                }
            }
        }
        return if (streaming) {
            CardJson.streaming(title = title, markdown = composed, titleColor = colorOf(template))
        } else {
            // 🔴 终态卡配色必须由 `template`（源自 `terminal(status=…)` 的显式状态）决定，
            //    与标题同源。误用 `progress.status`
            //    会导致「标题说已完成、header 却是灰色」的自相矛盾（当调用方尚未 markStatus 时）。
            CardJson.terminal(title = title, markdown = composed, status = statusOfTemplate(template))
        }
    }

    /** 整卡截断：从满窗口逐条丢弃最旧，直到 body 不超 cardMaxChars（算法勿改）。 */
    private fun truncatedBody(progress: TaskProgress, limits: Limits): String {
        var entries = progress.events.takeLast(limits.maxEntries)
        var body = renderBody(progress.copy(events = entries), limits.entryMaxChars)
        while (body.length > limits.cardMaxChars && entries.isNotEmpty()) {
            entries = entries.drop(1)
            body = if (entries.isEmpty()) OVERFLOW_BODY else renderBody(progress.copy(events = entries), limits.entryMaxChars)
        }
        return body
    }

    /** 🔒 窗口全部被字符上限挤掉时的兜底正文（锁定文案）。 */
    const val OVERFLOW_BODY = "**过程**：（过长已省略）"

    private fun renderBody(progress: TaskProgress, entryMaxChars: Int): String = buildString {
        append("**状态**：${progress.status.label()}")
        if (progress.events.isNotEmpty()) {
            append("\n\n**过程**（最近 ${progress.events.size} 条）")
            progress.events.forEach { e ->
                append("\n").append(renderEntry(e, entryMaxChars))
            }
        }
    }

    private fun renderEntry(e: ProgressEvent, entryMaxChars: Int): String = when (e) {
        is ProgressEvent.Thinking -> "> 💭 ${singleLine(e.text, entryMaxChars)}"
        is ProgressEvent.ToolUse -> "> 🔧 **${e.name}** `${singleLine(e.argsSummary, entryMaxChars)}`"
        is ProgressEvent.ToolResult -> "> ${if (e.ok) "✅" else "❌"} ${e.name} ${singleLine(e.summary, entryMaxChars)}"
        is ProgressEvent.AssistantText -> "> 📝 ${singleLine(e.text, entryMaxChars)}"
    }

    /** `template` 字面值 → 网关 `CardTitleColor`（一一对应，无新增色） */
    private fun colorOf(template: String): CardTitleColor = when (template) {
        "green" -> CardTitleColor.GREEN
        "red" -> CardTitleColor.RED
        "grey" -> CardTitleColor.GREY
        else -> CardTitleColor.BLUE
    }

    /**
     * `template` 字面值 → [TaskStatus]（终态卡配色用；与 [colorOf] 同源于 `template`）。
     * 🔴 与 `terminal(status=…)` 的显式状态同源，不读 `progress.status`——否则配色可能与标题矛盾。
     */
    private fun statusOfTemplate(template: String): TaskStatus = when (template) {
        "green" -> TaskStatus.SUCCESS
        "red" -> TaskStatus.FAILURE
        else -> TaskStatus.SKIPPED
    }

    private fun singleLine(text: String, max: Int): String {
        val collapsed = text.replace("\n", " ").trim()
        return if (collapsed.length <= max) collapsed else collapsed.take(max) + "…"
    }

    /** 已运行时长文案（🔒 `"${m}m${s}s"` / `"${s}s"`，锁定）。 */
    fun formatElapsed(millis: Long): String {
        val totalSec = millis / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "${m}m${s}s" else "${s}s"
    }

    /** 🔒 状态文案（锁定）。 */
    fun RunStatus.label(): String = when (this) {
        RunStatus.RUNNING -> "思考/执行中"
        RunStatus.DONE -> "已完成"
        RunStatus.FAILED -> "失败"
        RunStatus.TIMEOUT -> "超时"
    }
}
