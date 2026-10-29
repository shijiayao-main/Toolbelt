package com.jiaoay.log

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.LocalDateTime

object LogTimeFilter {

    /**
     * @param target 格式 yyyy-MM-dd_HH:mm:ss.SSS（亦容忍空格分隔与省略毫秒）；
     *   亦容忍裸 MM-dd HH:mm:ss.SSS（xlog 自产明文行首同款，从日志直接复制即可）。
     *   裸形态仅对裸格式日志保证正确（年份按目标年统一重锚，锚定值不影响结果）；
     *   年份格式日志（外部解码器产物）与跨年边界的日志请补全年份
     */
    fun filterByTime(file: File, target: String, rangeMinute: Long): ToolsResult {
        val targetTime: LocalDateTime = LogTimestamp.parseTarget(target)
            ?: return ToolsResult.Failure(
                "目标时间无法解析: \"$target\"（期望格式 yyyy-MM-dd_HH:mm:ss.SSS，" +
                    "亦容忍空格分隔与省略毫秒；亦容忍裸 MM-dd HH:mm:ss.SSS——xlog 自产明文行首同款，" +
                    "从日志直接复制即可，仅对裸格式日志保证正确，年份格式/跨年日志请补全年份）"
            )

        val startTime = targetTime.minusMinutes(rangeMinute)
        val endTime = targetTime.plusMinutes(rangeMinute)

        val fileName = file.nameWithoutExtension

        val outputFile = File(
            file.parent ?: ".",
            "$fileName.filter-time_${outputFileName(startTime, endTime)}.txt"
        )

        if (outputFile.exists() && outputFile.isFile) {
            return ToolsResult.Failure("$outputFile 已存在, 不需要再次进行处理")
        }

        try {
            val stats = filterByTime(
                inputFile = file,
                outputFile = outputFile,
                startTime = startTime,
                endTime = endTime,
                currentYear = targetTime.year.toString()
            )
            if (stats.timeLines == 0L) {
                // 无任何时间锚点 = 输出 0 行的静默失效场景：产物直接删除，改报失败
                outputFile.delete()
                return ToolsResult.Failure(
                    "未在文件中找到任何可识别的时间戳行（期望行首 MM-dd HH:mm:ss.SSS 或 " +
                        "yyyy-MM-dd HH:mm:ss.SSS），输出已丢弃——请确认文件为解码后的明文日志"
                )
            }
            if (stats.writtenLines == 0L && stats.timeLines > 0L) {
                // 区间整体越界（时间行存在但均不在窗口内）= 空产物假成功场景：产物删除，改报失败
                outputFile.delete()
                return ToolsResult.Failure(
                    "区间未命中任何时间行（时间戳行 ${stats.timeLines} 行均不在区间内）——" +
                        "请核对起止时间，年份格式/跨年日志请补全年份"
                )
            }
            return ToolsResult.Success(outputFile)
        } catch (e: Throwable) {
            // 零日志门面依赖（fat jar 纪律）：统一走 System.err 单行结构化输出，异常链逐层展开
            ToolsLog.reportFailure("LogTimeFilter", e)
            return ToolsResult.Failure(reason = "执行失败, message: ${e.message}")
        }
    }

    /**
     * 按起止区间截取日志（用户手里是起止时刻而非中点；对称窗口整数分钟口径
     * 表达不了奇数分钟区间）。产物与失败语义逐条镜像 [filterByTime] 对称窗口包装：
     * 容错解析、产物名 `filter-time_HHmm-HHmm` 同款、已存在拒绝、
     * 零时间锚点与零命中静默失效删产物报失败。裸格式年份锚定取 startTime 年份。
     *
     * @param startTime 格式 yyyy-MM-dd_HH:mm:ss.SSS（亦容忍空格分隔与省略毫秒）；
     *   亦容忍裸 MM-dd HH:mm:ss.SSS（xlog 自产明文行首同款，从日志直接复制即可）。
     *   裸形态仅对裸格式日志保证正确；年份格式日志（外部解码器产物）与跨年边界的
     *   日志请补全年份
     * @param endTime 同 [startTime]；早于 [startTime] 报错
     */
    fun filterByRange(file: File, startTime: String, endTime: String): ToolsResult {
        val start: LocalDateTime = LogTimestamp.parseTarget(startTime)
            ?: return ToolsResult.Failure(
                "起始时间无法解析: \"$startTime\"（期望格式 yyyy-MM-dd_HH:mm:ss.SSS，" +
                    "亦容忍空格分隔与省略毫秒；亦容忍裸 MM-dd HH:mm:ss.SSS——xlog 自产明文行首同款，" +
                    "从日志直接复制即可，仅对裸格式日志保证正确，年份格式/跨年日志请补全年份）"
            )
        val end: LocalDateTime = LogTimestamp.parseTarget(endTime)
            ?: return ToolsResult.Failure(
                "结束时间无法解析: \"$endTime\"（期望格式 yyyy-MM-dd_HH:mm:ss.SSS，" +
                    "亦容忍空格分隔与省略毫秒；亦容忍裸 MM-dd HH:mm:ss.SSS——xlog 自产明文行首同款，" +
                    "从日志直接复制即可，仅对裸格式日志保证正确，年份格式/跨年日志请补全年份）"
            )
        if (end.isBefore(start)) {
            return ToolsResult.Failure("结束时间早于起始时间: \"$endTime\" < \"$startTime\"")
        }

        val fileName = file.nameWithoutExtension

        val outputFile = File(
            file.parent ?: ".",
            "$fileName.filter-time_${outputFileName(start, end)}.txt"
        )

        if (outputFile.exists() && outputFile.isFile) {
            return ToolsResult.Failure("$outputFile 已存在, 不需要再次进行处理")
        }

        try {
            val stats = filterByTime(
                inputFile = file,
                outputFile = outputFile,
                startTime = start,
                endTime = end,
                currentYear = start.year.toString()
            )
            if (stats.timeLines == 0L) {
                // 无任何时间锚点 = 输出 0 行的静默失效场景：产物直接删除，改报失败
                outputFile.delete()
                return ToolsResult.Failure(
                    "未在文件中找到任何可识别的时间戳行（期望行首 MM-dd HH:mm:ss.SSS 或 " +
                        "yyyy-MM-dd HH:mm:ss.SSS），输出已丢弃——请确认文件为解码后的明文日志"
                )
            }
            if (stats.writtenLines == 0L && stats.timeLines > 0L) {
                // 区间整体越界（时间行存在但均不在区间内）= 空产物假成功场景：产物删除，改报失败
                outputFile.delete()
                return ToolsResult.Failure(
                    "区间未命中任何时间行（时间戳行 ${stats.timeLines} 行均不在区间内）——" +
                        "请核对起止时间，年份格式/跨年日志请补全年份"
                )
            }
            return ToolsResult.Success(outputFile)
        } catch (e: Throwable) {
            ToolsLog.reportFailure("LogTimeFilter", e)
            return ToolsResult.Failure(reason = "执行失败, message: ${e.message}")
        }
    }

    data class Stats(val timeLines: Long, val writtenLines: Long)

    fun filterByTime(
        inputFile: File,
        outputFile: File,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        currentYear: String,
    ): Stats {

        BufferedReader(InputStreamReader(FileInputStream(inputFile), Charsets.UTF_8), 1024 * 1024).use { reader ->
            BufferedWriter(OutputStreamWriter(FileOutputStream(outputFile), Charsets.UTF_8), 1024 * 1024).use { writer ->
                var line: String?
                var inRange = false
                var timeLines = 0L
                var writtenLines = 0L
                while ((reader.readLine().also { line = it }) != null) {
                    if (line.isNullOrEmpty()) {
                        // 空行原样透传（数据保真），仅不参与时间判定
                        if (inRange) {
                            writer.newLine()
                        }
                        continue
                    }
                    // 先判定后写入：窗口内时间行当行即写，超窗时间行不写入即 break
                    val parsed = LogTimestamp.parseLine(line, currentYear)
                    if (parsed != null) {
                        timeLines++
                        if (!inRange && !parsed.isBefore(startTime)) {
                            inRange = true
                        }
                        if (inRange && parsed.isAfter(endTime)) {
                            break // ✅ 提前结束，提高性能
                        }
                    }
                    if (inRange) {
                        writer.write(line)
                        writer.newLine()
                        writtenLines++
                    }
                }
                return Stats(timeLines = timeLines, writtenLines = writtenLines)
            }
        }
    }

    /** 产物名时间段：`HHmm-HHmm`（分钟补零，如 09:05 → 0905；对齐知识库声明格式） */
    private fun outputFileName(startTime: LocalDateTime, endTime: LocalDateTime): String {
        return "${startTime.hour.toString().padStart(2, '0')}" +
            "${startTime.minute.toString().padStart(2, '0')}" +
            "-" +
            "${endTime.hour.toString().padStart(2, '0')}" +
            "${endTime.minute.toString().padStart(2, '0')}"
    }
}
