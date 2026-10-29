# knowledge-import

## Purpose

定义 md 知识包（ai-config 格式族）到 SQLite 知识库四表的导入契约：让用户把既有 markdown 知识真源批量转进应用内知识库，导入过程带结构校验（拦截断链/漏登记/仓库不一致等已知质量问题），导入结果幂等可重复。

## Requirements

### Requirement: md 知识包解析导入

导入器 SHALL 接受一个知识包根目录（含 TAG_REGISTRY.md / LOG_ANALYSE_INDEX.md / UPGRADE_LOG.md / LOG_DIR_INFO.md 四元数据 + `<repo>/` 分节知识文档），解析为应用内知识库的结构化数据并落库。解析范围 SHALL 覆盖：TAG_REGISTRY 的主映射表（文档 → TAG 列表）、别名与变体表、超长 TAG 名单；LOG_ANALYSE_INDEX 的症状快查表、跨模块组合、知识库全目录；知识文档骨架（标题格式 `# {feature} 问题分析 [{process}]`、元数据块三行：症状触发词/关联文档/蒸馏元数据、`####` 功能分组、`###` 排查要点决策树）。

#### Scenario: 全量导入存量真源

- **GIVEN** 一个含 2 仓库（repo-alpha/repo-beta）、65 篇知识文档、848 行元数据的合法知识包目录
- **WHEN** 用户执行导入（UI 或 CLI）
- **THEN** 四表（tag_registry/decision_nodes/golden_samples/knowledge_docs）按仓库分组落库，导入结果报告各表条目数，重复导入结果一致（幂等）

#### Scenario: 解析失败明确报错不静默

- **GIVEN** 目录缺少 TAG_REGISTRY.md 或知识文档标题不符合骨架格式
- **WHEN** 用户执行导入
- **THEN** 导入失败并给出具体文件与行级原因，不写入部分数据（整包事务语义）

### Requirement: 导入校验断言（6 项移植 CHECK）

导入器 SHALL 在落库前执行 6 项结构校验，任一 ERROR 级失败即中止导入并逐条报告：①知识文档内 tag 声明与 TAG_REGISTRY 主映射登记一致（CHECK 1）；②知识文档标题符合 `# {feature} 问题分析 [{process}]` 格式（CHECK 3）；③LOG_ANALYSE_INDEX 全目录与磁盘文档双向一致——无孤儿文档/悬空条目（CHECK 4）；④TAG_REGISTRY 主映射行与磁盘文档双向一致（CHECK 5）；⑤知识文档必须位于 `<repo>/` 仓库分节下（CHECK 14）；⑥TAG_REGISTRY 仓库分节与文档路径的仓库前缀一致（CHECK 15）。

#### Scenario: 断链文档被拦截

- **GIVEN** 某知识文档引用了不存在的关联文档路径（CHECK 2 类缺陷的导入前拦截由格式解析承载）
- **WHEN** 导入器解析该文档
- **THEN** 导入中止，报告指出该文档与违规路径，无数据入库

#### Scenario: 校验通过后导入可检索

- **GIVEN** 一个通过全部 6 项校验的知识包
- **WHEN** 导入完成后用户以症状词检索知识库
- **THEN** 检索命中导入的文档条目（title/summary 与 md 元数据一致）

### Requirement: CLI 批处理入口

log-tools CLI SHALL 提供 `--type kb-import --kb <目录>` 子命令，接受知识包根目录参数执行同语义导入，退出码非零表示导入失败并输出校验报告；供用户在启动应用外批处理存量知识真源。

#### Scenario: CLI 导入存量包

- **GIVEN** 用户在 shell 执行 `java -jar log-tools-all.jar --type kb-import --kb ~/<知识包目录>/knowledge-base/docs`
- **WHEN** 该目录为合法知识包
- **THEN** 导入成功，stdout 输出各表条目数摘要；再次执行结果一致
