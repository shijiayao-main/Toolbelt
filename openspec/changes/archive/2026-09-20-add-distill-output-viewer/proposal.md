# Proposal: add-distill-output-viewer

## Why

P4-B 蒸馏批落了引擎与入库，但**成品不可见**：`KbManagePanel` 只有 repo 行列表 +
四表行数统计 + 删除 + 整包导出 JSON——接受候选后没有任何地方能看到蒸馏出的
TAG/决策节点/黄金样本/文档内容。需求（2026-09-20）：「蒸馏跑完看不到成品——
希望加日志/入口能主动查看蒸馏后产物」。查询 API（`KnowledgeRepository` 的
`lookupTag`/`searchGoldenSamples`/`searchDocs`/`docsOf`/`goldenSamplesOf`/
`decisionTree`/`readDoc`）在 P3 已全部落好但零 UI 消费方——本 change 纯 UI 接线，
数据面零改动。

## What Changes

1. **KbManagePanel 升级为可浏览**：repo 行从纯文字升级为可展开浏览——展开后四表
   分节（TAG 表 / 决策节点 / 黄金样本 / 知识文档）：
   - TAG 表：tag 名 + 行为约束描述 + 来源文档链
   - 决策节点：按 method 分组的 condition→action 列表
   - 黄金样本：symptom/rootCause/fixSummary 三元组卡（点击展开 evidenceLog）
   - 知识文档：title/summary 索引行（点击展开 body 全文）
   - 搜索框：keyword 经 `searchGoldenSamples` + `searchDocs` 双路检索
2. **REVIEW 收尾「查看入库结果」入口**：蒸馏 REVIEW 阶段（候选接受完成后）加
   入口按钮，跳转/滚动到 KbManagePanel 并展开对应 repo——闭环「跑完即可看」。
3. VM 增量：`kbBrowse` 状态面（展开 repo → 四表快照拉取）+ `kbSearch` 动作 +
   REVIEW 收尾入口动作。

## Out of Scope

- 知识库编辑（浏览只读——编辑是未来另一批）
- B2 导出器增强（md 人类可读导出——plan 提过列为可选，暂不做）
- 记忆面板联动（L2 记忆不在本 change 面）
