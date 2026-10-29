package com.jiaoay.easy_dev_toolbox.page.ai.distill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.DistillIngest
import com.jiaoay.easy_dev_toolbox.core.data.db.KnowledgePack
import com.jiaoay.easy_dev_toolbox.core.data.db.KnowledgeRepository
import com.jiaoay.easy_dev_toolbox.core.data.db.KNOWLEDGE_URI_PREFIX
import com.jiaoay.easy_dev_toolbox.core.data.db.SessionStore
import com.jiaoay.easy_dev_toolbox.core.data.db.SqliteSessionStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools
import com.jiaoay.easy_dev_toolbox.core.navigation.SpaceAttribution
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 知识蒸馏页 VM（P4-B 任务 4.3）：
 * - 会话核心全权委托 [session]（两阶段装配与 reducer 直测缝在 [DistillSession]）；
 * - VM 增益面：**阶段机**（表单 → 计划审批 → 运行 → 审阅 → 验证）+ **候选审阅三态**
 *   （pending/accepted/rejected——接受动作直调 KnowledgeRepository 入库，任务 4.5）；
 * - sink 消费归 session（progress/proposalBuffer 两结构化面已内建——VM 只透传）；
 * - 中止保全：cancel 后候选与进度不清（供审阅卡事后处理）。
 */
class DistillViewModel(
    /**
     * IO 线程域（VM 内全部阻塞 IO 段——SQLite/文件写——的承载调度器）。
     * 契约：默认 [Dispatchers.IO]，宿主装配点零改动；测试注入受控调度器
     * （与 Main 共享 TestScheduler 的 StandardTestDispatcher）后，VM 级 IO
     * 续体不飞真实 IO 线程——用例收尾 advanceUntilIdle 即确定性排空，
     * 无跨用例在途续体（teardown 无需竞态宽限 sleep）。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 测试接缝：会话工厂（失败 watcher 相位收口直测——注入即时失败/挂起的 executor
     * 会话，零网络；null = 生产装配）。入参 = viewModelScope（会话 job 随 VM 生命周期）。
     */
    private val sessionFactory: ((CoroutineScope) -> DistillSession)? = null,
) : ViewModel() {

    val session: DistillSession = sessionFactory?.invoke(viewModelScope) ?: DistillSession(
        scope = viewModelScope,
        configProvider = { LlmEndpointConfig.read() },
        executorProvider = { config -> LlmClientFactory.executor(config) },
        modelProvider = { config -> LlmClientFactory.model(config.modelId) },
        ledger = SqliteSessionStore(Database.database()),
    ).also { DistillSession.attach(it, viewModelScope) }

    // ── 阶段机（表单 → 探索 → 计划审批 → 执行 → 审阅） ──

    /** 页面阶段（数据驱动 UI 分段渲染——4.4 消费） */
    enum class Phase { FORM, EXPLORING, PLAN_REVIEW, DISTILLING, REVIEW }

    private val _phase = MutableStateFlow(Phase.FORM)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** 功能模块描述（表单 → 两阶段共用——批准后作执行入参） */
    private val _featureDesc = MutableStateFlow("")
    val featureDesc: StateFlow<String> = _featureDesc.asStateFlow()

    /** entry hints（表单 chips——可留空，agent 自搜兜底） */
    private val _entryHints = MutableStateFlow<List<String>>(emptyList())
    val entryHints: StateFlow<List<String>> = _entryHints.asStateFlow()

    /** 计划卡数据（探索产物 phaseResult JSON 解析 + 用户增删勾改面） */
    private val _planItems = MutableStateFlow<List<PlanRow>>(emptyList())
    val planItems: StateFlow<List<PlanRow>> = _planItems.asStateFlow()

    /** 探索 repo 标识（计划 JSON 顶层 repo 字段） */
    private val _planRepo = MutableStateFlow<String?>(null)
    val planRepo: StateFlow<String?> = _planRepo.asStateFlow()

    /** 计划卡行（path + reason + 用户勾选态） */
    data class PlanRow(
        val path: String,
        val reason: String,
        val checked: Boolean = true,
    )

    /** 执行阶段候选审阅三态（4.5 入库链消费；4.4 审阅卡渲染） */
    enum class ReviewState { PENDING, ACCEPTED, REJECTED }

    /** 审阅卡行（proposalBuffer 快照 + 三态标记） */
    data class ReviewRow(
        val proposal: DistillTools.DistillProposal,
        val state: ReviewState = ReviewState.PENDING,
    )

    /** 执行阶段实读文件清单（验证面——批准计划的勾选项：hints 命中标注的数据源） */
    private val _executedPlanFiles = MutableStateFlow<List<String>>(emptyList())
    val executedPlanFiles: StateFlow<List<String>> = _executedPlanFiles.asStateFlow()

    /** 审阅面数据（buffer 快照 + 三态——中止后仍可审阅） */
    private val _reviewRows = MutableStateFlow<List<ReviewRow>>(emptyList())
    val reviewRows: StateFlow<List<ReviewRow>> = _reviewRows.asStateFlow()

    /**
     * 测试接缝：直接置计划 repo + 审阅行——生产唯一写点 =
     * startExplore/approvePlan/snapshotReviewRows（两阶段需真实 LLM，单测不可驱动）；
     * acceptProposal 归属链（knowledge:// 轻归属）单测经此缝驱动。set 仅测试语义。
     */
    internal fun setReviewFixtureForTest(repo: String, rows: List<ReviewRow>) {
        _planRepo.value = repo
        _reviewRows.value = rows
    }

    /**
     * 测试接缝：直置 PLAN_REVIEW 阶段面（phase + repo + 计划行）——
     * 生产唯一写点 = startExplore watcher（需真实 LLM，单测不可驱动）；abandonPlan
     * 出口单测经此缝驱动起点态。set 仅测试语义（同 [setReviewFixtureForTest] 配方）。
     */
    internal fun setPlanReviewFixtureForTest(repo: String, items: List<PlanRow>) {
        _phase.value = Phase.PLAN_REVIEW
        _planRepo.value = repo
        _planItems.value = items
    }

    /** 阶段 watcher（每轮启动前置换——防 restart 后双 collector 同触发） */
    private var phaseWatchJob: kotlinx.coroutines.Job? = null

    /** 运行面板透传面 */
    val entries: StateFlow<List<ChatEntry>> get() = session.entries
    val running: StateFlow<Boolean> get() = session.running
    val progress: StateFlow<DistillSession.DistillProgressState?> get() = session.progress
    val phaseResult: StateFlow<String?> get() = session.phaseResult

    // ── 表单动作 ──

    fun setFeatureDesc(text: String) {
        _featureDesc.value = text
    }

    fun setEntryHints(hints: List<String>) {
        _entryHints.value = hints
    }

    /** 发起探索（FORM → EXPLORING；完成后回 PLAN_REVIEW） */
    fun startExplore() {
        // 重新探索 = 回执消失（横幅只承载「上轮已作废」一次性告知）
        _abandonReceiptVisible.value = false
        // 新轮次不背旧失败：入库失败横幅是上一审阅轮的一次性回执，跨轮残留
        // 会被误读为本轮失败
        _ingestError.value = null
        if (session.running.value) return
        if (_featureDesc.value.isBlank()) return
        // 使用计数口径：蒸馏任务发起 = 一次真实使用
        ToolUsageSignal.report(ToolUsageSignal.PageId.DISTILL)
        phaseWatchJob?.cancel()
        _phase.value = Phase.EXPLORING
        // 🔴 先发起再挂 watcher（置空防陈值重放）：startExplore 的 resetForPhase 清
        //    _phaseResult——watcher 挂上后收集到的是 null 起点而非上轮陈值（Main.immediate
        //    下先挂后发会让旧 phaseResult 立即重放，重探索瞬间假翻回旧计划卡）
        session.startExplore(featureDesc = _featureDesc.value, entryHints = _entryHints.value)
        phaseWatchJob = viewModelScope.launch {
            launch {
                // collect 探索产物：phaseResult 非空即解析计划 JSON → 计划卡。
                // 🔴 解析失败也是终态——静默不切相会把 EXPLORING 楔死（出口钮随
                //    running 消失后无任何出路），报错回 FORM 重试
                session.phaseResult.collect { result ->
                    if (result != null && _phase.value == Phase.EXPLORING) {
                        val parsed = parsePlanJson(result)
                        if (parsed == null) {
                            StatusSignals.show("探索产物解析失败（计划 JSON 畸形）——请调整描述后重试")
                            _phase.value = Phase.FORM
                        } else {
                            val (repo, items) = parsed
                            _planRepo.value = repo
                            _planItems.value = items.map { PlanRow(path = it.path, reason = it.reason) }
                            _phase.value = Phase.PLAN_REVIEW
                        }
                    }
                }
            }
            launch {
                // 失败终态收口：探索失败回 FORM + 报错（失败也是终态，不收口则相位楔死）
                session.phaseFailure.collect { failure ->
                    if (failure != null && _phase.value == Phase.EXPLORING) {
                        session.consumePhaseFailure()
                        StatusSignals.show("蒸馏探索失败：$failure")
                        _phase.value = Phase.FORM
                    }
                }
            }
        }
    }

    // ── 计划卡动作 ──

    fun togglePlanItem(index: Int) {
        _planItems.updateRows { rows ->
            rows.mapIndexed { i, row -> if (i == index) row.copy(checked = !row.checked) else row }
        }
    }

    /** 批准计划进执行阶段（PLAN_REVIEW → DISTILLING；勾选项进执行 prompt） */
    fun approvePlan() {
        val checked = _planItems.value.filter { it.checked }
        if (checked.isEmpty()) return
        _executedPlanFiles.value = checked.map { it.path }
        phaseWatchJob?.cancel()
        _phase.value = Phase.DISTILLING
        // 先发起再挂 watcher（置空防陈值重放——startDistill 清 _phaseResult，
        // 同 [startExplore] 的挂序纪律）
        session.startDistill(
            DistillSession.ApprovedPlan(
                featureDesc = _featureDesc.value,
                entryHints = _entryHints.value,
                plan = checked.map { DistillSession.PlanItem(path = it.path, reason = it.reason) },
            ),
        )
        phaseWatchJob = viewModelScope.launch {
            launch {
                session.phaseResult.collect { result ->
                    if (result != null && _phase.value == Phase.DISTILLING) {
                        // 执行收尾总结 = phaseResult；候选快照进审阅面
                        snapshotReviewRows()
                        _phase.value = Phase.REVIEW
                    }
                }
            }
            launch {
                // 失败终态收口：执行失败 → REVIEW 保全已产候选（审阅面可事后处理，
                // 语义同中止收口）——不楔死在 DISTILLING
                session.phaseFailure.collect { failure ->
                    if (failure != null && _phase.value == Phase.DISTILLING) {
                        session.consumePhaseFailure()
                        StatusSignals.show("蒸馏执行失败：$failure（已产候选已进审阅面）")
                        snapshotReviewRows()
                        _phase.value = Phase.REVIEW
                    }
                }
            }
        }
    }

    // 计划审批的第三选项「继续改计划」：留在 PLAN_REVIEW（增删勾改后重批）——无需动作面

    /** 中止（进度/候选保全——回审阅面处理已产出的候选） */
    fun cancel() {
        session.cancel()
        if (_phase.value == Phase.EXPLORING || _phase.value == Phase.DISTILLING) {
            snapshotReviewRows()
            _phase.value = Phase.REVIEW
        }
    }

    /**
     * 运行相位滞留出口：running 已落但相位未切（失败收口瞬态/意外滞留）时的
     * 兜底出路——出口钮不随 running 消失（失败终态收口后用户必有出路）。
     * 探索滞留回表单；执行滞留回审阅（候选保全语义同 [cancel]）。
     */
    fun exitStuckPhase() {
        when (_phase.value) {
            Phase.EXPLORING -> _phase.value = Phase.FORM
            Phase.DISTILLING -> {
                snapshotReviewRows()
                _phase.value = Phase.REVIEW
            }
            else -> Unit
        }
    }

    /** 一轮蒸馏结束（正常或中止）都进审阅面：buffer 快照 → 审阅行 */
    fun snapshotReviewRows() {
        _reviewRows.value = session.proposalBuffer.snapshot().map { ReviewRow(proposal = it) }
    }

    /** 实读文件清单的 hints 命中标注（验证面——file path 含任一 hint 片段 = 命中；
     *  未命中清单 = 探索主要靠自搜——提示用户补 hints 可收敛下一轮计划） */
    fun hintHitFlags(files: List<String>, hints: List<String>): List<Boolean> =
        files.map { f -> hints.any { hint -> hint.isNotBlank() && f.contains(hint, ignoreCase = true) } }

    // ── 审阅卡动作（三态流转 + 4.5 入库链） ──

    /** 接受候选 → 直调 DistillIngest 四表 upsert（kind 分派——成功置 ACCEPTED，失败保持 PENDING 供重试） */
    fun acceptProposal(seq: Int) {
        val row = _reviewRows.value.firstOrNull { it.proposal.seq == seq } ?: return
        val repo = _planRepo.value ?: return
        viewModelScope.launch {
            val result = DistillIngest.ingest(
                db = Database.database(),
                proposal = row.proposal,
                repo = repo,
            )
            if (result.written) {
                // 蒸馏知识入库成功 → 知识仓库归属当前调查空间。知识是四表记录非文件，
                // 经 addFiles 记「knowledge://<repo>」URI 串（零 schema 变更）；成员面板
                // 剥前缀分组显示。前缀单源化：拼接走 :core
                // KNOWLEDGE_URI_PREFIX（与显示侧 SpaceBar 同源），不持本地字面量。
                // 外层 viewModelScope.launch 是 Main 协程 → 补 withContext(IO)
                // 承接同步 SQLite 写。DistillIngest 拒 unknown repo（written=false）——
                // 本分支天然不混入 unknown 仓库；addFiles distinct 幂等（多次接受同 repo
                // 候选不重复记）；无选中空间静默跳过；失败槽内 JLog 收口不拖垮入库链
                withContext(ioDispatcher) {
                    SpaceAttribution.attributeFiles(listOf(KNOWLEDGE_URI_PREFIX + repo))
                }
                _reviewRows.updateRows { rows ->
                    rows.map { if (it.proposal.seq == seq) it.copy(state = ReviewState.ACCEPTED) else it }
                }
            } else {
                _ingestError.value = "${row.proposal.title}：${result.detail}"
            }
        }
    }

    fun rejectProposal(seq: Int) {
        _reviewRows.updateRows { rows ->
            rows.map { if (it.proposal.seq == seq) it.copy(state = ReviewState.REJECTED) else it }
        }
    }

    // ── 管理面（5.1：按 repo 浏览/查看/删除/导出——记忆面板四动作模式） ──

    /** 库统计（repos 数 + 四表行数——管理面头部） */
    private val _kbStats = MutableStateFlow<KbOverview?>(null)
    val kbStats: StateFlow<KbOverview?> = _kbStats.asStateFlow()

    /** 管理面概览（repo 清单 + 各表计数） */
    data class KbOverview(
        val repos: List<String>,
        val tags: Long,
        val decisionNodes: Long,
        val goldenSamples: Long,
        val docs: Long,
    )

    /** 刷新管理面概览（进入管理 tab / 删除后调用） */
    fun refreshKbStats() {
        viewModelScope.launch {
            val repo = withContext(ioDispatcher) {
                val r = KnowledgeRepository(Database.database())
                val stats = r.stats()
                KbOverview(repos = r.repos(), tags = stats.tags, decisionNodes = stats.decisions, goldenSamples = stats.samples, docs = stats.docs)
            }
            _kbStats.value = repo
        }
    }

    /** 按 repo 删除四表（管理面删除动作——含蒸馏候选入库的整组回退） */
    fun deleteKbRepo(repo: String) {
        viewModelScope.launch {
            withContext(ioDispatcher) { KnowledgeRepository(Database.database()).deleteRepo(repo) }
            refreshKbStats()
        }
    }

    // ── 成品浏览面（add-distill-output-viewer 任务 2.1——纯只读接线，查询 API 全既有） ──

    /** 仓库四表浏览快照（展开 repo 时拉取；null = 未展开）；全量双数段供节标题「命中 / 全量」 */
    data class KbBrowse(
        val repo: String,
        val tags: List<KnowledgeRepository.TagEntry>,
        val decisionNodes: List<KnowledgeRepository.DecisionNode>,
        val goldenSamples: List<KnowledgeRepository.GoldenSample>,
        val docs: List<KnowledgeRepository.KnowledgeDoc>,
        val goldenSamplesTotal: Int,
        val docsTotal: Int,
    )

    /** 关键词双路检索结果（null = 无检索） */
    data class KbSearchResult(
        val keyword: String,
        val repo: String,
        val samples: List<KnowledgeRepository.GoldenSample>,
        val docs: List<KnowledgeRepository.KnowledgeDoc>,
    )

    private val _kbBrowse = MutableStateFlow<KbBrowse?>(null)
    val kbBrowse: StateFlow<KbBrowse?> = _kbBrowse.asStateFlow()

    private val _kbSearchResult = MutableStateFlow<KbSearchResult?>(null)
    val kbSearchResult: StateFlow<KbSearchResult?> = _kbSearchResult.asStateFlow()

    /** 展开仓库拉四表快照（null = 收起；重复展开同 repo 刷新快照） */
    fun openKbRepo(repo: String?) {
        if (repo == null) {
            _kbBrowse.value = null
            _kbSearchResult.value = null
            return
        }
        viewModelScope.launch {
            val snapshot = withContext(ioDispatcher) {
                val r = KnowledgeRepository(Database.database())
                val methods = r.methodsOf(repo)
                val samples = r.goldenSamplesOf(repo)
                val docs = r.docsOf(repo)
                KbBrowse(
                    repo = repo,
                    tags = r.tagsOf(repo),
                    decisionNodes = methods.flatMap { r.decisionTree(repo, it) },
                    goldenSamples = samples,
                    docs = docs,
                    // 全量段（节标题「命中 / 全量」双数——同一查询面直取 size）
                    goldenSamplesTotal = samples.size,
                    docsTotal = docs.size,
                )
            }
            _kbBrowse.value = snapshot
        }
    }

    /** 关键词检索（黄金样本 + 文档双路 LIKE——当前展开仓库域内） */
    fun kbSearch(keyword: String) {
        val repo = _kbBrowse.value?.repo ?: return
        if (keyword.isBlank()) {
            _kbSearchResult.value = null
            return
        }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                val r = KnowledgeRepository(Database.database())
                KbSearchResult(
                    keyword = keyword,
                    repo = repo,
                    samples = r.searchGoldenSamples(repo, keyword),
                    docs = r.searchDocs(repo, keyword),
                )
            }
            _kbSearchResult.value = result
        }
    }

    /** REVIEW 收尾一键查看：展开本次蒸馏 repo（计划 repo 真源） */
    fun viewDistillOutput() {
        _planRepo.value?.let { openKbRepo(it) }
    }

    /** 导出 KnowledgePack JSON（管理面导出动作——默认落 ~/Documents/Toolbelt/knowledge/） */
    fun exportKbPack(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val path = KnowledgePack.exportPack(Database.database(), target = null, exportedAt = System.currentTimeMillis())
            // 导出包归属当前调查空间——exportPack 内部
            // withContext(IO) 返回后已落回 Main，归属写（同步 SQLite）须补 withContext(IO)。
            // 放 onDone 之前：面板/横幅提示到达时成员已可记入（时序不倒挂）。无选中空间
            // 静默跳过；失败槽内 JLog 收口不影响导出结果（文件已落盘）
            withContext(ioDispatcher) {
                SpaceAttribution.attributeFiles(listOf(path.absolutePath))
            }
            withContext(Dispatchers.Main) { onDone(path.absolutePath) }
        }
    }

    /** 入库失败提示（审阅面横幅——单条失败不拖垮整批，保持 PENDING 供重试） */
    private val _ingestError = MutableStateFlow<String?>(null)
    val ingestError: StateFlow<String?> = _ingestError.asStateFlow()

    /** 重新开始（审阅后重置全链回表单） */
    fun restart() {
        session.cancel()
        DistillSession.detach()
        _phase.value = Phase.FORM
        _planItems.value = emptyList()
        _planRepo.value = null
        _reviewRows.value = emptyList()
        _featureDesc.value = ""
        _entryHints.value = emptyList()
        // 全链重置含失败横幅（跨轮残留被误读为本轮失败）
        _ingestError.value = null
    }

    /**
     * 作废本次计划：PLAN_REVIEW 显式出口——回 FORM 且**表单内容保留**
     * （featureDesc/entryHints 不清，误入的用户改两笔描述即可重探）。
     *
     * 🔴 不复用 [restart]：restart 清 featureDesc/entryHints——作废的
     * 语义是「这份计划不要了」，不是「表单也不要了」。只清计划面（planItems/planRepo）；
     * _reviewRows 不清——PLAN_REVIEW 态候选恒空，清不清等价。
     */
    fun abandonPlan() {
        session.cancel()
        _phase.value = Phase.FORM
        _planItems.value = emptyList()
        _planRepo.value = null
        // 作废后回 FORM 显示回执横幅；重新探索（startExplore）/
        // 下一次提交后消失。
        _abandonReceiptVisible.value = true
        // 入库失败横幅随本轮作废一并清（作废 = 本轮审阅面整体退役）
        _ingestError.value = null
        // featureDesc/entryHints 保留——表单续用
    }

    /** 作废回执横幅可见态（abandonPlan 置位、startExplore 清除） */
    private val _abandonReceiptVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    val abandonReceiptVisible: kotlinx.coroutines.flow.StateFlow<Boolean> = _abandonReceiptVisible.asStateFlow()

    // ── 内部 ──

    /** 计划 JSON 解析（探索 phaseResult → repo + plan[{path, reason}]） */
    internal fun parsePlanJson(raw: String): Pair<String, List<DistillSession.PlanItem>>? =
        parsePlanJsonImpl(raw)

    /** ViewModel onCleared：detach 活动门面（侧栏指示归 Idle） */
    override fun onCleared() {
        DistillSession.detach()
        super.onCleared()
    }

    companion object {
        /**
         * 计划 JSON 解析实现（文件顶层 internal——直测缝：VM 实例构造触达 SQLite 真单例，
         * 测试经此纯函数面断言；幻觉/畸形计划直接进执行 prompt，解析是承重面）。
         * - repo 缺失 → "unknown"；plan 缺失 → 空列表；path 缺失的项跳过；
         * - ```json 代码块包裹容忍（模型爱包）；
         * - 畸形输入 → null（UI 提示重试，不崩溃）。
         */
        internal fun parsePlanJsonImpl(raw: String): Pair<String, List<DistillSession.PlanItem>>? =
            try {
                // 代码块包裹容忍（模型爱包 ```json … ```——先剥 fence 再 trim）
                var trimmed = raw.trim()
                if (trimmed.startsWith("```")) {
                    trimmed = trimmed.removePrefix("```json").removePrefix("```")
                        .removeSuffix("```")
                        .trim()
                }
                val obj = Json.parseToJsonElement(trimmed).jsonObject
                val repo = obj["repo"]?.jsonPrimitive?.content ?: "unknown"
                val plan = obj["plan"]?.jsonArray?.mapNotNull { el ->
                    val item = el.jsonObject
                    val path = item["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val reason = item["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                    DistillSession.PlanItem(path = path, reason = reason)
                } ?: emptyList()
                repo to plan
            } catch (e: Exception) {
                null
            }
    }
}

/** List<PlanRow>/List<ReviewRow> 的 update 辅助（MutableStateFlow.update 缩写） */
private inline fun <T> MutableStateFlow<List<T>>.updateRows(transform: (List<T>) -> List<T>) {
    value = transform(value)
}
