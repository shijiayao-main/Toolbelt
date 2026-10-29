package com.jiaoay.easy_dev_toolbox.core.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KnowledgeMdImporter 契约测试（P4-B 任务 2.2/2.3——零 LLM/零网络，纯文件与 DB）：
 * - 合法小知识包全量导入幂等回环（md → Pack → importPack → 四表检索命中）；
 * - 6 项 CHECK 每项一个违例样本（红向：拒绝产出 Pack）；
 * - 骨架外内容产 warning 不 ERROR；
 * - 与 kb_scan 语义对齐面：占位符 tag（{hashCode}/@<hashCode>）与方法级 tag（xxx()）
 *   不参与 CHECK 1 判定；NAV_ 前缀双向归一。
 */
class KnowledgeMdImporterTest {

    private fun tempKb(): File = java.nio.file.Files.createTempDirectory("kb-import").toFile()

    /** 合成最小合法知识包：1 repo / 1 文档 / 2 TAG / 1 决策树 */
    private fun writeValidKb(root: File) {
        File(root, "repoA").resolve("system").apply { mkdirs() }.resolve("alpha_issue.md").writeText(
            """
            # Alpha 问题分析 [com.example.alpha]

            > **症状触发词**：alpha 慢、alpha 不显示
            > **关联文档**：`repoA/system/alpha_issue.md`
            > **蒸馏元数据**：2026-09-19 | main@abc123 | 入口：AlphaService

            #### Alpha 分组（AlphaService）

            - tag: `AlphaService`

            > **日志模式**：
            > - `AlphaService: onStart`（启动）

            ### startAlpha 决策树

            - 检查 surface 是否有效
            - 检查 enterMode 门控
            """.trimIndent(),
        )
        File(root, "TAG_REGISTRY.md").writeText(
            """
            # TAG_REGISTRY

            ## TAG → 文档 映射

            ## repoA

            ### system/

            | 文档 | TAG |
            |------|-----|
            | `repoA/system/alpha_issue.md` | AlphaService, AlphaHelper |
            """.trimIndent(),
        )
        File(root, "LOG_ANALYSE_INDEX.md").writeText(
            """
            # LOG_ANALYSE_INDEX

            ## 症状 → 知识库 快查表（按仓库分节，节内按功能域分组，匹配时必须扫描全部分组）

            ## repoA

            | 症状 / 触发关键词 | 主文档 | 常见同时需要 |
            |---|---|---|
            | alpha 慢 / alpha 不显示 | `repoA/system/alpha_issue.md` | — |
            """.trimIndent(),
        )
    }

    @Test
    fun `valid kb parses to pack with expected counts`() {
        val root = tempKb().also { writeValidKb(it) }
        val result = KnowledgeMdImporter.parse(root)
        assertTrue(result.isOk, "合法包解析必须成功：${result.errors}")

        val pack = result.pack!!
        assertEquals(1, pack.size)
        val repoA = pack.first()
        assertEquals("repoA", repoA.repo)
        assertEquals(1, repoA.docs.size)
        assertEquals("repoA/system/alpha_issue.md", repoA.docs.first().name)
        assertEquals("Alpha", repoA.docs.first().title, "title = 标题骨架的 {feature} 段")
        assertTrue("alpha 慢" in repoA.docs.first().summary, "summary 源自症状快查表")
        assertEquals(setOf("AlphaService", "AlphaHelper"), repoA.tags.map { it.tag }.toSet())
        assertEquals("system", repoA.tags.first().module, "module 从路径第二段派生")
        assertTrue(repoA.decisionNodes.isNotEmpty(), "决策树节点被收集")
        assertTrue(
            result.warnings.any { it.contains("UPGRADE_LOG") || it.contains("LOG_DIR_INFO") },
            "缺四元数据成员产提示不阻断：${result.warnings}",
        )
    }

    @Test
    fun `full chain import is idempotent and searchable`() = kotlinx.coroutines.runBlocking {
        val root = tempKb().also { writeValidKb(it) }
        val db = Database.inMemory()
        val result = KnowledgeMdImporter.parse(root)
        assertTrue(result.isOk, result.errors.joinToString())

        // Pack → JSON → importPack（模拟 CLI/交换全链——导入器产物即包格式）
        val packFile = File(root, "pack.json")
        packFile.writeText(KnowledgeMdImporterTestJson.encode(result.pack!!))
        val r1 = KnowledgePack.importPack(db, packFile, KnowledgePack.ImportMode.REPLACE_PER_REPO)
        assertEquals(1, r1.docs)
        assertEquals(2, r1.tags)

        // 幂等：重复导入统计一致
        val r2 = KnowledgePack.importPack(db, packFile, KnowledgePack.ImportMode.REPLACE_PER_REPO)
        assertEquals(r1.docs, r2.docs)
        assertEquals(r1.tags, r2.tags)

        // 检索命中（knowledge.search 语义：title/summary LIKE）
        val repo = KnowledgeRepository(db)
        val hits = repo.searchDocs("repoA", "alpha 慢")
        assertEquals(1, hits.size, "症状词命中导入文档")
        val tagHits = repo.lookupTag("AlphaService", repo = "repoA")
        assertTrue(tagHits.isNotEmpty(), "TAG 反查命中")
    }

    // ── 红向：6 项 CHECK 违例样本 ──

    @Test
    fun `check 1 unregistered doc tag is rejected`() {
        val root = tempKb().also { writeValidKb(it) }
        val doc = File(root, "repoA/system/alpha_issue.md")
        doc.writeText(doc.readText().replace("- tag: `AlphaService`", "- tag: `UnregisteredTag`"))
        val result = KnowledgeMdImporter.parse(root)
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("[CHECK 1]") && it.contains("UnregisteredTag") })
        assertEquals(null, result.pack, "任一 ERROR 拒绝产出 Pack（整包事务）")
    }

    @Test
    fun `check 1 placeholder and method tags are ignorable`() {
        val root = tempKb().also { writeValidKb(it) }
        val doc = File(root, "repoA/system/alpha_issue.md")
        doc.writeText(
            doc.readText() +
                "\n- tag: `CapsuleViewManager@<hashCode>`\n- tag: `isPGearScene()`\n- tag: `Thing@{suffix}`\n",
        )
        val result = KnowledgeMdImporter.parse(root)
        assertTrue(result.isOk, "占位符/方法级 tag 按 kb_scan is_ignorable 语义豁免：${result.errors}")
    }

    @Test
    fun `check 3 bad title format is rejected`() {
        val root = tempKb().also { writeValidKb(it) }
        val doc = File(root, "repoA/system/alpha_issue.md")
        doc.writeText(doc.readText().replace("# Alpha 问题分析 [com.example.alpha]", "# Alpha 分析"))
        val result = KnowledgeMdImporter.parse(root)
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("[CHECK 3]") })
    }

    @Test
    fun `check 4 index dangling entry is rejected`() {
        val root = tempKb().also { writeValidKb(it) }
        val idx = File(root, "LOG_ANALYSE_INDEX.md")
        idx.writeText(idx.readText().replace("`repoA/system/alpha_issue.md`", "`repoA/system/ghost.md`"))
        val result = KnowledgeMdImporter.parse(root)
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("[CHECK 4]") && it.contains("ghost") })
    }

    @Test
    fun `check 5 registry documents both ways are validated`() {
        // 正向：登记了磁盘不存在的文档
        val root = tempKb().also { writeValidKb(it) }
        val reg = File(root, "TAG_REGISTRY.md")
        reg.writeText(
            reg.readText() +
                "\n| `repoA/system/ghost_doc.md` | GhostTag |\n",
        )
        val result = KnowledgeMdImporter.parse(root)
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("[CHECK 5]") && it.contains("ghost_doc") })

        // 反向：磁盘文档未登记
        val root2 = tempKb().also { writeValidKb(it) }
        File(root2, "repoA/system/omega_issue.md").writeText("# Omega 问题分析 [p.omega]\n")
        val reg2 = File(root2, "TAG_REGISTRY.md")
        reg2.writeText(reg2.readText().replace("| `repoA/system/alpha_issue.md` | AlphaService, AlphaHelper |", ""))
        val result2 = KnowledgeMdImporter.parse(root2)
        assertFalse(result2.isOk)
        assertTrue(result2.errors.any { it.contains("[CHECK 5]") && it.contains("未在 TAG_REGISTRY") }, result2.errors.joinToString())
    }

    @Test
    fun `check 14 doc outside repo section is rejected`() {
        // CHECK 14 的主判面：根下未知目录的文档（非 <repo>/ 分节）——本判例经 TAG_REGISTRY 行触发
        val root = tempKb().also { writeValidKb(it) }
        val reg = File(root, "TAG_REGISTRY.md")
        reg.writeText(reg.readText().replace("`repoA/system/alpha_issue.md`", "`repoZ/system/alpha_issue.md`"))
        val result = KnowledgeMdImporter.parse(root)
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.contains("[CHECK 14]") || it.contains("[CHECK 15]") }, result.errors.joinToString())
    }

    @Test
    fun `missing registry file aborts with explicit error`() {
        val root = tempKb()
        root.mkdirs()
        val result = KnowledgeMdImporter.parse(root)
        assertFalse(result.isOk)
        assertTrue(result.errors.first().contains("TAG_REGISTRY.md"))
    }
}

/** 测试件：Pack → JSON（生产链 KnowledgePack.importPack 读同格式） */
private object KnowledgeMdImporterTestJson {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun encode(repos: List<KnowledgePack.PackRepo>): String {
        val envelope = PackEnvelopeForTest(schemaVersion = 1, exportedAt = 0L, repos = repos)
        return json.encodeToString(PackEnvelopeForTest.serializer(), envelope)
    }

    @kotlinx.serialization.Serializable
    private data class PackEnvelopeForTest(
        val schemaVersion: Int,
        val exportedAt: Long,
        val repos: List<KnowledgePack.PackRepo>,
    )
}
