package com.jiaoay.easy_dev_toolbox.page.mcp

import com.jiaoay.easy_dev_toolbox.core.llm.WorkbenchPrompts
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MCP 研判名集一致性守护（真门禁）。
 *
 * MCP run 与桌面工作台链共用 [WorkbenchPrompts.ANALYZE_PROMPT]，prompt「可用工具」
 * 节引导的工具必须全部真实装配进 run 的 registry——名集落后于 prompt 会令外部
 * client 的研判 agent 按 prompt 引导调用不存在的工具。本守护钉两个面：
 * - 名集精确形态（工作台链同配方去 memory 四件）；
 * - prompt-名集一致性（prompt 增工具行而名集未跟上时红——防再漂）。
 *
 * 名集为何自持而非引用工作台链真源：feature 互不依赖铁律下
 * WorkbenchSession.WORKBENCH_NAMES 跨 feature 不可见（:core 的 EXTENDED_NAMES/
 * PIPELINE_NAMES 是 private 同样不可见）；可单源引用的 :core 真源
 * （KNOWLEDGE_NAMES / LogStatsDiffTools.NAMES）直接引用，其余按仓内值同源自持
 * 纪律镜像，由本守护钉死与 prompt 一致。
 */
class McpAgentToolNamesGuardTest {

    /** 名集精确 19 件 = 取证七件 + knowledge 四件 + 时间过滤两件 + 侦察四件 + 网络查询一件 + 管线一件 */
    @Test
    fun `analyze names are workbench recipe minus memory`() {
        assertEquals(
            setOf(
                // fs.extended 七件（取证）
                "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
                "fs.extended.hash_file", "fs.extended.hex_dump", "fs.extended.strings",
                "fs.extended.convert_timestamp",
                // knowledge 四件（历史案例检索 + 知识库三件）
                "knowledge.search_cases", "knowledge.search", "knowledge.read", "knowledge.lookup_tag",
                // log 时间过滤两件（对称窗口 / 任意区间）
                "log.filter_by_time", "log.filter_by_range",
                // 侦察四件
                "log.stats", "log.diff", "log.view", "log.cluster",
                // 网络查询一件（白名单域名网页拉取——公开文档查询，外放无私面）
                "web.fetch",
                // 确定性管线
                "log.analyze_log",
            ),
            McpAgentTools.WORKBENCH_NAMES,
            "研判名集 = 工作台链 23 件 − memory 四件（个人记忆不进外部 client）",
        )
    }

    /** 「列了没注册」零容忍——名集内每个名字须能被 AgentToolCatalog 解析 */
    @Test
    fun `every analyze name resolves in AgentToolCatalog`() {
        val registered = AgentToolCatalog.all.map { it.name }.toSet()
        val unknown = McpAgentTools.WORKBENCH_NAMES.filter { it !in registered }
        assertTrue(unknown.isEmpty(), "名集内存在目录未注册的工具名：$unknown")
    }

    /**
     * prompt-名集一致性（防再漂）：「可用工具」节列出的点分工具名必须全部 ∈ 研判名集。
     * 豁免面仅两个，均为刻意不外放：memory.*（个人记忆不进外部 client——registry
     * 装配不传 memoryDir，列了也恒不注册）与 write_todos（桌面会话件，非目录工具）。
     */
    @Test
    fun `prompt tool section is covered by analyze names`() {
        val section = analyzePromptToolSection()

        val dotted = Regex("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")
            .findAll(section).map { it.value }.toSet()
        val uncovered = dotted.filter { it !in McpAgentTools.WORKBENCH_NAMES }
        assertTrue(uncovered.isEmpty(), "prompt 引导但研判名集未装配的工具：$uncovered")

        val familyShorthands = Regex("[a-z][a-z0-9_]*\\.\\*")
            .findAll(section).map { it.value }.toSet()
        assertEquals(setOf("memory.*"), familyShorthands, "家族缩写仅允许 memory.*（不外放的既定豁免面）")
        assertTrue("write_todos" in section, "write_todos 行形态变更时须同步核对本豁免清单")
    }

    /** 恒不外放面：memory / distill / write_todos / user.ask_user 不进研判名集 */
    @Test
    fun `memory distill todos and ask user never leak into analyze names`() {
        val names = McpAgentTools.WORKBENCH_NAMES
        assertTrue(names.none { it.startsWith("memory.") }, "🔴 memory.* 不外放（个人记忆不进外部 client）")
        assertTrue(names.none { it.startsWith("distill.") }, "🔴 distill.* 恒不进研判链")
        assertTrue("write_todos" !in names)
        assertTrue("user.ask_user" !in names)
    }

    /** 取「## 可用工具」节（到下一个 ## 标题止）——prompt 结构变更时本提取须同步 */
    private fun analyzePromptToolSection(): String {
        val prompt = WorkbenchPrompts.ANALYZE_PROMPT
        val start = prompt.indexOf("## 可用工具")
        assertTrue(start >= 0, "ANALYZE_PROMPT 须保留「## 可用工具」节")
        val rest = prompt.substring(start)
        val end = rest.indexOf("\n## ")
        return if (end < 0) rest else rest.substring(0, end)
    }
}
