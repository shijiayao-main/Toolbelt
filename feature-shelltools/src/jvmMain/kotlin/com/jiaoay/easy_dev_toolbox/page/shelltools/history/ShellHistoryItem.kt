package com.jiaoay.easy_dev_toolbox.page.shelltools.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shell 命令历史条目，按类型区分展示样式与回放逻辑。
 *
 * 多态序列化：@SerialName 值与历史版本 JSON 的 `type` 字段值一致，老数据直接兼容。
 */
@Serializable
sealed class ShellHistoryItem {

    /** 唯一标识，用于去重移位 */
    abstract val id: String

    /** 面板显示的标签（第一行） */
    abstract val displayLabel: String

    /** 累计执行次数（用于"最常使用"排序） */
    abstract val useCount: Int

    /** 最近一次执行的 epoch millis（用于排序 tie-break） */
    abstract val lastUsedAt: Long

    /**
     * 普通命令
     * @param id 命令字符串
     * @param displayLabel 节点名
     * @param command 实际命令
     */
    @Serializable
    @SerialName("Normal")
    data class Normal(
        override val id: String,
        override val displayLabel: String,
        val command: String,
        override val useCount: Int = 1,
        override val lastUsedAt: Long = 0L,
    ) : ShellHistoryItem()

    /**
     * 多选命令
     * @param id executingKey（格式 nodeId:itemName）
     * @param displayLabel 选项名
     * @param executingKey 执行键
     * @param nodeName 选项名
     * @param command 实际命令
     */
    @Serializable
    @SerialName("MultiSelect")
    data class MultiSelect(
        override val id: String,
        override val displayLabel: String,
        val executingKey: String,
        val nodeName: String,
        val command: String,
        override val useCount: Int = 1,
        override val lastUsedAt: Long = 0L,
    ) : ShellHistoryItem()

    /**
     * 输入命令（存储解析后的最终命令）
     * @param id 解析后命令
     * @param displayLabel 节点名
     * @param command 解析后命令
     */
    @Serializable
    @SerialName("Input")
    data class Input(
        override val id: String,
        override val displayLabel: String,
        val command: String,
        override val useCount: Int = 1,
        override val lastUsedAt: Long = 0L,
    ) : ShellHistoryItem()

    /**
     * 链式命令
     * @param id nodeId
     * @param displayLabel 节点名
     * @param commands 命令列表
     * @param nodeId 节点 ID
     */
    @Serializable
    @SerialName("Chain")
    data class Chain(
        override val id: String,
        override val displayLabel: String,
        val commands: List<String>,
        val nodeId: String,
        override val useCount: Int = 1,
        override val lastUsedAt: Long = 0L,
    ) : ShellHistoryItem()
}

/** 复制条目并更新计数（addHistory 合并语义用） */
fun ShellHistoryItem.withCount(useCount: Int, lastUsedAt: Long): ShellHistoryItem = when (this) {
    is ShellHistoryItem.Normal -> copy(useCount = useCount, lastUsedAt = lastUsedAt)
    is ShellHistoryItem.MultiSelect -> copy(useCount = useCount, lastUsedAt = lastUsedAt)
    is ShellHistoryItem.Input -> copy(useCount = useCount, lastUsedAt = lastUsedAt)
    is ShellHistoryItem.Chain -> copy(useCount = useCount, lastUsedAt = lastUsedAt)
}
