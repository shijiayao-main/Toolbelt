package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * 投屏录像真机 E2E runner（门控：`SCRCPY_E2E_RECORD` 环境变量未设置时 assume 跳过，
 * 本地/CI 恒绿）。驱动生产引擎（全默认接缝零注入）对真机走 起投屏 → 起录像
 * （reset_video 即时启动）→ 采样录制 → 停止落盘全链，核录像面验收：
 * 产物落盘可读、帧数与 mux 写入数对账（[ScreencastRecordingOutcome.Saved.frameCount]）。
 * 产物「可播放」的容器级校验（ffprobe codec/时长/帧数）由验收方对打印出的产物路径执行。
 *
 * 环境变量：`SCRCPY_SERIAL`（默认取 adb devices 首个 device）、`SCRCPY_ADB`（默认裸 adb）、
 * `SCRCPY_RECORD_SECONDS`（录制时长秒数，默认 5）、`SCRCPY_RECORD_OUT`（产物目录，
 * 默认 feature-screencast/build/screencast-recording-e2e）。
 */
class ScreencastRecordingE2eRunner {

    @Test
    fun `device e2e - recording lands mp4 product with frame count reconciliation`() {
        assumeTrue(
            "SCRCPY_E2E_RECORD 未设置——真机录像 E2E 按门控执行，本地默认跳过",
            System.getenv("SCRCPY_E2E_RECORD") != null,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ScreencastEngine(scope)
        try {
            runBlocking {
                val serial = System.getenv("SCRCPY_SERIAL") ?: firstDeviceSerial()
                val adbPath = System.getenv("SCRCPY_ADB") ?: ""
                val seconds = System.getenv("SCRCPY_RECORD_SECONDS")?.toLongOrNull() ?: 5L
                val outDir = File(
                    System.getenv("SCRCPY_RECORD_OUT")
                        ?: "feature-screencast/build/screencast-recording-e2e",
                ).apply { mkdirs() }

                assertTrue(engine.start(adbPath, serial), "引擎必须接受启动（Idle 起始）")
                val streaming = withTimeout(30_000) {
                    engine.status.first { it is ScreencastStatus.Streaming }
                } as ScreencastStatus.Streaming
                println("[e2e] Streaming: 设备名='${streaming.deviceName}' ${streaming.width}x${streaming.height}")
                withTimeout(15_000) { engine.frame.first { it != null } }

                // 起录像：reset_video 即时启动——Pending → Active 应在关键帧到达即刻完成
                assertEquals(null, engine.startRecording(outDir), "起录应受理（Streaming 中）")
                val active = withTimeout(15_000) {
                    engine.recordingState.first { it is ScreencastRecordingState.Active }
                } as ScreencastRecordingState.Active
                println("[e2e] 录像中: ${active.file.absolutePath}")

                delay(seconds * 1_000)
                val outcome = engine.stopRecording()
                assertIs<ScreencastRecordingOutcome.Saved>(outcome)
                assertTrue(outcome.frameCount >= 1, "录制期至少落 1 帧（reset 后 IDR 必到）")
                assertTrue(outcome.file.isFile && outcome.file.length() > 0, "产物文件应落盘非空")
                println(
                    "[e2e] 录像产物: ${outcome.file.absolutePath} " +
                        "frames=${outcome.frameCount} size=${outcome.file.length()}B",
                )

                // 会话收口：投屏照常停止（录像面不牵连画面面）
                engine.stop()
                withTimeout(30_000) { engine.status.first { it is ScreencastStatus.Stopped } }
                println("[e2e] 会话已收口")
            }
        } finally {
            scope.cancel()
        }
    }

    private fun firstDeviceSerial(): String {
        val result = kotlinx.coroutines.runBlocking {
            com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell.execute("adb", "devices")
        }
        val output = result.getOrElse {
            error("adb devices 执行失败：${it.message}")
        }
        val serial = output.stdout.lineSequence()
            .map { it.substringBefore("\t").trim() }
            .firstOrNull { it.isNotEmpty() && it != "List of devices" }
            ?: error("adb devices 无在线设备")
        return serial
    }
}
