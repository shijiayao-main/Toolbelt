package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolNavItem
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.host.HotkeyHub
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.ai.AiToolsToggle
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.glass.appFloatShadow
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首启导览卡（宿主侧渲染，工作台家内容层之上）。
 *
 * 判定 = 三空（spaces / usageCounts / runs，[isFirstRunSnapshot] 纯函数）——零持久化 key，
 * 任一路转非空导览卡自动消失，无「不再提示」类新 DataStore 键。数据面 [OnboardingGate]
 * 只读拉取（SQLDelight 同步执行 + Dispatchers.IO），[OnboardingGate.loaded] 前不渲染（防老用户闪现）。
 * 内容：工具速览前 8 项直达 + Ctrl+K 命令面板引导（[HotkeyHub.openCommandPalette] 同源）+
 * 快捷键参考（[HotkeyHub.HOTKEY_TABLE]）。视觉 = sheet 档玻璃浮层 + 宽度上限
 * [Dimens.onboardingCardMaxWidth]（docs/core/theme.md「玻璃材质 token」sheet 归属）。
 */
@Composable
fun OnboardingCard(
    usageCounts: Map<String, Int>,
    onNavigate: (ToolPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gate = remember { OnboardingGate() }
    LaunchedEffect(Unit) { gate.refresh() }

    // loaded 完成前不渲染（防老用户闪现）：三空判定的 DB 两路首帧按 0 参与，
    // 老用户（有空间/run、计数回填前 usageCounts 暂空）会在拉取完成前判真——
    // 不等 loaded 即渲染 = 导览卡对老用户闪现一帧后消失。等拉取完成再判定：
    // 新用户首帧延迟几毫秒（DB 恒空，拉取即回）无感，老用户永不闪现。
    if (!gate.loaded || !gate.isFirstRun(usageCounts)) return

    val navItems = remember { onboardingToolItems() }
    val surfaceColors = LocalToolboxSurfaceColors.current

    // 叠层铺满内容区居中呈现（PageHome ContentViewport 的尺寸即本层边界）；
    // 卡宽封顶 onboardingCardMaxWidth，窗口再宽不摊大
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val cardModifier = Modifier
            .padding(Dimens.space24)
            .widthIn(max = Dimens.onboardingCardMaxWidth)
        // sheet 档浮层（唯一投影档 = 自绘软影 + 发丝定缘——载体本体自带）
        Surface(
            shape = AppShapes.glassSheet,
            color = Color.Transparent,
            modifier = cardModifier
                .appFloatShadow(cornerRadius = Dimens.glassSheetRadius)
                .appGlassSheet(
                    shape = AppShapes.glassSheet,
                ),
        ) {
            OnboardingCardBody(navItems = navItems, onNavigate = onNavigate)
        }
    }
}

/** 导览卡内容体（玻璃/tonal 两载体共用——标题/前置配置/速览网格/提示行） */
@Composable
private fun OnboardingCardBody(
    navItems: List<ToolNavItem>,
    onNavigate: (ToolPage) -> Unit,
) {
    val surfaceColors = LocalToolboxSurfaceColors.current
    // AI 已开启时隐藏 AI 设置引导行（开关真源唯一；开启 = 已配置过，无需再引导）
    val aiEnabled by AiToolsToggle.enabled.collectAsState()
    Column {
        Text(
            text = "开始使用 Toolbelt",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        // 前置配置直达区：两条核心前置不在速览入口里——不引导就要到点按钮撞禁用
        // 文案才知道去哪配（速览网格只收入口，配置页不是入口）
        Text(
            text = "前置配置",
            style = MaterialTheme.typography.labelMedium,
            color = surfaceColors.onSurfaceFaint,
            modifier = Modifier.padding(top = Dimens.space8),
        )
        if (!aiEnabled) {
            OnboardingPromptRow(
                text = "「AI 设置」配好模型服务——AI 研判、会话、知识蒸馏都依赖它",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                onClick = { onNavigate(ToolPage.AiSettings) },
            )
        }
        OnboardingPromptRow(
            text = "「设置」配好 adb 路径——设备观测与 Shell 工具都依赖它",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            onClick = { onNavigate(ToolPage.Setting) },
        )
        OnboardingToolGrid(items = navItems, onNavigate = onNavigate)
        OnboardingPromptRow(
            text = "Ctrl+K 打开命令面板查看全部工具与命令",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            onClick = { HotkeyHub.openCommandPalette?.invoke() },
        )
        Text(
            text = "F1 查看快捷键参考",
            style = MaterialTheme.typography.labelMedium,
            color = surfaceColors.onSurfaceFaint,
        )
    }
}

/**
 * 导览卡工具速览清单：[ToolPageRegistry.navItems] 前 [ONBOARDING_TOOL_LIMIT] 项
 * （注册序——internal 常量 = 测试锚，OnboardingCardTest 钉死「前 8 = 注册序」）。
 */
internal fun onboardingToolItems(): List<ToolNavItem> =
    ToolPageRegistry.navItems().take(ONBOARDING_TOOL_LIMIT)

/** 导览卡速览工具上限（任务书定 8——注册项退役时清单随之自然缩短） */
internal const val ONBOARDING_TOOL_LIMIT = 8

/**
 * 三空判定（从未用过）：spaces 无记录 + 使用计数全零 + runs 无记录。
 *
 * 纯函数 = 测试锚（OnboardingCardTest 直测）：三路皆空才 true；任一路非空
 * 即 false（判定转非空自动消失，零持久化 key 的消失机制即此函数）。
 *
 * @param spacesCount 空间总数（含归档——归档也说明用过）
 * @param usageCounts 页面使用计数 map（AppViewModel.pageUsageCounts）
 * @param runsCount run 总数
 */
internal fun isFirstRunSnapshot(
    spacesCount: Int,
    usageCounts: Map<String, Int>,
    runsCount: Int,
): Boolean = spacesCount == 0 && usageCounts.values.sum() == 0 && runsCount == 0

/**
 * 三空判定的只读数据面（拉取 spaces/runs 两路；usageCounts 经流订阅由调用方
 * 传入——PageHome 已收集，不重复拉）。
 *
 * 🔴 线程纪律（AppHostGuardTest 合规——无自建 scope）：SQLDelight 同步执行，
 * 拉取经 LaunchedEffect + Dispatchers.IO（PaletteEmptyState 同款；主线程直接查库即冻结 UI）。
 * [loaded] = 拉取完成标记——OnboardingCard 在 loaded 前不渲染（老用户的
 * spaces/runs 在首帧窗口按 0 参与判定会误判「三空」，防闪现一等到底）。
 */
internal class OnboardingGate {
    var spacesCount by mutableStateOf(0)
        private set
    var runsCount by mutableStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set

    /** 只读拉取（挂起面——OnboardingCard 组合期 LaunchedEffect 承接） */
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            runCatching {
                val db = Database.database()
                spacesCount = SpaceStore(db).listSpaces(includeArchived = true).size
                runsCount = RunStore(db).listRuns().size
            }
            loaded = true
        }
    }

    /** 三空判定（usageCounts 走流真源，DB 两路走本类拉取面） */
    fun isFirstRun(usageCounts: Map<String, Int>): Boolean =
        isFirstRunSnapshot(spacesCount, usageCounts, runsCount)
}

/** 工具速览流式网格：icon + label 小卡行，点击直达对应工具页 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingToolGrid(
    items: List<ToolNavItem>,
    onNavigate: (ToolPage) -> Unit,
) {
    val surfaceColors = LocalToolboxSurfaceColors.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                modifier = Modifier
                    .clip(AppShapes.small)
                    .clickable { onNavigate(item.page) }
                    .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
            ) {
                AppSymbolIcon(
                    icon = item.icon,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = surfaceColors.onSurfaceMid,
                )
            }
        }
    }
}

/** 大字提示行（Ctrl+K 直达唤起）：主色 bodyLarge，点击回调 */
@Composable
private fun OnboardingPromptRow(
    text: String,
    style: TextStyle,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.space2),
    ) {
        Text(
            text = text,
            style = style,
            color = color,
        )
    }
}
