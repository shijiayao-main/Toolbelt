package com.jiaoay.easy_dev_toolbox.page.larkbridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XA-16 守护（重设计批 3 · 2026-10-01）：剩余秒 pill 洗底对齐 DL-M11 微洗底三档。
 *
 * 同枚两态的 PM 合成裁定：CockpitPanel 剩余秒 pill——超时态 warningContainer.copy(bgSubtle)
 * 合成底直出由 DS-11 承担（该卡收编进 PillLabel），非超时态 onSurface×bgSegTrack 洗底由
 * 本卡（XA-16）承担（改 primary×selectedWash）。两态在同一 PillLabel 调用的 container
 * 双态分支里**原子同改**（同 agent 一次 Edit）——源码改动随 DS-11 commit 物理落盘
 * （同函数区不可拆 hunk），本 commit 落 XA-16 验收锚 + 全仓 bgSegTrack 终验的合并核。
 *
 * 验收面：
 * 1. **非超时态**：primary 系 selectedWash 0.08 微洗底（DL-M11 selected 档）+ 字色 primary
 *    （色源纪律：与 DS-03 tab 选中同族）——替代 onSurface×bgSegTrack 跨语义借档；
 * 2. **超时态**：warningContainer 直出 + warning 字（DS-11 直出，形态不变）；
 * 3. **胶囊形态**：经 PillLabel 渲染（DS-11 收编，capsule 配方不变）。
 *
 * 卡面 scope 列 core/theme/Alpha.kt——selectedWash 批 0 已落（Alpha.kt:154，DS-03），
 * 本卡零 Alpha.kt 改动（侦察确认）。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class CockpitRemainingPillGuardTest {

    @Test
    fun `remaining seconds pill dual state uses selected wash and warning container`() {
        val text = cockpitPanelSource()
        // 剩余秒 pill 块锚（XA-16 与 DS-11 同枚两态原子同改）
        val anchor = "// 同枚两态原子同改"
        val anchorIndex = text.indexOf(anchor)
        assertTrue(
            anchorIndex >= 0,
            "CockpitPanel 剩余秒 pill 双态注释锚缺失（$anchor）——改写后须同步本守护锚。",
        )
        val block = text.substring(anchorIndex, anchorIndex + 1000)
        // 非超时态（XA-16）：primary 系 selectedWash 0.08 微洗底（DL-M11 selected 档）+ 字色 primary
        assertTrue(
            "MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.selectedWash)" in block,
            "剩余秒 pill 非超时态必须 primary×selectedWash 微洗底（XA-16：DL-M11 selected 0.08 档，" +
                "替代 onSurface×bgSegTrack 跨语义借档）",
        )
        // 超时态（DS-11）：warningContainer 直出（双洗底清零）+ warning 字
        assertTrue(
            "colors.warningContainer" in block,
            "剩余秒 pill 超时态必须 warningContainer 直出（DS-11：双洗底 copy(bgSubtle) 清零）",
        )
        assertTrue(
            "colors.warning else MaterialTheme.colorScheme.primary" in block,
            "剩余秒 pill 字色双态：超时态 warning / 非超时态 primary（XA-16 字色对齐 primary 系）",
        )
        assertFalse(
            "AppAlpha.bgSegTrack" in block,
            "剩余秒 pill 不得再出现 AppAlpha.bgSegTrack 引用（XA-16 验收：非法挪用清零——施工注释的文字提及不算）",
        )
        assertFalse(
            "warningContainer.copy" in block,
            "剩余秒 pill 不得再出现 warningContainer.copy（DS-11 验收：双洗底清零）",
        )
        // 胶囊形态不变（收编进 PillLabel 后仍须 capsule 配方）
        assertTrue(
            "PillLabel(" in block,
            "剩余秒 pill 必须经 PillLabel 渲染（DS-11 收编——胶囊配方共享化）",
        )
    }

    /** 定位 CockpitPanel.kt 源文件（自测试工作目录向上找 settings.gradle.kts 定项目根） */
    private fun cockpitPanelSource(): String {
        val root = locateProjectRoot()
        val file = File(root, COCKPIT_PANEL)
        assertTrue(file.isFile, "$COCKPIT_PANEL 不存在——文件被移动须同步更新本测试路径")
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
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val COCKPIT_PANEL =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/CockpitPanel.kt"
    }
}
