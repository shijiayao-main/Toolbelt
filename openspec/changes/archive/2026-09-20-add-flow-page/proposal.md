# Proposal

## Why

Quest 域 change 1（ask_user 工具 + flows 表基建）已落地，本 change 补「流程页」——
维护者设想的用户可见面：开新流程 / 选历史流程（可续聊）→ 挑能力 → agent 主动发卡
索要信息 → 执行会话。这是「MCP 后面的大活」的产品本体（plan v3 已批：文案「流程」/
域名 Quest）。

## What Changes

- 新 Gradle 模块 `:feature-quest`：流程页两态（左栏历史列表 + 主区新建表单/会话流）；
- `QuestSession`（WorkbenchSession 五件套骨架 + ChatMemory 挂接 + ask 桥 + reducer）；
- `QuestHistoryProvider`（桥接 SessionHistoryProvider 纪律平移：store 滤 System +
  窗口封顶 + 写穿透 SqliteChatHistoryProvider）；
- `QuestCapabilities` 硬编码能力目录（本 change 落**空目录骨架 + 通用对话隐式能力**；
  日志分析/语音纪要两能力在 change 3 接线）；
- `:ui` 新增 `MultiChoiceField`（ChoiceField FilterChip 流平移多选）；
- 导航注册：`ToolPage.Flow` 枚举 + FlowContributions（group=AGENTS 智能体组）+
  ToolPageRegistry 拼接 + aiEnabled/navActivity 宿主注入。

## Capabilities

### New Capabilities
- `flow-page`: 流程页（新建/历史/能力挑选/会话流/AskCard 表单卡/历史续聊）

### Modified Capabilities
- `navigation-grouping`: 智能体组新增「流程」成员（第三位——AI 设置/日志工作台/知识
  蒸馏/语音转写后并列）
