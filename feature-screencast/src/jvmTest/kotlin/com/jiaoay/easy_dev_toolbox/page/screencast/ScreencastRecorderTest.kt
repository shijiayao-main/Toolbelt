package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat

/**
 * 录像 mux 内核单测：[H264Annexb] 纯函数换算（NALU 切分 / avcC 构造 / AVCC 长度前缀）
 * + [AvformatScreencastRecorder] 真实 avformat 封装（临时目录产物结构断言 + 失败路径
 * + avformat 读回的时间轴契约）。
 * 产物「可播放」的端到端校验（ffprobe/解码回读）归真机验收面，此处钉容器结构正确性。
 */
class ScreencastRecorderTest {

    // ── 真源码流固定件（baseline 档 H.264：SPS profile=66/level=30 + PPS） ──

    private val sps = hexToBytes("6742c01ed900a02ff970110000030001000003003c0f162e48")
    private val pps = hexToBytes("68cb83cb20")

    private fun newTempDir(): File = createTempDirectory("screencast-mux-test").toFile()

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte() }

    private fun annexbOf(vararg nalus: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (nalu in nalus) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nalu)
        }
        return out.toByteArray()
    }

    private val configAnnexb: ByteArray get() = annexbOf(sps, pps)

    /** 合成 VCL NALU（载荷内容不参与容器结构——movenc 只装箱不校验切片语义） */
    private fun sliceNalu(idr: Boolean, filler: Int): ByteArray =
        ByteArray(64) { i -> if (i == 0) (if (idr) 0x65 else 0x41).toByte() else (filler + i).toByte() }

    // ── H264Annexb 纯函数 ──

    @Test
    fun `split nalus handles 3 and 4 byte start codes without leaking zeros`() {
        // 4 字节起始码的前导零归下一码，不得混入上一 NALU 尾部（AVCC 长度换算会错位）
        val data = annexbOf(byteArrayOf(0x67, 1, 2), byteArrayOf(0x68, 3), byteArrayOf(0x65, 4, 5))
        val nalus = H264Annexb.splitNalus(data)
        assertEquals(3, nalus.size)
        assertContentEquals(byteArrayOf(0x67, 1, 2), nalus[0])
        assertContentEquals(byteArrayOf(0x68, 3), nalus[1])
        assertContentEquals(byteArrayOf(0x65, 4, 5), nalus[2])
    }

    @Test
    fun `split nalus keeps pure 3-byte start code stream intact`() {
        val out = java.io.ByteArrayOutputStream()
        listOf(byteArrayOf(0x67, 9), byteArrayOf(0x41, 8)).forEach {
            out.write(byteArrayOf(0, 0, 1)); out.write(it)
        }
        val nalus = H264Annexb.splitNalus(out.toByteArray())
        assertEquals(2, nalus.size)
        assertContentEquals(byteArrayOf(0x67, 9), nalus[0])
        assertContentEquals(byteArrayOf(0x41, 8), nalus[1])
    }

    @Test
    fun `build avcc carries sps triple and length prefixed nalus`() {
        val avcC = H264Annexb.buildAvcC(configAnnexb)
        // configurationVersion | profile | compat | level | 0xFF | 0xE1 | len16(sps) | sps | ppsCount | len16(pps) | pps
        val expected = byteArrayOf(0x01, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte()) +
            byteArrayOf(0x00, sps.size.toByte()) + sps +
            byteArrayOf(0x01, 0x00, pps.size.toByte()) + pps
        assertContentEquals(expected, avcC)
    }

    @Test
    fun `build avcc fails explicitly without sps or pps`() {
        val spsOnly = annexbOf(sps)
        val ppsOnly = annexbOf(pps)
        val junk = annexbOf(byteArrayOf(0x41, 1))
        assertFailsWith<IllegalArgumentException> { H264Annexb.buildAvcC(spsOnly) }
        assertFailsWith<IllegalArgumentException> { H264Annexb.buildAvcC(ppsOnly) }
        assertFailsWith<IllegalArgumentException> { H264Annexb.buildAvcC(junk) }
    }

    @Test
    fun `annexb to avcc prefixes 4-byte big-endian lengths preserving order`() {
        val naluA = byteArrayOf(0x67, 1, 2)
        val naluB = byteArrayOf(0x65, 3, 4, 5)
        val avcc = H264Annexb.annexbToAvcc(annexbOf(naluA, naluB))
        val expected = byteArrayOf(0, 0, 0, 3) + naluA + byteArrayOf(0, 0, 0, 4) + naluB
        assertContentEquals(expected, avcc)
    }

    @Test
    fun `annexb helpers reject empty access unit`() {
        assertFailsWith<IllegalArgumentException> { H264Annexb.annexbToAvcc(ByteArray(0)) }
        assertEquals(false, H264Annexb.hasSpsPps(annexbOf(sliceNalu(idr = false, filler = 0))))
        assertEquals(true, H264Annexb.hasSpsPps(configAnnexb))
    }

    // ── 时戳单调收口（纯函数面） ──

    @Test
    fun `monotonic pts clamps regression and duplicates`() {
        assertEquals(1_000L, AvformatScreencastRecorder.monotonicPts(lastPtsUs = null, ptsUs = 1_000L))
        assertEquals(2_000L, AvformatScreencastRecorder.monotonicPts(lastPtsUs = 1_000L, ptsUs = 2_000L))
        assertEquals(1_001L, AvformatScreencastRecorder.monotonicPts(lastPtsUs = 1_000L, ptsUs = 1_000L))
        assertEquals(1_001L, AvformatScreencastRecorder.monotonicPts(lastPtsUs = 1_000L, ptsUs = 500L))
    }

    // ── AvformatScreencastRecorder（真实 avformat，临时目录） ──

    @Test
    fun `mux writes ftyp avcC structure and reports frame count`() {
        val tempDir = newTempDir()
        val target = File(tempDir, "device_20260101_000000_000.mp4")
        val recorder = AvformatScreencastRecorder()
        recorder.start(configAnnexb, width = 640, height = 360, target = target)
        recorder.onFrame(ptsUs = 0, keyFrame = true, payload = annexbOf(sliceNalu(idr = true, filler = 0)))
        recorder.onFrame(ptsUs = 33_333, keyFrame = false, payload = annexbOf(sliceNalu(idr = false, filler = 1)))
        recorder.onFrame(ptsUs = 66_666, keyFrame = false, payload = annexbOf(sliceNalu(idr = false, filler = 2)))
        val count = recorder.finish()

        assertEquals(3L, count)
        assertTrue(target.isFile, "录像产物应落盘")
        val bytes = target.readBytes()
        assertTrue(bytes.size > 100, "产物应承载容器结构（实际 ${bytes.size}B）")
        // mp4 盒结构：偏移 4-8 为 ftop 类型盒名（ftyp），moov 内必含 avcC（extradata 落位）
        assertContentEquals("ftyp".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(4, 8))
        assertTrue(containsBytes(bytes, "avcC".toByteArray(Charsets.US_ASCII)), "avcC extradata 应在容器内")
        assertTrue(containsBytes(bytes, "moov".toByteArray(Charsets.US_ASCII)), "trailer 收尾后 moov 应在容器内")
    }

    @Test
    fun `mux rebases device-absolute pts onto zero-based timeline`() {
        // 设备侧 pts 是开机起的绝对时钟（本用例取 ~34 分钟 = 2.04e12µs）：未归零直写时
        // movenc 写前置空段 edit list——start_time 偏移数十分钟、seek 落空段即无帧可拖
        val tempDir = newTempDir()
        val target = File(tempDir, "device_rebase.mp4")
        val baseUs = 2_041_123_456_789L
        val recorder = AvformatScreencastRecorder()
        recorder.start(configAnnexb, width = 640, height = 360, target = target)
        recorder.onFrame(ptsUs = baseUs, keyFrame = true, payload = annexbOf(sliceNalu(idr = true, filler = 0)))
        recorder.onFrame(ptsUs = baseUs + 33_333, keyFrame = false, payload = annexbOf(sliceNalu(idr = false, filler = 1)))
        recorder.onFrame(ptsUs = baseUs + 66_666, keyFrame = false, payload = annexbOf(sliceNalu(idr = false, filler = 2)))
        assertEquals(3L, recorder.finish())

        val ptsUs = firstPacketPtsUs(target, count = 2)
        assertEquals(0L, ptsUs[0], "首帧应归零为时间轴原点（实际 ${ptsUs[0]}µs）")
        assertTrue(ptsUs[1] in 33_000..33_700, "帧距应保持（第二帧实际 ${ptsUs[1]}µs，期望 ≈33333µs）")
    }

    @Test
    fun `start fails explicitly on config without sps pps`() {
        val recorder = AvformatScreencastRecorder()
        val ex = assertFailsWith<IOException> {
            recorder.start(annexbOf(sliceNalu(idr = false, filler = 0)), 640, 360, File(newTempDir(), "x.mp4"))
        }
        assertTrue("SPS" in (ex.message ?: ""), "失败回执应指明 SPS 缺失：${ex.message}")
    }

    @Test
    fun `start fails explicitly when directory cannot be created`() {
        val blocker = File(newTempDir(), "blocker")
        assertTrue(blocker.createNewFile())
        val recorder = AvformatScreencastRecorder()
        assertFailsWith<IOException> {
            recorder.start(configAnnexb, 640, 360, File(File(blocker, "nested"), "x.mp4"))
        }
    }

    @Test
    fun `finish without start fails explicitly and close is idempotent`() {
        val recorder = AvformatScreencastRecorder()
        assertFailsWith<IOException> { recorder.finish() }
        recorder.close()
        recorder.close()
    }

    @Test
    fun `on frame without start fails explicitly`() {
        val recorder = AvformatScreencastRecorder()
        assertFailsWith<IOException> {
            recorder.onFrame(0, true, annexbOf(sliceNalu(idr = true, filler = 0)))
        }
    }

    /**
     * avformat 读回产物前 [count] 帧的 pts（µs，经流时基换算）——elst 空段若存在，
     * demuxer 会如实把它算进首帧 pts（非零即失败形态）。
     */
    private fun firstPacketPtsUs(file: File, count: Int): List<Long> {
        val ctx = AVFormatContext(null)
        check(avformat.avformat_open_input(ctx, file.absolutePath, null, null) >= 0) { "产物打开失败：${file.absolutePath}" }
        try {
            check(avformat.avformat_find_stream_info(ctx, null as AVDictionary?) >= 0) { "流信息解析失败" }
            val stream = ctx.streams(0)
            val tb = stream.time_base()
            val pkt = avcodec.av_packet_alloc()
            try {
                val pts = mutableListOf<Long>()
                while (pts.size < count && avformat.av_read_frame(ctx, pkt) >= 0) {
                    pts.add(pkt.pts() * 1_000_000L * tb.num() / tb.den())
                    avcodec.av_packet_unref(pkt)
                }
                return pts
            } finally {
                avcodec.av_packet_free(pkt)
            }
        } finally {
            avformat.avformat_close_input(ctx)
        }
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
