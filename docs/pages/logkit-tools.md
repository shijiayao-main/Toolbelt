# 日志工具四胞胎（:feature-logkit 三页 + 时间戳页）+ 日志对比页

> 「四胞胎」= 消费同一套任务卡骨架（`TaskPageTemplate` / `TaskListSection` / `TaskCard`，
> `:ui` `widget/task/`）的四个工具页：**XLog 解密 / Tag 过滤 / 文件分割**（`:feature-logkit`
> 三页）与**时间戳**（`:feature-timestamp`，同为「工具」聚合页子签但非文件任务形态）。
> 解压页（`:feature-archive`）也消费 TaskPageTemplate（无参数区变体），一并列尾。
> **日志对比页**（`LogDiffPageContent`，宿主 `composeApp/.../page/tools/logdiff/`）是
> 聚合页第 6 签——单发双输入形态，不属四胞胎任务卡骨架，独立成节见下。
>
> 宿主入口：「工具」聚合页（`page/tools/ToolsPage.kt`，dock 第 5 空间）页内六子签
> （XLog 解密 / Tag 过滤 / 文件分割 / 时间戳 / 解压 / 日志对比）；palette 深入模式
> （带参命令直执行 + openPage 深链）仍可达五件套原页（日志对比无深入模式候选——
> 可达面只有本页子签）。

文件（`feature-logkit` 模块根 `feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/`）：

| 文件 | 职责 |
|------|------|
| `logkit/LogKitContributions.kt` | 三页导航注册（navItem 已退役——palette + 工具页子签）+ palette 带参命令（ParamSpec：file_path/tag_regex/**输出标记**/size_mb） |
| `logkit/LogKitConfigStore.kt` | 日志工具域配置仓库（AppDataStore；key 与旧 EasyDataManager 一致零迁移） |
| `xlog/XLogDecoderPage.kt` | XLog 解密页（私钥框带显隐切换 + 未配置 Warning 引导） |
| `xlog/XLogDecoderViewModel.kt` | 私钥 500ms 防抖落盘 + 解码任务 |
| `logtag/LogTagFilterPage.kt` | Tag 过滤页（正则框 TL-04 即时校验 + 输出标记框） |
| `logtag/LogTagFilterViewModel.kt` | `regexValid` StateFlow + 整批预校验（Validation.Error 拒绝拖入） |
| `filesplit/FileSplitterPage.kt` | 文件分割页（`CommitNumberField` 分割大小） |
| `filesplit/FileSplitterViewModel.kt` | sizeMb 校验（≥1）+ 分割任务 |

时间戳页（`feature-timestamp` 模块根 `feature-timestamp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/timestamp/`）：

| 文件 | 职责 |
|------|------|
| `TimestampPage.kt` | 页面 + `TimestampPageContent()` 无头变体（工具页子签复用） |
| `TimestampViewModel.kt` | 双向转换 + 300ms debounce + recent chips（TL-12） |
| `TimestampConverter.kt` | 纯转换逻辑（TimestampFormat 枚举：LOG/标准/ISO-8601…） |
| `TimestampConfigStore.kt` | `timestamp_format` key（AppDataStore；恢复 runCatching valueOf 回退 LOG） |

---

## TaskPageTemplate（四胞胎同构骨架，:ui widget/task/）

参数化骨架——「参数区 + 拖放区 + 整批预校验错误条 + 任务列表」四段，差异全部参数化：

```kotlin
@Composable
fun TaskPageTemplate(
    title: String,
    viewModel: FileTaskViewModel,
    dropZone: FileDropZoneSpec,
    paramsSlot: (@Composable () -> Unit)? = null,   // XLog 私钥框 / LogTag 双框 / FileSplit 数字框 / Archive 无
    taskTitle: String = "任务",
    emptyHint: String? = "还没有任务",   // TL-05 · 批 11：模板级默认兜底防新页漏传；四签显式传页别文案；null = 不渲染空态
    onDeepAnalyze: ((String) -> Unit)? = null,
    onAiSummarize: ((TaskUi) -> Unit)? = null,
    embedded: Boolean = false,           // 无头形态：聚合页内嵌时消除双页头（AI 聚合批）
)
```

- **FileDropZone 两道回执**（2026-10-09 修复批）：`busy = hasRunningTasks` 时拖入/点击/
  Enter 三入口一律拒收（error 边框 + busyText「任务处理中，请等待完成或取消后再拖入」，
  自动消退——palette 等程序化提交路径不经本组件，靠 VM 侧单飞去重）；混批拖入部分命中
  时 StatusSignals 明示「已接收 N 个，跳过 M 个（类型不符）」（resolveFiles 返回
  (命中, 被滤数)，被滤项不再静默丢失）；
- **TaskCard 状态面**：Success 支持 `note` 降级标注（转写截断/分卷数等「成功但有例外」
  面向，产物路径下一行呈现）+ 产物路径 SelectionContainer + CopyIconButton（adb pull
  场景直接取路径）；Error 带 `folderPath`（输入文件所在目录——「打开文件夹」条目锚）；
  Cancelled 带 `partialOutput`（在盘部分产物如实回填）。

- **embedded 无头形态**：跳过 PageScaffold 直接渲染内容段——工具聚合页/智能体中心子签
  复用（聚合页自有页头）；VM 窗口级驻留两形态同实例；
- **inputError**：整批预校验失败 → `StatusBanner(Error)` + 行尾「知道了」（可手动关闭；
  再拖新批次自动复显）——不走裸 bodySmall；
- **覆盖确认卡**（重跑 affordance，2026-10-09 修复批）：拖入产物已在盘的文件不入队，
  VM 挂起 `overwriteRequest` 弹确认卡（覆盖重跑/取消二选；同批多冲突合并防意图覆写）——
  直接提交会被库层 exists 幂等硬拒成 Error 死端；「覆盖」经 `deleteStaleOutputsOf` 清旧
  产物后照常提交（XLog 页覆写为 no-op——原子替换写保「失败保旧产物」）；无冲突文件照常
  立即处理不被同批挂起项阻断；
- **TaskListSection**：任务头（标题+计数+清空钮）+ TaskCard LazyColumn。
  清空 = L1 撤销（`StatusSignals.showUndo` + `restoreTasks` 快照回填）；空态 = `EmptyStateView`
  四要素（`emptyHint` 页别文案 + learningHint 固定「拖入文件后自动开始处理，完成的产物会列在这里」）；
  LazyColumn `heightIn(max = consoleHeightMax)` 有界化（verticalScroll 父容器嵌套安全）；
  右键/「⋯」菜单 Error 态补「打开文件夹」「重试」条目（Error/Cancelled 从「无条目」改为
  Error 有出口、Cancelled 仍无）；
- **FileTaskViewModel**（abstract）：任务登记/状态机/openFolder/取消链/重跑 affordance 全收敛，
  子类实现 `process(file): ProcessResult` 并按需覆写四个基座钩子：
  - `outputTargetsOf`（产物目标集——单飞去重键）+ `hasRunningTasks`（FileDropZone busy
    门禁数据源）；提交按产物目标（含输入路径兜底）单飞去重，同目标在途拒绝并
    StatusSignals 明示「已跳过——同一产物的任务正在处理中」；
  - `existingOutputConflictsOf` / `deleteStaleOutputsOf`（覆盖确认卡检测/清理半边）；
  - `onTaskCancelled`（取消收口：返回在盘部分产物路径回填 `Cancelled.partialOutput`——
    卡面与磁盘终态一致，不谎报「未产生产物」）；
  - `ensureTaskActive`（协作式取消检查点——process 内循环逐天然边界调用才成立
    「取消即停止写产物」；阻塞实现在 withContext 边界被观察到，取消后跑完当次调用）。
  🔴 取消语义 = 协作式 + 收口，非强停（KDoc 契约已随批改真话）；
- **错误映射**（`describeFileTaskError`，`:ui` 顶层函数）：私钥缺失哨兵 / NumberFormatException
  +InvalidKeySpecException →「私钥格式错误」/ IllegalArgumentException →「文件格式错误」三类
  中文映射 + 指引；任务卡 catch 与 palette 直执行两路同源消费（lib 层保持英文异常零依赖纪律）。

### 页别差异参数（批 11 TL-05/TL-07 落地文案）

| 页 | title / taskTitle | emptyHint | paramsSlot |
|----|-------------------|-----------|------------|
| XLog 解密 | XLog 解密 / 解析任务 | 还没有解析任务 | 私钥框（label「私钥」，placeholder「输入 xlog 私钥（加密日志必填）」，PasswordVisualTransformation + 显隐切换；未配置顶置 Warning 引导） |
| Tag 过滤 | Tag 过滤 / 过滤任务 | 还没有过滤任务 | 「Tag 正则」框（monoInput + regexValid isError + supportingText「正则语法有误」）+「输出标记」框（placeholder「用于输出文件命名，如 MyTag」） |
| 文件分割 | 文件分割 / 分割任务 | 还没有分割任务 | `CommitNumberField`「分割大小」（≥1 校验，失焦提交 + 非法不提交） |
| 时间戳 | （非 TaskPageTemplate——独立表单页，见下节） | — | — |
| 解压 | 解压 / 解压任务 | 还没有解压任务 | 无（不传 paramsSlot）；🔴 不传 onDeepAnalyze（解压卡无「深度分析」钮） |

### TL-07 文案口径（批 11 三面同词）

- 旧「Tag 名称」/「Private Key」已全量中文化：页面 label、palette 命令面
  （`LogKitContributions` ParamSpec label「输出标记」）、校验面（`LogTagFilterViewModel`
  / `LogKitContributions` 的「输出标记不能为空」）三处同词；
- placeholder「用于输出文件命名，如 MyTag」；KDoc/横幅里的「xlog 私钥」是场景词保留。

### TL-11 深度分析链（批 11 扩两签）

```
成功卡「深度分析」钮（TaskCard.onDeepAnalyze 槽——onDeepAnalyze != null 才渲染）
  → :core DeepAnalyzeHook.offer(listOf(outputPath), "<域>产物深度分析")
  → 宿主 AppViewModel.init 恒接线（wire：补 spaceId + LogWorkbenchDeepLink.offer
     + setCurrentPage(LogWorkbench)）——feature 互不依赖铁律，logkit 只触中立槽
  → LogWorkbenchDeepLink.consume()（一次性取走）→ VM.prefill（预填不自动分析）
```

- 三签接线：XLog「XLog 解密产物深度分析」/ Tag 过滤「Tag 过滤产物深度分析」/
  文件分割「文件分割产物深度分析（N 卷）」（🔴 分割页投**全卷**——`allVolumesOf`
  按分卷名形扫同目录兄弟卷，只投首卷会把深度分析静默限死在片段 1/N；成功卡 note
  「共 N 卷」兜底标注）；
- AI 关闭时按钮仍渲染可点击（宿主 init 恒接线，深度分析 ≠ AI 强依赖——工作台分析链
  零 LLM 也可跑）；
- 右键菜单与 TaskCard 行尾「⋯」溢出菜单（TL-06）条目同源：`buildTaskContextMenuEntries`
  顶层单源（Success 态 = 打开文件夹/查看/深度分析/AI 整理/送往下一步/送飞书；
  Running 态 = 取消任务 danger；Error 态 = 打开文件夹/重试——失败也有出路；
  Cancelled 无条目无钮）。「查看」与「送飞书」仅产物是普通文件才出现（目录产物
  条目自动缺席）；「深度分析」/「AI 整理」/「送往下一步」由来源页按槽位注入决定
  在缺席。「⋯」钮 = M3
  DropdownMenu 直弹（AppContextMenu 仅右键触发无程序化展开口），容器视觉 surfaceDeep +
  borderHairline 与 AppContextMenu 同族；菜单条目不 remember 键控（回调 lambda 每重组重建，
  键控会留陈旧闭包——TL-06 补三）；
- **「送飞书」条目**（Success 态产物外发）：产物直发桥接接收会话（`ReportDelivery`
  中立槽，VM 动作经 `TaskListSectionActions.sendToLark` 槽组合期注入）。置灰判据 =
  `ReportDelivery.ready` 单源——`enabled` 形态置灰不隐藏（菜单条目无 tooltip 能力，
  未就绪原因的明示交给回执 toast；条目恒在场保可发现性）。`ready` 在条目求值瞬间
  读取（右键是打开瞬间快照、⋯ 钮展开重组时重算，菜单开着不闪现切换）。
  测试锚：`FileTaskViewModelLarkSendTest`、`TaskCardOverflowMenuGuardTest`。

## TL-04 正则即时校验（批 1；init 回填重算 2026-10-09 修复批补）

`LogTagFilterViewModel.regexValid`：非空即同步编译（true 可提交 / false 语法有误）；
页面正则框 `isError = !regexValid && 非空` + supportingText——非法正则**拖入前**可见
（拖入拦截文案走模板 inputError 横幅）。🔴 **init 回填后重跑同一校验链**
（`recomputeRegexValidation`）——持久化的坏正则（上次会话未修正即退出）不重算则
`_regexValid` 恒默认 true，卡面与真实语法态背离。守护：`LogTagFilterPageWiringGuardTest`
（锚 regexValid/supportingText 面）+ `LogTagFilterViewModelTest`（含 init 回填重算用例）。

## Tag 过滤「看分布」统计面板（StatsOverviewSection / StatsOverviewPanel）

Tag 过滤页参数区底部的全文件分布统计（源码 `LogTagFilterPage.kt` 末三段：
`StatsOverviewSection` 触发区 + `StatsOverviewPanel` 结果面板；引擎 =
`:log-tools` `LogStatsAnalyzer.analyze`，与 CLI `--type stats`、agent 工具
`log.stats` 同核同口径）：

- **手动触发**（`requestStats`）：「看分布」按钮（Secondary + Running busy 态承载
  进行中反馈）——全量 IO 不自动跑，写正则前先看分布再决定过滤哪个 Tag，免盲试；
  Running 中重复点击拒绝。锚点文件（最近拖入批首文件）缺席时置 `Failed` 给可操作
  提示（「先拖入文件再『看分布』」），不静默；
- **全文件口径**（与正则预演的「仅前 `PREVIEW_MAX_LINES` 行」相对）：面板标题恒写
  「分布概览（全文件）」；`LogTagStatsState.Ready.fileName` 是统计发生时的锚点文件名
  ——换拖新文件后旧面板靠文件名自证不误导，新统计由用户按需再触发。面板呈现：
  总块数 / 时间跨度（首末时间戳按值取 min/max；**全文无时间行时如实呈现「未识别到
  行首时间戳，跨度不可得」，不猜零值**）/ 级别块数（仅非零档 + 未识别计数）/
  Tag 频次前 `STATS_TOP_TAGS`（10）项（降序，共 `totalTagCount` 去重总数）/ 头行
  未识别出 Tag 的块数；
- **Tag 频次行点击回填**（`backfillTagRegex`）：Tag 字面值**逐字转义**正则元字符后
  **覆盖**写入 Tag 正则框——转义只做元字符级（`\.^$|?*+()[]{}`），不用 `\Q…\E`
  整段包裹（回填值直接呈现在输入框里，包裹形态不可读）；**不加 `^`/`$` 锚**（块命中
  是块内任一行 `find()` 的包含语义，块头行自带时间戳/级别前缀，锚形态永不命中）；
  覆盖非追加（点击语义 = 现在就过滤这个 Tag）；复用 `onTagRegexChange` 既有链路
  （校验即时重算 / debounce 落盘 / 预演自动跟随）。空 Tag 不回填（置空正则须走输入框
  显式清空）。「看分布 → 决定过滤 → 点击回填」闭环由此收口；
- **导出 Markdown**（`exportStats` → `StatsMarkdownExporter`）：面板所呈 `Ready` 快照
  → 可分享交付物，落 `~/Documents/Toolbelt/reports/stats-<秒级时间键>.md`（与日志工作台报告
  导出同根可见产物树；同秒重复导出原位覆写——同秒快照相同，覆写即最新）。
  🔴 数据源 = 面板同款快照（**不重扫文件**——导出与面板同源单数据，防「导出比屏上
  多/少」两套口径；快照自带前 N 截断，导出不再截断也不补全）。导出产物路径行回显
  （可拖选 + CopyIconButton 复制钮）；导出失败走全局 toast（StatusSignals）；
- **送飞书**（`sendStatsToLark`，`LogTagFilterViewModel`）：导出与外发一气呵成
  ——**发送前恒重新导出**（经 `writeStatsExport` 与「导出 Markdown」共用分流，
  落盘即恒最新统计快照，不复用旧导出产物）→ `ReportDelivery.deliver` 以文件
  消息直发桥接接收会话。`ReportDelivery` 是报告外发的 `:core` 中立槽（契约在
  `:core`、桥接宿主接线——feature 互不依赖，两侧唯一共同可见层即 `:core`），
  日志工作台报告面板送出动作同槽，本面板是其第二消费面。失败人话由通道归一
  （未接线/未运行/未配置会话/出站拒绝/传输失败），回执走全局 toast（StatusSignals）；
- **置灰单源**（`statsLarkSendDisabledReason` 纯函数，`LogTagFilterPage.kt`）：
  null = 可发送、非 null = 置灰原因（按钮 `disabledReason` 直接消费，禁静默置灰）；
  就绪态真源 = `ReportDelivery.ready`（未接线/未运行恒 false）。面板仅 `Ready`
  态组合，统计未生成时按钮不渲染——置灰判据只剩桥接运行态一闸；
- 🔴 **`deliver` 的 null = 已送出成功语义**：`ReportDelivery.Channel.deliver`
  返回 null 表示送出成功、非 null 才是失败人话——消费侧不可写
  `deliver(file) ?: 失败文案` 的 elvis 兜底（会把成功吞成失败），须先收返回值
  再判空分叉回执。

## TL-02 / TL-08 / TL-13 工具聚合页（宿主侧）

- **TL-08 ToolsConfigStore**：子签选择跨启动持久（key `tools_selected_tab`，
  AppDataStore；越界值视为脏数据回 0——🔴 刻意不用 coerceIn：`coerceIn(9)=4` 落第 5 签，
  坏值应回默认而非就近夹取）；
- **TL-02 聚合签路由**：五件套深入模式（palette openPage）以临时 tab 承载跳原页；
  子签选中态 rememberSaveable；
- **TL-13 Compact 档短名**：`currentWindowSizeClass() == Compact`（< 1024dp）时六签
  显示短名表「解密 / 过滤 / 分割 / 时间 / 解压 / 对比」（窄窗等分布局全名截断辨识度崩）——
  显示层替换（index 序不变、状态面零交集）；短名表是 ToolsPage 私有常量**不进
  ToolPageLabels**（检索别名与显示名分离 DL-F15）。

## 时间戳页（feature-timestamp）

非文件任务形态的独立表单页（同在工具聚合页子签）：

- **双向转换**：输入秒/毫秒时间戳或日期时间串 → `TimestampConverter` 纯函数互转；
  结果区按 `ConversionResult` 四态分支（Empty/FromTimestamp/FromDate/Invalid）；
- **300ms debounce + Enter 立即求值**（XA-13 · 批 11）：输入框挂 `.onEnterKey {
  viewModel.convert(input) }`（SubmitKey 单行语义默认档，含 NumPadEnter）——粘贴长串后
  Enter 即得结果不等 debounce；Enter 后 debounce 流对同值再触发一次，幂等无害；
  ui-style-guide 4.7j Enter 提交矩阵「时间戳页」例外行由假声明转真；
- **当前时间卡折叠化**（TL-09 · 批 11）：`SectionCard(collapsible = true)` 默认收起，
  折叠态单行 = `headerTrailing` 承载秒值 + CopyIconButton（进签主意图是「转换」，
  三行常显把首屏推到下方）；点标题行展开三行（秒/毫秒/日志格式 NowTimeRow）；
  展开态 trailing 秒值与首行去重；500ms 心跳不受折叠影响（VM 单例恒跑）；切签回保持
  展开态 = 页面 `rememberSaveable` 受控态（`expandedOverride`/`onExpandedChange`）；
- **Recent chips**（TL-12 · 批 4）：结果区上方一行，点 chip = 回填 + 立即 convert；
  会话内无成功转换则整行不渲染；
- **输出格式预设**：`SingleChoiceSegmentedButtonRow`（LOG/标准/ISO-8601…），
  切换即经 `TimestampConfigStore` 持久化并按新格式重算；恢复 runCatching valueOf 回退 LOG。

## 日志对比页（LogDiffPage，工具聚合页第 6 签）

宿主 `composeApp/.../page/tools/logdiff/`（`LogDiffPageContent` 无头形态——聚合页
自带 PageScaffold + 子签栏；`LogDiffViewModel` 接线层）。引擎 = `:log-tools`
`LogDiff`（Myers O(ND) + 行哈希流式扫描内存有界 + 归一化复用 `TimestampNormalizer`，
引擎契约见 [log-tools.md](../log-tools.md) CLI `diff` 节）。

- **单发双输入状态机**：与四胞胎的逐文件建卡批任务流不同——对比是「一次点击对两份
  文件产出一份结果」的单发动作（`Idle → Running → Done | Failed`，Failed 后可重新
  发起），不继承任务流基类；
- **双槽拖入**：基准 / 对比两槽（`FileDropZone`，接收面 `.txt`/`.log`）；**一次拖入
  两份**按落入槽位次序填满两槽（首份落本槽、次份落对侧槽——双份拖入视为「填满参数区」
  意图），第三份起忽略并经 StatusSignals 明示跳过数；换文件手势 = 单份拖入只覆写本槽。
  busy 门禁：对比进行中拖放区拒收换文件（busy 态两道回执组件内建）；
- **缺一拒收**：开始对比时两槽任一为空不发起、置 `inputError` 横幅（文案指明缺哪个
  槽——拖了基准漏对比是主场景，裸「请选择文件」用户两槽来回猜）；新文件落入或重新
  发起时清除，也可手动「清除该提示」；
- **归一化开关**（`normalize`）：「时间戳归一化对齐」——仅行首时间戳不同的行视为
  同一行（两次复现/两个版本比行为不比时钟）。🔴 只在**发起对比时**采样进引擎——
  开关拨动不重跑在途结果，改开关后需再点「开始对比」才生效；
- **degraded 明示**：引擎编辑距离超 `LogDiff.MAX_ALIGN_EDITS` 时退化整体替换——
  Warning 横幅**必须明示**「中间区域已整体替换展示、未逐行对齐」并给下一步指引
  （已开归一化仍超限 → 建议缩小对比范围；未开 → 建议开启归一化）。degraded 时 hunk
  巡检导航不可用（整体替换无逐 hunk 定位意义）；
- **结果渲染**：hunk 分组（unified `@@` 头 + 行级着色——增行 primary / 删行 error /
  上下文 onSurfaceVariant，语义色保守默认待真机终裁）扁平化进有界 LazyColumn
  （`heightIn(consoleHeightMax)`——本页处宿主 verticalScroll 容器内，无界
  LazyColumn 直接抛异常）。hunks 空表 = 全等，以 Success 横幅呈现（「两份文件内容
  一致（基准 X 行 / 对比 Y 行）」），不渲染结果卡。摘要行「共 N 处差异 · 基准 X 行 /
  对比 Y 行」；
- **hunk 巡检导航**（`HunkNav`）：「上一处/下一处」连点 + 「第 i / N 处」位置回显；
  未定位态（index = -1）首次推进落 0（不自动跳走用户视线）；边界置灰单源 =
  `canGoPrev`/`canGoNext`。定位走即时 `scrollToItem`（滚动零动画配方），目标 = hunk
  首行在扁平行集中的下标（`hunkFirstRowIndices` 规律：每 hunk 恒展成 1 行头 +
  lines.size 行）；
- **差异行点击跳查看器**：差异行整行命中区可点 → 打开对应侧文件的**应用内查看器**
  并定位到该行（经 `ParamCommandCatalog.LogViewerRequest.offer(path, 行号)` 写槽，
  宿主根叠层挂载 `LogViewerDialog`）。侧别解析：删行与上下文行跳**基准侧**、增行跳
  **对比侧**（跳到该行渲染原文真正所在的文件——引擎对上下文行恒取基准侧原文）。
  行号语义：1 基、该行在**原文件**中的行号——引擎 hunk 窗口起点恒为原文件 0 基下标，
  归一化只变换比对键不改行号坐标系，两模式跳转语义一致无分支。🔴 跳转目标取**发起
  对比时捕获**的文件（`DiffUiState.Done.oldFile/newFile`）——对比完成后槽位换文件
  不得改变旧结果行的跳转目标（按当前槽位文件跳会静默跳错行）；
- **导出 unified diff**（`exportDiff`）：产物落对比文件旁 `<对比文件名>.diff`，引擎按
  **覆盖语义**写（对比常需改一份再比一次地反复导出——预删会丢「失败保旧产物」）；
  仅 Done 态可导出（按钮仅 Done 态渲染，其余态静默 no-op）；导出路径行回显
  （`exportReceipt`），失败走 StatusSignals；
- **送飞书**（`sendDiffToLark`，`LogDiffViewModel`）：导出与外发一气呵成
  ——**发送前恒重新导出**（经 `writeDiffExport` 与「导出 diff 文件」共用分流，
  落盘即恒最新 diff，不复用旧导出产物）→ `ReportDelivery.deliver` 以文件消息
  直发桥接接收会话。仅 Done 态可发起（与导出同款渲染门，非 Done 态静默 no-op
  只兜底非 UI 调用面）。失败人话由通道归一（未接线/未运行/未配置会话/出站拒绝/
  传输失败），回执走全局 toast（StatusSignals）——与统计面板送飞书同槽同契约
  （`ReportDelivery` 中立槽面见「看分布」统计面板节）；
- **出站排除表判据面（diff 产物特有）**：产物落对比文件旁即**任意目录**（不落
  reports 产物树）——正常目录产物可照常送出；产物若落宿主机密根内或文件名命中
  凭据族，**命中被拒是设计行为**（排除表 fail-closed、不因调用方放宽），拒绝
  人话由通道归一经回执明示原因，不静默丢件；
- **置灰单源**（`diffLarkSendDisabledReason` 纯函数，`LogDiffPage.kt`）：null =
  可发送、非 null = 置灰原因（按钮 `disabledReason` 直接消费，禁静默置灰）；就绪态
  真源 = `ReportDelivery.ready`（未接线/未运行恒 false）。结果区仅 Done 态组合
  （与导出钮同款渲染门），置灰判据只剩桥接运行态一闸；
- 🔴 **`deliver` 的 null = 已送出成功语义**：与统计面板节同源 elvis 陷阱——消费侧
  不可写 `deliver(file) ?: 失败文案`（会把成功吞成失败），须先收返回值再判空分叉回执。

## DataStore key（四页配置）

| key | 所属仓库 | 类型/默认 | 用途 |
|-----|----------|-----------|------|
| `xlog_private_key` | LogKitConfigStore | String / "" | XLog 解密私钥（输入即持久，防抖落盘） |
| `log_tag_regex` | LogKitConfigStore | String / "" | Tag 正则 |
| `log_tag_name` | LogKitConfigStore | String / "" | 输出标记（旧称 Tag 名称） |
| `file_split_size_mb` | LogKitConfigStore | Int | 分割大小 |
| `timestamp_format` | TimestampConfigStore | String / "LOG" | 输出格式预设（枚举 name） |
| `tools_selected_tab` | ToolsConfigStore（宿主） | Int / 0 | 工具聚合页子签 index |

全部经 `:core` AppDataStore 进程唯一实例读写（约定 2——feature/宿主域禁止自行
PreferenceDataStoreFactory）。

## 测试面

- `LogKitParamCopyGuardTest`（TL-07 三面新串在位 + 旧串零残留）、`DeepAnalyzeWiringGuardTest`
  （TL-11 两页接线 + 解压页零 DeepAnalyzeHook）、`LogTagFilterPageWiringGuardTest`（TL-04 面）、
  `LogTagFilterViewModelTest`（含 init 回填重算用例）；任务基座：`FileTaskViewModelCancelTest`
  （检查点停写/取消收口回填/单飞去重/hasRunningTasks 八用例）、`DescribeFileTaskErrorTest`
  （三类映射）；`XLogDecoderCancelReceiptTest` / `FileSplitterCancelReceiptTest`（四页取消收口
  回执——历史产物不破坏 + partialOutput 如实回填）；log-tools：`LogDecodeFileTest`（半途失败
  保旧产物 + .tmp 删净 + 密钥预校验先于输出打开）；feature-timestamp：`TimestampEnterKeyWiringGuardTest`（XA-13）、
  `NowTimeCardCollapseWiringGuardTest`（TL-09）、`TimestampRecentChipsWiringGuardTest`（TL-12）、
  `TimestampConverterTest`；宿主：`TaskEmptyHintGuardTest`（「等待文件」全仓零命中 +
  页别文案在位）、`TaskCardOverflowMenuGuardTest`（TL-06 ⋯ 钮/菜单共享单源）。
- 扫盘型守护验收一律 `--rerun-tasks --no-build-cache`。

> :log-tools 库本体（`com/jiaoay/xlog/` 编解码管线、`com/jiaoay/log/` 过滤与分割、CLI
> `log-tools-all.jar`）已有专篇 [log-tools.md](../log-tools.md)——本页只记页面装配侧，
> 库层行为变化对宿主消费面的影响仍列下方。
> 2026-10-09 修复批库层行为变化两处：`LogDecode.decodeFile` 改**原子替换写**（temp 全量
> 解码成功后 `Files.move(ATOMIC_MOVE)`；失败/取消删 temp 保旧产物；密钥预校验提前到
> 打开输出前——头部 8MB 窗口探测含加密段且私钥空即抛同哨兵消息）；`LogTagFilter.sanitizeTagName`
> 由 internal 放宽为 public（宿主 feature 层预判产物名与库层命名逐字一致——覆盖确认卡
> 检测/清理的命名单源）。库层 exists 幂等契约不变（重跑死端由 feature 层覆盖确认卡解）。

