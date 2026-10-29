# 页面导航（R1 空间/文档模型 · 2026-09-25）

> **空间/文档分离（VS Code 范式）**：dock 是空间（不产生 tab），tab 条是你打开
> 的文档（全部可关、零固定）。旧「五舱/固定三签/retiredPageLabel 兜底链」模型已随
> R1 导航重构（2026-09-25）退役——本文按源码终态重写，历史形态见
> `openspec/changes/archive/`。
>
> **单开系统页批（2026-09-26 维护者拍板销账）**：设置/AI 设置/MCP/调试经
> `AppViewModel.SINGLE_SESSION_PAGES` 单源移出 tab 集——切页同空间语义不产生
> tab（「不会多开的页不占 tab 位」）；tab 集收敛为纯文档（报告 + 五件套深入
> 模式页）。设置页堆积重构已落地（`widget/SettingsTwoPane` 左分类导航双栏，
> 外观/环境/高级三分，窄窗回落单列——见 `PageSettings.kt` 与 docs/pages/settings.md）。

---

## ToolPage 枚举（主区内容 key）

文件：`core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ToolPage.kt`

```kotlin
enum class ToolPage {
    Setting,        // 设置页（系统区——dock 全顶排紧随分隔线；SINGLE_SESSION_PAGES 单开不进 tab 条）
    AppCenter,      // ~~应用中心~~ 已退役（反馈 6）——枚举值三段式保留，恒不可达
    XLogDecoder,    // ┐
    LogTagFilter,   // │
    FileSplitter,   // │ 五件套：navItem 已退役（S3），content 保留——
    ShellTools,     // │ 深入模式（palette 带参命令）+ 工具页子签 + dock 空间入口
    Timestamp,      // │ （五页仅 palette 深入模式与工具页子签可达）
    Archive,        // ┘
    Tools,          // 工具聚合页——五件套页内子签收拢，dock 第 5 空间（R1）
    LarkBridge,     // 飞书桥接（dock 空间第 4——R1 起不进 tab 条）
    McpExpose,      // MCP 服务（系统区——R1 起入 dock；文档页进 openTabs）
    AiSettings,     // AI 设置（系统区——R1 起入 dock；文档页进 openTabs）
    Distill,        // 知识蒸馏（退役散页——智能体中心第二签承载，palette 深入模式可达）
    LogWorkbench,   // 工作台 = 启动落点（dock 空间第 1；证据流视图宿主）
    VoiceTranscribe,// 语音转写（退役散页——智能体中心第三签承载）
    Flow,           // 会话（退役散页——智能体中心第一签承载）
    AgentCenter,    // 智能体中心聚合页（dock 空间第 2）
    DeviceObservation, // 设备观测空间页（dock 空间第 6——:feature-logcat 内容组件的宿主聚合页）
    Report,         // 报告（文档页——openTabs 成员；最近 run 报告列表 + 空态占位）
    Debug           // Shell 调试页（设置页开发者选项门控；文档页）
}
```

🔴 **S7 枚举收敛（2026-09-22）**：`Default` 欢迎页枚举值已删除（S2 退役页面、
S7 收敛枚举）——启动直达工作台家；存量用户 `last_page` 存 `"Default"` 时经
`loadEnum` 兜底落 `LogWorkbench`（`AppViewModel` init 钉死）。五件套枚举值
**保留**：深入模式（`ParamCommandSpec.openPage`）仍以页 content 形态渲染。

🔴 **应用中心退役（反馈 6，2026-09-23）**：`PageAppCenter.kt` 文件、注册项、
dock Apps 入口、`navItemsWithAppCenter` 顺序表扩展、`app_center_sort` 读写面
全部删除——**浏览/搜索/直达全部工具改经命令面板（Ctrl+K）承接**。存量三段式：
`ToolPage.AppCenter` 枚举值本批保留（恒不可达，后续批统一收敛删除）；存量
`last_page` 存 `"AppCenter"` 时 `AppViewModel` init 显式改道 `LogWorkbench`。

### currentPage 语义

`AppViewModel.currentPage` 自 S2 起语义收窄：**主区内容 key**（不是 14 页等权导航）。
主消费面 = `WorkspaceTabs`（tab 选中态）+ `PageHomeContent`（内容分发）。
持久化 key `last_page`（存 `ToolPage.name`，恢复经 `loadEnum` 兜底）。

---

## PageHome（五舱根布局）

文件：`page/home/PageHome.kt`

批 6 起，PageHome 之上还有**自绘标题栏**（`host/AppTitleBar`，`App.kt` 根 `Column` 首项）：透明圆角窗（`undecorated + transparent`）的窗内标题栏（三控制圆点/冻结快照拖拽/双击最大化），五舱全部在其下的内容区内——五舱 token 锚点坐标系原点 = 标题栏底，数值不变。

```
BoxWithConstraints                       ← provide LocalWindowSizeClass（classifyWindowSize）
 └── Column
      ├── Row(weight 1f)
      │    ├── PageHomeMenu    舱一：dock（降级形态——静态图标集，见下节）
      │    ├── WorkspaceTabs   舱二：主区 tab 容器（tab 条 + PageHomeContent 分发；零固定 tab——纯 openTabs 投影）
      │    └── SidePanelHost   舱三：右缘 inspector（全局单键宽度/窄轨态）
      ├── AppStatusBar          舱四：只读状态栏（面包屑 + 后台活动 + 桥接 + AI 开关）
      ├── StatusHostOverlay()    全局浮层 Toast（渲染壳；状态面在 :core StatusSignals）
      └── CommandPalette(...)   Ctrl+K 面板（浮层，paletteVisible 参数控制）
```

- **inspector 持久化**：`EasyDataManager` 全局单键（`inspector_width` +
  `inspector_rail`，500ms 防抖合并落盘）；旧 per-page `sidebar_<page>_*` 键随
  页面导航退役为**死数据不读不删**。
- **运行时集合分发**：`rememberHiddenNavPages()`（隐藏）/ `rememberAiDisabledPages()`
  （AI 置灰）/ `rememberNavActivity()`（活动指示）三产出点向 dock/tab/palette/状态栏分发。

### SpaceBar（已迁智能体中心第四签 · 空间挪窝批二 2026-09-23）

文件：`page/home/SpaceBar.kt`（`SpaceBarState` 自持列表态 + 选中态回写宿主）——主布局左轨已撤，页内宽轨形态由 `AgentCenterPage` 第四签渲染。

- 数据层 = `:core` `SpaceStore`（spaces 表，schema v5）：`create/listSpaces/
  spaceById/touch/rename/archive/addFiles·addFlows·addRuns/removeMember/delete/
  countSpaces`——空间删除**不级联删成员行**（聚合根只是引用集）；
- 选中态：`SpaceBarState.currentSpaceId` 自持 + `onSpaceSelected` 回写宿主
  `AppViewModel.setCurrentSpace`（App 级真源——深链泛化/FileBuffer→空间写入消费）；
- 成员三类：file_paths / flow_ids / run_ids（JSON 数组列）。

### WorkspaceTabs（舱二 · 文档 tab 容器 · R1 空间/文档模型）

文件：`page/home/WorkspaceTabs.kt`

- **tab 集 = `AppViewModel.openTabs` 纯投影**：只含「打开的文档」（报告/五件套
  深入模式页——AI 设置/MCP 服务/调试随设置同入 `SINGLE_SESSION_PAGES` 单开，
  不产生 tab），**零固定 tab**——旧固定三签（证据流/报告/桥接）
  与 `tabsProjection` 静态投影已随 R1 删除；
- **空集 Auto-hide**：无文档时 tab 条整体隐藏（AnimatedVisibility——回收 ~58px
  纵向空间）；空间页（工作台/智能体中心/Shell 工具/桥接/工具）不经 tab 条承载；
- **三分流判定单源**：`AppViewModel.TOOL_SPACES`（六空间）+ `SINGLE_SESSION_PAGES`（设置/AI 设置/MCP/调试，2026-09-26 维护者拍板移出 tab 集）——`setCurrentPage`
  对空间页只切 `currentPage`、对文档页追加 openTabs；dock 图标集（DOCK_ENTRIES）
  与此集语义同构但两处不合并（合会让 core 依赖 composeApp 页面文件）；
- **不持久化**：重启回空集（空间态由 `last_page` 承载），openTabs 不入 DataStore；
- **报告 tab 内容**（`page/home/PageReport.kt`）：最近 run 报告卡列表（RunStore
  reportPath 非空者倒序截前 N 件——文件名 + run 标题 + meta 行两 `PillLabel` 胶囊
  徽标[状态/相对时间——DOC-06 · 批 7.5；四态 pill 色对 = `statusPillColors`：
  RUNNING primary×selectedWash / COMPLETED successContainer×success / ABORTED
  surfaceDeep×onSurfaceVariant / FAILED errorContainer×error——与
  LogWorkbenchPage.statusColor 四态同映射双写互锚，改映射须双改]）+ 两动作钮——
  「查看」= 系统默认程序打开报告文件本体（DOC-02 · 批 7：`DesktopUtils.openFile`，
  失败降级打开所在目录）/「去工作台」回看 run（DOC-01：带 runId 深链 restore）；
  **整卡点击 = 展开/收起正文只读区**（DOC-05 · 批 4：markdown 源文本 mono 渲染 +
  惰性拉取，折叠/展开态 rememberSaveable 保持）；超上限列表尾补截断说明行
  （旧报告经工作台分析历史回看）；空态文案 + 加载圈 +「去工作台」按钮；
  `navItem = null` 不进任何导航面，`NON_COUNTED` 不计数；
- **复活通道封堵（R1）**：palette 推荐把 Distill/Flow/Voice 信号经
  `ToolPageLabels.hostOf` 路由到智能体中心子签、五件套信号路由到工具页子签
  ——退役散页不再复活为动态 tab；
- tab 条活动点消费 `navActivity`（S5a 起会话 tab 有 RUN 徽标）。

---

## PageHomeMenu（舱一 · dock 空间导航）

文件：`page/home/PageHomeMenu.kt`

**R1 空间/文档模型下 dock 是唯一空间导航**：静态图标集（DockManager 定制语义
[dock_items key/编辑模式/常用项管理] 早已随批删除）：

- 结构（自上而下）：**空间集**（工作台=LogWorkbench / 智能体中心=
  AgentCenter / Shell 工具=ShellTools / 飞书桥接=LarkBridge / 工具=Tools /
  设备观测=DeviceObservation——轨顶直落第 1 个空间图标、无品牌锚；点击切空间
  **不产生 tab**，与 `TOOL_SPACES` 单源同构；第六空间 dock 末位追加——
  Ctrl+1..5 序不变，第六空间 = Ctrl+6）→ 分隔线 → 命令入口
  （Ctrl+K 同源——全量工具的浏览/搜索/直达面）→ 分隔线 → **系统区**（全顶排
  紧随分隔线、无沉底——AI 设置 + MCP 服务 + 调试门控 + 设置，R1 起系统页也
  入 dock，Ctrl+N 序与 palette 可达性死角修复）；
- **图标语义（R6 识别测试驱动换符，2026-09-25）**：GLM 裸猜识别率 25% < 50%
  换符线 → 工作台 QUERY_STATS→DESCRIPTION（文档审查语义——QUERY_STATS 是
  改名前旧 icon 名，现役恒 DESCRIPTION）/ Shell 工具 DEVICES→TERMINAL（终端
  语义）/ 桥接 FLIGHT_TAKEOFF→FORUM（消息收发语义）；
  换后识别率 80%。消费面同步：palette 未读产物行 + LogWorkbench 贡献 icon；
- 视觉契约：`railWidthCompact`（68dp）纯图标 + hover 气泡配方单源
  `widget/interactions/HoverTooltipBox`（底层包装官方 material3 TooltipBox）+ 选中态
  `selectedContainer` 实底胶囊（与页签栏选中胶囊同族）+ AI 角标（恒紫 aiAccent）+ 活动点（navActivity）；
- dock 空间集过滤 `hiddenPages`（navVisible 契约保留）。

---

## AgentCenterPage（智能体中心聚合页 · AI 聚合批 2026-09-23）

文件：`page/agentcenter/AgentCenterPage.kt`（宿主页——AI 功能收拢为页内子签）。

- **SegmentedControl 四签**（P0 一致性批 2026-09-25：TabRow→SegmentedControl，
  与 LarkBridge 页内多签同语言）：会话（`FlowPageContent` 无头变体，默认签）/
  知识蒸馏（`DistillPageContent`）/ 语音转写（`VoicePageContent` embedded）/
  调查空间（`SpaceBar`——空间挪窝批二自主布局常驻轨迁入，页内宽轨形态）；
- **inspector 槽位承接**：Flow 的历史会话侧栏（FORUM）经
  `FlowContributions.flowSideBarContribution` 由本页 sideBar 消费（sideBarOf(AgentCenter)）；
- **AI 设置独立保留**（维护者拍板：控制台性质，随时进开开关，不入聚合）；
- **聚合入口不置灰**（aiEnabled 不上送——防「AI 关了找不到入口」死锁，子页各自表达依赖）；
- 子签选中态由预选槽订阅合成（`remember` 持态——saveable 恢复态与槽载荷竞态，
  消费配方见「一次性消费槽与页面驻留」节）；切签零动画（PageHomeContent 同口径）。

---

## ToolsPage（工具聚合页 · P1 动线批 2026-09-25）

文件：`page/tools/ToolsPage.kt`（宿主页——五件套收拢为页内子签）。

- **动机**：S3 起「palette 承接一切」把零门槛高频工具（解压）藏进 Ctrl+K——
  本页补上**常驻可达面**（dock 第 5 图标，Ctrl+5）；五件套自身 navItem 仍退役
  （palette 深入模式与带参命令照旧）；
- **SegmentedControl 五签**：XLog 解密（默认签）/ Tag 过滤 / 文件分割 / 时间戳 / 解压——
  四胞胎经 TaskPageTemplate `embedded` 无头形态复用（feature 页新增 `embedded` 参数），
  时间戳经 `TimestampPageContent()` 无头内容体（`TimestampPage` 保留 PageScaffold 壳）；
- XLog 签内**私钥状态显性化**：未配置时参数区顶置 Warning 引导（旧「拖入失败才知道」
  隐形硬前置退役）；palette 命令失败文案同步指向「工具」页；
- 滚动责任在本页 content 容器（verticalScroll 包装，embedded 四胞胎内部 LazyColumn
  heightIn 有界嵌套安全）；子签选中态经预选槽订阅合成（`remember` 持态——
  saveable 恢复态与槽载荷竞态，消费配方见「一次性消费槽与页面驻留」节）、
  切签零动画。

---

## ToolPageRegistry（注册装配点）

文件：`page/home/ToolPageRegistry.kt`

宿主单点装配：feature 模块 `Contributions.registrations` + 宿主自有页
（设置/报告/调试）拼接（应用中心注册项已随页退役删除——反馈 6）。

```kotlin
object ToolPageRegistry {
    fun registrations(appViewModel): List<ToolPageRegistration>   // content 分发（PageHomeContent）
    fun navItems(hiddenPages): List<ToolNavItem>                  // 导航项（8 页）
    fun navSections(hiddenPages): List<ToolNavSection>            // 三分组（工具/智能体/入口——GLB-04 起面包屑不再查表，保留为分组卡回归位+NavTest 不变量断言）
    fun hiddenPagesOf(): Set<ToolPage>                            // navVisible=false 归集
    fun aiEnabledSources(): List<ToolPageRegistration>           // AI 置灰归集源
    fun navActivitySources(): List<ToolPageRegistration>         // 活动指示归集源
    fun sideBarOf(page): List<SideBarContribution>                // inspector 槽位（页面作用域）
    fun toolActions(): List<ToolAction>                           // B7 无参动作面
    fun paramCommands(): List<ParamCommandSpec>                   // S3 带参命令聚合面（五件套验收载体）
}
```

- **注册顺序** = navItems 展开序（palette 同源）：以 `ToolPageRegistry` 现场拼接为准
  （featureRegistrations + 智能体中心聚合项插入 + 宿主页——AI 聚合批起含 AgentCenter，
  枚举旧序已漂移不再罗列）；
- **aiEnabled 宿主 copy 注入**（feature 互不依赖铁律）：宿主拼接点对
  LogWorkbench/Distill/VoiceTranscribe/Flow 各 copy `AiToolsToggle.enabled`
  （开关真源唯一）；**navActivity 同点注入**（WorkbenchSession.activity /
  QuestSession.activity 镜像 + feature 直引上送并存）；
- **分组数据驱动**：`ToolPageRegistration.group`（TOOLS/AGENTS/ENTRY）是分组归属
  唯一真源，宿主零映射清单。

---

## NavVisibility（可见性单点归集器）

文件：`page/home/NavVisibility.kt`——三维度语义不同不得混用：

| 维度 | 产出函数 | 语义 |
|------|---------|------|
| 隐藏 | `rememberHiddenNavPages()` | 页不在导航出现（navVisible=false；契约保留，当前全恒可见） |
| AI 置灰 | `rememberAiDisabledPages()` | 可见但置灰 + 恒紫 AI 角标（点击引导去 AI 设置） |
| 活动 | `rememberNavActivity()` | page → Idle/Running/Attention（dock 圆点/tab 活动点/状态栏活动段） |

`App.kt` 滞留兜底：`LaunchedEffect(hiddenPages, currentPage)` 当前页被隐藏即
回退工作台家。

---

## 命令面板（Ctrl+K · S3 升格形态）

文件：`page/home/CommandPalette.kt`（核心状态机 + `PaletteParamForm.kt` / `PaletteHud.kt` /
`PaletteEmptyState.kt` / `ParamCommandCatalog.kt` / `PaletteEntries.kt`——尾部卫星件
`PaletteEmptyHint` 族 / `PaletteEntry` / `PaletteSectionHeader` / `PaletteRow` /
`sortEntriesByFrecency`，2026-10-11 B6 自 CommandPalette 尾段平移，private→internal 同包）

- **候选四路汇入**：导航项（navItems）+ B7 无参动作（toolActions）+
  带参命令（ParamCommandCatalog.all = feature 页面命令 + AgentToolCatalog uiHint
  投影）+ 设置/调试宿主页；排序 = frecency（page_use_counts 加权）；
- **带参命令状态机**：列表态 → Enter/点击进**参数直填态**（ParamSpec 逐字段表单；
  FILE 拖入语义 / CHOICE chips；🔴 进表单焦点自动转移到首参数框、退表单回搜索框——
  `paramFocusRequester` 链；**编辑查询不再清 activeParam**（打字毁表单已修），表单态
  退出 = 仅 Esc 阶梯[确认取消→表单/HUD 退列表→关面板]）→ 填齐执行（🔴 Enter 由根
  拦截层收口——preview 隧道祖先先于焦点组件，与搜索框分支同源就绪判定/危险闸/执行中
  吞键；或点就绪态「执行」AppButton——原 else-if 提示就绪瞬间消失、鼠标零 affordance）
  → **HUD 态**（结果文案 + 产物 chips）；openPage 非 null 的命令行尾「打开工具页」钮 = **深入模式**
  （复用 onNavigate 跳原页面——R1 起五件套深入页作为文档进 openTabs，可关）；
- **危险动作 inline 二次确认**（2026-10-09 修复批，原「视觉标注」定音已推翻）：`isDangerous`
  条目（行 error 色）首次 Enter/点击置确认态（面板内 StatusBanner 确认条），**同条目再次
  Enter/点击才真执行**；Esc 先取消确认、激活其他条目改挂新确认（带参命令同一闸——含
  空参直执行路径）。契约注释 `core/navigation/CommandActions.isDangerous` 同批改；
- **前缀模式**：`>` 只看命令 / `@` 只看页面（前缀域不渲染「未读产物」段——
  用户明确收窄域时保底推荐让位）；
- **检索态三段分组**（GLB-12）：带参命令（动作项 Action 同档并入）→ 页面 →
  未读产物三段各带分组头（labelSmall faint）；第三段**恒显**（不按 query 过滤，
  「系统提醒你有未读报告」语义不依赖查询相关性；仅 Mixed 域）；
- **空态智能**（S3）：最近空间（SpaceStore 倒序前 3）/ 常用入口（使用计数前 3）/
  未读产物（RunStore reportPath 非 null 最近 3）三路推荐；🔴 R1 复活通道封堵：
  常用入口信号经 `ToolPageLabels.hostOf` 路由到聚合空间子签（Distill/Flow/Voice →
  智能体中心、五件套 → 工具页），退役散页不复活为动态 tab；
- **「暂存到 Buffer」动作**（S4）：palette 命令执行后可把产物路径暂存进
  `:core` `FileBuffer`（App 级 object，见 log-workbench.md）。

---

## TaskPipelineHandoff（工具管线衔接投递面）

文件：`core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/navigation/TaskPipelineHandoff.kt`
（`:core` 投递面——工具页产物卡「送往 &lt;下一级工具&gt;」情境动作的**管线衔接单源**，
标准日志分析管线：解压 → XLog 解密 → Tag 过滤 → 分割）。

### 三方分工

- **生产侧**（feature 工具页）：按产物来源页查 `nextToolTargetOf(sourcePageId)` 得
  目标（null = 条目不渲染），产物路径经 `offer(target, productPath)` 投递——
  目标命令 + 产物路径预填进命令面板**参数直填态**，用户只补剩余参数（如 Tag 正则
  与输出标记）即执行；
- **消费侧**（宿主）：palette 打开动作经 `wire` 接线（palette 可见态归属
  `main.kt`，借道宿主打开命令面板）+ `consumeSeed` 打开瞬间取载荷进参数直填态；
  消费契约**双路径**（槽 = `pendingSeed` StateFlow）：① 打开瞬间——palette 关→开
  时宿主组合全新起播，初值承载一次取走槽内既有载荷；② 面板已开——palette 保持
  组合期间新 offer 到达（唯一悬于已开面板之上的生产面 = 查看器独立窗口，宿主 open
  同值写回、effect 不重跑），消费面订阅 `pendingSeed` 流即时收到新载荷。两路径
  同用 `consumeSeed` **读即清**收口，同一 seed 恰消费一次，不滞留迟发；
- **表单化而非静默直执行**：下一级工具可能带必填用户参数，静默直执行无参数可取
  ——palette 参数直填态是既有命令执行面，必填参数留白由用户补齐。

### 产物类型 → 目标映射（PIPELINE 单张表，语义判据）

| 产物来源 key | 产物形态 | 目标 | 衔接语义 |
|--------------|----------|------|----------|
| `ToolUsageSignal.PageId.XLOG_DECODER` | `${name}.txt` 纯文本 | Tag 过滤（`logkit.tag-filter`，文件参数 `file_path`） | 管线主干衔接——过滤页输入类型即 `.txt` 纯文本日志 |
| `SourcePageId.LOGCAT_CAPTURE` | `logcat-<时间戳>.txt` 纯文本 | Tag 过滤（同上） | 管线支线衔接——抓取落点即「过滤定位」，行形态与过滤页输入同型 |
| `SourcePageId.LOG_VIEWER` | 可读文本日志（外来文件，无生产页身份） | Tag 过滤（同上） | 读面侧衔接——读到可疑 Tag 后一键投递，文件即过滤页输入类型 |
| `SourcePageId.LOG_VIEWER_ENCRYPTED` | 查看器拒收的加密件（XLOG_SEGMENTS 判型拒绝的 `.xlog`/`.alog`） | XLog 解密（`logkit.xlog-decrypt`，文件参数 `file_path`） | Failed 态衔接——加密件即解密页输入类型，回管线主干前半段（解密产物再进 Tag 过滤） |
| `ToolUsageSignal.PageId.ARCHIVE` | `<name>_extracted` 目录 | XLog 解密（同上） | 两分支契约（见下）——目录产物按内含 `.xlog`/`.alog` 命中数分流 |

不映射条目（语义判据）：**Tag 过滤产物**（已按 Tag 收敛的小文件）主去向是深度
分析（既有条目），不再映射分割；**分割产物**（`<name>_000.txt` 分卷序列）是管线
终点，无下一级。未登记的产物来源页一律返回 null（调用方条目不出现，无静默兜底目标）。

#### 解压产物两分支契约（ARCHIVE 行）

解压产物是**目录**，超出「单产物路径 → 单 palette 参数」的纯表映射语义，由调用方
（解压页条目动作）在投递前枚举分流，表行只承载目标与落点：

- **单文件**（目录内恰 1 个 `.xlog`/`.alog`，ignoreCase，混入非匹配文件不计）→
  `TaskPipelineHandoff.offer` 直投该文件进 palette 参数直填态（表行消费面）；
- **多文件**（≥2 个）→ `GoXLogDecryptHook` 跳页槽携**目录路径**（非预展开清单）
  去「工具」聚合页 XLog 解密子签——载荷读即清，由 XLog 解密页**组合期** consume
  后以页内同款 accepts 展开并批量入任务卡（含覆盖裁决）；accepts 过滤语义单源
  留在 XLog 解密域，生产侧不持第二套消费语义；
- **零匹配** → 不投递，就地 toast 回执原因 + 替代去向指引（拒绝必带原因；纯文本日志包的既有出口 = Tag 过滤页整目录拖入，回执指路免出应用回找）。

### SourcePageId 专属 key 纪律

`SourcePageId` 登记寄宿于其他工具页、无独立 `ToolUsageSignal.PageId` 枚举页的
内嵌面板（`LOGCAT_CAPTURE` / `LOG_VIEWER` / `LOG_VIEWER_ENCRYPTED`），**按产物语义
登记专属常量——勿复用宿主页的 `ToolUsageSignal.PageId` 常量**：宿主页其他产物共用同
key 会让映射表从「按产物」漂移成「按页」歧义。

### 横向直投（非管线）

管线衔接之外另有**横向直投目标**——表外常量 `TaskPipelineHandoff.VIEWER_TIME_SLICE`
（`NextToolTarget(label="送时间截取", commandId="tools.slice-time",
fileParamId="file_path")`），**刻意不进 PIPELINE 表**。语义判据：时间区间截取的
切出物仍是**本级**产物的子集（同文件的时间切片），不是下一级管线段的输入形态，
不构成「送往下一级工具」的管线衔接，故不占映射表行——`nextToolTargetOf` 查表恒
不返回该目标，调用方显式点名常量直投 `offer`。

消费点 = 查看器「送时间截取」底部动作（`:ui` `widget/LogViewerDialog.kt`），
额外预填经 **offer 扩参**承载：`offer(target, productPath, extraArgs: Map<String,
String> = emptyMap())`（HandoffSeed.args = 单路径 map + extraArgs；extraArgs key
须与目标命令 params id 逐字一致，与产物路径槽撞键时 extraArgs 值胜出）。预填集：
`file_path` 恒预填（产物路径经 offer 主参）；查看器带 `initialTimestamp` 定位打开
时预填 `start_time`（原样透传不解析不改写），行号档同无定位只预填路径（行号不是
时间点）；`end_time` 恒不预填（留白走目标命令既有必填未填齐回执）。按钮恒渲染
不按有无定位隐藏（定位档只决定预填集）；门控同管线衔接条目（仅 Ready 态渲染、
`isWired` 门控）。id 漂移守护：`ParamCommandCatalogTest`（`:composeApp`）断言常量
commandId 在 catalog `all()` 内且参数表含 `file_path`/`start_time`/`end_time` 三槽。

### 门控与守护

- **`isWired` 门控**：宿主未接线条目不出现（深度分析按钮同款门控语义）；
  `offer` 返回 false = 宿主未接线（投递被拒绝，载荷不残留）；
- **`commandId` 字符串锚守护**：目标命令 id 改名时 palette 解析落空 → 静默回落
  列表态（不炸面板），但衔接失效——`TaskPipelineHandoffTest`（`:feature-logkit`）
  断言 id 在 LogKitContributions 注册表内防漂移；
- 现网消费面：XLog 解密产物卡、logcat 抓取终态钮行、查看器「送往 Tag 过滤」
  底部动作与查看器 Failed 态加密件「送 XLog 解密」底部动作（`:ui`
  `widget/LogViewerDialog.kt`，契约见
  [log-tools.md](../log-tools.md)「消费面」节）；横向直投消费面见上方
  「横向直投（非管线）」节（查看器「送时间截取」底部动作）。

---

## 一次性消费槽与页面驻留（双 effect 配方）

跨页/跨签捎带「一次性载荷」的中立槽与消费页的标准消费形态。本节是配方册：
五消费面现形态 + 守护锚统一引用；新造槽照此消费。

### 结构前提

- **一次性消费槽**：宿主或 `:core` object 持有的三件套——`pending` StateFlow
  （载荷槽，初值 null/false）+ `offer`/`set` 写入 + `consume()` **读即清**
  （读后槽即空，同一载荷只被首个消费方取走）。生产面 offer 后紧跟
  `setCurrentPage(目标页)` 跳页——槽是「跳页 + 捎带载荷」的解耦通道（消费页
  在 feature 域时经 `:core` 中立槽解耦，不反向依赖宿主导航签名）；
- **页面驻留缺陷**（为何不能只写组合期一次性消费）：消费页若只写组合期一次性
  消费（`LaunchedEffect(Unit)`；palette 浮层消费面是 `LaunchedEffect(visible)`
  ——函数体以 visible 早退，关→开即全新组合，下同），该 effect 只在首组合跑
  一次。生产面（palette 候选行/查看器 Dialog/页内面板动作）可在**目标页保持
  组合**的状态下触发 offer——此时宿主 `setCurrentPage` 写**同值** StateFlow
  不发射、`AnimatedContent` 不重组，组合期一次性消费不重跑：新载荷静默 no-op
  并滞留槽内，离页返页/切签返签时才在错误上下文迟发（旧子签误跳、旧参数表单
  迟弹、旧展开迟置位）。

### 配方要件（双 effect）

1. **组合期初值承载**：`LaunchedEffect(Unit)` 一次性 `consume()` 取走首组合
   时槽内既有载荷，承担「首次落页/打开」路径；
2. **在页订阅**：`LaunchedEffect(槽源)` 订阅 `pending.filterNotNull()`
   （Boolean 槽用 `.filter { it }`），collect 内 `consume()` **只负责清槽**
   （载荷直接用 collect 收到的值），随后落地；
3. **落地分流单源**：两消费路径共用同一落地函数/同一落地 effect——订阅路径
   不得另造第二套分支（双分支即两路径行为漂移）；
4. **竞态自足**：初值承载清槽后，订阅流起播收到的初值即 null/false 被 filter
   过滤，天然不双发；即使订阅起播早于初值承载执行，`consume` 读即清也保证
   同一载荷恰消费一次，且两路径共用落地是同值幂等；
5. **子签态持态用 `remember` 不用 `rememberSaveable`**：saveable 的进程内恢复
   态会覆盖槽载荷/合成初值（恢复态与新载荷竞态，预选/持久语义失效）。

### 五消费面与守护锚（统一引用）

守护锚族全在 `composeApp/src/jvmTest/.../page/home/ToolPageWiringGuardTest.kt`
（源码切片锚——文件重命名/移动后须同步守护路径；测试方法名即锚）：

| 消费面 | 槽三件套 | 消费形态 | 守护锚 |
|--------|---------|---------|--------|
| `ToolsPage`（工具页子签预选） | `AppViewModel.pendingToolsTab` / `consumePendingToolsTab` | 双 effect；初值合成序 = 槽值优先 → 持久值回落 → 越界回 0 | `tools page composes initial tab from pending slot then persisted value` |
| `AgentCenterPage`（智能体中心子签预选） | `AppViewModel.pendingAgentCenterTab` / `consumePendingAgentCenterTab` | 订阅式单 effect——无持久合成序，StateFlow collect 起播重放当前值即覆盖首组合；读即清 + 越界回 0 | `agent center page options and tab state consume the pending slot` |
| `LogWorkbenchPage`（工作台深链） | `LogWorkbenchDeepLink.pending` / `consume` | 双 effect；落地共用 `applyDeepLinkInput`（预填 / runId 回看 `openRun` / 空间归属 / toast 回执） | `log workbench page consumes deep link through dual effects for in-page arrivals` |
| `CommandPalette`（管线衔接 seed） | `TaskPipelineHandoff.pendingSeed` / `consumeSeed` | 双 effect（承载 effect 键控 `visible`）；落地共用 `applyHandoffSeed`（命令 id 解析失败 `firstOrNull` 静默回落列表态）——消费契约双路径见本文「TaskPipelineHandoff」节 | `command palette consumes handoff seed through dual effects for in-panel arrivals` |
| `KbManagePanelContent`（蒸馏签库面板在签深链） | `KbManagePanelHook.pending` / `consume`（Boolean 槽） | 双 effect；两路径只置位 `kbPanelDeepLink`，展开落地由 `LaunchedEffect(kbPanelDeepLink, stats)` 等 kbStats 就绪统一承担 | `kb manage panel consumes deep link payload through dual effects for in-tab arrivals` |

工作台深链的载荷语义（预填/回看/空间成员写入/targetTime 独立通道）专篇在
[log-workbench.md](log-workbench.md)「深链」节；palette 衔接 seed 的生产侧
映射表在本文「TaskPipelineHandoff」节。

🔴 **该族槽纪律**：新造 pending+consume 槽时必须按本节配方双 effect 消费
（勿只写组合期一次性消费——页面驻留时新载荷静默滞留迟发），消费形态锚
（订阅槽流 / filter / consume 点数 / 落地分流共用）加进
`ToolPageWiringGuardTest` 同族守护。

---

## 全局快捷键表

唯一真源 `host/HotkeyHub.kt`（`match()` 纯函数 + `HOTKEY_TABLE` 常量，F1 参考卡
同源渲染；文档仅速查，改动以源码为准）：

| 键 | 动作 |
|----|------|
| `Ctrl+K` | 命令面板（混排；`>` 只看命令、`@` 只看页面） |
| `Ctrl+Shift+P` | 命令面板命令模式（`>` 预填） |
| `Ctrl+Tab` / `Ctrl+Shift+Tab` | 环形循环**已打开文档 tab**（R1 起纯 openTabs 序——空间页不经此键，文档页未开时经 Ctrl+K/dock 直达） |
| `Ctrl+,` | 设置页 |
| `Ctrl+W` | 关闭当前文档 tab（2026-10-09 修复批新增——可关 tab 此前仅鼠标 × 钮可达；当前页非文档 tab 时 toast 反馈，恒 consumed） |
| `Ctrl+1..6` | 跳转 dock 六空间（1=工作台 2=智能体中心 3=Shell 工具 4=飞书桥接 5=工具 6=设备观测——跳转序与 DOCK_ENTRIES 同源，dock 集之外的键不虚标） |
| `F1` | 快捷键参考卡 |
| `Esc` | 关闭顶层浮层（栈式 LIFO：F1 卡 → 命令面板） |

🔴 Ctrl+J / Ctrl+Alt+J 已随底面板退役删除（S2 维护者裁定）。

---

## PageUsageTracker（使用计数）

文件：`host/PageUsageTracker.kt`——统计页**真实使用**次数（palette frecency +
推荐首页「常用入口」供数）。🔴 **口径（反馈 4，2026-09-23 反转）**：使用 = 该页
**核心功能动作完成/发起**（解密/过滤/分割/转换/解压完成、命令启动、run 发起、
消息发送、桥接/服务启动、配置保存…），**非页面浏览**——点击页面/切 tab 不计数。
信号链：feature 动作点 `ToolUsageSignal.report(pageId)`（`:core`
`core/navigation/ToolUsageSignal.kt`，PageId 常量防拼写漂移）→ `AppViewModel`
init 订阅（loadEnum 映射，未知 id 忽略 + JLog）→ tracker +1 →
`EasyDataManager.setUsageCounts` 独立落盘（批 3 的切页合并写配方退役——
`setCurrentPage` 只写 last_page）。

`NON_COUNTED` = 设置/应用中心/报告/调试（结构性页面不计数；应用中心页已退役，
枚举值三段式保留仍留排除集兜底）。结构性页面**不发使用信号**。

---

## 新增页面步骤

1. **feature 侧**：`XxxContributions.registrations` 提供
   `ToolPageRegistration`（枚举值 + navItem（图标/label/分组）+ 可选
   `aiEnabled`（宿主 copy 注入——feature 不自送）+ 可选 `sideBar`
   （inspector 槽位贡献）+ 可选 `paramCommands`（palette 带参命令）+
   content lambda）；
2. **宿主侧**：`ToolPage` 枚举加值 → `ToolPageRegistry.featureRegistrations`
   追加一行拼接。

> 🔴 结构性页面（设置/报告/调试）进 `NON_COUNTED` 不计数——新增结构性页面须同步维护。
>
> 🔴 枚举与注册表同步演进：`ToolPageRegistryNavTest` 的「全枚举注册覆盖」断言
> 钉死每个枚举值（AppCenter 除外——页退役三段式）必须有注册项（缺项时
> PageHomeContent 兜底工作台家）。
>
> 测试文件分工（2026-10-11 B6 重组）：注册表的纯数据断言（navItems/navSections/
> registrations）留 `ToolPageRegistryNavTest`；导航**接线面**源码锚守护（dock/palette/
> 工具页/智能体中心/家页的图标字形、深链路由、子签槽订阅、文案单源切片）并出为
> `page/home/ToolPageWiringGuardTest.kt`。
>
> **inspector 槽位现状**：Shell 历史（HISTORY）/ Quest 历史会话（FORUM，由
> AgentCenter 聚合页 sideBar 承接）——Workbench 槽位已随 WB-25（重设计批 7）
> 删除（输入表单+分析历史回归页内左栏）——`SideBarContribution`
> （`:core` `core/navigation/SidePanelContribution.kt`：slot/icon/label/content）。
