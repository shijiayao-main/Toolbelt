package com.jiaoay.easy_dev_toolbox.page.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 结构带消费位封闭集守护（「结构带不上文字承载面」的机械化）：
 * 结构带对昼底 2.81 不过图形线，带上不放图形件/正文——消费位封闭集 = 侧栏轨
 * （PageHomeMenu dock 轨）+ 页签栏（WorkspaceTabs tab 条）两条常驻实底带，
 * 带上前景统一结构带配套字链。封闭集外的一切 chrome/正文承载面（状态栏/标题栏/
 * 命令面板/toast）不得出现 structureBand 消费（回潮即红）。
 * 验收面 = 设置页眯眼三色可辨（紫底板/绿激活件/近黑井缝）的带状语法仅此两带
 * （判据原文「绿结构带或激活件」——电光紫昼战验收基线）。
 */
class StructureBandConsumersGuardTest {

    private companion object {
        const val SRC = "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox"

        /** 消费位封闭集（两条常驻实底带） */
        val CONSUMERS = listOf("page/home/PageHomeMenu.kt", "page/home/WorkspaceTabs.kt")

        /** 文字承载面黑名单（结构带禁入） */
        val TEXT_CARRIERS = listOf(
            "host/AppStatusBar.kt",
            "host/AppTitleBar.kt",
            "page/home/CommandPalette.kt",
            "page/home/PaletteEntries.kt",
            "host/StatusHost.kt",
        )
    }

    @Test
    fun `structure band consumers are the closed rail and tab bar set`() {
        CONSUMERS.forEach { rel ->
            val text = pageSource(rel)
            assertTrue(
                "structureBandActive" in text && "structureBand" in text,
                "$rel 必须消费结构带三元组（底/配套字/激活态）——结构带缺席即验收判据失效。",
            )
            assertTrue(
                "onStructureBand" in text,
                "$rel 带面前景必须走结构带配套字链（带上前景统一配套字档）。",
            )
        }
    }

    @Test
    fun `structure band never reaches text carrying surfaces`() {
        // 🔴「不上文字承载面」纪律的机械化：结构带是面积构成装饰带（对昼底 2.81
        // 不过图形线），正文/图形承载面消费回潮即红。
        TEXT_CARRIERS.forEach { rel ->
            assertTrue(
                "structureBand" !in pageSource(rel),
                "$rel 不得消费结构带——结构带不上文字承载面（对昼底 2.81 破图形线，" +
                    "消费位封闭集 = 侧栏轨 + 页签栏）。",
            )
        }
    }

    private fun pageSource(rel: String): String {
        val file = File(locateProjectRoot(), "$SRC/$rel")
        assertTrue(file.isFile, "源码锚文件缺失：$rel（重命名/移动后请同步本守护）")
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
        error("无法定位项目根（user.dir=${System.getProperty("user.dir")}）")
    }
}
