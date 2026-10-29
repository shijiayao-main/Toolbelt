# flow-page Specification

## MODIFIED Requirements

### Requirement: agent 开工卡与执行中补问

会话态 SHALL 支持 agent 主动发卡索要信息：`user.ask_user` 触发时页面在输入区上方
固定渲染 AskCard（表单卡：question 文案 + 按 AskField 类型分派的字段 + 提交/暂无
补充两钮）；用户提交/跳过后工具软反馈回传、agent 继续执行。输入框 placeholder
SHALL 定位为「回答/追问」语义（非「发送消息」）——🔴 边界守则：流程页是对象绑定
页（对象 = flow），输入框仅流程存在后出现，不滑向通用 chat 页。

会话态 SHALL 同时支持围栏外路径确认卡（escapeGate）：fs.extended 工具访问围栏外
路径被拦时，页面 SHALL 在输入区上方渲染路径确认卡（路径 + 允许/拒绝两钮）；
允许后该路径登记进围栏（本会话后续免确认）、拒绝则工具收 DENIED 软反馈继续。
用户 cancel 停止时确认卡 SHALL 消隐（与 AskCard 同语义——🔴 表单不残留）。

#### Scenario: 开工集中提问

- **GIVEN** 新流程已开始、agent 判断关键信息缺失
- **WHEN** agent 调用 user.ask_user（多字段）
- **THEN** AskCard 即时出现（经 sink 旁路）；用户填卡提交后表单消隐、agent 继续

#### Scenario: 取消时表单不残留

- **GIVEN** AskCard 可见且 agent 挂起等待中
- **WHEN** 用户点停止（cancel）
- **THEN** AskCard 消隐（pendingQuestion 清空）、挂起收 CE、尾随 RUNNING 工具卡翻
  CANCELLED 态

#### Scenario: 围栏外路径确认

- **GIVEN** 日志分析流程运行中、用户给了围栏外日志路径
- **WHEN** agent 调 fs.extended.read_file 读该路径触发 escapeGate
- **THEN** 路径确认卡即时出现；用户点「允许」后工具继续（路径已进围栏）；
  用户点「拒绝」后工具收 DENIED 软反馈、agent 继续而不崩

#### Scenario: 取消时确认卡不残留

- **GIVEN** 路径确认卡可见且工具挂起等待中
- **WHEN** 用户点停止（cancel）
- **THEN** 确认卡消隐（pendingGate 清空）、挂起收 CE、agent 本轮终止不残留表单
