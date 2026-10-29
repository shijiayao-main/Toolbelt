package com.jiaoay.easy_dev_toolbox.page.ai.distill

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * AG-09 蒸馏计划作废显式出口单测（重设计批 4 · 2026-10-01）：
 * - **abandonPlan 阶段机语义**：PLAN_REVIEW → FORM，计划面（planItems/planRepo）清空，
 *   🔴 表单内容（featureDesc/entryHints）**保留**——与 restart 的分界就是这组断言
 *   （restart 全清是「重来」，作废是「这计划不要了、表单还在」，卡面 notes 明示
 *   不复用 restart 的原因）；
 * - **失实文案消失**：「关闭页面作废」从 DistillPage 源码消失（旧文案承诺 VM 窗口级
 *   驻留下不存在的「关闭页面作废」行为——切签/关 tab 计划还在，承诺失实）；
 *   「作废本次计划」显式钮与真实语义新文案在位。
 *
 * ## 测试缝
 * - VM 直构 + [DistillViewModel.setPlanReviewFixtureForTest]（本卡新增，set 仅测试语义）：
 *   生产唯一写点 startExplore watcher 需真实 LLM，单测不可驱动——同
 *   DistillAttributionTest 的直构配方（user.home 隔离 + Database.resetForTest——
 *   VM 装配触达 Database.database() 真单例，ledger 落隔离库）；
 * - 文案面走源码扫描锚（CockpitRemainingPillGuardTest 同款 locateProjectRoot 配方）——
 *   Compose UI 无桌面测试基建可渲染 PLAN_REVIEW 分支，源码锚钉行为面。
 *
 * 🔴 验收纪律（扫盘型）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DistillAbandonTest {

    private val dispatcher = StandardTestDispatcher()
    private val home = com.jiaoay.testfixtures.UserHomeIsolation("edt-distill-abandon-test")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect() // Database.database() 真单例落临时 home（隔离真库）
        // 🔴 Database.instance JVM 级缓存跨用例串库——重定向后 resetForTest 清缓存（同款纪律）
        Database.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        home.restore()
        Database.resetForTest()
    }

    /** 作废后回 FORM 且表单保留（featureDesc/entryHints 不清）+ 计划面清空 */
    @Test
    fun `abandon plan returns to form keeping feature desc and entry hints`() {
        val vm = DistillViewModel()
        // 起点态：表单已填 + 探索已产计划（经测试缝直置 PLAN_REVIEW——生产需真实 LLM）
        vm.setFeatureDesc("设置中心（SettingsPresenter / 主题状态机）")
        vm.setEntryHints(listOf("SettingsPresenter", "SettingsStateMachine"))
        vm.setPlanReviewFixtureForTest(
            repo = "settings",
            items = listOf(
                DistillViewModel.PlanRow(path = "src/SettingsPresenter.kt", reason = "入口 Presenter"),
                DistillViewModel.PlanRow(path = "src/SettingsManager.kt", reason = "状态管理"),
            ),
        )
        assertEquals(DistillViewModel.Phase.PLAN_REVIEW, vm.phase.value, "前置：测试缝已置 PLAN_REVIEW")

        vm.abandonPlan()

        assertEquals(
            DistillViewModel.Phase.FORM,
            vm.phase.value,
            "AG-09：作废显式出口回 FORM（旧文案承诺「关闭页面作废」但 VM 窗口级驻留根本不成立）",
        )
        assertTrue(vm.planItems.value.isEmpty(), "计划行清空（这份计划不要了）")
        assertNull(vm.planRepo.value, "计划 repo 标识清空")
        assertEquals(
            "设置中心（SettingsPresenter / 主题状态机）",
            vm.featureDesc.value,
            "🔴 表单内容保留——与 restart 的分界：restart 清空（重来），作废保留（表单续用）",
        )
        assertEquals(
            listOf("SettingsPresenter", "SettingsStateMachine"),
            vm.entryHints.value,
            "entry hints 同样保留——误入的用户改两笔描述即可重探",
        )
        assertFalse(vm.running.value, "作废后无在途会话（session.cancel 幂等——PLAN_REVIEW 态本无 running）")
    }

    /** 与 restart 的对照分界：restart 全清（既有行为不动），abandonPlan 只清计划面 */
    @Test
    fun `restart clears form while abandon keeps it - the two exits stay distinct`() {
        val vm = DistillViewModel()
        vm.setFeatureDesc("某功能描述")
        vm.setEntryHints(listOf("Hint"))
        vm.setPlanReviewFixtureForTest(repo = "x", items = listOf(DistillViewModel.PlanRow(path = "a.kt", reason = "r")))

        vm.restart()
        assertEquals(DistillViewModel.Phase.FORM, vm.phase.value)
        assertEquals("", vm.featureDesc.value, "restart 全清（审阅后重来的既有语义——本卡不动）")
        assertTrue(vm.entryHints.value.isEmpty())

        // 再走一遍作废路径确认分界不是「顺带被 restart 改掉」
        vm.setFeatureDesc("另一份描述")
        vm.setEntryHints(listOf("Hint2"))
        vm.setPlanReviewFixtureForTest(repo = "y", items = listOf(DistillViewModel.PlanRow(path = "b.kt", reason = "r")))
        vm.abandonPlan()
        assertEquals("另一份描述", vm.featureDesc.value, "abandonPlan 保留表单——两出口语义恒分界")
        assertEquals(listOf("Hint2"), vm.entryHints.value)
    }

    /** 失实文案「关闭页面作废」消失 + 显式钮与新文案在位（源码锚——扫盘防回潮） */
    @Test
    fun `misleading close-page-abandons copy gone and explicit abandon exit wired`() {
        // 🔴 剥注释后扫描（KnowledgeUriPrefixSingleSourceGuardTest 同款口径）：验收锚的是
        // 用户可见字符串字面量，注释里的历史叙述文字（如本卡改动说明）不误伤
        val page = stripKotlinComments(distillPageSource())
        assertFalse(
            "关闭页面作废" in page,
            "失实文案「关闭页面作废」必须从页面消失——VM 窗口级驻留下切签/关 tab 不作废，承诺失实（AG-09）",
        )
        assertTrue(
            "作废本次计划" in page,
            "「作废本次计划」显式钮在位（PLAN_REVIEW 第三出口）",
        )
        // 接线面：钮必须真接 VM（光有文案不算出口）
        assertTrue(
            "onAbandon = vm::abandonPlan" in page,
            "作废钮经 DistillPlanReview 的 onAbandon 参数真接 vm::abandonPlan（死文案不算显式出口）",
        )
        assertTrue(
            "fun abandonPlan" in stripKotlinComments(distillViewModelSource()),
            "VM 侧 abandonPlan 独立实现在位（不复用 restart——restart 清表单，作废须保留）",
        )
    }

    // ── 源码锚（扫盘基建——CockpitRemainingPillGuardTest 同款配方） ──

    private fun distillPageSource(): String = sourceFile(DISTILL_PAGE).readText()

    private fun distillViewModelSource(): String = sourceFile(DISTILL_VM).readText()

    private fun sourceFile(relativePath: String): File {
        val file = File(locateProjectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后请同步本测试路径）")
        return file
    }

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

    /**
     * 剥除行注释（//）与块注释（/** */、/* */），保留代码与字符串字面量内容——
     * 字符串感知（`"..."`/`"""..."""` 各自成态，字符串态内不识别注释起始），配方照抄
     * KnowledgeUriPrefixSingleSourceGuardTest.stripKotlinComments（UiArchitectureGuardTest 同款）。
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
        const val DISTILL_PAGE =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillPage.kt"
        const val DISTILL_VM =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/DistillViewModel.kt"
    }
}
