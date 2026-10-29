package com.jiaoay.easy_dev_toolbox.page.quest

import com.jiaoay.easy_dev_toolbox.core.llm.WorkbenchPrompts

/**
 * 流程能力 prompt 段。
 *
 * ## 协作版改写纪律（双重人设防线）
 * 两真源都是独白式完整 system prompt（「你是一名…专家」开头）：
 * - 日志分析 = WorkbenchPrompts.ANALYZE_PROMPT（:core——内容真源）；
 * - 语音纪要 = VoiceSummarizePrompts.SUMMARIZE_PROMPT（feature-voice——内容真源，
 *   feature 互不依赖不可 import，按纪律正文逐条改写）。
 * 直接拼进用户消息前会与 QuestPrompts.SYSTEM_PROMPT 形成双重人设——本文件去掉
 * 人设开头，换「已启用能力」段式过渡，纪律正文逐条保留。
 *
 * 日志分析纪律正文单源化：十铁律/验证门/知识库消费/报告格式四段
 * 直接引 [WorkbenchPrompts.disciplineSection]（`###` 标题层级 / 「本流程」收束主语）——
 * 本文件只保留协作语境的过渡段与工具面，不再自持纪律正文拷贝（消除「改一处漏一处」
 * 的双份漂移面；语音纪要段仍自持——feature-voice 不可 import）。
 */
object QuestAbilityPrompts {

    /** 日志分析能力段（WorkbenchPrompts 纪律正文协作版引用——工具面语境略有省略） */
    val LOG_ANALYSIS_SECTION: String = """
## 已启用能力：日志分析
本流程中涉及日志与故障分析的任务，按以下纪律执行。

### 可用工具
- log.analyze_log：确定性管线（解密→时间过滤→环境扫描→Tag 过滤），产出各阶段过滤文件——分析以管线产物为主要对象
- log.filter_by_time / log.filter_by_range：时间维度过滤两件（对称窗口 = 时间点 ±N 整数分钟；任意区间 = 起止时刻闭区间含两端——奇数分钟区间等对称窗口表达不了的形状用区间件）→ 输出 .txt 产物落原文件旁
- knowledge.search_cases：历史案例检索（黄金样本——症状→根因→修法→证据日志四要素一次给全）——同类症状先查历史案例，零命中再检索文档
- knowledge.search / knowledge.read / knowledge.lookup_tag：领域知识库（症状→文档 / TAG→文档 / 行为约束与决策树）
- fs.extended.read_file / grep / glob：取证读取（分页 read_file、模式 grep——参数写错会静默漏日志，空结果先放宽模式重试再下「无此日志」结论）
- log.stats / log.diff / log.view：侦察三件（分布概览 / 双日志对比 / 行号原文窗口）——统计→下钻→引用：大日志先看分布再定过滤策略，两份日志先对比找差异窗口，定位到行号后用 log.view 引用原文（均零产物）
- log.cluster：错误聚类（E/F 级块默认归并成 top-N 模板摘要：模板串/计数/首末时间/块首行号样例）——研判前先对 E/F 行聚类，同型异常先看 top-N 模板定优先级，再按行号样例用 log.view 下钻原文（零产物）
- memory.*：长期记忆（历史分析结论的跨会话参考）

${WorkbenchPrompts.disciplineSection(heading = "###", subject = "本流程")}
""".trim()

    /** 语音纪要能力段（VoiceSummarizePrompts.SUMMARIZE_PROMPT 协作版——三段纪要纪律） */
    val VOICE_MINUTES_SECTION: String = """
## 已启用能力：语音纪要
本流程中涉及语音转写/会议记录整理的任务，按以下纪律执行。

### 可用工具
- fs.extended.read_file：读取转写原文文件（分页读取——长文先读全文再整理，禁止只读开头就下结论）

### 整理纪律
1. **原文不改写**：纪要里的每一条要点都必须能在原文中找到出处；禁止补充原文没有的信息（如无上下文的推测结论）
2. **语气词剔除**：呃/嗯/那个/就是 等口头填充词不进纪要，但语义完整的口语表述保留原意转写为书面语
3. **说话人不明确时不虚构**：转写原文无说话人标注——禁止虚构「张三说」；以内容语义分点即可
4. **五语混排**：中英日韩粤词汇按原文保留（专有名词/代码/型号不改译）；粤语文本按原文保留

### 纪要格式（三段——直接输出 markdown）
# 纪要
## 要点 ——3-8 条核心信息（每条一行，按原文出现顺序或主题归类）
## 待办 ——原文中出现的行动项/待确认项（没有则写「无明确待办」）
## 结论 ——1-3 条收束性判断（无明确结论则写「原文未给出明确结论」）

**长度约束**：纪要总长度不超过原文的 1/3——纪要是压缩不是复述。
""".trim()
}
