package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import java.io.File
import java.io.IOException
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.javacpp.BytePointer

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil

/**
 * 录像产物回执（停止/中断收尾的一次性落定）：
 * - [Saved]：文件已可播（[file] 为落定路径、[frameCount] 为写入帧数）；[note] 非空 =
 *   录制非正常走完的如实注记（如分辨率变化分段、中断抢救部分文件），消费方在回执文案呈现；
 * - [Failed]：显式失败（[reason] 为人话原因），失败路径不留不可播的残件（见实现 close 契约）。
 */
sealed interface ScreencastRecordingOutcome {

    /** 录像落盘成功：[file] = mp4 产物（设备名 + 时间戳命名），[frameCount] = 写入的视频帧数 */
    data class Saved(val file: File, val frameCount: Long, val note: String? = null) : ScreencastRecordingOutcome

    /** 显式失败：[reason] 为人话原因 */
    data class Failed(val reason: String) : ScreencastRecordingOutcome
}

/**
 * 录像会话状态（引擎发布、面板消费）：
 * - [Idle]：无录像；
 * - [Pending]：已请求、尚未写入（等起点 config + 关键帧——reset_video 已发时通常百毫秒级；
 *   控制通道不可用则回退等自然关键帧，编码器 I 帧间隔 10s 为上限；静止画面不发帧）；
 * - [Active]：muxing 中（[file] 为本次产物路径）。
 */
sealed interface ScreencastRecordingState {

    /** 无录像 */
    data object Idle : ScreencastRecordingState

    /** 已请求未出帧：[file] 为预定的产物路径（首帧关键帧到达才开始创建/写入） */
    data class Pending(val file: File) : ScreencastRecordingState

    /** 录像中：[file] 为正在写入的产物路径 */
    data class Active(val file: File) : ScreencastRecordingState
}

/**
 * 录像 muxer 接缝（单会话单文件）：吃 demux 旁路的 config（SPS/PPS Annex-B）与帧载荷，
 * stream copy 封装进 mp4 容器（H.264 裸流零重编码，不涉任何编码器）。
 *
 * 线程模型：全部方法由引擎侧录像锁串行化调用（demux pump 的写帧与控制面的起停收尾
 * 共用一把锁）——实现无须自持锁；[start] 到 [finish]/[close] 之间实例被独占使用。
 *
 * 终止契约：[finish] 写容器收尾（trailer）后文件即完整可播；[close] 只释放资源不写
 * trailer（失败路径专用——写帧失败后的残件是否抢救由调用方决定，见 [finish] 抛错语义）。
 */
interface ScreencastRecorder : AutoCloseable {

    /**
     * 开启 muxing：解析 [configAnnexb]（SPS/PPS Annex-B）构造容器 extradata 并写 mp4
     * 头。[width]/[height] 为画面尺寸（容器轨元数据）。失败抛 [IOException]（含目录
     * 创建失败），由调用方转失败回执。
     */
    @Throws(IOException::class)
    fun start(configAnnexb: ByteArray, width: Int, height: Int, target: File)

    /**
     * 写一帧（[payload] 为 Annex-B 访问单元；[ptsUs] 为 server 微秒展示时戳，
     * [keyFrame] 为 demux 判定的关键帧位）。实现负责 Annex-B → AVCC 换算与时戳
     * 归零与单调收口（首帧为时间轴原点；乱序/重复 pts 钳制为前一帧 +1）。
     * 失败抛 [IOException]——调用方收口为录像失败回执，不逃逸会话失败通道（画面面
     * 不受录像面牵连）。
     */
    @Throws(IOException::class)
    fun onFrame(ptsUs: Long, keyFrame: Boolean, payload: ByteArray)

    /**
     * 收尾：写容器 trailer 并落盘。返回写入帧数。trailer 写失败抛 [IOException]——
     * mp4 无 trailer 即缺 moov 盒不可播放，调用方按失败收口（残件由调用方处置）。
     */
    @Throws(IOException::class)
    fun finish(): Long
}

/** 录像器工厂接缝：每段录像一个实例；单测注入 fake */
fun interface ScreencastRecorderFactory {
    fun create(): ScreencastRecorder
}

/** 录像产物树：`<home>/Documents/Toolbelt/recordings/`（与性能导出、报告产物同根的可见可分享产物树，不落内部数据根） */
object ScreencastRecordings {

    /**
     * 录像目录（父目录不存在则创建；创建失败留给调用方写盘报错——路径决策不吞错）。
     * [home] 参数化供单测注入临时根。路径真源 =
     * [FileUtils.getRecordingsExportDir]。
     */
    fun dir(home: File = File(System.getProperty("user.home"))): File =
        FileUtils.getRecordingsExportDir(home)
}

/**
 * 生产实现：JavaCV bytedeco ffmpeg 绑定直驱 avformat C API，把 H.264 Annex-B 裸流
 * stream copy 封装进 mp4（无编码器参与——许可面与解码链同源，libs.versions.toml
 * 非 -gpl classifier 契约维持）。
 *
 * 换算管线（真源 = [H264Annexb]）：config（SPS/PPS）→ avcC extradata（movenc 在
 * trailer 写 stsd 时仍读取，native 副本引用须存活至 finish）；逐帧 Annex-B → AVCC
 * 长度前缀形态后按容器要求写入。
 *
 * 时基契约：[onFrame] 的 ptsUs 先重定基再进容器（首帧为时间轴原点，后续帧减首帧
 * 基准）——设备侧 pts 是开机起的绝对时钟（分/时量级），直写会令 movenc 写前置空段
 * edit list：start_time 偏移数分钟、进度条 seek 落进空段即无帧可拖。归零后经
 * `av_packet_rescale_ts` 换算到容器流时基（write_header 后 movenc 定格，如 1/90000）；
 * dts = pts（设备端 MediaCodec 编码无 B 帧——若有 B 帧 dts 恒等 pts 即违反容器单调性，
 * 播放器兼容性降级为已知限制）。
 *
 * 资源契约：[finish]/[close] 幂等；任一方法抛错后实例不可再用（调用方弃用并 close）。
 */
class AvformatScreencastRecorder : ScreencastRecorder {

    private var context: AVFormatContext? = null
    private var stream: AVStream? = null
    private var packet: AVPacket? = null
    private var microsTimeBase: AVRational? = null
    private var lastPtsUs = Long.MIN_VALUE

    /** 首帧归零基准（null = 尚未收到首帧） */
    private var ptsOriginUs: Long? = null
    private var frameCount = 0L
    private var finished = false
    private var closed = false

    override fun start(configAnnexb: ByteArray, width: Int, height: Int, target: File) {
        check(context == null) { "recorder 已启动，不可复用" }
        target.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("录像目录创建失败：${parent.absolutePath}")
            }
        }
        val avcC = try {
            H264Annexb.buildAvcC(configAnnexb)
        } catch (e: IllegalArgumentException) {
            throw IOException("录像起点缺 SPS/PPS（config 载荷不完整）：${e.message}")
        }
        val context = AVFormatContext(null)
        val path = target.absolutePath
        val allocRet = avformat.avformat_alloc_output_context2(context, null, "mp4", path)
        if (allocRet < 0) {
            avformat.avformat_free_context(context)
            throw IOException("mp4 容器上下文创建失败（err=$allocRet）")
        }
        this.context = context
        try {
            val stream = avformat.avformat_new_stream(context, null)
                ?: throw IOException("mp4 视频轨创建失败")
            this.stream = stream
            val parameters: AVCodecParameters = stream.codecpar()
            parameters.codec_type(avutil.AVMEDIA_TYPE_VIDEO)
            parameters.codec_id(avcodec.AV_CODEC_ID_H264)
            parameters.width(width)
            parameters.height(height)
            // extradata 必须 ffmpeg 自有堆（av_memdup 复制）：codecpar 持有并随
            // avformat_free_context 释放——javacpp 侧不得再持释放器（二次 free 即 SIGSEGV）
            val avcCNative = avutil.av_memdup(BytePointer(*avcC), avcC.size.toLong())
            if (avcCNative == null || avcCNative.isNull) {
                throw IOException("avcC 原生复制失败（extradata 落位前提）")
            }
            parameters.extradata(BytePointer(avcCNative))
            parameters.extradata_size(avcC.size)
            val oformat = context.oformat()
            if ((oformat.flags() and avformat.AVFMT_NOFILE) == 0) {
                val pb = AVIOContext(null)
                val openRet = avformat.avio_open2(pb, path, avformat.AVIO_FLAG_WRITE, null as AVIOInterruptCB?, AVDictionary(null))
                if (openRet < 0) throw IOException("录像文件打开失败（err=$openRet）：$path")
                context.pb(pb)
            }
            val headerRet = avformat.avformat_write_header(context, null as AVDictionary?)
            if (headerRet < 0) throw IOException("mp4 容器头写入失败（err=$headerRet）")
            microsTimeBase = AVRational().num(1).den(PTS_TIME_BASE_DEN)
            packet = avcodec.av_packet_alloc()
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    override fun onFrame(ptsUs: Long, keyFrame: Boolean, payload: ByteArray) {
        val context = requireStarted()
        val pkt = packet ?: throw IOException("录像包未初始化")
        val stream = this.stream ?: throw IOException("录像轨未初始化")
        val avcc = try {
            H264Annexb.annexbToAvcc(payload)
        } catch (e: IllegalArgumentException) {
            throw IOException("帧载荷不是合法 Annex-B 访问单元：${e.message}")
        }
        val allocRet = avcodec.av_new_packet(pkt, avcc.size)
        if (allocRet < 0) throw IOException("录像包缓冲分配失败（err=$allocRet）")
        pkt.data().position(0).put(avcc, 0, avcc.size)
        pkt.stream_index(stream.index())
        val monotonicPts = monotonicPts(
            lastPtsUs = if (lastPtsUs == Long.MIN_VALUE) null else lastPtsUs,
            ptsUs = ptsUs,
        )
        lastPtsUs = monotonicPts
        val originUs = ptsOriginUs ?: monotonicPts.also { ptsOriginUs = it }
        val timelinePts = monotonicPts - originUs
        pkt.pts(timelinePts)
        pkt.dts(timelinePts)
        pkt.duration(0)
        pkt.pos(-1)
        pkt.flags(if (keyFrame) avcodec.AV_PKT_FLAG_KEY else 0)
        avcodec.av_packet_rescale_ts(pkt, microsTimeBase, stream.time_base())
        val writeRet = avformat.av_interleaved_write_frame(context, pkt)
        avcodec.av_packet_unref(pkt)
        if (writeRet < 0) throw IOException("录像帧写入失败（err=$writeRet，已写 $frameCount 帧）")
        frameCount++
    }

    override fun finish(): Long {
        if (finished) return frameCount
        val context = requireStarted()
        finished = true
        val trailerRet = avformat.av_write_trailer(context)
        if (trailerRet < 0) throw IOException("录像收尾写入失败（err=$trailerRet，trailer 缺失即文件不可播）")
        context.pb()?.let { pb ->
            if (!pb.isNull) {
                val closeRet = avformat.avio_close(pb)
                if (closeRet < 0) throw IOException("录像文件落盘冲刷失败（err=$closeRet）")
            }
        }
        release()
        return frameCount
    }

    /** 只释放资源不写 trailer（失败路径专用）——残件处置（删除或保留）由调用方承担 */
    override fun close() {
        if (closed) return
        closed = true
        release()
    }

    private fun requireStarted(): AVFormatContext =
        context ?: throw IOException("录像未启动（start 未成功调用）")

    private fun release() {
        packet?.let { runCatching { avcodec.av_packet_free(it) } }
        packet = null
        context?.let { ctx ->
            runCatching { avformat.avformat_free_context(ctx) }
        }
        context = null
        stream = null
        ptsOriginUs = null
    }

    companion object {

        /** [onFrame] pts 的源时基分母：1µs（scrcpy server pts 单位） */
        private const val PTS_TIME_BASE_DEN = 1_000_000

        /**
         * pts 单调收口（纯函数，单测直测）：[lastPtsUs] 为上一帧收口值（null = 首帧），
         * 回退/重复 pts 钳制为上一帧 +1——mp4 dts 单调是容器硬要求，静默放行会产出
         * 不可 seek 的文件。
         */
        internal fun monotonicPts(lastPtsUs: Long?, ptsUs: Long): Long =
            if (lastPtsUs != null && ptsUs <= lastPtsUs) lastPtsUs + 1 else ptsUs
    }
}

/**
 * JavaCV avformat 录像器工厂单例。平台守卫与解码链同口径（native 实加载探测，不比对
 * os.name/os.arch）：安装包只随打包平台的一套 native（classifier 由 build.gradle.kts
 * 按构建 OS 选），加载失败在 `LinkageError` 族抛出不进 Exception 通道，守卫前置为显式
 * [IOException]（经录像失败回执呈现，不进会话失败终态）。探测结论进程级缓存于
 * [nativeLoadFailure]。
 */
object AvformatScreencastRecorderFactory : ScreencastRecorderFactory {

    /** 探测结论（进程级）：null = native 可用，非 null = 失败摘要 */
    private val nativeLoadFailure: String? by lazy { probeBytedecoNative(avformat::class.java) }

    override fun create(): ScreencastRecorder {
        nativeLoadFailure?.let { failure ->
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            throw IOException(
                "当前平台（$os $arch）无法加载录像封装组件（$failure）——" +
                    "请使用与本机平台匹配的安装包",
            )
        }
        return AvformatScreencastRecorder()
    }
}
