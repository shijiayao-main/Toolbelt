# Design: conclusion-reply-anchor

## Context

死字段取证（2026-09-12）：`Outbound.replyToMessageId` 的消费方全在 `SdkLarkGateway`——text/markdown 链已有 `if (replyToMessageId != null) reply(...) else create(...)` 分流、且自带 TARGET_REVOKED（230020/230017/404）降级 create 重发（镜像 SDK `sendOneWithFallback`）；file 链（`sendFileByKey`）只有 create。**零生产者**：全仓没有任何代码给该字段赋值，唯一接近的先例是表情链在 `BuiltinHandlers` 里临时拼 `replyTo` 的局部变量。SDK reply endpoint javadec 明确支持 `msg_type="file"`。

## Decisions

### D1. sink 构造注入而非调用点逐处传（方案 A）

候选对照：
- **A. 构造注入**：`GatewayReplySink` + 构造参数，私有 `send()` 一处 `copy`——四条出站链（text/markdown/card/file）自动全继承，未来新增链路也天然带锚定；改动面 2 个构造点。
- B. 出站消息逐处 copy：每条链每个调用点都要写，覆盖面靠人肉保证，遗漏即静默丢锚定。

A 的「一处注入、处处生效」正是死字段该有的激活方式。

### D2. 私聊豁免判定收敛到 `RequestContextFactory.replyAnchorOf`

`if (chatType == P2P) null else messageId` 做成 companion 静态函数：两个构造点（EventIntake 默认路径 / BridgeAssembler replyOverride 生产路径）共用同一份判定，豁免语义只写一次；测试可直接静态调用断言。锚定 id 来源与表情链先例一致（`message.messageId`，不涉及 quoted 层）。

### D3. file 链 reply 失败降级而非放弃

- reply 分支失败码属 TARGET_REVOKED 类（230020 消息被撤回 / 230017 无权限读 / http 404）→ 降级 create 重发——**产物不丢**优先于锚定完整性。
- 其余失败码保持原样抛出（走既有出站错误处理），不吞不静默。

### D4. 分片只锚首片（接受，不修）

长结论分片发送时 SDK 内置只有 `i==0` 首片带 reply 关系——后续片是平铺消息。修它要绕过 SDK 封装自建分片循环，收益（群聊里第 2 片引用气泡）不抵扰动面；首片已足够定位归属。

## Risk / Trade-offs

- 群聊里「拒绝回复」「未命中兜底」等系统消息也会带引用气泡——语义上正确（它们同样是对该提问的响应），非 bug。
- 进度流式卡不锚定：`CardStreamPort` 无 replyTo 参数，扩面 5 处签名只为过程态加引用，收益太小，按需二开。
- 群聊判断依赖 `chatType` 字段可靠（长连接事件自带，与表情链同一数据源）。

## Migration Plan

无配置、无 schema 变更、无迁移。落地序：sink 构造参数 + send() copy → replyAnchorOf + 两构造点接线 → sendFileByKey reply 分支 → SyntheticGateway 断言测试。
