# logcat 实时抓取页（:feature-logcat）

文件均位于 `:feature-logcat` 模块 `page/logcat/`（包名 `com.jiaoay.easy_dev_toolbox.page.logcat`）。
宿主挂载 = 设备观测空间页（dock 第六空间，Ctrl+6——`composeApp` `page/deviceobs/DeviceObsPage.kt`
聚合页，AgentCenter 先例同配方：宿主自有页承载页头与设备栏，本模块贡献 public
`LogcatPanel` 内容组件）。历史沿革：面板曾内嵌于 Shell 工具页，归属迁移后 Shell 页
不再持有 logcat 面（相关约定见 [shell-tools.md](shell-tools.md)）。

## 引擎与面板挂载

面板 = 设备观测空间页内 `LogcatPanel`（宿主页壳内 TonalCard，抓取中自动展开）；引擎实例经
`:feature-logcat` `LogcatRuntime.engine` App 级持有（脱离页面生命周期——切页不中断抓取，
退出序随 AppHost 退出序收口，`main.kt` 经 `AppHost.register(LogcatRuntime)` 挂入）。
设备/adb 路径检测态**全部消费 :core 设备域单源**（`DeviceHub.monitor` 的
`DeviceStateSource` 三流——设备状态栏与 5s 可见性门控轮询的检测链在 :core 设备域，
Shell 工具页与新观测页共享同一单源，切页不丢设备选择），本面板不自起检测链路。

### 引擎与执行队列的分界（LogcatCaptureEngine）

`LogcatCaptureEngine(scope, runner = StreamingShellLogcatRunner)`：StreamingShell
流式消费 + 命中行按行落盘 + 有界滚动窗。**不入串行执行队列**——队列是「单条命令
限时完成」语义，实时抓取是「用户停止按钮唯一终止」的长跑流，**无超时看门狗**；
内存有界口径两者一致：队列靠超时封顶 + 滚动窗，本引擎靠滚动窗 + 全量流按行落盘。
测试缝：`runner` 可注入 fake（单测不真跑 adb）。

状态机 `LogcatCaptureStatus`：`Idle → Running(outputFile) → Stopped(outputFile?, totalCount, message)`
——`start` 已在抓取中返回 false（不重入，两路流会互踩同一落盘句柄）；`stop` 请求
停止（幂等，无抓取在跑无副作用），终态由收尾协程异步落定；状态/窗口经
`status` / `window` StateFlow 发布。

### 三形态匹配器（LogcatLineMatcher / LogcatFilterSpec）

过滤规格 `LogcatFilterSpec(tagQuery, minLevel)`：

- `tagQuery` 空白/逗号分隔多词（中英文逗号均容），命中任一即可、忽略大小写；
  `minLevel` 为 null = 不过滤级别（`LogcatLevel` 枚举 ordinal 即严重度序 V<D<I<W<E<F）；
- 两条件并存按 AND 合并；**无任何条件恒命中**；
- 🔴 解析不出 tag/级别的行，在设有对应过滤时**不命中**（过滤语义 = 只留可确认命中的行，
  不可确认即丢弃）。

行解析支持三种主流 logcat 形态：

1. **threadtime**：`MM-DD hh:mm:ss.mmm pid tid L TAG: msg`——按空白切 token，
   第 5 列单字母级别、第 6 列 `:` 前为 tag；
2. **brief**：`L/TAG(pid): msg` 行首直起（正则 `^([VDIWEF])/([^(\s]*)`）；
3. **time**：日期时间前缀后（第 3 token 起）的 brief 头。

三种形态都不匹配的行返回 null（不可确认）。匹配器纯函数、线程安全。

### 滚动窗有界 + 恒全量落盘

- **滚动窗**：`ArrayDeque` 上限 `MAX_RING_LINES = Dimens.listBoundedMax`（5000，与执行队列
  `MAX_OUTPUT_ITEMS` 同档），溢出**恒丢最旧**并累加 `droppedCount`——只影响实时回看；
  面板在 `droppedCount > 0` 时于控制台上方呈现「已丢弃最早 N 行（仅影响回看，落盘完整）」
  提示行（清屏不清溢出账，清屏后照常呈现）；
- **恒全量落盘**：每条命中行整行 + `\n` 一次写入产物文件（StreamingShell 按 `\n` 分行
  只回调完整行，文件无半行残留）；写句柄在流终止后必须关闭——终态 `Stopped` 落定即已
  关闭；进程启动失败且零命中时清理空产物文件。`LogcatWindow.lines / totalCount /
  droppedCount` 三口径：`totalCount` 为已落盘总行数（全量流口径，不受滚动窗截断影响）；
- 行快照聚合：**首行直通 + 100ms 窗口批量 + 单批上限 1000**（`ShellOutputAggregator`
  同配方——单行单快照在 logcat 突发速率下重组成本不可控）；流终止时经 channel 排序
  冲刷（终态可见时全部命中行已计入），收集协程无响应有防御 drain 兜底不丢行。

### 清屏（clearDisplay，清显示不清落盘）

抓取中「清屏」钮只清滚动显示窗，双契约与过滤回溯互补（`改过滤 → 清屏 → 只看新行`
盯屏闭环）：**恒全量落盘**——落盘句柄、`totalCount` 与 matcher 零触碰，清屏前后落盘
文件内容与全量计数零变化；**只影响后续行**——清屏后到达的命中行照常追加显示与落盘。
防复活走 `ShellOutputAggregator.clearOutput` 同款世代号配方（显示世代号 + drain
channel，清屏时刻前已在途的行不复活进滚动窗、但照常计入 `totalCount`）；
`droppedCount` 口径 = 溢出累计，清屏不追加也不撤销。非 Running 态引擎拒绝（清屏钮
与停止钮同款运行态门控，非运行态不渲染）。

### 中途改过滤（updateMatcher 回溯语义）

抓取进行中过滤参数可改（tag 逐键输入/级别 chip 切换即时生效）：VM 重组 matcher 经
`engine.updateMatcher` 写锁下原子替换（行回调并发到达亦只见一个一致 matcher），**免停止
重抓丢已有流**。🔴 **回溯语义：只影响后续到达的行**——已进滚动窗与已落盘的行不回溯
重算（回溯重算需全量流驻留内存，违背滚动窗有界内存契约）；落盘口径不变：每条行按
**到达时刻**生效的 matcher 判定命中，命中行全量落盘。非运行态引擎拒绝换 matcher
（无副作用——过滤由下次 `start` 按当时参数定型）。过滤变更回执经 `StatusSignals`
提示「过滤已更新，只影响后续行」（回溯语义用户不可见，须显式告知；1.5s 去抖防逐键堆栈）。

### 标记（markAnchor，复现点锚定）

抓取中「标记」钮把复现瞬间屏上位置记成查看锚点，停止后终态「查看」带锚点直达——
免肉眼回找。契约（`LogcatCaptureEngine.markAnchor` / `LogcatAnchor`）：

- **锚点承载 = 行首时间戳原文**（`LogcatAnchor.timestamp`，threadtime 头
  `MM-dd HH:mm:ss.mmm` 与年份形态同纳）：与查看器初始定位的时间戳档同格式同口径
  （行首时间戳契约单源在 log-tools `LogTimestamp`，引擎 `ANCHOR_LINE_HEAD`
  同款正则）——锚点字符串在产物文件内必有同形行头，查看侧**原样透传**即直达，
  零格式转换；`clockText` 取时钟段（`hh:mm:ss`）供回显与回执；
- **锚定源 = 滚动窗回扫**：`bufferLock` 下自末行向前回扫首个形态命中行——
  标记语义 = 锚定用户正看到的位置（channel 在途行最多滞后 100ms 提交，
  精度口径 = 屏上所见）；brief 等无时间戳行**不承载**（行内容在查看器无定位档，
  记了无处直达），回扫自动跳过；
- **单锚点**：多次标记覆盖前标记（复现瞬间是单点事件，用户语义 =
  「记住我现在看到的位置」，无多锚点选择面）；
- **世代清零**：锚点跨 Stopped 终态保留（消费面就是终态「查看」），下次
  `start` 清零——上次抓取的锚点指向上次产物，跨入新产物会误导「查看」直达；
- **拒绝语义（宁拒不猜）**：非 Running 态引擎拒绝（标记钮与清屏钮同款运行态
  门控，非运行态不渲染）；窗内无可锚定行（清屏后未有新行到达等）回执人话
  拒绝「暂无可标记的带时间戳行」——可见按钮不得静默死点；
- **回执与回显**：标记成功经 `StatusSignals` 回执「已标记 HH:mm:ss，停止后点
  『查看』直达标记处」；有锚点时面板在控制台上方回显直达语义（无标记零呈现，
  现行为原样）；
- **消费面**：终态「查看」点击时取 `anchor.fullTimestamp` 现值（含年完整串——
  查看器时间戳跳转解析式匹配兼容裸月日/含年两形态；带定位打开后点查看器
  「深度分析」，该串原样透传 offer 第三参预填工作台问题时间点，与下述
  「送工作台分析」载荷同形态）透传 `LogViewerDialog` 的 `initialTimestamp`
  （未标记 null = 不定位）；`LogcatPanel` 直接挂载查看器，
  不经 `LogViewerRequest` 槽。终态「送工作台分析」同消费锚点：标记过则
  `LogcatAnchor.fullTimestamp`（标记年 + 行首时间戳原文派生的完整时间串，
  语义见引擎契约）经 `LogcatViewModel.sendToWorkbench` 以
  `DeepAnalyzeHook.offer` 第三参 `prefillTargetTime` 通道预填工作台问题
  时间点（工作台经归一面写入目标时间框，契约见
  [pages/log-workbench.md](log-workbench.md)「深链」节）；未标记（锚点
  null）送 null——工作台不触碰时间域，用户手填值原样保留。

### 过滤参数持久化（跨启动记忆，只记参数不记行为）

过滤参数（tag 过滤串与最低级别档）经本模块 `LogcatConfigStore` 两 key 持久化
（`feature-logcat/.../logcat/LogcatConfigStore.kt`；key 字面量与 DataStore 文件随
归属迁移不变——存量用户零迁移）：

- **两 key**：`logcat_tag_query`（tag 过滤串；空 = 不过滤 tag）与
  `logcat_min_level`（最低级别档；落盘形态 = `LogcatLevel` 枚举的 name）——
  key 字面量与单一 DataStore 文件随归属迁移保持不变（`AppDataStore` 进程唯一实例，
  `DomainConfigStore` 共享骨架），存量用户过滤参数**跨启动记忆零迁移**；
- **null ↔ 空串 ↔ 枚举 name 三闭环还原**：写侧 `minLevel?.name ?: ""`
  （null 落空串）；读侧 `restoreMinLevel` 按 name 匹配枚举，空串（= null，
  无级别过滤）与非法 name 均回落 null——持久层损坏不炸恢复链路，宁丢过滤
  条件不丢抓取；
- **init 恢复 + 变更 debounce 落盘**：VM init 恢复上次值；tag 串与级别 name
  汇聚 Pair 走单一 debounce（400ms）通道**一次写两 key**（tag 逐键输入不逐键
  重写 preferences_pb）；🔴 `drop(1)` 弃初始重放值——启动期不发写，存量持久值
  只被 init 恢复读消费、不被空默认覆盖；
- 🔴 **只记忆参数不恢复行为**：抓取开关态/运行态不持久化，重启后恒 `Idle`
  （不会开机自抓）；抓取中改过滤的即时生效/回溯语义见上节，与持久化面正交。

### 启动前置与产物

- **前置检查**（`precheckHint`，纯同步读既有检测态，不静默）：检测失败（adb 路径缺失/
  无效）→ 提示 + 去设置入口；无设备 → 提示；多设备未选定 → 提示 + 选择设备入口
  （抓取必须钉死目标设备——不选定会让 `adb logcat` 在多台设备上报错）；
- 命令组装：每次启动重读 adb 路径（设置页变更后回页即生效）+ `AdbCommandRewriter.rewrite`
  重写 `adb logcat`（注入自定义 adb 路径与 `-s <serial>`）；
- 产物：`~/Documents/Toolbelt/logcat/logcat-<yyyyMMdd-HHmmss>.txt`（同秒冲突 `-N` 后缀避让）——
  用户主目录下可见的工具产物目录（非 `~/.toolbelt` 内部数据目录：产物要可见
  可拖拽进下游工具）；
- 终态衔接钮：**打开所在文件夹**（`DesktopUtils.openInFolder`）、**应用内查看**
  （产物路径进 `LogViewerDialog`；抓取中点过「标记」则带锚点时间戳直达标记处，
  锚点契约见上文「标记」节）、**送往 Tag 过滤**（经 `TaskPipelineHandoff`
  单张表投递进命令面板参数直填态——产物即过滤页输入类型）、**送工作台分析**
  （`DeepAnalyzeHook` 深链直投；标记过则 `fullTimestamp` 派生完整时间串经第三参
  `prefillTargetTime` 预填工作台问题时间点，锚点语义见上文「标记」节；
  送往 Tag 过滤与送工作台宿主未接线时按钮缺席）；
  **送飞书**（终态产物文件消息直发桥接接收会话，经 `:core` 中立槽 `ReportDelivery`
  ——报告面板/统计面板/日志对比同槽，本面板是第四消费面；形态与前两者不同：
  **置灰不缺席**，桥接未运行时按钮恒在场、置灰并 tooltip 明示原因，用户可见
  该动线存在）。送飞书契约：
  - **置灰单源**（`logcatLarkSendDisabledReason` 纯函数，`LogcatPanel.kt`）：
    null = 可发送、非 null = 置灰原因（禁用态 tooltip 直接消费，禁静默置灰）；就绪态
    真源 = `ReportDelivery.ready`（未接线/未运行恒 false）。终态钮行仅 Stopped +
    产物存在时组合，置灰判据只剩桥接运行态一闸；禁用态按钮不发 Hover 事件，tooltip
    经 `HoverTooltipBox` 外层 hoverable 挂接；
  - 🔴 **`ReportDelivery.deliver` 的 null = 已送出成功语义**：null 表示送出成功、
    非 null 才是失败人话——消费侧不可写 `deliver(file) ?: 失败文案` 的 elvis 兜底
    （会把成功吞成失败），须先收返回值再判空分叉回执（回执走 `StatusSignals` toast）；
  - **产物语义**：仅送 `Stopped` 终态产物（句柄已关、文件完整）；Running 中途
    不送，Stopped 且产物为 null（零命中产物已回收）或非 Stopped 态静默 no-op
    （VM 守卫兜底，UI 态已不可达）。
