package com.jiaoay.easy_dev_toolbox.core.navigation

/**
 * 报告页跳转中立槽（run 侧栏「全部报告」CTA）。
 *
 * **中立回调槽（:core 单点，[GoSettingsHook] 同款配方）**：feature 不得 import
 * 宿主自有包（UiGuard feature 隔离钉死），且 ToolPageRegistration.content 是
 * 零参 @Composable 工厂——宿主无法向页面传回调实参，故跳报告页语义经本槽中转：
 * - **生产侧**（feature-logworkbench WorkbenchSidePanelContent）：run 历史侧栏
 *   顶部「全部报告」行钮点击调 [offer]（null = 宿主未接线，调用点静默 no-op）——
 *   run 列表与报告页形成「近 50 份报告」的浏览面互指；
 * - **消费侧**（宿主 composeApp）：AppViewModel init wire——
 *   `wire { setCurrentPage(ToolPage.Report) }`。
 *
 * 与 AI 无关（确定性功能）——AI 关闭时入口仍可用。dock 不加图标（五空间格局不动，
 * 报告保持文档页定位）。
 */
object GoReportHook {

    @Volatile
    private var callback: (() -> Unit)? = null

    /** 宿主接线（AppViewModel init——生产侧入口渲染与响应的开关真源） */
    fun wire(callback: (() -> Unit)?) {
        this.callback = callback
    }

    /** feature 侧调用（null 槽 = 宿主未接线——调用点静默返回 false 表示不可用） */
    fun offer(): Boolean {
        val cb = callback ?: return false
        cb()
        return true
    }

    /** 测试缝：清空接线（单测隔离用） */
    fun clearForTest() {
        callback = null
    }
}
