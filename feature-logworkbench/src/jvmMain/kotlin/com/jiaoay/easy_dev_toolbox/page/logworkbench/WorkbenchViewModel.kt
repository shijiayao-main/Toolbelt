package com.jiaoay.easy_dev_toolbox.page.logworkbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.DesktopUtils
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.KnowledgeRepository
import com.jiaoay.easy_dev_toolbox.core.data.pathFileName
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.data.db.SqliteSessionStore
import com.jiaoay.easy_dev_toolbox.core.evidence.ReportSections
import com.jiaoay.easy_dev_toolbox.core.evidence.findTimestampLineIn
import com.jiaoay.easy_dev_toolbox.core.evidence.parseReport
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.navigation.SpaceAttribution
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.log.pipeline.LogAnalysisPipeline
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日志工作台 VM：
 * - run 态机（IDLE→RUNNING→COMPLETED/ABORTED/FAILED）经 [RunStore] 持久化——
 *   一次分析 = 一个 run（sessionId=runId，时间线经 SessionStore）；
 * - 会话核心全权委托 [session]（reducer 直测缝在 [WorkbenchSession]）；
 * - 报告落盘：[phaseResult] → `~/.easy_dev_toolbox/logworkbench/runs/<runId>/report.md`
 *   + updateReportPath（🔴 禁落源码仓库/日志目录外任意位置——落点铁律：报告文件
 *   写进用户源码树/日志目录即污染其内容，运行目录外零落点）；
 * - HITL 弹窗态透传（pendingGate → Compose Dialog）。
 *
 * 直测缝纪律：VM 构造触达 Database.database() 真单例——承重纯函数
 * （[deriveRunTitle]/[reportFileOf]）companion 顶层化。
 */
class WorkbenchViewModel(
    /**
     * IO 线程域（VM 内全部阻塞 IO 段——SQLite/文件读写——的承载调度器）。
     * 契约：默认 [Dispatchers.IO]，宿主装配点零改动；测试注入受控调度器
     * （如与 Main 共享 TestScheduler 的 StandardTestDispatcher）后，init 与
     * 收尾协程不再飞在真实 IO 线程上——用例收尾 advanceUntilIdle 即确定性
     * 排空，无跨用例在途续体。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val store = RunStore(Database.database())
    private val timeline = SqliteSessionStore(Database.database())

    /** 当前活跃会话（新 run 即新会话——runId 与 sessionId 同值；VM 销毁即废弃） */
    private var session: WorkbenchSession = newSession(PENDING_RUN_ID)

    /** phaseResult 收尾收集器单句柄（每次发起研判置换——防收集器无界累积 + 陈值重放） */
    private var phaseResultCollectJob: kotlinx.coroutines.Job? = null

    /** 管线直跑在途位（busy 渲染/中止钮/cancel 闸/重入守卫四面共用——session.running 不覆盖直跑） */
    private val _pipelineRunning = MutableStateFlow(false)
    val pipelineRunning: StateFlow<Boolean> = _pipelineRunning.asStateFlow()

    private var pipelineJob: kotlinx.coroutines.Job? = null

    /** 结构化输入表单态（左栏消费面） */
    private val _logFiles = MutableStateFlow<List<String>>(emptyList())
    val logFiles: StateFlow<List<String>> = _logFiles.asStateFlow()

    private val _targetTime = MutableStateFlow("")
    val targetTime: StateFlow<String> = _targetTime.asStateFlow()

    private val _rangeMinutes = MutableStateFlow(5L)
    val rangeMinutes: StateFlow<Long> = _rangeMinutes.asStateFlow()

    private val _symptom = MutableStateFlow("")
    val symptom: StateFlow<String> = _symptom.asStateFlow()

    private val _tagRegex = MutableStateFlow("")
    val tagRegex: StateFlow<String> = _tagRegex.asStateFlow()

    /** run 列表（左栏历史——listRuns 倒序） */
    private val _runs = MutableStateFlow<List<RunStore.RunSummary>>(emptyList())
    val runs: StateFlow<List<RunStore.RunSummary>> = _runs.asStateFlow()

    /** 当前 run 的报告文本（右栏消费面——null = 尚未生成） */
    private val _reportText = MutableStateFlow<String?>(null)
    val reportText: StateFlow<String?> = _reportText.asStateFlow()

    /**
     * 回看第三态：reportPath 在而报告文件读取失败（文件被手工清理/迁移——run 记录
     * 仍在，与「从未生成过报告」不可混淆折叠成空态误导重跑）。非 null = 读失败的
     * reportPath，右栏渲染错误态并给「打开所在目录」出路。
     */
    private val _reportReadError = MutableStateFlow<String?>(null)
    val reportReadError: StateFlow<String?> = _reportReadError.asStateFlow()

    init {
        refreshRuns()
        normalizeOrphanRuns()
    }

    /**
     * 孤儿 RUNNING 归一化：VM 构造期本进程必无在途 run（runJob 活在 viewModelScope，
     * 上一实例销毁即死，库行无人收口）——启动时库里 RUNNING 行全部置 FAILED，
     * 「库行状态 ≠ RUNNING」跨重启恒成立（不谎报运行中、ABORTED 语义不被孤儿挤占）。
     */
    private fun normalizeOrphanRuns() {
        viewModelScope.launch {
            val orphans = withContext(ioDispatcher) {
                store.listRuns().filter { it.status == RunStore.RunStatus.RUNNING }.map { it.id }
            }
            if (orphans.isEmpty()) return@launch
            orphans.forEach { store.updateStatus(it, RunStore.RunStatus.FAILED) }
            refreshRuns()
        }
    }

    // ── 表单态 ──

    fun setLogFiles(files: List<String>) {
        _logFiles.value = files
    }

    fun addTargetFile(path: String) {
        if (path.isNotBlank() && path !in _logFiles.value) {
            _logFiles.value = _logFiles.value + path
        }
    }

    fun removeTargetFile(path: String) {
        _logFiles.value = _logFiles.value - path
    }

    fun setTargetTime(text: String) {
        _targetTime.value = normalizeTargetTime(text)
    }

    fun setTargetTimeToNow() {
        _targetTime.value = targetTimeFormat.format(java.time.LocalDateTime.now())
    }

    /** 宽松归一：接受空格/T 分隔与省略/短毫秒写法，归一到下游管线严格格式；不匹配则原样保留（错误交由下游报，不静默改写） */
    private fun normalizeTargetTime(text: String): String {
        val t = text.trim()
        val m = looseTargetTime.matchEntire(t) ?: return t
        val millis = m.groupValues[3].padEnd(3, '0')
        return "${m.groupValues[1]}_${m.groupValues[2]}.$millis"
    }

    /** 宽松目标时间形态：`日期[空格/T]时分秒[.毫秒 1-3 位]`（缺毫秒自动补 .000） */
    private val looseTargetTime = Regex("""(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})(?:\.(\d{1,3}))?""")

    /** 目标时间严格格式（LogAnalysisPipeline 下游解析契约同值） */
    private val targetTimeFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss.SSS")

    fun setRangeMinutes(value: Long) {
        _rangeMinutes.value = value
    }

    fun setSymptom(text: String) {
        _symptom.value = text
    }

    fun setTagRegex(text: String) {
        _tagRegex.value = text
    }

    // ── 分析动作 ──

    /** 开始分析：新建 run（落 RunStore）→ 新会话 → startAnalysis */
    fun startAnalysis() {
        if (session.running.value) return
        if (_logFiles.value.isEmpty() || _symptom.value.isBlank()) {
            // 拒绝必带原因：回看只回填文件/时间/范围三字段（symptom 留空），确认卡
            // 路径触发本方法时此守卫是唯一拒面——静默 return 即「开始分析」零响应
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                if (_logFiles.value.isEmpty()) "先拖入日志文件" else "先填写症状描述",
            )
            return
        }

        val runId = "run-${UUID.randomUUID()}"
        // 使用计数口径：研判发起 = 一次真实使用
        ToolUsageSignal.report(ToolUsageSignal.PageId.LOG_WORKBENCH)
        val title = deriveRunTitle(_logFiles.value, _targetTime.value)
        store.create(
            id = runId,
            title = title,
            logFiles = _logFiles.value,
            targetTime = _targetTime.value.ifBlank { null },
            rangeMinutes = _rangeMinutes.value,
            status = RunStore.RunStatus.RUNNING,
        )
        // 空间归属：run 创建成功 → 记入当前调查空间
        //（:core 中立槽——无选中空间静默跳过；失败不拖垮研判发起——槽内 JLog 收口）
        viewModelScope.launch { attributeRunSafely(runId) }
        session = createSession(runId)
        WorkbenchSession.attach(session, viewModelScope)

        // 收尾 watcher：phaseResult 非空 → 报告落盘 + run 状态收口。
        // 🔴 单句柄置换（cancel 旧收集器再挂新的）：无界累积会让 N 次研判挂 N 个
        //    收集器并发写盘竞速，陈 collector 立即重放旧 phaseResult 假翻 COMPLETED。
        //    先挂后发在此路径安全：上面刚置换全新会话，其 phaseResult 恒 null 起点。
        val sessionRef = session
        phaseResultCollectJob?.cancel()
        phaseResultCollectJob = viewModelScope.launch {
            sessionRef.phaseResult.collect { result ->
                if (result != null) {
                    settleRun(runId, result, RunStore.RunStatus.COMPLETED)
                }
            }
        }
        session.startAnalysis(
            input = WorkbenchSession.AnalysisInput(
                logFiles = _logFiles.value,
                targetTime = _targetTime.value,
                rangeMinutes = _rangeMinutes.value,
                symptom = _symptom.value,
                tagRegex = _tagRegex.value.ifBlank { null },
            ),
        )
    }

    /**
     * 管线直跑（AI 关闭降级——spec「确定性管线可直接跑」）：不经 LLM，
     * [LogAnalysisPipeline.run] 全级执行，各阶段产物照常登记证据面 + run 状态收口。
     *
     * 运行态三面：[_pipelineRunning] 进 busy 渲染/中止钮/cancel 闸/本守卫与 openRun
     * 深链设防；协程体捕获 [session] 局部引用——发起后 session 被置换（如深链 openRun）
     * 产物仍写回发起会话。
     */
    fun runPipelineOnly() {
        if (session.running.value || _pipelineRunning.value) return
        val first = _logFiles.value.firstOrNull() ?: return

        // 表单快照（发起时意图——防协程执行期表单被改后管线读到漂移值）
        val logFiles = _logFiles.value
        val targetTime = _targetTime.value
        val rangeMinutes = _rangeMinutes.value
        val tagRegex = _tagRegex.value.ifBlank { null }

        // 多文件 + 管线直跑时明示只分析首个——AI 路径全文件、
        // 降级路径静默忽略其余，两条路径行为不一致且降级路径更弱；多文件支持
        // 需 :log-tools LogAnalysisPipeline 扩 Request（本期只做明示）
        if (logFiles.size > 1) {
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                "确定性管线仅分析 ${logFiles.size} 个文件中的第 1 个" +
                    "（${pathFileName(first)}）——其余需 AI 研判",
            )
        }

        val runId = "run-${UUID.randomUUID()}"
        // 使用计数口径：管线直跑同为研判发起 = 一次真实使用
        ToolUsageSignal.report(ToolUsageSignal.PageId.LOG_WORKBENCH)
        store.create(
            id = runId,
            title = deriveRunTitle(logFiles, targetTime),
            logFiles = logFiles,
            targetTime = targetTime.ifBlank { null },
            rangeMinutes = rangeMinutes,
            status = RunStore.RunStatus.RUNNING,
        )
        // 空间归属：直跑 run 同为研判发起——记入当前调查空间
        viewModelScope.launch { attributeRunSafely(runId) }
        session = createSession(runId)
        WorkbenchSession.attach(session, viewModelScope)
        val sessionRef = session
        _pipelineRunning.value = true
        pipelineJob = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    LogAnalysisPipeline.run(
                        LogAnalysisPipeline.Request(
                            logFile = File(first),
                            privateKey = com.jiaoay.easy_dev_toolbox.core.llm.tools.DecryptTools.defaultPrivateKeyProvider(),
                            targetTime = targetTime,
                            rangeMinutes = rangeMinutes,
                            tagRegex = tagRegex,
                            tagName = tagRegex?.take(40),
                        ),
                    )
                }
                // 阶段产物登记证据面（直跑无 sink 挂接——手工回补；写回发起会话非当前会话）
                val artifact = (result as? LogAnalysisPipeline.StageResult.Ok)?.artifact
                if (result is LogAnalysisPipeline.StageResult.Ok && artifact != null) {
                    sessionRef.registerFiles(listOf(artifact))
                    sessionRef.addPipelineArtifact(artifact, result.stage, result.note)
                }
                val status = if (result is LogAnalysisPipeline.StageResult.Ok) {
                    RunStore.RunStatus.COMPLETED
                } else {
                    RunStore.RunStatus.FAILED
                }
                if (result is LogAnalysisPipeline.StageResult.Abort) {
                    sessionRef.appendPipelineAbort(result.stage, result.reason)
                }
                settleRun(runId, pipelineSummary(result), status)
            } finally {
                // 取消/异常路径同样复位（finally 兜底——busy 恒不自锁）
                _pipelineRunning.value = false
            }
        }
    }

    /**
     * 中止：进度保全（已产条目/证据保留）+ run 状态 ABORTED。
     * 直跑与 AI 研判双路都可达——直跑在途时取消 pipelineJob（阻塞实现的管线调用
     * 不被强停，取消在 withContext 边界被观察到，迟到结果静默丢弃不收口）。
     */
    fun cancel() {
        if (_pipelineRunning.value) {
            pipelineJob?.cancel()
            _pipelineRunning.value = false
            store.updateStatus(currentRunId(), RunStore.RunStatus.ABORTED)
            return
        }
        if (!session.running.value) return
        session.cancel()
        store.updateStatus(currentRunId(), RunStore.RunStatus.ABORTED)
    }

    val aiEnabled: StateFlow<Boolean> =
        com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal.enabled

    /** 追问（报告次级按钮发起——增量研判，原结论不改写） */
    fun followUp(question: String) {
        if (session.running.value || _pipelineRunning.value || question.isBlank()) return
        if (currentRunId() == PENDING_RUN_ID) return
        store.updateStatus(currentRunId(), RunStore.RunStatus.RUNNING)
        val sessionRef = session
        val runId = currentRunId()
        // 🔴 先发起再挂 watcher：追问不换会话，phaseResult 仍持上轮结果——Main.immediate
        //    下 launch 即跑、StateFlow 对新收集器立即重放当前值，先挂后发会让旧报告在
        //    发起前被当增量收口（旧+旧合并覆写报告 + 双 settle 写盘竞速）。startAnalysis
        //    入口先置空 _phaseResult，收集器挂上时只收得到 null 起点（失败回执走
        //    onRunFailed 回调，不依赖收集器，晚挂不漏收口）。
        session.startAnalysis(
            input = WorkbenchSession.AnalysisInput(
                logFiles = _logFiles.value,
                targetTime = _targetTime.value,
                rangeMinutes = _rangeMinutes.value,
                symptom = question,
                tagRegex = _tagRegex.value.ifBlank { null },
            ),
            followUpContext = _reportText.value,
        )
        phaseResultCollectJob?.cancel()
        phaseResultCollectJob = viewModelScope.launch {
            sessionRef.phaseResult.collect { result ->
                if (result != null) {
                    // 增量研判：新报告 = 旧报告 + 增量段（原结论不改写——spec 追问形态）
                    val merged = mergeFollowUp(_reportText.value, result)
                    settleRun(runId, merged, RunStore.RunStatus.COMPLETED)
                }
            }
        }
    }

    /** run 收尾统一口：报告落盘 + updateReportPath + 状态 + 刷新列表 */
    private fun settleRun(runId: String, report: String, status: RunStore.RunStatus) {
        viewModelScope.launch {
            val file = reportFileOf(runId)
            withContext(ioDispatcher) {
                file.parentFile?.mkdirs()
                file.writeText(report)
                // 空间归属：报告 = 调查产物成员——落盘后记入
                // 当前调查空间（:core 中立槽——去重保序幂等，followUp 增量落盘不重复记；
                // 无选中空间静默跳过；失败不拖垮收尾——槽内 JLog 收口）
                SpaceAttribution.attributeFiles(listOf(file.absolutePath))
            }
            store.updateReportPath(runId, file.absolutePath)
            store.updateStatus(runId, status)
            _reportText.value = report
            // 新报告已落盘——读取失败态随之失效（同 run 重跑/追问增量后路径必可读）
            _reportReadError.value = null
            refreshRuns()
        }
    }

    private suspend fun attributeRunSafely(runId: String) {
        withContext(ioDispatcher) { SpaceAttribution.attributeRun(runId) }
    }

    // ── run 管理面 ──

    fun refreshRuns() {
        viewModelScope.launch {
            _runs.value = withContext(ioDispatcher) { store.listRuns() }
        }
    }

    /** 当前 runId（UI 高亮判定用——恒有值；Compose collect 面不持引用） */
    fun currentRunIdForUi(): String = session.runIdValue

    /**
     * 查看器 run 级书签锚（当前 run）：占位会话（尚无 run）返回 null——书签不
     * 落占位键。工作台产物打开请求携带（[com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest]），
     * 查看器据此按 (runId, 文件路径) 快照持久化与双锚恢复。
     */
    fun viewerBookmarkRunId(): String? = session.runIdValue.takeIf { it != PENDING_RUN_ID }

    fun hasUncommittedContent(): Boolean =
        session.entries.value.isNotEmpty() && session.runIdValue != PENDING_RUN_ID

    /**
     * 深链预填：文件、症状、问题时间点三载荷各按各自门控写入。
     *
     * [targetTime] 非空/非空白时经 [normalizeTargetTime] 归一写入目标时间框——与
     * [setTargetTime] 同一归一面，不另造第二套写入路径；null/空白不触碰时间域
     * （深链不带时间点时用户手填值原样保留，零回归）。宽松形态（空格分隔、省略/
     * 短毫秒）归一到严格格式，不可解析形态原样保留（错误交由下游报，不静默改写）。
     */
    fun prefill(logFiles: List<String>, prefillNote: String?, targetTime: String? = null) {
        if (session.running.value) return
        if (logFiles.isNotEmpty()) {
            _logFiles.value = logFiles.distinct()
        }
        if (!prefillNote.isNullOrBlank()) {
            _symptom.value = if (_symptom.value.isNotBlank() && _symptom.value != prefillNote) {
                "${_symptom.value}\n\n（来自深度分析：$prefillNote）"
            } else {
                prefillNote
            }
        }
        if (!targetTime.isNullOrBlank()) {
            _targetTime.value = normalizeTargetTime(targetTime)
        }
    }

    /**
     * 深链泛化 S4：产物文件写入调查空间成员集（「深度分析」产物落进当前调查——
     * 空间聚合文件/会话/产物的成员面）。SpaceStore 同步 SQLite、调用方自管线程
     * （SpaceStore.kt 契约）——写成员挂 viewModelScope + [ioDispatcher]，addFiles
     * 去重保序幂等（重复深链不产生重复成员）。
     *
     * 不静默丢失：runCatching 的失败面必须 JLog 落盘
     *（空间写入失败不能用户零感知）。
     */
    fun attachFilesToSpace(spaceId: String, paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            withContext(ioDispatcher) {
                runCatching {
                    val store = com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore(
                        com.jiaoay.easy_dev_toolbox.core.data.db.Database.database(),
                    )
                    // 空间可能已被删除——addFiles 的 mutate 幂等无操作（spaceById 落空即 return）
                    store.addFiles(spaceId, paths)
                    store.touch(spaceId)
                }.onFailure { e ->
                    // CE 不吞（取消传播）；其余失败落盘可查（app.log 排查面）
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    com.jiaoay.easy_dev_toolbox.core.log.JLog.e(TAG, e) {
                        "深链空间成员写入失败：spaceId=$spaceId paths=${paths.take(3)}…"
                    }
                }
            }
        }
    }

    /** 最近一次导出产物路径（右栏产物行回显；null = 本会话尚未导出） */
    private val _exportedReportPath = MutableStateFlow<String?>(null)
    val exportedReportPath: StateFlow<String?> = _exportedReportPath.asStateFlow()

    /**
     * 导出脱敏开关（内存态不持久化——默认关 = fail-closed：不脱敏是既有行为，
     * 用户显式开启才变换）。开 = 导出与送飞书产物经 [ReportAnonymizer] 在内容层
     * 前置变换（同值恒同替）；两个外发动作共用 [writeExportedReport] 单缝，
     * deliver 位点恒消费脱敏后的文件零改动。
     */
    private val _anonymizeExport = MutableStateFlow(false)
    val anonymizeExport: StateFlow<Boolean> = _anonymizeExport.asStateFlow()

    /** 脱敏开关回写（报告面板 checkbox 消费面） */
    fun setAnonymizeExport(enabled: Boolean) {
        _anonymizeExport.value = enabled
    }

    /**
     * 导出写盘分流（[exportCurrentReport] 与 [sendCurrentReportToLark] 共用；
     * 回执 toast 归各调用方——导出与外发的失败文案口径不同，不在此归一）。
     */
    private sealed interface ExportOutcome {
        data class Ok(val path: String) : ExportOutcome

        data object MissingRun : ExportOutcome

        data class Fail(val reason: String) : ExportOutcome
    }

    /**
     * 报告导出写盘（同 run 重复导出原位覆写——恒反映最新报告文本，含追问增量）。
     * IO 与既有回执语义原样收口：空间归属失败不拖垮导出；取消信号上抛。
     */
    private suspend fun writeExportedReport(runId: String, report: String): ExportOutcome {
        val evidenceFiles = session.evidenceFiles.value
        return withContext(ioDispatcher) {
            val summary = store.runById(runId) ?: return@withContext ExportOutcome.MissingRun
            runCatching {
                val md = ReportExporter.exportMarkdown(
                    run = summary,
                    reportText = report,
                    evidenceLines = ReportExporter.resolveEvidenceLines(
                        reportText = report,
                        evidenceFiles = evidenceFiles,
                        linesOf = { path -> File(path).readLines() },
                        // 行区间流式只读行段（readEvidenceRef 同款 useLines 配方——
                        // 起始行前的内容流过不载内存）
                        linesRangeOf = { path, start, end ->
                            File(path).useLines { lines ->
                                lines.drop(start - 1).take(end - start + 1).toList()
                            }
                        },
                        // 旧格式引用（无行区间）走流式时间戳定位——全量 readLines 在大日志上会压垮堆
                        lineFinderOf = { path, timestamp -> findTimestampLineIn(File(path), timestamp) },
                    ),
                    exportedAtMillis = System.currentTimeMillis(),
                )
                // 内容层前置变换：开关开 = 全产物（元信息/报告全文/证据行）单点过
                // 脱敏器——报告正文引用与证据行同管线自然同变换；开关关 = md 逐字
                // 直落现状。每次导出新实例：编号令牌按本次导出首遇次序分配。
                val content = if (_anonymizeExport.value) ReportAnonymizer().anonymize(md) else md
                val file = ReportExporter.exportFileOf(runId)
                file.parentFile?.mkdirs()
                file.writeText(content)
                // 空间归属：导出产物同为调查产物成员（settleRun 同款收口；
                // 无选中空间静默跳过，失败不拖垮导出回执）
                SpaceAttribution.attributeFiles(listOf(file.absolutePath))
                file
            }.fold(
                onSuccess = { ExportOutcome.Ok(it.absolutePath) },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ExportOutcome.Fail(e.message ?: e.javaClass.simpleName)
                },
            )
        }
    }

    /**
     * 导出当前 run 的研判报告为可分享 Markdown：`~/Documents/Toolbelt/reports/report-<runId>.md`
     * （[ReportExporter] 承载内容装配；同 run 重复导出原位覆写，恒反映最新报告文本）。
     *
     * 落点区分：导出是**用户发起的交付物**落用户可见产物目录，与报告自动落盘铁律
     * （自动落盘恒在 run 目录内）不冲突。
     *
     * 守卫静默返回（无 toast）：可感知拒绝由报告面板「导出 Markdown」钮承担
     * （运行中置灰带 disabledReason；无报告分支本方法不可达）——本守卫只兜底
     * 非 UI 调用面。
     */
    fun exportCurrentReport() {
        val runId = session.runIdValue
        val report = _reportText.value
        if (runId == PENDING_RUN_ID || report == null) return
        if (session.running.value || _pipelineRunning.value) return
        viewModelScope.launch {
            when (val outcome = writeExportedReport(runId, report)) {
                is ExportOutcome.Ok -> {
                    _exportedReportPath.value = outcome.path
                    com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                        "已导出 Markdown：${outcome.path}",
                    )
                }
                ExportOutcome.MissingRun -> com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                    "分析记录不存在，无法导出",
                )
                is ExportOutcome.Fail -> com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                    "导出失败：${outcome.reason}",
                )
            }
        }
    }

    /**
     * 一键送飞书：导出（恒重新导出即恒最新报告文本）→ 经 :core 报告外发通道
     * 以文件消息直发桥接接收会话。失败人话由通道归一（未运行/未配置会话/出站
     * 排除表拒绝/传输失败），此处只做回执 toast——置灰判据在报告面板
     * （larkSendDisabledReason），本守卫兜底非 UI 调用面（同 [exportCurrentReport]）。
     */
    fun sendCurrentReportToLark() {
        val runId = session.runIdValue
        val report = _reportText.value
        if (runId == PENDING_RUN_ID || report == null) return
        if (session.running.value || _pipelineRunning.value) return
        viewModelScope.launch {
            when (val outcome = writeExportedReport(runId, report)) {
                is ExportOutcome.Ok -> {
                    _exportedReportPath.value = outcome.path
                    val reason = com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery.deliver(
                        File(outcome.path),
                    )
                    if (reason == null) {
                        com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                            "已送出至飞书会话：${File(outcome.path).name}",
                        )
                    } else {
                        com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(reason)
                    }
                }
                ExportOutcome.MissingRun -> com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                    "分析记录不存在，无法送飞书",
                )
                is ExportOutcome.Fail -> com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                    "导出失败，无法送飞书：${outcome.reason}",
                )
            }
        }
    }

    /** 打开当前 run 的报告文件（系统默认程序打开文件本体——openFile 失败降级所在目录） */
    fun openCurrentReport() {
        val runId = session.runIdValue
        if (runId == PENDING_RUN_ID) return
        viewModelScope.launch(ioDispatcher) {
            val summary = store.runById(runId) ?: return@launch
            summary.reportPath?.let { path -> DesktopUtils.openFile(path) }
        }
    }

    // ── 根因存案（golden_samples 写路径——HITL：用户点存案即「根因确认」判定，
    //    不自动存案防误案入库） ──

    /**
     * 存案编辑载荷（对话框预填与回传体——四要素 + 标题/tags 皆可改可补）。
     * 四要素来自报告解析（[parseReport] 拆 conclusion/chain + [ReportExporter.resolveEvidenceLines]
     * 证据行原文回读），fixSummary 恒空预填（报告纪律刻意不给修复建议——存案时由
     * 用户补写，可改可补契约的一部分）。
     */
    data class CaseDraft(
        val title: String,
        val symptom: String,
        val rootCause: String,
        val fixSummary: String,
        val evidenceLog: String,
        val tags: List<String>,
    )

    /** 存案编辑对话框态（null = 不渲染对话框） */
    private val _caseDraft = MutableStateFlow<CaseDraft?>(null)
    val caseDraft: StateFlow<CaseDraft?> = _caseDraft.asStateFlow()

    /**
     * 打开存案编辑对话框：报告解析拆四要素 + 证据行回读预填。
     * 守卫静默返回（无 run/无报告/运行中）——可感知拒绝由面板「存案」钮承担
     * （[larkSendDisabledReason] 同款置灰纪律），本守卫兜底非 UI 调用面。
     */
    fun openCaseDraft() {
        val runId = session.runIdValue
        val report = _reportText.value
        if (runId == PENDING_RUN_ID || report == null) return
        if (session.running.value || _pipelineRunning.value) return
        viewModelScope.launch {
            val summary = withContext(ioDispatcher) { store.runById(runId) } ?: return@launch
            val evidenceFiles = session.evidenceFiles.value
            val evidenceLines = withContext(ioDispatcher) {
                ReportExporter.resolveEvidenceLines(
                    reportText = report,
                    evidenceFiles = evidenceFiles,
                    linesOf = { path -> File(path).readLines() },
                    // 行区间流式只读行段（writeExportedReport 同款 useLines 配方）
                    linesRangeOf = { path, start, end ->
                        File(path).useLines { lines ->
                            lines.drop(start - 1).take(end - start + 1).toList()
                        }
                    },
                    // 旧格式引用（无行区间）走流式时间戳定位——全量 readLines 在大日志上会压垮堆
                    lineFinderOf = { path, timestamp -> findTimestampLineIn(File(path), timestamp) },
                )
            }
            _caseDraft.value = buildCaseDraft(
                sections = parseReport(report),
                evidenceLines = evidenceLines,
                fallbackTitle = summary.title,
            )
        }
    }

    /**
     * 存案落库：编辑后的四要素 upsert 进专用案例仓（[KnowledgeRepository.CASE_REPO]）。
     * title/symptom 必填（golden_samples NOT NULL——空值在保存前拒绝并明示）；
     * 同名 (repo, title) 覆盖由 upsert 语义直通，回执区分新增/覆盖。
     */
    fun saveCaseArchive(draft: CaseDraft) {
        if (draft.title.isBlank() || draft.symptom.isBlank()) {
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show("存案需要标题与症状——先补全再保存")
            return
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val existed = KnowledgeRepository(Database.database()).upsertGoldenSample(
                    KnowledgeRepository.GoldenSample(
                        repo = KnowledgeRepository.CASE_REPO,
                        title = draft.title.trim(),
                        symptom = draft.symptom.trim(),
                        rootCause = draft.rootCause.ifBlank { "（待补）" }.trim(),
                        fixSummary = draft.fixSummary.ifBlank { "（待补）" }.trim(),
                        evidenceLog = draft.evidenceLog.ifBlank { null },
                        tags = draft.tags,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                _caseDraft.value = null
                com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                    if (existed) {
                        "已存案（覆盖了同名案例「${draft.title.trim()}」）"
                    } else {
                        "已存案：下次研判遇到相似症状会先检索历史案例、从既有结论起步"
                    },
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                    "存案失败：${e.message?.take(120) ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    /** 关闭存案编辑对话框（不落库） */
    fun dismissCaseDraft() {
        _caseDraft.value = null
    }

    /** 回看历史 run：restore 时间线 + 报告文本回填（不新建 run） */
    fun openRun(id: String) {
        if (session.running.value || _pipelineRunning.value) {
            // 拒绝必带原因：运行中回看被守卫拦截——静默 return 让「切换」钮零响应
            //（确认卡照弹后点切换落空的动线断链）
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show("研判运行中，请先中止当前分析")
            return
        }
        viewModelScope.launch {
            val summary = withContext(ioDispatcher) { store.runById(id) } ?: return@launch
            val restored = withContext(ioDispatcher) { timeline.replay(id) }
            session = createSession(id)
            session.restore(restored)
            _logFiles.value = summary.logFiles
            _targetTime.value = summary.targetTime ?: ""
            _rangeMinutes.value = summary.rangeMinutes ?: 5L
            // 导出产物行随会话切换清空：上一 run 的导出路径顶在新报告旁可被
            // 复制/打开动作错拿（产物行无 runId 归属标记，清空即无歧义）
            _exportedReportPath.value = null
            // 第三态收口：读取失败时置位读失败路径（右栏错误态），成功/无路径恒 null
            _reportReadError.value = null
            _reportText.value = summary.reportPath?.let { path ->
                readReportFile(path).also { if (it == null) _reportReadError.value = path }
            }
        }
    }

    /** 运行期报告产物路径提示（报告未落盘前的可预期落点；占位 run 无路径） */
    fun currentReportPathHint(): String? {
        val runId = session.runIdValue
        return if (runId == PENDING_RUN_ID) null else reportFileOf(runId).absolutePath
    }

    private suspend fun readReportFile(path: String): String? =
        withContext(ioDispatcher) {
            runCatching { File(path).readText() }.getOrNull()
        }

    fun renameRun(id: String, title: String) {
        if (title.isBlank()) return
        store.rename(id, title)
        refreshRuns()
    }

    fun deleteRun(id: String) {
        store.delete(id)
        // 产物同收：报告目录（runs/<runId>/）与库行一起删——孤儿 report.md 是磁盘
        // 垃圾且回看深链对已删 run 落空后不留死文件；失败落盘可查不拖垮删除回执
        viewModelScope.launch(ioDispatcher) {
            runCatching { reportFileOf(id).parentFile?.deleteRecursively() }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    com.jiaoay.easy_dev_toolbox.core.log.JLog.e(TAG, e) {
                        "删除 run 报告目录失败：$id"
                    }
                }
        }
        if (id == currentRunId()) {
            session.cancel()
            // UI 三面同收：置换占位会话——entries/currentRunIdForUi 归零，不留
            // 「库行已删而视图仍显旧证据流」的幽灵态
            session = newSession(PENDING_RUN_ID)
            _reportText.value = null
            _reportReadError.value = null
        }
        refreshRuns()
    }

    private fun currentRunId(): String = session.runIdValue

    // ── 会话透传面（UI 消费） ──

    val entries: StateFlow<List<ChatEntry>> get() = session.entries
    val running: StateFlow<Boolean> get() = session.running
    val pendingGate: StateFlow<File?> get() = session.pendingGate
    val evidenceFiles: StateFlow<List<String>> get() = session.evidenceFiles
    val todoBoard get() = session.todoBoard

    fun approveGate() = session.approveGate()
    fun denyGate() = session.denyGate()

    /** 会话工厂（runId 参数化——新 run/回看各建各的） */
    private fun newSession(runId: String): WorkbenchSession = WorkbenchSession(
        scope = viewModelScope,
        runId = runId,
        configProvider = { LlmEndpointConfig.read() },
        executorProvider = { config -> LlmClientFactory.executor(config) },
        modelProvider = { config -> LlmClientFactory.model(config.modelId) },
        privateKeyProvider = com.jiaoay.easy_dev_toolbox.core.llm.tools.DecryptTools.defaultPrivateKeyProvider,
        memoryDir = FileUtils.getMemoryDir(),
        ledger = timeline,
        onRunFailed = ::settleRunAsFailed,
    )

    /**
     * 测试接缝：会话工厂整体替换（失败链/收集器竞速直测——单测注入即时失败的
     * executor 会话，零网络；null = 生产装配 [newSession]）。onRunFailed 形参承接
     * VM 侧失败终态收口——注入会话必须透传该回调，否则失败回执链被测缝断开。
     */
    internal var sessionFactoryForTest:
        ((runId: String, onRunFailed: (String, String) -> Unit) -> WorkbenchSession)? = null

    /** 会话装配统一口（发起/回看三处共用；失败收口接线恒由 VM 提供，不随测试缝替换丢失） */
    private fun createSession(runId: String): WorkbenchSession =
        sessionFactoryForTest?.invoke(runId, ::settleRunAsFailed) ?: newSession(runId)

    /** 失败回执 → 失败终态收口（降级报告落盘 + 库行 FAILED——失败也落终态的 VM 侧承接） */
    private fun settleRunAsFailed(failedRunId: String, degradedReport: String) {
        settleRun(failedRunId, degradedReport, RunStore.RunStatus.FAILED)
    }

    override fun onCleared() {
        WorkbenchSession.detach()
        super.onCleared()
    }

    companion object {
        /** 日志 TAG（attachFilesToSpace 失败面 JLog 落盘用） */
        private const val TAG = "WorkbenchViewModel"

        /** 初始占位 runId（VM 构造期会话——尚无 run 时的 UI 恒等判定） */
        internal const val PENDING_RUN_ID = "run-pending"

        internal fun deriveRunTitle(logFiles: List<String>, targetTime: String): String {
            val firstName = logFiles.firstOrNull()?.let { pathFileName(it) } ?: "未命名"
            return if (targetTime.isBlank()) "$firstName（无时间点）" else "$firstName @ $targetTime"
        }

        /** 报告文件路径（run 专属目录——`~/.toolbelt/logworkbench/runs/<runId>/report.md`） */
        internal fun reportFileOf(runId: String): File =
            File(File(FileUtils.getLogworkbenchRunsDir(), runId), "report.md")

        /** 追问合并（增量段追加——原结论不改写） */
        internal fun mergeFollowUp(previous: String?, increment: String): String =
            if (previous.isNullOrBlank()) increment else "$previous\n\n---\n\n## 增量研判（追问）\n\n$increment"

        /**
         * 报告解析 → 存案预填载荷（纯函数——单测直测缝）：
         * - title/symptom ← 结论一句话（结论空时 title 回落 run 标题）；
         * - rootCause ← 因果链「根因」段（缺失回落整链拼接，再缺失空串由对话框补写）；
         * - fixSummary 恒空预填——报告纪律刻意不给修复建议（「仅定位分析」），修法由用户补；
         * - evidenceLog ← 证据行原文逐字回读（[ReportExporter.resolveEvidenceLines] 产物）；
         * - tags ← 证据行 TAG 高频词（产品归属上下文由 tags 承载，不靠案例仓名）。
         */
        internal fun buildCaseDraft(
            sections: ReportSections,
            evidenceLines: List<ReportExporter.EvidenceLine>,
            fallbackTitle: String,
        ): CaseDraft {
            val conclusion = sections.conclusion.trim()
            val rootCause = sections.chain.firstOrNull { it.first == "根因" }?.second
                ?: sections.chain.takeIf { it.isNotEmpty() }
                    ?.joinToString("；") { (label, text) -> "$label：$text" }
                ?: ""
            val evidenceLog = evidenceLines.mapNotNull { it.rawLine }.joinToString("\n")
            return CaseDraft(
                title = conclusion.ifBlank { fallbackTitle },
                symptom = conclusion,
                rootCause = rootCause,
                fixSummary = "",
                evidenceLog = evidenceLog,
                tags = evidenceTagCandidates(evidenceLines.mapNotNull { it.rawLine }),
            )
        }

        /** tags 预填上限（证据行高频 TAG 词取频次降序 Top N——更多由对话框补写） */
        private const val CASE_TAGS_PRESET_MAX = 5

        /**
         * 证据行 TAG 高频词提取：`E/CameraManager:` 形态的 `级别/TAG` 前缀（设备日志行
         * 标准形态）；频次降序取 Top [CASE_TAGS_PRESET_MAX]。零 TAG 行（纯文本行/
         * 兜底未命中行）自然产零候选。
         */
        internal fun evidenceTagCandidates(rawLines: List<String>): List<String> {
            val pattern = Regex("""[EWIDV]/([A-Za-z][A-Za-z0-9_]{1,40})""")
            return rawLines
                .flatMap { line -> pattern.findAll(line).map { it.groupValues[1] }.toList() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(CASE_TAGS_PRESET_MAX)
                .map { it.key }
        }

        /** 管线直跑的落盘文本（降级态报告——五部分骨架而非 LLM 报告） */
        internal fun pipelineSummary(result: LogAnalysisPipeline.StageResult): String = buildString {
            appendLine("# 日志分析报告")
            appendLine("## 结论")
            appendLine("——确定性管线执行（AI 未配置，无语义研判）")
            appendLine()
            appendLine("## 问题根因")
            when (result) {
                is LogAnalysisPipeline.StageResult.Ok -> {
                    appendLine("管线已完成，阶段：${result.stage}，产物：${result.artifact?.name ?: "无"}")
                    appendLine(result.note)
                    appendLine("- ? 配置模型服务后可获得根因研判")
                }
                is LogAnalysisPipeline.StageResult.Abort -> {
                    appendLine("- ✗ 管线中止于 ${result.stage}：${result.reason}")
                }
            }
            appendLine()
            appendLine("## 完整时间线")
            appendLine("（管线直跑模式——无 agent 时间线；产物文件见元数据）")
            appendLine()
            appendLine("## 关键依据")
            appendLine("（管线产物文件即为证据面，可经证据流浏览）")
            appendLine()
            appendLine("## 元数据")
            appendLine("- 模式：确定性管线直跑（AI 未配置）")
            if (result is LogAnalysisPipeline.StageResult.Ok) {
                appendLine("- 最终产物：${result.artifact?.absolutePath ?: "无"}")
            }
        }
    }
}
