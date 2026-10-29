// S0 · 扫描面自检守护：守护测试的「扫描面」本身没有守护——本测试补上这道元防线。
// 🔴 缺口实测（2026-09-15 诊断）：
//   1. LarkSdkIsolationTest.LIB_MODULES（:621）是静态枚举，D/E/F 组在模块目录/源集缺失时
//      `println + return@forEach` 静默空过（:282/:334/:374）——模块名写错或目录重构后，
//      三个组会**全量空转**而不红（M4-T5 时代的容忍逻辑，今天三个 lib 全部存在，该后门必须焊死）；
//   2. 全部扫盘守护（UiArchitectureGuardTest/BridgeAssemblerParityTest/LarkSdkIsolationTest B-1）
//      已各自带「扫描面规模下限」自检，但下限数字彼此独立、无一处统一登记——本测试把
//      「扫盘守护必须自带规模自检」钉成一条可被 grep 到的纪律锚。
// 🔴 本测试的三个组与被守护对象**平行**（不修改被守护文件一行）：A 组焊死 LIB_MODULES 的静默
//    空过后门；B 组钉死「settings.gradle.kts 的 lib 模块清单必须与 LIB_MODULES 一致」——
//    新增 lib 模块（如未来 :xxx-ws）忘了登记时，这里先红，而不是 D/E/F 静默漏扫；
//    C 组锚定「扫盘守护必须自带规模自检」的纪律文字存在于各守护文件头。
// 🔴 验收纪律：一律 --rerun-tasks --no-build-cache（AGENTS.md「测试」节）。
package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanSurfaceSelfCheckTest {

    /**
     * A：LarkSdkIsolationTest.LIB_MODULES 必须与 settings.gradle.kts 的 lib(jvmTarget 11) 模块清单
     * 精确一致（双向集合相等）。
     *
     * - settings 侧缺登记 ⇒ D/E/F 组漏扫该模块（静默空过后门）；
     * - LIB_MODULES 多出不存在/已删的模块 ⇒ 同样是扫描面漂移。
     * lib 模块的判定口径与既有约定一致：纯 JVM lib（`src/main/kotlin` 源集）= jvmTarget 11 库，
     * 即 settings.gradle.kts 中除 :composeApp/:core/:ui/:log-tools 外的 feature 页面模块外，
     * 以「有 src/main/kotlin 源集」识别的模块。
     */
    @Test
    fun `A LarkSdkIsolationTest LIB_MODULES matches settings gradle lib module inventory`() {
        val root = locateProjectRoot()
        val settings = File(root, "settings.gradle.kts")
        assertTrue(settings.isFile, "settings.gradle.kts 缺失——项目根定位异常")

        // settings 里的全部模块
        val allModules = settings.readLines()
            .mapNotNull { line -> includeRegex.find(line)?.groupValues?.get(1) }
            .toSet()
        assertTrue(allModules.size >= MIN_SETTINGS_MODULES, "settings 模块数异常（${allModules.size}）")

        // lib 模块 = 有 src/main/kotlin 生产源集的纯 JVM 模块（与 LarkSdkIsolationTest D/E/F 的扫描口径一致）。
        // :log-tools 刻意排除：它虽是纯 JVM lib，但不属「飞书桥接链」（SDK 隔离/R19 方向/静态可变态
        // 三组守护的对象是桥接链 lib），从未入 LarkSdkIsolationTest 的面——把它拉进 D/E/F 属于
        // 扩别人的守护面，不是本测试的职责。桥接链 lib 的真源 = KNOWN_LIB_MODULES。
        // 其余纯 JVM lib（:adb-core 等）不设排除项：它们在 LIB_MODULES 里显式登记，
        // 漏登记时本测试按「缺登记」红出来。
        val actualLibModules = allModules
            .filter { File(root, "$it/src/main/kotlin").isDirectory }
            .filter { it != "log-tools" }
            .toSet()
        // 自检：已知三件套必须在（否则「识别口径」本身漂了，本测试失效）
        assertTrue(
            KNOWN_LIB_MODULES.all { it in actualLibModules },
            "lib 模块识别口径异常：$KNOWN_LIB_MODULES 中 ${KNOWN_LIB_MODULES.filter { it !in actualLibModules }} 未被识别",
        )

        // 从 LarkSdkIsolationTest 源文本提取 LIB_MODULES 实际内容（不改被守护文件）
        val isolationTest = File(root, ISOLATION_TEST_PATH)
        assertTrue(isolationTest.isFile, "$ISOLATION_TEST_PATH 缺失——守护对象被移动，须同步更新本测试")
        val declared = libModulesRegex.find(isolationTest.readText())?.groupValues?.get(1)
            ?.let { text ->
                libEntryRegex.findAll(text).map { it.groupValues[1] }.toSet()
            }
            ?: error("无法从 LarkSdkIsolationTest 提取 LIB_MODULES——声明形态可能已改，须同步更新本测试")

        assertEquals(
            actualLibModules,
            declared,
            buildString {
                appendLine("LarkSdkIsolationTest.LIB_MODULES 与 settings.gradle.kts 的 lib 模块清单不一致：")
                appendLine("  缺登记（D/E/F 组会静默漏扫）：${actualLibModules - declared}")
                appendLine("  已失效（模块已删/改名，须清理）：${declared - actualLibModules}")
                appendLine("修复：更新 LarkSdkIsolationTest.LIB_MODULES，并同步其 D/E/F 组的模块缺失 println 容忍逻辑。")
            },
        )
    }

    /**
     * B：LarkSdkIsolationTest 的 D/E/F 组不得再保留「模块缺失 ⇒ println 空过」路径——
     * 三个 lib 模块已全部存在（M4-T5 已远去），该容忍逻辑只剩后门价值。
     *
     * 匹配「`println` 行内同时含 `空过`」的字面组合——这是三处空过打印的公共签名
     * （实测 :285/:337/:377：「`...本轮空过`」）。D/E/F 全部空过路径被移除后本组自然绿；
     * 若守护重引入「缺模块打印跳过」类容忍，这里先红。
     */
    @Test
    fun `B LarkSdkIsolationTest has no silent pass-through for missing lib modules`() {
        val root = locateProjectRoot()
        val isolationTest = File(root, ISOLATION_TEST_PATH)
        val offenders = isolationTest.readLines().mapIndexedNotNull { idx, line ->
            val trimmed = line.trimStart()
            // 🔴 排除注释行：焊死说明的注释文字会提及「println 空过」历史，那是文档不是代码。
            if (trimmed.startsWith("//")) return@mapIndexedNotNull null
            if (line.contains("println") && line.contains("空过")) idx + 1 else null
        }
        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("LarkSdkIsolationTest 仍存在「模块缺失 ⇒ println 空过」的静默路径（行 $offenders）：")
                appendLine("三个 lib 模块均已存在，缺失即异常——请把 `println + return@forEach` 改为 assertTrue 失败，")
                appendLine("或在模块真的允许暂缺时把该模块从 LIB_MODULES 移到显式「在建白名单」并附理由。")
            },
        )
    }

    /**
     * C：扫盘型守护必须自带「扫描面规模自检」（防目录定位异常导致永绿空转）。
     * 既有三处先例各自有；本组把它们钉成可 grep 的纪律锚——新扫盘守护落地时必须照抄。
     * 匹配各守护文件中「规模下限断言」的公共签名（`扫描` + 下限类字样同现于一行，或
     * `MIN_KT_FILES`/`sources.size` 形态的检查）。
     */
    @Test
    fun `C every scan-based guard carries a scan-surface size self-check`() {
        val root = locateProjectRoot()
        val guards = listOf(
            ISOLATION_TEST_PATH,
            UI_GUARD_TEST_PATH,
            REPLY_SINK_GUARD_PATH,
            MODULE_GRAPH_GUARD_PATH,
        )
        val missing = guards.filter { path ->
            val file = File(root, path)
            if (!file.isFile) return@filter true // 文件缺失也算未达标
            val text = file.readText()
            // 签名：check(... >= N)/check(... > N)/assertTrue(...size... > / >= ...) 的规模下限自检
            !sizeCheckRegex.containsMatchIn(text)
        }
        assertTrue(
            missing.isEmpty(),
            "以下扫盘守护缺少「扫描面规模自检」（防扫描根丢失导致的永绿后门）：$missing。" +
                "照抄先例：LarkSdkIsolationTest B-1 的 `check(allKt.size >= MIN_KT_FILES)`。",
        )
    }


    private companion object {
        const val ISOLATION_TEST_PATH =
            "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/LarkSdkIsolationTest.kt"
        const val UI_GUARD_TEST_PATH =
            "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/UiArchitectureGuardTest.kt"
        const val REPLY_SINK_GUARD_PATH =
            "lark-bridge/src/test/kotlin/com/jiaoay/lark/bridge/pipeline/ReplySinkUniquenessTest.kt"
        const val MODULE_GRAPH_GUARD_PATH =
            "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/arch/ModuleGraphGuardTest.kt"

        /** 已知 lib 三件套（识别口径自检锚；:log-tools 有 src/main/kotlin 但属工具 lib，不入 D/E/F 面） */
        val KNOWN_LIB_MODULES = setOf("lark-gateway", "lark-bridge", "lark-ws")

        /** settings.gradle.kts 的 include(:x) 行（当前 13 个模块，留余量） */
        const val MIN_SETTINGS_MODULES = 10

        /** `include(":xxx")` */
        val includeRegex = Regex("""include\(":([A-Za-z0-9\-]+)"\)""")

        /** `val LIB_MODULES = listOf("a", "b", "c")` 的右侧（单行 or 折行内联均可） */
        val libModulesRegex = Regex("""LIB_MODULES\s*=\s*listOf\(([^)]*)\)""")

        /** listOf(...) 内的字符串项 */
        val libEntryRegex = Regex(""""([A-Za-z0-9\-]+)"""")

        /** 规模下限自检签名（🔴 多行兼容：`check(x.size >= N)`、`check(x.size >= CONST)`、
         *  `assertTrue(... size > N ...)`、`check(N <= x.size)`；常量名可跨行出现在断言内）。
         *  Kotlin 无跨行表达式拆分断言的惯例（既有三处先例都是单行），单行匹配已足够。 */
        val sizeCheckRegex = Regex("""(?:check|assertTrue)\(\s*[^()]*\.size\s*[><]=?\s*(?:\d|[A-Z][A-Z_]*\d*)""")
    }
}
