package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.page.ToolPage

/**
 * 页面命名单源服务（空间/文档分离模型）。
 *
 * ## 为什么存在
 * 同一页面的显示名曾分散六处维护（navItem.label / DOCK_ENTRIES / tabsProjection 兜底 when /
 * App.kt 窗口标题 when / AppStatusBar 面包屑 when / retiredPageLabel），兜底链覆盖集不一致
 * 曾致三名并存、面包屑空白、检索漏项——现收敛为本表单源。
 */
object ToolPageLabels {

    /** 规范显示名（六消费面单源） */
    fun label(page: ToolPage): String? = when (page) {
        // ── 空间（dock 常驻五空间）──
        ToolPage.LogWorkbench -> "工作台"
        ToolPage.AgentCenter -> "智能体中心"
        ToolPage.ShellTools -> "Shell 工具"
        ToolPage.LarkBridge -> "飞书桥接"
        ToolPage.Tools -> "工具"
        ToolPage.DeviceObservation -> "设备观测"
        // ── 文档/系统页（经 dock 系统区、palette、深链打开）──
        ToolPage.Report -> "报告"
        ToolPage.Setting -> "设置"
        ToolPage.Debug -> "Shell 调试"
        ToolPage.AiSettings -> "AI 设置"
        ToolPage.McpExpose -> "MCP 服务"
        // ── 退役聚合子页（经宿主页子签承载，散页仅 palette 深入模式可达）──
        ToolPage.XLogDecoder -> "XLog 解密"
        ToolPage.LogTagFilter -> "Tag 过滤"
        ToolPage.FileSplitter -> "文件分割"
        ToolPage.Timestamp -> "时间戳"
        ToolPage.Archive -> "解压"
        ToolPage.Flow -> "会话"
        ToolPage.Distill -> "知识蒸馏"
        ToolPage.VoiceTranscribe -> "语音转写"
        // ── 恒不可达（僵尸枚举）──
        ToolPage.AppCenter -> null
    }

    fun aliases(page: ToolPage): List<String> = when (page) {
        ToolPage.LogWorkbench -> listOf("家", "日志工作台", "证据流", "研判")
        ToolPage.AgentCenter -> listOf("智能体", "AI", "会话", "流程") // 「流程」=旧称谓检索别名（老用户心智迁移，palette 匹配面专用）
        ToolPage.ShellTools -> listOf("设备", "adb")
        ToolPage.LarkBridge -> listOf("桥接", "飞书")
        ToolPage.DeviceObservation -> listOf("logcat", "抓日志", "观测", "实时日志")
        ToolPage.AiSettings -> listOf("AI", "模型")
        else -> emptyList()
    }

    fun hostOf(page: ToolPage): ToolPage? = when (page) {
        ToolPage.Flow, ToolPage.Distill, ToolPage.VoiceTranscribe -> ToolPage.AgentCenter
        ToolPage.XLogDecoder, ToolPage.LogTagFilter, ToolPage.FileSplitter,
        ToolPage.Timestamp, ToolPage.Archive -> ToolPage.Tools
        else -> null
    }

    /**
     * 工具聚合页子签 index：五件套 → [ToolsPage] SegmentedControl
     * 选中 index 的单点映射（0=XLog 解密 … 4=解压，与 ToolsPage 五签序逐字对齐）。
     * null = 非五件套页（palette 深入模式跳聚合页时无预选子签）。
     * palette openPageOf 经 [hostOf] 归并路由后，本表给宿主预选槽提供子签载荷。
     */
    fun hostIndexOf(page: ToolPage): Int? = when (page) {
        ToolPage.XLogDecoder -> 0
        ToolPage.LogTagFilter -> 1
        ToolPage.FileSplitter -> 2
        ToolPage.Timestamp -> 3
        ToolPage.Archive -> 4
        else -> null
    }

    /**
     * 智能体中心聚合页子签 index：三退役 AI 散页 →
     * [com.jiaoay.easy_dev_toolbox.page.agentcenter.AgentCenterPage] SegmentedControl
     * 选中 index 的单点映射（与四签序「0=会话 / 1=知识蒸馏 / 2=语音转写 / 3=调查空间」
     * 逐字对齐）。null = 非三退役页（第四签调查空间无枚举承载，不映射）。
     * palette 深链候选 / 存量 last_page 恢复经 [hostOf] 归并跳聚合页时，本表给
     * 宿主预选槽（AppViewModel.pendingAgentCenterTab）提供子签载荷。
     */
    fun agentCenterIndexOf(page: ToolPage): Int? = when (page) {
        ToolPage.Flow -> 0
        ToolPage.Distill -> 1
        ToolPage.VoiceTranscribe -> 2
        else -> null
    }
}
