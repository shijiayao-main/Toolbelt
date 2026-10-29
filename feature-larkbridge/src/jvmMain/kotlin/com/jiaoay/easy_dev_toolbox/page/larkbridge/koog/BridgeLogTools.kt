package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import ai.koog.agents.core.tools.ToolRegistry
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.KnowledgeTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.LogStatsDiffTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext

/**
 * 桥接调度 agent 的文件工具装配面（薄壳——工具实现已下沉 :core）。
 *
 * ## 变更史
 * - 五件 SimpleTool 工具（decrypt_log/filter_by_tag/filter_by_time/
 *   split_by_size/probe_file）在本文件实现——「内部工具能做的不委托外部 CLI」。
 * - 五件已**下沉 :core**（`core/llm/tools/LogTaskTools.kt`）并结构化升级
 *   （StructuredSimpleTool 三段式 + artifacts 精确产物），命名空间 `log.*` + aliases 保留旧名。
 *   本文件只保留**桥接装配语义**：围栏文案（「本消息的文件围栏」）+ 私钥载体注入 + extended 门控。
 *
 * ## 桥接装配语义（不变）
 * - 围栏为空（纯聊天消息无引用文件）⇒ 调用方不装配本面（工具不注册，省 token）；
 * - 私钥真源 = DispatchPayload 载体（`fileMessage.xlogPrivateKey`，App 形态宿主回落富化后）；
 * - 工具 description 英文、结果文案中文（喂 LLM）；围栏拒绝文案 = 桥接变体（fenceHint 注入）。
 *
 * @see AgentToolCatalog 目录单源与纪律守护（ToolCatalogDisciplineTest）
 */
internal object BridgeLogTools {

    /** 桥接域围栏拒绝文案（逐字保留旧 DENIED_HINT——桥接 LLM 反馈语义不变） */
    private const val BRIDGE_FENCE_HINT =
        "路径不在本消息的文件围栏内（只能操作用户引用发送的文件及其产物），请检查路径或改用已登记文件"

    /** 桥接文件工具名集合：log.* 六件 + fs.* 三件（extract/preview/list） */
    private val BRIDGE_NAMES = setOf(
        "log.decrypt", "log.filter_by_tag", "log.filter_by_time", "log.filter_by_range",
        "log.split_by_size", "log.probe_file",
        "fs.extract_archive", "fs.preview_text", "fs.list_dir",
    )

    /** extended 门控追加：:core 扩展件（read_file 分页/grep/glob/取证/时间戳） */
    private val EXTENDED_NAMES = setOf(
        "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
        "fs.extended.hash_file", "fs.extended.hex_dump", "fs.extended.strings",
        "fs.extended.convert_timestamp",
    )

    /** 日志分析管线（P4-A）：桥接链同样并入（飞书消息驱动日志分析） */
    private val PIPELINE_NAMES = setOf("log.analyze_log")

    /**
     * 侦察四件（log.stats / log.diff / log.view / log.cluster，READ_ONLY 零产物）：
     * 研判链「统计→下钻→引用」+ 错误聚类——stats 看分布再定过滤策略，diff 对比两份引用
     * 日志找行为差异，view 按行号引用原文，cluster 把同型异常归并成 top-N 模板定优先级；
     * 仍受本消息文件围栏约束。
     */
    private val RECON_NAMES = LogStatsDiffTools.NAMES

    /**
     * 组装文件工具注册表（无引用文件 = 围栏空 ⇒ 调用方不装配；文件工具面与旧版逐名等价，
     * 另并入侦察四件 log.stats/log.diff/log.view/log.cluster 与管线 log.analyze_log）。
     *
     * @param privateKeyProvider 有效 xlog 私钥（DispatchPayload 载体透传——App 形态下已含宿主
     *        回落富化，见 [KoogAgentRunner] 装配处的 xlogKeyFallback 缝；非 feature-ai 的 DataStore key 直读）
     * @param middlewares 工具中间件链（OutputCap 兜底驱逐等；默认空链零装饰）
     * @param extended 扩展面门控：true 时并入 :core 扩展件——大文件分析与取证场景；
     *        不含 write_todos（飞书无 todo 投影面）
     *
     * knowledge.* 四件（P3-D）恒在选择集——实际注册由描述符 factory 门控（search_cases
     * = hasCases() samples>0 单项判；其余三件 = `KnowledgeTools.hasContent()`——空库不注册）。
     */
    fun buildRegistry(
        context: ToolExecutionContext,
        privateKeyProvider: suspend () -> String,
        middlewares: List<com.jiaoay.easy_dev_toolbox.core.llm.agent.AgentToolMiddleware> = emptyList(),
        extended: Boolean = false,
    ): ToolRegistry = AgentToolCatalog.buildRegistry(
        names = (if (extended) BRIDGE_NAMES + EXTENDED_NAMES else BRIDGE_NAMES) +
            KnowledgeTools.KNOWLEDGE_NAMES + PIPELINE_NAMES + RECON_NAMES,
        context = context,
        config = AgentToolCatalog.ToolContextConfig(
            privateKeyProvider = privateKeyProvider,
            fenceHint = BRIDGE_FENCE_HINT,
        ),
        middlewares = middlewares,
    )

    /**
     * dev.snaplog 快照消费链（纯聊天消息 + dev.* 在册时装配）：侦察四件（log.stats/
     * log.diff/log.view/log.cluster）+ Tag 过滤——dev.snaplog 产物 register 进同一围栏
     * context 后本链直接消费（快照即入既有分析链）。与 [buildRegistry] 的差异只在名集：
     * 不含解密/分割/管线/知识库件（快照是纯文本，无 xlog 载体，私钥缝不存在），
     * 围栏文案同源（BRIDGE_FENCE_HINT 单源）。
     */
    fun buildSnapshotAnalysisRegistry(
        context: ToolExecutionContext,
        middlewares: List<com.jiaoay.easy_dev_toolbox.core.llm.agent.AgentToolMiddleware> = emptyList(),
    ): ToolRegistry = AgentToolCatalog.buildRegistry(
        names = RECON_NAMES + setOf("log.filter_by_tag"),
        context = context,
        config = AgentToolCatalog.ToolContextConfig(fenceHint = BRIDGE_FENCE_HINT),
        middlewares = middlewares,
    )
}
