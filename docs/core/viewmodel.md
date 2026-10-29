# AppViewModel

文件：`core/AppViewModel.kt`（完整路径 `composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/AppViewModel.kt`）

全局单例 ViewModel，通过 `AppViewModel.getViewModel()` 在任意 Composable 中获取同一实例。

---

## 状态（StateFlow）

| 字段 | 类型 | 初始值 | 说明 |
|------|------|--------|------|
| `colorScheme` | `StateFlow<AppThemeSpec>` | `AppThemeSpec.Default` | 当前主题（预设 seed 或自定义种子色） |
| `themeMode` | `StateFlow<ThemeMode>` | `ThemeMode.System` | 外观模式（跟随系统/浅色/深色） |
| `currentPage` | `StateFlow<ToolPage>` | `ToolPage.LogWorkbench` | 主区内容 key（S2 起语义收窄——工作台家为启动落点；Default 欢迎页已删） |
| `currentSpaceId` | `StateFlow<String?>` | `null` | 当前调查空间选中态（S4 升格——SpaceBar select 回写；深链泛化/FileBuffer→空间写入的「当前调查」判定源） |
| `shellType` | `StateFlow<ShellType>` | 平台默认值 | 当前 Shell 类型（同时写入 `ShellCommandBuilder.shellType`） |
| `debugPageEnabled` | `StateFlow<Boolean>` | `false` | dock 系统区是否显示 Debug 入口 |

---

## 初始化（init）

在 `viewModelScope.launch` 中异步执行，从 `EasyDataManager` 恢复所有持久化状态：

```
1. getColorSchemeName() + getCustomSeedColor() → _colorScheme.value（AppThemeSpec.fromId 恢复）
2. getThemeModeName()     → _themeMode.value     （loadEnum 恢复）
3. getLastPageName()      → _currentPage.value   （loadEnum 恢复——存量旧枚举名兜底落工作台家；
   last_page="AppCenter"（页已退役，反馈 6）显式改道 LogWorkbench）
4. getDebugPageEnabled()  → _debugPageEnabled.value
5. getShellTypeName()     → _shellType.value + ShellCommandBuilder.shellType（loadEnum 恢复）
6. usageTracker.load()    （page_use_counts 计数回填）
```

枚举解析失败时回退默认值（`core/data/EnumRestore.kt` 的 `loadEnum`——🔴 禁
`ToolPage.valueOf` 裸调；palette 空态"常用入口"计数解码同走 `runCatching valueOf`
非致命路径）。另：init 顶层 wire `DeepAnalyzeHook`（产物→工作台深链 + 切页；
S4 起补 spaceId 进 offer）；init 顶层订阅 `ToolUsageSignal.signals`
（反馈 4 计数口径：核心动作完成/发起才计数——loadEnum 映射 pageId，未知 id
忽略 + JLog；tracker +1 后 `EasyDataManager.setUsageCounts` 独立落盘）。

---

## 方法

```kotlin
fun setColorScheme(scheme: AppThemeSpec)
// 更新 _colorScheme；预设持久化 id，Custom 额外持久化种子色 ARGB

fun setCustomColorScheme(seed: Color)
// 色轮挑选/图片提取共用入口：setColorScheme(AppThemeSpec.Custom(seed))

fun setThemeMode(mode: ThemeMode)
// 更新 _themeMode，异步持久化 modeName

fun setCurrentPage(page: ToolPage)
// 主区内容 key 切换；更新 _currentPage，
// 异步持久化 pageName（🔴 反馈 4 起：切页只写 last_page，不再计数——计数由
// ToolUsageSignal 信号流订阅驱动，见 init）
// 🔴 SYS-15（重设计批 6）：backFromDebug()/previousPage 已随调试页返回钮退役——
// dock/命令面板切走即离开，无返回链（本表不再有条目）

fun setCurrentSpace(id: String?)
// 空间选中回写（SpaceBar select 调用——S4：App 级 currentSpaceId 真源）

fun setShellType(type: ShellType)
// 更新 _shellType，同步写入 ShellCommandBuilder.shellType，异步持久化 typeName

fun setDebugPageEnabled(enabled: Boolean)
// 更新 _debugPageEnabled，异步持久化
```

---

## 获取方式

```kotlin
// 在任意 Composable 内
val appViewModel = AppViewModel.getViewModel()

// 在 Composable 外（ViewModel 构造时注入）
// 不推荐，各功能 ViewModel 不应直接持有 AppViewModel 引用
// 如需读取 shellType 等，通过 ShellCommandBuilder.shellType 直接取
```

---

## 飞书桥接页 ViewModel 的状态管理范式

文件：`feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LarkBridgeViewModel.kt`

### 实际范式：多 StateFlow 分域暴露

`LarkBridgeViewModel` 采用**多个独立 `MutableStateFlow` 按职责域分别暴露**的模式（非单一不可变状态 + lens 切片）：

| StateFlow | 类型 | 职责 |
|-----------|------|------|
| `draft` | `StateFlow<BridgeConfig>` | 内存中的配置草稿（编辑器唯一数据源） |
| `issues` | `StateFlow<Map<String, String>>` | 字段级校验错误（`fieldId → message`） |
| `applyResult` | `StateFlow<ConfigApplyResult?>` | 最近一次落盘的分流结果（HOT/RESTART） |
| `startResult` | `StateFlow<BridgeStartResult?>` | 最近一次启动结果 |
| `busy` | `StateFlow<Boolean>` | 启动/停止在途标志（禁用操作按钮） |
| `banner` | `StateFlow<String?>` | 一次性操作反馈文案（用户可清除） |
| `probe` | `StateFlow<EnvironmentProbe?>` | 环境预检结果 |
| `registerState` | `StateFlow<RegisterAppState>` | 扫码建应用流程状态（sealed 五态：Idle / Waiting / QrShowing(qrUrl, expireInSec) / Success(appId) / Failed(message)；`RegisterAppDialog` 消费，Success 后凭据已自动回填 draft） |

### 写入路径（核心纪律）

```
updateField/updateConfig → _draft 即时更新 → 400ms trailing debounce → drop(1)
  → Mutex 单写者 → 校验通过才 store.applyConfig(persist=true) 落盘
  → 校验失败不覆写文件（UI 顶部红条 + 字段级 isError）
```

- **draft 即时更新**：每次编辑立刻改 `_draft` 并重算 `_issues`，UI 零延迟回显
- **400ms trailing debounce + drop(1)**：高频键击合并为一次落盘；`drop(1)` 确保仅打开页面不触发落盘（不生成 `bridge-config.json`）
- **Mutex 单写者**：`writeMutex` 串行化落盘，杜绝并发写覆盖
- **D8 短路**：`commit(next)` 若 `next == _draft.value` 则零副作用（不触发 debounce）

### 挂起纪律

`onStart` / `onStop` / `persist` 内的 store 调用都是 `suspend`（含网关建连/策略热更新的秒级网络调用），一律在 `viewModelScope.launch` 内调用。**本文件零 `runBlocking`**。

---

## VM IO 调度器注入（测试接缝）

功能 VM 内的阻塞 IO 段（SQLite/文件写）不直引 `Dispatchers.IO`——构造注入 `ioDispatcher: CoroutineDispatcher = Dispatchers.IO`（默认参数，宿主装配点零改动）；测试注入与 Main 共享 TestScheduler 的 `StandardTestDispatcher` 后，VM 级 IO 续体与 Main 队列同源确定性推进，teardown 无需 `Thread.sleep` 竞态宽限。`WorkbenchViewModel` / `DistillViewModel` 均此配方；`:core` 内部自带 `withContext(IO)` 的调用（如 `DistillIngest.ingest`）不在缝内——测试用轮询推进配方承接。

### DataStore 层测试配方（🔴 与 VM 级结论相反）

VM 级接缝（上文）注入与 Main 共享 TestScheduler 的 `StandardTestDispatcher`；**DataStore 层不得照搬**。`LocalDataManager` 的 `datastoreScope` 注入缝必须注入 Unconfined scope（`CoroutineScope(Dispatchers.Unconfined + SupervisorJob())`，构造参数契约见 [core/data.md](data.md)「LocalDataManager」节）：文件 IO 在调用线程同步落定，drain 即全静默，无真实 IO 线程续体与 `Dispatchers.resetMain` 的竞态。

🔴 若给 DataStore 层注入与 Main 共享 TestScheduler 的 scope，`runBlocking` 直读谓词（`store.getValue` 等非 launch 语境断言）会**死锁**——DataStore 挂起读写经 `withContext` 跳到注入 scope，no-sleep 直读语境里该共享调度器无人推进，协程永悬。

锚配方：`LogTagFilterViewModelTest` / `AiSettingsViewModelTest`（`datastoreScope` 字段 KDoc 纪律段 + 注入点 + tearDown「先排空在途协程再 resetMain」次序）。

### 与 AppViewModel 的关系

`LarkBridgeViewModel` 是独立的功能 ViewModel，通过 `viewModel { LarkBridgeViewModel() }` 创建，**不共享 AppViewModel 状态**。桥接服务生命周期独立于页面（`LarkBridgeManager` 是 App 级单例门面；🔴 窗口级 ViewModelStore 驻留——切页 VM 不销毁、关窗才 clear，docs/architecture.md「内存取证管线」节）。
