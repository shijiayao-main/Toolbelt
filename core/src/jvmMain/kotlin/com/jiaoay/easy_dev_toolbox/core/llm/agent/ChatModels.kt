package com.jiaoay.easy_dev_toolbox.core.llm.agent

import java.util.concurrent.atomic.AtomicLong

/**
 * 消息流条目模型（参照 OpenHands Action→Observation 消息流）：
 *
 * - **稳定 id**：LazyColumn key 用（deepagents 消息稳定 ID 规约的前半）；
 * - [ToolCall] 是**同一张卡的状态迁移**而非多条消息（OpenHands 原位替换模式）：
 *   RUNNING → COMPLETED/FAILED 时 [observation] 回填，不增行；
 * - 无 Koog 依赖（:ui 模块依赖面只有 :core/symbols/coil）——Koog 事件到 ChatEntry
 *   的映射在消费方（feature-ai 会话 reducer；P4 后为工作台 reducer）。
 */
sealed interface ChatEntry {
    val id: String

    /** 用户消息（附件 = 拖入文件名列表，渲染为 chips） */
    data class User(
        override val id: String,
        val text: String,
        val attachments: List<String> = emptyList(),
    ) : ChatEntry

    /** 助手消息（streaming = 打字机追加态：由消费方节流合并 delta） */
    data class Assistant(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
    ) : ChatEntry

    /** 工具调用卡：RUNNING → COMPLETED/FAILED/CANCELLED 原位状态迁移 */
    data class ToolCall(
        override val id: String,
        val toolCallId: String?,
        val toolName: String,
        val argsSummary: String,
        val state: ToolState,
        val observation: String? = null,
        val artifacts: List<ArtifactChip> = emptyList(),
    ) : ChatEntry

    /** 活动指示（deriveLiveActivity 模式：从事件流推导的当前活动文案） */
    data class Activity(
        override val id: String,
        val text: String,
        val running: Boolean,
    ) : ChatEntry

    /** 流内错误条目（TIMEOUT/FAILED/CANCELLED 三分） */
    data class ErrorBanner(
        override val id: String,
        val kind: ErrorKind,
        val title: String,
        val detail: String? = null,
    ) : ChatEntry

    /** 计划板快照（write_todos → TodoBoard → 会话派生快照；deepagents todos state 投影） */
    data class TodoBoardSnapshot(
        override val id: String,
        val items: List<TodoChip>,
    ) : ChatEntry

    /** id 计数器（ChatEntry 无业务语义 id 时用——内容 id 由消费方派生） */
    companion object {
        private val counter = AtomicLong(0)

        fun nextId(): String = "chat-${counter.incrementAndGet()}"
    }
}

/** 工具调用状态（E1 中文化 displayText——渲染面禁再内插枚举英文名） */
enum class ToolState {
    RUNNING, COMPLETED, FAILED, CANCELLED;

    /** 中文显示名（降级渲染行/审计文案单源） */
    fun displayText(): String = when (this) {
        RUNNING -> "运行中"
        COMPLETED -> "已完成"
        FAILED -> "失败"
        CANCELLED -> "已取消"
    }
}

enum class ErrorKind { TIMEOUT, FAILED, CANCELLED }

/** 产物 chip：精确路径（结构化 ToolResult 的 UI 投影——消灭兄弟文件启发式） */
data class ArtifactChip(
    val path: String,
    val kind: ArtifactChipKind,
)

enum class ArtifactChipKind { FILE, DIRECTORY, EXTRACTED, REPORT }

/** todo chip：write_todos 状态的三枚投影（OpenHands TaskItem 同款语义） */
data class TodoChip(
    val content: String,
    val status: TodoChipStatus,
)

enum class TodoChipStatus { PENDING, IN_PROGRESS, DONE }
