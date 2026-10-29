# Proposal: mcp-expose（MCP 外放）

## Why

总蓝图最后一批：把本地工具与 agent 能力经 MCP 协议递给外部消费（Claude Code / IDE 等 MCP client），与飞书桥接并列「入口」组——本地能力对外暴露的第二条通道。维护者已拍板：能力面 = 工具 + agent 会话全外放；协议实现 = 官方 Kotlin SDK（`io.modelcontextprotocol:kotlin-sdk-server` v0.15.0，JVM 11+）。

## What Changes

- **新 feature 模块 `:feature-mcp`**：MCP 服务页（入口组第二成员——注册序 LarkBridge 后）。streamable HTTP 端点（`embeddedServer(CIO)` + `mcpStreamableHttp`，127.0.0.1 绑定 + 可配端口，默认关）把本地能力外放。
- **工具面外放**：MCP tools 映射 `AgentToolCatalog` 目录（xlog 解密 / log.analyze_log 确定性管线 / fs.extended 取证七件 / knowledge.* 检索三件 / log-tools 管线）——外部 client 直接调用，经 `ToolExecutionContext` 围栏（授权根目录 = 源码目录 allowlist；出围栏 deny + reason）。
- **agent 会话外放**：研判 agent（log-workbench 同款工具集 + 十铁律提示词）与蒸馏 agent 经 MCP tools 触发——Toolbelt 内跑 KoogToolLoop、进度经 MCP progress 通知上报、报告文本回传。
- **服务生命周期**：AppHost 参与者（AppLifecycleParticipant）——页面开关启停 server，App 退出优雅停；端口配置经 `:core` AppDataStore 单点。
- **MCP 服务页 UI**：状态卡（运行态/端口/连接计数）+ 开关 + 端点 URL 一键复制 + 工具清单浏览（描述符面直读）+ 连接日志台。
- **依赖引入**：`kotlin-sdk-server` 0.15.0 + Ktor server CIO 引擎 + ktor-client（SDK 运行时需要）——SDK 0.x API 未稳定，类型隔离守护钉死在装配层（SherpaOnnxIsolation/LarkSdkIsolation 模式）。
- **修改 capability `navigation-grouping`**：入口组 1 → 2 成员（MCP 服务页进组的 scenario 更新）。

## Capabilities

- **New Capabilities**：
  - `mcp-expose`——MCP 外放服务页行为契约（端点/工具面/agent 会话/安全面）
- **Modified Capabilities**：
  - `navigation-grouping`——入口组成员扩展（1→2）

## Impact

- **新增**：`:feature-mcp` 模块（convention + settings include + composeApp 依赖）；`ToolPage.McpExpose` 枚举值（LarkBridge 后 Debug 前）；`ToolPageGroup.ENTRY` 组第二成员
- **依赖新增**：`io.modelcontextprotocol:kotlin-sdk-server:0.15.0` + `io.ktor:ktor-server-cio` + `io.ktor:ktor-client-cio`（版本进 `libs.versions.toml`）
- **复用**：`AgentToolCatalog.buildRegistry`（工具面装配）、`WorkbenchPrompts`（研判提示词）、`ToolExecutionContext`（围栏）、`AppHost`/`AppLifecycleParticipant`（生命周期）、`LlmConfigStore`（端点 + 源码目录 allowlist）
- **不影响**：桥接链/工作台/语音转写既有会话（MCP 会话独立装配，工具目录共享但只读）
- **验收**：MCP Inspector 连通 + 工具清单/调用全链 + agent 会话外放（fake LLM 单测）+ 隔离守护 + 全模块守护 `--rerun-tasks --no-build-cache` 全绿
