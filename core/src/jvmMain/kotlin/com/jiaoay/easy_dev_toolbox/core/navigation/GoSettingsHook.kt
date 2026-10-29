package com.jiaoay.easy_dev_toolbox.core.navigation

/**
 * 设置页跳转中立槽（设备页「检测失败 → 去设置配置 adb」CTA）。
 *
 * **中立回调槽（:core 单点，[DeepAnalyzeHook] 同款配方）**：feature 不得 import
 * 宿主自有包（UiGuard feature 隔离钉死），且 ToolPageRegistration.content 是
 * 零参 @Composable 工厂——宿主无法向页面传回调实参，故跳设置页语义经本槽中转：
 * - **生产侧**（feature-shelltools ShellToolsPage）：设备状态栏检测失败态的
 *   「去设置配置 adb 路径」入口点击调 [offer]（null = 宿主未接线，点击静默 no-op——
 *   入口恒渲染，无渲染门控）；
 * - **消费侧**（宿主 composeApp）：AppViewModel init wire——
 *   `wire { setCurrentPage(ToolPage.Setting) }`。
 */
object GoSettingsHook {

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
