package com.jiaoay.easy_dev_toolbox.page.larkbridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XA-15 收官守护（终验蜂群仲裁 P1 · 2026-10-02）：cockpit/ 区 contentSoft 深底残留清零
 * 源码锚——与 ShellOutputPanelConsoleGuardTest:144-152（`status row no longer softens
 * content alpha`）同款配方，钉死桥接域另一半。
 *
 * 背景：XA-15 半边施工（roadmap.md 验收「grep 两文件无 contentSoft 残留」）——
 * ShellOutputPanel 侧已清（SH-02 卡），CockpitPanel.RecentTaskRow meta 行两处
 * `onSurfaceVariant.copy(AppAlpha.contentSoft)`（透明合成 0.7）暗色对卡面 ~3.9-4.1:1
 * 低于 AA，已迁 [LocalToolboxSurfaceColors.onSurfaceFaint]（R5 对比度批分治档：
 * 暗 5.9:1 / 亮 5.13:1 达 AA；本文件 :492/:801/:840 消费先例同款）。
 *
 * 断言面 = cockpit/ 子目录（CockpitPanel/CockpitLogic/CockpitPanelLogic 三文件）：
 * FieldRow.kt 的 contextLabel contentSoft **有意保留**——（过滤结果行分区名前缀）
 * 在 tonal SectionCard 浅底卡面（surfaceRaised）上，与 SectionCard.kt:136 description
 * 弱化先例同槽位（SH-02 口径只禁「深底上的 contentSoft」——浅底合法弱化不在此账面）。
 *
 * 🔴 扫盘型守护测试会被 build cache 假绿：验收须
 * `./gradlew :feature-larkbridge:jvmTest --rerun-tasks --no-build-cache`。
 */
class CockpitContentSoftGuardTest {

    @Test
    fun `cockpit sources no longer soften content alpha`() {
        val dir = File(locateProjectRoot(), COCKPIT_DIR)
        assertTrue(dir.isDirectory, "cockpit/ 目录缺失：${dir.path}（重命名/移动后请同步本守护）")
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                // 剥注释后扫描（console guard 同款）——修复注释里的文字提及不算消费
                file.readText().lineSequence()
                    .filterNot { it.trimStart().startsWith("//") }
                    .joinToString("\n")
                    .contains("contentSoft")
            }
            .map { it.relativeTo(locateProjectRoot()).path }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "XA-15 守护：cockpit/ 生产代码 contentSoft 必须清零（深底不可控透明合成禁令——" +
                "SH-02 先例；残留文件：$offenders。浅底弱化走 FieldRow contextLabel 等卡面语境，" +
                "不在本断言面）",
        )
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
        const val COCKPIT_DIR =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit"
    }
}
