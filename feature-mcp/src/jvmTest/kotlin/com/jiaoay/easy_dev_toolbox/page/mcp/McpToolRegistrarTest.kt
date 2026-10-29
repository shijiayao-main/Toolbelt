package com.jiaoay.easy_dev_toolbox.page.mcp

import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * McpToolRegistrar 单测（P5 openspec mcp-expose 任务 2.1/2.2——design D8 零 Ktor/零 SDK）：
 * - 名集精确集合（外放面契约：memory 域四件、write_todos、distill 两件恒不在）；
 * - registry 装配面（名集内工具全数注册——能力门控件除外）；
 * - schema 派生（Koog 参数类型 → JSON Schema 四类映射）；
 * - 围栏语义（允许根内放行 / 围栏外 deny——fake gate）；
 * - 调用分发（args JSON 解码 → 执行 → 结果回传）。
 */
class McpToolRegistrarTest {

    // ── 2.1 名集契约 ──

    @Test
    fun `expose names cover exactly the deterministic tool face`() {
        assertEquals(
            setOf(
                // fs.extended 七件
                "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
                "fs.extended.hash_file", "fs.extended.hex_dump", "fs.extended.strings",
                "fs.extended.convert_timestamp",
                // knowledge 三件
                "knowledge.search", "knowledge.read", "knowledge.lookup_tag",
                // 管线 + log.* 六件
                "log.analyze_log", "log.decrypt", "log.filter_by_tag", "log.filter_by_time",
                "log.filter_by_range", "log.split_by_size", "log.probe_file",
            ),
            McpToolRegistrar.MCP_EXPOSE_NAMES,
            "外放名集 = fs.extended 七件 + knowledge 三件 + analyze_log + log.* 六件",
        )
    }

    @Test
    fun `memory and session tools are never exposed`() {
        // 🔴 design D3：个人记忆/桌面会话件不进外部 client
        assertTrue("memory.view" !in McpToolRegistrar.MCP_EXPOSE_NAMES)
        assertTrue("memory.append" !in McpToolRegistrar.MCP_EXPOSE_NAMES)
        assertTrue("write_todos" !in McpToolRegistrar.MCP_EXPOSE_NAMES)
        assertTrue("distill.report_step" !in McpToolRegistrar.MCP_EXPOSE_NAMES)
        assertTrue("distill.propose_knowledge" !in McpToolRegistrar.MCP_EXPOSE_NAMES)
    }

    @Test
    fun `expose names all exist in catalog`() {
        val catalogNames = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.all.map { it.name }.toSet()
        McpToolRegistrar.MCP_EXPOSE_NAMES.forEach { name ->
            assertTrue(name in catalogNames, "外放名集内工具必须在目录中注册：$name")
        }
    }

    @Test
    fun `registry assembles all names except gated`() {
        val registry = McpToolRegistrar.buildRegistryForTest()
        val dispatcher = McpToolRegistrar.ToolDispatcher(registry)
        val catalog = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.all
            .filter { it.name in McpToolRegistrar.MCP_EXPOSE_NAMES }
        // knowledge.* 装配门控 = 知识库有内容（hasContent()）——空库时返回 null 不注册属既有语义
        val gated = setOf("knowledge.search", "knowledge.read", "knowledge.lookup_tag")
        catalog.forEach { descriptor ->
            if (descriptor.name !in gated) {
                assertTrue(
                    dispatcher.toolOf(descriptor) != null,
                    "名集内工具必须经双名域解析命中（目录名或 aliases）：${descriptor.name}",
                )
            }
        }
    }

    // ── 2.1 schema 派生 ──

    /** 四类参数的样例工具（schema 映射直测面）。🔴 非 private：kotlinx serializer(KType)
     *  反射取 companion 不做 setAccessible——private 嵌套类会 SerializationException
     * （生产工具的 Args 全部 public，此处贴生产形态）。 */
    class SampleTool : SimpleTool<SampleTool.Args>(
        argsType = typeToken<Args>(),
        name = "sample",
        description = "schema 派生样例",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Path of file")
            val file_path: String,
            val offset: Long = 0,
            val ratio: Double = 1.0,
            val flag: Boolean = false,
        )

        override suspend fun execute(args: Args): String = "ok"
    }

    @Test
    fun `schema maps string integer float boolean to json schema types`() {
        val schema = McpToolRegistrar.toolSchemaOf(SampleTool())

        val props = schema.properties!!
        assertEquals("string", props["file_path"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", props["offset"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("number", props["ratio"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["flag"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `schema required lists only required parameters`() {
        val schema = McpToolRegistrar.toolSchemaOf(SampleTool())

        assertEquals(listOf("file_path"), schema.required, "可选参数不进 required")
    }

    @Test
    fun `schema of read_file tool derives from real descriptor`() {
        val registry = McpToolRegistrar.buildRegistryForTest()
        val tool = registry.getToolOrNull("fs.extended.read_file") ?: return
        val schema = McpToolRegistrar.toolSchemaOf(tool)

        val props = schema.properties!!
        assertTrue("file_path" in (schema.required ?: emptyList()), "read_file 的 file_path 必填")
        assertEquals("string", props["file_path"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(props.containsKey("offset"), "可选参数 offset 在 properties")
    }

    // ── 2.2 围栏语义 ──

    @Test
    fun `allowed root authorizes inside paths without gate`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("edt-mcp-fence").toFile()
        try {
            val context = ToolExecutionContext.withAllowedRoots(listOf(dir))
            val inside = File(dir, "a.txt").apply { writeText("hello") }
            assertTrue(context.isAllowed(inside), "允许根内路径直接放行（免逐文件 HITL）")
            assertTrue(context.authorizeWithGate(inside.absolutePath) != null, "gate 面同样放行")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `outside path is denied by deny-only gate`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("edt-mcp-fence2").toFile()
        val outside = java.nio.file.Files.createTempDirectory("edt-mcp-outside").toFile()
        try {
            var gatePath: File? = null
            val context = ToolExecutionContext.withAllowedRoots(
                allowedRoots = listOf(dir),
                escapeGate = ToolExecutionContext.PathEscapeGate { path ->
                    gatePath = path
                    false // 🔴 deny-only：MCP 侧恒拒绝
                },
            )
            val target = File(outside, "secret.txt")
            val authorized = context.authorizeWithGate(target.absolutePath)
            assertEquals(null, authorized, "围栏外路径 deny-only 恒拒")
            assertEquals(target.canonicalFile, gatePath, "gate 收到请求路径（reason 数据源）")
        } finally {
            dir.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ── 调用分发（JSON args → 执行 → 回传） ──

    @Test
    fun `dispatch decodes json args and returns tool text`() {
        val registry = ToolRegistry {
            tool(SampleTool())
        }
        val dispatcher = McpToolRegistrar.ToolDispatcher(registry)

        val descriptor = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.AgentToolDescriptor(
            name = "sample",
            description = "样例",
            risk = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.Risk.READ_ONLY,
            factory = { _, _, _ -> null },
        )
        val result = dispatcher.dispatch(
            descriptor,
            io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
                io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(
                    name = "sample",
                    arguments = kotlinx.serialization.json.buildJsonObject {
                        put("file_path", JsonPrimitive("/tmp/x.log"))
                        put("offset", JsonPrimitive(5))
                    },
                ),
            ),
        )

        // isError 三态（Boolean?）：null = 成功未置错（MCP 序列化面等价 false）
        assertEquals(null, result.isError, "工具成功不置错（isError 保持默认 null）")
        assertEquals(1, result.content.size)
        assertEquals("ok", (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text)
    }

    @Test
    fun `dispatch unknown tool returns error result`() {
        val dispatcher = McpToolRegistrar.ToolDispatcher(ToolRegistry { })
        val result = dispatcher.dispatch(
            com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.AgentToolDescriptor(
                name = "nope",
                description = "不存在",
                risk = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.Risk.READ_ONLY,
                factory = { _, _, _ -> null },
            ),
            io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
                io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(
                    name = "nope",
                    arguments = null,
                ),
            ),
        )
        assertTrue(result.isError == true, "未知工具 = isError 回传（client 可感知）")
    }

    @Test
    fun `dispatch bad args returns error result not crash`() {
        val registry = ToolRegistry { tool(SampleTool()) }
        val dispatcher = McpToolRegistrar.ToolDispatcher(registry)

        // offset 传字符串 = 解码失败 → errorResult（任务不崩）
        val result = dispatcher.dispatch(
            com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.AgentToolDescriptor(
                name = "sample",
                description = "样例",
                risk = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.Risk.READ_ONLY,
                factory = { _, _, _ -> null },
            ),
            io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
                io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(
                    name = "sample",
                    arguments = kotlinx.serialization.json.buildJsonObject {
                        put("file_path", JsonPrimitive("/tmp/x.log"))
                        put("offset", JsonPrimitive("not-a-number"))
                    },
                ),
            ),
        )
        assertTrue(result.isError == true, "坏参数 = error 文案回传（不抛异常崩 server）")
    }
}
