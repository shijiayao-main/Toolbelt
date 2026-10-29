package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 发送者详情「信任状态」卡形态守护。
 *
 * 验收面（准入白名单退役后）：
 * 1. 信任态胶囊在位（已信任/未信任）；
 * 2. 单钮翻转（加入/移出信任名单，条件式 label）；
 * 3. 两动作接线在位；
 * 4. 🔴 旧白/黑名单按钮与四态文案必须清零（防复辟）。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class LbGuardUsageStatsTest {

    @Test
    fun `trust quick actions are state pill plus toggling app button`() {
        val text = senderDetailSource()
        // 信任态胶囊两态在位
        assertTrue("已信任" in text && "未信任" in text, "信任态胶囊（已信任/未信任）缺失")
        // 单钮翻转（label 条件式）
        assertTrue(
            """label = if (trusted) "移出信任名单" else "加入信任名单"""" in text,
            "信任名单单钮翻转缺失",
        )
        // 两动作接线
        assertTrue(
            "SenderAccessAction.ADD_TO_TRUSTED" in text && "SenderAccessAction.REMOVE_FROM_TRUSTED" in text,
            "信任名单两动作接线缺失",
        )
        // 🔴 防复辟：白/黑名单快捷动作与四态文案必须清零
        assertFalse("\"白名单\"" in text || "\"黑名单\"" in text, "白/黑名单行已退役，不得复辟")
        assertFalse("inAllowed" in text || "inBlocked" in text, "白/黑名单归属变量已退役")
        assertFalse("ADD_TO_ALLOWLIST" in text || "ADD_TO_BLOCKLIST" in text, "白/黑名单动作已退役")
    }

    /** 定位 SenderDetailPanel.kt 源文件（PillLabelGuardTest 同款：user.dir 向上找 settings.gradle.kts）。 */
    private fun senderDetailSource(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val file = File(dir, SENDER_DETAIL_PANEL)
                assertTrue(file.isFile, "$SENDER_DETAIL_PANEL 不存在——文件被移动须同步更新本测试路径")
                return file.readText()
            }
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val SENDER_DETAIL_PANEL =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/stats/SenderDetailPanel.kt"
    }
}
