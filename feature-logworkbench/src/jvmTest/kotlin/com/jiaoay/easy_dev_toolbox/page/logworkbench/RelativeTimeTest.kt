package com.jiaoay.easy_dev_toolbox.page.logworkbench

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WB-12（重设计批 7 · 2026-10-01）：历史 run 行 meta 补全——relativeTime 直测
 * + 历史行 meta 拼接源码锚。
 *
 * - relativeTime 是顶层 internal fun（同包直测缝）——五档阈值边界直测
 *   （卡面例句「3 小时前/昨天/09-25」+ 施工自拟「刚刚/N 分钟前」第四档）；
 * - 空值不显 @ 段：meta 拼接经 listOfNotNull + `targetTime?.let`（源码锚钉死）。
 */
class RelativeTimeTest {

    // 与 jvmMain 侧档位常量同值（private 不可跨源集——本地镜像直测）
    private val MINUTE_MS = 60_000L
    private val HOUR_MS = 3_600_000L

    // 固定时基：2026-10-01 15:00:00.000（今日）
    private val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").parse("2026-10-01 15:00:00.000").time

    @Test
    fun `just now under one minute`() {
        assertEquals("刚刚", relativeTime(now - 30_000, now), "<1 分钟 →「刚刚」")
    }

    @Test
    fun `minutes ago under one hour`() {
        assertEquals("5 分钟前", relativeTime(now - 5 * MINUTE_MS, now), "<1 小时 →「N 分钟前」")
        assertEquals("59 分钟前", relativeTime(now - 59 * MINUTE_MS, now), "档位上边界 59 分钟")
    }

    @Test
    fun `hours ago same calendar day`() {
        assertEquals("3 小时前", relativeTime(now - 3 * HOUR_MS, now), "今日更早 →「N 小时前」（卡面例句）")
    }

    @Test
    fun `yesterday by calendar day not rolling window`() {
        // 25h 前 = 2026-09-30 14:00（日历昨日）——非 24h 滚动窗（滚动窗会误判「25 小时前」）
        assertEquals("昨天", relativeTime(now - 25 * HOUR_MS, now), "昨日跨日 →「昨天」（日历日比较）")
    }

    @Test
    fun `older than yesterday renders MM-dd`() {
        assertEquals("09-25", relativeTime(now - 6 * 24 * HOUR_MS, now), "更早 → MM-dd（卡面例句）")
    }

    @Test
    fun `wb12 history row meta concatenation anchors`() {
        val source = logWorkbenchPageSource()
        listOf(
            "relativeTime(run.updatedAt)" to "历史行 meta 必须含相对时间（WB-12 acceptance）",
            "run.logFiles.size" to "历史行 meta 必须含文件数（acceptance：状态+相对时间+文件数）",
            "run.targetTime?.let { \"@ \$it\" }" to "targetTime 非空才追加时间点段（acceptance：空值不显段）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 WB-12 要素「$needle」——$why")
        }
    }

    private fun logWorkbenchPageSource(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, PAGE_PATH)
        assertTrue(file?.isFile() == true, "源码锚文件缺失：$PAGE_PATH")
        return file.readText()
    }

    private companion object {
        const val PAGE_PATH =
            "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt"
    }
}
