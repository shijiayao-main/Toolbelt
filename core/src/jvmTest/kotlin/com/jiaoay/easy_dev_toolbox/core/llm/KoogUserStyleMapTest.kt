// 2026-09-14 per-user 性格批守护：KoogUserStyleMap 纯编解码契约。
//
// 契约（见 KoogUserStyleMap 类头）：
// 1. decode 坏值防御：空串 / 损坏 JSON / 半写崩溃残留一律回落空表，绝不抛（Preferences 无事务）；
// 2. encode 去重：同 openId 后写胜；
// 3. styleIdOf 查询：null = 跟随全局（无哨兵值）；
// 4. upsert/remove 为 List 纯函数（整表写，无增量）。
package com.jiaoay.easy_dev_toolbox.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoogUserStyleMapTest {

    @Test
    fun `decode blank and corrupted raw falls back to empty list`() {
        assertEquals(emptyList(), KoogUserStyleMap.decode(""))
        assertEquals(emptyList(), KoogUserStyleMap.decode("not-json{"))
        assertEquals(emptyList(), KoogUserStyleMap.decode("""{"broken":true}"""), "非数组形态（对象）也须回落空表")
    }

    @Test
    fun `encode then decode round trips and dedupes by last write`() {
        val entries = listOf(
            KoogUserStyleEntry("ou_1", "catgirl"),
            KoogUserStyleEntry("ou_2", "tsundere"),
            // 同 openId 两条：encode 后写胜（ou_1 → professional）
            KoogUserStyleEntry("ou_1", "professional"),
        )
        val decoded = KoogUserStyleMap.decode(KoogUserStyleMap.encode(entries))
        assertEquals(2, decoded.size, "同 openId 须去重")
        assertEquals("professional", KoogUserStyleMap.styleIdOf(decoded, "ou_1"), "同 openId 后写胜")
        assertEquals("tsundere", KoogUserStyleMap.styleIdOf(decoded, "ou_2"))
    }

    @Test
    fun `styleIdOf missing openId returns null meaning follow global`() {
        val entries = KoogUserStyleMap.upsert(emptyList(), "ou_1", "catgirl")
        assertNull(KoogUserStyleMap.styleIdOf(entries, "ou_other"), "未命中映射 = null（跟随全局，无哨兵值）")
    }

    @Test
    fun `upsert replaces existing entry and remove drops it`() {
        var entries = KoogUserStyleMap.upsert(emptyList(), "ou_1", "catgirl")
        entries = KoogUserStyleMap.upsert(entries, "ou_1", "cub")
        assertEquals(1, entries.size, "upsert 替换不追加")
        assertEquals("cub", KoogUserStyleMap.styleIdOf(entries, "ou_1"))

        entries = KoogUserStyleMap.remove(entries, "ou_1")
        assertTrue(entries.isEmpty(), "remove 后空表（= 恢复跟随全局）")
        assertNull(KoogUserStyleMap.styleIdOf(entries, "ou_1"))
    }
}
