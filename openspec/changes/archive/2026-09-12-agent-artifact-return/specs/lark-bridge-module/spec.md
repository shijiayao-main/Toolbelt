# Delta: lark-bridge-module

## ADDED Requirements

### Requirement: agent 产物回传（标记约定 + 文件消息）

桥接 SHALL 以「system prompt 约定 + 末尾标记行」的通用协议回传 agent 产物文件，不逐技能定制：

**注入**：agent 后端构造时 SHALL 按配置 `agent.artifacts.enabled` 在 effective system prompt 末尾条件追加独立的「产物交付约定」段（告知 agent：任务产出报告/结果文件时，在最终回复**最末尾**每行一条 `[产物] <文件绝对路径>`；未产出则不加）。该段 SHALL 与安全提示段相互独立（安全提示关闭时产物约定仍注入）。

**解析**：桥接 SHALL 只从 agent 最终回复的**末尾连续块**提取标记行——从尾部越过空行后必须命中标记行才回溯收集连续标记块；正文中间出现的 `[产物]` 字样 SHALL 不剥离、不解析（防 code block / 引文误伤）；空白路径条目跳过。

**校验与上传**：每条路径 SHALL 依次通过 tilde 展开 → 文件存在 → `WorkspacePolicy` canonical 沙箱校验（路径来自 agent 文本绝不信任；空 roots fail-safe 拒绝所有）→ 大小上限（`agent.artifacts.maxSizeBytes`）→ 数量上限（`agent.artifacts.maxCount`）→ 扩展名白名单（`agent.artifacts.allowedExtensions`，空 = 不限），全部通过后经出站出口以**两步上传**回传（`uploadFile` 得 file_key → raw client 直发 `msg_type="file"` + `content={"file_key":…}`——🔴 SDK `SendInput.file` 首参语义是本地路径/URL，不可用于 file_key），两步各计一次频控名额（上传步 acquire 计点；限流识别由网关侧 `GatewaySignal.RateLimited` 信号驱动退避）。

**降级**：仅任务状态为 SUCCESS 且 `enabled=true` 时进入处理；任何校验或上传失败的条目 SHALL 把标记行原样还原到正文末尾（附原因注释，用户至少可见绝对路径）；解析/编排整体异常 SHALL 降级为原文照发（🔴 `CancellationException` 必须显式 rethrow——取消/超时落在上传窗口时终态不错乱）；交付期每次出站 SHALL 先 `progress.touch()` 喂空闲看门狗。agent 未写标记行 ⇒ 零上传零副作用（与现状行为一致）。

**审计**：成功回传的产物清单 SHALL 写入回合审计记录（`attachments`，格式 `<文件名> <- <原路径>`；旧格式审计行 decode 为 null）。

**配置**（`agent.artifacts` 4 字段，全 HOT）：`enabled`（默认 true）、`maxCount`（1..10，默认 3）、`maxSizeBytes`（默认 26214400 = 25MB，MB 显示口径）、`allowedExtensions`（逗号分隔，默认空）。带默认值新增键 SHALL 走 typed 层默认值兜底，不 bump schemaVersion。

**网关契约**：`OutboundKind` SHALL 含 FILE；`MessageGateway` SHALL 暴露 `suspend fun uploadFile(source: Path, displayName: String?): String?`（默认实现返回 null 表示不支持，替身与测试假件零破坏）；SDK 实现走 `im().v1().file().create`（fileType="stream"）。

#### Scenario: 成功回合产物以文件消息回传

- **WHEN** agent 成功完成日志分析任务，最终回复末尾含 `[产物] /workspace/reports/analysis_result_x.md`
- **THEN** 该文件经沙箱校验后以飞书文件消息发送给用户，文字回复剥离标记行且正文保留

#### Scenario: agent 不写标记行则零副作用

- **WHEN** agent 正常回复且末尾无任何标记行（或 `enabled=false`）
- **THEN** 零上传、正文逐字节原样，行为与该功能不存在时一致

#### Scenario: 正文中间的标记字样不误伤

- **WHEN** agent 回复正文（非末尾）里引用了 `[产物] 示例` 字样且末尾无标记块
- **THEN** 正文原样保留，该字样不触发解析与上传

#### Scenario: 沙箱外路径拒绝上传并还原告知

- **WHEN** 标记行路径经 canonical 校验在 WorkspacePolicy 之外（或文件不存在 / 超大小 / 超数量 / 扩展名不匹配）
- **THEN** 不上传该条目，正文末尾以「以下产物未能回传」段还原标记行（附原因），文字回复不受阻

#### Scenario: 上传或解析异常不阻断文字回复

- **WHEN** uploadFile 返回失败（如无 im:resource 权限）或编排过程抛异常
- **THEN** 文字回复照常送达（失败条目还原为标记行 / 整体原文照发），服务不中断

#### Scenario: 审计记录携带产物清单

- **WHEN** 一条产物成功回传
- **THEN** 该回合审计行含 `attachments`（`<文件名> <- <原路径>`）；旧格式审计行读回时 attachments 为 null 不报错
