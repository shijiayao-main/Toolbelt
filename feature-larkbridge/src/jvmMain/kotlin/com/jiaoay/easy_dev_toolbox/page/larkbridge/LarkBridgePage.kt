package com.jiaoay.easy_dev_toolbox.page.larkbridge

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalScaffoldGlassInsets
import com.jiaoay.easy_dev_toolbox.page.larkbridge.cockpit.CockpitPanel
import com.jiaoay.easy_dev_toolbox.page.larkbridge.field.BotCapabilityCard
import com.jiaoay.easy_dev_toolbox.page.larkbridge.field.BotIdentityCard
import com.jiaoay.easy_dev_toolbox.page.larkbridge.field.ConfigEditor
import com.jiaoay.easy_dev_toolbox.page.larkbridge.field.P5FieldGroups
import com.jiaoay.easy_dev_toolbox.page.larkbridge.log.LogConsolePanel
import com.jiaoay.easy_dev_toolbox.page.larkbridge.security.SecurityHintsCard
import com.jiaoay.easy_dev_toolbox.page.larkbridge.stats.UsageStatsPanel
import com.jiaoay.easy_dev_toolbox.page.larkbridge.status.BridgeStatusPanel
import com.jiaoay.easy_dev_toolbox.page.larkbridge.status.ConfigCheckPanel
import com.jiaoay.lark.bridge.ConfigApplyResult
import com.jiaoay.lark.bridge.config.BridgeFields
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverTooltipBox
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.lark.bridge.observe.LogBus
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.launch

/**
 * 飞书桥接页：配置文档编辑器 + 驾驶舱 + 日志台 + 使用统计，四页签
 * （无「路由」页签——routes 数据体系已退役，RouteRuleEditor 是死 UI，勿复辟）。
 *
 * ## 骨架约定（保持不变）
 * `headerSticky = true` ⇒ content 获得**有界高度**（[PageScaffold] 内 `Column(Modifier.weight(1f))`），
 * 故滚动放在 content 内部的各页签（`verticalScroll` 挂在 fillMaxSize 的分支根 Column 上），
 * [PageScaffold] 的 `modifier` **不带** `verticalScroll`（全仓唯一在用的吸顶头用例）。
 * 页签槽填充：`weight(1f)` 挂 AnimatedContent 自身（直接子 of 吸顶 Column），
 * 分支根 fillMaxSize 撑满——🔴 分支根上的 weight 经接收者链泄漏到外层 ColumnScope
 * 不被 EnterExit Exit 消费（勿再挂回分支根）。
 *
 * ## 状态订阅
 * 门面装配是惰性的（仅打开页面不装配、不落盘、不生成 bridge-config.json）；status 走
 * [LarkBridgeViewModel.statusFlow] 的 collectAsState 订阅（变更即达），logs 走 [LarkBridgeViewModel.logs]
 * 的 LogBus 引用；[LarkBridgeViewModel.pollStatus] 仅作降级/兼容读。
 *
 * ## 视觉约定
 * - 页签切换经 [AnimatedContent] 过渡（fadeIn standard + 温和上浮 fadeOut fast；页签平级无方向序，
 *   不做方向感 slide——那是 PageHomeContent 页面注册表专属）；
 * - 配置校验 issues 改 errorContainer 容器条（对齐 ShellToolsPage banner 先例），不再裸铺红字；
 * - 全部页签顶层 Column 统一 `spacedBy(Dimens.space14)` 呼吸感（卡距档 space14）。
 *
 * ## 零白名单
 * 间距 [Dimens]、卡片走 `:ui` 的 SectionCard（tonal）/TonalCard、动效走 AppMotion，本页无任何
 * 裸 dp 字面量、裸动画规格、浮起卡片、Material 形状引用、列表滚动动画或裸 alpha 值。
 */
@Composable
fun LarkBridgePage() {
    val vm = LarkBridgeViewModel.getViewModel()

    val draft by vm.draft.collectAsState()
    val issues by vm.issues.collectAsState()
    val busy by vm.busy.collectAsState()
    val busyAction by vm.busyAction.collectAsState()
    val startResult by vm.startResult.collectAsState()
    val applyResult by vm.applyResult.collectAsState()
    val banner by vm.banner.collectAsState()
    val probe by vm.probe.collectAsState()
    val registerState by vm.registerState.collectAsState()
    val pendingImport by vm.pendingImport.collectAsState()

    val scope = rememberCoroutineScope()
    // 状态订阅：store.status 本就是 StateFlow——1Hz while+delay
    // 轮询纯多余（每秒空转 + 状态变更最坏 1s 延迟）。直接 collectAsState 订阅：
    // 变更即达（零延迟）+ 零空转。logs 同理（LogBus 引用恒定，快照足够）。
    var snapshot by remember { mutableStateOf<BridgeStatusSnapshot?>(null) }
    val statusFlow = vm.statusFlow()
    if (statusFlow != null) {
        val collected by statusFlow.collectAsState()
        LaunchedEffect(collected) { snapshot = collected }
    }
    var logs by remember { mutableStateOf<LogBus?>(null) }
    LaunchedEffect(Unit) { logs = vm.logs() }
    var tab by rememberSaveable { mutableStateOf(0) }
    val running = snapshot?.running ?: false

    // 测试消息流状态（外提共享）：hero「测试消息」与操作区折叠区同源——
    // chatId 空时点 hero 钮不发送、转为展开操作区引导填写（会话级记忆，切页签不丢）
    var testExpanded by rememberSaveable { mutableStateOf(false) }
    var testChatId by rememberSaveable { mutableStateOf("") }
    var testMessage by rememberSaveable { mutableStateOf("Toolbelt 桥接测试消息") }

    // chatId 焦点票据——hero「测试消息」在 chatId 空时点按，除展开操作区外
    // 递票让焦点直落目标会话输入框（防静默展开无引导）
    var chatIdFocusTicket by rememberSaveable { mutableStateOf(0) }

    // 配置页聚焦票据——驾驶舱向导「去填写」深链快速上手卡并聚焦首个空基础字段
    var configFocusTicket by rememberSaveable { mutableStateOf(0) }

    // 统计签任务明细展开票据——驾驶舱最近任务「全部记录 →」切统计签
    // 并递票展开「任务明细」卡（configFocusTicket 同款先例；rememberSaveable 会话级）。
    var usageStatsFocusTicket by rememberSaveable { mutableStateOf(0) }

    // AI 端点就绪态接线：快速开始第 0 步与缺端点提示条数据源——
    // 订阅 VM 的端点就绪 StateFlow（发布点 = 端点快照单点刷新），端点配置变更
    // 随下一次快照刷新即时重组（collectAsState，组合期零挂起读）；
    // 「去配置 AI」经 :core 中立槽 GoAiSettingsHook（宿主 AppViewModel wire 切
    // AI 设置页——GoSettingsHook 同款配方；未接线时按钮点击静默 no-op）。
    val aiEndpointReady by vm.aiEndpointReady.collectAsState()
    val onGoToAiSettings = remember {
        {
            com.jiaoay.easy_dev_toolbox.core.navigation.GoAiSettingsHook.offer()
            Unit
        }
    }

    // 底部真穿透 opt-in（bottomPenetration=true = 包裹层移除底部玻璃带让位，
    // 下方各页签滚动容器以 contentPadding 消费 LocalScaffoldGlassInsets 底值）
    PageScaffold(title = "飞书桥接", headerSticky = true, bottomPenetration = true,
        actions = {
            // 三管理钮上页头（可发现性）——
            // 导入/导出是全生命周期动作（备份/迁移/手编 JSON 唯一 UI 入口）。图标钮
            // 带 hover 气泡（配方单源 = widget/interactions/HoverTooltipBox），
            // 导入/导出 busy 置灰带 disabledReason。
            HeaderActionIconButton(
                // icon+字语义：
                // 导入 = 向下入托（FILE_DOWNLOAD）、导出 = 向上出托（FILE_UPLOAD）
                icon = MaterialSymbols.FILE_DOWNLOAD,
                desc = "导入配置 JSON",
                label = "导入",
                enabled = !busy,
                disabledReason = if (busy) "桥接操作进行中" else null,
                onClick = { scope.launch { pickJsonFile(save = false, title = "导入配置 JSON")?.let(vm::requestImport) } },
            )
            HeaderActionIconButton(
                icon = MaterialSymbols.FILE_UPLOAD,
                desc = "导出配置 JSON",
                label = "导出",
                enabled = !busy,
                disabledReason = if (busy) "桥接操作进行中" else null,
                onClick = { scope.launch { pickJsonFile(save = true, title = "导出配置 JSON")?.let(vm::exportJson) } },
            )
            HeaderActionIconButton(
                icon = MaterialSymbols.FOLDER_OPEN,
                desc = "打开配置目录",
                label = "打开目录",
                onClick = { openInFileManager(vm.dataRoot) },
            )
        }) {
        // 首进 loading：桥接装配（种子刷+DDL+文件锁）已下放 IO——
        // 在途给骨架反馈而非冻结白屏。装配通常 <1s，非首次进入 isAssembled 恒真零开销。
        val assembling by vm.assembling.collectAsState()
        if (assembling) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.iconHero),
                    )
                    Spacer(modifier = Modifier.height(Dimens.space8))
                    Text(
                        "正在装配桥接…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@PageScaffold
        }
        OperationBar(
            running = running,
            busy = busy,
            busyAction = busyAction,
            snapshot = snapshot,
            dryRun = draft.connection.dryRun,
            startResult = startResult,
            applyResult = applyResult,
            banner = banner,
            probe = probe,
            testExpanded = testExpanded,
            testChatId = testChatId,
            testMessage = testMessage,
            // 🔴 导入 JSON 先弹 L2 危险确认——选好文件只置挂起态，
            //    确认（vm::confirmImport）才走真实导入覆盖（覆盖不可逆）；取消（vm::cancelImport）零副作用。
            //    「已修改 N 项」复用配置页 @modified 徽标同源口径（P5FieldGroups.modifiedIds）。
            pendingImport = pendingImport,
            modifiedCount = remember(draft) { P5FieldGroups.modifiedIds(draft).size },
            onTestExpandedChange = { testExpanded = it },
            chatIdFocusTicket = chatIdFocusTicket,
            onSendTest = { vm.onSendTest(testChatId, testMessage) },
            onTestChatIdChange = { testChatId = it },
            onTestMessageChange = { testMessage = it },
            onRestart = vm::onRestart,
            onConfirmImport = vm::confirmImport,
            onCancelImport = vm::cancelImport,
            onClearBanner = { vm.clearBanner() },
            // 预检结果常驻条行尾关闭钮——手动处置 probe（banner 5s 链已解耦）
            onClearProbe = vm::clearProbe,
        )

        // 页签：哑光胶囊分段控件
        // （.seg/.si——容器胶囊底 + 选中项 surfaceDeep 高亮块，零动画）。
        // （「路由」页签已删——routes 数据体系已退役，四签：驾驶舱/配置/日志/统计；勿加回路由签）
        SegmentedControl(
            options = listOf("驾驶舱", "配置", "日志", "统计"),
            selectedIndex = tab,
            onSelect = { tab = it },
        )

        // 页签切换过渡（PageHomeContent 页面级先例的同构收敛；双帧期各页签 scroll state 独立无冲突）
        // 🔴 weight 归位：weight(1f) 挂在
        //    AnimatedContent 自身（content scope 直接子 = 吸顶骨架 Column，合法且生效）。
        //    挂在分支根各自 Modifier.weight(1f) 经隐式接收者链解析到外层
        //    PageScaffold 的 ColumnScope 才通过编译，但布局上分支根的直接父是
        //    EnterExit 自定义 Layout，ColumnParentData 不被消费 → weight 全部失效
        //    （各页签 wrap 不填槽；日志签 Box 只占 LogConsolePanel 自身 heightIn）。
        //    分支根改 fillMaxSize：在 EnterExit 有界约束（weight 生效后）下撑满页签槽。
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(animationSpec = AppMotion.standard()) +
                    slideInVertically(animationSpec = AppMotion.standard()) { it / 16 }) togetherWith
                    fadeOut(animationSpec = AppMotion.fast())
            },
            label = "larkTabTransition",
        ) { currentTab ->
            // 底部真穿透：吸顶页的底部玻璃带让位不再由 PageScaffold
            // 包裹层承担，改由各页签滚动容器以 contentPadding 消费（padding 挂 verticalScroll
            // 之后 = 滚动 contentPadding 语义）——内容滚出视口时滑入状态栏玻璃下被 blur 折射。
            // 吸顶页头（标题/操作区/分段控件）仍由包裹层顶部与侧边让位保护，不参与穿透。
            val glassBottom = LocalScaffoldGlassInsets.current.calculateBottomPadding()
            when (currentTab) {
                // 驾驶舱首屏：连接 hero 横幅 + 快速开始 + grid2 双卡 + 快捷操作 +
                // 运行状态全量行（原「运行」页签并入——BridgeStatusPanel 保留在此，不再单独占页签）。
                0 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = glassBottom),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space14),
                ) {
                    // 进行中确认只读视图——1Hz 快照变化时拉一次（纯内存快照，
                    // 与 CockpitPanel 内 audit 重读同款事件驱动模式；挂起网络调用不进采样循环）
                    var pendingViews by remember {
                        mutableStateOf<List<com.jiaoay.lark.bridge.confirm.PendingConfirmView>>(emptyList())
                    }
                    LaunchedEffect(snapshot) { pendingViews = vm.pendingConfirmations() }
                    // 表情回显徽标数据源（真正触发点在 CockpitPanel audit 重读后——
                    // 经 onRefreshTaskReactions 回调对 recent 的锚点拉取；此处只订观察值）
                    val taskReactions by vm.taskReactions.collectAsState()
                    CockpitPanel(
                        config = draft,
                        snapshot = snapshot,
                        configFileExists = vm.configFileExists,
                        dataRoot = vm.dataRoot,
                        busy = busy,
                        busyAction = busyAction,
                        aiEndpointReady = aiEndpointReady,
                        onGoToAiSettings = onGoToAiSettings,
                        pendingViews = pendingViews,
                        taskReactions = taskReactions,
                        onQueryReadCount = { messageId -> vm.messageReadCount(messageId) },
                        onRefreshTaskReactions = vm::refreshTaskReactions,
                        onGoToConfig = { tab = 1 },
                        // 向导「去填写」= 切配置页签 + 递聚焦票据（ConfigEditor focusTicket
                        // 消费）；onGoToConfig 保持只切页签（快捷操作行「去配置」共用，不聚焦）
                        onGoFillBasic = {
                            tab = 1
                            configFocusTicket++
                        },
                        onStart = vm::onStart,
                        onStop = vm::onStop,
                        onProbe = vm::onProbe,
                        onSendTest = {
                            if (testChatId.isNotBlank()) {
                                vm.onSendTest(testChatId, testMessage)
                            } else {
                                // chatId 未填 → 展开操作区 + 焦点直落输入框 +
                                // 引导文案（防静默展开，用户不知道下一步该干什么）
                                testExpanded = true
                                chatIdFocusTicket++
                            }
                        },
                        onRegisterApp = vm::onStartRegister,
                        // 最近任务「全部记录 →」= 切统计签 + 递明细展开票据
                        onAllRecords = {
                            tab = 3
                            usageStatsFocusTicket++
                        },
                    )
                    BridgeStatusPanel(snapshot = snapshot)
                }

                1 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = glassBottom),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space14),
                ) {
                    if (issues.isNotEmpty()) {
                        // 校验失败：errorContainer 容器条（总结 + 明细经 details 槽收在条内），不再裸铺红字
                        // 明细行经 details 槽收在容器条内、行距 space2；行文案 = 中文 label（BridgeFields.ALL
                        // lookup，找不到再回落 fieldId——「区域：必填」文案）
                        StatusBanner(
                            status = StatusBannerStatus.Error,
                            text = "配置校验未通过（${issues.size} 处），已暂停落盘：",
                            details = {
                                issues.forEach { (fieldId, message) ->
                                    val field = BridgeFields.ALL.firstOrNull { it.id == fieldId }
                                    val label = field?.label ?: fieldId
                                    Text(
                                        text = "· $label：$message",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            },
                        )
                    }
                    // S18/S21/S22：跨字段派生的权限与安全实时提示（消费 BridgeSecurityCardLogic 纯函数）
                    SecurityHintsCard(config = draft, confirmStatus = snapshot?.confirm)
                    // L0 黄条：桥接运行中改过 RESTART 字段——当前连接仍用旧值，
                    // 提示去驾驶舱重启（「N 项待重启生效」文案；落盘后 applyResult.RestartRequired
                    // 已由 OperationBar 折叠区承载逐字段明细，此处是配置签内的常驻可见形态）。
                    // trailing 内联「立即重启」钮（R13）：重启唯一动作入口若只在默认折叠的
                    //    OperationBar 内，黄条指路文字即断链——就地收纳动作而非纯文字指引
                    val pendingRestart = applyResult is ConfigApplyResult.RestartRequired && running
                    if (pendingRestart) {
                        val restartFields = (applyResult as ConfigApplyResult.RestartRequired).restartFields
                        StatusBanner(
                            status = StatusBannerStatus.Warning,
                            text = "${restartFields.size} 项待重启生效，当前连接仍用旧值 → 去驾驶舱重启",
                            trailing = {
                                OutlinedButton(
                                    onClick = vm::onRestart,
                                    enabled = !busy,
                                ) {
                                    Text("立即重启")
                                }
                            },
                        )
                    }
                    // 五分区：机器人主卡（身份面：人设/systemPrompt/groupMode +
                    // 「本地 AI · agent-first」pill）→ 能力清单卡（静态工具名 pill 平铺，主卡正下方）
                    // → 工具条（搜索/@modified/@restart + 过滤模式）→ 六分区平铺（ZONES，默认收起）。
                    // 区块顺序：主卡与能力清单同属身份面（DOM 序为准）。
                    BotIdentityCard(
                        config = draft,
                        issues = issues,
                        onCommit = { field, value -> vm.updateField(field, value) },
                    )
                    // 能力卡 scope 验证区块（L1/L2 恒注册——授权闸在后台 scope；
                    // draft 直接下发 + 探测三态内存态；GrantProbe 接缝见其类头）
                    val grantVerifyStates by vm.grantVerifyStates.collectAsState()
                    BotCapabilityCard(
                        config = draft,
                        verifyStates = grantVerifyStates,
                        onVerify = { grant, docUrl -> vm.onVerifyGrant(grant, docUrl) },
                        bridgeRunning = running,
                    )
                    ConfigEditor(
                        config = draft,
                        issues = issues,
                        onCommit = { field, value -> vm.updateField(field, value) },
                        focusTicket = configFocusTicket,
                    )
                    // G-C：check 结果回显（CLI `check` 的 UI 等价物，全字段自解释 + appSecret 前 4 后 4 掩码）
                    ConfigCheckPanel(config = draft, issues = issues)
                }

                2 -> Box(modifier = Modifier.fillMaxSize()) {
                    LogConsolePanel(logs = logs, console = draft.observability.logConsole)
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = glassBottom),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space14),
                ) {
                    // O2-O12：使用统计 + 发送者统计 + 图表（只读 audit.jsonl / sender_stats.jsonl）
                    // access/onAccessChange 接通详情页白黑快捷动作
                    // （draft→debounce→落盘→HOT 现成链路零新写路径；桥接运行中编辑 HOT 生效）
                    UsageStatsPanel(
                        dataRoot = vm.dataRoot,
                        access = draft.access,
                        onAccessChange = { next -> vm.updateConfig { it.copy(access = next) } },
                        // 驾驶舱「全部记录 →」递票展开任务明细卡
                        detailExpandTicket = usageStatsFocusTicket,
                    )
                }
            }
        }
    }

    // 扫码建应用流程对话框（阶段四）：四态渲染，关闭只收 UI 不杀 SDK 轮询
    RegisterAppDialog(
        state = registerState,
        onRetry = vm::onStartRegister,
        onDismiss = vm::onDismissRegister,
    )
}

/**
 * 页头图标动作钮：hover 气泡配方单源 = widget/interactions/HoverTooltipBox
 * （延迟/isPersistent/手动驱动由共享件承载），图标/着色/contentDescription
 * 走 ShellToolsPage 页头钮同款语言。
 * 置灰（busy）时 disabledReason 经同一 tooltip 槽说明（禁止静默置灰纪律）——
 * hover 检测必须走 .hoverable 挂钮容器：clickable 的 hover 发射被 enabled
 * 门控（disabled 钮不发 hover），hoverable 恒发射，置灰原因气泡才可达。
 */
@Composable
private fun HeaderActionIconButton(
    icon: String,
    desc: String,
    onClick: () -> Unit,
    label: String = desc,
    enabled: Boolean = true,
    disabledReason: String? = null,
) {
    // .ic-btn 规格：页头管理钮 =
    // **带字小件**（icon 14dp + 文字 labelSmall(11) + raised 底 + hairline 描边 +
    // small 圆角 + padding 5×12）——非 icon-only 形态
    // （文字从 tooltip 上浮到钮面；tooltip 保留承载 disabledReason）。
    HoverTooltipBox(
        tooltip = {
            Text(text = disabledReason ?: desc, style = MaterialTheme.typography.labelSmall)
        },
        // 页头动作区贴窗右缘：Below 水平钳制防长 disabledReason 越缘
        positioning = TooltipAnchorPosition.Below,
    ) { interactionSource ->
        Row(
            modifier = Modifier
                .clip(AppShapes.small)
                .background(
                    LocalToolboxSurfaceColors.current.surfaceRaised,
                    AppShapes.small,
                )
                .border(Dimens.glassStrokeHairline, LocalToolboxSurfaceColors.current.borderHairline, AppShapes.small)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
                .padding(horizontal = Dimens.space12, vertical = Dimens.headerBtnPaddingV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            AppSymbolIcon(
                icon = icon,
                contentDescription = desc,
                size = Dimens.progressInline /* 14dp = .ic-btn .ico */,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
