package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.page.ToolPage

/**
 * 导航可见性与 AI 置灰的**单一产出点**：把各注册项的 `navVisible` / `aiEnabled` 流
 * 归集成「应隐藏的页面集」与「应置灰的 AI 页面集」，供各消费方（侧栏 dock /
 * 命令面板 / 欢迎页提示卡 / Ctrl+Tab 快速切页 / 应用中心）使用。
 *
 * 为何集中而非各处自行过滤：多处消费逻辑若各写一遍，极易漂移——例如侧栏藏了但
 * Ctrl+Tab 还能进隐藏页（真实风险点，`main.kt` 的 Window 级快捷键不走侧栏数据）。
 * 一处产出即天然一致。
 *
 * 可见性有**两个维度**，语义不同不得混用：
 * - [rememberHiddenNavPages]：页**不在导航出现**（当前仅 navVisible=false，无人使用此维度，
 *   契约保留给未来「彻底移除的页」）；
 * - [rememberAiDisabledPages]：页**可见但置灰**（AI 开关关闭的 agent 依赖页；
 *   置灰页仍可出现在导航（角标 + 点击引导），不参与 Ctrl+Tab 循环与命令面板跳转直达。
 */

/**
 * 应隐藏的页面集（navVisible=false 者）。
 * Composition 内逐流订阅（来源数量固定且很小，逐流比 combine 变长数组直白）。
 */
@Composable
fun rememberHiddenNavPages(): Set<ToolPage> {
    val sources = ToolPageRegistry.navVisibilitySources()
    // 逐项订阅：每个注册项的 navVisible 都是 StateFlow，翻转即重组
    val visibility: List<Pair<ToolPage, Boolean>> = sources.map { registration ->
        val visible by registration.navVisible.collectAsState()
        registration.page to visible
    }
    return remember(visibility) {
        visibility.filter { (_, visible) -> !visible }.map { (page, _) -> page }.toSet()
    }
}

/**
 * 应置灰的 AI 页面集（aiEnabled 流非 null 且当前为 false 者）。
 * 页面仍显示（带 AI 角标），点击引导去 AI 设置开开关。
 */
@Composable
fun rememberAiDisabledPages(): Set<ToolPage> {
    val sources = ToolPageRegistry.aiEnabledSources()
    if (sources.isEmpty()) return emptySet()
    val states: List<Pair<ToolPage, Boolean>> = sources.map { registration ->
        val enabled by registration.aiEnabled!!.collectAsState()
        registration.page to enabled
    }
    return remember(states) {
        states.filter { (_, enabled) -> !enabled }.map { (page, _) -> page }.toSet()
    }
}

/**
 * 各页当前的侧栏活动指示：navActivity 非 null 的注册项 → page → 活动三态。
 * 缺席的页不在 Map 中（消费方按 `map[page] ?: NavActivity.Idle` 读默认）。
 * 归集为 Map 而非 Set：三态（Idle/Running/Attention）需按页取值，且 Attention 与
 *    Running 的视觉权重不同（消费方分派不同 token）。
 */
@Composable
fun rememberNavActivity(): Map<ToolPage, NavActivity> {
    val sources = ToolPageRegistry.navActivitySources()
    if (sources.isEmpty()) return emptyMap()
    val states: List<Pair<ToolPage, NavActivity>> = sources.map { registration ->
        val activity by registration.navActivity!!.collectAsState()
        registration.page to activity
    }
    return remember(states) { states.toMap() }
}
