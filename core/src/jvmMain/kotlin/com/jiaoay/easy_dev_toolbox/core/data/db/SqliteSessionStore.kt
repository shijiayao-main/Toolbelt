package com.jiaoay.easy_dev_toolbox.core.data.db

import com.jiaoay.easy_dev_toolbox.core.data.db.ToolbeltDb
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChip
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChipKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ErrorKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * SQLite 会话存储：[SessionStore] 的生产实现。
 *
 * ## 定位
 * agent 会话持久化真源（首建于万能解密域，工作台批为其下一个消费者）——messages 行式存储（可检索 FTS5 /
 * 可管理面直查），与 [SessionLedger]（JSONL，保留作测试实现与历史参照）同契约。
 *
 * ## 语义映射（与 JSONL 账本逐字对齐）
 * - append 幂等 upsert：经 upsertMessage ON CONFLICT(session_id, seq)——工具卡
 *   RUNNING→终态回填是「同 id 覆写」路径；
 * - tombstone → sessions.markTombstone（墓碑行语义同 JSONL TOMBSTONE 行）；
 * - replay → messagesForSession 行序重建 ChatEntry（activity/snapshot 不落盘不重放）；
 * - latestSessionId → sessions.latestSession（updated_at 倒序第一行）；
 *
 * ## 会话自动登记
 * append 首条时 upsert 会话行（title = 首条 USER 截 80 字兜底 "新会话"）——管理面
 * 无需显式「创建会话」调用。FIFO 容量淘汰不做（SQLite 是全量面；上界管理属 UI 批）。
 */
class SqliteSessionStore(
    private val db: ToolbeltDb,
) : SessionStore {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * seq 计数器（进程内会话粒度；UNIQUE(session_id, seq) 冲突走 upsert 覆写）。
     * 并发安全：append 从运行协程与 UI 线程两语境可达，普通 HashMap 并发 merge
     * 丢 seq 后被 ON CONFLICT 静默覆写既有行（会话条目丢失）——必须 ConcurrentHashMap。
     */
    private val seqCounters = ConcurrentHashMap<String, Long>()

    override fun append(sessionId: String, entry: ChatEntry) {
        when (entry) {
            is ChatEntry.Activity, is ChatEntry.TodoBoardSnapshot -> return // 瞬态不落盘
            else -> Unit
        }
        // 🔴 会话登记只做一次（首条）：后续 append 的 upsertSession 会用兜底标题
        //    （"新会话"）覆写首条派生的好标题——ensureSession 只在会话尚不存在时执行
        if (db.sessionsQueries.sessionById(sessionId).executeAsOneOrNull() == null) {
            ensureSession(sessionId, entry)
        }
        val seq = seqCounters.merge(sessionId, 1L, Long::plus) ?: 1L
        db.transaction {
            when (entry) {
                is ChatEntry.User -> upsert(
                    sessionId, seq, "user", entry.text,
                    attachments = entry.attachments,
                )
                is ChatEntry.Assistant -> upsert(
                    sessionId, seq, "assistant", entry.text,
                )
                is ChatEntry.ToolCall -> upsert(
                    sessionId, seq, "tool", entry.argsSummary,
                    toolName = entry.toolName,
                    observation = entry.observation?.take(OBSERVATION_LIMIT),
                    state = entry.state.name,
                    // S5d 回放保真：path|kind 对编码（🔴 '|' 而非 ':'——Linux 路径可含冒号）
                    artifacts = entry.artifacts.map { encodeArtifact(it) },
                    entryId = entry.id,
                )
                is ChatEntry.ErrorBanner -> upsert(
                    sessionId, seq, "error", entry.title,
                    errorKind = entry.kind.name,
                    title = entry.title,
                    detail = entry.detail,
                    entryId = entry.id,
                )
                is ChatEntry.Activity, is ChatEntry.TodoBoardSnapshot -> Unit // 已 early return
            }
        }
    }

    override fun tombstone(sessionId: String) {
        db.sessionsQueries.markTombstone(System.currentTimeMillis(), sessionId)
        seqCounters.remove(sessionId)
    }

    override fun replay(sessionId: String): List<ChatEntry> {
        val rows = db.messagesQueries.messagesForSession(sessionId).executeAsList()
        // 回填内存 seq 计数器（重放后继续 append 从 max+1 起）
        if (rows.isNotEmpty()) seqCounters[sessionId] = rows.size.toLong()
        return rows.mapNotNull { it.toEntry() }
    }

    override fun latestSessionId(): String? =
        db.sessionsQueries.latestSession().executeAsOneOrNull()?.id

    /** 装载存量：SQLite 恒 no-op（存量行落库即生效，无外部装载面）——接口方法为 JSONL 实现保留 */
    override fun load() = Unit

    // ── P3-A2 会话管理面（列表/重命名/删除——直查 sessions/messages 表） ──

    /** 历史会话摘要行（管理面列表；updated_at 倒序） */
    fun listSummaries(): List<SessionSummary> =
        db.sessionsQueries.activeSessions().executeAsList().map { row ->
            SessionSummary(
                id = row.id,
                title = row.title,
                updatedAt = row.updated_at,
                messageCount = db.messagesQueries.countMessagesForSession(row.id).executeAsOne().toInt(),
            )
        }

    fun rename(id: String, title: String) {
        db.sessionsQueries.renameSession(title, System.currentTimeMillis(), id)
    }

    /** 删除会话（级联删消息——外键阻止裸删，先消息后会话同事务） */
    fun delete(id: String) {
        db.transaction {
            // 🔴 SQLDelight 多语句命名查询（deleteSessionCascade 两连 DELETE）经
            //    executeAsOne 消费时只取首条——拆成两个单语句查询各执行一次
            db.messagesQueries.deleteMessagesForSession(id)
            db.sessionsQueries.deleteSession(id)
        }
    }

    /** 管理面摘要行模型 */
    data class SessionSummary(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messageCount: Int,
    )

    // ── 内部 ──

    /** 会话自动登记：首条 append 时 upsert 会话行（title 从首条 USER 派生） */
    private fun ensureSession(sessionId: String, entry: ChatEntry) {
        val now = System.currentTimeMillis()
        val title = when (entry) {
            is ChatEntry.User -> entry.text.take(80).ifBlank {
                entry.attachments.firstOrNull()?.substringAfterLast('/') ?: "新会话"
            }
            else -> "新会话"
        }
        db.sessionsQueries.upsertSession(sessionId, title, now, now)
    }

    private fun upsert(
        sessionId: String,
        seq: Long,
        role: String,
        content: String,
        toolName: String? = null,
        observation: String? = null,
        state: String? = null,
        attachments: List<String> = emptyList(),
        artifacts: List<String> = emptyList(),
        errorKind: String? = null,
        title: String? = null,
        detail: String? = null,
        entryId: String? = null,
    ) {
        db.messagesQueries.upsertMessage(
            session_id = sessionId,
            seq = seq,
            role = role,
            content = content,
            tool_name = toolName,
            tool_observation = observation,
            entry_id = entryId ?: syntheticEntryId(sessionId, seq),
            state = state,
            attachments = encodeList(attachments),
            artifacts = encodeList(artifacts),
            error_kind = errorKind,
            title = title,
            detail = detail,
            created_at = System.currentTimeMillis(),
        )
    }

    private fun syntheticEntryId(sessionId: String, seq: Long) = "db-${sessionId.take(8)}-$seq"

    /** 行 → ChatEntry 重建（未知 role 返回 null——前向兼容旧数据） */
    private fun com.jiaoay.easy_dev_toolbox.core.data.db.Messages.toEntry(): ChatEntry? = when (role) {
        "user" -> ChatEntry.User(
            id = entry_id ?: syntheticEntryId(session_id, seq),
            text = content,
            attachments = decodeList(attachments),
        )
        "assistant" -> ChatEntry.Assistant(
            id = entry_id ?: syntheticEntryId(session_id, seq),
            text = content,
        )
        "tool" -> ChatEntry.ToolCall(
            id = entry_id ?: syntheticEntryId(session_id, seq),
            toolCallId = null, // 重放态无 Koog toolCallId——恒终态渲染
            toolName = tool_name.orEmpty(),
            argsSummary = content,
            state = runCatching { ToolState.valueOf(state ?: "COMPLETED") }.getOrDefault(ToolState.COMPLETED),
            observation = tool_observation,
            // S5d 回放保真：path|kind 解码——纯 path（无 '|' 或 kind 非法）旧数据回落 FILE
            artifacts = decodeList(artifacts).map { raw -> decodeArtifact(raw) },
        )
        "error" -> ChatEntry.ErrorBanner(
            id = entry_id ?: syntheticEntryId(session_id, seq),
            kind = runCatching { ErrorKind.valueOf(error_kind ?: "FAILED") }.getOrDefault(ErrorKind.FAILED),
            title = title ?: content,
            detail = detail,
        )
        else -> null
    }

    private val listSerializer = ListSerializer(String.serializer())

    private fun encodeList(list: List<String>): String? =
        if (list.isEmpty()) null else json.encodeToString(listSerializer, list)

    private fun decodeList(raw: String?): List<String> =
        raw?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrDefault(emptyList()) } ?: emptyList()

    // ── S5d artifacts kind 编解码（回放保真——REPORT/EXTRACTED 细分不再丢失） ──

    /** 编码：`path|kind`（🔴 '|' 分隔——Linux 路径可含 ':'，lastIndexOf('|') 兜底最稳） */
    internal fun encodeArtifact(chip: ArtifactChip): String = "${chip.path}|${chip.kind.name}"

    /**
     * 解码：末段 '|' 后是合法 kind → 保留细分；否则（纯 path 旧数据 / kind 非法）
     * 整串作路径、kind 回落 FILE——S5d 前存量行零迁移直读。
     */
    internal fun decodeArtifact(raw: String): ArtifactChip {
        val idx = raw.lastIndexOf('|')
        if (idx > 0) {
            val kind = runCatching { ArtifactChipKind.valueOf(raw.substring(idx + 1)) }.getOrNull()
            if (kind != null) {
                return ArtifactChip(path = raw.substring(0, idx), kind = kind)
            }
        }
        return ArtifactChip(path = raw, kind = ArtifactChipKind.FILE)
    }

    companion object {
        const val OBSERVATION_LIMIT: Int = 2000
    }
}
