package com.jiaoay.easy_dev_toolbox.core.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 知识库管理面单测（P4-B 任务 5.1——「管理面写入与 agent 检索同源断言」）：
 * - deleteRepo 四表整组回退（删除后检索零命中——管理面与 agent 检索同库同源）；
 * - deleteTag/deleteGoldenSample 单条删除（复合主键面）；
 * - 蒸馏入库后管理面可见可删（DistillIngest 写入 → 管理面 repos/stats 可见 → deleteRepo 归零）。
 */
class KnowledgeManageTest {

    private fun tempDbFile(): File =
        File(System.getProperty("java.io.tmpdir"), "toolbelt-kb-mgmt-${System.nanoTime()}.db")
            .apply { deleteOnExit() }

    private fun newRepo() = KnowledgeRepository(Database.forFile(tempDbFile()))

    @Test
    fun `deleteRepo removes all four tables rows for that repo`() = runTest {
        val db = Database.forFile(tempDbFile())
        val repo = KnowledgeRepository(db)
        // 两 repo 各写入一点数据（经入库链——管理面与 agent 检索同库同源）
        DistillIngest.ingest(
            db = db,
            proposal = com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools.DistillProposal(
                kind = com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools.DistillProposal.Kind.TAG,
                title = "SettingsTag", body = "b", sourceRefs = listOf("a.kt:1"), seq = 1,
            ),
            repo = "settings",
        )
        DistillIngest.ingest(
            db = db,
            proposal = com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools.DistillProposal(
                kind = com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools.DistillProposal.Kind.TAG,
                title = "OtherTag", body = "b", sourceRefs = listOf("b.kt:1"), seq = 2,
            ),
            repo = "other",
        )
        assertEquals(setOf("settings", "other"), repo.repos().toSet(), "两仓库节均可见")

        // 删除 settings：settings 归零、other 完好
        repo.deleteRepo("settings")
        assertEquals(listOf("other"), repo.repos())
        assertTrue(repo.lookupTag(tag = "SettingsTag", repo = "settings").isEmpty(), "删除后检索零命中（同源断言）")
        assertTrue(repo.lookupTag(tag = "OtherTag", repo = "other").isNotEmpty(), "other 仓库不受影响")
        assertEquals(1L, repo.stats().tags, "stats 与删除同源（仅剩 other 一行）")
    }

    @Test
    fun `deleteTag removes single row by composite key`() = runTest {
        val db = Database.forFile(tempDbFile())
        val repo = KnowledgeRepository(db)
        DistillIngest.ingest(
            db = db,
            proposal = com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools.DistillProposal(
                kind = com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools.DistillProposal.Kind.TAG,
                title = "DupTag", body = "b", sourceRefs = listOf("a.kt:1"), seq = 1,
            ),
            repo = "settings",
        )
        DistillIngest.ingest(
            db = db,
            proposal = com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools.DistillProposal(
                kind = com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools.DistillProposal.Kind.TAG,
                title = "DupTag", body = "b2", sourceRefs = listOf("b.kt:1"), seq = 2,
            ),
            repo = "settings2",
        )

        // 只删 settings 的行（doc_name 由 title 派生——两行 doc_name 相同但 repo 不同）
        repo.deleteTag(tag = "DupTag", repo = "settings", docName = "DupTag 知识点")
        assertTrue(repo.lookupTag(tag = "DupTag", repo = "settings").isEmpty(), "settings 行已删")
        assertTrue(repo.lookupTag(tag = "DupTag", repo = "settings2").isNotEmpty(), "settings2 行完好（复合键精确删除）")
    }

    @Test
    fun `deleteGoldenSample removes single case`() = runTest {
        val db = Database.forFile(tempDbFile())
        val repo = KnowledgeRepository(db)
        DistillIngest.ingest(
            db = db,
            proposal = com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools.DistillProposal(
                kind = com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools.DistillProposal.Kind.GOLDEN_SAMPLE,
                title = "黑屏案例", body = "symptom：黑屏\nroot_cause：so\nfix：ABI", sourceRefs = listOf("a.kt:1"), seq = 1,
            ),
            repo = "settings",
        )
        assertTrue(repo.searchGoldenSamples(repo = "settings", keyword = "黑屏").isNotEmpty())

        repo.deleteGoldenSample(repo = "settings", title = "黑屏案例")
        assertTrue(repo.searchGoldenSamples(repo = "settings", keyword = "黑屏").isEmpty(), "单条删除后症状检索零命中")
        assertEquals(0L, repo.stats().samples)
    }
}
