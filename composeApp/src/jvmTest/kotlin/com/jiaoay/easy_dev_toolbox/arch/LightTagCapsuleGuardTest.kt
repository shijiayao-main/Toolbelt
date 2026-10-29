package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DS-07 守护（重设计批 3 · 2026-10-01）：轻标签胶囊收编——ui/composeApp 域。
 *
 * 收编依据（DL-M08 / 裁决 6「真缺口 = 轻标签类收编胶囊」）：chip 家族圆角语言
 * 分裂——按钮已是全圆胶囊、chip 仍 6/8dp 角。本卡把 ui 域轻标签收编
 * [AppShapes.capsule]（既有 token，守护值级白名单零改动）：
 *
 * 1. ActivityChip 自绘 chip 底（AppShapes.small → capsule；pulse 动画零动）；
 * 2. EvidenceTimeline TAG chip（实锚 EvidenceLineRow——行渲染件已自 EvidenceTimeline
 *    独立成文件）；
 * 3. ChoiceField（≥4 项 FlowRow 标签流）/ MultiChoiceField / PaletteParamForm（CHOICE
 *    单选组）三处 FilterChip 显式传 shape = AppShapes.capsule（M3 FilterChip 默认
 *    8dp 角——DL 分层条款要求轻标签显式归一，防默认角回潮）。
 *
 * 分层条款反向守护（DL-F10/F11，卡面 acceptance「无容器卡/输入框被误改胶囊」）：
 * - F10：多行输入框禁胶囊——ChatComposer / CommitTextField 恒圆角档，零 capsule；
 * - F11：容器卡禁全圆——TonalCard / SectionCard（容器卡族）零 capsule。
 *   （>720dp 判定不可稳定 grep——维度以「容器卡族文件零 capsule 引用」钉住。）
 *
 * 🔴 桥接域部分（ConfigFieldRenderer :256/:265 两枚 FilterChip + LogConsolePanel
 *    :189）由波次 2 同卡后续施工，不在本守护面（cap 断言只覆盖 ui/composeApp 域文件）。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class LightTagCapsuleGuardTest {

    // ── ① 轻标签收编：capsule 在位断言（缺失即红——chip 退回 6/8dp 角即违 DL-M08）──

    @Test
    fun `activity chip background shape is capsule`() {
        val text = file(UI_ACTIVITY_CHIP).readText()
        assertTrue(
            "AppShapes.capsule" in text,
            "ActivityChip chip 底必须 AppShapes.capsule（DS-07：small → capsule；" +
                "chip 底退回 AppShapes.small 即违 DL-M08 轻标签胶囊收编）。",
        )
        // 配对锚：capsule 只该出现在 chip 底一处（pulse 动画绿点是 CircleShape 不动）
        assertEquals(
            1,
            text.split("AppShapes.capsule").size - 1,
            "ActivityChip 应恰 1 处 AppShapes.capsule（chip 底）——多处出现说明挪作他档，核对。",
        )
    }

    @Test
    fun `evidence timeline tag chip background shape is capsule`() {
        // EvidenceLineRow 自 EvidenceTimeline 拆出独立成文件——TAG chip 实体随迁，锚重指。
        // 2026-10-14 chip 族收口更新：TAG chip 换件 ChipTextButton 单源（capsule 几何由
        // ChipButton.kt 内部承载），本文件锚「surface 中性底 + monoTag 字档」两参数在位
        val text = file(UI_EVIDENCE_LINE_ROW).readText()
        assertTrue(
            "ChipTextButton(" in text,
            "TAG chip 必须经 ChipTextButton 单源渲染（chip 族收口——capsule/描边/hover 洗底配方单源）。",
        )
        // TAG chip 用点：中性面 = surface 底（非 surfaceRaised——mock-base.css:212 .tag 稿面逐字）
        assertTrue(
            "container = LocalToolboxSurfaceColors.current.surface" in text,
            "TAG chip 容器必须 surface 中性底（mock-base.css:212 .tag 逐字——非主题染色）。",
        )
        assertTrue(
            "textStyle = DesktopTypography.monoTag" in text,
            "TAG chip 字档必须保留 monoTag（mono 标签语义——不随换件漂移）。",
        )
        // capsule 几何单源锚：ChipTextButton 档在 ChipButton.kt 内恒 capsule
        val chipButton = file(UI_CHIP_BUTTON).readText()
        assertTrue(
            "shape = AppShapes.capsule" in chipButton,
            "ChipTextButton 档必须 AppShapes.capsule（DL-M08 轻标签胶囊收编——TAG chip 换件后的 capsule 真源）。",
        )
        // 🔴 行高亮底（selectionHoverWashColor，无 shape 满宽矩形）不得被顺手改胶囊——
        //    那是行高亮不是 chip（DL-F10/F11 分层核对项）
        assertFalse(
            "AppShapes.capsule" in text,
            "EvidenceLineRow 本体不得再手写 AppShapes.capsule（行高亮底是满宽矩形非 chip——capsule 几何已单源 ChipButton.kt）。",
        )
    }

    @Test
    fun `choice field filter chips pass capsule shape explicitly`() {
        val text = file(UI_CHOICE_FIELD).readText()
        assertTrue(
            "shape = AppShapes.capsule" in text,
            "ChoiceField ≥4 项 FilterChip 必须显式 shape = AppShapes.capsule（DS-07：M3 默认 8dp 角归一）。",
        )
    }

    @Test
    fun `multi choice field filter chips pass capsule shape explicitly`() {
        val text = file(UI_MULTI_CHOICE_FIELD).readText()
        assertTrue(
            "shape = AppShapes.capsule" in text,
            "MultiChoiceField FilterChip 必须显式 shape = AppShapes.capsule（DS-07：M3 默认 8dp 角归一）。",
        )
    }

    @Test
    fun `palette param form choice chips pass capsule shape explicitly`() {
        val text = file(APP_PALETTE_PARAM_FORM).readText()
        assertTrue(
            "shape = AppShapes.capsule" in text,
            "PaletteParamForm CHOICE 单选组 FilterChip 必须显式 shape = AppShapes.capsule（DS-07）。",
        )
    }

    // ── ② 分层反向守护（DL-F10/F11）：容器卡/多行输入框零 capsule ──

    @Test
    fun `multi line inputs never use capsule - DL-F10`() {
        for (path in listOf(UI_CHAT_COMPOSER, UI_COMMIT_TEXT_FIELD)) {
            val text = file(path).readText()
            assertFalse(
                "AppShapes.capsule" in text,
                "$path 不得引用 AppShapes.capsule（DL-F10：多行输入框恒圆角档——" +
                    "caret 起读对称 + 纵向拉伸香肠感，胶囊禁用）。",
            )
        }
    }

    @Test
    fun `container cards never use capsule - DL-F11`() {
        for (path in listOf(UI_TONAL_CARD, UI_SECTION_CARD)) {
            val text = file(path).readText()
            assertFalse(
                "AppShapes.capsule" in text,
                "$path 不得引用 AppShapes.capsule（DL-F11：容器卡禁全圆——香肠感边界条款保留）。",
            )
        }
    }

    // ── 锚文件（缺失即红——重命名/移动后须同步本守护路径）──

    private fun file(relPath: String): File {
        val file = File(locateProjectRoot(), relPath)
        assertTrue(file.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return file
    }

    private companion object {
        const val UI_ACTIVITY_CHIP =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/chat/ActivityChip.kt"
        const val UI_EVIDENCE_LINE_ROW =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/evidence/EvidenceLineRow.kt"
        const val UI_CHIP_BUTTON =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/ChipButton.kt"
        const val UI_CHOICE_FIELD =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/ChoiceField.kt"
        const val UI_MULTI_CHOICE_FIELD =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/MultiChoiceField.kt"
        const val UI_CHAT_COMPOSER =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/chat/ChatComposer.kt"
        const val UI_COMMIT_TEXT_FIELD =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/CommitTextField.kt"
        const val UI_TONAL_CARD =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/TonalCard.kt"
        const val UI_SECTION_CARD =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/SectionCard.kt"
        const val APP_PALETTE_PARAM_FORM =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteParamForm.kt"
    }
}
