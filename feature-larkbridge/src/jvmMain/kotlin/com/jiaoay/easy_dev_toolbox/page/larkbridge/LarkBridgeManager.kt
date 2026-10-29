package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.db.SqliteChatHistoryProvider
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DecryptTools
import com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.AppDeviceSource
import com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.AppSelfStatusSource
import com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.AppStartupProbe
import com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.PersistedHistory
import ai.koog.prompt.message.Message
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.runtime.AppLifecycleParticipant
import com.jiaoay.easy_dev_toolbox.core.runtime.CapabilityId
import com.jiaoay.easy_dev_toolbox.core.runtime.CapabilityState
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.VoiceTranscriptionHub
import com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.KoogAgentRunner
import com.jiaoay.easy_dev_toolbox.page.larkbridge.log.JLogBridgeDelegate
import com.jiaoay.lark.bridge.Bridge
import com.jiaoay.lark.bridge.BridgeAssembler
import com.jiaoay.lark.bridge.BridgeGateways
import com.jiaoay.lark.bridge.BridgeStartResult
import com.jiaoay.lark.bridge.ConfigApplyResult
import com.jiaoay.lark.bridge.FileSendOutcome
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import com.jiaoay.lark.bridge.handler.AudioTranscriberSeam
import com.jiaoay.lark.bridge.handler.AudioTranscriptionOutcome
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.runtime.CapabilityRegistry
import com.jiaoay.lark.bridge.observe.EnvironmentProbe
import com.jiaoay.lark.bridge.observe.LogBus
import com.jiaoay.lark.gateway.MessageGateway
import com.jiaoay.lark.gateway.SendResult
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 宿主装配与生命周期门面：持有全应用唯一的 [Bridge] 实例。
 *
 * 服务生命周期独立于页面（页面 ViewModel 随导航销毁，桥接服务跨页存活）；页面侧只做观察 + 指令转发。
 * App 退出时由 `main.kt` 的 `DisposableEffect.onDispose` 调 [onAppExit]，防子进程与网关线程泄漏。
 *
 * ## 挂起纪律
 * [Bridge.start] 与 [Bridge.applyConfig] 是 `suspend`（内部包着网关建连 / 策略热更新的挂起网络调用），
 * 故本门面对外同样暴露 `suspend`（[start] / [stop] / [applyConfig]），**必须从协程调用**；
 * [getOrCreate] 内的 `runBlocking { koogRunner.refreshEndpoint() }` 是装配期种子刷
 * （DataStore 是 suspend 读而装配点无协程上下文），刻意保留；
 * 唯一一层**退出期**阻塞桥接在 [Bridge.onAppExit] 内部（`AutoCloseable` / JVM
 * shutdown hook 的必需桥接），[onAppExit] 只是转发，不叠加第二层。
 *
 * ## 装配纪律
 * [BridgeAssembler.assemble] 是全仓唯一装配点（App 与 CLI 共用），本文件是 App 侧唯一调用点
 * （`BridgeAssemblerParityTest` 的路径前缀白名单已含本模块）。装配只构造对象：
 * 网关工厂 [buildGateway] 惰性求值，仅在 [Bridge.start] 内建运行时束时才被调用，
 * 故 [getOrCreate] 本身**零网络连接**。
 */
object LarkBridgeManager : AppLifecycleParticipant {

    private const val TAG = "LarkBridgeManager"

    /**
     * 报告外发的前置拒绝统一文案：未装配与未运行对用户同观感（都须先去桥接页启动），
     * 单一文案防两态两句话的漂移（[deliverReport] 与置灰判据共用口径）。
     */
    private const val BRIDGE_NOT_RUNNING_REASON: String =
        "飞书桥接未运行，请先在飞书桥接页启动桥接"

    /** AppLifecycleParticipant 标识。 */
    override val id: String = "lark-bridge"

    /** Koog 端点快照刷新周期（对齐 RuntimeBundle.METRICS_INTERVAL_MILLIS 先例）。 */
    private const val KOOG_ENDPOINT_REFRESH_MILLIS: Long = 1_000L

    /**
     * 未运行态的慢刷新分档（tick 计数）：未运行时消费面只剩启动预检与页面就绪态显示，
     * 有界新鲜度即可——空闲态不做每秒 6+ 次 DataStore 读。
     */
    private const val KOOG_ENDPOINT_IDLE_REFRESH_TICKS: Int = 30

    /** 唯一门面实例（未装配时为 null）。装配与释放都在 [lock] 内完成。 */
    private val bridgeRef = AtomicReference<Bridge?>(null)

    /** 网关作用域（真实 SDK 网关需要；SyntheticGateway 用不到，但统一创建/取消，无副作用）。 */
    private val gatewayScopeRef = AtomicReference<CoroutineScope?>(null)

    private val lock = Any()

    private val koogRunnerRef = AtomicReference<KoogAgentRunner?>(null)

    /** 宿主 xlog 私钥缓存（回落缝同步读源；事件化 collect 维护，见 [HostXlogKeyCache] KDoc）。 */
    private val hostXlogKey = HostXlogKeyCache(DecryptTools.defaultPrivateKeyFlow)

    val dataRoot: Path
        get() = FileUtils.getLarkDir().toPath()

    /** 当前门面实例（未装配时为 null）。页面侧经此读 `repository` / `confirmStatus` / `isRunning`。 */
    val bridge: Bridge?
        get() = bridgeRef.get()

    /** 是否已装配（区别于 [Bridge.isRunning]：装配不等于在跑）。 */
    val isAssembled: Boolean
        get() = bridgeRef.get() != null

    /** 桥接整体状态快照（1Hz 采样，UI 状态页唯一数据源；未装配时为 null）。 */
    val status: StateFlow<BridgeStatusSnapshot>?
        get() = bridgeRef.get()?.status

    /**
     * 桥接页侧栏活动指示流（恒非 null）：未装配/未跑 ⇒ Idle；在跑 ⇒ Running。
     * 桥式实现：[bridgeActivitySink] 是发布端；订阅在**装配原子点**挂载（[attachHostLoops]）——
     * 订阅若挂 start()，startAll 先于装配跑时 `null?.collect` 静默跳过 ⇒ 圆点永不显示。
     * 布尔源 `BridgeStatusSnapshot.running`——1Hz 快照，无高频重组面。
     */
    val activityFlow: StateFlow<NavActivity>
        get() = bridgeActivitySink

    /** 活动流发布端（object 单例内 MutableStateFlow，与 LarkBridgeManager 生命周期一致）。 */
    private val bridgeActivitySink = kotlinx.coroutines.flow.MutableStateFlow(NavActivity.Idle)

    /**
     * 本地 AI 端点就绪态订阅流（驾驶舱快速开始第 0 步与缺端点提示条的数据源）。
     *
     * 发布点 = [KoogAgentRunner.refreshEndpoint] 写入端点快照后的回调（装配期种子刷、
     * 分档刷新回路、预检点按需现刷共用同一发射口）——端点配置变更随下一次快照刷新
     * 即时反映到订阅方（页面 collectAsState 直接重组），组合期零挂起读。
     * 未装配 / 已释放 ⇒ false（惰性装配语义下「未装配 ≈ 端点未验证」，保守方向——
     * 与 [bridgeActivitySink] 的 Idle 同口径）。
     */
    val koogEndpointReady: StateFlow<Boolean>
        get() = koogEndpointReadySink.asStateFlow()

    /**
     * 端点就绪态发布端。必须是装配周期全程在位的同一实例：订阅方（VM → 页面）
     * 在装配完成前（惰性装配）就已 collect 本流——发射器若随装配换实例，装配后发布
     * 到达的是另一条流，订阅方永不更新。
     */
    private val koogEndpointReadySink = MutableStateFlow(false)

    /** 环形日志台（UI 日志页订阅；未装配时为 null）。 */
    val logs: LogBus?
        get() = bridgeRef.get()?.logs

    /** 当前生效配置（未装配时回退默认配置，便于页面在装配前渲染表单）。 */
    val config: BridgeConfig
        get() = bridgeRef.get()?.config ?: DefaultBridgeConfig.default()

    fun getOrCreate(): Bridge {
        bridgeRef.get()?.let { return it }
        synchronized(lock) {
            bridgeRef.get()?.let { return it }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // 会话内提醒 job 面（remind_user 的延时承载）：绑定网关 scope——
            // shutdown 取消 scope 即全部提醒一并取消（装配周期 = 提醒生命周期）。
            val reminderScheduler = com.jiaoay.easy_dev_toolbox.page.larkbridge.koog.BridgeReminderScheduler(scope)
            // 桥接会话持久层：SqliteChatHistoryProvider 适配 PersistedHistory
            //（写穿透 + 读升级——重启后桥接会话可续，见 SessionHistoryProvider KDoc）
            val persistedHistory = SqliteChatHistoryProvider.forFile(
                dbPath = java.io.File(FileUtils.getDbDir(), "toolbelt.db").absolutePath,
            )
            // 本地 Koog 调度 agent：唯一实例（端点快照装配期刷一次；CLI 转发经 Bridge.cliAgentRunnerOrNull）。
            val koogRunner = KoogAgentRunner(
                persistedHistoryProvider = object : PersistedHistory {
                    override suspend fun store(conversationId: String, messages: List<Message>) =
                        persistedHistory.store(conversationId, messages)

                    override suspend fun load(conversationId: String): List<Message> =
                        persistedHistory.load(conversationId)

                    override suspend fun deleteHistory(conversationId: String): Boolean {
                        val had = persistedHistory.load(conversationId).isNotEmpty()
                        persistedHistory.deleteHistory(conversationId)
                        return had
                    }
                },
                // 步数预算配置化：run 前现读桥接 HOT 配置（agent.maxIterations）——
                // 设置页改值下一次 run 生效，无需重启桥接。未装配时回落默认配置（60）。
                maxIterationsProvider = { (bridgeRef.get()?.config ?: DefaultBridgeConfig.default()).agent.maxIterations },
                // 交付沙箱：send_file 的工作区放宽上界来源（与 `[产物]` 标记链**同组字段**——
                // workspace.root ∪ extraRoots）。
                // 空工作区 ⇒ null（不设上界 / 回落精确允许集判据）。保留 takeUnless 是为语义清晰：
                //    空 policy 与 null 在判据上等价（都让 insideSandbox 恒 false），但显式 null 更易读。
                // ⚠️ 生效时刻：run 前现读，**保存即生效（不等重启）**——与 workspace.root/extraRoots
                //    自身的 ApplyMode.RESTART 语义有意不一致（后者要重启才反映到工作区配置面）。
                sandboxProvider = {
                    sandboxPolicyOf((bridgeRef.get()?.config ?: DefaultBridgeConfig.default()).workspace)
                },
                // send_file 的两个前置闸输入——
                // ① 产物回传总开关（关掉即不许任何文件出站，send_file 不得成为旁路）；
                // ② 宿主机密根（敏感文件判据 1：`~/.toolbelt` 整棵子树——
                //    既含 lark/bridge-config.json 明文 appSecret，也含 .datastore 里的明文 LLM API Key）。
                artifactsEnabledProvider = {
                    (bridgeRef.get()?.config ?: DefaultBridgeConfig.default()).agent.artifacts.enabled
                },
                homeDataRootProvider = { FileUtils.getAppDataDir() },
                // 桥接配置源（nativeConfig：sys.*/dev.*/ws.*/self.status 的注册与工作区/超时钳制
                // 共用；HOT 现读）。null = 未装配（CLI 形态/测试）⇒ 依赖它的域不注册；
                // L2 五件与它无关，只判 payload.nativeSurface 在位（见 BridgeToolRegistry.build）。
                nativeToolsProvider = { bridgeRef.get()?.config },
                // 会话内提醒 job 面：App 形态恒注入（网关 scope 绑定）；
                // CLI 形态不经本装配 ⇒ provider 缺席 ⇒ remind_user 不注册。
                reminderSchedulerProvider = { reminderScheduler },
                // sys.* / dev.* / ws.* 三域的统一授权判据：信任用户名单（HOT 现读）。
                // 名单外用户进不了这三个域；名单内用户免会话总授权卡，但写/执行/系统修复
                // 仍有各自逐动作确认卡（信任免的是域准入，不是每次动作批准）。
                trustedUsersProvider = {
                    (bridgeRef.get()?.config ?: DefaultBridgeConfig.default()).access.trustedUsers
                },
                // 设备源 = 设备检测单源适配（每次调用新检测——serial 白名单恒校验当前列表）。
                devDeviceSource = AppDeviceSource,
                // self.status 自观测域接线：状态源 = 桥接内部态适配（状态页同数据面）。
                selfStatusSource = AppSelfStatusSource,
                // user.ask_user 挂起配对总线：Bridge 稳定件透出（runner 构造早于 Bridge 装配，
                // 恒 provider 形态每次 run 现取）。未装配（null）⇒ ask_user 不注册（fail-closed）。
                askUserBusProvider = { bridgeRef.get()?.askUserBus },
                // 端点就绪订阅流的发射口接线：快照单写点（refreshEndpoint）回调 →
                // [koogEndpointReadySink]，驾驶舱第 0 步/缺端点提示条据此即时重组。
                onEndpointReadyChanged = { ready -> koogEndpointReadySink.value = ready },
            )
            runCatching { kotlinx.coroutines.runBlocking { koogRunner.refreshEndpoint() } }
                .onFailure { JLog.w(TAG) { "Koog 端点快照初始化失败（不影响装配，run 前会重刷）：${it.message}" } }
            // 宿主私钥种子刷（assemble 前种好，回落缝拿首帧；
            // 与 koog 种子刷同款 runBlocking 形态——DataStore 是 suspend 读）。
            runCatching { kotlinx.coroutines.runBlocking { hostXlogKey.seed() } }
                .onFailure { JLog.w(TAG) { "宿主 xlog 私钥初始化失败（不影响装配，变更流 collect 会再刷）：${it.message}" } }
            val assembled = BridgeAssembler.assemble(
                dataRoot = dataRoot,
                // 🔴 工厂惰性求值：调用点在 Bridge.start() 内，此时 bridgeRef 已发布，可读到磁盘加载后的真实配置
                //    （急切求值会拿到装配前的默认配置，网关连错凭据）
                gatewayFactory = { buildGateway(bridgeRef.get()?.config ?: DefaultBridgeConfig.default(), scope) },
                defaultConfig = DefaultBridgeConfig.default(),
                parentJob = null, // App 侧独立根：退出由 onAppExit() 显式收敛
                consoleLog = false, // 桌面端不经 stdout 回显，日志走 logBus（UI 日志台）
                // delegate 经缝注入 JLog 适配器（lib 不 import JLog）——重连/队列丢弃/
                //    回复失败等事件须双写 app.log 留凭据，勿回落 Silent。
                logDelegate = JLogBridgeDelegate,
                // agent 执行器注入：装配即注入调度 agent（Koog 恒在位，agent 恒为硬依赖）——
                // 未命中 TEXT 恒归调度 agent，不可用时产出显式报错卡（DispatchAgentHandler 内闸）。
                agentRunnerOverride = koogRunner,
                // 未命中兜底 seam：注入非 null 即激活判定闭包；宿主返回 null = 全权交给装配点的
                // 调度 handler 兜底（不存在「未命中静默/固定回复」回落形态）。
                fallbackHandlerProvider = { null },
                // 宿主回落缝（桥接文档私钥留空时富化为
                // XLog 解密页私钥；同步读缓存，满足 lib 侧非挂起闭包契约）。
                xlogKeyFallback = { hostXlogKey.keyRef.get() },
                // 宿主语音转写缝：AUDIO 入站消息的转写闭包。feature 不得跨页引用
                // page.voice（feature 隔离）——转写能力经 :core 中立槽（VoiceTranscriptionHub，
                // ReportDelivery 同款注册契约）由宿主装配根注入；槽未接线（null）⇒ 桥接侧
                // AUDIO 走既有降级文案，行为不炸。
                audioTranscriber = voiceTranscriberSeamOf(),
                // 敏感文件排除表：宿主机密根显式上提一级到**应用数据根**
                // （`~/.easy_dev_toolbox`，非 `lark/` 子目录）——`.datastore/` 里存着明文
                // LLM API Key 与 xlog 私钥，只防 `lark/` 会在同级目录留缺口。
                sensitiveRoot = FileUtils.getAppDataDir().toPath(),
                // 启动自检推送接线：缝 = core 探查器适配（固定 argv 子集 + sys-audit 审计）；
                // 恒启用（agent.startupProbe 开关已随 v13→v14 退役），仅由本缝在位 +
                // connection.testChatId 非空决定是否发送。
                startupProbe = AppStartupProbe.seam,
            )
            bridgeRef.set(assembled)
            gatewayScopeRef.set(scope)
            koogRunnerRef.set(koogRunner)
            // 私钥事件化：装配点挂网关作用域 collect 变更流（shutdown 取消 gatewayScope 即收口）
            //——私钥保存即发射即写穿回落缓存，桥接停着改私钥也即时生效。
            hostXlogKey.watchIn(scope)
            JLog.i(TAG) { "桥接门面已装配：dataRoot=$dataRoot" }
            // 🔴 状态订阅跟随**装配原子点**挂载：startAll 在首帧前跑而装配由页面 VM 按需触发，
            //    订阅挂 start() 时 bridgeRef 恒 null ⇒ null?.collect 静默跳过 ⇒ 活动流永不更新
            //    （侧栏圆点不显示）。装配是唯一发布点，订阅必须在此挂。
            appScopeRef.get()?.let { hostScope -> attachHostLoops(hostScope, assembled, koogRunner) }
            return assembled
        }
    }

    /** AppHost 注入的 App 级 scope（[start] 时记录；装配与启动两种时序经 [appScopeRef] 汇合）。 */
    private val appScopeRef = AtomicReference<CoroutineScope?>(null)

    /** [attachHostLoops] 幂等闸：两组回路每次装配周期至多挂一次（防御双时序交叠重复订阅）。 */
    private val loopsAttached = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun attachHostLoops(hostScope: CoroutineScope, bridge: Bridge, runner: KoogAgentRunner) {
        if (!loopsAttached.compareAndSet(false, true)) return // 已挂（装配/start 时序交叠）⇒ 幂等跳过
        hostScope.launch {
            bridge.status.collect { snapshot ->
                bridgeActivitySink.value =
                    if (snapshot.running) NavActivity.Running else NavActivity.Idle
                // 报告外发通道就绪态单写点（ReportDelivery.ready 真源——置灰判据）
                ReportDelivery.publishReady(snapshot.running)
                // self.status 自观测域派生量喂入（重连计数/最近异常时刻——O(1) CAS 恒不
                // 挂起；门控关闭也持续喂，计数随采集挂载起算才有进程生命周期语义）
                AppSelfStatusSource.observe(snapshot)
            }
        }
        hostScope.launch {
            var idleTicks = 0
            while (isActive) {
                delay(KOOG_ENDPOINT_REFRESH_MILLIS)
                // 桥接运行态自判分档：1Hz 刷新的消费方都在运行期（消息分发读端点态），
                //    运行期保持 1Hz 热更；未运行态每 N 拍才做一次 DataStore 读。
                // 未运行态不得整条停摆：页面就绪态显示（aiEndpointReady）读的就是本回路
                //    维护的快照——停摆会让「刚配好端点」在页面上永不自愈（启动预检与环境
                //    预检读前已各自经 refreshKoogEndpointSnapshot 按需现刷，不依赖本回路）。
                // 本回路职责收敛为端点快照单一职责：宿主私钥改由 [HostXlogKeyCache]
                //    的变更流 collect 事件化维护（装配点挂网关作用域），不再每拍并列拉取。
                if (!bridge.isRunning && idleTicks < KOOG_ENDPOINT_IDLE_REFRESH_TICKS) {
                    idleTicks++
                    continue
                }
                idleTicks = 0
                runCatching { runner.refreshEndpoint() }
                    .onFailure { JLog.w(TAG) { "Koog 端点刷新失败（已忽略）：${it.message}" } }
            }
        }
    }

    override suspend fun start(scope: CoroutineScope) {
        appScopeRef.set(scope)
        // 报告外发通道接线（App 生命周期单点）：实现 = [deliverReport]（本文件胶水），
        // 就绪态经 attachHostLoops 的状态收集回路随 publishReady 发布（wire 一次即可——
        // 装配周期重建不换通道，deliverReport 现读 bridgeRef）。
        ReportDelivery.wire { file -> deliverReport(file) }
        // 已装配（getOrCreate 先于 startAll 的时序）⇒ 立即挂两组回路；
        // 未装配（主流程时序）⇒ 由 getOrCreate() 装配完成时挂（见其尾部 appScopeRef 分支）。
        val bridge = bridgeRef.get()
        val runner = koogRunnerRef.get()
        if (bridge != null && runner != null) {
            attachHostLoops(scope, bridge, runner)
        }
    }

    /** 能力四态上报：GATEWAY（连接状态）。 */
    override fun capabilities(): Map<CapabilityId, CapabilityState> {
        val snapshot = bridgeRef.get()?.status?.value
        val gatewayState = when {
            snapshot == null -> CapabilityState.Unavailable("未装配")
            snapshot.serving -> CapabilityState.Verified("桥接在跑")
            snapshot.needsAttention -> CapabilityState.Degraded("连接需关注（重连/阻断）", sinceMillis = 0L)
            else -> CapabilityState.Unavailable("未启动")
        }
        return mapOf(CapabilityId.GATEWAY to gatewayState)
    }

    suspend fun start(): BridgeStartResult = getOrCreate().start()

    /** 优雅停止（`suspend`；未装配或未运行时为空操作）。 */
    suspend fun stop() {
        bridgeRef.get()?.stop(Bridge.GRACE_MILLIS)
    }

    fun requestStop() {
        val scope = appScopeRef.get() ?: return
        scope.launch { stop() }
    }

    suspend fun applyConfig(next: BridgeConfig, persist: Boolean = true): ConfigApplyResult? =
        bridgeRef.get()?.applyConfig(next, persist)

    suspend fun sendTest(chatId: String, text: String): SendResult? =
        bridgeRef.get()?.sendTest(chatId, text)

    /**
     * 报告外发胶水（[ReportDelivery] 中立槽的桥接域实现；wire 见 [start]）：
     * 前置判桥接运行态与接收会话配置，出站校验与传输全收口 lib 门面
     * [Bridge.sendFile]（出站排除表在 lib 侧与 `send_file` 工具同一张表，本层不复制判据）。
     * 接收会话 = `connection.testChatId`（桥接域唯一既有的直发会话配置，
     * 与「发送测试消息」/授权验证探测同源）。
     *
     * @return null = 已送出；非 null = 失败人话（消费面直接展示）
     */
    internal suspend fun deliverReport(file: java.io.File): String? {
        val bridge = bridgeRef.get() ?: return BRIDGE_NOT_RUNNING_REASON
        if (!bridge.isRunning) return BRIDGE_NOT_RUNNING_REASON
        val chatId = bridge.config.connection.testChatId
        if (chatId.isBlank()) {
            return "未配置接收会话：请先在飞书桥接设置填写「测试 Chat ID」"
        }
        return when (val outcome = bridge.sendFile(chatId, file.toPath())) {
            null -> BRIDGE_NOT_RUNNING_REASON
            is FileSendOutcome.Sent -> null
            FileSendOutcome.InvalidInput -> "报告产物文件不存在：${file.path}"
            FileSendOutcome.Denied -> "该文件命中出站排除表，不允许外发：${file.name}"
            is FileSendOutcome.Failed ->
                "发送失败：${outcome.larkMsg ?: outcome.error?.name ?: "未知错误"}"
        }
    }

    /**
     * 预检点按需刷新 Koog 端点快照（单次 DataStore 读；suspend）。
     *
     * 消费面：启动预检（onStart 端点硬拒绝门）与环境预检（onProbe）在读
     * [probeEnvironment] 的 `agentAvailable` 前调用——该判据读的是本文件分档刷新回路维护的
     * 快照缓存，未运行态每 [KOOG_ENDPOINT_IDLE_REFRESH_TICKS] 拍才刷一次；预检前现刷一次即把
     * 「桥接停着、AI 设置页改端点后立即启动」的陈旧快照误拒窗口归零。
     * 未装配（[koogRunnerRef] 为 null）时 no-op——预检判据 [probeEnvironment] 本就恒 null。
     * 失败只 warn 不抛：沿用缓存快照判（与刷新回路同款容错语义）。
     */
    suspend fun refreshKoogEndpointSnapshot() {
        val runner = koogRunnerRef.get() ?: return
        runCatching { runner.refreshEndpoint() }
            .onFailure { JLog.w(TAG) { "Koog 端点预检刷新失败（已忽略，沿用缓存快照）：${it.message}" } }
    }

    /** 环境预检。转发门面 [Bridge.probeEnvironment]；未装配时返回 null（零网络）。 */
    fun probeEnvironment(): EnvironmentProbe? = bridgeRef.get()?.probeEnvironment()

    suspend fun probeNativeGrant(
        grant: com.jiaoay.lark.bridge.config.NativeToolGrant,
        testChatId: String,
        lookupOpenId: String,
        defaultFailText: String,
        probeDocUrl: String = "",
    ): String? = bridgeRef.get()?.probeNativeGrant(grant, testChatId, lookupOpenId, defaultFailText, probeDocUrl = probeDocUrl)

    fun pendingConfirmations(): List<com.jiaoay.lark.bridge.confirm.PendingConfirmView> =
        bridgeRef.get()?.pendingConfirmations().orEmpty()

    suspend fun listMessageReactions(messageId: String): List<com.jiaoay.lark.gateway.ReactionBrief>? =
        bridgeRef.get()?.listMessageReactions(messageId)

    suspend fun messageReadCount(messageId: String): Int? =
        bridgeRef.get()?.messageReadCount(messageId)

    suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): com.jiaoay.lark.bridge.RegisterAppResult {
        if (bridgeRef.get() == null) getOrCreate()
        return requireNotNull(bridgeRef.get()).registerApp(onQr)
    }

    override fun shutdown() {
        val current = bridgeRef.getAndSet(null) ?: return
        runCatching { current.onAppExit() }
            .onFailure { JLog.e(TAG, it) { "桥接退出清理失败" } }
        runCatching { gatewayScopeRef.getAndSet(null)?.cancel() }
            .onFailure { JLog.e(TAG, it) { "网关作用域取消失败" } }
        koogRunnerRef.set(null)
        hostXlogKey.keyRef.set("") // 回落缓存随周期清空（secret 不驻留；collect 随 gatewayScope 取消收口）
        koogEndpointReadySink.value = false // 就绪态随周期归零（与未装配同口径）
        ReportDelivery.publishReady(false) // 报告外发就绪态随周期归零（与未装配同口径）
        appScopeRef.set(null)
        loopsAttached.set(false)
        bridgeActivitySink.value = NavActivity.Idle // 释放 ⇒ 活动归零
        JLog.i(TAG) { "桥接门面已释放" }
    }

    /** 旧退出入口名保留（宿主调用面零改动；与 [shutdown] 同体）。 */
    fun onAppExit() = shutdown()

    private fun buildGateway(config: BridgeConfig, scope: CoroutineScope): MessageGateway =
        BridgeGateways.resolve(config, scope)

    /**
     * 桥接转写缝装配：读 :core 中立槽端口（宿主装配根已把 feature-voice 内核适配注入），
     * 映射成桥接内核的转写缝契约。槽未接线（null）⇒ 返回 null——装配点对 AUDIO 不构造
     * AudioHandler，消息走既有降级文案（能力缺席不炸主链路）。
     * 装配时点读一次（端口是 App 级单例，装配根恒先接线后装配）。
     */
    private fun voiceTranscriberSeamOf(): AudioTranscriberSeam? {
        val port = VoiceTranscriptionHub.port() ?: return null
        return AudioTranscriberSeam { audio ->
            when (val result = port.transcribe(audio)) {
                is VoiceTranscriptionHub.Result.Success ->
                    AudioTranscriptionOutcome.Success(result.text)
                is VoiceTranscriptionHub.Result.Unavailable ->
                    AudioTranscriptionOutcome.Unavailable(result.guidance)
                is VoiceTranscriptionHub.Result.Failed ->
                    AudioTranscriptionOutcome.Failed(result.reason)
            }
        }
    }

    internal fun sandboxPolicyOf(
        workspace: com.jiaoay.lark.bridge.config.WorkspaceConfig,
    ): com.jiaoay.lark.bridge.exec.WorkspacePolicy? =
        com.jiaoay.lark.bridge.exec.WorkspacePolicy
            .fromConfig(
                workspaceDir = workspace.root,
                workspaceWhitelist = workspace.extraRoots,
            )
            .takeUnless { it.isEmpty }
}
