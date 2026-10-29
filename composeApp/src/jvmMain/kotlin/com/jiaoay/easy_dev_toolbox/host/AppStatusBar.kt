package com.jiaoay.easy_dev_toolbox.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.core.runtime.AppHost
import com.jiaoay.easy_dev_toolbox.core.runtime.CapabilityState
import com.jiaoay.easy_dev_toolbox.core.runtime.CapabilityId
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalDayBattle
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.home.rememberNavActivity
import com.jiaoay.easy_dev_toolbox.page.home.ToolPageLabels
import com.jiaoay.easy_dev_toolbox.page.home.ToolPageRegistry
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassChromeStatusbar
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 应用级状态栏（「三舱骨架·单点收敛」——VS Code 状态栏语义）：窗口底部
 * [Dimens.controlCompact] 通栏、**严格只读**的状态读数 + 跳转入口。
 *
 * 三舱定位：dock 是唯一导航层、状态栏是状态层——**不承载任何决策**
 * （密度切换/开关拨动/动作执行一律不进状态栏，防膨胀为第二个工具栏）。
 *
 * 结构（左→右）：
 * - **左段·面包屑**：`Toolbelt · 页名`（空间路径语义——页名恒
 *   [ToolPageLabels] 单源，全部页同构渲染无降级分支；工程归组词「智能体/入口」
 *   退场——与窗口标题语义合流）。切页直接换字，零动画（工作台语言）。
 * - **右段**：
 *   - 常驻段：**Ctrl+K 命令面板提示**（弱色常驻、点击唤起
 *     面板；跳转入口先例：与活动段/桥接段/AI 段同构，均「读数 + 点击直达」，
 *     不承载决策故合规）；
 *   - 按需段（仅在有内容时渲染单项，无内容整段留白不留占位）：
 *     - 后台活动：navActivity 聚合（N 个页面在跑 → 「N 项后台任务」，点击跳首个
 *       Running 页；有 Attention 优先展示「需关注」并跳该页）；
 *     - 桥接连接：GATEWAY 能力态（CapabilityRegistry 四态——Verified 绿点 /
 *       Degraded 黄点 + hover 文案 / PermissionRequired/Unavailable 灰点）；
 *     - AI 开关：经注册表 aiEnabled 流（任一 AI 页关闭即「AI 关（点击配置）」，
 *       点击跳 AI 设置页）。
 *
 * 设备状态**不进状态栏**（既有裁定）：设备检测/选定态的真源是 :core 设备域
 *    （DeviceMonitor App 级单源），可见消费面 = Shell 页设备行；状态栏
 *    chrome 不承载设备语义，设备域是否进 dock 活动点/状态栏留观测空间
 *    接线时真机终裁。
 *
 * 视觉契约：chrome 恒实底
 * （opaqueSlotChrome——.statusbar background: var(--chrome-tint)，
 * 与标题栏/dock 同带一色；容器灰档偏差过大不取）glassBar 长条圆角
 * （与 tab 条同族）+ 顶部发丝 rim
 * （HorizontalDivider 分隔线退役——rim 即分隔语言）；高度 controlCompact
 * （Carbon 紧凑档）；labelSmall/iconSmall；零动画。
 */
@Composable
fun AppStatusBar(
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val currentPage by appViewModel.currentPage.collectAsState()
    val navActivity = rememberNavActivity()

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 状态栏是 chrome 常驻件（背后静态
            // aurora、内容被 contentPadding 挡在带外——blur 无从表达）→ 恒实底
            // （opaqueSlotChrome——.statusbar = --chrome-tint 与标题栏同带一色）；
            // 形态语言保留（glassBar 长条圆角 + 发丝 rim + 定缘）。
            .appGlassChromeStatusbar(
                // chrome 长条档：26dp 高
                // 上全圆渲染即 13dp 半径，与 glassBar 12dp 观感差 1px——账本归一非视觉
                // 变更，与 tab 条同族同圆角档
                shape = AppShapes.glassBar,
            )
            .height(Dimens.controlCompact)
            .padding(horizontal = Dimens.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // ── 左段：面包屑（Toolbelt · 页名）──
        // 弹性收缩（fill=false 宽窗不抢宽）：窄窗面包屑让宽省略，右段常驻件
        //（Ctrl+K 入口/活动/桥接/AI 段）恒完整可达
        StatusBreadcrumb(
            currentPage = currentPage,
            modifier = Modifier.weight(1f, fill = false),
        )

        // ── 右段：常驻 Ctrl+K 提示 + 按内容渲染（无内容整段留白，不留占位符） ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
        ) {
            StatusCommandPaletteSegment(
                onOpenPalette = { HotkeyHub.openCommandPalette?.invoke() },
            )
            StatusActivitySegment(
                navActivity = navActivity,
                onNavigate = { appViewModel.setCurrentPage(it) },
            )
            StatusGatewaySegment(
                onNavigate = { appViewModel.setCurrentPage(ToolPage.LarkBridge) },
            )
            StatusAiSegment(
                onNavigate = { appViewModel.setCurrentPage(ToolPage.AiSettings) },
            )
        }
    }
}

/** 面包屑：`Toolbelt · 页名`（空间路径语义——页名源 ToolPageLabels 单源） */
@Composable
private fun StatusBreadcrumb(
    currentPage: ToolPage,
    // 弹性落位由调用方注入（窄窗防溢出收缩——weight 是 RowScope 域修饰）
    modifier: Modifier = Modifier,
) {
    // 查表按页 remember：状态栏随 navActivity/能力态翻转高频重组，不再每次重建列表
    val navItem = remember(currentPage) { ToolPageRegistry.navItems().firstOrNull { it.page == currentPage } }
    // 页名单源：页名经 ToolPageLabels（与窗口标题/tab/dock tooltip 同源）
    val pageName = ToolPageLabels.label(currentPage)
        // navItem 注册名兜底（理论不达——ToolPageLabels 已覆盖全部可达页；
        // 防御未来新增页漏登记时面包屑不至于空白）
        ?: navItem?.label
    if (pageName == null) {
        Spacer(Modifier.size(Dimens.dotIndicatorSize))
        return
    }
    Text(
        text = "Toolbelt · $pageName",
        style = MaterialTheme.typography.labelSmall,
        // chrome 带取源井槽（Lowest）——文字走卡域链（井槽分家后基域深字对井面破 4.5；
        // 非覆写套 onCardMid 回落 onSurfaceVariant 逐位不变）
        color = LocalToolboxSurfaceColors.current.onCardMid,
        // 窄窗防溢出：面包屑弹性收缩（fill=false 宽窗不抢宽），右段常驻件
        //（Ctrl+K 入口/活动/桥接/AI 段）恒完整可达，长页名省略截断
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Ctrl+K 命令面板常驻段：弱色常驻提示 + 点击唤起。
 *
 * - **常驻语义**：无任何状态条件——Ctrl+K 是全应用的发现性入口，状态栏右侧
 *   恒可见（区别于按需段的活动/桥接/AI）；
 * - **唤起同源**：经 [HotkeyHub.openCommandPalette] 回调槽（main.kt Window 级
 *   注入——与 Ctrl+K 热键、dock「命令」入口完全同源，非平行实现）；
 * - **三舱定位合规**：本段是「跳转入口」先例（同活动段点击跳页——读数 + 点击
 *   直达），不承载决策（不开面板内容、不做任何开关），不违反状态栏只读纪律；
 * - **弱色**：onSurfaceMid（从 onSurfaceFaint 升档——状态栏 chrome 档
 *   实底比 Faint 文字色对比不足（thin 底实测 3.2:1）；Mid 档 = 5.5:1。常驻项
 *   视觉权重低于按需状态段的「弱」语义靠无图标小字承载，不再靠降对比度承载——
 *   降对比度以牺牲可读性为代价，非正道）。
 */
@Composable
private fun StatusCommandPaletteSegment(onOpenPalette: () -> Unit) {
    val surfaceColors = LocalToolboxSurfaceColors.current
    StatusSegment(
        icon = MaterialSymbols.MANAGE_SEARCH,
        text = "Ctrl+K 命令面板",
        contentDescription = "打开命令面板（Ctrl+K）",
        onClick = onOpenPalette,
        tint = surfaceColors.onCardMid,
    )
}

/** 后台活动段：聚合 navActivity——Running 计数 + Attention 优先 */
@Composable
private fun StatusActivitySegment(
    navActivity: Map<ToolPage, NavActivity>,
    onNavigate: (ToolPage) -> Unit,
) {
    val runningPages = navActivity.filterValues { it == NavActivity.Running }.keys
    val attentionPages = navActivity.filterValues { it == NavActivity.Attention }.keys

    val target = attentionPages.firstOrNull() ?: runningPages.firstOrNull() ?: return

    if (attentionPages.isNotEmpty()) {
        // 昼战（design-doc.md §5.3）：error 图标/文字直落昼战 chrome 破线——图形坐 errorContainerFixed 档、
        // 文字坐该面（error×errorContainerFixed 5.14，槽对同源 StatusBanner Error 槽）
        StatusSegment(
            icon = MaterialSymbols.NOTIFICATION_IMPORTANT,
            tint = MaterialTheme.colorScheme.error,
            text = "需关注：${labelOf(target)}",
            contentDescription = "跳转 ${labelOf(target)} 查看需关注事项",
            onClick = { onNavigate(target) },
            graphicCarrier = LocalEasyToolboxColors.current.errorContainerFixed,
            textCarrier = LocalEasyToolboxColors.current.errorContainerFixed,
        )
    } else {
        // 运行段 = 6dp info 蓝点（.stseg dotrun）
        // ——SYNC 图标 primary 紫弃用（dotrun 语义源 = StatusDot/DotState.RUN）；
        // 昼战坐 infoContainer 档（design-doc.md §5.3，dayBattleTint 图形 3.0 线）
        StatusSegment(
            dotColor = LocalEasyToolboxColors.current.info,
            text = if (runningPages.size > 1) {
                "${runningPages.size} 项后台任务"
            } else {
                "${labelOf(target)} 运行中"
            },
            contentDescription = "跳转 ${labelOf(target)}（后台运行中）",
            onClick = { onNavigate(target) },
            graphicCarrier = LocalEasyToolboxColors.current.infoContainer,
        )
    }
}

/** 桥接连接段：GATEWAY 能力四态（无上报 ⇒ 不渲染，不留占位） */
@Composable
private fun StatusGatewaySegment(onNavigate: (ToolPage) -> Unit) {
    val snapshot by AppHost.capabilities.snapshot.collectAsState()
    val gateway = snapshot[CapabilityId.GATEWAY] ?: return

    // 未配置 = 中性态非故障（首次使用路径——点开桥接页配置即解除）：与提示性
    // Ctrl+K 段同 chrome 档中性灰（onSurfaceMid），不与「故障=红」混载
    //（常驻红点红字 = 告警疲劳 + 语义混载）。待授权 PermissionRequired 保持
    // error（需行动的异常态）；Degraded 保持 tertiary（降级但可用——警示非故障）。
    // Verified 点色走 success（绿）——.dotok 语义（连接健康=绿）；
    // 与壳层「Running=primary」语言的冲突点由绿点分治消解（活动态紫、健康态绿）。
    // 昼战（design-doc.md §5.3）：success/error 语义点坐对应 container 档（dayBattleTint 图形 3.0 线）；
    // Degraded tertiary 橙昼战承载属「昼战橙徽章化」专项（design-doc.md §5.3#5 橙面+近黑描边语言），本段不接。
    val toolboxColors = LocalEasyToolboxColors.current
    val (dotColor, text) = when (gateway) {
        is CapabilityState.Verified -> dayBattleTint(toolboxColors.success, toolboxColors.successContainer) to "桥接在线"
        is CapabilityState.Degraded -> MaterialTheme.colorScheme.tertiary to "桥接降级"
        is CapabilityState.PermissionRequired -> dayBattleTint(MaterialTheme.colorScheme.error, toolboxColors.errorContainerFixed) to "桥接待授权"
        is CapabilityState.Unavailable -> LocalToolboxSurfaceColors.current.onCardMid to "桥接未配置"
    }

    StatusSegment(
        icon = null,
        dotColor = dotColor,
        text = text,
        contentDescription = if (gateway is CapabilityState.Unavailable) {
            "桥接未配置——点开桥接页按快速开始向导配置，配置完成后此段转绿"
        } else {
            "跳转桥接页（连接状态：$text）"
        },
        onClick = { onNavigate(ToolPage.LarkBridge) },
    )
}

/** AI 开关段：aiEnabled 流（任一 AI 页注册流 false 即显示；全开不渲染） */
@Composable
private fun StatusAiSegment(onNavigate: (ToolPage) -> Unit) {
    val aiEnabledSources = ToolPageRegistry.aiEnabledSources()
    if (aiEnabledSources.isEmpty()) return
    // C2 · 逐流订阅（修复陈旧读）：remember(states){ states.any{it.value} } 只在
    // 组合期读一次快照不订阅——AI 开关翻转时状态栏不刷新。逐流 collectAsState 后
    // 任一流翻转即重组（NavVisibility.rememberAiDisabledPages 同配方）。
    val allEnabled = aiEnabledSources.map { src ->
        src.aiEnabled!!.collectAsState().value
    }
    if (allEnabled.all { it }) return

    StatusSegment(
        // AI 段盾形（盾形 SVG 语义）——AUTO_AWESOME 星光弃用
        icon = MaterialSymbols.SHIELD,
        // AI 段恒紫（icon 与 text 经 StatusSegment 同参 tint 一并接线 aiAccent）；
        // 昼战（MC6 覆写板）：aiAccent 覆写绿对昼底仅图形级 3.24——icon 坐 aiAccentContainer
        // 档、文字坐 aiAccentContainer 芯片面（绿字对深紫 10.20，MC5「昼战消费位限芯片内」
        // 约束的落位）
        tint = LocalEasyToolboxColors.current.aiAccent,
        graphicCarrier = LocalEasyToolboxColors.current.aiAccentContainer,
        textCarrier = LocalEasyToolboxColors.current.aiAccentContainer,
        text = "AI 关",
        contentDescription = "AI 已关闭，点击跳转 AI 设置",
        onClick = { onNavigate(ToolPage.AiSettings) },
    )
}

/** 状态段原子：图标/圆点 + labelSmall 文字，可点击跳转（触媒 = 状态读数的跳转入口） */
@Composable
private fun StatusSegment(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    icon: String? = null,
    dotColor: androidx.compose.ui.graphics.Color? = null,
    // chrome 带文字链 = 卡域族（井槽分家后基域深字对井面破 4.5；非覆写套 onCardMid
    // 回落 onSurfaceVariant 逐位不变）
    tint: androidx.compose.ui.graphics.Color = LocalToolboxSurfaceColors.current.onCardMid,
    /**
     * 昼战图形承载档（design-doc.md §5.3，dayBattleTint）：非 null 且昼战 → icon/dot 坐该语义
     * container 档色（3.0 线）；非昼战不消费。
     */
    graphicCarrier: androidx.compose.ui.graphics.Color? = null,
    /**
     * 昼战文字承载面（design-doc.md §5.3）：非 null 且昼战 → 文字坐该语义 container 面的胶囊
     * （tint 作 contentColor，槽对呈现同 PillLabel）；非昼战不消费——文字裸放现状。
     */
    textCarrier: androidx.compose.ui.graphics.Color? = null,
) {
    val dayBattle = LocalDayBattle.current
    val graphicTint = if (dayBattle && graphicCarrier != null) graphicCarrier else tint
    Row(
        modifier = Modifier
            // hover indication 裁剪与段内小件同档 small（状态栏禁 capsule——
            // AppStatusBarShapeGuardTest 守护）
            .clip(AppShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        when {
            icon != null -> AppSymbolIcon(
                icon = icon,
                contentDescription = null,
                size = Dimens.iconSmall,
                tint = graphicTint,
            )
            dotColor != null -> Box(
                modifier = Modifier
                    .size(Dimens.dotIndicatorSize)
                    .background(
                        if (dayBattle && graphicCarrier != null) graphicCarrier else dotColor,
                        CircleShape,
                    ),
            )
        }
        if (dayBattle && textCarrier != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(min = Dimens.dotIndicatorSize)
                    // 行内语义承载面 = small 圆角档（状态栏禁 capsule——
                    // AppStatusBarShapeGuardTest 守护，段内小件同守）
                    .background(textCarrier, AppShapes.small)
                    .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                modifier = Modifier.widthIn(min = Dimens.dotIndicatorSize),
            )
        }
    }
}

/** 页显示名（活动段文案用）：经 [ToolPageLabels] 单源——注册表/兜底链全撤 */
private fun labelOf(page: ToolPage): String =
    ToolPageLabels.label(page) ?: "页面"
