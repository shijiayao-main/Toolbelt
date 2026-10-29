package com.jiaoay.easy_dev_toolbox.page.quest

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import com.jiaoay.easy_dev_toolbox.core.data.db.SqliteChatHistoryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 流程会话历史表（Quest 域 change 2——openspec add-flow-page 任务 1.2）。
 *
 * ## 定位（桥接 SessionHistoryProvider 纪律平移——非代码平移）
 * Koog ChatMemory 的自定义 [ChatHistoryProvider]：Koog 官方两个坑在桥接链已修，
 * 本 provider 逐字平移其纪律：
 * 1. **store 滤 System 消息**——ChatMemory.store 存整 prompt（含 system），load
 *    非空历史整体替换会重复注入 system（KoogAgentRunner 已修坑的纪律）；
 * 2. **窗口封顶 takeLast([WINDOW_SIZE])**——官方 InMemoryChatHistoryProvider 无上限，
 *    长流程历史无界膨胀会吃爆上下文；桥接用 LRU（多会话热路径），桌面流程域单活跃
 *    会话——直接截断窗口即可，无需淘汰算法。
 *
 * ## 与桥接的差异（有意）
 * - 无内存 LRU：桌面单活跃流程，store/load 直通持久层（[SqliteChatHistoryProvider]
 *   ——:core 现成件，JDBC 共享同一 db 文件）；
 * - load 侧同样窗口截断：续跑只带最近窗口，更早历史靠 messages 表回看（UI 面）。
 *
 * 🔴 续跑语义锚：conversationId = flowId（flows.id = messages.session_id =
 * chat_history.conversation_id 三层同 key——QuestStore.delete 级联清理；任一层
 * 改用别 key 即级联漏删，历史/消息行成孤儿残留）。
 */
class QuestHistoryProvider(
    private val persistStore: SqliteChatHistoryProvider,
) : ChatHistoryProvider {

    override suspend fun store(conversationId: String, messages: List<Message>) = withContext(Dispatchers.IO) {
        // 滤 System：load 整体替换路径会带上旧 system——system prompt 由骨架每轮重新注入
        val filtered = messages.filterNot { it is Message.System }
        persistStore.store(conversationId, filtered.takeLast(WINDOW_SIZE))
    }

    override suspend fun load(conversationId: String): List<Message> = withContext(Dispatchers.IO) {
        persistStore.load(conversationId).takeLast(WINDOW_SIZE)
    }

    companion object {
        /** 历史窗口（条数封顶≈40——交互轮上下文体量；超过即截断靠 messages 表回看） */
        const val WINDOW_SIZE: Int = 40

        /** 生产装配（宿主注入 db 路径——与 SqliteSessionStore/QuestStore 同一 db 文件）。 */
        fun forFile(dbPath: String): QuestHistoryProvider =
            QuestHistoryProvider(SqliteChatHistoryProvider.forFile(dbPath))
    }
}
