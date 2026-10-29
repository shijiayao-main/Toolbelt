package com.jiaoay.easy_dev_toolbox.core.llm.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import com.jiaoay.easy_dev_toolbox.core.llm.tools.OutputCapMiddleware
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AgentToolMiddleware 管线纪律（B1，零 LLM/零网络）：
 * - 链序：middlewares[0] 最外层（foldRight 逆序折叠）；
 * - 短路：intercept 不调 execute 直接返回；
 * - CE 穿透：下游抛 CancellationException 时链不得吞（取消传播红线）；
 * - decorateRegistry：名字/描述保留、execute 走装饰链；
 * - OutputCap：超长落盘 + 头尾预览 + 取回指针 + 驱逐文件登记进允许集；短结果原样直发。
 */
class AgentToolMiddlewareTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("mw-test").toFile()

    /** 记录拦截顺序的测试 middleware */
    private class RecordingMiddleware(
        override val id: String,
        private val log: MutableList<String>,
    ) : AgentToolMiddleware {
        override suspend fun intercept(call: ToolCallContext, execute: suspend () -> String): String {
            log += "before:$id"
            val result = execute()
            log += "after:$id"
            return "[$id:$result]"
        }
    }

    @Test
    fun `chain order is outside-in and result wraps inside-out`() = runTest {
        val log = mutableListOf<String>()
        val tool: Tool<String, String> = TestTools.hello()
            .withMiddlewares(listOf(RecordingMiddleware("A", log), RecordingMiddleware("B", log)))

        val result = tool.execute("world")

        assertEquals("[A:[B:hello world]]", result, "A 最外层、B 先拦截；包装顺序内层在外")
        assertEquals(listOf("before:A", "before:B", "after:B", "after:A"), log)
    }

    @Test
    fun `middleware can short-circuit without executing tool`() = runTest {
        val executed = mutableListOf<String>()
        val shortCircuit = object : AgentToolMiddleware {
            override val id = "deny"
            override suspend fun intercept(call: ToolCallContext, execute: suspend () -> String) = "DENIED"
        }
        val tool: Tool<String, String> = TestTools.recording(executed).withMiddlewares(listOf(shortCircuit))

        val result = tool.execute("x")

        assertEquals("DENIED", result)
        assertTrue(executed.isEmpty(), "短路时不得触达真实工具体")
    }

    @Test
    fun `cancellation exception passes through chain untouched`() = runTest {
        val passThrough = object : AgentToolMiddleware {
            override val id = "passthrough"
            override suspend fun intercept(call: ToolCallContext, execute: suspend () -> String) = execute()
        }
        val tool: Tool<String, String> = TestTools.alwaysCancel().withMiddlewares(listOf(passThrough, passThrough))

        var cancelled = false
        try {
            tool.execute("x")
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled, "CE 必须穿透装饰链（不吞取消传播）")
    }

    @Test
    fun `decorateRegistry preserves tool names and applies chain`() = runTest {
        val registry = TestTools.registryOf("alpha", "beta")
        val decorated = decorateRegistry(registry, listOf(SuffixMiddleware("[capped]")))

        assertEquals(setOf("alpha", "beta"), decorated.tools.map { it.name }.toSet(), "工具名必须保留（LLM 调用面不变）")
        @Suppress("UNCHECKED_CAST")
        val alpha = decorated.getToolOrNull("alpha") as Tool<String, String>
        assertEquals("hello alpha[capped]", alpha.execute("alpha"))
    }

    @Test
    fun `empty middleware list returns registry unchanged`() = runTest {
        val registry = TestTools.registryOf("only")
        val decorated = decorateRegistry(registry, emptyList())
        assertEquals(1, decorated.tools.size)
    }

    // ── OutputCapMiddleware ──

    @Test
    fun `output cap evicts oversized result to file with pointer`() = runTest {
        val dir = tempDir()
        val input = File(dir, "probe.txt").apply { writeText("seed") }
        val context = ToolExecutionContext(input)
        val cap = OutputCapMiddleware(context, File(dir, "evicted"), maxChars = 100)
        val huge = (1..300).joinToString("\n") { "line-$it" }

        val result = cap.intercept(ToolCallContext("grep", null)) { huge }

        assertTrue(result.contains("[工具结果超长，已落盘驱逐"), "应带驱逐标注: ${result.take(80)}")
        assertTrue(result.contains("头 5 行"), "应保留头部行")
        assertTrue(result.contains("line-300"), "应保留尾部行")
        assertTrue(result.contains("read_file(offset, limit)"), "应带取回指针")
        val evictedFile = File(dir, "evicted").listFiles()!!.single()
        assertTrue(context.isAllowed(evictedFile), "驱逐文件必须登记进允许集（否则 read_file 取回死路）")
        dir.deleteRecursively()
    }

    @Test
    fun `output cap passes short results untouched`() = runTest {
        val dir = tempDir()
        val input = File(dir, "probe.txt").apply { writeText("seed") }
        val context = ToolExecutionContext(input)
        val cap = OutputCapMiddleware(context, File(dir, "evicted"), maxChars = 100)

        val result = cap.intercept(ToolCallContext("preview_text", null)) { "短结果" }

        assertEquals("短结果", result)
        assertTrue(File(dir, "evicted").listFiles().isNullOrEmpty(), "短结果不落盘")
        dir.deleteRecursively()
    }

    // ── HITL gate ──

    @Test
    fun `escape gate approval registers path and second access skips gate`() = runTest {
        val dir = tempDir()
        val input = File(dir, "inside.txt").apply { writeText("x") }
        val outside = File(dir, "outside.txt").apply { writeText("secret") }
        var gateCalls = 0
        val context = ToolExecutionContext(input, escapeGate = {
            gateCalls++
            true // 批准
        })

        val first = context.authorizeWithGate(outside.absolutePath)
        val second = context.authorizeWithGate(outside.absolutePath)

        assertEquals(outside.canonicalFile, first, "批准后应放行")
        assertEquals(1, gateCalls, "登记后第二次访问不再询问（幂等放行）")
        assertEquals(second, first)
        dir.deleteRecursively()
    }

    @Test
    fun `escape gate rejection returns null and path stays unauthorized`() = runTest {
        val dir = tempDir()
        val input = File(dir, "inside.txt").apply { writeText("x") }
        val outside = File(dir, "outside.txt").apply { writeText("secret") }
        val context = ToolExecutionContext(input, escapeGate = { false })

        val result = context.authorizeWithGate(outside.absolutePath)

        assertEquals(null, result, "拒绝应返回 null（工具回 DENIED 文案）")
        assertTrue(!context.isAllowed(outside), "拒绝的路径不得登记")
        dir.deleteRecursively()
    }

    @Test
    fun `no gate keeps legacy deny semantics`() = runTest {
        val dir = tempDir()
        val input = File(dir, "inside.txt").apply { writeText("x") }
        val context = ToolExecutionContext(input) // 既有构造形态：无 gate

        val result = context.authorizeWithGate("/etc/passwd")

        assertEquals(null, result, "无 gate = 既有拒绝语义零变化")
        dir.deleteRecursively()
    }

    @Test
    fun `gate cancellation propagates out of authorizeWithGate`() = runTest {
        val dir = tempDir()
        val input = File(dir, "inside.txt").apply { writeText("x") }
        val context = ToolExecutionContext(input, escapeGate = {
            throw kotlinx.coroutines.CancellationException("任务取消")
        })

        var cancelled = false
        try {
            context.authorizeWithGate("/etc/passwd")
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled, "gate 抛 CE 必须穿透（取消传播红线）")
        dir.deleteRecursively()
    }

    // ── 测试工具与辅助 ──

    private object TestTools {
        fun hello(): Tool<String, String> = simpleTool("hello") { "hello $it" }
        fun recording(log: MutableList<String>): Tool<String, String> = simpleTool("record") {
            log += "executed"
            "done"
        }
        fun alwaysCancel(): Tool<String, String> = simpleTool("cancel_me") {
            throw kotlinx.coroutines.CancellationException("下游取消")
        }
        fun registryOf(vararg names: String): ToolRegistry = ToolRegistry {
            names.forEach { name -> tool(simpleTool(name) { "hello $it" }) }
        }

        private fun simpleTool(name: String, block: suspend (String) -> String): Tool<String, String> =
            object : Tool<String, String>(
                argsType = ai.koog.serialization.typeToken<String>(),
                resultType = ai.koog.serialization.typeToken<String>(),
                descriptor = ai.koog.agents.core.tools.ToolDescriptor(
                    name = name,
                    description = "test tool $name",
                ),
            ) {
                override suspend fun execute(args: String): String = block(args)
            }
    }

    private class SuffixMiddleware(private val suffix: String) : AgentToolMiddleware {
        override val id = "suffix"
        override suspend fun intercept(call: ToolCallContext, execute: suspend () -> String): String =
            execute() + suffix
    }
}
