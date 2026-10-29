package com.jiaoay.easy_dev_toolbox.page.logcat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * logcat 抓取引擎落盘契约回归（fake 流式输出，不真跑 adb）：
 * 落盘文件无半行残留、tag/级别过滤命中、停止后文件句柄关闭、
 * scope teardown 恰逢抓取中（取消时序）写盘收口不可中断（writer 必关+
 * 终态落 Stopped+落盘内容完整）、
 * 滚动窗有界（截最旧 + 丢弃计数，落盘写全量流）、运行中不重入、
 * 中途换过滤只影响后续行（已显示/已落盘行不回溯，非运行态拒绝换）、
 * 清屏只清显示（滚动窗清空 + 落盘/全量计数零变化 + 在途行不复活 +
 * 后续行照常追加，非运行态拒绝）、标记复现点（Running 态记屏上最新
 * 带时间戳行、后续标记覆盖、锚点跨终态保留且随下次 start 清零、
 * 非运行态与无带时间戳行拒绝、完整串双承载派生——裸月日头补标记年/
 * 年份形态原文透传）、武装捕获（命中行自身时间戳自动落锚 + 命中即
 * 收网 one-shot、解除武装撤销、非运行态拒绝、武装眼看过滤挡下的行
 * 且零触碰落盘/过滤口径、无时间戳命中行退回屏上语义）、
 * MatchAll 恒等快路径（无原子规格免 parse 恒真，带原子规格不可解析行
 * 拒收契约不回归）、折叠行组随窗发布（每窗快照 rows 与全量参考实现
 * foldConsoleRows 一致——含溢出截头与尾段帧组跨批成长）。
 */
class LogcatCaptureEngineTest {

    private lateinit var scope: CoroutineScope
    private lateinit var runner: FakeRunner
    private lateinit var engine: LogcatCaptureEngine

    @AfterTest
    fun tearDown() {
        if (::scope.isInitialized) {
            engine.stop()
            scope.cancel()
        }
    }

    private fun newEngine(): LogcatCaptureEngine {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runner = FakeRunner()
        engine = LogcatCaptureEngine(scope, runner)
        return engine
    }

    private suspend fun awaitStopped(timeoutMs: Long = 5_000) {
        withTimeout(timeoutMs) {
            engine.status.first { it is LogcatCaptureStatus.Stopped }
        }
    }

    @Test
    fun `captured file holds complete lines in order without partial residue`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        // 含长行与裸 \r 行：StreamingShell 按 \n 分行回调，回调行即完整行——
        // 半行残留只会来自引擎自身的写盘方式（逐行 write+newLine 则必完整）
        runner.programmed = listOf(
            "10-11 12:00:00.001  100  200 I ActivityManager: Start proc",
            "10-11 12:00:00.002  100  200 E Camera: open failed\r",
            "x".repeat(5000),
            "",
        )
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        awaitStopped()

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
        assertEquals(runner.programmed.size.toLong(), stopped.totalCount, "落盘总行数应等于流内全部行数")
        assertEquals(runner.programmed, out.contentLines(), "落盘行序与流序一致、无半行残留")
        // 末行终止符齐全（按行落盘语义：每行 write + newLine，newLine 为平台分隔符）
        val sep = System.lineSeparator()
        val text = out.readText()
        assertEquals(runner.programmed.joinToString("") { "$it$sep" }, text, "落盘文件逐字节完整")
        assertEquals(runner.programmed.size, text.count { it == '\n' }, "行数 = 换行数（无内嵌断裂）")
        Unit
    }

    @Test
    fun `captured file is written as UTF-8 regardless of platform default charset`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        val chinese = "10-11 12:00:00.001  100  200 I 应用进程: 中文日志行 · 表情标签"
        runner.programmed = listOf(chinese)
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        awaitStopped()

        // 按字节断言而非 readText()：平台默认编码恰为 UTF-8 时 readText 会掩盖编码错误，
        // 逐字节比对才能钉死「落盘编码不随平台默认漂移」的契约。
        val expected = (chinese + System.lineSeparator()).toByteArray(Charsets.UTF_8)
        assertContentEquals(expected, out.readBytes(), "落盘必须是 UTF-8 字节（消费端查看器恒按 UTF-8 读）")
    }

    @Test
    fun `tag filter keeps only matching lines on disk and window`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        runner.programmed = listOf(
            "10-11 12:00:00.001  100  200 I ActivityManager: Start proc",
            "10-11 12:00:00.002  100  201 D CameraService: connect",
            "10-11 12:00:00.003  100  202 W ActivityManager: Low memory",
            "--------- beginning of main",
        )
        assertTrue(
            engine.start(
                "adb logcat",
                out,
                LogcatLineMatcher(LogcatFilterSpec("activitymanager", null)),
            ),
        )
        awaitStopped()

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
        assertEquals(2, stopped.totalCount, "tag 过滤只命中 ActivityManager 两行（不可解析行不命中）")
        assertEquals(
            listOf(
                "10-11 12:00:00.001  100  200 I ActivityManager: Start proc",
                "10-11 12:00:00.003  100  202 W ActivityManager: Low memory",
            ),
            out.contentLines(),
            "落盘文件只含 tag 命中行",
        )
        assertEquals(2, engine.window.value.lines.size, "滚动窗与落盘同源同口径")
        Unit
    }

    @Test
    fun `min level filter keeps warn and above`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        runner.programmed = listOf(
            "10-11 12:00:00.001  100  200 V VerboseTag: v",
            "10-11 12:00:00.002  100  200 I InfoTag: i",
            "10-11 12:00:00.003  100  200 W WarnTag: w",
            "10-11 12:00:00.004  100  200 E ErrorTag: e",
            // brief 形态（行首 L/TAG）：与 threadtime 同一匹配口径
            "F/FatalTag( 300): crash",
        )
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", LogcatLevel.WARN))))
        awaitStopped()

        assertEquals(
            listOf(
                "10-11 12:00:00.003  100  200 W WarnTag: w",
                "10-11 12:00:00.004  100  200 E ErrorTag: e",
                "F/FatalTag( 300): crash",
            ),
            out.contentLines(),
            "级别过滤只留 >= W（brief 形态同口径命中）",
        )
        Unit
    }

    @Test
    fun `msg query filter keeps only lines whose message matches`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        runner.programmed = listOf(
            "10-11 12:00:00.001  100  200 I ActivityManager: Start proc camera",
            "10-11 12:00:00.002  100  201 D CameraService: connect",
            "--------- beginning of main",
        )
        // 查询语法编译到统一过滤模型后进 matcher——与旧构造同一消费通道
        val spec = when (val parsed = LogcatQueryParser.parse("msg:camera")) {
            is LogcatQueryParseResult.Ok -> parsed.spec
            is LogcatQueryParseResult.Err -> error("msg: 解析意外失败：${parsed.reason}")
        }
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(spec)))
        awaitStopped()

        assertEquals(
            listOf("10-11 12:00:00.001  100  200 I ActivityManager: Start proc camera"),
            out.contentLines(),
            "msg 过滤命中消息体含词的行（tag 无 camera 的行也命中；不可解析行拒收）",
        )
        assertEquals(1, engine.window.value.lines.size, "滚动窗与落盘同源同口径")
        Unit
    }

    @Test
    fun `stop closes file handle and flushes all emitted lines`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        runner.programmed = listOf(
            "10-11 12:00:00.001  100  200 I Tag: line-1",
            "10-11 12:00:00.002  100  200 I Tag: line-2",
        )
        runner.blockAfterEmit = true
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        // 运行中：终态未落定
        withTimeout(2_000) { engine.status.first { it is LogcatCaptureStatus.Running } }
        engine.stop()
        awaitStopped()

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
        assertEquals(2, stopped.totalCount, "停止时全部已发行都已落盘")
        assertEquals(runner.programmed, out.contentLines(), "停止后文件完整可读（无半行残留）")
        assertNoOpenFd(out, "停止终态落定后文件句柄必须已关闭")
        Unit
    }

    @Test
    fun `scope teardown mid capture still closes writer and settles stopped`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        runner.blockAfterEmit = true
        runner.programmed = listOf(
            "10-11 12:00:00.001  100  200 I Tag: line-1",
            "10-11 12:00:00.002  100  200 I Tag: line-2",
        )
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(2_000) { engine.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { engine.window.first { it.totalCount == 2L } }

        // teardown 恰逢抓取中：scope 取消同时击中捕获协程与收集协程——收尾必须顶住
        // 取消完成写盘收口；收集协程已死时冲刷走防御 drain（ack 等待 1s 超时后
        // tryReceive 清空），终态最长约 1s 落定
        scope.cancel()
        awaitStopped()

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
        assertEquals(2L, stopped.totalCount, "取消路径全量计数不丢")
        assertEquals(runner.programmed, out.contentLines(), "取消路径落盘内容完整（写盘收口不可中断）")
        assertNoOpenFd(out, "取消路径终态落定后文件句柄必须已关闭")
        Unit
    }

    @Test
    fun `rolling window is bounded while disk keeps full stream`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        val total = LogcatCaptureEngine.MAX_RING_LINES + 500
        runner.programmed = (1..total).map { "10-11 12:00:00.001  100  200 I Tag: line-$it" }
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        awaitStopped()

        val window = engine.window.value
        assertEquals(LogcatCaptureEngine.MAX_RING_LINES, window.lines.size, "滚动窗封顶在 MAX_RING_LINES")
        assertEquals(500, window.droppedCount, "超限丢最旧并计数，不静默截断")
        assertEquals(total.toLong(), window.totalCount, "全量计数不受滚动窗截断影响")
        val diskLines = out.contentLines()
        assertEquals(total, diskLines.size, "落盘承载全量流")
        assertEquals(
            "10-11 12:00:00.001  100  200 I Tag: line-$total",
            diskLines.last(),
            "落盘末行 = 流末行",
        )
        Unit
    }

    @Test
    fun `start while running is rejected`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Tag: only")
        runner.blockAfterEmit = true
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(2_000) { engine.status.first { it is LogcatCaptureStatus.Running } }

        assertFalse(
            engine.start("adb logcat", tempOutFile(), LogcatLineMatcher(LogcatFilterSpec("", null))),
            "运行中不重入（防两路流互踩同一落盘句柄）",
        )
        engine.stop()
        awaitStopped()
        Unit
    }

    @Test
    fun `launch failure with zero hits cleans up empty artifact`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        runner.programmed = emptyList()
        runner.launchFailed = true
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        awaitStopped()

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
        assertEquals(0L, stopped.totalCount)
        assertTrue(!out.exists(), "启动失败且零命中不留守卫空产物文件")
        Unit
    }

    @Test
    fun `mid capture matcher swap narrows only subsequent lines`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        runner.stagedBatches = listOf(
            listOf(
                "10-11 12:00:00.003  100  200 I Camera: close",
                "10-11 12:00:00.004  100  200 D Sensor: reading-2",
            ),
        )
        runner.programmed = listOf(
            "10-11 12:00:00.001  100  200 I Camera: open",
            "10-11 12:00:00.002  100  200 D Sensor: reading",
        )
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(2_000) { engine.window.first { it.totalCount == 2L } }

        assertTrue(
            engine.updateMatcher(LogcatLineMatcher(LogcatFilterSpec("camera", null))),
            "运行中换过滤须生效（原地收窄，免停止重抓）",
        )
        runner.releaseNextBatch()
        awaitStopped()

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
        assertEquals(3L, stopped.totalCount, "总命中 = 首批 2 行（旧过滤全放行）+ 二批 1 行（新过滤只放行 Camera）")
        assertTrue(
            engine.window.value.lines.contains("10-11 12:00:00.002  100  200 D Sensor: reading"),
            "已显示行不回溯重算——换过滤前进窗的 Sensor 行原样保留",
        )
        assertEquals(
            listOf(
                "10-11 12:00:00.001  100  200 I Camera: open",
                "10-11 12:00:00.002  100  200 D Sensor: reading",
                "10-11 12:00:00.003  100  200 I Camera: close",
            ),
            out.contentLines(),
            "落盘按行到达时刻的 matcher 判定全量写——首批全量保留 + 二批只写 Camera 命中行",
        )
        Unit
    }

    @Test
    fun `updateMatcher outside running capture is a no-op rejection`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        assertFalse(
            engine.updateMatcher(LogcatLineMatcher(LogcatFilterSpec("camera", null))),
            "Idle 态无进行中抓取——换过滤拒绝且无副作用",
        )

        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Camera: open")
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        awaitStopped()
        assertFalse(
            engine.updateMatcher(LogcatLineMatcher(LogcatFilterSpec("camera", null))),
            "已停止后换过滤拒绝——过滤随下次 start 定型",
        )
        Unit
    }

    @Test
    fun `clear display empties window and keeps disk total count and overflow count intact`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        // 溢出存量先行：滚动窗超限产生 droppedCount 后清屏——其口径 = 溢出累计，
        // 清屏不是溢出截断，不追加也不撤销（totalCount 是全量账，恒不因清屏变化）
        val overflow = 100
        val preClear = LogcatCaptureEngine.MAX_RING_LINES + overflow
        runner.programmed = (1..preClear).map { "10-11 12:00:00.001  100  200 I Tag: line-$it" }
        runner.stagedBatches = listOf(
            listOf("10-11 12:00:00.002  100  200 I Tag: post-clear-1"),
            listOf("10-11 12:00:00.003  100  200 I Tag: post-clear-2"),
        )
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(5_000) { engine.window.first { it.totalCount == preClear.toLong() } }
        assertEquals(overflow, engine.window.value.droppedCount, "前置：超限产生溢出计数")

        assertTrue(engine.clearDisplay(), "运行中清屏须生效")
        val cleared = engine.window.value
        assertEquals(0, cleared.lines.size, "清屏后滚动窗为空")
        assertEquals(preClear.toLong(), cleared.totalCount, "全量计数清屏前后零变化（清显示不清账）")
        assertEquals(overflow, cleared.droppedCount, "droppedCount 口径 = 溢出累计，清屏不追加也不撤销")

        runner.releaseNextBatch()
        withTimeout(5_000) { engine.window.first { it.totalCount == preClear + 1L } }
        assertEquals(
            listOf("10-11 12:00:00.002  100  200 I Tag: post-clear-1"),
            engine.window.value.lines,
            "清屏后命中行照常追加显示（只影响后续行）",
        )
        runner.releaseNextBatch()
        awaitStopped()

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
        assertEquals((preClear + 2).toLong(), stopped.totalCount, "全量计数照常累计")
        val diskLines = out.contentLines()
        assertEquals(preClear + 2, diskLines.size, "落盘承载全量流——清屏前的行全量保留")
        assertEquals(
            listOf(
                "10-11 12:00:00.002  100  200 I Tag: post-clear-1",
                "10-11 12:00:00.003  100  200 I Tag: post-clear-2",
            ),
            diskLines.takeLast(2),
            "落盘行序与流序一致，清屏不触碰落盘内容",
        )
        Unit
    }

    @Test
    fun `clear display filters in-flight lines from window but keeps them in total count`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Tag: pre-clear")
        runner.stagedBatches = listOf(
            listOf(
                "10-11 12:00:00.002  100  200 I Tag: in-flight-1",
                "10-11 12:00:00.003  100  200 I Tag: in-flight-2",
            ),
            listOf("10-11 12:00:00.004  100  200 I Tag: post-clear"),
        )
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(5_000) { engine.window.first { it.totalCount == 1L } }

        // 发完批即清屏：批内行必已入 channel（先落盘后 send），但未必已提交上屏——
        // 两条路径（已提交被清 / 在途被世代过滤）终态同为不上屏、仍计数；终态条件
        // 须双联（计数 + 空窗），单联计数会在「先提交后清」路径上抓到清屏前瞬态
        runner.afterBatchAction = { engine.clearDisplay() }
        runner.releaseNextBatch()
        withTimeout(5_000) { engine.window.first { it.totalCount == 3L && it.lines.isEmpty() } }
        assertEquals(
            3L,
            engine.window.value.totalCount,
            "在途行照常计入 totalCount（全量计数不吃显示过滤）",
        )

        runner.afterBatchAction = null
        runner.releaseNextBatch()
        withTimeout(5_000) { engine.window.first { it.totalCount == 4L } }
        assertEquals(
            listOf("10-11 12:00:00.004  100  200 I Tag: post-clear"),
            engine.window.value.lines,
            "清屏后到达的行照常上屏",
        )
        awaitStopped()

        assertEquals(
            listOf(
                "10-11 12:00:00.001  100  200 I Tag: pre-clear",
                "10-11 12:00:00.002  100  200 I Tag: in-flight-1",
                "10-11 12:00:00.003  100  200 I Tag: in-flight-2",
                "10-11 12:00:00.004  100  200 I Tag: post-clear",
            ),
            out.contentLines(),
            "在途行照常落盘且计入 totalCount（清显示不清账）",
        )
        Unit
    }

    @Test
    fun `clear display outside running capture is a no-op rejection`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        assertFalse(engine.clearDisplay(), "Idle 态清屏拒绝——无进行中抓取，清显示无意义")

        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Tag: only")
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        awaitStopped()
        val windowAtStop = engine.window.value
        assertFalse(engine.clearDisplay(), "已停止后清屏拒绝——滚动窗随终态定格")
        assertEquals(windowAtStop, engine.window.value, "拒绝时零副作用（窗口快照原样）")
        Unit
    }

    @Test
    fun `markAnchor records latest visible timestamped line and later marks overwrite`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        runner.blockAfterEmit = true
        runner.programmed = listOf(
            "10-11 12:00:00.001  100  200 I Tag: line-1",
            "10-11 12:00:00.002  100  200 I Tag: line-2",
        )
        runner.stagedBatches = listOf(
            listOf("10-11 12:00:00.003  100  200 I Tag: line-3"),
            listOf("F/FatalTag( 300): crash"), // 无时间戳行——回扫须跳过取更早的带时间戳行
        )
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(2_000) { engine.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { engine.window.first { it.totalCount == 2L } }

        val first = engine.markAnchor()
        assertTrue(first != null, "Running 态标记须成功")
        assertEquals("10-11 12:00:00.002", first!!.timestamp, "锚点 = 屏上最新带时间戳行的行首时间戳")
        assertEquals("12:00:00", first.clockText, "clockText = 回显时钟段")
        assertEquals("10-11 12:00:00.002", engine.anchor.value?.timestamp, "锚点经 StateFlow 发布")

        runner.releaseNextBatch()
        withTimeout(5_000) { engine.window.first { it.totalCount == 3L } }
        val second = engine.markAnchor()
        assertEquals(
            "10-11 12:00:00.003",
            second?.timestamp,
            "后续标记覆盖前标记（复现瞬间是单点事件，单锚点）",
        )

        runner.releaseNextBatch()
        withTimeout(5_000) { engine.window.first { it.totalCount == 4L } }
        val third = engine.markAnchor()
        assertEquals(
            "10-11 12:00:00.003",
            third?.timestamp,
            "无时间戳行不承载锚点——回扫取最近的带时间戳行",
        )

        engine.stop()
        awaitStopped()
        assertEquals(
            "10-11 12:00:00.003",
            engine.anchor.value?.timestamp,
            "锚点跨 Stopped 终态保留（消费面是终态「查看」的初始定位）",
        )

        // 下次抓取清零：上次产物里的锚点不得跨入新产物误导「查看」直达
        runner.blockAfterEmit = false
        runner.stagedBatches = emptyList()
        runner.programmed = listOf("10-11 13:00:00.001  100  200 I Tag: fresh-capture")
        assertTrue(engine.start("adb logcat", tempOutFile(), LogcatLineMatcher(LogcatFilterSpec("", null))))
        assertNull(engine.anchor.value, "start 须清零上一轮抓取的标记锚点")
        awaitStopped()
        Unit
    }

    /**
     * markedYear 双承载派生：裸月日形态（threadtime 默认无年份）以标记时刻本机年
     * 补前缀，年份形态（`-v year` 抓取）原文透传不改写；clockText 两形态同纳。
     * 完整串是「送工作台分析」的问题时间点载荷——工作台归一面把它归一到严格格式。
     */
    @Test
    fun `anchor fullTimestamp prefixes bare month-day head with markedYear and passes year-form through`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        val year = java.time.LocalDate.now().year
        runner.blockAfterEmit = true
        runner.programmed = listOf("10-11 12:00:00.002  100  200 I Tag: bare-head")
        runner.stagedBatches = listOf(
            listOf("$year-10-11 12:00:00.004  100  200 I Tag: year-head"),
        )
        assertTrue(engine.start("adb logcat -v year", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(2_000) { engine.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { engine.window.first { it.totalCount == 1L } }

        // 裸月日形态：原文零触碰，完整串以 markedYear 补全年份
        val bare = engine.markAnchor()!!
        assertEquals("10-11 12:00:00.002", bare.timestamp, "timestamp = 行首原文（零触碰改写）")
        assertEquals(year, bare.markedYear, "markedYear = 标记时刻本机年")
        assertEquals("$year-10-11 12:00:00.002", bare.fullTimestamp, "完整串 = 标记年 + 裸月日原文")
        assertEquals("12:00:00", bare.clockText, "clockText 两形态同纳（裸形态）")

        runner.releaseNextBatch()
        withTimeout(5_000) { engine.window.first { it.totalCount == 2L } }

        // 年份形态（`-v year`）：完整串 = 原文透传
        val yearForm = engine.markAnchor()!!
        assertEquals("$year-10-11 12:00:00.004", yearForm.timestamp)
        assertEquals("$year-10-11 12:00:00.004", yearForm.fullTimestamp, "年份形态原文透传——不改写不重复拼年")
        assertEquals("12:00:00", yearForm.clockText, "clockText 两形态同纳（年份形态）")

        engine.stop()
        awaitStopped()
        Unit
    }

    @Test
    fun `markAnchor rejects outside running and when window has no timestamped line`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        assertNull(engine.markAnchor(), "Idle 态无进行中抓取——标记拒绝且零副作用")

        // Running 但窗内无行：无处可锚定（清屏后未再来行同理）
        runner.blockAfterEmit = true
        runner.programmed = emptyList()
        runner.stagedBatches = listOf(listOf("F/FatalTag( 300): crash"))
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(2_000) { engine.status.first { it is LogcatCaptureStatus.Running } }
        assertNull(engine.markAnchor(), "空滚动窗无带时间戳行可锚定——拒绝（宁拒不猜）")
        assertNull(engine.anchor.value, "拒绝零副作用（锚点不落）")

        // Running 且窗内只有无时间戳行（brief 形态）：仍拒绝——行内容不是查看器定位档
        runner.releaseNextBatch()
        withTimeout(5_000) { engine.window.first { it.totalCount == 1L } }
        assertNull(engine.markAnchor(), "无时间戳承载的行不锚定（查看器无行内容定位档）")

        engine.stop()
        awaitStopped()
        assertNull(engine.markAnchor(), "已停止后标记拒绝——终态窗口已定格，锚点只记运行中所见")
        Unit
    }

    // ── 武装捕获（特征正则命中自动标记）──────────────────────────

    /** 订阅武装命中事件并汇入 channel（等订阅挂上再返回——无 replay，漏订即漏事件） */
    private suspend fun collectArmedHitsInto(channel: Channel<LogcatArmedHit>) {
        val subscribed = CompletableDeferred<Unit>()
        scope.launch {
            engine.armedHit
                .onSubscription { subscribed.complete(Unit) }
                .collect { channel.trySend(it) }
        }
        withTimeout(5_000) { subscribed.await() }
    }

    @Test
    fun `armed capture marks anchor from hit line itself and disarms after first fire`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        val hitEvents = Channel<LogcatArmedHit>(Channel.UNLIMITED)
        collectArmedHitsInto(hitEvents)
        runner.blockAfterEmit = true
        runner.stagedBatches = listOf(
            listOf(
                "10-11 12:00:00.001  100  200 I Kept: noise",
                "10-11 12:00:05.123  100  200 E Crash: FATAL EXCEPTION",
                "10-11 12:00:06.456  100  200 E Crash: FATAL EXCEPTION twice",
            ),
        )
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(2_000) { engine.status.first { it is LogcatCaptureStatus.Running } }

        assertFalse(engine.armed.value, "前置：未武装等待态为 false")
        assertTrue(engine.arm(Regex("FATAL")), "Running 态武装须生效")
        assertTrue(engine.armed.value, "武装后等待态置位")
        runner.releaseNextBatch()

        val hit = withTimeout(5_000) { hitEvents.receive() }
        assertEquals(
            "10-11 12:00:05.123  100  200 E Crash: FATAL EXCEPTION",
            hit.line,
            "命中事件携带触发行原文",
        )
        assertEquals(
            "10-11 12:00:05.123",
            hit.anchor?.timestamp,
            "武装锚点 = 命中行自身行首时间戳（零滚动窗依赖，≠手动标记的屏上最新行语义）",
        )
        assertEquals("12:00:05", hit.anchor?.clockText, "回显时钟段与手动标记同派生")
        assertFalse(engine.armed.value, "命中即收网——首个命中后自动撤销武装")
        assertEquals(
            "10-11 12:00:05.123",
            engine.anchor.value?.timestamp,
            "锚点经 anchor StateFlow 发布（合一覆盖，查看/送工作台消费面就绪）",
        )

        // one-shot：后续同特征行不重复触发（等窗口提交落定后断言无新事件、锚点不变）
        withTimeout(5_000) { engine.window.first { it.totalCount == 3L } }
        assertTrue(hitEvents.tryReceive().isFailure, "one-shot：第二命中行不再触发事件")
        assertEquals(
            "10-11 12:00:05.123",
            engine.anchor.value?.timestamp,
            "锚点不被后续命中行覆盖（收网后武装已撤）",
        )

        engine.stop()
        awaitStopped()
        assertEquals(
            "10-11 12:00:05.123",
            engine.anchor.value?.timestamp,
            "武装锚点跨 Stopped 终态保留（与手动标记同契约）",
        )
        Unit
    }

    @Test
    fun `armed trap watches raw stream and never touches filter or disk semantics`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        val hitEvents = Channel<LogcatArmedHit>(Channel.UNLIMITED)
        collectArmedHitsInto(hitEvents)
        runner.blockAfterEmit = true
        runner.stagedBatches = listOf(
            listOf(
                "10-11 12:00:01.000  100  200 I Kept: normal",
                "10-11 12:00:02.000  100  200 I Other: TRAP found",
                "10-11 12:00:03.000  100  200 I Kept: TRAP in kept",
            ),
        )
        assertTrue(
            engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("kept", null))),
        )
        withTimeout(2_000) { engine.status.first { it is LogcatCaptureStatus.Running } }
        assertTrue(engine.arm(Regex("TRAP")))
        runner.releaseNextBatch()

        val hit = withTimeout(5_000) { hitEvents.receive() }
        assertEquals(
            "10-11 12:00:02.000",
            hit.anchor?.timestamp,
            "武装眼看过滤挡下的行（特征行不必挤进过滤条件才被埋伏）——锚点 = 被挡命中行自身时间戳",
        )
        assertFalse(engine.armed.value, "命中即收网")

        withTimeout(5_000) { engine.window.first { it.totalCount == 2L } }
        assertTrue(hitEvents.tryReceive().isFailure, "被挡 TRAP 行收网后，放行的 TRAP 行不重复触发")
        engine.stop()
        awaitStopped()

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
        assertEquals(2L, stopped.totalCount, "全量计数 = 过滤命中行数（武装不改变行去留）")
        assertEquals(
            listOf(
                "10-11 12:00:01.000  100  200 I Kept: normal",
                "10-11 12:00:03.000  100  200 I Kept: TRAP in kept",
            ),
            out.contentLines(),
            "武装零触碰落盘口径：产物只含过滤命中行，被挡的 TRAP 命中行不落盘",
        )
        assertEquals(
            listOf(
                "10-11 12:00:01.000  100  200 I Kept: normal",
                "10-11 12:00:03.000  100  200 I Kept: TRAP in kept",
            ),
            engine.window.value.lines,
            "滚动窗与落盘同源同口径（武装判定不进显示通道）",
        )
        Unit
    }

    @Test
    fun `disarm revokes the trap before any hit`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        val hitEvents = Channel<LogcatArmedHit>(Channel.UNLIMITED)
        collectArmedHitsInto(hitEvents)
        runner.blockAfterEmit = true
        runner.stagedBatches = listOf(
            listOf("10-11 12:00:01.000  100  200 I Kept: TRAP after disarm"),
        )
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(2_000) { engine.status.first { it is LogcatCaptureStatus.Running } }

        assertTrue(engine.arm(Regex("TRAP")))
        engine.disarm()
        assertFalse(engine.armed.value, "解除武装即回落等待态")
        engine.disarm()
        assertFalse(engine.armed.value, "重复解除幂等")

        runner.releaseNextBatch()
        withTimeout(5_000) { engine.window.first { it.totalCount == 1L } }
        assertTrue(hitEvents.tryReceive().isFailure, "解除后的命中行不触发事件")
        assertNull(engine.anchor.value, "解除后命中行不落锚点")
        engine.stop()
        awaitStopped()
        Unit
    }

    @Test
    fun `arm rejects outside running capture and disarm is a harmless no-op`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        assertFalse(engine.arm(Regex("TRAP")), "Idle 态武装拒绝（无进行中的抓取）")
        assertFalse(engine.armed.value, "拒绝零副作用（等待态不置位）")
        engine.disarm()
        assertFalse(engine.armed.value, "未武装解除 no-op 不置位")

        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Kept: only")
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        awaitStopped()
        assertFalse(
            engine.arm(Regex("TRAP")),
            "Stopped 终态武装拒绝——武装随抓取生命周期，不跨轮残留",
        )
        assertFalse(engine.armed.value, "终态撤销后等待态恒 false")

        // 下一轮 start 从零定型：武装不残留进新抓取（start 同步重置，返回即已成立）
        runner.programmed = listOf("10-11 13:00:00.001  100  200 I Kept: fresh")
        assertTrue(engine.start("adb logcat", tempOutFile(), LogcatLineMatcher(LogcatFilterSpec("", null))))
        assertFalse(engine.armed.value, "新抓取以未武装起步")
        engine.stop()
        awaitStopped()
        Unit
    }

    @Test
    fun `armed hit without timestamp head falls back to screen latest anchor`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        val hitEvents = Channel<LogcatArmedHit>(Channel.UNLIMITED)
        collectArmedHitsInto(hitEvents)
        runner.blockAfterEmit = true
        runner.stagedBatches = listOf(
            listOf("10-11 12:00:01.000  100  200 I Kept: before"),
            listOf("F/CrashTag( 300): FATAL without head"),
        )
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        withTimeout(2_000) { engine.status.first { it is LogcatCaptureStatus.Running } }
        assertTrue(engine.arm(Regex("FATAL")))

        runner.releaseNextBatch()
        withTimeout(5_000) { engine.window.first { it.totalCount == 1L } }
        runner.releaseNextBatch()

        val hit = withTimeout(5_000) { hitEvents.receive() }
        assertEquals("F/CrashTag( 300): FATAL without head", hit.line)
        assertEquals(
            "10-11 12:00:01.000",
            hit.anchor?.timestamp,
            "命中行无行首时间戳（brief 形态）退回 markAnchor 屏上语义——锚定窗内最新带时间戳行",
        )
        assertFalse(engine.armed.value, "退回路径同样收网")
        engine.stop()
        awaitStopped()
        Unit
    }

    // ── 过滤恒等快路径与折叠随窗发布 ───────────────────────────

    /**
     * 无原子规格（无过滤抓取的默认形态）的免 parse 快路径：命中条件 = 谓词对象
     * 同一性（[LogcatLineMatcher.matches] 对 MatchAll 恒等对象直接返回 true），
     * 恒真语义（含不可解析行）与逐行 parse 路径的零原子分支逐字等价；带原子规格
     * 不享快路径，不可解析行照旧拒收（宁拒不猜契约不回归）。
     */
    @Test
    fun `match all spec takes identity fast path and matches universally`() {
        val defaultSpec = LogcatFilterSpec("", null)
        assertTrue(defaultSpec.predicate === MatchAll, "双空规格编译为 MatchAll 恒等对象（快路径命中条件）")
        val matcher = LogcatLineMatcher(defaultSpec)
        assertTrue(matcher.matches("--------- beginning of main"), "恒真含不可解析行（快路径零依赖 parse）")
        assertTrue(matcher.matches("not a logcat line"), "任意原文恒命中")
        assertTrue(matcher.matches("10-11 12:00:00.001  100  200 I Tag: normal"), "threadtime 行照常命中")

        val parsedSpec = when (val r = LogcatQueryParser.parse("   ")) {
            is LogcatQueryParseResult.Ok -> r.spec
            is LogcatQueryParseResult.Err -> error("空白查询不应解析失败：${r.reason}")
        }
        assertTrue(parsedSpec.predicate === MatchAll, "空白查询裸串回退同编译为 MatchAll")

        val constrained = LogcatLineMatcher(LogcatFilterSpec("camera", null))
        assertFalse(constrained.matches("not a logcat line"), "带原子规格对不可解析行拒收（负对照）")
        assertTrue(constrained.matches("10-11 12:00:00.001  100  200 I Camera: open"), "可解析行照常按谓词判定")
    }

    /**
     * 折叠行组随每窗快照发布（收集线程增量维护）：每个已发布窗口的 rows 都与全量
     * 参考实现 foldConsoleRows(lines) 逐组一致——含溢出截头与尾段帧组跨批量提交
     * 成长的中间快照。
     */
    @Test
    fun `every published window snapshot carries folded rows equal to full refold of its lines`() = runBlocking {
        val engine = newEngine()
        val out = tempOutFile()
        val frames = (1..60).map { "    at com.demo.F$it(F.kt:$it)" }
        val head = (1..LogcatCaptureEngine.MAX_RING_LINES - 10).map {
            "10-11 12:00:00.000  100  200 I Tag: line-$it"
        }
        runner.programmed = head + frames
        val seen = java.util.concurrent.ConcurrentLinkedQueue<LogcatWindow>()
        val watchJob = scope.launch { engine.window.collect { seen.add(it) } }
        assertTrue(engine.start("adb logcat", out, LogcatLineMatcher(LogcatFilterSpec("", null))))
        awaitStopped()
        watchJob.cancel()

        assertTrue(seen.isNotEmpty(), "须发布过至少一个窗口快照")
        val finalWindow = engine.window.value
        assertTrue(finalWindow.lines.size == LogcatCaptureEngine.MAX_RING_LINES, "前置：规模越过滚动窗上限（溢出截头路径已走通）")
        seen.forEach { w ->
            assertEquals(foldConsoleRows(w.lines), w.rows, "随窗发布的折叠行组与全量参考实现一致（收集线程增量维护恒等）")
        }
        Unit
    }

    // ── 测试基建 ──────────────────────────────────────────────

    /** fake 运行器：发射预置行后按需阻塞至 stop()；launchFailed 模拟进程启动失败（null） */
    private class FakeRunner : LogcatStreamRunner {
        var programmed: List<String> = emptyList()
        var blockAfterEmit = false
        var launchFailed = false
        var receivedCommand: String? = null

        /**
         * 分批发射锚（中途换过滤的时序缝）：stagedBatches 每批发射前挂起等
         * releaseNextBatch 放行——测试在两缝之间换 matcher，后续批即按新过滤判定。
         */
        var stagedBatches: List<List<String>> = emptyList()
        private val batchGates = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

        /**
         * 每批发射完后的同步动作锚（清屏防复活用例）：发完即回调——此刻本批行
         * 必已全部入 channel（引擎先落盘后 send），清屏时在途与否由收集协程时序决定，
         * 两条路径的断言终态相同，用例无竞态。
         */
        var afterBatchAction: (suspend () -> Unit)? = null

        /** 放行下一分批（挂起至该批的门挂上：首批发完前调用会等门而非空放） */
        suspend fun releaseNextBatch() {
            batchGates.receive().complete(Unit)
        }

        /** run() 入口信号：引擎置 Running 与捕获协程首跑之间有窗口，断言命令须先等本信号 */
        val started = CompletableDeferred<Unit>()

        @Volatile private var stopRequested = false
        private var stopSignal = CompletableDeferred<Unit>()

        override suspend fun run(command: String, onLine: suspend (String) -> Unit): Int? {
            receivedCommand = command
            started.complete(Unit)
            programmed.forEach { onLine(it) }
            stagedBatches.forEach { batch ->
                val gate = CompletableDeferred<Unit>()
                batchGates.trySend(gate)
                gate.await()
                batch.forEach { onLine(it) }
                afterBatchAction?.invoke()
            }
            if (blockAfterEmit && !stopRequested) stopSignal.await()
            return if (launchFailed) null else 0
        }

        override fun stop() {
            // stop 可能先于 run 到达（Running 态在捕获协程首跑前即置位）——双通道均记录；
            // 分批门一并放行（stop 后测试不再发批，放行只为不挂死未消费的门）
            stopRequested = true
            while (true) {
                batchGates.tryReceive().getOrNull()?.complete(Unit) ?: break
            }
            stopSignal.complete(Unit)
        }
    }

    private fun tempOutFile(): File =
        Files.createTempDirectory("logcat-engine-test").resolve("capture-${System.nanoTime()}.txt").toFile()

    /** 行内容读取：按平台行分隔符拆行，去掉末行终止符产生的尾部空元素（行本体保留空行） */
    private fun File.contentLines(): List<String> {
        val text = readText()
        if (text.isEmpty()) return emptyList()
        return text.split(System.lineSeparator()).dropLast(1)
    }
}

/** Linux 句柄泄漏检查：/proc/self/fd 下不得再有指向 [file] 的打开句柄（非 Linux 平台跳过） */
private fun assertNoOpenFd(file: File, message: String) {
    val fdDir = File("/proc/self/fd")
    if (!fdDir.isDirectory) return
    val target = file.absolutePath
    val leaked = fdDir.listFiles()
        .orEmpty()
        .mapNotNull { runCatching { it.canonicalFile.absolutePath }.getOrNull() }
        .any { it == target }
    assertTrue(!leaked, "$message：/proc/self/fd 仍持有 $target")
}
