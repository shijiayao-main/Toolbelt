package com.jiaoay.toolbelt.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android 全量键值表数据完整性守护：
 * - 总数断言（[AndroidKeyCodes.TOTAL_COUNT]）：官方公开常量全集增删须有意为之
 *   （同步全表与本锚），防条目静默丢失；
 * - 键值唯一/常量名唯一/键值升序：查询表的两面主键约束，破坏即查询行错乱；
 * - 键值域 ≤ [DeviceKeys.MAX_CUSTOM_KEYCODE]：全表任意命中码都在自定义兜底与
 *   `input keyevent` 直收域内（查询行点注入不被值域拒绝）；
 * - 与 [DeviceKeys] 高频子集同源：子集每键在全量表中有同码常量且常量名一致
 *   （防两套口径——子集改名/改码不通知全表即红）；
 * - 查询纯函数（[AndroidKeyCodes.matches]）：数字前缀/名称包含/空查询零命中矩阵。
 */
class AndroidKeyCodesTest {

    @Test
    fun `total count matches pinned anchor and list size`() {
        assertEquals(AndroidKeyCodes.TOTAL_COUNT, AndroidKeyCodes.ALL.size, "锚常量与全表条目数须一致")
        assertEquals(338, AndroidKeyCodes.ALL.size, "官方 KeyEvent 公开常量全集增删须同步全表与本断言")
    }

    @Test
    fun `codes and names are unique and codes are ascending`() {
        val codes = AndroidKeyCodes.ALL.map { it.code }
        val names = AndroidKeyCodes.ALL.map { it.name }
        assertEquals(codes.size, codes.distinct().size, "键值唯一破坏 = 查询行错乱")
        assertEquals(names.size, names.distinct().size, "常量名唯一破坏 = 查询行错乱")
        assertEquals(codes, codes.sorted(), "全表须键值升序（matches 命中序契约）")
    }

    @Test
    fun `all codes are inside custom key bound and keyevent range`() {
        assertTrue(
            AndroidKeyCodes.ALL.all { it.code in 0..DeviceKeys.MAX_CUSTOM_KEYCODE },
            "全表键值须在 0..${DeviceKeys.MAX_CUSTOM_KEYCODE} 值域内（查询行任意命中码可注入）",
        )
        // 官方键值上界即末位常量——全表无越域怪值
        assertEquals(337, AndroidKeyCodes.ALL.last().code)
        assertEquals("KEYCODE_F24", AndroidKeyCodes.ALL.last().name)
    }

    @Test
    fun `byCode and byName lookups hit exact entries and miss out-of-table inputs`() {
        val volUp = AndroidKeyCodes.byCode(24)
        assertEquals("KEYCODE_VOLUME_UP", volUp?.name)
        assertEquals(24, AndroidKeyCodes.byName("KEYCODE_VOLUME_UP")?.code)
        assertEquals(24, AndroidKeyCodes.byName("keycode_volume_up")?.code, "常量名查忽略大小写")
        assertNull(AndroidKeyCodes.byCode(-1), "负键值零命中")
        assertNull(AndroidKeyCodes.byCode(999), "表外键值零命中")
        assertNull(AndroidKeyCodes.byName("KEYCODE_NO_SUCH_KEY"), "表外常量名零命中")
    }

    @Test
    fun `matches does numeric prefix and case-insensitive name containment`() {
        assertTrue(AndroidKeyCodes.matches("").isEmpty(), "空查询零命中（不倾倒全表）")
        assertTrue(AndroidKeyCodes.matches("   ").isEmpty(), "纯空白查询零命中")

        // 数字 = 键值十进制串前缀
        val byNumber = AndroidKeyCodes.matches("24")
        assertTrue(byNumber.first().code == 24, "精确键值排首位（命中序 = 键值升序）")
        assertTrue(byNumber.all { it.code.toString().startsWith("24") }, "数字查询须前缀匹配")
        assertTrue(
            byNumber.map { it.code }.zipWithNext().all { (a, b) -> a < b },
            "命中序须键值升序",
        )

        // 文本 = 常量名忽略大小写包含
        val byName = AndroidKeyCodes.matches("volume")
        assertTrue(byName.all { it.name.contains("VOLUME") }, "文本查询须名称包含")
        assertTrue(
            byName.any { it.name == "KEYCODE_VOLUME_UP" } && byName.any { it.name == "KEYCODE_VOLUME_DOWN" },
            "volume 须命中音量键族",
        )
        assertEquals(byName, AndroidKeyCodes.matches("KEYCODE_VOLUME"), "带不带 KEYCODE_ 前缀同命中")
        assertEquals(byName, AndroidKeyCodes.matches("Volume"), "大小写不敏感")

        assertTrue(AndroidKeyCodes.matches("zzz_no_such_key").isEmpty(), "无命中如实返回空集")
    }

    @Test
    fun `high-frequency subset stays consistent with the full table`() {
        // 子集 → 全表官方常量名映射（同码同名才算同源）
        val expected = mapOf(
            DeviceKeys.HOME to "KEYCODE_HOME",
            DeviceKeys.BACK to "KEYCODE_BACK",
            DeviceKeys.MENU to "KEYCODE_MENU",
            DeviceKeys.RECENTS to "KEYCODE_APP_SWITCH",
            DeviceKeys.NOTIFICATION to "KEYCODE_NOTIFICATION",
            DeviceKeys.POWER to "KEYCODE_POWER",
            DeviceKeys.WAKEUP to "KEYCODE_WAKEUP",
            DeviceKeys.SLEEP to "KEYCODE_SLEEP",
            DeviceKeys.VOLUME_UP to "KEYCODE_VOLUME_UP",
            DeviceKeys.VOLUME_DOWN to "KEYCODE_VOLUME_DOWN",
            DeviceKeys.VOLUME_MUTE to "KEYCODE_VOLUME_MUTE",
            DeviceKeys.BRIGHTNESS_UP to "KEYCODE_BRIGHTNESS_UP",
            DeviceKeys.BRIGHTNESS_DOWN to "KEYCODE_BRIGHTNESS_DOWN",
            DeviceKeys.DPAD_UP to "KEYCODE_DPAD_UP",
            DeviceKeys.DPAD_DOWN to "KEYCODE_DPAD_DOWN",
            DeviceKeys.DPAD_LEFT to "KEYCODE_DPAD_LEFT",
            DeviceKeys.DPAD_RIGHT to "KEYCODE_DPAD_RIGHT",
            DeviceKeys.DPAD_CENTER to "KEYCODE_DPAD_CENTER",
        )
        val subset = DeviceKeys.COMMON_KEYS + DeviceKeys.DPAD_KEYS
        assertEquals(expected.keys, subset.toSet(), "子集键集变化须同步本同源断言映射")

        for ((key, name) in expected) {
            val entry = AndroidKeyCodes.byCode(key.keyCode)
            assertEquals(name, entry?.name, "「${key.label}」(${key.keyCode}) 在全表中的常量名须为 $name（防两套口径）")
        }
    }

    @Test
    fun `customKey domain accepts every injectable full-table code`() {
        // 查询行命中码与自定义兜底同值域——全表非零码逐一经 customKey 必须成键
        for (entry in AndroidKeyCodes.ALL) {
            if (entry.code == 0) continue // 0 号唯一不可注入码，下方单列断言
            val parsed = DeviceKeys.customKey(entry.code.toString())
            assertEquals(entry.code, parsed?.keyCode, "${entry.name} 须可经 customKey 成键")
        }
        // 0 号（KEYCODE_UNKNOWN）是全表唯一不可注入码：customKey 显式拒绝不静默
        assertNull(DeviceKeys.customKey("0"), "0 号键（KEYCODE_UNKNOWN）不可注入，customKey 显式拒绝")
    }
}
