package com.jiaoay.easy_dev_toolbox.page.larkbridge.cockpit

import com.jiaoay.lark.gateway.ReactionBrief
import kotlinx.coroutines.CancellationException

/**
 * 驾驶舱的纯逻辑件：确认行剩余秒格式化 + 表情徽标过滤计数 +
 * 已读查询失败退避判定（终止 1Hz 快照驱动的每秒重试循环）。
 *
 * 零 Compose、零 IO——由 `CockpitPanelLogicTest` 直测（UI 组件无既有渲染测试，纯函数抽测先例
 * 同 [CockpitLogic]）；测试用例名无 `/` 无 `.`、断言消息中文。
 */
object CockpitPanelLogic {

    /**
     * 已读查询失败重试上限：单卡连续失败达此数即**永不再查**。
     * 背景：`LaunchedEffect(pendingViews)` 随快照变化重启，失败若直接 `remove`
     * 允许下次快照重试 ⇒ 桥未运行/能力缺席时形成每秒一次的重试循环；达上限后 UI 恒显
     * 「已读 —」诚实缺席（不再打 API）。
     */
    const val READ_QUERY_MAX_FAILURES: Int = 3

    /**
     * 已读查询是否应发起（退避判定，纯函数——由调用方以「成功集合 + 失败计数」喂参）：
     * - [succeeded] = true（该卡已查到计数）⇒ 不再查（既有语义保留：同卡成功后不再查）；
     * - 失败次数 [failureCount] ≥ [READ_QUERY_MAX_FAILURES] ⇒ 永不再查；
     * - 其余（未查过 / 失败未达上限）⇒ 允许发起。
     */
    fun shouldRetryReadQuery(succeeded: Boolean, failureCount: Int): Boolean =
        !succeeded && failureCount < READ_QUERY_MAX_FAILURES

    /**
     * 已读查询的安全读取：普通失败折叠为 null（烧退避额度），**协程取消原样重抛**
     * ——`LaunchedEffect(pendingViews)` 在 pending 集结构变化时（新确认请求/他卡回填/裁决）重启并取消
     * 旧协程，在途 query 收到 CE 不是「查询失败」；若吞成 null 会虚假计失败 +1 且被取消协程继续
     * forEach 余下卡片各烧 1 次额度——READ_QUERY_MAX_FAILURES=3 下两次重启竞态即可让健康卡永久
     * 「已读 —」（仓内 kotlinx 纪律：吞 CE 清退避）。
     *
     * 🔴 catch 顺序钉死：`TimeoutCancellationException` 是 CE 子类——本函数不做超时区分（调用方
     * query 自带超时兜底），CE 一律按取消处理先于 Throwable 捕获。
     *
     * @param query 挂起查询（面板 `onQueryReadCount` 透传——恒不抛普通异常由本函数兜底）
     * @param cardId 确认卡消息 ID
     * @return 查到计数；null = 查询失败/能力缺席（烧退避额度）。CE = 调用方协程被取消（不烧额度，重抛）。
     */
    internal inline fun readCountSafely(query: (String) -> Int?, cardId: String): Int? = try {
        query(cardId)
    } catch (e: CancellationException) {
        throw e // 协程取消不是查询失败——不烧退避额度（LaunchedEffect 重启竞态防护）
    } catch (t: Throwable) {
        null
    }

    /**
     * 确认行「剩余秒」→ 展示文案与是否告警。
     *
     * @param remainingMillis 剩余毫秒（createdAt + effectiveTimeoutMillis - now）
     * @return 文案 = `mm:ss`（负值钳 0 显示 `00:00`）+ 超时临近告警位（≤0 = 「即将超时」，配色 warning）
     */
    fun formatRemaining(remainingMillis: Long): RemainingText {
        val clamped = remainingMillis.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return RemainingText(
            text = "%02d:%02d".format(minutes, seconds),
            expiring = remainingMillis <= 0L,
        )
    }

    /** 剩余秒 pill 的渲染形态（[expiring] = 已到/超时，UI 配 warning 色）。 */
    data class RemainingText(val text: String, val expiring: Boolean)

    /**
     * 表情回应 → 行尾徽标：**过滤机器人自点**后按 THUMBSUP/CROSS_MARK 分组取人最多的组
     * （机器人终态会给用户原消息加 THUMBSUP=成功 / CROSS_MARK=失败，用户自己也会点同款——
     * 徽标只反映「用户后加」的表情；botOpenId 未知（非 Connected 态）时不渲染徽标）。
     *
     * 取「人最多」组语义（设计稿屏⑤「最近任务行尾 👍 ×2」）：同一条消息上可能有多个 emoji 组
     * 并存（如成功 THUMBSUP 后用户又点了 CROSS_MARK），显示人数最多的一组，不发明聚合新形态。
     *
     * @param reactions 该消息的全部表情回应（listReactions 原样透传，含机器人自点）
     * @param botOpenId 机器人自身 openId（null/空 = 未知 ⇒ 无法过滤，返回 null 不渲染——诚实缺席）
     * @return 徽标（emojiKey + 用户人数）；null = 无用户表情可显示（不渲染徽标）
     */
    fun userReactionBadge(reactions: List<ReactionBrief>?, botOpenId: String?): ReactionBadge? {
        if (reactions.isNullOrEmpty()) return null
        if (botOpenId.isNullOrBlank()) return null
        val userReactions = reactions.filter { it.operatorOpenId != botOpenId }
        if (userReactions.isEmpty()) return null
        // 徽标只认机器人终态同款两个 emoji key（THUMBSUP/CROSS_MARK）——OnIt 是「处理中」瞬态、
        //    终态即撤回，其余 emoji key（用户自定义表情包等）不进徽标（口径收窄防噪音）。
        val counted = userReactions.filter { it.emojiKey == THUMBSUP_KEY || it.emojiKey == CROSS_MARK_KEY }
        if (counted.isEmpty()) return null
        val top = counted.groupingBy { it.emojiKey }.eachCount().maxByOrNull { it.value } ?: return null
        return ReactionBadge(emojiKey = top.key, count = top.value)
    }

    /** 「👍 ×N」徽标的渲染形态（[emojiKey] 决定字形与配色：THUMBSUP=success / CROSS_MARK=error）。 */
    data class ReactionBadge(val emojiKey: String, val count: Int)

    /** 飞书 emoji key 字面值（= BuiltinHandlers.EMOJI_DONE / EMOJI_FAILED，lib internal 不跨界——本地同字面值）。 */
    private const val THUMBSUP_KEY = "THUMBSUP"
    private const val CROSS_MARK_KEY = "CROSS_MARK"
}
