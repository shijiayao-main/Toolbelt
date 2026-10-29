package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话历史表（[ChatHistoryProvider] 自定义实现，规避官方两个坑——见 [KoogAgentRunner] 类头注释）：
 * - store 滤掉 System 消息（ChatMemory 存整 prompt 含 system，load 非空历史整体替换会重复注入 system）；
 * - LRU 容量封顶（koog 官方 InMemoryChatHistoryProvider 无上限，长跑进程内存无界膨胀）。
 *
 * 🔴 并发纪律：store/load 都在协程内调用（ChatMemory 的 pipeline 拦截器）——用 ConcurrentHashMap
 *    + 插入序 LRU（LinkedHashMap accessOrder 在并发下不安全，改显式时间戳驱逐）。
 */
internal class SessionHistoryProvider(
    private val capacity: Int,
    private val windowSize: Int,
    /**
     * 持久层（桥接记忆持久化；null = 纯内存——历史形态与测试默认）。
     * 语义：**写穿透**（store 同步落持久层）+ **读升级**（内存 miss 时从持久层回填
     * 并按 LRU 重登记）——重启后桥接会话历史可续，真机排查有据可查；
     * reset 同步清持久层行（deleteHistory）。
     */
    private val persistStore: PersistedHistory? = null,
) : ChatHistoryProvider {

    private val histories = ConcurrentHashMap<String, List<Message>>()
    private val lastAccess = ConcurrentHashMap<String, Long>()
    private val seq = java.util.concurrent.atomic.AtomicLong(0)

    override suspend fun store(conversationId: String, messages: List<Message>) {
        // 坑 1 规避：滤 System——load 整体替换 prompt 时 system 不会重复（本轮 system 由 AIAgent 注入）。
        val nonSystem = messages.filterNot { it is Message.System }
        if (nonSystem.isEmpty()) return
        histories[conversationId] = nonSystem.takeLast(windowSize)
        touch(conversationId)
        evictIfNeeded()
        // 写穿透：持久层收与内存同款窗口视图（重启回填后不超窗）
        persistStore?.store(conversationId, nonSystem.takeLast(windowSize))
    }

    override suspend fun load(conversationId: String): List<Message> {
        touch(conversationId)
        histories[conversationId]?.let { return it }
        // 读升级：内存 miss（重启/被 LRU 逐出）→ 持久层回填内存并续 LRU 命中序
        val persisted = persistStore?.load(conversationId).orEmpty()
        if (persisted.isNotEmpty()) {
            histories[conversationId] = persisted
            touch(conversationId)
            evictIfNeeded()
        }
        return persisted
    }

    /** 显式重置（用户发「新对话」）。@return true = 确实丢弃了一个存在的会话。 */
    suspend fun reset(conversationId: String): Boolean {
        lastAccess.remove(conversationId)
        val existedInMemory = histories.remove(conversationId) != null
        val existedInPersist = persistStore?.deleteHistory(conversationId) ?: false
        return existedInMemory || existedInPersist
    }

    private fun touch(conversationId: String) {
        lastAccess[conversationId] = seq.incrementAndGet()
    }

    /** LRU 驱逐：超容量时按访问序逐出最旧会话。 */
    private fun evictIfNeeded() {
        while (histories.size > capacity) {
            val oldest = lastAccess.minByOrNull { it.value }?.key ?: break
            histories.remove(oldest)
            lastAccess.remove(oldest)
            // 🔴 只逐内存不逐持久层：LRU 是内存容量语义，持久层是全量审计面——
            //    连持久层一起逐会把会话历史永久删光（数据不可逆丢失）。
            //    被逐会话再次被引用时经「读升级」路径自动恢复
        }
    }
}

interface PersistedHistory {
    suspend fun store(conversationId: String, messages: List<Message>)

    suspend fun load(conversationId: String): List<Message>

    suspend fun deleteHistory(conversationId: String): Boolean
}
