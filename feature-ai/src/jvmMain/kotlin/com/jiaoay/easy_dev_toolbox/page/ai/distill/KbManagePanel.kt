package com.jiaoay.easy_dev_toolbox.page.ai.distill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.data.db.KnowledgeRepository
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.ChipTextButton
import com.jiaoay.easy_dev_toolbox.widget.DotState
import com.jiaoay.easy_dev_toolbox.widget.SearchField
import com.jiaoay.easy_dev_toolbox.widget.StatusDot
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.flow.filter

/**
 * 知识库管理面（[KbManagePanelContent] + 仓库四表浏览 [KbRepoBrowse] + 检索派生纯函数）：
 * [KbManagePanelContent] public 化——供知识库独立签/独立页复用（palette 动作项注册直达另议）；
 * 检索派生纯函数（[kbRepoBrowseSearching] 等）internal——单测直测缝，[KbRepoBrowse] 消费。
 * 全部状态经 [DistillViewModel] 注入，纯 props 渲染面。
 */

// ── ⑤ 知识库管理面（按 repo 浏览/统计/删除/导出——记忆面板四动作模式）──
// public 化：供知识库独立签/独立页复用（palette 动作项注册直达另议）。

@Composable
fun KbManagePanelContent(vm: DistillViewModel) {
    val stats by vm.kbStats.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshKbStats() }
    var exportPath by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val kbBrowse by vm.kbBrowse.collectAsState()
    val kbSearchResult by vm.kbSearchResult.collectAsState()

    // 深链达面板（导航深链行「知识库管理 · 智能体中心 ·
    // 蒸馏签 · 库面板 · KbManagePanelContent public 化直达」）：palette「知识库
    // 管理」候选行点击 → KbManagePanelHook 载荷 → 消费置位展开标记，kbStats
    // 就绪后展开首仓库浏览面（kbBrowse = 展开态；空库无仓库可展开，载荷就地作废）。
    var kbPanelDeepLink by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // 落签初值承载（用户从别的签/页落蒸馏签——本面板首次组合）：一次性取走槽内
    // 既有载荷；consume 读即清，false 时不覆写展开标记（与下方订阅路径互不踩，
    // 同一载荷恰消费一次）。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (com.jiaoay.easy_dev_toolbox.core.navigation.KbManagePanelHook.consume()) {
            kbPanelDeepLink = true
        }
    }
    // 在签响应缝（ToolsPage 预选槽 / 工作台深链 / palette 衔接 seed 同款双
    // effect 配方）：palette 是浮层——用户已停在蒸馏签时点「知识库管理」候选行，
    // 宿主 setCurrentPage / pendingAgentCenterTab 双同值不发射（AnimatedContent
    // 不重组），上方 LaunchedEffect(Unit) 一次性消费不重跑，载荷将静默 no-op 并
    // 滞留槽内（切签返签时才迟发旧展开）。订阅槽流（非 false 即消费）保证在签
    // 期间新载荷即时置位展开标记；初值承载清槽后本流初值即 false 被过滤天然不
    // 双发；展开落地仍由下方 LaunchedEffect(kbPanelDeepLink, stats) 等 kbStats
    // 就绪统一承担（两消费路径共用，无第二套落地分支）。
    androidx.compose.runtime.LaunchedEffect(com.jiaoay.easy_dev_toolbox.core.navigation.KbManagePanelHook) {
        com.jiaoay.easy_dev_toolbox.core.navigation.KbManagePanelHook.pending
            .filter { it }
            .collect {
                // 读即清（与初值承载同款收口）：collect 直接用收到的载荷，
                // consume 只负责清槽——防切签返页时初值承载重读已消费旧载荷
                com.jiaoay.easy_dev_toolbox.core.navigation.KbManagePanelHook.consume()
                kbPanelDeepLink = true
            }
    }
    androidx.compose.runtime.LaunchedEffect(kbPanelDeepLink, stats) {
        if (kbPanelDeepLink && stats != null) {
            stats?.repos?.firstOrNull()?.let { vm.openKbRepo(it) }
            kbPanelDeepLink = false
        }
    }

    // 持久化实体删除一律确认：待删仓库名
    var pendingDeleteRepo by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    SectionCard(
        title = "知识库",
        description = "蒸馏候选与导入包的入库真源（agent 检索同库）——按仓库节管理；导出为 KnowledgePack JSON",
    ) {
        val s = stats
        if (s == null) {
            // 裸「读取中…」文字 → 行内圈（紧凑场景 progressInline+strokeThin 配方）
            InlineLoadingRow()
        } else if (s.repos.isEmpty()) {
            EmptyStateView(
                text = "知识库为空",
                learningHint = "蒸馏候选入库后，飞书机器人的知识检索即可使用这些知识",
                fillParent = false,
            )
        } else {
            Text(
                "共 ${s.repos.size} 个仓库 · TAG ${s.tags} / 决策节点 ${s.decisionNodes} / 黄金样本 ${s.goldenSamples} / 文档 ${s.docs}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.repos.forEach { repo ->
                val expanded = kbBrowse?.repo == repo
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.FOLDER,
                            contentDescription = null,
                            size = Dimens.iconSmall,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            repo,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { vm.openKbRepo(if (expanded) null else repo) },
                            color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        // 删除钮终态 = AppButton(Secondary)（error 色语义由
                        // AppConfirmDialog isDangerous 承担，勿改 Destructive 形态）
                        AppButton(
                            label = "删除",
                            busy = false,
                            kind = AppButtonKind.Secondary,
                            onClick = { pendingDeleteRepo = repo },
                        )
                    }
                    if (expanded) {
                        KbRepoBrowse(
                            browse = kbBrowse,
                            searchResult = kbSearchResult,
                            onSearch = vm::kbSearch,
                            // toolbar chips 流数据源（仓库全集 + 当前展开仓 + 切换回调）
                            repos = s.repos,
                            onSelectRepo = vm::openKbRepo,
                        )
                    }
                }
            }
            AppButton(
                label = "导出 KnowledgePack JSON",
                busy = false,
                kind = AppButtonKind.Secondary,
                onClick = { vm.exportKbPack { path -> exportPath = path } },
            )
            if (exportPath != null) {
                Text(
                    "已导出：$exportPath",
                    style = DesktopTypography.mono, // 与语音侧导出回执同构
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 删除确认对话框（含具体后果与数量——四表记录随仓库删除）
    pendingDeleteRepo?.let { repo ->
        com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog(
            title = "删除知识仓库",
            text = "将删除仓库「$repo」的全部 TAG / 决策节点 / 黄金样本 / 文档记录，不可恢复。" +
                "如需保留请先「导出 KnowledgePack JSON」。",
            confirmLabel = "删除",
            isDangerous = true,
            onConfirm = {
                vm.deleteKbRepo(repo)
                vm.refreshKbStats()
                pendingDeleteRepo = null
            },
            onDismiss = { pendingDeleteRepo = null },
        )
    }
}

// ── 仓库四表浏览（add-distill-output-viewer 任务 3.1/3.2——纯只读成品面） ──

@Composable
private fun KbRepoBrowse(
    browse: DistillViewModel.KbBrowse?,
    searchResult: DistillViewModel.KbSearchResult?,
    onSearch: (String) -> Unit,
    /** toolbar chips 流数据源：仓库全集 + 当前展开仓 + 切换回调 */
    repos: List<String> = emptyList(),
    onSelectRepo: (String?) -> Unit = {},
) {
    val b = browse ?: run {
        // 裸「读取中…」文字 → 行内圈（同上配方）
        InlineLoadingRow()
        return
    }
    var keyword by androidx.compose.runtime.remember(b.repo) { androidx.compose.runtime.mutableStateOf("") }
    // 展开态 remember key 加 keyword——检索过滤切换时过滤列表
    // 索引与全量列表索引错位（expandedSample 按 Int 索引引用），keyword 变更即重置
    // 展开态（低危防线：检索时通常未展开）
    var expandedSample by androidx.compose.runtime.remember(b.repo, keyword) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    var expandedDoc by androidx.compose.runtime.remember(b.repo, keyword) { androidx.compose.runtime.mutableStateOf<String?>(null) }

    // 检索结果落列表——keyword 非空且检索结果有效时黄金样本/
    // 文档两节只渲染命中（TAG/决策节点检索不覆盖，保持全量+节头 hint）。
    // 🔴 searchResult.repo == b.repo 守卫（顺手防线，如实上报）：kbSearch 有在途
    // 协程时 searchResult 可能仍是上一 repo 的结果——repo 不匹配不套用（防跨仓
    // 在途脏数据）；清空提交 = kbSearch("") → result=null → 恢复全量（VM:344-347 既有）。
    // 派生逻辑抽顶层纯函数（[kbRepoBrowseSearching] 等）——单测直测缝。
    val searching = kbRepoBrowseSearching(b, searchResult)
    val shownSamples = kbRepoBrowseShownSamples(b, searchResult)
    val shownDocs = kbRepoBrowseShownDocs(b, searchResult)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.space12),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        // 顶部工具条一行 =
        // search（34dp capsule，flex:1 吃满余宽）+ 仓库 chipbtn
        // 流（hot = 当前展开仓 kbBrowse.repo，点击 openKbRepo toggle 切换、再点
        // 同仓收起；同屏至多一枚 hot）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space10),
        ) {
            SearchField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = "搜索（黄金样本症状 / 文档标题）",
                onCommit = {
                    onSearch(it)
                },
                modifier = Modifier.weight(1f),
            )
            repos.forEach { repo ->
                val hot = b.repo == repo
                // hot 态落位差（primary 字 + primary×hotWash 热底）经 color/container
                // 参数进 ChipTextButton
                ChipTextButton(
                    label = repo,
                    color = if (hot) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    container = if (hot) {
                        MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.hotWash)
                    } else {
                        LocalToolboxSurfaceColors.current.surfaceRaised
                    },
                    onClick = { onSelectRepo(if (hot) null else repo) },
                )
            }
        }
        if (searchResult != null && searchResult.keyword.isNotBlank()) {
            // 命中行 = mut 色 + 行首 dotok
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
            ) {
                StatusDot(DotState.OK)
                Text(
                    "命中：黄金样本 ${searchResult.samples.size} · 文档 ${searchResult.docs.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ① TAG 表（检索态节头 hint——检索只覆盖黄金样本/文档双路，本表不参与过滤）
        SectionCard(
            title = "TAG 表（${b.tags.size}）" + if (searching) "（检索不覆盖此表）" else "",
            tonal = false,
        ) {
            if (b.tags.isEmpty()) {
                EmptyStateView(text = "暂无内容", fillParent = false, icon = MaterialSymbols.INBOX)
            } else {
                b.tags.forEach { tag ->
                    Column {
                        Text(tag.tag, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append("→ ${tag.docName}")
                                tag.module?.let { append(" · $it") }
                                if (tag.truncatedTag != null) append(" · 截断:${tag.truncatedTag}")
                                tag.note?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ② 决策节点（method 分组；检索态节头 hint 同 TAG 表）
        SectionCard(
            title = "决策节点（${b.decisionNodes.size}）" + if (searching) "（检索不覆盖此表）" else "",
            tonal = false,
        ) {
            if (b.decisionNodes.isEmpty()) {
                EmptyStateView(text = "暂无内容", fillParent = false, icon = MaterialSymbols.INBOX)
            } else {
                b.decisionNodes.groupBy { it.nodeKey.substringBefore("::") }.forEach { (method, nodes) ->
                    Column {
                        Text(method, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        nodes.forEach { node ->
                            Text(
                                "${node.condition} → ${node.action}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ③ 黄金样本（三元组 + evidenceLog 展开）——检索态只显命中；
        // 节标题「命中 / 全量」双数（非检索态 shown==total 两值相等）
        SectionCard(title = "黄金样本（${shownSamples.size} / ${b.goldenSamplesTotal}）", tonal = false) {
            if (shownSamples.isEmpty()) {
                EmptyStateView(text = "暂无内容", fillParent = false, icon = MaterialSymbols.INBOX)
            } else {
                shownSamples.forEachIndexed { index, sample ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedSample = if (expandedSample == index) null else index },
                    ) {
                        Text(sample.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "症状：${sample.symptom}\n根因：${sample.rootCause}\n修复：${sample.fixSummary}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (expandedSample == index) {
                            // 局部 val：跨模块 public 属性禁 smart cast——显式持有非空副本
                            val evidence = sample.evidenceLog
                            if (evidence != null) {
                                Text(
                                    evidence,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ④ 知识文档（索引行 + body 惰性展开——docsOf 已带 body，直接用）——检索态只显命中；
        // 节标题双数同构
        SectionCard(title = "知识文档（${shownDocs.size} / ${b.docsTotal}）", tonal = false) {
            if (shownDocs.isEmpty()) {
                EmptyStateView(text = "暂无内容", fillParent = false, icon = MaterialSymbols.INBOX)
            } else {
                shownDocs.forEach { doc ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedDoc = if (expandedDoc == doc.name) null else doc.name },
                    ) {
                        Text(doc.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            doc.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (expandedDoc == doc.name) {
                            Text(
                                doc.body,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 检索过滤派生（顶层纯函数——单测直测缝，KbRepoBrowse 消费） ──

/**
 * 行内加载指示：裸「读取中…」文字 → 行内圈统一配方
 *（紧凑场景 hero 圈过大，走 progressInline+strokeThin，EvidenceTimeline 同款）。
 * kbStats 读取中 / KbRepoBrowse 读取中两处复用。
 */
@Composable
private fun InlineLoadingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimens.progressInline),
            strokeWidth = Dimens.strokeThin,
        )
        Text(
            "读取中…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 检索态判定：keyword 非空 + repo 匹配（防跨仓在途脏数据——kbSearch 在途时 result 可能是上一 repo 的） */
internal fun kbRepoBrowseSearching(
    browse: DistillViewModel.KbBrowse,
    searchResult: DistillViewModel.KbSearchResult?,
): Boolean =
    searchResult != null && searchResult.keyword.isNotBlank() && searchResult.repo == browse.repo

/** 黄金样本渲染列表：检索态只显命中，否则全量 */
internal fun kbRepoBrowseShownSamples(
    browse: DistillViewModel.KbBrowse,
    searchResult: DistillViewModel.KbSearchResult?,
): List<KnowledgeRepository.GoldenSample> =
    if (kbRepoBrowseSearching(browse, searchResult)) {
        searchResult?.samples ?: browse.goldenSamples
    } else {
        browse.goldenSamples
    }

/** 知识文档渲染列表：检索态只显命中，否则全量 */
internal fun kbRepoBrowseShownDocs(
    browse: DistillViewModel.KbBrowse,
    searchResult: DistillViewModel.KbSearchResult?,
): List<KnowledgeRepository.KnowledgeDoc> =
    if (kbRepoBrowseSearching(browse, searchResult)) {
        searchResult?.docs ?: browse.docs
    } else {
        browse.docs
    }
