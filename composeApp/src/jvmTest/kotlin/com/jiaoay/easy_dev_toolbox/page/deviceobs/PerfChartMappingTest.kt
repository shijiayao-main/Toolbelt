package com.jiaoay.easy_dev_toolbox.page.deviceobs

import com.jiaoay.toolbelt.device.DevicePerfSample
import com.jiaoay.toolbelt.device.MemoryStats
import com.jiaoay.toolbelt.device.PerfField
import com.jiaoay.toolbelt.device.ProcessEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 性能面板显示纯函数层守护：折线映射（磁带式时间轴/洞断段/空数据边界）与
 * 进程排行显示（排序/前 N/文本）的零渲染单测——Canvas 只消费映射结果，
 * 映射回归即曲线画错。
 */
class PerfChartMappingTest {

    // ── 折线映射 ─────────────────────────────────────────────────────

    @Test
    fun `latest point pins to right edge with capacity slots`() {
        // 容量 4、宽 300 → 步长 100；两点：旧点 x=200、新点 x=300（贴右缘）
        val segments = chartPolylineSegments(listOf(0f, 100f), width = 300f, height = 100f, capacity = 4)
        assertEquals(1, segments.size)
        val seg = segments[0]
        assertEquals(2, seg.size)
        assertEquals(200f, seg[0].first, 0.001f)
        assertEquals(300f, seg[1].first, 0.001f)
        // y 轴：0 → 底缘、100 → 顶缘
        assertEquals(100f, seg[0].second, 0.001f)
        assertEquals(0f, seg[1].second, 0.001f)
    }

    @Test
    fun `single point becomes dot segment at right edge`() {
        val segments = chartPolylineSegments(listOf(50f), width = 300f, height = 100f, capacity = 4)
        assertEquals(1, segments.size)
        assertEquals(1, segments[0].size)
        assertEquals(300f, segments[0][0].first, 0.001f)
        assertEquals(50f, segments[0][0].second, 0.001f)
    }

    @Test
    fun `null hole splits segments without fake zero`() {
        // 洞（采样失败拍）断线成段，不落 0 点、不跨洞连线
        val segments = chartPolylineSegments(
            listOf(10f, null, 30f, 40f),
            width = 300f,
            height = 100f,
            capacity = 4,
        )
        assertEquals(2, segments.size)
        assertEquals(1, segments[0].size)
        assertEquals(2, segments[1].size)
        // 洞后两点仍在自己的容量槽上（容量 4 = 4 点满窗：i=2 → x=300-1*100）
        assertEquals(200f, segments[1][0].first, 0.001f)
        assertEquals(300f, segments[1][1].first, 0.001f)
        // 首部洞不留空段
        val leading = chartPolylineSegments(listOf(null, null, 30f), 300f, 100f, 4)
        assertEquals(1, leading.size)
    }

    @Test
    fun `empty and degenerate inputs map to no segments`() {
        assertTrue(chartPolylineSegments(emptyList(), 300f, 100f, 4).isEmpty())
        assertTrue(chartPolylineSegments(listOf(null, null), 300f, 100f, 4).isEmpty())
        assertTrue(chartPolylineSegments(listOf(50f), 0f, 100f, 4).isEmpty(), "零宽画布无段")
        assertTrue(chartPolylineSegments(listOf(50f), 300f, 0f, 4).isEmpty(), "零高画布无段")
        assertTrue(chartPolylineSegments(listOf(50f), 300f, 100f, 1).isEmpty(), "容量 <2 无槽轴")
    }

    @Test
    fun `out of range values are clamped to 0-100 band`() {
        val segments = chartPolylineSegments(listOf(150f, -20f), 300f, 100f, 2)
        assertEquals(0f, segments[0][0].second, 0.001f, "超上界钳到顶缘")
        assertEquals(100f, segments[0][1].second, 0.001f, "负值钳到底缘")
    }

    // ── 多曲线形态（多会话：每 serial 一色，序列间独立映射） ───────────

    private fun series(
        serial: String,
        vararg values: Float?,
        mem: List<Float?>? = null,
        fps: List<Float?> = emptyList(),
    ) = PerfCurveSeries(
        serial = serial,
        cpu = values.toList(),
        mem = mem ?: values.map { it?.let { v -> (v / 2f) } },
        fps = fps,
        capacity = values.size.coerceAtLeast(1),
    )

    @Test
    fun `multi chart maps each serial independently with its own holes`() {
        // 两 serial 各自独立映射：A 有洞断段、B 连续单段——序列间互不影响
        val a = series("A", 10f, null, 30f, 40f, mem = listOf(10f, 20f, null, 40f))
        val b = series("B", 5f, 15f, 25f, 35f)
        val curves = multiChartCurves(listOf(a, b), width = 300f, height = 100f)
        assertEquals(2, curves.size)

        val aCurve = curves.single { it.serial == "A" }
        assertEquals(2, aCurve.cpuSegments.size, "A 的 CPU 序列在洞处断段（序列内洞语义与单序列一致）")
        assertEquals(2, aCurve.memSegments.size, "A 的内存序列按自己的洞独立断段（不与 CPU 洞联动）")

        val bCurve = curves.single { it.serial == "B" }
        assertEquals(1, bCurve.cpuSegments.size, "B 无洞不串扰 A 的断段语义")
        assertEquals(4, bCurve.cpuSegments[0].size)
        // 各序列最新点各自贴右缘（磁带时间轴按序列容量独立定槽）
        assertEquals(300f, bCurve.cpuSegments[0].last().first, 0.001f)
        assertEquals(300f, aCurve.cpuSegments.last().last().first, 0.001f)
    }

    @Test
    fun `color slots are sorted-stable regardless of input order`() {
        val serials = listOf("S2", "S1", "S3")
        assertEquals(0, perfColorSlot(serials, "S1"))
        assertEquals(1, perfColorSlot(serials, "S2"))
        assertEquals(2, perfColorSlot(serials, "S3"))
        // 输入顺序变化不漂移槽位（图例与曲线逐台对色的稳定前提）
        assertEquals(1, perfColorSlot(listOf("S3", "S1", "S2"), "S2"))
        // 批外 serial 不产负下标（防越界；批外 serial 由调用面保证不出现）
        assertEquals(0, perfColorSlot(listOf("S1"), "S9"))
    }

    @Test
    fun `multi chart maps fps series with its own holes independent of cpu and mem`() {
        // fps 序列的洞与 CPU/内存序列互不联动：fps 有两洞断三段，CPU 单段照常
        val a = series(
            "A", 10f, 20f, 30f, 40f, 50f, 60f,
            fps = listOf(60f, null, 58f, null, 57f, 56f),
        )
        val curves = multiChartCurves(listOf(a), width = 300f, height = 100f)
        val aCurve = curves.single()
        assertEquals(1, aCurve.cpuSegments.size, "CPU 序列无洞不联动 fps 的洞")
        assertEquals(3, aCurve.fpsSegments.size, "fps 序列按自己的洞独立断段")
        assertEquals(2, aCurve.fpsSegments[2].size, "末段跨连续两点（57、56）")
        // fps 值域同样进 0–100 带（>100 的高刷读数按顶缘呈现）
        assertEquals(40f, aCurve.fpsSegments[0][0].second, 0.001f, "60fps → 60% 带位")
        val capped = multiChartCurves(
            listOf(series("B", 1f, 2f, fps = listOf(120f, 119f))),
            width = 300f,
            height = 100f,
        ).single()
        assertEquals(0f, capped.fpsSegments[0][0].second, 0.001f, "超带上缘钳顶缘（120fps → 100% 带位）")
    }

    @Test
    fun `serial tail label keeps short serials whole and truncates long ones`() {
        assertEquals("S1", serialTailLabel("S1"))
        assertEquals("ABCDEF", serialTailLabel("ABCDEF"))
        assertEquals("…23CDEF", serialTailLabel("ABCDEF0123CDEF"))
        assertEquals("…2458H9", serialTailLabel("AYK02183B02458H9"))
    }

    // ── 进程排行 ─────────────────────────────────────────────────────

    private fun entry(pid: Int, cpu: Float, rssKb: Long) = ProcessEntry(
        pid = pid,
        user = "system",
        cpuPercent = cpu,
        memPercent = 0f,
        rssKb = rssKb,
        name = "proc$pid",
    )

    @Test
    fun `sortProcesses ranks by cpu or rss`() {
        val entries = listOf(entry(1, cpu = 10f, rssKb = 500), entry(2, cpu = 80f, rssKb = 100))
        assertEquals(listOf(2, 1), sortProcesses(entries, byCpu = true).map { it.pid })
        assertEquals(listOf(1, 2), sortProcesses(entries, byCpu = false).map { it.pid })
    }

    @Test
    fun `rss human text folds by 1024 scale`() {
        assertEquals("266.0M", rssHumanText(266L * 1024))
        assertEquals("1.2G", rssHumanText((1.2 * 1024 * 1024).toLong()))
        assertEquals("512K", rssHumanText(512))
        assertEquals("0K", rssHumanText(0))
    }

    @Test
    fun `process row text aligns columns`() {
        val text = processRowText(entry(13856, cpu = 61.2f, rssKb = 266L * 1024))
        assertTrue(text.contains("13856"), "含 pid（实际：$text）")
        assertTrue(text.contains("61.2%"), "含 CPU%（实际：$text）")
        assertTrue(text.contains("266.0M"), "含 RSS（实际：$text）")
        assertTrue(text.endsWith("proc13856"), "名字收尾（实际：$text）")
    }

    // ── 读数与失败明细文本 ───────────────────────────────────────────

    @Test
    fun `reading texts show value or dash and explicit errors`() {
        assertEquals("66.7%", cpuValueText(PerfField.ok(66.66f)))
        assertEquals("—", cpuValueText(PerfField.fail("坏输出")))
        assertEquals("30.8°C", tempValueText(PerfField.ok(30.8f)))
        assertEquals("100%", batteryValueText(PerfField.ok(100)))
        assertEquals("—", batteryValueText(PerfField.fail("服务缺席")))
        assertEquals("58.7", fpsValueText(PerfField.ok(58.7f)))
        assertEquals("0.0", fpsValueText(PerfField.ok(0f)), "静屏 0 帧率是合法读数显示 0.0")
        assertEquals("—", fpsValueText(PerfField.fail("未见 flips 行")))
        val mem = MemoryStats(totalKb = 13_797_032, availableKb = 6_521_736, freeKb = 0)
        assertEquals("52.7%", memValueText(PerfField.ok(mem)))
        assertTrue(memDetailText(PerfField.ok(mem)).contains("总 13.2G"))
    }

    @Test
    fun `errors line joins failed domains and hides when all ok`() {
        val ok = DevicePerfSample(
            timestampMs = 0,
            cpuPercent = PerfField.ok(1f),
            memory = PerfField.ok(MemoryStats(100, 50, 10)),
            temperatureC = PerfField.ok(30f),
            batteryPercent = PerfField.ok(100),
            processes = PerfField.ok(emptyList()),
            fps = PerfField.ok(60f),
        )
        assertNull(perfErrorsLine(ok), "全成功不呈现明细行")

        val partial = ok.copy(
            cpuPercent = PerfField.fail("cpu 计数差值非正"),
            batteryPercent = PerfField.fail("未见 mBatteryLevel 行"),
            fps = PerfField.fail("未见 flips 行（SurfaceFlinger 输出格式不符）"),
        )
        val line = perfErrorsLine(partial)!!
        assertTrue(line.contains("CPU：cpu 计数差值非正"), "逐域人话（实际：$line）")
        assertTrue(line.contains("电池：未见 mBatteryLevel 行"), "逐域人话（实际：$line）")
        assertTrue(line.contains("FPS：未见 flips 行"), "fps 域失败进明细行（实际：$line）")
    }

    // ── 面板提示 ─────────────────────────────────────────────────────

    @Test
    fun `panel hint covers three no-target shapes`() {
        val none = perfPanelHint(
            com.jiaoay.toolbelt.device.DeviceState.None,
            detectError = null,
            selectedSerial = null,
        )
        assertTrue(none!!.message.contains("未检测到设备"))

        val failed = perfPanelHint(
            com.jiaoay.toolbelt.device.DeviceState.None,
            detectError = "adb 缺席",
            selectedSerial = null,
        )
        assertEquals(PerfPanelHintAction.GO_SETTINGS, failed!!.action)

        val multi = perfPanelHint(
            com.jiaoay.toolbelt.device.DeviceState.Multi(
                listOf(
                    com.jiaoay.toolbelt.device.DeviceInfo("S1", "A"),
                    com.jiaoay.toolbelt.device.DeviceInfo("S2", "B"),
                ),
            ),
            detectError = null,
            selectedSerial = null,
        )
        assertEquals(PerfPanelHintAction.SELECT_DEVICE, multi!!.action)

        assertNull(
            perfPanelHint(
                com.jiaoay.toolbelt.device.DeviceState.Single("S1", "Demo-1"),
                detectError = null,
                selectedSerial = null,
            ),
            "单设备/已选定不给提示",
        )
    }
}
