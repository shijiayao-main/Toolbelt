// 2026-09-18 对抗验证批：send_file 工作区沙箱放宽后的敏感文件防护表。
//
// 背景：放宽后 send_file 的可发送面 = 允许集 ∪ 工作区沙箱，而八件读类工具仍走严格允许集
// ⇒ 出现「读不了却能整包外发」的不对称。攻击链：引用文件里的恶意文本 → 间接 prompt injection
// → 诱导 LLM 发走工作区内的敏感文件。本测试钉死排除表的判据。
package com.jiaoay.lark.bridge.exec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveFilePolicyTest {

    private val dataRoot = File(System.getProperty("java.io.tmpdir"), "bridge-data-root-test")

    /** 构造一个位于数据根**之外**（tmp 根下的 ws/ 目录）的文件路径。 */
    private fun outside(vararg segments: String): File =
        segments.fold(dataRoot.parentFile!!) { acc, s -> File(acc, s) }

    @Test
    fun `桥接数据根内的文件一律禁止外发`() {
        // 配置文档（明文 appSecret）就在数据根下——工作区配成家目录时的头号泄漏目标
        assertTrue(
            SensitiveFilePolicy.isForbidden(File(dataRoot, "bridge-config.json"), dataRoot),
            "桥接配置文档必须被拒",
        )
        assertTrue(
            SensitiveFilePolicy.isForbidden(File(dataRoot, "audit.jsonl"), dataRoot),
            "数据根内任意文件都拒（配置/审计/统计全在此）",
        )
        assertTrue(
            SensitiveFilePolicy.isForbidden(File(File(dataRoot, "nested"), "deep.txt"), dataRoot),
            "数据根的子目录内也拒",
        )
        // 数据根本身
        assertTrue(SensitiveFilePolicy.isForbidden(dataRoot, dataRoot), "数据根自身也拒")
    }

    @Test
    fun `数据根之外的普通文件放行（根判据不误杀外侧）`() {
        // 🔴 判别性关键：被测文件必须**不在**任何凭据族名单里——否则判据 2 会兜住它，
        //    断言恒真、判据 1 整段删掉也照样绿（首版正是这么写错的，被对抗验证抓出）。
        assertFalse(
            SensitiveFilePolicy.isForbidden(outside("ws", "report.md"), dataRoot),
            "数据根之外的普通文件不得被根判据误杀",
        )
        assertFalse(
            SensitiveFilePolicy.isForbidden(outside("ws", "sub", "analysis.txt"), dataRoot),
            "数据根之外（深层）的普通文件也不得误杀",
        )
    }

    @Test
    fun `数据根外侧的凭据族文件仍被拒（判据 2 独立生效）`() {
        assertTrue(
            SensitiveFilePolicy.isForbidden(outside("ws", "id_rsa"), dataRoot),
            "凭据族判据独立于数据根判据",
        )
    }

    @Test
    fun `根判据用 canonical 比较-路径归一化不绕过`() {
        // 带 ./ 的等价路径（纯文本归一化）仍应命中数据根判据
        val viaDot = File(dataRoot.parentFile, "./bridge-data-root-test/audit.jsonl")
        assertTrue(
            SensitiveFilePolicy.isForbidden(viaDot, dataRoot),
            "带 ./ 的等价路径不得绕过数据根判据",
        )
    }

    @Test
    fun `符号链接指向数据根内也不绕过（canonicalPath 解析真实目标）`() {
        // 🔴 这是 canonicalPath 存在的全部理由：软链绕过。无符号链接权限的平台跳过。
        val tmp = System.getProperty("java.io.tmpdir")
        val realRoot = File(tmp, "sensitive-policy-real-root-${System.nanoTime()}").apply { mkdirs() }
        val target = File(realRoot, "bridge-config.json").apply { writeText("{}") }
        val linkParent = File(tmp, "sensitive-policy-link-${System.nanoTime()}").apply { mkdirs() }
        val link = File(linkParent, "innocent-name.txt")
        val linked = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        if (!linked) return // 平台无软链权限（如 Windows 非管理员）——跳过，不误报
        try {
            assertTrue(
                SensitiveFilePolicy.isForbidden(link, realRoot),
                "软链指向数据根内的配置文档必须被拒（canonicalPath 已解析真实目标）",
            )
        } finally {
            runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
            runCatching { target.delete() }
            runCatching { realRoot.delete() }
            runCatching { linkParent.delete() }
        }
    }

    @Test
    fun `凭据族文件名被拒`() {
        val names = listOf(
            ".env", ".env.local", ".env.production",
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
            "credentials", "secrets", "secret", "passwd", "shadow",
            "server.pem", "private.key", "cert.p12", "store.jks", "app.keystore",
            // 桥接 sidecar 的**精确名**（DownloadStore.META_FILE_NAME）
            ".meta.json",
        )
        names.forEach { name ->
            assertTrue(
                SensitiveFilePolicy.isForbidden(outside("ws", name), dataRoot),
                "$name 应被拒（凭据/密钥/元数据族）",
            )
        }
    }

    @Test
    fun `凭据名的常见变体被拒（stem 语义，对抗验证补）`() {
        // 等值比对会放行下列**真实凭据载体**（AWS/k8s 惯例 + 备份变体）——实测曾全部 ALLOW。
        val variants = listOf(
            "id_rsa.bak", "id_rsa_old", "id_rsa.pem",
            "credentials.json", "credentials.yaml", "credentials.txt",
            "secrets.yaml", "secret.yaml", "secrets.json",
            "aws-credentials", "service-account.json",
            "app.key.bak", "report.md.id_rsa",
        )
        variants.forEach { name ->
            assertTrue(
                SensitiveFilePolicy.isForbidden(outside("ws", name), dataRoot),
                "$name 应被拒（stem/多段语义）",
            )
        }
    }

    @Test
    fun `符号链接绕过凭据族判据（canonical 侧比对）`() {
        // 🔴 判据 2 必须同时看 canonical 名：CLI 子进程可 ln -s ~/.ssh/id_rsa report.md，
        //    只看 raw name 会让私钥以「report.md」的名义出站（对抗验证实测 ALLOW）。
        val tmp = System.getProperty("java.io.tmpdir")
        val dir = File(tmp, "sensitive-policy-symlink-${System.nanoTime()}").apply { mkdirs() }
        val realKey = File(dir, "id_rsa").apply { writeText("-----BEGIN OPENSSH PRIVATE KEY-----") }
        val disguised = File(dir, "harmless-report.md")
        val linked = runCatching { java.nio.file.Files.createSymbolicLink(disguised.toPath(), realKey.toPath()) }.isSuccess
        if (!linked) return // 平台无软链权限——跳过，不误报
        try {
            assertTrue(
                SensitiveFilePolicy.isForbidden(disguised, dataRoot),
                "软链伪装名不得绕过凭据族判据（canonical 名是 id_rsa）",
            )
        } finally {
            runCatching { java.nio.file.Files.deleteIfExists(disguised.toPath()) }
            runCatching { realKey.delete() }
            runCatching { dir.delete() }
        }
    }

    @Test
    fun `宿主应用数据根内的文件被拒（datastore 目录存明文 LLM Key）`() {
        // 🔴 判据 1 的根是**宿主应用数据根**（`~/.toolbelt`），不是 `lark/` 子目录：
        //    `.datastore/` 与其同级，存放明文 LLM API Key 与 xlog 私钥。
        //    只防 `lark/` 会在同级目录留缺口（对抗验证实测）。
        val appData = File(System.getProperty("java.io.tmpdir"), "sensitive-policy-appdata-${System.nanoTime()}")
        val datastore = File(appData, ".datastore").apply { mkdirs() }
        val prefs = File(datastore, "toolbelt.preferences_pb").apply { writeText("binary") }
        val larkCfg = File(File(appData, "lark").apply { mkdirs() }, "bridge-config.json").apply { writeText("{}") }
        try {
            assertTrue(
                SensitiveFilePolicy.isForbidden(prefs, appData),
                "DataStore 文件（明文 LLM API Key）必须被拒——这是判据 1 取应用数据根的全部理由",
            )
            assertTrue(
                SensitiveFilePolicy.isForbidden(larkCfg, appData),
                "桥接配置文档同样在应用数据根内",
            )
            // 判别性：换成 lark 子目录作根时，prefs 落在根外 ⇒ 该用例证明「根取哪一级」真的起作用
            assertFalse(
                SensitiveFilePolicy.isForbidden(prefs, File(appData, "lark")),
                "根取 lark/ 子目录时 DataStore 在根外——正是要修的缺口",
            )
        } finally {
            appData.deleteRecursively()
        }
    }

    @Test
    fun `公钥 pub 显式放行（不是机密，误拒会挡住正常交付）`() {
        listOf("id_rsa.pub", "id_ed25519.pub", "server.pem.pub", "deploy_key.pub").forEach { name ->
            assertFalse(
                SensitiveFilePolicy.isForbidden(outside("ws", name), dataRoot),
                "$name 是公钥，应放行",
            )
        }
    }

    @Test
    fun `meta 判据收窄为精确名（不误杀 report-meta 这类用户产物）`() {
        // 本仓 sidecar 精确名是 `.meta.json`；endsWith 写法会误杀常见用户产物（对抗验证抓出）。
        assertTrue(
            SensitiveFilePolicy.isForbidden(outside("ws", ".meta.json"), dataRoot),
            "精确名 .meta.json 应拒",
        )
        listOf("report.meta.json", "dataset.meta.json", "export.meta.json").forEach { name ->
            assertFalse(
                SensitiveFilePolicy.isForbidden(outside("ws", name), dataRoot),
                "$name 是常见用户产物，不应被归为桥接 sidecar 误杀",
            )
        }
    }

    @Test
    fun `sys 只读诊断审计文件禁止外发（判据一整树 + 名形前缀双拒）`() {
        // sys.* 只读诊断域审计（`~/.toolbelt/audit/sys-audit-*.jsonl`）：
        // 审计外发 = 擦痕迹式回读（群内即可确认哪些操作未被追踪）——判据一整树拒 +
        // 名形前缀兜底（宿主根未知时也不放行）。
        val appData = File(System.getProperty("java.io.tmpdir"), "sensitive-policy-audit-${System.nanoTime()}")
        val auditDir = File(appData, "audit").apply { mkdirs() }
        val auditFile = File(auditDir, "sys-audit-2026-10-14.jsonl").apply { writeText("{}") }
        try {
            assertTrue(
                SensitiveFilePolicy.isForbidden(auditFile, appData),
                "应用数据根内的审计文件必须被拒（判据一：宿主机密根整树）",
            )
            assertTrue(
                SensitiveFilePolicy.isForbidden(auditFile, null),
                "宿主根未知时审计文件仍须被拒（判据二名形：sys-audit 前缀族）",
            )
            assertTrue(
                SensitiveFilePolicy.isForbidden(File(auditDir, "sys-audit.jsonl"), null),
                "sys-audit 精确名同样被拒（前缀族等值形态）",
            )
        } finally {
            appData.deleteRecursively()
        }
    }

    @Test
    fun `数据根为 null 时仅凭据族判据生效`() {
        assertFalse(
            SensitiveFilePolicy.isForbidden(outside("ws", "report.md"), null),
            "根未知时普通文件仍放行",
        )
        assertTrue(
            SensitiveFilePolicy.isForbidden(outside("ws", "id_rsa"), null),
            "根未知时凭据族仍拒",
        )
    }

    @Test
    fun `普通产物文件放行（不误杀）`() {
        val names = listOf(
            "report.md", "analysis.txt", "crash.log", "data.csv",
            "summary.md", "result.json", "archive.zip", "screenshot.png",
            // 边界：不以 .env. 开头的相似名、非私钥扩展名
            "environment.md", "keyboard.log", "monkey.txt", "env.txt",
        )
        names.forEach { name ->
            assertFalse(
                SensitiveFilePolicy.isForbidden(outside("ws", name), dataRoot),
                "$name 不应被拒（正常产物）",
            )
        }
    }

    @Test
    fun `无扩展名的裸名凭据文件也被拒`() {
        // 实现侧缺口：无点文件名走 substringAfterLast('.') 得到 ""，被 ext.isNotEmpty() 短路
        // ⇒ 裸名 "key"/"privkey" 漏网。故裸名必须在精确名表里显式列出。
        listOf("key", "privkey", "privatekey", "private_key", "private-key").forEach { name ->
            assertTrue(
                SensitiveFilePolicy.isForbidden(outside("ws", name), dataRoot),
                "裸名私钥 $name 应被拒（无扩展名不得成为缺口）",
            )
        }
    }

    @Test
    fun `含 key 子串的正常名不误杀（精确名比较）`() {
        listOf("monkey", "turkey", "keynote.md", "keyboard.log").forEach { name ->
            assertFalse(
                SensitiveFilePolicy.isForbidden(outside("ws", name), dataRoot),
                "$name 不应被拒（精确名比较，不做子串匹配）",
            )
        }
    }
}
