package com.jiaoay.toolbelt.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设备采样与文件解析热路径的性能上界（数量级粗上界口径）。
 *
 * 这两条路径都跑在周期性/批量形态上：组合采样解析每拍对整个 dumpsys 输出做段落切分，
 * 文件浏览解析每次列目录对全部行做逐行列形态校验。二者算法正确时均与输入规模线性相关，
 * 一旦退化为「逐段/逐行重扫全量」（例如每个标记各扫一遍全表、逐行现编正则），耗时会随
 * 规模平方膨胀并直接拖垮设备面板的刷新节奏。
 *
 * 阈值 = 本机实测基线 × 约 10 倍，只拦数量级回归；失败信息带实测值供复测归因。
 */
class DeviceHotPathBudgetTest {

    /** 合成 dumpsys SurfaceFlinger 形态输出：数千行、体积占组合输出大头 */
    private fun surfaceFlingerDump(lines: Int): String = buildString {
        appendLine("SurfaceFlinger global state:")
        appendLine("Display 0 HWC layers:")
        for (i in 0 until lines) {
            appendLine("  layer $i: name=SurfaceView[com.demo/App]#$i visible=$i frames=$i")
        }
        appendLine("flips=928374")
        appendLine("Display 0 layer 0:")
    }

    private fun combinedOutput(sfLines: Int): String = buildString {
        appendLine("cpu  1444078 26340 724244 11714991 8177 39725 21832 0 0 0")
        appendLine("cpu0 32628 1196 80324 1585783 658 13246 12973 0 0 0")
        appendLine(PerfParsers.MARK_MEM)
        appendLine("MemTotal:       13797032 kB")
        appendLine("MemAvailable:    6521736 kB")
        appendLine(PerfParsers.MARK_THERMAL)
        appendLine("Current temperatures from HAL:")
        appendLine("Temperature{mValue=30.8, mType=3, mName=test temperature sensor, mStatus=0}")
        appendLine(PerfParsers.MARK_BATTERY)
        appendLine("  level: 87")
        appendLine(PerfParsers.MARK_TOP)
        appendLine("  PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS")
        appendLine("13856 system       20   0 7.1G 266M 136M S 61.2   1.9 170:55.54 com.demo.oobe")
        appendLine(PerfParsers.MARK_SF)
        append(surfaceFlingerDump(sfLines))
    }

    /**
     * 组合采样段落切分：每拍一次 exec 的全部输出都要切段后再解析，标记切分若退化为
     * 「逐标记全表 indexOfFirst」，成本随标记数 × 行数相乘膨胀。
     * 上限 1 秒为本机实测（约 0.05–0.10 秒）的约 10 倍。
     */
    @Test
    fun `组合采样段落切分十万行线性完成`() {
        val output = combinedOutput(sfLines = 100_000)
        val startedAt = System.nanoTime()
        val sections = PerfParsers.splitCombined(output)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(928_374L, PerfParsers.parseSurfaceFlingerFlips(sections.sf), "切段结果须可正常解析")
        assertEquals(30.8f, PerfParsers.parseThermalservice(sections.thermal), "切段结果须可正常解析")
        println("[hot-path] 组合输出切段 10 万行: ${elapsedMs}ms")
        assertTrue(
            elapsedMs < 1_000,
            "10 万行组合输出切段须单趟完成；实测 ${elapsedMs}ms，上限 1000ms（基线约 50–100ms 的约 10 倍）",
        )
    }

    /** 合成 toybox `ls -l` 长格式输出 */
    private fun lsOutput(entries: Int): String = buildString {
        appendLine("total $entries")
        for (i in 0 until entries) {
            appendLine("drwxrwx--x 2 root root 4096 2026-10-11 14:23 dir$i")
        }
    }

    /**
     * 设备文件浏览行解析：列目录输出逐行做权限/日期列形态校验，行数与目录条目同阶；
     * 逐行现编正则会把这层放大一个数量级（每行编译一次 Pattern）。
     * 上限 2 秒为本机实测（约 0.17–0.23 秒）的约 10 倍。
     */
    @Test
    fun `十万条目列目录输出逐行解析线性完成`() {
        val output = lsOutput(entries = 100_000)
        val startedAt = System.nanoTime()
        val entries = DeviceFileParsers.parseLsEntries(output)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(100_000, entries.size, "全部行形态合法，条目数应等于行数")
        println("[hot-path] 列目录解析 10 万行: ${elapsedMs}ms")
        assertTrue(
            elapsedMs < 2_000,
            "10 万条目列目录输出须逐行单趟完成；实测 ${elapsedMs}ms，上限 2000ms（基线约 170–230ms 的约 10 倍）",
        )
    }
}
