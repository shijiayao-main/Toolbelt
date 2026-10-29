// 引用流重设计（2026-09-11）机制 B 单测：DownloadStore（持久下载区 + 配额 FIFO）。
// 覆盖：目录布局 / root 空 null / per-user 与全局逐出 / rescan 重建 / legacy tmp-* 清扫 / 幂等记账。
package com.jiaoay.lark.bridge.files

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.FileMessageConfig
import com.jiaoay.lark.bridge.config.WorkspaceConfig
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadStoreTest {

    private fun workspace(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "download-store-$name-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }

    private fun config(
        root: String,
        perUserQuotaBytes: Long = FileMessageConfig().perUserQuotaBytes,
        totalQuotaBytes: Long = FileMessageConfig().totalQuotaBytes,
    ): BridgeConfig = BridgeConfig(
        workspace = WorkspaceConfig(root = root),
        fileMessage = FileMessageConfig(
            perUserQuotaBytes = perUserQuotaBytes,
            totalQuotaBytes = totalQuotaBytes,
        ),
    )

    /** 往账本里记账一个 per-message 目录（落 1 个真实文件）。 */
    private fun storeDir(store: DownloadStore, ws: File, sender: String, msgId: String, name: String, bytes: Int): File {
        val dir = store.dirFor(sender, msgId, name)!!.apply { mkdirs() }
        File(dir, DownloadPolicy.sanitizeFileName(name)).writeBytes(ByteArray(bytes))
        store.recordStored(dir, sender)
        return dir
    }

    // ── 目录布局 ──

    @Test
    fun `目录布局-按发送者与日期分桶且 msgId 截 12 位`() {
        val ws = workspace("layout")
        val store = DownloadStore({ config(ws.path) }, BridgeLog.Silent)
        val dir = store.dirFor("ou_alice", "om_abcdefghijklmnop", "crash.zip")
        assertTrue(dir != null)
        val rel = dir!!.path.removePrefix(ws.path)
        assertTrue(rel.startsWith("/downloads/ou_alice/"), "第二层必须是发送者目录：$rel")
        assertTrue(Regex("""/\d{4}-\d{2}-\d{2}/$""").containsMatchIn(File(dir.parent).name.let { "/$it/" }),
            "第三层必须是 yyyy-MM-dd：${dir.parent}")
        assertEquals("om_abcdefghi_crash.zip", dir.name, "目录名 = msgId 前 12 字符 + 下划线 + 清洗名")
        ws.deleteRecursively()
    }

    /**
     * 🔴 2026-09-17 审计修复（#4，block 级）：senderId / messageId 此前裸拼进路径——
     * CLI `handle-event` 的自定义 JSON 可传 `../../../tmp/x` 穿出下载根（take(12) 对
     * `../../../tmp/x` 得 `../../../tmp`）。修复 = 段级清洗（sanitizeDirSegment）。
     *
     * 🔴 2026-09-17 复核修复（二次）：用例改用**带有效内容的**穿越串（原先的
     * `"../../../../etc/passwd"` 截 12 位后是纯 `"../"` 序列，清洗后无有效内容 ⇒ 按新语义
     * 应判 null，那是「拒绝」而非「清洗」——两类意图分开断言，见下一个用例）。
     */
    @Test
    fun `dirFor 清洗发送者与消息 ID 的穿越段`() {
        val ws = workspace("slip")
        val store = DownloadStore({ config(ws.path) }, BridgeLog.Silent)
        val dir = store.dirFor("../../../tmp/ou_evil", "../../om_evil12", "a.log")
        assertTrue(dir != null)
        // 目录必须仍在 workspace 内（canonical 判定，符号链接/相对段都归一）
        val rootCanonical = ws.canonicalPath
        val dirCanonical = dir!!.canonicalPath
        assertTrue(
            dirCanonical.startsWith(rootCanonical + java.io.File.separator),
            "🔴 下载目录必须落在工作区内（穿越段已清洗）：$dirCanonical",
        )
        assertTrue(!dir.path.contains(".."), "清洗后路径不得残留 .. 段：${dir.path}")
        // 有效内容被保留（穿越序列被中和成下划线后 trim 掉），不是兜底名
        assertTrue(dir.path.contains("ou_evil"), "senderId 的有效内容应保留：${dir.path}")
        ws.deleteRecursively()
    }

    /**
     * 🔴 2026-09-17 复核修复（二次）：**纯穿越序列**段必须拒绝（而非兜底成 `"file"`）。
     * `take(12)` 使 messageId 段常整段是 `"../"` 序列——清洗后无有效内容，
     * 若兜底成字面 `"file"`，多个不同消息会混用同一物理目录。
     */
    @Test
    fun `dirFor 拒绝纯穿越序列的消息段`() {
        val ws = workspace("pureSlip")
        val store = DownloadStore({ config(ws.path) }, BridgeLog.Silent)
        assertNull(
            store.dirFor("ou_a", "../../../../etc/passwd", "a.log"),
            "messageId 截 12 位后是纯 `../` 序列，无有效内容 ⇒ 应拒绝而非兜底",
        )
        assertNull(store.dirFor("../../../", "om_1", "a.log"), "senderId 纯穿越序列同样拒绝")
        ws.deleteRecursively()
    }

    @Test
    fun `root 空白-dirFor 返回 null（下载前置拒绝）`() {
        val store = DownloadStore({ config("") }, BridgeLog.Silent)
        assertNull(store.dirFor("ou_a", "om_1", "a.log"))
    }

    /**
     * 🔴 2026-09-17 复核修复（二次，block 级）：无有效内容的段必须**拒绝**，不得兜底成 `"file"`。
     *
     * 复核实测：修复前 `sanitizeFileName` 的 BC-410 兜底把 `""` / `".."` / `"___"` 全变成字面
     * `"file"` ⇒ 三个不同 senderId 混用同一物理目录 `<root>/downloads/file/...`。
     * 而账本 [DownloadStore.recordStored] 按**原始** senderId 记账、[DownloadStore.rescan] 按
     * **目录名**记账 ⇒ 配额计数与实体错位（一方 evict 递归删目录会删掉另一方的文件且计数不回退）。
     */
    @Test
    fun `dirFor 拒绝无有效内容的发送者段-不得兜底成 file`() {
        val ws = workspace("blankseg")
        val store = DownloadStore({ config(ws.path) }, BridgeLog.Silent)
        listOf("", "   ", "..", "___", "...").forEach { bad ->
            assertNull(
                store.dirFor(bad, "om_1", "a.log"),
                "senderId=「$bad」无有效内容，必须拒绝（兜底成 file 会让多个 sender 混用同一目录）",
            )
        }
        assertNull(store.dirFor("ou_a", "", "a.log"), "messageId 段同样不得兜底")
        assertNull(store.dirFor("ou_a", "___", "a.log"), "messageId 段同样不得兜底")
        ws.deleteRecursively()
    }

    // ── 记账 ──

    @Test
    fun `recordStored 递归测字节-子目录产物一并入账`() {
        val ws = workspace("bytes")
        val store = DownloadStore({ config(ws.path) }, BridgeLog.Silent)
        val dir = store.dirFor("ou_a", "om_1", "a.zip")!!.apply { mkdirs() }
        File(dir, "a.zip").writeBytes(ByteArray(100))
        File(dir, "extracted").mkdirs()
        File(dir, "extracted/b.log").writeBytes(ByteArray(50))
        store.recordStored(dir, "ou_a")
        assertEquals(150L, store.senderBytes("ou_a"), "递归求和子目录")
        assertEquals(150L, store.totalBytes())
        assertEquals(1, store.size)
        ws.deleteRecursively()
    }

    @Test
    fun `同目录重复记账-覆盖旧条目不重复累计`() {
        val ws = workspace("dedup")
        val store = DownloadStore({ config(ws.path) }, BridgeLog.Silent)
        val dir = storeDir(store, ws, "ou_a", "om_1", "a.log", bytes = 100)
        assertTrue(store.isRecorded(dir))
        store.recordStored(dir, "ou_a") // 预处理产物增长后再记账：幂等覆盖
        File(dir, "decoded").mkdirs()
        File(dir, "decoded/a.log.log").writeBytes(ByteArray(30))
        store.recordStored(dir, "ou_a")
        assertEquals(130L, store.senderBytes("ou_a"), "🔴 覆盖记账不得重复累计")
        assertEquals(1, store.size)
        ws.deleteRecursively()
    }

    // ── per-user 配额逐出 ──

    @Test
    fun `per-user 超限-只逐该 sender 最旧目录且不动别的 sender`() {
        val ws = workspace("peruser")
        val store = DownloadStore({ config(ws.path, perUserQuotaBytes = 150) }, BridgeLog.Silent)
        val d1 = storeDir(store, ws, "ou_a", "om_1", "a.log", bytes = 100)
        val dOther = storeDir(store, ws, "ou_b", "om_2", "b.log", bytes = 100)
        val d3 = storeDir(store, ws, "ou_a", "om_3", "c.log", bytes = 100)
        store.evictIfNeeded()
        assertEquals(100L, store.senderBytes("ou_a"), "该 sender 逐到限内（100）")
        assertEquals(100L, store.senderBytes("ou_b"), "🔴 他人目录不得被逐")
        assertFalse(d1.exists(), "该 sender 最旧被整删")
        assertTrue(dOther.exists())
        assertTrue(d3.exists())
        ws.deleteRecursively()
    }

    @Test
    fun `per-user 超限-逐出后字节数下降且磁盘被删`() {
        val ws = workspace("peruser2")
        val store = DownloadStore({ config(ws.path, perUserQuotaBytes = 150) }, BridgeLog.Silent)
        val d1 = storeDir(store, ws, "ou_a", "om_1", "a.log", bytes = 100)
        val d2 = storeDir(store, ws, "ou_a", "om_3", "c.log", bytes = 100)
        store.evictIfNeeded()
        assertEquals(100L, store.senderBytes("ou_a"), "超 150 ⇒ 逐一个后余 100")
        assertEquals(1, store.size)
        assertFalse(d1.exists(), "最旧目录被整目录删除")
        assertTrue(d2.exists(), "最新目录保留")
        assertEquals(1L, store.evictions.get())
        ws.deleteRecursively()
    }

    // ── 全局配额逐出 ──

    @Test
    fun `全局超限-跨 sender 从队头逐`() {
        val ws = workspace("total")
        val store = DownloadStore({ config(ws.path, perUserQuotaBytes = 0, totalQuotaBytes = 250) }, BridgeLog.Silent)
        val d1 = storeDir(store, ws, "ou_a", "om_1", "a.log", bytes = 100)
        val d2 = storeDir(store, ws, "ou_b", "om_2", "b.log", bytes = 100)
        val d3 = storeDir(store, ws, "ou_a", "om_3", "c.log", bytes = 100)
        store.evictIfNeeded()
        assertEquals(200L, store.totalBytes(), "全局 250 超限 ⇒ 逐最旧一个即回限内（300-100=200≤250）")
        assertFalse(d1.exists(), "最旧 d1 被逐")
        assertTrue(d2.exists(), "回到限内即停，d2 保留")
        assertTrue(d3.exists())
        assertEquals(1L, store.evictions.get())
        ws.deleteRecursively()
    }

    // ── rescan ──

    @Test
    fun `rescan 重建账本-三层目录重算字节且幂等`() {
        val ws = workspace("rescan")
        var cfg = config(ws.path)
        val store = DownloadStore({ cfg }, BridgeLog.Silent)
        storeDir(store, ws, "ou_a", "om_1", "a.log", bytes = 100)
        storeDir(store, ws, "ou_b", "om_2", "b.log", bytes = 60)
        store.clear() // 模拟重启丢账本
        assertEquals(0, store.size)
        store.rescan()
        assertEquals(2, store.size, "三层目录扫描重建")
        assertEquals(160L, store.totalBytes())
        assertEquals(100L, store.senderBytes("ou_a"))
        store.rescan() // 幂等：先 clear 再扫，不重复累计
        assertEquals(160L, store.totalBytes(), "rescan 幂等")
        ws.deleteRecursively()
    }

    @Test
    fun `rescan 清除 legacy tmp 目录`() {
        val ws = workspace("legacy")
        val store = DownloadStore({ config(ws.path) }, BridgeLog.Silent)
        val legacy = File(File(ws, DownloadPolicy.DOWNLOADS_DIRNAME), "tmp-123").apply { mkdirs() }
        File(legacy, "x.log").writeText("x")
        store.rescan()
        assertFalse(legacy.exists(), "legacy tmp-* 被清扫")
        ws.deleteRecursively()
    }

    @Test
    fun `rescan 对 root 空白与缺目录安全`() {
        val store = DownloadStore({ config("") }, BridgeLog.Silent)
        store.rescan() // 不抛
        val ws = workspace("empty")
        DownloadStore({ config(ws.path) }, BridgeLog.Silent).rescan() // downloads/ 不存在
        ws.deleteRecursively()
    }

    // ── 域纪律（与 DownloadPolicyTest 同位补充） ──

    /** 🔴 D16：DownloadStore 无 synchronized/Mutex，可变状态全部为并发原语。 */
    @Test
    fun `D16 DownloadStore 零 synchronized-Mutex 且账本粒度为目录`() {
        val file = File(
            com.jiaoay.lark.bridge.pipeline.testRepoRoot(),
            "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/files/DownloadStore.kt",
        )
        assertTrue(file.isFile, "被扫描的源文件必须存在：${file.path}")
        val normalized = com.jiaoay.lark.bridge.pipeline.stripCommentsAndStrings(file.readText())
        assertFalse(normalized.contains("synchronized"), "🔴 D16：零 synchronized")
        assertFalse(normalized.contains("Mutex"), "🔴 D16：零 Mutex")
        assertFalse(normalized.contains("@Volatile"), "🔴 D16：零 @Volatile")
        assertFalse(normalized.contains("ProcessBuilder"), "🔴 E 组守护")
    }
}
