package com.jiaoay.easy_dev_toolbox.page.logworkbench

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.KnowledgeRepository
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.evidence.parseReport
import com.jiaoay.testfixtures.UserHomeIsolation
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 根因存案写路径测试（fake run 快照 + 临时证据产物文件）：
 * - 四要素预填：报告解析（结论→症状/标题、因果链→根因）+ 证据行原文回读
 *   （[ReportExporter.resolveEvidenceLines] 区间回读）+ tags 证据 TAG 高频词；
 * - 读写闭环：存案后 searchGoldenSamples（[KnowledgeRepository.CASE_REPO] 专用案例仓）
 *   即命中——写面与检索面同四要素；
 * - 同名 (repo, title) 覆盖语义：二次存案不增行、内容更新、回执区分覆盖；
 * - tags 编码单源：List<String> 经 repository encodeList/decodeList 单源往返保真，
 *   VM/面板源码零第二套编解码（源码切片锚）；
 * - 接线守护：报告面板「存入案例库」钮 + 页面对话框装配点（ReportLarkSendTest 同族配方）。
 *
 * 真机对话框手感/视觉终裁不在本测试面（用户门禁项）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchCaseArchiveTest {

    private val dispatcher = StandardTestDispatcher()

    /** user.home 隔离：VM 构造与存案写路径触 Database.database() 真单例——数据根须落临时目录 */
    private val home = UserHomeIsolation("edt-wb-case-archive-test")

    private val createdVms = mutableListOf<WorkbenchViewModel>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
    }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        home.restoreOnly() // 只还原不删（DataStore 在途写 flaky 先例——目录留 /tmp）
        Database.resetForTest() // 进程单例钉在隔离 home 上——不重置会跨测试类污染
    }

    private fun vmTracked(): WorkbenchViewModel =
        WorkbenchViewModel(ioDispatcher = dispatcher).also { createdVms += it }

    /** 轮询推进断言兜底（WorkbenchReportExportActionTest 同款配方） */
    private fun awaitTrue(probe: () -> Boolean): Boolean {
        repeat(100) {
            if (probe()) return true
            Thread.sleep(20)
            dispatcher.scheduler.advanceUntilIdle()
        }
        return probe()
    }

    // ── 结构化报告夹具（parseReport 对偶端——结论/因果链/带引用标记的关键依据） ──

    private val reportText = """
        # 日志分析报告

        ## 结论
        ——内部异常：拍照模式进入后相机预览链路未建立，预览画面黑屏

        ## 问题根因
        **根因**：CameraViewActivity 未收到拍照模式切换事件
        **直接故障点**：CameraManager 相机路由未注册
        **影响面**：预览画面全屏黑屏

        ## 关键依据

        - 09-19 10:29:50.000 E/CameraManager: route failed [证据 camera-decoded.txt:L10-12]

        ## 元数据
        - 输入文件：camera-sample.alog
    """.trimIndent()

    /** 证据产物夹具：L10-L12 闭区间为引用标记的行窗（1 基） */
    private fun evidenceFile(): File =
        File(home.home, "camera-decoded.txt").apply {
            writeText(
                (
                    List(9) { "填充行 ${it + 1}" } + listOf(
                        "09-19 10:29:50.000 E/CameraManager: camera route failed",
                        "09-19 10:29:50.123 E/CameraManager: retry route failed",
                        "09-19 10:29:51.001 D/XpuLink: frame on purpose",
                    )
                    ).joinToString("\n") + "\n",
            )
        }

    // ── 四要素预填（纯函数面：报告解析 + 证据行回读） ──

    @Test
    fun `四要素预填来自报告解析与证据行回读`() {
        val evidence = evidenceFile()
        val evidenceLines = ReportExporter.resolveEvidenceLines(
            reportText = reportText,
            evidenceFiles = listOf(evidence.absolutePath),
            linesOf = { path -> File(path).readLines() },
            linesRangeOf = { path, start, end ->
                File(path).useLines { lines ->
                    lines.drop(start - 1).take(end - start + 1).toList()
                }
            },
        )
        val draft = WorkbenchViewModel.buildCaseDraft(
            sections = parseReport(reportText),
            evidenceLines = evidenceLines,
            fallbackTitle = "camera-sample.alog @ 09-19 10:29:50.000",
        )
        assertEquals("内部异常：拍照模式进入后相机预览链路未建立，预览画面黑屏", draft.symptom, "症状 ← 结论一句话")
        assertEquals(draft.symptom, draft.title, "标题 ← 结论（一句话症状即案例名语义）")
        assertEquals("CameraViewActivity 未收到拍照模式切换事件", draft.rootCause, "根因 ← 因果链「根因」段")
        assertEquals("", draft.fixSummary, "修法恒空预填（报告纪律不产修法——存案时补写）")
        assertTrue("camera route failed" in draft.evidenceLog, "证据日志 ← 引用区间原文逐字回读")
        assertTrue("CameraManager" in draft.tags, "tags ← 证据行 TAG 高频词（CameraManager 两频次居首）")
        assertTrue("XpuLink" in draft.tags, "tags 次高频 TAG 同样入围")
    }

    @Test
    fun `结论缺失时标题回落 run 标题-证据零命中产零 tags`() {
        val bareReport = "# 日志分析报告\n\n## 问题根因\n**根因**：X\n"
        val draft = WorkbenchViewModel.buildCaseDraft(
            sections = parseReport(bareReport),
            evidenceLines = ReportExporter.resolveEvidenceLines(
                reportText = bareReport,
                evidenceFiles = emptyList(),
                linesOf = { path -> File(path).readLines() },
                linesRangeOf = null,
            ),
            fallbackTitle = "fallback-run-title",
        )
        assertEquals("fallback-run-title", draft.title, "结论空 ⇒ 标题回落 run 标题")
        assertEquals("", draft.symptom, "结论空 ⇒ 症状空（必填项由对话框补写）")
        assertTrue(draft.tags.isEmpty(), "零证据行 ⇒ 零 tags 候选")
    }

    @Test
    fun `TAG 高频词按频次降序取上限五件`() {
        val lines = listOf(
            "09-19 10:29:50.000 E/CameraManager: a",
            "09-19 10:29:50.100 E/CameraManager: b",
            "09-19 10:29:50.200 E/CameraManager: c",
            "09-19 10:29:50.300 D/XpuLink: d",
            "09-19 10:29:50.400 D/XpuLink: e",
            "09-19 10:29:50.500 W/FloorCenterManager: f",
            "09-19 10:29:50.600 I/OtherTag: g",
            "09-19 10:29:50.700 I/ThirdTag: h",
            "纯文本行无 TAG 前缀",
        )
        val tags = WorkbenchViewModel.evidenceTagCandidates(lines)
        assertEquals(listOf("CameraManager", "XpuLink", "FloorCenterManager", "OtherTag", "ThirdTag"), tags)
        assertEquals(5, tags.size, "上限 5 件（频次降序；纯文本行零候选）")
    }

    // ── VM 存案动作面（fake run 快照 → 对话框态 → 落库 → 读写闭环/覆盖语义） ──

    private fun seededRun(): String {
        val db = Database.database()
        val store = RunStore(db)
        val reportFile = File(home.home, "sample-report.md").apply { writeText(reportText) }
        store.create(
            id = "run-case-archive-1",
            title = "camera-sample.alog @ 09-19 10:29:50.000",
            logFiles = listOf("/tmp/camera-sample.alog.txt"),
            targetTime = "09-19 10:29:50.000",
            rangeMinutes = 5L,
            status = RunStore.RunStatus.COMPLETED,
        )
        store.updateReportPath("run-case-archive-1", reportFile.absolutePath)
        return "run-case-archive-1"
    }

    @Test
    fun `回看 run 后开存案-四要素进对话框态`() {
        val runId = seededRun()
        val vm = vmTracked()
        vm.openRun(runId)
        assertTrue(awaitTrue { vm.reportText.value != null }, "回看须先回填报告文本（存案预填的数据源）")

        vm.openCaseDraft()
        assertTrue(awaitTrue { vm.caseDraft.value != null }, "开存案须产出对话框预填载荷")
        val draft = vm.caseDraft.value!!
        assertEquals("内部异常：拍照模式进入后相机预览链路未建立，预览画面黑屏", draft.symptom)
        assertEquals("CameraViewActivity 未收到拍照模式切换事件", draft.rootCause)
        assertEquals("", draft.fixSummary)
    }

    @Test
    fun `占位会话开存案静默返回-无对话框态`() {
        val vm = vmTracked()
        vm.openCaseDraft()
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.caseDraft.value, "无 run/无报告守卫静默返回（可感知拒绝在面板钮置灰面）")
    }

    @Test
    fun `存案落库后检索即命中-重复存案覆盖不增行`() {
        val runId = seededRun()
        val vm = vmTracked()
        vm.openRun(runId)
        assertTrue(awaitTrue { vm.reportText.value != null })
        vm.openCaseDraft()
        assertTrue(awaitTrue { vm.caseDraft.value != null })
        val draft = vm.caseDraft.value!!.copy(
            fixSummary = "补写修法：注册 CameraManager 相机路由",
            tags = listOf("CameraManager", "拍照模式"),
        )

        // 读写闭环：存案后 searchGoldenSamples 即命中（写面 = 检索面的同一四要素）
        vm.saveCaseArchive(draft)
        assertTrue(awaitTrue { vm.caseDraft.value == null }, "存案成功后对话框态收口")

        val repository = KnowledgeRepository(Database.database())
        val hit = repository.searchGoldenSamples(KnowledgeRepository.CASE_REPO, "相机预览").single()
        assertEquals(draft.symptom, hit.symptom)
        assertEquals(draft.rootCause, hit.rootCause)
        assertEquals(draft.fixSummary, hit.fixSummary, "修法为用户补写值（非空预填契约）")
        assertEquals(listOf("CameraManager", "拍照模式"), hit.tags, "tags 经 encodeList/decodeList 单源往返保真")

        // 覆盖语义：同 (repo, title) 二次存案不增行、内容更新
        vm.openCaseDraft()
        assertTrue(awaitTrue { vm.caseDraft.value != null })
        vm.saveCaseArchive(vm.caseDraft.value!!.copy(fixSummary = "修法勘误后的版本"))
        assertTrue(awaitTrue {
            KnowledgeRepository(Database.database())
                .goldenSamplesOf(KnowledgeRepository.CASE_REPO)
                .singleOrNull()?.fixSummary == "修法勘误后的版本"
        }, "覆盖写最新内容")
        val cases = KnowledgeRepository(Database.database()).goldenSamplesOf(KnowledgeRepository.CASE_REPO)
        assertEquals(1, cases.size, "重复存案覆盖不增行（UNIQUE(repo, title) 单行收敛）")
    }

    @Test
    fun `标题或症状为空时保存被拒绝-库零落笔`() {
        val vm = vmTracked()
        vm.saveCaseArchive(
            WorkbenchViewModel.CaseDraft("", "", "", "", "", emptyList()),
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            0L,
            KnowledgeRepository(Database.database()).stats().samples,
            "必填项缺失 ⇒ 静默拒绝不落库（golden_samples NOT NULL 契约前置）",
        )
    }

    // ── 接线守护（源码切片锚——ReportLarkSendTest 同族配方） ──

    @Test
    fun `报告面板存案钮带置灰判据与页面装配点接线`() {
        val panel = source("feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchReportPanel.kt")
        val page = source("feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt")

        assertTrue("label = \"存入案例库\"" in panel, "报告面板丢失「存入案例库」按钮（动作区第三出口）")
        assertTrue(
            "disabledReason = caseArchiveDisabledReason(running = running)" in panel,
            "存案钮必须消费置灰判据纯函数（禁静默置灰）",
        )
        assertTrue("onArchiveCase = vm::openCaseDraft" in page, "页面丢失存案动作接线（onArchiveCase = vm::openCaseDraft）")
        assertTrue("CaseArchiveDialog(" in page, "页面丢失存案编辑对话框装配点")
        assertTrue("onSave = vm::saveCaseArchive" in page, "页面丢失存案保存接线（onSave = vm::saveCaseArchive）")
    }

    @Test
    fun `tags 编码单源-工作台域零第二套编解码`() {
        val vmSource = source("feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchViewModel.kt")
        val panel = source("feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchReportPanel.kt")
        listOf(vmSource, panel).forEach { src ->
            assertTrue("encodeToString" !in src, "工作台域禁止自带 JSON 数组编码（tags 经 repository encodeList 单源）")
            assertTrue("ListSerializer" !in src, "工作台域禁止第二套列表序列化器（防第二套编解码）")
        }
        // 写路径经公开 API 单源：upsertGoldenSample 入参是 List<String>（非编码串）
        assertTrue("upsertGoldenSample(" in vmSource, "存案写路径必须走 KnowledgeRepository 公开 API")
        assertTrue("CASE_REPO" in vmSource, "存案仓名必须引用 KnowledgeRepository.CASE_REPO 常量（不自持字面）")
    }

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, path)
        assertTrue(file?.isFile == true, "源码锚文件缺失：$path")
        return file.readText()
    }
}
