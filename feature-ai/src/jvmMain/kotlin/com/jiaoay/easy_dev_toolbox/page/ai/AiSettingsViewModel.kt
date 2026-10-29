package com.jiaoay.easy_dev_toolbox.page.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.message.Message
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig

/**
 * AI 设置页状态：LLM 端点配置 + 连通性测试。
 *
 * - 配置恢复：init 一次性挂起读全量回填（XLogDecoderViewModel 同款）；
 * - 提交语义：CommitTextField 失焦提交 → 比较当前 StateFlow 值短路（未变不落盘）
 *   → 更新 StateFlow + 异步落盘（blur 提交低频，无需 debounce）；
 * - 连通性测试：用 [MultiLLMPromptExecutor.execute]（普通 completion，能力要求最低——
 *   部分 OpenAI 兼容端点对 JSON-mode 支持不齐，判型失败不该误报「连不通」），
 *   超时 30s 封顶（探测不该挂满用户配置的 60s 默认值）；
 * - 记忆面板数据根：与 agent 装配
 *   （DecryptTools.buildToolRegistry 的 memoryDir）同一条路径——面板与 agent
 *   memory.* 工具写的是同一批文件（[com.jiaoay.easy_dev_toolbox.core.llm.tools.MemoryManager]）。
 */
class AiSettingsViewModel : ViewModel() {

    private val _config = MutableStateFlow(LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60))
    val config: StateFlow<LlmEndpointConfig> = _config.asStateFlow()

    /**
     * 记忆面板数据根：`~/.toolbelt/memory`——与 MemoryManager/
     * agent memory.* 工具装配同一路径真源（本页是唯一管理面）。
     */
    val memoryDir: java.io.File =
        com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getMemoryDir()

    // ── 源码目录 allowlist（桌面链专属，桥接链恒不消费）──

    private val _sourceRoots = MutableStateFlow<List<String>>(emptyList())
    /** 源码根目录列表（UI 展示序 = 存储序；读经 LlmConfigStore 单点，不本地缓存写） */
    val sourceRoots: StateFlow<List<String>> = _sourceRoots.asStateFlow()

    init {
        // 启动回填（与 config 同一模式；后续改动整表提交）
        viewModelScope.launch {
            _sourceRoots.value = LlmConfigStore.getSourceRoots()
        }
    }

    /** 源码目录整表提交（增/删后调用；下一任务粒度即生效——桌面链任务启动时现读） */
    fun setSourceRoots(roots: List<String>) {
        viewModelScope.launch {
            LlmConfigStore.setSourceRoots(roots)
            _sourceRoots.value = roots
        }
    }

    private val _embeddingModelId = MutableStateFlow("")
    val embeddingModelId: StateFlow<String> = _embeddingModelId.asStateFlow()

    internal var probeExecutorFactory: (LlmEndpointConfig) -> Pair<MultiLLMPromptExecutor, ai.koog.prompt.llm.LLModel> =
        { cfg -> LlmClientFactory.executor(cfg) to LlmClientFactory.model(cfg.modelId) }

    private val _connectivity = MutableStateFlow<ConnectivityProbe>(ConnectivityProbe.Idle)
    val connectivity: StateFlow<ConnectivityProbe> = _connectivity.asStateFlow()

    private val _modelCatalog = MutableStateFlow<ModelCatalog>(ModelCatalog.Idle)
    val modelCatalog: StateFlow<ModelCatalog> = _modelCatalog.asStateFlow()

    /** 展开模型选择列表（用户点「浏览」后为 true；选中或失焦后收起） */
    private val _pickerExpanded = MutableStateFlow(false)
    val pickerExpanded: StateFlow<Boolean> = _pickerExpanded.asStateFlow()

    /** AI 工具总开关（真源是 [AiToolsToggle]；此处镜像为 VM 状态供 UI 渲染） */
    val aiEnabled: StateFlow<Boolean> = AiToolsToggle.enabled

    fun setAiToolsEnabled(enabled: Boolean) {
        if (!enabled) {
            // 拨关 = 作废当前探测代 + 取消在途协程：探测最长 30s，迟到成功不得
            // 反向覆写 OFF（代号守卫兜底「取消未及时观察」的竞速）
            probeGeneration++
            probeJob?.cancel()
            probeJob = null
            _aiToggleFeedback.value = AiToggleFeedback.Idle
            viewModelScope.launch { AiToolsToggle.setEnabled(false) }
            return
        }
        val cfg = _config.value
        if (!cfg.isConfigured) {
            _aiToggleFeedback.value = AiToggleFeedback.Failed("请先填写 API Key 与模型 ID，再开启 AI 工具")
            return
        }
        if (probeJob?.isActive == true) return

        _aiToggleFeedback.value = AiToggleFeedback.Probing
        // 意图代号：探测发起时快照——成功回执只在「仍是本代意图」时落开关
        val generation = ++probeGeneration
        probeJob = viewModelScope.launch {
            val (executor, model) = probeExecutorFactory(cfg)
            val result = runConnectivityProbe(cfg, executor, model)
            when (result) {
                is ConnectivityProbe.Ok -> {
                    _connectivity.value = result
                    if (generation == probeGeneration) {
                        _aiToggleFeedback.value = AiToggleFeedback.Opened
                        AiToolsToggle.setEnabled(true)
                    }
                }
                is ConnectivityProbe.Failed -> {
                    _connectivity.value = result
                    if (generation == probeGeneration) {
                        _aiToggleFeedback.value = AiToggleFeedback.Failed(result.message)
                    }
                }
                else -> Unit
            }
        }
    }

    /** 探测协程单句柄 + 意图代号（再入判据/拨关取消/迟到回执不覆写新意图） */
    private var probeJob: kotlinx.coroutines.Job? = null
    private var probeGeneration = 0L

    /** 开关开时校验的反馈状态机（探测中 / 已开启 / 失败原因） */
    private val _aiToggleFeedback = MutableStateFlow<AiToggleFeedback>(AiToggleFeedback.Idle)
    val aiToggleFeedback: StateFlow<AiToggleFeedback> = _aiToggleFeedback.asStateFlow()

    // ── 模型 ID 输入形态（下拉为主 + 手动兜底） ──

    private val _manualMode = MutableStateFlow(false)
    val manualMode: StateFlow<Boolean> = _manualMode.asStateFlow()

    /** 用户点「手动输入模型 ID」→ 切到文本框形态 */
    fun onManualInput() {
        _manualMode.value = true
        _pickerExpanded.value = false
    }

    /** 手动提交非空值后回落下拉形态；空值保持手动模式（允许先清空再想） */
    fun onManualModelCommitted(value: String) {
        onModelIdChange(value)
        if (value.isNotBlank()) _manualMode.value = false
    }

    init {
        viewModelScope.launch {
            val saved = LlmEndpointConfig.read() // S4 单点构造（原四行样板）
            _config.value = saved
            // 向量模型回填（与 chat 配置同读同恢复）
            _embeddingModelId.value = LlmConfigStore.getEmbeddingModelId()
            // 性格模式/安全约束回填（Koog agent 全局配置）
            _agentStyleId.value = LlmConfigStore.getAgentStyleId()
            _agentStyleCustom.value = LlmConfigStore.getAgentStyleCustom()
            _agentSafetyId.value = LlmConfigStore.getAgentSafetyId()
            _agentSafetyCustom.value = LlmConfigStore.getAgentSafetyCustom()
        }
    }

    // ── 配置提交（失焦比较短路） ──

    fun onBaseUrlChange(value: String) = updateConfig(
        transform = { it.copy(baseUrl = value.trim()) },
        persist = { LlmConfigStore.setBaseUrl(it.baseUrl) },
    )

    fun onApiKeyChange(value: String) = updateConfig(
        transform = { it.copy(apiKey = value.trim()) },
        persist = { LlmConfigStore.setApiKey(it.apiKey) },
    )

    fun onModelIdChange(value: String) = updateConfig(
        transform = { it.copy(modelId = value.trim()) },
        persist = { LlmConfigStore.setModelId(it.modelId) },
    )

    /** 选中模型列表中某项：写回配置并收起列表 */
    fun onModelPicked(id: String) {
        _pickerExpanded.value = false
        _manualMode.value = false
        onModelIdChange(id)
    }

    fun setPickerExpanded(expanded: Boolean) {
        _pickerExpanded.value = expanded
    }

    fun onTimeoutChange(value: Int) = updateConfig(
        transform = { it.copy(timeoutSeconds = value) },
        persist = { LlmConfigStore.setTimeoutSeconds(it.timeoutSeconds) },
    )

    /** 向量模型提交（失焦比较短路——与 chat 配置同款提交语义，独立 StateFlow 独立落盘） */
    fun onEmbeddingModelChange(value: String) {
        val trimmed = value.trim()
        val current = _embeddingModelId.value
        if (trimmed == current) return
        _embeddingModelId.value = trimmed
        viewModelScope.launch { LlmConfigStore.setEmbeddingModelId(trimmed) }
    }

    // ── Koog agent 性格模式与安全约束（独立 StateFlow 镜像 embedding 先例；
    //    不进 _config/LlmEndpointConfig——那是 executor 缓存 key，掺字段会无谓失效重建）──

    private val _agentStyleId = MutableStateFlow("default")
    val agentStyleId: StateFlow<String> = _agentStyleId.asStateFlow()

    private val _agentStyleCustom = MutableStateFlow("")
    val agentStyleCustom: StateFlow<String> = _agentStyleCustom.asStateFlow()

    private val _agentSafetyId = MutableStateFlow("default")
    val agentSafetyId: StateFlow<String> = _agentSafetyId.asStateFlow()

    private val _agentSafetyCustom = MutableStateFlow("")
    val agentSafetyCustom: StateFlow<String> = _agentSafetyCustom.asStateFlow()

    /** 性格模式档切换（选 custom 之外回落正文预览 UI；StateFlow 先更新再异步落盘） */
    fun onAgentStyleIdChange(id: String) {
        if (id == _agentStyleId.value) return
        _agentStyleId.value = id
        viewModelScope.launch { LlmConfigStore.setAgentStyleId(id) }
    }

    /** 自定义性格正文提交（仅 custom 档生效；留空 = 不注入性格段） */
    fun onAgentStyleCustomChange(value: String) {
        val current = _agentStyleCustom.value
        if (value == current) return
        _agentStyleCustom.value = value
        viewModelScope.launch { LlmConfigStore.setAgentStyleCustom(value) }
    }

    /** 安全策略档切换 */
    fun onAgentSafetyIdChange(id: String) {
        if (id == _agentSafetyId.value) return
        _agentSafetyId.value = id
        viewModelScope.launch { LlmConfigStore.setAgentSafetyId(id) }
    }

    /** 自定义安全正文提交（仅 custom 档生效；留空 = 不注入安全约束段） */
    fun onAgentSafetyCustomChange(value: String) {
        val current = _agentSafetyCustom.value
        if (value == current) return
        _agentSafetyCustom.value = value
        viewModelScope.launch { LlmConfigStore.setAgentSafetyCustom(value) }
    }

    /** 失焦提交统一入口：值未变短路不落盘；变更则先更新 StateFlow 再异步持久化 */
    private inline fun updateConfig(
        transform: (LlmEndpointConfig) -> LlmEndpointConfig,
        crossinline persist: suspend (LlmEndpointConfig) -> Unit,
    ) {
        val current = _config.value
        val next = transform(current)
        if (next == current) return
        // 使用计数口径：保存配置（变更生效）= 一次真实使用
        ToolUsageSignal.report(ToolUsageSignal.PageId.AI_SETTINGS)
        _config.value = next
        viewModelScope.launch { persist(next) }
    }

    // ── 模型清单拉取 ──

    fun fetchModels() {
        val cfg = _config.value
        if (cfg.apiKey.isBlank()) {
            _modelCatalog.value = ModelCatalog.Failed("请先填写 API Key")
            return
        }
        if (_modelCatalog.value is ModelCatalog.Loading) return

        _modelCatalog.value = ModelCatalog.Loading
        _pickerExpanded.value = true
        viewModelScope.launch {
            val result = runCatching {
                LlmModelCatalog.fetch(LlmClientFactory.httpClient(cfg), cfg.baseUrl, cfg.apiKey)
            }
            _modelCatalog.value = result.fold(
                onSuccess = { ids ->
                    if (ids.isEmpty()) ModelCatalog.Failed("端点未返回任何模型") else ModelCatalog.Loaded(ids)
                },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // 拉取失败须落盘留凭据（不只进 UI 状态）
                    JLog.w(TAG) { "模型列表拉取失败：baseUrl=${cfg.baseUrl} err=${e.message?.take(200)}" }
                    ModelCatalog.Failed(e.message?.take(200) ?: "拉取失败：${e::class.simpleName}")
                },
            )
        }
    }

    // ── 连通性测试 ──

    fun testConnectivity() {
        val cfg = _config.value
        if (!cfg.isConfigured) {
            _connectivity.value = ConnectivityProbe.Failed("请先填写 API Key 与模型 ID")
            return
        }
        if (_connectivity.value is ConnectivityProbe.Running) return

        _connectivity.value = ConnectivityProbe.Running
        viewModelScope.launch {
            val result = runConnectivityProbe(cfg, LlmClientFactory.executor(cfg), LlmClientFactory.model(cfg.modelId))
            _connectivity.value = result
        }
    }

    /** 测试接缝：探测逻辑独立成顶层函数，单测注入 fake executor（零真实网络） */
    internal suspend fun runConnectivityProbe(
        config: LlmEndpointConfig,
        executor: MultiLLMPromptExecutor,
        model: ai.koog.prompt.llm.LLModel,
    ): ConnectivityProbe = withContext(Dispatchers.Default) {
        try {
            val response: Message.Assistant = withTimeout(PROBE_TIMEOUT_SECONDS.seconds) {
                executor.execute(
                    prompt("connectivity-probe") {
                        user("请只回复一个词：pong")
                    },
                    model,
                )
            }
            val reply = response.textContent().trim()
            ConnectivityProbe.Ok(modelId = config.modelId, replyPreview = reply.take(REPLY_PREVIEW_CHARS))
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // 探测失败须落盘留凭据（不只进 UI 状态；本函数是 internal 顶层缝，
            // TAG 经 companion 常量引用）
            JLog.w(TAG) { "连通性探测超时（${PROBE_TIMEOUT_SECONDS}s）：model=${config.modelId} baseUrl=${config.baseUrl}" }
            ConnectivityProbe.Failed("连接超时（${PROBE_TIMEOUT_SECONDS}s）——端点不可达或网络受限")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            JLog.w(TAG) { "连通性探测失败：model=${config.modelId} err=${e.message?.take(200) ?: e::class.simpleName}" }
            ConnectivityProbe.Failed(e.message?.take(200) ?: "连接失败：${e::class.simpleName}")
        }
    }

    companion object {
        /** 探测超时封顶 30s（首次探测不该挂满用户配置的默认超时） */
        internal const val PROBE_TIMEOUT_SECONDS = 30
        private const val REPLY_PREVIEW_CHARS = 40
        private const val TAG = "AiSettings"

        @Composable
        fun getViewModel(): AiSettingsViewModel {
            return viewModel { AiSettingsViewModel() }
        }
    }
}

/** 连通性探测状态机（渲染对齐 AdbCheckResult 模式：按钮 + 着色结果文本） */
sealed class ConnectivityProbe {
    data object Idle : ConnectivityProbe()
    data object Running : ConnectivityProbe()
    data class Ok(val modelId: String, val replyPreview: String) : ConnectivityProbe()
    data class Failed(val message: String) : ConnectivityProbe()
}

/** AI 总开关开时校验的反馈状态机（探测中 / 已开启 / 失败原因） */
sealed class AiToggleFeedback {
    data object Idle : AiToggleFeedback()
    data object Probing : AiToggleFeedback()
    data object Opened : AiToggleFeedback()
    data class Failed(val message: String) : AiToggleFeedback()
}

sealed class ModelCatalog {
    data object Idle : ModelCatalog()
    data object Loading : ModelCatalog()
    data class Loaded(val modelIds: List<String>) : ModelCatalog()
    data class Failed(val message: String) : ModelCatalog()
}
