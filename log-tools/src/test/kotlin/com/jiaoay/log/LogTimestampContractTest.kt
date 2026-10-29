package com.jiaoay.log

import com.github.ajalt.clikt.core.parse
import com.jiaoay.log.cli.LogUtilsCommand
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 时间戳契约（LogTimestamp/isTimeLine/LogTimeFilter/LogTagFilter）特征测试：
 * 覆盖裸格式与年份格式（alog_decoder.py 类外部解码器产物）双格式识别、
 * 过滤器对年份格式日志的正确过滤、无时间戳行静默失效防护、目标时间容错解析。
 */
class LogTimestampContractTest {

    private fun tempLog(lines: List<String>): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "log-tools-ts-test-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return File(dir, "app.log").apply {
            writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            deleteOnExit()
        }
    }

    // ── isTimeLine：双格式识别 ──

    @Test
    fun `isTimeLine 识别裸 MM-dd 格式`() {
        assertTrue("05-09 10:00:00.000 D/Main: msg".isTimeLine())
        assertFalse("2026-05-09x 10:00:00.000".isTimeLine())
    }

    @Test
    fun `isTimeLine 识别年份前缀格式（alog_decoder 产物）`() {
        assertTrue("2026-09-29 10:00:00.000 D/Main: msg".isTimeLine())
        assertTrue("2026-12-31 23:59:59.999 D/Main: msg".isTimeLine())
    }

    @Test
    fun `isTimeLine 拒绝非时间行`() {
        assertFalse("普通内容行".isTimeLine())
        assertFalse("D/Main: 2026-09-29 10:00:00.000 年份不在行首不命中".isTimeLine())
        assertFalse(null.isTimeLine())
    }

    // ── LogTimestamp.parseLine ──

    @Test
    fun `parseLine 裸格式按 fallbackYear 补年`() {
        val t = LogTimestamp.parseLine("05-09 10:00:00.000 rest", fallbackYear = "2026")
        assertEquals(LocalDateTime.of(2026, 5, 9, 10, 0, 0, 0), t)
    }

    @Test
    fun `parseLine 年份格式自带年份`() {
        val t = LogTimestamp.parseLine("2025-01-02 03:04:05.006 rest", fallbackYear = "2026")
        assertEquals(LocalDateTime.of(2025, 1, 2, 3, 4, 5, 6_000_000), t)
    }

    @Test
    fun `parseLine 非法日期返回 null`() {
        assertEquals(null, LogTimestamp.parseLine("2026-99-99 10:00:00.000", fallbackYear = "2026"))
        assertEquals(null, LogTimestamp.parseLine("无时间戳", fallbackYear = "2026"))
    }

    // ── LogTimestamp.parseTarget：容错解析 ──

    @Test
    fun `parseTarget 规范格式`() {
        val t = LogTimestamp.parseTarget("2026-05-09_10:00:00.000")
        assertEquals(LocalDateTime.of(2026, 5, 9, 10, 0, 0), t)
    }

    @Test
    fun `parseTarget 容忍空格分隔与省略毫秒`() {
        assertEquals(LocalDateTime.of(2026, 5, 9, 10, 0, 0), LogTimestamp.parseTarget("2026-05-09 10:00:00.000"))
        assertEquals(LocalDateTime.of(2026, 5, 9, 10, 0, 0), LogTimestamp.parseTarget("2026-05-09_10:00:00"))
    }

    @Test
    fun `parseTarget 容忍裸 MM-dd 以当前年锚定补全`() {
        // 裸日期（xlog 自产行首同款）无年份——当前年锚定；比较侧以目标年份
        // 重新锚定行时间戳，锚定年取值不影响单格式文件内的相对先后
        val currentYear = java.time.Year.now().value
        assertEquals(LocalDateTime.of(currentYear, 5, 9, 10, 0, 0), LogTimestamp.parseTarget("05-09 10:00:00.000"))
        assertEquals(LocalDateTime.of(currentYear, 5, 9, 10, 0, 0), LogTimestamp.parseTarget("05-09_10:00:00"))
    }

    @Test
    fun `parseTarget 裸日期非法仍宁拒不猜`() {
        assertEquals(null, LogTimestamp.parseTarget("99-99 10:00:00.000"))
        assertEquals(null, LogTimestamp.parseTarget("05-09"))
    }

    @Test
    fun `parseTarget 非法输入返回 null`() {
        assertEquals(null, LogTimestamp.parseTarget("not-a-time"))
        assertEquals(null, LogTimestamp.parseTarget(""))
    }

    // ── LogTimeFilter：年份格式日志过滤（本次静默失效场景回归） ──

    private fun yearFormatLog(): List<String> = listOf(
        "2026-09-29 10:00:00.000 D/Main: 窗口前",
        "2026-09-29 10:05:00.000 D/Main: 窗口内",
        "2026-09-29 10:06:00.000 D/Main: 窗口内后段",
        "2026-09-29 10:30:00.000 D/Main: 窗口外",
    )

    @Test
    fun `filter_by_time 对年份格式日志正确过滤`() {
        val input = tempLog(yearFormatLog())
        val result = LogTimeFilter.filterByTime(input, target = "2026-09-29_10:05:00.000", rangeMinute = 2)
        assertIs<ToolsResult.Success>(result)
        val out = result.outputFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(yearFormatLog().drop(1).take(2), out, "窗口 [10:03, 10:07] 应命中中间两行")
    }

    @Test
    fun `filter_by_time 对裸格式日志行为不回退`() {
        val lines = listOf(
            "05-09 10:00:00.000 D/Main: 窗口边界（恰为 startTime 不算 before）",
            "05-09 10:01:00.000 D/Main: 窗口内",
            "05-09 10:05:00.000 D/Main: 窗口外",
        )
        val input = tempLog(lines)
        val result = LogTimeFilter.filterByTime(input, target = "2026-05-09_10:01:00.000", rangeMinute = 1)
        assertIs<ToolsResult.Success>(result)
        val out = result.outputFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        // 窗口 [10:00, 10:02]：首行恰在边界（含），第三行窗外被过滤
        assertEquals(lines.take(2), out)
    }

    // ── 静默失效防护：无时间戳行 → Failure（产物删除） ──

    @Test
    fun `filter_by_time 无时间戳行报失败且不留产物`() {
        val input = tempLog(listOf("纯文本行一", "纯文本行二", "2026-09-29_10:00:00.000 但时间不在行首"))
        val result = LogTimeFilter.filterByTime(input, target = "2026-09-29_10:00:00.000", rangeMinute = 5)
        assertIs<ToolsResult.Failure>(result)
        assertTrue("时间戳" in result.reason, "失败原因应指明时间戳问题: ${result.reason}")
        val expectedOut = File(input.parent, "${input.nameWithoutExtension}.filter-time_0955-1005.txt")
        assertFalse(expectedOut.exists(), "0 时间行产物应被删除")
    }

    @Test
    fun `filter_by_time 目标时间非法报失败`() {
        val input = tempLog(listOf("05-09 10:00:00.000 D/Main: x"))
        val result = LogTimeFilter.filterByTime(input, target = "garbage", rangeMinute = 5)
        assertIs<ToolsResult.Failure>(result)
    }

    // ── LogTimeFilter.filterByRange：起止区间截取（包装契约镜像对称窗口款） ──

    @Test
    fun `filter_by_range 正常截取奇数分钟区间`() {
        // [10:04, 10:05] 是 1 分钟跨度——对称窗口 ±N 分钟整数口径表达不了该形状
        val input = tempLog(yearFormatLog())
        val result = LogTimeFilter.filterByRange(input, startTime = "2026-09-29_10:04:00.000", endTime = "2026-09-29_10:05:00.000")
        assertIs<ToolsResult.Success>(result)
        val out = result.outputFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(listOf(yearFormatLog()[1]), out, "区间 [10:04, 10:05] 应仅命中 10:05 一行")
        assertEquals(
            "app.filter-time_1004-1005.txt",
            result.outputFile.name,
            "产物名与对称窗口同款 HHmm-HHmm 命名",
        )
        assertEquals(input.parent, result.outputFile.parent, "产物落原文件旁")
    }

    @Test
    fun `filter_by_range 起止边界行均含入`() {
        val lines = listOf(
            "05-09 09:59:00.000 D/Main: 区间前",
            "05-09 10:00:00.000 D/Main: 恰为起始（含）",
            "05-09 10:02:00.000 D/Main: 恰为结束（含）",
            "05-09 10:03:00.000 D/Main: 区间后",
        )
        val input = tempLog(lines)
        val result = LogTimeFilter.filterByRange(input, startTime = "2026-05-09_10:00:00.000", endTime = "2026-05-09_10:02:00.000")
        assertIs<ToolsResult.Success>(result)
        val out = result.outputFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(lines.slice(1..2), out, "起止两边界行均含入（闭区间语义）")
    }

    @Test
    fun `filter_by_range 产物已存在拒绝`() {
        val input = tempLog(yearFormatLog())
        val expectedOut = File(input.parent, "app.filter-time_1000-1030.txt")
        expectedOut.writeText("既有产物")
        expectedOut.deleteOnExit()
        val result = LogTimeFilter.filterByRange(input, startTime = "2026-09-29_10:00:00.000", endTime = "2026-09-29_10:30:00.000")
        assertIs<ToolsResult.Failure>(result)
        assertTrue("已存在" in result.reason, "拒绝原因应指明产物已存在: ${result.reason}")
        assertEquals("既有产物", expectedOut.readText(), "已存在产物不被覆盖")
    }

    @Test
    fun `filter_by_range 无时间戳行报失败且不留产物`() {
        val input = tempLog(listOf("纯文本行一", "纯文本行二"))
        val result = LogTimeFilter.filterByRange(input, startTime = "2026-09-29_10:00:00.000", endTime = "2026-09-29_10:30:00.000")
        assertIs<ToolsResult.Failure>(result)
        assertTrue("时间戳" in result.reason, "失败原因应指明时间戳问题: ${result.reason}")
        val expectedOut = File(input.parent, "app.filter-time_1000-1030.txt")
        assertFalse(expectedOut.exists(), "0 时间行产物应被删除")
    }

    @Test
    fun `filter_by_range 结束早于起始报失败`() {
        val input = tempLog(yearFormatLog())
        val result = LogTimeFilter.filterByRange(input, startTime = "2026-09-29_10:30:00.000", endTime = "2026-09-29_10:00:00.000")
        assertIs<ToolsResult.Failure>(result)
        assertTrue("早于起始" in result.reason, "失败原因应指明区间反向: ${result.reason}")
    }

    @Test
    fun `filter_by_range 起止端点解析失败分别报错`() {
        val input = tempLog(yearFormatLog())
        val badStart = LogTimeFilter.filterByRange(input, startTime = "garbage", endTime = "2026-09-29_10:30:00.000")
        assertIs<ToolsResult.Failure>(badStart)
        assertTrue("起始" in badStart.reason, "起始端解析失败应指明是起始端: ${badStart.reason}")
        val badEnd = LogTimeFilter.filterByRange(input, startTime = "2026-09-29_10:00:00.000", endTime = "garbage")
        assertIs<ToolsResult.Failure>(badEnd)
        assertTrue("结束" in badEnd.reason, "结束端解析失败应指明是结束端: ${badEnd.reason}")
    }

    @Test
    fun `filter_by_range 容忍空格分隔与省略毫秒`() {
        val input = tempLog(yearFormatLog())
        val result = LogTimeFilter.filterByRange(input, startTime = "2026-09-29 10:05:00", endTime = "2026-09-29 10:06:00.000")
        assertIs<ToolsResult.Success>(result)
        val out = result.outputFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(yearFormatLog().slice(1..2), out, "宽松格式（空格分隔+省毫秒）应与规范格式同结果")
    }

    // ── 裸目标形态：与裸格式日志的配对契约（接受面写清的对消回归锚） ──

    @Test
    fun `裸目标配裸格式日志恒命中-年份锚定对消`() {
        // 裸 MM-dd 目标（xlog 自产明文行首同款，从日志直接复制）× 裸格式日志：
        // 行时间戳以目标年统一重锚，锚定年取值不影响结果——本用例是对消回归锚，
        // 防未来有人把目标年重锚口径「修」掉
        val lines = listOf(
            "05-09 09:58:00.000 D/Main: 窗口前",
            "05-09 10:00:00.000 D/Main: 窗口内",
            "05-09 10:03:00.000 D/Main: 窗口后",
        )
        val input = tempLog(lines)
        val result = LogTimeFilter.filterByTime(input, target = "05-09 10:00:30.000", rangeMinute = 1)
        assertIs<ToolsResult.Success>(result)
        val out = result.outputFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(lines.slice(1..1), out, "裸目标窗口 [09:59:30, 10:01:30] 应命中 10:00 一行")

        val range = LogTimeFilter.filterByRange(
            input,
            startTime = "05-09_10:00:00",
            endTime = "05-09_10:03:00.000",
        )
        assertIs<ToolsResult.Success>(range)
        val rangeOut = range.outputFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(lines.slice(1..2), rangeOut, "裸起止（_ 分隔 + 省毫秒）应命中中间两行")
    }

    @Test
    fun `裸目标配年格式日志走零命中守护-失败且产物被删`() {
        // 年格式文件（外部解码器产物）行时间戳自带年份；裸目标按当前年锚定，
        // 文件年份 ≠ 当前年时恒不命中——零命中守护拒绝并删产物，不留空文件假成功
        val fileYear = java.time.Year.now().value - 1
        val input = tempLog(
            listOf(
                "$fileYear-06-01 10:00:00.000 D/Main: a",
                "$fileYear-06-01 10:05:00.000 D/Main: b",
            ),
        )
        val result = LogTimeFilter.filterByTime(input, target = "06-01 10:05:00.000", rangeMinute = 5)
        assertIs<ToolsResult.Failure>(result)
        assertTrue("区间未命中任何时间行" in result.reason, "失败原因应指明零命中: ${result.reason}")
        assertTrue("2 行均不在区间内" in result.reason, "失败原因应含实际时间行数: ${result.reason}")
        val expectedOut = File(input.parent, "${input.nameWithoutExtension}.filter-time_1000-1010.txt")
        assertFalse(expectedOut.exists(), "零命中产物应被删除")

        // 全形态区间整体越界（时间行存在但均在区间外）同样走零命中守护
        // （该形态与目标形态无关，属同一空产物假成功洞）
        val input2 = tempLog(yearFormatLog())
        val miss = LogTimeFilter.filterByRange(
            input2,
            startTime = "2026-09-29_11:00:00.000",
            endTime = "2026-09-29_11:30:00.000",
        )
        assertIs<ToolsResult.Failure>(miss)
        assertTrue("区间未命中任何时间行" in miss.reason, "全形态越界同样报零命中: ${miss.reason}")
        val expectedOut2 = File(input2.parent, "app.filter-time_1100-1130.txt")
        assertFalse(expectedOut2.exists(), "越界空产物应被删除")
    }

    @Test
    fun `跨年裸文件区间倒置形态现状钉住-多收与拒收`() {
        // 跨年段裸文件相对先后在边界处倒置（12-31 行重锚后排在 01-01 之后）——
        // 三种区间形态现状钉住（parseTarget KDoc 跨年契约），不修代码：
        // 1) Dec 侧窗口：Jan 侧行重锚后排到 end 之前，不触发超窗 break 被多收；
        // 2) 跨边界窗口：end 重锚后早于 start，被倒置拒收；
        // 3) Jan 侧窗口：头部 12-31 行重锚后既 ≥ start 又 > end，提前 break 截断，
        //    零写入转零命中守护拒绝
        val lines = listOf(
            "12-31 23:59:00.000 D/Main: 跨年边界前夜",
            "01-01 00:01:00.000 D/Main: 跨年边界后",
        )

        val input = tempLog(lines)
        val overCollect = LogTimeFilter.filterByRange(
            input,
            startTime = "12-31_23:00:00.000",
            endTime = "12-31_23:59:59.999",
        )
        assertIs<ToolsResult.Success>(overCollect)
        val out = overCollect.outputFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(lines, out, "倒置使 Jan 侧行被判在 end 之前而多收（跨年契约现状）")

        val input2 = tempLog(lines)
        val rejected = LogTimeFilter.filterByRange(
            input2,
            startTime = "12-31_23:58:00.000",
            endTime = "01-01_00:05:00.000",
        )
        assertIs<ToolsResult.Failure>(rejected)
        assertTrue("早于起始" in rejected.reason, "跨边界区间被倒置拒收: ${rejected.reason}")

        val input3 = tempLog(lines)
        val truncated = LogTimeFilter.filterByRange(
            input3,
            startTime = "01-01_00:00:00.000",
            endTime = "01-01_00:05:00.000",
        )
        assertIs<ToolsResult.Failure>(truncated)
        assertTrue(
            "区间未命中任何时间行" in truncated.reason,
            "Jan 侧窗口被头部 12-31 行截断转零命中守护: ${truncated.reason}",
        )
    }

    // ── LogTagFilter：年份格式块切分（本次输出=输入场景回归） ──

    @Test
    fun `filter_by_tag 对年份格式日志正确切块`() {
        val lines = listOf(
            "2026-09-29 10:00:00.000 D/Alpha: a 命中",
            "2026-09-29 10:00:01.000 D/Beta: b",
            "2026-09-29 10:00:02.000 D/Alpha: c 命中",
        )
        val input = tempLog(lines)
        val result = LogTagFilter.filterByTag(input, tagRegex = "Alpha", tagName = "alpha")
        assertIs<ToolsResult.Success>(result)
        val out = result.outputFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        // Beta 块不命中：输出应只含 Alpha 两块（输出≠输入）
        assertEquals(listOf(lines[0], lines[2]), out)
    }

    @Test
    fun `filter_by_tag 空行保留在块内`() {
        val lines = listOf(
            "2026-09-29 10:00:00.000 D/Alpha: a",
            "",
            "后续行",
        )
        val input = tempLog(lines)
        val result = LogTagFilter.filterByTag(input, tagRegex = "Alpha", tagName = "alpha")
        assertIs<ToolsResult.Success>(result)
        val out = result.outputFile.readLines(Charsets.UTF_8)
        // 空行在命中块内应保留（首尾空白由 readLines 语义决定，中间空行保留）
        assertTrue("" in out, "块内空行应保留")
    }

    // ── LogSplitter：末尾空分片不再产生 ──

    @Test
    fun `splitBySize 恰在阈值处结束不产生空分片`() {
        val input = tempLog(listOf("line"))
        val result = LogSplitter.splitBySize(input, sizeMb = 1)
        assertIs<ToolsResult.Success>(result)
        val siblings = input.parentFile!!.listFiles()!!
            .filter { it.name.startsWith("app_") }
        assertEquals(1, siblings.size, "应只有 _000 一卷: ${siblings.map { it.name }}")
        assertTrue(siblings[0].length() > 0)
    }

    // ── 产物文件名：分钟补零 ──

    @Test
    fun `filter_time 产物名分钟补零`() {
        val input = tempLog(listOf("05-09 09:05:00.000 D/Main: x", "05-09 09:06:00.000 D/Main: y"))
        val result = LogTimeFilter.filterByTime(input, target = "2026-05-09_09:06:00.000", rangeMinute = 1)
        assertIs<ToolsResult.Success>(result)
        assertTrue(
            "filter-time_0905-0907" in result.outputFile.name,
            "产物名应为 HHmm 补零格式: ${result.outputFile.name}",
        )
    }

    // ── 时间行解析精度：防 substring 偏移类回归 ──

    @Test
    fun `时间行毫秒精度解析（旧 substring 硬编码 18 字符回归）`() {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss.SSS")
        val t = LogTimestamp.parseLine("2026-09-29 10:05:00.999 D/Main: x", fallbackYear = "2026")
        assertEquals(LocalDateTime.parse("2026-09-29_10:05:00.999", fmt), t)
    }

    // ── CLI 端到端（--type filter_by_range） ──────────────────

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8.name()))
        return try {
            block()
            buffer.toString(Charsets.UTF_8.name())
        } finally {
            System.setOut(original)
        }
    }

    @Test
    fun `CLI filter_by_range 成功路径产物命名与对称窗口同款`() {
        // [10:04, 10:35] 共 31 分钟——targetTime ± N 对称窗口整数分钟口径表达不了的区间形状
        val input = tempLog(yearFormatLog())
        val output = captureStdout {
            LogUtilsCommand().parse(
                listOf(
                    "--type", "filter_by_range",
                    "-f", input.absolutePath,
                    "--startTime", "2026-09-29_10:04:00.000",
                    "--endTime", "2026-09-29_10:35:00.000",
                ),
            )
        }
        // parse 不抛 = 退出码 0（脚本化退出判定口径与既有 type 一致）
        assertTrue("success! outputFile:" in output, "stdout 应含成功锚: $output")
        val outFile = File(input.parent, "app.filter-time_1004-1035.txt")
        assertTrue(outFile.isFile, "产物名应与对称窗口同款 filter-time_HHmm-HHmm: ${input.parentFile!!.list()!!.toList()}")
        val out = outFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(yearFormatLog().slice(1..3), out, "区间 [10:04, 10:35] 应命中 10:05/10:06/10:30 三行")
    }

    @Test
    fun `CLI filter_by_range 缺参与半参按必填校验失败`() {
        val input = tempLog(yearFormatLog())
        // 缺参：起止全缺
        val noTimes = assertFailsWith<IllegalArgumentException> {
            LogUtilsCommand().parse(listOf("--type", "filter_by_range", "-f", input.absolutePath))
        }
        assertEquals("--startTime is required for --type filter_by_range", noTimes.message)
        // 半参：只有起始缺结束（先缺哪个指明哪个——宁拒不猜，不做单端隐式补全）
        val half = assertFailsWith<IllegalArgumentException> {
            LogUtilsCommand().parse(
                listOf(
                    "--type", "filter_by_range",
                    "-f", input.absolutePath,
                    "--startTime", "2026-09-29_10:00:00.000",
                ),
            )
        }
        assertEquals("--endTime is required for --type filter_by_range", half.message)
    }

    @Test
    fun `CLI filter_by_range 格式错与起止倒挂宁拒不猜报失败`() {
        val input = tempLog(yearFormatLog())
        // 格式错（起始端）：引擎拒绝不猜——CLI 以非零退出（CliktError）承载
        val badStart = assertFailsWith<com.github.ajalt.clikt.core.CliktError> {
            captureStdout {
                LogUtilsCommand().parse(
                    listOf(
                        "--type", "filter_by_range",
                        "-f", input.absolutePath,
                        "--startTime", "13:00",
                        "--endTime", "2026-09-29_10:35:00.000",
                    ),
                )
            }
        }
        assertTrue(badStart.message!!.contains("operation failed"), "失败以 CliktError 承载非零退出: ${badStart.message}")
        // 格式错（结束端）与起止倒挂：拒绝原因经 stdout 呈现，各自指明端点
        val outputEnd = captureStdout {
            assertFailsWith<com.github.ajalt.clikt.core.CliktError> {
                LogUtilsCommand().parse(
                    listOf(
                        "--type", "filter_by_range",
                        "-f", input.absolutePath,
                        "--startTime", "2026-09-29_10:00:00.000",
                        "--endTime", "13:35",
                    ),
                )
            }
        }
        assertTrue("结束时间无法解析" in outputEnd, "结束端格式错应指明是结束端: $outputEnd")
        val outputReversed = captureStdout {
            assertFailsWith<com.github.ajalt.clikt.core.CliktError> {
                LogUtilsCommand().parse(
                    listOf(
                        "--type", "filter_by_range",
                        "-f", input.absolutePath,
                        "--startTime", "2026-09-29_10:35:00.000",
                        "--endTime", "2026-09-29_10:04:00.000",
                    ),
                )
            }
        }
        assertTrue("结束时间早于起始时间" in outputReversed, "起止倒挂应指明区间反向: $outputReversed")
        // 宁拒不猜：拒绝后不落任何产物
        assertFalse(
            input.parentFile!!.list()!!.any { it.startsWith("app.filter-time_") },
            "拒绝场景不得遗留产物: ${input.parentFile!!.list()!!.toList()}",
        )
    }

    @Test
    fun `CLI filter_by_range 不支持后缀按既有扩展校验失败`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "log-tools-range-ext-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        val input = File(dir, "app.bin").apply {
            writeText("2026-09-29 10:00:00.000 D/Main: x\n", Charsets.UTF_8)
            deleteOnExit()
        }
        assertFailsWith<IllegalArgumentException> {
            LogUtilsCommand().parse(
                listOf(
                    "--type", "filter_by_range",
                    "-f", input.absolutePath,
                    "--startTime", "2026-09-29_10:00:00.000",
                    "--endTime", "2026-09-29_10:30:00.000",
                ),
            )
        }
    }
}
