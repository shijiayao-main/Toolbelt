package com.jiaoay.easy_dev_toolbox.page.screencast

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 位图池契约单测（真 skia Bitmap，免设备）：
 * - 池有界（连续取帧后槽数恒 = 槽数上界，不随帧数增长——native 水位 O(池) 的机械断言面）；
 * - round-robin 延迟复用（一轮内槽互异 + 隔轮回首槽——「显示中的帧不被覆写」的结构保证）；
 * - 槽像素建池一次性物化 + 同槽在位覆写（`install` 收原生地址单趟写入，不换 PixelRef——
 *   像素缓冲地址跨帧恒定、两帧内容各自正确、置脏换代）；
 * - 帧内容等价（同一载荷下在位覆写读回与 `installPixels` 直装产物逐字节一致——
 *   一键拷贝变体的等价性背书）；
 * - 换档整池作废重建 / [ScreencastFrameBitmapPool.clear] 清空不残留。
 * 解码器真帧路径（H.264 解码 → sws → 池装帧）由真机 E2E runner（SCRCPY_E2E 门控）覆盖；
 * sws 输出到入槽的 av_image_copy_to_buffer 接线（含对齐垫行步长）由 ScreencastDecoderTest
 * 的装帧行为面单测背书。
 */
class ScreencastFrameBitmapPoolTest {

    @Test
    fun `slot count stays bounded regardless of frame count`() {
        val pool = ScreencastFrameBitmapPool(SLOT_COUNT)
        repeat(SLOT_COUNT * 10) { pool.acquire(FRAME_WIDTH, FRAME_HEIGHT) }
        assertEquals(
            SLOT_COUNT,
            pool.materializedSlotCount,
            "连续取帧后池内槽数恒 = 槽数上界（不随帧数增长，native 水位有界）",
        )
    }

    @Test
    fun `slot reuse is delayed by a full rotation`() {
        val pool = ScreencastFrameBitmapPool(SLOT_COUNT)
        val rotation = ArrayList<org.jetbrains.skia.Bitmap>(SLOT_COUNT + 1)
        repeat(SLOT_COUNT + 1) { rotation += pool.acquire(FRAME_WIDTH, FRAME_HEIGHT) }
        assertEquals(
            SLOT_COUNT,
            rotation.take(SLOT_COUNT).distinct().size,
            "一轮内各槽互不相同（正在显示的帧不被同轮覆写）",
        )
        assertSame(
            rotation[0],
            rotation[SLOT_COUNT],
            "第 N+1 轮回首槽（round-robin 延迟两代复用：覆写前必有 2 帧其他槽先行发布）",
        )
    }

    @Test
    fun `slot pixels materialize at build and frames overwrite in place`() {
        // 池化前提的语义锚：建池 allocPixels 一次性物化槽像素，帧内容经 install 的原生
        // 地址写入面在位覆写（不换 PixelRef/帧面零重装 churn）——实现若退回「逐帧重装
        // 换缓冲」形态此测试即红（池化方案前提失效）
        val pool = ScreencastFrameBitmapPool(1)
        val info = imageInfo(FRAME_WIDTH, FRAME_HEIGHT)
        val rowBytes = FRAME_WIDTH * 4
        val firstFrame = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 4) { ((it % 4) * 60).toByte() }
        val first = pool.acquire(FRAME_WIDTH, FRAME_HEIGHT)
        assertTrue(
            first.computeByteSize() >= FRAME_WIDTH * FRAME_HEIGHT * 4,
            "建池即物化槽像素（allocPixels 一次性，取槽即可绘）",
        )
        var firstAddr = 0L
        assertTrue(
            pool.install(first) { addr ->
                firstAddr = addr
                nativeWrite(addr, FRAME_WIDTH, FRAME_HEIGHT, firstFrame)
            },
            "首帧经原生地址写入面落位",
        )
        assertTrue(
            firstFrame.contentEquals(first.readPixels(info, rowBytes, 0, 0)),
            "首帧内容可读回（装帧正确）",
        )
        val firstGeneration = first.generationId

        val secondFrame = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 4) { (255 - ((it % 4) * 60)).toByte() }
        assertFalse(secondFrame.contentEquals(firstFrame), "两帧内容确不同（断言有效性前提）")
        val second = pool.acquire(FRAME_WIDTH, FRAME_HEIGHT)
        assertSame(first, second, "单槽池恒复用同一 Bitmap（同槽先后产出两帧）")
        var secondAddr = 0L
        assertTrue(
            pool.install(second) { addr ->
                secondAddr = addr
                nativeWrite(addr, FRAME_WIDTH, FRAME_HEIGHT, secondFrame)
            },
            "次帧同槽在位覆写落位",
        )
        assertTrue(firstAddr != 0L, "写入面拿到有效原生地址（allocPixels 物化面）")
        assertEquals(
            firstAddr,
            secondAddr,
            "像素缓冲地址跨帧恒定（在位覆写不换 PixelRef，帧面零重装 churn）",
        )
        assertTrue(
            secondFrame.contentEquals(second.readPixels(info, rowBytes, 0, 0)),
            "覆写后内容为新帧（同池槽先后产出像素内容各自正确）",
        )
        assertFalse(
            second.generationId == firstGeneration,
            "装帧后置脏换代（notifyPixelsChanged 语义，消费面缓存失效判据）",
        )
    }

    @Test
    fun `in-place native write matches the heap installPixels product`() {
        // 帧内容等价锚：同一载荷下，池槽原生地址在位覆写的读回 = 「堆暂存 + installPixels
        // 直装」参考产物逐字节一致——热路径换轨不改变像素产物
        val pool = ScreencastFrameBitmapPool(1)
        val info = imageInfo(FRAME_WIDTH, FRAME_HEIGHT)
        val rowBytes = FRAME_WIDTH * 4
        val payload = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 4) { ((it * 31 + 7) % 251).toByte() }
        val reference = org.jetbrains.skia.Bitmap()
        assertTrue(reference.installPixels(info, payload, rowBytes), "参考产物经 installPixels 直装落位")
        val slot = pool.acquire(FRAME_WIDTH, FRAME_HEIGHT)
        assertTrue(
            pool.install(slot) { addr -> nativeWrite(addr, FRAME_WIDTH, FRAME_HEIGHT, payload) },
            "池槽经原生地址在位覆写落位",
        )
        val referenceBytes = reference.readPixels(info, rowBytes, 0, 0)
        val slotBytes = pool.readLocked(slot) { it.readPixels(info, rowBytes, 0, 0) }
        assertTrue(
            referenceBytes.contentEquals(slotBytes),
            "同一载荷两条路径产物逐字节一致（池槽像素 = 参考位图像素）",
        )
    }

    @Test
    fun `resolution change rebuilds the pool for the new size`() {
        val pool = ScreencastFrameBitmapPool(SLOT_COUNT)
        val oldSlots = (0 until SLOT_COUNT).map { pool.acquire(FRAME_WIDTH, FRAME_HEIGHT) }
        val newWidth = FRAME_WIDTH * 2
        val newHeight = FRAME_HEIGHT * 2
        val newSlots = (0 until SLOT_COUNT).map { pool.acquire(newWidth, newHeight) }
        assertTrue(
            oldSlots.intersect(newSlots.toSet()).isEmpty(),
            "换档后旧池槽全部作废（分辨率变了像素布局变，旧槽不复用）",
        )
        assertEquals(SLOT_COUNT, pool.materializedSlotCount, "新池按槽数上界重建")
        val newInfo = imageInfo(newWidth, newHeight)
        val payload = ByteArray(newWidth * newHeight * 4) { (it % 11).toByte() }
        assertTrue(
            pool.install(newSlots.first()) { addr -> nativeWrite(addr, newWidth, newHeight, payload) },
            "新槽接受新尺寸在位覆写（池重建与换算上下文换档同步）",
        )
        assertTrue(
            payload.contentEquals(newSlots.first().readPixels(newInfo, newWidth * 4, 0, 0)),
            "新尺寸槽内容读回一致（重建后像素布局正确）",
        )
    }

    @Test
    fun `clear drops all slots and next acquire starts a fresh rotation`() {
        val pool = ScreencastFrameBitmapPool(SLOT_COUNT)
        repeat(SLOT_COUNT) { pool.acquire(FRAME_WIDTH, FRAME_HEIGHT) }
        val beforeClear = (0 until SLOT_COUNT).map { pool.slotAt(it) }
        pool.clear()
        assertEquals(0, pool.materializedSlotCount, "清空后槽数归零（引擎停止防跨会话残留）")
        repeat(SLOT_COUNT) { pool.acquire(FRAME_WIDTH, FRAME_HEIGHT) }
        val afterClear = (0 until SLOT_COUNT).map { pool.slotAt(it) }
        assertTrue(
            beforeClear.intersect(afterClear.toSet()).isEmpty(),
            "清空后重建的槽全部为全新对象（不复活清空前的槽）",
        )
    }

    @Test
    fun `install and readLocked round-trip frame content`() {
        // 装帧/读槽锁 API 的行为面（原生地址写入落位、读得回、覆写换内容）；锁的互斥性本身是
        // 竞态缝，不可确定性测试——契约由池 KDoc 并发条款收口
        val pool = ScreencastFrameBitmapPool(1)
        val info = imageInfo(FRAME_WIDTH, FRAME_HEIGHT)
        val rowBytes = FRAME_WIDTH * 4
        val payload = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 4) { (it % 7).toByte() }
        val slot = pool.acquire(FRAME_WIDTH, FRAME_HEIGHT)
        assertTrue(
            pool.install(slot) { addr -> nativeWrite(addr, FRAME_WIDTH, FRAME_HEIGHT, payload) },
            "装帧经锁 API 落位（原生地址写入）",
        )
        val readBack = pool.readLocked(slot) { it.readPixels(info, rowBytes, 0, 0) }
        assertTrue(payload.contentEquals(readBack), "锁内读回 = 装帧内容")
        val replacement = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 4) { ((it % 7) + 1).toByte() }
        assertTrue(
            pool.install(slot) { addr -> nativeWrite(addr, FRAME_WIDTH, FRAME_HEIGHT, replacement) },
            "覆写经锁 API 落位",
        )
        val readAgain = pool.readLocked(slot) { it.readPixels(info, rowBytes, 0, 0) }
        assertTrue(replacement.contentEquals(readAgain), "覆写后锁内读回 = 新帧内容")
    }

    /**
     * 原生地址写入（测试面）：skia 直达面（`Surface.makeRasterDirect` 包槽地址）原生写入，
     * 与生产 copyBgraFrameToSlotBuffer 同一「经池槽像素缓冲地址落帧」语义（javacpp 自带
     * 的 BytePointer.put 对 address==0 的包装件拒绝，测试面不共用该包装件）。
     */
    private fun nativeWrite(addr: Long, width: Int, height: Int, payload: ByteArray): Boolean {
        val info = imageInfo(width, height)
        val src = org.jetbrains.skia.Bitmap()
        assertTrue(src.installPixels(info, payload, width * 4), "测试源位图落位")
        org.jetbrains.skia.Surface.makeRasterDirect(info, addr, width * 4).use { surface ->
            surface.canvas.writePixels(src, 0, 0)
        }
        return true
    }

    private fun imageInfo(width: Int, height: Int): ImageInfo =
        ImageInfo.makeS32(width, height, ColorAlphaType.UNPREMUL)

    private companion object {
        const val SLOT_COUNT = 3
        const val FRAME_WIDTH = 64
        const val FRAME_HEIGHT = 48
    }
}
