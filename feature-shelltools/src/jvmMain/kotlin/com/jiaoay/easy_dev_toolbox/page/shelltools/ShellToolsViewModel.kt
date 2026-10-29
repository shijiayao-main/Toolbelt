package com.jiaoay.easy_dev_toolbox.page.shelltools

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter
import com.jiaoay.easy_dev_toolbox.entity.JResult
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ShellToolNodeConfig
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ToolNode
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.bodyContains
import com.jiaoay.easy_dev_toolbox.page.shelltools.history.ShellHistoryItem
import com.jiaoay.easy_dev_toolbox.page.shelltools.history.ShellHistoryManager
import com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom.CustomToolsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.device.DeviceWirelessConnector
import com.jiaoay.toolbelt.device.WirelessConnectorState

class ShellToolsViewModel(
    private val monitor: DeviceMonitor = DeviceHub.monitor,
    // 注入缝与 monitor 同款：连接器的目标解析消费同一设备三流单源，测试注入时
    // 与 monitor 成对注入保持解析面一致
    private val wirelessConnector: DeviceWirelessConnector = DeviceHub.wirelessConnector,
) : ViewModel() {

    // ── 设备域（:core 设备域 App 级单源的页面作用域消费壳） ──
    //
    // 检测/轮询/失效选定清除的生产逻辑在 [DeviceMonitor]（App 级单源）；
    // 本 VM 保留三流 + busy 面与操作转发的既有签名，页面消费面零改写。
    // 切页不销毁语义不变：单源的轮询循环随进程驻留，
    // 页面可见性经 [setPageVisible] 接线门控（不可见即跳过，spawn 频率等价）。

    /** 设备域三流窄接口：观测型消费方经此消费 :core 设备域，不依赖本壳 */
    val devices: DeviceStateSource get() = monitor

    val deviceState: StateFlow<DeviceState> get() = monitor.deviceState

    val detectError: StateFlow<String?> get() = monitor.detectError

    /**
     * 设备检测进行中（busy 态）：仅**用户可感知**的检测入口（手动刷新钮 +
     * 进页即刷）置位；轮询路径恒不置位。消费面：设备状态栏主行「（检测中…）」
     * 后缀 + 刷新钮禁用（连点防抖）。
     */
    val detecting: StateFlow<Boolean> get() = monitor.detecting

    val selectedSerial: StateFlow<String?> get() = monitor.selectedSerial

    /**
     * 一键转无线快照流（直通 [DeviceWirelessConnector] 单源）：设备栏动作钮的
     * 在态防抖（connecting）与回执/拒绝事件（eventSeq 去重键）均出自本流。
     */
    val wirelessState: StateFlow<WirelessConnectorState> get() = wirelessConnector.state

    /**
     * 发起一键转无线（设备栏动作钮入口）：目标解析与三步编排由连接器单源承载，
     * 回执/拒绝经 [WirelessConnectorState] 落地、toast 由页面组合期消费。
     */
    fun startWirelessConnect() {
        wirelessConnector.startWirelessConnect()
    }

    /** 自定义 adb 可执行文件路径缓存（空串 = 系统 PATH 中的 adb）；命令重写消费 */
    private val adbExecutablePath: String get() = monitor.endpoint.executablePath

    /** 页面可见性门控接线（internal 供页面组合生命周期与单测锚） */
    internal fun setPageVisible(visible: Boolean) {
        monitor.setPageVisible(visible)
    }

    /** 选定目标设备（设备选择弹窗确认后写入），此后本页命令注入 `adb -s <serial>` */
    fun selectDevice(serial: String) {
        monitor.selectDevice(serial)
    }

    /** 清除设备选择：命令不再注入 `-s`，状态栏回到"选择设备"入口 */
    fun clearDeviceSelection() {
        monitor.clearDeviceSelection()
    }

    /**
     * 手动刷新设备（刷新钮 + 进页即刷——用户可感知入口，置 busy）。
     */
    fun refreshDevices() {
        monitor.refreshDevices()
    }

    /**
     * 设备列表解析结果应用到设备域状态（internal 供单测/场景注入锚），
     * 失效选定清除等语义由 [DeviceMonitor.applyDeviceList] 单源承载。
     */
    internal fun applyDeviceList(devices: List<DeviceInfo>) {
        monitor.applyDeviceList(devices)
    }

    /** 检测失败态应用到设备域状态（internal 供单测/场景注入锚，与 [applyDeviceList] 对称） */
    internal fun applyDetectError(message: String) {
        monitor.applyDetectError(message)
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── 子控制器 ──

    private val customToolsController = CustomToolsController()
    val toolNodeStateFlow: StateFlow<JResult<List<ToolNode>>?> get() = customToolsController.toolNodeStateFlow

    /** 主配置加载错误提示（回退内置默认配置时非空），供页面顶部 banner */
    val mainConfigError: StateFlow<String?> get() = customToolsController.mainConfigError

    // 历史管理器上提 App 级（ShellToolsRuntime.history）：底面板「输出」页签
    // 是全局层，手动输入栏须在页面切走后仍可写历史；单实例防双实例并发写。
    private val historyManager: ShellHistoryManager get() = ShellToolsRuntime.ensureHistoryStarted()
    val historyFlow: StateFlow<List<ShellHistoryItem>> get() = historyManager.historyFlow
    val historySortMode: StateFlow<ShellHistorySortMode> get() = historyManager.sortMode
    val displayHistory get() = historyManager.displayHistory

    /**
     * 树内行尾「×N 使用计数」查找表（树内行恒有 pill mute 计数）。键覆盖两类历史 id 语义：Normal 历史 id = 命令串（executeNormal
     * 以 command 写入——多节点同命令共享计数）、Chain 历史 id = 节点 id；
     * 渲染层按 `body.value` / `node.id` 双键查表（ShellToolsNode.Child.useCount 的数据源）。
     */
    val useCountByNodeId: StateFlow<Map<String, Int>> = historyFlow
        .map { items -> items.groupBy { it.id }.mapValues { (_, group) -> group.sumOf { it.useCount } } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    // ── 统一执行引擎（App 级单例，执行状态脱离页面生命周期） ──

    private val engine: ShellExecutionEngine get() = ShellToolsRuntime.engine

    val execOutput: StateFlow<OutputState> get() = engine.output
    val execStatus: StateFlow<ExecStatus> get() = engine.status
    val executingNodeIds: StateFlow<Set<String>> get() = engine.activeKeys

    // ── 历史多选 ──

    private val _historySelectionMode = MutableStateFlow(false)
    val historySelectionMode: StateFlow<Boolean> = _historySelectionMode.asStateFlow()

    private val _historySelectedIds = MutableStateFlow<Set<String>>(emptySet())
    val historySelectedIds: StateFlow<Set<String>> = _historySelectedIds.asStateFlow()

    init {
        // P2：history start 收口 Runtime（幂等）；VM 只触发惰性启动
        ShellToolsRuntime.ensureHistoryStarted()
        // 搜索过滤回放钩子：fetch 重建节点后按非空 query 重放过滤（滤网单源——
        // 与 onSearchQueryChange 共用 searchFilterTransform，防「框有词、树全量」漂移）
        customToolsController.nodeTransform = { nodes ->
            searchFilterTransform(nodes, _searchQuery.value)
        }
    }

    // ── 配置加载 ──

    fun fetchShellToolsConfig() {
        viewModelScope.launch {
            customToolsController.fetchShellToolsConfig()
        }
    }

    // ── 设备域消费（检测链路在 :core 设备域单源） ──

    companion object {
        private const val MANUAL_LABEL_MAX_LEN = 40

        @Composable
        fun getViewModel(): ShellToolsViewModel = viewModel { ShellToolsViewModel() }
    }

    // ── 搜索过滤 ──

    /** 搜索前展开状态快照（query 由空变非空时记录，清空搜索时回填恢复） */
    private var expandedSnapshotBeforeSearch: Map<String, Boolean>? = null

    /**
     * 搜索过滤映射（纯函数，滤网单源）：query 空 = 恒等（不碰树）；非空 = 父名命中
     * 全部子可见 / 否则仅命中子可见。onSearchQueryChange 与 controller.nodeTransform
     * 共用本函数——两处各写一份必漂移。
     */
    private fun searchFilterTransform(nodes: List<ToolNode>, query: String): List<ToolNode> =
        if (query.isEmpty()) {
            nodes
        } else {
            nodes.map { node ->
                when (node) {
                    is ToolNode.Parent -> {
                        val parentMatch = node.name.contains(query, ignoreCase = true)
                        val childMatches = node.children.map { child ->
                            child is ToolNode.Child &&
                                (child.name.contains(query, ignoreCase = true) || bodyContains(child.body, query))
                        }
                        val anyChildMatch = childMatches.any { it }
                        val groupVisible = parentMatch || anyChildMatch
                        node.copy(
                            isVisible = groupVisible,
                            isExpanded = if (groupVisible) true else node.isExpanded,
                            children = node.children.mapIndexed { index, child ->
                                if (child is ToolNode.Child) {
                                    // 父名命中 → 全部子节点可见；否则仅命中子节点可见
                                    child.copy(isVisible = parentMatch || childMatches[index])
                                } else {
                                    child
                                }
                            },
                        )
                    }

                    // 顶层游离 Child（防御性分支，正常配置不出现）：按名称与命令内容过滤
                    is ToolNode.Child -> node.copy(
                        isVisible = node.name.contains(query, ignoreCase = true) || bodyContains(node.body, query)
                    )
                }
            }
        }

    fun onSearchQueryChange(query: String) {
        val wasEmpty = _searchQuery.value.isEmpty()
        val nowEmpty = query.isEmpty()

        // query 由空变非空：对全部 Parent 的 isExpanded 做快照（清空时回填恢复）
        if (wasEmpty && !nowEmpty) {
            expandedSnapshotBeforeSearch = collectExpandedSnapshot()
        }

        _searchQuery.value = query

        if (nowEmpty) {
            // 清空搜索：恢复完整树 + 回填展开态快照
            val snapshot = expandedSnapshotBeforeSearch
            expandedSnapshotBeforeSearch = null
            customToolsController.updateNodeList { node ->
                when (node) {
                    is ToolNode.Parent -> node.copy(
                        isVisible = true,
                        isExpanded = snapshot?.get(node.id) ?: node.isExpanded,
                        children = node.children.map { child ->
                            if (child is ToolNode.Child) child.copy(isVisible = true) else child
                        },
                    )

                    is ToolNode.Child -> node.copy(isVisible = true)
                }
            }
            return
        }

        // 非空过滤：走滤网单源（逐节点语义与 searchFilterTransform 等价）
        customToolsController.updateNodeList { node ->
            searchFilterTransform(listOf(node), query).first()
        }
    }

    /** 当前全部 Parent 的展开状态快照（搜索开始前记录） */
    private fun collectExpandedSnapshot(): Map<String, Boolean> {
        val data = customToolsController.toolNodeStateFlow.value
        val nodes = (data as? JResult.Success)?.data ?: return emptyMap()
        return nodes.filterIsInstance<ToolNode.Parent>().associate { it.id to it.isExpanded }
    }

    // ── 父节点展开/折叠 ──

    fun toggleParentExpanded(parentId: String) {
        customToolsController.toggleParentExpanded(parentId)
    }

    // ── 手动输入执行 ──

    fun submitManualCommand(raw: String) {
        val commands = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (commands.isEmpty()) return
        // 写入历史（多行粘贴时逐条记录）
        commands.forEach { cmd ->
            historyManager.add(
                ShellHistoryItem.Normal(
                    id = cmd,
                    displayLabel = cmd.take(MANUAL_LABEL_MAX_LEN),
                    command = cmd,
                )
            )
        }
        engine.submit(
            QueuedCommand(
                label = commands.first().take(MANUAL_LABEL_MAX_LEN),
                commands = rewriteCommands(commands),
                trackingKey = null,
                // 手动命令默认 300s 超时（封死长跑命令内存无界增长），停止按钮仍可提前终止
                timeoutMs = ShellExecutionEngine.MANUAL_COMMAND_TIMEOUT_MS,
                originalCommands = commands,
            )
        )
    }

    // ── 引擎操作 ──

    fun stopExecution() = engine.stop()

    fun clearOutput() = engine.clearOutput()

    /**
     * 重试最近一次失败任务（从失败步继续）：按【当前】adb 路径/串号重写原串后重新提交，
     * 超时口径与节点追踪键随原任务保真——重试是 UI 引导的恢复旅程终点，必须走统一
     * 重写层（直连引擎会把旧串号/旧路径永久烘进重试）。
     */
    fun retryExecution() {
        val failed = engine.status.value as? ExecStatus.Failed ?: return
        val originals = failed.originalCommands.ifEmpty { failed.commands }
        val startIndex = failed.failedStepIndex.coerceAtLeast(0)
        if (startIndex >= originals.size) return
        engine.submit(
            QueuedCommand(
                label = failed.label,
                commands = rewriteCommands(originals),
                trackingKey = failed.trackingKey,
                timeoutMs = failed.timeoutMs,
                originalCommands = originals,
                startIndex = startIndex,
            )
        )
    }

    /** 面板侧追加状态提示行（如导出失败提示），走引擎统一输出聚合链路 */
    fun appendOutputStatusLine(content: String) = engine.appendStatusLine(content)

    // ── 普通节点执行 ──

    fun executeNormal(nodeId: String, nodeName: String, command: String) {
        historyManager.add(
            ShellHistoryItem.Normal(
                id = command,
                displayLabel = nodeName,
                command = command,
            )
        )
        engine.submit(buildQueuedCommand(nodeName, listOf(command), trackingKey = nodeId))
    }

    // ── 历史记录回放（Normal / MultiSelect / Input 统一入口）──

    fun executeHistoryNormal(item: ShellHistoryItem.Normal) {
        // 回放 = 一次真实使用：补写计数（同 id 去重合并——useCount+1、Recent 浮顶），
        // 与 executeNormal / executeChain / 批量回放同语义；漏写会让最常用回放动线
        // 的 ×N 计数与排序静默失效
        historyManager.add(item)
        engine.submit(
            buildQueuedCommand(item.displayLabel, listOf(item.command), trackingKey = item.id)
        )
    }

    // ── 多选节点执行 ──

    fun executeMultiSelectItem(
        executingKey: String,
        nodeName: String,
        command: String,
    ) {
        historyManager.add(
            ShellHistoryItem.MultiSelect(
                id = executingKey,
                displayLabel = nodeName,
                executingKey = executingKey,
                nodeName = nodeName,
                command = command,
            )
        )
        engine.submit(buildQueuedCommand(nodeName, listOf(command), trackingKey = executingKey))
    }

    // ── 输入节点执行 ──

    fun executeInput(
        nodeId: String,
        nodeName: String,
        template: String,
        params: Map<String, String>,
    ) {
        var command = template
        params.forEach { (key, value) ->
            command = command.replace("{$key}", value)
        }
        historyManager.add(
            ShellHistoryItem.Input(
                id = command,
                displayLabel = nodeName,
                command = command,
            )
        )
        engine.submit(buildQueuedCommand(nodeName, listOf(command), trackingKey = nodeId))
    }

    // ── 链式节点执行 ──

    fun executeChain(
        nodeId: String,
        nodeName: String,
        commands: List<String>,
        startIndex: Int = 0
    ) {
        if (startIndex == 0) {
            historyManager.add(
                ShellHistoryItem.Chain(
                    id = nodeId,
                    displayLabel = nodeName,
                    commands = commands,
                    nodeId = nodeId,
                )
            )
        }
        engine.submit(
            buildQueuedCommand(
                nodeName,
                commands.drop(startIndex),
                trackingKey = nodeId,
                timeoutMs = ShellExecutionEngine.CHAIN_STEP_TIMEOUT_MS,
            )
        )
    }

    // ── 历史多选批量执行 ──

    fun toggleHistorySelectionMode() {
        _historySelectionMode.value = !_historySelectionMode.value
        if (!_historySelectionMode.value) _historySelectedIds.value = emptySet()
    }

    fun toggleHistorySelection(id: String) {
        _historySelectedIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun clearHistorySelection() {
        _historySelectedIds.value = emptySet()
    }

    /**
     * 串行执行选中历史项（按当前展示顺序展开为命令列表）。
     *
     * 执行同样写入历史：对选中条目按展示顺序逐条 [ShellHistoryManager.add]
     * （复用同 id 去重合并语义——useCount 累计、lastUsedAt 刷新、移至队首，不产生重复条目）。
     */
    fun executeSelectedHistory() {
        val selectedIds = _historySelectedIds.value
        if (selectedIds.isEmpty()) return
        val orderedItems = displayHistory.value.filter { it.id in selectedIds }
        // 批量执行补写历史：按展示顺序逐条 add（去重合并），与单条执行入口语义一致
        orderedItems.forEach { item -> historyManager.add(item) }
        val commands = orderedItems.flatMap { item ->
            when (item) {
                is ShellHistoryItem.Normal -> listOf(item.command)
                is ShellHistoryItem.MultiSelect -> listOf(item.command)
                is ShellHistoryItem.Input -> listOf(item.command)
                is ShellHistoryItem.Chain -> item.commands
            }
        }
        if (commands.isEmpty()) return
        engine.submit(
            QueuedCommand(
                label = "串行执行选中项(${commands.size})",
                commands = rewriteCommands(commands),
                trackingKey = null,
                // 批量内容是任意历史命令（非链式短设备操作），30s/条会误杀 pull/install——
                // 用节点命令 300s 档
                timeoutMs = ShellExecutionEngine.NODE_COMMAND_TIMEOUT_MS,
                originalCommands = commands,
            )
        )
        // 批量入队反馈接 StatusSignals 全局 toast；toast 显展开后命令条数
        // （非选中条目数——Chain 展开多命令）
        StatusSignals.show("已入队 ${commands.size} 条命令")
        _historySelectedIds.value = emptySet()
        _historySelectionMode.value = false
    }

    // ── 自定义命令 / 分组 CRUD（统一经 ConfigRepository） ──

    /** 新建或更新自定义工具：编辑已有节点时 node.id 命中即原地替换（改名不重建） */
    fun upsertCustomTool(node: ShellToolNodeConfig, groupId: String? = null) {
        viewModelScope.launch {
            customToolsController.upsertTool(node, groupId)
        }
    }

    fun deleteCustomNode(id: String) {
        viewModelScope.launch {
            customToolsController.deleteTool(id)
        }
    }

    /** @param onResult true = 创建成功 */
    fun createCustomGroup(name: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(customToolsController.createGroup(name) != null)
        }
    }

    /** @param onResult true = 重命名成功（false = 分组不存在或名称被占用） */
    fun renameCustomGroup(id: String, newName: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(customToolsController.renameGroup(id, newName))
        }
    }

    /** @param onResult true = 删除成功（默认分组受保护返回 false） */
    fun deleteCustomGroup(id: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(customToolsController.deleteGroup(id))
        }
    }

    fun moveCustomTool(toolId: String, targetGroupId: String) {
        viewModelScope.launch {
            customToolsController.moveTool(toolId, targetGroupId)
        }
    }

    /** @param onResult 导入的工具数；-1 = JSON 解析失败 */
    fun importCustomJson(json: String, onResult: (Int) -> Unit = {}) {
        viewModelScope.launch {
            onResult(customToolsController.importJson(json))
        }
    }

    fun exportCustomJson(ids: List<String>, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(customToolsController.export(ids))
        }
    }

    // ── 历史记录 ──

    fun setHistorySortMode(mode: ShellHistorySortMode) {
        if (_historySelectionMode.value) {
            clearHistorySelection()
        }
        historyManager.setSortMode(mode)
    }

    /** 删除单条历史记录（立即生效，经既有防抖链路持久化） */
    fun removeHistoryItem(id: String) {
        historyManager.remove(id)
    }

    /** 清空全部历史记录（立即生效，经既有防抖链路持久化） */
    fun clearAllHistory() {
        historyManager.clear()
    }

    // ── 内部方法 ──

    private fun buildQueuedCommand(
        label: String,
        commands: List<String>,
        trackingKey: String?,
        timeoutMs: Long? = ShellExecutionEngine.NODE_COMMAND_TIMEOUT_MS,
    ): QueuedCommand {
        // 未连接执行预警（非阻断 toast，单点收口 buildQueuedCommand 五路径）：
        // adb 类命令在无设备/未选定时会必撞一轮「失败-排障」循环——提醒后可预期失败，
        // 命令照常提交（本地命令不需要设备，不能全量禁用）
        val first = commands.firstOrNull()?.trim().orEmpty()
        when {
            AdbCommandRewriter.isAdbCommand(first) && monitor.deviceState.value is DeviceState.None ->
                StatusSignals.show("未检测到设备——adb 命令可能执行失败（已照常提交）")

            AdbCommandRewriter.isAdbCommand(first) &&
                monitor.deviceState.value is DeviceState.Multi && monitor.selectedSerial.value == null ->
                StatusSignals.show("未选定目标设备——命令不注入 -s 直接执行")
        }
        return QueuedCommand(
            label = label,
            commands = rewriteCommands(commands),
            trackingKey = trackingKey,
            timeoutMs = timeoutMs,
            // 重写前原串随任务携带：失败重试必须按当前环境重写原串（重写产物不可复用）
            originalCommands = commands,
        )
    }

    private fun rewriteCommands(commands: List<String>): List<String> {
        val serial = monitor.selectedSerial.value
        return commands.map { AdbCommandRewriter.rewrite(it, adbExecutablePath, serial) }
    }

    // ── onCleared ──

    override fun onCleared() {
        super.onCleared()
        // 历史管理器上提 App 级后，onCleared 不再 flush/close——历史生命周期
        // 收口 ShellToolsRuntime（App 级单实例跨页面存活；退出落盘由
        // ShellToolsRuntime.shutdown 在 AppHost 退出序统一 join，确定性语义不变）。
    }
}
