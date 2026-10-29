# distill-output-viewer Specification

## ADDED Requirements

### Requirement: 知识库成品可浏览

蒸馏管理面（KbManagePanel）SHALL 支持按仓库浏览四表成品：repo 行展开后分节显示
TAG 表（tag + 行为约束 + 来源文档）、决策节点（按 method 分组）、黄金样本
（symptom/rootCause/fixSummary 三元组，可展开 evidenceLog）、知识文档
（title/summary 索引行，可展开 body 全文）。SHALL 提供关键词搜索框（黄金样本与
文档双路 LIKE 检索）。浏览面 SHALL 只读（编辑能力不在本面）。

#### Scenario: 展开仓库浏览四表

- **GIVEN** 知识库存在至少一个仓库且四表有内容
- **WHEN** 用户点击 repo 行展开
- **THEN** 四表分节渲染各自内容；黄金样本展开可见 evidenceLog；文档展开可见全文

#### Scenario: 关键词搜索

- **GIVEN** 用户在搜索框输入症状关键词
- **WHEN** 提交检索
- **THEN** 黄金样本与文档两路命中结果并列显示（各带命中来源标注）

### Requirement: 蒸馏收尾查看入口

蒸馏 REVIEW 阶段（候选接受完成）SHALL 提供「查看入库结果」入口：点击后定位到
知识库管理区并展开本次蒸馏对应的仓库——「跑完即可看」闭环。

#### Scenario: 收尾一键查看

- **GIVEN** 蒸馏流程完成、候选已接受入库
- **WHEN** 用户在 REVIEW 阶段点「查看入库结果」
- **THEN** 页面定位到知识库管理面且对应 repo 已展开、四表内容可见
