package com.jiaoay.easy_dev_toolbox.page.ai.distill

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XA-23（重设计批 8 · 2026-10-02）Distill 加载/微空态统一守护：
 *
 * 旧形态违反 §6.2 加载标准组合与空态组件优先：kbStats null / KbRepoBrowse browse
 * null 两处裸「读取中…」文字；四表（TAG/决策/黄金样本/文档）空四处裸「（空）」。
 * 本卡统一：读取中两处换行内圈（InlineLoadingRow——progressInline+strokeThin+
 * labelSmall，批 7 XA-07 EvidenceTimeline:273-287 配方照抄；紧凑场景 hero 圈过大）；
 * 四表空换 EmptyStateView(text="暂无内容", fillParent=false, icon=INBOX)（SectionCard
 * tonal=false 内窄场景 fillParent=false 保持行内紧凑）。
 *
 * 🔴 串行序（勘误 12）：AG-14 先（同函数区 KbRepoBrowse——结构面）→ XA-23 后
 * （空态面）——本守护与 DistillKbSearchFilterGuardTest 同文件域共存。
 *
 * 「知识库为空」已是 EmptyStateView（批 4 落）——本卡只补四表空态 + 两处 loading。
 * 渲染件无 Compose 运行时直测缝——源码切片是一等守护形态（DistillAbandonTest
 * 同款口径，剥注释扫描防回潮）。
 */
class DistillLoadingEmptyStateGuardTest {

    @Test
    fun `xa23 inline loading row replaces bare reading text`() {
        val source = stripKotlinComments(distillPageSource())
        listOf(
            "private fun InlineLoadingRow()" to "行内加载私有件在位（两处复用）",
            "Modifier.size(Dimens.progressInline)" to "行内圈尺寸 token（14dp——紧凑场景 hero 圈过大）",
            "strokeWidth = Dimens.strokeThin" to "描边 token（2dp——批 7 XA-07 配方）",
            "\"读取中…\"" to "加载文案保留（labelSmall 档）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "DistillPage 丢失 XA-23 要素「$needle」——$why")
        }
        // 裸「读取中…」Text 行（bodySmall+onSurfaceVariant 旧配方）零残留——
        // InlineLoadingRow 内唯一合法出现（labelSmall）
        val bareReading = Regex(
            """Text\("读取中…", style = MaterialTheme\.typography\.bodySmall""",
        ).findAll(source).count()
        assertEquals(0, bareReading, "裸「读取中…」bodySmall Text 零残留（行内圈统一）")
    }

    @Test
    fun `xa23 four table empty states unified to empty state view`() {
        val source = stripKotlinComments(distillPageSource())
        val emptyStateCount = Regex(
            """EmptyStateView\(text = "暂无内容", fillParent = false, icon = MaterialSymbols\.INBOX\)""",
        ).findAll(source).count()
        assertEquals(
            4,
            emptyStateCount,
            "四表空态 EmptyStateView 恰 4 处（TAG/决策/黄金样本/文档——「暂无内容」统一）",
        )
        assertFalse(
            "（空）" in source,
            "裸「（空）」文案零残留（无引导语义——EmptyStateView 统一，防回潮）",
        )
    }

    @Test
    fun `xa23 existing knowledge base empty state preserved`() {
        val source = stripKotlinComments(distillPageSource())
        // 「知识库为空」已是 EmptyStateView（批 4 落）——本卡不动，防回退锚
        assertTrue(
            "知识库为空" in source,
            "「知识库为空」EmptyStateView 保留（批 4 既有——本卡只补四表+loading）",
        )
    }

    // ── 源码锚基建（DistillAbandonTest 同款配方） ──

    // 蒸馏页 = DistillPage + KbManagePanel 两文件（管理面拆出后仍同页语义）——拼接扫描
    private fun distillPageSource(): String {
        val page = File(locateProjectRoot(), DISTILL_PAGE)
        assertTrue(page.isFile, "源码锚文件缺失：$DISTILL_PAGE")
        val manage = File(locateProjectRoot(), KB_MANAGE_PANEL)
        assertTrue(manage.isFile, "源码锚文件缺失：$KB_MANAGE_PANEL")
        return page.readText() + manage.readText()
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
        const val KB_MANAGE_PANEL =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/KbManagePanel.kt"
    }
}
