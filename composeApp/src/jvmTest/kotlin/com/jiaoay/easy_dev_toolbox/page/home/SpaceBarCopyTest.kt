package com.jiaoay.easy_dev_toolbox.page.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 调查空间用途文案锚：SpaceBar 的说明面（info Tooltip + 空态引导行）是空间
 * 用途的唯一说明载体，文案要件钉死防回潮/防夸大：
 *
 * 1. [SPACE_INFO_TOOLTIP] 要件：聚合容器定义（文件+会话+产物记同一空间名下）/
 *    归档收起语义 / 一键新建语义 / **自动记入承诺**（addFlows/addRuns 已接线
 *    ——tooltip 按终态写）；
 * 2. [SPACE_EMPTY_HINT] 如实性锚：深度分析路径保留 + 「发起会话或研判也会
 *    自动归入」——文案与接线行为必须同步，接线失则文案失实。
 */
class SpaceBarCopyTest {

    // ── 1 · Tooltip 详文要件 ──

    @Test
    fun `info tooltip defines space as aggregation container`() {
        assertTrue(
            "聚合容器" in SPACE_INFO_TOOLTIP,
            "Tooltip 必须给出「聚合容器」定义（空间的用途是什么）",
        )
        // 聚合三要素 + 空间归属措辞：文件、会话、产物都自动记入当前空间
        listOf("文件", "会话", "产物", "当前空间").forEach { word ->
            assertTrue(
                word in SPACE_INFO_TOOLTIP,
                "Tooltip 聚合定义缺「$word」——文件+会话+产物三要素与当前空间归属必须齐",
            )
        }
    }

    @Test
    fun `info tooltip explains archive semantics`() {
        assertTrue("归档" in SPACE_INFO_TOOLTIP, "Tooltip 必须说明归档语义（收起旧调查）")
        assertTrue("新建" in SPACE_INFO_TOOLTIP, "Tooltip 必须说明新调查一键新建语义")
    }

    @Test
    fun `info tooltip promises auto attribution now wired`() {
        // tooltip 承诺「会话创建/run 产物自动记入当前空间」——按终态写：
        // 「自动记入」承诺 + 两条写入路径（深度分析带入 / 会话研判自动归属）
        assertTrue(
            "自动记入" in SPACE_INFO_TOOLTIP,
            "Tooltip 必须承诺自动记入（addFlows/addRuns 已接线兑现）",
        )
        assertTrue(
            "深度分析" in SPACE_INFO_TOOLTIP && "自动归属" in SPACE_INFO_TOOLTIP,
            "Tooltip 必须写明两条写入路径：深度分析带入文件 + 会话/研判自动归属",
        )
    }

    // ── 2 · 空态引导如实性 ──

    @Test
    fun `empty hint points to the only wired ingestion path`() {
        // 真实路径锚：产物卡「深度分析」深链（成员写入链）
        assertTrue(
            "深度分析" in SPACE_EMPTY_HINT,
            "空态引导必须指向真实可达路径：产物卡「深度分析」深链",
        )
    }

    @Test
    fun `empty hint advertises auto attribution wired in this batch`() {
        // 「自动归入」引导：空态用户的首要疑问是「怎么聚东西进来」，
        // 会话/研判路径是第二真实入口
        assertTrue(
            "自动归入" in SPACE_EMPTY_HINT,
            "空态引导必须补「发起会话或研判也会自动归入」（addFlows/addRuns 已接线）",
        )
        assertTrue(
            "会话" in SPACE_EMPTY_HINT && "研判" in SPACE_EMPTY_HINT,
            "空态引导的自动归入必须点名两条路径：发起会话 / 研判",
        )
    }

    // ── 3 · 分层不变量：Tooltip 详文与底注简短版各司其职 ──

    @Test
    fun `tooltip long-form and footer short-form stay layered`() {
        // 底注简短版一句话讲清「空间是什么」；详文要件比底注多「归档/新建」语义
        // （正文经 SPACE_FOOTER 常量渲染——常量在即 UI 在，无需扫盘读源码）
        listOf("一次调查", "文件", "会话", "产物").forEach { word ->
            assertTrue(word in SPACE_FOOTER, "底注关键词「$word」缺失——底注必须讲清空间是什么")
        }
        // 分层不重复：底注不得再塞归档/新建/自动记入（详文职责），详文必须比底注长
        assertFalse("归档" in SPACE_FOOTER, "底注保持简短——归档语义归 Tooltip 详文")
        assertFalse(
            "自动" in SPACE_FOOTER,
            "底注保持简短——自动记入承诺归 Tooltip 详文（底注只讲空间是什么）",
        )
        assertTrue(
            SPACE_INFO_TOOLTIP.length > SPACE_FOOTER.length,
            "Tooltip 详文必须比底注承载更多（分层失效两头一样长）",
        )
    }
}
