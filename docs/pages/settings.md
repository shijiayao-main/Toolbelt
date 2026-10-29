# 设置页

文件：`page/settings/PageSettings.kt`（页面装配：骨架 + 区块接线；同目录拆分件——2026-10-11 B6：主题橱窗族 `ThemeShowcase.kt`（`ThemeGroupLabel`/`ThemeCard`/`ThemeOptionItem`/`ThemeSpecimenBand`/`ThemeColorDots`/`CustomThemeItem` 全族 internal）、ADB 检测子卡 `AdbPathSetting.kt`；配色相关对话框 `ColorPickerDialog.kt`、`ImageThemeCard.kt` 仍在同目录）

---

## 结构

```kotlin
@Composable
fun PageSettings(appViewModel: AppViewModel = AppViewModel.getViewModel())
```

从 `AppViewModel` 订阅：
- `colorScheme: StateFlow<AppThemeSpec>` — 当前主题（预设或自定义种子色）
- `themeMode: StateFlow<ThemeMode>` — 外观模式（System/Light/Dark）
- `shellType: StateFlow<ShellType>` — 当前 Shell 类型
- `debugPageEnabled: StateFlow<Boolean>` — 调试入口开关

页面骨架统一为 `widget/PageScaffold`（`screenPadding`/`screenSpacing` + `PageHeader`），主体经 `widget/SettingsTwoPane` 双栏化：宽窗 = 左舱分类导航（外观/环境/高级三分）+ 右舱选中分类局部滚动（🔴 切分类回顶——`LaunchedEffect(selectedIndex) { animateScrollTo(0) }`，滚动位置是组件自持的，不回顶会直接落进新分类中段）；窄窗回落单列依序全量渲染。各区块用 `SettingsSection`（标题+描述+内容），共七组（`Shell 类型` 仅非 Windows 渲染 = Windows 下六组可见）：

---

## 外观模式区块

- `SingleChoiceSegmentedButtonRow` 三态：跟随系统 / 浅色 / 深色
- 点击调用 `appViewModel.setThemeMode(mode)`

---

## 环境背景区块

- `AppSwitchRow`「环境背景」：内容区环境光斑（aurora）开关——关闭后停绘光斑回纯色背景，调用 `appViewModel.setAuroraEnabled(checked)`

---

## 配色方案区块

- 预设：`AppThemeSpec.groupedPresets` 分组渲染（**6 组 20 套**，组序 = `AppThemeSpec.presets` 列表序——分组与清单由 `:core` 单源 `PresetGroup` + `spec.group` 承载，UI 不自持清单，任意增删预设自动跟随；每套的 pageBase 双态/染色参数/一击必杀件见 docs/core/theme.md「20 套目录裁定表」）：
  - **经典**：默认（Default，Fidelity 零漂移锚）/ 鎏金（Vibrant + 深空暖黑夜相主权）
  - **机甲**：军械绿（Vibrant，军黄纸 + 信号粉选中面）/ 电光紫（Vibrant，昼紫夜紫双态主权 + 全槽覆写表 + 结构带）/ 熔岩红（Vibrant，昼红夜红双态主权 + 橙第二种子）/ 蓝金三色（Fidelity + 红/金双种子三强调）/ 猩红流金（Vibrant + 玫瑰金/流金双种子，夜态红底主权）
  - **撞色**：晴空麦浪（Vibrant，夜态晴空蓝主权 + 唯一双态异相染色）/ 朱砂宣纸（Fidelity，近零染色）
  - **传统**：青花（Fidelity，深钴蓝瓷骨架 + 夜瓷蓝主权）/ 洒红节（Vibrant，粉雾纸重采样下沿钉定套）/ 市松翠格（Fidelity，市松夜主权 + 市松格纹图案）
  - **终端**：Dracula（Vibrant，官方 bg 直采 + 粉第二种子）/ Gruvbox（Fidelity，官方 light0 亮底主权 + 苔绿第二种子）/ Nord（Fidelity，双态官方底直采 + 霜蓝第二种子）
  - **情绪**：矩阵数字雨 / 警示橙（均 Vibrant，后者带黑黄斜纹图案）/ 青白霓虹（Fidelity + 霓虹粉第二种子，暗态霓虹描边）/ 薄荷青（Fidelity + 藏青第二种子，黑舞台暗态主权）/ 绯红夜城（Fidelity + 琥珀第二种子，夜幕派暗态主权）
- 非 Compact 档（Medium/Expanded/Wide）：每组 = 组标题（`ThemeGroupLabel`，labelMedium + `onSurfaceFaint`）+ 组内 `chunked(2)` 双列 `ThemeCard` 网格——水平单行卡（**三块构成样张带 + 名称 + 选中 CHECK**）；孤行兜底配方组内化（奇数组末行仅剩左卡时该卡独占整行宽、不渲染占位卡，不依赖总数奇偶）；门槛不锁 Wide 的宽度依据：Medium 下限 1024dp 扣让位/边距后单卡仍有 ~400dp 可用（`ThemeCard` 最小内容 ~170dp），双列放得下
- Compact 档回落：每组 = 组标题 + `ThemeOptionItem` 单列行式（leading `ThemeColorDots` 迷你窗条带预览，见下）；`ThemeOptionItem` 由原 `ThemeSpecItem` / `CustomThemeItem` 两个逐行同构的私有函数合并而来（visual-polish Wave 3），色点/加号占位差异经 `leading` 参数化，选中态边框、hover 反馈与点击行为统一
- **三块构成样张带（`ThemeSpecimenBand`，网格 ThemeCard 预览）**：亮/暗双条各一条（每条 = 底/第二声部/强调三段连续色带，weight 3/2/1 面积递减——面积构成层级，非等权色板），单条 15dp + 发丝描边 + `AppShapes.small` 圆角；取色单源 `spec.specimenTriad(dark)`（与实机 `colorSet` 同源——直读 seed 会漏掉染色/覆写套实值），双态并置让 pageBase 主权套的暗态身份在橱窗可见；**套级图案槽在场时底块叠加纹理采样**（市松翠格 市松棋盘 / 警示橙黑黄斜纹——采样与绘制单源 `ThemeSpecimen.kt`，消费位封闭集 = 样张 base 块）；选中卡面 = `selectedContainer` 整面承载，名称/CHECK/2dp 选中描边走 `onSelectedContainer` 配套字链（CHECK 恒渲染占位钉住字形自然行盒，防选中行 +2px 高跳动）
- **迷你窗条带（`ThemeColorDots`，Compact 回落 leading）**：深浅两条（每条 = 骨架条带背景/卡片/深卡三段，weight 2.5/1.5/1，宽 6×themeDotSize、`AppShapes.small` 裁剪 + `borderHairline` 发丝描边）+ 主/第二/第三强调三圆点（双种子套显形）；颜色全部经 `spec.colorSet(dark)` 派生与实机同源（直调 `dynamicColorScheme` 不传 style 会预览偏灰）
- hover 反馈：`ThemeOptionItem` / `ThemeCard` 基于 `HoverableRow`（hover 背景以 `AppAlpha.bgSubtle` fast 档渐入，见 widget/interactions）；`AppSwitchRow` 传 `hoverable = true` 时同样经 `HoverableRow` 渲染 hover 背景（该底座是 `AppSwitchRow` 组件内部实现，调用方无需感知）
- 自定义：`CustomThemeItem`（同为 `ThemeOptionItem` 形态，未设置时 leading 为 Add 加号占位）→ `ColorPickerDialog`（HSV 色相条 + 饱和度/明度渐变 + Hex 输入）→ `appViewModel.setCustomColorScheme(seed)`
- 图片提取：`ImageThemeCard`（单行 zone 行式入口——system.html `.zone` zrow 直译，非 FileDropZone 大拖放区；accepts png/jpg/jpeg/webp/bmp）→ IO 线程 `extractSeedFromImage()` → `ImageThemeConfirmDialog` 预览确认 → 同一持久化路径
- 持久化：预设存 id、自定义存 `color_scheme="Custom"` + `custom_seed_color`（ARGB）；被删/改名预设（海洋/日落/紫罗兰/蔷薇/青碧/琥珀 + 森林/石灰/深海荧光 + 改名批次前的旧 id）的存量 id 由 `AppThemeSpec.fromId` 的 `LEGACY_PRESET_MAP` 读面映射到同色相族最近保留档（海洋→青花、日落→警示橙、紫罗兰→默认、蔷薇→洒红节、青碧→薄荷青、琥珀→鎏金、森林→矩阵数字雨、石灰→**Nord**（终裁按浅 primary 距离复核，非 Gruvbox——见 docs/core/theme.md「LEGACY_PRESET_MAP 现值」表）、深海荧光→薄荷青；改名套旧 id（armory-green 等）直通同名新档；DataStore 旧值不清洗，手动切换即写新 id）

---

## Shell 类型区块

- **仅非 Windows 平台显示**
- `SingleChoiceSegmentedButtonRow` 遍历 `ShellType.entries`

---

## 内置 ADB 区块

没有路径输入框：adb 随应用分发，是**唯一来源**（不提供系统 PATH 回退，也不支持自定义路径）。

- 「检测可用性」按钮（`AdbPathSetting`，独立文件 `page/settings/AdbPathSetting.kt`）：执行
  `<内置 adb> version`，成功展示版本串（绿色）、失败如实展示错误（红色，不静默回退）
- 内置 adb 解析失败时（释放产物缺失/损坏）另行展示人话原因与处置建议
- 端点解析与释放语义见 [core/device.md](../core/device.md)

---

## 产物目录区块

- 路径 `OutlinedTextField`（`ExportRootSetting`，独立文件 `page/settings/ExportRootSetting.kt`，AdbPathSetting 同款配方）：失焦经 `appViewModel.setVisibleExportRoot(path)` 持久化 + **同步写 `FileUtils.applyVisibleExportRoot` 进程单源**（改后即时生效无需重启；留空回默认 `~/Documents/Toolbelt/`）
- 「浏览」按钮：Swing `JFileChooser` 目录模式（`pickDirectory`，IO 协程模态调用避免冻结 UI）
- 当前生效根只读行：mono 字 + `CopyIconButton`（日志目录行同款配方；配置为空时展示的就是默认根）
- 持久化 key：`visible_export_root`（String，默认 `""`；启动恢复 = `VisibleExportRootRestoreInitializer`）
- 消费方：全部可见产物写点经 `FileUtils` 可见产物树访问器（reports/recordings/perf/logcat/snapshots/knowledge，见 core/data.md「FileUtils 目录访问器」节）；可再生产物不迁移不回读，旧根存量留给用户

---

## 日志存储区块

- `AppSwitchRow`（Switch + 整行可点击，hover 形态：`hoverable = true` + `hoverShape = AppShapes.medium` + `labelStyle = bodyLarge`）：调用 `LogbackHelper.enableFileLog()/disableFileLog()`

---

## 开发者选项区块

- `AppSwitchRow`「显示调试入口」（同上 hover 形态三参一致）：调用 `appViewModel.setDebugPageEnabled(enabled)`
- 开启后 dock 系统区显示 Debug 页入口（SYS-15 · 批 6——「侧边导航」旧称谓已退役；见 pages/debug.md）
