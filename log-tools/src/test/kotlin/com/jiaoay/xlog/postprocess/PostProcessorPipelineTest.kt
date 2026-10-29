package com.jiaoay.xlog.postprocess

import com.jiaoay.xlog.parser.MagicStartMark
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class PostProcessorPipelineTest {

    private val context = SegmentContext(0, MagicStartMark.NoCompressStart, 0, 8, 9)

    private fun run(pipeline: PostProcessorPipeline, content: String): String {
        val sb = StringBuilder()
        pipeline.process(content, context) { line, terminated ->
            sb.append(line)
            if (terminated) sb.append('\n')
        }
        return sb.toString()
    }

    @Test
    fun `default pipeline equals legacy normalization`() {
        val pipeline = PostProcessorPipeline.default()
        val content = "[Asia/Shanghai]-1700000000000 line one\n[Asia/Shanghai]-1700000000001 line two\n"
        assertEquals(
            "11-15 06:13:20.000 line one\n11-15 06:13:20.001 line two\n",
            run(pipeline, content)
        )
    }

    @Test
    fun `empty processor list leaves content byte-identical`() {
        val pipeline = PostProcessorPipeline.of(emptyList())
        val content = "[Asia/Shanghai]-1700000000000 x\r\nplain\n"
        assertEquals(content, run(pipeline, content))
    }

    /** 段尾换行保留, 不会拼到下一段 */
    @Test
    fun `trailing newline is preserved`() {
        val pipeline = PostProcessorPipeline.default()
        assertEquals(
            "11-15 06:13:20.000 a\n11-15 06:13:20.000 b\n",
            run(pipeline, "[Asia/Shanghai]-1700000000000 a\n[Asia/Shanghai]-1700000000000 b\n")
        )
    }

    /** 前段无尾换行: 尾行作为未终止行输出, 与下一段之间由调用方决定拼接 */
    @Test
    fun `content without trailing newline emits unterminated last line`() {
        val pipeline = PostProcessorPipeline.default()
        assertEquals(
            "11-15 06:13:20.000 no-newline-tail",
            run(pipeline, "[Asia/Shanghai]-1700000000000 no-newline-tail")
        )
    }

    /** CRLF 统一为 \n(与旧版 lineSequence/joinToString 行为一致) */
    @Test
    fun `crlf terminator is unified to lf`() {
        val pipeline = PostProcessorPipeline.default()
        val content = "[Asia/Shanghai]-1700000000000 a\r\n[Asia/Shanghai]-1700000000000 b\r\n"
        assertEquals(
            "11-15 06:13:20.000 a\n11-15 06:13:20.000 b\n",
            run(pipeline, content)
        )
    }

    /** 快速路径: 非 [ 开头整段跳过拆行, 原样输出 */
    @Test
    fun `non bracket content bypasses line splitting`() {
        val pipeline = PostProcessorPipeline.default()
        val content = "plain content [Asia/Shanghai]-1700000000000 not-normalized\n"
        assertEquals(content, run(pipeline, content))
    }

    @Test
    fun `dropping processor removes line`() {
        val dropTag = object : LogPostProcessor {
            override fun processLine(line: String, context: SegmentContext): String? {
                return if (line.contains("DROP_ME")) null else line
            }
        }
        val pipeline = PostProcessorPipeline.of(listOf(dropTag))
        assertEquals(
            "kept\n",
            run(pipeline, "DROP_ME\nkept\nDROP_ME\n")
        )
    }

    @Test
    fun `processors execute in declaration order`() {
        val appendA = object : LogPostProcessor {
            override fun processLine(line: String, context: SegmentContext) = "$line A"
        }
        val appendB = object : LogPostProcessor {
            override fun processLine(line: String, context: SegmentContext) = "$line B"
        }
        val pipeline = PostProcessorPipeline.of(listOf(appendA, appendB))
        assertEquals("line A B", run(pipeline, "line"))
    }

    @Test
    fun `lifecycle hooks are invoked per segment`() {
        val events = mutableListOf<String>()
        val recorder = object : LogPostProcessor {
            override fun onSegmentStart(context: SegmentContext) { events.add("start:${context.segmentIndex}") }
            override fun onSegmentEnd(context: SegmentContext) { events.add("end:${context.segmentIndex}") }
            override fun onFinish(summary: DecodeSummary) { events.add("finish:${summary.segmentCount}") }
        }
        val pipeline = PostProcessorPipeline.of(listOf(recorder))
        val ctx0 = SegmentContext(0, MagicStartMark.NoCompressStart, 0, 8, 9)
        val ctx1 = SegmentContext(1, MagicStartMark.NoCompressStart, 1, 8, 9)
        pipeline.onSegmentStart(ctx0)
        pipeline.process("x", ctx0) { _, _ -> }
        pipeline.onSegmentEnd(ctx0)
        pipeline.onSegmentStart(ctx1)
        pipeline.process("y", ctx1) { _, _ -> }
        pipeline.onSegmentEnd(ctx1)
        pipeline.onFinish()
        assertEquals(listOf("start:0", "end:0", "start:1", "end:1", "finish:2"), events)
    }

    @Test
    fun `collector processor can write structured results into summary`() {
        class LineCountCollector : LogPostProcessor {
            var lines = 0
            override fun processLine(line: String, context: SegmentContext): String {
                lines++
                return line
            }
            override fun onFinish(summary: DecodeSummary) {
                summary.processorResults["lineCount"] = lines
            }
        }
        val collector = LineCountCollector()
        val pipeline = PostProcessorPipeline.of(listOf(collector))
        val ctx = SegmentContext(0, MagicStartMark.NoCompressStart, 0, 8, 9)
        pipeline.onSegmentStart(ctx)
        pipeline.process("a\nb\nc\n", ctx) { _, _ -> }
        pipeline.onSegmentEnd(ctx)
        pipeline.onFinish()
        assertEquals(3, pipeline.summary.processorResults["lineCount"])
    }
}
