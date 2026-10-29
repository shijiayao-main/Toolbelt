package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.CardHeader
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SearchField
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.BridgeFields
import com.jiaoay.lark.bridge.config.ConfigGroup
import com.jiaoay.lark.bridge.config.FieldTier
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * 配置文档编辑器：由 [BridgeFields.ALL] **数据驱动**渲染（计数以 BridgeFieldsTest 为唯一真源）。
 * 结构形态（勿回退为「连接与安全直出 + 单卡折叠」形态）：
 *   填全（draft 提交）后 quickSetup 翻 false，快速上手卡消失、五分区出现——零状态迁移、零滚动
 *   跳转（无动画过渡，条件渲染即形态切换）；BASIC 5 件在快速上手卡与五分区「连接与准入」两处
 *   重复出现是预期——同源 draft 双向同步。弱入口展开态 [advancedExpanded] 会话级（点开后本会话
 *   内门控开着也保留目录）。
 * - **深链聚焦**：驾驶舱向导「去填写」（CockpitPanel QuickStartCard 第 1 步）→ [LarkBridgePage]
 *   `configFocusTicket++` 并切配置页签 → 本编辑器 [focusTicket] → [QuickSetupGate]
 *   `.firstEmptyFieldId` 定位目标字段 → [focusRequesters] 票据表挂到 BASIC 行的输入框
 *   （snapshotFlow 等 requester 就绪，消解跨页签 AnimatedContent 组合时序；runCatching 吞
 *   not-attached 窗口，同 OperationBar chatIdFocusTicket 配方）。targetId 为 null（两组全完成，
 *   门控已关）或 requester 永不就绪时协程挂住无害——[LaunchedEffect] key（focusTicket）变化
 *   即取消重启，不泄漏。
 */
@Composable
fun ConfigEditor(
    config: BridgeConfig,
    issues: Map<String, String>,
    onCommit: (BridgeField<Any?>, Any?) -> Unit,
    modifier: Modifier = Modifier,
    /** 驾驶舱向导深链聚焦票据：>0 时聚焦首个空基础字段，同 chatIdFocusTicket 模式。 */
    focusTicket: Int = 0,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // chip 与搜索框共用同一查询串（点 chip = 写入保留字，再点 = 清空退出过滤）
    val filterState = remember(config, searchQuery) { ConfigFilter.parse(searchQuery, config) }
    val modified = remember(config) { P5FieldGroups.modifiedIds(config) }

    // 新用户门控（任一徽章组未完成 = 快速上手双形态开）。remember(config) 随 draft
    // 提交重算——填全即翻 false，原地变形为五分区目录（纯数据驱动，见类头）。
    // 跳过引导短路——「有意留空」是合法形态（dryRun/静态回复用法，
    // VM 工作区警示不阻断），用户点「跳过引导」后本会话内门控整体关闭（卡与弱入口都消失、
    // 六分区直出）；rememberSaveable 会话级与 advancedExpanded 同语义，不落盘重启恢复。
    var quickSetupDismissed by rememberSaveable { mutableStateOf(false) }
    val quickSetup =
        !quickSetupDismissed && remember(config) { QuickSetupGate.showQuickSetup(config) }
    // 弱入口展开态（会话级）：新用户点「高级配置（N 项）→」后本会话内保留五分区目录
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    // 分区受控展开 override（title to expanded）——搜索结果行
    // 「进入分区 →」跳转时置（清搜索 + 展开目标分区）；用户手动收起该分区时清空交还
    // 内部态（remember 非会话级——跳转是一次性动作，重启后无需恢复跳转语义）。
    var zoneExpandOverride by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    // 深链聚焦票据表（fieldId → FocusRequester，FieldRow 渲染 BASIC 行时 getOrPut 注册）
    val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    LaunchedEffect(focusTicket) {
        if (focusTicket > 0) {
            val targetId = QuickSetupGate.firstEmptyFieldId(config)
            if (targetId != null) {
                snapshotFlow { focusRequesters[targetId] }
                    .filterNotNull()
                    .first()
                    .let { runCatching { it.requestFocus() } }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        // 配置页顶层卡距走卡距档 space14；
        // 工具条 + 快速上手卡 + 五分区之间的页级卡距，分区内部小节距不动
        verticalArrangement = Arrangement.spacedBy(Dimens.space14),
    ) {
        ConfigToolbar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            modifiedCount = modified.size,
            restartCount = P5FieldGroups.RESTART_IDS.size,
            filterActive = filterState != null,
        )
        if (filterState == null) {
            // 双形态门控：新用户（两徽章组有未完成）顶部直出快速上手卡；
            // 「原地变形」= quickSetup 随 draft 提交翻 false 后此分支整体消失、五分区出现，
            // 零状态迁移零滚动跳转（条件渲染，无过渡动画——预期零新动效）
            if (quickSetup) {
                QuickSetupCard(
                    config = config,
                    issues = issues,
                    onCommit = onCommit,
                    modified = modified,
                    focusRequesters = focusRequesters,
                    // 跳过引导（会话级 dismissed）——卡与弱入口一同消失、六分区直出
                    onSkip = { quickSetupDismissed = true },
                )
                if (!advancedExpanded) {
                    QuickSetupAdvancedEntry(onExpand = { advancedExpanded = true })
                }
            }
            // 五分区目录：quickSetup 且未点弱入口时不渲染（双形态收敛）；advancedExpanded
            // = true（本会话已点开）或 quickSetup == false（老用户/已填全）时正常渲染。
            // BASIC 5 件在快速上手卡与本目录「连接与准入」两处重复出现是预期——同源 draft。
            if (!quickSetup || advancedExpanded) {
                P5FieldGroups.ZONES.forEach { zone ->
                    val zoneFields = P5FieldGroups.zoneFields(zone)
                    if (zoneFields.isEmpty()) return@forEach
                    val zoneIssueCount = zoneFields.count { it.id in issues }
                    val zoneModifiedCount = zoneFields.count { it.id in modified }
                    SectionCard(
                        title = zone.title,
                        collapsible = true,
                        // issues 非空的分区首进页面时自动展开（红徽标在，
                        // 用户直奔错误落点）。语义限制：initialExpanded 是
                        // rememberSaveable 初值，只在该 SectionCard 首次组合时生效——页面首进时
                        // issues 非空 ⇒ 展开 ✓；用户编辑后才出现的新 issue 不触发展开已组合的
                        // 收起分区（可点标题展开，红徽标已在）——完整受控展开走 zoneExpandOverride。
                        initialExpanded = zoneIssueCount > 0,
                        // 搜索结果「进入分区 →」跳转的受控展开——override 命中本分区时
                        // 强制展开；用户手动收起（onExpandedChange false）时清 override 交还
                        // 内部态（防「收不掉」死锁）。未命中分区的 override 传 null = 内部自持。
                        expandedOverride =
                            if (zoneExpandOverride?.first == zone.title) zoneExpandOverride?.second else null,
                        onExpandedChange = { expanded ->
                            if (!expanded && zoneExpandOverride?.first == zone.title) {
                                zoneExpandOverride = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        headerTrailing = {
                            ZoneBadges(
                                total = zoneFields.size,
                                modified = zoneModifiedCount,
                                errors = zoneIssueCount,
                            )
                        },
                    ) {
                        // 分区内部小节头：zone.sectionTitles 命中即覆盖（跨 ConfigGroup 分区的
                        // 自定义小节——「任务行为与限额」四小节），未命中回落 ConfigGroup.title；
                        // 同一小节标题的字段聚为一节（保持 fieldIds 书写序），节序 = 首现序
                        val sectionHeads = zoneFields.map { field ->
                            zone.sectionTitles[field.id] ?: field.group.title
                        }
                        val sectionOrder = sectionHeads.distinct()
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space12),
                        ) {
                            sectionOrder.forEach { sectionTitle ->
                                val fields = zoneFields.filterIndexed { index, _ -> sectionHeads[index] == sectionTitle }
                                if (fields.isEmpty()) return@forEach
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                                ) {
                                    CardHeader(title = sectionTitle, style = MaterialTheme.typography.titleSmall)
                                    FieldColumn(fields, config, issues, modified, onCommit)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 过滤模式：结果卡（命中字段平铺直接可编辑；退出 = 清空搜索框）
            FilterResultCard(
                state = filterState,
                config = config,
                issues = issues,
                modified = modified,
                query = searchQuery,
                onClear = { searchQuery = "" },
                onCommit = onCommit,
                // 「进入分区 →」= 清搜索退出过滤 + 受控展开目标分区
                // （zoneExpandOverride 按 title 匹配——ConfigFilter.Match.zoneTitle 反查现成）
                onEnterZone = { zoneTitle ->
                    searchQuery = ""
                    zoneExpandOverride = zoneTitle to true
                },
            )
        }
    }
}

// backend 值域收窄回纯 CLI 五值后 customTemplate/allowedTools 恒可见——它们是**重任务委托
// 目标的配置**，App 形态未命中消息恒由本地 AI 调度接手，本就该恒可见可调（Koog 的 call_cli_agent 会按它们渲染 CLI）。
// 勿回加按 backend 条件隐藏的过滤逻辑。

/**
 * L0 工具条：搜索框 + @modified/@restart 两 chip + 已修改徽标。
 * chip 点击 = 把保留字写进搜索框（复用同一过滤态管线）；再次点击 = 清空（退出过滤）。
 * // OPEN(工具条): 浮层定位/滚动定位高亮——当前落地形态是过滤模式。
 */
@Composable
private fun ConfigToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifiedCount: Int,
    restartCount: Int,
    filterActive: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        // CommitTextField 表单形态走 SearchField（label 降
        // placeholder）；onValueChange 直连 = 每键上抛（通道语义等价，@modified/@restart
        // 保留字 chip 点击写入 query 的过滤态管线零变化）
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "搜索 ${BridgeFields.ALL.size} 项配置，或输入 @modified / @restart",
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = query.trim().lowercase() == ConfigFilter.MENTION_MODIFIED,
            onClick = {
                onQueryChange(
                    if (query.trim().lowercase() == ConfigFilter.MENTION_MODIFIED) "" else ConfigFilter.MENTION_MODIFIED,
                )
            },
                        colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary
                    .copy(alpha = AppAlpha.hotWash),
                selectedLabelColor = MaterialTheme.colorScheme.primary,
            ),
shape = AppShapes.capsule, // 轻标签胶囊档（M3 默认 8dp 角显式归一）
            label = { Text("@modified · $modifiedCount", style = MaterialTheme.typography.labelSmall) },
        )
        FilterChip(
            selected = query.trim().lowercase() == ConfigFilter.MENTION_RESTART,
            onClick = {
                onQueryChange(
                    if (query.trim().lowercase() == ConfigFilter.MENTION_RESTART) "" else ConfigFilter.MENTION_RESTART,
                )
            },
                        colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary
                    .copy(alpha = AppAlpha.hotWash),
                selectedLabelColor = MaterialTheme.colorScheme.primary,
            ),
shape = AppShapes.capsule, // 轻标签胶囊档（M3 默认 8dp 角显式归一）
            label = { Text("@restart · $restartCount", style = MaterialTheme.typography.labelSmall) },
        )
    }
    // 已修改徽标行：过滤态下与结果卡重复，仅无过滤时显示（「已改 N」徽标的页级形态）
    if (!filterActive && modifiedCount > 0) {
        PillLabel(
            text = "已改 $modifiedCount 项（点 @modified 只看改过）",
            // primary 系徽标须 primary×selectedWash 直出
            // （bgSegTrack 是分段控件轨道 token，不得挪作 pill 洗底）
            container = MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.selectedWash),
            contentColor = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 快速上手卡：新用户门控双形态的主形态——恒展开 SectionCard「快速上手」，
 * 顶部完成徽章行（两 pill 两态）+ 恰 BASIC 5 件平铺（声明序即渲染序，数据驱动零硬编码）。
 *
 * - 徽章行 = [QuickSetupGate.badgeGroups]（顺序即渲染序）：ready = CHECK_CIRCLE + success 容器
 *   （LocalEasyToolboxColors.success/successContainer，参照重启 pill 的容器色组合）；未完成 =
 *   RADIO_BUTTON_UNCHECKED + onSurfaceVariant 灰态（AppAlpha.bgSubtle 底）。pill 配方复用
 *   [PillLabel]（icon 参数已支持；胶囊徽标共享件）。
 * - 「其余 N 项按场景收在下方」N 动态算（ALL − BASIC），不硬编码 58。
 * - BASIC 5 件经 [FieldRow] 渲染（与五分区同源渲染器），每行注册 focusRequester 供深链聚焦
 *   （getOrPut 在组合内调用，remember map 记住——跨页签 AnimatedContent 重组合也保活）。
 * - 「原地变形」：填全后 [QuickSetupGate.showQuickSetup] 翻 false，本卡整体消失（调用方条件渲染），
 *   零状态迁移零滚动跳转。
 */
@Composable
private fun QuickSetupCard(
    config: BridgeConfig,
    issues: Map<String, String>,
    onCommit: (BridgeField<Any?>, Any?) -> Unit,
    modified: Set<String>,
    focusRequesters: MutableMap<String, FocusRequester>,
    /** 跳过引导——「有意留空」合法形态的常驻噪音出口（会话级，不落盘）。 */
    onSkip: () -> Unit,
) {
    val colors = LocalEasyToolboxColors.current
    val basicFields = BridgeFields.ALL.filter { it.tier == FieldTier.BASIC }
    val advancedCount = BridgeFields.ALL.count { it.tier == FieldTier.ADVANCED }

    SectionCard(
        title = "快速上手",
        collapsible = false,
        modifier = Modifier.fillMaxWidth(),
        // 「跳过引导」挂卡头行尾（chead 行尾位；功能与会话记忆不变）
        headerTrailing = {
            TextButton(onClick = onSkip) {
                Text("跳过引导", style = MaterialTheme.typography.labelSmall)
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            // 完成徽章行（屏②「✓凭据 ✓工作区」）：两 pill 两态 + 一句提示
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                QuickSetupGate.badgeGroups(config).forEach { group ->
                    PillLabel(
                        text = group.label,
                        container = if (group.ready) colors.successContainer else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AppAlpha.bgSubtle)
                        },
                        contentColor = if (group.ready) colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = if (group.ready) MaterialSymbols.CHECK_CIRCLE else MaterialSymbols.RADIO_BUTTON_UNCHECKED,
                    )
                }
                Text(
                    text = "其余 $advancedCount 项按场景收在下方",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 恰 BASIC 5 件平铺（声明序即渲染序，数据驱动零硬编码）
            basicFields.forEach { field ->
                @Suppress("UNCHECKED_CAST")
                val typed = field as BridgeField<Any?>
                FieldRow(
                    field = typed,
                    config = config,
                    issue = issues[typed.id],
                    modified = typed.id in modified,
                    onCommit = { value -> onCommit(typed, value) },
                    focusRequester = focusRequesters.getOrPut(typed.id) { FocusRequester() },
                )
            }
        }
    }
}

/**
 * 弱入口行（屏②「高级配置（58 项）→」）：卡片外、快速上手卡之后，点开本会话保留五分区目录。
 * 样式仿本文件「退出过滤」行（clickable + interactionSource + indication = null + padding）。
 */
@Composable
private fun QuickSetupAdvancedEntry(onExpand: () -> Unit) {
    // .weaklink：fs-13(bodyMedium) +
    // 整行居中 + padding 10 + margin-top 8
    Text(
        text = "高级配置（${BridgeFields.ALL.count { it.tier == FieldTier.ADVANCED }} 项）→",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExpand,
            )
            .padding(all = Dimens.space10),
    )
}

/** 分区标题行徽标组：「N 项 · 已改 M」+ 错误 K 红徽标（issues 非空才显示）。 */
@Composable
private fun ZoneBadges(total: Int, modified: Int, errors: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        Text(
            text = "$total 项" + if (modified > 0) " · 已改 $modified" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (errors > 0) {
            PillLabel(
                text = "错误 $errors",
                container = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error,
                icon = MaterialSymbols.ERROR,
            )
        }
    }
}

@Composable
private fun FilterResultCard(
    state: ConfigFilter.FilterState,
    config: BridgeConfig,
    issues: Map<String, String>,
    modified: Set<String>,
    query: String,
    onClear: () -> Unit,
    onCommit: (BridgeField<Any?>, Any?) -> Unit,
    /** 「进入分区 →」跳转——清搜索 + 展开该分区；null = 不显示钮。 */
    onEnterZone: ((String) -> Unit)? = null,
) {
    val (title, entries) = when (state) {
        is ConfigFilter.FilterState.Modified ->
            "@modified：已修改 ${state.fields.size} 项" to state.fields.map { ConfigFilter.Match(it, "") }
        is ConfigFilter.FilterState.Restart ->
            "@restart：须重启生效 ${state.fields.size} 项" to state.fields.map { ConfigFilter.Match(it, "") }
        is ConfigFilter.FilterState.Keyword ->
            "「${query.trim()}」命中 ${state.matches.size} 项" to state.matches
    }
    SectionCard(
        title = title,
        collapsible = false,
        modifier = Modifier.fillMaxWidth(),
        headerTrailing = {
            // 行内清空入口（退出过滤 = 清空搜索框）
            Text(
                text = "退出过滤",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClear,
                    )
                    .padding(horizontal = Dimens.space4),
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = "无匹配字段（label / 说明 / 字段 ID 全文匹配；@modified 看改过、@restart 看须重启）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entries.forEachIndexed { matchIndex, match ->
                    @Suppress("UNCHECKED_CAST")
                    val typed = match.field as BridgeField<Any?>
                    Column(modifier = Modifier.fillMaxWidth()) {
                        FieldRow(
                            field = typed,
                            config = config,
                            issue = issues[match.field.id],
                            modified = match.field.id in modified,
                            onCommit = { value -> onCommit(typed, value) },
                            // 横切面（@modified/@restart）无分区归属；关键词命中显示分区名前缀。
                            // hint 补命中序号规则：
                            // 「『超时』命中 3 项 · 此为第 1 项」——contextLabel 前缀拼接
                            // （FieldRow 共用渲染器不签名不动，序号信息经 contextLabel 承载）
                            contextLabel = match.zoneTitle.ifBlank { null }?.let { zone ->
                                "$zone · 此为第 ${matchIndex + 1} 项"
                            },
                        )
                        // 关键词命中行尾「进入分区 →」（清搜索 + 展开该分区；滚动定位
                        // 属后置增强）。@modified/@restart 行
                        // zoneTitle 为空 ⇒ 无钮（横切面无分区归属语义，零代码成立）。
                        // FieldRow 是共用渲染器不签名不动——外包 Column 行尾缀文字钮
                        // （.lnk 行内值尾横排位——FieldRow 不可签名，钮行右对齐近似行尾位）。
                        val enterable = onEnterZone != null &&
                            state is ConfigFilter.FilterState.Keyword &&
                            match.zoneTitle.isNotBlank()
                        if (enterable) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text(
                                    text = "进入分区 →",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { onEnterZone?.invoke(match.zoneTitle) },
                                        )
                                        .padding(horizontal = Dimens.space4),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一列字段行（分区小节/过滤结果共用；间距 space8）。 */
@Composable
private fun FieldColumn(
    fields: List<BridgeField<*>>,
    config: BridgeConfig,
    issues: Map<String, String>,
    modified: Set<String>,
    onCommit: (BridgeField<Any?>, Any?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        fields.forEach { field ->
            @Suppress("UNCHECKED_CAST")
            val typed = field as BridgeField<Any?>
            FieldRow(
                field = typed,
                config = config,
                issue = issues[field.id],
                modified = field.id in modified,
                onCommit = { value -> onCommit(typed, value) },
            )
        }
    }
}
