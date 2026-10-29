package com.jiaoay.easy_dev_toolbox.page.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 外放工具四族：清单按族分节的分组维度——fs.extended 取证 /
 * knowledge 检索 / log 管线 / agent 会话（族序 = McpToolRegistrar.MCP_EXPOSE_NAMES
 * 注释序；节头文案随枚举内聚——页面零散改）。
 */
enum class ToolFamily(val sectionTitle: String) {
    FS_EXTENDED("fs.extended 取证"),
    KNOWLEDGE("knowledge 检索"),
    LOG("log 管线"),
    AGENT("agent 会话"),
}

/**
 * 名称前缀 → 工具族（单源映射）：tools 面新增 log.xxx 自动归 LOG 节、
 * 新增 fs.extended.xxx 自动归取证节——外放名集增删只改 MCP_EXPOSE_NAMES 侧，
 * 页面零散改（卡面验收）。
 */
private fun familyOf(name: String): ToolFamily = when {
    name.startsWith("fs.extended") -> ToolFamily.FS_EXTENDED
    name.startsWith("knowledge") -> ToolFamily.KNOWLEDGE
    name.startsWith("log") -> ToolFamily.LOG
    else -> ToolFamily.AGENT
}

/**
 * MCP 服务页 VM。
 *
 * ## 隔离纪律
 * 零 Ktor/SDK 类型（经 [McpServerManager] 门面消费——隔离面）；
 * VM 构造零网络/零 server 触达（init 只读配置 + 镜像 manager 状态流）。
 *
 * ## 状态面
 * - [serverStatus]：manager 状态镜像（运行态/端口/失败原因）；
 * - [enabled]/[port]：持久化配置态（LlmConfigStore）；
 * - [tools]：外放工具清单（描述符直读——名称/描述/风险/族，页面消费；族分节）。
 *
 * 开关翻转语义：持久化配置先行（写 key），再驱动 manager 即时启停；
 * 端口变更：运行中即时重启（停→按新端口起），未运行只落配置。
 */
class McpExposeViewModel : ViewModel() {

    private val TAG = "McpExposeViewModel"

    /** manager 状态镜像（页面状态卡数据源）。 */
    val serverStatus: StateFlow<McpServerManager.Status> = McpServerManager.status

    /** 服务开关持久化态。 */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 端口配置态（校验域 [LlmConfigStore.MCP_PORT_RANGE]——越界提交拒绝）。 */
    private val _port = MutableStateFlow(LlmConfigStore.DEFAULT_MCP_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    /** 外放工具清单（描述符直读——名称/描述/风险/族四级面；族分节）。 */
    data class ToolRow(
        val name: String,
        val description: String,
        val destructive: Boolean,
        val family: ToolFamily,
    )

    private val _tools = MutableStateFlow<List<ToolRow>>(emptyList())
    val tools: StateFlow<List<ToolRow>> = _tools.asStateFlow()

    /** agent 会话工具（MCP 专属面——非目录描述符派生，页面合并展示；显式 AGENT 族 = 既有分组语义）。 */
    private val agentToolRows = listOf(
        ToolRow(name = "workbench_analyze", description = "日志根因研判 agent（十铁律 + 工具取证，后台长流程）", destructive = false, family = ToolFamily.AGENT),
        ToolRow(name = "distill_run", description = "知识蒸馏 agent（审批前形态——候选不写库）", destructive = false, family = ToolFamily.AGENT),
        ToolRow(name = "workbench_status", description = "查询 agent 会话 run 的进度与结果", destructive = false, family = ToolFamily.AGENT),
    )

    /** 服务页日志台（manager 事件流——连接/调用/错误行，环形上限）。 */
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    /**
     * 启停操作在途标记：startNow 挂起探询端口
     * bind（阻塞段在 IO 线程）——期间开关置 busy 防重复触发 + 页面显示进行中反馈。
     */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    init {
        // 配置态回填（构造零 server 触达——只读 DataStore）
        viewModelScope.launch {
            runCatching {
                _enabled.value = LlmConfigStore.getMcpServerEnabled()
                _port.value = LlmConfigStore.getMcpServerPort()
            }.onFailure { JLog.e(TAG, it) { "MCP 配置读取失败" } }
        }
        // 工具清单（描述符直读——进程内静态，无 IO；族经名称前缀映射）
        _tools.value = AgentToolCatalog.all
            .filter { it.name in McpToolRegistrar.MCP_EXPOSE_NAMES }
            .map {
                ToolRow(
                    name = it.name,
                    description = it.uiHint?.description ?: it.description,
                    destructive = it.risk == AgentToolCatalog.Risk.DESTRUCTIVE,
                    family = familyOf(it.name),
                )
            } +
            agentToolRows
    }

    /** 开关翻转：配置先行 + manager 即时启停（失败面经 serverStatus 明示——不回滚开关，用户可关再开）。 */
    fun toggleEnabled(on: Boolean) {
        if (_switching.value) return
        _enabled.value = on
        viewModelScope.launch {
            _switching.value = true
            try {
                runCatching { LlmConfigStore.setMcpServerEnabled(on) }
                    .onFailure { JLog.e(TAG, it) { "MCP 开关配置写入失败" } }
                if (on) {
                    appendLog("开启服务（端口 ${_port.value}）…")
                    McpServerManager.startNow(_port.value)
                    val status = McpServerManager.status.value
                if (status.running) {
                    // 使用计数口径：服务启动成功 = 一次真实使用
                    ToolUsageSignal.report(ToolUsageSignal.PageId.MCP_EXPOSE)
                }
                appendLog(if (status.running) "端点就绪：${endpointUrl()}" else (status.detail ?: "启动失败"))
            } else {
                appendLog("关闭服务")
                McpServerManager.stopNow()
            }
            } finally {
                _switching.value = false
            }
        }
    }

    /** 端口提交（校验域内才写库 + 运行中即时重启）。 */
    fun commitPort(newPort: Int) {
        if (newPort !in LlmConfigStore.MCP_PORT_RANGE) return
        val wasRunning = McpServerManager.status.value.running
        _port.value = newPort
        viewModelScope.launch {
            runCatching { LlmConfigStore.setMcpServerPort(newPort) }
                .onFailure { JLog.e(TAG, it) { "MCP 端口配置写入失败" } }
            if (wasRunning) {
                appendLog("端口变更 → 重启服务（$newPort）")
                _switching.value = true
                try {
                    McpServerManager.stopNow()
                    McpServerManager.startNow(newPort)
                } finally {
                    _switching.value = false
                }
            } else {
                appendLog("端口已保存（$newPort，服务未运行）")
            }
        }
    }

    /** 端点 URL（运行态展示 + 一键复制）。 */
    fun endpointUrl(): String = "http://127.0.0.1:${_port.value}/mcp"

    /**
     * 在途 agent 会话数（状态卡观测面，响应式化）。
     *
     * 旧缺陷：`val activeRuns: Int get() = McpRunRegistry.activeCount` 是一次性 getter，
     * 页面只随其余 StateFlow 重组——外部 MCP client 起 run 后计数恒旧值不跟随。
     *
     * 新形态：runsVersion（一切在途数变化的入口自增——register/cancelAll/sweepFinished
     * /cancel 四入口 + run 三终态迁移）经 map 派生实时计数流——页面 collectAsState
     * 消费，run 增减即时重组。
     */
    val activeRunsFlow: StateFlow<Int> = McpRunRegistry.runsVersion
        .map { McpRunRegistry.activeCount }
        .stateIn(viewModelScope, SharingStarted.Eagerly, McpRunRegistry.activeCount)

    private fun appendLog(line: String) {
        // 日志行加 HH:mm:ss 前缀——stampLogLine 单点收口，
        // 开启/关闭/端口变更/端点就绪/失败五个事件出口全生效（既有文案不变）。
        val stamped = stampLogLine(line)
        _logLines.value = (_logLines.value + stamped).takeLast(LOG_CAPACITY)
    }

    companion object {
        // 服务日志卡头 hint「环形 200 行事件流」
        // 消费同一常量——单源防两处数值漂移，由 private 收窄改 public。
        const val LOG_CAPACITY = 200
    }
}

/** 服务日志时间戳格式：恒 HH:mm:ss 无日期。 */
private val logLineTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * 服务日志行加 HH:mm:ss 时间戳前缀：端口变更/启停/失败
 * 事件可与 JLog/audit 时间线对齐（design-language.md §4.2 时间戳惯例）。恒时间无日期
 * ——页内环形 200 行事件流语境（桥接页 BridgeStatusPanel 同款格式，单行格式器
 * 不值得抽件直写）。[time] 缺省当前时刻；显式参数 = 测试接 seam。
 */
internal fun stampLogLine(line: String, time: LocalTime = LocalTime.now()): String =
    "${time.format(logLineTimeFormatter)} $line"
