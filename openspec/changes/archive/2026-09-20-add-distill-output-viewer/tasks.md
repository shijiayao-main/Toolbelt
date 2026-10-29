# Tasks: add-distill-output-viewer

## 1. 数据面微增量

- [x] 1.1 `KnowledgeRepository.tagsOf(repo)`（暴露既有 `tagsByRepo` SQL——浏览面
      TAG 表全量枚举；8 行方法 + TagEntry 映射）

## 2. VM 增量

- [x] 2.1 DistillViewModel：`kbBrowse` 快照面（展开 repo → 四表数据拉取——
      tagsOf + methodsOf/decisionTree + goldenSamplesOf + docsOf）+ `kbSearch`
      双路检索动作（searchGoldenSamples + searchDocs）+ 快照/结果 StateFlow +
      viewDistillOutput 收尾动作（planRepo 真源）

## 3. UI 升级

- [x] 3.1 KbManagePanel：repo 行可展开（点击 toggle + kbBrowse 流态）——四表分节
      渲染（SectionCard tonal=false 裸分组）+ 黄金样本/文档行点击展开
      （evidenceLog/body 直读 docsOf 快照；🔴 跨模块 public 属性 smart cast 坑
      ——局部 val 收敛）
- [x] 3.2 搜索框（CommitTextField 失焦提交——黄金样本/文档双路结果并列分节 +
      命中计数行）
- [x] 3.3 REVIEW 阶段「查看入库结果」入口（REVIEW 分支下渲染管理面 + planRepo
      非空且有审阅行时出按钮 → viewDistillOutput 自动展开；零产物不渲染）

## 4. 收口

- [x] 4.1 全模块守护 `--rerun-tasks --no-build-cache` 全绿 + openspec validate 干净
      + 归档
