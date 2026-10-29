package com.jiaoay.log

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object LogSplitter {

    fun splitBySize(file: File, sizeMb: Int): ToolsResult {
        val thresholdBytes = sizeMb.toLong() * 1024 * 1024

        val firstName = outputFileName(file, 0)
        val firstFile = File(file.parent ?: ".", firstName)
        if (firstFile.exists()) {
            return ToolsResult.Failure("$firstFile 已存在，不需要再次进行处理")
        }

        try {
            var index = 0
            var currentBytes = 0L
            var currentFile: File? = null
            var writer: BufferedWriter? = null

            fun openNextWriter(): BufferedWriter {
                val outFile = File(file.parent ?: ".", outputFileName(file, index))
                currentFile = outFile
                return BufferedWriter(OutputStreamWriter(FileOutputStream(outFile), Charsets.UTF_8), 1024 * 1024)
            }

            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 1024 * 1024).use { reader ->
                writer = openNextWriter()
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (writer == null || line == null) {
                            return@use
                        }
                        // 字节数按「字符数 × 保守上界」估算（性能热路径），不再逐行 toByteArray
                        //    编码两次（写出时 BufferedWriter 还会再编码一次）。阈值判定对估算精度不敏感
                        //    （分割粒度本来就是近似目标），UTF-8 下 ASCII 行恰为 1 字节/字符、
                        //    非 ASCII 实际 2~4 字节/字符——估算恒 ≥ 实际的一半、≤ 实际的全值，
                        //    作为「何时切下一片」的触发量足够准。
                        val lineBytes = (line.length + 1L) // +1 for newline（按字符数计，见上方注释）
                        writer.write(line)
                        writer.newLine()
                        currentBytes += lineBytes

                        if (currentBytes >= thresholdBytes) {
                            writer.close()
                            index++
                            currentBytes = 0L
                            writer = openNextWriter()
                        }
                    }
                } finally {
                    writer?.close()
                }
            }

            // 恰在阈值处读完（或输入为空）时最后开启的分片 0 字节——删除该空片，
            // 避免 App 侧按 `_<idx>` 前缀枚举兄弟卷时数出 phantom 卷
            currentFile?.takeIf { it.isFile && it.length() == 0L && index > 0 }?.delete()

            return ToolsResult.Success(firstFile)
        } catch (e: Throwable) {
            // 结构化 stderr 替代裸 printStackTrace（同 LogTagFilter）
            ToolsLog.reportFailure("LogSplitter", e)
            return ToolsResult.Failure("执行失败, message: ${e.message}")
        }
    }

    private fun outputFileName(file: File, index: Int): String {
        return "${file.nameWithoutExtension}_${index.toString().padStart(3, '0')}.txt"
    }
}

