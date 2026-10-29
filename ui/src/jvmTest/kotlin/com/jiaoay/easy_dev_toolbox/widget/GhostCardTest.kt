package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GhostCard 弱化卡验收断言（DS-08 · 重设计批 3 · 2026-10-01）：
 *
 * 本批只落件零迁消费（首个消费方 = 批 8 AG-05 空态提示卡，roadmap「组件批先于消费批」），
 * 故验收面 = 三钉：
 * 1. **编译可达**：本文件引用 [GhostCard] 可组合项——签名不兼容改写或文件删除会直接
 *    编译失败（:ui:jvmTest 无法通过），ghost token（[AppAlpha.ghostBgLight]/[ghostBgDark]）
 *    与 shape/stroke token 同样以引用方式钉进编译面；
 * 2. **值断言**：alpha 两档 = DL-M21 固定值（亮 0.38 / 暗 0.05）；shape = AppShapes.large
 *    16dp；发丝描边宽 = glassStrokeHairline 1dp（DL-M06 卡密度与 DS-02 描边语言的消费值锚）；
 * 3. **配方源断言**：GhostCard.kt 源码必须钉住「预混 White 系 + LocalIsDarkTheme 明暗通道
 *    + 发丝描边 + 零 blur（chrome 恒实底纪律）」——防后续批接线时配方被顺手改动。
 *
 * （:ui 无 skiko 运行时（desktop.currentOs 仅宿主装配），像素级场景渲染不落本模块——
 * DS-09 的 ImageComposeScene 场景验证在 composeApp 侧基建上跑。）
 */
class GhostCardTest {

    /** 编译可达探针：签名改写/文件删除即本测试模块编译失败（不执行，只编译） */
    @Composable
    private fun compileReachabilityProbe() {
        GhostCard {
            Text(text = "GhostCard 编译可达探针")
        }
    }

    @Test
    fun `ghost bg alpha tiers equal DL-M21 fixed values`() {
        assertEquals(0.38f, AppAlpha.ghostBgLight, "ghost 卡底·亮档必须 0.38（DL-M21 固定值，误改即弱化层次失衡）")
        assertEquals(0.05f, AppAlpha.ghostBgDark, "ghost 卡底·暗档必须 0.05（DL-M21 固定值，超值即与 surfaceRaised 抢层次）")
    }

    @Test
    fun `ghost card shape and hairline stroke match design tokens`() {
        // shape 描边值锚：AppShapes.large（16dp 内容卡档）+ 1dp 发丝（DS-02 描边语言）
        assertEquals(RoundedCornerShape(16.dp), AppShapes.large, "GhostCard shape 档必须 AppShapes.large 16dp")
        assertEquals(1.dp, Dimens.glassStrokeHairline, "GhostCard 发丝描边宽必须 1dp（glassStrokeHairline）")
    }

    @Test
    fun `ghost card recipe stays pinned to DL-M21 premixed white`() {
        val source = ghostCardSource()
        listOf(
            "Color.White.copy(alpha = AppAlpha.ghostBgLight)" to "亮档必须预混 White 系 × ghostBgLight token",
            "Color.White.copy(alpha = AppAlpha.ghostBgDark)" to "暗档必须预混 White 系 × ghostBgDark token",
            "LocalIsDarkTheme.current" to "明暗通道必须经 LocalIsDarkTheme（AppTheme 恒装配、缺装配 fail fast）",
            "shape = AppShapes.large" to "shape 必须 AppShapes.large（DL-M05 内容卡 16dp 档）",
            "BorderStroke(Dimens.glassStrokeHairline, surface.borderHairline)" to "描边必须 1dp 发丝 + borderHairline 明暗分治档（DS-02 同款）",
            "borderHairline" to "描边色源必须 surface slot（borderHairline）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in source,
                "GhostCard.kt 丢失配方要素「$needle」——$why",
            )
        }
    }

    @Test
    fun `ghost card stays blur free and non elevated`() {
        // 只查代码区（KDoc 写明「零 blur」纪律本身含字样，剥注释防自咬）
        val source = stripComments(ghostCardSource())
        assertFalse(
            "blur" in source.lowercase() || "haze" in source.lowercase(),
            "GhostCard 禁 blur/haze（DL-M21「禁 blur 实现」+ R2 材质诚实化：blur 只属于浮层档 sheet/thin）",
        )
        assertFalse(
            "ElevatedCard" in source,
            "GhostCard 必须走 Card 扁平配方（ELEVATED_CARD 守护红线）",
        )
    }

    /** 剥 KDoc/行注释/块注释（字符串内容保留——本断言面只关心 API 调用） */
    private fun stripComments(source: String): String = buildString {
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) {
                        if (source[i] == '\n') append('\n')
                        i++
                    }
                    i += 2
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

    /** 定位 GhostCard.kt 源文件（自测试工作目录向上找 settings.gradle.kts 定项目根） */
    private fun ghostCardSource(): String {
        val root = locateProjectRoot()
        val file = File(root, GHOST_CARD_PATH)
        assertTrue(file.isFile, "$GHOST_CARD_PATH 不存在——DS-08 新件被移动须同步更新本测试路径")
        return file.readText()
    }

    private fun locateProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("未定位项目根（settings.gradle.kts）——:ui:jvmTest 工作目录异常")
    }

    private companion object {
        const val GHOST_CARD_PATH =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/GhostCard.kt"
    }
}
