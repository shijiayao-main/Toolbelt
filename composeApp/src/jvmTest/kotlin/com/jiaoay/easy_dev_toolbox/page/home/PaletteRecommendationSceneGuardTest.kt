package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.jiaoay.easy_dev_toolbox.arch.assertBandsSeparated
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.renderPixels
import com.jiaoay.easy_dev_toolbox.core.smartdetect.SmartDetection
import com.jiaoay.easy_dev_toolbox.core.smartdetect.SmartDetectionKind
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * palette 置顶推荐区（剪贴板识别 Smart Detection）场景渲染像素带守护：
 * [SceneGuardSupport] 单源配方。检测态注入走生产链——
 * fake [ClipboardManager] 经 [readClipboardForDetection] 驱动 :core 真实探测链产出
 * 命中表，再经 [PaletteEmptyHint] 入参直注推荐区组件（零生产注入口改动；全面板
 * 根部是 Popup 窗口，无头场景不栅格化 Popup——唤起链「唤起读一次」的源锚守护
 * 在 [PaletteSmartDetectionTest]，此处只钉推荐区渲染面）。
 *
 * 验证面（推荐区结构面回归网；审美面归维护者真机终裁）：
 * - 推荐区在场：三态载荷（epoch 文本 / 图片 base64 / GZIP 字节流）命中时
 *   「剪贴板识别」分区头 + 每命中一行进入渲染面（差分带数 = 命中数 + 1）；
 * - 分区独立：检测分区与常用入口分区带间有真背景间隙（叠放/粘连即红）；
 * - 预填态：行 meta 承载 [SmartDetection.preview]（载荷原文/解压首段）——预填
 *   投递链（offer/槽/消费）是交互面非像素面，由 [PaletteSmartDetectionTest]
 *   单测背书，此处不重测；
 * - 无检测态：空命中时检测分区整体缺席（常用入口分区不受影响）；
 * - 匹配失败态：前缀域不进推荐首页——传入命中也不渲染推荐区（同带数差分锚）。
 */
class PaletteRecommendationSceneGuardTest {

    /** 毫秒级 epoch 载荷（13 位纯数字，2001-2096 现实时间窗内） */
    private val epochPayload = "1728547200000"

    /** PNG 魔数载荷的 base64（18 字节 → 24 字符，恰在 base64 长度接受域下限） */
    private val imageBase64Payload: String = Base64.getEncoder().encodeToString(
        byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
        ) + ByteArray(10),
    )

    /** GZIP 字节流按 Latin-1 塞进文本通道（二进制查看器/复制工具的真实形态） */
    private val gzipPayload: String = ByteArrayOutputStream().let { out ->
        GZIPOutputStream(out).use { it.write("gzip 解压首段预览探针载荷".toByteArray()) }
        out.toByteArray().toString(Charsets.ISO_8859_1)
    }

    // ── 推荐区场景内容（[PaletteEmptyHint] 直发；空态数据不拉库——fields 默认空） ──

    @Composable
    private fun HintScene(
        detections: List<SmartDetection>,
        usageCounts: Map<String, Int>,
        prefixMode: PalettePrefix = PalettePrefix.Mixed,
        effectiveQuery: String = "",
        query: String = "",
    ) {
        val emptyState = remember { PaletteEmptyState() }
        PaletteEmptyHint(
            prefixMode = prefixMode,
            effectiveQuery = effectiveQuery,
            query = query,
            emptyState = emptyState,
            usageCounts = usageCounts,
            smartDetections = detections,
            onNavigate = { _, _ -> },
            onPreselectToolsTab = {},
            onSelectSpace = {},
            onDismiss = {},
        )
    }

    /** fake 剪贴板载荷 → 真实探测链命中表（生产同款 [readClipboardForDetection] 调用面） */
    private fun detectionsOf(payloads: List<String>): List<SmartDetection> =
        payloads.flatMap { readClipboardForDetection(FakeClipboard(it)) }

    // ── 用例 ──────────────────────────────────────────────────────────────

    @Test
    fun `detection hits of three payload kinds render as independent top section`() {
        val detections = detectionsOf(listOf(epochPayload, imageBase64Payload, gzipPayload))
        assertEquals(
            listOf(
                SmartDetectionKind.EPOCH_TIMESTAMP,
                SmartDetectionKind.IMAGE_BASE64,
                SmartDetectionKind.GZIP_DATA,
            ).sorted(),
            detections.map { it.kind }.sorted(),
            "前置：三态载荷各产出一条命中（epoch 文本/图片 base64/GZIP 经真实探测链）",
        )

        val usage = mapOf(ToolPage.Tools.name to 5)
        val withPixels = renderPixels(SCENE_W, SCENE_H) { HintScene(detections, usage) }
        val withoutPixels = renderPixels(SCENE_W, SCENE_H) { HintScene(emptyList(), usage) }
        val withBands = contentRows(withPixels, SCENE_W)
        val withoutBands = contentRows(withoutPixels, SCENE_W)

        assertTrue(
            withoutBands.size >= 3,
            "无检测基线应渲染 ≥3 内容带（常用入口分区头/行 + 读取行），实际 ${withoutBands.size}：$withoutBands",
        )
        assertBandsSeparated(withoutBands)
        assertBandsSeparated(withBands)
        assertEquals(
            withoutBands.size + detections.size + 1,
            withBands.size,
            "检测命中应作为新增内容带进入渲染面（分区头 + ${detections.size} 行），实际无检测 ${withoutBands.size} 带" +
                " → 有检测 ${withBands.size} 带——推荐区缺席或分区粘连",
        )
    }

    @Test
    fun `matched failure state keeps recommendation home out of render`() {
        val detections = detectionsOf(listOf(epochPayload))
        assertTrue(detections.isNotEmpty(), "前置：载荷须产出命中（否则差分锚空转）")

        val withPixels = renderPixels(SCENE_W, SCENE_H) {
            HintScene(
                detections = detections,
                usageCounts = emptyMap(),
                prefixMode = PalettePrefix.CommandsOnly,
                effectiveQuery = "zzz",
                query = ">zzz",
            )
        }
        val withoutPixels = renderPixels(SCENE_W, SCENE_H) {
            HintScene(
                detections = emptyList(),
                usageCounts = emptyMap(),
                prefixMode = PalettePrefix.CommandsOnly,
                effectiveQuery = "zzz",
                query = ">zzz",
            )
        }
        val withBands = contentRows(withPixels, SCENE_W)
        val withoutBands = contentRows(withoutPixels, SCENE_W)
        assertTrue(
            withBands.size in 1..2,
            "匹配失败态应只渲染单行精确文案带，实际 ${withBands.size}：$withBands",
        )
        assertEquals(
            withoutBands.size,
            withBands.size,
            "匹配失败态不得渲染推荐区（传入命中也不进场——推荐首页只在 Mixed 空查询可达）",
        )
    }

    private class FakeClipboard(private val text: String?) : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) = Unit
        override fun getText(): AnnotatedString? = text?.let { AnnotatedString(it) }
    }

    companion object {
        private const val SCENE_W = 800
        private const val SCENE_H = 600
    }
}
