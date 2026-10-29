# Spec Delta

## Purpose

定义 MCP 外放服务页的用户可见行为契约：本地工具与 agent 能力经 MCP 协议（streamable HTTP，127.0.0.1）递给外部 MCP client。本 capability 是「入口」组第二成员——本地能力对外暴露的第二条通道（第一条是飞书桥接）。

## ADDED Requirements

### Requirement: MCP 服务入口与生命周期

MCP 服务页 SHALL 提供服务开关（默认关闭）与端口配置（默认值经 :core 持久化）：开启后在本机 `127.0.0.1:<端口>` 起 streamable HTTP 端点（路径 `/mcp`）供外部 MCP client 连接；关闭即停端点。App 退出时 SHALL 优雅停 server（AppHost 参与者——不残留监听进程）。服务未开启时页面 SHALL 展示引导文案与端点说明；开启时展示运行态、端口与端点 URL（一键复制）。

#### Scenario: 开启即起端点

- **GIVEN** 用户在 MCP 服务页点开服务开关
- **WHEN** 端点就绪
- **THEN** 状态卡显示运行态与 `http://127.0.0.1:<端口>/mcp`；外部 MCP client（MCP Inspector 等）可连接并列出工具清单

#### Scenario: 退出不残留监听

- **GIVEN** MCP 服务正在运行
- **WHEN** 用户关闭应用
- **THEN** server 随 App 退出优雅停止，端口释放（再次启动无「端口被占用」）

#### Scenario: 端口冲突可感知

- **GIVEN** 配置端口已被占用
- **WHEN** 用户开启服务
- **THEN** 启动失败在状态卡明示「端口被占用，请修改端口」；不崩溃

### Requirement: 工具面外放

MCP tools SHALL 映射本地工具目录的确定性工具面（解密 / log.analyze_log 管线 / fs.extended 取证七件 / knowledge.* 检索三件）：工具名、描述与输入 schema 从工具目录描述符面派生（真源唯一）；调用 SHALL 经本地围栏（`ToolExecutionContext`）执行——授权根目录 = 源码目录 allowlist（AI 设置页已配的目录集），围栏外路径调用 SHALL 返回 deny + reason（错误信息说明哪个目录未授权）。工具执行结果（含产物文件路径）SHALL 回传 client。

#### Scenario: 外部 client 直调确定性管线

- **GIVEN** MCP 服务已开启且某日志文件在授权目录内
- **WHEN** 外部 client 调用 analyze_log 工具（传入文件路径与时间参数）
- **THEN** 管线本地执行（解密→时间过滤→环境扫描→Tag 过滤），阶段产物路径回传 client

#### Scenario: 围栏外路径拒绝

- **GIVEN** 外部 client 传入的文件路径在授权目录之外
- **WHEN** 工具调用执行
- **THEN** 返回 deny + reason（指明未授权的路径与授权目录清单）；不执行任何文件操作

### Requirement: agent 会话外放

MCP tools SHALL 提供研判与蒸馏两个 agent 会话入口（`workbench_analyze` / `distill_run`）：调用后 Toolbelt 内跑 agent 循环（本地 LLM 端点配置），进度 SHALL 经 MCP progress 通知上报（阶段/工具调用/完成度），最终报告文本回传。研判会话 SHALL 复用工作台研判链的提示词与工具集（十铁律 + 报告五部分）；蒸馏会话 SHALL 复用蒸馏链的审批前形态（产出候选知识文本，不直接入库）。会话超长执行时 client 可发起取消（MCP 取消语义），已产出的部分结果 SHALL 保留回传。

#### Scenario: 研判会话外放

- **GIVEN** 本地已配置 LLM 端点且 MCP 服务开启
- **WHEN** 外部 client 调用 workbench_analyze（传入日志文件/时间点/症状）
- **THEN** 研判 agent 在 Toolbelt 内执行（工具循环 + 十铁律），client 收到 progress 通知，完成后收到结构化报告文本

#### Scenario: 取消保全部分结果

- **GIVEN** 研判会话执行中
- **WHEN** client 发起取消
- **THEN** 会话停止，已产出的阶段产物与部分结论以「已中止」标记回传

#### Scenario: 未配端点明示

- **GIVEN** 本地未配置任何 LLM 端点
- **WHEN** 外部 client 调用 agent 会话工具
- **THEN** 返回明确错误（指引先在 AI 设置页配置端点）；工具面（确定性工具）不受影响

### Requirement: MCP 服务页管理面

MCP 服务页 SHALL 提供管理面：状态卡（运行态/端口/已连接 client 计数）、服务开关、端口配置、端点 URL 复制、外放工具清单浏览（从工具目录描述符面直读——名称/描述/风险分级呈现）、client 连接与调用日志台（连接/断开/工具调用记录，JLog 落盘）。

#### Scenario: 工具清单可见

- **GIVEN** MCP 服务页打开
- **WHEN** 用户浏览工具清单
- **THEN** 展示外放工具的名称、描述与风险分级（与实际注册进 MCP server 的清单一致——单一真源）
