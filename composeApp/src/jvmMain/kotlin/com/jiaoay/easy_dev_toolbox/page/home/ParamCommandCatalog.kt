@file:OptIn(
    ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class,
    ai.koog.serialization.annotations.InternalKoogSerializationApi::class,
)

package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.core.buffer.FileBuffer
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DecryptTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.KnowledgeTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResult
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResultSink
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandResult
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest
import com.jiaoay.log.LogDiff
import com.jiaoay.log.LogTimeFilter
import com.jiaoay.log.ToolsResult
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 带参命令聚合面（命令面板第三类候选的唯一数据源）。
 *
 * ## 两路汇入
 * 1. **feature 页面命令**（五件套 ParamCommandSpec——经 [ToolPageRegistry.paramCommands]）：
 *    直调 :log-tools 纯函数 / :core 纯函数（产物语义与原页面 VM 对齐）；
 * 2. **agent 工具投影**（[uiHintProjections]——AgentToolCatalog uiHint 面）：
 *    经 Koog ToolRegistry 装配 + executeUnsafe 直执行（MCP 外放 dispatch 同款通道），
 *    结构化结果经 [ToolResultSink] 旁路收集（StructuredSimpleTool 三段式——
 *    sink 拿 ToolResult，HUD 直接消费）。
 *
 * ## 双注册撞面去重（侦察风险①的机制化）
 * log.decrypt / fs.decrypt_file 与页面解密命令、log.filter_by_tag / log.split_by_size
 * 与页面过滤/分割命令**底层是同一个 :log-tools 函数**——但产物命名刻意不同
 * （agent log.decrypt 产物 `${nameWithoutExtension}.log`，页面命令产物 `${name}.txt`
 * ——LogKitContributions KDoc「.log 后缀刻意不混用」），故属**同能力双入口**而非
 * 同函数同产物。投影面显式排除，palette 不出现成对重复条目（页面命令以 feature
 * 注册序优先存活——页面入口的产物语义与原页面 VM 逐字对齐，是用户可预期的主通道）。
 * log.filter_by_time / log.probe_file / knowledge.search / log.analyze_log 无页面命令
 * 对应（时间过滤活在 LogWorkbench，判型/检索无页面态）——保留投影。
 * log.filter_by_range 不在此面：描述符 uiHint = null（本文件 timeSliceCommand
 * 已是同语义带参命令），uiHint 面为零即天然不投影，也无需进 [PROJECTION_EXCLUDED]。
 *
 * ## ask_user 不投影（侦察风险②）
 * fields 是 List&lt;FieldArg&gt; 嵌套结构，ParamSpec 四型表达不了 MULTI_CHOICE 列表；
 * 且 ask_user 是 agent 会话内 HITL 工具，独立 palette 投影语义存疑——裁掉
 * （descriptor.params 仍声明 question 单参以满足纪律断言）。
 *
 * ## 围栏语义（palette 特有）
 * args 里的 FILE 参数路径 = 用户**亲手填进表单**的路径（非模型幻觉路径）——
 * 围栏以它为初始允许集（[ToolExecutionContext] 单文件形态），HITL 语义已由用户
 * 填参完成，authorize 直接放行；产物登记后后续工具可继续访问。
 */
object ParamCommandCatalog {

    /** 投影排除集（与页面命令同能力的四件 + HITL 会话内件）。internal：装配门控单测锚 */
    internal val PROJECTION_EXCLUDED = setOf(
        "log.decrypt",
        "fs.decrypt_file",
        "log.filter_by_tag",
        "log.split_by_size",
        "user.ask_user",
    )

    /** 页面命令 + 投影面全量命令（palette 候选构建用；纯数据函数，可任意上下文调用） */
    fun all(): List<ParamCommandSpec> =
        ToolPageRegistry.paramCommands() + uiHintProjections() + bufferOfferCommand + logDiffCommand + logViewCommand + timeSliceCommand

    // ── File Buffer 暂存命令（palette 消费面） ──

    /**
     * 「暂存到 Buffer」带参命令：把文件路径暂存进 [FileBuffer]（App 级暂存区），
     * 后续拖给工作台/会话消费（围栏 register 即出暂存）。宿主直登记（FileBuffer 在
     * :core、五件套 ParamCommandSpec 域外——id 前缀 `buffer.` 与 feature 命令不撞）。
     * internal：单测锚（FileBuffer 纯内存，直测零副作用可清）。
     */
    internal val bufferOfferCommand = ParamCommandSpec(
        id = "buffer.offer",
        label = "暂存文件到 Buffer",
        desc = "文件先入暂存区，再拖给工作台/会话——数据就位先于工具选择",
        icon = MaterialSymbols.LAYERS,
        keywords = listOf("buffer", "暂存", "暂存区", "中转"),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "文件路径",
                type = ParamType.FILE,
                placeholder = "待暂存文件的绝对路径",
            ),
        ),
        performArgs = { args ->
            val path = args["file_path"].orEmpty()
            if (path.isBlank()) {
                return@ParamCommandSpec ParamCommandResult(success = false, summary = "未填写文件路径")
            }
            if (!File(path).isFile) {
                return@ParamCommandSpec ParamCommandResult(success = false, summary = "文件不存在：$path")
            }
            FileBuffer.offer(path)
            ParamCommandResult(
                summary = "已暂存（Buffer 现有 ${FileBuffer.files.value.size} 件）——工作台证据流顶部 Buffer 行可点 chip 消费或移除",
            )
        },
    )

    // ── 日志对比命令（工具页第 6 签的同能力直出通道） ──

    /**
     * 「对比两份日志」带参命令：palette 直出 [com.jiaoay.log.LogDiff] unified 产物
     * （`.diff` 落对比文件旁）。宿主直登记（与 [bufferOfferCommand] 同款配方）：
     * 日志对比是「工具」聚合页原生签，刻意不进 [com.jiaoay.easy_dev_toolbox.page.ToolPage]
     * 枚举（进枚举需三段式僵尸值承载 + 全仓 when 穷举改动），故不走 feature
     * paramCommands 注册面。[ParamCommandSpec.openPage] 为 null = 纯命令形态：
     * palette 语义是直出结果——交互式双槽体验走「工具」导航页日志对比签。
     * internal：命令行为单测锚（双 FILE 承载 + 全等不落产物 + degraded 明示）。
     */
    internal val logDiffCommand = ParamCommandSpec(
        id = "tools.log-diff",
        label = "对比两份日志",
        desc = "行级 diff 对比两份日志，unified 产物 .diff 落对比文件旁（交互式双槽在「工具」页日志对比签）",
        icon = MaterialSymbols.DIFFERENCE,
        keywords = listOf("diff", "对比", "差异", "日志对比"),
        params = listOf(
            ParamSpec(
                id = "old_path",
                label = "基准文件路径（old 侧）",
                type = ParamType.FILE,
                placeholder = "改动前日志的绝对路径",
            ),
            ParamSpec(
                id = "new_path",
                label = "对比文件路径（new 侧）",
                type = ParamType.FILE,
                placeholder = "改动后日志的绝对路径",
            ),
            ParamSpec(
                id = "normalize",
                label = "时间戳归一化对齐",
                type = ParamType.CHOICE,
                default = "关",
                options = listOf("关", "开"),
            ),
        ),
        performArgs = { args ->
            val oldPath = args["old_path"].orEmpty()
            val newPath = args["new_path"].orEmpty()
            // 缺一明示缺哪个槽（对齐页面「缺一拒收」口径——裸「未填写文件路径」不指明缺哪侧）
            when {
                oldPath.isBlank() ->
                    return@ParamCommandSpec ParamCommandResult(success = false, summary = "未填写基准文件路径")
                newPath.isBlank() ->
                    return@ParamCommandSpec ParamCommandResult(success = false, summary = "未填写对比文件路径")
            }
            withContext(Dispatchers.IO) {
                val oldFile = File(oldPath)
                val newFile = File(newPath)
                if (!oldFile.isFile) {
                    return@withContext ParamCommandResult(success = false, summary = "基准文件不存在：$oldPath")
                }
                if (!newFile.isFile) {
                    return@withContext ParamCommandResult(success = false, summary = "对比文件不存在：$newPath")
                }
                when (val result = LogDiff.diff(oldFile, newFile, args["normalize"] == "开")) {
                    is LogDiff.DiffResult.Failure ->
                        ParamCommandResult(success = false, summary = "对比失败：${result.reason}")
                    is LogDiff.DiffResult.Ok -> {
                        // 全等不落产物（页面同口径：全等早退无导出面），产物只在有差异时生成
                        if (result.hunks.isEmpty()) {
                            return@withContext ParamCommandResult(
                                summary = "两份文件内容一致" +
                                    "（基准 ${result.oldLineCount} 行 / 对比 ${result.newLineCount} 行）",
                            )
                        }
                        // 产物落点/覆盖语义与页面 exportDiff 对齐：<对比文件名>.diff 落对比文件旁
                        val out = newFile.parentFile
                            ?.let { File(it, "${newFile.name}.diff") }
                            ?: File("${newFile.name}.diff")
                        when (val write = LogDiff.writeUnified(result, oldFile.name, newFile.name, out)) {
                            is ToolsResult.Failure ->
                                ParamCommandResult(success = false, summary = "导出失败：${write.reason}")
                            is ToolsResult.Success -> ParamCommandResult(
                                // degraded 必须明示（引擎超对齐上限退化契约——调用方必须呈现给用户）
                                summary = "共 ${result.hunks.size} 处差异" +
                                    "（基准 ${result.oldLineCount} 行 / 对比 ${result.newLineCount} 行）" +
                                    "，已导出：${write.outputFile.absolutePath}" +
                                    if (result.degraded) {
                                        val advice = if (args["normalize"] == "开") {
                                            "已开启归一化仍超限，建议缩小两份文件的对比范围后重新对比"
                                        } else {
                                            "建议开启「时间戳归一化对齐」后重新对比"
                                        }
                                        "\n差异超过对齐上限（${LogDiff.MAX_ALIGN_EDITS}），" +
                                            "中间区域已整体替换、未逐行对齐——$advice"
                                    } else {
                                        ""
                                    },
                                artifacts = listOf(write.outputFile.absolutePath),
                            )
                        }
                    }
                }
            }
        },
    )

    // ── 时间区间截取命令（起止时刻直出——对称窗口表达不了的语义缺口） ──

    /**
     * 「截取时间区间日志」带参命令：palette 直出 [com.jiaoay.log.LogTimeFilter.filterByRange]
     * 产物（`.filter-time_HHmm-HHmm.txt` 落原文件旁，与对称窗口同款命名）。
     * 宿主直登记（与 [logDiffCommand] 同款配方）：时间区间是起止时刻语义
     * （对称窗口整数分钟口径表达不了奇数分钟区间），agent 工具 log.filter_by_time
     * 是时间点 ±N 分钟语义，两者刻意互不替代。时间参数格式
     * yyyy-MM-dd_HH:mm:ss.SSS（容忍空格分隔与省略毫秒），亦容忍裸
     * MM-dd HH:mm:ss.SSS（xlog 自产明文行首同款，从日志直接复制即可）——
     * 裸形态仅对裸格式日志保证正确（年份按目标年统一重锚，锚定值不影响
     * 结果）；年份格式日志（外部解码器产物）与跨年边界的日志请补全年份。
     * 无法解析/起止倒挂由引擎拒绝（宁拒不猜）。
     * 缺参与不存在文件两道回执对齐 [bufferOfferCommand] 口径。internal：单测锚。
     */
    internal val timeSliceCommand = ParamCommandSpec(
        id = "tools.slice-time",
        label = "截取时间区间日志",
        desc = "按起止时刻截取日志段，产物落原文件旁（时间点±窗口场景走「按时间点过滤日志」）",
        icon = MaterialSymbols.CONTENT_CUT,
        keywords = listOf("截取", "区间", "slice", "时间段", "切日志"),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "文件路径",
                type = ParamType.FILE,
                placeholder = "待截取日志文件的绝对路径",
            ),
            ParamSpec(
                id = "start_time",
                label = "起始时刻",
                type = ParamType.TEXT,
                placeholder = "格式 yyyy-MM-dd_HH:mm:ss.SSS，亦容忍裸 MM-dd HH:mm:ss.SSS（xlog 明文行首同款）",
            ),
            ParamSpec(
                id = "end_time",
                label = "结束时刻",
                type = ParamType.TEXT,
                placeholder = "格式 yyyy-MM-dd_HH:mm:ss.SSS，亦容忍裸 MM-dd HH:mm:ss.SSS（xlog 明文行首同款）",
            ),
        ),
        performArgs = { args ->
            val path = args["file_path"].orEmpty()
            val start = args["start_time"].orEmpty()
            val end = args["end_time"].orEmpty()
            when {
                path.isBlank() ->
                    return@ParamCommandSpec ParamCommandResult(success = false, summary = "未填写文件路径")
                start.isBlank() ->
                    return@ParamCommandSpec ParamCommandResult(success = false, summary = "未填写起始时刻")
                end.isBlank() ->
                    return@ParamCommandSpec ParamCommandResult(success = false, summary = "未填写结束时刻")
            }
            withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.isFile) {
                    return@withContext ParamCommandResult(success = false, summary = "文件不存在：$path")
                }
                when (val result = LogTimeFilter.filterByRange(file, start, end)) {
                    is ToolsResult.Failure ->
                        ParamCommandResult(success = false, summary = "截取失败：${result.reason}")
                    is ToolsResult.Success -> ParamCommandResult(
                        summary = "已截取：${result.outputFile.absolutePath}",
                        artifacts = listOf(result.outputFile.absolutePath),
                    )
                }
            }
        },
    )

    // ── 应用内查看器打开槽（palette 命令 → 宿主 Dialog 挂载） ──
    // 槽本体在 :core（core.runtime.LogViewerRequest——palette 命令/日志对比差异行
    // 跳转/工作台证据流截断动作多生产面共用的单真源；槽行为在 :core jvmTest 背书）。

    /**
     * 「查看日志文件」带参命令：palette 直达应用内查看器（行号/时间戳/级别跳转 +
     * 文本搜索 + 行文本选中复制 + 「送往 Tag 过滤」与「深度分析」衔接，
     * [com.jiaoay.easy_dev_toolbox.widget.LogViewerDialog]）。
     *
     * 查看器此前仅任务卡「查看」与 logcat 回执行两个入口——无任务卡的文件
     * （同事发来的/历史存量的 .txt）进不了查看器；本命令把「任意日志文件 →
     * 应用内快看」补成一步。缺参与不存在文件两道回执对齐 [bufferOfferCommand]
     * 口径（拒收必带原因）。执行 = [LogViewerRequest.offer] 写槽，宿主挂载
     * Dialog；打开失败（含加密文件「先解密」引导）由查看器 Failed 态自带，
     * 执行侧零新判断。internal：命令行为单测锚。
     */
    internal val logViewCommand = ParamCommandSpec(
        id = "tools.view-log",
        label = "查看日志文件",
        desc = "任意日志文件应用内快看——行号/时间戳/级别跳转、文本搜索、选中复制、送 Tag 过滤/深度分析（加密 xlog/alog 须先解密产出 txt）",
        icon = MaterialSymbols.VISIBILITY,
        keywords = listOf("查看", "view", "viewer", "快看", "打开日志"),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "文件路径",
                type = ParamType.FILE,
                placeholder = "待查看日志文件的绝对路径",
            ),
        ),
        performArgs = { args ->
            val path = args["file_path"].orEmpty()
            if (path.isBlank()) {
                return@ParamCommandSpec ParamCommandResult(success = false, summary = "未填写文件路径")
            }
            if (!File(path).isFile) {
                return@ParamCommandSpec ParamCommandResult(success = false, summary = "文件不存在：$path")
            }
            LogViewerRequest.offer(path)
            ParamCommandResult(summary = "已在应用内查看器打开：$path")
        },
    )

    // ── agent 工具投影（宿主侧执行器——通用一路覆盖全部投影件） ──

    /**
     * uiHint 投影命令集：AgentToolCatalog 里带 uiHint 的工具逐件投影为 ParamCommandSpec。
     *
     * 可见性：visibleWhen 在**面板打开瞬间求值**（对齐 ToolAction 契约）——
     * knowledge.* 空库隐藏（[knowledgeVisibility] 内存快照，经
     * [prefetchKnowledgeVisibility] 在 palette 打开瞬间异步预取——🔴 组合期零 SQL
     * （组合期同步查库违反本仓线程纪律——主线程查库即冻结 UI），其余恒可见
     * （需私钥件执行时报错文案引导配置，与页面行为一致）。
     */
    private fun uiHintProjections(): List<ParamCommandSpec> =
        AgentToolCatalog.all
            .filter { it.uiHint != null && it.name !in PROJECTION_EXCLUDED }
            .map { descriptor ->
                val hint = descriptor.uiHint!!
                ParamCommandSpec(
                    id = descriptor.name,
                    label = hint.title,
                    desc = hint.description.ifBlank { descriptor.description },
                    icon = when {
                        descriptor.name.startsWith("log.") -> MaterialSymbols.TEXT_SNIPPET
                        descriptor.name.startsWith("knowledge.") -> MaterialSymbols.SEARCH
                        else -> MaterialSymbols.MANAGE_SEARCH
                    },
                    keywords = hint.keywords,
                    params = descriptor.params,
                    visibleWhen = { projectedVisible(descriptor) },
                    performArgs = { args -> executeProjection(descriptor, args) },
                )
            }

    /**
     * knowledge.* 可见性内存快照（组合期零 SQL）：
     * palette 打开瞬间经 [prefetchKnowledgeVisibility] 在 IO 协程查库一次写此快照，
     * 组合期 [projectedVisible] 只读布尔。「打开瞬间求值一次」契约不变（面板开着
     * 库变化不闪现——低频面板交互，与 ToolAction 面同款）。
     */
    @Volatile
    private var knowledgeVisibility: Boolean = false

    /**
     * knowledge.* 可见性预取（palette 打开瞬间 LaunchedEffect 调用——IO 承接）。
     * SQL 查询（KnowledgeTools.hasContent 同步 SQLDelight stats）只发生在此，
     * 组合期零 SQL。返回快照值（供调用方诊断/测试）。
     */
    suspend fun prefetchKnowledgeVisibility(): Boolean {
        val snapshot = KnowledgeTools.hasContent()
        knowledgeVisibility = snapshot
        return snapshot
    }

    /** 投影可见性（面板打开瞬间求值）：knowledge.* 读内存快照（空库隐藏），其余恒可见 */
    private fun projectedVisible(descriptor: AgentToolCatalog.AgentToolDescriptor): Boolean = when {
        descriptor.name.startsWith("knowledge.") -> knowledgeVisibility
        else -> true
    }

    /** 测试缝：直写快照（单测免真库依赖） */
    internal fun setKnowledgeVisibilityForTest(value: Boolean) {
        knowledgeVisibility = value
    }

    /** sink 结果收集槽（单次执行单协程——无并发，直收即可） */
    private class ResultSlot {
        var value: ToolResult? = null
    }

    /**
     * 投影执行器：单文件围栏 + Koog registry 装配 + kotlinx 解码 args + executeUnsafe。
     *
     * - 围栏 = [ToolExecutionContext]（args 里第一个 FILE 参数路径为初始允许集）；
     * - 私钥提供者 = [DecryptTools.defaultPrivateKeyProvider]（与 XLog 页共享 DataStore key）；
     * - 结构化结果经 sink 旁路收集（HUD 消费 ToolResult.Ok.artifacts——产物 chips），
     *   executeUnsafe 返回值（render 文案）仅兜底（sink 不可达的异常路径）；
     * - IO 全程 Dispatchers.IO（解密/过滤/检索是磁盘/DB 操作）。
     */
    private suspend fun executeProjection(
        descriptor: AgentToolCatalog.AgentToolDescriptor,
        args: Map<String, String>,
    ): ParamCommandResult = withContext(Dispatchers.IO) {
        val fenceFile = descriptor.params
            .firstOrNull { it.type == ParamType.FILE }
            ?.let { spec -> args[spec.id] }
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
        val context = if (fenceFile != null && fenceFile.isFile) {
            ToolExecutionContext(fenceFile)
        } else {
            ToolExecutionContext()
        }
        val slot = ResultSlot()
        val registry = AgentToolCatalog.buildRegistry(
            names = setOf(descriptor.name),
            context = context,
            sink = ToolResultSink { _, result -> slot.value = result },
            config = AgentToolCatalog.ToolContextConfig(
                privateKeyProvider = DecryptTools.defaultPrivateKeyProvider,
            ),
        )
        // 双名域解析（McpToolRegistrar.toolOf 同款配方）：registry 内注册名 = 工具
        // 实现名（Koog name，如 probe_file），目录描述符面 = 点分目录名 + aliases
        // （log.probe_file）——查表序：目录名直查 → aliases 逐个查。修复前按目录名
        // 单查恒落空（三条 log.* 投影命令已不可执行——执行链单测暴露后收窄）。
        val tool = registry.getToolOrNull(descriptor.name)
            ?: descriptor.aliases.firstNotNullOfOrNull { registry.getToolOrNull(it) }
            ?: return@withContext ParamCommandResult(
                success = false,
                summary = "命令不可用：${descriptor.name}（装配门控未放行——检查配置后重试）",
            )
        try {
            @OptIn(
                ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class,
                ai.koog.serialization.annotations.InternalKoogSerializationApi::class,
            )
            val typed: Any? = when (val token = tool.argsType) {
                is ai.koog.serialization.KSerializerTypeToken<*> -> token.serializer
                is ai.koog.serialization.KotlinTypeToken ->
                    serializer(token.type)
                else -> null
            }?.let { ks ->
                @Suppress("UNCHECKED_CAST")
                argJson.decodeFromString(
                    ks as KSerializer<Any?>,
                    argsToJson(args, descriptor.params),
                )
            }
            tool.executeUnsafe(typed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ParamCommandResult(
                success = false,
                summary = "执行失败：${e.message ?: e::class.simpleName}",
            )
        }
        when (val result = slot.value) {
            is ToolResult.Ok -> result.toCommandResult()
            is ToolResult.Err -> result.toCommandResult()
            // sink 不可达（结构化旁路缺席）——executeUnsafe 已成功返回即视作成功
            null -> ParamCommandResult(summary = "命令已执行", success = true)
        }
    }

    /**
     * ToolResult → HUD 命令结果的映射（纯函数——投影执行链单测锚）：
     * Ok → summary + 产物路径 + success；Err → renderText + 失败。
     * internal：单测锚（Ok 产物路径映射 / Err renderText / sink-null 兜底语义的映射面）。
     */
    internal fun ToolResult.toCommandResult(): ParamCommandResult = when (this) {
        is ToolResult.Ok -> ParamCommandResult(
            summary = summary,
            artifacts = artifacts.map { it.path },
            success = true,
        )
        is ToolResult.Err -> ParamCommandResult(
            summary = renderText(),
            success = false,
        )
    }

    /**
     * args map → JSON（ParamSpec.type 转换：NUMBER 输出裸数值，其余 JSON 字符串；
     * 空值字段不传——由 @Serializable 默认值兜底）。
     * internal：投影执行链单测锚（编码语义回归网）。
     */
    internal fun argsToJson(args: Map<String, String>, specs: List<ParamSpec>): String =
        buildString {
            append("{")
            append(
                specs.mapNotNull { spec ->
                    val value = args[spec.id]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    when (spec.type) {
                        ParamType.NUMBER ->
                            value.toLongOrNull()?.let { "\"${spec.id}\":$it" }
                                ?: value.toDoubleOrNull()?.let { "\"${spec.id}\":$it" }
                        else -> "\"${spec.id}\":" + argJson.encodeToString(String.serializer(), value)
                    }
                }.joinToString(","),
            )
            append("}")
        }

    private val argJson = Json { ignoreUnknownKeys = true }
}
