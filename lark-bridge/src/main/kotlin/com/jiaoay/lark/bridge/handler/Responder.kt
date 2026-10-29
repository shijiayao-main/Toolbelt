// 回复排版器（Responder）+ 复合 handler（CompositeHandler）。
// 回复排版由 ReplyRendererTest 逐行断言守护（一个字符都不能差）。
// 🔴 零 SDK、零静态可变状态（纯函数 object；SDK 隔离守护扫盘钉死）。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.pipeline.HandlerOutcome
import com.jiaoay.lark.bridge.pipeline.HandlerWeight
import com.jiaoay.lark.bridge.pipeline.MessageHandler
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.ReplyDirective
import com.jiaoay.lark.bridge.pipeline.TaskKind
import com.jiaoay.lark.bridge.pipeline.TaskStatus

/**
 * 回复排版器：把 [HandlerOutcome] 渲染成飞书 markdown 文本。
 *
 * ## 🔒 精确排版（`ReplyRendererTest` 逐行断言，一个字符都不能差）
 * ```
 * lines[0] = "**<kindLabel> · <statusLabel>**"
 * lines[1] = <outcome.summary>
 * lines[2] = "错误码：`<errorCode>`"   // 仅 errorCode != null
 * lines[3] = "耗时：<x.x>s"            // 仅 elapsedMillis > 0（elapsedMillis / 1000.0）
 * ```
 * - kindLabel 直接取 [TaskKind.label]（`⚡ 指令` / `🧩 快捷指令` / `🤖 Agent`）；
 * - statusLabel：SUCCESS=`✅ 任务完成`、FAILURE=`❌ 任务失败`、SKIPPED=`⏭️ 已跳过`。
 *
 * ## 为什么由 handler 调用而非 Admission.deliver
 * `Admission.deliver`（已被 `RoutingTest`/`AccessPolicyTest`/`AdmissionTimeoutTest` 锁定）
 * 直接把 `outcome.summary` 原样发出，**不做任何排版**。为在**不削弱任何既有断言**的前提下让生产回复
 * 携带类型前缀（agent 回复「含 任务完成 + stdout」），排版落在 handler 侧：handler 产出结构化
 * [HandlerOutcome] 后经 [decorate] 把渲染文本写回 `summary`，deliver 再原样发出即得排版后的回复。
 * 测试用的 `ScriptedHandler` / 未命中兜底 / 拒绝回复不经本类 ⇒ 仍发 raw（既有断言零影响）。
 */
object Responder {

    /** 状态标识文案（🔒 锁定文案，ReplyRendererTest 断言）。 */
    fun statusLabel(status: TaskStatus): String = when (status) {
        TaskStatus.SUCCESS -> "✅ 任务完成"
        TaskStatus.FAILURE -> "❌ 任务失败"
        TaskStatus.SKIPPED -> "⏭️ 已跳过"
    }

    /** 类型标识文案（🔒 直接复用 [TaskKind.label]，杜绝双份字面值漂移）。 */
    fun kindLabel(kind: TaskKind): String = kind.label

    /**
     * 渲染完整回复文本（🔒 锁定格式，ReplyRendererTest 断言）。纯函数、零 IO、零状态。
     */
    fun render(outcome: HandlerOutcome): String = buildString {
        append("**").append(kindLabel(outcome.kind)).append(" · ").append(statusLabel(outcome.status)).append("**\n")
        append(outcome.summary)
        outcome.errorCode?.let { append("\n错误码：`").append(it).append("`") }
        if (outcome.elapsedMillis > 0) {
            append("\n耗时：").append(outcome.elapsedMillis / 1000.0).append("s")
        }
    }

    /**
     * 把 [render] 的结果写回 `outcome.summary`，供 handler 返回。
     * 以下情形**不排版**（保持原样）：
     * - 带 [HandlerOutcome.card]：卡片已自带完整内容，不再拼状态行；
     * - [ReplyDirective.SILENT] / [ReplyDirective.ALREADY_REPLIED]：本就不经 deliver 发送或已自行回复。
     */
    fun decorate(outcome: HandlerOutcome): HandlerOutcome =
        if (outcome.card != null || outcome.directive != ReplyDirective.DEFAULT) {
            outcome
        } else {
            outcome.copy(summary = render(outcome))
        }
}

/**
 * 复合 handler：把**同一 [RouteKind] 槽**下的多个能力单元按序合成一个 [MessageHandler]。
 *
 * ## 为什么需要它
 * `HandlerKinds.build` 每个 [RouteKind] 只接受**一个**工厂、`config.routes` 每 kind 恰一条 route
 * （见 `DefaultBridgeConfig.DEFAULT_ROUTES`），但一个 kind 槽要承载多项能力：
 * - SYSTEM 槽 = `SystemHandler`(LIGHT，ping / `/commands`) + `StopHandler`(URGENT)；
 * - COMMAND 槽 = `InteractionHandler`(LIGHT，卡片回传 + 确认/拒绝) + `CommandHandler`(LIGHT/HEAVY)。
 * 一条 route 无法绑多个 handler ⇒ 用本复合器在**同一 route** 下按序分发，配合 [MessageHandler.weightFor]
 * 表达「同槽不同消息不同档位」（附加式 seam）。
 *
 * ## 分发纪律
 * - [match]：任一子 handler 命中即命中；
 * - [weightFor]：取**第一个命中的子 handler** 的 `weightFor`（纯 CPU、O(子数)、零 IO、零锁，intake 零锁纪律）；
 * - [handle]：交给第一个命中的子 handler；无命中 ⇒ SKIPPED（match 已保证，防御性兜底）。
 * 子 handler 顺序即优先级（裁决/停止类先于内容类）。
 *
 * @param staticWeight [weight] 的静态取值（`describe()` 与 UI 预览用）；默认取首个子 handler 的静态 weight。
 */
class CompositeHandler(
    override val kind: RouteKind,
    private val children: List<MessageHandler>,
    override val timeoutSeconds: Long? = null,
    staticWeight: HandlerWeight? = null,
) : MessageHandler {

    init {
        require(children.isNotEmpty()) { "CompositeHandler 至少需要一个子 handler" }
    }

    override val weight: HandlerWeight = staticWeight ?: children.first().weight

    /** 只读预览：子 handler 的静态档位（测试与排障用）。 */
    val childWeights: List<HandlerWeight> get() = children.map { it.weight }

    private fun select(ctx: RequestContext): MessageHandler? = children.firstOrNull { it.match(ctx) }

    override fun match(ctx: RequestContext): Boolean = select(ctx) != null

    override fun weightFor(ctx: RequestContext): HandlerWeight = select(ctx)?.weightFor(ctx) ?: weight

    override suspend fun handle(ctx: RequestContext): HandlerOutcome =
        select(ctx)?.handle(ctx)
            ?: HandlerOutcome.skipped(kind = com.jiaoay.lark.bridge.pipeline.TaskKind.COMMAND, summary = "复合 handler 无子命中", silent = true)
}
