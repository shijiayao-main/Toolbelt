package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.rag.base.TextDocument
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import ai.koog.rag.vector.backend.InMemoryVectorStorageBackend
import ai.koog.rag.vector.embedder.DocumentEmbedder
import ai.koog.rag.vector.storage.EmbeddingStorage
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 工具使用手册知识库（RAG）：把随 App 分发的用户手册喂给 Koog 调度 agent，
 * 飞书里有人问「这个工具怎么用」时，agent 经 `search_knowledge` 工具检索手册回答。
 *
 * 内容与重建：手册真源 `docs/manual/` 构建期进 resources 随 App 分发（使用者零配置）；
 * Koog `EmbeddingStorage.add` 是 UUID 追加式（无去重/无变更检测/无「源文件→ID」反查），
 * 故 InMemory 后端 + 进程内首次使用时全量重建（不做落盘与增量）——新手册随版本打包，
 * 重启即自动全新索引，升级零代码零配置。
 * 检索模式与回落策略（语义 embedder 优先、本地 bigram 兜底、LOCAL_MIN_SCORE 放宽语义）、
 * 状态机与 Degraded 语义、测试接缝见 docs/pages/lark-bridge.md「知识库检索（RAG）」节。
 * [embedderProvider] / [manifestLoader] 为 internal 测试接缝（先例 [LlmClientFactory.executorProvider]）。
 */
internal object KnowledgeBase {

    /** 手册资源在 jar 内的根路径（构建期 syncManualToResources 产物） */
    private const val MANUAL_RESOURCE_DIR = "/manual"

    /** 清单文件（classloader 枚举不了 jar 内资源目录——清单是发现手册的唯一途径） */
    private const val MANIFEST_RESOURCE = "$MANUAL_RESOURCE_DIR/manifest.txt"

    /**
     * 检索相似度下限（cosine 语义：1 - diff）。语义向量模式 0.3；本地 bigram 模式用
     * [LOCAL_MIN_SCORE] 放宽——**本地检索只负责召回，选段是调度模型（chat LLM）的活**：
     * 候选段宁多勿漏（正确段离线实测 0.28+，0.2 保证进池；错段由模型自行忽略）。
     */
    internal const val MIN_SCORE = 0.3
    internal const val LOCAL_MIN_SCORE = 0.2

    /** 索引状态（sealed：构建成功前后的可见性收敛在 [state] 一个引用上） */
    private sealed interface State {
        data object NotReady : State

        /** @param semantic true = 语义向量索引（LLM embedder）；false = 本地 bigram 检索（端点不可用回落） */
        data class Ready(val storage: EmbeddingStorage<ManualDocument>, val semantic: Boolean) : State
        data class Degraded(val fingerprint: String, val reason: String) : State
    }

    private val state = AtomicReference<State>(State.NotReady)
    private val mutex = Mutex()

    /**
     * 生产默认接缝（resetForTest 恢复用；置前声明——接缝 var 的初始化引用它们）。
     * endpoint.modelId 恒传空串：[LlmEndpointConfig] 是 chat 端点快照，embedding 模型不经它
     *    （走 [LlmClientFactory.embeddingModel] 独立声明——见 LlmClientFactory.embeddingClient KDoc）。
     */
    private val DEFAULT_EMBEDDER_PROVIDER: suspend (String) -> Embedder? =
        suspend lambda@{ modelId ->
            val apiKey = LlmConfigStore.getApiKey()
            if (apiKey.isBlank()) return@lambda null
            // S4 单点构造 + modelIdOverride：占位空串语义保留（embedding 独立向量模型，不掺缓存 key）
            val client = LlmClientFactory.embeddingClient(LlmEndpointConfig.read(modelIdOverride = ""))
            LLMEmbedder(client, LlmClientFactory.embeddingModel(modelId))
        }

    private val DEFAULT_MANIFEST_LOADER: suspend () -> List<ManualDocument> = {
        val manifest = KnowledgeBase::class.java.getResourceAsStream(MANIFEST_RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)?.readText()
        manifest.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { name ->
                val content = KnowledgeBase::class.java.getResourceAsStream("$MANUAL_RESOURCE_DIR/$name")
                    ?.bufferedReader(Charsets.UTF_8)?.readText()
                content?.let { ManualDocument(id = name, content = it) }
            }
            .toList()
    }

    private val DEFAULT_EMBEDDING_MODEL_READER: suspend () -> String = {
        LlmConfigStore.getEmbeddingModelId().ifBlank { LlmConfigStore.DEFAULT_EMBEDDING_MODEL_ID }
    }

    /**
     * 测试接缝：embedder 工厂（生产 = 现读 DataStore 端点构造 LLMEmbedder）。
     * 参数：向量模型 ID（已落缺省值）；返回 null = 端点未配置（apiKey 空——不视为降级，静默不建）。
     */
    internal var embedderProvider: suspend (embeddingModelId: String) -> Embedder? = DEFAULT_EMBEDDER_PROVIDER

    /** 测试接缝：清单加载（返回手册文件名列表；生产从 classloader 读 manifest.txt） */
    internal var manifestLoader: suspend () -> List<ManualDocument> = DEFAULT_MANIFEST_LOADER

    /**
     * 测试接缝：向量模型 ID 读取（生产 = DataStore，空串落 [LlmConfigStore.DEFAULT_EMBEDDING_MODEL_ID]）。
     * 独立接缝的原因：[configFingerprint] 与 [buildIndexLocked] 都读它——测试注入内存假值
     * （测试 JVM 不依赖真实 DataStore）。
     */
    internal var embeddingModelIdReader: suspend () -> String = DEFAULT_EMBEDDING_MODEL_READER

    /** 同步可用性判定（工具注册期调用——只看清单资源是否打包在位，零网络零构建） */
    fun hasContent(): Boolean =
        KnowledgeBase::class.java.getResourceAsStream(MANIFEST_RESOURCE) != null

    /**
     * 确保索引就绪（幂等；Mutex 守护懒构建）。构建失败 ⇒ 降级态 + 如实 reason；
     * 端点未配 apiKey ⇒ 保持 NotReady（下次再试，不标记降级——端点未配是配置问题而非故障）。
     */
    private suspend fun ensureReady(): State = mutex.withLock {
        val current = state.get()
        when (current) {
            is State.Ready -> current
            is State.Degraded -> {
                // 指纹变化（改了向量模型/端点）⇒ 允许重试；未变 ⇒ 维持降级（每次 search 都打失败网络不可接受）
                if (current.fingerprint == configFingerprint()) current
                else buildIndexLocked() ?: current
            }
            State.NotReady -> buildIndexLocked() ?: State.NotReady
        }
    }

    /** 🔴 仅在 [mutex] 内调用（绕开锁并发构建会竞态写索引状态） */
    private suspend fun buildIndexLocked(): State? {
        val docs = manifestLoader()
        if (docs.isEmpty()) return null // 清单空/资源缺失：知识库不可用（NotReady 语义）
        val embeddingModelId = embeddingModelIdReader()
        // 语义 embedder：端点未配（null）⇒ 静默落本地检索；构造失败 ⇒ 同样落本地检索
        //（无 /v1/embeddings 接口的端点是常态而非异常——回落而非降级）
        val semanticEmbedder = try {
            embedderProvider(embeddingModelId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            JLog.i(TAG) { "语义 embedder 构建失败，回落本地 bigram 检索：${e.message?.take(120)}" }
            null
        }
        // 语义索引构建（embed 首次真调用发生在 add 内——构建成功≠端点真支持 embeddings；
        //    add 失败同样回落本地（本地 bigram 无外部依赖，理论不可达失败——真失败才 Degraded））
        // 段落切块：整篇 embed 对短查询稀释严重（冒烟 5 问 1 中）——
        //    按标题分块后短查询的相似度集中在相关段落，召回质量恢复。
        val chunks = docs.flatMap { chunkManual(it) }.ifEmpty { docs }
        if (semanticEmbedder != null) {
            try {
                val storage = EmbeddingStorage(
                    embedder = ManualDocumentEmbedder(semanticEmbedder),
                    storage = InMemoryVectorStorageBackend(),
                )
                storage.add(chunks)
                return State.Ready(storage, semantic = true).also { state.set(it) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                JLog.i(TAG) { "语义索引构建失败，回落本地 bigram 检索：${e.message?.take(120)}" }
                // 落到下方本地构建
            }
        }
        return try {
            val local = EmbeddingStorage(
                embedder = ManualDocumentEmbedder(LocalBigramEmbedder),
                storage = InMemoryVectorStorageBackend(),
            )
            local.add(chunks) // InMemory 后端 add 全量重建（见类头「升级丝滑」节）
            State.Ready(local, semantic = false).also { state.set(it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            state.set(State.Degraded(configFingerprint(), e.message ?: e::class.simpleName.orEmpty()))
            state.get()
        }
    }

    /** 降级指纹：向量模型 + 端点（apiKey 只取尾 4 位——指纹不落盘，仅内存比对；模型经测试接缝） */
    private suspend fun configFingerprint(): String {
        val model = embeddingModelIdReader()
        val apiKey = LlmConfigStore.getApiKey()
        val baseUrl = LlmConfigStore.getBaseUrl()
        return "$model@${baseUrl.takeLast(24)}#${apiKey.takeLast(4)}"
    }

    /**
     * 检索手册片段（Top-K + minScore 过滤）。给 Koog 工具的返回已拼为可读文本；
     * 索引未就绪/降级时返回 null，调用方（工具）负责转述降级原因。
     */
    suspend fun search(query: String, topK: Int = 4): String? {
        if (query.isBlank()) return null
        return when (val s = ensureReady()) {
            is State.Ready -> s.storage.search(
                SimilaritySearchRequest(
                    queryText = query,
                    limit = topK,
                    minScore = if (s.semantic) MIN_SCORE else LOCAL_MIN_SCORE,
                ),
            ).takeIf { it.isNotEmpty() }
                ?.joinToString("\n\n") { r ->
                    val doc = r.document
                    // 段落块 id 形如 "xlog-decode.md#§3 怎么用"——文件名与段落标题一并展示
                    "[手册：${doc.id}｜相关度 ${(r.score.value * 100).toInt()}%]\n${doc.content}"
                }
                ?: "手册中未检索到相关内容（或知识库尚未就绪）。请如实告知用户，不要编造工具用法。"
            is State.Degraded -> "知识库暂不可用（${s.reason}）——请在 AI 设置页检查向量模型配置。"
            State.NotReady -> null
        }
    }

    /** 检索结果为降级文案时调用方可见（工具层据此不把降级文案当检索结果渲染） */
    fun isDegradedReply(reply: String): Boolean = reply.startsWith("知识库暂不可用")

    /** 测试接缝：状态归零 + 生产接缝恢复（每用例 BeforeTest/AfterTest 调用） */
    internal fun resetForTest() {
        state.set(State.NotReady)
        embedderProvider = DEFAULT_EMBEDDER_PROVIDER
        manifestLoader = DEFAULT_MANIFEST_LOADER
        embeddingModelIdReader = DEFAULT_EMBEDDING_MODEL_READER
    }

    /** 手册文档（rag-base [TextDocument]；id = 手册文件名，内容即全文——短篇 ≤150 行不切块） */
    internal data class ManualDocument(
        override val content: String,
        override val id: String,
        override val metadata: Map<String, Any> = emptyMap(),
    ) : TextDocument

    /**
     * 按 `##` 标题把手册切成段落块（整篇 embed 对短查询稀释严重——冒烟
     * 5 问 1 中；官方 rag 无内置 chunking，自切）。**块内容并入文件名与一级标题**（最强主题词
     * 锚点：`xlog-decode` / `# XLog 解析`）；**标题行三重计入**（标题是主题词密度最高的行，
     * 加权对抗「短查询 vs 长段落」的稀释——真机实测把正确段从池外拉进 Top-4）。
     */
    private fun chunkManual(doc: ManualDocument): List<ManualDocument> {
        data class Chunk(val title: String, val headline: String, val body: String)

        // 一级标题（`# XLog 解析`）是整篇的主题词——并入每个块
        val h1 = doc.content.lineSequence().firstOrNull { it.startsWith("# ") }?.trim().orEmpty()
        val anchor = listOf(doc.id, h1).filter { it.isNotBlank() }.joinToString("\n")

        val chunks = mutableListOf<Chunk>()
        var currentTitle = ""
        var currentHeadline = "" // 本块自己的标题行（含前导 ## 的原文）
        val current = StringBuilder()
        doc.content.lineSequence().forEach { line ->
            if (line.startsWith("## ")) {
                if (current.isNotEmpty()) chunks.add(Chunk(currentTitle, currentHeadline, current.toString().trim()))
                currentTitle = line.removePrefix("## ").trim()
                currentHeadline = line
                current.clear()
                current.appendLine(line)
            } else {
                current.appendLine(line)
            }
        }
        if (current.isNotEmpty()) chunks.add(Chunk(currentTitle, currentHeadline, current.toString().trim()))
        return chunks.mapIndexed { i, c ->
            // embed 文本 = 锚点(文件名+H1) ×1 + 本块标题行 ×2 + 正文 ×1——标题权重三重计入
            // （headline 必须取本块快照值：块构造后闭包再读外部 var 会拿到末块的标题）
            val embedText = "$anchor\n${c.headline}\n${c.headline}\n${c.body}"
            ManualDocument(
                content = embedText,
                id = "${doc.id}#§${i + 1} ${c.title}".ifBlank { "${doc.id}#§${i + 1}" },
            )
        }
    }

    /**
     * rag-vector 的 JVM 便捷类（JVMFileEmbeddingStorage 等）全是 Path 基（面向磁盘文件），
     * 手册在 jar 里是字符串——本适配器把 [Embedder] 桥到 [DocumentEmbedder]（手册内容直接
     * 进 embed；query 走 [Embedder.embed] 原样透传）。
     */
    private class ManualDocumentEmbedder(private val delegate: Embedder) : DocumentEmbedder<ManualDocument> {
        override suspend fun embed(document: ManualDocument): Vector = delegate.embed(document.content)
        override suspend fun embed(text: String): Vector = delegate.embed(text)
        override fun diff(embedding1: Vector, embedding2: Vector): Double = delegate.diff(embedding1, embedding2)
    }

    private const val TAG = "KnowledgeBase"
}

/**
 * 本地 bigram 检索 embedder（端点无 /v1/embeddings 时的回落模式）。
 *
 * 算法：文本的**相邻字符对**（bigram）哈希进定维槽位（置 1）→ 相似度 = **重叠系数**
 * （overlap = |相交| / min(|查询|, |文档|)）。度量选择（真机三轮调参实证）：cosine 对
 * 「短查询 vs 长段落」天然稀释（分母含文档长度）——正确段全被长文档挤出 Top-4；重叠系数
 * 衡量「用户问的词这段覆盖了多少」，短查询不再被文档长度惩罚——离线+真机验证正确段
 * 全部进 Top-2（「xlog 解密怎么用」0.89、「时间戳怎么转」0.60）。
 * 定维槽位（[Vector.cosineSimilarity] 同维硬检查）；大小写归一 + 空白折叠；纯内存零网络。
 * diff 返回 `1 - overlap`（Embedder 契约：diff 越小越相似——方向写反会让检索反向排出最不相关段）——rag-vector 的
 *    `EmbeddingStorage.score = 1 - diff` 恰好还原 overlap 作为分数展示。
 */
private object LocalBigramEmbedder : Embedder {

    /**
     * 定维槽数。调参实证：97 维下查询 gram 与长文档 gram 的哈希
     * 碰撞把 overlap 推向饱和（长文档占满槽位 ⇒ 任何查询都是其子集 ⇒ 全员 100%——
     * 区分度归零）；2048 维（UTF-16 char pair 的哈希空间）碰撞率趋零，重叠系数恢复
     * 「查询覆盖率」语义。手册 40 块 × 2048 double = ~650KB——内存可忽略。
     */
    private const val DIM = 2048

    override suspend fun embed(text: String): Vector {
        val normalized = text.lowercase().replace(Regex("\\s+"), " ")
        if (normalized.length < 2) return Vector(List(DIM) { 0.0 })
        val slots = DoubleArray(DIM) { 0.0 }
        for (i in 0 until normalized.length - 1) {
            val gram = normalized.substring(i, i + 2)
            slots[Math.floorMod(gram.hashCode(), DIM)] = 1.0
        }
        return Vector(slots.toList())
    }

    /** 重叠系数：相交槽数 / 较小向量非零槽数。任一为零向量 ⇒ 0 相似（diff=1）。 */
    override fun diff(embedding1: Vector, embedding2: Vector): Double {
        val a = embedding1.values
        val b = embedding2.values
        val inter = a.indices.count { a[it] > 0.0 && b[it] > 0.0 }
        val minSize = minOf(a.count { it > 0.0 }, b.count { it > 0.0 })
        val overlap = if (minSize == 0) 0.0 else inter.toDouble() / minSize
        return 1.0 - overlap
    }
}
