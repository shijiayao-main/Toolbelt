package com.jiaoay.easy_dev_toolbox.page.logworkbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolNavItem
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 日志工作台域的导航注册出口（P4-C2 openspec log-workbench 任务 2.1/2.2）。
 *
 * 智能体组第三成员（组内序 [工作台, AI 设置, 蒸馏]——featureRegistrations 拼接序）。
 *
 * aiEnabled **不在此上送**：AiToolsToggle 真源在 feature-ai（page.ai 包），
 * feature 互不依赖铁律下本模块不得 import——由**宿主拼接点**（ToolPageRegistry）
 * copy 注入 `aiEnabled = AiToolsToggle.enabled`（宿主可自由 import；开关真源唯一）。
 * 本注册项 aiEnabled 默认 null 时页面恒可见可进（管线区离线可用——置灰语义经宿主注入）。
 *
 * navActivity 占位流仅在本模块注册项上保留兜底——宿主拼接点已 copy 注入
 * `WorkbenchSession.activity`（运行态真源，见 ToolPageRegistry）。
 * ToolAction 面以 ToolPageRegistryNavTest 断言为准（现含 shell.stop-current/bridge.stop）。
 */
object LogWorkbenchContributions {

    private val navItem = ToolNavItem(
        page = ToolPage.LogWorkbench,
        icon = MaterialSymbols.DESCRIPTION,
        label = "工作台",
        desc = "确定性管线 + agent 研判：拖日志、按时间点过滤、证据流回看与根因报告（研判需 AI）",
    )

    /** 研判运行态兜底占位（宿主拼接点 copy 注入 WorkbenchSession.activity 后本值不生效） */
    private val navActivity: StateFlow<NavActivity> = MutableStateFlow(NavActivity.Idle).asStateFlow()

    val registrations: List<ToolPageRegistration> = listOf(
        ToolPageRegistration(
            page = ToolPage.LogWorkbench,
            navItem = navItem,
            group = ToolPageGroup.AGENTS,
            navActivity = navActivity,
            // sideBar 贡献已删——输入表单 + run 列表回归页内
            // 左栏（inspector 对本页零贡献 = PageHome isNotEmpty
            // 门控天然不渲染，宽轨/窄轨均不含本页）。
        ) {
            LogWorkbenchPage()
        },
    )
}
