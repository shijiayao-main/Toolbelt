package com.jiaoay.easy_dev_toolbox.page.tools.hash

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 哈希/校验和计算引擎（纯函数面，[MessageDigest] + `java.util.zip.CRC32` 零第三方依赖）。
 *
 * ## 动机
 * 固件包/日志包核对场景：官网或同事给的是 MD5/SHA-256 校验值，本地要算同样口径
 * 的哈希对上；CRC32 是传输校验单字口径。MD5/SHA-1 是弱哈希，仅用于核对场景的
 * 对照口径，不用于安全用途。
 *
 * ## 契约
 * - [hashText]：按 UTF-8 字节摘要，回小写十六进制（`md5sum`/`sha256sum` 同口径）；
 *   文本超过 [MAX_TEXT_HASH_CHARS] 时面板侧拒绝（本函数仍会执行——上限防线在面板，
 *   引擎不猜 UI 语义）。
 * - [hashFile]：64KB 分块流式摘要（大文件不整读入内存）；路径为空/文件不存在/
 *   读取失败均显式 [HashFileResult.Failure] 携带可读 reason，不静默。
 * - 输出长度恒定：MD5 32 位、SHA-1 40 位、SHA-256 64 位、CRC32 8 位（零填充）。
 *
 * ## 线程模型
 * [hashFile] 是阻塞文件 IO——调用方负责调度（面板在 Dispatchers.IO 上调用，
 * AdbPathSetting 同款配方），本函数不自起线程。
 */

/** 四算法档；[label] 同时是 [MessageDigest.getInstance] 的算法名（CRC32 除外） */
enum class HashAlgorithm(val label: String) {
    MD5("MD5"),
    SHA_1("SHA-1"),
    SHA_256("SHA-256"),
    CRC32("CRC32"),
}

/** 文件哈希结果：显式两分支，失败不静默 */
sealed interface HashFileResult {

    /** [hashes] 键序 = 调用方传入的算法序；[byteCount] 为实际读取字节数 */
    data class Ok(val hashes: Map<HashAlgorithm, String>, val byteCount: Long) : HashFileResult

    /** 显式失败：reason 是可直接呈现给人的失败原因 */
    data class Failure(val reason: String) : HashFileResult
}

/** 文本哈希的字符上限：超出由面板拒绝并明示（超长文本逐键摘要阻塞 UI 线程） */
const val MAX_TEXT_HASH_CHARS: Int = 8 * 1024 * 1024

/** 文件分块读取大小：64KB（流式摘要的常规档，大小文件折中） */
private const val FILE_CHUNK_BYTES: Int = 64 * 1024

/** 文本摘要：UTF-8 字节 → 小写十六进制 */
fun hashText(text: String, algorithm: HashAlgorithm): String =
    hashBytes(text.toByteArray(Charsets.UTF_8), algorithm)

/**
 * 文件流式摘要：一次遍历同时算全部请求算法（每块只读一次，多算法不多次读盘）。
 * 默认全四档——核对场景通常要同时对多个口径。
 */
fun hashFile(
    path: String,
    algorithms: List<HashAlgorithm> = HashAlgorithm.entries.toList(),
): HashFileResult {
    if (path.isBlank()) return HashFileResult.Failure("文件路径为空")
    val trimmed = path.trim()
    val file = File(trimmed)
    if (!file.isFile) {
        return HashFileResult.Failure("文件不存在或不是普通文件：$trimmed")
    }
    val digesters = algorithms.map { it to StreamDigester(it) }
    val buffer = ByteArray(FILE_CHUNK_BYTES)
    var total = 0L
    try {
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for ((_, digester) in digesters) digester.update(buffer, read)
                total += read
            }
        }
    } catch (e: IOException) {
        return HashFileResult.Failure("读取失败（$trimmed）：${e.message ?: "IO 异常"}")
    } catch (e: SecurityException) {
        return HashFileResult.Failure("无权读取（$trimmed）：${e.message ?: "访问被拒绝"}")
    }
    return HashFileResult.Ok(digesters.associate { (algorithm, digester) -> algorithm to digester.finishHex() }, total)
}

/** 单次字节摘要（[hashText] 与文件末块共用同一摘要收口，口径单源） */
private fun hashBytes(bytes: ByteArray, algorithm: HashAlgorithm): String {
    val digester = StreamDigester(algorithm)
    digester.update(bytes, bytes.size)
    return digester.finishHex()
}

/**
 * 分块摘要器：CRC32 走 `java.util.zip.CRC32`，其余走 [MessageDigest]。
 * 单实例非线程安全——每次哈希新建（[hashFile]/[hashText] 局部持有）。
 */
private class StreamDigester(algorithm: HashAlgorithm) {

    private val digest: MessageDigest? =
        if (algorithm == HashAlgorithm.CRC32) null else MessageDigest.getInstance(algorithm.label)

    private val crc: java.util.zip.CRC32? =
        if (algorithm == HashAlgorithm.CRC32) java.util.zip.CRC32() else null

    fun update(buffer: ByteArray, length: Int) {
        digest?.update(buffer, 0, length)
        crc?.update(buffer, 0, length)
    }

    fun finishHex(): String =
        digest?.digest()?.toLowerHex() ?: "%08x".format(requireNotNull(crc).value)
}

/** 字节数组 → 小写十六进制（[hashText] 契约：`md5sum` 同口径） */
private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it) }
