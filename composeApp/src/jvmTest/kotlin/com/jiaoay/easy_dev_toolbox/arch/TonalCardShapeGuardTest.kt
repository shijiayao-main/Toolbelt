package com.jiaoay.easy_dev_toolbox.arch

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DS-05 守护（重设计批 3 · 2026-10-01）：内容卡默认形态批升的源码锚防线。
 *
 * 批升内容（DL-M05「内容卡默认 AppShapes.large 16dp」+ DL-M06「卡内边距 16×20、
 * 卡距 space14」）：
 * 1. TonalCard 默认 shape = `AppShapes.large`、内边距双参
 *    `contentPaddingVertical = Dimens.space16` / `contentPaddingHorizontal = Dimens.space20`
 *    （旧单参 contentPadding 已删——全仓显式消费点为零，不留兼容重载）；
 * 2. SectionCard tonal 卡 shape = AppShapes.large、同款内边距双参化；
 * 3. FileDropZone border/background 两用点 shape = AppShapes.large；
 * 4. 页级卡距（卡与卡之间）统一 space14——LarkBridgePage 三页签顶层、CockpitPanel
 *    顶层 + grid2、ConfigFieldRenderer 顶层、BridgeStatusPanel 顶层。
 *
 * 守护面 = 源码锚（值档回退到 medium/12dp/10dp 或单参复活即红）；行级容器豁免
 * （StatusBanner/TaskCard/ChatComposer/LogConsole 深底区 6dp 档）不在本守护面，
 * 由 DL-M06 行级容器口径约束。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class TonalCardShapeGuardTest {

    // ── 1. TonalCard：默认 shape + 内边距双参 ──

    @Test
    fun `tonalcard defaults to large shape and dual padding params`() {
        val text = tonalCardFile().readText()

        assertTrue(
            "shape: Shape = AppShapes.large," in text,
            "TonalCard 默认 shape 必须为 AppShapes.large（DS-05：12→16dp，DL-M05 " +
                "内容卡默认档），实际源码未命中。",
        )
        assertTrue(
            "contentPaddingVertical: Dp = Dimens.space16," in text,
            "TonalCard 内边距纵向默认必须为 Dimens.space16（DS-05：DL-M06 卡内边距 16×20）。",
        )
        assertTrue(
            "contentPaddingHorizontal: Dp = Dimens.space20," in text,
            "TonalCard 内边距横向默认必须为 Dimens.space20（DS-05：DL-M06 卡内边距 16×20）。",
        )
        assertFalse(
            "contentPadding: Dp" in text,
            "TonalCard 旧单参 contentPadding 不得复活（DS-05 双参化后全仓显式消费点" +
                "为零，单参回潮即歧义档回归）。",
        )
    }

    // ── 2. SectionCard：tonal 卡 shape + 同款双参 ──

    @Test
    fun `sectioncard tonal shape is large with dual padding params`() {
        val text = sectionCardFile().readText()

        assertTrue(
            "shape = AppShapes.large," in text,
            "SectionCard tonal 卡 shape 必须为 AppShapes.large（DS-05：与 TonalCard " +
                "同族默认升 16dp）。",
        )
        // 2026-10-03 对稿重锚（mock-base.css:332 .zone padding var(--sp-14) var(--sp-20) 逐字）：
        // 垂直 14（旧 16 是 TonalCard 默认档平移——zone 稿面为 14×20）
        assertTrue(
            "contentPaddingVertical: Dp = Dimens.space14," in text,
            "SectionCard 内边距纵向默认必须为 Dimens.space14（2026-10-03 对稿 " +
                "mock-base .zone padding --sp-14 --sp-20 逐字）。",
        )
        assertTrue(
            "contentPaddingHorizontal: Dp = Dimens.space20," in text,
            "SectionCard 内边距横向默认必须为 Dimens.space20（对稿 .zone 水平档）。",
        )
    }

    // ── 3. FileDropZone：border/background 两用点同步 16dp ──

    @Test
    fun `filedropzone border and background shapes are both large`() {
        val text = fileDropZoneFile().readText()

        // 两用点计数：AppShapes.large 应 ≥2（border + background），AppShapes.medium 零残留
        val largeCount = countOccurrences(text, "shape = AppShapes.large")
        assertTrue(
            largeCount >= 2,
            "FileDropZone border 与 background 两用点 shape 必须均为 AppShapes.large" +
                "（DS-05），实际命中 $largeCount 处（卡面实锚 :153-161 双用点）。",
        )
        assertFalse(
            "AppShapes.medium" in text,
            "FileDropZone 不得残留 AppShapes.medium（DS-05：拖放容器随内容卡升 16dp，" +
                "border/background 任一漏改即圆角错位)。",
        )
    }

    // ── 4. 页级卡距 space14（卡与卡之间；卡内/行内距不在守护面）──

    @Test
    fun `page level card gaps are space14 in larkbridge surfaces`() {
        // LarkBridgePage：三页签顶层 Column 同文本——批升后恰 3 处 space14 顶层卡距
        val bridgeText = larkBridgePageFile().readText()
        assertEquals(
            3,
            countOccurrences(bridgeText, "verticalArrangement = Arrangement.spacedBy(Dimens.space14),"),
            "LarkBridgePage 三页签顶层卡距必须为 space14（DS-05：0/1/2 页签顶层 " +
                "Column 三处），当前命中数不符。",
        )

        // CockpitPanel：顶层卡距 + grid2 卡距
        val cockpitText = cockpitPanelFile().readText()
        assertTrue(
            "verticalArrangement = Arrangement.spacedBy(Dimens.space14)," in cockpitText,
            "CockpitPanel 驾驶舱顶层卡距必须为 space14（DS-05）。",
        )
        assertTrue(
            "horizontalArrangement = Arrangement.spacedBy(Dimens.space14)," in cockpitText,
            "CockpitPanel grid2 双卡卡距必须为 space14（DS-05 · XA-17 吸收点：space10→space14）。",
        )

        // ConfigFieldRenderer：配置页顶层卡距（工具条 + 快速上手卡 + 五分区之间）
        assertTrue(
            "verticalArrangement = Arrangement.spacedBy(Dimens.space14)," in
                configFieldRendererFile().readText(),
            "ConfigFieldRenderer 配置页顶层卡距必须为 space14（DS-05）。",
        )

        // BridgeStatusPanel：运行状态顶层卡距
        assertTrue(
            "verticalArrangement = Arrangement.spacedBy(Dimens.space14)," in
                bridgeStatusPanelFile().readText(),
            "BridgeStatusPanel 顶层卡距必须为 space14（DS-05）。",
        )

        // UsageStatsPanel + SenderDetailPanel：统计签页级卡列卡距 space14——
        // 主面板（列表态 / 窄窗回落单列）与二级页（防御空态 / 详情主体）各 2 处
        val statsText = usageStatsPanelFile().readText()
        assertEquals(
            2,
            countOccurrences(statsText, "Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space14))"),
            "UsageStatsPanel 主面板页级卡列卡距必须为 space14（DS-05：列表态 / 窄窗回落 2 处），" +
                "当前命中数不符。",
        )
        // 双列网格列间距同档（Row 横向 space14）
        assertTrue(
            "horizontalArrangement = Arrangement.spacedBy(Dimens.space14)" in statsText,
            "UsageStatsPanel 双列网格列间距必须为 space14（对稿 .grid2 gap）。",
        )
        val statsDetailText = senderDetailPanelFile().readText()
        assertEquals(
            2,
            countOccurrences(statsDetailText, "Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space14))"),
            "SenderDetailPanel 二级页页级卡列卡距必须为 space14（DS-05：防御空态 / 详情主体 2 处），" +
                "当前命中数不符。",
        )
        // D6 · DS-05 收敛：根 Column 段距不得残留 space12（主面板 + 二级页同查）
        assertEquals(
            0,
            countOccurrences(statsText + statsDetailText, "verticalArrangement = Arrangement.spacedBy(Dimens.space12),"),
            "统计签页级段距必须收敛 space14（D6/DS-05：不得残留 space12 页级段距），" +
                "当前命中数不符。",
        )
    }

    // ── 守护锚文件（缺失即红——重命名/移动后须同步本守护路径）──

    private fun tonalCardFile(): File = anchor(UI_WIDGET_PATH + "TonalCard.kt")
    private fun sectionCardFile(): File = anchor(UI_WIDGET_PATH + "SectionCard.kt")
    private fun fileDropZoneFile(): File = anchor(UI_WIDGET_PATH + "FileDropZone.kt")
    private fun larkBridgePageFile(): File = anchor(
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LarkBridgePage.kt",
    )
    private fun cockpitPanelFile(): File = anchor(
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/CockpitPanel.kt",
    )
    private fun configFieldRendererFile(): File = anchor(
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/ConfigFieldRenderer.kt",
    )
    private fun bridgeStatusPanelFile(): File = anchor(
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/status/BridgeStatusPanel.kt",
    )
    private fun usageStatsPanelFile(): File = anchor(
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/UsageStatsPanel.kt",
    )
    private fun senderDetailPanelFile(): File = anchor(
        "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/SenderDetailPanel.kt",
    )

    private fun anchor(path: String): File {
        val file = File(locateProjectRoot(), path)
        assertTrue(file.isFile, "源码锚文件缺失：$path（重命名/移动后请同步本守护）")
        return file
    }

    private fun countOccurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    private companion object {
        const val UI_WIDGET_PATH = "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/"
    }
}
