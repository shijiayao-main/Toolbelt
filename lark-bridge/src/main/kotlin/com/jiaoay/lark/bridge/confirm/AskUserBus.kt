// user.ask_user 飞书形态的答案回传总线：提问卡 messageId → 挂起等待的 CompletableDeferred。
// 与 ConfirmService 的二元裁决不同——本总线承载**开放式结构化回答**（自由文本），
// 故不复用 ConfirmService 的 Decision 模型；防替答/超时/取消语义与之同防线：
// 只有原提问者（senderOpenId）的引用回复才被采纳；超时回 skipped 形态（软反馈不崩任务）。
package com.jiaoay.lark.bridge.confirm

import com.jiaoay.lark.bridge.BridgeLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 提问挂起总线：`user.ask_user` 工具挂起等用户回答的配对面。
 *
 * ## 形态（与 [ConfirmService] 同防线语义，裁决值域不同）
 * - pending 表以**提问卡 messageId** 为键（用户「引用提问卡回复」是答案回传的主通道——
 *   零格式学习成本）；单据号短标识是调试辅助，不作键；
 * - 防替答：注册时快照发起者 senderOpenId，decide 时不匹配即拒（返回 false 不完成挂起——
 *   他人的引用回复不误伤在途提问）；
 * - 超时 = 回 [AskAnswer.skipped]（软反馈——HITL 的失败模式是降级继续而非终止，
 *   与围栏 DENIED 同源哲学）；挂起方按 AskCaps 软超时换算（0.8×整体预算）传入；
 * - 取消联动：awaitAnswer 挂起点被取消时登记项随之注销（finally 清表），不泄漏 pending 项。
 *
 * ## 线程模型
 * register/decide 由不同协程调用（工具协程 vs InteractionHandler 管线协程）——
 * 表用 ConcurrentHashMap，CompletableDeferred 完成原子性由其自身保证；恒不抛
 * （decide 失败按未配对返回 false，调用方走「未命中 pending」路径）。
 */
class AskUserBus(private val log: BridgeLog = BridgeLog.Silent) {

    /** 一次提问的挂起面：发卡后 register，答案/超时后 complete。 */
    data class PendingQuestion(
        val requestId: String,
        val cardMessageId: String,
        val chatId: String,
        val senderOpenId: String,
        val question: String,
    )

    /** 答案回传载荷（fields 按字段 id 索引；skipped = 用户点了「暂无补充」或超时） */
    data class Answer(
        val values: Map<String, String>,
        val skipped: Boolean,
    )

    private val pending = ConcurrentHashMap<String, Entry>()
    private val seq = AtomicLong(0)

    private class Entry(
        val question: PendingQuestion,
        val deferred: CompletableDeferred<Answer>,
    )

    /** 单据号生成（qa-<seq> 形态，调试对账辅助；非键） */
    fun nextRequestId(): String = "qa-${seq.incrementAndGet()}"

    /**
     * 登记一张在途提问卡（发卡成功后调用）。
     *
     * @return 提问快照（requestId 已生成）
     */
    fun register(
        cardMessageId: String,
        chatId: String,
        senderOpenId: String,
        question: String,
    ): PendingQuestion {
        val q = PendingQuestion(
            requestId = nextRequestId(),
            cardMessageId = cardMessageId,
            chatId = chatId,
            senderOpenId = senderOpenId,
            question = question,
        )
        pending[cardMessageId] = Entry(q, CompletableDeferred())
        return q
    }

    /**
     * 挂起等待答案（软超时由调用方按 AskCaps 换算传入；超时回 skipped 并注销）。
     * 协程取消时注销登记项（防 pending 泄漏），取消原样上抛。
     */
    suspend fun awaitAnswer(cardMessageId: String, timeoutMillis: Long): Answer {
        val entry = pending[cardMessageId] ?: return Answer(emptyMap(), skipped = true)
        try {
            return withTimeoutOrNull(timeoutMillis) { entry.deferred.await() }
                ?: Answer(emptyMap(), skipped = true).also { unregister(cardMessageId) }
        } finally {
            // 完成（答/超时）与取消共用注销路径——登记项生命周期 = 挂起期
            unregister(cardMessageId)
        }
    }

    /**
     * 裁决一次回答（AskAnswerHandler 从引用回复/按钮回传调用）。
     *
     * @param cardMessageId 提问卡 messageId（配对键）
     * @param senderOpenId 回答者（防替答——必须等于发起者）
     * @param values 回答值（自由文本按字段解析后传入）
     * @param skipped 用户显式跳过
     * @return true = 配对成功且完成挂起；false = 未配对/防替答不匹配（调用方按非提问回答回执处理）
     */
    fun decide(
        cardMessageId: String,
        senderOpenId: String,
        values: Map<String, String>,
        skipped: Boolean,
    ): Boolean {
        val entry = pending[cardMessageId] ?: return false
        if (entry.question.senderOpenId != senderOpenId) {
            log.warn("ask_user 回答被拒（非发起者回复）：card=$cardMessageId")
            return false
        }
        pending.remove(cardMessageId)
        return entry.deferred.complete(Answer(values, skipped))
    }

    /** 按引用消息 id 查在途提问（AskAnswerHandler 配对判定用；不消费） */
    fun find(cardMessageId: String): PendingQuestion? = pending[cardMessageId]?.question

    private fun unregister(cardMessageId: String) {
        pending.remove(cardMessageId)
    }
}
