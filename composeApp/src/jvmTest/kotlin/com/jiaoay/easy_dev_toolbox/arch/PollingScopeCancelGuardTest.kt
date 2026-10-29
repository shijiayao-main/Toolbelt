// 设备轮询/采样环的 runTest 收口守护。
// 挂死机理：DeviceMonitor 构造即启动「while (isActive) { delay(5s) }」常驻轮询环
//（页面门控 false 只跳过检测，delay 环照跑）；runTest 收尾会把共享 TestScheduler 泵到
// 静默——活环让虚拟时间无限推进、每拍 spawn 真子进程（登录 shell + adb），process
// reaper 风暴可把测试 worker 挂死、已退出的子进程成僵尸。故轮询/采样 scope 的 cancel
// 必须发生在 runTest 体内（finally）；@AfterTest 的兜底 cancel 来不及收束（收尾泵在
// runTest 返回前）。runBlocking + 手动 runCurrent/advanceTimeBy 是有界推进、无收尾泵，
// 不在守护面内。
// 🔴 本测试是扫盘型守护，一律 --rerun-tasks --no-build-cache 跑（build cache 会假绿）。
package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class PollingScopeCancelGuardTest {

    /**
     * A：直构 [DeviceMonitor] 且含 runTest 的测试文件——**每个** runTest 体必须含
     * finally 块且块内有 cancel 调用。
     *
     * per-body 收口的原因：构造 helper（newMonitor/monitor）与用例同文件时，文本扫描
     * 无法区分哪个用例经 helper 直构——同文件所有 runTest 体统一按携带活环面收口
     *（偏保守侧：不涉环的 benign 体补 finally+cancel 无害）。
     */
    @Test
    fun `A device monitor test files cancel polling scope inside every runTest body`() {
        val violations = mutableListOf<String>()
        val triggered = mutableListOf<String>()
        for ((rel, text) in jvmTestKtFiles()) {
            if (!MONITOR_CTOR.containsMatchIn(text)) continue
            val bodies = extractRunTestBodies(text)
            if (bodies.isEmpty()) continue // 无 runTest：TestScheduler 无人推进，收尾泵不存在
            triggered += rel
            bodies.forEachIndexed { idx, body ->
                if (!hasFinallyCancel(body)) {
                    violations += "$rel：第 ${idx + 1} 个 runTest 体缺 finally+cancel（活轮询环会让 runTest 收尾泵无限推进虚拟时钟）"
                }
            }
        }
        // 防盲断言：已知直构 runTest 面必须仍在触发集内（文件被移动/重构须同步更新本守护）
        listOf(POLLING_TEST_PATH, DETECTING_TEST_PATH).forEach { path ->
            assertTrue(path in triggered, "$path 未落入 Rule A 触发集——扫描面失效或文件被移动，须同步更新本守护")
        }
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("DeviceMonitor 测试文件的 runTest 收口违例 ${violations.size} 处：")
                    violations.forEach { appendLine("  - $it") }
                    appendLine("处置：轮询 scope 在 runTest 体内 finally cancel（配套纪律 KDoc 见 DeviceMonitorPollingTest 类头）。")
                },
            )
        }
    }

    /**
     * B：直构 [PerfSampler] 且驱动 startSampling 的 runTest 测试文件——文件级
     * finally+cancel 下限（至少一处）。
     *
     * 文件级（非 per-body）的原因：PerfSampler 仅 startSampling() 起环，无目标设备的
     * 起动是合法零环形态——「哪个 startSampling 调用真的起环」不可文本判定，per-body
     * 精度守不住；文件级下限防整文件无纪律，起环用例的逐体精度由人工评审面承销。
     */
    @Test
    fun `B perf sampler test files driving startSampling keep a finally cancel floor`() {
        val violations = mutableListOf<String>()
        val triggered = mutableListOf<String>()
        for ((rel, text) in jvmTestKtFiles()) {
            if (!SAMPLER_CTOR.containsMatchIn(text)) continue
            if (!START_SAMPLING.containsMatchIn(text)) continue
            if (extractRunTestBodies(text).isEmpty()) continue
            triggered += rel
            if (!hasFinallyCancel(text)) {
                violations += "$rel：驱动 startSampling 的采样测试文件缺 finally+cancel 下限"
            }
        }
        assertTrue(
            PERF_SAMPLER_TEST_PATH in triggered,
            "$PERF_SAMPLER_TEST_PATH 未落入 Rule B 触发集——扫描面失效或文件被移动，须同步更新本守护",
        )
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("PerfSampler 采样测试文件的收口下限违例：")
                    violations.forEach { appendLine("  - $it") }
                    appendLine("处置：起环用例在 runTest 体内 finally cancel（同 Rule A 纪律）。")
                },
            )
        }
    }

    // ── 扫描基建 ──────────────────────────────────────────────────────

    /** 剥注释：行尾 // 与 KDoc/块注释行（星号打头）——契约词出现在注释里会假绿 */
    private fun stripComments(text: String): String = text.lines().joinToString("\n") { raw ->
        val code = raw.substringBefore("//")
        if (code.trimStart().startsWith("*")) "" else code
    }

    /** 宿主侧 jvmTest 源集全集（模块相对根 → 剥注释文本），扫描面与 AppHostGuardTest 的模块清单同口径 */
    private fun jvmTestKtFiles(): List<Pair<String, String>> {
        val root = locateProjectRoot()
        val dirs = HOST_MODULES.map { File(root, "$it/src/jvmTest/kotlin") }.filter { it.isDirectory }
        check(dirs.size == HOST_MODULES.size) { "jvmTest 扫描面目录缺失（${dirs.size}/${HOST_MODULES.size}）" }
        // 纯 JVM lib 的测试源集是 src/test/kotlin：设备域内核迁进 :device-core 后，
        // 轮询 scope 收束纪律同样适用于那里的测试，扫描面须一并覆盖
        val libDirs = LIB_MODULES.map { File(root, "$it/src/test/kotlin") }.filter { it.isDirectory }
        val allDirs = dirs + libDirs
        val files = allDirs.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.sortedBy { it.path }
        check(files.size >= MIN_KT_FILES) { "jvmTest 扫描数异常（${files.size} < $MIN_KT_FILES）：守护会静默失效" }
        return files.map { it.relativeTo(root).invariantSeparatorsPath to stripComments(it.readText()) }
    }

    /**
     * 提取全部 runTest lambda 体（剥注释后文本）。
     * 调用形态限定 `runTest(` 或 `runTest {`——import 行无跟随括号/花括号，天然排除；
     * 花括号配对为朴素深度计数（测试源里字符串字面量的花括号均配对平衡，如 ${}）。
     */
    private fun extractRunTestBodies(text: String): List<String> {
        val bodies = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = text.indexOf("runTest", from)
            if (at < 0) break
            from = at + "runTest".length
            // 词边界：前邻不得是标识符字符（防 MyrunTest 类误命中）
            if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '_')) continue
            var i = at + "runTest".length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) continue
            if (text[i] == '(') {
                val close = matchBracket(text, i, '(', ')') ?: continue
                i = close + 1
                while (i < text.length && text[i].isWhitespace()) i++
            }
            if (i >= text.length || text[i] != '{') continue
            val bodyEnd = matchBracket(text, i, '{', '}') ?: continue
            bodies += text.substring(i + 1, bodyEnd)
            from = bodyEnd + 1
        }
        return bodies
    }

    /** open 括号配对深度扫描；返回闭合括号索引，失衡（字符串内裸括号）返回 null */
    private fun matchBracket(text: String, openIdx: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (i in openIdx until text.length) {
            when (text[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** 文本中是否存在「finally 块内含 cancel 调用」的收口形态 */
    private fun hasFinallyCancel(text: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf("finally", from)
            if (at < 0) return false
            from = at + "finally".length
            var i = from
            while (i < text.length && text[i] != '{' && text[i] != '}') i++
            if (i >= text.length || text[i] != '{') continue
            val end = matchBracket(text, i, '{', '}') ?: continue
            if (text.substring(i + 1, end).contains("cancel(")) return true
            from = end
        }
    }

    private companion object {
        /** 直构判定须带标识符边界：DeviceMonitorPollingTest( 类长名不得误命中 */
        val MONITOR_CTOR = Regex("""(?<![A-Za-z0-9_])DeviceMonitor\s*\(""")
        val SAMPLER_CTOR = Regex("""(?<![A-Za-z0-9_])PerfSampler\s*\(""")
        val START_SAMPLING = Regex("""(?<![A-Za-z0-9_])startSampling\s*\(""")

        /** 宿主侧全部 jvmTest 模块（lib 域依赖方向上够不到 :core 设备域类，不在扫描面） */
        val HOST_MODULES = listOf(
            "composeApp", "core", "ui", "feature-timestamp", "feature-logkit",
            "feature-shelltools", "feature-logcat", "feature-screencast", "feature-larkbridge",
            "feature-ai", "feature-archive", "feature-quest", "feature-logworkbench",
            "feature-mcp", "feature-voice",
        )

        /** 纯 JVM lib 模块（测试源集在 src/test/kotlin，轮询纪律同样适用） */
        val LIB_MODULES = listOf("device-core", "adb-core")

        /** 扫描 .kt 数下限（留余量防扫描面静默收窄） */
        const val MIN_KT_FILES = 300

        const val POLLING_TEST_PATH =
            "device-core/src/test/kotlin/com/jiaoay/toolbelt/device/DeviceMonitorPollingTest.kt"
        const val DETECTING_TEST_PATH =
            "device-core/src/test/kotlin/com/jiaoay/toolbelt/device/DeviceMonitorDetectingTest.kt"
        const val PERF_SAMPLER_TEST_PATH =
            "device-core/src/test/kotlin/com/jiaoay/toolbelt/device/PerfSamplerTest.kt"
    }
}
