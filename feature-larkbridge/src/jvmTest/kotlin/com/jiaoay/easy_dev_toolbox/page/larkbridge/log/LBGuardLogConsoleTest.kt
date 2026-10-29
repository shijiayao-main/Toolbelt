package com.jiaoay.easy_dev_toolbox.page.larkbridge.log

import com.jiaoay.lark.bridge.observe.LogLine
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LB-12 守护（重设计批 9 · 2026-10-02）：日志行时间列 + 复制所见即所得。
 *
 * 拍板五条件（proposals.yaml LB-12，维护者 2026-09-29 CONDITIONAL）的代码级兑现：
 * 1. 复制文本真过滤态首行披露头「过滤态 N/M」（操作者与接收方双知悉）——
 *    [buildLogCopyText] 直测；
 * 2. 按钮文案动态「复制当前过滤结果」/「复制全部日志」——源码锚；
 * 3. 全不选 chip + 空搜索 = 全量复制（能力不丢）——[copy text without header when not filtered]
 *    + [copy text without header when filter keeps all lines]；
 * 4. 跨台边界（Shell 输出台时间列补齐）已登记欠账归批 10/11，本批零 Shell 页改动；
 * 5. formatter 单源——源码锚恰 1 处 `ofPattern(`。
 *
 * 🔴 旧裁定防回潮：LogConsolePanel 旧 KDoc「复制恒全量（复制的是用户看到前的完整缓冲）」
 * 已随 LB-12 推翻改写——该句整句不得回潮（回潮即复制语义退回全量）。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class LBGuardLogConsoleTest {

    // ── ① 纯函数直测：timeText（时间列单源）──

    @Test
    fun `time text renders HHmmss in system zone`() {
        val line = LogLine(seq = 1, level = "ERROR", tag = "T", message = "m", atMillis = 1748739254000L)
        val text = line.timeText()
        assertTrue(
            text.matches(Regex("""\d{2}:\d{2}:\d{2}""")),
            "时间列必须 HH:mm:ss（拍板格式；实得 $text）",
        )
        // 秒位时区无关断言（现代时区偏移恒整分，秒偏移为 0）——非同义反复的独立价值锚
        val expectedSecond = ((1748739254000L / 1000) % 60).toInt()
        assertEquals(expectedSecond, text.substring(6, 8).toInt(), "秒位须与 atMillis 同源")
    }

    @Test
    fun `copy line text is four tab separated segments`() {
        val line = LogLine(seq = 1, level = "WARN", tag = "Bot", message = "hello", atMillis = 0L)
        val text = line.copyLineText()
        val segments = text.split('\t')
        assertEquals(4, segments.size, "复制单行四段：HH:mm:ss/LEVEL/TAG/message（LB-12 扩段）")
        assertEquals(line.level, segments[1])
        assertEquals(line.tag, segments[2])
        assertEquals(line.message, segments[3])
    }

    // ── ② 纯函数直测：buildLogCopyText（披露头拍板条件①③）──

    @Test
    fun `copy text with header when truly filtered`() {
        val lines = (1..3).map { LogLine(it.toLong(), "INFO", "T$it", "m$it", 0L) }
        val filtered = lines.take(2)
        val text = buildLogCopyText(lines = lines, filtered = filtered, isFiltered = true)
        assertEquals(
            "过滤态 2/3",
            text.lineSequence().first(),
            "真过滤态首行必须披露头「过滤态 N/M」（拍板条件①，操作者与接收方双知悉）",
        )
        assertEquals(3, text.lineSequence().count(), "披露头 + 过滤后 2 行 = 3 行")
        assertTrue("INFO\tT2\tm2" in text, "复制体须含过滤结果行（所见即所得）")
        assertFalse("T3" in text, "未入过滤结果的行不得混入复制体")
    }

    @Test
    fun `copy text without header when not filtered`() {
        val lines = (1..2).map { LogLine(it.toLong(), "INFO", "T$it", "m$it", 0L) }
        val text = buildLogCopyText(lines = lines, filtered = lines, isFiltered = false)
        assertFalse(text.startsWith("过滤态"), "全量态（isFiltered=false）不得带披露头——能力不丢（拍板条件③）")
        assertEquals(2, text.lineSequence().count(), "全量复制逐行 = 缓冲全量")
    }

    @Test
    fun `copy text without header when filter keeps all lines`() {
        // isFiltered=true 但过滤后恰为全量（如全不选 chip 前恢复、或搜索词未命中剔除任何行之外恒真场景）：
        // N==M 披露头无信息量，不披露
        val lines = (1..2).map { LogLine(it.toLong(), "INFO", "T$it", "m$it", 0L) }
        val text = buildLogCopyText(lines = lines, filtered = lines, isFiltered = true)
        assertFalse(text.startsWith("过滤态"), "过滤后恰为全量（N==M）不得带披露头——披露头只在真过滤态")
    }

    @Test
    fun `copy text with header carries counts of current buffer`() {
        // 披露头计数必须对当前缓冲（环形淘汰后 lines.size 实时），N/M 双数知悉
        val lines = (1..5).map { LogLine(it.toLong(), "DEBUG", "T$it", "m$it", 0L) }
        val filtered = listOf(lines[4])
        val text = buildLogCopyText(lines = lines, filtered = filtered, isFiltered = true)
        assertEquals("过滤态 1/5", text.lineSequence().first())
    }

    // ── ③ 源码锚守护：LogConsolePanel 落点 ──

    @Test
    fun `log console panel anchors LB12 implementation`() {
        val text = file(LOG_CONSOLE_PANEL).readText()
        // formatter 单源（拍板条件⑤）：全文件恰 1 处 ofPattern(
        assertEquals(
            1,
            text.split("ofPattern(").size - 1,
            "时间 formatter 必须单源（拍板条件⑤）——出现多处即重复派生，收敛回 LOG_TIME_FORMAT。",
        )
        // 行渲染时间列：timeText 在 items(filtered 行块内、level 之前（行首）
        val itemsIdx = text.indexOf("items(filtered, key = { it.seq }")
        assertTrue(itemsIdx >= 0, "items(filtered 行渲染锚漂移——改写后须同步本守护。")
        val renderBlock = text.substring(itemsIdx, itemsIdx + 1100)
        val timeIdx = renderBlock.indexOf("line.timeText()")
        val levelIdx = renderBlock.indexOf("line.level")
        assertTrue(
            timeIdx in 0 until levelIdx,
            "行首必须先渲染时间列（line.timeText() 在 line.level 之前）——LB-12 acceptance「每行行首可见 HH:mm:ss」。",
        )
        // 复制所见即所得：textProvider 走 buildLogCopyText、动态 contentDescription（拍板条件②）
        assertTrue(
            "textProvider = { buildLogCopyText(" in text,
            "复制必须走 buildLogCopyText（复制对象=过滤结果，acceptance「过滤态点复制得到的是过滤后内容」）。",
        )
        assertTrue(
            """contentDescription = if (isFiltered) "复制当前过滤结果" else "复制全部日志"""" in text,
            "复制钮文案必须随过滤态动态（拍板条件②）。",
        )
        // 披露头拼装在位（拍板条件①）
        assertTrue(
            "\"过滤态 \${filtered.size}/\${lines.size}\\n\$body\"" in text,
            "披露头「过滤态 N/M」拼装缺失（拍板条件①）。",
        )
        // 🔴 旧裁定防回潮：旧 KDoc 整句不得回潮（回潮即复制语义退回全量）
        assertFalse(
            "复制的是用户看到前的完整缓冲" in text,
            "旧裁定句「复制的是用户看到前的完整缓冲」已随 LB-12 推翻——回潮即复制语义退回全量，禁止。",
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
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")})")
    }

    private companion object {
        const val LOG_CONSOLE_PANEL =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/log/LogConsolePanel.kt"
    }
}
