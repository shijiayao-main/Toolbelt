package com.jiaoay.easy_dev_toolbox.page.larkbridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 轻标签胶囊收编守护——**桥接域部分**。
 *
 * 与 composeApp 侧 [com.jiaoay.easy_dev_toolbox.arch.LightTagCapsuleGuardTest]
 * （ui/composeApp 域五点）共同完成全卡——本测试类只覆盖桥接域三枚
 * FilterChip 调用点：
 * 1. ConfigFieldRenderer L0 工具条 @modified/@restart 两枚过滤 chip
 *    （chip 点击 = 把保留字写进搜索框的过滤态管线）；
 * 2. LogConsolePanel 级别多选 chip（E/W/I/D）。
 *
 * 三枚均显式传 `shape = AppShapes.capsule`（轻标签类收编胶囊——M3 FilterChip
 * 默认 8dp 角显式归一，防默认角回潮）。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class LarkBridgeLightTagCapsuleGuardTest {

    @Test
    fun `config toolbar filter chips pass capsule shape explicitly`() {
        val text = file(FIELD_RENDERER).readText()
        val anchor = "FilterChip("
        assertTrue(
            anchor in text,
            "ConfigFieldRenderer FilterChip 锚漂移——工具条 @modified/@restart chip 改写后须同步本守护。",
        )
        // 两枚保留字 chip（@modified / @restart）各须有一枚 capsule 显式传参：
        // 以 chip label 锚定各自调用块，检查同块内 capsule 在位。
        for (mention in listOf("@modified", "@restart")) {
            val labelAnchor = "label = { Text(\"$mention ·"
            val labelIndex = text.indexOf(labelAnchor)
            assertTrue(
                labelIndex >= 0,
                "ConfigFieldRenderer $mention chip label 锚缺失（$labelAnchor）——改写后须同步本守护锚。",
            )
            // label 所在 FilterChip 调用块 = 自 label 向前回溯到最近的 "FilterChip(" 起点
            val chipStart = text.lastIndexOf("FilterChip(", labelIndex)
            assertTrue(
                chipStart >= 0,
                "$mention chip 调用块定位失败（label 前无 FilterChip(）。",
            )
            val block = text.substring(chipStart, labelIndex)
            assertTrue(
                "shape = AppShapes.capsule" in block,
                "$mention FilterChip 必须显式 shape = AppShapes.capsule" +
                    "（M3 默认 8dp 角归一，轻标签胶囊收编）。",
            )
        }
    }

    @Test
    fun `log console level chips pass capsule shape explicitly`() {
        val text = file(LOG_CONSOLE_PANEL).readText()
        val anchor = "LEVELS.forEach { level ->"
        val forEachIndex = text.indexOf(anchor)
        assertTrue(
            forEachIndex >= 0,
            "LogConsolePanel LEVELS chip 块锚漂移（$anchor 未命中）——改写后须同步本守护锚。",
        )
        val block = text.substring(forEachIndex, forEachIndex + 900)
        assertTrue(
            "shape = AppShapes.capsule" in block,
            "LogConsolePanel 级别多选 FilterChip 必须显式 shape = AppShapes.capsule（轻标签胶囊收编）。",
        )
    }

    // ── 锚文件（缺失即红——重命名/移动后须同步本守护路径）──

    private fun file(relPath: String): File {
        val file = File(projectRoot(), relPath)
        assertTrue(file.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return file
    }

    private fun projectRoot(): File {
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
        const val FIELD_RENDERER =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/ConfigFieldRenderer.kt"
        const val LOG_CONSOLE_PANEL =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/log/LogConsolePanel.kt"
    }
}
