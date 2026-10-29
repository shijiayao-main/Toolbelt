package com.jiaoay.easy_dev_toolbox.widget.chat

import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * 工具可视化器注册表（OpenHands tool-visualizers 配方）：
 *
 * Map 查表 + **纯文本兜底**——未注册的工具走 [ToolCallCard] 默认渲染，注册了的
 * 用专属可视化（未来 read_file 分页摘要卡 / grep 命中列表卡 / hex_dump 表格卡）。
 * 「一次加一个工具」：新工具接入零侵入，注册表后空不破坏任何现有渲染。
 */
object ToolVisualizerRegistry {

    private val map = mutableMapOf<String, ToolVisualizer>()

    /** 工具专属可视化器（action/observation 双阶段 props——Body 自决显示什么） */
    @Immutable
    fun interface ToolVisualizer {
        @Composable
        fun Body(entry: ChatEntry.ToolCall)
    }

    fun register(toolName: String, visualizer: ToolVisualizer) {
        map[toolName] = visualizer
    }

    fun unregister(toolName: String) {
        map.remove(toolName)
    }

    fun visualizerOrNull(toolName: String): ToolVisualizer? = map[toolName]

    /** 测试/进程退出清空 */
    fun clearForTest() {
        map.clear()
    }
}
