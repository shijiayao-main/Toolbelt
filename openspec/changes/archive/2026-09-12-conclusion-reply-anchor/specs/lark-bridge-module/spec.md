# Delta: lark-bridge-module

## ADDED Requirements

### Requirement: 结论引用锚定（群聊锚定、私聊豁免）

桥接的出站回复链 SHALL 支持把回复消息锚定为对提问消息的「引用回复」，用于群聊中定位结论归属：

**注入**：`GatewayReplySink` SHALL 以构造参数接收 `replyToMessageId: String?`，在私有 `send()` 出口一处以 `out.copy(replyToMessageId = ...)` 注入——text / markdown / card / file 四条出站链 SHALL 一次全部继承锚定（含拒绝回复与未命中兜底），后续新增出站链路 SHALL 天然携带。

**豁免**：锚定值 SHALL 由 `RequestContextFactory.replyAnchorOf(message)` 唯一判定：**群聊**（`chatType != P2P`）取提问消息 `messageId`；**私聊**（P2P）SHALL 返回 null（豁免——一对一场景引用气泡是噪音）；🔴 **卡片回传**（`kind == CARD_ACTION`）SHALL 一律返回 null——`fromCardAction` 硬编码 `chatType = UNKNOWN`（卡片回传事件不提供 chatType），仅靠 P2P 判定时整条卡片回传路径的豁免恒失效（私聊里点确认按钮的裁决回复会被错误锚定）；锚定语义是「锚到用户的**提问**」，卡片回传是用户对机器人的交互、不是提问，故一律不锚定。默认路径（`EventIntake`）与生产路径（`BridgeAssembler` replyOverride）两个 sink 构造点 SHALL **调用同一函数**取值（SHALL NOT 内联复制判定——双写会使测试背书的判定与生产运行的判定静默分叉）。

**file 链 reply 分支**：`SdkLarkGateway.sendFileByKey` SHALL 在 `replyToMessageId` 非空时优先走 reply endpoint（`im().v1().message().reply`，`msgType="file"`）；为空时保持 create 直发。reply 失败且错误码属 TARGET_REVOKED 类（230020 消息被撤回 / 230017 无权限 / http 404）SHALL 降级 create 重发——**产物不丢优先于锚定完整**；其余失败码 SHALL 原样走既有出站错误处理，不吞不静默。

**接受边界**：长结论分片只有首片锚定（SDK 内置 `i==0`）；markdown 走 reply 先转 post、被拒自动降级纯文本且保留锚定（SDK 内置行为）；进度流式卡不在锚定范围（过程态无需归属定位，`CardStreamPort` 不扩签名）。

#### Scenario: 群聊结论引用提问消息

- **WHEN** 用户在群聊发消息触发 agent 任务成功完成
- **THEN** 结论文本消息以「引用回复」形式锚定到该提问消息（outbound 携带提问 messageId），群里可定位这是谁的结论

#### Scenario: 私聊豁免不产生引用样式

- **WHEN** 同一提问发生在私聊（chatType == P2P）
- **THEN** `replyAnchorOf` 返回 null，结论以普通消息直发，与现状行为一致

#### Scenario: 卡片回传不锚定

- **WHEN** 用户在私聊（或群聊）点确认卡的「允许」按钮，cardAction 事件经 `fromCardAction` 进入（chatType 恒 UNKNOWN）
- **THEN** 裁决回复不携带锚定（不会被错误挂到确认卡上）——卡片回传不是提问

#### Scenario: 文件消息同样锚定且降级不丢

- **WHEN** 群聊回合带产物回传，file 消息走 reply endpoint 失败且错误码为 230020（提问被撤回）
- **THEN** 自动降级 create 重发该文件消息（产物不丢），不再带引用关系；其余错误码原样上抛走既有错误处理

#### Scenario: 四条出站链一处注入处处生效

- **WHEN** 同一回合产生 text 结论、markdown 结论、拒绝回复、file 产物四种出站消息且群聊锚定非空
- **THEN** 四条消息全部携带同一 `replyToMessageId`（无需各链路单独接线）
