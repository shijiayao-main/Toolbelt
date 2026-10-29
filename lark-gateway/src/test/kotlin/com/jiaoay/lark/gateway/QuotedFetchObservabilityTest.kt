// fetchQuotedMessage 诊断盲区守护（2026-09-12 真机取证批）：5 条静默 null 路径必须各有分类日志。
// 🔴 背景：真机 22:02 会话——补查执行了但既无成功 INFO 也无失败 WARN，事后无法回答
//    「被引用的消息为什么没取回」。本测试用源码 grep（stripComments 剥注释防假红/假绿，
//    与 FileDownloadStreamingTest 同款取舍：只剥注释、不剥字符串字面量）钉死每条路径的日志字面量。
package com.jiaoay.lark.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class QuotedFetchObservabilityTest {

    @Test
    fun `五条静默 null 路径各有分类日志`() {
        val src = stripComments(readGatewaySource())
        val fetch = src.substringAfter("override suspend fun fetchQuotedMessage(").substringBefore("\n    /** 告警限流")
        assertTrue(fetch.isNotEmpty(), "定位不到 fetchQuotedMessage 函数体")
        // ① rawClient null（异常态 ⇒ WARN）
        assertTrue("rawClient 为空" in fetch, "rawClient-null 路径须有 WARN 日志")
        // ② items 空
        assertTrue("items 为空" in fetch, "items-empty 路径须有分类 INFO")
        // ③ deleted
        assertTrue("消息已删除" in fetch, "deleted 路径须有分类 INFO")
        // ④ content 空
        assertTrue("body.content 为空" in fetch, "content-null 路径须有分类 INFO")
        // ⑤ 类型不支持 / 形态不识别（带 msgType 与 content 片段）
        assertTrue("不支持引用上下文或 content 形态不识别" in fetch, "type-unsupported 路径须有分类 INFO（含 msgType 与 content 片段）")
    }

    @Test
    fun `file 补查双形态兼容存在（message get 原始 JSON 分支）`() {
        val src = stripComments(readGatewaySource(QUOTED_PARSERS_SOURCE))
        val parse = src.substringAfter("internal fun parseQuotedFileContent(").substringBefore("\n/** text content 解析")
        assertTrue("file_key" in parse, "🔴 file 分支必须兼容 message.get 原始 JSON（{\"file_key\":…}）——修复前补查路径的文件消息永远静默失败")
        assertTrue("file_name" in parse, "原始 JSON 形态的 file_name 同样须解析")
    }

    /** 路径锚自述：源文件被移动/重命名须同步更新本测试的路径常量。 */
    private fun readGatewaySource(relativePath: String = GATEWAY_SOURCE): String {
        val f = File(repoRoot(), relativePath)
        assertTrue(f.isFile, "定位不到源文件（移动须同步更新本测试路径锚）：$relativePath")
        return f.readText()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    private companion object {
        const val GATEWAY_SOURCE = "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt"
        const val QUOTED_PARSERS_SOURCE = "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/QuotedContentParsers.kt"
    }

    /** 剥行注释与块注释内容（保换行）；不剥字符串字面量——与 FileDownloadStreamingTest.stripComments 同款取舍。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' -> {
                    out.append(c); i++
                    while (i < src.length) {
                        val d = src[i]
                        if (d == '\\' && i + 1 < src.length) {
                            out.append(d).append(src[i + 1]); i += 2; continue
                        }
                        out.append(d); i++
                        if (d == '"') break
                    }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i < src.length && !(src[i] == '*' && i + 1 < src.length && src[i + 1] == '/')) {
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
