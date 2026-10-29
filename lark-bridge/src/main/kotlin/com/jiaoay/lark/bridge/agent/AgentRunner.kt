// agent 子进程执行器 seam 接口与值类型。实现见 NoAgentRunner / KoogAgentRunner（宿主侧注入）。
// 实现方必须遵守接口 KDoc 里的接线约定（否则超时/取消/计数语义断裂）。
package com.jiaoay.lark.bridge.agent

import com.jiaoay.lark.bridge.exec.AgentLine
import java.io.File

/**
 * agent 子进程执行器 seam。
 *
 * 🔴 接线约定（实现必须遵守，否则超时/取消/计数语义断裂）：
 * 1. **必须经 [com.jiaoay.lark.bridge.exec.ProgramLauncher] 起进程**，🔴 不得自建 `ProcessBuilder`
 *    （守护测试钉死：`ProcessBuilder` 只允许出现在 `exec/ProgramLauncher.kt`）。
 * 2. **必须把 `onStart` 回调拿到的 `Process` 交给 [com.jiaoay.lark.bridge.exec.ProcessRegistry.register]**
 *    （`ProgramLauncher` 已内建，实现只需传参），这样取消/超时才能杀到进程。
 * 3. **必须把 [AgentRequest.totalTimeoutMillis] / [AgentRequest.idleTimeoutMillis] 原样透传给
 *    `ProgramLauncher.launch`**——这两个值由上层 `TimeoutPolicy` 依 `runtime.*` 计算，实现不得自行改写。
 * 4. **不得在 [run] 内吞掉 [kotlinx.coroutines.CancellationException]**：`withTimeout` 与 URGENT 停止指令
 *    靠取消传播触发 `ProgramLauncher` 的三段式 kill；捕获后不重抛会导致进程泄漏。
 * 5. 逐行输出必须回调 [onLine]（ProgressSink 与空闲看门狗以「有新行」为活动信号）。
 * 6. 🔴 子进程与 MCP stdio 一律 `Charsets.UTF_8`（禁用 core/extensions 的 Shell 三件套）。
 */
interface AgentRunner {

    /** 当前后端标识（`claude` / `qoder` / `copilot` / `zcode` / `custom`；koog 为调度层标识不经此字段）；供审计 `backend` 字段与 UI 展示。 */
    val backendId: String

    /**
     * 后端是否可用（可执行文件在 PATH 上、配置完整）。
     * false 时 handler 应直接产出 `agent_backend_unavailable`（TaskErrorCodes 的字面值），不要起进程。
     */
    val isAvailable: Boolean

    /**
     * 跑一次 agent 任务。
     * @param onLine 每读到一行 stdout/stderr 回调一次（活动信号 + 进度累积）。🔴 实现侧不得在此做阻塞 IO。
     * @return 收尾结果（含超时/kill 事实）。失败以返回值表达，不抛业务异常（与网关错误映射同纪律）。
     */
    suspend fun run(request: AgentRequest, onLine: (AgentLine) -> Unit): AgentRunResult

    /**
     * 带会话上下文与进度累积器的执行入口（接口成员：任何 [AgentRunner] 实现——包括 feature 侧的
     * Koog 调度 agent——都能接上进度卡与确认卡链，无需窄化转型）。
     *
     * @param chatId 会话 ID（确认卡目标；`--mcp-config` 的 provider 据此渲染占位符）
     * @param senderOpenId 发送者 ID（确认卡防替答依据）
     * @param progress 进度累积器；非 null 时结构化事件与文本都喂进去（进度卡数据源）
     * @param onLine 原始行回调（旁路观测；进度喂入是 progress 参数的职责，二者不得重复）
     * @return 收尾结果。默认实现转调 [run]（无会话上下文/进度能力的后端零改动兼容）。
     */
    suspend fun run(
        request: AgentRequest,
        chatId: String,
        senderOpenId: String,
        progress: ProgressAccumulator?,
        onLine: (AgentLine) -> Unit,
    ): AgentRunResult = run(request, onLine)

    /**
     * 重置指定会话（用户发 `agent.resetCommand`，默认「新对话」）。
     * @return true 表示确实丢弃了一个存在的会话；false 表示无会话可丢。
     */
    suspend fun resetSession(sessionKey: String): Boolean

    /**
     * 「新对话」的详细回执（default null = 无详细回执，既有实现零破坏）。
     *
     * 契约：调用时机恒为 [resetSession] 之后、每次 reset 至多读取一次（实现可按
     * 「读取即消费」语义回收条目）；回执须如实披露实际执行面（如服务端会话销毁各步结果），
     * 无可披露面（纯内存表删除）恒 null——调用方以 null 维持既有回执文案逐字不变。
     */
    suspend fun resetReceipt(sessionKey: String): String? = null
}

/**
 * 一次 agent 调用的全部输入（纯数据，argv 渲染只读本对象，不再回查配置）。
 *
 * @param totalTimeoutMillis 总超时（上层 `TimeoutPolicy` 计算：agent 类 = `runtime.agentTimeoutMinutes`）。
 * @param idleTimeoutMillis 空闲看门狗（`runtime.agentIdleTimeoutSeconds`）——无输出超此值即 kill：
 *        只有总超时的话，agent 卡死要等满总超时才被回收。
 * @param extraEnv 追加环境变量（已由 `EnvAllowlist` 过滤后的白名单键；实现只加 agent 必需项）。
 */
data class AgentRequest(
    val sessionKey: String,
    val prompt: String,
    val totalTimeoutMillis: Long,
    val idleTimeoutMillis: Long,
    val resetSession: Boolean = false,
    val systemPrompt: String = "",
    /**
     * 机器人人设（output style 等价物）：调度层轻量回复的语气与风格。
     * **只被进程内调度后端（Koog）消费**——CLI argv 后端不读此字段，CLI 委托的新 request
     *    也不复制它（人设不污染重任务输出；与 [systemPrompt]「两端共用」的语义分界）。
     */
    val persona: String = "",
    /**
     * 不透明 per-message 载体（默认 null = 既有后端零感知）：调用方（handler）把本消息的
     * 出站能力面（[com.jiaoay.lark.bridge.pipeline.ReplySink] 等）塞进来，进程内后端（Koog 调度
     * agent）从工具闭包里读出使用——CLI argv 后端不读此字段（子进程无法序列化它）。
     * 类型上收窄为 `Any?` 而非 ReplySink：本文件不得 import pipeline 包（agent→pipeline 反向）。
     */
    val opaque: Any? = null,
)

/**
 * agent 收尾结果（errorCode 字面值与 `TaskErrorCodes` 对齐）。
 *
 * @param errorCode 非空时必须是错误码字面值集之一（`agent_timeout` / `agent_backend_unavailable` /
 *        `agent_empty_output` / `agent_workspace_missing` / `agent_config_invalid` / `agent_exit_<code>` / `task_error`）。
 *        🔴 一个字都不能改名——用户存量 `audit.jsonl` 已含这些值，UI 侧按字面分桶。
 */
data class AgentRunResult(
    val exitCode: Int,
    val summary: String,
    val detail: String = "",
    val stderrTail: String = "",
    val timedOut: Boolean = false,
    val idleTimedOut: Boolean = false,
    val killed: Boolean = false,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
    val errorCode: String? = null,
    val elapsedMillis: Long = 0L,
    val outputChars: Int = 0,
    val newSessionId: String? = null,
) {
    /** 成功判定：退出码 0 且非超时且 summary 非空。 */
    val isOk: Boolean get() = exitCode == 0 && !timedOut && !idleTimedOut && errorCode == null
}
