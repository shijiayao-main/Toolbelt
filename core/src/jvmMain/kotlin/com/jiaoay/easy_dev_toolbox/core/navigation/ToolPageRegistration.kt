package com.jiaoay.easy_dev_toolbox.core.navigation

import androidx.compose.runtime.Composable
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 页面注册契约：feature 模块经此把「页面 key + 导航元数据 + 入口 Composable」
 * 注册回宿主导航（:composeApp 的 ToolPageRegistry 单点拼接）。
 *
 * - [navItem] 为 null 表示非工具分组页（欢迎页 / 设置 / 调试 / 应用中心）：不进侧栏分组
 *   与欢迎页提示卡；
 * - [navVisible] 导航可见性流（默认 [ALWAYS_VISIBLE]）：feature 侧提供、宿主侧消费，
 *   用于「按运行时开关隐藏某个工具页」；
 *    [aiEnabled] 同理：AI 开关状态流经契约上送，宿主不 import
 *    feature-ai 的 AiToolsToggle。[navActivity] 同理：活动状态流
 *    经契约上送，宿主不 import 各 feature 的运行时单例。[actions] 同理：动作经契约上送，
 *    宿主不 import 各 feature 的动作实现。
 *
 * 新增 feature 配方：ToolPage 枚举加值（core/navigation/ToolPage.kt）→ feature 暴露
 * XxxContributions → 宿主 ToolPageRegistry 拼接表加一行。
 */
data class ToolPageRegistration(
    val page: ToolPage,
    val navItem: ToolNavItem?,
    val navVisible: StateFlow<Boolean> = ALWAYS_VISIBLE,
    val aiEnabled: StateFlow<Boolean>? = null,
    val navActivity: StateFlow<NavActivity>? = null,
    val group: ToolPageGroup? = null,
    val actions: List<ToolAction> = emptyList(),
    val paramCommands: List<ParamCommandSpec> = emptyList(),
    val sideBar: List<SideBarContribution> = emptyList(),
    val content: @Composable () -> Unit,
)

/**
 * 侧栏活动指示三态。
 * - [Idle]：无后台活动（默认）；
 * - [Running]：页面后台有任务在跑（桥接跑 agent / Shell 跑命令 / AI 解密中）；
 * - [Attention]：需要用户关注（出错 / 需确认——比 Running 更醒目）。
 */
enum class NavActivity { Idle, Running, Attention }

/**
 * 恒可见流（默认 [ToolPageRegistration.navVisible]）。
 *
 * 单例共享同一实例：无隐藏需求的注册项全指向它，避免每次构造都新建 StateFlow。
 */
val ALWAYS_VISIBLE: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
