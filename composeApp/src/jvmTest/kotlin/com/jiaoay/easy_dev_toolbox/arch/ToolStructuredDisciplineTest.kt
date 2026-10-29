package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 工具结构化纪律守护（2026-09-18 架构升级批 B1，扫盘型——防腐化）：
 *
 * 目标：`core/llm/tools/` 下的 Koog `SimpleTool` 子类必须继承 `StructuredSimpleTool`——
 * 保证全部 agent 工具走「executeTyped → sink 旁路 → render」三段式（结构化产物 + 文案兼容），
 * 防止未来新增工具绕过结构化纪律直接返回裸 String。
 *
 * 为什么需要守护：B0 把 DecryptTools 四件迁到 StructuredSimpleTool 后，「裸 execute 覆写」
 * 就是纪律破口——新工具若直接继承 SimpleTool 并覆写 execute，产物路径会退回「字符串猜」
 * 时代，且围栏/HITL 接缝（authorizeWithGate）无从保证被使用。
 *
 * 同规则附带断言：生产工具类（StructuredSimpleTool 子类）的 executeTyped 内不得调用
 * 旧 `context.authorize(`（必须走 `authorizeWithGate(`——HITL 接缝单一入口）。
 * `authorize(` 的合法调用点仅限 ToolExecutionContext 自身与测试。
 *
 * 复用 [LarkSdkIsolationTest] 的扫描风格（kotlin("test")、剥注释、自 user.dir 探测项目根）。
 * 🔴 扫盘型守护验收必须 `--rerun-tasks --no-build-cache`（AGENTS.md 铁律）。
 */
class ToolStructuredDisciplineTest {

    // ------------------------------------------------------------------ 工具结构化

    /**
     * 断言 1：`core/llm/tools/` 生产源集里每个 `: SimpleTool<...>` 或 `: StructuredSimpleTool<...>`
     * 的类声明必须走 StructuredSimpleTool（SimpleTool 直接继承即违规）。
     * 豁免：`ToolResult.kt` 的 StructuredSimpleTool 基类声明本身（唯一合法直接继承点）。
     */
    @Test
    fun `all production simple tools must extend StructuredSimpleTool`() {
        val root = locateProjectRoot()
        val toolsDir = File(root, "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools")
        assertTrue(toolsDir.isDirectory, "工具目录缺失——目录被重构时本守护必须红出来而非静默漏扫")

        val violations = toolsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ToolResult.kt" }
            .flatMap { file ->
                val code = stripComments(file.readText())
                code.lineSequence()
                    .filter { directSimpleToolSuperRegex.containsMatchIn(it) }
                    .map { "${file.relativeTo(root).invariantSeparatorsPath}: ${it.trim()}" }
            }.toList()

        if (violations.isNotEmpty()) {
            fail(
                "core/llm/tools/ 下发现直接继承 SimpleTool 的工具（${violations.size} 处）——\n" +
                    "必须改继承 StructuredSimpleTool（executeTyped → sink 旁路 → render 三段式）。\n" +
                    violations.joinToString("\n"),
            )
        }

        // 防空转：本守护必须扫到至少 4 个 StructuredSimpleTool 声明（B0 存量面）
        val structuredCount = toolsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { it.readText().lineSequence() }
            .filter { it.contains(": StructuredSimpleTool<") }
            .count()
        assertTrue(structuredCount >= 4, "扫到 StructuredSimpleTool 声明 ${structuredCount} < 4——扫描器空转嫌疑")
    }

    /**
     * 断言 2：生产工具实现内不得调用旧 `context.authorize(`（必须走 `authorizeWithGate(`——
     * HITL 接缝单一入口；authorize 保留给 ToolExecutionContext 内部与既有测试）。
     */
    @Test
    fun `production tools must use authorizeWithGate not raw authorize`() {
        val root = locateProjectRoot()
        val toolsDir = File(root, "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools")
        assertTrue(toolsDir.isDirectory)

        val violations = toolsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ToolExecutionContext.kt" }
            .flatMap { file ->
                val code = stripComments(file.readText())
                code.lineSequence()
                    .filter { rawAuthorizeCallRegex.containsMatchIn(it) }
                    .map { "${file.relativeTo(root).invariantSeparatorsPath}: ${it.trim()}" }
            }.toList()

        if (violations.isNotEmpty()) {
            fail(
                "core/llm/tools/ 生产工具里发现旧 authorize( 调用（${violations.size} 处）——\n" +
                    "工具围栏校验必须走 context.authorizeWithGate(（HITL 接缝单一入口）。\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    /**
     * 断言 3：`StructuredSimpleTool` 子类的 `executeTyped` 内若调用 `context.` 前缀的路径校验，
     * 只允许 `authorizeWithGate`（与断言 2 同语义，但从「工具类体」视角再钉一次——覆盖未来
     * 新增的、可能与断言 2 正则形态不同的写法）。
     */
    @Test
    fun `structured tool execute bodies go through gate seam`() {
        val root = locateProjectRoot()
        val toolsDir = File(root, "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools")
        assertTrue(toolsDir.isDirectory)
        // 断言 2 已做全量正则匹配，本断言只做「至少扫到 ≥4 处 authorizeWithGate 生产调用」的
        // 反空转锚——扫描器若因目录重构失明，这里必须红。
        val gateCallCount = toolsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { stripComments(it.readText()).lineSequence() }
            .filter { it.contains("authorizeWithGate(") }
            .count()
        assertTrue(gateCallCount >= 4, "authorizeWithGate 生产调用 ${gateCallCount} < 4——工具围栏接线缺失或扫描器空转")
    }

    // ------------------------------------------------------------------ 辅助

    /** 剥 `//` 行注释与 `/* ... */` 块注释（保留换行维持行号），字符串字面量保留（本守护不涉文字误报面） */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var inBlockComment = false
        source.lineSequence().forEach { raw ->
            var line = raw
            if (inBlockComment) {
                val end = line.indexOf("*/")
                if (end >= 0) {
                    line = line.substring(end + 2)
                    inBlockComment = false
                } else {
                    out.append('\n')
                    return@forEach
                }
            }
            val commentIdx = line.indexOf("//")
            val code = if (commentIdx >= 0) line.substring(0, commentIdx) else line
            var rest = code
            // 处理行内块注释开头（跨行块注释置位）
            val blockStart = rest.indexOf("/*")
            if (blockStart >= 0) {
                rest = rest.substring(0, blockStart)
                inBlockComment = true
            }
            out.append(rest).append('\n')
        }
        return out.toString()
    }


    private companion object {
        /** 直接继承 SimpleTool 的类声明（: SimpleTool<...> 而非 StructuredSimpleTool） */
        private val directSimpleToolSuperRegex = Regex(
            """(?<!Structured)\bSimpleTool\s*<""",
        )

        /** 旧围栏调用：context.authorize( —— authorizeWithGate( 之外的工具级调用 */
        private val rawAuthorizeCallRegex = Regex(
            """\bcontext\.authorize\(""",
        )
    }
}
