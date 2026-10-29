package com.jiaoay.easy_dev_toolbox.page.quest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.QuestStore
import com.jiaoay.easy_dev_toolbox.core.data.db.SqliteSessionStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskCaps
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskRequest
import com.jiaoay.easy_dev_toolbox.core.navigation.SpaceAttribution
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 流程页 VM（Quest 域 change 2——openspec add-flow-page 任务 3.2）。
 *
 * ## 状态面
 * - [flows]：历史列表（QuestStore.listFlows 倒序——左栏数据源）；
 * - [selectedCapabilities]：新建表单能力多选态（空集 = 通用对话隐式能力）；
 * - [goalInput]：新建表单目标描述（非空才可开始）；
 * - [activeFlow]：当前会话态（null = ListMode；非 null = ChatMode）；
 * - 会话流面透传（entries/running/pendingQuestion）。
 *
 * ## openFlow 两层各司其职（design A：ChatMemory 层 + messages 表层）
 * UI 时间线经 timeline.replay 恢复；ChatMemory 续跑经 sessionId=flowId 自动挂接
 * （QuestHistoryProvider 持久层——重启后追问仍记得前文）。
 *
 * VM 构造零 LLM 触达（init 只读 flows 列表——DataStore/DB 直读）。
 */
class FlowViewModel : ViewModel() {

    private val timeline = SqliteSessionStore(Database.database())
    private val store = QuestStore(
        db = Database.database(),
        chatHistoryDbPath = File(FileUtils.getDbDir(), "toolbelt.db").absolutePath,
    )
    private val historyProvider = QuestHistoryProvider.forFile(
        File(FileUtils.getDbDir(), "toolbelt.db").absolutePath,
    )

    private val _flows = MutableStateFlow<List<QuestStore.FlowSummary>>(emptyList())
    val flows: StateFlow<List<QuestStore.FlowSummary>> = _flows.asStateFlow()

    /** 新建表单：能力多选态（id 集） */
    private val _selectedCapabilities = MutableStateFlow<Set<String>>(emptySet())
    val selectedCapabilities: StateFlow<Set<String>> = _selectedCapabilities.asStateFlow()

    /** 新建表单：目标描述 */
    private val _goalInput = MutableStateFlow("")
    val goalInput: StateFlow<String> = _goalInput.asStateFlow()

    /** 当前会话态（null = ListMode；ChatMode 透传会话流） */
    data class ActiveFlow(
        val id: String,
        val title: String,
        val capabilities: List<String>,
        val firstRound: Boolean, // 首轮 = 尚未发过消息（开工纪律段带上）
    )

    private val _activeFlow = MutableStateFlow<ActiveFlow?>(null)
    val activeFlow: StateFlow<ActiveFlow?> = _activeFlow.asStateFlow()

    /**
     * S5b · 会话页可见性信号（「当前 tab ≠ 会话页」判据的自造通路）：
     * FlowChat 组合期 markActive(true)、离场 onDispose markActive(false)——
     * feature-quest 禁 import 宿主 AppViewModel，AnimatedContent 只组合当前页，
     * 可见性只能由页面组合生命周期自证。
     */
    @Volatile
    private var chatVisible = false

    /** S5b · 可见性回写（FlowChat DisposableEffect 两侧触达——组合 = true / 离场 = false） */
    fun markActive(visible: Boolean) {
        chatVisible = visible
    }

    /** AI 开关镜像（feature 互不依赖铁律——AiToolsToggle 在 feature-ai 不可 import，
     *  直读 :core 配置域单点 LlmConfigStore.getAiEnabled；工作台同款先例）。 */
    private val _aiEnabled = MutableStateFlow(false)

    /** AI 开关镜像流（新建按钮置灰判定 + AI 关闭降级文案） */
    val aiEnabled: StateFlow<Boolean> = _aiEnabled.asStateFlow()

    private var session: QuestSession? = null

    /** S5b · 完成通知 collector 句柄（🔴 切流程先 cancel——旧 collector 随切换累积的
     *  常驻泄漏收口；同形态先例见 QuestSession companion 的 mirrorJob） */
    private var completionWatchJob: Job? = null

    // ── 会话流透传面（session 为 null 时用缓存空实例——每次新建会让
    //    collectAsState 的 flow key 失稳导致重复重启收集） ──

    private val emptyEntries = MutableStateFlow<List<ChatEntry>>(emptyList())
    private val emptyRunning = MutableStateFlow(false)
    private val emptyQuestion = MutableStateFlow<AskRequest?>(null)
    private val emptyGate = MutableStateFlow<File?>(null)
    private val emptyQueue = MutableStateFlow<List<String>>(emptyList())
    private val emptyEvidence = MutableStateFlow<List<String>>(emptyList())

    val entries: StateFlow<List<ChatEntry>>
        get() = session?.entries ?: emptyEntries
    val running: StateFlow<Boolean>
        get() = session?.running ?: emptyRunning
    val pendingQuestion: StateFlow<AskRequest?>
        get() = session?.pendingQuestion ?: emptyQuestion

    /** 围栏外路径确认面（PathConfirmCard 渲染数据源） */
    val pendingGate: StateFlow<File?>
        get() = session?.pendingGate ?: emptyGate

    /** S5c · Steer 排队面（Composer 上方「已排队 N 条」提示行数据源） */
    val pendingQueue: StateFlow<List<String>>
        get() = session?.pendingQueue ?: emptyQueue

    /** S5d · 会话证据文件面（chips 深链数据真源；本页第一版不渲染侧栏消费面） */
    val evidenceFiles: StateFlow<List<String>>
        get() = session?.evidenceFiles ?: emptyEvidence

    init {
        refreshFlows()
        viewModelScope.launch {
            _aiEnabled.value = runCatching { LlmConfigStore.getAiEnabled() }.getOrDefault(false)
        }
    }

    fun refreshFlows() {
        viewModelScope.launch {
            _flows.value = withContext(Dispatchers.IO) { store.listFlows() }
        }
    }

    // ── 新建表单动作 ──

    fun setGoalInput(text: String) {
        _goalInput.value = text
    }

    fun toggleCapability(id: String) {
        _selectedCapabilities.value = _selectedCapabilities.value.let {
            if (id in it) it - id else it + id
        }
    }

    /** 开始新流程（goal 非空才可；title 由 goal 派生） */
    fun startFlow() {
        val goal = _goalInput.value.trim()
        if (goal.isEmpty()) return
        // in-flight 守卫：launch 体在首个挂起点前不建会话——双击会跑进两个协程
        // 双建双发（同毫秒 flowId 撞 SQLite 主键）
        if (_startInFlight.value) return
        _startInFlight.value = true
        // 使用计数口径：首轮消息发送 = 一次真实使用
        ToolUsageSignal.report(ToolUsageSignal.PageId.FLOW)
        viewModelScope.launch {
            try {
                // flowId 用 UUID：毫秒时间戳在快速双击/重放场景可撞主键（UUID 根治——
                // 工作台 runId 同形态）
                val flowId = "flow-${UUID.randomUUID()}"
                val title = store.deriveTitle(goal)
                store.create(flowId, title, _selectedCapabilities.value.sorted())
                // 空间归属：会话创建成功 → 记入当前调查空间
                // （:core 中立槽——无选中空间静默跳过；挂 VM 既有协程 + IO 语境承接
                // 同步 SQLite 写，归属失败不拖垮创建——槽内 JLog 收口）
                withContext(Dispatchers.IO) { SpaceAttribution.attributeFlow(flowId) }
                refreshFlows()
                enterFlow(
                    ActiveFlow(id = flowId, title = title, capabilities = _selectedCapabilities.value.sorted(), firstRound = true),
                )
                // 首轮消息 = 目标描述（表单 goal 即用户第一句话——开工纪律段带上）
                session?.send(goal, firstRound = true, promptSection = promptSectionFor(_selectedCapabilities.value))
                _activeFlow.value = _activeFlow.value?.copy(firstRound = false) // 首轮已发
                _goalInput.value = ""
                _selectedCapabilities.value = emptySet()
            } finally {
                // 成功/异常/取消三路都复位（finally 兜底——守卫恒不自锁）
                _startInFlight.value = false
            }
        }
    }

    /** startFlow 在途位（Main 域读写——点击与协程体同线程；StateFlow 承载供 UI busy 接线） */
    private val _startInFlight = MutableStateFlow(false)
    val startInFlight: StateFlow<Boolean> = _startInFlight.asStateFlow()

    // ── 历史进入（两层各司其职：replay 恢复 UI + ChatMemory 自动挂接续跑） ──

    fun openFlow(id: String) {
        if (session?.running?.value == true) {
            // 拒绝必带原因：运行中锁历史浏览是会话完整性保护——静默 return 让
            // 会话行点击零响应成「页面坏了」错觉
            StatusSignals.show("当前会话运行中，请先中止或等本轮结束后再切换")
            return
        }
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) { store.flowById(id) } ?: return@launch
            val restored = withContext(Dispatchers.IO) { timeline.replay(id) }
            enterFlow(
                ActiveFlow(
                    id = id,
                    title = summary.title,
                    capabilities = summary.capabilities,
                    firstRound = false, // 历史进入非首轮——续聊不带开工纪律
                ),
            )
            session?.restore(restored)
        }
    }

    private suspend fun enterFlow(active: ActiveFlow) {
        session?.cancel()
        val fresh = newSession(active.id, active.capabilities.toSet())
        session = fresh
        _activeFlow.value = active
        // S5a · dock RUN 徽标：companion activity 镜像 attach（切会话先 detach 收旧镜像——
        // WorkbenchSession 配方；窗口关窗经 onCleared detach 收口）
        QuestSession.attach(fresh, viewModelScope)
        // S5b · 完成通知：running 下降沿 + 会话页不可见 → StatusSignals.show
        //（用户切走后唯一感知通道；页内收尾由时间线自证，不打扰）
        // 🔴 旧 collector 先 cancel（每次 enterFlow 新 launch 一个 collector、旧
        //    的不取消随切换累积——纯常驻小泄漏；旧 session 被新 flow 的 mirror 取代后
        //    恒 false 无伪通知，行为安全但协程随切换次数线性滞留到 onCleared）
        completionWatchJob?.cancel()
        completionWatchJob = viewModelScope.launch {
            var prev = fresh.running.value
            fresh.running.collect { cur ->
                if (prev && !cur && !chatVisible) {
                    StatusSignals.show("会话「${_activeFlow.value?.title ?: ""}」已完成")
                }
                prev = cur
            }
        }
    }

    private suspend fun newSession(flowId: String, capabilities: Set<String>): QuestSession = QuestSession(
        scope = viewModelScope,
        flowId = flowId,
        configProvider = { LlmEndpointConfig.read() },
        executorProvider = { config -> LlmClientFactory.executor(config) },
        modelProvider = { config -> LlmClientFactory.model(config.modelId) },
        historyProvider = historyProvider,
        ledger = timeline,
        extraToolNames = toolNamesFor(capabilities),
        // 勾日志分析 → 私钥缝接通（analyze_log 可装）；未勾 = null 不装管线（纯明文分析）
        privateKeyProvider = if ("log-analysis" in capabilities) {
            com.jiaoay.easy_dev_toolbox.core.llm.tools.DecryptTools.defaultPrivateKeyProvider
        } else {
            null
        },
        // L2 记忆目录：与工作台同源（memoryDir 门控 memory.* 装配）
        memoryDir = com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getMemoryDir(),
        askCapMillis = AskCaps.resolveAskCapMillis(
            LlmConfigStore.getTimeoutSeconds() * 1000L * QuestSession.OVERALL_TIMEOUT_ROUNDS,
        ),
    )

    // ── 会话动作面 ──

    /** 追问/回答发送（ChatMode 输入框——续轮恒非首轮；能力 prompt 段不再重拼：
     *  首轮已随消息进 ChatMemory 历史，重发会在历史里重复注入）。
     *  @return false = 队满被拒（[QuestSession.PENDING_QUEUE_MAX]）——调用方保留输入框
     *  文本（Toast 已回执「队列已满」），本轮结束后重提。 */
    fun sendMessage(text: String): Boolean {
        val s = session ?: return true
        if (text.isBlank()) return true
        if (!s.send(text, firstRound = false)) {
            StatusSignals.show("队列已满（${QuestSession.PENDING_QUEUE_MAX} 条）——本轮结束后再插话")
            return false
        }
        // 使用计数口径：消息发送成功 = 一次真实使用
        ToolUsageSignal.report(ToolUsageSignal.PageId.FLOW)
        viewModelScope.launch {
            val id = _activeFlow.value?.id
            if (id != null) withContext(Dispatchers.IO) { store.touch(id) }
            refreshFlows()
        }
        return true
    }

    /** AskCard 提交 */
    fun resolveAsk(values: Map<String, String>) {
        session?.resolveAsk(values)
    }

    /** AskCard 跳过 */
    fun skipAsk() {
        session?.skipAsk()
    }

    /** 路径确认卡：批准（路径登记进围栏，本会话后续免确认） */
    fun approveGate() {
        session?.approveGate()
    }

    /** 路径确认卡：拒绝（工具收 DENIED 软反馈） */
    fun denyGate() {
        session?.denyGate()
    }

    /** 附件拖入 */
    fun registerFiles(files: List<File>) {
        session?.registerFiles(files)
    }

    /** 停止本轮 */
    fun cancelRun() {
        session?.cancel()
    }

    /** 删除流程（三店级联——change 1 QuestStore.delete） */
    fun deleteFlow(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            if (_activeFlow.value?.id == id) {
                session?.cancel()
                session = null
                _activeFlow.value = null
            }
            refreshFlows()
        }
    }

    /** 重命名 */
    fun renameFlow(id: String, title: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.rename(id, title) }
            if (_activeFlow.value?.id == id) {
                _activeFlow.value = _activeFlow.value?.copy(title = title)
            }
            refreshFlows()
        }
    }

    // ── 能力映射（QuestCapabilities 目录真源——change 3 接实际能力注册项） ──

    /** 新建表单展示面（目录直读） */
    val abilityOptions: List<Pair<String, String>>
        get() = QuestCapabilities.all.map { it.id to it.title }

    private fun toolNamesFor(capabilities: Set<String>): Set<String> =
        QuestCapabilities.all
            .filter { it.id in capabilities }
            .flatMap { it.toolNames }
            .toSet()

    private fun promptSectionFor(capabilities: Collection<String>): String =
        QuestCapabilities.all
            .filter { it.id in capabilities }
            .joinToString("\n\n") { it.promptSection }

    /** 回新建表单态（左栏「新建」按钮——ChatMode 退出出口） */
    fun exitFlow() {
        if (session?.running?.value == true) session?.cancel()
        session = null
        _activeFlow.value = null
    }

    // ── S5a · 活动镜像生命周期收口（WorkbenchViewModel 同款先例） ──

    override fun onCleared() {
        completionWatchJob?.cancel()
        QuestSession.detach()
        super.onCleared()
    }
}
