package com.jiaoay.easy_dev_toolbox.page.voice

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 下载进度节流验收面（[ProgressThrottle] + [ModelStore] 接线）：
 * - 节流器纯逻辑：虚拟时钟驱动窗语义（窗内合并/窗到期发布合并值/完成收口强制发射）；
 * - ModelStore 接线：注入时钟冻结窗 → 末块不足一窗时完成收口仍把进度冲到最终值
 *   （SHA 门经 CompletableDeferred 门闩等观察者确认——无 flush 则超时红，零真实 sleep）。
 *
 * 🔴 观察者纪律：StateFlow 按值合并，相邻发射若无挂起点会被合并吞掉——
 * 接线测试的 fake 下载器在每次期望被观察的发射后 await 门闩，等 collector 落位再继续。
 */
class ModelStoreProgressThrottleTest {

    // ── 节流器纯逻辑（虚拟时钟——零真实等待） ──

    @Test
    fun `节流器窗内高频回调合并且窗到期发布合并值`() {
        var now = 0L
        val emitted = mutableListOf<Pair<Long, Long>>()
        val throttle = ProgressThrottle(windowMillis = 100, nowMillis = { now }) { r, t ->
            emitted.add(r to t)
        }

        throttle.onProgress(10, 100) // 首回调必发射（0% 起点可见）
        now = 10
        throttle.onProgress(20, 100) // 窗内——只记合并值
        throttle.onProgress(30, 100) // 窗内——合并值被最新覆盖
        now = 99
        throttle.onProgress(40, 100) // 99-0 < 100 仍窗内
        now = 100
        throttle.onProgress(50, 100) // 窗到期——发布合并值

        assertEquals(listOf(10L to 100L, 50L to 100L), emitted, "窗内 20/30/40 不发射，窗到期只发合并值 50")
    }

    @Test
    fun `节流器完成收口强制发射末块进度`() {
        var now = 0L
        val emitted = mutableListOf<Pair<Long, Long>>()
        val throttle = ProgressThrottle(windowMillis = 100, nowMillis = { now }) { r, t ->
            emitted.add(r to t)
        }

        throttle.onProgress(10, 100)
        now = 10
        throttle.onProgress(100, 100) // 末块不足一个时间窗——窗内滞留
        throttle.flush() // 完成收口：强制冲到最终值，不停在 99%

        assertEquals(listOf(10L to 100L, 100L to 100L), emitted, "末块进度须被 flush 强制发射")

        throttle.flush() // 无滞留——no-op，不重复发射末值
        assertEquals(2, emitted.size, "无滞留时 flush 不得重复发射")
    }

    // ── ModelStore 接线（时钟缝注入 + 门闩观察——零真实 sleep） ──

    @Test
    fun `下载完成末块进度强制发射（末块不足一窗）`() {
        cleanupModelDir()
        // 时钟冻结：末块恒不足一个时间窗——flush 语义成为唯一通路
        val originalClock = ModelStore.progressNowMillis
        ModelStore.progressNowMillis = { 0L }
        try {
            val finalProgressSeen = CompletableDeferred<Unit>()
            val seen = mutableListOf<ModelStore.State>()
            val downloader = object : ModelDownloader {
                override suspend fun download(
                    url: String,
                    targetModel: File,
                    targetTokens: File,
                    progress: (Long, Long) -> Unit,
                ) {
                    targetModel.writeBytes(ByteArray(128))
                    targetTokens.writeBytes(ByteArray(16))
                    progress(64, 128) // 首回调发射
                    progress(128, 128) // 末块——冻结时钟下窗内滞留
                }

                override suspend fun expectedModelSha256(): String? {
                    // SHA 门：等观察者确认末块进度已强制发射（无 flush 则本门永不开放→超时红）
                    withTimeout(10_000) { finalProgressSeen.await() }
                    return null
                }
            }
            runBlocking {
                val collector = launch {
                    ModelStore.state.collect { s ->
                        seen.add(s)
                        if (s == ModelStore.State.Downloading(128, 128)) finalProgressSeen.complete(Unit)
                    }
                }
                withTimeout(10_000) { ModelStore.download(downloader) }
                collector.cancel()
            }
            assertTrue(
                ModelStore.State.Downloading(128, 128) in seen,
                "完成时末块进度须强制发射（SHA 校验前观察者可见 100%）：$seen",
            )
            assertTrue(ModelStore.state.value is ModelStore.State.Ready, "完成/失败语义不变——回环 Ready")
            assertTrue(ModelStore.isReady())
        } finally {
            ModelStore.progressNowMillis = originalClock
            cleanupModelDir()
        }
    }

    @Test
    fun `下载窗内进度合并窗到期发布合并值（接线）`() {
        cleanupModelDir()
        val originalClock = ModelStore.progressNowMillis
        val clock = AtomicLong(0L)
        ModelStore.progressNowMillis = { clock.get() }
        try {
            val seenAt10 = CompletableDeferred<Unit>()
            val seenAt40 = CompletableDeferred<Unit>()
            val seenAt100 = CompletableDeferred<Unit>()
            val seen = mutableListOf<ModelStore.State>()
            val downloader = object : ModelDownloader {
                override suspend fun download(
                    url: String,
                    targetModel: File,
                    targetTokens: File,
                    progress: (Long, Long) -> Unit,
                ) {
                    targetModel.writeBytes(ByteArray(4))
                    targetTokens.writeBytes(ByteArray(2))
                    progress(10, 100) // 首回调发射
                    seenAt10.await() // 等观察者落位——防相邻发射被 StateFlow 合并吞掉
                    clock.addAndGet(50)
                    progress(20, 100) // 窗内——只记合并值
                    progress(30, 100) // 窗内——合并值被最新覆盖
                    clock.addAndGet(60) // 时钟推进到 110 ≥ 窗
                    progress(40, 100) // 窗到期——发布合并值
                    seenAt40.await()
                    progress(100, 100) // 末块窗内滞留
                }

                override suspend fun expectedModelSha256(): String? {
                    withTimeout(10_000) { seenAt100.await() } // flush 冲出的末块进度观察确认
                    return null
                }
            }
            runBlocking {
                val collector = launch {
                    ModelStore.state.collect { s ->
                        seen.add(s)
                        when (s) {
                            ModelStore.State.Downloading(10, 100) -> seenAt10.complete(Unit)
                            ModelStore.State.Downloading(40, 100) -> seenAt40.complete(Unit)
                            ModelStore.State.Downloading(100, 100) -> seenAt100.complete(Unit)
                            else -> Unit
                        }
                    }
                }
                withTimeout(10_000) { ModelStore.download(downloader) }
                collector.cancel()
            }
            val downloading = seen
                .filterIsInstance<ModelStore.State.Downloading>()
                .filter { it.totalBytes == 100L }
            assertEquals(
                listOf(10L, 40L, 100L),
                downloading.map { it.downloadedBytes },
                "窗内 20/30 合并不发射；窗到期发布 40；完成收口冲出 100：$downloading",
            )
            assertTrue(ModelStore.state.value is ModelStore.State.Ready)
        } finally {
            ModelStore.progressNowMillis = originalClock
            cleanupModelDir()
        }
    }

    private fun cleanupModelDir() {
        File(ModelStore.modelDir, ModelStore.MODEL_FILE).delete()
        File(ModelStore.modelDir, ModelStore.TOKENS_FILE).delete()
        File(ModelStore.modelDir, "${ModelStore.MODEL_FILE}.part").delete()
        File(ModelStore.modelDir, "${ModelStore.TOKENS_FILE}.part").delete()
    }
}
