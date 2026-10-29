package com.jiaoay.easy_dev_toolbox.page.dialog

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * XA-19 源码锚守护（重设计批 11 · 2026-10-02）：对话框 dismiss 措辞统一「取消」。
 *
 * design-language §4.2「次钮『取消』恒用『取消』；禁『关闭/不了/返回』混用」——
 * 本守护钉死残余三行清账后不回潮（批 8 已清 VoicePage「放弃」，卡面 ① 归 AG-10）：
 *
 * 1. RegisterAppDialog（feature-larkbridge）：confirmButton else 分支（Idle/进行中态
 *    单钮）+ dismissButton（Failed 态）两处「关闭」→「取消」；
 * 2. AiSettingsPage MemoryManagerDialog（feature-ai）：confirmButton「关闭」→「取消」。
 *
 * 合法「关闭」豁免面（**不在**断言范围，动它们不该红）：
 * - ShellDebugPage:163 Interactive 会话终止钮（STOP 图标 + error 底动作钮，非对话框
 *   dismissButton——2026-09-28 复核裁定移出 scope）；
 * - AppTitleBar 窗控圆点 / StatusHost 浮层语义 / 描述文案（非按钮面）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :composeApp:jvmTest --rerun-tasks --no-build-cache`。
 */
class DialogDismissWordingGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun readSource(relativePath: String): String {
        val file = File(projectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `register app dialog buttons say cancel`() {
        val text = readSource(
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/RegisterAppDialog.kt",
        )
        // 卡面验收：对话框按钮面零「关闭」（confirmButton else 分支 + dismissButton 两处）
        assertTrue(
            "Text(\"关闭\")" !in text,
            "XA-19 守护：RegisterAppDialog 按钮面残留「关闭」——对话框次钮措辞恒「取消」" +
                "（design-language §4.2 禁混用；重试钮等非 dismiss 语义钮除外）",
        )
        val cancelCount = Regex("""Text\("取消"\)""").findAll(text).count()
        assertTrue(
            cancelCount >= 2,
            "XA-19 守护：RegisterAppDialog 须有两处「取消」钮（confirmButton else 分支 + dismissButton Failed 态）" +
                "——实得 $cancelCount 处",
        )
    }

    @Test
    fun `memory manager dialog confirm button says cancel`() {
        val text = readSource(
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/AiSettingsPage.kt",
        )
        val dialogAnchor = text.indexOf("private fun MemoryManagerDialog(")
        assertTrue(dialogAnchor >= 0, "MemoryManagerDialog 须落 AiSettingsPage.kt 同文件（SYS-02 守护同锚）")
        val dialogBlock = text.substring(dialogAnchor)
        assertTrue(
            "Text(\"关闭\")" !in dialogBlock,
            "XA-19 守护：MemoryManagerDialog 按钮面残留「关闭」——对话框次钮措辞恒「取消」" +
                "（design-language §4.2 禁混用）",
        )
        assertTrue(
            "\"取消\"" in dialogBlock,
            "XA-19 守护：MemoryManagerDialog 须显「取消」（蜂群回归 2026-10-06 P2 对稿 " +
                "system.html:754：取消钮自 M3 confirmButton 槽移入底排双钮行——措辞纪律不变）",
        )
    }

    @Test
    fun `voice page review card dismiss stays cancel`() {
        val text = readSource(
            "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/VoicePage.kt",
        )
        // 批 8 AG-10 已落地（VoiceReviewCopyGuardTest 行为面双守护）——本锚防文案面回潮
        assertTrue(
            "Text(\"放弃\")" !in text,
            "XA-19 守护：VoicePage 审阅卡 dismissButton「放弃」不得回潮（批 8 AG-10 已改「取消」）",
        )
    }
}
