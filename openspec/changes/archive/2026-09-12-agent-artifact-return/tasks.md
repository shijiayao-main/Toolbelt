# Tasks: agent-artifact-return

## 1. 网关契约：FILE 出站 + 两步上传

- [x] 1.1 `GatewayModel`：`OutboundKind` +`FILE`、`OutboundFileSpec(fileKey, fileName)`、`Outbound.file(...)` 工厂与 init 不变式三分支。验证：`./gradlew :lark-gateway:compileKotlin`
- [x] 1.1a 🔴 P0 评审修复：FILE 第二步**不走** `ch.send(SendInput.file(...))`（SDK 语义首参是本地路径/URL，file_key 必抛 UPLOAD_FAILED）——改 `sendFileByKey` raw client 直发 `msg_type="file"` + `content={"file_key":…}`（sendCardByReference 同款 raw 模式）。验证：`./gradlew :lark-gateway:test`
- [x] 1.2a 🔴 P2 评审修复：`uploadFile` 响应码映射为 RateLimited 时双发 `GatewaySignal.RateLimited`（镜像 LarkCardStreamSink.check）；`GatewayReplySink.file` 上传步改 `acquire + 直调`（不走 guard——String? 返回值无法判限流，恒 null 回调会误 notifySuccess 清退避）。验证：`./gradlew :lark-bridge:test`
- [x] 1.2 `MessageGateway` +`suspend fun uploadFile(source: Path, displayName: String?): String?` 带默认实现（返回 null）——SyntheticGateway/假件零破坏。验证：`SyntheticGatewayTest` 反射穷尽断言 14→15 成员同步后全绿
- [x] 1.3 `SdkLarkGateway`：`uploadFile` 实现走 raw client `im().v1().file().create`（`CreateFileReqBody(fileType="stream", fileName, file)`）返回 `fileKey`；`send()` FILE 分支见 1.1a（P0 修复后为 `sendFileByKey` raw 直发，非 `SendInput.file`）。验证：编译通过（真机项见 5.x）
- [x] 1.4 `LarkOutboundMapper`：FILE 分支 msgType="file"、contentJson `{"file_key": ...}`（编译器穷尽 when 强制补全）。验证：`./gradlew :lark-gateway:test`

## 2. 出站出口与协议解析

- [x] 2.1 `ReplySink` +`suspend fun file(path, displayName?): SendResult`；`GatewayReplySink` 实现两步各过 RateGovernor；`ReplySink.kt:20-25` 契约缺口注释更新为已闭合。验证：`./gradlew :lark-bridge:compileKotlin`
- [x] 2.2 `agent/Artifacts.kt`（新建）：`ArtifactMarkers.extract`（末尾连续块两段扫描，容忍残缺标记行）+ `deliverArtifacts`（expandTilde → 沙箱 → 存在/大小/数量/扩展名 → 上传；失败条目还原 `<!-- 原因 -->`）+ `ArtifactsConfigView`/`ReplyFileSink` 窄接口。验证：`ArtifactsTest` 14 用例全绿
- [x] 2.2a 🔴 P1 评审修复：`withArtifactsDelivered` 的 catch **显式 rethrow CancellationException**（E-1 纪律，停止/超时落在上传窗口时表情与终态不错乱）；每次出站前 `ctx.progress.touch()` 喂空闲看门狗（多文件慢速上传不被 120s 误杀）。验证：`./gradlew :lark-bridge:test`
- [x] 2.2b 🔴 P2 评审修复：`isExtensionAllowed` 无点条目自动补点（"md"→".md"，防 "md" 误匹配 "cmd"）。验证：`ArtifactsTest` 全绿
- [x] 2.3 `Backends.artifactsPromptSuffix(enabled)`：`[产物交付约定]` 段注入，独立于 safetyPrompt（关闭安全提示仍注入）。验证：`SafetyPromptTest` 新增 3 条产物约定断言全绿

## 3. 接线与审计

- [x] 3.1 `BuiltinHandlers.runAgentPipeline` 内 `withArtifactsDelivered(ctx)`：仅 SUCCESS && enabled 进入，runCatching 降级原文照发；成功清单写 `outcome.attachments`。验证：`ArtifactUploadPipelineTest` 5 用例全绿
- [x] 3.2 `ObserveSeams`/`AuditLog`：`SinkRecord.Audit`/`AuditEntryJson` +`attachments: List<String>?`；`BridgeAssembler` onOutcome 接线。验证：`AuditLogTest` schema 11→12 字段断言同步后全绿
- [x] 3.3 `PipelineTestSupport.RecordingReplySink` 加 `file()` 记录实现。验证：`./gradlew :lark-bridge:test`

## 4. 配置域（agent.artifacts 4 字段，全 HOT 全 ADVANCED）

- [x] 4.1 `BridgeConfig.AgentConfig` +`artifacts: ArtifactsConfig`（enabled=true/maxCount=3/maxSizeBytes=25MB/allowedExtensions=""）；schemaVersion 不 bump。验证：`BridgeConfigDefaultsTest`/`RoundTripTest` 同步后全绿
- [x] 4.2 `BridgeFields` +4 字段（enabled=Switch、maxCount=Number 1..10、maxSizeBytes=Number MB 显示、allowedExtensions=Text）；tier 默认 ADVANCED。验证：`BridgeFieldsTest` 65 口径全绿
- [x] 4.3 守护测试口径同步：`BridgeFieldsTest`（65 字段/AGENT 21/Number 22/Switch 17/Text 10/ADVANCED 60）、`CheckCommandTest`（65）、`SyntheticGatewayTest`（15 成员）、`AuditLogTest`（12 字段）。验证：`./gradlew :lark-bridge:test` 全绿

## 5. 批次验收

- [x] 5.1 `./gradlew :lark-bridge:test :lark-gateway:test :feature-larkbridge:jvmTest :composeApp:jvmTest` 全绿（975 + gateway + feature + composeApp 基线 + 新增 22 用例）；扫盘守护 `--rerun-tasks --no-build-cache` 通过
- [x] 5.2 `docs/pages/lark-bridge.md` 同步：字段表 61→65、产物回传小节（配置项 + 标记约定 + 失败降级说明）
- [x] 5.3 仓库外配套：外部 log-helper skill 文档（个人环境，未入库） Step 7 补产物约定一句话
- [x] 5.4 真机验证（2026-09-12 维护者确认真机跑过）：① 飞书后台授予 `im:resource` 上传权限后 30MB 内上传成功；② 无权限时错误码 + 降级（文字回复仍达、标记行还原）；③ log-helper 跑真实日志分析，`analysis_result_*.md` 以文件消息回传；④ `enabled=false` 后与现状逐字节一致
