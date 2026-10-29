package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * sherpa-onnx 类型隔离守护（P4-S openspec voice-transcribe 任务 3.3——
 * LarkSdkIsolationTest 同款模式，聚焦类型泄漏面）。
 *
 * 目标：`com.k2fsa.sherpa.onnx.*` 的 JNI 面只允许出现在
 * `feature-voice/.../voice/SherpaTranscriber.kt`（design D7/D8 单文件钉死）——
 * 页面/VM/页面层经 [Transcriber] 接口消费，JNI 类型零外泄。
 *
 * 匹配前提（与 LarkSdkIsolationTest 同款取舍）：守护「类型依赖」而非「文字提及」——
 * 归一化剥注释与字符串字面量后再匹配。`files("libs/sherpa-onnx-jvm-1.13.8.jar")`
 * 这类构建脚本字符串不在扫描面（只扫 .kt 源码）。
 *
 * D 组风格补强：build.gradle.kts 的 jar 文件引用也收口——`sherpa-onnx` 字面量在
 * 构建脚本中只许出现在 `feature-voice/build.gradle.kts`（依赖声明单点）。
 */
class SherpaOnnxIsolationTest {

    private val allowedFile = "feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/SherpaTranscriber.kt"
    private val allowedBuildScript = "feature-voice/build.gradle.kts"

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
     * B 组：全仓 .kt 源码归一化后，sherpa JNI 类型引用只许出现在 SherpaTranscriber.kt。
     * 匹配 `com.k2fsa.sherpa.onnx`（import/FQCN）与 `OfflineRecognizer` 等 API 面直接证据
     * （后者只可能在 import 后使用——归一化保留）。
     */
    @Test
    fun `sherpa jni types stay inside SherpaTranscriber`() {
        val root = locateProjectRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.absolutePath.contains("/src/") } // 只扫源码（build 产物 .kt 不存在但防御）
            .filterNot { it.absolutePath.contains("/build/") }
            .forEach { file ->
                val normalized = normalizeKotlinSource(file.readText())
                if ("com.k2fsa.sherpa.onnx" in normalized || "OfflineRecognizer" in normalized) {
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (rel != allowedFile) offenders += rel
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "com.k2fsa.sherpa.onnx.* JNI 面只许出现在 $allowedFile（design D8 隔离）；违规文件：\n${offenders.joinToString("\n")}",
        )
    }

    /** D 组风格：构建脚本 sherpa jar 引用单点收口（只许 feature-voice/build.gradle.kts） */
    @Test
    fun `sherpa jar references stay in voice build script`() {
        val root = locateProjectRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.name == "build.gradle.kts" }
            .filterNot { it.absolutePath.contains("/build/") }
            .forEach { file ->
                if ("sherpa-onnx" in file.readText()) {
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (rel != allowedBuildScript) offenders += rel
                }
            }
        assertTrue(offenders.isEmpty(), "sherpa-onnx jar 引用只许在 $allowedBuildScript；违规：$offenders")
    }

    /** 防空扫描：目标文件必须存在且确实引用 sherpa 面（守护不永绿） */
    @Test
    fun `allowed file exists and really uses sherpa`() {
        val root = locateProjectRoot()
        val file = File(root, allowedFile)
        assertTrue(file.isFile, "SherpaTranscriber.kt 必须存在（白名单锚防腐化）")
        val normalized = normalizeKotlinSource(file.readText())
        assertTrue(
            "com.k2fsa.sherpa.onnx" in normalized || "OfflineRecognizer" in normalized,
            "白名单文件必须真实使用 sherpa 面（删除实现后须同步清理守护白名单）",
        )
        assertEquals(1, File(root, allowedBuildScript).isFile.let { if (it) 1 else 0 }, "voice 构建脚本必须存在")
    }
}
