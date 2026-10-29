package com.jiaoay.easy_dev_toolbox.core.data.db

import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChip
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChipKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ErrorKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SessionLedger 纪律（B8，零 LLM/零网络）：
 * - 写读回环：append → replay 条目语义等价（瞬态 Activity/TodoBoard 不落盘）；
 * - 墓碑行：tombstone 后老条目整会话丢弃；
 * - 容量淘汰：keepSessions 上界 FIFO（最老会话整会话剔除）；
 * - 坏行跳过 + 启动压缩：半行损坏不炸装载，装载后文件回写活条目；
 * - 崩溃残留 RUNNING 卡重放降级 CANCELLED（不能永远挂 spinner）；
 * - observation 截 2000。
 */
class SessionLedgerTest {

    private fun tempDir(): Path = Files.createTempDirectory("session-ledger")

    @Test
    fun `append then replay round trips stable entries`() {
        val dir = tempDir()
        val ledger = SessionLedger(sessionDir = dir)
        val sid = "s1"

        ledger.append(sid, ChatEntry.User(id = "u1", text = "分析这个文件", attachments = listOf("a.alog")))
        ledger.append(sid, ChatEntry.Activity(id = "act1", text = "嗅探中…", running = true)) // 瞬态
        ledger.append(
            sid,
            ChatEntry.ToolCall(
                id = "tc1", toolCallId = "k1", toolName = "decrypt_file",
                argsSummary = "path=/a.alog", state = ToolState.COMPLETED,
                observation = "解密成功", artifacts = listOf(ArtifactChip("/a.log", ArtifactChipKind.FILE)),
            ),
        )
        ledger.append(sid, ChatEntry.Assistant(id = "a1", text = "解密完成，输出在 /a.log"))
        ledger.append(sid, ChatEntry.TodoBoardSnapshot(id = "todo1", items = emptyList())) // 瞬态

        val replayed = ledger.replay(sid)
        assertEquals(3, replayed.size, "瞬态 Activity/TodoBoard 不落盘，其余 3 条在")

        val user = replayed[0] as ChatEntry.User
        assertEquals("分析这个文件", user.text)
        assertEquals(listOf("a.alog"), user.attachments)

        val tool = replayed[1] as ChatEntry.ToolCall
        assertEquals("decrypt_file", tool.toolName)
        assertEquals(ToolState.COMPLETED, tool.state)
        assertEquals("/a.log", tool.artifacts.single().path)
        assertNull(tool.toolCallId, "重放态无 toolCallId（事件链已断，卡片恒终态）")

        val assistant = replayed[2] as ChatEntry.Assistant
        assertEquals("解密完成，输出在 /a.log", assistant.text)
    }

    @Test
    fun `tombstone drops the whole session on reload`() {
        val dir = tempDir()
        SessionLedger(sessionDir = dir).let { first ->
            first.append("s1", ChatEntry.User(id = "u1", text = "旧会话"))
            first.tombstone("s1")
        }

        // 新账本实例模拟重启装载：墓碑把 s1 挤掉
        val reloaded = SessionLedger(sessionDir = dir)
        reloaded.load()
        assertTrue(reloaded.replay("s1").isEmpty(), "墓碑后老条目整会话丢弃")
        assertNull(reloaded.latestSessionId())
    }

    @Test
    fun `keep sessions evicts oldest whole session fifo`() {
        val dir = tempDir()
        val ledger = SessionLedger(sessionDir = dir, keepSessions = 2)

        ledger.append("s1", ChatEntry.User(id = "u1", text = "一"))
        ledger.append("s2", ChatEntry.User(id = "u2", text = "二"))
        ledger.append("s3", ChatEntry.User(id = "u3", text = "三"))

        assertEquals(2, ledger.sessionCount, "容量上界 2 生效")
        assertTrue(ledger.replay("s1").isEmpty(), "最老会话 s1 整会话剔除")
        assertFalse(ledger.replay("s2").isEmpty())
        assertFalse(ledger.replay("s3").isEmpty())
        assertEquals("s3", ledger.latestSessionId(), "最新会话 = 最后写入者")
    }

    @Test
    fun `bad lines are skipped and file is compacted on load`() {
        val dir = tempDir()
        val file = dir.resolve("sessions.jsonl")
        Files.write(
            file,
            """
            {"sessionId":"s1","id":"u1","kind":"USER","text":"正常行"}
            {"sessionId":"s1","id":"u2","kind":"AS 这是半行崩溃残留
            {"sessionId":"s1","id":"u3","kind":"ASSISTANT","text":"也正常"}
            """.trimIndent().toByteArray(),
        )

        val ledger = SessionLedger(sessionDir = dir)
        ledger.load()

        val replayed = ledger.replay("s1")
        assertEquals(2, replayed.size, "坏行跳过，好行保留")

        // 启动压缩：装载后文件只回写活条目（半行被清掉）
        val content = Files.readAllLines(file).joinToString("\n")
        assertFalse("半行崩溃残留" in content, "压缩后崩溃残留被清除")
    }

    @Test
    fun `running tool card degrades to cancelled on replay`() {
        val dir = tempDir()
        // 直接手工构造「落盘时还是 RUNNING」的行（崩溃残留形态）
        val file = dir.resolve("sessions.jsonl")
        Files.write(
            file,
            """
            {"sessionId":"s9","id":"tc1","kind":"TOOL_CALL","toolName":"grep","argsSummary":"pattern=ERROR","state":"RUNNING"}
            """.trimIndent().toByteArray(),
        )

        val ledger = SessionLedger(sessionDir = dir)
        ledger.load()

        val tool = ledger.replay("s9").single() as ChatEntry.ToolCall
        assertEquals(ToolState.CANCELLED, tool.state, "崩溃残留 RUNNING 卡重放降级 CANCELLED（不挂 spinner）")
    }

    @Test
    fun `observation is truncated to 2000 chars`() {
        val dir = tempDir()
        val ledger = SessionLedger(sessionDir = dir)
        val huge = "x".repeat(5000)

        ledger.append(
            "s1",
            ChatEntry.ToolCall(
                id = "tc1", toolCallId = "k", toolName = "read_file",
                argsSummary = "p", state = ToolState.COMPLETED, observation = huge,
            ),
        )

        val reloaded = SessionLedger(sessionDir = dir)
        reloaded.load()
        val tool = reloaded.replay("s1").single() as ChatEntry.ToolCall
        assertEquals(2000, tool.observation!!.length, "observation 截 2000 字符")
    }

    @Test
    fun `error banner survives round trip`() {
        val dir = tempDir()
        val ledger = SessionLedger(sessionDir = dir)

        ledger.append("s1", ChatEntry.ErrorBanner(id = "e1", kind = ErrorKind.TIMEOUT, title = "处理超时", detail = "60s"))
        ledger.tombstone("nope") // 无关会话墓碑 no-op

        val reloaded = SessionLedger(sessionDir = dir)
        reloaded.load()
        val error = reloaded.replay("s1").single() as ChatEntry.ErrorBanner
        assertEquals(ErrorKind.TIMEOUT, error.kind)
        assertEquals("处理超时", error.title)
    }
}
