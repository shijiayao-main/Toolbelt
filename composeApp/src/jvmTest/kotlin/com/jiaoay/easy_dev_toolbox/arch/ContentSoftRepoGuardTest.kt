package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * contentSoft 全仓扫描守护（per-module 先例推广：CockpitContentSoftGuardTest 同配方
 * 扩全仓生产代码——SH-02「文字 alpha 透明合成」禁令的仓库级收敛面）。
 *
 * 断言面 = 全部模块生产源码（jvmMain/main 双源集，剥行注释后扫描）：
 * `AppAlpha.contentSoft` 消费只允许出现在下方豁免登记表内的文件——
 * - **语义 container 对直出**（PillLabel/StatusBanner 现成件）与**全色字**是正解，
 *   新消费点必须走这两路，不得新增 text alpha 合成；
 * - 豁免仅收「背景/图形洗底」与「浅底/反转底合法弱化」两类既有存量，逐文件登记理由；
 *   豁免文件改名/移动即红（存在性校验）——清退一条就删一条登记（棘轮语义）。
 *
 * 🔴 扫盘型守护测试会被 build cache 假绿：验收一律
 * `./gradlew :composeApp:jvmTest --rerun-tasks --no-build-cache`。
 */
class ContentSoftRepoGuardTest {

    @Test
    fun `contentSoft stays confined to registered exemptions repo wide`() {
        val offenders = productionSources()
            .filter { (_, text) -> text.contains("contentSoft") }
            .map { it.first }
            .filter { it !in EXEMPTIONS.keys }
        assertTrue(
            offenders.isEmpty(),
            "contentSoft 消费越出豁免登记表（SH-02 文字透明合成禁令——语义 container 对/全色字为正解，" +
                "确属背景洗底或浅底合法弱化须先在本测试 EXEMPTIONS 登记理由）：$offenders",
        )
    }

    @Test
    fun `exemption registry paths all exist`() {
        val missing = EXEMPTIONS.keys.filter { !File(locateProjectRoot(), it).isFile }
        assertTrue(
            missing.isEmpty(),
            "豁免登记表含失效路径（文件已删/改名——登记须随消费点清退同步删除，棘轮只许降）：$missing",
        )
    }

    /** 全部生产源文件（剥行注释——注释文字提及不算消费；顶层模块目录扫描，build 产物不入面） */
    private fun productionSources(): List<Pair<String, String>> {
        val root = locateProjectRoot()
        val excluded = setOf("build", ".git", ".idea", "gradle")
        val sources = mutableListOf<Pair<String, String>>()
        root.listFiles { f -> f.isDirectory }
            .orEmpty()
            .filter { it.name !in excluded }
            .forEach { module ->
                for (srcSet in listOf("src/jvmMain/kotlin", "src/main/kotlin")) {
                    val dir = File(module, srcSet)
                    if (!dir.isDirectory) continue
                    dir.walkTopDown()
                        .filter { it.isFile && it.extension in setOf("kt", "kts") }
                        .forEach { f ->
                            val stripped = f.readText().lineSequence()
                                .filterNot { it.trimStart().startsWith("//") }
                                .joinToString("\n")
                            sources.add(f.relativeTo(root).invariantSeparatorsPath to stripped)
                        }
                }
            }
        assertTrue(sources.size > 300, "扫描面异常：仅 ${sources.size} 个生产源文件——模块根定位或源集枚举失效")
        return sources
    }

    /**
     * 豁免登记表（相对路径 → 理由）。新增豁免须在消费点写明同款理由并过评审——
     * 「背景/图形洗底」与「浅底/反转底合法弱化」之外不收。
     */
    private companion object {
        val EXEMPTIONS: Map<String, String> = mapOf(
            // token 定义本体（常量声明非消费）
            "core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/theme/Alpha.kt" to
                "contentSoft 档位常量声明处",
            // 以下三处 = 背景/图形洗底（非文字，透明合成不产生文字对比度问题）
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/AppTitleBar.kt" to
                "窗控圆点 hover 洗底（图形非文字）",
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/StatusHost.kt" to
                "撤销倒计时进度轨洗底（图形非文字）",
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/output/OutputHeightDivider.kt" to
                "分隔条握把 hover 洗底（图形非文字）",
            // 浅底/反转底上的合法弱化（SH-02 禁令语义 = 深底文字不可控合成）
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/SectionCard.kt" to
                "SectionCard description 弱化——浅底卡面（本文件 :136 先例槽位）",
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/FieldRow.kt" to
                "contextLabel 分区名前缀——tonal SectionCard 浅底（XA-15 原豁免锚）",
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/SpaceBar.kt" to
                "调查空间 tooltip 次行弱化——inverse 反转槽对语境（非深底正文）",
        )
    }
}
