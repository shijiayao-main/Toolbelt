// 文本出站面判据行为测试：机密根 / 凭据名 / 普通路径 / 日志样例四类样本的打码与放行边界。
// 判据真源与文件通道同 object 常量零搬运共用；本组测试即文本面命中边界的契约锚。
package com.jiaoay.lark.bridge.exec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SensitiveFilePolicyTextTest {

    private val dataRoot = File(System.getProperty("java.io.tmpdir"), "text-policy-data-root")

    private fun sanitize(text: String, homeDataRoot: File? = dataRoot): String =
        SensitiveFilePolicy.sanitizeOutboundText(text, homeDataRoot)

    /** 子串出现次数（提示「只附一次」类断言用）。 */
    private fun count(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    // ── 机密根：literal 与注入路径串形态 ──

    @Test
    fun `机密根 literal 形态命中打码且提示只附一次`() {
        val out = sanitize("配置在 ~/.easy_dev_toolbox/lark/bridge-config.json 里")
        assertFalse(out.contains("easy_dev_toolbox"), "机密根原文不得残留：$out")
        assertTrue(out.contains(SensitiveFilePolicy.TEXT_REDACTED_PLACEHOLDER), "命中段须被打码占位替换：$out")
        assertEquals(1, count(out, SensitiveFilePolicy.TEXT_REDACTED_HINT), "打码提示整条文本只附一次")
        assertTrue(out.endsWith(SensitiveFilePolicy.TEXT_REDACTED_HINT))
        assertTrue(out.startsWith("配置在") && out.contains("里"), "命中段之外的原文本须保留")
    }

    // ── 机密根：当前根 ~/.toolbelt 与旧根 ~/.easy_dev_toolbox 双形态契约 ──

    @Test
    fun `当前机密根 literal 形态命中打码`() {
        val out = sanitize("配置在 ~/.toolbelt/lark/bridge-config.json 里")
        assertFalse(out.contains("toolbelt/lark"), "当前根原文不得残留：$out")
        assertTrue(out.contains(SensitiveFilePolicy.TEXT_REDACTED_PLACEHOLDER), "命中段须被打码占位替换：$out")
        assertEquals(1, count(out, SensitiveFilePolicy.TEXT_REDACTED_HINT))
    }

    @Test
    fun `旧根形态改名迁移后仍恒打码`() {
        // 旧根 `~/.easy_dev_toolbox` 是改名迁移前落点：历史文本/未迁移机器上仍会出现，
        // 撤掉旧形态枚举即漏杀——本用例钉死双形态契约。
        val out = sanitize("旧配置在 ~/.easy_dev_toolbox/lark/bridge-config.json 里")
        assertFalse(out.contains("easy_dev_toolbox"), "旧根形态同样不得残留：$out")
        assertTrue(out.contains(SensitiveFilePolicy.TEXT_REDACTED_PLACEHOLDER))
    }

    @Test
    fun `当前根整串形态整段拒发`() {
        assertEquals(SensitiveFilePolicy.TEXT_REJECTED_NOTICE, sanitize("~/.toolbelt"))
        assertEquals(SensitiveFilePolicy.TEXT_REJECTED_NOTICE, sanitize("~/.toolbelt/lark/bridge-config.json"))
    }

    @Test
    fun `当前根分隔符边界对齐防前缀误杀`() {
        val samples = listOf(
            "看 ~/.toolbelt_backup 文件",
            "看 ~/.toolbelt2 文件",
        )
        samples.forEach { original ->
            assertSame(original, sanitize(original), "边界对齐外的相似前缀不得误杀：$original")
        }
    }

    @Test
    fun `机密根根 token 单独出现也打码`() {
        val out = sanitize("数据根 ~/.easy_dev_toolbox 不可外传")
        assertFalse(out.contains("easy_dev_toolbox"))
        assertTrue(out.contains(SensitiveFilePolicy.TEXT_REDACTED_PLACEHOLDER))
    }

    @Test
    fun `机密根注入路径串 raw 与 canonical 变体都命中`() {
        listOf(dataRoot.path, dataRoot.absolutePath, dataRoot.canonicalPath).forEach { form ->
            val out = sanitize("数据在 $form/.datastore/preferences_pb")
            assertFalse(out.contains(form), "注入形态不得残留：$out")
            assertFalse(out.contains(".datastore"), "机密根整 token 打码 ⇒ 子路径一并隐藏：$out")
            assertTrue(out.contains(SensitiveFilePolicy.TEXT_REDACTED_PLACEHOLDER))
        }
    }

    @Test
    fun `机密根分隔符边界对齐防前缀误杀`() {
        // 后随 `_backup` / `2` / `-bak` 一类续写不是边界对齐的命中——防 .easy_dev_toolbox_backup 误杀
        val samples = listOf(
            "看 ~/.easy_dev_toolbox_backup 文件",
            "看 ~/.easy_dev_toolbox2 文件",
            "路径 ${dataRoot.path}-bak 不在根内",
            "路径 ${dataRoot.path}x 不在根内",
        )
        samples.forEach { original ->
            assertSame(original, sanitize(original), "边界对齐外的相似前缀不得误杀：$original")
        }
    }

    @Test
    fun `机密根整串形态整段拒发不出原文任何片段`() {
        assertEquals(SensitiveFilePolicy.TEXT_REJECTED_NOTICE, sanitize("~/.easy_dev_toolbox"))
        assertEquals(SensitiveFilePolicy.TEXT_REJECTED_NOTICE, sanitize("  ~/.easy_dev_toolbox/lark/bridge-config.json "))
        assertEquals(SensitiveFilePolicy.TEXT_REJECTED_NOTICE, sanitize(dataRoot.path))
        assertEquals(SensitiveFilePolicy.TEXT_REJECTED_NOTICE, sanitize(dataRoot.path + "/audit/sys-audit.jsonl"))
    }

    @Test
    fun `混合文本不触发整段拒发走打码路径`() {
        // 分界样本：主体含说明文字 ⇒ 不是「整段即机密根路径串」——打码保留其余原文
        val out = sanitize("路径 ~/.easy_dev_toolbox/lark/bridge-config.json 含明文 appSecret")
        assertTrue(out.contains(SensitiveFilePolicy.TEXT_REDACTED_PLACEHOLDER), "混合文本走打码：$out")
        assertFalse(out.contains("easy_dev_toolbox"))
        assertTrue(out.contains("含明文 appSecret"), "非命中段须保留")
    }

    @Test
    fun `homeDataRoot 为 null 时机密根判据跳过凭据名判据仍生效`() {
        // null 语义与文件通道同义：机密根形态（含 literal）整体跳过——生产装配恒注入非 null
        val rootText = "配置在 ~/.easy_dev_toolbox/lark/audit.jsonl 里"
        assertSame(rootText, sanitize(rootText, homeDataRoot = null), "根未知时机密根判据跳过")
        val credText = "私钥在 /home/u/.ssh/id_rsa"
        val out = sanitize(credText, homeDataRoot = null)
        assertFalse(out.contains("id_rsa"), "根未知时凭据名判据仍生效（fail-closed 保留在凭据族）")
    }

    @Test
    fun `多处命中逐段打码`() {
        val out = sanitize("~/.easy_dev_toolbox/a 以及 /home/u/.env 和 ~/.easy_dev_toolbox/b")
        assertEquals(3, count(out, SensitiveFilePolicy.TEXT_REDACTED_PLACEHOLDER), "每个命中段各打码一次：$out")
        assertEquals(1, count(out, SensitiveFilePolicy.TEXT_REDACTED_HINT))
    }

    @Test
    fun `代码块内命中同样打码且围栏保留`() {
        val src = "```\ncat ~/.easy_dev_toolbox/lark/bridge-config.json\n```"
        val out = sanitize(src)
        assertFalse(out.contains("easy_dev_toolbox"))
        assertTrue(out.startsWith("```") && out.contains("\n```"), "打码占位不注入 markdown 语法、围栏结构保留")
    }

    // ── 凭据名：文件名形态四重命中 / 散文豁免 / pub 放行 ──

    @Test
    fun `凭据名文件名形态四重命中全部打码`() {
        val samples = listOf(
            "环境文件 /home/u/project/.env 读取" to ".env",
            "生产配置 .env.production" to ".env.production",
            "私钥 /home/u/.ssh/id_rsa 泄漏" to "id_rsa",
            "备份 id_rsa.bak" to "id_rsa",
            "旧钥 id_rsa_old" to "id_rsa",
            "证书 /etc/certs/server.pem" to "server.pem",
            "密钥 ca.key" to "ca.key",
            "多段 report.md.id_rsa" to "id_rsa",
            "桥接配置 bridge-config.json" to "bridge-config.json",
            "附件元数据 .meta.json" to ".meta.json",
            "审计 sys-audit-run.jsonl" to "sys-audit",
            "凭据 credentials.json 在这" to "credentials",
        )
        samples.forEach { (text, secret) ->
            val out = sanitize(text)
            assertFalse(out.contains(secret), "$secret 不得残留：$out")
            assertTrue(out.contains(SensitiveFilePolicy.TEXT_REDACTED_PLACEHOLDER), "命中段须打码：$out")
        }
    }

    @Test
    fun `凭据名打码只掩文件名段路径前缀保留`() {
        val out = sanitize("读 /home/u/project/.env 文件")
        assertTrue(out.startsWith("读 /home/u/project/"), "目录前缀不敏感须保留：$out")
        assertFalse(out.contains(".env"))
    }

    @Test
    fun `自然语言散文普通词不打码`() {
        val samples = listOf(
            "这个 secret 要保密",
            "the key is rotation",
            "check your credentials first",
            "passwd 字段留空即可",
            "shadow 一词多义",
            "monkey 与 turkey 不是凭据",
        )
        samples.forEach { original ->
            assertSame(original, sanitize(original), "散文普通词不得打码：$original")
        }
    }

    @Test
    fun `公钥 pub 形态放行语义继承`() {
        val samples = listOf(
            "公钥 id_rsa.pub 可外发",
            "证书指纹 server.pem.pub 无机密",
        )
        samples.forEach { original ->
            assertSame(original, sanitize(original), ".pub 公钥放行语义随常量面继承：$original")
        }
    }

    // ── 普通绝对路径 / 日志样例：全量放行（误杀面必须为零）──

    @Test
    fun `普通绝对路径全量放行`() {
        val samples = listOf(
            "报告 /home/u/logs/app.txt 已生成",
            "日志在 /data/a/b/output.log",
            "应用文件 /data/data/com.android.key/files/cache.bin",
            "下载 /home/u/Downloads/report.meta.json 已解压",
            "系统日志 /var/log/system.log",
            "安装包 /sdcard/Android/data/com.example/files/base.apk",
        )
        samples.forEach { original ->
            assertSame(original, sanitize(original), "普通绝对路径零打码（日志分析主业误杀面必须为零）")
        }
    }

    @Test
    fun `日志样例合成样本原样发出`() {
        val logSample = listOf(
            "10-18 14:23:01.234  1234  5678 E CameraService: open failed /data/vendor/camera/config.bin",
            "10-18 14:23:01.235  1234  5678 W AudioFlinger: write /sdcard/Music/track.mp3 latency 12ms",
            "10-18 14:23:02.001  2001  3002 I ActivityTaskManager: Displayed com.example.app/.MainActivity",
        ).joinToString("\n")
        assertSame(logSample, sanitize(logSample), "含普通路径与日志行噪声的日志分析样例零打码")
    }

    @Test
    fun `无命中文本返回原串引用`() {
        val clean = "普通回复，无任何命中"
        assertSame(clean, sanitize(clean))
    }
}
