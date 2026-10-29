package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.extensions.ConversionResult
import com.jiaoay.easy_dev_toolbox.core.extensions.Timestamps
import kotlinx.serialization.Serializable

/**
 * convert_timestamp 工具：时间戳 <-> 日期时间双向转换。
 *
 * 薄壳：核心逻辑复用 :core `Timestamps` 纯函数（与「时间戳转换」页同一实现，
 * feature-timestamp 已改薄委托——单一真源，日志时间字段分析场景刚需）。
 */
class ConvertTimestampTool(
    sink: ToolResultSink = ToolResultSink.NOOP,
) : StructuredSimpleTool<ConvertTimestampTool.Args>(
    sink = sink,
    argsType = typeToken<Args>(),
    name = "convert_timestamp",
    description = "Convert between unix timestamps and human-readable datetimes. " +
        "Accepts 10-digit seconds / 13-digit millis timestamps or datetime strings " +
        "(e.g. '2026-09-18 15:30:00').",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Timestamp or datetime string to convert")
        val value: String,
    )

    override suspend fun executeTyped(args: Args): ToolResult {
        if (args.value.isBlank()) {
            return ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "value 为空")
        }
        return when (val result = Timestamps.convert(args.value, DEFAULT_OUTPUT_PATTERN)) {
            is ConversionResult.Empty ->
                ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "value 为空")

            is ConversionResult.FromTimestamp -> ToolResult.Ok(
                summary = "${result.millis} → ${result.formatted}",
                data = null,
            )

            is ConversionResult.FromDate -> ToolResult.Ok(
                summary = "${args.value} → 毫秒 ${result.millis} / 秒 ${result.seconds}",
            )

            is ConversionResult.Invalid -> ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, result.message)
        }
    }

    private companion object {
        const val DEFAULT_OUTPUT_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
    }
}

/** AgentToolCatalog 描述符（P1 A1；顶层工具单件——不绑围栏） */
val CONVERT_TIMESTAMP: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
    name = "fs.extended.convert_timestamp",
    description = "Convert between unix timestamps and human-readable datetimes",
    risk = AgentToolCatalog.Risk.READ_ONLY,
    aliases = setOf("convert_timestamp"),
) { _, sink, _ -> ConvertTimestampTool(sink) }
