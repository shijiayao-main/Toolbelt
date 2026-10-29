package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 全仓 Window 组合点锚守护（锚=3 = 产品两扇窗 + 诊断 launcher 一窗）：main.kt 主窗 +
 * ScreencastDetachedWindow.kt 投屏独立视口窗 + realWindowDiag 诊断宿主主窗（jvmDiag
 * 源集、恒不进产品运行时，见锚内登记）。任何产品面第三窗、或绕过独立窗控制器
 * （ScreencastDetachedWindowController）在别处直接组合 Window 的新点，都使第二扇窗
 * 可表达性失效（防重窗硬需求失守）——新增 Window 组合点必须先改锚登记理由（棘轮
 * 纪律同 ReportDeliveryDisciplineTest）。
 *
 * 锚面 = `androidx.compose.ui.window.Window` 的 import / FQCN 引用位点（短名调用必经
 * import，双形态全收；同前缀类型 WindowState/WindowPosition/WindowScope 等由词边界
 * 排除）。扫描面与归一化配方与 [ReportDeliveryDisciplineTest] 同源（生产源集 +
 * 注释/字符串剥离 + 规模下限自证）。
 *
 * 验收纪律：本测试是扫盘型守护，一律 `--rerun-tasks --no-build-cache` 跑
 * （build cache 会假绿）。
 */
class WindowCompositionAnchorGuardTest {

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

    /**
     * A：全仓生产源集的 `androidx.compose.ui.window.Window` 位点集合必须与
     * [expectedWindowSites] 逐文件精确相等（双向）——新位点须先改锚再落代码，
     * 位点消失须降锚删条目，不许静默漂移。
     */
    @Test
    fun `A window composition sites match two-window anchor`() {
        val actual = mutableMapOf<String, Int>()
        for ((file, rel) in productionSources) {
            val count = countWindowTokens(normalizeKotlinSource(file.readText()))
            if (count > 0) actual[rel] = count
        }
        if (actual != expectedWindowSites) {
            fail(
                buildString {
                    appendLine("Window 组合点与锚漂移（全仓两扇窗不变式，防重窗收口）：")
                    appendLine("  锚：${expectedWindowSites.keys.joinToString()}")
                    val unregistered = actual.filterKeys { it !in expectedWindowSites }
                    val stale = expectedWindowSites.filterKeys { it !in actual }
                    val drifted = actual.filter { (rel, n) -> expectedWindowSites[rel] != null && expectedWindowSites[rel] != n }
                    unregistered.forEach { (rel, n) -> appendLine("  未登记新位点：$rel（$n 处）——新窗须先改锚并登记理由再落代码") }
                    stale.forEach { (rel, _) -> appendLine("  已消失位点：$rel——须降锚删除条目") }
                    drifted.forEach { (rel, n) -> appendLine("  数量漂移：$rel 实际 $n 处 ≠ 锚 ${expectedWindowSites[rel]} 处") }
                },
            )
        }
    }

    /** B：匹配器负向自证——同前缀类型/注释字符串不误伤，import/FQCN/别名 import 全命中 */
    @Test
    fun `B matcher self proof boundary comment and alias handling`() {
        // 命中：import 短名（生产 main.kt 形态）
        assertEquals(1, countWindowTokens(normalize("import androidx.compose.ui.window.Window")))
        // 命中：FQCN 调用（绕过 import 的形态）
        assertEquals(
            1,
            countWindowTokens(normalize("androidx.compose.ui.window.Window(onCloseRequest = {})")),
        )
        // 命中：别名 import（import ... as X 仍占一个组合点）
        assertEquals(
            1,
            countWindowTokens(normalize("import androidx.compose.ui.window.Window as DetachedWindow")),
        )

        // 不误伤：同前缀类型（WindowState/WindowPosition/WindowScope/WindowDecoration/
        // WindowExceptionHandler/WindowRecreator 词边界排除——next char 为字母即不算）
        val samePrefix = """
            import androidx.compose.ui.window.WindowState
            import androidx.compose.ui.window.WindowPosition
            import androidx.compose.ui.window.WindowScope
            import androidx.compose.ui.window.WindowDecoration
            val s: androidx.compose.ui.window.WindowExceptionHandlerFactory? = null
        """.trimIndent()
        assertEquals(0, countWindowTokens(normalize(samePrefix)), "同前缀类型不得计入 Window 组合点")

        // 不误伤：注释与字符串里的 token（归一化剥离后不计）
        val noise = """
            // androidx.compose.ui.window.Window 组合点注释示例
            /* androidx.compose.ui.window.Window */
            val s = "androidx.compose.ui.window.Window"
        """.trimIndent()
        assertEquals(0, countWindowTokens(normalize(noise)), "注释/字符串里的 token 不算组合点")

        // 命中面完整：import + FQCN 同文件两处都计（位点计数口径 = 出现次数）
        assertEquals(
            2,
            countWindowTokens(
                normalize(
                    "import androidx.compose.ui.window.Window\n" +
                        "androidx.compose.ui.window.Window(onCloseRequest = {})",
                ),
            ),
        )
    }

    // ── 匹配实现（配方与 ReportDeliveryDisciplineTest 同源） ─────────────────

    /**
     * 归一化后源码里 `androidx.compose.ui.window.Window` token 计数：词边界判定——
     * token 后一字符为字母/数字/下划线即属更长标识符（WindowState 等），不计。
     */
    private fun countWindowTokens(normalized: String): Int {
        val token = "androidx.compose.ui.window.Window"
        var count = 0
        var from = 0
        while (true) {
            val at = normalized.indexOf(token, from)
            if (at < 0) return count
            val end = at + token.length
            val next = normalized.getOrNull(end)
            if (next == null || !next.isLetterOrDigit() && next != '_') count++
            from = end
        }
    }

    private fun normalize(raw: String): String = normalizeKotlinSource(raw)

    /**
     * 归一化：剥离行注释、块注释内容（含跨行 KDoc，按深度配对防嵌套误闭合）、字符串
     * 字面量内容（普通含转义、原始三引号），保留全部换行以维持行号对齐。
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

    private val projectRoot: File by lazy { locateProjectRoot() }

    private companion object {

        /** Window 组合点锚：path → 位点数（产品两扇窗——main.kt 主窗 + 投屏独立视口窗） */
        val expectedWindowSites: Map<String, Int> = mapOf(
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/main.kt" to 1,
            "feature-screencast/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/ScreencastDetachedWindow.kt" to 1,
            // 按需诊断通道的宿主主窗（feature-screencast jvmDiag 源集，realWindowDiag
            // JavaExec 显式触发、恒不进产品运行时）：真 ScreencastDetachedWindowHost 须在
            // 主窗内容树内组合才能复刻产品拓扑，故诊断 launcher 持第三窗位点——非产品窗
            "feature-screencast/src/jvmDiag/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/diag/RealWindowDiagMain.kt" to 1,
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
