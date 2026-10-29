package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LB-19 / LB-09 守护（重设计批 9 · 2026-10-02）：能力中心卡形态与后台三件套分化。
 *
 * 锚文件 = [BOT_CAPABILITY_CARD]（本簇唯一生产文件——LB-19 卡头/小节题 + LB-09
 * GrantTrioRow 按钮分化，同文件两卡同守护，逐卡 commit 分批追加）。归一化 =
 * 剥空白 + 剥注释行（施工注释属历史记述，锚只对代码字面）。
 *
 * 1. **LB-19 能力卡补卡头**：BotCardHeader 参数化（title/pillText 两参）单源复用；
 *    BotCapabilityCard 侧头部「能力中心」+ 灰 pill「常备 N · 跨会话 6」；
 *    BotIdentityCard 调用点传原值（「机器人主卡」+「本地 AI · agent-first」）零视觉变化；
 *    小节题「常备工具（N 项）」（旧「能力清单（N 项常备）」防回潮清零）。
 * 2. **LB-09 三件套按钮分化**：backendUrlOf 增 page 参；「创建版本并发布」降级文案
 *    「发版：后台 → 版本管理与发布 ↗」仍跳权限页（名实相符）；旧文案防回潮清零。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 * PillLabelGuardTest 的 surfaceDeep/aiAccentContainer 断言零破坏（参数化不动色对）——
 * 同族守护彼处照跑，本文件不重复断言。
 */
class LbGuardCapabilityCardTest {

    // ── LB-19：卡头参数化 + 能力中心卡补头 ──

    @Test
    fun `lb19 bot card header is parameterized and both call sites pass titles`() {
        val code = normalizedCode(BOT_CAPABILITY_CARD)
        // 参数化签名（无参硬编码形态防回潮）；终验 review P2：色对 (pillContainer, pillContent)
        // 一并参数化——共享头不再挪用 aiAccent 槽令姊妹卡无法靠色区分语义
        assertTrue(
            "private fun BotCardHeader(" in code &&
                "title: String," in code &&
                "pillText: String," in code &&
                "pillContainer: Color," in code &&
                "pillContent: Color," in code,
            "BotCardHeader 必须参数化 (title, pillText, pillContainer, pillContent)（LB-19 + 终验色对参数化）",
        )
        assertFalse(
            "BotCardHeader()" in code,
            "无参 BotCardHeader() 调用不得残留（LB-19：全部调用点显式传 title/pillText）",
        )
        // 机器人主卡调用点：原值零视觉变化（aiAccent 紫身份面）
        assertTrue(
            "pillContainer = LocalEasyToolboxColors.current.aiAccentContainer" in code &&
                "pillContent = LocalEasyToolboxColors.current.aiAccent" in code,
            "BotIdentityCard 调用点必须传 aiAccent 色对（机器人主卡 AI 身份面语义）",
        )
        // 能力中心调用点：新头 + 动态计数 pill + 中性灰色对（LB-19 卡面/稿面双指定灰）
        assertTrue(
            "BotCardHeader(" in code && "title = \"能力中心\"," in code,
            "BotCapabilityCard 必须有「能力中心」头部行（LB-19 验收：卡有标题行且称谓为能力中心）",
        )
        assertTrue(
            "\"常备 \${tools.size} · 跨会话 \${NativeToolGrant.entries.size}\"" in code,
            "能力卡 pill 必须动态计数「常备 N · 跨会话 6」（tools.size + NativeToolGrant.entries.size 数据源现成）",
        )
        assertTrue(
            "pillContainer = surfaceColors.surfaceDeep" in code &&
                "pillContent = MaterialTheme.colorScheme.onSurfaceVariant" in code,
            "能力中心 pill 必须中性灰 (.pill.mute: surfaceDeep 底 + onSurfaceVariant 字)——终验 P2：紫 pill 与机器人主卡无法靠色区分",
        )
    }

    @Test
    fun `lb19 capability section title renamed to standing tools`() {
        val code = normalizedCode(BOT_CAPABILITY_CARD)
        assertTrue(
            "\"常备工具（\${tools.size} 项）\"" in code,
            "小节题必须改「常备工具（N 项）」（LB-19：称谓与 v2 设计真源统一）",
        )
        assertFalse(
            "能力清单（" in code,
            "旧小节题「能力清单（N 项常备）」不得残留（LB-19 防回潮）",
        )
    }

    // ── LB-09：后台三件套按钮分化 ──

    @Test
    fun `lb09 release button downgraded with honest label and page param`() {
        val code = normalizedCode(BOT_CAPABILITY_CARD)
        // backendUrlOf 增 page 参（默认 security 权限页）
        assertTrue(
            "page: String = \"security\"" in code,
            "backendUrlOf 必须增 page 参（默认 security）（LB-09：两钮不再同落点的前置形态）",
        )
        // 降级文案：名实相符（仍跳权限页）
        assertTrue(
            "\"发版：后台 → 版本管理与发布 ↗\"" in code,
            "版本钮必须降级文案「发版：后台 → 版本管理与发布 ↗」（LB-09：真机确认前名实相符）",
        )
        assertFalse(
            "创建版本并发布" in code,
            "旧文案「创建版本并发布」不得残留（LB-09：点开是权限页，承诺与落点不符——防回潮）",
        )
        // KDoc 待确认清单：版本页 deep link 形态登记（KDoc 被归一化剥除——对原文全量断言）
        val raw = file(BOT_CAPABILITY_CARD).readText()
        assertTrue(
            "appVersion?/v2/distribute" in raw,
            "KDoc 必须登记版本页 deep link 待确认形态（LB-09 验收：真机确认项登记）",
        )
    }

    // ── 基建 ──

    /** 归一化：剥行注释 + 剥块注释 + 压空白——锚只对代码字面（施工注释属历史记述不计）。 */
    private fun normalizedCode(relPath: String): String =
        file(relPath).readText()
            .replace(Regex("/\\*+[\\s\\S]*?\\*/"), "") // 块注释（含 KDoc）
            .lineSequence()
            .map { it.substringBefore("//").trim() } // 行注释（含行尾）
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun file(relPath: String): File {
        val f = File(projectRoot(), relPath)
        assertTrue(f.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return f
    }

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val BOT_CAPABILITY_CARD =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/BotCapabilityCard.kt"
    }
}
