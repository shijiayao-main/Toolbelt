package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.DesktopUtils
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.pathFileName
import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.jiaoay.toolbelt.device.DeviceDirectoryPullReceipt
import com.jiaoay.toolbelt.device.DeviceFileEntry
import com.jiaoay.toolbelt.device.DeviceFilePullReceipt
import com.jiaoay.toolbelt.device.DeviceFilePuller
import com.jiaoay.toolbelt.device.DeviceFilePushReceipt
import com.jiaoay.toolbelt.device.DeviceFilePusher
import com.jiaoay.toolbelt.device.DeviceFileStat
import com.jiaoay.toolbelt.device.DeviceFileType
import com.jiaoay.toolbelt.device.DevicePullReceipt
import com.jiaoay.toolbelt.device.DevicePushTargetExistsException
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource

/**
 * 浏览起点目录（设备侧用户数据卷根——设备日志/抓包等产物的常规落点）。
 * 尾斜杠不可去（契约）：真机上 `/sdcard` 是指向卷的 symlink，不带尾斜杠的
 * `ls -la /sdcard` 只列出链接自身一行（条目名即死胡同）；尾斜杠强制 ls 穿透
 * 进卷内列内容。子目录拼接在 [joinChildPath] 内去父路径尾斜杠，子层均为
 * 真实目录不受此限。
 */
internal const val DEVICE_FILE_BROWSER_ROOT = "/sdcard/"

/**
 * 条目名过滤投影（internal 供单测）：查询词为空 = 不过滤；非空 = 条目名**包含**
 * 匹配（大小写不敏感——目录内按名字找日志/抓包产物是高频动作，前缀输入天然被
 * 包含语义覆盖）。null 列表（未装载/目标失效）过滤为空列表。
 */
internal fun filterFileEntries(
    entries: List<DeviceFileEntry>?,
    query: String,
): List<DeviceFileEntry> {
    if (entries == null) return emptyList()
    val q = query.trim().lowercase()
    if (q.isEmpty()) return entries
    return entries.filter { it.name.lowercase().contains(q) }
}

/**
 * 拉回产物会话缓存上界：超界淘汰最旧（列表尾部）。防连续拉取数百产物把回执面
 * 与渲染面撑爆；20 覆盖一次浏览会话的典型拉取量且常读产物全在手。
 */
internal const val PULL_PRODUCT_CACHE_LIMIT = 20

/**
 * 落盘前撞名判定（internal 供单测）：拉取目标路径的会话级避让决策。纯函数 =
 * 只查不建零写盘（路径决策零副作用，同秒避让产物族同口径）。
 *
 * 契约：
 * - 期望落点 = [targetDir]/[entryName]；缓存内无行占据该路径 → 原名落盘
 *   （跨会话旧文件不感知——会话级缓存既有契约，不为跨会话扩面）；
 * - 占据行与本次**同源**（[sourceSerial]+[sourceDevicePath] 均同）→ 原名
 *   落盘：同源重拉 = 覆盖刷新（重拉最新日志是正动作，终态被本次结果原子
 *   替换是拉取内核既有语义）；
 * - 占据行**异源** → 期望名撞车，`-N` 序号后缀避让（后缀插在扩展名前，
 *   logcat/CSV 产物同配方）：候选位被缓存异源行或磁盘既有文件占据则序号
 *   递增（磁盘占位只查候选位——防避让落点顶替无主文件）；候选位是同源行
 *   → 落该位覆盖刷新（同源重拉刷新既有避让位）。
 */
internal fun resolvePullTargetFile(
    targetDir: File,
    entryName: String,
    sourceSerial: String,
    sourceDevicePath: String,
    cached: List<DeviceFilePullProduct>,
): File {
    val desired = File(targetDir, entryName)
    val occupying = cached.firstOrNull { it.receipt.localPath == desired.absolutePath }
    if (occupying == null || (occupying.sourceSerial == sourceSerial && occupying.sourceDevicePath == sourceDevicePath)) {
        return desired
    }
    var suffix = 1
    while (true) {
        val candidate = File(targetDir, numberedPullFileName(entryName, suffix))
        val row = cached.firstOrNull { it.receipt.localPath == candidate.absolutePath }
        if (row == null) {
            if (!candidate.exists()) return candidate
        } else if (row.sourceSerial == sourceSerial && row.sourceDevicePath == sourceDevicePath) {
            return candidate
        }
        suffix++
    }
}

/** 撞名避让的序号后缀名：后缀插在扩展名前（`log.txt` → `log-1.txt`）；无扩展名整体加尾；点开头的隐藏名视同无扩展名 */
private fun numberedPullFileName(entryName: String, suffix: Int): String {
    val dot = entryName.lastIndexOf('.')
    return if (dot > 0) {
        entryName.substring(0, dot) + "-" + suffix + entryName.substring(dot)
    } else {
        "$entryName-$suffix"
    }
}

/**
 * 拉回产物会话缓存行：一次成功拉取的回执全量（衔接动作所需的文件名与本地
 * 路径/大小/速度）。
 *
 * 契约：会话级缓存——VM 生命周期内有效，不落盘、不随设备切换清空（产物是本地
 * 产物，与设备侧目录树无关）；持久化属后续裁决，本层不做。
 * [receipt] 是文件/目录两条拉取链的公共回执面（[DevicePullReceipt]）：衔接/
 * 预览/查看动作只对文件回执成立，目录回执行无衔接/查看钮（目录不是 Tag 过滤/
 * XLog 解密/查看器的输入载体，衔接与查看缺席 by design）；打开所在文件夹对
 * 文件/目录两形态均成立。
 *
 * [sourceSerial]+[sourceDevicePath] 是落盘撞名判定（[resolvePullTargetFile]）
 * 的分源依据：设备日志文件名高度同名，异源（异设备/同设备异目录）同名拉取若
 * 落同一路径会静默顶替前次产物——产物行动作（查看/送分析/预览）随即张冠李戴。
 * 串号必须进来源标识：不同设备浏览同一起点根时设备侧路径相同，仅凭路径无法
 * 分辨设备。
 */
data class DeviceFilePullProduct(
    val fileName: String,
    val receipt: DevicePullReceipt,
    val sourceSerial: String,
    val sourceDevicePath: String,
)

/**
 * 文件浏览面板状态（请求-回执模型）：目录读数只在用户动作或目标串号变更时请求，
 * 无轮询环——「目录内容变了」不被本 VM 感知，刷新是显式动作。
 *
 * 契约：
 * - [entries] null = 未装载（无目标设备或首次读取未完成）；空列表 = 空目录（合法读数，
 *   解析层保证 `ls` 退出 0 零条目即空目录）；
 * - [filtered] 是 [entries] 按 [query] 的条目名过滤投影（纯显示层零 spawn），未装载
 *   时恒空列表（面板只消费 filtered）；query 不随目录装载/翻目录/刷新清零——用户
 *   翻目录时保留过滤词是常态，换目标设备同样保留（过滤词是目录无关的显示偏好）；
 * - [error] 为面向用户的失败回执（人话原样呈现，不翻译不吞）；下一动作（刷新/进入/
 *   返回/换目标）开始即清零；
 * - 进入/返回的导航栈变更**只在装载成功后提交**（失败停在原目录，不落半截导航）；
 * - [canNavigateUp] false = 已在浏览起点根（返回上级按钮的置灰依据，根上返回为
 *   无动作）；
 * - [pullProducts] 是拉回产物会话缓存（最近拉回在上，上界 [PULL_PRODUCT_CACHE_LIMIT]
 *   超界淘汰最旧）——拉 B 不丢 A 的衔接入口承载面；会话级不落盘（契约见
 *   [DeviceFilePullProduct]）；
 * - [pullProductError] 是产物行动作（送分析/预览/查看/打开所在文件夹）的失败回执
 *   （人话），下一次动作（拉取/衔接/预览/查看/打开所在文件夹）开始即清零。
 */
data class DeviceFileBrowserState(
    val path: String = DEVICE_FILE_BROWSER_ROOT,
    val canNavigateUp: Boolean = false,
    val entries: List<DeviceFileEntry>? = null,
    val filtered: List<DeviceFileEntry> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val pull: DeviceFilePullUiState = DeviceFilePullUiState.Idle,
    val pullProducts: List<DeviceFilePullProduct> = emptyList(),
    val pullProductError: String? = null,
    val push: DeviceFilePushUiState = DeviceFilePushUiState.Idle,
)

/**
 * 拉取动作 UI 状态（请求-回执模型，与目录读数分立互不清零）。
 *
 * 契约：[Busy] 期间面板拉取动作全禁（拉取内核单飞行，文件/目录两条链共用
 * busy 态）；[Busy.expectedSizeBytes] 是列目录时的 ls 尺寸（展示锚，非校验锚
 * ——校验走拉取时刻设备侧 stat 真值；目录链无尺寸校验面，[Busy.isDirectory]
 * 置位时该值无展示意义）；[Done] 回执携带本地路径/大小/速度（文件链）或
 * 文件数/总字节/速度（目录链，信息面——无单文件级校验，契约见
 * [DeviceDirectoryPullReceipt]），同时登记进
 * [DeviceFileBrowserState.pullProducts] 会话缓存（衔接入口由产物列表行承载）；
 * [Failed] 人话回执原样呈现（内核已含清理动作说明）。
 */
sealed interface DeviceFilePullUiState {
    data object Idle : DeviceFilePullUiState
    data class Busy(
        val fileName: String,
        val expectedSizeBytes: Long,
        val isDirectory: Boolean = false,
    ) : DeviceFilePullUiState

    data class Done(val fileName: String, val receipt: DevicePullReceipt) : DeviceFilePullUiState
    data class Failed(val fileName: String, val message: String) : DeviceFilePullUiState
}

/**
 * 推送动作 UI 状态（请求-回执模型，与目录读数/拉取回执分立互不清零）。
 *
 * 契约：[Busy] 期间面板推送动作全禁（推送内核单飞行），busy 覆盖「目标预检 + 推送」
 * 全程（预检是 adb stat 调用非零耗时）；[ConfirmPending] = 设备目标已存在、等待
 * 用户裁决（确认 = 覆盖推送，取消 = 零动作回 [DeviceFilePushUiState.Idle]——
 * 不静默覆盖是推送方向的安全语义，确认对话是真机门禁项）；[Done] 回执携带
 * 设备目标全路径/大小/速度；[Failed] 人话回执原样呈现（内核已含设备侧残留面说明）。
 */
sealed interface DeviceFilePushUiState {
    data object Idle : DeviceFilePushUiState
    data class ConfirmPending(
        val localPath: String,
        val fileName: String,
        val deviceDir: String,
        val sizeBytes: Long,
    ) : DeviceFilePushUiState

    data class Busy(val fileName: String, val expectedSizeBytes: Long) : DeviceFilePushUiState
    data class Done(val fileName: String, val receipt: DeviceFilePushReceipt) : DeviceFilePushUiState
    data class Failed(val fileName: String, val message: String) : DeviceFilePushUiState
}

/**
 * 设备文件浏览 VM（设备观测域「文件」子页的导航与读数状态源）：持有目录导航栈，
 * 把目录列举请求派发给注入的浏览内核（生产 = :core 设备域 DeviceHub.fileBrowser，
 * 挂起函数注入缝——单测 fake 即可离线驱动）。
 *
 * - 目标设备 = [DeviceStateSource] 选定串号（Multi 用会话级选择、Single 回退唯一
 *   台——与 logcat 抓取/性能采样同款语义）；目标串号变更 → 导航栈重置回浏览起点
 *   根并自动重载（不同设备的目录树不可互推）；
 * - 目标不可用（无设备/多设备未选/检测失败）时 [refresh] 落 [DeviceFileBrowserState.error]
 *   人话回执（不静默不 spawn）；目标从有到无（设备拔出/取消选择）时清空读数并
 *   回执失效原因——陈旧设备的目录列表不得继续冒充可读数；
 * - 竞态收束：在途请求被新请求作废（请求序号守卫，交错回执不上屏）；装载中
 *   进入/返回/刷新被拒（防对同目录双发 `ls`）；
 * - 拉取动作（[pullEntry]）与目录读数分立：回执进 [DeviceFileBrowserState.pull]
 *   互不清零；成功回执同时登记进 [DeviceFileBrowserState.pullProducts] 会话缓存
 *   （上界淘汰最旧，契约见 [DeviceFilePullProduct]）；文件/目录两条链的
 *   .part 完整性与目录链校验弱化契约见 [DeviceFilePuller] 头注释与
 *   [DeviceFilePuller.pullDirectory]；symlink 条目先经
 *   [statEntryDereferenced] 判真值再放行（真值分支契约见 [pullEntry]）；
 * - 推送动作（[requestPush]）与拉取/目录读数分立：回执进
 *   [DeviceFileBrowserState.push] 互不清零；不静默覆盖契约见 [DeviceFilePusher]
 *   头注释。
 */
class DeviceFileBrowserViewModel(
    private val devices: DeviceStateSource,
    private val listDirectory: suspend (path: String, serial: String) -> Result<List<DeviceFileEntry>>,
    private val statEntryDereferenced: suspend (path: String, serial: String) -> Result<DeviceFileStat> =
        { path, serial -> DeviceHub.fileBrowser.statEntry(path, serial, dereference = true) },
    private val puller: DeviceFilePuller = DeviceFilePuller(runner = com.jiaoay.easy_dev_toolbox.core.device.SystemAdbProcessRunner, endpointReader = com.jiaoay.easy_dev_toolbox.core.device.DeviceHub::currentEndpoint),
    private val pusher: DeviceFilePusher = DeviceFilePusher(runner = com.jiaoay.easy_dev_toolbox.core.device.SystemAdbProcessRunner, endpointReader = com.jiaoay.easy_dev_toolbox.core.device.DeviceHub::currentEndpoint),

    /** 打开所在文件夹动作缝：生产 = [DesktopUtils.openInFolder]（父目录语义），单测注入捕获 */
    private val folderOpener: (String) -> Unit = DesktopUtils::openInFolder,
    private val pullTargetDir: File = FileUtils.getDownloadsDir(),
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceFileBrowserState())
    val state: StateFlow<DeviceFileBrowserState> = _state.asStateFlow()

    /** 导航栈（含浏览起点根；last = 当前目录；单线程 Main 调度域内读写） */
    private val stack = mutableListOf(DEVICE_FILE_BROWSER_ROOT)

    /** 已装载读数的串号（串号变更检测锚；null = 从未装载或目标已失效） */
    private var loadedSerial: String? = null

    /** 在途请求序号（装载完成回写前比对，被更新请求作废） */
    private var requestSeq = 0

    init {
        viewModelScope.launch {
            combine(devices.deviceState, devices.selectedSerial) { _, _ -> resolveTargetSerial() }
                .distinctUntilChanged()
                .collect { target -> onTargetChanged(target) }
        }
    }

    /**
     * 更新过滤词（条目名过滤是纯显示层投影，零内核请求零 spawn）；翻目录/刷新/
     * 换目标不清词——过滤词跨目录装载持续生效。
     */
    fun setQuery(query: String) {
        updateState { it.copy(query = query) }
    }

    /**
     * 刷新当前目录（手动刷新/失败重试共用入口）：目标不可用时落人话回执不 spawn；
     * 导航栈不动（重试语义——停在出错目录原地重读）。
     */
    fun refresh() {
        val serial = loadedSerial ?: resolveTargetSerial()
        if (serial == null) {
            updateState { it.copy(error = targetBlockedReceipt()) }
            return
        }
        load(stack.last(), serial, commitNavigation = null)
    }

    /**
     * 进入条目：目录直进；symlink 条目先经解引用 stat 判真值再分派——真值目录
     * 经尾斜杠穿透进入（`ls -la` 对 symlink 终段只列链接自身一行，尾斜杠强制
     * 穿透进目标列内容——起点根契约同源，真机实测）；真值普通文件回人话
     * 回执指引拉取动作（不静默）；断链/目标不可访问回执明示「断链」（不静默）。
     * stat 判真值期间 [DeviceFileBrowserState.loading] 置位（面板禁导航，防双发）；
     * 装载成功才提交导航栈。
     */
    fun enterDirectory(entry: DeviceFileEntry) {
        if (entry.type == DeviceFileType.FILE || _state.value.loading) return
        val serial = loadedSerial ?: resolveTargetSerial()
        if (serial == null) {
            updateState { it.copy(error = targetBlockedReceipt()) }
            return
        }
        val child = joinChildPath(stack.last(), entry.name)
        when (entry.type) {
            DeviceFileType.DIRECTORY ->
                load(child, serial, commitNavigation = { stack.add(child) })

            DeviceFileType.SYMLINK -> enterSymlinkDirectory(entry, child, serial)

            DeviceFileType.FILE -> Unit
        }
    }

    /**
     * symlink 条目的真值判定 + 进入分派（点击行为三分支的承载）：
     * - 真值目录：`<子路径>/` 尾斜杠装载（导航栈同样记尾斜杠形态——子层拼接
     *   在 [joinChildPath] 内去尾斜杠，不受影响）；
     * - 真值普通文件：回执指引拉取动作，零 ls spawn；
     * - stat 失败（断链 symlink 解引用退出码非 0 / 设备侧错误）：回执明示断链
     *   或目标不可访问 + 设备侧原文，不静默。
     * 请求序号守卫与 [load] 同口径（判真值回写前被更新请求作废即丢弃）。
     */
    private fun enterSymlinkDirectory(entry: DeviceFileEntry, child: String, serial: String) {
        val seq = ++requestSeq
        updateState { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val stat = statEntryDereferenced(child, serial)
            if (seq != requestSeq) return@launch
            stat.fold(
                onSuccess = { s ->
                    if (s.type == DeviceFileType.DIRECTORY) {
                        val target = "$child/"
                        load(target, serial, commitNavigation = { stack.add(target) })
                    } else {
                        updateState {
                            it.copy(
                                loading = false,
                                error = "「${entry.name}」是指向普通文件的符号链接，不可作为目录进入" +
                                    "——可用行尾「拉取到本地」取回",
                            )
                        }
                    }
                },
                onFailure = { e ->
                    updateState {
                        it.copy(
                            loading = false,
                            error = "「${entry.name}」是断链或目标不可访问的符号链接：" +
                                (e.message ?: "未知错误"),
                        )
                    }
                },
            )
        }
    }

    /** 返回上级：弹一层导航栈；浏览起点根上为无动作（按钮已按 canNavigateUp 置灰）。 */
    fun navigateUp() {
        if (stack.size <= 1 || _state.value.loading) return
        val serial = loadedSerial ?: return
        val parent = stack[stack.lastIndex - 1]
        load(parent, serial, commitNavigation = { stack.removeAt(stack.lastIndex) })
    }

    /**
     * 拉取条目到本地：普通文件直拉；目录条目走目录递归拉取（[startDirectoryPull]
     * ——目录链校验面弱化契约见 [DeviceFilePuller.pullDirectory]）；symlink 条目
     * 先经解引用 stat 判真值再分派——真值普通文件放行拉取（拉取内核传输目标
     * 内容，期望尺寸取真值而非 ls 链接路径长）；真值目录/断链回人话回执
     * （不静默；真值目录的 symlink 不在目录拉取面内——可进入后逐条拉取）。
     * 目标路径 = 默认下载目录 + 设备侧原始名（异源同名经 [resolvePullTargetFile]
     * 以 `-N` 后缀避让、同源重拉覆盖刷新——完成回执如实携带实际落点）；在途
     * 拉取中再点被拒（拉取内核
     * 单飞行，文件/目录两条链共用 busy 态）。busy 即时上屏（symlink 判真值期间
     * busy 用 ls 尺寸占位——展示锚非校验锚，真值落定后修正），完成/失败回执进
     * [DeviceFileBrowserState.pull]，与目录读数回执分立。
     */
    fun pullEntry(entry: DeviceFileEntry) {
        if (_state.value.pull is DeviceFilePullUiState.Busy) return
        val serial = loadedSerial ?: resolveTargetSerial()
        if (serial == null) {
            updateState { it.copy(error = targetBlockedReceipt()) }
            return
        }
        val devicePath = joinChildPath(stack.last(), entry.name)
        when (entry.type) {
            DeviceFileType.DIRECTORY -> startDirectoryPull(entry, devicePath, serial)
            DeviceFileType.SYMLINK -> pullSymlinkEntry(entry, devicePath, serial)
            DeviceFileType.FILE -> startPull(entry, devicePath, serial, entry.sizeBytes)
        }
    }

    /**
     * symlink 条目拉取的真值判定分派：busy 占位先行（判真值期间面板拉取动作
     * 已禁），真值普通文件修正 busy 期望尺寸后续接共用拉取执行体；真值目录/断链
     * 落 [DeviceFilePullUiState.Failed] 人话回执，零 pull spawn。
     */
    private fun pullSymlinkEntry(entry: DeviceFileEntry, devicePath: String, serial: String) {
        updateState { it.copy(pull = DeviceFilePullUiState.Busy(entry.name, entry.sizeBytes)) }
        viewModelScope.launch {
            val stat = statEntryDereferenced(devicePath, serial)
            stat.fold(
                onSuccess = { s ->
                    if (s.type == DeviceFileType.FILE) {
                        startPull(entry, devicePath, serial, s.sizeBytes)
                    } else {
                        updateState {
                            it.copy(
                                pull = DeviceFilePullUiState.Failed(
                                    entry.name,
                                    "「${entry.name}」是指向目录的符号链接，不在目录拉取面内" +
                                        "（可点击进入后拉取目录内条目），未执行拉取",
                                ),
                            )
                        }
                    }
                },
                onFailure = { e ->
                    updateState {
                        it.copy(
                            pull = DeviceFilePullUiState.Failed(
                                entry.name,
                                "「${entry.name}」是断链或目标不可访问的符号链接，未执行拉取：" +
                                    (e.message ?: "未知错误"),
                            ),
                        )
                    }
                },
            )
        }
    }

    /**
     * 拉取执行体（普通文件与 symlink 真值命中两条路径共用）：busy 落位 → 内核回执
     * 回写（[settlePullOutcome]）。目标路径经 [resolvePullTargetFile] 撞名判定
     * （异源同名 -N 避让/同源覆盖刷新），完成回执如实携带实际落点。目录链执行体
     * 见 [startDirectoryPull]。
     */
    private fun startPull(entry: DeviceFileEntry, devicePath: String, serial: String, expectedSizeBytes: Long) {
        val localTarget = resolvePullTargetFile(pullTargetDir, entry.name, serial, devicePath, _state.value.pullProducts)
        updateState {
            it.copy(pull = DeviceFilePullUiState.Busy(entry.name, expectedSizeBytes), pullProductError = null)
        }
        viewModelScope.launch {
            settlePullOutcome(entry, serial, devicePath, puller.pullFile(devicePath, localTarget.absolutePath, serial))
        }
    }

    /**
     * 目录递归拉取执行体：busy 落位（[DeviceFilePullUiState.Busy.isDirectory]
     * 置位，期望尺寸无展示意义）→ 内核回执回写。目标路径撞名判定与文件链同口径
     * （[resolvePullTargetFile]，异源同名目录同样 -N 避让）。目录链校验面弱化
     * （无单文件级 size 校验）契约见 [DeviceFilePuller.pullDirectory]。
     */
    private fun startDirectoryPull(entry: DeviceFileEntry, devicePath: String, serial: String) {
        val localTarget = resolvePullTargetFile(pullTargetDir, entry.name, serial, devicePath, _state.value.pullProducts)
        updateState {
            it.copy(
                pull = DeviceFilePullUiState.Busy(entry.name, entry.sizeBytes, isDirectory = true),
                pullProductError = null,
            )
        }
        viewModelScope.launch {
            settlePullOutcome(entry, serial, devicePath, puller.pullDirectory(devicePath, localTarget.absolutePath, serial))
        }
    }

    /**
     * 拉取回执落状态（文件/目录两链共用）：成功回执除进
     * [DeviceFileBrowserState.pull] 外同时登记进产物会话缓存——落点已有缓存行
     * 时**原位替换**该行（同源重拉覆盖刷新，防两行同路径互冒充；经
     * [resolvePullTargetFile] 占据落点的行恒同源），否则最近在上追加、超界
     * 淘汰最旧——衔接入口由产物列表行承载，拉 B 不丢 A；目录回执行的衔接
     * 钮缺席 by design（契约见 [DeviceFilePullProduct]）。
     */
    private fun settlePullOutcome(
        entry: DeviceFileEntry,
        sourceSerial: String,
        sourceDevicePath: String,
        result: Result<out DevicePullReceipt>,
    ) {
        updateState { st ->
            result.fold(
                onSuccess = { receipt ->
                    val product = DeviceFilePullProduct(entry.name, receipt, sourceSerial, sourceDevicePath)
                    val settled = st.pullProducts
                        .indexOfFirst { it.receipt.localPath == receipt.localPath }
                        .takeIf { it >= 0 }
                        ?.let { index -> st.pullProducts.toMutableList().also { products -> products[index] = product } }
                        ?: (listOf(product) + st.pullProducts).take(PULL_PRODUCT_CACHE_LIMIT)
                    st.copy(
                        pull = DeviceFilePullUiState.Done(entry.name, receipt),
                        pullProducts = settled,
                        pullProductError = null,
                    )
                },
                onFailure = { e ->
                    st.copy(pull = DeviceFilePullUiState.Failed(entry.name, e.message ?: "拉取失败（未知错误）"))
                },
            )
        }
    }

    /** 取消在途拉取（无在途为无动作；取消后内核落取消回执并清理临时文件） */
    fun cancelPull() {
        puller.requestCancel()
    }

    /**
     * 发起推送（面板文件选择器的消费入口）：本地文件 → 设备当前目录（设备文件名 =
     * 本地文件名）。设备目标已存在时经 [DeviceFilePushUiState.ConfirmPending] 等
     * 用户裁决（确认才覆盖，不静默覆盖）；本地源缺席/在途推送中再点被拒（推送内核
     * 单飞行），busy 即时上屏（期望大小 = 本地文件真值）。
     */
    fun requestPush(localPath: String) {
        if (_state.value.push is DeviceFilePushUiState.Busy) return
        val local = File(localPath)
        if (!local.exists() || !local.isFile) {
            updateState {
                it.copy(
                    push = DeviceFilePushUiState.Failed(
                        pathFileName(localPath),
                        "本地文件不存在或不是普通文件：$localPath",
                    ),
                )
            }
            return
        }
        val serial = loadedSerial ?: resolveTargetSerial()
        if (serial == null) {
            updateState { it.copy(error = targetBlockedReceipt()) }
            return
        }
        push(local, stack.last(), serial, overwrite = false)
    }

    /**
     * 确认覆盖推送（[DeviceFilePushUiState.ConfirmPending] 的确认分支）：跳过
     * 存在预检直接推送（用户已裁决覆盖）；取消分支 = [dismissPushConfirm] 零动作。
     */
    fun confirmPushOverwrite() {
        val pending = _state.value.push as? DeviceFilePushUiState.ConfirmPending ?: return
        val serial = loadedSerial ?: resolveTargetSerial()
        if (serial == null) {
            updateState {
                it.copy(
                    push = DeviceFilePushUiState.Failed(pending.fileName, "目标设备已不可用（断开或未选定），覆盖推送未执行"),
                )
            }
            return
        }
        push(File(pending.localPath), pending.deviceDir, serial, overwrite = true)
    }

    /** 取消覆盖确认（零动作回 Idle——设备侧目标未被触碰） */
    fun dismissPushConfirm() {
        updateState { it.copy(push = DeviceFilePushUiState.Idle) }
    }

    /** 取消在途推送（无在途为无动作；取消后内核落取消回执并明示设备侧残留面） */
    fun cancelPush() {
        pusher.requestCancel()
    }

    /**
     * 单次推送执行体：busy 落位 → 内核回执回写。内核的存在拒绝是类型化异常
     * （[DevicePushTargetExistsException]），本层据其转 [DeviceFilePushUiState.ConfirmPending]
     * （确认对话数据源），其余失败人话原样进 [DeviceFilePushUiState.Failed]。
     */
    private fun push(local: File, deviceDir: String, serial: String, overwrite: Boolean) {
        updateState { it.copy(push = DeviceFilePushUiState.Busy(local.name, local.length())) }
        viewModelScope.launch {
            val result = pusher.pushFile(local.absolutePath, deviceDir, serial, overwrite)
            updateState { st ->
                st.copy(
                    push = result.fold(
                        onSuccess = { receipt -> DeviceFilePushUiState.Done(local.name, receipt) },
                        onFailure = { e ->
                            if (e is DevicePushTargetExistsException) {
                                DeviceFilePushUiState.ConfirmPending(local.absolutePath, local.name, deviceDir, local.length())
                            } else {
                                DeviceFilePushUiState.Failed(local.name, e.message ?: "推送失败（未知错误）")
                            }
                        },
                    ),
                )
            }
        }
    }

    /**
     * 拉取产物的工作台衔接目标（产物语义按后缀分立查表）：`.xlog`/`.alog` →
     * XLog 解密；`.txt`/无后缀 → Tag 过滤；其余后缀无衔接（null = 面板不出条目）。
     */
    fun nextToolTargetForPullProduct(fileName: String): TaskPipelineHandoff.NextToolTarget? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "xlog", "alog" ->
                TaskPipelineHandoff.nextToolTargetOf(TaskPipelineHandoff.SourcePageId.DEVICE_FILE_PULL_ENCRYPTED)
            "txt", "" ->
                TaskPipelineHandoff.nextToolTargetOf(TaskPipelineHandoff.SourcePageId.DEVICE_FILE_PULL)
            else -> null
        }
    }

    /**
     * 产物行「送分析」衔接（目标经 [nextToolTargetForPullProduct] 查表复用）：
     * 产物文件已被外部删除/移动时落 [DeviceFileBrowserState.pullProductError]
     * 人话回执（不静默、不投递）；文件在位才投递 [TaskPipelineHandoff]。
     * 目录产物无衔接动作（[nextToolTargetForPullProduct] 的后缀查表只对单文件
     * 产物成立——目录名后缀不承载文件语义；面板无衔接钮，本守卫兜底）。
     */
    fun handoffPullProduct(product: DeviceFilePullProduct) {
        if (product.receipt !is DeviceFilePullReceipt) return
        val target = nextToolTargetForPullProduct(product.fileName)
        if (target == null || !TaskPipelineHandoff.isWired) return
        missingPullProductReceipt(product)?.let { receipt ->
            updateState { it.copy(pullProductError = receipt) }
            return
        }
        updateState { it.copy(pullProductError = null) }
        TaskPipelineHandoff.offer(target, product.receipt.localPath)
    }

    /**
     * 产物行「查看」直达应用内查看器：经 [LogViewerRequest] 写槽、宿主根叠层挂载
     * 查看 Dialog（观测页与查看器同宿主，直调即挂载）。加密件（.alog/.xlog）与
     * 打开失败面由查看器 Failed 态自带的解密引导承接，本层零文件形态判断。
     * 产物被外部删除/移动时落 [DeviceFileBrowserState.pullProductError] 人话回执
     * （不静默、不投递）。目录产物无查看动作（面板动作门 [isViewablePullProduct]
     * + 本守卫兜底——目录不是查看器的单文件输入载体，与衔接查表同口径）。
     */
    fun viewPullProduct(product: DeviceFilePullProduct) {
        if (product.receipt !is DeviceFilePullReceipt) return
        missingPullProductReceipt(product, "查看")?.let { receipt ->
            updateState { it.copy(pullProductError = receipt) }
            return
        }
        updateState { it.copy(pullProductError = null) }
        LogViewerRequest.offer(product.receipt.localPath)
    }

    /**
     * 产物行「打开所在文件夹」（文件管理器侧自定位）：动作透传产物本地路径给
     * [folderOpener]（生产 = [DesktopUtils.openInFolder] 打开产物父目录——文件/
     * 目录产物统一「所在文件夹」= 父目录）。产物被外部删除/移动时落人话回执
     * 零执行（与查看/衔接/预览同一存在性闸）；目录产物本动作可用（目录行无查看
     * 与衔接钮，打开所在文件夹不缺席）。
     */
    fun openPullProductFolder(product: DeviceFilePullProduct) {
        missingPullProductReceipt(product, "打开所在文件夹")?.let { receipt ->
            updateState { it.copy(pullProductError = receipt) }
            return
        }
        updateState { it.copy(pullProductError = null) }
        folderOpener(product.receipt.localPath)
    }

    /**
     * 产物行「预览图片」的存在性闸：false = 产物已被外部删除/移动（人话回执已落
     * [DeviceFileBrowserState.pullProductError]，面板不展开预览）；true = 文件在位
     * 可展开。
     */
    fun requestPullProductPreview(product: DeviceFilePullProduct): Boolean {
        missingPullProductReceipt(product)?.let { receipt ->
            updateState { it.copy(pullProductError = receipt) }
            return false
        }
        return true
    }

    /**
     * 产物缺席人话回执（null = 产物在位）：会话缓存的产物行只保回执快照，
     * 产物可被外部工具删除/移动——缺席时产物行动作（衔接/预览/查看/打开所在
     * 文件夹）不得静默失效。[actionLabel] 融进回执尾句（点名未执行的动作）。
     * 在位判据按回执形态分立（文件 = isFile，目录 = isDirectory）。
     */
    private fun missingPullProductReceipt(
        product: DeviceFilePullProduct,
        actionLabel: String = "衔接动作",
    ): String? {
        val local = File(product.receipt.localPath)
        val present = if (product.receipt is DeviceDirectoryPullReceipt) local.isDirectory else local.isFile
        if (present) return null
        return "「${product.fileName}」的本地产物已不在原位（被外部删除或移动）：" +
            "${product.receipt.localPath}——${actionLabel}未执行"
    }

    /**
     * 目标变更消费：串号实变 → 栈重置回根并重载（读取在 [load] 内异步落）；
     * 目标从有到无 → 清读数回执失效原因（不重置导航栈——栈结构无害，重选设备
     * 后照常重置）。
     */
    private fun onTargetChanged(target: String?) {
        if (target != null && target != loadedSerial) {
            loadedSerial = target
            stack.clear()
            stack.add(DEVICE_FILE_BROWSER_ROOT)
            load(DEVICE_FILE_BROWSER_ROOT, target, commitNavigation = null)
        } else if (target == null && loadedSerial != null) {
            loadedSerial = null
            updateState { it.copy(entries = null, error = "目标设备已不可用（断开或未选定），目录读数已失效") }
        }
    }

    /**
     * 单次装载请求：装载期置 [DeviceFileBrowserState.loading]（面板据此禁交互），
     * 完成后按结果回写——成功提交导航（[commitNavigation] 非空时）并更新条目；
     * 失败停在原目录只落回执。请求序号作废检查在回写前（见类头竞态收束）。
     */
    private fun load(path: String, serial: String, commitNavigation: (() -> Unit)?) {
        val seq = ++requestSeq
        updateState { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = listDirectory(path, serial)
            if (seq != requestSeq) return@launch
            if (result.isSuccess) {
                commitNavigation?.invoke()
                updateState {
                    it.copy(
                        path = stack.last(),
                        canNavigateUp = stack.size > 1,
                        entries = result.getOrThrow(),
                        loading = false,
                        error = null,
                    )
                }
            } else {
                updateState {
                    it.copy(loading = false, error = result.exceptionOrNull()?.message ?: "目录读取失败（未知错误）")
                }
            }
        }
    }

    /**
     * 唯一状态写入点：统一重算过滤投影（[filterFileEntries]）——查询词/条目列表
     * 两者任何之一变更都收敛到同一管道，防投影漏算。过滤是纯显示层投影，本写入
     * 点不派发任何内核请求。
     */
    private fun updateState(transform: (DeviceFileBrowserState) -> DeviceFileBrowserState) {
        _state.update { current ->
            val next = transform(current)
            next.copy(filtered = filterFileEntries(next.entries, next.query))
        }
    }

    /**
     * 目标不可用回执（与 [perfPanelHint] 同因不同载体：本回执经状态流进面板
     * 错误槽，面板另有实时 hint 横幅——本函数覆盖动作时刻的同步拒绝）。
     */
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

    /**
     * 子目录路径拼接：父路径去尾斜杠 + `/` + 名（POSIX 文件名不含 `/`，ls 解析层
     * 已剔除 `.`/`..` 条目——无路径逃逸面）。
     */
    private fun joinChildPath(parent: String, name: String): String =
        parent.trimEnd('/') + "/" + name
}
