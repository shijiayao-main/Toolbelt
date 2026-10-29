# Design: agent-artifact-return

## Context

agent 子进程的工作目录是沙箱内某个目录，skill 产物（日志分析报告等）落盘在那里，用户在飞书侧只看得到文字 summary——「报告在服务器上但拿不到」。逐 skill 定制回传违反「桥接不感知具体技能」的分层；用户拍板走**通用机制**。

可用底座（本轮调研确认）：
- `Outbound` 出站模型已 sealed 三分（TEXT/MARKDOWN/CARD）+ `LarkOutboundMapper` 穷尽 when——加 FILE 是编译器强制全暴露的常规扩容。
- `MessageGateway` 已有「带默认实现的可选成员」先例（`resolveSenderName` 返回 null 即不支持）——`uploadFile` 同模式，SyntheticGateway/测试假件零破坏。
- SDK 形态已 javap 确认：`im().v1().file().create(CreateFileReq)` → `CreateFileRespBody.fileKey`；`CreateFileReqBody(fileType, fileName, file: java.io.File)`。🔴 发消息**不可用** `SendInput.file`（评审 P0 推翻初判——其首参语义是本地路径/URL，见 D3）；file_key 直发走 raw client `im().v1().message().create`。

缺的是「让 agent 说出产物在哪」的协议。

## Goals / Non-Goals

**Goals**
- 通用：桥接不逐 skill 定制，任何后端/技能只要服从约定就自动回传。
- 失败模式安全：agent 不配合 ⇒ 行为与现状完全一致（零上传零副作用）；上传失败 ⇒ 文字回复照发，失败条目带原因还原给用户。
- 不信任 agent 文本：路径必过 WorkspacePolicy canonical 沙箱校验（防目录穿越/符号链接逃逸）。

**Non-Goals**
- 不做产物目录监听/自动发现（约定协议优先，agent 最清楚自己产出了什么）。
- 不做 >30MB 分卷/压缩（超限条目降级还原，用户可后续自己处理）。
- 不改 qoder/copilot 后端的专用注入（约定段对所有后端同一份 system prompt 追加）。

## Decisions

### D1. 标记约定协议（而非工具回调）

三个候选：① system prompt 约定标记行（拍板）；② 给 agent 发清单工具（要开 MCP，重）；③ 扫描工作区 diff（误报面大，无法区分临时文件与产物）。

选 ①：零新工具面、agent（claude）对 system prompt 约定服从性高、skill 侧零感知（约定对一切任务生效，包括临时手打的对话）。协议文本注入为 system prompt 的独立段（`artifactsPromptSuffix`），与安全提示段相互独立——safetyPrompt 关闭时产物约定仍注入（两开关正交）。

标记行格式：回复**末尾**每行一条 `[产物] <文件绝对路径>`。只认「末尾连续块」——从尾部越过空行后必须命中标记行才回溯收集，正文中间的 `[产物]` 字样不剥离不解析（防 code block / 引文误伤）。

### D2. 校验链与降级

逐条：`expandTilde` → `file.isFile` → `WorkspacePolicy.requireInsideWorkspace`（canonical 校验，空 roots fail-safe 拒绝所有）→ `length() <= maxSizeBytes` → 扩展名白名单 → 上传。任一失败：该条目**还原为标记行 + HTML 注释写明原因**追加到正文末尾「以下产物未能回传：」段下——用户至少能看到绝对路径自己去找。上传结果非 ok 同样还原。

整条 `withArtifactsDelivered` catch Throwable 兜底且**显式 rethrow CancellationException**（E-1 纪律，见 D3 P1 补强）：业务异常 ⇒ 原文照发（产物丢失但回复不丢）；取消信号必须上抛。仅 `status==SUCCESS && artifacts.enabled` 时进入——失败回合的文本可能是错误堆栈，剥离标记反而藏信息。

### D3. 两步上传与频控

`ReplySink.file(path, displayName)` 是唯一出站出口（维持「一律经 RateGovernor」纪律）：`GatewayReplySink` 内部先 `gateway.uploadFile` 再发 file 消息，**两步各计一次频控名额**。SDK 侧 `fileType="stream"`（javap 实测枚举外的字符串形取值，待真机确认）。

🔴 **P0 对抗评审修正（2026-09-12，方案定稿后实测 SDK sources）**：第二步**不能**用 `SendInput.file(fileKey, ...)`——SDK 语义里该工厂首参是「待上传的本地路径/URL」（`OutboundSender.send` → `sendUploadedMedia` → `MediaUploader.toFile` 会对非 http 值做 `Paths.get(...).toRealPath()`，file_key 必抛 UPLOAD_FAILED——真机 100% 失败且第一步已上传成孤儿素材）。正确路径：`sendFileByKey` raw client 直发 `msg_type="file"` + `content={"file_key":…}`（`sendCardByReference` 同款 raw 模式）。上传步不走 `guard`（`String?` 返回值无法判限流，恒 null 回调会误 `notifySuccess` 清退避）——改 `acquire` 计点 + 直调，限流识别由网关侧 `GatewaySignal.RateLimited` 双信号驱动。

🔴 **P1 评审补强**：交付期每次出站前 `ctx.progress.touch()`（多文件慢速上传不被 120s 空闲看门狗误杀）；`withArtifactsDelivered` 的 catch 显式 rethrow `CancellationException`（E-1 纪律——停止/超时落在上传窗口时表情与终态不错乱）。

### D4. 配置域 agent.artifacts（4 字段，schemaVersion 不 bump）

- `enabled` 默认 **true**：协议是「agent 不写标记行 ⇒ 零行为变化」，开着不产生任何副作用，装完即用；关掉则标记行原样留在回复里（用户可见 agent 写了什么）。
- `maxCount` 默认 3：防 agent 失控写几十条把聊天刷成文件雨。
- `maxSizeBytes` 默认 25MB（MB 显示）：飞书 30MB 硬限内留余量。
- `allowedExtensions` 默认空=不限：与 `fileMessage.allowedExtensions` 同名对齐（入站收文件的扩展名白名单），出站对称。
- 带 默认值新增键走 typed 层默认值兜底（v2 时 fileMessage 8 键同模式），**不需要 bump schemaVersion**（v4 已由滚动窗口批次占用）。

### D5. 审计 attachments 激活

`HandlerOutcome.attachments` 此前是激活的零消费字段——本变更接线：成功上传的产物以 `<文件名> <- <原路径>` 格式写入 `SinkRecord.Audit` → `AuditEntryJson.attachments`（可空新增，旧格式行 decode 为 null）。审计里能看到「哪个回合把哪个文件传给了谁」。

## Risk / Trade-offs

- **协议服从性**：claude 偶尔不写标记行 ⇒ 该产物不回传（失败模式安全，无副作用）——真机确认服从率，skill 侧配套一句话提高确定性。
- **`fileType="stream"` 取值**：javap 看不到 SDK 内部对 fileType 的合法值校验，"stream" 来自飞书开放平台文档——真机第一跑确认。
- **30MB 上限**：飞书侧文档值，桥接配 25MB 留余量；真机实测校准。
- **RateGovernor 名额**：一次回传 3 个产物 = 6 个频控名额（3 上传 + 3 发送），4 rps/chat 下约 1.5 秒——可接受。

## Migration Plan

无 JSON 迁移（配置带默认值兜底）。落地顺序：网关契约（FILE/uploadFile）→ 出站出口（ReplySink.file）→ 解析与编排（Artifacts.kt）→ 注入与接线（Backends/BuiltinHandlers）→ 审计 → 配置域与守护测试同步。
