package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 派生角色 AA 对比度 + tone 双轨阶梯 + 分带色度地板守护（SlotContrastGuardTest 只守静态
 * 语义槽对，本文件补齐 ColorScheme 派生面与 AppTheme 装配面的空白）：20 套 × 2 态逐套实算——
 *
 * - 结构带接线对：覆写 structureBand 的套断带上配套字链对带面 ≥4.5（文字 AA 抽样）；
 *   带对昼底 1.76 面档登记不进图形线断言面（消费位封闭集 = 侧栏轨/页签栏，由
 *   composeApp 源码锚守护「不上文字承载面」纪律）；
 * - 选中容器接线对：覆写 selectedContainer 的套按实测对比分档——对底 ≥3.0（图形档）
 *   断面 vs 底图形线；<3.0（面档——官方 selection 保真）跳过面断言，可辨性由配套字链
 *   承载（onSelectedContainer 对选中面 ≥4.5，全部覆写套生效）；底色主权套字链断言
 *   独立保留（回落链配套性）；
 * - 文字档 ≥ 4.5:1（AA 普通文本硬线）：onSurface × {background, cardLow, cardHigh, cardHighest}、
 *   onSurfaceVariant × {background, cardLow, cardHigh}、onSurfaceFaint（消费面实色，含套级覆写位）×
 *   {background, cardLow}；**底色主权套文字域拆分**（套级底色参数存在即生效，非套名特判）：
 *   onSurface 族改挂基域对（background/surface）、卡域由 onCard 三档承载（无覆写回落
 *   onSurface 族，断言面不扩）——井槽（Lowest）按分家值判据分流：Lowest ≠ 页面底（分家）
 *   归卡域断言（chrome/sheet/thin 玻璃带落深井域，带面文字消费位走 onCard 浅字链）；
 *   Lowest = 底直铺（未分家）留基域断言（亮漆面基域深字承载）；
 * - 图形档 ≥ 3.0:1（WCAG 非文本图形线）：焦点线断言对象 = borderEmphasis 消费面实效值
 *   （outline 承载；装饰缝套 outline 对底 <3.0 由派生位亮线救援升 onSurface）× background、
 *   primary × {background, cardLow}、aiAccent × {background, cardLow}（消费面实色）、
 *   platingGreen × background（暗态）；
 * - tone 阶梯（AppThemeSpec 防回退注释的数值化，浅色双轨）：**97.9 锚轨**（中性豁免套——
 *   Default 零漂移锚 / 朱砂宣纸 近零染色 /
 *   薄荷青 真中性灰规格 / 蓝金三色 近无相白甲，克制即身份的对照系；以及未降 tone 的非豁免停留组）
 *   |tone(bg)−97.9| ≤ 0.5 逐位锚死（量化回读可到 97.97，锚容差覆盖）；**重采样轨**
 *   （SkeletonStain.lightPageTone < 97.9 的非豁免染色套）tone(bg) ∈ [94.5, 97.9]
 *   （下沿 = 全目录最深彩纸需求套钉定：洒红节粉雾纸 95.0 直采 − 量化回读容差），
 *   降 tone 换色域宽度是骨架解钳的唯一机制；
 *   两轨均 bg > Low > High > Highest 严格降序（「卡沉于底」，95.5 系回填会反转它）；暗态
 *   bg < Low < High < Highest 严格升序（「卡浮起」）——M3 tone 表钉死的结构不变量；
 * - 亮态 tone 双轨（97.9 锚轨 / 重采样窗）只约束无底色参数套；套级底色参数套整体退出
 *   双轨断言块（底色已直采，窗外即红前提不成立），结构自洽由阶梯方向 + 分带地板 + AA 承载；
 * - 分带色度地板（全部非豁免套浅 bg，含底色主权套）：按 bg 实测 HCT 色相带查表（暖红 3.5 / 暖橙 3.0 /
 *   暖黄 3.5 / 黄绿 5.0 / 绿 8.0 / 青 8.0 / 蓝紫 6.0 / 品红 4.0）——重采样解钳的落地验收，
 *   防非豁免套被钳回无相近白纸；豁免套不参与（近零染色即其身份）；
 *   暗态对应物 = 非豁免暗 bg chroma ≥ 10.0（M3 暗 tone6 色域上限充裕，参数保守才粘连）；
 * - 接线 consoleBg AA：AppTheme 装配 consoleBg = resolvedConsoleBg(spec, dark)
 *   （套级直配 > 暗态染色派生 > 静态基值）后，consoleText/consoleSuccess/consoleInfo/
 *   platingGreen 恒深值对解析底 ≥ 4.5:1，双态全目录断言；
 * - chartPalette 覆写面：有覆写就校验 8 档对实际承载面（亮 Low 卡 / 暗 bg）≥ 3:1。
 *
 * WCAG 公式与 [SlotContrastGuardTest] 同款（sRGB 线性化相对亮度）。
 */
class ThemeDerivedContrastGuardTest {

    @Test
    fun `derived text and graphic roles meet contrast floors for every preset and mode`() {
        val failures = mutableListOf<String>()
        AppThemeSpec.presets.forEach { spec ->
            listOf(false, true).forEach { dark ->
                val mode = if (dark) "暗" else "亮"
                val scheme = spec.colorSet(dark)
                val overrides = spec.slotOverrides(dark)
                // aiAccent/platingGreen 读消费面实色（覆写合并后）——覆写套守护直配值，
                // 无覆写套即静态恒定值，行为逐位不变
                val toolboxColors = (if (dark) DarkEasyToolboxColors else LightEasyToolboxColors)
                    .withSlotOverrides(overrides)
                val surfaces = toolboxSurfaceColors(scheme, dark, overrides)
                val pageBase = if (dark) spec.pageBaseDark else spec.pageBaseLight
                if (pageBase != null) {
                    // 底色主权套（文字域拆分）：页面底与卡阶梯明度域可把单色文字档挤出 AA
                    // 窗口——onSurface 族断言对挂基域（bg/surface），卡域由 onCard 三档承载
                    // （无覆写回落 onSurface 族）。井槽（Lowest）按分家值判据分流：井槽分家
                    // （Lowest ≠ 页面底——chrome/sheet/thin 玻璃带落深井域，带面文字消费位走
                    // onCard 浅字链）→ Lowest 归卡域断言；未分家（Lowest = 底直铺——亮漆面上
                    // 基域深字承载，浅字卡域链反而破 4.5）→ Lowest 留基域断言
                    val wellSplit = scheme.surfaceContainerLowest != scheme.background
                    val baseDomains = buildList {
                        add("background" to scheme.background)
                        add("surface" to scheme.surface)
                        if (!wellSplit) add("cardLowest" to scheme.surfaceContainerLowest)
                    }
                    for (container in baseDomains) {
                        check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onSurface/${container.first}", scheme.onSurface, container.second)
                        check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onSurfaceVariant/${container.first}", scheme.onSurfaceVariant, container.second)
                        check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onSurfaceFaint/${container.first}", surfaces.onSurfaceFaint, container.second)
                    }
                    val cardDomains = buildList {
                        if (wellSplit) add("cardLowest" to scheme.surfaceContainerLowest)
                        add("cardLow" to scheme.surfaceContainerLow)
                        add("cardHigh" to scheme.surfaceContainerHigh)
                        add("cardHighest" to scheme.surfaceContainerHighest)
                    }
                    for (container in cardDomains) {
                        check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onCardStrong/${container.first}", surfaces.onCardStrong, container.second)
                        check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onCardMid/${container.first}", surfaces.onCardMid, container.second)
                        check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onCardFaint/${container.first}", surfaces.onCardFaint, container.second)
                    }
                } else {
                    // 文字档 ≥ 4.5:1（onSurfaceFaint 走消费面实色——含套级覆写位）
                    for (container in listOf(
                        "background" to scheme.background,
                        "cardLow" to scheme.surfaceContainerLow,
                        "cardHigh" to scheme.surfaceContainerHigh,
                        "cardHighest" to scheme.surfaceContainerHighest,
                    )) {
                        check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onSurface/${container.first}", scheme.onSurface, container.second)
                    }
                    for (container in listOf(
                        "background" to scheme.background,
                        "cardLow" to scheme.surfaceContainerLow,
                        "cardHigh" to scheme.surfaceContainerHigh,
                    )) {
                        check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onSurfaceVariant/${container.first}", scheme.onSurfaceVariant, container.second)
                    }
                    for (container in listOf(
                        "background" to scheme.background,
                        "cardLow" to scheme.surfaceContainerLow,
                    )) {
                        check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onSurfaceFaint/${container.first}", surfaces.onSurfaceFaint, container.second)
                    }
                }
                // 图形档 ≥ 3.0:1（断言本体不动；aiAccent 读消费面实色）。
                // 焦点线断言对象 = borderEmphasis 消费面实效值（outline 承载；装饰缝套
                // ——outline 对底 <3.0——由 toolboxSurfaceColors 亮线救援升 onSurface，
                // 缝不承担图形可辨职责，断言跟消费位走）
                check(failures, GRAPHIC_AA, "%.2f", "${spec.id}[$mode] borderEmphasis/background", surfaces.borderEmphasis, scheme.background)
                for (container in listOf(
                    "background" to scheme.background,
                    "cardLow" to scheme.surfaceContainerLow,
                )) {
                    check(failures, GRAPHIC_AA, "%.2f", "${spec.id}[$mode] primary/${container.first}", scheme.primary, container.second)
                    check(failures, GRAPHIC_AA, "%.2f", "${spec.id}[$mode] aiAccent/${container.first}", toolboxColors.aiAccent, container.second)
                }
                // 选中容器接线对（绿平衡批随动 + 前景声部常驻化分档）：组件选中面统一取
                // selectedContainer 消费位（开关 on/导航选中/段选/卡选），选中面按实测对比
                // 自动分档（域拆纪律非套名特判）：
                // - 图形档（对底 ≥3.0——电光紫 绿 3.19/晴空麦浪 黄 4.91/军械绿 粉 3.07 等）：
                //   面自身可辨性承载，面 vs 底图形线断言保留；
                // - 面档（对底 <3.0——Dracula 官方 selection #44475A 1.56 保真）：面断言
                //   物理不成立，可辨性由选中面配套字链承载（onSelectedContainer 消费面
                //   实色——覆写 onSecondary/onSecondaryContainer 借道、其余回落 onSurface），
                //   字链 ≥4.5 对全部覆写套生效（比 3.0 面线更硬的替代承载，非放松）；
                // - 底色主权套的字链断言独立保留（无覆写套的回落链配套性——夜战绿面上
                //   白字取色坑的机械化拦截）。
                val selectedSurface = surfaces.selectedContainer
                if (overrides?.selectedContainer != null) {
                    if (contrast(selectedSurface, scheme.background) >= GRAPHIC_AA) {
                        check(failures, GRAPHIC_AA, "%.2f", "${spec.id}[$mode] selectedContainer/background", selectedSurface, scheme.background)
                    }
                    check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onSelectedContainer/selectedContainer", surfaces.onSelectedContainer, selectedSurface)
                }
                if (pageBase != null) {
                    check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onSelectedContainer/selectedContainer", surfaces.onSelectedContainer, selectedSurface)
                }
                // 结构带接线对（纪律 2 重裁批）：覆写 structureBand 的套——带上配套字链
                // （onStructureBand 消费面实色）对带面 ≥4.5（带上文字/图标 AA 抽样）。
                // 🔴 面档登记：带（电光紫 装甲骨架带=井槽同值）对昼底 2.81 不过图形线，
                // 不进图形线断言面——可辨性由明度异域（暗带 vs 亮底）+ 封闭消费位承载
                // （侧栏轨/页签栏两条常驻带，带上不放图形件/正文——消费位封闭集由
                // composeApp 源码锚守护）。
                if (overrides?.structureBand != null) {
                    check(failures, TEXT_AA, "%.2f", "${spec.id}[$mode] onStructureBand/structureBand", surfaces.onStructureBand, surfaces.structureBand)
                }
                // platingGreen 暗底图形档（稳态指示消费位承载面；亮底不进断言面——
                // 结构件绿对浅色页面底不过图形线，消费位限深井/芯片内是使用纪律）
                if (dark) {
                    check(failures, GRAPHIC_AA, "%.2f", "${spec.id}[$mode] platingGreen/background", toolboxColors.platingGreen, scheme.background)
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("派生角色对比度 FAIL（文字 ≥$TEXT_AA:1 / 图形 ≥$GRAPHIC_AA:1）：")
                failures.forEach { appendLine(it) }
                appendLine("处置：调染色参数或种子后重算全目录对比度；禁改 onSurfaceFaint 固定实色单点凑数。")
            },
        )
    }

    @Test
    fun `tone ladder keeps light cards sunk and dark cards floating across catalog`() {
        AppThemeSpec.presets.forEach { spec ->
            val light = spec.colorSet(dark = false)
            val lightBg = toneOf(light.background)
            val lightBgHct = Hct.fromInt(light.background.toArgb())
            if (spec.pageBaseLight != null) {
                // 底色主权套：亮态双轨断言块（重采样窗断言 + 97.9 锚轨断言）整体退出——
                // 底色已由套参数直采（跳过锚轨与重采样窗），窗断言的「窗外即红」前提不再成立；
                // 套内自洽由阶梯方向断言、分带色度地板（底色读得出彩相的泛化机械化）与
                // 文字域拆分 AA（G13）承载——是替换校验，非豁免
            } else {
                val resampled = spec.skeletonStain?.let { it.lightPageTone < LIGHT_PAGE_TONE } == true
                if (resampled) {
                    // 重采样轨：窗口下沿 = 最深彩纸需求套（洒红节粉雾纸 95.0 直采）− 量化回读容差，
                    // 上沿即锚
                    assertTrue(
                        lightBg >= LIGHT_TONE_WINDOW_MIN && lightBg <= LIGHT_PAGE_TONE,
                        "${spec.id} 亮态 bg tone $lightBg 出重采样窗口 [$LIGHT_TONE_WINDOW_MIN, $LIGHT_PAGE_TONE]" +
                            "（下沿 = 最深彩纸需求钉定）——非豁免套须降到窗口内解钳，或改登记中性豁免（须过题材理由）",
                    )
                } else {
                    // 97.9 锚轨（豁免套 + 非豁免停留组）：量化回读可到 97.97，锚容差 ±0.5 钉住
                    assertTrue(
                        kotlin.math.abs(lightBg - LIGHT_PAGE_TONE) <= 0.5,
                        "${spec.id} 亮态 bg tone $lightBg 偏离 $LIGHT_PAGE_TONE 校正锚（超 ±0.5）——" +
                            "97.9 管线套必须逐位走锚校正链",
                    )
                }
            }
            if (!spec.neutralSkeletonExempt) {
                // 分带色度地板只约束非豁免套（含重采样轨、97.9 停留组与底色主权套）；豁免套近零染色即身份
                val floor = bandFloor(lightBgHct.hue)
                assertTrue(
                    lightBgHct.chroma >= floor,
                    "${spec.id} 亮态 bg chroma ${"%.2f".format(lightBgHct.chroma)} 低于色相带 h${"%.0f".format(lightBgHct.hue)} " +
                        "地板 $floor——骨架染色被钳回无相近白纸（调染色参数或降 lightPageTone，不得删地板）",
                )
            }
            assertStrictlyDescending(
                "${spec.id} 亮态",
                lightBg,
                toneOf(light.surfaceContainerLow),
                toneOf(light.surfaceContainerHigh),
                toneOf(light.surfaceContainerHighest),
            )
            val dark = spec.colorSet(dark = true)
            assertStrictlyAscending(
                "${spec.id} 暗态",
                toneOf(dark.background),
                toneOf(dark.surfaceContainerLow),
                toneOf(dark.surfaceContainerHigh),
                toneOf(dark.surfaceContainerHighest),
            )
            if (!spec.neutralSkeletonExempt) {
                // 非豁免暗 bg chroma 地板：近黑底色相不可感的下界（M3 暗 tone6 色域上限
                // 15.3-43.9，参数保守只取 6-14 的套会被同族粘连）；底色主权套对直采底同样断言
                val darkBgHct = Hct.fromInt(dark.background.toArgb())
                assertTrue(
                    darkBgHct.chroma >= DARK_CHROMA_FLOOR,
                    "${spec.id} 暗态 bg chroma ${"%.2f".format(darkBgHct.chroma)} < 地板 $DARK_CHROMA_FLOOR" +
                        "——暗骨架染色不足（升 darkChroma，豁免须过题材理由登记）",
                )
            }
        }
    }

    @Test
    fun `light page tone anchor behavior holds`() {
        // 防回退锚的行为化（Default 豁免套单点快照）：AppThemeSpec 的浅色基底校正链
        // （单源常量 private 于该文件）必须把豁免套亮态 bg tone 稳定送到 97.9 锚
        // （勿回填 95.5 系——页面/卡明度序会反转，由阶梯测试逐套断言机械执行）。
        val bg = AppThemeSpec.Default.colorSet(dark = false).background
        assertTrue(
            kotlin.math.abs(toneOf(bg) - LIGHT_PAGE_TONE) <= 0.5,
            "Default 亮态 bg tone ${toneOf(bg)} 未稳定落在 $LIGHT_PAGE_TONE 锚——浅色基底校正链被破坏",
        )
    }

    @Test
    fun `wired console bg keeps console tokens above AA across catalog and modes`() {
        // AppTheme 装配点 consoleBg 单源解析（resolvedConsoleBg：套级直配 > 暗态染色派生 >
        // 静态基值）的等价实算：consoleText/consoleSuccess/consoleInfo 恒深值家族对解析底
        // ≥ 4.5:1，双态全目录（亮态无染色派生档 = 静态基值，底色主权套亮态同为人造深井面）。
        // platingGreen 井面同判（结构件绿在深井内的承载位）。log 四色/aiAccent/chartPalette
        // 不接染色（跨主题语义一致性），不在本断言面。
        val failures = mutableListOf<String>()
        AppThemeSpec.presets.forEach { spec ->
            listOf(false, true).forEach { dark ->
                val direct = spec.slotOverrides(dark)?.consoleBg
                val consoleBg = resolvedConsoleBg(spec, dark)
                val source = when {
                    direct != null -> "直配"
                    dark && stainedDarkConsoleBg(spec) != null -> "派生"
                    else -> "基值"
                }
                val table = if (dark) DarkEasyToolboxColors else LightEasyToolboxColors
                for ((name, fg) in listOf(
                    "consoleText" to table.consoleText,
                    "consoleSuccess" to table.consoleSuccess,
                    "consoleInfo" to table.consoleInfo,
                    "platingGreen" to table.withSlotOverrides(spec.slotOverrides(dark)).platingGreen,
                )) {
                    val ratio = contrast(fg, consoleBg)
                    if (ratio < TEXT_AA) {
                        failures.add("  ${spec.id}[${if (dark) "暗" else "亮"}] $name/$source consoleBg ${"%.2f".format(ratio)}:1 < $TEXT_AA:1")
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("接线 consoleBg 上恒深值 token 对比度 FAIL（≥$TEXT_AA:1）：")
                failures.forEach { appendLine(it) }
                appendLine("处置：调 stainedDarkConsoleBg 的 chroma 收敛档或 tone、或核对套级直配值；禁回调恒深值 token 单点凑数。")
            },
        )
    }

    @Test
    fun `overridden chart palettes meet graphic floor on their carrying surface`() {
        // chartPalette 套级覆写面守护（有覆写就校验，未覆写套不进断言面）：8 档分类色对
        // 实际承载面 ≥3:1——亮态承载面 = surfaceContainerLow 卡（图表最浅落面）、暗态 = 底。
        // 静态恒定色板不进本断言面：亮档分类色对 tone-96 卡面存在 <3 档（如橙 #EF6C00
        // 实算 ~2.79），恒定语义色板属跨主题一致性口径，不因断言面扩面而被迫改值。
        val failures = mutableListOf<String>()
        AppThemeSpec.presets.forEach { spec ->
            listOf(false, true).forEach { dark ->
                val overrides = spec.slotOverrides(dark)
                val palette = overrides?.chartPalette ?: return@forEach
                val scheme = spec.colorSet(dark)
                val carrying = if (dark) scheme.background else scheme.surfaceContainerLow
                palette.forEachIndexed { index, c ->
                    check(failures, GRAPHIC_AA, "%.2f", "${spec.id}[${if (dark) "暗" else "亮"}] chart${index + 1}/carrying", c, carrying)
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("覆写 chartPalette 对承载面对比度 FAIL（≥$GRAPHIC_AA:1）：")
                failures.forEach { appendLine(it) }
                appendLine("处置：调覆写色板档位（对承载面重算）；禁改静态恒定色板凑数。")
            },
        )
    }

    // ── 断言辅助 ──

    /**
     * 非豁免浅 bg 分带色度地板（按 bg 实测 HCT 色相查表，色相环分段左闭右开）：
     * 暖红 [340,360)∪[0,15) 3.5 / 暖橙 [15,45) 3.0 / 暖黄 [45,90) 3.5 / 黄绿 [90,115) 5.0 /
     * 绿 [115,165) 8.0 / 青 [165,215) 8.0 / 蓝紫 [215,300) 6.0 / 品红 [300,340) 4.0。
     * 线值 = 重采样后全目录逐套实测可达下界（最窄档 dracula c7.2/线 6.0、已删旧档 c3.8/线 3.0）。
     */
    private fun bandFloor(hue: Double): Double = when {
        hue >= 340.0 || hue < 15.0 -> 3.5
        hue < 45.0 -> 3.0
        hue < 90.0 -> 3.5
        hue < 115.0 -> 5.0
        hue < 165.0 -> 8.0
        hue < 215.0 -> 8.0
        hue < 300.0 -> 6.0
        else -> 4.0
    }

    private fun assertStrictlyDescending(label: String, bg: Double, low: Double, high: Double, highest: Double) {
        assertTrue(
            bg > low && low > high && high > highest,
            "$label tone 阶梯必须严格降序（bg $bg > Low $low > High $high > Highest $highest）——「卡沉于底」结构被破坏",
        )
    }

    private fun assertStrictlyAscending(label: String, bg: Double, low: Double, high: Double, highest: Double) {
        assertTrue(
            bg < low && low < high && high < highest,
            "$label tone 阶梯必须严格升序（bg $bg < Low $low < High $high < Highest $highest）——「卡浮起」结构被破坏",
        )
    }

    private fun check(
        failures: MutableList<String>,
        floor: Double,
        format: String,
        name: String,
        fg: Color,
        bg: Color,
    ) {
        val ratio = contrast(fg, bg)
        if (ratio < floor) {
            failures.add("  $name ${format.format(ratio)}:1 < $floor:1")
        }
    }

    /** HCT tone（material-color-utilities） */
    private fun toneOf(color: Color): Double = Hct.fromInt(color.toArgb()).tone

    /** WCAG 2.x 相对亮度（sRGB 线性化，与 SlotContrastGuardTest 同款） */
    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(fg: Color, bg: Color): Double {
        val hi = maxOf(luminance(fg), luminance(bg))
        val lo = minOf(luminance(fg), luminance(bg))
        return (hi + 0.05) / (lo + 0.05)
    }

    private companion object {
        /** AA 普通文本硬线（正文字号 < 18pt，非 3:1 大字档） */
        const val TEXT_AA = 4.5

        /** WCAG 非文本图形线（描边/图标/图形组件） */
        const val GRAPHIC_AA = 3.0

        /** 浅色基底校正锚（与 AppThemeSpec.kt 单源常量一致；两处各自断言防单边漂移） */
        const val LIGHT_PAGE_TONE = 97.9

        /**
         * 非豁免重采样窗口下沿：最深彩纸需求套（Gulal 粉雾纸 lightPageTone 95.0，
         * 量化回读 94.90）留 0.4 容差；域放宽由彩纸套需求钉定，不得反向收紧。
         */
        const val LIGHT_TONE_WINDOW_MIN = 94.5

        /** 非豁免暗 bg chroma 地板（近黑底色相不可感的下界；最窄档 绯红夜城 c10.02） */
        const val DARK_CHROMA_FLOOR = 10.0
    }
}
