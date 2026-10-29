package com.jiaoay.easy_dev_toolbox.page.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 首启导览卡测试锚（反馈批二 2026-09-23 · 壳层 UX 任务 4）：
 *
 * 1. **三空判定纯函数**（[isFirstRunSnapshot] 直测）：spaces/usageCounts/runs
 *    三路皆空 = 从未用过；任一路非空即 false（判定转非空自动消失——零持久化
 *    key 的消失机制即此函数，回归即导览卡对老用户闪现）；
 * 2. **工具速览清单**（[onboardingToolItems]）：前 8 = 注册序（ToolPageRegistry
 *    .navItems() 的 take(8)——注册项退役时清单随之自然缩短，不硬编码页枚举）；
 * 3. **上限锚**（[ONBOARDING_TOOL_LIMIT]）：任务书定 8——防误改。
 */
class OnboardingCardTest {

    // ── 1 · 三空判定纯函数 ──

    @Test
    fun `first run snapshot is true only when all three sources are empty`() {
        assertTrue(
            isFirstRunSnapshot(spacesCount = 0, usageCounts = emptyMap(), runsCount = 0),
            "三路皆空 = 从未用过——导览卡应渲染",
        )
    }

    @Test
    fun `any non-empty source disqualifies first run`() {
        // 三路各开一个非空：任一转非空即导览卡消失
        assertFalse(
            isFirstRunSnapshot(spacesCount = 1, usageCounts = emptyMap(), runsCount = 0),
            "建过空间（含归档）= 用过——导览卡不得渲染",
        )
        assertFalse(
            isFirstRunSnapshot(spacesCount = 0, usageCounts = mapOf("LogWorkbench" to 1), runsCount = 0),
            "用过任一工具（计数非零）= 用过——导览卡不得渲染",
        )
        assertFalse(
            isFirstRunSnapshot(spacesCount = 0, usageCounts = emptyMap(), runsCount = 2),
            "跑过任一 run = 用过——导览卡不得渲染",
        )
    }

    @Test
    fun `usage counts with zero values still count as empty`() {
        // 口径钉死：计数 map 里的零值条目（理论不可达——tracker 只在 +1 时落 key）
        // 不构成「用过」；判定按 values.sum() 而非 map 非空
        assertTrue(
            isFirstRunSnapshot(spacesCount = 0, usageCounts = mapOf("ShellTools" to 0), runsCount = 0),
            "全零计数 = 空——判定按求和口径，非 map 非空口径",
        )
    }

    // ── 2 · 工具速览清单 = navItems 前 8 注册序 ──

    @Test
    fun `onboarding tool items are first 8 nav items in registry order`() {
        val expected = ToolPageRegistry.navItems().take(ONBOARDING_TOOL_LIMIT)
        assertEquals(
            expected,
            onboardingToolItems(),
            "导览卡速览 = navItems() 前 ${ONBOARDING_TOOL_LIMIT} 项按注册序——硬编码清单/换序即红",
        )
    }

    @Test
    fun `onboarding tool items never exceed limit`() {
        assertTrue(
            onboardingToolItems().size <= ONBOARDING_TOOL_LIMIT,
            "速览清单长度恒 ≤ 上限（take 语义——注册项少于上限时随之缩短）",
        )
    }

    // ── 3 · 上限锚 ──

    @Test
    fun `tool limit is 8 per task spec`() {
        assertEquals(8, ONBOARDING_TOOL_LIMIT, "任务书定速览 8 项——改动须回维护者拍板")
    }
}
