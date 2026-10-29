package com.jiaoay.easy_dev_toolbox.core.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 知识库包导入导出（团队分享的交换格式）。
 *
 * ## 格式（KnowledgePack · schemaVersion **新真源从 1 起步**）
 * 单一 JSON 文档，与 bridge-config 的 6/7 旧账互不干扰（总蓝图拍板）。四表全量转储：
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "exportedAt": 1760000000000,
 *   "repos": [
 *     { "repo": "repo-alpha", "docs": [...], "tags": [...], "decisionNodes": [...], "goldenSamples": [...] }
 *   ]
 * }
 * ```
 * 结构选择「按 repo 分组」而非四表平铺：repo 是知识的**逻辑边界**（TAG_REGISTRY 主映射按
 * 仓库分节，跨仓同名 TAG 是两套独立代码），导入 = 按 repo 整组替换（[importPack]）或全库
 * 替换（[importPack] mode=REPLACE_ALL）。
 *
 * ## 语义
 * - **导出**（[exportPack]）：全库转储 → `~/.easy_dev_toolbox/knowledge/exports/` 或调用方指定路径；
 * - **导入**（[importPack]）：单事务整包写入，两种模式——
 *   - [ImportMode.REPLACE_ALL]：清空四表再写入（整包替换，默认——知识包是分享全量镜像）；
 *   - [ImportMode.REPLACE_PER_REPO]：只替换包内出现的 repo（增量导入，不动其他仓库）；
 * - **幂等**：upsert 语义（UNIQUE 冲突覆写）——同一包重复导入结果一致；
 * - **校验**：schemaVersion > 当前支持版本拒绝导入（前向兼容——旧版 App 读不懂新包，
 *   明确失败优于静默截断）；repo 字段缺失/空串拒绝。
 */
object KnowledgePack {

    const val CURRENT_SCHEMA_VERSION = 1

    /** 导入模式 */
    enum class ImportMode {
        /** 清空四表（全库替换——包是分享全量镜像） */
        REPLACE_ALL,

        /** 只替换包内出现的 repo（增量导入，不动其他仓库） */
        REPLACE_PER_REPO,
    }

    /** 导入结果（验收指标） */
    data class ImportResult(
        val mode: ImportMode,
        val repos: List<String>,
        val tags: Int,
        val decisionNodes: Int,
        val goldenSamples: Int,
        val docs: Int,
    )

    @Serializable
    data class PackDoc(
        val name: String,
        val title: String,
        val summary: String,
        val body: String,
        val updatedAt: Long,
    )

    @Serializable
    data class PackTag(
        val tag: String,
        val docName: String,
        val module: String? = null,
        val aliases: List<String> = emptyList(),
        val truncatedTag: String? = null,
        val note: String? = null,
    )

    @Serializable
    data class PackDecisionNode(
        val method: String,
        val nodeKey: String,
        val parentKey: String? = null,
        val condition: String,
        val action: String,
        val docName: String? = null,
    )

    @Serializable
    data class PackGoldenSample(
        val title: String,
        val symptom: String,
        val rootCause: String,
        val fixSummary: String,
        val evidenceLog: String? = null,
        val tags: List<String> = emptyList(),
        val createdAt: Long = 0L,
    )

    @Serializable
    data class PackRepo(
        val repo: String,
        val docs: List<PackDoc> = emptyList(),
        val tags: List<PackTag> = emptyList(),
        val decisionNodes: List<PackDecisionNode> = emptyList(),
        val goldenSamples: List<PackGoldenSample> = emptyList(),
    )

    @Serializable
    private data class PackEnvelope(
        val schemaVersion: Int,
        val exportedAt: Long,
        val repos: List<PackRepo>,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Pack 列表 → 完整包 JSON（CLI/导入器共用——pack 是 repos 段） */
    fun encodePackForCli(repos: List<PackRepo>): String {
        val envelope = PackEnvelope(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            repos = repos,
        )
        return json.encodeToString(envelope)
    }

    // ── 导出 ──

    /**
     * 全库导出为 KnowledgePack JSON 文档。
     *
     * @param db 进程唯一库（[Database.database]；测试可传 [Database.inMemory]）
     * @param target 目标文件（null = 默认落点 `~/.easy_dev_toolbox/knowledge/exports/
     *        knowledge-pack-<exportedAt>.json`——由调用方传时间戳命名）
     * @param exportedAt 导出时间戳（调用方传——本对象无状态不取钟）
     * @return 写出的文件
     */
    suspend fun exportPack(
        db: ToolbeltDb,
        target: File? = null,
        exportedAt: Long,
    ): File = withContext(Dispatchers.IO) {
        val repo = KnowledgeRepository(db)
        val repos = repo.repos().map { repoName ->
            PackRepo(
                repo = repoName,
                docs = repo.docsOf(repoName).map { d ->
                    PackDoc(name = d.name, title = d.title, summary = d.summary, body = d.body, updatedAt = d.updatedAt)
                },
                tags = db.knowledgeQueries.tagsByRepo(repoName).executeAsList().map { t ->
                    PackTag(
                        tag = t.tag,
                        docName = t.doc_name,
                        module = t.module,
                        aliases = repo.decodeList(t.aliases),
                        truncatedTag = t.truncated_tag,
                        note = t.note,
                    )
                },
                decisionNodes = db.knowledgeQueries.decisionNodesByRepo(repoName).executeAsList().map { n ->
                    PackDecisionNode(
                        method = n.method,
                        nodeKey = n.node_key,
                        parentKey = n.parent_key,
                        condition = n.condition,
                        action = n.action,
                        docName = n.doc_name,
                    )
                },
                goldenSamples = repo.goldenSamplesOf(repoName).map { s ->
                    PackGoldenSample(
                        title = s.title,
                        symptom = s.symptom,
                        rootCause = s.rootCause,
                        fixSummary = s.fixSummary,
                        evidenceLog = s.evidenceLog,
                        tags = s.tags,
                        createdAt = s.createdAt,
                    )
                },
            )
        }
        val pack = PackEnvelope(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            exportedAt = exportedAt,
            repos = repos,
        )
        val out = target ?: defaultExportFile(exportedAt)
        out.parentFile?.mkdirs()
        out.writeText(json.encodeToString(pack))
        out
    }

    // ── 导入 ──

    /**
     * 导入 KnowledgePack（单事务整包写入，schemaVersion 前向校验）。
     *
     * @param db 目标库
     * @param source 包文件
     * @param mode REPLACE_ALL（默认：全库替换）或 REPLACE_PER_REPO（只替换包内 repo）
     * @return 写入统计（验收面）
     * @throws IllegalArgumentException schemaVersion 不支持 / repo 字段缺失
     */
    suspend fun importPack(
        db: ToolbeltDb,
        source: File,
        mode: ImportMode = ImportMode.REPLACE_ALL,
    ): ImportResult = withContext(Dispatchers.IO) {
        val text = source.readText()
        val pack = json.decodeFromString(PackEnvelope.serializer(), text)
        if (pack.schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw IllegalArgumentException(
                "知识包 schemaVersion=${pack.schemaVersion} 超出当前支持（$CURRENT_SCHEMA_VERSION）——请升级应用后导入",
            )
        }
        pack.repos.forEach { r ->
            if (r.repo.isBlank()) throw IllegalArgumentException("知识包 repo 字段缺失/空串——格式损坏")
        }
        val repo = KnowledgeRepository(db)
        // transactionWithResult（非 transaction）：SQLDelight 2.x 的 transaction {}
        //    lambda 是 TransactionWithoutReturn 语义（返回值被吞成 Unit）；导入要拿写入
        //    统计必须走 transactionWithResult（带返回值的同款事务包裹）
        val result = db.transactionWithResult {
            if (mode == ImportMode.REPLACE_ALL) {
                db.knowledgeQueries.clearTags()
                db.knowledgeQueries.clearDecisionNodes()
                db.knowledgeQueries.clearGoldenSamples()
                db.knowledgeQueries.clearDocs()
            } else {
                pack.repos.forEach { r -> clearRepo(db, r.repo) }
            }
            var tags = 0
            var nodes = 0
            var samples = 0
            var docs = 0
            pack.repos.forEach { r ->
                r.tags.forEach { t ->
                    db.knowledgeQueries.insertTag(
                        tag = t.tag,
                        repo = r.repo,
                        doc_name = t.docName,
                        module = t.module,
                        aliases = repo.encodeList(t.aliases),
                        truncated_tag = t.truncatedTag,
                        note = t.note,
                    )
                    tags++
                }
                r.decisionNodes.forEach { n ->
                    db.knowledgeQueries.insertDecisionNode(
                        repo = r.repo,
                        method = n.method,
                        node_key = n.nodeKey,
                        parent_key = n.parentKey,
                        condition = n.condition,
                        action = n.action,
                        doc_name = n.docName,
                    )
                    nodes++
                }
                r.goldenSamples.forEach { s ->
                    db.knowledgeQueries.insertGoldenSample(
                        repo = r.repo,
                        title = s.title,
                        symptom = s.symptom,
                        root_cause = s.rootCause,
                        fix_summary = s.fixSummary,
                        evidence_log = s.evidenceLog,
                        tags = repo.encodeList(s.tags),
                        created_at = s.createdAt,
                    )
                    samples++
                }
                r.docs.forEach { d ->
                    db.knowledgeQueries.insertDoc(
                        repo = r.repo,
                        name = d.name,
                        title = d.title,
                        summary = d.summary,
                        body = d.body,
                        updated_at = d.updatedAt,
                    )
                    docs++
                }
            }
            ImportResult(
                mode = mode,
                repos = pack.repos.map { it.repo },
                tags = tags,
                decisionNodes = nodes,
                goldenSamples = samples,
                docs = docs,
            )
        }
        result
    }

    /** 按 repo 清空四表（REPLACE_PER_REPO 模式的清面） */
    private fun clearRepo(db: ToolbeltDb, repoName: String) {
        db.knowledgeQueries.deleteTagsByRepo(repoName)
        db.knowledgeQueries.deleteDecisionNodesByRepo(repoName)
        db.knowledgeQueries.deleteGoldenSamplesByRepo(repoName)
        db.knowledgeQueries.deleteDocsByRepo(repoName)
    }

    /** 默认导出落点：`~/Documents/Toolbelt/knowledge/`（交付物归可见产物树）。已导出旧件不迁移 */
    private fun defaultExportFile(exportedAt: Long): File =
        File(
            com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getKnowledgeExportDir(),
            "knowledge-pack-$exportedAt.json",
        )
}
