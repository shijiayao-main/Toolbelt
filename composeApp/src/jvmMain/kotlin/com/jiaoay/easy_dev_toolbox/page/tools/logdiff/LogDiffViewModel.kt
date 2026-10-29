package com.jiaoay.easy_dev_toolbox.page.tools.logdiff

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.log.LogDiff
import com.jiaoay.log.LogStatsAnalyzer
import com.jiaoay.log.LogStatsCompare
import com.jiaoay.log.LogStatsResult
import com.jiaoay.log.ToolsResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 日志对比页 VM：双文件参数区（基准 + 对比）与 [LogDiff] 引擎的接线层。
 *
 * 与四胞胎任务页（[com.jiaoay.easy_dev_toolbox.widget.task.FileTaskViewModel]）不同：
 * 对比是**单发双输入**动作（一次点击对两份文件产出一份结果），不是逐文件建卡的
 * 批任务流，故不继承任务流基类——本页自有 Running/Done/Failed 单发状态机。
 *
 * ## 双文件接收语义
 * 两槽各收一份文件；**一次拖入两份**（拖放区多放手势）按落入槽位次序填满两槽
 * （首份落本槽、次份落对侧槽），第三份起忽略并经 [StatusSignals] 明示跳过数。
 *
 * ## 拒收与门禁口径
 * - 「缺一拒收」：开始对比时两槽任一为空 → 不发起对比，置 [inputError]（文案指明
 *   缺哪个槽）——错误明示而非静默禁用按钮，用户无需猜原因；
 * - busy 门禁：对比进行中拖放区拒收换文件（FileDropZone busy 参数承接，两道回执
 *   组件内建）。
 *
 * ## 归一化开关语义
 * [normalize] 只在**发起对比时**采样进 [LogDiff.diff]——开关拨动不重跑在途结果；
 * 用户改开关后需再点「开始对比」才生效（保守默认：不做隐式自动重跑）。
 * 归一化是**行级比对键**布尔开关，对分布模式无意义（块级统计不比行），故仅行级
 * 模式可见生效。
 *
 * ## 两向模式与分立存储
 * [DiffMode] 采样语义与 [normalize] 同款：仅在 [startDiff] 发起点采样，模式切换
 * 不重跑/不清空任何在台结果。两向结果**分立存储**（[diffState] 与 [compareState]
 * 各持一套单发状态机）——切模式不丢在台结果，两侧可各自长期在台互不覆写。
 * 口径：分布对照回答块级 Tag/级别分布的增减面，**不是行级内容差异**——「哪几行
 * 不一样」归行级模式（[LogDiff] 引擎），两者正交，消费面不得混述。
 */
class LogDiffViewModel(
    /**
     * IO 线程域（VM 内全部阻塞 IO 段——引擎 diff 读文件、导出写盘——的承载调度器）。
     * 契约：默认 [Dispatchers.IO]，宿主装配点零改动；测试注入受控调度器
     * （与 Main 共享 TestScheduler 的 StandardTestDispatcher）后，VM 级 IO
     * 续体不飞真实 IO 线程——用例收尾 advanceUntilIdle 即确定性排空
     * （teardown 无需竞态宽限 sleep）。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** 对比槽位：OLD = 基准（old 侧），NEW = 对比（new 侧） */
    enum class Slot { OLD, NEW }

    /**
     * 两向模式：行级 = 逐行内容对比（[LogDiff] 引擎）；分布 = 基线对照「新鲜度视图」
     * （块级 Tag/级别分布的集合差分，引擎链 = [LogStatsAnalyzer.analyze] 单源 ×2 →
     * [LogStatsCompare.compare]）。分布对照 ≠ 行级内容差异：前者答「多了什么 Tag /
     * 哪个级别变多」，后者答「哪几行不一样」——两者正交，UI 文案不得混述。
     */
    enum class DiffMode { LINE, DISTRIBUTION }

    /** 分布对照单发状态机：Idle → Running → Done | Failed（与 [DiffUiState] 分立存储） */
    sealed class CompareUiState {
        data object Idle : CompareUiState()
        data object Running : CompareUiState()

        /**
         * @property compare 对照纯函数的结构化结果（[LogStatsCompare.CompareResult]——
         *         未识别块差值两侧分列、禁归因口径见其 KDoc）
         * @property oldFile 发起对照时捕获的基准文件（回显文件名取发起时现名）
         * @property newFile 发起对照时捕获的对比文件
         */
        data class Done(
            val compare: LogStatsCompare.CompareResult,
            val oldFile: File,
            val newFile: File,
        ) : CompareUiState() {
            /** 基准侧回显文件名 */
            val oldLabel: String get() = oldFile.name

            /** 对比侧回显文件名 */
            val newLabel: String get() = newFile.name
        }

        data class Failed(val reason: String) : CompareUiState()
    }

    /** 单发对比状态机：Idle → Running → Done | Failed（Failed 后可重新发起） */
    sealed class DiffUiState {
        data object Idle : DiffUiState()
        data object Running : DiffUiState()

        /**
         * @property result 引擎结构化结果（hunks 直接渲染、[LogDiff.DiffResult.Ok.degraded]
         *         必须明示给用户——超对齐上限时中间区域是整体替换而非逐行对齐）
         * @property oldFile 发起对比时捕获的基准文件（差异行跳转的目标——槽位事后
         *         换文件不得改变旧结果行的跳转目标，见 [openDiffLineInViewer]）
         * @property newFile 发起对比时捕获的对比文件
         */
        data class Done(
            val result: LogDiff.DiffResult.Ok,
            val oldFile: File,
            val newFile: File,
        ) : DiffUiState() {
            /** 导出头与结果摘要回显的文件名（发起时文件的现名） */
            val oldLabel: String get() = oldFile.name

            /** 对比侧回显文件名 */
            val newLabel: String get() = newFile.name
        }

        data class Failed(val reason: String) : DiffUiState()
    }

    /**
     * hunk 巡检指针：「上一处/下一处差异」连点导航的 VM 侧状态（null = 导航不可用，
     * 见 [hunkNav]）。非 null 时 count 恒 ≥1。
     *
     * @property index 当前 hunk 下标；-1 = 结果在台但尚未定位（首次推进落 0，
     *         不得自动跳走用户视线）
     * @property count 结果内 hunk 总数
     */
    data class HunkNav(val index: Int, val count: Int) {
        /** 首 hunk（含未定位态）回退不可用——按钮置灰的单一真源 */
        val canGoPrev: Boolean get() = index > 0

        /** 末 hunk 推进不可用——按钮置灰的单一真源 */
        val canGoNext: Boolean get() = index < count - 1

        /** 巡检位置回显文案；未定位（index = -1）时无位置可显返回 null */
        val positionLabel: String? get() = if (index >= 0) "第 ${index + 1} / $count 处" else null
    }

    private val _oldFile = MutableStateFlow<File?>(null)
    val oldFile: StateFlow<File?> = _oldFile.asStateFlow()

    private val _newFile = MutableStateFlow<File?>(null)
    val newFile: StateFlow<File?> = _newFile.asStateFlow()

    private val _normalize = MutableStateFlow(false)
    val normalize: StateFlow<Boolean> = _normalize.asStateFlow()

    /** 当前两向模式（发起点采样语义，见类 KDoc「两向模式与分立存储」） */
    private val _mode = MutableStateFlow(DiffMode.LINE)
    val mode: StateFlow<DiffMode> = _mode.asStateFlow()

    /** 提交前校验错误（缺一拒收文案）；新文件落入或重新发起时清除 */
    private val _inputError = MutableStateFlow<String?>(null)
    val inputError: StateFlow<String?> = _inputError.asStateFlow()

    private val _diffState = MutableStateFlow<DiffUiState>(DiffUiState.Idle)
    val diffState: StateFlow<DiffUiState> = _diffState.asStateFlow()

    /** 分布对照态（与 [diffState] 分立存储——切模式不丢在台结果） */
    private val _compareState = MutableStateFlow<CompareUiState>(CompareUiState.Idle)
    val compareState: StateFlow<CompareUiState> = _compareState.asStateFlow()

    /**
     * hunk 巡检指针；null = 导览不可用：无结果在台 / 全等（hunks 空表无 hunk 可巡）/
     * degraded（中间区域整体替换、未逐行对齐——逐 hunk 巡检无定位意义）。
     */
    private val _hunkNav = MutableStateFlow<HunkNav?>(null)
    val hunkNav: StateFlow<HunkNav?> = _hunkNav.asStateFlow()

    /** 导出回执（产物绝对路径行）；null = 无导出或导出失败（失败走 StatusSignals） */
    private val _exportReceipt = MutableStateFlow<String?>(null)
    val exportReceipt: StateFlow<String?> = _exportReceipt.asStateFlow()

    fun onFilesSelected(slot: Slot, files: List<File>) {
        if (files.isEmpty()) return
        // 一次拖入两份：首份落本槽、次份落对侧槽（换文件手势 = 单份拖入只覆写本槽；
        // 双份拖入视为「填满参数区」意图，对侧槽一并覆写）。第三份起忽略且明示
        // 跳过数——拒绝必带原因口径。
        if (files.size > 2) {
            StatusSignals.show("已接收前 2 个文件，其余 ${files.size - 2} 个忽略")
        }
        when (slot) {
            Slot.OLD -> {
                _oldFile.value = files.first()
                files.getOrNull(1)?.let { _newFile.value = it }
            }
            Slot.NEW -> {
                _newFile.value = files.first()
                files.getOrNull(1)?.let { _oldFile.value = it }
            }
        }
        _inputError.value = null
    }

    fun setNormalize(value: Boolean) {
        _normalize.value = value
    }

    /** 切换两向模式。只改模式指针：不重跑、不清空任何在台结果（分立存储语义） */
    fun setMode(mode: DiffMode) {
        _mode.value = mode
    }

    fun dismissInputError() {
        _inputError.value = null
    }

    /**
     * 发起对比（两向共用入口）：缺一拒收门禁两模式共用（文案同源），按发起点采样的
     * [mode] 分流行级 / 分布执行路径——两向结果分立落 [diffState] / [compareState]。
     */
    fun startDiff() {
        val old = _oldFile.value
        val new = _newFile.value
        // 缺一拒收：文案指明缺失槽位（拖了基准漏对比是主场景——裸「请选择文件」
        // 不指明缺哪个槽，用户两槽来回点）；两向模式共用同一门禁（分布对照同样
        // 需要两份文件）
        if (old == null || new == null) {
            _inputError.value = when {
                old == null && new == null ->
                    "对比需要两份文件——基准文件与对比文件均未选择，请分别拖入或点击选择"
                old == null ->
                    "对比需要两份文件——基准文件尚未选择，请拖入或点击选择基准文件后重新开始"
                else ->
                    "对比需要两份文件——对比文件尚未选择，请拖入或点击选择对比文件后重新开始"
            }
            return
        }
        when (_mode.value) {
            DiffMode.LINE -> startLineDiff(old, new)
            DiffMode.DISTRIBUTION -> startDistributionCompare(old, new)
        }
    }

    /** 行级执行路径：[LogDiff.diff] → [DiffUiState]；导出回执与巡检指针是行级独有面 */
    private fun startLineDiff(old: File, new: File) {
        if (_diffState.value is DiffUiState.Running) return
        _inputError.value = null
        _exportReceipt.value = null
        // 旧结果的巡检指针不跨次生效（新结果 hunk 数与边界均可能不同）
        _hunkNav.value = null
        _diffState.value = DiffUiState.Running
        viewModelScope.launch {
            val state = withContext(ioDispatcher) {
                // normalize 在发起点采样（开关语义见类 KDoc）
                when (val result = LogDiff.diff(old, new, _normalize.value)) {
                    is LogDiff.DiffResult.Ok -> DiffUiState.Done(result, oldFile = old, newFile = new)
                    is LogDiff.DiffResult.Failure -> DiffUiState.Failed(result.reason)
                }
            }
            _diffState.value = state
            val done = state as? DiffUiState.Done
            _hunkNav.value = done?.let {
                if (!it.result.degraded && it.result.hunks.isNotEmpty()) {
                    HunkNav(index = -1, count = it.result.hunks.size)
                } else {
                    null
                }
            }
        }
    }

    /**
     * 分布执行路径（基线对照「新鲜度视图」）：两份文件各一次 [LogStatsAnalyzer.analyze]
     * （块级统计单源，零第二套解析器）→ [LogStatsCompare.compare] 纯函数 → [CompareUiState]。
     * 不触行级独有面（导出回执/巡检指针）——分立存储：分布发起不清行级在台结果。
     */
    private fun startDistributionCompare(old: File, new: File) {
        if (_compareState.value is CompareUiState.Running) return
        _inputError.value = null
        _compareState.value = CompareUiState.Running
        viewModelScope.launch {
            val state = withContext(ioDispatcher) {
                val baseline = LogStatsAnalyzer.analyze(old)
                val current = LogStatsAnalyzer.analyze(new)
                when {
                    baseline is LogStatsResult.Ok && current is LogStatsResult.Ok ->
                        CompareUiState.Done(
                            LogStatsCompare.compare(baseline, current),
                            oldFile = old,
                            newFile = new,
                        )
                    baseline is LogStatsResult.Failure ->
                        CompareUiState.Failed("基准侧统计失败，${baseline.reason}")
                    else ->
                        CompareUiState.Failed("对比侧统计失败，${(current as LogStatsResult.Failure).reason}")
                }
            }
            _compareState.value = state
        }
    }

    /**
     * diff 导出写盘分流（[exportDiff] 与 [sendDiffToLark] 共用；回执 toast 归各调用方——
     * 导出与外发的失败文案口径不同，不在此归一）。
     *
     * 产物落点 = 对比文件旁 `<对比文件名>.diff`（与工作台报告/统计导出的 reports 产物树
     * **不同根**——diff 产物贴着对比文件落地，任意目录产物送出须经出站敏感文件排除表
     * 判）。引擎按覆盖语义写（对比常需改一份再比一次地反复导出——预删会丢「失败保旧
     * 产物」）。
     */
    private suspend fun writeDiffExport(done: DiffUiState.Done, new: File): Result<File> =
        withContext(ioDispatcher) {
            runCatching {
                val parent = new.parentFile
                val out = if (parent != null) File(parent, "${new.name}.diff") else File("${new.name}.diff")
                when (val result = LogDiff.writeUnified(done.result, done.oldLabel, done.newLabel, out)) {
                    is ToolsResult.Success -> result.outputFile
                    is ToolsResult.Failure -> error(result.reason)
                }
            }
        }

    /**
     * 导出 unified diff：产物落对比文件旁（`<对比文件名>.diff`），仅 Done 态可导出；
     * 其余态静默 no-op（按钮仅 Done 态渲染）。
     */
    fun exportDiff() {
        val done = _diffState.value as? DiffUiState.Done ?: return
        val new = _newFile.value ?: return
        viewModelScope.launch {
            writeDiffExport(done, new).fold(
                onSuccess = { file -> _exportReceipt.value = "已导出：${file.absolutePath}" },
                onFailure = { e ->
                    StatusSignals.show("导出失败：${e.message ?: e.javaClass.simpleName}")
                    _exportReceipt.value = null
                },
            )
        }
    }

    /**
     * 一键送飞书：导出（发送前恒重新导出即恒最新 diff——统计导出送出动作同款先例）
     * → 经 :core [ReportDelivery] 中立槽以文件消息直发桥接接收会话。
     *
     * - 仅 Done 态可发起（与 [exportDiff] 同款前置判据）；非 Done 态静默返回
     *   （按钮仅 Done 态渲染，本守卫只兜底非 UI 调用面）；
     * - 产物落对比文件旁而非 reports 产物树——送出经出站敏感文件排除表判：产物若落
     *   宿主机密根内或文件名命中凭据族，**命中被拒是设计行为**（排除表 fail-closed、
     *   不因调用方放宽），拒绝人话由通道归一经回执明示原因；
     * - [ReportDelivery.Channel.deliver] 的 null = 已送出**成功**语义——不可 elvis
     *   兜底（会把成功吞成失败），先收 reason 再判空分叉回执。
     */
    fun sendDiffToLark() {
        val done = _diffState.value as? DiffUiState.Done ?: return
        val new = _newFile.value ?: return
        viewModelScope.launch {
            writeDiffExport(done, new).fold(
                onSuccess = { file ->
                    _exportReceipt.value = "已导出：${file.absolutePath}"
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
     * 差异行 → 查看器跳转：打开 [slot] 侧文件的应用内查看器并定位到 [lineNumber]。
     *
     * ## 行号语义（引擎契约，两模式恒成立）
     * [lineNumber] 是该行在**原文件**中的 1 基行号。[LogDiff.Hunk.oldStart] /
     * [LogDiff.Hunk.newStart] 恒为原文件 0 基行下标——时间戳归一化只变换比对键
     * （[LogDiff] 对象 KDoc「归一化只参与比对，不改写产物」），不改变行号坐标系，
     * 故归一化模式与非归一化模式的跳转语义一致，无模式分支。
     *
     * ## 目标文件取发起时捕获（[DiffUiState.Done]）
     * 对比完成后槽位换文件不得改变旧结果行的跳转目标——按当前槽位文件跳会
     * 静默跳错行。非 Done 态静默 no-op（无结果即无行可跳）。
     */
    fun openDiffLineInViewer(slot: Slot, lineNumber: Int) {
        val done = _diffState.value as? DiffUiState.Done ?: return
        val file = if (slot == Slot.OLD) done.oldFile else done.newFile
        LogViewerRequest.offer(file.absolutePath, lineNumber)
    }

    /**
     * 送工作台分析：导出（发送前恒重新导出即恒最新 diff——[sendDiffToLark] 同款先例）
     * → 经 :core [DeepAnalyzeHook] 中立槽投递跳工作台预填（Tag 过滤/XLog/分割页
     * 产物直投同构配方；宿主 AppViewModel init 恒接线，本页只触槽）。
     *
     * 仅 Done 态可发起（与 [exportDiff]/[sendDiffToLark] 同款前置判据，本守卫只兜底
     * 非 UI 调用面）；槽未接线（offer 返回 false）回执人话不静默。
     */
    fun sendDiffToWorkbench() {
        val done = _diffState.value as? DiffUiState.Done ?: return
        val new = _newFile.value ?: return
        viewModelScope.launch {
            writeDiffExport(done, new).fold(
                onSuccess = { file ->
                    _exportReceipt.value = "已导出：${file.absolutePath}"
                    val offered = DeepAnalyzeHook.offer(
                        listOf(file.absolutePath),
                        "日志对比产物深度分析",
                    )
                    if (!offered) {
                        StatusSignals.show("深度分析通道不可用（宿主未接线）")
                    }
                },
                onFailure = { e ->
                    StatusSignals.show("导出失败，无法送工作台分析：${e.message ?: e.javaClass.simpleName}")
                },
            )
        }
    }

    /**
     * 巡检指针回退一处 hunk。导航不可用（[hunkNav] 为 null）或已处首 hunk 边界时
     * 静默 no-op——UI 按钮按 [HunkNav.canGoPrev] 置灰，此处边界钳制是禁用语义的
     * 数据侧双保险（不依赖按钮置灰才不越界）。
     */
    fun goToPrevHunk() {
        val nav = _hunkNav.value ?: return
        if (nav.index <= 0) return
        _hunkNav.value = nav.copy(index = nav.index - 1)
    }

    /**
     * 巡检指针推进一处 hunk。未定位态（index = -1）首次推进落 0；导航不可用或已处
     * 末 hunk 边界时静默 no-op（边界钳制语义见 [goToPrevHunk]）。
     */
    fun goToNextHunk() {
        val nav = _hunkNav.value ?: return
        if (nav.index >= nav.count - 1) return
        _hunkNav.value = nav.copy(index = nav.index + 1)
    }

    companion object {
        @Composable
        fun getViewModel(): LogDiffViewModel = viewModel { LogDiffViewModel() }
    }
}
