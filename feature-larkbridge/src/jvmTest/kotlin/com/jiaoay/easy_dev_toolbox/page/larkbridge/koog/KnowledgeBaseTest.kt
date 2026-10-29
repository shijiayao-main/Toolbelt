// 批 7 守护：KnowledgeBase（手册 RAG 知识库）的懒构建 / 检索模式回落 / 三态语义。
//
// 契约（见 KnowledgeBase 类头，2026-09-14 真机反馈后口径）：
// 1. hasContent 只看清单资源在位（同步零网络）；
// 2. 首次 search 懒构建索引（Mutex 守护、幂等——Ready 后不再重建）；
// 3. 🔴 端点无 embeddings ⇒ **回落本地 bigram 检索**（不再是降级——维护者真机实证无此接口，
//    检索照常工作）；本地构建失败（理论不可达）才 Degraded；
// 4. 检索 Top-K + minScore 过滤：相关查询命中手册篇；完全不相关查询无命中返回未检索到文案。
package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class KnowledgeBaseTest {

    /** fake 语义 embedder：定维 bigram 词袋（97 维槽位置 1——维度恒定，零网络）；open 供录制子类扩展 */
    private open class BagOfWordsEmbedder : Embedder {
        val calls = AtomicInteger(0)
        override suspend fun embed(text: String): Vector {
            calls.incrementAndGet()
            val slots = DoubleArray(DIM) { 0.0 }
            val normalized = text.replace(Regex("\\s+"), " ")
            for (i in 0 until normalized.length - 1) {
                val gram = normalized.substring(i, i + 2)
                slots[Math.floorMod(gram.hashCode(), DIM)] = 1.0
            }
            return Vector(slots.toList())
        }

        override fun diff(embedding1: Vector, embedding2: Vector): Double =
            1.0 - embedding1.cosineSimilarity(embedding2)

        companion object {
            private const val DIM = 97
        }
    }

    @BeforeTest
    fun setUp() {
        KnowledgeBase.resetForTest()
        KnowledgeBase.embedderProvider = { BagOfWordsEmbedder() }
        KnowledgeBase.manifestLoader = {
            listOf(
                KnowledgeBase.ManualDocument(id = "xlog-decode.md", content = "XLog 解密：把加密日志文件解密成可读文本。点击「选择文件」，选择 .xlog 或 .alog 文件开始解密。"),
                KnowledgeBase.ManualDocument(id = "shell-tools.md", content = "Shell 工具：一键执行 adb 命令与快捷脚本。在左侧选择设备，然后点击任意快捷指令。"),
            )
        }
    }

    @AfterTest
    fun tearDown() {
        KnowledgeBase.resetForTest()
    }

    @Test
    fun `检索命中手册片段（相关查询返回文件名与相关度）`() = runBlocking {
        val result = KnowledgeBase.search("怎么解密 xlog 日志文件")
        assertNotNull(result)
        assertTrue(result.contains("xlog-decode.md"), "应命中 XLog 手册篇：$result")
        assertTrue(result.contains("XLog 解密"), "应含手册原文片段")
        assertTrue(result.contains("相关度"), "应带相关度标注")
        Unit
    }

    @Test
    fun `不相关查询无命中返回未检索到文案（模型可如实转述）`() = runBlocking {
        // 词袋完全不重叠（菜谱词）——cosine ≈ 0，低于 MIN_SCORE 0.3 ⇒ 无命中
        val result = KnowledgeBase.search("红烧肉怎么做")
        assertNotNull(result, "无命中也应返回可转述文案而非 null")
        assertTrue(result.contains("未检索到"), "应如实说未检索到：$result")
        Unit
    }

    @Test
    fun `懒构建幂等（两次 search 只建一次索引）`() = runBlocking {
        val embedder = BagOfWordsEmbedder()
        KnowledgeBase.embedderProvider = { embedder }
        KnowledgeBase.search("怎么解密")
        val afterFirst = embedder.calls.get()
        assertTrue(afterFirst >= 3, "首次 search 应做全量索引（2 篇手册）+ 1 次查询 embed")
        KnowledgeBase.search("adb 设备")
        assertEquals(afterFirst + 1, embedder.calls.get(), "Ready 后不再重建索引，只增 1 次查询 embed")
        Unit
    }

    @Test
    fun `🔴 端点无 embeddings（embedder 抛异常）⇒ 回落本地 bigram 检索（2026-09-14 维护者真机实证场景）`() = runBlocking {
        KnowledgeBase.embedderProvider = { throw IllegalStateException("404 embeddings not found") }
        val result = KnowledgeBase.search("怎么解密 xlog 日志文件")
        assertNotNull(result, "回落本地检索后查询应有结果")
        assertTrue(result.contains("xlog-decode.md"), "本地 bigram 也应命中 XLog 手册篇：$result")
        assertTrue(!result.contains("知识库暂不可用"), "不再报不可用——本地检索在位：$result")
        Unit
    }

    @Test
    fun `🔴 端点未配（embedder null）⇒ 同样回落本地检索（检索无条件可用）`() = runBlocking {
        KnowledgeBase.embedderProvider = { null }
        val result = KnowledgeBase.search("shell 工具怎么执行 adb 命令")
        assertNotNull(result, "端点未配也应能本地检索")
        assertTrue(result.contains("shell-tools.md"), "应命中 Shell 手册篇：$result")
        Unit
    }

    @Test
    fun `本地检索模式完全不相关查询同样如实未命中`() = runBlocking {
        KnowledgeBase.embedderProvider = { null }
        val result = KnowledgeBase.search("红烧肉怎么做")
        assertNotNull(result)
        assertTrue(result.contains("未检索到"), "本地模式无命中的文案与语义模式一致：$result")
        Unit
    }

    @Test
    fun `空清单 ⇒ 知识库不可用（search null）`() = runBlocking {
        KnowledgeBase.manifestLoader = { emptyList() }
        assertNull(KnowledgeBase.search("怎么解密"))
        Unit
    }

    @Test
    fun `多小节手册各块 embed 含各自标题行（块快照非末块标题）`() = runBlocking {
        // 回归锚：headline 曾在切块循环结束后取末值——除末块外全部段落块共用最后
        // 一个小节标题，「标题行三重计入」加权从未生效且注入跨主题噪音词
        val embedInputs = mutableListOf<String>()
        KnowledgeBase.embedderProvider = {
            object : BagOfWordsEmbedder() {
                override suspend fun embed(text: String): Vector {
                    embedInputs += text
                    return super.embed(text)
                }
            }
        }
        KnowledgeBase.manifestLoader = {
            listOf(
                KnowledgeBase.ManualDocument(
                    id = "multi.md",
                    content = listOf(
                        "# 多节手册",
                        "## A 安装",
                        "安装说明安装说明",
                        "## B 卸载",
                        "卸载说明卸载说明",
                    ).joinToString("\n"),
                ),
            )
        }
        KnowledgeBase.search("安装")
        val chunkTexts = embedInputs.dropLast(1) // 末位是查询 embed
        assertEquals(3, chunkTexts.size, "前导段 + A 块 + B 块：${chunkTexts.size}")
        val chunkA = chunkTexts[1]
        val chunkB = chunkTexts[2]
        assertEquals(3, chunkA.split("## A 安装").size - 1, "A 块自身标题三重计入（加权 ×2 + 正文 ×1）：$chunkA")
        assertEquals(0, chunkA.split("## B 卸载").size - 1, "🔴 A 块不得混入 B 块标题（末块标题污染回归锚）：$chunkA")
        assertEquals(3, chunkB.split("## B 卸载").size - 1, "B 块自身标题三重计入：$chunkB")
        assertEquals(0, chunkB.split("## A 安装").size - 1, "🔴 B 块不得混入 A 块标题：$chunkB")
        Unit
    }
}
