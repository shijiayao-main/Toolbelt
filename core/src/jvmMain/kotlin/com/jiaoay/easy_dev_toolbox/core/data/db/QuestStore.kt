package com.jiaoay.easy_dev_toolbox.core.data.db

import java.sql.DriverManager
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 流程域 flow 仓储门面（Quest 域 change 1——openspec add-flow-ask-user-and-flows-schema 任务 2.2）。
 *
 * ## 定位
 * flow 对象（一段可续 agent 对话的元数据）的单一读写面：flows 表 CRUD + JSON 列编解码
 * 收口。配方与 [RunStore] 同构（P4-C2 管理面模式：listFlows 倒序 + rename/delete 照
 * SqliteSessionStore 管理面先例）。
 *
 * ## 三层同 key
 * flows.id = messages.session_id = chat_history.conversation_id——「历史流程继续上下文」
 * 的锚：[delete] 级联清理三店；续跑经 chat_history 的 ChatMemory 挂接（页面批消费）。
 *
 * ## 级联删除语义
 * flows + messages + sessions 在同一 SQLDelight 事务内删除（🔴 拆单语句——多语句
 * 命名查询只执行首条）；chat_history 行经共享 JDBC 连接后清（[deleteFlowHistory]，
 * 失败不回滚 SQLDelight 面——宽松语义，孤儿 blob 无害惰性数据）。
 *
 * @param chatHistoryDbPath chat_history 所在库文件路径（null = 不级联清 chat_history
 *        ——单测/无 ChatMemory 场景；生产传 [Database] 同一 db 文件）
 */
class QuestStore(
    private val db: ToolbeltDb,
    private val chatHistoryDbPath: String? = null,
) {

    private val json = Json
    private val stringList = ListSerializer(String.serializer())

    /** flow 元数据行模型（UI 消费面——capabilities 已解码为 id 列表）。 */
    data class FlowSummary(
        val id: String,
        val title: String,
        val capabilities: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
    )

    /** 首条用户消息 → 流程标题（截 80 字——SqliteSessionStore.ensureSession 派生先例）。 */
    fun deriveTitle(firstUserMessage: String): String =
        firstUserMessage.trim().replace('\n', ' ').take(TITLE_MAX_CHARS).ifBlank { "新流程" }

    /** 新建/覆写 flow（同 id 幂等 upsert——insertFlow ON CONFLICT 对齐）。 */
    fun create(
        id: String,
        title: String,
        capabilities: List<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        db.flowsQueries.insertFlow(
            id = id,
            title = title,
            capabilities = encodeCapabilities(capabilities),
            created_at = now,
            updated_at = now,
        )
    }

    /** 全量 flow（updated_at 倒序——左栏历史列表消费面）。 */
    fun listFlows(): List<FlowSummary> =
        db.flowsQueries.allFlows().executeAsList().map { it.toSummary() }

    /** 单 flow 查询（历史进入入口；不存在返回 null）。 */
    fun flowById(id: String): FlowSummary? =
        db.flowsQueries.flowById(id).executeAsOneOrNull()?.toSummary()

    /** 活跃触达（续聊/新消息后刷新列表排序）。 */
    fun touch(id: String, now: Long = System.currentTimeMillis()) {
        db.flowsQueries.touchFlow(updatedAt = now, id = id)
    }

    /** 管理面重命名。 */
    fun rename(id: String, title: String, now: Long = System.currentTimeMillis()) {
        db.flowsQueries.renameFlow(title = title, updatedAt = now, id = id)
    }

    /**
     * 删除流程（三店级联）：事务内先删时间线 messages、再删 sessions 行、最后删 flows
     * 行（外键方向与 [RunStore.delete] 同款——🔴 拆单语句：多语句命名查询只执行首条）。
     * chat_history 行随后经 JDBC 清（失败仅记录不回滚）。
     */
    fun delete(id: String) {
        db.transaction {
            db.flowsQueries.deleteFlowMessages(id)
            db.flowsQueries.deleteFlowSessions(id)
            db.flowsQueries.deleteFlow(id)
        }
        deleteFlowHistory(id)
    }

    /**
     * chat_history 行清理（经 [Database.chatHistoryConnection] 共享连接
     * ——旧每调用新开连接；🔴 共享连接不 .use{} 关闭：连接生命周期归 Database 进程池，
     *    use{} 关掉即断池，后续读写全部出错）。
     */
    private fun deleteFlowHistory(id: String) {
        val path = chatHistoryDbPath ?: return
        runCatching {
            val conn = Database.chatHistoryConnection(path)
            conn.createStatement().use { st ->
                st.execute("DELETE FROM chat_history WHERE conversation_id = '$id'")
            }
        } // 失败静默（宽松语义——孤儿 blob 无害；桥接 SessionHistoryProvider.deleteHistory 同款）
    }

    // ── 编解码 ──

    private fun encodeCapabilities(ids: List<String>): String =
        json.encodeToString(stringList, ids)

    private fun decodeCapabilities(raw: String?): List<String> =
        runCatching { json.decodeFromString(stringList, raw ?: "[]") }.getOrDefault(emptyList())

    private fun Flows.toSummary() = FlowSummary(
        id = id,
        title = title,
        capabilities = decodeCapabilities(capabilities),
        createdAt = created_at,
        updatedAt = updated_at,
    )

    private companion object {
        const val TITLE_MAX_CHARS = 80
    }
}
