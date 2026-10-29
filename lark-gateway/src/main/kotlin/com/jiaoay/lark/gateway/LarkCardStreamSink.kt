// :lark-gateway —— 全仓唯一允许 import com.lark.oapi.* 的模块（AGENTS.md 第 8 条）。
// 本文件：流式进度卡会话 LarkCardStreamSink（cardkit 四步 + 时间窗节流 + 24KB 预算分段）。SDK 隔离守护：LarkSdkIsolationTest。
// cardkit 调用收在 internal 接口 CardStreamPort 后面，使三条降级分支（patch→content→updateCard）只改实现、不改契约与 sink。
// 🔴 本文件禁 SDK 类型：sink 只依赖 CardStreamPort；SDK 翻译在 SdkCardStreamPort.kt 的 SdkCardStreamPort（gateway-contract.md §15.2 M-13）。
// 🔴 F 组硬门禁（gateway-contract.md §4.1）：本文件含两个 @Volatile var（segIndex / open），故禁止出现 companion / data / 匿名 三类 object 声明（object 是进程级单例，其可变状态会被多个 sink 实例共享串改；连注释里的 ASCII 字样也禁）。
package com.jiaoay.lark.gateway

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * cardkit / im 一次调用的纯结果（零 SDK 类型）。sink 与测试 fake 共用；SDK 侧由 SdkCardStreamPort 从 BaseResponse 翻译而来。
 * code = 飞书业务码（BaseResponse.getCode()）；httpStatus = RawResponse.getStatusCode()；cardId/messageId 仅建卡/发卡步骤非空。
 */
internal data class CardCallOutcome(
    val success: Boolean,
    val code: Int = 0,
    val httpStatus: Int? = null,
    val msg: String? = null,
    val cardId: String? = null,
    val messageId: String? = null,
    val rateLimitResetSec: Int? = null,
)

/**
 * 卡片流式的 SDK 能力面（internal 接口）。生产实现 [SdkCardStreamPort] 在 SdkCardStreamPort.kt；测试用 fake 计数。
 * 每个方法都是 suspend，实现内部负责 withContext(netDispatcher)（NF-A4：cardkit 四步发起点在 bridge-net）。
 */
internal interface CardStreamPort {
    /** ① 创建卡片实体（gateway-contract.md §4.2 步①）。cardJson 是已编码的整卡 JSON 串。成功时 outcome.cardId 非空。 */
    suspend fun createCardEntity(cardJson: String): CardCallOutcome

    /** ② 按引用发送卡片（gateway-contract.md §4.2 步②）。contentJson = {"type":"card","data":{"card_id":...}}。成功时 outcome.messageId 非空。 */
    suspend fun sendCardByReference(contentJson: String, receiveId: String, receiveIdType: String): CardCallOutcome

    /** ③ 流式更新文本元素（gateway-contract.md §4.2 步③，PASS 分支用 patch + partialElement）。 */
    suspend fun patchElement(cardId: String, elementId: String, partialJson: String, sequence: Int): CardCallOutcome

    /** ④ 结束流式（gateway-contract.md §4.2 步④，settings.streaming_mode=false）。 */
    suspend fun finishSettings(cardId: String, settingsJson: String, sequence: Int): CardCallOutcome
}

/**
 * 卡片失败处置动作（gateway-contract.md §4.9）。用 enum 而非 sealed 子类：F 组禁 object 声明，且 enum 无需承载 payload（轮换原因由主循环映射）。
 */
private enum class FailureAction { NONE, REBUILD_STREAM, ROTATE_BYTE_BUDGET, ROTATE_ENTITY_EXPIRED, GIVE_UP, BACKOFF }

/**
 * 流式进度卡会话实现。全部状态 per-sink，无任何全局 map（sequence 不做全局 card_id→seq 表）。
 * 🔴 每次轮换 = 新建卡片实体，绝不复用 card_id（卡片实体仅可发送一次、14 天有效）。
 *
 * 单一不可变快照：[slot] 持当前段的**整段全文**——滚动窗口语义下 = 最新窗口快照，
 * 不累积历史；tryReplace 一次 set 完成覆盖，主循环读 slot.get() 以 `dirty` 标志判定「距上次 patch 是否有变化」。
 * flushTerminal/close 只读 slot.get().text，与主循环无写竞态。
 */
internal class LarkCardStreamSink(
    private val port: CardStreamPort,
    private val target: LarkTarget,
    private val initial: CardSpec,
    private val netDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val onSignal: (GatewaySignal) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : CardStreamSink {

    /** 当前段的整段全文快照。text=全文，chars=text.length，dirty=距上次 patch 是否有变化。 */
    private data class Snapshot(val text: String, val chars: Int, val dirty: Boolean)

    private val log = LoggerFactory.getLogger(LarkCardStreamSink::class.java)

    private val slot = AtomicReference(Snapshot("", 0, false))

    // —— per-sink 可变状态（gateway-contract.md §4.1 表）——
    // 🔴 P1-3：sequence 用 AtomicInteger（不再是普通 var）——主循环 job 在 netDispatcher，flushTerminal/close 在
    //    withContext(NonCancellable + netDispatcher)，二者对序号做 read-modify-write **无互斥**；普通 var 会丢失更新
    //    ⇒ 重复 sequence ⇒ 飞书 300317 序号冲突拒绝 patch。incrementAndGet() 保证严格递增、无跳号无回退。
    private val sequence = AtomicInteger(0)      // 起始 0，每次 patch/settings 前 incrementAndGet（gateway-contract.md §4.1）
    private var cardId: String? = null            // 当前段卡片实体 id
    private var cardMessageId: String? = null     // 当前段「按引用发送」得到的 messageId
    private var lastFireAt: Long = 0L             // 上次 patch 的时刻（时间节流用）
    // segmentStartedAt 已删除：主动 TimeDeadline 轮换退役，服务端关流由 check() 的 200750 分支被动重建兜底

    @Volatile private var segIndex: Int = 1       // 契约成员，1-based（gateway-contract.md §4.6）
    @Volatile private var open: Boolean = true    // 契约 isOpen

    /** onFinished 的「恰一次」容器（见 [onFinished]，终态事实源 = [job] 生命周期）。 */
    private val finished = OnceCallback()

    /** 失败计数（卡片更新失败可观测三件套之一）。按 GatewayErrorCode 分类。 */
    private val failureCounters = ConcurrentHashMap<GatewayErrorCode, LongAdder>()

    /**
     * 连续重建计数。只被主循环协程读写（单线程），任一次 patch 成功即清零。
     * 与 [open] 同域：两者都在主循环内改动，flushTerminal/close 只读 open。
     */
    private var consecutiveRebuilds: Int = 0

    /** 供 SegmentRotationTest / Stream10MinRotationTest 断言「某错误码计数」。 */
    internal fun failureCount(code: GatewayErrorCode): Long = failureCounters[code]?.sum() ?: 0L

    /** 供 GatewayDispatcherTest / 引擎读取当前段「按引用发送」的 messageId（可空）。 */
    internal fun currentCardMessageId(): String? = cardMessageId

    // 主循环在构造时即启动（scope = 网关私有 gatewayScope 的子作用域，close()/网关 close() 时 cancel）。
    // internal（非 private）：SdkLarkGateway.openCardStream 需 job.invokeOnCompletion 从 openSinks 摘除本 sink。
    internal val job: Job = scope.launch(netDispatcher) {
        try {
            // 建第 1 段（reason=null ⇒ 不 ++segIndex、不发轮换信号）
            if (!rotateSegment(reason = null, initialText = initial.markdown)) {
                open = false
                return@launch
            }
            while (isActive && open) {
                val snap = slot.get()
                val now = clock()
                val elapsed = now - lastFireAt
                // 🔴 单条件时间节流 + 预算分段。flush 守卫用「snap.dirty」而非字符计数——替换语义下
                //    新快照比上次 patch 后**更短**时字符增量为负，内容变化却永不 patch（删行也是变化，必须刷）。
                // 主动 TimeDeadline 轮换已退役：长任务定期强发新卡会刷屏；服务端若真关流 ⇒
                //    patch 报 200750 EntityExpired ⇒ check() 的 ROTATE_ENTITY_EXPIRED 自动换卡续命
                //    （不静默死流）；若服务端不关流 ⇒ 恒 1 张卡。ByteBudget 分支保留（24KB 是飞书
                //    单卡 30KB 硬限的防御，滚动窗口 2000 字恒不可达但留作 cardMaxChars 调大时的兜底）。
                val timeHit = elapsed >= Tuning.streamThrottleMs                       // 250ms
                val budgetHit = snap.text.toByteArray(Charsets.UTF_8).size >= GatewayLimits.cardByteBudget // 24576（UTF-8 字节，gateway-contract.md §4.5）
                when {
                    // 🔴 轮换失败（新建卡片实体/发卡失败）必须置 open=false 并退出主循环——与首段构建（上方）
                    // 及 rotateSegment 自身契约（@return false=建卡/发卡失败，调用方置 open=false）一致。rotateSegment 在
                    // createCardEntity/sendCardByReference 失败时于重置 slot/segmentStartedAt/cardId **之前**返回 false；
                    // 若丢弃该 false，则快照不变 ⇒ budgetHit 恒成立 ⇒ 25ms 对已定稿死卡反复 finishSettings + 反复建卡
                    // ⇒ 自维持活锁（约 80 次/秒轰飞书网关侧 API、绕过 bridge 侧 RateGovernor），且失败窗口内内容永不再显示。
                    budgetHit -> if (!rotateSegment(RotationReason.ByteBudget, "")) { open = false; return@launch }
                    timeHit && snap.dirty -> actOnFailure(firePatch())
                }
                sleeper(Tuning.sinkPollIntervalMs)   // 25ms；虚拟时间测试由 advanceTimeBy 驱动
            }
        } finally {
            // 🔴 主循环自然退出（建卡失败 / 重建预算耗尽 / GIVE_UP）也算「流终结」——
            //    close()/flushTerminal()/abortForClose() 分别经 open=false 或 job.cancel() 汇到这里的完成态。
            //    三路终态在此汇成一处，对消费方（TrackingReplySink 的 openStreams 摘除）恰好触发一次。
            //    runCatching 对齐 invokeOnCompletion 语义：完成回调绝不能以异常顶掉 CancellationException。
            runCatching { finished.fire() }
                .onFailure { log.warn("onFinished 回调抛异常：{}", it.toString()) }
        }
    }

    override val isOpen: Boolean get() = open
    override val segmentIndex: Int get() = segIndex

    override fun tryReplace(text: String): Boolean {
        if (!open) return false
        // 🔴 1 槽 conflate：整段覆盖为最新窗口快照（滚动窗口语义——追加会累积历史，覆盖恒等于最新）。
        //    空串也是合法快照（窗口清空），同样置 dirty；覆盖频率由上层（泵只推最新帧）保证。
        slot.set(Snapshot(text, text.length, dirty = true))
        return true
    }

    override suspend fun flushTerminal(card: CardSpec) = withContext(NonCancellable + netDispatcher) {
        if (!open) return@withContext
        // 终态更新不受节流窗口限制，立即 flush（NF-02）；文案归引擎，网关忠实取 CardSpec.markdown，空则回退当前全文
        val finalText = LarkOutboundMapper.toTerminalMarkdown(card, fallback = slot.get().text)
        val cid = cardId
        if (cid != null) {
            val patchSeq = sequence.incrementAndGet()
            callAndCheck("flushTerminal.patch") {
                port.patchElement(cid, Tuning.streamElementId, encodePartial(finalText), patchSeq)
            }
            val settingsSeq = sequence.incrementAndGet()
            callAndCheck("flushTerminal.settings") {
                port.finishSettings(cid, encodeSettings(finalText), settingsSeq)
            }
        }
        open = false
        // 🔴 终态落账信号：飞书无按 card_id 读卡片实体的 API，终态正文只在这里——
        //    发给桥侧记账，供「引用这条机器人回复」以本地账兜底。messageId 取不到时仍发（text 有价值）。
        offerTerminalSignal(finalText)
        Unit
    }

    override suspend fun close() = withContext(NonCancellable + netDispatcher) {
        if (!open) return@withContext                 // 🔴 幂等：重复 close 无副作用（不会二次发终态卡）
        withTimeoutOrNull(Tuning.sinkCloseTimeoutMs) { // 3000ms，对齐十步关闭第 ④ 步
            val snap = slot.get()
            val cid = cardId
            if (snap.text.isNotEmpty() && cid != null) {
                val patchSeq = sequence.incrementAndGet(); runCatching { port.patchElement(cid, Tuning.streamElementId, encodePartial(snap.text), patchSeq) }
                val settingsSeq = sequence.incrementAndGet(); runCatching { port.finishSettings(cid, encodeSettings(snap.text), settingsSeq) }
            }
        }
        open = false
        // close（未发过终态卡）也算终态落定：正文 = 当前流内快照——引用这条回复时同样能兜底。
        offerTerminalSignal(slot.get().text)
        job.cancel()
        Unit
    }

    /**
     * 注册「流终结」回调（契约见 [CardStreamSink.onFinished]）。
     * 终态事实源 = [job] 生命周期：close() / flushTerminal() / abortForClose() 都走 job.cancel()，
     * 主循环自然退出（建卡失败 / 重建预算耗尽 / GIVE_UP）走 finally——三路在此汇成一处，
     * 由 [OnceCallback] 保证「恰一次」（含建卡秒败早于注册的竞态，晚到注册会补触发）。
     */
    override fun onFinished(callback: (() -> Unit)?) {
        finished.set(callback)
    }

    /** 终态落账信号发射（[GatewaySignal.StreamTerminal]）。text 空白则无记账价值，跳过。
     *  🔴 chatId 随信号下发（账本「本会话校验」维度；User 目标 null → 记 ""）。 */
    private fun offerTerminalSignal(text: String) {
        if (text.isBlank()) return
        val chatId = (target as? LarkTarget.Chat)?.chatId
        runCatching { onSignal(GatewaySignal.StreamTerminal(messageId = cardMessageId ?: return, markdown = text, chatId = chatId)) }
            .onFailure { log.warn("终态落账信号发射失败：${it.message}") }
    }

    /** 网关 close() 第 3 步兜底（gateway-contract.md §9.3）：非挂起，不发终态卡（网关不知文案），只置关闭 + cancel 协程。 */
    internal fun abortForClose() {
        open = false
        job.cancel()
    }

    // ==================== 内部：分段轮换 ====================

    /**
     * 分段轮换（gateway-contract.md §4.5）。reason==null 是建第 1 段（不 ++segIndex、不发信号）；非 null 是轮换（++segIndex + 发 StreamSegmentRotated）。
     * @return true=新段就绪；false=建卡/发卡失败（调用方置 open=false）。
     */
    private suspend fun rotateSegment(reason: RotationReason?, initialText: String): Boolean {
        // 1) 旧段收尾：补最后一次 patch（把旧段定格为末尾状态，gateway-contract.md §15.2 M-15）+ settings 结束流式，不复用旧 cardId
        //    🔴 收尾失败必须可观测：warn 日志 + failureCounters 计数（对齐 check() 的可观测三件套），
        //    否则旧卡永驻「生成中」且零线索；收尾失败仍不阻断轮换（旧卡已死，新卡继续才是正途）。
        val oldId = cardId
        val oldText = slot.get().text
        if (oldId != null) {
            val patchSeq = sequence.incrementAndGet()
            runCatching { port.patchElement(oldId, Tuning.streamElementId, encodePartial(oldText), patchSeq) }
                .onFailure { log.warn("旧卡收尾 patch 失败 cardId={} seq={}：{}", oldId, patchSeq, it.toString()) }
                .onSuccess { o -> if (!o.success || o.code != 0) check("old-segment-patch", o) }
            val settingsSeq = sequence.incrementAndGet()
            runCatching { port.finishSettings(oldId, encodeSettings(oldText), settingsSeq) }
                .onFailure { log.warn("旧卡收尾 finishSettings 失败 cardId={} seq={}：{}", oldId, settingsSeq, it.toString()) }
                .onSuccess { o -> if (!o.success || o.code != 0) check("old-segment-settings", o) }
        }
        // 2) 🔴 每次轮换 = 新建卡片实体（卡片实体仅可发送一次、14 天有效）
        val newCardJson = LarkOutboundMapper.encodeJson(
            LarkOutboundMapper.toCardJson(initial.copy(markdown = initialText, streaming = true))
        )
        val newCardId = createCardEntity(newCardJson) ?: return false
        val newMsgId = sendCardByReference(newCardId) ?: return false
        // 3) 段状态归零（sequence 重新从 0 计）
        cardId = newCardId
        cardMessageId = newMsgId
        sequence.set(0)
        slot.set(Snapshot(initialText, initialText.length, false))
        lastFireAt = clock()
        if (reason != null) {
            segIndex++
            onSignal(GatewaySignal.StreamSegmentRotated(segIndex, reason, newMsgId))
        }
        return true
    }

    private suspend fun createCardEntity(cardJson: String): String? {
        val o = runCatching { port.createCardEntity(cardJson) }.getOrElse { recordThrow("create", it); return null }
        if (!o.success || o.code != 0) { check("create", o); return null }
        return o.cardId
    }

    private suspend fun sendCardByReference(cardId: String): String? {
        val contentJson = LarkOutboundMapper.encodeJson(LarkOutboundMapper.toCardRefContent(cardId))
        val o = runCatching { port.sendCardByReference(contentJson, target.receiveId, target.receiveIdType) }
            .getOrElse { recordThrow("sendRef", it); return null }
        if (!o.success || o.code != 0) { check("sendRef", o); return null }
        return o.messageId
    }

    // ==================== 内部：patch 与处置 ====================

    private suspend fun firePatch(): FailureAction {
        val cid = cardId ?: return FailureAction.NONE
        val snap = slot.get()
        val patchSeq = sequence.incrementAndGet()
        val action = callAndCheck("patch") { port.patchElement(cid, Tuning.streamElementId, encodePartial(snap.text), patchSeq) }
        lastFireAt = clock()
        // 🔴 清 dirty 必须**只清掉本次已发出去的那一版**。
        //    旧写法 `slot.updateAndGet { it.copy(dirty = false) }` 在 patchElement 的挂起窗口（真实网络 RTT）内
        //    被泵协程 tryReplace 写入的新快照（v2）会被一并误标「已刷」——卡上实际是 v1，v2 要等到下一次
        //    dirty 变化才补发（内容是「卡停在旧内容直到下一帧」，长静默期会一直停）。CAS 比对 text 即可消解。
        slot.compareAndSet(snap, snap.copy(dirty = false))
        // 🔴 **成功 patch 才算恢复**——重建预算计数在这里清零（而非在 rotateSegment 成功后清零）。
        //    否则「建卡成功但 patch 持续失败」会每轮把计数归零 ⇒ 预算形同虚设，仍会无限重建刷幽灵卡。
        if (action == FailureAction.NONE) consecutiveRebuilds = 0
        return action
    }

    private suspend fun actOnFailure(a: FailureAction) {
        when (a) {
            FailureAction.NONE -> Unit
            // 🔴 三个重建/轮换分支**不得丢弃 rotateSegment 返回值**——
            //    重建时建卡失败不置 open=false ⇒ 25ms 后同一快照反复重建 ⇒ 活锁。现统一在失败时关闭并让主循环退出
            //    （open=false 后 while 条件不成立；调用方在同协程内，无需显式 return）。
            // 🔴 **重建预算**——只封死「重建失败」不足以收敛：
            //    「patch 持续报未登记码/404 而建卡持续成功」时每 250ms 就能成功重建一次（≈4 张幽灵卡/秒轰进会话，
            //    恰是本次单卡收敛要消灭的刷屏换了个触发路径）。现由 rebuildBudgeted 统一判定上限 + 退避。
            FailureAction.REBUILD_STREAM ->
                rebuildBudgeted(RotationReason.SequenceConflict)      // 300317/未登记码 → 重建流（新实体 + sequence 归零）
            FailureAction.ROTATE_BYTE_BUDGET ->
                rebuildBudgeted(RotationReason.ByteBudget)            // 200860 → 分段轮换
            FailureAction.ROTATE_ENTITY_EXPIRED ->
                rebuildBudgeted(RotationReason.EntityExpired)         // 200750 → 新建实体
            FailureAction.BACKOFF -> sleeper(Tuning.rateLimitBackoffMs)                  // 429/11232 → 退避（重试决策交 RateGovernor）
            FailureAction.GIVE_UP -> open = false                                        // 300302/230099 → 不重试
        }
    }

    /**
     * 受预算约束的重建。返回 false 表示应停止主循环（预算耗尽或建卡失败）。
     * 🔴 只有**成功 patch** 才清零计数（见 [firePatch]）——「建卡成功但 patch 持续失败」不算恢复。
     */
    private suspend fun rebuildBudgeted(reason: RotationReason): Boolean {
        if (consecutiveRebuilds >= Tuning.maxConsecutiveRebuilds) {
            log.warn(
                "重建预算耗尽（连续 {} 次，reason={}）——放弃本流，避免无限重建刷幽灵卡 cardId={}",
                consecutiveRebuilds, reason, cardId,
            )
            open = false
            return false
        }
        consecutiveRebuilds++
        if (!rotateSegment(reason, "")) {
            open = false
            return false
        }
        sleeper(Tuning.streamRebuildBackoffMs)   // 实测存在「新卡建出后 patch 仍失败」的持续状态，退避把速率压到 ~1/s
        return true
    }

    /** 统一「调用 + 逐次响应码检查」（gateway-contract.md §4.9）。异常与业务码失败都走「计数 + 日志 + 错误映射 + 信号」三件套，绝不静默。 */
    private suspend fun callAndCheck(tag: String, block: suspend () -> CardCallOutcome): FailureAction =
        runCatching { block() }.fold(
            onSuccess = { check(tag, it) },
            onFailure = { recordThrow(tag, it); FailureAction.GIVE_UP },
        )

    /** 逐次响应码检查（SDK 侧 cardkit 全静默失败，必须我方补检）。 */
    private fun check(tag: String, o: CardCallOutcome): FailureAction {
        if (o.success && o.code == 0) return FailureAction.NONE
        val gw = LarkErrorMapper.fromLarkCode(o.code, o.httpStatus)
        bump(gw)
        log.warn("cardkit {} 失败 code={} http={} msg={} cardId={} seq={}", tag, o.code, o.httpStatus, o.msg, cardId, sequence.get())
        onSignal(GatewaySignal.SendFailed(gw, o.msg ?: "", target.receiveId))
        if (o.rateLimitResetSec != null || gw == GatewayErrorCode.RateLimited) {
            onSignal(GatewaySignal.RateLimited(o.rateLimitResetSec, o.code, target.receiveId))
        }
        // 按码分派处置（gateway-contract.md §4.9 / [behavior-contract.md §5.3]）
        return when (gw) {
            GatewayErrorCode.CardSequenceConflict -> FailureAction.REBUILD_STREAM
            GatewayErrorCode.CardTooLarge -> FailureAction.ROTATE_BYTE_BUDGET
            GatewayErrorCode.CardEntityExpired -> FailureAction.ROTATE_ENTITY_EXPIRED
            GatewayErrorCode.CardStreamBusy -> FailureAction.NONE   // 200810：契约层已禁流式卡带交互组件，此处只记录
            GatewayErrorCode.CardJsonInvalid -> FailureAction.GIVE_UP // 300302/230099：重试必然再错
            GatewayErrorCode.RateLimited -> FailureAction.BACKOFF
            // 🔴 其余（含未登记业务码）走 REBUILD_STREAM 而非 GIVE_UP——主动轮换退役后，
            //    服务端若关流/报未知码，静默死流（比刷屏更糟：进度永停、无人知晓）比换卡续命糟糕。
            //    换卡再失败由 rotateSegment 返回 false 置 open=false 收敛（P0-1），不会活锁。
            else -> FailureAction.REBUILD_STREAM
        }
    }

    private fun recordThrow(tag: String, t: Throwable): FailureAction {
        val gw = LarkErrorMapper.fromThrowableName(t.javaClass.name, t.message)
        bump(gw)
        log.warn("cardkit {} 异常 cardId={} seq={}: {}", tag, cardId, sequence.get(), t.toString())
        onSignal(GatewaySignal.SendFailed(gw, t.message ?: t.toString(), target.receiveId))
        return FailureAction.GIVE_UP
    }

    private fun bump(code: GatewayErrorCode) {
        failureCounters.computeIfAbsent(code) { LongAdder() }.increment()
    }

    private fun encodePartial(text: String): String =
        LarkOutboundMapper.encodeJson(LarkOutboundMapper.toPartialElementJson(text))

    private fun encodeSettings(finalText: String): String =
        LarkOutboundMapper.encodeJson(LarkOutboundMapper.toFinishSettingsJson(finalText))
}
