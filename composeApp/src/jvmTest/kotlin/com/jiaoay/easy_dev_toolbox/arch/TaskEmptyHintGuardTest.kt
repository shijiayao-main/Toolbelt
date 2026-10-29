package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TL-05 · 四签空态统一源码锚守护（重设计批 11 · 2026-10-02，含 XA-18④）：
 *
 * 四胞胎任务页（XLog 解析 / Tag 过滤 / 文件分割 / 解压）空态此前三签零文案
 * （TaskListSection emptyHint 传 null = 空态不渲染，空列表下方全空白）、
 * 文件分割是单要素旧文案「等待文件...」。本卡落法（勘误 1——组件签名方案作废）：
 *
 * 1. **TaskPageTemplate 模板级默认值** `emptyHint: String? = "还没有任务"`——
 *    新页漏传也见四要素空态；组件层 [TaskListSection] 保持 `String? = null`
 *    opt-in 不动（组件默认会波及 VoicePage 等其它调用方——签名默认值方案作废事由）；
 * 2. **四页显式传页别文案**：XLog「还没有解析任务」/ LogTag「还没有过滤任务」/
 *    FileSplit「还没有分割任务」（XA-18④ 旧文案「拖入 .txt 文件开始分割」作废）/
 *    Archive「还没有解压任务」；
 * 3. **learningHint 现值不回退**：TaskListSection 空态分支批 8 已四要素化
 *    （EmptyStateView INBOX + learningHint「拖入文件后自动开始处理…」），本卡零改。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class TaskEmptyHintGuardTest {

    // ── ① 四页页别文案在位（缺失即红——空态文案退役/漂移即违 TL-05）──

    @Test
    fun `four sibling pages pass page specific empty hint`() {
        val missing = buildList {
            val xlog = file(XLOG_PAGE).readText()
            if ("emptyHint = \"还没有解析任务\"" !in xlog) add("XLogDecoderPage 缺 emptyHint「还没有解析任务」")
            val logtag = file(LOGTAG_PAGE).readText()
            if ("emptyHint = \"还没有过滤任务\"" !in logtag) add("LogTagFilterPage 缺 emptyHint「还没有过滤任务」")
            val filesplit = file(FILESPLIT_PAGE).readText()
            if ("emptyHint = \"还没有分割任务\"" !in filesplit) add("FileSplitterPage 缺 emptyHint「还没有分割任务」")
            val archive = file(ARCHIVE_PAGE).readText()
            if ("emptyHint = \"还没有解压任务\"" !in archive) add("ArchivePage 缺 emptyHint「还没有解压任务」")
        }
        if (missing.isNotEmpty()) {
            fail("TL-05 守护：四签空态页别文案残缺：\n${missing.joinToString("\n")}")
        }
    }

    // ── ② 模板默认值兜底在位（新页漏传也见空态）──

    @Test
    fun `task page template defaults empty hint to fallback copy`() {
        val text = file(TASK_PAGE_TEMPLATE).readText()
        assertTrue(
            "emptyHint: String? = \"还没有任务\"" in text,
            "TaskPageTemplate emptyHint 默认值须为「还没有任务」（TL-05：模板级兜底；" +
                "四签显式覆盖）——退回 null 默认 = 新页漏传即空态静默消失",
        )
    }

    // ── ③ TaskListSection 空态四要素不回退（批 8 成果防回退）──

    @Test
    fun `task list section empty state keeps four element recipe`() {
        val text = file(TASK_LIST_SECTION).readText()
        assertTrue(
            "EmptyStateView(" in text && "MaterialSymbols.INBOX" in text,
            "TaskListSection 空态必须走 EmptyStateView + INBOX（批 8 四要素收口防回退）",
        )
        assertTrue(
            "learningHint = \"拖入文件后自动开始处理，完成的产物会列在这里\"" in text,
            "TaskListSection 空态 learningHint 现值不回退（TL-05 验收：学习提示固定段）",
        )
        // opt-in 语义不动：组件层默认恒 null（模板层才有兜底默认）——组件默认非 null
        // 会波及 VoicePage 等其它调用方（勘误 1 裁量事由）
        assertTrue(
            "emptyHint: String? = null" in text,
            "TaskListSection 签名保持 emptyHint: String? = null opt-in（组件层默认非 null" +
                "会波及 VoicePage 等其它调用方——TL-05 勘误 1）",
        )
    }

    // ── ④ 旧文案全仓零残留（含注释——「等待文件」任何形态不得回潮）──

    @Test
    fun `legacy waiting copy purged repo wide including comments`() {
        val scanned = listOf(
            TASK_PAGE_TEMPLATE,
            TASK_LIST_SECTION,
            XLOG_PAGE,
            LOGTAG_PAGE,
            FILESPLIT_PAGE,
            ARCHIVE_PAGE,
        )
        val offenders = scanned
            .filter { "等待文件" in file(it).readText() }
            .map { it.substringAfterLast('/') }
        assertTrue(
            offenders.isEmpty(),
            "旧空态文案「等待文件」残留：$offenders（TL-05/XA-18④：页别文案已替代——" +
                "含注释面零残留）",
        )
    }

    // ── VoicePage 页别文案不受模板默认波及（勘误 1 裁量的回归面钉住）──

    @Test
    fun `voice page keeps its own empty hint`() {
        val text = file(VOICE_PAGE).readText()
        assertTrue(
            "emptyHint = \"拖入音频文件开始离线转写" in text,
            "VoicePage 自有页别 emptyHint 必须显式保留（模板默认值只兜新页——" +
                "语音页文案被模板默认覆盖即文案漂移）",
        )
        assertFalse(
            "还没有任务" in text,
            "VoicePage 不得出现模板兜底文案「还没有任务」（页别文案显式传 ≠ 漏传兜底）",
        )
    }

    // ── 锚文件（缺失即红——重命名/移动后须同步本守护路径）──

    private fun file(relPath: String): File {
        val f = File(locateProjectRoot(), relPath)
        assertTrue(f.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return f
    }

    private companion object {
        const val TASK_PAGE_TEMPLATE =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/task/TaskPageTemplate.kt"
        const val TASK_LIST_SECTION =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/task/TaskListSection.kt"
        const val XLOG_PAGE =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/xlog/XLogDecoderPage.kt"
        const val LOGTAG_PAGE =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logtag/LogTagFilterPage.kt"
        const val FILESPLIT_PAGE =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/filesplit/FileSplitterPage.kt"
        const val ARCHIVE_PAGE =
            "feature-archive/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/archive/ArchivePage.kt"
        const val VOICE_PAGE =
            "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoicePage.kt"
    }
}
