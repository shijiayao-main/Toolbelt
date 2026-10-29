package com.jiaoay.easy_dev_toolbox.page.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal

/**
 * AI 工具总开关的进程级真源（object 门面，先例 [LlmClientFactory]）：关闭后依赖 LLM/agent
 * 的注册页（工作台/语音/流程/蒸馏）置灰，AI 设置页恒可见（否则无法重新开启）。
 *
 * 🔴 写点必须同发镜像 [com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal]
 * （本类写点已收口同文件）——互不依赖的 feature 域经镜像直读实时开关。
 * [enabled] 经宿主 `ToolPageRegistry` copy 注入 `aiEnabled`（feature 互不依赖铁律，契约在 `:core`）。
 * 启动时序：持久化值经 `AiToolsRestoreInitializer`（高优 level 2）首帧前回填；
 * 🔴 回填前 [enabled] 恒 `false`（fail-closed，与持久层派生默认同向——读盘失败不得反向显示已启用）。
 * docs/pages/ai-settings.md、docs/pages/navigation.md「aiEnabled 宿主 copy 注入」详述。
 */
object AiToolsToggle {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    // 全部写点顺手镜像进 :core 中立槽 AiEnabledSignal——
    // feature-logworkbench/feature-voice 等互不依赖域直读该镜像即得实时开关，
    // 不再 init 一次性快照（陈旧快照 = 设置页开启后工作台仍降级的缺陷）。
    // 三个写点均在同文件（restore/setEnabled/测试缝），镜像结构性同步零遗漏面。

    /** 启动恢复（幂等：重复调用只是重读同一 key） */
    suspend fun restore() {
        _enabled.value = runCatching { LlmConfigStore.getAiEnabled() }.getOrDefault(false)
        AiEnabledSignal.mirror(_enabled.value)
    }

    /** 设置开关：先更新状态流（UI 即时响应），再异步落盘 */
    suspend fun setEnabled(value: Boolean) {
        _enabled.value = value
        AiEnabledSignal.mirror(value)
        // 落盘失败须 warn 落盘（完全静默时用户关开关重启后又回来无凭据可查）；
        // restore() 读侧失败有 AiToolsRestoreInitializer 的 warn 兜底，写侧对齐。
        runCatching { LlmConfigStore.setAiEnabled(value) }
            .onFailure {
                // kotlin-logging 门面无独立依赖可引（feature-ai 经 :core 的 JLog 走 logback）
                com.jiaoay.easy_dev_toolbox.core.log.JLog.w("AiToolsToggle") {
                    "AI 开关落盘失败（enabled=$value 不会持久化）：${it.message}"
                }
            }
    }

    /** 测试接缝：重置为默认（避免用例间状态泄漏；与 [_enabled] 初值同向） */
    internal fun resetForTest() {
        _enabled.value = false
        AiEnabledSignal.mirror(false)
    }

    fun setEnabledForTest(value: Boolean) {
        _enabled.value = value
        AiEnabledSignal.mirror(value)
    }
}
