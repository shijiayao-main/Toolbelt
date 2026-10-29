# flow-ask-hITL Specification

## Purpose
TBD - created by archiving change add-flow-ask-user-and-flows-schema. Update Purpose after archive.

## Requirements

### Requirement: agent 主动提问工具 user.ask_user

系统 SHALL 提供 `user.ask_user` agent 工具：agent 执行中可主动向用户发起一次结构化
提问。工具入参 SHALL 包含 `question`（必填提问文案）、`fields`（可选字段表单——
每字段 id/label/type/options/required/placeholder）、`allowSkip`（是否允许用户
跳过）。空 `fields` SHALL 等价于单条自由文本回答。非法字段 type SHALL 返回
BAD_ARGS 错误而非崩溃。

#### Scenario: 集中开工提问

- **GIVEN** agent 被指令「开工前先集中问齐关键信息」
- **WHEN** agent 调用 `user.ask_user`（question + 多个 fields）
- **THEN** 工具挂起等待用户回答；用户提交后工具 SHALL 把回答以「用户回答：
  字段标签: 值」清单文本回传 LLM，agent 基于回答继续执行

#### Scenario: 用户跳过

- **GIVEN** 一次 allowSkip=true 的提问
- **WHEN** 用户点「暂无补充」
- **THEN** 工具 SHALL 回传「用户表示暂无更多信息，请基于现有信息继续」软文案，
  agent 继续执行，任务不失败

#### Scenario: 等待软超时

- **GIVEN** 用户未回答且超过等待上限
- **WHEN** 软超时触发
- **THEN** 工具 SHALL 回传「用户未在限定时间内回答，请基于现有信息继续或先收尾」
  软文案并继续 run（🔴 不得先于 koogToolLoop 的整体硬超时被 withTimeout 杀掉——
  软超时须永远先于硬超时触发）

#### Scenario: 取消穿透

- **GIVEN** 提问挂起中（用户未回答）
- **WHEN** 会话被取消（runJob.cancel）
- **THEN** 挂起等待 SHALL 收到 CancellationException 原样传播（不吞不译），
  pending 提问状态由消费方清空

### Requirement: UserQuestionGate 槽位门控

`user.ask_user` 工具 SHALL 经 `ToolContextConfig.userQuestionGate` 槽位装配：
槽位为 null 时工具**不得注册**（不入 base names、桥接/工作台/蒸馏/语音旧链零
影响）；槽位非 null 时经 AgentToolCatalog 描述符点名注册。AskRequest（含
toolCallId/question/fields/allowSkip）SHALL 经 ToolResultSink 结构化旁路
（AskRequestPayload）供 UI 渲染表单卡，LLM 侧只见回传文案。

#### Scenario: null 槽位零注册

- **GIVEN** 一次不注入 userQuestionGate 的工具装配（桥接/工作台/蒸馏既有链）
- **WHEN** buildRegistry 构建
- **THEN** 工具注册表不含 user.ask_user——旧链提示词面与执行面零变化

#### Scenario: 点名注册

- **GIVEN** 注入了 userQuestionGate 的装配
- **WHEN** buildRegistry 以 user.ask_user 名集构建
- **THEN** 工具可被 LLM 调用且 AskRequest 经 sink 旁路到达 UI 面

### Requirement: 等待上限红线

Gate 桥消费方 SHALL 保证提问等待时长 ≤ 会话整体预算（koogToolLoop
overallTimeoutMillis）——参考桥接 resolveConfirmTimeoutUpperBound 纪律（确认等待
不得超过 agent 总超时）。基建层以 `resolveAskCapMillis(overallTimeoutMillis)`
提供换算（0.8 系数，钳 [1 分钟, 30 分钟]）。

#### Scenario: 换算函数钳域

- **GIVEN** resolveAskCapMillis 换算函数
- **WHEN** 传入任何正的整体预算值
- **THEN** 返回值恒在 [1 分钟, 30 分钟] 钳域内且 ≤ 整体预算的 0.8——软超时永远
  先于 koogToolLoop 硬超时触发
