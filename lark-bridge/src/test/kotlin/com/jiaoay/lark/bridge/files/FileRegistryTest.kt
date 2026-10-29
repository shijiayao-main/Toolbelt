// 引用流重设计（2026-09-11）机制 A 单测：FileRegistry（登记表）。
// 覆盖：TTL 惰性清除 / 容量 FIFO 逐出 / 跨 chat 拒查 / 幂等覆盖 / clock 盖章权。
package com.jiaoay.lark.bridge.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileRegistryTest {

    private fun record(
        messageId: String = "om_1",
        chatId: String = "oc_test",
        senderId: String = "ou_alice",
        vararg names: String = arrayOf("a.zip"),
    ): FileRegistry.FileRecord {
        val rec = FileRegistry.FileRecord(
            messageId = messageId,
            chatId = chatId,
            senderId = senderId,
            resources = names.map { FileRegistry.ResourceRef(fileKey = "fk_$it", fileName = it) },
            registeredAtMillis = 0L,
        )
        return rec
    }

    // ── 基本登记与查表 ──

    @Test
    fun `登记后可按 messageId 与 chatId 查到`() {
        val registry = FileRegistry(clock = { 1_000L })
        val saved = registry.register(
            messageId = "om_1",
            chatId = "oc_test",
            senderId = "ou_alice",
            resources = listOf(FileRegistry.ResourceRef("fk_1", "crash.zip")),
        )
        assertEquals(1, registry.size)
        // clock 盖章权：registeredAtMillis 由 registry 的 clock 产生，不是调用方自带
        assertEquals(1_000L, saved.registeredAtMillis)
        val found = registry.lookup("om_1", "oc_test", ttlMillis = Long.MAX_VALUE)
        assertTrue(found != null)
        assertEquals("ou_alice", found!!.senderId)
        assertEquals("fk_1", found.resources.single().fileKey)
    }

    @Test
    fun `未登记的 messageId 返回 null`() {
        val registry = FileRegistry()
        assertNull(registry.lookup("om_missing", "oc_test", Long.MAX_VALUE))
    }

    @Test
    fun `messageId 为空串直接返回 null`() {
        val registry = FileRegistry()
        registry.register("om_1", "oc_test", "ou_a", emptyList())
        assertNull(registry.lookup("", "oc_test", Long.MAX_VALUE))
    }

    // ── 跨会话拒查 ──

    @Test
    fun `chatId 不匹配视为未命中（跨会话引用拒查）`() {
        val registry = FileRegistry()
        registry.register("om_1", "oc_test", "ou_alice", listOf(FileRegistry.ResourceRef("fk", "a.log")))
        assertNull(registry.lookup("om_1", "oc_other", Long.MAX_VALUE), "🔴 跨会话引用必须视为未命中")
        // 条目本身不被跨会话查询误删
        assertTrue(registry.lookup("om_1", "oc_test", Long.MAX_VALUE) != null)
    }

    // ── TTL 惰性清除 ──

    @Test
    fun `TTL 过期-lookup 返回 null 且条目被惰性清除`() {
        var now = 0L
        val registry = FileRegistry(clock = { now })
        registry.register("om_1", "oc_test", "ou_a", listOf(FileRegistry.ResourceRef("fk", "a.log")))
        now = 72 * 3_600_000L + 1
        assertNull(registry.lookup("om_1", "oc_test", ttlMillis = 72 * 3_600_000L), "过 1ms 即过期")
        assertEquals(0, registry.size, "🔴 惰性清除：过期条目在 lookup 时被移除")
    }

    @Test
    fun `TTL 未过期可查且 ttlMillis 非正数视为永不过期`() {
        var now = 0L
        val registry = FileRegistry(clock = { now })
        registry.register("om_1", "oc_test", "ou_a", listOf(FileRegistry.ResourceRef("fk", "a.log")))
        now = 100L
        assertTrue(registry.lookup("om_1", "oc_test", ttlMillis = 0L) != null, "ttl<=0 = 永不过期")
        assertTrue(registry.lookup("om_1", "oc_test", ttlMillis = -1L) != null)
        // 恰等边界：clock - registeredAt > ttl 才过期（> 不是 >=）
        now = 200L
        assertTrue(registry.lookup("om_1", "oc_test", ttlMillis = 200L) != null, "恰等 TTL 应仍可查（> 判定）")
        now = 201L
        assertNull(registry.lookup("om_1", "oc_test", ttlMillis = 200L))
    }

    // ── 容量 FIFO 逐出 ──

    @Test
    fun `容量溢出-队头最旧条目被逐出`() {
        var now = 0L
        val registry = FileRegistry(clock = { now }, maxEntries = 3)
        repeat(4) { i ->
            now = i * 10L
            registry.register("om_$i", "oc_test", "ou_a", listOf(FileRegistry.ResourceRef("fk", "a.log")))
        }
        assertEquals(3, registry.size, "容量上限 3")
        assertNull(registry.lookup("om_0", "oc_test", Long.MAX_VALUE), "最旧的 om_0 被逐出")
        assertTrue(registry.lookup("om_1", "oc_test", Long.MAX_VALUE) != null)
        assertTrue(registry.lookup("om_3", "oc_test", Long.MAX_VALUE) != null)
    }

    @Test
    fun `同 messageId 重登记-幂等覆盖且不占新容量`() {
        val registry = FileRegistry(clock = { 0L }, maxEntries = 2)
        registry.register("om_1", "oc_test", "ou_a", listOf(FileRegistry.ResourceRef("fk_old", "a.log")))
        registry.register("om_2", "oc_test", "ou_b", listOf(FileRegistry.ResourceRef("fk", "b.log")))
        val updated = registry.register(
            "om_1", "oc_test", "ou_a",
            listOf(FileRegistry.ResourceRef("fk_new", "renamed.zip")),
        )
        assertEquals(2, registry.size, "覆盖不新增条目")
        assertEquals("fk_new", updated.resources.single().fileKey)
        val found = registry.lookup("om_1", "oc_test", Long.MAX_VALUE)!!
        assertEquals("fk_new", found.resources.single().fileKey, "重登记以最新为准")
    }

    // ── 辅助构造的 record() 不再被生产代码消费（防呆） ──

    @Test
    fun `FileRecord 仍是公开数据类-可被 rescan 场景自建`() {        val rec = record("om_x")
        assertEquals("om_x", rec.messageId)
        assertFalse(rec.resources.isEmpty())
    }

    // ── 回执别名（2026-09-15 回执别名批） ──

    @Test
    fun `alias 后按别名 id 查到原 record 且 messageId 保留原文件消息 id`() {
        val registry = FileRegistry(clock = { 0L })
        val original = registry.register("om_file", "oc_test", "ou_a", listOf(FileRegistry.ResourceRef("fk_1", "crash.zip")))
        val aliased = registry.alias("om_ack_reply", original)

        assertEquals(2, registry.size, "别名是独立条目（原 + 别名）")
        val byAlias = registry.lookup("om_ack_reply", "oc_test", Long.MAX_VALUE)
        assertTrue(byAlias != null, "引用回执 id 应命中文件")
        // 🔴 别名条目的 messageId 必须仍是原文件消息 id——换成回执 id 会让下载目录/单飞锁按 dest 漂移 ⇒ 双下载双计配额
        assertEquals("om_file", byAlias.messageId, "🔴 messageId 必须保留原文件消息 id（dest 推导依据）")
        assertEquals("fk_1", byAlias.resources.single().fileKey, "资源面原样")
        assertEquals("ou_a", byAlias.senderId, "配额归属人不变")
        assertEquals(aliased.messageId, original.messageId)
    }

    @Test
    fun `alias 幂等覆盖-重复登记不占新容量`() {
        val registry = FileRegistry(clock = { 0L }, maxEntries = 2)
        val original = registry.register("om_file", "oc_test", "ou_a", listOf(FileRegistry.ResourceRef("fk", "a.log")))
        registry.alias("om_ack_1", original)
        registry.alias("om_ack_1", original)
        assertEquals(2, registry.size, "同别名 id 重登记 = 幂等覆盖")
    }

    @Test
    fun `alias 条目受同款跨会话护栏`() {
        val registry = FileRegistry(clock = { 0L })
        val original = registry.register("om_file", "oc_test", "ou_a", listOf(FileRegistry.ResourceRef("fk", "a.log")))
        registry.alias("om_ack", original)
        assertNull(registry.lookup("om_ack", "oc_other", Long.MAX_VALUE), "他 chat 引用别名不得命中")
    }

    @Test
    fun `alias 条目 TTL 独立惰性清除-别名过期后原条目仍可命中`() {
        var now = 0L
        val registry = FileRegistry(clock = { now })
        val original = registry.register("om_file", "oc_test", "ou_a", listOf(FileRegistry.ResourceRef("fk", "a.log")))
        now = 10L
        registry.alias("om_ack", original) // 别名时间戳 = 10（比原条目新）
        now = 15L
        assertEquals(2, registry.size, "清除前两条目俱在（原 + 别名）")
        assertNull(registry.lookup("om_ack", "oc_test", ttlMillis = 3), "别名过期即清除（10+3<15）")
        assertTrue(registry.lookup("om_file", "oc_test", ttlMillis = 3) == null, "原条目更老、同样过期")
        // 反向场景：别名比原条目新 ⇒ 原条目先过期、别名仍活
        val original2 = registry.register("om_file2", "oc_test", "ou_a", listOf(FileRegistry.ResourceRef("fk2", "b.log"))) // t=15
        now = 20L
        registry.alias("om_ack2", original2) // 别名 t=20
        now = 23L // ttl=4：原条目 15+4<23 过期；别名 20+4>23 仍活
        assertNull(registry.lookup("om_file2", "oc_test", ttlMillis = 4), "原条目（t=15）先过期")
        assertTrue(registry.lookup("om_ack2", "oc_test", ttlMillis = 4) != null, "别名（t=20）仍活")
    }

    @Test
    fun `alias 参与同款 FIFO 逐出`() {
        val registry = FileRegistry(clock = { 0L }, maxEntries = 3)
        val a = registry.register("om_a", "oc_test", "ou_a", listOf(FileRegistry.ResourceRef("fk", "a.log")))
        registry.alias("ack_a", a)
        val b = registry.register("om_b", "oc_test", "ou_b", listOf(FileRegistry.ResourceRef("fk", "b.log")))
        registry.alias("ack_b", b)
        // 🔴 evictOverflow 在 register/alias 内即时执行（非惰性）：第 4 条 ack_b 落入即逐出最老的 om_a
        assertEquals(3, registry.size, "容量 3 即时逐出，恒不超")
        assertNull(registry.lookup("om_a", "oc_test", Long.MAX_VALUE), "最老原条目已被逐出")
        val c = registry.register("om_c", "oc_test", "ou_c", listOf(FileRegistry.ResourceRef("fk", "c.log")))
        registry.alias("ack_c", c)
        assertEquals(3, registry.size)
        assertNull(registry.lookup("ack_a", "oc_test", Long.MAX_VALUE), "最老别名同样被逐出")
        assertTrue(registry.lookup("ack_c", "oc_test", Long.MAX_VALUE) != null, "最新别名存活")
    }
}
