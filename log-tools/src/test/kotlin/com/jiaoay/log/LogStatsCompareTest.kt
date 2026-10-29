package com.jiaoay.log

import com.jiaoay.log.LogStatsAnalyzer.Level
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LogStatsCompare] 确定性契约测试（纯函数直测，零文件 IO——fixture 直接构造两个
 * [LogStatsResult.Ok]）：新 Tag 差分 / 共有 Tag 增量榜（正负双向序）/ 空基线退化 /
 * 级别全档差值（E/F 单列取数锚）/ 未识别块差值两侧分列呈现。
 *
 * 另钉死「对照层零文件 IO、零解析调用」的源码锚——块级统计单源 = [LogStatsAnalyzer.analyze]
 * （P15 零第二套解析器口径），对照函数只吃两份已产出的 Ok 结果。扫盘型用例会被
 * build cache 假绿：验收须 `--rerun-tasks --no-build-cache`。
 */
class LogStatsCompareTest {

    /** 直构 Ok（纯函数直测——不经 analyze，规避文件 IO 与时间戳年份锚定的不确定性） */
    private fun ok(
        totalBlocks: Int,
        tags: Map<String, Int>,
        levels: Map<Level, Int> = emptyMap(),
    ): LogStatsResult.Ok = LogStatsResult.Ok(
        scannedLines = totalBlocks,
        totalBlocks = totalBlocks,
        tagCounts = tags.map { (tag, count) -> TagCount(tag, count) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag }),
        levelCounts = levels,
        firstTimestamp = null,
        lastTimestamp = null,
        spanMillis = null,
    )

    @Test
    fun `new tags are baseline-absent only, ordered by current count then tag`() {
        val baseline = ok(totalBlocks = 8, tags = mapOf("Beta" to 1, "Dead" to 5))
        val current = ok(totalBlocks = 8, tags = mapOf("Alpha" to 3, "Beta" to 2, "Cee" to 1))

        val result = LogStatsCompare.compare(baseline, current)

        assertEquals(
            listOf(TagCount("Alpha", 3), TagCount("Cee", 1)),
            result.newTags,
            "新 Tag 集 = 对比侧在场、基线侧缺席者，按对比频次降序",
        )
        // 基线独有 Tag（Dead）不是「新 Tag」——新 Tag 的语义是集合存在性，不是对称差
        assertTrue(result.newTags.none { it.tag == "Dead" }, "基线独有 Tag 不得进新 Tag 集")
    }

    @Test
    fun `shared delta board ranks both directions by absolute delta with zero excluded`() {
        val baseline = ok(
            totalBlocks = 10,
            tags = mapOf("Aa" to 1, "Bb" to 5, "Cc" to 2, "Dd" to 2),
        )
        val current = ok(
            totalBlocks = 10,
            tags = mapOf("Aa" to 3, "Bb" to 1, "Cc" to 2, "Dd" to 4),
        )

        val result = LogStatsCompare.compare(baseline, current)

        assertEquals(
            listOf(
                LogStatsCompare.TagDelta("Bb", baselineCount = 5, currentCount = 1),
                LogStatsCompare.TagDelta("Aa", baselineCount = 1, currentCount = 3),
                LogStatsCompare.TagDelta("Dd", baselineCount = 2, currentCount = 4),
            ),
            result.sharedTagDeltas,
            "增量榜按 |delta| 降序（并列按 Tag 字典序），正负双向同榜、零差值剔除",
        )
        assertEquals(-4, result.sharedTagDeltas.first().delta, "负向（变少）与正向同榜排序")
        assertTrue(
            result.sharedTagDeltas.none { it.tag == "Cc" },
            "零差值的共有 Tag 不是增量（Cc 两侧均 2 不得入榜）",
        )
    }

    @Test
    fun `empty baseline degrades to all current tags as new with empty delta board`() {
        val baseline = ok(totalBlocks = 0, tags = emptyMap())
        val current = ok(totalBlocks = 6, tags = mapOf("Zeta" to 1, "Alpha" to 4, "Mid" to 2))

        val result = LogStatsCompare.compare(baseline, current)

        assertEquals(
            listOf(TagCount("Alpha", 4), TagCount("Mid", 2), TagCount("Zeta", 1)),
            result.newTags,
            "空基线退化：对比侧全量 Tag 均为新 Tag（频次降序），消费方无需特判",
        )
        assertTrue(result.sharedTagDeltas.isEmpty(), "无共有 Tag ⇒ 增量榜为空")
        assertEquals(0, result.baselineTotalBlocks)
        assertEquals(6, result.currentTotalBlocks)
    }

    @Test
    fun `unrecognized block deltas are reported per side and never folded into tag surfaces`() {
        // 基线 5 块、Tag 合计 3 ⇒ 未识别 Tag 头行 2 块；对比 7 块、合计 6 ⇒ 1 块。
        // 级别维度独立：基线级别合计 4 ⇒ 未识别 1；对比级别合计 7 ⇒ 未识别 0。
        val baseline = ok(
            totalBlocks = 5,
            tags = mapOf("A" to 2, "B" to 1),
            levels = mapOf(Level.ERROR to 3, Level.INFO to 1),
        )
        val current = ok(
            totalBlocks = 7,
            tags = mapOf("A" to 2, "B" to 3, "New" to 1),
            levels = mapOf(Level.ERROR to 5, Level.FATAL to 1, Level.INFO to 1),
        )

        val result = LogStatsCompare.compare(baseline, current)

        assertEquals(2, result.baselineUnparsedTagBlocks, "基线侧未识别 Tag 头行差值须单列")
        assertEquals(1, result.currentUnparsedTagBlocks, "对比侧未识别 Tag 头行差值须单列")
        assertEquals(1, result.baselineUnparsedLevelBlocks, "级别维度的未识别差值与 Tag 维度独立分列")
        assertEquals(0, result.currentUnparsedLevelBlocks)
        // 禁归因：两个 Tag 面（新 Tag 集 + 增量榜）不得出现任何虚构占位 Tag——
        // 全部条目都须是入参两侧真实在场的 Tag
        val realTags = setOf("A", "B", "New")
        val deltaSurfaces = result.newTags.map { it.tag } + result.sharedTagDeltas.map { it.tag }
        assertTrue(
            deltaSurfaces.all { it in realTags },
            "未识别块不得经占位 Tag 摊进 Tag 面（禁归因契约），实际条目：$deltaSurfaces",
        )
    }

    @Test
    fun `level deltas cover all levels with E and F deltas as direct accessors`() {
        val baseline = ok(
            totalBlocks = 4,
            tags = emptyMap(),
            levels = mapOf(Level.ERROR to 1, Level.DEBUG to 2),
        )
        val current = ok(
            totalBlocks = 4,
            tags = emptyMap(),
            levels = mapOf(Level.ERROR to 3, Level.FATAL to 1, Level.DEBUG to 2),
        )

        val result = LogStatsCompare.compare(baseline, current)

        // 全档入表（含零档与两侧均缺席档）——消费方按需筛选，函数侧不预裁剪
        assertEquals(6, result.levelDeltas.size, "六档级别全量入表")
        assertEquals(2, result.levelDeltas[Level.ERROR], "E 差值 = 3 − 1")
        assertEquals(1, result.levelDeltas[Level.FATAL], "F 差值 = 1 − 0")
        assertEquals(0, result.levelDeltas[Level.DEBUG], "D 两侧持平为 0")
        assertEquals(0, result.levelDeltas[Level.VERBOSE], "两侧均缺席档差值为 0")
        assertEquals(2, result.errorDelta, "E 增量单列取数锚（消费面第一信号位）")
        assertEquals(1, result.fatalDelta, "F 增量单列取数锚")
    }

    // ── 单源守护（扫盘型：验收须 --rerun-tasks --no-build-cache） ──

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")})")
    }

    @Test
    fun `compare layer performs no file IO and invokes no parser`() {
        val source = File(
            projectRoot(),
            "log-tools/src/main/kotlin/com/jiaoay/log/LogStatsCompare.kt",
        ).readText()
        assertFalse(
            "java.io" in source,
            "对照层零文件 IO——文件读取只属于 LogStatsAnalyzer.analyze，对照只吃两份 Ok 结果",
        )
        assertFalse(
            "analyze(" in source,
            "对照层不得触块解析（零第二套解析器口径）——两份 Ok 统计结果由调用方经单源产出",
        )
        assertFalse(
            "scanBlocks" in source,
            "对照层不得自行声明/调用块扫描内核（块统计单源 = LogStatsAnalyzer.analyze）",
        )
        assertFalse(
            "import " in source,
            "对照层应零 import（纯数据差分不依赖任何 IO/解析面）——出现 import 即口径漂移",
        )
    }
}
