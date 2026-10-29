package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.hct.Hct

/**
 * 浅色页面基底 tone 豁免锚 = 设计 token `--surface`（L*97.9——HCT tone 反解 ≈97.9）。
 * 中性豁免套（[AppThemeSpec.neutralSkeletonExempt]）逐位钉在本锚上；非豁免染色套走
 * [SkeletonStain.lightPageTone] 重采样窗口（验收下沿 94.5，由全目录最深彩纸需求套钉定）——97.9 处 HCT
 * 亮端色域最窄，暖红橙骨架被钳到 c1-2.3，降 tone 换更宽色域是唯一解钳机制
 * （[withTone] 只压亮度保色度，换 tone 解不了钳）。
 * 防回退：勿取 bg tone 95.5 系——95.5 暗于卡底（raised 95.0），页面/卡
 * 明度序「卡沉于底」随之反转，由 tone 阶梯严格降序断言机械执行。
 */
private const val LIGHT_PAGE_TONE = 97.9

/** HCT tone 重映射（色相/色度保持，只压亮度；`Hct` 来自 material-color-utilities）。 */
private fun Color.withTone(tone: Double): Color =
    Color(Hct.fromInt(this.toArgb()).withTone(tone).toInt())

/**
 * 骨架染色参数：显式指定中性调色板（neutral/neutralVariant）的 HCT 色相与色度，深浅两态各自独立。
 *
 * 契约：
 * - 覆写语义 = 整支调色板替换（materialkolor 覆写换算 `X = X?.toTonalPalette() ?: defaults.X`），
 *   background/卡阶梯/描边/玻璃全消费链自动跟随，onSurface 等文字槽 tone 表不变、对比度结构性保持；
 * - 覆写色自身 tone 无关紧要（TonalPalette.fromInt 只取 hue+chroma），统一取 tone 50 求色；
 * - null（[AppThemeSpec.skeletonStain] 缺省）= 跟随 style 档现行中性策略
 *   （Fidelity=种子色度÷8 / TonalSpot=6 / Vibrant=10 / Neutral=2）；
 * - 必须双态独立指定：跨色相双态骨架（如晴空麦浪亮态黄麦纸/暗态夜蓝）同相参数表达不了；
 * - [lightPageTone] 缺省 97.9 = 浅色基底走 97.9 锚校正链（与无染色套同管线，逐位不变）；
 *   显式降到窗口内（< 97.9）= 亮态 bg/surface/surfaceContainerLowest 三槽改为按目标 tone
 *   从染色参数直采重采样（[AppThemeSpec.colorSet]），97.9 处被钳掉的色度得以落地；
 * - 染色三原则与色度分档表见 docs/core/theme.md「表面染色」节。
 */
@Immutable
data class SkeletonStain(
    val darkHue: Double,
    val darkChroma: Double,
    val darkVariantChroma: Double,
    val lightHue: Double,
    val lightChroma: Double,
    val lightVariantChroma: Double,
    /** 浅色页面基底 tone：97.9 = 豁免锚管线；< 97.9 = 重采样目标（有效域 [94.5, 97.9]，下沿 = 最深彩纸需求套钉定 + 量化回读容差，窗口断言钉死） */
    val lightPageTone: Double = 97.9,
)

/**
 * 套级表面图案型（密封枚举，无开放端点——扩员须先过注册面重裁）：套级只选型不携带色值，
 * 双色由消费位从该套既有槽色取，周期/对比密度在绘制位固定。采样与绘制单源在 ThemeSpecimen.kt。
 */
enum class ThemeSurfacePattern {
    /** 市松格纹（两色棋盘小格；双色 = 该套卡阶首末两档） */
    Checkerboard,

    /** 警示斜纹（45° 等宽条带；双色 = 该套 warning 域 + 近黑档） */
    HazardStripe,
}

/**
 * 套级身份直配覆写表（昼/夜形态各一张，与 [SkeletonStain] 平行的套级可配面）：
 * 身份色（魂色）不经 M3 派生管线、原值直落——M3 派生会把一切身份色拉回平均值
 * （暗 primary 出薰衣草、饱和橙洗成棕），派生只许做中性阶梯、不许碰魂色。
 * 机制规格见 docs/core/theme.md「底色主权与套级直配」。
 *
 * 契约：
 * - 全字段可空，null = 该槽不覆写、走 M3 派生；无表套 = 派生结果逐位不变；
 * - M3 槽在 [AppThemeSpec.colorSet] 派生后统一 copy 生效——守护测试与 G5 救援判据
 *   经 colorSet 直读即见直配值；bg/surface 两槽不入本表（底色单源 =
 *   [AppThemeSpec.pageBaseLight]/[AppThemeSpec.pageBaseDark]，防双源歧义）；
 * - surfaceContainerLowest 可经本表再直配：底色参数先行三槽铺底后，井槽
 *   （surfaceContainerLowest）可与页面底分家（昼战同值铺底、夜战落井槽）；
 * - ToolboxSurfaceColors 槽（selectedContainer/onSurfaceFaint/onCard 三档）在
 *   [toolboxSurfaceColors] 派生位消费；EasyToolboxColors 槽在 AppTheme 装配位消费，
 *   consoleBg 单源解析走 [resolvedConsoleBg]（直配 > 暗态染色派生 > 静态基值）；
 * - 覆写表是套定义（代码常量），不入持久化（color_scheme 仍存 id）。
 */
@Immutable
data class ThemeSlotOverrides(
    val surfaceContainerLowest: Color? = null,
    val surfaceContainerLow: Color? = null,
    val surfaceContainer: Color? = null,
    val surfaceContainerHigh: Color? = null,
    val surfaceContainerHighest: Color? = null,
    val surfaceDim: Color? = null,
    val surfaceBright: Color? = null,
    val surfaceTint: Color? = null,
    val surfaceVariant: Color? = null,
    val primary: Color? = null,
    val onPrimary: Color? = null,
    val primaryContainer: Color? = null,
    val onPrimaryContainer: Color? = null,
    val secondary: Color? = null,
    val onSecondary: Color? = null,
    val secondaryContainer: Color? = null,
    val onSecondaryContainer: Color? = null,
    val tertiary: Color? = null,
    val onTertiary: Color? = null,
    val tertiaryContainer: Color? = null,
    val onTertiaryContainer: Color? = null,
    val onSurface: Color? = null,
    val onSurfaceVariant: Color? = null,
    val onBackground: Color? = null,
    val outline: Color? = null,
    val outlineVariant: Color? = null,
    val inverseSurface: Color? = null,
    val inverseOnSurface: Color? = null,
    val inversePrimary: Color? = null,
    // ── ToolboxSurfaceColors 槽（[toolboxSurfaceColors] 派生位消费）──
    val selectedContainer: Color? = null,
    val onSurfaceFaint: Color? = null,
    /**
     * 结构带（侧栏轨/页签栏两条常驻实底带——面积构成第二声部位的块级承载）：
     * null = 带底回落原 chrome/panel 材质槽（零漂移）；非 null = 两带
     * 整面换装。结构带不上文字承载面（消费位封闭集 = 侧栏轨+页签栏，带上不放
     * 图形件/正文——带面对昼底不背书图形线，带上前景走 [onStructureBand] 配套字链）。
     * 供给与覆写表注册制绑定（AppThemeSpecTest 注册面，非全目录开放）。
     */
    val structureBand: Color? = null,
    /** 结构带配套字（带面图标/页签文字/关闭钮统一配套字档；null = 回落 onCardMid） */
    val onStructureBand: Color? = null,
    /** 卡域文字三档（昼/夜底域与卡域文字分域——卡内消费点取色链见 docs/core/theme.md）：null = 回落 onSurface 族 */
    val onCardStrong: Color? = null,
    val onCardMid: Color? = null,
    val onCardFaint: Color? = null,
    // ── 样张图案槽（消费侧暴露——样张 base 块专用，不入 ToolboxSurfaceColors）──
    /**
     * 表面图案选型（样张 base 块纹理采样；null = 无图案，底块纯色逐位不变）。契约：
     * - 申请制（AppThemeSpecTest 注册面快照，非注册套传参即红——同 [structureBand] 先例）；
     * - 图案不上文字承载面：消费位封闭集 = 设置页样张 base 块（composeApp 源码锚守护，
     *   主题卡其余区域/应用页面/其他模块消费即红）；
     * - 密度上限：格纹/斜纹的周期与对比在绘制位固定，套级只选型；图案槽不携带色值，
     *   双色由采样单源（[AppThemeSpec.specimenPatternSample]）从该套既有槽色取。
     */
    val surfacePattern: ThemeSurfacePattern? = null,
    // ── EasyToolboxColors 槽（AppTheme 装配位消费；consoleBg 走 [resolvedConsoleBg] 单源）──
    val consoleBg: Color? = null,
    val aiAccent: Color? = null,
    val aiAccentContainer: Color? = null,
    val floatShadow: Color? = null,
    val chartPalette: List<Color>? = null,
    val platingGreen: Color? = null,
)

sealed class AppThemeSpec() {

    /** 主题显示名（设置页色卡文案） */
    abstract val displayName: String

    /** 主题种子色（设置页色卡预览用） */
    abstract val seed: Color

    /** 持久化标识；Custom 统一存 "Custom"，种子色另存 ARGB */
    abstract val id: String

    /** 设置页分组（预设库按题材分组；未覆写 = 经典组） */
    open val group: PresetGroup get() = PresetGroup.Classic

    /** 第二强调种子（null = 不设；双种子套把强调声部从单种子派生中显式化） */
    open val secondarySeed: Color? get() = null

    /** 第三强调种子（null = 回落 secondarySeed——双种子套的 tertiary 家族与强调同族，避免 Fidelity 互补副作用的随机窑变） */
    open val tertiarySeed: Color? get() = null

    /** 骨架染色（null = 跟随 style 档现行中性策略；参数语义见 [SkeletonStain]） */
    open val skeletonStain: SkeletonStain? get() = null

    /**
     * 套级底色主权参数（昼形态）：非 null 时 colorSet 亮分支把 background/surface/
     * surfaceContainerLowest 三槽直采为本值，跳过 97.9 锚轨与重采样轨——「浅模式 = 浅纸」
     * 的模式通则不适用于风格化主题，页面底是套的身份漆不是派生物。
     * 优先级：本参数存在时 [SkeletonStain.lightPageTone] 管线层不消费（底色单源歧义防护），
     * 字段与字段值保留（守护测试侧 resampled 判定路径仍依赖它）。
     */
    open val pageBaseLight: Color? get() = null

    /**
     * 套级底色主权参数（夜形态）：非 null 时暗分支把 background/surface/
     * surfaceContainerLowest 三槽 copy 为本值（井槽可再经 [ThemeSlotOverrides.surfaceContainerLowest]
     * 与页面底分家）；null = 暗态底走 M3 spec tone 表（现行行为）。
     */
    open val pageBaseDark: Color? get() = null

    /** 套级身份直配覆写表（昼形态；null = 无覆写，派生逐位不变） */
    open val lightOverrides: ThemeSlotOverrides? get() = null

    /** 套级身份直配覆写表（夜形态；null = 无覆写，派生逐位不变） */
    open val darkOverrides: ThemeSlotOverrides? get() = null

    /** 按深浅模式取覆写表（AppTheme 装配与守护测试共用的单源入口） */
    fun slotOverrides(dark: Boolean): ThemeSlotOverrides? = if (dark) darkOverrides else lightOverrides

    /**
     * 中性豁免套标记（克制即身份的对照系，机器可读单源）：豁免套亮态逐位走 97.9 锚管线、
     * 暗态控制台回落静态基值，不进重采样窗口/分带色度地板/bg ΔE 地板断言。
     * 清单与理由：Default（零漂移锚——派生快照锚依赖现行管线）/ 朱砂宣纸（近零染色即题材）/
     * 薄荷青（真中性灰规格）/ 蓝金三色（近无相白甲 + 三点式强调身份）——非豁免套拉开后其可辨识性反升。
     * 消费点：[AppTheme] 暗态 consoleBg 装配、ThemeDerivedContrastGuardTest、
     * ThemeDistinguishabilityGuardTest。
     */
    open val neutralSkeletonExempt: Boolean get() = false

    /**
     * 调色板风格档位契约（AppThemeSpecTest 钉死 + docs/core/theme.md「配色裁定」）：
     * - 保色相档 = Fidelity/Vibrant（primary 色相 ≈ seed 色相，hue-keep 守护全目录断言）；
     * - TonalSpot（默认平静档）：预设库现无套选用，Custom 色轮恒走本档；
     * - Expressive 全目录禁用：其 primary 调色板 = 种子相 +240°/chroma 40，旋相毁身份色
     *   （红相套会翻蓝紫），hue-keep 守护对其必红。
     */
    open val style: PaletteStyle get() = PaletteStyle.TonalSpot

    /** 动态生成指定深浅模式的 ColorScheme */
    fun colorSet(dark: Boolean): ColorScheme {
        val stain = skeletonStain
        // 骨架覆写色：tone 50 任意（调色板只取 hue+chroma，见 [SkeletonStain] 契约）
        val neutralStain = stain?.let {
            Color(Hct.from(if (dark) it.darkHue else it.lightHue, if (dark) it.darkChroma else it.lightChroma, 50.0).toInt())
        }
        val variantStain = stain?.let {
            Color(Hct.from(if (dark) it.darkHue else it.lightHue, if (dark) it.darkVariantChroma else it.lightVariantChroma, 50.0).toInt())
        }
        // 覆写换算（materialkolor ktx/DynamicScheme）：X 调色板 = 覆写色?.toTonalPalette()
        // ?: defaults.XPalette——全 null（Default/Custom）= 完整回退 defaults，
        // 派生与单种子管线逐位一致（零漂移锚）；四覆写参同一调用点并存互不冲突。
        val scheme = dynamicColorScheme(
            seedColor = seed,
            isDark = dark,
            style = style,
            secondary = secondarySeed,
            tertiary = tertiarySeed ?: secondarySeed,
            neutral = neutralStain,
            neutralVariant = variantStain,
        )
        // 底色主权（套级底色参数优先）：pageBase 非 null → bg/surface/surfaceContainerLowest
        // 三槽直采套参数，跳过 97.9 锚轨与重采样轨（lightPageTone 管线层不消费）；随后
        // 覆写表逐槽 copy（M3 身份槽直配位，井槽可再与页面底分家）。
        if (dark) {
            val base = pageBaseDark
            val withBase = if (base != null) {
                scheme.copy(
                    background = base,
                    surface = base,
                    surfaceContainerLowest = base,
                )
            } else {
                scheme
            }
            return applySlotOverrides(withBase, darkOverrides)
        }
        val pageBase = pageBaseLight
        if (pageBase != null) {
            return applySlotOverrides(
                scheme.copy(
                    background = pageBase,
                    surface = pageBase,
                    surfaceContainerLowest = pageBase,
                ),
                lightOverrides,
            )
        }
        // 页面基底双轨（浅色，无底色参数套的现行管线）：豁免套/无染色套/未降 tone 的染色套 →
        // 97.9 锚校正链（M3 派生 + withTone，逐位不变）；非豁免染色套 lightPageTone < 97.9 →
        // bg/surface/surfaceContainerLowest 三槽从染色参数按目标 tone 直采重采样。
        // 重采样必须是 Hct.from 直采（经 HctSolver 色域解算），不是 TonalPalette.tone(pageTone)
        // ——palette 插值在极端 tone 自带色度衰减（(h355,c7) 经 palette 采样 tone 96.5 只落
        // c2.26，直采落 c4.40），换 withTone 也解不了钳（只压亮度保色度）。
        // 卡容器阶梯（Low/High/Highest）不重采样不压 tone——「卡沉于底」明度序由
        // tone 阶梯严格降序断言机械执行（95.5 系回填会反转它，禁）。
        val pageTone = stain?.lightPageTone ?: LIGHT_PAGE_TONE
        return if (pageTone >= LIGHT_PAGE_TONE) {
            applySlotOverrides(
                scheme.copy(
                    background = scheme.background.withTone(LIGHT_PAGE_TONE),
                    surface = scheme.surface.withTone(LIGHT_PAGE_TONE),
                    surfaceContainerLowest = scheme.surfaceContainerLowest.withTone(LIGHT_PAGE_TONE),
                ),
                lightOverrides,
            )
        } else {
            val base = Color(Hct.from(stain!!.lightHue, stain.lightChroma, pageTone).toInt())
            applySlotOverrides(
                scheme.copy(
                    background = base,
                    surface = base,
                    surfaceContainerLowest = base,
                ),
                lightOverrides,
            )
        }
    }

    /** 覆写表逐槽 copy（null 槽回落派生值）；在底色三槽铺底之后执行，井槽（Lowest）可再直配 */
    private fun applySlotOverrides(scheme: ColorScheme, overrides: ThemeSlotOverrides?): ColorScheme {
        if (overrides == null) return scheme
        return scheme.copy(
            surfaceContainerLowest = overrides.surfaceContainerLowest ?: scheme.surfaceContainerLowest,
            surfaceContainerLow = overrides.surfaceContainerLow ?: scheme.surfaceContainerLow,
            surfaceContainer = overrides.surfaceContainer ?: scheme.surfaceContainer,
            surfaceContainerHigh = overrides.surfaceContainerHigh ?: scheme.surfaceContainerHigh,
            surfaceContainerHighest = overrides.surfaceContainerHighest ?: scheme.surfaceContainerHighest,
            surfaceDim = overrides.surfaceDim ?: scheme.surfaceDim,
            surfaceBright = overrides.surfaceBright ?: scheme.surfaceBright,
            surfaceTint = overrides.surfaceTint ?: scheme.surfaceTint,
            surfaceVariant = overrides.surfaceVariant ?: scheme.surfaceVariant,
            primary = overrides.primary ?: scheme.primary,
            onPrimary = overrides.onPrimary ?: scheme.onPrimary,
            primaryContainer = overrides.primaryContainer ?: scheme.primaryContainer,
            onPrimaryContainer = overrides.onPrimaryContainer ?: scheme.onPrimaryContainer,
            secondary = overrides.secondary ?: scheme.secondary,
            onSecondary = overrides.onSecondary ?: scheme.onSecondary,
            secondaryContainer = overrides.secondaryContainer ?: scheme.secondaryContainer,
            onSecondaryContainer = overrides.onSecondaryContainer ?: scheme.onSecondaryContainer,
            tertiary = overrides.tertiary ?: scheme.tertiary,
            onTertiary = overrides.onTertiary ?: scheme.onTertiary,
            tertiaryContainer = overrides.tertiaryContainer ?: scheme.tertiaryContainer,
            onTertiaryContainer = overrides.onTertiaryContainer ?: scheme.onTertiaryContainer,
            onSurface = overrides.onSurface ?: scheme.onSurface,
            onSurfaceVariant = overrides.onSurfaceVariant ?: scheme.onSurfaceVariant,
            onBackground = overrides.onBackground ?: scheme.onBackground,
            outline = overrides.outline ?: scheme.outline,
            outlineVariant = overrides.outlineVariant ?: scheme.outlineVariant,
            inverseSurface = overrides.inverseSurface ?: scheme.inverseSurface,
            inverseOnSurface = overrides.inverseOnSurface ?: scheme.inverseOnSurface,
            inversePrimary = overrides.inversePrimary ?: scheme.inversePrimary,
        )
    }

    /** 设置页分组（组序 = presets 列表序，经 groupedPresets 投影给 UI） */
    enum class PresetGroup(val label: String) {
        Classic("经典"),
        Mecha("机甲"),
        Flag("撞色"),
        Culture("传统"),
        Terminal("终端"),
        Mood("情绪"),
    }

    // ── 预设主题（6 组 20 套）──
    // 注释只记派生事实与档位契约：实测浅/暗 primary 与骨架 bg hex（HCT hue/chroma）、
    // 染色参数（SkeletonStain 深浅 (hue, neutralC, variantC)，亮相重采样套加 @tone 标注）、
    // 双种子角色、对 aiAccent 恒紫的常规线/救援通道结论；组序与两两可区分性度量见
    // docs/core/theme.md「配色裁定/表面染色」。

    data object Default : AppThemeSpec() {
        override val displayName get() = "默认"
        // seed = ia-v2 靛紫 #4F55BA + Fidelity 柔和档（柔和靛紫，非电紫霓虹感）；
        // 电紫 #363CFF+Vibrant 收为未来候选预设——「配色预设可插拔」，勿写死单路径。
        // 零漂移锚：不设 skeletonStain/secondarySeed——派生值与单种子管线逐位一致
        //（快照锚：亮 primary #363CA1 / 亮 tertiary #6F3C00 / 暗 tertiary #FFB778；
        // 暗 bg #131319 h284/c6.6 / 亮 bg #FBF8FF c5.4 靛紫暗房纸/紫调纸面）。
        override val seed get() = Color(0xFF4F55BA)
        override val style get() = PaletteStyle.Fidelity
        // 中性豁免（零漂移锚的组成部分）：亮态恒 97.9 锚管线、暗态控制台回落基值
        override val neutralSkeletonExempt get() = true
        override val id get() = "Default"
    }

    /** 鎏金：融合案 D 原型色系——琥珀工具主色 + 深空暖黑底 */
    data object Aurum : AppThemeSpec() {
        override val displayName get() = "鎏金"
        // seed = 原型 --acc #e8b84b 原值（HCT hue 85.5/Chroma 44.9，真琥珀区）；
        // Vibrant 派生：暗 primary #FBBC00 / 亮 primary #795900（深金保 AA 文字档）。
        // 与 aiAccent 恒紫 ΔE76 暗 91.8 / 亮 126.5——全目录最高档，守护的安全垫。
        // 染色 (80,10,14)/(75,6,10)@96.5：暗 bg #1A1206（h78/c10.0 深空暖黑）/
        // 亮 bg #FFF3E8（c5.3，降 tone 重采样解 97.9 处暖黄钳制）。
        // 金箔明暗（卡阶梯，两态覆写）：受光面 → 背光沉金的暖金浓淡阶，色度随深度上行——
        // 亮 #F7E9D9(c7.4/t93.0)→#F2E3D1(c8.3/t91.0)→#EDDDCA(c8.9/t88.9)→#DFCFBA(c9.8/t83.9)、
        // 暗 #251C0D(c11.0/t10.9)→#2A2010(c11.6/t13.0)→#2E2413(c11.9/t14.9)→#392E1A(c13.1/t19.6)；
        // rim 受光缘口子属需新机制的 B 档，本批不做。onSurfaceVariant 对新 High 卡
        // 亮 7.01 / 暗 8.97（AA 抽验）；种子/声部不动。
        // 夜相主权（pageBaseDark 直采）：#1A1206 = 染色派生暗底同值（h77.6/c10.0/t6.0，
        // 暗态色度地板 10.0 同域）——「黑屋里的金子」的夜形态即主形态，夜相底以套参数
        // 钉死、脱离 M3 暗 tone 表，防暗骨架染色参数被动漂移时夜相随动；辅助文字 t78
        // 同相转译 onSurfaceFaint #CFBFAA（h79.2/c10.1/t78.1——固定实色 #8A90A3 对沉金
        // Highest 卡 4.18 破 4.5，转译档对底 10.31 / 对 Highest 卡 7.40）。
        override val seed get() = Color(0xFFE8B84B)
        override val style get() = PaletteStyle.Vibrant
        override val skeletonStain get() = SkeletonStain(
            darkHue = 80.0, darkChroma = 10.0, darkVariantChroma = 14.0,
            lightHue = 75.0, lightChroma = 6.0, lightVariantChroma = 10.0,
            lightPageTone = 96.5,
        )
        override val pageBaseDark get() = Color(0xFF1A1206)
        override val lightOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFFF7E9D9),
            surfaceContainer = Color(0xFFF2E3D1),
            surfaceContainerHigh = Color(0xFFEDDDCA),
            surfaceContainerHighest = Color(0xFFDFCFBA),
        )
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFF251C0D),
            surfaceContainer = Color(0xFF2A2010),
            surfaceContainerHigh = Color(0xFF2E2413),
            surfaceContainerHighest = Color(0xFF392E1A),
            onSurfaceFaint = Color(0xFFCFBFAA),
        )
        override val id get() = "Aurum"
    }

    // ── 机甲组 ──

    data object ArmoryGreen : AppThemeSpec() {
        override val displayName get() = "军械绿"
        // Vibrant：军绿与荧光感都顶格色度（TonalSpot 会派灰军装绿）。亮 primary #336B00（军绿锚）/
        // 暗 primary #71E000（荧光绿派生形态）；单种子机制无第二种子输入，
        // 信号粉 #F055A0 不进派生（登记）。
        // 染色 (110,18,26)/(110,20,26)：暗 bg #141400（h111/c18.2 军械绿黑）/
        // 亮 bg #FEFCCA（c20.6 军黄纸）。亮相顶格调参的机制依据：黄绿带是 97.9 处唯一宽色域带
        // （h110 palette 交付峰 ~21，param 22 起回落），纸面 B 通道压到 ~202——
        // 对冷/中性纸端（紫/蓝紫/靛 bg B≈250-255）bg+卡面+容器阶梯通道差全部 ≥53，
        // 是亮态 ≥49 可见阈之上唯一的「整窗换世界」级骨架；其余色带亮纸通道差物理上限
        // 30-45（97.9/96.5 白纸律 + 窄色域），跨族分离由 A 腿与 bg ΔE 地板承载。
        // 与矩阵暗排复合腿 S=5.1/A=29.4（军绿黑 vs CRT 绿黑+荧光绿主）；aiAccent ΔE76 123.1/84.9。
        // 信号粉 #F055A0 升 selectedContainer（两态——亮对军黄纸 3.07 图形级/暗对动力管
        // 绿黑 5.76，开关 on/导航选中/段选/卡选全链信号粉面；派生管线不收粉、选中面
        // 是粉的唯一非派生常驻位）。配套字链分态解耦：亮态 onSelectedContainer 回落
        // onSurface 军绿黑（对粉 5.30，chip 派生灰绿面原生白字 6.43 不动）；暗态
        // onSecondary = #17131F（粉面 5.70——暗 onSurface 亮字对粉 2.50 破 4.5，chip
        // 亮绿面 #ADCFAD 深字 11.6 同步成立）。
        // 夜相主权（pageBaseDark 同值直采换轨，hue-keep 替代腿准入）：军械绿黑 #141400
        // （h111.0/c18.17/t5.9）= 染色派生暗底同值——夜相底以套参数钉死、脱离 M3 暗 tone 表，
        // 防暗骨架染色参数被动漂移时夜相随动（Aurum/青花先例同构）。hue-keep 准入：主腿
        // 底相 vs 种子相（#5E8F3A h135.8）距 24.8° 出带；替代腿三闸全过（t5.9 < 20 ∧
        // c18.17 ≥ 10 ∧ 底相距在册染色暗档相 110.0 仅 1.0° < 10°）。对比度抽验（G13 域拆
        // 随 pageBase 激活）：onSurface 对夜底 14.43 / onSurfaceVariant 对 Highest 卡 7.25；
        // 辅助文字 t78 同相转译 onSurfaceFaint #C5C2A8（h109.5/c11.9/t78.0——固定实色
        // #8A90A3 对 Highest 卡 3.89 破 4.5，转译档对底 10.32 / 对 Highest 卡 6.87）。
        override val seed get() = Color(0xFF5E8F3A)
        override val style get() = PaletteStyle.Vibrant
        override val group get() = PresetGroup.Mecha
        override val skeletonStain get() = SkeletonStain(
            darkHue = 110.0, darkChroma = 18.0, darkVariantChroma = 26.0,
            lightHue = 110.0, lightChroma = 20.0, lightVariantChroma = 26.0,
        )
        override val pageBaseDark get() = Color(0xFF141400)
        override val lightOverrides get() = ThemeSlotOverrides(
            selectedContainer = Color(0xFFF055A0),
        )
        override val darkOverrides get() = ThemeSlotOverrides(
            onSecondary = Color(0xFF17131F),
            selectedContainer = Color(0xFFF055A0),
            onSurfaceFaint = Color(0xFFC5C2A8),
        )
        override val id get() = "armory-green"
    }

    data object ElectricViolet : AppThemeSpec() {
        override val displayName get() = "电光紫"
        // 电光紫 = 身份色直配套（底色主权 + 覆写表机制的首个启用预设，后续套按
        // 涂装批案接入）：两形态页面底都是紫——昼面本漆 #9946FA / 夜面本漆 #5940A6
        // （电光夜形变体），底色参数直采三槽跳过 97.9 锚轨与重采样轨；
        // 全槽直配值见两份覆写表。
        // 种子层只余 G5 救援判据输入：secondary 与 aiAccent 覆写同值（ΔE76=0）必走救援，
        // 由 tertiary 橙腿承载（ΔE76 ≈129 ∧ HCT 色相距 ≈111°，染色 max 色度 18 ≥10）。
        // 染色 hue 对齐新底（昼 268 = 昼底相 / 夜 255 = 夜底相）；底色参数存在时
        // lightPageTone 管线层不消费，字段值 97.3 保留（守护测试 resampled 判定路径依赖）。
        override val seed get() = Color(0xFF9946FA)
        override val secondarySeed get() = Color(0xFF09F955)
        override val tertiarySeed get() = Color(0xFFF07830)
        override val style get() = PaletteStyle.Vibrant
        override val group get() = PresetGroup.Mecha
        override val skeletonStain get() = SkeletonStain(
            darkHue = 255.0, darkChroma = 18.0, darkVariantChroma = 24.0,
            lightHue = 268.0, lightChroma = 8.0, lightVariantChroma = 12.0,
            lightPageTone = 97.3,
        )
        override val pageBaseLight get() = Color(0xFF9946FA)
        override val pageBaseDark get() = Color(0xFF5940A6)

        // 昼战覆写表（亮模式）：卡沉于底阶梯（严格降序）+ 涂装黑正文/卡域白字双域
        // （单色过底 + 过最深卡 4.5 的 L 窗口为空集，双域是数学必然）。
        // 井槽分家：surfaceContainerLowest = 深井档（与页面底分家）——chrome/sheet/thin
        // 玻璃带取源链（Glass.kt 直取 Lowest）随之落深井域，带面文字/图标一律走
        // onCard 浅字链（基域黑字对井槽 cr≈1.2 破 4.5，浅字链 ≥10）；井槽不在 G14
        // 阶梯断言链，文字域由 G13 onCard 对井槽断言承载。卡阶梯向深域拉深：
        // 黑字余量 0.11 钉死底不可压暗，卡只能向深生长（明度边界是眯眼存活的第一层次）。
        // dim/tint = 井上亮档（介于底与 Low 之间的染色锚，aurora 主斑取源槽——Glass.kt
        // auroraPrimary ← surfaceTint）；bright 除名不接线（全应用零消费槽，M3 派生值
        // 无人读取）；昼 outline 保持近黑缝（装饰缝位——对拉深卡梯 2.75 不足图形线，
        // focus 线由 borderEmphasis 亮线救援升 primary，缝不承担图形职责）；结构带
        // structureBand = #3D1E7E（= surfaceContainerLowest 井槽同值）——侧栏轨/页签栏
        // 两条常驻实底带整面换装（面积构成第二声部）：语义 = 装甲骨架带，取井槽同值
        // 与 chrome 取源链（标题栏/状态栏/浮层玻璃带同落深井域）连成连续暗色框架，
        // 对昼底 2.81 不过图形线——带上不放图形件/正文（消费位封闭集登记于
        // ThemeSlotOverrides.structureBand），带上前景统一配套字链 onStructureBand
        // #EDE6FF（对带 10.30）；选中件与 dock 选中（绿胶囊 selectedContainer +
        // onSelectedContainer 深字，绿对井轨 9.12）自成边界；夜表不传（夜战绿由
        // selectedContainer 语义承载，带零漂移）；橙对昼底 1.64 不裸放——橙只做徽章
        // （近黑字 + 硬边描边承担边界，呈现层适配）；图表 8 档对最浅承载面 Low 卡 ≥3。
        override val lightOverrides get() = ThemeSlotOverrides(
            surfaceContainerLowest = Color(0xFF3D1E7E),
            surfaceContainerLow = Color(0xFF6C22D8),
            surfaceContainer = Color(0xFF5A1BAF),
            surfaceContainerHigh = Color(0xFF4C1798),
            surfaceContainerHighest = Color(0xFF3F1281),
            surfaceDim = Color(0xFF8A2BF0),
            surfaceTint = Color(0xFF8A2BF0),
            surfaceVariant = Color(0xFF6C22D8),
            primary = Color(0xFFEDE6FF),
            onPrimary = Color(0xFF2E2064),
            primaryContainer = Color(0xFF2E2064),
            onPrimaryContainer = Color(0xFFEDE6FF),
            secondary = Color(0xFF09F955),
            onSecondary = Color(0xFF17131F),
            secondaryContainer = Color(0xFF2E2064),
            onSecondaryContainer = Color(0xFF09F955),
            tertiary = Color(0xFFF07830),
            onTertiary = Color(0xFF17131F),
            tertiaryContainer = Color(0xFFF07830),
            onTertiaryContainer = Color(0xFF231400),
            onSurface = Color(0xFF000000),
            onSurfaceVariant = Color(0xFF050014),
            onBackground = Color(0xFF000000),
            outline = Color(0xFF0D0218),
            outlineVariant = Color(0xFF1A0833),
            inverseSurface = Color(0xFFEDE6FF),
            inverseOnSurface = Color(0xFF2E2064),
            inversePrimary = Color(0xFF5940A6),
            selectedContainer = Color(0xFF09F955),
            onSurfaceFaint = Color(0xFF050014),
            structureBand = Color(0xFF3D1E7E),
            onStructureBand = Color(0xFFEDE6FF),
            onCardStrong = Color(0xFFFFFFFF),
            onCardMid = Color(0xFFEDE6FF),
            onCardFaint = Color(0xFFEBE1FC),
            consoleBg = Color(0xFF17131F),
            aiAccent = Color(0xFF09F955),
            aiAccentContainer = Color(0xFF2E2064),
            floatShadow = Color(0xFF000000),
            chartPalette = listOf(
                Color(0xFF09F955), Color(0xFFFFE082), Color(0xFF4DD0E1), Color(0xFFEDE6FF),
                Color(0xFF90CAF9), Color(0xFFF8BBD0), Color(0xFFFFB74D), Color(0xFFFFCC9C),
            ),
            platingGreen = Color(0xFF50B850),
        )

        // 夜战覆写表（暗模式，电光夜战）：装甲本漆铺底后井槽分家落画布黑 #0B0B0B
        // （井是深渊槽不是页面底）；阶梯 = 昼战阶梯倒放（卡浮起），与昼战只差一档明度，
        // primary 与昼战同色（两形态交互色合一）；正文三档 6.59/5.20/4.89 层级差 1.7
        // 靠字号字重补（结构性收窄登记）；橙对夜底 2.74 <3.0 → 徽章化（橙面 +
        // #231400 字，与昼战同语言）；aiAccent 覆写荧光绿（恒紫对夜底 2.93 破图形线，
        // 荧光绿与昼战同源主题自洽）；卡域三档覆写与昼战同板（回落在最亮卡 #7B2BE8 上
        // 4.08/3.84 破 4.5）；图表沿用昼战板（对夜底 8 档全 ≥3，昼夜连续性）；
        // surfaceDim/Bright/Tint 不覆写走 M3 派生（tint=primary 近白在 elevated 面
        // 的发白暴露面集中在 Dialog 系——本应用卡片 elevation 恒 0，截图验收关注）。
        // 装甲缝：outline 深缝域（对底 1.73/对卡 1.88——暗缝读装甲拼板，替代亮缝
        // 「贴纸」读感）；缝不承担图形可辨职责——checkbox/输入框边等焦点线消费位
        // 由 toolboxSurfaceColors 的 borderEmphasis 亮线救援承接（缝对任一承载面
        // cr<3.0 时焦点线自动升 primary 焦点档），outline 槽自身退装饰缝位。
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLowest = Color(0xFF0B0B0B),
            surfaceContainer = Color(0xFF241D40),
            surfaceContainerLow = Color(0xFF6C22D8),
            surfaceContainerHigh = Color(0xFF711EE6),
            surfaceContainerHighest = Color(0xFF7B2BE8),
            surfaceVariant = Color(0xFF453A6E),
            primary = Color(0xFFEDE6FF),
            onPrimary = Color(0xFF2E2064),
            primaryContainer = Color(0xFF5940A6),
            onPrimaryContainer = Color(0xFFEDE6FF),
            secondary = Color(0xFF09F955),
            onSecondary = Color(0xFF17131F),
            secondaryContainer = Color(0xFF0A3D14),
            onSecondaryContainer = Color(0xFF50B850),
            tertiary = Color(0xFFF07830),
            onTertiary = Color(0xFF231400),
            tertiaryContainer = Color(0xFFF07830),
            onTertiaryContainer = Color(0xFF231400),
            onSurface = Color(0xFFEDE6FF),
            onSurfaceVariant = Color(0xFFD3CEE1),
            onBackground = Color(0xFFEDE6FF),
            outline = Color(0xFF361B6A),
            outlineVariant = Color(0xFF33285A),
            inverseSurface = Color(0xFFEDE6FF),
            inverseOnSurface = Color(0xFF2A2148),
            inversePrimary = Color(0xFF5940A6),
            selectedContainer = Color(0xFF09F955),
            onSurfaceFaint = Color(0xFFCFC6E6),
            onCardStrong = Color(0xFFFFFFFF),
            onCardMid = Color(0xFFEDE6FF),
            onCardFaint = Color(0xFFEBE1FC),
            consoleBg = Color(0xFF17131F),
            aiAccent = Color(0xFF09F955),
            aiAccentContainer = Color(0xFF2E2064),
            floatShadow = Color(0xFF000000),
            chartPalette = listOf(
                Color(0xFF09F955), Color(0xFFFFE082), Color(0xFF4DD0E1), Color(0xFFEDE6FF),
                Color(0xFF90CAF9), Color(0xFFF8BBD0), Color(0xFFFFB74D), Color(0xFFFFCC9C),
            ),
            platingGreen = Color(0xFF50B850),
        )
        override val id get() = "electric-violet"
    }

    data object LavaCrimson : AppThemeSpec() {
        override val displayName get() = "熔岩红"
        // Vibrant：红相顶格保相——Expressive primary = 种子相+240° 会把红旋成蓝紫（禁用实证用例），
        // 红→橙温差改由骨架染色（h0）与第二种子承载。装甲红 #DB3A45 + secondarySeed 实战橙
        // #FF8C42（暗强调派生 #FFB68D）；红不承载长文本，红面警示文字恒浅字。
        // 双态底色主权（对标 电光紫 施工模板）：昼战装甲红本漆 #DB3A45 铺底、夜战兽化红黑 #6E1526 沉浸。
        // 昼表 = 电光紫 昼表同构：卡沉于底阶梯（Low 向受光面提亮 t44=#C42B3C 装甲受光域/
        // container t33/High t29/Highest=夜底 #6E1526，同 电光紫「昼最深卡=夜底」构式）
        // + 底域黑字/卡域白字双域（M3 派生 onSurface tone10 对昼底 3.84、次级档 2.09、
        // 固定实色 faint 1.4 全破 4.5——双域是数学必然）；卡域次级/辅助文字随 Low 提亮
        // 同步上移（旧 #EFCBD0/#E9C4C9 对受光 Low 3.6 破 4.5，新档 4.72/4.54）；dim/tint
        // 保持 #A91027 独立档（Low 旧值域，阴影语义）；primary 反白（对昼底 4.47 图形线）
        // + onSecondary 白（selectedContainer 回落 High 深红档的反白配套字，取字链经
        // toolboxSurfaceColors 消费）；橙对昼底 1.56 不裸放——实战橙只做徽章语义；
        // aiAccent 覆写浅黄（恒紫对昼底 1.28 破图形线，通电反白族的暖档）；consoleBg
        // 直配深红黑井（昼战红绿灯底衬承载位）。
        // 夜表 = 电光紫 夜表同构：卡浮起阶梯 t26/t29/t32（贴底只差一档）+ onSurfaceFaint 覆写
        // t78 转译（固定实色对夜底 1.85 破 4.5）；正文/描边/强调全走 M3 派生（对夜底
        // 9.05/6.88/3.68/aiAccent 4.29 全过线，夜表零身份槽覆写）。
        override val seed get() = Color(0xFFDB3A45)
        override val secondarySeed get() = Color(0xFFFF8C42)
        override val style get() = PaletteStyle.Vibrant
        override val group get() = PresetGroup.Mecha
        override val skeletonStain get() = SkeletonStain(
            darkHue = 0.0, darkChroma = 16.0, darkVariantChroma = 22.0,
            lightHue = 8.0, lightChroma = 7.0, lightVariantChroma = 10.0,
            lightPageTone = 96.5,
        )
        override val pageBaseLight get() = Color(0xFFDB3A45)
        override val pageBaseDark get() = Color(0xFF6E1526)
        override val lightOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFFC42B3C),
            surfaceContainer = Color(0xFF9F0321),
            surfaceContainerHigh = Color(0xFF8E001B),
            surfaceContainerHighest = Color(0xFF6E1526),
            surfaceDim = Color(0xFFA91027),
            surfaceBright = Color(0xFFDB3A45),
            surfaceTint = Color(0xFFA91027),
            surfaceVariant = Color(0xFF8E001B),
            primary = Color(0xFFFFFFFF),
            onPrimary = Color(0xFF5C0A16),
            primaryContainer = Color(0xFF5C0A16),
            onPrimaryContainer = Color(0xFFFFFFFF),
            onSecondary = Color(0xFFFFFFFF),
            tertiary = Color(0xFFFF8C42),
            onTertiary = Color(0xFF231400),
            tertiaryContainer = Color(0xFFFF8C42),
            onTertiaryContainer = Color(0xFF231400),
            onSurface = Color(0xFF000000),
            onSurfaceVariant = Color(0xFF0A0108),
            onBackground = Color(0xFF000000),
            outline = Color(0xFF33101A),
            outlineVariant = Color(0xFF4A1520),
            inverseSurface = Color(0xFFFFFFFF),
            inverseOnSurface = Color(0xFF5C0A16),
            inversePrimary = Color(0xFF6E1526),
            onSurfaceFaint = Color(0xFF0D0005),
            onCardStrong = Color(0xFFFFFFFF),
            onCardMid = Color(0xFFFBE7EB),
            onCardFaint = Color(0xFFF9E2E7),
            consoleBg = Color(0xFF20040B),
            aiAccent = Color(0xFFFFECB3),
        )
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFF751B2B),
            surfaceContainerHigh = Color(0xFF7F2231),
            surfaceContainerHighest = Color(0xFF882938),
            onSurfaceFaint = Color(0xFFFFAAB0),
        )
        override val id get() = "lava-crimson"
    }

    data object TricolorBlueGold : AppThemeSpec() {
        override val displayName get() = "蓝金三色"
        // Fidelity：经典四色是文物级配色，种子色相色度原样保留不提纯。经典蓝 #2A63C8（选中/主按钮）+
        // secondarySeed 警戒红 #D13438（删除/告警专用）+ tertiarySeed V字金 #F2C230（星标/高亮小面积）——
        // 全场唯一三强调套，红黄双强调依赖 tertiarySeed 覆写；三色强调都不承载正文。
        // 近无相白甲表面（暗 c6.3/亮 c2.6）让三点式分布成为身份。
        // 染色 (240,6,8)/(238,2,3)：暗 bg #101418（h246/c6.3 夜整备场冷灰）/ 亮 bg #FAF9FA。
        // 与 nord（同为冷灰蓝骨架）靠红+金强调拉开（A 87.6/89.5）。
        // 胸蓝升 selectedContainer #2A63C8（本值，亮态——对白甲 5.38 图形级，开关 on/
        // 导航选中/段选/卡选全链胸蓝面；暗态派生选中面不覆写）；配套字链
        // onSecondary = #FFFFFF（胸蓝面 5.65——派生 onSecondary 与本值同白，显式覆写
        // 为接通 onSelectedContainer 选中面文字链）。
        // 夜相登记（夜整备维持染色派生，不落 pageBaseDark）：夜整备场冷灰 #101418
        // （h245.8/c6.27/t6.1）与种子相（#2A63C8 h268.1）距 22.3°，hue-keep 主腿出带，
        // 替代腿色度闸不过（c6.27 < 10——白甲近无相口径与替代腿互斥）；白甲夜港的身份
        // 承载位是近无相白甲豁免 + 三点式强调，不是夜底相，维持派生。
        override val seed get() = Color(0xFF2A63C8)
        override val secondarySeed get() = Color(0xFFD13438)
        override val tertiarySeed get() = Color(0xFFF2C230)
        override val style get() = PaletteStyle.Fidelity
        override val group get() = PresetGroup.Mecha
        // 中性豁免：近无相白甲 + 三点式强调分布即身份，白甲不进重采样
        override val neutralSkeletonExempt get() = true
        override val skeletonStain get() = SkeletonStain(
            darkHue = 240.0, darkChroma = 6.0, darkVariantChroma = 8.0,
            lightHue = 238.0, lightChroma = 2.0, lightVariantChroma = 3.0,
        )
        override val lightOverrides get() = ThemeSlotOverrides(
            onSecondary = Color(0xFFFFFFFF),
            selectedContainer = Color(0xFF2A63C8),
        )
        override val id get() = "tricolor-blue-gold"
    }

    data object CrimsonGold : AppThemeSpec() {
        override val displayName get() = "猩红流金"
        // Vibrant：专用红顶格纯度（Expressive +240° 禁用——猩红→酒红→金属的相位叙事改由骨架+种子承载）。
        // 专用红 #C92540 + secondarySeed 玫瑰金 #DBA97C（分隔线/图标/次级按钮金属声部，派生 #F0BC8E）+
        // tertiarySeed 流金 #C9A227（暗态强调分隔器，#ECC246 高亮）。亮态保持香槟粉纸派生
        //（红底昼战不做：专用红上黑字 3.83 破 AA，宁缺勿滥）。
        // 夜态底色主权（暗态涂装批）：高速红本漆域深档 #8C2338 铺底。覆写表：卡浮起阶梯 t35/t37/t39（贴底只差
        // 一档构式）；onSurfaceFaint/outline 覆写同相转译档（固定实色 2.79、派生 outline 2.76
        // 破线）；aiAccent 覆写亮紫 #C4B5FD（恒紫对红底 2.74 破图形线；流金 tertiary 撞同值
        // 不可用——金系整体与 tertiary ΔE<30，紫粉亮档 min ΔE 44.6 常规线过）；卡域次级/辅助
        // 文字覆写近白档（M3 派生 tone80 对 t39 卡 3.96 破 4.5）。前景声部常驻化（暗态）：
        // selectedContainer = 流金 #C9A227 本值（暗态强调分隔器主色升选中面——对红底
        // 3.60 图形级，开关 on/导航选中/段选/卡选全链金面；亮态金族对香槟粉纸最高 2.26
        // 破图形线，不覆写）；配套字链 onSecondary = #17131F（金面 7.55——派生深红字对金
        // 面不足 4.5；chip 派生玫瑰金亮档 #F0BC8E 面深字 10.69 同步成立，玫瑰金=chip 常驻、
        // 流金=选中面常驻、 tertiary 亮档守徽章位）。
        override val seed get() = Color(0xFFC92540)
        override val secondarySeed get() = Color(0xFFDBA97C)
        override val tertiarySeed get() = Color(0xFFC9A227)
        override val style get() = PaletteStyle.Vibrant
        override val group get() = PresetGroup.Mecha
        override val skeletonStain get() = SkeletonStain(
            darkHue = 338.0, darkChroma = 16.0, darkVariantChroma = 22.0,
            lightHue = 350.0, lightChroma = 6.0, lightVariantChroma = 9.0,
            lightPageTone = 96.5,
        )
        override val pageBaseDark get() = Color(0xFF8C2338)
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFF962A3F),
            surfaceContainerHigh = Color(0xFF9C2F43),
            surfaceContainerHighest = Color(0xFFA23448),
            onSurfaceFaint = Color(0xFFFFAEB6),
            onCardMid = Color(0xFFF2DCE0),
            onCardFaint = Color(0xFFEAD2D7),
            outline = Color(0xFFF77486),
            onSecondary = Color(0xFF17131F),
            selectedContainer = Color(0xFFC9A227),
            aiAccent = Color(0xFFC4B5FD),
        )
        override val id get() = "crimson-gold"
    }

    // ── 撞色组 ──

    data object SkyWheat : AppThemeSpec() {
        override val displayName get() = "晴空麦浪"
        // Vibrant：两颗种子都以接近旗帜的高彩出场——晴空蓝 #0057B7 + secondarySeed 麦穗金
        // #FFD700（块状第二强调；高亮容器 tone-85 #E9C400 上恒深字 #705D00，黄不铺大面积）。
        // 亮态保持麦纸派生（麦黄带直采做亮底：黄纸全目录离群，不做）；暗态晴空蓝本漆直采铺底
        //（撞色面主带，L*38.3 夜战域上沿）。覆写表：卡浮起阶梯 t41/42/43（贴底只差一档，
        // 晴空蓝底亮把阶梯整体顶高）；晴空蓝底亮（tone38）把 M3 派生文字链整体挤出 4.5 窗——
        // onSurfaceVariant/onSurfaceFaint/outline 覆写亮蓝白档（派生 4.05/固定实色 2.17/
        // 派生 2.19 全破线）；aiAccent 覆写麦穗金（恒紫对晴空蓝 2.17 破图形线，黄在蓝上
        // 是撞色面经典对比 4.91）；platingGreen 覆写黄绿亮档（静态 #50B850 对晴空蓝 2.73 破
        // 图形线）；卡域三档覆写近白档（派生链对 t43 卡 4.47/3.40 破 4.5）。
        // 葵黄升 selectedContainer #FFD700（撞色面黄蓝对 4.91 图形级——开关 on/导航选中/
        // 段选/卡选全链撞色面经典对比；亮态黄麦纸同族不成对面，不覆写）；配套字链
        // onSecondary = #17131F（黄面 13.03——撞色面蓝黄双层配深字，派生白字对黄 1.1 破 4.5）。
        override val seed get() = Color(0xFF0057B7)
        override val secondarySeed get() = Color(0xFFFFD700)
        override val style get() = PaletteStyle.Vibrant
        override val group get() = PresetGroup.Flag
        override val skeletonStain get() = SkeletonStain(
            darkHue = 258.0, darkChroma = 11.0, darkVariantChroma = 14.0,
            lightHue = 95.0, lightChroma = 8.0, lightVariantChroma = 12.0,
            lightPageTone = 97.3,
        )
        override val pageBaseDark get() = Color(0xFF0057B7)
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFF145EBE),
            surfaceContainerHigh = Color(0xFF1960C1),
            surfaceContainerHighest = Color(0xFF1E63C3),
            onSurfaceVariant = Color(0xFFCCE0FF),
            onSurfaceFaint = Color(0xFFBFD5F8),
            onCardStrong = Color(0xFFFFFFFF),
            onCardMid = Color(0xFFDDE8FF),
            onCardFaint = Color(0xFFDAE5FF),
            outline = Color(0xFF88B0FF),
            onSecondary = Color(0xFF17131F),
            selectedContainer = Color(0xFFFFD700),
            aiAccent = Color(0xFFFFFFFF),
            platingGreen = Color(0xFF8BC34A),
        )
        override val id get() = "sky-wheat"
    }

    data object VermilionPaper : AppThemeSpec() {
        override val displayName get() = "朱砂宣纸"
        // Fidelity：朱砂宣纸灵魂是朱红原色 + 纯墨中性 + 大留白，任何档位扭曲都毁极简。
        // 朱砂宣纸朱红 #BC002D（Munsell 5R 4/12 通行转写）+ secondarySeed 宣纸白 #F7F4EC
        //（白的语义位：浮层/正文族）。染色 (30,2,3)/(30,1,2)——全目录唯一近零染色底：
        // 暗 bg #161312（h40/c1.9 暖墨）/ 亮 bg #FEF8F6（c1.0 宣纸）；屏上永远只有一个红色焦点区。
        // 朱红纪律：原红对墨面 2.94:1 不达图形阈值——暗态朱只做实心容器或亮朱文字
        //（派生 #FFB3B3 文字档 11+:1）。
        // 夜相登记（夜之丸维持染色派生，不落 pageBaseDark）：① 暖墨夜底 #161312
        // （h39.7/c1.95/t6.1）与种子朱红相（#BC002D h17.9）距 21.9°，hue-keep 主腿出带，
        // 替代腿色度闸不过（c1.95 < 10——近零染色与替代腿互斥）；② 暖墨相与警示橙暖黑夜
        // 场同族（hue 距 1.0°），夜相直采的取值空间被同族 A 腿钉死；③ 近零染色（宣纸白/
        // 暖墨）即题材本体——夜之丸的身份承载位是朱印纪律不是墨底深浅。三重依据登记于
        // docs/core/theme.md「配色裁定演变」。
        override val seed get() = Color(0xFFBC002D)
        override val secondarySeed get() = Color(0xFFF7F4EC)
        override val style get() = PaletteStyle.Fidelity
        override val group get() = PresetGroup.Flag
        // 中性豁免：近零染色（宣纸白/暖墨）即题材本体，不进重采样
        override val neutralSkeletonExempt get() = true
        override val skeletonStain get() = SkeletonStain(
            darkHue = 30.0, darkChroma = 2.0, darkVariantChroma = 3.0,
            lightHue = 30.0, lightChroma = 1.0, lightVariantChroma = 2.0,
        )
        override val id get() = "vermilion-paper"
    }

    // ── 传统组 ──

    data object Qinghua : AppThemeSpec() {
        override val displayName get() = "青花"
        // Fidelity 保 hue261/c40 的钴蓝本相：「白瓷上只许一种蓝」，浓淡分水交给 tonal 阶梯。
        // 亮 primary #224B81（深钴蓝）/ 暗 primary #A8C8FF；Fidelity 互补副作用让 tertiary
        // 翻金族（亮 #614600 / 暗 #EDC062——青花金彩，窑变红利）。
        // 染色 (255,17,21)/(250,7,10)@96.8——暗骨架深钴蓝瓷档：暗 bg #061423（h254/c16.9）/
        // 亮 bg #F3F6FD（c6.9 瓷白蓝，升色度 + 降 tone 双解 97.9 钳制）。
        // 「深蓝整窗 vs Default 靛紫纸」一眼分清（与 Default 复合腿 S=8.1/A=17.3 过线）。
        // 「一档蓝五档浓淡」（两态覆写，色相恒钴蓝一档、浓度分五档）：
        // 亮 = 瓷白底 #F3F6FD 起卡的钴蓝由浓到淡倒挂阶（卡沉于底，越深越浓）——
        // #E6EBF5(c9.0/t92.9)→#DCE3F0(c11.1/t90.1)→#CFD8E7(c12.7/t86.1)→#BCC7DA(c15.3/t80.0)，
        // selectedContainer = 头浓钴蓝 #224B81（h261.2/c39.4/t31.7 = 亮 primary 本值；白瓷底
        // 6.90 图形级 + 白字 7.46，选中面/开关 on/导航选中/段选/卡选全链；onSecondary =
        // #FFFFFF 接通配套字链，chip 派生钴灰面 #525F76 白字同链成立）；
        // 暗 = 深瓷底 #061423 起浮卡阶色度上行（#18202A c11.9→#1F2835 c13.9→#273241 c15.5
        // →#303D50 c18.2），暗 selectedContainer 不覆写（头浓钴蓝在暗底不可读，亮面走派生档）。
        // 夜相主权（pageBaseDark 直采）：#061423 = 染色派生暗底同值（h254.2/c16.9/t6.0）——
        // 夜瓷蓝（深瓷底钴蓝更幽）以套参数钉死、脱离 M3 暗 tone 表，暗卡浮起阶与夜瓷底
        // 的同相关系由染色单源保持；辅助文字 t78 同相转译 onSurfaceFaint #B9C1CF
        //（h254.7/c12.1/t77.9——固定实色 #8A90A3 对浮卡阶 4.08-3.46 破 4.5，转译档对底
        // 10.24 / 对 Highest 卡 6.07）。
        // 白瓷底 L*96.8 亮域正文深字（onSurface t10 对阶梯全档 ≥10:1；onSurfaceVariant
        // 对新 Low/High 卡 7.86/6.55，暗 onSurfaceVariant 对新 High 卡 7.63——AA 抽验）。
        override val seed get() = Color(0xFF3D639B)
        override val style get() = PaletteStyle.Fidelity
        override val group get() = PresetGroup.Culture
        override val skeletonStain get() = SkeletonStain(
            darkHue = 255.0, darkChroma = 17.0, darkVariantChroma = 21.0,
            lightHue = 250.0, lightChroma = 7.0, lightVariantChroma = 10.0,
            lightPageTone = 96.8,
        )
        override val pageBaseDark get() = Color(0xFF061423)
        override val lightOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFFE6EBF5),
            surfaceContainer = Color(0xFFDCE3F0),
            surfaceContainerHigh = Color(0xFFCFD8E7),
            surfaceContainerHighest = Color(0xFFBCC7DA),
            selectedContainer = Color(0xFF224B81),
            onSecondary = Color(0xFFFFFFFF),
        )
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFF18202A),
            surfaceContainer = Color(0xFF1F2835),
            surfaceContainerHigh = Color(0xFF273241),
            surfaceContainerHighest = Color(0xFF303D50),
            onSurfaceFaint = Color(0xFFB9C1CF),
        )
        override val id get() = "qinghua-cobalt-white"
    }

    data object Gulal : AppThemeSpec() {
        override val displayName get() = "洒红节"
        // Vibrant：品红主相顶格饱和承载彩粉记忆（FruitSalad −50° 会旋成紫撞 aiAccent 线，否决）。
        // 亮 primary #B7006F / 暗 primary #FFB0CE。
        // 亮态彩纸主权（lightPageTone 95.0 粉雾纸）：洒红节彩粉的昼相纸面必须读出粉雾——
        // t96.5 处 gamut 只交付 c6.6（近白纸毁题材），降 tone 换色域宽是唯一解钳机制：
        // 粉雾纸 #FFEBF7（h335.7/c10.3/t94.9 直采回读）；有效域下沿 94.5 由本套钉定
        //（回读 94.90 留量化容差）。亮卡阶梯随纸面加深覆写（粉雾浓淡阶，色度随档上行）：
        // Low #F9E6F1(t93.0/c9.7)→container #F4E0ED(t91.1/c10.8)→High #F0DAE8(t89.2/c11.8)
        // →Highest #E9D2E2(t86.5/c13.0)；onSurfaceFaint 对纸 5.51 / 对 Low 卡 5.26、
        // onSurfaceVariant 对 Low/High 卡 7.87/7.10、onSurface 对 Highest 卡 12.09、
        // primary 对纸 5.70（AA/图形抽验）。
        // 夜相主权（pageBaseDark 同值直采换轨，hue-keep 替代腿准入）：暗态玫黑 #210C1C
        // （h340.3/c19.36/t6.1，染色顶格）= 染色派生暗底同值——夜相底以套参数钉死、脱离
        // M3 暗 tone 表。hue-keep 准入：主腿底相 vs 种子相（#DC4B92 h355.5）距 15.2° 出带；
        // 替代腿三闸全过（t6.1 < 20 ∧ c19.36 ≥ 10 ∧ 底相距在册染色暗档相 340.0 仅 0.3°
        // < 10°）。对比度抽验（G13 域拆随 pageBase 激活）：onSurface 对夜底 14.37 /
        // onSurfaceVariant 对 Highest 卡 7.24；辅助文字 t78 同相转译 onSurfaceFaint
        // #D4BAC9（h340.4/c13.9/t78.1——固定实色 #8A90A3 对 Highest 卡 3.86 破 4.5，
        // 转译档对底 10.29 / 对 Highest 卡 6.83）。与 熔岩红 暗同族 A=27.6（全目录同族最紧）。
        override val seed get() = Color(0xFFDC4B92)
        override val style get() = PaletteStyle.Vibrant
        override val group get() = PresetGroup.Culture
        override val skeletonStain get() = SkeletonStain(
            darkHue = 340.0, darkChroma = 20.0, darkVariantChroma = 30.0,
            lightHue = 335.0, lightChroma = 10.0, lightVariantChroma = 16.0,
            lightPageTone = 95.0,
        )
        override val pageBaseDark get() = Color(0xFF210C1C)
        override val lightOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFFF9E6F1),
            surfaceContainer = Color(0xFFF4E0ED),
            surfaceContainerHigh = Color(0xFFF0DAE8),
            surfaceContainerHighest = Color(0xFFE9D2E2),
        )
        override val darkOverrides get() = ThemeSlotOverrides(
            onSurfaceFaint = Color(0xFFD4BAC9),
        )
        override val id get() = "holi-gulal-colors"
    }

    data object CheckerEmerald : AppThemeSpec() {
        override val displayName get() = "市松翠格"
        // Fidelity：深翠本相保真（顶格 Vibrant 会变荧光绿毁墨感）。深翠 #257554 /
        // 暗 primary #89D6AF（tone-80 翠光）；Fidelity 互补副作用让 tertiary 翻绯
        // #FFB3B3 / #813A3C——恰成绯红点缀的窑变红利。
        // 染色 (158,12,18)/(158,9,12)：亮 bg #F2FCF1（c8.9，升色度至绿带地板上方——97.9 管线内可达）。
        // 夜相主权（pageBaseDark 提 tone 直采）：市松夜（市松翠格夜战）的夜战舞台整窗在场——
        // 墨绿黑提 tone 至 #1A271D（h155.9/c12.4/t14.2，色度地板 10.0 上方；t6 派生近黑
        // 沉底撑不起夜战场景读感）；卡浮起阶随底上移（darkOverrides 同相压 tone，贴底
        // 「只差一档」构式）：Low #1D2B21(t16.0/c12.7)→container #1F2E24(t17.3/c13.1)→
        // High #223226(t19.1/c14.2)→Highest #26382B(t21.6/c15.3)，色度随档上行；
        // 辅助文字 t78 同相转译 onSurfaceFaint #B6C5B7（h157.2/c11.9/t78.1——固定实色
        // #8A90A3 对浮起卡阶 3.9-4.3 破 4.5，转译档对底 8.62 / 对 Highest 卡 6.93）；
        // onSurfaceVariant 对底 9.11 / 对 Highest 卡 7.31、platingGreen 对底 6.15
        //（AA/图形抽验）。
        // 市松格纹走图案槽（surfacePattern = Checkerboard，只进样张 base 块纹理采样，
        // 双色取暗卡浮起阶首末两档；不进派生管线，契约见 ThemeSlotOverrides.surfacePattern）；
        // 与矩阵（绿近亲）分离由
        // 荧光绿声部 vs 深翠本相的 A 腿承载，提 tone 后骨架腿同步拉开。
        override val seed get() = Color(0xFF257554)
        override val style get() = PaletteStyle.Fidelity
        override val group get() = PresetGroup.Culture
        override val skeletonStain get() = SkeletonStain(
            darkHue = 158.0, darkChroma = 12.0, darkVariantChroma = 18.0,
            lightHue = 158.0, lightChroma = 9.0, lightVariantChroma = 12.0,
        )
        override val pageBaseDark get() = Color(0xFF1A271D)
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFF1D2B21),
            surfaceContainer = Color(0xFF1F2E24),
            surfaceContainerHigh = Color(0xFF223226),
            surfaceContainerHighest = Color(0xFF26382B),
            onSurfaceFaint = Color(0xFFB6C5B7),
            surfacePattern = ThemeSurfacePattern.Checkerboard,
        )
        override val id get() = "checker-emald"
    }

    // ── 终端组 ──

    data object DraculaNeon : AppThemeSpec() {
        override val displayName get() = "Dracula"
        // Vibrant「黑舞台+霓虹灯」的定义式用法：色度预算全烧给荧光点缀组。王紫 #BD93F9（官方 Purple）+
        // secondarySeed 骚粉 #FF79C6（官方 Pink——紫×粉双色记忆点）。亮态保持 97.0 染色派生
        // #F8F5FF（官方无亮态规范）；暗态官方 bg #282A36 直采铺底（暗态涂装批）。
        // 暗态覆写表：井分家落官方 bg darker 档 #191A21（consoleBg 同值单源）；卡浮起阶梯
        // t24/t28/t32（同相压 tone，贴底「只差一档」构式）；荧光绿/橙进 secondary/tertiary
        // 强调位（官方 Green/Orange 原值，对底 10.38/5.97）；selectedContainer = 官方
        // selection #44475A（开关 on/导航选中/段选/卡选全链跟随）——面档选中面（对底
        // 1.56 <3.0，官方 selection 保真），可辨性由选中面配套字链承载（onSelectedContainer
        // 回落 onSurface 亮字 7.09 ✓，G13 选中面分档断言的字链腿）；chip 走 secondary 荧光绿
        // + 派生 onSecondary 深字 9.55（onSecondary 不覆写——两链解耦，单值服务不了
        // #44475A 亮字与 #50FA7B 深字两面）；onSurfaceFaint 覆写 t78 转译（固定实色
        // #8A90A3 对直采底 4.47 <4.5）。aiAccent 恒紫常规线不过（28.9）走异族强调救援
        // （荧光绿 ΔE76 80+ ∧ 色相距 150°）。
        override val seed get() = Color(0xFFBD93F9)
        override val secondarySeed get() = Color(0xFFFF79C6)
        override val style get() = PaletteStyle.Vibrant
        override val group get() = PresetGroup.Terminal
        override val skeletonStain get() = SkeletonStain(
            darkHue = 292.0, darkChroma = 14.0, darkVariantChroma = 20.0,
            lightHue = 280.0, lightChroma = 7.0, lightVariantChroma = 10.0,
            lightPageTone = 97.0,
        )
        override val pageBaseDark get() = Color(0xFF282A36)
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLowest = Color(0xFF191A21),
            surfaceContainerLow = Color(0xFF373845),
            surfaceContainerHigh = Color(0xFF40414E),
            surfaceContainerHighest = Color(0xFF454B58),
            secondary = Color(0xFF50FA7B),
            tertiary = Color(0xFFFFB86C),
            selectedContainer = Color(0xFF44475A),
            onSurfaceFaint = Color(0xFFBBC1D0),
            consoleBg = Color(0xFF191A21),
        )
        override val id get() = "dracula-neon"
    }

    data object GruvboxWarmPaper : AppThemeSpec() {
        override val displayName get() = "Gruvbox"
        // Fidelity：锈橙保相 + 苔绿第二声部。锈橙 #D65D0E（官方 neutral_orange）+
        // secondarySeed 苔绿 #98971A（官方 neutral_green——对警示橙/朱砂宣纸/鎏金等暖纸近亲的
        // 分隔器：亮 A=31.3 / 暗 59.0）。
        // 亮态官方 light0 #FBF1C7 直采（全目录唯一非白纸亮底；暗态涂装批）；亮卡阶梯走官方
        // 面板档（light0_soft/bg2/bg3 严格降序）+ onCardMid/Faint 覆写官方 fg 系深档
        // （M3 派生次级文字对 #BDAE93 卡 4.29/2.88 破 4.5）。前景声部常驻化（全链走
        // selectedContainer/secondary 语义槽——开关 on/导航选中/段选/卡选随
        // selectedContainer，chip 随 secondary）：selectedContainer = 锈橙 #D65D0E
        // （对纸 3.41 图形级）+ secondary = 苔绿 #98971A 本值（chip 面——派生 tone40
        // #626200 提到本值，对纸 2.73 面档由选中点形状冗余承载）；配套字链
        // onSecondary = #17131F（锈橙面 4.73/苔绿面 5.90——派生白字对两值 3.1 破 4.5）。
        // 暗态不传参（保持染色派生 #1D1108）——官方中性暗底 #282828 色度 0.98 过不了
        // 暗态色度地板 10.0，与守护互斥故退单亮态传参（宁缺勿滥，暗态零漂移）。
        override val seed get() = Color(0xFFD65D0E)
        override val secondarySeed get() = Color(0xFF98971A)
        override val style get() = PaletteStyle.Fidelity
        override val group get() = PresetGroup.Terminal
        override val skeletonStain get() = SkeletonStain(
            darkHue = 55.0, darkChroma = 10.0, darkVariantChroma = 14.0,
            lightHue = 65.0, lightChroma = 12.0, lightVariantChroma = 16.0,
            lightPageTone = 96.5,
        )
        override val pageBaseLight get() = Color(0xFFFBF1C7)
        override val lightOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFFF2E5BC),
            surfaceContainerHigh = Color(0xFFD5C4A1),
            surfaceContainerHighest = Color(0xFFBDAE93),
            secondary = Color(0xFF98971A),
            onSecondary = Color(0xFF17131F),
            selectedContainer = Color(0xFFD65D0E),
            onCardMid = Color(0xFF3C3836),
            onCardFaint = Color(0xFF464138),
        )
        override val id get() = "gruvbox-warm-paper"
    }

    data object NordFrost : AppThemeSpec() {
        override val displayName get() = "Nord"
        // Fidelity：官方 nord9 主交互蓝 #81A1C1 + nord8 霜蓝强调（secondarySeed #88C0D0）
        // 的官方关系保真。种子取 nord9 非 nord10（保留更浅派生主的区分余量，勿换 nord10）。
        // 双态官方底直采（暗态涂装批）：亮 = nord5 #E5E9F0（Snow Storm 深档；nord6 #ECEFF4 色度 5.995 差 0.005
        // 不过蓝紫带地板 6.0，挪 nord5 即过且更贴「非纯白的白」）；暗 = nord0 #2E3440。
        // 亮卡阶梯 t86/82/78/74 同相压 tone（官方 Snow Storm 无暗于 nord5 的面板档），
        // 卡域次级文字覆写 nord1/nord1-2 之间转译（M3 派生对 t74 卡 4.24/3.0 破 4.5）；
        // 暗卡阶梯 = 官方 Polar Night 档 nord1/nord2/nord3 直采（t26/29/32 升序天然成立），
        // onSurfaceFaint 覆写 t78 转译（固定实色 #8A90A3 对 nord0 3.92 破 4.5）。
        override val seed get() = Color(0xFF81A1C1)
        override val secondarySeed get() = Color(0xFF88C0D0)
        override val style get() = PaletteStyle.Fidelity
        override val group get() = PresetGroup.Terminal
        override val skeletonStain get() = SkeletonStain(
            darkHue = 262.0, darkChroma = 12.0, darkVariantChroma = 16.0,
            lightHue = 255.0, lightChroma = 12.0, lightVariantChroma = 16.0,
            lightPageTone = 97.4,
        )
        override val pageBaseLight get() = Color(0xFFE5E9F0)
        override val pageBaseDark get() = Color(0xFF2E3440)
        override val lightOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFFD3D7DE),
            surfaceContainer = Color(0xFFC8CCD3),
            surfaceContainerHigh = Color(0xFFBDC1C8),
            surfaceContainerHighest = Color(0xFFB2B6BD),
            onCardMid = Color(0xFF3B4252),
            onCardFaint = Color(0xFF3E4656),
        )
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLowest = Color(0xFF1A202B),
            surfaceContainerLow = Color(0xFF383E4A),
            surfaceContainerHigh = Color(0xFF3F4551),
            surfaceContainerHighest = Color(0xFF454B58),
            onSurfaceFaint = Color(0xFFBBC1D0),
        )
        override val id get() = "nord-frost"
    }

    // ── 情绪组 ──

    data object Matrix : AppThemeSpec() {
        override val displayName get() = "矩阵数字雨"
        // Vibrant：荧光感 = 顶格色度（TonalSpot 派灰草绿）。暗 primary #00E560（磷光绿）/
        // 亮 primary #006E2A（深绿保 AA）。
        // 染色 (145,14,20)/(145,11,14)：暗 bg #0A1609（h146/c14.2 CRT 绿黑）/
        // 亮 bg #F1FCEA（c11.2，升色度至绿带地板上方——97.9 管线内可达）。
        // 与军械绿暗排 S=5.1/A=29.4（荧光绿黑 vs 军绿黑）；aiAccent ΔE76 119.8/81.1。
        // 数字绿声部落（暗态覆写）：selectedContainer = #00E560 磷光绿本值（= 派生暗 primary
        // 同源同值——终端选中语义 = 绿块反白，与既有绿语义同源不冲突、不引入第二绿），
        // 对 CRT 绿黑底 10.6 图形级；配套墨 onSecondary = #141E13（绿面 10.1 / 灰绿 chip 面
        // #A6D0B8 上 9.8——选中链与 chip 链一墨双面）。CRT 屏桶微光阶梯（暗态卡阶）：
        // #162114(c13.2/t11.3)→#1A2719(c14.2/t14.1)→#1F2C1D(c14.5/t16.4)→#273826(c17.0/t21.6)，
        // 色度随档上行 = 屏桶微光向亮处增强；亮态不动（基态绿纸已即题材）。
        override val seed get() = Color(0xFF00C853)
        override val style get() = PaletteStyle.Vibrant
        override val group get() = PresetGroup.Mood
        override val skeletonStain get() = SkeletonStain(
            darkHue = 145.0, darkChroma = 14.0, darkVariantChroma = 20.0,
            lightHue = 145.0, lightChroma = 11.0, lightVariantChroma = 14.0,
        )
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFF162114),
            surfaceContainer = Color(0xFF1A2719),
            surfaceContainerHigh = Color(0xFF1F2C1D),
            surfaceContainerHighest = Color(0xFF273826),
            selectedContainer = Color(0xFF00E560),
            onSecondary = Color(0xFF141E13),
        )
        override val id get() = "matrix-terminal"
    }

    data object Hazard : AppThemeSpec() {
        override val displayName get() = "警示橙"
        // Vibrant：警示感 = 顶格饱和橙（TonalSpot 派土褐）。亮 primary #A04100（安全橙深档）/
        // 暗 primary #FFB693。
        // 染色 (40,18,26)/(38,8,12)@96.5：亮 bg #FFF2EF（c3.8，参数不动、降 tone 重采样解 97.9 处 c1.3 钳制）。
        // 夜相主权（pageBaseDark 提 tone 直采）：黑黄警示夜场——警示黑 #30170D
        //（h39.5/c17.9/t11.1，t6 派生近黑在场感不足，提一档让夜幕下的警示黑读得出暖相，
        // 色度地板 10.0 上方）；卡浮起阶同相压 tone 贴底（darkOverrides「只差一档」构式）：
        // Low #351B10(t13.1/c18.2)→container #381E13(t14.5/c17.7)→High #3D2015(t15.9/c19.3)
        // →Highest #442519(t18.5/c20.0)；辅助文字 t78 同相转译 onSurfaceFaint #DDB9AC
        //（h40.0/c14.1/t77.9——固定实色 #8A90A3 对 Highest 卡 4.33 破 4.5，转译档对底
        // 9.26 / 对 Highest 卡 7.62）；onSurfaceVariant 对底 9.88、platingGreen 对底 6.63
        // （AA/图形抽验）。与 gruvbox 暖纸家族的同族分离仍由苔绿分隔器 A 腿承载。
        // 图案槽 surfacePattern = HazardStripe（只进样张 base 块 45° 斜纹采样，双色 =
        // warning 黄 + 警示黑底——条带的黑即本套夜场警示黑，契约见 ThemeSlotOverrides.surfacePattern）。
        override val seed get() = Color(0xFFE86100)
        override val style get() = PaletteStyle.Vibrant
        override val group get() = PresetGroup.Mood
        override val skeletonStain get() = SkeletonStain(
            darkHue = 40.0, darkChroma = 18.0, darkVariantChroma = 26.0,
            lightHue = 38.0, lightChroma = 8.0, lightVariantChroma = 12.0,
            lightPageTone = 96.5,
        )
        override val pageBaseDark get() = Color(0xFF30170D)
        override val darkOverrides get() = ThemeSlotOverrides(
            surfaceContainerLow = Color(0xFF351B10),
            surfaceContainer = Color(0xFF381E13),
            surfaceContainerHigh = Color(0xFF3D2015),
            surfaceContainerHighest = Color(0xFF442519),
            onSurfaceFaint = Color(0xFFDDB9AC),
            surfacePattern = ThemeSurfacePattern.HazardStripe,
        )
        override val id get() = "hazard-orange"
    }

    data object CyanNeon : AppThemeSpec() {
        override val displayName get() = "青白霓虹"
        // Fidelity：青白本相保真（顶格会毁「青白」的 pale 质感）——青白 #8FD3E8
        //（暗 primary #B8EDFF 潜航主交互）+ secondarySeed 霓虹粉 #FF5CA8（强调/选中：
        // #FFB0CD / #B2196C，不铺面）。
        // 染色 (246,14,20)/(240,7,10)@97.4：暗 bg #08141F（h249/c13.9 深蓝黑潜航夜面）/
        // 亮 bg #F4F8FE（c6.8 潜航冷纸，升色度 + 微挪相 244→240 + 降 tone 重采样解钳）。
        // 与 nord（同为深蓝黑骨架）A=77.7/48.1（青白+粉 vs 霜蓝）。
        // 霓虹描边（暗态覆写 outline/outlineVariant，卡阶维持潜航冷黑派生不动）：
        // outline = #E98FC1（霓虹粉域 h347.9/c45.2/t70.1，对底 7.60——描边/焦点线读霓虹灯管，
        // 过图形线 3.0 直接承载 borderEmphasis 无需救援）；outlineVariant = #5F6D74
        //（青灰域 h226.7/c11.9/t45.1，对底 3.47——细分隔线比粉主描边降一档）。亮态不动。
        override val seed get() = Color(0xFF8FD3E8)
        override val secondarySeed get() = Color(0xFFFF5CA8)
        override val style get() = PaletteStyle.Fidelity
        override val group get() = PresetGroup.Mood
        override val skeletonStain get() = SkeletonStain(
            darkHue = 246.0, darkChroma = 14.0, darkVariantChroma = 20.0,
            lightHue = 240.0, lightChroma = 7.0, lightVariantChroma = 10.0,
            lightPageTone = 97.4,
        )
        override val darkOverrides get() = ThemeSlotOverrides(
            outline = Color(0xFFE98FC1),
            outlineVariant = Color(0xFF5F6D74),
        )
        override val id get() = "cyan-neon"
    }

    data object MintTeal : AppThemeSpec() {
        override val displayName get() = "薄荷青"
        // Fidelity：#36C8AA 本相保真（软薄荷感不提纯）+ secondarySeed 藏青 #1E3A8A
        //（只做 secondary/tertiary：#B6C4FF / #4059AA）。
        // 昼夜参照系 = 聚光派（备选夜幕派弃用）：亮态保真中性灰骨架——染色亮档 (225,2,3)
        // @97.9 锚 → bg #FAF9F9（c2.3 灰白）逐位不动；暗态 = 黑舞台底 + 荧光青——
        // pageBaseDark #0A1A17（h183.0/c11.8/t7.8 青绿相暗黑，暗态色度地板 chroma≥10 同域取值，
        // tone 低于派生卡 Low ~10 保「卡浮起」升序）+ 染色暗档 (183,10,14)（派生阶梯/文字/
        // 描边随舞台相）+ selectedContainer = #36C8AA 荧光青本值（对舞台底 8.52 图形级，
        // 选中面/开关 on/导航选中/段选/卡选全链）+ onSecondary #182120 配套墨（青面 7.83 /
        // 藏青 chip 面 9.65）+ consoleBg #0D1210（井低于舞台底，consoleText 9.92）+
        // onSurfaceFaint #B5C4C3（t78 同相转译——辅助文字固定实色 #8A90A3 对派生 Highest 卡
        // 3.89 破 4.5，转译档对 6.87，pageBase 套 onCardFaint 回落链随此成立）。
        // 中性豁免保留：豁免位不闸 pageBaseDark/覆写表（蓝金三色 先例），暗态特色不经豁免；
        // 撤豁免反而触发亮态分带色度地板（h213 线 8.0 对 c2.3 灰白纸）——与「亮态零漂移」互斥。
        override val seed get() = Color(0xFF36C8AA)
        override val secondarySeed get() = Color(0xFF1E3A8A)
        override val style get() = PaletteStyle.Fidelity
        override val group get() = PresetGroup.Mood
        // 中性豁免：亮态真中性灰骨架（不掺青）即题材规格，不进重采样与分带地板
        override val neutralSkeletonExempt get() = true
        override val skeletonStain get() = SkeletonStain(
            darkHue = 183.0, darkChroma = 10.0, darkVariantChroma = 14.0,
            lightHue = 225.0, lightChroma = 2.0, lightVariantChroma = 3.0,
        )
        override val pageBaseDark get() = Color(0xFF0A1A17)
        override val darkOverrides get() = ThemeSlotOverrides(
            selectedContainer = Color(0xFF36C8AA),
            onSecondary = Color(0xFF182120),
            onSurfaceFaint = Color(0xFFB5C4C3),
            consoleBg = Color(0xFF0D1210),
        )
        override val id get() = "mint-teal"
    }

    data object CrimsonNight : AppThemeSpec() {
        override val displayName get() = "绯红夜城"
        // Fidelity：绯红本相保真 + secondarySeed 琥珀 #F2A33C（高亮/警示：#FFB964 / #875300）。
        // 亮态不动：冷蓝纸异族骨架（染色 (252,14,18)@96.5 → bg #F0F5FF c9.0 冷蓝纸，
        // 夜幕红字压冷蓝夜城的昼相）。
        // 暗态 = 夜幕派（备选夜城场景派弃用）：pageBaseDark #1F0F10（h14.7/c11.6/t6.1
        // 绯红相黑底，过暗态色度地板 chroma 10.0，tone 低于派生卡 Low 保升序）+
        // 染色暗档 (14,12,16)（派生阶梯/文字/描边随夜幕红相，consoleBg 随染色派生绯红井）+
        // 绯红声部：secondary #C35B66（h13.9/c50.2/t51.9 chip 面，配套墨 #1A0507 对面 4.69）+
        // selectedContainer #D25E6B（h13.9/c55.1/t54.8 夜幕红选中面，对底 5.2 图形级，
        // 配套墨 5.27——选中链与 chip 链共用一墨，两面同处中明度红域）+
        // tertiary #AB515A + tertiaryContainer #723B40 / onTertiaryContainer #FFCACD（6.04）+
        // onSurfaceFaint #E3B6B8（t78 同相转译——辅助文字固定实色 #8A90A3 对派生 Highest 卡
        // 3.89 破 4.5，转译档对 6.87，pageBase 套 onCardFaint 回落链随此成立）。
        // G9 对位：与猩红流金暗底 #8C2338 bg 对 46.74（底明度差承载，无需翻红预案）；
        // 与 gulal 暗同族 A=35.9、与 熔岩红 暗同族 A=42.0
        // （饱和绯红 vs 粉色系派生的分离余量）。
        override val seed get() = Color(0xFFD92632)
        override val secondarySeed get() = Color(0xFFF2A33C)
        override val style get() = PaletteStyle.Fidelity
        override val group get() = PresetGroup.Mood
        override val skeletonStain get() = SkeletonStain(
            darkHue = 14.0, darkChroma = 12.0, darkVariantChroma = 16.0,
            lightHue = 252.0, lightChroma = 14.0, lightVariantChroma = 18.0,
            lightPageTone = 96.5,
        )
        override val pageBaseDark get() = Color(0xFF1F0F10)
        override val darkOverrides get() = ThemeSlotOverrides(
            secondary = Color(0xFFC35B66),
            selectedContainer = Color(0xFFD25E6B),
            onSecondary = Color(0xFF1A0507),
            tertiary = Color(0xFFAB515A),
            tertiaryContainer = Color(0xFF723B40),
            onTertiaryContainer = Color(0xFFFFCACD),
            onSurfaceFaint = Color(0xFFE3B6B8),
        )
        override val id get() = "crimson-night"
    }

    // ── 自定义主题（覆盖式单例）──

    data class Custom(override val seed: Color) : AppThemeSpec() {
        override val displayName get() = "自定义"
        override val id get() = "Custom"
    }

    companion object {

        // 组序即分组渲染序：经典 → 机甲 → 撞色 → 传统 → 终端 → 情绪（groupedPresets 经 groupBy 保序投影）
        val presets: List<AppThemeSpec> by lazy {
            listOf(
                Default, Aurum,
                ArmoryGreen, ElectricViolet, LavaCrimson, TricolorBlueGold, CrimsonGold,
                SkyWheat, VermilionPaper,
                Qinghua, Gulal, CheckerEmerald,
                DraculaNeon, GruvboxWarmPaper, NordFrost,
                Matrix, Hazard, CyanNeon, MintTeal, CrimsonNight,
            )
        }

        /** 分组渲染数据源（防 UI 与核心清单漂移）：组序/组内序均 = presets 列表序 */
        val groupedPresets: List<Pair<PresetGroup, List<AppThemeSpec>>> by lazy {
            presets.groupBy { it.group }.map { (group, specs) -> group to specs }
        }

        // 被删/改名预设持久化 id 的读取面映射：存量 color_scheme 读到旧 id 时落同色相族
        // 最近保留档（映射依据 = 实测浅 primary 距离）；改名套旧 id 直通同名新档。
        // 纯读取面——DataStore 旧值不清洗，用户手动切换即写入新 id、映射自然失效；
        // 未知 id 仍回 Default。
        // 🔴 必须 by lazy：急切初始化会在 <clinit> 期引用 data object 的 INSTANCE——
        // 当 JVM 经由子类（如直读 Default）先触发本类初始化时，map 构造嵌在父类 clinit 内、
        // 而子类 clinit 尚未走到 INSTANCE 赋值，递归读取拿到 null 即 NPE（presets/groupedPresets
        // 同理不得去掉 lazy）。
        private val LEGACY_PRESET_MAP: Map<String, String> by lazy {
            mapOf(
                "Ocean" to Qinghua.id,
                "Sunset" to Hazard.id,
                "Violet" to Default.id,
                "Rose" to Gulal.id,
                "Teal" to MintTeal.id,
                "Amber" to Aurum.id,
                "Forest" to Matrix.id,
                "Slate" to NordFrost.id,
                "abyss-glow" to MintTeal.id,
                // 名称中性化改名的旧 id：直通同名新档
                "armory-green" to ArmoryGreen.id,
                "electric-violet" to ElectricViolet.id,
                "lava-crimson" to LavaCrimson.id,
                "tricolor-blue-gold" to TricolorBlueGold.id,
                "crimson-gold" to CrimsonGold.id,
                "sky-wheat" to SkyWheat.id,
                "vermilion-paper" to VermilionPaper.id,
                "checker-emerald" to CheckerEmerald.id,
                "cyan-neon" to CyanNeon.id,
                "mint-teal" to MintTeal.id,
                "crimson-night" to CrimsonNight.id,
            )
        }

        fun fromId(id: String, customSeed: Color? = null): AppThemeSpec {
            if (id == "Custom" && customSeed != null) return Custom(customSeed)
            return presets.firstOrNull { it.id == id }
                ?: LEGACY_PRESET_MAP[id]?.let { legacy -> presets.firstOrNull { it.id == legacy } }
                ?: Default
        }
    }
}
