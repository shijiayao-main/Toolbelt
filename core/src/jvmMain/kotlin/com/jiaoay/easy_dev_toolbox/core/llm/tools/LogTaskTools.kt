package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.AgentToolDescriptor
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.Risk
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.ToolContextConfig
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import com.jiaoay.log.LogSplitter
import com.jiaoay.log.LogTagFilter
import com.jiaoay.log.LogTimeFilter
import com.jiaoay.log.ToolsResult
import com.jiaoay.xlog.LogDecode
import com.jiaoay.xlog.LogFormatDetector
import com.jiaoay.xlog.LogProbe
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * log-tools 能力的平台工具集。
 *
 * ## 来源
 * 桥接 `BridgeLogTools` 五件**下沉 :core 并结构化升级**：
 * - 继承 `SimpleTool` 裸 String → **StructuredSimpleTool**（ToolResult.Ok.artifacts 精确产物 +
 *   sink 旁路——P4 日志工作台/进度 UI 的结构化数据源）；
 * - 渲染文案**逐字兼容**既有字面（`BridgeLogToolsTest` 断言与桥接 LLM 存量提示词零回归）；
 * - 围栏统一走 `authorizeWithGate`（HITL 单一入口；桥接无 gate 时语义 = 旧 authorize 拒绝，零变化）。
 *
 * ## 两域差异参数化（下沉时唯一保留的语义差异，全部经参数/ToolContextConfig 注入）
 * - decrypt_log 产物后缀 `.log`（桥接语义）vs decrypt_file `.txt`（桌面语义）——两件并存各自命名；
 * - 私钥源经 [AgentToolCatalog.ToolContextConfig.privateKeyProvider] 注入（桌面 = DataStore 直读；
 *   桥接 = DispatchPayload 载体 + 宿主回落富化，消费方组装）；
 * - 围栏拒绝文案经 [fenceHint] 参数化（桥接「本消息的文件围栏」vs 桌面「本任务允许范围」）——
 *   由 [AgentToolCatalog.buildRegistry] 从 ToolContextConfig.fenceHint 透传。
 *
 * ## 命名
 * `log.*` 点分命名空间 + [AgentToolDescriptor.aliases] 保留桥接旧名（decrypt_log 等）——
 * 存量 LLM 提示词与测试用旧名照常解析。
 */
object LogTaskTools {

    // ── 工具 1：xlog/alog 解密（桥接产物语义 .log） ──

    class DecryptLogTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val privateKeyProvider: suspend () -> String,
        private val fenceHint: String? = null,
    ) : StructuredSimpleTool<DecryptLogTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "decrypt_log",
        description = "Decrypt a device encrypted log file (.xlog/.alog) to a plain text .log file " +
            "in the same directory. Requires the XLog private key configured in the bridge settings " +
            "or the app's XLog page.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the encrypted log file (.xlog/.alog)")
            val file_path: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val input = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            val privateKey = privateKeyProvider()
            if (privateKey.isBlank()) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "缺少 xlog 私钥：请让用户在桥接配置「文件消息」分组或本应用「XLog 解密」页配置私钥后重试",
                )
            }
            val output = File(input.parentFile, "${input.nameWithoutExtension}.log")
            runCatching {
                // 内容判型路由：明文（已解密文件误挂加密后缀 / 明文 mmap）跳过解密直接复制为产物
                if (LogFormatDetector.detect(input) == LogFormatDetector.Format.PLAINTEXT) {
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

    // ── 工具 2：Tag 过滤（日志块语义） ──

    class FilterByTagTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val fenceHint: String? = null,
    ) : StructuredSimpleTool<FilterByTagTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "filter_by_tag",
        description = "Filter a plain-text device log file by tag regex, keeping whole log blocks " +
            "(a block = lines from one timestamp line to the next). Files without timestamp line " +
            "heads (e.g. mmap dumps / tombstones) fall back to blank-line-separated paragraph blocks, " +
            "and the result summary carries a warning saying so. Output goes to a sibling .txt file.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the input log file (.txt)")
            val file_path: String,
            @property:LLMDescription("Regex to match lines inside a log block (e.g. \"MyTag|AnotherTag\")")
            val tag_regex: String,
            @property:LLMDescription("Short label for the output file name")
            val tag_name: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val input = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            when (val result = LogTagFilter.filterByTag(input, args.tag_regex, args.tag_name)) {
                is ToolsResult.Success -> {
                    context.register(result.outputFile)
                    ToolResult.Ok(
                        // 降级形态提示并入 summary（无时间戳文件的块口径不同——agent 须知情）
                        summary = "过滤完成，输出文件: ${result.outputFile.absolutePath}" +
                            (result.warning?.let { "；$it" } ?: ""),
                        artifacts = listOf(
                            ArtifactRef(path = result.outputFile.absolutePath, kind = ArtifactKind.FILE,
                                sizeBytes = result.outputFile.length()),
                        ),
                    )
                }
                is ToolsResult.Failure ->
                    ToolResult.Err(ToolResult.ErrCode.FAILED, "过滤失败: ${result.reason}")
            }
        }
    }

    // ── 工具 3：时间过滤 ──

    class FilterByTimeTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val fenceHint: String? = null,
    ) : StructuredSimpleTool<FilterByTimeTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "filter_by_time",
        description = "Filter a plain-text device log file by a time point, keeping lines within " +
            "±range_minutes. Target time format: yyyy-MM-dd_HH:mm:ss.SSS (bare MM-dd HH:mm:ss.SSS " +
            "as printed in xlog plaintext logs also accepted—only guaranteed correct for " +
            "bare-format logs; year-prefixed / year-crossing logs need the full year). " +
            "Output goes to a sibling .txt file.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the input log file (.txt)")
            val file_path: String,
            @property:LLMDescription(
                "Target time point, format yyyy-MM-dd_HH:mm:ss.SSS (space separator and omitted millis " +
                    "also accepted). Bare MM-dd HH:mm:ss.SSS (as printed at line head of xlog plaintext " +
                    "logs) is also accepted—copy it from the log directly and never invent a year. " +
                    "The bare form is only guaranteed correct for bare-format logs; for year-prefixed " +
                    "logs (external decoder output) or logs crossing a year boundary, provide the full year",
            )
            val target_time: String,
            @property:LLMDescription("Keep minutes before AND after the target time (e.g. 5)")
            val range_minutes: Long,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val input = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            when (val result = LogTimeFilter.filterByTime(input, args.target_time, args.range_minutes)) {
                is ToolsResult.Success -> {
                    context.register(result.outputFile)
                    ToolResult.Ok(
                        summary = "过滤完成，输出文件: ${result.outputFile.absolutePath}",
                        artifacts = listOf(
                            ArtifactRef(path = result.outputFile.absolutePath, kind = ArtifactKind.FILE,
                                sizeBytes = result.outputFile.length()),
                        ),
                    )
                }
                is ToolsResult.Failure ->
                    ToolResult.Err(ToolResult.ErrCode.FAILED, "过滤失败: ${result.reason}")
            }
        }
    }

    // ── 工具 4：按大小分割 ──

    class SplitBySizeTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val fenceHint: String? = null,
    ) : StructuredSimpleTool<SplitBySizeTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "split_by_size",
        description = "Split a large plain-text log file into multiple parts by size (MB). " +
            "Output files are named <name>_000.txt, <name>_001.txt ... next to the input.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the input log file")
            val file_path: String,
            @property:LLMDescription("Maximum size of each part in MB (e.g. 20)")
            val size_mb: Int,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val input = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            when (val result = LogSplitter.splitBySize(input, args.size_mb)) {
                is ToolsResult.Success -> {
                    // 多卷全部登记（首卷起名，兄弟卷同目录遍历登记）
                    val parts = input.parentFile?.listFiles()
                        ?.filter { it.isFile && it.name.startsWith("${input.nameWithoutExtension}_") }
                        .orEmpty()
                    parts.forEach { context.register(it) }
                    context.register(result.outputFile)
                    ToolResult.Ok(
                        summary = "分割完成，共 ${parts.size.coerceAtLeast(1)} 卷，首卷: ${result.outputFile.absolutePath}",
                        artifacts = buildList {
                            add(ArtifactRef(result.outputFile.absolutePath, ArtifactKind.FILE,
                                result.outputFile.length()))
                            parts.forEach {
                                add(ArtifactRef(it.absolutePath, ArtifactKind.FILE, it.length()))
                            }
                        },
                    )
                }
                is ToolsResult.Failure ->
                    ToolResult.Err(ToolResult.ErrCode.FAILED, "分割失败: ${result.reason}")
            }
        }
    }

    // ── 工具 5：文件判型探测 ──

    class ProbeFileTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val fenceHint: String? = null,
    ) : StructuredSimpleTool<ProbeFileTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "probe_file",
        description = "Probe the head of a file to tell whether it is an xlog/alog encrypted device log " +
            "(and whether a private key is required), before deciding to decrypt. Zero decode, zero output.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the file to probe")
            val file_path: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val input = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            if (!input.isFile) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "文件不存在: ${input.absolutePath}",
                )
            }
            val probe = LogProbe.probe(input)
            ToolResult.Ok(
                buildString {
                    appendLine("判型结果（${input.name}）：")
                    appendLine("- 是否 xlog/alog 加密日志: ${if (probe.isXLog) "是" else "否"}")
                    if (probe.isXLog) {
                        appendLine("- 段数（探测窗口内）: ${probe.segmentsFound}")
                        appendLine("- 是否需要私钥: ${if (probe.needsPrivateKey) "是（需先配置 xlog 私钥）" else "否"}")
                        appendLine("- 压缩方式: ${probe.compressions.joinToString("、").ifBlank { "无" }}")
                    }
                }.trim(),
            )
        }
    }

    // ── 工具 6：起止区间过滤（任意闭区间——对称窗口整数分钟口径表达不了的奇数分钟区间） ──

    class FilterByRangeTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val fenceHint: String? = null,
    ) : StructuredSimpleTool<FilterByRangeTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "filter_by_range",
        description = "Filter a plain-text device log file by an explicit start/end time range " +
            "(inclusive at both ends). Start/end time format: yyyy-MM-dd_HH:mm:ss.SSS " +
            "(bare MM-dd HH:mm:ss.SSS as printed in xlog plaintext logs also accepted—only " +
            "guaranteed correct for bare-format logs; year-prefixed / year-crossing logs need " +
            "the full year). Output goes to a sibling .txt file.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the input log file (.txt)")
            val file_path: String,
            @property:LLMDescription(
                "Range start time, format yyyy-MM-dd_HH:mm:ss.SSS (inclusive; space separator and " +
                    "omitted millis also accepted). Bare MM-dd HH:mm:ss.SSS (as printed at line head " +
                    "of xlog plaintext logs) is also accepted—copy it from the log directly and never " +
                    "invent a year. The bare form is only guaranteed correct for bare-format logs; " +
                    "for year-prefixed logs (external decoder output) or logs crossing a year " +
                    "boundary, provide the full year",
            )
            val start_time: String,
            @property:LLMDescription(
                "Range end time, format yyyy-MM-dd_HH:mm:ss.SSS (inclusive; must not be earlier than " +
                    "start_time). Bare MM-dd HH:mm:ss.SSS (as printed at line head of xlog plaintext " +
                    "logs) is also accepted—copy it from the log directly and never invent a year. " +
                    "The bare form is only guaranteed correct for bare-format logs; for year-prefixed " +
                    "logs (external decoder output) or logs crossing a year boundary, provide the " +
                    "full year",
            )
            val end_time: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val input = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            when (val result = LogTimeFilter.filterByRange(input, args.start_time, args.end_time)) {
                is ToolsResult.Success -> {
                    context.register(result.outputFile)
                    ToolResult.Ok(
                        summary = "过滤完成，输出文件: ${result.outputFile.absolutePath}",
                        artifacts = listOf(
                            ArtifactRef(path = result.outputFile.absolutePath, kind = ArtifactKind.FILE,
                                sizeBytes = result.outputFile.length()),
                        ),
                    )
                }
                is ToolsResult.Failure ->
                    ToolResult.Err(ToolResult.ErrCode.FAILED, "过滤失败: ${result.reason}")
            }
        }
    }

    /** 桌面默认围栏文案（StructuredSimpleTool.deniedHint 同义；fenceHint 覆写优先） */
    private val deniedHint: String
        get() = "路径不在本任务允许范围内（只能访问用户拖入的文件与本次解密/解包的产物），请检查路径或改用已登记文件"

    // ── 目录描述符（AgentToolCatalog 注册面；新消费方经 catalog 装配） ──

    val DECRYPT_LOG: AgentToolDescriptor = AgentToolDescriptor(
        name = "log.decrypt",
        description = "Decrypt a device encrypted log file (.xlog/.alog) to a plain text .log file",
        risk = Risk.DESTRUCTIVE,
        aliases = setOf("decrypt_log"),
        uiHint = AgentToolCatalog.UiHint(
            title = "解密设备日志（.xlog/.alog → .log）",
            keywords = listOf("xlog", "alog", "解密", "decrypt"),
            description = "解密设备加密日志为纯文本（agent 工具）",
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
        val key = config.privateKeyProvider
            ?: return@AgentToolDescriptor null // 消费方未注入私钥能力 ⇒ 不注册
        DecryptLogTool(context, sink, key, config.fenceHint)
    }

    val FILTER_BY_TAG: AgentToolDescriptor = AgentToolDescriptor(
        name = "log.filter_by_tag",
        description = "Filter a plain-text device log file by tag regex, keeping whole log blocks",
        risk = Risk.DESTRUCTIVE,
        aliases = setOf("filter_by_tag"),
        uiHint = AgentToolCatalog.UiHint(
            title = "按 Tag 正则过滤日志",
            keywords = listOf("tag", "过滤", "filter"),
            description = "保留命中 Tag 的日志块，输出兄弟文件（agent 工具）",
        ),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "日志文件路径",
                type = ParamType.FILE,
                placeholder = ".txt 纯文本日志绝对路径",
            ),
            ParamSpec(id = "tag_regex", label = "Tag 正则", type = ParamType.TEXT, placeholder = "如 MyTag|AnotherTag"),
            ParamSpec(id = "tag_name", label = "输出标记", type = ParamType.TEXT, placeholder = "输出文件命名用短标签"),
        ),
    ) { context, sink, config ->
        FilterByTagTool(context, sink, config.fenceHint)
    }

    val FILTER_BY_TIME: AgentToolDescriptor = AgentToolDescriptor(
        name = "log.filter_by_time",
        description = "Filter a plain-text device log file by a time point, keeping lines within ±range",
        risk = Risk.DESTRUCTIVE,
        aliases = setOf("filter_by_time"),
        uiHint = AgentToolCatalog.UiHint(
            title = "按时间点过滤日志",
            keywords = listOf("时间", "过滤", "time"),
            description = "保留时间点 ±N 分钟内的日志行（agent 工具）",
        ),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "日志文件路径",
                type = ParamType.FILE,
                placeholder = ".txt 纯文本日志绝对路径",
            ),
            ParamSpec(
                id = "target_time",
                label = "目标时间点",
                type = ParamType.TEXT,
                placeholder = "yyyy-MM-dd_HH:mm:ss.SSS，亦容忍裸 MM-dd HH:mm:ss.SSS（xlog 明文行首同款）",
            ),
            ParamSpec(id = "range_minutes", label = "前后窗口（分钟）", type = ParamType.NUMBER, default = "5"),
        ),
    ) { context, sink, config ->
        FilterByTimeTool(context, sink, config.fenceHint)
    }

    /**
     * `log.filter_by_range` 目录描述符。uiHint = null：palette 已有同语义带参命令
     * tools.slice-time（宿主 ParamCommandCatalog 的 timeSliceCommand），带 uiHint 的
     * 描述符会被投影进命令面板成同能力双入口。与 [FILTER_BY_TIME] 的分工：
     * filter_by_time = 对称窗口（时间点 ±N 整数分钟）；filter_by_range = 任意起止
     * 闭区间（奇数分钟区间等对称窗口整数分钟口径表达不了的形状）。
     */
    val FILTER_BY_RANGE: AgentToolDescriptor = AgentToolDescriptor(
        name = "log.filter_by_range",
        description = "Filter a plain-text device log file by an explicit start/end time range (inclusive)",
        risk = Risk.DESTRUCTIVE,
        aliases = setOf("filter_by_range"),
        factory = { context, sink, config ->
            FilterByRangeTool(context, sink, config.fenceHint)
        },
    )

    val SPLIT_BY_SIZE: AgentToolDescriptor = AgentToolDescriptor(
        name = "log.split_by_size",
        description = "Split a large plain-text log file into multiple parts by size (MB)",
        risk = Risk.DESTRUCTIVE,
        aliases = setOf("split_by_size"),
        uiHint = AgentToolCatalog.UiHint(
            title = "按大小分割日志",
            keywords = listOf("分割", "split", "分卷"),
            description = "大日志文件按 MB 分卷（agent 工具）",
        ),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "日志文件路径",
                type = ParamType.FILE,
                placeholder = "纯文本日志绝对路径",
            ),
            ParamSpec(id = "size_mb", label = "每卷大小（MB）", type = ParamType.NUMBER, default = "10"),
        ),
    ) { context, sink, config ->
        SplitBySizeTool(context, sink, config.fenceHint)
    }

    val PROBE_FILE: AgentToolDescriptor = AgentToolDescriptor(
        name = "log.probe_file",
        description = "Probe the head of a file to tell whether it is an xlog/alog encrypted device log",
        risk = Risk.READ_ONLY,
        aliases = setOf("probe_file"),
        uiHint = AgentToolCatalog.UiHint(
            title = "判型探测文件",
            keywords = listOf("判型", "probe", "探测"),
            description = "探测文件是否为加密日志（agent 工具）",
        ),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "待探测文件路径",
                type = ParamType.FILE,
                placeholder = "任意文件绝对路径（探测是否 xlog/alog 加密）",
            ),
        ),
    ) { context, sink, config ->
        ProbeFileTool(context, sink, config.fenceHint)
    }
}
