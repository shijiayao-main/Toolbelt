package com.jiaoay.easy_dev_toolbox.core.data.db

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [KbImportCli] 的 argv 解析与退出码契约（不触库：全部走 `--dry-run` 或用法错误分支）。
 *
 * 契约面：`0` = 成功（含 `--help`）；`1` = 用法错误或解析校验失败；
 * 用法错误只写 stderr（stdout 不写用法以外内容），成功只写 stdout。
 */
class KbImportCliTest {

    private class Captured(val code: Int, val out: String, val err: String)

    private fun invoke(vararg args: String): Captured {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, "UTF-8").use { o ->
            PrintStream(err, true, "UTF-8").use { e -> KbImportCli.run(arrayOf(*args), o, e) }
        }
        return Captured(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    /** 最小合法知识包（与 [KnowledgeMdImporterTest] 同形态：1 repo / 1 文档 / 2 TAG） */
    private fun validKb(): File {
        val root = java.nio.file.Files.createTempDirectory("kb-cli").toFile()
        File(root, "repoA/system").mkdirs()
        File(root, "repoA/system/alpha_issue.md").writeText(
            """
            # Alpha 问题分析 [com.example.alpha]

            > **症状触发词**：alpha 慢
            > **关联文档**：`repoA/system/alpha_issue.md`
            > **蒸馏元数据**：main@abc123 | 入口：AlphaService

            #### Alpha 分组（AlphaService）

            - tag: `AlphaService`

            ### startAlpha 决策树

            - 检查 surface 是否有效
            """.trimIndent(),
        )
        File(root, "TAG_REGISTRY.md").writeText(
            """
            # TAG_REGISTRY

            ## TAG → 文档 映射

            ## repoA

            ### system/

            | 文档 | TAG |
            |------|-----|
            | `repoA/system/alpha_issue.md` | AlphaService, AlphaHelper |
            """.trimIndent(),
        )
        File(root, "LOG_ANALYSE_INDEX.md").writeText(
            """
            # LOG_ANALYSE_INDEX

            ## 症状 → 知识库 快查表

            ## repoA

            | 症状 / 触发关键词 | 主文档 | 常见同时需要 |
            |---|---|---|
            | alpha 慢 | `repoA/system/alpha_issue.md` | — |
            """.trimIndent(),
        )
        return root
    }

    @Test
    fun `help exits zero and prints usage to stdout`() {
        for (flag in listOf("-h", "--help")) {
            val r = invoke(flag)
            assertEquals(0, r.code, "$flag 必须以 0 退出")
            assertTrue("--kb" in r.out, "$flag 用法须列出 --kb：${r.out}")
            assertEquals("", r.err, "$flag 不应写 stderr")
        }
    }

    @Test
    fun `help wins over other arguments regardless of position`() {
        val r = invoke("--kb", "/tmp/does-not-exist", "--help")
        assertEquals(0, r.code, "--help 出现在任何位置都以 0 退出")
        assertTrue("用法" in r.out)
    }

    @Test
    fun `missing required kb exits non-zero with stderr usage`() {
        val r = invoke("--dry-run")
        assertEquals(1, r.code)
        assertEquals("", r.out, "用法错误不写 stdout")
        assertTrue("--kb" in r.err, "stderr 须指出缺 --kb：${r.err}")
    }

    @Test
    fun `unknown argument exits non-zero`() {
        val r = invoke("--kb", "/tmp", "--nope")
        assertEquals(1, r.code)
        assertTrue("未知参数" in r.err, "stderr 须指明未知参数：${r.err}")
    }

    @Test
    fun `option without value exits non-zero`() {
        assertEquals(1, invoke("--kb").code)
        assertEquals(1, invoke("--kb", "/tmp", "--pack-out").code)
    }

    @Test
    fun `option followed by another option is treated as missing value`() {
        // `--kb --dry-run` 是漏值，不能把下一个选项名当路径
        val r = invoke("--kb", "--dry-run")
        assertEquals(1, r.code)
        assertTrue("缺少取值" in r.err, "stderr 须指出漏值：${r.err}")
    }

    @Test
    fun `kb path must exist`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "kb-cli-missing-${System.nanoTime()}")
        val r = invoke("--kb", missing.absolutePath, "--dry-run")
        assertEquals(1, r.code)
        assertTrue("不存在" in r.err, "stderr 须说明目录不存在：${r.err}")
    }

    @Test
    fun `pack out pointing to a directory is rejected`() {
        val r = invoke("--kb", validKb().absolutePath, "--pack-out", System.getProperty("java.io.tmpdir"))
        assertEquals(1, r.code)
        assertTrue("目录" in r.err, "stderr 须说明目标是目录：${r.err}")
    }

    @Test
    fun `dry run on valid kb exits zero and writes pack json`() {
        val kb = validKb()
        val packOut = File(kb, "out/alpha-pack.json")
        val r = invoke("--kb", kb.absolutePath, "--pack-out", packOut.absolutePath, "--dry-run")
        assertEquals(0, r.code, "合法包 dry-run 须成功：${r.err}")
        assertTrue("[dry-run]" in r.out, "stdout 须有 dry-run 标记：${r.out}")
        assertTrue(packOut.isFile, "pack-out 指定的 JSON 须落盘（含父目录自动创建）")
        assertTrue("repoA" in packOut.readText())
    }

    @Test
    fun `equals form is equivalent to space form`() {
        val kb = validKb()
        val r = invoke("--kb=${kb.absolutePath}", "--dry-run")
        assertEquals(0, r.code, "--opt=value 与 --opt value 等价：${r.err}")
    }

    @Test
    fun `validation errors exit non-zero and report count`() {
        // 空目录缺四元数据 → 解析期报错
        val empty = java.nio.file.Files.createTempDirectory("kb-cli-empty").toFile()
        val r = invoke("--kb", empty.absolutePath, "--dry-run")
        assertEquals(1, r.code)
        assertTrue("validation errors" in r.err, "stderr 须有错误计数摘要：${r.err}")
        assertTrue("解析失败" in r.out, "stdout 须有失败说明：${r.out}")
    }
}
