package com.jiaoay.log

/**
 * 基线对照「新鲜度视图」纯函数：两个 [LogStatsResult.Ok]（健康基线 + 问题日志）的
 * 集合差分——「这份比好的那份多了什么」的分布面回答。零文件 IO、零解析：块级
 * 统计一律出自 [LogStatsAnalyzer.analyze] 单源（防第二套解析器），本对象只消费两份
 * 已产出的统计结果。后续新增对照消费面（agent 工具/报告导出等）**必须复用本函数**，
 * 不得在消费面再写第三套对照差分逻辑。
 *
 * ## 语义边界（消费方呈现时不得混口径）
 * - **分布对照 ≠ 行级内容差异**：本函数回答的是块级 Tag/级别**分布**的增减面；
 *   「哪几行不一样」是行级内容差异，归 [LogDiff.diff]，两者正交不互替。
 * - **与 [LogCluster] 分工**：cluster 吸收**文件内优先级**（同一份日志里哪类模板
 *   刷得最凶，top-N 排序）；本对照吸收**跨文件新增面**——基线对照里新冒出来的
 *   Tag 靠**集合存在性**判定（基线缺席即新），不靠计数大小（低频新 Tag 在任何
 *   top-N 里必沉底，存在性差分才能捞出）。
 *
 * ## 未识别块差值：两侧如实呈现，禁归因
 * 头行解析不出 Tag/级别的块**没有 Tag 可归属**，不得虚构占位 Tag 摊进任何增量榜
 * （归因 = 造假）。两侧的未识别差值经 [CompareResult] 的四个字段分别呈现
 * （口径 = 引擎契约：totalBlocks − 对应合计），消费方按「基线 x 块 / 对比 y 块」
 * 原样双列，不合成单一差值。
 */
object LogStatsCompare {

    /**
     * 基线对照结果（[compare] 专用）。全部排序确定性：频次/差值降序、并列按 Tag
     * 字典序——同输入恒同输出（可直接做快照断言）。
     *
     * @property newTags 新 Tag 集：对比侧在场、基线侧缺席的 Tag，按对比侧频次降序
     * @property sharedTagDeltas 共有 Tag 频次增量榜：两侧均在场的 Tag 中差值非零者
     * *（delta = 对比 − 基线，正负双向同榜），按差值绝对值降序
     * @property levelDeltas 级别块数差值（delta = 对比 − 基线）：**全档入表**（含零档，
     *   消费方按需筛选呈现，不在此预裁剪）
     * @property baselineUnparsedTagBlocks 基线侧头行解析不出 Tag 的块数（差值不归因契约见类 KDoc）
     * @property currentUnparsedTagBlocks 对比侧头行解析不出 Tag 的块数
     * @property baselineUnparsedLevelBlocks 基线侧头行解析不出级别的块数
     * @property currentUnparsedLevelBlocks 对比侧头行解析不出级别的块数
     * @property baselineTotalBlocks 基线侧完整块总数（呈现侧别规模用）
     * @property currentTotalBlocks 对比侧完整块总数
     */
    data class CompareResult(
        val newTags: List<TagCount>,
        val sharedTagDeltas: List<TagDelta>,
        val levelDeltas: Map<LogStatsAnalyzer.Level, Int>,
        val baselineUnparsedTagBlocks: Int,
        val currentUnparsedTagBlocks: Int,
        val baselineUnparsedLevelBlocks: Int,
        val currentUnparsedLevelBlocks: Int,
        val baselineTotalBlocks: Int,
        val currentTotalBlocks: Int,
    ) {
        /** ERROR 档块数差值（「环境变坏」的第一信号，消费面单列呈现的取数锚） */
        val errorDelta: Int get() = levelDeltas.getValue(LogStatsAnalyzer.Level.ERROR)

        /** FATAL 档块数差值（与 [errorDelta] 同列单列） */
        val fatalDelta: Int get() = levelDeltas.getValue(LogStatsAnalyzer.Level.FATAL)
    }

    /** 共有 Tag 的频次增量行：delta = 对比侧块数 − 基线侧块数（正 = 变多、负 = 变少） */
    data class TagDelta(val tag: String, val baselineCount: Int, val currentCount: Int) {
        val delta: Int get() = currentCount - baselineCount
    }

    /**
     * 基线（健康）与对比（问题）两份统计结果的集合差分。空基线退化语义：基线
     * Tag 集为空 ⇒ 对比侧全量 Tag 均为「新 Tag」、增量榜为空——消费方无需特判。
     */
    fun compare(baseline: LogStatsResult.Ok, current: LogStatsResult.Ok): CompareResult {
        val baselineTags = baseline.tagCounts.associate { it.tag to it.count }
        val currentTags = current.tagCounts.associate { it.tag to it.count }

        val newTags = currentTags.filterKeys { it !in baselineTags }
            .map { (tag, count) -> TagCount(tag, count) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag })

        val sharedTagDeltas = baselineTags.keys.intersect(currentTags.keys)
            .map { tag -> TagDelta(tag, baselineTags.getValue(tag), currentTags.getValue(tag)) }
            .filter { it.delta != 0 }
            .sortedWith(compareByDescending<TagDelta> { abs(it.delta) }.thenBy { it.tag })

        val levelDeltas = LogStatsAnalyzer.Level.entries.associateWith { level ->
            (current.levelCounts[level] ?: 0) - (baseline.levelCounts[level] ?: 0)
        }

        return CompareResult(
            newTags = newTags,
            sharedTagDeltas = sharedTagDeltas,
            levelDeltas = levelDeltas,
            baselineUnparsedTagBlocks = baseline.totalBlocks - baseline.tagCounts.sumOf { it.count },
            currentUnparsedTagBlocks = current.totalBlocks - current.tagCounts.sumOf { it.count },
            baselineUnparsedLevelBlocks = baseline.totalBlocks - baseline.levelCounts.values.sum(),
            currentUnparsedLevelBlocks = current.totalBlocks - current.levelCounts.values.sum(),
            baselineTotalBlocks = baseline.totalBlocks,
            currentTotalBlocks = current.totalBlocks,
        )
    }

    private fun abs(value: Int): Int = if (value < 0) -value else value
}
