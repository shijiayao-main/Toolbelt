# 会话存储域（SessionStore 家族 / ChatEntry 模型族 / 会话 reducer）

> 源码分布（均在 `:core`）：
> - **存储层** `core/data/db/`——`Database`（进程唯一 SQLite 装配）、`SessionStore`（接口契约）、`SqliteSessionStore`（生产实现）、`SessionLedger`（JSONL 变体）
> - **模型族** `core/llm/agent/ChatModels.kt`——`ChatEntry` sealed 族及其配套枚举/chip
> - **reducer 引擎** `core/llm/agent/ChatSessionReducer.kt`——Koog 事件 → ChatEntry 归约 + `NavActivityFacade`
>
> AI 能力域总览见 [pages/ai-settings.md](../pages/ai-settings.md)「会话存储」节；工作台 / Quest /
> 蒸馏三消费面的页面装配见 [pages/log-workbench.md](../pages/log-workbench.md) /
> [pages/quest.md](../pages/quest.md) / [pages/ai-settings.md](../pages/ai-settings.md)「知识蒸馏」节。
> 配置类持久化（DataStore）不属本域，见 [core/data.md](data.md)。

## 数据流总览

```
Koog 事件流（KoogLoopEvent）
  └─ ChatSessionReducer.onLoopEvent()          ← Koog 事件 → ChatEntry 归约（三 Session 委托单源）
       ├─ 内存态：entries: MutableStateFlow<List<ChatEntry>>（UI 时间线单一真源）
       └─ 落盘桥：persistEntry → SessionStore.append(sessionId, entry)
            └─ 生产装配 = SqliteSessionStore(Database.database())
                 └─ ToolbeltDb（SQLDelight：sessions 表 + messages 表 + messages_fts）
```

回看方向：`SqliteSessionStore.replay(sessionId)` 按 `seq` 行序重建 `List<ChatEntry>`，
会话类经 `ChatSessionReducer.restore()` 整体替换进 entries flow。

---

## Database（进程唯一 SQLite 装配 + user_version 迁移语义）

`object Database`（`core/data/db/Database.kt`）。库文件落
`~/.toolbelt/db/toolbelt.db`（`FileUtils.getDbDir()`，模块不做路径决策）。

| 入口 | 语义 |
|------|------|
| `database()` | 进程唯一 `ToolbeltDb`（懒初始化；线程安全——双检锁经 `@Volatile` 幂等）。生产恒经此取用：SQLite 同文件多连接写会锁竞争（对齐 `AppDataStore` 单例纪律） |
| `forFile(dbFile)` | 测试/自定义落点：直接以指定文件建库，**不进进程单例池** |
| `inMemory()` | 单测内存库（零文件零清理） |
| `schemaVersionForTest()` | 当前 schema 版本（迁移断言测试缝——「回写当前版本」语义免写死数字） |
| `chatHistoryConnection(dbPath)` | chat_history 域的进程唯一共享 JDBC 连接（`SqliteChatHistoryProvider` 用；按路径区分 + `computeIfAbsent` 原子建连，测试临时库多路径并存） |

连接配置（`forFile` 内 `Properties`）：

- `journal_mode = WAL`——读不阻塞写（会话写 + 列表读并发不互斥；不支持网络文件系统，桌面场景无碍）；
- `foreign_keys = true`——SQLite 外键默认关（`messages.session_id REFERENCES sessions`）需显式开。

### 🔴 user_version 迁移语义（建表/迁移判定与回写顺序）

- **建表判定**：SQLDelight 无 `.sqm` 迁移文件时 `migrate()` **只回放迁移、不建表**——首次建库必须
  显式 `create()`。老库凭 SQLite 页头 `PRAGMA user_version` 判断（`queryUserVersion`）：
  - `currentVersion == 0`（空库/新建）→ `schema.create()`（幂等——`CREATE IF NOT EXISTS` 语义）；
  - `0 < currentVersion < schema.version` → `schema.migrate(driver, currentVersion, schema.version)`
    回放 `.sqm` 增量。
- 🔴 **user_version 回写在两分支之后统一执行**：`migrate()` 自身**不落 user_version**——若只在
  create 分支回写，v1 存量库升 v2 后版本号仍停在 1，下次启动重复回放迁移撞
  「table already exists」。
- schema 真源 = `core/src/commonMain/sqldelight/.../db/` 的 `.sq`（v1 全量：sessions / messages /
  messages_fts + 同步触发器）+ `1.sqm`~`4.sqm`（增量：知识库四表 / runs / flows / spaces）。
  **新增迁移 = 新增 `N.sqm`**，SQLDelight 自动把 `Schema.version` 抬到 N。

---

## SessionStore 接口契约（`SessionStore.kt`）

会话 reducer 经本接口消费存储，**装配点决定实现**：生产 = `SqliteSessionStore`（SQLite 单源）、
测试 = `SessionLedger`（JSONL）或 `ledger = null`（纯内存零落盘）。

| 方法 | 契约 |
|------|------|
| `append(sessionId, entry)` | 追加一条；**同会话同 id 覆写 = 幂等 upsert**（工具卡 RUNNING→终态回填路径） |
| `tombstone(sessionId)` | 会话废弃（reset/新建会话）：此后 `replay` 不再返回该会话条目 |
| `replay(sessionId)` | 重建会话条目（装配顺序即落盘顺序）；未知会话返回空表 |
| `latestSessionId()` | 最新会话 id（重启恢复用；无会话返回 null） |
| `load()` | 装载存量数据，实现自决：JSONL 读文件重建 / SQLite 恒 no-op（见下） |

瞬态条目（`ChatEntry.Activity` / `ChatEntry.TodoBoardSnapshot`）两个实现一致**不落盘**——
活动文案从事件流重新推导，快照重放无价值。

---

## SqliteSessionStore（生产实现）

构造 `SqliteSessionStore(db: ToolbeltDb)`。messages 行式存储（可检索 FTS5 / 可管理面直查）。

### 🔴 seqCounters 必须 ConcurrentHashMap（并发不变量）

```kotlin
private val seqCounters = ConcurrentHashMap<String, Long>()
```

**理由（值 + 依据）**：`append` 从**运行协程与 UI 线程两语境可达**（reducer 落盘桥在运行协程
`onLoopEvent` 内、也在 VM 的回看/手工回补等 UI 侧路径内），普通 `HashMap` 并发 `merge` 丢 seq
后被 `ON CONFLICT` **静默覆写既有行**（会话条目丢失，无异常无日志）——必须 `ConcurrentHashMap`。

seq 计数器是**进程内会话粒度**内存态；`UNIQUE(session_id, seq)` 冲突走 upsert 覆写
（同 seq = 同一会话内同一逻辑位的终态回填）。`replay` 后回填计数器（重放后的 append 从
max+1 续号）；`tombstone` 时 `remove` 清位。

### 语义映射（与 JSONL 账本逐字对齐）

| 接口语义 | SQL 落点 |
|----------|----------|
| append 幂等 upsert | `upsertMessage ... ON CONFLICT(session_id, seq) DO UPDATE` |
| tombstone | `sessions.markTombstone`（墓碑行，`activeSessions`/`latestSession` 过滤 `tombstone = 0`） |
| replay | `messagesForSession`（`ORDER BY seq ASC`）行序重建 ChatEntry |
| latestSessionId | `latestSession`（`updated_at` 倒序第一行） |

### 会话自动登记

append 首条时 upsert 会话行（title = 首条 USER 截 80 字，空文本兜底首附件名/「新会话」）——
管理面无需显式「创建会话」调用。🔴 **登记只做一次**：后续 append 的 upsertSession 会用兜底
标题覆写首条派生的好标题——`ensureSession` 只在会话行不存在时执行。
FIFO 容量淘汰**不做**（SQLite 是全量面；上界管理属 UI 批）。

### 管理面（直查 sessions/messages 表，接口外扩）

- `listSummaries(): List<SessionSummary>`——活会话摘要行（`SessionSummary(id, title, updatedAt, messageCount)`，`updated_at` 倒序）；
- `rename(id, title)`；
- `delete(id)`——🔴 级联删：外键阻止裸删，**同事务先消息后会话**；SQLDelight 多语句命名查询
  （`deleteSessionCascade` 两连 DELETE）经 `executeAsOne` 消费时只取首条——拆两个单语句查询
  （`deleteMessagesForSession` + `deleteSession`）各执行一次。

### 落盘编码细节（回放保真）

- **observation 截 `OBSERVATION_LIMIT = 2000` 字符**（防 MB 级 tool 结果撑爆库）；
- **artifacts 编码 `path|kind`**（🔴 分隔符是 `|` 非 `:`——Linux 路径可含冒号；解码按
  `lastIndexOf('|')`，纯 path 旧数据 / kind 非法时整串作路径、kind 回落 `FILE`——存量行零迁移直读）；
- attachments / artifacts 列表经 kotlinx.serialization JSON 串（空表存 null）；
- 回放态 `toolCallId = null`（事件链已断，卡片恒终态渲染）；`state` 非法/缺失回落
  `COMPLETED`、`errorKind` 回落 `FAILED`；未知 role 返回 null（前向兼容旧数据）；
- `entry_id` 缺失时合成 `db-<sessionId 前 8 位>-<seq>`。

### load() = 显式 no-op

SQLite 存量行落库即生效，无外部装载面——`load()` 体为 `Unit`。接口方法保留是因为
`SessionLedger`（JSONL）实现需要真实 load（读文件重建内存索引）；生产装配无需也无人调它。

---

## SessionLedger（JSONL 变体——测试实现与历史参照）

构造 `SessionLedger(sessionDir: Path? = null, keepSessions: Int = 16)`（null = 纯内存，测试零落盘默认）。
**生产装配不出现此实现**（全仓生产源码零构造点）；价值 = 同契约的历史参照 + 测试替身。

机制（对齐 lark-bridge CardReplyLedger 配方）：

- **追加写**：每条 ChatEntry 落一行 `EntryLine`（JSONL，文件名 `sessions.jsonl`，目录由构造注入）；
- **墓碑行**：tombstone 写 `TOMBSTONE` 行——装载时该会话既有条目整会话丢弃；
- **坏行跳过**：追加写崩溃残留的半行解码失败直接跳过（计数告警）；
- **启动压缩**：`load()` 后立即全量重写文件（只回写活条目，防无上界增长）；
- **容量淘汰**：每会话一个记录位，`keepSessions` 上界 FIFO（`DEFAULT_KEEP_SESSIONS = 16`）。

DTO 单独建模（`EntryLine` @Serializable）而非直接序列化 ChatEntry：`:ui` 模块保持纯数据类
不加 serialization 插件（依赖面决策），映射在 feature 侧完成。落盘内容面 = 摘要面
（User/Assistant 全文，ToolCall 名称+argsSummary+observation 截 2000+artifacts 路径，
ErrorBanner kind+title+detail）；崩溃残留的 RUNNING 卡重放降级 CANCELLED（不能永远挂 spinner）。

---

## ChatEntry 模型族（`core/llm/agent/ChatModels.kt`）

消息流条目模型（参照 OpenHands Action→Observation 消息流），**零 Koog 依赖**——Koog 事件到
ChatEntry 的映射在 reducer，:ui 模块可直接消费本模型族。

`sealed interface ChatEntry`（稳定 id = LazyColumn key）：

| 变体 | 字段面 | 语义 |
|------|--------|------|
| `User` | text / attachments | 用户消息（附件 = 拖入文件名列表，渲染为 chips） |
| `Assistant` | text / streaming | 助手消息；`streaming = true` 为打字机草稿态（delta 由消费方节流合并） |
| `ToolCall` | toolCallId / toolName / argsSummary / state / observation / artifacts | **同一张卡的状态迁移**而非多条消息（RUNNING → COMPLETED/FAILED/CANCELLED 时 observation 回填，不增行） |
| `Activity` | text / running | 活动指示（deriveLiveActivity 模式——从事件流推导的当前活动文案）；瞬态不落盘 |
| `ErrorBanner` | kind / title / detail | 流内错误条目 |
| `TodoBoardSnapshot` | items: List<TodoChip> | 计划板快照（write_todos → TodoBoard → 会话派生投影）；瞬态不落盘 |

配套类型：

- `ToolState`（RUNNING/COMPLETED/FAILED/CANCELLED）+ `displayText()`——中文显示名单源（渲染面禁再内插枚举英文名）；
- `ErrorKind`（TIMEOUT/FAILED/CANCELLED）；
- `ArtifactChip(path, kind)` + `ArtifactChipKind`（FILE/DIRECTORY/EXTRACTED/REPORT）——产物精确路径（结构化 ToolResult 的 UI 投影，消灭兄弟文件启发式）；
- `TodoChip(content, status)` + `TodoChipStatus`（PENDING/IN_PROGRESS/DONE）；
- `ChatEntry.nextId()`——无业务语义 id 时用（`chat-<AtomicLong>`，进程内单调）。

---

## ChatSessionReducer（三 Session 委托单源）

`core/llm/agent/ChatSessionReducer.kt`。Workbench / Quest / Distill 三个会话类共用本引擎
（**委托持有**）——「Koog 事件 → ChatEntry」reducer 家族与活动门面的单源，改动须顾及三个消费语境。

```kotlin
class ChatSessionReducer(
    entries: MutableStateFlow<List<ChatEntry>>,  // 事件流（单一真源：会话类与本引擎共操作同一 flow）
    ledger: SessionStore? = null,                // 落盘桥（null = 不落盘）
    ledgerKey: String,                           // 落盘 key = messages.session_id
    streamingEnabled: Boolean = true,            // 流式打字机面门控
)
```

### 三消费委托（时间线三层同 key：ledgerKey = 各域主键 = messages.session_id）

| 消费方 | 位置 | ledgerKey | streamingEnabled |
|--------|------|-----------|------------------|
| `WorkbenchSession` | `:feature-logworkbench` `page/logworkbench/` | runId | true（工作台开流式面） |
| `QuestSession` | `:feature-quest` `page/quest/` | flowId | true（流程会话开流式面） |
| `DistillSession` | `:feature-ai` `page/ai/distill/` | distill 会话 id（`distill-<UUID>`） | false（蒸馏链不开流式面——进度走 report_step 结构化旁路） |

### 公共 API

| 方法 | 语义 |
|------|------|
| `onLoopEvent(event: KoogLoopEvent)` | Koog 事件归约主入口：LlmCallStarting→活动条；LlmCallCompleted→草稿转正/追加 Assistant；ToolCallStarting→活动条+RUNNING 卡；ToolCallCompleted/Failed→按 toolCallId 原位状态迁移+终态落盘；ExecutionFailed→ErrorBanner；StreamingFrame→（streamingEnabled 门控）delta 累积进末尾草稿 |
| `finalizeRun(output)` | run 收尾统一处理：草稿悬挂（流中断无 Completed）转正兜底/丢弃；终稿消重（末条 assistant 与 output 同文本跳过） |
| `restore(list)` | 条目重放（历史回看：整体替换 entries） |
| `clear()` | 条目清空（会话 reset） |
| `appendError(kind, title, detail)` | 错误横幅追加（含落盘） |
| `appendUser(text)` | 用户条目追加（Quest 多轮对话形态；含落盘） |
| `appendToolCallCard(...)` | 时间线追加工具卡（Workbench 直跑降级路径——管线产物手工回补；终态卡才落盘） |
| `attachArtifactsToLastToolCall(name, chips)` | 工具产物精确挂载（挂末尾最近一条同名工具卡） |

### 落盘桥纪律（persistEntry）

`persistEntry` 只落**稳态条目**：Assistant / ErrorBanner / 终态 ToolCall。RUNNING 卡不落盘——
终态时 `settleToolCard` 以同内容追加（`SessionStore.append` 新 seq 语义），与 upsert 覆写
语义合流为「终态回填」。Activity / TodoBoardSnapshot 两个实现侧一致滤除。

### NavActivityFacade（同文件）

侧栏活动指示门面：`activity: StateFlow<NavActivity>`（宿主经 ToolPageRegistration.navActivity
消费）；`attach(running, scope)` 镜像会话运行态（VM attach，切会话先 `detach()` 收旧镜像）。

---

## 与其他持久层的关系（双轨不合成）

- **`SqliteChatHistoryProvider`**（同目录）：Koog `ChatHistoryProvider` 的 SQLite 实现
  （chat_history 表，每会话一行 messages_json 整体替换）——**Koog ChatMemory 续聊真源**；
  本域 messages 表是 **UI 时间线回看真源**。ChatEntry↔Message 映射有损（Activity 无对应物），
  两层双写、不合成（合成一层必发散）。
- db/ 目录其余仓储与本域共享 `Database` 单例但各管各表：`RunStore`（runs 表，工作台 run 元数据）、
  `QuestStore`（flows 表）、`SpaceStore`（spaces 表）、`KnowledgeRepository`（知识库四表）；
  蒸馏入库链见 [pages/ai-settings.md](../pages/ai-settings.md)。
