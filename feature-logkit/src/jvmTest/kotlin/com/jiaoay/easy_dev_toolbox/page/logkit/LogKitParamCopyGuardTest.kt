package com.jiaoay.easy_dev_toolbox.page.logkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TL-07 · 参数文案中文化源码锚守护（重设计批 11 · 2026-10-02）：
 *
 * 口径钉死（勘误 4——yaml title/roadmap 三处一致为真，proposal 正文「输出文件
 * 标记」系转写漂移作废）：「Tag 名称」→「输出标记」；「Private Key」→「私钥」。
 * 三面同词：页面（LogTagFilterPage）/ 命令面（LogKitContributions palette
 * ParamSpec）/ 校验面（LogTagFilterViewModel + LogKitContributions 错误文案）。
 *
 * 验收面：
 * 1. XLog 参数区 label「私钥」（placeholder「输入 xlog 私钥（加密日志必填）」
 *    场景词不动）；
 * 2. Tag 过滤参数区第二框 label「输出标记」+ placeholder「用于输出文件命名，如
 *    MyTag」；palette 同名参数 label「输出标记」同步（命令面与页面同词）；
 * 3. 错误文案「输出标记不能为空」两处（校验面 + 命令面）——卡面漏列，施工裁量
 *    一并改（否则三处两词）；
 * 4. 旧串「Private Key」「Tag 名称」「输出文件名标签」「输出文件标记」代码区零残留（剥注释；
 *    KDoc 迁移注记中的「Tag 名称」属历史记述豁免）。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class LogKitParamCopyGuardTest {

    // ── ① 新串在位（页面 / palette 命令面 / 校验面 三面同词）──

    @Test
    fun `xlog label is localized to si-yao`() {
        val code = stripComments(file(XLOG_PAGE).readText())
        assertTrue(
            "Text(\"私钥\")" in code,
            "XLogDecoderPage 私钥框 label 须为「私钥」（TL-07：Private Key 中文化）",
        )
        assertTrue(
            "输入 xlog 私钥（加密日志必填）" in code,
            "placeholder「输入 xlog 私钥（加密日志必填）」不动（场景词保留——TL-07 勘误 3）",
        )
    }

    @Test
    fun `tag filter second field label is output marker`() {
        val code = stripComments(file(LOGTAG_PAGE).readText())
        assertTrue(
            "Text(\"输出标记\")" in code,
            "LogTagFilterPage 第二框 label 须为「输出标记」（TL-07：Tag 名称改口径——" +
                "实际用途是输出文件命名标记）",
        )
        assertTrue(
            "用于输出文件命名，如 MyTag" in code,
            "placeholder 须为「用于输出文件命名，如 MyTag」（TL-07 卡面）",
        )
    }

    @Test
    fun `palette param spec label matches page wording`() {
        val code = stripComments(file(LOGKIT_CONTRIBUTIONS).readText())
        assertTrue(
            "ParamSpec(id = \"tag_name\", label = \"输出标记\"" in code,
            "palette tag_name 参数 label 须为「输出标记」（命令面与页面同词——TL-07）",
        )
        assertTrue(
            "输出标记不能为空" in code,
            "命令面校验文案须为「输出标记不能为空」（卡面漏列施工裁量一并改——三面同词）",
        )
    }

    @Test
    fun `viewmodel validation copy matches page wording`() {
        val code = stripComments(file(LOGTAG_VIEWMODEL).readText())
        assertTrue(
            "输出标记不能为空" in code,
            "LogTagFilterViewModel 校验文案须为「输出标记不能为空」（校验面与页面/命令面同词）",
        )
    }

    // ── ② 旧串零残留（剥注释——KDoc 迁移注记豁免）──

    @Test
    fun `legacy copy strings purged from code sections`() {
        val offenders = buildList {
            val xlog = stripComments(file(XLOG_PAGE).readText())
            if ("Private Key" in xlog) add("XLogDecoderPage 代码区残留「Private Key」")
            for (path in listOf(LOGTAG_PAGE, LOGKIT_CONTRIBUTIONS, LOGTAG_VIEWMODEL)) {
                val code = stripComments(file(path).readText())
                val name = path.substringAfterLast('/')
                if ("Tag 名称" in code) add("$name 代码区残留「Tag 名称」")
                if ("输出文件名标签" in code) add("$name 代码区残留「输出文件名标签」")
                if ("输出文件标记" in code) add("$name 代码区残留「输出文件标记」（蜂群裁决 2026-10-06：label 终局=「输出标记」，勿再加字回潮）")
            }
        }
        if (offenders.isNotEmpty()) {
            fail("TL-07 守护：旧文案代码区残留：\n${offenders.joinToString("\n")}")
        }
    }

    // ── stripComments（LogTagFilterPageWiringGuardTest 同款语义）──

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

    private fun file(relPath: String): File {
        val f = File(locateProjectRoot(), relPath)
        assertTrue(f.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return f
    }

    /** 项目根定位（composeApp arch/GuardTestSupport 同款配方——跨模块测试源集不可见，本模块自持） */
    private fun locateProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val XLOG_PAGE =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/xlog/XLogDecoderPage.kt"
        const val LOGTAG_PAGE =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logtag/LogTagFilterPage.kt"
        const val LOGKIT_CONTRIBUTIONS =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logkit/LogKitContributions.kt"
        const val LOGTAG_VIEWMODEL =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logtag/LogTagFilterViewModel.kt"
    }
}
