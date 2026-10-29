# log-workbench-agent

## Purpose

定义工作台内置研判 agent 会话的行为契约：工具面、研判纪律（十铁律产品化）、多轮追问形态与报告生成。内容真源是 log-helper skill 的 Step 6 分析铁律与报告五部分规范——本 capability 是其桌面产品化。

## Requirements

### Requirement: 研判 agent 工具面

研判 agent SHALL 装配以下工具集：`log.analyze_log`（确定性管线）、`knowledge.search` / `knowledge.read_doc` / `knowledge.lookup_tag`（知识库检索三件）、`fs.extended.*`（read_file/grep/glob 等取证七件）、`memory.*`（L2 记忆四件，桌面链独有）、`write_todos`（计划投影）。工具围栏 = 会话附件集 + escapeGate HITL（未登记路径弹窗确认，非源码目录前缀语义）。

#### Scenario: 管线阶段产物精确挂载

- **GIVEN** 研判 agent 调用 log.analyze_log 且管线成功
- **WHEN** 阶段完成
- **THEN** 各阶段产物路径经结构化旁路精确挂载到 run 的时间线与证据流（非文本流猜测）

#### Scenario: 围栏外路径弹窗确认

- **GIVEN** agent 尝试读取用户未拖入的日志目录外文件
- **WHEN** 该路径未在围栏内
- **THEN** 弹出 HITL 确认（允许一次/拒绝）；拒绝后 agent 收到明确拒绝反馈并可改道

### Requirement: 研判纪律（十铁律产品化）

研判 agent 的 system prompt SHALL 产品化移植 log-helper Step 6 十条分析铁律：日志出现≠执行成功、下游缺失先验证过滤遗漏、内部状态≠UI 呈现、时间相邻≠因果、区分用户/系统行为、持续性症状全窗口追踪、过滤文件≠完整日志、ERROR/WARNING=P0 候选、值的验证≠来源的验证、多周期重复=竞态证据。报告生成 SHALL 遵循五部分规范（结论/根因（已确认与怀疑点分列）/完整时间线/关键依据（逐字日志证据块）/元数据）与表格-日志配对规范。

#### Scenario: 已确认与怀疑点分列

- **GIVEN** 研判完成
- **WHEN** 报告生成
- **THEN** 「已确认原因」与「怀疑点」分列呈现；未通过验证门（代码路径或日志中间步骤）的候选只出现在怀疑点，不冒充已确认

#### Scenario: 日志证据逐字引用

- **GIVEN** 报告中某条已确认原因附有日志依据
- **WHEN** 用户展开该依据
- **THEN** 日志行逐字来自过滤产物文件（含时间戳/TAG/完整消息体），未通过验证门的结论不标注已确认

### Requirement: 报告追问形态

对报告的追问 SHALL 经报告面板的次级按钮（结果窗形态）发起，不在工作台主输入框挂通用对话；追问携带报告上下文与既有工具面，产出以增量结论更新报告（原结论保留、新结论追加），run 的报告文件同步更新。

#### Scenario: 追问增量更新

- **GIVEN** 一份已完成的报告
- **WHEN** 用户经「追问」提交补充问题
- **THEN** agent 基于原报告上下文研判，新结论以增量段追加进报告与面板；原有结论不被改写

### Requirement: 运行预算与进度

研判 agent 的单次任务迭代预算 SHALL 独立于全局 agent 预算（日志分析是多轮长流程——管线调用+知识检索+取证+报告生成）；运行面板 SHALL 呈现结构化进度（当前阶段/正在调用的工具/已产出的结论数），报告生成前用户可中止且已完成阶段保全。

#### Scenario: 预算耗尽可恢复

- **GIVEN** 研判因迭代预算耗尽而中止
- **WHEN** 用户查看该 run
- **THEN** run 标记为「中止」，已产出的阶段产物、时间线与部分结论保留可回看；用户可调整参数后新开 run
