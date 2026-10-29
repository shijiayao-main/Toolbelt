package com.jiaoay.easy_dev_toolbox.page.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle
import com.jiaoay.easy_dev_toolbox.arch.assertBandsNotOverlapping
import com.jiaoay.easy_dev_toolbox.arch.clickAt
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.countColor
import com.jiaoay.easy_dev_toolbox.arch.GLYPH_COLOR_TOLERANCE
import com.jiaoay.easy_dev_toolbox.arch.probeSchemeColor
import com.jiaoay.easy_dev_toolbox.arch.probeWashColor
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodeBounds
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodes
import com.jiaoay.easy_dev_toolbox.arch.typeText
import com.jiaoay.easy_dev_toolbox.core.navigation.SmartPrefillHook
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.page.tools.base64.Base64ExtractorPanel
import com.jiaoay.easy_dev_toolbox.page.tools.json.JsonToolPanel
import com.jiaoay.easy_dev_toolbox.page.tools.regex.RegexTesterPanel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「工具」聚合页三签（正则测试器 / base64 提取 / JSON 工具）场景渲染像素守护：
 * [SceneGuardSupport] 单源配方。三签面板均为聚合页原生签——无 VM 无持久化，
 * 注入形态按面板既有输入缝分三路：
 * - 正则签：无预填缝——键盘注入全真链（[clickAt] 依 [semanticsNodeBounds] 聚焦
 *   输入框 + [typeText] 逐字符 AWT KEY_TYPED）；
 * - base64 / JSON 签：[SmartPrefillHook] 生产预填缝（palette 剪贴板推荐 → 目标签
 *   输入区的真实通道，组合期初值消费）；
 * - base64 候选点击：[clickAt] 指针注入（候选行 = 末个厚带——卡底描边薄带之上的
 *   最低内容带）。
 *
 * 验证面（正则签：命中高亮 + 分组捕获）：
 * - 键入语义读回：正则/样本键入内容经 EditableText 语义读回对账（typed 事件链
 *   断裂即读回不等红——像素差分受 placeholder 退场污染，禁用）；
 * - 命中高亮：命中区间着色 = primary×hotWash 洗底合成色（[probeWashColor] 探针
 *   + [countColor] 精确计数——AnnotatedString 着色链断裂即红）；
 * - 分组捕获：命中行 #N 序号 primary 精确色计数限定在样本文本框以下区域（命中
 *   预览/分组捕获两卡所在——排除聚焦文本框的 primary 焦点描边污染）；
 * - 带数只作 ≥ 地板 + 不倒挂（输入框/密排区内带会粘连漂移，等值与互斥断言禁用）。
 *
 * 验证面（base64 签：魔数预览）：
 * - 嗅探候选行在场（#N primary 序号精确色）；
 * - 点击候选后预览卡进渲染面（带数增量为正）+ 纯色 PNG 内容像素进渲染面
 *   （魔数识别 → skia 解码 → 内联缩放全链断裂即红；解码在 Dispatchers.Default
 *   异步落定，泵至落定）。
 *
 * 验证面（JSON 签：格式化错位回执）：
 * - 坏 JSON 错位回执以 error 精确色进渲染面（回执文案的行列定位由引擎单测钉死，
 *   场景层只钉呈现面）；
 * - 换投有效 JSON 后错位回执整面退场（error 着色归零）+ 格式化输出区进渲染面
 *   （带数增量为正）。
 *
 * 排版密度/配色气质归维护者真机终裁，本守护只钉结构面。
 */
class ToolsPanelsSceneGuardTest {

    @AfterTest
    fun tearDown() {
        SmartPrefillHook.clearForTest()
    }

    /** 宿主形态镜像 ToolsPage：Column spacedBy(12) 承载各签 SectionCard（卡间带隙来源） */
    @Composable
    private fun panelHost(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Dimens.space16),
            verticalArrangement = Arrangement.spacedBy(Dimens.space12),
        ) {
            content()
        }
    }

    /** 样本文本框以下区域的内容像素限定计数（区域下界 = 样本框底 + 1px） */
    private fun countColorBelowRow(
        pixels: Array<IntArray>,
        color: Int,
        belowYExclusive: Int,
        tolerance: Int = 0,
    ): Int = countColor(
        pixels.sliceArray((belowYExclusive + 1) until pixels.size),
        color,
        tolerance,
    )

    // ── 正则签：命中高亮 + 分组捕获 ─────────────────────────────────────

    @Test
    fun `regex panel renders hit highlight and group capture after typing`() {
        val primary = probeSchemeColor { MaterialTheme.colorScheme.primary }
        val highlight = probeWashColor(
            base = { LocalToolboxSurfaceColors.current.surfaceRaised },
            wash = { MaterialTheme.colorScheme.primary },
        )

        SceneGuardHandle(SCENE_W, SCENE_H).use { handle ->
            handle.setContent { panelHost { RegexTesterPanel() } }
            // 首帧：semantics 树随组合生成（输入框定位/读回的前提）
            handle.renderPixels()

            // 聚焦正则输入框（语义树第 0 个文本框），键入正则
            val patternField = handle.semanticsNodeBounds(matcher = editableMatcher, index = 0)
            handle.clickAt(x = patternField.center.x, y = patternField.center.y)
            handle.typeText(REGEX_PATTERN)
            handle.renderPixels()
            assertEquals(
                REGEX_PATTERN,
                editableTextAt(handle, index = 0),
                "正则键入未生效（聚焦/typed 事件链断裂）",
            )

            // 聚焦样本文本框（语义树第 1 个文本框——行带几何会被 label/回执带形污染，禁用），键入样本
            val sampleField = handle.semanticsNodeBounds(matcher = editableMatcher, index = 1)
            handle.clickAt(x = sampleField.center.x, y = sampleField.center.y)
            handle.typeText(REGEX_SAMPLE)
            val typedPx = handle.renderPixels()
            assertEquals(
                REGEX_SAMPLE,
                editableTextAt(handle, index = 1),
                "样本键入未生效（聚焦/typed 事件链断裂）",
            )

            val bands = contentRows(typedPx, SCENE_W)
            assertBandsNotOverlapping(bands)
            assertTrue(
                bands.size >= MATCHED_BANDS_MIN,
                "命中预览 + 分组捕获两卡应进渲染面（带数 ${bands.size} < $MATCHED_BANDS_MIN" +
                    "——命中卡渲染链断裂）：$bands",
            )
            assertTrue(
                countColor(typedPx, highlight) >= HIGHLIGHT_PX_MIN,
                "命中区间未着高亮洗底（探针色计数 ${countColor(typedPx, highlight)} < " +
                    "$HIGHLIGHT_PX_MIN——AnnotatedString 命中着色链断裂或零命中）",
            )
            // 分组捕获行 #N 序号：限定样本文本框以下区域计数（聚焦框 primary 焦点描边在上方，排除）；
            // 容差计数——#N 小号字素在细笔画西文字体下无纯色核心像素（本地 Noto/CI DejaVu 字体面不同）
            val matchRowPrimaryPx =
                countColorBelowRow(typedPx, primary, sampleField.bottom.toInt(), GLYPH_COLOR_TOLERANCE)
            assertTrue(
                matchRowPrimaryPx >= MATCH_ROW_PRIMARY_MIN,
                "分组捕获命中行 #N 序号未着 primary（区域计数 $matchRowPrimaryPx < " +
                    "$MATCH_ROW_PRIMARY_MIN——MatchRow 缺席或零命中）",
            )
        }
    }

    /** 文本框语义判定（SetText 动作键——M3 文本框的编辑语义锚） */
    private val editableMatcher: (androidx.compose.ui.semantics.SemanticsNode) -> Boolean = {
        it.config.getOrNull(SemanticsActions.SetText) != null
    }

    /** 文本框语义读回（EditableText 属性——键入内容的生产语义面，像素差分的稳锚替身） */
    private fun editableTextAt(handle: SceneGuardHandle, index: Int): String? =
        handle.semanticsNodes(matcher = editableMatcher)
            .getOrNull(index)
            ?.config
            ?.getOrNull(SemanticsProperties.EditableText)
            ?.text

    // ── base64 签：魔数预览 ─────────────────────────────────────────────

    @Test
    fun `base64 panel sniffs png candidate and renders magic preview on click`() {
        val primary = probeSchemeColor { MaterialTheme.colorScheme.primary }
        SmartPrefillHook.offer(
            SmartPrefillHook.Payload.Base64Text("snapshot thumb: ${solidPngBase64(PNG_RGB)} , eof"),
        )

        SceneGuardHandle(SCENE_W, SCENE_H).use { handle ->
            handle.setContent { panelHost { Base64ExtractorPanel() } }
            val sniffedPx = handle.renderPixels()
            val sniffedBands = contentRows(sniffedPx, SCENE_W)
            // 点击首个候选（候选行 = 末个厚带：卡底描边是 1-2px 薄带，候选行 ≥8px 厚）
            val candidateRow = sniffedBands.last { it.last - it.first + 1 >= CANDIDATE_ROW_MIN_HEIGHT }
            // 容差计数限定候选行区域——#N 小号字素在细笔画西文字体下无纯色核心像素
            val candidatePrimaryPx =
                countColor(sniffedPx.sliceArray(candidateRow), primary, GLYPH_COLOR_TOLERANCE)
            assertTrue(
                candidatePrimaryPx >= CANDIDATE_PRIMARY_MIN,
                "嗅探候选行 #N 序号未着 primary（区域计数 $candidatePrimaryPx < " +
                    "$CANDIDATE_PRIMARY_MIN——候选未进渲染面）",
            )
            handle.clickAt(x = 120f, y = (candidateRow.first + candidateRow.last) / 2f)

            // 解码在 Dispatchers.Default 异步落定（produceState 后台解码）——泵至预览落定
            var settledPx: Array<IntArray>? = null
            var attempts = 0
            while (settledPx == null && attempts < DECODE_WAIT_ATTEMPTS) {
                val px = handle.renderPixels()
                if (countColor(px, PNG_RGB) >= PNG_SOLID_MIN) {
                    settledPx = px
                } else {
                    Thread.sleep(DECODE_WAIT_MS)
                    attempts++
                }
            }
            val previewPx = settledPx
                ?: error("魔数预览未在预算内落定（魔数识别/skia 解码/内联缩放链断裂或异步超时）")
            val previewBands = contentRows(previewPx, SCENE_W)
            assertTrue(
                previewBands.size > sniffedBands.size,
                "点击候选后应多出预览卡各带（魔数预览缺席）——点击前 ${sniffedBands.size} 带 / " +
                    "点击后 ${previewBands.size} 带",
            )
        }
    }

    /** 纯色 PNG 的 base64（魔数识别 + skia 解码全真链载荷；headless 下 ImageIO 可用） */
    private fun solidPngBase64(rgb: Int): String {
        val image = BufferedImage(PNG_SIZE, PNG_SIZE, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until PNG_SIZE) {
            for (x in 0 until PNG_SIZE) image.setRGB(x, y, rgb)
        }
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "PNG", out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    // ── JSON 签：格式化错位回执 ─────────────────────────────────────────

    @Test
    fun `json panel renders invalid receipt then swaps to formatted output`() {
        val errorColor = probeSchemeColor { MaterialTheme.colorScheme.error }
        SmartPrefillHook.offer(SmartPrefillHook.Payload.JsonText(BAD_JSON))

        SceneGuardHandle(SCENE_W, SCENE_H).use { handle ->
            handle.setContent { panelHost { JsonToolPanel() } }
            val badPx = handle.renderPixels()
            val badBands = contentRows(badPx, SCENE_W)
            assertTrue(
                badBands.size >= 4,
                "坏 JSON 态应渲染标题/输入框/错位回执/查询卡各带，实际 ${badBands.size}：$badBands",
            )
            assertBandsNotOverlapping(badBands)
            assertTrue(
                countColor(badPx, errorColor) >= RECEIPT_ERROR_MIN,
                "错位回执未以错误色进渲染面（计数 ${countColor(badPx, errorColor)} < " +
                    "$RECEIPT_ERROR_MIN——坏 JSON 人话回执缺席）",
            )

            // 换投有效 JSON：面板驻留订阅（pending collect）承接第二次投递
            SmartPrefillHook.offer(SmartPrefillHook.Payload.JsonText(GOOD_JSON))
            val goodPx = handle.renderPixels()
            val goodBands = contentRows(goodPx, SCENE_W)
            assertEquals(
                0,
                countColor(goodPx, errorColor),
                "有效 JSON 后错位回执应整面退场（error 着色残留即回执态泄漏）",
            )
            assertTrue(
                goodBands.size >= badBands.size + 1,
                "有效 JSON 应多出格式化输出区（输出行/复制钮未进渲染面）——" +
                    "坏 ${badBands.size} 带 / 好 ${goodBands.size} 带",
            )
        }
    }

    companion object {
        private const val SCENE_W = 1096
        private const val SCENE_H = 900

        /** 正则键入载荷：含一个捕获分组（分组捕获视图的分组编号前提） */
        private const val REGEX_PATTERN = """\[(\w+)\]"""

        /** 样本载荷：两处命中（命中高亮与分组捕获各有两行可数） */
        private const val REGEX_SAMPLE = "[INFO] alpha [WARN] beta"

        /** 坏 JSON：双逗号语法错位（引擎回执带行列定位） */
        private const val BAD_JSON = """{"a": 1,, "b": 2}"""

        /** 有效 JSON：多行格式化输出（输出区进渲染面的载荷前提） */
        private const val GOOD_JSON = """{"a": 1, "b": [1, 2]}"""

        /** 探针 PNG 尺寸与内容色（纯色实心——skia 解码后精确色可数） */
        private const val PNG_SIZE = 64
        private const val PNG_RGB = 0xC0392B

        /** 命中态带数地板（两卡标题/正文/命中行/分组行的合计下限） */
        private const val MATCHED_BANDS_MIN = 11

        /** 命中高亮洗底精确色计数下限（两处命中 × 六字符区间着色） */
        private const val HIGHLIGHT_PX_MIN = 200

        /** 分组捕获行 #N 序号 primary 精确色计数下限（两命中行，区域限定计数） */
        private const val MATCH_ROW_PRIMARY_MIN = 12

        /** 候选行 #N 序号 primary 精确色计数下限 */
        private const val CANDIDATE_PRIMARY_MIN = 8

        /** 候选行最小带厚（卡底描边薄带之下的判别线） */
        private const val CANDIDATE_ROW_MIN_HEIGHT = 8

        /** 纯色 PNG 内容像素计数下限（魔数预览内联图像在场） */
        private const val PNG_SOLID_MIN = 400

        /** 异步解码落定等待（重泵次数 × 步长；真实解码远快于此预算） */
        private const val DECODE_WAIT_ATTEMPTS = 10
        private const val DECODE_WAIT_MS = 100L

        /** 错位回执 error 精确色计数下限（主行 + 行列定位回执） */
        private const val RECEIPT_ERROR_MIN = 60
    }
}
