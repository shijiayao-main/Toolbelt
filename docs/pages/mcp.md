# MCP 服务（本地能力外放）

文件（`feature-mcp` 模块根 `feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/`）：

| 文件 | 职责 |
|------|------|
| `McpContributions.kt` | 导航注册出口（navItem SHARE / label「MCP 服务」/ group=ENTRY 入口组第二成员） |
| `McpServerManager.kt` | 生命周期门面（object，AppLifecycleParticipant id="mcp-expose"；状态机 + McpEngine 接缝） |
| `McpToolRegistrar.kt` | 工具面映射单一真源（描述符 → MCP Tool schema 派生 + 围栏装配 + 调用分发） |
| `McpAgentTools.kt` | agent 会话三工具（workbench_analyze / distill_run / workbench_status） |
| `McpRunRegistry.kt` | run 登记表（stateless 会话模型：runId → RunHandle，容量 10） |
| `McpExposeViewModel.kt` | 页面 VM（零 Ktor/SDK 类型；配置态 + manager 状态镜像 + 工具清单直读） |
| `McpExposePage.kt` | 页面（状态卡 + 开关/端口 + 工具清单 + 日志台；headerSticky 吸顶头） |

配套（`:core`）：`core/llm/LlmConfigStore.kt`（新 key `mcp_server_enabled` / `mcp_server_port`）、
`core/llm/tools/AgentToolCatalog.kt`（描述符面单一真源）。

> 产品定位：本地工具与 agent 能力经 MCP 协议（官方 Kotlin SDK，streamable HTTP）递给外部
> MCP client（Claude Code / IDE 等）。**入口组第二成员**（第一条外放通道是飞书桥接）。
> 施工规格见 `openspec/changes/archive/2026-09-20-mcp-expose/`（design D1~D9）。

---

## 依赖形态（design D1/D9）

- **MCP Kotlin SDK 0.15.0**（`io.modelcontextprotocol:kotlin-sdk-server`，官方 KMP 产物
  JVM 11+——宿主 jvmTarget 25 合规）；🔴 SDK POM 的 ktor-server-* 全部 scope=runtime ⇒
  `embeddedServer(CIO)` / `Application` 等 Ktor 编译期类型必须显式声明
  `ktor-server-core` + CIO 引擎；
- **Ktor 版本双线**：`ktor-sdk = "3.5.1"`（本域，对齐 SDK 传递需求）独立于
  `ktor = "3.3.3"`（:ui 图片库既有线）——**不共用版本 ref**，避免 SDK 降级拉扯；
- 🔴 `io.modelcontextprotocol.*` / `io.ktor.*` 类型只许出现在三装配层文件
  （McpServerManager / McpToolRegistrar / McpAgentTools）+ jvmTest 两测试——
  `McpSdkIsolationTest`（composeApp `src/jvmTest/.../arch/`）四断言钉死；
- `feature-mcp/build.gradle.kts` 必须显式 `alias(libs.plugins.kotlinSerialization)`
  （convention 只带依赖不带插件——缺它 `@Serializable Args` 不生成 companion serializer，
  `serializer(KType)` 反射直接炸「Serializer not found」）。

## 服务生命周期（McpServerManager——design D6/D2）

```kotlin
object McpServerManager : AppLifecycleParticipant {
    data class Status(val running: Boolean, val port: Int, val detail: String?)
    val status: StateFlow<Status>                    // 页面状态卡数据源（零 Ktor/SDK 类型）
    fun startNow(port: Int)                           // 页面开关：起 embeddedServer CIO（127.0.0.1 绑定写死）
    fun stopNow()                                     // 幂等停（取消在途 run + 停 server）
    fun registerRunCancelHook(hook: () -> Unit)       // McpRunRegistry.cancelAll 装配点
    var engineForTest: McpEngine                      // 测试缝：注入 fake 引擎
}
```

- **状态机**：`STOPPED → STARTING → RUNNING ⇄ STOPPED`；启动失败（端口占用）→ `FAILED`
  带 reason（文案「端口被占用，请修改端口」），**不崩溃**；
- **默认恒不自动起**（design D6）：`start(scope)`（AppHost 参与者）只记录 scope + 挂 run
  取消钩，真正起 server 只由页面开关 `startNow` 触发；`shutdown()` = `stopNow()`；
- **生产引擎 DefaultEngine**：`embeddedServer(CIO, host="127.0.0.1", port) { mcpStatelessStreamableHttp { McpToolRegistrar.buildServer() } }.start(wait=false)`
  + `awaitPortBound(port)`（socket 探询 5s 上限）——🔴 Ktor `start(wait=false)` 异步
  bind 竞态：返回时端口可能未监听，不等待的话 client 立即连接被拒（`--rerun-tasks` 下
  必炸、普通跑侥幸过的假绿）；
- 🔴 `mcpStatelessStreamableHttp` 是 `Application` **扩展函数**——必须 import
  （`io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp`），全限定名
  调用 Unresolved。

## 工具面映射（McpToolRegistrar——design D3/D4）

**外放名集 `MCP_EXPOSE_NAMES`（17 件）**：

| 族 | 工具 |
|----|------|
| fs.extended 取证七件 | read_file / grep / glob / hash_file / hex_dump / strings / convert_timestamp |
| knowledge 检索三件 | search / read / lookup_tag |
| 确定性管线 | log.analyze_log |
| log.* 确定性六件 | decrypt / filter_by_tag / filter_by_time / filter_by_range / split_by_size / probe_file |

**不外放**：🔴 `memory.*`（个人记忆不进外部 client）、🔴 `write_todos`（桌面会话件）、
🔴 `fs.*` 基础四件（decrypt_file/extract_archive 桌面已有专属页；preview_text/list_dir
由 fs.extended.read_file 覆盖）。

**schema 派生（纯函数 `toolSchemaOf`）**：Koog `Tool.descriptor` 已从 @Serializable Args
反射出 requiredParameters/optionalParameters（SchemaGenerator 真源）——本层只做 Koog 参数
类型 → JSON Schema 类型的小映射（String→string / Integer→integer / Float→number /
Boolean→boolean / List→array+items / Enum→enum / AnyOf→anyOf，其他回落 string）；
描述 = `descriptor.uiHint?.description ?: descriptor.description`。

**🔴 双名域查表**：MCP 工具名 = 目录名（`log.decrypt` 点分命名空间——外放契约），Koog
`ToolRegistry` 内注册名 = 工具实现旧名（`decrypt_log`——存量 LLM 提示词兼容面）。查表经
`ToolDispatcher.toolOf`：目录名直查 → `descriptor.aliases` 逐个查（`registry.getToolOrNull(目录名)` 恒 miss）。

**🔴 args 解码坑**：`typeToken<Args>()` 生成 `KotlinTypeToken`（KType 载体）**不是**
`KSerializerTypeToken`——cast 取 `.serializer` 恒 null。正解：KSerializerTypeToken 直取
`.serializer`，KotlinTypeToken 经 `kotlinx.serialization.serializer(token.type)` 反射；
`ignoreUnknownKeys = true` 容忍 client 多传字段。

**围栏（deny-only——design D4）**：`ToolExecutionContext.withAllowedRoots(getSourceRoots)`
+ `escapeGate` 恒 `false`——远程 client 无法响应桌面 HITL 弹窗，fail-safe（同
McpPermissionServer「无法确认即拒绝」哲学）；拒绝文案带授权目录清单（= AI 设置页
「源码目录」allowlist），JLog 补记排障。

## agent 会话外放（McpAgentTools——design D2/D5）

三工具（schema 手写——MCP 专属面不经 Koog 描述符派生）：

| 工具 | 语义 |
|------|------|
| `workbench_analyze` | 启动日志根因研判 agent（后台长流程）→ 即时返回 runId |
| `distill_run` | 启动知识蒸馏 agent（**审批前形态**——ProposalBuffer 暂存不写库）→ 返回 runId |
| `workbench_status` | 按 runId 两段式查询（phase / progressNote / 结果文本 / 产物路径清单） |

**stateless 会话模型（D2）**：每次 tools/call = 独立 run（runId + McpRunRegistry 登记 +
后台 scope 执行）；client 断连不杀 run，在途时即时返回 runId + 说明。**progress 通知实做
降级**：ProgressToken 提取链在 stateless 下需跨请求上下文——首版不接，进度经
`workbench_status` 查询承载；🔴 源码 OPEN 登记在 `McpToolRegistrar.progressNotification`
（re-entry 条件 = 客户端反馈需要推送进度时接 sink→progressNotification——「仅 docs 一句
零源码锚点」的登记形态已随 2026-10-09 修复批补源码锚）。

- 研判执行体：`WorkbenchPrompts.ANALYZE_PROMPT` + `McpAgentTools.WORKBENCH_NAMES`
  （16 件 = 取证七件 + knowledge 三件 + 时间过滤两件 + 侦察三件 + analyze_log——
  工作台链同配方去 memory 四件，🔴 memory.* 不外放（个人记忆不进外部 client）、
  🔴 write_todos 不外放（桌面会话件）均为 prompt 豁免面。feature 互不依赖铁律下
  不可引用 `WorkbenchSession.WORKBENCH_NAMES`，值同源自持 + :core 真源单源引用
  （knowledge/侦察两组），与 prompt「可用工具」节的一致性由
  `McpAgentToolNamesGuardTest` 钉死）；`follow_up_context` 参数承载追问链（原报告文本）；
- 蒸馏执行体：`KoogDistillPrompts.DISTILL_PROMPT` 审批前形态（探索+执行一次直行；
  候选文本拼装随结果回传，入库走桌面蒸馏页人工审阅）；`source_dir` 授权目录前置校验
  （canonical 前缀比对，deny-only 语义 start 前即可判定）；
- 预算与超时：`getWorkbenchMaxIterations` / `getDistillMaxIterations`（既有 key 零新增）；
  整体超时 = `timeoutSeconds × OVERALL_TIMEOUT_ROUNDS(6)`（工作台/蒸馏同款换算）；
- 未配 LLM 端点：agent 两工具报错指引去 AI 设置页（确定性工具面不受影响）。

## run 登记表（McpRunRegistry）

```kotlin
object McpRunRegistry {
    const val MAX_RUNS = 10
    enum class Phase { RUNNING, COMPLETED, CANCELLED, FAILED }
    class RunHandle(val runId: String, val toolName: String) {
        val phase: StateFlow<Phase>
        val progressNote: MutableStateFlow<String>
        val result: MutableStateFlow<String?>          // 研判报告 / 蒸馏候选产物
        val artifacts: MutableStateFlow<List<String>>  // 阶段产物路径（中止保全）
        fun complete(text) / cancelWithPartial(partial) / fail(reason)
    }
    fun register(runId, toolName): RunHandle?   // 超容 null（调用方译「在途会话过多」）
    fun find(runId) / cancelAll() / cancel(runId) / sweepFinished()
}
```

- **容量 10 防耗尽**（研判/蒸馏是长流程，无上限会堆积）；超容拒绝新会话；
  🔴 **`register` 容量判定前先 `sweepFinished()`**（2026-10-09 修复批）——sweep 的生产
  调用点全在 register 成功路径之后，满表被拒一次后 sweep 永不可达 = 结构性死锁
  （峰值 10 run + 1 终态即触发），前置后「请稍后重试」文案才可能成立；
- `sweepFinished` 用 `entries.iterator()` 逐删（🔴 `ConcurrentHashMap.values.removeAll`
  Kotlin 扩展语义存疑不可用）；
- **在途数 = RUNNING 相位计数**（非表行数）：`activeCount` 只数 `Phase.RUNNING`——
  终态 run 在 sweep 前仍占表行，按行数计会把终态算在途（「N/10」pill / 端口确认框
  「中止全部 N 个在途」/ 端口提交门控三消费点全失真，全终态仍拦端口修改）；
  🔴 `RunHandle.complete/cancelWithPartial/fail` 三终态迁移点必 bump `runsVersionSink`
  （在途数响应式化的观测面——不 bump 则计数等到 sweep 才修正）；
- **取消保全**：`cancelWithPartial` 以「【已中止】部分结果：」标记回传已产出产物
  （研判 artifacts 保留 / 蒸馏候选标题清单保留）。

## 配置 key（LlmConfigStore）

| key | 缺省 | 说明 |
|-----|------|------|
| `mcp_server_enabled` | `false` | 服务开关（恒默认关——design D6 不自动起） |
| `mcp_server_port` | `8765`（`DEFAULT_MCP_PORT`） | 端口（校验域 `MCP_PORT_RANGE: IntRange = 1024..65535`） |

- 🔴 `MCP_PORT_RANGE` 是 `val` 非 `const`（Kotlin 只允许原始类型/String const）；
- 端口越界存量读侧回落缺省（`getMcpServerPort` takeIf 域过滤）；写侧 VM 校验域内才落盘；
- 端口变更语义：运行中即时重启（停 → 按新端口起），未运行只落配置。

## 页面（McpExposePage——design D7 全 token 化零白名单）

结构（管理面——非工作台三栏）：状态卡（运行态 Badge / 端点 URL + CopyIconButton /
在途会话数 / 失败原因明示）→ 服务区（AppSwitchRow + CommitNumberField 校验域）→
工具清单 LazyColumn（名称/描述/写盘风险 Badge——描述符直读与注册面单一真源一致）→
日志台（VM 事件流环形 200 行；`heightIn(min = mcpLogHeightMin, max = mcpLogHeightMax)`
定高窗内 LazyColumn 自滚 + `BottomFollowScrollEffect(contentVersion = logLines.hashCode())`
贴底跟随——🔴 环形封顶后 size 恒 200，contentVersion 必须用内容哈希否则追加不跟随；
无 max 封顶时新行恒落视口外的交互缺陷已收口）。

VM 隔离纪律：🔴 构造零网络/零 server 触达（init 只读配置 + 镜像 manager 状态流）；
开关翻转 = 配置先行（写 key）再驱动 manager 即时启停。

## 测试面（design D8）

| 测试文件 | 覆盖 |
|---------|------|
| `McpServerManagerTest`（9） | 状态机迁移 / fake 引擎注入 / 启停幂等 / 失败明示 |
| `McpToolRegistrarTest`（12） | 名集精确性 / schema 派生 / dispatch 回环（SDK 类型直测） |
| `McpAgentToolsTest`（7） | 参数校验 / 端点检查 / status 查询 |
| `McpAgentToolNamesGuardTest`（4） | 🔴 研判名集守护：16 件精确集合 / 目录可解析 / prompt「可用工具」节全覆盖（防再漂真门禁）/ memory·distill·todos·ask_user 恒不进 |
| `McpRunRegistryTest`（9+） | 容量 / 终态迁移 / 取消保全 / 清退；满表 +1 终态 → register 成功、activeCount 只计 RUNNING、三终态迁移点 bump version（2026-10-09 修复批补） |
| `McpServerSmokeTest`（2） | 🔴 **常驻防线**：真起 server + HTTP initialize/tools/list 断言 |

- 冒烟测试**保留为常驻**而非验收后删——SDK 0.x 升级最先报警的回归面（D9 风险面唯一
  自动化覆盖）；端口用 18765/18766 高位段避生产口；
- `McpSdkIsolationTest`（composeApp arch/）四断言：A 组 SDK markers 白名单 5 文件、
  B 组 `io.ktor` 白名单 = 三装配层 + `ui/.../AvatarImage.kt`（coil ktor3 既有线）、
  D 组构建脚本坐标单点（feature-mcp/build.gradle.kts + libs.versions.toml）、
  防空扫描（白名单文件须在位且真实引用 SDK 面）。
