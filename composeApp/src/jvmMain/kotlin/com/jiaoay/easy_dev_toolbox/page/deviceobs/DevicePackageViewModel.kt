package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.jiaoay.toolbelt.device.DeviceApkExportReceipt
import com.jiaoay.toolbelt.device.DeviceApkExporter
import com.jiaoay.toolbelt.device.DeviceFilePuller
import com.jiaoay.toolbelt.device.DevicePackageDetails
import com.jiaoay.toolbelt.device.DevicePackageEntry
import com.jiaoay.toolbelt.device.DevicePackageToggler
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.device.PerfSamplerState
import com.jiaoay.toolbelt.device.PerfSessionState
import com.jiaoay.toolbelt.device.ProcessEntry

/**
 * 系统过滤三态档：全部包 / 仅系统包（`pm list packages -s` 集合成员）/ 仅非系统包
 * （后装用户包）。档位语义对齐 [DevicePackageEntry.isSystem] 的集合差来源——
 * 全预装形态下「第三方」标记恒假，预装/后装判别只走系统标记。
 */
enum class DevicePackageSystemFilter(val label: String) {
    ALL("全部"),
    SYSTEM_ONLY("仅系统"),
    NON_SYSTEM("仅非系统"),
}

/**
 * 包列表过滤（internal 供单测）：查询词为空 = 不过滤；非空 = 包名**包含**匹配
 * （大小写不敏感——按厂商前缀找包是高频动作，`com.android`/`com.example`
 * 等前缀输入天然被包含语义覆盖，前缀命中是包含命中的子集）。null 列表（未装载）
 * 过滤为空列表。
 */
internal fun filterPackageEntries(
    entries: List<DevicePackageEntry>?,
    query: String,
    filter: DevicePackageSystemFilter,
): List<DevicePackageEntry> {
    if (entries == null) return emptyList()
    val q = query.trim().lowercase()
    return entries.filter { entry ->
        val matchesQuery = q.isEmpty() || entry.packageName.lowercase().contains(q)
        val matchesFilter = when (filter) {
            DevicePackageSystemFilter.ALL -> true
            DevicePackageSystemFilter.SYSTEM_ONLY -> entry.isSystem
            DevicePackageSystemFilter.NON_SYSTEM -> !entry.isSystem
        }
        matchesQuery && matchesFilter
    }
}

/**
 * 包运行态聚合读数（进程排行表投影到包名后的聚合面）：[cpuPercent] = 该包名下
 * 全部匹配进程 %CPU 之和（主进程与子进程各吃各的 CPU，求和才是应用总占用）；
 * [memKb] = 匹配进程 RSS 之和（单位 kB。求和口径裁决：主进程与子进程各占各的
 * 常驻内存，求和才是应用总占用面；RSS 按进程计含共享页，多进程求和会重复计入
 * 共享库页，故这是排序级上界读数非精确记账——与 [ProcessEntry.rssKb] 的
 * 「排序够用非精确认证」同口径，top 的 %MEM 列是一位小数舍入值，RSS 原值求和
 * 才是排序真源）；[processCount] = 匹配进程数（>1 即多进程应用——主进程 +
 * `包名:后缀` 子进程）。
 */
data class DevicePackageRuntime(
    val cpuPercent: Float,
    val memKb: Long,
    val processCount: Int,
)

/**
 * 包列表排序档：按包名 = pm 列举原序直通（既有默认零变更）；按 CPU / 按内存 =
 * 运行态读数降序（无读数的包沉底保原相对序）。档位形态与性能面板进程排行排序档
 * （PerfSortMode）同款。
 */
enum class DevicePackageSortMode(val label: String) {
    BY_NAME("按包名"),
    BY_CPU("按 CPU"),
    BY_MEMORY("按内存"),
}

/**
 * 进程名 → 包名匹配（纯函数，保守不猜口径）：仅两形态成立匹配——
 * - 精确匹配：top ARGS 的进程名即包名（应用主进程形态）；
 * - 子进程形态：`包名:后缀` 剥离首个 `:` 前段再精确比对（`com.foo:service` →
 *   `com.foo`）。
 * 其余一律不匹配：native 服务名（`surfaceflinger`）、内核线程名、大小写有差异
 * 的名字、前缀近似名（`com.foo.sub` vs `com.foo`）。进程名与包名是两个命名域，
 * 进程名 ≠ 包名是本质性近似——匹配不上就不投影，不显示错误徽标比不显示更糟。
 * 冒号在首位的私有进程名（`:service`）无名包可依，同样不匹配。
 */
internal fun processMatchesPackage(processName: String, packageName: String): Boolean {
    if (processName == packageName) return true
    val colonIdx = processName.indexOf(':')
    if (colonIdx <= 0) return false
    return processName.substring(0, colonIdx) == packageName
}

/**
 * 进程排行表 → 包运行态投影（纯函数）：只给匹配上已装包名的进程落读数，匹配
 * 不上的进程静默舍去（native/内核/大小写差异——见 [processMatchesPackage]）。
 * 返回 Map 的键集 ⊆ 入参包名集。
 */
internal fun projectPackageRuntime(
    processes: List<ProcessEntry>,
    packages: List<DevicePackageEntry>,
): Map<String, DevicePackageRuntime> {
    if (packages.isEmpty() || processes.isEmpty()) return emptyMap()
    val names = packages.mapTo(HashSet()) { it.packageName }
    val acc = HashMap<String, DevicePackageRuntime>()
    for (process in processes) {
        val target = if (process.name in names) {
            process.name
        } else {
            val base = process.name.substringBefore(':')
            if (base.isNotEmpty() && base.length != process.name.length && base in names) base else null
        } ?: continue
        val prev = acc[target]
        acc[target] = DevicePackageRuntime(
            cpuPercent = (prev?.cpuPercent ?: 0f) + process.cpuPercent,
            memKb = (prev?.memKb ?: 0L) + process.rssKb,
            processCount = (prev?.processCount ?: 0) + 1,
        )
    }
    return acc
}

/**
 * 包列表排序投影（纯函数）：按包名 = 原序直通；按 CPU / 按内存 = 运行态读数
 * 降序，无读数（未采样/未匹配/串号不匹配）的包沉底且保原相对序（稳定排序，
 * 并列不乱跳）。
 */
internal fun sortPackageEntries(
    entries: List<DevicePackageEntry>,
    runtime: Map<String, DevicePackageRuntime>,
    mode: DevicePackageSortMode,
): List<DevicePackageEntry> = when (mode) {
    DevicePackageSortMode.BY_NAME -> entries
    DevicePackageSortMode.BY_CPU -> entries.sortedByDescending { runtime[it.packageName]?.cpuPercent }
    DevicePackageSortMode.BY_MEMORY -> entries.sortedByDescending { runtime[it.packageName]?.memKb }
}

/**
 * 应用列表会话级快照：对比基线（保存时刻的装载列表 + 来源串号 + 时刻）。
 *
 * 契约：只存 VM 内存**不落盘**（持久化属后续裁决）；条目是保存时刻的全量
 * 装载面——查询/过滤/排序是视图投影不进快照，对比语义是全量 diff；串号是
 * 快照的组成元数据（跨串号对比 = 两台设备/两次刷机预装差异的场景本体，
 * 串号供对比前提示与回执标注，不阻断）；保存后列表的后续变更不回写快照
 * （快照是定格，重取列表须再存新快照顶替）。
 */
data class DevicePackageSnapshot(
    val serial: String,
    val entries: List<DevicePackageEntry>,
    val timestampMillis: Long,
)

/** 快照对比的版本变更条目：同包名两时刻 [DevicePackageEntry.versionCode] 不同（成对呈现）。 */
data class DevicePackageVersionChange(
    val packageName: String,
    val snapshotVersionCode: Long,
    val currentVersionCode: Long,
)

/**
 * 快照对比三分类结果（纯数据，呈现归面板）：[added] = 当前有快照无；
 * [removed] = 快照有当前无；[versionChanged] = 同包名 versionCode 不同
 * （uid/isSystem 差异单独出现不计差异——diff 键是包名 + versionCode）。
 * 全空三分类 = 无差异（合法结果非失败）。
 */
data class DevicePackageDiff(
    val added: List<DevicePackageEntry>,
    val removed: List<DevicePackageEntry>,
    val versionChanged: List<DevicePackageVersionChange>,
)

/**
 * 应用列表快照 diff（纯函数）：以包名键逐包比对两份列表得三分类，各类别按
 * 包名升序稳定排序——任意列举序的同一输入集得同一输出（列举序抖动不传染）。
 * 包名重复时后条覆盖前条（设备列举无重复，该语义只是键化的确定性兜底）。
 */
internal fun diffPackageEntries(
    snapshot: List<DevicePackageEntry>,
    current: List<DevicePackageEntry>,
): DevicePackageDiff {
    val snapshotByName = snapshot.associateBy(DevicePackageEntry::packageName)
    val currentByName = current.associateBy(DevicePackageEntry::packageName)
    val added = current.filter { it.packageName !in snapshotByName }.sortedBy { it.packageName }
    val removed = snapshot.filter { it.packageName !in currentByName }.sortedBy { it.packageName }
    val versionChanged = snapshot.mapNotNull { snap ->
        val cur = currentByName[snap.packageName]
        if (cur != null && cur.versionCode != snap.versionCode) {
            DevicePackageVersionChange(snap.packageName, snap.versionCode, cur.versionCode)
        } else {
            null
        }
    }.sortedBy { it.packageName }
    return DevicePackageDiff(added, removed, versionChanged)
}

/**
 * 单包详情动作 UI 状态（请求-回执模型，与包列表读数分立互不清零）。
 *
 * 契约：[Busy] = 详情读取在途（选中行展开卡内的进度位）；[Ready] 携带
 * [DevicePackageDetails] 最小字段面；[Failed] 人话回执原样呈现（内核侧已含
 * 缺席包/格式不符的显式失败语义，本层不翻译不吞）。
 */
sealed interface DevicePackageDetailsUiState {
    data object Idle : DevicePackageDetailsUiState
    data class Busy(val packageName: String) : DevicePackageDetailsUiState
    data class Ready(val details: DevicePackageDetails) : DevicePackageDetailsUiState
    data class Failed(val packageName: String, val message: String) : DevicePackageDetailsUiState
}

/**
 * APK 导出动作 UI 状态（请求-回执模型，与列表/详情读数分立互不清零）。
 *
 * 契约：[Busy] 覆盖「pm path 解析 + 逐 split 拉取」全程；[Busy.fileCount] == 0 是
 * pm path 解析期占位（首个 split 开始前无总数字面量，面板按「准备中」呈现）；
 * [Busy.expectedSizeBytes] 是导出开始时刻的设备侧 stat 快照（展示锚非校验锚，
 * 校验在拉取内核内部）；[Done] 携带逐 split 回执（路径/大小）；[Failed] 人话
 * 回执原样呈现（内核已含中断点与已完成文件不回滚说明）。
 */
sealed interface DevicePackageExportUiState {
    data object Idle : DevicePackageExportUiState
    data class Busy(
        val packageName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val fileName: String,
        val expectedSizeBytes: Long,
    ) : DevicePackageExportUiState

    data class Done(val packageName: String, val receipt: DeviceApkExportReceipt) : DevicePackageExportUiState
    data class Failed(val packageName: String, val message: String) : DevicePackageExportUiState
}

/**
 * 禁启动作 UI 状态（请求-回执模型）。禁启是改设备状态的高危动作，**确认分层
 * 在本层**（内核被调用即执行）：[DisableConfirmPending]/[EnableConfirmPending] =
 * 确认对话数据源（确认才执行，取消零动作零 spawn 回 Idle）；[Busy] 为执行期；
 * [Done] 人话回执落地后由 VM 触发详情刷新与已禁用态重探；[Failed] 人话原样。
 */
sealed interface DevicePackageToggleUiState {
    data object Idle : DevicePackageToggleUiState
    data class DisableConfirmPending(val packageName: String) : DevicePackageToggleUiState
    data class EnableConfirmPending(val packageName: String) : DevicePackageToggleUiState
    data class Busy(val packageName: String, val actionText: String) : DevicePackageToggleUiState
    data class Done(val packageName: String, val message: String) : DevicePackageToggleUiState
    data class Failed(val packageName: String, val message: String) : DevicePackageToggleUiState
}

/**
 * 快照对比动作 UI 状态（请求-回执模型）：[Idle] = 无对比回执；
 * [CrossSerialConfirmPending] = 跨串号对比确认对话数据源（快照串号 ≠ 当前
 * 列表串号——确认才对比，取消零动作）；[Result] 携带三分类结果 + 快照元数据
 * + 对比时刻的当前串号（回执是快照 ↔ 当时列表的静态产物，后续刷新/换设备
 * 不自动重算——再次对比是显式动作）。
 */
sealed interface DevicePackageDiffUiState {
    data object Idle : DevicePackageDiffUiState
    data class CrossSerialConfirmPending(val snapshotSerial: String, val currentSerial: String) : DevicePackageDiffUiState
    data class Result(
        val diff: DevicePackageDiff,
        val snapshot: DevicePackageSnapshot,
        val currentSerial: String,
    ) : DevicePackageDiffUiState
}

/**
 * 应用面板状态（请求-回执模型）：包列表只在用户动作或目标串号变更时请求，无轮询环
 * ——「设备装了新包」不被本 VM 感知，刷新是显式动作。
 *
 * 契约：
 * - [entries] null = 未装载（无目标设备或首次读取未完成）；空列表 = 设备零包
 *   （合法读数）；[filtered] 是 [entries] 按 [query]/[systemFilter] 的过滤投影，
 *   未装载时恒空列表（面板只消费 filtered）；
 * - [error] 为面向用户的失败回执（人话原样呈现），下一动作（刷新/换目标）开始
 *   即清零；
 * - [selected] = 当前展开详情卡的包名（null = 无展开）；选中包被过滤条件挤出
 *   [filtered] 时选中态保留（行不可见即卡不可见，清过滤即回看——不静默丢选中）；
 * - [details] 与列表读数分立：装载中选中仍可用（详情走独立通道），失败不清列表；
 * - [export]/[toggle] 是详情卡动作区状态（与列表/详情读数分立互不清零），动作
 *   回执随**选中包**走——换选中/收起/换设备时复位（回执是卡内语境产物，不跨
 *   选中态残留）；
 * - [selectedDisabled] = 选中包的当前禁用态（null = 未探测完成或探测失败——
 *   探测失败不出禁启钮，禁启钮按探测值显其一），选中变更后重探；
 * - [runtimeByPackage] = 进程排行投影的包运行态（key = 包名；空 Map = 无可投影
 *   运行态——未采样/串号不匹配/会话非活跃/进程域失败，徽标按缺席呈现不猜）；
 *   数据源 = 注入的采样器 StateFlow 最近一拍（只读消费，本 VM 不起采样不写采样器）；
 * - [sortMode] = 列表排序档（默认按包名 = 既有列举序零变更；「按 CPU」/「按内存」
 *   消费 [runtimeByPackage] 投影，无读数沉底保原序）；
 * - [snapshot] = 会话级对比基线（null = 未保存；不落盘，见
 *   [DevicePackageSnapshot] 头注释），跨串号保留（跨串号对比是场景本体）；
 * - [diff] = 快照对比回执（动作产物，与列表读数分立；新快照/换目标复位，
 *   关闭是显式动作）。
 */
data class DevicePackagePanelState(
    val entries: List<DevicePackageEntry>? = null,
    val filtered: List<DevicePackageEntry> = emptyList(),
    val query: String = "",
    val systemFilter: DevicePackageSystemFilter = DevicePackageSystemFilter.ALL,
    val loading: Boolean = false,
    val error: String? = null,
    val selected: String? = null,
    val details: DevicePackageDetailsUiState = DevicePackageDetailsUiState.Idle,
    val export: DevicePackageExportUiState = DevicePackageExportUiState.Idle,
    val toggle: DevicePackageToggleUiState = DevicePackageToggleUiState.Idle,
    val selectedDisabled: Boolean? = null,
    val runtimeByPackage: Map<String, DevicePackageRuntime> = emptyMap(),
    val sortMode: DevicePackageSortMode = DevicePackageSortMode.BY_NAME,
    val snapshot: DevicePackageSnapshot? = null,
    val diff: DevicePackageDiffUiState = DevicePackageDiffUiState.Idle,
)

/**
 * 设备应用面板 VM（设备观测域「应用」子页的列表与详情状态源）：把包列举/详情
 * 请求派发给注入的浏览内核（生产 = :core 设备域 DeviceHub.packageBrowser，
 * 挂起函数注入缝——单测 fake 即可离线驱动，零真子进程）。
 *
 * - 目标设备 = [DeviceStateSource] 选定串号（Multi 用会话级选择、Single 回退唯一
 *   台——与文件浏览同款语义）；目标串号变更 → 列表重载 + **详情缓存清空** +
 *   选中态复位（不同设备的包数据不可互推）；
 * - 目标不可用（无设备/多设备未选/检测失败）时 [refresh] 落人话回执（不静默不
 *   spawn）；目标从有到无时清空读数并回执失效原因——陈旧设备的包列表不得继续
 *   冒充可读数；
 * - 详情懒加载（选中后再拉取）：[DevicePackageDetailsUiState] 独立请求序号守卫，
 *   在途详情被新选中作废（交错回执不上卡）；已读详情按**当前设备串号**做会话级
 *   缓存（Map<包名, 详情>，换设备清空——同设备重复展开零二次 spawn）；
 * - 同包重复选中 = 收起展开（selected 置 null，详情回 Idle，缓存保留）。
 * - APK 导出（[requestExport]）与禁启（[requestDisable]/[requestEnable]）是详情卡
 *   动作：导出回执进 [DevicePackagePanelState.export]，禁启回执进
 *   [DevicePackagePanelState.toggle]，与列表/详情读数分立互不清零（但随选中态
 *   复位——回执是卡内语境产物）；导出内核的 .part/校验/取消语义见
 *   [DeviceApkExporter] 头注释，禁启的确认分层见 [DevicePackageToggleUiState]。
 * - 选中包的禁用态探测（[DevicePackagePanelState.selectedDisabled]）：选中时经
 *   已禁用集合（`pm list packages -d`）回读判定，请求序号守卫交错回执；探测失败
 *   置 null（面板不出禁启钮——禁启钮「按当前状态显其一」的前提是状态已知）。
 * - 运行态融合（[DevicePackagePanelState.runtimeByPackage]）：只读消费注入的
 *   [PerfSamplerState]（生产 = 设备域采样器 App 级单源的 StateFlow，本 VM 不起
 *   采样不写采样器）；采样数据按**当前列表串号** keyed 读取（串号不匹配零投影，
 *   设备切换瞬间不显示错配数据），且仅活跃会话的最近一拍参与投影（会话结束后
 *   的留存读数是历史面，不冒充实时运行态）。匹配口径与零投影条件见
 *   [processMatchesPackage] / [projectPackageRuntime]。
 * - 快照对比（[saveSnapshot]/[requestCompare]）：会话级快照存 VM 内存**不落盘**
 *   （持久化属后续裁决，模型契约见 [DevicePackageSnapshot]）；保存以装载成功为
 *   前提，新快照顶替并作废既有对比回执；对比以包名键逐包 diff（口径见
 *   [diffPackageEntries]），同串号直接出回执，跨串号落确认对话（确认才对比）；
 *   快照跨串号保留，对比回执随换目标复位（换设备后的对比是显式新动作）。
 */
class DevicePackageViewModel(
    private val devices: DeviceStateSource,
    private val listPackages: suspend (serial: String) -> Result<List<DevicePackageEntry>>,
    private val packageDetails: suspend (packageName: String, serial: String) -> Result<DevicePackageDetails>,
    private val exporter: DeviceApkExporter = DeviceApkExporter(runner = com.jiaoay.easy_dev_toolbox.core.device.SystemAdbProcessRunner, puller = com.jiaoay.toolbelt.device.DeviceFilePuller(runner = com.jiaoay.easy_dev_toolbox.core.device.SystemAdbProcessRunner, endpointReader = com.jiaoay.easy_dev_toolbox.core.device.DeviceHub::currentEndpoint), statEntry = { p, s -> com.jiaoay.easy_dev_toolbox.core.device.DeviceHub.fileBrowser.statEntry(p, s) }, endpointReader = com.jiaoay.easy_dev_toolbox.core.device.DeviceHub::currentEndpoint),
    private val toggler: DevicePackageToggler = DevicePackageToggler(runner = com.jiaoay.easy_dev_toolbox.core.device.SystemAdbProcessRunner, endpointReader = com.jiaoay.easy_dev_toolbox.core.device.DeviceHub::currentEndpoint),
    private val exportTargetDir: File = FileUtils.getDownloadsDir(),
    // 默认惰性空快照而非真单源：默认参数求值发生在每个构造点（含单测），接
    // DeviceHub.perfSampler 会连带触发设备域 App 级构造（轮询协程 + 配置读）；
    // 生产两处构造点（DeviceObsPage / DevicePackagePanel 兜底）显式传真单源
    private val perfState: StateFlow<PerfSamplerState> = MutableStateFlow(PerfSamplerState()).asStateFlow(),
) : ViewModel() {

    private val _state = MutableStateFlow(DevicePackagePanelState())
    val state: StateFlow<DevicePackagePanelState> = _state.asStateFlow()

    /** 已装载列表的串号（串号变更检测锚；null = 从未装载或目标已失效） */
    private var loadedSerial: String? = null

    /** 在途列表请求序号（完成回写前比对，被更新请求作废） */
    private var listSeq = 0

    /** 在途详情请求序号（交错守卫锚，与列表请求分立计数） */
    private var detailSeq = 0

    /** 在途禁用态探测序号（交错守卫锚，与详情请求分立计数） */
    private var disabledProbeSeq = 0

    /** 详情会话级缓存（key = 包名；换设备清空——包数据不跨设备互推） */
    private val detailsCache = mutableMapOf<String, DevicePackageDetails>()

    init {
        viewModelScope.launch {
            combine(devices.deviceState, devices.selectedSerial) { _, _ -> resolveTargetSerial() }
                .distinctUntilChanged()
                .collect { target -> onTargetChanged(target) }
        }
        // 采样器只读消费：每拍到达重算运行态投影（含会话收束/无数据的空投影），
        // 采样器自身零触碰（不起采样/不停止/不清史——面板是纯读消费方）
        viewModelScope.launch {
            perfState.collect { updateState { it } }
        }
    }

    /** 更新查询词（过滤是纯投影，零内核请求） */
    fun setQuery(query: String) {
        updateState { it.copy(query = query) }
    }

    /** 更新系统过滤三态档（过滤是纯投影，零内核请求） */
    fun setSystemFilter(filter: DevicePackageSystemFilter) {
        updateState { it.copy(systemFilter = filter) }
    }

    /** 更新列表排序档（纯投影，零内核请求；「按 CPU」消费运行态投影） */
    fun setSortMode(mode: DevicePackageSortMode) {
        updateState { it.copy(sortMode = mode) }
    }

    /**
     * 刷新包列表（手动刷新/失败重试共用入口）：目标不可用时落人话回执不 spawn。
     */
    fun refresh() {
        val serial = loadedSerial ?: resolveTargetSerial()
        if (serial == null) {
            updateState { it.copy(error = targetBlockedReceipt()) }
            return
        }
        loadPackages(serial)
    }

    /**
     * 保存会话级快照（当前装载列表 + 串号 + 时刻存 VM 内存，不落盘——模型契约见
     * [DevicePackageSnapshot]）：装载成功为前提，未装载落人话回执零快照；新快照
     * 顶替旧快照并作废既有对比回执（回执的基线已不存在）。
     */
    fun saveSnapshot() {
        val entries = _state.value.entries
        val serial = loadedSerial
        if (entries == null || serial == null) {
            updateState { it.copy(error = "包列表未装载，刷新成功后再保存快照") }
            return
        }
        updateState {
            it.copy(
                snapshot = DevicePackageSnapshot(serial, entries, System.currentTimeMillis()),
                diff = DevicePackageDiffUiState.Idle,
            )
        }
    }

    /**
     * 请求与快照对比：无快照零动作（面板按快照在位才出对比钮）；当前列表未装载
     * 落人话回执。同串号直接出回执；跨串号（快照来自另一台设备）落确认对话——
     * 确认才对比，取消零动作（见 [confirmCompare]/[dismissDiff]）。
     */
    fun requestCompare() {
        val snapshot = _state.value.snapshot ?: return
        val entries = _state.value.entries
        val serial = loadedSerial
        if (entries == null || serial == null) {
            updateState { it.copy(error = "当前包列表未装载，刷新成功后再对比") }
            return
        }
        if (snapshot.serial != serial) {
            updateState {
                it.copy(diff = DevicePackageDiffUiState.CrossSerialConfirmPending(snapshot.serial, serial))
            }
            return
        }
        updateState { it.copy(diff = diffResultOf(snapshot, entries, serial)) }
    }

    /**
     * 跨串号对比确认分支：确认时重读当前快照与列表（对话期间快照可能被顶替、
     * 目标可能失效——基线或列表缺席时对比不执行，回执失效原因并复位）。
     */
    fun confirmCompare() {
        if (_state.value.diff !is DevicePackageDiffUiState.CrossSerialConfirmPending) return
        val snapshot = _state.value.snapshot ?: return
        val entries = _state.value.entries
        val serial = loadedSerial
        if (entries == null || serial == null) {
            updateState {
                it.copy(diff = DevicePackageDiffUiState.Idle, error = "目标设备已不可用，对比未执行")
            }
            return
        }
        updateState { it.copy(diff = diffResultOf(snapshot, entries, serial)) }
    }

    /** 关闭对比回执/取消跨串号确认（零动作零 spawn，快照保留——收起回执不是丢弃基线） */
    fun dismissDiff() {
        updateState { it.copy(diff = DevicePackageDiffUiState.Idle) }
    }

    /** 对比回执组装（纯 diff + 快照元数据 + 对比时刻当前串号，静态产物不自动重算） */
    private fun diffResultOf(
        snapshot: DevicePackageSnapshot,
        entries: List<DevicePackageEntry>,
        serial: String,
    ): DevicePackageDiffUiState.Result =
        DevicePackageDiffUiState.Result(diffPackageEntries(snapshot.entries, entries), snapshot, serial)

    /**
     * 选中包展开详情卡（再点同包 = 收起）：详情命中缓存零 spawn；未命中走懒加载
     * （请求序号守卫，在途被新选中作废）；目标不可用落失败回执（选中态保留，
     * 目标恢复后重选即读）。选中变更同时复位详情卡动作区（导出/禁启回执是卡内
     * 语境产物，不跨选中态残留）并重探选中包禁用态。
     */
    fun selectPackage(entry: DevicePackageEntry) {
        val current = _state.value
        if (current.selected == entry.packageName) {
            // 收起同时作废在途详情请求（防迟到回执写进无选中态的详情槽）
            detailSeq++
            disabledProbeSeq++
            updateState {
                it.copy(
                    selected = null,
                    details = DevicePackageDetailsUiState.Idle,
                    export = DevicePackageExportUiState.Idle,
                    toggle = DevicePackageToggleUiState.Idle,
                    selectedDisabled = null,
                )
            }
            return
        }
        updateState {
            it.copy(
                selected = entry.packageName,
                details = DevicePackageDetailsUiState.Idle,
                export = DevicePackageExportUiState.Idle,
                toggle = DevicePackageToggleUiState.Idle,
                selectedDisabled = null,
            )
        }
        val serial = loadedSerial ?: resolveTargetSerial()
        if (serial == null) {
            updateState {
                it.copy(details = DevicePackageDetailsUiState.Failed(entry.packageName, targetBlockedReceipt()))
            }
            return
        }
        probeDisabledState(entry.packageName, serial)
        detailsCache[entry.packageName]?.let { cached ->
            updateState { it.copy(details = DevicePackageDetailsUiState.Ready(cached)) }
            return
        }
        loadDetails(entry.packageName, serial)
    }

    /**
     * 单次详情装载：busy 即时上屏（展开卡进度位），完成后按结果回写；成功入缓存
     * （同设备重复展开零二次 spawn）。
     */
    private fun loadDetails(packageName: String, serial: String) {
        val seq = ++detailSeq
        updateState { it.copy(details = DevicePackageDetailsUiState.Busy(packageName)) }
        viewModelScope.launch {
            val result = packageDetails(packageName, serial)
            if (seq != detailSeq) return@launch
            result.fold(
                onSuccess = { details ->
                    detailsCache[packageName] = details
                    updateState { it.copy(details = DevicePackageDetailsUiState.Ready(details)) }
                },
                onFailure = { e ->
                    updateState {
                        it.copy(
                            details = DevicePackageDetailsUiState.Failed(
                                packageName,
                                e.message ?: "包详情读取失败（未知错误）",
                            ),
                        )
                    }
                },
            )
        }
    }

    /**
     * 请求导出选中包的 APK 到本地导出目录（[exportTargetDir]，App 形态默认下载
     * 目录）：busy 即时上屏（pm path 解析期 fileCount=0 占位），逐 split 经内核
     * 进度回调推进；在途导出中再点被拒（导出内核单飞行）。目标不可用落
     * [DevicePackagePanelState.error] 人话回执不 spawn（与列表动作同口径）。
     */
    fun requestExport(packageName: String) {
        if (_state.value.export is DevicePackageExportUiState.Busy) return
        val serial = loadedSerial ?: resolveTargetSerial()
        if (serial == null) {
            updateState { it.copy(error = targetBlockedReceipt()) }
            return
        }
        updateState {
            it.copy(
                export = DevicePackageExportUiState.Busy(packageName, 0, 0, "", 0),
            )
        }
        viewModelScope.launch {
            val result = exporter.exportApk(packageName, exportTargetDir, serial) { start ->
                updateState {
                    it.copy(
                        export = DevicePackageExportUiState.Busy(
                            start.packageName,
                            start.fileIndex,
                            start.fileCount,
                            start.fileName,
                            start.expectedSizeBytes,
                        ),
                    )
                }
            }
            updateState { st ->
                st.copy(
                    export = result.fold(
                        onSuccess = { receipt -> DevicePackageExportUiState.Done(packageName, receipt) },
                        onFailure = { e ->
                            DevicePackageExportUiState.Failed(packageName, e.message ?: "APK 导出失败（未知错误）")
                        },
                    ),
                )
            }
        }
    }

    /** 取消在途导出（无在途为无动作；取消后内核落取消回执并清理在途 .part） */
    fun cancelExport() {
        exporter.requestCancel()
    }

    /**
     * 请求禁用包（确认对话数据源——内核被调用即执行，确认分层在本层）：在途禁启
     * 中再点被拒。包当前禁用态未知（探测失败/未完成）时同样出确认（pm 对已禁用
     * 包再禁用是设备侧幂等错误，确认面不吞）。
     */
    fun requestDisable(packageName: String) {
        if (_state.value.toggle is DevicePackageToggleUiState.Busy) return
        updateState { it.copy(toggle = DevicePackageToggleUiState.DisableConfirmPending(packageName)) }
    }

    /** 请求启用包（确认对话数据源，同 [requestDisable] 分层） */
    fun requestEnable(packageName: String) {
        if (_state.value.toggle is DevicePackageToggleUiState.Busy) return
        updateState { it.copy(toggle = DevicePackageToggleUiState.EnableConfirmPending(packageName)) }
    }

    /**
     * 确认禁启执行（确认对话的确认分支；取消分支 = [dismissToggleConfirm] 零动作
     * 零 spawn）。目标不可用时落失败回执（确认对话关闭，禁启未执行）。
     */
    fun confirmToggle() {
        val (packageName, disable) = when (val pending = _state.value.toggle) {
            is DevicePackageToggleUiState.DisableConfirmPending -> pending.packageName to true
            is DevicePackageToggleUiState.EnableConfirmPending -> pending.packageName to false
            else -> return
        }
        val serial = loadedSerial ?: resolveTargetSerial()
        if (serial == null) {
            val actionText = if (disable) "禁用" else "启用"
            updateState {
                it.copy(
                    toggle = DevicePackageToggleUiState.Failed(
                        packageName,
                        "目标设备已不可用（断开或未选定），$actionText 未执行",
                    ),
                )
            }
            return
        }
        executeToggle(packageName, disable, serial)
    }

    /** 取消禁启确认（零动作零 spawn 回 Idle——设备状态未被触碰） */
    fun dismissToggleConfirm() {
        updateState { it.copy(toggle = DevicePackageToggleUiState.Idle) }
    }

    /**
     * 禁启执行体：busy 落位 → 内核回执回写；成功后刷新选中包详情（缓存作废重读）
     * 并重探禁用态（禁启钮按新状态显其一）。
     */
    private fun executeToggle(packageName: String, disable: Boolean, serial: String) {
        val actionText = if (disable) "禁用" else "启用"
        updateState {
            it.copy(toggle = DevicePackageToggleUiState.Busy(packageName, actionText))
        }
        viewModelScope.launch {
            val result = if (disable) {
                toggler.disablePackage(packageName, serial)
            } else {
                toggler.enablePackage(packageName, serial)
            }
            result.fold(
                onSuccess = {
                    updateState {
                        it.copy(toggle = DevicePackageToggleUiState.Done(packageName, "已$actionText $packageName"))
                    }
                    detailsCache.remove(packageName)
                    if (_state.value.selected == packageName) {
                        loadDetails(packageName, serial)
                        probeDisabledState(packageName, serial)
                    }
                },
                onFailure = { e ->
                    updateState {
                        it.copy(
                            toggle = DevicePackageToggleUiState.Failed(
                                packageName,
                                e.message ?: "${actionText}失败（未知错误）",
                            ),
                        )
                    }
                },
            )
        }
    }

    /**
     * 选中包禁用态探测（`pm list packages -d` 集合成员判定）：请求序号守卫交错
     * 回执；成功置成员判定值，失败置 null（面板不出禁启钮——状态未知不猜）。
     */
    private fun probeDisabledState(packageName: String, serial: String) {
        val seq = ++disabledProbeSeq
        viewModelScope.launch {
            val result = toggler.disabledPackages(serial)
            if (seq != disabledProbeSeq) return@launch
            result.fold(
                onSuccess = { disabledSet ->
                    updateState { it.copy(selectedDisabled = packageName in disabledSet) }
                },
                onFailure = {
                    updateState { it.copy(selectedDisabled = null) }
                },
            )
        }
    }

    /**
     * 目标变更消费：串号实变 → 缓存清空 + 选中态复位 + 对比回执复位 + 列表重载
     * （在途请求与详情靠序号/串号判据一并作废）；快照保留（跨串号对比是场景
     * 本体）；目标从有到无 → 清读数回执失效原因（快照与对比回执同理复位）。
     */
    private fun onTargetChanged(target: String?) {
        if (target != null && target != loadedSerial) {
            loadedSerial = target
            detailsCache.clear()
            listSeq++
            detailSeq++
            disabledProbeSeq++
            updateState {
                it.copy(
                    selected = null,
                    details = DevicePackageDetailsUiState.Idle,
                    export = DevicePackageExportUiState.Idle,
                    toggle = DevicePackageToggleUiState.Idle,
                    selectedDisabled = null,
                    diff = DevicePackageDiffUiState.Idle,
                )
            }
            loadPackages(target)
        } else if (target == null && loadedSerial != null) {
            loadedSerial = null
            detailsCache.clear()
            listSeq++
            detailSeq++
            disabledProbeSeq++
            updateState {
                it.copy(
                    entries = null,
                    selected = null,
                    details = DevicePackageDetailsUiState.Idle,
                    export = DevicePackageExportUiState.Idle,
                    toggle = DevicePackageToggleUiState.Idle,
                    selectedDisabled = null,
                    diff = DevicePackageDiffUiState.Idle,
                    error = "目标设备已不可用（断开或未选定），包列表读数已失效",
                )
            }
        }
    }

    /**
     * 单次列表装载：装载期置 [DevicePackagePanelState.loading]（面板据此禁刷新），
     * 完成后按结果回写；请求序号作废检查在回写前（在途装载被换目标作废）。
     */
    private fun loadPackages(serial: String) {
        val seq = ++listSeq
        updateState { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = listPackages(serial)
            if (seq != listSeq) return@launch
            result.fold(
                onSuccess = { entries -> updateState { it.copy(entries = entries, loading = false, error = null) } },
                onFailure = { e ->
                    updateState { it.copy(loading = false, error = e.message ?: "包列举失败（未知错误）") }
                },
            )
        }
    }

    /**
     * 唯一状态写入点：统一重算两个投影——运行态投影（[runtimeProjectionFor]）与
     * 过滤排序投影（[filterPackageEntries] → [sortPackageEntries]）——查询词/过滤
     * 档/排序档/列表/采样拍五者任何之一变更都收敛到同一管道，防投影漏算。
     */
    private fun updateState(transform: (DevicePackagePanelState) -> DevicePackagePanelState) {
        _state.update { current ->
            val next = transform(current)
            val runtime = runtimeProjectionFor(next.entries)
            next.copy(
                runtimeByPackage = runtime,
                filtered = sortPackageEntries(
                    filterPackageEntries(next.entries, next.query, next.systemFilter),
                    runtime,
                    next.sortMode,
                ),
            )
        }
    }

    /**
     * 运行态投影重算（每状态写入与每采样拍到达都收敛于此）：
     * - 按**当前列表串号** keyed 直读 [PerfSamplerState.latestBySerial]（多会话
     *   消费面禁用单会话投影）——串号不匹配零投影，设备切换瞬间不显示错配数据；
     * - 仅活跃会话的最近一拍参与投影：会话表无该串号（未采样）或非 Active
     *   （已收束）时留存读数不冒充实时运行态；
     * - 进程域失败（PerfField.error）按无数据处理；[entries] 未装载/为空时零投影。
     */
    private fun runtimeProjectionFor(entries: List<DevicePackageEntry>?): Map<String, DevicePackageRuntime> {
        if (entries.isNullOrEmpty()) return emptyMap()
        val serial = loadedSerial ?: return emptyMap()
        val sampler = perfState.value
        if (sampler.sessions[serial] !is PerfSessionState.Active) return emptyMap()
        val processes = sampler.latestBySerial[serial]?.processes?.value ?: return emptyMap()
        return projectPackageRuntime(processes, entries)
    }

    /** 目标不可用人话回执（无设备/检测失败/多设备未选三分支） */
    private fun targetBlockedReceipt(): String = when {
        devices.detectError.value != null ->
            "设备检测失败：${devices.detectError.value}——请检查设置页 ADB 路径"

        devices.deviceState.value is DeviceState.None ->
            "未检测到设备，请连接设备（或刷新检测）"

        devices.deviceState.value is DeviceState.Multi && devices.selectedSerial.value == null ->
            "多设备连接，请先在上方设备栏选择目标设备"

        else -> "目标设备不可用（未检测到设备或未选定）"
    }

    /** 目标串号解析：Multi 用会话级选择；Single 回退唯一台；None = null */
    private fun resolveTargetSerial(): String? = when (val device = devices.deviceState.value) {
        is DeviceState.Single -> device.serial
        is DeviceState.Multi -> devices.selectedSerial.value
        is DeviceState.None -> null
    }
}
