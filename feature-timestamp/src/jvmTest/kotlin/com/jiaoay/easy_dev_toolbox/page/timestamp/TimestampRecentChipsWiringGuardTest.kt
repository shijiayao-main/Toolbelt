package com.jiaoay.easy_dev_toolbox.page.timestamp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TL-12 装配面源码锚守护（重设计批 4 · 2026-10-01）：chips 行接线钉死。
 *
 * 数据源 `_recent` 流行为由 [TimestampViewModelRecentTest] 行为验收；
 * [RecentChipsRow] 是 TimestampPage.kt 私有件（页面行为无场景测试接缝），
 * 装配面按 XA-08（ShellToolsViewModelDetectingWiringGuardTest）/ SH-01 先例
 * 用源码锚守护——chips 行被改回无形态（渲染位置漂移/回填链断裂/capsule
 * 丢失/空态渲染）时本守护红：
 * 1. chips 行在结果区（`when (val result = output)` 分支块）**之前**渲染
 *    （卡面「结果区上方」——TL-12 落点勘误 :90 前）；
 * 2. 点 chip 回填 = onTimestampChange + convert 双调用（卡面「回填输入框并
 *    立即 convert」——防抖流等待才出结果不合「立即」语义）；
 * 3. FilterChip 显式 shape = AppShapes.capsule（DS-07 批 3 先例——M3 默认
 *    8dp 角显式归一，DL-M08 轻标签胶囊）；
 * 4. recent 空列表不渲染 chips 行（会话内尚无成功转换不占位）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-timestamp:jvmTest --rerun-tasks --no-build-cache`。
 */
class TimestampRecentChipsWiringGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun pageSource(): String {
        val file = File(
            projectRoot(),
            "feature-timestamp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/timestamp/TimestampPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `chips row renders above result section`() {
        val text = pageSource()
        // 2026-10-04 重锚：TL-D2 收进「双向转换」SectionCard 后缩进 8→12——锚去缩进敏感
        val chipsAnchor = "if (recent.isNotEmpty()) {"
        assertTrue(
            chipsAnchor in text,
            "TL-12 守护：chips 行须以 recent 非空为条件渲染（RecentChipsRow 调用锚漂移）",
        )
        val chipsPos = text.indexOf("RecentChipsRow(recent")
        val resultPos = text.indexOf("when (val result = output)")
        assertTrue(
            chipsPos >= 0 && resultPos >= 0 && chipsPos < resultPos,
            "TL-12 守护：chips 行必须在结果区（when output 分支）之前渲染（卡面「结果区上方」" +
                "——TL-12 落点勘误 :90 前）；chipsPos=$chipsPos resultPos=$resultPos",
        )
    }

    @Test
    fun `chip click refills input and converts immediately`() {
        val text = pageSource()
        // 回填双调用锚：onChipClick lambda 体（源码 12 空格缩进，正则容忍空白漂移）
        val refillAnchor = Regex(
            """onChipClick = \{ value ->\s+viewModel\.onTimestampChange\(value\)\s+viewModel\.convert\(value\)\s+\}""",
        )
        assertTrue(
            refillAnchor.containsMatchIn(text),
            "TL-12 守护：点 chip 必须 = 回填输入框（onTimestampChange）+ 立即 convert 双调用" +
                "（卡面「回填输入框并立即 convert」——只回填等防抖不合「立即」语义）",
        )
    }

    @Test
    fun `chip shape is explicitly capsule`() {
        val text = pageSource()
        assertTrue(
            "shape = AppShapes.capsule" in text,
            "TL-12 守护：RecentChipsRow FilterChip 必须显式 shape = AppShapes.capsule" +
                "（DS-07 批 3 先例：M3 默认 8dp 角显式归一，DL-M08 轻标签胶囊）",
        )
    }

    @Test
    fun `chips row stays private and consumes vm recent flow`() {
        val text = pageSource()
        assertTrue(
            "private fun RecentChipsRow(" in text,
            "TL-12 守护：RecentChipsRow 须为页面私有件（页面内嵌组件收口，不入 :ui 共享件）",
        )
        assertTrue(
            "viewModel.recent.collectAsState()" in text,
            "TL-12 守护：页面须订阅 VM recent 流（渲染数据源 = VM 状态面，非页面自算）",
        )
        assertFalse(
            "RECENT_CAP" in text,
            "TL-12 守护：cap 收口在 VM（convert 记录点 take(RECENT_CAP)），页面不得自截列表" +
                "（双处截断会漂移）",
        )
    }
}
