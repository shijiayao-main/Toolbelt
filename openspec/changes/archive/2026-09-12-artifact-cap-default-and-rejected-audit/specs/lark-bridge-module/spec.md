# Delta: lark-bridge-module

## MODIFIED Requirements

### Requirement: agent 产物回传（标记约定 + 文件消息）

桥接 SHALL 以「system prompt 约定 + 末尾标记行」的通用协议回传 agent 产物文件，不逐技能定制：

**注入**：agent 后端构造时 SHALL 按配置 `agent.artifacts.enabled` 在 effective system prompt 末尾条件追加独立的「产物交付约定」段（告知 agent：任务产出报告/结果文件时，在最终回复**最末尾**每行一条 `[产物] <文件绝对路径>`；未产出则不加）。该段 SHALL 与安全提示段相互独立（安全提示关闭时产物约定仍注入）。

**解析**：桥接 SHALL 只从 agent 最终回复的**末尾连续块**提取标记行——从尾部越过空行后必须命中标记行才回溯收集连续标记块；正文中间出现的 `[产物]` 字样 SHALL 不剥离、不解析（防 code block / 引文误伤）；空白路径条目跳过。

**校验与上传**：每条路径 SHALL 依次通过 tilde 展开 → 文件存在 → `WorkspacePolicy` canonical 沙箱校验（路径来自 agent 文本绝不信任；空 roots fail-safe 拒绝所有）→ 大小上限（`agent.artifacts.maxSizeBytes`）→ 数量上限（`agent.artifacts.maxCount`）→ 扩展名白名单（`agent.artifacts.allowedExtensions`，空 = 不限），全部通过后经出站出口以**两步上传**回传（`uploadFile` 得 file_key → raw client 直发 `msg_type="file"` + `content={"file_key":…}`——🔴 SDK `SendInput.file` 首参语义是本地路径/URL，不可用于 file_key），两步各计一次频控名额（上传步 acquire 计点；限流识别由网关侧 `GatewaySignal.RateLimited` 信号驱动退避）。

**降级**：仅任务状态为 SUCCESS 且 `enabled=true` 时进入处理；任何校验或上传失败的条目 SHALL 把标记行原样还原到正文末尾（附原因注释，用户至少可见绝对路径）；解析/编排整体异常 SHALL 降级为原文照发（🔴 `CancellationException` 必须显式 rethrow——取消/超时落在上传窗口时终态不错乱）；交付期每次出站 SHALL 先 `progress.touch()` 喂空闲看门狗。agent 未写标记行 ⇒ 零上传零副作用（与现状行为一致）。

**审计（2026-09-12 artifact-cap-default-and-rejected-audit 扩容）**：成功回传的产物清单 SHALL 写入回合审计记录（`attachments`，格式 `<文件名> <- <原路径>`）；**被拒条目清单 SHALL 写入同条审计记录的 `rejectedArtifacts`**（元素即还原到正文的同款行：`[产物] <路径>  <!-- 原因 -->`——一份数据两处消费，正文供用户即时可见、审计供事后取证）。两者均沿「后加可空字段」惯例：`List<String>? = null`，旧格式审计行 decode 为 null 不破坏；空清单落 null 不产空键。

**配置**（`agent.artifacts` 4 字段，全 HOT）：`enabled`（默认 true）、`maxCount`（1..10，**默认 5**——2026-09-12 真机取证：AVM 多事件 skill 按 N+1 产出（4 事件 = 5 份），默认 3 会把按序末尾的汇总报告截断；上限的存在意义是防 agent 失控刷文件雨，不是压正常场景，5 恰好覆盖典型值而未拉满 10）、`maxSizeBytes`（默认 26214400 = 25MB，MB 显示口径）、`allowedExtensions`（逗号分隔，默认空）。带默认值键改默认 SHALL 走 typed 层默认值兜底，不 bump schemaVersion；存量文档已显式写值的用户配置不受默认变更影响。

**网关契约**：`OutboundKind` SHALL 含 FILE；`MessageGateway` SHALL 暴露 `suspend fun uploadFile(source: Path, displayName: String?): String?`（默认实现返回 null 表示不支持，替身与测试假件零破坏）；SDK 实现走 `im().v1().file().create`（fileType="stream"）。

#### Scenario: 成功回合产物以文件消息回传

- **WHEN** agent 成功完成日志分析任务，最终回复末尾含 `[产物] /workspace/reports/analysis_result_x.md`
- **THEN** 该文件经沙箱校验后以飞书文件消息发送给用户，文字回复剥离标记行且正文保留

#### Scenario: 默认上限下 AVM 五份产物全量回传

- **WHEN** agent 回合末尾产出 5 条标记行（4 单事件 + 1 汇总），配置文档未显式写 `agent.artifacts.maxCount`
- **THEN** 5 份全部回传，零截断，审计行 `attachments` 含 5 项且 `rejectedArtifacts` 为 null

#### Scenario: 超限被拒条目落审计可取证

- **WHEN** agent 产出 7 条标记行而 `maxCount=5`，按序第 6、7 条被「超出数量上限」拒绝
- **THEN** 5 份照常回传；正文末尾还原 2 条被拒行（附原因），同回合审计行 `rejectedArtifacts` 含这 2 条同款还原行——失败取证不依赖翻飞书聊天记录

#### Scenario: agent 不写标记行则零副作用

- **WHEN** agent 正常回复且末尾无任何标记行（或 `enabled=false`）
- **THEN** 零上传、正文逐字节原样，行为与该功能不存在时一致

#### Scenario: 正文中间的标记字样不误伤

- **WHEN** agent 回复正文（非末尾）里引用了 `[产物] 示例` 字样且末尾无标记块
- **THEN** 正文原样保留，该字样不触发解析与上传

#### Scenario: 沙箱外路径拒绝上传并还原告知

- **WHEN** 标记行路径经 canonical 校验在 WorkspacePolicy 之外（或文件不存在 / 超大小 / 超数量 / 扩展名不匹配）
- **THEN** 不上传该条目，正文末尾以「以下产物未能回传」段还原标记行（附原因），被拒行同步落审计 `rejectedArtifacts`，文字回复不受阻

#### Scenario: 旧格式审计行读回不破坏

- **WHEN** 解析不含 `rejectedArtifacts` 键的历史审计行
- **THEN** decode 为 null 不报错；含该键的新行正常读出清单

#### Scenario: 审计记录携带产物清单

- **WHEN** 一条产物成功回传
- **THEN** 该回合审计行含 `attachments`（`<文件名> <- <原路径>`）；旧格式审计行读回时 attachments 为 null 不报错

#### Scenario: 上传或解析异常不阻断文字回复

- **WHEN** uploadFile 返回失败（如无 im:resource 权限）或编排过程抛异常
- **THEN** 文字回复照常送达（失败条目还原为标记行 / 整体原文照发），服务不中断
