# :lark-gateway 网关契约定稿（M3-T3 施工规格）

> **文档定位**：本文档是 **M3-T3（`:lark-gateway` 网关契约与 LarkChannel 适配）的唯一施工规格**。T3 施工者照本文档可以**零决策**开工：Kotlin 签名、SDK 调用路径、常量取值、错误码映射、测试断言与阈值均已定稿并标注出处。
>
> **定稿闸门**：M1 Spike 的 **S1（SDK Gradle 集成）+ S2（jlink 打包）+ S3（LarkChannel 真实 API 四问）全 PASS**（`spike-findings.md:108` / `:267` / `:601`），方案设定的「S1+S2+S3 未通过前不得进入 M3 的网关契约定稿」闸门**已开**。S4 / S6 尚未真机执行（凭据未到），本文档为其两种结果都留了明确分支（§4.10 与 §13）。
>
> **本文档不修改契约**：`MessageGateway` 的 **12 个成员**（方案 `:54-66`）与 `CardStreamSink` 的 **5 个成员**（方案 `:68-69`）**完整保留**；唯一字面校正是 `registerApp` 的**回调参数名与参数语义**（按 SDK 实测，§8 与 §14 修正 6）。

---

## 0. 真源边界与出处标注约定

### 0.1 上游真源（本文档只吸收、不推翻）

| 真源 | 路径 | 本文档使用方式 |
|---|---|---|
| 方案（唯一真源） | 方案文档（仓库外，未入库；本文档即定稿规格） | 目标架构 `:28-49`、网关契约 `:51-78`、并发/背压/生命周期 `:206-241`、D1-D20 `:243-268`、风险表 R1-R21 `:509-533`、后台核对 7 项 `:537-551`、已否决替代方案 11 项 `:553-567` |
| M1 源码级实测 | 同目录 `spike-findings.md`（1361 行） | S1/S2/S3/S5/S7 判定、偏差 A-G（`:605-802`）、`LarkChannel` 公开面（`:814-833`）、默认值全清单（附录 A `:1281-1301`）、遗留风险（附录 C `:1347-1361`） |
| 行为基线 | 同目录 `behavior-contract.md`（1526 行） | §4.13 errorCode 全集（`:1000-1022`）、§4.14 卡片结构字面值（`:1024-1035`）、§5.1-§5.11 SDK 实测断言调整（`:1076-1170`）、§7.1 NF-01..NF-18（`:1242-1261`）、§7.2 NF-A1..NF-A7（`:1267-1275`）、§9 P4（`:1362-1367`）/ P11（`:1412-1417`） |
| S4/S6 运行手册 | 同目录 `spike-s4s6-runbook.md`（**已按其 §7 删除清单移除**——S4/S6 真机验证完毕后该文件自称「非交付物」；其 §8 的 SDK 签名结论已完整吸收进本文档与 `spike-findings.md`，`[RB §n]` 标记据此对照） | §3 后台 7 项（`:121-131`）、§5 PASS/FAIL 判定（`:152-167`）、§6.2 FAIL 降级、**§8 脚手架已核验的 SDK 2.8.5 真实签名（`:264-281`）** |
| 配置域规格（**并行撰写，另一 agent 所有**） | 同目录 `config-domain-spec.md`（982 行） | **只引用不重复定义**：§3.2.1 `connection.*`（`:91-100`）、§3.2.2 `access.*`（`:102-110`）、§4.4 `PipelineSnapshot`（`:281-284`）、**§12.1 `BridgeErrorCode` 完整清单（`:719-751`）**、§12.2 → `JErrorCode` 映射（`:753-772`） |
| Spike 脚手架（**编译期已验证的真实 API 用法**） | `lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/spike/`：`SpikeCommon.kt`(180行)、`S4LongConnectionSpike.kt`(222行)、`S6CardStreamSpike.kt`(341行) | 反射 shutdown 实现（决策 13①）、`LarkChannelOptions` 构造范式、cardkit 四步调用序列 |

### 0.2 出处标注约定

| 标记 | 含义 |
|---|---|
| `[SF:n]` | `spike-findings.md` 第 n 行 |
| `[BC:n]` / `[BC §x.y]` | `behavior-contract.md` 第 n 行 / 该章节 |
| `[RB §n]` | `spike-s4s6-runbook.md` 该章节（**该文件已移除**，出处结论保留在本文档正文与 `spike-findings.md`） |
| `[PLAN:n]` | 方案文件第 n 行 |
| `[CDS §n]` | `config-domain-spec.md` 该章节（**本文档不复述其内容**） |
| `[JAVAP <FQN>]` | 本次定稿期间对 `oapi-sdk-2.8.5.jar` 的 `javap` 实测签名 |
| `[SCAFFOLD <文件>]` | spike 脚手架中已编译通过的用法（编译期即证据） |
| 🔴 | 会导致**静默错误行为**的强制项，不得省略、不得"简化" |
| `待真机确认` | 该结论在 S4/S6/T11 之前是**假设**，全部汇总在 §13 |

### 0.3 取证方式说明（与任务书指路的差异，如实登记）

任务书指路的 `find ~/.gradle/caches/modules-2 -name "oapi-sdk-2.8.5-sources.jar"` **实测零命中**：sources jar 是 S2/S3 期间下载到 `build/spike-tmp/` 的临时取证工作区，收尾时已按 `[SF:1335]` 整体删除，Gradle 不会自动下载 sources。缓存内只有二进制 jar：`~/.gradle/caches/modules-2/files-2.1/com.larksuite.oapi/oapi-sdk/2.8.5/<hash>/oapi-sdk-2.8.5.jar`。

因此本文档的 SDK 签名结论来自**两条互相印证的证据链**：

1. `javap -cp <该 jar> <FQN>` 直接读字节码公开签名 —— 标记 `[JAVAP ...]`；
2. S4/S6 脚手架**已按真实包名编译通过**，其 import 与调用形态本身即编译期验证 —— 标记 `[SCAFFOLD ...]`，汇总清单见 `[RB §8]`。

两条链一致处即为定稿结论；不一致处（例如 `QRCodeInfo` 的 getter 名）在 §14 明确标注并给出采用值。

### 0.4 本文档**不**负责的内容（避免与并行文档重复）

| 内容 | 归属 |
|---|---|
| 配置 JSON schema v1、`BridgeFields.ALL`、`ConfigGroup`、`DefaultBridgeConfig`、`ConfigValidator`、`ConfigRepository`、`ConfigMigrations`、`LegacyDocumentMapper`（35 key 映射） | `config-domain-spec.md`（另一 agent 所有，本文档**只引用**） |
| `BridgeErrorCode` 的**码值清单**与 `→ JErrorCode` 映射 | `config-domain-spec.md` §12.1 / §12.2。本文档 §6.3 只做**飞书业务码 → 网关自有错误语义**的映射，并给出建议的下游对接表 |
| 旧 60 个测试的 BC-001..BC-550 断言编号体系 | `behavior-contract.md`（本文档只引用编号） |
| M4 桥接内核（处理链 / handlers / agent / confirm / observe / files / 门面装配） | 方案 `[PLAN:397-414]`，后续 T5-T9 规格 |
| M5 CLI 五子命令、M6 UI、M7 迁移与文档 | 方案 `[PLAN:415-431]` |

---

## 1. 契约定稿结论

**S1 + S2 + S3 三个 spike 全 PASS，`:lark-gateway` 对外契约定稿：`MessageGateway` 的 12 个成员与 `CardStreamSink` 的 5 个成员一字不改地完整保留；SDK 能力与契约语义的三处不兼容全部在 `:lark-gateway` 内部用「raw client 逃生舱」补齐，不向 `:lark-bridge` 泄漏任何 SDK 类型，也不改动契约形状。**

### 1.1 定稿判据（三个闸门 spike 的证据）

| Spike | 判定 | 证据行 | 对契约定稿的意义 |
|---|---|---|---|
| **S1** SDK Gradle 集成 | **PASS** | `[SF:108]` | `easydevtoolbox.jvm.cli` convention 插件已在 convention 层钉死 Java 11 + jvmTarget 11、已注入 `kotlin-test`、已 apply `com.gradleup.shadow` `[SF:90-107]` ⇒ `:lark-gateway/build.gradle.kts` 不需重复声明（§10.1） |
| **S2** jlink 打包 | **PASS** | `[SF:267]` | 实际模块清单 **9 项**（§10.5）；`httpclient → java.naming + java.security.jgss` 缺口经裁决为**死代码路径**，无需补模块、无需 `includeAllModules` `[SF:234-265]` |
| **S3** LarkChannel 四问 | **PASS** | `[SF:601]`、答案表 `[SF:308-315]` | Q1 线程模型（全 commonPool + handler 同步在 WS reader 线程）、Q2 去重键（`messageId`）、Q3 sequence（SDK 自持 `private int`）、Q4 重试（**FAIL**：盲指数退避 + `Thread.sleep` 占 commonPool）⇒ 直接决定 §3.4 / §4.3 / §5.2 / §9 |

S3 的 Q4 是四问里唯一 FAIL 的一项，但不构成定稿阻塞：其应对（`RetryConfig.maxAttempts = 1` + 频控退避全部自有承担）在 `[BC §5.4]` 与决策 6 中已定稿，落在 §5.2 与 §7。

### 1.2 三处「SDK 能力 ≠ 契约语义」的消解（不改契约）

| # | 契约成员 | SDK 侧的不兼容（实测） | 本文档定稿的消解方式 | 出处 |
|---|---|---|---|---|
| **1** | `openCardStream(target, initial): CardStreamSink` | `LarkChannel.stream(to, StreamInput, SendOptions)` 是**回调驱动**（`StreamInput.card(Map, CardStreamProducer)`，producer 返回即结束），契约是**推驱动**（任意时刻 `tryAppend`）；SDK 生成的卡片 JSON 被硬编码为 `title="Streaming response"` / `element_id="stream_md"`，我方 `ProgressCardRenderer` 的自定义布局塞不进去。更致命：`OutboundSender.updateCardElementContent` / `finishStreamingCard` **完全丢弃返回值**（四个卡片方法均不调用 `assertSuccessfulResponse`），`300317`/`200860`/`200810`/`200750` 全部**静默失败** | 走 **raw client cardkit 四步 API**：`LarkChannel.getRawClient()` → `cardkit().v1().card().create()` → `im().v1().message().create()`（按引用发卡）→ `cardkit().v1().cardElement().patch()`（流式更新文本元素）→ `cardkit().v1().card().settings()`（结束流式），**逐次检查 `resp.success()` 与 `resp.code`** 并映射到网关错误语义；自持 per-stream `sequence: Int` | `[SF:808]` D-1、`[SF:667-689]` 偏差 C、`[BC §5.3]`、`[JAVAP cardkit.v1.resource.Card / CardElement]`、`[SCAFFOLD S6CardStreamSpike.kt]` |
| **2** | `setReaction(messageId, emojiKey, add)` | SDK 是**两个**方法：`addReaction(messageId, emojiType) → CompletableFuture<String>`（返回 reactionId）+ `removeReactionByEmoji(messageId, emojiType) → CompletableFuture<Boolean>`；另有 `removeReaction(messageId, reactionId)` 需保存 reactionId | 网关层**合成**：`add=true → addReaction`、`add=false → removeReactionByEmoji`。**不保存 reactionId**（因此不使用 `removeReaction`） | `[SF:824-825]`、`[SF:832]`、决策 11 |
| **3** | `downloadResource(messageId, fileKey, dest): Boolean` | `LarkChannel.downloadResource(fileKey, type)` 返回 `CompletableFuture<byte[]>`——**不落盘、无 `messageId`/`dest` 参数**；`fileMessage.maxFileSizeBytes` 默认 100MB ⇒ 整文件入堆。🔴 **本次 javap 实测：raw client 路径同样不提供 `InputStream`**——`im().v1().messageResource()` 只有 `get(GetMessageResourceReq[, RequestOptions]) throws Exception`，返回 `GetMessageResourceResp extends BaseResponse<java.io.ByteArrayOutputStream>` + `writeFile(String) throws IOException`，整文件仍全量缓冲在堆内（`BAOS` 而非 `byte[]`，同样入堆） | 契约签名**不变**；实现改走 raw client `messageResource().get()` + `writeFile(临时文件)` + **原子移动**到 `dest`，叠加**三重降级防护**：① `maxFileSizeBytes` 上限钳制（超限直接返回 `false`，不发请求）② 下载**并发单飞**（同 `messageId+fileKey` 只允许一个在途）③ 临时文件 + `ATOMIC_MOVE` + 失败清理。标 `待 S4 真机确认`（"SDK 无流式路径"本身已是实测结论；待确认的是真机内存曲线与 100MB 上限的实际表现） | `[BC §5.9]`、`[JAVAP com.lark.oapi.service.im.v1.resource.MessageResource]`、`[JAVAP com.lark.oapi.service.im.v1.model.GetMessageResourceResp]`、决策 10 |

### 1.3 契约成员清点（13 + 5，逐条状态）

| # | 成员 | 状态 | 本文档章节 |
|---|---|---|---|
| 1 | `val connectionState: StateFlow<GatewayConnectionState>` | 保留 | §2.6、§3.3 |
| 2 | `val inbound: Flow<InboundMessage>` | 保留 | §2.3、§3.4 |
| 3 | `val signals: Flow<GatewaySignal>` | 保留 | §2.7、§3.5 |
| 4 | `suspend fun connect(cred, policy): GatewayConnectResult` | 保留 | §2.5、§3.6 |
| 5 | `suspend fun disconnect()` | 保留 | §3.8 |
| 6 | `suspend fun updatePolicy(policy)` | 保留（**语义收窄**，决策 3） | §2.5、§5.3 |
| 7 | `suspend fun send(target, out): SendResult` | 保留 | §2.4、§3.9 |
| 8 | `fun openCardStream(target, initial): CardStreamSink` | 保留（实现走逃生舱） | §2.4、§4 |
| 9 | `suspend fun downloadResource(messageId, fileKey, dest): Boolean` | 保留（实现走逃生舱） | §3.7 |
| 10 | `suspend fun setReaction(messageId, emojiKey, add): Boolean` | 保留（网关层合成） | §3.10 |
| 11 | `suspend fun registerApp(onQr): LarkCredential` | 保留（**回调参数语义校正** + 实现真实扫码流） | §8 |
| 12 | `override fun close()` | 保留（幂等 + 反射 shutdown） | §3.11 |
| 🔴 13 | `suspend fun resolveSenderName(openId): String? = null` | **task 15 新增**（带默认实现，S4 真机回填 `senderName` 反查） | §2.6、§16 |
| S1 | `CardStreamSink.isOpen` | 保留 | §2.8、§4.4 |
| S2 | `CardStreamSink.tryAppend(chunk): Boolean` | 保留 | §2.8、§4.4 |
| S3 | `CardStreamSink.flushTerminal(card)` | 保留 | §2.8、§4.7 |
| S4 | `CardStreamSink.close()` | 保留 | §2.8、§4.8 |
| S5 | `CardStreamSink.segmentIndex` | 保留 | §2.8、§4.6 |

### 1.4 SDK 隔离的三重强制在本模块的落点

`[PLAN:45-49]` 定义的三重强制，逐条落位：

1. **编译期**——`:lark-gateway` 用 `implementation(libs.oapi.sdk)`（🔴 **不是 `api`**）`[SF:1311]`；`:lark-bridge` 经 `api(project(":lark-gateway"))` 依赖本模块，但拿不到任何 SDK 类型 ⇒ 在 `:lark-bridge` 里写 `import com.lark.oapi.*` **直接编译失败**。这是最强一道，不靠人工纪律。
2. **守护测试**——`LarkSdkIsolationTest`（M3-T4），精确断言规格见 §12（含真实包名黑名单与追加的 `org.apache.commons.logging` / `ApacheHttpClientTransport`）。
3. **约定登记**——`AGENTS.md` 关键约定 6 补例外 + 新增第 8 条；`build.gradle.kts` 文件头注释（§10.4）。**shadowJar 禁止 relocate Gson/protobuf**：Gson 未被 shade（`import com.google.gson.Gson` 原样），`Jsons.DEFAULT` 是 `public static final Gson`，SDK 大量用反射 + Gson TypeAdapter 处理 `com.lark.oapi.service.*.model.*` ⇒ relocate Gson 会直接打断 SDK 反射链 `[SF:933-936]`。

本模块内部再加**第四道自律约束**（施工纪律，非强制机制）：`SdkLarkGateway.kt` 与 `LarkCardStreamSink.kt` 是**仅有的两个** import SDK 的文件（§3.1、§4.1）；mapper 层（`LarkPolicyMapper` / `LarkInboundMapper` / `LarkOutboundMapper` / `LarkErrorMapper`）**全部是纯函数**，入参出参只用本文档 §2 定义的纯 Kotlin 值类型 + JDK 类型，**零 SDK 类型**。这样 §11 的四个 mapper 测试可以完全不启动 SDK、不连真机、纯 `runTest` 跑绿。

> ⚠️ 与 `[PLAN:394]` 的一处细化：方案写「`SdkLarkGateway.kt`（**唯一** import SDK 的文件）」。本文档定稿为**两个**文件：`SdkLarkGateway.kt` + `LarkCardStreamSink.kt`。
> 理由：决策 1 要求卡片流式走 raw client cardkit，其四个 API 的请求/响应类型（`CreateCardReq` / `PatchCardElementReq` / `SettingsCardReq` / `CreateMessageReq` 及各自 `Resp`）**必然**出现在卡片流式实现内；若强行塞进 `SdkLarkGateway.kt`，会让该文件同时承担长连接生命周期与卡片流式两套职责，违背单一职责且不可测。
> **这不违反 SDK 隔离**（隔离边界是**模块**，不是文件）：`LarkSdkIsolationTest` 的断言是「`lark-gateway/src` **之外**不得出现 SDK 字符串」（§12），模块内多一个文件不触碰任何断言。登记为 §14 修正 8。

---

## 2. 契约的精确 Kotlin 签名

### 2.1 文件与包布局（定稿）

包根：`com.jiaoay.lark.gateway`（**不是** `com.jiaoay.easy_dev_toolbox.*`——R19 方向约束：lib 侧 jvmTarget 11 不得依赖 `:core` jvmTarget 21，`[PLAN:531]`、`[CDS §14.1]`）。

| 文件 | import SDK？ | 职责 | 章节 |
|---|---|---|---|
| `GatewayModel.kt` | ❌ | 全部纯 Kotlin 值类型：`LarkCredential` / `LarkTarget` / `InboundMessage` / `InboundResource` / `InboundMention` / `InboundKind` / `ChatType` / `CardAction` / `Outbound` / `OutboundKind` / `CardSpec` / `CardButtonSpec` / `CardTitleColor` / `SendResult` / `GatewayConnectResult` / `GatewayErrorCode` | §2.2-§2.5 |
| `MessageGateway.kt` | ❌ | `MessageGateway` 接口 + `GatewayConnectionState` + `GatewaySignal` sealed 层级 | §2.5-§2.7 |
| `CardStreamSink.kt` | ❌ | `CardStreamSink` 接口 | §2.8 |
| `LarkPolicy.kt` | ❌ | `LarkPolicy` + `DmMode` | §5.1 |
| `Tuning.kt` | ❌ | 全部调优常量（不入用户配置文档） | §7 |
| `SdkLarkGateway.kt` | ✅ | `MessageGateway` 的唯一 SDK 实现：长连接生命周期、事件订阅、send / download / reaction / close | §3 |
| `LarkCardStreamSink.kt` | ✅ | `CardStreamSink` 的 SDK 实现：cardkit 四步、sequence、节流、预算分段、轮换 | §4 |
| `LarkPolicyMapper.kt` | ❌（纯函数） | `LarkPolicy` → SDK 五个 config 的**取值表**（以纯数据 `GatewaySdkSettings` 表达，由 `SdkLarkGateway` 翻译成 SDK 对象） | §5 |
| `LarkInboundMapper.kt` | ❌（纯函数） | `NormalizedMessageRecord` → `InboundMessage` | §6.1 |
| `LarkOutboundMapper.kt` | ❌（纯函数） | `Outbound` / `CardSpec` → `LarkPayload`（`msgType` + `content` JSON 串） | §6.2 |
| `LarkErrorMapper.kt` | ❌（纯函数） | 飞书业务码 / HTTP 状态 / SDK 异常码 → `GatewayErrorCode` | §6.3 |
| `RegisterAppFlow.kt` | ✅ | 扫码建应用（专用 dispatcher，阻塞式轮询） | §8 |

> 🔴 **mapper 层「零 SDK 类型」的实现手法**（这是本节唯一的结构性技巧，务必照做）：
> mapper 是纯函数，但 `LarkPolicyMapper` 的目标是 SDK 的 `PolicyConfig` / `SafetyConfig` 等嵌套类。为保持 mapper 可脱离 SDK 单测，定稿采用**中间纯数据类型**：
> - `LarkPolicyMapper.toSettings(policy: LarkPolicy): GatewaySdkSettings`，`GatewaySdkSettings` 是 `GatewayModel.kt` 里的纯 data class（字段名与 SDK 属性名 1:1，含全部必须覆写的默认值）；
> - `SdkLarkGateway` 内有一个**私有** `applySettings(GatewaySdkSettings): LarkChannelOptions.Builder`，是唯一把纯数据翻译成 SDK 对象的地方。
> - 同理 `LarkInboundMapper.from(record: NormalizedMessageRecord): InboundMessage`，`NormalizedMessageRecord` 是纯 data class（字段名 = `NormalizedMessage` 的 16 个 getter，§6.1），由 `SdkLarkGateway` 的 handler 在**回调线程外**填充。
>
> 收益：§11 的 `PolicyMappingTest` / `LarkInboundMapperTest` / `LarkOutboundMapperTest` / `LarkErrorMapperTest` 四个测试**完全不加载 SDK 类**，可在 jlink 裁剪环境外独立跑绿，也不受 SDK 版本升级影响。

### 2.2 `LarkCredential` / `LarkTarget`（定稿）

```kotlin
package com.jiaoay.lark.gateway

/** 飞书自建应用凭据。字段与 [CDS §3.2.1] 的 connection.appId / appSecret / region 一一对应。 */
data class LarkCredential(
    val appId: String,          // connection.appId，形如 cli_xxx；SDK LarkChannelOptions.newBuilder(appId, appSecret)
    val appSecret: String,      // connection.appSecret。🔴 R17：日志/审计/check 输出一律掩码（前 4 位 + "***"）
    val region: String = "feishu", // connection.region，Choice(feishu, lark)。SDK 侧对应 domain 选择
) {
    /** 掩码形态，供日志与 UI 显示。绝不 toString 出 appSecret。 */
    fun masked(): String = "LarkCredential(appId=$appId, appSecret=${appSecret.take(4)}***, region=$region)"
    override fun toString(): String = masked()   // 🔴 覆写 toString，防止任何字符串插值泄漏 secret
}

/**
 * 出站目标。receiveIdType 的字面值直接喂给 SDK：
 * CreateMessageReq.newBuilder().receiveIdType(String)  [JAVAP CreateMessageReq$Builder]
 * 注意：com.lark.oapi.service.im.v1.enums.ReceiveIdType 这个类**不存在**（javap 实测找不到），
 * 只能用字面串或 CreateMessageCreateMessageV1ReceiveIDTypeEnum。本文档定稿用**字面串**。
 */
sealed interface LarkTarget {
    val receiveId: String
    val receiveIdType: String   // "chat_id" | "open_id"
    /** 群聊会话（含 p2p 会话，飞书 p2p 也有 oc_ 开头的 chat_id） */
    data class Chat(val chatId: String) : LarkTarget {
        override val receiveId: String get() = chatId
        override val receiveIdType: String get() = "chat_id"
    }
    /** 直接按用户 open_id 发（用于 rejectionReply 等尚无 chatId 的场景） */
    data class User(val openId: String) : LarkTarget {
        override val receiveId: String get() = openId
        override val receiveIdType: String get() = "open_id"
    }

    companion object {
        /** 由 InboundMessage 反推回复目标：一律用 Chat（chatId 已由 SDK 给出，p2p/group 通用）。 */
        fun ofInbound(msg: InboundMessage): LarkTarget = Chat(msg.chatId)
    }
}
```

**定稿理由**：
- `receiveIdType` 字面值 `"chat_id"` 已被 S6 脚手架编译并调用验证 `[SCAFFOLD S6CardStreamSpike.kt:191]`；
- `LarkTarget` 做成 sealed 而非 `(id, type)` 二元组，是为了让「传错 idType」在**编译期**就不可能（R1 缓解：契约保持最小面但不留误用口子）；
- 🔴 `LarkCredential.toString()` **必须覆写**：方案 R17 要求 secret 单点统一掩码 `[PLAN:529]`，而 Kotlin data class 默认 `toString()` 会打出全部字段。网关是最先接触 secret 的一层，这里不堵住，下游任何 `"cred=$cred"` 日志都会泄漏。

### 2.3 `InboundMessage` 及其子类型（定稿）

字段名严格对齐 `NormalizedMessage` 的 **16 个 getter** `[SF:848-853]`、`[BC §5.8]`、决策 15：

```kotlin
enum class ChatType { P2P, GROUP, UNKNOWN }

enum class InboundKind {
    TEXT, IMAGE, FILE, AUDIO, VIDEO, POST, MERGE_FORWARD, STICKER, SHARE_CHAT, SHARE_USER,
    CARD_ACTION,   // 来自 "cardAction" 事件流（不是 message 的 rawContentType，见下）
    UNKNOWN,       // 🔴 rawContentType 出现未登记取值时的 fallback，绝不抛异常
}

data class InboundResource(
    val type: String?,        // ResourceDescriptor.getType()
    val fileKey: String?,     // ResourceDescriptor.getFileKey()
    val fileName: String?,    // ResourceDescriptor.getFileName()
    val durationMs: Long,     // ResourceDescriptor.getDurationMs()
)

data class InboundMention(
    val key: String?,         // MentionInfo.getKey()，形如 @_user_1
    val openId: String?,      // MentionInfo.getOpenId()
    val userId: String?,      // MentionInfo.getUserId()
    val name: String?,        // MentionInfo.getName()
)

/** 卡片回传（card.action.trigger）。CardActionEvent 的 8 个 getter，见 [RB §8]。 */
data class CardAction(
    val operatorId: String,   // CardActionEvent.getOperatorId()
    val actionTag: String?,   // getActionTag()
    val actionName: String?,  // getActionName()
    val actionOption: String?,// getActionOption()
    val actionValue: String?, // getActionValue()
)

data class InboundMessage(
    val messageId: String,        // 🔴 幂等键（D12 / [BC §5.1]）。SDK 无 eventId 字段，无从误用
    val chatId: String,
    val chatType: ChatType,       // SDK 字面值 "p2p" / "group" → 枚举；其它 → UNKNOWN
    val senderId: String,
    val senderName: String,       // SDK 已从事件本身解析（不额外发 HTTP）[SF:855]
    val text: String,             // NormalizedMessage.getContent()：已转换 + 已剥离 bot mention（stripBotMentions=true 硬编码 [SF:857]）
    val kind: InboundKind,
    val rawContentType: String?,  // 🔴 原样透传（不是 messageType），供可观测与 P11 回填
    val resources: List<InboundResource>,
    val mentions: List<InboundMention>,
    val mentionAll: Boolean,      // NormalizedMessage.isMentionAll()
    val mentionedBot: Boolean,    // NormalizedMessage.isMentionedBot()
    val rootId: String?,
    val threadId: String?,
    val replyToMessageId: String?,
    val createTimeMillis: Long,   // NormalizedMessage.getCreateTime()：long 毫秒（不是字符串，[BC §5.8]）
    val cardAction: CardAction? = null,  // kind == CARD_ACTION 时非空
    val gatewayReceivedAtMillis: Long = 0L, // 网关本地时钟，供 StaleFilter 交叉校验（NF-A7）
) {
    /** D13 复合会话键：chatId + (threadId ?: rootId ?: "")。SDK 原生提供三字段，无需从 raw JSON 挖 [SF:855]。 */
    val sessionKey: String get() = chatId + (threadId ?: rootId ?: "")
}
```

🔴 **`raw` 字段刻意不进契约**：`NormalizedMessage.getRaw()` 只有在 `includeRawInMessage(true)` 时才有值，而该 builder 方法**已 deprecated**（编译有 warning），决策 14 明令生产代码不得依赖。因此 `InboundMessage` **不含** raw JSON 字段；raw 只允许在 S4/S6 spike 脚手架内临时使用。

🔴 **`CARD_ACTION` 的来源不是 `rawContentType`**：`CardActionEvent` **无 `rawContentType` getter** `[RB §8]`、`[SCAFFOLD S4LongConnectionSpike.kt:184-203]` ⇒ 卡片回传是**独立事件流**（`ch.on("cardAction", ...)`），不是 `message` 事件的一种 content type。这是 `[BC §9 P11]` 的一条已可定稿的结论（脚手架已实测字段面）；但「飞书是否**同时**把卡片消息作为 `message` 事件下发、若下发其 `rawContentType` 取何值」仍 `待 S4 真机回填`（§13 第 3 条）。

### 2.4 `Outbound` / `CardSpec` / `SendResult`（定稿）

```kotlin
enum class OutboundKind { TEXT, MARKDOWN, CARD }

data class Outbound(
    val kind: OutboundKind,
    val text: String? = null,                 // kind == TEXT | MARKDOWN 时非空
    val card: CardSpec? = null,               // kind == CARD 时非空
    val replyToMessageId: String? = null,     // → SendOptions.Builder.replyTo(String) [SF:879]
    val replyInThread: Boolean = false,       // → SendOptions.Builder.replyInThread(Boolean)
    val mentionOpenIds: List<String> = emptyList(), // → SendOptions.Builder.mentions(List<String>)
    val idempotencyKey: String? = null,       // → CreateMessageReqBody.Builder.uuid(String) [JAVAP]；
                                              //   🔴 send() 本身不重试（决策 6），uuid 只为飞书侧去重与排障留痕
) {
    init {
        require((kind == OutboundKind.CARD) == (card != null)) { "CARD 当且仅当 card != null" }
        require((kind != OutboundKind.CARD) == (text != null)) { "TEXT/MARKDOWN 当且仅当 text != null" }
    }
    companion object {
        fun text(s: String) = Outbound(OutboundKind.TEXT, text = s)
        fun markdown(s: String) = Outbound(OutboundKind.MARKDOWN, text = s)
        fun card(c: CardSpec) = Outbound(OutboundKind.CARD, card = c)
    }
}

/** 卡片标题配色 → 飞书 header.template 字面值。取值来自 [BC §4.14] 的进度卡文案与配色约定。 */
enum class CardTitleColor(val template: String) {
    BLUE("blue"),     // 受理态（"已受理"）
    GREEN("green"),   // DONE（"已完成"）
    RED("red"),       // FAILED（"失败"）
    GREY("grey"),     // 进行中 / 中性
}

data class CardButtonSpec(
    val text: String,
    val value: String,               // 回传时出现在 CardActionEvent.getActionValue()
    val primary: Boolean = false,    // → behaviors[0].type=="callback" + button type primary/default
)

/**
 * 卡片规格。🔴 契约级约束（决策 22）：
 *   streaming == true 时 buttons **必须为空**——飞书 200810「流式期间无法响应卡片回传」。
 *   进度卡一律只读流式卡；确认/裁决一律独立的非流式卡（streaming == false）。
 *   该约束由 LarkOutboundMapper 强制（非空即抛 IllegalArgumentException），并由 NF-A6 负向断言守护。
 */
data class CardSpec(
    val title: String,
    val titleColor: CardTitleColor = CardTitleColor.GREY,
    val markdown: String,                                  // → body.elements[0].tag=="markdown" 的 content
    val buttons: List<CardButtonSpec> = emptyList(),       // → behaviors 数组，禁 action 包裹（否则 230099）
    val streaming: Boolean = false,                        // true → config.streaming_mode = true
    val summary: String? = null,                           // → config.summary.content；null 时取 markdown.take(80)
)

/** 出站结果。🔴 失败**返回**而不抛异常（BC-459 / NF-A3 ④）。 */
data class SendResult(
    val ok: Boolean,
    val messageId: String?,                 // ok == true 时非空（CreateMessageRespBody.getMessageId()）
    val error: GatewayErrorCode? = null,    // ok == false 时非空
    val larkCode: Int? = null,              // 飞书业务码原值（BaseResponse.getCode()），可观测性用
    val larkMsg: String? = null,            // BaseResponse.getMsg()
    val httpStatus: Int? = null,            // RawResponse.getStatusCode()
    val chunkMessageIds: List<String> = emptyList(), // 文本超 3500 分片时的多 messageId（OutboundConfig.textChunkLimit）
)
```

### 2.5 `GatewayConnectResult` 与 `GatewayErrorCode`（定稿）

🔴 **一处必须先讲清的层次约束**：`BridgeErrorCode` 按 `[CDS §12.1]` 定义在 `lark-bridge/config/BridgeErrorCode.kt`，即 **`:lark-bridge` 模块内**。而依赖方向是 `:lark-bridge --api--> :lark-gateway`，**`:lark-gateway` 不能反向引用 `:lark-bridge` 的任何类型**（否则循环依赖，Gradle 直接失败）。
因此网关**必须有自己的错误枚举** `GatewayErrorCode`（定义在 `GatewayModel.kt`），由 `:lark-bridge` 侧做一次 `GatewayErrorCode → BridgeErrorCode` 映射。
`BridgeErrorCode` 的码值清单与 `→ JErrorCode` 映射**归 `[CDS §12.1/§12.2]`，本文档不重复定义**；§6.3 只给出建议的对接表。此项已作为**新发现的契约级矛盾**上报（附录 B 第 1 条）。

```kotlin
/**
 * 网关自有错误语义。命名与 SDK 的 LarkChannelErrorCode 枚举值对齐 [SF:890-892]，
 * 并补齐 cardkit 侧的业务码语义（SDK 枚举里没有这些）。
 */
enum class GatewayErrorCode {
    // —— 连接域 ——
    ConnectFatal,            // 不可重试：凭据错误 / 应用未发版 / scope 缺失 / 99991672 / HTTP 404 纯文本
    ConnectRetryable,        // 可重试：网络抖动 / 缺 URL 字段 / 5xx
    BlockedByPeerInstance,   // D19 同 appId 单实例互斥（由注入的 appIdLockProbe 报告）
    NotConnected,            // SDK LarkChannelErrorCode.NOT_CONNECTED：未 connect 就调用出站 API

    // —— 出站域 ——
    FormatError,             // SDK FORMAT_ERROR
    TargetRevoked,           // SDK TARGET_REVOKED（会话被解散 / 用户注销）
    PermissionDenied,        // SDK PERMISSION_DENIED（scope 不足）
    UploadFailed,            // SDK UPLOAD_FAILED
    SsrfBlocked,             // SDK SSRF_BLOCKED（OutboundConfig.ssrfGuardEnabled 拦截）
    SendTimeout,             // SDK SEND_TIMEOUT
    SendFailed,              // 兜底：LarkChannelErrorCode.UNKNOWN 或未登记业务码

    // —— 频控域 ——
    RateLimited,             // SDK RATE_LIMITED / HTTP 429 / 业务码 11232（整点压力限流）

    // —— 卡片流式域（🔴 SDK 侧全部静默失败，必须由我方逐次检查响应码后产出）——
    CardSequenceConflict,    // 300317 sequence 不连续 / 乱序 → 重建流
    CardTooLarge,            // 200860 单卡 30KB 超限 → 触发分段轮换
    CardStreamBusy,          // 200810 流式期间无法响应卡片回传 → 确认卡必须走独立非流式卡
    CardEntityExpired,       // 200750 卡片实体 14 天过期 / 仅可发送一次 → 新建实体
    CardJsonInvalid,         // 300302 / 230099（unknown property: action）→ 卡片 JSON 结构错误

    // —— 资源域 ——
    DownloadFailed,          // 下载失败（含 IO / 非 0 业务码）
    DownloadTooLarge,        // 超 fileMessage.maxFileSizeBytes 上限，主动拒绝（不发请求）
    ReactionFailed,          // addReaction / removeReactionByEmoji 失败

    // —— 扫码建应用域 ——
    RegisterQrExpired,       // SDK ExpiredException / QRCodeInfo.expireIn 到期
    RegisterInvalidArgument, // SDK RegisterAppException(ERROR_INVALID_ARGUMENT)
    RegisterFailed,          // 其它 RegisterAppException

    Unknown,                 // 兜底
}

sealed interface GatewayConnectResult {
    data class Connected(val botOpenId: String, val botName: String?) : GatewayConnectResult
    data class Failed(
        val error: GatewayErrorCode,
        val larkCode: Int? = null,
        val larkMsg: String? = null,
        val retryable: Boolean = error == GatewayErrorCode.ConnectRetryable,
    ) : GatewayConnectResult
    data object BlockedByPeerInstance : GatewayConnectResult
}
```

> **`BotIdentity` 的实测形状**：`class BotIdentity { BotIdentity(String,String); String getOpenId(); String getName(); }` `[SF:887]` ⇒ `Connected(botOpenId, botName)` 两字段足够，无需引入 SDK 类型。
> **`BlockedByPeerInstance` 的产出方**：D19 的 `AppIdLock`（`FileChannel.tryLock`）归 `:lark-bridge`（M4-T7），**不在网关内实现**。网关通过构造参数 `appIdLockProbe: (() -> Boolean)? = null` 接受宿主注入的探针：`connect()` 首先调用它，返回 `true` 即产出 `BlockedByPeerInstance` 并把 `connectionState` 置为该状态，**不触碰 SDK**。默认 `null` ⇒ 探针恒不触发（`SyntheticGateway` 与单测无需感知）。这样 D19 的**唯一所有者仍是引擎**，网关只是把结果表达进契约状态机。

### 2.6 `MessageGateway` 接口（13 成员，逐条规格）

> 🔴 **成员数 12 → 13（task 15 补录，2026-09-08）**：新增 `suspend fun resolveSenderName(openId: String): String? = null`（**带默认实现**，S4 真机结论回填——`senderName` 事件字段常缺，需按 `openId` 反查通讯录补全）。因带默认实现，未覆写的实现类（如 `SyntheticGateway`）自动继承 no-op；🔴 施工教训：Kotlin 给接口加**带默认实现的 suspend 成员**后，反射会额外看到合成方法 `resolveSenderName$suspendImpl` 与 `access$...$jd`，成员计数断言须 `filter { !it.contains('$') }`（否则误报 15 而非 13，见 §16 补录）。

```kotlin
package com.jiaoay.lark.gateway

import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MessageGateway : AutoCloseable {
    val connectionState: StateFlow<GatewayConnectionState>
    val inbound: Flow<InboundMessage>
    val signals: Flow<GatewaySignal>
    suspend fun connect(cred: LarkCredential, policy: LarkPolicy): GatewayConnectResult
    suspend fun disconnect()
    suspend fun updatePolicy(policy: LarkPolicy)
    suspend fun send(target: LarkTarget, out: Outbound): SendResult
    fun openCardStream(target: LarkTarget, initial: CardSpec): CardStreamSink
    suspend fun downloadResource(messageId: String, fileKey: String, dest: Path): Boolean
    suspend fun setReaction(messageId: String, emojiKey: String, add: Boolean): Boolean
    suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): LarkCredential
    suspend fun resolveSenderName(openId: String): String? = null  // task 15 新增，带默认实现（第 13 成员）
    override fun close()
}
```

逐成员的**语义 / 线程与挂起约定 / SDK 实现路径 / 失败信号**：

| # | 成员 | 语义 | 线程 / 挂起约定 | SDK 实现路径 | 失败时的错误码 / 信号 |
|---|---|---|---|---|---|
| 1 | `connectionState` | 连接状态机的**唯一**权威来源。初值 `Idle`；`connect()` 期间 `Connecting`；成功后 `Connected(botOpenId, botName)`；SDK `"reconnecting"` 事件 → `Reconnecting`；`"reconnected"` → `Connected`；`disconnect()`/失败 → `Disconnected` | `MutableStateFlow` 由网关内部持有，`asStateFlow()` 暴露。**写入只发生在 `net` dispatcher 或 SDK 回调线程**（后者仅一次原子 `value =` 赋值，无锁无挂起） | `connectSync()` 返回值 + `on("reconnecting")` / `on("reconnected")` `[SF:817-820]`、`[SF:839-844]` ⇒ **不需要额外轮询** | — |
| 2 | `inbound` | 已归一化的入站事件流（`message` + `cardAction` 两个 SDK 事件流**合流**） | `callbackFlow` + `awaitClose`（§3.4）。**冷流**：每个 collector 独立；网关内部只维持一个上游 `Channel(512, DROP_OLDEST)` `[PLAN:225]`，`inbound` 是它的 `receiveAsFlow()` | `ch.on("message", ChannelEventHandler<NormalizedMessage>)` + `ch.on("cardAction", ChannelEventHandler<CardActionEvent>)`，🔴 每个事件名**只注册一个** handler（决策 8） | 溢出 → `DROP_OLDEST` + `signals.emit(Dropped)` 计数（D18） |
| 3 | `signals` | 非消息类的网关事件（拒绝 / 重连 / 频控 / 发送失败 / 分段轮换） | 同 `inbound`，独立 `Channel(256, DROP_OLDEST)`（信号可丢但必须计数） | `on("reject")` / `on("reconnecting")` / `on("reconnected")` / `on("error")` + 出站失败点主动 emit | 见 §2.7 |
| 4 | `connect(cred, policy)` | 建立长连接。**必须先 connect 成功再注册 handler**——`botIdentity` 为 null 时 `ChannelInboundProcessor` 抛 `NOT_CONNECTED` `[SF:856]`、`[SCAFFOLD S4:63-72]` | `suspend`，整体包在 `withContext(netDispatcher)`。🔴 用 **`connectSync()`** 而非 `connect()`（后者 `supplyAsync` 不传 Executor ⇒ 跑 commonPool，决策 5） | `LarkChannelFactory.createLarkChannel(options)` → `ch.connectSync(): BotIdentity` | `GatewayConnectResult.Failed(ConnectFatal/ConnectRetryable)` 或 `BlockedByPeerInstance`；异常一律**捕获后映射**，不向上抛 |
| 5 | `disconnect()` | 主动断开长连接，**不释放线程池**（释放是 `close()` 的职责） | `suspend`，`withContext(netDispatcher)` + `withTimeoutOrNull(Tuning.disconnectTimeoutMs)`。🔴 用 **`disconnectSync()`** | `ch.disconnectSync()` `[RB §8]`；`LarkChannel` 另有 `disconnect(): CompletableFuture<Void>` `[SF:819]`，不用 | 超时 → `signals.emit(SendFailed)` 不适用；记 WARN，状态置 `Disconnected(ConnectRetryable, "timeout")` |
| 6 | `updatePolicy(policy)` | **热更新，不重连**（消灭 D8「改白名单就断连」）。🔴 **语义收窄（决策 3）**：网关只转发 SDK 能吃下的子集（5 个 `PolicyConfig` 字段），sender 级 allow/block 与 blocklist **不下推**，由引擎 `PipelineSnapshot` 原子替换承担 `[CDS §4.4]` | `suspend`，`withContext(netDispatcher)`。SDK `updatePolicy` 是 `void` 同步方法 `[SF:826]`，不需 await | 🔴 `ch.updatePolicy(LarkPolicyMapper.toSdkPolicy(current, partial))`——**SDK 侧是全量覆盖那 5 个字段而非增量 merge**（决策 7），所以必须**先复制当前完整 policy 再改目标字段**，否则「只改 dmMode」会把 `groupAllowlist` 清空 `[SF:691-735]` 偏差 D | 无返回值；SDK 抛异常 → 记 ERROR + `signals.emit(SendFailed(...))`，状态不变（policy 更新失败不断连） |
| 7 | `send(target, out)` | 发一条消息（文本 / markdown / 卡片）。文本超 `textChunkLimit=3500` 由网关**自动分片**，返回全部 `chunkMessageIds` | `suspend`，`withContext(netDispatcher)`。🔴 **不自动重试**（决策 6：发送非幂等，防重复投递） | `ch.send(to, SendInput, SendOptions)` → `.await()`；或 raw client `im().v1().message().create(CreateMessageReq)`（卡片按引用发送时**必须**走 raw client，见 §4.5） | `SendResult(ok=false, error=..., larkCode=..., larkMsg=..., httpStatus=...)`，**不抛**（BC-459 / NF-A3 ④） |
| 8 | `openCardStream(target, initial)` | 开一条流式进度卡会话。**非 suspend**（契约如此）——同步返回 sink，实际的建卡/发卡在 sink 内部首个 `tryAppend` 或后台协程里异步完成 | 🔴 **非挂起**：内部 `launch(netDispatcher)` 启动流式协程；调用方立即可 `tryAppend` | raw client cardkit 四步（§4.2） | 建卡失败 → `sink.isOpen == false` + `signals.emit(SendFailed(CardEntityExpired/...))`；`tryAppend` 返回 `false` |
| 9 | `downloadResource(messageId, fileKey, dest)` | 把消息附件落盘到 `dest`。返回 `Boolean`（成功 / 失败），**不抛** | `suspend`，`withContext(netDispatcher)`；**可重试**（幂等，由我方 `RateGovernor` 退避，决策 6） | 🔴 raw client `im().v1().messageResource().get(GetMessageResourceReq)` → `resp.writeFile(临时文件)` → `Files.move(tmp, dest, ATOMIC_MOVE)`。**不用** `ch.downloadResource(fileKey, type)`（返回 `byte[]`，整文件入堆） | `false` + `signals.emit(SendFailed(DownloadFailed/DownloadTooLarge))`；详见 §3.7 |
| 10 | `setReaction(messageId, emojiKey, add)` | 加 / 撤一个 emoji 回应 | `suspend`，`withContext(netDispatcher)`；幂等，可重试 | `add=true → ch.addReaction(messageId, emojiKey).await()`；`add=false → ch.removeReactionByEmoji(messageId, emojiKey).await()`（决策 11，**不保存 reactionId**，故不用 `removeReaction`） | `false` + `signals.emit(SendFailed(ReactionFailed))` |
| 11 | `registerApp(onQr)` | 扫码创建自建应用，返回新凭据。**阻塞式轮询最长约 600s** | `suspend`，🔴 包在**专用** `registerDispatcher`（`Dispatchers.IO.limitedParallelism(1, "bridge-register")`），**不占 commonPool、不占 `net` 的 8 个槽**（决策 12） | `com.lark.oapi.scene.registration.RegisterApp.register(RegisterAppOptions) → RegisterAppResult(clientId, clientSecret, userInfo)` `[SF:921-923]` | 抛 `GatewayException(RegisterQrExpired / RegisterInvalidArgument / RegisterFailed)`——**这是 12 成员里唯一允许抛异常的**（它是用户主动发起的一次性交互流程，无"静默失败"风险）。详见 §8 |
| 12 | `close()` | 幂等释放：断连 + **反射 shutdown SDK 的 ws 线程池** + cancel 内部 scope | **非挂起**（`AutoCloseable`）。内部用 `runBlocking(NonCancellable) { withTimeoutOrNull(Tuning.closeTimeoutMs) }` 完成挂起收尾；🔴 反射部分**不挂起**，纯 `runCatching` | `ch.disconnectSync()` → `reflectivelyShutdownWsExecutor(ch.rawWsClient)` → `scope.cancel()`（§3.11） | 反射失败**不抛**，只记 ERROR 并置标志位；由 `StartStopLeakTest` / `GatewayCloseReleasesThreadsTest` 报警（决策 13③） |

### 2.7 `GatewayConnectionState` 与 `GatewaySignal`（完整 sealed 定义）

```kotlin
/** 连接状态机。成员与取值严格照 [PLAN:55]，不增不减。 */
sealed interface GatewayConnectionState {
    data object Idle : GatewayConnectionState
    data object Connecting : GatewayConnectionState
    /** botOpenId 来自 BotIdentity.getOpenId()；botName 来自 getName()，可能为 null [SF:887] */
    data class Connected(val botOpenId: String, val botName: String? = null) : GatewayConnectionState
    data object Reconnecting : GatewayConnectionState
    data class Disconnected(val code: GatewayErrorCode, val msg: String) : GatewayConnectionState
    data object BlockedByPeerInstance : GatewayConnectionState
}

/** 非消息类网关信号。成员与取值严格照 [PLAN:57]，不增不减。 */
sealed interface GatewaySignal {
    /** SDK PolicyGate 拒绝。reason = RejectEvent.getReason() 的 name（RejectReason 枚举 [SF:691-735]）。
     *  🔴 决策 3 后 SDK 侧全放行 ⇒ 本信号在正常运行时**恒不出现**；保留它是为了 SDK 侧策略被误配时可观测。 */
    data class Rejected(val reason: String, val messageId: String?, val chatId: String?, val senderId: String?) : GatewaySignal
    data object Reconnecting : GatewaySignal
    data object Reconnected : GatewaySignal
    /** resetSec：来自 x-ogw-ratelimit-reset 响应头；头缺失时为 null（该头是否真实存在 待 S6 真机确认，§13 第 4 条）。
     *  [JAVAP com.lark.oapi.core.response.RawResponse].getHeaders(): Map<String, List<String>> ⇒ API 层可读。 */
    data class RateLimited(val resetSec: Int?, val larkCode: Int?, val chatId: String?) : GatewaySignal
    data class SendFailed(val code: GatewayErrorCode, val msg: String, val chatId: String? = null) : GatewaySignal
    /** 流式卡因 24KB 预算或 9min 期限轮换到新卡片实体。segmentIndex 从 1 开始（第 2 段轮换时值为 2）。 */
    data class StreamSegmentRotated(val segmentIndex: Int, val reason: RotationReason, val newMessageId: String?) : GatewaySignal
    /** D18 纪律：所有丢弃点必须可观测。入站 Channel(512) 或信号 Channel(256) 溢出时 emit。 */
    data class Dropped(val where: String, val droppedTotal: Long) : GatewaySignal
}

enum class RotationReason { ByteBudget, TimeDeadline, EntityExpired, SequenceConflict }
```

> ⚠️ `Dropped` 是本文档**新增**的第 7 个 `GatewaySignal` 子类，`[PLAN:57]` 只列了 6 个。
> 理由：D18 要求「全部丢弃点加计数器并暴露为指标」`[PLAN:266]`，而 `[PLAN:225]` 明确 SDK handler → intake 的 `Channel(512, DROP_OLDEST)` 要「+ 计数 + WARN」。
> 网关是该丢弃点的**唯一**发生地，若不发信号，引擎无法计数。新增子类**不破坏**契约（sealed 的下游 `when` 若无 `else` 分支会编译失败，这正是我们要的强制可见性）。已登记为附录 B 第 2 条（只报告，不裁定）。
> 若 Leader 认为不应扩契约，替代方案是把丢弃计数改为网关内部的 `LongAdder` + 由引擎轮询——但那会引入新的拉取路径，不如信号直接。**本文档按新增 `Dropped` 施工。**

**状态机迁移表（定稿，施工者照此写 `MutableStateFlow` 的赋值点）**：

| 当前态 | 触发 | 目标态 | 赋值点 |
|---|---|---|---|
| `Idle` | `connect()` 被调用且 `appIdLockProbe` 未报占用 | `Connecting` | §3.6 步骤 1 |
| `Connecting` | `connectSync()` 返回非 null `BotIdentity` | `Connected(openId, name)` | §3.6 步骤 4 |
| `Connecting` | `connectSync()` 抛异常 / 返回 null | `Disconnected(code, msg)` | §3.6 步骤 5 |
| `Connecting` | `appIdLockProbe() == true` | `BlockedByPeerInstance` | §3.6 步骤 0 |
| `Connected` | SDK `"reconnecting"` 事件 | `Reconnecting` | §3.5 handler |
| `Reconnecting` | SDK `"reconnected"` 事件 | `Connected`（沿用原 botOpenId） | §3.5 handler |
| `Connected`/`Reconnecting` | `disconnect()` 完成 | `Disconnected(Unknown, "client requested")` | §3.8 |
| 任意 | `close()` | `Disconnected(Unknown, "closed")`（若原为 `Idle` 则保持 `Idle`） | §3.11 |
| `Disconnected` | 再次 `connect()` | `Connecting` | §3.6 步骤 1（**允许重连**，不需重建 gateway 实例） |

### 2.8 `CardStreamSink` 接口（5 成员，逐条规格）

```kotlin
interface CardStreamSink {
    /** 流是否仍可写。false 的三种成因：已 close / 建卡失败 / 已发终态卡。 */
    val isOpen: Boolean
    /**
     * 追加一段增量文本。返回 false 表示「本次未接受」——调用方**不需要**重投（网关内部是 1 槽 conflate，
     * 快照已包含该 chunk；返回 false 只表示 sink 已关闭或已终态）。
     * 🔴 非挂起、非阻塞、O(1)：只把 chunk 追加进内部不可变快照并置脏标志，实际 patch 由内部节流协程发出。
     */
    fun tryAppend(chunk: String): Boolean
    /** 发终态卡：立即 flush（不受节流窗口限制）+ settings 结束流式 + 置 isOpen=false。 */
    suspend fun flushTerminal(card: CardSpec)
    /** 关闭流。若尚未发终态卡，则用当前快照补一次终态（幂等：重复 close 无副作用）。 */
    suspend fun close()
    /** 当前段序号，从 1 开始。每次因 24KB 预算 / 9min 期限 / 200750 / 300317 轮换后 +1。 */
    val segmentIndex: Int
}
```

| 成员 | 线程 / 挂起约定 | 对应实现 | 失败信号 |
|---|---|---|---|
| `isOpen` | `@Volatile` 读，任意线程 | §4.4 | — |
| `tryAppend` | 🔴 **非挂起**，可在任意线程（含 `Dispatchers.Default` 的 pipe 解析协程）调用；内部只做「追加到 1 槽 conflate」 | §4.4 | 返回 `false`；若因卡片错误关闭，另 emit `SendFailed` |
| `flushTerminal` | `suspend`，`withContext(netDispatcher)`，🔴 包在 `NonCancellable` 内（十步关闭第 ④ 步要求终态卡必须发出去）`[PLAN:239]` | §4.7 | `signals.emit(SendFailed(CardXxx))`；不抛 |
| `close` | `suspend`，`NonCancellable` + `withTimeoutOrNull(3s)`（十步关闭第 ④ 步的 3s 上限） | §4.8 | 超时记 WARN，不抛 |
| `segmentIndex` | `@Volatile` 读 | §4.6 | — |

**契约级约束（决策 22，🔴 强制）**：`openCardStream(target, initial)` 的 `initial: CardSpec` 与 `flushTerminal(card)` 的 `card`，若 `streaming == true` 而 `buttons.isNotEmpty()`，`LarkOutboundMapper` **抛 `IllegalArgumentException`**（快速失败，不是静默降级）。理由：200810「流式期间无法响应卡片回传」是飞书侧的硬互斥，用户点了按钮永远收不到回传，确认链路会静默卡死到超时——这是**最坏的一类缺陷**。NF-A6 ⑥ 要求负向断言守护 `[BC:1274]`。

---

## 3. `SdkLarkGateway` 实现规格

### 3.1 文件职责与唯一性

`SdkLarkGateway.kt` 是 `MessageGateway` 的**唯一 SDK 实现**（另有 `SyntheticGateway` 在 `:lark-bridge` 的测试源集内，用于 NF-18 `AssemblerParityTest`，不 import SDK `[PLAN:513]`）。

```kotlin
class SdkLarkGateway(
    private val netDispatcher: CoroutineDispatcher,          // 由引擎注入 BridgeDispatchers.net（IO.limitedParallelism(8,"bridge-net")）
    private val scope: CoroutineScope,                       // 由引擎注入 BridgeRuntime 的 childJob 作用域
    private val appIdLockProbe: (() -> Boolean)? = null,     // D19 探针，默认 null（§2.5）
    private val settings: GatewaySdkSettings = GatewaySdkSettings.DEFAULT, // 由 LarkPolicyMapper 产出（§5）
    private val clock: () -> Long = System::currentTimeMillis,             // 可注入，供虚拟时间测试
) : MessageGateway
```

🔴 **dispatcher 归属**：网关**不自己创建** `net` 池，由引擎注入 `[PLAN:208-217]` 的 `BridgeDispatchers.net`，保证全仓只有一个 `bridge-net` 池、`RateGovernorTest` 与 `GatewayDispatcherTest` 的线程名断言才有单一目标。默认值不提供（构造参数无默认），强制装配点显式传入。

🔴 **仅本文件与 `LarkCardStreamSink.kt` / `RegisterAppFlow.kt` 允许出现 `import com.lark.oapi.*`**（§1.4、§12）。

### 3.2 SDK 类型的真实包名（决策 16，逐条照抄）

| SDK 类型 | 🔴 真实 FQN | 常见错误写法（方案原文，**不要用**） |
|---|---|---|
| `LarkChannel` | `com.lark.oapi.channel.LarkChannel` | `com.lark.oapi.LarkChannel` |
| `LarkChannelFactory` | `com.lark.oapi.channel.LarkChannelFactory` | — |
| `ChannelEventHandler<T>` | `com.lark.oapi.channel.ChannelEventHandler` | — |
| `ChannelSubscription` | `com.lark.oapi.channel.ChannelSubscription` | — |
| `LarkChannelOptions` | `com.lark.oapi.channel.config.LarkChannelOptions` | `com.lark.oapi.config.LarkChannelOptions` |
| `SafetyConfig` / `PolicyConfig` / `BatchTextConfig` / `OutboundConfig` / `RetryConfig` | 🔴 **全是 `LarkChannelOptions` 的静态嵌套类**，写作 `LarkChannelOptions.SafetyConfig` 等 | 当作独立顶层类 `com.lark.oapi.channel.config.SafetyConfig` |
| `NormalizedMessage` | `com.lark.oapi.channel.model.NormalizedMessage` | `com.lark.oapi.model.NormalizedMessage` |
| `CardActionEvent` / `RejectEvent` / `BotIdentity` / `SendInput` / `StreamInput` / `SendOptions` / `SendResult`（SDK 的） / `ResourceDescriptor` / `MentionInfo` | `com.lark.oapi.channel.model.*` | — |
| `LarkChannelException` / `LarkChannelErrorCode` | `com.lark.oapi.channel.exception.*` | — |
| `ws.Client` | `com.lark.oapi.ws.Client` | — |
| raw client | `com.lark.oapi.Client` | — |
| cardkit | `com.lark.oapi.service.cardkit.v1.model.{CreateCardReq, CreateCardReqBody, CreateCardResp, PatchCardElementReq, PatchCardElementReqBody, PatchCardElementResp, SettingsCardReq, SettingsCardReqBody, SettingsCardResp, ContentCardElementReq, ContentCardElementReqBody}` | — |
| im | `com.lark.oapi.service.im.v1.model.{CreateMessageReq, CreateMessageReqBody, CreateMessageResp, GetMessageResourceReq, GetMessageResourceResp}` | — |
| 响应基类 | `com.lark.oapi.core.response.BaseResponse` / `com.lark.oapi.core.response.RawResponse` | 🔴 **不是** `com.lark.oapi.core.httpclient.RawResponse`（该类不存在，javap 实测） |
| 默认 HTTP 传输 | `com.lark.oapi.core.httpclient.OkHttpTransport`（shaded OkHttp：`com.lark.oapi.okhttp.*`，静态单例 `OKHttps.defaultClient`） | 🔴 **不是** Apache HttpClient（决策 17，§10.5） |

出处：`[SF:609-633]` 偏差 A（真实包名清单）、`[SF:816-829]`、`[RB §8]`、`[JAVAP]` 逐类核验。

### 3.3 `LarkChannelOptions` 的构造（定稿代码路径）

```kotlin
// 位于 SdkLarkGateway 私有方法 applySettings()。这是全仓唯一把纯数据翻译成 SDK 对象的地方。
private fun buildOptions(cred: LarkCredential, s: GatewaySdkSettings): LarkChannelOptions {
    val safety = LarkChannelOptions.SafetyConfig().apply {
        // 🔴🔴 决策 4：真实 Kotlin 属性名带 is 前缀。写成 chatQueueEnabled = false 会解析成
        // 另一个不存在的赋值（或静默走 setter 重载失败），实际仍用默认 true ⇒ 入站消息被 600ms 合批。
        isChatQueueEnabled = s.chatQueueEnabled           // 恒 false，见 §5.2
        dedupTtlMs        = s.dedupTtlMs                  // 12h（SDK 默认，作第二道网）
        dedupMaxEntries   = s.dedupMaxEntries             // 5000（SDK 默认）
        staleMessageWindowMs = s.staleMessageWindowMs     // 30min（SDK 默认）
        // batchText 不需要配置：chatQueueEnabled=false 后 BatchTextConfig.delayMs=600 不再生效
    }
    val policy = LarkChannelOptions.PolicyConfig().apply {
        // 🔴 [SCAFFOLD S4:39-44] 实测：用**显式 setter** 而非 Kotlin 合成属性，规避 is 前缀歧义
        setGroupAllowlist(s.groupAllowlist)               // 恒 emptyList() ⇒ 全放行（决策 3）
        setDmMode(s.dmMode)                               // 恒 "open"
        setRequireMention(s.requireMention)               // ← access.groupRequireMention 唯一下推项
        setRespondToMentionAll(s.respondToMentionAll)     // 恒 true
    }
    val outbound = LarkChannelOptions.OutboundConfig().apply {
        textChunkLimit    = s.textChunkLimit              // 3500（出站分片，🔴 不是 BatchTextConfig，决策 18）
        streamThrottleMs  = s.streamThrottleMs            // 250（决策 19）
        streamThrottleChars = s.streamThrottleChars       // 4000（决策 19：Throttle 是「或」关系，必须调大）
        ssrfGuardEnabled  = s.ssrfGuardEnabled            // true
        allowedFileDirs   = s.allowedFileDirs             // 🔴 默认空 ⇒ 必须显式填（[SF:916]）
        retry = LarkChannelOptions.RetryConfig().apply { maxAttempts = 1; baseDelayMs = 500 } // 🔴 决策 6
    }
    return LarkChannelOptions.newBuilder(cred.appId, cred.appSecret)
        .safety(safety)
        .policy(policy)
        .outbound(outbound)
        // 🔴 决策 14：不调用 includeRawInMessage / includeRawEvent（已 deprecated，编译有 warning）
        .build()
}
```

🔴 **`isChatQueueEnabled` 的属性名陷阱（决策 4，最高优先级）**：SDK 的 Java 侧是 `isChatQueueEnabled()` / `setChatQueueEnabled(boolean)` `[RB §8]`。Kotlin 对此类 `isXxx`/`setXxx` 组合合成的属性名是 **`isChatQueueEnabled`**。方案 `[PLAN:?]` 与 `[SF:1301]` 写作 `safety.chatQueueEnabled = false`——**照此书写不会报错但也不会生效**（Kotlin 会把它解析为对 `setChatQueueEnabled` 的调用仅当属性名匹配；名字不匹配时若存在同名的 Java 字段则静默赋值到别处，否则编译失败）。脚手架已用 `isChatQueueEnabled = false` 编译通过 `[SCAFFOLD S4:36-38]`，**照抄脚手架写法**。`PolicyMappingTest` 必须有断言 `settings.chatQueueEnabled == false`（§11）。

**默认 `chatQueueEnabled=true` 的四个致命后果**（`[BC §5.5]`，施工者必须理解才能拒绝"简化"）：
① **发送者归属错乱**——`ChatPipeline.mergeBatch()` 把同 chat 600ms 窗口内多条消息合并，`content` 用 `\n\n` 拼接，但 `messageId`/`senderId`/`createTime` **只保留最后一条** ⇒ 群聊里 A 的消息被记到 B 名下，直接破坏审计（BC-299..310 的 `senderOpenId`）与访问控制（BC-186..194）；
② **「停止」指令的 URGENT 通路失效**——`停止` 与前一条普通消息合并后不再精确 `equals` 匹配（BC-200..205 全部依赖精确匹配），D11 的 URGENT 旁路准入形同虚设；
③ **每条入站固有 +600ms 延迟**——即使只有一条消息也要等满窗口；
④ **与我方 `ChatLane` 双重排队**——SDK 排一次、内核再排一次，背压语义（`PipeBackpressureTest`）无法推理。

### 3.4 `inbound` 的 `callbackFlow` 装配与「单 handler per event name」（决策 8）

🔴 **`ChannelEventBus.on()` 是单 handler per event name，后注册覆盖前者，不是多播**。SDK 原文 `[SF:369-372]`：

```java
if (handlers.containsKey(eventName)) LOGGER.warn("... being overwritten");
handlers.put(eventName, handler);
```

⇒ 网关**每个事件名只注册一个 handler**，内部分发。九个事件名（`[SF:833]`）：`message` / `reject` / `cardAction` / `reaction` / `comment` / `botAdded` / `reconnecting` / `reconnected` / `error`。
本实现**只注册前 6 个中用到的 4 个 + 2 个连接态**，共 **6 个** handler；`reaction` / `comment` / `botAdded` 三个事件名**不注册**（首批范围不需要，注册了也只是空转）。

```kotlin
private val inboundChannel = Channel<InboundMessage>(512, BufferOverflow.DROP_OLDEST)  // [PLAN:225]
private val signalChannel  = Channel<GatewaySignal>(256, BufferOverflow.DROP_OLDEST)
override val inbound: Flow<InboundMessage> = inboundChannel.receiveAsFlow()
override val signals: Flow<GatewaySignal>  = signalChannel.receiveAsFlow()

// connect() 成功后按此顺序注册（🔴 必须先 connectSync 成功，否则 botIdentity 为 null 抛 NOT_CONNECTED [SF:856]）
private fun registerHandlers(ch: LarkChannel) {
    ch.on("message", ChannelEventHandler<NormalizedMessage> { msg ->
        // 🔴🔴 决策 9：回调线程内**只允许**这一行。不得日志、不得 JSON、不得挂起、不得 launch、不得加锁。
        runCatching { inboundChannel.trySend(LarkInboundMapper.from(capture(msg))) }
    })
    ch.on("cardAction", ChannelEventHandler<CardActionEvent> { ev ->
        runCatching { inboundChannel.trySend(LarkInboundMapper.fromCardAction(capture(ev))) }
    })
    ch.on("reject", ChannelEventHandler<RejectEvent> { ev ->
        runCatching { signalChannel.trySend(LarkInboundMapper.toRejectedSignal(capture(ev))) }
    })
    ch.on("reconnecting", ChannelEventHandler<Any?> {
        connectionState.value = GatewayConnectionState.Reconnecting
        runCatching { signalChannel.trySend(GatewaySignal.Reconnecting) }
    })
    ch.on("reconnected", ChannelEventHandler<Any?> {
        connectionState.value = GatewayConnectionState.Connected(botOpenId, botName)  // 沿用原 identity
        runCatching { signalChannel.trySend(GatewaySignal.Reconnected) }
    })
    ch.on("error", ChannelEventHandler<Any?> { e ->
        runCatching { signalChannel.trySend(GatewaySignal.SendFailed(GatewayErrorCode.Unknown, e.toString())) }
    })
}
```

> **`capture(msg)` 的定位（🔴 决策 9 的关键细节）**：`capture` 是把 `NormalizedMessage` 的 16 个 getter **只读复制到纯 data class `NormalizedMessageRecord`** 的操作，**它本身就在回调线程内执行**，因此必须与 `trySend` 一起满足 <1ms。
> 这是**可行的**：`[SCAFFOLD S4:128-144]` 已实测「只读 16 个 getter 到局部变量」的耗时是**微秒级**（脚手架打印 `回调内捕获耗时(us)`，S4-PASS 判定 ⑦ 要求 <1000us `[RB:164]`）。
> 🔴 **绝不允许**把 `capture` 换成 `LarkInboundMapper.from(...)` 之外的任何加工（如 mention 解析、文本清洗、日志格式化）——那些必须放到 `intake` dispatcher 上由引擎做。
> 🔴 `resources` / `mentions` 是 `List`，`capture` 内做 `toList()` 浅拷贝（O(n)，n ≤ 十几），仍在 µs 级。**不得**在回调内遍历 `resources` 发起任何下载。

**为什么这条是 3 秒铁律的结构性保证（决策 9 的完整因果链，施工者必读）**：

1. handler 在 **shaded-okhttp 的 WS reader 线程**上**同步**执行 `[SF:378-391]`；
2. `ws.Client.handleDataFrame` 的 **ACK 与 `X-Biz-Rt` 上报都在 handler 返回之后**才发出 ⇒ reader 线程被占用期间，**整条长连接的所有后续事件都无法读取**（不只是当前事件超时）`[BC §5.7]`；
3. handler 抛异常 ⇒ `Response(500)` ⇒ **飞书重推该事件** `[SF:378-391]`；
4. 🔴 更隐蔽的一点：`SafetyPipeline.dispatchMessageBatch` 的 `finally { seenCache.mark(id); lock.release(id); }` **也在 handler 返回之后**执行 `[SF:399-401]` ⇒ **handler 阻塞期间，飞书的重推不会被 SDK 的去重拦住**（因为 mark 还没打上），会直接击穿到 handler 造成雪崩；
5. ⇒ 回调内**只允许** `runCatching { inboundChannel.trySend(msg) }`：非阻塞、非挂起、O(1)、不抛异常。绑定测试 `Handler3SecondTest`，阈值 **<1ms**（NF-01 `[BC:1244]`，M4-T7 落地，但**约束由本文件的 handler 写法在 M3-T3 就固化**）。

**`awaitClose` 的位置**：契约的 `inbound` 是 `receiveAsFlow()` 而非 `callbackFlow`（因为 handler 注册发生在 `connect()` 里，不是 Flow 收集时）。方案 `[PLAN:72]` 写的「`callbackFlow` + `awaitClose { channel.disconnect() }`」**在本文档定稿为等价但更准确的形态**：

```kotlin
// connect() 内部：把「Flow 收集结束 → 断连」的语义挂在引擎的 gatewayJob 上，而不是 callbackFlow 的 awaitClose。
// 理由：callbackFlow 的 awaitClose 只在**该 Flow 的 collector 取消**时触发，而契约的 inbound/signals 是两个独立 Flow，
// 用 callbackFlow 会导致「任一 collector 取消即断连」的错误语义。
// 定稿形态：SdkLarkGateway 内部持有一个 connectJob: Job，由引擎的 gatewayJob 作为 parent；
// gatewayJob 取消（十步关闭第 ⑤ 步之前）⇒ connectJob 取消 ⇒ invokeOnCompletion { runCatching { ch.disconnectSync() } }。
private var connectJob: Job? = null
private fun attachDisconnectOnCancel(ch: LarkChannel) {
    connectJob = scope.launch(netDispatcher) { awaitCancellation() }.also {
        it.invokeOnCompletion { runCatching { ch.disconnectSync() } }
    }
}
```

这保持了方案要求的「`awaitClose { channel.disconnect() }` 语义」（收集/生命周期结束即断连），同时避免双 Flow 的错误耦合。已登记为 §14 修正 9。

### 3.5 `signals` 的三个来源

| 来源 | 触发点 | 产出 |
|---|---|---|
| SDK `"reject"` 事件 | `PolicyGate.evaluate()` 拒绝 `[SF:691-735]` | `Rejected(reason, messageId, chatId, senderId)`。🔴 决策 3 后 SDK 全放行 ⇒ 正常恒不出现 |
| SDK `"reconnecting"` / `"reconnected"` | `ws.Client` 的 `onReconnecting` / `onReconnected` 回调 `[SF:839-840]` | `Reconnecting` / `Reconnected`（同时改 `connectionState`） |
| **我方出站失败点主动 emit** | `send` / `downloadResource` / `setReaction` / `LarkCardStreamSink` 的响应码检查 | `SendFailed(code, msg, chatId)` / `RateLimited(resetSec, larkCode, chatId)` / `StreamSegmentRotated(...)` / `Dropped(...)` |

🔴 SDK 的 `"error"` 事件映射为 `SendFailed(GatewayErrorCode.Unknown, e.toString())`——`ChannelEventHandler<Any?>` 拿到的是 `Throwable`/事件对象，无结构化码 `[SCAFFOLD S4:88]`。

### 3.6 `connect()` 的完整步骤（定稿，照此顺序写）

| 步 | 动作 | 失败处理 |
|---|---|---|
| 0 | `appIdLockProbe?.invoke() == true` ⇒ `connectionState = BlockedByPeerInstance`；返回 `GatewayConnectResult.BlockedByPeerInstance` | 不触碰 SDK |
| 1 | `connectionState = Connecting` | — |
| 2 | `withContext(netDispatcher) { buildOptions(cred, LarkPolicyMapper.toSettings(policy)) }` → `LarkChannelFactory.createLarkChannel(options)` | 构造异常 → `Failed(ConnectFatal)` |
| 3 | `withContext(netDispatcher) { withTimeoutOrNull(Tuning.connectTimeoutMs) { runCatching { ch.connectSync() } } }` 🔴 用 `connectSync()`（同步，跑在调用方线程＝我们的 `net` 池），**不用** `connect()`（`supplyAsync` 无 Executor ⇒ commonPool，决策 5） | `null`（超时）→ `Failed(ConnectRetryable)`；异常 → 经 `LarkErrorMapper` 映射 |
| 4 | `identity != null` ⇒ 保存 `botOpenId = identity.openId` / `botName = identity.name`；`connectionState = Connected(botOpenId, botName)` | — |
| 5 | 🔴 **connect 成功之后**才 `registerHandlers(ch)` + `attachDisconnectOnCancel(ch)`（顺序反了会因 `botIdentity == null` 抛 `NOT_CONNECTED` `[SF:856]`） | — |
| 6 | 返回 `Connected(botOpenId, botName)` | — |

**握手超时**：SDK 内部 `awaitWebSocketReady(15000)` `[SF:1297]` ⇒ `Tuning.connectTimeoutMs` 必须 **> 15000**，定稿 **20000**（§7）。

**连接失败的排障四项**（写进 `ConnectFatal` 的 `msg`，供 UI 与 CLI `check` 直接展示）`[SCAFFOLD S4:68]`、`[RB §4]`：
① 应用是否已发版且可用范围含自己；② 权限 scope 是否齐（含 `cardkit:card:write`）；③ 后台事件/回调订阅方式是否已切「使用长连接」并保存成功；④ 网络是否走代理（**长连接不支持代理**，检查 `HTTP_PROXY`/`HTTPS_PROXY`）。
🔴 第 ③ 项有**顺序陷阱（R21）**：后台保存长连接订阅方式的那一刻，本地必须已有一条 SDK 长连接处于「已连接」状态，否则保存失败 `[PLAN:551]`、`[RB §4]`。UI 引导横幅与文档步骤顺序归 M6/M7，网关侧的职责是**把这条提示写进 `ConnectFatal.msg`**。

### 3.7 `downloadResource()` 的实现（决策 10：含降级方案）

🔴 **实测结论（本次定稿新增，既有文档未记载）**：SDK 2.8.5 **不提供任何 `InputStream` 流式下载路径**。

| API | 实测签名 | 是否流式 |
|---|---|---|
| `LarkChannel.downloadResource(fileKey, type)` | `CompletableFuture<byte[]>` `[SF:823]` | ❌ 整文件 `byte[]` 入堆，且不落盘、无 `messageId`/`dest` 参数 |
| raw client `client.im().v1().messageResource()` | `[JAVAP com.lark.oapi.service.im.v1.resource.MessageResource]`：仅 `get(GetMessageResourceReq)` / `get(GetMessageResourceReq, RequestOptions)`，均 `throws Exception`。**无 `*Sync`/异步变体之分，无返回 `InputStream` 的重载** | ❌ |
| `GetMessageResourceResp` | `[JAVAP]`：`extends BaseResponse<java.io.ByteArrayOutputStream>`，另有 `getFileName()` / `setFileName(String)` / **`writeFile(String) throws IOException`** | ❌ 整文件先缓冲进 `ByteArrayOutputStream`（堆内），`writeFile` 才落盘 |

⇒ `[BC §5.9]` 写的「改走 raw client 的流式落盘（`InputStream` → `FileOutputStream` 边读边写）」在 SDK 2.8.5 上**不可实现**。决策 10 已预设该分支（"若确实只有 byte[]，写出降级方案"）。本文档定稿的**降级方案**如下（三重防护）：

```kotlin
override suspend fun downloadResource(messageId: String, fileKey: String, dest: Path): Boolean =
  withContext(netDispatcher) {
    // —— 防护 ①：上限钳制（不发请求就拒）——
    // fileSizeBytes 由调用方（引擎的 files handler）从 InboundResource 侧无从获知 ⇒
    // 定稿：网关侧只做「已知上限」的钳制，即 Tuning.downloadMaxBytes（= fileMessage.maxFileSizeBytes，由引擎注入 settings）。
    // 真正的按实际大小拒绝由引擎在调用前完成（它持有 fileMessage.maxFileSizeBytes 配置）。
    if (settings.downloadMaxBytes <= 0L) return@withContext false

    // —— 防护 ②：并发单飞（同 messageId+fileKey 只允许一个在途）——
    val key = "$messageId|$fileKey"
    if (!inflightDownloads.add(key)) {
        signalChannel.trySend(GatewaySignal.SendFailed(GatewayErrorCode.DownloadFailed, "duplicate inflight: $key"))
        return@withContext false
    }
    val tmp = dest.resolveSibling(dest.fileName.toString() + ".part-" + clock())
    try {
        val req = GetMessageResourceReq.newBuilder()
            .messageId(messageId).fileKey(fileKey).type(settings.downloadResourceType)  // "file" | "image"
            .build()
        val resp = runCatching { rawClient().im().v1().messageResource().get(req) }.getOrNull()
            ?: return@withContext false
        if (!resp.success()) {
            val code = LarkErrorMapper.fromLarkCode(resp.code)
            signalChannel.trySend(GatewaySignal.SendFailed(code, "download code=${resp.code} msg=${resp.msg} key=$key"))
            return@withContext false
        }
        // —— 防护 ③：临时文件 + 原子移动 + 失败清理 ——
        // 🔴 writeFile 只接受 String 路径 [JAVAP]，dest 是 Path ⇒ 必须 toString()
        resp.writeFile(tmp.toString())
        Files.createDirectories(dest.parent)
        runCatching { Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING); Unit }  // 跨文件系统降级
        true
    } catch (t: Throwable) {
        runCatching { Files.deleteIfExists(tmp) }
        signalChannel.trySend(GatewaySignal.SendFailed(GatewayErrorCode.DownloadFailed, t.toString()))
        false
    } finally {
        inflightDownloads.remove(key)
    }
  }
```

| 防护 | 作用 | 局限（如实登记） |
|---|---|---|
| ① 上限钳制 | 避免明知超限仍下载 | 网关侧**拿不到实际文件大小**（`ResourceDescriptor` 只有 `type`/`fileKey`/`fileName`/`durationMs` `[RB §8]`，**无 size 字段**）⇒ 按实际大小拒绝必须由引擎在调用前用 `fileMessage.maxFileSizeBytes` 判断（它也只能用配置上限，不是实际大小） |
| ② 并发单飞 | 避免同一附件被并发下载 N 份 ⇒ 堆内同时存在 N 个 `BAOS` | 不同 `fileKey` 的并发下载仍会各自入堆 ⇒ **堆峰值 = 并发下载数 × 单文件大小**，这是 SDK 2.8.5 的**硬约束**，只能靠引擎侧限制并发下载数（建议 ≤2）缓解 |
| ③ 临时文件 + 原子移动 | 失败不残留半成品（BC-298 追加断言）；`ATOMIC_MOVE` 保证读者永远看到完整文件 | `writeFile` 之前的堆内缓冲无法消除 |

🔴 **NF-A5 `FileDownloadStreamingTest` 的断言形态必须相应调整**：`[BC:1273]` 原写「采样 `Runtime.totalMemory()-freeMemory()` 增量**远小于文件体积**」——在 SDK 2.8.5 上**该断言必然失败**（`BAOS` 全量入堆）。定稿调整为三条可兑现的断言：
① **实现路径不出现 `ch.downloadResource(fileKey, type)`**（grep 断言，防止有人图省事用 `byte[]` 路径）；
② **落盘是「临时文件 + `Files.move`」**，失败时 `dest` 不存在（可测：mock `writeFile` 抛异常 → 断言 `dest` 不存在且 `.part-*` 已清理）；
③ **并发单飞**：同 `messageId+fileKey` 并发调用 2 次 ⇒ 只有 1 次真正发起 SDK 请求，另一次返回 `false` 且 emit `DownloadFailed`。
并把「堆峰值 = 并发数 × 文件大小」作为**已知限制**写进 `docs/pages/lark-bridge.md`（M7-T16）。此项列为附录 B 第 3 条上报。

### 3.8 `disconnect()`（第 5 成员）

```kotlin
override suspend fun disconnect() {
    withContext(netDispatcher) {
        val ch = channel ?: return@withContext
        withTimeoutOrNull(Tuning.disconnectTimeoutMs) { runCatching { ch.disconnectSync() } }
            ?: run { log.warn("disconnectSync 超时 ${Tuning.disconnectTimeoutMs}ms") }
        connectionState.value = GatewayConnectionState.Disconnected(GatewayErrorCode.Unknown, "client requested")
    }
}
```
🔴 `disconnect()` **不做**反射 shutdown——那是 `close()` 的职责（十步关闭把 ⑤ disconnect 与 ⑥ close 分成两步、各给 5s 上限 `[PLAN:239]`，合并会让超时归因失效）。

### 3.9 `send()`（第 7 成员）

```kotlin
override suspend fun send(target: LarkTarget, out: Outbound): SendResult = withContext(netDispatcher) {
    val ch = channel ?: return@withContext SendResult(false, null, GatewayErrorCode.NotConnected)
    val payload = LarkOutboundMapper.toPayload(out)      // 纯函数：msgType + content JSON 串（§6.2）
    // 🔴 文本分片：OutboundConfig.textChunkLimit=3500 是**出站**分片（决策 18），SDK 的 OutboundSender 会自己分片，
    //    但 SDK 分片后的多 messageId 只在 SendResult.getChunkIds() 里 [SF:882] ⇒ 我方必须把它透传成 chunkMessageIds。
    val opts = SendOptions.newBuilder()
        .apply {
            out.replyToMessageId?.let { replyTo(it) }
            if (out.replyInThread) replyInThread(true)
            if (out.mentionOpenIds.isNotEmpty()) mentions(out.mentionOpenIds)
        }.build()
    val input = when (out.kind) {
        OutboundKind.TEXT     -> SendInput.text(payload.content)
        OutboundKind.MARKDOWN -> SendInput.markdown(payload.content)
        OutboundKind.CARD     -> SendInput.card(payload.cardMap)   // 🔴 非流式卡才走这里；流式卡走 §4 的 raw client
    }
    runCatching { ch.send(target.receiveId, input, opts).await() }   // .await() 来自 kotlinx.coroutines.future（FutureKt 已在 core-jvm 内，禁新增 jdk8 依赖）
        .fold(
            onSuccess = { r -> SendResult(true, r.messageId, chunkMessageIds = r.chunkIds ?: emptyList()) },
            onFailure = { t ->
                val (code, larkCode, larkMsg, http) = LarkErrorMapper.fromThrowable(t)
                signalChannel.trySend(GatewaySignal.SendFailed(code, larkMsg ?: t.toString(), target.receiveId))
                SendResult(false, null, code, larkCode, larkMsg, http)
            },
        )
}
```

🔴 **`send()` 不自动重试**（决策 6）：发送非幂等，重试会重复投递。SDK 侧 `RetryConfig.maxAttempts=1` 已关掉其盲退避；我方 `RateGovernor` 只做**前置节流**（放行后才发起调用，NF-05 追加断言 `[BC:1248]`），不做发送重试。
🔴 **`.await()` 的来源**：`kotlinx.coroutines.future.await()`。`FutureKt.class` 已在 `kotlinx-coroutines-core-jvm-1.11.0.jar` 内，**禁止新增 `kotlinx-coroutines-jdk8`**（`[PLAN:562]` 已否决项）。
🔴 **`await()` 仍在 commonPool 上跑 SDK 的 `supplyAsync`**：`withContext(netDispatcher)` 只能保证**我们的挂起点**在 `net` 池，SDK 内部的 `supplyAsync` 依然落在 commonPool `[SF:545-590]` Q1。这是 `send()` 路径**无法完全消解**的部分（SDK 没提供 `sendSync`）。定稿取舍：
- **可完全消解的路径**（必须消解）：`connectSync()` / `disconnectSync()` / raw client 的 cardkit 与 messageResource（这些都是**同步方法**，跑在调用方线程＝我们的 `net` 池）；
- **不可消解的路径**：`ch.send()` / `ch.addReaction()` / `ch.removeReactionByEmoji()` 只有 `CompletableFuture` 变体 ⇒ SDK 的**实际 HTTP 执行**在 commonPool。缓解：这些调用是**非阻塞异步**（不 `Thread.sleep`、不占线程等 IO，OkHttp 自己有连接池线程），与 `OutboundRetry` 的 `Thread.sleep` 占线程性质不同；且 `maxAttempts=1` 已确保不会进入 `OutboundRetry` 的 sleep 路径。
- `GatewayDispatcherTest`（NF-A4）的断言据此**收窄**为：「`connect`/`disconnect`/卡片流式/资源下载四类**同步** SDK 调用点，执行线程名必须以 `bridge-net` 开头」+「`RetryConfig.maxAttempts == 1`（grep + `PolicyMappingTest` 断言），确保永不进入 `OutboundRetry` 的 `Thread.sleep`」。原 `[BC:1272]` 的「所有 SDK 调用」表述按此调整，列为附录 B 第 4 条上报。

### 3.10 `setReaction()`（第 10 成员，决策 11 的合成方式）

```kotlin
override suspend fun setReaction(messageId: String, emojiKey: String, add: Boolean): Boolean =
  withContext(netDispatcher) {
    val ch = channel ?: return@withContext false
    runCatching {
        if (add) ch.addReaction(messageId, emojiKey).await() != null       // CompletableFuture<String> = reactionId
        else     ch.removeReactionByEmoji(messageId, emojiKey).await()     // CompletableFuture<Boolean>
    }.getOrElse { t ->
        val (code, _, msg, _) = LarkErrorMapper.fromThrowable(t)
        signalChannel.trySend(GatewaySignal.SendFailed(code, msg ?: t.toString(), messageId))
        false
    }
  }
```

**为什么不用 `removeReaction(messageId, reactionId)`**：它需要保存 `addReaction` 返回的 reactionId ⇒ 网关必须维护一张 `messageId+emoji → reactionId` 的表（又一个需要容量治理的全局状态，正是 R8 被取消的同类问题 `[BC §5.2]`）。`removeReactionByEmoji` 直接按 emoji 撤，**无状态**。代价：同一 emoji 被多人加过时会一并撤掉——对本项目语义无影响（bot 只管理自己加的 reaction，用于「已受理」标记）。
🔴 `addReaction` 返回 `CompletableFuture<String>`，`await()` 得到 `String?`；**成功判据是 `!= null` 且不抛**，不要去解析 reactionId 的内容。

### 3.11 `close()` 的幂等 + 反射 shutdown（决策 13，🔴 本节是全文档最高风险点）

**问题事实（实测）** `[SF:780-796]` 偏差 F：

```java
// com.lark.oapi.ws.Client
protected final ExecutorService executor = Executors.newFixedThreadPool(cores*2+1);
// 全类无任何 shutdown 方法；close() 只调 disconnect()
```

⇒ 该线程池是**非 daemon**（`Executors.newFixedThreadPool` 默认 `ThreadFactory` 产非 daemon 线程），线程名形态 `pool-N-thread-M` `[SCAFFOLD SpikeCommon.kt ThreadProbe]`。
⇒ **每个 start/stop 周期泄漏 ≥ 1 个非 daemon 线程**，且非 daemon 线程会**阻止 JVM 自然退出**——对 Compose Desktop 应用即「关窗后进程残留」，对 CLI `serve` 即「Ctrl-C 后进程不退出」。
⇒ 方案十步关闭的第 ⑥ 步「`gateway.close()` 释放 SDK 线程/连接池」`[PLAN:239]` 在 SDK 2.8.5 上**原本不可兑现**；本节的反射 shutdown 使它**重新可兑现**。

**① 反射 shutdown 的具体路径（照抄 `[SCAFFOLD SpikeCommon.kt:reflectivelyShutdownWsExecutor]`，已实测可用）**：

```kotlin
/**
 * 反射关闭 com.lark.oapi.ws.Client 内部永不 shutdown 的非 daemon FixedThreadPool。
 * 反射路径（实测）：wsClient.javaClass（沿继承链向上）.getDeclaredField("executor")
 *                  -> setAccessible(true) -> (ExecutorService).shutdownNow()
 * @return true = 成功 shutdown；false = 字段缺失/类型不符/被安全管理器拒绝（**不抛**）
 */
private fun reflectivelyShutdownWsExecutor(wsClient: Any?): Boolean {
    if (wsClient == null) return false
    return try {
        var clazz: Class<*>? = wsClient.javaClass
        var field: java.lang.reflect.Field? = null
        // executor 声明在 com.lark.oapi.ws.Client 本身；沿继承链向上找，兼容潜在子类
        while (clazz != null && field == null) {
            field = runCatching { clazz.getDeclaredField("executor") }.getOrNull()
            if (field == null) clazz = clazz.superclass
        }
        if (field == null) return false
        field.isAccessible = true                       // 🔴 JDK 11 下对非导出包的私有字段仍需 setAccessible；
                                                        //    com.lark.oapi 是 classpath（非 module）上的自动模块 ⇒ 不受 JPMS 强封装阻拦
        val executor = field.get(wsClient) as? java.util.concurrent.ExecutorService ?: return false
        executor.shutdownNow()                          // 🔴 shutdownNow 而非 shutdown：ws reader 可能阻塞在 socket read，
                                                        //    shutdown() 不会中断已提交任务，线程仍不回落
        true
    } catch (t: Throwable) { false }
}
```

**② `close()` 的完整实现（幂等 + 释放顺序）**：

```kotlin
private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
private var wsExecutorReleased = false    // 供 GatewayCloseReleasesThreadsTest 断言

override fun close() {
    if (!closed.compareAndSet(false, true)) return     // 🔴 幂等：CAS，重复 close() 直接返回（十步关闭第 ⑥ 步可能被 onAppExit + shutdown hook 各调一次）
    val ch = channel
    // 步骤 a：断连（不挂起，runCatching 兜底）
    runCatching { ch?.disconnectSync() }
    // 步骤 b：cancel 内部协程（卡片流式协程、connectJob）
    runCatching { gatewayScope.cancel() }              // gatewayScope = 网关私有的子 scope，parent 是注入的 scope
    // 步骤 c：🔴 反射 shutdown SDK 的 ws 线程池（决策 13①）
    wsExecutorReleased = runCatching { reflectivelyShutdownWsExecutor(ch?.rawWsClient) }.getOrDefault(false)
    if (!wsExecutorReleased) {
        log.error("无法反射关闭 com.lark.oapi.ws.Client.executor —— SDK 版本升级可能改了字段名/类型。" +
                  "JVM 将不会自然退出，依赖 exitProcess 兜底（决策 13②）。请核对 StartStopLeakTest 的失败输出。")
    }
    // 步骤 d：关闭内部 channel（让 inbound/signals 的 collector 正常结束）
    runCatching { inboundChannel.close() }
    runCatching { signalChannel.close() }
    // 步骤 e：状态归位
    connectionState.value = GatewayConnectionState.Disconnected(GatewayErrorCode.Unknown, "closed")
    channel = null
}
```

🔴 **`ch.rawWsClient` 的取用时机**：必须在 `channel = null` **之前**、且 `disconnectSync()` **之后**取。`getRawWsClient()` 是 `LarkChannel` 的公开 getter `[SF:828]`、`[RB §8]`。
🔴 **`close()` 是非挂起的**（`AutoCloseable.close()` 签名所限），因此**不能**用 `withContext`。上面全部是同步调用 + `runCatching`，符合契约。十步关闭的第 ⑥ 步在引擎侧用 `withTimeoutOrNull(5s) { withContext(netDispatcher) { gateway.close() } }` 包裹 `[PLAN:239]`。

**③ `exitProcess` 双保险（决策 13②）**——反射是**尽力而为**，必须有无条件兜底：

| 宿主 | 兜底方式 | 落点 |
|---|---|---|
| **CLI `serve`** | 十步关闭完成后**显式** `exitProcess(0)`（异常路径 `exitProcess(非0)`）。🔴 不依赖 JVM 自然退出——即使反射失败、即使还有非 daemon 线程残留，进程也一定终止 | `:lark-bridge` 的 `cli/ServeCommand.kt`（M5-T10），**不在网关内** |
| **Compose App** | `LarkBridgeManager.onAppExit()` → `bridge.stop(graceMillis)` → 十步关闭（含第 ⑥ 步 `gateway.close()`）→ **由宿主 `Application.exit()`/`System.exit` 退出**。网关侧只提供 `close()`，不调用任何进程级 API | `:feature-larkbridge` 的宿主装配 + `composeApp/main.kt`（M6-T12/T14） |

🔴 **网关内绝不出现 `exitProcess` / `System.exit`**：库模块调用进程级 API 是不可接受的副作用（会让单测直接终止 JVM）。这条写进 §10.4 的文件头注释。

**④ 测试报警机制（决策 13③）**：
- `GatewayCloseReleasesThreadsTest`（本模块，§11 第 10 项）：断言 `close()` 后 `wsExecutorReleased == true` 且 `Thread.getAllStackTraces().keys.count { !it.isDaemon && it.name.matches(Regex("pool-\\d+-thread-\\d+")) }` **回落到 connect 前的基线**。
- `StartStopLeakTest`（NF-15，M4-T9）：`start → stop ×20` 后 `threadCount` **不增长**。**该断言原本在 `[BC:1258]` 标 `TBD(用户裁决中)`，现已由决策 13 解除**——阈值定为「回落到基线 ±0」（严格断言，不给豁免）。
- 🔴 **SDK 升级导致反射失效时，这两个测试会立刻红**：`wsExecutorReleased == false` 直接失败，且 ERROR 日志明确指向「字段名/类型变更」。这是刻意设计的**主动报警**，不是静默降级。版本锁定在 `2.8.5`（`gradle/libs.versions.toml`），升级必须重跑这两个测试。

> ⚠️ **与 `[SF:810]` D-3 建议的冲突**：spike-findings 的 D-3 建议「**不试图修复 SDK**（禁止改 SDK、**禁止反射关它的 executor**），改为在进程边界收口」。
> **Leader 决策 13 已推翻该建议**，改为「反射 shutdown + 显式退出」**双保险**。本文档按决策 13 施工。登记为 §14 修正 5。

---

## 4. `LarkCardStreamSink` 实现规格

### 4.1 类骨架

```kotlin
internal class LarkCardStreamSink(
    private val client: com.lark.oapi.Client,        // 来自 LarkChannel.getRawClient()，🔴 不新建 Client（复用连接池）
    private val target: LarkTarget,
    private val initial: CardSpec,
    private val netDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,               // 网关私有 gatewayScope 的子 scope，close() 时 cancel
    private val onSignal: (GatewaySignal) -> Unit,   // → signalChannel.trySend
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },   // 🔴 可注入，供虚拟时间测试（CardThrottleTest / Stream10MinRotationTest）
) : CardStreamSink
```

内部状态（🔴 **全部 per-sink，无任何全局 map**——决策 2 取消 R8 的 `card_id→sequence LRU(256)`）：

| 字段 | 类型 | 语义 |
|---|---|---|
| `sequence` | `private var Int` | 当前段的 sequence。**起始值 0，每次 patch/settings 前 `++sequence`**。🔴 `待 S6 真机确认`（§13 第 1 条） |
| `cardId` | `private var String?` | 当前段的卡片实体 id（`CreateCardRespBody.getCardId()`） |
| `cardMessageId` | `private var String?` | 当前段「按引用发送」得到的 messageId |
| `segmentIndex` | `@Volatile private var Int = 1` | 契约成员，从 1 开始 |
| `snapshot` | `private val StringBuilder` | 当前段的完整 markdown 文本（**只在 sink 协程内访问**，无锁） |
| `pendingChunks` | `Channel<String>(1, DROP_OLDEST)` 或 `AtomicReference<String?>` | 🔴 **1 槽 conflate**（D3 修复：禁止 `Channel(UNLIMITED)`，禁止四变量非原子组合）`[PLAN:228]`、`[PLAN:251]` |
| `segmentStartedAt` | `private var Long` | 当前段的起始时刻，用于 9min 轮换 |
| `open` | `@Volatile private var Boolean = true` | 契约 `isOpen` |
| `throttle` | `private var Long`（lastFireAt）+ `private var Int`（pendingChars） | 🔴 **双条件**节流（决策 19） |

🔴 **`sequence` 的实测依据与假设声明（决策 2）**：SDK 内部 `MarkdownStreamControllerImpl` 就是 `private int sequence` + `++sequence` `[SF:459-544]` Q3、`[BC §5.2]` ⇒ 「每流一个 Int」是与 SDK 同构的正确做法。
但**起始值（0 还是 1）与递增时机（patch 前 ++ 还是 patch 后 ++）在 S6 真机确认前是假设**。S6 脚手架用的是 `var seq = 0` + 每次 patch 前 `seq++`（即首个 patch 的 sequence == 1）`[SCAFFOLD S6:89-98]`。**本文档按脚手架写法定稿。**
**S6 失败时的调整位点**（唯一一处，改动面 ≤ 3 行）：
- 若真机报 `300317` 且提示 sequence 应从 0 开始 ⇒ 把 `++sequence` 改为 `sequence++`（首个 patch 用 0）；
- 若真机报 `300317` 且提示每次必须 +1 而我方跳号 ⇒ 检查节流路径是否有「跳过 patch 但仍递增」的分支（本文档定稿的实现里 `sequence` **只在真正发 patch/settings 前**递增，不会跳号）；
- 若真机要求 `settings`（结束流式）与最后一次 `patch` **共用**同一 sequence ⇒ 把 §4.7 的 `++sequence` 去掉，复用 patch 的值。
三个分支都只改 `LarkCardStreamSink.kt` 的递增位点，**不影响契约、不影响任何测试断言**（`SegmentRotationTest` 断言的是「从 0 单调递增」的**相对**性质，不是绝对值）。

> 🔴 **本文件禁止出现 `object` / `companion object` / 匿名 `object :`（M3-T4 的 F 组硬门禁）**
> 已交付的 `LarkSdkIsolationTest`（`composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/LarkSdkIsolationTest.kt:366-389`，F 组）对 lib 模块 `src/main/kotlin` 的每个文件做**行级启发式**判定：
> **同一文件内既有 `\b(?:companion\s+object|object)\b` 匹配、又有 `@Volatile` + `var`（同行或紧邻下一非空行）⇒ 直接 fail**（D16 静态全局可变状态守护）。
> 而 `LarkCardStreamSink.kt` 按本规格**必然**含两个 `@Volatile private var`（`segmentIndex` / `open`，上表）⇒ **只要该文件里再出现任何 `object` 字样，构建即红**。
> 因此定稿三条硬约束：
> ① `LarkCardStreamSink.kt` **不得**声明 `companion object`（需要常量 ⇒ 放 `Tuning.kt`；需要工厂 ⇒ 放 `SdkLarkGateway.kt` 的私有函数）；
> ② **不得**使用匿名 `object : Xxx { }` 表达式（需要回调 ⇒ 用 lambda 或具名私有类）；
> ③ 若确实需要 `companion object`，**唯一合法替代**是把 `segmentIndex` / `open` 从 `@Volatile var` 改为 `AtomicInteger` / `AtomicBoolean`（`SdkLarkGateway.kt` 的 `close()` 幂等已用 `AtomicBoolean`，风格一致）——🔴 但 `AtomicInteger.get()` 与契约 `segmentIndex: Int` 的**属性语义**不同（契约要求可直接读），需在实现里包一个 `val segmentIndex: Int get() = _seg.get()`，**契约签名不变**。
> ④ 注意 F 组的 `objectDeclRegex` 会命中 `data object`（`\bobject\b` 是词边界匹配）⇒ `GatewayModel.kt` / `MessageGateway.kt` 里的 `data object Idle` 等**已使这两个文件 hasObject=true**，故 🔴 **这两个文件也禁止出现 `@Volatile var`**（本规格未安排，天然安全，但施工者不得自行添加）。

### 4.2 cardkit 四步 API 调用序列（定稿，含真实签名）

全部签名来自 `[JAVAP]` 实测 + `[SCAFFOLD S6CardStreamSpike.kt]` 编译验证 + `[RB §8]` 汇总。四个方法**都 `throws Exception`** ⇒ 必须 `runCatching`。

**① 创建卡片实体** —— `client.cardkit().v1().card().create(CreateCardReq): CreateCardResp`

```kotlin
val cardJson = LarkOutboundMapper.toCardJson(initial.copy(streaming = true), elementId = Tuning.STREAM_ELEMENT_ID)
val body = CreateCardReqBody.newBuilder().type("card_json").data(cardJson).build()
val req  = CreateCardReq.newBuilder().createCardReqBody(body).build()
val resp = client.cardkit().v1().card().create(req)            // throws Exception
// 🔴 逐次检查（决策 1）：
if (!resp.success()) -> 映射 resp.code（§6.3）；200750 => CardEntityExpired（本次就是新建，说明凭据/权限问题）
val cardId = resp.data?.cardId                                 // CreateCardRespBody.getCardId()
```
- `[JAVAP CreateCardReq$Builder]`：`createCardReqBody(CreateCardReqBody)` / `getCreateCardReqBody()` / `build()`
- `[JAVAP CreateCardReqBody$Builder]`：`type(String)` / `data(String)`；`type` 的字面值 `"card_json"` 已由脚手架验证 `[SCAFFOLD S6]`
- `[JAVAP CreateCardResp]`：`extends BaseResponse<CreateCardRespBody>`

**② 按引用发送卡片** —— `client.im().v1().message().create(CreateMessageReq): CreateMessageResp`

```kotlin
val content = LarkOutboundMapper.toCardRefContent(cardId)   // {"type":"card","data":{"card_id":"<cardId>"}}
val body = CreateMessageReqBody.newBuilder()
    .receiveId(target.receiveId).msgType("card").content(content)
    .apply { idempotencyKey?.let { uuid(it) } }             // [JAVAP CreateMessageReqBody$Builder].uuid(String)
    .build()
val req  = CreateMessageReq.newBuilder()
    .receiveIdType(target.receiveIdType)                    // 🔴 字面串 "chat_id" / "open_id"（§2.2）
    .createMessageReqBody(body).build()
val resp = client.im().v1().message().create(req)
if (!resp.success()) -> 映射；val cardMessageId = resp.data?.messageId   // CreateMessageRespBody.getMessageId()
```
- `[JAVAP CreateMessageReq$Builder]`：`receiveIdType(String)` **或** `receiveIdType(CreateMessageCreateMessageV1ReceiveIDTypeEnum)`。🔴 `com.lark.oapi.service.im.v1.enums.ReceiveIdType` **这个类不存在**（javap 实测找不到）⇒ 定稿用**字面串**重载。
- 🔴 注意区分：`OutboundSender.sendCardByReference(String, ReceiveIdType, String, SendOptions)` 签名里的 `ReceiveIdType` 是 **channel 层的另一个类**（`com.lark.oapi.channel.outbound.*`），与 `im.v1` 无关。本文档不用 `OutboundSender`（偏差 B：`LarkChannel.outboundSender` 是 `private final` 无 getter `[SF:635-665]`），因此不涉及该类。

**③ 流式更新文本元素** —— `client.cardkit().v1().cardElement().patch(PatchCardElementReq): PatchCardElementResp`

```kotlin
val partial = LarkOutboundMapper.toPartialElementJson(snapshotText, elementId = Tuning.STREAM_ELEMENT_ID)
// = {"tag":"markdown","element_id":"stream_md","content":"<snapshot>"}
val body = PatchCardElementReqBody.newBuilder().partialElement(partial).sequence(sequence).build()
val req  = PatchCardElementReq.newBuilder().cardId(cardId).elementId(Tuning.STREAM_ELEMENT_ID)
             .patchCardElementReqBody(body).build()
val resp = client.cardkit().v1().cardElement().patch(req)
```
- `[JAVAP PatchCardElementReqBody$Builder]`：`partialElement(String)` / **`uuid(String)`** / `sequence(Integer)`
- `[JAVAP PatchCardElementResp]`：`extends BaseResponse<EmptyData>` ⇒ **无 data 可取，只能看 `code`/`msg`/`success()`**
- 🔴 **`patch` vs `content` 的选型**：`CardElement` 另有 `content(ContentCardElementReq)`，其 Body 是 `uuid` / `content` / `sequence(Integer)` `[JAVAP ContentCardElementReqBody]`。两者都能做「按 sequence 更新文本元素」。
  **本文档定稿用 `patch`**，理由：① SDK 自己的 `RawMessageSender.updateCardElementContent` 内部走的就是 `patch` + `partialElement`（`[SF:667-689]` 偏差 C 提到该方法名）；② S6 脚手架用 `patch` 并已编译通过 `[SCAFFOLD S6]`；③ `partialElement` 携带 `tag`+`element_id`+`content` 三元，语义比 `content` 的单串更明确。
  `content` 路径**保留为 S6 失败时的备选**（§4.10 降级分支 B）。此项列为附录 B 第 5 条上报（两条 API 并存、选型未经真机对比）。

**④ 结束流式** —— `client.cardkit().v1().card().settings(SettingsCardReq): SettingsCardResp`

```kotlin
val settings = LarkOutboundMapper.toFinishSettingsJson(finalText)
// = {"config":{"streaming_mode":false,"summary":{"content":"<finalText.take(80)>"}}}
val body = SettingsCardReqBody.newBuilder().settings(settings).sequence(sequence).build()
val req  = SettingsCardReq.newBuilder().cardId(cardId).settingsCardReqBody(body).build()
val resp = client.cardkit().v1().card().settings(req)
```
- `[JAVAP SettingsCardReqBody$Builder]`：`settings(String)` / **`uuid(String)`** / `sequence(Integer)`
- `[JAVAP SettingsCardResp]`：`extends BaseResponse<EmptyData>`

🔴 **`uuid` 字段的处置（本次定稿新发现，方案与脚手架均未使用）**：`PatchCardElementReqBody` 与 `SettingsCardReqBody` 都有 `uuid`。它是飞书侧的**幂等去重键**。
**本文档定稿：不填 `uuid`（保持 null）**。理由：① 我方 sequence 已经是幂等语义（同 sequence 重发是覆盖而非追加）；② 填 uuid 会让「同 uuid 的第二次请求被飞书静默忽略」，与我方「节流后重发最新快照」的语义冲突——节流窗口内快照是**变化**的，必须让飞书接受新值；③ 未经真机验证 uuid 与 sequence 的交互。
若 S6 观测到重试导致内容错乱，再启用 `uuid = "$cardId-$sequence"`（**改动位点：`LarkCardStreamSink` 的两处 `newBuilder()`，各加一行**）。列为附录 B 第 6 条上报。

### 4.3 sink 协程的主循环（定稿）

```kotlin
private val job = scope.launch(netDispatcher) {
    // —— 建段（第 1 段）——
    if (!rotateSegment(reason = null, initialText = initial.markdown)) { open = false; return@launch }
    segmentStartedAt = clock()
    while (isActive && open) {
        // 1 槽 conflate：取走当前待处理增量（可能为 null）
        val chunk = pendingChunks.tryReceive().getOrNull()
        if (chunk != null) {
            snapshot.append(chunk)
            pendingChars += chunk.length
        }
        // —— 🔴 决策 19：双条件节流（时间 **或** 字符增量，任一满足即 flush）——
        val elapsed = clock() - lastFireAt
        val timeHit  = elapsed >= Tuning.streamThrottleMs          // 250ms
        val charsHit = pendingChars >= Tuning.streamThrottleChars  // 4000
        val budgetHit = snapshotByteSize() >= Tuning.cardByteBudget // 24576 ⇒ 分段
        val deadlineHit = elapsedOfSegment() >= Tuning.streamSegmentDeadlineMs // 540_000 ⇒ 9min 轮换
        when {
            budgetHit   -> rotateSegment(RotationReason.ByteBudget, initialText = "")
            deadlineHit -> rotateSegment(RotationReason.TimeDeadline, initialText = "")
            timeHit || charsHit -> { if (pendingChars > 0 || chunk != null) firePatch(); }
        }
        sleeper(Tuning.sinkPollIntervalMs)   // 25ms；虚拟时间测试里由 advanceTimeBy 驱动
    }
}
```

🔴 **为什么必须同时看字符增量（决策 19）**：SDK 自带的 `Throttle.note()` 触发条件是 `pendingChars >= chars || elapsed >= ms`（**或**关系），`streamThrottleChars` 默认 **50** `[SF:912]`。
⇒ 只按时间节流会漏掉「250ms 内涌入 5000 字符」的情形：那时卡片内容已经远超预算但还没到时间窗口，会一次性 patch 一个巨大 payload，既撞 30KB 也可能撞飞书的单次请求体限制。
⇒ 本文档定稿 `streamThrottleChars = 4000`（不是 SDK 的 50），配合 `cardByteBudget = 24576` 的预算检查，形成「时间 250ms **或** 增量 4000 字符 **或** 累计 24576 字节」三重触发。
🔴 **不要把 `streamThrottleChars` 设成 50**：那会让节流退化成「每 50 字符一次 patch」，agent 高速输出时轻松突破 10 次/秒上限（R7②）。S6 脚手架用 `SpikeThrottle(ms=250, chars=4000)` 正是这个取值 `[SCAFFOLD S6:113]`。

🔴 **SDK 默认 `streamThrottleMs = 100`（= 10 次/秒）正好卡在飞书「单卡更新 10 次/秒」上限、零余量** `[SF:903]`、`[SF:1300]`。我方固定 **250ms（= 4 次/秒，留 60% 余量）**（决策 19、`[PLAN:78]`、`[BC:1035]` R7②）。

### 4.4 `tryAppend` / `isOpen`（1 槽 conflate，D3 修复）

```kotlin
override val isOpen: Boolean get() = open

override fun tryAppend(chunk: String): Boolean {
    if (!open || chunk.isEmpty()) return open
    // 🔴 1 槽 conflate：新 chunk 与槽内未取走的 chunk **拼接**（不是覆盖！覆盖会丢字），槽满即返回 true 表示已接受
    synchronizedSlot { buffered = (buffered ?: "") + chunk }   // 单字段 AtomicReference.updateAndGet，无锁
    return true
}
```

🔴 **D3 的精确含义**（`[PLAN:251]`「节流器用 `Channel(UNLIMITED)` + 四变量非原子组合有竞态」→「1 槽 conflate + 单一不可变快照」）：
- **禁止** `Channel(Channel.UNLIMITED)`：无界队列在 agent 高速输出时会无界增长（内存回归）；
- **禁止**四个独立可变变量（如 `pendingText` / `pendingLen` / `dirty` / `lastFlush`）分别读写：非原子组合会产生「dirty=true 但 pendingText 还是旧值」的中间态；
- **定稿**：**单一不可变快照**——用一个 `AtomicReference<Snapshot>`，`Snapshot` 是 `data class Snapshot(val text: String, val chars: Int, val dirty: Boolean)`，所有变更走 `updateAndGet { it.copy(...) }`，**一次原子操作完成三字段更新**。
```kotlin
private data class Snapshot(val text: String, val chars: Int, val dirty: Boolean)
private val slot = java.util.concurrent.atomic.AtomicReference(Snapshot("", 0, false))
override fun tryAppend(chunk: String): Boolean {
    if (!open || chunk.isEmpty()) return open
    slot.updateAndGet { it.copy(text = it.text + chunk, chars = it.chars + chunk.length, dirty = true) }
    return true
}
// sink 协程侧：取走并清零（一次原子操作）
private fun drain(): Snapshot = slot.getAndUpdate { Snapshot("", 0, false) }
```
🔴 **`tryAppend` 的返回语义**：`open == false` 时返回 `false`；否则**恒返回 `true`**（1 槽 conflate 永不拒绝，只会把新 chunk 拼进槽内）。调用方**不需要重投**。这与 `[PLAN:68]` 的 `tryAppend(chunk): Boolean` 签名兼容——返回值的意义是「sink 是否还接受输入」，不是「本次是否立即发出」。

### 4.5 24576 字节预算截断与分段轮换（R7④ / R20）

```kotlin
private fun snapshotByteSize(): Int = currentText.toByteArray(Charsets.UTF_8).size

private suspend fun rotateSegment(reason: RotationReason?, initialText: String): Boolean {
    // 1) 旧段收尾：发终态 settings（结束流式），不复用旧 cardId
    if (cardId != null) runCatching { fireSettings(finalText = oldSegmentSummary()) }
    // 2) 🔴 每次轮换 = 新建卡片实体（R20：卡片实体仅可发送一次 + 发送方必须是创建方 + 14 天有效期）
    val newCardJson = LarkOutboundMapper.toCardJson(
        initial.copy(markdown = initialText, streaming = true), Tuning.STREAM_ELEMENT_ID)
    val newCardId = createCardEntity(newCardJson) ?: return false
    val newMsgId  = sendCardByReference(newCardId) ?: return false
    // 3) 段状态归零
    cardId = newCardId; cardMessageId = newMsgId
    sequence = 0                       // 🔴 新实体 => sequence 重新从 0 计（脚手架同此 [SCAFFOLD S6:169]）
    slot.set(Snapshot(initialText, initialText.length, false))
    segmentStartedAt = clock()
    if (reason != null) { segmentIndex++; onSignal(GatewaySignal.StreamSegmentRotated(segmentIndex, reason, newMsgId)) }
    return true
}
```

**预算取值理由**：`cardByteBudget = 24576`（24KB）= 飞书单卡 **30KB** 硬上限留 **6KB（20%）** 给 JSON 转义膨胀 `[PLAN:78]`、`[BC:1035]`、`[BC:1246]` NF-03。
转义膨胀来源：markdown 里的 `"` `\` `\n` 在 JSON 串里各膨胀 1-5 字节，中文在 UTF-8 下已是 3 字节；6KB 余量对 24KB 正文足够。
🔴 **按 UTF-8 字节数算，不按字符数算**：飞书的 30KB 是**字节**限制（错误码 200860 的语义）。用 `String.length` 判断会在中文场景下**低估 3 倍**，直接撞 200860。

**`SegmentRotationTest` 的喂入方式（🔴 关键，见 `[BC §9 P4]`）**：
`ProgressCardRenderer.Limits.cardMaxChars = 5000` `[BC:1031]` ⇒ 若测试**经进度卡链路**（agent 输出 → `ProgressAccumulator` → `ProgressCardRenderer` → `CardSpec` → sink）喂 40KB，渲染器会在 5000 字符处就截断，sink 永远看不到 24576 字节 ⇒ 「40KB ⇒ 恰好 2 段」的断言**永不触发**（假绿）。
⇒ **明确要求：`SegmentRotationTest` 在 `CardStreamSink` 层直接喂 40KB**（绕过 `ProgressCardRenderer`），即直接构造 `LarkCardStreamSink` 并 `tryAppend` 累计 40 * 1024 字节的文本。
`cardMaxChars = 5000` 是否放宽属 `[BC §9 P4]` 待裁定点，**归 M4-T8 与 Leader**，不影响本测试的写法。

### 4.6 `segmentIndex` 与轮换触发条件汇总

| 触发 | 判定式 | 常量 | 产出信号 | 出处 |
|---|---|---|---|---|
| 字节预算 | `snapshotByteSize() >= 24576` | `Tuning.cardByteBudget` | `StreamSegmentRotated(idx, ByteBudget, newMsgId)` | R7④ `[PLAN:519]`、NF-03 `[BC:1246]` |
| 时间期限 | `clock() - segmentStartedAt >= 540_000` | `Tuning.streamSegmentDeadlineMs` | `StreamSegmentRotated(idx, TimeDeadline, newMsgId)` | R7⑤ + R20，NF-04 `[BC:1247]`。9min 避开飞书 **10min 自动关流** |
| 实体过期 | patch/settings 返回 `200750` | — | `StreamSegmentRotated(idx, EntityExpired, newMsgId)` + `SendFailed(CardEntityExpired, ...)` | R20 `[PLAN:532]`、决策 21 |
| sequence 冲突 | patch 返回 `300317` | — | `StreamSegmentRotated(idx, SequenceConflict, newMsgId)` + `SendFailed(CardSequenceConflict, ...)` | `[BC §5.3]`、决策 1 |

`segmentIndex` 从 **1** 开始（第 1 段），每次轮换后 `++`。契约 `[PLAN:69]` 只写 `val segmentIndex: Int` 未定起始值 ⇒ 本文档定稿 **1-based**（与 `StreamSegmentRotated.segmentIndex` 一致，语义是「轮换后进入第 N 段」）。
🔴 **9min 轮换的实测状态**：`[RB §6.3]` 明确「9/10 分钟轮换可能未实测」⇒ `Stream10MinRotationTest` 用**虚拟时间**断言（不连真机），真机行为在 T11 收口（§13 第 6 条）。

### 4.7 `flushTerminal(card)`（终态卡）

```kotlin
override suspend fun flushTerminal(card: CardSpec) = withContext(NonCancellable + netDispatcher) {
    if (!open) return@withContext
    // 1) 🔴 终态更新**不受节流窗口限制**，立即 flush（NF-02 断言 [BC:1245]）
    val finalText = LarkOutboundMapper.toTerminalMarkdown(card, fallback = slot.get().text)
    ++sequence
    runCatching { firePatch(finalText) }.onFailure { recordCardFailure(it, "flushTerminal.patch") }
    // 2) 结束流式：streaming_mode = false + summary
    ++sequence
    runCatching { fireSettings(finalText) }.onFailure { recordCardFailure(it, "flushTerminal.settings") }
    // 3) 置关闭
    open = false
}
```

🔴 **`NonCancellable` 是强制的**：十步关闭第 ④ 步「遍历开放卡片流发**终态卡** + `sink.close()`（`NonCancellable`, 3s）」`[PLAN:239]`。若不包 `NonCancellable`，外层 `stop()` 的 cancel 会在终态卡发出前打断协程 ⇒ 用户的卡片**永久停在「生成中」态**（这正是 `[BC §5.3]` 描述的 SDK 静默失败的用户可见后果）。

**终态卡的文案与配色（字面值，🔒 不得改动）** `[BC:1032]`：
- 受理态：文案含 `已受理`，`titleColor = BLUE`（`header.template == "blue"`）
- 进行中：文案含 `已运行 35s` 形态（`已运行 <秒>s`），图标 `💭`（thinking）/ `🔧`（tool_use）/ `✅`（tool_result 成功）
- 终态 DONE：文案 `已完成`，`titleColor = GREEN`
- 终态 FAILED：文案 `失败`，`titleColor = RED`
- 排版（`[BC:995-997]`）：`lines[0]` = 首行（类型前缀 + 标题）；`lines[1]` = `result.summary`；`lines[2]` = `` 错误码：`<code>` ``（反引号包裹，仅 errorCode 非空）；`lines[3]` = `耗时：<x.x>s`（`elapsedMillis / 1000.0`，仅 `> 0`）
- `kindLabel`：`AGENT→"🤖 Agent"`、`SKILL→"🧩 快捷指令"`、`COMMAND→"⚡ 指令"`
- 单条超长截断加省略号 `…`（BC-339）

🔴 **这些字面值的渲染归 `:lark-bridge` 的 `ProgressCardRenderer`（M4-T8），不归网关**。网关只负责把 `CardSpec` 忠实转成卡片 JSON（§6.2）。本节列出它们是为了让 T3 施工者理解 `CardSpec` 字段的**用途**，不要在网关侧重复实现渲染。

### 4.8 `close()`（幂等）

```kotlin
override suspend fun close() = withContext(NonCancellable + netDispatcher) {
    if (!open) return@withContext                 // 🔴 幂等：重复 close 无副作用（不会二次发终态卡）
    withTimeoutOrNull(Tuning.sinkCloseTimeoutMs) { // 3000，对齐十步关闭第 ④ 步的 3s 上限
        val snap = slot.get()
        if (snap.text.isNotEmpty() && cardId != null) {
            ++sequence; runCatching { firePatch(snap.text) }
            ++sequence; runCatching { fireSettings(snap.text) }
        }
    }
    open = false
    job.cancel()
}
```

🔴 `close()` 与 `flushTerminal()` 的分工：`flushTerminal` 由引擎在任务正常/异常结束时调用（带完整终态文案与配色）；`close` 是**兜底**（引擎忘了调 flushTerminal、或十步关闭第 ④ 步遍历开放流时），用当前快照补一次终态，避免卡片停在「生成中」。
🔴 十步关闭第 ④ 步的正确写法是 **先 `flushTerminal(终态卡)` 再 `close()`**；`close()` 的幂等保证第二步不会重复发卡。

### 4.9 逐次响应码检查与错误映射（🔴 决策 1 的核心，SDK 侧全静默）

```kotlin
/** 每一次 cardkit / im 调用之后都必须过这个函数。返回 true = 可继续；false = 已按错误语义处置。 */
private fun check(tag: String, resp: com.lark.oapi.core.response.BaseResponse<*>): Boolean {
    val code = resp.code                       // [JAVAP BaseResponse].getCode(): int
    if (resp.success() && code == 0) return true
    val http = resp.rawResponse?.statusCode    // [JAVAP RawResponse].getStatusCode(): int
    val resetSec = readRateLimitResetHeader(resp)   // x-ogw-ratelimit-reset（存在性 待 S6 确认，§13 第 4 条）
    val gw = LarkErrorMapper.fromLarkCode(code, http)
    failureCounters.increment(gw)              // 🔴 三件套①：按业务码分类计数，暴露为指标
    log.warn("cardkit $tag 失败 code=$code http=$http msg=${resp.msg} cardId=$cardId seq=$sequence")  // 🔴 三件套②：含 card_id 与 sequence
    onSignal(GatewaySignal.SendFailed(gw, resp.msg ?: "", target.receiveId))                          // 🔴 三件套③：错误映射
    if (resetSec != null || gw == GatewayErrorCode.RateLimited) {
        onSignal(GatewaySignal.RateLimited(resetSec, code, target.receiveId))
    }
    // 按码分派处置（[BC §5.3] 的「错误映射 → 处置」四条）
    when (gw) {
        GatewayErrorCode.CardSequenceConflict -> { /* 300317 → 重建流 */ pendingAction = Action.RebuildStream }
        GatewayErrorCode.CardTooLarge         -> { /* 200860 → 触发分段轮换 */ pendingAction = Action.Rotate(RotationReason.ByteBudget) }
        GatewayErrorCode.CardEntityExpired    -> { /* 200750 → 新建卡片实体 */ pendingAction = Action.Rotate(RotationReason.EntityExpired) }
        GatewayErrorCode.CardStreamBusy       -> { /* 200810 → 契约层已禁止流式卡带交互组件，此处只记录 */ pendingAction = Action.None }
        GatewayErrorCode.CardJsonInvalid      -> { /* 300302 / 230099 → 卡片 JSON 结构错，不重试（重试必然再错） */ pendingAction = Action.GiveUp }
        GatewayErrorCode.RateLimited          -> { pendingAction = Action.Backoff(resetSec) }
        else                                  -> { pendingAction = Action.GiveUp }
    }
    return false
}

private fun readRateLimitResetHeader(resp: com.lark.oapi.core.response.BaseResponse<*>): Int? {
    val headers = resp.rawResponse?.headers ?: return null   // [JAVAP RawResponse].getHeaders(): Map<String, List<String>>
    for ((k, v) in headers) if (k.equals(Tuning.rateLimitResetHeader, ignoreCase = true)) return v?.firstOrNull()?.toIntOrNull()
    return null
}
```

🔴 **「卡片更新失败必须可观测」三件套缺一不可**（NF-A3 `[BC:1271]`、`[BC §5.3]`）：**计数** + **日志**（含 `card_id` 与业务码与 sequence）+ **错误映射**（每个码有专属 `GatewayErrorCode`）。`LarkGatewayErrorMappingTest` 逐条断言。
🔴 **根因回顾**：SDK `RawMessageSender` 的 `createCardInstance` / `updateCardElementContent` / `finishStreamingCard` / `patchCard` **四个方法均不调用 `assertSuccessfulResponse`**（`assertSuccessfulResponse` 只被 `rawSend` / `createCardInstance` 之外的路径调用，实测见 `[SF:667-689]` 偏差 C）⇒ HTTP 200 + 业务码失败时**无异常、无日志、无返回值差异**。我方走 raw client 就**必须**自己补上这一层，否则等于把 SDK 的缺陷原样继承。

**已知业务码与处置对照（🔴 与 §6.3 的映射表一致，此处给处置动作）**：

| 业务码 | 语义 | `GatewayErrorCode` | 处置 | 是否重试 |
|---|---|---|---|---|
| `300317` | sequence 不连续 / 乱序 | `CardSequenceConflict` | 重建流（新实体 + sequence 归零） | 是（新实体后重发当前快照） |
| `200860` | 卡片内容超 30KB | `CardTooLarge` | 触发分段轮换 | 是（轮换后重发） |
| `200810` | 流式期间无法响应卡片回传 | `CardStreamBusy` | **无处置**——契约层已禁止（决策 22）；命中说明有非流式卡误开了 streaming_mode | 否 |
| `200750` | 卡片实体 14 天过期 / 仅可发送一次 | `CardEntityExpired` | 新建卡片实体（R20） | 是 |
| `300302` | 与 `update_multi` 相关（SDK 生成的卡未设该字段） | `CardJsonInvalid` | 🔴 我方自建卡 JSON **显式设 `config.update_multi = true`**（§6.2）；命中即说明该字段被漏掉或真机语义与预期不符 | 否 |
| `230099` | `parse card json err: unknown property: action` | `CardJsonInvalid` | button 必须走 `behaviors` 数组，禁 `action` 包裹 `[BC:1030]` | 否 |
| `11232` | 整点压力限流（无官方明文） | `RateLimited` | 退避（`RateGovernor`）。**是否真机可观测 待确认**（§13 第 5 条） | 是（退避后） |
| HTTP `429` | 频控 | `RateLimited` | 读 `x-ogw-ratelimit-reset` 退避 | 是 |

### 4.10 S6 的两种结果分支（决策 23，🔴 必须两条都留）

S6（卡片流式限制）**尚未真机执行**（凭据未到）。判定标准来自 `[RB §5.2]`：② `throttle250` 实验**零业务错误码** + ⑤ `overflow` 实验**主动分段规避 200860** ⇒ raw-client cardkit 路线成立。

**分支 A：S6 PASS ⇒ 按 §4.1-§4.9 的打字机流式实现（本文档主体即此分支）**
- 需要按真机输出回填的项：`sequence` 起始值与递增时机（§4.1）、卡片 JSON 的 `update_multi` / `streaming_config` 结构（§6.2）、`x-ogw-ratelimit-reset` 头是否存在（§4.9）、`rawContentType` 各类型实际取值（§6.1）。全部在 §13 清单内。
- 改动面：**只改常量与 JSON 结构字面值**，不改类结构、不改契约、不改测试断言形态。

**分支 B：S6 FAIL ⇒ 降级「非流式分段 `updateCard`」**（`[PLAN:519]` R7 的既定触发后应对、`[RB §6.2]`）

| 层级 | 降级动作 | 改动文件 | 测试断言形态变化 |
|---|---|---|---|
| **B-0（最轻）** 节流参数不够 | `streamThrottleMs` 250 → 400 或 500；`streaming_config.step` 5 → 10 | 只改 `Tuning.kt` 两个常量 | `CardThrottleTest` 的阈值从「1000 append/s ⇒ ≤4 update」改为「⇒ ≤2.5 update」/「≤2 update」；`SegmentRotationTest` 不变 |
| **B-1** `patch` 路径不通 | 改用 `cardkit().v1().cardElement().content(ContentCardElementReq)`（`content` + `uuid` + `sequence`）`[JAVAP ContentCardElementReqBody]` | `LarkCardStreamSink.kt` 的 `firePatch()` 一个方法 + `LarkOutboundMapper` 加一个 `toContentJson()` | 无断言形态变化（`CardThrottleTest` / `SegmentRotationTest` 都通过注入的 fake client 计数，与具体 API 无关） |
| **B-2（最重）** 流式整体不可用 | **放弃打字机**，改「非流式分段 `updateCard`」：卡片 JSON 不设 `streaming_mode`，用 `cardkit().v1().card().update(UpdateCardReq)`（`[JAVAP cardkit.v1.resource.Card].update(UpdateCardReq[, RequestOptions])`）按 250ms 节流**整卡替换**；分段轮换逻辑不变 | `LarkCardStreamSink.kt`（`firePatch` → `fireUpdate`、去掉 `fireSettings`）、`LarkOutboundMapper.kt`（去掉 `streaming_mode` / `streaming_config` / `summary`，`toCardJson` 改为非流式形态）、`Tuning.kt`（`streamSegmentDeadlineMs` 可保留但意义弱化为「防单卡过长」） | 🔴 **`CardThrottleTest` 断言形态变化**：仍断言「1000 append/s ⇒ ≤4 update」，但被测方法名从 `patchElement` 改为 `updateCard`；**`SegmentRotationTest` 断言形态变化**：仍断言「40KB ⇒ 恰好 2 段 + 每段新建实体」，但**去掉**「`sequence` 从 0 单调递增」这一子断言（非流式 `updateCard` 无 sequence 概念），改为断言「每段的 `cardId` 互不相同」；`Stream10MinRotationTest` **保留**（10min 自动关流是卡片实体级限制，与是否流式无关） |

🔴 **分支 B 不改契约**：`CardStreamSink` 的 5 个成员、`CardSpec`、`GatewaySignal.StreamSegmentRotated` 全部不变 ⇒ 引擎侧（M4-T8 的 `ProgressCardRenderer` 与 handlers）**零改动**。这是「契约最小面 + 逃生舱在网关内」设计（R1 缓解 `[PLAN:513]`）的直接收益。
🔴 **分支 B 的用户可见降级**：卡片不再逐字打字，而是每 250ms 整卡刷新一次。功能完整可用，观感变差。这是 `[PLAN:519]` 明示的可接受代价。

---

## 5. `LarkPolicyMapper` 逐字段映射表

### 5.1 `LarkPolicy` 与 `GatewaySdkSettings`（定稿）

```kotlin
/** 契约层策略。🔴 只含 SDK PolicyConfig 能表达的 5 个字段（决策 3：语义收窄）。 */
data class LarkPolicy(
    val groupAllowlist: List<String> = emptyList(),     // 引擎恒传 emptyList（全放行）
    val dmMode: String = "open",                        // "open" | "disabled" | "allowlist"；引擎恒传 "open"
    val dmAllowlist: List<String> = emptyList(),        // dmMode == "allowlist" 时才有意义；引擎恒传 emptyList
    val requireMention: Boolean = false,                // ← access.groupRequireMention 的唯一下推项
    val respondToMentionAll: Boolean = true,            // 引擎恒传 true
)

/** mapper 的输出：纯数据，字段名与 SDK 属性名 1:1。这是「mapper 零 SDK 类型」的关键（§2.1）。 */
data class GatewaySdkSettings(
    // —— SafetyConfig ——
    val chatQueueEnabled: Boolean = false,        // 🔴 决策 4：SDK 属性名 isChatQueueEnabled，恒 false
    val dedupTtlMs: Long = 43_200_000L,           // 12h（SDK 默认）[SF:1299]
    val dedupMaxEntries: Int = 5000,              // SDK 默认
    val dedupSweepMs: Long = 300_000L,            // 5min（SDK 默认）
    val staleMessageWindowMs: Long = 1_800_000L,  // 30min（SDK 默认）
    val processingLockTtlMs: Long = 300_000L,     // 5min（SDK 默认）
    // —— PolicyConfig ——
    val groupAllowlist: List<String> = emptyList(),
    val dmMode: String = "open",
    val dmAllowlist: List<String> = emptyList(),
    val requireMention: Boolean = false,
    val respondToMentionAll: Boolean = true,
    // —— BatchTextConfig（入站合批）—— 🔴 chatQueueEnabled=false 后全部失效，仅为完整性保留默认值
    val batchDelayMs: Int = 600,                  // SDK 默认 [SF:737-778] 偏差 E
    // —— OutboundConfig（出站）——
    val textChunkLimit: Int = 3500,               // 🔴 决策 18：出站分片属 OutboundConfig，不是 BatchTextConfig
    val streamThrottleMs: Int = 250,              // 🔴 决策 19：SDK 默认 100（零余量），我方 250（留 60% 余量）
    val streamThrottleChars: Int = 4000,          // 🔴 决策 19：SDK 默认 50，必须调大（Throttle 是「或」关系）
    val streamInitialText: String = "Thinking...",// SDK 默认 [SF:905]；我方不用（自建卡 JSON），保留默认
    val ssrfGuardEnabled: Boolean = true,         // SDK 默认 true [SF:906]
    val ssrfAllowlist: List<String> = emptyList(),// SDK 默认空
    val allowedFileDirs: List<String> = emptyList(), // 🔴 SDK 默认空 ⇒ 必须由引擎注入（[SF:916]：不填会被 SSRF 防护拦掉本地路径）
    // —— RetryConfig ——
    val retryMaxAttempts: Int = 1,                // 🔴 决策 6：SDK 默认 3，关掉盲指数退避
    val retryBaseDelayMs: Int = 500,              // SDK 默认；maxAttempts=1 时不生效
    // —— 网关自有（非 SDK 字段，一并放这里以便单点注入）——
    val downloadMaxBytes: Long = 104_857_600L,    // = fileMessage.maxFileSizeBytes 默认 100MB [BC §5.9]
    val downloadResourceType: String = "file",    // GetMessageResourceReq.setType()
    val downloadSingleFlight: Int = 2,            // 并发下载上限（§3.7 防护 ②的堆峰值缓解）
) {
    companion object { val DEFAULT = GatewaySdkSettings() }
}
```

### 5.2 逐字段映射表（我方 → SDK / 或「不下推」）

| 我方来源（`[CDS §3.2.2]` `access.*`） | `GatewaySdkSettings` 字段 | SDK 目标 | 下推？ | 定稿取值 | 理由 / 出处 |
|---|---|---|---|---|---|
| `access.mode`（allowlist/blocklist） | — | — | 🔴 **不下推** | 由引擎 `AccessControl` + `PipelineSnapshot` 承担 | SDK `PolicyConfig` **无黑名单能力**（只有 `groupAllowlist`/`dmMode`/`dmAllowlist`/`requireMention`/`respondToMentionAll`）`[SF:809]` D-2；决策 3 |
| `access.allowedSenders` | — | — | 🔴 **不下推** | 由引擎 `AccessControl` 承担 | 同上。SDK 的 `groupAllowlist` 是**群**白名单（chat_id 维度），不是 **sender** 白名单（open_id 维度），**语义不同，不可混用** |
| `access.blockedSenders` | — | — | 🔴 **不下推** | 由引擎 `AccessControl` 承担 | SDK 无黑名单 |
| `access.rejectionReply` | — | — | 🔴 **不下推** | 由引擎承担 | SDK `PolicyGate` 的 reject **只发 `"reject"` 事件、不给我方处理链介入机会**，无法做「拒绝但回一句提示」`[SF:809]` D-2 |
| `access.groupRequireMention` | `requireMention` | `PolicyConfig.setRequireMention(boolean)` | ✅ **唯一下推项** | 跟随配置（默认 `true` `[CDS:110]`） | 决策 3。这是 SDK 侧唯一能帮我方省流量的开关（未 @ 的群消息在 SDK 层就被 `PolicyGate` 拦掉，不进 handler） |
| —（无对应配置项） | `respondToMentionAll` | `PolicyConfig.setRespondToMentionAll(boolean)` | ✅ 恒 `true` | `true` | 全放行；@所有人 也应触发（旧实现行为） |
| —（无对应配置项） | `groupAllowlist` | `PolicyConfig.setGroupAllowlist(List)` | ✅ 恒 `emptyList()` | `emptyList()` | 全放行（单一裁决点在引擎） |
| —（无对应配置项） | `dmMode` | `PolicyConfig.setDmMode(String)` | ✅ 恒 `"open"` | `"open"` | 全放行 |
| —（无对应配置项） | `dmAllowlist` | `PolicyConfig.setDmAllowlist(List)` | ✅ 恒 `emptyList()` | `emptyList()` | `dmMode="open"` 时不使用 |
| 🔴 **无配置项（硬编码）** | `chatQueueEnabled` | `SafetyConfig.setChatQueueEnabled(false)` / Kotlin `isChatQueueEnabled = false` | ✅ 恒 `false` | `false` | **决策 4**：默认 `true` + `BatchTextConfig.delayMs=600` 会合并同 chat 600ms 内多条消息（`content` 用 `\n\n` 拼接、`messageId`/`senderId`/`createTime` 只保留最后一条），破坏发送者归属/审计/幂等键，让「停止」指令的 URGENT 通路失效、每条入站固有 +600ms 延迟、与我方 `ChatLane` 双重排队 `[BC §5.5]`、`[SF:1301]`。🔴 **不暴露为用户配置旋钮**（符合已否决方案「把 SDK 调优旋钮暴露进配置文档」`[PLAN:560]`） |
| 🔴 **无配置项（硬编码）** | `retryMaxAttempts` | `RetryConfig.maxAttempts` | ✅ 恒 `1` | `1` | **决策 6**：SDK 默认 3 + `baseDelayMs=500` 的**盲指数退避**（`sleep(baseDelayMs * 3^(attempt-1))` = 500ms→1.5s→4.5s），且用 `Thread.sleep` **占住 commonPool 线程** `[SF:545-590]` Q4 FAIL。关掉以免与我方 `RateGovernor` 双层放大 `[BC §5.4]` |
| 🔴 **无配置项（硬编码）** | `streamThrottleMs` | `OutboundConfig.streamThrottleMs` | ✅ 恒 `250` | `250` | 决策 19 + `[PLAN:78]` + R7② |
| 🔴 **无配置项（硬编码）** | `streamThrottleChars` | `OutboundConfig.streamThrottleChars` | ✅ 恒 `4000` | `4000` | 决策 19：`Throttle.note()` 是 `pendingChars >= chars \|\| elapsed >= ms` 的**或**关系，SDK 默认 50 会让节流失效 `[SF:912]` |
| 🔴 **无配置项（硬编码）** | `textChunkLimit` | `OutboundConfig.textChunkLimit` | ✅ 恒 `3500` | `3500` | **决策 18**：这是**出站**文本分片阈值，属 `OutboundConfig`；方案用词有误把它归给 `BatchTextConfig`（入站合批）`[SF:902]`。🔴 该值是 `OutboundSender` **构造时快照，不可热更** `[SF:914]` |
| `fileMessage.*`（`[CDS §3.2.6]`） | `ssrfGuardEnabled` / `ssrfAllowlist` / `allowedFileDirs` | `OutboundConfig.*` | ✅ | `true` / `emptyList()` / 🔴 **由引擎注入 workspace 根与 extraRoots** | `[SF:916]`：`ssrfGuardEnabled` 默认开、`allowedFileDirs` 默认空 ⇒ 不显式填会被 SSRF 防护拦掉本地路径。M4-T8 的 `files` handler 必须配 |
| `observability.*` / SDK 默认 | `dedupTtlMs` / `dedupMaxEntries` / `dedupSweepMs` / `staleMessageWindowMs` / `processingLockTtlMs` | `SafetyConfig.*` | ✅ 沿用 SDK 默认 | 12h / 5000 / 5min / 30min / 5min | `[SF:1299]`。SDK 去重作**第二道网**，我方 `MessageDeduper`（LRU 4096 + TTL 12h）是第一道 `[BC §5.1]`、NF-A1 ③「**不自研第二套去重语义**」 |
| `fileMessage.maxFileSizeBytes`（`[CDS §3.2.6]`） | `downloadMaxBytes` | 网关自有（非 SDK） | ✅ | 默认 100MB | `[BC §5.9]` |

### 5.3 `updatePolicy(policy)` 的语义收窄与「全量覆盖」陷阱（🔴 决策 7）

**契约语义（定稿）**：`updatePolicy(policy)` 是**热更新、不重连**（消灭 D8「改白名单就断连」`[PLAN:60]`、`[PLAN:256]`），但**语义收窄**为：
> 「网关只转发 SDK 能吃下的子集（`PolicyConfig` 的 5 个字段），其余（sender 级 allow/block、blocklist、rejectionReply）由引擎 `PipelineSnapshot` **原子替换**承担 `[CDS §4.4]`。」

🔴 **SDK 侧 `updatePolicy(partial)` 是「全量覆盖那 5 个字段」而非增量 merge** `[SF:691-735]` 偏差 D。SDK 原文语义：传入的 `PolicyConfig` 对象的**全部 5 个字段**会整体替换掉当前生效值；未设置的字段取其**默认值**（不是保留原值）。
⇒ **实现必须先复制当前完整 policy，再改目标字段**：

```kotlin
private val currentPolicy = java.util.concurrent.atomic.AtomicReference(LarkPolicy())

override suspend fun updatePolicy(policy: LarkPolicy) = withContext(netDispatcher) {
    // 🔴 决策 7：全量覆盖语义 ⇒ 必须把「新 policy」与「当前 policy」合并成完整 5 字段后再交给 SDK。
    //    本实现里 LarkPolicy 本身就是完整 5 字段（无 nullable），所以直接整体替换即可；
    //    但**绝不能**只把「变化了的字段」构造成一个部分填充的 PolicyConfig 传下去——
    //    那样 groupAllowlist 会被清空成 SDK 默认值（emptyList），dmMode 会被重置成默认。
    val merged = currentPolicy.getAndSet(policy)
    val ch = channel ?: return@withContext
    runCatching {
        val sdkPolicy = LarkChannelOptions.PolicyConfig().apply {
            setGroupAllowlist(policy.groupAllowlist)     // 全 5 字段逐一显式设置，一个都不漏
            setDmMode(policy.dmMode)
            setDmAllowlist(policy.dmAllowlist)
            setRequireMention(policy.requireMention)
            setRespondToMentionAll(policy.respondToMentionAll)
        }
        ch.updatePolicy(sdkPolicy)                       // void，同步 [SF:826]
    }.onFailure { log.error("updatePolicy 失败（不断连，policy 保持 SDK 侧原值）", it) }
}
```

🔴 **`PolicyMappingTest` 必须有的断言**（§11 第 1 项）：构造「只改 `requireMention`」的调用序列，断言传给 SDK 的 `PolicyConfig` 的**其余 4 个字段仍等于当前完整 policy 的值**（不是 SDK 默认值）。这是决策 7 的唯一守护点——该缺陷**不会抛异常、不会报错，只会静默把白名单清空**。

**热更生效范围（`[SF:914]` 的混合热更语义，必须在文档里区分）**：

| 旋钮 | 热更是否生效 | 原因 |
|---|---|---|
| `PolicyConfig` 的 5 个字段 | ✅ 立即生效 | `updatePolicy` 是 SDK 提供的热更入口 |
| `OutboundConfig.streamThrottleMs` / `streamThrottleChars` / `streamInitialText` | ✅ 立即生效 | SDK 每次读 config `[SF:914]` |
| `OutboundConfig.textChunkLimit` | ❌ **需重连** | `OutboundSender` 构造器里 `this.chunkLimit = options.getOutbound().getTextChunkLimit()` 是**构造时快照** `[SF:914]` |
| `SafetyConfig` 全部字段（含 `chatQueueEnabled`） | ❌ **需重连** | 无热更入口；`SafetyPipeline` 在 channel 构造时装配 |
| `RetryConfig` | ❌ **需重连** | 同上 |

⇒ **本文档定稿：这些旋钮全部硬编码为常量（`GatewaySdkSettings.DEFAULT` + `Tuning.kt`），不暴露给用户配置**，因此「需重连生效」的问题在本产品里**不存在**。这也是 `[PLAN:560]` 已否决「把 SDK 调优旋钮暴露进配置文档」的额外支撑理由。**唯一跟随用户配置变化的是 `requireMention`（HOT）与 `allowedFileDirs`（需重连，由引擎在 RESTART 类变更时重连处理，`[CDS §5.5]` applyMode 分类）。**

---

## 6. `LarkInboundMapper` / `LarkOutboundMapper` / `LarkErrorMapper` 规格

### 6.0 三个 mapper 的共同纪律（🔴 强制）

| # | 纪律 | 理由与出处 |
|---|---|---|
| 1 | **签名中零 SDK 类型**（无 `NormalizedMessage` / `CardActionEvent` / `BaseResponse` / `Client`） | 决策 16 + §2.1：SDK 隔离三重强制的第三重（可测性）。四个 mapper 测试（§11）**不加载 SDK 类**即可跑绿 |
| 2 | **纯函数、无状态、无 IO、无隐式时钟** | 时钟一律由调用方注入（`gatewayReceivedAtMillis` 由 `SdkLarkGateway` 填） |
| 3 | **未知值一律走 fallback，绝不抛异常** | 决策 15 明令 `rawContentType` 映射「容忍未知值」；`[BC §9 P11]` 指出卡片回传的实际取值尚未真机抓取 |
| 4 | **唯一允许抛异常的点**：`streaming == true && buttons.isNotEmpty()` ⇒ `IllegalArgumentException` | 决策 22 的快速失败（200810 是硬互斥，静默降级会让确认链路卡死到超时）。NF-A6 ⑥ 负向断言守护 `[BC:1274]` |
| 5 | **禁止在 mapper 内做重试、退避、频控判断** | 决策 6：重试归 `RateGovernor`（M4-T7）；mapper 只做形状转换 |
| 6 | **禁止读 raw JSON** | 决策 14：`includeRawInMessage` / `includeRawEvent` 已 deprecated，生产代码只用 typed 字段 |

### 6.1 `LarkInboundMapper`（文件 `LarkInboundMapper.kt`）

#### 6.1.1 输入类型 `NormalizedMessageRecord`（定稿，纯 data class）

字段名 = `NormalizedMessage` 的 **16 个 getter** 去 `get`/`is` 前缀后的驼峰形态 `[SF:848-853]`、决策 15：

```kotlin
package com.jiaoay.lark.gateway.internal   // 🔴 internal 包：非契约面，:lark-bridge 不应引用

/** NormalizedMessage 的只读快照。由 SdkLarkGateway 的 capture() 在**回调线程内**填充（微秒级）。 */
data class NormalizedMessageRecord(
    val messageId: String,
    val chatId: String,
    val chatType: String?,        // "p2p" | "group"（SDK 实测字面值，[BC §5.8]）
    val senderId: String,
    val senderName: String?,
    val content: String?,         // 已转换 + 已剥离 bot mention（stripBotMentions=true 硬编码 [SF:857]）
    val rawContentType: String?,  // 🔴 不是 messageType（决策 15）
    val resources: List<ResourceRecord>,
    val mentions: List<MentionRecord>,
    val mentionAll: Boolean,
    val mentionedBot: Boolean,
    val rootId: String?,
    val threadId: String?,
    val replyToMessageId: String?,
    val createTime: Long,         // long 毫秒（不是字符串）[BC §5.8]
) {
    data class ResourceRecord(val type: String?, val fileKey: String?, val fileName: String?, val durationMs: Long)
    data class MentionRecord(val key: String?, val openId: String?, val userId: String?, val name: String?)
}

/** CardActionEvent 的只读快照（8 个 getter 中的 5 个，[RB §8] / [SCAFFOLD S4:184-203]）。 */
data class CardActionRecord(
    val operatorId: String?, val actionTag: String?, val actionName: String?,
    val actionOption: String?, val actionValue: String?,
    val messageId: String?, val chatId: String?,   // 卡片回传也要能定位会话
)
```

> 🔴 **`raw` 字段刻意不进 `NormalizedMessageRecord`**（决策 14）。若 S4 真机需要观测 raw，只在 spike 脚手架内打印，不进生产类型。
> 🔴 **无 `eventId` 字段**（决策 15 + `[BC §5.1]`）：SDK 的 `NormalizedMessage` 本就没有该 getter ⇒ 幂等键误用 `eventId` 在类型层面即不可能（D12 的结构性修复）。

#### 6.1.2 方法签名（定稿）

```kotlin
object LarkInboundMapper {
    fun fromMessage(r: NormalizedMessageRecord, receivedAtMillis: Long): InboundMessage
    fun fromCardAction(r: CardActionRecord, receivedAtMillis: Long): InboundMessage
    fun chatTypeOf(raw: String?): ChatType          // internal，供测试直接断言
    fun kindOf(rawContentType: String?): InboundKind // internal，供测试直接断言
}
```

#### 6.1.3 字段对照表（`NormalizedMessageRecord` → `InboundMessage`）

| `InboundMessage` 字段 | 来源 | 转换规则 | 备注 |
|---|---|---|---|
| `messageId` | `r.messageId` | 原样 | 🔴 幂等键（D12）。`isBlank()` ⇒ **仍产出** `InboundMessage`，由引擎 `MessageDeduper` 丢弃（NF-A1 ④ 的 `messageId == ""` 用例归 M4-T7，网关不代为丢弃） |
| `chatId` | `r.chatId` | 原样 | |
| `chatType` | `r.chatType` | `"p2p"`→`P2P`；`"group"`→`GROUP`；其它/`null`→`UNKNOWN` | **大小写敏感精确匹配**（SDK 字面值实测小写 `[BC §5.8]`）；`UNKNOWN` 不抛 |
| `senderId` | `r.senderId` | 原样 | 访问控制的输入（决策 3：由引擎 `AccessControl` 承担） |
| `senderName` | `r.senderName` | `?: ""` | SDK 只从事件本身取，不额外发 HTTP `[SF:855]` |
| `text` | `r.content` | `?: ""` | 🔴 **不得二次剥离 `@机器人`**——SDK 已剥（`stripBotMentions=true` 硬编码 `[SF:857]`）。旧实现若自剥，行为基线标「SDK 吸收」 |
| `kind` | `r.rawContentType` | 见 §6.1.4 | |
| `rawContentType` | `r.rawContentType` | **原样透传**（含未知值） | 供可观测与 P11 回填；不做归一化 |
| `resources` | `r.resources` | `map { InboundResource(it.type, it.fileKey, it.fileName, it.durationMs) }` | 下载路径由引擎 `DownloadPathPolicy` 白名单约束（BC-414..418），网关**不校验路径** |
| `mentions` | `r.mentions` | 同构 map | |
| `mentionAll` | `r.mentionAll` | 原样 | |
| `mentionedBot` | `r.mentionedBot` | 原样 | 🔴 群聊 `requireMention` 判定的输入（决策 3 下推给 SDK 的 `PolicyConfig.requireMention`；引擎侧仍保留自己的判定作为第二道） |
| `rootId` / `threadId` / `replyToMessageId` | 同名 | 原样（可 null） | D13 的 `sessionKey = chatId + (threadId ?: rootId ?: "")` 三字段齐备 `[SF:855]` |
| `createTimeMillis` | `r.createTime` | 原样 `Long` | NF-A2 ④ 断言 `> 0`；`StaleFilter`（NF-A7）的输入 |
| `cardAction` | — | `fromMessage` 恒 `null`；`fromCardAction` 填 `CardAction(...)` | |
| `gatewayReceivedAtMillis` | 参数 | 原样 | 由 `SdkLarkGateway` 用 `System.currentTimeMillis()` 在 capture 时填 |

#### 6.1.4 🔴 `rawContentType` → `InboundKind` 映射表（**容忍未知值**，决策 15）

| `rawContentType` 字面值 | `InboundKind` | 证据等级 | 备注 |
|---|---|---|---|
| `"text"` | `TEXT` | 实测（SDK `SendInput.Kind.TEXT` 对称 `[SF:865]`） | |
| `"image"` | `IMAGE` | 实测（同上 `IMAGE`） | `resources` 含 fileKey |
| `"file"` | `FILE` | 实测（同上 `FILE`） | 走 `downloadResource` |
| `"audio"` / `"media"` | `AUDIO` | ⚠️ `待 S4 真机回填` | SDK 出站枚举是 `AUDIO`，入站字面值**未抓取** |
| `"video"` | `VIDEO` | ⚠️ `待 S4 真机回填` | 同上 |
| `"post"` | `POST` | 实测（`SendInput.post(Object)` `[SF:867]`） | 富文本；SDK 已转成 `content` 文本 |
| `"merge_forward"` | `MERGE_FORWARD` | 实测（`subMessageFetcher` 只在该类型触发 `[SF:856]`） | 🔴 SDK 内部**唯一**会在 handler 线程发 HTTP 的路径 ⇒ T11 必须专测一次合并转发 |
| `"sticker"` | `STICKER` | 实测（`SendInput.sticker(fileKey)` `[SF:868]`） | |
| `"share_chat"` | `SHARE_CHAT` | 实测（同上） | |
| `"share_user"` | `SHARE_USER` | 实测（同上） | |
| `"interactive"` / `"card"` | `UNKNOWN`（**不是** `CARD_ACTION`） | ⚠️ `待 S4 真机回填`（`[BC §9 P11]`） | 🔴 卡片回传走**独立事件流** `"cardAction"`（§2.3 已定稿），`message` 事件里即使出现卡片消息也不应当作回传处理 |
| **任何其它值 / `null`** | **`UNKNOWN`** | 定稿 | 🔴 **绝不抛异常、绝不丢弃**。`InboundMessage` 照常产出，由引擎按 `UNKNOWN` 决定处置（当前旧行为是「未知事件类型」计数 + 忽略，BC-436 形态改写） |

**实现形态（定稿，照抄）**：

```kotlin
fun kindOf(rawContentType: String?): InboundKind = when (rawContentType) {
    "text" -> InboundKind.TEXT
    "image" -> InboundKind.IMAGE
    "file" -> InboundKind.FILE
    "audio", "media" -> InboundKind.AUDIO
    "video" -> InboundKind.VIDEO
    "post" -> InboundKind.POST
    "merge_forward" -> InboundKind.MERGE_FORWARD
    "sticker" -> InboundKind.STICKER
    "share_chat" -> InboundKind.SHARE_CHAT
    "share_user" -> InboundKind.SHARE_USER
    else -> InboundKind.UNKNOWN          // 🔴 无 when 穷尽性报错风险：else 兜底
}
```

> 🔴 **禁止写成 `enum valueOf` 或 `InboundKind.valueOf(rawContentType.uppercase())`**：那会在未知值上抛 `IllegalArgumentException`，直接违反决策 15。`LarkInboundMapperTest` 必须有一条断言：`kindOf("some_future_lark_type") == UNKNOWN` 且**不抛**（NF-A2 的隐含要求，本文档显式化）。

#### 6.1.5 `fromCardAction` 的三条规则

1. `messageId`：`CardActionRecord.messageId ?: ""`。🔴 卡片回传**可能没有 messageId**（回传不是消息）⇒ 引擎的去重与回复路由必须容忍空值。`待 S4 真机回填`（§13 第 3 条）。
2. `kind` 恒 `CARD_ACTION`，`rawContentType` 恒 `null`（`CardActionEvent` 无该 getter `[RB §8]`）。
3. `chatType`：卡片回传事件**不提供 chatType** ⇒ 恒 `UNKNOWN`。引擎侧不得依赖它做群聊/单聊分支（若需要，用 `chatId` 前缀 `oc_` 判断，但那是引擎的事，网关不做）。


### 6.2 `LarkOutboundMapper`（文件 `LarkOutboundMapper.kt`）

#### 6.2.1 方法签名（定稿）

```kotlin
object LarkOutboundMapper {
    /** 整卡 JSON（Map 形态，交给 cardkit create / im.message.create 的 content 字段）。 */
    fun toCardJson(spec: CardSpec): Map<String, Any?>

    /** 流式卡的**局部**元素 JSON：只含 body.elements[0]（tag=markdown）。供 cardkit patch 用。 */
    fun toPartialElementJson(markdown: String): Map<String, Any?>

    /** 结束流式的 settings JSON：streaming_mode = false。供 cardkit settings 用。 */
    fun toFinishSettingsJson(): Map<String, Any?>

    /** 按引用发送卡片时的 content 形态：{"type":"card","data":{"card_id":...}} */
    fun toCardRefContent(cardId: String): Map<String, Any?>

    /** msg_type 字面值。🔴 markdown → "interactive"，否则 "text"（[BC §4.14]）。 */
    fun msgTypeOf(kind: OutboundKind): String

    /** content 字符串（im.message.create 的 body.content 是 **JSON 字符串**，不是对象）。 */
    fun contentJsonOf(out: Outbound): String
}
```

> 🔴 **`Map` 而非 `String` 的选型**：cardkit 的 `CreateCardInstanceReqBody.Builder.card(Map)` 与 `PatchCardElementReqBody` 接受的是结构化对象；而 `im().v1().message().create()` 的 `content` 是 **JSON 字符串**。两套 API 形态不同 ⇒ mapper 同时提供 `Map`（给 cardkit）与 `contentJsonOf`（给 im.message，内部用 SDK 传递进来的 Gson 序列化——🔴 **网关不得自己 import Gson**，序列化统一走 §3.2 记录的 `JsonCodec` 私有工具（`SdkLarkGateway.kt` 内，是唯一 import SDK 的文件之一，见附录 B 第 12 条）。

#### 6.2.2 `toCardJson` 的完整结构（🔴 字面值全部来自 `[BC §4.14]`，一个都不能改）

```json
{
  "schema": "2.0",
  "config": {
    "streaming_mode": <spec.streaming>,
    "update_multi": true,
    "streaming_config": {
      "print_frequency_ms": 30,
      "print_step": 1,
      "print_strategy": "fast"
    },
    "summary": { "content": <spec.summary ?: spec.markdown.take(80)> }
  },
  "header": {
    "title": { "tag": "plain_text", "content": <spec.title> },
    "template": <spec.titleColor.template>
  },
  "body": {
    "elements": [
      { "tag": "markdown", "element_id": "stream_md", "content": <spec.markdown> }
    ]
  }
}
```

| 键 | 定稿值 | 出处 | 说明 |
|---|---|---|---|
| `schema` | `"2.0"` | 🔒 `[BC:1026]` | 1.0 结构的 markdown 只支持子集，`####` 标题 / 表格 / 引用**不渲染**（线上实测原样显示星号与竖线） |
| `body.elements[0].tag` | `"markdown"` | 🔒 `[BC:1027]` | |
| `body.elements[0].element_id` | `"stream_md"` | `[SF §S3]` + SDK 硬编码同值 | 🔴 SDK 的 `OutboundSender` 把 element_id 硬编码为 `"stream_md"`；我方自建 cardkit 调用**沿用同值**，好处是 S6 脚手架已验证过、且与飞书文档示例一致。**不是**契约的一部分（下游不可依赖） |
| `config.streaming_mode` | `spec.streaming` | `[BC §4.14]` D1 影响行 | |
| `config.update_multi` | `true` | ⚠️ `待 S6 真机确认`（§13 第 2 条） | 语义是「允许多次更新」；错误码 `300302` 与该键相关 `[BC:1094]`。**未经真机验证**，若 S6 报 300302 则改 `false` 或删除该键（改动位点：`toCardJson` 一行） |
| `config.streaming_config.*` | `30 / 1 / "fast"` | ⚠️ `待 S6 真机确认`（§13 第 2 条） | 飞书侧的**渲染**节流（与我方 250ms 的**请求**节流是两回事，不冲突）。三个键名与取值来自飞书卡片 JSON 2.0 文档，**未在 sources jar 内找到对应常量** ⇒ 标假设 |
| `config.summary.content` | `spec.summary ?: markdown.take(80)` | `[BC §4.14]` | 会话列表里的一句话摘要 |
| `header.template` | `spec.titleColor.template` | 🔒 `[BC:1032]` | `blue`(受理) / `green`(DONE) / `red`(FAILED) / `grey`(进行中) |

#### 6.2.3 button 的 JSON 形态（🔴 `behaviors` 数组，**禁 `action` 包裹**）

```json
{
  "tag": "button",
  "text": { "tag": "plain_text", "content": <b.text> },
  "type": <if (b.primary) "primary" else "default">,
  "behaviors": [
    { "type": "callback", "value": { "key": <b.value> } }
  ]
}
```

- 🔒 出处 `[BC:1030]`：button 回调必须走 `behaviors` 数组（元素 `type == "callback"`），**禁止 `action` 包裹属性**——否则飞书回 `230099 parse card json err: unknown property: action`（实锤联调日志）。
- `value.key` 回传时出现在 `CardActionEvent.getActionValue()`（§6.1.5 的 `CardAction.actionValue`）。
- 🔴 **`spec.streaming == true && spec.buttons.isNotEmpty()` ⇒ 抛 `IllegalArgumentException("流式卡内禁放交互组件（飞书 200810）")`**（决策 22 / §6.0 纪律 4）。这是本 mapper **唯一**的抛异常点。
- button 追加到 `body.elements`（在 markdown 元素**之后**），因此 `toPartialElementJson` 只更新 `elements[0]`，按钮不受流式更新影响——但流式卡根本不允许有按钮，故该组合只在**非流式**卡（确认/裁决卡）出现，而非流式卡走 `im.message.create` 或 `card().update()`，不走 patch。

#### 6.2.4 `msgTypeOf` / `contentJsonOf`

| `OutboundKind` | `msg_type` | `content`（JSON 字符串） | 出处 |
|---|---|---|---|
| `TEXT` | `"text"` | `{"text": <out.text>}` | 🔒 `[BC:1028]` `msgType(markdown=false) == "text"` |
| `MARKDOWN` | `"interactive"` | `toCardJson(CardSpec(title="", markdown=out.text!!, streaming=false))` 的序列化 | 🔒 `[BC:1028]` `msgType(markdown=true) == "interactive"`。**markdown 一律包成卡片**（1.0 的 `post` 富文本渲染不全，`[BC:1026]`） |
| `CARD` | `"interactive"` | `toCardJson(out.card!!)` 的序列化 | 🔒 `[BC:1029]` `content` 字符串内含 `"schema":"2.0"` |

- 🔒 `[BC:1029]`：`sendBody` 带 `receive_id` ⇒ 由 `CreateMessageReqBody.Builder.receiveId(target.receiveId)` 承担（不是 content 里的键）。
- 🔴 **文本分片不在 mapper 内做**：`OutboundConfig.textChunkLimit = 3500`（决策 18，属**出站**配置）由 **SDK 内部**分片，产出的多 messageId 通过 `SendResult.getChunkIds()` 回传 `[SF:884]` ⇒ `SendResult.chunkMessageIds`。**我方不自己切**（自切会与 SDK 再切一次，产生双层分片）。
- 🔴 **特殊字符转义往返一致**（NF-A6 ⑤ `[BC:1274]`）：由序列化器承担，`LarkOutboundMapperTest` 必须有一条用例：`markdown` 含 `"` / `\` / `\n` / 中文 emoji 时，`contentJsonOf` 再解析回来与原值 `equals`。


### 6.3 `LarkErrorMapper`（文件 `LarkErrorMapper.kt`）

#### 6.3.1 方法签名（定稿）

```kotlin
object LarkErrorMapper {
    /** 飞书业务码（BaseResponse.getCode()）+ HTTP 状态 → GatewayErrorCode。 */
    fun fromLarkCode(larkCode: Int?, httpStatus: Int?): GatewayErrorCode

    /** SDK 的 LarkChannelException.getCode() 是 **String**（枚举的 getValue()）[SF:893]。 */
    fun fromLarkChannelCode(code: String?): GatewayErrorCode

    /** 异常兜底。🔴 不 import SDK 异常类型：调用方先取 t::class.java.name 传进来。 */
    fun fromThrowableName(fqcn: String?, message: String?): GatewayErrorCode

    /** 是否值得由 RateGovernor 重试（决策 6：只有卡片更新与资源下载可重试）。 */
    fun isRetryable(e: GatewayErrorCode): Boolean
}
```

> 🔴 **`fromThrowableName(fqcn, msg)` 而非 `fromThrowable(t: Throwable)` 的理由**：若签名收 `Throwable` 并做 `is LarkChannelException` 判断，mapper 就必须 import SDK 类型，违反 §6.0 纪律 1。改为由 `SdkLarkGateway`（唯一 import SDK 的文件）在 `catch` 里取 `t::class.java.name` 与 `t.message` 传入 ⇒ **mapper 保持零 SDK 类型、可在无 SDK 环境下跑 `LarkErrorMapperTest`**。

#### 6.3.2 飞书业务码 → `GatewayErrorCode`（定稿映射表）

| 业务码 | 含义 | `GatewayErrorCode` | 网关侧动作（§4.9 的 `pendingAction`） | 出处 |
|---|---|---|---|---|
| `300317` | sequence 不连续 / 乱序 | `CardSequenceConflict` | **RebuildStream**：丢弃当前 sequence，新建卡片实体，从当前全量快照重开 | `[BC:1094]`、决策 1 |
| `200860` | 单卡 30KB 超限 | `CardTooLarge` | **Rotate**：立即触发分段轮换（不等预算判定） | `[BC:1094]`、R7① |
| `200810` | 流式期间无法响应卡片回传 | `CardStreamBusy` | **GiveUp**（该卡）：记 ERROR；架构级消解是「流式卡禁放交互组件」（决策 22），故正常运行**不应命中** | `[BC:1094]`、R7⑤ |
| `200750` | 卡片实体 14 天过期 / 仅可发送一次 | `CardEntityExpired` | **RebuildStream**：新建实体（R20 的专属错误码要求 `[BC:1247]`） | `[BC:1094]`、R20 |
| `300302` | 卡片 JSON 结构错误（`update_multi` 相关） | `CardJsonInvalid` | **GiveUp** + ERROR（含完整 cardJson 的 DEBUG 转储） | `[BC:1094]` |
| `230099` | `parse card json err: unknown property: action` | `CardJsonInvalid` | **GiveUp** + ERROR。🔒 根因已由 `[BC:1030]` 消解（button 走 `behaviors`） | `[BC:1030]` |
| `11232` | 整点压力限流 | `RateLimited` | **Backoff**：`RateGovernor` 退避；⚠️ 是否真机可观测 `待 S6 确认`（§13 第 5 条） | `[BC:1094]`、`[BC:1248]` |
| `99991672` | 应用凭据无效 / 无权限 | `ConnectFatal` | 连接期：**不重试**，`GatewayConnectResult.Failed(retryable=false)` | `[BC:1271]` NF-A3 ③ |
| HTTP `429` | Too Many Requests | `RateLimited` | **Backoff**，读 `x-ogw-ratelimit-reset` 头 | `[BC:1248]` |
| HTTP `404` + 纯文本 body | 网关路径错误（非 JSON 响应） | `ConnectFatal` | **不重试**（承接 BC-445..448 / BC-459 语义升级） | `[BC:1271]` NF-A3 ③ |
| HTTP `5xx` | 服务端错误 | `ConnectRetryable`（连接期）/ `SendFailed`（出站期） | 出站期**不自动重试**（决策 6，防重复投递） | 定稿 |
| 其它非 0 码 | 未登记 | `SendFailed`（出站）/ `ConnectRetryable`（连接） | 记 WARN 含原始码，供后续登记 | 定稿（**容忍未知值**，与 §6.1.4 同一纪律） |
| `0` / `null` | 成功 | — | 不进入本 mapper | — |

**优先级规则（🔴 必须照此顺序判定，否则会错分类）**：
1. 先看 **HTTP 状态**（`429` → `RateLimited`；`404`+非 JSON → `ConnectFatal`；`5xx` → 见上）；
2. 再看**业务码**（上表 300317..99991672）；
3. 最后兜底 `SendFailed` / `Unknown`。
> 理由：飞书在限流时可能同时返回 HTTP 429 **和**某个业务码，若先查业务码会漏掉 `RateLimited` 语义，导致 `RateGovernor` 不退避。

#### 6.3.3 `LarkChannelErrorCode`（9 值 `[SF:890-892]`）→ `GatewayErrorCode`

| SDK 枚举值（`getValue()` 字面） | `GatewayErrorCode` | 备注 |
|---|---|---|
| `FORMAT_ERROR` | `FormatError` | 入参形状错 |
| `TARGET_REVOKED` | `TargetRevoked` | 会话解散 / 用户注销 ⇒ **不重试** |
| `RATE_LIMITED` | `RateLimited` | ⇒ `RateGovernor` 退避 |
| `PERMISSION_DENIED` | `PermissionDenied` | scope 不足 ⇒ **不重试**，UI 引导补 scope |
| `UPLOAD_FAILED` | `UploadFailed` | 图片/文件上传失败 |
| `SSRF_BLOCKED` | `SsrfBlocked` | `OutboundConfig.ssrfGuardEnabled=true` 拦截（默认开，我方**不关**） |
| `SEND_TIMEOUT` | `SendTimeout` | ⇒ **不自动重试**（决策 6：非幂等） |
| `NOT_CONNECTED` | `NotConnected` | `botIdentity == null` 时 `ChannelInboundProcessor` 抛 `[SF:856]` |
| `UNKNOWN` | `Unknown` | 兜底 |

🔴 **`getCode()` 返回的是 `String`**（`LarkChannelException.getCode()` `[SF:893]`），不是 int ⇒ `fromLarkChannelCode(code: String?)` 用**字符串精确匹配**，且 `else -> Unknown`（容忍 SDK 升级新增枚举值）。

#### 6.3.4 `isRetryable` 定稿（决策 6）

```kotlin
fun isRetryable(e: GatewayErrorCode): Boolean = when (e) {
    // 可重试：幂等路径（卡片更新按 sequence 幂等 / 资源下载幂等）
    RateLimited, CardSequenceConflict, CardTooLarge, CardEntityExpired,
    ConnectRetryable, DownloadFailed, DownloadTooLarge -> true
    // 🔴 不可重试：非幂等（发消息会重复投递）或重试无意义
    SendFailed, SendTimeout, FormatError, TargetRevoked, PermissionDenied,
    UploadFailed, SsrfBlocked, CardStreamBusy, CardJsonInvalid,
    ConnectFatal, BlockedByPeerInstance, NotConnected, ReactionFailed,
    RegisterQrExpired, RegisterInvalidArgument, RegisterFailed, Unknown -> false
}
```

> 🔴 **`isRetryable` 的语义是「该错误码在**幂等路径**上是否值得重试」，不是「`send()` 是否可以重试」**。
> `send()`（发消息）**永不重试**（决策 6，非幂等）；只有 `LarkCardStreamSink` 的 patch/settings 调用（sequence 幂等）与 `downloadResource`（幂等）才把它交给 `RateGovernor`（M4-T7）。
> 因此 `RateLimited` 标 `true` 不会导致重复投递——`send()` 路径根本不查这个函数。`SdkLarkGateway.send()` 内 🔴 **禁止调用 `isRetryable`**（`GatewayDispatcherTest` 可加一条 grep 断言）。

#### 6.3.5 建议的 `GatewayErrorCode → BridgeErrorCode` 对接表（**归 `:lark-bridge`，本文档只建议不定义**）

🔴 `BridgeErrorCode` 的**码值与定义**归 `[CDS §12.1]`（14 个值，2000-2040）。本文档**不重复定义**。下表只是「网关侧语义 → 桥接侧现有枚举」的**建议落点**，供 M4-T5/T8 施工者参考；若 `[CDS]` 的最终清单与此不符，**以 `[CDS]` 为准**。

| `GatewayErrorCode` | 建议映射到 `[CDS §12.1]` 的 | 说明 |
|---|---|---|
| `ConnectFatal` / `ConnectRetryable` / `NotConnected` | `ConnectFailed`(2010) | CDS 只有一个连接失败码 |
| `BlockedByPeerInstance` | `BlockedByPeerInstance`(2011) | 1:1 |
| `RateLimited` | `RateLimited`(2012) | 1:1 |
| `DownloadFailed` / `DownloadTooLarge` | `DownloadFailed`(2030) | |
| `SendFailed` / `SendTimeout` / `FormatError` / `TargetRevoked` / `PermissionDenied` / `UploadFailed` / `SsrfBlocked` / `ReactionFailed` / `Unknown` | `SendFailed`(2040) | 🔴 **9 个网关码压成 1 个桥接码** ⇒ 语义损失 |
| `CardSequenceConflict` / `CardTooLarge` / `CardStreamBusy` / `CardEntityExpired` / `CardJsonInvalid` | **无对应值** | 🔴 **缺口**，见附录 B 第 1 条 |
| `RegisterQrExpired` / `RegisterInvalidArgument` / `RegisterFailed` | **无对应值** | 🔴 **缺口**，见附录 B 第 1 条 |

> ⚠️ **两处缺口需 Leader 裁决**（本文档只报告）：
> ① `[BC:1271]` NF-A3 ① 明确要求「**每个码有专属 `BridgeErrorCode`**」，但 `[CDS §12.1]` 的 14 个值里**没有任何卡片流式专属码**，也没有扫码建应用码 ⇒ 要么 CDS 补 5+3 个值（码值段需 CDS 决定），要么 NF-A3 ① 的断言收窄为「每个码有专属 `GatewayErrorCode` + 计数 + 日志」（本文档 §2.5 已满足后者）。
> ② 9 个网关出站码压成 1 个 `SendFailed`(2040) 会让 UI 无法区分「权限不足」与「会话已解散」——两者的用户处置动作完全不同（前者补 scope，后者换会话）。
> **本文档按「网关侧 `GatewayErrorCode` 保持 26 值完整语义」施工，不受上述缺口影响**；映射的收窄发生在 `:lark-bridge`，属 M4 范畴。


---

## 7. `Tuning.kt` 常量表（决策 20）

🔴 **本节全部常量不进用户配置文档**（`[PLAN:560]` 已否决「把 SDK 调优旋钮暴露进配置」；`[CDS]` 的 35 个 key 里**不得出现**任何本节常量）。
理由：① 每个值都由飞书**硬限制**推导，用户改错即触发线上错误码；② 暴露即需承担「热更 / 需重启」的 applyMode 分类与 UI 卡片，成本远超收益；③ SDK 侧的 `textChunkLimit` 等**构造时快照不可热更** `[SF:914]`，暴露给用户会造成「改了没生效」的假象。
**唯一例外**：`allowedFileDirs` 与 `requireMention` 跟随用户配置（§5.2），但它们不在本文件，而在 `GatewaySdkSettings`。

文件位置：`lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/Tuning.kt`，形态 `internal object Tuning`（🔴 `internal` 而非 `public`：不是契约面，`:lark-bridge` 不得引用）。

### 7.1 卡片流式域

| 常量名 | 值 | 对应飞书硬限制 | 出处 | 备注 |
|---|---|---|---|---|
| `streamThrottleMs` | `250L` | 单卡更新 **10 次/秒** | R7② `[PLAN:517]`、`[BC:1035]` | 250ms = **4 次/秒**，留 **60% 余量**。🔴 SDK 默认 `100`（=10/s）**正好卡在上限、零余量** `[SF:905]` ⇒ 必须覆写 |
| `streamThrottleChars` | `4000` | 同上 | 🔴 **方案漏了这个旋钮**（`[SF:906]` SDK 默认 `50`） | 决策 19：SDK 的 `Throttle.note()` 是 `pendingChars >= chars \|\| elapsed >= ms`（**或**关系 `[SF:912]`）⇒ 我方自实现节流**必须同时考虑字符增量**。取 4000 是「一段 markdown 增量达 4000 字符即立即刷」，避免长输出只按时间刷导致观感卡顿 |
| `cardByteBudget` | `24576` | 单卡 **30KB**（30720 B） | R7① `[PLAN:517]`、`[BC:1035]` | 30720 − 24576 = **6144 B（6KB）留给 JSON 转义膨胀**（`"` → `\"`、`\n` → `\\n`、中文 3 字节）。🔴 **按 UTF-8 字节算，不按字符算**（§4.5） |
| `streamSegmentDeadlineMs` | `540_000L` | **10min 自动关流** | R7⑤ `[PLAN:517]`、`[BC:1035]` | 9min = 540s，留 **60s 余量**。⚠️ 10min 自动关闭行为 `待 T11 真机确认`（§13 第 6 条） |
| `sinkPollIntervalMs` | `25L` | — | 定稿 | sink 协程的 `delay` 粒度。25ms ⇒ 每秒最多 40 次唤醒，远低于 `streamThrottleMs`，保证节流窗口精度（虚拟时间测试下不受影响） |
| `sinkCloseTimeoutMs` | `3_000L` | — | `[BC:1257]` 十步关闭第 ④ 步「`NonCancellable`, 3s」 | `flushTerminal` + `close` 的总预算 |
| `streamElementId` | `"stream_md"` | — | SDK `OutboundSender` 硬编码同值 `[SF §S3]` | 见 §6.2.2。**不是契约面** |

### 7.2 频控域

| 常量名 | 值 | 对应飞书硬限制 | 出处 | 备注 |
|---|---|---|---|---|
| `perChatRps` | `4` | 同群 **5 QPS** | R7⑥ `[PLAN:517]`、`[BC:1106]` | 留 **20% 余量** |
| `globalRps` | `40` | 接口级 **50/s** | `[BC:1106]`、决策 20 | 留 **20% 余量** |
| `rateLimitResetHeader` | `"x-ogw-ratelimit-reset"` | — | `[BC:1104]`（🔴 全 SDK 源码 grep **零命中**，SDK 不读它） | ⚠️ 该头**是否真实存在** `待 S6 真机确认`（§13 第 4 条）。缺失时 `RateLimited.resetSec = null`，`RateGovernor` 用固定退避 |
| `rateLimitBackoffMs` | `1_000L` | — | 定稿 | reset 头缺失时的兜底退避基数（指数：1s → 2s → 4s，上限 `rateLimitBackoffMaxMs`） |
| `rateLimitBackoffMaxMs` | `30_000L` | — | 定稿 | 避免整点限流时无限退避 |

> 🔴 `perChatRps` / `globalRps` 的**消费者是 `RateGovernor`（M4-T7，`:lark-bridge` 侧）**，不是网关。放在 `:lark-gateway` 的 `Tuning.kt` 里会造成「常量在网关、使用者在桥接」的跨模块引用，而 `Tuning` 是 `internal` ⇒ **施工者需把这两个常量放到 `:lark-bridge` 侧的同名 `Tuning`**（两处各持一份，值必须一致，由 `RateGovernorTest` 断言 4/40）。
> 本文档在此**如实登记为「决策 20 的字面表述与模块边界冲突」**（附录 B 第 13 条，只报告不裁定）；§7.2 的 5 个常量中，只有 `rateLimitResetHeader` 与 `streamThrottle*` 真正被网关使用（前者在读响应头时，后者在 §4.3 主循环）。
> **定稿处置**：`Tuning.kt`（网关侧）**只保留** `rateLimitResetHeader` / `rateLimitBackoffMs` / `rateLimitBackoffMaxMs`；`perChatRps` / `globalRps` **在本文档记录其值与出处**（供 M4-T7 照抄），但**不写入网关的 `Tuning.kt`**。

### 7.3 出站与 SDK 配置域（与 §5.1 的 `GatewaySdkSettings.DEFAULT` 一一对应）

| 常量名 | 值 | 对应飞书硬限制 | 出处 | 备注 |
|---|---|---|---|---|
| `textChunkLimit` | `3500` | 单条消息文本上限 | `[SF:903]`（SDK 默认同值）、决策 18 | 🔴 属 **`OutboundConfig`（出站）**，**不是** `BatchTextConfig`（入站合批）——方案此处用词有误（§14 修正 3）。**显式写死**，不依赖 SDK 默认值（升级可能变） |
| `retryMaxAttempts` | `1` | — | 决策 6、`[BC:1104]` | 🔴 关掉 SDK 盲指数退避（默认 `3` / `baseDelayMs=500` ⇒ 500ms→1s→2s），避免与 `RateGovernor` 双层放大 |
| `retryBaseDelayMs` | `500` | — | `[SF:907]` SDK 默认同值 | `maxAttempts=1` 下**不生效**，写死只为「SDK 升级改默认值时我方不变」 |
| `ssrfGuardEnabled` | `true` | — | `[SF:908]` SDK 默认同值 | 🔴 **不关**。网关会替用户下载/上传图片，SSRF 防护是唯一屏障 |
| `dedupMaxEntries` | `5000` | — | `[SF:1298]` SDK 默认同值 | SDK `SafetyConfig` 的入站去重是**第二道网**（第一道是我方 `MessageDeduper` LRU 4096 + TTL 12h，NF-A1） |
| `dedupTtlMs` | `43_200_000L`（12h） | — | `[SF:1298]` SDK 默认同值 | |
| `dedupSweepMs` | `300_000L`（5min） | — | `[SF:1298]` SDK 默认同值 | |
| `staleMessageWindowMs` | `1_800_000L`（30min） | — | `[SF:1298]` SDK 默认同值 | SDK 侧陈旧消息过滤；我方 `StaleFilter`（NF-A7）是第二道 |
| `processingLockTtlMs` | `300_000L`（5min） | — | `[SF:1298]` SDK 默认同值 | |
| `chatQueueEnabled` | `false` | — | 🔴 决策 4、`[SF:1301]` | **必须显式覆写**（SDK 默认 `true`）。真实 Kotlin 属性名 `isChatQueueEnabled`（§3.3、§14 修正 4） |
| `batchTextDelayMs` | `600` | — | `[SF §S3-E]` SDK 默认同值 | `chatQueueEnabled=false` 下**不生效**；写死是为了「若有人误开队列，行为可预测」 |

### 7.4 连接与生命周期域

| 常量名 | 值 | 出处 | 备注 |
|---|---|---|---|
| `wsHandshakeTimeoutMs` | `15_000L` | 实测 `LarkChannel.awaitWebSocketReady(15000)` `[SF:1296]` | 🔴 **SDK 内部硬编码，我方无法覆写**。此处记录是为了 `connect()` 的 `withTimeoutOrNull` **必须 > 15s**（否则会在 SDK 握手完成前就超时，产生假失败） |
| `connectTimeoutMs` | `20_000L` | 定稿（> `wsHandshakeTimeoutMs` + 5s 余量） | `connect()` 的整体预算 |
| `disconnectTimeoutMs` | `5_000L` | `[BC:1257]` 十步关闭第 ⑤ 步「`gateway.disconnect()`(5s)」 | |
| `closeTimeoutMs` | `5_000L` | `[BC:1257]` 十步关闭第 ⑥ 步「`gateway.close()`(5s)」 | 🔴 含反射 shutdown；反射本身**不挂起**（§3.11） |
| `wsExecutorShutdownAwaitMs` | `2_000L` | 定稿 | 反射 `shutdownNow()` 后 `awaitTermination` 的上限（在 `closeTimeoutMs` 预算内） |
| `inboundChannelCapacity` | `512` | `[PLAN:225]`「SDK handler → intake `Channel(512, DROP_OLDEST)` + 计数 + WARN」 | 🔴 溢出 ⇒ `GatewaySignal.Dropped("inbound", n)`（§2.7） |
| `signalChannelCapacity` | `256` | 定稿（方案未给） | 溢出 ⇒ `GatewaySignal.Dropped("signal", n)`。🔴 `Dropped` 自身也可能溢出 ⇒ 用 `trySend` 且**丢弃时只累加计数器不递归发信号** |
| `downloadMaxBytes` | `104_857_600L`（100MB） | `[BC:1273]` `fileMessage.maxFileSizeBytes` 默认 100MB | 🔴 该值的**权威来源是用户配置** `fileMessage.maxFileSizeBytes`（`[CDS §3.2]`）⇒ 网关侧的 `downloadMaxBytes` 只是**兜底上限**，实际值由 `SdkLarkGateway` 构造参数注入（§3.7）。**不是** Tuning 硬编码 |
| `downloadSingleFlight` | `true` | 定稿（§3.7 三重防护之二） | 同 `(messageId, fileKey)` 并发下载只放行一个 |

### 7.5 施工者注意（🔴 三条）

1. **`Tuning` 是 `internal object`**：`:lark-bridge` 与测试（同模块 `src/test`）可访问，跨模块不可。若 `RateGovernorTest`（M4-T7，在 `:lark-bridge`）需要 `perChatRps`，按 §7.2 的定稿处置在桥接侧另持一份。
2. **每个常量上方必须有一行注释写「对应飞书硬限制 + 余量百分比」**（决策 20 的字面要求）。注释是**验收项**：`CodeReview` 阶段逐条核对，缺注释视为未完成。
3. **禁止出现 `const val` 之外的形态**（不得是 `var`、不得从环境变量读、不得从配置读）。`Tuning` 的任何可变性都会让 §11 的阈值断言失去意义。


---

## 8. `RegisterAppFlow` 规格（决策 12）

文件：`lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/RegisterAppFlow.kt`。
🔴 **这是第三个 import SDK 的文件**（另两个见 §3.1 / 附录 B 第 12 条）——因为 `RegisterApp` 是 SDK 的独立场景类，与 `LarkChannel` 无关联，塞进 `SdkLarkGateway.kt` 会让该文件承担两种无关职责。`LarkSdkIsolationTest` 的白名单必须包含它（§12）。

### 8.1 SDK 的真实 API（实测，`[SF:921-931]`）

```java
// com.lark.oapi.scene.registration.RegisterApp
public static RegisterAppResult register(RegisterAppOptions options)   // 🔴 阻塞式，最长约 600s
        throws RegisterAppException, ExpiredException

class RegisterAppResult { String getClientId(); String getClientSecret(); UserInfo getUserInfo(); }
class QRCodeInfo        { String getUrl(); int getExpireIn(); }          // 🔴 getter 名实测（不是 qrCodeUrl/expireSeconds）
class RegisterAppException extends Exception { String getCode(); String getDescription(); }
class ExpiredException extends RegisterAppException { }

// RegisterAppOptions.Builder
.onQRCode(Consumer<QRCodeInfo>)     // 🔴 必填：opts.getOnQRCode() == null 时抛
                                    //   RegisterAppException(ERROR_INVALID_ARGUMENT, "onQRCode is required")
```

### 8.2 契约回调参数（🔴 决策 12 已改，方案原文不对应 SDK）

```kotlin
// MessageGateway 第 11 成员（[PLAN:67] 原文是 (deviceCode, verifyUrl)，与 SDK 不对应）
suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): LarkCredential
```

| 参数 | 来源 | 说明 |
|---|---|---|
| `qrUrl` | `QRCodeInfo.getUrl()` | 🔴 是**二维码图片的 URL**（需在 UI 侧渲染成二维码图），不是设备码 |
| `expireInSec` | `QRCodeInfo.getExpireIn()` | 二维码有效期（秒）。UI 需倒计时并支持「刷新二维码」 |
| 返回 `LarkCredential.appId` | `RegisterAppResult.getClientId()` | 🔴 飞书把 `client_id` 等同于 `app_id` |
| 返回 `LarkCredential.appSecret` | `RegisterAppResult.getClientSecret()` | 🔴 R17：落盘前不得进日志；`LarkCredential.toString()` 已掩码（§2.2） |
| 返回 `LarkCredential.region` | — | `RegisterApp` **不提供 region** ⇒ 沿用当前配置的 `connection.region`（`[CDS §3.2.1]`，默认 `"feishu"`）。由调用方（UI）填入 |

> 🔴 **`UserInfo` 刻意不进契约**：它只用于 UI 展示「以哪个飞书账号创建」，属可选信息。若 M6 需要，通过 `GatewaySignal` 或独立回调扩展，**不改 `registerApp` 签名**（避免为 UI 便利污染契约）。登记为附录 B 第 14 条（只报告）。

### 8.3 实现规格（定稿）

```kotlin
internal object RegisterAppFlow {
    /** 🔴 专用 dispatcher，不占 commonPool、不占 net 的 8 个槽（决策 12）。 */
    private val registerDispatcher = Dispatchers.IO.limitedParallelism(1, "bridge-register")

    suspend fun run(onQr: (String, Int) -> Unit): LarkCredential = withContext(registerDispatcher) {
        // 1) 用 SuspendCancellableCoroutine 之外的形态：register() 是**阻塞**调用，
        //    直接在 registerDispatcher 上跑即可（limitedParallelism(1) ⇒ 同时只有一个扫码流）。
        val result = runCatching {
            RegisterApp.register(
                RegisterAppOptions.newBuilder()
                    .onQRCode { info -> onQr(info.url, info.expireIn) }   // 🔴 回调在 SDK 线程上，只做转发
                    .build()
            )
        }
        result.fold(
            onSuccess = { LarkCredential(appId = it.clientId, appSecret = it.clientSecret, region = regionHint) },
            onFailure = { t -> throw mapRegisterFailure(t) }
        )
    }

    private fun mapRegisterFailure(t: Throwable): GatewayException = when {
        t::class.java.name.endsWith("ExpiredException") ->
            GatewayException(GatewayErrorCode.RegisterQrExpired, "二维码已过期，请重新发起", t)
        t::class.java.name.endsWith("RegisterAppException") ->
            GatewayException(
                if (t.message?.contains("INVALID_ARGUMENT") == true) GatewayErrorCode.RegisterInvalidArgument
                else GatewayErrorCode.RegisterFailed,
                t.message ?: "扫码创建应用失败", t)
        else -> GatewayException(GatewayErrorCode.RegisterFailed, t.message ?: "unknown", t)
    }
}
```

| # | 规格点 | 理由 |
|---|---|---|
| 1 | 🔴 **`limitedParallelism(1, "bridge-register")` 专用 dispatcher** | 决策 12 明令「必须包在专用 dispatcher，不占 commonPool」。`register()` **阻塞最长 600s**，若落在 `net`（8 槽）会占掉 1/8 的出站能力长达 10 分钟；若落在 commonPool 会污染全 JVM |
| 2 | 🔴 **`onQRCode` 回调内只做转发，不做 IO / 不挂起** | 回调在 SDK 线程上执行。`onQr` 由 UI 提供 ⇒ UI 侧实现必须是「`scope.launch { qrState.value = ... }`」形态，**不得在回调里直接改 Compose 状态**（跨线程）。这条约束写进本文档，由 M6-T13 的 UI 施工者遵守；网关侧不做防护（无法防护） |
| 3 | 🔴 **可中断性**：`withContext(registerDispatcher)` 的外层取消**不会**中断 `RegisterApp.register()` 内部的轮询 | SDK 无中断钩子（实测 API 面无 `cancel`）。缓解：① UI 侧提供「取消」按钮，取消后**忽略**后续 `onQr` 与结果（用 `Job` 的 `isActive` 判定）；② `register()` 自身有 600s 上限，不会永久挂住；③ 该 dispatcher 只有 1 个槽 ⇒ 用户重复点击会排队而不是并发，UI 侧需在发起时禁用按钮。**这条限制如实登记为附录 B 第 15 条** |
| 4 | 🔴 **`registerApp` 是 12 成员里唯一允许抛异常的**（§2.6 第 11 行） | 它是用户主动发起的一次性交互，无「静默失败」风险；抛 `GatewayException` 让 UI 直接 catch 展示文案，比返回 `Result` 更简洁 |
| 5 | 🔴 **不在网关内持久化凭据** | `registerApp` 只返回 `LarkCredential`；写盘（`bridge-config.json`，POSIX 600）归 `:lark-bridge` 的 `ConfigWriter`（M4-T5，`[BC:1253]` NF-10） |
| 6 | **`regionHint`** 由 `SdkLarkGateway` 从当前 `LarkCredential.region` 透传 | `RegisterApp` 不返回 region |

### 8.4 🔴 入口约束：**不进 CLI**（决策 12，零漂移）

| 入口 | 是否提供扫码建应用 | 依据 |
|---|---|---|
| **CLI** | **否**。严格保持 **5 个子命令**：`serve` / `check` / `send` / `handle-event` / `permission-server` | 决策 12 明令「**不新增 `register-app` 子命令**」；`[PLAN]` M5-T10 的 CLI 规格 |
| **Compose UI** | **是**。唯一入口，位于连接卡片（M6-T13） | 决策 12「扫码入口只在 UI」 |

> 🔴 **施工者禁止事项**：不得为了「CLI 也能建应用」而在 `handle-event` 或 `check` 里塞隐藏分支；不得新增第 6 个子命令；不得把 `RegisterAppFlow` 暴露为 `public`（它是 `internal`，`:lark-bridge` 通过 `MessageGateway.registerApp` 契约成员访问）。
> **验收**：M5-T10 的 CLI 测试断言「`--help` 输出的子命令数 == 5 且名字精确匹配」。


---

## 9. 线程与生命周期

### 9.1 SDK 侧线程清单（🔴 四类，逐条实测）

| # | 线程 / 线程池 | 创建时机 | daemon? | SDK 是否 shutdown | 我方处置 | 出处 |
|---|---|---|---|---|---|---|
| 1 | **shaded OkHttp 的 WS reader 线程**（`com.lark.oapi.okhttp`，`ws/Client.java` 里 `httpClient = new OkHttpClient()`） | 每次 `new ws.Client`（即每次 `connect()`） | 否（OkHttp 默认线程工厂） | `disconnect()` 会关 WebSocket ⇒ reader 线程随之结束 | 无需处置，但 🔴 **handler 就跑在这条线程上**（3 秒铁律的根因，§3.4） | `[SF:262]`、`[SF:312]` |
| 2 | **`ws.Client.executor`** = `Executors.newFixedThreadPool(cores*2+1)` | 🔴 **构造即创建**（不是 connect 时） | **非 daemon** | 🔴 **全类无任何 shutdown**；`close()` 只 `disconnect()` | 🔴 **反射 `shutdownNow()`**（§3.11），这是决策 13 的核心 | `[SF:783]`、`[SF:791]` |
| 3 | **shaded OkHttp 静态单例 `OKHttps.defaultClient`** 的连接池 / dispatcher 线程 | 首次 HTTP 调用 | 是（OkHttp 的连接池线程是 daemon） | 进程级单例，**永不释放**（也不该释放） | 无需处置。**全 JVM 共享一个**，反复 connect/disconnect **不会**累积 | `[SF:244]`、`[SF:255]`、`[SF:1296]` |
| 4 | **`lark-channel-chat-pipeline`** 单线程 daemon scheduler（`ChatPipeline`） | `chatQueueEnabled == true` 时 | 是 | — | 🔴 **已被 `isChatQueueEnabled = false` 消解**（决策 4）⇒ 该线程**根本不创建** | `[SF:778]` |
| 5 | **`ForkJoinPool.commonPool`** | JVM 级 | 是 | — | 🔴 SDK 的 `supplyAsync`/`runAsync` 不传 Executor ⇒ 落在此处 `[SF:312]`；`OutboundRetry` 甚至 `Thread.sleep` 占线程 `[SF:568]`。我方处置：`maxAttempts=1`（永不进 sleep 路径）+ 同步 API 包 `withContext(netDispatcher)`（§3.9 的**部分**可消解，如实登记） | `[SF:343]`、`[SF:346]`、`[SF:597]` |

> 🔴 **只有第 2 类会累积泄漏**：每个 `start/stop` 周期泄漏 `2*CPU+1` 个**非 daemon** 线程，且**阻止 JVM 自然退出**（`[SF:791]`）。第 1 类随 disconnect 结束，第 3/5 类是 JVM 级单例，第 4 类被消解。
> ⇒ 这使方案十步关闭的第 ⑥ 步「`gateway.close()` 释放 SDK 线程/连接池」`[BC:1257]` **重新可兑现**（在决策 13 之前，该步骤事实上无法完成，因为 SDK 不提供释放 API）。

### 9.2 我方 dispatcher 归属（六个具名 + 网关私有第 7 个）

| dispatcher | 定义位置 | 规格 | 网关是否使用 | 出处 |
|---|---|---|---|---|
| `intake` | `:lark-bridge` `BuiltinExecutors` | `Dispatchers.Default` | **否**（网关只 `trySend` 到引擎给的 `Channel`，不切 dispatcher） | `[PLAN:210-217]` |
| `task` | 同上 | `Dispatchers.Default` | 否 | 同上 |
| `pipe` | 同上 | `IO.limitedParallelism(maxConcurrent*2+2, "bridge-pipe")` | 否 | 同上 |
| **`net`** | 同上（**由引擎注入网关**） | `IO.limitedParallelism(8, "bridge-net")` | 🔴 **是**：`connect` / `disconnect` / cardkit 四步 / messageResource 下载，全部 `withContext(netDispatcher)` | 决策 5、`[BC:1272]` NF-A4 |
| `sink` | 同上 | `IO.limitedParallelism(1, "bridge-sink")` | 否（审计/统计落盘归引擎） | `[PLAN:210-217]` |
| `metrics` | 同上 | `Default.limitedParallelism(1, "bridge-metrics")` | 否 | 同上 |
| 🔴 **`bridge-register`**（第 7 个，网关私有） | **`:lark-gateway` `RegisterAppFlow`** | `IO.limitedParallelism(1, "bridge-register")` | 是：只服务 `registerApp` | §8.3 规格 1、决策 12 |

> 🔴 **`net` 由构造参数注入，不在网关内自建**：`SdkLarkGateway(netDispatcher: CoroutineDispatcher, ...)`。
> 理由：① 六个 dispatcher 的**唯一创建点**是 `BuiltinExecutors.build()`（`[BC §6.2]`，D16 消解的静态状态纪律），网关自建会破坏「唯一装配点」；② `GatewayDispatcherTest`（NF-A4）需要注入 `StandardTestDispatcher` 才能断言线程名；③ 网关内自建的 dispatcher 无人 shutdown，会成为新的泄漏源。
> 🔴 **`bridge-register` 是例外**：它不在 `BuiltinExecutors` 里，因为 ① `RegisterApp` 是网关独有的 SDK 场景，引擎不该知道；② `limitedParallelism` 返回的 `CoroutineDispatcher` **无需 shutdown**（它是 `Dispatchers.IO` 的视图，不持有线程）⇒ 不构成泄漏。
> 🔴 **网关内的 `gatewayScope`**（§3.1）用 `SupervisorJob() + Dispatchers.Default`（不是 `net`），因为它只跑「Flow 收集 + 状态机更新 + `trySend`」这类**非阻塞**逻辑；所有阻塞 SDK 调用在内部再 `withContext(netDispatcher)`。

### 9.3 `close()` 的释放顺序（🔴 定稿，与 §3.11 一致，此处给全景）

| 步 | 动作 | 超时 | 失败处置 | 释放的资源 |
|---|---|---|---|---|
| 0 | `AtomicBoolean.compareAndSet(false, true)`；失败即 return | — | — | 保证幂等（`[BC:1257]` 十步关闭的 CAS 纪律） |
| 1 | `runCatching { ch.disconnectSync() }` | `disconnectTimeoutMs`（由外层 `closeTimeoutMs` 覆盖） | 记 WARN，继续 | 第 1 类（WS reader 线程）+ WebSocket 连接 |
| 2 | `gatewayScope.cancel()` | — | — | 所有 `inbound`/`signals` 收集协程、`connectJob` |
| 3 | 遍历 `openSinks` 逐个 `close()`（`NonCancellable`） | `sinkCloseTimeoutMs` 每个 | 记 WARN，继续 | 每个 sink 的协程与卡片流 |
| 4 | 🔴 `reflectivelyShutdownWsExecutor(ch.rawWsClient)` | `wsExecutorShutdownAwaitMs` | 记 ERROR + 置 `wsExecutorReleased = false`，**不抛** | 第 2 类（`2*CPU+1` 个非 daemon 线程） |
| 5 | `inboundChannel.close()` / `signalChannel.close()` | — | — | 两个 Channel 的缓冲 |
| 6 | `connectionState.value = Disconnected(Unknown, "closed")`（若原为 `Idle` 则保持 `Idle`） | — | — | — |

🔴 **步 3 与 `[BC:1257]` 十步关闭第 ④ 步的分工**：引擎的第 ④ 步是「遍历开放卡片流**发终态卡** + `sink.close()`」——即**引擎先调 `flushTerminal`**（因为它知道任务成败与文案），**再调 `gateway.close()`**。网关的步 3 是**兜底**：若引擎漏了某个 sink，网关保证它被关掉（不发终态卡，因为网关不知道文案）。
🔴 **步 4 必须在步 2 之后**：`gatewayScope.cancel()` 会取消正在 `withContext(netDispatcher)` 里跑的 SDK 调用；若先 shutdown executor，那些调用的回调线程会异常终止，产生噪音日志。

### 9.4 反射 shutdown + `exitProcess` 双保险（决策 13）

| 保险 | 位置 | 作用 | 失效条件 |
|---|---|---|---|
| **① 反射 shutdown** | `SdkLarkGateway.close()` 步 4（§3.11 完整代码） | 真正释放 `2*CPU+1` 个非 daemon 线程，使 `StartStopLeakTest` 的 `threadCount` 断言可满足、JVM 可自然退出 | SDK 升级改了 `ws.Client` 的字段名 / 继承结构 ⇒ 反射找不到字段 |
| **② 显式退出** | CLI `serve` 用 `exitProcess(code)`；App 侧 `onAppExit()` 后由宿主退出 | 兜住 ① 失效的情况：即使线程还在，进程也能结束 | 宿主忘记调（由 `TeardownCompletenessTest` NF-14 守护「`onAppExit()` 与 JVM shutdown hook 都调用 stop」） |

🔴 **网关内绝不出现 `exitProcess` / `System.exit`**：网关是**库**，杀进程是宿主的权力。CLI 的 `exitProcess` 在 `:lark-bridge` 的 `cli/` 里（M5-T10），App 的退出在 `:composeApp` 里。

🔴 **报警机制（决策 13③）**：`GatewayCloseReleasesThreadsTest`（§11 第 10 项）断言 ① `close()` 后 `wsExecutorReleased == true`；② `Thread.getAllStackTraces().keys.count { it.name.startsWith("pool-") && !it.isDaemon }` 回落到基线。**SDK 升级导致反射失效时，该测试立刻红**——这是「反射脆弱性」的唯一可接受代价，且它是**可见的**红，不是静默泄漏。


---

## 10. `:lark-gateway` 的构建规格

### 10.1 四个已实测事实（🔴 施工者**不得**重复实现，convention 已提供）

| # | 事实 | 实测位置 | 对 T3 的含义 |
|---|---|---|---|
| 1 | convention 插件 `easydevtoolbox.jvm.cli` **已钉** `sourceCompatibility/targetCompatibility = VERSION_11` + `kotlin compilerOptions.jvmTarget = JVM_11` | `build-logic/src/main/kotlin/JvmCliConventionPlugin.kt:22-30` | 🔴 `lark-gateway/build.gradle.kts` **不得**再写 `jvmTarget`／`kotlinOptions`。R19（lib 11 不能依赖 `:core` 21）由这行保证 |
| 2 | convention **已注入** `testImplementation("org.jetbrains.kotlin:kotlin-test")` | 同上 `:32` | 🔴 模块内**不得**再声明 `kotlin-test`。当前 `build.gradle.kts` 只额外声明了 `testImplementation(libs.kotlinx.coroutinesTest)`，正确 |
| 3 | `oapi-sdk` 用 **`implementation`** 而非 `api` | `lark-gateway/build.gradle.kts`（现有文件，S1 产物 `[SF 附录 B]`） | 🔴 这是 SDK 隔离三重强制中**最强的一道（编译期）**：下游写 `import com.lark.oapi.*` **直接编译失败**。改成 `api` 会让 `LarkSdkIsolationTest` 失去意义 |
| 4 | **不声明** shadowJar `Main-Class` | 同上（文件头注释已写明「本模块不声明 shadowJar Main-Class（无独立 CLI，仅作库被 :lark-bridge api 依赖）」） | 🔴 `:lark-gateway` 是**纯库**，不得加 `mainClass`。加了会让 `:composeApp` 的打包产生歧义 |

### 10.2 已批准的优化：`tasks.shadowJar { enabled = false }`

**实测问题**（`[SF:1361]` 附录 C）：convention 给所有 `jvm.cli` 模块都 apply 了 shadow ⇒ `:lark-gateway` **每次 build 都产出 31.9 MB 无用 fat jar**：

| 产物 | 实测大小 |
|---|---|
| `lark-gateway/build/libs/lark-gateway-all.jar` | **31,938,337 B** |
| `lark-gateway/build/libs/lark-gateway.jar` | **456 B**（S1 时只有 `package-info.kt`） |

**定稿处置**：在 `lark-gateway/build.gradle.kts` 的 `dependencies {}` **之后**追加：

```kotlin
// 🔴 已批准（[SF:1361] 附录 C 的「可选优化」，设计裁定采纳）：
// 本模块是纯库（无 Main-Class、无独立 CLI），shadowJar 产物无人消费，
// 却每次 build 写盘 ~32MB 并拖慢构建。禁用它。
// 注意：这只禁用 :lark-gateway 的 shadowJar，不影响 :log-tools（它有真实 CLI）
// 与 :composeApp 的打包路径。
tasks.shadowJar { enabled = false }
```

| 影响面 | 结论 |
|---|---|
| `:log-tools:shadowJar` | **不受影响**（不同模块）；`[PLAN]` 硬约束要求的 `java -jar log-tools-all.jar --type xlog -f …` 回归仍需跑（M3-T2 范畴） |
| `:composeApp` 的 jlink / `.deb` | **不受影响**：`composeApp` 通过 `implementation(project(":lark-bridge"))` → `api(project(":lark-gateway"))` 拿到**普通 jar**（`lark-gateway.jar`），从不消费 `-all.jar` |
| `LarkSdkIsolationTest` 断言 ① | **不受影响**（它 grep `build.gradle.kts` 里的 `oapi-sdk`，与 shadowJar 无关） |
| 🔴 **与 §10.3 的 exclude 追加是否矛盾** | **不矛盾**。`exclude("META-INF/*.EC")` 加在 **convention 层**（`JvmCliConventionPlugin`），服务 `:log-tools` 与未来任何 shadow 模块；`:lark-gateway` 自己禁用了 shadowJar，因此该 exclude 对本模块**无实际作用**，是**前瞻性保险**（决策要求） |

### 10.3 已批准的 exclude 追加：`META-INF/*.EC`

**现状**（`JvmCliConventionPlugin.kt:36-40`，M3-T2 已由 施工者 修完）：

```kotlin
exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
mergeServiceFiles()
```

**定稿追加**（🔴 设计裁定，作为未来 EC 签名依赖的保险）：

```kotlin
exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
```

| 项 | 说明 |
|---|---|
| 出处 | spike 施工图列了第 4 个 `.EC`；M3-T2 只加了 3 个 |
| 为什么需要 | `.EC` 是 **ECDSA 签名文件**（JCE provider 类 jar 常见）。若某个依赖带 `META-INF/XXX.EC` 而 fat jar 里没有对应的原始签名摘要，运行时会 `SecurityException: Invalid signature file digest`——与 `.SF/.DSA/.RSA` 完全同类的故障 |
| 当前是否真有问题 | **否**。实测 SDK 传递依赖里 `bcprov`（BouncyCastle，签名 jar）不在 `oapi-sdk` 的传递链上（`[SF:1291]` 的依赖全清单无 bcprov）；`:log-tools` 侧的 BC 是既有依赖 |
| 🔴 **不得改动 `mergeServiceFiles()`** | R12：它保证 slf4j 的 `META-INF/services/` 不被剥掉（`[SF:1358]`）。S7 已验证 |
| 🔴 **不得回退成 `exclude("META-INF/")`** | 那会连带剥掉 `META-INF/services/`，导致 slf4j ServiceLoader 失效（S7 的 NOP 复现已实锤） |
| 归属 | **`build-logic`（convention 层）**，不是 `:lark-gateway`。⚠️ 本文档**无权修改任何现有文件** ⇒ 此项由 **M3-T2 的负责人（对应施工者）或 T3 施工者在获得授权后**落地；本文档只定稿「应该怎么写」 |

### 10.4 文件头注释要求（🔴 验收项）

`lark-gateway/build.gradle.kts` 现有的三行文件头注释**已满足**要求，保留原样：

```
// 全仓唯一 SDK 依赖点；禁止 relocate Gson/protobuf（SDK 内部反射依赖原始包名）。
// 除本模块外，任何模块出现 `com.lark.oapi` / `com.google.gson` / `org.apache.http` /
// `com.google.common` / `com.larksuite` 的 import 都属违规（M3-T4 的 LarkSdkIsolationTest 守护）。
```

🔴 **T3 需追加的第 4 行**（因为 §12 会扩展黑名单）：

```
// 追加黑名单（[SF:1355] R-S5-1）：org.apache.commons.logging（随 httpclient 传递进来的
// commons-logging 1.2）与 ApacheHttpClientTransport（全树无实例化点的死代码路径）。
```

**每个 `.kt` 源文件的文件头**（🔴 T3 新增的 10 个文件都要）：

```kotlin
// :lark-gateway —— 全仓唯一允许 import com.lark.oapi.* 的模块（AGENTS.md 第 8 条）。
// 本文件：<一句话职责>。SDK 隔离守护：LarkSdkIsolationTest（M3-T4）。
```

> 例外：`LarkPolicyMapper.kt` / `LarkInboundMapper.kt` / `LarkOutboundMapper.kt` / `LarkErrorMapper.kt` / `Tuning.kt` / 契约文件（`MessageGateway.kt` / `CardStreamSink.kt` / `GatewayModel.kt`）的文件头必须**额外**写一行：
> `// 🔴 本文件签名中不得出现任何 SDK 类型（§6.0 纪律 1）；违反即 LarkSdkIsolationTest 红。`

### 10.5 jlink 模块清单（🔴 S2 实测，**无需新增**）

`composeApp/build.gradle.kts` 的 jlink 配置保留 **9 个模块**（`[SF:1284]` 附录 A）：

```
java.base, java.datatransfer, java.desktop, java.logging, java.net.http,
java.prefs, java.xml, jdk.crypto.ec, jdk.unsupported
```

| 结论 | 依据 |
|---|---|
| 🔴 **不需要补 `java.naming` / `java.security.jgss`** | jdeps 报的 `httpclient → java.naming + java.security.jgss` 缺口是**死代码路径**：`ApacheHttpClientTransport` 全 SDK 源码树**无任何实例化点**，默认传输是 shaded OkHttp（`Transport.java:325` / `Client.java:578,581` / `:255`）`[SF:241-262]`、`[SF:798-800]` |
| 🔴 **不需要 `includeAllModules = true`** | 同上 |
| ⚠️ **`modules("java.net.http")` 的注释理由已失效** | `[SF:801-802]`：新实现走 SDK 的 shaded OkHttp，不再依赖 JDK HttpClient。**模块本身不能删**（`composeApp/build.gradle.kts:59` 实测存在），但 M7 文档需把理由改写为「历史遗留」。🔴 本文档**不改该文件**，只登记 |
| 🔴 **runtime 无 `java.management` ⇒ logback 配置禁止 `<jmxConfigurator/>`** | `[SF:1357]` R-S2-2。9 模块清单里没有 `java.management`；引入 `<jmxConfigurator/>` 会在运行时 `NoClassDefFoundError`。**这是 M4/M7 的约束，本文档在此固化，避免 T3 施工者顺手加** |
| ⚠️ **R-S2-1 仍未闭合** | `[SF:279]`：S2 时 `:lark-gateway` 是**空壳**，没有任何代码真正加载 SDK 类 ⇒ S2 只证明「新增依赖不破坏打包与启动」，**没有**证明「SDK 的网络/加密路径在裁剪后的 jlink runtime 里不缺模块」。必须用**打好的 `.deb`/`.msi`/`.dmg`**（不是 `:composeApp:run`，后者跑在完整 JDK 上）实际收发一次消息 ⇒ 归 **M5/T11**（§13 第 8 条） |

### 10.6 🔴 SPIKE 块的删除要求（T3 施工前必须处置）

`lark-gateway/build.gradle.kts` 现有 **~50 行 SPIKE ONLY 块**（`spikeSourceSet` / `forwardSpikeCredentials()` / `tasks.register<JavaExec>("spikeS4")` / `("spikeS6")`）与 `tasks.withType<Test> { failOnNoDiscoveredTests = false }`。

| 项 | 定稿处置 |
|---|---|
| `spikeS4` / `spikeS6` 两个 JavaExec | **保留到 S4/S6 真机验证完成**（对应施工者正在执行任务 ID:3），之后**连同 `src/test/kotlin/.../spike/` 目录整体删除**（文件内注释已写明「不属于交付物」） |
| `failOnNoDiscoveredTests = false` | 🔴 **T3 写完第一个真实测试后即可删除**（`:lark-gateway` 将贡献 > 0 个测试）。**若保留**，会导致「测试类名写错 ⇒ 0 个测试被发现 ⇒ 仍然绿」的假通过 |
| 🔴 **T3 施工者不得删除 spike 块** | S4/S6 尚未完成（ID:3 IN_PROGRESS）。T3 只在 `dependencies {}` 后追加 §10.2 的 `tasks.shadowJar { enabled = false }`，**其余原样** |
| `:lark-gateway` 的测试计数 | T3 完成后从 **0** 变为 **≥ 10**（§11 的 10 项中，M3-T3 归属 8 项 + M3-T4 的 1 项 + `GatewayCloseReleasesThreadsTest`）⇒ **782 测试基线会变**，M3 收尾时需重新统计并更新 `[BC]` 的基线数字 |


---

## 11. 测试清单（🔴 逐个写清断言、阈值、守护的 D#/R#）

### 11.0 归属与基础设施

| 项 | 定稿 |
|---|---|
| **M3-T3 归属**（本文档负责规格） | `PolicyMappingTest`、`LarkInboundMapperTest`、`LarkOutboundMapperTest`、`LarkErrorMapperTest`、`GatewayDispatcherTest`(NF-A4)、`FileDownloadStreamingTest`(NF-A5 的 M3-T3 部分)、`LarkPolicyMapperTest`(NF-A6)、`CardThrottleTest`、`SegmentRotationTest`、`Stream10MinRotationTest`、`GatewayCloseReleasesThreadsTest` |
| **M3-T4 归属**（子任务承担者负责，本文档 §12 只给规格） | `LarkSdkIsolationTest`(NF-16) |
| **M4-T7/T8 归属**（本文档**只给阈值不写规格**，避免越界） | `RateGovernorTest`(NF-05)、`Handler3SecondTest`(NF-01)、`StartStopLeakTest`(NF-15)、`MessageDeduperTest`(NF-A1)、`StaleFilterTest`(NF-A7) |
| 测试源目录 | `lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/` |
| 基础设施 | `kotlinx-coroutines-test` + `runTest` + `StandardTestDispatcher` + **虚拟时间**（`advanceTimeBy`/`runCurrent`）；长驻循环（sink 主循环）用 **`backgroundScope`** `[BC:1236]` |
| 🔴 **不用虚拟时间的** | `GatewayCloseReleasesThreadsTest`（真实线程，走 `Dispatchers.IO` + 真实 `withTimeout`）`[BC:1236]` |
| 🔴 **mapper 四件套不加载 SDK 类** | §6.0 纪律 1 的直接收益：可在 jlink 裁剪环境外独立跑绿 |

### 11.1 `PolicyMappingTest`（M3-T3，🔴 与 NF-A6 `LarkPolicyMapperTest` 是**同一个文件**）

> ⚠️ **命名冲突消解**：`[PLAN:394]` 的 T3 测试清单写 `PolicyMappingTest`，`[BC:1274]` NF-A6 写 `LarkPolicyMapperTest`。**本文档定稿：一个文件，类名 `PolicyMappingTest`，文件头注释里写明「= BC NF-A6 的 `LarkPolicyMapperTest`」**，避免两个文件各测一半。（登记为附录 B 第 16 条）

| # | 断言 | 阈值 / 形态 | 守护 |
|---|---|---|---|
| ① | `toSettings(policy)` 只把 `access.groupRequireMention` 下推为 `requireMention` | `policy.groupRequireMention = true ⇒ settings.requireMention == true`；`false ⇒ false` | 决策 3 |
| ② | **其余 4 个 SDK policy 字段恒为放行默认值**，**不随** `LarkPolicy` 变化 | `settings.groupAllowlist == emptyList()`、`dmMode == "open"`、`dmAllowlist == emptyList()`、`respondToMentionAll == true`——**对任意输入 policy** | 决策 3（SDK 侧全放行） |
| ③ | 🔴 **`chatQueueEnabled == false` 且不可被配置打开** | `GatewaySdkSettings.DEFAULT.chatQueueEnabled == false`；且 `toSettings` 的**任何**输入都产出 `false`（NF-A2 ② 的同源断言） | 决策 4 / `[BC:1270]` NF-A2 ② |
| ④ | 🔴 **`maxAttempts == 1`** | `settings.retryMaxAttempts == 1` | 决策 6 / `[BC:1248]` |
| ⑤ | `streamThrottleMs == 250`、`streamThrottleChars == 4000`、`textChunkLimit == 3500` | 精确相等 | 决策 19 / 18 / R7② |
| ⑥ | `allowedFileDirs` 来自 policy 且**默认空时必须显式填** | `settings.allowedFileDirs == policy.allowedFileDirs`；`[SF:916]` SDK 默认空 | `[SF:916]` |
| ⑦ | 🔴 **全量覆盖语义**（决策 7）：`updatePolicy` 只改 `requireMention` 时，其余 4 字段**仍等于当前完整 policy 的值** | 构造 `current = LarkPolicy(groupRequireMention=false, ...)`，调 `updatePolicy(current.copy(groupRequireMention=true))`，断言传给 SDK 的 `PolicyConfig` 的 5 个字段**全部**与 `current.copy(...)` 一致（尤其 `groupAllowlist` **未被清空**） | 🔴 决策 7 / §5.3 |
| ⑧ | 🔴 **卡片 JSON 字面约束**（NF-A6 ①-⑤ `[BC:1274]`） | ①`schema == "2.0"` + `body.elements` 存在；②button 走 `behaviors` 数组（`behaviors[0].type == "callback"`）且**无 `action` 键**；③`msgTypeOf(MARKDOWN) == "interactive"`、`msgTypeOf(TEXT) == "text"`；④`receiveId` 非空；⑤特殊字符（`"` / `\` / `\n` / emoji）转义**往返一致** | 🔒 `[BC:1026-1030]`、R7 |
| ⑨ | 🔴 **负向断言**（NF-A6 ⑥）：流式卡 elements 中出现 button 时 mapper **拒绝** | `assertFailsWith<IllegalArgumentException> { LarkOutboundMapper.toCardJson(CardSpec(title="t", markdown="m", streaming=true, buttons=listOf(CardButtonSpec("ok","ok")))) }` | 决策 22 / R7⑤ / 200810 |

### 11.2 `LarkInboundMapperTest`（M3-T3 = NF-A2 `[BC:1270]`）

| # | 断言 | 阈值 / 形态 | 守护 |
|---|---|---|---|
| ① | 🔴 **入站不做跨消息合并**：同 chat 600ms 内灌 **3 条** `NormalizedMessageRecord` ⇒ 产出 **3 条** `InboundMessage` | 且每条的 `messageId` / `senderId` / `createTimeMillis` **各自独立不被覆盖**；断言 `content` **不含** `"\n\n"` 拼接痕迹 | 决策 4 / §5.5 |
| ② | 🔴 **字段名按 `NormalizedMessage` 实际写** | 编译期即保证（`NormalizedMessageRecord` 的字段名 = 16 getter）；额外断言 `InboundMessage` **无 `eventId` 属性**（用反射 `InboundMessage::class.memberProperties.none { it.name == "eventId" }`） | 决策 15 / D12 |
| ③ | `chatType` 映射 | `"p2p"→P2P`、`"group"→GROUP`、`null→UNKNOWN`、`"GROUP"`（大写）`→UNKNOWN`（**大小写敏感**） | §6.1.3 |
| ④ | `createTimeMillis > 0` | 用录制样本（真实飞书时间戳）驱动 | NF-A2 ④ / `[BC §5.8]` |
| ⑤ | 🔴 **`rawContentType` 容忍未知值** | `kindOf("some_future_lark_type") == UNKNOWN` **且不抛**；`kindOf(null) == UNKNOWN`；`kindOf("interactive") == UNKNOWN`（**不是** `CARD_ACTION`） | 决策 15 / `[BC §9 P11]` |
| ⑥ | 10 个已知 `rawContentType` 全覆盖 | 逐值参数化断言（`text`/`image`/`file`/`audio`/`media`/`video`/`post`/`merge_forward`/`sticker`/`share_chat`/`share_user`） | §6.1.4 |
| ⑦ | 🔴 **不二次剥离 bot mention** | 输入 `content = "hello"`（SDK 已剥）⇒ 输出 `text == "hello"`；输入 `content = "@_user_1 hello"` 且 `mentions` 非空 ⇒ 输出**原样** `"@_user_1 hello"`（网关**不得**再剥） | `[SF:857]` `stripBotMentions=true` 硬编码 |
| ⑧ | `sessionKey` 复合键 | `threadId != null ⇒ chatId + threadId`；`threadId == null && rootId != null ⇒ chatId + rootId`；两者皆 null ⇒ `chatId + ""` | D13 / `[SF:855]` |
| ⑨ | `fromCardAction` 三条规则 | `kind == CARD_ACTION`、`rawContentType == null`、`chatType == UNKNOWN`、`cardAction != null` | §6.1.5 |
| ⑩ | 🔴 **用录制样本驱动，不连真机** | 样本以 Kotlin 字面量写在测试文件内（**不放 resources**，避免 jlink/打包路径问题） | NF-A2 ⑤ / 方案映射原则① |

### 11.3 `LarkOutboundMapperTest`（M3-T3）

| # | 断言 | 阈值 / 形态 | 守护 |
|---|---|---|---|
| ① | `toCardJson` 的 8 个键全部存在且值精确 | `schema=="2.0"`、`config.streaming_mode==spec.streaming`、`config.update_multi==true`、`config.streaming_config.print_frequency_ms==30`、`header.template==spec.titleColor.template`、`body.elements[0].tag=="markdown"`、`body.elements[0].element_id=="stream_md"`、`config.summary.content` 非空 | 🔒 `[BC:1026-1032]` |
| ② | `summary` 缺省时取 `markdown.take(80)` | 输入 200 字符 markdown ⇒ `summary.content.length == 80` | §6.2.2 |
| ③ | 🔴 button 走 `behaviors`、**无 `action`** | `assertNull(json 里任何层级的 "action" 键)`——**递归遍历**整个 Map | 🔒 `[BC:1030]` / 230099 |
| ④ | `primary` 映射 | `primary=true ⇒ "type"=="primary"`；`false ⇒ "default"` | §6.2.3 |
| ⑤ | 🔴 流式卡 + button ⇒ 抛 | 同 §11.1 ⑨（**两处都要有**：`PolicyMappingTest` 侧是 NF-A6 的验收点，这里是 mapper 的单元验收点） | 决策 22 |
| ⑥ | `msgTypeOf` / `contentJsonOf` | `TEXT→"text"` + `{"text":...}`；`MARKDOWN→"interactive"` + 含 `"schema":"2.0"`；`CARD→"interactive"` | 🔒 `[BC:1028-1029]` |
| ⑦ | 🔴 **`toPartialElementJson` 只含 `elements[0]`** | 断言返回的 Map **不含** `header` / `config` / `schema` 键（patch 只更新局部，带全量会被飞书拒） | §4.2 |
| ⑧ | `toFinishSettingsJson` | `streaming_mode == false` 且**不含** `elements` | §4.7 |
| ⑨ | `toCardRefContent` | `{"type":"card","data":{"card_id":<id>}}` 三层结构精确 | §4.2 步 2 |
| ⑩ | 转义往返 | 同 §11.1 ⑧ ⑤ | NF-A6 ⑤ |


### 11.4 `LarkErrorMapperTest`（M3-T3 = NF-A3 `LarkGatewayErrorMappingTest` `[BC:1271]`）

> ⚠️ **命名冲突消解**：任务书写 `LarkErrorMapperTest`，`[BC:1271]` NF-A3 写 `LarkGatewayErrorMappingTest`。**定稿：类名 `LarkErrorMapperTest`，文件头注明「= BC NF-A3」**（同 §11.1 的处置）。

| # | 断言 | 阈值 / 形态 | 守护 |
|---|---|---|---|
| ① | 🔴 **6 个卡片业务码各有专属 `GatewayErrorCode`** | `fromLarkCode(300317,null)==CardSequenceConflict`、`200860→CardTooLarge`、`200810→CardStreamBusy`、`200750→CardEntityExpired`、`300302→CardJsonInvalid`、`230099→CardJsonInvalid` | `[BC:1271]` NF-A3 ② / R2 / R7 |
| ② | `11232` 与 HTTP `429` 都 → `RateLimited` | `fromLarkCode(11232,200)==RateLimited`；`fromLarkCode(null,429)==RateLimited`；`fromLarkCode(11232,429)==RateLimited` | `[BC:1248]` NF-05 |
| ③ | 🔴 **优先级规则**：HTTP 状态先于业务码 | `fromLarkCode(200860, 429) == RateLimited`（**不是** `CardTooLarge`）——限流语义优先，否则 `RateGovernor` 不退避 | §6.3.2 |
| ④ | `99991672 → ConnectFatal` 且 `retryable == false` | | `[BC:1271]` NF-A3 ③ |
| ⑤ | HTTP `404` + 非 JSON ⇒ `ConnectFatal` | `fromLarkCode(null, 404) == ConnectFatal` | `[BC:1271]` NF-A3 ③ / BC-445..448 |
| ⑥ | 「缺 URL 字段 → `Retryable`」 | `fromThrowableName("java.lang.IllegalArgumentException","url is blank") == ConnectRetryable` | `[BC:1271]` NF-A3 ③ |
| ⑦ | `LarkChannelErrorCode` 9 值全覆盖 | 逐值参数化：`"FORMAT_ERROR"→FormatError` … `"UNKNOWN"→Unknown`；🔴 **未知字符串** `"SOME_NEW_CODE"→Unknown` **且不抛** | `[SF:890-892]` / §6.3.3 |
| ⑧ | 🔴 **`isRetryable` 的完整二分** | 26 个 `GatewayErrorCode` **逐个**断言（7 个 `true` / 19 个 `false`），用 `GatewayErrorCode.entries.forEach` 保证**新增枚举值时测试自动红** | 决策 6 |
| ⑨ | 🔴 **「卡片更新失败必须可观测」三件套**（NF-A3 ①） | 三件套的**计数**与**日志**部分**不在 mapper 内**（mapper 是纯函数）⇒ 断言落在 `LarkCardStreamSinkTest`（§4.9 的 `check()`）：① `failureCounters[code] > 0`；② 日志含 `cardId` + `sequence` + 业务码；③ 本 mapper 提供错误映射。**测试文件里必须写明这条分工**，否则 NF-A3 ① 会被误判为未覆盖 | `[BC:1271]` NF-A3 ① |
| ⑩ | 🔴 **出站失败返回 `false` 不抛异常**（NF-A3 ④） | 断言 `LarkErrorMapper` **无任何** `throw`；`SendResult.ok == false` 的构造由 `SdkLarkGateway.send()` 承担（BC-459） | `[BC:1271]` NF-A3 ④ |

### 11.5 `CardThrottleTest`（M3-T3 写，`[BC:1245]` NF-02 标 M4-T8 ⇒ 见下方归属说明）

> 🔴 **归属定稿**：`[BC:1245]` 把 NF-02 标为 **M4-T8**（因为节流器当时设想在 `ProgressCardRenderer` 侧）。但决策 1/2 把流式实现整体下沉到 `:lark-gateway` 的 `LarkCardStreamSink` ⇒ **节流器在网关内**，测试也必须在 `:lark-gateway`。
> **本文档定稿：`CardThrottleTest` 在 M3-T3 落地（`:lark-gateway/src/test`），M4-T8 不再重复实现节流，只做「进度卡链路」的集成断言。**（登记为附录 B 第 17 条）

| # | 断言 | 阈值 / 形态 | 守护 |
|---|---|---|---|
| ① | 🔴 **虚拟时间下 1000 次 `append`/秒 ⇒ ≤ 4 次 `update`** | `runTest { backgroundScope.launch{sink} ; repeat(1000){ sink.tryAppend("x") ; advanceTimeBy(1) } ; advanceTimeBy(1000)` ⇒ `fakeCardKit.patchCallCount <= 4`。**阈值 4 = 1000ms / 250ms** | R7② / D1 / D3 / `[BC:1245]` |
| ② | 🔴 **字符增量也能触发**（决策 19） | `advanceTimeBy(100)`（未满 250ms）但 `tryAppend` 了 **4000 字符** ⇒ `patchCallCount == 1`。反证：只按时间节流会让长输出的观感卡顿 | 决策 19 / `[SF:912]` `Throttle.note()` 的**或**关系 |
| ③ | 🔴 **终态更新不受窗口限制**（立即 flush） | 刚 `patch` 完（0ms 前）立即 `flushTerminal(card)` ⇒ 终态调用**立刻发出**，不等 250ms | `[BC:1245]` |
| ④ | 🔴 **1 槽 conflate + 单一不可变快照**（D3） | 结构性断言：grep `LarkCardStreamSink.kt` **零** `Channel(Channel.UNLIMITED)`、**零** 4 个独立 `var` 组合；`tryAppend` 内**只有一次** `AtomicReference.updateAndGet` | D3 / `[BC:1245]` |
| ⑤ | **不丢字**：节流窗口内的多次 append **拼接**而非覆盖 | 窗口内 `tryAppend("a")`、`tryAppend("b")` ⇒ 下一次 `patch` 的内容含 `"ab"`（🔴 覆盖会丢 `"a"`） | §4.4 |
| ⑥ | `tryAppend` 返回值语义 | sink 开放时恒 `true`；`close()` 后恒 `false`；空串 `true`（无副作用） | §4.4 |
| ⑦ | 🔴 **长驻循环用 `backgroundScope`** | 否则 `runTest` 因未结束的子协程**挂死** | `[BC:1236]` |

### 11.6 `SegmentRotationTest`（M3-T3，`[BC:1246]` NF-03 标 M4-T8 ⇒ 归属同 §11.5）

| # | 断言 | 阈值 / 形态 | 守护 |
|---|---|---|---|
| ① | 🔴 **40KB 内容 ⇒ 恰好 2 段** | 预算 `cardByteBudget = 24576`（24KB）。40KB / 24KB ⇒ 第 1 段 24KB + 第 2 段 16KB = **2 段**。断言 `fakeCardKit.createInstanceCallCount == 2` 且 `sink.segmentIndex == 2` | R7① / D1 / `[BC:1246]` |
| ② | 🔴 **必须在 `CardStreamSink` 层直接喂 40KB** | `[BC §9 P4]` 指出：`agent.progress.cardMaxChars = 5000`（`ProgressCardRenderer.Limits` 的第三层截断 `[BC:1031]`）会让**经进度卡链路**的内容永远 ≤ 5000 字符 ⇒ 24KB 预算**永不触发**，测试变成假绿。**定稿：测试直接构造 `LarkCardStreamSink` 并 `tryAppend` 一个 40_000 字节的字符串，完全绕过 `ProgressCardRenderer`** | 🔴 `[BC §9 P4]` |
| ③ | 🔴 **按 UTF-8 字节算，不按字符算** | 用**中文字符**（每个 3 字节）构造：`8192 个中文字符 = 24576 字节` ⇒ 恰触发轮换。若按字符算则永不触发 | §4.5 / §7.1 |
| ④ | **每段是新建卡片实体** | 两段的 `card_id` **互不相同**（`assertNotEquals(id1, id2)`）；🔴 **绝不复用** `card_id` | R20 / `[BC:1246]` |
| ⑤ | 🔴 **sequence 断言按 §5.2 调整** | 「每个 `CardStreamSink` 持**一个 `Int`**，从起始值单调递增」；轮换后新 sink 内部计数器**重新从起始值开始**（新实体 ⇒ 新 sequence 空间） | `[BC:1088]` §5.2 |
| ⑥ | 🔴 **禁止的断言形态** | 测试文件内**不得出现**：`map size`、`256`、`LRU`、`逐出`、`cardSequenceMapSize`。用 grep 断言自查（`[BC:1246]` 明列禁止） | R8 分支已取消（决策 2） |
| ⑦ | 轮换发出 `GatewaySignal.StreamSegmentRotated` | `reason == ByteBudget`、`segmentIndex == 2`、`newMessageId != null` | §2.7 |
| ⑧ | `200860` 触发**立即**轮换（不等预算判定） | fake cardkit 对第 1 次 patch 返回 `code=200860` ⇒ 断言紧接着发生 `createInstance` | §6.3.2 / R7① |

### 11.7 `Stream10MinRotationTest`（M3-T3，`[BC:1247]` NF-04 标 M4-T8 ⇒ 归属同 §11.5）

| # | 断言 | 阈值 / 形态 | 守护 |
|---|---|---|---|
| ① | 🔴 **9min 主动轮换** | `advanceTimeBy(540_000)` ⇒ 恰发生 **1 次**轮换（`createInstanceCallCount == 2`）；`advanceTimeBy(539_999)` ⇒ **0 次** | R7⑤ / `[BC:1247]` |
| ② | 🔴 **跨 9min 边界断言轮换恰发生一次** | 持续 append 18min ⇒ **恰好 2 次**轮换（第 9min、第 18min），**不是** 「每秒检查导致多次」 | `[BC:1247]` |
| ③ | **不复用 `card_id`** | 三段的 `card_id` 两两不等 | R20 / `[BC:1247]` |
| ④ | 🔴 **`200750` 映射为专属错误码并触发重建** | fake cardkit 返回 `code=200750` ⇒ 断言 ① `failureCounters[CardEntityExpired] == 1`；② 随后发生 `createInstance`（重建）；③ `RotationReason.EntityExpired` 的信号被发出 | R20 / `[BC:1247]` |
| ⑤ | 期限轮换发出信号 | `reason == TimeDeadline` | §2.7 |
| ⑥ | 🔴 **用虚拟时间，不连真机** | `[RB §6.3]` 明确「9/10 分钟轮换可能未实测」⇒ 真机行为在 **T11** 收口（§13 第 6 条） | §14.6 修正、`[BC:1236]` |
| ⑦ | 期限与预算**同时**满足时只轮换一次 | 在 8min59s 灌入 40KB ⇒ 断言 `createInstanceCallCount == 2`（**不是 3**）。🔴 实现上需在轮换后**同时**重置 `segmentStartMillis` 与 `byteCount` | §4.6（四种触发条件的去重） |


### 11.8 `RateGovernorTest`（🔴 **M4-T7 归属，本文档只给阈值，不写规格**）

`RateGovernor` 在 `:lark-bridge`（引擎侧），不在网关。本文档记录其阈值只为**保证网关侧不重复实现频控**：

| 阈值 | 值 | 出处 |
|---|---|---|
| 每 chat 令牌桶 | **4 rps** | R7⑥ / `[BC:1106]` / 决策 20 |
| 全局令牌桶 | **40 rps** | `[BC:1106]` / 决策 20 |
| 读 `x-ogw-ratelimit-reset` 退避 | 头缺失 ⇒ 固定退避 `1s → 2s → 4s`，上限 `30s` | §7.2；⚠️ 头是否真实存在 `待 S6`（§13 第 4 条） |
| `11232` 触发退避 | 是 | `[BC:1248]` |
| 退避挂起在 `net` dispatcher | 是（不阻塞 `Default`） | `[BC:1248]` |
| 🔴 追加断言「`RateGovernor` 放行后才发起 SDK 调用」 | 是 | `[BC:1248]`（避免与 SDK 盲退避双层放大） |

🔴 **网关侧的对应约束**（这些**是** T3 的验收项）：
- `LarkCardStreamSink` 的 patch 调用**不得自带重试**，重试决策交 `RateGovernor`（决策 6）；
- `send()` **永不重试**（决策 6），且 🔴 **禁止调用 `LarkErrorMapper.isRetryable`**（§6.3.4）；
- 网关收到 `RateLimited` 时**只发 `GatewaySignal.RateLimited(resetSec, larkCode, chatId)`**，**不自己退避**（§3.5）。

### 11.9 `Handler3SecondTest`（🔴 **M4-T7 归属，但结构性约束由 T3 落地**）

`[BC:1244]` NF-01 标 M4-T7。**但它的核心断言（回调内只允许 `trySend`）测的是网关代码** ⇒ 定稿分工：

| 断言 | 落地里程碑 | 说明 |
|---|---|---|
| ① SDK 回调内**只允许** `runCatching { inboundChannel.trySend(msg) }`，单次耗时 **< 1ms** | **M3-T3 落地**（测 `SdkLarkGateway` 的 `capture()` + handler） | 🔴 阈值 **<1ms**（决策 9）。实测依据：`[SCAFFOLD S4:128-144]` 的 16 getter 复制是微秒级；S4-PASS 判定 ⑦ 要求 `<1000us` `[RB:164]` |
| ② intake 路径 **grep 断言零** `synchronized` / 零 `Mutex` / 零 `suspend` 调用 / 零 `launch` / 零日志格式化 | **M3-T3 落地**（grep 范围 = `SdkLarkGateway.kt` 的 handler 函数体） | 🔴 **本项是 T3 的硬验收**：handler 内出现任何一处 `log.info(...)` 字符串插值即红 |
| ③ 回调内**不得**出现任何 IO / 网络 / 锁 / JSON 序列化 | **M3-T3 落地** | 🔴 这就是「`capture()` 只做 getter 复制、不做 mapper 转换」的理由——**mapper 转换在收集协程里做**（§3.4），不在回调里 |
| ④ handler 阻塞期间飞书重推**不被去重拦住**的因果链说明 | 文档级（§3.4 已写） | `[SF:359]` handler **同步**执行 + ACK 与 `X-Biz-Rt` 在 handler **返回之后**才发 + `SafetyPipeline.seenCache.mark(id)` 在返回后的 finally 里 |

> 🔴 **`<1ms` 的测量方式**（避免测试自身抖动导致 flaky）：用 `repeat(10_000) { capture(sample) }` 测**总耗时**再除以 10000，断言平均 `< 1ms`；**不用**单次 `measureNanoTime`（JIT 预热会假红）。真实线程，**不用虚拟时间**。

### 11.10 `GatewayCloseReleasesThreadsTest`（M3-T3，🔴 决策 13③ 的报警点）

| # | 断言 | 阈值 / 形态 | 守护 |
|---|---|---|---|
| ① | `close()` 后 `wsExecutorReleased == true` | 网关暴露 `internal val wsExecutorReleased: Boolean`（§3.11）供测试读取 | 决策 13① |
| ② | 🔴 **非 daemon 线程数回落到基线** | `before = countNonDaemonPoolThreads()`；`connect() → close()`；`after = ...`；断言 `after <= before`。`countNonDaemonPoolThreads()` = `Thread.getAllStackTraces().keys.count { !it.isDaemon && it.name.startsWith("pool-") }` | 决策 13③ / `[SF:791]` |
| ③ | 🔴 **start/stop × N 不累积** | 循环 **5 次**（🔴 不是 `[BC:1258]` 的 20 次——`StartStopLeakTest` 测的是**引擎全链路** 20 次，含子进程/卡片流/审计；本网关级测试只测 SDK 线程，5 次足以暴露 `2*CPU+1` 的累积且跑得快）⇒ 断言最终 `after <= before + 0` | 决策 13③ / `[BC:1258]` NF-15 ① |
| ④ | `close()` **幂等** | 连调 3 次不抛、不重复 disconnect（fake channel 的 `disconnectCount == 1`） | §3.11 / `[BC:1257]` CAS 纪律 |
| ⑤ | 🔴 **反射失效时立刻报警** | 用 fake `ws.Client`（字段名改成 `executorRenamed`）⇒ 断言 `wsExecutorReleased == false` **且** 日志里有一条 ERROR 含「SDK 升级导致反射路径失效」 | 决策 13③ |
| ⑥ | `close()` 后 `connectionState` 归位 | `Disconnected(Unknown, "closed")`；若 close 前是 `Idle` ⇒ 保持 `Idle` | §2.7 状态机 |
| ⑦ | 🔴 **网关内零 `exitProcess` / `System.exit`** | grep `lark-gateway/src/main` 断言零命中（§9.4：杀进程是宿主的权力） | §9.4 |
| ⑧ | **不用虚拟时间** | 真实 `Dispatchers.IO` + 真实 `withTimeout(5s)` | `[BC:1236]` |

> 🔴 **①②③ 与 `[BC:1258]` NF-15 ① 的 `TBD(用户裁决中)` 的关系**：NF-15 ① 的阈值原标 `TBD`，因为当时 SDK 线程泄漏的缓解方案未定（`[BC §9 P2]`）。**决策 13 已解除该 TBD** ⇒ NF-15 ① 的阈值定稿为「**`threadCount` 回落到基线（±2 容差，容纳 JIT/GC 线程波动）**」。本文档据此给出网关级的 ①②③，M4-T9 的 `StartStopLeakTest` 照此收口。（登记为附录 B 第 18 条：`[BC]` 的 `TBD` 需回填）

### 11.11 `GatewayDispatcherTest`（M3-T3 = NF-A4 `[BC:1272]`，🔴 断言已收窄）

| # | 断言 | 阈值 / 形态 | 守护 |
|---|---|---|---|
| ① | 🔴 **四类同步 SDK 调用点的执行线程名以 `bridge-net` 开头** | `connect` / `disconnect` / cardkit 四步 / messageResource 下载。用注入的 fake SDK 在调用点记录 `Thread.currentThread().name` | 决策 5 / `[BC:1272]` |
| ② | 🔴 **`RetryConfig.maxAttempts == 1`**（grep + `PolicyMappingTest` ④ 双证） | 确保**永不进入** `OutboundRetry` 的 `Thread.sleep` 路径（`[SF:568]`：该 sleep 在 commonPool 上执行，会占住线程） | `[SF:597]` / 决策 6 |
| ③ | 🔴 **`commonPool` 不可完全消解的部分已如实登记** | `ch.send()` / `addReaction()` / `removeReactionByEmoji()` **只有 `CompletableFuture` 变体** ⇒ SDK 内部 `supplyAsync` 落 commonPool，我方无法改。断言形态**不覆盖**这三处；测试文件的注释里必须写明这条豁免及其理由（§3.9） | 附录 B 第 4 条 |
| ④ | `net` dispatcher 由**构造参数注入**，网关内不自建 | grep `SdkLarkGateway.kt` 断言：零 `Dispatchers.IO`、零 `limitedParallelism`（**唯一例外**：`RegisterAppFlow.kt` 的 `bridge-register`，§9.2） | §9.2 / D16 |
| ⑤ | 🔴 **`registerApp` 跑在 `bridge-register`** | 断言线程名以 `bridge-register` 开头，且**不以** `bridge-net` 开头（不占 net 的 8 槽） | 决策 12 / §8.3 |

### 11.12 `FileDownloadStreamingTest`（M3-T3 + M4-T8 = NF-A5 `[BC:1273]`，🔴 断言已调整）

> 🔴 **`[BC:1273]` 原断言「采样 `Runtime.totalMemory()-freeMemory()` 增量**远小于文件体积**」在 SDK 2.8.5 上必然失败**：`GetMessageResourceResp extends BaseResponse<ByteArrayOutputStream>` ⇒ SDK **先把整个文件读进堆**，再 `writeFile(String)` 落盘。raw client **不提供** `InputStream` 路径（§3.7 的 javap 三行对照表）。
> `[BC §5.9]` 写的「改走 raw client 的流式落盘（`InputStream` → `FileOutputStream` 边读边写）」**与实测冲突** ⇒ 本文档定稿为**降级方案 + 三条可兑现断言**（登记为附录 B 第 3 条，只报告不裁定）。

| # | 断言（🔴 定稿，替代原 NF-A5 的内存增量断言） | 阈值 / 形态 | 守护 |
|---|---|---|---|
| ① | **上限钳制**：超 `downloadMaxBytes` 时**主动拒绝，不发请求** | fake SDK 报告 size 超限 ⇒ `downloadResource(...) == false` 且 `fakeClient.callCount == 0`。⚠️ `ResourceDescriptor` **无 size 字段**（§3.7）⇒ 实际钳制发生在**落盘后**：写临时文件时 `Files.size(tmp) > max` ⇒ 删除 + 返回 `false` | `[BC:1273]` / R10 |
| ② | **并发单飞**：同 `(messageId, fileKey)` 并发 5 次 ⇒ SDK 只被调 **1 次** | `fakeClient.callCount == 1`，其余 4 次拿到同一结果 | §3.7 三重防护之二 |
| ③ | **原子落盘 + 失败清理** | ① 成功路径：`dest` 存在且**无** `.part-*` 残留；② 失败路径（fake SDK 抛异常）：`dest` **不存在**、`.part-*` **已被删除** | §3.7 三重防护之三 |
| ④ | 🔴 **`writeFile` 的路径参数是 `String`** | 断言传给 SDK 的是 `dest.toString()`（契约签名是 `dest: Path`） | §3.7 / javap 实测 |
| ⑤ | **下载路径策略归引擎** | 网关**不校验** `dest` 是否在白名单内（`DownloadPathPolicy` 在 `:lark-bridge`，BC-414..418）。测试断言网关对任意 `dest` 都照写 | `[BC:1273]` 末句 |
| ⑥ | ⚠️ **真机内存曲线归 T11** | `待 S4 真机确认`：实测 100MB 文件下载时的堆峰值（§13 第 9 条）。若峰值不可接受，降级为「把 `fileMessage.maxFileSizeBytes` 的默认值从 100MB 下调」（属 `[CDS]` 范畴，需 Leader 裁决） | `[SF:279]` R-S2-1 同源 |


---

## 12. `LarkSdkIsolationTest`（M3-T4）的精确断言规格

> 🔴 **归属**：M3-T4（对应施工者，任务 ID:8 正在进行）。本节只提供**规格**，供其照抄/校正；本文档**不写该测试文件**。
> 文件位置：`[BC:1259]` 未指定模块。**定稿建议：放 `:lark-bridge/src/test`**（它需要遍历全仓文件树，而 `:lark-gateway` 的 test 源集在 S4/S6 期间有 `failOnNoDiscoveredTests = false` 的干扰；且 `:lark-bridge` 通过 `api(project(":lark-gateway"))` 能看到网关源码目录）。**若 对应施工者 已放在别处，以其为准**（登记为附录 B 第 19 条）。

### 12.0 🔴 交付后核验结果（本节 12.1-12.5 的**修正前提**，必读）

本节 12.1-12.5 是**在未见实现的情况下**按任务书「两条断言」撰写的规格。M3-T4（对应施工者，任务 ID:8）**已完成**，实现已落地。逐条核验结果如下——🔴 **以实测实现为准，本节 12.1-12.5 中与之冲突的部分作废**：

| 项 | 本节原规格（12.1-12.5） | 🔴 实测实现（已落地） | 结论 |
|---|---|---|---|
| 文件位置 | 建议 `:lark-bridge/src/test/kotlin/...` | **`composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/LarkSdkIsolationTest.kt`**（561 行，与 `UiArchitectureGuardTest` 同目录同包） | **原建议作废**。放 `:composeApp` 更优：它能看到全仓文件树，且不受 `:lark-gateway` 的 `failOnNoDiscoveredTests=false` 干扰 |
| 断言数量 | 「两条断言」（任务书原文） | **六组 A-F、9 个 `@Test`**：A(3) 依赖声明隔离 / B(2) 类型不泄漏 + 归一化自检 / C(1) 禁 relocate / D(1) jvmTarget 方向(R19) / E(1) ProcessBuilder 收口 / F(1) D16 静态可变状态 | **原规格覆盖面不足**，实测实现**更强**。本文档 §12.1-§12.4 的内容已被 A/B/C/D 组**全部覆盖且更精确** |
| 白名单形态 | **按文件**列白名单：`SdkLarkGateway.kt` / `LarkCardStreamSink.kt` / `RegisterAppFlow.kt` + spike 目录 + `build.gradle.kts` | **按目录前缀**：`gatewayKt = allKt.filter { rel.startsWith("lark-gateway/") }`，`outside = allKt - gatewayKt`（`:151-157`） | 🔴 **关键结论：本文档附录 B 第 12/19 条担心的「三个文件 vs 唯一文件」硬冲突不存在**。目录前缀白名单**天然容纳**网关内任意多个 import SDK 的文件 ⇒ **T3 可以直接开工，无需为此等裁决** |
| 黑名单 | 本文档列 **8** 项（含追加的 `com.lark.oapi.okhttp`） | 实测 **7** 项（`:527-535`）：`com.lark.oapi` / `com.larksuite` / `com.google.gson` / `org.apache.http` / `com.google.common` / `org.apache.commons.logging` / `ApacheHttpClientTransport` | 本文档第 8 项 `com.lark.oapi.okhttp` 是**冗余的**（已被 `com.lark.oapi` 前缀完全覆盖）⇒ **无需追加**，决策 16 要求的两项（`org.apache.commons.logging` / `ApacheHttpClientTransport`）**实测已在** |
| 注释/字符串归一化 | 本文档未提及 | 实测有 `normalizeKotlinSource()`（`:424-475`）剥离行注释/块注释(含嵌套)/字符串字面量后再匹配，并有独立 `@Test` 锁死归一化正确性（`:187-241`） | 🔴 **实测实现解决了本文档未发现的一个真问题**：`core/.../LogbackHelper.kt:46` 的 `getLogger("com.lark.oapi")` 是**合法字符串字面量**，若不归一化会误报 ⇒ 本文档 §12.5「测试自我防护」的第 1 条**被实测实现覆盖且更好** |
| 空扫描防永绿 | 本文档 §12.5 第 2 条要求 | 实测已有：`check(allKt.size >= MIN_KT_FILES)`（`:148`，下限 100）+ `assertTrue(gatewayKt.isNotEmpty())`（`:153`） | **已覆盖** |
| 🔴 **失败消息文案** | — | `:173` 写「`SdkLarkGateway.kt` 为**唯一** import SDK 的文件」 | ⚠️ **该行只是 `fail()` 的提示字符串，不是断言**，功能上无害；但与决策 1/12 定稿的「三个文件」**表述不符** ⇒ **建议在 M7-T16 一并修正文案**（本文档无权改），已登记为附录 B 第 20 条 |
| 🔴 **F 组与本文档 §4.1 的真实冲突** | 本文档 §12 未预见 | F 组（`:366-389`）：lib `src/main/kotlin` 内**同一文件**同时出现 `object`/`companion object` **与** `@Volatile` + `var` ⇒ fail | 🔴 **这是 T3 开工前唯一真实的硬冲突**，已在 §4.1 末尾补三条硬约束 + 附录 A.2.5 补两条勾选项。**不需要 Leader 裁决**（本文档已通过「禁止 `LarkCardStreamSink.kt` 出现 `object` 字样」消解），但**必须让 T3 施工者知道** |
| E 组 | 本文档未涉及 | E 组（`:332-351`）：lib `src/main/kotlin` 的 `ProcessBuilder` 只允许出现在 `exec/ProgramLauncher.kt` | ✅ **与本文档无冲突**：`:lark-gateway` 全文**零** `ProcessBuilder`（已 grep 确认），E 组对 gateway 是空集合合法 |
| D 组 | 本文档 §2.1 已按 R19 定稿包名 `com.jiaoay.lark.gateway` | D 组（`:280-314`）：lib 模块不得 `project(":core"/":ui"/":composeApp"/":feature-larkbridge")`，源码不得含 `com.jiaoay.easy_dev_toolbox` | ✅ **一致**。🔴 注意本文档正文有 3 处提及 `com.jiaoay.easy_dev_toolbox`，但**都在 `.md` 文档里**，D 组只扫 `.kt` ⇒ 不受影响；T3 施工者**不得**把这 3 处写进网关 `.kt`（含注释，因为 D 组同样先归一化再匹配，注释会被剥离 ⇒ 实际上注释里写也安全，但**不要依赖这一点**） |

> 🔴 **施工者读法**：§12.1-§12.5 **保留**作为「这些断言为什么这么设计」的推理记录；**实际断言以已落地的 561 行实现为唯一真源**。T3 施工者**不需要**再写 `LarkSdkIsolationTest`，只需要**不违反它**（违反点见上表最后三行 + §4.1 的 F 组约束）。

### 12.1 断言 A：`oapi-sdk` 依赖声明的**唯一性**

```
遍历 <repoRoot>/**/build.gradle.kts（排除 build/ 、.gradle/、build-logic/build/）
⇒ 文件内容匹配正则 `oapi[-.]sdk` 的集合 **必须 == { "lark-gateway/build.gradle.kts" }**
```

| 项 | 定稿 |
|---|---|
| 匹配方式 | 正则 `oapi[-.]sdk`（同时命中 `libs.oapi.sdk` 与 `oapi-sdk`），**不用**字面串 `com.larksuite.oapi:oapi-sdk`（后者会漏掉 version catalog 形态） |
| 比较方式 | **集合相等**（不是「包含」）⇒ 多一个或少一个都红 |
| 路径归一化 | 用相对 repoRoot 的 POSIX 分隔路径（`File.relativeTo(root).invariantSeparatorsPath`），避免 Windows `\` 导致断言在跨平台 CI 上假红 |
| 守护 | 决策 1（SDK 隔离到 `:lark-gateway`）+ `[BC:1259]` NF-16 ① |

### 12.2 断言 B：源码 import **黑名单**（🔴 用真实包名，决策 16）

```
遍历 <repoRoot>/**/*.kt（排除 build/、.gradle/）
筛出**不在** lark-gateway/src/ 下的文件
⇒ 文件内容不得出现下表任一字符串（含 import 与全限定名，故用**子串**匹配而非解析 import）
```

| # | 黑名单字符串 | 为什么在名单里 | 出处 |
|---|---|---|---|
| 1 | `com.lark.oapi` | SDK 根包。🔴 **真实包名**（决策 16）：`com.lark.oapi.channel.LarkChannel` / `com.lark.oapi.channel.LarkChannelFactory` / `com.lark.oapi.channel.ChannelEventHandler` / `com.lark.oapi.channel.config.LarkChannelOptions` / `com.lark.oapi.channel.model.NormalizedMessage` / `com.lark.oapi.Client` / `com.lark.oapi.ws.Client` / `com.lark.oapi.core.response.RawResponse` / `com.lark.oapi.scene.registration.RegisterApp` | `[SF §8]` javap 全量、决策 16 |
| 2 | `com.larksuite` | Maven 坐标 group（`com.larksuite.oapi:oapi-sdk`）；代码里出现即有人在写全限定坐标字符串 | `[BC:1259]` NF-16 ② |
| 3 | `com.google.gson` | 🔴 **Gson 未被 shade**（`[SF:936]`、`[SF:1292]`）⇒ 它随 SDK 传递进 classpath，是**最容易泄漏**的一个。这也是「禁止 relocate Gson」的根因 | `[SF:936]` |
| 4 | `org.apache.http` | httpclient 4.5.13 / httpcore / httpmime 随 SDK 传递进来（**死代码路径**，但 jar 在 classpath 上 ⇒ 可被 import） | `[SF:1291]` |
| 5 | `com.google.common` | guava 32.0.0-jre 随 SDK 传递进来 | `[SF:1291]` |
| 6 | 🔴 **`org.apache.commons.logging`** | **追加**（`[SF:1355]` R-S5-1）：commons-logging 1.2 随 httpclient 传递进来 ⇒ **双日志门面**风险（与 slf4j 并存会导致日志分裂到两个后端）。S5 已裁决 slf4j 单一门面（实测解析版本 `2.0.17` `[SF:1285]`） | 决策 16、`[SF:1355]` |
| 7 | 🔴 **`ApacheHttpClientTransport`** | **追加**（同上）：`com.lark.oapi.core.httpclient.ApacheHttpClientTransport` 全 SDK 源码树**无任何实例化点** `[SF:260]` ⇒ 它是死代码路径。若有人 import 它，等于把 jdeps 报的 `java.naming` + `java.security.jgss` 缺口**从死路径变成活路径**，jlink 裁剪产物会 `NoClassDefFoundError` | 决策 16、`[SF:260]`、`[SF:279]` |
| 8 | 🔴 **`com.lark.oapi.okhttp`** | shaded OkHttp（`OKHttps.defaultClient` 静态单例 `[SF:244]`）。它被 `com.lark.oapi` 覆盖，但**单独列出**是为了让报错信息更精确（有人可能以为「shaded 的 okhttp 不算 SDK」） | `[SF:244]`、`[SF:1292]` |

**白名单例外（🔴 必须精确，否则断言会把合法代码判红）**：

| 例外 | 理由 |
|---|---|
| `lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt` | 决策：唯一 import `LarkChannel` 系列的文件（§3.1） |
| `lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkCardStreamSink.kt` | cardkit 四步 API 必须 import `com.lark.oapi.Client` 与 `service.cardkit.v1.*`（§4.2）。🔴 方案 `[PLAN:394]` 只写「`SdkLarkGateway.kt` 是唯一 import SDK 的文件」，与决策 1（cardkit 走 raw client）**自相矛盾** ⇒ 本文档定稿为**两个**（登记为附录 B 第 12 条、§14 修正 10） |
| `lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/RegisterAppFlow.kt` | 决策 12 的扫码流必须 import `com.lark.oapi.scene.registration.*`（§8.1）⇒ **第三个** |
| `lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/spike/**` | S4/S6 脚手架**临时**允许用 raw JSON（决策 14：`includeRawInMessage`/`includeRawEvent` 只允许在 spike 里）。🔴 **S4/S6 完成后该目录整体删除**（§10.6），届时白名单条目一并删除 |
| `lark-gateway/build.gradle.kts` | 断言 B 只扫 `.kt`，天然不含它；但断言 A 需要它 |

> 🔴 **白名单用「文件相对路径精确相等」而非「目录前缀」**：目录前缀会让「往 `lark-gateway/src/main/.../gateway/` 里新加一个 import SDK 的文件」静默通过。精确相等 ⇒ **每加一个 import SDK 的文件，都必须显式改 `LarkSdkIsolationTest` 的白名单**，这就是一次强制的 code review 触发点。

### 12.3 断言 C：R19 方向断言（jvmTarget 11 不得依赖 21）

```
遍历 lark-bridge/src/**/*.kt 与 lark-gateway/src/**/*.kt
⇒ 不得出现字符串 "com.jiaoay.easy_dev_toolbox"
```

| 项 | 定稿 |
|---|---|
| 守护 | R19 `[PLAN:531]`：lib 模块是 jvmTarget **11**，`:core` 是 **21** ⇒ 反向依赖会 `UnsupportedClassVersionError` |
| 🔴 **lib 侧禁用清单**（`[PLAN]` 硬约束，本断言只覆盖包名，其余靠 code review） | `JLog` / `JResult` / `JErrorCode` / `AppDataStore` / `FileUtils` 一律不得引用；数据根路径由**宿主注入** |
| 与 §2.5 的关系 | 这就是 `BridgeErrorCode` 必须定义在 lib 侧、`GatewayErrorCode` 必须定义在网关侧的**同一个约束**的两个层次 |
| 出处 | `[BC:1259]` NF-16 ③ |

### 12.4 断言 D：🔴 **验收方式——「能抓错」而非「能通过」**

`[BC:1259]` NF-16 ④ 明列：**故意在 `:core` 加一行 `import com.google.gson.Gson` 后本测试必须失败**。

| 步 | 动作 | 期望 |
|---|---|---|
| 1 | 在 `core/src/main/kotlin/.../`（任一现有 `.kt`）临时加一行 `import com.google.gson.Gson` | — |
| 2 | 跑 `./gradlew :lark-bridge:test --tests "*LarkSdkIsolationTest*"` | 🔴 **必须红**，且报错信息含**文件路径 + 命中行号 + 命中的黑名单字符串** |
| 3 | 撤销该行 | — |
| 4 | 重跑 | **必须绿** |
| 5 | 🔴 **对称验收**（本文档追加）：在 `:core` 临时加 `import org.apache.commons.logging.LogFactory` 与 `import com.lark.oapi.core.httpclient.ApacheHttpClientTransport`，各自必须红 | 验证**追加的两个黑名单项**真的生效（否则它们只是文档里的字） |
| 6 | 🔴 **对称验收**（本文档追加）：在 `:lark-bridge/build.gradle.kts` 临时加 `implementation(libs.oapi.sdk)` ⇒ 断言 A 必须红 | 验证断言 A 的**集合相等**（不是包含）真的生效 |

> 🔴 **只跑「通过」不算验证**（`[BC:1259]` 原文）。步 2/5/6 的三次「必须红」是 M3-T4 的**验收闸门**，施工者需在回报里附上三次的失败输出。
> ⚠️ **执行时序约束**：步 1-6 会**临时修改现有文件** ⇒ 必须在 M3-T2 的构建验收（另一 agent 正在跑）**完成之后**执行，避免文件竞态。且每次改动后必须 `git diff` 确认已还原。

### 12.5 测试的**自我防护**（🔴 避免「测试自己坏了但一直绿」）

| # | 断言 | 理由 |
|---|---|---|
| ① | `repoRoot` 的推导必须**成功**：断言 `File(repoRoot, "settings.gradle.kts").exists()` | 若 repoRoot 推导错（例如 CI 的工作目录不同），遍历会得到**空集合** ⇒ 断言 A 的「集合相等」会红（好），但断言 B/C 的「不得出现」会**假绿**（坏） |
| ② | 断言 B 的遍历结果**文件数 > 100** | 本仓 `.kt` 文件实测远超 100 ⇒ 若为 0 或个位数，说明 glob 写错了，必须红 |
| ③ | 断言 A 的遍历结果**必须包含** `lark-gateway/build.gradle.kts` | 双向验证：既「不多」也「不少」 |
| ④ | 🔴 **禁止用 `@Disabled` / `assumeTrue` 跳过** | grep 测试文件本身，断言零 `@Disabled`、零 `assume` |


---

## 13. 待真机确认清单（S4 / S6 / T11）

> 🔴 **本节是全文档所有 `待真机确认` 标注的唯一汇总点**。每条给出：假设内容 → 当前定稿处置 → 验证方式 → **假设被推翻时的改动位点**（精确到文件与函数）。
> S4/S6 正在由 对应施工者 执行（任务 ID:3）；T11 是 M5 的人工闸门。

| # | 假设 | 归属 | 当前定稿处置 | 验证方式 | 🔴 被推翻时的改动位点 |
|---|---|---|---|---|---|
| 1 | **`sequence` 的起始值与递增语义**：假设起始 **1**、每次 patch **+1**、同 sequence 重发是**覆盖**而非追加 | **S6** | 每个 sink 自持一个 `Int`（决策 2）；`MarkdownStreamControllerImpl` 内部就是 `private int sequence` + `++sequence` `[SF §5.2 / BC:1086]` ⇒ 我方**镜像**该语义 | S6 抓取：① SDK 自己发流式卡时的第一个 sequence 值（打 SDK 的 DEBUG 日志或抓包）；② 故意用**同一 sequence** 连发两次不同内容，看飞书是覆盖还是报 `300317`；③ 故意**跳号**（1 → 3），看是否报 `300317` | `LarkCardStreamSink.kt`：① `private var sequence = Tuning.sequenceStart`（起始值改一个常量）；② `nextSequence()` 函数（递增语义改这一处）；③ `check()` 里 `300317` 的 `pendingAction`（若非「重建」而是「回退重发」，改这一处）。**三个位点都是单行改动，契约与测试形态不变** |
| 2 | **卡片 JSON 的 `config.update_multi` 与 `config.streaming_config` 结构** | **S6** | `update_multi = true`；`streaming_config = {print_frequency_ms:30, print_step:1, print_strategy:"fast"}`（§6.2.2） | S6 抓取：① SDK 的 `OutboundSender` 实际发出的卡片 JSON（脚手架已可打印）；② 官方卡片 JSON 2.0 文档；③ 若报 `300302` 则逐键试删 | `LarkOutboundMapper.kt` 的 `toCardJson()`：**三个键各一行**。🔴 若 `update_multi` 必须为 `false`，则 §11.3 ① 的断言同步改 |
| 3 | **`rawContentType` 各类型的实际取值**（尤其 `audio`/`media`、`video`、以及**卡片消息**是否作为 `message` 事件下发） | **S4** | §6.1.4 的映射表：10 个已知值 + **未知值一律 `UNKNOWN` 不抛** | S4 在真机上**逐个类型发一条消息**（文本/图片/文件/语音/视频/富文本/合并转发/表情/分享群/分享人/卡片），打印 `NormalizedMessage.getRawContentType()` 的**原始字面值**；同时确认 `CardActionEvent` 是否**也**触发 `message` 事件 | `LarkInboundMapper.kt` 的 `kindOf()`：**只加 `when` 分支**。🔴 因为 fallback 是 `UNKNOWN` 不抛，**即使假设全错也不会崩**，只是分类不准 ⇒ 这是本文档最安全的假设 |
| 4 | **`x-ogw-ratelimit-reset` 响应头是否真实存在** | **S6** | `GatewaySignal.RateLimited(resetSec: Int?)`，头缺失 ⇒ `null` ⇒ `RateGovernor` 用固定退避（§7.2） | S6 故意触发限流（1 秒内对同卡发 >10 次更新），抓 `RawResponse.getHeaders()` 的**全部键** | `LarkCardStreamSink.kt` 的 `readRateLimitResetHeader()`：**改一个头名字符串**。若该头根本不存在，`RateGovernor`（M4-T7）的固定退避分支即生效，**无需改网关** |
| 5 | **`11232`（整点压力限流）是否可观测** | **S6** | `LarkErrorMapper` 已映射为 `RateLimited`（§6.3.2） | S6 **无法主动构造**（11232 是飞书整点高峰的自发限流）⇒ 只能靠 T11 期间的**日志观测**。定稿：`check()` 对**所有**非 0 码都记 WARN 含原始码 ⇒ 即使 11232 出现也会被记录 | 无需改动。若长期观测不到，`[BC:1248]` NF-05 的「11232 触发退避」断言改为**纯单元测试**（fake 响应注入 11232），不要求真机复现 |
| 6 | **10 分钟自动关闭流的行为** | **S6 + T11** | `streamSegmentDeadlineMs = 540_000`（9min 主动轮换，留 60s 余量）；`[RB §6.3]` 明确「9/10 分钟轮换可能未实测」 | S6 让一个流式卡**持续 11 分钟不轮换**，观测：① 第 10 分钟后 patch 是否报错（报什么码）；② 卡片在飞书客户端的最终形态（停在中间态 / 显示「已结束」） | `Tuning.kt` 的 `streamSegmentDeadlineMs`：**改一个数字**。若飞书实际是 5min 关闭 ⇒ 改 `270_000`；若 10min 关闭时报的是 `200750` ⇒ `check()` 已有 `EntityExpired → RebuildStream` 分支，**自动兜住** |
| 7 | **流式期间点击按钮的 `200810` 表现** | **S6** | 🔴 **架构级消解**（决策 22）：流式卡**禁放任何交互组件**，`LarkOutboundMapper` 强制抛 `IllegalArgumentException` ⇒ 正常运行**不应命中** `200810` | S6 故意构造一张**带 button 的流式卡**，点击按钮，观测：① 是否回 `200810`；② `cardAction` 事件是否**根本不下发**；③ 流式是否被打断 | 无需改动（我方已禁止该组合）。S6 的价值是**确认 200810 的真实表现**，以便 M6 的 UI 文案与 `[BC §4.14]` 的 R7⑤ 描述准确 |
| 8 | **jlink 裁剪后 SDK 的网络/加密路径是否缺模块**（R-S2-1） | **T11** | 保留 S2 的 9 模块清单，**不新增**（§10.5）；理论推断「shaded OkHttp + JDK TLS，`java.base` 含 `javax.net.ssl`、`jdk.crypto.ec` 含 EC ⇒ 足够」`[SF:279]` | 🔴 **必须用打好的 `.deb`/`.msi`/`.dmg`**（**不是** `:composeApp:run`，后者跑在完整 JDK 上）实际收发一次消息，确认不出现 `NoClassDefFoundError: javax/naming/...` 或 TLS 握手失败 | `composeApp/build.gradle.kts` 的 jlink `modules(...)`：按方案既定应对补 `modules("java.naming")`，或退到 `includeAllModules = true`。**⚠️ 本文档无权改该文件**，改动归 M5/T11 |
| 9 | **raw client 下载 100MB 文件的真机堆峰值** | **S4 / T11** | §3.7 降级方案（上限钳制 + 单飞 + 临时文件原子落盘）；🔴 SDK **无** InputStream 路径，必然全量入堆一次 | 真机下载一个 ~100MB 文件，用 `-Xmx512m` 跑，观测是否 OOM / GC 停顿 | 🔴 **若不可接受**：① 把 `fileMessage.maxFileSizeBytes` 默认值从 100MB 下调（属 `[CDS]` 范畴，**需 Leader 裁决**）；② 或在网关内**分块**下载（但 `messageResource().get()` 无 Range 参数 ⇒ 技术上不可行）。**定稿：走 ①**，登记为附录 B 第 3 条 |
| 10 | **cardkit 的 `patch` vs `content` 两条流式文本 API 的真机对比** | **S6** | §4.2 定稿用 **`patch`**（三条理由：局部更新语义精确、S6 脚手架已编译验证、与 SDK 内部 `patchCard` 同路径）。`content` 保留为分支 B-1 | S6 分别用两个 API 发同一段流式内容，观测：① 是否都成功；② 打字机效果是否一致；③ 哪个更省流量 | `LarkCardStreamSink.kt` 的 `updateTextElement()`：**换一个 API 调用**（分支 B-1，§4.10 已写明改动形态） |
| 11 | **`uuid` 幂等字段与 `sequence` 的交互** | **S6** | §4.2 定稿**不填 `uuid`**（三条理由） | S6 若观测到「节流后重发导致内容错乱/重复」⇒ 启用 `uuid = "$cardId-$sequence"` | `LarkCardStreamSink.kt` 的**两处 `newBuilder()` 各加一行**（§4.2 已写明启用位点） |
| 12 | **`RegisterApp` 扫码流的真机可用性** | **S4（可选）/ T11** | §8 按 javap 实测签名实现（`[SF:921-931]`）；阻塞最长 ~600s | 真机扫码创建一个测试应用，观测：① `QRCodeInfo.getUrl()` 返回的是**图片 URL** 还是**需自行编码的内容串**；② `getExpireIn()` 的单位（秒 vs 毫秒）；③ 600s 上限是否真实；④ `UserInfo` 的字段 | 🔴 **若 `getUrl()` 是内容串而非图片 URL**：M6-T13 的 UI 需自行生成二维码图（引 `com.google.zxing` 或自绘）⇒ **属 M6 范畴，不改网关契约**。若 `getExpireIn()` 是毫秒：`RegisterAppFlow.kt` 的 `expireInSec` 换算**一行** |
| 13 | **`BlockedByPeerInstance` 与 SDK 的连接互斥关系** | **T11** | §2.5 定稿：由引擎注入 `appIdLockProbe`，网关**不触碰 SDK** 即返回该状态 | T11 同时启动 App 与 CLI `serve`，观测：① 我方 `AppIdLock` 是否先拦住（预期是）；② 若绕过我方锁，**飞书侧**是否也拒绝同 appId 的第二条长连接（R16 `[PLAN:527]`） | 若飞书侧也拒绝 ⇒ `connect()` 的 `ConnectFatal.msg` 需**区分**两种成因（我方锁 vs 飞书拒），改动位点：§3.6 步骤 0 与步骤 5 的 msg 文案 |
| 14 | **合并转发消息（`merge_forward`）在 handler 线程内发 HTTP 的实际耗时** | **S4 / T11** | `[SF:856]`：`subMessageFetcher` 只在该类型触发，是 SDK 内部**唯一**会在 handler 线程上发 HTTP 的路径 ⇒ 与 3 秒铁律**直接冲突**，但**我方无法消解**（SDK 内部行为） | S4/T11 发一条**合并转发**消息（含 10+ 条子消息），观测 handler 的实际耗时与飞书是否重推 | 🔴 **若超 3 秒**：唯一缓解是 `SafetyConfig` 关闭子消息展开（**需查 SDK 是否有该开关**，`[SF]` 未记录）⇒ 若无开关，则如实登记为**已知限制**并写进 M7 文档。`Handler3SecondTest` 的 <1ms 断言**只测我方 `capture()`**，不含 SDK 内部的 `subMessageFetcher` 耗时 ⇒ 测试不受影响 |

### 13.1 🔴 施工者与本文档假设的关系（三条纪律）

1. **T3 施工者按本文档的「当前定稿处置」写代码，不等 S4/S6 结果**。14 条假设的**改动位点全部是单行或单函数级**，且**没有一条会改动契约**（`MessageGateway` 12 成员 / `CardStreamSink` 5 成员 / `GatewayErrorCode` 26 值全部不变）⇒ S4/S6 的结果只影响**网关内部实现**，不影响 `:lark-bridge` 与 `:feature-larkbridge`。
2. **S4/S6 结果回来后，改动由 T3 施工者或 M5-T11 负责人执行**，逐条对照本表的「改动位点」列，**不得顺手改契约**。
3. 🔴 **`[BC]` 与 `[CDS]` 中所有 `TBD` / `待真机` 标注，在本表有对应条目的，以本表为唯一汇总点**（避免三份文档各记一半）。


---

## 14. 与方案不符之处的修正记录

> 格式：**方案/上游文档原文 → 实测事实（含出处）→ 本文档采用的结论**。
> 🔴 本节**只修正事实性错误与已被 Leader 裁决推翻的建议**，不重新提出任何 `[PLAN:553-567]` 已否决的 11 项替代方案。

| # | 原文（出处） | 实测事实（出处） | 本文档采用的结论 |
|---|---|---|---|
| **1** | 方案通篇用包名 `com.lark.oapi.LarkChannel` 形态的**简写**，未给完整包路径 | javap 实测：`com.lark.oapi.channel.LarkChannel` / `com.lark.oapi.channel.LarkChannelFactory` / `com.lark.oapi.channel.ChannelEventHandler` / `com.lark.oapi.channel.config.LarkChannelOptions` / `com.lark.oapi.channel.model.NormalizedMessage`；`SafetyConfig`/`PolicyConfig`/`BatchTextConfig`/`OutboundConfig`/`RetryConfig` 全是 `LarkChannelOptions` 的**静态嵌套类**（`LarkChannelOptions$PolicyConfig`），**不是**独立顶层类 `[SF §8]`、决策 16 | §3.2 给出**完整真实包名表**；`LarkSdkIsolationTest` 的黑名单用真实包名（§12.2）；🔴 施工者**不得**按方案简写去猜 import |
| **2** | `[PLAN:211]`（`net` dispatcher 行）写「**Apache HttpClient 是阻塞 IO**」；`[PLAN:801]` 同源表述「新实现依赖 JDK HttpClient/WebSocket」 | 🔴 **事实错误**。默认 HTTP 传输是 **shaded OkHttp**（`com.lark.oapi.okhttp`，静态单例 `OKHttps.defaultClient`）：`Transport.java:325` / `Client.java:578,581` / `:255` 三处 `new OkHttpTransport(...)`；`ApacheHttpClientTransport` **全 SDK 源码树无任何实例化点**；上传也走 OkHttp 的 `MultipartBody` 而非 `httpmime` 的 `MultipartEntityBuilder`；WebSocket 同样 shaded OkHttp（`ws/Client.java` 里 `new OkHttpClient()`）`[SF:241-262]`、`[SF:798-800]`、`[SF:1296]`、决策 17 | ① §9.1 的线程清单按 **shaded OkHttp** 写；② §10.5 定稿「jdeps 报的 `httpclient → java.naming + java.security.jgss` 缺口是**死代码路径**，不需补 jlink 模块、不需 `includeAllModules`」；③ §12.2 追加 `ApacheHttpClientTransport` 进黑名单（防止死路径被激活）；④ `net` dispatcher 的**理由改写**为「SDK 异步 API 全落 commonPool + `OutboundRetry` 用 `Thread.sleep` 占线程」，而不是「Apache 阻塞 IO」 |
| **3** | 方案把「文本分片 **3500**」归给 **`BatchTextConfig`**（入站合批） | 🔴 **归属错误**。`textChunkLimit = 3500` 是 **`OutboundConfig`（出站）**的字段 `[SF:903]`；`BatchTextConfig` 只有 `delayMs = 600`（入站合批窗口）`[SF §S3-E]`。决策 18 已裁定 | §5.2 把 `textChunkLimit=3500` 写进 **`OutboundConfig`** 段；§7.3 同；§3.3 的 `buildOptions` 定稿代码按此装配。🔴 施工者若照方案写进 `BatchTextConfig`，**编译就不过**（该类无此字段） |
| **4** | `[PLAN]` 与 `[SF:1301]` 都写 **`safety.chatQueueEnabled = false`** | 🔴 **属性名错误**。真实 Kotlin 属性名带 `is` 前缀：**`isChatQueueEnabled`**（javap 实测 getter/setter 形态 `isChatQueueEnabled()` / `setChatQueueEnabled(boolean)`，Kotlin 侧合成属性名为 `isChatQueueEnabled`）。决策 4 已裁定 | §3.3 的 `buildOptions` 定稿代码写 **`isChatQueueEnabled = s.chatQueueEnabled`**，并在代码注释里标 🔴 陷阱；`PolicyMappingTest` ③ 断言该值为 `false`。🔴 **照方案原文写 `chatQueueEnabled = false` 会怎样**：Kotlin 对 Java 的 `setChatQueueEnabled` 会合成**两个**可用名，`chatQueueEnabled` 也可能编译通过——但**一旦 SDK 改成 Kotlin 实现或改名，就会静默回落到默认 `true`**，触发消息合并的四个致命后果（§3.3）。故必须用**带 `is` 的显式名** |
| **5** | `[SF:810]` D-3 建议「**不试图修复 SDK**（禁止改 SDK、**禁止反射关它的 executor**），改为在进程边界收口」，并建议把 `StartStopLeakTest` 的线程断言降级为「单次循环内增长 ≤ 2*CPU+1 且第二次不再增长」 | 🔴 **Leader 决策 13 已推翻该建议**，改为「**反射 shutdown + 显式退出**」双保险。实测依据不变：`ws.Client` 构造即 `Executors.newFixedThreadPool(cores*2+1)`（**非 daemon**）`[SF:783]`，**全类无任何 shutdown**，`close()` 只 `disconnect()` ⇒ 每周期泄漏 ≥1 非 daemon 线程且**阻止 JVM 自然退出** `[SF:791]` | §3.11 给出**照抄自 spike 脚手架 `SpikeCommon.kt` 的完整反射代码**（沿继承链查 `getDeclaredField("executor")` → `isAccessible = true` → `shutdownNow()`）；§9.4 给出双保险表；§11.10 的 `GatewayCloseReleasesThreadsTest` **保留严格断言**（threadCount 回落到基线），**不采纳 D-3 的降级断言**；`[BC:1258]` NF-15 ① 的 `TBD(用户裁决中)` 据此解除（附录 B 第 18 条） |
| **6** | `[PLAN:67]` 契约 `registerApp(onQr: (deviceCode: String, verifyUrl: String) -> Unit)` | 🔴 **与 SDK 不对应**。实测 `com.lark.oapi.scene.registration.RegisterApp.register(RegisterAppOptions) → RegisterAppResult(clientId, clientSecret, userInfo)`；回调是 `onQRCode(QRCodeInfo)`，`QRCodeInfo` 的 getter 是 **`getUrl()` / `getExpireIn()`** `[SF:921-931]`。决策 12 已裁定改为 `(qrUrl, expireInSec)` | §8.2 定稿契约回调为 **`(qrUrl: String, expireInSec: Int)`**；🔴 **并修正决策 12 自身的 getter 名**：决策文写 `QRCodeInfo(qrCodeUrl, expireSeconds)`，实测是 `getUrl()`/`getExpireIn()` ⇒ §8.1 表格按**实测**写，§8.3 代码用 `info.url` / `info.expireIn`。单位（秒 vs 毫秒）标 `待 S4 真机确认`（§13 第 12 条） |
| **7** | `[PLAN:519]` R8 分支：`card_id → sequence` 的 **`LRU(256)`** + `cardSequenceMapSize` 指标 | 🔴 **SDK 实测取消该分支**。`MarkdownStreamControllerImpl` 内部就是 `private int sequence` + `++sequence`——**每个流对象一个计数器**，SDK 内部**不存在** `card_id → sequence` 的全局 map `[BC:1086-1088]` §5.2。决策 2 已裁定取消 | §4.1 的内部状态表：每个 `LarkCardStreamSink` 持**一个 `Int`**，随 sink 生命周期自然回收，**无全局 map**；§11.6 ⑥ **禁止**出现「map size 256 / LRU 逐出 / `cardSequenceMapSize`」类断言（grep 自查）；收益：少一个需容量治理的全局状态，D9/D20 的关闭链路少一步 |
| **8** | `[PLAN:72]` 协程化规则：「`callbackFlow` + **`awaitClose { channel.disconnect() }`**」 | 🔴 **在双 Flow 契约下语义错误**。契约有 `inbound` **和** `signals` 两个 `Flow`，若各自 `callbackFlow { awaitClose { ch.disconnect() } }`，则「其中一个 Flow 的收集结束」会**断掉整条长连接**（另一个 Flow 还活着）。且 `connectionState` 是 `StateFlow`（第三个订阅面） | §3.4 定稿改为：**单一内部 `Channel` + `receiveAsFlow()`**（`inbound` 与 `signals` 各一个），SDK 的 handler 注册在 `connect()` 里做一次；断开由 **`connectJob.invokeOnCompletion { disconnectSync() }`** + `close()` 承担，**不用 `awaitClose`**。🔴 决策 8（单 handler per event name）与此配合：9 个事件名各注册**一个** handler，内部分发到两个 Channel |
| **9** | `[PLAN:394]` T3 文件清单：「`SdkLarkGateway.kt`（**唯一 import SDK 的文件**）」 | 🔴 **与决策 1 自相矛盾**。决策 1 要求 cardkit 走 `LarkChannel.getRawClient()` 拿 `com.lark.oapi.Client` 直接调 4 个 API ⇒ `LarkCardStreamSink.kt` **必须** import `com.lark.oapi.Client` 与 `service.cardkit.v1.*`。决策 12 的扫码流 ⇒ `RegisterAppFlow.kt` **必须** import `com.lark.oapi.scene.registration.*` | §3.1 / §12.2 定稿为**三个** import SDK 的文件：`SdkLarkGateway.kt`、`LarkCardStreamSink.kt`、`RegisterAppFlow.kt`；🔴 `LarkSdkIsolationTest` 的白名单必须**精确列出这三个**（§12.2），否则 M3-T4 会把 T3 的合法代码判红。**「唯一」的语义收窄为「`:lark-gateway` 是唯一 import SDK 的模块」**（这才是决策 1 的原意） |
| **10** | `[PLAN:57]` `GatewaySignal` 只列 **6 个**子类：`Rejected`/`Reconnecting`/`Reconnected`/`RateLimited(resetSec)`/`SendFailed(code,msg)`/`StreamSegmentRotated` | 🔴 **方案未覆盖丢弃可观测性**。D18 要求「全部丢弃点加计数器并暴露为指标」`[PLAN:266]`，`[PLAN:225]` 明确 SDK handler → intake 的 `Channel(512, DROP_OLDEST)` 要「+ 计数 + WARN」，而该丢弃点的**唯一发生地是网关** | §2.7 **新增第 7 个子类** `Dropped(where: String, droppedTotal: Long)`。新增子类**不破坏**契约（sealed 的下游 `when` 若无 `else` 会编译失败，这正是我们要的强制可见性）。**已登记为附录 B 第 2 条（只报告不裁定）**；若 Leader 认为不应扩契约，替代方案是网关内 `LongAdder` + 引擎轮询（本文档不采纳，理由见 §2.7） |
| **11** | `[BC:1187]` §6.2 已记录：方案原表述「`BuiltinExecutors.kt` 的 `build()` **108 行**」 | `[BC:1188]` 实测：`BuiltinExecutors.kt` **全文 108 行**（`wc -l` 报 107，差 1 为末行无换行符口径），其中 **`build()` 为 :39-99，约 61 行** | 本文档**沿用 `[BC §6.2]` 的修正**：方案的「108 行」应理解为**文件全长**。对 T3 **无影响**（`BuiltinExecutors` 属 M4-T6），此处只为「修正记录」的完整性而登记 |
| **12** | `[BC:1213]` 记录旧 `fileDownloader` 的签名：`(suspend (messageId: String, fileKey: String, target: **java.io.File**) -> Boolean)?` | 契约 `[PLAN:64]` 的 `downloadResource(messageId, fileKey, dest: **java.nio.file.Path**): Boolean` 用的是 **`Path`** | 🔴 **这不是错误，是刻意的现代化**（`Path` 支持 `Files.move(ATOMIC_MOVE)`，§3.7 的原子落盘依赖它）。但 M4-T8 的 `FileMessageExecutor` 平移时**必须做 `File → Path` 适配**（`file.toPath()`）。本文档在 §3.7 定稿 `Path`，并在此登记该**跨里程碑的适配点**，避免 T8 施工者照抄旧签名 |
| **13** | `[BC:1273]` NF-A5：「下载…**改走 raw client 流式落盘**」；`[BC §5.9]`：「`InputStream` → `FileOutputStream` 边读边写」 | 🔴 **实测无此路径**。javap：`GetMessageResourceResp extends BaseResponse<**ByteArrayOutputStream**>` + `writeFile(**String**)`；`MessageResource.get(GetMessageResourceReq)` **无** `*Sync`/异步变体之分，返回类型固定 ⇒ SDK **先把整个文件读进堆**再落盘（§3.7 的三行对照表） | §3.7 定稿**降级方案**（上限钳制 + 并发单飞 + 临时文件 `.part-*` + `Files.move(ATOMIC_MOVE)` + 失败清理）；§11.12 把 NF-A5 的「内存增量远小于文件体积」断言**替换为三条可兑现断言**；真机堆峰值标 `待 S4 确认`（§13 第 9 条）。**已登记为附录 B 第 3 条（只报告不裁定）** |
| **14** | `[BC:1272]` NF-A4：「**所有** SDK 调用必须包在自有 `net` dispatcher 内（优先用 `*Sync` 变体）」 | 🔴 **不可完全兑现**。`ch.send()` / `addReaction()` / `removeReactionByEmoji()` **只有 `CompletableFuture` 变体** `[SF:816-829]` ⇒ SDK 内部 `supplyAsync` 无 Executor 重载 `[SF:343,346]`，实际 HTTP 执行**必然**落 commonPool。且 `MessageResource.get()` **无** `*Sync` 变体（决策 5 的「优先用 `*Sync`」在下载路径**无对应物**） | §3.9 定稿：① **可完全消解**的路径（`connectSync`/`disconnectSync`/cardkit 四步/messageResource 都是同步方法）一律 `withContext(netDispatcher)`；② **不可消解**的三处如实登记豁免（理由：非阻塞异步，不 `Thread.sleep`；且 `maxAttempts=1` 确保不进 `OutboundRetry` 的 sleep 路径 `[SF:568,597]`）；③ §11.11 把 NF-A4 的断言**收窄**为「四类**同步**调用点线程名以 `bridge-net` 开头」+「`maxAttempts == 1`」。**已登记为附录 B 第 4 条** |
| **15** | `[PLAN:51-78]` 的契约签名里出现 `LarkTarget` / `CardSpec` / `InboundMessage` / `Outbound` / `SendResult` / `GatewayConnectResult` / `LarkPolicy` / `LarkCredential` **8 个值类型**，且 `[PLAN:394]` 把 `GatewayModel.kt` / `LarkPolicy.kt` / `LarkCredential.kt` 列为要写的文件 | 🔴 **方案从未定义这 8 个类型的形状**（全文 grep 只在签名里出现名字）。这是「T3 施工者零决策开工」的**最大缺口** | §2.2-§2.5 **补齐全部 8 个定义**（含每个字段的来源、SDK 对应物、可空性、`init` 校验、`toString` 掩码）；`LarkPolicy` 在 §5.1 定义（5 字段，与 `[CDS §3.2.2]` 的 `access.*` 对齐）。🔴 **这些定义是本文档新增的定稿内容，不是方案的复述**——若 Leader 认为任何字段形状需调整，**必须在 T3 开工前提出**，否则契约一旦落地就会被 `:lark-bridge` 依赖 |
| **16** | `[BC:1245]` NF-02 / `[BC:1246]` NF-03 / `[BC:1247]` NF-04 把 `CardThrottleTest` / `SegmentRotationTest` / `Stream10MinRotationTest` 标为 **M4-T8** | 🔴 **归属已失效**。`[BC]` 撰写时节流器设想在 `ProgressCardRenderer`（引擎侧）；决策 1/2 把整个流式实现下沉到 `:lark-gateway` 的 `LarkCardStreamSink` ⇒ 被测代码在网关 | §11.0 / §11.5 / §11.6 / §11.7 定稿：**三个测试都在 M3-T3 落地**（`:lark-gateway/src/test`）；M4-T8 **不重复实现节流**，只做「进度卡链路」的集成断言。**已登记为附录 B 第 17 条**（`[BC]` 的里程碑标注需回填） |
| **17** | `[PLAN:394]` T3 测试清单只列 **`PolicyMappingTest`** 一个；`[BC:1274]` NF-A6 列 **`LarkPolicyMapperTest`**；`[BC:1271]` NF-A3 列 **`LarkGatewayErrorMappingTest`**，而任务书写 **`LarkErrorMapperTest`** | 🔴 **三处命名不一致**，若不消解会出现「两个文件各测一半，都绿，但覆盖不全」 | §11.1 / §11.4 定稿：**一个文件一个类名**——`PolicyMappingTest`（文件头注明「= BC NF-A6 的 `LarkPolicyMapperTest`」）、`LarkErrorMapperTest`（文件头注明「= BC NF-A3 的 `LarkGatewayErrorMappingTest`」）。**已登记为附录 B 第 16 条** |
| **18** | `[PLAN:206-241]` 六 dispatcher 表**未含**扫码用的 dispatcher | 决策 12 要求 `registerApp`「**必须包在专用 dispatcher**，不占 commonPool」；`register()` **阻塞最长 600s** `[SF:921]` ⇒ 占 `net` 的 1/8 槽达 10 分钟不可接受 | §8.3 / §9.2 定稿**第 7 个 dispatcher**：`Dispatchers.IO.limitedParallelism(1, "bridge-register")`，**网关私有**（不进 `BuiltinExecutors.build()`，因为它是 SDK 独有场景且 `limitedParallelism` 视图**无需 shutdown**，不构成泄漏）。`GatewayDispatcherTest` ⑤ 断言其线程名 |
| **19** | `[PLAN]` 十步关闭第 ⑥ 步「`gateway.close()` **释放 SDK 线程/连接池**」`[BC:1257]` | 🔴 **在决策 13 之前该步骤事实上无法兑现**：`LarkChannel` **不是 `AutoCloseable`、没有 `close()`** `[SF:831]`，SDK 只提供 `disconnect()`，且 `ws.Client.executor` 永不 shutdown `[SF:791]` | §9.1 + §9.3 + §3.11 使该步骤**重新可兑现**：契约的 `close()`（`AutoCloseable`，我方定义）映射到「`disconnectSync()` + 反射 shutdown executor + `scope.cancel()`」。**注意 `AutoCloseable` 是**我方契约**加的，不是 SDK 提供的** |
| **20** | `[SF:1360]` 附录 C：「`modules("java.net.http")` 注释理由已失效…或经同意后在 M3-T2 移除并重新验证打包」 | 实测 `composeApp/build.gradle.kts:59` 确有 `modules("java.net.http")`；新实现走 shaded OkHttp，**不再依赖 JDK HttpClient** `[SF:801]` | 本文档定稿：**模块保留、不删**（删属 M3-T2 范围外改动，且需重新验证打包）；**理由改写归 M7 文档**（§10.5）。🔴 T3 施工者**不得**动 `composeApp/build.gradle.kts` |


---

## 附录 A. T3 施工顺序与逐文件验收清单

> 🔴 本附录是**施工顺序的唯一权威**。12 个源文件 + 10 个测试文件 + 1 处构建文件改动，按下列 6 个批次推进；**每批次结束必须能编译通过**（`:lark-gateway:compileKotlin`），否则不得进入下一批次。

### A.1 施工顺序（6 批次，依赖拓扑排序）

| 批次 | 文件 | 依据章节 | 前置 | 本批次完成的判据 |
|---|---|---|---|---|
| **B0 构建** | `lark-gateway/build.gradle.kts`（**只追加一行**） | §10.2 | 无 | `tasks.shadowJar { enabled = false }` 已追加在 `dependencies {}` **之后**；🔴 **SPIKE 块 50 行一行不动**（§10.6）；🔴 `failOnNoDiscoveredTests = false` **暂留**（B5 末删除） |
| **B1 纯类型** | `GatewayModel.kt`、`MessageGateway.kt`、`CardStreamSink.kt`、`LarkPolicy.kt`、`Tuning.kt` | §2.2-§2.8、§5.1、§7 | B0 | 5 个文件**全部零 SDK import**；`GatewayErrorCode` 26 值齐全（§2.5）；`GatewaySignal` **7** 子类齐全（§2.7，含新增 `Dropped`）；`Tuning` 33 个常量值与 §7 表**逐字符相等** |
| **B2 纯 mapper** | `LarkPolicyMapper.kt`、`LarkInboundMapper.kt`、`LarkOutboundMapper.kt`、`LarkErrorMapper.kt` | §5.2、§6.1-§6.3 | B1 | 4 个文件**全部零 SDK import**（§6.0 纪律 1）；`kindOf` 有 `else` 兜底（§6.1.4）；`fromThrowableName` 收 `String` 不收 `Throwable`（§6.3.1）；`toSettings` 的 `isChatQueueEnabled=false` / `maxAttempts=1` / `streamThrottleMs=250` / `textChunkLimit=3500` 四个覆写值到位（§5.3） |
| **B3 mapper 测试** | `PolicyMappingTest.kt`、`LarkInboundMapperTest.kt`、`LarkOutboundMapperTest.kt`、`LarkErrorMapperTest.kt` | §11.1-§11.4 | B2 | 4 个测试文件共 **39 条断言**全绿；🔴 测试进程**不加载任何 SDK 类**（§11.0 收益）；文件头写明「= NF-A6 `LarkPolicyMapperTest`」「= NF-A3 `LarkGatewayErrorMappingTest`」的互注（§14 修正 17） |
| **B4 SDK 实现** | `SdkLarkGateway.kt`、`LarkCardStreamSink.kt`、`RegisterAppFlow.kt` | §3、§4、§8 | B2（B3 可并行） | **全仓 import SDK 的文件恰为这 3 个**（§12.2 白名单）；每个事件名**只注册一个 handler**（§3.4）；所有 SDK 调用在 `withContext(netDispatcher)` 内（§9.2）；handler 内**只有** `runCatching { inboundChannel.trySend(...) }`（决策 9）；`close()` 幂等 + 反射 shutdown 到位（§3.11）；`registerApp` 走 `bridge-register`（§8.3） |
| **B5 实现测试 + 收口** | `GatewayDispatcherTest.kt`、`FileDownloadStreamingTest.kt`、`CardThrottleTest.kt`、`SegmentRotationTest.kt`、`Stream10MinRotationTest.kt`、`GatewayCloseReleasesThreadsTest.kt` | §11.5-§11.12 | B4 | 6 个测试全绿；🔴 **删除** `build.gradle.kts` 里的 `failOnNoDiscoveredTests = false`（§10.6，否则类名写错会假绿）；🔴 重算测试基线（`:lark-gateway` 从 0 → **≥10**，§A.3） |

### A.2 逐文件验收勾选项（🔴 每个勾选项都有出处，不得凭感觉打勾）

#### A.2.1 `GatewayModel.kt` / `MessageGateway.kt` / `CardStreamSink.kt`

- [ ] 包声明是 `com.jiaoay.lark.gateway`，**不是** `com.jiaoay.easy_dev_toolbox.*`（§2.1，R19）
- [ ] `MessageGateway` **12 个成员一个不多一个不少**（§1.3 清点表）；签名与 §2.6 **逐字符相等**
- [ ] `CardStreamSink` **5 个成员**（§2.8）；`CardSpec` 无任何交互元素字段（决策 22）
- [ ] `InboundMessage` **无 `eventId` 字段**（决策 15）；幂等键只有 `messageId`
- [ ] `rawContentType` 的映射目标 `InboundKind` 含 `UNKNOWN`（§6.1.4）
- [ ] `GatewayConnectionState` 6 子类含 `Connected(botOpenId)` / `Disconnected(code,msg)` / `BlockedByPeerInstance`（§2.7）
- [ ] `GatewaySignal` **7** 子类：`Rejected` / `Reconnecting` / `Reconnected` / `RateLimited(resetSec)` / `SendFailed` / `StreamSegmentRotated` / **`Dropped`**（§2.7、附录 B 第 2 条）
- [ ] 文件头注释含 §10.4 要求的 4 行（其中第 4 行「本文件禁止 import SDK」由 T3 追加）

#### A.2.2 `Tuning.kt`

- [ ] 33 个常量**逐个带注释**说明对应的飞书硬限制（决策 20）
- [ ] `streamThrottleMs = 250`（**不是** SDK 默认 100）、`cardByteBudget = 24576`、`streamSegmentDeadlineMs = 540_000`、`textChunkLimit = 3500`、`wsHandshakeTimeoutMs = 15000` 五值**不得改动**
- [ ] 🔴 **不含** `perChatRps` / `globalRps`（消费者 `RateGovernor` 在 `:lark-bridge`，§7.2；值只在文档记录供 M4-T7 照抄）
- [ ] 🔴 这些常量**不得**出现在任何用户配置文档（决策 20；配置 schema 归 `config-domain-spec.md`）

#### A.2.3 四个 mapper

- [ ] `LarkPolicyMapper.updatePolicy` 路径**先复制完整 policy 再改目标字段**（决策 7；§5.3 的陷阱说明）
- [ ] `LarkInboundMapper.kindOf` 用 `when(String)` + `else`，**禁止** `InboundKind.valueOf(rawContentType)`（§6.1.4 的警告）
- [ ] `LarkOutboundMapper.toCardJson` 的 8 个键值与 §6.2.2 **逐字符相等**（`schema="2.0"`、`update_multi=true`、`element_id="stream_md"`、`print_frequency_ms=30`、`print_step=1`、`print_strategy="fast"`）
- [ ] button 用 `behaviors` 数组，**禁** `action` 包裹（否则 `230099 unknown property: action`）
- [ ] `CardSpec.streaming == true` 且携带 button ⇒ **抛 `CardJsonInvalid`**（决策 22 的测试可断言形态）
- [ ] `LarkErrorMapper` 三步优先级：HTTP 状态 → 业务码 → 兜底（§6.3.2）
- [ ] 文本分片**不在 mapper 内做**（§6.2.4，归出站链路）

#### A.2.4 `SdkLarkGateway.kt`

- [ ] 🔴 **单一 `Channel` + `connectJob.invokeOnCompletion`**，**不是** `callbackFlow + awaitClose`（§14 修正 8：双 Flow 下 `awaitClose` 语义错误）
- [ ] 9 个事件名各注册**恰好一个** handler（决策 8；`message`/`reject`/`cardAction`/`reaction`/`comment`/`botAdded`/`reconnecting`/`reconnected`/`error`）
- [ ] handler 体内**只允许** `runCatching { inboundChannel.trySend(msg) }`（决策 9）；🔴 **不得**在 handler 内 `launch`、不得 `await`、不得打日志以外的任何 IO
- [ ] `inbound` 是 `Channel(512, DROP_OLDEST)`，满时 emit `GatewaySignal.Dropped`（§2.7）
- [ ] `applySettings(GatewaySdkSettings)` 是**唯一**把纯数据翻译成 SDK 对象的私有函数（§2.1 的手法说明）
- [ ] `isChatQueueEnabled = false` **显式写出**（决策 4；🔴 属性名带 `is` 前缀）
- [ ] `RetryConfig.maxAttempts = 1`（决策 6）
- [ ] 未使用 `includeRawInMessage` / `includeRawEvent`（决策 14，编译 warning 检查）
- [ ] `close()` 幂等（`AtomicBoolean` CAS）+ 反射 shutdown `ws.Client.executor` 的具体路径与 `SpikeCommon.kt` 一致（§3.11、决策 13）
- [ ] `downloadResource` 走 `resp.writeFile(tmp.toString())` + `Files.move`，**不用** `ch.downloadResource` 的 `byte[]`（§3.7）

#### A.2.5 `LarkCardStreamSink.kt`

- [ ] cardkit 四步调用序列与 §4.2 一致；**每次流式会话新建实体，绝不复用 card_id**（决策 21 / R20）
- [ ] sequence 是**每 sink 一个 `private var Int`**，🔴 **无** `card_id→sequence` LRU、**无** `cardSequenceMapSize` 指标（决策 2；取消 R8）
- [ ] 节流是 **250ms + 字符增量双条件**（决策 19；SDK `Throttle.note()` 是 `||` 关系，我方必须同时考虑字符）
- [ ] 1 槽 conflate（§4.4，D3 修复）
- [ ] 24576 字节预算截断 + 分段轮换（§4.5）；9min 轮换（§4.6）
- [ ] 🔴 **每一次** cardkit 调用后**逐次检查** `resp.success()` 与业务码（决策 1；SDK 的 `updateCardElementContent`/`finishStreamingCard` 完全丢弃返回值，300317/200860/200810/200750 全静默）
- [ ] 200750 映射为 `CardEntityExpired` 并**触发重建实体**（决策 21）
- [ ] 🔴 **`LarkCardStreamSink.kt` 全文无 `object` / `companion object` / 匿名 `object :` 字样**（§4.1 的 F 组硬门禁；该文件含 2 个 `@Volatile private var`，一旦同文件出现 `object` 字样 ⇒ `LarkSdkIsolationTest` F 组直接 fail）
- [ ] 🔴 **`GatewayModel.kt` / `MessageGateway.kt` 内不得新增任何 `@Volatile var`**（这两个文件已因 `data object` 使 F 组的 hasObject=true）
- [ ] S6 的**两条分支都留**（决策 23）：PASS ⇒ 打字机；FAIL ⇒ 非流式分段 `updateCard`，且 §4.10 写明的文件/测试改动位点可直接执行

#### A.2.6 `RegisterAppFlow.kt`

- [ ] 走 `Dispatchers.IO.limitedParallelism(1, "bridge-register")`（§8.3、§9.2、§14 修正 18）
- [ ] 回调参数是 `(qrUrl: String, expireInSec: Int)`（决策 12）；🔴 实测 getter 是 `getUrl()` / `getExpireIn()`（§14 修正 6）
- [ ] 🔴 **CLI 五个子命令零漂移**：`serve`/`check`/`send`/`handle-event`/`permission-server`，**不新增 `register-app`**（决策 12；§8.4）；扫码入口只在 UI
- [ ] 阻塞式轮询最长约 600s，**不可中断**已如实登记（§8.3 规格 3、附录 B 第 15 条）

### A.3 🔴 测试基线重算（B5 末必做）

| 项 | 定稿 |
|---|---|
| 现状 | `:lark-gateway` 测试计数为 **0**（只有 spike 脚手架，且 `failOnNoDiscoveredTests = false` 使其静默绿） |
| B5 后 | 10 个测试文件 ⇒ `:lark-gateway` 计数变为 **≥10** |
| 影响 | 全仓 **782** 基线（`:composeApp` 15 / `:core` 28 / `:feature-larkbridge` 67 / `:feature-shelltools` 89 / `:feature-timestamp` 13 / `:lark-bridge` 483 / `:log-tools` 87）**需重算** |
| 谁改 | 🔴 **T3 施工者不改基线文档**（`behavior-contract.md` 只读，另有人所有）⇒ 把新计数**报给 Leader**，由 M4 收口时统一回填 |

---

## 附录 B. 撰写过程中发现的**新契约级矛盾 / 方案未覆盖点**（🔴 只报告，不裁定）

> 本附录是本文档**唯一**的「上报区」。每条格式固定四列：**现象 → 证据 → 本文档的临时处置 → 需 Leader 裁决的问题**。
> 「临时处置」是为了让 T3 能开工而做的**最保守假设**，**不是**裁决；Leader 若不同意，改动位点已在每条最后一列写明。
> 🔴 编号被正文引用（§2.5 / §2.7 / §3.7 / §3.9 / §4.2 / §6.2.1 / §6.3.5 / §7.2 / §8.2 / §8.3 / §11.1 / §11.4 / §11.5 / §11.10 / §11.12 / §12 / §13 / §14），**不得重排**。

| # | 现象 | 证据 | 本文档的临时处置 | 需 Leader 裁决的问题 |
|---|---|---|---|---|
| **1** | 🔴 **`BridgeErrorCode` 与 `GatewayErrorCode` 的值集不匹配**：NF-A3 ① 要求「每个飞书码有**专属** `BridgeErrorCode`」，但网关在 `:lark-gateway`（jvmTarget 11），`BridgeErrorCode` 定义在 `:lark-bridge` ⇒ **网关不能反向依赖**，必须自有 `GatewayErrorCode`。而 `config-domain-spec.md §12.1` 的 14 个 `BridgeErrorCode` 值中**没有任何卡片流式码、也没有扫码码** | `[BC:1271]`（NF-A3 ①）；`[CDS §12.1]`（14 值清单）；`[PLAN:531]`（lib 侧不得引用 `JErrorCode`） | §2.5 定稿 **26 值** `GatewayErrorCode`；§6.3.5 给出 `GatewayErrorCode → BridgeErrorCode` 的**建议**对接表，其中 **5 个卡片码 + 3 个扫码码在 CDS 侧无对应值**，只能临时压到 `SendFailed(2040)` / `Unknown`；**9 个 `LarkChannelErrorCode` 出站值也被压成 1 个 `SendFailed(2040)`** | ① 是否在 `config-domain-spec.md` **补 8 个新码值**（卡片流式 5 + 扫码 3）？② 若不补，NF-A3 ① 的「每个码有专属 `BridgeErrorCode`」断言**必须收窄**为「每个码有专属 `GatewayErrorCode`，且映射到 `BridgeErrorCode` 时允许 N:1」——**这条断言归 M3-T3，需要 Leader 明确批准收窄**，否则 §11.4 的第 ① 断言写不出可绿的形态 |
| **2** | **`GatewaySignal` 需要第 7 个子类 `Dropped`**：`inbound` 是 `Channel(512, DROP_OLDEST)`，丢弃事件**必须可观测**（否则「消息莫名消失」无法排障，且违反 D12 的可审计要求），但方案 `[PLAN:57]` 只列了 6 个子类 | `[PLAN:57]`；`[PLAN]` 有界内存链路表（`Channel(512, DROP_OLDEST)`）；D12 | §2.7 定稿新增 `Dropped(reason: String, count: Int)`；§14 修正 10 登记 | 扩契约成员属于**契约变更**（本文档的前提是「12 成员完整保留不改」，但 `GatewaySignal` 是 sealed **值类型**不是接口成员）。**是否批准新增第 7 个子类？** 若不批准，替代方案是把丢弃计数只写进 `metrics` 通道 ⇒ 但 `signals` 的消费者（UI 日志面板）就看不到丢弃 |
| **3** | 🔴 **raw client 无 `InputStream` 流式下载路径** ⇒ `[BC:1273]` NF-A5 的「内存增量**远小于**文件体积」断言在 SDK 2.8.5 上**必然失败**（`GetMessageResourceResp extends BaseResponse<ByteArrayOutputStream>`，整文件先入堆） | `[JAVAP com.lark.oapi.service.im.v1.resource.MessageResource]`（仅 `get(...)` 两个重载，无流式变体）；`[JAVAP GetMessageResourceResp]`（`writeFile(String)`）；`[SF:823]`（`downloadResource → CompletableFuture<byte[]>`） | §3.7 定稿**三重防护降级方案**（上限钳制 + 并发单飞 + 临时文件原子移动）；§11.12 把 NF-A5 断言改写为**三条可兑现的**（不用 `byte[]` 路径 / 临时文件 + move / 并发单飞）；§13 第 9 条列入待真机确认；§14 修正 13 登记 | ① **是否接受 NF-A5 断言形态的改写**（原断言不可兑现）？② 「堆峰值 = 并发下载数 × 单文件大小」是 SDK 2.8.5 的**硬约束**，是否要求引擎侧限制并发下载数（本文档建议 ≤2）？该限制属 M4-T8，**需要在 T8 规格里落一条**，本文档无权写 |
| **4** | 🔴 **决策 5「优先用 `*Sync` 变体」在三条路径上无对应物**：`ch.send()` / `addReaction()` / `removeReactionByEmoji()` 实测**只有 `CompletableFuture` 形态**，SDK 未提供 `*Sync` ⇒ NF-A4「**所有** SDK 调用必须包在自有 `net` dispatcher 内」不可完全兑现（`await()` 的续体恢复线程不由我方控制） | `[SF:840-900]`（javap 签名）；`[BC:1272]`（NF-A4 原文） | §3.9 / §3.10 定稿用 `withContext(netDispatcher) { future.await() }` 包裹（**发起**在 `net`，**续体恢复**可能在 commonPool）；§11.11 把 NF-A4 断言**收窄**为「四类同步调用点（`connectSync`/`disconnectSync`/`messageResource().get`/`RegisterApp.register`）+ 所有 `await()` 的调用点都在 `netDispatcher` 作用域内」；§14 修正 14 登记 | **是否接受 NF-A4 断言收窄？** 若不接受，唯一彻底方案是给这三个调用套 `runBlocking(netDispatcher)`（**会在 commonPool 上阻塞线程，与决策 5 的初衷相反**）或改用 `Dispatchers.IO` 上的 `future.get()`（同样是阻塞）。**本文档判断收窄是正确解，但需要 Leader 确认** |
| **5** | **cardkit 有 `patch` 与 `content` 两条候选 API**，S6 脚手架只验证了 `patch`（`PatchCardElementReq`）；另一条 `ContentCardElementReq` 语义相近但**未实测** | `[SF]` S6 脚手架的 API 选择；`[JAVAP com.lark.oapi.service.cardkit.v1]` | §4.2 定稿**只用 `patch`**（S6 将验证的那条），并在 §13 列为待真机确认 | S6 若发现 `patch` 有未知限制，**是否允许切换到 `content`**？切换会改动 §4.2 的第 ③ 步与 `CardThrottleTest` 的 mock 面 ⇒ 需要 Leader 预先授权「S6 FAIL 时的第二候选」，否则施工者会在 S6 结果出来后卡住 |
| **6** | **cardkit 的 `uuid` 幂等字段方案与脚手架均未使用**：`PatchCardElementReqBody` / `SettingsCardReqBody` 实测**都有 `uuid(String)`**，可用于请求级幂等，但方案与 S6 脚手架都没提 | `[JAVAP PatchCardElementReqBody]` / `[JAVAP SettingsCardReqBody]` | §4.2 定稿**不用 `uuid`**（`sequence` 已是幂等键，决策 2；多加一个键会引入「两个幂等键不一致」的新故障模式） | **是否确认不用 `uuid`？** 若 Leader 认为应该用（例如为了应对我方重试导致的重复 `patch`），需要给出 `uuid` 的生成规则与 `sequence` 的关系，本文档无法自行决定 |
| **7** | **`LarkChannel` 不是 `AutoCloseable`、没有 `close()`**，SDK 只提供 `disconnect()` ⇒ 方案十步关闭第 ⑥ 步「`gateway.close()` 释放 SDK 线程/连接池」在决策 13 之前**事实上无法兑现** | `[SF:831]`（`LarkChannel` 的方法面）；`[SF:791]`（executor 永不 shutdown）；`[BC:1257]`（第 ⑥ 步原文） | §9.1 / §9.3 / §3.11 使该步骤**重新可兑现**：`AutoCloseable` 是**我方契约**加的（不是 SDK 提供的），映射到「`disconnectSync()` + 反射 shutdown + `scope.cancel()`」；§14 修正 19 登记 | 无需裁决（决策 13 已覆盖），**仅报告**：契约里的 `close()` 与 SDK 的 `disconnect()` 是**两个不同层次的东西**，施工者容易混淆 ⇒ 已在 §3.8 / §3.11 分开写清 |
| **8** | **`com.lark.oapi.service.im.v1.enums.ReceiveIdType` 不存在**（javap 实测无此类）⇒ 发送时的 `receive_id_type` 只能用**字面串**（`"open_id"` / `"chat_id"`），无编译期保护 | `[JAVAP com.lark.oapi.service.im.v1.*]`（枚举包内无 `ReceiveIdType`） | §6.2 定稿 `LarkTarget` → 字面串的映射表（3 行），并在 `LarkOutboundMapperTest` 加断言锁死字面串 | 无需裁决，**仅报告**：拼写错误只会在运行时暴露（飞书返回参数错误）⇒ 已用测试断言兜住 |
| **9** | **`GetMessageResourceResp.writeFile(String)` 只接受 `String`**，而契约 `downloadResource(..., dest: Path)` 用 `Path` ⇒ 必须 `dest.toString()`；🔴 且 `writeFile` **自己会创建父目录吗？未实测** | `[JAVAP GetMessageResourceResp]`；契约签名（决策 10） | §3.7 定稿在 `writeFile` **之前**显式 `Files.createDirectories(dest.parent)`（不依赖 SDK 行为）；写入目标是 `.part-<ts>` 临时文件，成功后 `Files.move` | 无需裁决，**仅报告**：`[BC:1213]` 旧实现的 `fileDownloader` 签名用 `java.io.File` ⇒ **M4-T8 有一个 `File → Path` 的适配点**，已在 §14 修正 12 登记，T8 施工者必须知道 |
| **10** | **`ResourceDescriptor` 无 size 字段**（只有 `type`/`fileKey`/`fileName`/`durationMs`）⇒ 网关**无法在下载前按实际文件大小拒绝**，只能用配置上限钳制 | `[SF §8]`（`ResourceDescriptor` 的字段面） | §3.7 防护 ① 定稿为「网关侧只做**已知上限**钳制（`settings.downloadMaxBytes`，由引擎注入）；按实际大小拒绝由引擎在调用前完成」——但**引擎也只能用配置上限**，不是实际大小 | **`fileMessage.maxFileSizeBytes`（默认 100MB）的语义到底是「拒绝下载超过此值的文件」还是「下载缓冲区上限」？** 方案未明确。若是前者，SDK 2.8.5 **无法实现**（拿不到实际大小）⇒ 需要 Leader 明确该配置项的语义并同步给 `config-domain-spec.md` |
| **11** | **`InboundResource` 是否应携带 `durationMs`**：`ResourceDescriptor` 有该字段（音视频时长），但方案的入站模型未提及 ⇒ 本文档定稿**携带**（信息不丢），但引擎侧是否消费未知 | `[SF §8]`（`ResourceDescriptor` 字段面） | §2.3 定稿 `InboundResource(type, fileKey, fileName, durationMs)` | **是否保留 `durationMs`？** 若引擎不消费，保留会增加一个无用字段；若未来 UI 要显示时长，删掉就得再改契约。**本文档倾向保留**，需 Leader 确认 |
| **12** | 🔴 **方案 `[PLAN:394]`「`SdkLarkGateway.kt` 是唯一 import SDK 的文件」与决策 1 自相矛盾**：决策 1 要求卡片流式走 raw client 的 cardkit API ⇒ `LarkCardStreamSink.kt` 必须 import SDK；决策 12 要求真实扫码流 ⇒ `RegisterAppFlow.kt` 必须 import SDK | `[PLAN:394]`；决策 1；决策 12 | §3.1 / §6.2.1 / §12.2 定稿**三个**文件可 import SDK；§12.2 的 `LarkSdkIsolationTest` 白名单**恰好列这 3 个**；§14 修正 9 登记 | **是否批准把「唯一」改为「恰好三个」？** 这直接影响 M3-T4（对应施工者，任务 ID:8 **已完成**）的 `LarkSdkIsolationTest` 白名单 ⇒ 🔴 **若 对应施工者 已按「唯一」实现了断言，该测试现在必然是红的或白名单不完整，需要立刻核对** |
| **13** | **`perChatRps` / `globalRps` 的归属与 `internal Tuning` 的模块边界冲突**：决策 20 要求把这两个常量写进 `Tuning.kt`，但其消费者 `RateGovernor` 在 `:lark-bridge`，而网关的 `Tuning` 是 `internal` ⇒ `:lark-bridge` **看不到** | 决策 20；`[PLAN]` M4-T7（`RateGovernor` 归 `:lark-bridge`） | §7.2 定稿网关侧 `Tuning.kt` **只保留** `rateLimitResetHeader` / `rateLimitBackoffMs` / `rateLimitBackoffMaxMs`（网关自己消费）；`perChatRps=4` / `globalRps=40` **只在文档记录值与出处**，供 M4-T7 在自己的 `Tuning` 里照抄 | **是否接受「两个 rps 常量不进网关 `Tuning.kt`」？** 若 Leader 要求集中在一个文件，则 `Tuning` 必须改为 `public`（会泄漏到 `:feature-larkbridge`，违反封装）或在 `:lark-bridge` 复制一份（两处易漂移）。**本文档判断分置 + 文档记录是唯一无副作用解** |
| **14** | **`RegisterAppResult.userInfo` 刻意不进契约**：实测 `RegisterAppResult` 含 `clientId` / `clientSecret` / `userInfo`，本文档定稿只取前两个（`LarkCredential` 无 user 字段）⇒ **扫码人身份不落盘、不审计** | `[SF:921-931]`（`RegisterAppResult` 字段面）；§2.2（`LarkCredential` 3 字段） | §8.2 定稿丢弃 `userInfo`，并写明理由（凭据文件只存 app 级密钥；user 信息属 PII，不应进 `bridge-config.json`） | **是否确认丢弃 `userInfo`？** 若 UI 需要显示「由谁扫码创建」，需要扩 `LarkCredential` 或另设返回类型 ⇒ 属契约变更，需 Leader 批准。**本文档倾向丢弃**（PII 最小化） |
| **15** | **`RegisterApp.register()` 阻塞且不可中断**：实测阻塞式轮询最长约 600s，**无 cancel 句柄、不响应 `Thread.interrupt()`**（SDK 内部 `Thread.sleep` 轮询）⇒ 用户点「取消」后**最长仍占 `bridge-register` 槽 10 分钟** | `[SF:921]`（阻塞式轮询约 600s） | §8.3 规格 3 如实登记；定稿 `bridge-register` 是 `limitedParallelism(1)` ⇒ **期间第二次扫码会排队**；UI 侧必须禁用「再次扫码」按钮直到返回 | **UI 该如何表达「取消不了」？** 三种候选：① 不提供取消按钮（诚实但体验差）；② 提供取消按钮但只关闭对话框、后台仍占槽（**误导用户**）；③ 把 `register()` 包进 `withTimeout`（**超时后线程仍泄漏**，因为不可中断）。**本文档无权决定 UI 行为**（归 M6-T13），但需要在 T13 规格前给出裁决 |
| **16** | **测试命名三处不一致**：任务书写 `PolicyMappingTest`，`[BC:1274]` 写 `LarkPolicyMapperTest`（NF-A6）；任务书写 `LarkErrorMapperTest`，`[BC:1271]` 写 `LarkGatewayErrorMappingTest`（NF-A3）；`[BC:1270]` 的 NF-A2 与任务书的 `LarkInboundMapperTest` 一致 | `[BC:1270-1274]`；任务书章节 11 | §11.1 / §11.4 定稿「**一文件一类名** + 文件头互注」（用任务书的名字，文件头注明「= NF-A6 `LarkPolicyMapperTest`」）；§14 修正 17 登记 | **是否接受用任务书的名字为准？** 若 Leader 要求用 `behavior-contract.md` 的名字，则 §11.1 / §11.4 的类名与文件名都要改（**`behavior-contract.md` 只读，本文档不能改它**）⇒ 会造成两份文档的名字永久不一致，除非 Leader 授权 对应施工者 改 BC |
| **17** | **NF-02 / NF-03 / NF-04 的里程碑归属需要回填**：`[BC]` 标为 **M4-T8**，但这三条的被测代码（`LarkPolicyMapper` / 三个 mapper / `SdkLarkGateway`）**已下沉到 `:lark-gateway`** ⇒ 属 **M3-T3** | `[BC]` NF-02/03/04 的归属标注；§2.1（文件布局） | §11.0 / §11.5 定稿改标 **M3-T3**；§14 修正 16 登记 | **`behavior-contract.md` 只读** ⇒ 本文档无法修正归属标注。**是否授权对应施工者（任务 ID:5，已完成）回填 BC 的三处归属？** 若不回填，M4-T8 施工者会以为要写这三条测试，而 M3-T3 施工者以为不用写 ⇒ **重复劳动或双双漏写** |
| **18** | **`[BC:1258]` NF-15 ① 的阈值仍是 `TBD(用户裁决中)`**：决策 13 已给出「反射 shutdown + 显式退出」双保险 ⇒ 阈值**可以定稿了**，但 BC 未回填 | `[BC:1258]`（NF-15 ①）；决策 13 | §11.10 定稿 `GatewayCloseReleasesThreadsTest` 阈值为「**threadCount 回落到基线（±2 容差）**」，并说明 SDK 升级导致反射失效时该测试**立刻报警**；§14 修正 5 登记 | **±2 容差是否可接受？** 依据：`ws.Client` 的 `newFixedThreadPool(cores*2+1)` 在 `disconnect()` 后**核心线程仍存活**（除非反射 shutdown 成功）⇒ 若反射失败，泄漏数是 `cores*2+1`（8 核机器 = **17**），远大于 2，能被抓住。但 shaded OkHttp 的连接池线程是**懒创建 + 5 分钟空闲回收**，可能在测量窗口内残留 1-2 条 ⇒ 故留 ±2。**若 Leader 要求 ±0，测试会偶发红** |
| **19** | **`LarkSdkIsolationTest` 的文件位置未定**：`[BC:1259]` 只给了类名，**未指定模块与目录** | `[BC:1259]`（NF-16） | §12 定稿**建议**放 `:lark-bridge/src/test/kotlin/...`（理由：需遍历全仓文件树；`:lark-gateway` 的 test 源集在 S4/S6 期间有 `failOnNoDiscoveredTests = false` 干扰）；并写明「**若 对应施工者 已放在别处，以其为准**」 | 🔴 **任务 ID:8（对应施工者）已 COMPLETED** ⇒ **该测试已经落地在某处**。**需要立刻核对其实际位置与 §12.2 的白名单是否一致**（尤其附录 B 第 12 条的「三个文件」白名单）。若 对应施工者 按「唯一 import SDK 的文件 = `SdkLarkGateway.kt`」实现，则**本文档定稿的 `LarkCardStreamSink.kt` / `RegisterAppFlow.kt` 会让该测试变红** ⇒ 这是 **M3-T3 与 M3-T4 之间唯一的硬冲突点，必须在 T3 开工前解决** |

### B.1 上报优先级（本文档的判断，供 Leader 排序）

| 级别 | 条目 | 理由 |
|---|---|---|
| 🔴 **必须在 T3 开工前裁决** | **12、19**（同一件事的两面）、**1** | 12/19 是 M3-T3 与 M3-T4 的**硬冲突**（对应施工者已交付，若白名单不符则 T3 一开工就红）；1 决定 §11.4 第 ① 断言能否写出可绿形态 |
| 🟠 **T3 期间可并行裁决** | **2、3、4、16、17、18** | 都是「断言形态收窄 / 归属回填」类，本文档已给出可开工的临时处置，裁决只影响测试断言的最终措辞 |
| 🟡 **T3 结束前给出即可** | **5、6、13、14、15** | 5/6 依赖 S6 结果（对应施工者，任务 ID:3 **正在进行**）；13/14 是封装与 PII 取舍；15 归 M6-T13 |
| ⚪ **仅报告，无需裁决** | **7、8、9、10、11** | 已有无副作用的定稿处置，登记是为了让下游施工者知道坑在哪（9/10 涉及 M4-T8，11 涉及 `config-domain-spec.md`） |

### B.2 本文档**未**上报但需要 Leader 知道的两件事

1. 🔴 **本文档无权修改任何既有文件**，因此 §14 的 20 条修正记录**全部只是「记录」**——`重写飞书桥接_ae9f1206.md`（方案）、`behavior-contract.md`、`spike-findings.md` 里的对应表述**仍然是错的**。若不在 M7-T16 统一收口，后续施工者读方案会读到 20 处已失效的表述。**建议 Leader 在 M7-T16 的任务描述里显式列入「按 `gateway-contract.md §14` 回填方案与 BC」**。
2. 🔴 **本文档与 `config-domain-spec.md` 的三个交界面**需确认已由对应施工者（任务 ID:14，已完成）覆盖：① `GatewaySdkSettings` 的字段是否都能从配置文档 schema 推导（尤其 `downloadMaxBytes` / `downloadResourceType`）；② `GatewayErrorCode → BridgeErrorCode` 的映射表（本文档 §6.3.5 只给**建议**，**定义权在 CDS**）；③ `Tuning` 的 33 个常量**确认不入**配置文档 schema（决策 20）。本文档已按「只引用不重复定义」纪律撰写（§0.4），但**未读 CDS 全文**（撰写期间它仍在增长，最后一次观测为 90702 字节），因此**交界面的一致性未经交叉验证**。

### B.3 🔴 交付后核验：附录 B 第 12 / 19 条的**证据化结论**（撤销其 🔴 优先级）

第 12 / 19 条撰写时**未见 M3-T4 的实现**（当时任务 ID:8 标注为「正在运行」），故按最坏情况判为「必须在 T3 开工前裁决」。**现已读到实际落地的 561 行实现**（`composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/LarkSdkIsolationTest.kt`），结论如下：

| 条目 | 原判断 | 🔴 核验后的结论 |
|---|---|---|
| **12**（三个文件 vs 唯一文件） | 「若 对应施工者 按『唯一』实现，则 T3 一开工就红」 | ✅ **冲突不存在**。B-1 用的是**目录前缀**白名单（`:151-157`：`startsWith("lark-gateway/")`），不是文件白名单 ⇒ 网关内 3 个（或任意多个）import SDK 的文件**全部合法**。**无需裁决** |
| **19**（文件位置未定） | 「建议放 `:lark-bridge/src/test`，若 对应施工者 放在别处以其为准」 | ✅ **已确定**：`composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/`。本文档 §12 的原建议**作废**（详见 §12.0）。**无需裁决** |

⇒ 🔴 **B.1 的「必须在 T3 开工前裁决」级别中，第 12 / 19 条已撤销**；该级别**只剩第 1 条**（`BridgeErrorCode` 值集不匹配）。

### B.4 🔴 核验过程中**新发现**的两条（第 20 / 21 条）

| # | 现象 | 证据 | 本文档的处置 | 需 Leader 裁决的问题 |
|---|---|---|---|---|
| **20** | **F 组（D16 静态可变状态守护）与本文档 §4.1 的 `LarkCardStreamSink` 骨架存在真实冲突**：F 组对 lib `src/main/kotlin` 逐文件判定「同文件内 `object`/`companion object` **与** `@Volatile`+`var` 共存 ⇒ fail」；而 §4.1 定稿的 `LarkCardStreamSink.kt` **必然**含 `@Volatile private var segmentIndex` 与 `@Volatile private var open` ⇒ **只要施工者在该文件里写一个 `companion object`（Kotlin 里极常见的习惯），构建立刻红** | `LarkSdkIsolationTest.kt:366-389`（F 组）+ `:552`（`objectDeclRegex = \b(?:companion\s+object\|object)\b`，**会命中 `data object` 与匿名 `object :`**）+ `:403-414`（`findVolatileVarLine`）；本文档 §4.1 字段表 | 🔴 **已在 §4.1 末尾补「三条硬约束 + 第 ④ 条连带约束」**，并在附录 A.2.5 补两条勾选项：① 该文件禁止 `companion object`（常量 ⇒ `Tuning.kt`，工厂 ⇒ `SdkLarkGateway.kt` 私有函数）；② 禁止匿名 `object :`；③ 若必须用 `companion object`，唯一合法替代是把两个 `@Volatile var` 换成 `AtomicInteger`/`AtomicBoolean` 并用 `val ... get() =` 包装（**契约签名不变**）；④ `GatewayModel.kt`/`MessageGateway.kt` 已因 `data object` 使 hasObject=true ⇒ **这两个文件禁止新增 `@Volatile var`** | **无需裁决**（本文档已消解）。**仅报告**：F 组是**行级启发式而非 AST**，这类「跨文件语义无关、同文件字面共存即红」的门禁对施工者是**反直觉**的 ⇒ 建议 Leader 在 T3 任务描述里**显式提示**这一条，否则施工者大概率会踩（踩了之后错误信息会指向 D16，而不是指向「你多写了一个 companion object」） |
| **21** | **`LarkSdkIsolationTest.kt:173` 的 `fail()` 文案仍写「`SdkLarkGateway.kt` 为唯一 import SDK 的文件」**，与决策 1 / 12 定稿的「三个文件」不符 | `LarkSdkIsolationTest.kt:173`（字符串字面量，**非断言逻辑**） | 本文档**无权修改**（`.kt` 源文件，且属 M3-T4 交付物）⇒ 仅在 §12.0 登记；功能上**无害**（断言用的是目录前缀，不是这句话） | **是否授权在 M7-T16 修正该文案**（连带 §14 的 20 条一起回填）？若不修，未来某个施工者读到这条 fail 消息会被误导为「网关只能有一个文件 import SDK」，从而把 cardkit / 扫码逻辑硬塞进 `SdkLarkGateway.kt`，造成一个 2000 行的上帝文件 |

---

## §15 决策补录（设计裁定，2026-09-07）

> 🔴 本节由 M3-T3 施工者按 Leader 对附录 B 21 条的裁定追加，**优先于本文档正文与方案原文**。每条格式：「附录 B 第 N 条 → 裁定 → 理由 → 施工影响」。施工冲突时以本节为准。

### 15.1 Leader 对附录 B 的 16 项裁定

1. **（B-1，🔴 阻塞项）错误码** → 裁定：桥接段在方案 14 个码值基础上**只新增 2 个**——`LarkCardStreamFailed(2041)`（归并 300317/200860/200810/200750/300302 等卡片流式失败）与 `LarkRegisterAppFailed(2050)`。网关侧 `GatewayErrorCode` 保持定稿值集不变；**网关码 → 桥接码的映射允许 N:1**，但映射 `when` 必须穷尽、不得有兜底 `else` 漏项（用 sealed/enum 穷尽性让编译器兜底）。→ 理由：桥接码是 UI/日志面，过细无收益；网关码是工程面，需完整语义。→ 施工影响：**NF-A3 ① 断言收窄为**「每个飞书码有专属 `GatewayErrorCode`；映射到 `BridgeErrorCode` 允许 N:1，映射必须穷尽」。映射函数 `GatewayErrorMapping.kt` 定义在 `:lark-bridge`（M4-T5/T7 承接，**本轮不写**），`:lark-gateway` 只负责产出 `GatewayErrorCode`。`BridgeErrorCode`(lib 侧 16 值) 与 `JErrorCode`(`:core` 2000-2099 段) 的同构与映射归 M4-T5/M6-T12。
2. **（B-2）批准新增 `GatewaySignal.Dropped` 第 7 子类** → 裁定：`Channel(512, DROP_OLDEST)` 的丢弃必须可观测。→ 理由：D18「全部丢弃点加计数器并暴露为指标」。→ 施工影响：丢弃既进 metrics 计数，也发 `Dropped(where, droppedTotal)` 信号让 UI 日志台可见。`GatewaySignal` 共 **7 子类**。
3. **（B-3）批准下载降级方案** → 裁定：SDK 2.8.5 实测无 `InputStream` 流式下载路径（`GetMessageResourceResp extends BaseResponse<ByteArrayOutputStream>`），硬约束。`maxFileSizeBytes` 语义 = **「下载后拒绝阈值 + 文档化的内存上界」**，不是下载前预检（`ResourceDescriptor` 无 size 字段，无法预检）。→ 施工影响：NF-A5 按三条可兑现断言改写。**登记给 M4-T8 的硬性要求：文件下载必须加 `Semaphore(2)` 并发上限**（堆峰值 = 并发下载数 × 单文件大小），该 SDK 硬约束要写进 M7 的 `docs/pages/lark-bridge.md`。
4. **（B-4）批准 NF-A4 收窄** → 裁定：`send()`/`addReaction()`/`removeReactionByEmoji()` 无 `*Sync` 对应物。断言定为「所有 SDK 调用的**发起点**必须在 `net` dispatcher 上（`withContext(netDispatcher)` 包裹）」；`await()` 的续体恢复线程不由我方控制，但续体内**只允许**做非阻塞的映射与 `trySend`，不得阻塞、不得再发起 SDK 调用。
5. **（B-16）测试命名以 `Lark*MapperTest` 为准** → 裁定：`LarkPolicyMapperTest`/`LarkInboundMapperTest`/`LarkOutboundMapperTest`/`LarkErrorMapperTest`（符合仓库「测试名 = 被测类名 + Test」惯例）。方案的 `PolicyMappingTest` 与 `LarkGatewayErrorMappingTest` **均作废**。BC 回填由 M7-T16 统一做，本轮不改 BC。
6. **（B-17）NF-02/NF-03/NF-04 归属改为 M3-T3** → 裁定：`CardThrottleTest`/`SegmentRotationTest`/`Stream10MinRotationTest` **由本轮实现**，不留给 M4-T8。BC 归属回填由 M7-T16 做。
7. **（B-18）NF-15 ① 阈值定稿为双断言** → 裁定：(a) **不存在**名字匹配 SDK ws 线程池命名模式的线程（真泄漏判据）；(b) `ThreadMXBean.threadCount ≤ 基线 + 2`（±2 容差为 shaded OkHttp 连接池懒回收）。→ 施工影响：`GatewayCloseReleasesThreadsTest` 两条都要写。
8. **（B-5）预授权 S6 失败时的第二候选** → 裁定：若真机 `patch` 路径失败，允许切到 cardkit `content` API；两者都失败则降级为「非流式分段 `updateCard`」（放弃打字机）。→ 施工影响：**本轮先按 PASS 分支（`patch`）实现**，但 cardkit 调用收在一个 internal 接口后面，让两条降级路径可在不改契约前提下切换。
9. **（B-6）确认不使用 `uuid(String)` 幂等字段** → 裁定：`sequence` 已是幂等键，`uuid` 语义未经真机验证，误用可能导致飞书侧静默 no-op。→ 施工影响：`Outbound.idempotencyKey` 保留契约字段但 `send()` 不喂 `uuid()`。
10. **（B-13）协议常量的跨模块可见性** → 裁定：`:lark-gateway` 暴露窄公开面 `object GatewayLimits`，**只含 4 个常量**：`perChatRps=4`、`globalRps=40`、`textChunkLimit=3500`、`cardByteBudget=24576`（每个带飞书硬限制出处注释）；其余保持 `internal object Tuning`。→ 施工影响：`RateGovernor`（M4-T7）读 `GatewayLimits`。**本裁定覆盖 §7.2「perChatRps/globalRps 放 :lark-bridge」的字面处置**——两者改放网关公开面 `GatewayLimits`。
11. **（B-14）确认 `RegisterAppResult.userInfo` 不进契约** → 裁定：PII 最小化，扫码人身份不落盘不审计。`LarkCredential` 只含 appId/appSecret/region 三字段。
12. **（B-15）`RegisterApp` 不可中断的处置** → 裁定：`registerApp()` 网关侧跑在**专用单线程 dispatcher**（`Dispatchers.IO.limitedParallelism(1, "bridge-register")`），阻塞约 600s 不可中断，网关**不得**假装可取消。→ 施工影响（宿主侧归 M6-T13）：流程协程挂 `LarkBridgeManager` 作用域而非页面作用域，暴露 `StateFlow<RegisterAppState>`（Idle/WaitingScan(qrUrl,expireAt)/Polling/Succeeded(cred)/Failed(code)/Expired），UI 显示二维码 + `expireSeconds` 倒计时 + 文案「最长约 10 分钟，期间无法取消，可离开本页」。本轮只保证网关侧契约与 dispatcher 正确。
13. **（自评第 2 点）§2.2-§2.5 的 8 个值类型形状予以批准** → 裁定：施工中若发现某形状确实不可实现，做**最小调整**并记进本节追加条目（见 §15.2），不大改。
14. **（B-11）`InboundResource` 只保留 SDK 实际提供的字段** → 裁定：`durationMs` 经 S4 脚手架实测 `ResourceDescriptor.getDurationMs()` 存在，**保留**（type/fileKey/fileName/durationMs 四字段）。
15. **（B-9）登记给 M4-T8 的适配点** → 裁定：BC:1213 旧签名用 `java.io.File`，新契约用 `java.nio.file.Path`，T8 需做 `File → Path` 适配。
16. **（B-20/B-21）守护测试两处修正由本轮承接** → 裁定：见 §15.3。F 组改作用域感知 + `:173` 文案修正，修正理由登记于 §15.3。

### 15.2 施工中发现的矛盾与最小调整（决策 13 授权登记）

- **（M-1）`GatewayErrorCode` 实际值数 = 24，非文档多处声称的「26」** → 证据：§2.5 枚举字面只列 24 值（连接域 4 + 出站域 7 + 频控 1 + 卡片流式 5 + 资源 3 + 扫码 3 + 兜底 1 = 24）；§6.3.4 `isRetryable` 穷尽 `when`（无 `else`）恰好 7 true + 17 false = 24。裁定 1 要求「映射 `when` 必须穷尽、无兜底 `else`」——只有在 24 值时才编译通过。→ **处置：实现 24 值**（§2.5 字面 + §6.3.4 穷尽 when 为权威），「26」登记为文档误 count。NF-A3 的「计数」断言按 24 写。
- **（M-2）`contentJsonOf` 的序列化归属** → 矛盾：§6.2.1 note 说序列化走 `SdkLarkGateway` 的 `JsonCodec`（mapper 不得 import Gson）；但 §11.3 要求 `LarkOutboundMapperTest` **不加载 SDK** 即可测 `contentJsonOf` 转义往返。→ **处置：`LarkOutboundMapper` 自带纯 Kotlin 最小 JSON 编码器**（`internal fun encodeJson(Any?): String`，处理 Map/List/String/Number/Boolean/null + RFC 转义），满足「mapper 零 SDK」+「可脱 SDK 单测」。`SdkLarkGateway` 喂 cardkit 的 `Map` 仍可用 SDK 传递的 Gson，但 `contentJsonOf` 走本编码器。
- **（M-3）`from` vs `fromMessage`** → §3.4 正文用 `LarkInboundMapper.from()`，§6.1.2 定稿 `fromMessage()`。→ **处置：以 §6.1.2 为准用 `fromMessage`/`fromCardAction`**。
- **（M-4）`toCardJson` 签名** → §6.2.1 `toCardJson(spec)` vs §4.2 调用 `toCardJson(spec, elementId=...)`。→ **处置：`toCardJson(spec: CardSpec, elementId: String = Tuning.streamElementId)`**，默认参数兼容两种调用。
- **（M-5）`allowedFileDirs` 的来源** → §11.1 ⑥ 断言 `settings.allowedFileDirs == policy.allowedFileDirs`，但 `LarkPolicy`（§5.1，5 字段）**无** `allowedFileDirs`。→ **处置：`LarkPolicyMapper.toSettings(policy)` 产出的 `allowedFileDirs = emptyList()`**（引擎在 M4-T8 经 `settings.copy(allowedFileDirs=...)` 注入 workspace 根）。`LarkPolicyMapperTest` 断言改为「`toSettings` 默认 `allowedFileDirs` 为空，且 `requireMention` 跟随 policy」。
- **（M-6）`downloadSingleFlight` 类型** → §7.4 表为 `Boolean=true`，§5.1 `GatewaySdkSettings` 为 `Int=2`。→ **处置：以 `GatewaySdkSettings.downloadSingleFlight: Int = 2` 为权威**（裁定 3 要求 `Semaphore(2)` 并发上限，Int 才是许可数）。`Tuning` 不再单列该常量。
- **（M-7）`DmMode` 枚举** → §2.1 文件表提「LarkPolicy.kt 含 `LarkPolicy + DmMode`」，但 §5.1 定稿 `dmMode: String`（"open"/"disabled"/"allowlist"）。→ **处置：用 `String`，不引入 `DmMode` 枚举**（§5.1 为更具体的后出定稿）。
- **（M-8）`streaming_config` JSON 形状** → §6.2.2 定稿 `print_frequency_ms=30 / print_step=1 / print_strategy="fast"`；S6 脚手架用实验值 `print_frequency_ms=Map{default:70..} / step=5 / strategy="fast"` 且自标「待真机核对」。→ **处置：生产代码按 §6.2.2**（spec 权威），S6 实验值不进生产；待 S6 真机回填后再议。
- **（M-9）`Tuning` 常量数** → 任务书与附录 A 称「33 个」，但裁定 10 把 4 个移入公开 `GatewayLimits`、§7.2/§7.4 把 `perChatRps/globalRps/downloadMaxBytes/downloadSingleFlight` 移出网关 `Tuning`。→ **处置：`GatewayLimits` 4 公开常量 + `Tuning` internal 常量（卡片流式域 6 + 频控域 3 + 出站/SDK 配置域 10 + 连接生命周期域 7 = 26）**，逐个带飞书硬限制出处注释。不盲目凑「33」字面。🔴 交付实测确认：4 公开 + 26 internal = **30** 常量（非 33）。
- **（M-10）§3.9/§6.3 引用的符号或语义与实现不符（两处）** →
  (a) `LarkOutboundMapper.toPayload`（§3.9 引用）不存在 ⇒ `SdkLarkGateway.send()` 直接按 `out.kind` 构造 `SendInput.text/markdown/card`（card 走 `toCardJson` 产出的 `Map`，显式 cast 为 `Map<String,Any>`，值恒非空安全）。
  (b) 连接期「缺 URL / 配置字段」由 SDK 抛 `IllegalArgumentException`（message 如 `"url is blank"`），而 §6.3.2 line 344 明确「`ConnectRetryable` = 网络抖动 / 缺 URL 字段 / 5xx」、§11.4 ⑥ 断言其可重试 ⇒ `LarkErrorMapper.fromThrowableName` 增 `IllegalArgumentException → ConnectRetryable` 分支（原实现落 `else → Unknown`，与规格冲突）。
- **（M-11）挂起 / 异常 / 测试脚本形态（三处）** →
  (a) §3.9/§3.10 引用的 `LarkErrorMapper.fromThrowable(4 元组)` 不存在 ⇒ `SdkLarkGateway` 内置私有 `mapThrowable(t)`：`is LarkChannelException → fromLarkChannelCode(t.code)`，否则 `fromThrowableName(t.javaClass.name, t.message)`（不把 SDK 异常类型 import 进 mapper，§6.3.1 纪律）。
  (b) `CompletableFuture.await()` 是挂起调用，不能放进非挂起的 `runCatching` ⇒ `send()`/`setReaction()` 用 `try/catch`。
  (c) `CardThrottleTest` §11.5 ① 的字面脚本自相矛盾：`repeat(1000){append;advanceTimeBy(1)}` **再** `advanceTimeBy(1000)` = 2000ms 虚拟时间，按其自身阈值「4 = 1000ms/250ms」会产出约 8 次 patch，与 `≤4` 冲突 ⇒ 定稿只保留「1000 次 append 跨越 1000ms」一段（正是「1000 append/秒」语义），断言 `patchCount ≤ 4`。
- **（M-12）`LarkInboundMapper.toRejectedSignal` 未定义** → `reject` 事件信号在 `registerHandlers` 内联构造（`RejectEvent` 4 getter：reason/messageId/chatId/senderId → `GatewaySignal.Rejected`）。reject 是低频连接态事件，允许在回调内改状态机 + trySend（§3.4），不受 3 秒铁律的高频约束。
- **（M-13）cardkit 四步收在 internal `CardStreamPort` seam 后** → 裁定 8/B-5 要求三条降级路径（`patch`→cardkit `content`→非流式 `updateCard`）可在不改契约下切换；生产实现 `SdkCardStreamPort`（`SdkLarkGateway.kt` 顶层 internal 类）把 `BaseResponse` 翻译成纯 `CardCallOutcome`，`LarkCardStreamSink` 只依赖 `CardStreamPort` ⇒ sink 可脱 SDK 单测（§11.5-§11.7 用 `FakeCardStreamPort` + 虚拟时间）。
- **（M-14）`connect()` 合并 settings 与 policy** → 把「引擎注入的 `settings`」与「本次 `policy` 的 5 字段」经 `settings.copy(groupAllowlist/dmMode/dmAllowlist/requireMention/respondToMentionAll = policy.*)` 合并后再翻译成 SDK 选项，避免直接用 policy 覆盖丢失引擎注入的 `allowedFileDirs` 等非 policy 字段。
- **（M-15）旧段收尾补最后一次 patch** → 分段轮换时先对旧段补一次 patch（把 `fullText` 里尚未刷出的尾部 flush），再 `settings` 结束旧段流式，避免尾部字符丢失；新段用新 `cardId`（不复用，R20）。
- **（M-16）DROP_OLDEST 丢弃可观测** → `Channel(512, DROP_OLDEST)` 的 `trySend` 恒成功（挤掉最旧一条），无法从返回值判丢弃 ⇒ 用 `AtomicInteger inboundOccupancy` 占用计数推断：`getAndIncrement()` 前值 ≥ 容量 ⇒ 本次挤掉最旧 ⇒ 净占用回落 + `inboundDropTotal` 计数 + 发 `GatewaySignal.Dropped("inbound", total)`。全程 O(1) 非阻塞无锁（满足决策 9 回调纪律）。
- **（M-17）signal 通道自身丢弃计数归 M4 metrics** → `signalChannel` 溢出只累加计数器，不再递归发 `Dropped`（避免 `Dropped` 自身触发 `Dropped` 的无限递归）；metrics 暴露归 M4。
- **（M-18，原代码注释 `M-item`）`isSsrfGuardEnabled` 真实属性名** → §3.3 写 `ssrfGuardEnabled` 是 BUG（`OutboundConfig` 无该合成属性 ⇒ 编译失败）；真实 Kotlin 属性名带 `is` 前缀 `isSsrfGuardEnabled`（同族 `isChatQueueEnabled`，决策 4）。
- **（M-19，原代码注释 `M-item`）`actionValue` 保真度待 S4** → `CardActionEvent.getActionValue()` 是 `Map<String,Any>`，record 字段是 `String?`；决策 9 禁回调内 JSON 序列化 ⇒ `capture()` 用 `toString()`（µs 级），下游（M4-T8 card action executor）自行解析。结构化保真度待 S4 真机回填。
- **（M-20，🔴 本轮测试新增发现）grep 型守护断言必须剥离注释与字符串字面量** → 现象：`Handler3SecondTest`/`GatewayDispatcherTest`/`GatewayCloseReleasesThreadsTest` 的「禁止出现 X」断言若直接对生产源 `readText()` grep 会**假红**，因为命中了注释/日志文案里对 X 的**提及**而非真实使用：`SdkLarkGateway.kt` 抬头注释「本文件零 `Dispatchers.IO` / 零 `limitedParallelism`」、`close()` 内 `log.error` 文案「依赖宿主 `exitProcess` 兜底」、`capture()` KDoc「跳过 `getRaw()`」、`registerHandlers` 注释「不 `launch` / 不加锁」。→ 处置：测试基础设施 `CardStreamTestSupport.kt` 提供 `stripCommentsAndStrings()`（剥离行/嵌套块注释 + `"..."`/`"""..."""` 字符串），**所有「禁止出现 X」断言改用归一化源**；「必须出现 X」的存在性断言仍用原始源（X 常是含字符串字面量的代码，如 `writeFile(tmp.toString())`、`getDeclaredField("executor")`）。做法对齐 `LarkSdkIsolationTest.normalizeKotlinSource`（M3-T4 已用同一手法解决 `LogbackHelper.kt` 的 `getLogger("com.lark.oapi")` 字符串误报）。→ 影响：仅测试内部实现，不改任何生产语义。

### 15.3 守护测试 F 组修正理由（裁定 16 / B-20 / B-21）

- **F 组（D16 静态全局可变状态守护）原为行级启发式**，两类误报：① `objectDeclRegex` 词边界匹配会命中 `data object` 与匿名 `object :`；② 判定「同文件内 `object`/`companion object` 与 `@Volatile`+`var` 共存即 fail」，而 D16 要禁的是**声明在 object/companion 体内**的静态可变状态。`LarkCardStreamSink.kt` 必含类级 `@Volatile private var segmentIndex/open`（§4.1），按 Kotlin 习惯写 `companion object` 即误红，且错误信息指向 D16 而非真因，对施工者反直觉。
- **修正 1（作用域感知）**：F 组改用花括号配对计数，只在 `object X { … }` / `companion object { … }` **体内**出现 `@Volatile` + `var` 时才 fail。类级 `@Volatile var`、`data object`、匿名 `object :` 均**不违规**；顶层 `@Volatile var` 仍违规（保留）。
- **修正 2（`:173` 文案）**：「`SdkLarkGateway.kt` 为唯一 import SDK 的文件」已与事实不符（裁定后网关内多文件合法 import SDK），改为「SDK 只允许出现在 `:lark-gateway` 模块内（B-1 用目录前缀白名单，模块内文件数不限）；其余模块需能力请经纯 Kotlin 网关契约间接使用」。
- **不削弱**：A~E 组断言语义与其余 8 个测试方法一律不动；修正后以 `NegativeProbe.kt` 做负向/正向验证并删除，9 个测试方法仍全绿。

### 15.4 与 `config-domain-spec.md` 的三个交界面核对结论（第 3 步收口）

- **① `GatewaySdkSettings` 字段可否由配置 schema 推导**：24 字段中，`requireMention` ← `access.groupRequireMention`（可推导）；`allowedFileDirs` ← 引擎注入 workspace 根 + `fileMessage.extraRoots`（**不可由 connection/access schema 直接推导，由 M4-T8 的 files handler 装配时注入**）；`downloadMaxBytes` ← `fileMessage.maxFileSizeBytes`（可推导，默认 100MB）；`downloadResourceType="file"`（**硬编码，非配置项**）；其余 20 字段全部硬编码常量（`chatQueueEnabled=false`/`retryMaxAttempts=1`/`streamThrottle*`/`textChunkLimit`/dedup*/stale*/ssrf* 等），**一个都不进配置 schema**（决策 20 + 已否决「把 SDK 调优旋钮暴露进配置」）。结论：交界面一致，无缺口。
- **② `GatewayErrorCode → BridgeErrorCode` 映射表定义权**：裁定 1 已定——定义在 `:lark-bridge`（M4-T5/T7），`:lark-gateway` 只产出 `GatewayErrorCode`（24 值），**本轮不写映射函数**。§6.3.5 的建议对接表仅供参考，CDS §12.1 为码值权威。结论：定义权清晰，无循环依赖（`:lark-bridge --api--> :lark-gateway` 单向）。
- **③ `Tuning` 常量确认不进配置 schema**：`GatewayLimits` 4 公开常量 + `Tuning` 26 internal 常量，**全部不入** CDS 的 35 个 key（决策 20）。唯一跟随用户配置的是 `requireMention`（HOT）与 `allowedFileDirs`（需重连），二者在 `GatewaySdkSettings` 而非 `Tuning`。结论：一致。

## §16 S6 真机实测补录（2026-09-08）

> 🔴 本节是 S6 真机实测（真机环境验证，凭据与测试数据不入库）结论的**唯一权威补录**，由真机实测执行者回填。
> 证据日志（gitignored `build/` 下，只读）：`build/sdk-probe/probe-card.log`、`build/sdk-probe/probe-patch.log`、`build/sdk-probe/run2threads.txt`、`build/s6-ratelimit.log`、`build/s6p-fast50.log`、`build/s6p-throttle250.log`、`build/s6p-overflow.log`、`build/s6p-summary.txt`。
> 🔴 本节**只追加**，不改 §1–§15 正文；§13 待确认清单对应条目的终态见 §16.8（原文保留）。
> **S6 核心裁定：`LarkCardStreamSink` 不降级**——cardkit patch 流式方案真机全链路 code=0（createCard → interactive 发送 → 同卡 100 次 patch → settings 结束），R7 第①层防护（弃用消息编辑改 CardKit）+ 250ms 节流 + 24576 预算 + 主动分段轮换**全部实测有效**（详见 §16.7）。

### 16.1 四个真机坑（现象 → 飞书错误码与 msg 原文 → 根因 → 正确写法）

#### 坑 1：发送卡片实体的 `msgType` 必须是 `"interactive"`，不能是 `"card"`
- **铁证**（`probe-card.log`）：
  - `[A msgType=card card_id引用] http=400 code=230001 success=false msg="Your request contains an invalid request parameter", ext="invalid msg_type: card" messageId=<null>`
  - `[B msgType=interactive card_id引用] http=200 code=0 success=true msg=success messageId=om_x100***`
  - `[C msgType=interactive 内联 cardJson] http=200 code=0 success=true`
- **根因**：飞书 `im.message.create` 的 `msg_type` 枚举无 `"card"`；发送 card_id 引用的卡片实体，`msg_type` 必须是 `"interactive"`（互动卡片），卡片类型信息在 `content={"type":"card","data":{"card_id":…}}` 里表达。
- **正确写法**：`im.message.create(receiveIdType="chat_id", msgType="interactive", content={"type":"card","data":{"card_id":<X>}})` → code=0。
- 🔴 **附带结论**：SDK 自带的 `OutboundSender.sendCardByReference` 走的就是 `msgType="card"` 的错误路径，**且它丢弃返回值所以静默失败**——反证 M3 裁定「自建 cardkit 调用 + 逐次检查响应码」（决策 1 / §4.9）的正确性。
- **落地位点**：`SdkLarkGateway.kt` 的 `SdkCardStreamPort.sendCardByReference`（`.msgType("interactive")`）；`LarkOutboundMapper.msgTypeOf(CARD/MARKDOWN)="interactive"`（单一真源，§6.2.4）。**防回归网**：`CardStreamShapeTest`（grep 生产源断言含 `.msgType("interactive")` 且不含 `.msgType("card")`）+ `LarkOutboundMapperTest.msgTypeOf and contentJsonOf produce the right literals`。

#### 坑 2：`cardkit.cardElement.patch` 的 `partial_element` 绝不能含 `"tag"` 字段
- **四组对照铁证**（`probe-patch.log`）：

  | 组 | partial_element | seq | code | msg |
  |---|---|---|---|---|
  | P1 | `{tag, element_id, content}` | 1 | **300312 ✗** | `ErrMsg: tag cannot be updated;` |
  | P2 | `{element_id, content}` | 2 | **0 ✓** | success（elapsed 278ms） |
  | P3 | `{content}` | 3 | **0 ✓** | success（elapsed 241ms） |
  | P4 | `{tag, content}` | 4 | **300312 ✗** | `ErrMsg: tag cannot be updated;` |

  ⇒ **只要带 tag 就失败**（P1/P4 全灭 100/100），与 element_id 是否存在无关。
- **根因**：`cardElement.patch` 是局部更新，`tag`（元素类型）是不可变属性，飞书拒绝更新它（300312）。
- **正确写法**：`partial_element = {element_id:"stream_md", content:<累积快照>}`（P2 形态，保留 element_id 更明确）。
- **落地位点**：`LarkOutboundMapper.toPartialElementJson`（键集合精确 `{element_id, content}`）。**防回归网**：`LarkOutboundMapperTest.toPartialElementJson is P2 form without tag`（`assertFalse(containsKey("tag"))` + `assertEquals(setOf("element_id","content"), keys)`）+ `CardStreamShapeTest.patchElement partial never contains tag key`（sink 层实测 partial JSON 串无 `"tag"`）。

#### 坑 3：首版不启用 `streaming_config`（打字机动画）
- **铁证**：`step:5` → `11311 printStep is nil`；改 `print_step:5`（标量）→ `10002 failed to unmarshal PrintStepConfig, type float64` ⇒ `print_step` 需要 **PrintStepConfig 对象**而非标量，官方结构未核对清楚。
- **裁定**：**首版不启用 `streaming_config`**。服务端 250ms 节流已实现平滑刷新（§16.3），打字机动画非流式必需。待官方 PrintStepConfig 结构核对后再启用（**非阻塞**）。
- **落地位点**：`LarkOutboundMapper.toCardJson` 的 `config` 删除整个 `streaming_config` 键（键集合 = `{streaming_mode, update_multi, summary}`）。**防回归网**：`LarkOutboundMapperTest.toCardJson produces schema config header body and no streaming_config` + `CardStreamShapeTest.created card json has schema config body and no streaming_config`（`assertFalse("streaming_config" in card)`）。
- 🔴 **表面矛盾登记**（只报告不裁定）：`build/s6p-throttle250.log` 第 6 行标注「实验组：update_multi + streaming_config」且 20 次 patch 全 code=0。判断：触发 11311/10002 的是**独立的 streaming_config 结构探测实验**（`step`/`print_step` 标量形态），不在 `build/` 现存日志内；throttle250 若确实带 streaming_config 却 code=0，可能其形态恰好合法或该实验组未真正启用。**任务裁定「首版不启用 streaming_config」为权威**，遵照执行。

#### 坑 4：分段预算必须基于「累计总量」而非「当前段长度」
- **铁证**（`build/s6p-summary.txt`：`overflow EXIT=143`；143=128+15=SIGTERM=pkill）：脚手架 `expOverflow` 用 `while(sb<40KB)` + 每段 `sb.setLength(0)` ⇒ 清零后条件永真 ⇒ **无限循环刷屏**（`s6p-overflow.log` 跑到段 17 被手动 pkill）。
- **根因**：判定条件绑定「当前段缓冲长度」，缓冲在每段轮换后被清零 ⇒ 跨段累计判定失效、循环不终止。
- **核对结论**：现有 `LarkCardStreamSink` 实现**正确无需改**——主循环 `while (isActive && open)`（非字节绑定），`budgetHit = snap.text.toByteArray(UTF_8).size >= 24576`，`rotateSegment` 后 `slot.set(Snapshot(initialText="", …))` 每段重置为空 ⇒ `budgetHit` 下次不成立 ⇒ 无无限循环（per-segment 检查等价于「累计跨过 N×预算」）。
- **防回归网**（补测试钉死反例）：`SegmentRotationTest.forty KB rotation terminates and does not loop forever`——喂 40KB + 推进 400×25ms 虚拟时间，断言 `createCount` 封顶 == 2、`segmentIndex==2`、close 后 `isOpen==false` 且 `job.isCompleted`（循环必然终止）。

### 16.2 卡片 JSON 结构定稿（实测 code=0 的形状，逐键）
```
schema = "2.0"
header.title = {tag:"plain_text", content:<标题>}
config = { streaming_mode: true, update_multi: true, summary: {content:"[Generating...]"} }   ← 🔴 不设 streaming_config（坑 3）
body.elements = [ {tag:"markdown", element_id:"stream_md", content:<初始文本>} ]
发送：im.message.create(receiveIdType="chat_id", msgType="interactive", content={"type":"card","data":{"card_id":…}})   ← 🔴 msgType=interactive（坑 1）
更新：cardkit.cardElement.patch(cardId, elementId="stream_md", partial_element={element_id, content}, sequence++)       ← 🔴 partial 绝不含 tag（坑 2）
结束：cardkit.card.settings(cardId, config.streaming_mode=false, sequence++)
```
- 🔴 **`update_multi=true` 必设**（多次 patch 同一元素的前提）。
- 🔴 **sequence 自持 per-stream 严格递增**：实测 1→100 全接受、**零 `300317`**（`s6p-fast50.log`）⇒ M3 裁定（每 sink 一个 `Int`、取消 R8 的 LRU(256)）**被真机确认正确**，不改回去。
- **防回归网**：`CardStreamShapeTest.sendCardByReference uses chat_id and card reference content`（receiveIdType=="chat_id" + content 精确等于 `{"type":"card","data":{"card_id":"card_1"}}`）、`…created card json…`（schema/streaming_mode/update_multi/无 streaming_config/body.elements[0] 形状）、`…sequence starts at one and is per stream independent`（两 sink 各 [1,2,3] 互不干扰）、`…flushTerminal finishes settings with streaming mode false and increments sequence`。

### 16.3 「无需改动」项的实测确认清单（核对后逐条确认，未瞎改）
| 项 | 实测铁证 | 结论 |
|---|---|---|
| **24576 字节预算保守可用** | `s6p-overflow.log`：patch 到 **24864 bytes 仍 code=0**（30KB 上限=30720）；段 1–14 每段 patch 到 24864 全 code=0 | ✅ 无需改（`GatewayLimits.cardByteBudget=24576`） |
| **主动分段轮换有效** | 每段**新建卡片实体**（cardId 递增 `7682845***`→`7682846***`），全程**无 `200860`** | ✅ R20「每次流式会话新建实体、绝不复用 card_id」被证实正确 |
| **250ms 节流完全足够** | `s6p-throttle250.log`：19 次节流 patch(seq1–19) + 终态 patch(seq20) + finishStream **全 code=0，零业务错误码**，内容完整、终态正确 | ✅ 无需改（`Tuning.streamThrottleMs=250`）；`CardThrottleTest`（6 tests）已覆盖双条件 |
| **未出现的错误码** | 全程未出现 `300317`(sequence 冲突) / `200860`(30KB) / `200750`(卡过期) / `300302`(update_multi) / `200810`(流式期回传互斥) / `429` / `11232` | ✅ 现有 `check()` 分派分支均为防御性，未触发 |

### 16.4 🔴 `fast50` 零错误的正确解读（防后人误读把节流调快）
- **实测**（`s6p-fast50.log`）：50ms 间隔、故意超「10 次/秒」上限，**100/100 patch code=0，零错误**，seq1→100 全接受，无 `300317`。
- 🔴 **这不是因为飞书不限流**：单 patch 网络往返实测 **213–374ms**（min 213@seq54，max 374@seq62）+ sleep 50ms ⇒ **同步串行实际仅约 3/s，根本达不到「50ms 超频」**，是**同步调用天然节流**。
- **结论**：**不得**据此把节流调快到 50ms。250ms 节流仍是定稿值；`fast50` 的零错误只证明「同步串行不会触发限流」，不证明「50ms 间隔安全」。

### 16.5 🔴 `x-ogw-ratelimit-reset` 头不存在 ⇒ RateGovernor 固定窗口退避
- **实测**（`s6-ratelimit.log`）：30 条文本 @约 2.3QPS（elapsed 409–734ms），`x-ogw-ratelimit-reset` **全程缺失**（日志第 44 行 `x-ogw-ratelimit-reset 是否出现过 = false`），零 429、零 11232 ⇒ 印证 M1「SDK 完全不读该头」。
- **处置**：`RateGovernor.notifyRateLimited(chatId, resetHeaderSeconds: Int?)` **保留参数与「有头按头值退避」分支**（防御性，若飞书未来加回该头仍能用），但**固定窗口阶梯退避（1s→2s→4s→…上限 30s）是实际生效路径**。已在 `RateGovernor.kt` KDoc 与 `Tuning.rateLimitResetHeader` 注释、`SdkLarkGateway.findRateLimitReset` KDoc 写明「2026-09-08 真机实测：headerSeen=false」。
- 🔴 **文档表述清理**：§13 第 4 条、§7.2、§11.8 中任何「读该头退避」的表述均以本节为准——该头**已确认不存在**，退避走固定窗口。防回归网：`RateGovernorTest.S6 实测头缺失 headerSeen false 时走固定窗口退避`（断言 resetHeaderSeconds=null 走 1s→2s→4s→8s）+ 既有 `固定退避阶梯…` / `reset 头存在则按头值精确退避…`。

### 16.6 线程收口实测数据 + 🔴 `close()` 必须配合显式 `exitProcess` 契约
- **反射 shutdown 实测有效**（`run2threads.txt`）：`wsClient.javaClass.getDeclaredField("executor") → setAccessible(true) → (ExecutorService).shutdownNow()`。
  - disconnect 后：`threadCount=26 / 非 daemon=17`（OkHttp Dispatcher + main + `pool-1-thread-1..15` 共 15 个）；
  - 反射 shutdown 后：`threadCount=11 / **非 daemon=2**`（OkHttp Dispatcher + main），`pool-N-thread-M=[]`，反射成功=true，**回落 15 个**（多次实测回落量 15–18）。
- 🔴 **但 OkHttp Dispatcher 仍是非 daemon** ⇒ 残留 **2 个非 daemon 线程**（OkHttp Dispatcher + main）：**`close()` 单靠反射 shutdown 不能让 JVM 自然退出，必须配合显式 `exitProcess`**。
- 🔴 **给 M5-T10（CLI `serve`）与 M6-T12（宿主 `onAppExit()`）的头号契约**：进程退出**必须由宿主显式 `exitProcess(0)`**（决策 13②）。网关内**绝不** `exitProcess` / `System.exit` / `Runtime.halt`（由 `GatewayCloseReleasesThreadsTest.gateway main sources contain no process level exit` grep 钉死）。已写入 `SdkLarkGateway.close()` 的 KDoc。
- **阈值口径**：`GatewayCloseReleasesThreadsTest` / `StartStopLeakTest` 的线程阈值**必须容忍残留 2 个非 daemon 线程，不能要求归零**。防回归网：`GatewayCloseReleasesThreadsTest.close KDoc records real device thread numbers and exitProcess contract`（grep 锁定 KDoc 含 26/17→11/2 + exitProcess 契约）。实时线程数值断言仍归 T11 真机（本轮无凭据不连真机）。
- ⚠️ 说明：`StartStopLeakTest` 位于 `:lark-bridge/…/exec/`，测的是**子进程**泄漏（liveProcesses==0），非线程；本节仅登记其对「残留 2 非 daemon」阈值的口径要求，实际线程测试是 `:lark-gateway` 的 `GatewayCloseReleasesThreadsTest`。

### 16.7 S6 核心裁定：`LarkCardStreamSink` 不降级
- cardkit patch 流式方案真机**全链路 code=0**：`createCard`（type=card_json）→ `im.message.create`（interactive 发送 card_id 引用）→ 同卡 **100 次 patch**（seq1→100 全接受）→ `card.settings`（streaming_mode=false 结束）。
- 方案 **R7 第①层防护**（弃用消息编辑改 CardKit）、**250ms 节流**、**24576 预算**、**主动分段轮换**（新建实体不复用 card_id）**全部实测有效**。
- ⇒ `LarkCardStreamSink` **不启用任何降级分支**（§4.10 的 patch→content→updateCard 降级链保持为「契约预留、当前不触发」）。

### 16.8 §13「待真机确认清单」逐条状态更新（原文保留，此处给终态）
| §13 # | 假设 | S6 终态 |
|---|---|---|
| 1 | sequence 起始值/递增语义 | ✅ **已确认**：per-sink `Int` 起始 0、每次 patch/settings 前 ++（首个 patch seq==1），1→100 全接受、零 300317（§16.2）。改动位点无需触发 |
| 2 | `update_multi` 与 `streaming_config` 结构 | ✅ **已确认**：`update_multi=true` 必设且有效；🔴 `streaming_config` **首版不启用**（坑 3，11311/10002）。§11.3 ① 断言已同步改为「config 不含 streaming_config」 |
| 3 | rawContentType 各类型取值 | ⏳ **仍待 S4/T11**（属入站映射，非 S6 卡片流式范围） |
| 4 | `x-ogw-ratelimit-reset` 头是否存在 | ✅ **已确认：不存在**（headerSeen=false，§16.5）⇒ RateGovernor 固定窗口退避为实际生效路径 |
| 5 | 11232 是否可观测 | ✅ **本轮未出现**（30 条 @2.3QPS 零 11232）；`check()` 对所有非 0 码记 WARN ⇒ 出现即被记录。长期观测归 T11 |
| 6 | 10 分钟自动关流行为 | ⏳ **仍待 T11**（S6 未跑满 11min；9min 主动轮换 `streamSegmentDeadlineMs=540_000` 为定稿，`Stream10MinRotationTest` 单测覆盖） |
| 7 | 流式期点击按钮 200810 表现 | ✅ **架构级消解确认**（决策 22：流式卡禁放交互组件）；S6 未构造带 button 的流式卡，200810 全程未出现（§16.3） |
| 8 | jlink 裁剪后网络/加密路径 | ⏳ **仍待 T11**（需打包产物真机收发，非 S6 范围） |
| 9 | raw client 下载 100MB 堆峰值 | ⏳ **仍待 S4/T11**（非卡片流式范围） |
| 10 | cardkit `patch` vs `content` 对比 | ✅ **已确认用 `patch`**：`patch` 全链路 code=0（§16.7），`content`（分支 B-1）**无需启用** |
| 11 | `uuid` 幂等字段与 sequence 交互 | ✅ **已确认不填 uuid**：sequence 已是幂等键，100 次 patch 无内容错乱/重复 ⇒ 无需启用 `uuid` |
| 12 | RegisterApp 扫码流可用性 | ⏳ **仍待 S4/T11**（非 S6 范围） |
| 13 | BlockedByPeerInstance 与 SDK 连接互斥 | ⏳ **仍待 T11** |
| 14 | merge_forward 在 handler 线程发 HTTP 耗时 | ⏳ **仍待 S4/T11**（非卡片流式范围） |

> **S6 已确认条目**：#1 #2 #4 #5(本轮未现) #7 #10 #11（共 7 条）。**仍待 S4/T11**：#3 #6 #8 #9 #12 #13 #14（共 7 条，均属入站映射/下载/打包/扫码/互斥，非卡片流式范围）。

### 16.9 🔴 T16 契约计数终态（成员数 12→13、`GatewayErrorCode` 24 值）——正文陈旧字面的权威覆盖

依 T16 修改纪律（`gateway-contract.md` §1-§15 只在修正记录/成员表处动，不改正文断言），本节为**唯一权威终态**，凡与下述冲突的正文旧字面一律以本节为准：

- **`MessageGateway` 成员数 = 13**（原 12 + task 15 新增 `suspend fun resolveSenderName(openId): String? = null`，带默认实现）。§1.3 清点表与 §2.6 接口块已同步为 13。🔴 正文仍以旧「12 个成员」表述的位置（**不逐处改字面，以本节覆盖**）：卷首「本文档不修改契约」段（`:7`）、§1 定稿闸门摘要（`:63`）、§14 假设汇总（`:2428` 的「`MessageGateway` 12 成员」）。这些句子语义为「不改动**原有** 12 成员的形状」，新增第 13 成员是**追加**（带默认实现，不破坏既有实现类），与「不修改契约」不冲突。
- **`GatewayErrorCode` = 24 值**（§15.2 M-1 已裁定，`GatewayModel.kt:181` KDoc 自证「文档多处声称的 26 为误 count」；连接域 4 + 出站域 7 + 频控 1 + 卡片流式 5 + 资源 3 + 扫码 3 + 兜底 1 = 24）。🔴 正文仍写「26 值」的位置（**以本节 + M-1 覆盖，不逐处改字面**）：§11.4 映射说明（`:1745`「保持 26 值完整语义」）、§11.4 `isRetryable` 断言描述（`:2175`「26 个…7 true / 19 false」——实际为 **24 个，7 true / 17 false**）、附录 B 纯类型行（`:2475`）、附录 B 第 1 条（`:2563`）。凡涉及计数断言一律按 **24** 写（NF-A3 计数断言、`isRetryable` 穷尽 `when` 无 `else` ⇒ 只有 24 值才编译通过）。

### 16.10 🔴 真机复验更正脚注（2026-09-08，T11 第五轮）：schema 2.0 事件的 image/file `content` 无 `resources` 包装层

> **本节为纯追加更正脚注，不删改上方任何历史记载正文**（§3.7 `:776`/§16 `:2287`/§14 假设汇总 `:2576-2577`/`:2629`/`:2640` 与 §6.1.3 `:1501`、§6.1.4 `:1515` 关于 `resources`/`ResourceDescriptor` 的记载原样保留）。

**真机实测（2026-09-08，T11 第五轮，13 条真实 `im.message.receive_v1` 事件）**：schema 2.0 事件里，
- **image** 消息的 SDK payload `content` 实测为 `{"image_key":"img_v3_..."}` —— **仅一个 key**；
- **file** 消息的 `content` 实测为 `{"file_key":"file_v3_...","file_name":"..."}` —— **两个 key**；
- 🔴 **两者都没有 `resources` 包装层**。

而 S4 当时（用已删除的 spike 脚手架）报告的是「`resources` 无 `size` 字段」，该记载被写进本文档 §3.7（`:776`「`ResourceDescriptor` 只有 type/fileKey/fileName/durationMs，无 size 字段」）、§16（`:2287`）、§14（`:2576`/`:2577`）等处。

**两者的关系澄清（🔴 不矛盾，是不同层面）**：
- S4 记载的 `resources` / `ResourceDescriptor` 指的是 **SDK `NormalizedMessage.getResources()` 归一化后的字段面**（`List<ResourceDescriptor>`，每个 descriptor 只有 type/fileKey/fileName/durationMs、**无 size**）——这一层结论**仍然正确**，SDK 确实帮我们把 `content` 里的 `image_key`/`file_key` 解析进了 `resources` 列表。
- 真机第五轮说的「无 `resources` 包装层」指的是**原始事件 JSON 的 `content` 字段结构**（`{"image_key":...}` / `{"file_key":...,"file_name":...}`，没有再套一层 `resources`）。
- S4 的 `resources` 记载可能源于不同的事件订阅方式或 SDK 版本的 payload 形态描述口径。

**功能无影响**：我方 `LarkInboundMapper` 已按实测形态正确解析（真机证据：image 消息被正确归一化并走到未命中兜底、file 消息被正确提取 fileKey/fileName 并走到 `FileHandler` 产出 `file_agent_disabled`），故**功能无影响，仅更正文档记载**。`LarkInboundMapper` 兼容性只读核实结论见 `spike-findings.md` 同名更正脚注。
