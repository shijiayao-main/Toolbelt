# Proposal: conclusion-reply-anchor

## Why

真机使用反馈（2026-09-12）：群里多人提问时，AI 的结论与回传的文件消息「不知道在回答谁」——结论混在消息流里无法定位归属，文件消息更是完全无上下文。

调研定性：`Outbound.replyToMessageId` 是**死字段**——消费方存在（`SdkLarkGateway` 的 text/markdown 链按它分流 reply endpoint），但全仓**零生产者**（没有任何代码给它赋值）；SDK reply endpoint 支持 `msg_type="file"`（javadoc 列明）。即链路已铺好，只差在出口把锚定 id 注入。

用户拍板：**群聊锚定、私聊豁免**（群聊需要定位「这是谁的结论」，私聊本来就一对一，引用气泡反而是噪音）。

## What Changes

- `GatewayReplySink` 构造注入 `replyToMessageId: String? = null`；私有 `send()` 一处 `out.copy(replyToMessageId = ...)`——text / markdown / card / file 四条出站链一次全继承锚定（拒绝回复、未命中兜底顺带覆盖）。
- 两个 sink 构造点传值：
  - `RequestContextFactory.replyAnchorOf`（companion 静态）：`if (chatType == P2P) null else messageId`——**私聊豁免**的唯一判定点。
  - `EventIntake` 默认路径与 `BridgeAssembler` replyOverride 生产路径两处 GatewayReplySink 构造均传 `replyAnchorOf(message)`。
- `SdkLarkGateway.sendFileByKey` 拆 reply 分支：anchor 非空先走 reply endpoint（`ReplyMessageReq{messageId=anchor, body{msgType="file", content}}`）；reply 失败且错误为 TARGET_REVOKED 类（code 230020/230017 或 http 404）时降级 create 重发——镜像 SDK `sendOneWithFallback` 的 target-gone 降级，产物不丢。

## Impact

- 群聊里结论文本与文件消息均以「引用回复」气泡挂到提问消息下；私聊行为与现状完全一致（无引用样式）。
- 提问被撤回（TARGET_REVOKED）时文件消息自动降级 create 直发，不丢产物。
- 已知边界（接受）：结论 >3500 字分片只**首片**锚定（SDK 内置 `i==0` 逻辑）；markdown 走 reply 先转 post、被拒自动降级纯文本且保留锚定（SDK 内置）。进度流式卡不在本批（过程态无需锚定）。
