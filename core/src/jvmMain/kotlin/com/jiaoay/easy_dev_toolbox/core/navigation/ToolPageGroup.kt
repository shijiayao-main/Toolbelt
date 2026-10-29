package com.jiaoay.easy_dev_toolbox.core.navigation

/**
 * 工具页分组（产品三层叙事的导航投影）。
 *
 * 归组判据是**功能核心价值来源**，而非「是否用到确定性代码」：
 * - [TOOLS]：卖点是确定性变换（文件格式转换、过滤、解包等本地规则即可完成的操作）——
 *   工具本体离线完整可用，AI 开关不 gates；
 * - [AGENTS]：卖点是 agent 推理（根因研判、知识蒸馏、口语整理等以模型为大脑的能力）——
 *   内部的确定性子步骤（管线过滤、离线转写）属于 agent 的摄取前端，不改归属；
 * - [ENTRY]：卖点是把本地工具或智能体能力递给外部消费（消息平台桥接、agent 协议外放）。
 *
 * 同一能力做成以 agent 推理为卖点的功能页归 [AGENTS]，做成单步确定性动作的工具页归
 * [TOOLS]——归组看页面卖点，不看能力本身。
 *
 * 结构页（应用中心/系统设置/调试页）不携带分组（null）——见 [ToolPageRegistration.group]。
 */
enum class ToolPageGroup {
    /** 工具——确定性基座：XLog 解密/Tag 过滤/文件分割/解压/时间戳/Shell 工具 */
    TOOLS,

    /** 智能体——agent 为大脑的功能页：日志工作台/知识蒸馏/语音转写/AI 设置（agent 域控制台） */
    AGENTS,

    /** 入口——外部消费本地能力：飞书桥接（P5 MCP 外放同属此类概念） */
    ENTRY,
}
