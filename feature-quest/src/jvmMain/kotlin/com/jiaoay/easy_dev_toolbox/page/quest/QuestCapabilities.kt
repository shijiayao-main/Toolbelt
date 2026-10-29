package com.jiaoay.easy_dev_toolbox.page.quest

import com.jiaoay.easy_dev_toolbox.core.llm.tools.LogStatsDiffTools

/**
 * 流程能力目录（Quest 域 change 2——openspec add-flow-page 任务 2.2 空目录；change 3 接线真身）。
 *
 * ## 形态（硬编码注册表，不做能力市场/插件化）
 * 加能力 = 加一个 [all] 注册项（AgentToolCatalog 同款纪律）。首版 **2 真实 + 1 隐式**：
 * 日志分析 / 语音纪要（change 3 落地）+ 通用问答（隐式——向导全不选 = 零工具零 prompt 段）。
 *
 * @param id 稳定标识（存 flows.capabilities JSON 数组；恢复时未知 id 跳过不崩——
 *        EnumRestore 先例纪律，change 3 守护测试钉死 id ∈ AgentToolCatalog 可解析面）
 * @param title 展示名（新建表单 chip 文案）
 * @param description 展示描述
 * @param toolNames 能力工具名集（注入 QuestSession.extraToolNames）
 * @param promptSection 能力 prompt 段（首轮拼在 prompt 最前；改写自各域 Prompts 协作版）
 */
data class QuestAbility(
    val id: String,
    val title: String,
    val description: String,
    val toolNames: Set<String> = emptySet(),
    val promptSection: String = "",
)

/**
 * 注册面（Quest 域 change 3 落地真身）。
 *
 * toolNames 值同源自持纪律（WORKBENCH_NAMES/BridgeLogTools 先例——:core 的
 *    EXTENDED_NAMES/MEMORY_NAMES 是 private 跨模块不可见；目录名是稳定契约，
 *    守护测试钉死 ∈ AgentToolCatalog 可解析面防漂移）。例外：[LogStatsDiffTools.NAMES]
 *    是 public——侦察四件经该单源引用（与 BridgeLogTools/WorkbenchSession 同款），
 *    不自持拷贝防字面漂移。
 */
object QuestCapabilities {

    /** 取证七件（fs.extended——与工作台 EXTENDED_FILE_NAMES 同值自持） */
    private val EXTENDED_FILE_NAMES = setOf(
        "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
        "fs.extended.hash_file", "fs.extended.hex_dump", "fs.extended.strings",
        "fs.extended.convert_timestamp",
    )

    /** 知识库四件（与 KnowledgeTools.KNOWLEDGE_NAMES 同值自持——read_doc 真名是 knowledge.read） */
    private val KNOWLEDGE_NAMES = setOf(
        "knowledge.search_cases", "knowledge.search", "knowledge.read", "knowledge.lookup_tag",
    )

    /** L2 记忆四件（memoryDir null 时 catalog factory 返回 null 不注册） */
    private val MEMORY_NAMES = setOf("memory.view", "memory.append", "memory.replace", "memory.delete")

    /** 侦察四件（[LogStatsDiffTools.NAMES] 单源引用——研判动线「统计→下钻→引用」+ 错误聚类） */
    private val RECON_NAMES = LogStatsDiffTools.NAMES

    /**
     * log.* 时间过滤两件：filter_by_time = 对称窗口（时间点 ±N 整数分钟）；
     * filter_by_range = 任意起止闭区间（奇数分钟区间等对称窗口表达不了的形状）。
     */
    private val LOG_FILTER_NAMES = setOf("log.filter_by_time", "log.filter_by_range")

    val all: List<QuestAbility> = listOf(
        QuestAbility(
            id = "log-analysis",
            title = "日志分析",
            description = "根因研判：解密→过滤→取证→知识库反查，产出五部分结构化报告",
            toolNames = EXTENDED_FILE_NAMES + KNOWLEDGE_NAMES + MEMORY_NAMES + RECON_NAMES +
                LOG_FILTER_NAMES + setOf("log.analyze_log"),
            promptSection = QuestAbilityPrompts.LOG_ANALYSIS_SECTION,
        ),
        QuestAbility(
            id = "voice-minutes",
            title = "语音纪要",
            description = "转写原文整理为三段纪要（要点/待办/结论），原文不改写",
            toolNames = setOf("fs.extended.read_file"),
            promptSection = QuestAbilityPrompts.VOICE_MINUTES_SECTION,
        ),
    )
}
