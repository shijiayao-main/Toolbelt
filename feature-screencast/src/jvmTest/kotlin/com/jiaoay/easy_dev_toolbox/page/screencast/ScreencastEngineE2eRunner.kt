package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell
import com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue

/**
 * 投屏生产引擎真机 E2E runner（门控：`SCRCPY_E2E` 环境变量未设置时 assume 跳过，
 * 本地/CI 恒绿）。与 spike runner（协议链验证面）不同：本面驱动**生产引擎本体**
 * （SimpleShell/StreamingShell/真实 listener/JavaCV 解码器全默认接缝，零注入）走
 * deploy → 隧道 → 握手 → demuxer → JavaCV 解码 → 帧流发布全链，并核 Major-3
 * 生命周期收口（设备端 app_process 消失 + 隧道规则清空）。
 *
 * forward 隧道用例第二道门控 `SCRCPY_E2E_FORWARD`：生产引擎以
 * [TunnelPreference.ForwardOnly] 偏好驱动 forward 隧道形态
 * （探活字节段 connectForward + forward 规则清理面）——出图即探活首字节 0x00
 * 已消费的证据（零探活即连接失败进显式失败终态）。
 *
 * 环境变量：`SCRCPY_SERIAL`（默认取 adb devices 首个 device）、`SCRCPY_ADB`（默认裸
 * adb）、`SCRCPY_E2E_SECONDS`（帧采样秒数，默认 8）、`SCRCPY_E2E_OUT`（PNG 落盘目录，
 * 默认 build/screencast-e2e）。命令序列与判据核对表见投屏 spike 判据档案
 * scrcpy-spike-runbook.md。
 */
class ScreencastEngineE2eRunner {

    @Test
    fun `device e2e - production engine full chain and lifecycle`() {
        assumeTrue(
            "SCRCPY_E2E 未设置——真机 E2E 按门控执行，本地默认跳过",
            System.getenv("SCRCPY_E2E") != null,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ScreencastEngine(scope)
        try {
            runBlocking {
                val serial = System.getenv("SCRCPY_SERIAL") ?: firstDeviceSerial()
                val adbPath = System.getenv("SCRCPY_ADB") ?: ""
                val seconds = System.getenv("SCRCPY_E2E_SECONDS")?.toIntOrNull() ?: 8
                val outDir = File(
                    System.getenv("SCRCPY_E2E_OUT")
                        ?: "feature-screencast/build/screencast-e2e",
                ).apply { mkdirs() }

                assertTrue(engine.start(adbPath, serial), "引擎必须接受启动（Idle 起始）")
                val streaming = withTimeout(30_000) {
                    engine.status.first { it is ScreencastStatus.Streaming }
                } as ScreencastStatus.Streaming
                println("[e2e] Streaming: 设备名='${streaming.deviceName}' ${streaming.width}x${streaming.height}")

                val firstFrame = withTimeout(15_000) {
                    engine.frame.first { it != null }
                }
                assertNotNull(firstFrame)
                println("[e2e] 首帧已发布（世代 ${firstFrame.generation}）")
                writePng(firstFrame, File(outDir, "frame-001.png"))

                // 帧流采样：StateFlow 合帧保最新（消费计数为合帧后下界，引擎 fps 为引擎侧真值）
                var conflatedUpdates = 0
                withTimeoutOrNull(seconds * 1_000L) {
                    engine.frame.collect { if (it != null) conflatedUpdates++ }
                }
                println("[e2e] ${seconds}s 采样：合帧后消费 $conflatedUpdates 次，引擎 fps=${engine.fps.value}")

                engine.stop()
                val stopped = withTimeout(10_000) {
                    engine.status.first { it is ScreencastStatus.Stopped }
                } as ScreencastStatus.Stopped
                assertEquals("已停止", stopped.message)
                assertFalse(stopped.failed)
                assertTrue(engine.frame.value == null, "停止后帧流清屏（世代防复活收口）")
                println("[e2e] 停止收口：${stopped.message}、帧已清")

                // Major-3 验收点 1/2：设备端进程消失 + 隧道规则清空
                val adb = SimpleShellScrcpyAdbGateway(adbPath = adbPath, serial = serial)
                val ps = adb.execute("adb shell ps -A").getOrNull()?.stdout.orEmpty()
                assertTrue(
                    "com.genymobile.scrcpy.Server" !in ps,
                    "Major-3 验收失败：设备端 app_process 残留",
                )
                val forwardList = adb.execute("adb forward --list").getOrNull()?.stdout.orEmpty()
                val reverseList = adb.execute("adb reverse --list").getOrNull()?.stdout.orEmpty()
                assertTrue("scrcpy_" !in forwardList, "Major-3 验收失败：forward 规则残留: $forwardList")
                assertTrue("scrcpy_" !in reverseList, "Major-3 验收失败：reverse 规则残留: $reverseList")
                println("[e2e] Major-3 验收点 1/2 通过：app_process 进程消失、隧道规则清空")
            }
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    /**
     * forward 隧道真机 E2E（双门控 `SCRCPY_E2E` + `SCRCPY_E2E_FORWARD`）：ForwardOnly 偏好
     * 驱动 forward 隧道全链——Streaming 到达即探活首字节 0x00 段（connectForward）消费
     * 成功的证据（零探活字节 = 连接失败进显式失败终态，到不了 Streaming）；停止后核
     * forward 规则清理面（Major-3 验收点）。serve 命令的 tunnel_forward=true 由
     * [ScreencastEngine] 按隧道形态同源生成，无需本用例注入。
     */
    @Test
    fun `device e2e - forward tunnel full chain and rule cleanup`() {
        assumeTrue(
            "SCRCPY_E2E 未设置——真机 E2E 按门控执行，本地默认跳过",
            System.getenv("SCRCPY_E2E") != null,
        )
        assumeTrue(
            "SCRCPY_E2E_FORWARD 未设置——forward 隧道形态按第二道门控执行，默认跳过",
            System.getenv("SCRCPY_E2E_FORWARD") != null,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ScreencastEngine(scope)
        try {
            runBlocking {
                val serial = System.getenv("SCRCPY_SERIAL") ?: firstDeviceSerial()
                val adbPath = System.getenv("SCRCPY_ADB") ?: ""
                val seconds = System.getenv("SCRCPY_E2E_SECONDS")?.toIntOrNull() ?: 8
                val outDir = File(
                    System.getenv("SCRCPY_E2E_OUT")
                        ?: "feature-screencast/build/screencast-e2e",
                ).apply { mkdirs() }

                assertTrue(
                    engine.start(
                        adbPath,
                        serial,
                        tunnelPreference = TunnelPreference.ForwardOnly,
                    ),
                    "引擎必须接受启动（Idle 起始）",
                )
                val streaming = withTimeout(30_000) {
                    engine.status.first { it is ScreencastStatus.Streaming }
                } as ScreencastStatus.Streaming
                println("[e2e-fwd] Streaming（forward 隧道，探活字节段已消费）: 设备名='${streaming.deviceName}' ${streaming.width}x${streaming.height}")

                val firstFrame = withTimeout(15_000) {
                    engine.frame.first { it != null }
                }
                assertNotNull(firstFrame)
                println("[e2e-fwd] 首帧已发布（世代 ${firstFrame.generation}）")
                writePng(firstFrame, File(outDir, "frame-forward-001.png"))

                var conflatedUpdates = 0
                withTimeoutOrNull(seconds * 1_000L) {
                    engine.frame.collect { if (it != null) conflatedUpdates++ }
                }
                println("[e2e-fwd] ${seconds}s 采样：合帧后消费 $conflatedUpdates 次，引擎 fps=${engine.fps.value}")

                engine.stop()
                val stopped = withTimeout(10_000) {
                    engine.status.first { it is ScreencastStatus.Stopped }
                } as ScreencastStatus.Stopped
                assertEquals("已停止", stopped.message)
                assertFalse(stopped.failed)
                assertTrue(engine.frame.value == null, "停止后帧流清屏（世代防复活收口）")
                println("[e2e-fwd] 停止收口：${stopped.message}、帧已清")

                // Major-3 验收点（forward 规则清理面）：进程消失 + 双向规则清空
                val adb = SimpleShellScrcpyAdbGateway(adbPath = adbPath, serial = serial)
                val ps = adb.execute("adb shell ps -A").getOrNull()?.stdout.orEmpty()
                assertTrue(
                    "com.genymobile.scrcpy.Server" !in ps,
                    "Major-3 验收失败：设备端 app_process 残留",
                )
                val forwardList = adb.execute("adb forward --list").getOrNull()?.stdout.orEmpty()
                val reverseList = adb.execute("adb reverse --list").getOrNull()?.stdout.orEmpty()
                assertTrue("scrcpy_" !in forwardList, "Major-3 验收失败：forward 规则残留: $forwardList")
                assertTrue("scrcpy_" !in reverseList, "Major-3 验收失败：reverse 规则残留: $reverseList")
                println("[e2e-fwd] Major-3 验收点通过：app_process 进程消失、隧道规则清空")
            }
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    /** 合帧帧落盘 PNG（人审证据；ImageBitmap 逐像素回 BufferedImage） */
    private fun writePng(frame: ScreencastFrame, target: File) {
        runCatching {
            val bitmap = frame.image
            val pixelMap = bitmap.toPixelMap()
            val buffered = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    buffered.setRGB(x, y, pixelMap[x, y].toArgb())
                }
            }
            ImageIO.write(buffered, "png", target)
            println("[e2e] PNG 落盘: ${target.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure { println("[e2e] PNG 落盘失败（不阻断判据）: ${it.message}") }
    }

    private suspend fun firstDeviceSerial(): String {
        val command = AdbCommandRewriter.rewrite("adb devices", "", serial = null)
        val stdout = SimpleShell.execute(command).getOrNull()?.stdout.orEmpty()
        return stdout.lineSequence()
            .filter { "\tdevice" in it }
            .map { it.substringBefore('\t').trim() }
            .firstOrNull()
            ?: throw IllegalStateException("adb devices 无在线设备——真机 E2E 需设备在线")
    }
}
