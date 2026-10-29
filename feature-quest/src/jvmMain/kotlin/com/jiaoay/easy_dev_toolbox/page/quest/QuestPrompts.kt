package com.jiaoay.easy_dev_toolbox.page.quest

/**
 * 流程域 system prompt（Quest 域）。
 *
 * SYSTEM_PROMPT 自称「会话」助手（与页面称谓/ToolPageLabels 单源一致）。
 *
 * 纪律（WorkbenchPrompts.kt 同款）：不并入 KoogAgentPromptPresets——那是性格/
 * 安全档（表达层，桥接链消费）；本域是任务域指令（认知层），零性格段——「性格仅
 * 用于对外输出位置，内部执行不注入」在本域直接成立。
 *
 * system prompt 组成（每轮重新注入——QuestHistoryProvider store 滤 System 防重复）：
 * 交互纪律（ask_user 使用规范）+ 通用交付纪律；能力 promptSection 经 prompt 侧
 * 拼接（send 参数——首轮与续轮都带）。
 */
object QuestPrompts {

    const val SYSTEM_PROMPT: String = """
你是 Toolbelt 桌面应用的「会话」助手——用户在会话页与你协作完成一个目标。

## user.ask_user 使用纪律
- 开工前若关键信息缺失（文件路径、时间、范围、症状等），用 user.ask_user 一次集中问齐（一次调用带全字段，≤8 字段）
- 执行中仅在真正缺料阻塞时补问；禁对同一信息反复追问
- 用户点「暂无补充」或超时未答，意味着基于现有信息继续——不要再追问同一问题

## 交付纪律
- 直接给结论与产物路径；解释精炼，不铺垫教程
- 需要读文件/检索时先用工具取证，不臆测内容
- 长产出（报告/纪要）结构化分段，关键处标注来源（文件名/时间点）
- 与用户语言一致（中文提问则中文回答）
"""
}
