// 重设计批 1 复核修复守护（2026-09-30）：knowledge:// 前缀单源化——AG-02/AG-04 接缝。
//
// 🔴 根因（复核实锤）：写侧 DistillViewModel（AG-04 蒸馏归属）曾自拼
//    "knowledge://$repo" 字面量、显示侧 SpaceBar（AG-02 成员面板）曾自持
//    internal const val KNOWLEDGE_URI_PREFIX = "knowledge://"——两处独立无机制绑定，
//    一侧改格式另一侧静默失效（knowledge 成员掉进「文件」节裸串显示/面板认不出
//    写侧产物）；两测试各钉自己的字面量，链路无人断言。
//
// 修复后单源形态：常量唯一定义点 = :core SpaceStore.kt 的
// `KNOWLEDGE_URI_PREFIX`（top-level public const——成员值形态语义归 SpaceStore；
// feature-ai 写侧与 composeApp 显示侧均消费），写侧拼接 / 显示侧剥前缀全部 import。
//
// 本守护钉死三件事（防回潮）：
// 1. 全仓生产源码（jvmMain/main，🔴 不含 test 源集——测试断言字面量豁免）中
//    `"knowledge://` 字面量出现次数 == 1（:core 常量定义处唯一）——再冒出的
//    第二处字面量即双口径回潮；
// 2. 写侧消费点（DistillViewModel.kt）必须引用 KNOWLEDGE_URI_PREFIX
//    拼接归属 URI（改回裸串先红）；
// 3. 显示侧消费点（SpaceBar.kt）必须引用 KNOWLEDGE_URI_PREFIX
//    剥前缀分组显示，且不得再自持本地 `const val KNOWLEDGE_URI_PREFIX` 声明。
//
// 🔴 扫描面自检（先例 LarkSdkIsolationTest B-1 / UiArchitectureGuardTest）：
//    文件数下限防目录定位异常静默缩水成永绿后门。
// 🔴 验收纪律：扫盘型守护会被 build cache 假绿（AGENTS.md「测试」节），
//    一律 --rerun-tasks --no-build-cache 验收。
package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KnowledgeUriPrefixSingleSourceGuardTest {

    /**
     * A：全仓生产源码（jvmMain/main，排除 test 源集）里 `"knowledge://` 字面量
     * 只允许出现 1 次——:core SpaceStore.kt 常量定义处。任何第二处字面量
     * （写侧裸拼/显示侧自持常量回潮）即红。
     *
     * 字面量口径：双引号开头（`"knowledge://`）——只匹配字符串字面量形态，
     * 不误伤注释里的 knowledge:// 叙述文字（剥注释后扫描）与测试断言
     * （jvmTest 不在扫描面）。
     */
    @Test
    fun `A knowledge uri literal appears exactly once in production sources`() {
        val sources = productionSources()
        val hits = sources.flatMap { (file, code) ->
            Regex(""""knowledge://""").findAll(code)
                .map { "${file.relativeTo(locateProjectRoot()).path}（剥注释后文本命中）" }
        }
        assertEquals(
            1,
            hits.size,
            buildString {
                appendLine("生产源码 \"knowledge:// 字面量必须恰好出现 1 次（:core SpaceStore.kt 常量定义处），实际 ${hits.size} 次：")
                hits.forEach { appendLine("  - $it") }
                appendLine("双口径回潮：写侧/显示侧必须 import com.jiaoay.easy_dev_toolbox.core.data.db.KNOWLEDGE_URI_PREFIX，不得自持字面量。")
            },
        )
    }

    /**
     * B：写侧消费点（feature-ai DistillViewModel——AG-04 蒸馏归属唯一拼前缀处）
     * 必须引用 KNOWLEDGE_URI_PREFIX 拼接归属 URI。
     */
    @Test
    fun `B writer side distill view model composes uri via shared constant`() {
        val vm = anchorFile(WRITER_PATH)
        val code = stripKotlinComments(vm.readText())
        assertTrue(
            "KNOWLEDGE_URI_PREFIX" in code,
            "AG-04 写侧（DistillViewModel）必须引用 KNOWLEDGE_URI_PREFIX 拼接归属 URI" +
                "（文件已改路径？同步更新本守护的 WRITER_PATH）",
        )
        assertTrue(
            "KNOWLEDGE_URI_PREFIX + repo" in code,
            "AG-04 写侧归属拼接形态 = KNOWLEDGE_URI_PREFIX + repo（改回裸串/改形态 = 绕过单源，须同步守护）",
        )
    }

    /**
     * C：显示侧消费点（composeApp SpaceBar——AG-02 成员面板剥前缀处）必须引用
     * KNOWLEDGE_URI_PREFIX，且不得再自持本地 `const val KNOWLEDGE_URI_PREFIX`
     * 声明（本地声明会遮蔽 :core 单源——改一处另一处失效的原始病灶）。
     */
    @Test
    fun `C display side space bar consumes shared constant without local declaration`() {
        val bar = anchorFile(DISPLAY_PATH)
        val code = stripKotlinComments(bar.readText())
        assertTrue(
            "KNOWLEDGE_URI_PREFIX" in code,
            "AG-02 显示侧（SpaceBar）必须引用 KNOWLEDGE_URI_PREFIX 剥前缀分组" +
                "（文件已改路径？同步更新本守护的 DISPLAY_PATH）",
        )
        if (Regex("""const\s+val\s+KNOWLEDGE_URI_PREFIX""").containsMatchIn(code)) {
            fail(
                "SpaceBar 不得再自持 local const val KNOWLEDGE_URI_PREFIX——本地声明遮蔽 :core 单源" +
                    "（复核修复的原始病灶回潮），删除本地声明、改 import :core 常量。",
            )
        }
    }

    // ── 扫描基建 ──

    /** 守护锚文件（缺失即红——重命名/移动后须同步本守护路径） */
    private fun anchorFile(relativePath: String): File {
        val file = File(locateProjectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后请同步本守护）")
        return file
    }

    /**
     * 全仓生产源码（源文件 → 剥注释后代码文本）：每模块 src/jvmMain/kotlin 与
     * src/main/kotlin（纯 JVM lib 模块），🔴 排除 test 源集——测试断言里的
     * "knowledge://settings" 字面量是钉行为的合法形态，豁免；build/.git 等目录剪枝。
     */
    private fun productionSources(): List<Pair<File, String>> {
        val root = locateProjectRoot()
        val modules = root.listFiles { f -> f.isDirectory }
            .orEmpty()
            .filter { it.name !in EXCLUDED_DIR_NAMES }
        val sources = mutableListOf<Pair<File, String>>()
        for (module in modules) {
            for (srcRoot in PRODUCTION_SOURCE_ROOTS) {
                val dir = File(module, srcRoot)
                if (!dir.isDirectory) continue
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .sortedBy { it.path }
                    .forEach { file -> sources += file to stripKotlinComments(file.readText()) }
            }
        }
        // 🔴 扫描面自检：空扫描/规模缩水 = 永绿后门（先例 UiArchitectureGuardTest 的文件数下限）
        check(sources.isNotEmpty()) { "生产源码扫描结果为空：目录定位异常（root=${root.path}）" }
        check(sources.size >= MIN_PRODUCTION_KT_FILES) {
            "生产源码扫描文件数异常（${sources.size} < $MIN_PRODUCTION_KT_FILES 下限）：模块目录可能缺失，守护会静默失效"
        }
        return sources
    }

    /**
     * 剥除行注释（//）与块注释（/** */、/* */），保留代码与换行——换行保留使剥除后
     * 文本行号与原文件对齐。字符串感知：普通 `"..."`（`\` 转义生效）与 `"""..."""`
     * 原始字符串各自成态，字符串态内不识别注释起始——字符串里的 `//`（如 URI）不会
     * 误剥后续代码；字符串内容本身保留供扫描（本守护锚的就是字符串字面量）。
     * 配方照抄 UiArchitectureGuardTest.stripKotlinComments（同款语义）。
     */
    private fun stripKotlinComments(source: String): String {
        val sb = StringBuilder(source.length)
        var inString = false
        var inRawString = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                inRawString -> when {
                    c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        sb.append("\"\"\"")
                        i += 3
                        inRawString = false
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }

                inString -> when {
                    c == '\\' && i + 1 < source.length -> {
                        sb.append(c).append(source[i + 1])
                        i += 2
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = false
                        i++
                    }

                    c == '\n' -> {
                        sb.append(c)
                        inString = false
                        i++
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }

                else -> when {
                    c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                        sb.append("\"\"\"")
                        i += 3
                        inRawString = true
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = true
                        i++
                    }

                    c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                        while (i < source.length && source[i] != '\n') i++
                    }

                    c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                        i += 2
                        while (i < source.length) {
                            if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') {
                                i += 2
                                break
                            }
                            if (source[i] == '\n') sb.append('\n')
                            i++
                        }
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
        return sb.toString()
    }

    private companion object {

        /** AG-04 写侧锚（feature-ai 蒸馏归属唯一拼前缀处） */
        const val WRITER_PATH =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillViewModel.kt"

        /** AG-02 显示侧锚（composeApp 成员面板剥前缀处） */
        const val DISPLAY_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/SpaceBar.kt"

        /** 生产源码根（KMP 宿主域 jvmMain + 纯 JVM lib main） */
        val PRODUCTION_SOURCE_ROOTS = listOf("src/jvmMain/kotlin", "src/main/kotlin")

        /**
         * 生产源码文件数下限（自检防永绿后门）：当前 jvmMain/main 全仓约 900+ kt
         * （core 164 + lark-bridge 195 + lark-gateway 45 + ui 45 + log-tools 43 +
         * feature 域 ~170 + composeApp 67 —— 含 test 已剔除前的粗量级，留足余量）。
         */
        const val MIN_PRODUCTION_KT_FILES = 300

        /** 剪枝目录（构建产物/IDE/非模块目录；test-fixtures 是纯测试基建无生产面，扫描不入面但保留在目录树中无害） */
        val EXCLUDED_DIR_NAMES = setOf(
            "build", ".gradle", ".kotlin", ".idea", ".git", "node_modules", ".clikt_tmp", ".review_tmp",
        )
    }
}
