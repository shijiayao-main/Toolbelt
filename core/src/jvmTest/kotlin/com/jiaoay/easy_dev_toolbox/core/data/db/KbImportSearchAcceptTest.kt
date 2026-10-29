package com.jiaoay.easy_dev_toolbox.core.data.db

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 任务 2.5 真源导入验收（只在本机有真源库时跑——存在性门控非 CI 契约）：
 * kb-import 全量导入后 knowledge.search 语义检索命中既有症状词。
 */
class KbImportSearchAcceptTest {
    @Test
    fun `imported truth source is searchable by known symptom`() {
        // 直接查用户库（kb-import 已导入；未导入环境跳过）
        val db = try {
            Database.database()
        } catch (e: Exception) {
            println("跳过：DB 不可用（${e.message}）")
            return
        }
        val repo = KnowledgeRepository(db)
        val stats = repo.stats()
        if (stats.docs == 0L) {
            println("跳过：知识库为空（未运行 kb-import）")
            return
        }
        println("库内统计：$stats")
        assertTrue(stats.docs >= 65L, "真源导入应含 65 篇文档：实际 ${stats.docs}")

        // 症状词检索（「相机拉起失败」近似词——LOG_ANALYSE_INDEX 实证触发词）
        val hits = repo.searchDocs("repo-alpha", "相机")
        assertTrue(hits.isNotEmpty(), "症状词「相机」必须命中导入文档")
        hits.take(3).forEach { println("命中: ${it.name} — ${it.title}") }

        // TAG 反查（lookup_tag 前缀变体展开同路径）
        val tagHit = repo.lookupTag("SettingsPresenter", repo = "repo-alpha")
        assertTrue(tagHit.isNotEmpty(), "TAG 反查必须命中")
        tagHit.take(2).forEach { println("TAG 反查: ${it.tag} → ${it.docName}") }
    }
}
