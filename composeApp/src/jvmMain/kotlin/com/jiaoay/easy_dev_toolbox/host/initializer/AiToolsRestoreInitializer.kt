package com.jiaoay.easy_dev_toolbox.host.initializer

import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializer
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.page.ai.AiToolsToggle

/**
 * AI 工具总开关恢复（宿主注册，level 2 高优）：读 feature 域持久化值
 * （`llm_ai_enabled`，经 [AiToolsToggle] 门面）回填进程级状态。
 *
 * 为何需要（时序）：开关状态决定 AI 能力（如飞书智能调度）的可用性。若等页面组合后再异步读盘，
 * 首帧会先按默认值渲染、随后被纠正——已关闭的用户会看到隐藏项闪现一下。
 * level 2 与 `LogFileRestoreInitializer` 同级，在任何窗口组合前完成。
 *
 * 失败不阻断启动：读盘异常时 [AiToolsToggle.restore] 内部已兜底为**关闭**（fail-closed）
 * ——置灰可恢复，不会把未配置的用户显示成已启用。
 */
class AiToolsRestoreInitializer : JInitializer {

    override val isHighLevel: Boolean = true

    override fun level(): Int {
        return 2
    }

    override suspend fun init(): Boolean {
        runCatching { AiToolsToggle.restore() }
            .onFailure { JLog.w(TAG) { "AI 工具开关恢复失败，回落默认关闭：${it.message}" } }
        return true
    }

    companion object {
        private const val TAG = "AiToolsInitializer"
    }
}
