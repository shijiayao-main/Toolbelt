package com.jiaoay.easy_dev_toolbox.page.quest

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.utils.time.KoogClock
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * QuestHistoryProvider 单测（Quest 域 change 2 任务 1.2）。
 * 覆盖：store 滤 System（防重复注入纪律）/ 窗口截断（store+load 双侧）/ 写穿透读回。
 * 持久层用真 SqliteChatHistoryProvider（临时 db 文件——官方 schema 语义由其自带
 * 测试背书，此处只验本 provider 的滤除与截断纪律）。
 */
class QuestHistoryProviderTest {

    private lateinit var dbFile: File
    private lateinit var provider: QuestHistoryProvider

    @BeforeTest
    fun setUp() {
        dbFile = File(System.getProperty("java.io.tmpdir"), "quest-history-${System.nanoTime()}.db")
            .apply { deleteOnExit() }
        provider = QuestHistoryProvider.forFile(dbFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    private fun userMsg(text: String): Message = Message.User(
        id = null,
        parts = listOf(MessagePart.Text(text)),
        metaInfo = RequestMetaInfo.create(KoogClock.System),
    )

    private fun systemMsg(text: String = "sys"): Message = Message.System(
        id = null,
        parts = listOf(MessagePart.Text(text)),
        metaInfo = RequestMetaInfo.create(KoogClock.System),
    )

    private fun assistantMsg(text: String): Message = Message.Assistant(
        id = null,
        parts = listOf(MessagePart.Text(text)),
        metaInfo = ai.koog.prompt.message.ResponseMetaInfo.create(KoogClock.System),
    )

    private fun Message.firstText(): String =
        parts.filterIsInstance<MessagePart.Text>().firstOrNull()?.text ?: ""

    @Test
    fun `store filters system messages`() = runTest {
        provider.store(
            "f1",
            listOf(systemMsg(), userMsg("第一条"), assistantMsg("回答")),
        )
        val loaded = provider.load("f1")
        assertEquals(2, loaded.size, "System 消息不入历史（防 load 整体替换重复注入）")
        assertEquals("第一条", loaded[0].firstText())
    }

    @Test
    fun `store truncates to window size`() = runTest {
        val many = (1..(QuestHistoryProvider.WINDOW_SIZE + 10)).map { userMsg("消息 $it") }
        provider.store("f2", many)
        val loaded = provider.load("f2")
        assertEquals(QuestHistoryProvider.WINDOW_SIZE, loaded.size, "store 侧窗口截断")
        assertEquals("消息 11", loaded.first().firstText(), "保留的是最新窗口（最老 10 条被截）")
    }

    @Test
    fun `write through and load back across provider instances`() = runTest {
        // 重启语义：新 provider 实例（同 db 文件）读回——「历史流程继续上下文」的持久面
        provider.store("f3", listOf(userMsg("重启前的消息")))
        val reopened = QuestHistoryProvider.forFile(dbFile.absolutePath)
        assertEquals("重启前的消息", reopened.load("f3").first().firstText())
    }

    @Test
    fun `load on empty conversation returns empty`() = runTest {
        assertEquals(emptyList(), provider.load("nope"))
    }
}
