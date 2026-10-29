# 数据存储层

数据存储层源码**跨模块分布**：基础设施（`AppDataStore` / `LocalDataManager` / `DataStoreExtensions` / `EnumRestore` / `FileUtils` / `DesktopUtils`）位于 `:core` 的 `core/data/`；宿主域仓库 `EasyDataManager.kt` 物理位于 `:composeApp` 模块的宿主包 `com.jiaoay.easy_dev_toolbox.host`（2026-09-10 迁入，随迁的还有 `host/initializer/LogFileRestoreInitializer`）；各 feature 域配置仓库在各自 feature 模块内（归属见下文「配置项总表」）。

---

## 文件总览

| 文件 | 位置 | 职责 |
|------|------|------|
| `AppDataStore.kt` | `:core` | 进程唯一 Preferences DataStore 持有者：所有域配置仓库共享此实例（同一文件多实例会启动即崩）；`internal storeForTest` 仅供 jvmTest 以临时目录实例替换 |
| `DomainConfigStore.kt` | `:core` | 域配置仓库共享骨架（抽象基类）：同构 `read`/`write` 委托对的唯一真源 + `activeStore` 读写通道（默认恒为 `AppDataStore.store`）。新增一个域配置仓库 = 继承本类 + 声明 key 常量与一行 get/set；带测试缝的域把 `activeStore` override 为缝 var（缝本体留在各 Store 自己的 object 内——internal 跨模块不可见），无缝域不 override |
| `EasyDataManager.kt` | `:composeApp`（`host/` 宿主包） | **宿主域**配置仓库：外观/导航/调试开关/日志开关/Shell 类型；泛型 `read/write` 收敛实现。各 feature 域 key 已迁往各自模块的 ConfigStore，不再集中于此 |
| `LocalDataManager.kt` | `:core` | 通用 DataStore 读写封装，含内存缓存；🔴 `datastoreDir` 构造注入参数（构造期钉死落盘目录——测试隔离）+ `setValue`/`setValues`/`clear` 的 post-edit 权限硬化钩子（`hardenSecretFile`，见下文） |
| `SecretFilePerms.kt` | `:core` | 🔴 密钥文件权限硬化单源：判定谓词 `isSecretPreferencesFile`（文件名**包含** `.preferences_pb`——覆盖 `.bak-forensic`/`.tmp` 后缀变体，调用点不得私写）+ `harden600` 落盘助手（rw-------；绝不向上抛，非 POSIX 跳过/失败告警每会话至多一次） |
| `DataStoreExtensions.kt` | `:core` | DataStore 的 suspend 扩展函数，处理类型映射 |
| `EnumRestore.kt` | `:core` | `loadEnum(name, default)`：枚举名恢复 + 非法回退 |
| `DesktopUtils.kt` | `:core` | `openInFolder(path)`：打开文件所在文件夹（收敛各页面重复实现） |
| `FileUtils.kt` | `:core` | 存储路径单源：机密根访问器（`~/.toolbelt/` 下 `getLogDir` / `getDatastoreDir` / `getLarkDir` / `getDbDir` / `getMemoryDir` / `getVoiceTranscriptsDir` 等，均不自动 mkdirs）+ 可见产物树访问器（`~/Documents/Toolbelt/` 下 `getReportsExportDir` / `getRecordingsExportDir` / `getSnapshotsExportDir` 等，目录按需 mkdirs）+ 旧根常量（迁移判定专用）。🔴 子目录路径必须经访问器构造——`File(getAppDataDir(), "字面量")` 直拼被 `StoragePathLiteralGuardTest` 拦截 |
| `db/` | `:core` | SQLite 数据库域：进程唯一 `Database` 单例装配（`~/.toolbelt/db/toolbelt.db`，WAL + user_version 迁移）+ SessionStore 会话存储家族（`SqliteSessionStore` 生产实现 / `SessionLedger` JSONL 变体）+ runs/flows/spaces/知识库各表仓储门面。专篇见 [core/session-store.md](session-store.md) |
| `LegacyDataRootMigration.kt` | `:core` | 旧数据根一次性改名迁移（`~/.easy_dev_toolbox` → `~/.toolbelt` 整树 Files.move）。🔴 时序契约：宿主 main() 入口段调用，必须先于一切数据根访问（窗口几何/视觉态预载的 runBlocking DataStore 读已会触盘） |

各 feature 域配置仓库（均经 `AppDataStore.store` 读写，禁止自行 `PreferenceDataStoreFactory`）：

| 仓库 | 位置 | 覆盖域 |
|------|------|--------|
| `LogKitConfigStore` | `:feature-logkit`（`page/logkit/`） | xlog 私钥 / Tag 过滤 / 文件分割 |
| `ShellToolsDataStore` | `:feature-shelltools`（`page/shelltools/`） | Shell 历史 / 输出面板 / adb 路径 |
| `TimestampConfigStore` | `:feature-timestamp`（`page/timestamp/`） | 时间戳输出格式 |
| `LlmConfigStore` | `:core`（`core/llm/`，批 6 下沉——桥接调度 agent 复用端点配置） | LLM 端点（`llm_base_url` / `llm_api_key` / `llm_model_id` / `llm_timeout_seconds`，默认 60s）+ AI 工具开关 `llm_ai_enabled`（三态，见下）+ 性格/安全 4 key（2026-09-19 P4-C1 起专篇欠账，见 docs/index.md 欠账清单；源码为准） |

### FileUtils 目录访问器

存储布局定名（发版前定名批，此后冻结）：**双根语义**——机密根 `~/.toolbelt/`（应用私有数据，桥接出站整树排除）与可见产物树 `~/Documents/Toolbelt/`（用户交付物，整树可出群）。旧根 `~/.easy_dev_toolbox/` 由启动期 `LegacyDataRootMigration`（`core/data/`，main() 入口段调用，先于一切数据根访问）一次性整树改名；DataStore 文件名同批改 `toolbelt.preferences_pb`（`LocalDataManager` 首读写自动 rename 旧名文件，key 内容无损）。

**机密根访问器**（惯例一致：返回 `File`、不自动 mkdirs）：

| 访问器 | 返回路径 | 用途 |
|--------|----------|------|
| `getAppDataDir()` | `~/.toolbelt` | 宿主应用数据根（`lark/`、`.datastore/`、`logs/` 的公共父目录）。🔴 公开：出站敏感文件排除表（`SensitiveFilePolicy`）的判据 1 用它作**宿主机密根**——取本层而非 `getLarkDir()` 子目录，是因为 `.datastore/toolbelt.preferences_pb` 里存着**明文 LLM API Key** 与 xlog 私钥，只防 `lark/` 会在同级目录留缺口。消费点：`LarkBridgeManager` 的 `homeDataRootProvider` + `assemble(sensitiveRoot = …)`。 |
| `getLogDir()` | `~/.toolbelt/logs` | 日志存储根 |
| `getDatastoreDir()` | `~/.toolbelt/.datastore` | DataStore 目录 |
| 🔴 `getLarkDir()` | `~/.toolbelt/lark` | **飞书桥接数据根**（配置文档 `bridge-config.json` / 审计 `audit.jsonl` / 发送者统计 `sender_stats.jsonl` / 单实例锁目录）。桥接内核 `:lark-bridge` 是 jvmTarget 11 纯 JVM lib，**不得反向依赖 `:core`**，故数据根由宿主 `LarkBridgeManager` 经此访问器注入（`dataRoot = FileUtils.getLarkDir().toPath()`）；CLI 缺省根（`CliSupport.kt` 字面量）与它的同根契约由 `LarkCliDataRootParityTest` 钉死 |
| 🔴 `getDbDir()` | `~/.toolbelt/db` | **SQLite 数据库根**（库文件 `toolbelt.db`——`Database` 单例装配落点，目录由 `Database.forFile` mkdirs）。会话存储/工作台 runs/flows/spaces/知识库各域共享同一库文件，专篇见 [core/session-store.md](session-store.md) |
| `getMemoryDir()` | `~/.toolbelt/memory` | L2 记忆目录（工作台/智能体中心/调查工作台三装配点共用路径真源） |
| `getLogworkbenchRunsDir()` | `~/.toolbelt/logworkbench/runs` | 工作台 run 目录（runId 子目录由调用方追加） |
| `getVoiceTranscriptsDir()` / `getVoiceNotesDir()` | `~/.toolbelt/voice/transcripts`、`voice/notes` | 转写稿/纪要导出 |
| `getModelsDir()` | `~/.toolbelt/models` | ASR/VAD 模型目录（子目录由调用方追加） |
| `getNativeCacheDir()` | `~/.toolbelt/native-cache` | JNI native 解包缓存（`sherpa_onnx.native.path` 注入面） |
| `getScrcpyDir()` | `~/.toolbelt/scrcpy` | scrcpy server jar 释放目录 |
| `getAuditDir()` | `~/.toolbelt/audit` | **只读诊断域审计目录**（sys.* 工具执行落盘：`sys-audit-<yyyy-MM-dd>.jsonl` 纯追加，`SysAuditLog` 落点，目录首次落盘时 mkdirs）。🔴 审计树在宿主机密根内 ⇒ `SensitiveFilePolicy` 判据 1 整树拒发，且 `sys-audit` 名形前缀兜底（宿主根未知也不放行）——审计外发 = 擦痕迹式回读 |
| `getDevlogsDir()` | `~/.toolbelt/devlogs` | 设备日志快照目录（agent 设备域 dev.snaplog 落点，同判据 1 整树拒发） |
| `getShellToolsFile()` / `getShellToolsCustomFile()` | `~/.toolbelt/shell_tools*.json` | Shell 工具配置 |
| `getDownloadsDir()` | `~/Downloads` | 设备文件拉取默认落点（刻意在机密根之外——拉取产物是用户文件） |
| `getLegacyAppDataDir()` | `~/.easy_dev_toolbox` | 旧数据根（**只读**，`LegacyDataRootMigration` 迁移判定专用，禁止新写路径引用） |

**可见产物树访问器**（`<home>/Toolbelt/`，目录按需 mkdirs、创建失败留给调用方写盘报错；`home` 参数化供单测注入临时根）：

| 访问器 | 返回路径 | 交付物 |
|--------|----------|--------|
| `getReportsExportDir()` | `~/Documents/Toolbelt/reports` | 研判报告导出（`ReportExporter`）+ Tag 分布统计（`StatsMarkdownExporter`） |
| `getRecordingsExportDir()` | `~/Documents/Toolbelt/recordings` | 投屏录屏 mp4（`ScreencastRecordings`） |
| `getPerfExportDir()` | `~/Documents/Toolbelt/perf` | 性能 CSV（`PerfCsvExporter`） |
| `getLogcatExportDir()` | `~/Documents/Toolbelt/logcat` | logcat 抓取产物 txt |
| `getSnapshotsExportDir()` | `~/Documents/Toolbelt/snapshots` | 投屏快照 PNG（原机密根 `screenshots/` 迁入可见树；旧 PNG 不迁移不删除） |
| `getKnowledgeExportDir()` | `~/Documents/Toolbelt/knowledge` | 知识包导出 JSON（原 `knowledge/exports/` 迁入可见树；旧导出件不迁移） |

> 🔴 新增数据根子目录或可见产物子目录：**先**在 `FileUtils` 落访问器，再改调用面——直拼字面量会被 `StoragePathLiteralGuardTest` B/C 组拦截；旧根名 `easy_dev_toolbox` 字面量仅限迁移常量与打码双形态兼容（该测试 A 组白名单）。

---

## EasyDataManager

DataStore 文件路径：`~/.toolbelt/.datastore/toolbelt.preferences_pb`

实现收敛：所有 get/set 走两个私有泛型方法，每对方法一行：

```kotlin
private suspend inline fun <reified T : Any> read(key: String, default: T): T
private suspend inline fun <reified T : Any> write(key: String, value: T)

suspend fun getColorSchemeName(): String = read(KEY_COLOR_SCHEME, "Default")
suspend fun setColorSchemeName(name: String) = write(KEY_COLOR_SCHEME, name)
```

### 配置项总表（key → 类型 / 默认值 / 归属）

> 2026-09 模块化拆分后 key 不再全部集中在 `EasyDataManager`：feature 域 key 已迁往各自模块的 ConfigStore（key 字符串原样保留，单一 DataStore 文件不变，存量配置零迁移）。下表「归属」列为当前真实读写方。

| DataStore Key | 类型 | 默认值 | 归属 | 说明 |
|---------------|------|--------|------|------|
| `color_scheme` | String | `"Default"` | EasyDataManager（宿主） | 主题名（`AppColorScheme.name`） |
| `custom_seed_color` | Long | `-1` | EasyDataManager（宿主） | 自定义主题种子色（ARGB）；`-1` = 未设置 |
| `theme_mode` | String | `"System"` | EasyDataManager（宿主） | 外观模式（`ThemeMode.name`） |
| `debug_page_enabled` | Boolean | `false` | EasyDataManager（宿主） | 导航栏是否显示调试入口 |
| `last_page` | String | `"Default"` | EasyDataManager（宿主） | 上次停留的页面（`ToolPage.name`） |
| `log_file_enabled` | Boolean | `false` | EasyDataManager（宿主） | 文件日志开关（设置页"开启日志存储"），跨重启保留 |
| `shell_type` | String | macOS→`"ZSH"` / Linux→`"BASH"` | EasyDataManager（宿主） | Shell 类型（`ShellType.name`） |
| `window_x` / `window_y` / `window_width` / `window_height` | Int | `-1` | EasyDataManager（宿主） | 主窗位置与尺寸（AWT 物理像素；`-1` = 未记录，width/height ≤ 0 即视为无记录；x/y 允许负值——多显示器副屏坐标系）。写经 `setWindowGeometry` 四元组一次原子落盘，读侧校验（非法尺寸/屏外回退）单源在 `host/WindowGeometry.kt` `validatedWindowGeometryOrNull` |
| `screencast_detached_window_x` / `screencast_detached_window_y` / `screencast_detached_window_width` / `screencast_detached_window_height` | Int | `-1` | EasyDataManager（宿主） | 投屏独立视口窗位置与尺寸（配方同主窗 `window_*`：四元组原子落盘 + `validatedWindowGeometryOrNull` 读侧校验；值经 initial/write lambda 进 `ScreencastDetachedWindowHost`，feature 不感知持久化） |
| `xlog_private_key` | String | `""` | LogKitConfigStore | XLog 解码私钥 |
| `log_tag_regex` | String | `""` | LogKitConfigStore | Tag 正则（匹配日志行中的 Tag） |
| `log_tag_name` | String | `""` | LogKitConfigStore | Tag 名称（用于输出文件命名） |
| `file_split_size_mb` | Int | `10` | LogKitConfigStore | 文件分割大小（MB） |
| `timestamp_format` | String | `"LOG"` | TimestampConfigStore | 时间戳输出格式预设（存 `TimestampFormat` 枚举 name，恢复失败回退默认） |
| `shell_command_history` | String | `""` | ShellToolsDataStore | Shell 历史 JSON（kotlinx.serialization） |
| `visible_export_root` | String | `""`（默认 `~/Documents/Toolbelt/`） | EasyDataManager（宿主） | 可见产物树根的用户自定义路径（设置页「产物目录」）。生效通道：启动期 `VisibleExportRootRestoreInitializer` 读入 `FileUtils.applyVisibleExportRoot` 进程单源（访问器同步读）；运行时改配置经 `AppViewModel.setVisibleExportRoot` 同步写回，即时生效 |
| `shell_history_sort_mode` | String | `"MostUsed"` | ShellToolsDataStore | 历史排序模式 |
| `adb_executable_path` | String | `""` | ShellToolsDataStore | 自定义 adb 可执行文件路径（Shell 工具页命令与设备检测共用；空 = 系统 PATH 中的 adb，设置页可配置） |
| `lark_bridge_*`（旧 35 key，**只读、不再被运行时消费**） | String/Boolean/Long | — | 桥接域例外：新配置走 `bridge-config.json`，不入 DataStore；旧 key 仅 T15 导入读取（详见说明列与 `AGENTS.md` 约定 2） | 🔴 **飞书桥接已推倒重写完成**：新桥接域的配置改走**外部单一 JSON 文档** `~/.toolbelt/lark/bridge-config.json`（schemaVersion + 校验 + `.bak` 单代备份），由 `:lark-bridge` 的 `ConfigRepository` 管理，**不入 DataStore**（例外理由见 `AGENTS.md` 约定 2）。这些旧 key 现仅供 **T15 一次性导入**（`LegacyConfigImporter` → lib 侧纯函数 `LegacyDocumentMapper`）读取；导入成功后**不清除**旧值（保留回退能力），运行时不再读它们。旧 key 语义摘要：chat_id / dry_run / transport / app_id / app_secret / region / token_dir / workspace_dir / allowed_senders / blocked_senders / access_mode / rejection_reply / unhandled_reply + agent 组 + static_replies + skills；其中 `lark_bridge_transport`（新架构固定 SDK 长连接）、`lark_bridge_token_dir`（SDK 内部管理 token）、`lark_bridge_preset_migration_notice`（旧 preset 横幅瞬态标记）为**合法废弃、不迁移**。 |
| 🔴 `lark_bridge_legacy_imported` | Boolean | `false` | LegacyConfigImporter（`:feature-larkbridge`，T15 一次性导入） | **新增标记**（旧树不存在）：T15 一次性导入的幂等辅助。F-8 收窄后语义：此标记**只**用于「确认无旧数据可迁后避免每次启动重复读 DataStore」，**不再**是迁移的无条件短路闸门。门控主闸门 = `bridge-config.json` 是否存在（存在即跳过迁移）。有旧数据时**无论标记是否置位都执行迁移**；迁移成功后 config 存在 ⇒ 下次启动被主闸门拦住 ⇒ 不会重复迁移。导入失败**不写文档、不置标记**，以便下次自动重试。手工重新导入路径 = 删除 `bridge-config.json` 后重启 App（若想彻底从零开始不要旧设置，需同时清掉旧 DataStore 里的 `lark_bridge_*` 键）。 |

> **新增配置项的步骤（双轨）：**
> 1. 按域选仓库：宿主域 key（外观/导航/调试开关/日志开关/Shell 类型）在 `EasyDataManager` 添加；feature 域 key 在该域的 ConfigStore 添加（`LogKitConfigStore` / `TimestampConfigStore` / `ShellToolsDataStore`）——feature 域新仓库继承 `:core` 的 `DomainConfigStore` 共享骨架（key 常量 + 一行 get/set 走骨架 `read`/`write`；测试缝 = object 内 `internal var store = AppDataStore.store` + `override val activeStore get() = store`，无缝域不 override，禁止自行 `PreferenceDataStoreFactory`）
> 2. 如需 UI 状态，在 `AppViewModel`（宿主域）或该页 ViewModel（feature 域）添加 StateFlow + `init` 恢复（enum 用 `loadEnum`）
>
> 🔴 SH-13（重设计批 10）死 key 清账：旧表中的 `shell_output_panel_height_dp` /
> `shell_output_panel_expanded` 两 key 已随三舱迁移 + 输出台形态上提退役
> （`ShellToolsDataStore` 现仅上述 3 key）；输出台高度 = 会话级 `rememberSaveable`
> （OutputHeightDivider 拖拽），不持久化——明细见 [pages/shell-tools.md](pages/shell-tools.md) DataStore key 节。
> 3. 如有设置项 UI，修改 `PageSettings`

---

## LocalDataManager

```kotlin
class LocalDataManager(
    private val dataStoreName: String,
    private val datastoreDir: File? = null,
    private val datastoreScope: CoroutineScope? = null,
    private val legacyFileNames: List<String> = emptyList(),
)
```

- DataStore 文件存放在 `~/.toolbelt/.datastore/<dataStoreName>.preferences_pb`
  （`datastoreDir` 为 null 时经 `FileUtils.getDatastoreDir()` 解析；生产恒经 `AppDataStore` 单例构造、不注入）
- 🔴 **`datastoreDir` 注入参数（测试隔离用）**：DataStore 的落盘路径在本实例**首次读写**时才解析
  （androidx FileStorage 在 createConnection 时才调 produceFile），故测试隔离必须在**构造期**把目录
  钉死——否则用例还原 `user.home` 后才执行的首操作会把文件落进真实数据目录。null = 沿用既有
  lazy 语义。既有测试锚：`core/data/LocalDataManagerDirInjectionTest`（谓词变体 + 注入目录断言）
- 🔴 **`datastoreScope` 注入参数（DataStore 内部读写协程的宿主 scope）**：DataStore 内部
  读经 `readState`、写经 `writeActor`，恒派发到该 scope。null = 库省缺
  （`Dispatchers.IO + SupervisorJob()`——null 时不传 scope，不镜像默认值）；测试注入
  受控 scope 后 DataStore 挂起读写随之受控。注入约束（违反即 DataStore 永久不可用）：
  scope 必须长命自带独立 `Job`（如 `SupervisorJob`）——DataStore 把 scope 取消视为
  终态（Final）信号；也不得复用 `viewModelScope` 等测试外生命周期 scope。测试侧注入
  `Dispatchers.Unconfined` 的配方与死锁边界见 [core/viewmodel.md](viewmodel.md)
  「DataStore 层测试配方」节
- 有旧文件名自动迁移：无扩展名 → 加 `.preferences_pb`；`legacyFileNames` 注入的历史名
  （原样名与归一化名皆查）在首读写时 rename 到目标名文件（生产 = `AppDataStore` 注入
  旧 DataStore 名 `easy_dev_toolbox` → `toolbelt.preferences_pb`，key 内容无损）
- 内置 `ReentrantReadWriteLock` 内存缓存，读优先命中缓存

```kotlin
internal suspend inline fun <reified T> getValue(key: String, defaultValue: T): T
internal suspend inline fun <reified T : Any> setValue(key: String, value: T)
suspend fun setValues(values: Map<String, Any>)
suspend fun clear()
```

### 密钥文件权限硬化（post-edit 钩子 + 启动期兜底扫描）

`preferences_pb` 是明文密钥的落盘处（`llm_api_key` / `xlog_private_key` 等），权限不变量：**写后即
rw-------(600)，不得敞到下次启动**。

- **post-edit 钩子**：`setValue` / `setValues` / `clear` 三条写路径在落盘后统一调
  `hardenSecretFile()` → `SecretFilePerms.harden600(targetFile())`。理由：DataStore 以临时文件 +
  原子替换落盘，新文件权限由进程 umask 决定，不经钩子则密钥文件按 umask 敞开。inline 写路径的
  chmod 调用经非 inline helper（`@PublishedApi internal`）承载
- **钩子漏斗外的写者**（原子替换的 `.tmp` 中间文件、corruptionHandler 清空重建、legacy rename、
  直呼 `dataStore().edit` 的旁路）由启动期 `SecretFilePermHardeningInitializer`
  （`:composeApp` `host/initializer/`，level 2 initializer）幂等重扫兜底：每次启动对
  `getDatastoreDir()` 下命中 `SecretFilePerms.isSecretPreferencesFile` 的文件全量重设 600，
  恒返回 true（权限硬化失败不阻断启动）。**钩子不替代扫描，扫描亦不替代钩子**
- **非 POSIX 文件系统（Windows）**整体跳过并 log 留痕——明文密钥按平台默认权限落盘属已知
  边界，不宣称已收口；判定与落盘助手单源在 `SecretFilePerms`
  （`PREFERENCES_MARKER = ".preferences_pb"` **包含**匹配——`.bak-forensic` 等后缀变体不以
  marker 结尾，`endsWith` 匹配必漏，谓词不得在各调用点私写）

---

## DataStoreExtensions

为 `DataStore<Preferences>` 提供泛型 get/set 扩展。

支持的类型：`Int`, `Double`, `String`, `Boolean`, `Float`, `Long`, `Set<String>`

类型通过 `reified T` + `when(value)` 自动映射到对应的 `Preferences.Key`，无需手动指定 key 类型。

### 调度器契约（零 hop）

本层扩展函数不得叠 `withContext(Dispatchers.IO)` 之类的真实调度器 hop：DataStore
内部读经 `readState`、写经 `writeActor` 恒派发到自身 scope（`LocalDataManager` 的
`datastoreScope` 注入缝可替换），再叠一层会形成第二个**不可注入**的调度器——测试
注入受控 scope 后仍有真实 IO 线程续体与测试调度器的竞态。本层剩余工作（key 解析、
`Preferences` map 取值）是纯内存操作，无阻塞面。
