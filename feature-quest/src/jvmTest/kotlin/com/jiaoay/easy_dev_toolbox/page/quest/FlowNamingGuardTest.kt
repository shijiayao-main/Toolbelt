// 重设计批 8 · AG-01 称谓单源化守护（2026-10-02）：「流程」全消费面改「会话」。
//
// 卡面 acceptance：
// 1. grep FlowPage.kt/QuestPrompts.kt 零「流程」用户可见字面量残留（KDoc 注释不限）；
// 2. 深入模式打开独立页页头 =「会话」，与聚合页第一签名逐字一致（ToolPageLabels.Flow
//    单源 = composeApp 域，feature-quest 不可 import——卡面裁定直写字面量 + 注释锚双改）。
//
// 扫描口径（KnowledgeUriPrefixSingleSourceGuardTest / DistillAbandonTest 同款配方）：
// - stripKotlinComments 剥注释后扫字符串字面量——注释里的历史叙述（「流程域」KDoc、
//   改动说明）不误伤；raw string（SYSTEM_PROMPT）内容保留可断；
// - 🔴 CommandPalette.kt:242 desc「主动流程」是功能性描述非页面称谓，不在清账面
//   （侦察勘误 1 裁定，composeApp 域也不属本守护扫描面）；
// - QuestAbilityPrompts.kt「本流程」是 prompt 任务主语非页面称谓，不在卡面 scope。
//
// 🔴 验收纪律：扫盘型守护会被 build cache 假绿（AGENTS.md「测试」节），
// 一律 --rerun-tasks --no-build-cache 验收。
package com.jiaoay.easy_dev_toolbox.page.quest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class FlowNamingGuardTest {

    /** A：FlowPage.kt 剥注释后零「流程」——用户可见称谓面清账（字符串字面量口径） */
    @Test
    fun `A flow page has zero 流程 literal in string state`() {
        val code = stripKotlinComments(sourceFile(FLOW_PAGE).readText())
        val hits = Regex("流程").findAll(code).toList()
        if (hits.isNotEmpty()) {
            fail(
                "FlowPage.kt 用户可见「流程」字面量残留 ${hits.size} 处（AG-01 称谓单源化回潮）——" +
                    "页面称谓统一「会话」（单源=ToolPageLabels.Flow，DL-M23）。" +
                    "若是新功能性描述（非页面称谓），先核 proposals.yaml AG-01 裁定再同步本守护。",
            )
        }
    }

    /** B：QuestPrompts.kt raw string（SYSTEM_PROMPT）内零「流程」——agent 自称与页面称谓一致 */
    @Test
    fun `B quest prompts system prompt has zero 流程`() {
        val code = stripKotlinComments(sourceFile(QUEST_PROMPTS).readText())
        val hits = Regex("流程").findAll(code).toList()
        if (hits.isNotEmpty()) {
            fail(
                "QuestPrompts.kt SYSTEM_PROMPT 内「流程」残留 ${hits.size} 处——agent 自称须与页面称谓「会话」一致（AG-01）。",
            )
        }
    }

    /** C：称谓「会话」五面锚在位（页头 + 空态 + 双对话框 + 降级文案） */
    @Test
    fun `C session naming anchors present`() {
        val page = stripKotlinComments(sourceFile(FLOW_PAGE).readText())
        // 独立页页头（深入模式/动态 tab 直达）——与 ToolPageLabels.Flow 逐字一致
        assertTrue("title = \"会话\"" in page, "FlowPage 独立页页头 title 必须为「会话」（DL-M23 与 ToolPageLabels 逐字一致）")
        // 空态（XA-18① 合并：EmptyStateView 形态 + 本卡文案）
        assertTrue("EmptyStateView(" in page, "空态必须 EmptyStateView 形态（XA-18① 合并施工）")
        assertTrue("尚无会话" in page, "空态主文案「尚无会话」在位")
        assertTrue("FORUM" in page, "空态 icon=FORUM（与侧栏槽位「历史会话」同形——FlowContributions 同款）")
        assertTrue("fillParent = false" in page, "侧栏空态 fillParent=false（verticalScroll Column 内行内紧凑）")
        // 双对话框 + AI 降级文案
        assertTrue("重命名会话" in page, "重命名对话框 title「重命名会话」在位")
        assertTrue("删除会话" in page, "删除确认 title「删除会话」在位")
        assertTrue("开启后再开始会话" in page, "AI 关闭降级文案「…开始会话」在位")
        // prompt 自称
        val prompts = stripKotlinComments(sourceFile(QUEST_PROMPTS).readText())
        assertTrue("「会话」助手" in prompts, "SYSTEM_PROMPT 自称「会话」助手在位")
    }

    // ── 源码锚扫盘基建（DistillAbandonTest 同款配方） ──

    private fun sourceFile(relativePath: String): File {
        val file = File(locateProjectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后请同步本测试路径）")
        return file
    }

    private fun locateProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    /**
     * 剥除行注释（//）与块注释（/** */、/* */），保留代码与字符串字面量内容——字符串感知
     * （`"..."`/`"""..."""` 各自成态，字符串态内不识别注释起始），配方照抄
     * KnowledgeUriPrefixSingleSourceGuardTest.stripKotlinComments（UiArchitectureGuardTest 同款）。
     */
    private fun stripKotlinComments(source: String): String {
        val sb = StringBuilder(source.length)
        var inString = false
        var inRawString = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                inRawString -> when {
                    c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        sb.append("\"\"\"")
                        i += 3
                        inRawString = false
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }

                inString -> when {
                    c == '\\' && i + 1 < source.length -> {
                        sb.append(c).append(source[i + 1])
                        i += 2
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = false
                        i++
                    }

                    c == '\n' -> {
                        sb.append(c)
                        inString = false
                        i++
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }

                else -> when {
                    c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        sb.append("\"\"\"")
                        i += 3
                        inRawString = true
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = true
                        i++
                    }

                    c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                        while (i < source.length && source[i] != '\n') i++
                    }

                    c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                        i += 2
                        while (i < source.length) {
                            if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') {
                                i += 2
                                break
                            }
                            if (source[i] == '\n') sb.append('\n')
                            i++
                        }
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
        return sb.toString()
    }

    private companion object {
        const val FLOW_PAGE =
            "feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/FlowPage.kt"
        const val QUEST_PROMPTS =
            "feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/QuestPrompts.kt"
    }
}
