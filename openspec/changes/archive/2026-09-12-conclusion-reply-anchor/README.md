# conclusion-reply-anchor

激活 `Outbound.replyToMessageId` 死字段：GatewayReplySink 构造注入锚定 id（text/markdown/card/file 四链一处生效）；群聊锚定提问消息、私聊豁免（replyAnchorOf 唯一判定）；sendFileByKey 加 reply 分支 + TARGET_REVOKED 降级 create（产物不丢）
