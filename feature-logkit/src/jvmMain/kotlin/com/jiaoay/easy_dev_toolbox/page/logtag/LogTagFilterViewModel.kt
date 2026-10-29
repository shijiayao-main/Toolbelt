package com.jiaoay.easy_dev_toolbox.page.logtag

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.widget.task.FileTaskViewModel
import com.jiaoay.easy_dev_toolbox.page.logkit.LogKitConfigStore
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.widget.task.ProcessResult
import com.jiaoay.easy_dev_toolbox.widget.task.Validation
import com.jiaoay.log.LogStatsAnalyzer
import com.jiaoay.log.LogStatsResult
import com.jiaoay.log.LogTagFilter
import com.jiaoay.log.TagPreviewResult
import com.jiaoay.log.ToolsResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter

/**
 * Log Tag 过滤：多文件任务卡模型（[FileTaskViewModel] 收敛后仅剩域逻辑）。
 *
 * - Tag 正则与名称经 [LogKitConfigStore] 持久化（onChange 即写），init 恢复
 * - [validate] 对整批判校验（regex/name 对整批相同），任一为空拒绝整批并置 [inputError]；
 *   正则语法错误同样在拖入前拦截（非法正则不进 process 阶段）
 * - 正则命中预演 [regexPreview]：锚点文件（最近拖入批首文件，[addFiles] 捕获）在位且
 *   正则合法非空时，debounce 后对前 PREVIEW_MAX_LINES 行扫描出命中块数与首个命中块
 *   （仅前 N 行非全量）；无效正则/未拖文件不触发。预演与全量跑 [process] 共存不互斥
 * - 分布统计 [statsState]（[requestStats] 手动触发）：对锚点文件**全文件**统计
 *   Tag 频次/级别计数/首末时间戳跨度——写正则前先看分布再过滤，免盲试。全量 IO
 *   故不自动跑；结果面板自带文件名自证（换拖新文件后旧面板按名不误导）
 */
class LogTagFilterViewModel(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileTaskViewModel(ioDispatcher) {

    private val _tagRegex = MutableStateFlow("")
    val tagRegex: StateFlow<String> = _tagRegex.asStateFlow()

    private val _tagName = MutableStateFlow("")
    val tagName: StateFlow<String> = _tagName.asStateFlow()

    private val _regexValid = MutableStateFlow(true)
    val regexValid: StateFlow<Boolean> = _regexValid.asStateFlow()

    /** 正则错误原因（稿「错误四要素」口径：语法有误：$reason 带具体异常） */
    private val _regexErrorReason = MutableStateFlow("")
    val regexErrorReason: StateFlow<String> = _regexErrorReason.asStateFlow()

    /**
     * 正则命中预演锚点文件：最近一次拖入批的首文件（[addFiles] 捕获）。
     * 整批被 [validate] 拒绝（正则空/非法）也照常记录——「拖入被拒 → 边改正则边预演」
     * 正是预演的主场景。null = 尚无锚点，预演不触发。
     */
    private val _previewFile = MutableStateFlow<File?>(null)

    /** [regexPreview] 的当前值非空 ⇔ 锚点文件在位且正则合法非空 */
    private val _regexPreview = MutableStateFlow<TagRegexPreviewState?>(null)
    val regexPreview: StateFlow<TagRegexPreviewState?> = _regexPreview.asStateFlow()

    /** 分布统计面板态；[requestStats] 手动触发迁移（Idle → Running → Ready/Failed） */
    private val _statsState = MutableStateFlow<LogTagStatsState>(LogTagStatsState.Idle)
    val statsState: StateFlow<LogTagStatsState> = _statsState.asStateFlow()

    /** 最近一次统计导出产物路径（统计面板产物行回显；null = 本会话尚未导出过） */
    private val _exportedStatsPath = MutableStateFlow<String?>(null)
    val exportedStatsPath: StateFlow<String?> = _exportedStatsPath.asStateFlow()

    /**
     * 「看分布」手动触发：对预演锚点文件（[addFiles] 捕获的最近拖入批首文件）做
     * 全文件分布统计（块级 Tag 频次 / 级别计数 / 首末时间戳跨度）。
     *
     * - 锚点缺席（尚未拖入文件）置 [LogTagStatsState.Failed] 给可操作提示，不静默
     * - 进行中（Running）重复点击忽略：单遍流式读内存有界，无需取消面
     * - 读取失败折叠为 Failed（统计是辅助反馈，不弹全局错误）
     */
    fun requestStats() {
        val file = _previewFile.value
        if (file == null) {
            _statsState.value = LogTagStatsState.Failed("尚未拖入日志文件——先拖入文件再「看分布」")
            return
        }
        if (_statsState.value is LogTagStatsState.Running) return
        _statsState.value = LogTagStatsState.Running
        viewModelScope.launch {
            // 全文件扫描在 [ioDispatcher]（不占主线程）；Running 态已挡重复触发，无需 collectLatest 取消
            val state = withContext(ioDispatcher) { LogStatsAnalyzer.analyze(file) }
            _statsState.value = when (state) {
                is LogStatsResult.Ok -> LogTagStatsState.Ready(
                    fileName = file.name,
                    totalBlocks = state.totalBlocks,
                    unparsedTagHeads = state.totalBlocks - state.tagCounts.sumOf { it.count },
                    unparsedLevelHeads = state.totalBlocks - state.levelCounts.values.sum(),
                    topTags = state.tagCounts.take(STATS_TOP_TAGS).map { it.tag to it.count },
                    totalTagCount = state.tagCounts.size,
                    levelCounts = state.levelCounts.entries
                        .sortedByDescending { it.key.ordinal }
                        .map { it.key.name to it.value },
                    firstTimestampText = state.firstTimestamp?.format(STATS_TIMESTAMP_FORMATTER),
                    lastTimestampText = state.lastTimestamp?.format(STATS_TIMESTAMP_FORMATTER),
                    spanMillis = state.spanMillis,
                )
                is LogStatsResult.Failure -> LogTagStatsState.Failed(state.reason)
            }
        }
    }

    /**
     * 统计导出写盘分流（[exportStats] 与 [sendStatsToLark] 共用；回执 toast 归各
     * 调用方——导出与外发的失败文案口径不同，不在此归一）。
     *
     * 数据源 = 调用方传入的 [LogTagStatsState.Ready] 快照——与面板展示同源单数据，
     * 不重扫文件（导出即所见）；写盘在 [ioDispatcher]，取消信号上抛。
     */
    private suspend fun writeStatsExport(stats: LogTagStatsState.Ready): Result<File> =
        withContext(ioDispatcher) {
            runCatching {
                val exportedAt = System.currentTimeMillis()
                val md = StatsMarkdownExporter.exportMarkdown(stats, exportedAt)
                val file = StatsMarkdownExporter.exportFileOf(exportedAt)
                file.parentFile?.mkdirs()
                file.writeText(md)
                file
            }
        }

    /**
     * 统计结果导出为可分享 Markdown：`~/Documents/Toolbelt/reports/stats-<秒级时间键>.md`
     * （[StatsMarkdownExporter] 承载内容装配与路径决策）。
     *
     * - 数据源 = [statsState] 现值 [LogTagStatsState.Ready] 快照——与面板展示
     *   **同源单数据，不重扫文件**（导出即所见）；非 Ready 态静默返回（可感知
     *   置灰由面板导出钮承担——面板仅 Ready 态组合，按钮恒可达时本守卫只兜底）；
     * - 成败回执经 [StatusSignals] toast（导出是辅助动线，不折进统计面板态机）；
     * - 产物落点 = 用户可见产物树（与日志工作台报告导出同根），不进应用内部数据根。
     */
    fun exportStats() {
        val stats = _statsState.value as? LogTagStatsState.Ready ?: return
        viewModelScope.launch {
            writeStatsExport(stats).fold(
                onSuccess = { file ->
                    _exportedStatsPath.value = file.absolutePath
                    StatusSignals.show("已导出统计 Markdown：${file.absolutePath}")
                },
                onFailure = { e ->
                    StatusSignals.show("统计导出失败：${e.message ?: e.javaClass.simpleName}")
                },
            )
        }
    }

    /**
     * 一键送飞书：导出（发送前恒重新导出即恒最新统计快照——工作台报告送出动作
     * 同款先例）→ 经 :core [ReportDelivery] 中立槽以文件消息直发桥接接收会话。
     *
     * - 非 Ready 态静默返回（可感知置灰由统计面板送出钮承担——面板仅 Ready 态
     *   组合，本守卫只兜底非 UI 调用面，同 [exportStats]）；
     * - 失败人话由通道归一（未接线/未运行/未配置会话/出站拒绝/传输失败），
     *   此处只做回执 toast——置灰判据在统计面板（statsLarkSendDisabledReason）；
     * - [ReportDelivery.Channel.deliver] 的 null = 已送出**成功**语义——不可
     *   elvis 兜底（会把成功吞成失败），先收 reason 再判空分叉回执。
     */
    fun sendStatsToLark() {
        val stats = _statsState.value as? LogTagStatsState.Ready ?: return
        viewModelScope.launch {
            writeStatsExport(stats).fold(
                onSuccess = { file ->
                    _exportedStatsPath.value = file.absolutePath
                    val reason = ReportDelivery.deliver(file)
                    if (reason == null) {
                        StatusSignals.show("已送出至飞书会话：${file.name}")
                    } else {
                        StatusSignals.show(reason)
                    }
                },
                onFailure = { e ->
                    StatusSignals.show("导出失败，无法送飞书：${e.message ?: e.javaClass.simpleName}")
                },
            )
        }
    }

    /**
     * 统计面板 Tag 频次行回填：Tag 字面值逐字转义正则元字符后**覆盖**写入 [tagRegex]
     * （复用 [onTagRegexChange] 既有链路——校验即时重算 / debounce 落盘 / 预演自动跟随）。
     *
     * 匹配语义（与既有过滤语义对齐，不引入第二套口径）：
     * - 包含匹配、不加 `^`/`$` 锚——块命中是块内任一行 find() 的包含语义
     *   （[LogTagFilter]），块头行自带时间戳/级别前缀，`^Tag$` 形态永不命中；
     * - 逐字转义——统计行 Tag 取自日志原文，可含 `(` 等元字符；不转义则回填出
     *   非预期正则（点错比手抄更糟）。只用元字符级转义（[escapeRegexLiteral]），
     *   不用 [Regex.escape] 的 `\Q…\E` 整段包裹——回填值直接呈现在输入框里，
     *   包裹形态不可读，且用户后续手改时易误伤包裹配对；
     * - 覆盖非追加——点击语义 = 现在就过滤这个 Tag；与既有正则的追加组合
     *   无既有语法先例，不发明；输入框即时可见新值即回执，不另设提示态。
     *
     * 空 Tag 不回填（置空正则须走输入框显式清空）。
     */
    fun backfillTagRegex(tag: String) {
        if (tag.isEmpty()) return
        onTagRegexChange(escapeRegexLiteral(tag))
    }

    /** Java Pattern 语义下的正则元字符集（转义目标；`-` 仅在字符类内为元字符，不在列） */
    private val regexMetaChars = "\\.^$|?*+()[]{}"

    /** 字面值逐字转义：只转义元字符、其余原样保留——转义结果保持人可读 */
    private fun escapeRegexLiteral(literal: String): String = buildString {
        for (c in literal) {
            if (c in regexMetaChars) append('\\')
            append(c)
        }
    }

    init {
        viewModelScope.launch {
            _tagRegex.value = LogKitConfigStore.getLogTagRegex()
            _tagName.value = LogKitConfigStore.getLogTagName()
            // 回填后重算校验态：持久化的坏正则（上次会话输入未修正即退出）若不重编，
            // _regexValid 恒默认 true——卡面与真实语法态背离，拖入才在 validate 撞错
            recomputeRegexValidation(_tagRegex.value)
        }
    }

    // Tag 输入 400ms debounce（每键击落盘 ⇒ 整文件重写风暴；
    //    配方同桥接页）。Pair 汇聚两个字段，一次 debounce 通道落两值（键击交错时以末值为准）。
    private val tagFieldChanges = MutableStateFlow("" to "")

    init {
        viewModelScope.launch {
            tagFieldChanges
                .debounce(400.milliseconds)
                .collect { (regex, name) ->
                    if (regex.isNotEmpty()) LogKitConfigStore.setLogTagRegex(regex)
                    if (name.isNotEmpty()) LogKitConfigStore.setLogTagName(name)
                }
        }
    }

    fun onTagRegexChange(value: String) {
        _tagRegex.value = value
        // 输入即时校验（空串视为未填写、不置错——同 validate 的空值语义）
        recomputeRegexValidation(value)
        _inputError.value = null
        tagFieldChanges.value = value to _tagName.value
    }

    /** 正则编译校验链（输入与 init 回填共用——空串视为未填写不置错，语义同 validate） */
    private fun recomputeRegexValidation(value: String) {
        val result = runCatching { Regex(value) }
        _regexValid.value = value.isBlank() || result.isSuccess
        _regexErrorReason.value = result.exceptionOrNull()?.message?.take(REGEX_ERROR_HINT_CHARS) ?: ""
    }

    fun onTagNameChange(value: String) {
        _tagName.value = value
        _inputError.value = null
        tagFieldChanges.value = _tagRegex.value to value
    }

    /**
     * 捕获预演锚点后走基类既有流程。锚点捕获先于 [validate]（拒批也记录），
     * 其余行为（校验/建卡/冲突裁决）与基类逐字一致。
     */
    override fun addFiles(files: List<File>) {
        files.firstOrNull()?.let { _previewFile.value = it }
        super.addFiles(files)
    }

    // 正则命中预演：tagRegex/锚点文件任一变化 → debounce → 前 PREVIEW_MAX_LINES 行扫描。
    // 触发门槛三则（任一不满足置回 null）：锚点文件在位 / 正则非空 / 正则可编译——
    // 与输入框 isError 消费同一 [recomputeRegexValidation] 校验口径。
    init {
        viewModelScope.launch {
            combine(_tagRegex, _previewFile) { regex, file -> regex to file }
                .debounce(PREVIEW_DEBOUNCE_MS.milliseconds)
                .collectLatest { (regex, file) ->
                    val trimmed = regex.trim()
                    if (file == null || trimmed.isEmpty() || runCatching { Regex(trimmed) }.isFailure) {
                        _regexPreview.value = null
                        return@collectLatest
                    }
                    // 扫描在 [ioDispatcher]（文件读取不占主线程）；collectLatest 保证新一轮
                    // 输入取消在途扫描，旧结果不覆盖新输入
                    val state = withContext(ioDispatcher) {
                        when (val result = LogTagFilter.previewByTag(file, trimmed, PREVIEW_MAX_LINES)) {
                            is TagPreviewResult.Ok -> TagRegexPreviewState(
                                fileName = file.name,
                                scannedLines = result.scannedLines,
                                truncated = result.truncated,
                                totalBlocks = result.totalBlocks,
                                matchedBlocks = result.matchedBlocks,
                                skippedTailBlocks = result.skippedTailBlocks,
                                fallbackMode = result.fallbackMode,
                                firstMatchedBlockPreview = result.firstMatchedBlockPreview,
                                firstMatchedBlockLineCount = result.firstMatchedBlockLineCount,
                            )
                            // 预演读取失败（文件被移走等）不弹错误——预演是辅助反馈，
                            // 全量跑的任务卡才是错误的正式呈现面
                            is TagPreviewResult.Failure -> null
                        }
                    }
                    _regexPreview.value = state
                }
        }
    }

    override fun validate(files: List<File>): Validation {
        // 整批判校验：regex/name 对整批相同，无需逐文件校验
        val regex = _tagRegex.value.trim()
        val name = _tagName.value.trim()
        if (regex.isEmpty()) return Validation.Error("Tag 正则不能为空")
        if (name.isEmpty()) return Validation.Error("输出标记不能为空") // 三面同词（页面/命令面/校验面）
        // 正则语法错误在拖入前拦截。文案四要素：问题定位（Tag 正则语法有误）+ 原因（编译错误前
        // 120 字）+ 修正指引（修正正则后重新拖入）+ 内容保留（输入框不清空，重拖即用已输入值）。
        // runCatching 而非 Pattern.compile 直试：Kotlin Regex 编译失败即抛，双写语义一致
        // （log-tools 侧 Pattern.compile 防御保留——CLI 直调路径仍需兜底，双保险）。
        runCatching { Regex(regex) }.onFailure { e ->
            return Validation.Error(
                "Tag 正则语法有误（${e.message?.take(REGEX_ERROR_HINT_CHARS) ?: "未知原因"}），" +
                    "已输入内容已保留，修正正则后重新拖入",
            )
        }
        return Validation.Passed
    }

    /** 产物前缀（`<名>.filter-tag_`——tagName 参与命名，精确文件名随输入漂移，收口按前缀扫） */
    private fun outputFilePrefixOf(file: File): String = "${file.nameWithoutExtension}.filter-tag_"

    /** 精确产物文件（与库层 filterByTag 同一命名函数——预判/清理与库写必须逐字一致） */
    private fun outputFileOf(file: File): File =
        File(file.parent ?: ".", "${file.nameWithoutExtension}.filter-tag_${LogTagFilter.sanitizeTagName(_tagName.value.trim())}.txt")

    override fun outputTargetsOf(file: File): Set<String> = emptySet()

    /** 重跑冲突检测：本 tag 名产物精确在盘（库层 exists 幂等硬拒的死端面） */
    override fun existingOutputConflictsOf(file: File): List<File> =
        outputFileOf(file).takeIf { it.isFile }?.let(::listOf) ?: emptyList()

    /** 覆盖清理：删精确冲突产物（前缀命中的他 tagName 产物不动——那是别的运行结果） */
    override fun deleteStaleOutputsOf(file: File) {
        outputFileOf(file).delete()
    }

    /**
     * 取消收口：按产物前缀扫同目录（tagName 漂移时精确名不可预知），在盘即如实回填。
     * 不删：无法区分本次运行的半成品与提交前已存在的历史产物，删除有破坏历史产物风险。
     */
    override fun onTaskCancelled(file: File): String? {
        val prefix = outputFilePrefixOf(file)
        val first = (file.parentFile ?: File("."))
            .listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.firstOrNull() ?: return null
        return first.absolutePath
    }

    override suspend fun process(file: File): ProcessResult {
        val result = LogTagFilter.filterByTag(
            file = file,
            tagRegex = _tagRegex.value.trim(),
            tagName = _tagName.value.trim(),
        )
        return when (result) {
            is ToolsResult.Success -> {
                // 使用计数口径：过滤执行成功 = 一次真实使用
                ToolUsageSignal.report(ToolUsageSignal.PageId.LOG_TAG_FILTER)
                ProcessResult.Success(result.outputFile.absolutePath)
            }
            is ToolsResult.Failure -> ProcessResult.Failure(result.reason)
        }
    }

    companion object {
        /** 正则错误提示的原因截断上限（四要素文案中「原因」段前 120 字） */
        internal const val REGEX_ERROR_HINT_CHARS = 120

        /**
         * 预演扫描行数上限：读取+块扫描毫秒级完成（界面刷新秒级内），按设备日志
         * 典型 5~20 行/块覆盖上百块——样本足够判断正则命中面，而免全文件 IO。
         * 预演计数仅基于前 PREVIEW_MAX_LINES 行、非全量——UI 文案必须呈现该语义，
         * 防用户把预演计数误读为全文件结果。
         */
        internal const val PREVIEW_MAX_LINES = 2000

        /** 预演输入 debounce：对齐 tagFieldChanges 落盘通道的即时反馈节奏 */
        internal const val PREVIEW_DEBOUNCE_MS = 300L

        /**
         * 分布统计 Tag 表呈现上限（其余以「共 N 个 Tag」汇总行呈现）——几万行日志的
         * 去重 Tag 数可上百，全表入面板滚动不可读，先看头部分布已够决策过滤哪个 Tag
         */
        internal const val STATS_TOP_TAGS = 10

        /** 分布统计首末时间戳呈现格式（与 LogTimestamp 契约的年份格式同形） */
        internal val STATS_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        @Composable
        fun getViewModel(): LogTagFilterViewModel {
            return viewModel { LogTagFilterViewModel() }
        }
    }
}

/**
 * 正则命中预演快照（[LogTagFilterViewModel.regexPreview] 的载荷）。
 *
 * 各计数仅基于锚点文件前 [LogTagFilterViewModel.PREVIEW_MAX_LINES] 行、非全量
 * （[truncated] = true 时文件还有后续行未扫描）；[skippedTailBlocks] = 截断丢弃的
 * 尾块数（完整性不可判定，不参与命中统计）。消费方 UI 必须把「非全量」语义写进文案，
 * 防用户把预演计数误读为全文件结果。
 */
data class TagRegexPreviewState(
    val fileName: String,
    /** 实际扫描行数（≤ [LogTagFilterViewModel.PREVIEW_MAX_LINES]） */
    val scannedLines: Int,
    /** true = 文件长于扫描上限，计数非全量 */
    val truncated: Boolean,
    /** 扫描范围内识别出的完整块数（含未命中块） */
    val totalBlocks: Int,
    val matchedBlocks: Int,
    val skippedTailBlocks: Int,
    /** true = 文件无标准时间戳行首，块按空行分段切分（段落口径——UI 须如实提示防误读） */
    val fallbackMode: Boolean,
    /** 首个命中块的前若干行（无命中为空表） */
    val firstMatchedBlockPreview: List<String>,
    /** 首个命中块的总行数（预览截断时 > 预览行数） */
    val firstMatchedBlockLineCount: Int,
)

/**
 * 分布统计面板态（[LogTagFilterViewModel.statsState] 的载荷）。
 *
 * [Ready] 的全部计数基于**整个文件**（与预演 [TagRegexPreviewState] 的「仅前 N 行」
 * 相对）；[Ready.fileName] 是统计发生时的锚点文件名——换拖新文件后旧面板靠文件名
 * 自证不误导，新统计由用户按需再触发。
 */
sealed class LogTagStatsState {

    /** 未触发过统计（无面板） */
    object Idle : LogTagStatsState()

    /** 统计进行中（按钮 busy 态消费） */
    object Running : LogTagStatsState()

    /**
     * @property totalBlocks 完整块总数
     * @property unparsedTagHeads 头行解析不出 Tag 的块数
     * @property unparsedLevelHeads 头行解析不出级别的块数
     * @property topTags (Tag, 块数) 频次降序前 [LogTagFilterViewModel.STATS_TOP_TAGS] 项
     * @property totalTagCount 去重后全部可解析 Tag 总数
     * @property levelCounts (级别名, 块数) 严重度降序、仅非零
     * @property firstTimestampText 首时间戳文本（按值取 min）；全文无时间行为 null
     * @property lastTimestampText 末时间戳文本（按值取 max）；全文无时间行为 null
     * @property spanMillis 首末时间戳跨度毫秒数；任一端缺席为 null
     */
    data class Ready(
        val fileName: String,
        val totalBlocks: Int,
        val unparsedTagHeads: Int,
        val unparsedLevelHeads: Int,
        val topTags: List<Pair<String, Int>>,
        val totalTagCount: Int,
        val levelCounts: List<Pair<String, Int>>,
        val firstTimestampText: String?,
        val lastTimestampText: String?,
        val spanMillis: Long?,
    ) : LogTagStatsState()

    data class Failed(val reason: String) : LogTagStatsState()
}
