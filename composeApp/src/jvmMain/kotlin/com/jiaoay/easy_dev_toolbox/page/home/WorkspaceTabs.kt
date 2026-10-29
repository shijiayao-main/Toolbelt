package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassChrome
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 主区 tab 容器（空间/文档分离模型）。
 *
 * ## 新模型：tab 条 = 纯「打开的文档」
 * - **dock 是空间，tab 是你打开的东西**：空间页（工作台/智能体中心/Shell 工具/
 *   飞书桥接/工具）切换不产生 tab；单开系统页（设置/AI 设置/MCP/Shell 调试，
 *   AppViewModel.SINGLE_SESSION_PAGES 单源）切页同空间语义、永不进 openTabs；
 *   文档页（报告/深入模式五件套）打开时成为可关闭 tab；
 * - **零固定栏**：「证据流/报告/桥接」三固定 base 投影已删除——其中
 *   2/3 与 dock 完全重复（证据流=家、桥接），双导航系统是「导航过剩」反模式；
 * - **Auto-hide**：openTabs 空集时 tab 条整体隐藏（expand/shrink + fade 出入场），
 *   回收 ~58px 纵向——消除「标题栏+tab 条+页内大标题」三层横向框压窄内容区；
 * - tab 条只投影文档集，与 dock 的空间导航天然不重复。
 *
 * ## 标签单源
 * tab label 经 [ToolPageLabels.label] 单源取（窗口标题/面包屑/dock tooltip 同源），
 * 不再有本文件私有的 when 兜底链。图标经注册表 navItem 查表，落空走 TAB 兜底
 * （navItem=null 的宿主页——Setting/Debug 注册项不进导航分组是既有契约）。
 *
 * ## 拆分（轻量版全屏铺）
 * 本函数为独立悬浮胶囊（PageHome 顶层叠层位）；`tabsProjection` 纯函数与 `WorkspaceTabsTest` 测试锚对齐新模型。
 */
@Composable
fun WorkspaceTabBar(
    appViewModel: AppViewModel,
    aiDisabledPages: Set<ToolPage>,
    navActivity: Map<ToolPage, NavActivity>,
    modifier: Modifier = Modifier,
) {
    val currentPage by appViewModel.currentPage.collectAsState()
    val openTabs by appViewModel.openTabs.collectAsState()
    val tabs = tabsProjection(openTabs)
    val surfaceColors = LocalToolboxSurfaceColors.current

    // Auto-hide：无文档时整条隐藏（回收纵向——空集是常态而非异常，动画用
    // expand/shrink 保持布局平滑过渡，不被「主区切页动画退役」裁定约束
    // ——本条是 tab 条自身出入场，非页内容切换）
    AnimatedVisibility(
        visible = tabs.isNotEmpty(),
        enter = expandVertically(animationSpec = AppMotion.standard()) + fadeIn(animationSpec = AppMotion.fast()),
        exit = shrinkVertically(animationSpec = AppMotion.standard()) + fadeOut(animationSpec = AppMotion.fast()),
        modifier = modifier,
    ) {
        // 悬浮胶囊本体（PageHome 叠层位负责 inset——此处只画胶囊 + 行内布局）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                // tab 条是 chrome 常驻件——恒实底（opaqueSlotChrome）；底色 = 结构带槽
                //（页签栏常驻实底带——面积构成第二声部位）：覆写套整面换装、非覆写套走
                // 原 chrome 档底（零漂移）；定缘/rim 形态语言不变
                .appGlassChrome(
                    shape = AppShapes.glassBar,
                    backgroundColor = surfaceColors.structureBand.takeIf { surfaceColors.structureBandActive },
                )
                .height(Dimens.tabBarHeight)
                // 多 tab 同开总宽超窗时横向滚动兜底——普通 Row 会把右侧 tab 裁出
                // 视口且无法到达（tab 可同开多个，数量不可预期）
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Dimens.space4),
        ) {
            tabs.forEach { tab ->
                WorkbenchTabRow(
                    label = tab.label,
                    icon = tab.icon,
                    selected = currentPage == tab.page,
                    aiDisabled = tab.page in aiDisabledPages,
                    activity = navActivity[tab.page] ?: NavActivity.Idle,
                    onClick = { appViewModel.setCurrentPage(tab.page) },
                    onClose = { appViewModel.closeTab(tab.page) },
                )
            }
        }
    }
}

/**
 * 文档 tab 投影：[openTabs] 全量直投影——**全部可关、零固定**。
 * internal = 测试锚直测（WorkspaceTabsTest 钉死新断言：投影 = openTabs 直映、
 * 无固定项、label 单源、无重复页）。
 */
internal data class WorkbenchTab(
    val page: ToolPage,
    val label: String,
    val icon: String,
)

internal fun tabsProjection(openTabs: List<ToolPage>): List<WorkbenchTab> {
    // openTabs 直投影。空间页剔除（异常输入防御：VM 分流语义保证空间页不进集，
    // 但深链/恢复路径绕过分流时此处兜底——dock 是唯一空间导航，tab 只承载文档）。
    // 查表按 page 建一次索引：逐 tab 全表 firstOrNull 在 tab 数 × 重组频率下重复扫描
    val navByPage = ToolPageRegistry.navItems().associateBy { it.page }
    return openTabs
        .filter { it !in AppViewModel.TOOL_SPACES }
        .distinct()
        .map { page ->
            val navItem = navByPage[page]
            // label 单源：ToolPageLabels（窗口标题/面包屑/dock tooltip 同源）。
            // 落空（僵尸枚举）不裸露英文枚举名——兜底空串由渲染层 maxLines 截断。
            val label = ToolPageLabels.label(page) ?: navItem?.label ?: ""
            // navItem=null 的宿主页（设置/调试/报告）按页
            // 语义分派图标——TAB 剪影一刀切致「同页三入口两图标」（dock 齿轮 vs
            // tab 剪影）。dock 系统区图标为准：设置=SETTINGS、调试=BUG_REPORT、报告=INSIGHTS
            //（报告对齐 palette 候选字形——DESCRIPTION 工作台家专属）。
            val icon = navItem?.icon ?: hostPageIcon(page)
            WorkbenchTab(page, label, icon)
        }
}

/** 宿主页兜底图标（navItem=null 的页——与 dock 系统区同图标语义，单源对照 PageHomeMenu） */
internal fun hostPageIcon(page: ToolPage): String = when (page) {
    ToolPage.Setting -> MaterialSymbols.SETTINGS
    ToolPage.Debug -> MaterialSymbols.BUG_REPORT
    // AI 设置三面同字形 PSYCHOLOGY（dock/系统区/navItem 单源对照）
    ToolPage.AiSettings -> MaterialSymbols.PSYCHOLOGY
    ToolPage.McpExpose -> MaterialSymbols.HUB
    // 报告 tab 兜底对齐 palette 候选（INSIGHTS）——DESCRIPTION
    // else 兜底只留给僵尸枚举（五件套深入模式 navItem 查表命中，不落本分支）
    ToolPage.Report -> MaterialSymbols.INSIGHTS
    else -> MaterialSymbols.DESCRIPTION
}

@Composable
private fun WorkbenchTabRow(
    label: String,
    icon: String,
    selected: Boolean,
    aiDisabled: Boolean,
    activity: NavActivity,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val surfaceColors = LocalToolboxSurfaceColors.current
    // hover 档：非选中悬停微洗底
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            // tab 选中底 = surfaceColors.selectedContainer 实底胶囊（选中容器身份槽，
            // 与 SpaceBar 选中行/ThemeCard 选中卡同源；hover 洗底保持 primary 系）；
            // 选中底胶囊化（shape = AppShapes.capsule）——
            // 与 dock 选中胶囊/SegmentedControl 选中胶囊同族，矩形
            // 满宽底回潮即红（WorkspaceTabsTest 源码锚守护）。
            // 洗底三态/优先序单源 = selectionHoverWashColor（选中实底档经 selectedColor 传入）
            .background(
                color = selectionHoverWashColor(
                    hovered = hovered,
                    selected = selected,
                    selectedColor = surfaceColors.selectedContainer,
                ),
                shape = AppShapes.capsule,
            )
            .padding(horizontal = Dimens.space12, vertical = Dimens.space4),
    ) {
        AppSymbolIcon(
            icon = icon,
            contentDescription = label,
            size = Dimens.iconSmall,
            // 带面前景三元：结构带激活套未选中图标走带面配套字链（带面前景统一
            // onStructureBand，对带 ≥4.5 由覆写表接线对背书）；非激活套未选中图标 = primary（原样）
            tint = when {
                aiDisabled && surfaceColors.structureBandActive -> surfaceColors.onStructureBand
                aiDisabled -> surfaceColors.onCardFaint
                selected -> surfaceColors.onSelectedContainer
                surfaceColors.structureBandActive -> surfaceColors.onStructureBand
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = label,
            // tab 文字 12sp SemiBold（DesktopTypography.tabLabel 档）——.tab fs-12/600
            // 归一恒定（选中/未选中两态同字形）；行高 16 不破 tabBarHeight 34dp 胶囊。
            // 选中字/图标走 onSelectedContainer（选中容器面上的配套字链——选中底已换
            // selectedContainer 实底，primary 字对实底无配套保证，绿面上更会落两态异值坑）。
            // 未选中字走 onCardMid（卡域字链）——tab 条是 chrome 带取源井槽（Lowest），
            // 井槽分家后基域深字对井面破 4.5（非覆写套 onCard 回落 onSurface 族逐位不变）；
            // 结构带激活套未选中字走带面配套字链（带面前景统一 onStructureBand）。
            style = DesktopTypography.tabLabel,
            color = if (selected) {
                surfaceColors.onSelectedContainer
            } else if (surfaceColors.structureBandActive) {
                surfaceColors.onStructureBand
            } else {
                surfaceColors.onCardMid
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Dimens.space6),
        )
        // AI 置灰角标（禁用不隐藏——点击仍可进入引导开开关；恒紫 aiAccent）
        if (aiDisabled) {
            AppSymbolIcon(
                icon = MaterialSymbols.AUTO_AWESOME,
                contentDescription = "AI 未配置",
                size = Dimens.iconSmall,
                tint = LocalEasyToolboxColors.current.aiAccent,
            )
        }
        // 活动点（Running 态小圆点——用户切走 tab 后的感知通道）
        if (activity == NavActivity.Running || activity == NavActivity.Attention) {
            Box(
                modifier = Modifier
                    .padding(start = Dimens.space4)
                    .size(Dimens.dotIndicatorSize)
                    .background(
                        color = if (activity == NavActivity.Attention) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
            )
        }
        // 全部 tab 可关（文档 tab 恒显 ✕）。
        // ✕ 尺寸自查（口径随组 A MICS 32 修正）：视觉 16dp 钮获得 32dp 命中区，
        // 小于 M3 默认 48dp 不撑爆 tabBarHeight 34dp 行内布局；与 SpaceBar 归档钮
        // 等列表行内动作图标同档（iconSmall 标准档）。
        androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.size(Dimens.iconSmall)) {
            AppSymbolIcon(
                icon = MaterialSymbols.CLOSE,
                contentDescription = "关闭 $label",
                size = Dimens.iconSmall,
                // 关闭钮同带面前景三元（结构带激活套走带面配套字链 onStructureBand）
                tint = if (surfaceColors.structureBandActive) {
                    surfaceColors.onStructureBand
                } else {
                    surfaceColors.onCardFaint
                },
            )
        }
    }
}
