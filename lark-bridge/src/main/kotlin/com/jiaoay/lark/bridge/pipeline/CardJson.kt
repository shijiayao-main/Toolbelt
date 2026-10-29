// 卡片构造。
// 飞书 200810「流式期间无法响应卡片回传」⇒ 流式卡内**禁放任何交互组件**；确认/裁决一律独立**非流式**卡。
// 消解手法：在 CardSpec 构造层面就保证——streamingCard 的签名里**根本没有 buttons 参数**，
//    interactionCard 的签名里**根本没有 streaming 参数**（恒 false）。构造不出违规组合 ⇒ 无需运行时校验兜底。
// 零 SDK：只产出 :lark-gateway 的 CardSpec 纯数据；JSON 拼装归网关 LarkOutboundMapper。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.CardButtonSpec
import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.CardTitleColor

/**
 * 卡片构造唯一入口（纯函数 object，零可变状态 ⇒ 守护测试零命中）。
 *
 * 三条不变式（由签名强制，不靠纪律）：
 * 1. [streaming] 产出的卡 `buttons` 恒为 `emptyList()`；
 * 2. [interaction]/[confirm] 产出的卡 `streaming` 恒为 `false`；
 * 3. [requireNonInteractiveStreaming] 是最后一道防线：任何**外部**构造的 CardSpec 若要进流式通道，
 *    必须经它校验（T8 的 handler 若自行拼 CardSpec，一律过这道）。
 */
object CardJson {

    /** 受理态卡（流式）：标题蓝、正文随进度增量 patch。无 buttons 参数。 */
    fun streaming(
        title: String,
        markdown: String,
        titleColor: CardTitleColor = CardTitleColor.BLUE,
        summary: String? = null,
    ): CardSpec = CardSpec(
        title = title,
        titleColor = titleColor,
        markdown = markdown,
        buttons = emptyList(),
        streaming = true,
        summary = summary,
    )

    /**
     * 交互卡（**非流式**）：确认/裁决/任意按钮回传。无 streaming 参数（恒 false）。
     * @throws IllegalArgumentException buttons 为空——交互卡没按钮没有意义，多半是调用方漏传。
     */
    fun interaction(
        title: String,
        markdown: String,
        buttons: List<CardButtonSpec>,
        titleColor: CardTitleColor = CardTitleColor.GREY,
        summary: String? = null,
    ): CardSpec {
        require(buttons.isNotEmpty()) { "交互卡必须至少有一个按钮（否则应改用 streaming/terminal）" }
        return CardSpec(
            title = title,
            titleColor = titleColor,
            markdown = markdown,
            buttons = buttons,
            streaming = false,
            summary = summary,
        )
    }

    /**
     * 确认卡（confirm 域用）。🔴 **独立非流式卡**：与正在流式的进度卡**并存**，
     * 绝不把按钮塞进进度卡（否则进度卡处于 streaming_mode，按钮回传被 200810 拒绝）。
     *
     * @param token 确认令牌；回传时出现在 `CardAction.value` 里，confirm 总线据此裁决。
     */
    fun confirm(
        title: String,
        markdown: String,
        token: String,
        acceptLabel: String = "允许",
        rejectLabel: String = "拒绝",
        acceptValue: String = "allow",
        rejectValue: String = "deny",
    ): CardSpec = interaction(
        title = title,
        markdown = markdown,
        buttons = listOf(
            CardButtonSpec(text = acceptLabel, value = "$token:$acceptValue", primary = true),
            CardButtonSpec(text = rejectLabel, value = "$token:$rejectValue", primary = false),
        ),
        titleColor = CardTitleColor.BLUE,
    )

    /**
     * 确认卡的**已答态回执**（answeredQuestionCard 已答卡语义）：
     * 裁决后经 `ReplySink.updateExistingCard` 整卡替换原确认卡——按钮消失、标注裁决结果，
     * 消灭「原卡按钮滞留 + 新发文字回执」的状态脱节。
     *
     * 非流式、**无按钮**（已答态的要点正是按钮消失；新裁决入口仍在文字通道「确认/拒绝 &lt;id&gt;」）。
     * 🔴 不变式：`streaming=false` 且 `buttons=emptyList()`——绝不能进流式通道（200810），
     *    也不携带任何交互组件。
     *
     * @param approved 裁决方向：true=已批准（绿标）/ false=已拒绝（灰标）
     * @param toolName 原确认卡的工具名（已答卡保留原问题上下文——评审定稿语义）
     * @param markdown 已答态正文（裁决结果 + 时间 + 原参数摘要，由调用方组装）
     */
    fun answeredConfirm(
        approved: Boolean,
        toolName: String,
        markdown: String,
    ): CardSpec = CardSpec(
        title = if (approved) "✅ 已批准" else "已拒绝",
        titleColor = if (approved) CardTitleColor.GREEN else CardTitleColor.GREY,
        markdown = markdown,
        buttons = emptyList(),
        streaming = false,
        summary = "确认 $toolName：${if (approved) "已批准" else "已拒绝"}",
    )

    /**
     * 确认卡的**已超时态**（[answeredConfirm] 的超时分支变体）：
     * 超时拒绝后经 `ReplySink.updateExistingCard` 整卡替换原确认卡——按钮消失、标注「已超时拒绝」，
     * 消灭「超时后原卡按钮滞留可点」与真实状态（已超时拒绝）的脱节。
     *
     * 独立新函数而非给 [answeredConfirm] 加参：语义自明、既有裁决调用点零动。
     * 不变式同款：`streaming=false` 且 `buttons=emptyList()`（不进流式通道、不带交互组件）。
     * 标题固定「⏱ 已超时拒绝」灰标（等同拒绝语义；markdown 由调用方组装——骨架对齐
     *    BuiltinHandlers 已答态先例 + 末行「单据：cf-xxxx」）。
     *
     * @param toolName 原确认卡的工具名（超时卡保留原问题上下文——评审定稿语义）
     * @param markdown 已超时态正文（超时结果 + 工具 + 参数 + 单据短标识，由调用方组装）
     */
    fun timedOutConfirm(
        toolName: String,
        markdown: String,
    ): CardSpec = CardSpec(
        title = "⏱ 已超时拒绝",
        titleColor = CardTitleColor.GREY,
        markdown = markdown,
        buttons = emptyList(),
        streaming = false,
        summary = "确认 $toolName：已超时拒绝",
    )

    /**
     * 终态卡（成功/失败/跳过）。非流式、无按钮：它是流式卡的**收尾**（`CardStreamSink.flushTerminal`）
     * 或独立发送的结果卡，两种用法都要求 `streaming=false`。
     */
    fun terminal(
        title: String,
        markdown: String,
        status: TaskStatus,
        summary: String? = null,
    ): CardSpec = CardSpec(
        title = title,
        titleColor = when (status) {
            TaskStatus.SUCCESS -> CardTitleColor.GREEN
            TaskStatus.FAILURE -> CardTitleColor.RED
            TaskStatus.SKIPPED -> CardTitleColor.GREY
        },
        markdown = markdown,
        buttons = emptyList(),
        streaming = false,
        summary = summary,
    )

    /**
     * 最后一道防线：断言 [spec] 可以安全进入流式通道。
     * @throws IllegalStateException spec 带交互组件却声明 streaming=true（200810 的组合）。
     */
    fun requireNonInteractiveStreaming(spec: CardSpec): CardSpec {
        check(!(spec.streaming && spec.buttons.isNotEmpty())) {
            "流式卡内禁放交互组件（飞书 200810：流式期间无法响应卡片回传）；" +
                "确认/裁决请改用 CardJson.confirm 发独立非流式卡"
        }
        return spec
    }
}
