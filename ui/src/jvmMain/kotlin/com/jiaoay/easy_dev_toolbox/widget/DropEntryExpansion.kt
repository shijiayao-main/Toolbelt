package com.jiaoay.easy_dev_toolbox.widget

import java.io.File
import java.nio.file.Files

/**
 * 拖入条目展开（目录一等输入）：把一次拖入解析出的「文件 + 目录」混合批展开为
 * 逐文件输入批——目录递归枚举内部文件、经 [accepts] 过滤后并入。产物目录
 * （如解压产出的 `*_extracted`，常含几十个日志文件）拖入即整目录衔接下游
 * 工具页，不必逐个文件从文件管理器拖。
 *
 * ## 契约
 * - [DropExpansionOutcome.files] 非空 ⇔ [DropExpansionOutcome.rejectReason] 为 null；
 *   整批判拒必带原因（零匹配 / 超限），不静默丢弃；
 * - 双轨过滤：顶层非目录条目走 [acceptsDirectEntry]（null 回落 [accepts]），目录展开
 *   产物恒走 [accepts]。为什么双轨：直拖/点击是用户点名文件的明确意图，误拒成本高，
 *   交下游内容判型兜底；目录展开是批量场景，后缀粗滤是高效闸门（防日志目录里
 *   png/zip 等非日志文件全量入卡的噪音）。目录条目（含展开关闭时的目录项）不属于
 *   「非目录条目」，恒按 [accepts] 判定；
 * - 目录内非匹配文件计入 [DropExpansionOutcome.skippedCount]（与顶层文件混批的
 *   「跳过数」同一口径，由调用方随对回执）；
 * - 条目与层级双上限超限**整批判拒**（不截断收部分——截断会让用户误以为已收全）；
 *   上限是防病态深目录/海量条目冻结 UI 的「尽力而为」上界（对齐 log-tools
 *   `utils/SafeExtract.kt` 的解压炸弹闸门思路），不是精确配额；
 * - 符号链接目录不递归（防环形引用死循环）；符号链接文件按普通文件参与过滤；
 * - 同目录子项按名称排序展开（同批拖入结果顺序确定，可预期、可复现）；
 * - [expandDirectories] 关闭时目录条目按「类型不符」计入跳过、不枚举——
 *   未启用目录能力的调用方语义与既有行为一致。
 *
 * 展开在拖入回调内同步执行（条目上限封顶了遍历量），调用方无需自带 IO 调度。
 */
fun expandDroppedEntries(
    entries: List<File>,
    accepts: (File) -> Boolean,
    expandDirectories: Boolean,
    fallbackRejectText: String,
    limits: DropExpansionLimits = DropExpansionLimits(),
    acceptsDirectEntry: ((File) -> Boolean)? = null,
): DropExpansionOutcome {
    val matched = mutableListOf<File>()
    var skipped = 0
    var zeroMatchDirCount = 0
    // 访问条目计数（顶层条目 + 目录内子项，文件与目录各计一次）——条目上限判定基数
    var visited = 0
    // 顶层非目录条目的过滤通道（目录展开产物与目录条目恒走 accepts，见类 KDoc 双轨契约）
    val directEntryAccepts = acceptsDirectEntry ?: accepts

    fun reject(reason: String) = DropExpansionOutcome(
        files = emptyList(),
        skippedCount = 0,
        rejectReason = reason,
    )

    val entryOverflowReason = "目录条目数超过上限（${limits.maxEntries}）——已中止本次拖入"

    // null = 正常扫完；非空 = 超限原因（整批判拒由顶层收口，matched 不部分返回）
    fun scanDir(dir: File, depth: Int): String? {
        // 不可读目录按零匹配处理（枚举不出内容，如实不猜）
        val children = dir.listFiles()?.sortedBy { it.name } ?: return null
        for (child in children) {
            if (++visited > limits.maxEntries) return entryOverflowReason
            if (child.isDirectory) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (depth >= limits.maxDepth) {
                    return "目录嵌套层级超过上限（${limits.maxDepth}）——已中止本次拖入"
                }
                scanDir(child, depth + 1)?.let { return it }
            } else {
                if (accepts(child)) matched += child else skipped++
            }
        }
        return null
    }

    for (entry in entries) {
        if (++visited > limits.maxEntries) return reject(entryOverflowReason)
        if (entry.isDirectory && expandDirectories && !Files.isSymbolicLink(entry.toPath())) {
            val before = matched.size
            scanDir(entry, depth = 0)?.let { return reject(it) }
            if (matched.size == before) zeroMatchDirCount++
        } else {
            // 双轨分流：目录条目（展开关闭/符号链接目录）恒走 accepts；顶层非目录
            // 条目是用户点名的文件，走 directEntryAccepts（null 时与 accepts 同一谓词）
            val passed = if (entry.isDirectory) accepts(entry) else directEntryAccepts(entry)
            if (passed) matched += entry else skipped++
        }
    }
    if (matched.isEmpty()) {
        return DropExpansionOutcome(
            files = emptyList(),
            skippedCount = skipped,
            rejectReason = when {
                zeroMatchDirCount > 0 ->
                    "拖入的 $zeroMatchDirCount 个目录内没有可处理的文件（空目录或类型不符）"
                else -> fallbackRejectText
            },
        )
    }
    return DropExpansionOutcome(files = matched, skippedCount = skipped, rejectReason = null)
}

/**
 * 目录展开双上限。
 *
 * @property maxEntries 单次拖入访问的条目总数上限（顶层条目 + 目录内子项，文件与目录各计一次）
 * @property maxDepth 拖入目录之下的嵌套子目录层数上限（拖入目录自身为第 0 层）
 */
data class DropExpansionLimits(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    companion object {
        /**
         * 默认条目上限：产物目录典型为几十个日志文件，千级上界已远超真实产物规模，
         * 又能在病态目录（依赖树/缓存目录）拖入冻结界面前截停。
         */
        const val DEFAULT_MAX_ENTRIES = 1_000

        /** 默认层级上限：真实日志产物目录几乎不嵌套；上限防的是环形构造与异常深度 */
        const val DEFAULT_MAX_DEPTH = 16
    }
}

/**
 * 展开结果：[files] 非空 = 通过（[skippedCount] 为混批被滤项数，含目录内非匹配文件，
 * 调用方随对回执）；[files] 为空 = 整批判拒，[rejectReason] 必非空
 * （零匹配/超限原因，调用方直接呈现）。
 */
data class DropExpansionOutcome(
    val files: List<File>,
    val skippedCount: Int,
    val rejectReason: String?,
)
