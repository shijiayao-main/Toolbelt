package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes

/**
 * 任务级错误码 → 「人话 + 修复建议」翻译表。
 *
 * ## 为什么在 host 侧而不在 lib 侧
 * lib（`:lark-bridge`，jvmTarget 11）的回复文本面向**飞书端用户**（手机上看），保持简短错误码 +
 * 结果抬头即可；完整的「哪一步坏了、去哪改」诊断是**桌面端运维信息**，归宿主（jvmTarget 25）。
 * 与 [BridgeErrorMapping]（连接层 BridgeErrorCode → JErrorCode）同理分居两侧——本文件管**任务级**
 * [TaskErrorCodes]，两套体系互不重叠。
 *
 * ## 真实数据驱动的翻译重点
 * 动机：同一 prompt 连败 8 次（`agent_timeout` × 7）无任何 UI 解释——
 * COMMAND 路由委托 agent 时吃 `runtime.taskTimeoutSeconds=60s` 档，而 agent 实际跑 27~79s。
 * 超时类/工作区缺失类是最高频失败，文案给到「调哪个字段」的可执行建议。
 *
 * `when` 处理 `String`（含 `agent_exit_` 前缀形态）故**必须保留兜底**——未知码只降级为原始码
 * 展示，不崩、不吞。零 Compose、零 IO。
 */
object TaskErrorHint {

    /** 一条失败诊断：[plain] 人话解释、[fix] 修复建议、[fieldId] 指向的配置字段（可空 = 无直接落点）。 */
    data class Hint(val plain: String, val fix: String, val fieldId: String? = null)

    /** 翻译一个任务错误码；未知码兜底为「原始码 + 看日志」（不崩、不吞）。
     *  [backend] 保留历史审计值的解码维度（旧记录含 CLI 后端 id）；现网新错误恒产自
     *  本地 AI 调度链，文案按调度链视角引导。 */
    fun of(errorCode: String?, backend: String? = null): Hint {
        if (errorCode.isNullOrBlank()) return Hint("未知错误（无错误码）", "查看「日志」页签的详细输出")
        return when {
            errorCode.startsWith(TaskErrorCodes.AGENT_EXIT_PREFIX) ->
                Hint(
                    plain = "任务执行进程异常退出（${errorCode.removePrefix(TaskErrorCodes.AGENT_EXIT_PREFIX).let { if (it.isNotBlank()) "退出码 $it" else "未知退出码" }}）",
                    fix = "到「日志」页签看任务的原始输出；常见原因是构建/测试命令失败（非零退出码是数据不是崩溃）",
                )

            errorCode == TaskErrorCodes.TASK_TIMEOUT || errorCode == TaskErrorCodes.AGENT_TIMEOUT_ALIAS ->
                Hint(
                    plain = "任务超时被终止（agent 还没跑完就到时间上限了）",
                    fix = "快捷指令类任务受「任务超时(秒)」限制，agent 类受「agent 超时(分钟)」限制；" +
                        "长时间任务请调大对应字段",
                    fieldId = "runtime.taskTimeoutSeconds",
                )

            errorCode == TaskErrorCodes.AGENT_WORKSPACE_MISSING ->
                Hint(
                    plain = "工作区目录不存在",
                    fix = "到「配置」页签 →「连接与准入」→ 工作区根目录重新选择一个存在的目录（改动需重启桥接生效）",
                    fieldId = "workspace.root",
                )

            errorCode == TaskErrorCodes.AGENT_START_FAILED ->
                Hint(
                    plain = "agent 进程没有启动起来（系统拒绝了命令行参数）",
                    fix = "常见于 Windows 上 prompt 含特殊字符（引号/百分号）触发系统校验；" +
                        "把 prompt 里的引号去掉或改写后再试；仍失败请到「日志」页签看详细原因",
                )

            errorCode == TaskErrorCodes.AGENT_MAX_ITERATIONS ->
                Hint(
                    plain = "任务太复杂，agent 步数预算用完了（还没得出结论就被迭代上限截停）",
                    fix = "把大任务拆成几次小提问（比如先解密、再按时间点过滤、再分析）；" +
                        "或到「AI 设置」确认模型端点稳定后重试；复杂任务也可在提问里给出更明确的方向减少 agent 试错",
                )

            errorCode == TaskErrorCodes.AGENT_BACKEND_UNAVAILABLE ->
                Hint(
                    plain = if (backend == KOOG_BACKEND) {
                        "本地 AI 后端不可用（端点未配置或配置不完整）"
                    } else {
                        "agent 后端不可用（可执行文件未找到）"
                    },
                    fix = if (backend == KOOG_BACKEND) {
                        "到「AI 设置」页配置 LLM 端点（Base URL / API Key / 模型 ID），本地 AI 调度依赖该端点"
                    } else {
                        "本地 AI 端点未就绪——到「AI 设置」页配置模型端点后重试"
                    },
                )

            // 该码双语义——Koog 调度 agent 端点未配置
            // （App 形态死区引导：指 AI 设置页端点，而非误导用户去改 CLI 模板）；CLI 后端模板/
            // 档位配置错误（旧语义保留）。of(code, backend) 区分两态；无 backend 的旧调用
            // （audit 历史值）回落 CLI 文案。
            errorCode == TaskErrorCodes.AGENT_CONFIG_INVALID && backend == KOOG_BACKEND ->
                Hint(
                    plain = "本地 AI 未就绪（模型端点未配置或不可用）",
                    fix = "到「AI 设置」页配置模型端点（Base URL / API Key / 模型）——飞书桥接的智能回复依赖本地 AI",
                )

            errorCode == TaskErrorCodes.AGENT_CONFIG_INVALID ->
                Hint(
                    plain = "agent 配置不合法",
                    fix = "到「日志」页签看详细原因；AI 端点类问题到「AI 设置」页核对配置",
                )

            errorCode == TaskErrorCodes.AGENT_EMPTY_OUTPUT ->
                Hint(
                    plain = "任务没有产出任何内容",
                    fix = "到「日志」页签看原始输出；可能是任务本身无需输出或模型响应异常",
                )

            errorCode == TaskErrorCodes.AGENT_BUSY ->
                Hint(
                    plain = "同会话排队已达上限，任务被拒",
                    fix = "等在途任务完成，或调大「运行时」分组的并发与队列上限",
                    fieldId = "runtime.chatQueueLimit",
                )

            errorCode == TaskErrorCodes.TASK_QUEUE_TIMEOUT ->
                Hint(
                    plain = "排队等待超时",
                    fix = "当前并发满载；可调大「最大并发」或「排队等待(秒)」",
                    fieldId = "runtime.queueWaitSeconds",
                )

            errorCode == TaskErrorCodes.COMMAND_AGENT_DISABLED ->
                Hint(
                    plain = "快捷指令的执行后端不可用",
                    fix = "本地 AI 端点未就绪——到「AI 设置」页配置模型端点后重试",
                )

            errorCode == TaskErrorCodes.COMMAND_TEMPLATE_EMPTY ->
                Hint(
                    plain = "快捷指令的 Prompt 模板为空",
                    fix = "快捷指令模板已退役——该指令由内置指令集接管，请检查指令名",
                )

            errorCode == TaskErrorCodes.COMMAND_CONFIRM_UNAVAILABLE ->
                Hint(
                    plain = "指令要求执行前确认，但确认链路不可用",
                    fix = "检查「Agent 与后端」的确认设置，或关闭该指令的「需确认」开关",
                    fieldId = "agent.confirm.enabled",
                )

            errorCode == TaskErrorCodes.FILE_DOWNLOAD_FAILED ->
                Hint(
                    plain = "文件下载失败",
                    fix = "检查飞书后台是否已授予 im:resource 权限；文件超过「文件大小上限」会拒绝下载（该值可在下方调大，最大 4GB）",
                    fieldId = "fileMessage.maxFileSizeBytes",
                )

            // 时长硬上限是常量而非配置字段（AudioHandler.MAX_DURATION_MILLIS），无 fieldId 落点。
            errorCode == TaskErrorCodes.AUDIO_DURATION_EXCEEDED ->
                Hint(
                    plain = "语音超过 10 分钟硬上限，下载前已拒收",
                    fix = "请剪短或分段后重发",
                )

            // 模型下载态在「AI 设置」页而非桥接配置字段，无 fieldId 落点。
            errorCode == TaskErrorCodes.AUDIO_TRANSCRIBE_FAILED ->
                Hint(
                    plain = "语音转写失败（解码或推理异常）",
                    fix = "到「AI 设置」确认转写模型已下载可用，到「日志」页签看详细原因",
                )

            // 零生产者码（全仓 grep 亲验：除定义与 TaskErrorCodes.ALL 外零出现；字面值留在 ALL 供存量
            // audit.jsonl 按字面分桶，不删不改名）：FILE_AGENT_DISABLED / COMMAND_NOT_FOUND /
            // FILE_EXTENSION_REJECTED 三码的翻译分支随 agent 硬依赖化（「执行器未启用」语义不复存在）/
            // 指令发现内置化 / allowedExtensions 退役一并下葬；FILE_WORKSPACE_MISSING 从未接线——引用流
            // 与 agent 管线合流后工作区缺失统一落 AGENT_WORKSPACE_MISSING。存量 audit 若真出现这些字面值
            // 会落下方 else 兜底分支（「该错误码暂无翻译」）。TaskErrorHintTest 的结构性守卫以本注释
            // 所列码为零生产者白名单——新增码进白名单前须 grep 亲验零生产者并在此登记理由。

            else -> Hint(
                plain = "任务失败（$errorCode）",
                fix = "到「日志」页签看详细输出；该错误码暂无翻译，可反馈给开发者",
            )
        }
    }

    /** 本地 AI 后端标识（桥接侧 `AgentHandler` 的 Koog 判定读同一字面值）。 */
    private const val KOOG_BACKEND = "koog"
}
