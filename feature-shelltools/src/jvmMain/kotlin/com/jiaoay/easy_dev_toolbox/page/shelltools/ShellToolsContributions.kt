package com.jiaoay.easy_dev_toolbox.page.shelltools

import dev.vicart.compose.material.symbols.MaterialSymbols

import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.navigation.SideBarContribution
import com.jiaoay.easy_dev_toolbox.core.navigation.SideBarSlot
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolAction
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolNavItem
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsViewModel
import com.jiaoay.easy_dev_toolbox.page.shelltools.history.ShellHistoryPanel

/**
 * Shell 工具页的导航注册出口。
 *
 * [navActivity]：侧栏/状态栏活动指示（Running = 引擎在跑命令；
 * Attention = 最近一次执行失败需关注）。源 [ShellToolsRuntime.activity]
 * （App 级单例，脱离页面生命周期）。
 *
 * [actions] 命令面板动作项：薄转发进
 * [ShellToolsRuntime.engine]（App 级单例门面，契约纪律「不捕获局部态」）。
 */
object ShellToolsContributions {

    private val navItem = ToolNavItem(
        page = ToolPage.ShellTools,
        icon = MaterialSymbols.TERMINAL,
        label = "Shell 工具",
        desc = "常用 adb / shell 快捷指令一键执行，支持自定义命令与历史回放",
    )

    /** 停止当前命令：仅引擎在跑时可见（visibleWhen 面板打开瞬间求值） */
    private val stopCurrentAction = ToolAction(
        id = "shell.stop-current",
        label = "停止当前 Shell 命令",
        desc = "终止执行中的命令并清空等待队列",
        icon = MaterialSymbols.STOP_CIRCLE,
        keywords = listOf("stop", "停止", "终止"),
        isDangerous = true,
        visibleWhen = { ShellToolsRuntime.engine.status.value is ExecStatus.Running },
        perform = { ShellToolsRuntime.engine.stop() },
    )

    val registrations: List<ToolPageRegistration> = listOf(
        ToolPageRegistration(
            page = ToolPage.ShellTools,
            navItem = navItem,
            navActivity = ShellToolsRuntime.activity,
            group = ToolPageGroup.TOOLS,
            actions = listOf(stopCurrentAction),
            // 侧栏槽位：历史记录面板（页面作用域 VM 可达——SideBarHost 统一承载
            // 可拖宽/可折叠，替代页内固定 280dp 样板）。
            // R 批补 icon/label：窄轨入口 + 面板小节标题（右侧化后槽位在窗口最右缘）
            sideBar = listOf(
                SideBarContribution(
                    slot = SideBarSlot.End,
                    icon = MaterialSymbols.HISTORY,
                    label = "历史记录",
                ) {
                    ShellHistoryPanel(viewModel = ShellToolsViewModel.getViewModel())
                },
            ),
            // 「输出」页签随 BottomPanel 一并退役，
            // Shell 输出回页内渲染（ShellToolsPage 直挂 ShellOutputPanelContent——App 级
            // engine 源不变，仅渲染位置回页面）。
        ) { ShellToolsPage() },
    )
}
