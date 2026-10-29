// pipeline 子命令 CLI 层退出码契约：成功跑完必须退出码 0（parse 不抛）——
// 脚本化消费方（agent 判据/CI）按退出码判定成败，成功路径非零退出即每次成功被判失败
package com.jiaoay.log.pipeline

import com.github.ajalt.clikt.core.parse
import com.jiaoay.log.cli.LogUtilsCommand
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class PipelineCliExitCodeTest {

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8.name()))
        return try {
            block()
            buffer.toString(Charsets.UTF_8.name())
        } finally {
            System.setOut(original)
        }
    }

    private fun tempLog(content: String, name: String = "pipeline_cli.txt"): File =
        kotlin.io.path.createTempDirectory("edt-pipeline-cli").resolve(name).toFile()
            .apply { writeText(content) }

    @Test
    fun `pipeline 成功路径退出码为 0`() {
        val log = tempLog(
            listOf(
                "09-19 10:29:50.000 D/FloorCenterManager: init ok",
                "09-19 10:30:05.000 E/CameraManager: camera route failed",
                "09-19 10:30:10.000 W/CameraManager: retry 1",
                "09-19 10:30:15.000 W/CameraManager: retry 2",
                "09-19 10:30:20.000 W/CameraManager: retry 3",
                "09-19 10:30:25.000 D/CameraManager: done",
            ).joinToString("\n") + "\n",
        )
        // parse 不抛 = 退出码 0（与其余子命令成功路径同口径——非零退出码仅承载中止）
        val output = captureStdout {
            LogUtilsCommand().parse(
                listOf(
                    "--type", "pipeline",
                    "-f", log.absolutePath,
                    "--targetTime", "2026-09-19_10:30:00.000",
                    "--range", "5",
                    "--tagRegex", "CameraManager|FloorCenterManager",
                    "--tagName", "camera",
                ),
            )
        }
        assertTrue("success! stage=" in output, "成功锚经 stdout 呈现: $output")
        assertTrue("operation failed" !in output, "成功路径不得再打 operation failed: $output")
    }
}
