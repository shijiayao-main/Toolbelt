package com.jiaoay.toolbelt.device

/**
 * 设备负载波纯解析层（字符串 → 数值模型，零副作用零 IO）：命令面语义 = adb shell
 * 程序语义，采样调度在 [PerfSampler]。本层同时持有组合命令的段落框架契约
 * （[MARK_*] 标记 + [splitCombined]）——标记是「命令构造」与「输出解析」两端的
 * 唯一真源，改标记只动这里。
 *
 * 失败语义（真门禁）：所有解析函数对人话不认识的输出抛 [IllegalArgumentException]
 * （message 面向用户呈现），禁静默回 0/空——dumpsys 输出跨 ROM 差异大，格式不符
 * 必须显式失败。adb shell 走 pty 时行尾可能带 `\r`，所有入口统一剥除。
 *
 * 格式基线：真机实测——top 支持 `-n 1 -b` 批处理输出；电池服务
 * `dumpsys battery` 缺席（常电设备无电池域），经 `dumpsys power` 的 `mBatteryLevel=`
 * 回退链取数（回退判据与理由见 [PerfSampler]）。SurfaceFlinger 的 `flips=` 行是
 * 公开形态合成基线（非实测——真机格式校准门禁见 [parseSurfaceFlingerFlips]）。
 */
internal object PerfParsers {

    // ── 组合命令段落框架 ──────────────────────────────────────────────

    /** 段落标记（echo 独占一行落面）；命名带 PERF 前缀防与设备输出撞文本 */
    internal const val MARK_MEM = "@@PERF_MEM@@"
    internal const val MARK_THERMAL = "@@PERF_THERMAL@@"
    internal const val MARK_BATTERY = "@@PERF_BATTERY@@"
    internal const val MARK_TOP = "@@PERF_TOP@@"
    internal const val MARK_SF = "@@PERF_SF@@"

    /**
     * 差值基线拍 payload（CPU 计数基线 + FPS 翻帧基线，一次 exec 采两基线）：
     * 段标记复用 [MARK_SF]——[splitCombined] 的 stat 段 = 首标记前内容，其余段
     * 不在基线拍出现（缺席即空串，不触发任何解析）。
     */
    internal const val BASELINE_PAYLOAD: String =
        "cat /proc/stat; echo $MARK_SF; dumpsys SurfaceFlinger"

    /**
     * 组合采样命令的设备侧 payload（一次 exec 采六域，段间 echo 标记分割）。
     * 不含电池回退——`dumpsys power` 输出体积大且仅电池域缺席时才需要
     * （见 [PerfSampler] 回退链），常态一拍只花差值基线与本次共两次 exec。
     */
    internal const val COMBINED_PAYLOAD: String =
        "cat /proc/stat; echo $MARK_MEM; cat /proc/meminfo; " +
            "echo $MARK_THERMAL; dumpsys thermalservice; " +
            "echo $MARK_BATTERY; dumpsys battery; " +
            "echo $MARK_TOP; top -n 1 -b; " +
            "echo $MARK_SF; dumpsys SurfaceFlinger"

    /** 组合命令按标记切段结果：段缺失（标记行不见）= 空串，对应域解析显式失败 */
    internal data class CombinedSections(
        val stat: String,
        val mem: String,
        val thermal: String,
        val battery: String,
        val top: String,
        val sf: String,
    )

    internal fun splitCombined(output: String): CombinedSections {
        val lines = output.replace("\r", "").lines()
        // 单趟扫描建「行号 → 标记」索引：逐标记 indexOfFirst 全表反复扫描在
        // SurfaceFlinger dump（体积大头）下每拍两次 × 数千行被放大
        val trimmedLines = lines.map { it.trim() }
        var firstMarkIdx = -1
        val markLineIdx = HashMap<String, Int>(MARKS.size * 2)
        for (i in trimmedLines.indices) {
            val trimmed = trimmedLines[i]
            if (trimmed in MARK_SET && trimmed !in markLineIdx) {
                if (firstMarkIdx < 0) firstMarkIdx = i
                markLineIdx[trimmed] = i
            }
        }
        fun sectionAfter(marker: String): String {
            val idx = markLineIdx[marker] ?: return ""
            // 本段终止于其后最近的一个标记行
            val nextMarkIdx = markLineIdx.values.filter { it > idx }.minOrNull() ?: lines.size
            return lines.subList(idx + 1, nextMarkIdx).joinToString("\n").trim()
        }
        return CombinedSections(
            stat = lines.subList(0, if (firstMarkIdx < 0) lines.size else firstMarkIdx).joinToString("\n").trim(),
            mem = sectionAfter(MARK_MEM),
            thermal = sectionAfter(MARK_THERMAL),
            battery = sectionAfter(MARK_BATTERY),
            top = sectionAfter(MARK_TOP),
            sf = sectionAfter(MARK_SF),
        )
    }

    private val MARKS = listOf(MARK_MEM, MARK_THERMAL, MARK_BATTERY, MARK_TOP, MARK_SF)

    private val MARK_SET = MARKS.toSet()

    // ── 预编译解析正则（采样热路径每拍全量过这些解析器，函数内联 Regex = 逐行重复编译）──

    private val WHITESPACE_SPLIT = Regex("\\s+")

    private val MEMINFO_LINE = Regex("^([A-Za-z]+):\\s+(\\d+)\\s+kB")

    private val THERMAL_VALUE = Regex("mValue=(-?\\d+(?:\\.\\d+)?)")

    private val BATTERY_LEVEL_LINE = Regex("^\\s*level:\\s+(\\d+)\\s*$", RegexOption.MULTILINE)

    private val POWER_BATTERY_LEVEL = Regex("mBatteryLevel=(\\d+)")

    private val FLIPS = Regex("""flips=(\d+)""")

    // ── CPU：/proc/stat 两次差值 ─────────────────────────────────────

    /**
     * 解析 `/proc/stat` 聚合 `cpu ` 行（跳过 cpu0/cpu1… 单核行）为前 8 列
     * user/nice/system/idle/iowait/irq/softirq/steal 计数。guest 列不计入
     * （guest 已含在 user 内，计入会双算）。
     */
    internal fun parseProcStatTotal(output: String): LongArray {
        val line = output.replace("\r", "").lineSequence()
            .firstOrNull { it.startsWith("cpu ") || it.trim() == "cpu" }
            ?: throw IllegalArgumentException("未见 /proc/stat 聚合 cpu 行")
        val fields = line.trim().split(WHITESPACE_SPLIT)
        // 首词是 "cpu"；后随至多 10 列计数，不足 8 列（异常 ROM 截断）显式失败
        if (fields.size < 5) throw IllegalArgumentException("cpu 行列数不足（${fields.size}）")
        return LongArray(8) { i -> fields.getOrNull(i + 1)?.toLongOrNull() ?: 0L }
    }

    /**
     * 两次聚合 cpu 计数差值算占用率（busy% = (Δtotal − Δidle)/Δtotal×100，
     * idle 含 iowait 列）。计数器回绕/设备重启（Δtotal ≤ 0）显式失败——差值基线
     * 失效时禁给假 0。
     */
    internal fun cpuBusyPercentBetween(prev: LongArray, cur: LongArray): Float {
        if (cur.size != prev.size) throw IllegalArgumentException("两次 cpu 计数列数不一致")
        val totalDelta = cur.sum() - prev.sum()
        val idleDelta = (cur[3] + cur[4]) - (prev[3] + prev[4])
        if (totalDelta <= 0) throw IllegalArgumentException("cpu 计数差值非正（设备重启或两次读取过近）")
        return ((totalDelta - idleDelta) * 100f / totalDelta).coerceIn(0f, 100f)
    }

    // ── 内存：/proc/meminfo 关键项 ────────────────────────────────────

    internal fun parseMeminfo(output: String): MemoryStats {
        val values = HashMap<String, Long>(8)
        output.replace("\r", "").lineSequence().forEach { line ->
            val m = MEMINFO_LINE.find(line) ?: return@forEach
            values[m.groupValues[1]] = m.groupValues[2].toLong()
        }
        val total = values["MemTotal"]
            ?: throw IllegalArgumentException("未见 MemTotal 行")
        val available = values["MemAvailable"]
            ?: throw IllegalArgumentException("未见 MemAvailable 行")
        return MemoryStats(totalKb = total, availableKb = available, freeKb = values["MemFree"] ?: 0L)
    }

    // ── 温度：dumpsys thermalservice ────────────────────────────────

    /**
     * 解析 `Current temperatures from HAL:` 段内的 `Temperature{mValue=…, …}`
     * 行，取最大值（多传感器取最热口径）。段缺失或无一行可解析 = 显式失败。
     */
    internal fun parseThermalservice(output: String): Float {
        val lines = output.replace("\r", "").lines()
        val start = lines.indexOfFirst { it.trim() == "Current temperatures from HAL:" }
        if (start < 0) throw IllegalArgumentException("未见 Current temperatures from HAL 段")
        val regex = THERMAL_VALUE
        val values = lines.drop(start + 1)
            .takeWhile { it.isBlank() || it.contains("Temperature{") }
            .mapNotNull { regex.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
        if (values.isEmpty()) throw IllegalArgumentException("温度段内无一行可解析的 mValue")
        return values.max()
    }

    // ── 电池：dumpsys battery 主链 + dumpsys power 回退链 ────────────

    /** 主链：标准 `dumpsys battery` 的 `level: N` 行（0–100）；缺席即抛（回退链接手） */
    internal fun parseBatteryLevel(output: String): Int {
        val m = BATTERY_LEVEL_LINE.find(output.replace("\r", ""))
            ?: throw IllegalArgumentException("未见 battery level 行（服务缺席或格式不符）")
        return m.groupValues[1].toInt()
    }

    /** 回退链：`dumpsys power` 的 `mBatteryLevel=N` 行（设备常电无电池服务时的唯一取数点） */
    internal fun parsePowerBatteryLevel(output: String): Int {
        val m = POWER_BATTERY_LEVEL.find(output.replace("\r", ""))
            ?: throw IllegalArgumentException("未见 mBatteryLevel 行")
        return m.groupValues[1].toInt()
    }

    // ── 进程排行：top -n 1 -b ───────────────────────────────────────

    /** toybox top 交互态 ANSI 转义（光标操作/反显加粗），批处理本无、剥除防呆 */
    private val ANSI = Regex("\\u001b\\[[0-9;?]*[a-zA-Z]")

    /**
     * 解析 `top -n 1 -b` 输出的进程行：表头行（含 PID/USER/%CPU）定位后逐行
     * 按定序列解析（toybox 布局：PID USER PR NI VIRT RES SHR S %CPU %MEM TIME+ ARGS），
     * 坏行跳过不拖垮好行；表头缺失或零有效行 = 显式失败。
     * 返回保持 top 的 CPU 降序列序（top 侧已排好），RSS 排序由消费面做。
     */
    internal fun parseTopProcesses(output: String): List<ProcessEntry> {
        val lines = output.replace("\r", "").replace(ANSI, "").lines()
        val headerIdx = lines.indexOfFirst { it.contains("PID") && it.contains("USER") && it.contains("%CPU") }
        if (headerIdx < 0) throw IllegalArgumentException("未见 top 表头行（PID/USER/%CPU）")
        val entries = lines.drop(headerIdx + 1).mapNotNull(::parseTopRow)
        if (entries.isEmpty()) throw IllegalArgumentException("top 表头后无一行有效进程行")
        return entries
    }

    /** 单行解析：列数 ≥ 12 且 PID 可解析才认（top 自身行如 `top -n 1 -b` 也是普通行） */
    private fun parseTopRow(line: String): ProcessEntry? {
        val cols = line.trim().split(WHITESPACE_SPLIT)
        if (cols.size < 12) return null
        val pid = cols[0].toIntOrNull() ?: return null
        val cpu = cols.getOrNull(8)?.toFloatOrNull() ?: return null
        val mem = cols.getOrNull(9)?.toFloatOrNull() ?: return null
        val rss = rssToKb(cols[5])
        return ProcessEntry(
            pid = pid,
            user = cols[1],
            cpuPercent = cpu,
            memPercent = mem,
            rssKb = rss,
            name = cols.drop(11).joinToString(" "),
        )
    }

    /**
     * RES 列缩写折算 kB：toybox human_readable 口径 K/M/G/T/P/E = 1024 进制，
     * 无缩写裸数字 = 字节（内核线程 RES=0 形态）。
     */
    internal fun rssToKb(token: String): Long {
        if (token.isEmpty()) return 0L
        val suffix = token.last().uppercaseChar()
        val factor = when (suffix) {
            'K' -> 1L
            'M' -> 1L shl 10
            'G' -> 1L shl 20
            'T' -> 1L shl 30
            'P' -> 1L shl 40
            'E' -> 1L shl 50
            else -> return (token.toLongOrNull() ?: 0L) / 1024L  // 裸字节 → kB
        }
        val number = token.dropLast(1).toDoubleOrNull() ?: return 0L
        return (number * factor).toLong()
    }

    // ── FPS：dumpsys SurfaceFlinger 翻帧计数 ────────────────────────

    /**
     * 解析 `dumpsys SurfaceFlinger` 输出的 `flips=<N>` 翻帧累计计数（合成器
     * 单调递增计数，取首个命中行）。
     *
     * 帧率语义（防误读）：flips 差值是**全屏合成帧率**——含系统各显示层（状态栏/
     * 其它应用/动效）的合成帧，不是单应用的帧率；曲线走低可能是前台应用静止而
     * 其它层在动，也可能是真掉帧，须结合复现场景读。
     *
     * 格式基线：`flips=` 独立 token 行的公开形态合成样例（非实测）。跨 ROM 的
     * SurfaceFlinger dump 输出差异大，真机格式校准门禁：需真机 dumpsys 实测输出
     * 核对后定稿（thermalservice 实测基线同款流程）；格式不符显式失败不静默给 0。
     */
    internal fun parseSurfaceFlingerFlips(output: String): Long {
        val m = FLIPS.find(output.replace("\r", ""))
            ?: throw IllegalArgumentException("未见 flips 行（SurfaceFlinger 输出格式不符或本 ROM 不打印 flips）")
        return m.groupValues[1].toLong()
    }

    /**
     * 两采样点 flips 差值换算帧率：fps = Δflips / [elapsedMs] × 1000（[elapsedMs]
     * = 两采样点实际读表间隔——含 exec 耗时，除名义窗口更贴近真实帧率）。
     * 计数回退（SurfaceFlinger 重启/回绕，Δ < 0）或窗口非正显式失败——差值
     * 失效禁给假值。Δ = 0 是合法读数（静屏无新合成帧），非失败。
     */
    internal fun fpsBetween(prevFlips: Long, curFlips: Long, elapsedMs: Long): Float {
        if (elapsedMs <= 0) throw IllegalArgumentException("fps 差值窗口非正（两次采样点过近或时钟异常）")
        val delta = curFlips - prevFlips
        if (delta < 0) throw IllegalArgumentException("flips 计数回退（SurfaceFlinger 重启或计数回绕）")
        return delta * 1000f / elapsedMs
    }
}
