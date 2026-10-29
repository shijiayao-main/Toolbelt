// observe 审计域：审计 schema + 落盘 sink。
// 常驻 BufferedWriter + 批量 flush（每条记录 open→write→close 会在高频任务下反复付出 inode 分配代价）。
// 🔴 严禁复用 `BridgeJson`（其 prettyPrint=true 会破坏 jsonl 单行；classDiscriminator 会污染 schema）——用 [AuditJson]。
// 🔴 字段 schema 与用户存量 `~/.toolbelt/lark/audit.jsonl` 双向兼容：
//    存量数据必须能被新读取器解析，新写入的行也必须能按旧 schema 读回。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.AuditJson
import com.jiaoay.lark.bridge.exec.Paths
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * 🔒 审计记录（JSONL 单行对象）。**前 11 个字段名/类型/可空性/默认值与存量 audit.jsonl 的
 * 旧 schema 逐字一致**；`attachments` 为产物回传新增的可空字段（与 backend/kind 同为
 * 「后加字段」惯例——旧格式行无此键 decode 为 null，双向兼容不破坏）。
 * `userMessageId` 尾部后加（同惯例三件套：默认 null + 空值不产键 +
 * [AuditJson] ignoreUnknownKeys 旧行兼容）；`delegatedFromBackend`（委托嵌套标记）同款尾部后加。
 *
 * 独立于 UI 日志台（环形缓冲会丢历史），出事后唯一可复盘的凭据。
 *
 * 命名加 `Json` 后缀是为了与 [SinkRecord.Audit]（通道内的传输形态）区分：
 * 本类是**磁盘 schema**，[SinkRecord.Audit] 是**内存 seam**；两者字段一一对应，由 [toEntry] 转换。
 */
@Serializable
data class AuditEntryJson(
    /** 事件时间（epoch millis） */
    val ts: Long,
    /** 会话 chatId */
    val chatId: String,
    /** 发送者 openId */
    val senderOpenId: String,
    /** prompt 摘要（前 200 字符） */
    val prompt: String,
    /** 渲染后命令摘要（argv 空格连接，单元素截断） */
    val command: String,
    /** 结果状态：SUCCESS / FAILURE / SKIPPED（🔒 = `TaskStatus.name`，大写） */
    val status: String,
    /** 失败错误码（可空） */
    val errorCode: String? = null,
    /** 进程退出码（未启动为 null） */
    val exitCode: Int? = null,
    /** 总耗时（毫秒） */
    val elapsedMillis: Long,
    /** agent 后端类型（claude/qoder/copilot/zcode/custom/koog）；旧格式记录无此键，decode 为 null（宿主侧归入"未知"） */
    val backend: String? = null,
    /** 路由类型标签（TaskKind.name：COMMAND/SKILL/AGENT）；旧格式记录无此键，decode 为 null（宿主侧归入"未知"） */
    val kind: String? = null,
    /** 产物回传成功清单；旧格式记录无此键，decode 为 null */
    val attachments: List<String>? = null,
    /** 产物回传被拒清单（含路径与拒绝原因，如 `<路径>  <!-- 超出数量上限 5 -->`）；旧格式记录无此键，decode 为 null */
    val rejectedArtifacts: List<String>? = null,
    /** 发送者用户名；空串落 null 不产空键；旧格式记录无此键，decode 为 null */
    val userName: String? = null,
    /** 用户原消息 id（最近任务表情回显查询锚点）；空串落 null 不产空键；旧格式记录无此键，decode 为 null */
    val userMessageId: String? = null,
    /** 发起委托的父 backend id（嵌套标记：非委托记录为 null）；旧格式记录无此键，decode 为 null */
    val delegatedFromBackend: String? = null,
)

/** [SinkRecord.Audit]（内存 seam）→ [AuditEntryJson]（磁盘 schema）。纯函数、零拷贝语义变化。 */
fun SinkRecord.Audit.toEntry(): AuditEntryJson = AuditEntryJson(
    ts = ts,
    chatId = chatId,
    senderOpenId = senderOpenId,
    prompt = prompt,
    command = command,
    status = status,
    errorCode = errorCode,
    exitCode = exitCode,
    elapsedMillis = elapsedMillis,
    backend = backend,
    kind = kind,
    attachments = attachments,
    rejectedArtifacts = rejectedArtifacts,
    userName = userName,
    userMessageId = userMessageId,
    delegatedFromBackend = delegatedFromBackend,
)

/**
 * 审计域常量与纯函数。
 *
 * 本 object 体内只有 `const val` 与纯函数，无可变全局状态。
 */
object AuditRecords {

    /** 🔒 审计文件名。 */
    const val FILE_NAME: String = SinkRecord.AUDIT_FILE_NAME

    /** 🔒 prompt 摘要截断长度。 */
    const val PROMPT_SUMMARY_CHARS: Int = SinkRecord.PROMPT_SUMMARY_CHARS

    /** 🔒 命令摘要单元素截断长度。⚠️ **不是 200**——按 200 实现会让命令摘要超长 4 倍。 */
    const val CMD_SUMMARY_CHARS: Int = SinkRecord.CMD_SUMMARY_CHARS

    /**
     * 🔒 命令摘要：argv 空格连接、单元素截断、换行压平。
     *
     * ⚠️ `ellipsis = ""` 是**实测定稿**：存量 `audit.jsonl` 的 command 字段结尾没有 `…`。
     * 加上省略号会让同一命令在新旧行里形态不同。
     */
    fun summarizeCommand(command: List<String>): String =
        command.joinToString(" ") { StringTruncation.truncate(it, CMD_SUMMARY_CHARS, ellipsis = "").replace('\n', ' ') }

    /** 🔒 prompt 摘要（`ellipsis = ""`，按码点截断到 200）。 */
    fun summarizePrompt(prompt: String): String =
        StringTruncation.truncate(prompt, PROMPT_SUMMARY_CHARS, ellipsis = "")

    /** 审计 sink 的编码器（类型不匹配返回 null ⇒ 由 [JsonlSink] 计 `typeMismatchDropped`，不静默吞）。 */
    fun encoder(): JsonlEncoder = JsonlEncoder { record ->
        (record as? SinkRecord.Audit)?.let { AuditJson.encodeToString(AuditEntryJson.serializer(), it.toEntry()) }
    }

    /** 解析审计文件所在目录 → 文件；[dir] blank 返回 null（= 禁用审计，与旧 `AuditLogger` 语义一致）。 */
    fun resolveFile(dir: String): File? = JsonlSink.resolveFile(dir, FILE_NAME)
}

/**
 * JSONL 行编码器（[JsonlSink] 的唯一「记录 → 单行文本」出口）。
 * 返回 null 表示「记录类型与本 sink 不匹配」，由 sink 计数后跳过——不静默丢弃。
 */
fun interface JsonlEncoder {
    fun encode(record: SinkRecord): String?
}

/**
 * 常驻句柄 + 体积轮转的 JSONL 追加写入器。
 *
 * ## 运行形态
 * | 维度 | 行为 |
 * |---|---|
 * | 句柄 | **常驻** [BufferedWriter]，`close()` 才释放 |
 * | flush | **批量**：64 条 或 500ms（由 [JsonlSink] 驱动） |
 * | 轮转 | 8MB × 3（`audit.jsonl` → `.1` → `.2`，`.2` 删除） |
 * | 失败 | 旁路语义：warn + 计数，另加 `writeFailures` 计数 + **丢弃坏句柄重开** |
 *
 * ## 单写者前提
 * 本类**不是**线程安全的：它只被一个协程（跑在 `dispatchers.sink` = `IO.limitedParallelism(1)`）驱动。
 * 单写者 ⇒ 无需文件锁、天然有序；因此内部用普通 `private var` 而非原子量（实例字段不属
 * object/companion/顶层的可变全局状态禁令范围）。
 *
 * @param file 目标文件；null ⇒ **禁用**（`writeLine` 直接返回 false，不建目录不开句柄）
 * @param rotationBytes 轮转阈值字节（默认 8MB，来自 `observability.auditRotationMb`）
 * @param keepFiles 保留文件总数（含当前文件；默认 3，来自 `observability.auditKeepFiles`）
 */
class JsonlRotatingWriter(
    private val file: File?,
    private val rotationBytes: Long = DEFAULT_ROTATION_BYTES,
    private val keepFiles: Int = DEFAULT_KEEP_FILES,
    private val log: BridgeLog = BridgeLog.Silent,
) : AutoCloseable {

    /** 是否启用（[file] 非 null）。 */
    val enabled: Boolean get() = file != null

    private var writer: BufferedWriter? = null
    private var counter: CountingOutputStream? = null

    /** 打开句柄时文件已有的字节数（续写场景下必须计入轮转判定，否则轮转周期被重置）。 */
    private var existingBytes: Long = 0L

    /** flush 次数（可观测）。 */
    var flushCount: Long = 0L
        private set

    /** 轮转次数（可观测）。 */
    var rotationCount: Long = 0L
        private set

    /** IO 写入/flush 失败次数（失败即丢弃，必须计数）。 */
    var writeFailures: Long = 0L
        private set

    /** 自上次轮转以来写入的字节数（含开句柄时文件已有字节）。 */
    val bytesSinceRotation: Long get() = existingBytes + (counter?.count ?: 0L)

    /**
     * 追加一行（自动补 `\n`）。
     * @return true ⇒ 已写入缓冲（**未必已落盘**，由 [flush] 保证）；false ⇒ 禁用态或 IO 失败（已计数 + warn）
     */
    fun writeLine(line: String): Boolean {
        val target = file ?: return false
        return runCatching {
            val out = ensureWriter(target)
            out.write(line)
            out.newLine()
            if (bytesSinceRotation >= rotationBytes) rotate(target)
            true
        }.onFailure { e -> onFailure(target, e) }.getOrDefault(false)
    }

    /** 把缓冲落盘。幂等；未开过句柄（零记录）时是 no-op 且返回 true。 */
    fun flush(): Boolean {
        val out = writer ?: return true
        return runCatching {
            out.flush()
            flushCount++
            true
        }.onFailure { e -> onFailure(file ?: return true, e) }.getOrDefault(false)
    }

    /** 关闭句柄（幂等）。关闭前先 flush，尽力把在途缓冲落盘。 */
    override fun close() {
        val out = writer ?: return
        writer = null
        counter = null
        existingBytes = 0L
        runCatching {
            out.flush()
            out.close()
        }.onFailure { e -> log.warn("$TAG close failed: ${file?.path} ${e.message}") }
    }

    private fun ensureWriter(target: File): BufferedWriter {
        writer?.let { return it }
        target.parentFile?.mkdirs()
        existingBytes = runCatching { target.length() }.getOrDefault(0L)
        val counting = CountingOutputStream(FileOutputStream(target, /* append = */ true))
        counter = counting
        return BufferedWriter(OutputStreamWriter(counting, Charsets.UTF_8), BUFFER_CHARS).also { writer = it }
    }

    /**
     * 体积轮转：`audit.jsonl` → `audit.jsonl.1` → `audit.jsonl.2`（第 [keepFiles] 个被删）。
     * 🔴 从最旧的备份往前挪，避免 `renameTo` 覆盖语义在 Windows 上失败。
     */
    private fun rotate(target: File) {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        counter = null
        var index = (keepFiles - 1).coerceAtLeast(1)
        while (index >= 1) {
            val older = rotationFile(target, index)
            val newer = if (index == 1) target else rotationFile(target, index - 1)
            if (older.exists()) runCatching { older.delete() }
            if (newer.exists()) runCatching { newer.renameTo(older) }
            index--
        }
        existingBytes = 0L
        rotationCount++
        log.info("$TAG rotated: ${target.name} threshold=${rotationBytes}B keep=$keepFiles total=$rotationCount")
    }

    private fun onFailure(target: File, e: Throwable) {
        writeFailures++
        log.warn("$TAG write failed（审计是旁路，不影响主链路）: ${target.path} ${e.message}")
        // 句柄可能已坏（磁盘满/被外部删除）：丢弃它，下次 ensureWriter 重开。
        //    常驻句柄必须显式自愈，否则一次 IO 异常会让后续全部记录静默丢失。
        runCatching { writer?.close() }
        writer = null
        counter = null
        existingBytes = 0L
    }

    /** 零分配字节计数器（轮转判定需要真实 UTF-8 字节数，`String.length` 对中文会低估 3 倍）。 */
    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len.toLong()
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    companion object {
        private const val TAG = "JsonlRotatingWriter"

        /** 🔒 8MB（方案「8MB×3 轮转」；对应 `observability.auditRotationMb=8`）。 */
        const val DEFAULT_ROTATION_BYTES: Long = 8L * 1024 * 1024

        /** 🔒 3 个文件（对应 `observability.auditKeepFiles=3`；含当前文件 ⇒ 磁盘上限 24MB）。 */
        const val DEFAULT_KEEP_FILES: Int = 3

        /** BufferedWriter 字符缓冲（8K 字符 ≈ 24KB UTF-8 中文）。 */
        const val BUFFER_CHARS: Int = 8 * 1024

        /** 轮转备份文件：`<name>.<index>`（index 从 1 起，1 = 最近一次轮转出的旧文件）。 */
        fun rotationFile(base: File, index: Int): File = File(base.parentFile, "${base.name}.$index")
    }
}

/**
 * JSONL 落盘消费者（有界内存链路第 4 行的消费端）。
 *
 * ## 运行契约
 * 1. **必须跑在 `dispatchers.sink`**（`IO.limitedParallelism(1, "bridge-sink")`）：单写者 ⇒ 无需文件锁；
 *    装配点为 `runtime.auditSinkScope.launch { auditSink.run() }`（作用域树里的 `auditSinkJob`）。
 * 2. **批量 flush**：满 [flushBatchSize]=64 条 或 距上次 flush 超 [flushIntervalMillis]=500ms 即 flush；
 *    **无待写数据时不带超时地挂起**（`pending == 0` 分支）⇒ **Idle 态零 CPU 唤醒**。
 * 3. **收尾**：通道 `close()` 且排空后 `run()` 正常返回；返回前在 [NonCancellable] 内 flush + close，
 *    保证 `stop()` 取消 sink 协程时**在途的最后一批审计不丢**。
 * 4. 🔴 **绝不向调用方抛异常**：审计/统计是旁路，任何 IO 失败只 warn + 计数。
 * 5. 🔴 **三类丢弃全部计数可观测**——[writeDropped]（IO 失败）、[typeMismatchDropped]（编码器不匹配）、
 *    加上通道侧的 `SinkChannels.auditDropped`（DROP_OLDEST 溢出），由 [droppedTotal] 汇总前两类。
 *
 * @param encoder 记录 → 单行 JSON（返回 null ⇒ 类型不匹配，计入 [typeMismatchDropped]）
 * @param receive 取一条记录；**返回 null 表示通道已关闭且排空**（`SinkChannels.receiveAudit/receiveStats` 契约）
 */
class JsonlSink(
    private val writer: JsonlRotatingWriter,
    private val encoder: JsonlEncoder,
    private val receive: suspend () -> SinkRecord?,
    private val log: BridgeLog = BridgeLog.Silent,
    private val flushIntervalMillis: Long = FLUSH_INTERVAL_MILLIS,
    private val flushBatchSize: Int = FLUSH_BATCH_SIZE,
    private val where: String = WHERE_UNKNOWN,
) {

    /** 成功编码并写入缓冲的记录数。 */
    var writtenTotal: Long = 0L
        private set

    /** IO 失败而丢弃的记录数。 */
    var writeDropped: Long = 0L
        private set

    /** 类型不匹配而跳过的记录数（正常装配下恒为 0，非 0 说明接线错了）。 */
    var typeMismatchDropped: Long = 0L
        private set

    /** 本 sink 侧丢弃总数（不含通道 DROP_OLDEST 溢出，那部分在 `SinkChannels.auditDropped`）。 */
    val droppedTotal: Long get() = writeDropped + typeMismatchDropped

    /** `run()` 是否已正常收尾（通道关闭且排空）。 */
    var finished: Boolean = false
        private set

    /** flush 次数（转发 [JsonlRotatingWriter.flushCount]）。 */
    val flushCount: Long get() = writer.flushCount

    /** 轮转次数。 */
    val rotationCount: Long get() = writer.rotationCount

    /** IO 失败次数。 */
    val writeFailures: Long get() = writer.writeFailures

    /** 是否启用（目录 blank ⇒ false，此时 `run()` 只排空通道并计数，不建文件）。 */
    val enabled: Boolean get() = writer.enabled

    /**
     * 消费循环。🔴 恒不抛（内部全部 `runCatching`）；被取消时在 [NonCancellable] 内收尾落盘。
     */
    suspend fun run() {
        var pending = 0
        try {
            while (true) {
                val hadPending = pending > 0
                // 无待写数据时**不带超时**地挂起 ⇒ Idle 零唤醒；
                //    有待写数据时才用 500ms 超时驱动「批量窗口到期」的定时 flush。
                val record: SinkRecord? = if (hadPending) {
                    withTimeoutOrNull(flushIntervalMillis) { receive() }
                } else {
                    receive()
                }
                if (record == null) {
                    if (hadPending) {
                        // 500ms 内无新记录 ⇒ 批量窗口到期，定时 flush 后继续等
                        writer.flush()
                        pending = 0
                        continue
                    }
                    // 无待写数据时 receive() 返回 null 只可能是「通道已关闭且排空」⇒ 正常收尾
                    break
                }
                val line = runCatching { encoder.encode(record) }
                    .onFailure { e -> log.warn("$TAG encode failed where=$where: ${e.message}") }
                    .getOrNull()
                if (line == null) {
                    typeMismatchDropped++
                    continue
                }
                if (!writer.writeLine(line)) {
                    writeDropped++
                    continue
                }
                writtenTotal++
                pending++
                if (pending >= flushBatchSize) {
                    writer.flush()
                    pending = 0
                }
            }
        } finally {
            // 🔴 stop() 会取消 sink 协程；在 NonCancellable 内把在途缓冲落盘再关句柄（不丢最后一批）。
            withContext(NonCancellable) {
                runCatching { if (pending > 0) writer.flush() }
                runCatching { writer.close() }
                finished = true
            }
        }
    }

    companion object {
        private const val TAG = "JsonlSink"

        /** 批量 flush 的时间窗（方案表逐字：500ms）。 */
        const val FLUSH_INTERVAL_MILLIS: Long = 500L

        /** 批量 flush 的条数阈（方案表逐字：64 条）。 */
        const val FLUSH_BATCH_SIZE: Int = 64

        /** `where` 字面值（审计通道；对齐 `SinkChannels.AUDIT_WHERE`）。 */
        const val WHERE_AUDIT: String = SinkChannels.AUDIT_WHERE

        /** `where` 字面值（统计通道）。 */
        const val WHERE_STATS: String = SinkChannels.STATS_WHERE

        /** `where` 兜底字面值（自建 sink 时未指定）。 */
        const val WHERE_UNKNOWN: String = "unknown-sink"

        /**
         * 目录 + 文件名 → 目标文件；[dir] blank 返回 null（= 禁用）。
         * 🔴 `~` 前缀展开对齐写入侧（读与写必须用同一展开，否则读写找不到同一文件）。
         */
        fun resolveFile(dir: String, fileName: String): File? =
            dir.takeIf { it.isNotBlank() }?.let { File(Paths.expandTilde(it.trim()), fileName) }

        /** 审计 sink 装配（消费 [SinkChannels.receiveAudit]）。 */
        fun audit(
            channels: SinkChannels,
            dir: String,
            rotationMb: Int = DEFAULT_ROTATION_MB,
            keepFiles: Int = JsonlRotatingWriter.DEFAULT_KEEP_FILES,
            log: BridgeLog = BridgeLog.Silent,
        ): JsonlSink = JsonlSink(
            writer = JsonlRotatingWriter(
                file = resolveFile(dir, SinkRecord.AUDIT_FILE_NAME),
                rotationBytes = rotationBytes(rotationMb),
                keepFiles = keepFiles,
                log = log,
            ),
            encoder = AuditRecords.encoder(),
            receive = { channels.receiveAudit() },
            log = log,
            where = WHERE_AUDIT,
        )

        /** 发送者统计 sink 装配（消费 [SinkChannels.receiveStats]）。 */
        fun stats(
            channels: SinkChannels,
            dir: String,
            rotationMb: Int = DEFAULT_ROTATION_MB,
            keepFiles: Int = JsonlRotatingWriter.DEFAULT_KEEP_FILES,
            log: BridgeLog = BridgeLog.Silent,
        ): JsonlSink = JsonlSink(
            writer = JsonlRotatingWriter(
                file = resolveFile(dir, SinkRecord.SENDER_STATS_FILE_NAME),
                rotationBytes = rotationBytes(rotationMb),
                keepFiles = keepFiles,
                log = log,
            ),
            encoder = SenderStatsRecords.encoder(),
            receive = { channels.receiveStats() },
            log = log,
            where = WHERE_STATS,
        )

        /** `observability.auditRotationMb` → 字节；`<= 0` 视为不轮转（[Long.MAX_VALUE]）。 */
        fun rotationBytes(rotationMb: Int): Long =
            if (rotationMb <= 0) Long.MAX_VALUE else rotationMb.toLong() * 1024 * 1024

        /** 默认轮转体积（MB），对应 `observability.auditRotationMb=8`。 */
        const val DEFAULT_ROTATION_MB: Int = 8
    }
}
