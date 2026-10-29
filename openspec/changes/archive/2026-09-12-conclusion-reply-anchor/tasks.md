# Tasks: conclusion-reply-anchor

## 1. sink 构造注入（方案 A）

- [x] 1.1 `GatewayReplySink` +构造参数 `replyToMessageId: String? = null`；私有 `send()` 开头 `out.copy(replyToMessageId = replyToMessageId ?: out.replyToMessageId)`——text/markdown/card/file 四链一处全锚定。验证：`./gradlew :lark-bridge:compileKotlin`
- [x] 1.2 `RequestContextFactory.replyAnchorOf`（companion 静态）：`chatType == P2P ? null : messageId`。验证：静态调用编译通过
- [x] 1.3 生产构造点接线：`EventIntake` 默认路径 + `BridgeAssembler` replyOverride——🔴 后者由内联副本改为**调用同一函数**（对抗评审：双写会让测试守的判定与生产跑的判定分叉）。验证：`BridgeAssemblerParityTest ⑧` 源码守卫 + `ReplySinkAnchoringTest`
- [x] 1.4 卡片回传豁免（对抗评审补）：`kind == CARD_ACTION` ⇒ null（`fromCardAction` 硬编码 chatType=UNKNOWN，P2P 判定对整条路径恒失效）。验证：`卡片回传一律不锚定` 用例

## 2. file 链 reply 分支

- [x] 2.1 `SdkLarkGateway.sendFileByKey`：anchor 非空先 `im().v1().message().reply`（msgType="file"）；为空保持 create。验证：`./gradlew :lark-gateway:compileKotlin`
- [x] 2.2 reply 失败且 code 230020/230017 或 http 404 → 降级 `sendFileByKeyCreate` 重发（镜像 text 链降级）。验证：编译 + 既有 sendFileByKey 测试不红

## 3. 测试

- [x] 3.1 `ReplySinkAnchoringTest`（新建）：群聊 text/markdown 双链断言 `recordedSends[n].out.replyToMessageId`、私聊豁免 `replyAnchorOf == null`、群聊 file 链继承锚定、无锚定时字段保持 null。验证：`./gradlew :lark-bridge:test`

## 4. 验收

- [x] 4.1 四模块全量 `--rerun-tasks --no-build-cache` 全绿
- [x] 4.2 真机（2026-09-12 维护者确认真机跑过）：群里提问→结论与文件消息带引用气泡；私聊无引用样式；提问被撤回后文件消息仍能直发（降级路径）
