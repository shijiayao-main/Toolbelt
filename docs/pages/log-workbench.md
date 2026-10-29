# 日志工作台

文件（`feature-logworkbench` 模块根 `feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/`）：

| 文件 | 职责 |
|------|------|
| `LogWorkbenchContributions.kt` | 导航注册出口（navItem DESCRIPTION「工作台」/ group=AGENTS；🔴 sideBar 贡献已随 WB-25 重设计批 7 删除——输入表单 + 分析历史回归页内左栏，inspector 对本页零贡献） |
| `LogWorkbenchPage.kt` | 工作台页（`PageScaffold(density = Dense)` + 整页 Box——HITL 确认卡固定右下叠加域；三栏 Row：左输入表单+分析历史 / 中块化证据流 / 右报告；S4 起中栏 = 琥珀块+紫块；WB-21 起 Wide 屏右栏 400dp 加宽档） |
| `WorkbenchReportPanel.kt` | 报告渲染簇（`ReportPanel` 纯渲染窄接口 internal——因果链三段卡/三态徽章/依据引用跳转/追问；2026-10-11 B7 自 LogWorkbenchPage 平移，零页面态共享） |
| `WorkbenchViewModel.kt` | run 态机 + 表单态 + 报告落盘 + prefill 深链缝 + attachFilesToSpace（S4 深链写空间成员）+ openRun 回看 |
| `WorkbenchSession.kt` | 研判 agent 会话核心（单循环 + HITL gate + companion activity 镜像[attach/detach]；reducer 自 2026-09-27 审计批起委托 :core `ChatSessionReducer`——streamingEnabled=true） |
| `LogWorkbenchDeepLink.kt` | 深链 pending 槽单例（object 门面；S4 起带 spaceId、DOC-01 起带 runId 回看） |
| `WorkbenchPrompts.kt`（`:core` `core/llm/`） | 研判 system prompt（ANALYZE_PROMPT = 人设+工具面+`disciplineSection()` 单源纪律正文；🔴 工具名以 KnowledgeTools 注册真源为准——read_doc 失真事故 2026-09-27 已修，Quest 协作段同引共享段） |

配套数据层（`:core`）：`core/data/db/Runs.sq` + `RunStore.kt`（runs 表）；
`Spaces.sq` + `SpaceStore.kt`（spaces 表，schema v5——调查空间聚合根）。

S4 起配套共享层：

| 层 | 文件 | 说明 |
|----|------|------|
| 证据纯函数（`:core`） | `core/evidence/EvidenceModels.kt` | parseLogLine/readLogLines/histogramBuckets/parseReport/contextLinesOf（🔴 suspend + 流式滑动窗定位 + Dispatchers.IO——产物可达百万行级，组合期全量 readLines 会卡调用线程）/findEvidenceFileOf（public，随迁测试 `core/src/jvmTest/.../evidence/EvidenceModelsTest.kt`） |
| 证据流渲染（`:ui`） | `ui/widget/evidence/EvidenceTimeline.kt`（+ `TimeHistogram.kt` 直方图子系统 / `EvidenceLineRow.kt` 行渲染件——2026-10-11 B7 拆分独立文件） | EvidenceTimeline/TimeHistogram/EvidenceLineRow/StageAnchorBar（原 LogWorkbenchPage 私有 Composable 上移 public；RowScope 9 参签名原样保持；offset 纯函数与主装配留 EvidenceTimeline.kt） |
| File Buffer（`:core`） | `core/buffer/FileBuffer.kt` | App 级暂存区 object（files StateFlow + offer/remove/clear；只存路径） |

---

## 页面布局（S4 块化证据流形态；P0 骨架迁移 + WB-25 输入回左栏后）

`PageScaffold(title = "工作台", density = Dense, constrained = false)`（三栏密度页
不启用限宽）+ 整页 `Box(fillMaxSize)`（WB-15：HITL 确认卡固定右下叠加域）内
`Row(weight 1f)`：

- **左栏** `Dimens.workbenchSidePanelWidth`（280dp）：`WorkbenchSidePanelContent`
  （内部自带 `fillMaxWidth().verticalScroll` 滚动 Column，外层只定宽勿再包滚动——
  双嵌套冲突）= 「全部报告」行钮（GoReportHook→报告页第二入口）+ FileDropZone
  多选拖入 + CommitTextField（时间点/Tag 正则）+ CommitNumberField（范围，1..1440 校验，
  非数字不进编辑态、越界不提交回退显示值——旧 toLongOrNull 直通 -5/0 显示与生效背离）
  + 症状输入 + 分析历史（HoverableRow 回看/改名/删，行点击经 WB-05 未收口内容确认拦截缝；
  运行中点「切换」不弹确认卡，StatusSignals 明示「研判运行中，请先中止当前分析」）；
  问题时间点旁「现在」钮一键取当前时间（P1 动线批——「现在出问题了」是最高频输入）；
  `setTargetTime` 宽松归一：空格/T 分隔与省略/短毫秒自动归一到 `yyyy-MM-dd_HH:mm:ss.SSS`；
- **中栏** `weight(1f)`：**块化证据流**（S4，`ui/widget/evidence/EvidenceTimeline`）——**琥珀块** = 工具产物行流（SelectionContainer 包裹——行流文本可选中复制，验收判据 = 视口内拖选；块头 = 阶段名+产物文件+迷你直方图[60 桶，`evidenceBlockHistogramHeight`]+「设为当前」钮；左缘色条 `evidenceBlockBarWidth` 工具色）+ **紫块** = AI 研判时间线（左缘色条/图标恒紫 aiAccent——融合案 D 双语义色裁决）。块间锚点条 `StageAnchorBar`（解密→时间过滤→环境扫描→Tag 过滤→研判管线阶段分界；chip 列挂 horizontalScroll——产物文件名多/长时防硬裁）；`jumpToTimestamp` 跨块定位链 = 切文件 + scrollToItem + 高亮（🔴 流式零动画铁律，`animateScrollToItem` 被 UiGuard 拦；WB-13 过滤态跳过滚动定位、高亮仍设）。行级交互：**TAG chip 双通道**（点 chip = 写左栏正则〔下次研判生效〕+ 写行内搜索过滤词〔当前证据流就地 contains 过滤，即时可见〕）；**E/W 级别 pill 槽对**（E = errorContainerFixed/errorFixed、W = warningContainer/warning——旧 logWarn×surfaceDeep 1.37:1 / logError×errorContainer 2.7:1 均低于 AA，槽对数值由 `SlotContrastGuardTest` 在 token 单源钉死）；无时间戳行 hover 洗底与 clickable 同门槛（enabled = timestamp != null，假 affordance 消除）；行展开 ±N 上下文异步取数（contextLinesOf suspend IO，取数中显「上下文回读中…」）；截断提示条 = 指产物文件本体 + 行尾「查看完整产物」钮（经 :core `LogViewerRequest` 中立槽 → 宿主根叠层 `LogViewerDialog` 应用内查看器——请求路径 = activeEvidence 单源，宿主未挂载时降级 `DesktopUtils.openInFolder` + toast 明示不静默 no-op）；**超窗点击动作化直达**——引用跳转目标行不在预览前窗且产物已截断（大概率在窗口外而非「不存在」）时，宿主挂载态下经查看器槽发起**带 `initialTimestamp` 的打开请求**（请求路径 = 本跳转解析出的产物文件单源，非当前页文件），查看器打开就绪后引擎时间戳 seek 定位到该行，点击→定位一步完成（用户不再进查看器手动输一遍时间戳），toast 兼作回执（未跳原因 + 已开已定位动作）；宿主未挂载时降级保持指路 toast（指路对象 = 截断条「查看完整产物」钮——该钮自身有 hostMounted 降级，指路恒有效可达），不静默 no-op。
- **右栏** `Dimens.workbenchReportPanelWidth`（320dp；WB-21 起 Wide 屏 >1760 用
  `workbenchReportPanelWidthWide` 400dp 加宽档）：报告五部分解析呈现（因果链三段卡 TonalCard / 三态徽章 ✓?✗ / 依据引用跳转 + 追问）。

🔴 全 token 化零白名单（含 S4 新增 `evidenceBlockBarWidth`/`evidenceBlockHistogramHeight` 两 token）。

## run 对象（一次分析 = 可回看对象）

`RunStore`（SQLDelight runs 表；sessionId = runId 复用 messages 时间线）：

```kotlin
class RunStore(private val db: ToolbeltDb) {
    data class RunSummary(id, title, logFiles: List<String>, targetTime, rangeMinutes, status, reportPath, createdAt, updatedAt)
    enum class RunStatus { RUNNING, COMPLETED, ABORTED, FAILED }
    // RunStatus.label(status) 中文标签单源（审计批 2026-09-27 收敛——PageReport 与
    // LogWorkbenchPage 各持一份逐字相同映射已并）
    fun create(...) / listRuns() / runById(id) / updateStatus(...) / updateReportPath(...) / rename(...)
    fun delete(id)  // 事务级联删 messages
}
```

报告落盘：`~/.toolbelt/logworkbench/runs/<runId>/report.md`（🔴 禁落源码仓库/日志目录外任意位置）。

### run 终态收口与运行态（2026-10-09 修复批收口面）

- **库行状态 ≠ RUNNING 是记录系统不变量**：`WorkbenchSession.startAnalysis` 两 catch
  （超时/异常）与配置快照/工具装配前置失败一律经 `onRunFailed` 回调落**失败降级报告**
  （五部分骨架 = 错误摘要 + 已产证据清单）+ 库行 FAILED——失败也落终态，run 永久卡
  「运行中」不存在；cancel 路径不走此回调（ABORTED 由 VM 收口，语义不同）；
- **孤儿 RUNNING 归一化**：VM init 扫 `listRuns()`，RUNNING 且非本进程在途（runJob 活在
  viewModelScope，上一实例销毁即死）全部置 FAILED「进程中断」——跨重启不谎报运行中；
- **追问收集器单句柄置换**：`phaseResultCollectJob` 每次发起研判 cancel 旧收集器再挂新的
  （无界累积 = N 次研判 N 个收集器并发写盘竞速；陈 collector 立即重放旧 phaseResult 假翻
  COMPLETED）。追问路径**先发起再挂 watcher**（不换会话，phaseResult 仍持上轮结果，
  Main.immediate 下 StateFlow 对新收集器立即重放——startAnalysis 入口先置空才可先挂）；
- **管线直跑运行态四面**（`pipelineRunning` StateFlow）：busy 渲染 / 中止钮 / cancel 闸 /
  重入与 openRun 深链守卫共用——`session.running` 不覆盖直跑；协程体捕获 session 局部
  引用，发起后 session 被置换（深链 openRun）产物仍写回发起会话；表单快照在发起时捕获；
- **删除三面同收**：`deleteRun` = 库行 + `runs/<runId>/` 报告目录（IO 协程，失败落日志
  不拖垮回执）+ UI 态置换占位会话（删当前 run 不留「库行已删而视图仍显旧证据流」幽灵态）；
- **拒绝必带原因**：`startAnalysis` 空 symptom/文件、`openRun` 运行中、`runPipelineOnly`
  重入与多文件降级提示均走 StatusSignals（不再静默 return）。

## 研判链（WorkbenchSession——B5 单循环会话）

- **工具集** `WORKBENCH_NAMES`（20 件）：fs.extended 七件 + knowledge 三件 + memory 四件 + analyze_log + log 时间过滤两件（`filter_by_time` 对称窗口 / `filter_by_range` 任意起止闭区间）+ 侦察三件 log.stats/log.diff/log.view；write_todos 经 todoBoard 补挂（目录外桌面会话件）。🔴 名集模块内自持（:core 的 EXTENDED_NAMES/MEMORY_NAMES 是 private 跨模块不可见——BridgeLogTools.BRIDGE_NAMES 先例）；
- **预算** `LlmConfigStore.getWorkbenchMaxIterations()`（key `llm_workbench_max_iterations`，缺省 60）；整体超时 = 单次超时 × 6 轮；
- **流式打字机**：StreamingFrame delta 累积进末尾 streaming 草稿 → LlmCallCompleted 原位转正（保留 id）→ finalizeRun 终稿消重（万能解密三段蓝本）；
- **HITL gate**：`pendingGate: StateFlow<File?>` + CompletableDeferred 挂起等裁决——
  围栏外路径弹**非模态确认卡**（审计批 2026-09-27 形态统一：模态 AlertDialog → 共享
  `widget/PathConfirmCard`；2026-10-11 B9 起进出场过渡/末值缓存/组合期抢焦装配上提
  `widget/GateConfirmOverlay`（`:ui` 共享覆盖层件，工作台与流程页两消费面同一配方）；
  WB-15 落位固定右下 BottomEnd 叠加、不遮页头/内容流；
  Enter 允许 / Esc 拒绝键盘裁决——FocusRequester 卡组合主动聚焦；XA-21 进出场
  AnimatedVisibility(fast 120ms)；批准后本会话内该路径免确认，拒绝则工具跳过该访问继续执行）；
- **AI 关闭降级**：`runPipelineOnly()` 直跑 `LogAnalysisPipeline.run`（:log-tools 经 :core api 传递可见）——spec「确定性管线可直接跑」；多文件只分析首个并 StatusSignals 明示（AI 路径全文件，两路径行为差异不静默）；
- **活动镜像（S5a 配方先例）**：companion `activity` StateFlow + `attach(session, scope)/detach()`——宿主 `ToolPageRegistry` copy 注入 navActivity，dock RUN 徽标/状态栏活动段自动点亮。

## File Buffer（S4 暂存区）

`:core` `core/buffer/FileBuffer.kt`（App 级 object，LogWorkbenchDeepLink object 门面先例）：

- **纯内存暂存（只存路径）**：SpaceStore 是同步 SQLite、调用方自管线程——object 内自建 scope 会红 AppHostGuardTest；写空间成员属宿主/VM 侧职责（`WorkbenchViewModel.attachFilesToSpace` 先例）；
- **授权语义在围栏**（「拖进会话才授权」）：文件被会话 register 即从暂存区移除——WorkbenchSession/QuestSession `registerFiles` 双挂 `FileBuffer.remove`；
- 消费面（2026-09-23 评审修复批闭环）：palette「暂存到 Buffer」命令（HUD 计数回显）+ **工作台证据流顶部 Buffer 行**——chips 点击 = 载入工作台（addTargetFile）并移出暂存区、× 移除、Tooltip 全路径；空 Buffer 零渲染。Popup 内拖拽（DnD）形态后置二期（KDoc 已销账）。

## 深链（design D6 宿主接线；S4 泛化 + 空间成员写入；DOC-01 回看语义）

```
XLog/Tag 过滤/文件分割产物卡「深度分析」（TaskCard.onDeepAnalyze 槽 → DeepAnalyzeHook.offer——TL-11 起三工具页全接）/ logcat 抓取终态「送工作台分析」（`:feature-logcat` `LogcatViewModel.sendToWorkbench`——标记过则附 targetTime 载荷；面板挂载于设备观测空间页）/ 报告卡「去工作台」（offerRunReview 回看深链）
/ Quest 会话 chips 点击（S5d onOpenArtifact）
  → :core DeepAnalyzeHook.offer(files, note, targetTime)  // feature 互不依赖：logkit/quest 只 import :core 中立槽；targetTime = 可选预填问题时间点（null = 工作台不触碰时间域）
  → 宿主 AppViewModel.init wire                             // 读 currentSpaceId 补 spaceId + LogWorkbenchDeepLink.offer（载荷原样转发）+ setCurrentPage(LogWorkbench)
  → LogWorkbenchDeepLink.consume()                          // pending 槽一次性取走（重复进页不重复预填）；消费面 = 双 effect（ToolsPage 预选槽同款）——组合期 LaunchedEffect(Unit) 初值消费 + 在页订阅 pending.filterNotNull()（工作台已是当前页时 setCurrentPage 同值不发射、组合期消费不重跑，在页新载荷经订阅即时消费；collect 内 consume 读即清，防离页返页初值消费重读旧载荷）
  → VM.prefill(logFiles, prefillNote, targetTime)          // 预填文件与症状（不自动启动分析；WB-07 不盲覆——已有症状追加不替换）；targetTime 非空/非空白经 normalizeTargetTime 归一面写入目标时间框（与 setTargetTime 同一写入面，不另造第二套路径），null/空白不触碰时间域
  → VM.attachFilesToSpace(spaceId, files)                  // S4：offer 带 spaceId 时写空间成员（SpaceStore.addFiles）
  → [runId 非 null 时] VM.openRun(runId)                    // DOC-01 回看语义：restore 时间线 + 报告回填；
                                                           //   openRun 落空（run 已删）以 logFiles 预填兜底
```

消费成功后 toast 回执「已带入 N 个文件（并预填症状）（并聚焦复现时刻）」——括段按
载荷有无增减：prefillNote 非 null 追加「并预填症状」、targetTime 非 null 追加「并
聚焦复现时刻」；WB-07——logFiles 为空不 toast，回看深链 offerRunReview 的 logFiles
默认空列表。

🔴 **prefillNote 禁塞机器数据**：症状域是用户必填可编辑域——机器载荷（问题时间点、
文件名、统计值等）不得拼进 `prefillNote`，一律走各自独立载荷通道（时间点 =
`DeepAnalyzeHook.offer` 第三参 `prefillTargetTime` / `LogWorkbenchDeepLink.offer`
第三参 `targetTime`）。机器数据混进症状域会被用户当作自己的输入直接看到/误编辑，
且 `prefill` 的 WB-07 追加语义（拼「（来自深度分析：…）」段）会把它缝进用户症状
文本污染表单。生产侧先例：`LogcatViewModel.sendToWorkbench` 的 note 恒为固定提示
文案，锚点 `fullTimestamp` 派生的时间串只走第三参。

## 报告出站（导出 Markdown / 一键送飞书）

**导出与外发共用一次导出写盘**：`WorkbenchViewModel.writeExportedReport`（`ExportOutcome` sealed 三态分流；回执 toast 归各调用方——导出与外发失败文案口径不同）→ `ReportExporter.exportMarkdown`（`feature-logworkbench/.../ReportExporter.kt`）落 `~/Documents/Toolbelt/reports/report-<runId>.md`（用户发起的交付物目录，与报告自动落盘铁律 `~/.toolbelt/logworkbench/runs/…` 双轨）。同 run 重复导出**原位覆写**；🔴 「送飞书」**发送前恒重新导出**（不复用上次导出产物，外发恒为最新报告文本，含追问增量）。

**一键送飞书经 `:core` 中立槽 `ReportDelivery`**（`core/runtime/ReportDelivery.kt`；`TaskPipelineHandoff` 同款配方——报告动作在 feature-logworkbench、桥接门面在 feature-larkbridge，两侧唯一共同可见层是 `:core`：契约在 `:core`、宿主接线）：

- `ReportDelivery.deliver(file): String?`——null = 已送出，非 null = 失败人话（含未接线：消费面恒可调用，无需自判接线态）；桥接域类型不进 `:core`；
- `ReportDelivery.ready`（StateFlow）= 「送飞书」置灰判据唯一真源：未接线/桥接未运行恒 false，报告面板据此置灰并明示原因（`larkSendDisabledReason` 纯函数，判据序即文案序），不静默；
- 宿主接线 = `LarkBridgeManager.start()` 生命周期单点 `wire { deliverReport }`（App 生命周期启动时一次；装配周期重建不换通道，`deliverReport` 现读 bridgeRef）；就绪态由状态收集回路 `publishReady(snapshot.running)` 发布，随宿主 shutdown 归零（与未装配同口径）；
- `deliverReport` 胶水（`:feature-larkbridge`）前置判桥接运行态与接收会话 = `connection.testChatId`（零新配置面），出站校验与传输收口 lib 门面 `Bridge.sendFile`（排除表同一张表等门面契约见 [pages/lark-bridge.md](lark-bridge.md)「运行与诊断操作」节与「出站敏感文件排除表」节）。

失败人话由通道归一（未运行/未配置会话/出站排除表拒绝/传输失败），VM 只做回执 toast；VM 守卫（运行中/无报告分支不可达）兜底非 UI 调用面。守护测试：`ReportLarkSendTest`（通道契约 + 就绪态语义 + 未接线人话失败 + 页面 `ReportDelivery.ready.collectAsState` 消费源码锚）。

## 测试面

`WorkbenchSessionTest`（reducer/HITL/时间线落库/名集/装配）、`WorkbenchSessionFailureTest`（失败路径落 FAILED/降级报告）、`WorkbenchPipelineDirectRunTest`（直跑运行态/取消/表单快照）、`WorkbenchFollowUpCollectorTest`（追问收集器置换/陈值重放防护）、`EvidenceModelsTest`（`:core` jvmTest，随 S4 上移；含大文件流式定位用例）、`LogWorkbenchDeepLinkTest`、`WorkbenchEndToEndTest`（合成样本管线全跑到 run 落库/回放/报告/级联删）、`FileBufferTest`（`:core`，offer 去重/围栏移除）、`WorkbenchReportPanelRenderGuardTest`（报告面板渲染守护——XA-22「分析历史」译名锚）。零 LLM/零网络（DistillSessionTest 同款纪律）。
