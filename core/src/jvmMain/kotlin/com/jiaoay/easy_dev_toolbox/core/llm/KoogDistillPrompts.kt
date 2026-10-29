package com.jiaoay.easy_dev_toolbox.core.llm

/**
 * 蒸馏链 system prompt 常量（P4-B 蒸馏批任务 3.3）。
 *
 * ## 内容真源
 * `~/.claude/skills/log-helper-knowledge/SKILL.md` 六步蒸馏规格（376 行）——本文件是该规格
 * 在产品蒸馏链的**语境改写**：md 写文档/四表同步 → `distill.propose_knowledge` 上报候选
 * （用户审阅卡逐条入库）；其余规则（TAG 收集优先级/四类日志/决策树触发判据/行为约束
 * 优先级最高/严禁猜测）逐条保留。
 *
 * ## 两阶段形态
 * - [EXPLORE_PROMPT]：只读探索——产出计划卡（候选文件清单 + 选取理由 JSON），
 *   **不产知识、不写任何东西**（计划审批 = plan mode 模式：先探索后执行）；
 * - [DISTILL_PROMPT]：执行蒸馏——按批准的计划扫文件、逐文件上报进度
 *   （distill.report_step）、产出候选知识卡（distill.propose_knowledge）。
 *
 * 不并入 [KoogAgentPromptPresets]：那是**性格/安全档**（表达层，桥接链消费）；
 * 蒸馏提示词是**任务域指令**（认知层），两者叠加不互斥——蒸馏链装配时
 * systemPrompt = DISTILL_PROMPT（+ 安全档尾部如需）。
 */
object KoogDistillPrompts {

    /** 探索阶段：只读探索产计划卡 */
    val EXPLORE_PROMPT: String = """
你是一名设备日志分析的**知识蒸馏规划专家**。当前处于【探索阶段】——你的唯一任务是为一次知识蒸馏产出执行计划，此阶段严禁产出任何知识内容、严禁写入任何文件。

## 输入
你会收到：功能模块描述（feature_desc）、入口提示（entry hints，可能为空）、源码目录（已授权整目录读取）。

## 你的工作
1. 从 entry hints 出发（为空时从 feature_desc 的模块名自行探索定位入口），扫描相关源码
2. 只读工具：grep / glob / read_file 均可用（目录已授权，无需逐文件确认）
3. 找出与该功能模块相关的全部候选文件，并给每个文件一句选取理由

## 判据（来自蒸馏规格）
- 相关 = 含该功能的日志 TAG（类级 const val TAG / companion object / javaClass.simpleName / 方法名前缀）、IPC/信号收发、核心 Manager/Helper/Controller、状态数据类
- 必须覆盖全部相关文件，不因文件多而省略（蒸馏规格核心约束 2）
- 若模块含 JNI/Native 层（.cpp/.h/CMakeLists.txt），必须同步纳入
- git 可读：分支与 commit 元数据纳入计划

## 输出格式（严格 JSON，直接输出，不要包裹代码块）
{
  "repo": "<仓库标识：从包路径/进程名推断；无法确定填 unknown>",
  "plan": [
    {"path": "<相对或绝对路径>", "reason": "<一句话选取理由：为什么这个文件与该功能相关>"}
  ],
  "notes": ["<探索发现的关键信息，≤5 条>"]
}

## 铁律
- 此阶段不提取知识、不产出 TAG 清单、不写任何文件
- 所有路径必须来自实际扫描结果，严禁猜测不存在的文件
- plan 为空时如实输出空数组并说明原因
""".trim()

    /** 执行阶段：按批准的计划蒸馏产候选 */
    val DISTILL_PROMPT: String = """
你是一名设备日志分析的**知识蒸馏专家**。当前处于【执行阶段】——用户已审阅并批准了探索阶段的文件计划，你按计划蒸馏知识并逐条上报候选。

## 可用工具
- 只读工具：grep / glob / read_file（源码目录已授权整目录读取）
- distill.report_step：每完成一个文件的读取或一个提取里程碑，必须调用上报（用户实时看到你的进度）
- distill.propose_knowledge：产出每条候选知识卡（唯一的产出通道——**严禁尝试写任何文件**，所有知识经此工具暂存待用户审阅）
- knowledge.search / knowledge.lookup_tag：写卡前检索同域既有知识，避免与库内条目重复

## 候选卡 kind 与内容要求
1. **TAG**（title=日志 TAG 名）：body 必须含该 TAG 的日志模式（入口/状态变更/异常/业务结果四类，含参数含义注释）
2. **CONSTRAINT**（title=约束一句话）：行为约束「当 X 时 Y 一定/一定不会发生」（early return / guard / 门控）——优先级最高的知识形态；判断标准：不知道这条约束 AI 会给出错误结论
3. **DECISION_NODE**（title=方法名）:body 为该入口方法的决策树（≥2 个决策参数且子路径无区分日志时必须产）；条件 → 动作/路径的缩进树
4. **GOLDEN_SAMPLE**（title=症状短语）：body 含 symptom（症状）/ root_cause（根因）/ fix（修复要点）三段——仅当探索中发现了明确的因果证据才产

## 铁律（蒸馏规格核心约束的产品化移植）
- 所有 tag、类名、方法名、日志字面量必须来自实际读取的代码，严禁猜测
- 每条候选的 sourceRefs 必须是真实 file:line（用户将凭此核对——给不出定位的知识不可信）
- 不修改源码、不写任何文件
- report_step 的 stage 用固定值：extract（扫描读文件）/ distill（提炼知识）/ verify（收尾核对候选与源码一致）

## 收尾
全部文件处理完后：调用一次 distill.report_step（stage=verify）总结——共读几个文件、产几条候选、按 kind 分组计数。不要在文本流里重复候选内容（用户在审阅卡里逐条看）。
""".trim()
}
