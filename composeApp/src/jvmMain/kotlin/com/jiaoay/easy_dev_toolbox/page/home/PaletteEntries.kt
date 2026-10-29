package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.data.pathFileName
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.SmartPrefillHook
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolAction
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.smartdetect.SmartDetection
import com.jiaoay.easy_dev_toolbox.core.smartdetect.SmartDetectionKind
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.tools.ToolsConfigStore
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 命令面板候选数据与列表渲染卫星：[PaletteEntry] 候选模型、空态/推荐首页面板
 * （PaletteEmptyHint 族）、检索态分组头与候选行、前缀模式与 frecency 排序。
 * 面板状态机与键盘链在 [CommandPalette]（经同包 internal 可见性消费本文件）。
 */
@Composable
internal fun PaletteEmptyHint(
    prefixMode: PalettePrefix,
    effectiveQuery: String,
    query: String,
    emptyState: PaletteEmptyState,
    usageCounts: Map<String, Int>,
    smartDetections: List<SmartDetection>,
    onNavigate: (ToolPage, ToolPage?) -> Unit,
    onPreselectToolsTab: (Int) -> Unit,
    onSelectSpace: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 匹配失败态（非推荐首页）：精确文案（命令面/页面面各自的搜索域）
    if (!shouldShowRecommendationHome(prefixMode, effectiveQuery)) {
        Text(
            text = when (prefixMode) {
                PalettePrefix.CommandsOnly -> "没有匹配「${effectiveQuery}」的命令"
                PalettePrefix.PagesOnly -> "没有匹配「${effectiveQuery}」的页面"
                PalettePrefix.Mixed -> "没有匹配「${query}」的页面"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = LocalToolboxSurfaceColors.current.onCardMid,
            modifier = Modifier.padding(vertical = Dimens.space12),
        )
        return
    }

    // 推荐首页态：四路推荐（Mixed 空查询）
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = Dimens.paletteListMaxHeight)
            .padding(vertical = Dimens.space4),
    ) {
        // ── 剪贴板识别（置顶第一段；面板唤起时探测一次，置信降序）──
        // 推荐行点击 = 预填载荷入槽 + 预选槽写目标子签 + 跳「工具」聚合页。
        // 预填不自动执行——载荷只送进目标页输入区，回执明示「待确认」
        //（无预填面的识别只跳页）。视觉全复用推荐首页既有行形态与 token，
        // 视觉层待真机终裁。
        if (smartDetections.isNotEmpty()) {
            EmptyHintHeader(icon = MaterialSymbols.CONTENT_PASTE_GO, title = "剪贴板识别")
            smartDetections.forEach { detection ->
                val target = smartDetectionTargetOf(detection.kind)
                EmptyHintRow(
                    icon = smartDetectionIconOf(detection.kind),
                    label = target.label,
                    meta = detection.preview,
                    onClick = {
                        smartPrefillPayloadOf(detection)?.let { payload ->
                            SmartPrefillHook.offer(payload)
                            StatusSignals.show("已预填到「${target.label}」，待确认")
                        }
                        onPreselectToolsTab(target.toolsTab)
                        onNavigate(ToolPage.Tools, null)
                        onDismiss()
                    },
                )
            }
        }
        // ── 最近空间（SpaceStore 倒序前 3；点击 = 切换到该空间后关面板） ──
        // 选中写 AppViewModel.currentSpaceId 真源；SpaceBar 经 syncFromHost 订阅
        // 真源回流高亮（SpaceBar 订阅写侧真源，双向闭环）。
        if (emptyState.recentSpaces.isNotEmpty()) {
            EmptyHintHeader(icon = MaterialSymbols.STADIUM, title = "最近空间")
            emptyState.recentSpaces.forEach { space ->
                EmptyHintRow(
                    icon = MaterialSymbols.FOLDER,
                    label = space.title,
                    meta = "${space.filePaths.size + space.flowIds.size + space.runIds.size} 项成员",
                    onClick = {
                        onSelectSpace(space.id)
                        onDismiss()
                    },
                )
            }
        }
        // ── 常用入口（usageCounts 排序前 3）──
        // usage 信号必须经 [ToolPageLabels.hostOf] 路由到聚合空间（智能体中心/工具）
        // ——直接用信号页会把退役散页顶成动态 tab 复活（同一功能两处存在），
        // 散页永不复活。
        val frequent = remember(usageCounts) {
            // host 归属折叠（分步显式类型——链式推断在 groupBy 处断裂）：
            // 信号页 → 聚合空间（ToolPageLabels.hostOf），同空间计数合并求和
            val hostCounts = mutableMapOf<ToolPage, Int>()
            usageCounts.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .forEach { (name, count) ->
                    val page = runCatching { ToolPage.valueOf(name) }.getOrNull() ?: return@forEach
                    val host = ToolPageLabels.hostOf(page) ?: page
                    hostCounts[host] = (hostCounts[host] ?: 0) + count
                }
            hostCounts.entries
                .sortedByDescending { it.value }
                .take(3)
                .toList()
        }
        if (frequent.isNotEmpty()) {
            EmptyHintHeader(icon = MaterialSymbols.HISTORY, title = "常用入口")
            // 查表按 page 建一次索引：行内全表 firstOrNull 在行数 × 重组频率下重复扫描
            val navByPage = ToolPageRegistry.navItems().associateBy { it.page }
            frequent.forEach { (page, count) ->
                val navItem = navByPage[page]
                EmptyHintRow(
                    icon = navItem?.icon ?: MaterialSymbols.TAB,
                    label = ToolPageLabels.label(page) ?: navItem?.label ?: page.name,
                    meta = "用过 $count 次",
                    onClick = {
                        onNavigate(page, null)
                        onDismiss()
                    },
                )
            }
        }
        // ── 未读产物（RunStore reportPath 非 null 最近 3） ──
        if (emptyState.recentReports.isNotEmpty()) {
            EmptyHintHeader(icon = MaterialSymbols.INSIGHTS, title = "未读产物")
            emptyState.recentReports.forEach { run ->
                EmptyHintRow(
                    // 未读产物行对齐报告候选字形（INSIGHTS）
                    icon = MaterialSymbols.INSIGHTS,
                    label = run.title,
                    meta = "报告: ${run.reportPath?.let { pathFileName(it) } ?: "-"}",
                    onClick = {
                        // 行文案是报告文件名，落点改报告页（报告卡列表语义即此行
                        // 落地页）；行点名具体 run——runId 经聚焦槽投递（报告卡
                        // 直达：展开 + 滚动定位 + 微洗底），落点不变
                        ReportFocusRequest.offer(run.id)
                        onNavigate(ToolPage.Report, null)
                        onDismiss()
                    },
                )
            }
        }
        if (!emptyState.loaded) {
            Text(
                text = "正在读取…",
                style = MaterialTheme.typography.labelSmall,
                color = LocalToolboxSurfaceColors.current.onCardFaint,
            )
        }
    }
}

@Composable
private fun EmptyHintHeader(icon: String, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
        modifier = Modifier.padding(top = Dimens.space4),
    ) {
        AppSymbolIcon(
            icon = icon,
            contentDescription = null,
            size = Dimens.iconSmall,
            tint = LocalToolboxSurfaceColors.current.onCardFaint,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = LocalToolboxSurfaceColors.current.onCardMid,
        )
    }
}

@Composable
private fun EmptyHintRow(
    icon: String,
    label: String,
    meta: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.small)
            .background(
                // EmptyHintRow 常显提示底 = primary 系 hoverWash（0.04 常显）
                color = MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.hoverWash),
                shape = AppShapes.small,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.space12, vertical = Dimens.space8),
    ) {
        AppSymbolIcon(
            icon = icon,
            contentDescription = null,
            size = Dimens.iconSmall,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalToolboxSurfaceColors.current.onCardStrong,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = meta,
            style = MaterialTheme.typography.labelSmall,
            color = LocalToolboxSurfaceColors.current.onCardFaint,
        )
    }
}

/**
 * 命令面板候选条目（sealed 三类）：
 * - [Navigate] 导航项：Enter/点击 = 跳页（aiDisabled = 置灰页 Enter 引导去 AI 设置）；
 * - [Action] 动作项：Enter/点击 = perform（不切页、不关面板）；
 * - [ParamCommand] 带参命令项：Enter/点击 = 进参数直填态 → 执行 → HUD。
 *
 * [key] 为 LazyColumn key（导航项=page 名、动作项=action id、命令项=spec id——
 * 三类 key 空间经前缀天然隔离）。
 */
internal sealed interface PaletteEntry {
    val icon: String
    val label: String
    val desc: String
    val keywords: List<String>
    val key: String

    /** 导航项：跳转到页面 */
    data class Navigate(
        val page: ToolPage,
        override val icon: String,
        override val label: String,
        override val desc: String,
        val aiDisabled: Boolean = false,
        override val keywords: List<String> = emptyList(),
        /**
         * 同页域内变体（「知识库管理」与「知识蒸馏」同落 ToolPage.Distill——
         * key 须区分防 LazyColumn key 撞车）：
         * 空串（默认）= 既有候选零回归；非空 = key 追加变体段 + 点击时经
         * [com.jiaoay.easy_dev_toolbox.core.navigation.KbManagePanelHook] 发载荷。
         */
        val variant: String = "",
    ) : PaletteEntry {
        override val key: String = if (variant.isEmpty()) "nav:${page.name}" else "nav:${page.name}:$variant"
    }

    /** 动作项：不切页直接执行 */
    data class Action(
        val action: ToolAction,
        override val icon: String,
        override val label: String,
        override val desc: String,
        override val keywords: List<String>,
        val isDangerous: Boolean = false,
    ) : PaletteEntry {
        override val key: String = "act:${action.id}"
    }

    data class ParamCommand(
        val spec: ParamCommandSpec,
        override val icon: String = spec.icon,
        override val label: String = spec.label,
        override val desc: String = spec.desc,
        override val keywords: List<String> = spec.keywords,
        val isDangerous: Boolean = spec.isDangerous,
    ) : PaletteEntry {
        override val key: String = "param:${spec.id}"
    }

    /**
     * 未读产物行（检索态第三段）：检索态 Mixed 域**恒显**——不按 query 过滤
     * （s02 列表态查询「解压」时未读产物行零匹配仍显示 = 规格字面；
     * 「系统提醒你有未读报告」语义不依赖查询相关性）。数据 = 推荐首页
     * 同源 [PaletteEmptyState.recentReports]（RunStore reportPath 非 null 最近 3）。
     * 点击/Enter 落报告页，runId 经 [ReportFocusRequest] 聚焦槽直达该卡
     * （展开 + 滚动定位 + 2s 微洗底）。
     */
    data class ReportRun(val run: RunStore.RunSummary) : PaletteEntry {
        override val icon: String = MaterialSymbols.INSIGHTS
        override val label: String = run.title
        override val desc: String = "报告: ${run.reportPath?.let { pathFileName(it) } ?: "-"}"
        override val keywords: List<String> = emptyList()
        override val key: String = "run:${run.id}"
    }
}

/** 「知识库管理」深链候选变体（同落 Distill 页的库面板直达载荷行） */
internal const val VARIANT_KB_MANAGE = "kb-manage"

/** 检索态列表槽（分组头/条目——状态机 entriesWithHeaders 分组与 LazyColumn 渲染共用） */
internal sealed interface ListSlot {
    data class Header(val title: String) : ListSlot
    data class Entry(val entry: PaletteEntry) : ListSlot
}

@Composable
internal fun PaletteSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = LocalToolboxSurfaceColors.current.onCardFaint,
        modifier = Modifier.padding(
            start = Dimens.space12,
            top = Dimens.space6,
        ),
    )
}

/** 候选行：图标 + label/desc 两行 + 选中态背景（tonal，无 elevation）；置灰页带 AI 角标；危险条目 error 色；带参命令行尾深入模式钮 */
@Composable
internal fun PaletteRow(
    entry: PaletteEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onOpenPage: (() -> Unit)? = null,
) {
    val isDangerous = (entry is PaletteEntry.Action && entry.isDangerous) ||
        (entry is PaletteEntry.ParamCommand && entry.isDangerous)
    // 桌面 hover 档：指点扫视定位感
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                // 命中/选中行底 = primary 系 selectedWash（0.08）；
                // 非选中悬停 = primary 系 hoverWash（0.04）——
                // 三态/优先序单源 = selectionHoverWashColor
                color = selectionHoverWashColor(hovered = hovered, selected = selected),
                shape = AppShapes.small,
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.space12, vertical = Dimens.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
    ) {
        androidx.compose.foundation.layout.Box {
            AppSymbolIcon(
                icon = entry.icon,
                contentDescription = null,
                size = Dimens.iconMedium,
                tint = when {
                    isDangerous -> MaterialTheme.colorScheme.error
                    entry is PaletteEntry.Navigate && entry.aiDisabled ->
                        LocalToolboxSurfaceColors.current.onCardFaint
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            if (entry is PaletteEntry.Navigate && entry.aiDisabled) {
                // AI 角标（aiAccent 恒紫——置灰本体色不动，角标全紫可区分）
                AppSymbolIcon(
                    icon = MaterialSymbols.AUTO_AWESOME,
                    contentDescription = "AI 应用",
                    modifier = Modifier
                        .align(Alignment.TopEnd),
                    size = Dimens.iconSmall,
                    tint = LocalEasyToolboxColors.current.aiAccent,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isDangerous -> MaterialTheme.colorScheme.error
                        entry is PaletteEntry.Navigate && entry.aiDisabled ->
                            LocalToolboxSurfaceColors.current.onCardFaint
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (entry is PaletteEntry.Navigate && entry.aiDisabled) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.AUTO_AWESOME,
                        contentDescription = null,
                        size = Dimens.iconSmall,
                        tint = LocalEasyToolboxColors.current.aiAccent,
                    )
                }
                when (entry) {
                    is PaletteEntry.Action -> {
                        // 动作项徽标：与导航项视觉区分（面板内可混排多类，徽标消歧义）
                        Text(
                            text = "动作",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalToolboxSurfaceColors.current.onCardFaint,
                        )
                    }
                    is PaletteEntry.ParamCommand -> {
                        // 带参命令徽标：与动作项的语义区分——动作=即时无参，命令=填参执行
                        Text(
                            text = "命令",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalToolboxSurfaceColors.current.onCardFaint,
                        )
                    }
                    else -> {}
                }
            }
            Text(
                text = entry.desc,
                style = MaterialTheme.typography.labelSmall,
                color = LocalToolboxSurfaceColors.current.onCardFaint,
            )
        }
        // 深入模式钮：带参命令行尾「打开工具页」——openPage 非 null 才渲染
        //（路由经 hostOf 归并落聚合页，文案对齐落地语义）
        if (onOpenPage != null) {
            AppSymbolIcon(
                icon = MaterialSymbols.OPEN_IN_NEW,
                contentDescription = "打开工具页（深入模式）",
                size = Dimens.iconSmall,
                tint = LocalToolboxSurfaceColors.current.onCardFaint,
                modifier = Modifier
                    .size(Dimens.iconMedium)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenPage),
            )
        }
    }
}

/**
 * 面板前缀模式：首字符分流——`>` 仅命令（动作+带参） / `@` 仅页面 / 其余混排。
 */
internal enum class PalettePrefix {
    Mixed,
    CommandsOnly,
    PagesOnly;

    companion object {
        fun of(query: String): PalettePrefix = when {
            query.startsWith(">") -> CommandsOnly
            query.startsWith("@") -> PagesOnly
            else -> Mixed
        }
    }
}

/**
 * frecency 排序：使用计数加权——常用页浮前；同计数/无记录项保持注册序
 * （稳定排序兜底）；动作项与带参命令不参与计数排序（恒排全部导航项之后，保留
 * 注册序供 feature 自控位置——命令级计数面暂缺，与动作项同档）。
 */
internal fun sortEntriesByFrecency(
    entries: List<PaletteEntry>,
    usageCounts: Map<String, Int>,
): List<PaletteEntry> {
    // 注册序索引（同计数项的稳定兜底；map 免 O(n²) indexOf）
    val originalIndex = HashMap<PaletteEntry, Int>(entries.size * 2)
    entries.forEachIndexed { i, entry -> originalIndex[entry] = i }
    return entries.sortedWith(
        compareByDescending<PaletteEntry> { entry ->
            when (entry) {
                is PaletteEntry.Navigate -> usageCounts[entry.page.name] ?: 0
                is PaletteEntry.Action -> -1 // 动作项恒排导航项之后（含 0 计数页）
                is PaletteEntry.ParamCommand -> -1 // 带参命令与动作同档（命令级计数后置）
                is PaletteEntry.ReportRun -> -1 // 未读产物恒排导航项之后（保底段固定尾位）
            }
        }.thenBy { originalIndex[it] ?: 0 },
    )
}

/**
 * 剪贴板识别推荐行落点（kind → 工具聚合页子签锚单源映射）：
 * 点击 = [onPreselectToolsTab] 写宿主预选槽（AppViewModel.pendingToolsTab——
 * ToolsPage 既有双 effect 配方消费，与 palette 深入模式同一槽）+ 跳「工具」
 * 聚合页。推荐行不走 deepLink 第二参通道——预选统一单通道，避免与深入模式
 * 的 hostIndexOf 换算并存两套落签路径。
 */
internal data class SmartDetectionTarget(
    val label: String,
    val toolsTab: Int,
)

/** 推荐行 label 与子签锚（label 对齐工具聚合页九签全名表） */
internal fun smartDetectionTargetOf(kind: SmartDetectionKind): SmartDetectionTarget = when (kind) {
    SmartDetectionKind.EPOCH_TIMESTAMP -> SmartDetectionTarget("时间戳", ToolsConfigStore.TAB_TIMESTAMP)
    SmartDetectionKind.XLOG_PATH -> SmartDetectionTarget("XLog 解密", ToolsConfigStore.TAB_XLOG_DECODE)
    SmartDetectionKind.JSON_OBJECT -> SmartDetectionTarget("JSON 工具", ToolsConfigStore.TAB_JSON_TOOL)
    SmartDetectionKind.BASE64_TEXT -> SmartDetectionTarget("Base64 提取", ToolsConfigStore.TAB_BASE64_EXTRACT)
    SmartDetectionKind.GZIP_DATA -> SmartDetectionTarget("解压", ToolsConfigStore.TAB_ARCHIVE)
    SmartDetectionKind.IMAGE_BASE64 -> SmartDetectionTarget("Base64 提取（图片）", ToolsConfigStore.TAB_BASE64_EXTRACT)
}

/**
 * 推荐行 → 预填载荷映射（kind 单源）：null = 该类识别只推荐跳页、无预填面
 * （gzip 承载内容字节而非路径，解压页输入面是文件拖入——唯一无预填面）。
 * JSON/base64 文本/图片 base64 的剪贴板原文即目标页合法输入（粘贴区形态），
 * 预填进输入框/粘贴区。prefillText 缺位（探测器载荷漂移）时静默降级为纯跳页，
 * 不投递空载荷。
 */
internal fun smartPrefillPayloadOf(detection: SmartDetection): SmartPrefillHook.Payload? =
    when (detection.kind) {
        SmartDetectionKind.EPOCH_TIMESTAMP ->
            detection.prefillText?.let { SmartPrefillHook.Payload.EpochValue(it) }
        SmartDetectionKind.XLOG_PATH ->
            detection.prefillText?.let { SmartPrefillHook.Payload.XLogFilePath(it) }
        SmartDetectionKind.JSON_OBJECT ->
            detection.prefillText?.let { SmartPrefillHook.Payload.JsonText(it) }
        SmartDetectionKind.BASE64_TEXT ->
            detection.prefillText?.let { SmartPrefillHook.Payload.Base64Text(it) }
        SmartDetectionKind.IMAGE_BASE64 ->
            detection.prefillText?.let { SmartPrefillHook.Payload.Base64Text(it) }
        SmartDetectionKind.GZIP_DATA -> null
    }

/** 推荐行图标（kind 语义锚：时间=SCHEDULE / 解密=ENCRYPTED / JSON=DATA_OBJECT / Base64=CODE / GZip=FOLDER_ZIP / 图片 base64=IMAGE） */
internal fun smartDetectionIconOf(kind: SmartDetectionKind): String = when (kind) {
    SmartDetectionKind.EPOCH_TIMESTAMP -> MaterialSymbols.SCHEDULE
    SmartDetectionKind.XLOG_PATH -> MaterialSymbols.ENCRYPTED
    SmartDetectionKind.JSON_OBJECT -> MaterialSymbols.DATA_OBJECT
    SmartDetectionKind.BASE64_TEXT -> MaterialSymbols.CODE
    SmartDetectionKind.GZIP_DATA -> MaterialSymbols.FOLDER_ZIP
    SmartDetectionKind.IMAGE_BASE64 -> MaterialSymbols.IMAGE
}
