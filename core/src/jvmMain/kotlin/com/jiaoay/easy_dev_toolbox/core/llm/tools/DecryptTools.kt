package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import com.jiaoay.utils.SafeExtract
import com.jiaoay.xlog.LogDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 本地文件工具集（SimpleTool 注册，全部 suspend + IO 包裹；原万能解密页工具族，页移除后
 * 为桥接/工作台共用资产）：
 *
 * - decrypt_file：设备加密日志（.xlog/.alog）→ 同目录 .txt（LogDecode 门面）；
 * - extract_archive：zip/tar/gz 族解包 → ${name}_extracted/（SafeExtract，zip-slip 防护内置）；
 * - preview_text：文件头 4KB 文本预览（UTF-8 严格失败回退 GB18030——设备日志常见中文编码）；
 * - list_dir：目录清单（名称/大小/目录标记）。
 *
 * 🔴 全部工具的路径参数必经 [ToolExecutionContext.authorize] 围栏校验
 * （防 LLM 幻觉路径与文件内容注入；未命中返回错误文案，任务不崩）。
 *
 * xlog 私钥不新增配置：直接读既有 xlog_private_key（与 XLog 解密页共享同一 key，
 * 编辑入口仍在 XLog 页，本域只读）。
 *
 * 四件均继承 [StructuredSimpleTool]——内部结构化
 * （[ToolResult.Ok.artifacts] 精确产物），渲染文案**逐字兼容**既有字面
 * （`runLocalPath` 的 `startsWith("解密成功")` 与桥接进度映射零回归）。
 */
object DecryptTools {

    // ── 工具 1：解密 ──

    class DecryptFileTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val privateKeyProvider: suspend () -> String,
    ) : StructuredSimpleTool<DecryptFileTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "decrypt_file",
        description = "Decrypt a device encrypted log file (.xlog/.alog) to a plain text .txt file " +
            "in the same directory. Requires the XLog private key configured in the XLog page.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the encrypted log file")
            val file_path: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val input = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
            val privateKey = privateKeyProvider()
            if (privateKey.isBlank()) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "缺少 xlog 私钥：请先在「XLog 解密」页配置私钥后重试",
                )
            }
            val output = File(input.parentFile, "${input.name}.txt")
            runCatching {
                // 内容判型路由：明文（已解密文件误挂加密后缀 / 明文 mmap）跳过解密直接复制为产物
                if (com.jiaoay.xlog.LogFormatDetector.detect(input) ==
                    com.jiaoay.xlog.LogFormatDetector.Format.PLAINTEXT
                ) {
                    input.copyTo(output, overwrite = true)
                } else {
                    LogDecode().decodeFile(privateKey = privateKey, logFile = input, outFile = output)
                }
            }.fold(
                onSuccess = {
                    context.register(output)
                    ToolResult.Ok(
                        summary = "解密成功: ${output.absolutePath}",
                        artifacts = listOf(
                            ArtifactRef(path = output.absolutePath, kind = ArtifactKind.FILE, sizeBytes = output.length()),
                        ),
                    )
                },
                onFailure = { e ->
                    ToolResult.Err(ToolResult.ErrCode.FAILED, "解密失败: ${e.message ?: e::class.simpleName}")
                },
            )
        }
    }

    // ── 工具 2：解包 ──

    class ExtractArchiveTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<ExtractArchiveTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "extract_archive",
        description = "Extract an archive file (zip/tar/tar.gz/tgz/gz) into a sibling directory " +
            "named '<archive>_extracted'. Returns the list of extracted file paths.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the archive file")
            val archive_path: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val archive = context.authorizeWithGate(args.archive_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
            if (!SafeExtract.isArchive(archive.name)) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "不支持的压缩格式: ${archive.name}（支持 zip/tar/tar.gz/tgz/gz）",
                )
            }
            val destDir = File(archive.parentFile, "${archive.name}_extracted")
            runCatching {
                SafeExtract.extract(archive, destDir)
            }.fold(
                onSuccess = { extracted ->
                    context.registerArchiveContents(destDir, extracted)
                    if (extracted.isEmpty()) {
                        ToolResult.Ok(
                            summary = "解包完成但未解出文件: ${destDir.absolutePath}",
                            artifacts = listOf(
                                ArtifactRef(destDir.absolutePath, ArtifactKind.DIRECTORY),
                            ),
                        )
                    } else {
                        ToolResult.Ok(
                            summary = "解包成功，共 ${extracted.size} 个文件:\n" +
                                extracted.joinToString("\n") { it.absolutePath },
                            artifacts = buildList {
                                add(ArtifactRef(destDir.absolutePath, ArtifactKind.EXTRACTED))
                                extracted.forEach { add(ArtifactRef(it.absolutePath, ArtifactKind.FILE, it.length())) }
                            },
                        )
                    }
                },
                onFailure = { e ->
                    ToolResult.Err(ToolResult.ErrCode.FAILED, "解包失败: ${e.message ?: e::class.simpleName}")
                },
            )
        }
    }

    // ── 工具 3：文本预览 ──

    class PreviewTextTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<PreviewTextTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "preview_text",
        description = "Read the first 4KB of a text file for content inspection " +
            "(UTF-8 first, falls back to GB18030 for Chinese-encoded logs).",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the text file")
            val file_path: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val file = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
            if (!file.isFile) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "文件不存在: ${file.absolutePath}",
                )
            }
            val head = ByteArray(PREVIEW_BYTES).also { buf ->
                file.inputStream().use { input ->
                    var read = 0
                    while (read < buf.size) {
                        val n = input.read(buf, read, buf.size - read)
                        if (n < 0) break
                        read += n
                    }
                }
            }.let { if (it.size > file.length()) it.copyOf(file.length().toInt()) else it }

            val text = decodeText(head)
            if (text.length >= PREVIEW_CHARS) {
                ToolResult.Ok(text.take(PREVIEW_CHARS) + "\n...(已截断，完整内容更长)")
            } else {
                ToolResult.Ok(text.ifBlank { "(文件内容为空)" })
            }
        }

        /** UTF-8 严格解码失败 → GB18030 回退（设备日志常见中文编码） */
        private fun decodeText(bytes: ByteArray): String {
            val strictDecoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            return runCatching {
                strictDecoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }.getOrDefault(String(bytes, charset("GB18030")))
        }

        private companion object {
            const val PREVIEW_BYTES = 4 * 1024
            const val PREVIEW_CHARS = 3500
        }
    }

    // ── 工具 4：目录清单 ──

    class ListDirTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<ListDirTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "list_dir",
        description = "List entries of a directory (name, size, is-directory).",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the directory")
            val dir_path: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val dir = context.authorizeWithGate(args.dir_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
            if (!dir.isDirectory) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "不是目录: ${dir.absolutePath}")
            }
            val entries = dir.listFiles()
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.FAILED, "无法读取目录: ${dir.absolutePath}")
            if (entries.isEmpty()) return@withContext ToolResult.Ok("(目录为空)")
            ToolResult.Ok(
                entries.sorted().joinToString("\n") { entry ->
                    val marker = if (entry.isDirectory) "dir" else "${entry.length()}B"
                    "${entry.name} [$marker]"
                },
            )
        }
    }

    // ── AgentToolCatalog 描述符（P1 A1：经 catalog 装配的注册面） ──

    val DECRYPT_FILE: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = "fs.decrypt_file",
        description = "Decrypt a device encrypted log file (.xlog/.alog) to a plain text .txt file",
        risk = AgentToolCatalog.Risk.DESTRUCTIVE,
        aliases = setOf("decrypt_file"),
        uiHint = AgentToolCatalog.UiHint(
            title = "解密设备日志（.xlog/.alog → .txt）",
            keywords = listOf("xlog", "alog", "解密", "decrypt"),
            description = "xlog 解密能力（agent 工具）",
        ),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "加密日志路径",
                type = ParamType.FILE,
                placeholder = "拖入或填入 .xlog / .alog 文件绝对路径",
            ),
        ),
    ) { context, sink, config ->
        val key = config.privateKeyProvider ?: return@AgentToolDescriptor null
        DecryptFileTool(context, sink, key)
    }

    val EXTRACT_ARCHIVE: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = "fs.extract_archive",
        description = "Extract zip/tar/gz archives into a sibling '<archive>_extracted' directory",
        risk = AgentToolCatalog.Risk.DESTRUCTIVE,
        aliases = setOf("extract_archive"),
    ) { context, sink, _ -> ExtractArchiveTool(context, sink) }

    val PREVIEW_TEXT: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = "fs.preview_text",
        description = "Read the first 4KB of a text file (UTF-8 first, GB18030 fallback)",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        aliases = setOf("preview_text"),
    ) { context, sink, _ -> PreviewTextTool(context, sink) }

    val LIST_DIR: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = "fs.list_dir",
        description = "List entries of a directory (name, size, is-directory)",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        aliases = setOf("list_dir"),
    ) { context, sink, _ -> ListDirTool(context, sink) }

    /** 桌面链工具名集合（基础 4 件） */
    private val DESKTOP_BASE_NAMES =
        setOf(DECRYPT_FILE, EXTRACT_ARCHIVE, PREVIEW_TEXT, LIST_DIR).map { it.name }.toSet()

    /** extended 7 件工具名 */
    private val EXTENDED_NAMES = setOf(
        "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
        "fs.extended.hash_file", "fs.extended.hex_dump", "fs.extended.strings",
        "fs.extended.convert_timestamp",
    )

    /** 记忆域 4 件工具名（P3-B；随 memoryDir 门控并入选择集） */
    internal val MEMORY_NAMES = setOf("memory.view", "memory.append", "memory.replace", "memory.delete")

    /** 日志分析管线（P4-A）：两链通用（私钥经 config 注入；桌面 extended/桥接都并入） */
    private val PIPELINE_NAMES = setOf("log.analyze_log")

    /**
     * 组装本任务的工具注册表（P1 起经 [AgentToolCatalog] 过滤装配——存量签名/工具面/
     * 文案零变化；围栏与私钥提供者由任务级注入；sink 旁路默认 NOOP；middlewares 经
     * [com.jiaoay.easy_dev_toolbox.core.llm.agent.decorateRegistry] 逐工具包裹）。
     *
     * 扩展面（门控参数，存量调用形态零变化）：
     * - [todoBoard]：非 null 时注册 write_todos（规划投影）——解密页传任务级实例；桥接不挂；
     * - [extended]：true 时并入文件/取证/时间戳扩展件——解密页对话式任务流传 true；
     * - [memoryDir]：L2 语义记忆目录（P3-B；null = 本链不装记忆域四件）。
     *
     * knowledge.* 三件（P3-D）**恒在选择集**——实际注册由描述符 factory 内
     * `KnowledgeTools.hasContent()` 门控（空知识库返回 null 不注册），故无需本层传参。
     */
    fun buildToolRegistry(
        context: ToolExecutionContext,
        privateKeyProvider: suspend () -> String = defaultPrivateKeyProvider,
        resultSink: ToolResultSink = ToolResultSink.NOOP,
        middlewares: List<com.jiaoay.easy_dev_toolbox.core.llm.agent.AgentToolMiddleware> = emptyList(),
        todoBoard: TodoBoard? = null,
        extended: Boolean = false,
        memoryDir: java.io.File? = null,
    ): ToolRegistry {
        val names = if (extended) DESKTOP_BASE_NAMES + EXTENDED_NAMES else DESKTOP_BASE_NAMES
        // 记忆域四件随 memoryDir 门控（P3-B）：null = 本链不装（桥接/记忆关闭）；
        // 非 null = memory.* 并入选择集（READ_ONLY 三件 + DESTRUCTIVE 一件）
        val memoryNames = if (memoryDir != null) MEMORY_NAMES else emptyList()
        val config = AgentToolCatalog.ToolContextConfig(
            privateKeyProvider = privateKeyProvider,
            todoBoard = todoBoard,
            memoryDir = memoryDir,
        )
        // catalog 裸装配（middlewares 空链）→ 补挂 write_todos → 末尾统一装饰一次
        //（避免 catalog 装饰 + 补挂重建再装饰的双重包裹）
        val base = AgentToolCatalog.buildRegistry(
            names = names + memoryNames + KnowledgeTools.KNOWLEDGE_NAMES + PIPELINE_NAMES,
            context = context,
            sink = resultSink,
            config = config,
            middlewares = emptyList(),
        )
        return com.jiaoay.easy_dev_toolbox.core.llm.agent.decorateRegistry(
            ToolRegistry {
                base.tools.forEach { tool(it) }
                if (todoBoard != null) tool(WriteTodosTool(todoBoard, resultSink))
            },
            middlewares,
        )
    }

    /** 默认私钥提供者：读既有 xlog_private_key（与 feature-logkit LogKitConfigStore 共享，只读；
     *  编辑入口在 XLog 页）。VM 侧未配置 LLM 的降级路径同样复用它。 */
    val defaultPrivateKeyProvider: suspend () -> String = {
        AppDataStore.store.getValue(XLOG_PRIVATE_KEY, "")
    }

    /**
     * 默认私钥提供者的流形态：xlog_private_key 变更流（collect 即得当前值，每次落盘后
     * 即时发射；真源 key 与 [defaultPrivateKeyProvider] 同一）。宿主回落缓存的事件化
     * collect 用它——订阅方零轮询、零陈旧窗口。getter 形态使每次取用都锚定
     * [AppDataStore.store] 的当前实例（流本身是冷流，构造无副作用）。
     */
    val defaultPrivateKeyFlow: kotlinx.coroutines.flow.Flow<String?>
        get() = AppDataStore.store.valueFlow(XLOG_PRIVATE_KEY)

    /** 与 XLog 解密页共享的既有 key 名 */
    private const val XLOG_PRIVATE_KEY = "xlog_private_key"
}
