package com.jiaoay.easy_dev_toolbox.page.ai.distill

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AG-08（重设计批 8 · 2026-10-02）蒸馏页按钮语言统一 AppButton 守护：
 *
 * 同页混用四代按钮语言（裸 Button/OutlinedButton/TextButton 对 AppButton）——
 * E2/E3 收口（R3 2026-09-25「禁静默置灰」）只做了表单主钮半途而废。本卡统一：
 * 「接受」Primary、「驳回/查看入库结果/重新探索/重新开始/导出 KnowledgePack JSON」
 * Secondary；「中止」钮按侦察勘误 6b 走 TextButton-error 最小对齐（EvidenceTimeline:264
 * 逐字同款——🔴 卡面 AppButton(cancel=onCancel) 方案语义错：cancel 只在 busy=true
 * 渲染，照抄=「执行中主钮+停止次钮」双钮结构，AppButton.kt:105）。
 *
 * 豁免面（卡面 acceptance 明文）：KbRepoBrowse 展开行内「删除」TextButton 保留
 * （行内小钮）；🔴 「导出 KnowledgePack JSON」在 KbManagePanelContent 面板级非
 * 展开行内，不豁免（如实上报判定）。
 *
 * 渲染件无 Compose 运行时直测缝——源码切片是一等守护形态（DistillAbandonTest
 * 同款口径）。断言面：
 * - **裸钮零残留**：归一化后 `Button(onClick`/`OutlinedButton(onClick` 零命中
 *   （material3 顶层 import 已清退——断 import 行本身）；
 * - **豁免清单锚**：两处全限定 `androidx.compose.material3.TextButton(` 在位
 *   （KbRepoBrowse 行内删除 + 中止 TextButton-error）；
 * - **AppButton 消费面**：Primary ≥ 2（开始探索+接受）、Secondary 消费在位。
 */
class DistillAppButtonGuardTest {

    @Test
    fun `ag08 bare material3 buttons retired from distill page`() {
        val source = stripKotlinComments(distillPageSource())
        assertFalse(
            "import androidx.compose.material3.Button" in source,
            "material3.Button import 必须清退（AG-08 按钮语言统一——裸 Button 零残留）",
        )
        assertFalse(
            "import androidx.compose.material3.OutlinedButton" in source,
            "material3.OutlinedButton import 必须清退（AG-08——裸 OutlinedButton 零残留）",
        )
        assertFalse(
            Regex("(?<![A-Za-z])Button\\(onClick").containsMatchIn(source),
            "裸 Button(onClick 调用零残留（AppButton 是唯一实底/描边钮——审阅卡「接受」已收 Primary；" +
                "🔴 词边界断言：TextButton( 含子串 Button(onClick 须排除——豁免钮不在断言面）",
        )
        assertFalse(
            Regex("(?<![A-Za-z])OutlinedButton\\(onClick").containsMatchIn(source),
            "裸 OutlinedButton(onClick 调用零残留（AppButton Secondary 替代——查看入库结果/" +
                "重新探索/重新开始/驳回/导出五处）",
        )
    }

    @Test
    fun `ag08 app button conversions wired with kinds`() {
        val source = stripKotlinComments(distillPageSource())
        listOf(
            "label = \"查看入库结果\"" to "REVIEW 成品查看钮统一 AppButton(Secondary)",
            "label = \"导出 KnowledgePack JSON\"" to "KbManagePanelContent 面板级导出钮统一 AppButton(Secondary)——" +
                "非 KbRepoBrowse 展开行内，不落豁免（如实上报判定）",
            "label = \"重新探索\"" to "PLAN_REVIEW 次钮统一 AppButton(Secondary)",
            "label = \"重新开始\"" to "REVIEW 重开钮统一 AppButton(Secondary)",
            "label = \"接受\"" to "审阅卡主钮统一 AppButton(Primary)",
            "label = \"驳回\"" to "审阅卡次钮统一 AppButton(Secondary)",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "DistillPage 丢失 AG-08 要素「$needle」——$why")
        }
        val primaryCount = Regex("kind = AppButtonKind\\.Primary").findAll(source).count()
        assertTrue(
            primaryCount >= 2,
            "AppButtonKind.Primary 消费点须 ≥ 2（开始探索 + 审阅卡接受）——实测 $primaryCount",
        )
    }

    @Test
    fun `ag08 exempt inline text buttons remain fully qualified`() {
        val source = distillPageSource()
        // 2026-10-03 对稿收尾重锚：两处豁免已兑现——「删除」（KbRepoBrowse 行内→
        // Secondary，d5 申报「error 语义由 AppConfirmDialog
        // 承担」）与「中止」（运行面板→Destructive，AG-08 停止语义）；全限定裸
        // TextButton 恒 0 处——回潮即测试红。
        val exemptCount = Regex("androidx\\.compose\\.material3\\.TextButton\\(").findAll(source).count()
        assertEquals(
            0,
            exemptCount,
            "全限定裸 TextButton 恒 0 处（2026-10-03 AG-08 d5 收尾——两处豁免已兑现" +
                "AppButton(Destructive)）——实测 $exemptCount",
        )
    }

    // ── 源码锚基建（DistillAbandonTest 同款配方） ──

    // 蒸馏页 = DistillPage + KbManagePanel 两文件（管理面拆出后仍同页语义）——拼接扫描
    private fun distillPageSource(): String =
        sourceFile(DISTILL_PAGE).readText() + sourceFile(KB_MANAGE_PANEL).readText()

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
        const val KB_MANAGE_PANEL =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/KbManagePanel.kt"
    }
}
