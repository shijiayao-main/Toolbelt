// 引用即上下文配套机制：机器人回复（流式卡）账本。
// 背景：机器人回复是 CardKit 流式卡，im.v1.message.get 只能拿到 {"type":"card","card_id":…} 实体引用，
//    飞书平台没有「按 card_id 读卡片实体」的 GET API（SDK cardkit 五方法已核实：create/update/batchUpdate/
//    settings/idConvert）——正文只在我们自己发终态卡的手里。
// 机制：网关 sink 终态落定时发 GatewaySignal.StreamTerminal → Bridge 收集写本账本（messageId → markdown）→
//    用户「引用机器人回复」时 ReferencedMessageResolver 先查本账，命中直接作文本上下文（零 API 调用）。
// 🔴 持久化：进程重启（Hot Reload/关 App）即丢 ⇒ 重启后引用机器人
//    回复必 miss（真机实证）。现落盘 <dataRoot>/card-reply-ledger.jsonl（kotlinx.serialization，
//    不引 Gson——bridge 禁 SDK/Gson 纪律）：record 追加写、FIFO 淘汰时全量重写压缩（512 行上界，
//    重写成本低）、启动 load 逐行解码坏行跳过。
// 🔴 纪律：CHM，零 synchronized/Mutex（与 FileRegistry 同手法）；FIFO 容量上限防内存无界。
package com.jiaoay.lark.bridge.files

import com.jiaoay.lark.bridge.BridgeLog
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 机器人回复账本：卡消息 messageId → (chatId, 终态 markdown 全文)。
 * 🔴 **含 chatId 维度**——`lookup(messageId, chatId)` **chatId 不匹配
 *    即 miss**。防跨会话引用：agent 工具（fetch_quoted_context 等）按「本会话」取上下文，
 *    不允许 A 会话的 agent 读到 B 会话的机器人回复正文（prompt 注入面收窄到当前会话）。
 *    旧持久化行（无 chatId 字段）载入时落 ""（未知）——查询单聊 chatId=null 时视为匹配（兼容）。
 *
 * - 写入时机：流式卡终态落定（flushTerminal / close 兜底），经网关信号到达；
 * - 查询时机：引用机器人回复且登记表 miss（机器人消息永不进 FileRegistry）；
 * - Bridge 稳定件：跨 start/stop 存活（与 [FileRegistry] 同批生命周期）；
 *   [persistenceFile] 非 null 时跨进程存活。
 *
 * ## 并发模型
 * 写入发生在 RuntimeBundle.signalScope 收集协程；查询发生在 Agent/CommandHandler 任务协程。
 * CHM 保证可见性；FIFO 队列 + map 双重结构与 FileRegistry 溢出逐出同款（瞬态不一致无害）。
 * 落盘在 record 内同步执行（512 行 JSONL 全量重写 ≈ 数 ms，signalScope 收集协程可承受）。
 *
 * @param persistenceFile 持久化文件（null = 纯内存——测试默认零改动）。
 */
class CardReplyLedger(
    private val maxEntries: Int = MAX_ENTRIES,
    private val persistenceFile: Path? = null,
    private val log: BridgeLog = BridgeLog.Silent,
) {
    private val replies = ConcurrentHashMap<String, Entry>()
    private val insertionOrder = ConcurrentLinkedQueue<String>()
    private val json = Json { ignoreUnknownKeys = true }

    /** 一条账目（chatId 维度——本会话校验依据）。 */
    private data class Entry(val chatId: String, val markdown: String)

    /** 记一条终态（同 messageId 覆写 = 幂等）。空白 markdown 不记。chatId null → ""（未知，兼容旧载入态）。 */
    fun record(messageId: String, chatId: String?, markdown: String) {
        if (messageId.isBlank() || markdown.isBlank()) return
        val existed = replies.put(messageId, Entry(chatId.orEmpty(), markdown)) != null
        if (existed) insertionOrder.remove(messageId)
        insertionOrder.add(messageId)
        while (replies.size > maxEntries) {
            val eldestKey = insertionOrder.poll() ?: break
            replies.remove(eldestKey)
        }
        persist()
    }

    /**
     * 按引用回复的 messageId 查终态全文；未记录返回 null。
     * **本会话校验**——账目 chatId 与查询 chatId 不匹配即 miss。
     *    null（单聊 User 目标/未知）只匹配账目 chatId 为 ""（未知）的旧记录，防跨会话穿透。
     */
    fun lookup(messageId: String, chatId: String? = null): String? =
        replies[messageId]?.takeIf { it.chatId == chatId.orEmpty() && it.markdown.isNotBlank() }?.markdown

    /** 当前条目数（测试与指标用）。 */
    val size: Int get() = replies.size

    /** 清空（测试用；同步清落盘文件——与内存态保持一致）。 */
    fun clear() {
        replies.clear()
        insertionOrder.clear()
        persistenceFile?.let { runCatching { Files.deleteIfExists(it) } }
    }

    /**
     * 启动装载：逐行解码 JSONL（坏行跳过——追加写崩溃残留）、按行序保留最新 [maxEntries] 条入内存。
     * 🔴 装载后**立即全量重写**文件：只回写活条目，防文件无上界增长。
     */
    fun load() {
        val file = persistenceFile ?: return
        if (!Files.isRegularFile(file)) return
        var badLines = 0
        val lines = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
        for (line in lines) {
            val parsed = runCatching { json.decodeFromString(LedgerLine.serializer(), line) }.getOrNull()
            if (parsed == null || parsed.messageId.isBlank() || parsed.markdown.isBlank()) {
                badLines++
                continue
            }
            val existed = replies.put(parsed.messageId, Entry(parsed.chatId.orEmpty(), parsed.markdown)) != null
            if (existed) insertionOrder.remove(parsed.messageId)
            insertionOrder.add(parsed.messageId)
            while (replies.size > maxEntries) {
                val eldestKey = insertionOrder.poll() ?: break
                replies.remove(eldestKey)
            }
        }
        if (badLines > 0) log.warn("CardReplyLedger 载入跳过 $badLines 行损坏记录（追加写崩溃残留）")
        persist()
    }

    /** 全量重写落盘（活条目按 FIFO 序）：record 每次同步调用（512 行上界，成本可控）；失败仅静默降级为内存态。 */
    private fun persist() {
        val file = persistenceFile ?: return
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            val body = insertionOrder.mapNotNull { id -> replies[id]?.let { LedgerLine(id, it.chatId, it.markdown) } }
                .joinToString("\n") { json.encodeToString(LedgerLine.serializer(), it) }
            Files.write(file, body.toByteArray(Charsets.UTF_8))
        }
    }

    /** JSONL 行（kotlinx.serialization；禁 Gson——bridge 纪律）。
     *  chatId 默认 ""：旧格式行（无此字段）decode 落空串=未知，与 null 查询兼容。 */
    @Serializable
    private data class LedgerLine(val messageId: String, val chatId: String = "", val markdown: String)

    companion object {
        /** FIFO 容量上限。每条 markdown 上限 ≈ 24KB（卡片预算），512 条 ≈ 12MB 上界；对齐小容量防无界。 */
        const val MAX_ENTRIES: Int = 512
    }
}
