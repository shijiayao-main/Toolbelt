# 配置域施工规格（config-domain-spec.md）

> **里程碑**：M4-T5（配置域）与 M6-T13（schema 驱动 UI）共用。
> **唯一真源方案**：`重写飞书桥接_ae9f1206.md`。
> **行为基线**：同目录 `behavior-contract.md`（550 条断言）。
> **SDK 隔离契约**：同目录 `gateway-contract.md`（另一 agent 并行撰写，本文档不重复其内容）。
> **本文档目标**：让 T5 与 T13 施工者**零决策开工**。

---

## 1. 落盘与路径

### 1.1 数据根

| 宿主 | 注入方式 | 数据根 |
|---|---|---|
| **App**（`:feature-larkbridge`） | `FileUtils.getLarkDir()`（新增公开访问器，见 §1.3） | `~/.easy_dev_toolbox/lark/` |
| **CLI**（`:lark-bridge` shadowJar） | `--data-dir <path>` 旗标；缺省时 `File(System.getProperty("user.home"), ".easy_dev_toolbox/lark")` | 同上 |

### 1.2 `ConfigPaths` 完整派生表

定义位置：`lark-bridge/config/ConfigPaths.kt`（lib 侧纯数据类，接受 `Path dataRoot` 构造参数）。

| 路径 | 派生规则 | 用途 |
|---|---|---|
| `configFile` | `<dataRoot>/bridge-config.json` | 配置文档唯一真源 |
| `configBackup` | `<dataRoot>/bridge-config.json.bak` | 首次写前备份 |
| `auditFile` | `<dataRoot>/audit.jsonl` | 审计日志（JSONL，字段 schema 与旧 `AuditLogger` 完全一致） |
| `senderStatsFile` | `<dataRoot>/sender_stats.jsonl` | 发送者统计（JSONL，schema 与旧一致） |
| `locksDir` | `<dataRoot>/locks/` | 单实例互斥锁目录 |
| `lockFile(appId)` | `<dataRoot>/locks/<sha256(appId).take(16)>.lock` | FileChannel.tryLock |
| `downloadsTmpPrefix` | `<workspaceRoot>/downloads/tmp-*` | 文件消息下载临时目录（由 `DownloadPathPolicy` 管理） |

**实测事实**：`~/.easy_dev_toolbox/lark/` 下已存在旧实现产出的 `audit.jsonl` 与 `sender_stats.jsonl`（用户真实使用过桥接）。**新旧产物必须落在同一目录、且 audit 字段 schema 与旧完全一致**（旧文件仍要能被新 UI 读——BC-306/BC-308 的 `backend`/`kind` 可空字段向后兼容契约）。

### 1.3 `FileUtils` 新增公开访问器（M6-T12 对 `:core` 的改动之一）

```kotlin
// core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/data/FileUtils.kt
fun getLarkDir(): File = File(getAppDataDir(), "lark")
```

- 返回路径：`~/.easy_dev_toolbox/lark`（`getAppDataDir()` 已有，返回 `File(user.home, ".easy_dev_toolbox")`）
- **注入到 lib 侧的方式**：feature 侧 `LarkBridgeManager` 调用 `FileUtils.getLarkDir().toPath()` 得到 `Path`，传入 `ConfigPaths(dataRoot = path)` 与 `ConfigRepository(paths)`。**lib 侧只接受 `Path`/`File` 参数，不引用 `FileUtils`**（jvmTarget 11 不能依赖 jvmTarget 21 的 `:core`）。

### 1.4 旧实现硬编码对照（已退役）

旧 `LarkBridgeManager.kt:45` 硬编码 `private const val AUDIT_DIR = "~/.easy_dev_toolbox/lark"`；旧 VM `STATS_DIR` 同源。新架构统一由 `ConfigPaths` 派生，不再硬编码。

---

## 2. `BridgeJson` 配方

唯一定义位置：`lark-bridge/config/BridgeJson.kt`。

```kotlin
val BridgeJson = Json {
    ignoreUnknownKeys = true      // 前向兼容：新版本写的键不被旧版本吃掉
    encodeDefaults = true         // 整体写回时字段完整，便于人肉审阅
    explicitNulls = false         // null 字段不写键（可空字段如 errorCode 为 null 时缺键）
    classDiscriminator = "kind"   // routes sealed 多态的判别字段
    prettyPrint = true            // 人可读（15-30KB 文档，磁盘代价可忽略）
}
```

**与仓库既有配方的对齐点**：旧 `LarkBridgeConfigStore.kt:19-22` 的 `bridgeJson` 只有 `ignoreUnknownKeys=true; encodeDefaults=true`；新增 `explicitNulls=false`（对齐旧 `AuditLogger.kt:53` 的 `encodeDefaults=false` 语义——可空字段为 null 时不写键）、`classDiscriminator="kind"`（routes 多态所需）、`prettyPrint=true`（用户可直接编辑 JSON）。

�� **严禁复用禁令（§16.7 裁定）**：`BridgeJson` **只用于 `bridge-config.json`**，**严禁**被 `audit.jsonl` / `sender_stats.jsonl` 复用——`encodeDefaults=true` 会让可空字段在 null 时写出默认值键（违反 BC-306/BC-308「null 时缺键」契约），`prettyPrint=true` 会把一条记录拆成多行（`JsonlTailReader` 按行解析直接崩），`classDiscriminator="kind"` 会污染审计 schema。审计/统计必须使用**独立实例**：

```kotlin
// lark-bridge/observe/AuditJson.kt —— 与 BridgeJson 严格分离
val AuditJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false    // 🔴 null 字段缺键（对齐旧 AuditLogger.kt:53）
    explicitNulls = false
    prettyPrint = false       // 🔴 jsonl 一行一条
    // 🔴 不设 classDiscriminator
}
```

完整对照表与现实约束（`~/.easy_dev_toolbox/lark/audit.jsonl` 已有用户真实数据须双向兼容）见 **§16.7**。

---

## 3. 完整 JSON schema v1

### 3.1 文档结构概览

```jsonc
{
  "schemaVersion": 1,
  "connection":    { ... },
  "access":        { ... },
  "workspace":     { ... },
  "runtime":       { ... },
  "agent":         { ... },
  "fileMessage":   { ... },
  "observability": { ... },
  "routes":        [ ... ],
  "experimental":  { }
}
```

### 3.2 逐字段表

#### 3.2.1 `connection`（ConfigGroup.CONNECTION）

| JSON 路径 | Kotlin 类型 | 默认值 | 语义 | 校验规则 | applyMode | 来源 |
|---|---|---|---|---|---|---|
| `connection.appId` | `String` | `""` | 飞书自建应用 App ID | 非空白（必填条件见 §16⑤） | RESTART | 旧 key `lark_bridge_app_id` |
| `connection.appSecret` | `String` | `""` | 飞书自建应用 App Secret | 非空白（必填条件见 §16⑤） | RESTART | 旧 key `lark_bridge_app_secret` |
| `connection.region` | `String` | `"feishu"` | 区域：feishu / lark | Choice(feishu, lark) | RESTART | 旧隐含常量 |
| `connection.autoStart` | `Boolean` | `false` | 应用启动时自动连接 | — | HOT | 新增 |
| `connection.dryRun` | `Boolean` | `false` | 干跑模式 | — | HOT | 旧 key `lark_bridge_dry_run` |
| `connection.testChatId` | `String` | `""` | 测试消息目标 chat | — | HOT | 旧 key `lark_bridge_chat_id` |

#### 3.2.2 `access`（ConfigGroup.ACCESS）

| JSON 路径 | Kotlin 类型 | 默认值 | 语义 | 校验规则 | applyMode | 来源 |
|---|---|---|---|---|---|---|
| `access.mode` | `String` | `"allowlist"` | 访问控制模式 | Choice(allowlist, blocklist) | HOT | 旧 key `lark_bridge_access_mode`（whitelist→allowlist） |
| `access.allowedSenders` | `List<String>` | `[]` | 白名单 open_id 列表 | — | HOT | 旧 key `lark_bridge_allowed_senders`（逗号分隔→数组） |
| `access.blockedSenders` | `List<String>` | `[]` | 黑名单 open_id 列表 | — | HOT | 旧 key `lark_bridge_blocked_senders` |
| `access.rejectionReply` | `String` | `""` | 拒绝回复模板（{sender} 占位） | — | HOT | 旧 key `lark_bridge_rejection_reply` |
| `access.groupRequireMention` | `Boolean` | `true` | 群聊须 @机器人 | — | HOT | 旧 key `lark_bridge_group_require_mention` |

#### 3.2.3 `workspace`（ConfigGroup.WORKSPACE）

| JSON 路径 | Kotlin 类型 | 默认值 | 语义 | 校验规则 | applyMode | 来源 |
|---|---|---|---|---|---|---|
| `workspace.root` | `String` | `""` | 工作区根目录 | —（空串=未设置；格式与存在性均延迟校验，见 §16⑤） | RESTART | 旧 key `lark_bridge_workspace_dir` |
| `workspace.extraRoots` | `List<String>` | `[]` | 额外允许根目录列表 | — | RESTART | 旧 key `lark_bridge_workspace_whitelist`（JSON 数组字符串→List） |
| `workspace.agentWorkDir` | `String` | `""` | agent CLI 工作目录（空=回退 root） | — | RESTART | 旧 key `lark_bridge_agent_work_dir`（需 ~ 展开） |
| `workspace.envAllowlist` | `List<String>` | 见下方 | 用户可配置的额外环境变量白名单 | — | RESTART | 裁决修正（旧 BASE_ENV_KEYS 11 键） |
| `workspace.outputCharset` | `String` | `"utf-8"` | agent 子进程输出编码 | Choice(utf-8, native) | RESTART | 方案新增 |

**`workspace.envAllowlist` 默认值（裁决 1 修正）**：

```json
["PATH","HOME","LANG","LC_ALL","TZ","http_proxy","https_proxy","no_proxy","HTTP_PROXY","HTTPS_PROXY","NO_PROXY"]
```

共 **11 键**，逐字来自旧 `SandboxedProcessFactory.kt:57-61` 的 `BASE_ENV_KEYS`。方案原 8 键**缺 3 个小写代理变体**，照方案实现会导致 Linux 下 agent 子进程静默失去代理能力。

**`EnvAllowlist` 语义定义**：用户可配置的 `workspace.envAllowlist` ∪ **平台必需基础键**（Windows 追加 `SystemRoot`/`SystemDrive`/`USERPROFILE`/`HOMEDRIVE`/`HOMEPATH`/`APPDATA`/`LOCALAPPDATA`/`COMSPEC`/`PATHEXT`/`TEMP`/`TMP` 共 11 键，不可被用户配置移除）。实现位置：`exec/EnvAllowlist.kt`，纯函数 `effectiveKeys(userAllowlist: Set<String>, isWindows: Boolean): Set<String>`。

Windows 必需键来自旧 `SandboxedProcessFactory.kt:68-71` 的 `WINDOWS_REQUIRED_ENV_KEYS`（11 键）。缺 `SystemRoot`/`COMSPEC` 时 node 及多数 Win32 程序初始化异常；缺 `USERPROFILE`/`APPDATA` 时 claude 找不到凭证目录。

#### 3.2.4 `runtime`（ConfigGroup.RUNTIME）

| JSON 路径 | Kotlin 类型 | 默认值 | 语义 | 校验规则 | applyMode | 来源 |
|---|---|---|---|---|---|---|
| `runtime.maxConcurrentAgents` | `Int` | `2` | 同时处理的 agent 任务上限 | coerceIn(1, 16) | HOT | 旧 `maxConcurrentTasks` |
| `runtime.chatQueueLimit` | `Int` | `2` | 同一 chat 并发排队上限 | coerceIn(1, 8) | HOT | 旧 `queueLimit` |
| `runtime.queueWaitSeconds` | `Long` | `30` | 排队等待超时秒数 | coerceIn(5, 300) | HOT | 旧 `queueWaitTimeoutSeconds` |
| `runtime.busyReplyCooldownSeconds` | `Long` | `30` | 繁忙回复按 chat 合批冷却 | coerceIn(5, 300) | HOT | 新增 |
| `runtime.taskTimeoutSeconds` | `Long` | `60` | 通用任务超时秒数 | coerceIn(10, 3600) | HOT | 旧 `taskTimeoutSeconds` |
| `runtime.agentTimeoutMinutes` | `Long` | `10` | agent 任务超时分钟数 | coerceIn(1, 120) | HOT | 旧 key `lark_bridge_agent_timeout_minutes` |
| `runtime.agentIdleTimeoutSeconds` | `Long` | `120` | agent 空闲看门狗秒数 | coerceIn(10, 600) | HOT | 新增（D10） |
| `runtime.unhandledReply` | `String` | `""` | 未命中兜底回复 | — | HOT | 旧 key `lark_bridge_unhandled_reply` |

#### 3.2.5 `agent`（ConfigGroup.AGENT）

| JSON 路径 | Kotlin 类型 | 默认值 | 语义 | 校验规则 | applyMode | 来源 |
|---|---|---|---|---|---|---|
| `agent.enabled` | `Boolean` | `false` | 启用 agent 执行器 | — | RESTART | 旧 key `lark_bridge_agent_enabled` |
| `agent.backend` | `String` | `"claude"` | 后端类型 | Choice(claude, qoder, copilot, custom) | RESTART | 旧 key `lark_bridge_agent_backend` |
| `agent.customTemplate` | `String` | `""` | custom 后端命令模板 | backend==custom 时须含 `{prompt}` | RESTART | 旧 key `lark_bridge_agent_custom_template` |
| `agent.systemPrompt` | `String` | `""` | 注入 agent 的 system prompt | — | RESTART | 旧 key `lark_bridge_agent_system_prompt` |
| `agent.permissionPreset` | `String` | `"safe"` | 权限档位 | Choice(readonly, safe, workspace, all, custom) | RESTART | 旧 key `lark_bridge_agent_permission_preset` |
| `agent.allowedTools` | `String` | `""` | 自定义工具限制（custom 档透传） | — | RESTART | 旧 key `lark_bridge_agent_allowed_tools` |
| `agent.prohibitDelete` | `Boolean` | `true` | 禁止删除类高危操作 | — | RESTART | 旧 key `lark_bridge_agent_prohibit_delete` |
| `agent.safetyPromptEnabled` | `Boolean` | `true` | 安全提示注入开关 | — | RESTART | 旧 key `lark_bridge_agent_safety_prompt_enabled` |
| `agent.session.capacity` | `Int` | `64` | 会话 LRU 容量 | coerceIn(8, 256) | HOT | 新增 |
| `agent.session.keyByThread` | `Boolean` | `true` | 会话按 thread 隔离 | — | HOT | 新增（D13） |
| `agent.progress.enabled` | `Boolean` | `true` | 进度回显开关 | — | HOT | 旧 `progressUpdatesEnabled` |
| `agent.progress.patchIntervalMillis` | `Long` | `2000` | 进度卡 patch 合并窗口毫秒 | coerceIn(250, 10000) | HOT | 旧 `progressPatchIntervalMillis`（裁决 2） |
| `agent.progress.maxEntries` | `Int` | `12` | 进度卡滚动窗口事件条数 | coerceIn(3, 50) | HOT | 旧 `progressMaxEntries` |
| `agent.progress.cardMaxChars` | `Int` | `5000` | 进度卡内容字符上限 | coerceIn(500, 20000) | HOT | 旧 `progressCardMaxChars`（裁决 3） |
| `agent.confirm.enabled` | `Boolean` | `false` | 运行时确认开关 | — | HOT | 旧 key `lark_bridge_agent_confirm_enabled` |
| `agent.confirm.timeoutSeconds` | `Long` | `60` | 确认超时秒数 | coerceIn(10, 3600) | HOT | 旧 key `lark_bridge_agent_confirm_timeout_seconds` |
| `agent.confirm.selfCheck` | `String` | `"async"` | 自检模式 | Choice(async, sync, off) | HOT | 新增（D14） |

**裁决 3 说明**：`agent.progress.cardMaxChars=5000` 是「进度卡内容上限」（业务偏好，用户可调），而网关 `Tuning.kt` 的 `cardByteBudget=24576` 是「网关侧卡片体硬预算」（协议约束，不可配置）。两者职责边界清晰：进度卡在 5000 字符处截断后交给网关，网关按 24576 字节预算做分段轮换。`SegmentRotationTest` 必须在 `CardStreamSink` 层直接喂 40KB 来测（不经进度卡链路），否则永不触发。

#### 3.2.6 `fileMessage`（ConfigGroup.FILE_MESSAGE）

| JSON 路径 | Kotlin 类型 | 默认值 | 语义 | 校验规则 | applyMode | 来源 |
|---|---|---|---|---|---|---|
| `fileMessage.enabled` | `Boolean` | `true` | 处理文件消息开关 | — | HOT | 旧 key `lark_bridge_file_message_enabled` |
| `fileMessage.maxFileSizeBytes` | `Long` | `104857600` | 下载大小上限（100MB）。**存储字节，UI 以 MB 显示**（见 §16②） | coerceIn(1048576, 1073741824) | HOT | 旧 key `lark_bridge_file_max_size_mb` ×1048576 |
| `fileMessage.textInlineMaxBytes` | `Long` | `262144` | 纯文本直读上限（256KB） | coerceIn(1024, 1048576) | HOT | 旧 `textInlineMaxBytes` |
| `fileMessage.autoExtract` | `Boolean` | `true` | 压缩包自动解包 | — | HOT | 旧 key `lark_bridge_file_auto_extract` |
| `fileMessage.allowedExtensions` | `String` | `".log,.txt,.xlog,.alog,.zip,.tar,.tar.gz,.tgz,.gz"` | 允许的扩展名 | — | HOT | 旧 `allowedExtensions` |
| `fileMessage.triggerPrefix` | `String` | `""` | 文件消息触发词前缀 | — | HOT | 旧 `triggerPrefix` |
| `fileMessage.imageBatchWindowMillis` | `Long` | `500` | 图片批处理窗口 | coerceIn(100, 5000) | HOT | 新增 |

#### 3.2.7 `observability`（ConfigGroup.OBSERVABILITY）

| JSON 路径 | Kotlin 类型 | 默认值 | 语义 | 校验规则 | applyMode | 来源 |
|---|---|---|---|---|---|---|
| `observability.auditEnabled` | `Boolean` | `true` | 审计日志开关 | — | HOT | 新增（旧隐含开启） |
| `observability.senderStatsEnabled` | `Boolean` | `true` | 发送者统计开关 | — | HOT | 新增（旧隐含开启） |
| `observability.auditRotationMb` | `Int` | `8` | 审计轮转大小 MB | coerceIn(1, 64) | HOT | 新增（D6） |
| `observability.auditKeepFiles` | `Int` | `3` | 轮转保留文件数 | coerceIn(1, 10) | HOT | 新增（D6） |
| `observability.logConsole.capacity` | `Int` | `1000` | UI 日志台容量 | coerceIn(100, 5000) | HOT | 旧 `MAX_LOG_LINES` |
| `observability.logConsole.tickerHz` | `Int` | `10` | 日志台刷新频率 | coerceIn(1, 30) | HOT | 新增（D7） |
| `observability.logConsole.followBottom` | `Boolean` | `true` | 自动跟随底部 | — | HOT | 新增 |

#### 3.2.8 `routes`（数组，顺序 == 路由优先级）

见 §4 `Routes.kt` 的 sealed 层级。

#### 3.2.9 `experimental`

| JSON 路径 | Kotlin 类型 | 默认值 | 语义 | 校验规则 | applyMode | 来源 |
|---|---|---|---|---|---|---|
| `experimental` | `JsonObject` | `{}` | 未识别顶层段原样暂存 | — | — | 新增（前向兼容） |

---

## 4. `Routes.kt` 的 sealed 层级

定义位置：`lark-bridge/config/Routes.kt`

### 4.1 `RouteKind` 枚举

```kotlin
enum class RouteKind(val jsonValue: String) {
    SYSTEM("system"),
    REPLY("reply"),
    COMMAND("command"),
    AGENT("agent"),
    FILE("file"),
}
```

### 4.2 `RouteRule` sealed interface

```kotlin
@Serializable
sealed interface RouteRule {
    val id: String
    val enabled: Boolean
}

@Serializable @SerialName("system")
data class SystemRoute(
    override val id: String = "builtin",
    override val enabled: Boolean = true,
) : RouteRule

@Serializable @SerialName("reply")
data class ReplyRoute(
    override val id: String = "",
    override val enabled: Boolean = true,
    val trigger: String = "",
    val match: String = "exact",       // exact | prefix | regex
    val reply: String = "",
) : RouteRule

@Serializable @SerialName("command")
data class CommandRoute(
    override val id: String = "",
    override val enabled: Boolean = true,
    val name: String = "",
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val promptTemplate: String = "",
    val requiresConfirm: Boolean = false,
) : RouteRule

@Serializable @SerialName("agent")
data class AgentRoute(
    override val id: String = "default",
    override val enabled: Boolean = true,
    val triggers: List<String> = listOf("ai", "/ai"),
    val resetCommand: String = "新对话",
    val stopCommand: String = "停止",
) : RouteRule

@Serializable @SerialName("file")
data class FileRoute(
    override val id: String = "file-analysis",
    override val enabled: Boolean = true,
    val triggerPrefix: String = "",
) : RouteRule
```

### 4.3 多态序列化约定

- `classDiscriminator = "kind"`（由 `BridgeJson` 统一配置）
- JSON 中每条 route 必须含 `"kind"` 字段，值为 `RouteKind.jsonValue`
- 数组顺序 == 路由优先级：`PipelineRouter` 按数组序遍历 `routes.filter{enabled}`，`firstOrNull { matches }` 命中即停

### 4.4 `PipelineSnapshot` 预计算与热替换

配置变更时，`PipelineSnapshot` 一次性构建 `List<MatchableHandler>`（从 `routes` 数组 + `HandlerKinds` 注册表派生），存入 `AtomicReference<PipelineSnapshot>`。热路径零查表零分配。HOT applyMode 的 routes 变更通过无锁替换 `AtomicReference` 实现——下一个事件即生效、在途任务不受影响、不断连。

### 4.5 routes 与 BridgeFields 的边界

**routes 是列表型字段，不进 `BridgeFields.ALL` 声明表**。原因：
- `BridgeField<T>` 的 `get`/`set` lambda 针对标量字段设计
- routes 编辑走 `RouteRuleEditor` 的 `when(kind)` 分支（全模块唯一分支点）
- UI 的路由卡是 bespoke 组件（拖拽排序、kind 图标、触发词摘要、enabled 开关、上移/下移/编辑/删除）
- 方案 R15 明确「bespoke 卡不进表，需要特殊布局直接写 Composable——不强行全 schema 化」

**新增一类能力改 4 处**（而非字段表的 2 处）：① `Routes.kt` 加 sealed 子类 + 其字段行 ② `handler/XxxHandler.kt` 实现 ③ `pipeline/HandlerKinds.kt` 注册表加一行 ④ `page/larkbridge/route/RouteRuleEditor.kt` 加一个 `when` 分支。sealed + `classDiscriminator` 让漏写即红构建。

---

## 5. `BridgeFields.ALL` 逐行完整枚举表

### 5.1 类型签名

```kotlin
class BridgeField<T>(
    val id: String,                    // JSON 点路径，全局唯一
    val group: ConfigGroup,            // 分组（对应 UI SectionCard）
    val label: String,                 // UI 标签
    val hint: String,                  // UI 提示文字
    val editor: Editor,                // 编辑器类型
    val secret: Boolean = false,       // 打印/日志/check 输出统一掩码
    val get: (BridgeConfig) -> T,
    val set: (BridgeConfig, T) -> BridgeConfig,
    val validate: (T) -> String? = { null },  // 返回非 null = 校验失败消息
    val applyMode: ApplyMode = ApplyMode.HOT,
)

enum class ApplyMode { HOT, RESTART }

sealed interface Editor {
    data object Text : Editor
    data object Multiline : Editor
    data object Secret : Editor
    data class Number(
        val min: Long,
        val max: Long,
        val displayScale: Long = 1,   // UI 显示换算除数（§16②）
        val unitSuffix: String = "",   // UI 显示单位后缀（§16②）
    ) : Editor
    data object Switch : Editor
    data class Choice(val options: List<String>) : Editor
    data object Directory : Editor
    data class Tags(
        val separator: String = ",",
        val pickDirectory: Boolean = false,  // true=渲染「添加目录」按钮（§16③）
    ) : Editor
}
```

### 5.2 完整枚举表

> **约定**：`get 路径` / `set 路径` 用 Kotlin 属性访问表达式简写；`validate` 为 null 表示无额外校验。

| # | id | group | label | hint | editor | secret | get 路径 | set 路径 | validate | applyMode |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `connection.appId` | CONNECTION | App ID | 飞书自建应用 App ID | Text | false | `it.connection.appId` | `copy(connection=connection.copy(appId=v))` | 非空白（仅 `agent.enabled` 或 `connection.autoStart` 为 true 时必填） | RESTART |
| 2 | `connection.appSecret` | CONNECTION | App Secret | 飞书自建应用密钥（绝不写入日志） | Secret | **true** | `it.connection.appSecret` | `copy(connection=connection.copy(appSecret=v))` | 非空白（同上必填条件） | RESTART |
| 3 | `connection.region` | CONNECTION | 区域 | feishu=国内；lark=海外 | Choice(feishu, lark) | false | `it.connection.region` | `copy(connection=connection.copy(region=v))` | — | RESTART |
| 4 | `connection.autoStart` | CONNECTION | 自动启动 | 应用启动时自动连接桥接 | Switch | false | `it.connection.autoStart` | `copy(connection=connection.copy(autoStart=v))` | — | HOT |
| 5 | `connection.dryRun` | CONNECTION | 干跑模式 | 不起子进程不连 WS，只打印 | Switch | false | `it.connection.dryRun` | `copy(connection=connection.copy(dryRun=v))` | — | HOT |
| 6 | `connection.testChatId` | CONNECTION | 测试 Chat ID | 发送测试消息的目标 | Text | false | `it.connection.testChatId` | `copy(connection=connection.copy(testChatId=v))` | — | HOT |
| 7 | `access.mode` | ACCESS | 访问控制模式 | allowlist=白名单；blocklist=黑名单 | Choice(allowlist, blocklist) | false | `it.access.mode` | `copy(access=access.copy(mode=v))` | — | HOT |
| 8 | `access.allowedSenders` | ACCESS | 白名单 | 允许的发送者 open_id（逗号分隔） | Tags | false | `it.access.allowedSenders` | `copy(access=access.copy(allowedSenders=v))` | — | HOT |
| 9 | `access.blockedSenders` | ACCESS | 黑名单 | 拒绝的发送者 open_id（逗号分隔） | Tags | false | `it.access.blockedSenders` | `copy(access=access.copy(blockedSenders=v))` | — | HOT |
| 10 | `access.rejectionReply` | ACCESS | 拒绝回复 | 被拒时回复模板（{sender} 占位），空=静默 | Text | false | `it.access.rejectionReply` | `copy(access=access.copy(rejectionReply=v))` | — | HOT |
| 11 | `access.groupRequireMention` | ACCESS | 群聊 @门控 | 群聊须 @机器人才触发 | Switch | false | `it.access.groupRequireMention` | `copy(access=access.copy(groupRequireMention=v))` | — | HOT |
| 12 | `workspace.root` | WORKSPACE | 工作区根目录 | agent 沙箱根目录（空=未设置） | Directory | false | `it.workspace.root` | `copy(workspace=workspace.copy(root=v))` | —（无格式校验；`~` 展开由 `exec/Paths.kt` 负责，存在性延迟到 connect()） | RESTART |
| 13 | `workspace.extraRoots` | WORKSPACE | 额外目录 | 多目录白名单（带目录选择器） | **Tags(pickDirectory=true)** | false | `it.workspace.extraRoots` | `copy(workspace=workspace.copy(extraRoots=v))` | — | RESTART |
| 14 | `workspace.agentWorkDir` | WORKSPACE | Agent 工作目录 | 空=回退到工作区根 | Directory | false | `it.workspace.agentWorkDir` | `copy(workspace=workspace.copy(agentWorkDir=v))` | — | RESTART |
| 15 | `workspace.envAllowlist` | WORKSPACE | 环境变量白名单 | 透传给 agent 子进程的环境变量名 | Tags | false | `it.workspace.envAllowlist` | `copy(workspace=workspace.copy(envAllowlist=v))` | — | RESTART |
| 16 | `workspace.outputCharset` | WORKSPACE | 输出编码 | agent 子进程 stdout 编码 | Choice(utf-8, native) | false | `it.workspace.outputCharset` | `copy(workspace=workspace.copy(outputCharset=v))` | — | RESTART |
| 17 | `runtime.maxConcurrentAgents` | RUNTIME | 最大并发 | 同时处理的 agent 任务数 | Number(1, 16) | false | `it.runtime.maxConcurrentAgents` | `copy(runtime=runtime.copy(maxConcurrentAgents=v))` | coerceIn(1, 16) | HOT |
| 18 | `runtime.chatQueueLimit` | RUNTIME | 聊天队列上限 | 同一 chat 排队上限 | Number(1, 8) | false | `it.runtime.chatQueueLimit` | `copy(runtime=runtime.copy(chatQueueLimit=v))` | coerceIn(1, 8) | HOT |
| 19 | `runtime.queueWaitSeconds` | RUNTIME | 排队超时(秒) | 等待许可的超时 | Number(5, 300) | false | `it.runtime.queueWaitSeconds` | `copy(runtime=runtime.copy(queueWaitSeconds=v))` | coerceIn(5, 300) | HOT |
| 20 | `runtime.busyReplyCooldownSeconds` | RUNTIME | 繁忙冷却(秒) | 同 chat 繁忙回复合批间隔 | Number(5, 300) | false | `it.runtime.busyReplyCooldownSeconds` | `copy(runtime=runtime.copy(busyReplyCooldownSeconds=v))` | coerceIn(5, 300) | HOT |
| 21 | `runtime.taskTimeoutSeconds` | RUNTIME | 任务超时(秒) | 通用任务超时 | Number(10, 3600) | false | `it.runtime.taskTimeoutSeconds` | `copy(runtime=runtime.copy(taskTimeoutSeconds=v))` | coerceIn(10, 3600) | HOT |
| 22 | `runtime.agentTimeoutMinutes` | RUNTIME | Agent 超时(分) | agent 任务独立超时 | Number(1, 120) | false | `it.runtime.agentTimeoutMinutes` | `copy(runtime=runtime.copy(agentTimeoutMinutes=v))` | coerceIn(1, 120) | HOT |
| 23 | `runtime.agentIdleTimeoutSeconds` | RUNTIME | 空闲超时(秒) | 无输出即终止 | Number(10, 600) | false | `it.runtime.agentIdleTimeoutSeconds` | `copy(runtime=runtime.copy(agentIdleTimeoutSeconds=v))` | coerceIn(10, 600) | HOT |
| 24 | `runtime.unhandledReply` | RUNTIME | 兜底回复 | 无匹配时回复，空=静默 | Text | false | `it.runtime.unhandledReply` | `copy(runtime=runtime.copy(unhandledReply=v))` | — | HOT |
| 25 | `agent.enabled` | AGENT | 启用 Agent | 关闭后 agent 路由不触发 | Switch | false | `it.agent.enabled` | `copy(agent=agent.copy(enabled=v))` | — | RESTART |
| 26 | `agent.backend` | AGENT | 后端类型 | claude/qoder/copilot/custom | Choice(claude, qoder, copilot, custom) | false | `it.agent.backend` | `copy(agent=agent.copy(backend=v))` | — | RESTART |
| 27 | `agent.customTemplate` | AGENT | 自定义模板 | {prompt} 必填，{session} 可选 | Multiline | false | `it.agent.customTemplate` | `copy(agent=agent.copy(customTemplate=v))` | backend==custom 时须含 `{prompt}` | **HOT** |
| 28 | `agent.systemPrompt` | AGENT | System Prompt | 注入 agent 的系统提示 | Multiline | false | `it.agent.systemPrompt` | `copy(agent=agent.copy(systemPrompt=v))` | — | **HOT** |
| 29 | `agent.permissionPreset` | AGENT | 权限档位 | readonly/safe/workspace/all/custom | Choice(readonly, safe, workspace, all, custom) | false | `it.agent.permissionPreset` | `copy(agent=agent.copy(permissionPreset=v))` | — | **HOT** |
| 30 | `agent.allowedTools` | AGENT | 自定义工具 | 仅 custom 档生效，后端原生语法 | Text | false | `it.agent.allowedTools` | `copy(agent=agent.copy(allowedTools=v))` | — | **HOT** |
| 31 | `agent.prohibitDelete` | AGENT | 禁止删除 | 安全底线：禁止删除类操作 | Switch | false | `it.agent.prohibitDelete` | `copy(agent=agent.copy(prohibitDelete=v))` | — | **HOT** |
| 32 | `agent.safetyPromptEnabled` | AGENT | 安全提示 | 追加安全约束到 prompt 尾部 | Switch | false | `it.agent.safetyPromptEnabled` | `copy(agent=agent.copy(safetyPromptEnabled=v))` | — | **HOT** |
| 33 | `agent.session.capacity` | AGENT | 会话容量 | LRU 会话缓存上限 | Number(8, 256) | false | `it.agent.session.capacity` | `copy(agent=agent.copy(session=session.copy(capacity=v)))` | coerceIn(8, 256) | HOT |
| 34 | `agent.session.keyByThread` | AGENT | 按话题隔离 | 群内不同话题独立会话 | Switch | false | `it.agent.session.keyByThread` | `copy(agent=agent.copy(session=session.copy(keyByThread=v)))` | — | HOT |
| 35 | `agent.progress.enabled` | AGENT | 进度回显 | 流式更新执行过程 | Switch | false | `it.agent.progress.enabled` | `copy(agent=agent.copy(progress=progress.copy(enabled=v)))` | — | HOT |
| 36 | `agent.progress.patchIntervalMillis` | AGENT | 进度间隔(ms) | patch 合并窗口（下限 250ms 防触发频控） | Number(250, 10000) | false | `it.agent.progress.patchIntervalMillis` | `copy(agent=agent.copy(progress=progress.copy(patchIntervalMillis=v)))` | coerceIn(250, 10000) | HOT |
| 37 | `agent.progress.maxEntries` | AGENT | 进度条数 | 滚动窗口保留事件数 | Number(3, 50) | false | `it.agent.progress.maxEntries` | `copy(agent=agent.copy(progress=progress.copy(maxEntries=v)))` | coerceIn(3, 50) | HOT |
| 38 | `agent.progress.cardMaxChars` | AGENT | 卡片字符上限 | 进度卡内容上限（超长丢最旧） | Number(500, 20000) | false | `it.agent.progress.cardMaxChars` | `copy(agent=agent.copy(progress=progress.copy(cardMaxChars=v)))` | coerceIn(500, 20000) | HOT |
| 39 | `agent.confirm.enabled` | AGENT | 运行时确认 | 高风险工具调用经飞书确认 | Switch | false | `it.agent.confirm.enabled` | `copy(agent=agent.copy(confirm=confirm.copy(enabled=v)))` | — | HOT |
| 40 | `agent.confirm.timeoutSeconds` | AGENT | 确认超时(秒) | 超时等同拒绝（下限 10s） | Number(10, 3600) | false | `it.agent.confirm.timeoutSeconds` | `copy(agent=agent.copy(confirm=confirm.copy(timeoutSeconds=v)))` | coerceIn(10, 3600) | HOT |
| 41 | `agent.confirm.selfCheck` | AGENT | 自检模式 | async=后台探测；sync=启动阻塞；off=跳过 | Choice(async, sync, off) | false | `it.agent.confirm.selfCheck` | `copy(agent=agent.copy(confirm=confirm.copy(selfCheck=v)))` | — | HOT |
| 42 | `fileMessage.enabled` | FILE_MESSAGE | 文件消息 | 处理群聊/单聊文件 | Switch | false | `it.fileMessage.enabled` | `copy(fileMessage=fileMessage.copy(enabled=v))` | — | HOT |
| 43 | `fileMessage.maxFileSizeBytes` | FILE_MESSAGE | 文件大小上限(MB) | UI 显示 MB，存储字节（默认 100MB） | **Number(1048576, 1073741824, displayScale=1048576, unitSuffix=" MB")** | false | `it.fileMessage.maxFileSizeBytes` | `copy(fileMessage=fileMessage.copy(maxFileSizeBytes=v))` | coerceIn(1048576, 1073741824) | HOT |
| 44 | `fileMessage.textInlineMaxBytes` | FILE_MESSAGE | 文本直读上限 | 字节（默认 256KB） | Number(1024, 1048576) | false | `it.fileMessage.textInlineMaxBytes` | `copy(fileMessage=fileMessage.copy(textInlineMaxBytes=v))` | coerceIn(1024, 1048576) | HOT |
| 45 | `fileMessage.autoExtract` | FILE_MESSAGE | 自动解包 | zip/tar.gz/tgz 自动解压 | Switch | false | `it.fileMessage.autoExtract` | `copy(fileMessage=fileMessage.copy(autoExtract=v))` | — | HOT |
| 46 | `fileMessage.allowedExtensions` | FILE_MESSAGE | 允许扩展名 | 逗号分隔，空=不限 | Text | false | `it.fileMessage.allowedExtensions` | `copy(fileMessage=fileMessage.copy(allowedExtensions=v))` | — | HOT |
| 47 | `fileMessage.triggerPrefix` | FILE_MESSAGE | 触发前缀 | @机器人 + 前缀才触发 | Text | false | `it.fileMessage.triggerPrefix` | `copy(fileMessage=fileMessage.copy(triggerPrefix=v))` | — | HOT |
| 48 | `fileMessage.imageBatchWindowMillis` | FILE_MESSAGE | 图片批处理窗口(ms) | 多图合并等待窗口 | Number(100, 5000) | false | `it.fileMessage.imageBatchWindowMillis` | `copy(fileMessage=fileMessage.copy(imageBatchWindowMillis=v))` | coerceIn(100, 5000) | HOT |
| 49 | `observability.auditEnabled` | OBSERVABILITY | 审计日志 | 记录 agent 执行审计 | Switch | false | `it.observability.auditEnabled` | `copy(observability=observability.copy(auditEnabled=v))` | — | HOT |
| 50 | `observability.senderStatsEnabled` | OBSERVABILITY | 发送者统计 | 记录对话量与用户名 | Switch | false | `it.observability.senderStatsEnabled` | `copy(observability=observability.copy(senderStatsEnabled=v))` | — | HOT |
| 51 | `observability.auditRotationMb` | OBSERVABILITY | 审计轮转(MB) | 单文件大小上限 | Number(1, 64) | false | `it.observability.auditRotationMb` | `copy(observability=observability.copy(auditRotationMb=v))` | coerceIn(1, 64) | HOT |
| 52 | `observability.auditKeepFiles` | OBSERVABILITY | 保留文件数 | 轮转保留的历史文件 | Number(1, 10) | false | `it.observability.auditKeepFiles` | `copy(observability=observability.copy(auditKeepFiles=v))` | coerceIn(1, 10) | HOT |
| 53 | `observability.logConsole.capacity` | OBSERVABILITY | 日志台容量 | UI 显示的最大行数 | Number(100, 5000) | false | `it.observability.logConsole.capacity` | `copy(observability=observability.copy(logConsole=logConsole.copy(capacity=v)))` | coerceIn(100, 5000) | HOT |
| 54 | `observability.logConsole.tickerHz` | OBSERVABILITY | 刷新频率(Hz) | 日志台合批刷新 | Number(1, 30) | false | `it.observability.logConsole.tickerHz` | `copy(observability=observability.copy(logConsole=logConsole.copy(tickerHz=v)))` | coerceIn(1, 30) | HOT |
| 55 | `observability.logConsole.followBottom` | OBSERVABILITY | 跟随底部 | 新日志自动滚到底 | Switch | false | `it.observability.logConsole.followBottom` | `copy(observability=observability.copy(logConsole=logConsole.copy(followBottom=v)))` | — | HOT |
| 56 | `observability.logConsole.enabled` | OBSERVABILITY | 日志台开关 | 关掉后 UI 日志台不再收 lib 侧事件 | Switch | false | `it.observability.logConsole.enabled` | `copy(observability=observability.copy(logConsole=logConsole.copy(enabled=v)))` | — | HOT |

### 5.3 统计

| ConfigGroup | 字段数 | HOT | RESTART |
|---|---|---|---|
| CONNECTION | 6 | 3 | 3 |
| ACCESS | 5 | 5 | 0 |
| WORKSPACE | 5 | 0 | 5 |
| RUNTIME | 8 | 8 | 0 |
| AGENT | 17 | **15** | **2** |
| FILE_MESSAGE | 7 | 7 | 0 |
| OBSERVABILITY | 8 | 8 | 0 |
| **合计** | **56** | **46** | **10** |

### 5.4 validate 实现规则

- **纯函数**：`(T) -> String?`，返回 null = 通过，非 null = 错误消息
- **路径存在性检查延迟到 `connect()`/首次 agent 执行**，validate 里**不做磁盘 stat**
- **数值区间**：由 `Editor.Number(min, max)` 隐含，validate 无需重复；仅当业务语义超出区间时额外校验。🔴 **`min`/`max` 恒为存储口径（字节），不是 `displayScale` 换算后的显示口径**——`fileMessage.maxFileSizeBytes` 的 `Number(1048576, 1073741824)` 校验的是**字节值**，UI 显示的 1~1024（MB）由渲染层换算，**校验器绝不校验 MB 值**（§16②）
- **confirm.timeoutSeconds ≥ 10s**：钳制而非拒绝（旧 `MIN_CONFIRM_TIMEOUT_SECONDS=10L`，BC-476）
- **customTemplate 含 `{prompt}`**：仅当 `agent.backend == "custom"` 时校验（跨字段，在 `ConfigValidator` 层做）
- **appId / appSecret 只做非空白校验**（§16⑤ 已删除正则与长度下限）；必填条件为 `agent.enabled == true` 或 `connection.autoStart == true`
- **`workspace.root` 无格式校验**（§16⑤ 已删除「须绝对路径或 `~` 开头」）：空串合法（=未设置），非空时不做格式判断
- **`Editor.Number` 的 min/max 用 `coerceIn` 语义给提示而非硬拒**（§16⑤ 通则）

### 5.5 applyMode 分类依据

- **HOT**：变更后通过 `gateway.updatePolicy()` + `AtomicReference<PipelineSnapshot>` 无锁替换即可生效，下一个事件即用新配置，在途任务不受影响，不断连
- **RESTART**：变更需要重建连接/重新装配子进程工厂（涉及 SDK 长连接凭据、进程沙箱参数、agent 后端选择等不可热替换的基础设施）

分类清单（严格按方案）：
- HOT = `access.*` / `runtime.*` / `agent.progress|confirm|session` / `fileMessage.*` / `observability.*` / `routes` / `connection.autoStart|dryRun|testChatId`（§16④ 批准） / `agent.customTemplate|systemPrompt|permissionPreset|allowedTools|prohibitDelete|safetyPromptEnabled`（§16④ 推翻原判定）
- RESTART = `connection.appId|appSecret|region` / `workspace.*` / `agent.backend|enabled`（**仅这 2 个 agent 字段**：后端可用性探测 `which` 与 `selfCheck` 在 start 期完成，§16④）

---

## 6. `ConfigGroup` 枚举与 UI 分组映射

### 6.1 枚举定义

```kotlin
enum class ConfigGroup(
    val title: String,
    val order: Int,
    val initialExpanded: Boolean,   // §16⑥：高级旋钮组默认折叠
) {
    CONNECTION("连接", 0, initialExpanded = true),
    ACCESS("访问控制", 1, initialExpanded = true),
    WORKSPACE("工作区", 2, initialExpanded = true),
    RUNTIME("运行时", 3, initialExpanded = false),        // §16⑥ 4 个高级旋钮
    AGENT("Agent 与后端", 4, initialExpanded = true),
    FILE_MESSAGE("文件消息", 5, initialExpanded = false),  // §16⑥ 3 个高级旋钮
    OBSERVABILITY("可观测性", 6, initialExpanded = true),
}
    CONNECTION("连接", 0),
    ACCESS("访问控制", 1),
    WORKSPACE("工作区", 2),
    RUNTIME("运行时", 3),
    AGENT("Agent 与后端", 4),
    FILE_MESSAGE("文件消息", 5),
    OBSERVABILITY("可观测性", 6),
}
```

### 6.2 UI 7 张 SectionCard

按 `order` 升序排列，每张 `SectionCard(collapsible = true)`：

| 序号 | 标题 | 卡片内容 | `initialExpanded` | 是否 bespoke |
|---|---|---|---|---|
| 0 | 连接 | 6 个字段 + **扫码按钮**（bespoke） | `true` | **部分 bespoke**：扫码按钮不进字段表 |
| 1 | 访问控制 | 5 个字段 | `true` | 纯 schema 驱动 |
| 2 | 工作区 | 5 个字段 | `true` | 纯 schema 驱动 |
| 3 | 运行时 | 8 个字段（**含 4 个高级旋钮**，§16⑥） | **`false`** | 纯 schema 驱动 |
| 4 | Agent 与后端 | 17 个字段 | `true` | 纯 schema 驱动 |
| 5 | 文件消息 | 7 个字段（**含 3 个高级旋钮**，§16⑥） | **`false`** | 纯 schema 驱动 |
| 6 | 可观测性 | 7 个字段 | `true` | 纯 schema 驱动 |

### 6.3 bespoke 卡（不进 BridgeFields 字段表）

方案 R15 明确「不强行全 schema 化」，以下 4 类 UI 是 bespoke Composable：

1. **连接卡的扫码按钮**：`RegisterApp` OAuth Device Grant 流程（新增能力）
2. **路由卡**：routes 有序列表 + `RouteRuleEditor`（§4.5 已说明）
3. **日志台**：`LogConsole`（`SnapshotStateList` + 10Hz ticker，非配置字段）
4. **统计卡**：`UsageStatsCard` + `SenderStatsCard`（读 audit.jsonl/sender_stats.jsonl 聚合，非配置字段）

---

## 7. `DefaultBridgeConfig`

### 7.1 完整默认文档

所有字段默认值严格按 §3 的逐字段表，`schemaVersion = 1`。

### 7.2 默认 routes（5 条，精确内容）

```json
[
  {
    "kind": "system",
    "id": "builtin",
    "enabled": true
  },
  {
    "kind": "reply",
    "id": "help",
    "enabled": true,
    "trigger": "/help",
    "match": "exact",
    "reply": "**可用指令**\n\n发送 `/commands` 查看完整清单"
  },
  {
    "kind": "command",
    "id": "log-analysis",
    "enabled": true,
    "name": "日志分析",
    "aliases": ["/log", "/log-helper"],
    "description": "调用本地 /log-helper skill 分析崩溃日志、异常堆栈，定位问题根因",
    "promptTemplate": "/log-helper {args}",
    "requiresConfirm": false
  },
  {
    "kind": "agent",
    "id": "default",
    "enabled": true,
    "triggers": ["ai", "/ai"],
    "resetCommand": "新对话",
    "stopCommand": "停止"
  },
  {
    "kind": "file",
    "id": "file-analysis",
    "enabled": true,
    "triggerPrefix": ""
  }
]
```

### 7.3 内置 `log-analysis` 指令来源

**精确来自旧 `BuiltInQuickCommands.kt:29-37`**（已实测读取原文）：

```kotlin
val LOG_ANALYSIS = BridgeConfig.QuickCommandConfig(
    id = "log-analysis",
    name = "日志分析",
    aliases = listOf("/log", "/log-helper"),
    description = "调用本地 /log-helper skill 分析崩溃日志、异常堆栈，定位问题根因",
    promptTemplate = """/log-helper {args}""",
    enabled = true,
    requiresConfirm = false,
)
```

新架构中**内置指令改为默认文档里的 routes 数据**（而非硬编码），旧 `QuickCommandRegistry` 222 行 + `BuiltInQuickCommands` 44 行整体退役。用户可通过编辑 `routes` 数组中 `kind=command` 的条目来覆盖/禁用/新增指令。

---

## 8. `ConfigValidator`

### 8.1 设计

```kotlin
// lark-bridge/config/ConfigValidator.kt
object ConfigValidator {
    fun validate(config: BridgeConfig): List<ConfigIssue>
}

data class ConfigIssue(
    val fieldId: String,    // 对应 BridgeField.id，UI 用于字段级 isError 标记
    val message: String,    // 人可读的校验失败消息
)
```

### 8.2 规则清单

1. **逐字段 validate**：遍历 `BridgeFields.ALL`，对每个字段调用 `field.validate(field.get(config))`，非 null 即产出 `ConfigIssue(field.id, msg)`
2. **跨字段规则**（纯函数，O(字段数)）：
   - `agent.backend == "custom"` 且 `agent.customTemplate` 不含 `{prompt}` → issue
   - `agent.enabled == true` 或 `connection.autoStart == true` 时，`connection.appId` / `connection.appSecret` 任一为空白 → issue（缺凭证）。**三者均无格式/长度校验**（§16⑤）
   - `workspace.root` 非空且 `workspace.agentWorkDir` 非空时，后者须在前者内（路径前缀检查，**纯字符串比较不做磁盘 stat**）
3. **路径存在性检查延迟到 `connect()`/首次 agent 执行**——validate 里不做磁盘 stat

### 8.3 失败处理

- 返回 `JResult.Failed(LarkConfigInvalid, issues)`（feature 侧映射后的 `JErrorCode`）
- **不覆写文件**（校验失败时 `ConfigRepository.save()` 拒绝落盘）
- UI 顶部红条显示 issues 数量 + 首条消息
- 字段级 `isError`：`issues.map { it.fieldId }.toSet()` 传入 Composable

---

## 9. `ConfigRepository` 写入路径

### 9.1 完整写入序列

```
UI 编辑 → 内存 draft 即时更新
  → 400ms trailing debounce（防止高频键击产生大量写入）
  → Mutex 单写者（保证同一时刻只有一个写操作）
  → 首次写前：复制当前文件为 .bak（仅首次，后续覆写 .bak）
  → 写 bridge-config.json.tmp-<nanoTime>（同目录保证同文件系统）
  → FileChannel.force(true)（fsync 确保数据落盘）
  → Files.move(ATOMIC_MOVE, REPLACE_EXISTING)
    → 不支持 ATOMIC_MOVE 时降级：Files.move(REPLACE_EXISTING)
  → 失败：删除 tmp 文件，保留旧文件不动
  → 成功：POSIX 下设置权限 rw-------(600)
```

### 9.2 关键设计决策

- **400ms trailing debounce**：文档约 15-30KB，`encodeToString` < 1ms，防抖下 ≤ 3 次/秒
- **`Mutex` 单写者**：sink dispatcher `limitedParallelism(1)` 天然有序，Mutex 是第二道保险
- **`.tmp-<nanoTime>` 命名**：nanoTime 防并发写时文件名冲突
- **POSIX 600**：`appSecret` 明文落 JSON 的泄露面缓解（R17）
- **`flushNow()`**：在 `stop()`/`onAppExit()` 内以 `NonCancellable` 调用，确保退出前写入完成

### 9.3 对应测试

- **`ConfigAtomicWriteTest`**（NF-10）：原子写/备份/损坏文件回退 `Failed(LarkConfigParseFailed)`/写入中途抛异常旧文件完好/POSIX 600
- **`ConfigRepositoryTest`**：注入临时 File，验证 400ms debounce 合并、Mutex 串行化、flushNow 在 NonCancellable 内完成

---

## 10. `ConfigMigrations`

### 10.1 设计

```kotlin
// lark-bridge/config/ConfigMigrations.kt
typealias Migration = (Int, JsonObject) -> JsonObject  // (fromVersion, doc) -> migratedDoc

val MIGRATIONS: List<Migration> = listOf(
    // v1 起步，当前 0 步但框架在位
)
```

### 10.2 关键约束

- **在 JsonObject 层操作**（不是 typed 层）：被删/改名字段不会让 decode 失败
- **`experimental` / 未知顶层段原样暂存并在写回时合并**：读取时把 `schemaVersion` 以外的未识别顶层键存入 `experimental: JsonObject`，写回时合并到输出文档
- **退役配置项与「未知键保留」不冲突**（裁决 4）：
  - `experimental` / 未知顶层段保留是针对 **bridge-config.json 文档内**的前向兼容
  - `lark_bridge_transport` / `token_dir` / `preset_migration_notice` 是**旧 DataStore key**，压根不进新文档，属「废弃不迁移」
  - 两件事分节处理，不混淆

### 10.3 对应测试

- **`ConfigMigrationsTest`**：v0→v1 空链 + 伪造 v0 文档升迁 + 未知顶层段保留
- **`ConfigMigrationUnknownKeyPreservedTest`**（NF-11）：未知键原样保留断言

---

## 11. `LegacyDocumentMapper`

### 11.1 设计

```kotlin
// lark-bridge/config/LegacyDocumentMapper.kt
object LegacyDocumentMapper {
    /**
     * 纯函数，输入 Map<String,String>（旧 DataStore 的 key-value 快照），
     * 不依赖 DataStore、不依赖 :core（lib 侧可单测）。
     * 返回构造好的 v1 BridgeConfig。
     */
    fun map(legacyData: Map<String, String>): BridgeConfig
}
```

### 11.2 完整 35 key 映射表

| # | 旧 DataStore key | 旧类型/默认值 | 新 JSON 路径 | 转换细节 |
|---|---|---|---|---|
| 1 | `lark_bridge_app_id` | String "" | `connection.appId` | 直接映射 |
| 2 | `lark_bridge_app_secret` | String "" | `connection.appSecret` | 直接映射（明文搬迁） |
| 3 | `lark_bridge_chat_id` | String "" | `connection.testChatId` | 直接映射 |
| 4 | `lark_bridge_dry_run` | Boolean false | `connection.dryRun` | `"true"/"false"` → Boolean |
| 5 | `lark_bridge_access_mode` | String "whitelist" | `access.mode` | `whitelist`→`allowlist`；`blacklist`→`blocklist` |
| 6 | `lark_bridge_allowed_senders` | String "" | `access.allowedSenders` | 逗号分隔 → `List<String>`（trim + 过滤空项） |
| 7 | `lark_bridge_blocked_senders` | String "" | `access.blockedSenders` | 同上 |
| 8 | `lark_bridge_rejection_reply` | String "" | `access.rejectionReply` | 直接映射 |
| 9 | `lark_bridge_group_require_mention` | Boolean true | `access.groupRequireMention` | `"true"/"false"` → Boolean |
| 10 | `lark_bridge_workspace_dir` | String "" | `workspace.root` | 直接映射 |
| 11 | `lark_bridge_workspace_whitelist` | String JSON[] | `workspace.extraRoots` | JSON 数组字符串 → `List<String>`（runCatching 解析，失败回退空列表） |
| 12 | `lark_bridge_agent_work_dir` | String "" | `workspace.agentWorkDir` | 需 `~` 展开（`exec/Paths.kt` 的 `Paths.expandTilde`；文档旧称 `PathExpansion.expandTilde` 为命名漂移，见 §15 #11） |
| 13 | `lark_bridge_agent_enabled` | Boolean false | `agent.enabled` | `"true"/"false"` → Boolean |
| 14 | `lark_bridge_agent_backend` | String "claude" | `agent.backend` | 直接映射 |
| 15 | `lark_bridge_agent_custom_template` | String "" | `agent.customTemplate` | 直接映射 |
| 16 | `lark_bridge_agent_system_prompt` | String "" | `agent.systemPrompt` | 直接映射 |
| 17 | `lark_bridge_agent_permission_preset` | String "safe" | `agent.permissionPreset` | **直接落终值，不再产生迁移横幅**；旧默认 = `PermissionPreset.DEFAULT_PRESET` = `"safe"` |
| 18 | `lark_bridge_agent_allowed_tools` | String "" | `agent.allowedTools` | 直接映射 |
| 19 | `lark_bridge_agent_prohibit_delete` | Boolean true | `agent.prohibitDelete` | `"true"/"false"` → Boolean |
| 20 | `lark_bridge_agent_safety_prompt_enabled` | Boolean true | `agent.safetyPromptEnabled` | `"true"/"false"` → Boolean |
| 21 | `lark_bridge_agent_timeout_minutes` | Long 10 | `runtime.agentTimeoutMinutes` | `toLongOrNull() ?: 10L` |
| 22 | `lark_bridge_agent_confirm_enabled` | Boolean false | `agent.confirm.enabled` | `"true"/"false"` → Boolean |
| 23 | `lark_bridge_agent_confirm_timeout_seconds` | Long 60 | `agent.confirm.timeoutSeconds` | `toLongOrNull() ?: 60L` |
| 24 | `lark_bridge_agent_prefix` | String "ai" | `routes[kind=agent].triggers` | 逗号分隔 → `List<String>`（如 `"ai,/ai"` → `["ai","/ai"]`） |
| 25 | `lark_bridge_agent_reset_command` | String "新对话" | `routes[kind=agent].resetCommand` | 直接映射 |
| 26 | `lark_bridge_agent_stop_command` | String "停止" | `routes[kind=agent].stopCommand` | 直接映射 |
| 27 | `lark_bridge_static_replies` | String JSON | `routes[kind=reply]` | JSON → 多条 ReplyRoute（每条 `match="exact"`，id 自动生成 `"reply-<index>"`） |
| 28 | `lark_bridge_skills` | String JSON | `routes[kind=command]` | JSON → 多条 CommandRoute（**注意 key 历史名是 skills 实际存 QuickCommand**） |
| 29 | `lark_bridge_unhandled_reply` | String "" | `runtime.unhandledReply` | 直接映射 |
| 30 | `lark_bridge_file_message_enabled` | Boolean true | `fileMessage.enabled` | `"true"/"false"` → Boolean |
| 31 | `lark_bridge_file_max_size_mb` | Long 100 | `fileMessage.maxFileSizeBytes` | `toLongOrNull() ?: 100L` **× 1048576** |
| 32 | `lark_bridge_file_auto_extract` | Boolean true | `fileMessage.autoExtract` | `"true"/"false"` → Boolean |
| 33 | `lark_bridge_transport` | String "cli" | **废弃不迁移** | 新架构固定长连接（见 §11.4） |
| 34 | `lark_bridge_token_dir` | String "" | **废弃不迁移** | SDK 内部管理 token |
| 35 | `lark_bridge_preset_migration_notice` | Boolean | **废弃不迁移** | 新版无迁移横幅 |

**残留 key（代码中无常量，仅可能存在于老用户 DataStore）**：`lark_bridge_event_key` / `lark_bridge_lark_cli_path` / `lark_bridge_region` —— **一律忽略不迁移**。

### 11.3 执行机制

- **门控（F-8 收窄后三步骤，实测 `LegacyConfigImporter.importOnce`）**：① `bridge-config.json` **已存在 ⇒ 跳过**（返回 `ConfigExists`，**主闸门**，保护手工/新 UI 编辑过的文档不被旧值污染）；② 否则探测哨兵 key `AppDataStore.store.hasKey("lark_bridge_app_id")`——**无**旧数据 ⇒ 若 `lark_bridge_legacy_imported` 未置位则置位后返回 `NoLegacyData`（标记此后避免每次启动重复探测 DataStore）；③ **有**旧数据 ⇒ 🔴 **无论 `lark_bridge_legacy_imported` 是否置位都执行迁移**。标记**不再是迁移的无条件短路闸门**，只用于「确认无旧数据可迁后避免每次启动重复读 DataStore」；主闸门始终是 `bridge-config.json` 是否存在。
- **逐 key 独立 `runCatching`**：单 key 解析失败不阻断其余 34 个（失败 key 记入迁移报告）
- **失败不写任何文档、不置标记（可重试）**：导入失败 → **不创建任何文档**（含空白文档）+ 明确告警 + **不置标记**，以便下次自动重试。🔴 修正原文自相矛盾：原文「失败创建空白默认文档」与「不置标记下次重试」冲突——触发条件含「config 不存在」，一旦失败即创建空白文档，则重试永不触发（见 §15 #12）。
- **旧 key 迁移后不删除**：保持回滚可能，文档注明「不再消费」
- **无「重新导入」按钮**：旧实现无此能力，不属全功能平移范围。手工重新导入路径 = 删除 `bridge-config.json` 后重启 App ⇒ 主闸门（步骤 ①「config 不存在」）放行，若有旧数据即重新迁移（F-8 后即使 `lark_bridge_legacy_imported` 已置位也会迁移，不再被标记短路）；若想彻底从零开始（不要旧设置），需同时清掉旧 DataStore 里的 `lark_bridge_*` 键，否则重启会再次迁移回旧值。

### 11.4 `transport` 显式废弃记录（裁决 9）

迁移报告里**必须显式列出**：

```
[已废弃，未迁移] lark_bridge_transport = "cli"
  原因：新架构固定使用 SDK 长连接，不再有 cli/openapi 传输层选择。
  您的旧 transport 设置已被有意丢弃。
```

### 11.5 `agent_permission_preset` 迁移说明（裁决 10）

- 直接落终值 `"safe"`（旧默认 = `PermissionPreset.DEFAULT_PRESET`），**不再产生迁移横幅**
- **注意区分**：
  - `backend=custom`（customTemplate 恒空串时报错）—— 这是配置校验问题
  - `preset=custom`（allowedTools 空白时 fail-safe 降级为 safe）—— 这是运行时安全机制
  - 两件不同的事，不要混淆
- **qoder 后端没有 `all` 档**，其最高档是 `QODER_WORKSPACE`（照 `all→*` 实现会给 qoder 传非法工具串）

---

## 12. 错误码

### 12.1 lib 侧 `BridgeErrorCode`（完整清单）

定义位置：`lark-bridge/config/BridgeErrorCode.kt`（jvmTarget 11，不依赖 `:core`）

```kotlin
enum class BridgeErrorCode(val code: Int, val desc: String) {
    // 配置域 (2000-2009)
    ConfigNotFound(2000, "配置文件不存在"),
    ConfigParseFailed(2001, "配置文件解析失败"),
    ConfigInvalid(2002, "配置校验失败"),
    ConfigWriteFailed(2003, "配置写入失败"),

    // 连接域 (2010-2019)
    ConnectFailed(2010, "连接失败"),
    BlockedByPeerInstance(2011, "同 App ID 已有实例运行"),
    RateLimited(2012, "被飞书频控"),

    // Agent 域 (2020-2029)
    AgentUnavailable(2020, "Agent 后端不可用"),
    AgentTimeout(2021, "Agent 执行超时"),
    WorkspaceDenied(2022, "工作目录越界"),
    ConfirmUnavailable(2023, "确认链路不可用"),

    // 文件域 (2030-2039)
    DownloadFailed(2030, "文件下载失败"),
    ExtractUnsafeEntry(2031, "解包路径不安全"),

    // 出站域 (2040-2049)
    SendFailed(2040, "消息发送失败"),
}
```

共 **14 个**，码值范围 2000-2040。

### 12.2 feature 侧 `BridgeErrorCode → JErrorCode` 映射表

定义位置：`feature-larkbridge/.../core/larkbridge/BridgeErrorMapping.kt`

| BridgeErrorCode | JErrorCode | 码值 |
|---|---|---|
| ConfigNotFound | LarkConfigNotFound | 2000 |
| ConfigParseFailed | LarkConfigParseFailed | 2001 |
| ConfigInvalid | LarkConfigInvalid | 2002 |
| ConfigWriteFailed | LarkConfigWriteFailed | 2003 |
| ConnectFailed | LarkConnectFailed | 2010 |
| BlockedByPeerInstance | LarkBlockedByPeerInstance | 2011 |
| RateLimited | LarkRateLimited | 2012 |
| AgentUnavailable | LarkAgentUnavailable | 2020 |
| AgentTimeout | LarkAgentTimeout | 2021 |
| WorkspaceDenied | LarkWorkspaceDenied | 2022 |
| ConfirmUnavailable | LarkConfirmUnavailable | 2023 |
| DownloadFailed | LarkDownloadFailed | 2030 |
| ExtractUnsafeEntry | LarkExtractUnsafeEntry | 2031 |
| SendFailed | LarkSendFailed | 2040 |

### 12.3 `JErrorCode` 桥接段（裁决 7）

现有 `JErrorCode.kt` 只有 14 行，最大码值 1002，注释 `// 1000 - 2000 shell tools 错误码`。

**M4-T5 对 `:core` 的改动**：
1. 把 shell 段注释改为 `// 1000 - 1999 shell tools 错误码`（消除 2000 边界歧义）
2. 新增桥接段 `// 2000 - 2099 lark bridge 错误码`，14 个枚举值（码值与上表一致）

---

## 13. 测试清单

### 13.1 `BridgeConfigRoundTripTest`（M4-T5）

| 断言 | 对应 BC |
|---|---|
| encode→decode 等价（全字段默认值文档往返不变） | BC-319..324（model 默认值双态） |
| 缺字段取默认（手工删除 JSON 键后 decode 取 data class 默认值） | BC-319..324 |
| 未知键忽略（添加 `"unknownKey": 123` 后 decode 不报错） | NF-11 |
| `experimental` 保留（写入非空 experimental 后往返不丢） | NF-11 |

### 13.2 `BridgeFieldsTest`（NF-17，M6-T12）

| 断言 | 对应 BC |
|---|---|
| **逐 field 断言 `set(get(defaultConfig))` 恒等**（读改写往返不丢值） | NF-17 |
| **`id` 全局唯一**（56 个 id 无重复） | NF-17 |
| **`group` 非空**（每个字段有且仅有一个 ConfigGroup） | NF-17 |
| **`applyMode` 非空**（每个字段明确 HOT 或 RESTART） | NF-17 / D8 |
| `secret=true` 仅 `connection.appSecret` 一个 | NF-17 / R17 |
| bespoke 卡（连接扫码/路由/日志/统计）不出现在 BridgeFields 中 | NF-17 / R15 |

### 13.3 `ConfigValidatorTest`（M4-T5）

| 断言 | 对应 BC |
|---|---|
| 全默认文档校验通过（issues 为空） | BC-006 |
| customTemplate 缺 `{prompt}` → issue（BC-006 精确对应） | BC-006 |
| confirm.timeoutSeconds < 10 → 钳制到 10（不产生 issue，运行时钳制） | BC-476 |
| appId / appSecret 空白且 `agent.enabled=true` → issue；**格式错误不产生 issue**（§16⑤ 已删除正则与长度校验） | §16⑤ |
| `workspace.root` 为空串 → 校验通过（不报缺失） | §16⑤ |
| 路径存在性不在 validate 中检查（无磁盘 stat） | 方案明确 |

### 13.4 `ConfigRepositoryTest`（M4-T5）

| 断言 | 对应 BC |
|---|---|
| 注入临时 File：首次写入创建文件 | NF-10 |
| 400ms debounce 合并：快速连续 set 只产生一次写入 | NF-10 |
| 损坏文件回退 `Failed(LarkConfigParseFailed)`（不抛、不静默用默认值覆盖） | NF-10 / BC-534/537/545 |
| 写入中途抛异常 → 旧文件完好无损 | NF-10 |
| flushNow 在 NonCancellable 内完成 | NF-14 第⑧步 |

### 13.5 `ConfigAtomicWriteTest`（NF-10，M4-T5）

| 断言 | 对应 BC |
|---|---|
| 原子写：临时文件 + rename | NF-10 |
| 写前备份旧文件为 .bak | NF-10 |
| POSIX 权限 600 | NF-10 / R17 |
| secret 字段在日志/审计/check 输出中统一掩码 | NF-10 / R17 |

### 13.6 `ConfigMigrationsTest`（M4-T5）

| 断言 | 对应 BC |
|---|---|
| v0→v1 空链（当前无迁移步骤，框架在位） | NF-11 |
| 伪造 v0 文档升迁到 v1 正确 | NF-11 |
| 未知顶层段原样保留 | NF-11 |

### 13.7 `ConfigMigrationUnknownKeyPreservedTest`（NF-11，M7-T15）

| 断言 | 对应 BC |
|---|---|
| 未知键写入后读出仍在 | NF-11 |
| `experimental` 段内容不丢 | NF-11 |

### 13.8 `LegacyDocumentMapperTest`（M4-T5，lib 侧）

| 断言 | 对应 BC |
|---|---|
| **35 key 全覆盖**：逐 key 构造输入 Map，验证映射到正确 JSON 路径 | BC-528..550 / §6.4 |
| 单 key 失败不阻断：某 key 值为非法格式时其余 34 个正常映射 | NF-11 / R6 |
| 废弃项显式记录：`transport`/`token_dir`/`preset_migration_notice` 出现在报告中 | P9 |
| `whitelist`→`allowlist` 转换正确 | BC-528 |
| `file_max_size_mb` ×1048576 转换正确 | BC-528 |
| `workspace_whitelist` JSON 数组字符串 → List 正确 | BC-531..534 |
| `static_replies` JSON → 多条 routes[kind=reply] 正确 | BC-542..545 |
| `skills` JSON → routes[kind=command] 正确 | BC-535..537 |
| `agent_prefix` → routes[kind=agent].triggers 正确 | BC-528 |
| `agent_work_dir` 需 ~ 展开 | BC-393..399 |

### 13.9 `LegacyConfigImporterTest`（M6-T12，feature 侧）

| 断言 | 对应 BC |
|---|---|
| 用独立临时目录 `LocalDataManager` 实例替换 `AppDataStore.storeForTest` | — |
| 35 key 映射：全部旧 key 写入临时 DataStore 后导入，验证新文档字段值正确 | BC-528..550 |
| 二次启动不重复导入：标记已置后再次调用 → 短路返回 | BC-549 / R18 |
| 单 key 解析失败不阻断其余 | BC-534/537/545 / R6 |
| 迁移标记正确：成功后 `lark_bridge_legacy_imported = true` | BC-546..548 |
| 失败不置标记可重试 | R6 |

### 13.10 `BridgeConfigDefaultsTest`（M4-T5，§16.1 新增）

> behavior-contract §2.3 明确：旧 `model/BridgeConfigTest.kt` 6 个方法的默认值契约移到本测试类（旧 data class 随 M2 消失）。

| 断言 | 对应 BC / 裁定 |
|---|---|
| 🔴 `runtime.taskTimeoutSeconds == 60L` | **§16.1 裁定①**（方案 jsonc + 旧 `BridgeConfig.kt:32` 双证） |
| 🔴 `runtime.agentTimeoutMinutes == 10L` | **§16.1 裁定①**——把 NF-08 想守护的「agent 总超时 10 分钟」显式落到正确字段（**NF-08 的 600s 是口径混淆，本轮不改 behavior-contract.md**） |
| `agent.enabled == false` | BC-528 |
| `agent.backend == "claude"` | BC-528 |
| `agent.permissionPreset == "safe"`（== `PermissionPreset.DEFAULT_PRESET`） | BC-546 / 裁决 10 |
| `agent.prohibitDelete == true` / `agent.safetyPromptEnabled == true` | BC-538 / BC-540（安全底线） |
| `workspace.envAllowlist` == 旧 `BASE_ENV_KEYS` 11 键（逐项比对，含 3 个小写代理变体） | 裁决 1 / §3.2.3 |
| `fileMessage.maxFileSizeBytes == 104_857_600L`（100MB） | §3.2.6 |
| `agent.progress.patchIntervalMillis == 2000L` / `cardMaxChars == 5000` | 裁决 2 / 裁决 3 |
| `routes.size == 5` 且 kind 顺序为 system/reply/command/agent/file | §7 |
| `routes` 中 `log-analysis` 的 `promptTemplate == "/log-helper {args}"`（🔒 逐字，防行为回归） | §7 / 旧 `BuiltInQuickCommands.kt:29-37` |
| `schemaVersion == 1` | §3.1 |

---

## 14. grep 断言清单

### 14.1 R19 方向断言（NF-16）

`:lark-bridge` 与 `:lark-gateway` 源码（`src/main/kotlin/`）**不得出现** `com.jiaoay.easy_dev_toolbox` 包引用。

### 14.2 ProcessBuilder 限制

lib 侧 `ProcessBuilder` **只允许出现在** `exec/ProgramLauncher.kt`。grep 断言扫描 `lark-bridge/src/main/kotlin/` 全目录，匹配 `ProcessBuilder` 的文件集合 == `{exec/ProgramLauncher.kt}`。

### 14.3 intake 路径零锁

intake 路径（`pipeline/EventIntake.kt` + `pipeline/InboundQueue.kt` + `pipeline/MessageDeduper.kt` + `pipeline/StaleFilter.kt` + `pipeline/AccessControl.kt` + `pipeline/PolicyGate.kt` + `pipeline/PipelineRouter.kt`）**零 `synchronized` / 零 `Mutex`**。

### 14.4 D16 守护（P12，Leader 已批准）

lib 侧（`:lark-bridge` + `:lark-gateway`）**不得新增** object/companion object 体内或顶层的 `@Volatile var` 全局可变状态。**类级实例字段 `@Volatile var` 不在禁令内**（如 `LarkCardStreamSink` 的实例级 volatile 状态是合法写法）。

🔴 **grep 纪律为作用域感知**（原文「`@Volatile` 后跟 `var` 命中数 == 0」是过宽表述，实测 `lark-gateway/src/main` 有 7 处**类级实例字段**命中，会被误伤）：判据改为「**object/companion object 体内或顶层**的 `@Volatile var` 命中数 == 0；类级实例字段放行」。该收窄后的判据由 `LarkSdkIsolationTest` F 组 `findStaticVolatileVarOffenders`（花括号配对计数 `scanBraces` + object 声明区间 `findObjectRegions`）机器守护，不再靠人工全文 grep（见 §15 #13）。

---

## 15. 与方案不符之处的修正记录

| # | 方案原文 | 实测事实 | 本文档采用的结论 |
|---|---|---|---|
| 1 | `workspace.envAllowlist` 默认 8 键 `["PATH","HOME","LANG","LC_ALL","TZ","HTTP_PROXY","HTTPS_PROXY","NO_PROXY"]` | 旧 `SandboxedProcessFactory.kt:57-61` 的 `BASE_ENV_KEYS` 是 **11 键**（方案漏了 `http_proxy`/`https_proxy`/`no_proxy` 三个小写变体）；`:68-75` 还有 `WINDOWS_REQUIRED_ENV_KEYS`（11 键）+ `effectiveBaseKeys(isWindows)` 的平台化追加逻辑（方案完全没提） | **默认值改为 11 键**（旧实测集合）；Windows 必需键作为**平台化硬编码追加**（不进用户配置，不可被移除）；`EnvAllowlist` 语义为「用户配置 ∪ 平台必需」 |
| 2 | 方案 M6-T12「feature 侧用 `:core` 的 `FileUtils` 注入数据根」 | `FileUtils.getAppDataDir()` 是 `private`，只有 `getLogDir/getDatastoreDir/getShellToolsFile/getShellToolsCustomFile` 四个公开方法，**无 `getLarkDir()`** | M6-T12 给 `FileUtils` **新增公开访问器 `getLarkDir(): File`**（返回 `File(getAppDataDir(), "lark")`），这是方案未点名的 `:core` 改动 |
| 3 | 方案桥接段起点 2000 | `JErrorCode.kt:10` 注释写「1000 - 2000 shell tools 错误码」，与 2000 **名义重叠** | 保留方案桥接段码值不变（2000 起），**把 shell 段注释改为「1000-1999」**以消除歧义 |
| 4 | 方案「`BuiltinExecutors.build()` 108 行手工装配链」 | `BuiltinExecutors.kt` **全文 108 行**，其中 `build()` 为 :39-99 约 61 行（behavior-contract §6.2） | 方案表述应理解为**文件全长 108 行**，`build()` 本身约 61 行。不影响修复方向 |
| 5 | 方案 D5「runBlocking 在 `openapi/UserNameResolver.kt`」 | `UserNameResolver.kt:33` 已是 `suspend fun resolve`，全文无 `runBlocking`。**实际位置是 `stats/SenderStatsStore.kt:88-90`** | 删 `runBlocking` 的目标文件更正为 `stats/SenderStatsStore.kt` |
| 6 | 方案 D8「40+ 独立 StateFlow」 | 实测 46 处 `MutableStateFlow` 声明，但**分布与直觉相反：几乎全在 feature 侧 VM（46 处中的绝大多数），lib 侧仅 8 个** | **改造重心在 M6-T13（VM 重写），不在 M4 lib 侧**；lib 侧 8 个 StateFlow 收敛进网关/pipeline 状态即可 |
| 7 | 方案 `progressPatchIntervalMillis` 未在新配置文档中给字段 | 旧实现是用户旋钮（`BridgeConfig.kt:158`），按「全功能平移」须保持可编辑 | **进 `BridgeFields.ALL`**（#36），加下限钳制 `coerceIn(250, 10000)` 防用户调小触发飞书频控 |
| 8 | 方案未明确 `cardMaxChars=5000` 与网关 `cardByteBudget=24576` 的关系 | 两者职责不同：前者是进度卡内容上限（业务偏好），后者是网关侧卡片体硬预算（协议约束） | **并存不矛盾**；`SegmentRotationTest` 必须在 `CardStreamSink` 层直接喂 40KB 来测，不经进度卡链路 |
| 9 | 方案未区分退役 DataStore key 与文档内未知键保留 | P5 冲突：退役 key 是旧 DataStore 概念，未知键保留是新 JSON 文档的前向兼容 | **分节处理**：旧 DataStore key 废弃不迁移（§11.2 #33-35）；新文档内未知键原样保留（§10.2） |
| 10 | 方案未说明删除 `--sender-stats-dir` 后 CLI 如何注入 stats 目录 | P8：30 个业务字段旗标删除后，所有路径需另有注入方式 | **全部派生自单一数据根 `<dataRoot>/lark/`**（§1.2 ConfigPaths），CLI 用 `--data-dir` 注入根路径 |
| 11 | §11.2 #12 旧写 `PathExpansion.expandTilde` | 代码实为 `exec/Paths.kt` 的 `object Paths { fun expandTilde(...) }`，无 `PathExpansion` 符号 | **命名漂移修正**：文档统一改指 `Paths.expandTilde`（批准人=Leader，2026-09-08，T16） |
| 12 | §11.3 旧写「导入失败 → 创建空白默认文档 + 不置标记（下次重试）」 | 自相矛盾：触发条件含「config 不存在」，一旦失败即创建空白文档，重试永不触发 | **改为「失败不写任何文档、不置标记，只有成功才落盘 + 置 `lark_bridge_legacy_imported`」**（批准人=Leader，2026-09-08，T16；同批删除「UI 提供重新导入按钮」，改为手工删文档重启） |
| 13 | §14.4 旧写「`@Volatile` 后跟 `var` 命中数 == 0」（全文无差别） | 实测 `lark-gateway/src/main` 有 7 处**类级实例字段** `@Volatile var` 合法命中，全文 grep 会误伤 | **收窄为作用域感知**：只禁 object/companion 体内或顶层的 `@Volatile var`，类级实例字段放行；由 `LarkSdkIsolationTest` F 组 `findStaticVolatileVarOffenders` 机器守护（批准人=Leader，2026-09-08，T16） |
| 14 | 方案 M6-T13 范式「单一不可变状态 + lens 切片」（`StateFlow<BridgeUiState>`） | 实测 `page/larkbridge/LarkBridgeViewModel.kt`（**326 行**）用 **7 个独立 `MutableStateFlow`**：`_draft`(`:57`，`StateFlow<BridgeConfig>`) / `_issues`(`:62`) / `_applyResult`(`:67`) / `_startResult`(`:72`) / `_busy`(`:77`) / `_banner`(`:82`) / `_probe`(`:87`) | **设计裁定接受该偏离（偏离清单 #12），不重构为单一大 state**。理由三条：① 方案该范式的**真实目标已达成**——它要消的是旧 779 行 VM 里约 60% 的样板（一个字段跨 5 处、一类能力跨 8 处），而新 VM 是 326 行且渲染完全由 `BridgeFields.ALL`（56 字段）数据驱动，样板已消除，**目标达成靠的是声明式字段表，不是状态形状**；② `_draft` 本身就是**单一不可变的 `BridgeConfig` StateFlow**，配置状态无不一致窗口，其余 6 个是彼此独立的一次性信号，合进一个大 state 只会引入无意义的重组面；③ 此阶段重构是纯 churn + 回归风险，零用户可见收益。（批准人=Leader，2026-09-08） |
| 15 | 方案 `routes` 冲突判定「仅按 id 去重」 | 实测 `config/LegacyDocumentMapper.kt:201-204`（`mergeRoutes` / `findConflictIndex` / `isMatchingKeyConflict`）+ `config/ConfigValidator.kt:69-102`（`findDuplicateMatchingKeys`）已把冲突判定**从「仅 id」扩展为「id 或匹配键」** | **设计裁定接受该扩展（偏离清单 #14）**。匹配键定义：`ReplyRoute`→`(trigger, match)`、`CommandRoute`→`aliases` 任一重叠、`AgentRoute`→`triggers` 任一重叠、`SystemRoute` / `FileRoute`→各自单例；冲突即**就地整条替换并保留原数组下标**（保留下标即保留优先级），无冲突才追加末尾。理由：旧实现支持「用户自定义条目覆盖内置同触发词条目」，而 `parseStaticReplies` 生成的 id 恒为 `reply-<i>`、永不与内置 `help` 撞 id，只按 id 去重会让**用户的自定义回复被内置条目永久遮蔽、静默失效**。**真实数据实证**：真实 `bridge-config.json` 修复前 `routes` 长 6 条且 `/help` 重复、修复后长 5 条且下标 1 就是用户的 `reply="测试"`。（批准人=Leader，2026-09-08） |

---

## 附录 A：`BridgeConfig` Kotlin data class 骨架（T5 施工参考）

```kotlin
@Serializable
data class BridgeConfig(
    val schemaVersion: Int = 1,
    val connection: ConnectionConfig = ConnectionConfig(),
    val access: AccessConfig = AccessConfig(),
    val workspace: WorkspaceConfig = WorkspaceConfig(),
    val runtime: RuntimeConfig = RuntimeConfig(),
    val agent: AgentConfig = AgentConfig(),
    val fileMessage: FileMessageConfig = FileMessageConfig(),
    val observability: ObservabilityConfig = ObservabilityConfig(),
    val routes: List<RouteRule> = DefaultBridgeConfig.DEFAULT_ROUTES,
    val experimental: JsonObject = JsonObject(emptyMap()),
)
```

每个子域 data class 的字段与默认值严格按 §3 的逐字段表。所有字段均带默认值（存量配置 JSON 无新键时反序列化不受影响）。

---

## 附录 B：`ConfigGroup` → UI SectionCard 渲染流程

```
BridgeFields.ALL
  .groupBy { it.group }
  .entries.sortedBy { it.key.order }
  .forEach { (group, fields) ->
      SectionCard(title = group.title, collapsible = true) {
          fields.forEach { field ->
              FieldEditor(field, value = field.get(draft), onEdit = { vm.set(field, it) })
          }
      }
  }
```

`FieldEditor` 按 `field.editor` 的 sealed 类型 dispatch 到 8 种通用编辑器之一。

---

## 附录 C：CLI `check` 命令输出格式

```
$ java -jar lark-bridge-all.jar check

Bridge Config: ~/.easy_dev_toolbox/lark/bridge-config.json
Schema Version: 1
Validation: PASS (0 issues)

┌─────────────────────────────────────────┬───────────────┬─────────────────────┐
│ Field ID                                │ Apply Mode    │ Current Value       │
├─────────────────────────────────────────┼───────────────┼─────────────────────┤
│ connection.appId                        │ RESTART       │ cli_a0xxxxxxxxxxxx  │
│ connection.appSecret                    │ RESTART       │ ************        │
│ connection.region                       │ RESTART       │ feishu              │
│ ...                                     │ ...           │ ...                 │
└─────────────────────────────────────────┴───────────────┴─────────────────────┘

secret=true 字段统一掩码显示（前4后4，中间 *）。
```

---


---

## 16. 决策补录（设计裁定）

> 本节固化 Leader 对上轮回报的 6 个待决点 + 1 个附带陷阱的全部裁定。**本节与前 15 节具有同等效力；凡前文表格与本节冲突，以本节为准（冲突处已就地改正，见各条「影响的章节」）。**
>
> 🔴 **`behavior-contract.md` 本轮未被修改**（另一 agent 正在并发只读该文件）。§16.1 对 NF-08 的更正只写在本节，由 T5 施工者据此执行。

### 16.1 裁定①：`runtime.taskTimeoutSeconds` 默认值 = 60（秒）

**问题**：方案 jsonc 与旧 `BridgeConfig.kt:32` 均为 `60`，但 `behavior-contract.md` 的 **NF-08** 表述为「旧默认 600s」，两者冲突。

**裁定结论**：默认值 = **60**（`Long`）。NF-08 的「600s」判定为**口径混淆**——`600s == 10min`，对应的是 `runtime.agentTimeoutMinutes = 10` 这条 **agent 专用总超时**，不是通用 `taskTimeoutSeconds`。

**理由**：方案 jsonc（唯一真源）写 60 + 旧 lib 源码 `BridgeConfig.kt:32 taskTimeoutSeconds: Long = 60` **双证一致**；NF-08 是单方表述且其数值恰好等于另一字段的语义值，属典型的「守护意图正确、绑定字段错位」。

**影响的章节**：
- §3.2.4 `runtime` 表：`taskTimeoutSeconds` 默认 `60`、`agentTimeoutMinutes` 默认 `10` —— **原表已正确，无需修改**。
- §13 **新增 13.10 `BridgeConfigDefaultsTest`** —— 已就地补入。
- 🔴 **`behavior-contract.md` NF-08 需按本条更正，本轮不改该文件**（并发只读保护）。

**施工者必须遵守的硬性要求（T5）**：
1. `BridgeConfigDefaultsTest` 断言 `runtime.taskTimeoutSeconds == 60L`。
2. **必须同时新增断言 `runtime.agentTimeoutMinutes == 10L`** —— 把 NF-08 想守护的「agent 总超时 10 分钟」这条语义**显式落到正确的字段上**，避免 NF-08 的守护意图随更正而丢失。
3. 两条断言缺一不可：只写第 1 条会让 NF-08 变成无承接条目的验收缺口（违反 behavior-contract §1「标为移植的条目若在新架构中找不到承接它的测试，即为验收缺口」）。

### 16.2 裁定②：`fileMessage.maxFileSizeBytes` —— 存储字节 / UI 显示 MB，靠 `Editor.Number` 加参数解决

**问题**：新 schema 用字节（默认 `104857600`），旧 UI 是 MB 口径（`commitFileMaxSizeMb` + `coerceIn(1L, 1024L)`，用户填「100」即 100MB）。直接照 schema 渲染会要求用户填「104857600」，属 UX 退步。

**裁定结论**：**不新增第 9 种 editor**，改为给 `Editor.Number` 增加两个带默认值的参数：

```kotlin
data class Number(
    val min: Long,
    val max: Long,
    val displayScale: Long = 1,     // UI 显示换算除数
    val unitSuffix: String = "",    // UI 显示单位后缀
) : Editor
```

`fileMessage.maxFileSizeBytes` 的字段声明：

```kotlin
editor = Editor.Number(
    min = 1_048_576L,              // 1 MB
    max = 1_073_741_824L,          // 1024 MB
    displayScale = 1_048_576L,
    unitSuffix = " MB",
)
```

**换算规则（三层职责严格分离）**：

| 层 | 口径 | 说明 |
|---|---|---|
| **`BridgeField.get/set`** | **字节**（`T = Long`） | 只在字节层面工作，**不做任何换算** |
| **config 文档 / JSON** | **字节** | `104857600` 原样落盘，换算不进文档 |
| **UI 渲染层**（T13 `FieldEditors.kt`） | **MB** | 显示 `value / displayScale` + `unitSuffix`；提交 `input * displayScale` 回写 |
| **CLI `check` 输出** | **字节原值 + 括号附 MB** | 如 `104857600 (100 MB)`，便于人读且不丢原值 |

**理由**：① 守住方案「editor 只能是 8 种」的规定——**这是给 `Number` 加参数，不是加类型**；② 不牺牲旧版「用户填 100 就是 100MB」的体验（用户已拍板决策 3「全功能平移，现有能力一个不少」）；③ `min/max` 仍按字节定义（`1048576 ~ 1073741824`，即 1MB~1024MB），与旧 UI `coerceIn(1L, 1024L)` 的 MB 口径**数值等价**；④ 参数统一用 **`Long`**（不用 `Int`），因为字节值 `1073741824` 已逼近 `Int.MAX_VALUE`（`2147483647`），若后续放宽上限到 2GB 以上会溢出。

**影响的章节（已就地修正）**：
- §5.1 `Editor.Number` 签名 —— **已改为 4 参数版**。
- §5.2 表 **#43 行** —— editor 列已改为 `Number(1048576, 1073741824, displayScale=1048576, unitSuffix=" MB")`，label 改为「文件大小上限(MB)」，hint 改为「UI 显示 MB，存储字节（默认 100MB）」。
- §3.2.6 `fileMessage` 表 —— 语义列已补「**存储字节，UI 以 MB 显示**（见 §16②）」。

**施工者必须遵守的硬性要求（T13）**：`FieldEditors.kt` 的 `Number` 分支必须实现 `displayScale`/`unitSuffix`；换算**只发生在渲染层**，禁止把 MB 值写回 `BridgeConfig`。`displayScale == 1` 时（其余 18 个 Number 字段）行为与旧版完全一致，无回归面。

### 16.3 裁定③：`workspace.extraRoots` —— 给 `Editor.Tags` 加 `pickDirectory`，还原目录选择器 UX

**问题**：旧 UI 是带目录选择器的增删列表（`LarkBridgeViewModel.kt:485-498` 的 `addWorkspaceWhitelistDir` / `removeWorkspaceWhitelistDir`），纯 `Tags` 编辑器只能手输路径串，丢失 picker UX。方案 8 种 editor 里没有「目录列表 + picker」这一型。

**裁定结论**：**不新增第 9 种 editor**，改为给 `Editor.Tags` 增加参数：

```kotlin
data class Tags(
    val separator: String = ",",
    val pickDirectory: Boolean = false,
) : Editor
```

| `pickDirectory` | 渲染形态 |
|---|---|
| `true` | 标签列表 + **「添加目录」按钮** + 每个 tag 带删除按钮。按钮调用 `:ui` 既有的 `pickDirectory(title)`，返回路径**追加**为一个 tag |
| `false`（默认） | 纯手输标签串（分隔符 `separator`） |

**适用范围（逐字段指定，不得扩大）**：

| 字段 | `pickDirectory` | 依据 |
|---|---|---|
| `workspace.extraRoots` | **`true`** | 还原旧版 `addWorkspaceWhitelistDir`/`removeWorkspaceWhitelistDir` 的 picker UX |
| `access.allowedSenders` | `false` | open_id 串，无目录语义 |
| `access.blockedSenders` | `false` | 同上 |
| `workspace.envAllowlist` | `false` | 环境变量名，无目录语义 |
| `fileMessage.allowedExtensions` | **不适用** | ⚠️ 澄清：该字段在本文档 §3.2.6 的类型是 **`String`**（逗号分隔串，默认 `.log,.txt,.xlog,.alog,.zip,.tar,.tar.gz,.tgz,.gz`），editor 是 **`Text`** 而非 `Tags`，与旧 `BridgeConfig.kt:91` 一致。裁定③列举它只是表明「不启用 picker」，**其 editor 类型保持 `Text` 不变** |

**理由**：同 §16.2——守住方案「editor 只能是 8 种」的规定（给 `Tags` 加参数，不是加类型），同时不让「全功能平移」在 UX 上退步。

**`:ui` 侧既有 API 实测签名（T13 直接调用，不需新建）**：

```kotlin
// ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/DirectoryPicker.kt:26
suspend fun pickDirectory(title: String = "选择目录"): File?
```

⚠️ **该函数非 `@Composable`**（是 `suspend` 顶层函数），因此必须在 `viewModelScope` / `rememberCoroutineScope` 内调用，不能在 Composable 组合期直接调。返回 `null` 表示用户取消，此时**不追加 tag、不报错**。

**影响的章节（已就地修正）**：
- §5.1 `Editor.Tags` 签名 —— **已由 `data object` 改为 2 参数 `data class`**。
- §5.2 表 **#13 行**（`workspace.extraRoots`）—— editor 列已改为 `Tags(pickDirectory=true)`，hint 改为「多目录白名单（带目录选择器）」。

**施工者必须遵守的硬性要求（T13）**：`FieldEditors.kt` 实现 8 个编辑器时，**`Tags` 分支必须含 `pickDirectory` 子分支**（`if (editor.pickDirectory) 渲染「添加目录」按钮 else 纯文本输入`）。追加 tag 时须**去重**（同一路径不重复入列）并**保序**（BC-532「whitelist write then read back preserves order」）。

### 16.4 裁定④：applyMode 补齐 —— 批准 3 个 `connection` 字段，推翻 6 个 `agent` 字段

**问题**：方案的 HOT 清单没有 `connection.*`，RESTART 清单只点名 `connection.appId|appSecret|region`，也未覆盖 6 个 agent 标量字段。上轮我自行判定：3 个 connection 字段为 HOT、6 个 agent 字段为 RESTART。

**裁定结论**：

| 字段 | 上轮判定 | **最终裁定** | 结果 |
|---|---|---|---|
| `connection.autoStart` | HOT | **HOT** | ✅ 批准 |
| `connection.dryRun` | HOT | **HOT** | ✅ 批准 |
| `connection.testChatId` | HOT | **HOT** | ✅ 批准 |
| `agent.customTemplate` | RESTART | **HOT** | 🔴 推翻 |
| `agent.systemPrompt` | RESTART | **HOT** | 🔴 推翻 |
| `agent.permissionPreset` | RESTART | **HOT** | 🔴 推翻 |
| `agent.allowedTools` | RESTART | **HOT** | 🔴 推翻 |
| `agent.prohibitDelete` | RESTART | **HOT** | 🔴 推翻 |
| `agent.safetyPromptEnabled` | RESTART | **HOT** | 🔴 推翻 |
| `agent.backend` | RESTART | **RESTART** | 维持 |
| `agent.enabled` | RESTART | **RESTART** | 维持 |

**推翻 6 个 agent 字段的理由**：这 6 个都是**纯 argv/prompt 输入**，`RequestContext` 本身就按请求携带 `config`，`AgentHandler` 每次调用时从**当前配置快照**解析后端与档位即可做到「下一个事件即生效、在途任务不受影响、不断连」——这正是方案 HOT 机制的定义。把它们判成 RESTART 会导致「改一句系统提示词就断长连接」，**恰是方案 D8 要消灭的旧缺陷**（旧版「改白名单就 recreate 整桥断连」，见 behavior-contract BC-509..512 与 D8）。

**维持 `agent.backend` / `agent.enabled` 为 RESTART 的理由**：后端可用性探测（`which` / `Availability`）与 `agent.confirm.selfCheck` 在 **start 期**完成，改后端需重跑探测；`enabled` 从 false→true 需装配 agent 子系统。与方案原文一致。

**影响的章节（已就地修正）**：
- §5.2 表 **#27 ~ #32 共 6 行** —— applyMode 列已由 `RESTART` 改为 `**HOT**`。
- §5.3 统计表 —— 已加 HOT/RESTART 两列，AGENT 组由「9 HOT / 8 RESTART」改为「**15 HOT / 2 RESTART**」，全表由「39/16」改为「**45/10**」。
- §5.5 分类清单 —— HOT 行已补入 3 个 connection 字段与 6 个 agent 字段；RESTART 行的 agent 部分已收窄为 `agent.backend|enabled`。

**施工者必须遵守的硬性要求（T8，🔴 强制）**：
1. `AgentRunner` / `AgentBackend` 的解析**必须在每次任务调用时从 `RequestContext.config` 取值**。
2. **禁止在装配期缓存 backend 实例或 argv 片段**（包括禁止把 `permissionPreset` 解析出的工具串、`systemPrompt` 拼接结果、`customTemplate` 渲染结果存成字段或闭包捕获值）。一旦缓存，这 6 个字段的 HOT 语义即失效，退化为「改了不生效」的静默 bug。
3. `AssemblerParityTest` **需补一条断言**：「同一 config 装配结果**不持有 agent 字段的快照副本**」——实现建议：装配后用 `updatePolicy()` 换入 `systemPrompt` 已改的新快照，断言下一次 `AgentHandler` 调用产出的 argv 含新值，且期间**未发生 restart**（无断连事件）。

### 16.5 裁定⑤：删弱自发明的 validate 规则 —— 校验器不得拒绝可能合法的值

**问题**：上轮我自行补了 3 条无方案/旧码依据的校验规则（appId 正则、appSecret 长度下限、workspace.root 格式），可能误拒合法值。

**裁定结论（逐条）**：

| 字段 | 上轮规则 | **最终裁定** | 结果 |
|---|---|---|---|
| `connection.appId` | 非空时须匹配正则 `cli_[a-zA-Z0-9]+` | **仅非空白校验**（`isNotBlank`） | 🔴 删除正则 |
| `connection.appSecret` | 非空时 `length >= 16` | **仅非空白校验**（`isNotBlank`） | 🔴 删除长度下限 |
| `workspace.root` | 非空时须为绝对路径或 `~` 开头 | **允许空串（=未设置）；非空时不做任何格式校验** | 🔴 删除格式校验 |
| reply/help 默认帮助文案 | 我编写的一版帮助文本 | **保留** | ✅ 批准（属默认内容非校验规则，无害且填上了方案 jsonc 的 `"…"` 占位符） |

**删除理由**：
- **appId 正则**：飞书 appId 形态未经全量实测，且私有化/自建实例可能不同；**误拒会把用户彻底锁在门外**（配置校验失败 → 不落盘 → 无法启动桥接）。
- **appSecret 长度**：同理，长度是外部形态假设，不是本地可判定的契约。
- **workspace.root 格式**：方案明确「路径存在性检查延迟到 `connect()`/首次 agent 执行，避免启动期磁盘 stat 拖慢冷启动」，**格式校验同属应延迟的一类**；`~` 展开由 `exec/Paths.kt` 的 `expandTilde` 负责（旧 `LarkBridgeViewModel.kt:465` 同源），**不是校验器职责**。

**🔴 `ConfigValidator` 设计纪律（通则，本节为规范性条款）**：

> **`validate` 只做三类检查**：
> 1. **数值区间** —— `Editor.Number` 的 `min`/`max`，且用 **`coerceIn` 语义给出提示而非硬拒**（钳制到边界，不阻断落盘）；
> 2. **必填非空白** —— **仅** `connection.appId` / `connection.appSecret`，且**仅当 `agent.enabled == true` 或 `connection.autoStart == true` 时才必填**（未启用桥接时允许留空草稿）；
> 3. **枚举值合法性** —— `Editor.Choice` 的 `options` 内。
>
> **任何需要磁盘 stat、网络探测、或依赖外部形态假设（正则/长度/前缀）的检查一律不进 `validate`。**

**影响的章节（已就地修正）**：
- §3.2.1 `connection` 表 #95/#96 行 —— 校验列已改为「非空白（必填条件见 §16⑤）」。
- §3.2.3 `workspace` 表 #116 行 —— 校验列已改为「—（空串=未设置；格式与存在性均延迟校验）」。
- §5.2 表 **#1 / #2 / #12 行** —— validate 列已按上表改正；#12 的 hint 由「agent 沙箱根目录（必填）」改为「（空=未设置）」。
- §5.4 validate 实现规则 —— 已删除「appId 格式」条，改为 3 条删弱说明。
- §8.2 跨字段规则 —— 「`connection.appId` 非空但 `appSecret` 为空」已改为「`agent.enabled` 或 `autoStart` 为 true 时两者任一空白 → issue」。
- §13.3 `ConfigValidatorTest` —— 「appId 格式错误 → issue」断言已改为「格式错误**不**产生 issue」+ 新增「`workspace.root` 空串校验通过」。

**施工者必须遵守的硬性要求**：`ConfigValidatorTest` **必须含一条反向断言**——喂入 `appId = "not_a_cli_id"`（不匹配旧正则）与 `appSecret = "short"`（<16 字符），断言 **`issues` 为空**。这条断言是防止后人「好心」把格式校验加回来的守护。

### 16.6 裁定⑥：7 个旧「非用户可编辑」的 lib 字段全部保留暴露，不收缩

**问题**：`textInlineMaxBytes` / `allowedExtensions` / `triggerPrefix` / `maxConcurrentAgents` / `chatQueueLimit` / `queueWaitSeconds` / `taskTimeoutSeconds` 在旧版**只有 lib 侧 data class 默认值、无 DataStore key、用户改不了**。新 schema 让它们进了字段表，属能力扩张，是否收缩？

**裁定结论**：**全部保留在 `BridgeFields.ALL` 里，不收缩**（56 字段数不变）。

**理由**：
1. **方案的 JSON schema 已明确包含它们，schema 是权威**；
2. **UI 是 schema 驱动的通用渲染，多 7 个字段不会增加 ViewModel 一行代码**，与「VM ≤200 行」目标无冲突——该目标的威胁来自 **per-field StateFlow 与 per-field commit 方法**（旧 `LarkBridgeViewModel.kt` 780 行里 `commitXxx` 方法占了大头），而方案已把这两者归零（**per-field** flow 与 **per-field** commit 方法均已消除：配置状态收敛为单一不可变 `StateFlow<BridgeConfig>`（`_draft`）+ `BridgeField.set` 通用提交；🔴 但 VM 并非「只有一个 flow」——实测是 7 个独立 `MutableStateFlow`（`_draft` + `_issues`/`_applyResult`/`_startResult`/`_busy`/`_banner`/`_probe` 6 个一次性信号），设计裁定接受该状态形状，理由见 §15 #14）；
3. 这是「全功能平移」的**超集**而非缺失，属可接受的能力扩张（用户已拍板决策 3）。

**配套要求 —— 两组默认折叠**：

| ConfigGroup | `initialExpanded` | 该组内的「高级旋钮」 |
|---|---|---|
| CONNECTION | `true` | — |
| ACCESS | `true` | — |
| WORKSPACE | `true` | — |
| **RUNTIME** | **`false`** | 4 个：`maxConcurrentAgents` / `chatQueueLimit` / `queueWaitSeconds` / `taskTimeoutSeconds` |
| AGENT | `true` | — |
| **FILE_MESSAGE** | **`false`** | 3 个：`textInlineMaxBytes` / `allowedExtensions` / `triggerPrefix` |
| OBSERVABILITY | `true` | — |

**折叠理由**：高级旋钮默认收起，避免首次配置被 **56 个字段**淹没；这也缓解方案 **R21「首次配置卡在后台」**的体验问题（用户首次进页面只需面对连接/访问控制/工作区/Agent 四组的核心字段）。

**影响的章节（已就地修正）**：
- §6.1 `ConfigGroup` 枚举 —— 已增加第 3 个构造参数 `initialExpanded: Boolean`，RUNTIME 与 FILE_MESSAGE 为 `false`，其余 5 组为 `true`。
- §6.2 UI 7 张 SectionCard 表 —— 已增加 `initialExpanded` 列。

**施工者必须遵守的硬性要求（T13）**：`SectionCard(collapsible = true, initialExpanded = group.initialExpanded)`——**直接从枚举取值，禁止在 Composable 里硬编码哪组折叠**（否则新增分组时会漏配）。折叠状态**不需持久化**（每次进页面按枚举默认值展开/收起）。

### 16.7 裁定⑦（附带批准）：🔴 `BridgeJson` 严禁被 `audit.jsonl` / `sender_stats.jsonl` 复用

**问题**（上轮我只报告未请求裁定，Leader 主动批准并要求固化）：config 用的 `BridgeJson` 配方含 `encodeDefaults=true` + `prettyPrint=true` + `classDiscriminator="kind"`，若被审计/统计复用会**破坏审计文件兼容性**。

**裁定结论**：T8 施工者必须为审计/统计定义**独立的 `AuditJson` 实例**：

```kotlin
// lark-bridge/observe/AuditJson.kt —— 与 BridgeJson 严格分离，禁止互相引用
val AuditJson = Json {
    ignoreUnknownKeys = true      // 向后兼容旧审计行
    encodeDefaults = false        // 🔴 可空字段为 null 时【缺键】（BC-306/BC-308 契约）
    explicitNulls = false         // 同上，双保险
    prettyPrint = false           // 🔴 jsonl 必须单行紧凑（一行一条记录）
    // 🔴 不设 classDiscriminator（审计记录非多态 sealed，设了会污染 schema）
}
```

**冲突点逐条对照**：

| 配方项 | `BridgeJson`（config） | `AuditJson`（jsonl） | 若误用 `BridgeJson` 的后果 |
|---|---|---|---|
| `encodeDefaults` | `true` | **`false`** | 可空字段为 null 时**写出默认值键**，违反 BC-306/BC-308「null 时缺键」契约，旧 UI 统计读取逻辑失效 |
| `prettyPrint` | `true` | **`false`** | 一条记录被拆成多行，**`JsonlTailReader` 按行解析直接崩**（jsonl 的生命线是「一行一条」） |
| `classDiscriminator` | `"kind"` | **不设** | 审计记录被注入无关的 `"kind"` 键，schema 污染 |
| `ignoreUnknownKeys` | `true` | `true` | 一致，均需向后兼容 |
| `explicitNulls` | `false` | `false` | 一致 |

**实测证据**：旧 `AuditLogger.kt:53` 就是 **`encodeDefaults = false`**，本文档 §2 的「对齐点」段早已指出这一点，但未升级为禁令——本条把它升级为**硬性禁令**。

**🔴 现实约束（不可忽略）**：`~/.easy_dev_toolbox/lark/audit.jsonl` 下**已存在用户真实使用桥接产生的既有数据**（实测约 11KB 真实记录）。因此：
- 新旧产物**必须落在同一目录**（§1.2 `ConfigPaths` 派生表已保证）；
- **audit 字段 schema 必须与旧完全一致**（旧文件仍要能被新 UI 的统计卡读回）；
- 新写入的行必须能被旧 schema 读回（双向兼容）。

**影响的章节**：§2（已就地补入「严禁复用」禁令与 `AuditJson` 独立实例要求）。

**施工者必须遵守的硬性要求（T8）**：新增测试断言两条——
1. **新写入的 audit 行能被旧 schema 读回**（字段名/缺键语义一致）；
2. **旧 `~/.easy_dev_toolbox/lark/audit.jsonl` 里的既有行能被新 `JsonlTailReader` 解析**（测试用 fixture 复刻旧行格式，含 BC-525 的 `kind == null` 旧行 → 落「未知」桶）。

### 16.8 本次修正后 §5 表的最终统计（规范性数据，`BridgeFieldsTest` 据此断言）

**字段总数：56**（原 55 + 设计裁定新增 1 项，见下方修正记录）。

> **修正记录（设计裁定，2026-09-08）**：56 = 原 55 + `observability.logConsole.enabled`（设计裁定新增，语义 = 是否把 lib 侧日志收进 UI 日志台；旧 UI 的「观察」开关，属全功能平移必补项；归属 HOT / OBSERVABILITY 组 / Switch editor）。✅ **已补齐**：§5.2 完整枚举表第 56 行 `observability.logConsole.enabled`（label=日志台开关 / hint / editor=Switch / secret=false / get / set / validate=— / applyMode=HOT）已按 `BridgeFields.kt:514-518` 实测声明逐列补入，默认值 `enabled=true`（`BridgeConfig.kt` `LogConsoleConfig`）。🔴 **插入位置 = OBSERVABILITY 组末尾（第 55 行 `followBottom` 之后），非 `capacity` 之前**——§5.2 表的排序规则实测为「按 `BridgeFields.ALL` 声明顺序」，而 `enabled` 在 `BridgeFields.ALL` 中排在 `capacity`/`tickerHz`/`followBottom` **之后（全表最后一个 BridgeField）**（与 `BridgeConfig.kt` `LogConsoleConfig` 里 `enabled` 居首的属性声明顺序不同，以字段表顺序为准）。本节各计数表与 §5.2 明细行数（现 56 行）现已自洽。

**按 `ConfigGroup` × `applyMode`**：

| ConfigGroup | 字段数 | HOT | RESTART | `initialExpanded` |
|---|---|---|---|---|
| CONNECTION | 6 | 3 | 3 | `true` |
| ACCESS | 5 | 5 | 0 | `true` |
| WORKSPACE | 5 | 0 | 5 | `true` |
| RUNTIME | 8 | 8 | 0 | **`false`** |
| AGENT | 17 | **15** | **2** | `true` |
| FILE_MESSAGE | 7 | 7 | 0 | **`false`** |
| OBSERVABILITY | 8 | 8 | 0 | `true` |
| **合计** | **56** | **46** | **10** | 5 组 `true` / 2 组 `false` |

**RESTART 的 10 个字段完整清单**（`BridgeFieldsTest` 应逐一断言，防漂移）：

```
connection.appId, connection.appSecret, connection.region,
workspace.root, workspace.extraRoots, workspace.agentWorkDir,
workspace.envAllowlist, workspace.outputCharset,
agent.backend, agent.enabled
```

**按 `Editor` 类型（8 种，未新增第 9 种）**：

| Editor | 字段数 | 备注 |
|---|---|---|
| `Number(min, max, displayScale, unitSuffix)` | **19** | 其中 **1 个**（`fileMessage.maxFileSizeBytes`）启用 `displayScale=1048576` / `unitSuffix=" MB"`，其余 18 个用默认值（`displayScale=1`），行为与旧版一致 |
| `Switch` | **15** | — |
| `Text` | **7** | 含 `fileMessage.allowedExtensions`（`String` 类型，见 §16.3 澄清） |
| `Choice(options)` | **6** | `access.mode` / `agent.backend` / `agent.permissionPreset` / `workspace.outputCharset` / `agent.confirm.selfCheck` / `observability.logLevel` |
| `Tags(separator, pickDirectory)` | **4** | 其中 **1 个**（`workspace.extraRoots`）启用 `pickDirectory=true`，其余 3 个（`access.allowedSenders` / `access.blockedSenders` / `workspace.envAllowlist`）为 `false` |
| `Multiline` | **2** | `agent.customTemplate` / `agent.systemPrompt` |
| `Directory` | **2** | `workspace.root` / `workspace.agentWorkDir` |
| `Secret` | **1** | `connection.appSecret` |
| **合计** | **56** | — |

**`secret = true` 计数：恰好 1**（`connection.appSecret`）。打印 / 日志 / CLI `check` 输出统一掩码（前 4 后 4，中间 `*`），单点控制。

**`Editor` 参数化改动的影响面小结**：8 种 editor **类型数不变**，只是 `Number` 与 `Tags` 各增带默认值的参数 ⇒ **对 54 个未启用新参数的字段零影响**（默认值即旧行为），回归面仅限 2 个字段。

### 16.9 零决策声明

> ✅ **T5（配置域）与 T13（schema 驱动 UI）现已完全零决策**：schema、`BridgeFields.ALL` 56 行、`Routes` sealed 层级、默认文档与 5 条 routes、校验纪律、写入路径、迁移框架、35 key 映射、错误码、editor 参数化、分组折叠、测试清单、grep 断言，均已逐行定稿，施工者可照本文档直接落地，无需现场判断。
>
> ⚠️ **唯一例外**：§16.1 中提到的 **`behavior-contract.md` NF-08 需由 T5 施工者按本节更正**——本轮**未修改该文件**（另一 agent 正在并发只读，改动会造成读到半成品）。T5 施工者应：① 按 §16.1 写 `taskTimeoutSeconds == 60L` 与 `agentTimeoutMinutes == 10L` 两条断言；② 在 T5 的完成报告里注明「NF-08 已按 config-domain-spec §16.1 更正」，由 Leader 决定何时回写 `behavior-contract.md`。
>
> **对其他里程碑的溢出要求**（本文档只登记，不由 T5/T13 承接）：
> - **T8**：§16.4 的 3 条硬性要求（agent 字段禁止装配期缓存）+ §16.7 的 `AuditJson` 独立实例与 2 条兼容性断言。
> - **T3 / `gateway-contract.md`**：§16.7 的 `AuditJson` 与网关侧 `Tuning.kt` 常量分属两份文档，勿混。
> - **T12**：`:core` 的两处改动（`JErrorCode` shell 段注释改「1000 - 1999」+ `FileUtils.getLarkDir()`），见 §12 与 §1.3。

---

> **文档结束。** 共 **16 节 + 3 附录**。`BridgeFields.ALL` 枚举 **56 个**用户可编辑标量字段（**46 HOT / 10 RESTART**，8 种 editor，1 个 secret）。§16 为 设计裁定补录，与前 15 节同等效力。
