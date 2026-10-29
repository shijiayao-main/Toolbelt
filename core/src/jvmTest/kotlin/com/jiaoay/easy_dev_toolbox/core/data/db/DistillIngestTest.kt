package com.jiaoay.easy_dev_toolbox.core.data.db

import com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DistillIngest 入库链单测（P4-B 任务 4.5，零 LLM——纯 DB 写读回环）：
 * - 四 kind 各写入一张表并回读验证（TAG→tag_registry / CONSTRAINT/DECISION_NODE→
 *   decision_nodes / GOLDEN_SAMPLE→golden_samples）；
 * - unknown repo 拒绝写入（written=false，库内零脏数据）；
 * - 幻觉驳回场景：不入库即无脏数据（驳回是 VM 动作——本测钉「不入库 = 零行」这一不变量）；
 * - golden body 三段拆分（symptom/root_cause/fix 正则面）。
 */
class DistillIngestTest {

    private fun proposal(
        kind: DistillTools.DistillProposal.Kind,
        title: String,
        body: String,
        seq: Int = 1,
    ) = DistillTools.DistillProposal(
        kind = kind,
        title = title,
        body = body,
        sourceRefs = listOf("src/demo/Foo.kt:42"),
        seq = seq,
    )

    @Test
    fun `tag proposal writes tag registry and reads back`() = kotlinx.coroutines.test.runTest {
        val db = Database.inMemory()
        val result = DistillIngest.ingest(
            db = db,
            proposal = proposal(DistillTools.DistillProposal.Kind.TAG, "SettingsPresenter", "入口/状态/异常/结果四类日志模式"),
            repo = "settings",
        )

        assertTrue(result.written, "TAG 入库成功：${result.detail}")
        val repo = KnowledgeRepository(db)
        val hits = repo.lookupTag(tag = "SettingsPresenter", repo = "settings")
        assertTrue(hits.isNotEmpty(), "TAG 反查命中刚写入的候选")
        assertTrue(hits.first().note!!.contains("入口/状态"))
    }

    @Test
    fun `constraint proposal writes decision nodes as annotation`() = kotlinx.coroutines.test.runTest {
        val db = Database.inMemory()
        val result = DistillIngest.ingest(
            db = db,
            proposal = proposal(DistillTools.DistillProposal.Kind.CONSTRAINT, "设置中深色模式不可切换", "early return 守卫：mSettingsActive 时切换请求直接忽略"),
            repo = "settings",
        )

        assertTrue(result.written)
        val repo = KnowledgeRepository(db)
        val nodes = repo.decisionTree(repo = "settings", method = "设置中深色模式不可切换")
        assertEquals(1, nodes.size, "约束以附注形态落决策树表（method 命中）")
        assertTrue(nodes.first().condition.contains("early return"))
    }

    @Test
    fun `decision node proposal writes tree root`() = kotlinx.coroutines.test.runTest {
        val db = Database.inMemory()
        val result = DistillIngest.ingest(
            db = db,
            proposal = proposal(
                DistillTools.DistillProposal.Kind.DECISION_NODE,
                "startRenderIfNeeded",
                "doorState==OPEN → 延迟渲染；battery<20% → 拒绝",
                seq = 3,
            ),
            repo = "settings",
        )

        assertTrue(result.written)
        val repo = KnowledgeRepository(db)
        assertEquals(listOf("startRenderIfNeeded"), repo.methodsOf("settings"))
        val tree = repo.decisionTree(repo = "settings", method = "startRenderIfNeeded")
        assertTrue(tree.first().action.contains("doorState==OPEN"))
    }

    @Test
    fun `golden sample proposal writes and reads by symptom keyword`() = kotlinx.coroutines.test.runTest {
        val db = Database.inMemory()
        val result = DistillIngest.ingest(
            db = db,
            proposal = proposal(
                DistillTools.DistillProposal.Kind.GOLDEN_SAMPLE,
                "相机拉起失败",
                "symptom：黑屏无预览\nroot_cause：so 加载失败\nfix：检查 ABI 过滤",
            ),
            repo = "settings",
        )

        assertTrue(result.written)
        val repo = KnowledgeRepository(db)
        val hits = repo.searchGoldenSamples(repo = "settings", keyword = "黑屏")
        assertTrue(hits.isNotEmpty(), "症状关键词检索命中刚写入的黄金样本")
        assertEquals("so 加载失败", hits.first().rootCause)
        assertEquals("检查 ABI 过滤", hits.first().fixSummary)
    }

    @Test
    fun `unknown repo is rejected without writing`() = kotlinx.coroutines.test.runTest {
        val db = Database.inMemory()
        val result = DistillIngest.ingest(
            db = db,
            proposal = proposal(DistillTools.DistillProposal.Kind.TAG, "X", "b"),
            repo = "unknown",
        )

        assertFalse(result.written, "unknown repo 拒绝写入")
        val stats = KnowledgeRepository(db).stats()
        assertEquals(0L, stats.tags, "驳回/拒绝写入后库内零脏数据")
    }

    @Test
    fun `golden body split regex extracts three sections`() {
        val (symptom, rootCause, fix) = DistillIngest.splitGoldenBody(
            "symptom：相机黑屏\nroot_cause：native so 加载失败\nfix：核对 abiFilters",
        )
        assertEquals("相机黑屏", symptom)
        assertEquals("native so 加载失败", rootCause)
        assertEquals("核对 abiFilters", fix)

        // 拆不动的 body 整段落 symptom（root/fix 留空——检索面仍可用症状词命中）
        val (s2, rc2, f2) = DistillIngest.splitGoldenBody("一段无结构描述")
        assertEquals("一段无结构描述", s2)
        assertEquals("", rc2)
        assertEquals("", f2)
    }
}
