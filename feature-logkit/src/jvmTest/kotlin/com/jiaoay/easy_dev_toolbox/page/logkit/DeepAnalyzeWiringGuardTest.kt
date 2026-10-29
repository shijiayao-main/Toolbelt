package com.jiaoay.easy_dev_toolbox.page.logkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TL-11 · 过滤/分割产物接「深度分析」源码锚守护（重设计批 11 · 2026-10-02，
 * 日志链闭环「解密 → 过滤/分割 → 工作台分析」）：
 *
 * 此前 onDeepAnalyze 仅 XLogDecoderPage 接线，过滤/分割产物卡无深链——用户需
 * 手动复制路径去工作台粘贴（audit-tl.md F11）。本卡两页各加 XLog 同款逐字配方：
 * `onDeepAnalyze = { outputPath -> DeepAnalyzeHook.offer(listOf(outputPath),
 * prefillNote) }`。
 *
 * 验收面：
 * 1. LogTagFilterPage / FileSplitterPage 各含 DeepAnalyzeHook.offer + 页别
 *    prefillNote 串（「Tag 过滤产物深度分析」/「文件分割产物深度分析」前缀——
 *    分割页动态追加「（N 卷）」后缀）；
 * 2. 解压成功卡无「深度分析」钮（语义不匹配——DeepAnalyzeHook 收文件路径列表，
 *    目录不接）：ArchivePage 零 DeepAnalyzeHook 引用；
 * 3. 投递面按产物语义分流：LogTagFilter 页与 XLog 页同源（listOf(outputPath)
 *    单文件源）；FileSplitter 页投全卷（allVolumesOf(outputPath) 扫同目录
 *    兄弟卷——只投首卷会把深度分析静默限死在片段 1/N，下游 prefill 本就
 *    支持多文件）。
 *
 * 行为注记（如实上报勘误 2）：AppViewModel wire 恒接线（AI 开关不门控）——
 * 「深度分析」点击恒跳工作台预填（工作台分析链零 LLM 也可跑），与 XLog 页
 * 现行为完全一致。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class DeepAnalyzeWiringGuardTest {

    // ── ① 两页接线在位（缺失即红——产物深链退役即违 TL-11）──

    @Test
    fun `tag filter page wires deep analyze with page specific note`() {
        val code = stripComments(file(LOGTAG_PAGE).readText())
        assertTrue(
            "onDeepAnalyze = { outputPath ->" in code &&
                "DeepAnalyzeHook.offer(listOf(outputPath), \"Tag 过滤产物深度分析\")" in code,
            "LogTagFilterPage 须接线 onDeepAnalyze → DeepAnalyzeHook.offer" +
                "（prefillNote「Tag 过滤产物深度分析」——TL-11 卡面逐字配方）",
        )
    }

    @Test
    fun `file split page wires deep analyze with page specific note`() {
        val code = stripComments(file(FILESPLIT_PAGE).readText())
        assertTrue(
            "onDeepAnalyze = { outputPath ->" in code &&
                "val volumes = allVolumesOf(outputPath)" in code &&
                "DeepAnalyzeHook.offer(volumes, \"文件分割产物深度分析" in code,
            "FileSplitterPage 须接线 onDeepAnalyze → DeepAnalyzeHook.offer" +
                "（prefillNote 前缀「文件分割产物深度分析」——TL-11 卡面配方；" +
                "投递面 = allVolumesOf(outputPath) 全卷，非首卷单文件）",
        )
    }

    // ── ② 解压页零引用（目录不接——语义不匹配维持现状）──

    @Test
    fun `archive page keeps zero deep analyze reference`() {
        val code = stripComments(file(ARCHIVE_PAGE).readText())
        assertFalse(
            "DeepAnalyzeHook" in code,
            "ArchivePage 不得引用 DeepAnalyzeHook（TL-11 验收 2：解压产物是目录，" +
                "语义不匹配——出现即越界接线）",
        )
        assertFalse(
            "onDeepAnalyze" in code,
            "ArchivePage 不得传 onDeepAnalyze（null 缺省 = 「深度分析」钮不渲染）",
        )
    }

    // ── ③ XLog 页既有接线不回退（三页同源配方基线）──

    @Test
    fun `xlog page deep analyze wiring stays`() {
        val code = stripComments(file(XLOG_PAGE).readText())
        assertTrue(
            "DeepAnalyzeHook.offer(listOf(outputPath), \"XLog 解密产物深度分析\")" in code,
            "XLogDecoderPage 既有 onDeepAnalyze 接线不回退（TL-11 三页同源配方基线）",
        )
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
        const val LOGTAG_PAGE =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logtag/LogTagFilterPage.kt"
        const val FILESPLIT_PAGE =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/filesplit/FileSplitterPage.kt"
        const val ARCHIVE_PAGE =
            "feature-archive/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/archive/ArchivePage.kt"
        const val XLOG_PAGE =
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/xlog/XLogDecoderPage.kt"
    }
}
