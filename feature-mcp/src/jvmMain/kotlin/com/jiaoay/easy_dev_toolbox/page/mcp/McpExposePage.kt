package com.jiaoay.easy_dev_toolbox.page.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.AppSwitchRow
import com.jiaoay.easy_dev_toolbox.widget.BottomFollowScrollEffect
import com.jiaoay.easy_dev_toolbox.widget.CommitNumberField
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.FormRow
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton

/**
 * MCP 服务页（全 token 化零白名单）。
 *
 * 结构（管理面——非工作台三栏）：
 * - 状态卡：运行态语义 pill 徽章（successContainer/surfaceDeep）+ 端点 URL + 复制按钮 +
 *   在途 agent 会话数 + 失败原因 Error StatusBanner + 接入示例两行可复制（CLI/JSON）；
 * - 服务区：开关行 + 端口 CommitNumberField（1024..65535 校验域）；
 *   端口提交：运行中且在途会话 >0 时先经 AppConfirmDialog
 *   断连确认（在途 0 或未运行 = 零摩擦即时提交）；
 * - 工具清单：LazyColumn 按四族分节（描述符直读，与实际注册面单一真源一致）；
 * - 日志台：VM 事件流（开启/端口变更/端点就绪/失败原因）——
 *   有日志时列表区恒深底 consoleBg + mono consoleText 行（控制台语义，明暗分治）。
 */
@Composable
fun McpExposePage() {
    val vm: McpExposeViewModel = viewModel { McpExposeViewModel() }
    val status by vm.serverStatus.collectAsState()
    val enabled by vm.enabled.collectAsState()
    val port by vm.port.collectAsState()
    val tools by vm.tools.collectAsState()
    val logLines by vm.logLines.collectAsState()
    // 在途会话数走 StateFlow 消费——外部 client 起 run 即时重组
    val activeRuns by vm.activeRunsFlow.collectAsState()

    // 待确认端口（页面局部确认态——null = 无待确认项）。
    // 确认 UI 是 Compose 层（AiSettingsPage pendingXxx 局部态同款先例），VM commitPort
    // 重启链零改动——取消 = 丢弃 pendingPort，端口与运行态均不变。
    var pendingPort by remember { mutableStateOf<Int?>(null) }

    PageScaffold(
        title = "MCP 服务",
        headerSticky = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.screenSpacing),
        ) {
            // ── 状态卡 ──
            // 语义色局部（container 直出）——切片取值提到 SectionCard 前
            //（卡头徽章槽 headerTrailing 与 content 共用）。
            val toolboxColors = LocalEasyToolboxColors.current
            val surfaceColors = LocalToolboxSurfaceColors.current
            SectionCard(
                title = "服务状态",
                // 三段卡头「.t + spacer + pill」——「运行中」pill 在卡头行尾；
                // .pill.ok 运行中 / .pill.mute 已停止双态，门控语义零变化。
                // 启动中态（running=false + detail=STARTING_DETAIL）不渲染徽章：
                // detail 内联文字承载过渡态，防「已停止」灰徽章误闪。
                headerTrailing = {
                    if (status.running) {
                        PillLabel(
                            text = "运行中",
                            container = toolboxColors.successContainer,
                            contentColor = toolboxColors.success,
                        )
                    } else if (status.detail != McpServerManager.STARTING_DETAIL) {
                        PillLabel(
                            text = "已停止",
                            container = surfaceColors.surfaceDeep,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            ) {
                // 「端点」独立 frow 三段——lab（FormRow 定宽档）+ ctl 段 txt mono
                // flex:1 左起 + ic-btn 复制右缘（.ctl gap sp-8 → space8）。
                FormRow(label = "端点") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (status.running) vm.endpointUrl() else "端点未就绪（开启服务后可连接）",
                            style = DesktopTypography.monoInput,
                            modifier = Modifier.weight(1f),
                        )
                        if (status.running) {
                            CopyIconButton(textProvider = { vm.endpointUrl() }, contentDescription = "复制端点 URL")
                        }
                    }
                }
                // detail 分支：启动中保留内联灰字；启动失败走 Error StatusBanner（detail 即失败文案真源，
                // 端口占用 =「端口被占用，请修改端口」）。运行中 detail 恒 null 不渲染（startNow 成功路径）。
                status.detail?.let { detail ->
                    if (detail == McpServerManager.STARTING_DETAIL) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        StatusBanner(status = StatusBannerStatus.Error, text = detail)
                    }
                }
                // 「在途 agent 会话」frow 三段——lab + hint 中段 + pill inf 右缘
                //（infoContainer/info 槽对）。
                FormRow(
                    label = "在途 agent 会话",
                    hint = "runsVersion 响应式计数（外部 client 起 run 1s 内更新）",
                ) {
                    PillLabel(
                        text = "$activeRuns / ${McpRunRegistry.MAX_RUNS}",
                        container = toolboxColors.infoContainer,
                        contentColor = toolboxColors.info,
                    )
                }
                Text(
                    text = "外部 MCP client（Claude Code / IDE / MCP Inspector）连接上述端点即可调用本地工具与 agent 会话。" +
                        "服务只绑定本机 127.0.0.1，不对外网开放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 接入示例（仅运行中渲染，与端点复制钮同门控）——
                // endpointUrl() 单源派生 local 值，显示与复制同串（端口号与当前端口一致）；
                // CopyButton 带文字形态（AiSettingsPage PresetPreview 同款），剪贴板内容
                // 可直接被 claude mcp add / mcp.json 使用（首次接入零文档外查）。
                if (status.running) {
                    val endpoint = vm.endpointUrl()
                    val cliCommand = "claude mcp add toolbelt --transport http $endpoint"
                    val jsonSnippet = "{\"mcpServers\":{\"toolbelt\":{\"type\":\"http\",\"url\":\"$endpoint\"}}}"
                    Text(
                        text = "接入示例（运行中展示，点击复制）",
                        // .subhead fs-12/650（与族节头同档）
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = cliCommand,
                            style = DesktopTypography.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        CopyButton(text = cliCommand)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = jsonSnippet,
                            style = DesktopTypography.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        CopyButton(text = jsonSnippet)
                    }
                }
            }

            // ── 服务区：开关 + 端口 ──
            SectionCard(title = "服务配置") {
                // E3 busy 补齐：启停在途（端口 bind 探询中）开关置灰 + 文案明示
                val switching by vm.switching.collectAsState()
                AppSwitchRow(
                    label = "开启 MCP 服务",
                    checked = enabled,
                    onCheckedChange = { vm.toggleEnabled(it) },
                    supportingText = if (switching) {
                        "正在${if (enabled) "启动" else "停止"}服务（等待端口就绪，最长 30s）…"
                    } else {
                        "开启后在本机 ${vm.endpointUrl()} 起 MCP 端点（App 退出自动停止）"
                    },
                    enabled = !switching,
                    // 独立服务开关对齐设置页「卡片 hover 壳」视觉档
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.medium)
                        .padding(horizontal = Dimens.space16, vertical = Dimens.space10),
                    hoverable = true,
                    hoverShape = AppShapes.medium,
                    labelStyle = MaterialTheme.typography.bodyLarge,
                )
                CommitNumberField(
                    label = "端口",
                    value = port,
                    // 在途 agent 会话 >0 时改端口会静默掐断全部外部会话
                    // （runCancelHook 收口）——先经断连确认再走重启链；在途 0 / 未运行零摩擦直提。
                    onCommit = { newPort ->
                        if (status.running && activeRuns > 0) pendingPort = newPort else vm.commitPort(newPort)
                    },
                    isValid = { it in com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore.MCP_PORT_RANGE },
                    supportingText = "合法域 1024..65535（默认 8765）；运行中修改端口会即时重启服务",
                )
            }

            // ── 工具清单（描述符直读——与注册面单一真源一致；按四族分节） ──
            SectionCard(
                title = "外放工具清单",
                // 三段卡头「.t + spacer + pill mute N 项」——计数在卡头行尾 pill.mute
                //（总数仍展示，承载体从标题内联文字迁移）
                headerTrailing = {
                    PillLabel(
                        text = "${tools.size} 项",
                        container = LocalToolboxSurfaceColors.current.surfaceDeep,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                // 按族分节渲染——显式 groupBy + ToolFamily.entries
                // 四族序（拼接序 = 描述符 16 项在前、agent 3 项在后 ≠ 渲染序，不能依赖
                // 拼接序）；族节头 = 小节头 item + Text（labelSmall + onSurfaceVariant，
                // 桥接页分区目录行同款语言——零 experimental、零 stickyHeader）；节头
                // 计数动态算（tools 面增删零页面散改）。
                val toolsByFamily = tools.groupBy { it.family }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = Dimens.consoleHeightMax),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    ToolFamily.entries.forEach { family ->
                        val familyTools = toolsByFamily[family].orEmpty()
                        if (familyTools.isEmpty()) return@forEach
                        item(key = "family-${family.name}") {
                            Text(
                                text = "${family.sectionTitle}（${familyTools.size} 件）",
                                // 族节头 = .subhead 档（fs-12/650 + on-surface-var；
                                // BridgeStatusPanel 小节头同款先例）
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(familyTools, key = { it.name }) { tool ->
                            // 工具名 tpill + 描述 mut 同行水平排布（gap 10；写盘徽标行尾随）
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.space10),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                // tpill 胶囊形态（.tpill：fs-11 mono + on-surface-var 字 +
                                // surface-deep 底胶囊）
                                PillLabel(
                                    text = tool.name,
                                    container = LocalToolboxSurfaceColors.current.surfaceDeep,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = DesktopTypography.monoTag,
                                )
                                Text(
                                    text = tool.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (tool.destructive) {
                                    // 风险徽标 4dp 微档（.risk r-badge:4px）：M3 Badge 默认
                                    // 胶囊圆角违规，本版本无 shape 参数——外层
                                    // Modifier.clip(AppShapes.badge) 裁出微档圆角；
                                    // 底/字走 errorContainerFixed/errorFixed 定值槽对
                                    //（scheme.errorContainer 动态派生暗档偏亮偏红）
                                    Badge(
                                        containerColor = toolboxColors.errorContainerFixed,
                                        modifier = Modifier.clip(AppShapes.badge),
                                    ) {
                                        Text(
                                            text = "写盘",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = toolboxColors.errorFixed,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 日志台 ──
            // 服务日志是日志/控制台语义面——有日志时列表区恒深底 consoleBg
            //（token 明暗分治）+ mono 行；行色走 consoleText（onSurfaceVariant
            // 原值在深底对比度反转不可读）。
            // 空态分支留在深底外（降级态语言不混控制台底）。
            SectionCard(
                title = "服务日志",
                // 卡头行尾 hint「环形 N 行事件流」（容量语义说明；hint 档 =
                // labelSmall + onSurfaceFaint）
                headerTrailing = {
                    Text(
                        text = "环形 ${McpExposeViewModel.LOG_CAPACITY} 行事件流",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                    )
                },
            ) {
                if (logLines.isEmpty()) {
                    // 空态收 EmptyStateView——保持 if/else 门控（守护锚 emptyIndex < bgIndex 依赖）。
                    EmptyStateView(
                        text = "暂无事件",
                        learningHint = "开启/关闭/端口变更会记录在这里",
                        fillParent = false,
                    )
                } else {
                    // 深底消费点取 LocalEasyToolboxColors（状态卡切片已取 toolboxColors，
                    // 此处独立取值——日志台与状态卡是两个 SectionCard 切片互不涉）。
                    val toolboxColors = LocalEasyToolboxColors.current
                    // min-height 96dp 起步 + [Dimens.mcpLogHeightMax] 封顶：行多时
                    // 页面滚动视口不再被整段吃掉（追加后新行恒在视口外的交互缺陷）——
                    // 列表在固定窗内自滚 + 贴底跟随承接（BottomFollowScrollEffect
                    // 四处先例同款；heightIn(max) 给出界高度约束，外层 verticalScroll
                    // 下 LazyColumn 无界约束崩溃不复现）。
                    val logListState = rememberLazyListState()
                    // contentVersion = 列表内容哈希：环形封顶后 size 恒 200，追加
                    // 只有内容变化——version 不变贴底跟随即停摆（BottomFollowScroll 契约）
                    BottomFollowScrollEffect(
                        listState = logListState,
                        itemCount = logLines.size,
                        contentVersion = logLines.hashCode().toLong(),
                    )
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.mcpLogHeightMin, max = Dimens.mcpLogHeightMax)
                            .background(color = toolboxColors.consoleBg, shape = AppShapes.medium)
                            // .console padding 横 16/纵 12
                            .padding(horizontal = Dimens.space16, vertical = Dimens.space12),
                        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                    ) {
                        items(logLines) { line ->
                            Text(
                                text = line,
                                style = DesktopTypography.mono,
                                color = toolboxColors.consoleText,
                            )
                        }
                    }
                }
            }
        }
    }

    // 端口重启断连确认（在途会话 >0 才拦截——弹窗文案带在途数；
    // 取消 = 丢弃 pendingPort，端口与运行态均不变）。
    pendingPort?.let { newPort ->
        AppConfirmDialog(
            title = "重启 MCP 服务",
            text = "端口变更将重启服务并中止全部 $activeRuns 个在途 agent 会话，外部 client 需按新端口重连。",
            onConfirm = {
                pendingPort = null
                vm.commitPort(newPort)
            },
            onDismiss = { pendingPort = null },
            confirmLabel = "重启",
            dismissLabel = "取消",
        )
    }
}
