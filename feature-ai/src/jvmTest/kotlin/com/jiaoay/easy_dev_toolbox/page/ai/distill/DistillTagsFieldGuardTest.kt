package com.jiaoay.easy_dev_toolbox.page.ai.distill

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AG-07（重设计批 8 · 2026-10-02）蒸馏 entry hints 改 TagsField 逐行编辑守护：
 *
 * 旧形态 OutlinedTextField 受控 join/split 回写（value = entryHints.joinToString("、")
 * + onValueChange = split("、", ",", " ") 回写 List）——输入顿号瞬间整框重排、光标
 * 跳尾（受控组件把用户输入与状态串行化）；:ui 现成 TagsField（逐行 hover 删除 +
 * 回车追加，Quest 审阅批已验形态）正对此场景。渲染件无 Compose 运行时直测缝
 * （feature-ai 无 skiko 运行时——DistillAbandonTest 源码锚同款口径），源码切片
 * 是一等守护形态：
 *
 * - **join/split 回写面退役**：DistillPage 归一化后不含 `entryHints.joinToString`
 *   （渲染侧 join）与 split 回写（防回潮）；
 * - **TagsField 接线**：`onTagsChange = vm::setEntryHints` List 直通（VM 签名
 *   List<String> 零转换）；
 * - **prompt 拼接零回归**：DistillSession.kt `entryHints.joinToString("、")` 在位
 *   （List 直通不回归——DistillSessionTest hints 用例照跑背书行为面）；
 * - **placeholder 语义**：TagsField 无 placeholder 参数——原 placeholder 语义并入
 *   supportingText（卡面偏离如实上报锚：断 supportingText 文案含「逐条回车添加」）。
 */
class DistillTagsFieldGuardTest {

    @Test
    fun `ag07 entry hints join split roundtrip retired from distill page`() {
        val source = stripKotlinComments(distillPageSource())
        assertFalse(
            "entryHints.joinToString" in source,
            "entryHints.joinToString 受控回写必须退役（AG-07：顿号整框重排/光标跳尾根因——" +
                "TagsField 逐行渲染替代；VM 侧 DistillSession 拼接不在此断言面）",
        )
        assertFalse(
            "vm.setEntryHints(text.split" in source,
            "split 回写链必须退役（AG-07：onTagsChange List 直通替代——split(\",\"/\" \")" +
                "三路分割把空格也当分隔符，含空格 hint 无法输入）",
        )
    }

    @Test
    fun `ag07 tags field wired with direct list passthrough`() {
        val source = stripKotlinComments(distillPageSource())
        listOf(
            "TagsField(" to "entry hints 渲染收口 TagsField（ui/widget 现成件——acceptance 逐条增删无重排）",
            "tags = entryHints" to "入参 tags=entryHints（List 直通零转换）",
            "onTagsChange = vm::setEntryHints" to "onTagsChange 直通 VM（setEntryHints 已是 List<String> 签名——零 VM 改动）",
            "入口提示（可选）" to "label 文案沿用（卡面原文）",
            "留空时 agent 从功能描述自行探索（自搜兜底）" to "supportingText 文案沿用（原 placeholder 语义并入——TagsField 无 placeholder 参数）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "DistillPage 丢失 AG-07 要素「$needle」——$why")
        }
    }

    @Test
    fun `ag07 distill session prompt joining unaffected`() {
        // prompt 拼接面零回归：DistillSession entryHints joinToString("、") 在位
        // （TagsField 增删只变 List 元素，拼接面不感知——acceptance 第二项）
        val sessionSource = stripKotlinComments(distillSessionSource())
        assertTrue(
            "entryHints.joinToString" in sessionSource,
            "DistillSession prompt 拼接 joinToString(「、」) 必须在位（List 直通零回归——" +
                "hints 以 List<String> 原样进 prompt）",
        )
    }

    // ── 源码锚基建（DistillAbandonTest 同款配方） ──

    private fun distillPageSource(): String = sourceFile(DISTILL_PAGE).readText()

    private fun distillSessionSource(): String = sourceFile(DISTILL_SESSION).readText()

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

    /** 剥除行注释与块注释（字符串感知）——DistillAbandonTest.stripKotlinComments 同款配方 */
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
        const val DISTILL_PAGE =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillPage.kt"
        const val DISTILL_SESSION =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillSession.kt"
    }
}
