package com.jiaoay.easy_dev_toolbox.core.media

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 音频解码器单测（P4-S voice-transcribe 任务 1.2——[AudioTranscoder] 直测缝）。
 *
 * 零 ffmpeg 依赖面：argv 构造纯函数 + wav RIFF 头解析（手工造头，不依赖外部样本）。
 */
class AudioTranscoderTest {

    // ── argv 构造（纯函数） ──

    @Test
    fun `解码 argv 形态对齐 16k mono pcm_s16le`() {
        val input = File("/tmp/a.mp3")
        val output = File("/tmp/out.wav")
        val argv = AudioTranscoder.decodeArgv(input, output)
        assertEquals(
            listOf(
                "ffmpeg", "-y", "-loglevel", "error",
                "-i", "/tmp/a.mp3",
                "-ar", "16000",
                "-ac", "1",
                "-c:a", "pcm_s16le",
                "/tmp/out.wav",
            ),
            argv,
        )
        // argv 无 shell 注入面：路径原样进参数组（不经 shell 解释）
        assertEquals("/tmp/a.mp3", argv[5])
    }

    // ── wav 头解析 ──

    /** 造最小 16bit PCM mono wav（含 fmt + data 块） */
    private fun wav16kMono(pcm: ShortArray): File {
        val dataBytes = pcm.size * 2
        val out = ByteArrayOutputStream()
        fun leShort(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        fun leInt(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
        out.write("RIFF".toByteArray()); out.write(leInt(36 + dataBytes)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(leInt(16))
        out.write(leShort(1))          // PCM
        out.write(leShort(1))          // mono
        out.write(leInt(16000))        // sample rate
        out.write(leInt(16000 * 2))    // byte rate
        out.write(leShort(2))          // block align
        out.write(leShort(16))         // bits
        out.write("data".toByteArray()); out.write(leInt(dataBytes))
        pcm.forEach { out.write(leShort(it.toInt())) }
        val f = kotlin.io.path.createTempDirectory("voice-wav").resolve("t.wav").toFile()
        f.writeBytes(out.toByteArray())
        return f
    }

    @Test
    fun `wav 16bit mono 解析出样本与采样率`() {
        val pcm = shortArrayOf(0, 16384, -16384, 8192)
        val wav = wav16kMono(pcm)
        val result = AudioTranscoder.readWavPcm(wav)
        assertTrue(result.isSuccess, "解析应成功：${result.exceptionOrNull()?.message}")
        val (samples, rate) = result.getOrThrow()
        assertEquals(16000, rate)
        assertEquals(4, samples.size)
        assertEquals(0.5f, samples[1], 0.001f)   // 16384/32768
        assertEquals(-0.5f, samples[2], 0.001f)
    }

    @Test
    fun `非 wav 与坏头安全降级`() {
        val dir = kotlin.io.path.createTempDirectory("voice-wav").toFile()
        val notWav = File(dir, "a.txt").apply { writeText("普通文本不是音频") }
        val badRiff = File(dir, "b.wav").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val truncated = File(dir, "c.wav").apply { writeBytes("RIFF\u0000\u0000\u0000WAVE".toByteArray()) }

        assertTrue(AudioTranscoder.readWavPcm(notWav).isFailure)
        assertTrue(AudioTranscoder.readWavPcm(badRiff).isFailure)
        assertTrue(AudioTranscoder.readWavPcm(truncated).isFailure)
        assertTrue(AudioTranscoder.readWavPcm(File(dir, "nope.wav")).isFailure, "不存在文件也须失败")
    }

    @Test
    fun `ffmpeg 探测有缓存且不炸`() {
        // 本机有 ffmpeg（截图管线在用）——探测应为 true 且二次调用直接命中缓存
        val first = AudioTranscoder.isFfmpegAvailable()
        val second = AudioTranscoder.isFfmpegAvailable()
        assertEquals(first, second, "二次探测应命中缓存（结果一致）")
    }
}
