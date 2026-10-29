package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/**
 * 快照内容等值单测（真 Skia 位图 + 引擎真链路——ScreencastHarness fake 只替换
 * socket/adb 面，快照路径 [ScreencastEngine.snapshot] 全真）：快照的**预览帧回退
 * 路径** PNG 像素必须等于最新发布帧的内容——default fake 网关下设备实拍因 pull
 * 无产物判失败回退（实拍真源路径的逐字节等值由 ScreencastSnapshotTest 的
 * pull 模拟器用例钉死）。既单测只验尺寸与回执落流，内容面从未钉死；发布多帧
 * （红→绿→蓝）后快照，PNG 读回须为蓝帧而非任何旧帧。
 */
class ScreencastSnapshotContentTest {

    /** 纯色实帧（真 Skia 位图：N32 OPAQUE + erase 落色，走引擎真实像素读路径） */
    private fun solidFrame(argb: Int): ScreencastDecodedFrame {
        val bitmap = Bitmap()
        assertTrue(
            bitmap.allocPixels(ImageInfo.makeN32(SOLID, SOLID, ColorAlphaType.OPAQUE)),
            "测试位图物化失败",
        )
        bitmap.erase(argb)
        return ScreencastDecodedFrame(
            image = bitmap.asComposeImageBitmap(),
            width = SOLID,
            height = SOLID,
        )
    }

    @Test
    fun `snapshot png content equals latest published frame`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitStreaming()

            // 依序发布红→绿→蓝三帧（每帧都等到 StateFlow 落值，发布序确定）
            val red = solidFrame(COLOR_RED)
            harness.decoder.offer(red)
            harness.engine.frame.awaitImage(red.image)
            val green = solidFrame(COLOR_GREEN)
            harness.decoder.offer(green)
            harness.engine.frame.awaitImage(green.image)
            val blue = solidFrame(COLOR_BLUE)
            harness.decoder.offer(blue)
            harness.engine.frame.awaitImage(blue.image)

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            val saved = assertIs<ScreencastSnapshotOutcome.Saved>(outcome)
            val image = ImageIO.read(saved.file) ?: error("落盘 PNG 不可经 ImageIO 读回")
            assertEquals(
                COLOR_BLUE,
                image.getRGB(SOLID / 2, SOLID / 2),
                "PNG 内容须等于最新发布帧（蓝）——读到旧帧（红/绿）即快照链路取了陈旧画面",
            )
            assertTrue(
                harness.adb.commands.any { "adb shell screencap -p" in it },
                "设备实拍须先于回退被尝试（主路径形态锁）",
            )
            dir.deleteRecursively()
        }
    }

    @Test
    fun `snapshot after continued publishing still matches the then-latest frame`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitStreaming()

            // 旧帧打底后再持续发布同轮转多帧（蓝系渐深），快照落在最后一帧发布之后
            val red = solidFrame(COLOR_RED)
            harness.decoder.offer(red)
            harness.engine.frame.awaitImage(red.image)
            val shades = (1..6).map { i -> solidFrame(shadeBlue(i)) }
            shades.forEach { frame ->
                harness.decoder.offer(frame)
                harness.engine.frame.awaitImage(frame.image)
            }

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            val saved = assertIs<ScreencastSnapshotOutcome.Saved>(outcome)
            val image = ImageIO.read(saved.file) ?: error("落盘 PNG 不可经 ImageIO 读回")
            assertEquals(
                shadeBlue(shades.size),
                image.getRGB(SOLID / 2, SOLID / 2),
                "多帧连发后快照仍须等于发布面最后一帧——读到任何更早帧即陈旧画面",
            )
            dir.deleteRecursively()
        }
    }

    private fun shadeBlue(index: Int): Int =
        (0xFF000000.toInt()) or (0x000030 * index and 0xFF)

    private fun freshDir(): File = createTempDirectory("screencast-snapshot-content").toFile()

    private suspend fun StateFlow<ScreencastStatus>.awaitStreaming() {
        withTimeout(5_000) { first { it is ScreencastStatus.Streaming } }
    }

    private suspend fun StateFlow<ScreencastFrame?>.awaitImage(target: ImageBitmap) {
        withTimeout(5_000) { first { it?.image === target } }
    }

    private companion object {
        const val SOLID = 8
        val COLOR_RED = 0xFFFF0000.toInt()
        val COLOR_GREEN = 0xFF00FF00.toInt()
        val COLOR_BLUE = 0xFF0000FF.toInt()
    }
}
