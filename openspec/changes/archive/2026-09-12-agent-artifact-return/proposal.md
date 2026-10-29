# Proposal: agent-artifact-return

## Why

agent（claude CLI 子进程）跑 skill 会生成产物文件（如日志分析报告 `analysis_result_*.md`），但只留在磁盘——桥接完全没有「机器人→用户」的文件发送能力：`OutboundKind` 仅 TEXT/MARKDOWN/CARD，`MessageGateway` 无上传成员，`ReplySink.kt:20-25` 显式登记着这个契约缺口。用户想在对话里最后把报告也返回给用户，又不想逐 skill 定制。

拍板方案：**飞书文件消息 + 通用标记约定协议**——桥接向 agent 注入「产物交付约定」（system prompt 约定段），agent 在最终回复末尾每行一条 `[产物] <文件绝对路径>`；桥接解析标记 → 剥离正文 → 校验 → 两步上传（`im:resource` 得 file_key → 发 file 消息）。skill 侧只需知道「照系统提示做」，不感知飞书。

## What Changes

- **网关契约**：`OutboundKind` +`FILE`、`Outbound` +`file: OutboundFileSpec(fileKey, fileName)` 工厂；`MessageGateway` +`suspend fun uploadFile(source: Path, displayName: String?): String?`（带默认实现返回 null，同 `resolveSenderName` 模式，不破 SyntheticGateway）；`SdkLarkGateway` 实现 raw client `im().v1().file().create`（`fileType="stream"`）+ `send()` FILE 分支 `SendInput.file(fileKey, fileName)`。
- **出站出口**：`ReplySink` +`suspend fun file(path, displayName?): SendResult`；`GatewayReplySink` 两步各过一次 RateGovernor（上传 + 发消息是两次 API）。
- **协议与解析**：`agent/Artifacts.kt`（新建）——`ArtifactMarkers.extract` 从文本**末尾向前**收集连续 `[产物] ` 行（正文中间的字样不误伤）；`deliverArtifacts` 编排：expandTilde → `WorkspacePolicy` canonical 沙箱校验 → 存在/大小/数量/扩展名过滤 → 上传；失败条目标记行**原样还原**到正文末尾（带原因注释），文字回复永不阻断。
- **注入**：`Backends.artifactsPromptSuffix(enabled)` 独立于安全提示（safetyPrompt 关闭时产物约定仍可注入）。
- **接线**：`runAgentPipeline` 内 `withArtifactsDelivered(ctx)`——仅 `status==SUCCESS && artifacts.enabled` 时处理，整体 runCatching 降级原文照发；成功清单写 `HandlerOutcome.attachments`。
- **审计**：`SinkRecord.Audit`/`AuditEntryJson` +`attachments: List<String>?`（旧格式行 decode 为 null）。
- **配置域** `agent.artifacts` 4 字段（全 HOT、全 ADVANCED）：`enabled`（默认 true——不写标记行则零行为变化，装完即用）、`maxCount`（1..10，默认 3）、`maxSizeBytes`（MB 显示，默认 25MB——飞书 30MB 硬限内留余量）、`allowedExtensions`（逗号分隔，空=不限）。schemaVersion 不 bump（带默认值新增走 typed 层兜底惯例）。

## Impact

- **代码**：`lark-gateway`（GatewayModel/MessageGateway/SdkLarkGateway/LarkOutboundMapper）、`lark-bridge`（Artifacts 新建、Backends/BuiltinHandlers/ReplySink/ObserveSeams/AuditLog/BridgeConfig/BridgeFields/BridgeAssembler/SyntheticGateway）。
- **契约面扩容**：`MessageGateway` 14→15 成员、`AuditEntryJson` 11→12 字段——守护测试同步。
- **失败模式安全**：agent 不写标记行 ⇒ 零上传零副作用；任何校验/上传失败不阻断文字回复；解析异常整体降级原文照发。
- **仓库外配套**：外部 log-helper skill 文档（个人环境，未入库） Step 7 补一句「若系统提示含产物交付约定则照做」。
- **待真机确认**：`im:resource` 上传权限 scope、30MB 实测上限、`fileType="stream"` 取值、claude 服从标记约定的实跑概率。
