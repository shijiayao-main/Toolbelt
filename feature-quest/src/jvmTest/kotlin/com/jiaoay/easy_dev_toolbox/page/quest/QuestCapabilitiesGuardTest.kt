package com.jiaoay.easy_dev_toolbox.page.quest

import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.LogStatsDiffTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 能力目录守护（Quest 域 change 3——openspec add-flow-capabilities 任务 1.3）。
 *
 * 钉死「目录名集与 AgentToolCatalog 注册面不漂移」（plan G 节风险 3 落地配方）：
 * - 每个注册项 toolNames ∈ catalog 可解析面（「列了没注册」零容忍——isUserAsked
 *   工具先例：真名 knowledge.read 而非 read_doc，prompt 内名集失真先例已发生）；
 * - id 唯一 + promptSection 非空 + 首版两真实能力存在。
 *
 * 落 feature-quest jvmTest（纯 :core 引用，零 fixture 零 LLM 触达）。
 */
class QuestCapabilitiesGuardTest {

    @Test
    fun `every ability toolNames resolve in AgentToolCatalog`() {
        val registered = AgentToolCatalog.all.map { it.name }.toSet()
        val unknown = QuestCapabilities.all
            .flatMap { it.toolNames }
            .filter { it !in registered }
        assertTrue(unknown.isEmpty(), "「列了没注册」零容忍——未知工具名：$unknown")
    }

    @Test
    fun `ability ids are unique`() {
        val ids = QuestCapabilities.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "能力 id 必须唯一（flows.capabilities 持久化键）")
    }

    @Test
    fun `every ability has non-blank prompt section`() {
        QuestCapabilities.all.forEach { ability ->
            assertTrue(ability.promptSection.isNotBlank(), "「${ability.id}」promptSection 不得为空")
        }
    }

    @Test
    fun `first release registers log analysis and voice minutes`() {
        val ids = QuestCapabilities.all.map { it.id }
        assertTrue("log-analysis" in ids, "首版能力面：日志分析")
        assertTrue("voice-minutes" in ids, "首版能力面：语音纪要")
    }

    @Test
    fun `log analysis toolset matches workbench value source`() {
        // 对照锚：fs.extended 七件 + knowledge 四件 + memory 四件 + 侦察四件 + 时间过滤两件 + analyze_log
        val logAbility = QuestCapabilities.all.first { it.id == "log-analysis" }
        assertEquals(22, logAbility.toolNames.size, "日志分析名集 = 7+4+4+4+2+1 = 22 件")
        assertTrue("log.analyze_log" in logAbility.toolNames)
        assertTrue("fs.extended.read_file" in logAbility.toolNames)
        assertTrue("knowledge.read" in logAbility.toolNames, "真名 knowledge.read（非 read_doc）")
        assertTrue(
            "knowledge.search_cases" in logAbility.promptSection,
            "promptSection 必须引导 knowledge.search_cases（历史案例检索——名集与 prompt 引导面同批）",
        )
        assertTrue("memory.view" in logAbility.toolNames)
        assertTrue("log.filter_by_time" in logAbility.toolNames, "时间过滤对称窗口件须在名集")
        assertTrue("log.filter_by_range" in logAbility.toolNames, "时间区间截取件须在名集（与对称窗口件成对）")
    }

    @Test
    fun `log analysis assembles and guides scout tools`() {
        val logAbility = QuestCapabilities.all.first { it.id == "log-analysis" }
        assertTrue(
            LogStatsDiffTools.NAMES.all { it in logAbility.toolNames },
            "侦察四件必须并入 LOG_ANALYSIS 名集（LogStatsDiffTools.NAMES 单源锚——不装则 Quest 链 AI 用不上「先侦察后下钻」）",
        )
        assertTrue(
            LogStatsDiffTools.NAMES.all { it in logAbility.promptSection },
            "promptSection 必须引导侦察四件（不引导 = 装而不用），引导口径须与工具描述符语义一致",
        )
        // 时间过滤两件：名集与 prompt 引导面同批（装而不用零容忍——与侦察四件同款守护口径）
        listOf("log.filter_by_time", "log.filter_by_range").forEach { name ->
            assertTrue(name in logAbility.promptSection, "promptSection 必须引导 $name（名集与 prompt 引导面同批）")
        }
    }
}
