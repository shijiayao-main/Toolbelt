package com.jiaoay.easy_dev_toolbox.page.larkbridge

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.lark.bridge.BridgeStartResult
import com.jiaoay.lark.bridge.ConfigApplyResult
import com.jiaoay.lark.bridge.RegisterAppResult
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.BridgeJson
import com.jiaoay.lark.bridge.config.ConfigValidator
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.lark.bridge.observe.EnvironmentProbe
import com.jiaoay.lark.bridge.observe.LogBus
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * 飞书桥接页的唯一状态源：配置文档（[BridgeConfig]）的内存编辑器 + 运行监控指令转发。
 *
 * ## 写入路径（严格照方案「配置模型：单一 JSON 文档」小节）
 * ```
 * updateField → _draft 即时更新（内存） → 400ms trailing debounce → Mutex 单写者
 *   → 校验通过才 store.applyConfig(persist=true) 落盘；校验失败**不覆写文件**
 * ```
 * - **draft 即时更新**：每次 [updateField] / [updateConfig] 立刻改 [_draft] 并重算 [_issues]，UI 零延迟回显；
 * - **400ms trailing debounce**：[_draft] 流 `debounce(400).drop(1)`——高频键击合并为一次落盘，
 *   `drop(1)` 确保「仅打开页面（初始值）」**不触发落盘**（不生成 bridge-config.json）；
 * - **Mutex 单写者**：[writeMutex] 串行化落盘，杜绝并发写覆盖；
 * - **校验失败不覆写**：[_issues] 非空时 [persist] 直接返回，文件保持旧内容，UI 顶部红条 + 字段级 isError。
 *
 * ## 挂起纪律
 * [onStart] / [onStop] / [persist] 内的 store 调用都是 `suspend`（内含网关建连 / 策略热更新的秒级网络调用），
 * 一律在 [viewModelScope].launch 内调用，**本文件零 `runBlocking`**（否则冻结 Compose UI）。
 */
@OptIn(FlowPreview::class)
class LarkBridgeViewModel(
    private val store: BridgeStore = LarkBridgeStore,
) : ViewModel() {

    private val _assembling = MutableStateFlow(false)

    /** 首进装配在途（页面 loading 态数据源）。 */
    val assembling: StateFlow<Boolean> = _assembling.asStateFlow()

    // 🔴 draft 初值 = **装配后的磁盘真值**。
    //    若以未装配的 `store.config` 起步会回退全默认 ⇒ 空表单起步 ⇒ 用户首次编辑经 debounce
    //    落盘，把「默认值+局部编辑」整份覆写磁盘配置（凭据被抹，下次 start 即「appId 为空」）。
    //    🔴 构造期零阻塞：getOrCreate 同步装配（runBlocking×2 种子刷 + JDBC DDL + 文件锁）不得压进
    //    VM 构造 = 首进桥接页冻结主线程数百 ms~秒级。修正 = 构造期零阻塞：draft 以磁盘快照起步（装配前
    //    getOrCreate 同步装配（runBlocking×2 种子刷 + JDBC DDL + 文件锁）压进 VM 构造 = 首进
    //    桥接页冻结主线程数百 ms~秒级。修正 = 构造期零阻塞：draft 以磁盘快照起步（装配前
    //    config getter 已能读 ConfigRepository 磁盘文档），装配下放 init 协程 IO 线程 +
    //    [_assembling] loading 态；装配完成后若磁盘真值 ≠ 现值则回填——「draft 永不
    //    从空默认起步」由快照起步 + 回填双保险承接（未装配读磁盘本身就是真值非空默认）。
    //    debounce 落盘闸不变：回填经 _draft.value 直写（非用户编辑，不触发落盘流）。
    private val _draft = MutableStateFlow(store.config)

    /** 内存中的配置草稿（编辑器唯一数据源；即时反映用户输入）。 */
    val draft: StateFlow<BridgeConfig> = _draft.asStateFlow()

    private val _issues = MutableStateFlow(computeIssues(store.config))

    /** 字段级校验错误：`fieldId → message`（来自 [ConfigValidator.validate] 纯函数）。 */
    val issues: StateFlow<Map<String, String>> = _issues.asStateFlow()

    private val _applyResult = MutableStateFlow<ConfigApplyResult?>(null)

    /** 最近一次落盘的分流结果（用于「需重启才生效」黄条提示）。 */
    val applyResult: StateFlow<ConfigApplyResult?> = _applyResult.asStateFlow()

    private val _startResult = MutableStateFlow<BridgeStartResult?>(null)

    /** 最近一次启动结果（[BridgeStartResult]；null = 尚未尝试）。 */
    val startResult: StateFlow<BridgeStartResult?> = _startResult.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** 启动 / 停止在途标志（禁用操作按钮，防重复点击）。 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _busyAction = MutableStateFlow<BusyAction?>(null)

    /**
     * 在途动作（busy 小圈长在**正确的按钮**上——
     * `if (busy)` 只驱动「启动」按钮转圈的话，点停止时会启动按钮 label↔spinner 两次跳变（闪烁主源）。
     */
    val busyAction: StateFlow<BusyAction?> = _busyAction.asStateFlow()

    private val _banner = MutableStateFlow<String?>(null)

    /** 一次性操作反馈（导入 / 导出 / 打开目录的结果文案；用户可清除）。 */
    val banner: StateFlow<String?> = _banner.asStateFlow()

    private val _probe = MutableStateFlow<EnvironmentProbe?>(null)

    /** 最近一次环境预检结果（null = 尚未预检 / 未装配）。 */
    val probe: StateFlow<EnvironmentProbe?> = _probe.asStateFlow()

    private val _registerState = MutableStateFlow<RegisterAppState>(RegisterAppState.Idle)

    /**
     * 扫码建应用流程状态（阶段四产品转正）。QR 出现时 UI 渲染二维码与倒计时；
     * Success 后 [onStartRegister] 已自动把凭据回填进 draft（走常规 updateField 落盘链路）。
     */
    val registerState: StateFlow<RegisterAppState> = _registerState.asStateFlow()

    // L2 授权清单的探测验证三态（GrantProbe.VerifyState）。
    //    **运行时观察值，不落盘**——「桌面授权（l2Grants）与后台授权（scope+发版）是两道独立闸」，
    //    本 Map 只观察第二道闸的当下状态：存 VM 内存、重启即失、绝不进 bridge-config.json（红线 2）。
    private val _grantVerifyStates =
        MutableStateFlow<Map<com.jiaoay.lark.bridge.config.NativeToolGrant, GrantProbe.VerifyState>>(emptyMap())

    /**
     * L2 各授权项的探测三态（grant → state；缺项 = NotVerified）。
     * 能力中心卡（BotCapabilityCard）勾选清单的状态 pill 数据源。
     */
    val grantVerifyStates:
        StateFlow<Map<com.jiaoay.lark.bridge.config.NativeToolGrant, GrantProbe.VerifyState>> =
        _grantVerifyStates.asStateFlow()

    /** 探测在途的 grant 集合（验证按钮 busy 态；与三态 Probing 同源，独立存防并发覆盖）。 */
    private val _probingGrants =
        MutableStateFlow<Set<com.jiaoay.lark.bridge.config.NativeToolGrant>>(emptySet())

    // 最近任务表情回显（userMessageId → ReactionBrief 清单）。
    //    **运行时观察值，不落盘**——grantVerifyStates 同款模式：只观察当下状态、存 VM 内存、
    //    重启即失，绝不进 bridge-config.json（红线 4）。null 语义：audit 旧行无 userMessageId
    //    （历史锚点缺席）⇒ map 无该键 ⇒ 徽标不渲染（诚实缺席，不伪造）。
    private val _taskReactions = MutableStateFlow<Map<String, List<com.jiaoay.lark.gateway.ReactionBrief>>>(emptyMap())

    /**
     * 最近任务表情回显（key = audit 的 userMessageId；缺项 = 旧行无锚点/能力缺席/未刷新）。
     * 驾驶舱「最近任务」行尾徽标数据源（过滤机器人自点在 UI 层做——botOpenId 在页面快照里）。
     */
    val taskReactions: StateFlow<Map<String, List<com.jiaoay.lark.gateway.ReactionBrief>>> =
        _taskReactions.asStateFlow()

    // 🔴 导入 JSON 的 L2 危险确认挂起态
    //    （_grantVerifyStates 同款私有 StateFlow 模式）。导入是**覆盖性危险操作**——
    //    「导入 JSON」钮选好文件后只置本态（[requestImport]），经 AppConfirmDialog 确认
    //    （[confirmImport]）才走 [importJson]；确认前 draft 不动、零磁盘变化（debounce 落盘链不触发）。
    private val _pendingImport = MutableStateFlow<File?>(null)

    /** 待确认的导入文件（null = 无待确认导入；OperationBar 消费渲染确认框）。 */
    val pendingImport: StateFlow<File?> = _pendingImport.asStateFlow()

    /** 单写者锁：串行化落盘（trailing debounce 后的第二道保险）。 */
    private val writeMutex = Mutex()

    init {
        // 首进异步装配：装配下放 IO——getOrCreate 的 runBlocking 种子刷
        // + JDBC DDL + 文件锁全部离开主线程；期间 [assembling]=true 驱动页内 loading。
        viewModelScope.launch {
            if (!store.isAssembled) {
                _assembling.value = true
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        store.ensureAssembled()
                    }
                    // 装配后磁盘真值回填（未装配期 getter 快照可能陈旧；用户已在 loading
                    // 期间开始编辑的话放弃回填防覆写——编辑真源优先）
                    val fresh = store.config
                    if (fresh != _draft.value && _issues.value.isEmpty()) {
                        _draft.value = fresh
                        _issues.value = computeIssues(fresh)
                    }
                } finally {
                    _assembling.value = false
                }
            }
        }
        // 旧 DataStore 35 key 的一次性迁移：飞书 feature 入口异步触发（先于任何装配与落盘）。
        //    迁移可能改写磁盘文档，故迁移后重新读取 store.config 刷新 draft（内存 fake 下为 no-op，不改 draft）。
        //    独立协程、不阻塞 UI；[store.ensureLegacyMigrated] 幂等（已迁移/无旧数据/config 已存在时零副作用）。
        // autoStart：迁移完成后判定（迁移可能刚把 autoStart 从旧配置搬进来，必须在迁移之后判定）；
        viewModelScope.launch {
            store.ensureLegacyMigrated()
            val migrated = store.config
            if (migrated != _draft.value) commit(migrated)
            // autoStart 接线——幂等闸门保证进程内只触发一次，重复进页不重复触发
            store.ensureAutoStarted()
        }
        viewModelScope.launch {
            _draft
                .debounce(PERSIST_DEBOUNCE_MILLIS.milliseconds)
                .drop(1) // 丢弃初始值：仅打开页面不落盘（不生成 bridge-config.json）
                .collect { cfg -> persist(cfg) }
        }
    }

    /** 数据根（配置目录 / 导入导出锚点）。 */
    val dataRoot: Path get() = store.dataRoot

    /**
     * `bridge-config.json` 是否已存在（进页探测一次）。
     * 驾驶舱「快速开始」向导的首次用户判定输入：凭据全空**且**配置文件不存在 = 全新用户。
     * 惰性装配语义下仅打开页面不生成该文件（drop(1)），故首次会话中此值恒为「用户真实状态」。
     */
    val configFileExists: Boolean by lazy {
        java.nio.file.Files.exists(dataRoot.resolve("bridge-config.json"))
    }

    // ==================================================================================
    // 编辑：draft 即时更新
    // ==================================================================================

    /** 单字段编辑：立刻改 draft + 重算 issues（debounce 落盘由 init 的收集器负责）。 */
    fun <T> updateField(field: BridgeField<T>, value: T) {
        commit(field.set(_draft.value, value))
    }

    /** 整体配置变换（访问控制编辑、导入等）：立刻改 draft + 重算 issues。 */
    fun updateConfig(transform: (BridgeConfig) -> BridgeConfig) {
        commit(transform(_draft.value))
    }

    /**
     * 提交新配置：短路——[next] 与当前 draft 完全相等则**零副作用**
     * （不改 draft、不重算 issues、不触发 debounce 落盘）。
     * @return 是否实际发生变化
     */
    private fun commit(next: BridgeConfig): Boolean {
        if (next == _draft.value) return false
        _draft.value = next
        _issues.value = computeIssues(next)
        return true
    }

    // ==================================================================================
    // 运行指令（suspend store 调用一律在 viewModelScope.launch 内）
    // ==================================================================================

    /**
     * 启动桥接（[store.start] 恒不抛，失败归一为 [BridgeStartResult.Refused]）。
     * 启动前零网络预检，两档**硬拒绝**（合成 [BridgeStartResult.Refused]、不发起 start）：
     * appId 空 ⇒ [com.jiaoay.lark.bridge.config.BridgeErrorCode.ConfigInvalid]；
     * 装配完成但本地 AI 端点未配置 ⇒ [com.jiaoay.lark.bridge.config.BridgeErrorCode.AgentUnavailable]
     * （agent 恒为硬依赖，不存在「不配端点也能收发」的形态——不可用时的消息面出路是显式报错卡）。
     * 两档**软提示**（横幅、不阻断）：workspace.root 空（工作区缺失是运行期逐任务显式报错，
     * 任务面收窄而非入口不可用）与 CLI 执行后端不可用（Koog 在而 CLI 缺 = 重任务委托失败，
     * 本地 AI 对话不受影响）。端点态与 CLI 态的分层见 [cliAgentAvailable]。
     *
     * 拒绝判据必须用 [BridgeStore.probeEnvironment] 的**非空性**分流，不能用 [aiEndpointReady]：
     * 后者把「未装配」（probe == null，ensureAssembled 惰性装配链未完成）与「端点未配置」
     * 折叠成同一个 false——以它判拒会误拒未装配态、打断惰性装配。
     *
     * 端点判据读前经 [store.refreshEndpointSnapshot] 按需现刷一次（单次 DataStore 读、
     * suspend，故两档预检判据与 start 同在协程内按序执行）——消除「桥接停着、
     * AI 设置页改完端点立即启动」被刷新回路陈旧快照误拒的窗口。
     */
    fun onStart() {
        // 预检（纯 CPU / 零网络）：draft 即用户所见，以 draft 为准
        val draft = _draft.value
        if (draft.connection.appId.isBlank()) {
            _startResult.value = BridgeStartResult.Refused(
                com.jiaoay.lark.bridge.config.BridgeErrorCode.ConfigInvalid,
                "App ID 未填写：请展开「配置」页签，在「连接与准入」分区填写飞书应用凭据",
            )
            return
        }
        viewModelScope.launch {
            // 端点预检点按需现刷（DataStore 挂起读）：判据读到的是当前磁盘配置的快照，
            //    而非刷新回路 ≤30s 慢刷的分档缓存。
            store.refreshEndpointSnapshot()
            // agent 硬依赖：装配完成（probe 非空）且端点未配置 ⇒ 入口硬拒绝（不调 store.start）。
            //    probe == null = 未装配窗口：不拒也不横幅（不可知态，向导第 0 步与驾驶舱提示条已承担引导）。
            val probe = store.probeEnvironment()
            if (probe != null && !probe.agentAvailable) {
                _startResult.value = BridgeStartResult.Refused(
                    com.jiaoay.lark.bridge.config.BridgeErrorCode.AgentUnavailable,
                    "本地 AI 未就绪：请到「AI 设置」页配置模型端点（Base URL / API Key / 模型），配置后重新启动桥接",
                )
                return@launch
            }
            if (draft.workspace.root.isBlank()) {
                _banner.value = "提示：工作区未配置——工作区类任务（写文件/构建/git 查询）不可用，日志分析不受影响；请到「配置」页签选择工作区根目录"
            }
            _busy.value = true
            _busyAction.value = BusyAction.STARTING
            _startResult.value = store.start()
            if ((_startResult.value as? BridgeStartResult.Started) != null) {
                // 使用计数口径：桥接启动成功（含快捷指令
                // CommandRoute 路由的承载面）= 一次真实使用
                ToolUsageSignal.report(ToolUsageSignal.PageId.LARK_BRIDGE)
            }
            _busyAction.value = null
            _busy.value = false
        }
    }

    /** 停止桥接（不清 startResult——保留最近一次启动/预检反馈，避免横幅瞬间消失的闪烁）。 */
    fun onStop() {
        viewModelScope.launch {
            _busy.value = true
            _busyAction.value = BusyAction.STOPPING
            store.stop()
            _busyAction.value = null
            _busy.value = false
        }
    }

    /**
     * 一键重启：RESTART 字段变更后的黄条「重启桥接」按钮调用——
     * stop 完成后接着 start（串行，非并发）。仅桥接运行中可用（未运行时保存即下次启动生效，
     * 按钮不显示）。复用现有 busy/busyAction 体系（RESTARTING 驱动按钮转圈）。
     */
    fun onRestart() {
        viewModelScope.launch {
            _busy.value = true
            _busyAction.value = BusyAction.RESTARTING
            runCatching {
                store.stop()
                _startResult.value = store.start()
            }
            _busyAction.value = null
            _busy.value = false
        }
    }

    /** 状态流（页面 collectAsState 直订——零延迟零空转；未装配 null）。 */
    fun statusFlow(): kotlinx.coroutines.flow.StateFlow<BridgeStatusSnapshot>? = store.status

    /** 状态快照一次性读（降级/兼容面——新代码用 [statusFlow]）。 */
    fun pollStatus(): BridgeStatusSnapshot? = store.status?.value

    /**
     * 进行中确认只读视图（驾驶舱「待你确认」区块数据源）。
     * 纯内存快照读取（非挂起零网络）——页面层在 statusFlow 快照变化时重读
     * （`LaunchedEffect(snapshot)`，与 audit 重读同款事件驱动模式，绝不进采样循环）。
     */
    fun pendingConfirmations(): List<com.jiaoay.lark.bridge.confirm.PendingConfirmView> =
        store.pendingConfirmations()

    /**
     * 消息已读人数（「已读 N 人」）：[store.messageReadCount] 透传
     * （挂起网络调用——只由面板在确认卡已发送时事件驱动调用，不进采样循环）。
     * null = 未运行/能力缺席/查询失败（UI 显示「已读 —」）。
     */
    suspend fun messageReadCount(messageId: String): Int? = store.messageReadCount(messageId)

    /**
     * 刷新最近任务表情回显（「👍 ×N」徽标）。
     *
     * 节流纪律（红线 3）：只在 audit 重读后调用（audit 集已变 ⇒ 新行可能有新锚点），
     * **绝不 1Hz 每秒刷**（[store.listMessageReactions] 是挂起网络调用，不进采样循环）。
     * IO 并行逐条查询：runCatching 恒不抛；null（能力缺席/未运行）静默缺席**不写 map**——
     * 旧 map 值保留（不伪造清空）；旧行无 userMessageId 不进查询清单（历史锚点诚实缺席）。
     *
     * @param messageIds 最近任务的 userMessageId 清单（已过滤空白/null 锚点）
     */
    fun refreshTaskReactions(messageIds: List<String>) {
        val ids = messageIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            // IO 并行逐条查询：每条独立 runCatching，null（能力缺席/未运行）静默缺席不写 map
            ids.forEach { id ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    val reactions = runCatching { store.listMessageReactions(id) }.getOrNull()
                    if (reactions != null) {
                        _taskReactions.value = _taskReactions.value + (id to reactions)
                    }
                }
            }
        }
    }

    /**
     * 本地 AI 端点就绪态订阅流（快速开始第 0 步与缺端点提示条数据源）。
     *
     * 订阅式：经 [BridgeStore.aiEndpointReady]——发布点是端点快照的单点刷新（装配期种子刷 /
     * 分档刷新回路 / 预检点按需现刷共用同一发射口），端点配置变更随下一次快照刷新即时反映；
     * 页面 collectAsState 订阅，组合期零挂起读。
     * 桥接未装配时恒 false（惰性装配语义下「未装配 ≈ 端点未验证」，与未装配时 hero
     * 显示「未运行」同口径的保守方向）；装配由 init 的 ensureAssembled 异步链完成后，
     * 装配期种子刷经发布点把真实端点态发射进本流。
     */
    val aiEndpointReady: StateFlow<Boolean> = store.aiEndpointReady

    /**
     * 环境预检：同步汇总 agent 后端可用性 + 凭据就绪 + 运行态，**零网络调用**；
     * 结果经 [probe] 暴露、并给一条 [banner] 文案回显。未装配时提示。
     *
     * 读数前经 [store.refreshEndpointSnapshot] 按需现刷端点快照（单次 DataStore 读、
     * suspend，故整段在协程内）——预检 banner 常驻至下次预检、不随刷新回路自愈，
     * 读陈旧缓存快照会把 ≤30s 的过时端点态固化进常驻文案。
     */
    fun onProbe() {
        viewModelScope.launch {
            store.refreshEndpointSnapshot()
            val result = store.probeEnvironment()
            _probe.value = result
            _banner.value = result?.let {
                "预检：后端 ${it.agentBackend}（${if (it.agentAvailable) "可用" else "不可用"}）· " +
                    "凭据${if (it.credentialReady) "就绪" else "未填写"} · ${if (it.running) "运行中" else "未运行"}"
            } ?: "预检失败：桥接未装配（请先启动一次以完成装配）"
        }
    }

    /**
     * 发测试消息。suspend 转发一律在 [viewModelScope].launch 内；
     * **零真实网络**由 `connection.dryRun=true` → SyntheticGateway 保证（生产装配见 LarkBridgeManager）。
     * 结果经 [banner] 回显（成功回显 messageId、失败回显归一错误码名）。
     */
    fun onSendTest(chatId: String, text: String) {
        viewModelScope.launch {
            _busy.value = true
            val result = store.sendTest(chatId, text)
            _banner.value = when {
                result == null -> "发送失败：桥接未运行（请先启动）"
                result.ok -> "测试消息已发送（messageId=${result.messageId ?: "-"}）"
                else -> "发送失败：${result.error?.name ?: "未知错误"}"
            }
            _busy.value = false
        }
    }

    /**
     * L2 授权清单的「验证」入口：只读探测单 grant 的后台 scope 是否开通。
     *
     * 探测纪律（红线 2）：只读（read-only API）、手动触发（仅本方法）、失败不重试、
     *    不阻塞落盘（独立协程 + 结果只进 [grantVerifyStates]，与 draft/persist 零交集）、
     *    **结果不落盘**（运行时观察值，重启即失——见 [GrantProbe] 类头）。
     *
     * 前置闸（不满足直接落失败态，不发网络）：
     * - 桥接未运行 → 红 pill「桥接未运行」（调用方按钮已置灰，此处防御双保险）；
     * - testChatId 空（CHAT_HISTORY/CHAT_INFO）→ 红 pill「先填测试会话 ID」；
     * - LOOKUP_USER 无目标 openId → 红 pill 提示发消息/填白名单（[GrantProbe.latestSenderOpenId]）；
     * - READ_WIKI/READ_DOC 无探测链接 → 红 pill 提示粘贴链接（探测目标由用户指定——
     *   自建应用无保证可读的公共节点）。
     *
     * 在途去重：同一 grant 探测中再点直接忽略（[GrantProbe.VerifyState.Probing] 期按钮已禁用）。
     *
     * @param probeDocUrl 读文档两项的探测目标（用户粘贴的 wiki/docx 链接；其余 grant 忽略）
     */
    fun onVerifyGrant(
        grant: com.jiaoay.lark.bridge.config.NativeToolGrant,
        probeDocUrl: String = "",
    ) {
        if (!GrantProbe.supportsProbe(grant)) return
        if (_probingGrants.value.contains(grant)) return
        val cfg = _draft.value
        // 在途标记（pill 转探测中）——launch 前同步置位防双击竞态
        _probingGrants.value = _probingGrants.value + grant
        _grantVerifyStates.value = _grantVerifyStates.value + (grant to GrantProbe.VerifyState.Probing)
        viewModelScope.launch {
            val testChatId = cfg.connection.testChatId
            if (grant != com.jiaoay.lark.bridge.config.NativeToolGrant.LOOKUP_USER &&
                grant != com.jiaoay.lark.bridge.config.NativeToolGrant.READ_WIKI &&
                grant != com.jiaoay.lark.bridge.config.NativeToolGrant.READ_DOC &&
                testChatId.isBlank()
            ) {
                // testChatId 空闸（CHAT_HISTORY/CHAT_INFO 依赖）：按钮已置灰，此处防御双保险
                _grantVerifyStates.value =
                    _grantVerifyStates.value + (grant to GrantProbe.VerifyState.Failed("先填测试会话 ID"))
                _probingGrants.value = _probingGrants.value - grant
                return@launch
            }
            // 读文档两项的前置闸：探测链接空/形态不匹配 → 不发网络直接红 pill（引导文案同门面口径）
            if (grant == com.jiaoay.lark.bridge.config.NativeToolGrant.READ_WIKI && !probeDocUrl.contains("/wiki/")) {
                _grantVerifyStates.value = _grantVerifyStates.value +
                    (grant to GrantProbe.VerifyState.Failed("先粘贴一条 /wiki/ 链接再验证"))
                _probingGrants.value = _probingGrants.value - grant
                return@launch
            }
            if (grant == com.jiaoay.lark.bridge.config.NativeToolGrant.READ_DOC && !probeDocUrl.contains("/docx/")) {
                _grantVerifyStates.value = _grantVerifyStates.value +
                    (grant to GrantProbe.VerifyState.Failed("先粘贴一条 /docx/ 链接再验证"))
                _probingGrants.value = _probingGrants.value - grant
                return@launch
            }
            val lookupOpenId = if (grant == com.jiaoay.lark.bridge.config.NativeToolGrant.LOOKUP_USER) {
                // LOOKUP_USER 目标 openId：sender_stats 最近发送者 → 白名单首项（取舍见 GrantProbe KDoc）；
                // 全空 → 不发网络，直接红 pill 引导
                val openId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    GrantProbe.latestSenderOpenId(dataRoot, cfg)
                }
                if (openId == null) {
                    _grantVerifyStates.value = _grantVerifyStates.value + (grant to GrantProbe.VerifyState.Failed(
                        "无探测目标：先给机器人发条消息或填白名单",
                    ))
                    _probingGrants.value = _probingGrants.value - grant
                    return@launch
                }
                openId
            } else {
                ""
            }
            // 探测结果统一落三态（null = 通过→绿 pill 带 HH:mm；非 null = 失败人话→红 pill）。
            // 🔴 双连接互踢修复：改走 store.probeNativeGrant 窄口
            //    （经运行中网关，与 sendTest 同门面形态）——旧 probeGrant 另造一次性网关并 connect，
            //    会开第二条 :lark-ws 长连接把主桥接踢下线（互踢，详见 BridgeStore.probeNativeGrant KDoc）。
            // runCatching 兜底：lib 门面恒不抛（runCatching 已收口），此处防 VM 侧意外
            var failureText: String? = null
            runCatching {
                store.probeNativeGrant(
                    grant = grant,
                    testChatId = testChatId,
                    lookupOpenId = lookupOpenId,
                    defaultFailText = GrantProbe.defaultFailText(grant),
                    probeDocUrl = probeDocUrl,
                )
            }.onFailure { failureText = GrantProbe.defaultFailText(grant) }
            _grantVerifyStates.value = _grantVerifyStates.value + (grant to if (failureText == null) {
                GrantProbe.VerifyState.Verified(System.currentTimeMillis())
            } else {
                GrantProbe.VerifyState.Failed(failureText!!)
            })
            _probingGrants.value = _probingGrants.value - grant
        }
    }

    /**
     * 当前日志台（未装配 / `logConsole.enabled=false` 时为 null）。
     */
    fun logs(): LogBus? = store.logs

    /** 扫码流程协程句柄（再入守卫判据——UI 态可被收起，Job 活性才是「轮询在途」真源） */
    private var registerJob: kotlinx.coroutines.Job? = null

    /** 扫码对话框被用户收起标记（QrShowing 态收起置位——迟到终态落横幅不再弹对话框） */
    private var registerUiDismissed = false

    /**
     * 发起扫码建应用（阶段四产品转正）。🔴 全程在 viewModelScope 独立协程：SDK 流程阻塞最长
     * 约 600s 且**不可中断**（无 cancel 钩子——直接在调用线程阻塞会冻结界面至超时）——
     * 页面切换只让本协程的后续状态更新失效（Job 已取消），
     * 不杀 SDK 轮询；QR 回调只转发状态（不做 IO）。Success 自动回填凭据进 draft（走常规落盘链路）。
     *
     * 🔴 再入守卫 = [registerJob] 活性判据而非 UI 态：对话框可被用户收起（UI 态回 Idle
     *    而 SDK 轮询在途），按 UI 态判会在收起后放进第二个并发 registerApp——双 SDK
     *    轮询竞速 QR 回调与 Success 回填。拒绝必带原因（横幅明示，禁静默）。
     */
    fun onStartRegister() {
        if (registerJob?.isActive == true) {
            _banner.value = "扫码流程仍在后台进行（SDK 轮询最长约 600s、不可中断）——请先等待其结束再重试"
            return
        }
        registerUiDismissed = false
        registerJob = viewModelScope.launch {
            _registerState.value = RegisterAppState.Waiting
            val result = store.registerApp { url, expire ->
                _registerState.value = RegisterAppState.QrShowing(url, expire)
            }
            // 凭据自动回填恒走 updateConfig 常规链路（draft 即时更新 + debounce 落盘 +
            // 校验）——VM 级执行，与对话框是否还开着无关
            if (result is RegisterAppResult.Success) {
                updateConfig {
                    it.copy(
                        connection = it.connection.copy(
                            appId = result.credential.appId,
                            appSecret = result.credential.appSecret,
                            region = result.credential.region,
                        ),
                    )
                }
            }
            if (registerUiDismissed) {
                // 对话框已被用户收起：回执落横幅（迟到的终态不再把对话框重新拉起）
                registerUiDismissed = false
                _registerState.value = RegisterAppState.Idle
                _banner.value = when (result) {
                    is RegisterAppResult.Success ->
                        "扫码建应用完成——appId=${result.credential.appId}（凭据已自动回填）"
                    is RegisterAppResult.QrExpired -> "扫码建应用二维码已过期，可重新发起"
                    is RegisterAppResult.InvalidArgument -> "扫码建应用参数不合法：${result.message}"
                    is RegisterAppResult.Failed -> "扫码建应用失败：${result.message}"
                    null -> "扫码建应用失败：桥接未装配"
                }
            } else {
                _registerState.value = when (result) {
                    is RegisterAppResult.Success -> RegisterAppState.Success(result.credential.appId)
                    is RegisterAppResult.QrExpired -> RegisterAppState.Failed("二维码已过期，请重新发起：${result.message}")
                    is RegisterAppResult.InvalidArgument -> RegisterAppState.Failed("参数不合法：${result.message}")
                    is RegisterAppResult.Failed -> RegisterAppState.Failed(result.message)
                    null -> RegisterAppState.Failed("桥接未装配")
                }
            }
        }
    }

    /**
     * 收起扫码流程对话框（UI 层解耦）：QrShowing 态允许收起——SDK 轮询不可中断，
     * 收起后迟到的终态经横幅落地（不重新弹对话框）；其余态（Waiting/Failed/Success）
     * 回 Idle（Waiting 极短，其迟到回执仍走对话框原语义）。
     */
    fun onDismissRegister() {
        if (_registerState.value is RegisterAppState.QrShowing) {
            registerUiDismissed = true
        }
        _registerState.value = RegisterAppState.Idle
    }

    /** 清除「需重启」黄条。 */
    fun clearApplyResult() {
        _applyResult.value = null
    }

    /**
     * 清除一次性操作反馈（仅 banner）。
     *
     * 收窄为只清 banner——连带清 probe 会把
     * 「当下环境态」的持久结果（常驻至用户处置）一并清掉；5s 自动清除只应处置一次性提示。
     * 唯一消费点 = OperationBar 的 banner 5s 自动清除链（grep 证实）。
     */
    fun clearBanner() {
        _banner.value = null
    }

    /**
     * 仅清除预检结果（banner 不动）——手动关闭只处置 probe
     * （probe 常驻至下次预检覆盖或用户手动关闭，生命周期与一次性 banner 解耦）。
     */
    fun clearProbe() {
        _probe.value = null
    }

    // ==================================================================================
    // 导入 / 导出 JSON（IO 跑 Dispatchers.IO，不阻塞 UI）
    // ==================================================================================

    /**
     * 导出当前 draft 到 [file]（完整 JSON 文档，含 routes；用 [BridgeJson] 保持 classDiscriminator）。
     *
     * 导出件含明文 `appSecret`，属密钥文件族——落盘后必须经 [BridgeStore.hardenSecretFile]
     * 收紧为 POSIX 600（与 `bridge-config.json` 主写路径同一硬化单源）；非 POSIX 平台
     * 由该实现自行留痕跳过。
     */
    fun exportJson(file: File) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(
                        BridgeJson.encodeToString(BridgeConfig.serializer(), _draft.value),
                        Charsets.UTF_8,
                    )
                    store.hardenSecretFile(file)
                }
            }
            _banner.value = result.fold(
                onSuccess = { "已导出到 ${file.name}" },
                onFailure = { "导出失败：${it.message}" },
            )
        }
    }

    /**
     * 导入请求挂起（「导入 JSON」钮选好文件后的唯一动作）。
     * 只置 [pendingImport]——解析/覆盖一律延后到用户在确认框点「导入并覆盖」。
     * 🔴 确认前磁盘零变化：本方法零 IO、零 draft 变更（debounce 落盘链不触发）——
     * 提前落盘会让「取消」失去零副作用语义（覆盖不可逆）。
     */
    fun requestImport(file: File) {
        _pendingImport.value = file
    }

    /** 确认导入——清挂起态后走既有 [importJson]（解析失败保留原 draft，不覆写）。 */
    fun confirmImport() {
        val file = _pendingImport.value ?: return
        _pendingImport.value = null
        importJson(file)
    }

    /** 取消导入——只清挂起态，draft 与磁盘保持原样（零副作用）。 */
    fun cancelImport() {
        _pendingImport.value = null
    }

    /** 从 [file] 导入 JSON 覆盖 draft（解析失败保留原 draft，不覆写）。 */
    fun importJson(file: File) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BridgeJson.decodeFromString(BridgeConfig.serializer(), file.readText(Charsets.UTF_8))
                }
            }
            result.onSuccess { cfg ->
                commit(cfg)
                _banner.value = "已导入 ${file.name}"
            }.onFailure {
                _banner.value = "导入失败：${it.message}"
            }
        }
    }

    // ==================================================================================
    // 落盘（trailing debounce 收集器的终点）
    // ==================================================================================

    private suspend fun persist(cfg: BridgeConfig) {
        writeMutex.withLock {
            // 🔴 校验失败不覆写文件：issues 非空即中止本次落盘
            if (_issues.value.isNotEmpty()) return@withLock
            _applyResult.value = store.applyConfig(cfg, persist = true)
        }
    }

    private fun computeIssues(cfg: BridgeConfig): Map<String, String> =
        ConfigValidator.validate(cfg).associate { it.fieldId to it.message }

    companion object {
        /** 写入路径的 trailing debounce 窗口（方案「配置模型：单一 JSON 文档」小节：400ms）。 */
        const val PERSIST_DEBOUNCE_MILLIS = 400L

        @Composable
        fun getViewModel(): LarkBridgeViewModel = viewModel { LarkBridgeViewModel() }
    }
}

/** 在途动作（busy 转圈反馈长在正确按钮上的判定依据）。 */
enum class BusyAction { STARTING, STOPPING, RESTARTING }

/**
 * 扫码建应用流程状态。
 * [QrShowing] 的 [qrUrl] 是**二维码图片 URL**（SDK QRCodeInfo.getUrl），UI 侧可展示链接或加载图片。
 */
sealed interface RegisterAppState {
    /** 未发起 / 已关闭。 */
    data object Idle : RegisterAppState

    /** 已发起、等待 SDK 出二维码。 */
    data object Waiting : RegisterAppState

    /** 二维码已出（[qrUrl] 图片地址、[expireInSec] 有效期秒数）。 */
    data class QrShowing(val qrUrl: String, val expireInSec: Int) : RegisterAppState

    /** 扫码完成，凭据已自动回填（[appId] 回显用）。 */
    data class Success(val appId: String) : RegisterAppState

    /** 失败（过期 / 参数 / 其它），[message] 给用户。 */
    data class Failed(val message: String) : RegisterAppState
}
