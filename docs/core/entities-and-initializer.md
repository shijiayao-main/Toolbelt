# 结果实体与启动初始化域（JResult / JErrorCode / JInitializerManager）

> 源码分布（均在 `:core`）：
> - **结果实体** `entity/`——`JResult`（通用结果 sealed 族）、`JErrorCode`（错误码枚举，全部结果失败的错误语义单源）
> - **初始化域** `core/initializer/`——`JInitializer`（初始化器接口）、`JInitializerManager`（调度器 object）、`InitResult`（启动三态）
>
> 交叉锚：`JResult` 的 UI 渲染面（StatusView 四态分发）见
> [ui-style-guide.md](../ui-style-guide.md) 4.3 节；启动流程全貌（main → 窗口 → App）见
> [architecture.md](../architecture.md)「启动流程」节；Shell 工具页配置加载链见
> [pages/shell-tools.md](../pages/shell-tools.md)；桥接域错误码映射见
> [pages/lark-bridge.md](../pages/lark-bridge.md)。

---

## JResult（通用结果 sealed 族）

`entity/JResult.kt`。`sealed class JResult<out T>`，全仓通用「操作结果」载体——
`out T` 协变使 `Failed`/`Loading`/`Empty` 可作 `JResult<Nothing>` 赋给任意 `JResult<T>`。

| 分支 | 类型 | 契约 |
|------|------|------|
| `Success<T>(data: T)` | data class | 成功；负载数据经 `data` 取用 |
| `Failed(code: JErrorCode, msg: String? = null)` | data class | 失败；`code` 必填（错误语义单源 = `JErrorCode`），`msg` 可空——消费方展示文案恒走 `msg ?: code.desc`（如 StatusView 的 `FailedStatusView`） |
| `Loading` | class | **现网不可达**：全仓零构造点，保留作 StatusView 的加载态规格分支（`null` 同样渲染为加载态） |
| `Empty` | class | **现网不可达**：同上，保留作 StatusView 的空态规格分支 |

companion 工厂：`JResult.success(data)`——`Success` 的便捷构造（`Failed` 无工厂，现场构造）。

### 消费面现状

- **feature-shelltools 链**是主要生产/消费方：`ShellToolsConfigLoader` 产
  `Failed(ShellConfigNotFound / ShellConfigParseFailed / ShellConfigEmpty)` 与 `Success(nodes)`；
  `CustomToolsController` 变换 `Success` 负载；`ShellToolsConfigRepository` / ViewModel /
  两个编辑对话框按分支分发。
- **feature-larkbridge**：`TaskErrorHint` / `BridgeStatusPanel` 消费 `JErrorCode`
  （经 `BridgeErrorMapping` 从 lib 侧映射，见下节）。
- **:ui StatusView** 是唯一按四分支（含 Loading/Empty）分发的渲染组件。
- 新代码选型：需要「可失败操作的统一返回形态」时用 `JResult`；
  需要向用户解释失败原因时**必须**给 `JErrorCode` 现有码或先扩枚举，不要裸抛字符串。

---

## JErrorCode（错误码枚举）

`entity/JErrorCode.kt`。`enum class JErrorCode(val code: Int, val desc: String)`——
`desc` 是面向用户的中文一句话语义，`Failed(msg = null)` 时即为展示文案。

### 分段规划

- `0`：成功哨兵值（`JResult.Success` 不携带 code，此值供数值侧对照/接口回显用）。
- `1000–1999`：Shell 工具域。
- `2000–2099`：飞书桥接域——**与 lib 侧 `:lark-bridge` 的 `BridgeErrorCode` 同构但独立**
  （lib 是 jvmTarget 11 纯 JVM，不得依赖 `:core`）；feature 侧经
  `BridgeErrorMapping.toJErrorCode()` 做映射，两枚举 16 值严格 1:1（code 数值、desc 文本、
  声明顺序逐项一致，仅名称差 `Lark` 前缀）——**新增桥接错误码须双侧同批同步**。

### 全量清单（与源码现值逐项一致）

| 枚举名 | code | desc | 域 |
|--------|------|------|----|
| `Success` | 0 | 成功 | 哨兵 |
| `ShellConfigNotFound` | 1000 | 没有找到配置文件 | Shell 配置 |
| `ShellConfigParseFailed` | 1001 | 配置文件解析失败 | Shell 配置 |
| `ShellConfigEmpty` | 1002 | 配置文件为空 | Shell 配置 |
| `LarkConfigNotFound` | 2000 | 配置文件不存在 | 桥接·配置（2000–2009） |
| `LarkConfigParseFailed` | 2001 | 配置文件解析失败 | 桥接·配置 |
| `LarkConfigInvalid` | 2002 | 配置校验失败 | 桥接·配置 |
| `LarkConfigWriteFailed` | 2003 | 配置写入失败 | 桥接·配置 |
| `LarkConnectFailed` | 2010 | 连接失败 | 桥接·连接（2010–2019） |
| `LarkBlockedByPeerInstance` | 2011 | 同 App ID 已有实例运行 | 桥接·连接 |
| `LarkRateLimited` | 2012 | 被飞书频控 | 桥接·连接 |
| `LarkAgentUnavailable` | 2020 | Agent 后端不可用 | 桥接·Agent（2020–2029） |
| `LarkAgentTimeout` | 2021 | Agent 执行超时 | 桥接·Agent |
| `LarkWorkspaceDenied` | 2022 | 工作目录越界 | 桥接·Agent |
| `LarkConfirmUnavailable` | 2023 | 确认链路不可用 | 桥接·Agent |
| `LarkDownloadFailed` | 2030 | 文件下载失败 | 桥接·文件（2030–2039） |
| `LarkExtractUnsafeEntry` | 2031 | 解包路径不安全 | 桥接·文件 |
| `LarkSendFailed` | 2040 | 消息发送失败 | 桥接·出站（2040–2049） |
| `LarkCardStreamFailed` | 2041 | 卡片流式更新失败 | 桥接·出站 |
| `LarkRegisterAppFailed` | 2050 | 扫码建应用失败 | 桥接·扫码建应用（2050–2059） |

---

## 启动初始化域（core/initializer/）

### JInitializer（初始化器接口）

`core/initializer/JInitializer.kt`：

| 成员 | 契约 |
|------|------|
| `isHighLevel: Boolean` | **分组开关而非优先级**——决定走高优组（并发、失败阻断）还是低优组（串行、失败不阻断）；组内执行序由 `level()` 决定 |
| `suspend init(): Boolean` | 执行体；返回 `false` 或抛异常都计为失败。高优失败会阻断整个启动流程 |
| `level(): Int` | 同组内执行序，`@IntRange(from = 1, to = 10)`，默认 7；数字越小越早 |

### JInitializerManager（调度器 object）

**注册方式**：

- 公开注册入口 `registerInitializer(initializer)`；宿主侧统一经
  `AppHost.registerInitializers(list)` 批量转调（单点装配，`core/runtime/AppHost.kt`）。
- 🔴 **注册必须发生在 `startInit()` 之前**（`main()` 顶层同步调用，保序契约）——
  `startInit` 取的是注册完成后的列表快照。
- 默认列表 = `:core` 自带的 `JLogInitializer`（日志引导，`isHighLevel = true`、`level = 1`）
  **加上**宿主注册项；`:core` 不依赖宿主配置，宿主域恢复类初始化器由宿主自己注册。
- 测试缝（internal，生产不得重新赋值）：`initializersProvider`（列表注入）与
  `ioDispatcher`（注入 test dispatcher 用虚拟时间）；`resetForTest()` 还原全部注入与幂等标记。

**执行模型**（`startInit()`，整体跑在 `ioDispatcher`，默认 `Dispatchers.IO`）：

1. 按 `isHighLevel` 拆高优/低优两组，各自按 `level()` 分桶（1..10）。
2. **高优组**：同 level 内**并发**（`async`）执行；任一失败（返回 false 或抛异常）即整组
   中止、**低优组不再执行**——高优失败 = 环境不可用（如日志引导失败）。异常被捕获转为
   失败结果，不在组合期直接崩溃。level 间串行（按桶序）。
3. **低优组**：按 level 串行、同 level 内也串行（`forEach` 逐个）；**相邻有任务的 level
   之间等 1 秒**（分级错峰；空 level 直接跳过，不产生空转延迟）；单个失败只记入
   `failedList`，不中断其他。
4. 幂等：`isInit`（AtomicBoolean）保证 `startInit` 只执行一次，二次调用直接返回。

### 启动耗时观测面（InitializerTiming / initTimingsFlow）

纯观测面，不参与成败语义（`InitResult` sealed 形状不动，消费面零涟漪）：

- `InitializerTiming(initializerClass, isHighLevel, level, isSuccess, durationNanos)`——
  单初始化器耗时记录；`durationNanos` 只度量 `init()` 本体（`System.nanoTime` 口径），
  低优相邻级别间的 1 秒错峰 delay **不计入**（错峰是调度开销非初始化器开销；
  组级总耗时 = 各记录求和）。
- `JInitializerManager.initTimingsFlow: MutableStateFlow<List<InitializerTiming>>`——
  按组聚合发射，恒先于同阶段 `initStateFlow` 写入：高优组结束后首次发射（此时低优组
  尚未执行）；低优组结束后终态发射全量记录。
- 高优组失败短路时**仍发射**（失败排障更需要耗时证据），但仅含已被 await 的记录，
  被短路时仍在跑的并发兄弟项不补记；低优失败项同样记录（`isSuccess = false`）。
- 每组完成时经 `publishTimings` 逐条打 `JLog.d`（TAG `AppInitializerManager`，
  `init timing [high|low] ... took X ms`）——日志即现行可读露出面，
  设置页 UI 露出面属另立项。`resetForTest()` 同步清空该流。

### InitResult（启动三态）与发射时序

`core/initializer/InitResult.kt`。`sealed class InitResult(val isHighLevel: Boolean)`：

| 状态 | 载荷 | 语义 |
|------|------|------|
| `Success(isHighLevel)` | 无 | 该组全部成功 |
| `Failed(isHighLevel, failedList: List<Class<*>>)` | 失败初始化器的 `javaClass` 列表 | 该组至少一个失败；UI 直接展示类名定位肇事者 |

**`initStateFlow: MutableStateFlow<InitResult?>` 发射时序**（初始 `null`）：

```
null                          → App 呈 Loading 态
高优组成功  → Success(true)   → App 挂载 PageHome（此时低优组可能仍在跑）
低优组完成  → Success(false)  终态：全部成功
             或 Failed(false, failedList)   终态：低优失败（应用不因此退出）
高优组失败  → Failed(true, failedList)      → App 呈 InitFailedView（失败列表+退出按钮），
                                             低优组不执行
```

🔴 消费方注意：`Success` 是**分阶段发射**的——先 `Success(isHighLevel = true)`（高优完成，
PageHome 可挂载），再 `Success(false)` 或 `Failed(false)` 覆盖为终态。UI 按 `when` 分发
（`composeApp` `App.kt`）：`Failed` → `InitFailedView`；`Success` → `PageHome`；
`null` → Loading。**低优组失败不阻止应用进入主界面**，只体现在终态流值里。

顶层扩展 `InitResult?.highLevelIsSuccess()`（`InitResult.kt` 同文件）：
`null` → false；`Success` → true；`Failed(isHighLevel = true)` → false；
`Failed(isHighLevel = false)` → true（高优阶段已成功，低优失败不算高优失败）。
语义 = 「高优阶段是否成功」；生产源码暂无调用点，供按阶段细分的消费方使用。

### 现网初始化器注册表（composeApp `host/initializer/`）

| 初始化器 | isHighLevel | level | 职责 |
|----------|-------------|-------|------|
| `JLogInitializer`（:core `core/log/`） | true | 1 | 日志引导（:core 自带，非宿主注册） |
| `LogFileRestoreInitializer` | true | 2 | 按设置页开关恢复文件日志 appender；失败仅告警不阻断 |
| `AiToolsRestoreInitializer` | true | 2 | 读 `llm_ai_enabled` 回填 AI 工具总开关（首帧前完成，防隐藏项闪现；读盘异常 fail-closed 回落关闭） |
| `SecretFilePermHardeningInitializer` | true | 2 | 密钥文件 POSIX 600 权限兜底重扫（谓词单源 `SecretFilePerms`；恒返回 true，失败不阻断） |

现网四件全在高优组——「失败阻断」由各自 `init()` 内部**恒返回 true**（告警兜底）化解，
低优组执行模型当前零注册者（保留给未来「可延迟、可容忍失败」的启动任务）。
