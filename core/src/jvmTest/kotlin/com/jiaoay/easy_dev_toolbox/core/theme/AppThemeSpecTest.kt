package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AppThemeSpec 预设/恢复契约断言：
 * - 预设清单 = 6 组 20 套（经典 2 / 机甲 5 / 撞色 2 / 传统 3 / 终端 3 / 情绪 5），增删即失败
 * - 被删/改名预设的持久化 id 经 LEGACY_PRESET_MAP 读面映射到同色相族最近保留档（或同名新档）
 *   ——存量 color_scheme 优雅回退，未知 id 仍回 Default
 * - Default 档契约钉死：seed 0xFF4F55BA + style = Fidelity + 无覆写（零漂移锚）——
 *   派生 primary 与 ia-v2 mock #4F55BA 同色相同色度档（ΔE76=10.4）
 * - fromId 往返（设置页能存必须能恢复——「能存不能恢复」是新预设的经典事故面）
 * - 保色相档（Fidelity/Vibrant）不丢身份色相（hue-keep 双腿：种子带主腿 + 夜相骨架同族
 *   替代腿）：primary 相 ≈ seed 相（与 pageBase 存在性解耦），底相挂主腿或替代腿
 * - 恒紫可区分性双通道：常规线 min(ΔE76(p/s/t, aiAccent)) ≥ 30.0，
 *   不过线者必须走「异族强调救援」（ΔE76 ≥ 40 ∧ HCT 色相距 ≥ 40° ∧ 骨架染色 max 色度 ≥ 10）
 *   ——同族明度差冒充分离被色相距门拦死（紫罗兰先例：同族紫 ΔE76 31.5 仍判不可区分而删除）
 */
class AppThemeSpecTest {

    @Test
    fun `presets list is six grouped sets of twenty presets in order`() {
        val ids = AppThemeSpec.presets.map { it.id }
        assertEquals(
            listOf(
                "Default", "Aurum",
                "armory-green", "electric-violet", "lava-crimson", "tricolor-blue-gold", "crimson-gold",
                "sky-wheat", "vermilion-paper",
                "qinghua-cobalt-white", "holi-gulal-colors", "checker-emald",
                "dracula-neon", "gruvbox-warm-paper", "nord-frost",
                "matrix-terminal", "hazard-orange", "cyan-neon", "mint-teal", "crimson-night",
            ),
            ids,
            "预设清单/顺序被改动——设置页分组色卡顺序与 docs/core/theme.md 均锚定此清单",
        )
        assertEquals(20, AppThemeSpec.presets.size, "预设数量应为 20（经典 2 + 机甲 5 + 撞色 2 + 传统 3 + 终端 3 + 情绪 5）")
    }

    @Test
    fun `preset styles pinned across catalog and expressive banned`() {
        // Default 契约：seed = ia-v2 紫调 0xFF4F55BA + style = Fidelity（柔和档）+ 零覆写（零漂移锚）
        assertEquals(PaletteStyle.Fidelity, AppThemeSpec.Default.style, "Default = Fidelity（柔和靛紫契约）")
        assertEquals(Color(0xFF4F55BA), AppThemeSpec.Default.seed, "Default seed = ia-v2 紫调 #4F55BA")
        assertNull(AppThemeSpec.Default.secondarySeed, "Default 第二种子必须为 null（零漂移锚）")
        assertNull(AppThemeSpec.Default.tertiarySeed, "Default 第三种子必须为 null（零漂移锚）")
        assertNull(AppThemeSpec.Default.skeletonStain, "Default 骨架染色必须为 null（零漂移锚，克制基线）")
        // 底色主权/覆写表同样属零漂移锚组成面：Default 不传参 = 派生管线逐位现行行为
        assertNull(AppThemeSpec.Default.pageBaseLight, "Default 底色主权参数必须为 null（零漂移锚）")
        assertNull(AppThemeSpec.Default.pageBaseDark, "Default 底色主权参数必须为 null（零漂移锚）")
        assertNull(AppThemeSpec.Default.lightOverrides, "Default 覆写表必须为 null（零漂移锚）")
        assertNull(AppThemeSpec.Default.darkOverrides, "Default 覆写表必须为 null（零漂移锚）")
        // 经典组其余档：Aurum 维持 Vibrant
        assertEquals(PaletteStyle.Vibrant, AppThemeSpec.Aurum.style)
        // 机甲组档位钉死：荧光/警示题材走 Vibrant，保色相题材走 Fidelity
        assertEquals(PaletteStyle.Vibrant, AppThemeSpec.ArmoryGreen.style)
        assertEquals(PaletteStyle.Vibrant, AppThemeSpec.ElectricViolet.style)
        assertEquals(PaletteStyle.Vibrant, AppThemeSpec.LavaCrimson.style)
        assertEquals(PaletteStyle.Fidelity, AppThemeSpec.TricolorBlueGold.style)
        assertEquals(PaletteStyle.Vibrant, AppThemeSpec.CrimsonGold.style)
        // 撞色组：高彩撞色转译走 Vibrant，极简墨底走 Fidelity
        assertEquals(PaletteStyle.Vibrant, AppThemeSpec.SkyWheat.style)
        assertEquals(PaletteStyle.Fidelity, AppThemeSpec.VermilionPaper.style)
        // 传统组：保色相题材走 Fidelity，顶格品红走 Vibrant
        assertEquals(PaletteStyle.Fidelity, AppThemeSpec.Qinghua.style)
        assertEquals(PaletteStyle.Vibrant, AppThemeSpec.Gulal.style)
        assertEquals(PaletteStyle.Fidelity, AppThemeSpec.CheckerEmerald.style)
        // 终端组：霓虹顶格走 Vibrant，官方关系保真走 Fidelity
        assertEquals(PaletteStyle.Vibrant, AppThemeSpec.DraculaNeon.style)
        assertEquals(PaletteStyle.Fidelity, AppThemeSpec.GruvboxWarmPaper.style)
        assertEquals(PaletteStyle.Fidelity, AppThemeSpec.NordFrost.style)
        // 情绪组：荧光/顶格题材走 Vibrant，pale/保相题材走 Fidelity
        assertEquals(PaletteStyle.Vibrant, AppThemeSpec.Matrix.style)
        assertEquals(PaletteStyle.Vibrant, AppThemeSpec.Hazard.style)
        assertEquals(PaletteStyle.Fidelity, AppThemeSpec.CyanNeon.style)
        assertEquals(PaletteStyle.Fidelity, AppThemeSpec.MintTeal.style)
        assertEquals(PaletteStyle.Fidelity, AppThemeSpec.CrimsonNight.style)
        // 🔴 Expressive 全目录禁用：primary = 种子相 +240° 旋相毁身份色（hue-keep 守护的机制防线）
        AppThemeSpec.presets.forEach { spec ->
            assertTrue(
                spec.style != PaletteStyle.Expressive,
                "${spec.id} 使用了 Expressive 档——旋相毁身份色，全目录禁用",
            )
        }
        // Custom 无覆写 → 跟随默认 TonalSpot（色轮无染色/多种子输入的既有口径）
        val custom = AppThemeSpec.Custom(Color(0xFF112233))
        assertEquals(PaletteStyle.TonalSpot, custom.style)
        assertNull(custom.secondarySeed, "Custom 第二种子必须为 null（色轮无多种子输入）")
        assertNull(custom.skeletonStain, "Custom 骨架染色必须为 null（色轮无染色输入）")
        assertNull(custom.pageBaseLight, "Custom 底色主权参数必须为 null（色轮无涂装输入）")
        assertNull(custom.pageBaseDark, "Custom 底色主权参数必须为 null（色轮无涂装输入）")
        assertNull(custom.lightOverrides, "Custom 覆写表必须为 null（色轮无涂装输入）")
        assertNull(custom.darkOverrides, "Custom 覆写表必须为 null（色轮无涂装输入）")
    }

    @Test
    fun `dual seed and skeleton stain contracts hold per preset`() {
        // tertiarySeed 缺省回落 secondarySeed（双种子套的 tertiary 家族与强调同族，
        // 避免 Fidelity 互补副作用的随机窑变）；显式第三种子的只有 tricolor-blue-gold / crimson-gold
        assertEquals(AppThemeSpec.TricolorBlueGold.secondarySeed, Color(0xFFD13438), "tricolor-blue-gold 第二种子 = 警戒红")
        assertEquals(AppThemeSpec.TricolorBlueGold.tertiarySeed, Color(0xFFF2C230), "tricolor-blue-gold 第三种子 = V字金（三强调套）")
        assertEquals(AppThemeSpec.CrimsonGold.tertiarySeed, Color(0xFFC9A227), "猩红流金第三种子 = 流金")
        AppThemeSpec.presets.forEach { spec ->
            if (spec.tertiarySeed != null) {
                assertTrue(
                    spec.secondarySeed != null,
                    "${spec.id} 设了 tertiarySeed 却没有 secondarySeed——第三种子必须叠在双种子之上",
                )
            }
        }
        // 救援通道候选套（aiAccent 常规线不过的三个面）必须带染色且 max 色度 ≥ 10
        listOf(AppThemeSpec.ElectricViolet, AppThemeSpec.DraculaNeon).forEach { spec ->
            val stain = spec.skeletonStain
            assertTrue(stain != null, "${spec.id} 必须带骨架染色（救援通道骨架条件）")
            assertTrue(
                maxOf(stain!!.darkChroma, stain.lightChroma) >= 10.0,
                "${spec.id} 染色 max 色度不足 10——救援通道的骨架条件失效",
            )
        }
    }

    @Test
    fun `groupedPresets partitions presets losslessly in list order`() {
        val grouped = AppThemeSpec.groupedPresets
        assertEquals(
            AppThemeSpec.presets,
            grouped.flatMap { it.second },
            "分组必须无损覆盖全部预设（组内序 = presets 相对序，防设置页分组与核心清单漂移）",
        )
        assertEquals(
            listOf("经典", "机甲", "撞色", "传统", "终端", "情绪"),
            grouped.map { it.first.label },
            "组序/组标签被改动——设置页组标题直出此清单（组序 = presets 列表序）",
        )
    }

    @Test
    fun `fromId round-trips every preset id`() {
        AppThemeSpec.presets.forEach { spec ->
            val restored = AppThemeSpec.fromId(spec.id)
            assertEquals(spec, restored, "预设 id=${spec.id} 必须可 fromId 恢复（能存不能恢复 = 新预设经典事故）")
        }
        assertEquals(AppThemeSpec.Aurum.seed, Color(0xFFE8B84B), "Aurum seed 应为融合案 D 原型 --acc 原值")
    }

    @Test
    fun `fromId falls back to Default on unknown id and Custom requires seed`() {
        assertEquals(AppThemeSpec.Default, AppThemeSpec.fromId("NonExistent"))
        val custom = AppThemeSpec.fromId("Custom", customSeed = Color(0xFFABCDEF))
        assertIs<AppThemeSpec.Custom>(custom)
        assertEquals(Color(0xFFABCDEF), custom.seed)
        // Custom 无种子 → 回退 Default（与既有行为一致）
        assertEquals(AppThemeSpec.Default, AppThemeSpec.fromId("Custom"))
    }

    @Test
    fun `fromId maps removed legacy preset ids to nearest kept preset`() {
        // 迁移锚：被删 id 落同色相族最近保留档（映射依据 = 实测浅 primary 距离）——
        // 存量用户重启后的主题漂移被收敛为「最近同族」而非一律 Default
        assertEquals(AppThemeSpec.Qinghua, AppThemeSpec.fromId("Ocean"), "海洋→青花（钢蓝→钴蓝同族）")
        assertEquals(AppThemeSpec.Hazard, AppThemeSpec.fromId("Sunset"), "日落→警示橙（暖橙同族）")
        assertEquals(AppThemeSpec.Default, AppThemeSpec.fromId("Violet"), "紫罗兰→Default（紫最近档）")
        assertEquals(AppThemeSpec.Gulal, AppThemeSpec.fromId("Rose"), "蔷薇→洒红节（品红族）")
        assertEquals(AppThemeSpec.MintTeal, AppThemeSpec.fromId("Teal"), "青碧→薄荷青（青荧光域最近保留档）")
        assertEquals(AppThemeSpec.Aurum, AppThemeSpec.fromId("Amber"), "琥珀→鎏金（同琥珀族）")
        assertEquals(AppThemeSpec.Matrix, AppThemeSpec.fromId("Forest"), "森林→矩阵数字雨（同纸绿域主力）")
        assertEquals(AppThemeSpec.NordFrost, AppThemeSpec.fromId("Slate"), "石灰→Nord（映射契约「按浅 primary 距离复核」实测 nord-frost 最近 ΔE76 12.07，gruvbox 为暖纸异族）")
        assertEquals(AppThemeSpec.MintTeal, AppThemeSpec.fromId("abyss-glow"), "深海荧光→薄荷青（青荧光域近亲）")
    }

    @Test
    fun `hue keeping styles keep seed hue in primary across whole catalog and both modes`() {
        // 保色相档（Fidelity/Vibrant）的 hue-keep 双腿语义（替代腿 = OR 增腿 + 三闸，
        // 收紧语义非放松）：换档退到 Expressive 时色相被 +240°（紫种子翻绿、丢身份色）。
        // - 主腿（种子带）：底相/primary 相 vs 种子相 < 15°——机体本漆套的涂装身份，
        //   夜 = 同一块漆换光照；全目录扩面（20 套保色相档 × 2 态）；
        // - 替代腿（夜相骨架同族，仅暗态、三闸齐备）：tone < NIGHT_SCENE_TONE_MAX（夜幕侧
        //   最亮 dracula t17.3 与涂装侧最深 nord t21.6 的实测间隙）∧ chroma ≥
        //   NIGHT_FAMILY_CHROMA_MIN（与 ThemeDerivedContrastGuardTest 暗态 bg 色度地板
        //   同源同值）∧ 底相距在册染色暗档相 < STAIN_FAMILY_TOLERANCE（现有染色族最大
        //   偏移 2.4° 的 4 倍余量）——场景面套的涂装身份，夜 = 题材夜场，底与暗卡/井槽
        //   同吃染色相（底相 ≈ 染色参数）；
        // - primary 相检查与 pageBase 存在性解耦：pageBase 套该态 primary 未被覆写表直配时
        //   仍断言（堵「落主权后 Expressive 防线静默消失」——直配反白为低色度域 hue 无语义，
        //   仅未直配态断言）；
        // - 🔴 官方色板直采套例外（Dracula/Gruvbox）不可被替代腿吸收：底 = 官方色板原值、
        //   种子 = 官方强调色，「底从种子派生」与「底随染色」两前提均不成立（Dracula 底距
        //   种子 29.2°/距自身染色暗档相 15.2°，双腿皆不过）——身份锚改挂 paint sovereignty
        //   注册面 hex 快照（比 hue 距更强的钉法，非放松）。
        val officialPaletteDirect = setOf(AppThemeSpec.DraculaNeon, AppThemeSpec.GruvboxWarmPaper)
        val hueKeep = AppThemeSpec.presets.filter {
            it.style == PaletteStyle.Fidelity || it.style == PaletteStyle.Vibrant
        }
        assertEquals(20, hueKeep.size, "保色相档应为 20 套（Fidelity 10 + Vibrant 10）——目录增删后未同步则修正此锚")
        hueKeep.forEach { spec ->
            val seedHue = hueOf(spec.seed)
            listOf(false, true).forEach { dark ->
                val pageBase = if (dark) spec.pageBaseDark else spec.pageBaseLight
                if (pageBase != null && spec in officialPaletteDirect) return@forEach
                // primary 相检查（与 pageBase 存在性解耦；覆写表直配态 hue 无语义，跳过）
                if (spec.slotOverrides(dark)?.primary == null) {
                    val primaryHue = hueOf(spec.colorSet(dark).primary)
                    assertTrue(
                        hueDistance(seedHue, primaryHue) < 15.0,
                        "${spec.id} ${if (dark) "dark" else "light"} primary hue $primaryHue 偏离 seed hue $seedHue" +
                            "——身份色相被旋转（档位必须是保色相档 Fidelity/Vibrant 而非 Expressive）",
                    )
                }
                if (pageBase != null) {
                    val baseHct = Hct.fromInt(pageBase.toArgb())
                    val seedBand = hueDistance(seedHue, baseHct.hue) < 15.0
                    val stain = spec.skeletonStain
                    val nightStainFamily = dark &&
                        baseHct.tone < NIGHT_SCENE_TONE_MAX &&
                        baseHct.chroma >= NIGHT_FAMILY_CHROMA_MIN &&
                        stain != null &&
                        hueDistance(stain.darkHue, baseHct.hue) < STAIN_FAMILY_TOLERANCE
                    assertTrue(
                        seedBand || nightStainFamily,
                        "${spec.id} ${if (dark) "dark" else "light"} pageBase 底相偏离种子带且不构成夜相骨架同族" +
                            "——须回种子带或改在册染色参数使整副夜骨架随动并过全目录重算；禁单点改值或动阈值",
                    )
                }
            }
        }
    }

    @Test
    fun `aiAccent stays distinguishable via regular line or exotic rescue channel`() {
        // 常规线：派生 primary/secondary/tertiary 对 aiAccent 的最近 ΔE76 ≥ 30.0
        //（线源 = 紫罗兰深色 primary 31.5 的同法实测 31.15 + 0.4 算法余量）。
        // 救援通道（OR 关系，非豁免）：∃ secondary/tertiary 满足
        //   ΔE76 ≥ 40.0 ∧ HCT 色相距 ≥ 40° ∧ skeletonStain ≠ null ∧ max(暗,亮 染色色度) ≥ 10。
        // 阈值依据：救援通道是唯一分离通道需更高余量（40 = 1.33× 常规 30 线，最窄 margin +6.3）；
        // 色相距 ≥40° 防「同族明度差冒充分离」（紫罗兰先例：同族紫 ΔE76 31.5 仍判不可区分而删除）；
        // 骨架 max 色度 ≥10 = 「深色骨架+异族强调整体差异」的可测化（有性格档以上）。
        // aiAccent 读消费面实色（套级覆写合并后）——覆写套以直配值为断言对象
        // （静态恒紫常量只是未覆写套的回落值），救援判据结构不变。
        AppThemeSpec.presets.forEach { spec ->
            listOf(false, true).forEach { dark ->
                val ai = (if (dark) DarkEasyToolboxColors else LightEasyToolboxColors)
                    .withSlotOverrides(spec.slotOverrides(dark))
                    .aiAccent
                val scheme = spec.colorSet(dark)
                val minDelta = listOf(scheme.primary, scheme.secondary, scheme.tertiary)
                    .minOf { c -> deltaE76(c, ai) }
                if (minDelta >= 30.0) return@forEach
                val stain = spec.skeletonStain
                val rescued = stain != null &&
                    maxOf(stain.darkChroma, stain.lightChroma) >= 10.0 &&
                    listOf(scheme.secondary, scheme.tertiary).any { c ->
                        deltaE76(c, ai) >= 40.0 && hueDistance(hueOf(c), hueOf(ai)) >= 40.0
                    }
                assertTrue(
                    rescued,
                    "${spec.id} ${if (dark) "dark" else "light"} 派生色对 aiAccent 最近 ΔE76=$minDelta " +
                        "低于 30.0 常规线，且不满足异族强调救援（ΔE76≥40 ∧ 色相距≥40° ∧ 染色 max 色度≥10）" +
                        "——AI 语义色被主题撞掉",
                )
            }
        }
    }

    @Test
    fun `paint sovereignty stays within the registered spec set`() {
        // 底色主权/覆写表的目录级注册制：机制是全目录泛化的（G13 域拆/G14 双轨退出
        // 均按参数存在性判定），启用面 = 已过全目录守护重算的套集——electric-violet
        //（电光紫 涂装）+ 终端组/机体红族/晴空麦浪 六套（暗态涂装）+ 轻涂装化六套
        //（青花/Aurum/Matrix/青白霓虹/薄荷青/绯红夜城）
        // + 夜相主权五套（Aurum/青花暗底直采同值、市松翠格 市松夜/警示橙夜场提 tone、
        // 洒红节粉雾纸降 tone，出处 docs/core/theme.md「配色裁定演变」）。
        // 任一套意外传参会静默退出 G14 亮态双轨断言块（窗外即红前提失效），此断言即那条
        // 静默退出的闸门；新增传参套必须先过全目录重算（G9-G16 全绿）再把 id 与值快照
        // 加入本注册面。hue-keep 准入条件（双腿语义）：底相要么在种子带（距种子相 <15°，
        // 机体本漆），要么过夜相替代腿三闸（仅暗态：tone<20 ∧ chroma≥10 ∧ 底相距在册
        // 染色暗档相<10°，场景面夜场）——双腿皆不过者（官方色板直采套）由本注册面 hex
        // 快照单独钉死。骨架染色参数随本注册面逐值快照：stain 变更 = 整副夜/昼骨架随动
        // = 身份变更，须重过全目录守护重算后再改快照锚。
        val registered = setOf(
            AppThemeSpec.ElectricViolet,
            AppThemeSpec.DraculaNeon, AppThemeSpec.GruvboxWarmPaper, AppThemeSpec.NordFrost,
            AppThemeSpec.LavaCrimson, AppThemeSpec.CrimsonGold,
            AppThemeSpec.SkyWheat,
            AppThemeSpec.TricolorBlueGold, AppThemeSpec.ArmoryGreen,
            AppThemeSpec.Qinghua, AppThemeSpec.Aurum,
            AppThemeSpec.Matrix, AppThemeSpec.CyanNeon,
            AppThemeSpec.MintTeal, AppThemeSpec.CrimsonNight,
            AppThemeSpec.CheckerEmerald, AppThemeSpec.Gulal, AppThemeSpec.Hazard,
        )
        AppThemeSpec.presets.forEach { spec ->
            if (spec in registered) return@forEach
            assertNull(spec.pageBaseLight, "${spec.id} 意外声明 pageBaseLight——底色主权启用须经全目录守护重算，非单点加参")
            assertNull(spec.pageBaseDark, "${spec.id} 意外声明 pageBaseDark")
            assertNull(spec.lightOverrides, "${spec.id} 意外声明 lightOverrides")
            assertNull(spec.darkOverrides, "${spec.id} 意外声明 darkOverrides")
        }
        // 注册套的底色值快照（官方直采/涂装本漆/夜相本漆锚——改值 = 换涂装身份，须重新过全目录重算）
        assertEquals(Color(0xFF9946FA), AppThemeSpec.ElectricViolet.pageBaseLight, "电光紫 昼战底 = 横幅本漆 #9946FA")
        assertEquals(Color(0xFF5940A6), AppThemeSpec.ElectricViolet.pageBaseDark, "电光紫 夜战底 = 装甲本漆 #5940A6")
        assertEquals(Color(0xFF282A36), AppThemeSpec.DraculaNeon.pageBaseDark, "Dracula 暗底 = 官方 bg #282A36")
        assertEquals(Color(0xFFFBF1C7), AppThemeSpec.GruvboxWarmPaper.pageBaseLight, "Gruvbox 亮底 = 官方 light0 #FBF1C7")
        assertNull(AppThemeSpec.GruvboxWarmPaper.pageBaseDark, "Gruvbox 暗态不传参（官方中性暗底过不了色度地板，退单亮态）")
        assertEquals(Color(0xFFE5E9F0), AppThemeSpec.NordFrost.pageBaseLight, "Nord 亮底 = 官方 nord5 #E5E9F0")
        assertEquals(Color(0xFF2E3440), AppThemeSpec.NordFrost.pageBaseDark, "Nord 暗底 = 官方 nord0 #2E3440")
        assertEquals(Color(0xFFDB3A45), AppThemeSpec.LavaCrimson.pageBaseLight, "熔岩红 昼底 = 装甲红本漆 #DB3A45")
        assertEquals(Color(0xFF6E1526), AppThemeSpec.LavaCrimson.pageBaseDark, "熔岩红 夜底 = 兽化红黑 #6E1526")
        assertEquals(Color(0xFF8C2338), AppThemeSpec.CrimsonGold.pageBaseDark, "猩红流金 夜底 = 高速红漆深档 #8C2338")
        assertEquals(Color(0xFF0057B7), AppThemeSpec.SkyWheat.pageBaseDark, "晴空麦浪 夜底 = 晴空蓝本漆 #0057B7")
        assertEquals(Color(0xFF141400), AppThemeSpec.ArmoryGreen.pageBaseDark, "ArmoryGreen 夜底 = 军械绿黑 #141400（染色派生同值直采，夜相主权换轨零漂移）")
        assertEquals(Color(0xFF0A1A17), AppThemeSpec.MintTeal.pageBaseDark, "薄荷青 夜底 = 黑舞台青绿相暗黑 #0A1A17（亮态零漂移：pageBaseLight 恒 null）")
        assertNull(AppThemeSpec.MintTeal.pageBaseLight, "薄荷青 亮态不传参（真中性灰纸走 97.9 锚轨，昼夜身份分治）")
        assertEquals(Color(0xFF1F0F10), AppThemeSpec.CrimsonNight.pageBaseDark, "绯红夜城 夜底 = 夜幕派绯红相黑底 #1F0F10（亮态冷蓝纸不动）")
        assertNull(AppThemeSpec.CrimsonNight.pageBaseLight, "绯红夜城 亮态不传参（冷蓝纸异族骨架走重采样轨）")
        assertEquals(Color(0xFF1A1206), AppThemeSpec.Aurum.pageBaseDark, "鎏金夜底 = 深空暖黑 #1A1206（染色派生同值直采，夜相主权换轨零漂移）")
        assertEquals(Color(0xFF061423), AppThemeSpec.Qinghua.pageBaseDark, "青花夜底 = 深瓷底钴蓝 #061423（染色派生同值直采，夜相主权换轨零漂移）")
        assertEquals(Color(0xFF1A271D), AppThemeSpec.CheckerEmerald.pageBaseDark, "市松翠格 夜底 = 市松夜墨绿黑提 tone #1A271D")
        assertEquals(Color(0xFF30170D), AppThemeSpec.Hazard.pageBaseDark, "警示橙夜底 = 黑黄警示夜场暖黑 #30170D")
        assertEquals(Color(0xFF210C1C), AppThemeSpec.Gulal.pageBaseDark, "洒红节夜底 = 玫黑 #210C1C（染色派生同值直采，夜相主权换轨零漂移）")
        assertEquals(95.0, AppThemeSpec.Gulal.skeletonStain?.lightPageTone, "洒红节亮态粉雾纸 lightPageTone = 95.0（有效域下沿 94.5 的钉定套）")
        assertEquals(
            Color(0xFFF9E6F1), AppThemeSpec.Gulal.lightOverrides?.surfaceContainerLow,
            "洒红节亮卡 Low = 粉雾浓淡阶 #F9E6F1（纸面加深后卡沉于底随动）",
        )
        // 骨架染色参数逐值快照（在册全集）：任一参数变更即红——stain 变更 = 身份变更 =
        // 强制全目录重算（hue-keep 三闸/色度地板/对比度/可区分性全绿后再改此锚）
        assertStainSnapshot("电光紫", AppThemeSpec.ElectricViolet, 255.0, 18.0, 24.0, 268.0, 8.0, 12.0, 97.3)
        assertStainSnapshot("Dracula", AppThemeSpec.DraculaNeon, 292.0, 14.0, 20.0, 280.0, 7.0, 10.0, 97.0)
        assertStainSnapshot("Gruvbox", AppThemeSpec.GruvboxWarmPaper, 55.0, 10.0, 14.0, 65.0, 12.0, 16.0, 96.5)
        assertStainSnapshot("Nord", AppThemeSpec.NordFrost, 262.0, 12.0, 16.0, 255.0, 12.0, 16.0, 97.4)
        assertStainSnapshot("熔岩红", AppThemeSpec.LavaCrimson, 0.0, 16.0, 22.0, 8.0, 7.0, 10.0, 96.5)
        assertStainSnapshot("猩红流金", AppThemeSpec.CrimsonGold, 338.0, 16.0, 22.0, 350.0, 6.0, 9.0, 96.5)
        assertStainSnapshot("晴空麦浪", AppThemeSpec.SkyWheat, 258.0, 11.0, 14.0, 95.0, 8.0, 12.0, 97.3)
        assertStainSnapshot("蓝金三色", AppThemeSpec.TricolorBlueGold, 240.0, 6.0, 8.0, 238.0, 2.0, 3.0)
        assertStainSnapshot("ArmoryGreen", AppThemeSpec.ArmoryGreen, 110.0, 18.0, 26.0, 110.0, 20.0, 26.0)
        assertStainSnapshot("青花", AppThemeSpec.Qinghua, 255.0, 17.0, 21.0, 250.0, 7.0, 10.0, 96.8)
        assertStainSnapshot("鎏金", AppThemeSpec.Aurum, 80.0, 10.0, 14.0, 75.0, 6.0, 10.0, 96.5)
        assertStainSnapshot("Matrix", AppThemeSpec.Matrix, 145.0, 14.0, 20.0, 145.0, 11.0, 14.0)
        assertStainSnapshot("青白霓虹", AppThemeSpec.CyanNeon, 246.0, 14.0, 20.0, 240.0, 7.0, 10.0, 97.4)
        assertStainSnapshot("薄荷青", AppThemeSpec.MintTeal, 183.0, 10.0, 14.0, 225.0, 2.0, 3.0)
        assertStainSnapshot("绯红夜城", AppThemeSpec.CrimsonNight, 14.0, 12.0, 16.0, 252.0, 14.0, 18.0, 96.5)
        assertStainSnapshot("市松翠格", AppThemeSpec.CheckerEmerald, 158.0, 12.0, 18.0, 158.0, 9.0, 12.0)
        assertStainSnapshot("洒红节", AppThemeSpec.Gulal, 340.0, 20.0, 30.0, 335.0, 10.0, 16.0, 95.0)
        assertStainSnapshot("警示橙", AppThemeSpec.Hazard, 40.0, 18.0, 26.0, 38.0, 8.0, 12.0, 96.5)
    }

    @Test
    fun `surface pattern slot stays within the registered pattern set`() {
        // 图案槽申请制注册面（同 paint sovereignty 注册面语义）：登记表 = 套 → 图案型快照，
        // 任一套传参不在表内即红（未过全目录守护重算的静默扩面被此闸拦死）。图案槽不携带
        // 色值——双色由采样单源（ThemeSpecimen.specimenPatternSample）从该套既有槽色取，
        // 周期/对比密度在绘制位固定，套级只选型；消费位封闭集 = 设置页样张 base 块
        // （composeApp 源码锚守护 SpecimenPatternConsumersGuardTest 扫白名单）。
        val registry = mapOf(
            AppThemeSpec.CheckerEmerald to ThemeSurfacePattern.Checkerboard,
            AppThemeSpec.Hazard to ThemeSurfacePattern.HazardStripe,
        )
        AppThemeSpec.presets.forEach { spec ->
            listOf(false, true).forEach { dark ->
                val pattern = spec.slotOverrides(dark)?.surfacePattern ?: return@forEach
                assertTrue(
                    spec in registry,
                    "${spec.id} dark=$dark 传了图案槽 $pattern 却不在注册面——申请制：先过全目录守护重算再入表",
                )
                assertEquals(
                    registry[spec], pattern,
                    "${spec.id} 图案型与注册面快照不符（改型 = 展示身份变更，须重过守护重算）",
                )
            }
        }
        // 注册套逐套快照（哪套传什么枚举；只登记暗态——两套的图案即夜场身份）
        assertEquals(
            ThemeSurfacePattern.Checkerboard,
            AppThemeSpec.CheckerEmerald.darkOverrides?.surfacePattern,
            "市松翠格 暗态图案 = Checkerboard（市松格纹，双色取暗卡浮起阶首末两档）",
        )
        assertNull(
            AppThemeSpec.CheckerEmerald.lightOverrides?.surfacePattern,
            "市松翠格 亮态不传图案（亮态翠纸走派生样张）",
        )
        assertEquals(
            ThemeSurfacePattern.HazardStripe,
            AppThemeSpec.Hazard.darkOverrides?.surfacePattern,
            "警示橙暗态图案 = HazardStripe（黑黄斜纹，双色取 warning 黄 + 警示黑底）",
        )
        assertNull(
            AppThemeSpec.Hazard.lightOverrides?.surfacePattern,
            "警示橙亮态不传图案（亮态暖纸走派生样张）",
        )
    }

    @Test
    fun `default fidelity derived roles snapshot`() {
        // Default Fidelity 派生值锚：供 token 对拍与 docs 引用。
        // 派生链 materialkolor dynamicColorScheme(#4F55BA, Fidelity) + 浅色基底 tone 97.9 校正
        // （primary/tertiary 不在基底校正面，派生值不受影响）。
        val light = AppThemeSpec.Default.colorSet(dark = false)
        val dark = AppThemeSpec.Default.colorSet(dark = true)
        assertEquals(
            "#363CA1", "#%06X".format(light.primary.toArgb() and 0xFFFFFF),
            "浅色 primary 派生锚（ia-v2 同色相族柔和靛紫，ΔE76≈10.4 于 mock #4F55BA）",
        )
        assertEquals(
            "#6F3C00", "#%06X".format(light.tertiary.toArgb() and 0xFFFFFF),
            "浅色 tertiary 派生锚——Fidelity 副作用：亮态亦翻深棕橙（非薰衣草紫）",
        )
        assertEquals(
            "#FFB778", "#%06X".format(dark.tertiary.toArgb() and 0xFFFFFF),
            "暗色 tertiary 派生锚——Fidelity 副作用：由薰衣草紫翻橙",
        )
    }

    @Test
    fun `semantic success warning slots match design language section 2 4 ruling`() {
        // design-language §2.4 契约快照锚——
        // 亮侧 = ia-v2 新值 #1B6C4A/#8A5300；暗侧契约「沿用现状」= #81C784/#FFC107。
        // 快照双向钉死：改亮值须过 §2.4 重裁决，改暗值须同步 §2.4 表——防两态口径再漂移。
        assertEquals(
            Color(0xFF1B6C4A), LightEasyToolboxColors.success,
            "浅色 success 必须 = §2.4 亮值 #1B6C4A（ia-v2 契约）",
        )
        assertEquals(
            Color(0xFF8A5300), LightEasyToolboxColors.warning,
            "浅色 warning 必须 = §2.4 亮值 #8A5300（ia-v2 契约）",
        )
        assertEquals(
            Color(0xFF81C784), DarkEasyToolboxColors.success,
            "暗色 success 沿用现状 #81C784（§2.4 表「暗沿用现状」——改值须同步 §2.4）",
        )
        assertEquals(
            Color(0xFFFFC107), DarkEasyToolboxColors.warning,
            "暗色 warning 沿用现状 #FFC107（§2.4 表「暗沿用现状」——改值须同步 §2.4）",
        )
    }

    @Test
    fun `electric violet green identity slots hold on both modes`() {
        // 绿占比判据（「选中面/激活件绿覆盖≈100%+绿可见性」）的值级锚：选中容器身份槽 selectedContainer
        // 与 AI 通电槽 aiAccent 两态恒绿 #09F955——组件消费位（选中 tab/段/卡/开关轨/芯片、
        // AI 角标）经 toolboxSurfaceColors 与 EasyToolboxColors 装配消费这两槽，槽漂移即
        // 判据失效。绿可见性（绿面对承载底 ≥3.0 / 配套字对绿面 ≥4.5）由
        // ThemeDerivedContrastGuardTest 选中容器接线对承载。
        listOf(false, true).forEach { dark ->
            val mode = if (dark) "暗" else "亮"
            val overrides = AppThemeSpec.ElectricViolet.slotOverrides(dark)
            val scheme = AppThemeSpec.ElectricViolet.colorSet(dark)
            val surfaces = toolboxSurfaceColors(scheme, dark, overrides)
            val toolboxColors = (if (dark) DarkEasyToolboxColors else LightEasyToolboxColors)
                .withSlotOverrides(overrides)
            assertEquals(
                Color(0xFF09F955), surfaces.selectedContainer,
                "电光紫 $mode 选中容器身份槽必须恒绿 #09F955（绿占比判据的选中面载体）",
            )
            assertEquals(
                Color(0xFF09F955), toolboxColors.aiAccent,
                "电光紫 $mode AI 通电槽必须恒绿 #09F955（绿占比判据的激活件载体）",
            )
        }
    }

    // ── 断言辅助（material-color-utilities Lab ΔE76）──

    /** HCT 色相（cam16 hue）——用 materialkolor Hct 直接取，避免自写转换 */
    private fun hueOf(color: Color): Double = Hct.fromInt(color.toArgb()).hue

    /** HCT 色相环距（0..180，防 350° vs 10° 被算成 340°） */
    private fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return minOf(d, 360.0 - d)
    }

    /**
     * 骨架染色参数逐值快照（paint sovereignty 注册面专用，dark/light 双态 3 参 + 亮相 tone）：
     * 任一参数变更即红——stain 变更 = 整副夜/昼骨架随动 = 身份变更
     */
    private fun assertStainSnapshot(
        label: String,
        spec: AppThemeSpec,
        darkHue: Double,
        darkChroma: Double,
        darkVariantChroma: Double,
        lightHue: Double,
        lightChroma: Double,
        lightVariantChroma: Double,
        lightPageTone: Double = 97.9,
    ) {
        val stain = spec.skeletonStain
        assertNotNull(stain, "$label 必须带骨架染色（注册面快照前提）")
        assertEquals(darkHue, stain.darkHue, "$label 染色暗档相被改动——身份变更须重过全目录守护重算")
        assertEquals(darkChroma, stain.darkChroma, "$label 染色暗档中性色度被改动——身份变更须重过全目录守护重算")
        assertEquals(darkVariantChroma, stain.darkVariantChroma, "$label 染色暗档 variant 色度被改动——身份变更须重过全目录守护重算")
        assertEquals(lightHue, stain.lightHue, "$label 染色亮档相被改动——身份变更须重过全目录守护重算")
        assertEquals(lightChroma, stain.lightChroma, "$label 染色亮档中性色度被改动——身份变更须重过全目录守护重算")
        assertEquals(lightVariantChroma, stain.lightVariantChroma, "$label 染色亮档 variant 色度被改动——身份变更须重过全目录守护重算")
        assertEquals(lightPageTone, stain.lightPageTone, "$label 亮相重采样目标 tone 被改动——身份变更须重过全目录守护重算")
    }

    private fun deltaE76(a: Color, b: Color): Double {
        val labA = com.materialkolor.utils.ColorUtils.labFromArgb(a.toArgb())
        val labB = com.materialkolor.utils.ColorUtils.labFromArgb(b.toArgb())
        val dl = labA[0] - labB[0]
        val da = labA[1] - labB[1]
        val db = labA[2] - labB[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    private companion object {
        /** 夜相替代腿 tone 闸上限：闸值落在夜幕侧最亮（dracula t17.3）与涂装侧最深（nord t21.6）的实测间隙 */
        const val NIGHT_SCENE_TONE_MAX = 20.0

        /**
         * 夜相替代腿色度闸下限：与 ThemeDerivedContrastGuardTest 的 DARK_CHROMA_FLOOR
         * 同源同值（非豁免暗 bg chroma 地板）——两处失配即守护失锚，禁单边改值
         */
        const val NIGHT_FAMILY_CHROMA_MIN = 10.0

        /** 夜相替代腿染色族相距容差：现有染色族最大偏移 2.4°（Aurum）的 4 倍余量 */
        const val STAIN_FAMILY_TOLERANCE = 10.0
    }
}
