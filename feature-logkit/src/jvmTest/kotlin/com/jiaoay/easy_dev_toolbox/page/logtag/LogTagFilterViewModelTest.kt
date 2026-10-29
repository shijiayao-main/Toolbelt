package com.jiaoay.easy_dev_toolbox.page.logtag

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.page.logkit.LogKitConfigStore
import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import com.jiaoay.testfixtures.UserHomeIsolation
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TL-04 · Tag 正则拖入前预校验（重设计批 1）：
 *
 * 修复前 [LogTagFilterViewModel.validate] 只查 regex/name 非空——非法正则到
 * process 阶段才由 log-tools 的 Pattern.compile 抛出，任务卡只给
 * 「执行失败, message: …」无修正指引；输入框也无即时语法反馈。
 *
 * 验收断言（经公有入口 addFiles 端到端，不放宽 validate 可见性）：
 * - 非法正则拖入被 validate 拦截：整批拒绝（零任务卡）+ inputError 文案四要素
 *   （问题定位 / 原因片段 / 修正指引「重新拖入」/ 内容保留承诺）；
 * - 合法正则正常放行建卡（既有行为不回归）；
 * - [LogTagFilterViewModel.regexValid] 输入即时反馈三态（非法 false / 合法 true /
 *   空串 true——空视为未填写不置错，同 validate 空值语义）。
 *
 * 纪律（AiSettingsViewModelTest 同款）：断言全程同步——init 的 DataStore 读 launch
 * 在 StandardTestDispatcher 下恒 pending 不打扰；DataStore IO 走注入 Unconfined
 * datastoreScope 同步落定，tearDown 先排空在途协程再 resetMain（零真实 sleep）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogTagFilterViewModelTest {

    /** user.home 隔离（init 读 LogKitConfigStore → AppDataStore 经 user.home 定 DataStore 根） */
    private val homeIsolation = UserHomeIsolation("edt-logtag-test")

    private val dispatcher = StandardTestDispatcher()

    /**
     * DataStore IO 受控 scope（LocalDataManager 的 datastoreScope 注入缝）：Unconfined 让
     * DataStore 文件 IO 在调用线程同步落定——drain 即全静默，无真实 IO 线程续体与
     * resetMain 的竞态。🔴 不得改与 Main 共享 StandardTestDispatcher 的 scope：
     * DataStore 挂起读写经 withContext 跳到注入 scope，本类种子用例经调度器推进
     * 落定；共享 TestScheduler 的 scope 在 no-sleep 直读语境会因调度器无人推进而死锁。
     */
    private val datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var originalStore: LocalDataManager

    /** 本地时区暂存：setUp 钉 UTC、tearDown 还原（理由见 setUp 注释） */
    private val originalZone = java.util.TimeZone.getDefault()

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        originalStore = LogKitConfigStore.store
        // redirect 窗口内 eager 解析并钉死目录（LocalDataManager 构造参数契约），
        // 还原 user.home 后的迟到首操作不得落错目录
        LogKitConfigStore.store = LocalDataManager(
            dataStoreName = "test_logtag_${java.util.UUID.randomUUID()}",
            datastoreDir = FileUtils.getDatastoreDir(),
            datastoreScope = datastoreScope,
        )
        Dispatchers.setMain(dispatcher)
        // ReportDelivery 是全局单例槽——本类的送飞书用例 wired fake channel
        // 残留会跨用例泄漏到其他 VM 测试（clearForTest 前置清场 + 后置复位）
        ReportDelivery.clearForTest()
        // 钉 UTC：本类送出用例经 send 路径触发 StatsMarkdownExporter 类初始化，
        // 其时间格式器在类初始化时捕获一次系统时区——若先于 StatsMarkdownExporterTest
        //（其内容锚钉 UTC）加载，+8 等本地时区会被永久冻结进格式器致其红；
        // 与该测试同款钉 UTC 后，加载次序不再影响两侧断言
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    }

    @AfterTest
    fun tearDown() {
        // DataStore IO 已随 Unconfined datastoreScope 同步落定——排空即全静默，
        // resetMain 前无在途续体竞态；restoreOnly 不删临时目录（注入 store 的
        // 落盘路径钉在 redirect 窗口内的数据目录——目录留盘即 DataStore 实例安全）
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        LogKitConfigStore.store = originalStore
        Dispatchers.resetMain()
        ReportDelivery.clearForTest()
        java.util.TimeZone.setDefault(originalZone)
        homeIsolation.restoreOnly()
    }

    // 构造登记：tearDown 统一 cancel（批 5 收口——VM init 的 DataStore 恢复协程在
    // 真实 IO 上飞，用例结束不 cancel 则在途续体跨用例回 Main，撞下一用例 setMain
    // 自检「Dispatchers.Main is used concurrently with setting it」低频窗口竞态）。
    private val createdVms = mutableListOf<LogTagFilterViewModel>()

    private fun vm(): LogTagFilterViewModel =
        LogTagFilterViewModel(ioDispatcher = dispatcher).also { createdVms += it }

    private fun tempFile(): java.io.File =
        Files.createTempFile("edt-logtag", ".txt").toFile()

    // ── 正则命中预演（regexPreview：锚点文件 + debounce 扫描）────────

    /** 3 块 fixture：块 2/3 续行含 MyTag，块 1 不含 */
    private fun previewFixtureFile(): File {
        val content = buildString {
            appendLine("10-11 12:00:01.001 E/plain: block-1 head")
            appendLine("continuation block-1 miss")
            appendLine("10-11 12:00:02.001 E/plain: block-2 head")
            appendLine("continuation block-2 MyTag hit")
            appendLine("10-11 12:00:03.001 E/plain: block-3 head")
            appendLine("continuation block-3 MyTag hit")
        }
        return Files.createTempDirectory("edt-logtag-preview").toFile()
            .let { File(it, "preview-fixture.txt").apply { writeText(content) } }
    }

    /**
     * drain init 恢复协程后再交互：init 的 DataStore 读在真实 IO 上飞，
     * 不 drain 则恢复值（上一用例 tearDown drain 落盘的持久化正则）会在
     * advanceUntilIdle 时覆盖本用例已输入的正则——预演断言随之漂移。
     */
    private fun drainInit() {
        repeat(10) { dispatcher.scheduler.advanceUntilIdle(); Thread.sleep(20) }
    }

    /** 轮询等待预演完成（VM 级扫描与 Main 同 TestScheduler——首拍 advanceUntilIdle 即排空；轮询环保留作推进兜底） */
    private fun awaitPreview(
        viewModel: LogTagFilterViewModel,
        predicate: (TagRegexPreviewState?) -> Boolean,
    ) {
        repeat(100) {
            dispatcher.scheduler.advanceUntilIdle()
            if (predicate(viewModel.regexPreview.value)) return
            Thread.sleep(20)
        }
    }

    /** 轮询等待统计完成（全文件扫描同 awaitPreview 口径） */
    private fun awaitStats(
        viewModel: LogTagFilterViewModel,
        predicate: (LogTagStatsState) -> Boolean,
    ) {
        repeat(100) {
            dispatcher.scheduler.advanceUntilIdle()
            if (predicate(viewModel.statsState.value)) return
            Thread.sleep(20)
        }
    }

    // ── 分布统计（statsState：requestStats 手动触发）────────

    /**
     * 6 块 fixture：块 1 = 裸文本头（Tag/级别未识别面）；块 2/4 = E/Alpha；
     * 块 3 = W/Beta(1234)（brief 带 pid）；块 5 = I/Gamma；块 6 = D/Backtrack
     * （时间最早但出现最晚——钉死 first 按 min 取值）。
     */
    private fun statsFixtureFile(): File {
        val content = buildString {
            appendLine("无时间戳与级别的裸文本头")
            appendLine("2026-10-11 08:00:00.000 E/Alpha: block-2 head")
            appendLine("continuation block-2")
            appendLine("2026-10-11 08:00:01.000 W/Beta(1234): block-3 head")
            appendLine("2026-10-11 08:00:02.000 E/Alpha: block-4 head")
            appendLine("2026-10-11 08:00:05.000 I/Gamma: block-5 head")
            appendLine("2026-10-11 07:59:00.000 D/Backtrack: block-6 head")
        }
        return Files.createTempDirectory("edt-logtag-stats").toFile()
            .let { File(it, "stats-fixture.txt").apply { writeText(content) } }
    }

    @Test
    fun `requestStats computes tag level and span overview on anchor file`() {
        val viewModel = vm()
        drainInit()
        val fixture = statsFixtureFile()
        viewModel.addFiles(listOf(fixture))

        viewModel.requestStats()
        awaitStats(viewModel) { it is LogTagStatsState.Ready }
        val ready = assertIs<LogTagStatsState.Ready>(viewModel.statsState.value)

        assertEquals(fixture.name, ready.fileName, "统计对象 = 预演锚点文件（最近拖入批首文件）")
        assertEquals(6, ready.totalBlocks, "fixture 共 6 块")
        assertEquals(
            listOf("Alpha" to 2, "Backtrack" to 1, "Beta" to 1, "Gamma" to 1),
            ready.topTags,
            "Tag 频次降序（并列按字典序），Alpha 2 块居首",
        )
        assertEquals(4, ready.totalTagCount, "去重 Tag 共 4 个")
        assertEquals(1, ready.unparsedTagHeads, "块 1 裸文本头解析不出 Tag")
        assertEquals(
            listOf("ERROR" to 2, "WARN" to 1, "INFO" to 1, "DEBUG" to 1),
            ready.levelCounts,
            "级别计数按严重度降序、仅非零档",
        )
        assertEquals(1, ready.unparsedLevelHeads, "块 1 裸文本头解析不出级别")
        // first 按 min 取值（block-6 时间最早但出现最晚）
        assertEquals("2026-10-11 07:59:00.000", ready.firstTimestampText)
        assertEquals("2026-10-11 08:00:05.000", ready.lastTimestampText)
        assertEquals(65_000L, ready.spanMillis, "07:59:00.000 → 08:00:05.000 = 65 秒")
    }

    @Test
    fun `requestStats without anchor file fails with actionable hint`() {
        val viewModel = vm()
        drainInit()

        viewModel.requestStats()
        val failed = assertIs<LogTagStatsState.Failed>(viewModel.statsState.value, "未拖文件触发统计应给提示态而非静默")
        assertTrue("拖入" in failed.reason, "提示须指明先拖入文件：${failed.reason}")
    }

    @Test
    fun `stats never auto-runs and idle state hides panel`() {
        val viewModel = vm()
        drainInit()

        viewModel.addFiles(listOf(statsFixtureFile()))
        repeat(5) { dispatcher.scheduler.advanceUntilIdle(); Thread.sleep(20) }
        assertIs<LogTagStatsState.Idle>(viewModel.statsState.value, "统计是全量 IO——拖入不自动跑，保持 Idle 待手动触发")
    }

    // ── 统计送飞书（sendStatsToLark：ReportDelivery 第二消费面）────────

    /** 统计 Ready 前置 VM（拖入 fixture → requestStats → 轮询至 Ready） */
    private fun readyStatsVm(): LogTagFilterViewModel {
        val viewModel = vm()
        drainInit()
        viewModel.addFiles(listOf(statsFixtureFile()))
        viewModel.requestStats()
        awaitStats(viewModel) { it is LogTagStatsState.Ready }
        return viewModel
    }

    /** 轮询等待回执 toast 出现（导出/送出协程与 Main 同 TestScheduler——同 awaitStats 口径） */
    private fun awaitToast(predicate: (String) -> Boolean): String {
        repeat(100) {
            dispatcher.scheduler.advanceUntilIdle()
            StatusSignals.toast.value.lastOrNull()?.text?.let { if (predicate(it)) return it }
            Thread.sleep(20)
        }
        error("回执 toast 未在轮询预算内出现")
    }

    @Test
    fun `sendStatsToLark is a no-op when stats not ready`() {
        StatusSignals.clearForTest()
        val viewModel = vm()
        drainInit()
        var delivered: File? = null
        ReportDelivery.wire { f -> delivered = f; null }

        viewModel.sendStatsToLark()
        repeat(10) { dispatcher.scheduler.advanceUntilIdle(); Thread.sleep(20) }

        assertNull(delivered, "统计未生成（Idle）时送出动作不得发起——不导出不触通道（与导出钮同款前置判据）")
        assertTrue(StatusSignals.toast.value.isEmpty(), "no-op 守卫路径不得产生回执 toast")
    }

    @Test
    fun `sendStatsToLark without wired channel fails with human-readable receipt`() {
        val viewModel = readyStatsVm()
        StatusSignals.clearForTest()
        assertFalse(ReportDelivery.ready.value, "前置：通道未接线（ready=false）")

        viewModel.sendStatsToLark()
        val receipt = awaitToast { "不可用" in it }

        assertTrue(
            "报告外发通道不可用" in receipt,
            "ready=false 时送出须回执人话失败而非静默/异常（通道归一），实际：$receipt",
        )
    }

    @Test
    fun `sendStatsToLark delivers freshly exported product to wired channel`() {
        val viewModel = readyStatsVm()
        StatusSignals.clearForTest()
        var delivered: File? = null
        ReportDelivery.wire { f -> delivered = f; null }
        ReportDelivery.publishReady(true)

        viewModel.sendStatsToLark()
        repeat(100) {
            dispatcher.scheduler.advanceUntilIdle()
            if (delivered != null) return@repeat
            Thread.sleep(20)
        }
        val file = assertNotNull(delivered, "就绪通道必须收到统计产物 File")

        assertTrue(file.isFile, "送达产物必须真实在盘（发送前恒重新导出——工作台报告同款先例）")
        assertEquals(
            StatsMarkdownExporter.exportDir(),
            file.parentFile,
            "产物落点 = 导出产物树（~/Documents/Toolbelt/reports/）——与「导出 Markdown」产物同源",
        )
        assertTrue(
            Regex("stats-\\d{8}-\\d{6}\\.md").matches(file.name),
            "产物名 = stats-<秒级时间键>.md（StatsMarkdownExporter 路径决策），实际：${file.name}",
        )
        assertTrue("日志分布统计" in file.readText(), "产物内容 = 统计 Markdown 本体（非空壳）")
        assertEquals(
            file.absolutePath,
            viewModel.exportedStatsPath.value,
            "面板产物行回显与送达文件同源同路径",
        )
        val receipt = awaitToast { "已送出至飞书会话" in it }
        assertTrue(file.name in receipt, "成功回执须指名送达文件（回执可核对）：$receipt")
    }

    @Test
    fun `sendStatsToLark surfaces channel failure reason verbatim`() {
        val viewModel = readyStatsVm()
        StatusSignals.clearForTest()
        ReportDelivery.wire { "通道实现的失败人话" }
        ReportDelivery.publishReady(true)

        viewModel.sendStatsToLark()
        val receipt = awaitToast { "通道实现的失败人话" in it }

        assertEquals(
            "通道实现的失败人话",
            receipt,
            "通道失败人话必须原样透传为回执（归一在通道实现方，消费面不二次加工）",
        )
    }

    @Test
    fun `preview appears after file drop and follows regex edits`() {
        val viewModel = vm()
        drainInit()
        val fixture = previewFixtureFile()

        // 未拖文件：仅改正则不触发预演
        viewModel.onTagRegexChange("MyTag")
        repeat(5) { dispatcher.scheduler.advanceUntilIdle(); Thread.sleep(20) }
        assertNull(viewModel.regexPreview.value, "未拖文件时预演不触发")

        // 拖入即捕获锚点（validate 拒批——name 为空——锚点照常记录，预演照常可用）
        viewModel.addFiles(listOf(fixture))
        awaitPreview(viewModel) { it != null }
        var preview = assertNotNull(viewModel.regexPreview.value, "拖入后预演应出现")
        assertEquals(2, preview.matchedBlocks, "块 2/3 命中 MyTag")
        assertEquals(3, preview.totalBlocks, "fixture 共 3 块")
        assertEquals(0, preview.skippedTailBlocks, "fixture 短于扫描上限，无截断")
        assertEquals(false, preview.truncated)
        assertEquals(
            "10-11 12:00:02.001 E/plain: block-2 head",
            preview.firstMatchedBlockPreview.first(),
            "首个命中块预览从块 2 头行开始",
        )

        // 正则改了立刻见效（预演跟随输入更新）
        viewModel.onTagRegexChange("block-1")
        awaitPreview(viewModel) { it?.matchedBlocks == 1 }
        preview = assertNotNull(viewModel.regexPreview.value)
        assertEquals(1, preview.matchedBlocks, "改正则后预演跟随更新（block-1 仅块 1 命中）")
        assertEquals(
            "10-11 12:00:01.001 E/plain: block-1 head",
            preview.firstMatchedBlockPreview.first(),
        )
    }

    @Test
    fun `invalid or blank regex never triggers preview`() {
        val viewModel = vm()
        drainInit()
        viewModel.addFiles(listOf(previewFixtureFile()))

        // 非法正则：拖入被拒（validate 拦截），预演也不触发
        viewModel.onTagRegexChange("[")
        repeat(10) { dispatcher.scheduler.advanceUntilIdle(); Thread.sleep(20) }
        assertNull(viewModel.regexPreview.value, "非法正则不触发预演")

        // 修正后预演出现（拖入被拒 → 边改正则边预演的主场景）
        viewModel.onTagRegexChange("MyTag")
        awaitPreview(viewModel) { it?.matchedBlocks == 2 }

        // 清空正则：预演置回 null（空串视为未填写，同 validate 空值语义）
        viewModel.onTagRegexChange("")
        repeat(10) { dispatcher.scheduler.advanceUntilIdle(); Thread.sleep(20) }
        assertNull(viewModel.regexPreview.value, "空正则应置回无预演态")
    }

    @Test
    fun `preview scans only first PREVIEW_MAX_LINES lines of large file`() {
        // 1001 块 × 2 行 = 2002 行 > PREVIEW_MAX_LINES(2000)：前 2000 行 = 块 1-1000
        // 完整；块 1001 尾块截断丢弃；命中块只有块 1/500（块 1001 的命中在扫描界外）
        val dir = Files.createTempDirectory("edt-logtag-preview-n").toFile()
        val file = File(dir, "large-fixture.txt")
        file.printWriter().use { writer ->
            for (i in 1..1001) {
                writer.println("10-11 12:00:00.001 E/plain: block-$i head")
                writer.println(
                    if (i == 1 || i == 500) "continuation block-$i MyTag hit"
                    else "continuation block-$i miss",
                )
            }
        }

        val viewModel = vm()
        drainInit()
        viewModel.onTagRegexChange("MyTag")
        viewModel.addFiles(listOf(file))
        awaitPreview(viewModel) { it != null }

        val preview = assertNotNull(viewModel.regexPreview.value)
        assertEquals(LogTagFilterViewModel.PREVIEW_MAX_LINES, preview.scannedLines, "扫描恒停在行数上限")
        assertEquals(true, preview.truncated, "2002 行文件 > 2000 行上限——计数非全量")
        assertEquals(2, preview.matchedBlocks, "仅扫描界内块 1/500 命中；块 1001 的命中不可见")
        assertEquals(1, preview.skippedTailBlocks, "截断在块 1001 中间 → 尾块丢弃计 1")
    }

    // ── 统计 Tag 频次行回填（backfillTagRegex：转义 + 覆盖 + 预演跟随）────

    @Test
    fun `backfill escapes metacharacters replaces existing regex and keeps it valid`() {
        val viewModel = vm()
        drainInit()
        viewModel.addFiles(listOf(statsFixtureFile()))
        // 已有非空正则：点击频次行语义 = 覆盖（非追加组合）
        viewModel.onTagRegexChange("MyTag")

        viewModel.backfillTagRegex("Beta(1234)")

        assertEquals(
            "Beta\\(1234\\)",
            viewModel.tagRegex.value,
            "Tag 字面值逐字转义元字符后覆盖写入（统计行 Tag 取自日志原文，可含元字符）",
        )
        assertEquals(true, viewModel.regexValid.value, "转义后的字面值恒为合法正则")
        assertEquals("", viewModel.regexErrorReason.value, "合法回填不置错因")
        assertNull(viewModel.inputError.value, "回填走输入链路——既有 inputError 随输入清空")
    }

    @Test
    fun `backfilled literal matches by containment and preview follows`() {
        val viewModel = vm()
        drainInit()
        viewModel.addFiles(listOf(statsFixtureFile()))

        viewModel.backfillTagRegex("Beta(1234)")

        // 回填走既有输入链路：预演自动跟随（无需再触发）
        awaitPreview(viewModel) { it?.matchedBlocks == 1 }
        val preview = assertNotNull(viewModel.regexPreview.value, "回填后预演自动跟随")
        assertEquals(
            1, preview.matchedBlocks,
            "包含匹配语义（无 ^ $ 锚）：仅块 3（W/Beta(1234)）命中——锚定形态永不命中",
        )
        assertEquals(
            "2026-10-11 08:00:01.000 W/Beta(1234): block-3 head",
            preview.firstMatchedBlockPreview.first(),
        )
    }

    @Test
    fun `backfill plain tag stays literal identity`() {
        val viewModel = vm()
        drainInit()
        viewModel.addFiles(listOf(statsFixtureFile()))

        viewModel.backfillTagRegex("Alpha")

        assertEquals("Alpha", viewModel.tagRegex.value, "无元字符 Tag 转义后原样（人可读）")
        awaitPreview(viewModel) { it?.matchedBlocks == 2 }
        assertEquals(
            2, assertNotNull(viewModel.regexPreview.value).matchedBlocks,
            "块 2/4（E/Alpha）命中",
        )
    }

    @Test
    fun `backfill empty tag is a no-op`() {
        val viewModel = vm()
        drainInit()
        viewModel.onTagRegexChange("MyTag")

        viewModel.backfillTagRegex("")

        assertEquals("MyTag", viewModel.tagRegex.value, "空 Tag 不回填——置空正则须走输入框显式清空")
    }

    // ── 拖入前拦截（validate 分支）──────────────────────────────

    @Test
    fun `invalid regex rejected before task creation with four-part message`() {
        val viewModel = vm()
        viewModel.onTagRegexChange("[")   // 未闭合字符类：Regex 编译必抛
        viewModel.onTagNameChange("myTag")

        viewModel.addFiles(listOf(tempFile()))

        val error = assertNotNull(viewModel.inputError.value, "非法正则拖入应置 inputError 拒整批")
        // 四要素：① 问题定位 ② 原因片段（编译错误 message）③ 修正指引 ④ 内容保留承诺
        assertTrue(error.startsWith("Tag 正则语法有误"), "文案须以问题定位开头：$error")
        assertTrue("[" in error, "文案须含编译原因片段：$error")
        assertTrue(error.contains("修正正则后重新拖入"), "文案须含修正指引：$error")
        assertTrue(error.contains("已输入内容已保留"), "文案须含内容保留承诺：$error")
        assertTrue(
            viewModel.tasks.value.isEmpty(),
            "非法正则应整批拒绝（零任务卡）——不应到 process 阶段才以「执行失败」报错",
        )
    }

    @Test
    fun `invalid regex cause hint truncated to 120 chars`() {
        val viewModel = vm()
        // 未闭合组 + 长尾：PatternSyntaxException message 含近邻原文，长度可超 120
        viewModel.onTagRegexChange("((" + "x".repeat(300))
        viewModel.onTagNameChange("myTag")

        viewModel.addFiles(listOf(tempFile()))

        val error = assertNotNull(viewModel.inputError.value)
        val hint = error.substringAfter("（", "").substringBefore("）", "")
        assertTrue(
            hint.length <= LogTagFilterViewModel.REGEX_ERROR_HINT_CHARS + 10,
            "原因片段应截断（实际 ${hint.length} 字）：$hint",
        )
    }

    @Test
    fun `valid regex still passes validation and creates task card`() {
        val viewModel = vm()
        viewModel.onTagRegexChange("My(T|t)ag")
        viewModel.onTagNameChange("myTag")

        viewModel.addFiles(listOf(tempFile()))

        assertNull(viewModel.inputError.value, "合法正则不应置 inputError")
        assertEquals(1, viewModel.tasks.value.size, "合法正则应正常建卡（既有行为不回归）")
    }

    @Test
    fun `empty regex and name keep existing empty-rejection semantics`() {
        val viewModel = vm()
        viewModel.onTagNameChange("myTag")

        viewModel.addFiles(listOf(tempFile()))

        assertEquals("Tag 正则不能为空", viewModel.inputError.value, "空正则优先级保持既有顺序")
        assertTrue(viewModel.tasks.value.isEmpty())
    }

    // ── 输入即时反馈（regexValid 三态）────────────────────────

    @Test
    fun `regexValid reflects syntax instantly on each keystroke`() {
        val viewModel = vm()

        viewModel.onTagRegexChange("[")
        assertEquals(false, viewModel.regexValid.value, "非法正则应即时 false")

        viewModel.onTagRegexChange("[a-z]+")
        assertEquals(true, viewModel.regexValid.value, "修正为合法正则应即时翻回 true")

        viewModel.onTagRegexChange("")
        assertEquals(true, viewModel.regexValid.value, "空串视为未填写、不置错（同 validate 空值语义）")
    }

    @Test
    fun `regexValid stays true for name-only edits`() {
        val viewModel = vm()
        viewModel.onTagNameChange("myTag")
        assertEquals(true, viewModel.regexValid.value, "名称输入不应影响正则校验态")
    }

    @Test
    fun `persisted invalid regex revalidates on init restore`() {
        // L8 · 重启恢复重算校验态：init 直灌若不重编正则，_regexValid 恒默认 true——
        // 卡面与真实语法态背离（拖入才在 validate 撞错）。种子 = 直接写持久层
        //（与 init 恢复同一数据源），建 VM 后断言校验态与错误原因同步置位。
        val seedScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        var seeded = false
        seedScope.launch { LogKitConfigStore.setLogTagRegex("["); seeded = true }
        // DataStore 写经注入 Unconfined datastoreScope 同步落定——首拍 advanceUntilIdle
        // 即完成；轮询环保留作跨调度器链路兜底（sleep 仅未落定时消耗）
        repeat(50) {
            dispatcher.scheduler.advanceUntilIdle()
            if (!seeded) Thread.sleep(20)
        }
        assertTrue(seeded, "种子写入未完成（DataStore 落盘超时）——环境异常非被测行为")

        val viewModel = vm()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.regexValid.value, "恢复的坏正则必须重算出 false（init 回填后重编校验链）")
        assertTrue(
            viewModel.regexErrorReason.value.isNotEmpty(),
            "恢复期置错须带编译原因（与输入即时反馈同链）",
        )
        seedScope.cancel()
    }

    // ── 取消收口（回执不谎报 + 历史产物不破坏）────────────────────

    @Test
    fun `cancel keeps pre-existing tag product intact and backfills receipt`() {
        val viewModel = vm()
        viewModel.onTagRegexChange("My(T|t)ag")
        viewModel.onTagNameChange("myTag")
        val dir = Files.createTempDirectory("edt-logtag-cancel").toFile()
        // 输入文件名固定（收口按 `<名>.filter-tag_` 前缀匹配——随机临时名会错位）
        val input = File(dir, "app.log").apply { writeText("line\n") }
        val previous = File(dir, "app.filter-tag_myTag.txt").apply { writeText("PREVIOUS-FILTERED") }

        // 产物在盘 → 挂覆盖裁决；取消裁决 = 不建卡 + 历史产物原样保留
        viewModel.addFiles(listOf(input))
        assertNotNull(viewModel.overwriteRequest.value, "产物在盘须挂覆盖裁决")
        viewModel.dismissOverwrite()
        assertEquals(0, viewModel.tasks.value.size, "取消覆盖不建卡")
        assertEquals("PREVIOUS-FILTERED", previous.readText(), "取消覆盖不得动历史产物")

        // 确认覆盖：精确产物被清理后建卡；启动前取消 → 无在盘产物可回填
        viewModel.addFiles(listOf(input))
        viewModel.confirmOverwrite()
        assertTrue(!previous.exists(), "确认覆盖清理精确冲突产物")
        viewModel.cancelTask(viewModel.tasks.value.single().id)
        dispatcher.scheduler.advanceUntilIdle()

        val task = viewModel.tasks.value.single()
        val status = task.status as? TaskUiStatus.Cancelled
        assertNotNull(status, "启动前取消应落 Cancelled：${task.status}")
        assertEquals(null, status.partialOutput, "历史产物已按覆盖确认清除——取消无在盘产物可回填")
    }
}
