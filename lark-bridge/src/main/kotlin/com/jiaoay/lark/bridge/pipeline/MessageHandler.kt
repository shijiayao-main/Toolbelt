// ② Admission 与 ③ Execute 共同依赖的 handler 契约。
// weight 决定准入档位，match 决定路由命中。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.config.RouteRule
import java.io.File

/**
 * 准入档位（两级准入的三档）。[LIGHT]/[URGENT] 完全不占许可：
 * `/help` 这类轻量指令不会被长跑 agent 阻塞。
 *
 * | 档位 | 谁 | 许可 | 附加语义 |
 * |---|---|---|---|
 * | [URGENT] | `StopHandler`（`agent.stopCommand`） | **绕过** | 并 cancel 目标 chat 的在途任务 |
 * | [LIGHT] | `SystemHandler`（ping / `/commands`）、`CommandHandler` 的清单分支、`InteractionHandler`（卡片回传 + 确认/拒绝）、`FileHandler`（文件登记 + 回执）、拒绝回复、未命中兜底 | **不占** | 直接 launch |
 * | [HEAVY] | `AgentHandler`、`CommandHandler` 委托 agent 分支 | `Semaphore(maxConcurrentAgents)` | + ChatLane 每 chat 上限 + `queueWaitSeconds` + 繁忙回复合批 |
 */
enum class HandlerWeight { LIGHT, HEAVY, URGENT }

/**
 * 统一处理链的能力单元。
 *
 * 契约纪律：
 * - [match] **必须是纯 CPU 判定**（字符串比较/正则/枚举判断），不得 IO、不得挂起、不得查配置以外的外部状态。
 *   它在 ① intake 单协程里被逐个候选调用 ⇒ 任何阻塞都会拖垮全链路（3 秒铁律）。
 * - [handle] 跑在 ③ Execute 的任务协程（`task` dispatcher）里，被 `withTimeout` 包裹；
 *   🔴 **必须响应取消**（不得吞 `CancellationException`），否则 `ProgramLauncher` 的三段式 kill 不会触发。
 * - [timeoutSeconds] 为 `null` ⇒ 用引擎默认（`TimeoutPolicy`：agent 类取 `runtime.agentTimeoutMinutes`，
 *   其余取 `runtime.taskTimeoutSeconds`）。🔒 handler 声明 `timeoutSeconds=1` 时**覆盖**全局 60。
 */
interface MessageHandler {

    /** 本 handler 服务的 route 种类；[PipelineSnapshot] 据此把它绑到对应 [RouteRule]。 */
    val kind: RouteKind

    /** 准入档位（决定 ② Admission 的分支）——**静态**默认档位，`describe()` 与 UI 路由预览读它。 */
    val weight: HandlerWeight

    /**
     * 每条消息的**动态**准入档位。
     *
     * ## 为什么需要它
     * 一条 [RouteRule] 只绑一个 handler（`HandlerKinds.build` 每 kind 一个工厂、`config.routes` 每 kind 恰一条），
     * 但**同一 handler 依消息内容分档**：`SystemHandler` 对 ping 是 [HandlerWeight.LIGHT]、对停止指令是
     * [HandlerWeight.URGENT]；`CommandHandler` 对清单查询是 LIGHT、对委托 agent 是 HEAVY。静态 [weight]
     * 结构上无法表达「一个 handler 多档」⇒ [weightFor] 是其**唯一可实现形态**。
     *
     * ## 🔴 硬性约束（受 IntakeGuardTest 的函数体级断言管辖——它在 ① intake 单协程上被 [com.jiaoay.lark.bridge.pipeline.Admission.submit] 调用）：
     * - **纯函数**：只读 [ctx] 的 message/route/config，不写任何状态；
     * - **非挂起、O(1)**：体内零 `suspend`/`launch`/`delay`/`withTimeout`；
     * - **零 I/O**：体内零 `Files.`/`Socket`/`Http`/`Json.`；
     * - **零锁**：体内零 `synchronized`/`Mutex`。
     * 🔴 别把它日后写成查表或读盘——那会把 intake 单协程拖成全链路瓶颈（3 秒铁律）。
     *
     * ## 默认实现
     * 默认 `= [weight]`（静态档位）⇒ 不覆写它的 handler（含测试的 `ScriptedHandler`）行为完全不变，
     * 现有全量测试与 `RoutingTest` 的 `describe()` 断言（`reply:a:LIGHT`）零影响。
     */
    fun weightFor(ctx: RequestContext): HandlerWeight = weight

    /** 独立超时（秒）；null ⇒ 引擎默认。 */
    val timeoutSeconds: Long?

    /** 是否命中本条消息（纯 CPU）。[RequestContext.route] 在调用时已定格为候选 route。 */
    fun match(ctx: RequestContext): Boolean

    /** 执行并产出结果。🔴 不得自行发送回复——出站一律经 [RequestContext.reply]（④ Reply 的唯一出口）。 */
    suspend fun handle(ctx: RequestContext): HandlerOutcome
}

/**
 * handler 工厂（构造期 seam）。
 * 「新增一类能力只在此加一行」的机制载体：`HandlerKinds` 的单表把 [RouteKind] 映射到本工厂，
 *    **唯一装配点**只需在表里加一行，不必改管线代码。
 */
fun interface HandlerFactory {
    fun create(route: RouteRule, deps: HandlerDeps): MessageHandler
}

/**
 * handler **构造期**依赖（与 [RequestContext] 的**每消息期**依赖刻意分离：
 * 构造期只放「跨消息稳定」的东西，避免快照重建时把每消息对象误绑进 handler 实例）。
 *
 * @param configProvider 读当前生效配置（HOT 语义：handler 每次执行时读，拿到的是最新值）。
 * @param statusProvider 桥接状态快照 provider。
 *   **晚绑定闭包**（Bridge 注入 `{ bundleRef.get()?.metrics?.latest ?: _status.value }`）：
 *   metrics.latest 才是真正的 1Hz 全量快照——`_status.value` 在两次生命周期跳变之间可能是旧帧。
 *   带默认值 `{ null }` ⇒ 存量测试/Harness/PipelineTestSupport 零改动
 *   （与 `MessageGateway.resolveSenderName` 默认实现同款先例）。null = 状态暂不可知（handler 回退文案）。
 */
data class HandlerDeps(
    val configProvider: () -> BridgeConfig,
    val log: BridgeLog,
    val statusProvider: () -> com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot? = { null },
    /**
     * 桥接数据根 provider——`[产物]` 标记链的敏感判据 1 输入。
     * 不入 [BridgeConfig]，因为它是宿主注入的**位置**而非配置项（lib 侧装配时已知，同 [dataRoot] 先例）；
     * 带默认值 `{ null }` ⇒ 存量测试/Harness 零改动（null = 未知，跳过数据根判据，凭据族判据仍生效）。
     */
    val dataRootProvider: () -> File? = { null },
    /**
     * 机器人回复账本 provider：DispatchPayload 会话作用域
     * 四件之一——fetch_quoted_context 工具的查询面。不入 BridgeConfig（稳定件非配置，
     * 同 [dataRootProvider] 先例）；默认 `{ null }` ⇒ 存量测试/Harness 零改动
     * （null = 上下文取回能力关闭，工具如实告知 LLM）。
     */
    val replyLedgerProvider: () -> com.jiaoay.lark.bridge.files.CardReplyLedger? = { null },
    /**
     * L2 原生能力面**工厂**：按消息现造 [NativeChatSurface]
     * （chatId 绑定在实现闭包里 ⇒ 每消息件，不能装配期单例——见 [NativeChatSurface] KDoc「为什么每消息现造」）。
     * 入参是该消息的 [RequestContext]（工厂本体可据它做「哪些消息可造」的判定，如 chatId 空白短路）。
     * 默认 `null` ⇒ 存量测试/Harness 零改动（null = L2 五工具全不注册，fail-closed）。
     */
    val nativeSurfaceFactory: ((RequestContext) -> NativeChatSurface?)? = null,
    /**
     * L2 写操作确认门**工厂**：按消息现造 [com.jiaoay.lark.bridge.confirm.ToolConfirmGate]
     * （chatId/senderOpenId 绑定 ⇒ 每消息件，同 [nativeSurfaceFactory] 理由）。
     * 默认 `null` ⇒ 存量测试/Harness 零改动（null = 确认链不可用，L2 写操作 fail-closed 拒绝）。
     */
    val confirmGateFactory: ((RequestContext) -> com.jiaoay.lark.bridge.confirm.ToolConfirmGate?)? = null,
)
