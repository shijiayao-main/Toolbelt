package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * 取证小工具集（工具箱特色的 agent 能力面）：
 *
 * - hash_file：sha256 / md5 摘要（校验文件完整性、判重复文件）；
 * - hex_dump：十六进制转储（offset/length 分页，cap 4096 字节——看文件头/魔数的刚需）；
 * - strings：提取可打印字符串序列（min_length 过滤，cap 500——二进制里找 URL/路径/版本号）。
 *
 * 纯 JVM 零依赖，全部走围栏 + StructuredSimpleTool 三段式。
 */
object ForensicTools {

    // ── AgentToolCatalog 描述符（P1 A1） ──

    val HASH_FILE: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = "fs.extended.hash_file",
        description = "Compute sha256/md5 digest of a file",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        aliases = setOf("hash_file"),
    ) { context, sink, _ -> HashFileTool(context, sink) }

    val HEX_DUMP: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = "fs.extended.hex_dump",
        description = "Hex dump of a byte range (offset/length, capped)",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        aliases = setOf("hex_dump"),
    ) { context, sink, _ -> HexDumpTool(context, sink) }

    val STRINGS: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = "fs.extended.strings",
        description = "Extract printable strings from a binary file",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        aliases = setOf("strings"),
    ) { context, sink, _ -> StringsTool(context, sink) }

    // ── hash_file ──

    class HashFileTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<HashFileTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "hash_file",
        description = "Compute the SHA-256 or MD5 hash of a file (integrity check / duplicate detection).",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the file")
            val file_path: String,
            @property:LLMDescription("Hash algorithm: sha256 or md5 (default sha256)")
            val algo: String = "sha256",
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val file = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
            if (!file.isFile) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.NOT_FOUND, "文件不存在: ${file.absolutePath}")
            }
            val algorithm = args.algo.lowercase()
            if (algorithm !in setOf("sha256", "md5")) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "不支持的算法: $algorithm（仅 sha256 / md5）",
                )
            }
            val digest = runCatching {
                val md = MessageDigest.getInstance(
                    if (algorithm == "sha256") "SHA-256" else "MD5",
                )
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }.getOrElse { e ->
                return@withContext ToolResult.Err(ToolResult.ErrCode.FAILED, "计算失败: ${e.message}")
            }
            ToolResult.Ok(
                summary = "$algorithm(${file.name}) = $digest\n[文件大小: ${file.length()}B]",
            )
        }
    }

    // ── hex_dump ──

    class HexDumpTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<HexDumpTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "hex_dump",
        description = "Hex-dump a byte range of a file (magic bytes / header inspection). " +
            "Max 4096 bytes per call; use offset to page through.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the file")
            val file_path: String,
            @property:LLMDescription("Byte offset to start from (default 0)")
            val offset: Long = 0,
            @property:LLMDescription("Number of bytes to dump, max 4096")
            val length: Int = 256,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val file = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
            if (!file.isFile) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.NOT_FOUND, "文件不存在: ${file.absolutePath}")
            }
            if (args.offset < 0 || args.length <= 0) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "参数非法：offset ≥ 0 且 length > 0",
                )
            }
            val len = args.length.coerceAtMost(MAX_DUMP_BYTES)
            if (args.offset >= file.length()) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "offset ${args.offset} 超出文件大小 ${file.length()}B",
                )
            }
            val bytes = file.inputStream().use { input ->
                var skipped = 0L
                while (skipped < args.offset) {
                    val n = input.skip(args.offset - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                if (skipped < args.offset) {
                    return@withContext ToolResult.Err(ToolResult.ErrCode.FAILED, "无法跳转到 offset ${args.offset}")
                }
                val buf = ByteArray(len)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
            if (bytes.isEmpty()) return@withContext ToolResult.Ok("(offset 处无可读字节)")

            val sb = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val chunk = bytes.copyOfRange(i, minOf(i + 16, bytes.size))
                val hex = chunk.joinToString(" ") { "%02x".format(it) }
                val ascii = chunk.joinToString("") {
                    if (it in 32..126) it.toInt().toChar().toString() else "."
                }
                sb.appendLine("%08x  %-48s  |%s|".format(args.offset + i, hex, ascii))
                i += 16
            }
            sb.append("[dumped ${bytes.size}B @ offset ${args.offset}；文件总大小 ${file.length()}B]")
            ToolResult.Ok(summary = sb.toString())
        }

        private companion object {
            const val MAX_DUMP_BYTES = 4096
        }
    }

    // ── strings ──

    class StringsTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<StringsTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "strings",
        description = "Extract printable strings (min length 4 by default) from a binary or text file — " +
            "useful for finding URLs, paths, version markers inside binaries or logs.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the file")
            val file_path: String,
            @property:LLMDescription("Minimum string length (default 4)")
            val min_length: Int = 4,
            @property:LLMDescription("Max strings to return (default 500)")
            val max_results: Int = 500,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val file = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
            if (!file.isFile) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.NOT_FOUND, "文件不存在: ${file.absolutePath}")
            }
            if (args.min_length < 1 || args.max_results <= 0) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "参数非法：min_length ≥ 1 且 max_results > 0")
            }

            // 逐字节扫描（可打印 ASCII 段连续 ≥ min_length 收集；GB18030 区段交给 read_file 的文本通道）
            val found = mutableListOf<String>()
            runCatching {
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var carry = StringBuilder()
                    while (found.size < args.max_results) {
                        val n = input.read(buf)
                        if (n < 0) break
                        for (idx in 0 until n) {
                            val b = buf[idx]
                            if (b in 32..126) {
                                carry.append(b.toInt().toChar())
                            } else {
                                if (carry.length >= args.min_length) {
                                    found += carry.toString()
                                    if (found.size >= args.max_results) break
                                }
                                carry = StringBuilder()
                            }
                        }
                    }
                    if (carry.length >= args.min_length) found += carry.toString()
                }
            }.getOrElse { e ->
                return@withContext ToolResult.Err(ToolResult.ErrCode.FAILED, "读取失败: ${e.message}")
            }

            if (found.isEmpty()) return@withContext ToolResult.Ok("(未提取到 ≥${args.min_length} 字符的可打印串)")
            ToolResult.Ok(
                summary = found.joinToString("\n") + "\n\n[共 ${found.size} 条（≥${args.min_length} 字符）]",
            )
        }
    }
}
