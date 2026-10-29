package com.jiaoay.easy_dev_toolbox.host

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GLB-03 守护（重设计批 2 · 2026-09-30）：状态栏胶囊归一 glassBar 12dp。
 *
 * 收口依据：2026-09-29 维护者拍板翻案批 3.5-c「状态栏全圆胶囊」——26dp 高上全圆
 * 渲染即 13dp 半径，与 glassBar 12dp 观感差 1px，拍板从未真正改变观感；本拍板属
 * **账本归一**（消 DL-F11 全宽容器全圆字面违规 + token 注释自相矛盾），非视觉
 * 变更。状态栏与 tab 条（WorkspaceTabs）同族同圆角档（chrome 长条 glassBar）。
 *
 * 守护面：
 * 1. AppStatusBar 状态栏 shape = `AppShapes.glassBar`（chrome 长条档）；
 * 2. `AppShapes.capsule` 在 AppStatusBar 零残留（改回全圆即红）；
 * 3. AppShapes token 注释一致性：capsule 口径不再宣称「用于状态栏」（块注释删
 *    「状态栏」字样），glassBar KDoc 含「状态栏」消费面——两注释互不打架。
 *
 * 范围钉死：StatusHost toast 的 capsule 是横条小件白名单成员，**不在本守护面**
 * （AppShapes.capsule 合法消费点）；超长容器 >720dp 全圆禁令（DL-F11）不受本卡
 * 影响。真机走查若维护者仍偏好全圆端头：按拍板 notes 预案 1 行回退 + DL-F11 登记
 * 豁免（届时本守护随改）。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class AppStatusBarShapeGuardTest {

    @Test
    fun `status bar chrome shape is glassBar not capsule`() {
        val bar = statusBarFile()
        val text = bar.readText()

        // ① 状态栏 shape 走 chrome 长条档（与 tab 条同族同圆角档）
        assertTrue(
            "shape = AppShapes.glassBar" in text,
            "AppStatusBar 状态栏 shape 必须为 AppShapes.glassBar（GLB-03 拍板：12dp chrome 长条，" +
                "与 WorkspaceTabs 同族），实际源码未命中。",
        )

        // ② capsule 引用零残留（批 3.5-c 全圆旧形态回潮即红）
        assertFalse(
            "AppShapes.capsule" in text,
            "AppStatusBar 不得再引用 AppShapes.capsule（GLB-03：状态栏全圆已被 2026-09-29 " +
                "拍板翻案，旧形态回潮即红）。",
        )
    }

    @Test
    fun `appshapes capsule comment no longer claims statusbar and glassbar doc names it`() {
        val shapes = appShapesFile()
        val text = shapes.readText()

        // ③a capsule 块注释口径：状态栏消费面退场（注释与消费面一致，不与 glassBar 打架）
        //     提取 = 锚行所在连续 `//` 行块（行级配方——锚行本身即注释行，前后扩展）
        val lines = text.lines()
        val anchorIdx = lines.indexOfFirst { it.contains(CAPSULE_BLOCK_ANCHOR) }
        assertTrue(
            anchorIdx >= 0,
            "AppShapes capsule 口径块注释缺失——注释锚漂移（$CAPSULE_BLOCK_ANCHOR），守护失效，先修锚。",
        )
        var start = anchorIdx
        while (start > 0 && lines[start - 1].trimStart().startsWith("//")) start--
        var end = anchorIdx
        while (end + 1 < lines.size && lines[end + 1].trimStart().startsWith("//")) end++
        val capsuleBlockComment = lines.subList(start, end + 1).joinToString("\n")
        assertFalse(
            "状态栏" in capsuleBlockComment,
            "AppShapes capsule 块注释不得再宣称「用于状态栏」（GLB-03：状态栏已改走 glassBar，" +
                "capsule 消费面 = 选中 pill/能力 chip/toast 等横条小件）。",
        )

        // ③b glassBar KDoc 消费面含状态栏（chrome 长条两消费面：tab 条 + 状态栏）
        val glassBarDoc = text.substringAfter(GLASS_BAR_ANCHOR, "")
            .substringBefore("val glassBar")
        assertTrue(
            "状态栏" in glassBarDoc,
            "AppShapes.glassBar KDoc 必须点名「状态栏」消费面（GLB-03 终态：tab 条/状态栏同走 chrome 长条档）。",
        )
    }

    // ── 守护锚文件（缺失即红——重命名/移动后须同步本守护路径）──

    private fun statusBarFile(): File {
        val file = File(locateProjectRoot(), APP_STATUS_BAR_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$APP_STATUS_BAR_PATH（重命名/移动后请同步本守护）")
        return file
    }

    private fun appShapesFile(): File {
        val file = File(locateProjectRoot(), APP_SHAPES_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$APP_SHAPES_PATH（重命名/移动后请同步本守护）")
        return file
    }

    private companion object {
        const val APP_STATUS_BAR_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/AppStatusBar.kt"
        const val APP_SHAPES_PATH =
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/theme/AppShapes.kt"

        /** capsule 口径块注释锚（玻璃四级体系块内「胶囊（capsule）口径」行） */
        const val CAPSULE_BLOCK_ANCHOR = "胶囊（capsule）口径"

        /** glassBar KDoc 锚（chrome 长条圆角注释行） */
        const val GLASS_BAR_ANCHOR = "/** chrome 长条圆角"
    }
}
