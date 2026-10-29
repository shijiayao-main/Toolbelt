package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.materialkolor.utils.ColorUtils
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 主题预设两两可区分性守护（运行时实算、零 hex 锚、自动覆盖未来新增套）：
 * 全部预设两两（20×19/2 = 190 对）×深浅两态 = 380 对模式，四组断言——
 *
 * ① 联合度量（骨架单通道 + 强调单通道 + 复合腿）：
 *    - S = min(ΔE76(bgA,bgB), ΔE76(cardLowA,cardLowB))：骨架单通道（大面积底色）；
 *    - A = max over 槽位对(ΔE76(primary), ΔE76(secondary), ΔE76(tertiary))：强调单通道；
 *    - PASS = S ≥ 12.0 ∨ A ≥ 25.0 ∨ (S ≥ 4.0 ∧ A ≥ 15.0)。
 * ② bg hex 两两互异（无豁免）：同 hex 即「换主题零观感」，是骨架可辨的机械下界。
 * ③ bg ΔE76 地板（只约束「双非豁免 ∧ HCT 色相差 ≥25°」的跨族对）：亮 ≥ 2.0 / 暗 ≥ 4.0——
 *    非豁免套已按 SkeletonStain.lightPageTone 重采样 + 分带色度地板解钳，跨族纸面必须可辨；
 *    暗态 4.0 是近黑底低明度压缩后的可辨下界。
 * ④ A 腿（强调单通道 ≥ A_ACCENT）：约束「同族对（色相差 <25°）」——bg 通道物理不可辨
 *    （同族亮纸 ΔE76<1 是色域物理），观感分离完全依赖强调声部。
 *
 * 阈值依据（实跑取证）：
 * - A ≥ 25 = 上轮保留对实测可辨下界（单槽距离 14.5~19.1 判「可辨」）× 1.7 收紧；
 * - S ≥ 12 = 暗态骨架单通道（暗 tone 6/10 深底对 ΔE76 天花板实测 ~8-11，只有大色度差够得着）；
 * - 复合腿 S ≥ 4 ∧ A ≥ 15 = 大面积底色 1.6-2×JND 配可辨强调的双通道（重采样窗口
 *   [94.5,97.9] + 分带色度地板落地后，浅态骨架 ΔE76 从钳制的 ~0-1.5 抬到 2-9）；
 * - 中性豁免清单（neutralSkeletonExempt，克制即身份的对照系）：Default（零漂移锚）/
 *   朱砂宣纸（近零染色）/ 薄荷青（真中性灰规格）/ 蓝金三色（近无相白甲+三点式身份）。豁免套不进 ③ 的 bg 地板
 *   （豁免×豁免 bg ΔE76 实测贴地：蓝金三色×薄荷青 0.89 等——
 *   近零染色即身份；非豁免套拉开后其可辨识性反升），
 *   含豁免套的跨族对（如 Default×qinghua 暗 S=4.7/A=17.3 复合腿）由 ① 联合度量复合腿承载分离
 *   ——这些是登记过的最紧对，Default 种子受零漂移锚契约钉死，A≥25 硬线对其不可达。
 * 最紧对（全目录 margin 最薄处，实跑取证登记）：matrix↔青白霓虹 亮 S=12.01（cardLow 腿）、
 * Default↔gulal 暗 S=12.06（均走 S≥12 硬线单通道）——翻红预案 = 重算全部度量并微调对端套参数，不得删线或加豁免。
 */
class ThemeDistinguishabilityGuardTest {

    @Test
    fun `every preset pair stays distinguishable in both modes by joint skeleton accent metric`() {
        val presets = AppThemeSpec.presets
        val failures = mutableListOf<String>()
        for (i in presets.indices) {
            for (j in i + 1 until presets.size) {
                for (dark in listOf(false, true)) {
                    val mode = if (dark) "暗" else "亮"
                    val schemeA = presets[i].colorSet(dark)
                    val schemeB = presets[j].colorSet(dark)
                    val s = skeletonDistance(schemeA, schemeB)
                    val a = accentDistance(schemeA, schemeB)
                    val pass = s >= S_SKELETON ||
                        a >= A_ACCENT ||
                        (s >= S_COMPOSITE && a >= A_COMPOSITE)
                    if (!pass) {
                        failures.add("  ${presets[i].id} vs ${presets[j].id} [$mode] S=%.1f A=%.1f".format(s, a))
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("预设两两可区分性 FAIL（联合度量 S≥$S_SKELETON ∨ A≥$A_ACCENT ∨ S≥$S_COMPOSITE∧A≥$A_COMPOSITE）：")
                failures.forEach { appendLine(it) }
                appendLine("处置：微调对端套的种子/染色参数后重算全目录度量；不得删线、不得加豁免。")
            },
        )
    }

    @Test
    fun `background colors are pairwise distinct across catalog and modes`() {
        // ② 骨架 bg hex 两两互异（全 190 对 × 两态，无豁免）：同 hex = 切换该两套时页面
        // 底色零变化，是「换主题骨架感知不动」的最硬形态；豁免套也不许同 hex（暗态现状
        // 已满足，此断言双向防回退）。
        val presets = AppThemeSpec.presets
        val failures = mutableListOf<String>()
        for (i in presets.indices) {
            for (j in i + 1 until presets.size) {
                for (dark in listOf(false, true)) {
                    val bgA = presets[i].colorSet(dark).background
                    val bgB = presets[j].colorSet(dark).background
                    if (bgA == bgB) {
                        failures.add("  ${presets[i].id} vs ${presets[j].id} [${if (dark) "暗" else "亮"}] bg 同 hex")
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("骨架 bg 两两互异 FAIL：")
                failures.forEach { appendLine(it) }
                appendLine("处置：调染色参数或降 lightPageTone 重采样解钳；不得删断言、不得做 hex 拼凑。")
            },
        )
    }

    @Test
    fun `cross family non exempt pairs keep skeleton background delta floors`() {
        // ③ 跨族 bg ΔE76 地板：双非豁免 ∧ bg HCT 色相差 ≥ 25°（色相环距）的对——
        // 亮 ≥ 2.0（重采样解钳后的纸面可辨下界）/ 暗 ≥ 4.0（近黑底低明度压缩后的下界）。
        // 同族对由 A 腿断言接管（bg 物理不可辨）；含豁免套的对由 ① 联合度量承载（见类 KDoc）。
        val presets = AppThemeSpec.presets
        val failures = mutableListOf<String>()
        for (i in presets.indices) {
            for (j in i + 1 until presets.size) {
                val a = presets[i]
                val b = presets[j]
                if (a.neutralSkeletonExempt || b.neutralSkeletonExempt) continue
                for (dark in listOf(false, true)) {
                    val bgA = a.colorSet(dark).background
                    val bgB = b.colorSet(dark).background
                    val hueGap = hueDistance(hueOf(bgA), hueOf(bgB))
                    if (hueGap < FAMILY_HUE_GAP) continue
                    val d = deltaE76(bgA, bgB)
                    val floor = if (dark) BG_FLOOR_DARK else BG_FLOOR_LIGHT
                    if (d < floor) {
                        failures.add(
                            "  ${a.id} vs ${b.id} [${if (dark) "暗" else "亮"}] bg ΔE76=%.2f < $floor（色相差 %.0f°）".format(d, hueGap),
                        )
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("跨族非豁免 bg ΔE76 地板 FAIL（亮 ≥$BG_FLOOR_LIGHT / 暗 ≥$BG_FLOOR_DARK）：")
                failures.forEach { appendLine(it) }
                appendLine("处置：调染色参数/降 lightPageTone/挪相拆同族；不得删地板，翻红先重算全目录度量。")
            },
        )
    }

    @Test
    fun `same family pairs keep accent leg above distinguishability line`() {
        // ④ 同族对（bg HCT 色相差 <25°，含同族的豁免套对）→ A 腿（强调三槽位对 ΔE76 最大值）
        // ≥ A_ACCENT：bg 通道物理不可辨时观感分离完全依赖强调声部。
        // 沿用现行 A_ACCENT 常量，不新增线值。
        // 含豁免套的跨族对（如 Default×qinghua 暗 S=4.7/A=17.3 复合腿）
        // 不在本断言面——其分离由原样保留的联合度量复合腿承载（Default 种子受零漂移锚
        // 契约钉死，动种子不可行；这些是登记过的复合腿最紧对）。
        val presets = AppThemeSpec.presets
        val failures = mutableListOf<String>()
        for (i in presets.indices) {
            for (j in i + 1 until presets.size) {
                val a = presets[i]
                val b = presets[j]
                for (dark in listOf(false, true)) {
                    val bgA = a.colorSet(dark).background
                    val bgB = b.colorSet(dark).background
                    if (hueDistance(hueOf(bgA), hueOf(bgB)) >= FAMILY_HUE_GAP) continue
                    val accent = accentDistance(a.colorSet(dark), b.colorSet(dark))
                    if (accent < A_ACCENT) {
                        failures.add(
                            "  ${a.id} vs ${b.id} [${if (dark) "暗" else "亮"}] A=%.1f < $A_ACCENT（同族对）".format(accent),
                        )
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("同族对 A 腿 FAIL（强调单通道 ≥$A_ACCENT）：")
                failures.forEach { appendLine(it) }
                appendLine("处置：微调对端套的 secondarySeed/tertiarySeed 或挪相拆同族；不得删线。")
            },
        )
    }

    /** 骨架单通道：大面积底色（页面基底 / 卡容器低档）的最近 ΔE76 */
    private fun skeletonDistance(a: ColorScheme, b: ColorScheme): Double =
        minOf(deltaE76(a.background, b.background), deltaE76(a.surfaceContainerLow, b.surfaceContainerLow))

    /** 强调单通道：主/次/第三强调三槽位对 ΔE76 的最大值（任一槽位拉开即算分离） */
    private fun accentDistance(a: ColorScheme, b: ColorScheme): Double {
        val slotsA = listOf(a.primary, a.secondary, a.tertiary)
        val slotsB = listOf(b.primary, b.secondary, b.tertiary)
        return slotsA.indices.maxOf { k -> deltaE76(slotsA[k], slotsB[k]) }
    }

    /** HCT 色相（bg 实测相，重采样套 = 染色 lightHue/darkHue 的直采落地） */
    private fun hueOf(color: Color): Double = Hct.fromInt(color.toArgb()).hue

    /** HCT 色相环距（0..180，防 350° vs 10° 被算成 340°） */
    private fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return minOf(d, 360.0 - d)
    }

    private fun deltaE76(a: Color, b: Color): Double {
        val labA = ColorUtils.labFromArgb(a.toArgb())
        val labB = ColorUtils.labFromArgb(b.toArgb())
        val dl = labA[0] - labB[0]
        val da = labA[1] - labB[1]
        val db = labA[2] - labB[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    private companion object {
        /** 骨架单通道硬线：暗态深底对 ΔE76 天花板 ~8-11 实测，只有大色度差够得着 */
        const val S_SKELETON = 12.0

        /** 强调单通道硬线：上轮保留对可辨下界 14.5 × 1.7 收紧 */
        const val A_ACCENT = 25.0

        /** 复合腿骨架下界：重采样窗口 + 分带色度地板落地后浅态骨架 ΔE76 的复合腿下界 */
        const val S_COMPOSITE = 4.0

        /** 复合腿强调下界：大面积底色 1.6-2×JND 配可辨强调的双通道 */
        const val A_COMPOSITE = 15.0

        /** 跨族非豁免对亮 bg ΔE76 地板（重采样解钳后的纸面可辨下界） */
        const val BG_FLOOR_LIGHT = 2.0

        /** 跨族非豁免对暗 bg ΔE76 地板（近黑底低明度压缩后的可辨下界） */
        const val BG_FLOOR_DARK = 4.0

        /** 同族线：bg HCT 色相差 <25° 视为同族（bg 通道物理不可辨，分离依赖 A 腿） */
        const val FAMILY_HUE_GAP = 25.0
    }
}
