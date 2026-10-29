// 进度值类型组：ProgressEvent + TaskProgressListener + RunStatus + TaskProgress。
// 二者是同一组进度值类型，合并到一个文件避免跨包循环引用。
package com.jiaoay.lark.bridge.agent

/**
 * agent 执行进度事件（由 agent CLI stdout 行解析产出）。
 *
 * 事件来源：claude 后端 stream-json 模式的 assistant 块（thinking/text/tool_use）、
 * user 块（tool_result）、result。非 stream 后端无进度事件（降级为无进度卡片更新）。
 */
sealed interface ProgressEvent {
    /** 模型思考块（完整块，非 token 增量） */
    data class Thinking(val text: String) : ProgressEvent

    /** 工具调用（如 Bash: `ls -l | wc`） */
    data class ToolUse(val name: String, val argsSummary: String) : ProgressEvent

    /** 工具执行结果 */
    data class ToolResult(val name: String, val ok: Boolean, val summary: String) : ProgressEvent

    /** 模型文本输出块（完整块） */
    data class AssistantText(val text: String) : ProgressEvent
}

/**
 * 进度事件监听器：agent 子进程 stdout 排干协程（`pipe` 池）调用。
 *
 * 实现约定：
 * - MUST 非阻塞、线程安全（排干循环同步调用，阻塞会卡死管道排干进而卡死子进程）
 * - MUST NOT 抛异常（调用方不捕获）
 */
fun interface TaskProgressListener {
    fun onEvent(event: ProgressEvent)
}

/** 执行阶段（进度卡片终态判定） */
enum class RunStatus { RUNNING, DONE, FAILED, TIMEOUT }

/**
 * 不可变进度快照（供渲染与测试）。
 */
data class TaskProgress(
    val startedAtEpochMillis: Long,
    /** 滚动窗口事件（accumulation 侧已做截断与合并） */
    val events: List<ProgressEvent> = emptyList(),
    val status: RunStatus = RunStatus.RUNNING,
)
