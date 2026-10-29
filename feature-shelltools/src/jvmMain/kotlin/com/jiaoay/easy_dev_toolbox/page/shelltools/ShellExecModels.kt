package com.jiaoay.easy_dev_toolbox.page.shelltools

import androidx.compose.runtime.Immutable

/**
 * 执行状态：由 ShellExecutionEngine 驱动，驱动底部输出面板头部指示
 */
sealed interface ExecStatus {
    data object Idle : ExecStatus

    data class Running(
        val label: String,
        val stepIndex: Int,
        val totalSteps: Int,
        val currentCommand: String,
    ) : ExecStatus

    data class Failed(
        val label: String,
        val commands: List<String>,
        val failedStepIndex: Int,
        /** 失败步命令（头部「执行失败: cmd（exit N）」形态的命令段） */
        val failedCommand: String = "",
        /** 失败步 exit 码（进程启动失败 = null，头部退「（启动失败）」） */
        val exitCode: Int? = null,
        /**
         * 重写前原串（空 = 兼容回退用 [commands]）：重试必须按【当前】adb 路径/串号
         * 经 VM 重写层重写——[commands] 是已重写产物，重写器对它 no-op，复用会把
         * 旧串号/旧路径永久烘进重试
         */
        val originalCommands: List<String> = emptyList(),
        /** 原任务超时口径（重试保真；null = 不限时） */
        val timeoutMs: Long? = null,
        /** 原任务追踪键（重试保真——节点 loading 态锚回同一节点） */
        val trackingKey: String? = null,
    ) : ExecStatus

    data object Completed : ExecStatus
}

/**
 * 执行输出条目类型
 */
enum class ExecOutputType {
    Command,
    Stdout,
    Stderr,
    StepMarker,
    Status,
}

/**
 * 执行输出条目（全 val 不可变，Compose 渲染稳定性标注）
 *
 * @param seq 会话内单调递增行号（引擎内 AtomicLong 递增赋值），作为 LazyColumn 稳定 key：
 *   不因新增输出或滚动窗口截断而变化，clearOutput 后也不重置（跨清空无冲突）
 */
@Immutable
data class ExecOutputItem(
    val type: ExecOutputType,
    val content: String,
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val seq: Long = 0,
)

/**
 * 输出面板状态快照：一次携带 UI 所需全部信息。
 *
 * @param items 当前滚动窗口内的全部条目（每聚合窗口一次 toList() 快照，非每行一次）
 * @param droppedCount 累计丢弃条数（超过 MAX_OUTPUT_ITEMS 截断的最旧行数；清空时归零）
 */
data class OutputState(
    val items: List<ExecOutputItem> = emptyList(),
    val droppedCount: Int = 0,
)
