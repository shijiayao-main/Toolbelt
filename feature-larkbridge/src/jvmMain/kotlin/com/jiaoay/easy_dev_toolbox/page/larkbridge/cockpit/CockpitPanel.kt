package com.jiaoay.easy_dev_toolbox.page.larkbridge.cockpit

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.page.larkbridge.BusyAction
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalDayBattle
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import com.jiaoay.easy_dev_toolbox.page.larkbridge.stats.UsageBarChart
import com.jiaoay.easy_dev_toolbox.page.larkbridge.stats.UsageStats
import com.jiaoay.easy_dev_toolbox.page.larkbridge.status.carrier
import com.jiaoay.easy_dev_toolbox.page.larkbridge.status.color
import com.jiaoay.easy_dev_toolbox.page.larkbridge.status.label
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.lark.bridge.confirm.ConfirmService
import com.jiaoay.lark.bridge.confirm.PendingConfirmView
import com.jiaoay.lark.bridge.observe.AuditEntryJson
import com.jiaoay.lark.bridge.observe.BridgeConnectionStatus
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.lark.bridge.observe.JsonlTailReader
import com.jiaoay.lark.gateway.ReactionBrief
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/** 确认区块发起者 openId 的显示截断（前 8 位——与 RegisterAppDialog 的 appId.take(8) 同款回落口径）。 */
private const val SENDER_DISPLAY_CHARS = 8

@Composable
fun CockpitPanel(
    config: BridgeConfig,
    snapshot: BridgeStatusSnapshot?,
    configFileExists: Boolean,
    dataRoot: Path,
    busy: Boolean,
    busyAction: BusyAction?,
    onGoToConfig: () -> Unit,
    onGoFillBasic: () -> Unit = {},
    onStart: () -> Unit,
    onStop: () -> Unit,
    onProbe: () -> Unit,
    onSendTest: () -> Unit,
    onRegisterApp: (() -> Unit)?,
    aiEndpointReady: Boolean = false,
    /** 「去配置」直达 AI 设置页（页面经 :core 中立槽 GoAiSettingsHook——宿主 wire 切 AI 设置页）。 */
    onGoToAiSettings: () -> Unit = {},
    pendingViews: List<PendingConfirmView> = emptyList(),
    /** 最近任务表情回显（行尾「👍 ×N」徽标数据源；key = audit 的 userMessageId）。 */
    taskReactions: Map<String, List<ReactionBrief>> = emptyMap(),
    onAllRecords: () -> Unit = {},
    /** 已读人数查询（「已读 N 人」；确认卡已发送时由本面板异步触发——事件驱动不进采样循环）。 */
    onQueryReadCount: (suspend (String) -> Int?)? = null,
    onRefreshTaskReactions: ((List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEasyToolboxColors.current

    // 后台 checklist 勾选态：rememberSaveable 会话级（导航切页不丢；App 重启重置——向导本来就该重验）
    var checked: Set<Int> by rememberSaveable { mutableStateOf(emptySet<Int>()) }

    var auditAll by remember { mutableStateOf<List<AuditEntryJson>>(emptyList()) }
    // audit 读取失败可见化——「读到 0 条」≠「读取抛异常」：
    //    runCatching{...}.getOrDefault(emptyList()) 会把两者折叠成同一假空态；失败态单独
    //    记 message，grid2 双卡在空态分支之前渲染内联 error 行 + 重试钮（数据目录权限等
    //    环境问题不再伪装成「暂无任务记录」）。
    var auditLoadError by remember { mutableStateOf<String?>(null) }
    // 重试票：+1 触发下方 LaunchedEffect 重读（不依赖 snapshot 计数变化）
    var auditRetryTick by remember { mutableStateOf(0) }
    LaunchedEffect(snapshot?.counters?.completed, snapshot?.counters?.failed, auditRetryTick) {
        // 任一任务完成/失败计数变化时重读（1Hz 轮询的 snapshot 驱动，无独立定时器）；
        // 一次 IO 同时喂最近任务 / 今日统计 / 迷你柱状图三处（聚合全走 UsageStats 纯函数）
        val loaded = withContext(Dispatchers.IO) {
            runCatching { JsonlTailReader.readAuditAll(dataRoot.toString()).records }
        }
        loaded.fold(
            onSuccess = { records ->
                auditAll = records
                auditLoadError = null
            },
            onFailure = { e ->
                // 失败保留旧数据（不清空——诚实显示「读取失败 + 旧快照」优于假空态）
                auditLoadError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            },
        )
    }
    val recent = remember(auditAll) {
        auditAll.sortedByDescending { it.ts }.take(5)
    }
    val nowMillis = remember(auditAll) { System.currentTimeMillis() }
    val today = remember(auditAll, nowMillis) { UsageStats.summarizeToday(auditAll, nowMillis) }
    val buckets = remember(auditAll, nowMillis) { UsageStats.aggregateByDay(auditAll, nowMillis) }

    // ── 表情回显刷新（audit 重读后事件驱动一次）──
    // 红线 3：挂起网络调用不进 1Hz 采样循环——只在本 LaunchedEffect（audit 集已变）触发；
    // 锚点取 recent 的 userMessageId（旧行无锚点 = null 被过滤，VM 侧再过滤空白 + 去重）。
    LaunchedEffect(auditAll) {
        onRefreshTaskReactions?.invoke(recent.mapNotNull { it.userMessageId })
    }

    // ── 「已读 N 人」查询 ──
    // 事件驱动（不进 1Hz 采样循环）：确认区块的每张 PENDING 卡已发送（cardMessageId 非空）时查一次；
    // 1Hz 快照驱动 pendingViews 变化时，新出现的 cardMessageId 再查（查过的 id 跳过——不重复打 API）。
    // 失败退避：失败即 remove 会允许下次快照重试，桥未运行/能力缺席
    //    时随 1Hz 快照形成每秒一次的重试循环。改失败计数：单卡失败 ≥ CockpitPanelLogic.READ_QUERY_MAX_FAILURES
    //    即永不再查（UI 恒显「已读 —」诚实缺席）；查询成功路径与事件驱动触发结构零改动。
    var readCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // 降普通 map：CE 原样重抛后（见下方 readCountSafely），被取消协程在查询点
    //    即时退出不再继续 forEach 余下卡片；且全部读改写都发生在组合调度器单线程上（LaunchedEffect
    //    重启的新旧两代只在挂起点交错、单次读改写非挂起不可分割）——synchronizedMap 的防御失去存在理由。
    val readQueryFailures = remember { mutableMapOf<String, Int>() }
    LaunchedEffect(pendingViews) {
        val query = onQueryReadCount ?: return@LaunchedEffect
        pendingViews.forEach { view ->
            val cardId = view.cardMessageId ?: return@forEach
            // 退避判定走 CockpitPanelLogic 纯函数（CockpitPanelLogicTest 直测背书）
            if (!CockpitPanelLogic.shouldRetryReadQuery(succeeded = cardId in readCounts, failureCount = readQueryFailures[cardId] ?: 0)) {
                return@forEach
            }
            // 🔴 协程取消不是查询失败——CE 原样重抛不烧退避额度（安全读取抽在
            //    CockpitPanelLogic.readCountSafely 供三态直测）。runCatching 吞 CE 会把
            //    LaunchedEffect(pendingViews) 重启竞态（pending 集结构变化即取消旧协程）误记为
            //    失败 +1，且被取消协程继续烧余下卡片额度——READ_QUERY_MAX_FAILURES=3 下两次
            //    重启竞态即可让健康卡永久「已读 —」。
            val count = CockpitPanelLogic.readCountSafely({ query(it) }, cardId)
            if (count != null) {
                readCounts = readCounts + (cardId to count)
            } else {
                // 失败计数 +1（≥ 上限即永不再查——退避常量在 CockpitPanelLogic）
                readQueryFailures[cardId] = (readQueryFailures[cardId] ?: 0) + 1
            }
        }
    }

    // 确认区块的剩余秒时钟锚 pendingViews：`nowMillis`（audit 重读锚点）在
    //    确认窗口期恰好冻结（audit 只在任务收尾写、确认是任务中途的挂起窗，数分钟无写入），
    //    不能当确认倒计时时钟。pendingViews 由页面层 1Hz 快照拉取（每秒换引用），
    //    时钟锚到它上面即随每帧自然重算——零新增协程零轮询（组合内纯取时，非 IO）。
    val pendingNowMillis = remember(pendingViews) { System.currentTimeMillis() }

    // 机器人自身 openId（表情徽标过滤用：Connected 态才可得——非 Connected 无法过滤 ⇒ 徽标不渲染）
    val botOpenId = (snapshot?.connection as? BridgeConnectionStatus.Connected)?.botOpenId

    // 一次性入场编排（PageDefault 模板）：首帧 false、组合后置 true，重组不重放
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = modifier.fillMaxWidth(),
        // 驾驶舱顶层卡距走卡距档 space14
        verticalArrangement = Arrangement.spacedBy(Dimens.space14),
    ) {
        // ── 1. 连接 hero 区（stagger 第 0 级）──
        // hero 形态：16dp 圆角无标题卡（AppShapes.glassPanel——状态即图形，无小节标题栏）；
        // 右侧同行按钮 = 启动|停止（随状态切换）+ 测试消息。
        // busy 小圈长在发起动作的按钮上（STARTING/STOPPING 驱动——与 OperationBar 同配方）。
        // 缺端点提示条内嵌 hero（与「已启动≠已连接」提示条同款位置——见 [ConnectionHeroArea] 尾部）。
        AnimatedVisibility(
            visible = entered,
            enter = fadeIn(animationSpec = AppMotion.staggered(0)) +
                slideInVertically(animationSpec = AppMotion.staggered(0)) { it / 16 },
        ) {
            TonalCard(
                shape = AppShapes.glassPanel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ConnectionHeroArea(
                    snapshot = snapshot,
                    busy = busy,
                    busyAction = busyAction,
                    onStart = onStart,
                    onStop = onStop,
                    onSendTest = onSendTest,
                    aiEndpointReady = aiEndpointReady,
                    onGoToAiSettings = onGoToAiSettings,
                )
            }
        }

        // ── 2. 进行中的确认区块（PENDING 非空才渲染——空表整块缺席；PENDING 卡本体不 patch）──
        // 位置：hero 之下、向导之上——进行中的确认是「需要立刻行动」的最高优先级信息
        // （行内容：工具名/发起者/剩余 02:31/已读 N 人）。
        if (pendingViews.isNotEmpty()) {
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(animationSpec = AppMotion.staggered(1)) +
                    slideInVertically(animationSpec = AppMotion.staggered(1)) { it / 16 },
            ) {
                PendingConfirmationsCard(
                    pendingViews = pendingViews,
                    readCounts = readCounts,
                    nowMillis = pendingNowMillis, // 时钟锚 pendingViews（1Hz 帧重算），见上方注释
                )
            }
        }

        // ── 3. 快速开始向导（stagger 第 1 级；有确认区块时顺延 stagger 但同为 1 档——240ms 封顶不破）──
        // 存续判定「后台清单未完成」驱动——扫码回填凭据后向导不再
        // 整卡消失，而是收成折叠态（凭据就绪且清单未勾且无首条消息）；freshUser 仍展开态直出。
        val quickStartSteps = CockpitLogic.steps(
            appId = config.connection.appId,
            appSecret = config.connection.appSecret,
            snapshot = snapshot,
            backendChecklistDone = checked.size >= CockpitLogic.BACKEND_CHECKLIST.size,
            aiEndpointReady = aiEndpointReady,
        )
        if (CockpitLogic.showQuickStart(
                appId = config.connection.appId,
                appSecret = config.connection.appSecret,
                configFileExists = configFileExists,
                backendChecklistDone = quickStartSteps.backendConfigured,
                firstMessageReceived = quickStartSteps.firstMessageReceived,
            )
        ) {
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(animationSpec = AppMotion.staggered(1)) +
                    slideInVertically(animationSpec = AppMotion.staggered(1)) { it / 16 },
            ) {
                QuickStartCard(
                    steps = quickStartSteps,
                    checked = checked,
                    onCheck = { idx, add ->
                        checked = if (add) checked + idx else checked - idx
                    },
                    onGoToConfig = onGoToConfig,
                    onGoFillBasic = onGoFillBasic,
                    onRegisterApp = onRegisterApp,
                    onGoToAiSettings = onGoToAiSettings,
                )
            }
        }

        // ── 4. grid2 双卡（stagger 第 2 级；2×30+180=240ms 整，严守封顶）──
        // 最近任务 | 今日统计 半宽双卡（卡距走卡距档 space14）
        AnimatedVisibility(
            visible = entered,
            enter = fadeIn(animationSpec = AppMotion.staggered(2)) +
                slideInVertically(animationSpec = AppMotion.staggered(2)) { it / 16 },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 双卡等高：右卡高度跟随左卡最近任务内容（grid2 语义）
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space14),
            ) {
                SectionCard(
                    title = "最近任务",
                    // 列配比 1.15fr:1fr（左宽右窄）——非双 weight 等宽
                    modifier = Modifier.weight(1.15f).fillMaxHeight(),
                    // 卡头「全部记录 →」——切统计签并展开任务明细卡
                    // （usageStatsFocusTicket 票据，configFocusTicket 同款先例）；
                    // 「近 5 条 · 」计数前缀（chead hint）
                    headerTrailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "近 5 条 · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                            )
                            Text(
                                text = "全部记录 →",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onAllRecords,
                                    )
                                    .padding(horizontal = Dimens.space4),
                            )
                        }
                    },
                ) {
                    if (auditLoadError != null) {
                        // 读取失败 ≠ 暂无记录——内联 error 行 + 重试钮（grid2 窄版，
                        // 数据目录权限等环境问题不再伪装成假空态）
                        Text(
                            text = "任务记录读取失败：$auditLoadError",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { auditRetryTick++ }) { Text("重试") }
                    } else if (recent.isEmpty()) {
                        // SectionCard(weight) 弹性卡内空态占满居中
                        EmptyStateView(
                            text = "暂无任务记录（在飞书发消息给机器人后自动记录）",
                            modifier = Modifier.fillMaxSize(),
                            fillParent = true,
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            recent.forEach { entry ->
                                RecentTaskRow(
                                    entry = entry,
                                    // 行尾表情徽标（过滤机器人自点后计数；旧行无锚点 = null 不渲染）
                                    reactions = entry.userMessageId?.let { taskReactions[it] },
                                    botOpenId = botOpenId,
                                )
                            }
                        }
                    }
                }
                SectionCard(
                    title = "今日统计",
                    // 右卡 1fr
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    // 「近 7 天」计数 hint（chead hint）
                    headerTrailing = {
                        Text(
                            text = "近 7 天",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                        )
                    },
                ) {
                    if (auditLoadError != null) {
                        // 同最近任务卡——读取失败内联 error 行 + 重试钮（同一数据源同一失败态）
                        Text(
                            text = "任务记录读取失败：$auditLoadError",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { auditRetryTick++ }) { Text("重试") }
                    } else if (auditAll.isEmpty()) {
                        EmptyStateView(
                            text = "暂无任务记录（agent 收到消息并执行后自动记录）",
                            modifier = Modifier.fillMaxSize(),
                            fillParent = true,
                        )
                    } else {
                        Column(
                            // 柱图区弹性占余高（flex:1 等高——UsageBarChart height 参数化直用容器约束）
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                        ) {
                            // 近 7 天迷你柱状图（弹性高——撑满卡内余高）
                            UsageBarChart(
                                buckets = buckets,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                height = Dp.Unspecified,
                            )
                            // 今日计数行（muted 统计行同位；语义换算成真实审计口径，不虚构）
                            val caption = buildString {
                                append("今日 ${today.total}")
                                append(" · 成功 ${today.success}")
                                append(" · 失败 ${today.failure}")
                                if (today.skipped > 0) append(" · 跳过 ${today.skipped}")
                            }
                            Text(
                                text = caption,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ── 5. 快捷操作（直接渲染，不参与 stagger 编排）──
        // 启动/停止已上 hero 行，本行只留非生命周期操作
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            OutlinedButton(onClick = onProbe, enabled = !busy) { Text("环境预检") }
            OutlinedButton(onClick = onGoToConfig) { Text("去配置") }
        }
    }
}

/**
 * 连接 hero 区：iconHero 状态图标 + 状态主行 + 副行（「状态即图形」）+ 右侧操作按钮行。
 * 图标七态穷尽映射（[BridgeConnectionStatus] sealed，编译器背书）——云系列与
 * 「桥接=长连接到飞书云」心智吻合；SwapHoriz 是导航图标不复用（避免身份混淆）。
 *
 * 按钮 busy 门控（P4 修复语义延续）：启动/停止合并为单钮随状态切换（enabled = !busy——
 * running 时即停止钮、未运行时即启动钮，两侧语义各自满足）；busy 小圈长在发起动作的按钮上
 * （STARTING/STOPPING 驱动）；测试消息 enabled = running && !busy，chatId 为空时由
 * [onSendTest] 的调用方（LarkBridgePage）转为展开操作区引导填写。
 */
@Composable
private fun ConnectionHeroArea(
    snapshot: BridgeStatusSnapshot?,
    busy: Boolean,
    busyAction: BusyAction?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSendTest: () -> Unit,
    aiEndpointReady: Boolean,
    onGoToAiSettings: () -> Unit,
) {
    val colors = LocalEasyToolboxColors.current
    val connection = snapshot?.connection

    val icon: String
    val iconTint: Color
    // 昼战（design-doc.md §5.3，dayBattleTint）：语义图标直落昼战页面底破图形线——坐语义 container 档；
    // 非昼战原值逐位不变
    when (connection) {
        is BridgeConnectionStatus.Connected -> {
            icon = MaterialSymbols.CLOUD_DONE
            iconTint = dayBattleTint(colors.success, colors.successContainer)
        }

        null, BridgeConnectionStatus.Idle -> {
            icon = MaterialSymbols.CLOUD_OFF
            iconTint = LocalToolboxSurfaceColors.current.onSurfaceFaint
        }

        BridgeConnectionStatus.Connecting -> {
            icon = MaterialSymbols.CLOUD_QUEUE
            iconTint = dayBattleTint(colors.warning, colors.warningContainer)
        }

        BridgeConnectionStatus.Reconnecting, is BridgeConnectionStatus.Draining -> {
            icon = MaterialSymbols.SYNC
            iconTint = dayBattleTint(colors.warning, colors.warningContainer)
        }

        is BridgeConnectionStatus.Disconnected -> {
            icon = MaterialSymbols.CLOUD_OFF
            iconTint = dayBattleTint(MaterialTheme.colorScheme.error, colors.errorContainerFixed)
        }

        is BridgeConnectionStatus.Blocked -> {
            icon = MaterialSymbols.BLOCK
            iconTint = dayBattleTint(MaterialTheme.colorScheme.error, colors.errorContainerFixed)
        }
    }

    val statusColor = connection?.color() ?: MaterialTheme.colorScheme.onSurfaceVariant
    // Draining（停止排空中）显示过渡态文案——
    //    running 已翻 false、Draining 未结束的窗口里打「未运行（排空中）」自相矛盾。
    val mainLine = when {
        connection is BridgeConnectionStatus.Draining -> "正在停止…（排空 ${connection.activeTasks} 个在途任务）"
        snapshot?.running == true -> "运行中 · ${connection?.label() ?: "…"}"
        else -> "未运行（${connection?.label() ?: "空闲"}）"
    }
    val subLine = when (connection) {
        is BridgeConnectionStatus.Connected -> {
            // 连接建立时刻（connectedSinceMillis 是快照真实字段，
            // 1Hz publishStatus 接线后运行中持续刷新）。语义诚实：不伪造「心跳」——这是「连接自」。
            val since = snapshot?.connectedSinceMillis
                ?.let {
                    java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                }
            val base = when {
                (snapshot?.counters?.intakeReceived ?: 0L) > 0 -> "已收到 ${snapshot.counters.intakeReceived} 条消息"
                else -> "等待首条消息"
            }
            if (since != null) "$base · 连接自 $since" else base
        }
        null, BridgeConnectionStatus.Idle -> "点「启动」连接飞书"
        BridgeConnectionStatus.Reconnecting -> "长连接断开，自动重连中…（期间消息可能延迟）"
        else -> "长连接状态流转中，请稍候"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
    ) {
        AppSymbolIcon(
            icon = icon,
            contentDescription = null,
            // fs-28——iconHero 档与载入圈档分离
            size = Dimens.heroIcon,
            tint = iconTint,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            // 昼战（design-doc.md §5.3#4）：连接态语义色（success/warning/error）直落昼战页面底
            // 全破正文线——主行坐语义 container 面的胶囊（槽对呈现，桥接态 carrier 单源）；
            // 非昼战裸字现状逐位不变
            val heroCarrier = connection?.carrier()
            if (LocalDayBattle.current && heroCarrier != null && heroCarrier.second != null) {
                PillLabel(
                    text = mainLine,
                    container = heroCarrier.second!!,
                    contentColor = heroCarrier.first,
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text(
                    text = mainLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor,
                )
            }
            Text(
                text = subLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ── 右侧操作按钮（hero 行：pri 启停 + sec 测试消息）──
        val running = snapshot?.running == true
        Button(
            onClick = if (running) onStop else onStart,
            enabled = !busy,
        ) {
            if (busyAction == BusyAction.STARTING || busyAction == BusyAction.STOPPING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.progressInline),
                    strokeWidth = Dimens.strokeThin,
                )
            } else {
                AppSymbolIcon(
                    icon = if (running) MaterialSymbols.STOP else MaterialSymbols.PLAY_ARROW,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                )
                Text(if (running) "停止" else "启动")
            }
        }
        // AppButton + disabledReason（禁静默置灰——同页 OperationBar 发送钮合规范例）；
        // 启停钮的 busy 由行内 spinner 自解释，不入本收口
        AppButton(
            label = "测试消息",
            busy = false,
            onClick = onSendTest,
            kind = AppButtonKind.Secondary,
            leadingIcon = MaterialSymbols.SEND,
            enabled = running && !busy,
            disabledReason = when {
                busy -> "有操作进行中，稍候再试"
                !running -> "先启动桥接，再发测试消息"
                else -> null
            },
        )
    }

    // agent 能力链健康度（配置态单行双段 pill）：调度段与向导第 0 步/缺端点黄条共用同一
    // 端点数据源，合并单行避免第三处重复；运行期计数归状态页签（BridgeStatusPanel），分工勿互吞。
    AgentChainHealthRow(
        dispatchReady = aiEndpointReady,

    )

    // 「已启动」≠「已连接」顺序陷阱提示（运行中但未连接时）——warning 容器条
    if (snapshot?.running == true && connection !is BridgeConnectionStatus.Connected) {
        StatusBanner(
            status = StatusBannerStatus.Warning,
            text = "「已启动」≠「已连接」：请等上方显示「已连接」后再去飞书后台修改订阅方式（顺序陷阱）。",
        )
    }

    // 缺端点提示条——与上方顺序陷阱条同款位置（hero 内嵌、hero 行之下）
    AiEndpointHintCard(
        visible = !aiEndpointReady,
        onGoToAiSettings = onGoToAiSettings,
    )
}

/**
 * 快速开始向导卡（[CockpitLogic] 五步 + checklist 勾选 + 顺序陷阱警告）。
 * 第 0 步 = AI 端点就绪（数据源 [onGoToAiSettings] 所绑的
 * `aiEndpointReady` 页面参数；未就绪时「去配置」直达 AI 设置页——把失败路径变引导路径）。
 * 第 1 步「去填写」走 [onGoFillBasic] 深链（切配置页签 + 聚焦首个空基础字段），
 * 与通用「去配置」（[onGoToConfig]，快捷操作行/页头仍用）分道——聚焦票据只归向导步骤。
 */
@Composable
private fun QuickStartCard(
    steps: CockpitLogic.QuickStartSteps,
    checked: Set<Int>,
    onCheck: (Int, Boolean) -> Unit,
    onGoToConfig: () -> Unit,
    onGoFillBasic: () -> Unit,
    onRegisterApp: (() -> Unit)?,
    onGoToAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEasyToolboxColors.current
    val doneCount = listOf(
        steps.aiEndpointReady,
        steps.credentialsReady,
        steps.started,
        steps.backendConfigured,
        steps.firstMessageReceived,
    ).count { it }

    SectionCard(
        title = if (steps.credentialsReady) "快速开始 · 后台配置未完成" else "快速开始：搭建你的飞书机器人",
        modifier = modifier.fillMaxWidth(),
        // 向导存续折叠化：纯新用户（凭据空）首见展开态；
        // 凭据就绪（扫码回填/手填）后收成折叠态，标题动态「快速开始 · 后台配置未完成」，
        // 7 项后台清单与顺序陷阱警告点开可查（不再随扫码成功整卡消失）。
        collapsible = true,
        initialExpanded = !steps.credentialsReady,
        headerTrailing = {
            // 「x/5 步」进度徽标（原内容区文字行折叠后不可见——移入 headerTrailing 槽
            // 折叠/展开两态恒可见）
            // .zrow 形态：
            // zc 色档 = primary 字 + selectedWash 洗底（.zc color primary + background
            // primary×wash-selected——非 success 绿档）
            PillLabel(
                text = "$doneCount/5 步",
                container = MaterialTheme.colorScheme.primary.copy(
                    alpha = com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha.selectedWash,
                ),
                contentColor = MaterialTheme.colorScheme.primary,
            )
            // badge「N 项清单待勾」（.badge = warn×warn-c 琥珀非灰 mute；位置 = zc 之后行尾）
            if (checked.size < CockpitLogic.BACKEND_CHECKLIST.size) {
                PillLabel(
                    text = "${CockpitLogic.BACKEND_CHECKLIST.size - checked.size} 项清单待勾",
                    container = colors.warningContainer,
                    contentColor = colors.warning,
                )
            }
            // zn 副题（稿 :362 zn 紧随标题、在 zc 之前）
            if (steps.credentialsReady) {
                Text(
                    text = "扫码已回填凭据",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            // 出口定位副行：本页是智能体能力的对外出口，两层 agent 分工一句话讲透
            // （「本地 AI 调度 / CLI 执行后端」一对词与 BridgeConfig 代码口径单源）。
            Text(
                text = "本页把本机智能体接进飞书——日常消息由本地 AI 调度应答，重任务委托 CLI 执行后端",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StepRow(
                done = steps.aiEndpointReady,
                text = "0. 为本地 AI 调度配置端点（AI 设置页）",
                actionLabel = "去配置",
                onAction = onGoToAiSettings,
            )
            StepRow(
                done = steps.credentialsReady,
                text = "1. 填入飞书应用的 App ID 与 App Secret",
                actionLabel = "去填写",
                // 深链聚焦（切配置页签 + 聚焦快速上手卡首个空基础字段），不走 onGoToConfig
                onAction = onGoFillBasic,
            )
            if (onRegisterApp != null) {
                StepRow(
                    done = steps.credentialsReady,
                    text = "（或）扫码一键创建应用并自动填入凭据",
                    actionLabel = "扫码建应用",
                    onAction = onRegisterApp,
                )
            }
            StepRow(
                done = steps.started,
                text = "2. 点「启动」，等状态显示「已连接」（保持 App 开着）",
                actionLabel = null,
                onAction = null,
            )
            if (CockpitLogic.orderTrapWarningVisible(steps)) {
                StatusBanner(
                    status = StatusBannerStatus.Warning,
                    text = "先完成上一步再去飞书后台改订阅方式：后台保存那一刻本地必须已「已连接」，否则保存失败。",
                )
            }
            Text(
                text = "3. 飞书开放平台后台配置（逐项勾选）：",
                style = MaterialTheme.typography.bodyMedium,
            )
            CockpitLogic.BACKEND_CHECKLIST.forEachIndexed { idx, item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                ) {
                    Checkbox(checked = idx in checked, onCheckedChange = { onCheck(idx, it) })
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StepRow(
                done = steps.firstMessageReceived,
                text = "4. 在飞书给机器人发一条消息（如 ping）验证收发",
                actionLabel = null,
                onAction = null,
            )
            if (steps.aiEndpointReady &&
                steps.credentialsReady &&
                steps.started &&
                steps.backendConfigured &&
                !steps.firstMessageReceived
            ) {
                StatusBanner(
                    status = StatusBannerStatus.Warning,
                    text = "连上了却收不到消息？回查第 3 步的事件订阅是否已切「使用长连接接收事件」并保存。",
                )
            }
        }
    }
}

/**
 * 缺端点提示条：本地 AI 端点未配置时，警告「消息将收到『本地 AI 未就绪』显式错误回复」
 * 并给「去配置」直达 AI 设置页。
 *
 * 常驻条件渲染、**无 UI 侧冷却**：提示条的显隐纯由 [visible]（= `!aiEndpointReady`）驱动，
 * 没有任何计时器/一次性消费状态——「避免每条消息重复打扰」的节流已由消息链路的
 * `busyReplyCooldownSeconds` 合并批处理承担（RuntimeConfig），UI 侧不重复节流（避免双闸互相
 * 掩盖对方的失效）。提示条消失即端点已配好（1Hz 端点快照回路 ≤1s 翻真）。
 */
@Composable
private fun AiEndpointHintCard(
    visible: Boolean,
    onGoToAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = AppMotion.fast()),
        exit = fadeOut(animationSpec = AppMotion.fast()),
        modifier = modifier,
    ) {
        StatusBanner(
            status = StatusBannerStatus.Warning,
            text = "本地 AI 端点未配置——消息将收到「本地 AI 未就绪」显式错误回复；端点配置后自动恢复。",
            trailing = {
                OutlinedButton(onClick = onGoToAiSettings) {
                    Text("去配置")
                }
            },
        )
    }
}

/**
 * 单步行：完成态 CheckCircle（filled，success tint）/ 未完成 Outlined.Circle（状态类归 outlined）
 * + 说明 + 可选动作按钮。
 */
@Composable
private fun StepRow(
    done: Boolean,
    text: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEasyToolboxColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        AppSymbolIcon(
            icon = if (done) MaterialSymbols.CHECK_CIRCLE else MaterialSymbols.CIRCLE,
            contentDescription = null,
            size = Dimens.iconSmall,
            tint = if (done) {
                colors.success
            } else {
                LocalToolboxSurfaceColors.current.onSurfaceFaint
            },
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * agent 能力链健康度单行（驾驶舱状态区，配置态）：
 * 「本地 AI 调度」单段 pill——回答「AI 设置页配的端点被谁用了」（Koog 端点配置态）。
 */
@Composable
private fun AgentChainHealthRow(
    dispatchReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEasyToolboxColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        PillLabel(
            text = "本地 AI 调度：${if (dispatchReady) "就绪" else "未配置"}",
            container = if (dispatchReady) colors.successContainer else colors.warningContainer,
            contentColor = if (dispatchReady) colors.success else colors.warning,
        )
    }
}

/**
 * 进行中的确认区块：PENDING 非空才渲染（空表整块缺席——调用方判定）。
 * 卡头：「待你确认」+「N 项进行中」badge（headerTrailing 槽
 * 挂 PillLabel，N = [pendingViews].size 动态；零 pending 时整卡不组合故无空值渲染面）。
 * 每行：工具名（mono 风）+ 发起者（openId 前 8 位回落——视图只有 senderOpenId，无 userName 数据源；
 * UsageStatsPanel 的「userName.ifBlank { senderOpenId }」同款口径）+ 剩余秒 pill（mm:ss，负值
 * 「即将超时」warning 配色）+ 已读 N 人（确认卡已发送且查到计数时显示 N，否则「已读 —」诚实缺席）
 * + 行尾复制裁决短语钮（textProvider =「确认 <shortId>」——桌面用户粘贴
 * 即裁，DECISION_REGEX 通道现成；短码单源 ConfirmService.shortId）。
 *
 * 纯渲染（零挂起零网络——已读计数由面板侧事件驱动查询后经 [readCounts] 注入；复制钮
 * 为剪贴板写、非网络）；剩余秒随 1Hz 快照重算（[nowMillis] 由调用方锚
 * **pendingViews** 帧取时——audit 重读锚点在确认窗内冻结（audit 只在任务收尾写），不能当
 * 确认倒计时时钟；区块行粒度 mm:ss）。
 */
@Composable
private fun PendingConfirmationsCard(
    pendingViews: List<PendingConfirmView>,
    readCounts: Map<String, Int>,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEasyToolboxColors.current
    val faintColor = LocalToolboxSurfaceColors.current.onSurfaceFaint
    SectionCard(
        title = "待你确认",
        modifier = modifier.fillMaxWidth(),
        // 卡头「N 项进行中」badge——
        // .chead（标题 spacer badge 尾随）；N = pendingViews.size 动态计数。
        // 零 pending 零渲染：本卡整块只在 pendingViews 非空时被调用方组合（上方 if 门控），
        //    故此处 N 恒 ≥1，badge 恒有内容；色对走 warning 系（.badge = --warn×--warn-c，
        //    与行内剩余秒 pill 超时态同族——「待办紧迫」语义一致）。
        headerTrailing = {
            PillLabel(
                text = "${pendingViews.size} 项进行中",
                container = colors.warningContainer,
                contentColor = colors.warning,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            // 行距：.frow+.frow margin-top 2px
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            pendingViews.forEach { view ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.LOCK,
                        contentDescription = null,
                        size = Dimens.iconSmall,
                        // 昼战坐 warningContainer 档（design-doc.md §5.3，dayBattleTint 图形 3.0 线）
                        tint = dayBattleTint(colors.warning, colors.warningContainer),
                    )
                    // 头部簇 = 工具名 + 发起者，包一层 weight(1f) 吃满配额（尾部
                    // pill 恒贴行尾）；工具名内层 fill=false 保持发起者邻接。
                    // 勿回退「工具名 fill=false + 尾部 Spacer(weight)」双 weight
                    // 形态：配额平分后尾部 pill 堆行中部、右缘悬空
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        // 工具名（mono 风——确认卡「**工具**：`Bash`」同语义）
                        Text(
                            text = view.toolName,
                            // mono fs-12 weight600（mono + SemiBold，非默认 labelSmall）
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 发起者：openId 前 8 位（无 userName 数据源的诚实回落）
                        Text(
                            text = view.senderOpenId.take(SENDER_DISPLAY_CHARS).ifBlank { "-" },
                            style = MaterialTheme.typography.labelSmall,
                            // .hint = on-surface-faint 弱档
                            color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 剩余秒 pill（mm:ss；已到/超时 = warning 配色「即将超时」）
                    val remaining = view.createdAt + view.effectiveTimeoutMillis - nowMillis
                    val remainingText = CockpitPanelLogic.formatRemaining(remaining)
                    // 同枚两态原子同改：超时态 warningContainer 直出
                    //（定义已含 bgSubtle 预洗，copy 属双洗底——清零）；非超时态
                    // primary×selectedWash 微洗底 selected 档
                    PillLabel(
                        // 「剩余 02:31」形态：补「剩余」前缀——裸 mm:ss 需读者自行猜测语义
                        text = if (remainingText.expiring) "即将超时" else "剩余 ${remainingText.text}",
                        container = if (remainingText.expiring) {
                            colors.warningContainer
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.selectedWash)
                        },
                        contentColor = if (remainingText.expiring) colors.warning else MaterialTheme.colorScheme.primary,
                    )
                    // 已读 N 人（cardMessageId 非空且查到计数 → N；未发卡/未查到 → 「已读 —」）
                    val readCount = view.cardMessageId?.let { readCounts[it] }
                    Text(
                        text = readCount?.let { "已读 $it 人" } ?: "已读 —",
                        style = MaterialTheme.typography.labelSmall,
                        color = faintColor,
                    )
                    // 行尾「复制裁决短语」——粘贴「确认 <短码>」到飞书即可走
                    // DECISION_REGEX 通道秒裁（短码单源 = ConfirmService.shortId，勿手写
                    // removePrefix 链防漂移）。
                    // 🔴 防替答语义：裁决校验 chatId + senderOpenId 与发起者一致（BuiltinHandlers）——
                    //    短语复制给他人粘贴无效，只有原请求者可用；校验缺失会让任何人替答执行高危操作（此处不加注 UI，KDoc 登记）。
                    CopyIconButton(
                        textProvider = { "确认 " + ConfirmService.shortId(view.requestId) },
                        contentDescription = "复制裁决短语（确认 ${ConfirmService.shortId(view.requestId)}）",
                    )
                }
            }
        }
    }
}
