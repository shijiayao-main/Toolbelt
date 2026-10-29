package com.jiaoay.easy_dev_toolbox.page.deviceobs

import com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle
import com.jiaoay.easy_dev_toolbox.arch.assertBandsNotOverlapping
import com.jiaoay.easy_dev_toolbox.arch.assertBandsSeparated
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.countColor
import com.jiaoay.easy_dev_toolbox.arch.isContentPx
import com.jiaoay.easy_dev_toolbox.arch.probeSchemeColor
import com.jiaoay.easy_dev_toolbox.arch.renderPixels
import com.jiaoay.toolbelt.device.AdbProcessRunner
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.device.DevicePerfSample
import com.jiaoay.toolbelt.device.MemoryStats
import com.jiaoay.toolbelt.device.PerfField
import com.jiaoay.toolbelt.device.PerfSampler
import com.jiaoay.toolbelt.device.PerfSessionState
import com.jiaoay.toolbelt.device.ProcessEntry
import com.jiaoay.toolbelt.device.ShellResult
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import androidx.compose.material3.MaterialTheme
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import com.jiaoay.toolbelt.adb.AdbEndpoint

/**
 * 性能监控面板场景渲染像素带守护：[SceneGuardSupport] 单源配方
 * 扩到本面板。状态注入走生产链——真实 [PerfSampler] +
 * fake gateway（真解析管线全跑通，免真 adb），等历史点 ≥3 再渲染，面板展开态
 * 呈现的布局/曲线/排行表进像素面。
 *
 * 验证面（有会话两态的面板回归网）：
 * - 内容带互斥：头部行/读数带/曲线带/排行表各带间背景间隙 > 3px（叠放/粘连即红）；
 * - 曲线带在场且非空壳：≥80px 高的带内有折线内容像素（CPU/内存/FPS 三曲线进渲染面，
 *   chartPolylineSegments → Canvas 链断裂即红）；
 * - 排行表带非空壳：进程行文字进渲染面。
 *
 * 验证面（导出双动作位 + 无数据缺席）：
 * - 运行态导出动作行作为独立内容带在场，清空曲线（无数据）后随曲线内容同帧退场
 *   ——带数与 primary 着色双降（CPU 曲线是 primary 主增量源）；
 * - 停止态导出钮与开始采样钮同行（带数不变），以动作行内容像素差分计钮在场
 *   （清空后「保留中」回执与导出/送飞钮整面退场；钮文本着色不落在 primary
 *   精确色上，内容像素差分不依赖钮配色）。
 *
 * 验证面（多会话视图）：
 * - 双 serial 图例行 + 双语义色曲线进渲染面（色板槽位差分计数——图例色标与曲线
 *   共用 perfColorSlot，逐台对色）；
 * - 收束单台差分：停掉第二台后该台色随图例/曲线整体退出渲染面（单会话视图不残留
 *   第二台色）。
 *
 * 无会话且无残留曲线的纯提示态（hint/banner 文案与门控）由映射纯函数单测与面板
 * 结构钉文案，场景层不为它单设渲染用例（像素带边际价值低）；带残留曲线的停止态
 * 因导出动作位入本类场景网。
 */
class PerfPanelSceneGuardTest {

    private val homeIsolation = UserHomeIsolation("edt-perf-scene")
    private val createdScopes = mutableListOf<CoroutineScope>()

    /** /proc/stat 读序号：相邻读取 Δtotal=300、Δidle=100 → busy ≈ 66.7%（中线可辨） */
    private val statReads = AtomicLong(0)

    private val gateway = AdbProcessRunner { command ->
        Result.success(route(command))
    }

    private fun route(command: String): ShellResult {
        val n = statReads.getAndIncrement()
        val stat = "cpu  ${1000 + 200 * n} 0 0 ${2000 + 100 * n} 0 0 0 0 0 0\ncpu0 1 0 0 1 0 0 0 0 0 0"
        return when {
            command.contains("dumpsys power") -> ShellResult(0, "mBatteryLevel=100\n", "")
            // 组合命令段标记 = :core PerfParsers 的段落框架契约字面值（internal 不跨模块，
            // 改标记须同步镜像）
            command.contains("@@PERF_MEM@@") -> ShellResult(
                0,
                stat + "\n@@PERF_MEM@@\n" +
                    "MemTotal:       13797032 kB\nMemAvailable:    6521736 kB\nMemFree:         3965080 kB\n" +
                    "@@PERF_THERMAL@@\n" +
                    "Current temperatures from HAL:\nTemperature{mValue=30.8, mType=3, mName=skin, mStatus=0}\n" +
                    "@@PERF_BATTERY@@\nCan't find service: battery\n" +
                    "@@PERF_TOP@@\n" +
                    "  PID USER      PR  NI VIRT RES SHR S[%CPU] %MEM TIME+ ARGS\n" +
                    "13856 system      20   0 7.1G 266M 136M S 61.2   1.9 170:55.54 com.demo.oobe\n" +
                    " 2810 system      20   0  18G 803M 594M S 32.2   5.9  76:49.57 com.demo.carspeech\n",
                "",
            )

            else -> ShellResult(0, stat, "")
        }
    }

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
    }

    @AfterTest
    fun tearDown() {
        createdScopes.forEach { it.cancel() }
        homeIsolation.restoreOnly()
    }

    /** 带内内容像素计数（单源 [isContentPx] 判定；bandContentPx 属本面板私有几何工具非单源配方） */
    private fun bandContentPx(pixels: Array<IntArray>, band: IntRange): Int {
        var cnt = 0
        for (y in band) {
            for (x in pixels[y].indices) {
                if (isContentPx(pixels[y][x])) cnt++
            }
        }
        return cnt
    }

    /** 全帧内容像素计数（停止态动作行的钮/回执差分锚——不依赖钮配色） */
    private fun frameContentPx(pixels: Array<IntArray>): Int {
        var cnt = 0
        for (row in pixels) {
            for (p in row) {
                if (isContentPx(p)) cnt++
            }
        }
        return cnt
    }

    @Test
    fun `active session renders readings chart and process table bands`() {
        // 设备域单源：fake 网关 + 注入缝置单设备（门控默认关——不 spawn 轮询）
        val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it }
        val monitor = DeviceMonitor(
            scope = monitorScope,
            runner = { Result.success(ShellResult(0, "", "")) },

            endpointReader = { AdbEndpoint("") },
        )
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "Demo-1")))

        // 采样器：60s 曲线档（容量 30 满环全宽折线）真跑生产链开首拍成「有会话」态，
        // 历史体量经 applySample 注入缝预置（会话节奏不等 40 拍真实时钟）
        val samplerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it }
        val sampler = PerfSampler(
            scope = samplerScope,
            devices = monitor,
            runner = gateway,
            endpointReader = { AdbEndpoint("") },
            sampleIntervalMs = 60_000L,
            cpuWindowMs = 40L,
            initialPageVisible = true,
        )
        sampler.startSampling()
        runBlocking {
            withTimeout(10_000) { sampler.state.first { it.latest != null } }
        }
        val okMem = com.jiaoay.toolbelt.device.MemoryStats(
            totalKb = 13_797_032,
            availableKb = 6_521_736,
            freeKb = 3_965_080,
        )
        val procSample = com.jiaoay.toolbelt.device.ProcessEntry(
            pid = 13856,
            user = "system",
            cpuPercent = 61.2f,
            memPercent = 1.9f,
            rssKb = 266L * 1024,
            name = "com.demo.oobe",
        )
        repeat(40) { i ->
            sampler.applySample(
                com.jiaoay.toolbelt.device.DevicePerfSample(
                    timestampMs = i * 60_000L,
                    cpuPercent = PerfField.ok(25f + (i % 5) * 15f),
                    memory = PerfField.ok(okMem),
                    temperatureC = PerfField.ok(30.8f),
                    batteryPercent = PerfField.ok(100),
                    processes = PerfField.ok(listOf(procSample)),
                    // fps 恒值低档：点列恒贴图表底缘一带——多档散点会拆碎曲线带间隙
                    // （带间隙 ≥80px 的图表在场断言以单条带为前提）
                    fps = PerfField.ok(10f),
                ),
            )
        }

        val pixels = renderPixels(SCENE_W, SCENE_H) {
            PerfPanel(devices = monitor, sampler = sampler)
        }
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(
            bands.size >= 4,
            "有会话展开态应渲染 ≥4 内容带（头部/读数/曲线/排行表），实际 ${bands.size}：$bands" +
                "——自动展开链断裂或内容未进渲染面",
        )
        assertBandsSeparated(bands)

        // 曲线带：细折线像素稀疏不构成「行带」，改几何定位——两内容带间最大纵向
        // 间隙即图表画布区（chartHeight=120 档），区内须有折线内容像素（空壳即红）
        var chartGap = -1
        var chartRegion = 0..0
        for (i in 0 until bands.size - 1) {
            val gap = bands[i + 1].first - bands[i].last - 1
            if (gap > chartGap) {
                chartGap = gap
                chartRegion = (bands[i].last + 1)..(bands[i + 1].first - 1)
            }
        }
        assertTrue(
            chartGap >= 80,
            "内容带间最大间隙 $chartGap < 80——图表画布未进渲染面（bands=$bands）",
        )
        val chartPx = bandContentPx(pixels, chartRegion)
        assertTrue(
            chartPx >= 60,
            "图表区内内容像素 $chartPx < 60——折线未进渲染面（映射或 Canvas 链断裂，region=$chartRegion）",
        )

        // 排行表带：末带须有进程行文字（两行真名进程行进渲染面）
        val lastBand = bands.last()
        val tablePx = bandContentPx(pixels, lastBand)
        assertTrue(
            tablePx >= 40,
            "排行表带内容像素 $tablePx < 40——进程行未进渲染面（band=$lastBand）",
        )
    }

    // ── 导出双动作位 / 多会话视图（共用注入缝底座） ─────────────────────

    /** 单台设备监视器（fake 网关回空——采样环零真实提交，状态全走 applySample 注入缝） */
    private fun monitorOf(vararg devices: DeviceInfo): DeviceMonitor {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it }
        val monitor = DeviceMonitor(
            scope = scope,
            runner = { Result.success(ShellResult(0, "", "")) },

            endpointReader = { AdbEndpoint("") },
        )
        monitor.applyDeviceList(devices.toList())
        return monitor
    }

    /**
     * 页面可见性门控恒关的采样器（帧间零真实采样提交——帧间差分只计被控变量的变化）；
     * 会话态由 startSampling/startSamplingToTargets 起真会话承载。
     */
    private fun gatedSampler(monitor: DeviceMonitor): PerfSampler =
        PerfSampler(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it },
            devices = monitor,
            runner = gateway,
            endpointReader = { AdbEndpoint("") },
            sampleIntervalMs = 60_000L,
            cpuWindowMs = 40L,
            initialPageVisible = false,
        )

    /**
     * 注入样本：CPU/内存双折线取陡斜率（相邻拍纵向落差 ≥10px/36px 步长——折线不成
     * 行带，帧间带数差分不被折线形态污染）；FPS 恒 0 贴图表底缘（点列与基准线同行
     * 成带，清空曲线后带形不变）。
     */
    private fun injectedSample(i: Int, cpu: Float): DevicePerfSample =
        DevicePerfSample(
            timestampMs = i * 60_000L,
            cpuPercent = PerfField.ok(cpu),
            memory = PerfField.ok(
                MemoryStats(
                    totalKb = 13_797_032,
                    availableKb = 12_500_000L - i * 1_400_000L,
                    freeKb = 3_965_080,
                ),
            ),
            temperatureC = PerfField.ok(30.8f),
            batteryPercent = PerfField.ok(100),
            processes = PerfField.ok(
                listOf(ProcessEntry(13856, "system", 61.2f, 1.9f, 266L * 1024, "com.demo.oobe")),
            ),
            fps = PerfField.ok(0f),
        )

    /**
     * 导出双动作位 + 无数据整行缺席（同一存续场景多帧差分）：
     * - 运行态：导出动作行作为独立内容带在场——清空曲线（无数据）后随曲线内容
     *   同帧退场（带数与 primary 着色双降）；
     * - 停止态：残留曲线下停止采样，展开态存续（同一场景组合态，expanded 不复位），
     *   导出钮与开始采样钮同行——动作行内容像素差分计钮（不依赖钮配色）。
     */
    @Test
    fun `export actions render in running and stopped states and drop entirely without data`() {
        val monitor = monitorOf(DeviceInfo("S1", "Demo-1"))
        val sampler = gatedSampler(monitor)
        sampler.startSampling()
        repeat(8) { i -> sampler.applySample("S1", injectedSample(i, 15f + i * 10f)) }
        assertTrue(sampler.state.value.session is PerfSessionState.Active, "前置：会话 Active（运行态视图）")
        assertTrue(perfHistoryHasPoints(sampler.state.value), "前置：保留历史有采样点（导出门开）")

        val primaryColor = probeSchemeColor { MaterialTheme.colorScheme.primary }
        SceneGuardHandle(SCENE_W, SCENE_H).use { handle ->
            handle.setContent { PerfPanel(devices = monitor, sampler = sampler) }

            // 运行态（有数据）：导出动作行作为独立内容带在场
            val runningPixels = handle.renderPixels()
            val runningBands = contentRows(runningPixels, SCENE_W)
            assertBandsNotOverlapping(runningBands)
            assertTrue(
                runningBands.size >= 8,
                "运行态应渲染 ≥8 内容带（头部/停止动作行/导出行/读数两行/图表基准线/图例行/排行表头/进程行），" +
                    "实际 ${runningBands.size}：$runningBands",
            )

            // 无数据缺席（运行态）：清空曲线后导出行与曲线内容退场——带数下降 +
            // primary 着色差分（CPU 曲线是 primary 主增量源；导出行同帧退场）
            sampler.clearHistory()
            val clearedPixels = handle.renderPixels()
            val clearedBands = contentRows(clearedPixels, SCENE_W)
            assertTrue(
                clearedBands.size < runningBands.size,
                "清空曲线后带数应少于清空前（导出行与曲线内容退场）——清空前 ${runningBands.size} 带、" +
                    "清空后 ${clearedBands.size} 带",
            )
            assertTrue(
                countColor(runningPixels, primaryColor) >=
                    countColor(clearedPixels, primaryColor) + EXPORT_BUTTON_PRIMARY_GAIN_MIN,
                "运行态清空前应多出 primary 着色内容像素（CPU 曲线 + 导出行同帧退场，" +
                    "差分 < $EXPORT_BUTTON_PRIMARY_GAIN_MIN 即有数据呈现面缺席）——" +
                    "有数据 ${countColor(runningPixels, primaryColor)} px / 无数据 ${countColor(clearedPixels, primaryColor)} px",
            )

            // 停止态（残留曲线）：停止采样后展开态与导出动作位存续
            repeat(8) { i -> sampler.applySample("S1", injectedSample(i, 15f + i * 10f)) }
            sampler.stopSampling()
            val stoppedPixels = handle.renderPixels()
            val stoppedBands = contentRows(stoppedPixels, SCENE_W)
            assertBandsNotOverlapping(stoppedBands)
            assertTrue(
                stoppedBands.size >= 2,
                "停止态应渲染 ≥2 内容带（头部/开始采样动作行），实际 ${stoppedBands.size}：$stoppedBands",
            )
            // 停止态导出钮差分：清空后「保留中」回执与导出/送飞钮整面退场——钮文本着色
            // 不落在 primary 精确色上（实测停止态着色差分恒零），动作行内容像素差分
            // 不依赖钮配色
            sampler.clearHistory()
            val stoppedClearedPixels = handle.renderPixels()
            val stoppedClearedBands = contentRows(stoppedClearedPixels, SCENE_W)
            assertBandsNotOverlapping(stoppedClearedBands)
            assertTrue(
                frameContentPx(stoppedPixels) >=
                    frameContentPx(stoppedClearedPixels) + EXPORT_ROW_CONTENT_GAIN_MIN,
                "停止态清空前应多出残留曲线呈现面内容像素（保留中回执 + 导出钮行，差分 < " +
                    "$EXPORT_ROW_CONTENT_GAIN_MIN 即导出位缺席）——" +
                    "有数据 ${frameContentPx(stoppedPixels)} px / 无数据 ${frameContentPx(stoppedClearedPixels)} px",
            )
        }
    }

    /**
     * 多会话视图（双 serial 并行）：每台一条图例行（色标 + 尾号 + 五域读数）+ 多曲线
     * 画布。差分锚：
     * - 双色板槽位差分计数——槽位 0/1 两色都进渲染面（图例色标与曲线共用 perfColorSlot，
     *   任一台图例行/曲线缺席即该色计数见底）；
     * - 多曲线画布非空壳：最大带间隙即画布区，区内须有内容像素（multiChartCurves →
     *   Canvas 链断裂即红）；
     * - 收束单台差分：停掉第二台后多会话视图退场，该台色随图例/曲线整体退出渲染面
     *   （单会话视图不残留第二台色）。
     */
    @Test
    fun `multi serial sessions render per serial legends and dual palette curves`() {
        val monitor = monitorOf(DeviceInfo("aaa11111", "Demo-1"), DeviceInfo("bbb22222", "Demo-1"))
        val sampler = gatedSampler(monitor)
        sampler.startSamplingToTargets(listOf("aaa11111", "bbb22222"))
        repeat(6) { i ->
            sampler.applySample("aaa11111", injectedSample(i, 10f + i * 10f))
            sampler.applySample("bbb22222", injectedSample(i, 90f - i * 10f))
        }
        assertEquals(
            2,
            sampler.state.value.sessions.values.count { it is PerfSessionState.Active },
            "前置：双会话 Active（多会话视图门控）",
        )

        val paletteFirst = probeSchemeColor { LocalEasyToolboxColors.current.chartPalette[0] }
        val paletteSecond = probeSchemeColor { LocalEasyToolboxColors.current.chartPalette[1] }

        SceneGuardHandle(SCENE_W, SCENE_H).use { handle ->
            handle.setContent { PerfPanel(devices = monitor, sampler = sampler) }
            val multiPixels = handle.renderPixels()
            val multiBands = contentRows(multiPixels, SCENE_W)
            assertBandsNotOverlapping(multiBands)
            assertTrue(
                multiBands.size >= 7,
                "多会话视图应渲染 ≥7 内容带（头部/多设备提示/停止动作行/导出行/两台图例行/图表基准线/曲线图例行），" +
                    "实际 ${multiBands.size}：$multiBands",
            )
            assertTrue(
                countColor(multiPixels, paletteFirst) >= LEGEND_SWATCH_COLOR_MIN,
                "第一台（槽位 0）图例色标+曲线应进渲染面（差分计数 < $LEGEND_SWATCH_COLOR_MIN 即该台图例行缺席）",
            )
            assertTrue(
                countColor(multiPixels, paletteSecond) >= LEGEND_SWATCH_COLOR_MIN,
                "第二台（槽位 1）图例色标+曲线应进渲染面（差分计数 < $LEGEND_SWATCH_COLOR_MIN 即该台图例行缺席）",
            )

            // 多曲线画布非空壳：两内容带间最大纵向间隙即画布区，区内须有内容像素
            var chartGap = -1
            var chartRegion = 0..0
            for (i in 0 until multiBands.size - 1) {
                val gap = multiBands[i + 1].first - multiBands[i].last - 1
                if (gap > chartGap) {
                    chartGap = gap
                    chartRegion = (multiBands[i].last + 1)..(multiBands[i + 1].first - 1)
                }
            }
            assertTrue(chartGap >= 80, "内容带间最大间隙 $chartGap < 80——多曲线画布未进渲染面（bands=$multiBands）")
            val chartPx = bandContentPx(multiPixels, chartRegion)
            assertTrue(
                chartPx >= 60,
                "多曲线画布区内内容像素 $chartPx < 60——曲线未进渲染面（multiChartCurves 或 Canvas 链断裂，region=$chartRegion）",
            )

            // 收束单台差分：停掉第二台后槽位 1 色应整体退场（图例色标 + 曲线不残留）
            sampler.stopSampling("bbb22222")
            val singlePixels = handle.renderPixels()
            assertEquals(
                0,
                countColor(singlePixels, paletteSecond),
                "收掉第二台后槽位 1 色应退出渲染面（单会话视图仅 scheme 三色）——色标或曲线残留即红",
            )
        }
    }

    companion object {
        private const val SCENE_W = 1096
        private const val SCENE_H = 900

        /** 运行态清空差分（CPU 曲线 primary 着色 + 导出行）的计数下限 */
        private const val EXPORT_BUTTON_PRIMARY_GAIN_MIN = 10

        /** 停止态动作行内容像素差分下限（保留中回执 + 导出/送飞钮整面；钮描边/图标/文本远超此值） */
        private const val EXPORT_ROW_CONTENT_GAIN_MIN = 400

        /** 图例色标实色块（iconSmall 见方，去圆角）+ 曲线着色的双槽位差分计数下限 */
        private const val LEGEND_SWATCH_COLOR_MIN = 60
    }
}
