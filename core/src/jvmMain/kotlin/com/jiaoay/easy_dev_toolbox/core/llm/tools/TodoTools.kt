package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * 任务级规划板（参照 deepagents 的 todos state 投影模式）：
 *
 * **计划放 state 不放消息流**——agent 经 `write_todos` 工具更新计划，UI 从 [TodoBoard.items]
 * StateFlow 直接渲染（不从消息流猜）；任务级实例，随会话销毁。
 *
 * Plan Hygiene 纪律（deepagents 同款，写进各 agent 的 system prompt）：
 * 「任务结束前必须 reconcile 每个 TODO：标 done / blocked（附一句话原因）/ cancelled——
 * 不许带着 pending 收尾」。
 */
class TodoBoard {
    private val _items = MutableStateFlow<List<TodoItem>>(emptyList())
    val items: StateFlow<List<TodoItem>> = _items.asStateFlow()

    /** 全量覆盖语义（deepagents write_todos 同款——每次调用提交完整清单） */
    fun replaceAll(items: List<TodoItem>) {
        _items.value = items.toList()
    }

    fun clear() {
        _items.value = emptyList()
    }
}

/** 单条待办：内容 + 状态（三枚，OpenHands 同款语义） */
@Serializable
data class TodoItem(
    val content: String,
    val status: TodoStatus = TodoStatus.PENDING,
)

@Serializable
enum class TodoStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
}

/**
 * write_todos 工具：agent 的规划写入口（全量覆盖）。
 *
 * 桥接形态不注册（飞书无 todo 投影面）——经 [DecryptTools.buildToolRegistry] 的
 * `todoBoard` 参数门控：null = 不注册。
 */
class WriteTodosTool(
    private val board: TodoBoard,
    sink: ToolResultSink = ToolResultSink.NOOP,
) : StructuredSimpleTool<WriteTodosTool.Args>(
    sink = sink,
    argsType = typeToken<Args>(),
    name = "write_todos",
    description = "Write your task plan as a todo list (full replacement each call). " +
        "Statuses: PENDING / IN_PROGRESS / DONE. Before finishing, reconcile every todo " +
        "(mark done or explain why blocked) — never leave items pending at the end.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Complete todo list (replaces the previous one)")
        val todos: List<TodoItem>,
    )

    override suspend fun executeTyped(args: Args): ToolResult {
        if (args.todos.isEmpty()) {
            return ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "todos 为空——要清空计划请勿调用本工具")
        }
        args.todos.firstOrNull { it.content.isBlank() }?.let {
            return ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "存在空 content 的 todo 项")
        }
        board.replaceAll(args.todos)
        val done = args.todos.count { it.status == TodoStatus.DONE }
        val inProgress = args.todos.count { it.status == TodoStatus.IN_PROGRESS }
        val pending = args.todos.count { it.status == TodoStatus.PENDING }
        return ToolResult.Ok(
            summary = "计划已更新：共 ${args.todos.size} 项（完成 $done / 进行中 $inProgress / 待办 $pending）",
        )
    }
}
