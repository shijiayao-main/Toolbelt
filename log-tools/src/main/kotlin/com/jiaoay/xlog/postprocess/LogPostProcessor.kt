package com.jiaoay.xlog.postprocess

import com.jiaoay.xlog.parser.MagicStartMark

/** 单个段的后处理上下文: 段头元数据 */
internal class SegmentContext(
    val segmentIndex: Int,
    val magicStartMark: MagicStartMark,
    val seq: Int,
    val beginHour: Int,
    val endHour: Int,
)

/** 解码汇总: 由各处理器在 onFinish 时补充统计 */
internal class DecodeSummary {
    var segmentCount: Int = 0
    /** EOF 处被静默丢弃的不完整尾段字节数 */
    var droppedTailBytes: Long = 0
    /** 处理器收集的结构化结果挂载点(键为处理器名) */
    val processorResults: MutableMap<String, Any> = mutableMapOf()
}

/** 行输出目的地; terminated 表示该行在源内容中带行终止符 */
internal fun interface LineSink {
    fun write(line: String, terminated: Boolean)
}

/**
 * 解码后处理器。管道按声明顺序执行:
 * - [requiredContentPrefix]: 快速路径声明。非 null 且整段内容不以它开头时, 本处理器整段跳过
 *   (省去拆行开销, 保留旧版 content.startsWith("[") 的语义)。
 * - [processLine]: 行级变换, 返回 null 表示丢弃该行。
 * - 段生命周期钩子: 供跨段统计类处理器在段边界重置/落账。
 * - [onFinish]: 全部段处理完后调用一次, 可写入 [DecodeSummary.processorResults]。
 *
 * 注意: 实例持有统计状态时不可跨 decodeFile 调用复用。
 */
internal interface LogPostProcessor {

    val requiredContentPrefix: String?
        get() = null

    fun onSegmentStart(context: SegmentContext) {}

    /** @return 变换后的行; null = 丢弃 */
    fun processLine(line: String, context: SegmentContext): String? = line

    fun onSegmentEnd(context: SegmentContext) {}

    fun onFinish(summary: DecodeSummary) {}
}
