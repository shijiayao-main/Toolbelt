package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.lark.bridge.BridgeStartResult
import com.jiaoay.lark.bridge.ConfigApplyResult
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.lark.bridge.observe.EnvironmentProbe
import com.jiaoay.lark.bridge.observe.LogBus
import com.jiaoay.lark.gateway.SendResult
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel 与宿主门面之间的**窄接口**。
 *
 * 引入本接口的唯一目的是让 [com.jiaoay.easy_dev_toolbox.page.larkbridge.LarkBridgeViewModel] 可被单测
 * 以内存 fake 替换，从而在不触碰真实文件系统（`~/.easy_dev_toolbox/lark/`）的前提下验证
 * 「draft 即时更新 → 400ms trailing debounce → Mutex 单写者 → 校验失败不覆写」的写入路径。
 *
 * 本接口不修改 [LarkBridgeManager]：真实实现 [LarkBridgeStore] 只做转发。
 */
interface BridgeStore {

    /** 桥接数据根（配置目录 / 导入导出的默认锚点）。 */
    val dataRoot: Path

    /** 当前生效配置（未装配时回退默认配置，便于装配前渲染表单）。 */
    val config: BridgeConfig

    /** 是否已装配（装配 ≠ 在跑）。 */
    val isAssembled: Boolean

    /** 桥接整体状态快照（1Hz 采样；未装配时为 null）。 */
    val status: StateFlow<BridgeStatusSnapshot>?

    /** 环形日志台（未装配 / `logConsole.enabled=false` 时为 null）。 */
    val logs: LogBus?

    /** 应用新配置（`suspend`；HOT 无锁热替换，含 RESTART 字段返回 [ConfigApplyResult.RestartRequired]）。 */
    suspend fun applyConfig(next: BridgeConfig, persist: Boolean): ConfigApplyResult?

    /** 启动桥接（`suspend`；恒不抛，失败归一为 [BridgeStartResult.Refused]）。 */
    suspend fun start(): BridgeStartResult

    /** 优雅停止（`suspend`）。 */
    suspend fun stop()

    /**
     * 旧 DataStore 35 key 一次性导入钩子——在 `BridgeAssembler.assemble()` 与首次落盘之前触发。
     * 默认 no-op（供 [com.jiaoay.easy_dev_toolbox.page.larkbridge.LarkBridgeViewModelTest] 的内存 fake 继承，
     * 使 VM 单测零文件系统副作用、不触碰真实 `~/.easy_dev_toolbox`）；生产实现见 [LarkBridgeStore]。
     * 幂等：已迁移 / 无旧数据 / `bridge-config.json` 已存在时零副作用。
     */
    suspend fun ensureLegacyMigrated() {}

    /**
     * 🔴 确保门面已装配后再读 [config]。
     * 否则：`config` getter 在**未装配时回退全默认**（appId=""），而 `ensureLegacyMigrated` 只在
     * Migrated 分支装配——配置文件已存在（老用户常态）走 Skip 分支不装配 ⇒ VM draft 以**空默认**起步
     * ⇒ 用户在空表单上任意编辑经 debounce 落盘，把「默认值+局部编辑」整份覆写磁盘配置（凭据被抹，
     * 下次 start 即「appId 为空」）。本钩子让 draft 初值恒为磁盘真值；装配是零网络/零磁盘写的同步构造。
     * 默认 no-op（内存 fake 继承——fake 的 [config] 即其构造入参，无装配概念）。
     */
    fun ensureAssembled() {}

    /**
     * `connection.autoStart` 接线——迁移完成后，若 autoStart==true 且未运行，则在协程里触发一次 [start]。
     * 默认 no-op（内存 fake 继承）；生产实现见 [LarkBridgeStore]（AtomicBoolean 幂等闸门，进程内只触发一次）。
     * 必须在 [ensureLegacyMigrated] 之后调用（迁移可能刚把 autoStart 从旧配置搬进来）。
     */
    suspend fun ensureAutoStarted() {}

    /**
     * 发测试消息。默认返回 null（供内存 fake 继承，零网络副作用）；
     * 生产实现见 [LarkBridgeStore]，转发门面 [com.jiaoay.lark.bridge.Bridge.sendTest]。
     */
    suspend fun sendTest(chatId: String, text: String): SendResult? = null

    /**
     * 环境预检。默认返回 null（内存 fake）；生产实现见 [LarkBridgeStore]，
     * 转发门面 [com.jiaoay.lark.bridge.Bridge.probeEnvironment]（零网络调用）。
     */
    fun probeEnvironment(): EnvironmentProbe? = null

    /**
     * 预检点按需刷新本地 AI 端点快照（单次 DataStore 读）。
     *
     * 启动预检与环境预检在读 [probeEnvironment] 的 `agentAvailable` 前调用——该判据读的是
     * 分档刷新回路维护的快照缓存，未运行态慢刷存在陈旧窗口；读前现刷一次即归零。
     * 默认 no-op（内存 fake 继承，零 DataStore 副作用）；生产实现见 [LarkBridgeStore]。
     * 未装配时 no-op（快照无消费面）。
     */
    suspend fun refreshEndpointSnapshot() {}

    /**
     * 本地 AI 端点就绪态订阅流（驾驶舱快速开始第 0 步与缺端点提示条的数据源）。
     *
     * 订阅式：发布点是端点快照的单点刷新（装配期种子刷 / 分档刷新回路 / [refreshEndpointSnapshot]
     * 按需现刷共用同一发射口），端点配置变更随下一次快照刷新即时反映——消费方 collect，
     * 组合期零挂起读、零轮询。与 [probeEnvironment] 的 `agentAvailable` 同源（同一快照缓存），
     * 但后者是读时快照、前者是变更即达。
     * 未装配 ⇒ false（惰性装配语义下「未装配 ≈ 端点未验证」，保守方向）。
     * 默认恒 false（内存 fake 继承，零副作用；需要可编程翻转的测试覆写本成员）。
     */
    val aiEndpointReady: StateFlow<Boolean>
        get() = endpointNotReadyFlow

    companion object {
        /** 默认订阅流的共享常源（恒 false；fake 不覆写时恒未就绪，与未装配同口径）。 */
        private val endpointNotReadyFlow: StateFlow<Boolean> = MutableStateFlow(false)
    }

    /**
     * 扫码建应用（[com.jiaoay.lark.bridge.Bridge.registerApp] 门面透传）。
     * 默认返回 null（内存 fake 继承，零网络副作用）；生产实现见 [LarkBridgeStore]。
     * 🔴 阻塞最长约 600s 且不可中断（SDK 无 cancel 钩子）——调用方须在独立协程里调用
     * （在 UI/EDT 线程直接调用会冻结界面至超时）。
     */
    suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): com.jiaoay.lark.bridge.RegisterAppResult? = null

    /**
     * L2 授权验证探测——**经运行中网关**的只读探测窄口
     * （[com.jiaoay.lark.bridge.Bridge.probeNativeGrant] 门面透传）。
     *
     * 🔴 为什么必须经运行中网关：另造一次性网关并 connect 会——
     *    `BridgeGateways.sdk()` 恒注入 WsClientTransportFactory ⇒ connect 会开**第二条 :lark-ws
     *    入站长连接**，触发「同 appId 双长连接被服务端互踢」——桥接运行中
     *    点「验证」会把自己踢下线。探测动作必须收口 lib 门面（与 [sendTest] 同形态），
     *    本接口不暴露 gateway 实例。
     *
     * @return null = 探测通过；非 null = 失败人话；桥接未运行/未装配时返回「桥接未运行」人话
     *   （lib 门面语义）——调用方（VM）前置闸已挡（未运行时按钮置灰），此处为防御双保险。
     */
    suspend fun probeNativeGrant(
        grant: com.jiaoay.lark.bridge.config.NativeToolGrant,
        testChatId: String,
        lookupOpenId: String,
        defaultFailText: String,
        probeDocUrl: String = "",
    ): String? = null

    /**
     * 进行中确认只读视图——[com.jiaoay.lark.bridge.Bridge.pendingConfirmations]
     * 门面透传（纯内存快照，零网络零挂起，恒经运行中实例）。
     * 默认返回空表（内存 fake 继承，零副作用）；生产实现见 [LarkBridgeStore]。
     */
    fun pendingConfirmations(): List<com.jiaoay.lark.bridge.confirm.PendingConfirmView> = emptyList()

    /**
     * 消息表情回应——[com.jiaoay.lark.bridge.Bridge.listMessageReactions]
     * 门面透传（经运行中网关只读，不另造网关——互踢纪律见 [probeNativeGrant] KDoc）。
     * 默认返回 null（内存 fake 继承，零网络副作用）；null = 能力缺席，调用方不渲染徽标。
     */
    suspend fun listMessageReactions(messageId: String): List<com.jiaoay.lark.gateway.ReactionBrief>? = null

    /**
     * 消息已读人数——[com.jiaoay.lark.bridge.Bridge.messageReadCount]
     * 门面透传（经运行中网关只读 + 有界翻页聚合，同一互踢纪律）。
     * 默认返回 null（内存 fake 继承，零网络副作用）；null = 能力缺席，UI 显示「已读 —」。
     */
    suspend fun messageReadCount(messageId: String): Int? = null

    /**
     * 把任意落盘文件按「配置文件同等」收紧为 POSIX 600（导出通道专用）。
     *
     * 为什么必须走 store 而不是调用方自写：`BridgeConfig.appSecret` 是**明文字段**，
     * 导出/导入的文件与 `bridge-config.json` 同属密钥文件族。主配置走
     * `ConfigRepository.writeAtomic`（临时件 → fsync → ATOMIC_MOVE → .bak → 600）；
     * 导出路径若自写 `writeText` 则整条硬化链被旁路，文件按进程 umask 落盘。
     *
     * 默认 no-op（内存 fake 继承，零文件系统副作用）；生产实现见 [LarkBridgeStore]，
     * 委托 [com.jiaoay.lark.bridge.config.ConfigRepository] 的 `setPosix600` 单源
     * （非 POSIX 平台跳过并留痕，口径与主配置一致）。
     */
    fun hardenSecretFile(file: java.io.File) {}
}

/**
 * [BridgeStore] 的真实实现：直接转发 [LarkBridgeManager]。
 *
 * [applyConfig] 在**未装配**时先 [LarkBridgeManager.getOrCreate]（零网络连接、零磁盘写）再转发，
 * 使编辑器首次落盘可用；装配只在真正有编辑发生时触发，故「仅打开页面」不会生成 `bridge-config.json`。
 */
object LarkBridgeStore : BridgeStore {

    /**
     * 进程内一次性迁移闸门：避免同一会话内 VM 重建（导航来回）反复触发读盘。
     * 迁移失败时复位（见 [ensureLegacyMigrated]），以便下次触发重试；成功/跳过时保持置位。
     */
    private val migrationGuard = AtomicBoolean(false)

    /**
     * 进程内一次性自动启动闸门——只在装配那一次触发，不在每次进入桥接页时重复触发
     * （否则用户每次切页都会重连飞书）。与 [migrationGuard] 同款 AtomicBoolean 幂等模式。
     */
    private val autoStartGuard = AtomicBoolean(false)

    override val dataRoot: Path
        get() = LarkBridgeManager.dataRoot

    override val config: BridgeConfig
        get() = LarkBridgeManager.config

    override val isAssembled: Boolean
        get() = LarkBridgeManager.isAssembled

    override val status: StateFlow<BridgeStatusSnapshot>?
        get() = LarkBridgeManager.status

    override val logs: LogBus?
        get() = LarkBridgeManager.logs

    override suspend fun applyConfig(next: BridgeConfig, persist: Boolean): ConfigApplyResult? {
        if (!LarkBridgeManager.isAssembled) LarkBridgeManager.getOrCreate()
        return LarkBridgeManager.applyConfig(next, persist)
    }

    override suspend fun start(): BridgeStartResult = LarkBridgeManager.start()

    override suspend fun stop() {
        LarkBridgeManager.stop()
    }

    override suspend fun sendTest(chatId: String, text: String): SendResult? =
        LarkBridgeManager.sendTest(chatId, text)

    override fun probeEnvironment(): EnvironmentProbe? = LarkBridgeManager.probeEnvironment()

    override suspend fun refreshEndpointSnapshot() = LarkBridgeManager.refreshKoogEndpointSnapshot()

    override val aiEndpointReady: StateFlow<Boolean>
        get() = LarkBridgeManager.koogEndpointReady

    override suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): com.jiaoay.lark.bridge.RegisterAppResult =
        LarkBridgeManager.registerApp(onQr)

    override suspend fun probeNativeGrant(
        grant: com.jiaoay.lark.bridge.config.NativeToolGrant,
        testChatId: String,
        lookupOpenId: String,
        defaultFailText: String,
        probeDocUrl: String,
    ): String? = LarkBridgeManager.probeNativeGrant(grant, testChatId, lookupOpenId, defaultFailText, probeDocUrl = probeDocUrl)

    override fun pendingConfirmations(): List<com.jiaoay.lark.bridge.confirm.PendingConfirmView> =
        LarkBridgeManager.pendingConfirmations()

    override suspend fun listMessageReactions(messageId: String): List<com.jiaoay.lark.gateway.ReactionBrief>? =
        LarkBridgeManager.listMessageReactions(messageId)

    override suspend fun messageReadCount(messageId: String): Int? =
        LarkBridgeManager.messageReadCount(messageId)

    /** 生产实现：委托 lib 侧硬化单源（与 `bridge-config.json` 主写路径同一实现）。 */
    override fun hardenSecretFile(file: java.io.File) {
        com.jiaoay.lark.bridge.config.SecretFilePermissions.harden600(file)
    }

    /**
     * 迁移生产实现：读进程唯一 [AppDataStore.store] 的旧 35 key，经 [LegacyConfigImporter] 一次性导入到
     * `<dataRoot>/bridge-config.json`（🔴 先于 [LarkBridgeManager.getOrCreate] 的装配与首次落盘——
     * 晚于装配则 draft 以默认值起步，首次落盘即整份覆写抹掉旧凭据）。
     *
     * 迁移成功后**立即装配门面**，使 [config] 反映迁移后的磁盘文档——否则 VM 仍以默认值初始化 draft，
     * 用户首次编辑会用默认值覆写掉刚迁入的配置。失败时复位闸门（不置标记，[LegacyConfigImporter] 内部保证
     * 不写文档），下次触发自动重试。
     */
    override suspend fun ensureLegacyMigrated() {
        if (!migrationGuard.compareAndSet(false, true)) return
        val outcome = LegacyConfigImporter.importOnce(
            store = AppDataStore.store,
            dataRoot = dataRoot,
            log = { msg -> JLog.i("LegacyConfigImporter") { msg } },
        )
        when (outcome) {
            is LegacyImportOutcome.Migrated -> LarkBridgeManager.getOrCreate()
            is LegacyImportOutcome.Failed -> migrationGuard.set(false)
            // 🔴 Skip / NoLegacyData 也装配——配置文件已存在（老用户常态）
            //    若不装配，[LarkBridgeManager.config] 回退全默认，VM draft 以空配置起步，
            //    首次编辑即把「默认值+局部编辑」整份覆写磁盘（凭据被抹）。装配零网络零磁盘写。
            else -> LarkBridgeManager.getOrCreate()
        }
    }

    /** 生产实现——见接口 KDoc。 */
    override fun ensureAssembled() {
        LarkBridgeManager.getOrCreate()
    }

    /**
     * `connection.autoStart` 接线（生产实现）。
     *
     * 语义：桥接门面装配完成后，若 `connection.autoStart == true` 且当前未运行，则在协程里调用 [start]。
     * 只在装配那一次触发（[autoStartGuard] 幂等闸门），不在每次进入桥接页时重复触发。
     * 必须在 [ensureLegacyMigrated] 之后调用（迁移可能刚把 autoStart 从旧配置搬进来）。
     * 🔴 [LarkBridgeManager.start] 是 suspend（内部是 gateway.connect() 挂起网络调用），
     *    只能在协程里调，绝不得在 EDT 上 runBlocking（否则冻结界面直至建连完成）。
     */
    override suspend fun ensureAutoStarted() {
        if (!autoStartGuard.compareAndSet(false, true)) return
        // 读 config 前确保装配——未装配时读到的是全默认
        //    （autoStart=false），磁盘上 autoStart=true 也永远不会自动启动。
        LarkBridgeManager.getOrCreate()
        val cfg = LarkBridgeManager.config
        if (!cfg.connection.autoStart) return
        if (LarkBridgeManager.bridge?.isRunning == true) return
        JLog.i("LarkBridgeStore") { "autoStart=true，自动启动桥接…" }
        LarkBridgeManager.start()
    }
}
