package com.jiaoay.easy_dev_toolbox.core.data.db

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager

/**
 * Koog ChatHistoryProvider 的 SQLite 实现。
 *
 * ## 为什么不用官方 Exposed 线
 * Koog 官方 chat-memory-sql 的 SQLChatHistoryProvider 基类绑定 Exposed ORM + 独立 DataSource
 * 工件（被排除在 koog-agents 聚合 jar 外），且无 SQLite 具体 provider——为保持「零新增 ORM
 * 依赖」哲学（DB 选型报告淘汰 Exposed 的裁定），本类直接实现 [ChatHistoryProvider] 接口
 * （store/load 两方法），schema 照搬官方 H2 版（conversation_id 主键 + messages_json blob +
 * updated_at + ttl_timestamp），经共享 JDBC 连接落 [Database] 库文件。
 *
 * ## 存储形态（官方语义保留）
 * - **每会话一行**（messages_json 整体替换）——这是 Koog ChatMemory 的挂接语义
 *   （interceptStrategyCompleted 时整包 store）；可检索的行式消息在 ToolbeltDb
 *   messages 表（两者面向不同消费方：Koog 多轮挂接 vs 会话管理面/FTS 检索）；
 * - TTL 按 updated_at 滚动（活跃会话不过期——官方设计决策）；
 * - compact JSON（官方 defaultChatHistoryJson 同配置）。
 *
 * ## 线程模型
 * store/load 经 [Dispatchers.IO]；JDBC 连接由调用方注入（与 SQLDelight 驱动共享同一
 * db 文件、各自连接——SQLite WAL 下读写不互斥）。桥接域的消费（KoogAgentRunner 的
 * ChatMemory.Feature）传 [forConnection] 形态；单测传 [Connection] 直测。
 */
class SqliteChatHistoryProvider(
    private val connection: Connection,
    private val tableName: String = "chat_history",
    private val ttlSeconds: Long? = null,
) : ChatHistoryProvider {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        migrate()
    }

    /** 建表（幂等；照搬官方 H2ChatHistorySchemaMigrator 的 DDL，SQLite 方言） */
    fun migrate() {
        connection.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS $tableName (
                    conversation_id TEXT NOT NULL,
                    messages_json TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    ttl_timestamp INTEGER NULL,
                    PRIMARY KEY (conversation_id)
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_${tableName}_updated_at ON $tableName (updated_at)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_${tableName}_ttl ON $tableName (ttl_timestamp)")
        }
    }

    override suspend fun store(conversationId: String, messages: List<Message>): Unit = withContext(Dispatchers.IO) {
        val messagesJson = json.encodeToString(ListSerializer(Message.serializer()), messages)
        val now = System.currentTimeMillis()
        val ttl = ttlSeconds?.let { now + it * 1000 }
        connection.prepareStatement(
            """
            INSERT INTO $tableName (conversation_id, messages_json, updated_at, ttl_timestamp)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(conversation_id) DO UPDATE SET
                messages_json = excluded.messages_json,
                updated_at = excluded.updated_at,
                ttl_timestamp = excluded.ttl_timestamp
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.setString(2, messagesJson)
            ps.setLong(3, now)
            ttl?.let { ps.setLong(4, it) } ?: ps.setNull(4, java.sql.Types.BIGINT)
            ps.executeUpdate()
        }
        cleanupExpired()
    }

    override suspend fun load(conversationId: String): List<Message> = withContext(Dispatchers.IO) {
        connection.prepareStatement(
            "SELECT messages_json FROM $tableName WHERE conversation_id = ? AND (ttl_timestamp IS NULL OR ttl_timestamp > ?)",
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.setLong(2, System.currentTimeMillis())
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@withContext emptyList()
                val text = rs.getString(1) ?: return@withContext emptyList()
                runCatching {
                    json.decodeFromString(ListSerializer(Message.serializer()), text)
                }.getOrDefault(emptyList())
            }
        }
    }

    /** 过期清理（store 时顺带跑——官方 cleanupExpired 语义；TTL 未配置时零成本） */
    suspend fun cleanupExpired(): Unit = withContext(Dispatchers.IO) {
        if (ttlSeconds == null) return@withContext
        connection.prepareStatement("DELETE FROM $tableName WHERE ttl_timestamp IS NOT NULL AND ttl_timestamp <= ?")
            .use { ps ->
                ps.setLong(1, System.currentTimeMillis())
                ps.executeUpdate()
            }
    }

    /** 会话数（诊断/管理面用） */
    suspend fun conversationCount(): Long = withContext(Dispatchers.IO) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $tableName").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    /** 删除单会话（会话管理面） */
    suspend fun deleteHistory(conversationId: String): Unit = withContext(Dispatchers.IO) {
        connection.prepareStatement("DELETE FROM $tableName WHERE conversation_id = ?").use { ps ->
            ps.setString(1, conversationId)
            ps.executeUpdate()
        }
    }

    companion object {
        /**
         * 共享 JDBC 连接形态。
         *
         * 旧形态每次调用 DriverManager.getConnection 新开长连接且永不关——
         * FlowViewModel 每次 VM 重建即泄漏一条。收敛为经 [Database.chatHistoryConnection]
         * 进程单条共享（同 db 文件、WAL 并发安全；调用方签名零改动）。
         * 传独立路径（测试/异库）仍走 DriverManager 独立连接。
         */
        fun forFile(dbPath: String, ttlSeconds: Long? = null): SqliteChatHistoryProvider =
            SqliteChatHistoryProvider(
                connection = Database.chatHistoryConnection(dbPath),
                ttlSeconds = ttlSeconds,
            )
    }
}
