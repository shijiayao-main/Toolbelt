package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.ClipboardManager
import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import com.jiaoay.easy_dev_toolbox.core.navigation.SmartPrefillHook
import com.jiaoay.easy_dev_toolbox.core.smartdetect.SmartDetection
import com.jiaoay.easy_dev_toolbox.core.smartdetect.SmartDetectionKind
import com.jiaoay.easy_dev_toolbox.page.tools.ToolsConfigStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 剪贴板识别推荐区接线单测：落点映射（kind → 工具页子签锚）、剪贴板读取
 * 容错（读不到静默无推荐）、预填链（载荷映射/槽语义/消费接缝源锚）、
 * 与既有候选/排序/选中逻辑互不干扰的零回归锚。
 * 探测器本体（误报纪律/预览构造/预填载荷）单测在 :core smartdetect 域，此处不重测。
 */
class PaletteSmartDetectionTest {

    @AfterTest
    fun tearDown() {
        // 槽是进程级单例，残留载荷会跨用例串台——逐用例清场
        SmartPrefillHook.clearForTest()
    }

    // ── 落点映射 ──

    @Test
    fun `detection kinds map to tools page tab anchors`() {
        assertEquals(SmartDetectionTarget("时间戳", 3), smartDetectionTargetOf(SmartDetectionKind.EPOCH_TIMESTAMP))
        assertEquals(SmartDetectionTarget("XLog 解密", 0), smartDetectionTargetOf(SmartDetectionKind.XLOG_PATH))
        assertEquals(SmartDetectionTarget("JSON 工具", 7), smartDetectionTargetOf(SmartDetectionKind.JSON_OBJECT))
        assertEquals(SmartDetectionTarget("Base64 提取", 8), smartDetectionTargetOf(SmartDetectionKind.BASE64_TEXT))
        assertEquals(SmartDetectionTarget("解压", 4), smartDetectionTargetOf(SmartDetectionKind.GZIP_DATA))
        assertEquals(
            SmartDetectionTarget("Base64 提取（图片）", 8),
            smartDetectionTargetOf(SmartDetectionKind.IMAGE_BASE64),
        )
        // 子签锚与 ToolsConfigStore 常量单源对齐（防映射表内漂移出第二套 index）
        assertEquals(0, ToolsConfigStore.TAB_XLOG_DECODE)
        assertEquals(3, ToolsConfigStore.TAB_TIMESTAMP)
        assertEquals(4, ToolsConfigStore.TAB_ARCHIVE)
        assertEquals(7, ToolsConfigStore.TAB_JSON_TOOL)
        assertEquals(8, ToolsConfigStore.TAB_BASE64_EXTRACT)
    }

    // ── 预填载荷映射 ──

    @Test
    fun `smart detections map to prefill payloads for wired kinds and degrade silently when missing`() {
        assertEquals(
            SmartPrefillHook.Payload.EpochValue("1728547200"),
            smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.EPOCH_TIMESTAMP, 90, "1728547200", "1728547200")),
        )
        assertEquals(
            SmartPrefillHook.Payload.XLogFilePath("/tmp/a.xlog"),
            smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.XLOG_PATH, 95, "/tmp/a.xlog", "/tmp/a.xlog")),
        )
        assertEquals(
            SmartPrefillHook.Payload.JsonText("{\"a\": 1}"),
            smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.JSON_OBJECT, 90, "{\"a\": 1}", "{\"a\": 1}")),
            "JSON 预填=原文整串进 JSON 工具页输入框",
        )
        assertEquals(
            SmartPrefillHook.Payload.Base64Text("SGVsbG8gd29ybGQgdGhpcyBpcyBhIHRlc3Q="),
            smartPrefillPayloadOf(
                SmartDetection(SmartDetectionKind.BASE64_TEXT, 75, "x", "SGVsbG8gd29ybGQgdGhpcyBpcyBhIHRlc3Q="),
            ),
            "base64 文本预填=token 原文进 Base64 提取页粘贴区",
        )
        assertEquals(
            SmartPrefillHook.Payload.Base64Text("aW1hZ2VUb2tlbg=="),
            smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.IMAGE_BASE64, 85, "x", "aW1hZ2VUb2tlbg==")),
            "图片 base64 预填=token 原文进 Base64 提取页粘贴区（与文本类同载荷型）",
        )
        assertNull(smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.GZIP_DATA, 95, "x", null)), "gzip 无预填面（唯一纯跳页类）")
        assertNull(smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.GZIP_DATA, 95, "x", "有载荷也不投递")), "gzip 恒无预填面（不因载荷在场而开面）")
        // prefillText 缺位（探测器载荷漂移）时静默降级为纯跳页，不投递空载荷
        assertNull(smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.EPOCH_TIMESTAMP, 90, "x", null)))
        assertNull(smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.XLOG_PATH, 95, "x", null)))
        assertNull(smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.JSON_OBJECT, 90, "x", null)))
        assertNull(smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.BASE64_TEXT, 75, "x", null)))
        assertNull(smartPrefillPayloadOf(SmartDetection(SmartDetectionKind.IMAGE_BASE64, 85, "x", null)))
    }

    // ── 预填槽语义 ──

    @Test
    fun `smart prefill slot holds payload until matching type consumes read once`() {
        SmartPrefillHook.offer(SmartPrefillHook.Payload.EpochValue("1728547200"))
        // 异型消费不取走（时间戳页与 XLog 页共用单槽不互吞）
        assertNull(SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.XLogFilePath>(), "异型消费须返回 null")
        assertEquals(SmartPrefillHook.Payload.EpochValue("1728547200"), SmartPrefillHook.pending.value, "异型消费后槽不动")
        // 同型消费读即清
        assertEquals(
            SmartPrefillHook.Payload.EpochValue("1728547200"),
            SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.EpochValue>(),
            "同型消费取走载荷",
        )
        assertNull(SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.EpochValue>(), "读即清——二次取空")
        // 后到覆写（单载荷面：新推荐取代旧推荐）
        SmartPrefillHook.offer(SmartPrefillHook.Payload.EpochValue("1"))
        SmartPrefillHook.offer(SmartPrefillHook.Payload.XLogFilePath("/tmp/a.xlog"))
        assertEquals(
            SmartPrefillHook.Payload.XLogFilePath("/tmp/a.xlog"),
            SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.XLogFilePath>(),
            "后到覆写后旧载荷不可达",
        )
        assertNull(SmartPrefillHook.pending.value, "槽清空后 pending 回 null")
    }

    @Test
    fun `json and base64 payloads do not swallow each other in the typed slot`() {
        // 异型不互吞契约对新增载荷型同样成立：JSON 页与 Base64 页共用单槽，
        // 各自只取走同型载荷，异型消费返回 null 且槽不动
        SmartPrefillHook.offer(SmartPrefillHook.Payload.JsonText("{\"a\": 1}"))
        assertNull(
            SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.Base64Text>(),
            "Base64 页不得吞 JSON 载荷",
        )
        assertNull(
            SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.EpochValue>(),
            "时间戳页不得吞 JSON 载荷",
        )
        assertEquals(SmartPrefillHook.Payload.JsonText("{\"a\": 1}"), SmartPrefillHook.pending.value, "异型消费后槽不动")
        assertEquals(
            SmartPrefillHook.Payload.JsonText("{\"a\": 1}"),
            SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.JsonText>(),
            "同型消费取走载荷",
        )
        assertNull(SmartPrefillHook.pending.value)

        SmartPrefillHook.offer(SmartPrefillHook.Payload.Base64Text("aGVsbG8="))
        assertNull(
            SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.JsonText>(),
            "JSON 页不得吞 base64 载荷",
        )
        assertEquals(
            SmartPrefillHook.Payload.Base64Text("aGVsbG8="),
            SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.Base64Text>(),
        )
        assertNull(SmartPrefillHook.pending.value, "读即清——取走后槽回空")
    }

    // ── 剪贴板读取容错 ──

    @Test
    fun `read clipboard returns empty silently for null blank and plain text`() {
        assertEquals(emptyList(), readClipboardForDetection(FakeClipboard(null)))
        assertEquals(emptyList(), readClipboardForDetection(FakeClipboard("")))
        assertEquals(emptyList(), readClipboardForDetection(FakeClipboard("   ")))
        assertEquals(emptyList(), readClipboardForDetection(FakeClipboard("普通文本不产生推荐")))
    }

    @Test
    fun `read clipboard routes detected content through core detectors`() {
        val hits = readClipboardForDetection(FakeClipboard("1728547200000"))
        assertEquals(listOf(SmartDetectionKind.EPOCH_TIMESTAMP), hits.map { it.kind })
        assertEquals("1728547200000", hits.single().preview)
    }

    @Test
    fun `clipboard read failure is swallowed into empty recommendations`() {
        // AWT 剪贴板读取可抛（无属主/并发抢占/headless）——读取面契约：吞掉
        // 全部异常静默无推荐，不炸面板
        val throwing = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) = error("not used")
            override fun getText(): AnnotatedString = throw IllegalStateException("clipboard unavailable")
        }
        assertEquals(emptyList(), readClipboardForDetection(throwing))
    }

    // ── 零回归锚：推荐区与既有候选/排序/选中逻辑互不干扰 ──

    @Test
    fun `smart detections stay out of candidate set filtering and selection`() {
        // 推荐区是推荐首页独立分区：探测结果不得进候选全集（allItems）、
        // 不得参与过滤（filtered）、不得触碰 selectedIndex——三者任何一处
        // 接入都会改变既有命令排序/键盘选中语义
        val palette = File(locateProjectRoot(), COMMAND_PALETTE_SOURCE_PATH).readText()
        val allItemsBlock = palette.substringAfter("val allItems: List<PaletteEntry>")
            .substringBefore("val prefixMode")
        assertFalse(
            "smartDetections" in allItemsBlock,
            "探测结果不得进候选全集（会改 frecency 排序语义）",
        )
        val filteredBlock = palette.substringAfter("val filtered = remember")
            .substringBefore("LaunchedEffect(filtered.size)")
        assertFalse(
            "smartDetections" in filteredBlock,
            "探测结果不得参与检索过滤（会改列表条目序与 selectedIndex 映射）",
        )
        // 探测结果的唯一去向 = 推荐首页面板入参
        val hintCall = palette.substringAfter("PaletteEmptyHint(").substringBefore("onSelectSpace")
        assertTrue(
            "smartDetections = smartDetections" in hintCall,
            "探测结果只能作为 PaletteEmptyHint 入参呈现（推荐首页独立分区）",
        )
        // 推荐首页态键盘吞键契约在位（Enter 不得盲执行被推荐面板遮蔽的选中项）
        assertTrue("homeShowing -> true" in palette, "推荐首页态 Enter 吞键契约必须在位")
    }

    @Test
    fun `detection section renders inside recommendation home before other sections`() {
        // 推荐区渲染锚：只在推荐首页分支渲染（匹配失败早退守卫之后），置顶在
        // 最近空间/常用入口之前；点击落点 = 预选槽 + 跳工具页
        val entries = File(locateProjectRoot(), PALETTE_ENTRIES_SOURCE_PATH).readText()
        val hintFun = entries.substringAfter("internal fun PaletteEmptyHint")
            .substringBefore("private fun EmptyHintHeader")
        assertTrue("剪贴板识别" in hintFun, "推荐首页必须含「剪贴板识别」分区头")
        assertTrue(
            hintFun.indexOf("shouldShowRecommendationHome") < hintFun.indexOf("剪贴板识别"),
            "推荐区必须在匹配失败早退守卫之后（带查询/前缀域不显示推荐区）",
        )
        assertTrue(
            hintFun.indexOf("剪贴板识别") < hintFun.indexOf("最近空间"),
            "剪贴板识别分区必须置顶（先于最近空间段）",
        )
        assertTrue(
            "onPreselectToolsTab(target.toolsTab)" in hintFun &&
                "onNavigate(ToolPage.Tools, null)" in hintFun,
            "推荐行点击必须走预选槽 + 跳工具聚合页单通道（不走 deepLink 第二参）",
        )
    }

    // ── 预填链接线锚（源锚守护：生产侧 offer + 消费侧双 effect/待确认面）──

    @Test
    fun `detection rows offer prefill payload with pending-confirmation receipt`() {
        val entries = File(locateProjectRoot(), PALETTE_ENTRIES_SOURCE_PATH).readText()
        val hintFun = entries.substringAfter("internal fun PaletteEmptyHint")
            .substringBefore("private fun EmptyHintHeader")
        assertTrue(
            "smartPrefillPayloadOf(detection)" in hintFun,
            "预填载荷必须走单源映射函数（kind 单源，不在行内散造）",
        )
        assertTrue("SmartPrefillHook.offer(payload)" in hintFun, "推荐行点击必须入槽预填载荷（先于跳页）")
        assertTrue("待确认" in hintFun, "回执必须明示「已预填待确认」语义（预填不自动执行）")
        // 无预填面的识别（payload null）不得投递——offer 必须在 let 载荷非空分支内
        assertTrue(
            "smartPrefillPayloadOf(detection)?.let" in hintFun,
            "offer 必须以载荷非空为前提（无预填面识别只跳页）",
        )
    }

    @Test
    fun `timestamp page consumes epoch prefill through dual effects without invoking convert`() {
        val ts = File(locateProjectRoot(), TIMESTAMP_PAGE_SOURCE_PATH).readText()
        val prefillBlock = ts.substringAfter("palette 剪贴板预填消费")
            .substringBefore("批量结果单行")
        assertTrue(
            "SmartPrefillHook.pending.filterNotNull().collect" in prefillBlock,
            "在页订阅必须在位（页面驻留时 palette 再投递的承接面——双 effect 配方第二 effect）",
        )
        assertEquals(
            2,
            prefillBlock.split("consumeIfType<SmartPrefillHook.Payload.EpochValue>").size - 1,
            "组合期初值承载 + 在页订阅两条消费路径（双 effect）",
        )
        assertTrue("viewModel.onTimestampChange" in prefillBlock, "预填落地 = 只写输入框")
        assertFalse(
            "convert(" in prefillBlock,
            "预填路径不得调用 convert——转换结果由页面既有防抖流按既有输入语义显示（预填不自动执行）",
        )
    }

    @Test
    fun `xlog page parks prefill path behind explicit confirm not composition time landing`() {
        val page = File(locateProjectRoot(), XLOG_PAGE_SOURCE_PATH).readText()
        // 待确认面 = 槽流渲染（载荷驻槽），确认/忽略双钮都是显式点击
        assertTrue("SmartPrefillHook.pending.collectAsState()" in page, "待确认行按槽流渲染（载荷驻槽不读走）")
        assertTrue("入卡解析" in page && "忽略" in page, "确认/忽略双钮待确认面必须在位")
        // 组合期只消费跳页槽（既有目录载荷），不消费预填载荷（入卡即执行——预填不自动执行）
        val composeBlock = page.substringAfter("fun XLogDecoderPage")
            .substringBefore("TaskPageTemplate(")
        assertTrue("consumePendingDirectory" in composeBlock, "跳页槽（解压目录载荷）消费保持不变")
        assertFalse(
            "SmartPrefillHook.consumeIfType" in composeBlock,
            "组合期不得消费预填载荷——XLog 预填只经确认钮点击落地",
        )
        // 消费只发生在确认钮/忽略钮点击点，且与跳页槽共用同一落地函数（分流单源）
        val paramsBlock = page.substringAfter("paramsSlot = {")
        assertEquals(
            2,
            paramsBlock.split("SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.XLogFilePath>").size - 1,
            "确认 + 忽略两处点击点消费",
        )
        assertTrue("landDroppedPath" in composeBlock && "landDroppedPath" in paramsBlock, "预填与跳页槽共用落地函数（落地分流单源）")
    }

    @Test
    fun `json and base64 tool panels consume prefill through dual effects without auto execution`() {
        // 双面板消费面源锚（TimestampPage 双 effect 配方同款）：组合期初值 + 在页
        // 订阅两条路径；预填只写输入框/粘贴区（覆写语义），解码/求值仍由既有显示链
        // 或用户显式点按承担
        val json = File(locateProjectRoot(), JSON_TOOL_PANEL_SOURCE_PATH).readText()
        val jsonPrefill = json.substringAfter("palette 剪贴板预填消费")
            .substringBefore("private const val RENDER_HIT_ROW_CAP")
        assertTrue(
            "SmartPrefillHook.pending.filterNotNull().collect" in jsonPrefill,
            "JSON 工具页在页订阅必须在位（页面驻留时 palette 再投递的承接面——双 effect 第二面）",
        )
        assertEquals(
            2,
            jsonPrefill.split("consumeIfType<SmartPrefillHook.Payload.JsonText>").size - 1,
            "JSON 工具页组合期初值承载 + 在页订阅两条消费路径（双 effect）",
        )
        assertTrue("input = it.value" in jsonPrefill && "input = payload.value" in jsonPrefill, "JSON 预填落地 = 只写输入框（覆写）")
        assertTrue(
            jsonPrefill.split("input =").size - 1 == 2,
            "预填块内只允许两条输入框赋值（无其他落地面）",
        )

        val base64 = File(locateProjectRoot(), BASE64_PANEL_SOURCE_PATH).readText()
        val base64Prefill = base64.substringAfter("palette 剪贴板预填消费")
            .substringBefore("private data class CandidatePreview")
        assertTrue(
            "SmartPrefillHook.pending.filterNotNull().collect" in base64Prefill,
            "Base64 提取页在页订阅必须在位（双 effect 第二面）",
        )
        assertEquals(
            2,
            base64Prefill.split("consumeIfType<SmartPrefillHook.Payload.Base64Text>").size - 1,
            "Base64 提取页双 effect 两条消费路径",
        )
        assertTrue("text = it.value" in base64Prefill && "text = payload.value" in base64Prefill, "Base64 预填落地 = 只写粘贴区（覆写）")
        assertFalse(
            "decodeCandidate(" in base64Prefill || "selected = " in base64Prefill,
            "预填路径不得触发解码预览或候选选中（解码仍须用户点击候选——预填不自动执行）",
        )
    }

    private class FakeClipboard(private val text: String?) : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) = Unit
        override fun getText(): AnnotatedString? = text?.let { AnnotatedString(it) }
    }

    private companion object {
        const val COMMAND_PALETTE_SOURCE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/CommandPalette.kt"
        const val PALETTE_ENTRIES_SOURCE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteEntries.kt"
        const val TIMESTAMP_PAGE_SOURCE_PATH =
            "feature-timestamp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/timestamp/TimestampPage.kt"
        const val XLOG_PAGE_SOURCE_PATH =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/xlog/XLogDecoderPage.kt"
        const val JSON_TOOL_PANEL_SOURCE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/json/JsonToolPanel.kt"
        const val BASE64_PANEL_SOURCE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/base64/Base64ExtractorPanel.kt"
    }
}
