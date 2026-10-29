package com.jiaoay.easy_dev_toolbox.page.logworkbench

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.evidence.findTimestampLineIn
import com.jiaoay.testfixtures.UserHomeIsolation
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * [ReportExporter] 确定性单测：固定 run 快照 → 导出 md 内容锚逐项断言
 * （runId / 结论原文 / 证据行原文 / 时间戳格式 / 目录与文件名规则）。
 */
class ReportExporterTest {

    private val originalZone = TimeZone.getDefault()

    @BeforeTest
    fun setUp() {
        // 时间戳格式断言钉 UTC——ZoneId.systemDefault() 随 TimeZone.getDefault 变化
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @AfterTest
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `导出目录在用户可见产物树 reports 子目录`() {
        val home = File("build/tmp/report-exporter-test-home").absoluteFile
        val dir = ReportExporter.exportDir(home)
        assertEquals(
            File(File(File(home, "Documents"), "Toolbelt"), "reports").absolutePath,
            dir.absolutePath,
            "导出目录须与 logcat 产物同根（~/Documents/Toolbelt 可见产物树）下 reports 子目录",
        )
        assertTrue(dir.isDirectory, "exportDir 须负责父目录创建（调用方直接写盘）")
    }

    @Test
    fun `导出文件名含 runId 且同 run 覆写同名`() {
        val dir = File("build/tmp/report-exporter-test-file").absoluteFile
        assertEquals(
            "report-run-export-1.md",
            ReportExporter.exportFileOf("run-export-1", dir).name,
            "文件名 = report-<runId>.md——runId 即 run 唯一键，文件可追溯回 run",
        )
        // 同 runId 二次解析同名 = 原位覆写语义（导出恒反映最新报告文本）
        assertEquals(
            ReportExporter.exportFileOf("run-export-1", dir),
            ReportExporter.exportFileOf("run-export-1", dir),
        )
    }

    @Test
    fun `导出时间行格式为本地时区秒级`() {
        assertEquals(
            "1970-01-01 00:00:00",
            ReportExporter.formatExportTime(0L),
            "纪元零点锚（UTC 钉死）——格式 yyyy-MM-dd HH:mm:ss",
        )
    }

    @Test
    fun `固定 run 快照导出 md 内容锚全命中`() {
        val millis = 1_760_000_000_000L
        val exported = ReportExporter.exportMarkdown(
            run = summaryFixture(),
            reportText = REPORT_FIXTURE,
            evidenceLines = listOf(
                ReportExporter.EvidenceLine(
                    timestamp = "09-19 10:29:50.000",
                    rawLine = LONG_EVIDENCE_LINE,
                ),
                ReportExporter.EvidenceLine(timestamp = "09-19 10:29:52.999", rawLine = null),
            ),
            exportedAtMillis = millis,
        )
        // 元信息锚
        assertTrue("- runId：run-export-1" in exported, "runId 须进元信息（可追溯）")
        assertTrue("- 标题：camera-sample.log @ 09-19 10:29:50.000" in exported, "标题须进元信息")
        assertTrue("- 状态：已完成" in exported, "状态走 RunStatus.label 译名")
        assertTrue("  - /tmp/camera-sample.alog.txt" in exported, "分析文件逐个列出")
        assertTrue("- 时间点：09-19 10:29:50.000（前后 5 分钟）" in exported, "时间点 + 范围一并呈现")
        assertEquals(
            "- 导出时间：${
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(millis))
            }",
            exported.lineSequence().firstOrNull { it.startsWith("- 导出时间：") },
            "导出时间行格式锚（独立计算对照）",
        )
        // 结论/报告全文逐字保真锚
        assertTrue(
            "**根因**：CameraManager 在 XPU 回传前被销毁" in exported,
            "报告全文须原文嵌入（结论/因果链逐字可分享）",
        )
        // 证据行原文逐字保真锚（超摘要截断档长度的整行零截断）
        assertTrue(
            LONG_EVIDENCE_LINE in exported,
            "证据行原文整行逐字嵌入（60 字摘要档是屏显配方，导出不截断）",
        )
        assertTrue(
            "（产物文件中未命中该时间戳）" in exported,
            "未命中引用保留时间戳 + 未命注记（不静默丢弃）",
        )
    }

    @Test
    fun `无时间点 run 的元信息省略时间行`() {
        val exported = ReportExporter.exportMarkdown(
            run = summaryFixture(targetTime = null, rangeMinutes = null),
            reportText = REPORT_FIXTURE,
            evidenceLines = emptyList(),
            exportedAtMillis = 0L,
        )
        assertFalse(
            exported.lineSequence().any { it.startsWith("- 时间点：") },
            "未设时间点的 run 不产空时间行",
        )
        assertTrue(
            "（报告无关键依据时间戳引用）" in exported,
            "零证据引用段的空态注记",
        )
    }

    @Test
    fun `证据行解析按产物完整度序取整行原文`() {
        val linesByPath = mapOf(
            "/tmp/partial-filter.txt" to listOf(
                "09-19 10:29:51.123 D/XpuLink: frame on purpose",
            ),
            "/tmp/full-decoded.txt" to listOf(
                "09-19 10:29:49.000 D/CameraManager: idle",
                LONG_EVIDENCE_LINE,
            ),
        )
        val resolved = ReportExporter.resolveEvidenceLines(
            reportText = REPORT_FIXTURE,
            evidenceFiles = linesByPath.keys.toList(),
            linesOf = { path -> linesByPath[path] ?: error("产物文件不可读：$path") },
        )
        assertEquals(3, resolved.size, "引用数 = 报告关键依据段的时间戳去重集")
        assertEquals(
            LONG_EVIDENCE_LINE,
            resolved.firstOrNull { it.timestamp == "09-19 10:29:50.000" }?.rawLine,
            "命中判定须与 evidenceSnippetOf 同式（TIMESTAMP_PATTERN 提取值比对），取整行零截断",
        )
        assertEquals(
            "09-19 10:29:51.123 D/XpuLink: frame on purpose",
            resolved.firstOrNull { it.timestamp == "09-19 10:29:51.123" }?.rawLine,
            "各时间戳独立按文件序解析（首个含该时间戳的产物命中）",
        )
        assertNull(
            resolved.firstOrNull { it.timestamp == "09-19 10:29:52.999" }?.rawLine,
            "全部产物无命中 = null（渲染面回退未命注记）",
        )
    }

    @Test
    fun `证据行解析对不可读产物文件跳过不炸`() {
        val resolved = ReportExporter.resolveEvidenceLines(
            reportText = REPORT_FIXTURE,
            evidenceFiles = listOf("/tmp/missing-product.txt", "/tmp/full-decoded.txt"),
            linesOf = { path ->
                if (path == "/tmp/full-decoded.txt") listOf(LONG_EVIDENCE_LINE) else error("不可读")
            },
        )
        assertEquals(
            LONG_EVIDENCE_LINE,
            resolved.first { it.timestamp == "09-19 10:29:50.000" }.rawLine,
            "读失败的候选跳过（runCatching 吞读错），候选链继续",
        )
    }

    @Test
    fun `带行区间引用优先按行区间回读并注记条目头`() {
        val resolved = ReportExporter.resolveEvidenceLines(
            reportText = CITED_REPORT_FIXTURE,
            evidenceFiles = citedLinesByPath.keys.toList(),
            linesOf = { path -> citedLinesByPath[path] ?: error("产物文件不可读：$path") },
            linesRangeOf = { path, start, end ->
                citedLinesByPath.getValue(path).drop(start - 1).take(end - start + 1)
            },
        )
        assertEquals(2, resolved.size, "引用数 = 关键依据段时间戳去重集（区间/无标记混排）")
        val cited = resolved.first { it.timestamp == "09-19 10:29:50.000" }
        assertEquals(
            "camera-decoded.txt:L10-12",
            cited.citationNote,
            "区间回读条目注记 = 引用标记的 文件名:L起-止（裸名对产物集精确匹配的解析结果）",
        )
        assertEquals(
            "$CITED_WINDOW_FIRST\n$LONG_EVIDENCE_LINE\n$CITED_WINDOW_LAST",
            cited.rawLine,
            "区间路径优先：原文 = 引用区间 [L10, L12] 闭区间内原文行逐字回读" +
                "（同时间戳在列表序更早产物有扫描命中也不走扫描）",
        )
        val legacy = resolved.first { it.timestamp == "09-19 10:29:51.123" }
        assertNull(legacy.citationNote, "无标记块 = 旧格式引用——恒走时间戳扫描兜底，条目头零注记")
        assertEquals(
            "09-19 10:29:51.123 D/XpuLink: frame on purpose",
            legacy.rawLine,
            "旧格式引用扫描命中判定与既有口径一致（首个含该时间戳的产物整行）",
        )
    }

    @Test
    fun `导出条目头带行区间注记且新格式标记随报告全文逐字保真`() {
        val resolved = ReportExporter.resolveEvidenceLines(
            reportText = CITED_REPORT_FIXTURE,
            evidenceFiles = citedLinesByPath.keys.toList(),
            linesOf = { path -> citedLinesByPath[path] ?: error("产物文件不可读：$path") },
            linesRangeOf = { path, start, end ->
                citedLinesByPath.getValue(path).drop(start - 1).take(end - start + 1)
            },
        )
        val exported = ReportExporter.exportMarkdown(
            run = summaryFixture(),
            reportText = CITED_REPORT_FIXTURE,
            evidenceLines = resolved,
            exportedAtMillis = 0L,
        )
        assertTrue(
            "### 09-19 10:29:50.000（camera-decoded.txt:L10-12）" in exported,
            "区间引用条目头补 文件名:L起-止 注记（回读出处可追溯）",
        )
        assertTrue(
            "### 09-19 10:29:51.123\n" in exported,
            "旧格式引用条目头保持零注记现状",
        )
        assertTrue(
            "[证据 camera-decoded.txt:L10-12]" in exported,
            "报告全文段逐字嵌入——新格式引用标记自动随全文携带（保真段零改动）",
        )
        assertTrue(
            CITED_WINDOW_FIRST in exported && CITED_WINDOW_LAST in exported,
            "区间回读的窗口行逐字入导出（fenced code block 多行原文）",
        )
    }

    @Test
    fun `行区间引用任一环失败回退时间戳扫描兜底`() {
        fun resolveCited(
            marker: String,
            evidenceFiles: List<String>,
            linesRangeOf: ((String, Int, Int) -> List<String>)? = { path, start, end ->
                citedLinesByPath.getValue(path).drop(start - 1).take(end - start + 1)
            },
        ) = ReportExporter.resolveEvidenceLines(
            reportText = markerReport(marker),
            evidenceFiles = evidenceFiles,
            linesOf = { path -> citedLinesByPath[path] ?: error("产物文件不可读：$path") },
            linesRangeOf = linesRangeOf,
        )

        // 名称不在产物集（产物不在盘/名称不匹配）→ 兜底
        val unresolvedName = resolveCited("[证据 ghost.txt:L1-2]", listOf("/tmp/partial-filter.txt"))
        assertEquals(
            "09-19 10:29:50.000 E/CameraManager: camera route failed (partial)",
            unresolvedName.single().rawLine,
            "引用文件名解析失败回退时间戳扫描（产物集内首个含该时间戳的产物）",
        )
        assertNull(unresolvedName.single().citationNote, "兜底路径条目头零注记")
        // 读取抛错 → 兜底
        val unreadable = resolveCited(
            "[证据 camera-decoded.txt:L10-12]",
            listOf("/tmp/camera-decoded.txt"),
            linesRangeOf = { _, _, _ -> error("读失败") },
        )
        assertEquals(
            LONG_EVIDENCE_LINE,
            unreadable.single().rawLine,
            "区间读取抛错吞掉（runCatching）回退时间戳扫描——导出不因引用标记炸裂",
        )
        // 区间越出文件尾（返回行数不足请求区间）→ 兜底
        val outOfTail = resolveCited("[证据 camera-decoded.txt:L11-99]", listOf("/tmp/camera-decoded.txt"))
        assertEquals(LONG_EVIDENCE_LINE, outOfTail.single().rawLine, "窗口不完整 = 越出文件尾，回退扫描")
        assertNull(outOfTail.single().citationNote, "兜底路径条目头零注记")
        // 行段缝未注入（不传 linesRangeOf 的既有调用面）→ 恒走兜底零回归
        val noSeam = resolveCited("[证据 camera-decoded.txt:L10-12]", listOf("/tmp/partial-filter.txt"), linesRangeOf = null)
        assertEquals(
            "09-19 10:29:50.000 E/CameraManager: camera route failed (partial)",
            noSeam.single().rawLine,
            "行段缝未注入 = 区间路径不启用，行为与缝引入前完全一致",
        )
    }

    private fun summaryFixture(
        targetTime: String? = "09-19 10:29:50.000",
        rangeMinutes: Long? = 5L,
    ) = RunStore.RunSummary(
        id = "run-export-1",
        title = "camera-sample.log @ 09-19 10:29:50.000",
        logFiles = listOf("/tmp/camera-sample.alog.txt"),
        targetTime = targetTime,
        rangeMinutes = rangeMinutes,
        status = RunStore.RunStatus.COMPLETED,
        reportPath = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private companion object {
        /** 超过屏显摘要截断档（60 字）的整行证据原文——零截断断言的判据样本 */
        private const val LONG_EVIDENCE_LINE =
            "09-19 10:29:50.000 E/CameraManager: camera route failed with retry exhausted " +
                "and surface destroyed during binding sequence"

        private val REPORT_FIXTURE = """
            # 日志分析报告

            ## 结论

            ——相机链路路由失败，根因在 CameraManager 初始化时序。

            ## 问题根因

            **根因**：CameraManager 在 XPU 回传前被销毁
            **直接故障点**：CameraManager.bind() 返回 -1
            **影响面**：相机预览黑屏
            - ✓ XPU 回传频率正常
            - ? 系统内存压力参与
            - ✗ 相机硬件故障

            ## 关键依据

            - 09-19 10:29:50.000 路由失败
            - 09-19 10:29:51.123 XPU 帧回传
            - 09-19 10:29:52.999 内存压力

            ## 完整时间线

            | 时间 | 事件 |
            |---|---|
            | 09-19 10:29:50.000 | 路由失败 |
        """.trimIndent()

        /** 区间回读窗口首行（引用区间 [L10, L12] 的 L10） */
        private const val CITED_WINDOW_FIRST = "09-19 10:29:49.900 D/CameraManager: entering bind sequence"

        /** 区间回读窗口尾行（引用区间 [L10, L12] 的 L12） */
        private const val CITED_WINDOW_LAST = "09-19 10:29:50.100 D/XpuLink: route settled"

        /**
         * 新格式报告夹具：标记块（区间引用）+ 无标记块（旧格式引用）混排——
         * 同一报告两条路径并存的行为判据。
         */
        private val CITED_REPORT_FIXTURE = """
            # 日志分析报告

            ## 结论

            ——相机链路路由失败，根因在 CameraManager 初始化时序。

            ## 关键依据

            - 09-19 10:29:50.000 E/CameraManager: 路由失败 [证据 camera-decoded.txt:L10-12]

            - 09-19 10:29:51.123 D/XpuLink: XPU 帧回传
        """.trimIndent()

        /**
         * 区间回读域产物集：列表序在前的 partial 产物**也含**同时间戳扫描命中——
         * 区间路径须无视它（按引用标记的文件名解析，不走列表序扫描）。
         */
        private val citedLinesByPath = mapOf(
            "/tmp/partial-filter.txt" to listOf(
                "09-19 10:29:50.000 E/CameraManager: camera route failed (partial)",
                "09-19 10:29:51.123 D/XpuLink: frame on purpose",
            ),
            "/tmp/camera-decoded.txt" to List(9) { "填充行 ${it + 1}" } + listOf(
                CITED_WINDOW_FIRST, // L10
                LONG_EVIDENCE_LINE, // L11
                CITED_WINDOW_LAST, // L12
            ),
        )

        /** 单时间戳 + 引用标记的极小报告夹具（标记紧跟证据行首——解析同块绑定口径） */
        private fun markerReport(marker: String): String = """
            # 日志分析报告

            ## 关键依据

            - 09-19 10:29:50.000 E/CameraManager: 路由失败 $marker
        """.trimIndent()
    }
}

/**
 * [WorkbenchViewModel.exportCurrentReport] 动作测试（WorkbenchSwitchConfirmTest
 * 同款 VM 直测纪律）：
 * - Main dispatcher 替换 + user.home 隔离（VM 构造触 Database.database() 真单例）；
 * - 导出落点断言在隔离 home 下的可见产物目录——不触真实用户数据。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchReportExportActionTest {

    private val dispatcher = StandardTestDispatcher()

    /** user.home 隔离：VM 构造触 Database.database() 真单例——数据根须落临时目录 */
    private val home = UserHomeIsolation("edt-wb-report-export-test")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
    }

    // IO 调度器注入缝：VM 全部 IO 段与 Main 共享同一 TestScheduler——init/收尾
    // 协程不飞真实 IO 线程，用例收尾 advanceUntilIdle 即确定性排空（无跨用例续体）。
    private val createdVms = mutableListOf<WorkbenchViewModel>()

    private fun vmTracked(): WorkbenchViewModel =
        WorkbenchViewModel(ioDispatcher = dispatcher).also { createdVms += it }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        home.restoreOnly() // 只还原不删（DataStore 在途写 flaky 先例——目录留 /tmp）
        Database.resetForTest() // 进程单例钉在隔离 home 上——不重置会跨测试类污染
    }

    /** 轮询推进断言兜底：注入后 advanceUntilIdle 已确定性收口，循环首拍即中，防御性保留 */
    private fun awaitTrue(probe: () -> Boolean): Boolean {
        repeat(100) {
            if (probe()) return true
            Thread.sleep(20)
            dispatcher.scheduler.advanceUntilIdle()
        }
        return probe()
    }

    @Test
    fun `回看 run 后导出落盘可见产物目录并回显路径`() {
        val db = Database.database()
        val store = RunStore(db)
        val reportFile = File(home.home, "sample-report.md").apply {
            writeText("## 结论\n\n——路由失败样本结论。\n")
        }
        store.create(
            id = "run-export-vm-1",
            title = "camera-sample.log @ 09-19 10:29:50.000",
            logFiles = listOf("/tmp/camera-sample.alog.txt"),
            targetTime = "09-19 10:29:50.000",
            rangeMinutes = 5L,
            status = RunStore.RunStatus.COMPLETED,
        )
        store.updateReportPath("run-export-vm-1", reportFile.absolutePath)

        val vm = vmTracked()
        vm.openRun("run-export-vm-1")
        assertTrue(
            awaitTrue { vm.reportText.value != null },
            "回看须先回填报告文本（导出的数据源）",
        )

        vm.exportCurrentReport()
        assertTrue(
            awaitTrue { vm.exportedReportPath.value != null },
            "导出动作须落盘并回显产物路径",
        )

        val exportedPath = vm.exportedReportPath.value!!
        assertEquals(
            File(File(File(File(home.home, "Documents"), "Toolbelt"), "reports"), "report-run-export-vm-1.md").absolutePath,
            exportedPath,
            "导出落点 = 隔离 home 下可见产物目录，文件名含 runId 可追溯",
        )
        val exported = File(exportedPath)
        assertTrue(exported.isFile, "导出文件须真实落盘")
        val content = exported.readText()
        assertTrue("run-export-vm-1" in content, "导出内容含 runId（元信息段）")
        assertTrue("路由失败样本结论" in content, "报告全文逐字嵌入（结论可分享）")
    }

    @Test
    fun `切回看另一 run 清空上一 run 的导出产物行`() {
        val db = Database.database()
        val store = RunStore(db)
        val reportA = File(home.home, "report-a.md").apply { writeText("## 结论\n\n——A 的结论。\n") }
        val reportB = File(home.home, "report-b.md").apply { writeText("## 结论\n\n——B 的结论。\n") }
        for (run in listOf("run-swap-a" to reportA, "run-swap-b" to reportB)) {
            store.create(
                id = run.first,
                title = "${run.first} @ 09-19 10:29:50.000",
                logFiles = listOf("/tmp/camera-sample.alog.txt"),
                targetTime = "09-19 10:29:50.000",
                rangeMinutes = 5L,
                status = RunStore.RunStatus.COMPLETED,
            )
            store.updateReportPath(run.first, run.second.absolutePath)
        }

        val vm = vmTracked()
        vm.openRun("run-swap-a")
        assertTrue(awaitTrue { vm.reportText.value != null }, "前置：A 报告回填")
        vm.exportCurrentReport()
        assertTrue(awaitTrue { vm.exportedReportPath.value != null }, "前置：A 导出产物行回显")

        vm.openRun("run-swap-b")
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(
            vm.exportedReportPath.value,
            "切 run 须清空上一 run 的导出产物行——A 的路径顶着 B 的报告可被复制/打开动作错拿",
        )
        assertTrue(
            "B 的结论" in (vm.reportText.value ?: ""),
            "切 run 后报告文本应为新 run 内容（清空动作不得误伤回填）",
        )
    }

    @Test
    fun `占位会话导出静默返回零落盘`() {
        val vm = vmTracked()
        vm.exportCurrentReport()
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(
            vm.exportedReportPath.value,
            "无 run/无报告的守卫静默返回——不落盘不回显（可感知拒绝在面板钮置灰面）",
        )
        assertFalse(
            ReportExporter.exportFileOf(WorkbenchViewModel.PENDING_RUN_ID, ReportExporter.exportDir(home.home)).isFile,
            "守卫路径不得产出任何文件",
        )
    }

    // ── 导出脱敏开关行为（开关态经 VM 外发单缝——送飞书共用同一写盘点） ──

    /** 开关行为测试的夹具 run：报告含 VIN 原值 + 分析文件含用户名路径 */
    private fun fixtureRunWithPii(reportText: String): String {
        val db = Database.database()
        val store = RunStore(db)
        val reportFile = File(home.home, "pii-report.md").apply { writeText(reportText) }
        store.create(
            id = "run-export-pii-1",
            title = "pii-sample.log @ 09-19 10:29:50.000",
            logFiles = listOf("/home/alice/pii-sample.alog.txt"),
            targetTime = "09-19 10:29:50.000",
            rangeMinutes = 5L,
            status = RunStore.RunStatus.COMPLETED,
        )
        store.updateReportPath("run-export-pii-1", reportFile.absolutePath)
        return "run-export-pii-1"
    }

    @Test
    fun `开关开导出产物含替值且原值零残留`() {
        fixtureRunWithPii("## 结论\n\n——VIN LHGARV8897K123456 复现样本结论。\n")
        val vm = vmTracked()
        vm.openRun("run-export-pii-1")
        assertTrue(awaitTrue { vm.reportText.value != null }, "前置：报告回填")
        vm.setAnonymizeExport(true)
        vm.exportCurrentReport()
        assertTrue(awaitTrue { vm.exportedReportPath.value != null }, "导出须落盘回显")

        val content = File(vm.exportedReportPath.value!!).readText()
        assertTrue("VIN_1" in content, "报告全文段替值须在产物中（同值恒同替）")
        assertTrue(
            "  - /home/USER_1/pii-sample.alog.txt" in content,
            "元信息分析文件路径的户名段须替换",
        )
        assertFalse("LHGARV8897K123456" in content, "VIN 原值零残留")
        assertFalse("alice" in content, "户名原值零残留")
    }

    @Test
    fun `开关关导出产物逐字现状`() {
        fixtureRunWithPii("## 结论\n\n——VIN LHGARV8897K123456 复现样本结论。\n")
        val vm = vmTracked()
        vm.openRun("run-export-pii-1")
        assertTrue(awaitTrue { vm.reportText.value != null }, "前置：报告回填")
        vm.exportCurrentReport()
        assertTrue(awaitTrue { vm.exportedReportPath.value != null }, "导出须落盘回显")

        val content = File(vm.exportedReportPath.value!!).readText()
        assertTrue("LHGARV8897K123456" in content, "开关关（默认）报告全文逐字保真现状")
        assertTrue(
            "  - /home/alice/pii-sample.alog.txt" in content,
            "开关关分析文件路径逐字现状",
        )
        assertFalse("VIN_1" in content, "开关关不得出现替值")
        assertFalse("USER_" in content, "开关关不得出现户名替值")
    }

    // ── 旧格式引用（无行区间）的流式回读 ──

    /**
     * 注入 [ReportExporter.resolveEvidenceLines.lineFinderOf] 后，旧格式引用必须**不经**
     * 全量读路径。用「全量 read 会被调用即爆炸」的探针证明：大日志导出只靠流式定位。
     */
    @Test
    fun `旧格式引用走流式定位而非全量读`() {
        val evidenceFile = File.createTempFile("evidence-stream", ".txt")
        try {
            evidenceFile.writeText(
                buildString {
                    repeat(5_000) { appendLine("10-23 12:00:0${it % 10}.000  1000  2000 I Tag: filler $it") }
                    appendLine("10-23 12:00:09.123  1000  2000 I Tag: target line")
                },
            )
            val report = """
                ## 结论

                ——结论。

                ## 关键依据

                - 2026-10-23 12:00:09.123 目标行
            """.trimIndent()

            var fullReadInvoked = false
            val lines = ReportExporter.resolveEvidenceLines(
                reportText = report,
                evidenceFiles = listOf(evidenceFile.absolutePath),
                linesOf = { fullReadInvoked = true; emptyList() },
                lineFinderOf = { path, ts -> findTimestampLineIn(File(path), ts) },
            )

            assertFalse(fullReadInvoked, "注入 lineFinderOf 后不得回退全量读路径")
            assertTrue(
                lines.any { it.rawLine?.contains("target line") == true },
                "流式定位须命中目标行：${lines.map { it.rawLine }}",
            )
        } finally {
            evidenceFile.delete()
        }
    }

    /** 未注入 lineFinderOf 时，旧调用方行为零变化（仍走 linesOf）。 */
    @Test
    fun `未注入 lineFinderOf 时回退既有全量读路径`() {
        val report = """
            ## 结论

            ——结论。

            ## 关键依据

            - 2026-10-23 12:00:09.123 目标行
        """.trimIndent()

        var fullReadInvoked = false
        val lines = ReportExporter.resolveEvidenceLines(
            reportText = report,
            evidenceFiles = listOf("/tmp/irrelevant.txt"),
            linesOf = { fullReadInvoked = true; emptyList() },
        )

        assertTrue(fullReadInvoked, "未注入时须保持既有全量读路径（旧调用方零影响）")
        assertTrue(lines.isNotEmpty(), "引用条目本身须保留")
    }
}
