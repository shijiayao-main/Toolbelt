# flow-page Specification

## ADDED Requirements

### Requirement: 流程页入口与两态布局

应用 SHALL 提供「流程」页面（智能体组成员）：左栏固定宽历史流程列表（updated_at
倒序 + 新建按钮 + 重命名/删除管理面）+ 主区两态——无活跃流程时显示新建表单（能力
多选 chips + 目标描述输入 + 开始按钮），有活跃流程时显示会话流（时间线 + AskCard
+ 追问输入框）。页面 SHALL 经 aiEnabled 注入 AI 置灰（AI 关闭时新建按钮置灰引导
去 AI 设置页——与工作台/语音同语义）。

#### Scenario: 新建流程向导

- **GIVEN** 用户进入流程页且无活跃流程
- **WHEN** 勾选能力（可全不选 = 通用对话）+ 输入目标 + 点「开始」
- **THEN** 创建 flow（QuestStore.create——title 由目标文本派生）并进入会话态；
  agent 首轮被指令「开工纪律：关键信息缺失时先用 user.ask_user 一次集中问齐」

#### Scenario: 历史流程进入与续聊

- **GIVEN** 历史列表存在至少一个流程
- **WHEN** 用户点击一条历史流程
- **THEN** 会话流经 messages replay 恢复 UI 时间线；后续追问经 ChatMemory
  （sessionId=flowId）携带历史上下文——agent 记得前文（可继续执行）

### Requirement: agent 开工卡与执行中补问

会话态 SHALL 支持 agent 主动发卡索要信息：`user.ask_user` 触发时页面在输入区上方
固定渲染 AskCard（表单卡：question 文案 + 按 AskField 类型分派的字段 + 提交/暂无
补充两钮）；用户提交/跳过后工具软反馈回传、agent 继续执行。输入框 placeholder
SHALL 定位为「回答/追问」语义（非「发送消息」）——🔴 边界守则：流程页是对象绑定
页（对象 = flow），输入框仅流程存在后出现，不滑向通用 chat 页。

#### Scenario: 开工集中提问

- **GIVEN** 新流程已开始、agent 判断关键信息缺失
- **WHEN** agent 调用 user.ask_user（多字段）
- **THEN** AskCard 即时出现（经 sink 旁路）；用户填卡提交后表单消隐、agent 继续

#### Scenario: 取消时表单不残留

- **GIVEN** AskCard 可见且 agent 挂起等待中
- **WHEN** 用户点停止（cancel）
- **THEN** AskCard 消隐（pendingQuestion 清空）、挂起收 CE、尾随 RUNNING 工具卡翻
  CANCELLED 态

### Requirement: ChatMemory 续跑与两层各司其职

历史流程续聊 SHALL 经 ChatMemory 持久层（SqliteChatHistoryProvider，conversation_id
= flowId）携带 LLM 上下文；UI 时间线 SHALL 经 messages 表（SessionStore）replay
恢复。QuestHistoryProvider 的 store SHALL 滤除 System 消息（防重复注入）并对
窗口封顶（takeLast ≈ 40 条）。流程删除 SHALL 级联三店（change 1 QuestStore.delete）。

#### Scenario: 重启后续聊记得前文

- **GIVEN** 流程进行中重启应用
- **WHEN** 用户从历史进入该流程并发起追问
- **THEN** agent 回答基于重启前的对话内容（ChatMemory 恢复生效）；UI 时间线完整
  回放（messages replay）

