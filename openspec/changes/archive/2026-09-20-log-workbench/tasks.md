# Tasks

## 1. 地基：runs 表 + RunStore（run-persistence）

- [x] 1.1 SQLDelight schema v2→v3：`runs` 表（id/title/log_files JSON/target_time/range_minutes/status/report_path/created_at/updated_at）+ `.sqm` 迁移文件 + `core/build.gradle.kts` version=3；迁移测试：既有 v2 库升级不丢 sessions/messages 行 + runs 可写可读（KnowledgeEngineTest v1→v2 配方复用，钉 migrate/user_version 回写行为）
- [x] 1.2 `core/data/db/RunStore.kt`：createRun/listRuns（updated_at 倒序）/renameRun/deleteRun（事务级联删 messages）/updateStatus/updateReportPath——管理面模式照 SqliteSessionStore.listSummaries 先例；单测：全生命周期回环 + delete 级联 + 状态迁移
- [x] 1.3 run 时间线接线纪律验证：WorkbenchSession 以 runId 为 sessionId 落 messages（复用 SessionStore.append 分型）；单测断言 run 与时间线行的关联完整性（listRuns 每项可 replay 时间线）；observation 2000 截断不放宽（证据原文走产物文件路径）

## 2. 模块与导航接线（module-nav）

- [x] 2.1 `:feature-logworkbench` 模块建块：settings include + `easydevtoolbox.kmp.feature`（🔴 src/jvmMain/kotlin 形态——lib 形态撞 ScanSurfaceSelfCheckTest.A）+ composeApp 依赖行；包 `page.logworkbench`（跨模块 relativePath 天然唯一）；空 Contributions 骨架先编译绿
- [x] 2.2 `ToolPage.LogWorkbench` 枚举值 + LogWorkbenchContributions（navItem icon=QUERY_STATS、group=AGENTS、aiEnabled=AiToolsToggle.enabled、navActivity=WorkbenchSession.activity——引用物 3.x 建好后接线；本任务先占位恒 Idle 流）+ composeApp featureRegistrations 拼接（LogKit 后、AiContributions 前——组内序 [工作台, AI 设置, 蒸馏]）
- [x] 2.3 NavTest 断言面五处同步：navItems 顺序表/智能体组列表 [LogWorkbench, AiSettings, Distill]/navActivity set {LarkBridge, Distill, LogWorkbench}/aiEnabled set {Distill, LogWorkbench}/B7 恒空维持；`:composeApp:jvmTest` 过

## 3. 工作台会话域（workbench-session）

- [x] 3.1 `WorkbenchSession`：单循环会话核心（B5 语义——附件渐进 register + escapeGate HITL；构造签名照 DistillSession 平移 + privateKeyProvider）；reducer KoogLoopEvent→ChatEntry（ToolCall 原位迁移）+ 🔴 StreamingFrame 流式打字机消费（AiChatSession 蓝本：streaming 草稿→LlmCallCompleted 转正+终稿消重 finalizeRun）；预算 key `llm_workbench_max_iterations`（缺省 60，LlmConfigStore 新 key + 校验域 6..120 + 单测）；overallTimeout ×6 轮先例
- [x] 3.2 `WorkbenchSessionTest`（零 LLM/零网络——DistillSessionTest 同款纪律）：事件→条目映射（ToolCall 原位/流式草稿转正/终稿消重）/围栏外路径 HITL 弹窗态/中止保全（CE 上抛 + 已有条目保留）/TimeoutCE→TIMEOUT 分型（CE 分支之前）/sessionId=runId 时间线落库断言
- [x] 3.3 `WorkbenchViewModel`：run 态机（IDLE→RUNNING→COMPLETED/ABORTED/FAILED）+ RunStore 读写 + 结构化输入表单态（日志文件多选/时间点/范围/症状/Tag 正则）+ 会话装配（viewModel {} 初始化器重载——🔴 Desktop 反射创建不支持，7b1c7cb 先例）；🔴 直测缝纪律：VM 构造触 Database.database() 真单例——承重纯函数（如参数校验/产物路径派生）companion/文件顶层化
- [x] 3.4 WorkbenchSession.activity 活动门面（attach/detach 镜像——DistillSession companion 同构）+ 2.2 的 navActivity 接线正式替换占位流

## 4. 研判提示词与工具装配（agent-prompt）

- [x] 4.1 `core/llm/WorkbenchPrompts.kt`：ANALYZE_PROMPT（十铁律产品化改写——每条 1-3 句执行约束 + 验证门（方式 A/B，未过门=怀疑点）+ 报告五部分结构模板 + 表格-日志配对规范 + 知识库消费指令（search 症状词→lookup_tag→read_doc→合并 tag_regex））；🔴 不并入 KoogAgentPromptPresets（KoogDistillPrompts 先例）；长度 <3KB
- [x] 4.2 工具集装配：`WORKBENCH_NAMES = EXTENDED_NAMES(7) + KNOWLEDGE_NAMES(3) + MEMORY_NAMES(4) + PIPELINE_NAMES(1)` + write_todos（todoBoard 注入）——经 AgentToolCatalog.buildRegistry(names) 精装；🔴 不入任何 base names（桥接/桌面旧链零影响）；单测：装配面精确集合 + distill.* 恒不在 + 私钥 null 时 analyze_log 不注册
- [x] 4.3 `WorkbenchSession.runAnalysis` 执行体：表单参数 → prompt 组装（结构化输入 + 症状 + 已有报告上下文）→ koogToolLoop（sessionId=runId）→ sink 产物精确挂载（analyze_log 各阶段 artifacts → run 时间线 + 证据流数据源）；报告生成收口（phaseResult → report.md 落 `~/.easy_dev_toolbox/logworkbench/runs/<runId>/` + updateReportPath）

## 5. 三栏工作台页（workbench-ui）

- [x] 5.1 新 token：`Dimens.workbenchSidePanelWidth`(280)/`workbenchReportPanelWidth`(320)/`workbenchInputRowHeight` 等 + theme.md「有据保留」登记 + UiGuard 值级豁免**不进**（全 token 化目标——蒸馏批零白名单同款）；token 单测
- [x] 5.2 `LogWorkbenchPage` 三栏骨架：ShellToolsPage 例外先例形态（不套 PageScaffold；Column(padding(space16)) + Row(weight(1f))：左栏固定宽输入表单 + VerticalDivider + 中栏 weight(1f) 证据流 + VerticalDivider + 右栏固定宽报告）+ PageHeader 引用；🔴 UiGuard 全绿（animateScrollToItem 禁——引用跳转用 scrollToItem）
- [x] 5.3 左栏：结构化输入表单（文件 chips 多选（FileDropZone/TagsField 配方）/时间点 CommitTextField/范围 CommitNumberField/症状 OutlinedTextField/Tag 正则可空）+「开始分析」按钮（AI 关闭时研判区降级文案）+ run 列表（RunStore.listRuns 倒序 + 点击回看 + 重命名/删除菜单——HoverableRow 配方）
- [x] 5.4 中栏证据流：管线阶段锚点条（解密→时间过滤→环境扫描→Tag 过滤→研判——GitHub Actions 阶段模型；点击 scrollToItem 跳区段）+ 日志行流（LogConsolePanel 配方：级别四色 token/Monospace/LazyColumn(key=seq)/VerticalScrollbarSlot/BottomFollowScrollEffect）+ 行级 ±N 上下文展开（ToolCallCard 的 AnimatedVisibility(softSpring) 折叠先例；展开段读产物文件行段）+ 字段一键过滤（点行内 TAG 字段→过滤 chip）+ 时间直方图（Canvas 照 UsageBarChart/rememberChartProgress 入场模式，60 桶）
- [x] 5.5 右栏报告面板：因果链三段卡（TonalCard：根因→直接故障点→影响面）+ 假设三态徽章（✓ primary/✗ error/? onSurfaceVariant——失败假设保留不抹除）+ 依据引用块（逐字日志行 + 点击 scrollToItem 跳中栏 + 高亮）+ 横幅三级展开（摘要→总览→明细，StatusBanner details 槽配方）+「打开报告文件」「追问」次级按钮
- [x] 5.6 追问链：报告面板次级按钮发起（非主输入框 chat 形态）——携带报告上下文增量研判 + 结论增量段追加进报告与面板（原结论不改写）+ report.md 同步更新
- [x] 5.7 UiGuard 终跑：`:composeApp:jvmTest` 全绿（logworkbench 页零白名单条目目标；确需豁免先 theme.md 登记）

## 6. 深链入口（deep-link）

- [x] 6.1 `LogWorkbenchDeepLink` object（pending 槽单例流）：`PendingInput(logFiles, prefillNote?)` + `offer()/consume()`；LogWorkbenchPage LaunchedEffect 消费 → VM.prefill；单测（offer→consume 恒等 + consume 后清空）
- [x] 6.2 XLog 解密产物「深度分析」情境按钮：宿主接线方案（feature-logkit 产物卡暴露回调槽——不 import feature-logworkbench；composeApp 侧接线 DeepLink.offer + setCurrentPage(LogWorkbench)）；🔴 若接线复杂度超预期降级为独立子批（在 design D6 已备降级路径）——apply 时定夺
- [x] 6.3 深链单测：pending 槽→VM prefill→输入表单预填断言（合成产物路径）

## 7. 守护与收口（guard-close）

- [x] 7.1 全模块守护终跑 `--rerun-tasks --no-build-cache`（core/feature-logworkbench/feature-logkit/feature-larkbridge/composeApp/log-tools）+ 扫盘型守护核对（UiGuard/ModuleGraphGuard/LarkSdkIsolation/ScanSurfaceSelfCheck/ToolCatalogDiscipline 全绿）
- [x] 7.2 数据面程序化端到端：合成日志样本（明文 .txt + 假时间点）→ 管线全跑 → 产物挂载 → run 落库 → replay 时间线 → 报告文件生成——单测背书全链（真机 LLM 链待已配置的 LLM 端点）
- [x] 7.3 欢迎页智能体组三成员截图留档 + openspec validate + git commit（收口）；docs-sync 提醒（新增页面/导航分组变更——确认后执行）

## 验证方式（apply 阶段执行）

- 全模块守护 `--rerun-tasks --no-build-cache` 全绿（含新增 NavTest 断言面）
- runs v3 迁移测试 + WorkbenchSessionTest reducer 纪律 + RunStore 生命周期 + 深链槽单测
- 数据面端到端程序化闭环（合成样本）
- 真机验收待维护者配端点后：拖真实 .alog → 管线 → 证据流 → 报告 → run 回看 → 追问
