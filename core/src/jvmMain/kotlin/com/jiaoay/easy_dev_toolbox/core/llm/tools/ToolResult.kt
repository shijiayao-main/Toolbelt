package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken

/**
 * 工具结构化结果（参照 deepagents 的结构化工具结果契约）：
 *
 * Koog `SimpleTool` 的 TResult 被 final 定死为 String（SimpleTool.kt 源码核实），故结构化结果
 * **不能**作为 execute 返回值上抛——设计为「内部结构化 + 渲染文案喂 LLM + sink 旁路给程序侧」三段式：
 */
sealed class ToolResult {

    /** 成功：渲染首行 + 产物精确路径 + 可选结构化负载（分页指针/grep 统计） */
    data class Ok(
        val summary: String,
        val artifacts: List<ArtifactRef> = emptyList(),
        val data: ToolPayload? = null,
    ) : ToolResult()

    /** 失败：错误码 + 文案（喂 LLM 当工具反馈，LLM 可自行调整，任务不崩） */
    data class Err(
        val code: ErrCode,
        val message: String,
        val hint: String? = null,
    ) : ToolResult() {
        /** 供 LLM 消费的完整文案（message + 可选 hint） */
        fun renderText(): String = if (hint == null) message else "$message\n$hint"
    }

    enum class ErrCode { DENIED, BAD_ARGS, NOT_FOUND, FAILED }

    /** 统一渲染入口：Ok → summary；Err → renderText() */
    fun render(): String = when (this) {
        is Ok -> summary
        is Err -> renderText()
    }
}

/** 产物引用：精确路径 + 种类（消灭「兄弟文件启发式猜」） */
data class ArtifactRef(
    val path: String,
    val kind: ArtifactKind,
    val sizeBytes: Long = 0,
)

enum class ArtifactKind { FILE, DIRECTORY, EXTRACTED, REPORT }

/** 结构化负载：工具特定的元数据（扩展面——新工具按需加子类） */
sealed interface ToolPayload {
    /** 分页读取指针（read_file）：总数 + 本次偏移 + 实际返回行数 */
    data class Paged(val totalLines: Long, val offset: Int, val returned: Int) : ToolPayload

    /** 搜索统计（grep）：命中数 + 是否被截断 */
    data class GrepStats(val hits: Int, val capped: Boolean) : ToolPayload

    /** 蒸馏进度上报（distill.report_step，P4-B）：运行面板数据源（sink 旁路） */
    data class DistillProgressPayload(
        val subject: String,
        val activeForm: String,
        val stage: String,
        val progressHint: String,
    ) : ToolPayload

    /** 蒸馏候选卡上报（distill.propose_knowledge，P4-B）：审阅卡实时流数据源 */
    data class DistillProposalPayload(
        val seq: Int,
        val kind: String,
        val title: String,
    ) : ToolPayload

    /** 提问请求旁路（user.ask_user，Quest 域 change 1）：宿主 UI 表单卡数据源 */
    data class AskRequestPayload(val request: AskRequest) : ToolPayload

    /** 只读探查回执（sys.probe）：目录条目名 + 退出码（数据非失败信号）+ 输出是否被截断 */
    data class ProbePayload(
        val key: String,
        val exitCode: Int,
        val truncated: Boolean,
    ) : ToolPayload

    /** 窄路径只读回执（sys.read_config）：路径族 + 读取目标（清单族为族根）+ 是否截断 */
    data class ReadConfigPayload(
        val family: String,
        val path: String,
        val truncated: Boolean,
    ) : ToolPayload

    /** 修复动作回执（sys.repair）：目录条目名 + 退出码 + 回滚信息（原值快照+配方）+ 是否截断 */
    data class RepairPayload(
        val key: String,
        val exitCode: Int,
        val rollback: String,
        val truncated: Boolean,
    ) : ToolPayload

    /** 配置写回执（sys.write_config）：写族 + 目标路径 + .bak 快照路径（新建文件为 null）+ 是否实际变更 + 是否截断 */
    data class WriteConfigPayload(
        val family: String,
        val path: String,
        val backupPath: String?,
        val changed: Boolean,
        val truncated: Boolean,
    ) : ToolPayload

    /** 服务动作回执（sys.service）：动作名 + 服务名 + 退出码 + 回滚配方 + 是否截断 */
    data class ServicePayload(
        val action: String,
        val service: String,
        val exitCode: Int,
        val rollback: String,
        val truncated: Boolean,
    ) : ToolPayload

    /** 任务执行回执（ws.run_task）：配方动作 + 退出码 + 是否超时被杀 */
    data class RunTaskPayload(
        val action: String,
        val exitCode: Int,
        val timedOut: Boolean,
    ) : ToolPayload

    /** git 只读回执（git.read）：动作名 + 仓库相对路径 + 退出码 + 是否截断 */
    data class GitReadPayload(
        val action: String,
        val repoPath: String,
        val exitCode: Int,
        val truncated: Boolean,
    ) : ToolPayload

    /** 外委清单回执（sys.handoff）：步数 + executed 恒 false（零执行面——清单交用户本人裁决） */
    data class HandoffPayload(
        val stepCount: Int,
        val executed: Boolean,
        val truncated: Boolean,
    ) : ToolPayload

    /** 验证回环回执（sys.verify）：修复动作名 + 三态判定 + 探查尝试次数 + 是否截断 */
    data class VerifyPayload(
        val key: String,
        val verdict: String,
        val attempts: Int,
        val truncated: Boolean,
    ) : ToolPayload

    /** 设备清单回执（dev.devices）：检测到的设备数 + 是否截断（零设备是正常答案非失败） */
    data class DeviceListPayload(
        val deviceCount: Int,
        val truncated: Boolean,
    ) : ToolPayload

    /** 设备日志快照回执（dev.snaplog）：串号 + 退出码（数据非失败信号）+ 快照落盘路径 + 是否截断 */
    data class SnaplogPayload(
        val serial: String,
        val exitCode: Int,
        val snapshotPath: String,
        val truncated: Boolean,
    ) : ToolPayload

    /** 自观测回执（self.status）：运行态 + 连接态 + 重连计数 + 终态计数 + 配置/审计聚合计数（零原文）+ 是否截断 */
    data class SelfStatusPayload(
        val running: Boolean,
        val connectionState: String,
        val reconnectCount: Long,
        val completed: Long,
        val failed: Long,
        val configIssueCount: Int,
        /** 当日审计聚合总数（-1 = 聚合暂不可得） */
        val auditTotal: Int,
        /** 当日被裁决挡下的尝试数（-1 = 聚合暂不可得） */
        val auditDenied: Int,
        val truncated: Boolean,
    ) : ToolPayload
}

fun interface ToolResultSink {
    fun onResult(toolName: String, result: ToolResult)

    companion object {
        /** 默认无操作（存量调用形态零改动） */
        val NOOP: ToolResultSink = ToolResultSink { _, _ -> }

        fun concurrentOf(onResult: (String, ToolResult) -> Unit): ToolResultSink =
            ToolResultSink { name, result -> onResult(name, result) }
    }
}

abstract class StructuredSimpleTool<TArgs : Any>(
    private val sink: ToolResultSink,
    argsType: TypeToken,
    name: String,
    description: String,
) : SimpleTool<TArgs>(
    argsType = argsType,
    name = name,
    description = description,
) {
    /** 结构化执行体（直测缝：测试直接断言 ToolResult 结构；生产经 [execute] 三段式） */
    abstract suspend fun executeTyped(args: TArgs): ToolResult

    /** 旁路顺序：先 sink 后 render——sink 消费方拿到的数据与 LLM 收到的文案同源 */
    final override suspend fun execute(args: TArgs): String {
        val result = executeTyped(args)
        sink.onResult(name, result)
        return result.render()
    }

    /** 围栏拒绝文案（DecryptTools 与扩展工具共用同一句，LLM 反馈一致） */
    protected val deniedHint: String
        get() = "路径不在本任务允许范围内（只能访问用户拖入的文件与本次解密/解包的产物），请检查路径或改用已登记文件"
}
