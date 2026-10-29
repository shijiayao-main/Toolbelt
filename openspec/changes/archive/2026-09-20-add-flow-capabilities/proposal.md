# Proposal: add-flow-capabilities

## Why

Quest 域 change 2（add-flow-page）落地的流程页目前只有通用对话隐式能力——
`QuestCapabilities.all` 是空目录，新建表单引导文案写着「暂无注册能力」。
维护者的原始设想（plan v3 拍板）是「类应用中心的样式，用户选择自己需要的工具」：
日志分析 / 语音纪要 / 通用问答三能力面。本 change 把前两个真实能力接进能力目录，
完成主活最后一块拼图。

## What Changes

1. **能力目录接线**（`QuestCapabilities` 空目录 → 两真实注册项）：
   - **日志分析**（`log-analysis`）：toolNames 值同源自持（fs.extended 七件 +
     knowledge 三件 + memory 四件 + log.analyze_log——WORKBENCH_NAMES 同款名集，
     照 BridgeLogTools/WORKBENCH_NAMES 先例在 feature-quest 自持字面量，跨模块
     不可见 private 常量不引用）；promptSection 改写自 WorkbenchPrompts.ANALYZE_PROMPT
     协作版；装配缝 privateKeyProvider + memoryDir 注入 QuestSession。
   - **语音纪要**（`voice-minutes`）：toolNames = fs.extended.read_file 单件；
     promptSection 改写自 VoiceSummarizePrompts.SUMMARIZE_PROMPT 协作版。
   - 通用问答 = 隐式能力（全不选），零改动。
2. **escapeGate 桥**（🔴 本 proposal 新定夺项——plan 未写明的必要配套）：QuestSession
   现为裸 `ToolExecutionContext()`（无 gate）。接 fs.extended 后 agent 读用户在
   AskCard/输入框给出的围栏外路径（如「分析 /data/app.log」）会被围栏静默 DENIED
   ——真实可用性缺口。补 WorkbenchSession 同款 gate 桥：pendingGate StateFlow +
   挂起等 UI 确认 + cancel 清理，FlowPage 加确认卡。
3. **QuestSession 装配缝扩展**：构造参数追加 privateKeyProvider（null = 纯明文分析，
   analyze_log 不装）/ memoryDir（null = memory.* 不装）——工作台五件套同款语义；
   ToolContextConfig 透传。
4. **能力目录守护测试**：QuestCapabilities.toolNames ∈ AgentToolCatalog 可解析面
   （防止目录名集与目录注册漂移——plan G 节风险 3 的配方落地）。
5. promptSection 协作版改写纪律：两段提示词都是完整 system prompt 语义的独白式
   （「你是一名…专家」），拼在用户消息前须改为段式过渡（「本流程已启用…能力，
   以下纪律适用于本次流程中的对应任务」）——不改写会导致与 QuestPrompts.SYSTEM_PROMPT
   语义冲突（双重人设）。

## Out of Scope

- 独立 AI 页收编/移除（工作台/蒸馏/转写页保持独立——专门交互面流程页承载不了）
- write_todos 补挂（Quest 域 KISS 首版不装计划投影；prompt 段不引用）
- 能力市场/插件化/用户自定义能力（plan v3 裁定硬编码注册表）
- 真机端到端 LLM 验收（列为验收剧本待已配置的 LLM 端点；单测面以 fake 装配验证）
