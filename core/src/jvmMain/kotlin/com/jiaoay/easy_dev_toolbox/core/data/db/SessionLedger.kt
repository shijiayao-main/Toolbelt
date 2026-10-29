package com.jiaoay.easy_dev_toolbox.core.data.db

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChip
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChipKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ErrorKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 会话账本（JSONL 持久化实现——现为测试实现与历史参照）：
 * agent 会话的 JSONL 持久化。
 *
 * ## 机制（对齐 lark-bridge CardReplyLedger 配方：追加写 + 坏行跳过 + 启动压缩）
 * - **追加写**：每条 [ChatEntry] 落一行 [EntryLine]（同会话同 id 覆写 = 幂等 upsert）；
 *   observation 截 2000 字符（防 MB 级 tool 结果撑爆账本）；
 * - **墓碑行**：reset（新建会话）写 `TOMBSTONE` 行——装载时把该会话的既有条目
 *   全部丢弃（会话废弃语义）；
 * - **容量淘汰**：每会话一个记录位，keepSessions 上界 FIFO——满员时最老会话整会话剔除；
 * - **坏行跳过**：追加写崩溃残留的半行解码失败直接跳过；
 * - **启动压缩**：load 后立即全量重写文件（只回写活条目，防文件无上界增长）。
 *
 * ## 落盘内容面（摘要面，非全文面）
 * - User/Assistant：**全文**（多轮记忆的 prompt tail 源——重建会话上下文的必需品）；
 * - ToolCall：名称+argsSummary+observation 截 2000+artifacts 路径（重放时证据链可见）；
 * - Activity/TodoBoardSnapshot：**瞬态不落盘**（活动文案从事件流重新推导，快照重放无价值）；
 * - ErrorBanner：kind+title+detail。
 *
 * DTO 单独建模（[EntryLine]）而非直接 @Serializable ChatEntry：:ui 模块保持纯
 *    数据类不加 serialization 插件（依赖面决策），映射在 feature-ai 侧完成。
 *
 * @param sessionDir 账本目录（null = 纯内存——测试零落盘默认）
 * @param keepSessions 会话容量上界（FIFO 淘汰）
 */
class SessionLedger(
    private val sessionDir: Path? = null,
    private val keepSessions: Int = DEFAULT_KEEP_SESSIONS,
) : SessionStore {
    private val json = Json { ignoreUnknownKeys = true }

    /** 会话 id → 有序条目（内存态；load 回填 / append 持久） */
    private val sessions = LinkedHashMap<String, MutableList<EntryLine>>()
    private val sessionOrder = LinkedHashSet<String>()

    /**
     * 追加一条（同会话同 id 覆写 = 幂等 upsert；跳过 [ChatEntry.Activity] /
     * [ChatEntry.TodoBoardSnapshot] 瞬态不落盘）。
     */
    override fun append(sessionId: String, entry: ChatEntry) {
        val line = when (entry) {
            is ChatEntry.User -> EntryLine(
                sessionId = sessionId,
                id = entry.id,
                kind = LineKind.USER,
                text = entry.text,
                attachments = entry.attachments,
            )
            is ChatEntry.Assistant -> EntryLine(
                sessionId = sessionId,
                id = entry.id,
                kind = LineKind.ASSISTANT,
                text = entry.text,
            )
            is ChatEntry.ToolCall -> EntryLine(
                sessionId = sessionId,
                id = entry.id,
                kind = LineKind.TOOL_CALL,
                toolName = entry.toolName,
                argsSummary = entry.argsSummary,
                state = entry.state,
                observation = entry.observation?.take(OBSERVATION_LIMIT),
                artifacts = entry.artifacts.map { it.path },
            )
            is ChatEntry.ErrorBanner -> EntryLine(
                sessionId = sessionId,
                id = entry.id,
                kind = LineKind.ERROR,
                errorKind = entry.kind,
                title = entry.title,
                detail = entry.detail,
            )
            is ChatEntry.Activity, is ChatEntry.TodoBoardSnapshot -> return // 瞬态不落盘
        }
        writeLine(line)
    }

    /** 会话废弃（reset/新建会话）：写墓碑行——装载时老条目整会话丢弃。 */
    override fun tombstone(sessionId: String) {
        writeLine(EntryLine(sessionId = sessionId, id = "tombstone", kind = LineKind.TOMBSTONE))
    }

    /** 重建会话条目（装配顺序即落盘顺序）；未知会话返回空表。 */
    override fun replay(sessionId: String): List<ChatEntry> =
        sessions[sessionId]?.mapNotNull { it.toEntry() } ?: emptyList()

    /** FIFO 最新会话 id（重启恢复用；无会话返回 null）。 */
    override fun latestSessionId(): String? = sessionOrder.lastOrNull()

    /** 当前活会话数（测试与指标用）。 */
    val sessionCount: Int get() = sessionOrder.size

    /** 装载：逐行解码坏行跳过；结束后立即全量重写（压缩——只回写活条目）。 */
    override fun load() {
        val dir = sessionDir ?: return
        val file = dir.resolve(LEDGER_FILE)
        if (!Files.isRegularFile(file)) return
        var badLines = 0
        val lines = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
        for (line in lines) {
            val parsed = runCatching { json.decodeFromString(EntryLine.serializer(), line) }.getOrNull()
            if (parsed == null) {
                badLines++
                continue
            }
            if (parsed.kind == LineKind.TOMBSTONE) {
                sessions.remove(parsed.sessionId)
                sessionOrder.remove(parsed.sessionId)
            } else {
                indexLine(parsed)
            }
        }
        if (badLines > 0) {
            JLog.w(TAG) { "会话账本跳过 $badLines 行损坏记录（追加写崩溃残留）" }
        }
        compact()
    }

    /** 全量重写（活条目按会话 FIFO 序）；失败静默降级为内存态。 */
    private fun compact() {
        val dir = sessionDir ?: return
        runCatching {
            Files.createDirectories(dir)
            val body = sessionOrder.flatMap { sid -> sessions[sid].orEmpty() }
                .joinToString("\n") { json.encodeToString(EntryLine.serializer(), it) }
            Files.write(dir.resolve(LEDGER_FILE), body.toByteArray(Charsets.UTF_8))
        }
    }

    /** 落一行：内存索引 + 追加写盘（内存态恒前进，写盘失败不阻断会话）。 */
    private fun writeLine(line: EntryLine) {
        if (line.kind == LineKind.TOMBSTONE) {
            sessions.remove(line.sessionId)
            sessionOrder.remove(line.sessionId)
        } else {
            indexLine(line)
        }
        val dir = sessionDir ?: return
        runCatching {
            Files.createDirectories(dir)
            Files.write(
                dir.resolve(LEDGER_FILE),
                (json.encodeToString(EntryLine.serializer(), line) + "\n").toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun indexLine(line: EntryLine) {
        val list = sessions.getOrPut(line.sessionId) { mutableListOf() }
        val existing = list.indexOfFirst { it.id == line.id }
        if (existing >= 0) list[existing] = line else list.add(line)
        sessionOrder.add(line.sessionId)
        evictIfNeeded()
    }

    private fun evictIfNeeded() {
        while (sessionOrder.size > keepSessions) {
            val eldest = sessionOrder.firstOrNull() ?: break
            sessions.remove(eldest)
            sessionOrder.remove(eldest)
        }
    }

    // ── DTO → ChatEntry 映射 ──

    private fun EntryLine.toEntry(): ChatEntry? = when (kind) {
        LineKind.USER -> ChatEntry.User(
            id = id,
            text = text.orEmpty(),
            attachments = attachments,
        )
        LineKind.ASSISTANT -> ChatEntry.Assistant(
            id = id,
            text = text.orEmpty(),
        )
        LineKind.TOOL_CALL -> ChatEntry.ToolCall(
            id = id,
            // 重放态无 Koog toolCallId（事件链已断）——卡片不再原位迁移，恒终态渲染；
            // 落盘时 RUNNING（崩溃残留的运行卡）重放降级 CANCELLED——不能永远挂 spinner
            toolCallId = null,
            toolName = toolName.orEmpty(),
            argsSummary = argsSummary.orEmpty(),
            state = if (state == ToolState.RUNNING) ToolState.CANCELLED else state ?: ToolState.COMPLETED,
            observation = observation,
            artifacts = artifacts.map { ArtifactChip(path = it, kind = ArtifactChipKind.FILE) },
        )
        LineKind.ERROR -> ChatEntry.ErrorBanner(
            id = id,
            kind = errorKind ?: ErrorKind.FAILED,
            title = title.orEmpty(),
            detail = detail,
        )
        LineKind.TOMBSTONE -> null
    }

    /**
     * JSONL 行（kotlinx.serialization）。observation 截断见 [OBSERVATION_LIMIT]。
     */
    @Serializable
    data class EntryLine(
        val sessionId: String,
        val id: String,
        val kind: LineKind,
        val text: String? = null,
        val attachments: List<String> = emptyList(),
        val toolName: String? = null,
        val argsSummary: String? = null,
        val state: ToolState? = null,
        val observation: String? = null,
        val artifacts: List<String> = emptyList(),
        val errorKind: ErrorKind? = null,
        val title: String? = null,
        val detail: String? = null,
    )

    @Serializable
    enum class LineKind { USER, ASSISTANT, TOOL_CALL, ERROR, TOMBSTONE }

    companion object {
        private const val TAG = "SessionLedger"
        private const val LEDGER_FILE = "sessions.jsonl"
        const val DEFAULT_KEEP_SESSIONS: Int = 16
        const val OBSERVATION_LIMIT: Int = 2000
    }
}
