package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 画面快照单测（ScreencastHarness fake 链路，落盘路径注入临时目录——不触用户数据根）：
 * 无帧显式失败不落空文件、有帧落盘 PNG 可经 ImageIO 读回且尺寸与帧一致、
 * 停止后旧帧不复活落盘（世代闸收口）。
 *
 * snapshotResult 落流用例（回执单源契约——窗内回执行与面板消费同一持态流，缺早退
 * 落流即窗内静默）：确定性驱动五路（Saved/无帧/世代闸/目录创建失败/落盘异常）+
 * 新会话起点清与 Stopped 终态保留。「非 Streaming」防御分支（帧在但状态机已离
 * Streaming——start 清帧与状态写入同在 stateLock 内原子完成，常规调用面不可达）按
 * 裁决以 [ScreencastEngine.snapshotOutcome] 的 KDoc 契约注释 + 代码审读收口，不开
 * 注入缝不为防御分支增加生产测试面。
 */
class ScreencastSnapshotTest {

    @Test
    fun `snapshot without frame fails explicitly without writing file`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            val failed = assertIs<ScreencastSnapshotOutcome.Failed>(outcome)
            assertTrue("无画面帧" in failed.reason, "失败回执须明示无帧真因（不静默）")
            assertTrue(dir.listFiles().isNullOrEmpty(), "失败路径不得落任何文件")
            dir.deleteRecursively()
        }
    }

    @Test
    fun `snapshot writes readable png matching frame dimensions`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            harness.decoder.offer(ScreencastDecodedFrame(image = ImageBitmap(32, 24), width = 32, height = 24))
            harness.engine.frame.awaitUntil { it != null }

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            val saved = assertIs<ScreencastSnapshotOutcome.Saved>(outcome)
            val image = ImageIO.read(saved.file) ?: error("落盘文件不可经 ImageIO 读回（非合法 PNG）")
            assertEquals(32, image.width, "PNG 读回宽须与帧一致")
            assertEquals(24, image.height, "PNG 读回高须与帧一致")
            assertTrue(
                saved.file.name.startsWith("TestDevice"),
                "文件名须含设备名（多设备并存不互覆），实际：${saved.file.name}",
            )
            assertTrue(saved.file.name.endsWith(".png"))
            dir.deleteRecursively()
        }
    }

    @Test
    fun `snapshot after stop does not revive the stale frame`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
            harness.decoder.offer(ScreencastDecodedFrame(image = ImageBitmap(8, 8), width = 8, height = 8))
            harness.engine.frame.awaitUntil { it != null }

            harness.engine.stop()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            assertIs<ScreencastSnapshotOutcome.Failed>(outcome, "停止后旧帧不得复活落盘（世代闸收口）")
            assertTrue(dir.listFiles().isNullOrEmpty())
            dir.deleteRecursively()
        }
    }

    // ── 设备端实拍真源（主路径）与预览帧回退 ──────────────────────────────────────

    @Test
    fun `device capture is the source of truth and pulls device png`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            startStreamingWithFrame(harness)

            // 模拟设备端 screencap 产物：真实合法 PNG（与预览帧 8×8 不同——真源断言有效性前提）
            val deviceImage = BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB)
            deviceImage.setRGB(2, 1, 0xFF00FF00.toInt())
            val devicePng = ByteArrayOutputStream().also {
                ImageIO.write(deviceImage, "png", it)
            }.toByteArray()
            harness.adb.pullSimulator = { _, local -> File(local).writeBytes(devicePng) }

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            val saved = assertIs<ScreencastSnapshotOutcome.Saved>(outcome)
            assertTrue(
                devicePng.contentEquals(saved.file.readBytes()),
                "快照产物须逐字节等于设备端实拍 PNG（真源=设备实拍，非预览帧重编码）",
            )
            val commands = harness.adb.commands.toList()
            assertTrue(
                commands.any { "adb shell screencap -p /sdcard/toolbelt_snap_" in it },
                "实拍第一步须向设备端 screencap：$commands",
            )
            assertTrue(
                commands.any { it.startsWith("adb pull /sdcard/toolbelt_snap_") && saved.file.name in it },
                "实拍第二步须把设备端产物 pull 到本地产物路径：$commands",
            )
            assertTrue(
                commands.any { "adb shell rm -f /sdcard/toolbelt_snap_" in it },
                "实拍收尾须清理设备端临时文件（尽力语义但命令必发）：$commands",
            )
            dir.deleteRecursively()
        }
    }

    @Test
    fun `device capture failure falls back to preview frame png`(): Unit = runBlocking {
        ScreencastHarness(failOn = Regex("screencap")).use { harness ->
            startStreamingWithFrame(harness)

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            val saved = assertIs<ScreencastSnapshotOutcome.Saved>(outcome)
            val image = ImageIO.read(saved.file) ?: error("回退产物不可经 ImageIO 读回")
            assertEquals(8, image.width, "实拍失败须回退预览帧（产物=帧内容，8×8）")
            assertEquals(8, image.height, "回退产物高须与帧一致")
            dir.deleteRecursively()
        }
    }

    @Test
    fun `pulled non-png payload falls back to preview frame`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            startStreamingWithFrame(harness)

            // pull「成功」但产物非 PNG（错误页/空壳）——产物校验把关，不让坏件冒充实拍
            harness.adb.pullSimulator = { _, local -> File(local).writeBytes("not-a-png".toByteArray()) }

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            val saved = assertIs<ScreencastSnapshotOutcome.Saved>(outcome)
            val image = ImageIO.read(saved.file) ?: error("回退产物不可经 ImageIO 读回")
            assertEquals(8, image.width, "非 PNG 实拍载荷须判失败回退预览帧（不落坏件）")
            dir.deleteRecursively()
        }
    }

    // ── snapshotResult 落流（回执单源：全部返回路径统一落流，窗/面板消费同一持态） ──

    /** Streaming 态 + 注入一帧（快照产物面的最小前置） */
    private suspend fun startStreamingWithFrame(harness: ScreencastHarness) {
        harness.startAndHandshake()
        harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }
        harness.decoder.offer(ScreencastDecodedFrame(image = ImageBitmap(8, 8), width = 8, height = 8))
        harness.engine.frame.awaitUntil { it != null }
    }

    @Test
    fun `saved path lands receipt in engine flow`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            startStreamingWithFrame(harness)

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            val saved = assertIs<ScreencastSnapshotOutcome.Saved>(outcome)
            assertEquals(
                outcome,
                harness.engine.snapshotResult.value,
                "Saved 须落引擎回执流（窗内回执行由流承接——不落流即窗内静默）",
            )
            assertTrue(saved.file.isFile, "回执产物必须真实在盘")
            dir.deleteRecursively()
        }
    }

    @Test
    fun `no-frame early exit lands failed receipt in engine flow`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            harness.startAndHandshake()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Streaming }

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            val failed = assertIs<ScreencastSnapshotOutcome.Failed>(outcome)
            assertTrue("无画面帧" in failed.reason, "无帧早退真因须进回执（与面板零帧判据同源）")
            assertEquals(
                outcome,
                harness.engine.snapshotResult.value,
                "无帧早退 Failed 也须落流（窗内「等待画面态按快照」的失败呈现由流承接）",
            )
            assertTrue(dir.listFiles().isNullOrEmpty(), "失败路径不得落任何文件")
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stale frame after stop lands generation-gate failure in flow`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            startStreamingWithFrame(harness)

            // 确定性：offer 已耗尽，解码循环阻塞在 grab 的 100ms poll——stop() 返回时
            // 收尾清帧最早发生在 poll 超时后，此刻快照帧仍在且 stopRequested 已落 =
            // isPublishable false 恰走世代闸路径（非竞态凑形态：时钟量级隔离 ~100ms）
            harness.engine.stop()
            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            val failed = assertIs<ScreencastSnapshotOutcome.Failed>(outcome)
            assertTrue(
                "投屏已收口——旧帧不复活" in failed.reason,
                "世代闸路径真因须进回执（非无帧/非落盘措辞）：${failed.reason}",
            )
            assertEquals(outcome, harness.engine.snapshotResult.value, "世代闸 Failed 须落流")
            assertTrue(dir.listFiles().isNullOrEmpty())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `directory create failure lands failed receipt in engine flow`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            startStreamingWithFrame(harness)

            // 路径被同名词件占用：isDirectory=false 且 mkdirs=false——目录创建失败路
            val occupied = File(freshDir(), "occupied").apply { assertTrue(createNewFile()) }
            val outcome = harness.engine.snapshot(occupied)
            val failed = assertIs<ScreencastSnapshotOutcome.Failed>(outcome)
            assertTrue("截图目录创建失败" in failed.reason, "目录失败真因须进回执：${failed.reason}")
            assertEquals(outcome, harness.engine.snapshotResult.value, "目录创建失败 Failed 须落流")
            occupied.delete()
            occupied.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `disk write failure lands catch-branch receipt in engine flow`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            startStreamingWithFrame(harness)

            // 只读目录（isDirectory=true 过目录闸，写盘抛 IOException）——catch 分支路
            val readOnly = freshDir().apply { setWritable(false) }
            val outcome = harness.engine.snapshot(readOnly)
            val failed = assertIs<ScreencastSnapshotOutcome.Failed>(outcome)
            assertTrue("快照落盘失败" in failed.reason, "写盘异常真因须进回执：${failed.reason}")
            assertEquals(outcome, harness.engine.snapshotResult.value, "catch 分支 Failed 须落流")
            readOnly.deleteRecursively()
        }
    }

    @Test
    fun `receipt survives stopped terminal and clears on next session start`(): Unit = runBlocking {
        ScreencastHarness().use { harness ->
            startStreamingWithFrame(harness)

            val dir = freshDir()
            val outcome = harness.engine.snapshot(dir)
            assertIs<ScreencastSnapshotOutcome.Saved>(outcome)

            harness.engine.stop()
            harness.engine.status.awaitUntil { it is ScreencastStatus.Stopped }
            assertEquals(
                outcome,
                harness.engine.snapshotResult.value,
                "Stopped 终态不清快照回执（拍→停→打开/送出的动线不因停投失去回执行）",
            )

            // 新会话起点（start 清帧区同点）清上一会话回执
            harness.engine.start("", "TEST-SERIAL-2")
            assertEquals(ScreencastStatus.Connecting, harness.engine.status.value)
            assertNull(harness.engine.snapshotResult.value, "新会话起点须清上一会话快照回执")
            dir.deleteRecursively()
        }
    }

    private fun freshDir(): File = createTempDirectory("screencast-snapshot").toFile()

    private suspend fun <T> StateFlow<T>.awaitUntil(
        timeoutMs: Long = 5_000,
        predicate: (T) -> Boolean,
    ): T = withTimeout(timeoutMs) { first { predicate(it) } }
}
