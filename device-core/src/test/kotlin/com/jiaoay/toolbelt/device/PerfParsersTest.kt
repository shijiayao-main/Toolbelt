package com.jiaoay.toolbelt.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设备负载波纯解析层守护：真机（demo-board 平台）实测输出做 fixture（批处理 top /
 * thermalservice HAL 段 / meminfo 关键项——跨 ROM 差异按实测基线钉住）；
 * 坏输出全部断言显式抛 [IllegalArgumentException]，禁静默 0/空。
 */
class PerfParsersTest {

    // ── 真机 fixture ───────────────────────────────────────────────────

    /** 真机实测聚合 cpu 行（10 列：user nice system idle iowait irq softirq steal guest guest_nice） */
    private val realProcStat = """
        cpu  1444078 26340 724244 11714991 8177 39725 21832 0 0 0
        cpu0 32628 1196 80304 1585783 658 13246 12973 0 0 0
        cpu1 54705 1652 79640 1576111 855 5318 1103 0 0 0
        intr 101944023 0 0 0
    """.trimIndent()

    private val realMeminfo = """
        MemTotal:       13797032 kB
        MemFree:         3965080 kB
        MemAvailable:    6521736 kB
        Buffers:            3588 kB
        Cached:          2149960 kB
        SwapCached:        27412 kB
    """.trimIndent()

    private val realThermal = """
        IsStatusOverride: false
        ThermalEventListeners:
        Thermal Status: 0
        Cached temperatures:
        Temperature{mValue=30.8, mType=3, mName=test temperature sensor, mStatus=0}
        HAL Ready: true
        Current temperatures from HAL:
        Temperature{mValue=30.8, mType=3, mName=test temperature sensor, mStatus=0}
        Current cooling devices from HAL:
        CoolingDevice{mValue=100, mType=0, mName=test cooling device}
    """.trimIndent()

    private val realTop = """
        Tasks: 420 total,   1 running, 419 sleeping,   0 stopped,   0 zombie
          Mem:    13473M total,     9596M used,     3877M free,      2101M cached
         Swap:     6143M total,     1212M used,     4931M free,      2101M cached
        800%cpu  84%user   0%nice  42%sys 671%idle   0%iow   0%irq   3%sirq   0%host
          PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS
        13856 system       20   0 7.1G 266M 136M S 61.2   1.9 170:55.54 com.demo.oobe
         2810 system       20   0  18G 803M 594M S 32.2   5.9  76:49.57 com.demo.launcher
        14826 root         20   0 2.0G 4.5M 3.6M R 19.3   0.0   0:00.06 top -n 1 -b
        """.trimIndent()

    /**
     * SurfaceFlinger `flips=` 行的公开形态合成样例（非实测——真机格式校准门禁：
     * 需真机 dumpsys 实测输出核对后定稿，thermalservice 实测基线同款流程）。
     */
    private val syntheticSurfaceFlinger = """
        SurfaceFlinger global state:
        Display 0 HWC layers:
        flips=928374
    """.trimIndent()

    // ── /proc/stat 与 CPU 差值 ────────────────────────────────────────

    @Test
    fun `parseProcStatTotal reads aggregate cpu line only`() {
        val stat = PerfParsers.parseProcStatTotal(realProcStat)
        assertEquals(8, stat.size)
        assertEquals(1444078L, stat[0])
        assertEquals(11714991L, stat[3])
        assertEquals(0L, stat[7])
    }

    @Test
    fun `parseProcStatTotal tolerates crlf line endings from adb pty`() {
        val stat = PerfParsers.parseProcStatTotal(realProcStat.replace("\n", "\r\n"))
        assertEquals(1444078L, stat[0])
    }

    @Test
    fun `parseProcStatTotal fails explicitly without aggregate cpu line`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PerfParsers.parseProcStatTotal("cpu0 1 1 1 1 1 1 1 0 0 0\nctxt 12\n")
        }
        assertTrue(e.message!!.contains("cpu"), "失败原因须人话（实际：${e.message}）")
    }

    @Test
    fun `cpuBusyPercentBetween computes busy delta over window`() {
        // 两次计数：Δtotal=150（user+100、idle+50）→ busy%=100/150×100
        val prev = longArrayOf(1000, 0, 0, 900, 0, 0, 0, 0)
        val cur = longArrayOf(1100, 0, 0, 950, 0, 0, 0, 0)
        assertEquals(100f / 150f * 100f, PerfParsers.cpuBusyPercentBetween(prev, cur), 0.001f)
    }

    @Test
    fun `cpuBusyPercentBetween fails explicitly on non-positive counter delta`() {
        val flat = longArrayOf(100, 0, 0, 50, 0, 0, 0, 0)
        // 计数器不动（两次读取过近）与回绕（设备重启变小）都不得给假 0
        assertFailsWith<IllegalArgumentException> { PerfParsers.cpuBusyPercentBetween(flat, flat.copyOf()) }
        assertFailsWith<IllegalArgumentException> {
            PerfParsers.cpuBusyPercentBetween(flat, longArrayOf(1, 0, 0, 1, 0, 0, 0, 0))
        }
    }

    @Test
    fun `cpuBusyPercentBetween fails on column mismatch`() {
        assertFailsWith<IllegalArgumentException> {
            PerfParsers.cpuBusyPercentBetween(longArrayOf(1, 2), longArrayOf(1, 2, 3))
        }
    }

    // ── meminfo ────────────────────────────────────────────────────────

    @Test
    fun `parseMeminfo reads key items and used percent`() {
        val mem = PerfParsers.parseMeminfo(realMeminfo)
        assertEquals(13_797_032L, mem.totalKb)
        assertEquals(6_521_736L, mem.availableKb)
        assertEquals(3_965_080L, mem.freeKb)
        val usedPercent = (mem.totalKb - mem.availableKb) * 100f / mem.totalKb
        assertEquals(usedPercent, mem.usedPercent, 0.0001f)
    }

    @Test
    fun `parseMeminfo fails explicitly without MemAvailable`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PerfParsers.parseMeminfo("MemTotal: 100 kB\nMemFree: 10 kB\n")
        }
        assertTrue(e.message!!.contains("MemAvailable"))
    }

    // ── thermalservice ────────────────────────────────────────────────

    @Test
    fun `parseThermalservice reads HAL section value`() {
        assertEquals(30.8f, PerfParsers.parseThermalservice(realThermal))
    }

    @Test
    fun `parseThermalservice takes max across sensors`() {
        val output = """
            Current temperatures from HAL:
            Temperature{mValue=41.5, mType=1, mName=cpu0, mStatus=0}
            Temperature{mValue=38.2, mType=2, mName=gpu, mStatus=0}
        """.trimIndent()
        assertEquals(41.5f, PerfParsers.parseThermalservice(output))
    }

    @Test
    fun `parseThermalservice fails explicitly without HAL section`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PerfParsers.parseThermalservice("IsStatusOverride: false\nHAL Ready: true\n")
        }
        assertTrue(e.message!!.contains("Current temperatures"))
    }

    // ── 电池主链与回退链 ──────────────────────────────────────────────

    @Test
    fun `parseBatteryLevel reads standard dumpsys battery level line`() {
        val output = "Current Battery Service state:\n  AC powered: false\n  level: 85\n  status: 2\n"
        assertEquals(85, PerfParsers.parseBatteryLevel(output))
    }

    @Test
    fun `parseBatteryLevel fails explicitly when battery service missing`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PerfParsers.parseBatteryLevel("Can't find service: battery\n")
        }
        assertTrue(e.message!!.contains("battery"))
    }

    @Test
    fun `parsePowerBatteryLevel reads fallback level from dumpsys power`() {
        val output = "mBatteryLevel=100\nmBatteryLevelLow=false\n"
        assertEquals(100, PerfParsers.parsePowerBatteryLevel(output))
    }

    @Test
    fun `parsePowerBatteryLevel fails explicitly without level line`() {
        assertFailsWith<IllegalArgumentException> {
            PerfParsers.parsePowerBatteryLevel("mWakefulness=Awake\n")
        }
    }

    // ── top 进程排行 ──────────────────────────────────────────────────

    @Test
    fun `parseTopProcesses reads toybox batch rows`() {
        val entries = PerfParsers.parseTopProcesses(realTop)
        assertEquals(3, entries.size)
        val first = entries[0]
        assertEquals(13856, first.pid)
        assertEquals("system", first.user)
        assertEquals(61.2f, first.cpuPercent)
        assertEquals(1.9f, first.memPercent)
        assertEquals(266L * 1024, first.rssKb)
        assertEquals("com.demo.oobe", first.name)
        // 含空格的命令行名须整名保留
        assertEquals("top -n 1 -b", entries[2].name)
        // M 缩写折算：第二行 RES=803M
        assertEquals(803L * 1024, entries[1].rssKb)
    }

    @Test
    fun `parseTopProcesses tolerates leftover ansi escapes`() {
        val output = "\u001b[H\u001b[J" + realTop.replace("13856 system", "\u001b[7m 13856 system\u001b[0m")
        val entries = PerfParsers.parseTopProcesses(output)
        assertEquals(13856, entries.first().pid)
    }

    @Test
    fun `parseTopProcesses fails explicitly without header`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PerfParsers.parseTopProcesses("Tasks: 1 total\nMem: 1G total\n")
        }
        assertTrue(e.message!!.contains("表头"))
    }

    @Test
    fun `parseTopProcesses fails explicitly when no row survives`() {
        val output = "  PID USER      PR  NI VIRT RES SHR S[%CPU] %MEM TIME+ ARGS\nbad row\n"
        assertFailsWith<IllegalArgumentException> { PerfParsers.parseTopProcesses(output) }
    }

    @Test
    fun `rssToKb folds suffixes by 1024 scale`() {
        assertEquals(803L * 1024, PerfParsers.rssToKb("803M"))
        assertEquals(4608L, PerfParsers.rssToKb("4.5M"))
        assertEquals((7.1 * 1024 * 1024).toLong(), PerfParsers.rssToKb("7.1G"))
        assertEquals(0L, PerfParsers.rssToKb("0"))
        assertEquals(2048L, PerfParsers.rssToKb("2097152"))
    }

    // ── FPS：SurfaceFlinger flips 与差值换算 ──────────────────────────

    @Test
    fun `parseSurfaceFlingerFlips reads first flips counter line`() {
        assertEquals(928_374L, PerfParsers.parseSurfaceFlingerFlips(syntheticSurfaceFlinger))
    }

    @Test
    fun `parseSurfaceFlingerFlips tolerates crlf line endings from adb pty`() {
        assertEquals(928_374L, PerfParsers.parseSurfaceFlingerFlips(syntheticSurfaceFlinger.replace("\n", "\r\n")))
    }

    @Test
    fun `parseSurfaceFlingerFlips fails explicitly without flips line`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PerfParsers.parseSurfaceFlingerFlips("SurfaceFlinger global state:\nno counter here\n")
        }
        assertTrue(e.message!!.contains("flips"), "缺席须显式失败不静默给 0（实际：${e.message}）")
    }

    @Test
    fun `fpsBetween converts flips delta over measured elapsed window`() {
        // Δflips=120、实际间隔 2000ms → 60 fps
        assertEquals(60f, PerfParsers.fpsBetween(prevFlips = 1000L, curFlips = 1120L, elapsedMs = 2000L), 0.001f)
        // Δ=0 是合法读数（静屏无新合成帧），非失败
        assertEquals(0f, PerfParsers.fpsBetween(1000L, 1000L, 2000L), 0.001f)
    }

    @Test
    fun `fpsBetween fails explicitly on counter regression or non-positive window`() {
        // 计数回退（SurfaceFlinger 重启/回绕）不得给负值或假值
        val e = assertFailsWith<IllegalArgumentException> {
            PerfParsers.fpsBetween(prevFlips = 2000L, curFlips = 1000L, elapsedMs = 1000L)
        }
        assertTrue(e.message!!.contains("回退"), "回退原因须人话（实际：${e.message}）")
        assertFailsWith<IllegalArgumentException> { PerfParsers.fpsBetween(1L, 2L, elapsedMs = 0L) }
    }

    // ── 组合切段 ──────────────────────────────────────────────────────

    @Test
    fun `splitCombined splits sections by marker lines`() {
        val output = buildString {
            append(realProcStat).append('\n')
            append(PerfParsers.MARK_MEM).append('\n')
            append(realMeminfo).append('\n')
            append(PerfParsers.MARK_THERMAL).append('\n')
            append(realThermal).append('\n')
            append(PerfParsers.MARK_BATTERY).append('\n')
            append("Can't find service: battery\n")
            append(PerfParsers.MARK_TOP).append('\n')
            append(realTop).append('\n')
            append(PerfParsers.MARK_SF).append('\n')
            append(syntheticSurfaceFlinger)
        }
        val sections = PerfParsers.splitCombined(output)
        assertTrue(sections.stat.contains("cpu  1444078"), "stat 段含聚合 cpu 行")
        assertEquals(13_797_032L, PerfParsers.parseMeminfo(sections.mem).totalKb)
        assertEquals(30.8f, PerfParsers.parseThermalservice(sections.thermal))
        assertTrue(sections.battery.contains("Can't find service"))
        assertEquals(3, PerfParsers.parseTopProcesses(sections.top).size)
        assertEquals(928_374L, PerfParsers.parseSurfaceFlingerFlips(sections.sf), "sf 段按标记切出可解析")
    }

    @Test
    fun `splitCombined missing marker leaves empty section and parsers fail explicitly`() {
        // 组合输出截断（标记行后无内容）：对应域解析必须显式失败，不静默
        val sections = PerfParsers.splitCombined("cpu  1 1 1 1 1 1 1 0 0 0\n${PerfParsers.MARK_MEM}\n")
        assertEquals("", sections.thermal)
        assertEquals("", sections.top)
        assertEquals("", sections.sf)
        assertNull(PerfField.of { PerfParsers.parseThermalservice(sections.thermal) }.value)
        assertTrue(PerfField.of { PerfParsers.parseThermalservice(sections.thermal) }.error!!.isNotEmpty())
        val fps = PerfField.of { PerfParsers.parseSurfaceFlingerFlips(sections.sf) }
        assertNull(fps.value, "sf 段缺席 flips 解析显式失败（禁静默给 0）")
        assertTrue(fps.error!!.isNotEmpty())
    }

    // ── PerfField 语义 ────────────────────────────────────────────────

    @Test
    fun `perfField ok and fail are mutually exclusive`() {
        assertFailsWith<IllegalArgumentException> { PerfField<Int>(null, null) }
        assertFailsWith<IllegalArgumentException> { PerfField(1, "both") }
        assertEquals(3, PerfField.ok(3).value)
        assertEquals("坏输出", PerfField.fail<Int>("坏输出").error)
        // of：解析块抛 IAE 转 fail，正常值转 ok
        assertEquals(7, PerfField.of { 7 }.value)
        assertEquals("坏了", PerfField.of<String> { throw IllegalArgumentException("坏了") }.error)
    }
}
