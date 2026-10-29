# Proposal

## Why

万能解密页是形态错配的遗留：确定性文件变换（解密/解包）本不需要 agent 对话输入框，用户已裁定移除（"文件工具里出现对话输入框行为奇怪"、解密/解包已有专属工具页完整覆盖）。同时导航分组仍是宿主侧硬编码三组过滤行（`page in listOf(...)`），无分组一等概念，AI 页与时间戳/解压混排在"小工具与集成"组——用户裁定的产品叙事（确定性基座/agent 大脑/接口层）需要导航层能被读懂。两者同批落地：移除页与新分组互为对方的归属清理，拆开做会各留一半过渡态。

## What Changes

- **导航三类分组数据驱动化**：`ToolPageRegistration` 契约新增 `group: ToolPageGroup`（三值枚举 TOOLS 工具 / AGENTS 智能体 / ENTRY 入口）；宿主 `ToolPageRegistry.navSections` 删除硬编码 `listOf(...)` 过滤行，欢迎页分组卡按各 feature registration 自带 group 字段渲染。分组语义 = 功能核心价值来源：确定性变换=工具、agent 推理=智能体、外部消费入口=入口。dock 窄轨与应用中心**不分区**，维持同格展示（智能体组成员带 AI 角标，既有机制不变）。
- **全部现有页面归属重分**：工具组 = XLog 解密 / Tag 过滤 / 文件分割 / 解压 / 时间戳 / Shell 工具；智能体组 = AI 设置（agent 域控制台）；入口组 = 飞书桥接。欢迎页/应用中心/设置页/调试页为结构页不进分组。
- **移除万能解密页**（**BREAKING**，用户裁定）：删除 AiChatPage / AiChatSession / AiChatViewModel / AiChatHub / LLM 判型链（classifyWithLlm / localFallbackDecision）/ `ToolPage.AiDecrypt` 枚举值 / AiContributions 的页面注册。桌面侧暂时没有通用 agent 会话页（后续日志工作台/知识蒸馏页为对象绑定的专用会话页）。
- **平台层资产保留**（不为页面陪葬）：fs.* 工具族（桥接链在用）、AgentToolCatalog 全部 24 件、KoogToolLoop、SessionStore/SqliteSessionStore + sessions/messages DB 表（休眠待工作台 run 历史复用）、memory.* 四件工具 + MemoryManager、AiSettingsPage / AiToolsToggle / 模型目录组件。
- **记忆面板迁移**：万能解密页头的记忆面板四动作（P3-E：查看/编辑/删除/清空）整面平移到 AI 设置页（MemoryManager 同源读写样板不变）——AI 设置页成为 agent 域控制台。
- **FileSniffer 下沉 :core**：万能解密的文件嗅探判型（FileSniffer/FileTypeDecision）挪至 core/llm/tools 供后续日志工作台管线复用，feature-ai 内删除副本。
- **DockManager 上限修复**：`MAX_DOCK_ITEMS = 9` 恰等于当前注册全量数（物化即满员），抬至 12（为日志工作台/知识蒸馏/语音转写三个新页预留）。

## Capabilities

### New Capabilities

- `navigation-grouping`: 导航分组一等概念——ToolPageRegistration 的 group 字段契约（三值枚举、默认值、结构页豁免）与欢迎页分组卡的数据驱动渲染规则。
- `ai-settings-console`: AI 设置页作为 agent 域控制台的记忆管理面——四动作（查看/编辑/删除/清空）从万能解密页头整面迁入，与 agent 记忆工具同源读写。

### Modified Capabilities

- `welcome-page`: 分组真源从"宿主硬编码导航分组"改为"registration.group 字段聚合"；分组数量从固定 3 组（日志分析/Shell 工具/小工具与集成）变为三类分组语义（工具/智能体/入口）；提示卡 Scenario 随之更新。

### Removed/Unchanged 说明

- 万能解密页：无既有 spec 引用（全库 grep 零命中），纯删除不产生 delta。
- app-settings（宿主设置页）：本批不改动其行为，不产生 delta。
- dock / 应用中心 / 命令面板：分组不分区（渲染零改动），不立 delta；使用计数与 frecency 以 ToolPage 枚举为 key，页面级粒度不变。
