package com.jiaoay.easy_dev_toolbox.page.logworkbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 外发内容脱敏器测试：
 * 1. 五类值模式命中/不命中矩阵（VIN 边界防吞子串 / IMEI 与 13 位毫秒时间戳按
 *    长度区分 / MAC 大小写两态 / IPv4 段值上界 / 用户名路径三形态）；
 * 2. 同值恒同替（同实例内同值复用同一编号令牌，跨行亦然）；
 * 3. 行数与换行守恒（逐行变换不增删行——产物行号/引用锚不漂移）；
 * 4. 判据集棘轮：[ReportAnonymizer.VALUE_PATTERNS] 与测试白名单恒等——
 *    静默扩判据（新增类别/改正则/调次序）即红。
 */
class ReportAnonymizerTest {

    // ── VIN ──

    @Test
    fun `VIN 十七位命中且边界判据防吞更长或更短字母数字串的子串`() {
        val anonymizer = ReportAnonymizer()
        assertEquals(
            "vin=VIN_1",
            anonymizer.anonymize("vin=LHGARV8897K123456"),
            "17 位 VIN 字符集命中（首遇分配 VIN_1）",
        )
        val guard = ReportAnonymizer()
        listOf(
            "XLHGARV8897K123456", // 前邻字母数字
            "LHGARV8897K123456X", // 后邻字母数字
            "LHGARV8897K12345", // 16 位
            "LHGARV8897K1234567", // 18 位
        ).forEach { line ->
            assertEquals(line, guard.anonymize(line), "边界判据不得吞子串：$line")
        }
    }

    // ── IMEI ──

    @Test
    fun `IMEI 十五位命中且十三位毫秒时间戳不误伤`() {
        val anonymizer = ReportAnonymizer()
        assertEquals(
            "imei=IMEI_1",
            anonymizer.anonymize("imei=861503123456789"),
            "15 位数字命中（首遇分配 IMEI_1）",
        )
        val guard = ReportAnonymizer()
        listOf(
            "ts=1698240000000", // 13 位毫秒时间戳（日志时间轴高频形态）
            "ts=16982400000000", // 14 位
            "ts=1698240000000000", // 16 位
        ).forEach { line ->
            assertEquals(line, guard.anonymize(line), "非 15 位数字串不得误伤（长度天然区分时间戳）：$line")
        }
    }

    // ── MAC ──

    @Test
    fun `MAC 十六进制对命中且大小写两态同判`() {
        val anonymizer = ReportAnonymizer()
        val out = anonymizer.anonymize(
            "mac=AA:BB:CC:DD:EE:FF mac=aa:bb:cc:dd:ee:ff mac=Aa:Bb:01:99:0f:Fe",
        )
        assertTrue("MAC_1" in out && "MAC_2" in out && "MAC_3" in out, "三值各分配独立编号")
        listOf("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff", "Aa:Bb:01:99:0f:Fe").forEach { value ->
            assertFalse(value in out, "大小写两态同判（原值零残留）：$value")
        }
        val guard = ReportAnonymizer()
        listOf(
            "AA:BB:CC:DD:EE", // 五组不足
            "AA:BB:CC:DD:EE:GG", // 尾组非十六进制
        ).forEach { line ->
            assertEquals(line, guard.anonymize(line), "非六组十六进制对形态不得误伤：$line")
        }
    }

    // ── IPv4 ──

    @Test
    fun `IPv4 四段点分命中含 loopback 且段值上界与多段串不误伤`() {
        val anonymizer = ReportAnonymizer()
        val out = anonymizer.anonymize("server=192.168.1.100 db=127.0.0.1 gw=10.0.0.254")
        assertTrue("IP_1" in out && "IP_2" in out && "IP_3" in out, "三值各分配独立编号")
        listOf("192.168.1.100", "127.0.0.1", "10.0.0.254").forEach { value ->
            assertFalse(value in out, "loopback/内网段同替（同值恒同替不是段白名单）：$value")
        }
        val guard = ReportAnonymizer()
        listOf(
            "version=5.15.0.100.5", // 五段点分（版本号形态）
            "bad=999.1.1.1", // 段值越 255 上界
            "port=1.2.3", // 三段不足
        ).forEach { line ->
            assertEquals(line, guard.anonymize(line), "非四段或越上界的点分串不得误伤：$line")
        }
    }

    // ── 含用户名的绝对路径 ──

    @Test
    fun `用户名路径三形态命中且仅替换用户名段`() {
        val anonymizer = ReportAnonymizer()
        val out = anonymizer.anonymize(
            "/home/alice/logs/app.log\n/Users/bob/Desktop/x.log\nC:\\Users\\carol\\Desktop\\x.log",
        )
        assertEquals(
            "/home/USER_1/logs/app.log\n/Users/USER_2/Desktop/x.log\nC:\\Users\\USER_3\\Desktop\\x.log",
            out,
            "三形态命中且路径根/子路径保留（仅替换用户名段）",
        )
        val guard = ReportAnonymizer()
        assertEquals(
            "/var/log/app.log",
            guard.anonymize("/var/log/app.log"),
            "非用户名根路径原样",
        )
    }

    // ── 同值恒同替 ──

    @Test
    fun `同值恒同替且跨行复用同一编号令牌`() {
        val anonymizer = ReportAnonymizer()
        val out = anonymizer.anonymize(
            """
            vin1=LHGARV8897K123456
            again: LHGARV8897K123456 vin2=WBXYZ1234X5M67890
            third LHGARV8897K123456
            """.trimIndent(),
        )
        assertEquals(3, out.split("VIN_1").size - 1, "同值跨行恒复用 VIN_1")
        assertEquals(1, out.split("VIN_2").size - 1, "第二原值分配 VIN_2（首遇次序编号）")
    }

    // ── 守恒 ──

    @Test
    fun `行数与换行守恒`() {
        val anonymizer = ReportAnonymizer()
        val input = "/home/alice/a.log\nvin=LHGARV8897K123456\nplain line\n"
        val out = anonymizer.anonymize(input)
        assertEquals(
            input.lineSequence().count(),
            out.lineSequence().count(),
            "行数守恒（不增删行——产物行号/引用锚不漂移）",
        )
        assertTrue(out.endsWith("\n"), "尾换行守恒")
        assertEquals("plain line", out.lineSequence().elementAt(2), "未命中行逐字原样（行位置不漂移）")
    }

    // ── 导出产物形混排 ──

    @Test
    fun `导出产物形全文混排逐类替换且原值零残留`() {
        val anonymizer = ReportAnonymizer()
        val exported = buildString {
            appendLine("- 分析文件：")
            appendLine("  - /home/dave/camera-sample.alog.txt")
            appendLine("## 报告全文")
            appendLine("VIN=LHGARV8897K123456 IMEI=861503123456789")
            appendLine("MAC=AA:BB:CC:DD:EE:FF IP=192.168.1.100 ts=1698240000000")
        }
        val out = anonymizer.anonymize(exported)
        assertTrue("  - /home/USER_1/camera-sample.alog.txt" in out, "元信息分析文件路径的户名段替换")
        assertTrue("VIN=VIN_1" in out && "IMEI=IMEI_1" in out, "报告正文命中替换")
        assertTrue("MAC=MAC_1" in out && "IP=IP_1" in out, "证据行形态命中替换")
        assertTrue("ts=1698240000000" in out, "13 位毫秒时间戳逐字保留（统计结构不破坏）")
        listOf("dave", "LHGARV8897K123456", "861503123456789", "AA:BB:CC:DD:EE:FF", "192.168.1.100")
            .forEach { value -> assertFalse(value in out, "原值零残留：$value") }
    }

    // ── 判据集棘轮（静默扩判据即红） ──

    @Test
    fun `判据集与测试白名单恒等`() {
        // 比对面 = (tokenPrefix, 正则 pattern 串) 对（kotlin Regex 无 equals 重写，
        // 直接比 data class 全等是恒假——比 pattern 串即全信息：判据集全部走
        // 单参构造，options 恒空集）
        val whitelist = listOf(
            "VIN" to """(?<![A-Za-z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Za-z0-9])""",
            "IMEI" to """(?<![0-9])\d{15}(?![0-9])""",
            "MAC" to """(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])""",
            "IP" to """(?<![0-9.])(?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)){3}(?![0-9.])""",
            "USER" to """(?<=(?:/home/|/Users/))[A-Za-z0-9][A-Za-z0-9._-]*(?=/)|(?<=C:\\Users\\)[A-Za-z0-9][A-Za-z0-9._-]*(?=\\)""",
        )
        assertEquals(
            whitelist,
            ReportAnonymizer.VALUE_PATTERNS.map { it.tokenPrefix to it.pattern.pattern },
            "类别集/正则/次序须与测试白名单恒等——扩判据 = 扩遮蔽面与误伤面，须先改本白名单再落生产代码",
        )
    }
}
