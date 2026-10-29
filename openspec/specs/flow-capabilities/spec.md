# flow-capabilities Specification

## Purpose
TBD - created by archiving change add-flow-capabilities. Update Purpose after archive.

## Requirements

### Requirement: 流程能力目录注册面

流程页能力挑选器 SHALL 由硬编码注册表（`QuestCapabilities.all`）驱动：每个能力
含稳定 id / 展示 title / description / toolNames / promptSection。首版 SHALL 注册
两个真实能力——日志分析（`log-analysis`）与语音纪要（`voice-minutes`）；全不选 =
通用对话隐式能力（零工具零 prompt 段）。新增能力 = 加一个注册项（不做能力市场/
插件化/用户自定义 DSL）。

#### Scenario: 能力目录守护

- **GIVEN** QuestCapabilities 注册表
- **WHEN** 守护测试运行
- **THEN** 每个注册项的 toolNames 全部 ∈ AgentToolCatalog 可解析工具面（防目录
  名集与目录注册漂移——「列了没注册」零容忍）

#### Scenario: 新建表单展示能力 chips

- **GIVEN** 用户进入流程页新建表单
- **WHEN** 能力目录非空
- **THEN** MultiChoiceField 渲染「日志分析」「语音纪要」选项（含描述引导文案），
  可多选；全不选 = 通用对话

### Requirement: 能力装配与 prompt 协作版

选中能力 SHALL 驱动会话装配：首轮 prompt = 能力 promptSection（协作版段式过渡——
不改写独白式人设，防与 SYSTEM_PROMPT 双重人设冲突）拼在用户目标前；工具集 =
能力 toolNames 并集 + user.ask_user 恒注册。日志分析能力 SHALL 注入 privateKeyProvider
装配缝（null = analyze_log 不装，纯明文分析）与 memoryDir（null = memory.* 不装）。

#### Scenario: 日志分析能力装配

- **GIVEN** 用户勾选「日志分析」并开始流程
- **WHEN** agent 首轮装配
- **THEN** 工具集含 fs.extended 七件 + knowledge 三件 + memory 四件 +
  analyze_log（私钥可用时）+ user.ask_user；首轮 prompt 前段含研判协作纪律
  （十铁律协作版 + 验证门 + 知识库消费流程 + 报告格式）

#### Scenario: 语音纪要能力装配

- **GIVEN** 用户勾选「语音纪要」并开始流程
- **WHEN** agent 首轮装配
- **THEN** 工具集含 fs.extended.read_file + user.ask_user；首轮 prompt 前段含
  三段纪要纪律（原文不改写/语气词剔除/五语混排 + 纪要格式）

#### Scenario: 双能力并选

- **GIVEN** 用户同时勾选「日志分析」与「语音纪要」
- **WHEN** agent 首轮装配
- **THEN** 工具集 = 两能力 toolNames 并集；prompt 段 = 两段协作版以空行分隔拼接
