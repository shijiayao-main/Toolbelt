// ③ Execute 阶段的产出值类型。🔴 errorCode 字面值严格对齐 behavior-contract.md §4.13（全集）：
// 「一个都不能少、不能改名」——用户存量 audit.jsonl 已含这些值，UI 按字面分桶。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.CardSpec

/**
 * 任务终态（计数纪律：SUCCESS→completedCount、FAILURE→failedCount、
 * **SKIPPED 不计入**——BridgeEngine 语义，测试锁定）。
 */
enum class TaskStatus { SUCCESS, FAILURE, SKIPPED }

/**
 * 任务类型（🔴 **SKILL 必须保留**——回复抬头
 * `**🧩 快捷指令 · ✅ 任务完成**` 依赖它，删掉会让存量审计行的 kind 分桶失效）。
 */
enum class TaskKind(val label: String) {
    COMMAND("⚡ 指令"),
    SKILL("🧩 快捷指令"),
    AGENT("🤖 Agent"),
}

/**
 * errorCode 全集（🔒 17 个字面值，逐字来自实测定稿；一个都不能少、不能改名）。
 *
 * 纯 `const val` object ⇒ 零可变静态状态（LarkSdkIsolationTest F 组零命中）。
 * 归属：`task_queue_timeout` / `agent_busy` / `task_timeout` 由 Admission/执行阶段产出；
 *    其余由 handlers/agent/confirm/files 组产出。放在本文件是为让两侧共用同一份字面值，杜绝拼写漂移。
 */
object TaskErrorCodes {
    // —— 准入与超时产出 ——
    /** 排队等待超 `runtime.queueWaitSeconds`。 */
    const val TASK_QUEUE_TIMEOUT: String = "task_queue_timeout"

    /** 同一 chat 超 `runtime.chatQueueLimit` 在途上限。 */
    const val AGENT_BUSY: String = "agent_busy"

    /** 总超时或空闲看门狗触发。 */
    const val TASK_TIMEOUT: String = "task_timeout"

    // —— agent 域产出 ——
    const val AGENT_BACKEND_UNAVAILABLE: String = "agent_backend_unavailable"
    const val AGENT_CONFIG_INVALID: String = "agent_config_invalid"
    const val AGENT_EMPTY_OUTPUT: String = "agent_empty_output"
    const val AGENT_WORKSPACE_MISSING: String = "agent_workspace_missing"

    /**
     * 子进程命令行被 OS/运行时校验拒绝：
     * JDK ProcessImpl 对 .cmd/.bat 参数的 VERIFICATION_CMD_BAT 校验拒绝裸内嵌引号时抛
     * IllegalArgumentException 的归一归宿——此前穿透双 catch 产裸 task_error。
     */
    const val AGENT_START_FAILED: String = "agent_start_failed"

    /**
     * Koog 内建迭代保险丝触顶（AIAgentMaxNumberOfIterationsReachedException
     * 专项 catch 的归宿）。归属 host 侧（KoogAgentRunner）产生、飞书端按字面分桶——与
     * 17 字面值锁定集并存新增，不顶替 `task_error` 兜底。
     */
    const val AGENT_MAX_ITERATIONS: String = "agent_max_iterations"

    /** 前缀：非 0 退出码拼成 `agent_exit_<code>`（前缀 + 数字，不是单一字面值）。 */
    const val AGENT_EXIT_PREFIX: String = "agent_exit_"

    fun agentExit(code: Int): String = AGENT_EXIT_PREFIX + code

    // —— 快捷指令域产出 ——
    const val COMMAND_AGENT_DISABLED: String = "command_agent_disabled"
    const val COMMAND_CONFIRM_UNAVAILABLE: String = "command_confirm_unavailable"
    const val COMMAND_NOT_FOUND: String = "command_not_found"
    const val COMMAND_TEMPLATE_EMPTY: String = "command_template_empty"

    // —— 文件消息域产出 ——
    const val FILE_AGENT_DISABLED: String = "file_agent_disabled"
    const val FILE_DOWNLOAD_FAILED: String = "file_download_failed"
    const val FILE_EXTENSION_REJECTED: String = "file_extension_rejected"

    // —— 引用流产出 ——
    const val FILE_WORKSPACE_MISSING: String = "file_workspace_missing"

    // —— 语音消息域产出（入站 AUDIO 转写链，与锁定字面值集并存新增）——
    /** 语音时长超 AudioHandler 硬上限（下载前拒收；详见其 KDoc 的双约束说明）。 */
    const val AUDIO_DURATION_EXCEEDED: String = "audio_duration_exceeded"

    /** 宿主转写闭包执行失败（解码/推理异常）；模型未下载属 Unavailable 引导回执，不产此码。 */
    const val AUDIO_TRANSCRIBE_FAILED: String = "audio_transcribe_failed"

    // —— 兜底 ——
    const val TASK_ERROR: String = "task_error"

    /** 全集（含 `agent_exit_` 前缀形态）；供 MetricsCounters/UI 分桶与文档核对。 */
    val ALL: Set<String> = setOf(
        AGENT_BACKEND_UNAVAILABLE, AGENT_BUSY, AGENT_CONFIG_INVALID, AGENT_EMPTY_OUTPUT,
        AGENT_MAX_ITERATIONS, AGENT_TIMEOUT_ALIAS, AGENT_WORKSPACE_MISSING, AGENT_EXIT_PREFIX,
        AUDIO_DURATION_EXCEEDED, AUDIO_TRANSCRIBE_FAILED,
        COMMAND_AGENT_DISABLED, COMMAND_CONFIRM_UNAVAILABLE, COMMAND_NOT_FOUND, COMMAND_TEMPLATE_EMPTY,
        FILE_AGENT_DISABLED, FILE_DOWNLOAD_FAILED, FILE_EXTENSION_REJECTED, FILE_WORKSPACE_MISSING,
        TASK_ERROR, TASK_QUEUE_TIMEOUT, TASK_TIMEOUT,
    )

    /** behavior-contract.md §4.13 同时登记了 `agent_timeout`（agent 内部超时）——与 `task_timeout` 并存，不可合并。 */
    const val AGENT_TIMEOUT_ALIAS: String = "agent_timeout"
}

/**
 * handler 对回复环节的指示。
 * - [DEFAULT]：由 ④ Reply 阶段按 [HandlerOutcome] 渲染并发送。
 * - [ALREADY_REPLIED]：handler 已自行发出回复（如流式卡的终态卡），Reply 阶段**不得**再发一条。
 * - [SILENT]：刻意静默（如未授权拒绝且 `access.rejectionReply` 为空、SKIPPED 的探测类消息）。
 */
enum class ReplyDirective { DEFAULT, ALREADY_REPLIED, SILENT }

/**
 * ③ Execute 的产出（旧 `TaskResult` 的重建版：去掉 `cardJson: String`，改持 [CardSpec] 值类型——
 * 卡片 JSON 拼装归 [com.jiaoay.lark.bridge.pipeline.CardJson]，handler 不再手搓 JSON 字符串，
 * 这样「流式卡内禁放交互组件」(200810) 才能在类型层面被强制）。
 *
 * @param elapsedMillis 由执行阶段③统一计时（handler 不必自算），排版 `耗时：<x.x>s` 用它。
 * @param attachments 产物回传成功清单；格式 `<文件名> <- <原路径>`，
 *        由 withArtifactsDelivered 在 SUCCESS && agent.artifacts.enabled 回合写入，随 SinkRecord.Audit 透传审计。
 *        字段本身不触发上传（上传在 handler 接线内完成）；旧格式审计行无此键 decode 为 null。
 * @param rejectedArtifacts 产物回传被拒清单：含路径与拒绝原因（超限/沙箱/大小/扩展名/上传失败），
 *        由 withArtifactsDelivered 写入并随审计落盘——失败取证不再只能翻飞书聊天记录。
 */
data class HandlerOutcome(
    val status: TaskStatus,
    val kind: TaskKind,
    val summary: String,
    val detail: String = "",
    val errorCode: String? = null,
    val exitCode: Int? = null,
    val elapsedMillis: Long = 0L,
    val card: CardSpec? = null,
    val attachments: List<String> = emptyList(),
    val rejectedArtifacts: List<String> = emptyList(),
    val directive: ReplyDirective = ReplyDirective.DEFAULT,
    val backend: String? = null,
) {
    /** 是否计入完成/失败计数器（SKIPPED 不计入，旧语义平移）。 */
    val countsForMetrics: Boolean get() = status != TaskStatus.SKIPPED

    companion object {
        fun success(
            kind: TaskKind,
            summary: String,
            detail: String = "",
            elapsedMillis: Long = 0L,
            card: CardSpec? = null,
            backend: String? = null,
            attachments: List<String> = emptyList(),
            rejectedArtifacts: List<String> = emptyList(),
        ) = HandlerOutcome(
            status = TaskStatus.SUCCESS, kind = kind, summary = summary, detail = detail,
            elapsedMillis = elapsedMillis, card = card, backend = backend, attachments = attachments,
            rejectedArtifacts = rejectedArtifacts,
        )

        fun failure(
            kind: TaskKind,
            summary: String,
            errorCode: String,
            detail: String = "",
            exitCode: Int? = null,
            elapsedMillis: Long = 0L,
            backend: String? = null,
        ) = HandlerOutcome(
            status = TaskStatus.FAILURE, kind = kind, summary = summary, detail = detail,
            errorCode = errorCode, exitCode = exitCode, elapsedMillis = elapsedMillis, backend = backend,
        )

        /**
         * 跳过（不计入 metrics）。@param silent true ⇒ Reply 阶段完全静默（未授权 + 空模板场景）。
         */
        fun skipped(
            kind: TaskKind = TaskKind.COMMAND,
            summary: String = "",
            errorCode: String? = null,
            silent: Boolean = false,
        ) = HandlerOutcome(
            status = TaskStatus.SKIPPED, kind = kind, summary = summary, errorCode = errorCode,
            directive = if (silent) ReplyDirective.SILENT else ReplyDirective.DEFAULT,
        )

        /** 排队等待超时：🔒 `errorCode=="task_queue_timeout"`。 */
        fun queueTimeout(waitSeconds: Long, kind: TaskKind = TaskKind.AGENT) = failure(
            kind = kind,
            summary = "当前任务较多，已在队列等待超 ${waitSeconds}s，请稍后重试或发送「停止」",
            errorCode = TaskErrorCodes.TASK_QUEUE_TIMEOUT,
        )

        /** 同 chat 在途超限：🔒 `errorCode=="agent_busy"`。 */
        fun chatBusy(limit: Int, kind: TaskKind = TaskKind.AGENT) = failure(
            kind = kind,
            summary = "当前会话已有 $limit 个任务在途，请等待完成或发送「停止」",
            errorCode = TaskErrorCodes.AGENT_BUSY,
        )

        /**
         * 总超时：🔒 `errorCode=="task_timeout"`、summary 含 `任务超时（>1s）` 形态。
         * [seconds] 用「有效超时秒数」，`timeoutSeconds=1` 的 handler 覆盖全局 60 时即渲染 `>1s`。
         */
        fun totalTimeout(seconds: Long, kind: TaskKind = TaskKind.AGENT) = failure(
            kind = kind,
            summary = "任务超时（>${seconds}s）",
            errorCode = TaskErrorCodes.TASK_TIMEOUT,
        )

        /**
         * 空闲看门狗触发（专治 agent 卡死要等满总超时的问题）。
         * 与总超时**可区分**：summary 写「无输出」，errorCode 仍是 `agent_timeout`（behavior-contract.md §4.13 登记值）。
         */
        fun idleTimeout(seconds: Long, kind: TaskKind = TaskKind.AGENT) = failure(
            kind = kind,
            summary = "任务空闲超时（>${seconds}s 无输出）",
            errorCode = TaskErrorCodes.AGENT_TIMEOUT_ALIAS,
        )

        /** handler 抛未预期异常（「执行器异常不中断服务且回复失败」）。 */
        fun error(kind: TaskKind, message: String?, elapsedMillis: Long = 0L) = failure(
            kind = kind,
            summary = "任务执行失败：${message ?: "未知错误"}",
            errorCode = TaskErrorCodes.TASK_ERROR,
            elapsedMillis = elapsedMillis,
        )
    }
}
