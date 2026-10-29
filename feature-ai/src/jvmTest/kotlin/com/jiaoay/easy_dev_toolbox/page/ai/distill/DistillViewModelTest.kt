package com.jiaoay.easy_dev_toolbox.page.ai.distill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 计划 JSON 解析单测（P4-B 任务 4.3，纯函数零依赖——经 companion [DistillViewModel.parsePlanJsonImpl]
 * 直测，不构造 VM：VM 装配触达 Database.database() 真单例）。
 *
 * 解析是审阅链承重面：幻觉/畸形计划会直接进执行 prompt（用户批准的文件清单），
 * 必须钉死：正常解析 / 代码块包裹容忍 / 畸形输入 null 不炸 / 空 plan 合法。
 */
class DistillViewModelTest {

    private fun parsePlan(raw: String): Pair<String, List<DistillSession.PlanItem>>? =
        DistillViewModel.parsePlanJsonImpl(raw)

    @Test
    fun `plan json parses repo and items`() {
        val raw = """
            {"repo": "settings", "plan": [{"path": "src/SettingsPresenter.kt", "reason": "入口 Presenter"}, {"path": "src/SettingsManager.kt", "reason": "状态管理"}], "notes": ["x"]}
        """.trimIndent()

        val (repo, items) = parsePlan(raw)!!
        assertEquals("settings", repo)
        assertEquals(2, items.size)
        assertEquals("src/SettingsPresenter.kt", items[0].path)
        assertEquals("入口 Presenter", items[0].reason)
    }

    @Test
    fun `plan json tolerates code fence wrapping`() {
        val raw = """
            ```json
            {"repo": "demo", "plan": [{"path": "a.kt", "reason": "r"}], "notes": []}
            ```
        """.trimIndent()

        val (repo, items) = parsePlan(raw)!!
        assertEquals("demo", repo)
        assertEquals(1, items.size)
    }

    @Test
    fun `plan json bad input returns null instead of crashing`() {
        assertNull(parsePlan("这不是 JSON"))
    }

    @Test
    fun `plan json empty plan is valid`() {
        // plan 为空数组 = 合法（agent 如实输出「找不到相关文件」——UI 提示重试而非崩溃）
        val (repo, items) = parsePlan("{\"repo\": \"x\", \"plan\": []}")!!
        assertEquals("x", repo)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `plan json missing repo falls back to unknown`() {
        val (repo, items) = parsePlan("{\"plan\": [{\"path\": \"a.kt\", \"reason\": \"r\"}]}")!!
        assertEquals("unknown", repo)
        assertEquals(1, items.size)
    }
}
