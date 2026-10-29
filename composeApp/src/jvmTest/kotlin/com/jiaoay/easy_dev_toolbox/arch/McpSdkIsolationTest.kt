package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MCP SDK 类型隔离守护（P5 openspec mcp-expose 任务 5.1——LarkSdkIsolation/SherpaOnnxIsolation
 * 同款模式）。
 *
 * 目标：`io.modelcontextprotocol.*` 与 `io.ktor.*` 的类型面只允许出现在 :feature-mcp 的
 * 三个装配层文件（design D1）：
 * - `McpServerManager.kt`（embeddedServer CIO + 生命周期）
 * - `McpToolRegistrar.kt`（描述符 → MCP Tool 映射 + 分发）
 * - `McpAgentTools.kt`（agent 会话工具）
 * 页面/VM 经本模块门面消费（零 Ktor/SDK 类型——D1 隔离）。SDK 0.x API 未稳定，
 * 隔离面收窄 = 未来升级的改动面收敛到三文件。
 *
 * 匹配前提（同款取舍）：守护「类型依赖」而非「文字提及」——归一化剥注释与字符串
 * 字面量后再匹配。
 *
 * D 组风格补强：build.gradle.kts 的 SDK/Ktor 依赖引用只许出现在
 * `feature-mcp/build.gradle.kts` 与 `gradle/libs.versions.toml`（依赖声明单点）。
 */
class McpSdkIsolationTest {

    private val allowedFiles = setOf(
        "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpServerManager.kt",
        "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpToolRegistrar.kt",
        "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpAgentTools.kt",
        // jvmTest 里的 CallToolRequest/TextContent 构造是 SDK 契约直测面（dispatch 回环断言）——
        // 属装配层测试，与生产三文件同域（测试代码不外泄面）
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpToolRegistrarTest.kt",
        "feature-mcp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpAgentToolsTest.kt",
    )

    private val allowedBuildScripts = setOf(
        "feature-mcp/build.gradle.kts",
        "gradle/libs.versions.toml",
        // 宿主侧依赖行（implementation(project(":feature-mcp")) 不含 SDK 坐标，但
        // composeApp 是 jpackage 面——出现 modelcontextprotocol 字面量只会在依赖注释，防御放行）
    )

    /** 归一化：剥行注释/块注释/字符串字面量，保留换行（行号对齐） */
    private fun normalizeKotlinSource(source: String): String = buildString {
        var i = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inRawString = false
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inLineComment -> if (c == '\n') { inLineComment = false; append('\n') }
                inBlockComment -> if (c == '*' && next == '/') { inBlockComment = false; i++ }
                inRawString -> if (c == '"' && next == '"' && source.getOrNull(i + 2) == '"') {
                    inRawString = false; i += 2
                } else if (c == '\n') append('\n')
                inString -> when {
                    c == '\\' && next != null -> i++
                    c == '"' -> inString = false
                    c == '\n' -> { inString = false; append('\n') }
                }
                else -> when {
                    c == '/' && next == '/' -> inLineComment = true
                    c == '/' && next == '*' -> { inBlockComment = true; i++ }
                    c == '"' && next == '"' && source.getOrNull(i + 2) == '"' -> { inRawString = true; i += 2 }
                    c == '"' -> inString = true
                    c == '\n' -> append('\n')
                    !c.isWhitespace() -> append(c)
                }
            }
            i++
        }
    }


    /**
     * A 组：全仓 .kt 源码归一化后，MCP SDK 类型引用只许出现在三装配层文件（+测试）。
     * 匹配 `io.modelcontextprotocol`（import/FQCN）与 SDK 门面类型名（CallToolRequest/
     * CallToolResult/TextContent/ToolSchema/Implementation/ServerCapabilities/mcpStatelessStreamableHttp）。
     */
    @Test
    fun `mcp sdk types stay inside three assembly files`() {
        val root = locateProjectRoot()
        val markers = listOf(
            "io.modelcontextprotocol",
            "CallToolRequest", "CallToolResult", "ToolSchema",
            "mcpStatelessStreamableHttp",
        )
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.absolutePath.contains("/src/") }
            .filterNot { it.absolutePath.contains("/build/") }
            .forEach { file ->
                val normalized = normalizeKotlinSource(file.readText())
                if (markers.any { it in normalized }) {
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (rel !in allowedFiles) offenders += rel
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "io.modelcontextprotocol.* / MCP SDK 门面类型只许出现在 $allowedFiles（design D1 隔离）；违规文件：\n${offenders.joinToString("\n")}",
        )
    }

    /** Ktor 合法出现面：MCP server 装配（本批）+ :ui 头像库网络引擎（2026-09-14 既有——coil ktor3 线）。 */
    private val ktorAllowedFiles = allowedFiles + setOf(
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/AvatarImage.kt",
    )

    /**
     * B 组：Ktor 类型引用收窄——`io.ktor` import/FQCN 只许在 MCP server 装配文件与
     * :ui 头像库（coil-network-ktor3 既有线）。其他模块（:core/其他 feature/宿主）零
     * Ktor 编译期依赖。
     */
    @Test
    fun `ktor types stay in server manager only`() {
        val root = locateProjectRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.absolutePath.contains("/src/") }
            .filterNot { it.absolutePath.contains("/build/") }
            .forEach { file ->
                val normalized = normalizeKotlinSource(file.readText())
                if ("io.ktor" in normalized) {
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (rel !in ktorAllowedFiles) offenders += rel
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "io.ktor.* 编译期引用只许出现在 $ktorAllowedFiles（embeddedServer 唯一装配点）；违规文件：\n${offenders.joinToString("\n")}",
        )
    }

    /** D 组风格：构建脚本 SDK/Ktor 依赖坐标引用单点收口。 */
    @Test
    fun `mcp and ktor sdk coordinates stay in build scripts allowlist`() {
        val root = locateProjectRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && (it.name == "build.gradle.kts" || it.name == "libs.versions.toml") }
            .filterNot { it.absolutePath.contains("/build/") }
            .forEach { file ->
                if ("modelcontextprotocol" in file.readText() || "ktor-server" in file.readText()) {
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (rel !in allowedBuildScripts) offenders += rel
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "MCP SDK / ktor-server 依赖坐标只许在 $allowedBuildScripts（依赖声明单点）；违规：$offenders",
        )
    }

    /**
     * 防空扫描：三个白名单文件必须存在且真实引用 SDK 面（守护不永绿——
     * 实现删除后须同步清理守护白名单）。
     */
    @Test
    fun `allowed files exist and really use mcp sdk`() {
        val root = locateProjectRoot()
        val registrar = File(root, "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpToolRegistrar.kt")
        assertTrue(registrar.isFile, "McpToolRegistrar.kt 必须存在（白名单锚防腐化）")
        assertTrue(
            "io.modelcontextprotocol" in normalizeKotlinSource(registrar.readText()),
            "白名单文件必须真实引用 SDK 面（Registrar 是工具注册锚）",
        )
        val agentTools = File(root, "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpAgentTools.kt")
        assertTrue(agentTools.isFile, "McpAgentTools.kt 必须存在")
        assertEquals(
            allowedFiles.size,
            allowedFiles.count { File(root, it).isFile },
            "白名单文件须全部在位（缺文件 = 守护白名单漂移）：$allowedFiles",
        )
    }
}
