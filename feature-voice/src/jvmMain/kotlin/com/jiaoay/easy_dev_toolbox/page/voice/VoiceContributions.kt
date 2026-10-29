package com.jiaoay.easy_dev_toolbox.page.voice

import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.page.ToolPage

/**
 * 语音转写域的导航注册出口（P4-S openspec voice-transcribe 任务 1.1/3.1）。
 *
 * 导航入口三段式收敛（AppCenter 先例同款）：
 * - **navItem=null**——语音转写不再作为独立导航页出现（palette 导航候选/分组卡
 *   不展示），子页签经宿主 AgentCenterPage（智能体中心第三签）渲染；
 * - **content 注册保留**（聚合页子页签与深入模式仍渲染 VoicePage）；
 * - **存量兜底由宿主承接**：retiredPageLabel 中文兜底与 last_page 改道（组 B 同步）
 *   不在本域处理；
 * - aiEnabled 本来就不在此上送（feature 互不依赖铁律——AiToolsToggle 真源在
 *   feature-ai）：宿主拼接点 copy 注入逻辑不动（注册项仍在 featureRegistrations 里）。
 */
object VoiceContributions {

    val registrations: List<ToolPageRegistration> = listOf(
        ToolPageRegistration(
            page = ToolPage.VoiceTranscribe,
            // navItem 退役——语音转写子页签经宿主 AgentCenterPage 渲染。
            navItem = null,
            group = ToolPageGroup.AGENTS,
        ) {
            VoicePage()
        },
    )
}
