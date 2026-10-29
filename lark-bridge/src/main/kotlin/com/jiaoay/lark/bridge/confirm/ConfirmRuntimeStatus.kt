// 运行时确认链路可用性状态机。
// selfCheck 默认 async ⇒ 长连接建立成功后**后台**探测，探测期间对外暴露 Probing 而不是 Unavailable。
package com.jiaoay.lark.bridge.confirm

/**
 * 运行时确认链路可用性。
 *
 * 为什么它是宿主可观测状态：MCP server 命令模板拼装失败（classpath 含空格被拆词、命令行超长、
 * jpackage 态 `java.class.path` 不含业务类导致 MainKt ClassNotFound）只会留下一行 warn，
 * 表现为 claude 拿不到裁决 → fail-safe deny → 「agent 什么都不敢做」，用户与 UI 都无从判断原因。
 * 故本状态被提升为宿主可观测状态（App 设置页 / CLI serve 收集）。
 *
 * ## [Probing]（自检进行中）
 * 同步自检要在启动路径上跑一次完整 JVM 冷启动（最坏 `SELF_CHECK_TIMEOUT_MILLIS` = 30s 卡住启动），
 * 故默认 `agent.confirm.selfCheck = "async"`：长连接建立成功后**后台**探测，
 * 探测期间状态为 [Probing]（UI 显示「确认链路自检中」），探测结束再落 [Ready] / [Unavailable]。
 *
 * 🔴 [Unavailable] 的 fail-safe deny 语义是本状态机的底线：确认链路不可用时，高危操作一律拒绝，
 * 绝不因「确认不了」而放行。
 */
sealed class ConfirmRuntimeStatus {

    /** 未启用：confirm.enabled=false / 后端非 claude / 桥接已停止（正常配置态，非故障；agent 恒为硬依赖，无「agent 关闭」分支） */
    data object Disabled : ConfirmRuntimeStatus()

    /**
     * 自检进行中（`selfCheck="async"` 时，总线已监听、脚本已生成，但尚未确认能拉起）。
     *
     * 探测期语义 = 「暂按 [Unavailable] 处理（fail-safe deny），但不向用户报故障」。
     *
     * @param port 已监听的确认总线端口（127.0.0.1 回环）
     * @param scriptPath 已生成的 MCP server 启动脚本路径（排障时可直接手工执行）
     */
    data class Probing(
        val port: Int = -1,
        val scriptPath: String = "",
    ) : ConfirmRuntimeStatus()

    /**
     * 就绪：确认总线已监听 [port]，启动脚本已生成且自检拉起通过。
     *
     * @param port 确认总线端口（127.0.0.1 回环）
     * @param scriptPath MCP server 启动脚本路径（排障时可直接手工执行）
     */
    data class Ready(
        val port: Int,
        val scriptPath: String,
    ) : ConfirmRuntimeStatus()

    /**
     * 降级不可用：高危操作将 fail-safe deny（claude 侧表现为「什么都不敢做」）。
     *
     * @param reason 人读原因（总线启动失败 / 脚本生成失败 / 自检退出码与输出摘要）
     */
    data class Unavailable(
        val reason: String,
    ) : ConfirmRuntimeStatus()

    /** 是否处于「可以送裁决」的状态（仅 [Ready]）。🔴 [Probing] 视为不可用（fail-safe）。 */
    val isReady: Boolean get() = this is Ready
}

/**
 * 确认通知出口：在确认请求产生后、挂起等待裁决前，向飞书发送确认卡片。
 *
 * 确认通知出口：在确认请求产生后、挂起等待裁决前，向飞书发送确认卡片。
 *
 * 实现约定：
 * - 发送失败降级不阻断（fail-safe）：MUST NOT 抛异常，失败时仅记日志
 * - 卡片内容含 toolName、input 摘要、4 位短 ID、操作引导文案
 * - 🔴 卡片必须是**非流式交互卡**（`CardJson.confirm(...)`）——流式卡内禁放任何交互组件（飞书平台限制）
 * - 由门面装配为基于 `ReplySink` 的实现
 */
interface ConfirmNotifier {
    /**
     * 发送确认卡片到飞书（非阻塞，失败降级）。
     *
     * @param request 确认请求快照（含 chatId/senderOpenId/toolName/inputSummary/requestId）
     */
    suspend fun notify(request: ConfirmService.ConfirmRequest)
}
