# UI 风格规范与标准

> 面向对象：写新页面 / 新组件的工程师与 AI。本文是**快速上手 + API 手册**：
> 令牌取值、可抄代码模板、反例与守护规则一文档在手，即可写出合规 UI，无需翻阅源码。
> 规范真源是 [core/theme.md](core/theme.md)（token 定义缘由、有据保留登记处）；两者冲突时以 theme.md 为准。

- 技术栈：Kotlin Compose Desktop（JVM），Material 3
- 源码根路径：`composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/`（下文相对路径均以此为根）
- 验证命令：`./gradlew :composeApp:jvmTest`（含 UI 架构守护测试，违规即红）

---

## 1. 概述

**目的**：所有 UI 代码共享同一套设计令牌（间距/圆角/透明度/动效/卡片/颜色）与共享组件，视觉统一、可维护、可被 `UiArchitectureGuardTest` 源码扫描守护。

**核心心智模型**：

1. **取值只认 token**——`Dimens` / `AppShapes` / `AppAlpha` / `AppMotion` / `AppCardStyles` / `MaterialTheme.colorScheme` / `LocalEasyToolboxColors`，不写裸 `xx.dp`、`alpha = 0.x`、`tween(...)`、`RoundedCornerShape(12.dp)`。
2. **组件只认共享层**——骨架用 `PageScaffold`，状态用 `StatusView`，任务卡用 `TaskCard`，文件输入用 `FileDropZone`，复制用 `CopyButton` / `CopyIconButton`，hover 行用 `HoverableRow`。
3. **动效只做状态过渡**——120/180/240ms 三档封顶，流式输出与日志行零动画。

**新增例外 / 新档位的流程**（顺序不可颠倒，文档先行评审）：

| 需求 | 流程 |
|------|------|
| 间距/图标/圆角/时长/透明度缺档 | 先在 `core/theme/` 对应 token 文件扩充定义，并同步 theme.md 取值表，再使用 |
| 无法 token 化的字面量（如 IconButton 触摸目标） | 先在 [core/theme.md](core/theme.md)「有据保留」清单登记理由，再在 `UiArchitectureGuardTest` 的白名单登记（文件 + 值），最后才能写进代码 |
| 新卡片档位 / 新语义色 | 先扩展 `CardStyles.kt` / `EasyToolboxColors.kt` 并登记文档，再使用 |
| 一次性反馈 / 计时器 | 计时常量只写 `core/theme/TimingConstants.kt`（5000/6000/1500/2400/200ms 五常量），🔴 不混入 `Motion.kt`（动画封顶档） |

**值等价迁移原则**（改老页面时）：替换 token 必须数值相等（`8.dp` → `Dimens.space8`、`RoundedCornerShape(12.dp)` → `AppShapes.medium`），视觉零变化；有意视觉变更须在 change/commit 中单独说明。

---

## 2. 设计令牌速查表

全部定义于 `core/theme/`，import 前缀 `com.jiaoay.easy_dev_toolbox.core.theme.*`。

### 2.1 间距（`Dimens`）

| token | 值 | 典型用途 |
|-------|-----|---------|
| `space2` | 2dp | 行内紧贴（标题与说明文字间） |
| `space4` | 4dp | 图标与文字间距、细分隔 |
| `space6` | 6dp | 紧凑行间距 |
| `space8` | 8dp | 列表项间距 |
| `space10` | 10dp | 任务卡内行距 |
| `space12` | 12dp | 卡片内边距、表单间距 |
| `space16` | 16dp | 分组间距 |
| `space24` | 24dp | 页面级留白 |
| `screenPadding` | 24dp（= space24） | 各功能页统一内边距 |
| `screenSpacing` | 16dp（= space16） | 页面顶层元素间距 |

### 2.2 图标尺寸（`Dimens`）

| token | 值 | 典型用途 |
|-------|-----|---------|
| `iconSmall` | 16dp | 列表/状态类小图标、复制按钮图标 |
| `iconMedium` | 18dp | 任务卡状态等中图标 |
| `iconLarge` | 20dp | 按钮内大图标 |
| `iconHero` | 40dp | 拖放区主图标、加载/空态主图标 |
| `iconDisplay` | 64dp | 失败态 hero 图标（App.kt InitFailedView；原欢迎页 hero 已随 PageDefault 退役） |

### 2.3 圆角（`AppShapes`，预构造 Shape 实例）

| token | 值 | 典型用途 |
|-------|-----|---------|
| `AppShapes.small` | 6dp | 紧凑卡片（任务卡、工具按钮；B3 密度收敛批 8→6dp） |
| `AppShapes.medium` | 12dp | 面板/拖放区/分组卡 |
| `AppShapes.large` | 16dp | 大容器（对话框） |

### 2.4 透明度（`AppAlpha`）

| token | 值 | 语义（典型用例） |
|-------|-----|---------|
| `bgSubtle` | 0.3 | 弱背景：拖放区常态/拖拽背景、hover 行背景 |
| `bgSubtleAlt` | 0.35 | 次弱背景：输出面板底色、编辑器参数区 |
| `contentDisabled` | 0.38 | 禁用态内容 tint（对齐 M3 disabled content alpha） |
| `borderFaint` | 0.4 | 弱描边：卡片边框；兼用于进度条轨道 |
| `bgTask` | 0.5 | 任务卡背景（tonal 卡容器色） |
| `contentFaint` | 0.6 | 弱内容：次级说明文字、空态图标 |
| `contentSoft` | 0.7 | 软内容：辅助文字、徽标、时间戳 |

用法：`color.copy(alpha = AppAlpha.contentFaint)`。**近值不归并**，按语义选档。

### 2.5 动效（`MotionDurations` + `AppMotion`）

| 常量 | 值 | 典型用途 |
|------|-----|---------|
| `MotionDurations.fast` | 120ms | hover、图标切换等高频轻量反馈 |
| `MotionDurations.standard` | 180ms | 常规状态过渡（面板展开、页面切换） |
| `MotionDurations.slow` | 240ms | 大区域变化（**全应用封顶值**） |
| `MotionDurations.staggerDelay` | 30ms | 入场编排逐项延迟 |

| 工厂函数 | 实现 | 用途 |
|---------|------|------|
| `AppMotion.fast()` | `tween(120)` | 三档时长一一对应 |
| `AppMotion.standard()` | `tween(180)` | 同上 |
| `AppMotion.slow()` | `tween(240)` | 同上 |
| `AppMotion.staggered(index)` | `tween(180, delayMillis = index * 30)` | 入场编排逐项递进，index=0 无延迟 |
| `AppMotion.softSpring()` | `spring(StiffnessMediumLow)` | 尺寸变化类（分组展开），不引入时长概念 |

**两条铁律**：

- **240ms 封顶**：任何动效 duration 不超过 240ms。桌面工具动效只服务于状态认知，不做等待表演。
- **流式输出/日志行零动画**：不施加 `animateContentSize` / `animateScrollToItem` / 逐行动画（见第 5.4 节）。

### 2.6 卡片（`AppCardStyles`）

当前唯一落地档 **tonal 扁平卡**，完整配方（`colors` + `elevation` + `shape` 三件套）：

```kotlin
Card(
    colors = AppCardStyles.tonal(),                                        // surfaceRaised 容器（B3 换源；旧 surfaceVariant.copy(bgTask) 已弃）
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),      // 0 投影，扁平语言
    shape = AppShapes.small,                                               // 紧凑任务卡；面板/分组卡用 AppShapes.medium
) { ... }
```

`0.dp` 是唯一允许的裸 dp（零值全局豁免）。**禁止** `ElevatedCard`、禁止就地拼装 `CardDefaults.cardColors`。

### 2.7 颜色

**主题色槽（`MaterialTheme.colorScheme`，动态生成、深浅自适应）**：

主题由 `AppThemeSpec` 种子色经 MaterialKolor `dynamicColorScheme` 动态生成（10 个预设 + Custom，深浅同源；2026-09-23 大胆化批 Default/Aurum 用 Vibrant 高饱和档）；写页面无需关心生成细节，只消费 `colorScheme` 槽位。新增预设主题见 [core/theme.md](core/theme.md)。

| 槽 | 用途 |
|----|------|
| `primary` | 强调：品牌图标、操作图标、Running 状态、拖放区拖拽态前景/边框 |
| `primaryContainer` | 拖放区拖拽态背景 |
| `tertiary` | 成功态图标 tint（TaskCard） |
| `error` | 错误文案、失败态图标 |
| `onBackground` | 页面标题（PageHeader 内置）、小节标题 |
| `onSurface` | 主内容文字 |
| `onSurfaceVariant` | 次级文字、图标默认 tint、空态文字 |

**表面语义 slot 层（`LocalToolboxSurfaceColors`，2026-09-18 B3 起；R5 对比度批 2026-09-25 弱文字档明暗分治）**：三档文字 `onSurfaceStrong/Mid/Faint`（主/次/辅助）。🔴 **弱文字一律用 slot 不裸 copy**：`onSurfaceFaint` 经 `toolboxSurfaceColors(scheme, dark)` 明暗分治（暗 ×`contentFaintTextDark` 0.72 = 5.9:1 / 亮 ×`contentFaintTextLight` 0.8 = 4.7:1，均 ≥ WCAG AA）——旧 `onSurfaceVariant.copy(AppAlpha.contentFaint)` 恒 0.6 档暗色 4.48:1 / 浅色 2.94:1 双低于 AA（R5 走查 12/20 页复现弱字不可读定罪）。`AppAlpha.contentFaint` 只留背景/装饰语义（拖放高亮环/分组对话框当前项背景/时间线锚点底）。
| `surfaceVariant` | tonal 卡容器基色、hover 背景基色、拖放区常态背景基色 |
| `outline` | 拖放区常态边框 |

**语义扩展色（`LocalEasyToolboxColors`，主题系统未覆盖的场景色）**：

```kotlin
val toolbox = LocalEasyToolboxColors.current
toolbox.success            // 成功/在线状态（如「已复制」反馈 tint）
toolbox.successContainer   // 成功浅底
toolbox.warning            // 警告/多设备提醒
toolbox.warningContainer   // 警告浅底（状态容器条 Warning 槽）
toolbox.info               // 提示信息（DS-01 · 重设计批 0 Info 槽对补齐——StatusBanner Info 槽正源）
toolbox.infoContainer      // 提示信息浅底（状态容器条 Info 槽）
toolbox.aiAccent           // AI 语义强调（恒紫——角标/紫块/状态栏 AI 段；见下）
toolbox.aiAccentContainer  // AI 浅底（紫块容器）
toolbox.consoleBg          // 日志/终端控制台背景
toolbox.consoleText        // 控制台默认文本
toolbox.logDebug / logInfo / logWarn / logError   // 日志级别着色
toolbox.floatShadow      // 浮层软影色（自绘 appFloatShadow——浮层唯一投影语言；elevation 阴影 Linux GPU 硬灰环，2026-09-25 弃用）
```

页面**禁止硬编码** `Color(0xFF...)`。语义色与种子色体系无关，由 `AppTheme` 按深浅模式提供（`Light/DarkEasyToolboxColors`）。

### 2.8 排版（`MaterialTheme.typography`）

| 样式 | 用途 |
|------|------|
| `headlineSmall` | 页面标题（PageHeader 内置） |
| `headlineMedium` | 大标题（欢迎页已退役，保留档位） |
| `titleSmall` | 分组/小节标题 |
| `bodyLarge` | 副标题（欢迎页已退役，保留档位） |
| `bodyMedium` | 主内容文字、拖放区标题 |
| `bodySmall` | 错误提示、日志行 |
| `labelSmall` | 状态描述、次级说明、时间戳 |

代码/路径/正则/日志/时间戳类等宽文本一律 **`DesktopTypography.mono`**（12sp 系统等宽——2026-09-21 P3 起全仓 40 处字面量已收口；按原 slot 差异用 `mono.copy(fontSize=…, fontWeight=…)` 值等价）。🔴 新代码禁写 `fontFamily = FontFamily.Monospace` 字面量。

---

## 2.9 四舱布局与响应式（调查工作台 S2-S7 终态 · 2026-09-22；2026-09-23 空间挪窝批二起 SpaceBar 迁智能体中心第四签、主布局降为四舱）

**四舱结构**（`PageHome` 根布局——融合案 D：dock 左 / 主区 tab 容器 / inspector 右 / 状态栏底）：

```
BoxWithConstraints {                  ← provide LocalWindowSizeClass（classifyWindowSize 唯一消费点）
Column {
  Row {
    PageHomeMenu 56dp                 ← 舱一：dock（降级静态图标集：家/智能体/设备/桥接/工具/命令入口）
    WorkspaceTabs weight 1f          ← 舱二：主区 tab 容器（tab 条 34dp + PageHomeContent 分发；
    │                                   零固定 tab——纯 openTabs 投影，Auto-hide 空集隐藏）
    SidePanelHost                    ← 舱三：inspector（窗口最右缘；Start/End 贡献纵排合并渲染）
  }
  AppStatusBar 26dp                   ← 舱四：只读状态层（controlCompact 复用）
}
StatusHostOverlay()                   ← 全局浮层 Toast（渲染壳；状态面 :core StatusSignals）
CommandPalette                        ← Ctrl+K 浮层（S3：带参命令 + HUD + 空态智能）
```

🔴 **底面板已退役**（2026-09-22 S2 维护者裁定）：BottomPanel/PanelHost/`panel_*` 四
key/Ctrl+J·Ctrl+Alt+J 热键一并删除，不做改义保留——运行中会话可见性由 dock RUN
徽标（navActivity）+ 主区 tab 直达承接。

**响应式四档**（`core/theme/Breakpoints.kt`——阈值走 `Dimens.breakpoint*`）：

| 档 | 宽度 | inspector |
|----|------|-----------|
| Compact | < 1024 | 强制窄视图（`sidebarRailWidth` 48dp icon 轨） |
| Medium | 1024–1440 | 展开面板 280dp |
| Expanded | 1440–1760 | 220–360 可拖 |
| Wide | > 1760 | 同上 |

**inspector 双形态**（`SidePanelHost`，受控组件）：
- **展开面板**：贡献内容纵排（多贡献间 HorizontalDivider 分节）；splitter 在面板**左缘**；背景 `surfaceRaised`
- **窄轨**：`sidebarRailWidth` 48dp icon 轨，每贡献一个 icon 钮（tooltip = label）
- **状态由宿主持有**（`PageHome` 全局 `inspectorWidth`/`inspectorRailMode` 单键），落盘经 `EasyDataManager` `inspector_width` + `inspector_rail`（500ms 防抖）——旧 per-page `sidebar_<page>_*` 键已随页面导航退役（死数据不读不删）

**inspector 槽位契约**（`:core` `ToolPageRegistration.sideBar: List<SideBarContribution>`，默认空表零侵入）：

```kotlin
data class SideBarContribution(
    val slot: SideBarSlot = SideBarSlot.Start,   // 同页多贡献纵排序（不再决定渲染位置）
    val icon: String = "",                        // 窄轨 icon（Material Symbol 蛇形名；空 = 不进窄轨）
    val label: String = "",                       // 窄轨 tooltip 文案
    val content: @Composable () -> Unit,
)
```

🔴 **生命周期分工**：inspector 槽位 = **页面作用域**（切页销毁重建，VM 经 `viewModel{}` 与页内同实例可达——窗口级 VMStore 驻留下 VM 本身切页存活）；全局持续监控流（桥接日志等）经裁定**留页内**。

🔴 **壳层不渲染标题**（R 批裁定）：各贡献内容自带小节标题（ShellHistoryPanel「历史记录」/ Workbench「分析输入」+「**分析记录**」——XA-22 译名，UI 现行串见 LogWorkbenchPage / Quest「历史会话」）——壳层 HeaderRow 只放收起钮，label 仅用于窄轨 tooltip，防三层标题重复。

**双语义色（融合案 D 裁决 7）**：工具/证据色跟 scheme 走（琥珀系）= `MaterialTheme` 派生色；AI 色独立 token 恒紫 = `LocalEasyToolboxColors.current.aiAccent`（见 2.7 与 [core/theme.md](core/theme.md)「AI 语义色」）。

---

## 3. 页面骨架标准

### 3.1 标准功能页模板（直接照抄）

```kotlin
@Composable
fun XxxPage() {
    val viewModel = XxxViewModel.getViewModel()
    val fieldA by viewModel.fieldA.collectAsState()
    val tasks by viewModel.tasks.collectAsState()

    PageScaffold(
        title = "工具页标题",
        actions = {
            // 标题栏右侧操作区（可选）：IconButton + Dimens.iconMedium 图标
            IconButton(onClick = viewModel::clearTasks) {
                Icon(
                    imageVector = Icons.Default.ClearAll,
                    contentDescription = "清空列表",
                    modifier = Modifier.size(Dimens.iconMedium),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        // 内容区一：表单输入
        OutlinedTextField(
            value = fieldA,
            onValueChange = viewModel::onFieldAChange,
            label = { Text("字段标签") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // 内容区二：文件输入
        FileDropZone(
            title = "拖入或点击选择 .txt 日志文件（支持多选）",
            dragTitle = "松开以开始过滤",
            desc = "支持同时拖入多个文件批量过滤",
            onFilesSelected = viewModel::addFiles,
            multiple = true,
            accepts = { it.extension == "txt" },
        )

        // 内容区三：任务列表（items 必须带稳定 key）
        if (tasks.isNotEmpty()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(task = task, onOpenFolder = { viewModel.openFolder(it) })
                }
            }
        }
    }
}
```

要点：

- 骨架 = `PageScaffold`（内部已装 `screenPadding` 内边距 + `screenSpacing` 顶层间距 + `PageHeader` 标题栏），**不要**再手写外层 `Column + padding`。
- 需要页面级滚动时把 `verticalScroll(rememberScrollState())` 经 `modifier` 传入 `PageScaffold`；Snackbar 等浮层放骨架之外。
- **吸顶头（`headerSticky = true`）**：标题栏不随内容滚动、content 获得有界高度自行滚动——此时 `modifier` 不得再带 `verticalScroll`（滚动放 content 内部），固定头内容（如页内 Tab 行）直接排在 content 前即可；默认 false，既有页面零行为变化。吸顶头的真价值 = **页头恒定可见 + content 恰满视口**（fillMaxSize 根下右舱/内容区本就能滚——「不开吸顶就滚不动」是误解；选它是为了长内容滚动时标题与固定头行不滚出视口）。吸顶头现有三个在用用例：① `LarkBridgePage`（飞书桥接页，M6/M7 重写 + 批 5 改版）——`PageScaffold(title = "飞书桥接", headerSticky = true, bottomPenetration = true)` + 页内 4 页签（驾驶舱/配置/日志/统计——「路由」页签已随 P3 routes 退役删除，见 lark-bridge.md 页面结构节，`SegmentedControl` 哑光胶囊），`OperationBar` 与 `SegmentedControl` 作为固定头排在 content 前，页签槽填充 = `AnimatedContent(modifier = Modifier.weight(1f), ...)`（**weight 挂 AnimatedContent 自身**，直接子 of 吸顶 Column 才生效），各页签分支根 `Modifier.fillMaxSize().verticalScroll(...).padding(bottom = LocalScaffoldGlassInsets 底值)`——🔴 分支根上挂 weight 会经隐式接收者链泄漏到外层 ColumnScope（编译通过）但不被 EnterExit Layout 消费、布局全部失效（2026-09-26 重叠批二审计定罪，页签内容 wrap 萎缩不填槽），`PageScaffold` 的 `modifier` **不带** `verticalScroll`；② `McpExposePage`（MCP 服务页，2026-09-20 P5）——`headerSticky = true` 未开穿透，滚动容器挂法同①但**不**消费底部玻璃带让位（包裹层全让位 = 旧几何）；③ `AgentCenterPage`（智能体中心聚合页，批一·组 B）——同②；其 voice 签滚动配方见下条。
- **🔴 AnimatedContent 单根纪律（2026-09-26 重叠批二全局约定）**：`AnimatedContent` 的 content lambda **必须单根发射**——CMP 1.12 的 `AnimatedEnterExitMeasurePolicy` 尾行 `placeables.fastForEach { it.place(0, 0) }` 把多根 placeable 全部叠放在 (0,0)，多根即同位叠放（2026-09-15 UsageStatsPanel、2026-09-26 ToolsPage 两次真机「内容重叠」事故同一根因）。每个 when 分支包单根 `Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space12))`（正例：`LarkBridgePage` / `ToolsPage` / `UsageStatsPanel`）。API 行为基线由 `arch/AnimatedContentSingleRootGuardTest` 钉死（多根叠放 / 单根流式两用例）。
- **🔴 聚合页分支滚动陷阱（AgentCenterPage 先例）**：滚动**只包需要滚的分支**、不包容器——容器级 `verticalScroll` 会使 AnimatedContent 全分支无界化，分支内无 `heightIn` 的 LazyColumn（`ChatMessageList` / `SpaceBar`）在无界约束下测量直接 `IllegalStateException`。正解：`2 -> Column(Modifier.verticalScroll(rememberScrollState())) { VoicePageContent(embedded = true) }`——只有 voice 分支无界，其余分支保持有界高度契约。
- 现成范例：`page/logtag/LogTagFilterPage.kt`、`page/timestamp/TimestampPage.kt`、`page/filesplit/FileSplitterPage.kt`。

### 3.2 何时不用 PageScaffold（有意例外）

| 例外 | 原因 |
|------|------|
| `page/shelltools/ShellToolsPage.kt` | 紧凑工作台（工具树在上占主区 + 底部输出台——2026-09-25 空树归因批 v2 恢复原版语序；历史面板在 inspector End 侧栏），信息密度优先，Dense 档不吃 24dp 页面留白 |

（`page/home/PageDefault.kt` 欢迎页已随调查工作台 S2 退役删除——启动直达工作台家。）
新页面**默认必须**走 `PageScaffold`；只有信息密度或构图与「标题 + 表单/列表」结构本质冲突时才自建骨架，且裸 dp 需按有据保留登记。

### 3.3 值等价迁移（改老页面时）

遇到老代码里的 `8.dp` / `RoundedCornerShape(12.dp)` / `alpha = 0.5f`，逐处替换为对应 token 且数值相等（查上方速查表）；不趁机「顺手调优」——视觉变更须单独提案。

---

## 4. 共享组件 API 手册

### 4.1 PageScaffold（页面骨架）

```kotlin
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    density: PageDensity = PageDensity.Comfortable,
    headerSticky: Boolean = false,
    bottomPenetration: Boolean = false,   // 批 5-b：吸顶页底部真穿透 opt-in（见 3.1 吸顶头节）
    scrollable: Boolean = false,
    statusSlot: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
)
```

内部：`Column(fillMaxSize().padding(密度档 ⊕ LocalScaffoldGlassInsets), spacedBy(density.spacing))` + `PageHeader`（批 3.7-a 起让位 = 密度边距 + 玻璃带让位逐边相加；`bottomPenetration = true` 的吸顶页底部让位移除、消费点下沉到页内滚动容器）。内容区恒满可用宽（2026-10-20 维护者裁定废除 Wide 档「限宽 1200dp + 居中」——大屏差异化 UI 未做之前居中悬浮即缺陷；差异化留待真设计）；吸顶 weight(1f) 容器带 `spacedBy(density.spacing)`、非吸顶由外层 spacedBy 承载——content 兄弟间距恒 = density.spacing（由 `arch/SegmentedGapGuardTest` 像素钉死）。用法见 3.1。自建骨架的判断见 3.2。

### 4.2 PageHeader（标题栏，一般不单独用）

```kotlin
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
)
```

`headlineSmall` + `onBackground` 大标题，右侧 `actions`。已内置于 `PageScaffold`；只在完全自定义骨架的标题栏场景直接引用，保持标题样式全应用一致。

### 4.2a SectionCard（分组卡，tonal 真卡）+ CardHeader（卡内小节头）

```kotlin
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    collapsible: Boolean = false,
    initialExpanded: Boolean = true,
    tonal: Boolean = true,                    // true = tonal 扁平卡容器（6.3 配方）
    contentPaddingVertical: Dp = Dimens.space16,
    contentPaddingHorizontal: Dp = Dimens.space20,   // DS-05 · 批 3 批升：双参对齐 TonalCard（垂直 16 × 水平 20）
    headerTrailing: (@Composable () -> Unit)? = null,  // 折叠标题行尾、旋转箭头前的附加槽（IA 批①：「N 项 · 已改 M · 错误 K」徽标）
    expandedOverride: Boolean? = null,        // 受控展开态（LB-18 · 批 9：非空 = 覆盖内部自持态）
    onExpandedChange: ((Boolean) -> Unit)? = null,     // 展开态变化回调（收起动作回传 = 外部清 override 防死锁）
    content: @Composable ColumnScope.() -> Unit,
)

@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,   // titleSmall 级显式传入
    trailing: (@Composable () -> Unit)? = null,
)
```

- `SectionCard`：分组卡唯一形态——标题（titleMedium）+ 可选描述 + 常开/折叠内容，整体落在
  `AppCardStyles.tonal()` + 0 elevation + `AppShapes.large` 真卡容器上（2026-09-10 起默认
  `tonal = true`；旧裸分组形态传 `tonal = false`）。折叠箭头 iconMedium + fast 档旋转。
  展开态自持 `rememberSaveable`（受控场景传 `expandedOverride` + `onExpandedChange`）。
  **新分组一律用它，不要手写 Card+标题+箭头拼装。**
- `CardHeader`：卡内小节头（`Row{ Text(weight(1f)), trailing }` 配方）；`style` 参数化后可承载
  titleSmall 级标题，替代调用点手写标题行。

### 4.2b TonalCard（tonal 扁平卡统一包装）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/TonalCard.kt`

```kotlin
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.large,                       // DS-05 · 批 3：12→16dp（DL-M05 内容卡默认）
    contentPaddingVertical: Dp = Dimens.space16,          // DS-05：单值 space12 → 双参 16×20（DL-M06 卡内边距）
    contentPaddingHorizontal: Dp = Dimens.space20,
    contentSpacing: Dp = Dimens.space8,
    showBorder: Boolean = true,                           // 嵌套场景内层卡传 false 不描边
    content: @Composable ColumnScope.() -> Unit,
)
```

**契约：新卡片一律走本组件**（配方变更只改一处）；需要自定义配色/形状的特殊卡仍可直接
`Card + AppCardStyles.tonal()`（token 本身不受影响）。与 `SectionCard` 的分工：**SectionCard =
带标题/描述/折叠态的分组卡**；**TonalCard = 无标题的通用内容卡**（报告因果链卡/提示卡内容
承载/表单分组容器等）。

- 配方本体：`AppCardStyles.tonal()` + 0 elevation + 默认 `AppShapes.large` +
  1dp 发丝描边（DS-02 · 批 0：`glassStrokeHairline × borderHairline` 明暗分治——
  「有边无形」语言，34+ 消费点零改动自动升级）；
- 默认内容排版：`Column(padding 垂直 space16 × 水平 space20, spacedBy(space8))`；
- 嵌套约定：卡内再放卡时内层传 `showBorder = false`（双描边堆叠发灰）。


### 4.3 StatusView（结果四态分发）

```kotlin
@Composable
fun <T> StatusView(
    modifier: Modifier = Modifier,
    result: JResult<T>?,
    contentView: @Composable (data: T) -> Unit,
)
```

按 `JResult`（`entity/JResult`）分发：

| result | 渲染 |
|--------|------|
| `JResult.Loading` 或 `null`（数据未就绪） | 居中 `CircularProgressIndicator`（`iconHero` 尺寸） |
| `JResult.Empty` | 居中 `Icons.Outlined.Inbox`（`iconHero`，`LocalToolboxSurfaceColors.current.onSurfaceFaint`——R5 迁 slot）+ 「暂无数据」 |
| `JResult.Failed` | `bodySmall` + `error` 色文案（`msg ?: code.desc`） |
| `JResult.Success<T>` | `contentView(result.data)` |

```kotlin
StatusView(result = uiState) { data ->
    LazyColumn { /* 成功态内容 */ }
}
```

何时自建：只有当空态/错误态需要**页面级定制文案与动作**（如引导跳转）时，才自行分支渲染，但仍复用第 6.2 节的图标 + 文案组合模式。

### 4.4 TaskCard（异步任务卡）

```kotlin
data class TaskUi(val id: String, val fileName: String, val status: TaskUiStatus)

sealed class TaskUiStatus {
    data object Running : TaskUiStatus()
    data class Success(val outputPath: String) : TaskUiStatus()
    data class Error(val message: String) : TaskUiStatus()
    data class Cancelled(val partialOutput: String? = null) : TaskUiStatus()
}

@Composable
fun TaskCard(
    task: TaskUi,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDeepAnalyze: ((String) -> Unit)? = null,   // 成功卡「深度分析」情境按钮（P4-C2）
    onAiSummarize: ((TaskUi) -> Unit)? = null,   // 成功卡「AI 整理」情境按钮（P4-S）
)
```

文件处理类任务（XLog 解码 / 文件分割 / Tag 过滤 / 语音转写共用）的标准卡片：状态图标（进度圈 / CheckCircle / Error，`iconMedium`，tint 走 primary/tertiary/error 并 `animateColorAsState` + `Crossfade` 均为 fast 档）+ 文件名 + 状态描述 + 成功后「打开文件夹」按钮 + 两个可选情境按钮槽。**新文件处理页直接复用，不要重写任务卡。**

两个可选槽（2026-09-20 P4 批新增，默认 null 不渲染——存量四胞胎调用方零迁移）：
- `onDeepAnalyze`：产物「深度分析」→ 跳日志工作台（XLog 解密页接线——:core `DeepAnalyzeHook` 中立槽经宿主 AppViewModel wire）；
- `onAiSummarize`：产物「AI 整理纪要」（语音转写页接线——审阅卡形态）。

```kotlin
items(tasks, key = { it.id }) { task ->
    TaskCard(task = task, onOpenFolder = { viewModel.openFolder(it) })
}
```

### 4.5 FileDropZone（文件拖放/点击选择）

```kotlin
@Composable
fun FileDropZone(
    title: String,                                  // 常态标题
    dragTitle: String,                              // 拖拽悬停时标题
    desc: String,                                   // 底部说明
    onFilesSelected: (List<File>) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.FileUpload,   // 主图标（iconHero）
    height: Dp = 140.dp,                            // 有据保留默认值
    multiple: Boolean = true,
    accepts: (File) -> Boolean = { true },          // 扩展名过滤
)
```

内部完成 DragData → File 解析与 AWT 文件选择器，页面零事件处理；拖入/离开三色渐变（border/背景/前景，fast 档）。任何「需要用户提供文件」的页面都用它，不要自建拖放。

```kotlin
FileDropZone(
    title = "拖入或点击选择 .txt 日志文件（支持多选）",
    dragTitle = "松开以开始过滤",
    desc = "支持同时拖入多个文件批量过滤",
    onFilesSelected = viewModel::addFiles,
    multiple = true,
    accepts = { it.extension == "txt" },
)
```

### 4.6 CopyButton / CopyIconButton / rememberCopyFeedbackState（复制交互）

```kotlin
@Composable
fun CopyButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
)

@Composable
fun CopyIconButton(
    textProvider: () -> String,          // 惰性求值：点击瞬间才调用
    contentDescription: String? = null,  // IconButton 无文字标签，应提供
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

@Composable
fun rememberCopyFeedbackState(): CopyFeedbackState   // state.copy(text) / state.isCopied
```

统一行为：写剪贴板 + 1.5s「已复制」反馈（ContentCopy ↔ Check 图标 `Crossfade` fast 档，成功态 tint = 语义 `success` 色），连点自动重置计时。选型：

| 场景 | 用 |
|------|-----|
| 复制静态/小文本，有文字标签 | `CopyButton(text = main)` |
| 工具栏图标形态；流式大文本（点击时才全量格式化） | `CopyIconButton(textProvider = { buildOutput() })` |
| 文本需异步生成（ViewModel 回调导出），反馈由自有图标呈现 | `rememberCopyFeedbackState()` 自持句柄 |

### 4.7 HoverableRow（可悬停行）

```kotlin
@Composable
fun HoverableRow(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,       // hover 背景裁剪形状，与行圆角对齐
    actionContent: @Composable RowScope.(isHovered: Boolean) -> Unit = {},
    content: @Composable RowScope.() -> Unit,
)
```

两类 hover 反馈（均 fast 档）：行背景渐入（`surfaceVariant.copy(AppAlpha.bgSubtle)`，覆盖含内边距的整行）+ 行尾操作区渐显（固定占位、仅 alpha 0↔1，行宽不跳动）。

**关键约定**：alpha=0 时操作区仍可命中点击，`actionContent` 回传 `isHovered`，调用方必须 `enabled = isHovered` 保持「不可见即不可点」：

```kotlin
HoverableRow(
    modifier = Modifier
        .fillMaxWidth()
        .clip(AppShapes.small)
        .clickable { onSelect(entry) }
        .padding(horizontal = Dimens.space12, vertical = Dimens.space8),
    shape = AppShapes.small,
    actionContent = { isHovered ->
        IconButton(onClick = { onDelete(entry) }, enabled = isHovered) {
            Icon(Icons.Default.Delete, contentDescription = "删除",
                modifier = Modifier.size(Dimens.iconSmall))
        }
    },
) {
    Text(entry.title, style = MaterialTheme.typography.bodyMedium)
}
```

### 4.7a AppSwitchRow（开关行）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/AppSwitchRow.kt`

```kotlin
@Composable
fun AppSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    rowClickable: Boolean = true,      // true = 整行点击切换（indication = null，主反馈交给 hover 背景）
    hoverable: Boolean = false,        // 2026-09-10 新增：true 时经 HoverableRow 渲染 hover 背景
    hoverShape: Shape = RectangleShape,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
)
```

行为要点：

- `label`（+ 可选 `supportingText`）+ Switch 的收敛组件，替代各页手写的「Row + Text + Switch」样板
- `rowClickable = true` 时整行点击切换（默认），false 时仅 Switch 本体可操作
- **hover 三参（`hoverable`/`hoverShape`/`labelStyle`）为 2026-09-10 新增**，默认值 = 原直排形态（不进 `HoverableRow` 组合）；`hoverable = true` 时 hover 背景以 `hoverShape` 裁剪渐入（fast 档 + `AppAlpha.bgSubtle`，底座 `HoverableRow` 属组件内部实现）
- 行修饰（clip/padding/border）由调用方经 `modifier` 传入，组件不内置

消费方：`PageSettings`（日志存储/开发者选项 ×2，hover 形态）、`ConfigFieldRenderer`（Switch 编辑器 ×1）、`RouteRuleEditor`（×6）。

### 4.7b AppConfirmDialog（通用确认对话框）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/AppConfirmDialog.kt`

```kotlin
@Composable
fun AppConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isDangerous: Boolean = false,      // true = 确认按钮 error 色
    confirmLabel: String = "确认",
    dismissLabel: String = "取消",
)
```

「标题 + 说明 + 确认/取消」手写样板（4+ 处）的收敛。**危险确认统一约定**：删除/清空类操作一律 `isDangerous = true`（确认键 `MaterialTheme.colorScheme.error` 红色），确认键文案用动词（「删除」「清空」）而非泛化「确认」。

消费方：`ShellHistoryPanel`（清空历史，confirmLabel="清空"）、`ShellToolsChildNode`（删除自定义命令，confirmLabel="删除"）、`ShellToolsParentNode`（删除分组，confirmLabel="删除"）。

### 4.7c EmptyStateView（通用空态 · 四要素版，2026-09-21 P3 扩参）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/EmptyStateView.kt`

```kotlin
@Composable
fun EmptyStateView(
    text: String,                                  // ① 系统状态一句短文案（必选）
    modifier: Modifier = Modifier,
    icon: String = MaterialSymbols.INBOX,          // 蛇形名（批 9b）
    fillParent: Boolean = true,
    learningHint: String? = null,                  // ② 学习提示：此处将来显示什么
    actionLabel: String? = null,                   // ③ 关键任务直达路径（与 onAction 成对）
    onAction: (() -> Unit)? = null,
    processing: Boolean = false,                   // ④ true 时整块不渲染（批量处理中禁空态闪现）
)
```

空态唯一语言（NN/g 四要素）：三套旧语言（本组件旧单要素 / `StatusView.Empty` / 裸 bodySmall 文本）全部收口。🔴 **要素④强制**：批量拖入后首卡建成前禁止显示「还没有任务」（NN/g 点名的 particularly harmful 闪现）。后三要素可选（learningHint 空态引导、actionLabel+onAction 直达按钮），首要素恒备。

消费方：`UsageStatsPanel` ×2、`SenderDetailPanel` ×2（B4 拆分独立二级页）、`BridgeStatusPanel`、`CockpitPanel`、`LogConsolePanel` ×2（feature-larkbridge）、`ShellHistoryPanel`、`TaskListSection` 空态分支（learningHint 固定「拖入文件后自动开始处理」）。

### 4.7h AppButton（进行中反馈唯一规范，2026-09-21 P3 新增）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/AppButton.kt`

```kotlin
enum class AppButtonKind { Primary, Secondary, Destructive }

@Composable
fun AppButton(
    label: String,
    busy: Boolean,                        // true = 按钮内 progressInline 圈 + 进行时措辞 + disabled
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busyLabel: String? = null,            // 进行时措辞（默认 "$label 中…"）
    kind: AppButtonKind = Primary,
    enabled: Boolean = true,
    disabledReason: String? = null,       // 非空 → 置灰 + tooltip 说明原因（🔴 禁止静默置灰）
    cancel: (() -> Unit)? = null,         // 非空 + busy → 旁侧统一停止钮（STOP_CIRCLE + error）
    tooltipPositioning: TooltipAnchorPosition? = null,  // 贴窗缘落位（投屏窗顶栏等）显式给值——
                                          // plain 默认定位器无水平钳制，长置灰原因/busy 文案越出窗缘
)
```

**响应时间三阈值（NN/g）唯一形态表**：

| 时长 | 形态 |
|------|------|
| < 100ms | 零反馈（不闪圈——不进本组件） |
| 100ms–1s | `AppButton(busy = true)` |
| 1–10s | 同上 + `cancel` 停止钮 |
| > 10s 可知总量 | determinate 进度（TaskProgressRow 规划态，尚未落地——现状走模型下载式 LinearProgressIndicator） |
| > 10s 不可知总量 | 计数式滚动反馈（🔴 禁止伪造百分比） |

🔴 **唯一例外（登记）**：`ChatComposer` 发送↔停止翻钮保留（输入框内 affordance，chat 惯例优先）；`OperationBar`（feature-larkbridge）busyAction 配方保留（2026-09-11 真机批验证）。两者停止态视觉与本组件同源（STOP_CIRCLE + error tint）。

**R3 基建修复（2026-09-25 组件一致性批 B1）**：`modifier` 参数经 `Modifier.weight(1f, fill = false)` 类场景必须**直达 `AppButtonInner` 本体**——旧实现在中间包装层吃掉 weight/fill 语义，`fillMaxWidth` 场景按钮只占内容宽。**E2 收口现状（R3 后）**：蒸馏执行/语音下载重试/Shell 管理钮/报告页设置浏览等 ~15 处主操作均走本组件；置灰必带 `disabledReason`（禁静默置灰铁律由本参数承载）。

### 4.7i AppList（已退役 · R3 组件一致性批 E5 死组件清除，2026-09-25）

`widget/AppList.kt` 已删除——R3 摸查定罪「零调用死组件」：规范宣称五处手拼长列表
（LogConsole/ShellOutput/ShellDebug/ShellHistory/SpaceBar）将收编 AppList，实际从未
发生（收编会引入行为变更风险且收益仅是「规范宣称成真」）。各长列表维持页内实现
+ 既有容量约定：Shell 输出有界封顶 5000 / 桥接日志台环形 1000 / Workbench run 域
清单 30（`Dimens.listBoundedMax/listRingMax/listDomainMax` token 保留）。子件
`BottomFollowScrollEffect` 独立保留（`BottomFollowScroll.kt`）。
### 4.7j interactions（2026-09-21 P4 键盘与焦点体系；2026-09-27 审计批收编）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/interactions/`

| 组件 | 签名 | 职责 |
|------|------|------|
| `SubmitKey.kt` | `Modifier.onEnterKey(allowMultiline = false) { submit() }` | Enter 语义唯一配方（onPreviewKeyEvent + KeyDown + NumPadEnter）；多行时 Ctrl+Enter 提交、裸 Enter 换行。🔴 页面不得自写 Enter 处理 |

> 🔴 2026-09-27 审计批（62ef927）：`FocusRing.kt`（FocusRingBox）与 `ListKeyboard.kt`（listKeyNavigation）
> 已删除——全仓零引用死组件（CommandPalette 自持三态机键盘处理、Material 控件自带焦点态），
> 与 R3 E5 的 AppList 同款「零调用死组件」裁定。`Modifier.requestFocusOnEnter` 同批删除（SubmitKey.kt 内）。

**Enter 提交矩阵**（登记例外）：

| 场景 | 行为 |
|------|------|
| 单行输入 | `onEnterKey { submit() }`（Enter 提交并吞掉） |
| 多行输入 | `onEnterKey(allowMultiline = true) { submit() }`（Enter 换行、Ctrl+Enter 提交） |
| `ChatComposer` | Enter 发送 / Shift+Enter 换行（chat 惯例，自持 onPreviewKeyEvent 同配方） |
| `TagsField` | Enter 确认标签（自持） |
| 时间戳页 | 300ms debounce + Enter 立即求值 |
| `CommitTextField` | 失焦提交（设置项唯一入口） |
| `ShellOutputPanel` | Shift+Enter 留行内 / 提交后保留末行（运行台惯例，域内例外；2026-09-27 审计批补登记） |

### 4.7k StatusSignals / StatusHostOverlay（全局浮层反馈通道；S5b 状态面下沉 :core）

状态面源码：`core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/runtime/StatusSignals.kt`
（`:core`——feature 可见，S5b 完成通知的下沉通道）
渲染壳源码：`composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/StatusHost.kt`

```kotlin
object StatusSignals {
    val toast: StateFlow<ToastEntry?>              // 当前 toast（渲染壳消费）
    fun show(text: String)                         // 成功/确认类：SUCCESS_BANNER_MS(5s) 自动消退 + hover 暂停
    fun showUndo(text: String, onUndo: () -> Unit)  // L1 撤销：UNDO_WINDOW_MS(6s) 倒计时条 + 撤销钮
    fun dismiss()                                   // 手动关闭（×）
    fun setHovered(hovered: Boolean)                // hover 冻结回喂（渲染壳调用）
}
@Composable fun StatusHostOverlay()                 // PageHome 挂载（右下角，空时零渲染；计时器在渲染壳 LaunchedEffect）
```

全仓唯一 Snackbar（ShellDebugPage）已迁入。渲染位置：状态栏之上右下浮层，不遮挡内容。



### 4.7d CommitTextField（失焦提交输入框）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/CommitTextField.kt`

```kotlin
@Composable
fun CommitTextField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,        // 失焦时上抛终值，调用方比较短路（值未变不落盘）
    modifier: Modifier = Modifier,
    onValueChange: ((String) -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
)

@Composable
fun CommitNumberField(                 // 整型变体：数字键盘、非法终值不提交并回退
    label: String, value: Int, onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier, isError: Boolean = false, supportingText: String? = null,
)
```

行为契约：每键只更新内部编辑态（零外抛）；**失焦提交**——「曾获得焦点且离开」才上抛（防虚拟失焦集体提交）；外部 `value` 仅初值/重置语义，聚焦中不劫持输入。

消费方：`ConfigFieldRenderer`（Text/Secret/Multiline 编辑器基座）、`RouteRuleEditor`、`OperationBar`（feature-larkbridge）。

### 4.7e StatusBanner（通用状态容器条，2026-09-12 新增）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/StatusBanner.kt`

```kotlin
@Composable
fun StatusBanner(
    status: StatusBannerStatus,      // Success / Warning / Error / Info
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,   // 行尾槽（如「知道了」按钮）
    details: (@Composable ColumnScope.() -> Unit)? = null,   // 明细槽（2026-09-12 评审修复新增）
)
```

6.4 节「容器色 + 前缀图标 + 文字」三层语言的组件化（收敛时删掉了 OperationBar 私有 ResultBanner 与 SecurityHintsCard 私有四件套）。四语义槽三色成套：Success = successContainer/CheckCircle、Warning = warningContainer/WarningAmber、Error = errorContainer/Error、Info = **infoContainer/info**（DS-01 · 重设计批 0：Info 槽对补齐——摘除旧 warningContainer 弱黄借用；图标/文字 = colors.info，亮 #0B57D0 / 暗 #64B5F6，见 [core/theme.md](core/theme.md)「语义扩展色」）。图标经批 9b 迁移全部为 Material Symbol 蛇形名（CHECK_CIRCLE/WARNING_AMBER/ERROR/INFO——AppSymbolIcon 渲染，非 Icons.Default 位图类）。

`details` 明细槽：内容渲染在容器色内、标题行下方（行距 space2）——多行明细（如校验失败逐条列出）**必须经该槽收进条内**，不得散落在条外页面背景上（评审修复：LarkBridgePage 校验明细曾因无此槽掉到条外，被 spacedBy(space12) 逐行拉开、视觉分组尽失）。

消费方（2026-09-12 收敛后）：OperationBar ×5（启动结果/预检/banner/dryRun/需重启）、SecurityHintsCard ×6、LarkBridgePage 校验失败条、BridgeStatusPanel Blocked 条、ConfigCheckPanel 结论条、ShellToolsPage 主配置错误条（trailing 关闭按钮）。

**新代码直接用它，不要再手写 Surface 容器条配方。**

### 4.7f ScrollableListRow / VerticalScrollbarSlot（桌面滚动条，2026-09-12 新增）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/ScrollableListRow.kt`

```kotlin
@Composable
fun ScrollableListRow(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    showScrollbar: Boolean = true,
    content: @Composable () -> Unit,          // 内放 LazyColumn(state = listState, ...)
)

@Composable
fun VerticalScrollbarSlot(                     // 独立挂点（布局不便换壳时）
    listState: LazyListState,
    modifier: Modifier = Modifier,
)
```

Compose Desktop 的 LazyColumn 默认不渲染滚动条；长列表（日志台/输出面板/历史面板）一律配右缘细滚动条：宽度 `Dimens.scrollBarWidth`（8dp）、常态 `contentFaint` 低存在感、hover 渐显 outline（`ScrollbarStyle.hoverDurationMillis = 300` 为官方组件内置 hover 色过渡，非业务动效）。滚动条非动画件，不触碰流式零动画铁律；贴底跟随（4.8）语义不受影响。

🔴 **heightIn 弹性容器接入坑（2026-09-12 评审修复）**：滚动条的 `fillMaxHeight` 在 `heightIn(min, max)` 上界弹性容器内会把 Row 交叉轴钉死到 max（fillMax* 同型机制，参见 NavigationRail fillMaxWidth 教训），内容少时背景块塌缩、与滚动条割裂。弹性高度场景（LogConsolePanel）接入时 **LazyColumn 自身须补 `fillMaxHeight`**、显式接受恒定高度；固定高度场景（外层 weight/Surface.height 钉死，如 ShellOutputPanel/ShellDebugPage/ShellHistoryPanel）无此问题。

### 4.7g MultiChoiceField（多选 chips 字段，2026-09-20 Quest 域新增）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/MultiChoiceField.kt`

```kotlin
@Composable
fun MultiChoiceField(
    label: String,
    options: List<String>,
    selected: Set<String>,          // 选中集（与 ChoiceField 的单值 selected 相对）
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    labels: Map<String, String> = emptyMap(),
)
```

ChoiceField 的 FilterChip 流分支平移多选语义（`Set<String>` toggle 进出）；选中项
带 CHECK 前导图标（success 色）。消费方：流程页能力挑选器（能力多选 + AskCard
MultiChoice 字段）。🔴 零白名单：间距 `Dimens`、选中色 `LocalEasyToolboxColors`；
`labels` 声明式文案映射与 ChoiceField 同约定（缺失 label 以 raw 值 fallback）。

消费方：LogConsolePanel（heightIn 弹性 + LazyColumn fillMaxHeight）、ShellOutputPanel.OutputList、ShellDebugPage 消息列表、ShellHistoryPanel.HistoryList。**新长列表页面直接复用。**

### 4.7l SegmentedControl（页内域切换唯一语言）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/SegmentedControl.kt`

```kotlin
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
)
```

哑光胶囊分段控件（液态玻璃批 5-a；iOS 形态、零动画——Q3 拍板；哑光纪律：页内控件不上玻璃）。**选型规则（P0 一致性批 2026-09-25 收口，治理「三皮并存」）**：

| 场景 | 用 |
|------|-----|
| **页内域/子页签切换**（LarkBridge 五签、AgentCenter 四签） | `SegmentedControl`——全应用唯一形态；🔴 M3 `TabRow`（下划线页签）存量已清零、不再新增 |
| 表单内联枚举（设置页外观模式、时间戳输出格式、Shell 历史排序档） | `SingleChoiceSegmentedButtonRow`（M3 表单语言）或 `ChoiceField` |
| 对话式多选 | `MultiChoiceField` |

**AI 开关降级统一形态（P0 一致性批）**：AI 依赖的表单主操作一律「按钮可见 + [AppButton] `disabledReason` 置灰原因 + 可见引导文案（指路 AI 设置）」——禁整钮替换/隐藏按钮两种旧形态（工作台/流程页已收口）。🔴 登记例外：VoicePage 转写任务卡的「AI 整理」为 TaskCard 可选槽（槽位 null = 不渲染），以底部降级文案表达——嵌入卡语境无按钮置灰形态，不适用本规则。

### 4.7m RenameDialog / PathConfirmCard / GateConfirmOverlay（单行重命名与 HITL 路径确认；2026-09-27 审计批新增共享件，B9 上提覆盖层件）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/RenameDialog.kt`、`PathConfirmCard.kt`、`GateConfirmOverlay.kt`

```kotlin
@Composable
fun RenameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit)

@Composable
fun PathConfirmCard(
    path: File,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    focusRequester: FocusRequester? = null,   // WB-15（重设计批 7）第 4 参：非空时挂卡根
                                              // 并 focusable——键盘裁决宿主；null = 既有行为
)

@Composable
fun GateConfirmOverlay(                      // B9（2026-10-11）上提：HITL 确认卡覆盖层件
    visible: Boolean,
    path: File?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    focusRequester: FocusRequester,          // 必传——组合期主动抢焦是共享件契约
    modifier: Modifier = Modifier,           // 落位（对齐/限宽/阴影/让位）由调用方注入
)
```

| 组件 | 收敛缘由 | 消费方 |
|------|----------|--------|
| `RenameDialog` | 原为 LogWorkbenchPage.RenameRunDialog 与 FlowPage.RenameFlowDialog 两份逐字相同私有实现（仅标题差）——feature 互不依赖经共享组件复用 | 日志工作台 run 重命名 / 流程页 flow 重命名 |
| `PathConfirmCard` | **HITL 围栏确认形态统一（维护者 2026-09-27 裁决：非模态卡）**——此前工作台走模态 AlertDialog、流程页走非模态 TonalCard，同一语义两种形态；多页签工作流下模态打断过重 | 工作台 pendingGate / 流程页 pendingGate |
| `GateConfirmOverlay` | 工作台与流程页两处近乎逐行同构的确认卡覆盖层（进出场过渡/末值缓存/组合期抢焦装配）上提共享（B9）——两处落位差异留调用方经 modifier | 工作台 pendingGate / 流程页 pendingGate |

要点：
- RenameDialog 内自持文本编辑态（`remember(initial)`），调用方只接 onSave/onDismiss——不引入额外状态提升；
- PathConfirmCard 键盘裁决 **Enter 允许 / Esc 拒绝**（卡级 onPreviewKeyEvent，随卡组合存在）——🔴 Esc 是真实实现（旧 FlowPage 版提示行写「Esc 拒绝」但只挂了 onEnterKey，属假声明，本版修正）；approve/deny 幂等（CompletableDeferred complete 后 UI 态清 null，重复按键无副作用）；
- GateConfirmOverlay 末值缓存（`lastGate`）：`visible=true` 而 `path` 短暂为 null 的退出过渡帧不闪空白卡；进出场恒 fast 档（HITL 是全局注意抢占语义，突现突消反差突兀）；组合期 `runCatching { focusRequester.requestFocus() }` 防挂载未完成抛错。

### 4.7n 重设计批 3 新件（SearchField / PillLabel / GhostCard / DashedBox / SettingsTwoPane）

| 组件 | 源码 | 签名摘要 | 语义边界 |
|------|------|----------|----------|
| `SearchField` | `widget/SearchField.kt` | `SearchField(value, onValueChange, modifier, placeholder = "", enabled = true, textStyle, leadingIcon = SEARCH, trailingIcon)` | **搜索 affordance 统一件**（DS-10）：单行矮搜索框，capsule 形 + `controlStandard` 34dp 高 + BasicTextField/DecorationBox 官方定制路径（8×16 contentPadding——M3 OutlinedTextField 不暴露 contentPadding 且行盒最小 56dp 压不到矮框档）；描边三态（1dp 发丝 / focus 2dp borderEmphasis / 禁用 borderSubtle 退隐 + surfaceDeep 底 + `focusProperties { canFocus = enabled }` 禁不可聚焦）；mono 语域传 `textStyle = DesktopTypography.mono`。消费方：桥接配置工具条/日志台过滤行/Shell 输出过滤栏/Shell 页面搜索（四处）。**新搜索框一律用它，不得回 OutlinedTextField 手拼** |
| `PillLabel` | `widget/PillLabel.kt` | `PillLabel(text, container, contentColor, modifier, icon: String? = null, style = labelSmall)` | **非交互式状态/标签 pill 唯一配方**（DS-11 收敛；当年的 ConfigFieldRenderer.StatusBadge 面已随 B4 FieldRow 家族提取退役，徽标族全走本组件）：`Row(capsule 底 + padding 8×2) + AppSymbolIcon(iconSmall) + Text(labelSmall)`。🔴 DL-M10：语义洗底必须 container 实色直出（warningContainer 等），**禁止 copy(alpha) 二次合成**（双洗底）。与 `SegmentedControl`/FilterChip 不同族——那是交互件，本件是静态徽标。消费方：桥接配置徽标族/能力中心 pill 流/驾驶舱剩余秒 pill/报告 meta 行（DOC-06 四态 pill） |
| `GhostCard` | `widget/GhostCard.kt` | `GhostCard(modifier, contentPaddingVertical = space16, contentPaddingHorizontal = space20, content)` | **提示/示意/占位说明卡专用容器**（DS-08）：弱化实底（DL-M21 预混 White 系 ghostBgLight 0.38 / ghostBgDark 0.05，不随主题 seed 染色）+ 1dp 发丝描边 + 零 blur（chrome 恒实底纪律——不上玻璃）。🔴 **正文数据卡禁用**（数据卡恒 TonalCard/SectionCard）；无交互态。消费方：FlowPage「最近会话」空态提示卡、AI 未配置降级说明卡 |
| `DashedBox`（`Modifier.appDashedBorder`） | `widget/decor/DashedBox.kt` | `Modifier.appDashedBorder(color = borderStrong, shape = AppShapes.medium, strokeWidth = glassStrokeHairline)` | **虚线降级盒修饰符**（DS-09）：drawBehind + dashPathEffect 自绘段线（4px 实/4px 空）。🔴 **DASH_SCOPE 守护**：`dashPathEffect` 全仓唯一合法落点 = `ui/widget/decor/` 目录——虚线在别处出现即守护红灯，消费方一律挂本修饰符。🔴 **降级态/未完成态/探测示意态容器专属**（DL-M20——正常内容卡恒实线，靠形状语言区分确定性与示意内容）。消费方：桥接能力中心探测未通过态、mock 注区 |
| `SettingsTwoPane` | `widget/SettingsTwoPane.kt` | `SettingsTwoPane(sections: List<SettingsPaneSection>, modifier)`；`SettingsPaneSection(title, icon, content)` | **设置页双栏容器**（SYS-01 · 批 6）：Wide 档（>1760）左舱分类导航恒窄列（`settingsNavWidth` 200dp，HoverableRow + `selectedWash` primary 选中洗底）+ 右舱选中分类局部滚（verticalScroll 内置——滚动责任从整页迁到右舱）；窄窗回落 sections 依序单列全量渲染（滚动责任仍在调用方整页滚）。进页初值 = 首分类（remember 态，无跨窗持久化）。消费方：设置页（三类）/ AI 设置页（四类）；分类计数 ≤4 |

### 4.7o 共享组件收敛批新件（HoverTooltipBox / TooltipIconButton / selectionHoverWashColor / ChipTextButton / GhostChipButton；2026-10-11 B8）

| 组件 | 源码 | 签名摘要 | 语义边界 |
|------|------|----------|----------|
| `HoverTooltipBox` | `widget/interactions/HoverTooltip.kt` | `HoverTooltipBox(tooltip, modifier, positioning = null, containerColor, contentColor, showWhen = true) { interactionSource -> … }` | **全仓 hover 气泡配方单源**（B8 收敛 9 处同构手写）：`enableUserInput = false` 关 M3 默认输入驱动，hover 检测恒经 `hoverable`（🔴 clickable 的 hover 发射被 `if (enabled)` 门控——置灰原因 tooltip 会死接线；hoverable 恒发射）+ `TOOLTIP_DELAY_MS` 显示延迟（快速划过不闪现）+ `isPersistent = true`（hover 常驻阅读）+ `showWhen` 条件门控。🔴 契约：content 收到的 interactionSource **必须**真挂 `.hoverable(interactionSource)` 到可命中面（挂控件本体不挂内图标），只收不挂 = 永不显示死接线。贴窗口顶/底缘锚点须显式传 `TooltipAnchorPosition.Below/Above`（水平定位无垂直兜底会把气泡顶出窗外） |
| `TooltipIconButton` | `widget/interactions/HoverTooltip.kt` | `TooltipIconButton(tooltip 文案, icon, onClick, enabled, …)` | 「图标钮 + hover 说明」家族现成装配（IconButton 包 HoverTooltipBox）；置灰原因说明的唯一规范路径（iconSmall 触达域由 IconTouchTargetGuardTest 覆盖） |
| `selectionHoverWashColor` | `widget/interactions/SelectionWash.kt` | `selectionHoverWashColor(hovered, selected = false, selectedColor = primary×selectedWash): Color` | **选中/悬停行洗底色三态单源**：优先序 = 选中压过悬停（selectedWash 0.08 > hoverWash 0.04，层级序即优先序，不叠加两档）。两档 selectedColor 各有主：微洗底档（默认 primary×selectedWash——列表行/证据行高亮族）；选中容器实底档（调用方传 `surfaceColors.selectedContainer`——页签选中胶囊/空间选中行族，配 onSelectedContainer 字链）。纯悬停行 selected 缺省 false；shape/padding/clickable 落位留调用方 |
| `ChipTextButton` | `widget/ChipButton.kt` | `ChipTextButton(label, onClick, modifier, enabled, color, container, textAlign, contentPadding)` | **文字 chip 钮胶囊档**：capsule + labelMedium SemiBold + 14×6——行尾文字钮族（页头 trailing/题行动作钮/对话框动作钮，weight 铺宽传 TextAlign.Center）。落位差异（危险 error 字 / hot 选中 primary 字 + primary×hotWash 热底）由调用方计算后传入 |
| `GhostChipButton` | `widget/ChipButton.kt` | `GhostChipButton(label, onClick, modifier, enabled, color, container)` | **文字 chip 钮小档**：small 圆角 + labelSmall + 12×4——块头内嵌小钮族（证据流块头次级动作）。🔴 契约：modifier 链序固定 background → border → clickable → padding（命中区不受装饰裁剪） |

消费方覆盖面（B8 盘点口径）：hover 气泡族 AppTitleBar/PageHomeMenu/SpaceBar/WorkspaceTabs/AiSettingsPage/KbManagePanel/LarkBridgePage/ShellToolsPage/ShellHistoryPanel/ShellOutputPanel/SidePanelHost；洗底行族 WorkspaceTabs/SpaceBar/SettingsTwoPane 导航/证据行；ChipTextButton 族 AiSettingsPage/KbManagePanel；GhostChipButton 族 EvidenceTimeline 块头。**新写 hover 气泡/选中洗底行/文字 chip 钮一律用本节组件，不再手写配方**。

### 4.8 滚动跟随（流式列表贴底判定，三处同构模式）

现网三处同构复用：`page/shelltools/output/ShellOutputPanel.kt`、`page/debug/ShellDebugPage.kt`、`feature-larkbridge/src/jvmMain/…/page/larkbridge/log/LogConsolePanel.kt`。新写流式列表**照抄此块**：

```kotlin
/** 贴底跟随像素容差：判定「最后一可见项底边接近视口底边」时吸收亚像素/微偏移 */
private const val BOTTOM_FOLLOW_TOLERANCE_PX = 8

val listState = rememberLazyListState()

// 追加时自动滚底（scrollToItem 即时跳转，零动画铁律）。
// 本 effect 执行时 layoutInfo 仍是旧布局快照（新追加条目尚未布局），索引容差判定
// （lastVisible == last - 1 || lastVisible == last）在单批追加 ≥2 条时恒假，跟随会永久停摆；
// 改用稳健贴底判定：
// 1) canScrollForward == false：旧快照上用户确实贴底（不能再向前滚），批量追加也跟随；
// 2) 像素判定补充：最后一可见项底边接近视口底边（px 容差吸收亚像素/微偏移）。
// 用户上翻查看历史（两判定均不满足）或手动滚动中不跟随，避免劫持视点。
LaunchedEffect(items.size) {
    val last = items.lastIndex
    if (last >= 0 && !listState.isScrollInProgress) {
        // 贴底判定：不能继续向前滚（旧布局快照上用户处于底部），
        // 或最后一可见项底边接近视口底边（容差吸收亚像素/微偏移）
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
        val atBottom = !listState.canScrollForward ||
            (lastVisible != null &&
                lastVisible.offset + lastVisible.size >= info.viewportEndOffset - BOTTOM_FOLLOW_TOLERANCE_PX)
        if (atBottom) listState.scrollToItem(last)
    }
}
```

语义要点：`canScrollForward` 在旧布局快照上判定用户是否贴底（批量追加也跟随）；`+8px` 像素容差吸收亚像素/微偏移；`isScrollInProgress` 防止劫持手动滚动。

### 4.9 系统文件/目录选择器家族（DirectoryPicker / FilePicker）

#### pickDirectory（目录选择，Swing）

```kotlin
suspend fun pickDirectory(title: String = "选择目录"): File?
```

Swing `JFileChooser(DIRECTORIES_ONLY)`（Linux 上 AWT FileDialog 目录模式不可靠）。挂起函数：内部 `withContext(Dispatchers.IO)` 切出 UI 线程，再经 `SwingUtilities.invokeAndWait` 把模态弹窗派回 EDT（Compose Desktop UI 线程即 EDT，直接弹会冻结界面）；IO 线程仅阻塞等待，Compose UI 线程全程不被阻塞。返回选中目录（绝对路径），取消/关闭返回 null（owner 缺失时以无 owner 模态弹出，不影响功能）。

**调用范式**：

```kotlin
val scope = rememberCoroutineScope()
OutlinedButton(onClick = {
    scope.launch {
        pickDirectory("选择工作区域文件夹")?.let { text = it.absolutePath }
    }
}) { /* icon + 文字 */ }
```

选中后只回填输入框状态，不自动保存——持久化仍走调用方显式保存流。

#### pickOpenFiles / pickSaveFile（文件选择，AWT FileDialog；2026-09-27 审计批单源）

源码：`ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/FilePicker.kt`

```kotlin
suspend fun pickOpenFiles(title: String, multiple: Boolean = false, filter: (File) -> Boolean = { true }): List<File>
suspend fun pickSaveFile(title: String, suggestedName: String? = null): File?
```

AWT `FileDialog`（各平台原生外观）——与 `pickDirectory` 的文件选择对偶面。线程配方同
`pickDirectory`（挂起 + IO 线程，UI 不冻结）；owner 取当前 ComposeWindow（Frame 子类）。
此前 5 份同配方私有拷贝（FileDropZone/PaletteParamForm/PageSettings/WorkbenchViewModel/
ShellOutputPanel 导出）已收敛为单源——**页面不得再自写 AWT FileDialog 选择器**（原 PageSettings 的 ADB「浏览」按钮随 B6 迁 `AdbPathSetting.kt`，消费点跟随）。
取消/关闭返回空列表或 null。`larkbridge/JsonFileDialog.kt` 的 `pickJsonFile`
（JFileChooser 版）有意保留自持——KDoc 注明与 pickDirectory 的 Swing 互操作镜像关系。

> 🔴 FileDropZone 自 2026-09-27 审计批起内部走 `pickerScope.launch { pickOpenFiles(...) }`
> ——此前它在 UI 线程直弹模态（既有瑕疵顺带修正）。

---

## 5. 交互与动效标准

### 5.1 时长档位选择表

| 场景 | 规格 |
|------|------|
| hover 背景/操作区渐显、状态图标 Crossfade、tint 过渡、拖放区三色渐变 | `AppMotion.fast()` |
| 面板展开/折叠（AnimatedVisibility）、页面切换（AnimatedContent） | `AppMotion.standard()` |
| 大区域变化 | `AppMotion.slow()` |
| 入场编排逐项递进 | `AppMotion.staggered(index)` |
| 尺寸变化（分组展开、内容高度插值） | `AppMotion.softSpring()` |

### 5.2 入场编排模板（一次性；原始出处 `PageDefault.kt` 已退役，模板沉淀保留）

```kotlin
var entered by remember { mutableStateOf(false) }
LaunchedEffect(Unit) { entered = true }   // 首帧 false、组合后置 true，重组不重放

AnimatedVisibility(
    visible = entered,
    enter = fadeIn(animationSpec = AppMotion.staggered(index)) +
        slideInVertically(animationSpec = AppMotion.staggered(index)) { it / 16 },
) {
    // 内容；同组的 fadeIn/slideInVertically 用同一 index，位移幅度 it/16（温和上浮）
}
```

- 递进延迟由 `staggered(index)` 在 tween `delayMillis` 内消化，调用方只持**单一** `entered` 状态，无需逐项分批置位。
- 3 项编排总量 `2*30 + 180 = 240ms`，恰在封顶量级内。
- hero（页面主视觉）作为第 0 级先用 `AppMotion.standard()` 入场，列表项再 staggered。

### 5.3 hover 模式

首选 `HoverableRow`（见 4.7）；hover 说明气泡一律 `HoverTooltipBox`/`TooltipIconButton`（见 4.7o，全仓配方单源）。确需自建（非整行 hover，如卡片局部强调）时，同构写法：

```kotlin
val interactionSource = remember { MutableInteractionSource() }
val isHovered by interactionSource.collectIsHoveredAsState()
val alpha by animateFloatAsState(
    targetValue = if (isHovered) 1f else 0f,
    animationSpec = AppMotion.fast(),
    label = "xxxHover",
)
Box(Modifier.hoverable(interactionSource) /* ... */) { /* 用 alpha 驱动背景/边框渐变 */ }
```

### 5.4 零动画铁律（流式输出/日志行）

**流式输出、日志行、高频重组的列表内容，禁止任何动画**：

- 禁 `animateScrollToItem`（守护测试强制）——滚动一律 `scrollToItem` 即时跳转 + 4.8 节贴底跟随；
- 禁 `Modifier.animateContentSize()` 施加于日志行/流式条目（约定级，review 拦截）；
- 禁逐行入场动画（fadeIn/AnimatedVisibility 包日志行，约定级）。

原因：高频追加 + 逐行动画 = 重组风暴 + 掉帧。动画只做**状态过渡**（展开/收起、出现/消失、hover 反馈、页面切换）。

### 5.5 危险操作三级（L0/L1/L2，2026-09-21 P3 落地）

判据 = **持久化 × 影响面**（撤销槽只覆盖内存态，防「假撤销」）：

| 档 | 判据 | 通道 |
|----|------|------|
| **L0** | 可逆且低成本 | 直接执行 |
| **L1** | **内存态可恢复** | 直接执行 + `StatusHost.showUndo`（6s 撤销条，hover 暂停） |
| **L2** | **持久化实体** / 不可逆 / 影响面大 | `AppConfirmDialog(isDangerous = true)`——标题=动作原句、正文含**具体后果与数量**、主钮 error 色破坏性动词 |

**L1 覆盖**：清空任务列表（`clearTasks` 返回快照 + `restoreTasks`）。**L2 覆盖**：Flow 删流程（SQLite）、Workbench 删 run（SQLite）、Distill 删知识仓库、清空全部 Shell 历史、Shell 删分组。

🔴 **边界**：持久化实体一律 L2——Flow 会话含 SQLite 记录，撤销无法真恢复；同页「单条不确认、清空要确认」的割裂随判据统一消失。

### 5.6 全局快捷键表（调查工作台 S7 终态）

唯一真源 = `host/HotkeyHub.kt` 的 `match()` 纯函数 + `HOTKEY_TABLE` 常量（渲染面 F1 参考卡 / 设置页同源，🔴 禁止文档与代码二次维护——本表仅速查，改动以源码为准）：

| 键 | 动作 |
|----|------|
| `Ctrl+K` | 命令面板（混排；`>` 只看命令、`@` 只看页面） |
| `Ctrl+Shift+P` | 命令面板命令模式（`>` 预填） |
| `Ctrl+Tab` / `Ctrl+Shift+Tab` | 环形切当前 tab 集（openTabs 动态集；未开 tab 的页经 Ctrl+K 直达） |
| `Ctrl+,` | 设置页 |
| `Ctrl+1..6` | 跳转 dock 第 N 项（dock 视觉序：1=家 2=智能体 3=设备 4=桥接 5=工具 6=设备观测——第六空间 dock 末位追加，Ctrl+1..5 序不变；dock 集之外的键不虚标——真源 HotkeyHub.kt「切换空间」六项） |
| `F1` | 快捷键参考卡（可搜索） |
| `Esc` | 关闭顶层浮层（栈式 LIFO：F1 卡 → 命令面板） |

（Ctrl+J / Ctrl+Alt+J 已随底面板退役删除——S2 维护者裁定。）

### 5.7 校验时机三段式 + 错误文案四要素（2026-09-21 P3 落地）

**校验时机**（唯一三段式）：

1. 拖入/粘贴**整批预检**——仅类型不符给拒绝态（`FileDropZone` 2.4s 消退），不在未提交时报字段错；
2. **字段失焦**对已触碰字段校验 → `isError` + `supportingText`；
3. **复合表单提交** → 主钮 `disabledReason`（AppButton tooltip——🔴 禁止静默置灰）。

**错误文案模板（强制）**：`[哪个对象] + [发生了什么] + [现在能做什么] + [已输入内容保留]`；错误态 = 图标 + error 色 + 边框三重冗余（不得只靠颜色）。

**落地形态**：`FileTaskViewModel.inputError` 经 `TaskPageTemplate` 渲染为 `StatusBanner(Error)` + 行尾「知道了」（`dismissInputError`）；整批预检错误不再走裸 bodySmall。

---

## 6. 图标与视觉语言

### 6.1 图标体系（`androidx.compose.material.icons`）

| 类别 | 何时用 | 现网例 |
|------|--------|--------|
| `Icons.Default.*`（filled） | **操作类**：可点击/触发动作 | ContentCopy、Check、ClearAll、FileUpload、FolderOpen、CheckCircle、Error、Delete |
| `Icons.Outlined.*`（outlined） | **状态/空态类**：无交互的语义展示 | Inbox（空态） |

尺寸档位见 2.2；tint 规则：

| tint | 场景 |
|------|------|
| 默认（`LocalContentColor` / `onSurfaceVariant`） | 常规操作图标 |
| `MaterialTheme.colorScheme.primary` | 品牌/强调操作图标 |
| `MaterialTheme.colorScheme.tertiary` | 成功态图标（TaskCard） |
| `LocalEasyToolboxColors.current.success` | 「已复制」成功反馈 |
| `MaterialTheme.colorScheme.error` | 失败态 |
| `LocalToolboxSurfaceColors.current.onSurfaceFaint` | 空态图标（R5 迁 slot——裸 copy 降对比度违 AA） |
| 禁用态 | 内容色 `.copy(alpha = AppAlpha.contentDisabled)` |

装饰性图标（旁有文字标签说明语义）`contentDescription = null`；独立可操作图标必须提供中文 `contentDescription`。

### 6.1a 停止 / 取消 / 失败字形三槽映射表（2026-10-14 P179）

「终止/失败」语义面图标字形**按槽位取用，不按页面自选**（四族并用回收：STOP / CANCEL / STOP_CIRCLE / CLOSE → 三槽）：

| 槽位 | 字形 | 适用语义 | 现网例 |
|------|------|----------|--------|
| 会话/流程级停止 | `STOP_CIRCLE` | 停止正在运行的会话/流程（动作措辞为「停止」） | AppButton 统一停止钮、logcat「停止并落盘」、screencast「停止投屏」「停止录像」、输出台执行停止钮 |
| 单任务取消 | `CANCEL` | 取消排队/进行中的单个任务（动作措辞为「取消」；圆环内 X） | TaskCard 运行中取消钮、TaskCard/ToolCallCard 已取消态 |
| 错误态 | `ERROR` | 失败终态的语义展示 | TaskCard/ToolCallCard 失败态、StatusBanner.Error、输出台执行失败头 |

CLOSE 保留给「关闭」槽（关闭钮/清除搜索等，`StatusHostCloseIconGuardTest` 钉死），不得用于失败态或停止/取消语义。

### 6.2 空态 / 加载态 / 错误态标准

**空态一律优先 `EmptyStateView`（4.7c）**——通用「图标 + 引导文案」居中组合已收敛为组件，手写空态仅限组件表达不了的定制场景（如空态需附操作按钮/跳转入口）；自建时沿用同一组合模式（对齐其内部实现）：

| 态 | 组合 |
|----|------|
| 加载 | `CircularProgressIndicator(Modifier.size(Dimens.iconHero))` 居中于可用区域 |
| 空态 | **首选 `EmptyStateView`**；自建仅限定制场景：outlined 图标（`iconHero`，`contentFaint` 透明度）+ `bodyMedium` 引导文案，`space12` 间距，居中 |
| 错误 | `bodySmall` + `colorScheme.error` 文案；带图标时 filled Error（`iconSmall`/`iconMedium`） |

### 6.3 卡片视觉语言

全应用卡片一律 **tonal 扁平**：`AppCardStyles.tonal()` + 0 elevation + `AppShapes.small/medium`（配方见 2.6）。无投影；边框描边仅用于拖放区（2.dp 实线 outline → primary 渐变）等输入引导场景，常规卡片不加边框。

### 6.4 状态容器条与状态圆点（2026-09-10 新增，先例 ShellToolsPage banner / device/DeviceStatusBar）

**状态不用彩色文字单层表达**，一律「容器色 + 前缀图标 + 文字」三层（图标 iconSmall + bodySmall）——2026-09-12 起已组件化为 `widget/StatusBanner`（四语义槽，见 4.7e），**新代码直接用组件，不再手写 Surface 配方**：

| 语义 | 容器色 | 前缀图标 | 文字色 |
|------|--------|----------|--------|
| 成功/中性信息 | `LocalEasyToolboxColors.successContainer` | CHECK_CIRCLE（Material Symbol） | `onSurfaceVariant`（深色模式 success 在绿底对比不足） |
| 警告 | `warningContainer` token（2026-09-12 新增，原各处 `warning.copy(AppAlpha.bgSubtle)` 合成底收编） | WARNING_AMBER | `colors.warning` |
| 错误 | `MaterialTheme.colorScheme.errorContainer` | ERROR | `onErrorContainer` |
| 提示（Info） | `infoContainer` token（DS-01 · 重设计批 0 补齐：亮 #D3E3FD / 暗 #0D3B77） | INFO | `colors.info`（亮 #0B57D0 / 暗 #64B5F6） |

旧配方（手写 `Surface(shape = AppShapes.small, color = 容器色)` + `Row(padding space12/space8)`）仅存量代码保留，勿新增。

**状态圆点**（紧凑行的二元在/不在信号）：`Box(Modifier.size(Dimens.space10).clip(CircleShape).background(状态色))` + 文字——操作栏连接态、StatusRow 运行/连接/确认链路行；大空间场景（页面 hero）用 iconHero 状态图标（云系列 outlined），圆点与图标是同一设计语言的两个分辨率。


---

## 7. MUST / NEVER 硬规则

### 7.1 守护测试强制（`composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/UiArchitectureGuardTest.kt`，源码扫描，违规即测试红）

| # | 规则 | 正例 | 反例 | 守护规则名 |
|---|------|------|------|-----------|
| 1 | 圆角一律 `AppShapes` | `shape = AppShapes.medium` | `MaterialTheme.shapes.medium` | MATERIAL_SHAPES |
| 2 | 同上（dp 面） | `shape = AppShapes.medium` | `RoundedCornerShape(12.dp)` | RAW_DP |
| 3 | 流式滚动零动画 | `listState.scrollToItem(last)` | `listState.animateScrollToItem(last)` | ANIMATE_SCROLL |
| 4 | 动画规格唯一定义点 | `AppMotion.fast()` | `tween(240)`、`spring(...)`、`tween<Float>(240)` | MOTION_SPEC |
| 5 | 卡片一律 tonal 扁平 | `Card(colors = AppCardStyles.tonal(), elevation = ... 0.dp)` | `ElevatedCard { }` | ELEVATED_CARD |
| 6 | 透明度一律 `AppAlpha` | `.copy(alpha = AppAlpha.contentFaint)` | `.copy(alpha = 0.6f)` | RAW_ALPHA |
| 7 | dp 一律 `Dimens` | `Modifier.size(Dimens.iconMedium)` | `Modifier.size(16.dp)` | RAW_DP |
| 8 | 字号禁半像素 sp | `DesktopTypography.monoTag` | `fontSize = 11.5.sp` | HALF_PIXEL_SP |
| 9 | 禁 emoji 代图标 | `AppSymbolIcon(MaterialSymbols.ATTACH_FILE)` | `"📎 ${name}"` | EMOJI |

正则细节（供对照）：

- MOTION_SPEC：`\b(tween|spring)\s*[<(]`——只允许出现在 `core/theme/Motion.kt`（整文件白名单）。
- RAW_DP：`\b(?:[1-9]\d*(?:\.\d+)?|0\.\d*[1-9]\d*)\s*\.\s*dp\b`——非零数字 + `.dp`，含小数与空白写法；`0.dp` / `0.0.dp` 零值全局豁免；`Dimens.kt` / `AppShapes.kt` 整文件豁免（token 本体）；其余按「文件 → 允许值集合」值级白名单（真源见 theme.md「有据保留」清单）。白名单现行规模（**XA-20 · 重设计批 11 · 2026-10-02 更新；2026-10-14 UX 走查批 ShellOutputPanel 清除钮 20dp 收 MICS 32 条目退役 10→9**）：整文件豁免 **7 键**（`Motion.kt`/`Dimens.kt`/`AppShapes.kt` + EMOJI 豁免四文件：`EvidenceModels.kt`/`WorkbenchPrompts.kt`/`KbImportCli.kt`/`DistillPage.kt`——协议符号与 CLI 输出有据保留）+ 值级豁免 **9 键** = **16 键**（XA-20 图标钮收编后 ShellHistoryPanel/ShellToolsParentNode/ShellOutputPanel 整条退役、三文件值集收缩；以 `UiArchitectureGuardTest` 白名单区现场计数为准；总数锚断言 `raw dp whitelist total key count matches docs` 钉死键数与 theme.md 同步）。
- RAW_ALPHA：`alpha\s*=\s*0\.\d`——命名实参形态；`AppAlpha.bgTask` 这类常量引用不命中。
- **HALF_PIXEL_SP**（DS-17 · 重设计批 0）：`\d+\.5\s*\.\s*sp\b`——xx.5.sp 半像素字号（DL-F09：跨平台渲染不可控）；零白名单。
- **EMOJI**（DS-17 · 重设计批 0）：`[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]`——生产文案禁 emoji 代图标；协议符号（✓/✗）与 CLI 输出（✅/❌）走四文件白名单（见 theme.md「有据保留」）。
- **NO_GLOW**（DS-17 · 重设计批 0，独立 @Test）：`BlurMaskFilter|MaskFilter\.blur`——DL-F06 禁 glow（唯一高光语言 = 1dp 受光缘发丝）；豁免 `ui/widget/glass/`。
- **DASH_SCOPE**（DS-17 · 重设计批 0，独立 @Test · **缓挂态**）：`dashPathEffect`——DL-M20 虚线只给降级态；DS-09（批 3）落地 `ui/widget/decor/` 前全仓零命中，落地后翻转为目录级豁免。
- 注释内的提及会被剥除（字符串感知的注释剥离器），但**不要**依赖这一点规避规则。
- **新文件零豁免**：值级白名单只对已登记文件生效。新页面里写 `32.dp`（IconButton 触摸目标）同样会红——要么避免自定义尺寸（默认 IconButton 即可），要么按第 1 节流程双登记后再写。
- 白名单防腐化：已登记值若从源码消失，`raw dp whitelist values still match real code` 断言会红——例外代码删除/替换后必须同步清理白名单与 theme.md 清单。

### 7.2 工程约定（review 拦截，守护测试不覆盖）

| # | 规则 | 说明 |
|---|------|------|
| 8 | 零动画铁律完整版 | `animateContentSize` / 逐行动画施加于流式输出、日志行 = 违规（正则只拦 `animateScrollToItem`，其余靠 review，见 5.4） |
| 9 | LazyColumn 稳定 key | `items(list, key = { it.id })`；日志/流式行 key 用单调递增 seq（clear 不重置），并加 `contentType = { "xxx-log" }` 复用同一组合缓存槽（先例 `BridgeLogConsole`） |
| 10 | `@Immutable` / `@Stable` 使用 | 重组敏感的稳定值类（颜色/主题值集合）标 `@Immutable`（先例 `EasyToolboxColors`）；持可变状态的交互句柄标 `@Stable`（先例 `CopyFeedbackState`）；一次性 UI 数据模型（`TaskUi`）可不标 |
| 11 | 组件复用优先 | 新需求先对照第 4 节 API 手册找现成组件；「差不多但不完全一样」的需求优先加参数泛化共享组件，而非复制粘贴私有实现 |
| 12 | 动效 240ms 封顶 | 走 `AppMotion` 工厂即自动满足；封顶值本身勿随意抬高 |
| 13 | AnimatedContent 单根纪律 | content lambda 每分支单根发射（多根 = CMP 1.12 叠放同位，见 3.1 要点）；API 行为基线钉在 `arch/AnimatedContentSingleRootGuardTest`，页面配方靠 review 拦截 |
| 14 | 聚合页分支滚动 | 滚动只包需要滚的分支不包容器（容器级滚动 = 分支无界化 → 无 heightIn 的 LazyColumn 崩溃，见 3.1 要点） |

---

## 8. 新增 UI 时的检查清单

- [ ] 需要的新档位已先在 `core/theme/` token 文件扩充定义并同步 theme.md 取值表（第 1 节流程）？
- [ ] 间距/图标尺寸/圆角零新增裸 dp；确需的有据保留值（IconButton 触摸目标等）已在 theme.md + 守护白名单双登记？
- [ ] 透明度全走 `AppAlpha`、圆角全走 `AppShapes`、卡片配色全走 `AppCardStyles.tonal()`（无 ElevatedCard、无就地 CardDefaults.cardColors）？
- [ ] 动画规格全经 `AppMotion` 工厂（无裸 tween/spring），时长不超 240ms？
- [ ] 流式输出/日志行零动画，滚动跟随照抄 4.8 节贴底判定模板（scrollToItem + canScrollForward + 8px 容差）？
- [ ] LazyColumn 的 items 带稳定 key；流式行加 contentType？
- [ ] 页面骨架走 `PageScaffold`（例外仅限 3.2 两类，且理由成立）？
- [ ] 共享组件优先复用（PageScaffold/PageHeader/StatusView/TaskCard/FileDropZone/CopyButton/CopyIconButton/HoverableRow），无重复造轮子？
- [ ] 颜色零硬编码（`Color(0xFF...)`）：主题槽 + `LocalEasyToolboxColors` 够用？
- [ ] `./gradlew :composeApp:jvmTest` 全绿（含 6 条守护规则与白名单防腐化断言）？

---

## 9. 相关文档

| 文档 | 内容 |
|------|------|
| [core/theme.md](core/theme.md) | **规范真源**：token 设计缘由、有据保留清单（守护白名单单一真源）、迁移状态 |
| [index.md](index.md) | 知识库总索引 |
| [core/viewmodel.md](core/viewmodel.md) | AppViewModel 全局状态（主题/深浅模式装配入口） |
| [pages/navigation.md](pages/navigation.md) | 新页面挂导航的流程 |
| [pages/settings.md](pages/settings.md) | 设置页（主题选择/自定义配色） |
| [pages/shell-tools.md](pages/shell-tools.md) | Shell 工具页（双栏例外、输出面板） |
| [pages/debug.md](pages/debug.md) | Debug 页 |
| [pages/lark-bridge.md](pages/lark-bridge.md) | 飞书桥接页（日志控制台先例） |
| `composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/UiArchitectureGuardTest.kt` | 守护测试本体（6 规则 + 白名单 + 防腐化断言） |
| `openspec/changes/2026-09-02-visual-polish/` | 视觉治理 change（本体系的落地记录） |
| `openspec/specs/ui-design-tokens/spec.md` | UI 设计令牌规格 |
