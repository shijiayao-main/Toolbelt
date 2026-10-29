package com.jiaoay.easy_dev_toolbox.page.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 模型分发状态机单测（P4-S voice-transcribe 任务 1.3——[ModelStore] + fake 下载器）。
 *
 * 🔴 目录接缝：ModelStore.modelDir 是常量（真实数据目录）——单测经「手动放置」路径
 * 写入后立即清理（本机开发者目录共享；不重建对象目录注入——object 常量不可注，
 * 改造为可注入会破坏单例门面形态，权衡后测试自我清理）。
 */
class ModelStoreTest {

    /** fake 下载器：字节数组直写目标 + 可注入 SHA */
    private class FakeDownloader(
        private val modelBytes: ByteArray = ByteArray(1024) { it.toByte() },
        private val tokensBytes: ByteArray = "tokens".toByteArray(),
        private val sha: String? = null,
    ) : ModelDownloader {
        override suspend fun download(
            url: String,
            targetModel: File,
            targetTokens: File,
            progress: (Long, Long) -> Unit,
        ) {
            targetModel.writeBytes(modelBytes)
            targetTokens.writeBytes(tokensBytes)
            progress(modelBytes.size.toLong(), modelBytes.size.toLong())
        }

        override suspend fun expectedModelSha256(): String? = sha
    }

    /** SHA-256 工具（与 ModelStore 同式） */
    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `就绪判定双文件在位`() {
        // 清场 → 未就绪；手动放置 → 就绪；refresh 落 Ready
        cleanup()
        ModelStore.refresh()
        assertTrue(ModelStore.state.value is ModelStore.State.Unknown, "空目录应为 Unknown")

        File(ModelStore.modelDir, ModelStore.MODEL_FILE).writeBytes(ByteArray(16))
        File(ModelStore.modelDir, ModelStore.TOKENS_FILE).writeBytes(ByteArray(16))
        assertTrue(ModelStore.isReady(), "双文件在位即就绪（手动放置路径）")
        ModelStore.refresh()
        assertTrue(ModelStore.state.value is ModelStore.State.Ready)
        cleanup()
    }

    @Test
    fun `fake 下载回环到就绪`() {
        cleanup()
        ModelStore.refresh()
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        kotlinx.coroutines.runBlocking {
            ModelStore.download(FakeDownloader(modelBytes = bytes, sha = sha256(bytes)))
        }
        assertTrue(ModelStore.isReady(), "下载完成双文件应就位")
        assertTrue(ModelStore.state.value is ModelStore.State.Ready)
        // 内容恒等
        assertEquals(bytes.size.toLong(), File(ModelStore.modelDir, ModelStore.MODEL_FILE).length())
        cleanup()
    }

    @Test
    fun `SHA 校验失败清理回 Corrupt`() {
        cleanup()
        kotlinx.coroutines.runBlocking {
            ModelStore.download(FakeDownloader(sha = "deadbeef".repeat(8)))
        }
        assertTrue(ModelStore.state.value is ModelStore.State.Corrupt, "SHA 不符应为 Corrupt")
        assertTrue(!ModelStore.isReady(), "part 文件已清理，正名文件不应在位")
        assertTrue(!File(ModelStore.modelDir, "${ModelStore.MODEL_FILE}.part").exists(), "part 须删除")
        cleanup()
    }

    @Test
    fun `已就绪时下载跳过`() {
        cleanup()
        placeReadyFiles()
        kotlinx.coroutines.runBlocking {
            ModelStore.download(FakeDownloader(modelBytes = "不同内容".toByteArray()))
        }
        assertTrue(ModelStore.state.value is ModelStore.State.Ready)
        assertEquals(3L, File(ModelStore.modelDir, ModelStore.MODEL_FILE).length(), "已就绪不重下——内容不变")
        cleanup()
    }

    @Test
    fun `下载失败回 Failed 且清理 part`() {
        cleanup()
        val boom = object : ModelDownloader {
            override suspend fun download(
                url: String,
                targetModel: File,
                targetTokens: File,
                progress: (Long, Long) -> Unit,
            ) {
                targetModel.writeBytes(ByteArray(10))
                throw IllegalStateException("网络中断")
            }

            override suspend fun expectedModelSha256(): String? = null
        }
        kotlinx.coroutines.runBlocking { ModelStore.download(boom) }
        assertTrue(ModelStore.state.value is ModelStore.State.Failed, "异常应为 Failed：${ModelStore.state.value}")
        assertTrue(!File(ModelStore.modelDir, "${ModelStore.MODEL_FILE}.part").exists())
        cleanup()
    }

    private fun placeReadyFiles() {
        File(ModelStore.modelDir, ModelStore.MODEL_FILE).writeBytes("abc".toByteArray())
        File(ModelStore.modelDir, ModelStore.TOKENS_FILE).writeBytes("tk".toByteArray())
    }

    private fun cleanup() {
        File(ModelStore.modelDir, ModelStore.MODEL_FILE).delete()
        File(ModelStore.modelDir, ModelStore.TOKENS_FILE).delete()
        File(ModelStore.modelDir, "${ModelStore.MODEL_FILE}.part").delete()
        File(ModelStore.modelDir, "${ModelStore.TOKENS_FILE}.part").delete()
    }
}
