package com.jiaoay.easy_dev_toolbox.host

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GLB-04 守护（重设计批 2 · 2026-09-30）：状态栏面包屑空间路径语义。
 *
 * 收口依据：ToolPageGroup 是管线归组判据（卖点归组），不是用户导航词汇——
 * 旧形态「分组名 / 页名」产「智能体 / 工作台」（落点页错误冠组）、「智能体 /
 * 智能体中心」（逐字重复）、「入口 / 飞书桥接」（管线词）三类畸形；且宿主页
 * （设置/报告/调试）无 group 降级裸页名——同条状态栏两种信息结构。
 *
 * 终态（.crumb 两层结构）：恒「Toolbelt · 规范页名」两层，页名
 * [ToolPageLabels] 单源直取（与窗口标题语义合流），全部页同构渲染无降级分支。
 *
 * 守护面（防回潮）：
 * 1. StatusBreadcrumb 渲染行 = `"Toolbelt · $pageName"` 形态锚；
 * 2. navSections / groupName 查表消费零残留（回潮即红——工程归组词退场）；
 * 3. navItem 兜底保留（防未来新增页漏登记时面包屑空白——非查表逻辑）。
 *
 * 范围钉死：ToolPageRegistry.navSections 本体与 groupTitles 保留不动
 * （未来分组卡/OnboardingCard 回归位 + NavTest:101 分组不变量断言不受影响）。
 *
 * 🔴 验收纪律（扫盘型同款）：--rerun-tasks --no-build-cache 防 build cache 假绿。
 */
class StatusBreadcrumbPathGuardTest {

    @Test
    fun `breadcrumb renders toolbelt-space path with label single source`() {
        val bar = statusBarFile()
        val text = bar.readText()

        // ① 空间路径形态锚：恒「Toolbelt · 页名」，页名 ToolPageLabels 单源
        assertTrue(
            "text = \"Toolbelt · \$pageName\"" in text,
            "StatusBreadcrumb 渲染必须为 \"Toolbelt · \$pageName\"（GLB-04 空间路径两层——" +
                "与 shell.html .crumb 同构），实际源码未命中。",
        )
        assertTrue(
            "ToolPageLabels.label(currentPage)" in text,
            "面包屑页名必须经 ToolPageLabels 单源直取（GLB-04 acceptance：与窗口标题语义合流）。",
        )

        // ② 分组查表消费零残留（navSections/groupName 回潮即红；全文件口径——
        //    调用点注释同守（复核 P1-1：:98 旧注「分组名 / 页名」曾漏改漂移））
        assertFalse(
            "navSections" in text,
            "AppStatusBar 全文件不得再查 navSections 分组表（GLB-04：工程归组词「智能体/入口」退场，" +
                "管线归组判据非用户导航词汇）。",
        )
        assertFalse(
            "groupName" in text,
            "AppStatusBar 全文件不得再消费 groupName（GLB-04：删 navSections 查表 + groupName 段，" +
                "防「智能体 / 智能体中心」逐字重复类畸形回潮；调用点注释同守）。",
        )

        // ③ navItem 兜底保留（防未来漏登页面包屑空白——GLB-04 明示保留项）
        val breadcrumbFun = text.substringAfter("private fun StatusBreadcrumb")
            .substringBefore("\n}")
        assertTrue(
            "?: navItem?.label" in breadcrumbFun,
            "navItem 注册名兜底必须保留（GLB-04 卡面：防未来新增页漏登记时面包屑不至于空白）。",
        )
    }

    /** 守护锚文件（缺失即红——重命名/移动后须同步本守护路径） */
    private fun statusBarFile(): File {
        val file = File(locateProjectRoot(), APP_STATUS_BAR_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$APP_STATUS_BAR_PATH（重命名/移动后请同步本守护）")
        return file
    }

    private companion object {
        const val APP_STATUS_BAR_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/host/AppStatusBar.kt"
    }
}
