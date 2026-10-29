package com.jiaoay.easy_dev_toolbox.page.larkbridge.cockpit

import com.jiaoay.lark.gateway.ReactionBrief
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

/**
 * [CockpitPanelLogic] 纯逻辑契约（IA 重设计批 ⑤ 2026-09-27 屏⑤ + 重设计批 1 LB-03）：
 * 确认行剩余秒格式化（mm:ss + 即将超时告警位）+ 表情徽标过滤计数（机器人自点过滤）
 * + 已读查询失败退避判定（终止 1Hz 快照驱动的重试循环）。
 * 纯函数直测（CockpitPanel 无渲染测试——纯逻辑抽测先例同 CockpitLogicTest）。
 */
class CockpitPanelLogicTest {

    // ── 剩余秒格式化 ──

    @Test
    fun 剩余秒格式化为分秒() {
        assertEquals("02:31", CockpitPanelLogic.formatRemaining(151_000L).text, "预览稿屏⑤示例值：剩余 02:31")
        assertEquals("00:05", CockpitPanelLogic.formatRemaining(5_400L).text, "不足一分钟补零")
        assertEquals("10:00", CockpitPanelLogic.formatRemaining(600_000L).text, "整分钟")
        assertEquals("60:00", CockpitPanelLogic.formatRemaining(3_600_000L).text, "超 59 分不进位小时（mm:ss 直显）")
    }

    @Test
    fun 剩余秒负值与零钳制为即将超时() {
        val expired = CockpitPanelLogic.formatRemaining(-1L)
        assertEquals("00:00", expired.text, "负值钳 0 显示")
        assertTrue(expired.expiring, "剩余 ≤ 0 = 即将超时（warning 配色位）")

        val exactlyZero = CockpitPanelLogic.formatRemaining(0L)
        assertTrue(exactlyZero.expiring, "恰为 0 也算即将超时")

        val healthy = CockpitPanelLogic.formatRemaining(1_000L)
        assertFalse(healthy.expiring, "仍有 1 秒 = 未到告警线")
    }

    // ── 表情徽标过滤计数 ──

    @Test
    fun 徽标过滤机器人自点后按用户计数() {
        val reactions = listOf(
            ReactionBrief(emojiKey = "THUMBSUP", operatorOpenId = "ou_bot"),
            ReactionBrief(emojiKey = "THUMBSUP", operatorOpenId = "ou_user_a"),
            ReactionBrief(emojiKey = "THUMBSUP", operatorOpenId = "ou_user_b"),
        )
        val badge = CockpitPanelLogic.userReactionBadge(reactions, botOpenId = "ou_bot")
        assertEquals(2, badge?.count, "机器人自点被过滤 ⇒ 用户 2 人（👍 ×2 = 预览稿屏⑤示例）")
        assertEquals("THUMBSUP", badge?.emojiKey)
    }

    @Test
    fun 徽标无人点或机器人未知时不渲染() {
        assertNull(
            CockpitPanelLogic.userReactionBadge(emptyList(), botOpenId = "ou_bot"),
            "空清单 ⇒ null（不渲染）",
        )
        assertNull(
            CockpitPanelLogic.userReactionBadge(null, botOpenId = "ou_bot"),
            "null（能力缺席/锚点缺席）⇒ null（诚实缺席）",
        )
        assertNull(
            CockpitPanelLogic.userReactionBadge(
                listOf(ReactionBrief(emojiKey = "THUMBSUP", operatorOpenId = "ou_user")),
                botOpenId = null,
            ),
            "botOpenId 未知（非 Connected 态）⇒ 无法过滤机器人自点 ⇒ 不渲染（诚实缺席）",
        )
        assertNull(
            CockpitPanelLogic.userReactionBadge(
                listOf(ReactionBrief(emojiKey = "THUMBSUP", operatorOpenId = "ou_bot")),
                botOpenId = "ou_bot",
            ),
            "只剩机器人自点 ⇒ 过滤后为空 ⇒ 不渲染",
        )
    }

    @Test
    fun 徽标只认终态同款表情键且取人数最多组() {
        // OnIt 是「处理中」瞬态（终态即撤回）——用户即使手滑点 OnIt 也不进徽标
        assertNull(
            CockpitPanelLogic.userReactionBadge(
                listOf(ReactionBrief(emojiKey = "OnIt", operatorOpenId = "ou_user")),
                botOpenId = "ou_bot",
            ),
            "OnIt 瞬态不进徽标（口径收窄防噪音）",
        )
        // 多组并存：取人最多组（THUMBSUP 2 人 vs CROSS_MARK 1 人 ⇒ 2）
        val badge = CockpitPanelLogic.userReactionBadge(
            listOf(
                ReactionBrief(emojiKey = "THUMBSUP", operatorOpenId = "ou_a"),
                ReactionBrief(emojiKey = "THUMBSUP", operatorOpenId = "ou_b"),
                ReactionBrief(emojiKey = "CROSS_MARK", operatorOpenId = "ou_c"),
            ),
            botOpenId = "ou_bot",
        )
        assertEquals("THUMBSUP", badge?.emojiKey, "多组并存取人数最多组")
        assertEquals(2, badge?.count)
    }

    // ── 已读查询失败退避（LB-03 · 重设计批 1）──

    @Test
    fun 已读查询失败达上限即永不再查() {
        assertEquals(3, CockpitPanelLogic.READ_QUERY_MAX_FAILURES, "上限常量 = 3（卡面钉死）")
        // 0/1/2 次失败 → 仍允许重试（未达上限）
        assertTrue(CockpitPanelLogic.shouldRetryReadQuery(succeeded = false, failureCount = 0), "未查过 ⇒ 发起")
        assertTrue(CockpitPanelLogic.shouldRetryReadQuery(succeeded = false, failureCount = 1), "失败 1 次 ⇒ 仍可重试")
        assertTrue(CockpitPanelLogic.shouldRetryReadQuery(succeeded = false, failureCount = 2), "失败 2 次 ⇒ 仍可重试（3 次封顶未触）")
        // 达上限 → 永不再查（终止 1Hz 快照重试循环）
        assertFalse(CockpitPanelLogic.shouldRetryReadQuery(succeeded = false, failureCount = 3), "失败 ≥3 次 ⇒ 永不再查")
        assertFalse(CockpitPanelLogic.shouldRetryReadQuery(succeeded = false, failureCount = 4), "超上限同样永不再查")
    }

    @Test
    fun 已读查询成功后不再查且退避只看本卡计数() {
        assertFalse(
            CockpitPanelLogic.shouldRetryReadQuery(succeeded = true, failureCount = 0),
            "同卡已查到计数 ⇒ 不再查（既有语义保留）",
        )
        assertFalse(
            CockpitPanelLogic.shouldRetryReadQuery(succeeded = true, failureCount = 10),
            "成功态优先于失败计数（不可能出现成功+达上限的组合，防御断言）",
        )
        // 退避按 cardId 独立计数：卡 A 达上限不影响卡 B 首查
        assertTrue(
            CockpitPanelLogic.shouldRetryReadQuery(succeeded = false, failureCount = 0),
            "卡 B 首查不受卡 A 达上限影响（计数按 cardId 独立——本函数只判定单卡）",
        )
    }

    // ── 已读查询安全读取三态（LB-03 复核修复 · 重设计批 1）──

    @Test
    fun 已读查询安全读取三态正常返回真值() {
        assertEquals(
            2,
            CockpitPanelLogic.readCountSafely(query = { _ -> 2 }, cardId = "om_card"),
            "查询正常 ⇒ 原样返回计数值（不包一层）",
        )
        assertEquals(
            0,
            CockpitPanelLogic.readCountSafely(query = { _ -> 0 }, cardId = "om_card"),
            "0 是合法计数（已读 0 人）——不是失败，不得折叠成 null",
        )
    }

    @Test
    fun 已读查询安全读取普通异常折叠为null() {
        assertNull(
            CockpitPanelLogic.readCountSafely(query = { _ -> throw IllegalStateException("网络未就绪") }, cardId = "om_card"),
            "普通 Throwable ⇒ null（烧退避额度——LB-03 主语义保留）",
        )
        assertNull(
            CockpitPanelLogic.readCountSafely(query = { _ -> error("能力缺席") }, cardId = "om_card"),
            "Error 系普通失败同样折叠 null（调用方只认 Int? 二态）",
        )
    }

    @Test
    fun 已读查询安全读取协程取消原样重抛不烧额度() {
        // 🔴 LB-03 复核修复主断言：LaunchedEffect(pendingViews) 重启竞态取消旧协程 ⇒ 在途 query 抛 CE，
        //    必须原样重抛（吞成 null = 虚假计失败 +1，被取消协程还会继续 forEach 余下卡片各烧 1 次额度）。
        //    异常断言用 try/catch 而非 assertFailsWith（ProgramLauncherTest 教训：后者返回 Throwable
        //    会留尾值）+ assertSame 验实例同一。
        val ce = CancellationException("effect restarting")
        var thrown: Throwable? = null
        try {
            CockpitPanelLogic.readCountSafely(query = { _ -> throw ce }, cardId = "om_card")
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown != null, "CE 必须重抛（不得吞）")
        assertSame(ce, thrown, "重抛的是同一个 CE 实例（不包装不换新）")
        // 重抛路径不返回值（编译器背书：返回类型 Int? 的 null 只在 Throwable 分支）——退避额度零消耗
    }
}
