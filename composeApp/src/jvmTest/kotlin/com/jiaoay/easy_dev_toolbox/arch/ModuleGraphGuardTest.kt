// S0 · 构建图守护：把「依赖声明必须对应真实源码消费」从人工纪律升级为红构建门禁。
// 🔴 首个用例即真实违例（2026-09-15 已随本守护落地时删除）：feature-larkbridge 曾声明
//    implementation(project(":feature-ai")) 而源码零 page.ai import——它消费的 LlmConfigStore /
//    LlmClientFactory 等在批 6-R 已下沉 :core/llm，:feature-ai 对 koog 件是 implementation
//    不传递任何东西，故该依赖是纯死链（见 feature-larkbridge/build.gradle.kts 的修正注释）。
// 🔴 feature→feature 依赖的正当形态 = 宿主外唯一跨页引用渠道；声明了就必须有源码证据
//    （page.<x> import），否则删除或进白名单并附理由。白名单条目必须仍有真实命中（防腐化，
//    手法对齐 UiArchitectureGuardTest 的「whitelist entries still match real code」）。
// 🔴 扫描面自检（先例 LarkSdkIsolationTest B-1 的 MIN_KT_FILES）：目录定位异常必须显式失败，
//    不允许静默空转成永绿后门。
// 🔴 验收纪律：扫盘型守护会被 build cache 假绿（AGENTS.md「测试」节），
//    一律 --rerun-tasks --no-build-cache 验收。
package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class ModuleGraphGuardTest {

    /**
     * A：任何模块声明 `project(":feature-<x>")` 依赖，其源码（main + test 全源集）必须
     * 真实 import `com.jiaoay.easy_dev_toolbox.page.<x>.` 下的类型；否则该依赖为死链。
     *
     * - 豁免：`:composeApp`（宿主聚合点，合法引用全部 feature 页面，依赖即消费）；
     * - 白名单 [DEP_CONSUME_ALLOWLIST]：声明正当但消费形态特殊（非 page import）的条目，
     *   🔴 每条必须带理由且仍能匹配到真实依赖声明（防腐化：依赖被删 ⇒ 白名单条目同步删）。
     */
    @Test
    fun `A feature project dependencies must be backed by real page imports in the declaring module`() {
        val root = locateProjectRoot()
        val scripts = root.walkBuildScripts()
        check(scripts.size >= MIN_BUILD_SCRIPTS) {
            "build.gradle.kts 扫描数异常（${scripts.size} < $MIN_BUILD_SCRIPTS）：目录定位可能失效，守护会静默失效"
        }

        val violations = mutableListOf<String>()
        val declaredFeatureDeps = mutableSetOf<String>() // "声明模块->被依赖模块" 对，供白名单防腐化匹配
        for (script in scripts) {
            val declaringModule = script.parentFile.name
            // 剥 // 行注释再匹配：注释里提及依赖字面量（如「删除原 implementation(project(":feature-ai"))」
            // 的修史注释）不是声明；build 脚本无块注释用法，剥行注释已足够。
            val text = script.readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            val featureDeps = featureDepRegex.findAll(text)
                .map { it.groupValues[1] } // 如 "feature-ai"
                .toSet()
            if (featureDeps.isEmpty()) continue
            if (declaringModule == HOST_MODULE) continue // 宿主聚合点豁免
            val moduleSources = listOf(
                File(root, "$declaringModule/src/jvmMain/kotlin"),
                File(root, "$declaringModule/src/commonMain/kotlin"),
                File(root, "$declaringModule/src/main/kotlin"),
                File(root, "$declaringModule/src/jvmTest/kotlin"),
            ).filter { it.isDirectory }
            val sourceText = moduleSources
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
                .joinToString("\n") { it.readText() }
            for (dep in featureDeps) {
                declaredFeatureDeps += "$declaringModule->$dep"
                val pagePackage = "page.${dep.removePrefix("feature-")}."
                if (!sourceText.contains(pagePackage)) {
                    val exempt = DEP_CONSUME_ALLOWLIST.any { it.first == "$declaringModule->$dep" }
                    if (!exempt) {
                        violations += "$declaringModule 声明 project(\":$dep\") 但源码零 import $pagePackage —— 死依赖"
                    }
                }
            }
        }

        // 防腐化：白名单条目必须仍对应真实存在的依赖声明（依赖被删 ⇒ 条目必须同步删除）
        val stale = DEP_CONSUME_ALLOWLIST.filter { it.first !in declaredFeatureDeps }
        if (stale.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("DEP_CONSUME_ALLOWLIST 存在已无对应依赖声明的条目（防腐化断言）：")
                    stale.forEach { (key, reason) -> appendLine("  - $key（理由：$reason）") }
                    appendLine("对应依赖已被删除，请同步移除该白名单条目。")
                },
            )
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("构建图死依赖违例 ${violations.size} 条：")
                    violations.forEach { appendLine("  - $it") }
                    appendLine("feature→feature 依赖必须有源码证据（import com.jiaoay.easy_dev_toolbox.page.<x>.*）；")
                    appendLine("需要的类型若已下沉 :core/:ui，请删除依赖声明；消费形态特殊请进 DEP_CONSUME_ALLOWLIST 并附理由。")
                },
            )
        }
    }

    /**
     * B：`:lark-bridge`（jvmTarget 11 纯 JVM lib）禁止依赖任何 jvm21 宿主模块（:core/:ui/feature-*）。
     *
     * 这是 LarkSdkIsolationTest D 组「宿主 jvm21 模块」断言的构建图侧镜像：D 组守源码引用，
     * 本组守依赖声明——两侧合围，新增模块时无论从哪个方向漏都会红。
     * （:composeApp/:feature-* 不在此扫描面：宿主依赖 feature 是正向方向。）
     */
    @Test
    fun `B lark lib modules never depend on host jvm21 modules`() {
        val root = locateProjectRoot()
        val libScripts = LIB_MODULES.map { File(root, "$it/build.gradle.kts") }
        assertTrue(
            libScripts.all { it.isFile },
            "lib 模块 build 脚本缺失：${LIB_MODULES.filter { !File(root, "$it/build.gradle.kts").isFile }}",
        )
        val violations = libScripts.flatMap { script ->
            val text = script.readText()
            HOST_MODULE_TOKENS.filter { token -> text.contains(token) }
                .map { token -> "${script.parentFile.name}: 声明 $token（jvm11 lib 禁依赖宿主 jvm21 模块）" }
        }
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("lib→宿主 依赖方向违例 ${violations.size} 条（R19）：")
                    violations.forEach { appendLine("  - $it") }
                    appendLine("数据根路径/宿主类型一律由宿主注入（先例：BridgeConfig.dataRoot 由 App 侧写入）。")
                },
            )
        }
    }


    /** 遍历全部 build.gradle.kts（剪枝构建产物/IDE 目录），含各 feature 模块 */
    private fun File.walkBuildScripts(): List<File> =
        walkTopDown()
            .onEnter { it.name !in EXCLUDED_DIR_NAMES }
            .filter { it.isFile && it.name == "build.gradle.kts" }
            .sortedBy { it.path }
            .toList()

    private companion object {
        const val HOST_MODULE = "composeApp"

        /** lib(jvm11) 模块（与 LarkSdkIsolationTest.LIB_MODULES 同口径） */
        val LIB_MODULES = listOf("lark-gateway", "lark-bridge", "lark-ws", "log-tools")

        /** 全仓 build.gradle.kts 下限（当前 13 个模块 + build-logic，留余量） */
        const val MIN_BUILD_SCRIPTS = 10

        /** `project(":feature-xxx")` 声明（含 implementation/api 任意配置） */
        val featureDepRegex = Regex("""project\(":((?:feature-)[a-z]+)"\)""")

        /** lib 禁依赖的宿主 jvm21 模块声明 token（对齐 LarkSdkIsolationTest.forbiddenHostProjectDeps + :ui） */
        val HOST_MODULE_TOKENS = listOf(
            "project(\":core\")",
            "project(\":ui\")",
            "project(\":composeApp\")",
            "project(\":feature-larkbridge\")",
        )

        val EXCLUDED_DIR_NAMES = setOf(
            "build", ".gradle", ".kotlin", ".clikt_tmp", ".review_tmp", ".idea", "node_modules", ".git",
        )

        /**
         * 声明正当但消费形态特殊的 feature 依赖（"声明模块->被依赖模块" to 理由）。
         * 当前为空：feature 间需要的公共类型一律下沉 :core/:ui（先例：批 6-R 的 core.llm 下沉、
         * 批 2026-09-14 的 ToolExecutionContext 下沉），不靠 feature→feature 直连。
         */
        val DEP_CONSUME_ALLOWLIST: List<Pair<String, String>> = emptyList()
    }
}
