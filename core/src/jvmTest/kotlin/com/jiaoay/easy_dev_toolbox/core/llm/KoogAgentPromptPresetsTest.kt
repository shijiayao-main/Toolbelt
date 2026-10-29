// 2026-09-14 性格模式/安全约束批守护：内置档定义与解析契约。
//
// 契约（见 KoogAgentPromptPresets 类头）：
// 1. 档 id 全局唯一（两 enum 合并查重——UI 选择器以 id 为键）+ label 非空（中文展示文案）；
// 2. 默认安全档正文**逐字保留**原 SYSTEM_PROMPT_HEAD 安全底线句（提示词语义不因重构漂移）；
// 3. custom 档：空文本 → null（不注入空段，防提示词孤儿段头）；非空 → trim 后原文；
// 4. 未知 id 防御性回落 DEFAULT（配置被手工改坏不崩，静默回落默认档）；
// 5. 猫娘档正文必须含「该专业的地方照常专业」（防角色扮演侵蚀工具准确性——正文被后人改瘦的防漂移锚）。
package com.jiaoay.easy_dev_toolbox.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoogAgentPromptPresetsTest {

    @Test
    fun `style and safety preset ids are unique within each enum and labels non-blank`() {
        // 🔴 "default"/"custom" 两 id 是刻意跨 enum 共享的（两个独立选择器各自的缺省/自定义槽），
        //    合并查重要剔除共享档——只断言「各 enum 内部不重复」+「非共享 id 跨 enum 不撞」。
        val styleIds = KoogAgentStylePreset.entries.map { it.id }
        assertEquals(styleIds.size, styleIds.distinct().size, "性格档 id 不得重复")
        val safetyIds = KoogAgentSafetyPreset.entries.map { it.id }
        assertEquals(safetyIds.size, safetyIds.distinct().size, "安全档 id 不得重复")
        val shared = setOf("default", "custom")
        val nonShared = (styleIds + safetyIds).filterNot { it in shared }
        assertEquals(nonShared.size, nonShared.distinct().size, "非共享 id 跨 enum 不得撞")
        KoogAgentStylePreset.entries.forEach { assertTrue(it.label.isNotBlank(), "${it.id} label 不得为空") }
        KoogAgentSafetyPreset.entries.forEach { assertTrue(it.label.isNotBlank(), "${it.id} label 不得为空") }
    }

    @Test
    fun `default safety prompt preserves the original safety-baseline sentence verbatim`() {
        // 原 SYSTEM_PROMPT_HEAD 末行安全底线句——从 HEAD 段移入安全档时语义必须逐字继承
        assertTrue(
            "不要伪造工具结果；工具失败时如实告知用户并给出替代建议" in KoogAgentSafetyPreset.DEFAULT.prompt,
            "默认安全档必须逐字包含原安全底线句",
        )
        assertTrue(
            "[安全约束 - 不可违反]" in KoogAgentSafetyPreset.DEFAULT.prompt,
            "头部字面量与 CLI 后端 safetyPromptSuffix 同源",
        )
    }

    @Test
    fun `stylePrompt resolves builtin custom and unknown ids`() {
        // 内置档：返档正文
        assertEquals(
            KoogAgentStylePreset.CATGIRL.prompt,
            KoogAgentPromptPresets.stylePrompt("catgirl", "被忽略的自定义文本"),
        )
        // custom：空文本 → null（不注入空段）
        assertNull(KoogAgentPromptPresets.stylePrompt("custom", ""), "custom 空文本必须返 null")
        assertNull(KoogAgentPromptPresets.stylePrompt("custom", "   "), "custom 纯空白也必须返 null")
        // custom：非空 → trim 后原文（trim 容忍 UI 端空白，双写不丢）
        assertEquals("用猫娘口吻回复", KoogAgentPromptPresets.stylePrompt("custom", "  用猫娘口吻回复\n"))
        // 未知 id：防御性回落 DEFAULT
        assertEquals(
            KoogAgentStylePreset.DEFAULT.prompt,
            KoogAgentPromptPresets.stylePrompt("no-such-id", "x"),
            "未知 id 回落默认档（手工改坏配置不崩）",
        )
    }

    @Test
    fun `safetyPrompt mirrors style resolution semantics`() {
        assertEquals(KoogAgentSafetyPreset.DEFAULT.prompt, KoogAgentPromptPresets.safetyPrompt("default", ""))
        assertNull(KoogAgentPromptPresets.safetyPrompt("custom", ""))
        assertEquals("只许读取", KoogAgentPromptPresets.safetyPrompt("custom", " 只许读取 "))
        assertEquals(KoogAgentSafetyPreset.DEFAULT.prompt, KoogAgentPromptPresets.safetyPrompt("bogus", ""))
    }

    @Test
    fun `catgirl preset anchors tool-accuracy clause against drift`() {
        assertTrue(
            "该专业的地方照常专业" in KoogAgentStylePreset.CATGIRL.prompt,
            "猫娘档必须保留「该专业的地方照常专业」——角色扮演不得侵蚀工具调用与结论准确性",
        )
        // 各内置档正文非空且以段头自我说明（custom 档除外——正文走自定义 key）
        KoogAgentStylePreset.entries.filter { it != KoogAgentStylePreset.CUSTOM }.forEach {
            assertTrue(it.prompt.isNotBlank(), "${it.id} 内置档正文不得为空")
            assertTrue("性格模式" in it.prompt, "${it.id} 段头应自我说明是性格模式设定")
        }
    }

    @Test
    fun `builtin prompts are pairwise distinct`() {
        val prompts = KoogAgentStylePreset.entries.filter { it != KoogAgentStylePreset.CUSTOM }.map { it.prompt }
        prompts.zipWithNext().forEach { (a, b) ->
            assertNotEquals(a, b, "各内置性格档正文必须互不相同（档位才有区分度）")
        }
    }

    // ── 10 档扩档批（2026-09-14）：四段结构防漂移锚 ──
    //
    // 每档正文统一四段结构（性格定义/表达方式/优先级约束/技术内容纪律）——主人提供的
    // 设定稿模板；「正经点」是优先级段的正经模式收口锚。逐句锚会让后人改正文时疲于
    // 奔命，段头锚保结构、逐档关键句靠评审。

    @Test
    fun `每档正文含四段结构锚（性格定义-表达方式-优先级约束-技术内容纪律）`() {
        KoogAgentStylePreset.entries.filter { it != KoogAgentStylePreset.CUSTOM }.forEach {
            assertTrue("性格定义" in it.prompt, "${it.id} 必须含性格定义段")
            assertTrue("表达方式" in it.prompt, "${it.id} 必须含表达方式段")
            assertTrue("优先级约束" in it.prompt, "${it.id} 必须含优先级约束段")
            assertTrue("技术内容纪律" in it.prompt, "${it.id} 必须含技术内容纪律段")
            assertTrue("正经点" in it.prompt, "${it.id} 优先级段必须含正经模式收口语义")
        }
    }

    @Test
    fun `猫娘档含只在对话内称呼主人的语义锚`() {
        // per-user 性格批（2026-09-14）需求锚：「我不喜欢我的猫娘管别人叫主人」——
        // per-user 注入的猫娘档正文必须声明「只在和你的对话里这么称呼」
        assertTrue(
            "只在和你的对话里这么称呼" in KoogAgentStylePreset.CATGIRL.prompt,
            "猫娘档必须含「只在和你的对话里称呼主人」语义（per-user 注入的身份边界）",
        )
    }

    // ── SYS-21 · 重设计批 6（2026-10-01）：blurb 画像行防漂移锚 ──
    //
    // 卡面配方：每档一句话画像（人话描述，非提示词工程产物）——chip 标签 2-3 字
    // 无画像，非创作者用户无法低成本预期效果。DEFAULT/CUSTOM 空串（默认档无需
    // 自我画像、自定义档画像由用户自己写）。

    @Test
    fun `builtin style presets carry non-blank blurb except default and custom`() {
        KoogAgentStylePreset.entries
            .filter { it != KoogAgentStylePreset.DEFAULT && it != KoogAgentStylePreset.CUSTOM }
            .forEach {
                assertTrue(it.blurb.isNotBlank(), "${it.id} 内置档 blurb 画像不得为空（SYS-21：每档一句话画像）")
                assertTrue(
                    it.blurb.length <= 40,
                    "${it.id} blurb 画像必须是一句话（≤40 字，当前 ${it.blurb.length} 字）——画像行不是第二段正文",
                )
                assertNotEquals(
                    it.prompt, it.blurb,
                    "${it.id} blurb 不得与 prompt 同文——画像是人话描述非提示词工程产物",
                )
            }
        assertEquals("", KoogAgentStylePreset.DEFAULT.blurb, "默认档 blurb 空串（无需自我画像）")
        assertEquals("", KoogAgentStylePreset.CUSTOM.blurb, "自定义档 blurb 空串（画像由用户自写）")
    }

    @Test
    fun `style preset blurbs are pairwise distinct`() {
        val blurbs = KoogAgentStylePreset.entries.filter { it.blurb.isNotBlank() }.map { it.blurb }
        assertEquals(blurbs.size, blurbs.distinct().size, "各档画像必须互不相同（画像才有档位区分度）")
    }
}
