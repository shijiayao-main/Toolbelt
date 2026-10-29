package com.jiaoay.easy_dev_toolbox.core.evidence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 证据面解析器单测（P4-C2 openspec log-workbench 任务 5.4/5.5——直测缝；
 * 调查工作台 S4 随 EvidenceModels 上移 :core/evidence，断言零改）。
 *
 * 行格式真源对齐：时间戳 `MM-dd HH:mm:ss.SSS`（LogExts.TIME_PATTERN）+
 * logcat 风格 `L/Tag: msg`（LogAnalysisPipelineTest 夹具同构）。
 */
class EvidenceModelsTest {

    private val sampleLine = "09-19 10:29:50.000 E/CameraManager: camera route failed"

    // ── 行解析 ──

    @Test
    fun `时间行解析出级别 TAG 与消息`() {
        val line = parseLogLine(seq = 0, raw = sampleLine)
        assertEquals("09-19 10:29:50.000", line.timestamp)
        assertEquals('E', line.level)
        assertEquals("CameraManager", line.tag)
        assertEquals("camera route failed", line.message)
    }

    @Test
    fun `非时间行降级为纯文本`() {
        val line = parseLogLine(seq = 1, raw = "	at com.example.Foo.bar(Foo.kt:42)")
        assertNull(line.timestamp)
        assertNull(line.level)
        assertNull(line.tag)
        assertNull(line.message)
        assertEquals("	at com.example.Foo.bar(Foo.kt:42)", line.raw)
    }

    @Test
    fun `块内续行保留原文`() {
        // logcat 多行输出（块内续行）——时间行后续行不带时间戳
        val line = parseLogLine(seq = 2, raw = " continuation of previous message")
        assertNull(line.timestamp)
        assertEquals(" continuation of previous message", line.raw)
    }

    // ── 有界读取 ──

    @Test
    fun `有界读取封顶与不存在文件安全`() {
        val dir = kotlin.io.path.createTempDirectory("wb-evidence").toFile()
        val file = File(dir, "sample.txt")
        file.writeText((1..10).joinToString("\n") { sampleLine })
        assertEquals(10, readLogLines(file).size)
        assertEquals(3, readLogLines(file, limit = 3).size)
        assertTrue(readLogLines(File(dir, "nope.txt")).isEmpty())
    }

    // ── WB-03（重设计批 1 · 2026-09-30）：截断探测 + 总行数 ──

    @Test
    fun `超限文件 truncated true 且 totalLines 数全量`() {
        val dir = kotlin.io.path.createTempDirectory("wb-truncate").toFile()
        val file = File(dir, "big.txt")
        file.writeText((1..2500).joinToString("\n") { sampleLine })
        val result = readLogLinesResult(file)
        assertEquals(2000, result.lines.size, "窗口行流恒前 2000 行（内存有界铁律）")
        assertEquals(true, result.truncated, "2500 行 > 2000 上限 ⇒ 截断标记为 true（提示条+徽标的显示判据）")
        assertEquals(2500, result.totalLines, "totalLines = 文件真值行数（徽标「共 N 行」数据源）")
    }

    @Test
    fun `恰等于上限不截断——边界行数零提示`() {
        val dir = kotlin.io.path.createTempDirectory("wb-boundary").toFile()
        val file = File(dir, "exact.txt")
        file.writeText((1..2000).joinToString("\n") { sampleLine })
        val result = readLogLinesResult(file)
        assertEquals(2000, result.lines.size)
        assertEquals(false, result.truncated, "恰 2000 行 = 恰读完，未截断（零提示零回归边界）")
        assertEquals(2000, result.totalLines)
    }

    @Test
    fun `低于上限常规产物 truncated false 零提示`() {
        val dir = kotlin.io.path.createTempDirectory("wb-normal").toFile()
        val file = File(dir, "small.txt")
        file.writeText((1..42).joinToString("\n") { sampleLine })
        val result = readLogLinesResult(file)
        assertEquals(42, result.lines.size)
        assertEquals(false, result.truncated, "42 ≤ 2000 常规产物不截断——徽标/提示条零渲染判据")
        assertEquals(42, result.totalLines, "未截断时 totalLines = lines.size（零额外遍历契约）")
    }

    @Test
    fun `不存在文件截断探测安全降级`() {
        val dir = kotlin.io.path.createTempDirectory("wb-missing").toFile()
        val result = readLogLinesResult(File(dir, "nope.txt"))
        assertTrue(result.lines.isEmpty())
        assertEquals(false, result.truncated)
        assertEquals(0, result.totalLines)
    }

    // ── 直方图 ──

    @Test
    fun `直方图分桶计数与退化安全`() {
        val lines = listOf(
            parseLogLine(0, "09-19 10:29:50.000 D/T: a"),
            parseLogLine(1, "09-19 10:29:50.000 D/T: b"),
            parseLogLine(2, "09-19 10:29:59.000 W/T: c"),
        )
        val buckets = histogramBuckets(lines)
        assertEquals(60, buckets.size)
        assertEquals(3, buckets.sum())
        // 单时间戳退化：全进首桶
        val single = histogramBuckets(listOf(parseLogLine(0, "09-19 10:29:50.000 D/T: a")))
        assertEquals(1, single[0])
        // 无时间行：全零
        assertTrue(histogramBuckets(listOf(parseLogLine(0, "nope"))).all { it == 0 })
    }

    // ── 报告解析 ──

    @Test
    fun `报告解析因果链三态与引用时间戳`() {
        val report = """
            # 日志分析报告
            ## 结论
            ——内部异常：相机路由失败

            ## 问题根因
            **根因**：XPU 回传帧丢失
            **直接故障点**：CameraManager.route() 抛出 IllegalStateException
            **影响面**：设置页面黑屏
            - ✓ 已确认原因：XPU 帧丢失（证伪条件：XPU 日志显示连续帧）
            - ? 怀疑点：回传频率异常（证伪条件：统计窗口内频率正常）
            - ✗ 已排除：用户误触（依据：无 onClick 日志）

            ## 完整时间线
            | 时间 | 事件 |
            |---|---|
            | 09-19 10:29:50.000 | E/CameraManager: camera route failed |

            ## 关键依据
            > 09-19 10:29:50.000 E/CameraManager: camera route failed
            > 09-19 10:30:05.000 W/CameraManager: retry 1

            ## 元数据
            - 输入：a.txt
        """.trimIndent()
        val sections = parseReport(report)
        assertEquals("内部异常：相机路由失败", sections.conclusion)
        assertEquals(3, sections.chain.size)
        assertEquals("根因" to "XPU 回传帧丢失", sections.chain.first())
        assertEquals("影响面" to "设置页面黑屏", sections.chain.last())
        assertEquals(1, sections.confirmed.size)
        assertEquals(1, sections.suspected.size)
        assertEquals(1, sections.excluded.size)
        assertTrue("XPU 帧丢失" in sections.confirmed.first())
        assertEquals(
            listOf("09-19 10:29:50.000", "09-19 10:30:05.000"),
            sections.evidenceRefs.map { it.timestamp },
            "WB-10 改型：引用时间戳经 evidenceRefs 承载（tag/snippet 由页面回读拼装）",
        )
    }

    @Test
    fun `报告解析缺失段安全降级`() {
        val sections = parseReport("# 随便写的\n没有规范结构")
        assertEquals("", sections.conclusion)
        assertTrue(sections.chain.isEmpty())
        assertTrue(sections.confirmed.isEmpty())
        assertTrue(sections.evidenceRefs.isEmpty())
        assertTrue(sections.increments.isEmpty(), "无追问的报告增量段恒空（零变化判据）")
        assertTrue(sections.timelineRows.isEmpty(), "无表格段的时间线行恒空（零变化判据）")
    }

    // ── WB-08（重设计批 4）：追问增量段多段提取 ──

    @Test
    fun `两次追问提取两段增量各归各卡`() {
        // mergeFollowUp 分隔符真源直译（WorkbenchViewModel.mergeFollowUp companion）：
        // "$previous\n\n---\n\n## 增量研判（追问）\n\n$increment"
        val report = """
            # 日志分析报告
            ## 结论
            ——首次研判结论

            ## 问题根因
            **根因**：XPU 回传帧丢失

            ## 完整时间线
            | 时间 | 事件 |
            |---|---|
            | 09-19 10:29:50.000 | E/CameraManager: camera route failed |

            ## 关键依据
            > 09-19 10:29:50.000 E/CameraManager: camera route failed

            ## 元数据
            - 输入：a.txt

            ---

            ## 增量研判（追问）
            第 1 次追问的增量结论：回传频率经统计窗口验证为正常。

            ---

            ## 增量研判（追问）
            第 2 次追问的增量结论：确认是 CameraManager 路由竞态。
        """.trimIndent()
        val sections = parseReport(report)
        assertEquals(2, sections.increments.size, "两次追问 = 两段增量（sectionOf 只取首段的缺陷即本卡要修的）")
        assertTrue(
            "第 1 次追问的增量结论" in sections.increments.first(),
            "首段须是第一次追问的内容（按标题出现序）",
        )
        assertTrue(
            "第 2 次追问的增量结论" in sections.increments.last(),
            "末段须是第二次追问的内容",
        )
        assertTrue(
            sections.increments.all { "---" !in it },
            "段内 --- 分隔行剥掉（分隔符是 mergeFollowUp 的拼接物非增量内容）",
        )
    }

    @Test
    fun `单次追问提取一段增量`() {
        val report = """
            # 日志分析报告
            ## 结论
            ——结论

            ## 增量研判（追问）
            唯一一次追问的增量。
        """.trimIndent()
        val sections = parseReport(report)
        assertEquals(1, sections.increments.size)
        assertTrue("唯一一次追问的增量" in sections.increments.single())
    }

    @Test
    fun `无追问的报告增量段恒空`() {
        val report = """
            # 日志分析报告
            ## 结论
            ——无追问样本

            ## 关键依据
            > 09-19 10:29:50.000 E/CameraManager: camera route failed
        """.trimIndent()
        assertTrue(parseReport(report).increments.isEmpty(), "零追问零变化——面板零渲染判据")
    }

    // ── WB-09（重设计批 4）：时间线表格行提取 ──

    @Test
    fun `时间线表格行解析出 header 与数据行`() {
        val report = """
            # 日志分析报告
            ## 结论
            ——结论

            ## 完整时间线
            | 时间 | 事件 | 相机状态 |
            |---|---|---|
            | 09-19 10:29:50.000 | E/CameraManager: camera route failed | 打开失败 |
            | 09-19 10:30:05.000 | W/CameraManager: retry 1 | 重试 |
        """.trimIndent()
        val rows = parseReport(report).timelineRows
        assertEquals(3, rows.size, "header + 2 数据行（--- 分隔行跳过）")
        assertEquals(listOf("时间", "事件", "相机状态"), rows.first(), "首行 = header（格子 trim）")
        assertEquals(
            listOf("09-19 10:29:50.000", "E/CameraManager: camera route failed", "打开失败"),
            rows[1],
            "数据行按 | 分割（行首/行尾管道符空段剥掉）",
        )
        assertEquals("09-19 10:30:05.000", rows[2].first())
    }

    @Test
    fun `无表格段的时间线行恒空`() {
        val report = """
            # 日志分析报告
            ## 完整时间线
            （管线直跑模式——无 agent 时间线；产物文件见元数据）
        """.trimIndent()
        assertTrue(
            parseReport(report).timelineRows.isEmpty(),
            "非表格时间线段（管线直跑占位文案）恒空——零渲染判据",
        )
    }

    // ── WB-10（重设计批 4）：引用摘要回读 ──

    @Test
    fun `evidenceSnippetOf 命中行回读 TAG 与消息首行`() {
        val dir = kotlin.io.path.createTempDirectory("wb-snippet").toFile()
        val file = File(dir, "tag.filter-x.txt")
        file.writeText(
            listOf(
                "09-19 10:29:49.000 D/T: pre",
                sampleLine,
                "09-19 10:29:51.000 D/T: post",
            ).joinToString("\n"),
        )
        val snippet = evidenceSnippetOf(
            timestamp = "09-19 10:29:50.000",
            linesOf = { path -> File(path).readLines() },
            evidenceFiles = listOf(file.absolutePath),
        )
        assertEquals(
            "CameraManager" to "camera route failed",
            snippet,
            "命中行解析出 TAG + 消息体（parseLogLine 同款结构）",
        )
    }

    @Test
    fun `evidenceSnippetOf 超长消息截 60 字`() {
        val longMessage = (1..30).joinToString(" ") { "超长消息段$it" }
        val dir = kotlin.io.path.createTempDirectory("wb-snippet-long").toFile()
        val file = File(dir, "long.txt")
        file.writeText("09-19 10:29:50.000 E/CameraManager: $longMessage")
        val snippet = evidenceSnippetOf(
            timestamp = "09-19 10:29:50.000",
            linesOf = { path -> File(path).readLines() },
            evidenceFiles = listOf(file.absolutePath),
        )
        assertEquals(60, snippet?.second?.length, "消息首行截 60 字（SNIPPET_MAX_CHARS 卡面配方）")
        assertEquals("CameraManager", snippet?.first)
    }

    @Test
    fun `evidenceSnippetOf 全部文件无命中回 null`() {
        val dir = kotlin.io.path.createTempDirectory("wb-snippet-miss").toFile()
        val file = File(dir, "other.txt")
        file.writeText("09-19 11:00:00.000 D/T: 无关行")
        assertNull(
            evidenceSnippetOf(
                timestamp = "09-19 10:29:50.000",
                linesOf = { path -> File(path).readLines() },
                evidenceFiles = listOf(file.absolutePath),
            ),
            "无命中回 null——渲染面回退纯时间戳行（现状语义）",
        )
        assertNull(
            evidenceSnippetOf(
                timestamp = "09-19 10:29:50.000",
                linesOf = { path -> File(path).readLines() },
                evidenceFiles = emptyList(),
            ),
            "空证据文件集回 null（新 run 未产证据前）",
        )
    }

    // ── 引用标记协议（块级解析 + 行区间校验） ──

    @Test
    fun `新格式证据块解析出文件名与行区间`() {
        val report = """
            # 日志分析报告
            ## 结论
            ——结论

            ## 关键依据
            > [证据 tag.filter-camera.txt:L10-12]
            > 09-19 10:29:50.000 E/CameraManager: camera route failed
            > 09-19 10:30:05.000 W/CameraManager: retry 1

            ## 元数据
            - 输入：a.txt
        """.trimIndent()
        val refs = parseReport(report).evidenceRefs
        assertEquals(2, refs.size, "块内每条时间戳各产一条引用，共享同块标记")
        refs.forEach { ref ->
            assertEquals("tag.filter-camera.txt", ref.fileName, "块内时间戳绑定本块引用标记")
            assertEquals(10, ref.startLine)
            assertEquals(12, ref.endLine)
        }
        assertEquals("09-19 10:29:50.000", refs.first().timestamp)
        assertEquals("09-19 10:30:05.000", refs.last().timestamp)
        assertNull(refs.first().tag)
        assertNull(refs.first().snippet)
    }

    @Test
    fun `止号 L 双写形态与裸止号单行三形态同区间解析`() {
        // 标记条款例示为裸止号，agent 双写 `L` 前缀时不得静默降级——三形态
        // 各自独立分块绑定各自时间戳，区间语义须逐字段一致
        val report = """
            # 日志分析报告
            ## 关键依据
            > [证据 a.txt:L10-L12]
            > 09-19 10:29:50.000 E/CameraManager: camera route failed

            > [证据 a.txt:L10-12]
            > 09-19 10:30:05.000 W/CameraManager: retry 1

            > [证据 a.txt:L10]
            > 09-19 10:30:10.000 D/CameraManager: idle
        """.trimIndent()
        val refs = parseReport(report).evidenceRefs
        assertEquals(3, refs.size)
        val (dual, bare, single) = Triple(refs[0], refs[1], refs[2])
        assertEquals("a.txt", dual.fileName, "双写形态命中区间解析不静默降级")
        assertEquals(10, dual.startLine)
        assertEquals(12, dual.endLine)
        assertEquals("09-19 10:29:50.000", dual.timestamp)
        assertEquals("a.txt", bare.fileName, "裸止号旧格式恒兼容")
        assertEquals(10, bare.startLine)
        assertEquals(12, bare.endLine)
        assertEquals("a.txt", single.fileName, "单行形态恒兼容")
        assertEquals(10, single.startLine)
        assertEquals(10, single.endLine, "单行引用归一为起止同值")
    }

    @Test
    fun `双写形态解析产物过四态校验为 Verified`() {
        // 双写形态只放宽标记匹配（parse 层），行区间校验链路（validate 层）零改动——
        // 解析产物照常走区间命中 + 行首时间戳相等的既有两道硬门禁
        val report = """
            # 日志分析报告
            ## 关键依据
            > [证据 tag.filter-camera.txt:L2-L4]
            > 09-19 10:29:50.000 E/CameraManager: camera route failed
        """.trimIndent()
        val ref = parseReport(report).evidenceRefs.single()
        val window = { _: String, _: Int, _: Int ->
            listOf(
                "09-19 10:29:49.000 D/T: 区间首行前一行口径无关——校验只看请求窗口",
                "09-19 10:29:50.000 E/CameraManager: camera route failed",
                " 09-19 10:29:50.000 D/T: 续行不算行首命中",
            )
        }
        assertEquals(
            EvidenceCitationCheck.Verified,
            validateEvidenceCitation(ref, listOf("/tmp/run1/tag.filter-camera.txt"), window),
            "双写形态解析产物照常过区间命中 + 行首时间戳相等两道硬门禁",
        )
    }

    @Test
    fun `单行引用容忍为起止同值且块内位置不敏感`() {
        val report = """
            # 日志分析报告
            ## 关键依据
            > 09-19 10:29:50.000 E/CameraManager: camera route failed
            > [证据 tag.filter-camera.txt:L7]
        """.trimIndent()
        val ref = parseReport(report).evidenceRefs.single()
        assertEquals("tag.filter-camera.txt", ref.fileName)
        assertEquals(7, ref.startLine, "单行引用 L<行> 起始行")
        assertEquals(7, ref.endLine, "单行引用归一为起止同值")
        assertEquals("09-19 10:29:50.000", ref.timestamp, "绑定按块判定——时间戳行在标记行之前仍同块绑定")
    }

    @Test
    fun `旧格式报告回退纯时间戳引用且新字段全空`() {
        val report = """
            # 日志分析报告
            ## 结论
            ——结论

            ## 关键依据
            > 09-19 10:29:50.000 E/CameraManager: camera route failed
            > 09-19 10:30:05.000 W/CameraManager: retry 1

            ## 元数据
        """.trimIndent()
        val refs = parseReport(report).evidenceRefs
        assertEquals(2, refs.size)
        refs.forEach { ref ->
            assertNull(ref.fileName, "无标记报告的引用文件名 = null（旧格式零回归）")
            assertNull(ref.startLine, "无标记报告的引用起点 = null（旧格式零回归）")
            assertNull(ref.endLine, "无标记报告的引用止点 = null（旧格式零回归）")
        }
    }

    @Test
    fun `旧格式跨块重复时间戳仍全局去重`() {
        // 同一时间戳出现在两个无标记块——回退语义 = 扁平提取的全局 distinct，
        // 分块本身不得产生重复引用（旧格式解析结果逐字段一致的判定面）
        val report = """
            # 日志分析报告
            ## 关键依据
            > 09-19 10:29:50.000 E/CameraManager: camera route failed

            > 09-19 10:29:50.000 E/CameraManager: camera route failed
        """.trimIndent()
        assertEquals(
            1,
            parseReport(report).evidenceRefs.size,
            "无标记分块不改变全局去重语义（改动前 distinct 行为）",
        )
    }

    @Test
    fun `各块标记独立绑定不跨块串绑`() {
        val report = """
            # 日志分析报告
            ## 关键依据
            > [证据 a.txt:L1-2]
            > 09-19 10:29:50.000 E/CameraManager: camera route failed

            > [证据 b.txt:L9-11]
            > 09-19 10:30:05.000 W/CameraManager: retry 1

            > 09-19 10:31:00.000 D/T: 无标记块不继承他块标记
        """.trimIndent()
        val refs = parseReport(report).evidenceRefs
        assertEquals(3, refs.size)
        assertEquals("a.txt", refs[0].fileName)
        assertEquals(1, refs[0].startLine)
        assertEquals(2, refs[0].endLine)
        assertEquals("09-19 10:29:50.000", refs[0].timestamp)
        assertEquals("b.txt", refs[1].fileName)
        assertEquals(9, refs[1].startLine)
        assertEquals(11, refs[1].endLine)
        assertEquals("09-19 10:30:05.000", refs[1].timestamp)
        assertNull(refs[2].fileName, "无标记块回退纯时间戳语义——不串绑前块标记")
        assertEquals("09-19 10:31:00.000", refs[2].timestamp)
    }

    @Test
    fun `坏标记安全降级为纯时间戳引用`() {
        fun singleRefOf(markerLine: String): EvidenceRef =
            parseReport(
                """
                # 日志分析报告
                ## 关键依据
                > $markerLine
                > 09-19 10:29:50.000 E/CameraManager: camera route failed
                """.trimIndent(),
            ).evidenceRefs.single()

        listOf(
            "[证据 a.txt:Labc-2]" to "非数字起",
            "[证据 a.txt:L1-abc]" to "非数字止",
            "[证据 a.txt 行5到行9]" to "未知结构（无冒号行号段）",
            "[证据 :L1-2]" to "空文件名",
            "[证据 a.txt:L9-5]" to "止小于起（parse 层退化判定）",
            "[证据 a.txt:L0-3]" to "零起始（行号 1 基）",
            "[证据 a.txt:L99999999999999-2]" to "行号溢出 Int",
        ).forEach { (marker, why) ->
            val ref = singleRefOf(marker)
            assertNull(ref.fileName, "坏标记降级——$why")
            assertNull(ref.startLine, "坏标记降级——$why")
            assertNull(ref.endLine, "坏标记降级——$why")
            assertEquals("09-19 10:29:50.000", ref.timestamp, "坏标记跳过后块内时间戳照常提取——$why")
        }
    }

    @Test
    fun `validateEvidenceCitation 区间命中且行首时间戳相等为 Verified`() {
        val ref = EvidenceRef(
            timestamp = "09-19 10:29:50.000",
            tag = null,
            snippet = null,
            fileName = "tag.filter-camera.txt",
            startLine = 2,
            endLine = 4,
        )
        val check = validateEvidenceCitation(
            ref = ref,
            evidenceFiles = listOf("/tmp/run1/tag.filter-camera.txt"),
            linesOf = { _, start, end ->
                assertEquals(2, start)
                assertEquals(4, end)
                listOf(
                    "09-19 10:29:49.000 D/T: pre",
                    "09-19 10:29:50.000 E/CameraManager: camera route failed",
                    "  continuation without leading timestamp",
                )
            },
        )
        assertEquals(
            EvidenceCitationCheck.Verified,
            check,
            "硬门禁双过：区间行数足额 + 区间内任一行行首时间戳相等（续行无行首时间戳不参与命中）",
        )
    }

    @Test
    fun `validateEvidenceCitation 区间越出文件尾为 LineRangeOutOfBounds`() {
        val ref = EvidenceRef(
            timestamp = "09-19 10:29:50.000",
            tag = null,
            snippet = null,
            fileName = "a.txt",
            startLine = 8,
            endLine = 10,
        )
        val check = validateEvidenceCitation(
            ref = ref,
            evidenceFiles = listOf("/tmp/a.txt"),
            linesOf = { _, start, _ ->
                // 文件共 9 行：请求 8..10 只返回到文件尾的 2 行（不足请求区间 = 越界信号）
                (start..9).map { "09-19 10:29:5${it - 8}.000 D/T: line$it" }
            },
        )
        assertEquals(
            EvidenceCitationCheck.LineRangeOutOfBounds,
            check,
            "窗口行数不足请求区间 = 越出文件尾（越界判定优先于时间戳判定）",
        )
    }

    @Test
    fun `validateEvidenceCitation 区间合法但行首时间戳不匹配为 TimestampMismatch`() {
        val ref = EvidenceRef(
            timestamp = "09-19 10:29:50.000",
            tag = null,
            snippet = null,
            fileName = "a.txt",
            startLine = 1,
            endLine = 2,
        )
        val check = validateEvidenceCitation(
            ref = ref,
            evidenceFiles = listOf("/tmp/a.txt"),
            linesOf = { _, _, _ ->
                listOf(
                    "09-19 10:29:48.000 D/T: 产物重跑后区间指向别的行",
                    " 09-19 10:29:50.000 D/T: 行首缩进的时间戳不算行首命中",
                )
            },
        )
        assertEquals(
            EvidenceCitationCheck.TimestampMismatch,
            check,
            "区间在行数内但无行首时间戳相等（行首口径——缩进行的时间戳不参与命中）",
        )
    }

    @Test
    fun `validateEvidenceCitation 引用不可解析落地为 FileUnresolved`() {
        val window = { _: String, _: Int, _: Int -> listOf("09-19 10:29:50.000 D/T: x") }
        val ref = EvidenceRef(
            timestamp = "09-19 10:29:50.000",
            tag = null,
            snippet = null,
            fileName = "ghost.txt",
            startLine = 1,
            endLine = 1,
        )
        assertEquals(
            EvidenceCitationCheck.FileUnresolved,
            validateEvidenceCitation(ref, listOf("/tmp/a.txt"), window),
            "文件名不在证据产物集 = 解析域外",
        )
        assertEquals(
            EvidenceCitationCheck.FileUnresolved,
            validateEvidenceCitation(
                ref.copy(fileName = "a.txt"),
                listOf("/tmp/a.txt"),
                { _, _, _ -> error("行窗口读取失败") },
            ),
            "行窗口读取失败 = 不可解析落地",
        )
        assertEquals(
            EvidenceCitationCheck.FileUnresolved,
            validateEvidenceCitation(
                ref.copy(fileName = null, startLine = null, endLine = null),
                listOf("/tmp/a.txt"),
                window,
            ),
            "旧格式引用（无标记三字段全空）无区间可校验 = 不可解析落地",
        )
    }

    @Test
    fun `追问合并报告走同一引用解析路径`() {
        // mergeFollowUp 拼接形态直译（WorkbenchViewModel.mergeFollowUp）：
        // "$previous\n\n---\n\n## 增量研判（追问）\n\n$increment"
        val previous = """
            # 日志分析报告
            ## 结论
            ——主报告结论

            ## 关键依据
            > [证据 tag.filter-camera.txt:L10-12]
            > 09-19 10:29:50.000 E/CameraManager: camera route failed
        """.trimIndent()
        val increment = """
            第 1 次追问的增量结论：回传频率经统计窗口验证为正常。

            ## 关键依据
            > [证据 other.txt:L3]
            > 09-19 10:31:00.000 D/T: 增量证据
        """.trimIndent()
        val merged = "$previous\n\n---\n\n## 增量研判（追问）\n\n$increment"
        val sections = parseReport(merged)
        val ref = sections.evidenceRefs.single()
        assertEquals("tag.filter-camera.txt", ref.fileName, "引用只取主报告关键依据段（唯一解析路径）")
        assertEquals(10, ref.startLine)
        assertEquals(12, ref.endLine)
        assertEquals("09-19 10:29:50.000", ref.timestamp)
        assertEquals(1, sections.increments.size, "增量段原样成卡（mergeFollowUp 追加段无第二套引用口径）")
        assertTrue(
            "第 1 次追问的增量结论" in sections.increments.single(),
            "增量卡 = sectionsAllOf 段界内原文（增量段内嵌标题处截断——既有段界语义不因引用协议改变）",
        )
        assertTrue(
            sections.evidenceRefs.none { it.timestamp == "09-19 10:31:00.000" },
            "增量段引用不二次解析进 evidenceRefs（零二套口径——引用只出自主报告关键依据段）",
        )
    }

    // ── 上下文回读 ──

    @Test
    fun `上下文回读当前文件前后行段`() = kotlinx.coroutines.runBlocking {
        val dir = kotlin.io.path.createTempDirectory("wb-evidence").toFile()
        val full = File(dir, "full.txt")
        full.writeText(
            listOf(
                "09-19 10:29:48.000 D/T: line0",
                "09-19 10:29:49.000 D/T: line1",
                sampleLine,
                "09-19 10:29:51.000 D/T: line3",
                "09-19 10:29:52.000 D/T: line4",
            ).joinToString("\n"),
        )
        val lines = contextLinesOf(
            timestamp = "09-19 10:29:50.000",
            evidenceFiles = listOf(full.absolutePath),
            currentFile = full.absolutePath,
            n = 1,
        )
        assertEquals(3, lines.size)
        assertTrue("10:29:49" in lines.first())
        assertTrue("camera route failed" in lines[1])
        assertTrue("10:29:51" in lines.last())
    }

    @Test
    fun `上下文回读断档时回查更完整产物`() = kotlinx.coroutines.runBlocking {
        val dir = kotlin.io.path.createTempDirectory("wb-evidence").toFile()
        val complete = File(dir, "complete.txt")
        complete.writeText(
            listOf(
                "09-19 10:29:48.000 D/T: pre",
                sampleLine,
                "09-19 10:29:52.000 D/T: post",
            ).joinToString("\n"),
        )
        val filtered = File(dir, "tag.filter-tag_X.txt")
        filtered.writeText(sampleLine)

        // 当前文件是断档的 Tag 过滤产物——回读完整文件取上下文
        val lines = contextLinesOf(
            timestamp = "09-19 10:29:50.000",
            evidenceFiles = listOf(complete.absolutePath, filtered.absolutePath),
            currentFile = filtered.absolutePath,
            n = 1,
        )
        assertEquals(3, lines.size)
        assertTrue("10:29:48" in lines.first())
    }

    @Test
    fun `上下文回读大文件流式定位（尾部命中滑动窗收口正确）`() = kotlinx.coroutines.runBlocking {
        val dir = kotlin.io.path.createTempDirectory("wb-evidence-big").toFile()
        val big = File(dir, "big.log")
        val total = 200_000
        val timestamp = "09-19 10:29:50.000"
        big.bufferedWriter().use { writer ->
            repeat(total) { i ->
                // 命中行置文件末行：before 滑动窗经历全量逐出，after 段触 EOF 提前收口
                val line = if (i == total - 1) {
                    "$timestamp E/Tag: tail-hit"
                } else {
                    "09-19 %02d:%02d:%02d.%03d D/T: fill-$i".format(
                        (i / 3600) % 24, (i / 60) % 60, i % 60, i % 1000,
                    )
                }
                writer.write(line)
                writer.write("\n")
            }
        }

        val lines = contextLinesOf(
            timestamp = timestamp,
            evidenceFiles = listOf(big.absolutePath),
            currentFile = big.absolutePath,
            n = 5,
        )

        // 窗口 = 命中前最近 5 行 + 命中行（无 after 段）——滑动窗逐出中间行、
        // 首尾边界都对（组合期全量 readLines 的旧形态在本规模会整文件载入）
        assertEquals(6, lines.size, "尾部命中窗口 = before 5 行 + 命中行")
        // 命中行索引 = total-1；窗口首行 = 命中前第 5 行 = total-1-5（中间行已逐出）
        assertTrue("fill-${total - 1 - 5}" in lines.first(), "before 段为命中前最近 5 行（窗口首行 = 命中前第 5 行）")
        assertTrue("tail-hit" in lines.last(), "命中行收窗内")
        dir.deleteRecursively()
        // 末表达式须 Unit：runBlocking 透传 deleteRecursively 的 Boolean 会令 @Test 方法非 void（JUnit4 拒载）
        Unit
    }

    @Test
    fun `引用时间戳定位产物文件`() {
        val dir = kotlin.io.path.createTempDirectory("wb-evidence").toFile()
        val a = File(dir, "a.txt"); a.writeText("09-19 10:00:00.000 D/T: x")
        val b = File(dir, "b.txt"); b.writeText("09-19 11:00:00.000 D/T: y")
        assertEquals(b.absolutePath, findEvidenceFileOf("09-19 11:00:00.000", listOf(a.absolutePath, b.absolutePath)))
        assertNull(findEvidenceFileOf("09-19 12:00:00.000", listOf(a.absolutePath, b.absolutePath)))
        assertNotNull(LogLine(seq = 0, raw = "r", timestamp = null, level = null, tag = null, message = null))
    }
}
