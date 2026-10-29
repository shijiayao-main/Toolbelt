// 文件登记表。
// 语义：FILE 消息到达 ⇒ FileHandler.handle 登记（messageId → FileRecord）；
//    用户引用回复（replyToMessageId）命中指令前缀时按 ID 查表定位文件，懒下载。
// 为什么需要登记表：SDK capture 刻意跳过被引用消息内容，replyToMessageId 只是 ID——
//    不登记则引用时无从得知 fileKey/fileName。
// 🔴 装配期单例（PipelineSnapshot 每次 HOT 重建都 new handler 实例，registry 必须跨快照存活）。
// 纪律：CHM + ConcurrentLinkedQueue，零 synchronized/Mutex（与 ChatLane/MessageDeduper 同手法）。
package com.jiaoay.lark.bridge.files

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 引用文件登记表：messageId → [FileRecord]。内存表（重启即空——已知的可接受限界：
 * 飞书不重放历史事件，重启后旧文件的 fileKey 无从恢复；已落盘文件仍在下载配额区内）。
 *
 * ## 容量与过期
 * - FIFO 容量上限 [MAX_ENTRIES]（对齐 MessageDeduper 的 4096，不做配置项——条目 ~200B，上限 ≈ 1MB 内存）；
 * - TTL 惰性清除：[lookup] 时发现过期即 remove（无定时器）。
 *   TTL 值由调用方读 `fileMessage.registryTtlHours` 后换算传入——本类不持 config，保持纯数据结构可测。
 *
 * ## 并发模型
 * 登记发生在 FileHandler.handle 的任务协程；查表发生在 Agent/CommandHandler.handle 的任务协程。
 * CHM 保证可见性；FIFO 队列与 map 的双重结构在溢出逐出时可能瞬态不一致（队头已出队、map 未删），
 * 逐出循环以 map 为准做最终删除 ⇒ 不丢数据、不漏删，最坏情况队头残留一个已被覆盖的 key（无害）。
 */
class FileRegistry(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxEntries: Int = MAX_ENTRIES,
) {

    /** 一条被登记消息里的单个资源（对齐 InboundResource 的 fileKey/fileName 面）。 */
    data class ResourceRef(
        val fileKey: String,
        val fileName: String,
    )

    /** 一条被登记的文件消息。 */
    data class FileRecord(
        val messageId: String,
        val chatId: String,
        /** 文件发送者（openId）——下载配额归属人 + 目录归属。 */
        val senderId: String,
        val resources: List<ResourceRef>,
        val registeredAtMillis: Long,
    )

    private val records = ConcurrentHashMap<String, FileRecord>()

    /** 登记序（≈ 收录序），溢出逐出的队头来源。 */
    private val insertionOrder = ConcurrentLinkedQueue<String>()

    /**
     * 登记一条文件消息；登记时间由本表 clock 盖章（调用方不得自带时间戳——
     * 测试用虚拟时钟控 TTL，生产为系统钟，二者只能有一个真相源）。
     * 同 messageId 重登记 = 幂等覆盖（旧 key 先出队）。
     */
    fun register(
        messageId: String,
        chatId: String,
        senderId: String,
        resources: List<ResourceRef>,
    ): FileRecord {
        val record = FileRecord(
            messageId = messageId,
            chatId = chatId,
            senderId = senderId,
            resources = resources,
            registeredAtMillis = clock(),
        )
        val existed = records.put(record.messageId, record) != null
        if (existed) insertionOrder.remove(record.messageId)
        insertionOrder.add(record.messageId)
        evictOverflow()
        return record
    }

    /**
     * 别名登记：把**机器人回执消息的 id** 也登记成指向 [record] 的别名条目——
     * 用户引用回执（而非引用原文件消息）时 lookup 同样命中，走完整懒下载/预处理链。
     *
     * 🔴 `record.messageId` **原样保留**（不换成回执 id）：下载目录 `dirFor`、同消息复用 `recordedDirFor`、
     *    per-dest 单飞锁全部按 record.messageId 推导——换成回执 id 会让「引用回执」与「引用原文件」
     *    两条路落进不同 dest，并发触发时双下载、双预处理、配额双计；保留原 id 则两路合并同一 dest，
     *    由既有 inflightLocks 单飞锁去重（leader 下载、follower 复用）。
     * 别名条目是**独立 key**：TTL 惰性清除与 FIFO 逐出各自独立发生（时间戳相差毫秒级），单条存活
     *    即可命中；全 miss 还有 im.v1.message.get 补查自愈。代价仅是每文件消息占两个 FIFO 条目。
     * 同 aliasId 重复登记 = 幂等覆盖（回执重发场景）。
     */
    fun alias(aliasId: String, record: FileRecord): FileRecord {
        val aliasRecord = record.copy(registeredAtMillis = clock())
        val existed = records.put(aliasId, aliasRecord) != null
        if (existed) insertionOrder.remove(aliasId)
        insertionOrder.add(aliasId)
        evictOverflow()
        return aliasRecord
    }

    /** FIFO 溢出逐出（register/alias 共用）：以 map 为准做最终删除，队头空则停（不丢数据、不漏删）。 */
    private fun evictOverflow() {
        while (records.size > maxEntries) {
            val eldestKey = insertionOrder.poll() ?: break
            records.remove(eldestKey)
        }
    }

    /**
     * 按引用回复查表。
     * @param ttlMillis 登记有效期（`fileMessage.registryTtlHours` 换算；<=0 视为永不过期）
     * @return null = 未登记 / TTL 过期（惰性清除）/ chatId 不匹配（跨会话引用视为未命中）
     */
    fun lookup(messageId: String, chatId: String, ttlMillis: Long): FileRecord? {
        if (messageId.isBlank()) return null
        val record = records[messageId] ?: return null
        if (record.chatId != chatId) return null
        if (ttlMillis > 0 && clock() - record.registeredAtMillis > ttlMillis) {
            // 惰性清除：过期条目连带出队，防队头积压
            records.remove(messageId)
            insertionOrder.remove(messageId)
            return null
        }
        return record
    }

    /** 当前条目数（测试与指标用）。 */
    val size: Int get() = records.size

    /** 该 messageId 是否已登记（不论 chatId——供调用方区分「跨会话引用」与「完全未登记」的提示文案）。 */
    fun contains(messageId: String): Boolean = records.containsKey(messageId)

    /** 清空（测试用）。 */
    fun clear() {
        records.clear()
        insertionOrder.clear()
    }

    companion object {
        /** FIFO 容量上限（对齐 MessageDeduper 4096）。 */
        const val MAX_ENTRIES: Int = 4096
    }
}
