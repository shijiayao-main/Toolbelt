package com.jiaoay.easy_dev_toolbox.core.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * sys 输出脱敏器守护：
 * 1. secret 形态遮蔽矩阵（TOKEN/PASSWORD/PASSWD/SECRET/CREDENTIAL 子串 + KEY 结尾，
 *    大小写不敏感 + export 前缀形态）；
 * 2. IM 变量族恒明文放行（诊断对象本体）+ GTK_IM_MODULE 不遮的对照；
 * 3. 有意不遮面：非赋值行、空值赋值行、fcitx5 复数键名热键行；
 * 4. 行数与换行守恒（脱敏不截断——截断归工具层 capAt）。
 */
class SysOutputSanitizerTest {

    // ── secret 形态遮蔽矩阵 ──

    @Test
    fun `遮蔽键子串命中即遮值`() {
        val cases = mapOf(
            "GITHUB_TOKEN=ghp_abcdef123456" to "GITHUB_TOKEN=***",
            "API_TOKEN=sk-live-999" to "API_TOKEN=***",
            "PASSWORD=hunter2" to "PASSWORD=***",
            "DB_PASSWD=p@ss" to "DB_PASSWD=***",
            "SECRET_KEY=abc" to "SECRET_KEY=***",
            "appSecret=x-t-1234" to "appSecret=***",
            "CREDENTIAL_DATA=xyz" to "CREDENTIAL_DATA=***",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, SysOutputSanitizer.sanitize(input), "遮蔽形态：$input")
        }
    }

    @Test
    fun `KEY 结尾形态遮蔽（分段连写与无分隔连写）`() {
        val cases = mapOf(
            "API_KEY=sk-123" to "API_KEY=***",
            "MYKEY=value" to "MYKEY=***",
            "HOTKEY=F1" to "HOTKEY=***",
            "access_key=AKIA123" to "access_key=***",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, SysOutputSanitizer.sanitize(input), "KEY 结尾遮蔽：$input")
        }
    }

    @Test
    fun `export 前缀形态同遮（xprofile 的 export 行）`() {
        assertEquals(
            "export GITHUB_TOKEN=***",
            SysOutputSanitizer.sanitize("export GITHUB_TOKEN=ghp_abc"),
            "export 前缀不得成为遮蔽绕过面",
        )
    }

    // ── IM 变量族恒明文放行（对照面） ──

    @Test
    fun `IM 变量族恒明文放行`() {
        val lines = listOf(
            "GTK_IM_MODULE=fcitx",
            "QT_IM_MODULE=fcitx",
            "XMODIFIERS=@im=fcitx",
            "SDL_IM_MODULE=fcitx",
            "INPUT_METHOD=fcitx",
        )
        lines.forEach { line ->
            assertEquals(line, SysOutputSanitizer.sanitize(line), "IM 诊断主变量不得遮蔽：$line")
            assertTrue(
                line.substringBefore('=') in SysOutputSanitizer.ALLOWED_KEYS,
                "放行清单必须显式收录（遮蔽键集加宽时的对照锚）：$line",
            )
        }
    }

    @Test
    fun `GTK_IM_MODULE 不遮的对照（含 export 形态与桌面会话变量）`() {
        val plain = listOf(
            "GTK_IM_MODULE=fcitx",
            "export GTK_IM_MODULE=fcitx",
            "export XMODIFIERS=@im=fcitx",
            "XDG_CURRENT_DESKTOP=ubuntu:GNOME",
            "XDG_SESSION_TYPE=wayland",
            "DESKTOP_SESSION=ubuntu",
            "LANG=zh_CN.UTF-8",
        )
        plain.forEach { line ->
            assertEquals(line, SysOutputSanitizer.sanitize(line), "对照面：不得遮蔽 $line")
        }
    }

    // ── 有意不遮面（误伤取舍） ──

    @Test
    fun `非赋值行与空值赋值行原样放行`() {
        val plain = listOf(
            "[Hotkey]",
            "no assignment here",
            "TOKEN=",
            "GITHUB_TOKEN=",
            "  ",
        )
        plain.forEach { line ->
            assertEquals(line, SysOutputSanitizer.sanitize(line), "无泄密面行不得改写：$line")
        }
    }

    @Test
    fun `fcitx5 复数键名热键行不遮（Keys 结尾是快捷键配置不是凭据）`() {
        val plain = listOf(
            "TriggerKeys=Control+Space",
            "EnumerateWithTriggerKeys=True",
            "EnumerateForwardKeys=",
            "AltTriggerKeys=Shift_L",
        )
        plain.forEach { line ->
            assertEquals(line, SysOutputSanitizer.sanitize(line), "热键诊断价值不得消灭：$line")
        }
    }

    // ── 行中子串赋值（journalctl 形态：行首是时间戳，赋值在消息段中） ──

    @Test
    fun `journalctl 行中赋值形态遮蔽（时间戳前缀不绕过）`() {
        val cases = mapOf(
            "Oct 14 10:00:00 host app[123]: token=abc123" to "Oct 14 10:00:00 host app[123]: token=***",
            "Oct 14 10:00:00 host app[123]: password=hunter2 auth ok" to "Oct 14 10:00:00 host app[123]: password=*** auth ok",
            "Oct 14 10:00:00 host app[123]: API_KEY=sk-123; user=gdm" to "Oct 14 10:00:00 host app[123]: API_KEY=***; user=gdm",
            "10:00:00 host systemd[1]: SESSION_TOKEN=\"quoted-secret\"" to "10:00:00 host systemd[1]: SESSION_TOKEN=***",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, SysOutputSanitizer.sanitize(input), "行中遮蔽形态：$input")
        }
    }

    @Test
    fun `journalctl 真实整段输出逐行脱敏且诊断主体保留`() {
        val input = """
            Oct 14 09:58:01 host systemd[1234]: Started fcitx5 daemon.
            Oct 14 09:58:02 host fcitx5[1300]: Loading profile token=ghp_live_leak
            Oct 14 09:58:03 host gnome-shell[1400]: GTK_IM_MODULE=fcitx
            Oct 14 09:58:04 host app[1500]: auth failed password=supersecret retry=1
        """.trimIndent()
        val sanitized = SysOutputSanitizer.sanitize(input)
        assertTrue("token=***" in sanitized, "行中 token 赋值须遮蔽")
        assertTrue("password=***" in sanitized, "行中 password 赋值须遮蔽")
        assertFalse("ghp_live_leak" in sanitized, "遮蔽后不得残留原值")
        assertFalse("supersecret" in sanitized)
        assertTrue("GTK_IM_MODULE=fcitx" in sanitized, "IM 变量行中形态保持明文（诊断对象本体）")
        assertTrue("Started fcitx5 daemon." in sanitized, "普通日志行保持原样")
    }

    // ── JSON 键值形态 ──

    @Test
    fun `JSON 键值形态遮蔽（引号内键名判定）`() {
        val cases = mapOf(
            """{"token":"abc123","user":"gdm"}""" to """{"token":"***","user":"gdm"}""",
            """"API_KEY": "sk-123"""" to """"API_KEY":"***"""",
            """{"name":"fcitx5","version":5}""" to """{"name":"fcitx5","version":5}""",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, SysOutputSanitizer.sanitize(input), "JSON 遮蔽形态：$input")
        }
    }

    @Test
    fun `JSON 空值与普通键保持原样`() {
        val plain = listOf(
            """{"token":""}""",
            """{"name":"fcitx5"}""",
        )
        plain.forEach { line ->
            assertEquals(line, SysOutputSanitizer.sanitize(line), "无泄密面 JSON 行不得改写：$line")
        }
    }

    // ── 行中空值与幂等 ──

    @Test
    fun `行中空值赋值原样放行且已遮蔽段再扫幂等`() {
        assertEquals(
            "Oct 14 10:00:00 host app[123]: token=",
            SysOutputSanitizer.sanitize("Oct 14 10:00:00 host app[123]: token="),
            "行中空值无泄密面，原样放行",
        )
        assertEquals(
            "Oct 14 10:00:00 host app[123]: token=***",
            SysOutputSanitizer.sanitize("Oct 14 10:00:00 host app[123]: token=***"),
            "已遮蔽段再扫幂等（*** 不被二次改写）",
        )
    }

    // ── ALLOWED_KEYS 恒明文清单棘轮（只许收不许扩） ──

    @Test
    fun `ALLOWED_KEYS 子集棘轮：只许收不许扩`() {
        val original = setOf(
            "GTK_IM_MODULE", "QT_IM_MODULE", "XMODIFIERS", "SDL_IM_MODULE", "INPUT_METHOD",
            "XDG_CURRENT_DESKTOP", "XDG_SESSION_TYPE", "XDG_SESSION_DESKTOP",
            "DESKTOP_SESSION", "LANG", "LC_ALL",
        )
        assertTrue(
            SysOutputSanitizer.ALLOWED_KEYS.isNotEmpty() && SysOutputSanitizer.ALLOWED_KEYS.all { it in original },
            "恒明文清单只许收缩（扩条目 = 扩明文面，须走提案评审）：实际 ${SysOutputSanitizer.ALLOWED_KEYS}",
        )
    }

    // ── 多行与守恒 ──

    @Test
    fun `多行混合输出逐行处理且行数换行守恒`() {
        val input = buildString {
            appendLine("[Groups/0]")
            appendLine("Name=\"分组 2\"")
            appendLine("DefaultIM=rime")
            appendLine("API_KEY=secret-value")
            appendLine("XMODIFIERS=@im=fcitx")
        }
        val sanitized = SysOutputSanitizer.sanitize(input)
        assertTrue("API_KEY=***" in sanitized, "secret 行须遮蔽")
        assertFalse("secret-value" in sanitized, "遮蔽后不得残留原值")
        assertTrue("XMODIFIERS=@im=fcitx" in sanitized, "IM 变量保持明文")
        assertTrue("DefaultIM=rime" in sanitized, "普通配置行保持明文")
        assertEquals(input.lineSequence().count(), sanitized.lineSequence().count(), "行数守恒（脱敏不截断）")
        assertTrue(sanitized.endsWith("\n"), "尾换行守恒（截断归工具层 capAt，脱敏不代行）")
    }

    @Test
    fun `真实 printenv 形态整段脱敏后诊断值保留`() {
        val input = """
            GTK_IM_MODULE=fcitx
            QT_IM_MODULE=fcitx
            XMODIFIERS=@im=fcitx
            SHELL_TOKEN=dummy-secret
        """.trimIndent()
        val sanitized = SysOutputSanitizer.sanitize(input)
        assertTrue("GTK_IM_MODULE=fcitx" in sanitized && "XMODIFIERS=@im=fcitx" in sanitized)
        assertTrue("SHELL_TOKEN=***" in sanitized)
        assertFalse("dummy-secret" in sanitized)
    }

    // ── 性能守护（线性扫描回归） ──

    /**
     * 🔴 病理输入不得触发回溯爆炸：脱敏跑在 sys.* 全量捕获输出上（上限 2M 字符），
     * 超长无空白连续段（base64 blob / minified JSON / 无换行堆栈）曾令等价正则
     * O(n²) 回溯——32K 字符 7.5 秒、2M 字符超两分钟不返回（可挂死应用）。
     * 本断言取从严阈值：2M 字符须在 2 秒内完成（现行实现实测 ~50ms，留 40 倍余量）。
     */
    @Test
    fun `超长无空白单行线性完成不回溯`() {
        val twoMegabytes = "x".repeat(2_000_000)
        val startedAt = System.nanoTime()
        SysOutputSanitizer.sanitize(twoMegabytes)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(
            elapsedMs < 2_000,
            "2M 字符单行须线性完成（无回溯爆炸）；实测 ${elapsedMs}ms，超 2s 上限",
        )
    }

    /**
     * 语义等价锚（对照旧正则行为）：同一行内多个赋值只看首个非重叠匹配推进；
     * 数字起始段不构成键名（`123abc=1` 原样）；键名与 `=` 间空白被收拢。
     */
    @Test
    fun `行中赋值扫描与正则语义等价`() {
        // 值段遇 `,` 截止 ⇒ `token=aaa,bb` 只遮前段
        assertEquals(
            "msg token=***,bb tail",
            SysOutputSanitizer.sanitize("msg token=aaa,bb tail"),
        )
        // 数字起始不构成键名（正则键名须以字母/下划线起始）
        assertEquals("abc 123abc=1 xyz", SysOutputSanitizer.sanitize("abc 123abc=1 xyz"))
        // 键名与等号间空白被收拢为 `KEY=`
        assertEquals("app token=***", SysOutputSanitizer.sanitize("app token   =   secret"))
        // 同行多个匹配逐个推进
        assertEquals(
            "app api_key=*** db_password=***",
            SysOutputSanitizer.sanitize("app api_key=k1 db_password=p1"),
        )
        // 引号段整体遮蔽（含内部空白）
        assertEquals(
            "app token=*** end",
            SysOutputSanitizer.sanitize("""app token="a b c" end"""),
        )
    }
}
