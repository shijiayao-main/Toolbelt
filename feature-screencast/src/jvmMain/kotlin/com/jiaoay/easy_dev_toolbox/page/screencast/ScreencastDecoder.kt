package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.Loader
import org.jetbrains.skia.Bitmap

/**
 * 解码产物：设备画面一帧（已转 Compose 渲染位图）。
 * [width]/[height] 来自解码器解析面（与 SessionMeta 同源口径，作渲染纵横比用）。
 *
 * [image] 生命周期（生产实现）：解码器位图池的槽——像素内容会被后续帧在位覆写（契约见
 * [ScreencastFrameBitmapPool]）。一切像素读取（makeFromBitmap/readPixels/toPixelMap）
 * 必须持池锁（[ScreencastFrameBitmapPool.readLocked] 或 synchronized(asSkiaBitmap())）
 * ——裸读与覆写窗相交读到半帧内容；锁内读出的是当帧像素快照，锁外使用安全。
 */
class ScreencastDecodedFrame(
    val image: ImageBitmap,
    val width: Int,
    val height: Int,
)

/**
 * 投屏解码器接缝：吃 demuxer 吐出的 Annex-B 码流包（config/帧载荷同通道），吐解码帧。
 *
 * 线程模型：[onPacket] 由 demux pump 协程调用、[grab] 由解码协程调用（两协程并发，
 * 实现必须支持单写单读并发）；[signalEnd]/[close] 由会话收尾与停止路径调用（幂等）。
 *
 * 终止契约：[signalEnd] 后 [grab] 必须能返回（返回 null 或抛 IOException）——
 * 解码协程阻塞在 [grab] 时，停止路径经 [signalEnd] 解锁是收口前提。
 */
interface ScreencastDecoder : AutoCloseable {

    /**
     * 写入一个码流包（config/csd 或视频帧载荷）。写失败抛 [IOException]——由消费方
     * （demux pump）的失败收口计入会话终态真因，不逃逸全局未处理通道。
     */
    @Throws(IOException::class)
    fun onPacket(payload: ByteArray)

    /** 码流输入面终结信号（EOF）：解锁阻塞中的 [grab]，此后 [grab] 返回 null 或抛 [IOException] */
    fun signalEnd()

    /**
     * 阻塞取下一解码帧（无帧时阻塞等待）；流终结返回 null；坏流抛 [IOException]
     * （消费方经失败收口进终态真因）。无视频数据的帧（非视频流，防御）跳过继续取。
     *
     * 返回帧的 [ScreencastDecodedFrame.image] 生命周期有限（生产实现为位图池槽，见
     * [ScreencastFrameBitmapPool] 契约）——消费方按「最新帧即用即弃」消费，不跨多个
     * 发布周期持有。
     */
    @Throws(IOException::class)
    fun grab(): ScreencastDecodedFrame?
}

/** 解码器工厂接缝：每会话一个实例；单测注入 fake */
fun interface ScreencastDecoderFactory {
    fun create(): ScreencastDecoder
}

/**
 * 生产实现：avcodec 直驱解码裸 H.264 流（逐包 send/receive → sws 转 BGRA → Skia 位图）。
 *
 * 低延迟契约（显示链端到端滞后逐段可测）：
 * - **逐包直驱、不经 avformat/解析器**——scrcpy 每个码流包即完整访问单元，raw h264
 *   的 avformat 路径须经解析器按「下一 AU 起始」定界，恒多持一包（30fps 档即 +33ms）；
 * - **零重排**——设备端 MediaCodec 编码无 B 帧（dts=pts，录像面同款口径），解码器上下文
 *   恒 `has_b_frames=0` + `AV_CODEC_FLAG_LOW_DELAY` + 单线程（多线程帧管水线按线程数
 *   迟滞出帧）。残余一拍为 h264 解码器固有出帧节点：无帧界信令（无 AUD）的 in-band
 *   流上，上一帧在下一帧首 slice 处理时才释放（真机实测恒差一包，30fps 档即一帧节拍；
 *   逐包尾追加 AUD 定界实测不改变该节点，不作无效修饰）；
 * - **原生侧单趟入槽 + 发布位图池化**——YUV→BGRA 一次 sws（ffmpeg native 帧缓冲），
 *   BGRA 经 [copyBgraFrameToSlotBuffer] 单趟原生 memcpy 直入池槽像素缓冲（JVM 堆
 *   零参与，无逐像素路径）。池化是 native 水位的结构性上界：每帧新建 Bitmap 的形态下
 *   skia 像素缓冲的释放走 phantom Cleaner（须 JVM GC 先收走死包装对象），GC 稀疏时
 *   native 分配率远超释放率，RSS 无界棘升；池化 + 在位覆写后帧面零 native 分配，
 *   native 水位恒 = 槽数 × 单帧缓冲（帧面反压仍由码流包有界队列承担，池只收 convert
 *   之后的 skia 侧）。
 *
 * 队列契约：码流包经有界队列交接（容量 [PACKET_QUEUE_CAPACITY]）——解码算力瞬时不足时
 * 积压上界即容量（超出经 socket/TCP 反压设备端编码器），不排队不背压地保「最新帧优先」
 * 由引擎合帧层承担，解码侧只保顺序不丢包（丢 P 帧即花屏）。
 *
 * 线程模型：[onPacket]（pump 协程）与 [grab]（解码协程）经队列并发；[codecContext] 的
 * send/receive 只在 [grab] 调用线程触达；[close] 由会话收尾在 [grab] 收口（返回 null）
 * 之后调用（引擎会话协程顺序保证），无并发释放。
 */
class JavaCvScreencastDecoder : ScreencastDecoder {

    private val queue = ArrayBlockingQueue<ByteArray>(PACKET_QUEUE_CAPACITY)

    @Volatile
    private var ended = false

    @Volatile
    private var closed = false

    private val codecContext: AVCodecContext
    private val packet: AVPacket
    private val decodedFrame: AVFrame
    private val rgbFrame: AVFrame
    private var swsContext: SwsContext? = null

    /** 发布面位图池（convert 之后 skia 侧，借还/覆写契约见 [ScreencastFrameBitmapPool]）；internal = 单测观测面 */
    internal val bitmapPool = ScreencastFrameBitmapPool(BITMAP_POOL_SLOT_COUNT)

    /** 换算上下文物化档位（判据 = 解码输出宽高）：与 BGRA 目标帧缓冲、sws 上下文同批重建 */
    private var convertWidth = 0
    private var convertHeight = 0

    init {
        val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
            ?: throw IOException("无可用 H.264 解码器（ffmpeg native 缺失或不全）")
        codecContext = avcodec.avcodec_alloc_context3(codec)
            ?: throw IOException("解码器上下文创建失败")
        // 低延迟三件套：无 B 帧流零重排 + 单线程零帧管水线（值语义见类 KDoc 契约）
        codecContext.flags(codecContext.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
        codecContext.has_b_frames(0)
        codecContext.thread_count(1)
        val openRet = avcodec.avcodec_open2(codecContext, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
        if (openRet < 0) {
            avcodec.avcodec_free_context(codecContext)
            throw IOException("解码器打开失败（err=$openRet）")
        }
        packet = avcodec.av_packet_alloc()
        decodedFrame = avutil.av_frame_alloc()
        rgbFrame = avutil.av_frame_alloc()
    }

    override fun onPacket(payload: ByteArray) {
        if (ended) {
            throw IOException("解码器输入面已终结（signalEnd 后写入）")
        }
        try {
            // 有界等待入队（空转档 50ms）：队列满时 pump 阻塞在写入上是反压路径的常态，
            // 终结旗标是阻塞解锁的第二通道——signalEnd 腾位放哨兵与阻塞 put 互相拉扯，
            // put 若无终结出口即活锁（停止路径永阻塞）
            while (!queue.offer(payload, PUT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                if (ended) {
                    throw IOException("解码器输入面已终结（停止反压丢弃在途包）")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("码流写入等待被中断", e)
        }
    }

    override fun signalEnd() {
        ended = true
        // 唤醒阻塞中的 grab：挤掉队尾载荷腾位放哨兵（终态下积压载荷已无消费意义）
        while (!queue.offer(END_TOKEN)) {
            queue.poll()
        }
    }

    @Throws(IOException::class)
    override fun grab(): ScreencastDecodedFrame? {
        while (true) {
            // 有界等待轮询（空转档 100ms）：终结旗标是阻塞解锁的第二通道——close 清队会
            // 挤掉哨兵，仅靠哨兵唤醒存在永阻塞窗口（有包到达时 poll 即刻返回，无延迟代价）
            val payload = try {
                queue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("解码等待被中断", e)
            }
            if (payload != null && payload.size == 0) return null
            if (ended || closed) return null
            if (payload == null) continue

            val allocRet = avcodec.av_new_packet(packet, payload.size)
            if (allocRet < 0) throw IOException("解码包缓冲分配失败（err=$allocRet）")
            packet.data().position(0).put(payload, 0, payload.size)
            val sendRet = avcodec.avcodec_send_packet(codecContext, packet)
            avcodec.av_packet_unref(packet)
            if (sendRet < 0 && sendRet != avutil.AVERROR_EAGAIN() && sendRet != avutil.AVERROR_EOF()) {
                // 坏包容错跳过（设备端流经 TCP 恒完整，防御性口径与解码容错同源），继续喂下一包
                continue
            }

            while (true) {
                val recvRet = avcodec.avcodec_receive_frame(codecContext, decodedFrame)
                if (recvRet == avutil.AVERROR_EAGAIN() || recvRet == avutil.AVERROR_EOF()) break
                if (recvRet < 0) break
                val result = try {
                    convertDecodedFrame()
                } finally {
                    avutil.av_frame_unref(decodedFrame)
                }
                return result
            }
        }
    }

    /**
     * 解码输出帧 → Compose 位图：YUV→BGRA 一次 sws（ffmpeg native 帧缓冲），BGRA 单趟
     * 原生 memcpy 直入池槽像素缓冲（JVM 堆零参与）。
     * 分辨率/像素格式变化（server 端编码器重配）即重建换算上下文并整池换档。
     */
    private fun convertDecodedFrame(): ScreencastDecodedFrame {
        val width = decodedFrame.width()
        val height = decodedFrame.height()
        ensureConvertContext(width, height)
        swscale.sws_scale(
            swsContext,
            decodedFrame.data(),
            decodedFrame.linesize(),
            0,
            height,
            rgbFrame.data(),
            rgbFrame.linesize(),
        )
        val bitmap = bitmapPool.acquire(width, height)
        val installed = bitmapPool.install(bitmap) { dstAddr ->
            copyBgraFrameToSlotBuffer(dstAddr, rgbFrame, width, height)
        }
        if (!installed) {
            throw IOException("帧位图像素落位失败（${width}x$height）")
        }
        return ScreencastDecodedFrame(
            image = bitmap.asComposeImageBitmap(),
            width = width,
            height = height,
        )
    }

    /** 换算上下文（sws + BGRA 目标帧缓冲）：按解码输出的尺寸/格式惰性重建 */
    private fun ensureConvertContext(width: Int, height: Int) {
        if (convertWidth == width && convertHeight == height) return
        // av_frame_get_buffer 的 ffmpeg 契约要求帧处于未缓冲态——重配前必须 unref
        // 释放旧 BGRA 缓冲，否则旧 AVBufferRef 引用被直接覆写（每档次泄漏一份帧缓冲）
        avutil.av_frame_unref(rgbFrame)
        rgbFrame.format(avutil.AV_PIX_FMT_BGRA)
        rgbFrame.width(width)
        rgbFrame.height(height)
        if (avutil.av_frame_get_buffer(rgbFrame, 0) < 0) {
            throw IOException("BGRA 帧缓冲分配失败（${width}x$height）")
        }
        // sws_getCachedContext 的 cached 契约：传既有 context 由它判参——参数相同即复用、
        // 参数变化时内部 free 旧建新，旧 context 所有权在此移交；返回 null = 失败且旧已
        // free（先置 null 防悬垂再抛）
        val existing = swsContext
        swsContext = null
        swsContext = swscale.sws_getCachedContext(
            existing,
            width, height, decodedFrame.format(),
            width, height, avutil.AV_PIX_FMT_BGRA,
            swscale.SWS_BILINEAR,
            null, null, null as DoublePointer?,
        )
            ?: throw IOException("像素格式换算上下文创建失败（${width}x$height）")
        convertWidth = width
        convertHeight = height
    }

    override fun close() {
        if (closed) return
        closed = true
        signalEnd()
        queue.clear()
        queue.offer(END_TOKEN)
        bitmapPool.clear()
        runCatching { avcodec.av_packet_free(packet) }
        runCatching { avutil.av_frame_free(decodedFrame) }
        runCatching { avutil.av_frame_free(rgbFrame) }
        runCatching { swscale.sws_freeContext(swsContext) }
        runCatching { avcodec.avcodec_free_context(codecContext) }
        swsContext = null
    }

    companion object {

        /**
         * 码流包队列容量：积压上界 = 16 AU——超限经 pump 阻塞反压到 socket/TCP/设备端
         * 编码器（不丢包：丢 P 帧即花屏），滞后上界约 0.5s（30fps）且解码算力正常时
         * 队列恒近空。
         */
        private const val PACKET_QUEUE_CAPACITY = 16

        /**
         * 位图池槽数：3 = 延迟两代复用——同槽覆写前必有 2 帧其他槽先行发布，正在显示/
         * 读取的帧不被下一帧覆写（StateFlow 合帧语义下显示窗口至多跨 1 次发布，2 槽即
         * 够，3 保守）。native 水位上界 = 3 × 单帧缓冲（1080x606 预览档 ≈ 7.5MB）。
         */
        private const val BITMAP_POOL_SLOT_COUNT = 3

        /** 队列哨兵：零长载荷 = 输入面终结（[signalEnd] 放入，[grab] 取到即返回 null） */
        private val END_TOKEN = ByteArray(0)

        /** grab 无包空转的轮询档（ms）：终结旗标兜底唤醒的检查粒度，正常取包零延迟代价 */
        private const val POLL_INTERVAL_MS = 100L

        /** onPacket 队列满时的写入轮询档（ms）：终结旗标兜底解锁的检查粒度 */
        private const val PUT_POLL_INTERVAL_MS = 50L
    }
}

/**
 * BGRA 帧原生侧单趟入槽（解码热路径唯一拷贝点）：`av_image_copy_to_buffer` 按源行步长
 * （sws 目标帧缓冲可含对齐垫）把 [frame] 像素拷进 [dstAddr] 起的池槽像素缓冲，目标按
 * 紧排解释（行步长 = width×4，与池槽 `allocPixels` 的 S32 UNPREMUL 布局配对）。JVM 堆
 * 零参与。返回 false = FFmpeg 侧拷入失败（目标缓冲不足等），调用方按装帧失败收口。
 *
 * 目标地址包装：javacpp 公开 API 无「long 地址 → Pointer」构造，用 NULL 基址 BytePointer
 * 经 position 落位——javacpp 对函数 Pointer 形参取 address + position 作原生实参地址
 * （与 Buffer 位置语义同源，ScreencastDecoderTest 装帧行为面单测钉死）。capacity/limit
 * 一并抬到地址上界，防后续版本恢复位置校验时静默破坏。
 */
internal fun copyBgraFrameToSlotBuffer(dstAddr: Long, frame: AVFrame, width: Int, height: Int): Boolean {
    val dstSize = avutil.av_image_get_buffer_size(avutil.AV_PIX_FMT_BGRA, width, height, 1)
    val dst = BytePointer()
    dst.capacity(dstAddr + dstSize)
    dst.limit(dstAddr + dstSize)
    dst.position(dstAddr)
    return avutil.av_image_copy_to_buffer(
        dst,
        dstSize,
        frame.data(),
        frame.linesize(),
        avutil.AV_PIX_FMT_BGRA,
        width,
        height,
        1,
    ) >= 0
}

/**
 * 解码发布面 skia 位图池：固定 [slotCount] 槽 round-robin 轮转，槽像素建池时
 * `allocPixels` 一次性物化，帧内容经 [install] 的原生地址写入面在位覆写——不换
 * PixelRef、帧面零 native 分配，同槽在位覆写语义由 `ScreencastFrameBitmapPoolTest` 钉死。
 *
 * 🔴 并发契约（违反即显示面错误帧）：**装帧与一切像素读取必须经 [install]/[readLocked]
 * 同锁**（监视对象 = 槽 Bitmap 包装对象，跨帧恒同一实例）。写入窗与读取窗相交时读取方
 * 会读到半帧覆写内容；Compose 绘制相对 StateFlow 发布是异步的（首帧突发期解码发布可
 * 领先 AWT 首绘数帧；窗口暴露/resize 的 AWT 重绘可在任意发布周期之后重画最后帧），
 * 「绘制窗口 ⊂ 一个发布周期」不可依赖。锁把写入窗与读取窗互斥；读取窗内拿到的是当帧
 * 像素快照（可变位图 → raster 拷贝），锁外绘制不再触池槽。
 *
 * 生命周期契约（借还语义）：
 * - **借** = 解码线程（[JavaCvScreencastDecoder.grab]）取槽装帧并发布进引擎合帧通道
 *   （StateFlow 只保最新）；**还** = 无显式归还——同槽在之后又发布 [slotCount]-1 帧
 *   后才轮转回位被覆写。
 * - **陈旧画面而非错误失效**：消费方绘制的是装帧时刻的像素快照（经 [readLocked]
 *   拷出），跨发布周期重绘不因槽被覆写而错——像素内容滞后最多为「读到更新帧」。
 *
 * 内存上界 = [slotCount] × 单帧缓冲（1080x606 预览档 ≈ 2.5MB/槽，建池一次性分配）+
 * 每次绘制的 raster 快照（当帧释放）；池不做无界缓冲（帧面反压由码流包有界队列承担，
 * 池只收 convert 之后的 skia 侧）。换分辨率整池作废重建（像素布局随尺寸变化；旧槽
 * 引用丢弃、native 缓冲走 GC 释放——换档低频可接受）；[clear] 供会话收口清空
 * （防跨会话残留）。
 *
 * 线程模型：acquire 仅解码线程触达；[install] 解码线程、[readLocked] 渲染/快照线程——
 * 两者经槽监视对象互斥（无锁并发面即错误帧面）。
 */
internal class ScreencastFrameBitmapPool(private val slotCount: Int) {

    private var slots: Array<Bitmap?> = arrayOfNulls(slotCount)

    /** 槽像素缓冲原生地址（建池物化时经 `peekPixels` 采点；槽在位覆写不换 PixelRef，地址跨帧恒定） */
    private var slotAddrs = LongArray(slotCount)
    private var nextSlot = 0
    private var slotWidth = 0
    private var slotHeight = 0

    /** 取下一轮转槽；尺寸变化（server 端编码器重配）即整池作废重建，返回槽由调用方装帧 */
    fun acquire(width: Int, height: Int): Bitmap {
        if (slotWidth != width || slotHeight != height) {
            val info = slotImageInfo(width, height)
            slots = Array(slotCount) { Bitmap() }
            slotAddrs = LongArray(slotCount)
            for (index in 0 until slotCount) {
                val bitmap = slots[index]!!
                // allocPixels 失败（native 不足）不在此抛——install 面以 addr=0 显式失败收口
                if (bitmap.allocPixels(info)) {
                    slotAddrs[index] = bitmap.peekPixels()?.addr ?: 0L
                }
            }
            nextSlot = 0
            slotWidth = width
            slotHeight = height
        }
        val bitmap = slots[nextSlot]!!
        nextSlot = (nextSlot + 1) % slotCount
        return bitmap
    }

    /** 槽像素布局：S32 + UNPREMUL——与解码器 sws 的 BGRA 输出配对（BGRA 无预乘语义） */
    private fun slotImageInfo(width: Int, height: Int): org.jetbrains.skia.ImageInfo =
        org.jetbrains.skia.ImageInfo.makeS32(width, height, org.jetbrains.skia.ColorAlphaType.UNPREMUL)

    /**
     * 装帧（解码线程）：持槽锁把槽像素缓冲的原生地址交给 [write] 做单趟覆写（目标按
     * 槽 info 布局解释，行步长紧排），成功后置脏（换代——消费面已拷出的 raster 快照
     * 与缓存正确失效）。返回 false = 槽像素未物化（建池 allocPixels 失败）或 [write]
     * 失败，调用方按装帧失败收口。
     */
    fun install(bitmap: Bitmap, write: (Long) -> Boolean): Boolean =
        synchronized(bitmap) {
            val index = slots.indexOfFirst { it === bitmap }
            if (index < 0 || slotAddrs[index] == 0L) return@synchronized false
            val written = write(slotAddrs[index])
            if (written) bitmap.notifyPixelsChanged()
            written
        }

    /**
     * 消费面读槽（渲染/快照线程）：与 [install] 同锁——[block] 内的像素读取
     * （makeFromBitmap / readPixels / toPixelMap）不与装帧覆写窗相交。
     */
    fun <T> readLocked(bitmap: Bitmap, block: (Bitmap) -> T): T = synchronized(bitmap) { block(bitmap) }

    /** 清空（会话收口）：槽引用全部丢弃，native 像素缓冲交还 skiko Cleaner */
    fun clear() {
        slots = arrayOfNulls(slotCount)
        slotAddrs = LongArray(slotCount)
        nextSlot = 0
        slotWidth = 0
        slotHeight = 0
    }

    /** 观测点（单测）：已物化槽数——连续取帧后恒 ≤ [slotCount]（池有界的机械断言面） */
    internal val materializedSlotCount: Int get() = slots.count { it != null }

    /** 观测点（单测）：按轮转位取槽（复用身份/换档作废断言用） */
    internal fun slotAt(index: Int): Bitmap? = slots.getOrNull(index)
}

/**
 * native 实加载探测：返回 null = 该 JNI 绑定类可加载，非 null = 失败摘要（人话原因）。
 *
 * 只收 `LinkageError`（`UnsatisfiedLinkError`/`NoClassDefFoundError`——native 缺件或
 * 架构不符的实际抛出形态）与 `RuntimeException`（javacpp 对配置/缓存目录的显式报错）：
 * 二者都是「本机不可用」的可预期形态。`OutOfMemoryError` 等其余 Error 不放行——它们是
 * 资源故障，吞掉会把真实崩溃伪装成平台不支持。
 */
internal fun probeBytedecoNative(nativeClass: Class<*>): String? =
    try {
        Loader.load(nativeClass)
        null
    } catch (e: LinkageError) {
        e.message ?: e::class.simpleName ?: "未知错误"
    } catch (e: RuntimeException) {
        e.message ?: e::class.simpleName ?: "未知错误"
    }

/**
 * JavaCV 解码器工厂单例。
 *
 * 平台守卫 = native 实加载探测，不比对 os.name/os.arch：安装包只随打包平台的一套 native
 * （classifier 由 build.gradle.kts 按构建 OS 选），跨平台搬运安装包或 native 被系统拒绝
 * 加载时，加载期抛 `LinkageError` 族（绕过 Exception 收口）。守卫把「不可用」前置为显式
 * [IOException]（进会话失败终态、给指引文案）。探测结论进程级缓存于 [nativeLoadFailure]
 * （`by lazy` 同步初始化，多线程首调安全）：成功也缓存，避免每次开会话重复解包 native。
 */
object JavaCvScreencastDecoderFactory : ScreencastDecoderFactory {

    /** 探测结论（进程级）：null = native 可用，非 null = 失败摘要 */
    private val nativeLoadFailure: String? by lazy { probeBytedecoNative(avcodec::class.java) }

    override fun create(): ScreencastDecoder {
        nativeLoadFailure?.let { failure ->
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            throw IOException(
                "当前平台（$os $arch）无法加载投屏解码组件（$failure）——" +
                    "请使用与本机平台匹配的安装包",
            )
        }
        return JavaCvScreencastDecoder()
    }
}
