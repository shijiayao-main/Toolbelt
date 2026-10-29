package com.jiaoay.xlog.postprocess

/**
 * 后处理管道: 有序执行一组 [LogPostProcessor]。
 *
 * 行处理函数按处理器顺序折叠组合, 丢弃(null)短路; 带快速路径声明的处理器
 * 在整段内容不满足前缀时被剔除, 内容完全不拆行。
 * 行终止符按源内容原样保留并传递给 [LineSink], 段边界不会拼行。
 */
internal class PostProcessorPipeline private constructor(
    private val processors: List<LogPostProcessor>,
) {

    val summary: DecodeSummary = DecodeSummary()

    fun onSegmentStart(context: SegmentContext) {
        summary.segmentCount++
        processors.forEach { it.onSegmentStart(context) }
    }

    fun onSegmentEnd(context: SegmentContext) {
        processors.forEach { it.onSegmentEnd(context) }
    }

    /** 处理一个段的文本内容, 逐行写入 sink */
    fun process(content: String, context: SegmentContext, sink: LineSink) {
        val activeProcessors = processors.filter { processor ->
            val prefix = processor.requiredContentPrefix
            prefix == null || content.startsWith(prefix)
        }
        if (activeProcessors.isEmpty()) {
            // 无活动处理器: 原样输出(含终止符)
            sink.write(content, terminated = false)
            return
        }
        // 按行处理: 拆行后逐行过管道, 行终止符状态原样保留。
        // 内容以终止符结尾时最后一段是空串, 会被视为"无尾行"跳过, 终止符由前一行携带。
        var pendingLine: String? = null
        var lineStart = 0
        val contentLength = content.length
        var index = 0
        while (index < contentLength) {
            val ch = content[index]
            if (ch == '\n' || ch == '\r') {
                var current: String? = content.substring(lineStart, index)
                for (processor in activeProcessors) {
                    if (current == null) break
                    current = processor.processLine(current, context)
                }
                if (pendingLine != null) {
                    sink.write(pendingLine, terminated = true)
                }
                pendingLine = current
                // 跳过 \r\n 成对终止符
                if (ch == '\r' && index + 1 < contentLength && content[index + 1] == '\n') {
                    index++
                }
                lineStart = index + 1
            }
            index++
        }
        // 尾行: 内容不以终止符结尾时的剩余部分
        if (lineStart < contentLength) {
            var lastLine: String? = content.substring(lineStart)
            for (processor in activeProcessors) {
                if (lastLine == null) break
                lastLine = processor.processLine(lastLine, context)
            }
            if (pendingLine != null) {
                sink.write(pendingLine, terminated = true)
                pendingLine = null
            }
            if (lastLine != null) {
                sink.write(lastLine, terminated = false)
            }
        } else if (pendingLine != null) {
            sink.write(pendingLine, terminated = true)
        }
    }

    fun onFinish() {
        processors.forEach { it.onFinish(summary) }
    }

    companion object {

        /** 默认管道: 仅时间戳归一化(与旧行为一致) */
        fun default(): PostProcessorPipeline = of(listOf(TimestampNormalizer()))

        fun of(processors: List<LogPostProcessor>): PostProcessorPipeline =
            PostProcessorPipeline(processors.toList())
    }
}
