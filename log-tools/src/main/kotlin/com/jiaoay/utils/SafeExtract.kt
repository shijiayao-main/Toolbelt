// 压缩包解包器。迁移动机：agent 文件工具（fs.extended.extract_archive）需要解压能力，
// 直接依赖 :lark-bridge 会拖整个飞书桥接链；:lark-bridge 本已依赖 :log-tools，
// 迁到此处对桥接是一行 import 改动。
package com.jiaoay.utils

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * 压缩包解包器：zip / tar / tar.gz / tgz / 单文件 .gz 解到目标目录。
 *
 * 零依赖原则（lib 不引第三方压缩库）：zip 用 JDK [ZipInputStream]，
 * tar 用手写 ustar 解析（512B header + 数据块，格式简单稳定），gzip 用 JDK [GZIPInputStream]。
 *
 * 安全约定（zip-slip 防护）：压缩包内条目名经 [safeChild] 规范化——
 * 绝对路径、`..` 穿越、符号链接/硬链接条目一律拒绝（跳过该条目，不中止解包）。
 *
 * 判定为压缩包的依据是扩展名（[isArchive]），调用方自行决定何时启用解包
 * （桥接域与 [com.jiaoay.lark.bridge.config.FileMessageConfig.autoExtract] 开关配合）。
 *
 * 本 object 体内**只有不可变常量**（两个 `Int` 常量），无任何可变全局状态。
 */
object SafeExtract {

    /**
     * 🔴 解压炸弹防护（zip bomb）。
     *
     * 写出路径若为无上限流式拷贝，一个几十 KB 的嵌套 zip 可解出 TB 级数据把磁盘写满，
     * 且中途无法取消、失败发生在磁盘已满之后。触发路径真实：桥接域 `autoExtract`
     * 默认开（收到引用文件即自动解压），另有 agent 工具（fs.extended.extract_archive）
     * 同款调用点。
     *
     * 三道闸（均为「尽力而为」的合理上界，不是精确配额）：
     * 1. **累计解出字节上限** [MAX_TOTAL_EXTRACTED_BYTES]（默认 2GiB）；
     * 2. **单条目压缩比上限** [MAX_COMPRESSION_RATIO]（防「单个巨条目」绕过累计上限的早期阶段）；
     * 3. **条目数上限** [MAX_ENTRY_COUNT]（防海量小条目耗尽 inode/时间）。
     *
     * 超限一律抛 [ExtractLimitExceededException]（**不静默跳过**——静默会让用户以为解压成功但文件不全）。
     */
    private const val MAX_TOTAL_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024   // 2 GiB

    /** 单条目解出/压缩比上限（zip bomb 的典型形态是极高压缩比）。 */
    private const val MAX_COMPRESSION_RATIO = 200L

    /** 单次解压的条目数上限。 */
    private const val MAX_ENTRY_COUNT = 100_000

    /** 解压安全阈值下限（供测试注入小阈值用；生产用上面的默认值）。 */
    class Limits(
        val maxTotalBytes: Long = MAX_TOTAL_EXTRACTED_BYTES,
        val maxRatio: Long = MAX_COMPRESSION_RATIO,
        val maxEntries: Int = MAX_ENTRY_COUNT,
    )

    /** 解压超限异常（可读文案，供上层转成用户提示）。 */
    class ExtractLimitExceededException(message: String) : java.io.IOException(message)

    /**
     * 受限拷贝：把 [input] 写到 [out]，累计写入不超过 [remainingBytes]，并受单条目压缩比约束。
     *
     * @param entryCompressedSize 该条目的压缩后大小（用于压缩比判定）；<= 0 表示未知（跳过压缩比检查）
     * @param remainingBytes 本次解压还剩多少字节配额（超了即抛）
     * @return 实际写入字节数
     */
    private fun copyLimited(
        input: InputStream,
        out: java.io.OutputStream,
        entryName: String,
        entryCompressedSize: Long,
        remainingBytes: Long,
        limits: Limits,
        ratioCheck: Boolean = true,
        /**
         * 精确读取长度：tar 这类**定长条目流**必须按 header 声明的 size 精确消费，
         * 读到 EOF 会把后续条目的 padding 与数据一并吞掉（曾因此让 5 个 tar 用例失败）。
         * null = 读到流结束（zip/gzip 的形态）。
         */
        exactBytes: Long? = null,
    ): Long {
        var written = 0L
        var remaining = exactBytes ?: Long.MAX_VALUE
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val want = if (exactBytes != null) minOf(buf.size.toLong(), remaining).toInt() else buf.size
            val n = input.read(buf, 0, want)
            if (n < 0) break
            written += n
            remaining -= n
            // ① 累计配额
            if (written > remainingBytes) {
                throw ExtractLimitExceededException(
                    "解压总量超过安全上限（本次配额 ${remainingBytes / (1024 * 1024)}MB 已用尽）" +
                        "——疑似解压炸弹，已中止。条目：$entryName",
                )
            }
            // ② 单条目压缩比（仅在有压缩后大小时判定；小条目豁免以免误伤正常高压缩率小文件）
            if (ratioCheck && entryCompressedSize > 0 && written > RATIO_CHECK_FLOOR_BYTES) {
                val ratio = written / entryCompressedSize
                if (ratio > limits.maxRatio) {
                    throw ExtractLimitExceededException(
                        "条目压缩比异常（${ratio}:1，上限 ${limits.maxRatio}:1）——疑似解压炸弹，已中止。条目：$entryName",
                    )
                }
            }
            out.write(buf, 0, n)
        }
        return written
    }

    /** 压缩比检查的最小解出字节门槛（低于此值不判定，避免误伤正常的小文件）。 */
    private const val RATIO_CHECK_FLOOR_BYTES = 10L * 1024 * 1024   // 10 MiB

    /**
     * 磁盘可用空间预检：目标盘可用空间不足配额时，提前把配额收紧到可用空间，
     * 让失败发生在**写入之前**而非磁盘写满之后。
     */
    private fun effectiveBudget(destDir: File, limits: Limits): Long {
        val usable = runCatching { destDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        // 留 64MB 余量给系统与其他进程
        val available = (usable - 64L * 1024 * 1024).coerceAtLeast(0)
        return minOf(limits.maxTotalBytes, available)
    }

    /**
     * 按扩展名判定是否为支持的压缩包。
     *
     * 实测清单（**大小写不敏感**，`lowercase()` 后比较，故 `.TGZ` 亦真）：
     * `.zip` / **`.tar`** / `.tar.gz` / `.tgz` / **`.gz`** → true；`.log` / `.xlog` → false。
     * ⚠️ 后缀清单易漏：`.tar` 与 `.gz` 是两个独立后缀，与 `.tar.gz`/`.tgz` 并存。
     */
    fun isArchive(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".tar") ||
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".gz")
    }

    /**
     * 解包到 [destDir]（不存在则创建）。
     *
     * @return 解出的文件列表（绝对路径；跳过目录条目与被安全策略拒绝的条目）
     * @throws IllegalArgumentException 不支持的压缩格式
     * @throws java.io.IOException 解包 IO 失败
     */
    fun extract(archive: File, destDir: File): List<File> = extract(archive, destDir, Limits())

    /**
     * 解包到 [destDir]，带显式安全阈值（解压炸弹防护）。
     * 测试可注入小阈值验证闸门；生产走默认 [Limits]。
     */
    fun extract(archive: File, destDir: File, limits: Limits): List<File> {
        destDir.mkdirs()
        // 🔴 磁盘空间预检：把配额收紧到可用空间，让失败发生在写入之前
        val budget = effectiveBudget(destDir, limits)
        if (budget <= 0) {
            throw ExtractLimitExceededException("目标磁盘可用空间不足（需预留 64MB 余量），已拒绝解压")
        }
        val name = archive.name.lowercase()
        return when {
            name.endsWith(".zip") -> extractZip(archive, destDir, budget, limits)
            name.endsWith(".tar") -> extractTar(archive, destDir, gzip = false, budget = budget, limits = limits)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                extractTar(archive, destDir, gzip = true, budget = budget, limits = limits)
            name.endsWith(".gz") -> listOf(decompressGzipSingle(archive, destDir, budget, limits))
            else -> throw IllegalArgumentException("不支持的压缩格式：${archive.name}")
        }
    }

    private fun extractZip(archive: File, destDir: File, budget: Long, limits: Limits): List<File> {
        val files = mutableListOf<File>()
        var used = 0L
        var count = 0
        ZipInputStream(archive.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                // 🔴 条目数闸——缺闸即条目数无上限，炸弹档案可无限展开
                if (++count > limits.maxEntries) {
                    throw ExtractLimitExceededException(
                        "压缩包条目数超过上限（${limits.maxEntries}）——疑似解压炸弹，已中止",
                    )
                }
                val target = safeChild(destDir, entry.name) ?: continue
                target.parentFile?.mkdirs() // 子目录条目（a/b.log）先建父目录
                val written = FileOutputStream(target).let { fos ->
                    java.io.BufferedOutputStream(fos, 64 * 1024).use { out ->   // 64KB 缓冲写（大文件提速明显）
                        copyLimited(zis, out, entry.name, entry.compressedSize, budget - used, limits)
                    }
                }
                used += written
                files.add(target)
                zis.closeEntry()
            }
        }
        return files
    }

    /**
     * tar 条目类型标志（ustar header 第 156 字节）：'0'/' ' 普通文件；'5' 目录；'2' 符号链接；'1' 硬链接。
     * 🔒 behavior-contract.md §4.7：typeFlag **非 `'0'` 且非 `' '` 的条目一律跳过**——符号链接/硬链接是**逃逸面**
     * （解出一个指向 `/etc` 的符号链接后，后续条目就能沿它写出工作区）。
     */
    private const val TAR_TYPEFLAG_OFFSET = 156
    private const val TAR_BLOCK_SIZE = 512

    /** 手写 ustar 解析（零依赖）：逐块读 header → 按类型处理 → 读数据 → 跳过 512 对齐 padding */
    private fun extractTar(
        archive: File,
        destDir: File,
        gzip: Boolean,
        budget: Long,
        limits: Limits,
    ): List<File> {
        val files = mutableListOf<File>()
        var used = 0L
        var count = 0
        val stream = if (gzip) GZIPInputStream(archive.inputStream().buffered()) else archive.inputStream().buffered()
        stream.use { input ->
            while (true) {
                val header = ByteArray(TAR_BLOCK_SIZE)
                if (readFully(input, header) < TAR_BLOCK_SIZE) break
                // 全零块 = 归档结束
                if (header.all { it == 0.toByte() }) break
                val name = parseTarString(header, 0, 100)
                val typeFlag = header[TAR_TYPEFLAG_OFFSET].toInt().toChar()
                val size = parseTarSize(header, 124, 12)
                // 🔴 512 对齐 padding：条目无论被解出还是被跳过，都必须消费 size + padding，
                //    否则穿越/链接条目之后的合法条目 header 会错位而解不出（只跳 size 会错位）。
                val padding = if (size > 0) (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE else 0L
                val isRegularFile = typeFlag == '0' || typeFlag == ' '
                // 目录/符号链接/硬链接（非常规文件）是越权逃逸面；空白名 / size==0 / safeChild 拒绝 一律跳过（不中止解包）
                val target = if (isRegularFile && size > 0 && name.isNotBlank()) safeChild(destDir, name) else null
                if (target == null) {
                    if (size > 0) skipFully(input, size + padding)
                    continue
                }
                // 🔴 条目数闸（只对真正落盘的常规文件计数——跳过的条目不计入；缺闸即条目数无上限）
                if (++count > limits.maxEntries) {
                    throw ExtractLimitExceededException(
                        "归档条目数超过上限（${limits.maxEntries}）——疑似解压炸弹，已中止",
                    )
                }
                // 🔴 累计配额闸：tar header 自带 size，先做前置判定，再逐块受限拷贝——缺闸即 tar 炸弹写满磁盘
                if (used + size > budget) {
                    throw ExtractLimitExceededException(
                        "解压总量超过安全上限（本次配额 ${budget / (1024 * 1024)}MB 已用尽）" +
                            "——疑似解压炸弹，已中止。条目：$name",
                    )
                }
                target.parentFile?.mkdirs() // 子目录条目先建父目录
                used += FileOutputStream(target).let { fos ->
                    java.io.BufferedOutputStream(fos, 64 * 1024).use { out ->   // 64KB 缓冲写
                        // tar 的 size 是解出大小（无独立压缩后大小），故 ratioCheck=false；
                        // 🔴 exactBytes=size 必须传——tar 是定长条目流，读到 EOF 会吞掉后续条目
                        copyLimited(
                            input, out, name,
                            entryCompressedSize = 0, remainingBytes = budget - used, limits = limits,
                            ratioCheck = false, exactBytes = size,
                        )
                    }
                }
                if (padding > 0) skipFully(input, padding)
                files.add(target)
            }
        }
        return files
    }

    /** tar header 字符串字段（NUL/空白结尾，UTF-8） */
    private fun parseTarString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset + length
        for (i in offset until offset + length) {
            if (header[i] == 0.toByte()) { end = i; break }
        }
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    /** tar header 八进制大小字段（offset 起 length 字节，US-ASCII） */
    private fun parseTarSize(header: ByteArray, offset: Int, length: Int): Long {
        var end = offset + length
        for (i in offset until offset + length) {
            if (header[i] == 0.toByte() || header[i] == ' '.code.toByte()) { end = i; break }
        }
        val text = String(header, offset, end - offset, Charsets.US_ASCII).trim()
        return text.takeIf { it.isNotEmpty() }?.toLong(8) ?: 0L
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun copyFully(input: InputStream, out: FileOutputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    /**
     * 单文件 gzip 解压（xxx.log.gz → xxx.log；JDK 自带 GZIPInputStream）。
     * 🔴 接入累计配额闸（单文件 .gz 同样可以是炸弹——高压缩比的超大日志）。
     */
    private fun decompressGzipSingle(archive: File, destDir: File, budget: Long, limits: Limits): File {
        val outName = archive.name.removeSuffix(".gz")
        // 用 safeChild 而非直接 File(destDir, outName)：条目名来自文件名，同样可能含穿越段
        val target = safeChild(destDir, outName) ?: File(destDir, DownloadPolicyLikeFallback(outName))
        target.parentFile?.mkdirs()
        GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            FileOutputStream(target).let { fos ->
                java.io.BufferedOutputStream(fos, 64 * 1024).use { out ->   // 64KB 缓冲写
                    copyLimited(gz, out, outName, archive.length(), budget, limits)
                }
            }
        }
        return target
    }

    /** 文件名兜底清洗（与 DownloadPolicy.sanitizeFileName 同口径的极简版，仅用于 .gz 输出名）。 */
    private fun DownloadPolicyLikeFallback(name: String): String =
        name.replace(Regex("""[<>:"|?*/\\]"""), "_").trim('_').ifBlank { "file" }

    /**
     * 🔒🔒 zip-slip 防护：条目名规范化后必须仍位于 [destDir] 内。
     * 拒绝绝对路径与 `..` 穿越；返回 null 表示该条目被拒绝。
     *
     * 两重校验缺一不可：
     * ① 字面校验（`startsWith("/")` / **按路径段**判定 `..`）——拦掉绝大多数穿越，O(1) 无 IO；
     * ② canonical 校验（`runCatching`）——拦掉 Windows 盘符切换、符号链接等残余形态。
     *
     * 🔴 字面那重的判定粒度必须是「split('/') 后逐段 `== ".."`」而非「`contains("..")`
     *    字面包含」——后者把完全合法的文件名（`a..b.txt`、`v1.2..bak`）**静默跳过**，
     *    解压结果少文件且无提示。仅拒绝真正的 `..` 路径段；安全面不放宽
     *    （`../` 段仍拦，canonical 那重保留）。
     */
    internal fun safeChild(destDir: File, entryName: String): File? {
        if (entryName.isBlank()) return null
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        // 按路径段判定 `..`：`a..b.txt` 合法放行；`../x`、`a/../x` 拒绝
        if (normalized.split('/').any { it == ".." }) return null
        val target = File(destDir, normalized)
        // 双保险：canonical 校验（Windows 盘符切换等残余形态也拦下）
        return runCatching {
            if (!target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) null else target
        }.getOrNull()
    }
}
