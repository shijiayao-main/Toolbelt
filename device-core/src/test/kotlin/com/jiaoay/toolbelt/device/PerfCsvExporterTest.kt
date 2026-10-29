package com.jiaoay.toolbelt.device

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * CSV 导出纯函数守护（逐行断言，零 IO 面）：
 * - 头行/注释行/overflowed 注记行结构；
 * - 三域 null = 空字段如实呈现（禁伪装 0——与折线断段同口径）；
 * - epoch 毫秒原样十进制、Map 迭代序逐 serial 分组、空集边界。
 * 路径决策面：exportDir 产物树落点 + resolveExportFile 同秒避让。
 */
class PerfCsvExporterTest {

    private fun point(
        ts: Long,
        cpu: Float? = 12.5f,
        mem: Float? = 63.4f,
        fps: Float? = 59.9f,
    ) = PerfHistoryPoint(ts, cpu, mem, fps)

    @Test
    fun `header lines come first and empty histories yield header only`() {
        val csv = PerfCsvExporter.toCsv(emptyMap())
        val lines = csv.trimEnd('\n').lines()
        assertEquals(
            "serial,epoch_ms,cpu_percent,mem_used_percent,fps",
            lines[2],
            "头行恒在注释行×2 之后",
        )
        assertEquals(3, lines.size, "空集 = 仅注释行×2 + 头行，零数据行：$csv")
        assertTrue(csv.endsWith("\n"), "产物以换行结尾（行导向文本，末行无悬挂）")
        assertTrue(lines[0].startsWith("#"), "首行是注释行（# 开头非 CSV 数据行）")
        assertTrue(lines[1].startsWith("#"), "空字段语义说明行是注释行")
    }

    @Test
    fun `normal sample row keeps epoch millis verbatim and float decimal text`() {
        val csv = PerfCsvExporter.toCsv(
            mapOf("S1" to PerfHistorySnapshot(listOf(point(1_728_816_000_000)), 360, false)),
        )
        assertEquals(
            "S1,1728816000000,12.5,63.4,59.9",
            csv.trimEnd('\n').lines()[3],
            "正常拍整行：epoch 毫秒原样 + 三域 Float 原样十进制",
        )
    }

    @Test
    fun `null domains export as empty fields never as zero`() {
        val csv = PerfCsvExporter.toCsv(
            mapOf("S1" to PerfHistorySnapshot(listOf(point(1000, cpu = null, mem = 63.4f, fps = null)), 360, false)),
        )
        assertEquals(
            "S1,1000,,63.4,",
            csv.trimEnd('\n').lines()[3],
            "失败拍空字段如实（null 域禁伪装 0——假 0 即假旁证）",
        )
    }

    @Test
    fun `overflowed snapshot emits comment note line before that serial rows`() {
        val csv = PerfCsvExporter.toCsv(
            mapOf(
                "S1" to PerfHistorySnapshot(listOf(point(1), point(2)), 360, overflowed = true),
                "S2" to PerfHistorySnapshot(listOf(point(3)), 360, overflowed = false),
            ),
        )
        val lines = csv.trimEnd('\n').lines()
        assertEquals(
            "# S1：滚动窗口更早采样点已滚出（窗口约 30 分钟）",
            lines[3],
            "overflowed 注记行在头行之后、该 serial 数据行之前",
        )
        assertEquals("S1,1,12.5,63.4,59.9", lines[4])
        assertEquals("S1,2,12.5,63.4,59.9", lines[5])
        assertEquals(
            "S2,3,12.5,63.4,59.9",
            lines[6],
            "未溢出 serial 无注记行（Map 迭代序逐 serial 分组）",
        )
        assertEquals(7, lines.size)
    }

    @Test
    fun `empty point list of one serial yields no data row for it`() {
        val csv = PerfCsvExporter.toCsv(
            mapOf(
                "S1" to PerfHistorySnapshot(emptyList(), 360, false),
                "S2" to PerfHistorySnapshot(listOf(point(42)), 360, false),
            ),
        )
        val lines = csv.trimEnd('\n').lines()
        assertEquals(4, lines.size, "空点集 serial 零数据行（也无注记行——未溢出）")
        assertEquals("S2,42,12.5,63.4,59.9", lines[3])
    }

    @Test
    fun `exportDir lands under Toolbelt perf subtree of injected home`() {
        val home = createTempDirectory("perf-csv-home").toFile()
        val dir = PerfCsvExporter.exportDir(home)
        assertEquals(File(File(File(home, "Documents"), "Toolbelt"), "perf"), dir)
        assertTrue(dir.isDirectory, "父目录不存在则创建（runCatching 不炸裂）")
        home.deleteRecursively()
    }

    @Test
    fun `resolveExportFile stamps seconds and avoids same-second collision with suffix`() {
        val dir = createTempDirectory("perf-csv-file").toFile()
        val millis = 1_728_816_012_345L
        val first = PerfCsvExporter.resolveExportFile(dir, millis)
        assertTrue(
            Regex("""perf-\d{8}-\d{6}\.csv""").matches(first.name),
            "文件名 = perf-<yyyyMMdd-HHmmss>.csv（本地时区档），实际 ${first.name}",
        )
        assertTrue(!first.exists(), "避让判定只查不建（路径决策零副作用）")
        first.createNewFile()
        val second = PerfCsvExporter.resolveExportFile(dir, millis)
        assertEquals(
            "${first.nameWithoutExtension}-1.csv",
            second.name,
            "同秒冲突以 -N 后缀避让（logcat 产物同配方）",
        )
        dir.deleteRecursively()
    }
}
