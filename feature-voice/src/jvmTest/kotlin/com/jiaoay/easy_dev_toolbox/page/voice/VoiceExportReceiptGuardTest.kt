package com.jiaoay.easy_dev_toolbox.page.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 纪要导出回执守护：
 *
 * 1. 语音页：导出后卡内出现「已导出：<绝对路径>」等宽文本（exportedPath 局部态 +
 *    DesktopTypography.mono——路径类等宽先例 ui-style-guide §2.8）；
 * 2. 同构验收（文案模板）：两侧同为「已导出：$path」模板——蒸馏页当前 bodySmall、
 *    语音页 mono（蒸馏侧 mono 升级独立推进；落地后两态同构，本测模板断言不变）。
 */
class VoiceExportReceiptGuardTest {

    @Test
    fun `语音页导出回执接线完整`() {
        val page = voicePageSource()
        listOf(
            "exportedPath by remember { mutableStateOf<String?>(null) }" to
                "导出回执局部态（卡面原文方案——单纪要语义下重组重置可接受）",
            "vm.exportNote()?.let { f -> exportedPath = f.absolutePath }" to
                "导出钮必须把落盘文件绝对路径写进回执态",
            "\"已导出：\$path\"" to "回执文案模板（与蒸馏页同构）",
            "style = DesktopTypography.mono" to "回执路径必须 mono 等宽（ui-style-guide §2.8）",
        ).forEach { (needle, why) ->
            assertTrue(needle in page, "VoicePage.kt 丢失回执要素「$needle」——$why")
        }
    }

    @Test
    fun `蒸馏页回执文案模板同构`() {
        // 管理面 KbManagePanelContent 自 DistillPage 拆出——导出回执随迁 KbManagePanel.kt
        val distill = kbManagePanelSource()
        val template = "已导出：\$exportPath"
        assertTrue(
            template in distill,
            "KbManagePanel.kt 导出回执文案模板必须与语音页同构（「已导出：$template」）",
        )
        // 模板恰 1 处（防漂移出第三种形态）
        assertEquals(
            1,
            Regex(Regex.escape(template)).findAll(distill).count(),
            "蒸馏页导出回执应恰 1 处",
        )
    }

    // ── 源码锚 ──

    private fun voicePageSource(): String = readSource(
        "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoicePage.kt",
    )

    private fun kbManagePanelSource(): String = readSource(
        "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/KbManagePanel.kt",
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
}
