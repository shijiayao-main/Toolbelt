package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ReportDelivery 消费纪律守护。
 *
 * [ReportDelivery.deliver] 的契约是「返回 null = 已送出；非 null = 失败人话」——
 * 因此消费面的合法形态恒为「先收 reason 再判空」。任何 `deliver(...) ?: 人话失败`
 * 形态的 elvis 都会把**成功吞成失败**（null 走 elvis 右支）——这是静默错行为：
 * 文件已送出，用户却看到失败提示。本守护把这条红线从「人工纪律」固化为构建门禁：
 *
 * - A 组：全仓生产源集 `ReportDelivery.deliver(` 位点计数棘轮——位点是新增/消失
 *   都必须同批改锚登记理由，不许静默漂移（新消费面不入锚即红）；
 * - B 组：elvis 反模式全仓零容忍——归一化后每个 deliver 调用点的闭括号后紧跟
 *   `?:` 即违规（多行调用按括号配对跨行追踪，不靠单行正则）；
 * - C 组：位点提取 + elvis 判定的内联自证（多行/FQCN/嵌套括号/字符串与注释内
 *   token 不计），确保匹配器不误伤也不漏判。
 *
 * ## 扫描面与归一化
 *
 * 扫描面 = 全仓**生产源集**（`src/jvmMain` / `src/main` / `src/commonMain` /
 * `src/androidMain`，测试源集不纳入生产纪律）；匹配前用 [normalizeKotlinSource]
 * 剥离注释与字符串字面量内容（保留换行维持行号对齐）——字符串/注释里出现的
 * token 文字不算消费位点。归一化与根路径遍历配方与 [LarkSdkIsolationTest] 同源
 * （其 helper 为 private，故本文件自带一份实现）。
 *
 * 验收纪律：本测试是扫盘型守护，一律 `--rerun-tasks --no-build-cache` 跑
 * （build cache 会假绿）。
 */
class ReportDeliveryDisciplineTest {

    /** 一个 deliver 消费位点：相对项目根路径 + 调用行号 */
    private data class DeliverSite(val relPath: String, val line: Int)

    /** 一次 elvis 反模式违规：路径 + 行号 + 定位说明 */
    private data class ElvisOffence(val relPath: String, val line: Int)

    // ------------------------------------------------------------------ 扫描面

    /** 项目根 */
    private val projectRoot: File by lazy { locateProjectRoot() }

    /** 全仓生产源文件（相对项目根路径），规模下限自检防「扫描根丢失 ⇒ 永绿」后门 */
    private val productionSources: List<Pair<File, String>> by lazy {
        val files = projectRoot.walkTopDown()
            .onEnter { it.name !in EXCLUDED_DIR_NAMES }
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.relativeTo(projectRoot).invariantSeparatorsPath }
            .filter { (_, rel) -> isProductionSourcePath(rel) }
            .sortedBy { it.second }
            .toList()
        check(files.size >= MIN_SOURCE_FILES) {
            "扫描面异常：仅发现 ${files.size} 个生产源文件（下限 $MIN_SOURCE_FILES）——" +
                "扫描根定位或源集剪枝失效，守护会静默空转"
        }
        files
    }

    // ------------------------------------------------------------------ A 组：位点计数棘轮

    /**
     * A：全仓生产源集的 `ReportDelivery.deliver(` 位点集合必须与 [expectedDeliverSites]
     * 棘轮锚**逐文件精确相等**（双向）：
     * - 新文件出现位点 = 新消费面未登记，须先改锚再落代码；
     * - 已登记文件的位点数只能同批增减改锚——位点消失后锚数字须降、条目归零须删，
     *   不许静默漂移（防腐化语义与 CommentGuardTest baseline 同口径）。
     */
    @Test
    fun `A deliver call sites match per-file ratchet anchor`() {
        val actual = mutableMapOf<String, Int>()
        for ((file, rel) in productionSources) {
            val count = countDeliverTokens(normalizeKotlinSource(file.readText()))
            if (count > 0) actual[rel] = count
        }
        val total = actual.values.sum()
        val unregistered = actual.filterKeys { it !in expectedDeliverSites }
        val stale = expectedDeliverSites.filterKeys { it !in actual }
        val drifted = actual.filter { (rel, n) -> expectedDeliverSites[rel] != null && expectedDeliverSites[rel] != n }
        if (total != EXPECTED_TOTAL_DELIVER_SITES || unregistered.isNotEmpty() || stale.isNotEmpty() || drifted.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("ReportDelivery.deliver 位点与棘轮锚漂移（消费面收口，不许静默漂移）：")
                    appendLine("  总数：实际 $total 处 ≠ 全局锚 $EXPECTED_TOTAL_DELIVER_SITES 处（位点增删须同批改锚并登记理由）")
                    unregistered.forEach { (rel, n) -> appendLine("  未登记新位点：$rel（$n 处）——新消费面须先改锚再落代码") }
                    stale.forEach { (rel, _) -> appendLine("  已消失位点：$rel——须降锚删除条目") }
                    drifted.forEach { (rel, n) -> appendLine("  数量漂移：$rel 实际 $n 处 ≠ 锚 ${expectedDeliverSites[rel]} 处") }
                    appendLine("合法消费形态：val reason = ReportDelivery.deliver(file) 后 if (reason == null) 判成功；")
                    appendLine("登记面 = 本测试 [expectedDeliverSites]（改锚须在 commit message 登记理由）。")
                },
            )
        }
    }

    // ------------------------------------------------------------------ B 组：elvis 反模式零容忍

    /**
     * B：全仓生产源集归一化后，任何 deliver 调用点的闭括号后（跳过空白，含换行）紧跟
     * `?:` 即违规——null 是成功语义，elvis 右支只在**成功**时执行。
     * 闭括号按括号配对跨行追踪，多行调用（含嵌套括号实参）不漏判。
     */
    @Test
    fun `B elvis after deliver call is forbidden everywhere`() {
        val offenders = mutableListOf<ElvisOffence>()
        for ((file, rel) in productionSources) {
            val normalized = normalizeKotlinSource(file.readText())
            var from = 0
            while (true) {
                val at = normalized.indexOf(SCAN_TOKEN, from)
                if (at < 0) break
                if (isElvisAfterDeliverCall(normalized, at)) {
                    offenders.add(ElvisOffence(rel, lineOf(normalized, at)))
                }
                from = at + SCAN_TOKEN.length
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("elvis 反模式：deliver 的 null 是「已送出」成功语义，`deliver(...) ?: 失败提示` 会把成功吞成失败（${offenders.size} 处）：")
                    offenders.forEach { appendLine("  ${it.relPath}:${it.line}") }
                    appendLine("合法形态：val reason = ReportDelivery.deliver(file)，随后 if (reason == null) 走成功分支。")
                },
            )
        }
    }

    // ------------------------------------------------------------------ C 组：匹配器内联自证

    /** C：位点提取与 elvis 判定的正反样本锁死——提取状态机写坏时先红这里，A/B 不空转。 */
    @Test
    fun `C matcher flags elvis forms and ignores token in comments and strings`() {
        // 违规①：单行 elvis（null=成功被吞成失败）——必须命中
        val elvisSingleLine = """
            val reason = ReportDelivery.deliver(file) ?: "发送失败"
        """.trimIndent()
        assertTrue(
            findElvisInSample(elvisSingleLine).isNotEmpty(),
            "单行 elvis 反模式必须被判违规",
        )

        // 违规②：多行全限定调用 + 尾随逗号 + elvis 跨行（生产多行位点同构形态）——必须命中
        val elvisMultiline = """
            val reason = com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery.deliver(
                File(outcome.path),
            ) ?: "发送失败"
        """.trimIndent()
        assertTrue(
            findElvisInSample(elvisMultiline).isNotEmpty(),
            "多行调用 + 跨行 elvis 必须被判违规（括号配对跨行追踪，不能靠单行正则）",
        )

        // 违规③：实参含嵌套括号 + elvis——括号配对必须穿透嵌套括号命中
        val elvisNestedArgs = """
            val reason = ReportDelivery.deliver(wrap(file)) ?: "发送失败"
        """.trimIndent()
        assertTrue(
            findElvisInSample(elvisNestedArgs).isNotEmpty(),
            "嵌套括号实参的 elvis 必须被判违规（闭括号配对不得停在实参内层括号）",
        )

        // 合法①：先收 reason 再判空（唯一合法消费形态）——必须通过
        val legitimate = """
            val reason = ReportDelivery.deliver(file)
            if (reason == null) {
                show("已送出")
            } else {
                show(reason)
            }
        """.trimIndent()
        assertTrue(
            findElvisInSample(legitimate).isEmpty(),
            "先收 reason 再判空的合法形态不得误报",
        )

        // 合法②：deliver 作为外层调用的实参、elvis 归属外层——elvis 不紧跟 deliver 闭括号，不得误报
        val elvisBelongsToOuter = """
            val x = wrap(ReportDelivery.deliver(file)) ?: fallback
        """.trimIndent()
        assertTrue(
            findElvisInSample(elvisBelongsToOuter).isEmpty(),
            "elvis 归属外层调用时不得误报 deliver",
        )

        // 合法③：token 出现在字符串字面量 / 注释里——非消费位点，不计数不判违规
        val tokenInNoise = """
            val s = "ReportDelivery.deliver(file) 的返回值"  // 注释里的 ReportDelivery.deliver(
            /* 块注释 ReportDelivery.deliver( */
        """.trimIndent()
        assertEquals(
            0,
            countDeliverTokens(normalizeKotlinSource(tokenInNoise)),
            "字符串/注释里的 token 文字不算消费位点",
        )
        assertTrue(
            findElvisInSample(tokenInNoise).isEmpty(),
            "字符串/注释里的 token 文字不得触发 elvis 判定",
        )

        // 位点计数自证：合法形态恰 1 处 token（A 组计数口径与 C 组一致）
        assertEquals(
            1,
            countDeliverTokens(normalizeKotlinSource(legitimate)),
            "合法形态应恰计 1 个位点",
        )
    }

    // ------------------------------------------------------------------ 匹配实现

    /** 归一化后样本里的全部 elvis 违规（行号相对样本自足，仅供 C 组断言非空性） */
    private fun findElvisInSample(raw: String): List<Int> {
        val normalized = normalizeKotlinSource(raw)
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = normalized.indexOf(SCAN_TOKEN, from)
            if (at < 0) break
            if (isElvisAfterDeliverCall(normalized, at)) hits.add(lineOf(normalized, at))
            from = at + SCAN_TOKEN.length
        }
        return hits
    }

    /** 归一化后源码里的 `ReportDelivery.deliver(` token 计数 */
    private fun countDeliverTokens(normalized: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = normalized.indexOf(SCAN_TOKEN, from)
            if (at < 0) return count
            count++
            from = at + SCAN_TOKEN.length
        }
    }

    /**
     * deliver 调用点（token 起点 [callStart]）的闭括号后（跳过空白含换行）是否紧跟 `?:`。
     * 闭括号按深度配对追踪（跳过字符字面量），多行与嵌套括号实参均正确。
     */
    private fun isElvisAfterDeliverCall(normalized: String, callStart: Int): Boolean {
        val open = normalized.indexOf('(', callStart)
        if (open < 0) return false
        val close = matchParen(normalized, open)
        if (close < 0) return false
        var i = close + 1
        while (i < normalized.length && normalized[i].isWhitespace()) i++
        return normalized.startsWith("?:", i)
    }

    /** 返回与 [open] 处 `(` 配对的 `)` 位置（跳过字符字面量；字符串内容已归一化清空），无配对返回 -1 */
    private fun matchParen(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (c == '\'') {
                i++
                while (i < s.length) {
                    if (s[i] == '\\') { i += 2; continue }
                    if (s[i] == '\'') { i++; break }
                    i++
                }
                continue
            }
            if (c == '(') depth++
            else if (c == ')') { depth--; if (depth == 0) return i }
            i++
        }
        return -1
    }

    /** char offset → 1-based 行号（归一化保留换行，行号与原文件对齐；样本内为样本相对行号） */
    private fun lineOf(s: String, pos: Int): Int {
        var line = 1
        for (i in 0 until minOf(pos, s.length)) if (s[i] == '\n') line++
        return line
    }

    /**
     * 归一化：剥离行注释、块注释内容（含跨行 KDoc，按深度配对防嵌套误闭合）、字符串字面量内容
     * （普通含转义、原始三引号），保留全部换行以维持行号对齐。配方与 LarkSdkIsolationTest
     * 同源（其实现为 private，本文件自带一份）：deliver 守护只认代码区消费位点，
     * 字符串/注释里的 token 文字不算。
     */
    private fun normalizeKotlinSource(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    var depth = 1
                    while (i < source.length && depth > 0) {
                        when {
                            source[i] == '/' && i + 1 < source.length && source[i + 1] == '*' -> { depth++; i += 2 }
                            source[i] == '*' && i + 1 < source.length && source[i + 1] == '/' -> { depth--; i += 2 }
                            else -> { if (source[i] == '\n') sb.append('\n'); i++ }
                        }
                    }
                }
                c == '"' && next == '"' && i + 2 < source.length && source[i + 2] == '"' -> {
                    sb.append("\"\"\"")
                    i += 3
                    while (i < source.length) {
                        if (source[i] == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"') {
                            sb.append("\"\"\""); i += 3; break
                        }
                        if (source[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                c == '"' -> {
                    sb.append('"'); i++
                    while (i < source.length) {
                        val sc = source[i]
                        if (sc == '\\' && i + 1 < source.length) { i += 2; continue }
                        if (sc == '"') { sb.append('"'); i++; break }
                        if (sc == '\n') { sb.append('\n'); i++; break }
                        i++
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** 生产源集判定：`src/` 直下一级源集名不在测试源集黑名单内（测试源集外的 src 一律算生产面） */
    private fun isProductionSourcePath(rel: String): Boolean {
        val segments = rel.split('/')
        val srcIdx = segments.indexOf("src")
        if (srcIdx < 0 || srcIdx + 1 >= segments.size) return false
        return segments[srcIdx + 1] !in TEST_SOURCE_SETS
    }

    private companion object {

        /** 扫描 token：全限定调用（…core.runtime.ReportDelivery.deliver(）与 import 短名调用均天然命中 */
        const val SCAN_TOKEN = "ReportDelivery.deliver("

        /** 全局位点锚：位点总数增删须同批改 [expectedDeliverSites] 并登记理由 */
        const val EXPECTED_TOTAL_DELIVER_SITES = 7

        /**
         * deliver 消费位点棘轮锚（path → 登记位点数）：逐文件精确相等——新增消费面须先登记
         * 再落代码；位点消失须降锚/删条目；任何方向的漂移都在 A 组红出来。
         */
        val expectedDeliverSites: Map<String, Int> = mapOf(
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/deviceobs/PerfPanel.kt" to 1,
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/logdiff/LogDiffViewModel.kt" to 1,
            "feature-logcat/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logcat/LogcatViewModel.kt" to 1,
            "feature-logkit/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logtag/LogTagFilterViewModel.kt" to 1,
            "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchViewModel.kt" to 1,
            // ScreencastViewModel 面板路径位点已删：投屏面板单行化后快照回执行的唯
            // 一消费面在独立窗（ScreencastDetachedWindow.kt 的 windowOps 自持实现，
            // 位点保留在上行）——降锚 8→7，不许静默漂移
            "feature-screencast/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/ScreencastDetachedWindow.kt" to 1,
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/task/FileTaskViewModel.kt" to 1,
        )

        /** 测试源集黑名单（`src/` 直下一级；其余 src 面一律算生产源集） */
        val TEST_SOURCE_SETS = setOf("jvmTest", "test", "commonTest", "androidTest")

        /** 剪枝目录名（构建产物 / IDE / 临时目录，任何深度命中即不进入） */
        val EXCLUDED_DIR_NAMES = setOf(
            "build", ".gradle", ".kotlin", ".clikt_tmp", ".review_tmp", ".idea", "node_modules", ".git",
        )

        /** 生产源文件下限（防扫描根丢失/剪枝失效导致守护静默空转） */
        const val MIN_SOURCE_FILES = 500
    }
}
