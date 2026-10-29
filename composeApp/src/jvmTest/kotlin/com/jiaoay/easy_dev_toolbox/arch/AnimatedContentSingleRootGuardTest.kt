package com.jiaoay.easy_dev_toolbox.arch

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import com.jiaoay.easy_dev_toolbox.core.theme.AppTheme
import com.jiaoay.easy_dev_toolbox.core.theme.AppThemeSpec
import com.jiaoay.easy_dev_toolbox.core.theme.ThemeMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AnimatedContent 单根纪律守护：
 *
 * 🔴 CMP 1.12 的 [AnimatedContent] content lambda **必须是单根发射**——
 * AnimatedEnterExitMeasurePolicy.measure 尾行 `placeables.fastForEach { it.place(0, 0) }`
 * 把多根 placeable 全部叠放在 (0,0)，多根即叠放（同位重叠）。
 *
 * 守护面：色条三根发射 → 断言三色带 y 范围互斥（若 API 行为变化会当场红，防止
 * 「多根恰好流式」的侥幸回归；单根包裹配方变化即本测试红，提醒更新先例注释）。
 */
class AnimatedContentSingleRootGuardTest {

    private lateinit var scene: ImageComposeScene

    @AfterTest
    fun tearDown() {
        if (this::scene.isInitialized) scene.close()
    }

    private fun render(content: @Composable () -> Unit): Array<IntArray> {
        scene = ImageComposeScene(width = 400, height = 600, density = Density(1f))
        scene.setContent { AppTheme(scheme = AppThemeSpec.Default, themeMode = ThemeMode.Light) { content() } }
        var image = scene.render(0)
        var guard = 0
        while (scene.hasInvalidations() && guard < 10) {
            image = scene.render(0)
            guard++
        }
        val data = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG) ?: error("encode fail")
        val b = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(data.bytes))
        return Array(b.height) { y -> IntArray(b.width) { x -> b.getRGB(x, y) and 0xFFFFFF } }
    }

    /** 按色值找行带（该色像素 >10 的行聚类，间隔 ≤2 合并） */
    private fun colorBands(pixels: Array<IntArray>, color: Int): List<IntRange> {
        val hits = mutableListOf<Int>()
        for (y in pixels.indices) {
            var cnt = 0
            for (x in pixels[y].indices step 2) {
                if (pixels[y][x] == color) cnt++
            }
            if (cnt > 10) hits.add(y)
        }
        if (hits.isEmpty()) return emptyList()
        val groups = mutableListOf<IntRange>()
        var start = hits[0]
        var prev = hits[0]
        for (y in hits.drop(1)) {
            if (y - prev > 2) {
                groups.add(start..prev)
                start = y
            }
            prev = y
        }
        groups.add(start..prev)
        return groups
    }

    @Test
    fun `multi root emission inside AnimatedContent stacks at origin`() {
        // 基线钉死 API 行为：多根发射必然叠放（= 单根纪律的存在理由）。
        // 若本用例红（多根开始流式布局），单根包裹配方可解除——届时改守护口径。
        val RED = 0xE05050
        val GREEN = 0x50C080
        val BLUE = 0x5080C0
        val pixels = render {
            Column(Modifier.fillMaxWidth()) {
                Text("头部")
                AnimatedContent(
                    targetState = 0,
                    transitionSpec = { (fadeIn(animationSpec = tween(0)) togetherWith fadeOut(animationSpec = tween(0))) },
                    label = "probe",
                ) {
                    // 多根发射：三个不同色条（违反单根纪律的形态——正是被守护的错例）
                    Box(Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(30.0f)).background(color(RED))) { Text("A") }
                    Box(Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(30.0f)).background(color(GREEN))) { Text("B") }
                    Box(Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(30.0f)).background(color(BLUE))) { Text("C") }
                }
            }
        }
        val redB = colorBands(pixels, RED)
        val greenB = colorBands(pixels, GREEN)
        val blueB = colorBands(pixels, BLUE)
        // 蓝色（末根，盖上）应完整渲染 30px 高行带
        assertTrue(
            blueB.size == 1 && (blueB.first().last - blueB.first().first + 1) in 28..32,
            "蓝色末根应完整渲染 ~30px 行带（实际 $blueB）",
        )
        // 🔴 叠放判定：末根独占视口高度带、前根不产出独立流式带（红绿行带空或与
        //    蓝带重合残影）。若红绿出现独立 30px 带 = 多根开始流式布局，单根纪律可解除。
        val stackedOut = redB.none { (it.last - it.first + 1) in 25..35 } &&
            greenB.none { (it.last - it.first + 1) in 25..35 }
        assertTrue(
            stackedOut,
            "多根发射应叠放同位（CMP 1.12 AnimatedEnterExit place(0,0)）——若红绿" +
                "各自出现独立 ~30px 带则单根纪律可解除：red=$redB green=$greenB blue=$blueB",
        )
    }

    @Test
    fun `single root column lays out children sequentially`() {
        // 正例锚：单根 Column 包裹配方（ToolsPage / UsageStatsPanel 修复后的形态）——
        // 三色条依次流式排列，无任何重叠。
        val RED = 0xE05050
        val GREEN = 0x50C080
        val BLUE = 0x5080C0
        val pixels = render {
            Column(Modifier.fillMaxWidth()) {
                Text("头部")
                AnimatedContent(
                    targetState = 0,
                    transitionSpec = { (fadeIn(animationSpec = tween(0)) togetherWith fadeOut(animationSpec = tween(0))) },
                    label = "probe",
                ) {
                    // 单根纪律配方：一个 Column 根，内部多子流式
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(30.0f)).background(color(RED))) { Text("A") }
                        Box(Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(30.0f)).background(color(GREEN))) { Text("B") }
                        Box(Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(30.0f)).background(color(BLUE))) { Text("C") }
                    }
                }
            }
        }
        val redB = colorBands(pixels, RED)
        val greenB = colorBands(pixels, GREEN)
        val blueB = colorBands(pixels, BLUE)
        // 三色带各一组、依次递增、互不重叠
        assertEquals(1, redB.size, "单根配方红条应恰好一行带：$redB")
        assertEquals(1, greenB.size, "单根配方绿条应恰好一行带：$greenB")
        assertEquals(1, blueB.size, "单根配方蓝条应恰好一行带：$blueB")
        assertTrue(
            redB.first().last < greenB.first().first && greenB.first().last < blueB.first().first,
            "单根 Column 配方三色应流式依次排列：red=$redB green=$greenB blue=$blueB",
        )
        val gapRedGreen = greenB.first().first - redB.first().last - 1
        val gapGreenBlue = blueB.first().first - greenB.first().last - 1
        assertTrue(gapRedGreen in 0..4 && gapGreenBlue in 0..4, "单根配方色条紧邻（0-4px 间隙）: $gapRedGreen/$gapGreenBlue")
    }

    private fun color(i: Int) = androidx.compose.ui.graphics.Color(
        (i shr 16 and 0xFF) / 255f,
        (i shr 8 and 0xFF) / 255f,
        (i and 0xFF) / 255f,
    )
}
