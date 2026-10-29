# Proposal

## Why

维护者的新想法「agent 主动交互流程」（P4/P5 全部落地后的下一个大活）需要三块基建才能
成页：① agent 在执行中**主动向用户索要信息**的 HITL 原语（现仓只有二元裁决——
escapeGate/确认卡，无开放式提问工具）；② 「流程 = 可继续上下文的历史对话」的
持久化对象（flows 表——维护者裁定历史流程可续聊，非复跑模板）；③ 流程域独立预算。

本 change 只做基建（:core + db），零 UI——页面与能力接线在后续 change
（add-flow-page / add-flow-capabilities）落地。先铺基建的原因：ask_user 工具与
flows 表是页面批的硬前置，且 :core 层改动需先固化契约面（agent 工具目录 +
schema 迁移），避免页面批把「契约设计」与「UI 施工」搅在一起。

## What Changes

- 新增 `user.ask_user` agent 工具（:core llm/tools）：agent 执行中主动向用户发起
  结构化提问（question + 可选 fields 表单 + allowSkip），经 `UserQuestionGate`
  挂起等人回答（CompletableDeferred 语义——escapeGate 同款机制），软反馈回传
  LLM 继续执行；
- 新增 `ToolContextConfig.userQuestionGate` 槽位（null 不注册——distillBuffer 同款
  反向门控，桥接/工作台/旧链零影响）；
- 新增 flows 表（SQLite schema v3→v4 迁移）+ `QuestStore` 门面（Runs.sq/RunStore
  配方照抄）：流程对象持久化（id/title/capabilities/时间戳），删除级联 messages/
  sessions/chat_history 三店；
- 新增 LlmConfigStore 流程预算 key（`getFlowMaxIterations`）。

## Capabilities

### New Capabilities
- `flow-ask-hITL`: agent 主动提问 HITL 原语（user.ask_user 工具 + UserQuestionGate
  + AskRequest/AskField/AskAnswer 模型 + 软反馈语义 + 等待上限红线）
- `flow-store`: 流程对象持久化（flows 表 + QuestStore 门面 + 三店级联删除 + 迁移）

### Modified Capabilities
<!-- 本 change 不修改既有 capability 的 spec 级行为：AgentToolCatalog 加槽位是
     纯增量（null 默认零影响）；LlmConfigStore 加 key 是纯增量。 -->
