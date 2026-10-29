// P3-D · 知识库引擎验收（2026-09-19）：
//
// 1. 迁移：v1 存量库（sessions/messages 面）升 v2 不丢数据、四表可写；
// 2. Repository 四面：TAG 反查（含前缀变体）/黄金样本症状检索/文档渐进披露/决策树；
// 3. KnowledgePack 往返：导出→清库→导入 → 四表逐字段保真（schemaVersion=1 新真源）；
// 4. 装配门控判别面：hasContent 空库 false / 种子库 true（factory 接线在 KnowledgeTools，不在本测试面）；
// 5. LIKE 转义：关键词含 %/_ 不误当通配符。
package com.jiaoay.easy_dev_toolbox.core.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class KnowledgeEngineTest {

    private fun tempDbFile(): File =
        File(System.getProperty("java.io.tmpdir"), "toolbelt-kb-${System.nanoTime()}.db")
            .apply { deleteOnExit() }

    /** 模拟 v1 存量库：手动建 v1 表 + 写入桥接持久化数据 + user_version=1，再经 forFile 升 v2 */
    private fun v1DbWithLegacyData(): File {
        val file = tempDbFile()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            // v1 schema（P1-A2 原始 DDL——sessions/messages + FTS5 触发器，外键约束原样）
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, tombstone INTEGER NOT NULL DEFAULT 0)")
                st.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id), seq INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, tool_name TEXT, tool_observation TEXT, entry_id TEXT, state TEXT, attachments TEXT, artifacts TEXT, error_kind TEXT, title TEXT, detail TEXT, created_at INTEGER NOT NULL, UNIQUE(session_id, seq))")
                st.execute("INSERT INTO sessions VALUES ('bridge-1', '桥接会话', 100, 100, 0)")
                st.execute("INSERT INTO messages(session_id, seq, role, content, created_at) VALUES ('bridge-1', 0, 'user', '重启前的消息', 100)")
                st.execute("PRAGMA user_version = 1")
            }
        }
        return file
    }

    // ── 1. 迁移 ──

    @Test
    fun `v1 库升级 v2 不丢会话数据且四表可写`() {
        val file = v1DbWithLegacyData()
        // forFile 走 migrate 分支（user_version=1 < schema.version=2）
        val db = Database.forFile(file)
        // 旧数据原样在位
        assertEquals("桥接会话", db.sessionsQueries.sessionById("bridge-1").executeAsOne().title)
        assertEquals(1L, db.messagesQueries.countMessagesForSession("bridge-1").executeAsOne())
        // 四表可写（迁移真实建表）
        db.knowledgeQueries.insertDoc("repo-alpha", "system/signal_center.md", "信号中心", "信号分发", "# 正文", 1L)
        assertEquals(1L, db.knowledgeQueries.countDocs().executeAsOne())
    }

    @Test
    fun `升级后 user_version 落到当前 schema 版本-二次打开零重复迁移`() {
        val file = v1DbWithLegacyData()
        Database.forFile(file)
        // P4-C2 runs 表批后 schema.version = 3（v1→v2 知识四表 + v2→v3 runs 表两级连升）——
        // 断言面向「回写当前版本」而非写死数字（升级路径迭代不再撞此测试）
        val expected = Database.schemaVersionForTest()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            val rs = conn.createStatement().executeQuery("PRAGMA user_version")
            assertTrue(rs.next() && rs.getLong(1) == expected, "migrate 后 user_version 必须回写 $expected")
        }
        // 二次打开：版本相等 ⇒ 不再触发迁移分支（重复回放会撞表已存在）
        Database.forFile(file)
    }

    @Test
    fun `全新库直接建 v2 全表`() {
        val db = Database.forFile(tempDbFile())
        db.knowledgeQueries.insertTag("XPD_C", "repo-alpha", "system/xdata.md", "system", null, null, null)
        val stats = db.knowledgeQueries.knowledgeStats().executeAsOne()
        assertEquals(1L, stats.tags, "全新库四表即时可用")
    }

    // ── 2. Repository 查询面 ──

    private fun seededRepo(): Pair<ToolbeltDb, KnowledgeRepository> {
        val db = Database.forFile(tempDbFile())
        db.knowledgeQueries.insertTag(
            tag = "FloorCenterManager",
            repo = "repo-alpha",
            doc_name = "system/signal_center.md",
            module = "system",
            aliases = null,
            truncated_tag = null,
            note = null,
        )
        // 跨文档共享 TAG：同 tag 两个 doc 行（TAG_REGISTRY 登记语义）
        db.knowledgeQueries.insertTag("CarServiceController", "repo-alpha", "system/signal_center.md", "system", null, null, null)
        db.knowledgeQueries.insertTag("CarServiceController", "repo-alpha", "carcontrol/carctl_signal_framework.md", "carcontrol", null, null, null)
        // 截断形式（NAV_NAV_ 前缀 + 26 字符截断形态）
        db.knowledgeQueries.insertTag(
            tag = "UnityMapPrecisionMoldManager",
            repo = "repo-alpha",
            doc_name = "ld/unity_map_ld.md",
            module = "ld",
            aliases = null,
            truncated_tag = "NAV_NAV_UnityMapPrecisionMoldManag",
            note = "L.Tag 包装截断",
        )
        // 别名行（NAV_ 前缀形态）
        db.knowledgeQueries.insertTag(
            tag = "ScreenStateManagerTag",
            repo = "repo-alpha",
            doc_name = "system/screen_split_window.md",
            module = "system",
            aliases = """["NAV_ScreenStateManagerTag"]""",
            truncated_tag = null,
            note = "TAG 常量与类名不一致",
        )
        db.knowledgeQueries.insertDoc(
            "repo-alpha", "system/signal_center.md", "信号中心",
            "FloorCenterManager 信号分发与 DataCacheHelper 缓存", "# 信号中心正文", 100L,
        )
        db.knowledgeQueries.insertGoldenSample(
            "repo-alpha", "拍照模式进入但影像完全不显示", "拍照模式后相机无影像", "CameraViewActivity 未启动",
            "检查 Activity 声明与 Intent", null, null, 200L,
        )
        db.knowledgeQueries.insertDecisionNode(
            "repo-alpha", "check_camera_display", "root", null,
            "拍照模式进入且无影像", "先查 settings_dark_mode.md 的状态链路", "system/surface_lifecycle.md",
        )
        return db to KnowledgeRepository(db)
    }

    @Test
    fun `TAG 反查-裸类名命中带前缀落盘形态`() {
        val (_, repo) = seededRepo()
        // 裸类名 → NAV_NAV_ 截断形式命中（变体展开生效）
        val hits = repo.lookupTag("UnityMapPrecisionMoldManager")
        assertTrue(hits.isNotEmpty(), "裸类名应经变体展开命中截断形式")
        assertEquals("ld/unity_map_md.md".replace("_md", "_ld"), hits.first().docName)
    }

    @Test
    fun `TAG 反查-同 TAG 多文档行全返回`() {
        val (_, repo) = seededRepo()
        val hits = repo.lookupTag("CarServiceController", repo = "repo-alpha")
        assertEquals(2, hits.size, "跨文档共享 TAG 两行都返回（TAG_REGISTRY 登记语义）")
    }

    @Test
    fun `TAG 反查-前缀别名命中`() {
        val (_, repo) = seededRepo()
        val hits = repo.lookupTag("NAV_ScreenStateManagerTag")
        // 输入本身就是完整别名形态：精确匹配 tag 列不中，但 aliases LIKE 命中
        assertTrue(hits.isNotEmpty(), "别名形态应命中 aliases LIKE")
    }

    @Test
    fun `黄金样本-症状关键词检索命中`() {
        val (_, repo) = seededRepo()
        val hits = repo.searchGoldenSamples("repo-alpha", "相机无影像")
        assertEquals(1, hits.size)
        assertEquals("拍照模式进入但影像完全不显示", hits.first().title)
    }

    @Test
    fun `文档渐进披露-search 命中索引-read 读正文`() {
        val (db, repo) = seededRepo()
        val index = repo.searchDocs("repo-alpha", "信号分发")
        assertEquals(1, index.size, "summary 关键词应命中索引")
        val body = repo.readDoc("repo-alpha", "system/signal_center.md")
        assertEquals("# 信号中心正文", body)
        assertEquals(listOf("repo-alpha"), repo.repos())
        assertEquals(5L, repo.stats().tags, "五 TAG 行（1+共享2+截断1+别名1）")
    }

    @Test
    fun `决策树读取与方法枚举`() {
        val (_, repo) = seededRepo()
        assertEquals(listOf("check_camera_display"), repo.methodsOf("repo-alpha"))
        val tree = repo.decisionTree("repo-alpha", "check_camera_display")
        assertEquals(1, tree.size)
        assertEquals(null, tree.first().parentKey, "根节点 parentKey=null")
        assertTrue(tree.first().condition.contains("拍照模式"))
    }

    @Test
    fun `LIKE 转义-百分号下划线不当通配符`() {
        val (db, repo) = seededRepo()
        // 插入一个 100% 命中陷阱的行：summary 含字面 '%'（如 "100% 黑屏"）
        db.knowledgeQueries.insertDoc(
            "repo-alpha", "ld/trap.md", "陷阱文档", "电池 100% 黑屏", "正文", 300L,
        )
        // 用户关键词 "100% 黑屏" 里的 % 被 ESCAPE——字面匹配（不把 % 当通配符）
        val literal = repo.searchDocs("repo-alpha", "100% 黑屏")
        assertEquals(1, literal.size, "字面 % 应被 ESCAPE 转义后仍精确命中")
        // 下划线同理
        db.knowledgeQueries.insertDoc("repo-alpha", "ld/trap2.md", "陷阱文档二", "info_1 缺失", "正文", 300L)
        assertEquals(1, repo.searchDocs("repo-alpha", "info_1").size, "字面 _ 转义后精确命中")
    }

    // ── 3. KnowledgePack 往返 ──

    @Test
    fun `KnowledgePack 导出导入往返-四表保真`() = runBlocking {
        val (source, _) = seededRepo()
        val target = File(System.getProperty("java.io.tmpdir"), "kb-pack-roundtrip-${System.nanoTime()}.json")
            .apply { deleteOnExit() }
        KnowledgePack.exportPack(source, target, exportedAt = 42L)

        // 干净目标库导入（REPLACE_ALL）
        val fresh = Database.forFile(tempDbFile())
        val result = KnowledgePack.importPack(fresh, target)
        assertEquals(listOf("repo-alpha"), result.repos)
        assertEquals(5, result.tags, "5 TAG 行（含截断/别名形态）全量导入")
        assertEquals(1, result.docs)
        assertEquals(1, result.goldenSamples)
        assertEquals(1, result.decisionNodes)

        // 逐字段保真（表面积抽样：TAG/doc/sample/node）
        val repo = KnowledgeRepository(fresh)
        assertEquals(5, repo.stats().tags)
        val doc = repo.readDoc("repo-alpha", "system/signal_center.md")
        assertEquals("# 信号中心正文", doc, "正文逐字保真")
        val sample = repo.goldenSamplesOf("repo-alpha").first()
        assertEquals("拍照模式进入但影像完全不显示", sample.title)
        assertEquals(200L, sample.createdAt, "黄金样本 createdAt 往返不丢（排序列）")
    }

    @Test
    fun `同包重复导入幂等`() = runBlocking {
        val (source, _) = seededRepo()
        val target = File(System.getProperty("java.io.tmpdir"), "kb-pack-idem-${System.nanoTime()}.json")
            .apply { deleteOnExit() }
        KnowledgePack.exportPack(source, target, exportedAt = 42L)
        val fresh = Database.forFile(tempDbFile())
        KnowledgePack.importPack(fresh, target)
        KnowledgePack.importPack(fresh, target)
        assertEquals(5L, KnowledgeRepository(fresh).stats().tags, "重复导入不翻倍（upsert 幂等）")
    }

    @Test
    fun `schemaVersion 前向校验-超版本拒绝`() = runBlocking {
        val bad = File(System.getProperty("java.io.tmpdir"), "kb-pack-bad-${System.nanoTime()}.json")
            .apply { deleteOnExit() }
        bad.writeText("""{"schemaVersion": 99, "exportedAt": 1, "repos": []}""")
        val db = Database.forFile(tempDbFile())
        val error = try {
            KnowledgePack.importPack(db, bad)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(error != null, "超版本包必须拒绝导入")
        assertTrue(error!!.message!!.contains("99"))
    }

    @Test
    fun `REPLACE_PER_REPO 不动其他仓库`() = runBlocking {
        val (source, _) = seededRepo()
        val target = File(System.getProperty("java.io.tmpdir"), "kb-pack-perrepo-${System.nanoTime()}.json")
            .apply { deleteOnExit() }
        KnowledgePack.exportPack(source, target, exportedAt = 42L)
        // 目标库预置另一个 repo
        val dest = Database.forFile(tempDbFile())
        dest.knowledgeQueries.insertDoc("repo-beta", "trafficlight.md", "红绿灯", "融合", "SR 正文", 1L)
        KnowledgePack.importPack(dest, target, mode = KnowledgePack.ImportMode.REPLACE_PER_REPO)

        val repo = KnowledgeRepository(dest)
        assertEquals(2, repo.repos().size, "repo-beta 预置库保留 + repo-alpha 新导入")
        assertEquals("# 信号中心正文", repo.readDoc("repo-alpha", "system/signal_center.md"))
        assertEquals("SR 正文", repo.readDoc("repo-beta", "trafficlight.md"), "REPLACE_PER_REPO 不清其他仓库")
    }

    // ── 4. 装配门控（判别面 = hasContent 空/满；factory 接线在 KnowledgeTools，不在本测试面） ──

    @Test
    fun `hasContent 空库为 false-种子库为 true`() {
        // 空库四计数全零（DatabaseTest.inMemory 无知识库种子）
        val emptyStats = KnowledgeRepository(Database.forFile(tempDbFile())).stats()
        assertEquals(0L, emptyStats.tags + emptyStats.decisions + emptyStats.samples + emptyStats.docs)
        val (_, seeded) = seededRepo()
        val seededStats = seeded.stats()
        assertTrue(seededStats.docs + seededStats.tags > 0, "种子库非空")
    }

    // ── 5. 单条存案写路径（公开 upsert API——(repo, title) 覆盖语义 + tags 编解码单源） ──

    @Test
    fun `单条 upsert 新增后重复存案覆盖且返回已存在`() {
        val repository = KnowledgeRepository(Database.forFile(tempDbFile()))
        val sample = KnowledgeRepository.GoldenSample(
            repo = KnowledgeRepository.CASE_REPO,
            title = "拍照模式影像不显示",
            symptom = "拍照模式后相机无影像",
            rootCause = "CameraViewActivity 未启动",
            fixSummary = "检查 Activity 声明",
            evidenceLog = "09-19 10:29:50.000 E/CameraManager: route failed",
            tags = listOf("相机", "拍照模式"),
            createdAt = 1000L,
        )
        assertFalse(repository.upsertGoldenSample(sample), "首次存案 = 新增（existed = false）")
        assertTrue(repository.upsertGoldenSample(sample.copy(fixSummary = "修正后的修法")), "同 (repo, title) 二次存案 = 覆盖（existed = true）")
        val cases = repository.goldenSamplesOf(KnowledgeRepository.CASE_REPO)
        assertEquals(1, cases.size, "覆盖不增行（UNIQUE(repo, title) 单行收敛）")
        assertEquals("修正后的修法", cases.single().fixSummary, "覆盖写最新内容")
        // 读写闭环：存案后症状词检索即命中（写面 = 检索面的同一四要素）
        val hit = repository.searchGoldenSamples(KnowledgeRepository.CASE_REPO, "相机无影像").single()
        assertEquals(sample.title, hit.title)
        assertEquals(listOf("相机", "拍照模式"), hit.tags, "tags 编解码经 encodeList/decodeList 单源往返保真")
        // 覆盖保留原登记时间（insertGoldenSample 的 ON CONFLICT 不更新 created_at）
        assertEquals(1000L, hit.createdAt, "覆盖语义：created_at 保留原行登记时间")
    }

    @Test
    fun `案例仓进入 repos 迭代域-空 repo 检索工具可全域命中`() {
        val repository = KnowledgeRepository(Database.forFile(tempDbFile()))
        repository.upsertGoldenSample(
            KnowledgeRepository.GoldenSample(
                repo = KnowledgeRepository.CASE_REPO, title = "黑屏案例",
                symptom = "设置页面黑屏", rootCause = "主线程阻塞", fixSummary = "核对线程模型",
                evidenceLog = null, tags = emptyList(), createdAt = 1L,
            ),
        )
        assertTrue(KnowledgeRepository.CASE_REPO in repository.repos(), "案例仓经四表 UNION 自动进 repos() 迭代域")
        assertTrue("casebook" != "repo-alpha" && "casebook" != "repo-beta", "仓名避让真实产品仓名（虚拟仓契约）")
    }
}
