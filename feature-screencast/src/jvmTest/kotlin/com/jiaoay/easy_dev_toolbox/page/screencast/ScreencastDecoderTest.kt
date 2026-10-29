package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bytedeco.ffmpeg.global.avutil

/**
 * 生产解码器单测（免设备）：
 * - 终止契约：停止路径经 [ScreencastDecoder.signalEnd] 解锁阻塞中的 [ScreencastDecoder.grab]
 *   是会话收口前提（接口 KDoc 终止契约的机械化背书）。
 * - 装帧行为面：sws 输出的 BGRA 帧（行步长含对齐垫）经 copyBgraFrameToSlotBuffer 单趟
 *   原生拷入池槽紧排像素缓冲——接线逐字节等价的机械化背书。
 * 解码正确性面由真机 E2E runner（SCRCPY_E2E 门控）覆盖。
 */
class ScreencastDecoderTest {

    @Test
    fun `grab returns null after signalEnd without any packet`() {
        val decoder = JavaCvScreencastDecoderFactory.create()
        decoder.use {
            it.signalEnd()
            assertNull(it.grab(), "终结信号后 grab 必须立即可返回 null（不阻塞）")
        }
    }

    @Test
    fun `blocked grab is unlocked by signalEnd from another thread`() {
        val decoder = JavaCvScreencastDecoderFactory.create()
        val grabbed = CountDownLatch(1)
        val result = Array<ScreencastDecodedFrame?>(1) { null }
        val consumer = Thread {
            try {
                result[0] = decoder.grab()
            } catch (_: IOException) {
            } finally {
                grabbed.countDown()
            }
        }
        consumer.isDaemon = true
        consumer.start()
        Thread.sleep(200)
        assertTrue(grabbed.count == 1L, "无包时 grab 应处于阻塞中（终止契约的前提形态）")
        decoder.signalEnd()
        assertTrue(grabbed.await(3, TimeUnit.SECONDS), "signalEnd 必须解锁阻塞中的 grab（停止路径收口前提）")
        assertNull(result[0], "解锁后 grab 返回 null（流终结语义）")
        decoder.close()
    }

    @Test
    fun `onPacket after signalEnd throws IOException`() {
        val decoder = JavaCvScreencastDecoderFactory.create()
        decoder.use {
            it.signalEnd()
            assertFailsWith<IOException> { it.onPacket(ByteArray(16)) }
        }
    }

    @Test
    fun `close is idempotent and grab stays null after close`() {
        val decoder = JavaCvScreencastDecoderFactory.create()
        decoder.signalEnd()
        decoder.close()
        decoder.close()
        assertNull(decoder.grab(), "关闭后 grab 恒 null（不复活不阻塞）")
    }

    @Test
    fun `close clears the bitmap pool`() {
        // 池观测面在生产实现上（接口无池概念），直构跳过工厂平台守卫
        val decoder = JavaCvScreencastDecoder()
        assertEquals(0, decoder.bitmapPool.materializedSlotCount, "未经解码会话池为空（惰性物化）")
        decoder.close()
        decoder.close()
        assertEquals(0, decoder.bitmapPool.materializedSlotCount, "关闭后池保持清空（会话收口防跨会话残留）")
    }

    @Test
    fun `signalEnd unblocks a producer blocked on a full queue`() {
        val decoder = JavaCvScreencastDecoderFactory.create()
        // 灌满队列制造反压：第 17 笔 onPacket 阻塞在生产者线程（停止路径的真实形态）
        repeat(PACKET_QUEUE_CAPACITY) { decoder.onPacket(ByteArray(1024)) }
        val producerDone = CountDownLatch(1)
        val producerError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val producer = Thread {
            try {
                decoder.onPacket(ByteArray(1024))
            } catch (e: Throwable) {
                producerError.set(e)
            } finally {
                producerDone.countDown()
            }
        }
        producer.isDaemon = true
        producer.start()
        Thread.sleep(200)
        assertTrue(producerDone.count == 1L, "队列满时生产者应处于阻塞中（反压形态前提）")
        val t0 = System.nanoTime()
        decoder.signalEnd()
        assertTrue(producerDone.await(3, TimeUnit.SECONDS), "signalEnd 必须解锁阻塞在满队列上的生产者（活锁防线）")
        // signalEnd 的契约是解锁而非拒绝在途写入：腾位 poll 与阻塞 offer 竞争同一空位，
        // offer 可先于哨兵抢到腾出的空位、包被正常接收（慢机竞态窗更宽）——两种终态皆
        // 合法；「终结后写入必拒」由下方确定性直写断言钉死（ended 旗标短路，不经队列竞态）
        val producerOutcome = producerError.get()
        assertTrue(
            producerOutcome == null || producerOutcome is IOException,
            "解锁后生产者终态应为正常接收或 IOException（实际 $producerOutcome）",
        )
        var postEndEx: Throwable? = null
        try {
            decoder.onPacket(ByteArray(1024))
        } catch (e: Throwable) {
            postEndEx = e
        }
        assertTrue(postEndEx is IOException, "终结后的写入以 IOException 显式报告（实际 $postEndEx）")
        assertTrue(
            (System.nanoTime() - t0) / 1_000_000 < 2_000,
            "signalEnd 不得被反压状态拖入无限循环（UI 停止路径不被拖死）",
        )
        decoder.close()
    }

    @Test
    fun `copyBgraFrameToSlotBuffer packs padded BGRA frame into tight slot pixels`() {
        // 装帧接线锚：sws 目标帧缓冲的行步长经 av_frame_get_buffer 默认对齐可含对齐垫，
        // 入槽拷贝必须按源行步长取行、按紧排落位（行步长 = width×4）——尾行不截断、
        // 行间不串位、帧面逐字节等价
        val width = 65 // rowBytes = 260，非对齐宽：行步长垫位后必然大于行载荷
        val height = 17
        val rowBytes = width * 4
        val frame = avutil.av_frame_alloc() ?: error("测试帧对象分配失败")
        try {
            frame.format(avutil.AV_PIX_FMT_BGRA)
            frame.width(width)
            frame.height(height)
            assertEquals(0, avutil.av_frame_get_buffer(frame, 64), "BGRA 帧缓冲分配成功")
            val srcLinesize = frame.linesize(0)
            assertTrue(srcLinesize > rowBytes, "源行步长含对齐垫（断言有效性前提）")
            // 逐行互异填充（防「只拷首行/恒零」假绿），期望紧排帧面同公式生成
            for (y in 0 until height) {
                val row = ByteArray(rowBytes) { (((it * 7 + y * 13) % 251)).toByte() }
                frame.data(0).position((srcLinesize * y).toLong()).put(row, 0, rowBytes)
            }
            val expected = ByteArray(rowBytes * height) { i ->
                (((i % rowBytes) * 7 + (i / rowBytes) * 13) % 251).toByte()
            }

            val pool = ScreencastFrameBitmapPool(1)
            val info = org.jetbrains.skia.ImageInfo.makeS32(
                width,
                height,
                org.jetbrains.skia.ColorAlphaType.UNPREMUL,
            )
            val slot = pool.acquire(width, height)
            assertTrue(
                pool.install(slot) { addr -> copyBgraFrameToSlotBuffer(addr, frame, width, height) },
                "含对齐垫源帧经原生侧单趟入槽落位",
            )
            val readBack = pool.readLocked(slot) { it.readPixels(info, rowBytes, 0, 0) }
            assertTrue(
                expected.contentEquals(readBack),
                "池槽像素 = 紧排期望帧面（对齐垫不串行、尾行不截断、逐字节等价）",
            )
        } finally {
            avutil.av_frame_free(frame)
        }
    }

    private companion object {
        /** 与 [JavaCvScreencastDecoder] 队列容量同源（镜像值，KDoc 契约数字） */
        const val PACKET_QUEUE_CAPACITY = 16
    }
}
