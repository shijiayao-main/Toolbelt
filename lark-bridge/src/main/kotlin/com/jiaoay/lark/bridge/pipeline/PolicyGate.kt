// ① Intake 第 4 步——策略门控（群@门控）。
// 🔴 能力「拒绝事件可见性」：拒绝必须产出 GatewaySignal.Rejected(reason) + 计数，不能静默吃掉。
// 🔴 本文件属 intake 零锁清单（IntakeGuardTest 守护）：纯 CPU 判定 + CAS 计数，零锁、零 IO、零挂起——违反即挂死 intake 单消费者协程、全链路停摆。
// 🔴 拒绝诊断走**既有唯一** DEBUG 路径——
//    [onRejected] 由 BridgeAssembler 接到 `log.debug(PolicyGate.rejectDiagnostics(rejected))`；`--verbose` 把桥接侧
//    console 下限降到 DEBUG 后它即浮现。本类不持有并行诊断 seam
//    （保留一个永不接线的 null seam 是维护陷阱，且会造成同一事件两条并行日志路径）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.GatewaySignal
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 策略门控（① Intake 第 4 步，紧跟 sender 准入之后、路由之前）。
 *
 * ## 分工（gateway-contract）：SDK 侧策略**全放行**
 * 网关下推的 `LarkPolicy` 恒为 `groupAllowlist=emptyList()` / `dmMode="open"` / `dmAllowlist=emptyList()`
 * / `requireMention=false`（即 SDK 侧不做任何拒绝）；sender 级 allow/block 由 [AccessControl] 承担，
 * 群@门控由**本类**承担。理由：SDK 的 `PolicyConfig` **无黑名单能力**，把一半策略放 SDK、一半放桥接会造成
 * 「配置改了但行为没变」的不可解释现象。⇒ 拒绝的唯一执行点在桥接侧，可观测性也只有一处。
 *
 * 🔴 `groupRequireMention` **不得**下推给 SDK：SDK `safety.PolicyGate.evaluate()` 的判定颗粒度是
 * **会话级**（只看 chatType + 是否 @机器人，**不区分消息类型**），群文件消息物理上无法带 @ ⇒
 * `requireMention=true` 时 SDK 把它拦在 `"message"` 事件之前，下面针对 `InboundKind.FILE` 的豁免
 * **永远执行不到**（文件从未登记 ⇒ 引用必 miss）。
 * 由单一构造点 `BridgeGateways.policy()` **恒传 false**（显式传，不依赖 SDK 默认值）。
 *
 * ## 判定表（私聊模式 = 全放行）
 * | 条件 | 处置 | 理由 |
 * |---|---|---|
 * | `kind == CARD_ACTION` | **放行** | 卡片回传没有「@机器人」概念；拦下会让确认/裁决按钮全部失效 |
 * | `kind == FILE` | **放行** | 文件消息物理上无法携带 @；豁免仅允许登记（无下载无 agent），执行由引用回复显式发起 |
 * | `kind == AUDIO` | **放行** | 语音消息物理上无法携带 @；豁免只放行进管线（转写/群聊拒绝闸收口在 AudioHandler 内，私聊才转写） |
 * | `chatType == P2P` | **放行** | 私聊语境下要求@自己是荒谬的；sender 级准入已由 [AccessControl] 决定 |
 * | `chatType == GROUP` 且 `groupRequireMention` 且 **未**@机器人/未@所有人 | **拒绝** | 群内闲聊不应触发 agent（成本与噪音） |
 * | `chatType == GROUP` 且 `groupRequireMention` 且 (@机器人 或 @所有人) | **放行** | — |
 * | `chatType == GROUP` 且 `groupRequireMention == false` | **放行** | 用户显式关闭门控 |
 * | 🔴 `chatType == UNKNOWN` | **放行** | 未知会话类型（飞书新增 chatType）不足以判定该拒绝；**绝不误杀真实消息**（与 [StaleFilter] 的 NF-A7 同理） |
 *
 * ## 拒绝事件可见性
 * 拒绝时经 [onRejected] 产出一个 [GatewaySignal.Rejected]，并累加 [rejectedCount]。
 * [onRejected] 必须 **O(1) 非挂起**（它在 intake 单协程里被调用）。生产接线点在 `BridgeAssembler`：
 * `PolicyGate({ cfg.access }) { rejected -> log.debug(PolicyGate.rejectDiagnostics(rejected)) }`——拒绝文本经
 * [rejectDiagnostics] 纯函数渲染（chatId/senderId **已掩码**），`--verbose` 下即浮现。
 *
 * @param configProvider 读当前 `access` 域（HOT：配置热替换后下一条消息立即生效）。
 * @param onRejected 拒绝旁路回调（可为 null ⇒ 只计数不发信号）。必须是本类**最后一个**函数参数：
 *   既有调用点普遍用 trailing-lambda 绑它（如 `PolicyGate({cfg}) { rejected-> }`），参数序变动会让 trailing lambda 错位。
 *   🔴 不得在实现里做 IO / 加锁 / 挂起（它跑在 intake 单协程里，IntakeGuardTest 零锁守护）。
 */
class PolicyGate(
    private val configProvider: () -> AccessConfig,
    private val onRejected: ((GatewaySignal.Rejected) -> Unit)? = null,
    /**
     * 群聊唤起模式真源：读 `agent.groupMode`
     * （mention/all 双枚举，默认 mention 仅@唤起）。**必传**（装配点与
     * 测试基座均具名注入）。
     */
    private val agentGroupModeProvider: () -> String,
) {

    private val rejectedTotal = AtomicLong(0)
    private val passedTotal = AtomicLong(0)
    private val groupGatedTotal = AtomicLong(0)

    /**
     * 最近一次拒绝的原因字面值。
     * 用 [AtomicReference] 而非易变可变字段（@Volatile var 在本仓被守护测试禁用），
     *    与 `ConfigRepository` 的手法一致；跨线程可见性由 CAS 语义保证。
     */
    private val lastReason = AtomicReference<String?>(null)

    /** 累计被本门控拒绝的条数。 */
    val rejectedCount: Long get() = rejectedTotal.get()

    /** 累计放行条数。 */
    val passedCount: Long get() = passedTotal.get()

    /**
     * 累计**群消息进入门控评估**的次数（不论 `groupRequireMention` 开不开、不论放行与否；
     * 私聊与卡片回传在前两个 `return true` 就走了，**不计**）。
     * 排障用：为 0 说明消息全走了私聊/卡片回传分支；非 0 但 [rejectedCount] 为 0 说明门控关着或都@了机器人。
     */
    val groupEvaluatedCount: Long get() = groupGatedTotal.get()

    /** 最近一次拒绝的原因字面值（UI 状态栏展示；null 表示尚未拒绝过）。 */
    val lastRejectionReason: String? get() = lastReason.get()

    /**
     * 判定并计数；拒绝时同时产出 [GatewaySignal.Rejected]。
     * @return true ⇒ 放行（继续走路由）；false ⇒ 拒绝（调用方发 rejectionReply 或直接静默）。
     * 纯 CPU、不挂起、零锁。
     */
    fun test(message: InboundMessage): Boolean {
        val allowed = isAllowed(message)
        if (allowed) {
            passedTotal.incrementAndGet()
            return true
        }
        rejectedTotal.incrementAndGet()
        val reason = REASON_GROUP_REQUIRE_MENTION
        lastReason.set(reason)
        onRejected?.invoke(
            GatewaySignal.Rejected(
                reason = reason,
                messageId = message.messageId,
                chatId = message.chatId,
                senderId = message.senderId,
            ),
        )
        return false
    }

    /** 只做判定不动计数、不发信号（`PolicyGateTest` 的判定表用它，避免计数与信号干扰断言）。 */
    fun isAllowed(message: InboundMessage): Boolean {
        if (message.kind == InboundKind.CARD_ACTION) return true   // 卡片回传一律放行
        // 文件/语音消息豁免：两者物理上无法携带 @机器人（同口径），
        //    若不豁免则群聊里发的文件/语音全被 @ 门控拦下、后续流程整体死路。
        //    文件豁免只允许**登记**（FileHandler 仅登记 + 回执，无下载无 agent）；
        //    语音豁免只放行进管线——AudioHandler 内仍有群聊拒绝闸（群语音不转写，静默留痕），
        //    私聊语音才转写；执行语义收口在单一 handler 内可测，拒绝口径见其 KDoc。
        //    AccessControl 白名单准入仍前置于本门控，安全面不变。
        // 🔴 FILE/AUDIO **无条件**豁免群@门控（不读任何配置）：物理上无法携带 @。
        //    SDK 侧恒全放行 ⇒ 本行是文件/语音消息进管线的唯一依据。
        if (message.kind == InboundKind.FILE || message.kind == InboundKind.AUDIO) return true
        if (message.chatType != ChatType.GROUP) return true        // P2P 与 UNKNOWN 一律放行
        groupGatedTotal.incrementAndGet()
        // 群聊唤起判定唯一真源 = agent.groupMode
        // （"all" = 群聊每条白名单消息都进 agent；其余值含 "mention" = 仅@唤起，默认）。
        // 未知值一律回落仅@唤起（成本闸 fail-safe：宁可少放行不可多烧钱）。
        return agentGroupModeProvider() == GROUP_MODE_ALL || message.mentionedBot || message.mentionAll
    }

    companion object {
        /** 群聊唤起模式「全部回复」的字面值（`agent.groupMode="all"`；其余值含默认 "mention" = 仅@唤起）。 */
        const val GROUP_MODE_ALL: String = "all"

        /**
         * [GatewaySignal.Rejected.reason] 的字面值（「拒绝事件可见性」的唯一 reason 取值；
         * 与网关侧 SDK `RejectEvent.getReason().name` 命名风格一致，便于 UI 单表渲染）。
         */
        const val REASON_GROUP_REQUIRE_MENTION: String = "group_require_mention"

        /**
         * 群@门控拒绝的 DEBUG 诊断文本（纯函数，`RejectDiagnosticsMaskingTest` 直接单测）。
         * 由 `BridgeAssembler` 的 [onRejected] 接线点唯一调用（`log.debug(rejectDiagnostics(rejected))`）。
         * 内容：拒绝原因 + **掩码后的** chatId 与 senderOpenId（[GatewaySignal.Rejected] 二者可空 ⇒ `orEmpty()`）。
         * 隐私硬约束：文本**不得含完整 chatId/openId**（`--verbose` 下这条会真实打印到控制台）。
         */
        internal fun rejectDiagnostics(rejected: GatewaySignal.Rejected): String =
            "策略门控拒绝 reason=${rejected.reason} " +
                "chat=${maskIdentifier(rejected.chatId.orEmpty())} sender=${maskIdentifier(rejected.senderId.orEmpty())}"

        /**
         * openId/chatId 掩码（纯函数；口径同 :lark-gateway `maskOpenId`，跨模块 internal 不可见故本地实现）：
         * 前 6 + `****` + 后 4；长度 <= 10 全掩码（前后 6/4 重叠会还原完整值）。
         * 🔴 桥接侧所有拒绝/准入 DEBUG 文本的标识符掩码统一走本函数（`BridgeAssembler` 群@拒绝、`EventIntake` sender 准入）。
         */
        internal fun maskIdentifier(id: String): String =
            if (id.length <= 10) "*".repeat(id.length)
            else id.take(6) + "****" + id.takeLast(4)
    }
}
