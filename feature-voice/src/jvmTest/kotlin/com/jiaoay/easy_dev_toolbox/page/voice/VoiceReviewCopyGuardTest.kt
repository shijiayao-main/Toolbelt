package com.jiaoay.easy_dev_toolbox.page.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 审阅卡文案合规 + 死缝清理守护：
 *
 * 1. 审阅卡次钮文案 = 「取消」（design-language §4.2：对话框 dismiss 恒「取消」——
 *    「放弃」语义过重，用户可能误以为纪要已产出且被丢弃）；整理进行中卡
 *    （Running 态）confirmButton 同为「取消」（dismissReview 在 Running 态即
 *    session.cancel 收口）；
 * 2. 失败卡零「知道了」（泛化 OK 词族禁用）——主钮同为
 *    「取消」（dismissReview），失败重试走任务卡行内「AI 整理」再点；
 * 3. 死缝防回潮：retrySummarize / summarizeTodoBoard 双死面（全仓零消费）已从
 *    VoiceViewModel 删除，断言不得回潮（session.retry 保留——session
 *    API 面，不在本守护 scope）；
 * 4. 不写回语义不回归：dismissReview → session.cancel + 清 summarizingFor——
 *    acceptedNote 恒 null 的生产写点只有 acceptNote（VoiceAttributionTest 语义测背书）。
 */
class VoiceReviewCopyGuardTest {

    @Test
    fun `审阅卡与失败卡文案合规`() {
        val page = stripKotlinComments(voicePageSource())
        // 「放弃」「知道了」防回潮（注释态也被剥——字面量残留即红）
        assertFalse("放弃" in page, "VoicePage.kt 不得再现「放弃」（dismiss 恒「取消」）")
        assertFalse("知道了" in page, "VoicePage.kt 不得再现「知道了」（泛化 OK 词族禁用）")
        // 整理进行中卡 confirmButton + 审阅卡 dismissButton + 失败卡 confirmButton 各一枚「取消」（count == 3）
        assertEquals(
            3,
            Regex("Text\\(\"取消\"\\)").findAll(page).count(),
            "VoicePage.kt 应恰有 3 处 Text(\"取消\")（整理进行中卡 confirmButton + 审阅卡 dismissButton + 失败卡 confirmButton）",
        )
        // 「放弃」壳件 RowDismissButtons 已退役（dismissButton 槽内联）
        assertFalse("RowDismissButtons" in page, "RowDismissButtons 死壳应已退役（内联取消钮）")
    }

    @Test
    fun `取消后纪要不写回语义接线不变`() {
        val page = voicePageSource()
        // 三卡 onClick 恒 dismissReview（不采纳不写回——acceptedNote 恒 null 语义；
        // Running 态 dismissReview 即 session.cancel 收口）
        assertEquals(
            3,
            Regex("onClick = vm::dismissReview").findAll(page).count(),
            "整理进行中卡 confirmButton、审阅卡 dismissButton 与失败卡 confirmButton 都必须挂 dismissReview（不写回语义）",
        )
        assertTrue("onDismissRequest = vm::dismissReview" in page, "审阅卡 Esc 关闭也应走 dismissReview")
    }

    @Test
    fun `VM 死缝防回潮`() {
        val vm = voiceViewModelSource()
        assertFalse("retrySummarize" in vm, "VoiceViewModel.retrySummarize 死缝已删——不得回潮")
        assertFalse("summarizeTodoBoard" in vm, "VoiceViewModel.summarizeTodoBoard 死属性已删——不得回潮")
        // 采纳写点唯一性：dismissReview 仍走 session.cancel + 清 summarizingFor
        assertTrue("summarizeSession.cancel()" in vm, "dismissReview 不写回语义（session.cancel）必须保留")
    }

    // ── 源码锚 ──

    private fun voicePageSource(): String = readSource(
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoicePage.kt",
    )

    private fun voiceViewModelSource(): String = readSource(
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoiceViewModel.kt",
    )

    private fun readSource(relativePath: String): String {
        // 向上找 settings.gradle.kts 定项目根（GuardTestSupport/VoiceReviewMarkdownTest 同款配方）
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val file = File(dir, relativePath)
                assertTrue(file.isFile, "源码锚文件缺失：$relativePath")
                return file.readText()
            }
            dir = dir.parentFile
            hops++
        }
        error("未定位项目根（settings.gradle.kts）——:feature-voice:jvmTest 工作目录异常")
    }

    /** 剥除行注释与块注释，保留代码与字符串字面量（配方照抄 UiArchitectureGuardTest）。 */
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
}
