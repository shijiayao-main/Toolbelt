package com.jiaoay.easy_dev_toolbox.page.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AG-16 审阅卡 markdown 分层渲染接线断言（重设计批 4 · 2026-10-01）：
 *
 * 消费面锚（:ui 侧 MarkdownTextTest 已直测解析闭集与渲染件配方，本测只钉消费面）：
 * 1. Produced 分支主形态必须消费 MarkdownText（非 mono 平文）——层级可见性的接线真源；
 * 2. mono 原文态必须保留（「查看原文」切换——审阅语义 = 核验原文 vs 纪要，卡面
 *    「双栏对照列后置」的切换态先行）；
 * 3. 「放弃→取消」文案合规是 AG-10 批 8 面（已施工）——本测只钉 AG-16 面不扩改，
 *    文案守护见 VoiceReviewCopyGuardTest。
 */
class VoiceReviewMarkdownTest {

    @Test
    fun `review card renders minutes via markdown text with raw toggle wired`() {
        val source = voicePageSource()
        listOf(
            // 主形态 = MarkdownText 分层渲染（AG-16 接线真源——非等宽平文）
            "MarkdownText(markdown = s.markdown)" to
                "审阅卡纪要态必须消费 MarkdownText（AG-16：标题/列表层级可见）",
            // mono 原文态保留 + 切换钮（「查看原文/查看纪要」双态）
            "style = DesktopTypography.mono" to
                "mono 原文态必须保留（查看原文切换——核验原文 vs 纪要双态）",
            "if (showRaw) \"查看纪要\" else \"查看原文\"" to
                "切换钮文案必须双态（原文态→查看纪要 / 纪要态→查看原文）",
            "showRaw = !showRaw" to "切换钮必须翻转 showRaw（页面局部态，无 VM）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in source,
                "VoicePage.kt 丢失接线要素「$needle」——$why",
            )
        }
    }

    // ── 源码锚 ──

    private fun voicePageSource(): String {
        // 向上找 settings.gradle.kts 定项目根（GuardTestSupport/NavTest 同款配方）
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val file = File(dir, VOICE_PAGE_PATH)
                assertTrue(file.isFile, "源码锚文件缺失：$VOICE_PAGE_PATH")
                return file.readText()
            }
            dir = dir.parentFile
            hops++
        }
        error("未定位项目根（settings.gradle.kts）——:feature-voice:jvmTest 工作目录异常")
    }

    private companion object {
        const val VOICE_PAGE_PATH =
            "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoicePage.kt"
    }
}
