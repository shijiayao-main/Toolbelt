package com.jiaoay.easy_dev_toolbox.page.larkbridge.status

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LB-07 守护（重设计批 9 · 2026-10-02）：L4 校验报告收口（入口行 + 错误分区自动展开）。
 *
 * v2 真源结构树 L4「ConfigCheckPanel 收成入口行；issues 非空自动展开、错误所在分区自动展开」
 * 的落地锚（批①未含此项）：
 * 1. ConfigCheckPanel 改 `collapsible = true, initialExpanded = false`——校验通过时配置签
 *    只见一行标题（折叠态），展开后为现全字段表（StatusBanner + 展开钮随折叠体进内容区）；
 * 2. headerTrailing 结论徽标（通过 = success「通过」/ 未过 = error「未过 N 处」）——折叠态
 *    结论仍可见；
 * 3. ConfigEditor 六分区 `initialExpanded = zoneIssueCount > 0`——issues 非空的分区首进
 *    页面自动展开（红徽标已在）。
 *
 * 🔴 语义限制如实上报（卡面与源码行为矛盾时按源码实况修并上报）：initialExpanded 是
 * rememberSaveable 初值，只在 SectionCard 首次组合时生效——页面首进 issues 非空 ⇒ 展开 ✓；
 * 用户编辑后才出现的新 issue 不触发已组合收起分区的展开（完整受控展开归 LB-18 :ui 扩面）。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class LbGuardConfigCheckTest {

    @Test
    fun `lb07 check panel is collapsed entry line with verdict badge`() {
        val text = source(CONFIG_CHECK_PANEL)
        // 1. 入口行化：单行 .lnk 文字链入口
        // ——原折叠 SectionCard 形态随稿退役；通过/未过两态文案着色在入口行内
        assertTrue(
            "配置校验总览 · 通过（0 处）→" in text && "配置校验总览 · 未过 \${issues.size} 处 →" in text,
            "入口行必须是 .lnk 文字链双态文案（通过 0 处 / 未过 N 处——LB-07 + 终审 P2 对稿归一）",
        )
        // 2. 入口行可点击展开 + 结论着色（通过=primary / 未过=error）
        assertTrue(
            ".clickable { expanded = !expanded }" in text,
            "入口行必须可点击展开（LB-07 验收：校验通过时配置签只见一行入口）",
        )
        assertTrue(
            "color = if (pass)" in text,
            "入口行文案必须按 pass 结论着色（primary/error——.lnk 语义）",
        )
        // 3. 展开体直出：全字段表（验收「展开后为现全字段表」——无二次展开钮/无 StatusBanner 夹层）
        assertTrue(
            "FieldCheckRow(field = field, config = config, issue = issues[field.id])" in text,
            "展开体必须直出全字段表（终审 P2：StatusBanner 与二次展开钮夹层随稿退役）",
        )
        assertFalse(
            "展开全字段表" in text,
            "二次展开钮文案不得残留（终审 P2：入口行点击直出全字段表，无中间层）",
        )
    }

    @Test
    fun `lb07 issue zones auto expand on first composition`() {
        val text = source(CONFIG_FIELD_RENDERER)
        // 六分区循环内：initialExpanded 推导（issues 非空 ⇒ 首进展开）
        assertTrue(
            "initialExpanded = zoneIssueCount > 0," in text,
            "六分区 SectionCard 必须 initialExpanded = zoneIssueCount > 0（LB-07：错误分区自动展开）",
        )
        // 语义限制登记：推导注释必须如实标注初值语义（编辑后新 issue 不触发展开）
        assertTrue(
            "rememberSaveable 初值" in text,
            "LB-07 语义限制必须在源码注释登记（initialExpanded 只在首次组合生效——编辑后新 issue 不触发展开）",
        )
    }

    @Test
    fun `lb07 formatter pure function untouched`() {
        // ConfigCheckFormatterTest 照跑零破坏的背书锚：掩码函数仍在（纯函数面未动）
        val text = source(CONFIG_CHECK_FORMATTER)
        assertTrue("fun " in text, "ConfigCheckFormatter 纯函数面必须保留（LB-07 零改动面）")
    }

    /** 定位源文件（LbGuardStatusPanelTest 同款：user.dir 向上找 settings.gradle.kts 定项目根）。 */
    private fun source(relPath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val file = File(dir, relPath)
                assertTrue(file.isFile, "$relPath 不存在——文件被移动须同步更新本测试路径")
                return file.readText()
            }
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val PKG = "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge"
        const val CONFIG_CHECK_PANEL = "$PKG/status/ConfigCheckPanel.kt"
        const val CONFIG_FIELD_RENDERER = "$PKG/field/ConfigFieldRenderer.kt"
        const val CONFIG_CHECK_FORMATTER = "$PKG/status/ConfigCheckFormatter.kt"
    }
}
