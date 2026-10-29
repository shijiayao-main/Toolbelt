# Tasks

## 1. 契约层：分组一等概念

- [x] 1.1 新建 `core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/navigation/ToolPageGroup.kt`：三值枚举 TOOLS（工具）/AGENTS（智能体）/ENTRY（入口）+ KDoc（归组判据 = 功能核心价值来源，见 spec）
- [x] 1.2 `ToolPageRegistration`（core/navigation/ToolPageRegistration.kt）加 `group: ToolPageGroup? = null` 字段 + KDoc（null = 结构页不进分组渲染）
- [x] 1.3 全部 feature Contributions 补 group 声明：XLog 解密/Tag 过滤/文件分割/解压/时间戳/Shell 工具 → TOOLS；AI 设置 → AGENTS；飞书桥接 → ENTRY；宿主结构页（欢迎/应用中心/设置/调试）不声明（null）——编译绿后 git commit（行为未变的双轨期基线）

## 2. 宿主切换：navSections 聚合器

- [x] 2.1 `ToolPageRegistry`（composeApp/page/home/ToolPageRegistry.kt）删三组 `page in listOf(...)` 硬编码过滤行，新增按 `registration.group` 聚合的 navSections（枚举序 TOOLS→AGENTS→ENTRY 稳定）；分组显示名映射表落宿主（工具/智能体/入口）
- [x] 2.2 欢迎页分组卡渲染数据源指向聚合结果（PageHomeMenu/PageDefault 消费面核对，卡片组件复用零新组件）
- [x] 2.3 `:composeApp:jvmTest` + 守护 `--rerun-tasks --no-build-cache` 全绿（NavVisibility/PageUsageTracker 消费面零适配验证）；git commit

## 3. 万能解密移除（BREAKING）

- [x] 3.1 移植先于删除：`SqliteSessionStore.kt` + `SessionStore.kt` + `SessionLedger.kt` 挪 `core/data/db/`（package/import 更新，测试随迁，feature-ai 引用清零）；`FileSniffer.kt` + `FileTypeDecision.kt` 挪 `core/llm/tools/`
- [x] 3.2 删 feature-ai `page/ai/decrypt/` 的 AiChatPage/AiChatViewModel/AiChatSession/AiChatHub 四文件；判型链（classifyWithLlm/localFallbackDecision）随文件消亡，确认无散落引用
- [x] 3.3 删 `ToolPage.AiDecrypt` 枚举值 + AiContributions 的 decrypt 页面注册（navItem/actions/navActivity 全清）；AiChatHub 引用面（宿主命令面板/NavActivity 消费）grep 归零
- [x] 3.4 删 `docs/pages/ai-decrypt.md` + docs/index.md 对应条目
- [x] 3.5 全模块守护回归 `--rerun-tasks --no-build-cache`（core/feature-ai/feature-larkbridge/composeApp + log-tools）；万能解密 grep 归零核对（保留白名单：fs.* 工具族/catalog/SessionStore 家族/memory 域）；git commit

## 4. 记忆面板迁 AI 设置页

- [x] 4.1 AiChatPage 的记忆面板 UI 块（状态变量 + DropdownMenu + 查看/编辑/删除/清空四弹窗）平移 AiSettingsPage 顶部 SectionCard「长期记忆」（含入口按钮与记忆列表快照）
- [x] 4.2 AiSettingsViewModel 加 `memoryDir` 真源（FileUtils.getAppDataDir()/memory——与 MemoryManager/agent 装配同一路径）；UiGuard/守护测试过（新增弹窗组件用既有 AppConfirmDialog/AlertDialog）
- [x] 4.3 MemoryManagerTest 仍在 :core 全绿（同源契约钉住）；git commit

## 5. dock 上限 + 收尾

- [x] 5.1 DockManager.MAX_DOCK_ITEMS 9 → 12（注释更新取值理由：现有 9 活动工具页 + 工作台/蒸馏/转写 3 页预留）
- [x] 5.2 守护测试锚清理：NavVisibilityTest/PageUsageTrackerTest 等若含 AiDecrypt 锚同步删除；钉「AI 置灰面为零不炸」断言
- [x] 5.3 端到端验收：`./gradlew :composeApp:run` 手动过欢迎页三分组渲染 + dock/应用中心形态不变（截图管线 xwd+ffmpeg 留档）；全模块守护终跑全绿；git commit（本批收口）

## 验证方式（apply 阶段执行）

- 全模块守护 `--rerun-tasks --no-build-cache` 全绿
- 欢迎页三类分组卡渲染正确（3 卡、顺序工具→智能体→入口、组内条目与注册一致）
- 万能解密全链路归零（导航/dock/命令面板/应用中心/文档），fs.* 工具族与桥接链行为零回归
- AI 设置页记忆面板四动作可用（与 agent 记忆同源——MemoryManagerTest 背书）
