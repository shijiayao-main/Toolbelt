package com.jiaoay.easy_dev_toolbox.page.settings

import androidx.compose.material3.MaterialTheme
import com.jiaoay.easy_dev_toolbox.arch.assertBandsSeparated
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.countColor
import com.jiaoay.easy_dev_toolbox.arch.GLYPH_COLOR_TOLERANCE
import com.jiaoay.easy_dev_toolbox.arch.probeSchemeColor
import com.jiaoay.easy_dev_toolbox.arch.renderPixels
import com.jiaoay.easy_dev_toolbox.core.initializer.InitializerTiming
import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializerManager
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「启动耗时」子区验收（设置页开发者选项门控内呈现）：
 * - 渲染面：ImageComposeScene 零显示器配方（arch 包 SceneGuardSupport 单源）直测面板——
 *   [JInitializerManager.initTimingsFlow] 是公开观测面即注入口，预置记录后逐条耗时行/
 *   组标题应渲染为独立内容带（空态差分防「面板以零占位缺席」误绿）；
 * - 失败标记：error 着色随 isSuccess=false 出现、全成功态零出现；
 * - 门控面：源码锚（SettingsPageCopyGuardTest 同款配方）——面板调用必须在
 *   debugPageEnabled 门控块内 + 组合计必须 sumOf(durationNanos) 求和口径；
 * - 格式化：毫秒一位小数（Locale.ROOT 固定小数点）纯函数直测。
 *
 * 面板纯读 StateFlow（无 DataStore/无 IO/无动画），无需 user.home 隔离与 Main
 * dispatcher 替换；全局流在 tearDown 还原空表防跨用例泄漏。
 */
class StartupTimingsPanelTest {

    @AfterTest
    fun tearDown() {
        JInitializerManager.initTimingsFlow.value = emptyList()
    }

    // ── 渲染面 ────────────────────────────────────────────────────────────

    @Test
    fun `timing rows render as separated bands per group with empty state diff`() {
        JInitializerManager.initTimingsFlow.value = emptyList()
        val emptyPixels = renderPixels(SCENE_W, SCENE_H, frameBudgetNanos = FRAME_BUDGET_NS) { StartupTimingsPanel() }
        val emptyBands = contentRows(emptyPixels, SCENE_W)
        assertTrue(
            emptyBands.size >= 3,
            "空态应至少渲染标题 + 描述 + 空提示三带，实际 ${emptyBands.size}：$emptyBands",
        )

        JInitializerManager.initTimingsFlow.value = listOf(
            timing(FakeHighA::class.java, isHighLevel = true, isSuccess = true, durationNanos = 1_500_000L),
            timing(FakeHighB::class.java, isHighLevel = true, isSuccess = false, durationNanos = 250_000_000L),
            timing(FakeLowA::class.java, isHighLevel = false, isSuccess = true, durationNanos = 12_000_000L),
        )
        val seededPixels = renderPixels(SCENE_W, SCENE_H, frameBudgetNanos = FRAME_BUDGET_NS) { StartupTimingsPanel() }
        val seededBands = contentRows(seededPixels, SCENE_W)
        assertBandsSeparated(seededBands)
        // 标题 + 描述 + 高优组标题 + 2 耗时行 + 低优组标题 + 1 耗时行 = 7 带
        assertTrue(
            seededBands.size >= 7,
            "预置 2+1 两组三记录应渲染 ≥7 内容带（标题/描述/两组标题/三耗时行），" +
                "实际 ${seededBands.size}：$seededBands",
        )
        assertTrue(
            seededBands.size >= emptyBands.size + 4,
            "预置记录后内容带数应比空态至少 +4（两 组标题 + 三 耗时行 - 空 提示行），" +
                "空态 ${emptyBands.size} → 实际 ${seededBands.size}——面板以零占位缺席即红",
        )
    }

    @Test
    fun `failed timing rows carry error colored marker only when failure present`() {
        val errorColor = probeSchemeColor { MaterialTheme.colorScheme.error }

        JInitializerManager.initTimingsFlow.value = listOf(
            timing(FakeHighA::class.java, isHighLevel = true, isSuccess = true, durationNanos = 1_500_000L),
        )
        val okPixels = renderPixels(SCENE_W, SCENE_H, frameBudgetNanos = FRAME_BUDGET_NS) { StartupTimingsPanel() }
        assertTrue(
            countColor(okPixels, errorColor) == 0,
            "全成功态不得出现 error 着色像素（失败标记只随 isSuccess=false 出现）",
        )

        JInitializerManager.initTimingsFlow.value = listOf(
            timing(FakeHighA::class.java, isHighLevel = true, isSuccess = true, durationNanos = 1_500_000L),
            timing(FakeHighB::class.java, isHighLevel = true, isSuccess = false, durationNanos = 250_000_000L),
        )
        val badPixels = renderPixels(SCENE_W, SCENE_H, frameBudgetNanos = FRAME_BUDGET_NS) { StartupTimingsPanel() }
        // 容差计数——「失败」小号字素在细笔画西文字体下无纯色核心像素（本地 Noto/CI DejaVu 字体面不同）
        val errorMarkPx = countColor(badPixels, errorColor, GLYPH_COLOR_TOLERANCE)
        assertTrue(
            errorMarkPx >= 10,
            "失败行必须渲染 error 着色「失败」标记——计数 $errorMarkPx < 10" +
                "（标记缺席 = 失败初始化器在耗时行上不可辨）",
        )
    }

    // ── 格式化纯函数 ─────────────────────────────────────────────────────

    @Test
    fun `millis formatting keeps one decimal place with root locale`() {
        assertEquals("0.0 ms", formatInitMillis(0L), "零耗时必须格式化为 0.0 ms")
        assertEquals("1.5 ms", formatInitMillis(1_500_000L), "1_500_000ns 必须格式化为 1.5 ms")
        assertEquals(
            "123.5 ms",
            formatInitMillis(123_456_789L),
            "123_456_789ns 必须格式化为 123.5 ms（%.1f 四舍五入到一位小数）",
        )
    }

    // ── 门控面源码锚 ─────────────────────────────────────────────────────

    @Test
    fun `panel call is gated by debug page switch and sums group total from nanos`() {
        val text = pageSettingsSource()
        val gateAnchor = text.indexOf("if (debugPageEnabled) {")
        assertTrue(gateAnchor >= 0, "开发者选项门控块缺失（debugPageEnabled 门控回归）")
        val callAnchor = text.indexOf("StartupTimingsPanel()", gateAnchor)
        assertTrue(
            callAnchor >= 0,
            "启动耗时面板调用必须在 debugPageEnabled 门控块内（门控外出现 = 开发者门禁失效）",
        )
        assertTrue(
            callAnchor - gateAnchor < 1_600,
            "面板调用应与直达行同处门控块内（距门控 ${callAnchor - gateAnchor} 字符——漂移出块即门控回归）",
        )
        assertTrue(
            "JInitializerManager.initTimingsFlow.collectAsState()" in text,
            "面板数据源必须订阅 initTimingsFlow（观测面单源，不走任何中转状态）",
        )
        assertTrue(
            "group.sumOf { it.durationNanos }" in text,
            "组合计必须走 sumOf durationNanos 求和口径（错峰 delay 不计入——求和即组级真实初始化开销）",
        )
        assertTrue(
            "timing.initializerClass.simpleName ?: timing.initializerClass.name" in text,
            "类名呈现必须取 Class.simpleName 简名（匿名类 null 回落全限定名）",
        )
    }

    // ── 场景渲染底座：arch 包 SceneGuardSupport 单源（静态面板，短预算参数化） ──

    /** 观测记录 fixture（仅取 Class 简名参与渲染，无初始化行为） */
    private fun timing(
        initializerClass: Class<*>,
        isHighLevel: Boolean,
        isSuccess: Boolean,
        durationNanos: Long,
    ) = InitializerTiming(
        initializerClass = initializerClass,
        isHighLevel = isHighLevel,
        level = 1,
        isSuccess = isSuccess,
        durationNanos = durationNanos,
    )

    private fun pageSettingsSource(): String {
        val file = File(locateProjectRoot(), PAGE_REL_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$PAGE_REL_PATH")
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
        error("无法定位项目根（user.dir=${System.getProperty("user.dir")}}）")
    }

    /** 简名 fixture 类（渲染面断言「类简名 + ms」文案形态的确定性输入） */
    private class FakeHighA
    private class FakeHighB
    private class FakeLowA

    companion object {
        private const val SCENE_W = 400
        private const val SCENE_H = 420

        /** 渲染结算预算 400ms（静态面板无动画，短于单源缺省 1.2s 即收敛；步进真源在 SceneGuardSupport） */
        private const val FRAME_BUDGET_NS = 400_000_000L

        private const val PAGE_REL_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/settings/PageSettings.kt"
    }
}
