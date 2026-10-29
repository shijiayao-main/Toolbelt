package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * FFM（java.lang.foreign）隔离守护——LarkSdkIsolationTest / SherpaOnnxIsolationTest
 * 同款模式，聚焦直调 native 的面。
 *
 * 目标：FFM 下调 native 符号的 API 面（java.lang.foreign / SymbolLookup /
 * FunctionDescriptor / downcallHandle）只允许出现在
 * `core/.../system/NativeAllocatorTuner.kt`——native 直调收口单点，防各 feature
 * 各自起 FFM 调用面后绕开隔离评审与平台门控。
 *
 * 匹配前提（同款取舍）：守护「API 依赖」而非「文字提及」——归一化剥注释与字符串
 * 字面量后再匹配；import 行保留在扫描面内（import 即依赖）。
 */
class ForeignLinkerIsolationTest {

    private val allowedFile = "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/system/NativeAllocatorTuner.kt"

    /** FFM API 依赖面指纹：import/FQCN/核心调用件，四选一命中即视为此面使用者 */
    private val ffmFaces = listOf(
        "java.lang.foreign",
        "SymbolLookup",
        "FunctionDescriptor",
        "downcallHandle",
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
     * 全仓 .kt 源码归一化后，FFM API 面只许出现在 NativeAllocatorTuner.kt。
     */
    @Test
    fun `foreign linker api stays inside NativeAllocatorTuner`() {
        val root = locateProjectRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.absolutePath.contains("/src/") }
            .filterNot { it.absolutePath.contains("/build/") }
            .forEach { file ->
                val normalized = normalizeKotlinSource(file.readText())
                if (ffmFaces.any { it in normalized }) {
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (rel != allowedFile) offenders += rel
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "java.lang.foreign FFM 面只许出现在 $allowedFile（native 直调收口单点）；违规文件：\n${offenders.joinToString("\n")}",
        )
    }

    /** 防空扫描：白名单文件必须存在且真实使用 FFM 面（守护不永绿） */
    @Test
    fun `allowed file exists and really uses ffm`() {
        val root = locateProjectRoot()
        val file = File(root, allowedFile)
        assertTrue(file.isFile, "NativeAllocatorTuner.kt 必须存在（白名单锚防腐化）")
        val normalized = normalizeKotlinSource(file.readText())
        assertTrue(
            ffmFaces.any { it in normalized },
            "白名单文件必须真实使用 FFM 面（删除实现后须同步清理守护白名单）",
        )
    }
}
