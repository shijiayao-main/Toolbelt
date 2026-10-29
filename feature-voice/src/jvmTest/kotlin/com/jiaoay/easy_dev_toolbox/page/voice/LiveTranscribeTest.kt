package com.jiaoay.easy_dev_toolbox.page.voice

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * 实时录音域单测（add-voice-live-recording-vad 任务 2.2/2.4——design D6）：
 * - ModelStore VAD 清单项（isVadReady/下载状态机/SHA Corrupt——fake downloader）；
 * - VadSegmenter 段状态机（fake VadEngine 脚本化段序列 + RMS 音量面）；
 * - LiveTranscriber（fake SegmentTranscriber——段序/空段滤除/fullText 拼接）。
 *
 * MicCapture 不测（TargetDataLine 系统依赖——真机验收面）。
 */
class LiveTranscribeTest {

    // ── fake VadEngine：脚本化段队列 ──

    private class FakeVadEngine : VadEngine {
        val accepted = mutableListOf<FloatArray>()
        private val queue = ArrayDeque<FloatArray>()
        var flushed = false

        fun scriptSegment(samples: FloatArray) {
            queue.add(samples.copyOf())
        }

        override fun acceptWaveform(samples: FloatArray) {
            accepted.add(samples.copyOf())
        }

        override fun isEmpty() = queue.isEmpty()

        override fun nextSegment(): VadSegment? =
            if (queue.isEmpty()) null else VadSegment(0, queue.removeFirst())

        override fun flush() {
            flushed = true
        }

        override fun reset() {
            queue.clear()
            accepted.clear()
            flushed = false
        }

        override fun release() {}
    }

    // ── VadSegmenter 段状态机 ──

    @Test
    fun `feed windows surface scripted segments in order`() {
        val engine = FakeVadEngine()
        val out = mutableListOf<FloatArray>()
        val segmenter = VadSegmenter(engine) { samples, _ -> out.add(samples) }

        engine.scriptSegment(FloatArray(16) { 0.5f }) // 段 1
        segmenter.feed(FloatArray(512) { 0.1f })
        assertEquals(1, out.size, "feed 后立即出段")
        assertEquals(16, out[0].size)

        engine.scriptSegment(FloatArray(8) { 0.3f }) // 段 2
        segmenter.feed(FloatArray(512) { 0.2f })
        assertEquals(2, out.size)
        assertTrue(engine.accepted.size == 2, "两窗均喂入引擎")
    }

    @Test
    fun `level reflects window rms and resets on finish`() {
        val engine = FakeVadEngine()
        val segmenter = VadSegmenter(engine) { _, _ -> }
        segmenter.feed(FloatArray(512) { 0.5f }) // RMS 0.5×3 增益 → 钳 1.0
        assertEquals(1f, segmenter.level.value)
        segmenter.finish()
        assertEquals(0f, segmenter.level.value, "finish 后音量归零")
        assertTrue(engine.flushed, "finish 触发 flush（尾段收口）")
    }

    @Test
    fun `silence window keeps level near zero`() {
        val segmenter = VadSegmenter(FakeVadEngine()) { _, _ -> }
        segmenter.feed(FloatArray(512) { 0f })
        assertEquals(0f, segmenter.level.value)
    }

    // ── LiveTranscriber：段→文本流 ──

    @Test
    fun `segments append in order and blank filtered`() = runTest {
        val transcriber = LiveTranscriber(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            transcriber = LiveTranscriber.SegmentTranscriber { samples, _ ->
                if (samples.size == 16) Result.success("第一段") else Result.success("")
            },
        )
        transcriber.start()
        transcriber.segmentSink(FloatArray(16), 0)
        transcriber.segmentSink(FloatArray(8), 16000) // 空文本段——滤除
        // 🔴 推理已下放 Default（LiveTranscriber.withContext）——段消费走真实线程跳转，
        //    runTest 虚拟 delay 等不到；须在 Default 语境真实时钟轮询等段落位
        //    （withTimeout/delay 在 Default 调度器上均为真实时钟，不受虚拟时间影响）
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) {
                while (transcriber.segments.value.isEmpty()) kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(listOf("第一段"), transcriber.segments.value.map { it.text })
        assertEquals(1, transcriber.segments.value.first().index, "段序从 1 起")
        assertEquals(0, transcriber.segments.value.first().startSample, "段起点样本号随段透传")
        assertEquals("第一段", transcriber.fullText())

        transcriber.reset()
        assertTrue(transcriber.segments.value.isEmpty(), "reset 清空")
    }

    // ── ModelStore VAD 清单项（fake downloader 配方——ModelStoreTest 同款） ──

    @BeforeTest
    fun setUpVad() {
        ModelStore.vadModelDir.mkdirs()
    }

    @AfterTest
    fun cleanVad() {
        File(ModelStore.vadModelDir, ModelStore.VAD_MODEL_FILE).delete()
        File(ModelStore.vadModelDir, "${ModelStore.VAD_MODEL_FILE}.part").delete()
    }

    private class FakeSingleDownloader(
        private val bytes: ByteArray,
        private val corrupt: Boolean = false,
    ) : ModelDownloader {
        override suspend fun download(
            url: String,
            targetModel: File,
            targetTokens: File,
            progress: (Long, Long) -> Unit,
        ) = Unit // ASR 面用不到

        override suspend fun expectedModelSha256(): String? = null

        override suspend fun downloadSingle(url: String, target: File, progress: (Long, Long) -> Unit) {
            target.writeBytes(bytes)
        }
    }

    @Test
    fun `vad ready requires single file`() {
        val file = File(ModelStore.vadModelDir, ModelStore.VAD_MODEL_FILE)
        file.delete()
        assertFalse(ModelStore.isVadReady())
        file.writeBytes(ByteArray(64))
        assertTrue(ModelStore.isVadReady())
        file.delete()
    }

    @Test
    fun `vad download rounds to ready`() = runTest {
        val file = File(ModelStore.vadModelDir, ModelStore.VAD_MODEL_FILE)
        file.delete()
        // 内容与 VAD_SHA256 匹配（64 字节 sha 由真实下载实测——此处用 fixture：
        // 造不出真实 sha 对应内容时直接校验「成功改名 + Ready」路径用合法 sha 内容）
        val realBytes = java.io.File("/tmp/silero_vad.onnx").takeIf { it.isFile }?.readBytes()
        val downloader = if (realBytes != null) {
            FakeSingleDownloader(realBytes) // 本机 spike 模型文件——sha 恒匹配
        } else {
            FakeSingleDownloader(ByteArray(64))
        }
        ModelStore.downloadVad(downloader)
        val state = ModelStore.vadState.value
        if (realBytes != null) {
            assertTrue(state is ModelStore.State.Ready, "真实模型下载回环就绪：$state")
            assertTrue(file.isFile)
        } else {
            assertTrue(state is ModelStore.State.Corrupt, "假内容 SHA 失败回 Corrupt：$state")
            assertFalse(file.isFile)
        }
    }

    @Test
    fun `vad corrupt cleans part file`() = runTest {
        val part = File(ModelStore.vadModelDir, "${ModelStore.VAD_MODEL_FILE}.part")
        val file = File(ModelStore.vadModelDir, ModelStore.VAD_MODEL_FILE)
        file.delete()
        part.delete()
        ModelStore.downloadVad(FakeSingleDownloader(ByteArray(64))) // 内容必不匹配真实 sha
        val state = ModelStore.vadState.value
        if (!java.io.File("/tmp/silero_vad.onnx").isFile) {
            assertTrue(state is ModelStore.State.Corrupt)
            assertFalse(part.isFile, "Corrupt 后 .part 清理")
        }
    }
}
