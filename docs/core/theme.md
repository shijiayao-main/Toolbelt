# 主题系统

文件：`core/theme/`

---

## 结构

| 文件 | 职责 |
|------|------|
| `AppThemeSpec.kt` | 主题规格：sealed class，预设（seed + PaletteStyle + 底色主权 pageBase + 身份直配覆写表 + 图案槽）+ 自定义种子色，`dynamicColorScheme` 派生 + `colorSet` 底色/覆写装配 |
| `ThemeMode.kt` | `ThemeMode`：System（跟随系统）/ Light / Dark |
| `AppTheme.kt` | 主题装配入口：解析深浅、装配 MaterialTheme + 语义色 Local |
| `EasyToolboxColors.kt` | 语义扩展色 token（成功/警告/控制台/日志级别/AI 语义色）与 Local |
| `SurfaceColors.kt` | 表面语义 slot 层 `ToolboxSurfaceColors`：surface 三层 / onSurface 三档 / **onCard 三档（卡域文字分域）** / border 四档（`borderEmphasis` 带 outline 不足图形线的 primary 亮线救援）/ hover / selectedContainer + **结构带三元组** `structureBand`/`onStructureBand`/`structureBandActive`——派生式（`toolboxSurfaceColors(scheme, dark, overrides)` 显式传参），套级覆写经 overrides 消费 |
| `DayBattle.kt` | 昼战形态判定与语义承载：`LocalDayBattle` / `LocalTrafficBackdrop`（昼战红绿灯底衬=昼战井色复用）/ `dayBattleTint`（图形档换 container 色）——判定 = 亮模式 ∧ `pageBaseLight` 在场 ∧ HCT tone<70 |
| `ThemeSpecimen.kt` | 套级三块构成样张取色单源：`specimenTriad(dark)` 双态（底/第二声部/强调 + 眯眼可辨判据）/ `specimenPatternSample` 图案采样 / `drawSpecimenPatternSample` 绘制 |
| `Dimens.kt` | 尺寸设计 token：间距阶梯（space2~24）+ 语义别名 + 图标四档 + 三舱/断点/容量组（2026-09-21 交互重设计） |
| `AppShapes.kt` | 圆角设计 token：预构造 Shape 实例（8/12/16dp 三档） |
| `CardStyles.kt` | 卡片样式设计 token：`AppCardStyles.tonal` 扁平卡配色（无 elevation、surfaceVariant 系背景），卡片容器配色唯一定义点 |
| `Motion.kt` | 动效设计 token：时长三档（120/180/240ms 封顶）+ stagger 编排延迟 + 弹簧规格，动画规格唯一定义点 |
| `Alpha.kt` | 透明度设计 token：语义命名 alpha 阶梯（命名收口，值 = 现状精确值） |
| `TimingConstants.kt` | 计时常量单点（2026-09-21 P0 新增：SUCCESS_BANNER_MS=5000 / UNDO_WINDOW_MS=6000 / COPY_FEEDBACK_MS=1500 / REJECT_HINT_MS=2400 / TOOLTIP_DELAY_MS=200）——🔴 不混入 Motion.kt（Motion 是 120/180/240 动画封顶档，混入计时器会诱导突破封顶） |
| `Breakpoints.kt` | 响应式四档（2026-09-21 P0 新增）：`WindowSizeClass`{Compact,Medium,Expanded,Wide} + `classifyWindowSize(widthDp, …)` 纯函数 + `LocalWindowSizeClass`——🔴 零 dp 字面量（阈值参数化由调用方从 Dimens 取后传入），不进守护白名单 |

依赖：[MaterialKolor](https://github.com/jordond/materialkolor) 5.0.1（`com.materialkolor:material-kolor`），提供 material-color-utilities 的 Kotlin 多平台实现。

---

## AppThemeSpec

```kotlin
sealed class AppThemeSpec() {
    abstract val displayName: String
    abstract val seed: Color          // 主色种子（三角色之「主」）
    abstract val id: String           // 持久化标识
    open val style: PaletteStyle      // 档位契约见下表；未覆写 = TonalSpot
    open val group: PresetGroup       // 设置页分组；未覆写 = Classic

    // 三角色模型（骨架 / 主 / 强调 + 第三强调）：
    open val secondarySeed: Color?          // 强调 = 第二种子（null = 不设，单种子套）
    open val tertiarySeed: Color?           // 第三强调 = 第三种子（null = 回落 secondarySeed——双种子套的 tertiary 家族与强调同族）
    open val skeletonStain: SkeletonStain?  // 骨架 = 中性调色板染色（null = 跟随 style 档现行中性策略）

    // 底色主权（涂装本漆直采；null = 走既有管线）：
    open val pageBaseLight: Color?    // 昼形态：非 null = 亮分支 bg/surface/surfaceContainerLowest 三槽直采，跳过 97.9 锚轨与重采样轨
    open val pageBaseDark: Color?     // 夜形态：非 null = 暗分支三槽直采（井槽 Lowest 可再经覆写表与页面底分家）
    // 套级身份直配覆写表（昼/夜各一张；null = 派生逐位不变）：
    open val lightOverrides: ThemeSlotOverrides?
    open val darkOverrides: ThemeSlotOverrides?
    open val neutralSkeletonExempt: Boolean // 中性豁免（克制即身份）：不进重采样窗口/分带色度地板/bg ΔE 地板

    fun colorSet(dark: Boolean): ColorScheme =
        dynamicColorScheme(seed, isDark, style, secondary = secondarySeed,
            tertiary = tertiarySeed ?: secondarySeed, neutral/neutralVariant = stain 覆写)
        → pageBase 在场：bg/surface/surfaceContainerLowest 三槽直采套参数
         （SkeletonStain.lightPageTone 管线层不消费——底色单源歧义防护）+ applySlotOverrides 逐槽 copy
        → pageBase 缺席 + 浅色：双轨——97.9 锚轨 = withTone 校正（豁免套/未降 tone 的染色套，逐位不变）｜
          重采样轨 = lightPageTone < 97.9 时 bg/surface/surfaceContainerLowest 三槽
          从染色参数按目标 tone 直采（Hct.from 经 HctSolver 解算，非 palette 采样）
        → pageBase 缺席 + 暗色：M3 spec tone 表 + applySlotOverrides

    // 预设（6 组 20 套，presets 列表序 = 组序）：
    //   经典 Classic：Default 默认（Fidelity，零漂移锚）/ Aurum 鎏金（Vibrant）
    //   机甲 Mecha：armory-green 军械绿（Vibrant）/ electric-violet 电光紫（Vibrant，昼紫夜紫双态主权 + 全槽覆写表）/
    //     lava-crimson 熔岩红（Vibrant + 橙第二种子，昼红夜红双态主权）/
    //     tricolor-blue-gold 蓝金三色（Fidelity + 红/金双种子三强调）/ crimson-gold 猩红流金（Vibrant + 玫瑰金/流金双种子，夜态红底主权）
    //   撞色 Flag：sky-wheat 晴空麦浪（Vibrant，夜态晴空蓝主权）/ vermilion-paper 朱砂宣纸（Fidelity）
    //   传统 Culture：qinghua-cobalt-white 青花（Fidelity）/ holi-gulal-colors 洒红节（Vibrant，粉雾纸下沿钉定套）/ checker-emald 市松翠格（Fidelity，市松夜 + Checkerboard 图案）
    //   终端 Terminal：dracula-neon Dracula（Vibrant，官方 bg 直采）/ gruvbox-warm-paper Gruvbox（Fidelity，官方 light0 亮底主权）/ nord-frost Nord（Fidelity，双态官方底直采）
    //   情绪 Mood：matrix-terminal 矩阵数字雨 / hazard-orange 警示橙（均 Vibrant，后者带 HazardStripe 图案）/
    //     cyan-neon 青白霓虹（Fidelity）/ mint-teal 薄荷青（Fidelity，黑舞台暗态主权）/ crimson-night 绯红夜城（Fidelity，夜幕派暗态主权）
    // 自定义：data class Custom(seed)（覆盖式单例，恒 TonalSpot；不染色/不设多种子/不涂装/无覆写表——色轮无该输入，登记为边界）

    companion object {
        val presets: List<AppThemeSpec>
        val groupedPresets: List<Pair<PresetGroup, List<AppThemeSpec>>>   // presets.groupBy{group}，组序/组内序 = presets 列表序
        fun fromId(id: String, customSeed: Color? = null): AppThemeSpec   // 被删 id 经 LEGACY_PRESET_MAP 读面映射，未知 id 仍回 Default
    }
}

/** 套级身份直配覆写表（昼/夜形态各一张，与 SkeletonStain 平行的套级可配面）——三段槽位：
 *  ① M3 身份槽（primary 族/secondary 族/tertiary 族/onSurface 族/outline 族/inverse 族/surfaceContainer 系/dim/bright/tint/variant，
 *     bg/surface 两槽不入表——底色单源 = pageBaseLight/pageBaseDark，防双源歧义；井槽 surfaceContainerLowest 可经表与页面底分家）；
 *  ② ToolboxSurfaceColors 槽：selectedContainer（选中容器身份槽）/ onSurfaceFaint / onCard 三档（底色主权套卡域文字分域）/
 *     structureBand + onStructureBand（结构带，见「底色主权与套级直配」节）；
 *  ③ EasyToolboxColors 槽：consoleBg（走 resolvedConsoleBg 单源解析：直配 > 暗态染色派生 > 静态基值）/
 *     aiAccent / aiAccentContainer / floatShadow / chartPalette / platingGreen。
 *  全字段可空，null = 回落派生；覆写表是套定义（代码常量），不入持久化（color_scheme 仍存 id）。
 */
@Immutable
data class ThemeSlotOverrides(
    /* M3 槽 × 27 + ToolboxSurfaceColors 槽 + EasyToolboxColors 槽，全可空 */
    val surfacePattern: ThemeSurfacePattern? = null,  // 样张 base 块图案选型（申请制注册面；图案不上文字承载面）
)

/** 套级表面图案型（密封枚举，无开放端点——扩员须先过注册面重裁） */
enum class ThemeSurfacePattern {
    Checkerboard,   // 市松格纹（双色 = 该套卡阶首末两档 surfaceContainerLow/Highest）
    HazardStripe,   // 警示斜纹（双色 = warning 档 + 近黑档）
}
```

```kotlin
/** 骨架染色：中性（neutral/neutralVariant）调色板的 HCT 色相与色度，深浅两态各自独立 */
@Immutable
data class SkeletonStain(
    val darkHue: Double, val darkChroma: Double, val darkVariantChroma: Double,
    val lightHue: Double, val lightChroma: Double, val lightVariantChroma: Double,
    val lightPageTone: Double = 97.9,  // 缺省 = 97.9 锚管线；< 97.9 = 亮态 bg 三槽重采样目标（有效域 [94.5, 97.9]，下沿 = 最深彩纸需求套洒红节 95.0 − 量化容差）
)
```

**新增预设主题**：在 `AppThemeSpec` 中加一个 data object（displayName + seed + 可选 style / secondarySeed / tertiarySeed / skeletonStain / pageBaseLight / pageBaseDark / lightOverrides / darkOverrides / neutralSkeletonExempt + 可选 group 覆写），并按分组插入 `presets` 列表——设置页分组色卡（经 `groupedPresets` 渲染；非 Compact 档双列网格 / Compact 档单列行式回落，门槛锚 `PageSettings.kt` `currentWindowSizeClass() != WindowSizeClass.Compact`）自动包含，无需改其他代码。守护四件套需同步：`AppThemeSpecTest`（清单/档位/分组锚 + 底色主权注册面 + 染色参数逐值快照 + 图案注册面）、`ThemeDistinguishabilityGuardTest`（联合度量+bg 互异+跨族地板+同族 A 腿）、`ThemeDerivedContrastGuardTest`（AA 域拆+tone 双轨+分带色度地板+接线 consoleBg AA+chartPalette 覆写面）、`ThemeSpecimenTriadGuardTest`（样张两态逐位互异+相邻块可辨）——全部运行时实算、零 hex 锚、自动覆盖新套。新套缺省按非豁免处理，须过重采样窗口/分带地板/bg 地板；确属克制题材再登记 `neutralSkeletonExempt` 并同步守护 KDoc 与上表。`PresetGroup` = Classic(经典) / Mecha(机甲) / Flag(撞色) / Culture(传统) / Terminal(终端) / Mood(情绪)；组标签直出设置页组标题。

**LEGACY_PRESET_MAP 现值（12 条，读面映射非清洗——DataStore 旧值不清洗，手动切换即写新 id）**：

| 被删 id | 映射到 | 依据 |
|---|---|---|
| `Ocean` 海洋 | qinghua-cobalt-white 青花 | 钢蓝→钴蓝同族 |
| `Sunset` 日落 | hazard-orange 警示橙 | 暖橙同族 |
| `Violet` 紫罗兰 | Default | 紫最近档（紫系被 aiAccent 线结构性排除） |
| `Rose` 蔷薇 | holi-gulal-colors 洒红节 | 品红族 |
| `Teal` 青碧 | mint-teal | 青荧光域最近保留档（单层查找无转发，不落深海荧光旧链） |
| `Amber` 琥珀 | Aurum 鎏金 | 同琥珀族 |
| `Forest` 森林 | matrix-terminal 矩阵数字雨 | 同纸绿域主力（目录 26→20 裁定） |
| `Slate` 石灰 | **nord-frost** Nord | 映射契约「按浅 primary 距离复核」优先于裁定表初值——实测 nord-frost ΔE76 12.07 最近，gruvbox 为暖纸异族 |
| `abyss-glow` | mint-teal | 青荧光域近亲 |
| `armory-green` | armory-green 军械绿 | 改名直通 |
| `electric-violet` | electric-violet 电光紫 | 改名直通 |
| `lava-crimson` | lava-crimson 熔岩红 | 改名直通 |
| `tricolor-blue-gold` | tricolor-blue-gold 蓝金三色 | 改名直通 |
| `crimson-gold` | crimson-gold 猩红流金 | 改名直通 |
| `sky-wheat` | sky-wheat 晴空麦浪 | 改名直通 |
| `vermilion-paper` | vermilion-paper 朱砂宣纸 | 改名直通 |
| `checker-emerald` | checker-emerald 市松翠格 | 改名直通 |
| `cyan-neon` | cyan-neon 青白霓虹 | 改名直通 |
| `mint-teal` | mint-teal 薄荷青 | 改名直通 |
| `crimson-night` | crimson-night 绯红夜城 | 改名直通 |

### 三角色模型与表面染色（SkeletonStain）

每个预设由三个角色定义，与 materialkolor 覆写参数一一对应：

| 角色 | 字段 | 机制 |
|------|------|------|
| 骨架 | `skeletonStain: SkeletonStain?` | 整支覆写 neutral/neutralVariant 调色板——background/卡阶梯/玻璃/描边全消费链自动换血；null = 跟随 style 档现行中性策略（Fidelity=种子色度÷8 / TonalSpot=6 / Vibrant=10 / Neutral=2） |
| 主 | `seed`（primarySeed） | 既有字段；保色相档（Fidelity/Vibrant）下 primary 色相 ≈ seed 色相 |
| 强调 | `secondarySeed: Color?` | 第二种子整支覆写 secondary 调色板；null = 不设（单种子套） |
| 第三强调 | `tertiarySeed: Color?` | 覆写 tertiary 调色板；null = **回落 secondarySeed**（双种子套的 tertiary 家族与强调同族，避免 Fidelity 互补副作用的随机窑变）；显式第三种子的只有 tricolor-blue-gold（V字金）与猩红流金（流金） |

- **覆写语义**（materialkolor ktx/DynamicScheme）：`X 调色板 = 覆写色?.toTonalPalette() ?: defaults.XPalette`——全 null（Default/Custom）= 完整回退 defaults，派生与单种子管线逐位一致（零漂移锚）。覆写色 tone 无关紧要（TonalPalette 只取 hue+chroma），统一 tone 50 求色。
- **tone 阶梯不变量**：tone 表由 M3 spec 钉死不动（暗 bg6/Lowest4/Low10/High17/Highest22；浅卡阶梯 Low96/High92/Highest90）——「卡沉于底/卡浮起」结构性自动保持；onSurface/onBackground 恒 tone 90/10，文字对比度结构性保持。浅色页面基底走**双轨**：97.9 锚轨（豁免套与未降 tone 的染色套，withTone 校正逐位）与重采样轨（`lightPageTone` ∈ [94.5, 97.9]，下沿由最深彩纸需求套钉定，bg/surface/surfaceContainerLowest 三槽直采）——两轨同受「bg > Low > High > Highest 严格降序」次序断言守护；**底色主权套（pageBase 在场）整体退出浅色双轨断言块**（底色已直采，「窗外即红」前提不成立），套内自洽由阶梯方向 + 分带色度地板 + AA 域拆承载（是替换校验，非豁免）。`onSurfaceFaint` 固定实色（#5B6070/#8A90A3）= 无覆写套回落值——底色主权套对浮起卡阶固定实色破 4.5 的，经覆写表 t78 同相转译覆写（Aurum/青花/市松翠格/Hazard/ArmoryGreen/Gulal/熔岩红/猩红流金/晴空麦浪/Dracula/Nord/薄荷青/绯红夜城 等在册）；`EasyToolboxColors` 语义槽不随派生（身份边界；`consoleBg`/`aiAccent` 等经覆写表直配为登记例外，见「底色主权与套级直配」节）。
- **色度分档**（以暗态 bg 实测 chroma 落位；非豁免套暗 chroma 地板 10.0 = 分带地板的暗态对应物）：克制 0-4（朱砂宣纸 1.9）/ 有性格 5-12（Default 6.6、薄荷青 11.8、蓝金三色 6.3、Aurum 10.0、gruvbox 10.2、绯红夜城 11.6）/ 大胆 13-20（青白霓虹 13.9、matrix 14.2、qinghua 16.9、hazard 18.0、armory-green 18.2、gulal 19.4）。落位规则 = 题材的「环境性格」：近零染色墨系走克制、情绪强识别套走大胆、工作台套走有性格。⚠️ 页面底涂装化（pageBase 主权漆直采）后，晴空麦浪/电光紫/熔岩红/猩红流金/dracula/nord 六套的暗 bg 色度已随主权漆取值（晴空麦浪 派生档 11.1、电光紫 18.2、熔岩红 15.7、猩红流金 15.7、dracula 14.0、nord 12.2 为染色派生档历史锚，同值换轨套不变）——现行落位以守护暗态色度地板 ≥10 实跑为准（待核对）。
- **亮态色域天平（双轨解钳）**：tone 97.9-98 处 HCT 亮端色域最窄，暖红橙骨架被钳到 c1-2.3、紫青绿可撑 c4-11、近零档 0.4-2.3——97.9 管线内调参够不着的套（暖红/暖橙/暖黄带尤甚）走 `lightPageTone` 重采样轨解钳（97.9 是色域陡变区，h80 天花板 2.7 → h110 23.6；降 tone 换色域宽度，withTone 只压亮度保色度解不了钳）。🔴 重采样必须 `Hct.from(hue, chroma, tone)` 直采（经 HctSolver 色域解算），不能用 `TonalPalette.tone(pageTone)`——palette 插值在极端 tone 自带色度衰减（(h355,c7) 经 palette 采样 tone 96.5 只落 c2.26，直采落 c4.40）。分带色度地板（暖红 3.5/暖橙 3.0/暖黄 3.5/黄绿 5.0/绿 8.0/青 8.0/蓝紫 6.0/品红 4.0）是解钳的落地验收；豁免套（克制即身份）不参与。**亮态可见阈边界（≥49/255 通道差）**：全目录只有黄绿带（armory-green h110，palette 交付峰 ~21）能把 bg/卡面/容器阶梯通道差推过 49（对冷/中性纸端 ≥53）——其余色带亮纸通道差物理上限 30-45（白纸律 + 窄色域），跨族对的可辨分离由 A 腿与 bg ΔE 地板承载；97.9 管线内 palette 交付在峰点后回落（armory-green param 20 落 c20.6、param 22 反落 c17.4），顶格调参以实测交付为准不虚标参数。
- **🔴 Expressive 全目录禁用**：其 primary 调色板 = 种子相 +240°/chroma 40（materialkolor ColorSpec2021 实读），旋相毁身份色（熔岩红/猩红流金等红相套会翻蓝紫）；红→橙等温差叙事改由骨架染色色相位移与第二/第三种子承载。hue-keep 守护（v2 双锚分腿语义，见「守护口径」节）是机制防线。
- **Console/玻璃边界**：`consoleBg` 消费面单源解析 `resolvedConsoleBg(spec, dark)` = **套级直配**（电光紫 昼夜表 #17131F / 熔岩红 #20040B / Dracula #191A21 / 薄荷青 #0D1210）> **暗态骨架染色派生**（非豁免染色套 `stainedDarkConsoleBg`：Hct.from(暗 hue, 暗染色×0.35 收敛 [4,6], tone 7)）> **静态基值**（亮 #1E1E1E 恒定 / 暗基值 #141416）——控制台不再与近黑骨架融合；`aiAccent` 恒紫、log 四色/状态槽对不随派生（跨主题语义一致性；aiAccent/chartPalette 经覆写表直配为登记例外——电光紫 荧光绿/图表板、熔岩红 浅黄、猩红流金 亮紫、晴空麦浪 白）。玻璃 chrome/panel/sheet/thin 取源 surfaceContainer 系自动跟骨架色温，禁 lerp 染色回潮（见「染色体系」节）。
- **Custom 边界**：色轮/图片提取仍是单种子 TonalSpot，无染色/多种子/底色主权/覆写表输入（`EasyDataManager` 仅 color_scheme/custom_seed_color 两键）；无法复现任何带 skeletonStain/secondarySeed/pageBase 的预设，后续若开放需扩持久化键 + 设置页取色器。

### 底色主权与套级直配（pageBase / 覆写表 / 结构带 / 图案层 / 样张 / 昼战）

**底色主权（pageBaseLight / pageBaseDark，涂装本漆直采）**：

- **语义**：套级底色参数非 null 时，`colorSet` 对应分支把 background/surface/surfaceContainerLowest 三槽直采为本值，跳过 97.9 锚轨与重采样轨——「浅模式 = 浅纸」的模式通则不适用于风格化主题，页面底是套的身份漆不是派生物。
- **单源**：bg/surface 两槽**不入覆写表**（底色单源 = pageBase，防双源歧义）；pageBase 存在时 `SkeletonStain.lightPageTone` 管线层不消费（字段与字段值保留，守护测试 resampled 判定路径依赖）。
- **井槽分家**：surfaceContainerLowest 可经覆写表再直配与页面底分家（昼战同值铺底、夜战落井槽）——井是 chrome/sheet/thin 玻璃带取源槽（Glass.kt 直取 Lowest），分家即玻璃带落深井域，带面文字/图标一律走 onCard 浅字链；井槽不在浅色阶梯断言链，文字域由 onCard 对井槽断言承载。
- **文字域拆分（G13）**：底色主权套按**参数存在性**生效（非套名特判）——onSurface 族断言挂基域（bg/surface），卡域由 **onCard 三档**（onCardStrong/Mid/Faint，无覆写回落 onSurface 族）承载；井槽按分家值分流（分家归卡域断言、未分家留基域断言）。

**身份直配覆写表（ThemeSlotOverrides，昼/夜各一张）**：

- 三段槽位（M3 身份槽 / ToolboxSurfaceColors 槽 / EasyToolboxColors 槽）见上方 AppThemeSpec 契约块；逐槽 copy 在底色三槽铺底**之后**执行（井槽可再直配），null 槽回落派生、null 表逐位不变。
- **选中容器配套字链**：套级覆写 `onSecondary` 直配即视为启用「选中面取字链」——`ToolboxSurfaceColors.onSelectedContainer` 取该直配值；选中容器面上的文字/图标不许直取 onSurfaceStrong/onBackground（底色主权套两态异值，直取会在夜战绿面上落白字）也不许直取 onSecondary（非覆写套无配套保证），一律走本槽。
- **borderEmphasis 亮线救援**：outline 对 background/Low/Lowest 任一承载面 <3.0 的装饰缝套（夜装甲缝/昼拉深卡梯近黑缝），焦点线自动升 primary 焦点档——缝不承担图形可辨职责，outline 槽自身退装饰缝位；非救援套 outline 直取逐位不变。
- **注册制（申请制，非全目录开放）**：见「守护口径」注册面三闸——未注册套传参即红（default-deny）、注册套逐值快照（pageBase hex / 染色参数 / 图案型），改值 = 身份变更 = 强制全目录守护重算后再改快照锚。

**结构带三元组（structureBand / onStructureBand / structureBandActive）**：

- **语义**：侧栏轨（`PageHomeMenu` dock 轨）+ 页签栏（`WorkspaceTabs` tab 条）两条常驻实底带整面换装——面积构成第二声部位的块级承载。
- **派生**：`toolboxSurfaceColors` 产出三元组——带底 = 套级覆写 ?: surfaceContainerLowest（回落原材质，零漂移）；配套字 = 覆写 ?: onSurfaceVariant（回落域 = onCardMid 同值）；激活态 = 覆写存在性布尔（域拆纪律非套名特判），消费位按 active 三元切换原材质与原文字链，非激活套逐位不变。
- **纪律**：🔴 结构带不上文字承载面（带上不放图形件/正文，带上前景统一走 onStructureBand 配套字链）；消费位封闭集 = 侧栏轨 + 页签栏（`StructureBandConsumersGuardTest` 源码锚守护）。带面对昼底不过图形线属面档登记（可辨性由明度异域 + 封闭消费位承载）。
- **现行启用**：仅 电光紫 昼表（structureBand = 井槽同值 #3D1E7E + onStructureBand #EDE6FF——装甲骨架带与 chrome 取源链连成连续暗色框架；夜表不传，夜战绿由 selectedContainer 语义承载）。

**图案层（surfacePattern 图案槽）**：

- **型**：`ThemeSurfacePattern` 密封枚举 {Checkerboard 市松格纹 / HazardStripe 警示斜纹}——套级只选型不携带色值，周期/对比密度在绘制位固定。
- **双色采样单源**：`specimenPatternSample(dark)`——Checkerboard = 该套卡阶首末两档（surfaceContainerLow/Highest）；HazardStripe = warning 档 + 近黑档（暗态近黑 = 警示黑底 background，亮态 = 正文墨 onSurface）。
- **纪律**：🔴 图案不上文字承载面；消费位封闭集 = 设置页样张 base 块（`SpecimenPatternConsumersGuardTest` 扫白名单——主题卡其余区域/应用页面/其他模块消费即红）。
- **注册制**：AppThemeSpecTest 图案注册面（套→图案型快照）——现行在册 = 市松翠格（暗态 Checkerboard）/ Hazard（暗态 HazardStripe），两套亮态均不传（亮态走派生样张，断言钉死）。

**样张双态（specimenTriad(dark)，三块构成样张取色单源）**：

- **三块**：底 = `colorSet(dark).background`（涂装套 = 主权漆直采；白纸锚轨套 = 派生纸——与实机页面底同源，不直读 seed）；第二声部 = secondary 实值；强调 = 候选链 tertiaryContainer（仅 tertiarySeed 声明在场的套）→ tertiary → primaryContainer 中首个与第二声部眯眼可辨者，全落空保底 primaryContainer（声部 tone 40 对容器 tone 90 结构分离下界）。
- **判据**：眯眼可辨 = HCT tone 距 ≥10 ∨（色度双 ≥8 ∧ 色相距 ≥15）；声部同族微差拒绝域（色相距 <15 ∧ tone 距 <25 的同调色板明暗双档跳下一档）。
- **状态域**：亮态锁全部相邻对；暗态只锁 底|声部（刻意单色相暗态——如 绯红夜城 夜幕派全绯红族——声部|强调不锁可辨，逐位互异 + 暗底承载分离属单色相身份的诚实呈现，不判并块）。
- **消费点与守护**：设置页 ThemeCard 样张带（亮/暗双条各消费一次）；`ThemeSpecimenTriadGuardTest`（两态三块逐位互异 + 亮态相邻可辨 + 暗态底|声部可辨 + 两态底互异 + 主权漆直采抽验）。

**昼战判定与语义承载（DayBattle.kt）**：

- **判定卡口**：`LocalDayBattle` = 亮模式 ∧ `pageBaseLight` 在场 ∧ **HCT tone(pageBaseLight) < 70**（`DAY_BATTLE_MAX_BASE_TONE`）——🔴 必须卡 tone 而非只看参数存在性：浅纸直采套（官方米黄/雪白亮底）恒定语义槽亮值直落浅底本就达标，container 承载面反与浅纸同明度破图形线；浅纸套走非昼战呈现（与其余浅底套逐位一致）。判定按参数存在性 + tone（域拆纪律，非套名特判）。
- **承载**：文字（4.5 线）坐对应语义 container 面（槽对呈现——warning×warningContainer / errorFixed×errorContainerFixed 等）；图形点/段/图标（3.0 线）经 `dayBattleTint(content, container)` 换 container 档色；红绿灯底衬 `LocalTrafficBackdrop` = 昼战井色复用（lightOverrides.consoleBg 直配值，AppTheme 装配点单源下发）。非昼战形态消费点走原呈现逐位不变。

### 20 套目录裁定表（pageBase 双态 + 染色参数 + 一击必杀件）

浅色重采样套的浅参数带 `@tone` 标注（= `SkeletonStain.lightPageTone`，bg/surface/surfaceContainerLowest 三槽直采目标）；未标注 = 97.9 锚管线；pageBase 在场的套其 `@tone` 值管线层不消费（字段保留，守护测试 resampled 判定路径依赖）。豁免套（中性豁免清单 `neutralSkeletonExempt`）= Default（零漂移锚）/ 朱砂宣纸（近零染色）/ 薄荷青（真中性灰规格）/ 蓝金三色（近无相白甲+三点式身份）——克制即身份的对照系，不进重采样窗口与分带地板。「—（派生 …）」= 该态底色走 M3 派生/锚轨/重采样轨（无 pageBase 直采），括注 hex 为源码注释实测锚；逐槽覆写全表以 `AppThemeSpec.kt` 源码 KDoc 为准，本表只登记 pageBase 双态/染色参数/必杀件三要素。

| 套（id） | 组 | 档 | 染色 深/浅 (hue, neutralC, variantC) | pageBase 昼/夜 | 一击必杀件（一句话） |
|---|---|---|---|---|---|
| Default 默认 豁免 | 经典 | Fidelity | 无（零漂移锚） | —（派生 暗 #131319 / 亮 #FBF8FF） | 零漂移锚：seed #4F55BA + Fidelity 零覆写（快照锚亮 primary #363CA1）+ LEGACY 终点 |
| Aurum 鎏金 | 经典 | Vibrant | (80,10,14)/(75,6,10)@96.5 | —（亮态 #FFF3E8 重采样）/ #1A1206 深空暖黑（染色派生同值换轨） | 金箔两态四档卡阶浓淡阶（色度随深度上行）+ onSurfaceFaint #CFBFAA t78 转译 |
| armory-green 军械绿 | 机甲 | Vibrant | (110,18,26)/(110,20,26) | —（亮态军黄纸 #FEFCCA——全目录唯一宽色域带顶格，通道差 ≥53）/ #141400 军械绿黑（同值换轨，hue-keep 替代腿准入） | 信号粉 #F055A0 selectedContainer 两态全链（亮态「整窗换世界」级骨架） |
| electric-violet 电光紫 | 机甲 | Vibrant | (255,18,24)/(268,8,12)@97.3（管线层不消费） | #9946FA 昼面本漆 / #5940A6 夜面本漆 | 全槽直配昼战/夜战覆写表（卡沉于底/卡浮起、底域黑字卡域白字双域、图表 8 档）+ structureBand 装甲骨架带 + aiAccent 荧光绿 #09F955；双种子 #09F955/#F07830 |
| lava-crimson 熔岩红 | 机甲 | Vibrant | (0,16,22)/(8,7,10)@96.5 | #DB3A45 昼战装甲红本漆 / #6E1526 夜战兽化红黑 | 昼战装甲红卡沉于底（Low t44 受光域）+ 底域黑字/卡域白字双域 + aiAccent 浅黄 #FFECB3；第二种子 #FF8C42 实战橙（只做徽章） |
| tricolor-blue-gold 蓝金三色 豁免 | 机甲 | Fidelity | (240,6,8)/(238,2,3) | —（派生 暗 #101418 / 亮 #FAF9FA） | 全场唯一三强调套（双种子警戒红 #D13438/V字金 #F2C230）+ 胸蓝 #2A63C8 selectedContainer（亮态）；夜整备维持派生（替代腿色度闸不过登记） |
| crimson-gold 猩红流金 | 机甲 | Vibrant | (338,16,22)/(350,6,9)@96.5 | —（亮态香槟粉纸派生 #FFF2F5 系实算登记，红底昼战不做）/ #8C2338 高速红漆深档 | 夜卡浮起 t35/37/39 + selectedContainer 流金 #C9A227 + 霓虹 outline #F77486 + aiAccent 亮紫 #C4B5FD；双种子 玫瑰金 #DBA97C/流金 #C9A227 |
| sky-wheat 晴空麦浪 | 撞色 | Vibrant | (258,11,14)/(95,8,12)@97.3（唯一双态异相染色） | —（亮态麦纸派生 #FFF6E6 系实算登记）/ #0057B7 晴空蓝本漆 | 葵黄 #FFD700 selectedContainer（撞色面蓝黄对 4.91）+ aiAccent 白 + platingGreen 黄绿；第二种子 #FFD700 |
| vermilion-paper 朱砂宣纸 豁免 | 撞色 | Fidelity | (30,2,3)/(30,1,2) 近零 | —（派生 暗 #161312 暖墨 / 亮 #FEF8F6 宣纸） | 近零染色宣纸/暖墨即题材 + 朱印纪律（夜相直采三重依据登记，维持派生不落 pageBaseDark）；第二种子 #F7F4EC 宣纸白 |
| qinghua-cobalt-white 青花 | 传统 | Fidelity | (255,17,21)/(250,7,10)@96.8 | —（亮态瓷白蓝 #F3F6FD 重采样）/ #061423 夜瓷蓝（染色派生同值换轨） | 「一档蓝五档浓淡」两态钴蓝卡阶（亮倒挂沉/暗浮起）+ 头浓钴蓝 #224B81 selectedContainer（亮态） |
| holi-gulal-colors 洒红节 | 传统 | Vibrant | (340,20,30)/(335,10,16)@95.0 粉雾纸 | —（亮态 #FFEBF7 粉雾纸——重采样轨直采，窗口下沿 94.5 钉定套）/ #210C1C 玫黑（同值换轨，替代腿准入） | 亮卡粉雾浓淡阶四档（色度上行）+ onSurfaceFaint #D4BAC9 t78 转译 |
| checker-emald 市松翠格 | 传统 | Fidelity | (158,12,18)/(158,9,12) | —（亮态翠纸 #F2FCF1 派生）/ #1A271D 市松夜（t14.2 提 tone 主权） | 暗卡浮起阶四档（色度上行）+ surfacePattern=Checkerboard（样张市松格纹） |
| dracula-neon Dracula | 终端 | Vibrant | (292,14,20)/(280,7,10)@97.0 异族骨架 | —（亮态 #F8F5FF 派生，官方无亮态规范）/ #282A36 官方 bg 直采 | 井分家 #191A21（consoleBg 同值单源）+ 官方 selection #44475A 面档选中 + secondary/tertiary 官方绿橙；第二种子 #FF79C6 骚粉 |
| gruvbox-warm-paper Gruvbox | 终端 | Fidelity | (55,10,14)/(65,12,16)@96.5 | #FBF1C7 官方 light0（全目录唯一非白纸亮底主权）/ —（暗态 #1D1108 派生零漂移，官方中性暗底过不了暗态色度地板） | 官方面板档亮卡（light0_soft/bg2/bg3）+ 锈橙 #D65D0E selectedContainer + 苔绿 #98971A chip（第二种子） |
| nord-frost Nord | 终端 | Fidelity | (262,12,16)/(255,12,16)@97.4 | #E5E9F0 官方 nord5 / #2E3440 官方 nord0 | 全目录唯一双态主权套（官方 Snow Storm/Polar Night 直采）+ 暗卡 Polar Night 档直采；第二种子 #88C0D0 霜蓝 |
| matrix-terminal 矩阵数字雨 | 情绪 | Vibrant | (145,14,20)/(145,11,14) | —（派生 暗 #0A1609 CRT 绿黑 / 亮 #F1FCEA） | 磷光绿 #00E560 selectedContainer（=派生暗 primary 同源同值）+ CRT 屏桶微光暗卡阶 |
| hazard-orange 警示橙 | 情绪 | Vibrant | (40,18,26)/(38,8,12)@96.5 | —（亮态暖纸 #FFF2EF 重采样）/ #30170D 黑黄警示夜场（t11.1 提 tone 主权） | 暗卡浮起阶四档 + surfacePattern=HazardStripe（样张黑黄斜纹）+ onSurfaceFaint #DDB9AC 转译 |
| cyan-neon 青白霓虹 | 情绪 | Fidelity | (246,14,20)/(240,7,10)@97.4 | —（派生 暗 #08141F 潜航夜面 / 亮 #F4F8FE 潜航冷纸） | 霓虹描边（暗态 outline #E98FC1 对底 7.60 直过图形线 / outlineVariant #5F6D74 青灰降档）——描边即身份；第二种子 #FF5CA8 霓虹粉 |
| mint-teal 薄荷青 豁免 | 情绪 | Fidelity | (183,10,14)/(225,2,3) 亮真中性灰/暗舞台青 | —（亮态真中性灰纸 #FAF9F9 零漂移）/ #0A1A17 黑舞台（聚光派） | 荧光青 #36C8AA selectedContainer + consoleBg 井 #0D1210 + onSurfaceFaint #B5C4C3 转译；第二种子 #1E3A8A 藏青 |
| crimson-night 绯红夜城 | 情绪 | Fidelity | (14,12,16)/(252,14,18)@96.5 亮异族冷蓝/暗夜幕红 | —（亮态冷蓝纸 #F0F5FF 重采样）/ #1F0F10 夜幕派绯红相黑底 | 暗态绯红声部直配（secondary #C35B66 / selectedContainer #D25E6B / tertiary 族三档）+ onSurfaceFaint #E3B6B8 转译；第二种子 #F2A33C 琥珀 |

### 守护口径（主题域测试，一律 `--rerun-tasks --no-build-cache` 跑）

| 守护 | 断言 | 阈值/依据 |
|---|---|---|
| `AppThemeSpecTest` | 清单 20 套 6 组序、档位全集钉死、Expressive 禁用、双种子契约、fromId 往返/LEGACY 映射（12 个被删 id）、Default 快照锚（零覆写 + 派生锚）、语义槽 §2.4 快照、**底色主权注册面**（default-deny + pageBase/stain 逐值快照）、**图案注册面**（套→图案型快照） | 目录/契约/注册锚，增删即失败 |
| `AppThemeSpecTest` hue-keep v2 | 全部 Fidelity/Vibrant 预设（20 套）×两态双腿语义（见下方「hue-keep v2」详解） | Expressive +240° 旋相禁用的机制防线 |
| `AppThemeSpecTest` aiAccent 双通道 | 常规线 min(ΔE76(p/s/t, aiAccent)) ≥ 30.0；不过线者走「异族强调救援」：∃ secondary/tertiary ΔE76 ≥ 40 ∧ HCT 色相距 ≥ 40° ∧ 染色 max 色度 ≥ 10；aiAccent 读**消费面实色**（套级覆写合并后，覆写套以直配值为断言对象） | 常规线 = 紫罗兰先例 31.5 同法实测 31.15+0.4 余量；救援线 = 1.33× 常规线（唯一分离通道需更高余量）；色相距 ≥40° 防同族明度差冒充分离（同族不许救援，异族才许）；现行救援候选套 = electric-violet（aiAccent 两态直配荧光绿 #09F955——secondary 与 aiAccent 同值 ΔE=0，救援由 tertiary 橙腿承载 ΔE76≈129/色相距≈111°）与 dracula-neon（恒紫常规线 28.9 不过，荧光绿 ΔE76 80+/色相距 150° 救援） |
| `ThemeSpecimenTriadGuardTest` | 全预设两态三块逐位互异 + 亮态相邻块眯眼可辨 + 暗态底\|声部可辨（声部\|强调不锁——单色相暗态诚实呈现）+ 两态样张底互异 + 主权漆/tertiaryContainer 直采抽验 | 样张取色契约（见「底色主权与套级直配」节）——「并块零信息量」回潮即红 |
| `ThemeDistinguishabilityGuardTest` | 预设两两（190 对）×两态 = 380 对模式，四组断言：①联合度量 S=min(ΔE76 bg, ΔE76 cardLow)、A=max 槽位对 ΔE76(p/s/t)，PASS = S≥12 ∨ A≥25 ∨ (S≥4∧A≥15)；②bg 两两 hex 互异（无豁免）；③跨族（bg HCT 色相差≥25°）双非豁免对 bg ΔE76 地板：亮 ≥2.0 / 暗 ≥4.0；④同族对 A ≥ 25（bg 物理不可辨时强调声部兜底） | 运行时实算零 hex 锚；含豁免套的跨族对由 ① 复合腿承载（Default 种子受零漂移锚契约钉死，A≥25 硬线对其不可达——Default↔qinghua 暗 4.7/17.3 为登记复合腿最紧对）。全目录 margin 最薄处（守护 KDoc 登记实跑取证）：matrix↔青白霓虹 亮 S=12.01（cardLow 腿）、Default↔gulal 暗 S=12.06（均走 S≥12 硬线）；翻红预案 = 重算全部度量并微调对端套参数，不得删线或加豁免 |
| `ThemeDerivedContrastGuardTest` | 20×2 逐套实算：文字 ≥4.5:1（非底色参数套 onSurface×4 容器档、onSurfaceVariant×3、onSurfaceFaint×2；底色主权套**域拆**——onSurface 族挂基域、卡域由 onCard 三档承载、井槽按分家值分流）+ 图形 ≥3.0:1（borderEmphasis 消费面实效值/bg、primary×2、aiAccent 消费面实色×2、暗态 platingGreen）+ 选中容器接线对（覆写套按实测分档：对底 ≥3.0 断图形线、<3.0 面档跳过由配套字链 onSelectedContainer ≥4.5 承载；底色主权套字链断言独立保留）+ 结构带接线对（覆写套 onStructureBand/带面 ≥4.5；带对昼底不过图形线属面档登记）+ tone 双轨（浅：97.9 锚轨 \|tone(bg)−97.9\|≤0.5、重采样轨 tone∈[94.5,97.9]；**底色主权套整体退出双轨块**；两轨严格降序；暗严格升序）+ 分带色度地板（非豁免浅 bg 按色相带查表，含底色主权套 + 非豁免暗 bg chroma ≥10.0）+ 接线 consoleBg AA（consoleText/consoleSuccess/consoleInfo/platingGreen 对解析底 ≥4.5:1）+ chartPalette 覆写面（有覆写就校验 8 档对承载面 ≥3:1，静态恒定色板不进断言面） | WCAG 公式同 `SlotContrastGuardTest`；分带地板最窄档 dracula c7.2/线 6.0、警示橙 c3.8/线 3.0（守护 KDoc 登记）；全局最差对比度实测值以守护实跑为准（电光紫 全槽直配后旧登记值已失效，待实跑重登记） |

**hue-keep v2（双锚分腿 + 注册面三闸，夜相主权批收编）**：

- **双锚分腿**（pageBase 套的底相断言，OR 关系）：底相要么在**种子带主腿**（vs 种子相 <15°——机体本漆套的涂装身份，夜 = 同一块漆换光照），要么过**夜相骨架同族替代腿**（仅暗态，三条件齐备：tone < 20（闸值落在夜幕侧最亮 dracula t17.3 与涂装侧最深 nord t21.6 的实测间隙）∧ chroma ≥ 10（与暗态 bg 色度地板同源同值，两处失配即守护失锚）∧ 底相与在册染色暗档相（stain.darkHue）环距 < 10°（现有染色族最大偏移 2.4° 的 4 倍余量）——场景面套的涂装身份，夜 = 题材夜场，底与暗卡/井槽同吃染色相）。
- **官方色板直采例外**：Dracula/Gruvbox 不可被替代腿吸收（底 = 官方色板原值、种子 = 官方强调色，「底从种子派生」与「底随染色」两前提均不成立——Dracula 底距种子 29.2°/距自身染色暗档相 15.2°，双腿皆不过）——身份锚改挂注册面 hex 快照（比 hue 距更强的钉法，非放松）。
- **primary 腿解耦**：primary 相检查与 pageBase 存在性解耦——pageBase 套该态 primary 未被覆写表直配时仍断言（堵「落主权后 Expressive 防线静默消失」；覆写表直配反白为低色度域 hue 无语义，仅未直配态断言）。
- **注册面三闸**（paint sovereignty default-deny）：① 未注册套 pageBaseLight/pageBaseDark/lightOverrides/darkOverrides 四参数必须全 null（意外传参即红——任一套意外传参会静默退出 G14 亮态双轨断言块，此断言即那条静默退出的闸门）；② 注册套逐值快照（pageBase hex + 染色参数双态 3 参 + lightPageTone——stain 变更 = 整副夜/昼骨架随动 = 身份变更，须重过全目录重算后再改快照锚）；③ 图案注册面同构（套→图案型快照，改型 = 展示身份变更）。
- **现行准入**：ArmoryGreen/Gulal 夜相直采走替代腿三闸全过（底相距在册染色暗档相 1.0°/0.3°）；蓝金三色/朱砂宣纸 色度闸不过（c6.27/c1.95 < 10——白甲近无相/近零染色与替代腿互斥）维持派生 + 套注释登记依据。

**ΔE76 局限注记**：全部度量沿用 Lab ΔE76（与既有先例线同算法可比），色相 270° 附近蓝紫区压缩的已知色差空间局限存在；升级 ΔE2000 属全目录基线重算，未做。errorFixed 语义不做套级断言（红系套暗 primary 与 errorFixed 暗 #EF9A9E ΔE 仅 9.1-9.5 是鲑色收敛的结构性结果——错误语义由固定容器槽对 + 上下文承载，见 openRisks 备案）。

### 配色裁定演变（2026-09-23 大胆化 → 2026-09-29 R-2 换紫撤销 → 2026-10 轻涂装化/目录 20/夜相主权/hue-keep v2/图案层）

**现行裁定（夜相主权普及 + 亮态彩纸域，破「日间浅白/夜间深黑」平涂语法）**——蓝图 design-doc-v6.md §③/§④ L2+L3 兑付，逐套落值（全部过 G9-G16 全目录重算，380 对 FAIL=0）：

| 套 | 夜相/彩纸方向 | 关键落值 |
|----|----|----|
| 鎏金 Aurum | 夜相主权直采（=染色派生同值换轨） | pageBaseDark #1A1206（h77.6/c10.0/t6.0）+ onSurfaceFaint t78 转译 #CFBFAA（对底 10.31/Highest 卡 7.40——固定实色对沉金 Highest 卡 4.18 破 4.5） |
| 青花 | 夜相主权直采「夜瓷蓝」 | pageBaseDark #061423（h254.2/c16.9/t6.0，深瓷底钴蓝更幽）+ onSurfaceFaint #B9C1CF（对底 10.24/Highest 卡 6.07） |
| 市松翠格 | 夜相主权提 tone「市松夜」 | pageBaseDark #1A271D（h155.9/c12.4/t14.2，12-16 带）+ 暗卡浮起阶 #1D2B21→#1F2E24→#223226→#26382B（色度上行）+ onSurfaceFaint #B6C5B7（对底 8.62/Highest 卡 6.93） |
| 警示橙 Hazard | 夜相主权提 tone「黑黄警示夜场」 | pageBaseDark #30170D（h39.5/c17.9/t11.1）+ 暗卡浮起阶 #351B10→#381E13→#3D2015→#442519 + onSurfaceFaint #DDB9AC（对底 9.26/Highest 卡 7.62） |
| ArmoryGreen | 夜相主权同值换轨（hue-keep 替代腿三闸准入：t5.9<20 ∧ c18.17≥10 ∧ 底相距在册染色暗档相 1.0°<10°） | pageBaseDark #141400（h111.0/c18.2/t5.9）+ onSurfaceFaint t78 转译 #C5C2A8（对底 10.32/Highest 卡 6.87） |
| 洒红节 Gulal 暗态 | 夜相主权同值换轨（hue-keep 替代腿三闸准入：底相距在册染色暗档相 0.3°；与 熔岩红 暗同族 A=27.6 全目录同族最紧） | pageBaseDark #210C1C（h340.3/c19.4/t6.1，染色顶格）+ onSurfaceFaint #D4BAC9 转译（对底 10.29/Highest 卡 6.83） |
| 洒红节 Gulal 亮态 | 亮态彩纸「粉雾纸」（lightPageTone 96.5→95.0，全目录最深需求套） | 亮 bg #FFEBF7（h335.7/c10.3/t94.9；t96.5 处 gamut 只交付 c6.6 毁题材）+ 亮卡粉雾浓淡阶 #F9E6F1→#F4E0ED→#F0DAE8→#E9D2E2（色度上行）；重采样窗口下沿 96.3→**94.5**（=95.0 − 量化回读容差，守护断言随动） |

- **已有夜相主权/覆写不重复**：电光紫/熔岩红/猩红流金/晴空麦浪/Dracula/Nord/薄荷青/绯红夜城（pageBaseDark）、Matrix/青白霓虹（暗态覆写）、Gruvbox（亮态主权+暗态零漂移裁定）。
- **pageBaseDark 直采的机制前提（已由 hue-keep v2 收编）**：hue-keep 守护对 pageBase 套把断言对象从「primary 相 vs 种子相」改挂「底相双腿」——种子带主腿（<15°）∨ 夜相骨架同族替代腿（仅暗态三闸），语义与三闸见守护口径「hue-keep v2」详解；primary 相检查与 pageBase 存在性解耦（未直配态仍断言）。
- **G13 域拆随动**：pageBaseDark 套的文字域拆分后，onCardFaint 回落链（固定实色 #8A90A3 t67）对浮起卡阶（t14-21.6）不足 4.5，按 绯红夜城/薄荷青 先例走 onSurfaceFaint t78 同相转译（上表四套）。

**openIssues（已决收编 + 未决登记，未决项待维护者/后续批裁决）**：

**已决收编**（原 open 节移出）：

1. ~~夜相主权 × hue-keep 守护的机制冲突~~（原 4 套：ArmoryGreen/蓝金三色/朱砂宣纸/洒红节暗态的夜相底相与种子相距超 15° 带，直采即翻 hue-keep）——**已决**：hue-keep v2 双锚分腿落地（替代腿三闸，见守护口径详解）。ArmoryGreen/Gulal 夜相直采准入（底相距在册染色暗档相 1.0°/0.3°）；蓝金三色/朱砂宣纸 替代腿色度闸不过（c6.27/c1.95 < 10），三重依据登记维持派生（白甲近无相/近零染色豁免即题材；朱砂宣纸附加依据：暖墨夜底与警示橙暖黑同族 hue 距 1.0°，朱印纪律才是身份承载位）。

**未决（登记待裁）**：

2. **亮态白纸昼夜身份**：套级昼夜身份锁定未做——dark 仍由全局 themeMode 单键决定，暗黑身份套（猩红流金/Matrix/绯红夜城/青白霓虹/Dracula/薄荷青 等）亮态仍为白纸/粉纸派生；「套级昼夜锁定/亮态身份迁移」未立项（机制诊断未进施工队列）。
3. **青白霓虹 粉描边双态扩展**：暗态霓虹描边已落地（darkOverrides outline #E98FC1 / outlineVariant #5F6D74）；「描边母题」作为套身份的昼态扩展（design-doc-v5 C 档「昼夜身份锁定 + 霓虹描边母题」）未裁，亮态描边维持派生。
4. **绯红夜城 暗条两红**：绯红夜城 暗态样张全绯红族（secondary/selected/tertiary 同相，tone 距可 <10）——样张守护按「单色相暗态诚实呈现」登记（声部|强调不锁可辨，逐位互异 + 暗底承载分离）；是否拆红/引入对照块未裁。
5. **图案亮态**：surfacePattern 现只登记暗态（市松翠格/Hazard 亮态断言 null——亮态翠纸/暖纸走派生样张）；亮态图案未立项。
6. **Nord 余量**：官方档边界余量极薄登记——nord6 色度 5.995 差 0.005 不过蓝紫带地板 6.0（故亮底挪 nord5）；种子取 nord9 非 nord10（保留更浅派生主的区分余量，勿换 nord10）。（「Nord 余量 0.1°」的原始出处未在仓库文档找到，待核对。）
7. **亮态彩纸域按需放宽，非全员压暗**：绯红夜城 冷蓝纸（t96.5/c9.0）与 青白霓虹 潜航冷纸（t97.4/c6.8）经评估维持现值——绯红夜城 冷蓝已是非直采套中最彩纸面（异族骨架读感成立），青白霓虹 的 pale 青白质感属 Fidelity 保真身份（同「青白本相保真」裁定逻辑）；后续若维护者点名再放宽。
8. **Gulal 粉雾纸 95.0 为窗口下沿钉定套**：再降 tone（如向 90 系）须重新钉定下沿并全目录重算；95.0 处粉雾 c10.3 已达「纸面读出粉雾」的题材下限，不建议再深。

**现行裁定（目录裁定 26→20）**——删 6 留 18 + 条件留 2（市松翠格/Gulal：重设计兑付绑定，不兑付则删；**两套条件均已兑付转正式保留**——市松翠格 市松夜主权 + Checkerboard 图案层落地、Gulal 粉雾纸 95.0 + 暗态玫黑同值换轨落地）。LEGACY 读面映射：`Forest`→matrix-terminal、`Slate`→**nord-frost**（终裁 2026-10：映射契约自身条款「按浅 primary 距离复核」优先于裁定表初值——Slate 浅 primary #595E6C 实测最近保留档为 nord-frost ΔE76 12.07，gruvbox-warm-paper 为暖纸异族）、`abyss-glow`→mint-teal，`Teal` 连锁改指 mint-teal（原指深海荧光，单层查找无转发）——现值全表见上方「LEGACY_PRESET_MAP 现值」。数值验收（G9 190 对 ×2 态 = 380 对模式 FAIL=0、hue-keep 20 套、AA 全绿、tone 阶梯；最紧对 matrix↔青白霓虹 亮 S=12.01）见守护口径表。

**现行裁定（2026-09-29 重设计批 0 · R-2 拍板，撤销 2026-09-23 大胆化）**——维护者裁决（40-agent 评审团 adjudication-report.md，走 **Fidelity 路**）：

| 项 | 裁定 | 依据 |
|----|------|------|
| Default 档位 | Vibrant → **Fidelity** | Fidelity 派生 primary 与 ia-v2 mock #4F55BA 同色相同色度档（ΔE76≈10.4），真正拿到柔和靛紫；Vibrant 路派生 #363CFF 经视觉复评判「霓虹/工程感」非 ia-v2 气质，且 Vibrant+紫 seed 对 aiAccent ΔE76=16.56<30 必挂账本 |
| Default seed | 0xFF3B5BA5 → **0xFF4F55BA** | ia-v2 重设计稿 primary（维护者明确喜欢的靛紫）——「换血」真源（R-2） |
| 新增 Aurum「鎏金」 | seed=0xFFE8B84B + Vibrant（维持不变） | 2026-09-23 批新增，不受 R-2 撤销影响 |
| 恒紫可区分性 | 核验过线，不回退 | Fidelity 实跑（AppThemeSpecTest 真账本 6/6 绿，断言 ≥30.0 线）：全 10 预设深浅 20 组派生色对 aiAccent 恒紫最近 ΔE76 过线（最低组仍是 Violet 深色 S6 先例） |
| 配色预设可插拔 | 维护者定调（2026-09-29） | Fidelity 只是当前预制档，未来快速推出多风格配色预设——电紫（#363CFF+Vibrant）收为未来候选预设；新增预设 = data object + presets 一行 + AppThemeSpecTest 清单锚同步 |
| ⚠️ Fidelity 副作用 | tertiary 翻橙族 | 实测（快照锚）：亮态 tertiary #6F3C00 深棕橙、暗态 #FFB778 橙——极光第二枚两态均换色相族，批 0 对拍时真机过目暗色极光 |
| 预设库重构（4 组 11 套） | 删 6 个平淡 TonalSpot 项（海洋/日落/紫罗兰/蔷薇/青碧/琥珀——Custom 可复现或与新增档撞脸），新增 7 预设分入动漫/传统/情绪组；被删 id 经 `LEGACY_PRESET_MAP` 读面映射（海洋→青花、日落→警示橙、紫罗兰→Default、蔷薇→洒红节、青碧→深海荧光、琥珀→鎏金，DataStore 旧值不清洗） | 紫系预设被 aiAccent ΔE76≥30 线结构性排除（实测紫系高饱和种子 16.3~29.4 全挂线）；两两撞色矩阵淘汰 20+ 候选；TonalSpot 平淡项可被 Custom 色轮一比一复现，仅 Forest（全目录唯一一键沉稳绿）与 Slate（Neutral 低彩档 Custom 复现不了）留经典组 |

**历史裁定存档（2026-09-23 大胆化，已撤销）**：维护者真机反馈「配色可以大胆一点」→ Default 换 Vibrant（primary chroma 36→200，浅色 primary 485D92→0056D0 电蓝）+ 新增鎏金；恒紫可区分性核验 31.15~126.49 全过线。撤销原因见上行表依据列。

**现行裁定（表面染色 + 双种子 + 目录扩容）**——预设库 4 组 11 套 → **6 组 26 套**（新增国家/终端两组），机制三件套：①`SkeletonStain` 骨架染色（整支覆写 neutral/neutralVariant，见「三角色模型与表面染色」节）；②`secondarySeed`/`tertiarySeed` 双种子（强调与第三强调显式化）；③aiAccent 可区分性升级为**双通道**（常规线 ≥30 + 异族强调救援，见「守护口径」节）。Default 契约（seed #4F55BA + Fidelity + 零覆写）原样保留 = 零漂移锚，快照锚/LEGACY 映射/fromId 语义全部不动。当时数值验收（650 对可区分、AA 全绿、tone 阶梯、hue-keep 23 套）——现行口径见守护口径表（20 套 380 对）。

效果图（融合案 D 原型）色板对照（只读参考，非 token 值源）：底色 `#0a0b0e` / 面板 `#101116` / 琥珀主强调 `#e8b84b`（= Aurum seed）/ 紫 AI `#a78bfa`（= aiAccent 深色值，S6 已收编）/ 文字 `#d7dae3`。**双语义色裁定不变**：琥珀=确定性工具色跟 scheme 走（Aurum 即把它升为整主题主色），紫=AI 独立 token 恒紫（EasyToolboxColors 固定常量）。

**style 档位速查**（materialkolor 5.0.1 `PaletteStyle` 实际可用值）：TonalSpot（平静，primary chroma 36）/ Neutral（近单色）/ **Vibrant**（高饱和，primary chroma 200 保色相）/ Expressive（色相旋转，🔴 **全目录禁用**——primary = 种子相+240° 旋相毁身份色，见「三角色模型」节）/ Rainbow / FruitSalad / Monochrome / Fidelity / Content。

### 预览一致性（样张带双态 + 迷你窗条带回落）

设置页主题预览均经 `spec.colorSet(dark)` / `spec.specimenTriad(dark)` 派生（含 style 档位与套级覆写表）——预览与实机配色同源，直调 `dynamicColorScheme` 不传 style 会「预览偏灰、实机偏艳」：

- **网格 ThemeCard（非 Compact 档）= 三块构成样张带 `ThemeSpecimenBand`**：亮/暗双条各一条（每条 = 底/第二声部/强调三段连续色带，weight 3/2/1 面积递减——样张表达面积构成层级，不是等权色板），取色单源 `specimenTriad(dark)`；套级图案槽在场时底块叠加纹理采样（市松棋盘/警示斜纹，采样与绘制单源 `ThemeSpecimen.kt`）。双态并置让 pageBaseDark 主权套的暗态身份在橱窗有像素在场（单亮条会使暗态主权与配色判定被亮态取证面偏置污染）。三块取**声部色域**而非骨架三段的理由：97.9 锚轨非涂装套的骨架段近白、对白纸卡不可分——声部色域让非涂装套三块也两两可辨，兼「不对味」套指认诊断面。选中卡面 = `selectedContainer` 整面承载，名称/CHECK/选中描边走 onSelectedContainer 配套字链。
- **Compact 档回落与自定义项 leading = `ThemeColorDots` 迷你窗条带**：深浅两条（每条 = 骨架条带背景/卡片/深卡三段 weight 2.5/1.5/1 + 主/第二/第三强调三圆点）——骨架染色与多种子套在色卡上直接显形；颜色全部取自 `spec.colorSet(dark)` 派生。
- `ImageThemeConfirmDialog`（`ImageThemeCard.kt`）预览深浅两排色点同源派生。

---

## ThemeMode 与 AppTheme

```kotlin
@Composable
fun AppTheme(scheme: AppThemeSpec, themeMode: ThemeMode, content: () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // 装配 MaterialTheme(scheme.colorSet(dark)) + CompositionLocalProvider(LocalEasyToolboxColors)
}
```

**使用方式（App.kt）：**

```kotlin
val colorScheme by appViewModel.colorScheme.collectAsState()
val themeMode by appViewModel.themeMode.collectAsState()
AppTheme(scheme = colorScheme, themeMode = themeMode) { PageHome() }
```

持久化：`color_scheme` 存 `AppThemeSpec.id`；自定义种子色存 `custom_seed_color`（ARGB Long，-1 未设置）。恢复：`AppThemeSpec.fromId(id, customSeed)`。

---

## 自定义配色（两条入口，同一持久化路径）

| 入口 | 位置 | 流程 |
|------|------|------|
| 色轮挑选 | 设置页"自定义"卡片 → `ColorPickerDialog` | HSV 色相条 + 饱和度/明度渐变区 + Hex 输入 → 确认 |
| 图片提取 | 设置页"从图片提取配色"卡片（`ImageThemeCard`） | 拖入/选择图片 → `extractSeedFromImage()`（IO 线程：ImageIO 解码 → `toComposeImageBitmap()` → `themeColorOrNull()` 量化提取）→ 确认对话框预览深浅两排色点 → 应用 |

两者都调用 `AppViewModel.setCustomColorScheme(seed)` → `AppThemeSpec.Custom(seed)`，持久化 `color_scheme="Custom"` + `custom_seed_color`。启动恢复只读色值，**不重读图片**。

---

## 语义扩展色（EasyToolboxColors）

页面需要主题系统未覆盖的场景色时，**从 Local 读取，禁止硬编码**：

```kotlin
val toolbox = LocalEasyToolboxColors.current
toolbox.success      // 成功/在线
toolbox.successContainer  // 成功浅底（状态容器条 Success 槽）
toolbox.warning      // 警告/多设备
toolbox.warningContainer  // 警告浅底（状态容器条 Warning 槽，2026-09-12 收编 6 处 warning.copy(bgSubtle) 合成底）
toolbox.info         // 提示信息（DS-01 · 重设计批 0 Info 槽对补齐：亮 #0B57D0 / 暗 #64B5F6——design-language §2.4 裁决 9）
toolbox.infoContainer     // 提示信息浅底（状态容器条 Info 槽——DS-01 起摘除旧 warningContainer 弱黄借用；亮 #D3E3FD / 暗 #0D3B77）
toolbox.errorFixed        // 错误主色固定槽（蜂群回归 2026-10-06——tokens.css:136 精确等值：亮 #B3261E= M3 亮 error 本值 / 暗 #EF9A9E 稿面裁决定值；容器语义消费点用 errorContainerFixed）
toolbox.errorContainerFixed   // 错误容器固定槽（tokens.css:137：亮 #F9DEDC 恰= M3 亮 errorContainer 本值 / 暗 #5C1A16 裁决新增实色——StatusBanner/PathConfirmCard 等容器语义消费点经本槽直取，弃 scheme.errorContainer 动态派生）
toolbox.aiAccent         // AI 语义强调（恒紫，见下「AI 语义色」）
toolbox.aiAccentContainer    // AI 浅底（块容器/角标底色）
toolbox.consoleBg    // 日志控制台背景（单源解析 resolvedConsoleBg：套级直配 > 暗态骨架染色派生 > 静态基值；亮 #1E1E1E 恒定、暗基值 #141416）
toolbox.logError / logWarn / logInfo / logDebug   // 日志级别着色
toolbox.consoleSuccess / consoleInfo   // 控制台语义成功/信息色（亮暗同值 #81C784/#64B5F6，恒深值家族——对基值 #1E1E1E/#141416 双态 AA PASS，对接线后的暗态派生底同样 ≥4.5:1 由 ThemeDerivedContrastGuardTest 断言；主题派生 success/info 在浅色 consoleBg 上 2.61:1 不达，禁用）
toolbox.chartPalette // 图表分类色板（List<Color>，8 档；环形图扇区按下标取色，2026-09-12 替换 3 主题槽方案）
toolbox.floatShadow  // 浮层软影色（自绘 appFloatShadow——浅色蓝灰/深色纯黑；2026-09-25 elevation 阴影 GPU 硬灰环根因弃用后的替代投影语言）
toolbox.previewPaper / previewInk / previewAccent / previewPalette
                     // 布局预览色族（亮暗同值恒值族——预览纸是「文档」语义，与 consoleBg 恒深底对称：
                     // paper 纸底 #F2F3F7 / ink 纸上墨 #3C4043（8.9:1）/ accent 选中强调 #1565C0（5.15:1，
                     // 双槽对纸 AA PASS）/ palette 类族色板 6 档（索引序 = 布局检查器类族判定 ordinal）；
                     // 消费面 = 设备观测「布局」子页预览图（LayoutDiagramPane），洗底透明度走 AppAlpha 档）
```

（语义色与种子色生成体系无关，保持手写深浅两份。）

**深浅两态值要点（D5 · 重设计批 7.5 补记）**：`success` 亮色 = `#1B6C4A`（暗色 #81C784）、
`warning` 亮色 = `#8A5300`（暗色 #FFC107）——亮侧取深值保证浅底对比度（EasyToolboxColors.kt
:53/:56）；`warningContainer` 合成底（warning.copy(bgSubtle) 预洗）随亮值联动，勿在
消费点二次调 alpha。`info` 槽对（DS-01 · 重设计批 0）：亮 = `#0B57D0`（ia-v2 §2.4
裁决 9 直译）/ 暗 = `#64B5F6`；`infoContainer` 亮 = `#D3E3FD` / 暗 = `#0D3B77`——
StatusBanner Info 槽正源，**Info 语义不再借用 warningContainer 弱黄**。错误容器固定槽对
（蜂群回归 2026-10-06）：`errorFixed` 亮 = `#B3261E`（M3 亮 error 本值）/ 暗 = `#EF9A9E`；
`errorContainerFixed` 亮 = `#F9DEDC`（恰 = M3 亮 errorContainer 本值）/ 暗 = `#5C1A16`
（tokens.css:137 裁决新增实色）——旧消费点走 `scheme.errorContainer` 动态派生（暗 ≈#8C1D18
系偏亮偏红）已迁固定槽。全部值以 `EasyToolboxColors.kt` 现场为准。

### AI 语义色（aiAccent / aiAccentContainer，2026-09-22 S6 定稿）

融合案 D 双语义色的紫侧：**琥珀=确定性工具色跟 scheme 走，紫=AI 独立 token 恒紫**——
任何配色/换色下与工具色可区分是需求本身，故为**固定常量、不随 seed 派生**（Violet 预设
seed 就是紫色、Custom seed 可任意，派生必然撞色相；裁决 7「AI 色独立 token（换色支持）」）。

| token | 浅色值 | 深色值 | 用途 |
|-------|--------|--------|------|
| `aiAccent` | `0xFF7C3AED`（violet-600） | `0xFFA78BFA`（原型 #a78bfa） | AI 角标（dock/tab 条/应用中心/palette 候选）、状态栏 AI 段、紫块左色条/图标 |
| `aiAccentContainer` | `0xFFEDE9FE`（violet-100） | `0xFF4C1D95`（violet-900） | 紫块头底色等 AI 容器面 |

- **消费面**（S4/S5/S6 已接线，新 AI 视觉一律走本 token）：证据流紫块头（`ui/widget/evidence/EvidenceTimeline`）、dock AI 角标（`PageHomeMenu`）、tab 条 AI 角标（`WorkspaceTabs`）、应用中心 AI 角标（`PageAppCenter`）、palette 置灰页角标 ×2（`CommandPalette` 候选行）、状态栏 AI 段（`AppStatusBar`）。**置灰本体色**（图标/文字 `onSurfaceVariant.copy(contentFaint)`）不动——角标恒紫是「AI 应用」的语义标记，本体置灰是「不可用」的状态标记，两者语义正交。
- **换色可区分性核验**（S6，Lab ΔE76 量化）：9 预设深浅 18 组 primary/secondary/tertiary 派生色对 aiAccent 的最近对 = **Violet 深色 primary（ΔE=31.5、L* 差 15.5）**——muted 派生 vs 高饱和常量可区分；plan 预判的 Violet×浅色撞色点实测 ΔE=67（最安全组）。aiAccent 对 9 预设 surface/surfaceContainer 对比度：浅色 4.89~5.44、深色 6.00~6.83；aiAccentContainer 与底色 Lab 距离：浅 ΔE=6.9~18.2、深 ΔE=72~81——**色相恒紫不动、明度无需微调（原型色即定稿色）**。真机换色截图复核仍按 S6 验收清单走（18+2 张）。

---

## 尺寸设计 token（Dimens / AppShapes）

间距、图标尺寸、圆角统一从 `Dimens` / `AppShapes` 引用，**禁止新写 `xx.dp` 字面量**（jvmTest `arch/UiArchitectureGuardTest` 源码扫描强制，例外见下方「有据保留」清单）。需要新档位时先在 token 文件中扩充定义再使用，禁止在调用点就地写值。

### 排版 token（DesktopTypography，2026-09-19 P2-B4 密度批）

`MaterialTheme` 的 typography 由 `AppTheme` 统一装配为 `DesktopTypography.typography`——**Win11 Type ramp 桌面阶梯**（caption 12 / body 13-14 / subtitle 20 / title 28，替换 M3 移动优先默认），全部页面自动生效，消费点仍按 `MaterialTheme.typography.xxx` 引用、零迁移。等宽档 `DesktopTypography.mono`（12sp 系统等宽——日志/时间戳/路径/十六进制场景专用，JBR 下即 JetBrains 家族线）。派生档：`monoTag`（11sp Medium——日志 TAG/命令名/会话 id 行内标签）、`monoInput`（13sp/行高 18——命令/路径输入框编辑态）、**`tabLabel`**（GLB-13 · 批 7.5：12sp SemiBold、行高 16——文档 tab 文字归一恒定档，稿面 `.tab` fs-12/600 直译；仅供 WorkspaceTabBar tab 行消费，勿挪作他用）。

**桌面控件高度三档（`Dimens`，Carbon 密度体系）**：`controlCompact`（26dp 紧凑：表格行/工具栏）、`controlStandard`（34dp 标准：表单/按钮）、`controlSpacious`（40dp 宽松：首页卡/拖放区）。

**命令面板 omnibar（`Dimens`，2026-09-19 P2-B1 非模态化批）**：`paletteWidth`（560dp 面板宽）、`paletteTopOffset`（120dp 顶部弹出偏移）、`paletteListMaxHeight`（420dp 候选列表封顶滚动）。（原 `overlayShadowElevation` 8dp 全仓无消费点，2026-09-25 死 token 清账删除。）

**日志工作台三栏（`Dimens`，2026-09-20 P4-C2 三栏布局批）**：`workbenchSidePanelWidth`（280dp 左栏——结构化输入表单 + run 列表，ShellToolsPage 右栏同量级）、`workbenchReportPanelWidth`（320dp 右栏——报告面板）、`workbenchHistogramHeight`（16dp——R-5 对稿 .histo sp-16 证据流时间直方图）、`workbenchStageBarHeight`（36dp 管线阶段锚点条）。三栏固定尺寸全部经 token 收口——logworkbench 页零白名单条目目标同飞书桥接页先例。

**证据流块化（`Dimens`，2026-09-22 调查工作台 S4）**：`evidenceBlockBarWidth`（2dp 块头左缘语义色条宽——对齐 chat BarRow 色条语言）、`evidenceBlockHistogramHeight`（14dp 块头迷你直方图高度——R-5 对稿 .histo.mini sp-14——主直方图 72dp 档的缩小形态）。

**调查工作台五舱（`Dimens`，2026-09-22 S2）**：`spaceBarWidth`（240dp 调查空间栏轨宽）、`tabBarHeight`（34dp 主区 tab 条高）、`railWidthCompact`（56dp dock 纯图标轨——🔴 2026-10-03 换代批起 68dp 贴边实底整列，本行 56 为历史值；见「dock 轨换代」注）、`dockItemSize`（40dp dock 图标钮触摸目标）、`dockItemBackgroundSize`（32dp 选中态 tonal 圆底）。

**蜂群回归 2026-10-06 新 token（稿面逐字直译，全部带对稿出处）**：
- `Dimens`：`themeDotSize=10`（system.html:34 .theme-dots 点 10px）/ `memoryConsoleMinHeight=150`（system.html:748 .console min-height）/ `iconTiny=13`（workbench.html:402 .ico.tiny）/ `heroIcon=28`（cockpit 稿 fs-28 hero 状态图标）/ `barChartHeight=96`（stats 稿 .lb-bars calc(sp-24×4)）/ `dragbarGripWidth=46` / `dragbarGripHeight=3`（shell-tools.html:46 .grip 46×3px）/ `workbenchEmptyIcon=40`（workbench.html:457 空态 svg 40px——EmptyStateView iconSize 域覆写）/ `fieldControlNarrowWidth=110`（tools-suite.html:513 .ctl .txt 窄档 inline）/ `fieldControlMinWidth=220`（mock-base .ctl .txt min-width:220px——SYS frow 化批）；`chartBarGap` 12→4（stats 稿 .bars gap sp-4）/ `chartMiniBarHeight` 8→6（稿迷你条 6px）/ `chartHeight` 160→120（环形 120px）/ `contentViewportPaddingBottom` 50→42（shell.html:28 .body--botspace 逐字）/ `sideBarWidthDefault` 280→340（维护者裁定：shell-tools.html:48 .side 域稿逐字）；`evidenceBlockBarWidth` 2→4（workbench.html:56 --sp-4）
- `AppAlpha`：`blockHeadWash=0.55` / `blockHeadWashStrong=0.60`（workbench.html:51/55/:59 .blkhead/.stagebar 洗底两档）/ `borderSubtleOverlay=0.14`（tokens.css:25 --border-subtle ×onSurface）/ `consoleHeadDivider=0.18`（shell-tools.html:57 .ol-hd border-bottom color-mix 18%）
- `SurfaceColors`：`surfaceHighest` 槽（--surface-highest 系=M3 surfaceContainerHighest——AppSwitchRow 关态轨道底）；`onSurfaceFaint` 改稿面固定实色（亮 #5B6070 / 暗 #8A90A3——派生合成退役）/ `borderSubtle` 改 onSurface×0.14
- 新共享组件：`StatusDot`/`DotState`（mock-base.css:294-298 .dotok/.dotrun/.doterr 6dp 三态直译）/ `KbdText`（mock-base.css:350 .kbd 键帽——fs-11 mono + surface-deep 底 + r-sm + 发丝内描边）/ `IcBtnContainer`（.ic-btn raised 底+发丝边小钮，MICS 32 命中区收进本体）/ `FormRow`（mock-base .frow 三段横排——lab 190 定宽 SemiBold + hint 中段 + ctl 右缘；SYS frow 化维护者裁定批）/ `SectionCard.titleStyle` 参数（内容卡 .chead .t fs-16/650 档覆写——zone 14 与内容卡 16 两档并存）

**2026-10-04 收口登记批（完备性批评家 F-7——对稿蜂群 16 commit 的新/改值 token 补账）**：`switchTrackWidth=36` / `switchTrackHeight=20` / `switchThumbSize=16` / `switchThumbInset=2`（R-9 自绘开关稿 .switch 逐字）、`dockIconSize=22`（GLB-D1——.rail .ico）、`hitlCardWidth=360`（WB-D4——.wb-float）、`statusLabelWidth=120`（LB-D4——.srow .mut）、`fieldLabelWidth=190`（LB FieldRow——.frow .lab）、`mcpLogHeightMin=96`（R-11——MCP 日志 min-height 语义）、`reportTableColTs=96` / `reportTableColLvl=60`（WB-D3——mdtable 96px 60px 1fr）、`emptyStateIcon=22`（TL-D5③——稿空态 22px）、`headerBtnPaddingV=5`（LB-D1——.ic-btn padding 5px 12px）、`paletteWidth=560`（R-10 终审——.pal 施工恒 560）、`workbenchHistogramHeight=16` / `evidenceBlockHistogramHeight=14`（R-5——.histo sp-16/sp-14）、`trafficLightDot=12` / `titleBarPaddingH=20`（标题栏对稿）。

**2026-10-09 全项目修复批登记**：`Dimens.mcpLogHeightMax=240.dp`（MCP 日志台封顶——200 行环形事件流无 max 封顶时新行恒落视口外；取值 = consoleHeightMin 同档日志台常规可视高度；消费 `McpExposePage.kt` heightIn(min, max) 定高窗 + LazyColumn 自滚）；`EasyToolboxColors` 暗侧 `successContainer` #1B5E20→**#16421A**、`warningContainer` #9A6B00→**#6E4D00**（容器加深至槽对文字对比度达 AA 4.5:1——#81C784/#16421A=5.71、#FFC107/#6E4D00=4.73，旧值 3.91/2.88 FAIL；文字色 #81C784/#FFC107 零改动；数值由 `SlotContrastGuardTest`（core jvmTest，8 对全断言）在 token 单源钉死）。

**三舱与断点（`Dimens`，2026-09-21 交互重设计 P0；R 批 2026-09-22 补 `sidebarRailWidth`）**：`sideBarWidthMin=220` / `sideBarWidthDefault=340`（2026-10-06 维护者裁定：shell-tools.html:48 `.side width:340px` 域稿逐字，胜 shell 全局稿 .inspector 280——旧 280 系三处历史固定宽归一档）/ `sideBarWidthMax=360`（侧栏槽位拖宽边界）、`sidebarRailWidth=48`（R 批：右侧化折叠窄轨 icon 轨宽——小于 dock 的 `railWidthCompact=68`（2026-10-03 对稿 .rail 68px），次级层视觉弱于左 dock）、`splitterWidth=4` / `splitterHitWidth=12`（侧栏分隔条视觉宽/命中宽——Fitts：命中面大于可见面）、`focusRingOffset=1`（焦点环间隙）、`screenPaddingCompact=16`（Compact 档主区内边距）、`breakpointCompactMax=1024` / `breakpointMediumMax=1440` / `breakpointWideMax=1760`（响应式四档阈值）、`workbenchReportPanelWidthWide=400`、`toastWidth=360` / `toastBottomOffset=46`（2026-09-24 批 3.7-b 勘误 12→46：预览稿 `.toast{bottom:46px}` 直译——toast 底缘在状态栏胶囊顶缘之上 12px，修复玻璃叠玻璃重叠）/ `toastMaxStack=3` / `undoBarHeight=40`（StatusHost 浮层）、`inspectorTopClearance=54` / `inspectorBottomClearance=50`（批 3.7-b：预览稿 `.inspector{top:calc(8px+46px);bottom:calc(8px+42px)}` 直译，修复 tab 条胶囊×inspector 玻璃叠玻璃）、`helpCardMaxHeight=480`（F1 参考卡，P4）。**SegmentedControl 四 token（2026-09-24 批 5-a，预览稿 L339-343 `.seg/.si` 直译）**：`segmentedPadding=3`（容器内边距）、`segmentedItemPaddingVertical=6` / `segmentedItemPaddingHorizontal=18`（分段项行内边距——2026-10-03 对稿 .seg .s padding 6px 18px 逐字）、`segmentedItemGap=2`（项与容器壁间隙）；选中态 = surfaceDeep 底 + primary 字 + borderEmphasis 描边，零动画（Q3 拍板）。（评审修复批 2026-09-23：`panelHandleHeight`/`panelGrabberWidth` 已随 S2 底面板退役删除——生产消费为零，死 token 清账。）

**容量三档（`Dimens`，2026-09-21 P3 ListCapacity 常量组，Int 非 dp）**：`listBoundedMax=5000`（有界封顶——Shell 输出，`ShellExecutionEngine.MAX_OUTPUT_ITEMS` 引用此 token）、`listRingMax=1000`（环形缓冲——桥接日志台）、`listDomainMax=30`（域对象清单——Workbench run 列表）。

**批 6/5 押后 token 三件（已实存消费，2026-10-02 终验蜂群收口入册）**：`settingsNavWidth=200`（SYS-01 · 批 6：`SettingsTwoPane` 分类导航恒窄列宽，预览稿 system.html `.pane-nav` 200px 直译——消费 `ui/widget/SettingsTwoPane.kt`）、`shellTreeHeightMin=320`（SH-17 · 批 5：Shell 页工具树区最小高度硬底——输出台拖拽上限钳制同源引用，S2 事故红线「树区不可被拖拽饿成 0」；消费 `ShellToolsPage.kt` + `ShellOutputPanelWiringGuardTest`）、`memoryDialogHeightMax=320`（SYS-02 · 批 6：记忆管理对话框内容区两列各自滚动上限 = composerHeightMax×2；消费 `AiSettingsPage.kt`）。三者均已随对应批落地并有真实消费点，非押后。

### 取值定义

**间距阶梯（`Dimens`，4dp 网格为主，2/6/10 为半步档）：**

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

语义别名（页面级高频场景，直接映射阶梯）：`screenPadding`（= space24，各功能页统一内边距）、`screenSpacing`（= space16，页面顶层元素间距）。

**图标五档（`Dimens`）：**

| token | 值 | 用途 |
|-------|-----|------|
| `iconSmall` | 16dp | 列表/状态类小图标 |
| `iconMedium` | 18dp | 任务卡状态等中图标 |
| `iconLarge` | 20dp | 按钮内大图标 |
| `iconHero` | 40dp | 拖放区主图标 |
| `iconDisplay` | 64dp | 失败态 hero 图标（App.kt InitFailedView；原欢迎页 hero 已随 PageDefault 退役） |

**圆角三档（`AppShapes`，预构造 Shape 实例；B3 密度收敛批 small 8→6dp——桌面密度收拢，medium/large 仍与 M3 默认对齐）：**

| token | 值 | 用途 |
|-------|-----|-----|
| `small` | 6dp | 紧凑卡片（任务卡、工具按钮） |
| `medium` | 12dp | 面板/拖放区/分组卡 |
| `large` | 16dp | 大容器（内容卡默认档——对话框已随 F-3 裁决 2026-10-05 改走 `glassSheet` 20dp：稿面 --r-xl「浮层大件」家族 6/7 处一致 + 两处注区字面申报） |

**图表尺寸（`Dimens`，bridge 统计图）：**

| token | 值 | 用途 |
|-------|-----|------|
| `chartHeight` | 160dp | 图表主图高度（柱状/环形统一） |
| `chartCompactHeight` | 56dp | 驾驶舱「今日统计」卡迷你柱状图高度（预览稿页③ 56px 直译；批 5-b 新增） |
| `titleBarHeight` | 40dp | 自绘标题栏高（批 6 edge-to-edge；预览稿 .titlebar ≈38px 收敛 4dp 网格档） |
| `trafficLightDot` | 12dp | 标题栏窗口控制圆点直径（2026-10-03 对稿 mock-base .titlebar .dot 12px 逐字——旧 11 系 v10 旧稿直译已废） |
| `titleBarPaddingH` | 20dp | 标题栏水平内边距（2026-10-03 对稿 .titlebar padding 0 var(--sp-20) 逐字） |
| `resizerThickness` | 8dp | 自绘缩放边条厚度（批 6；CMP 内建 UndecoratedWindowResizer 默认 8dp 同值——内建件真机实测增益异常 1px 级，自绘替换） |
| `chartBarWidth` | 24dp | 柱状图单柱宽度 |
| `chartBarGap` | 12dp | 柱状图柱间距（= space12 档，语义独立保留） |
| `chartStroke` | 24dp | 环形图环厚 / 横向条形图条高 |
| `chartMiniBarHeight` | 8dp | 发送者卡内嵌迷你条形高度 |
| `chartMiniBarWidth` | 96dp | 发送者卡内嵌迷你条形宽度 |

**控制台高度与细粒度装饰（`Dimens`，飞书桥接日志台 / 行内进度圈；原「有据保留」值收编）：**

| token | 值 | 用途 |
|-------|-----|------|
| `consoleHeightMin` | 240dp | 日志/输出控制台最小高度（整页滚动下 weight 不可用；2026-09-12 起日志台经 LazyColumn fillMaxHeight 恒占 min(可用高, max)——滚动条 fillMaxHeight 在 heightIn 弹性容器内钉死交叉轴，见 ui-style-guide 4.7f 接入坑） |
| `consoleHeightMax` | 420dp | 日志/输出控制台最大高度（日志多时封顶） |
| `questDropZoneHeight` | 80dp | 流程页附件拖放区高度（聊天区上方紧凑态——FileDropZone 默认 140dp 矮版，2026-09-20 Quest 域） |
| `strokeThin` | 2dp | 细描边宽度（行内进度圈 strokeWidth、边框线宽） |
| `configModifiedBarWidth` | 3dp | 配置行「已修改」左缘竖条宽（飞书桥接 IA 批① 2026-09-27，预览稿 `.frow.mod` inset 3px 直译） |
| `progressInline` | 14dp | 行内进度圈直径（按钮内 loading 等紧凑场景） |
| `scrollBarWidth` | 8dp | 桌面滚动条宽度（`VerticalScrollbar` 细杆，2026-09-12 收敛 4 处 LazyColumn） |

**布局子页线框图（`Dimens`，设备观测空间布局子页双栏图舱几何与绘制档位）：**

| token | 值 | 用途 |
|-------|-----|------|
| `layoutDiagramMinWidth` | 280dp | 双栏图舱最小宽（防被树舱挤死；workbenchSidePanelWidth 同量级独立语义） |
| `layoutDiagramHeightCompact` | 200dp | Compact 档线框图回落高（consoleHeightMin 同量级独立语义——图固定高、树独立滚动） |
| `layoutDiagramStroke` | 1dp | 线框图普通线框描边宽（选中描边走 strokeThin） |
| `layoutDiagramLabelMinHeight` | 12dp | 线框图节点标签绘制阈值（节点视口高 ≥ 此值才绘 label——全量 measure 是绘制主成本） |
| `layoutPreviewSkeleton` | 2dp | 预览文本占位骨架条高（TEXT 类族无文本节点的线框占位——源无文本内容时如实画骨架不编造文字） |

### 使用示例

```kotlin
// 页面骨架
Column(
    modifier = Modifier.fillMaxSize().padding(Dimens.screenPadding),
    verticalArrangement = Arrangement.spacedBy(Dimens.screenSpacing),
)

// 组件内
Card(shape = AppShapes.small) {
    Row(
        modifier = Modifier.padding(horizontal = Dimens.space12, vertical = Dimens.space10),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space10),
    ) {
        Icon(imageVector = ..., modifier = Modifier.size(Dimens.iconMedium))
    }
}
```

### 有据保留（不入 token 的字面量）

以下数值有独立依据（Material 规范的触摸目标、控件高度等），**有据保留**、不强制替换：

- 窗口默认尺寸：`main.kt` DpSize(1280, 860)（白名单单一真源回填——`UiArchitectureGuardTest` RAW_DP 白名单既有条目，此前漏登记；改动值须同 commit 校准 `ShellOutputPanelWiringGuardTest` 默认窗封顶基线（633/253dp）与 `docs/architecture.md` 窗口几何表述）
- IconButton 触摸目标：**32 = MICS 统一**（XA-20 · 重设计批 11：图标钮命中区一律
  `Dimens.minimumInteractiveSize` 32dp——六文件 24/28/36dp 字面量全收编，`Feature-shelltools`
  IconTouchTargetGuardTest 钉死；不再有 24/28/36 三档字面量豁免）；行内空间受限的紧凑档 20.dp（`ShellCustomEditorDialog` 删除选项按钮——`ShellOutputPanel` 清除关键字按钮条目已退役：2026-10-14 UX 走查批命中区扩至 `minimumInteractiveSize`，深底 console 语境 20dp 难点中）
- 管理行动作钮等宽基线：`Dimens.manageActionMinWidth` 164.dp（按最长钮「新增自定义命令」7 字文案 + 前导图标 + 按钮内边距推导，≥最长钮取最近偶数档；`widthIn(min=…)` = 等宽基线非硬等宽，极端字号缩放允许内容宽溢出）
- 拖放区默认高度：140.dp（`FileDropZone` 默认参数）；`ImageThemeCard` 100.dp 条目已退役（蜂群回归 2026-10-06：入口区随 system.html:365-367 zone 单行化改 zrow 行式，100dp 大拖放区删除，白名单值移除）
- strokeWidth：2.dp（进度指示器描边、边框线宽；🔴 R3 2026-09-25 起 `ShellOutputPanel` 行内进度圈 14dp/2dp 已收编 `Dimens.progressInline`/`strokeThin` token，该文件白名单值移除 14/2）
- 细粒度装饰小图标：13 / 14.dp（Tune 等内联小图标）；状态圆点 10.dp 条目已退役（SH-05 · 重设计批 10：收编 `Dimens.dotIndicatorSize` 6dp token——ShellToolsPage 设备状态胶囊行消费，白名单条目随之清零）
- 控件功能尺寸：输入框高度（140/160dp）
  - （2026-09-23 反馈批二：过滤栏高度 44.dp 随裁切修复删除——`ShellOutputPanel` 过滤框改走 `OutlinedTextFieldDefaults.DecorationBox` 官方定制路径，紧凑档 contentPadding 上下各 8dp 走 `Dimens.space8` token，不再有固定高度；44dp 白名单值同步清理）
  - 🔴 2026-09-21 交互重设计 P2 净减兑现（Shell 域随三舱迁移消费点消失）：输出面板默认高度 240dp（`ShellToolsViewModel` 面板状态面删除，形态上提 `host/PanelHost` + `panel_*` key）；拖拽把手可点击区 16.dp 与视觉条 32×4.dp（`ShellToolsPage` `DragHandle` 删除，底面板把手改走 token）；`ShellOutputPanel` `tonalElevation` 1.dp（页内旧变体删除）
- 色轮与取色对话框（settings 渐进迁移范围）：`ColorPickerDialog` 预览色点 48.dp、色点描边 1.dp、色相条高 16.dp、饱和度/明度渐变区高 180.dp、取色指示圈 16.dp + 2.dp 描边；`ImageThemeCard` 种子色点 40.dp、预览色点 22.dp、标签列宽 64.dp、loading 行 strokeWidth 2.dp（🔴 DS-04 · 重设计批 0 起垂直内边距 14.dp 归 `Dimens.space14` token、白名单值移除；蜂群回归 2026-10-06 起 zone 单行化，拖放区 100dp 删除、白名单值随条目更新一并移除——见上条）
- 主题选项行/主题卡（`ThemeShowcase.kt`，主题橱窗族自 `PageSettings.kt` 迁出后所在文件）：边框宽 1.dp / 2.dp（未选中/选中态——`ThemeOptionItem` 与 `ThemeCard` 同款配方；垂直内边距 14.dp 已归 `Dimens.space14` token，白名单值移除）
- （2026-09-22 S2 调查工作台：`PageDefault` 欢迎页随融合案 D 退役，40/20/32dp 白名单条目与下文「有据保留」条目同步清理）
- `0.dp`（elevation、无尺寸占位等零值——全局条目，守护规则正则直接排除，无需逐文件登记）
- `AppShapes.capsule` 消费面语义扩张登记（U19 投屏多指手势反馈件，2026-10-15）：投屏修饰键模式徽标（帧区左下小浮件）纳入「胶囊横条小件」消费面——徽标为小件（宽 ≪ 720dp），非 chrome 长条（`glassBar` 域），「>720dp 禁全圆」边界不触；后续泊指计数胶囊（「泊 N/3」）同族同口径。登记先于消费（设计稿明确的先决项），`AppShapes.kt` 口径注释同步改写。

**架构守护测试例外**（Wave 7 守护测试白名单的单一真源，以"文件 + 理由"登记；新增例外必须先在本清单登记再入白名单）：

**DS-17 · 重设计批 0（2026-09-30）新增豁免登记**：
- **EMOJI 四文件**：`EvidenceModels.kt` / `WorkbenchPrompts.kt`（distill 输出协议符号 ✓/✗——解析标记，非 UI 图标代用）；`KbImportCli.kt` / `DistillPage.kt`（CLI echo 与 ErrorBanner 横幅文案的 ✅/❌/⛔——CLI 生态惯例输出）。
- **NO_GLOW 豁免目录**：`ui/widget/glass/`（`GlassMaterials` 柔边用途；DL-F06 禁 glow 的唯一目录级例外，独立 @Test 形态）。
- **DASH_SCOPE 缓挂**：`dashPathEffect` 全仓禁（DL-M20 虚线只给降级态）；`ui/widget/decor/` 豁免目录待 DS-09（施工批 3）落地后启用。
- **HALF_PIXEL_SP 零白名单**：xx.5.sp 半像素字号无任何豁免（DL-F09）。

- `Motion.kt`：`tween` / `spring` 唯一定义点（动画规格工厂本体，见下方"动效设计 token"章节）
- （R3 白名单清理 2026-09-25：`ImageThemeCard.kt` loading 行 `MaterialTheme.shapes.medium` 改 `AppShapes.medium`——MATERIAL_SHAPES 整文件豁免删除，规则归零例外）
- 裸 dp 字面量（RAW_DP 规则）：token 定义文件 `Dimens.kt` / `AppShapes.kt` 整文件豁免（token 本体）；其余文件按「文件 → 允许值集合」豁免（即上方「有据保留」清单逐条对应，如 `ThemeShowcase.kt` → 1/2dp），同文件未登记的新值仍会违规；`0.dp` 零值全局豁免（规则正则直接排除）。白名单允许值必须仍真实存在于对应文件（jvmTest 防腐化断言强制），例外代码删除/替换后须同步清理白名单与本清单。

### 迁移状态与新代码规范

- 已一次性完成值等价迁移的范围：`widget/`（FileDropZone、TaskCard）与 `page/shelltools/`、`page/xlog/`、`page/filesplit/`、`page/logtag/`、`page/timestamp/`；替换均为值等价（如 `8.dp → Dimens.space8`、`RoundedCornerShape(12.dp) → AppShapes.medium`），视觉无变化。
- RAW_DP 守护测试落地时的顺手值等价替换（均为有档值，视觉无变化）：`ColorPickerDialog` 间距 16/12/4.dp → `Dimens` 档、圆角 8/12.dp → `AppShapes` 档；`ImageThemeCard` 间距 16/12/6.dp → `Dimens` 档、loading 行进度圈 20.dp → `Dimens.iconLarge`。
- 页面骨架已统一（Wave 1）：各功能页（xlog / filesplit / logtag / timestamp / settings / debug；飞书桥接页已于 M6/M7 重写并接入 `widget/PageScaffold`，`headerSticky = true` 吸顶头 + 页内 5 页签）迁移至 `widget/PageScaffold`；`ShellToolsPage` 紧凑双栏工作台为有意例外，不套 PageScaffold（2026-09-21 P0 起三页 ShellTools/LogWorkbench/Flow 已迁 `PageScaffold(density = Dense)`——ShellToolsPage 例外解除）。settings / home 页内剩余零散 `.dp` 字面量按上方「有据保留」清单登记，改到哪个文件顺手替换哪个，不专项批量替换。（原欢迎页 `PageDefault` 已随调查工作台 S2 退役删除。）🔴 飞书桥接页达成**零白名单条目**：日志台高度走 `consoleHeightMin`/`consoleHeightMax`、行内进度圈走 `strokeThin`/`progressInline`，无任何裸 dp 进入守护白名单（原「有据保留」的 240dp/2dp/14dp 桥接用途已收编为这 4 个 token；上方「有据保留」清单里同值条目仅对应 shell/settings 等**非桥接**文件的仍在用字面量，与白名单一一对应，不得删）。白名单现行规模（**XA-20 · 重设计批 11 · 2026-10-02 更新；2026-10-14 UX 走查批 ShellOutputPanel 清除钮 20dp 收 MICS 32 条目退役 10→9**）：整文件豁免 **7 键**（`Motion.kt`/`Dimens.kt`/`AppShapes.kt` + **EMOJI 豁免四文件**：`EvidenceModels.kt`/`WorkbenchPrompts.kt`——distill 输出协议符号 ✓/✗ 解析标记非 UI 图标；`KbImportCli.kt`/`DistillPage.kt`——CLI echo 与 ErrorBanner 横幅文案，CLI 生态惯例）+ RAW_DP 值级豁免 **9 键** = **16 键**（XA-20 图标钮 24/28/36dp 收 MICS 32：ShellHistoryPanel/ShellToolsParentNode 两值面清零**整条退役**、ShellCustomEditorDialog/ShellToolsChildNode/TaskCard 三文件**值集收缩**，ShellOutputPanel 20dp 清除钮条目随 2026-10-14 命中区扩 MICS **整条退役**——前口径 SH-05 · 重设计批 10：12+7=19；以 `UiArchitectureGuardTest` 白名单区现场计数为准，与 ui-style-guide.md 7.1 口径一致；总数锚断言 `raw dp whitelist total key count matches docs` 钉死键数与文档同步。DS-04 值消解注记：PageSettings/ImageThemeCard 的 14dp 已归 `space14` token，两文件白名单条目保留其余真实命中值——值消解不消条目）。
- **2026-09-21 交互重设计 P2 净减记录**：Shell 域随三舱迁移净减 1 整条目（`ShellToolsViewModel` 240dp）+ ShellToolsPage 9 值→1 值 + ShellOutputPanel 6 值→5 值——白名单 19 值级→14 值级。这是「三舱骨架」批的核心验收指标之一。
- **S7 终态复核（2026-09-22 调查工作台 S7；键数 2026-09-23 评审修复批更正）**：白名单规模 17 键（整文件 4 + 值级 13）——S7 复核时误报「维持 18 键（4+14）」：S2 提交 f4d4c8a 删 PageDefault 条目后值级已 14→13，S7 现场计数与 docs 未同步改数（评审抓出失实，本批更正并补总数锚断言 `raw dp whitelist total key count matches docs`——以后增删条目断言会提醒同步 docs）。Shell 域 7 条路径（ShellToolsPage/ShellHistoryPanel/ShellCustomEditorDialog/ShellToolsGroupDialogs/ShellToolsChildNode/ShellToolsParentNode/ShellOutputPanel）全部仍有真实命中（防腐化断言绿）；PageDefault 条目已随 S2 清理，无回潮。
- **mono 排版收口（2026-09-21 P3）**：`FontFamily.Monospace` 字面量 40 处/17 文件全数替换为 `DesktopTypography.mono`（值等价 copy：bodySmall 直接 mono；bodyMedium/labelSmall/labelMedium 按原 slot 差异 copy 字号/字重）——新代码等宽场景一律 `DesktopTypography.mono`，不再写 `fontFamily = FontFamily.Monospace`。
- **新页面/新组件强制使用 token**：间距/图标尺寸/圆角一律引用 `Dimens` / `AppShapes`，code review 拦截新增 `.dp` 间距字面量。

---

## 卡片样式设计 token（AppCardStyles）

卡片容器配色统一从 `AppCardStyles` 引用，**禁止就地拼装 `CardDefaults.cardColors`**；`ElevatedCard` 投影卡已全量退场（架构守护测试以源码扫描强制），卡片一律扁平 tonal 语言。配套骨架组件 `widget/PageScaffold`（`screenPadding` + `screenSpacing` + `PageHeader`）统一各功能页骨架（见上方迁移状态）。

### tonal 配方（当前唯一落地档）

| 属性 | 取值 | 说明 |
|------|------|------|
| `containerColor` | `surfaceVariant.copy(AppAlpha.bgTask)` | 值等价自 TaskCard 现状提取 |
| elevation | 0（`CardDefaults.cardElevation(defaultElevation = 0.dp)`） | 无投影，扁平语言 |
| shape | `AppShapes.small`（紧凑任务卡）/ `AppShapes.medium`（面板/分组卡） | 按卡片粒度选择 |

```kotlin
Card(
    colors = AppCardStyles.tonal(),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    shape = AppShapes.medium,
) { ... }
```

elevated（强调卡，surfaceContainerLow + elevation）/ flat（扁平卡，surface 直出）两档为规划中的后续分层，当前仅 tonal 一档落地。已使用 `AppCardStyles.tonal()` token 配方的范围：TaskCard、飞书桥接页各卡（M6/M7 重写后经 `:ui` 的 `SectionCard`/`TonalCard` 统一走 tonal 配方，零 `ElevatedCard`、零裸 dp；2026-09-10 起 `SectionCard` 默认 `tonal = true`——分组卡本体渲染 tonal 容器，「裸分组冒充卡片」的口径差就此消解）。输出面板（`Surface` + `surfaceVariant.copy(AppAlpha.bgSubtleAlt)`）同属 tonal 扁平视觉语言（surfaceVariant 系背景 + 0 elevation），但为就地取值、未走 token 配方，属后续收敛项而非「已对齐」。（原「欢迎页提示卡」同类项已随 PageDefault 退役消失。）

> 🔴 容器组件分层（2026-10-02 重设计批 3/11 定稿）：**新卡片一律走 `:ui` `TonalCard`**
> （配方包装化——见 [ui-style-guide.md](../ui-style-guide.md) 4.2b；带标题/折叠的分组卡走
> `SectionCard`）；本节 `AppCardStyles.tonal()` 是其配色 token 本体，直接消费仅限
> 特殊自定义卡。

---

## 动效设计 token（Motion）

时长与动画规格统一从 `MotionDurations` / `AppMotion` 引用，**`tween(` / `spring(` 调用只允许出现在 `Motion.kt`**（jvmTest `arch/UiArchitectureGuardTest` 源码扫描强制，白名单挂靠上方"有据保留"清单）。需要新档位时先在 token 文件中扩充定义再使用，禁止在调用点就地写动画规格。

### 取值定义

**时长三档 + 编排延迟（`MotionDurations`，240ms 为全应用封顶值）：**

| token | 值 | 典型用途 |
|-------|-----|---------|
| `fast` | 120ms | hover、图标切换等高频轻量反馈 |
| `standard` | 180ms | 常规状态过渡（面板展开、页面切换） |
| `slow` | 240ms | 大区域变化（**封顶值**） |
| `staggerDelay` | 30ms | 入场编排逐项延迟（如卡片逐张浮现——5.2 模板） |

**动画规格工厂（`AppMotion`）：**

| 规格 | 实现 | 用途 |
|------|------|------|
| `fast()` / `standard()` / `slow()` | `tween` 对应档位 | 与上表三档一一对应 |
| `softSpring()` | `spring(StiffnessMediumLow)` | 分组展开等尺寸变化（不引入时长概念） |

### 设计原则

- **240ms 封顶**：本应用是桌面工具，动效只服务于状态认知（"变化正在发生/已完成"），不做等待表演；超过封顶的过渡会随键鼠高频操作累积成拖沓感。
- **入场编排**：`staggerDelay`（30ms）逐项小步递进，整体编排仍在封顶量级内完成。
- **零动画铁律**：动画只做状态过渡；**流式输出/日志行零动画**——不施加 `animateContentSize` / `animateScrollToItem` / 逐行动画，列表高频重组场景下会引发重组风暴与掉帧。

---

## 透明度设计 token（AppAlpha）

`Color.copy(alpha = ...)` 的透明度取值统一从 `AppAlpha` 引用（命名收口第一轮：token 值 = 现状精确值，现存 21 处调用取值不变）。**近值不归并**：语义相近但数值不同的档位（0.3 / 0.35 / 0.4）保持独立 token——透明度观感漂移不可控（同一 alpha 在不同底色/色相下视觉差异非线性），归并即引入不可预见的视觉变化。

### 取值定义

| token | 值 | 典型用途 |
|-------|-----|---------|
| `transparent` | 0.0 | 全透明：hover 操作区隐形态（飞书桥接配置行「恢复默认」未 hover 时；2026-09-27 IA 批①） |
| `bgSubtle` | 0.3 | 弱背景：拖放区常态/拖拽背景、分组对话框非当前项 |
| `bgSegTrack` | 0.08 | 分段控件轨道底（2026-09-24 批 5-a）：预览稿 `.seg{background:color-mix(txt 8%)}` 直译——SegmentedControl 容器胶囊底 |
| `bgSubtleAlt` | 0.35 | 次弱背景：自定义工具编辑器参数区、输出面板底色 |
| `contentDisabled` | 0.38 | 禁用态内容 tint（对齐 M3 disabled content alpha） |
| `borderFaint` | 0.4 | 弱描边：主题卡边框；兼用于进度条轨道、父节点卡背景 |
| `bgTask` | 0.5 | 任务卡背景 |
| `contentFaint` | 0.6 | 弱内容：次级说明文字（拖放区描述、输出计数、参数计数） |
| `contentSoft` | 0.7 | 软内容：辅助文字（提示卡描述、徽标、时间戳） |
| `scrimSoft` | 0.5 | Compact 档侧栏浮层遮罩（2026-09-21 P0 新增；语义独立于 bgTask 任务卡底色——复用会让「调任务卡」误伤浮层，同值多场景先例） |

同值多场景（如 `borderFaint` 兼用于边框/轨道/卡背景）第一轮共用同一 token，语义细分留待后续按需拆分。新页面/新组件强制引用 token，禁止就地新写 `alpha = 0.x` 字面量（`UiArchitectureGuardTest` 源码扫描强制）。

---

## 玻璃材质 token（R2 材质诚实化 · 2026-09-25 终态）

### 设计基线与诚实化裁定

历史视觉基线为已作废的旧稿 v10（九轮真机反馈收敛；2026-10-03 归档），现行设计唯一真源 = 本节 token 契约。本节裁定记录的是 R2 材质诚实化（2026-09-25）以来的终态语义：**blur 的唯一设计语义 = 「此层之下有需保留觉知的变化内容」**——据此裁定：

1. **浮层（palette/F1 参考卡/toast）恒真玻璃**——GLM 实拍验证 blur 语义真实兑现（透过面板可见下层内容残影晕开）；
2. **chrome（dock/tab 条/状态栏/inspector）玻璃退役恒实底**——背后是静态 aurora 且内容被 contentPadding 刻意挡在玻璃带外，blur 无从表达；`glassTintChrome*/glassTintPanel*` 常量删除，`appGlassChrome/appGlassPanel/appGlassChromeStatusbar` 切 `opaqueSlot*` 实底升格主路径（**形态语言全保留**：发丝 rim/定缘/胶囊轮廓）；
3. **`glassEnabled` 设置项退役**——玻璃只存浮层且恒真，「全局玻璃」开关失去语义；AppViewModel 只余 `auroraEnabled` 单开关；
4. 玻璃根 `LocalToolboxGlassRoot` 从 PageHome 上提 App 级（App.kt Success 分支 provides）——F1 参考卡转真玻璃。

三条红线（预览稿 v4-v9 迭代的教训沉淀）：

1. **禁表面渐变装饰带**——高光唯一语言 = 受光缘 1dp 发丝内边线（`glassStrokeHairline` + rim alpha）。
2. **chrome 悬浮件零投影**——投影语义：只有真浮层带**自绘软影**（`:ui` `widget/glass/FloatShadow.kt`——CMP `shadowElevation` 在 Linux GPU 管线渲染为无模糊硬灰环，真机实测定罪弃用）；常驻 chrome 的分离语言 = 发丝边 + 实底档。
3. **明暗分治**——暗色可透、亮色近实底；材质参数只随明暗走，不随主题包走。

### 玻璃两档 + chrome 实底三档（R2 终态）

| 档 | blur | tint 暗/亮 | 归属 |
|----|------|-----------|------|
| glassSheet | `glassBlurSheet` 30dp | `glassTintSheetDark` 0.72 / `glassTintSheetLight` 0.92 | 命令面板、F1 参考卡、导览卡（真浮层，唯一允许投影档） |
| glassThin | `glassBlurThin` 18dp | `glassTintThinDark` 0.85 / `glassTintThinLight` 0.94 | toast（真浮层） |
| opaqueSlotChrome | —（实底） | — | 标题栏（`AppTitleBar`）、dock 竖轨外全部 chrome、tab 条、**状态栏**（`appGlassChromeStatusbar`——2026-10-03 对稿改稿面口径：状态栏与标题栏同带一色 `--chrome-tint`，旧 opaqueSlotThin 容器灰档废弃）、MCP 日志台等 |
| opaqueSlotPanel | —（实底） | — | inspector 承板（`appGlassPanel`）、**dock 竖轨**（`appGlassRail`——2026-10-03 新增：贴边直角 + 左缘受光，对稿 mock-base .rail） |

### 尺寸/圆角/透明度新增档

- `Dimens`：`glassBlurChrome/Sheet/Thin`（24/30/18dp）、`glassStrokeHairline`（1dp）、`glassFloatInset`（8dp——浮动 chrome 距窗缘）、`glassShadowElevation`（12dp——自绘软影光晕缩放基准，2026-09-25 起不再喂 elevation）、`glassSheetRadius`（20dp——sheet 圆角值源，AppShapes.glassSheet 与软影绘制同源）、`statusSlotHeight`（42dp——状态栏悬浮带高 = 状态栏胶囊 26 + 底隙 8 + 顶隙 8，dock 底部让位 = `glassFloatInset + statusSlotHeight` 50dp，预览稿 `.dock{bottom:calc(8px+42px)}` 同构；2026-09-24 批 3.5-c）；aurora 遮罩八 token（2026-09-24 批 3.5-b，预览稿四边 linear-gradient mask 直译，begin=透明起点/end=不透明终点）：`auroraMaskTop 52→76dp` / `auroraMaskBottom 36→60dp` / `auroraMaskLeft 72→96dp` / `auroraMaskRight 8→32dp`；内容视口六 token（2026-09-24 批 3.6 轻量版全屏铺，预览稿 L237 `.content` padding + L186 `.content-fade` 直译）：`contentViewportPaddingTop/Start/End/Bottom`（50/80/8/50dp——Top=8+tab 条 34+8、Start=8+56 dock 轨+16 呼吸隙、End=8 窗缘语言、Bottom=statusSlotHeight 42+8）+ `contentViewportPaddingTopNoTabs`（GLB-05 · 重设计批 4：**8dp**——openTabs 空集 tab 条 Auto-hide 时顶让位收敛窗缘隙；语义独立于 `glassFloatInset` 同值登记，日后单独调值不牵连。消费点 PageHome `LocalScaffoldGlassInsets` top 分支响应式二选一——**顶让位非恒定 50dp，随 tab 条在场/空集在 50/8dp 间切换**）+ `contentFadeBand`（34dp 渐隐边带，`appContentFade` 垂直双带消费）。**批 3.7-a 语义勘误**：四 token 经 `LocalScaffoldGlassInsets`（Glass.kt）下发 PageScaffold——scrollable 页 = 滚动 contentPadding 语义（内容滚出视口穿过玻璃带被 blur 折射，CSS padding-in-scroll-container 等价物），视口本体全屏铺、`appContentFade` mask 带锚窗口玻璃带边缘（0..34/H-34..H）。aurora 光斑批 3.7-b 椭圆化：主 46%W×40%H at 20%,26% / 副 52%W×46%H at 84%,76%（drawOval 双轴——原 drawCircle minDim 直译错误，宽窗横径 -44%）。

**浮层软影（2026-09-25 自绘批）**：CMP `shadowElevation` 在 Linux GPU 管线渲染为无模糊硬灰环（命令面板四周 ~10px 灰边 + 底部 16px 灰带，真机实测定罪）——浮层投影弃 elevation，改自绘细粒度多层圆角矩形（`:ui` `widget/glass/FloatShadow.kt` 的 `appFloatShadow`，12 层连续衰减近似预览稿 `--shadow-float`「0 18px 50px」；初版三层方案被盲像素审计定罪为「台阶平环」——8/9/11px 恒定平带、环间跳变 20-26/px——改 12 层后层距 ~2px、无 ≥6px 恒定带）：`AppAlpha.floatShadowDark/Light`（0.52/0.16）× `EasyToolboxColors.floatShadow`（浅 #28375A / 深 #000000），光晕基准 `Dimens.glassShadowElevation`、圆角基准 `Dimens.glassSheetRadius`。消费点：CommandPalette / OnboardingCard（sheet 浮层）；F1 参考卡（浮层统一批：Dialog→非模态 Popup、撤 M3 59% 平台模态调光、opaqueSlotSheet 厚板 + 软影——App 级浮层读不到玻璃根（根按「App 级不挂」纪律在 PageHome 内创建），材质取 palette 浮层既有兜底档，观感同源）；StatusHost 哑光兜底 toast 撤投影（GPU 硬环同款根因，inverseSurface 深底对比已足）。
- `AppShapes`：`glassPanel`(16dp 玻璃大件) / `glassSheet`(20dp 浮层) / `glassBar`(12dp chrome 长条) / `capsule`(percent 全圆——2026-09-24 批 3.5-c 口径改写 + **GLB-03 · 重设计批 2 拍板消费面收窄**：胶囊仅用于 toast 等横条小件、选中 pill/能力 chip 与投屏手势模式徽标（语义扩张见上方「有据保留」登记，2026-10-15）；**chrome 长条（状态栏/tab 条）走 `glassBar` 12dp**——状态栏实际消费 `appGlassChromeStatusbar`（内部 glassBar 语义，`AppStatusBar.kt` 注释自证）；**超长容器 >720dp 禁全圆**「香肠感」边界保留)。
- `AppAlpha` **2026-10-04 收口登记批**（完备性批评家 F-6——本轮新档补账）：`consoleFieldBg`(0.08——控制台语境容器底，shell-tools .cmdin/.search color-mix 8% 逐字)/`consoleFieldBorder`(0.14——同源描边档)/`switchThumbShadow`(0.20——自绘开关滑块微影，稿 .switch::after)/`ghostBgLight`(0.38)/`ghostBgDark`(0.05)（DS-08 ghost 卡双档）/`hoverWash`(0.04)/`selectedWash`(0.08)/`hotWash`(0.10)（DS-03 交互微洗底三档，色源恒 primary——hotWash 生产消费点 2026-10-04 起 8/8 全传导）/`contentFaintTextDark`(0.89)/`contentFaintTextLight`(0.72)（弱内容文字档——稿 --on-surface-faint 实色反解，双面 5.9:1+ 达 AA）。- `AppAlpha`（🔴 2026-10-03 对稿重锚——2026-09-27 新稿 tokens.css §2.7 逐字）：四档 tint 明暗分治（上表）+ **rim 四档** `glassRimDark/Light`(0.22/0.50——chrome/sheet/thin 档) + `glassRimPanelDark/Light`(0.20/0.45——panel 档；旧 0.10/0.22 是 v10 旧稿「×.45」换算产物已废) + `glassBorderDark/Light`(0.10/0.08——色源 onSurface 系，对稿 --glass-brd 与 border-hairline 同值族) + `auroraPrimaryDark/Light`(0.10/0.07) + `auroraTertiaryDark/Light`(0.08/0.05)。🔴 已删除：`glassStainDark/Light`（lerp 染色配方废弃——稿面玻璃四档 rgb=中性面档位直取）、`titleBarBg/titleBarDot`（标题栏改 chrome 档实底 + 红绿灯产品三色）。另 opaque 档 ×4（`opaqueSlotChrome/Panel/Sheet/Thin` = 中性面档位直取 alpha=1，非 lerp 派生）。

### 窗口形态终态（批 6 无边框，2026-09-25 维护者裁定：观感优先）

批 6 `undecorated + transparent` 透明圆角窗 + 自绘标题栏（`host/AppTitleBar`：三控制圆点/拖拽/双击最大化）+ 自绘缩放边条（`host/UndecoratedResizeOverlay`）。最终形态经**维护者四轮真机手测 A/B**（commits b666237→ec3ff03→96214a1→本 commit 的完整链在 git 历史）：

1. **无边框窗缩放 = 应用驱动 setSize**（CMP 内建 resizer 同机制）：逐事件 setLocation+setSize 两次异步调用，X11 异步 ConfigureRequest + 内容重渲染滞后 = 真机「剧烈抖动」+「先移动后扩大」错位；自绘冻结快照算法（MouseInfo + DOWN 时刻窗口位快照差分）+ 松手即停三重 break（`!isPrimaryPressed`/`changedToUp`/指针消失）= 几何完美单调 1:1 零震荡。
2. **EWMH `_NET_WM_MOVERESIZE` 让 WM 原生接管**：本机 WM（麒麟系）**连最简原生 X 窗口都完全无视该消息**——死路（Python/FFM 双通道实证）。
3. **系统标题栏形态**：WM 驱动几何平滑，但 **XAWT 双窗结构（Frame+内容子窗）** 使左/上缘缩放时内容先跟原点平移再重排变宽（所有 Java 桌面应用在 Linux 的共有税，IntelliJ 同款；Compositor stretch 为 XWayland 共有税）——维护者实测「也抖」。
4. **两种形态都抖，维护者裁定无边框观感优先**（2026-09-25）。「先移后宽」残留 = XAWT 结构税，已知候选缓解 = 内容子窗 gravity 改 Static（FFM hack，效果部分且脆）——按「不为效果引入大量代码」原则暂不实施。

**同名 token 全部现役**：titleBarHeight(40)/trafficLightDot(12)/titleBarPaddingH(20)/resizerThickness；Window(resizable=false) + LocalAppWindowScope（host/AppWindowScope.kt，fail-fast）。🔴 AppAlpha.titleBarBg/titleBarDot 已随 2026-10-03 对稿批删除（标题栏改 chrome 档实底 + 红绿灯产品三色——见上 AppAlpha 节）。

### 页面基底 tone 校正（双轨：97.9 锚 + 重采样窗口；旧稿 95.5 系已废勿回填）

`AppThemeSpec.colorSet` 浅色模式页面基底（background / surface / surfaceContainerLowest——后两者分别是 ToolboxSurfaceColors 页面基底槽与玻璃 chrome 取源槽）走**三轨（底色主权直采 > 97.9 锚 > 重采样窗口）**：

- **底色主权轨（最高优先）**：套级 `pageBaseLight` 在场时三槽直采套参数，双轨整体跳过（`lightPageTone` 管线层不消费）——风格化主题的页面底是身份漆，见「底色主权与套级直配」节。
- **97.9 锚轨**：`Hct.withTone(97.9)` 校正（色相/色度保持只压亮度，materialkolor `com.materialkolor.hct.Hct`）。锚 = 重设计稿 tokens.css `--surface`（L*97.9）——页面基底**亮于**卡底（稿面 `--surface-raised` L*95.0，「卡沉于底」，M3 派生默认 Low tone96 恰好同向，无需反转操作）。中性豁免套（`neutralSkeletonExempt`）与未降 tone 的染色套逐位走本轨。
- **重采样轨**：非豁免染色套 `SkeletonStain.lightPageTone` ∈ [94.5, 97.9]（下沿 = 全目录最深彩纸需求套——洒红节粉雾纸 95.0 直采——留量化回读容差钉定；再降须重裁窗口）时，bg/surface/surfaceContainerLowest 三槽改为 `Hct.from(染色 hue, 染色 chroma, lightPageTone)` **直采**——97.9 处 HCT 亮端色域最窄（暖红橙被钳到 c1-2.3），降 tone 换色域宽度是骨架解钳的唯一机制。🔴 必须 Hct.from 直采（经 HctSolver 解算），不得 `TonalPalette.tone(pageTone)`（palette 插值在极端 tone 自带色度衰减，见「亮态色域天平」）；不得用 withTone 到更低 tone（只压亮度保色度，解不了钳）。降 tone 后 bg 暗于派生卡阶梯的套须随动覆写亮卡阶梯（「卡沉于底」降序断言，洒红节粉雾浓淡阶先例）。

卡容器阶梯（surfaceContainerLow/High/Highest）与深色模式不压 tone 不重采样（深色模式另有 pageBaseDark 直采，见「底色主权与套级直配」节）。**色相偏紫 ~20° 属 seed 本征，登记有据差异**（换 seed 等于换主题，不动）。🔴 防回退：勿回填旧稿 95.5 系取值——95.5 暗于卡底会反转「卡沉于底」明度序，由 tone 阶梯严格降序断言机械执行；取值单源 = `AppThemeSpec.kt` 的 `LIGHT_PAGE_TONE`、各套 `lightPageTone` 与各套 `pageBaseLight`。

### 染色体系（三层全派生，零硬编码新色）

🔴 2026-10-03 对稿重写：玻璃四档 = `scheme.surfaceContainer*` **预混直取**（稿面 tokens.css §2.7 玻璃 rgba 的 rgb 部分就是中性面档位——chrome=Lowest/panel=Low/sheet=High/thin=Highest；主题联动经 surfaceContainer 派生自动保留，pageBase 套的井槽分家使玻璃带落深井域）。旧 `lerp(slot, primary, glassStain)` 染色配方与 glassStain token 已删除（DS 对拍定罪：紫染 + ΔL≈10 档）。🔴 恒紫 `aiAccent` / `consoleBg`（语义面深底——消费面单源解析 `resolvedConsoleBg`：套级直配 > 暗态骨架派生 > 静态基值，非玻璃染色）/ 日志四色**不参与任何玻璃与 aurora 染色**（紫只住哑光内容块）。

### 环境背景 aurora（玻璃的折射底料）

窗口根、全部内容之下插 `AuroraBackdrop`（双 radialGradient，恒静态零动画）。🔴 光斑只属于内容区：中心在内容区四分位（约 20%/26% 与 84%/76%）、半径 ≤50%，chrome 带经遮罩裁剪零光斑——光斑落进玻璃条会显"廉价贴纸"（真机反馈实锤）。

### 作用域与消费纪律

- 🔴 `dev.chrisbanes.haze` import 唯一落点 = `:ui` `widget/glass/`（GlassMaterials.kt 单文件，`UiArchitectureGuardTest` GLASS_SCOPE 规则钉死）；消费方一律走包装修饰符 `appGlassChrome/Panel/Sheet/Thin`。
- 玻璃只给 chrome 与浮层；**内容即哑光**（TonalCard/表单/列表行/控制台/图表永不玻璃——控制台是内容，玻璃上控制台 = 装饰 blur 违 HIG 红线）。
- 玻璃不叠玻璃：玻璃区内交互态一律实色叠层（hoverOverlay/selectedContainer）。

### 玻璃动效

`AppMotion.glassSpring()`（dampingRatio 0.75 + StiffnessMedium）：dock 图标按压 scale 0.96 回弹专属，不挂列表行/日志行。浮层入场走既有 `AppMotion.standard()` 工厂（palette fadeIn+slideInVertically 180ms / 退场 fast 120ms）。切页零动画、流式零动画铁律不变。
