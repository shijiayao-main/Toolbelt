// confirm：确认状态机。
// 含确认超时钳制纯函数（见 companion `clampedConfirmTimeoutMillis`，
//    对应测试 `ConfirmTimeoutClampTest`）。
package com.jiaoay.lark.bridge.confirm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 进行中确认的**只读视图**（驾驶舱「待你确认」区块数据面）。
 *
 * 与 [ConfirmService.ConfirmRequest] 的关系：[ConfirmRequest] 是发起时快照（状态机内部账本形态），
 * 本类是**快照语义的对外投影**（读取瞬间值）——由 [ConfirmService.pendingViews] 一次性映射产出，
 * UI 侧零 lib 对象引用、零挂起（配合 1Hz 状态快照拉取，见 feature 侧 CockpitPanel 接线）。
 *
 * @param requestId 完整确认单 ID（`cf-<seq>-<hex4>`）
 * @param chatId 确认卡目标会话
 * @param senderOpenId 发起者 openId（防替答维度；UI 显示回落 openId 前缀——无 userName 数据源）
 * @param toolName 工具名（确认卡同源）
 * @param inputSummary 入参摘要（服务侧已截断到 [ConfirmService.INPUT_SUMMARY_CHARS]）
 * @param riskLevel 风险等级名（空串 = 未分级）
 * @param createdAt 发起时刻（epoch millis；剩余秒 = createdAt + effectiveTimeoutMillis - now）
 * @param effectiveTimeoutMillis 生效超时毫秒（🔴 = awaitDecision **实际使用**的值，由调用方传
 *   clamped 产物——MCP server 路径与 L2 工具路径各自现算，绝不设默认值防「记账值与生效值脱节」）
 * @param cardMessageId 确认卡已发送则非空（已读回执查询锚点；未发卡/发送失败为 null ⇒ UI 显示「已读 —」）
 */
data class PendingConfirmView(
    val requestId: String,
    val chatId: String,
    val senderOpenId: String,
    val toolName: String,
    val inputSummary: String,
    val riskLevel: String,
    val createdAt: Long,
    val effectiveTimeoutMillis: Long,
    val cardMessageId: String?,
)

/**
 * 运行时确认状态机：agent 高风险工具调用 → 确认请求挂起 → 飞书裁决 → 继续/拒绝。
 *
 * 状态流转：
 * - PENDING → APPROVED（原发送者回复「确认 <id>」）
 * - PENDING → DENIED（原发送者回复「拒绝 <id>」）
 * - PENDING → TIMEOUT（超时，等同 DENIED——agent 收到拒绝自行调整，进程不 kill）
 * - PENDING → CANCELLED（用户停止指令，随停止逻辑 kill 进程）
 *
 * 防替答：裁决校验 chatId + senderOpenId 与发起请求时一致（群聊中他人回复无效）。
 * 确认请求与 agent 进程 1:1：awaitDecision 用 CompletableDeferred 挂起，agent 进程自然阻塞。
 *
 * 本类**无任何静态可变状态**（构造注入，无 companion 持有）；
 * `pending` 是**实例级** `ConcurrentHashMap`，实例随装配点唯一 ⇒ 「停止后旧实例不得继续产生副作用」
 * 的语义由装配唯一性自然保证。
 *
 * 契约：① [ConfirmRequest.riskLevel] 风险等级随请求快照传导（确认卡风险徽标行）；
 * ② [DecideResult.Ok.requestId] 裁决时快照捎带完整请求 ID（已答态「单据：cf-xxxx」短标识对账）。
 * ③ [pendingViews] 只读投影 + [PendingEntry.effectiveTimeoutMillis] 生效超时记账
 * （[newRequest] 第 6 参必传）——驾驶舱「待你确认」区块（工具名/发起者/剩余秒/已读锚点）的数据面。
 *
 * 构造注入 [onTimedOut] 超时回调（默认 `{}` ⇒ 既有构造点零改动）。
 * 超时点在 remove 前快照 [TimedOutConfirm]（request 全量 + cardMessageId 现读）交给回调——装配点
 * 据此把原确认卡 patch 为「已超时拒绝」无按钮态（消灭超时分支的按钮滞留脱节）。回调失败**绝不阻断**
 * TIMEOUT 返回（runCatching 双保险）。
 */
class ConfirmService(
    /**
     * 确认超时回调：超时点在 awaitDecision 协程内被调（suspend，零额外 scope），
     * 载荷是超时瞬间的完整快照。默认 `{}` = 不接线（SyntheticGateway/既有测试构造点零破坏）；
     * 回调抛异常被 runCatching 吞掉
     * （装配闭包内另有只 log 降级——BuiltinHandlers 已答态同款双层），TIMEOUT 返回不受影响。
     */
    private val onTimedOut: suspend (TimedOutConfirm) -> Unit = {},
    /**
     * 已读人数查询口：催办判定点现查确认卡已读状态（null = 能力缺席/未发卡/查询失败
     * ⇒ **fail-safe 静默到期不催不延**——对已读用户刷屏的风险 > 漏催一次）。默认 `{ null }` =
     * 不接线 ⇒ SyntheticGateway/既有测试构造点零破坏、催办链路整体缺席。
     * 装配点闭包内直接经运行中网关 gateway.readReceipts **首页判零**（翻页聚合是 UI 已读数展示需求，
     * 判零看首页即足）；不走 Bridge.messageReadCount 门面——装配闭包（createBundle 内）早于 Bridge
     * 实例构造，够不到门面。
     */
    private val readCountProvider: suspend (messageId: String) -> Int? = { null },
    /**
     * 催办回调：未读确认在窗口末段被调（suspend，跑在 awaitDecision 协程内）——
     * 装配点闭包发轻量文字催办（sink.text）。默认 `{}` = 不接线 ⇒ 催办链路缺席（已读/缺席判定仍在
     * service 层做，回调只负责发出）。每请求**至多 1 次**（PendingEntry.remindedRef CAS 防双发）。
     */
    private val onReminder: suspend (ReminderNotice) -> Unit = {},
) {

    /**
     * 超时瞬间的快照载荷。
     *
     * 不用卡面三参散传 `(cardMessageId, toolName, inputSummary)`——不足：已答卡「单据：cf-xxxx」
     * 行需要 requestId、装配点发卡目标需要 chatId，全部在 request 里——直接捎带完整 request 快照
     * （单据行短标识走 [Companion.shortId] 单源，与 decide 的 DecideResult.Ok 快照捎带同形）。
     *
     * @param request 超时请求的发起时快照（requestId/chatId/toolName/inputSummary/riskLevel 全在内）
     * @param cardMessageId 确认卡 messageId 现读（发卡回填后非空；未发卡/发送失败为 null ⇒ 调用方跳过更新）
     */
    data class TimedOutConfirm(
        val request: ConfirmRequest,
        val cardMessageId: String?,
    )

    /**
     * 确认超时/催办链路的**周期接线束**：三个回调（[onTimedOut] 已超时卡更新 /
     * [readCount] 已读查询 / [onReminder] 文字催办）都需要**运行中周期**的网关件（gateway/rateGovernor/
     * netDispatcher——每周期件），而 [ConfirmService] 是跨 start/stop 的稳定件 ⇒ 装配点用
     * AtomicReference 槽晚绑定：`RuntimeBundle.create` 每周期接线后整束写入，`Bridge.teardownBundle`
     * 清回默认（停止后旧周期不得再产生副作用）。默认束 = 三闭包全空 ⇒ 未运行/测试直造时
     * 超时与催办链路整体缺席。
     */
    data class TimedOutWiring(
        /** 已超时卡更新回调：闭包内按 request.chatId 现造 GatewayReplySink 走 updateExistingCard。 */
        val onTimedOut: suspend (TimedOutConfirm) -> Unit = {},
        /** 已读人数查询（催办判定）：null = 能力缺席 ⇒ fail-safe 静默。 */
        val readCount: suspend (messageId: String) -> Int? = { null },
        /** 文字催办回调：未读确认在催办点被调一次（每请求至多 1 次，service 层 CAS 保证）。 */
        val onReminder: suspend (ReminderNotice) -> Unit = {},
    )

    /**
     * 催办载荷：催办点的快照 + **顺延后的剩余窗口**（催办文案「还剩 N 秒」的数据源——
     * 顺延量按窗口比例在 service 层算，装配闭包拿不到 timeoutMillis，故由 service 层随载荷捎带）。
     *
     * @param request 请求快照（chatId 发催办目标 / requestId 短码单源）
     * @param cardMessageId 卡锚点现读（催办判定已用过，捎带供装配闭包引用锚定等扩展用）
     * @param remainingMillis 顺延段剩余毫秒（= 顺延量；从催办点到最终超时的窗口）
     */
    data class ReminderNotice(
        val request: ConfirmRequest,
        val cardMessageId: String?,
        val remainingMillis: Long,
    )

    /** 确认请求（发起时快照，防替答依据） */
    data class ConfirmRequest(
        val requestId: String,
        val chatId: String,
        val senderOpenId: String,
        val toolName: String,
        /** 工具入参摘要（确认卡片展示用） */
        val inputSummary: String,
        val createdAt: Long,
        /**
         * 风险等级名（确认卡风险徽标行：`**风险**：HIGH（高风险操作，请仔细核对参数）`）。
         * 取 [RiskClassifier.RiskLevel.name] 字符串而非枚举类型：① 默认空串 = 未知/未分级，
         *    data class 默认值让既有构造点（测试/旧调用方）零改动，而枚举 nullable 需要全点显式判空；
         *    ② RiskClassifier 在 confirm 包内同域可用，但**字符串跨协议层**（MCP server → 环回 TCP →
         *    BusMessage → ConfirmRequest）传导，协议载荷本就是 String——用 String 单源同构，不引入
         *    协议序列化特判。渲染端（GatewayConfirmNotifier）只做 isBlank 判定，不解析枚举语义。
         */
        val riskLevel: String = "",
    )

    /** 裁决结果（供确认卡按钮与文字指令共用的裁决语义） */
    enum class Decision { APPROVED, DENIED, TIMEOUT, CANCELLED }

    /** 裁决操作结果（飞书回复语义） */
    sealed class DecideResult {
        /**
         * 裁决成功送达（裁决时快照捎带，非事后查账——裁决成功后 `awaitDecision` 随即注销条目，事后查会竞态扑空）。
         * @param cardMessageId 原确认卡的 messageId（发卡后回填，见 [recordCardMessageId]）。
         *   未回填/未发卡时为 null ⇒ 调用方跳过已答态更新。
         * @param toolName 原确认请求的工具名（已答卡保留原问题上下文——已答卡回执语义）。
         * @param inputSummary 原确认请求的参数摘要（已答卡正文回显）。
         * @param requestId 原确认请求完整 ID（已答态短标识：正文「单据：cf-xxxx（事后对账短标识）」——
         *   cf- 前缀 + [Companion.shortId] 末段 hex4。短标识是末段 hex4 前缀匹配（matchesIdPrefix 语义），
         *   seq 不同 hex4 相同时 `cf-` + shortId 与完整 requestId 不唯一对应——对账用非唯一键，不发明新格式）。
         */
        data class Ok(
            val decision: Decision,
            val cardMessageId: String? = null,
            val toolName: String = "",
            val inputSummary: String = "",
            val requestId: String = "",
        ) : DecideResult()
        /** 短标识无匹配（已裁决/不存在） */
        data object NotFound : DecideResult()
        /** 非原发送者（防替答拦截） */
        data object Forbidden : DecideResult()
    }

    private val pending = ConcurrentHashMap<String, PendingEntry>()
    private val seq = AtomicLong(0)

    /**
     * 在途确认条目：request 快照 + 挂起 deferred + 确认卡 messageId（可回填）。
     *
     * [cardMessageIdRef] 默认 null、由 [recordCardMessageId] 在确认卡**发送成功后**回填
     *    （发卡在 BridgeAssembler 旁路点——见 ReplySinkUniquenessTest 白名单第 2 条；服务只存账，
     *    不自己发卡）。裁决时 InteractionHandler 据此把原卡更新为已答态。
     *    零 @Volatile var——单字段单写点（发卡回填）用 AtomicReference 承载
     *    （files 域「一律 AtomicReference」同款，见 DownloadStoreTest 域纪律组）。
     *
     * [effectiveTimeoutMillisRef] 是 awaitDecision 将使用的生效超时
     * （由调用方传 clamped 产物，不设默认：两个调用点显式传，防「记账值与生效值脱节」）。
     * [pendingViews] 据此算「剩余秒」pill。
     *
     * 🔴 effectiveTimeoutMillis val → [effectiveTimeoutMillisRef] AtomicLong——未读催办的**窗口顺延**
     * （addAndGet）必须同步到记账值，否则驾驶舱「剩余秒」
     * （CockpitPanel: createdAt + effectiveTimeoutMillis - now）会在原到期点提前归零（UI 显示已超时、
     * 实际条目还挂着顺延窗口）——「记账值与生效值同源」契约在顺延场景的延伸（AtomicLong 单字段
     * 可变点 = files 域「零 @Volatile、单写点原子承载」同族纪律）。[remindedRef] 是每请求催办
     * 至多 1 次的 CAS 闸（compareAndSet 防并发双发）。
     */
    private class PendingEntry(
        val request: ConfirmRequest,
        val deferred: CompletableDeferred<Decision>,
        /**
         * awaitDecision 将使用的生效超时毫秒（无默认值——调用方显式传；为可顺延的 AtomicLong：
         * 催办顺延 addAndGet 于此，[pendingViews] 透传 .get()——「剩余秒」随顺延自然续走）。
         */
        val effectiveTimeoutMillisRef: AtomicLong,
        /** 催办已发闸（false → true 单向 CAS——每请求催办至多 1 次，防刷屏）。 */
        val remindedRef: AtomicBoolean = AtomicBoolean(false),
        val cardMessageIdRef: AtomicReference<String?> = AtomicReference(null),
    )

    /**
     * 产生新确认请求（requestId 自动生成，含随机后缀防碰撞）。
     * [riskLevel] 用于确认卡风险徽标行（默认空串 = 未知/未分级 ⇒ 卡上不渲染风险行）。
     * 第 6 参 [effectiveTimeoutMillis]（🔴 **必传、无默认值**——语义 = [awaitDecision]
     * 将使用的生效超时，由调用方传 `clampedConfirmTimeoutMillis(...)` 产物；L2 工具路径在
     * [ToolConfirmGate.requireConfirmation] 显式传，
     * 记账值与生效值同源不脱节）。
     */
    fun newRequest(
        chatId: String,
        senderOpenId: String,
        toolName: String,
        inputSummary: String,
        riskLevel: String = "",
        effectiveTimeoutMillis: Long,
    ): ConfirmRequest {
        val requestId = "cf-${seq.incrementAndGet()}-${(0..0xFFFF).random().toString(16).padStart(4, '0')}"
        val request = ConfirmRequest(
            requestId = requestId,
            chatId = chatId,
            senderOpenId = senderOpenId,
            toolName = toolName,
            inputSummary = inputSummary.take(INPUT_SUMMARY_CHARS),
            createdAt = System.currentTimeMillis(),
            riskLevel = riskLevel,
        )
        pending[requestId] = PendingEntry(request, CompletableDeferred(), AtomicLong(effectiveTimeoutMillis))
        return request
    }

    /**
     * 挂起等待裁决（MCP server 线程调用）。
     * 超时返回 [Decision.TIMEOUT]（等同拒绝，不抛异常）；请求被取消返回 [Decision.CANCELLED]。
     *
     * 超时分支在 remove 前快照 [TimedOutConfirm] 交给 [onTimedOut]（默认 `{}`
     * 零行为；装配点接线后把原确认卡 patch 为已超时拒绝态）。回调失败只吞不抛（runCatching），
     * TIMEOUT 返回不受影响——已超时卡更新是增强面，绝不阻断裁决链路。
     *
     * **二段等待状态机**：第一段等 `timeoutMillis - 提前量`（催办提前点 = 窗口
     * 3/4 处，比例制：小窗口下提前点不早于中点钳制）；第一段到点仍未决且催办闸未用 ⇒ 现查已读
     * （[readCountProvider]）：**未读**（readCount==0）⇒ 经 [onReminder] 发一次文字催办 + 窗口顺延
     * [REMINDER_EXTENSION_FRACTION_DENOMINATOR] 分之一（顺延记账进 [PendingEntry.effectiveTimeoutMillisRef]
     * addAndGet——驾驶舱「剩余秒」随顺延自然续走），再等满顺延段；**已读未决 / 能力缺席（null）**
     * ⇒ 静默到期不催不延（fail-safe：对已读用户刷屏的风险 > 漏催一次）。
     * 🔴 每请求催办至多 1 次（[PendingEntry.remindedRef] CAS——防并发双发与无限续期）。
     * 已决/已答竞态自然短路：deferred 已 complete ⇒ 两段 withTimeoutOrNull 均立即返回裁决，
     * decide/cancelAll 竞态全兼容（催办段只是把挂起点切成两段，不改变任何返回语义）。
     * readCount 抛异常 ⇒ runCatching 折叠 null = 能力缺席语义（fail-safe 静默）。
     */
    suspend fun awaitDecision(request: ConfirmRequest, timeoutMillis: Long): Decision {
        val entry = pending[request.requestId] ?: return Decision.CANCELLED
        // 第一段：原窗口 - 催办提前量（窗口 3/4 处催；钳制不早于中点——小窗口下提前点仍留半窗）。
        val firstLegMillis = (timeoutMillis - timeoutMillis / REMINDER_LEAD_FRACTION_DENOMINATOR)
            .coerceAtLeast(timeoutMillis / 2)
        val firstLeg = withTimeoutOrNull(firstLegMillis) { entry.deferred.await() }
        if (firstLeg != null) {
            pending.remove(request.requestId)
            return firstLeg
        }
        // 催办判定段：未决 + 催办闸未用 ⇒ 查已读决定「催+顺延」或「静默到期」。
        val extensionMillis = timeoutMillis / REMINDER_EXTENSION_FRACTION_DENOMINATOR
        val reminded = entry.remindedRef.compareAndSet(false, true) && run {
            // 快照（催办载荷复用 request 全量 + 卡锚点形态；剩余窗口随载荷捎带——顺延量按窗口比例在此算）。
            val snapshot = TimedOutConfirm(entry.request, entry.cardMessageIdRef.get())
            // null fail-safe：能力缺席/未发卡/查询失败 ⇒ 不催不延（对已读用户刷屏的风险 > 漏催一次）。
            val readCount = snapshot.cardMessageId
                ?.let { runCatching { readCountProvider(it) }.getOrNull() }
            if (readCount == 0) {
                // 🔴 记账同步（「记账值与生效值同源」契约）：顺延必须 addAndGet 进条目记账值，
                //    否则驾驶舱「剩余秒」在原到期点提前归零（UI 显示已超时、实际条目还挂着顺延窗口）。
                entry.effectiveTimeoutMillisRef.addAndGet(extensionMillis)
                runCatching { onReminder(ReminderNotice(snapshot.request, snapshot.cardMessageId, extensionMillis)) }
                true
            } else {
                false
            }
        }
        // 第二段：催办成功 ⇒ 等满顺延段；未催（已读/null/闸已用）⇒ 补齐原窗口剩余段（提前量归位——
        // 总等待时长不变），静默到期。
        val secondLegMillis = if (reminded) extensionMillis else timeoutMillis - firstLegMillis
        val secondLeg = withTimeoutOrNull(secondLegMillis) { entry.deferred.await() }
        val decision = secondLeg ?: firstLeg
        // 超时分支 remove 前快照（条目还持 cardMessageIdRef——先拍快照语义自明）。
        if (decision == null) {
            val snapshot = TimedOutConfirm(entry.request, entry.cardMessageIdRef.get())
            // 🔴 回调失败绝不阻断 TIMEOUT 返回（装配闭包内还有 runCatching 只 log 的降级层——BuiltinHandlers
            //    已答态更新同款双层保险）。
            runCatching { onTimedOut(snapshot) }
        }
        pending.remove(request.requestId)
        return decision ?: Decision.TIMEOUT
    }

    /**
     * 飞书裁决入口：短标识匹配 + chatId/senderOpenId 双重校验（防替答）。
     * 短标识取 requestId 去前缀后的首段（单 chat 串行下同时最多 1 个 PENDING，4 hex 足够）。
     */
    fun decide(idPrefix: String, chatId: String, senderOpenId: String, approved: Boolean): DecideResult {
        val entry = pending.values.firstOrNull { it.request.matchesIdPrefix(idPrefix) }
            ?: return DecideResult.NotFound
        if (entry.request.chatId != chatId || entry.request.senderOpenId != senderOpenId) {
            return DecideResult.Forbidden
        }
        val decision = if (approved) Decision.APPROVED else Decision.DENIED
        entry.deferred.complete(decision)
        return DecideResult.Ok(
            decision = decision,
            cardMessageId = entry.cardMessageIdRef.get(),
            toolName = entry.request.toolName,
            inputSummary = entry.request.inputSummary,
            requestId = entry.request.requestId,
        )
    }

    /** 桥接停止/用户停止指令：取消全部在途确认（等待方收到 CANCELLED，不抛异常） */
    fun cancelAll() {
        pending.values.forEach { it.deferred.complete(Decision.CANCELLED) }
        pending.clear()
    }

    /** 当前在途请求数（测试/观测用） */
    fun pendingCount(): Int = pending.size

    /** 当前全部在途请求快照（测试/观测用） */
    fun pendingRequests(): List<ConfirmRequest> = pending.values.map { it.request }

    /**
     * 当前全部在途确认的**只读视图**（驾驶舱「待你确认」区块数据面）。
     *
     * 快照映射（读取瞬间值）：cardMessageId 从 cardMessageIdRef 现读（发卡回填后非空——已读回执
     * 查询锚点）；effectiveTimeoutMillis 透传条目记账值（awaitDecision 生效值同源）。
     * 🔴 纯内存读、非挂起、恒不抛——供 [com.jiaoay.lark.bridge.Bridge.pendingConfirmations] 窄口
     * 1Hz 快照节奏拉取（挂起网络调用不进采样循环，本成员不触网）。
     */
    fun pendingViews(): List<PendingConfirmView> = pending.values.map { entry ->
        PendingConfirmView(
            requestId = entry.request.requestId,
            chatId = entry.request.chatId,
            senderOpenId = entry.request.senderOpenId,
            toolName = entry.request.toolName,
            inputSummary = entry.request.inputSummary,
            riskLevel = entry.request.riskLevel,
            createdAt = entry.request.createdAt,
            effectiveTimeoutMillis = entry.effectiveTimeoutMillisRef.get(),
            cardMessageId = entry.cardMessageIdRef.get(),
        )
    }

    /**
     * 回填确认卡 messageId（GatewayConfirmNotifier 发卡成功后调用——发卡在装配旁路点，服务只存账）。
     * 未知 requestId 静默忽略（裁决竞态下条目可能已注销——已答态是增强，绝不因此抛）。
     */
    fun recordCardMessageId(requestId: String, messageId: String) {
        pending[requestId]?.cardMessageIdRef?.set(messageId)
    }

    /** 短标识匹配：requestId 末段（hex4）以用户输入开头（用户输入「确认 a3f2」的 a3f2） */
    private fun ConfirmRequest.matchesIdPrefix(prefix: String): Boolean {
        val clean = prefix.removePrefix("cf-").trim()
        if (clean.isBlank()) return false
        val tail = requestId.substringAfterLast('-')
        return tail.startsWith(clean)
    }

    companion object {
        /** 工具入参摘要截断（字符） */
        const val INPUT_SUMMARY_CHARS = 120

        // ── 催办提前点与窗口顺延的比例制常量 ──
        //    比例制：与窗口时长自适应，10s 下限钳制下顺延 5s 仍为正；
        //    催办提前点 = 窗口 3/4 处（第一段 = timeout - timeout/4）；顺延量 = 窗口 1/2。

        /** 催办提前点分母（4 ⇒ 窗口 3/4 处催办——留 1/4 窗口给发起者看到催办后裁决）。 */
        const val REMINDER_LEAD_FRACTION_DENOMINATOR = 4L

        /** 顺延量分母（2 ⇒ 顺延 = 窗口 1/2——10s 窗口顺延 5s；至多顺延一次，防无限续期）。 */
        const val REMINDER_EXTENSION_FRACTION_DENOMINATOR = 2L

        /** 从 requestId 提取短标识（确认卡片展示用，如 a3f2） */
        fun shortId(requestId: String): String =
            requestId.removePrefix("cf-").split('-').lastOrNull()?.take(SHORT_ID_CHARS) ?: requestId

        /** 短标识长度（字符） */
        const val SHORT_ID_CHARS = 4

        // ── 确认超时钳制纯函数（🔒 字面值实测，见 behavior-contract.md §10.3 第 1 项） ──

        /**
         * 🔒 确认超时**下限**（秒）。
         * 理由：`0`/负值会让确认卡片刚发出即超时拒绝。
         */
        const val MIN_CONFIRM_TIMEOUT_SECONDS = 10L

        /** 🔒 确认超时**上限**（秒）（与 UI 滑杆上限同值，lib 侧兜底）。 */
        const val MAX_CONFIRM_TIMEOUT_SECONDS = 3600L

        /**
         * 🔒 秒 → 毫秒并施加下限钳制。
         *
         * 实测断言（见 ConfirmTimeoutClampTest）：`(0)==10_000`、`(-30)==10_000`、`(120)==120_000`。
         * 纯函数，无 IO、无状态。
         */
        fun clampedConfirmTimeoutMillis(seconds: Long): Long =
            seconds.coerceAtLeast(MIN_CONFIRM_TIMEOUT_SECONDS) * 1000L

        /**
         * 🔒 `agentTimeoutMinutes` → 确认超时上限（秒）。
         * 语义：确认等待不得超过 agent 总超时（否则 agent 已被 kill 而确认还在挂着）。
         */
        fun resolveConfirmTimeoutUpperBound(minutes: Long): Long =
            minOf(MAX_CONFIRM_TIMEOUT_SECONDS, (minutes * 60L).coerceAtLeast(1L))
    }
}
