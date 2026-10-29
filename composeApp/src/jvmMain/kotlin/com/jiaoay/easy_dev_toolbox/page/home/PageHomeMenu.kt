package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxGlassColors
import com.jiaoay.easy_dev_toolbox.host.HotkeyHub
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassRail
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverTooltipBox
import dev.vicart.compose.material.symbols.MaterialSymbols
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/**
 * 侧边 dock（静态图标集）：**六空间** / 命令入口 / 系统区；全量浏览/搜索/直达
 * 改经命令面板（Ctrl+K）承接（应用中心页已退役——
 * 「命令」入口即其职责的收敛形态）。
 *
 * 结构（dock 槽序——**全顶排**，无沉底）：
 * **六空间**（工作台/智能体中心/Shell 工具/飞书桥接/工具/设备观测）→ 分隔线 → 命令入口
 * （Ctrl+K 同源——palette 即全量工具的浏览/搜索/直达面）→ 分隔线 → 系统区
 * （AI 设置 / MCP 服务 / 调试（门控）· 设置）。`.rail` 内零 Spacer、
 * 槽间 gap sp-10 + 垂直 padding sp-20。
 * （调试页槽在 9 槽闭集外——运行时门控渲染，显示时插在 MCP 与设置之间。）
 *
 * - **降级语义**：dock 不再是导航面而是「高频入口 + 持续监控流」——
 *   家=工作台（启动落点）/ 智能体=AgentCenter（聚合页，页内子页签承载会话/
 *   知识蒸馏/语音转写）/ 设备=ShellTools（设备态常驻）/
 *   桥接=LarkBridge（持续监控流）；
 * - **命令入口**：[HotkeyHub.openCommandPalette] 回调槽（main.kt Window 级注入，
 *   与 Ctrl+K 热键同源）；
 * - 视觉契约（.rail 形态）：轨宽 [Dimens.railWidthCompact]
 *   （68dp）贴边实底整列（无悬浮 inset、无圆角胶囊）+ hover 气泡
 *   （配方单源 = widget/interactions/HoverTooltipBox）
 *   + 选中态选中容器绿胶囊实底（r-md 方块深底不取——选中面统一走
 *   selectedContainer 身份槽，与页签栏选中胶囊同族）+ AI 角标 + 活动点。
 */
@Composable
fun PageHomeMenu(
    appViewModel: AppViewModel,
    hiddenPages: Set<ToolPage> = emptySet(),
    aiDisabledPages: Set<ToolPage> = emptySet(),
    navActivity: Map<ToolPage, NavActivity> = emptyMap(),
) {
    val currentPage by appViewModel.currentPage.collectAsState()
    val debugEnabled by appViewModel.debugPageEnabled.collectAsState()
    val surfaceColors = LocalToolboxSurfaceColors.current

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(Dimens.railWidthCompact)
            // .rail：贴边实底整列承板
            // （panel 实底 + 右缘 hairline + **左缘**受光 rim `inset 1px 0 0 0`
            //  + **贴边直角**（无 border-radius）
            // + 无 blur（chrome 实底件不做模糊——blur 属浮层）。
            // 底色 = 结构带槽（侧栏轨常驻实底带——面积构成第二声部位）：覆写套整面
            // 换装、非覆写套走原 panel 档底（零漂移）；描边/rim 形态语言不变。
            .appGlassRail(
                shape = RectangleShape,
                backgroundColor = surfaceColors.structureBand.takeIf { surfaceColors.structureBandActive },
            )
            .padding(vertical = Dimens.space20),
        verticalArrangement = Arrangement.spacedBy(Dimens.space10),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // .rail 首子元素 = 第 1 个 dockslot：轨顶直落图标，
        // 无品牌锚（9 槽闭集无此元素）。

        // ── dock 空间集（空间/文档分离）──
        // 六空间：工作台/智能体中心/Shell 工具/飞书桥接/工具/设备观测——dock 是唯一空间导航，
        // 点击切空间**不产生 tab**（固定 tab 已删除）。
        // AI 置灰设计：AgentCenter 注册项不上送 aiEnabled（聚合入口不置灰、子页
        // 各自表达）。LogWorkbench 不再页级依赖 AI（证据流/确定性
        // 管线是确定性功能）——dock 家图标永不被 AI 开关劫持改道。
        // hiddenPages 过滤保留：注册项被运行时隐藏时图标不渲染（navVisible 契约）。
        DOCK_ENTRIES
            .filter { it.page !in hiddenPages }
            .forEach { entry ->
                val aiOff = entry.page in aiDisabledPages
                DockIconButton(
                    icon = entry.icon,
                    label = entry.label,
                    hint = if (aiOff) "${entry.hint}（AI 已关闭，点击去开启）" else entry.hint,
                    selected = currentPage == entry.page,
                    aiDisabled = aiOff,
                    activity = navActivity[entry.page] ?: NavActivity.Idle,
                    onClick = { appViewModel.setCurrentPage(entry.page) },
                )
            }

        DockDivider()

        // ── 命令入口（Ctrl+K 同源）：带参直填 / HUD 升格 ──
        DockIconButton(
            icon = MaterialSymbols.MANAGE_SEARCH,
            label = "命令",
            hint = "Ctrl+K 唤起命令面板 / Ctrl+Shift+P 仅命令",
            selected = false,
            onClick = { HotkeyHub.openCommandPalette?.invoke() },
        )

        DockDivider()

        // ── 系统区（全顶排——AI 设置/MCP/设置紧随第二条 sep，无 Spacer 沉底）──
        // 系统区三/四槽散置无容器（「恒定枚集 9 槽」钉死）。
        // AI 设置/MCP 常驻直达语义不变。
        DockIconButton(
            // AI 设置改 PSYCHOLOGY（AGENTS 组语义，agent 大脑）
            // ——SMART_TOY 撞标清零，保留给「智能体中心」入口级聚合（design-language.md §4.3 唯一锚）。
            icon = MaterialSymbols.PSYCHOLOGY,
            label = "AI 设置",
            hint = "模型服务 / 开关 / 知识库",
            selected = currentPage == ToolPage.AiSettings,
            onClick = { appViewModel.setCurrentPage(ToolPage.AiSettings) },
        )
        DockIconButton(
            icon = MaterialSymbols.HUB,
            label = "MCP 服务",
            hint = "工具外放给 AI 客户端",
            selected = currentPage == ToolPage.McpExpose,
            onClick = { appViewModel.setCurrentPage(ToolPage.McpExpose) },
        )
        if (debugEnabled) {
            DockIconButton(
                icon = MaterialSymbols.BUG_REPORT,
                label = "Shell 调试",
                hint = "Shell 调试页（开发者选项）",
                selected = currentPage == ToolPage.Debug,
                onClick = { appViewModel.setCurrentPage(ToolPage.Debug) },
            )
        }
        DockIconButton(
            icon = MaterialSymbols.SETTINGS,
            label = "设置",
            hint = "外观 / 配色 / Shell / 日志 / 开发者选项",
            selected = currentPage == ToolPage.Setting,
            onClick = { appViewModel.setCurrentPage(ToolPage.Setting) },
        )
    }
}

/**
 * dock 空间集（空间/文档分离）：纯宿主数据，无 feature 依赖。
 * 空间图标点击切空间不产生 tab（AppViewModel.TOOL_SPACES 单源判定）；系统区
 * 直达页（设置/AI 设置/MCP/调试）是单开系统页（SINGLE_SESSION_PAGES 单源），
 * 切页同空间语义不产生 tab；文档页（报告）才打开成可关 tab。
 * label 与 [ToolPageLabels] 规范名对齐（同名单源——此处的 label 用于 tooltip 与
 * Ctrl+1..5 跳转序，不得与窗口标题/面包屑漂移）。
 * internal = Ctrl+1..5 跳转序同源消费（HotkeyHub wire 点引本清单，单点数据无漂移）。
 */
internal data class DockEntry(
    val page: ToolPage,
    val icon: String,
    val label: String,
    val hint: String,
)

/**
 * 智能体中心 desc 单源：dock tooltip（[DOCK_ENTRIES]）与
 * palette 候选（ToolPageRegistry.navItem.desc）两面同文——曾双源各持字面量漂移
 * （注册项漏「调查空间」），统一模板收敛到单点。
 * internal = 同包（page.home）跨文件直引，零依赖成本。
 */
internal const val AGENT_CENTER_DESC: String = "会话 / 知识蒸馏 / 语音转写 / 调查空间，一页内切换"

/**
 * 工具聚合页 desc 单源：dock tooltip（[DOCK_ENTRIES]）与 palette 候选
 * （ToolPageRegistry.navItem.desc）两面同文——AGENT_CENTER_DESC 同款防漂移配方。
 * desc 是 palette 子串命中面：必须全包含 ToolsPage TOOLS_TAB_LABELS_FULL
 * 全部子签标签，子签清单增改时同步回写（NavDescConsistencyGuardTest 守护）。
 */
internal const val TOOLS_DESC: String =
    "XLog 解密 / Tag 过滤 / 文件分割 / 时间戳 / 解压 / 日志对比 / 正则测试 / JSON 工具 / Base64 提取 / 哈希校验 / 颜色转换 / JWT 解析 / ULID 工具，一页内切换"

/**
 * 设备观测 desc 单源：dock tooltip（[DOCK_ENTRIES]）与 palette 候选
 * （ToolPageRegistry.navItem.desc）两面同文——AGENT_CENTER_DESC 同款防漂移配方。
 * desc 是 palette 子串命中面：须含观测台成员（logcat/投屏/性能/按键/复现编排）
 * 与文件/应用/布局子页关键词，空间成员增改时同步回写
 * （NavDescConsistencyGuardTest 守护）。
 */
internal const val DEVICE_OBS_DESC: String =
    "logcat 实时抓取 / 投屏 / 性能 / 按键 / 复现编排 / 文件 / 应用 / 布局"

internal val DOCK_ENTRIES = listOf(
    DockEntry(ToolPage.LogWorkbench, MaterialSymbols.DESCRIPTION, "工作台", "证据流 / 解密 / 研判——日志调查主阵地"),
    DockEntry(ToolPage.AgentCenter, MaterialSymbols.SMART_TOY, "智能体中心", AGENT_CENTER_DESC),
    DockEntry(ToolPage.ShellTools, MaterialSymbols.TERMINAL, "Shell 工具", "adb / 设备检测 / 指令执行"),
    DockEntry(ToolPage.LarkBridge, MaterialSymbols.FORUM, "飞书桥接", "智能体的飞书出口 / 消息收发 / 驾驶舱"),
    // 工具聚合页常驻直达——desc 全列子签标签保 palette 搜索直达（Ctrl+5）
    DockEntry(ToolPage.Tools, MaterialSymbols.HANDYMAN, "工具", TOOLS_DESC),
    // 设备观测空间（logcat 实时抓取等设备实时观测成员）：dock 末位追加——
    // Ctrl+1..5 空间序不变（肌肉记忆零迁移），本空间 = Ctrl+6
    DockEntry(ToolPage.DeviceObservation, MaterialSymbols.MONITOR, "设备观测", DEVICE_OBS_DESC),
)

/** dock 分隔线：宽度与 tonal 圆底对齐（视觉内收，不横贯整轨） */
@Composable
private fun DockDivider() {
    HorizontalDivider(
        modifier = Modifier
            .padding(vertical = Dimens.space8)
            .width(Dimens.dockItemBackgroundSize),
    )
}

@Composable
private fun DockIconButton(
    icon: String,
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
    aiDisabled: Boolean = false,
    activity: NavActivity = NavActivity.Idle,
) {
    val surfaceColors = LocalToolboxSurfaceColors.current

    // hover 气泡配方单源 = interactions.HoverTooltipBox（延迟/isPersistent/手动驱动）；
    // 本钮特有：Right 定位（dock 贴窗口左缘，气泡右落）+ .tip 深卡域底
    HoverTooltipBox(
        positioning = TooltipAnchorPosition.Right,
        // .tip：surface-highest 底 +
        // 卡域字链 + fs-11(labelSmall)——tooltip 底 = Highest 深卡域（井槽分家后
        // 涂装套基域黑字对深 Highest 破 4.5），文字走 onCard 配套链（非覆写套
        // onCard 回落 onSurface 族逐位不变）
        containerColor = LocalToolboxSurfaceColors.current.surfaceHighest,
        contentColor = LocalToolboxSurfaceColors.current.onCardStrong,
        tooltip = {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (hint.isNotBlank() && hint != label) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalToolboxSurfaceColors.current.onCardMid,
                    )
                }
            }
        },
    ) { interactionSource ->
        // 按压反馈：0.96 压缩 + glassSpring 回弹（theme.md 玻璃动效节既有口径；
        // graphicsLayer lambda 形态不在守护六规则清单内）
        val pressed by interactionSource.collectIsPressedAsState()
        val pressScale by animateFloatAsState(
            targetValue = if (pressed) AppMotion.glassPressScale else 1f,
            animationSpec = AppMotion.glassSpring(),
            label = "dockPressScale",
        )
        Box(modifier = Modifier.graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        }) {
            IconButton(
                onClick = onClick,
                interactionSource = interactionSource,
                modifier = Modifier
                    .size(Dimens.dockItemSize)
                    .hoverable(interactionSource),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (selected) {
                        // 选中态 = 选中容器身份槽实底胶囊（AppShapes.capsule，与页签栏
                        // 选中胶囊同族）+ glass-brd hairline；底色取 selectedContainer
                        //（覆写套恒绿选中面，非覆写套回落 High 档 = 原 surfaceDeep 同值
                        // 逐位不变）——选中块在暗色结构带上自成激活边界
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    color = LocalToolboxSurfaceColors.current.selectedContainer,
                                    shape = AppShapes.capsule,
                                )
                                .border(
                                    width = Dimens.glassStrokeHairline,
                                    color = LocalToolboxGlassColors.current.borderGlass,
                                    shape = AppShapes.capsule,
                                ),
                        )
                    }
                    AppSymbolIcon(
                        icon = icon,
                        contentDescription = label,
                        // .rail .ico 22px（iconMedium 18 是通用档）
                        size = Dimens.dockIconSize,
                        // 带面前景三元：结构带激活套（带面=井槽同值暗域）图标走结构带
                        // 配套字链（对带 ≥4.5 由覆写表接线对背书）；非激活套走卡域链
                        //（井槽分家后基域深字对井面破图形线；非覆写套 onCard 回落 onSurface
                        // 族逐位不变）。选中图标坐选中容器绿胶囊走 onSelectedContainer
                        //（选中面配套字链，与页签栏选中字同规格）不走带链。
                        tint = when {
                            aiDisabled && !surfaceColors.structureBandActive ->
                                surfaceColors.onCardFaint
                            aiDisabled -> surfaceColors.onStructureBand.copy(alpha = AppAlpha.contentDisabled)
                            selected -> surfaceColors.onSelectedContainer
                            surfaceColors.structureBandActive -> surfaceColors.onStructureBand
                            else -> surfaceColors.onCardMid
                        },
                    )
                    if (aiDisabled) {
                        // AI 角标（右上角）：该工具依赖 agent，需开 AI 开关——恒紫 aiAccent
                        // （AI 色独立 token，任何主题下可区分；置灰本体色不动）
                        AppSymbolIcon(
                            icon = MaterialSymbols.AUTO_AWESOME,
                            contentDescription = "AI 应用",
                            modifier = Modifier
                                .align(Alignment.TopEnd),
                            size = Dimens.iconSmall,
                            tint = LocalEasyToolboxColors.current.aiAccent,
                        )
                    }
                    // 活动指示（右下角小圆点）：Running = 后台在跑（primary）；
                    // Attention = 需关注（error）。全走已有 token，零白名单。
                    if (activity != NavActivity.Idle) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(Dimens.dotIndicatorSize)
                                .background(
                                    color = if (activity == NavActivity.Attention) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}
