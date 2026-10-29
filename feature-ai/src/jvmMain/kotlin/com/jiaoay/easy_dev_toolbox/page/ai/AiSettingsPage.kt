package com.jiaoay.easy_dev_toolbox.page.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.WindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.currentWindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.llm.KoogAgentSafetyPreset
import com.jiaoay.easy_dev_toolbox.core.llm.KoogAgentStylePreset
import com.jiaoay.easy_dev_toolbox.core.llm.tools.MemoryManager
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import com.jiaoay.easy_dev_toolbox.widget.ChipTextButton
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.AppSwitchRow
import com.jiaoay.easy_dev_toolbox.widget.ChoiceField
import com.jiaoay.easy_dev_toolbox.widget.CommitNumberField
import com.jiaoay.easy_dev_toolbox.widget.CommitTextField
import com.jiaoay.easy_dev_toolbox.widget.FormRow
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.SettingsPaneSection
import com.jiaoay.easy_dev_toolbox.widget.SettingsTwoPane
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TagsField
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow
import com.jiaoay.easy_dev_toolbox.widget.interactions.rememberCopyFeedbackState
import com.jiaoay.easy_dev_toolbox.widget.IcBtnContainer
import dev.vicart.compose.material.symbols.MaterialSymbols
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 设置页：配置 OpenAI 兼容端点（Base URL / API Key / 模型 ID / 请求超时），
 * 供飞书智能调度等 AI 能力调用；另承载 Koog agent 全局配置（性格模式 + 安全约束，
 * 「koog agent 是大脑」——配置归本页而非飞书桥接）与**长期记忆面板**
 * （agent 域控制台的记忆管理面）。
 *
 * - 未配置时页顶 Info 横幅提示「AI 工具将以本地规则降级运行」（不阻断任何功能）；
 * - 连通性测试：普通 completion 探测（能力要求最低），成功着色展示模型应答前 40 字符
 *   证明真实往返（渲染对齐设置页 AdbCheckResult 模式）；
 * - 性格/安全改动即时生效（≤1s 免重启——飞书桥接的端点刷新回路现读本页配置）；
 * - 双栏化：六 SectionCard 收进 [SettingsTwoPane] 四分类
 *   （模型服务/智能体/能力授权/长期记忆四分类）；
 *   窄窗回落单列与现状逐像素等价；五个对话框是浮层不进分类（content 尾部组合）。
 */
@Composable
fun AiSettingsPage() {
    val viewModel = AiSettingsViewModel.getViewModel()
    val config by viewModel.config.collectAsState()
    val probe by viewModel.connectivity.collectAsState()
    val catalog by viewModel.modelCatalog.collectAsState()
    val pickerExpanded by viewModel.pickerExpanded.collectAsState()
    val aiEnabled by viewModel.aiEnabled.collectAsState()
    val aiToggleFeedback by viewModel.aiToggleFeedback.collectAsState()

    // 记忆面板（L2 语义记忆管理面——四动作：查看/编辑/删除/清空；
    // 与 agent memory.* 工具同源读写 [MemoryManager]）
    val memoryDir = remember { viewModel.memoryDir }
    var memoryOpen by remember { mutableStateOf(false) }
    var memorySnapshot by remember { mutableStateOf<List<MemoryManager.MemoryIndexEntry>>(emptyList()) }
    var memoryEditTarget by remember { mutableStateOf<Pair<MemoryManager.MemoryIndexEntry, String>?>(null) }
    var memoryDeleteTarget by remember { mutableStateOf<MemoryManager.MemoryIndexEntry?>(null) }
    var memoryClearAsk by remember { mutableStateOf(false) }

    // 刷新走协程：listIndex 是阻塞文件 IO，点击 handler 直调会冻结 Main
    // （与下方 writeEntry 同缺陷同配方）——对话框先开，快照就绪即重组刷新
    val memoryRefreshScope = rememberCoroutineScope()
    fun refreshMemorySnapshot() {
        memoryRefreshScope.launch {
            memorySnapshot = withContext(Dispatchers.IO) { MemoryManager.listIndex(memoryDir) }
        }
    }

    // 首屏首刷：snapshot 空初始值 + 只在用户动作后刷新 = 已有记忆首屏显示
    // 「暂无长期记忆」假态——组合即拉一次。listIndex 是阻塞文件 IO，包 IO 直下
    // （core 签名不动——生产调用点仅本页 + MemoryManager 内部）
    LaunchedEffect(Unit) {
        memorySnapshot = withContext(Dispatchers.IO) { MemoryManager.listIndex(memoryDir) }
    }

    // ── AI 设置页双栏化（左分类导航 + 右内容舱）──
    // 四分类目：模型服务（AI 工具/模型服务两卡）/
    // 智能体（性格模式/安全约束两卡）/ 能力授权（源码目录）/ 长期记忆（长期记忆卡）
    // ——六 SectionCard 组卡结构保留，原样平移进右舱。
    // 窄窗回落分支 = sections 依序全量单列，与现状逐像素等价。
    val sections = listOf(
        SettingsPaneSection(
            title = "模型服务",
            icon = MaterialSymbols.DNS,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space16)) {
                SectionCard(
                    title = "AI 工具",
                    description = "关闭后，依赖模型服务的能力（如飞书智能调度）不可用；本地工具页不受影响",
                ) {
                    // frow 化（frow 首行形态）：
                    // lab「启用 AI 工具」+ hint「AI 能力可用…」中段 + switch 右缘
                    // （frow=true 时组件内部横排三段：lab 190 定宽 + hint 弱档 + switch 最右）
                    AppSwitchRow(
                        label = "启用 AI 工具",
                        checked = aiEnabled,
                        frow = true,
                        // 开时校验：拨开先跑连通性探测，失败拒绝开启并弹原因；
                        // 探测中禁用防重复提交（30s 封顶，AiSettingsViewModel.PROBE_TIMEOUT_SECONDS）
                        onCheckedChange = viewModel::setAiToolsEnabled,
                        enabled = aiToggleFeedback !is AiToggleFeedback.Probing,
                        supportingText = when {
                            aiToggleFeedback is AiToggleFeedback.Probing -> "正在验证端点连通性（最长 30 秒）…"
                            aiEnabled -> "AI 能力可用（飞书智能调度等）"
                            else -> "AI 工具未开启；在此可重新开启"
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.medium)
                            .padding(horizontal = Dimens.space16, vertical = Dimens.space10),
                        hoverable = true,
                        hoverShape = AppShapes.medium,
                    )
                    // 开启校验失败原因走 StatusBanner(Error)（非行内裸红字）
                    (aiToggleFeedback as? AiToggleFeedback.Failed)?.let { feedback ->
                        StatusBanner(
                            status = StatusBannerStatus.Error,
                            text = "开启失败：${feedback.message}",
                        )
                    }
                }

                SectionCard(
                    title = "模型服务",
                    // 「OpenAI 兼容端点」= 卡头行尾 pill.mute（onSurfaceVar 字 + surfaceDeep 底）
                    headerTrailing = {
                        PillLabel(
                            text = "OpenAI 兼容端点",
                            container = LocalToolboxSurfaceColors.current.surfaceDeep,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                ) {
                    // frow 化：lab「Base URL」
                    // + hint「兼容 OpenAI 协议…」中段 + ctl 右缘 txt min-width:220px
                    // ——label/supportingText 外置（labelInline=false），ctl 段走 fieldControlMinWidth
                    FormRow(
                        label = "Base URL",
                        hint = "兼容 OpenAI 协议，如 https://api.deepseek.com/v1；留空用官方地址",
                    ) {
                        CommitTextField(
                            label = null,
                            value = config.baseUrl,
                            onCommit = viewModel::onBaseUrlChange,
                            labelInline = false,
                            modifier = Modifier.width(Dimens.fieldControlMinWidth),
                        )
                    }
                    // API Key 明文切换 + 复制——配置排障第一动作是
                    // 「确认 Key 粘对了」；trailingIcon 槽承载 eye 切换与复制两钮。
                    // visualTransformation 只影响显示不影响编辑文本——失焦提交链零变化。
                    // frow 化：lab「API Key」
                    // + hint「明文保存在本机 DataStore」中段 + ctl 右缘 txt.secret + 两 ic-btn
                    // ——label/supportingText 外置，eye/copy 两钮留 trailingIcon 框内（.ctl 内联附加控件）。
                    // 两 ic-btn 挂 hoverable+alpha 渐显（「明文切换 hover 才显示图标钮」规则；
                    // ConfigFieldRenderer.resetActionAlpha 同款配方；hidden 态 alpha=0 不可点防误触）。
                    // revealed = hover 或字段获焦（onFocusEvent 观察本节点与后代）——disabled 钮
                    // 不进焦点链，键盘用户 Tab 到字段即点亮两钮（裸 hoverable 只认鼠标 =
                    // eye 钮零键盘通路，HoverActions.kt 行焦点分支同源契约）
                    var showKey by remember { mutableStateOf(false) }
                    FormRow(
                        label = "API Key",
                        hint = "明文保存在本机 DataStore",
                    ) {
                        val apiKeyInteraction = remember { MutableInteractionSource() }
                        val apiKeyHovered by apiKeyInteraction.collectIsHoveredAsState()
                        var apiKeyFocused by remember { mutableStateOf(false) }
                        val apiKeyRevealed = apiKeyHovered || apiKeyFocused
                        CommitTextField(
                            label = null,
                            value = config.apiKey,
                            onCommit = viewModel::onApiKeyChange,
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            labelInline = false,
                            monoText = true,
                            modifier = Modifier
                                .width(Dimens.fieldControlMinWidth)
                                .hoverable(apiKeyInteraction)
                                .onFocusEvent { apiKeyFocused = it.hasFocus },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { showKey = !showKey },
                                        enabled = apiKeyRevealed,
                                        modifier = Modifier.apiKeyActionAlpha(apiKeyRevealed),
                                    ) {
                                        AppSymbolIcon(
                                            icon = if (showKey) MaterialSymbols.VISIBILITY_OFF else MaterialSymbols.VISIBILITY,
                                            contentDescription = if (showKey) "隐藏 API Key" else "显示 API Key",
                                            size = Dimens.iconSmall,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    CopyIconButton(
                                        textProvider = { config.apiKey },
                                        contentDescription = "复制 API Key",
                                        modifier = Modifier.apiKeyActionAlpha(apiKeyRevealed),
                                        enabled = apiKeyRevealed,
                                    )
                                }
                            },
                        )
                    }
                    // frow 化：lab「模型 ID」
                    // + hint「端点 /v1/models 拉取 · 手动输入兜底」中段 + ctl 右缘。
                    // manual 分支 label/supportingText 外置（labelInline=false）；
                    // 下拉分支 ModelIdDropdown 外包 FormRow 但不传 hint——其框内
                    // supportingText（拉取态文案）保留，防中段双 hint 叠加。
                    val manualMode by viewModel.manualMode.collectAsState()
                    if (manualMode) {
                        FormRow(
                            label = "模型 ID（手动）",
                            hint = "网关不支持模型列表接口时的兜底输入；提交非空值后回到下拉形态",
                        ) {
                            CommitTextField(
                                label = null,
                                value = config.modelId,
                                onCommit = viewModel::onManualModelCommitted,
                                labelInline = false,
                                modifier = Modifier.width(Dimens.fieldControlMinWidth),
                            )
                        }
                    } else {
                        // 行尾「拉取列表」chipbtn（点击 = fetchModels）——保留
                        // ModelIdDropdown 下拉交互（VM 四态状态机是防失焦覆盖竞态的有意改造），
                        // 行尾直拉与下拉内动作项两入口同源；chipbtn 形态 = TaskCard「深度分析」同配方
                        FormRow(
                            label = "模型 ID",
                            trailing = {
                                ChipTextButton(
                                    label = "拉取列表",
                                    enabled = catalog !is ModelCatalog.Loading,
                                    onClick = viewModel::fetchModels,
                                )
                            },
                        ) {
                            // 不传 fillMaxWidth：ModelIdDropdown 默认链含 fillMaxWidth，
                            // 会把行内 trailing 槽挤压归零（ctl 是 txt + chipbtn 同排
                            // 右缘聚拢）——ctl 收敛字段固有宽度，锚点 min 宽走 fieldControlMinWidth
                            ModelIdDropdown(
                                config = config,
                                catalog = catalog,
                                expanded = pickerExpanded,
                                onExpandedChange = viewModel::setPickerExpanded,
                                onPick = viewModel::onModelPicked,
                                onFetch = viewModel::fetchModels,
                                onManualInput = viewModel::onManualInput,
                                modifier = Modifier.width(Dimens.fieldControlMinWidth),
                            )
                        }
                    }

                    // frow 化：lab「请求超时（秒）」
                    // + hint「单次 LLM 调用上限，默认 60」中段 + ctl 右缘
                    // （实现走 fieldControlMinWidth 统一档，不取 80px 窄档）
                    FormRow(
                        label = "请求超时（秒）",
                        hint = "单次 LLM 调用上限，默认 60",
                    ) {
                        CommitNumberField(
                            label = "请求超时（秒）",
                            value = config.timeoutSeconds,
                            onCommit = viewModel::onTimeoutChange,
                            isValid = { it in 5..600 },
                            labelInline = false,
                            modifier = Modifier.width(Dimens.fieldControlMinWidth),
                        )
                    }

                    // 向量模型：知识库语义检索用（与 chat 模型共用同一 Base URL / API Key，
                    // 走端点的 /v1/embeddings 接口）。留空 = 内置缺省（text-embedding-3-small）；
                    // 端点不支持向量接口时知识库自动回落本地关键词检索，功能不受影响。
                    val embeddingModelId by viewModel.embeddingModelId.collectAsState()
                    // frow 化：lab「向量模型 ID」
                    // + hint「知识库语义检索用（可选）」中段 + ctl 右缘 txt
                    // （本页 hint 取现 supportingText 全文，含缺省值/回落说明）
                    FormRow(
                        label = "向量模型 ID",
                        hint = "飞书机器人知识库语义检索用（可选项）；留空 = text-embedding-3-small。" +
                            "端点不支持向量接口时自动改用本地检索，无需配置",
                    ) {
                        CommitTextField(
                            label = null,
                            value = embeddingModelId,
                            onCommit = viewModel::onEmbeddingModelChange,
                            labelInline = false,
                            modifier = Modifier.width(Dimens.fieldControlMinWidth),
                        )
                    }

                    // 忙态按钮走 AppButton——行内 progressInline 圈 + 进行时
                    // 措辞 + busy 自动置灰（ui-style-guide.md §4.7h：100ms–1s 唯一形态）。
                    // 钮形态 = chipbtn hot（primary 字 + primary×0.10 洗底，AppButtonKind.Hot）
                    // + 行尾 hint「单次 completion 探测（能力要求最低），最长 30 秒」；
                    // btnrow margin-top → Spacer(space10)
                    Spacer(modifier = Modifier.height(Dimens.space10))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        AppButton(
                            label = "测试连通性",
                            busy = probe is ConnectivityProbe.Running,
                            onClick = viewModel::testConnectivity,
                            busyLabel = "测试中…",
                            kind = AppButtonKind.Hot,
                        )
                        Text(
                            "单次 completion 探测（能力要求最低），最长 30 秒",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 探测结果走 StatusBanner 容器条（三层语言，非裸彩色文本）
                    when (val p = probe) {
                        is ConnectivityProbe.Ok -> StatusBanner(
                            status = StatusBannerStatus.Success,
                            text = "已连通 ${p.modelId}：${p.replyPreview}",
                        )

                        is ConnectivityProbe.Failed -> StatusBanner(
                            status = StatusBannerStatus.Error,
                            text = p.message,
                        )

                        else -> {}
                    }
                }
            }
        },
        SettingsPaneSection(
            title = "智能体",
            icon = MaterialSymbols.HUB,
        ) {
            // ── Koog agent 全局配置（性格模式 10 档 + 安全约束）──
            // 作用范围：仅飞书机器人「智能调度」的本地 AI 轻量回复；委托 CLI 的重任务不受影响。
            // 改动即时生效（≤1s 免重启）：桥接的端点刷新回路每秒现读这些配置。
            // 按用户的专属档在「飞书桥接 → 统计 → 用户详情」里配置，未配置的用户使用此处的全局档。

            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space16)) {
                val styleId by viewModel.agentStyleId.collectAsState()
                val styleCustom by viewModel.agentStyleCustom.collectAsState()
                SectionCard(
                    title = "性格模式",
                    description = "决定飞书机器人（智能调度）回复的语气与风格；委托 CLI 的重任务不受影响。" +
                        "按用户的专属档在「飞书桥接 → 统计 → 用户详情」里配置，未配置的用户使用此处的全局档",
                    // 卡头行尾 pill.ai「全局档」（aiAccent/aiAccentContainer 直出；
                    // BotCardHeader 紫档同款配方）
                    headerTrailing = {
                        PillLabel(
                            text = "全局档",
                            container = LocalEasyToolboxColors.current.aiAccentContainer,
                            contentColor = LocalEasyToolboxColors.current.aiAccent,
                        )
                    },
                ) {
                    // 10 档 chip 流（ChoiceField ≥4 项 = FlowRow+FilterChip 标签流）：
                    // 自动换行零滚动，嵌套滚动问题随之消失——此处不再需要封顶 verticalScroll
                    ChoiceField(
                        label = "模式",
                        options = KoogAgentStylePreset.entries.map { it.id },
                        selected = styleId,
                        onSelect = viewModel::onAgentStyleIdChange,
                        labels = KoogAgentStylePreset.entries.associate { it.id to it.label },
                    )
                    val selectedStyle = KoogAgentStylePreset.entries.firstOrNull { it.id == styleId }
                    // 画像行（人话一句）先于 prompt 原文——chip 标签 2-3 字
                    // 无画像非创作者无法预期效果；prompt 是提示词工程产物，降为按需折叠查看。
                    // 画像行 = frow 变体（lab width:auto + hint 档画像文）+ 左缘 3px --ai 竖条
                    // （box-shadow:inset 3px 0 0 0 var(--ai) 直译，宽走 configModifiedBarWidth）
                    // + ctl 行右端「查看提示词」chipbtn + 复制 ic-btn；
                    // 展开 prompt 原文（showPrompt toggle）落画像行下方独立块。
                    var showPrompt by remember { mutableStateOf(false) }
                    selectedStyle?.takeIf { it.blurb.isNotBlank() }?.let {
                        // .frow：hover primary×0.04 洗底的实现载体 = HoverableRow；
                        // IntrinsicSize.Min 令左缘竖条沿整行（CockpitPanel 先例）
                        HoverableRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .clip(AppShapes.medium),
                            shape = AppShapes.medium,
                        ) {
                            // 左缘 3px ai 竖条（inset box-shadow → 条 Box；fillMaxHeight 沿整行）
                            Box(
                                modifier = Modifier
                                    .width(Dimens.configModifiedBarWidth)
                                    .fillMaxHeight()
                                    .background(LocalEasyToolboxColors.current.aiAccent),
                            )
                            Text(
                                text = it.blurb,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalToolboxSurfaceColors.current.onSurfaceMid,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = Dimens.space12, vertical = Dimens.space10),
                            )
                            // 「查看提示词」chipbtn（widget/ChipButton.kt ChipTextButton：
                            // raised 底胶囊 + 发丝边 + fs-12/600）+ 复制 ic-btn（IcBtnContainer
                            // = .ic-btn raised 底+发丝边配方件；裸 IconButton 缺底）
                            ChipTextButton(
                                label = if (showPrompt) "收起提示词" else "查看提示词",
                                onClick = { showPrompt = !showPrompt },
                            )
                            // 复制走 CopyFeedbackState（CopyButton.kt KDoc 场景 2：调用方
                            // 自持句柄配自有图标——IcBtnContainer 底框件替代裸 IconButton）
                            val promptCopyState = rememberCopyFeedbackState()
                            IcBtnContainer(
                                contentDescription = "复制提示词",
                                onClick = { promptCopyState.copy(it.prompt) },
                            ) {
                                AppSymbolIcon(
                                    icon = if (promptCopyState.isCopied) {
                                        MaterialSymbols.CHECK
                                    } else {
                                        MaterialSymbols.CONTENT_COPY
                                    },
                                    contentDescription = null,
                                    size = Dimens.iconSmall,
                                    tint = if (promptCopyState.isCopied) {
                                        LocalEasyToolboxColors.current.success
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.width(Dimens.space12))
                        }
                    }
                    if (selectedStyle != null && selectedStyle.prompt.isNotBlank() && showPrompt) {
                        PresetPreview(text = selectedStyle.prompt)
                    }
                    // 自定义档引导（无自定义 textarea 形态——保留既有引导行；画像行
                    // 在上方 selectedStyle.blurb 分支，CUSTOM 档 blurb 空不渲染）
                    if (selectedStyle == KoogAgentStylePreset.CUSTOM) {
                        // frow 化：按 .frow 三段通式对齐——lab 外置 + hint 中段 + 多行编辑器右缘
                        FormRow(
                            label = "自定义性格描述",
                            hint = "自由描述你想要的语气与风格（可参考内置档的画像与写法）；留空 = 不注入性格段",
                        ) {
                            CommitTextField(
                                label = null,
                                value = styleCustom,
                                onCommit = viewModel::onAgentStyleCustomChange,
                                labelInline = false,
                                singleLine = false,
                                minLines = 4,
                                modifier = Modifier.width(Dimens.fieldControlMinWidth),
                            )
                        }
                    }
                }

                val safetyId by viewModel.agentSafetyId.collectAsState()
                val safetyCustom by viewModel.agentSafetyCustom.collectAsState()
                SectionCard(
                    title = "安全约束",
                    description = "注入本地 AI 提示词末尾的不可违反约束；委托 CLI 的重任务有自己的安全提示，两不相扰",
                ) {
                    ChoiceField(
                        label = "策略",
                        options = KoogAgentSafetyPreset.entries.map { it.id },
                        selected = safetyId,
                        onSelect = viewModel::onAgentSafetyIdChange,
                        labels = KoogAgentSafetyPreset.entries.associate { it.id to it.label },
                    )
                    val selectedSafety = KoogAgentSafetyPreset.entries.firstOrNull { it.id == safetyId }
                    // 安全卡补画像行 + prompt 折叠（与性格卡同款形态）
                    selectedSafety?.takeIf { it.blurb.isNotBlank() }?.let {
                        Text(
                            text = it.blurb,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalToolboxSurfaceColors.current.onSurfaceMid,
                        )
                    }
                    if (selectedSafety != null && selectedSafety.prompt.isNotBlank()) {
                        var showSafetyPrompt by remember { mutableStateOf(false) }
                        TextButton(onClick = { showSafetyPrompt = !showSafetyPrompt }) {
                            Text(if (showSafetyPrompt) "收起提示词" else "查看提示词")
                        }
                        if (showSafetyPrompt) {
                            PresetPreview(text = selectedSafety.prompt)
                        }
                    } else if (selectedSafety == KoogAgentSafetyPreset.CUSTOM) {
                        // frow 化：按 .frow 三段通式对齐——lab 外置 + hint 中段 + 多行编辑器右缘
                        FormRow(
                            label = "自定义安全约束",
                            hint = "逐条列出约束（可先复制默认约束参考再改写）；留空 = 不注入安全段",
                        ) {
                            CommitTextField(
                                label = null,
                                value = safetyCustom,
                                onCommit = viewModel::onAgentSafetyCustomChange,
                                labelInline = false,
                                singleLine = false,
                                minLines = 4,
                                modifier = Modifier.width(Dimens.fieldControlMinWidth),
                            )
                        }
                    }
                }
            }
        },
        SettingsPaneSection(
            title = "能力授权",
            icon = MaterialSymbols.EDIT,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space16)) {
                // ── 源码目录 allowlist（桌面链专属，桥接链恒不消费）──
                val sourceRoots by viewModel.sourceRoots.collectAsState()
                SectionCard(
                    title = "源码目录",
                    description = "授权桌面 agent（如知识蒸馏）整目录读取你的源码项目；授权后目录内文件不再逐个弹窗确认。" +
                        "飞书桥接不消费此配置（源码目录不会因此可被发送到飞书群）",
                ) {
                    TagsField(
                        label = "目录列表",
                        tags = sourceRoots,
                        onTagsChange = viewModel::setSourceRoots,
                        allowPickDirectory = true,
                        supportingText = "添加后即刻生效：下一次 agent 任务启动时现读配置（免重启）",
                    )
                }
            }
        },
        SettingsPaneSection(
            title = "长期记忆",
            icon = MaterialSymbols.TARGET,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space16)) {
                // ── 长期记忆面板（四动作与 agent memory.* 工具同源；
                //    管理载体 = 独立对话框 MemoryManagerDialog）──
                SectionCard(
                    title = "长期记忆",
                    description = "agent 在对话中自动写入的语义记忆（${
                        if (memorySnapshot.isEmpty()) "暂无" else "${memorySnapshot.size} 条"
                    }）；打开右上角记忆图标管理",
                ) {
                    if (memorySnapshot.isEmpty()) {
                        Text(
                            text = "暂无长期记忆：agent（飞书智能调度）会在对话中自动写入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        memorySnapshot.forEach { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = entry.hook,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // 卡内「查看」开管理对话框（查看详情并入对话框右列；
                                // 刷新快照保证对话框列表与卡内概览同步）
                                TextButton(onClick = {
                                    refreshMemorySnapshot()
                                    memoryOpen = true
                                }) { Text("查看") }
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        refreshMemorySnapshot()
                        memoryOpen = true
                    }) { Text("管理全部记忆") }
                }
            }
        },
    )

    // 滚动责任迁移——双栏（Wide）时走吸顶模式（headerSticky=true 给 content
    // 有界高度），右舱 Column(verticalScroll) 局部滚（SettingsTwoPane 内置）；
    // 窄窗回落保持现状整页滚（headerSticky=false + modifier verticalScroll——
    // PageScaffold 契约：headerSticky 与 modifier verticalScroll 互斥）。
    val wideWindow = currentWindowSizeClass() == WindowSizeClass.Wide
    val scrollState = rememberScrollState()
    PageScaffold(
        title = "AI 设置",
        headerSticky = wideWindow,
        modifier = if (wideWindow) Modifier else Modifier.verticalScroll(state = scrollState),
        actions = {
            // 记忆面板入口（打开瞬间快照）；「长期记忆」= 带字 ic-btn
            //（pagehead 纯文字无图标，fs-12 = bodySmall 档）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(AppShapes.small)
                    .background(LocalToolboxSurfaceColors.current.surfaceRaised, AppShapes.small)
                    .border(Dimens.glassStrokeHairline, LocalToolboxSurfaceColors.current.borderHairline, AppShapes.small)
                    .clickable {
                        refreshMemorySnapshot()
                        memoryOpen = true
                    }
                    .padding(horizontal = Dimens.space12, vertical = Dimens.headerBtnPaddingV),
            ) {
                Text(
                    "长期记忆",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) {
        if (!config.isConfigured) {
            // 未配置横幅以 StatusBanner Info 槽承载（守护锚要求横幅留在 pane 之前）
            StatusBanner(
                status = StatusBannerStatus.Info,
                text = "尚未配置模型服务：AI 工具开关默认关闭，填入 API Key 与模型 ID 后可开启；" +
                    "本地工具页（解密/解压等）不受 AI 开关影响，离线照常可用",
            )
        }

        // 内容区恒满宽（Wide 限宽居中已废除——大屏差异化
        // UI 未做之前，居中悬浮即缺陷）；frow 三段行宽屏中段空洞是 FormRow 族的
        // 共性课题，待大屏真设计统一收口，不在本页单独特化
        SettingsTwoPane(sections = sections)

        // ── 记忆管理对话框（独立面板化；勿回退为 DropdownMenu 当列表容器——
        // 菜单=动作集心智、长列表不可滚动）——四动作（查看/编辑/删除/清空）全部收进本对话框：
        // 左列条目列表 + 右列只读详情直达编辑/删除，清空沉底 error 钮。
        if (memoryOpen) {
            MemoryManagerDialog(
                entries = memorySnapshot,
                memoryDir = memoryDir,
                onDismiss = { memoryOpen = false },
                onViewEdit = { entry -> memoryEditTarget = entry to (MemoryManager.readEntry(memoryDir, entry.fileName) ?: "") },
                onDelete = { entry -> memoryDeleteTarget = entry },
                onClearAsk = { memoryClearAsk = true },
            )
        }

        // 「查看」语义并入 MemoryManagerDialog 右列只读详情

        // 编辑弹窗（整文件覆写——正文级编辑，索引不动）
        memoryEditTarget?.let { (entry, body) ->
            var edited by remember(entry.fileName) { mutableStateOf(body) }
            var saveError by remember(entry.fileName) { mutableStateOf(false) }
            val saveScope = rememberCoroutineScope()
            AlertDialog(
                onDismissRequest = { memoryEditTarget = null },
                shape = AppDialogDefaults.shape,
                containerColor = AppDialogDefaults.containerColor(),
                modifier = Modifier.appDialogRim(),
                title = { Text("编辑记忆：${entry.title}", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = edited,
                            onValueChange = { edited = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                // 多行正文编辑上限 = Composer 同款 token（记忆条目正文与输入区同尺寸面）
                                .height(Dimens.composerHeightMax),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        if (saveError) {
                            Text(
                                "保存失败：记忆文件写入未成功（磁盘不可写或路径已失效），正文未落盘",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = Dimens.space8),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            // 保存 = 文件 IO（直跑点击 handler 卡 Main）+ 失败零反馈
                            // （对话框静默不动）双缺陷——IO 包 withContext，失败置卡内
                            // 错误提示不吞（静默数据丢失不可接受）
                            saveScope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    MemoryManager.writeEntry(memoryDir, entry.fileName, edited)
                                }
                                if (ok) {
                                    saveError = false
                                    memoryEditTarget = null
                                } else {
                                    saveError = true
                                }
                            }
                        },
                    ) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { memoryEditTarget = null }) { Text("取消") }
                },
            )
        }

        // 单条删除确认
        memoryDeleteTarget?.let { entry ->
            AppConfirmDialog(
                title = "删除记忆",
                text = "将删除「${entry.title}」及其索引行，不可恢复。",
                onConfirm = {
                    MemoryManager.deleteEntry(memoryDir, entry.fileName)
                    memoryDeleteTarget = null
                    refreshMemorySnapshot()
                },
                onDismiss = { memoryDeleteTarget = null },
                confirmLabel = "删除",
                dismissLabel = "取消",
            )
        }

        // 清空确认（全部记忆——最高危险级）
        if (memoryClearAsk) {
            AppConfirmDialog(
                title = "清空全部记忆",
                text = "将删除全部 ${memorySnapshot.size} 条长期记忆，不可恢复。",
                onConfirm = {
                    MemoryManager.clearAll(memoryDir)
                    memoryClearAsk = false
                    refreshMemorySnapshot()
                },
                onDismiss = { memoryClearAsk = false },
                confirmLabel = "清空",
                dismissLabel = "取消",
            )
        }
    }
}

/** 内置档只读预览：正文（弱化配色）+ 一键复制（供参考后自写自定义档），不支持编辑。 */
@Composable
private fun PresetPreview(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        CopyButton(text = text)
    }
}

@Composable
private fun MemoryManagerDialog(
    entries: List<MemoryManager.MemoryIndexEntry>,
    memoryDir: java.io.File,
    onDismiss: () -> Unit,
    onViewEdit: (MemoryManager.MemoryIndexEntry) -> Unit,
    onDelete: (MemoryManager.MemoryIndexEntry) -> Unit,
    onClearAsk: () -> Unit,
) {
    var selected by remember(entries) { mutableStateOf(entries.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("长期记忆管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    text = "${entries.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                )
            }
        },
        text = {
            if (entries.isEmpty()) {
                Text(
                    text = "暂无长期记忆：agent（飞书智能调度）会在对话中自动写入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // ── 左列：条目列表（title+hook 行结构沿用原卡内概览；
                        //    双列各自滚动封顶 memoryDialogHeightMax）──
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(max = Dimens.memoryDialogHeightMax)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            entries.forEachIndexed { index, entry ->
                                val isSelected = entry == selected
                                if (index > 0) {
                                    // .frow margin-top 2px
                                    Spacer(modifier = Modifier.height(Dimens.space2))
                                }
                                HoverableRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(AppShapes.medium)
                                        .clickable { selected = entry }
                                        .padding(horizontal = Dimens.space12, vertical = Dimens.space10),
                                    shape = AppShapes.medium,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            // 选中态提亮（与左舱导航同语言：微洗底 + 主色）
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        Text(
                                            text = entry.hook,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    // 行尾删除 ic-btn 恒显（frow 行尾常驻）——防误触
                                    // 语义由 AppConfirmDialog 二次确认承接；钮放 content
                                    // 主体绕开 actionContent 槽的 hover alpha。
                                    // 形态 = IcBtnContainer（.ic-btn raised 底+发丝边配方件；
                                    // SVG fill=currentColor 中性色）
                                    IcBtnContainer(
                                        contentDescription = "删除记忆",
                                        onClick = { onDelete(entry) },
                                    ) {
                                        AppSymbolIcon(
                                            icon = MaterialSymbols.DELETE,
                                            contentDescription = null,
                                            size = Dimens.iconSmall,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                        // ── 右列：题行（题先行 + spacer + 编辑/删除 chipbtn 右端）→ console 正文
                        //    ＋ 底排「清空全部记忆+取消」两钮同排沉底（btnrow flex:1）
                        Column(
                            modifier = Modifier
                                // 双列比例 1fr/1.35fr（.mgr grid-template-columns）
                                .weight(1.35f)
                                .heightIn(max = Dimens.memoryDialogHeightMax)
                                .borderStart(),
                        ) {
                            selected?.let { entry ->
                                // 题行：题 fs-14（titleSmall）+ spacer + 编辑/删除 chipbtn 右端
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // 编辑/删除 chipbtn（chead 行尾 chipbtn——删除 chipbtn error 字；
                                    // ChipTextButton 单源件：raised 底胶囊 + 发丝边）
                                    ChipTextButton(
                                        label = "编辑",
                                        onClick = { onViewEdit(entry) },
                                    )
                                    ChipTextButton(
                                        label = "删除",
                                        color = MaterialTheme.colorScheme.error,
                                        onClick = { onDelete(entry) },
                                    )
                                }
                                // 记忆正文 = 恒深控制台语义面（consoleBg 底 + consoleText mono +
                                // r-md 容器 + sp-12/16 内边距）——VoicePage 分段区同款配方
                                val toolboxColors = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current
                                Text(
                                    text = MemoryManager.readEntry(memoryDir, entry.fileName)
                                        ?: "（记忆文件缺失）",
                                    style = DesktopTypography.mono,
                                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                    color = toolboxColors.consoleText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = Dimens.memoryConsoleMinHeight)
                                        .background(toolboxColors.consoleBg, AppShapes.medium)
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = Dimens.space16, vertical = Dimens.space12),
                                )
                            }
                        }
                    }
                    // 底排「清空全部记忆（error 字）+ 取消」两钮同排沉底 flex:1（chipbtn
                    // 形态：raised 底胶囊 + 发丝边 + 居中；「取消」措辞纪律不变仅移位）
                    Spacer(modifier = Modifier.height(Dimens.space14))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // 通栏档（weight 铺宽 + 居中 + 横边距 space12）经
                        // textAlign/contentPadding 参数进 ChipTextButton
                        ChipTextButton(
                            label = "清空全部记忆",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = Dimens.space12,
                                vertical = Dimens.space6,
                            ),
                            onClick = onClearAsk,
                            modifier = Modifier.weight(1f),
                        )
                        ChipTextButton(
                            label = "取消",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = Dimens.space12,
                                vertical = Dimens.space6,
                            ),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            // M3 AlertDialog 必传槽——底排双钮（清空+取消）已沉 text 内，本槽传空组合
        },
    )
}

/** 详情列左缘分隔线（竖 borderSubtle——appDialogRim 同款 drawBehind 配方，弱分隔不抢内容） */
@Composable
private fun Modifier.borderStart(): Modifier {
    val lineColor = LocalToolboxSurfaceColors.current.borderSubtle
    return drawBehind {
        drawLine(
            color = lineColor,
            start = Offset(x = 0f, y = 0f),
            end = Offset(x = 0f, y = size.height),
            // 分隔线 1dp 描边语义走 glassStrokeHairline token
            //（ModelIdDropdown BorderStroke 同款 1dp 发丝档先例）
            strokeWidth = Dimens.glassStrokeHairline.toPx(),
        )
    }
}

/**
 * API Key 字段行内 ic-btn 的 hover 渐显 alpha（稿注区规则「明文切换 hover 才显示
 * 图标钮」）。配方 = ConfigFieldRenderer.resetActionAlpha 同款：隐形态
 * alpha=transparent（不可见即不可点，enabled 同步门控）、显形态不透明。
 */
private fun Modifier.apiKeyActionAlpha(visible: Boolean): Modifier =
    if (visible) {
        this
    } else {
        this.then(Modifier.alpha(AppAlpha.transparent))
    }
