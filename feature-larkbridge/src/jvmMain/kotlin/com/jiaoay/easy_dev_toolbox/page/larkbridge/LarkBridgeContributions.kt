package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolAction
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolNavItem
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/**
 * 飞书桥接页的导航注册出口：宿主 `ToolPageRegistry` 单点拼接（模块化注册契约）。
 *
 * registrations 为纯数据（content 是惰性 @Composable 工厂，切到该页才组合），
 * 新 feature 照此配方暴露一个 object 即可。
 *
 * [navItem] 的 desc 是欢迎页提示卡文案：新架构为 SDK 长连接单通道，
 * 旧「支持 CLI 与 OpenAPI 两种通道」的说法已随 lark-cli 传输层整体消失，不得回写。
 *
 * S3 · [navActivity]：桥接页的侧栏活动指示（Running = 桥接在跑；Idle = 未跑）。
 * 数据源 [LarkBridgeManager.status]（`BridgeStatusSnapshot.running`——1Hz 状态快照，
 * 布尔本就无高频）；Attention 态保留给未来的「需确认/出错」分流（当前连接需关注时
 * 桥接状态页已有警示行，侧栏不重复升级）。
 */
object LarkBridgeContributions {

    private val navItem = ToolNavItem(
        page = ToolPage.LarkBridge,
        // FORUM 图标——与 dock 空间集同字形（图标=页面指纹）
        icon = MaterialSymbols.FORUM,
        label = "飞书桥接",
        desc = "把本地智能体接进飞书群：消息收发 / 日志转推",
    )

    /** S3 · 桥接活动流：未装配/未跑 ⇒ Idle；在跑 ⇒ Running（布尔源，无高频重组面）。 */
    val navActivity: StateFlow<NavActivity> =
        LarkBridgeManager.activityFlow

    /** P1 · 停止桥接动作（仅 Running 时可见；薄转发进 Manager 非挂起门面） */
    private val stopBridgeAction = ToolAction(
        id = "bridge.stop",
        label = "停止飞书桥接",
        desc = "优雅断开长连接并停止消息收发",
        icon = MaterialSymbols.STOP_CIRCLE,
        keywords = listOf("bridge", "stop", "桥接", "停止"),
        isDangerous = true,
        visibleWhen = { LarkBridgeManager.activityFlow.value == NavActivity.Running },
        perform = { LarkBridgeManager.requestStop() },
    )

    val registrations: List<ToolPageRegistration> = listOf(
        ToolPageRegistration(
            page = ToolPage.LarkBridge,
            navItem = navItem,
            navActivity = navActivity,
            group = ToolPageGroup.ENTRY,
            actions = listOf(stopBridgeAction),
        ) { LarkBridgePage() },
    )
}
