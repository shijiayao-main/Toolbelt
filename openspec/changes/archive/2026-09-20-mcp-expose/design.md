# Design: mcp-expose

## Context

维护者拍板：工具 + agent 会话全外放、官方 Kotlin SDK（`io.modelcontextprotocol:kotlin-sdk-server` 0.15.0——JVM 11+ KMP 产物，`embeddedServer(CIO)` + `mcpStreamableHttp` 服务端形态，SDK 不传递 Ktor 引擎需自行声明）。仓内既有资产：`AgentToolCatalog`（工具目录 + 描述符面 + buildRegistry 装配）、`ToolExecutionContext`（围栏 + escapeGate）、`LlmConfigStore.getSourceRoots()`（源码目录 allowlist）、`AppHost`/`AppLifecycleParticipant`（生命周期）、`WorkbenchPrompts`/`KoogDistillPrompts`（提示词）。

## D1 模块与分层

`:feature-mcp`（`easydevtoolbox.kmp.feature` convention——jvmTarget 25 可吃 KMP SDK）：

```
page/mcp/
├── McpContributions.kt      # 注册出口（navItem icon=SHARE、group=ENTRY）
├── McpExposePage.kt          # 管理面 UI（状态卡/开关/端口/工具清单/日志台）
├── McpExposeViewModel.kt     # 服务态 + 开关/端口配置 + 工具清单面
├── McpServerManager.kt        # 服务生命周期（AppLifecycleParticipant 实现者）
├── McpToolRegistrar.kt       # 工具面映射（描述符 → MCP Tool + 调用分发）
└── McpAgentTools.kt           # agent 会话工具（workbench_analyze/distill_run）
```

🔴 SDK 类型隔离（对齐 SherpaOnnxIsolation/LarkSdkIsolation 模式）：
`io.modelcontextprotocol.*` 只允许出现在 `McpServerManager.kt` + `McpToolRegistrar.kt` +
`McpAgentTools.kt` 三个装配层文件（Ktor embeddedServer 调用点同此）；页面/VM 经本模块
门面消费。守护测试 3.x 钉死。

## D2 会话模型裁定：stateless + 每调用独立 run

**决策：`mcpStatelessStreamableHttp()`**（非 stateful）。理由：
- MCP 工具语义是无状态调用；agent 长会话与「一次 tools/call 返回」模型不匹配——
  长会话保持会让 MCP session 与本地 run 的生命周期互相纠缠（session 断即 run 丢）；
- 每次调用 = 独立 run（runId 生成 + 独立 WorkbenchSession 形态会话）；追问链经
  工具参数携带（`follow_up_context` 可选参数——原报告文本或 runId 引用）；
- stateless 下 client 断连不杀 run：run 在后台继续、完成后结果可经同 runId 查询
  （`workbench_status` 工具）——进度与结果两段式。

**run 登记表**：feature 内 object `McpRunRegistry`（ConcurrentHashMap<runId, RunHandle>；
容量上限 10——超出拒绝新会话防资源耗尽；RunHandle 持 session + StateFlow 进度 + cancel 钩）。

## D3 工具面映射（McpToolRegistrar）

描述符 → MCP Tool 的**单一真源派生**（不手抄双维护）：
- 名称：描述符 `name`（点分命名空间 `fs.extended.read_file` 原样——MCP 工具名合法字符集含点）；
- 描述：`uiHint.description ?: description`（uiHint 是中文产品文案，MCP client 展示友好）；
- schema：从工具类的 `@Serializable Args` 经 kotlinx.serialization 反射派生
  （`serializer().descriptor` → JSON Schema properties——kotlinx 的 descriptor 遍历
  与 JSON Schema 类型映射 ~40 行纯函数，单测缝）；
- 调用分发：`buildRegistry(names)` 装配一次（进程内单例 registry），MCP handler 按
  工具名查表执行——与桥接链/桌面链完全同构的执行面。

**外放名集**（MCP_EXPOSE_NAMES，feature 内自持——WorkbenchSession.WORKBENCH_NAMES 先例）：
fs.extended 七件 + knowledge 三件 + analyze_log +（xlog 解密走 analyze_log 的
REDECRYPT 级——不单列）。🔴 memory.* 不外放（个人记忆不进外部 client）；🔴
write_todos 不外放（桌面会话件）；🔴 出站类（send_file 等桥接域工具）不在目录本就不装。

## D4 围栏与安全面

- **绑定 127.0.0.1**（写死不配置——外网零暴露）；无鉴权首版（本机信任域）；
- **围栏**：`ToolExecutionContext` 授权根目录 = `LlmConfigStore.getSourceRoots()`
  （源码目录 allowlist——AI 设置页既有配置面，零新配置 UI）+ 临时围栏注册（工具调用
  参数中的文件路径逐次 register——同工作台「附件渐进」语义）；
- **escapeGate = deny-only**（MCP 侧无 UI 弹窗——HITL 形态裁定为「拒绝 + reason 列出
  授权目录清单」，远程 client 无法响应桌面弹窗）；🔴 与桌面链弹窗语义的差异进 spec：
  桌面链 approve/deny 二态，MCP 链恒 deny（fail-safe——McpPermissionServer 的
  「无法确认即拒绝」同哲学）；
- agent 会话（工具循环内）同围栏——agent 读文件也出不了授权目录。

## D5 agent 会话执行体（McpAgentTools）

- **workbench_analyze**：参数（log_files[]/target_time/range_minutes/symptom/tag_regex?/
  follow_up_context?）→ 新 runId → 旁路起 WorkbenchSession 同构会话（KoogToolLoop +
  WorkbenchPrompts + WORKBENCH_NAMES 名集——直接复用 feature-logworkbench 的常量与
  session 类？🔴 **不可 import**：feature 互不依赖铁律——在 :feature-mcp 自持
  WORKBENCH_NAMES 常量（值同源，WorkbenchPrompts 在 :core 可直接用），会话执行体
  用 :core 的 KoogToolLoop 直接装配（与 WorkbenchSession 同构的精简版——MCP 侧无
  ChatEntry reducer 面，进度经 progress 回调直发）；
- **distill_run**：参数（source_dir/module_desc/entry_hints[]）→ 蒸馏候选产出文本
  （**审批前形态**——不写知识库，产出经 tools/call 结果返回；桌面审阅入库仍走蒸馏页）；
- **progress 上报**：SDK 的 progress 通知（`sendNotification(progress)` 面经核对——
  0.15.0 API 形态施工时以 javadoc/源码为准；不可用则降级为日志通知面的 progress
  token 约定）；
- **取消**：MCP cancelled 通知 → RunHandle.cancel()（CE 传播——工作台「中止保全」同语义）；
- **预算**：研判用 `getWorkbenchMaxIterations`（同工作台）、蒸馏用
  `getDistillMaxIterations`（同蒸馏链）——既有 key 零新增。

## D6 生命周期与配置

- `McpServerManager : AppLifecycleParticipant`：`start(scope)` 读开关配置决定是否起
  server（默认关——恒不自动起）；`shutdown()` 停 server + 取消在途 run；
  页面开关翻转即时启停（manager 单例 object 持 AtomicReference<ApplicationEngine>）；
- 配置 key（:core `AppDataStore` 经 EasyDataManager 或 feature ConfigStore 先例——
  用 `LlmConfigStore` 同包新 key：`mcp_server_enabled`/`mcp_server_port`——LLM 配置域
  同族归属）；默认端口 8765（避开常用 3000/8080）；
- **页面零依赖 Ktor**：VM 经 manager 门面（start/stop/status/port 配置）——Ktor 类型
  不进 VM/页面（隔离面与 D1 一致）。

## D7 服务页 UI（全 token 化零白名单）

- PageScaffold + PageHeader（标准页形态——管理面非工作台三栏）；
- 状态卡（TonalCard）：运行态徽章（成功绿/关闭 onSurfaceVariant）+ 端点 URL + 复制按钮
  （CopyIconButton）+ 连接计数；
- 开关行 + 端口 CommitNumberField（1024..65535 校验域）；
- 工具清单：LazyColumn（描述符面直读——名称/描述/风险徽章三级色）；
- 日志台：LogConsolePanel 配方（JLog 回显或 manager 事件流——连接/调用/错误行）。

## D8 测试策略

- **零 Ktor/零 SDK 单测**（隔离纪律同 sherpa——SDK 0.x 不进单测依赖面）：
  schema 派生纯函数 / 名集精确断言 / run 登记表（容量/查询单测）/ escapeGate deny
  语义（fake gate）/ agent 工具参数组装 / 配置 key 回环；
- **集成验证**：MCP Inspector 真机连通（验收清单）；工具调用经 SDK 内嵌的
  ChannelTransport？——🔴 不进单测（SDK 0.x 行为面 + Ktor 引擎依赖），集成留真机；
- 守护：McpSdkIsolationTest（io.modelcontextprotocol 三文件白名单 + ktor 引用单点 +
  白名单锚防腐化）+ NavTest 断言面（入口组 2 成员）。

## D9 jpackage 打包面

Ktor CIO 是纯 JDK 网络栈（无 netty native）——jlink 模块面冲击小；但 Ktor 依赖
`jdk.unsupported`（sun.misc）等非常规模块——打包后启动验证进验收清单（此前审计
报告已有 jlink 裁剪两条阻断的先例教训，MCP server 起不起得来要真机包验）。
