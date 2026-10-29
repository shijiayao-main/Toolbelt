package com.jiaoay.easy_dev_toolbox.page.logtag

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TL-04 · 正则框即时反馈源码锚守护（重设计批 1）：VM 行为由
 * [LogTagFilterViewModelTest] 断言；本测试扫源码钉死 UI 装配面
 * （防将来 supportingText/isError 接线被改回静默形态）：
 * 1. 正则框消费 regexValid（isError 与 supportingText 双锚——缺任一
 *    即「输入即时反馈」半残）；
 * 2. supportingText 文案「正则语法有误」（与模板 inputError 横幅文案
 *    「Tag 正则语法有误…」同一术语系，防文案漂移成两套口径）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-logkit:jvmTest --rerun-tasks --no-build-cache`。
 */
class LogTagFilterPageWiringGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    /** 剥除 // 行注释与 /* */ 块注释（含 KDoc），字符串内容保留——UiArchitectureGuardTest 同款语义 */
    private fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var inLine = false
        var inBlock = false
        var inString = false
        var inRawString = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inLine -> if (c == '\n') {
                    inLine = false
                    sb.append(c)
                }

                inBlock -> if (c == '*' && next == '/') {
                    inBlock = false
                    i++
                }

                inRawString -> when {
                    c == '"' && source.getOrNull(i + 1) == '"' && source.getOrNull(i + 2) == '"' -> {
                        sb.append("\"\"\"")
                        i += 2
                        inRawString = false
                    }

                    else -> sb.append(c)
                }

                inString -> when {
                    c == '\\' && next != null -> {
                        sb.append(c).append(next)
                        i++
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = false
                    }

                    else -> sb.append(c)
                }

                else -> when {
                    c == '/' && next == '/' -> {
                        inLine = true
                        i++
                    }

                    c == '/' && next == '*' -> {
                        inBlock = true
                        i++
                    }

                    c == '"' && source.getOrNull(i + 1) == '"' && source.getOrNull(i + 2) == '"' -> {
                        sb.append("\"\"\"")
                        i += 2
                        inRawString = true
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = true
                    }

                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    private fun pageCode(): String {
        val file = File(
            projectRoot(),
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logtag/LogTagFilterPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return stripComments(file.readText())
    }

    @Test
    fun `regex field consumes regexValid for isError and supportingText`() {
        val code = pageCode()
        val missing = buildList {
            if (!code.contains("viewModel.regexValid.collectAsState()")) {
                add("页面未收集 regexValid（VM 校验态未接入 UI）")
            }
            if (!code.contains("isError = !regexValid && tagRegex.isNotBlank()")) {
                add("正则框 isError 未按 regexValid && 非空判定（空串误报错/非法不标红）")
            }
            if (!code.contains("supportingText = when")) {
                add("正则框缺 supportingText 槽（isError 标红但无文字说明 = 反馈半残）")
            }
        }
        if (missing.isNotEmpty()) {
            fail("TL-04 守护：LogTagFilterPage 正则框即时反馈接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    @Test
    fun `supporting text copy stays on regex-syntax terminology`() {
        val code = pageCode()
        // 2026-10-04 TL-D3① 重锚：四要素句「Tag 正则语法有误：$reason」（稿面错误
        // 四要素——原因具体化；术语系「正则语法有误」保留）
        assertTrue(
            "Tag 正则语法有误" in code,
            "TL-04 守护：supportingText 术语系「正则语法有误」保留（TL-D3① 四要素句" +
                "「Tag 正则语法有误：<原因>」）",
        )
    }
}
