# Shell 工具页

文件均位于 `page/shelltools/`。

---

## 文件总览

| 文件 | 职责 |
|------|------|
| `ShellToolsPage.kt` | Composable 入口：单页布局（工具列表 + 历史 + 底部输出面板 + 输入栏） |
| `:ui` `widget/device/DeviceStatusBar.kt` | 设备状态栏（**public 共享渲染件**——设备域升位 :core 后随升 :ui widget 层；本页与设备观测页共用同一件，不重写第二份三分渲染） |
| `:ui` `widget/device/DeviceSelectDialog.kt` | 多设备选择对话框（public 共享渲染件；RadioButton 单选列表，>3 台降级路径） |
| `ShellToolsViewModel.kt` | 页面聚合器：设备检测、搜索、面板偏好，执行引擎转发 `ShellToolsRuntime`；历史/自定义委托给子控制器 |
| `ShellToolsModels.kt` | 页面状态模型（`DeviceState` / `ShellHistorySortMode`；Exec 系列模型已下沉 core） |
| `list/ShellToolsList.kt` | 工具树 LazyColumn（搜索无命中空态 `EmptyStateView` 三要素 +「清空搜索」直达——XA-04 · 批 10；contentType 分类型复用组合缓存槽） |
| `list/node/ShellToolsChildNode.kt` | 四种子节点渲染（Normal/MultiSelect/Input/Chain）+ 自定义节点操作入口（分享/移动/编辑/删除，删除经 `AppConfirmDialog(isDangerous=true, confirmLabel="删除")` 确认） |
| `list/node/ShellToolsParentNode.kt` | 父节点分组卡片（自定义组带 新增到本组/分享全部/重命名/删除，删除经 `AppConfirmDialog(isDangerous=true, confirmLabel="删除")` 确认并说明组内工具迁移去向；展开/收起 fadeIn(fast)+expandVertically(standard) 过渡 + 箭头 fast 旋转） |
| `list/custom/CustomToolsController.kt` | 自定义命令控制器：节点树状态 + 展开记忆 + CRUD 转发 Repository（进程级共享单例） |
| `list/custom/ShellCustomEditorDialog.kt` | 自定义命令编辑器（四类型表单 + 目标分组选择） |
| `list/custom/ShellToolsGroupDialogs.kt` | 分组命名（新建/重命名）/ 跨组移动 / 剪贴板导入对话框 |
| `history/ShellHistoryManager.kt` | 历史管理：状态/排序/去重合并/防抖持久化/删除与清空/退出 flush（从 VM 拆出） |
| `history/ShellHistoryPanel.kt` | 历史面板：排序切换（多选模式下禁用 + 置灰原因 tooltip，SH-07）+ useCount 徽标 + 多选批量执行（执行钮数量常显「串行执行选中项（N）」）+ hover 单条删除（直接删除不弹确认）+ 空态 `EmptyStateView`（"暂无历史记录"）+ 清空全部（`AppConfirmDialog(isDangerous=true, confirmLabel="清空")` 二次确认） |
| `history/ShellHistoryItem.kt` | 历史条目模型（@Serializable 多态，`type` 鉴别器与旧数据兼容） |
| `history/ShellHistorySerializer.kt` | kotlinx.serialization 多态序列化封装 |
| `output/ShellOutputPanel.kt` | **底部输出台**（SH-02 · 重设计批 10 终版 + R-6 整台恒深：树在上占主区、本台沉底——**整台**恒深底 consoleBg 五类行色（含空闲态，R-6 维护者裁定按稿面）+ hasOutput 门控（空闲紧裹仅头部/输入条、过滤栏 SH-09 同门控）；有输出时高度**随内容增长、封顶内滚**（短输出不占满封顶）；**默认收起**（进程级会话态 `ShellToolsRuntime.consoleExpanded`）：执行后输出台保持头部+输入条（不遮挡树区），输出照常流式落库、失败红行/计数在头部可见；折叠钮手动展开、展开后保持，分隔条随收起隐藏；弹性区/输入条经 ConsoleFlexBody 装配（固定件先测——输出涨到封顶由列表内滚吸收、输入条恒驻）；封顶约束含台内边距（heightIn 挂根 padding 外侧）；输出台封顶高度经 OutputHeightDivider 拖拽受控（分隔条 = 输出台上缘跟手：向下拖收窄；未手拖随可用高双向跟随、手拖后只收不放）；流式输出 + 停止/清空/重试 + 复制全部/导出 .txt/关键字过滤（SearchField）。🔴 S2 批曾误置树上方且保留 fillMaxSize——非 weight 子项吞满整列把工具树饿成 0 高，v2 复刻原版语序修复） |

core 层配套（物理位于 feature-shelltools 模块 `page/shelltools[/config]/`，包名 `com.jiaoay.easy_dev_toolbox.page.shelltools[.config]`；`AdbCommandRewriter` 仍留在 :core 真包 `core/shelltools/`；`StreamingShell` 等 shell 执行器仍在 :core `core/extensions/`）。工具树空列表空态 = Success 空列表前置 if → `EmptyStateView` 四要素（「暂无工具」+ 学习提示 + 「新增自定义命令」直达——SH-16 · 批 10；🔴 JResult.Empty 全仓零构造点现网不可达，不走 StatusView Empty 分支）：

| 文件 | 职责 |
|------|------|
| `page/shelltools/ShellToolsConfigRepository.kt` | 三源配置管道（默认资源 → 主配置 → 自定义）+ 自定义 CRUD 写路径（Mutex + 原子写 + 迁移） |
| `page/shelltools/ShellToolsRuntime.kt` | App 级单例：`CoroutineScope(SupervisorJob()+Default)` + 全局唯一执行引擎 |
| `page/shelltools/ShellExecutionEngine.kt` | 统一执行引擎：串行队列 + 流式输出 + 状态机 + 停止/重试（输出聚合管线由 `ShellOutputAggregator.kt` 协作者独占：bufferLock/世代号/收集协程单一属主，对外 API 经引擎转发签名不变——2026-10-11 B1 提取） |
| `page/shelltools/ShellExecModels.kt` | `ExecStatus` / `ExecOutputItem` / `ExecOutputType` |
| `page/shelltools/ShellToolsDataStore.kt` | Shell 域配置仓库（key 与旧 EasyDataManager 的 KEY_SHELL_* 一致，经 :core `AppDataStore` 进程唯一实例读写） |
| `page/shelltools/config/ShellToolsConfig.kt` | JSON 1:1 映射模型（`body: JsonElement?`，含可选 `id`） |
| `page/shelltools/config/ShellToolsConfigLoader.kt` | 加载并解析 shell_tools.json（NotFound/Empty/ParseFailed 错误码） |
| `page/shelltools/config/ShellToolsConfigParser.kt` | JSON ↔ ToolNode 双向转换（整型 type 手动路由 0/1/2/3，parse/encode 对称） |
| `page/shelltools/config/ShellToolsNode.kt` | UI 展示层数据模型 ToolNode / ChildBody（与 JSON 解耦） |
| `page/shelltools/config/DefaultShellToolsConfigProvider.kt` | 内置默认资源读取（composeResources，缓存 + 失败降级空列表） |
| `core/shelltools/AdbCommandRewriter.kt`（:core） | adb 命令重写器（纯函数）：注入自定义 adb 路径与 `-s <serial>`（VM 提交前统一应用） |
| `core/extensions/StreamingShell.kt`（:core） | 流式执行器：每条命令一个一次性进程，逐行回调输出；kill 升级链（destroy→3s→destroyForcibly，200ms 轮询收割，killRequested 前提） |
| `core/extensions/SimpleShell.kt`（:core） | 一次性执行（进程退出后一次性返回），设备刷新/debug 页使用 |
| `core/extensions/InteractiveShell.kt`（:core） | 持久交互会话（adb shell），debug 页使用 |

JSON 配置格式详见 [../shell_tools.md](../shell_tools.md)。

---

## ViewModel 分层

```
ShellToolsViewModel（聚合器）
 ├── ShellHistoryManager        历史列表/排序/防抖落盘/onCleared flush
 ├── CustomToolsController      自定义命令 CRUD/导入导出/节点树状态（转发 ShellToolsConfigRepository）
 └── ShellToolsRuntime.engine   App 级执行引擎（get() 转发，VM 不持有实例）
```

`onCleared`：`runBlocking { withTimeoutOrNull(2s) { historyManager.flush() } }` + `historyManager.close()`。
执行引擎挂 App 级 `ShellToolsRuntime`——**离开页面不停止命令、不清输出**（切回页面仍能看到刚才的输出），
`onCleared` 不再调用 `engine.stop()`，仅保留历史 flush。

---

## 页面结构

```
ShellToolsPage（单页，无路由切换）
├─ Row(weight 1f)
│   ├─ 左栏: TitleBar(+新增/新建分组/导入/打开配置目录/刷新) + 管理行（页头「更多」
│   │        内联展开——popup-free，SH-06「更多」钮带 tooltip） + 设备状态栏
│   │        （SH-05 · 批 10 胶囊化：Surface(capsule) + Single 型号直显 /
│   │        Multi ≤3 台状态栏内联 FilterChip 直选零对话框、>3 台降级对话框）
│   │        + 主配置错误banner(可关闭) + ShellToolsList
│   └─ 右栏: ShellHistoryPanel（最常使用/最近使用切换 + 多选批量执行）
├─ OutputHeightDivider（拖拽分隔条调节输出台封顶高度——SH-13 后形态；DragHandle
│   已随批删除；输出台高度会话级记忆进程级持有（ShellToolsRuntime.consoleCapHeight——
│   切页保持，重启随进程回默认 420dp）不持久化，DataStore 两 key 已退役；
│   初始封顶随可用高单向收缩——可用高不足时钳到「可用高 − 树硬底 − 间隙/分隔条/台内边距」；
│   边界（960×640 最小窗像素级实测）：该窗可用高 ≈385px < 空闲需求 491px，钳制下限
│   consoleHeightMin 240dp 反超可用高可容纳值，空闲态 Section 即降至 ~212px（低于 320dp
│   硬底）——钳制只能延缓不能豁免极端窗挤压；若需根治走候选 E（上调 main.kt 最小窗高
│   640→≥718px 使空闲态恰容硬底，涉 RAW_DP 白名单/theme.md 登记/architecture.md 同步——
│   待维护者裁定，未实施）
│   默认窗实得参数（1280×860，像素级实测）：内容列可用高 633px → 输出台封顶 253dp、
│   拖拽行程 [240dp, 253dp]（真死区阈值=可用高 620px）。行程 < splitterMinTravel(24dp)
│   时分隔条把手诚实化隐藏（死 affordance 不上屏），窗口放大行程恢复自动回归。未手动拖拽时
│   封顶随可用高双向跟随（放大恢复 [240dp, 420dp] 全程、缩小收缩）；拖拽后保留手调值
│   （只收不放，`ShellToolsRuntime.consoleCapUserAdjusted` 标记；零位移试拖不置位）。
│   树区在输出态恒保 320dp 硬底（封顶含台内边距，默认窗树区实得 344dp）。
├─ ShellOutputPanel（输出台沉底恒深底——SH-02 · 批 10 + R-6 整台恒深：整台恒
   consoleBg 明暗分治深底（亮 #1E1E1E / 暗 #141416，含空闲态——R-6 维护者裁定按稿面），
   行色走恒深值 token 五类行色表；空闲紧裹仅头部/输入条（过滤栏 SH-09 同门控），
   有输出时高度随内容增长、封顶内滚（短输出不占满封顶）；默认收起（执行后
   保持头部+输入条，折叠钮手动展开）；流式输出 + 停止/清空/重试 + 复制全部/导出 .txt/
   关键字过滤——过滤栏走 SearchField（DS-10 迁移），仅 hasOutput 渲染（SH-09）；
   全部行被过滤隐藏时列表区显式提示「关键字无匹配，全部 N 条已隐藏」（防误读为
   内容丢失——过滤词仅显示层，清空关键字即恢复）；输出行档走 DesktopTypography
   .monoConsole（稿 .ol line-height 1.7 收编正源）；手动输入条圆角 AppShapes.medium
   （稿 .cmdin r-md 档））
```

所有执行路径（列表节点 / 链式 / 手动输入 / 历史回放 / 批量）输出统一进入底部面板；
「静默执行」开关（showOutput）已整体退役——输出恒展示，历史条目/配置 JSON 中的旧
`showOutput` 键由解析器 `ignoreUnknownKeys` 忽略，旧数据直接兼容。输出台「收起」
是视口管理（输出照常流式落库、头部状态机/计数反馈恒驻），非静默执行回潮。

---

## 执行引擎（ShellExecutionEngine，page/shelltools/）

引擎实例经 `ShellToolsRuntime` object 单例持有（App 级 scope：`SupervisorJob() + Dispatchers.Default`），
执行状态脱离页面生命周期：切走再切回页面，输出面板内容仍在、执行中的命令不中断。
页面 ViewModel 仅做 `get() = ShellToolsRuntime.engine` 转发。

```kotlin
data class QueuedCommand(
    val label: String,               // 面板头部显示的任务名
    val commands: List<String>,      // 逐条串行执行
    val trackingKey: String? = null, // 执行期间写入 activeKeys，驱动节点 loading 态
    val timeoutMs: Long? = 60_000L,  // 数据类默认仅为兜底非生效值——VM 四路径显式 300s
                                     // （NODE_COMMAND_TIMEOUT_MS），手动输入 300s，链式 30s/步
    val failFast: Boolean = true,    // 某条非 0 退出即中止后续
)

class ShellExecutionEngine(scope: CoroutineScope) {
    val output: StateFlow<OutputState>     // 统一输出（滚动窗口上限 5000 条 + droppedCount 丢弃计数）
    val status: StateFlow<ExecStatus>      // Idle / Running / Failed / Completed
    val queuedCount: StateFlow<Int>        // 排队深度（头部「排队 N」徽标消费；stop 清零）
    val activeKeys: StateFlow<Set<String>> // 原 executingNodeIds 语义

    fun submit(task: QueuedCommand)   // 入队（Channel.UNLIMITED），单消费者串行执行
    fun stop()                        // kill 当前进程 + 丢弃队列（升级收割由 StreamingShell 完成）
    fun clearOutput()                 // 清空缓冲并递增世代号（旧窗口行不得复活）
    fun retryLastFailure()            // 已退役——重试走 VM.retryExecution（统一重写层按当前
                                      //   adb 路径/串号重写原串 + 超时/追踪键保真 + 失败步续跑）
    fun appendStatusLine(content: String) // 面板侧追加状态提示行（如导出失败），走统一聚合链路
}

data class ExecOutputItem(type, content, stepIndex, totalSteps, seq: Long) // seq 单调递增，作 LazyColumn 稳定 key
data class OutputState(items: List<ExecOutputItem>, droppedCount: Int)    // 快照发布，丢弃计数可见不静默截断
```

关键设计：
- **输出聚合（D1）**：`appendOutput` 向 `Channel<OutputEvent>(UNLIMITED)` 发射，收集协程按
  "首行直通（低延迟）+ 100ms 窗口收割 + MAX_BATCH=1000" 批量 commit 到 `ArrayDeque` 滚动窗口，
  再快照发布 `OutputState`；任务收尾（runTask 完成/失败）与 stop 后 `flushPending()` 立即冲刷
  （FlushSignal 哨兵 + ack），保证 Completed 状态可见时输出已完整。聚合管线自 2026-10-11 B1 起
  由 `ShellOutputAggregator`（`page/shelltools/ShellOutputAggregator.kt`）独立协作者持有——
  独占 bufferLock/世代号/收集协程，缓冲与聚合契约不变，对外仍经引擎 `output` 消费
- **滚动窗口（D2）**：`ArrayDeque<ExecOutputItem>` 上限 5000 条，溢出批量 removeFirst 并累加
  `droppedCount`；面板头部显示"N 条（已丢弃 M）"，M=0 时省略
- **世代号校验**：`clearOutput()` 递增 generation，旧批次的行提交时被世代过滤丢弃——清空后旧窗口行不得复活
- **kill 升级链（D5，StreamingShell）**：`kill()` 仅置位 `killRequested`（volatile）+ `destroy()`（SIGTERM）；
  收割循环 200ms 轮询——进程未退出时等待 `TERMINATION_GRACE_MS=3s` 后 `destroyForcibly()`（SIGKILL），
  再等 `FORCE_KILL_WAIT_MS=2s` 仍存活则放行 exitCode=-1 并输出"进程未响应强制终止"提示行。
  升级以 killRequested 为前提，正常命令不会被误杀；watchdog 超时只调一次 kill（升级由收割循环完成）
- **每条命令一个一次性进程**（`StreamingShell.executeStreaming`），经 `ShellCommandBuilder.wrap`
  （Linux/macOS `/bin/zsh -l -c`、Windows `cmd.exe /c`）以登录 shell 执行，整条命令作单个 `-c` 参数
- **完成信号 = 进程退出 + 真实 exitCode**（无 `__DONE__` marker）；失败立即判定，不再等 marker 超时
- **流式输出**：stdout/stderr 逐行回调，`adb logcat` 类长输出实时可见
- 单条命令超时由看门狗协程 kill；读流 EOF 有 1.5s 兜底超时（防派生继承 stdout 的子进程导致管道永不 EOF）

### Chain 语义变化（相对旧版）

旧版 chain 在 `adb shell` 会话内执行（设备侧），`["adb root", "adb disable-verity", "adb remount"]`
这类带 `adb` 前缀的配置实际会超时失败。**新版全部 host-side 执行**，这类配置正常工作；
设备侧多步需写成 `adb shell 'cmd1; cmd2'`。

### 输出面板操作（复制 / 导出 / 过滤）

- **复制/导出（过滤口径）**：头部 `CopyIconButton`（`widget/interactions` 共享组件）写入系统剪贴板，格式化为纯文本（Command 行带 `"$ "` 前缀，其余类型原文，逐行 join）；**复制与导出均按过滤口径**——过滤词非空时复制/导出的是可见行（「过滤出关心的行 → 带走的就是这些行」），计数行显「M/N 条 · 已过滤」，导出 toast 带条数；输出为空时禁用；列表文本可选中（`SelectionContainer` 拖选 + Ctrl+C）；成功后图标 ContentCopy ↔ Check 以 fast 档 Crossfade 反馈 1.5s（与列表节点分享、时间戳页复制共用 `CopyFeedbackState`）
- **导出 .txt**：`java.awt.FileDialog`（SAVE 模式）+ 默认文件名 `shell-output-yyyyMMdd-HHmmss.txt`，
  在 `Dispatchers.IO` 模态调用；取消静默返回，写失败经 `appendStatusLine` 在面板追加 Status 提示行；
  输出为空时禁用
- **关键字实时过滤**：过滤词进程级持有（`ShellToolsRuntime.outputFilterKeyword`，
  不进 DataStore）+ 派生过滤视图（忽略大小写、仅过滤显示不改底层数据）；
  清空输出时过滤词保留；自动滚动依赖过滤后列表 size，仅当 atBottom 时跟随
- **丢弃计数**：头部显示"N 条（已丢弃 M）"，M 为滚动窗口溢出累计（不静默截断）

---

## logcat 实时抓取（已迁出）

logcat 实时抓取面板（引擎 / VM / 面板 / 两过滤参数 key）已整体迁出本模块——
归属 `:feature-logcat`（dock 第六空间「设备观测」页，宿主聚合页挂载），
引擎契约 / 匹配器 / 标记锚点 / 持久化 key 详见
[logcat.md](logcat.md)。本页仅保留 `adb logcat` 类**长跑 shell 命令**的常规执行
（单条超时看门狗 + 滚动窗，见上文「执行引擎」节），与实时抓取面板无关联。

---

## 状态模型

### ExecStatus（page/shelltools/ShellExecModels.kt）

```kotlin
sealed interface ExecStatus {
    data object Idle : ExecStatus
    data class Running(label, stepIndex, totalSteps, currentCommand) : ExecStatus
    data class Failed(label, commands, failedStepIndex) : ExecStatus
    data object Completed : ExecStatus
}
```

### DeviceState / ExecOutputItem / ExecOutputType / OutputState

`DeviceState`（含 `DeviceInfo`）的真源在 :core 设备域
（`core/device/DeviceModels.kt`——设备检测/选定/adb 路径的生产逻辑同在 :core
`DeviceMonitor`，本页 VM 是页面作用域消费壳）；`ShellHistorySortMode` 留在
`page/shelltools/ShellToolsModels.kt`；
`ExecOutputItem`（含单调递增 `seq: Long`，引擎 AtomicLong 赋值）/ `ExecOutputType` /
`OutputState`（items + droppedCount 快照）同在 `page/shelltools/ShellExecModels.kt`
（ExecOutputType: Command/Stdout/Stderr/StepMarker/Status）。

### ShellHistorySortMode

```kotlin
enum class ShellHistorySortMode(val label: String) { MostUsed("最常使用"), Recent("最近使用") }
```

---

## 设备检测与多设备串号选择

### 设备检测（refreshDevices）

- **触发点（SH-04 起 2026-09-30）**：① VM 常驻 5s 自动轮询（`DEVICE_POLL_INTERVAL_MS`，
  构造即启动、窗口级驻留——VM 经页面作用域 `viewModel{}` 首次组合时构造、切页不销毁），
  🔴 **页面可见性门控**（`_pageVisible`，`setPageVisible` 由 ShellToolsPage 的
  `DisposableEffect` 接线：进入组合置 true、切页 `onDispose` 置 false）：不可见期间该拍
  **静默跳过**（无门控则 VM 窗口级驻留下每 5s 仍 spawn 登录 shell + adb 两个子进程），
  回页置 true 即恢复、下一拍照常轮询、无需补拍；默认 true——无页面接线的直构消费方
  （单测）保持既有轮询语义——插拔即更新、无需手动刷新。首拍先等一个间隔——进页即刷由页面承担，不双发 adb——② 进页
  `LaunchedEffect(Unit)` 即刷（PageHomeContent 即时切换分发，切页即销毁本组合、回页重新
  组合重跑）；③ 行尾手动刷新钮（即时反馈）。
  轮询无 busy/detecting 中间态，结果静默走 `applyDeviceList`/`applyDetectError`（幂等；
  与 XA-08 busy 态零互斥）
- 检测命令：`adb devices -l`（经 `AdbCommandRewriter.rewrite(command, adbPath, serial = null)` 仅做
  adbPath 路径替换，不注入 serial），`SimpleShell` 一次性执行
- **adbPath 缓存机制**：VM 在 `init` 与每次 `refreshDevices` 时从 DataStore 重读
  `adb_executable_path` 存入 VM 内 StateFlow 缓存（LocalDataManager 有内存缓存，重读开销可忽略；
  设置页变更后回到本页刷新即生效）；路径为空 = 使用系统 PATH 中的 `adb`
- **无效路径不静默回退**：自定义路径非空即按该路径执行，失败如实报错（检测失败置 `None` 并清除选择）
- 解析（`parseAdbDevices`，companion internal 供单测）：跳过标题行与 `*` daemon 提示行，
  仅保留状态 `device` 的在线设备（offline/unauthorized 过滤），从 `model:xxx` 提取型号（缺失回退空串）

### DeviceState 模型（含 DeviceInfo）

```kotlin
data class DeviceInfo(val serial: String, val model: String) {
    val displayLabel: String get() = model.ifEmpty { serial }  // 型号优先，串号兜底
}

sealed class DeviceState {
    data object None : DeviceState()
    data class Single(val serial: String, val model: String) : DeviceState()
    data class Multi(val devices: List<DeviceInfo>) : DeviceState() { val count get() = devices.size }
}
```

### 多设备选择流程（会话级，不持久化）

- 内联直选（SH-05 · 重设计批 10）：`Multi` 且 ≤3 台时状态栏内 FilterChip 直选——点选即定向，
  无对话框打断；已选定时 chips 常驻（选中 chip 高亮）+「清除选择」钮（终验 review 修复：
  原 `selectedSerial == null` 门令已选定后 chips 消失，直选动线对已选定态断裂）
- `DeviceSelectDialog`（`:ui` `widget/device/DeviceSelectDialog.kt` 共享渲染件；RadioButton 单选列表：型号为主标识 + 串号小字）= >3 台降级路径，
  仅经状态栏「选择设备 / 重新选择」入口手动打开（**无自动弹窗**——SH-05 内联直选接管后
  LaunchedEffect 自动弹出路径已删除）
- 选定后：状态栏显示选中 chip 高亮 +「清除选择」入口；
  未选定时显示黄色圆点 +「已连接 (N台)」+ chips 直选（>3 台为「选择设备」按钮）
- 选择维护：`refreshDevices` 后已选串号不在新设备列表、或退化为单设备/无设备时自动清除
  （回到未选定）；检测失败同样清除（状态未知不误注入）

### `-s` 串号注入（AdbCommandRewriter，design 决策 1）

选定设备后本页**所有提交路径**（列表节点普通/多选/输入/链式、手动输入、历史回放、批量执行）
经 VM 的 `rewriteCommands`（`buildQueuedCommand` 与两处直接构造 `QueuedCommand` 的提交点统一覆盖）
应用重写器；历史记录保存原始命令，回放时按当时选择注入。规则：

> 手动输入栏（`output/ShellOutputPanel.kt` 的 `ShellInputBar`）2026-09-30 SH-01 起
> 经 `VM.submitManualCommand` 提交——同链写历史 + `adb -s` 注入（修复前面板内联
> 直连 `engine.submit` 绕过重写器，选定设备对手动输入不生效）；接线由
> `ShellOutputPanelWiringGuardTest` 源码锚守护。

1. 命令 trim 后行首不是 `adb`（词边界：`adb` 后须空白或行尾）→ 原样返回（管道右侧 adb 不处理）
2. `adbPath` 非空 → 行首 `adb` 替换为自定义路径（路径含空白字符时单引号包裹）
3. `serial` 非空且 `adb` 后首参数不是 `-s`（用户显式指定优先）→ 插入 `-s <serial>`
4. 未选定（含单设备/无设备/已清除）→ 不注入，行为与现状一致

---

## 历史记录

- `ShellHistoryItem`（sealed: Normal/MultiSelect/Input/Chain）新增 `useCount: Int`（默认 1）、
  `lastUsedAt: Long`；`addHistory` 合并语义：同 id 已存在则 `useCount+1`、更新 `lastUsedAt`、移到队首
- 序列化：`ShellHistorySerializer` 反序列化时归一化（Gson 绕过构造器，旧数据缺字段得到 0 而非默认值）
- 排序：`displayHistory` 由 `combine(historyFlow, historySortMode)` 计算；
  MostUsed = useCount desc（tie-break lastUsedAt desc），Recent 反之
- 多选批量：面板进入多选模式后勾选多条 → 「串行执行选中项（N）」（数量常显，SH-07）
  按展示顺序展开为一个 QueuedCommand；**批量执行同样写入历史**——按展示顺序逐条
  `add(item)`（复用同 id 去重合并语义，useCount 累计、移至队首）
- 多选与排序互斥（SH-07 2026-09-30）：`executeSelectedHistory` 按展示序展开命令，
  多选中切排序会令执行序静默改变——排序段在多选模式下禁用（hover tooltip 说明
  「多选中切换排序将清空已选」），且 `setHistorySortMode` 兜底先清空勾选
  （纵深防御覆盖面板外的全部排序入口）；多选模式本身保持，退出多选或重新勾选即解锁
- 单条删除：非多选模式下卡片 hover 态行尾删除按钮渐显（`HoverableRow` fast 档，固定占位不改变布局；
  `enabled = isHovered` 保持"不可见即不可点"防误点），点击立即移除（不弹确认）；
  多选模式下隐藏删除入口
- 清空全部：面板标题行「清空全部」按钮（空历史禁用）→ `AppConfirmDialog` 二次确认（危险操作红色确认：`isDangerous = true`，确认键「清空」）
- 单条删除（P2 对齐批 2026-09-25）：hover 行尾删除钮 → `AppConfirmDialog` L2 确认——历史经 DataStore
  持久化，单条与清空同判据（旧版「单条直删、清空要确认」割裂消失）
- 上限 30 条，防抖 1s 持久化到 DataStore（key `shell_command_history`），onCleared 时 runBlocking 强制落盘

---

## 自定义命令与分组管理

全部数据读写经 `page/shelltools/ShellToolsConfigRepository`（三源管道唯一写路径，
schema 详见 [../shell_tools.md](../shell_tools.md)）。

### 分组

- **默认分组**「我的自定义」（id 固定 `custom`）：未分组工具的展示容器；可重命名（落盘提升为显式
  分组）、**不可删除**；删除其他分组时组内工具自动迁移至此
- **显式分组**（id `custom-group:` + UUID）：页头「更多」管理行「新建自定义分组」或分组头「+」创建
  （P1 动线批：标题管理四钮收进页头「更多」**内联展开行**——popup-free，M3 DropdownMenu 在
  PageScaffold 页内子组合锚定三连败后弃用（错位→裁剪→不开，tour6-8 实拍；页内 popup 系统性
  疑云登记 P2）；页头只留 刷新 + 更多 两钮）；
  重命名有同名占用提示；删除经 `AppConfirmDialog` 二次确认（危险操作红色确认，说明组内工具迁移去向）
- **跨组移动**：自定义工具节点行尾操作区（P1 动线批起 hover 渐显——HoverableRow 固定占位 +
  不可见即不可点，消解长列表常驻 chrome 过载）「移动」图标 → 分组列表（标注当前组）→ 点击目标即移动
  （工具 id 与数据不变，仅分组归属变化）
- 分组级 CRUD 后重新 fetch 配置；Controller 记忆 `expandedParentIds` 回填展开状态
- 展开/收起动效：分组卡 = `AnimatedVisibility` 的 `fadeIn(fast) + expandVertically(standard)` 过渡 + 展开箭头 fast 档旋转（SectionCard 共享件承载；点击分组属低频交互，不受流式输出零动画铁律约束）；链式节点步骤预览仍为 `animateContentSize(softSpring)`（行内展开）

### 四类型编辑器（ShellCustomEditorDialog）

- 类型 Segmented 四选一：**普通 / 多选 / 输入 / 链式**（对应 body.type 0/1/2/3，全部可创建可编辑）
- 编辑已有节点按 `ChildBody` 实际类型回填（多选回填选项列表、输入回填模板与参数）——
  导入的多选/输入工具可完整编辑，不再退化
- 多选选项动态行（名称/命令/描述增删）；输入参数动态行（参数名/描述增删，模板 `{参数名}` 占位）
- 保存前校验：名称必填；普通命令必填；多选 ≥1 有效选项（名称+命令）；输入模板必填且 ≥1 参数；
  链式 ≥1 非空行
- 新建模式可选目标分组（编辑模式保持原分组）；编辑按 id 原地替换（改名不重建）

### 导入 / 导出

- 分享：单节点 / 整组 / 全部 → JSON 复制到剪贴板（**剥离 id**，与既有分享物格式互通）
- 导入：管理行「从剪贴板导入」→ 粘贴 JSON（可一键从剪贴板填充）→ 复制语义生成新副本（一律新 id），
  兼容旧扁平片段与新分组结构（可混合）；解析失败在对话框内提示
- 旧 `shell_tools_custom.json` 首次读入自动迁移（补 id + 立即回写 + `.bak` 备份），详见配置文档

### 主配置错误提示

主配置读失败/空白/解析失败时回退内置默认配置，列表顶部展示可关闭的错误 banner
（`mainConfigError` StateFlow）；主配置不存在（新用户）静默使用默认配置。

---

## DataStore key（page/shelltools/ShellToolsDataStore.kt）

| Key | 类型 | 默认 | 说明 |
|-----|------|------|------|
| `shell_command_history` | String(JSON) | "" | 历史记录 |
| `shell_history_sort_mode` | String | "MostUsed" | 历史排序模式 |

> 🔴 SH-13（重设计批 10）死 key 清账：旧表中的 `shell_output_panel_height_dp` /
> `shell_output_panel_expanded` 两 key 已随三舱迁移 + 输出台形态上提退役
> （`ShellToolsDataStore` 现为上述 2 key）；`adb_executable_path` 的真源已升位
> :core 设备域 `DeviceConfigStore`（同 key 同 DataStore 文件零迁移，设备检测与
> 命令重写统一从设备域读取——本页与设备观测页共享）；logcat 两过滤参数 key
> （`logcat_tag_query` / `logcat_min_level`）随面板迁 `:feature-logcat` 的
> `LogcatConfigStore`（key 字面量与 DataStore 文件不变，存量零迁移——见
> [logcat.md](logcat.md)「过滤参数持久化」节）；
> 输出台高度/展开态/输出过滤词/管理行
> 展开 = 进程级会话态（`ShellToolsRuntime` 的 `consoleCapHeight`/`consoleExpanded`/
> `outputFilterKeyword`/`manageExpanded` StateFlow），均不持久化。

---

## ToolNode 树模型（page/shelltools/config/ShellToolsNode.kt）

UI 层只依赖此模型，不直接使用 JSON 映射模型。

```kotlin
sealed class ToolNode {
    abstract val id: String
    abstract val name: String

    data class Parent(
        override val id: String,
        override val name: String,
        val children: List<ToolNode>,
        val isExpanded: Boolean = false,
        val isVisible: Boolean = true,
        val isEditable: Boolean = false,      // 自定义分组为 true（主配置分组为 false）
        val isDefaultGroup: Boolean = false,  // 自定义默认分组（不可删除，迁移目标）
    ) : ToolNode()

    data class Child(
        override val id: String,
        override val name: String,
        val body: ChildBody,
        val isEditable: Boolean = false,      // 自定义节点驱动分享/移动/编辑/删除入口
        val isVisible: Boolean = true,        // 搜索过滤可见性（与 Parent.isVisible 对称，由 VM 搜索逻辑维护）
    ) : ToolNode()
}
```

### 搜索过滤（匹配范围扩展）

搜索框匹配范围覆盖**分组名、工具名与命令内容**（`bodyContains`，忽略大小写）：
Normal 匹配 value/desc、MultiSelect 匹配各 item 的 name/value/desc、Input 匹配模板与各参数的
name/desc、Chain 匹配任一命令。过滤规则：父名命中 → 显示全部子节点；父名不命中但有子节点命中 →
仅显示命中子节点；均不命中 → 分组隐藏。query 由空变非空时对全部 Parent 的 isExpanded 做快照，
清空搜索时回填恢复（搜索展开不破坏用户折叠状态）。

### ChildBody 类型

```kotlin
sealed class ChildBody {
    data class Normal(val value: String, val desc: String)
    data class MultiSelect(val items: List<MultiSelectItem>)
    data class Input(val value: String, val params: List<InputParam>)
    data class Chain(val commands: List<String>)
}
```

---

## JSON 映射模型（page/shelltools/config/ShellToolsConfig.kt）

与 `shell_tools.json` / `shell_tools_custom.json` 1:1 对应，由 `ShellToolsConfigParser` 解析后转换为
`ToolNode`，UI 不直接使用；`encodeChildBody` 与 `parseChildBody` 对称（整型 type 手动路由 0/1/2/3）：

```kotlin
data class ShellToolNodeConfig(type: Int, name: String, body: JsonElement?, id: String? = null)
data class NormalBody(type: Int, name: String, value: String, desc: String)
data class MultiSelectBody(type: Int, name: String, list: List<MultiSelectItem>)
data class InputBody(type: Int, name: String, value: String, list: List<InputParam>)
data class ChainBody(type: Int, name: String, list: List<String>)
```
