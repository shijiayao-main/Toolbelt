// observe：JSONL 尾部读取器。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.config.AuditJson
import com.jiaoay.lark.bridge.exec.Paths
import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.DeserializationStrategy

/** 一次尾部读取的结果（扫描量、损坏行数、是否被截断全部可观测，不静默）。 */
data class JsonlReadResult<T>(
    /** 命中的记录，**按文件顺序（旧 → 新）** 排列。 */
    val records: List<T>,
    /** 实际扫描的行数（含损坏行）。 */
    val linesScanned: Int,
    /** 解析失败被跳过的行数（🔴 可观测——上游不计数就无从发现解析质量劣化）。 */
    val damagedLines: Int,
    /** 是否因触到 [JsonlTailReader.MAX_RECORDS] 上限而提前停止（文件里还有更早的记录没读）。 */
    val truncated: Boolean,
    /** 实际从磁盘读取的字节数（用于验证「与文件总大小无关」）。 */
    val bytesRead: Long,
) {
    val size: Int get() = records.size
}

/**
 * JSONL 尾部读取器（**从文件末尾反向分块扫描**）。
 *
 * ## 为什么是反向读
 * UI 与 CLI 只关心「最近的 N 条」；正向 `readLines()` 必须先读完整个文件才能拿到尾部。
 * 反向扫描以 [TAIL_CHUNK_BYTES]=64KB 为单位从 `length()` 往回读，命中 [maxRecords] 条或 `ts < cutoff`
 * 立即停止 ⇒ **读取成本 = O(命中的记录数)**，与文件总大小解耦。
 * 本 object 体内只有 `const val` 与纯函数，无可变全局状态。
 */
object JsonlTailReader {

    /** 反向扫描的分块大小（方案表逐字：64KB）。 */
    const val TAIL_CHUNK_BYTES: Int = 64 * 1024

    /** 单次读取的记录上限（方案表逐字：20000）。 */
    const val MAX_RECORDS: Int = 20_000

    private val NEWLINE: Byte = '\n'.code.toByte()
    private val EMPTY_BYTES = ByteArray(0)

    /**
     * 读取 `ts >= cutoff` 的记录（旧 → 新）。
     *
     * @param cutoff 时间下界（epoch millis）；`0L` = 不设下界（读满 [maxRecords] 为止）
     * @param maxRecords 记录上限（同时是**扫描行数**上限：损坏行也占额度，防止病态文件导致无限扫描）
     * @param deserializer 行 → 记录（必须用 `ignoreUnknownKeys = true` 的配方，见 [AuditJson]）
     * @param tsOf 记录 → `ts`（用于 cutoff 早停：一旦读到早于 cutoff 的记录即停止，因为更早的必然也早于它）
     */
    fun <T> readSince(
        file: File,
        cutoff: Long,
        maxRecords: Int = MAX_RECORDS,
        deserializer: DeserializationStrategy<T>,
        tsOf: (T) -> Long,
    ): JsonlReadResult<T> {
        val limit = maxRecords.coerceAtLeast(1)
        // 反向扫描得到的是「新 → 旧」，先倒序收集，最后整体反转成文件顺序
        val newestFirst = ArrayList<T>(minOf(limit, 512))
        var damaged = 0
        var limitReached = false
        val scan = scanTail(file, limit) { line ->
            val parsed = runCatching { AuditJson.decodeFromString(deserializer, line) }.getOrNull()
            if (parsed == null) {
                damaged++
                true // 损坏行跳过但继续
            } else if (cutoff > 0L && tsOf(parsed) < cutoff) {
                false // 🔴 早停：追加写 ⇒ 更早的记录 ts 只会更小
            } else {
                newestFirst.add(parsed)
                limitReached = newestFirst.size >= limit
                !limitReached
            }
        }
        newestFirst.reverse()
        return JsonlReadResult(
            records = newestFirst,
            linesScanned = scan.linesVisited,
            damagedLines = damaged,
            // 🔴 两类「没读完」必须区分：
            //    ① 记录上限截断（`limitReached` 且磁盘上还有没读到的字节）⇒ `truncated = true`；
            //    ② cutoff 早停（调用方只要这段时间窗）⇒ **不算**截断。
            //    `block` 返回 false 时 `scanTail` 无法区分两者，故在此用「已读字节 < 文件体积」判定。
            truncated = scan.truncated || (limitReached && scan.bytesRead < runCatching { file.length() }.getOrDefault(0L)),
            bytesRead = scan.bytesRead,
        )
    }

    /** 读取审计文件里 `ts >= cutoff` 的记录（旧 → 新）。 */
    fun readAuditSince(
        file: File,
        cutoff: Long = 0L,
        maxRecords: Int = MAX_RECORDS,
    ): JsonlReadResult<AuditEntryJson> = readSince(
        file = file,
        cutoff = cutoff,
        maxRecords = maxRecords,
        deserializer = AuditEntryJson.serializer(),
        tsOf = { it.ts },
    )

    /** 读取统计文件里 `ts >= cutoff` 的记录（旧 → 新）。 */
    fun readStatsSince(
        file: File,
        cutoff: Long = 0L,
        maxRecords: Int = MAX_RECORDS,
    ): JsonlReadResult<SenderStatsEntry> = readSince(
        file = file,
        cutoff = cutoff,
        maxRecords = maxRecords,
        deserializer = SenderStatsEntry.serializer(),
        tsOf = { it.ts },
    )

    /**
     * 读取审计文件全量的等价（宿主聚合展示用），但**内存有界**（最多 [maxRecords] 条、
     * 最多从尾部读满这些条所需的字节）。文件不存在 / 目录 blank ⇒ 空结果。
     * 🔴 `~` 前缀展开对齐写入侧（读取不展开会找不到文件）。
     */
    fun readAuditAll(dir: String, maxRecords: Int = MAX_RECORDS): JsonlReadResult<AuditEntryJson> =
        resolveFile(dir, AuditRecords.FILE_NAME)?.let { readAuditSince(it, 0L, maxRecords) } ?: emptyResult()

    /** 读取统计文件全量（同 [readAuditAll]）。 */
    fun readStatsAll(dir: String, maxRecords: Int = MAX_RECORDS): JsonlReadResult<SenderStatsEntry> =
        resolveFile(dir, SenderStatsRecords.FILE_NAME)?.let { readStatsSince(it, 0L, maxRecords) } ?: emptyResult()

    /** 只读原始行（不解析）；供 CLI `audit tail` 与双向兼容测试使用。 */
    fun readTailLines(file: File, maxLines: Int = MAX_RECORDS): List<String> {
        val lines = ArrayList<String>(minOf(maxLines.coerceAtLeast(1), 512))
        scanTail(file, maxLines.coerceAtLeast(1)) { line ->
            lines.add(line)
            lines.size < maxLines.coerceAtLeast(1)
        }
        lines.reverse()
        return lines
    }

    /** 目录 + 文件名 → 文件；blank 返回 null（与 [JsonlSink.resolveFile] 同一套语义）。 */
    fun resolveFile(dir: String, fileName: String): File? = JsonlSink.resolveFile(dir, fileName)

    /** `~` 展开后再解析（CLI 传入 `~/.toolbelt/lark` 形态时用）。 */
    fun expand(dir: String): String = Paths.expandTilde(dir.trim())

    private fun <T> emptyResult(): JsonlReadResult<T> =
        JsonlReadResult(emptyList(), 0, 0, truncated = false, bytesRead = 0L)

    private class TailScan(val linesVisited: Int, val bytesRead: Long, val truncated: Boolean)

    /**
     * 从文件尾反向扫描，把每一行（**新 → 旧**）交给 [block]；[block] 返回 false 即停止。
     * @param maxLines 行数硬上限（触到即停止并置 `truncated = true`）
     */
    private fun scanTail(file: File, maxLines: Int, block: (String) -> Boolean): TailScan {
        if (!file.isFile) return TailScan(0, 0L, truncated = false)
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) return TailScan(0, 0L, truncated = false)
        var linesVisited = 0
        var bytesRead = 0L
        var truncated = false
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                var pos = raf.length()
                var carry = EMPTY_BYTES
                var stop = false
                while (pos > 0L && !stop) {
                    val chunkSize = minOf(TAIL_CHUNK_BYTES.toLong(), pos).toInt()
                    pos -= chunkSize
                    val buf = ByteArray(chunkSize)
                    raf.seek(pos)
                    raf.readFully(buf)
                    bytesRead += chunkSize
                    // 从块尾往块首扫 '\n'：每命中一个就产出一条完整行
                    var end = chunkSize
                    var i = chunkSize - 1
                    while (i >= 0) {
                        if (buf[i] == NEWLINE) {
                            val line = decode(buf, i + 1, end - (i + 1), carry)
                            carry = EMPTY_BYTES
                            if (line.isNotBlank()) {
                                linesVisited++
                                if (!block(line)) {
                                    stop = true
                                    break
                                }
                                if (linesVisited >= maxLines) {
                                    // 还有更早的字节没读 ⇒ 截断
                                    truncated = pos > 0L || i > 0
                                    stop = true
                                    break
                                }
                            }
                            end = i
                        }
                        i--
                    }
                    // 块内第一个 '\n' 之前的字节 = 某条更早行的**尾部**，以字节形态累积（防切断多字节字符）
                    if (!stop) carry = prepend(buf, 0, end, carry)
                }
                // 文件首行可能不以 '\n' 结尾（carry 拼出最后一行时也覆盖此形态）
                if (!stop && pos == 0L && carry.isNotEmpty()) {
                    val line = String(carry, Charsets.UTF_8)
                    if (line.isNotBlank()) {
                        linesVisited++
                        block(line)
                    }
                }
            }
        }
        return TailScan(linesVisited, bytesRead, truncated)
    }

    /** `buf[off, off+len)` + `carry` 一次性解码为 UTF-8 字符串（carry 是文件里更靠后的字节）。 */
    private fun decode(buf: ByteArray, off: Int, len: Int, carry: ByteArray): String =
        if (carry.isEmpty()) {
            String(buf, off, len, Charsets.UTF_8)
        } else {
            val merged = ByteArray(len + carry.size)
            System.arraycopy(buf, off, merged, 0, len)
            System.arraycopy(carry, 0, merged, len, carry.size)
            String(merged, 0, merged.size, Charsets.UTF_8)
        }

    /** 把 `buf[off, off+len)` 前插到 [carry]（结果 = 更早的字节 + 已有的 carry）。 */
    private fun prepend(buf: ByteArray, off: Int, len: Int, carry: ByteArray): ByteArray {
        if (len <= 0) return carry
        val merged = ByteArray(len + carry.size)
        System.arraycopy(buf, off, merged, 0, len)
        System.arraycopy(carry, 0, merged, len, carry.size)
        return merged
    }
}
