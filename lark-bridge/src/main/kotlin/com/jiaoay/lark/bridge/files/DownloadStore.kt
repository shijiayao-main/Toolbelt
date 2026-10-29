// 持久下载区 + 按发送者分目录 + 配额 FIFO 清理。
// 语义：引用命中才懒下载（不被引用的文件零磁盘成本）；下载目录按 senderOpenId 区分；
//    每用户配额 + 全局上限（字节口径），超限按收录时间 FIFO 逐出**整个 per-message 目录**。
// 账本粒度 = per-message 目录（zip 可解出上千文件，按目录记账保证账本 O(消息数)）。
// 🔴 纪律：CHM + ConcurrentLinkedQueue + AtomicLong，零 synchronized/Mutex——违反即在下载/逐出热路径引入锁竞争卡死。
// per-message 目录带 .meta.json sidecar（messageId/fileKey/
//    fileName/sha256/bytes/preprocessStamp）+ messageId→目录持久映射 + sha256 内容寻址索引
//    （跨消息复用省磁盘省预处理；跨消息不省下载 API——入站无内容指纹，能力上限如此）。
package com.jiaoay.lark.bridge.files

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.BridgeConfig
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 持久下载区管理器（装配期单例，跨 HOT 快照存活）。
 *
 * ## 目录布局
 * `<workspace.root>/downloads/<senderOpenId>/<yyyy-MM-dd>/<messageId前12>_<safeName>/`
 * —— 按发送者区分（需求 4）+ 按日期分桶（FIFO 扫描高效）+ 每消息每文件独立目录（配额记账粒度）。
 *
 * ## 配额语义（`fileMessage.perUserQuotaBytes` / `totalQuotaBytes`）
 * - `recordStored` 后由调用方在任务协程 `launch { evictIfNeeded() }`（🔴 绝不进 intake 单协程）；
 * - per-user 超限 ⇒ 只逐该 sender 的最旧条目；全局超限 ⇒ 跨 sender 从队头逐；
 * - 删除 = 整目录 `deleteRecursively()`（半删一个消息的文件比整删更糟），失败 warn 不抛；
 * - 不做定时轮询：配额只在 store 时增长，事件驱动已完备；启动 [rescan] 兜底重建账本。
 *
 * ## 文件复用能力
 * - `.meta.json` sidecar：每个 per-message 目录落一份（messageId/fileKey/fileName/sha256/bytes/preprocessStamp）；
 *   [rescan] 读 meta 重建「messageId → 目录」映射与「sha256 → 目录」内容索引——跨天/跨进程重启后复用不失效。
 * - 在途保护（pin/unpin 引用计数）：agent 任务持有目录引用期间逐出跳过（防「agent 运行中其文件目录被
 *   他人新下载触发的配额逐出删除」竞态）。
 * - legacy 目录（无 .meta.json）：只进账本不进索引——仅同消息可复用（迁移成本，KDoc 化）。
 */
class DownloadStore(
    private val configProvider: () -> BridgeConfig,
    private val log: BridgeLog,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 账本条目：一个 per-message 目录的字节数与收录时间。 */
    data class Entry(
        val dir: File,
        val senderId: String,
        val bytes: Long,
        val storedAtMillis: Long,
    )

    /** .meta.json 的持久形态（kotlinx.serialization；落盘面最小——不含临时态）。 */
    @kotlinx.serialization.Serializable
    data class DirMeta(
        val messageId: String,
        val chatId: String = "",
        val senderId: String = "",
        val fileKey: String = "",
        val fileName: String = "",
        val sha256: String? = null,
        val bytes: Long = 0L,
        /** 预处理配置指纹：autoExtract/preprocessCommand/xlogPrivateKey 的指纹。
         *  空串 = 尚未跑过（或上次失败）——复用命中「artifacts 一并复用」须 stamp 完整匹配。 */
        val preprocessStamp: String = "",
    )

    /** dir 绝对路径 → Entry（账本主结构）。 */
    private val ledger = ConcurrentHashMap<String, Entry>()

    /** 收录序（≈ FIFO 序），逐出的队头来源。 */
    private val insertionOrder = ConcurrentLinkedQueue<Entry>()

    /** 每发送者字节累计 / 全局字节累计。 */
    private val perSenderBytes = ConcurrentHashMap<String, AtomicLong>()
    private val totalBytes = AtomicLong(0L)

    /** 已逐出条目数（观测；同 PipelineCounters.quotaEvictions 的本地镜像，装配层二选一取数）。 */
    val evictions: AtomicLong = AtomicLong(0)

    // ---- 文件复用：messageId → 物理目录映射 + sha256 内容索引 ----
    /** messageId → 物理持有目录（同消息跨天/重启复用：目录名含日期，rescan 读 meta 重建）。 */
    private val recordedDirs = ConcurrentHashMap<String, RecordedDir>()

    /** 一次同消息复用命中的结果（目录 + 元数据）。 */
    data class RecordedDir(
        val dir: File,
        val meta: DirMeta,
    )

    /** sha256 → 物理持有目录（跨消息内容去重索引；value = dir 绝对路径）。 */
    private val contentIndex = ConcurrentHashMap<String, String>()

    /** 在途目录引用计数（pin/unpin；key = dir 绝对路径）。evictIfNeeded 跳过 pinned 目录。 */
    private val pinnedDirs = ConcurrentHashMap<String, Int>()

    /**
     * 每消息每文件的落盘目录：`<root>/downloads/<senderId>/<yyyy-MM-dd>/<msgId前12>_<safeName>/`。
     * @return null = `workspace.root` 空白（下载前置拒绝，调用方回「工作区未配置」失败）
     *
     * 🔴 senderId / messageId 必须先经
     * [DownloadPolicy.sanitizeDirSegment] 段级清洗——裸拼时 CLI `handle-event` 的自定义
     * JSON 可传 `../../../tmp/x` 穿出下载根（真实飞书侧是 `ou_*`/`om_*`，触发面在 CLI 侧）。
     *
     * 🔴 清洗后**无法产出有意义段名时返回 null**（拒绝下载）。
     * 清洗函数对空/纯点/纯下划线入参一律兜底成字面 `"file"`，多个不同 senderId
     * （`""` / `".."` / `"___"`）会混用同一物理目录，而账本按原始 senderId 记账
     * ⇒ 配额计数与实体错位（一方 evict 递归删目录会删掉另一方的文件且计数不回退）。
     */
    fun dirFor(senderId: String, messageId: String, fileName: String): File? {
        val root = configProvider().workspace.root.trim()
        if (root.isBlank()) return null
        val dateDir = LocalDate.now().format(DATE_FORMAT)
        val safeName = DownloadPolicy.sanitizeFileName(fileName)
        val safeSender = DownloadPolicy.sanitizeDirSegment(senderId) ?: return null
        val safeMessage = DownloadPolicy.sanitizeDirSegment(
            messageId.take(DownloadPolicy.MESSAGE_ID_PREFIX_CHARS),
        ) ?: return null
        return File(File(File(root, DownloadPolicy.DOWNLOADS_DIRNAME), safeSender), dateDir)
            .resolve("${safeMessage}_$safeName")
    }

    /**
     * 同消息复用查询（T2.1）：messageId → 已落盘目录 + meta（含 sha256/preprocessStamp）。
     * 一律 isFile 校验（目录被逐出/文件被删即自然失效，回落正常下载——索引自愈）。
     * @return 命中且主文件仍存在时返回目录与 meta；否则 null
     */
    fun recordedDirFor(messageId: String, fileName: String): RecordedDir? {
        val recorded = recordedDirs[messageId] ?: return null
        // 文件名维度校验：同消息可能多资源（多文件消息），meta 记录的是本目录持有的文件名
        if (recorded.meta.fileName != fileName) return null
        if (!File(recorded.dir, DownloadPolicy.sanitizeFileName(fileName)).isFile) return null
        return recorded
    }

    /**
     * 跨消息内容寻址查询（T2.2）：sha256 → 物理持有目录。
     * 一律 isFile 校验（自愈：被逐出即失效）。
     * @return 命中且原文件仍在时返回目录与 meta；否则 null
     */
    fun dirBySha256(sha256: String, fileName: String): RecordedDir? {
        val path = contentIndex[sha256] ?: return null
        val dir = File(path)
        val meta = readMeta(dir) ?: return null
        if (meta.fileName != fileName) return null
        if (!File(dir, DownloadPolicy.sanitizeFileName(fileName)).isFile) return null
        return RecordedDir(dir, meta)
    }

    /**
     * 落盘完成（含预处理产物）后记账：写 .meta.json sidecar + 递归测字节 → 进 FIFO 账本。
     * @param senderId 配额归属人（文件发送者）
     * @param meta 本目录的元数据（messageId/fileKey/fileName/sha256/preprocessStamp；调用方下载完成后组装）
     */
    fun recordStored(dir: File, senderId: String, meta: DirMeta = DirMeta(messageId = "")) {
        // T2.1：写 meta sidecar（失败不阻断记账——meta 缺失只损失复用能力，不损失账本）
        if (meta.messageId.isNotBlank()) {
            runCatching { writeMeta(dir, meta) }
                .onFailure { log.warn("$TAG meta sidecar 写入失败 dir=${dir.path}: ${it.message}") }
            recordedDirs[meta.messageId] = RecordedDir(dir, meta)
            meta.sha256?.takeIf { it.isNotBlank() }?.let { hash ->
                contentIndex[hash] = dir.absolutePath
            }
        }
        val bytes = runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
            .onFailure { log.warn("$TAG 测字节失败 dir=${dir.path}: ${it.message}") }
            .getOrDefault(0L)
        val entry = Entry(dir = dir, senderId = senderId, bytes = bytes, storedAtMillis = clock())
        val key = dir.absolutePath
        val old = ledger.put(key, entry)
        if (old != null) {
            insertionOrder.remove(old)
            perSenderBytes[old.senderId]?.addAndGet(-old.bytes)
            totalBytes.addAndGet(-old.bytes)
        }
        insertionOrder.add(entry)
        perSenderBytes.computeIfAbsent(senderId) { AtomicLong(0L) }.addAndGet(bytes)
        totalBytes.addAndGet(bytes)
    }

    /** 在途保护（T2.4）：agent 任务持有目录引用期间 pin；逐出跳过 pinned 目录。配对 unpin（finally）。 */
    fun pin(dir: File) {
        pinnedDirs.merge(dir.absolutePath, 1, Int::plus)
    }

    /** 解除在途保护（finally 调用；计数归零即删键——evict 判据是计数 > 0，非键存在性）。 */
    fun unpin(dir: File) {
        pinnedDirs.compute(dir.absolutePath) { _, v ->
            val next = (v ?: 1) - 1
            if (next > 0) next else null   // null ⇒ CHM 删除键
        }
    }

    /** 该目录是否在途保护中（计数 > 0）。 */
    private fun isPinned(dir: File): Boolean = (pinnedDirs[dir.absolutePath] ?: 0) > 0

    /**
     * 配额检查 + 逐出（同步实现，由调用方放协程里跑；幂等、非挂起阻塞但有界——
     * 每次至多逐出「超出部分 / 最小条目」数量的目录）。
     * 🔴 T2.4：跳过 pinned 目录（在途 agent 任务引用中——deleteRecursively 不查在途引用的竞态由 pin 闭合）。
     */
    fun evictIfNeeded() {
        val cfg = configProvider().fileMessage
        val perUserLimit = cfg.perUserQuotaBytes
        val totalLimit = cfg.totalQuotaBytes
        if (perUserLimit <= 0 && totalLimit <= 0) return

        // 1) 每用户超限：逐出该 sender 最旧条目（跳过 pinned；无可逐条目即停——pin 不是死循环挡板）
        if (perUserLimit > 0) {
            val senders = perSenderBytes.keys.toList()
            for (sender in senders) {
                while ((perSenderBytes[sender]?.get() ?: 0L) > perUserLimit) {
                    val victim = oldestOf(sender) ?: break
                    evict(victim)
                }
            }
        }
        // 2) 全局超限：跨 sender 从队头逐（🔴 跳过 pinned 项——evict 内部跳过后不回减，
        //    这里逐出「队头起第一个非 pinned 条目」防死循环：pinned 不挡后续更旧条目的逐出）
        if (totalLimit > 0) {
            while (totalBytes.get() > totalLimit) {
                val victim = insertionOrder.firstOrNull { !isPinned(it.dir) } ?: break
                evict(victim)
            }
        }
    }

    /** 从账本逐出一条：删目录 + 回减计数 + T2.2 按目录批量清内容索引/映射条目。失败 warn 不抛。 */
    private fun evict(entry: Entry) {
        // T2.4 在途保护：pinned 目录跳过（调用方 oldestOf/firstOrNull 已过滤——双保险直通）
        if (isPinned(entry.dir)) return
        val key = entry.dir.absolutePath
        if (ledger.remove(key) == null) return
        insertionOrder.remove(entry)
        val deleted = runCatching { entry.dir.deleteRecursively() }.getOrDefault(false)
        perSenderBytes[entry.senderId]?.addAndGet(-entry.bytes)
        totalBytes.addAndGet(-entry.bytes)
        // T2.2：索引同步清理（按目录批量——该目录可能被多条映射/索引项引用）
        recordedDirs.entries.removeIf { it.value.dir.absolutePath == key }
        contentIndex.entries.removeIf { it.value == key }
        evictions.incrementAndGet()
        log.info("$TAG 配额逐出 dir=${entry.dir.path} bytes=${entry.bytes} deleted=$deleted")
    }

    /** 指定 sender 的最旧条目（队头序线性扫描；条目量 = 消息数级，可接受；跳过 pinned）。 */
    private fun oldestOf(senderId: String): Entry? =
        insertionOrder.firstOrNull { it.senderId == senderId && !isPinned(it.dir) }

    /**
     * 启动重建：扫 `downloads/<senderId>/<date>/<msg-dir>` 三层目录重建账本（崩溃/重启后索引丢失的兜底），
     * 顺带删除历史版本 `downloads/tmp-*` 残留（「用完即删」模式的遗留，不入账本）。
     * 复用扩展：读每个 msg-dir 的 .meta.json 重建 recordedDirs 映射与 contentIndex 索引。
     */
    fun rescan() {
        val root = configProvider().workspace.root.trim()
        if (root.isBlank()) return
        val downloads = File(root, DownloadPolicy.DOWNLOADS_DIRNAME)
        if (!downloads.isDirectory) return

        // 1) 清 legacy tmp-*（旧 DownloadPolicy.tmpDirFor 的残留；上一代即删除未接线，这里补刀）
        downloads.listFiles { f -> f.isDirectory && f.name.startsWith(LEGACY_TMP_PREFIX) }
            .orEmpty().forEach { dir ->
                runCatching { dir.deleteRecursively() }
                log.info("$TAG rescan 清除 legacy 残留: ${dir.name}")
            }

        // 2) 重建账本（幂等：先清空再扫，防重复累计）——同时读 .meta.json 重建映射与索引
        clear()
        downloads.listFiles { f -> f.isDirectory }.orEmpty().forEach { senderDir ->
            val sender = senderDir.name
            senderDir.listFiles { f -> f.isDirectory }.orEmpty().forEach { dateDir ->
                dateDir.listFiles { f -> f.isDirectory }.orEmpty().forEach { msgDir ->
                    val meta = readMeta(msgDir)
                    if (meta != null && meta.messageId.isNotBlank()) {
                        recordedDirs[meta.messageId] = RecordedDir(msgDir, meta)
                        meta.sha256?.takeIf { it.isNotBlank() }?.let { hash ->
                            contentIndex[hash] = msgDir.absolutePath
                        }
                    }
                    runCatching { recordStored(msgDir, sender, meta ?: DirMeta(messageId = "")) }
                        .onFailure { log.warn("$TAG rescan 记账失败 dir=${msgDir.path}: ${it.message}") }
                }
            }
        }
        log.info("$TAG rescan 完成 entries=$size bytes=${totalBytes.get()} indexedMsg=${recordedDirs.size} indexedSha=${contentIndex.size}")
    }

    /** 读一个 per-message 目录的 .meta.json（缺失/损坏返回 null——legacy 目录形态）。 */
    internal fun readMeta(dir: File): DirMeta? {
        val metaFile = File(dir, META_FILE_NAME)
        if (!metaFile.isFile) return null
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString(DirMeta.serializer(), metaFile.readText())
        }.getOrNull()
    }

    /** 写 .meta.json（覆盖写；调用方保证 dir 已存在）。 */
    private fun writeMeta(dir: File, meta: DirMeta) {
        val metaFile = File(dir, META_FILE_NAME)
        metaFile.writeText(
            kotlinx.serialization.json.Json.encodeToString(DirMeta.serializer(), meta),
        )
    }

    /** 账本条目数 / 全局字节（测试与指标用）。 */
    val size: Int get() = ledger.size
    fun totalBytes(): Long = totalBytes.get()
    fun senderBytes(senderId: String): Long = perSenderBytes[senderId]?.get() ?: 0L

    /** 该目录是否已入账本（幂等复用判定：二次引用同文件不重复记账/重复触发配额检查）。 */
    fun isRecorded(dir: File): Boolean = ledger.containsKey(dir.absolutePath)

    /** 清空账本（rescan 与测试用；不删磁盘文件）。 */
    fun clear() {
        ledger.clear()
        insertionOrder.clear()
        perSenderBytes.clear()
        totalBytes.set(0L)
        recordedDirs.clear()
        contentIndex.clear()
    }

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(DownloadPolicy.DATE_PATTERN)

        /** legacy 临时目录前缀（旧 tmpDirFor 产物）。 */
        const val LEGACY_TMP_PREFIX: String = "tmp-"

        /** per-message 目录的元数据 sidecar 文件名（T2.1/T2.2）。 */
        const val META_FILE_NAME: String = ".meta.json"

        private const val TAG = "DownloadStore"
    }
}
