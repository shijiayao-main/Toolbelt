# Design: add-distill-output-viewer

## D1 数据面（零改动——纯 API 接线）

全部走 `KnowledgeRepository`（core/data/db）既有查询面：

| 面 | API | 形态 |
|---|---|---|
| TAG 表 | `tagsOf(repo)`——**本批新增 Repository 方法**（`tagsByRepo` SQL 已在 Knowledge.sq:29，导出器 KnowledgePack 已消费；Repository 层漏暴露） | 全量（约 8 行） |
| 决策节点 | `decisionTree(repo, method)` + `methodsOf(repo)` | method 分组遍历 |
| 黄金样本 | `goldenSamplesOf(repo)` / `searchGoldenSamples(repo, kw)` | 全量 + 检索双面 |
| 知识文档 | `docsOf(repo)` / `searchDocs(repo, kw)` / `readDoc`（展开时取） | 索引 + 惰性全文 |

## D2 UI 形态（KbManagePanel 内升级——不新建页面）

- repo 行 → 可展开（点击 toggle）：展开时 VM 拉四表快照（IO）；
- 四表分节用 SectionCard（tonal=false 裸分组——嵌套在管理卡内层避免卡中卡）；
- 黄金样本行：三行摘要（symptom/rootCause/fixSummary）+ 点击展开 evidenceLog
  （code block 风格文本）；文档行：title + summary + 点击展开 body；
- 搜索框：CommitTextField 失焦提交 + 两路结果并列分节；
- 展开态持久：同一 repo 展开期间刷新（删除别的 repo）不坍缩——expandedRepo
  状态 keyed by repo 名。

## D3 REVIEW 收尾入口

- DistillPage REVIEW 阶段卡（phase == REVIEW && 有入库产物时）加「查看入库结果」
  按钮：动作 = 滚动到 KbManagePanel + 预展开 targetRepo；
- targetRepo 真源 = 本次蒸馏的 repo 名（DistillViewModel 执行段已定）——核对
  VM 里 repo 名的持有面（planWith/kbRepo 字段）；无产物（零候选接受）不渲染按钮。

## D4 测试策略

- 数据面零改动 ⇒ 无新单测义务；
- VM 增量：kbBrowse 快照拉取/搜索动作（KnowledgeRepository 直查 inMemory db——
  P3 批 KnowledgeRepositoryTest 同款 fixture 可平移）；
- UI 全 token 化零白名单；全模块守护终跑 `--rerun-tasks --no-build-cache`。
