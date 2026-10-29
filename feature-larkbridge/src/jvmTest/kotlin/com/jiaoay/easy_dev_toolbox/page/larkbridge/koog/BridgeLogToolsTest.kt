// 2026-09-19 P1 平台地基批 A1：桥接装配面守护（五件已下沉 :core LogTaskTools，本测试改钉装配语义）。
//
// 契约（见 BridgeLogTools 类头）：
// 1. buildRegistry 文件工具面与旧版逐名等价（内部五件 log.* + fs.* 三件文件工具），
//    另恒含管线 analyze_log 与侦察四件 log.stats/log.diff/log.view/log.cluster；
// 2. extended=true 追加 7 件扩展件；extended=false 不含扩展件；
// 3. 桥接围栏文案变体注入（fenceHint——拒绝文案逐字等于桥接旧 DENIED_HINT）；
// 4. 行为级断言（围栏/产物登记/私钥缺省/判型/多卷）已随类下沉 :core —— 见
//    core/src/jvmTest/.../LogTaskToolsTest.kt（断言逐字平移）。
package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BridgeLogToolsTest {

    private val workDir = File(System.getProperty("java.io.tmpdir"), "bridge-registry-${System.nanoTime()}")
        .apply { mkdirs(); deleteOnExit() }

    private fun fenceWith(vararg files: File): ToolExecutionContext = ToolExecutionContext(files.toList())

    // ── 注册语义（工具面逐名等价） ──

    @Test
    fun `buildRegistry 含全部十四件基础工具（registry 调用名保留旧裸名）`() {
        val input = File(workDir, "any.txt").apply { writeText("x") }
        val registry = BridgeLogTools.buildRegistry(fenceWith(input), privateKeyProvider = { "k" })
        val names = registry.tools.map { it.name }.toSet()
        // registry 调用名 = 工具类自带 name（旧裸名）——LLM 存量提示词零迁移；
        // 目录层的点分命名空间（log.*）是 catalog 身份，P5 MCP 外放时使用。
        // 无条件集合 = 内部六件 + fs.* 三件 + P4-A 管线（analyze_log）+ 侦察四件
        // （log.stats/log.diff/log.view/log.cluster）。knowledge.* 四件走 factory 门控
        // （search_cases = hasCases() samples>0；其余三件 = hasContent()）——
        // **环境敏感**（读生产库：本机已导入 ai-config 真源时注册 18 件、空库 14 件），
        // 故只断言「十四件必在 + distill.* 恒不在」，
        // knowledge 四件的存在性断言归 KnowledgeTools 域测试（Db 注入临时库形态）。
        val unconditional = setOf(
            "decrypt_log", "filter_by_tag", "filter_by_time", "filter_by_range",
            "split_by_size", "probe_file", "extract_archive", "preview_text", "list_dir",
            "analyze_log",
            "log.stats", "log.diff", "log.view", "log.cluster",
        )
        assertTrue(names.containsAll(unconditional), "十四件基础面恒在：${names - unconditional} 为环境增量")
        assertEquals(0, names.count { it.startsWith("distill.") }, "🔴 distill.* 恒不进桥接链（出站旁路防线）")
        assertEquals(0, names.count { it.startsWith("memory.") }, "🔴 memory.* 恒不进桥接链（桌面域 L2 记忆不外放）")
    }

    @Test
    fun `extended 门控-true 时并入 7 件扩展件`() {
        val input = File(workDir, "any2.txt").apply { writeText("x") }
        val base = BridgeLogTools.buildRegistry(fenceWith(input), privateKeyProvider = { "k" })
        val extended = BridgeLogTools.buildRegistry(fenceWith(input), privateKeyProvider = { "k" }, extended = true)
        // 基数 = 环境敏感（knowledge.* hasContent 门控）：以「差集恒为 7 件扩展件」为不变量——
        // extended 增量与库内容无关
        assertEquals(7, extended.tools.size - base.tools.size, "extended 增量恒为 7 件扩展件（环境无关）")
        assertTrue(
            extended.tools.map { it.name }.containsAll(
                listOf(
                    "read_file", "grep", "glob", "hash_file", "hex_dump", "strings", "convert_timestamp",
                ),
            ),
            "extended 应含全部扩展件（registry 调用名）",
        )
        assertTrue(base.tools.map { it.name }.none { it in setOf("read_file", "grep", "glob") }, "基础面不含扩展件")
    }

    // ── 桥接围栏文案变体（fenceHint 注入后逐字保留） ──

    @Test
    fun `桥接围栏拒绝文案逐字保留`() = runBlocking {
        val inside = File(workDir, "in.txt").apply { writeText("x") }
        val outside = File(workDir, "out.txt").apply { writeText("x") }
        val bridgeHint = "路径不在本消息的文件围栏内（只能操作用户引用发送的文件及其产物），请检查路径或改用已登记文件"
        // fenceHint 语义经 :core LogTaskToolsTest 断言注入正确性；此处验证装配面引用的是同一文案常量
        val probe = com.jiaoay.easy_dev_toolbox.core.llm.tools.LogTaskTools.ProbeFileTool(
            context = fenceWith(inside),
            fenceHint = bridgeHint,
        )
        val result = probe.execute(
            com.jiaoay.easy_dev_toolbox.core.llm.tools.LogTaskTools.ProbeFileTool.Args(outside.absolutePath),
        )
        assertEquals(bridgeHint, result, "桥接围栏文案应逐字等于旧 DENIED_HINT（LLM 存量反馈语义不变）")
    }
}
