package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandResult
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.smartdetect.SmartDetection
import com.jiaoay.easy_dev_toolbox.core.smartdetect.detectClipboard
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxGlassColors
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.glass.appFloatShadow
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassSheet
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/**
 * 全局命令面板 omnibar：Ctrl+K 唤起，顶部弹出（非模态）——面板打开时
 * 主窗口交互不阻断，点击面板外即关闭。
 *
 * ## 浮层材质
 * - **sheet 玻璃**：Surface → [appGlassSheet]（浮层恒真玻璃，
 *   投影挂 [androidx.compose.ui.draw.shadow]（glassShadowElevation——sheet 唯一投影档）；
 * - **拦截层**：Popup 内容 = fillMaxSize 拦截 Box（detectTapGestures）——面板内
 *   点击空白 onDismiss；点外关闭走 Popup 自身 onDismissRequest（见 Popup 处注释）；
 * - **Popup 内热键补链**：Popup 是独立 focus 体系，main.kt Window 级 onPreviewKeyEvent
 *   收不到 Popup 内按键——Esc→onDismiss、Ctrl+K→toggle（面板开着 = 关）
 *   补挂输入框 onPreviewKeyEvent（preview 链先于文本编辑 handler）。
 *
 * 数据源三路汇入（浏览/搜索/直达全部工具均经本面板——palette 是全量工具的
 * 唯一浏览面）：
 * - 导航项（ToolPageRegistry.navItems）；
 * - 动作项（ToolPageRegistry.toolActions）+ 带参命令（ParamCommandCatalog.all
 *   = feature 页面命令 + AgentToolCatalog uiHint 投影）；
 * - 设置/调试宿主手工页（设置恒可达；调试经 debugEnabled 门控）。
 *
 * 带参命令状态机（PaletteEntry.ParamCommand）：
 * 列表态 → Enter/点击 = 进参数直填态（PaletteParamForm，焦点自搜索框转移至首参数框）→
 * 填齐必填 → Enter（根拦截层收口——preview 隧道先于焦点组件）或点「执行」钮 →
 * HUD 态（PaletteHud 显示结果/产物 chips）→ Esc 退回列表 / 点击外部关面板照旧。
 * 表单态退出 = 仅 Esc（表单态列表已替换为参数表单，「点列表退出」不可达——编辑查询
 * 亦不退出表单：焦点已在参数框，打字不毁表单）。
 * openPage 非 null 的命令行尾「打开工具页」钮（深入模式——经 hostOf
 * 归并跳聚合页 + 预选子签，散页永不复活）。
 *
 * 空态即首页：Mixed 域空查询时**直接渲染四路
 * 推荐面板**（剪贴板识别/最近空间/常用入口/未读产物——[shouldShowRecommendationHome] 判定；
 * 剪贴板识别 = 面板唤起时对剪贴板内容做一次类型探测，命中即推荐对应工具子签，
 * 置顶于其余三路之前），
 * 不再依赖「filtered 为空」条件（该条件与候选集恒含设置项互斥，恒不可达）；
 * 带查询/前缀域无匹配时保留「没有匹配」固定文案。推荐面板的最近空间行走
 * [onSelectSpace] 接宿主 AppViewModel.setCurrentSpace（SpaceBar select 同款回写）。
 *
 * 执行逃逸竞态：执行中编辑查询即作废在途执行门票
 * （execTicket 递增，迟到协程核对丢弃——hudResult 不得把面板突切回 HUD 态盖住
 * 当前态），executing 随之复位（编辑后 Enter 可重新发起，长任务期间不再死键）；
 * 门票作废不改三态（表单态编辑查询不再退出表单）。
 *
 * 危险动作二次确认（inline，不引弹窗打断流）：isDangerous 条目首次激活置确认态
 * （面板内确认条 + 行 error 色已渲染），同条目再次 Enter/点击才真执行；Esc 取消、
 * 激活其他条目改挂新确认。契约注释见 core/navigation/CommandActions.isDangerous。
 *
 * 排序 = frecency（usage counts 加权 + 注册序稳定兜底）：常用页浮前。
 * 键盘契约：↑/↓ 移动选中（滚动跟随）、Enter 执行/进表单、Esc 关闭；
 * 推荐首页态（Mixed 空查询）↑↓/Enter 不作用于被遮蔽的完整列表（推荐行走点击）。
 *
 * 动作/命令可见性：visibleWhen 在**面板打开瞬间求值一次**——面板开着时
 *    任务跑完/启动不会闪现消失条目（低频面板交互，不引入流式重算）。
 *
 * 视觉全走既有 token（AppShapes/Dimens/AppAlpha），无新增裸 dp、无新动画规格。
 */
@Composable
fun CommandPalette(
    visible: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (ToolPage, ToolPage?) -> Unit,
    debugEnabled: Boolean,
    usageCounts: Map<String, Int> = emptyMap(),
    hiddenPages: Set<ToolPage> = emptySet(),
    aiDisabledPages: Set<ToolPage> = emptySet(),
    commandMode: Boolean = false,
    onPreselectToolsTab: (Int) -> Unit = {},
    onSelectSpace: (String) -> Unit = {},
) {
    if (!visible) return

    // 浮层入场动效：入场 scale+fade（Popup 挂载即窗
    // 存在，出场无法拦窗销毁——入场动画达成「不再硬切」；模态动效规范 = scale-feedback +
    // fade-crossfade 组合）。AppMotion.fast 到位（面板是高频瞬态件，短档即可）。
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val appearFraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = AppMotion.fast(),
        label = "paletteAppear",
    )
    val appearModifier = Modifier
        .graphicsLayer {
            scaleX = 0.96f + 0.04f * appearFraction
            scaleY = 0.96f + 0.04f * appearFraction
            alpha = appearFraction
        }

    var query by remember { mutableStateOf(if (commandMode) ">" else "") }
    var selectedIndex by remember { mutableStateOf(0) }

    // ── 带参命令状态机（三态：列表 / 参数直填 / HUD） ──
    var activeParam by remember { mutableStateOf<ParamCommandSpec?>(null) }
    var paramValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var hudResult by remember { mutableStateOf<ParamCommandResult?>(null) }
    var executing by remember { mutableStateOf(false) }
    // 执行逃逸竞态门票：每次执行发一张递增门票；逃逸（查询编辑清空
    // HUD 态）使门票失效——在途协程收尾时核对门票，非当次执行不得回写 hudResult/
    // executing（迟到结果不得把逃逸后的列表态突切回 HUD 态）。remember+Int 整型
    // 单线程组合态读写，无并发面。
    var execTicket by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // H2 · 危险动作二次确认态（inline，不引弹窗打断流）：首次激活置 pendingId，
    // 同条目再次激活才真执行；Esc/激活其他条目清除。id+label 双槽（label 供
    // 确认条文案——条目实例随 allItems 重算重建，不可作比较键）
    var dangerousPendingId by remember { mutableStateOf<String?>(null) }
    var dangerousPendingLabel by remember { mutableStateOf<String?>(null) }

    /** 表单态抢焦链：进参数直填态焦点转首参数框（搜索框→参数框），退表单归还搜索框 */
    val paramFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(activeParam) {
        if (activeParam != null) {
            runCatching { paramFocusRequester.requestFocus() }
        } else {
            runCatching { focusRequester.requestFocus() }
        }
    }

    /** 危险动作执行闸：非危险直通；危险首触发置确认态、同条目再次触发才执行 */
    fun performAction(entry: PaletteEntry.Action) {
        if (entry.isDangerous && dangerousPendingId != entry.key) {
            dangerousPendingId = entry.key
            dangerousPendingLabel = entry.label
            return
        }
        dangerousPendingId = null
        dangerousPendingLabel = null
        entry.action.perform()
    }

    /** 危险确认态清除（Esc/表单退出/HUD 退出共用——悬挂确认不跨态存活） */
    fun clearDangerousPending() {
        dangerousPendingId = null
        dangerousPendingLabel = null
    }

    /**
     * 面板关闭单一收口（三条关闭通道同源：点外 onDismissRequest / 点空白 tap /
     * Esc·Ctrl+K onDismiss——通道旁路不得直调 [onDismiss]，防绕过执行态兜底）。
     *
     * 执行中关闭：非模态浮层关闭即组合销毁、rememberCoroutineScope 取消——在途
     * 协程结果连人带货消失（阻塞 IO 段可能已跑完、产物部分落盘）。scope 语义
     * 不变（关闭恒取消执行），仅补 toast 回执防「命令静默蒸发」零感知。
     */
    fun dismissPalette() {
        if (executing) {
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                "「${activeParam?.label ?: "命令"}」已取消（若为大文件，产物可能已部分落盘）",
            )
        }
        onDismiss()
    }

    // 空态智能数据（打开瞬间拉取一次）
    val emptyState = rememberPaletteEmptyState(visible)

    // 剪贴板识别推荐（打开瞬间读取探测一次；探测纯函数族在 :core smartdetect）。
    // 推荐作为推荐首页的独立分区呈现——不进候选全集、不参与 frecency 排序、
    // 不触碰 selectedIndex（推荐首页态键盘本就吞键，零交互面交集）。
    var smartDetections by remember { mutableStateOf<List<SmartDetection>>(emptyList()) }
    val clipboardManager = LocalClipboardManager.current

    // 组合期零 SQL：knowledge.* 可见性经 LaunchedEffect 异步
    // 预取内存快照（ParamCommandCatalog.prefetchKnowledgeVisibility——IO 承接），组合期
    // visibleWhen 只读布尔不查库。knowledgeReady 是「预取完成」闩：完成后 [allItems]
    // 重算一次（remember 键），至多一拍快照陈旧窗口；「打开瞬间求值一次」契约不变。
    var knowledgeReady by remember { mutableStateOf(false) }

    /**
     * 衔接 seed 落地分流（打开瞬间初值承载与在页订阅两消费路径共用的单一来源）：
     * 目标命令在全量目录解析成功即进参数直填态（默认值预填 + 衔接参数覆写）；
     * id 解析失败（改名/退役）静默回落列表态，不炸面板。
     */
    fun applyHandoffSeed(seed: TaskPipelineHandoff.HandoffSeed) {
        ParamCommandCatalog.all().firstOrNull { it.id == seed.commandId }?.let { spec ->
            activeParam = spec
            paramValues = spec.params
                .filter { it.default != null }
                .associate { it.id to it.default!! } + seed.args
            // seed 即进参数直填态 = 显式逃离当前执行/HUD 态——与查询编辑/Esc 同款
            // 门票收口：作废在途结果回写（迟到 hudResult 不得把已切换的表单态拉回
            // HUD）+ executing 复位（表单 Enter 即刻可用）+ HUD 结果顺手清空
            execTicket += 1
            executing = false
            hudResult = null
        }
    }

    // 打开瞬间初值承载（关→开路径）：palette 从关到开时本函数体经 visible 早退
    // 全新组合，此处一次性取走槽内既有 seed。
    LaunchedEffect(visible) {
        if (visible) {
            ParamCommandCatalog.prefetchKnowledgeVisibility()
            knowledgeReady = true
            // 剪贴板识别：面板唤起读一次（探测面与动作可见性同款「打开瞬间求值
            // 一次」契约——面板开着不跟随系统剪贴板变化重探测）
            smartDetections = readClipboardForDetection(clipboardManager)
            // 管线衔接 seed 消费（工具页产物「送往下一步」投递）：见上方
            // applyHandoffSeed 分流注释
            TaskPipelineHandoff.consumeSeed()?.let { seed -> applyHandoffSeed(seed) }
        }
    }

    // 在页响应缝（ToolsPage 预选槽 / LogWorkbenchPage 深链同款双 effect 配方）：
    // palette 是浮层且本函数体以 visible 早退——「面板已开」时查看器独立窗口
    // （唯一悬于已开面板之上的生产面）的衔接动作 offer 后，宿主 open 是同值
    // 写回 paletteVisible（不触发关→开重组），上方 LaunchedEffect(visible)
    // 不重跑，seed 将滞留槽内、下次打开迟发旧参数表单（旧产物路径预填）。
    // 订阅槽流（StateFlow 非空即消费）保证面板保持组合期间新 seed 即时进
    // 参数直填态；打开瞬间消费序不变（初值承载仍由上方 effect 承担——槽被
    // 初值消费后已清空，本 effect 起播收到的流初值即 null，天然不双发；即使
    // 起播早于初值承载执行，consumeSeed 读即清也保证同一 seed 恰消费一次，
    // 两路径落地共用 applyHandoffSeed，重复进表单态是同值幂等）。
    LaunchedEffect(TaskPipelineHandoff) {
        TaskPipelineHandoff.pendingSeed
            .filterNotNull()
            .collect { seed ->
                // 读即清（与打开瞬间承载同款收口）：collect 直接用收到的载荷，
                // consumeSeed 只负责清槽——防下次打开时初值承载重读已消费旧载荷
                TaskPipelineHandoff.consumeSeed()
                applyHandoffSeed(seed)
            }
    }

    /** 命令入口统一收口：列表选中进表单 / 表单填齐执行（Enter 与点击共用） */
    fun executeParamCommand(spec: ParamCommandSpec, values: Map<String, String>) {
        if (!paramFormReady(spec.params, values)) return
        // H2 危险闸：危险命令首次执行请求置确认态（同 spec 再次触发才真执行）
        if (spec.isDangerous && dangerousPendingId != "param:${spec.id}") {
            dangerousPendingId = "param:${spec.id}"
            dangerousPendingLabel = spec.label
            return
        }
        dangerousPendingId = null
        dangerousPendingLabel = null
        executing = true
        execTicket += 1
        val ticket = execTicket
        scope.launch {
            val result = runCatching { spec.performArgs(values) }
                .getOrElse { e ->
                    ParamCommandResult(success = false, summary = "执行失败：${e.message ?: e::class.simpleName}")
                }
            // 逃逸核对：门票作废（用户已编辑查询或经 Esc 逃回列表）则丢弃迟到结果，
            // 且不得触碰 executing（逃逸时已复位）；作废不丢回执——结果经全局
            // 状态条告知（作废的是「回写面板态」，不是「告知结果」）
            if (ticket != execTicket) {
                com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                    "「${spec.label}」已完成：${result.summary}",
                )
                return@launch
            }
            hudResult = result
            executing = false
        }
    }

    // 候选全集（打开瞬间求值一次）：导航项 + 动作项 + 带参命令 + 设置/调试宿主页
    // hiddenPages/aiDisabledPages 参与 remember 键：被隐藏页须从搜索结果消失
    // knowledgeReady 参与 remember 键：knowledge.* 可见性快照异步预取完成后重算
    //    候选（组合期零 SQL——visibleWhen 读内存快照，不再同步查库）
    val allItems: List<PaletteEntry> = remember(debugEnabled, hiddenPages, aiDisabledPages, usageCounts, knowledgeReady) {
        buildList {
            ToolPageRegistry.navItems(hiddenPages).map { navItem ->
                add(
                    PaletteEntry.Navigate(
                        page = navItem.page,
                        icon = navItem.icon,
                        label = navItem.label,
                        desc = navItem.desc,
                        aiDisabled = navItem.page in aiDisabledPages,
                    ),
                )
            }
            ToolPageRegistry.toolActions()
                .filter { it.visibleWhen() }
                .forEach { action ->
                    add(
                        PaletteEntry.Action(
                            action = action,
                            icon = action.icon,
                            label = action.label,
                            desc = action.desc,
                            keywords = action.keywords,
                            isDangerous = action.isDangerous,
                        ),
                    )
                }
            ParamCommandCatalog.all()
                .filter { it.visibleWhen() }
                .forEach { spec -> add(PaletteEntry.ParamCommand(spec)) }
            add(PaletteEntry.Navigate(ToolPage.Setting, MaterialSymbols.SETTINGS, "设置", "外观 / 配色 / Shell / 日志 / 开发者选项"))
            // 报告页 Navigate 候选：palette 是全量浏览/搜索/直达面——报告页（tab
            // 集第四成员）补直达候选，tab 条外也有第二入口
            add(PaletteEntry.Navigate(ToolPage.Report, MaterialSymbols.INSIGHTS, "报告", "最近 run 报告卡列表（工作台产物）"))
            // 三退役 AI 散页深链候选：三页 navItem 已退役，本三候选让深链直达聚合页**对应子签**（Navigate 行
            // 第二参捎带原散页枚举 → App.kt 接线 hostOf 归并 + agentCenterIndexOf 置预选槽）。图标 = design-language.md §4.3 语义锚：会话 CHAT（对话气泡）、蒸馏 AUTO_STORIES
            // （书页蒸馏）、语音 MIC（表内「麦克风 mic」锚）——Filled 可点击语义。
            add(PaletteEntry.Navigate(ToolPage.Distill, MaterialSymbols.AUTO_STORIES, "知识蒸馏", "源码探索 → 计划审批 → 蒸馏入库（智能体中心子签）"))
            add(PaletteEntry.Navigate(ToolPage.VoiceTranscribe, MaterialSymbols.MIC, "语音转写", "音频转写与 AI 整理（智能体中心子签）"))
            add(PaletteEntry.Navigate(ToolPage.Flow, MaterialSymbols.CHAT, "会话", "agent 会话与主动流程（智能体中心子签）"))
            // 深链达面板（导航深链行：「知识库管理 ·
            // 智能体中心 · 蒸馏签 · 库面板 · KbManagePanelContent public 化直达」）：
            // 与「知识蒸馏」同落 Distill（variant 区分 key 防撞车），点击时经
            // KbManagePanelHook 中立槽发库面板展开载荷（见两处点击分支）
            add(
                PaletteEntry.Navigate(
                    ToolPage.Distill,
                    MaterialSymbols.LIBRARY_BOOKS,
                    "知识库管理",
                    "智能体中心 · 蒸馏签 · 库面板（蒸馏入库与 KnowledgePack 导出）",
                    keywords = listOf("知识库", "蒸馏", "导出", "kb", "knowledge"),
                    variant = VARIANT_KB_MANAGE,
                ),
            )
            if (debugEnabled) {
                add(PaletteEntry.Navigate(ToolPage.Debug, MaterialSymbols.BUG_REPORT, "Shell 调试", "Shell 调试页（开发者选项）"))
            }
        }.let { sortEntriesByFrecency(it, usageCounts) }
    }

    // 模糊过滤：label/desc/keywords 大小写不敏感子串匹配；query 为空展示全集。
    // 前缀模式：`>` 仅命令（动作+带参） / `@` 仅页面——前缀剥离后按正文过滤。
    val prefixMode = remember(query) { PalettePrefix.of(query) }
    val effectiveQuery = remember(query, prefixMode) {
        if (prefixMode == PalettePrefix.Mixed) query else query.drop(1)
    }
    val filtered = remember(query, allItems, emptyState.recentReports) {
        val scoped: List<PaletteEntry> = when (prefixMode) {
            PalettePrefix.Mixed -> allItems
            PalettePrefix.CommandsOnly -> allItems.filter {
                it is PaletteEntry.Action || it is PaletteEntry.ParamCommand
            }
            PalettePrefix.PagesOnly -> allItems.filterIsInstance<PaletteEntry.Navigate>()
        }
        // 未读产物（检索态第三段）：只在 Mixed 域恒显（前缀域不显——用户明确收窄
        // 域时保底推荐让位）；不按 query 过滤（s02 字面：查询词零匹配仍显示）。
        if (prefixMode != PalettePrefix.Mixed) {
            scoped
        } else {
            val base: List<PaletteEntry> = if (effectiveQuery.isBlank()) scoped
            else scoped.filter { entry ->
                entry.label.contains(effectiveQuery, ignoreCase = true) ||
                    entry.desc.contains(effectiveQuery, ignoreCase = true) ||
                    entry.keywords.any { it.contains(effectiveQuery, ignoreCase = true) } ||
                    // 检索别名——「家」「设备」「adb」等用户心智词
                    // 命中规范名页（ToolPageLabels.aliases 单源；palette 是无学习成本入口）
                    (entry is PaletteEntry.Navigate &&
                        ToolPageLabels.aliases(entry.page).any { it.contains(effectiveQuery, ignoreCase = true) })
            }
            // 报告行追加在过滤后（不参与 frecency 排序——恒显保底段固定尾位）
            base + emptyState.recentReports.map { PaletteEntry.ReportRun(it) }
        }
    }

    // 过滤后选中项 clamp（结果集缩小时防越界）
    LaunchedEffect(filtered.size) {
        if (selectedIndex >= filtered.size) selectedIndex = filtered.lastIndex.coerceAtLeast(0)
    }

    // 打开即聚焦搜索框（FocusRequester 优先请求，frame 落地后生效）。
    // 🔴 popup 可聚焦窗在 CMP 内异步落成，单发请求
    //    会稳定落空（开箱不聚焦）——改双发：立即一发 +
    //    下一帧补一发，runCatching 吞未挂载态（节点未 attach 时 requestFocus 抛错）
    LaunchedEffect(visible) {
        if (visible) {
            runCatching { focusRequester.requestFocus() }
            androidx.compose.runtime.withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    // 顶部弹出偏移（px）：paletteTopOffset token → 密度换算
    val density = androidx.compose.ui.platform.LocalDensity.current
    val paletteTopOffsetPx = with(density) { Dimens.paletteTopOffset.toPx() }.toInt()

    /**
     * 深入模式：ParamCommand.openPage 跳页（palette 行尾钮 / HUD 链接共用）。
     * 路由经 [ToolPageLabels.hostOf] 归并——五件套散页
     * 恒落聚合页（工具/智能体中心），第二参捎带原散页枚举（宿主换算预选子签），
     * 散页永不复活。一处改动，五条带参命令 + HUD 链接全部生效。
     */
    fun openPageOf(spec: ParamCommandSpec) {
        spec.openPage?.let { page ->
            onNavigate(ToolPageLabels.hostOf(page) ?: page, page)
            onDismiss()
        }
    }

    // 非模态 Popup：顶部弹出、focusable 抢焦点。关闭链路三条，勿凭直觉改动：
    // - 点外关闭 = onDismissRequest（CMP Popup 窗口级外部点击检测；拦截层
    //   fillMaxSize 只在面板内撑满——面板外区域不归它管，勿试图用它做「全窗拦截」）；
    // - Esc/Ctrl+K = **输入框** onPreviewKeyEvent（focusable Popup 的按键恒派发给
    //   焦点组件——输入框是唯一焦点落点，preview 链真实可达）；
    // - 面板内点击空白 = 拦截层 detectTapGestures（尺寸即面板）。
    Popup(
        onDismissRequest = { dismissPalette() },
        properties = PopupProperties(focusable = true),
        alignment = Alignment.TopCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, paletteTopOffsetPx),
    ) {
        // 拦截层：尺寸即面板（见上注），tap 兜底 + 热键漂移兜底。
        // preview 隧道 = 祖先链先行——本层处理先于焦点组件（搜索框/参数框），
        // 故 Esc 三步与表单态 Enter 在此收口（焦点在参数框时搜索框 handler 不可达）
        // BoxWithConstraints = 最矮窗高度收口：弹层约束有界时（CMP Popup 以窗口
        // 尺寸约束内容）面板限高「窗高 − 顶部偏移 − 底缘余量」——固定 420dp 列表
        // 封顶 + 120dp 顶偏移在最矮窗（640dp）合计溢出窗底；列表随限高自动收窄。
        // 约束无界时退回不限高（既有行为，非桌面环境兜底）
        BoxWithConstraints(
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Escape -> {
                            when {
                                // 二次确认悬挂：Esc 先取消确认（不关面板）
                                dangerousPendingId != null -> {
                                    clearDangerousPending()
                                    true
                                }
                                // 表单/HUD 态：Esc 先退回列表（打字毁表单收口——表单
                                // 仅经 Esc 退出，编辑查询不再退出）。退表单与查询
                                // 编辑同款收口：作废在途执行门票 + executing 复位
                                // （否则迟到结果把已退出的列表态突切回 HUD，且
                                // executing 悬挂期间列表态 Enter 死键）
                                activeParam != null || hudResult != null -> {
                                    activeParam = null
                                    hudResult = null
                                    clearDangerousPending()
                                    execTicket += 1
                                    executing = false
                                    true
                                }
                                else -> {
                                    dismissPalette()
                                    true
                                }
                            }
                        }
                        event.key == Key.K && event.isCtrlPressed -> {
                            dismissPalette() // toggle 语义：开着 = 关
                            true
                        }
                        // 表单态 Enter 上收根 Box（H1）：焦点在参数框——Enter 接既有
                        // executeParamCommand 执行收口（就绪判定/危险闸同搜索框分支）
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                            activeParam != null && hudResult == null -> {
                            when {
                                executing -> true // 执行中吞键（防重复触发）
                                else -> {
                                    val spec = activeParam!!
                                    if (paramFormReady(spec.params, paramValues)) {
                                        executeParamCommand(spec, paramValues)
                                    }
                                    true
                                }
                            }
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { dismissPalette() }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
        Surface(
            shape = AppShapes.glassSheet,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            border = null,
            modifier = Modifier
                .padding(top = Dimens.space8)
                // 自绘软影：elevation 阴影 Linux GPU 硬灰环根因弃用——
                // 见 widget/glass/FloatShadow.kt；挂在 padding 后 = 光晕锚定面板本体
                .appFloatShadow(cornerRadius = Dimens.glassSheetRadius)
                .appGlassSheet(
                    shape = AppShapes.glassSheet,
                ),
        ) {
            // 面内文字/图标一律卡域链（onCardStrong/Mid/Faint）：sheet 玻璃 tint
            // 取源井槽（Lowest，Glass.kt）——井槽分家套的涂装面是深井域，基域深字
            // 对井面破 4.5；非覆写套 onCard 族回落 onSurface 族逐位不变。
            // 限高 = 窗高 − 顶偏移 − 底缘余量（BoxWithConstraints 注记见上）
            val paletteMaxHeightDp = if (constraints.hasBoundedHeight) {
                with(density) { (constraints.maxHeight - paletteTopOffsetPx).toDp() } - Dimens.space16
            } else {
                androidx.compose.ui.unit.Dp.Unspecified
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                modifier = Modifier
                    .width(Dimens.paletteWidth)
                    .then(
                        if (paletteMaxHeightDp != androidx.compose.ui.unit.Dp.Unspecified) {
                            Modifier.heightIn(max = paletteMaxHeightDp)
                        } else {
                            Modifier
                        },
                    )
                    .padding(Dimens.space12),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { input ->
                        query = input
                        selectedIndex = 0
                        // 编辑查询不再退出表单态（H1 打字毁表单收口——焦点已在首参数框，
                        //    搜索框打字是显式动作；表单仅经 Esc 退出，点列表在表单态不可达）。
                        //    逃逸竞态收口保留：
                        //    作废在途执行门票（迟到的 hudResult 不得把列表态突切回 HUD）
                        //    + executing 复位（逃逸后列表态 Enter 可用——长任务期间不再死键）。
                        execTicket += 1
                        executing = false
                        hudResult = null
                    },
                    placeholder = { Text(if (activeParam == null) "搜索页面或工具…" else "带参命令执行中——填写下方参数") },
                    singleLine = true,
                    textStyle = DesktopTypography.mono,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            // 键盘导航（焦点常驻输入框，无需 Tab 切焦点）：
                            // 🔴 必须过滤 event.type——一次物理按键派发 KeyDown + KeyUp
                            //    两个事件（都走 preview 链），不过滤则 ↑/↓ 每按一次移动两格
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            // Esc/Ctrl+K 已由面板根拦截层先行消费（preview 隧道祖先先——
                            // 见拦截层注）：本分支为防御保留（根层覆盖面若收窄，Esc 不至
                            // 直穿关面板）。Popup 内按键恒经焦点链派发（main.kt Window 级
                            // 收不到 Popup 内按键）。先于导航键处理。
                            when {
                                event.key == Key.Escape -> {
                                    dismissPalette()
                                    return@onPreviewKeyEvent true
                                }
                                event.key == Key.K && event.isCtrlPressed -> {
                                    dismissPalette()
                                    return@onPreviewKeyEvent true
                                }
                            }
                            // 推荐首页态：Enter/↑↓ 不作用于不可见的完整列表
                            // （filtered=allItems 恒非空但被推荐面板遮蔽——键盘须防
                            // 「盲执行」：Enter 不得悄悄执行不可见选中项）
                            val homeShowing = shouldShowRecommendationHome(prefixMode, effectiveQuery)
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (activeParam == null && !homeShowing && filtered.isNotEmpty()) {
                                        selectedIndex = (selectedIndex + 1).coerceAtMost(filtered.lastIndex)
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (activeParam == null && !homeShowing && filtered.isNotEmpty()) {
                                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                    }
                                    true
                                }
                                // Enter：HUD/表单/列表三分态 + 三类条目分支
                                Key.Enter, Key.NumPadEnter -> {
                                    when {
                                        executing -> true // 执行中吞键（防重复触发）
                                        hudResult != null -> true // HUD 态 Enter 无操作（操作行走链接）
                                        activeParam != null -> {
                                            val spec = activeParam!!
                                            if (paramFormReady(spec.params, paramValues)) {
                                                executeParamCommand(spec, paramValues)
                                            }
                                            true
                                        }
                                        // 推荐首页态 Enter 吞键（推荐行走点击）——判定在表单态之后：
                                        // activeParam 非 null 是表单态优先（查询常为空、homeShowing
                                        // 恒 true，先判会吞掉表单执行键）；此处 = 列表态被推荐面板遮蔽
                                        homeShowing -> true
                                        else -> {
                                            filtered.getOrNull(selectedIndex)?.let { entry ->
                                                when (entry) {
                                                    is PaletteEntry.ReportRun -> {
                                                        // 行点名具体 run——runId 经聚焦槽投递（报告卡
                                                        // 直达：展开 + 滚动定位 + 微洗底），落点不变
                                                        //（未读产物行落地页 = 报告卡列表）
                                                        ReportFocusRequest.offer(entry.run.id)
                                                        onNavigate(ToolPage.Report, null)
                                                        onDismiss()
                                                    }
                                                    is PaletteEntry.Navigate -> {
                                                        // Navigate 行路由语义的正源 = openPageOf KDoc（hostOf
                                                        // 归并 + 第二参捎带原散页枚举）。本分支差异：aiDisabled
                                                        // 置灰页保留去 AI 设置引导（AiSettings 无深链载荷，
                                                        // 第二参 null——AiSettings 非 hostOf 宿主，接线
                                                        // 分支零命中）；「知识库管理」变体行 = 落签 + 发库面板
                                                        // 展开载荷（导航深链行直达）。
 
                                                        if (entry is PaletteEntry.Navigate &&
                                                            entry.variant == VARIANT_KB_MANAGE
                                                        ) {
                                                            com.jiaoay.easy_dev_toolbox.core.navigation.KbManagePanelHook.offer()
                                                        }
                                                        val targetPage =
                                                            if (entry.aiDisabled) ToolPage.AiSettings
                                                            else ToolPageLabels.hostOf(entry.page) ?: entry.page
                                                        val deepLink = if (entry.aiDisabled) null else entry.page
                                                        onNavigate(targetPage, deepLink)
                                                        onDismiss()
                                                    }
                                                    is PaletteEntry.Action -> performAction(entry)
                                                    is PaletteEntry.ParamCommand -> {
                                                        if (entry.spec.params.isEmpty()) {
                                                            // 空参命令直执行（过渡期形态——params 空表的命令
                                                            // 不进表单直接执行；全量命令带参化后，本分支为
                                                            // 契约允许的空 schema 直通面）
                                                            activeParam = entry.spec
                                                            paramValues = emptyMap()
                                                            executeParamCommand(entry.spec, emptyMap())
                                                        } else {
                                                            // 进参数直填态（默认值预填）
                                                            activeParam = entry.spec
                                                            paramValues = entry.spec.params
                                                                .filter { it.default != null }
                                                                .associate { it.id to it.default!! }
                                                        }
                                                    }
                                                }
                                            }
                                            true
                                        }
                                    }
                                }
                                else -> false
                            }
                        },
                )

                // H2 · 危险动作 inline 二次确认条（面板内轻确认，不引弹窗打断流；
                // 同条目再次 Enter/点击执行，Esc 取消——Esc 三步见根拦截层）
                if (dangerousPendingId != null) {
                    StatusBanner(
                        status = StatusBannerStatus.Warning,
                        text = "「$dangerousPendingLabel」为危险动作——再次 Enter 或点击该行执行，Esc 取消",
                    )
                }

                when {
                    // ── HUD 态：命令执行结果 ──
                    hudResult != null -> {
                        val hudSpec = activeParam
                        // 产物续跑（palette 直跑产物「送往下一步」——按命令 id 桥接
                        // PIPELINE/DeepAnalyzeHook，与页面产物卡衔接能力对称）：
                        // Pipeline 走衔接槽投递（面板已开，seed 由在页订阅缝消费
                        // 即切下一命令参数直填态，路径已预填）；DeepAnalyze 走深链
                        // 槽工作台预填（切页语义，palette 退场）
                        val hudNextStep = hudSpec?.let { nextStepOfCommand(it.id) }
                        PaletteHud(
                            result = hudResult!!,
                            onBack = {
                                hudResult = null
                                activeParam = null
                            },
                            nextStep = hudNextStep,
                            onSendToNext = { artifactPath ->
                                when (val step = hudNextStep) {
                                    is HudNextStep.Pipeline -> TaskPipelineHandoff.offer(step.target, artifactPath)
                                    HudNextStep.DeepAnalyze -> {
                                        DeepAnalyzeHook.offer(
                                            listOf(artifactPath),
                                            "${hudSpec?.label ?: "命令"}产物深度分析",
                                        )
                                        dismissPalette()
                                    }
                                    null -> {}
                                }
                            },
                            onOpenPage = hudSpec?.openPage?.let {
                                { openPageOf(hudSpec) }
                            },
                        )
                    }
                    // ── 参数直填态：PaletteParamForm ──
                    activeParam != null -> {
                        PaletteParamForm(
                            commandLabel = activeParam!!.label,
                            params = activeParam!!.params,
                            values = paramValues,
                            onValueChange = { id, value ->
                                paramValues = paramValues + (id to value)
                            },
                            focusRequester = paramFocusRequester,
                        )
                        if (executing) {
                            Text(
                                text = "执行中…",
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalToolboxSurfaceColors.current.onCardMid,
                                modifier = Modifier.padding(vertical = Dimens.space4),
                            )
                        } else if (paramFormReady(activeParam!!.params, paramValues)) {
                            // 就绪态可见执行钮（H1——原就绪瞬间提示 else-if 消失、鼠标零 affordance）
                            AppButton(
                                label = "执行",
                                busy = false,
                                kind = AppButtonKind.Primary,
                                onClick = { executeParamCommand(activeParam!!, paramValues) },
                            )
                        } else {
                            Text(
                                text = "必填参数未填齐（填写后 Enter 执行）",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalToolboxSurfaceColors.current.onCardFaint,
                                modifier = Modifier.padding(vertical = Dimens.space4),
                            )
                        }
                        // 深入模式钮：行尾「打开工具页」（路由已归并聚合页，
                        // 文案对齐落地语义——不再落散页）
                        if (activeParam!!.openPage != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                                modifier = Modifier
                                    .clip(AppShapes.capsule)
                                    .clickable { openPageOf(activeParam!!) }
                                    .padding(vertical = Dimens.space4),
                            ) {
                                AppSymbolIcon(
                                    icon = MaterialSymbols.OPEN_IN_NEW,
                                    contentDescription = null,
                                    size = Dimens.iconSmall,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "打开工具页（深入模式）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    // ── 推荐首页态 / 空态（空态即首页）──
                    // Mixed 域空查询 = 首页态：直接渲染三路推荐面板（不等 filtered 空——
                    // 候选集恒含设置项，该条件与首页态互斥，恒不可达）；
                    // 带查询/前缀域无匹配 = 精确「没有匹配」文案。
                    shouldShowRecommendationHome(prefixMode, effectiveQuery) || filtered.isEmpty() -> {
                        PaletteEmptyHint(
                            prefixMode = prefixMode,
                            effectiveQuery = effectiveQuery,
                            query = query,
                            emptyState = emptyState,
                            usageCounts = usageCounts,
                            smartDetections = smartDetections,
                            onNavigate = onNavigate,
                            onPreselectToolsTab = onPreselectToolsTab,
                            onSelectSpace = onSelectSpace,
                            onDismiss = onDismiss,
                        )
                    }
                    else -> {
                        val listState = rememberLazyListState()
                        // ⚖️ 检索态三段落实——
                        // 第三段「未读产物」Mixed 域**恒显**（不按 query 过滤：s02
                        // 列表态查询「解压」时该行零匹配仍显示 = 规格字面；「系统提醒
                        // 你有未读报告」语义不依赖查询相关性）。数据 = 推荐首页同源
                        // recentReports（面板打开即拉取，检索态作用域可读）；
                        // 前缀域（`>`/`@`）不显——用户明确
                        // 收窄域时保底推荐让位。空数据不显段头（首页态同款守卫）。
                        // 分组头 = labelSmall faint 无图标纯文本行（.pgrp 同构）。
                        // 空查询零变化：推荐首页态走 PaletteEmptyHint 分支（上方 when），
                        // 本列表态只在带查询/前缀域可达。分组槽表在 LazyColumn 外求值
                        //（滚动跟随 LaunchedEffect 也要按槽序换算落点）。
                        val entriesWithHeaders = buildList {
                            val commands = filtered.filter { it is PaletteEntry.ParamCommand || it is PaletteEntry.Action }
                            val pages = filtered.filterIsInstance<PaletteEntry.Navigate>()
                            val reports = filtered.filterIsInstance<PaletteEntry.ReportRun>()
                            if (commands.isNotEmpty()) {
                                add(ListSlot.Header("带参命令"))
                                commands.forEach { add(ListSlot.Entry(it)) }
                            }
                            if (pages.isNotEmpty()) {
                                add(ListSlot.Header("页面"))
                                pages.forEach { add(ListSlot.Entry(it)) }
                            }
                            if (reports.isNotEmpty()) {
                                add(ListSlot.Header("未读产物"))
                                reports.forEach { add(ListSlot.Entry(it)) }
                            }
                        }
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                            modifier = Modifier.heightIn(max = Dimens.paletteListMaxHeight),
                        ) {
                            items(entriesWithHeaders, key = { slot ->
                                when (slot) {
                                    is ListSlot.Header -> "hdr:${slot.title}"
                                    is ListSlot.Entry -> slot.entry.key
                                }
                            }) { slot ->
                                when (slot) {
                                    is ListSlot.Header -> PaletteSectionHeader(title = slot.title)
                                    is ListSlot.Entry -> {
                                        val entry = slot.entry
                                        PaletteRow(
                                            entry = entry,
                                            selected = entry.key == filtered[selectedIndex].key,
                                            onClick = {
                                                when (entry) {
                                                    is PaletteEntry.ReportRun -> {
                                                        // 行点名具体 run——runId 经聚焦槽投递（与 Enter 分支
                                                        // 同语义：报告卡直达展开定位），落点不变（未读产物
                                                        // 行落地页 = 报告卡列表）
                                                        ReportFocusRequest.offer(entry.run.id)
                                                        onNavigate(ToolPage.Report, null)
                                                        onDismiss()
                                                    }
                                                    is PaletteEntry.Navigate -> {
                                                        // 行点击路由语义的正源 = openPageOf KDoc（与 Enter 分支
                                                        // 同语义）。本分支差异：aiDisabled 置灰页保留去 AI 设置
                                                        // 引导（无深链载荷）；「知识库管理」变体行同发库面板
                                                        // 载荷（Enter 分支同款）。
                                                        if (entry is PaletteEntry.Navigate &&
                                                            entry.variant == VARIANT_KB_MANAGE
                                                        ) {
                                                            com.jiaoay.easy_dev_toolbox.core.navigation.KbManagePanelHook.offer()
                                                        }
                                                        val targetPage =
                                                            if (entry.aiDisabled) ToolPage.AiSettings
                                                            else ToolPageLabels.hostOf(entry.page) ?: entry.page
                                                        val deepLink = if (entry.aiDisabled) null else entry.page
                                                        onNavigate(targetPage, deepLink)
                                                        onDismiss()
                                                    }
                                                    is PaletteEntry.Action -> performAction(entry)
                                                    is PaletteEntry.ParamCommand -> {
                                                        if (entry.spec.params.isEmpty()) {
                                                            activeParam = entry.spec
                                                            paramValues = emptyMap()
                                                            executeParamCommand(entry.spec, emptyMap())
                                                        } else {
                                                            activeParam = entry.spec
                                                            paramValues = entry.spec.params
                                                                .filter { it.default != null }
                                                                .associate { it.id to it.default!! }
                                                        }
                                                    }
                                                }
                                            },
                                            onOpenPage = if (entry is PaletteEntry.ParamCommand && entry.spec.openPage != null) {
                                                { openPageOf(entry.spec) }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        // 选中项滚动跟随：即时 scrollToItem（流式零动画铁律不适用于此低频交互，
                        // 但滚动仍走零动画语义——状态跳变即达）。列表含分组头槽位：
                        // selectedId（entry.key）在 entriesWithHeaders 中的真实 index 才是
                        // scrollToItem 的落点（selectedIndex 是 filtered 序，直接用会落在
                        // 分组头上）。
                        LaunchedEffect(selectedIndex, filtered.size) {
                            val selectedId = filtered.getOrNull(selectedIndex)?.key ?: return@LaunchedEffect
                            val targetIndex = entriesWithHeaders.indexOfFirst {
                                it is ListSlot.Entry && it.entry.key == selectedId
                            }
                            if (targetIndex >= 0) listState.scrollToItem(targetIndex)
                        }
                    }
                }

                // 底部固定提示条（键面提示纪律：浮层内允许常显，数据源 HotkeyHub 同源）
                // 两态均补第二热键 Ctrl+Shift+P 曝光；文案与 dock hint 同款「仅命令」——560dp 宽 labelSmall
                // 单行可容纳（两态追加后约 83%/89% 占用），不换行。
                HorizontalDivider(
                    modifier = Modifier.padding(top = Dimens.space8),
                    color = LocalToolboxGlassColors.current.borderGlass,
                )
                Text(
                    text = if (shouldShowRecommendationHome(prefixMode, effectiveQuery)) {
                        // 推荐首页态：推荐行走点击/输入检索（↑↓/Enter 不作用于隐藏列表）
                        "点击推荐直达 · 输入即检索 · Esc 关闭 · > 只看命令 · @ 只看页面 · Ctrl+Shift+P 仅命令"
                    } else {
                        "↑↓ 选择 · Enter 执行 · Esc 关闭 · 输入 > 只看命令 · @ 只看页面 · Ctrl+Shift+P 仅命令"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalToolboxSurfaceColors.current.onCardFaint,
                    modifier = Modifier.padding(top = Dimens.space8),
                )
            }
        }
        }
    }
}

/**
 * 推荐首页态判定（纯函数——组合逻辑单测锚）：
 * Mixed 域空查询（含裸 `>`/`@` 之外的初始态与退格清空态）→ 三路推荐面板作为
 * palette「首页」。带查询匹配失败 / CommandsOnly / PagesOnly 域空态由调用方
 * 走精确「没有匹配」文案，不进推荐态。
 *
 * 🔴 勿改回 filtered.isEmpty() 条件：候选集 buildList 末尾无条件 add Navigate(Setting)（CommandsOnly/PagesOnly
 *    域也恒非空）——两条件互斥，推荐面板会成死代码；本判定与 filtered 脱钩。
 */
internal fun shouldShowRecommendationHome(prefixMode: PalettePrefix, effectiveQuery: String): Boolean =
    prefixMode == PalettePrefix.Mixed && effectiveQuery.isBlank()

/**
 * 剪贴板读取 + 探测（面板唤起一次性调用）：
 * - 读取走 CMP [ClipboardManager.getText]（桌面端 AWT systemClipboard 承接）。
 *   取舍：CMP 已提供非弃用的 LocalClipboard/Clipboard，但读取面是 suspend +
 *   ClipEntry 解包，一次性同步读取场景不值得引入挂起链——保留同步 getText，
 *   弃用升级留给 CMP 移除该 API 时再迁移；
 * - 时机契约：唤起读一次、读后不驻留不轮询（与动作可见性 visibleWhen 同款
 *   「打开瞬间求值一次」口径）；
 * - 读不到（空剪贴板 / AWT 抛 IllegalStateException 等一切读取异常）→
 *   空表静默无推荐零报错——探测面吞掉全部读取异常，读不到不如报错；
 * - 探测误报纪律与四类探测面见 :core smartdetect/[detectClipboard]。
 */
internal fun readClipboardForDetection(clipboard: ClipboardManager): List<SmartDetection> =
    runCatching { clipboard.getText()?.text }.getOrNull()?.let { detectClipboard(it) } ?: emptyList()
