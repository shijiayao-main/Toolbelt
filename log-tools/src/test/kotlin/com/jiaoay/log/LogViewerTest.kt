package com.jiaoay.log

import com.jiaoay.xlog.helpers.XLogTestFactory
import java.io.File
import java.io.FileInputStream
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 大文件查看引擎特征测试：任意行号窗口内容正确性、内存上界（窗口钳制 + 索引字节契约）、
 * 终止符三形态与缓冲区边界、空文件/尾部/越界窗口、格式路由、查看期间漂移失效、级别单源提取、
 * 时间戳导航（跨窗 seek / 年份锚定 / 宁拒不猜）、行偏移索引两遍扫描等价锚与超限拒绝门。
 */
class LogViewerTest {

    private fun tempFile(name: String, content: ByteArray): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "log-viewer-test-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return File(dir, name).apply {
            writeBytes(content)
            deleteOnExit()
        }
    }

    private fun tempFile(name: String, text: String): File = tempFile(name, text.toByteArray(Charsets.UTF_8))

    private fun openOk(file: File): LogViewSession {
        val result = LogViewer.open(file)
        return assertIs<LogViewer.OpenResult.Ok>(result).session
    }

    private fun texts(window: List<ViewerLine>): List<String> = window.map { it.text }

    // ── 任意行号窗口内容正确 ──

    @Test
    fun `任意行号窗口内容与展示行号正确`() {
        val lines = (0 until 100).map { "L%03d 中文内容行 ${it % 7}".format(it) }
        val session = openOk(tempFile("win.log", lines.joinToString("\n") + "\n"))
        assertEquals(100, session.totalLines)

        val head = session.readWindow(0, 10)
        assertEquals(lines.take(10), texts(head))
        assertEquals((1..10).toList(), head.map { it.number })

        val mid = session.readWindow(42, 5)
        assertEquals(lines.slice(42 until 47), texts(mid))
        assertEquals((43..47).toList(), mid.map { it.number })

        val tail = session.readWindow(95, 10)
        assertEquals(lines.slice(95 until 100), texts(tail))
        assertEquals((96..100).toList(), tail.map { it.number })
    }

    @Test
    fun `跳转任意行号无需顺序读且内容逐字保真`() {
        val total = 50_000
        val file = tempFile("jump.log", "x")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(total) { i -> w.write("jump-line-$i-尾巴${i % 3}\n") }
        }
        val session = openOk(file)
        assertEquals(total, session.totalLines)
        val window = session.readWindow(31_337, 2)
        // 31337 % 3 == 2、31338 % 3 == 0（行尾「尾巴N」由行号模 3 决定）
        assertEquals(listOf("jump-line-31337-尾巴2", "jump-line-31338-尾巴0"), texts(window))
        assertEquals(listOf(31338, 31339), window.map { it.number })
    }

    // ── 终止符三形态与边界 ──

    @Test
    fun `三种终止符混合与文末无换行尾行`() {
        val session = openOk(tempFile("terms.log", "a1\r\nb2\nc3\rd4"))
        assertEquals(4, session.totalLines)
        assertEquals(listOf("a1", "b2", "c3", "d4"), texts(session.readWindow(0, 10)))
        assertEquals(listOf("c3", "d4"), texts(session.readWindow(2, 10)))
    }

    @Test
    fun `仅换行符文件计一空行且尾起始偏移被丢弃`() {
        val session = openOk(tempFile("nl.log", "\n"))
        assertEquals(1, session.totalLines)
        assertEquals(listOf(""), texts(session.readWindow(0, 10)))

        val two = openOk(tempFile("blank.log", "a\n\n"))
        assertEquals(2, two.totalLines)
        assertEquals(listOf("a", ""), texts(two.readWindow(0, 10)))

        val plain = openOk(tempFile("one.log", "a\n"))
        assertEquals(1, plain.totalLines)
        assertEquals(listOf("a"), texts(plain.readWindow(0, 10)))
    }

    @Test
    fun `行内孤回车按终止符切分与 readLine 口径一致`() {
        val session = openOk(tempFile("cr.log", "ab\rcd\n"))
        assertEquals(2, session.totalLines)
        assertEquals(listOf("ab", "cd"), texts(session.readWindow(0, 10)))
    }

    @Test
    fun `连续回车跨缓冲区边界终止符判定正确`() {
        // 65535 字节行 + "\r\n"：'\r' 落在 64KB 缓冲区末字节，'\n' 在下一缓冲区首
        val longLine = "x".repeat(64 * 1024 - 1)
        val session = openOk(tempFile("edge1.log", longLine + "\r\nsecond"))
        assertEquals(2, session.totalLines)
        val window = session.readWindow(0, 2)
        assertEquals(listOf(longLine, "second"), texts(window))

        // 65535 字节行 + 孤立 '\r'：跨缓冲区后判定为单字节终止
        val session2 = openOk(tempFile("edge2.log", longLine + "\rtail"))
        assertEquals(2, session2.totalLines)
        assertEquals(listOf(longLine, "tail"), texts(session2.readWindow(0, 2)))
    }

    // ── 空文件 / 尾部 / 越界 ──

    @Test
    fun `空文件零行且任意窗口恒空`() {
        val session = openOk(tempFile("empty.log", ""))
        assertEquals(0, session.totalLines)
        assertEquals(0L, session.indexMemoryBytes)
        assertTrue(session.readWindow(0, 10).isEmpty())
        assertTrue(session.readWindow(5, 10).isEmpty())
    }

    @Test
    fun `尾部窗口只返回剩余行且越界起点返回空表`() {
        val lines = (0 until 10).map { "n$it" }
        val session = openOk(tempFile("tail.log", lines.joinToString("\n", postfix = "\n")))
        val window = session.readWindow(8, 100)
        assertEquals(listOf("n8", "n9"), texts(window))
        assertTrue(session.readWindow(10, 5).isEmpty())
        assertTrue(session.readWindow(99, 5).isEmpty())
    }

    // ── 内存上界（窗口钳制 + 索引字节契约）──

    @Test
    fun `窗口行数被硬上界钳制且索引驻留为每行八字节`() {
        val total = 20_000
        val file = tempFile("cap.log", "x")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(total) { i -> w.write("cap-$i\n") }
        }
        val session = openOk(file)
        val window = session.readWindow(0, Int.MAX_VALUE)
        assertEquals(LogViewer.MAX_WINDOW_LINES, window.size, "请求超限必须被钳制到硬上界")
        assertEquals("cap-4999", window.last().text)
        assertEquals(5000, window.last().number)
        assertEquals(total * 8L, session.indexMemoryBytes, "行偏移索引驻留 = 8 字节/行")
    }

    @Test
    fun `大文件散点窗口内容正确且窗口外行不进入返回集`() {
        val total = 200_000
        val file = tempFile("big.log", "x")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(total) { i -> w.write("L%06d 中文尾巴\n".format(i)) }
        }
        val session = openOk(file)
        assertEquals(total, session.totalLines)
        assertEquals(total * 8L, session.indexMemoryBytes)

        fun expect(i: Int) = "L%06d 中文尾巴".format(i)
        for (start in listOf(0, 1, 123, 99_999, 199_990)) {
            val window = session.readWindow(start, 7)
            assertEquals(7, window.size, "起点 $start 窗口应满额返回")
            assertEquals((start + 1..start + 7).toList(), window.map { it.number })
            assertEquals((start until start + 7).map { expect(it) }, texts(window))
        }
        val tail = session.readWindow(199_998, 100)
        assertEquals(listOf(expect(199_998), expect(199_999)), texts(tail))
    }

    // ── 参数门禁 ──

    @Test
    fun `负起点与非正行数按编程错误拒绝`() {
        val session = openOk(tempFile("gate.log", "a\nb\n"))
        assertFailsWith<IllegalArgumentException> { session.readWindow(-1, 5) }
        assertFailsWith<IllegalArgumentException> { session.readWindow(0, 0) }
        assertFailsWith<IllegalArgumentException> { session.readWindow(0, -3) }
    }

    // ── 格式路由 ──

    @Test
    fun `加密段结构文件被路由拒绝且typed档位为加密`() {
        val xlog = tempFile(
            "real.xlog",
            XLogTestFactory.segment(mark = 0x03, payload = "hello log".toByteArray()),
        )
        val result = LogViewer.open(xlog)
        val failure = assertIs<LogViewer.OpenResult.Failure>(result)
        assertTrue("需先解密" in failure.reason, "拒绝理由应给出可操作指引：${failure.reason}")
        // 加密档位（typed 判型单源）：消费方以此渲染「送解密」衔接，不依赖文案匹配
        assertTrue(failure.encrypted, "XLOG_SEGMENTS 拒绝须置 encrypted=true（构造点唯一）")
    }

    @Test
    fun `非加密失败的typed档位恒为非加密`() {
        val missing = tempFile("missing.log", "x").apply { delete() }
        val failure = assertIs<LogViewer.OpenResult.Failure>(LogViewer.open(missing))
        assertFalse(failure.encrypted, "文件不存在失败不得误标加密档（误标即误渲染送解密入口）")
    }

    @Test
    fun `明文与未知形态均按文本可看`() {
        val plain = tempFile("plain.txt", "2026-09-29 10:00:00.000 D/Main: hello\n")
        assertEquals(1, openOk(plain).totalLines)

        // UNKNOWN（无可读时间戳的普通文本）不拒绝——查看只读无害
        val unknown = tempFile("note.txt", "随便一段文本，没有时间戳\nsecond line\n")
        val session = openOk(unknown)
        assertEquals(2, session.totalLines)
        assertEquals(listOf("随便一段文本，没有时间戳", "second line"), texts(session.readWindow(0, 10)))
    }

    // ── 查看期间漂移 ──

    @Test
    fun `文件长度变化后窗口读取按失效拒绝`() {
        val file = tempFile("drift.log", "a\nb\nc\n")
        val session = openOk(file)
        assertEquals(3, session.totalLines)
        file.appendText("d\n")
        assertFailsWith<IllegalStateException> { session.readWindow(0, 2) }
        // 重新打开后按新内容工作
        assertEquals(4, openOk(file).totalLines)
    }

    // ── 级别单源提取 ──

    @Test
    fun `级别提取复用块头解析且续行为中性`() {
        val content = listOf(
            "05-09 10:00:00.000 E/Main: 崩溃了",
            "    at com.foo.Bar.baz(Bar.kt:1)",
            "05-09 10:00:01.000 W/Worker: 慢了",
            "09-29 10:00:02.000 123 456 I Tag: threadtime 形态",
            "没有级别的一行",
        ).joinToString("\n", postfix = "\n")
        val window = openOk(tempFile("level.log", content)).readWindow(0, 10)
        assertEquals(LogStatsAnalyzer.Level.ERROR, window[0].level)
        assertNull(window[1].level, "续行应为中性（null）")
        assertEquals(LogStatsAnalyzer.Level.WARN, window[2].level)
        assertEquals(LogStatsAnalyzer.Level.INFO, window[3].level)
        assertNull(window[4].level)
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            window.map { it.number },
        )
    }

    // ── 级别导航查询 ──

    @Test
    fun `级别导航命中跨窗口偏移且起点为含`() {
        // 12000 行、E 散点 3/7500/11999、W 散点 6000（跨两个以上扫描窗，验证逐窗 seek）
        val total = 12_000
        val file = tempFile("nav.log", "x")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(total) { i ->
                when (i) {
                    3, 7_500, 11_999 -> w.write("05-09 10:00:00.000 E/Main: boom-$i\n")
                    6_000 -> w.write("05-09 10:00:00.000 W/Worker: slow-$i\n")
                    else -> w.write("05-09 10:00:00.000 D/Main: plain-$i\n")
                }
            }
        }
        val session = openOk(file)
        val errors = setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)

        assertEquals(3, session.nextLevelLine(0, errors))
        // 起点 4 在首个扫描窗内无命中，命中 7500 须由下一窗 seek 得到
        assertEquals(7_500, session.nextLevelLine(4, errors))
        assertEquals(11_999, session.nextLevelLine(7_501, errors))
        // 起点即命中行：含语义（连点逐错误行为依赖此口径）
        assertEquals(11_999, session.nextLevelLine(11_999, errors))
        assertNull(session.nextLevelLine(12_000, errors), "起点越上界应无命中")
        assertNull(session.nextLevelLine(11_999 + 1, errors), "末个命中之后再无命中")

        // E|F 档查询跳过 W 行；W 档单独命中
        assertEquals(7_500, session.nextLevelLine(4, setOf(LogStatsAnalyzer.Level.ERROR)))
        assertEquals(6_000, session.nextLevelLine(0, setOf(LogStatsAnalyzer.Level.WARN)))
    }

    @Test
    fun `级别导航无命中与空档位返回 null 且负起点拒绝`() {
        val content = listOf(
            "05-09 10:00:00.000 I/Main: info",
            "05-09 10:00:01.000 W/Worker: warn",
            "没有级别的一行",
        ).joinToString("\n", postfix = "\n")
        val session = openOk(tempFile("nomiss.log", content))
        assertNull(session.nextLevelLine(0, setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)))
        assertNull(session.nextLevelLine(0, emptySet()), "空档位恒无命中")
        assertNull(
            session.nextLevelLine(2, setOf(LogStatsAnalyzer.Level.WARN)),
            "起点之后无 W 应返回 null",
        )
        assertFailsWith<IllegalArgumentException> { session.nextLevelLine(-1, setOf(LogStatsAnalyzer.Level.WARN)) }
    }

    @Test
    fun `级别导航跨扫描窗无命中走满末窗返回 null`() {
        // 5500 行全 I（跨两个扫描窗），E|F 档查询须走满末窗后返回 null 而非卡死或误报
        val total = 5_500
        val file = tempFile("allinfo.log", "x")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(total) { w.write("05-09 10:00:00.000 I/Main: info-$it\n") }
        }
        val session = openOk(file)
        assertEquals(5_500, session.totalLines)
        assertNull(session.nextLevelLine(0, setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)))
    }

    @Test
    fun `级别反向导航命中跨窗口且起点为不含`() {
        // 12000 行、E 散点 3/7500/11999、W 散点 6000（命中间隔超扫描窗宽，验证反向逐窗 seek）
        val total = 12_000
        val file = tempFile("nav-prev.log", "x")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(total) { i ->
                when (i) {
                    3, 7_500, 11_999 -> w.write("05-09 10:00:00.000 E/Main: boom-$i\n")
                    6_000 -> w.write("05-09 10:00:00.000 W/Worker: slow-$i\n")
                    else -> w.write("05-09 10:00:00.000 D/Main: plain-$i\n")
                }
            }
        }
        val session = openOk(file)
        val errors = setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)

        // 上界即命中行：不含语义（连点回退不重复命中当前行依赖此口径）
        assertNull(session.prevLevelLine(3, errors), "上界即命中行本身不应命中")
        assertEquals(3, session.prevLevelLine(4, errors))
        assertEquals(7_500, session.prevLevelLine(7_501, errors), "命中在上界同窗内应直接取到")
        // 上界 7000 距最近命中 3 超 5000 行：须跨窗反向 seek（末窗 [2000,7000) 空后取首窗）
        assertEquals(3, session.prevLevelLine(7_000, errors))
        assertEquals(11_999, session.prevLevelLine(total, errors), "上界越 EOF 按末行计（先扫到最靠后命中）")
        assertNull(session.prevLevelLine(0, errors), "首行之前无命中")
        assertEquals(6_000, session.prevLevelLine(7_000, setOf(LogStatsAnalyzer.Level.WARN)))
    }

    @Test
    fun `级别反向导航无命中与空档位返回 null 且负起点拒绝`() {
        val content = listOf(
            "05-09 10:00:00.000 I/Main: info",
            "05-09 10:00:01.000 W/Worker: warn",
            "没有级别的一行",
        ).joinToString("\n", postfix = "\n")
        val session = openOk(tempFile("nomiss-prev.log", content))
        assertNull(
            session.prevLevelLine(3, setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)),
            "全文件无 E|F 应无命中",
        )
        assertNull(session.prevLevelLine(3, emptySet()), "空档位恒无命中")
        assertNull(
            session.prevLevelLine(1, setOf(LogStatsAnalyzer.Level.WARN)),
            "上界之前无 W 应返回 null",
        )
        assertFailsWith<IllegalArgumentException> { session.prevLevelLine(-1, setOf(LogStatsAnalyzer.Level.WARN)) }
    }

    // ── 文本搜索查询 ──

    @Test
    fun `搜索命中跨窗口收集且大小写不敏感子串`() {
        // 12000 行、needle 散点 3/5000/11999（分落三个扫描窗，验证逐窗 seek 收集）
        val total = 12_000
        val file = tempFile("search.log", "x")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(total) { i ->
                when (i) {
                    3 -> w.write("NEEDLE-upper-$i\n")
                    5_000 -> w.write("needle-lower-$i\n")
                    11_999 -> w.write("prefix needle-in-middle $i\n")
                    else -> w.write("plain-$i\n")
                }
            }
        }
        val session = openOk(file)

        val result = session.searchLines(0, "NeEdLe")
        assertEquals(listOf(3, 5_000, 11_999), result.matchedLineIndexes, "三种形态命中均按行下标升序收集")
        assertFalse(result.truncated)

        // 起点含语义：起点即命中行也入集；收集恒自起点走到文件末（非单点）
        assertEquals(listOf(3, 5_000, 11_999), session.searchLines(3, "needle").matchedLineIndexes)
        assertEquals(listOf(5_000, 11_999), session.searchLines(4, "needle").matchedLineIndexes)
        assertEquals(listOf(11_999), session.searchLines(11_999, "needle").matchedLineIndexes)
        assertTrue(session.searchLines(12_000, "needle").matchedLineIndexes.isEmpty(), "起点越上界应空集")
    }

    @Test
    fun `搜索无匹配返回空集且空关键词与负起点按编程错误拒绝`() {
        val session = openOk(tempFile("nomatch.log", "a\nb\nc\n"))
        val result = session.searchLines(0, "zzz")
        assertTrue(result.matchedLineIndexes.isEmpty(), "无匹配应返回空集")
        assertFalse(result.truncated)
        assertFailsWith<IllegalArgumentException> { session.searchLines(0, "") }
        assertFailsWith<IllegalArgumentException> { session.searchLines(-1, "a") }
    }

    @Test
    fun `搜索结果集被硬上界钳制且超限明示截断`() {
        // 6000 行全命中（超结果集上界），须在首扫描窗内即达上界止步而非全量收集
        val total = 6_000
        val file = tempFile("flood.log", "x")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(total) { w.write("hit-$it\n") }
        }
        val session = openOk(file)
        val result = session.searchLines(0, "hit")
        assertEquals(LogViewer.MAX_SEARCH_RESULTS, result.matchedLineIndexes.size, "结果集必须被硬上界钳制")
        assertTrue(result.truncated, "达上界必须明示截断（不得当作匹配总数）")
        assertEquals((0 until LogViewer.MAX_SEARCH_RESULTS).toList(), result.matchedLineIndexes)
    }

    @Test
    fun `文件长度变化后搜索按失效拒绝`() {
        val file = tempFile("search-drift.log", "a\nb\nc\n")
        val session = openOk(file)
        file.appendText("d\n")
        assertFailsWith<IllegalStateException> { session.searchLines(0, "b") }
    }

    // ── 时间戳导航查询 ──

    @Test
    fun `时间戳导航命中跨窗口且为首个不早于目标的行`() {
        // 12000 行、时间行散点 3/6000/11000（0 基，两处在首窗之外），验证逐窗 seek 与首个命中
        val total = 12_000
        val file = tempFile("ts-nav.log", "x")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(total) { i ->
                when (i) {
                    3 -> w.write("05-09 09:59:59.999 D/Main: early\n")
                    6_000 -> w.write("05-09 13:25:30.000 E/Main: crash\n")
                    11_000 -> w.write("05-09 23:59:00.000 I/Main: late\n")
                    else -> w.write("plain-$i\n")
                }
            }
        }
        val session = openOk(file)
        val target = LocalDateTime.of(2026, 5, 9, 13, 25, 30, 0)

        // 首窗内的时间行 3 早于目标被跳过，命中 6000 须由第二扫描窗 seek 得到
        assertEquals(6_000, session.nextTimestampLine(0, target))
        // 目标早于命中行时间戳 1 毫秒：仍是 6000（首个 ≥ 目标）
        assertEquals(6_000, session.nextTimestampLine(0, target.minusNanos(1_000_000)))
        // 起点即命中行：含语义（与 nextLevelLine 同口径）
        assertEquals(6_000, session.nextTimestampLine(6_000, target))
        assertEquals(11_000, session.nextTimestampLine(6_001, target))
        assertNull(session.nextTimestampLine(11_001, target), "末个时间行之后无命中")
        assertNull(session.nextTimestampLine(12_000, target), "起点越上界应无命中")
        assertFailsWith<IllegalArgumentException> { session.nextTimestampLine(-1, target) }
    }

    @Test
    fun `时间戳导航裸格式行年份锚定取目标年份`() {
        val content = listOf(
            "2026-05-09 13:25:30.000 I/Main: explicit",
            "05-09 13:25:31.000 D/Main: bare",
        ).joinToString("\n", postfix = "\n")
        val session = openOk(tempFile("ts-year.log", content))

        // 目标年份远超任何行内年份：年份格式行（自持年份）早于目标被跳过；裸格式行
        // 须按目标年份锚定才 ≥ 目标——若锚定误取当前时钟年份则恒 null（口径反证）
        assertEquals(1, session.nextTimestampLine(0, LocalDateTime.of(2099, 5, 9, 13, 25, 30, 0)))
        assertNull(session.nextTimestampLine(0, LocalDateTime.of(2099, 5, 9, 13, 25, 31, 500)), "毫秒之后无命中")
    }

    @Test
    fun `时间戳导航无时间行与目标超尾返回 null`() {
        val none = openOk(tempFile("ts-none.log", "a\nb\nc\n"))
        assertNull(none.nextTimestampLine(0, LocalDateTime.of(2026, 5, 9, 0, 0)), "无时间行恒无命中")

        val tail = openOk(tempFile("ts-tail.log", "05-09 10:00:00.000 D/Main: only\n"))
        assertNull(tail.nextTimestampLine(0, LocalDateTime.of(2026, 5, 9, 10, 0, 0, 1)), "目标晚于唯一时间行 1 毫秒应 null")
        assertNull(tail.nextTimestampLine(0, LocalDateTime.of(2026, 5, 9, 11, 0)), "目标超尾应 null 不猜落点")
    }

    // ── 行偏移索引两遍扫描精确分配（等价锚 + 超限拒绝门）──

    /**
     * 倍增扩容参考实现（等价锚）：扫描口径与生产实现同一状态机、以逐条追加 + 倍增
     * 拷贝建索引——生产实现的两遍扫描（先数行、后精确分配填充）产出须与之逐元素一致。
     */
    private fun referenceDoublingLineStarts(file: File, length: Long): LongArray {
        FileInputStream(file).use { input ->
            var starts = LongArray(1024)
            var count = 0
            fun addStart(offset: Long) {
                if (count == starts.size) starts = starts.copyOf(starts.size * 2)
                starts[count++] = offset
            }
            addStart(0L)
            val buf = ByteArray(64 * 1024)
            var base = 0L
            var carryCr = false
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                var i = 0
                while (i < n) {
                    val b = buf[i]
                    val pos = base + i
                    if (carryCr) {
                        addStart(pos + if (b == 0x0A.toByte()) 1L else 0L)
                        carryCr = b == 0x0D.toByte()
                        i++
                    } else {
                        when (b) {
                            0x0A.toByte() -> {
                                addStart(pos + 1L)
                                i++
                            }

                            0x0D.toByte() -> {
                                carryCr = true
                                i++
                            }

                            else -> i++
                        }
                    }
                }
                base += n
            }
            if (count > 0 && starts[count - 1] == length) count--
            return starts.copyOf(count)
        }
    }

    @Test
    fun `两遍扫描产出与倍增参考实现逐元素等价`() {
        // 65535 字节行 + "\r\r"：'\r' 落在 64KB 缓冲区末字节，下一缓冲区首字节再 '\r'
        val crAtBufferEdge = "x".repeat(64 * 1024 - 1) + "\r\rt\n"
        // 跨多缓冲区混合终止符 + 无终止符尾行
        val multiBuffer = buildString {
            repeat(3) { append("y".repeat(64 * 1024)).append("\r\n") }
            append("z".repeat(64 * 1024 + 10)).append('\r')
            append("tail-no-terminator")
        }
        val cases: List<Pair<String, String>> = listOf(
            "空文件" to "",
            "单行无尾换行" to "only",
            "单行带尾换行" to "a\n",
            "单个换行符" to "\n",
            "连续空行" to "a\n\n\nb\n",
            "终止符三形态混合" to "a1\r\nb2\nc3\rd4",
            "孤回车结尾" to "abc\r",
            "连续回车结尾" to "abc\r\r",
            "CRLF 结尾" to "a\r\n",
            "回车后无内容" to "\r",
            "CRLF 后无内容" to "\r\n",
            "行内孤回车" to "a\r\rb\r\nc",
            "中文尾行无换行" to "行一\n行二\n中文尾行",
            "缓冲区末字节孤回车" to crAtBufferEdge,
            "跨多缓冲区混合形态" to multiBuffer,
        )
        cases.forEachIndexed { i, (name, text) ->
            val file = tempFile("eq-$i.log", text)
            val reference = referenceDoublingLineStarts(file, file.length())
            val session = openOk(file)
            assertEquals(reference.size, session.totalLines, "[$name] 总行数应与参考实现一致")
            assertEquals(
                reference.size.toLong() * 8L,
                session.indexMemoryBytes,
                "[$name] 索引驻留字节应与参考实现一致",
            )
            assertTrue(
                session.lineStarts.contentEquals(reference),
                "[$name] 行偏移索引须与倍增参考实现逐元素一致（生产=两遍精确分配）",
            )
        }
    }

    @Test
    fun `行数超索引内存上界的文件开门拒绝且失败形态对齐先例`() {
        // 单字节空行铺满超限档（每行 1 字节）：行数 = 字节数；首遍计数达上界 + 2 条即
        // 提前止步判拒——不建索引、无大块分配，拒绝路径自身不 OOM
        val file = tempFile("huge.log", "x")
        file.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024) { '\n'.code.toByte() }
            var remaining = LogViewer.MAX_INDEX_LINES + 2
            while (remaining > 0) {
                val n = minOf(chunk.size, remaining)
                out.write(chunk, 0, n)
                remaining -= n
            }
        }
        val failure = assertIs<LogViewer.OpenResult.Failure>(LogViewer.open(file))
        assertTrue("可查看上限" in failure.reason, "拒绝理由应给出上限值：${failure.reason}")
        assertTrue("分割" in failure.reason, "拒绝理由应给出可操作指引（形态对齐 XLOG_SEGMENTS 先例）：${failure.reason}")
        assertTrue(file.name in failure.reason, "拒绝理由应含文件名（形态对齐 XLOG_SEGMENTS 先例）")
        assertFalse(failure.encrypted, "超限拒绝是普通失败档，不得误标加密（误标即误渲染送解密入口）")

        // 阈值以下文件不受拒绝门影响，正常打开
        val below = openOk(tempFile("below.log", "a\nb\n"))
        assertEquals(2, below.totalLines)
    }
}
