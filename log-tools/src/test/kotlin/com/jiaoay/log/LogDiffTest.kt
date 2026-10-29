package com.jiaoay.log

import com.github.ajalt.clikt.core.parse
import com.jiaoay.log.LogDiff.DiffResult
import com.jiaoay.log.LogDiff.Hunk
import com.jiaoay.log.LogDiff.HunkLine
import com.jiaoay.log.LogDiff.LineKind
import com.jiaoay.log.LogDiff.Op
import com.jiaoay.log.LogDiff.OpKind
import com.jiaoay.log.cli.LogUtilsCommand
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.random.Random

/**
 * LogDiff 引擎确定性测试 + CLI `--type diff` 端到端：
 * 固定输入 → hunk 序列逐项断言；归一化模式「同内容不同时间戳」两份日志 diff 为空；
 * 超限退化契约；大文件行哈希策略等价性；随机小输入对 DP 最长公共子序列 oracle 的
 * 编辑距离与游程连续性核对。
 */
class LogDiffTest {

    // ── 测试基建 ──

    private fun tempFile(name: String, lines: List<String>): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "log-diff-test-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return File(dir, name).apply {
            writeText(
                if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"),
                Charsets.UTF_8,
            )
            deleteOnExit()
        }
    }

    private fun diffOk(oldLines: List<String>, newLines: List<String>, normalize: Boolean = false): DiffResult.Ok {
        val old = tempFile("old.log", oldLines)
        val new = tempFile("new.log", newLines)
        val result = LogDiff.diff(old, new, normalizeTimestamps = normalize)
        return assertIs<DiffResult.Ok>(result)
    }

    private fun hunkLine(kind: LineKind, text: String): HunkLine = HunkLine(kind, text)

    private fun ctx(text: String) = hunkLine(LineKind.CONTEXT, text)
    private fun rem(text: String) = hunkLine(LineKind.REMOVED, text)
    private fun add(text: String) = hunkLine(LineKind.ADDED, text)

    // ── 固定输入：hunk 序列逐项断言 ──

    @Test
    fun `替换加新增相邻编辑合并为单 hunk`() {
        val old = (1..10).map { "l$it" }.toMutableList()
        val new = old.toMutableList().apply {
            this[3] = "l4-new"
            add(7, "l7-extra")
        }
        val ok = diffOk(old, new)
        assertFalse(ok.degraded)
        assertEquals(10, ok.oldLineCount)
        assertEquals(11, ok.newLineCount)
        assertEquals(1, ok.hunks.size)
        val hunk = ok.hunks[0]
        assertEquals(Hunk(oldStart = 0, oldLines = 10, newStart = 0, newLines = 11, lines = listOf(
            ctx("l1"), ctx("l2"), ctx("l3"),
            rem("l4"), add("l4-new"),
            ctx("l5"), ctx("l6"), ctx("l7"),
            add("l7-extra"),
            ctx("l8"), ctx("l9"), ctx("l10"),
        )), hunk)
    }

    @Test
    fun `间隔达到两倍上下文时切分为双 hunk`() {
        val old = (1..14).map { "l%02d".format(it) }
        val new = old.toMutableList().apply {
            this[1] = "L02"
            this[12] = "L13"
        }
        val ok = diffOk(old, new)
        assertFalse(ok.degraded)
        assertEquals(2, ok.hunks.size)
        assertEquals(
            Hunk(0, 5, 0, 5, listOf(ctx("l01"), rem("l02"), add("L02"), ctx("l03"), ctx("l04"), ctx("l05"))),
            ok.hunks[0],
        )
        assertEquals(
            Hunk(9, 5, 9, 5, listOf(ctx("l10"), ctx("l11"), ctx("l12"), rem("l13"), add("L13"), ctx("l14"))),
            ok.hunks[1],
        )
    }

    @Test
    fun `两份文件全等则 hunk 序列为空`() {
        val lines = listOf("a", "b", "c")
        val ok = diffOk(lines, lines)
        assertEquals(0, ok.hunks.size)
        assertFalse(ok.degraded)
    }

    @Test
    fun `旧文件为空时整体新增`() {
        val ok = diffOk(emptyList(), listOf("n1", "n2", "n3"))
        assertEquals(1, ok.hunks.size)
        assertEquals(
            Hunk(0, 0, 0, 3, listOf(add("n1"), add("n2"), add("n3"))),
            ok.hunks[0],
        )
        assertEquals(0, ok.oldLineCount)
        assertEquals(3, ok.newLineCount)
    }

    @Test
    fun `新文件为空时整体删除`() {
        val ok = diffOk(listOf("o1", "o2"), emptyList())
        assertEquals(1, ok.hunks.size)
        assertEquals(
            Hunk(0, 2, 0, 0, listOf(rem("o1"), rem("o2"))),
            ok.hunks[0],
        )
    }

    @Test
    fun `对齐游程序列确定性断言`() {
        val old = listOf("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "l9", "l10")
        val new = old.toMutableList().apply {
            this[3] = "l4-new"
            add(7, "l7-extra")
        }
        fun hashes(lines: List<String>) = LongArray(lines.size) { i -> hashOfTest(lines[i]) }
        val aligned = LogDiff.alignOps(hashes(old), hashes(new))
        assertFalse(aligned.degraded)
        assertContentEquals(
            listOf(
                Op(OpKind.MATCH, 0, 0, 3),
                Op(OpKind.DELETE, 3, 3, 1),
                Op(OpKind.INSERT, 4, 3, 1),
                Op(OpKind.MATCH, 4, 4, 3),
                Op(OpKind.INSERT, 7, 7, 1),
                Op(OpKind.MATCH, 7, 8, 3),
            ),
            aligned.ops,
        )
        // 确定性：同一输入重复对齐结果一致
        val again = LogDiff.alignOps(hashes(old), hashes(new))
        assertContentEquals(aligned.ops, again.ops)
    }

    private fun hashOfTest(line: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (ch in line) hash = (hash xor ch.code.toLong()) * 0x100000001b3L
        return hash
    }

    // ── 时间戳归一化对齐 ──

    @Test
    fun `归一化模式下同内容不同时间戳的两份日志 diff 为空`() {
        val old = listOf(
            "08-27 14:41:50.686 D/TAG: hello",
            "08-27 14:41:51.000 D/TAG: world",
        )
        val new = listOf(
            "08-27 14:42:03.111 D/TAG: hello",
            "08-27 14:42:03.250 D/TAG: world",
        )
        val normalized = diffOk(old, new, normalize = true)
        assertEquals(0, normalized.hunks.size, "仅时间戳不同的两份日志归一化后应全等")
        assertFalse(normalized.degraded)

        val raw = diffOk(old, new, normalize = false)
        assertEquals(1, raw.hunks.size, "非归一化模式应报出差异")
    }

    @Test
    fun `归一化模式复用 TimestampNormalizer 对齐 epoch 与可读两种行首`() {
        // epoch 形态与可读形态表达同一瞬间：先归一化再掩码后应视为同一行
        val old = listOf("[Asia/Shanghai]-1787812910686 D/TAG: hello")
        val new = listOf("08-27 14:41:50.686 D/TAG: hello")
        assertEquals(0, diffOk(old, new, normalize = true).hunks.size)

        // epoch 与 epoch、不同瞬间：两次复现的时钟差被抹平
        val old2 = listOf("[Asia/Shanghai]-1787812910686 D/TAG: hello")
        val new2 = listOf("[Asia/Shanghai]-1787812955999 D/TAG: hello")
        assertEquals(0, diffOk(old2, new2, normalize = true).hunks.size)

        // 内容真差异不被归一化吞掉
        val old3 = listOf("08-27 14:41:50.686 D/TAG: hello")
        val new3 = listOf("08-27 14:41:50.686 D/TAG: world")
        assertEquals(1, diffOk(old3, new3, normalize = true).hunks.size)
    }

    @Test
    fun `归一化模式下 hunk 显示行原文`() {
        val old = listOf("08-27 14:41:50.686 D/TAG: a", "08-27 14:41:50.700 D/TAG: b")
        val new = listOf("08-27 14:41:50.686 D/TAG: a", "08-27 14:41:50.700 D/TAG: changed")
        val ok = diffOk(old, new, normalize = true)
        assertEquals(1, ok.hunks.size)
        // 上下文行取 old 侧原文，时间戳原样展示
        assertEquals(
            listOf(ctx("08-27 14:41:50.686 D/TAG: a"), rem("08-27 14:41:50.700 D/TAG: b")),
            ok.hunks[0].lines.take(2),
        )
    }

    // ── 超限退化契约 ──

    @Test
    fun `编辑距离超上限时退化为整体替换并置 degraded`() {
        val old = ArrayList<String>(5000).apply { add("head"); repeat(4999) { add("o$it") } }
        val new = ArrayList<String>(5000).apply { add("head"); repeat(4999) { add("n$it") } }
        val ok = diffOk(old, new)
        assertTrue(ok.degraded, "9998 处编辑超过对齐上限应退化")
        assertEquals(1, ok.hunks.size)
        val hunk = ok.hunks[0]
        assertEquals(0, hunk.oldStart)
        assertEquals(5000, hunk.oldLines)
        assertEquals(5000, hunk.newLines)
        // 公共前缀行保留为上下文，其余整体替换：1 上下文 + 4999 删 + 4999 增
        assertEquals(9999, hunk.lines.size)
        assertEquals(LineKind.CONTEXT, hunk.lines[0].kind)
        assertEquals("head", hunk.lines[0].text)
        assertEquals(4999, hunk.lines.count { it.kind == LineKind.REMOVED })
        assertEquals(4999, hunk.lines.count { it.kind == LineKind.ADDED })
    }

    // ── 大文件：行哈希有界策略等价性 ──

    @Test
    fun `六万行大文件三处差异的窗口与内容逐项正确`() {
        val total = 60_000
        val old = (0 until total).map { "line-$it" }
        val new = old.toMutableList().apply {
            this[10_000] = "line-10000-changed"
            removeAt(30_000)
            add(50_001, "inserted-extra")
        }
        val ok = diffOk(old, new)
        assertFalse(ok.degraded)
        assertEquals(total, ok.oldLineCount)
        assertEquals(total, ok.newLineCount)
        assertEquals(3, ok.hunks.size)

        val replace = ok.hunks[0]
        assertEquals(9_997, replace.oldStart)
        assertEquals(7, replace.oldLines)
        assertEquals(7, replace.newLines)
        assertEquals(8, replace.lines.size)
        assertEquals(rem("line-10000"), replace.lines[3])
        assertEquals(add("line-10000-changed"), replace.lines[4])

        val delete = ok.hunks[1]
        assertEquals(29_997, delete.oldStart)
        assertEquals(7, delete.oldLines)
        assertEquals(6, delete.newLines)
        assertEquals(rem("line-30000"), delete.lines[3])

        val insert = ok.hunks[2]
        assertEquals(49_999, insert.oldStart)
        assertEquals(49_998, insert.newStart)
        assertEquals(6, insert.oldLines)
        assertEquals(7, insert.newLines)
        assertEquals(add("inserted-extra"), insert.lines[3])
    }

    // ── 性质测试：Myers 对齐对 DP 最长公共子序列 oracle 等价 ──

    @Test
    fun `随机小输入的编辑距离与 DP oracle 一致且游程连续`() {
        val random = Random(42)
        repeat(300) { case ->
            val n = random.nextInt(0, 25)
            val m = random.nextInt(0, 25)
            val a = LongArray(n) { random.nextLong(1, 6) }
            val b = LongArray(m) { random.nextLong(1, 6) }
            val aligned = LogDiff.alignOps(a, b)
            assertFalse(aligned.degraded, "case $case: 小输入不应退化")

            // 游程连续性：两坐标系上恰好覆盖全部行
            var aPos = 0
            var bPos = 0
            for (op in aligned.ops) {
                assertEquals(aPos, op.aStart, "case $case: a 连续性")
                assertEquals(bPos, op.bStart, "case $case: b 连续性")
                aPos += op.aExtent
                bPos += op.bExtent
            }
            assertEquals(n, aPos, "case $case: a 全覆盖")
            assertEquals(m, bPos, "case $case: b 全覆盖")

            // MATCH 游程两侧行哈希必须相等
            for (op in aligned.ops) {
                if (op.kind == OpKind.MATCH) {
                    repeat(op.count) { i -> assertEquals(a[op.aStart + i], b[op.bStart + i], "case $case: 匹配段哈希") }
                }
            }

            val editCount = aligned.ops.filter { it.kind != OpKind.MATCH }.sumOf { it.count }
            assertEquals(dpEditDistance(a, b), editCount, "case $case: 编辑距离应等于 DP oracle")
        }
    }

    /** DP 最长公共子序列基准：编辑距离 = (n - L) + (m - L) */
    private fun dpEditDistance(a: LongArray, b: LongArray): Int {
        val dp = IntArray(b.size + 1)
        for (i in 1..a.size) {
            var diag = 0
            for (j in 1..b.size) {
                val saved = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) diag + 1 else maxOf(dp[j], dp[j - 1])
                diag = saved
            }
        }
        return a.size + b.size - 2 * dp[b.size]
    }

    // ── CLI 端到端 ──

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
    fun `CLI diff 成功路径输出产物与 hunk 计数锚`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "log-diff-cli-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        val old = File(dir, "old.txt").apply {
            writeText("a\nb\nc\n", Charsets.UTF_8); deleteOnExit()
        }
        val new = File(dir, "new.txt").apply {
            writeText("a\nB\nc\n", Charsets.UTF_8); deleteOnExit()
        }
        val output = captureStdout {
            LogUtilsCommand().parse(listOf("--type", "diff", "-f", old.absolutePath, "--other", new.absolutePath))
        }
        assertTrue("success! outputFile:" in output, "stdout 应含成功锚: $output")
        assertTrue("hunks: 1" in output, "stdout 应含 hunk 计数锚: $output")

        val outFile = File(dir, "old.diff_new.txt")
        assertTrue(outFile.isFile, "产物应落在基准文件同目录")
        val lines = outFile.readLines(Charsets.UTF_8)
        assertEquals("--- old.txt", lines[0])
        assertEquals("+++ new.txt", lines[1])
        assertEquals("@@ -1,3 +1,3 @@", lines[2])
        assertEquals(" a", lines[3])
        assertEquals("-b", lines[4])
        assertEquals("+B", lines[5])
        assertEquals(" c", lines[6])
    }

    @Test
    fun `CLI diff 归一化模式端到端 hunk 计数为零`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "log-diff-cli-norm-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        val old = File(dir, "old.log").apply {
            writeText("08-27 10:00:00.000 D/A: x\n08-27 10:00:01.000 D/A: y\n", Charsets.UTF_8); deleteOnExit()
        }
        val new = File(dir, "new.log").apply {
            writeText("08-27 10:05:00.123 D/A: x\n08-27 10:05:01.456 D/A: y\n", Charsets.UTF_8); deleteOnExit()
        }
        val output = captureStdout {
            LogUtilsCommand().parse(
                listOf(
                    "--type", "diff",
                    "-f", old.absolutePath, "--other", new.absolutePath,
                    "--normalizeTimestamps",
                ),
            )
        }
        assertTrue("hunks: 0" in output, "归一化模式下应零 hunk: $output")
        val outFile = File(dir, "old.diff_new.txt")
        assertEquals(2, outFile.readLines(Charsets.UTF_8).size, "无差异产物只有两行文件头")
    }

    @Test
    fun `CLI diff 缺 other 旗标按必填校验失败`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "log-diff-cli-miss-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        val old = File(dir, "old.txt").apply { writeText("a\n", Charsets.UTF_8); deleteOnExit() }
        assertFailsWith<IllegalArgumentException> {
            LogUtilsCommand().parse(listOf("--type", "diff", "-f", old.absolutePath))
        }
    }

    @Test
    fun `CLI diff 对不存在文件报参数错误`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "log-diff-cli-nofile-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        val old = File(dir, "old.txt").apply { writeText("a\n", Charsets.UTF_8); deleteOnExit() }
        assertFailsWith<com.github.ajalt.clikt.core.UsageError> {
            LogUtilsCommand().parse(
                listOf("--type", "diff", "-f", old.absolutePath, "--other", File(dir, "missing.txt").absolutePath),
            )
        }
    }

    @Test
    fun `CLI diff 不支持后缀按既有扩展校验失败`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "log-diff-cli-ext-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        val old = File(dir, "old.bin").apply { writeText("a\n", Charsets.UTF_8); deleteOnExit() }
        val new = File(dir, "new.txt").apply { writeText("a\n", Charsets.UTF_8); deleteOnExit() }
        assertFailsWith<IllegalArgumentException> {
            LogUtilsCommand().parse(listOf("--type", "diff", "-f", old.absolutePath, "--other", new.absolutePath))
        }
    }
}
