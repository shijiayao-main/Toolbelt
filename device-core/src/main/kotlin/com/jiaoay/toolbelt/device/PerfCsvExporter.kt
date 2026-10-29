package com.jiaoay.toolbelt.device

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 性能曲线历史导出器：[PerfHistorySnapshot] → CSV 交付物（性能数据与日志同
 * 时间轴对齐的旁证载体——epoch 毫秒列可与日志时间戳直接对表）。
 *
 * 纯函数纪律：[toCsv] 零 IO 零副作用；[exportDir]/[resolveExportFile] 只做
 * 路径决策（写盘由调用方承担，失败不吞）。
 *
 * 产物落点 = `~/Documents/Toolbelt/perf/`：可见可拖拽的工具产物树（与 logcat 抓取产物、
 * reports 报告产物同根），不落 `~/.toolbelt` 内部数据根。
 *
 * CSV 行结构（注释行以 `#` 开头，**非 CSV 数据行**——消费方按注释行理解，不入
 * 数据列解析）：
 * 1. 注释行×2：导出说明与空字段语义；
 * 2. 头行：`serial,epoch_ms,cpu_percent,mem_used_percent,fps`；
 * 3. 逐 serial 分组（保持入参 Map 迭代序）：[PerfHistorySnapshot.overflowed]
 *    为 true 的 serial 先出一条「更早已滚出」注记行，随后逐点一行。
 *
 * 数值口径：epoch_ms = 采样时刻 Unix 毫秒原样十进制；三域为 Float 原样十进制
 * 文本。采样失败拍该域为 **null**——导出为空字段如实呈现，禁伪装成 0（与折线
 * 渲染面「null 处断段、不画假谷」同口径——伪装 0 即把失败拍变成假旁证）；消费
 * 方以空字段 = 该拍该域无有效读数理解。
 */
private const val DEFAULT_VISIBLE_ROOT = "Toolbelt"

object PerfCsvExporter {

    /**
     * 导出目录（`<home>/Documents/<appName>/perf`，父目录不存在则创建；创建失败留给
     * 调用方写盘报错——路径决策不吞错）。[home] 参数化供单测注入临时根。
     *
     * [appName] 由调用方给出：本 lib 不持路径真源（宿主经自己的路径访问器取真实产物树根），
     * 默认值只在独立使用时生效。
     */
    fun exportDir(
        home: File = File(System.getProperty("user.home")),
        appName: String = DEFAULT_APP_DIR_NAME,
    ): File = File(File(File(home, "Documents"), appName), "perf").apply { mkdirs() }

    /** 独立使用时的产物树名（宿主注入自己的值；与宿主可见产物树同名只是默认对齐） */
    private const val DEFAULT_APP_DIR_NAME = DEFAULT_VISIBLE_ROOT

    /**
     * 产物文件名：`perf-<yyyyMMdd-HHmmss>.csv`（同秒冲突时 `-N` 后缀避让，
     * logcat 产物同配方）。internal 可见性不跨模块，消费面经本方法取文件。
     */
    fun resolveExportFile(dir: File = exportDir(), millis: Long = System.currentTimeMillis()): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(millis))
        var candidate = File(dir, "perf-$stamp.csv")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "perf-$stamp-$suffix.csv")
            suffix++
        }
        return candidate
    }

    /**
     * 曲线历史 → CSV 文本（行结构见对象 KDoc）。
     *
     * - [histories] 保持入参 Map 迭代序逐 serial 分组（调用方以采样器发布序
     *   传入——键序即各 serial 首次采样序）；
     * - 空 Map / 各 serial 全空点集 = 仅注释行 + 头行（调用方消费面前置判据
     *   兜底，本函数不拒空——空输入零炸裂）；
     * - 产物以换行符结尾（行导向文本，末行无悬挂）。
     */
    fun toCsv(histories: Map<String, PerfHistorySnapshot>): String = buildString {
        appendLine("# Toolbelt 性能曲线导出（serial = 设备串号；epoch_ms = 采样时刻 Unix 毫秒）")
        appendLine("# 空字段 = 该拍该域采样失败（不伪装 0）；以 # 开头的行为注释行，非 CSV 数据行")
        appendLine("serial,epoch_ms,cpu_percent,mem_used_percent,fps")
        histories.forEach { (serial, snapshot) ->
            if (snapshot.overflowed) {
                appendLine("# $serial：滚动窗口更早采样点已滚出（窗口约 30 分钟）")
            }
            snapshot.points.forEach { point ->
                appendLine(
                    listOf(
                        serial,
                        point.timestampMs.toString(),
                        point.cpuPercent?.toString() ?: "",
                        point.memUsedPercent?.toString() ?: "",
                        point.fps?.toString() ?: "",
                    ).joinToString(","),
                )
            }
        }
    }
}
