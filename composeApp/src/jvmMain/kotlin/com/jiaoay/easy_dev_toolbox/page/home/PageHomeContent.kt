package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.page.ToolPage

/**
 * 主区页面分发（即时切换——页面切换动画已整体退役，currentPage 变化直接重组渲染）：
 *
 * - 注册表 map 分发（注册化）：未知 page（理论不可达，枚举与注册表同步演进）
 *   回退工作台家（Default 欢迎页注册项已删，fallback 目标 = LogWorkbench）；
 * - AppCenter 注册项已删——currentPage 恒不为 AppCenter
 *   （无入口）；map 兜底分支顺手承接任何残余恢复态（落工作台家）。
 * - 五件套散页兜底重定向——残余恢复态落五件套枚举时改渲染
 *   工具聚合页（深入模式路由归并后散页整页形态退役，子签承接全部入口）。
 * - 兜底扩容 AgentCenter——三退役 AI 散页（Flow/Distill/
 *   VoiceTranscribe）残余恢复态同款改道聚合页渲染，散页整页形态的渲染可达面
 *   彻底清零（五件套 + 三 AI 散页全部经聚合页子签承载）。
 */
@Composable
fun PageHomeContent(
    appViewModel: AppViewModel = AppViewModel.getViewModel(),
) {
    val currentPage by appViewModel.currentPage.collectAsState()
    val registrations: List<ToolPageRegistration> = ToolPageRegistry.registrations(appViewModel)

    // 散页兜底重定向：currentPage 残余落在散页枚举时（存量 last_page
    // 恢复、旧版本深链等绕过 palette 归并的路径）改渲染承载它的聚合页（hostOf 单源
    // 映射——五件套 → Tools / 三 AI 散页 → AgentCenter）——散页整页形态退役，聚合页
    // 子签承接全部入口。散页注册项 content 暂留（NavTest 契约面），本层是分发口的
    // 兜底闸——注册表 map 对散页 key 不再有渲染可达面。
    val effectivePage = ToolPageLabels.hostOf(currentPage)
        ?.takeIf { it == ToolPage.Tools || it == ToolPage.AgentCenter }
        ?: currentPage
    val reg = registrations.firstOrNull { it.page == effectivePage }
        ?: registrations.first { it.page == ToolPage.LogWorkbench }
    reg.content()
}
