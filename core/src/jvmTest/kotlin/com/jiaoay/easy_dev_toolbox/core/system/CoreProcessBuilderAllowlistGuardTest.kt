package com.jiaoay.easy_dev_toolbox.core.system

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * core 侧 ProcessBuilder 合法点集守护（扫盘型——防腐化）。
 *
 * 守护目标：`:core` 生产源集里 [ProcessBuilder] 的出现点必须精确收口在合法点集
 * （[ALLOWED_PROCESS_BUILDER_SITES]）。core 的进程调用纪律分两种语义
 * （docs/core/shell.md「两种进程调用语义的分工」节）：
 * - shell 命令行语义 → `core/extensions/` 三件封装 + ShellCommandBuilder（AGENTS.md 约定 1 的统一收口件）；
 * - argv 程序调用语义 → `core/media/AudioTranscoder.kt`（ffmpeg 直调先例）与
 *   `core/system/ArgvProcessRunner.kt`（只读诊断域执行原语）。
 * 语义之外另立 ProcessBuilder 点（绕过 shell 封装直调、或给诊断域另造执行件）是纪律破口：
 * argv 语义件承载 LLM 驱动的命令面，出现点失控 = 白名单只读域的执行面失控，故钉成红构建门禁。
 *
 * 收口/子集语义（含 ProcessBuilder 的文件必须精确等于合法点集）：
 * - 新增合法点必须先在本表登记理由再落生产代码（顺序颠倒即红构建）；
 * - 条目指向的文件被删/改名/迁出后必须同步删条目（防白名单静默漂移）；
 * - 诊断域执行原语文件缺失时必须红（锚点文件被误删时守护不得静默空转）。
 *
 * 另附一条结构断言：`core/llm/tools/`（agent 工具面收口目录）内零 ProcessBuilder——
 * agent 工具一律经 [ArgvProcessRunner] 执行原语，不得在工具类内直造进程。
 *
 * 验收纪律：扫盘型守护一律 `--rerun-tasks --no-build-cache`（build cache 会假绿）。
 */
class CoreProcessBuilderAllowlistGuardTest {

    private val projectRoot: File by lazy { locateProjectRoot() }

    private val coreMainSourceDir: File by lazy {
        File(projectRoot, "core/src/jvmMain/kotlin")
    }

    @Test
    fun `core production ProcessBuilder sites stay within the registered allowlist`() {
        val sources = coreMainSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        check(sources.size >= MIN_CORE_KT_FILES) {
            "core 生产源码扫描文件数异常（${sources.size} < $MIN_CORE_KT_FILES 下限）：目录定位失效，守护会静默失效"
        }
        val hitFiles = sources
            .filter { normalizeKotlinSource(it.readText()).contains("ProcessBuilder") }
            .map { it.relativeTo(projectRoot).invariantSeparatorsPath }
            .toSet()
        val offenders = hitFiles - ALLOWED_PROCESS_BUILDER_SITES.keys
        if (offenders.isNotEmpty()) {
            fail(
                "core 生产源集出现 ProcessBuilder 合法点集之外的文件：$offenders\n" +
                    "core 进程调用只有两种合法语义：shell 命令行语义走 core/extensions 封装、\n" +
                    "argv 程序调用语义走 media/AudioTranscoder.kt（ffmpeg 先例）与\n" +
                    "system/ArgvProcessRunner.kt（只读诊断域执行原语）。新增合法点必须先在\n" +
                    "ALLOWED_PROCESS_BUILDER_SITES 登记理由再落生产代码。",
            )
        }
        val stale = ALLOWED_PROCESS_BUILDER_SITES.keys - hitFiles
        if (stale.isNotEmpty()) {
            fail(
                "合法点集含不再出现 ProcessBuilder 的文件（文件被删/改写/迁出？同步删条目）：$stale",
            )
        }
    }

    @Test
    fun `argv runner anchor file exists and is the diagnostics domain sole implementer`() {
        val anchor = File(projectRoot, ARGV_RUNNER_RELATIVE_PATH)
        assertTrue(
            anchor.isFile,
            "只读诊断域执行原语缺失（$ARGV_RUNNER_RELATIVE_PATH）——锚点文件被误删时本守护必须红，不得静默空过",
        )
        if (anchor.isFile) {
            assertTrue(
                normalizeKotlinSource(anchor.readText()).contains("ProcessBuilder"),
                "$ARGV_RUNNER_RELATIVE_PATH 登记为 argv 执行原语但体内无 ProcessBuilder——锚点漂移",
            )
        }
    }

    @Test
    fun `agent tools directory contains no ProcessBuilder`() {
        val toolsDir = File(projectRoot, AGENT_TOOLS_DIR)
        assertTrue(
            toolsDir.isDirectory,
            "agent 工具目录缺失（$AGENT_TOOLS_DIR）——目录重构时本守护必须红出来而非静默漏扫",
        )
        val offenders = toolsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { normalizeKotlinSource(it.readText()).contains("ProcessBuilder") }
            .map { it.relativeTo(projectRoot).invariantSeparatorsPath }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "core/llm/tools 内不得出现 ProcessBuilder（agent 工具一律经 core/system/ArgvProcessRunner 执行）：" +
                "$offenders。工具内直造进程 = 三张守护网（围栏/结构化纪律/目录）外的执行面。",
        )
    }

    @Test
    fun `allowlist reasons are registered for every site`() {
        // 每个合法点必须带非空理由（理由缺失 = 未审视的豁免，守护退化成抄名单）
        val blank = ALLOWED_PROCESS_BUILDER_SITES.filterValues { it.isBlank() }.keys
        assertTrue(blank.isEmpty(), "合法点缺登记理由：$blank")
        // 计数自检：登记面与常量表条目数一致（mapOf 拼写漂移时红）
        assertEquals(6, ALLOWED_PROCESS_BUILDER_SITES.size, "合法点集条目数漂移：${ALLOWED_PROCESS_BUILDER_SITES.keys}")
    }

    // ── 扫描实现 ──

    /**
     * 归一化：剥行注释/块注释（含嵌套）/字符串字面量内容（普通 + raw），保留换行。
     * 守护的是「类型使用」而非「文字提及」——KDoc/注释里的 ProcessBuilder 说明文字
     * 不判为真实出现点（与 LarkSdkIsolationTest 同款取舍）。
     */
    private fun normalizeKotlinSource(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else ' '
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
                    i += 3
                    while (i < source.length) {
                        if (source[i] == '"' && i + 2 < source.length &&
                            source[i + 1] == '"' && source[i + 2] == '"'
                        ) {
                            i += 3
                            break
                        }
                        if (source[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                c == '"' -> {
                    i++
                    while (i < source.length) {
                        if (source[i] == '\\' && i + 1 < source.length) { i += 2; continue }
                        if (source[i] == '"') { i++; break }
                        if (source[i] == '\n') { sb.append('\n'); break }
                        i++
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** 自 user.dir 逐级向上探测项目根（settings.gradle.kts 所在层） */
    private fun locateProjectRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("项目根定位失败（settings.gradle.kts 未在 user.dir 向上 8 层内）")
    }

    private companion object {

        /** core 生产源码 .kt 扫描下限（现网 150+，留余量）：跌破说明扫描根丢失 */
        const val MIN_CORE_KT_FILES = 100

        /** agent 工具面收口目录（围栏/结构化纪律/目录三张守护网的扫描根） */
        const val AGENT_TOOLS_DIR = "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools"

        /** 只读诊断域执行原语锚点文件 */
        const val ARGV_RUNNER_RELATIVE_PATH =
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/system/ArgvProcessRunner.kt"

        /**
         * core 侧 ProcessBuilder 合法点集：相对项目根路径 → 登记理由。
         *
         * 🔴 扩展契约：新增出现点必须**先**在此登记理由再落生产代码（LLM 驱动的
         * 进程执行面失控即回火面），顺序颠倒即红构建；文件被删/改名/改写掉
         * ProcessBuilder 后必须同步删条目（第一个测试的 stale 断言强制）。
         */
        val ALLOWED_PROCESS_BUILDER_SITES: Map<String, String> = mapOf(
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/SimpleShell.kt" to
                "shell 命令行语义统一收口件（AGENTS.md 约定 1：用户手输 shell 命令的宿主侧封装本体）",
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/StreamingShell.kt" to
                "shell 命令行语义统一收口件（同 SimpleShell）",
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/InteractiveShell.kt" to
                "shell 命令行语义统一收口件（同 SimpleShell）",
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/extensions/ShellCommandBuilder.kt" to
                "shell 命令行语义 wrap/charset 冁口件（cmd.exe / zsh -l 包装本体）",
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/media/AudioTranscoder.kt" to
                "argv 程序调用语义先例：ffmpeg 确定性文件变换直调（不适用 shell 语义——Windows ANSI charset 会编坏 UTF-8 路径参数）",
            ARGV_RUNNER_RELATIVE_PATH to
                "argv 程序调用语义执行原语（只读诊断域 sys.* 唯一执行件：无 shell 解释器、argv 数组直传、" +
                    "命令黑名单硬拒 + systemctl/journalctl 恒 --user 硬校验、看门狗 + 进程树级联终止）",
        )
    }
}
