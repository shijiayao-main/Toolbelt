package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.easy_dev_toolbox.core.llm.agent.AgentToolMiddleware
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolCallContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 大结果兜底驱逐 middleware（参照 deepagents `_message_eviction` 的
 * 「超阈值落盘 + 头尾行预览 + 取回指针」配方）：
 *
 * 为什么需要它：read_file 有精细分页，但其余工具（grep 大命中 / preview_text 超长 / 未来扩展件）
 * 仍可能返回超长结果整块进 LLM 上下文——大日志场景直接撑爆窗口。本 middleware 作为**兜底网**
 * 拦在所有工具结果上：超过 [maxChars] 时全文落盘 + 原地替换为「头尾 [headLines]/[tailLines] 行 +
 * `[N lines truncated, 全文: <path>]`」——LLM 仍可用 read_file（分页）取回细节。
 *
 * 🔴 驱逐文件 [context.register] 回允许集——否则「取回指针」是死路（read_file 围栏拒绝）。
 * 🔴 不吞 CancellationException：落盘失败保底返回原文（deepagents「backend 写失败保留原消息」
 *    的保底不丢语义；吞 CE 即取消传播断、「停止」指令失效）。
 */
class OutputCapMiddleware(
    private val context: ToolExecutionContext,
    private val evictionDir: File,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val headLines: Int = 5,
    private val tailLines: Int = 5,
) : AgentToolMiddleware {

    private val seq = AtomicLong(0)

    override val id: String = "output_cap"

    override suspend fun intercept(
        call: ToolCallContext,
        execute: suspend () -> String,
    ): String {
        val result = execute()
        if (result.length <= maxChars) return result

        return withContext(Dispatchers.IO) {
            val evicted = runCatching { writeEvicted(call.toolName, result) }
                .onFailure { return@withContext result } // 落盘失败保底：原文直发，不丢内容
                .getOrThrow()
            context.register(evicted)
            evictedPreview(evicted, result)
        }
    }

    private fun writeEvicted(toolName: String, content: String): File {
        evictionDir.mkdirs()
        val file = File(evictionDir, "evicted-${toolName}-${seq.incrementAndGet()}.txt")
        file.writeText(content)
        return file
    }

    /** 头尾行预览 + 截断标注 + 取回指针（行数标注便于 LLM 用 read_file offset/limit 定位） */
    private fun evictedPreview(evicted: File, original: String): String {
        val lines = original.lineSequence().toList()
        val head = lines.take(headLines)
        val tail = if (lines.size > headLines + tailLines) lines.takeLast(tailLines) else emptyList()
        return buildString {
            appendLine("[工具结果超长，已落盘驱逐——头 ${head.size} 行 + 尾 ${tail.size} 行预览]")
            head.forEach { appendLine(it) }
            if (tail.isNotEmpty()) {
                appendLine("...(中间 ${lines.size - head.size - tail.size} 行已截断)...")
                tail.forEach { appendLine(it) }
            } else {
                appendLine("...(其余 ${lines.size - head.size} 行已截断)...")
            }
            append("[共 ${lines.size} 行 truncated，全文: ${evicted.absolutePath}——可用 read_file(offset, limit) 分页取回]")
        }
    }

    companion object {
        /** 默认驱逐阈值 80K 字符（对齐 deepagents tool_token_limit 换算量级） */
        const val DEFAULT_MAX_CHARS: Int = 80_000
    }
}
