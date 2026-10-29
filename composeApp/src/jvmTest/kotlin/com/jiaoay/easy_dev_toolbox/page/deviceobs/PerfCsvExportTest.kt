package com.jiaoay.easy_dev_toolbox.page.deviceobs

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.jiaoay.toolbelt.device.PerfHistoryPoint
import com.jiaoay.toolbelt.device.PerfHistorySnapshot
import com.jiaoay.toolbelt.device.PerfSamplerState

/**
 * 曲线导出动作面守护（纯 JVM 直测，免场景渲染）：
 * - 写盘链端到端：PerfSamplerState → CSV 文本 → `~/Documents/Toolbelt/perf/` 产物落位，
 *   逐行断言头行/注记行/空字段行（null 域不伪装 0）；
 * - 失败人话：空数据集拒绝、产物目录不可建拒绝（回执不静默）；
 * - 门控判据：导出数据判据（跨会话保留历史）与「送飞书」置灰判据；
 * - 回执文本：成功带完整路径、失败人话归一。
 */
class PerfCsvExportTest {

    private fun point(ts: Long, cpu: Float? = 12.5f, mem: Float? = 63.4f, fps: Float? = 59.9f) =
        PerfHistoryPoint(ts, cpu, mem, fps)

    private fun tempHome(): File = createTempDirectory("perf-export-home").toFile()

    @Test
    fun `write lands under Toolbelt perf subtree with verbatim csv rows`() {
        val home = tempHome()
        val state = PerfSamplerState(
            historyBySerial = mapOf(
                "S1" to PerfHistorySnapshot(
                    listOf(point(1000, cpu = null, fps = null), point(2000)),
                    360,
                    false,
                ),
                "S2" to PerfHistorySnapshot(listOf(point(3000)), 360, true),
            ),
        )
        val result = writePerfCsvExport(state, home = home, now = 1_728_816_000_000L)
        val file = assertIs<File>(result.getOrNull(), "有数据导出必须成功：${result.exceptionOrNull()}")
        assertEquals(File(File(File(home, "Documents"), "Toolbelt"), "perf"), file.parentFile, "产物落可见产物树")
        val lines = file.readText(Charsets.UTF_8).trimEnd('\n').lines()
        assertEquals("serial,epoch_ms,cpu_percent,mem_used_percent,fps", lines[2], "头行")
        assertEquals(
            "S1,1000,,63.4,",
            lines[3],
            "失败拍空字段如实（null 域禁伪装 0）",
        )
        assertEquals("S1,2000,12.5,63.4,59.9", lines[4])
        assertEquals(
            "# S2：滚动窗口更早采样点已滚出（窗口约 30 分钟）",
            lines[5],
            "溢出注记行（# 开头非 CSV 数据行）",
        )
        assertEquals("S2,3000,12.5,63.4,59.9", lines[6])
        assertEquals(7, lines.size)
        home.deleteRecursively()
    }

    @Test
    fun `empty histories export fails with human-readable reason`() {
        val result = writePerfCsvExport(PerfSamplerState(), home = tempHome())
        val e = assertIs<IllegalStateException>(result.exceptionOrNull())
        assertTrue("无可导出的曲线数据" in (e.message ?: ""), "空数据集人话拒绝：${e.message}")
    }

    @Test
    fun `unwritable home fails with dir creation reason not silently`() {
        val homeAsFile = File(createTempDirectory("perf-export-block").toFile(), "occupier")
        homeAsFile.createNewFile()
        val state = PerfSamplerState(
            historyBySerial = mapOf("S1" to PerfHistorySnapshot(listOf(point(1)), 360, false)),
        )
        val result = writePerfCsvExport(state, home = homeAsFile)
        val e = assertIs<IllegalStateException>(result.exceptionOrNull())
        assertTrue(
            "产物目录创建失败" in (e.message ?: ""),
            "目录不可建人话回执：${e.message}",
        )
        homeAsFile.parentFile.deleteRecursively()
    }

    @Test
    fun `same-second double export avoids collision with suffix file`() {
        val home = tempHome()
        val state = PerfSamplerState(
            historyBySerial = mapOf("S1" to PerfHistorySnapshot(listOf(point(1)), 360, false)),
        )
        val now = 1_728_816_000_000L
        val first = writePerfCsvExport(state, home = home, now = now).getOrThrow()
        val second = writePerfCsvExport(state, home = home, now = now).getOrThrow()
        assertTrue(first.exists() && second.exists(), "同秒两次导出互不覆盖")
        assertTrue(first.name != second.name, "同秒冲突以 -N 后缀避让：${first.name} vs ${second.name}")
        home.deleteRecursively()
    }

    @Test
    fun `has points gate reads retained history across serials`() {
        assertFalse(perfHistoryHasPoints(PerfSamplerState()), "无历史不可导出")
        assertFalse(
            perfHistoryHasPoints(
                PerfSamplerState(
                    historyBySerial = mapOf("S1" to PerfHistorySnapshot(emptyList(), 360, false)),
                ),
            ),
            "空点集 serial 不构成可导出数据",
        )
        assertTrue(
            perfHistoryHasPoints(
                PerfSamplerState(
                    historyBySerial = mapOf(
                        "S1" to PerfHistorySnapshot(emptyList(), 360, false),
                        "S2" to PerfHistorySnapshot(listOf(point(1)), 360, false),
                    ),
                ),
            ),
            "任一 serial 有点即可导出（多会话/残留曲线）",
        )
    }

    @Test
    fun `lark send gate mirrors delivery readiness`() {
        assertNull(perfLarkSendDisabledReason(larkDeliveryReady = true))
        val reason = perfLarkSendDisabledReason(larkDeliveryReady = false)
            ?: error("未就绪态置灰原因缺席（判据失守——静默可发送）")
        assertTrue("飞书桥接" in reason, "置灰原因人话明示：$reason")
    }

    @Test
    fun `export receipt texts carry path and human failure`() {
        val file = File("/tmp/perf-demo.csv")
        assertTrue(perfExportReceipt(file) == "已导出性能曲线：${file.absolutePath}", "成功回执带完整路径")
        assertEquals("导出失败：目录不可写", perfExportFailureText(IllegalStateException("目录不可写")))
        assertTrue(
            "IllegalStateException" in perfExportFailureText(IllegalStateException()),
            "异常 message 缺席时退类名不产空回执",
        )
    }
}
