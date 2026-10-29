package com.jiaoay.easy_dev_toolbox.page

/**
 * 主区内容 key（枚举收敛终态）：
 * - [Default] 欢迎页已删除——启动直达 [LogWorkbench] 家；
 * - 五件套（[XLogDecoder]/[LogTagFilter]/[FileSplitter]/[Timestamp]/[Archive]）
 *   navItem 已退役，但 **content 注册保留**——palette 深入模式
 *   （ParamCommandSpec.openPage → WorkspaceTabs 临时 tab → PageHomeContent 分发）
 *   仍以页形态渲染，故枚举值保留；常驻可达面 = [Tools]
 *   聚合页（dock 第 5 图标，页内子签嵌入五件套 embedded 变体）；
 * - [Report] 报告页：tab 集第四成员
 *   （占位语义——最近 run 报告卡列表 + 空态引导，宿主自有页 navItem = null）；
 * - 语义收窄：本枚举不再是「14 页等权导航」，而是「主区 tab/页面内容 key」。
 *
 */
enum class ToolPage {
    Setting,
    AppCenter,
    XLogDecoder,
    LogTagFilter,
    FileSplitter,
    ShellTools,
    Timestamp,
    Archive,
    Tools, // 工具聚合页：五件套页内子签收拢——dock 常驻直达（Ctrl+5），五件套自身 navItem 仍退役
    LarkBridge,
    McpExpose,
    AiSettings,
    Distill,
    LogWorkbench,
    VoiceTranscribe,
    Flow, // 流程（:feature-quest，智能体组——agent 主动交互流程页）
    AgentCenter, // 智能体中心聚合页——页内子页签承载会话/知识蒸馏/语音转写；Flow/Distill/VoiceTranscribe 三值退役 navItem、保留 content，存量 last_page 改道 AgentCenter（改道逻辑在宿主 AppViewModel）
    Report, // 报告（宿主自有视图页——工作台 tab 容器第四成员：最近 run 报告列表 + 空态占位）
    Debug,
    DeviceObservation, // 设备观测空间页（dock 第六空间：logcat 实时抓取等设备实时观测成员，宿主聚合页）
}