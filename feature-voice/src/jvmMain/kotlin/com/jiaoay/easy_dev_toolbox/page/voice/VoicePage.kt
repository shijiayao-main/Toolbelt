package com.jiaoay.easy_dev_toolbox.page.voice

import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.BottomFollowScrollEffect
import com.jiaoay.easy_dev_toolbox.widget.task.FileDropZoneSpec
import com.jiaoay.easy_dev_toolbox.widget.task.TaskPageTemplate
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.easy_dev_toolbox.widget.text.MarkdownText
import dev.vicart.compose.material.symbols.MaterialSymbols
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim

/**
 * 语音转写页（P4-S openspec voice-transcribe 任务 2.3）。
 *
 * TaskPageTemplate 四胞胎形态 + 顶部模型就绪引导区（未就绪时下载引导）
 * + 成功卡「AI 整理」按钮（审阅卡 AlertDialog）+ 采纳纪要区。
 *
 * 全 token 化零白名单（既有四胞胎 token 面——无新布局尺寸）。
 *
 * 审阅卡纪要内容区用 MarkdownText 分层渲染（闭集件——标题/
 * 列表层级可见，非等宽平文）+「查看原文/查看纪要」切换钮（mono 原文态保留——
 * 审阅语义 = 核验原文 vs 纪要）。
 *
 * 双形态：[VoicePage] 整页形态保留供深入模式/
 * 动态 tab 直达；[VoicePageContent] 无头内容变体供宿主 AgentCenterPage 子页签内嵌
 * （聚合页自有页头，消除双页头/双 padding——TaskPageTemplate embedded 配合）。
 */
@Composable
fun VoicePage() {
    VoicePageContent()
}

/**
 * 无头内容变体：主体三段 + 审阅对话框原样，无整页滚动
 * 外壳决定权——[embedded] 参数语义见 [TaskPageTemplate]（null = 整页形态走模板
 * 自带 PageScaffold；true = 聚合页子页签无头内嵌）。
 */
@Composable
fun VoicePageContent(embedded: Boolean = false) {
    val vm: VoiceViewModel = viewModel { VoiceViewModel() }
    val modelState by vm.modelState.collectAsState()
    val aiEnabled by vm.aiEnabled.collectAsState()
    val summarizeState by vm.summarizeState.collectAsState()
    val acceptedNote by vm.acceptedNote.collectAsState()
    val summarizingFor by vm.summarizingFor.collectAsState()

    // 根布局补整页滚动——原 fillMaxWidth 无滚动，两层
    // section（模型引导 + VAD 录音）+ 纪要区超出窗口高度时被裁剪不可达。
    // 🔴 嵌套滚动约束链：本页滚动 ⇒ TaskPageTemplate 内 TaskListSection 的
    //    LazyColumn 必须有界（已补 heightIn，见 TaskListSection.kt；无界 LazyColumn
    //    在可滚动父内测量时 Compose 直接抛异常）。
    // embedded 时滚动责任上移聚合页 content 容器（模板无头
    // 形态下本 Column 即子页签内容根）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (embedded) Modifier else Modifier.verticalScroll(rememberScrollState())),
        verticalArrangement = Arrangement.spacedBy(Dimens.space12),
    ) {
        // ── 模型就绪引导区（未就绪时下载引导） ──
        ModelGuideSection(vm = vm, state = modelState)

        // ── 实时录音区（add-voice-live-recording-vad——VAD 伪流式） ──
        LiveRecordingSection(vm = vm, aiEnabled = aiEnabled)

        TaskPageTemplate(
            title = "语音转写",
            viewModel = vm,
            taskTitle = "转写任务",
            emptyHint = "拖入音频文件开始离线转写（wav 直读；mp3/m4a/flac/ogg 需系统 ffmpeg）",
            // 同句兼作常驻卡头行尾 hint（稿卡头 hint 常驻）
            headerHint = "拖入音频文件开始离线转写（wav 直读；mp3/m4a/flac/ogg 需系统 ffmpeg）",
            embedded = embedded,
            onAiSummarize = if (aiEnabled) {
                { task -> vm.startSummarize(task) }
            } else {
                null // 置灰语义经 null 槽——按钮不渲染 + 底部降级文案明示（spec 语义）
            },
            dropZone = FileDropZoneSpec(
                icon = MaterialSymbols.GRAPHIC_EQ,
                title = "拖入或点击选择音频文件（wav / mp3 / m4a / flac / ogg，可多选）",
                dragTitle = "松开以转写",
                desc = "离线转写（中英日韩粤）——不联网可用；wav 不依赖 ffmpeg",
                accepts = { it.extension.lowercase() in setOf("wav", "mp3", "m4a", "flac", "ogg") },
            ),
        )

        // AI 关闭降级文案（spec：置灰不隐藏——明示基座可用整理不可用）
        if (!aiEnabled) {
            Text(
                "配置模型服务后可对转写原文做 AI 整理纪要（要点/待办/结论）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.space4),
            )
        }

        // ── 已采纳纪要区（审阅通过后写回） ──
        acceptedNote?.let { note ->
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                // 导出回执局部态（单纪要语义——纪要卡重组即重置）
                var exportedPath by remember { mutableStateOf<String?>(null) }
                Text("纪要（已采纳）", style = MaterialTheme.typography.titleSmall)
                // 已采纳卡内容走 .md h1/h2/li 三档分层渲染
                MarkdownText(markdown = note)
                TextButton(onClick = { vm.exportNote()?.let { f -> exportedPath = f.absolutePath } }) {
                    Text("导出纪要文件")
                }
                exportedPath?.let { path ->
                    // 回执路径可见（与蒸馏页回执「已导出：$path」同构模板；
                    // 路径类等宽先例 ui-style-guide.md §2.8——DesktopTypography.mono）
                    Text(
                        "已导出：$path",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimens.space4),
                    )
                }
            }
        }
    }

    // ── 审阅卡（整理产出——采纳才写回，spec「AI 整理纪要」） ──
    when (val s = summarizeState) {
        VoiceSummarizeSession.State.Running -> {
            // 整理进行中卡（原 Running 落 else 零渲染 =「点了整理没反应」——目标名 +
            // 秒表耗时 + 取消）。dismissReview 在 Running 态即 cancel（session.cancel
            // 收口），Esc/scrim 与「取消」钮同语义（design-language.md §4.2）
            val startedAt = remember { System.currentTimeMillis() }
            var elapsedSec by remember { mutableStateOf(0L) }
            LaunchedEffect(startedAt) {
                while (true) {
                    elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
                    kotlinx.coroutines.delay(1_000)
                }
            }
            AlertDialog(
                shape = AppDialogDefaults.shape,
                containerColor = AppDialogDefaults.containerColor(),
                modifier = Modifier.appDialogRim(),
                onDismissRequest = vm::dismissReview,
                title = { Text("AI 整理中——${summarizingFor ?: ""}") },
                text = {
                    Text(
                        "正在读取转写原文并生成结构化纪要，已完成 ${elapsedSec}s。" +
                            "完成后将弹出审阅卡（采纳才写回）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                confirmButton = {
                    TextButton(onClick = vm::dismissReview) { Text("取消") }
                },
            )
        }
        is VoiceSummarizeSession.State.Produced -> {
            // 纪要 markdown 分层渲染（MarkdownText 闭集件）+「查看原文」mono 态
            // 切换（审阅语义 = 核验原文 vs 纪要；切换态先行，双栏另议）。
            var showRaw by remember { mutableStateOf(false) }
            AlertDialog(
                shape = AppDialogDefaults.shape,
                containerColor = AppDialogDefaults.containerColor(),
                modifier = Modifier.appDialogRim(),
                onDismissRequest = vm::dismissReview,
                title = { Text("AI 整理纪要——${summarizingFor ?: ""}") },
                text = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = Dimens.consoleHeightMax),
                    ) {
                        if (showRaw) {
                            // 原文态：mono 等宽平文（核验原文——改动前的原形态）；
                            // SelectionContainer = 转写原文可选中复制（核验动线「带走原文」）
                            SelectionContainer {
                                Text(
                                    s.markdown,
                                    style = DesktopTypography.mono,
                                )
                            }
                        } else {
                            // 纪要态：闭集分层渲染（主形态）
                            MarkdownText(markdown = s.markdown)
                        }
                        TextButton(
                            onClick = { showRaw = !showRaw },
                            modifier = Modifier.padding(top = Dimens.space4),
                        ) { Text(if (showRaw) "查看纪要" else "查看原文") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = vm::acceptNote) { Text("采纳") }
                },
                dismissButton = {
                    // design-language.md §4.2：对话框 dismiss 恒「取消」；
                    // dismissReview 语义不变——不采纳不写回。
                    TextButton(onClick = vm::dismissReview) { Text("取消") }
                },
            )
        }

        is VoiceSummarizeSession.State.Failed -> {
            AlertDialog(
                shape = AppDialogDefaults.shape,
                containerColor = AppDialogDefaults.containerColor(),
                modifier = Modifier.appDialogRim(),
                onDismissRequest = vm::dismissReview,
                title = { Text("整理失败") },
                text = { Text(s.reason) },
                confirmButton = {
                    // 泛化 OK 词族禁用——失败重试走任务卡行内「AI 整理」再点（现有动线）。
                    TextButton(onClick = vm::dismissReview) { Text("取消") }
                },
            )
        }

        else -> {}
    }
}

/** 模型就绪引导区（Unknown/Downloading/Failed/Corrupt 时呈现——Ready 时零占位；
 *  ASR 就绪而 VAD 缺失的引导也从这里出——录音区内不再有第二个下载引导） */
@Composable
private fun ModelGuideSection(vm: VoiceViewModel, state: ModelStore.State) {
    val vadState by vm.vadState.collectAsState()
    when (state) {
        is ModelStore.State.Ready, ModelStore.State.Unknown -> if (state is ModelStore.State.Unknown) {
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                    Text("转写模型未就绪", style = MaterialTheme.typography.titleSmall)
                    // 取消后引导：有保留分片时明示「分片已保留 + 将重新下载」（不假续传）
                    val retainedMb = remember(state) { ModelStore.retainedPartialBytes() / 1_000_000 }
                    Text(
                        if (retainedMb > 0) {
                            "首次使用需下载 SenseVoice int8 模型（约 227MB，一次下载长期复用）。\n" +
                                "检测到上次取消后保留的 ${retainedMb}MB 分片（本次将重新下载，分片不续用）；" +
                                "也可手动放置 model.int8.onnx 与 tokens.txt 到模型目录跳过下载。"
                        } else {
                            "首次使用需下载 SenseVoice int8 模型（约 227MB，一次下载长期复用；" +
                                "也可手动放置 model.int8.onnx 与 tokens.txt 到模型目录跳过下载）。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 下载钮 AppButton(Secondary)
                    AppButton(
                        label = if (retainedMb > 0) "继续下载（重新拉取）" else "下载模型",
                        busy = false,
                        kind = AppButtonKind.Secondary,
                        onClick = { vm.downloadModel() },
                    )
                }
            }
        } else if (vadState !is ModelStore.State.Ready) {
            // ASR 就绪、VAD 缺失 → VAD 引导卡上移至此（顺序就绪引导——
            // 录音区内不再渲染第二个下载入口）
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                    Text("VAD 模型未就绪", style = MaterialTheme.typography.titleSmall)
                    VadGuideRow(
                        state = vadState,
                        onDownload = vm::downloadVadModel,
                        onCancel = vm::cancelDownloadVadModel,
                    )
                }
            }
        }

        is ModelStore.State.Downloading -> {
            // 速度差分流（VM 层 500ms 窗口——重组不重置）
            val speed by vm.downloadSpeed.collectAsState()
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                    // 卡头「模型准备」题 + pill warn「下载中」（chead 形态）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        Text("模型准备", style = MaterialTheme.typography.titleMedium)
                        com.jiaoay.easy_dev_toolbox.widget.PillLabel(
                            text = "下载中",
                            container = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current.warningContainer,
                            contentColor = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current.warning,
                        )
                    }
                    Text("模型下载中…", style = MaterialTheme.typography.bodyMedium)
                    if (state.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { state.downloadedBytes.toFloat() / state.totalBytes },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${state.downloadedBytes / 1_000_000} / ${state.totalBytes / 1_000_000} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    // 速度/ETA 行——速度行恒显（稿「速度为 0 时只显速度行不炸」，
                    // speed≤0 显 0 MB/s）；ETA 仅 totalBytes 已知且 speed>0 可估
                    //（-1 = 未知总长不可估），只做约数取整分（网络抖动不做精确倒计时）
                    if (state.totalBytes > 0) {
                        Text(
                            // 速度一位小数（10 × 1s 窗口采样整形——避免浮点 locale 逗号）
                            "${speed * 10 / 1_000_000 / 10.0} MB/s" +
                                (if (speed > 0) {
                                    " · 剩余约 ${etaMinutes(state.totalBytes - state.downloadedBytes, speed)} 分"
                                } else {
                                    ""
                                }),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 统一停止钮（下载属 1s–10s+ 长任务，必带取消）；
                    // .abtn.dangertx 形态——AppButton Destructive 统一语言
                    AppButton(
                        label = "取消下载",
                        busy = false,
                        kind = AppButtonKind.Destructive,
                        onClick = { vm.cancelDownloadModel() },
                    )
                }
            }
        }

        is ModelStore.State.Failed -> TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                Text("模型下载失败", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Text(state.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppButton(
                    label = "重试下载",
                    busy = false,
                    kind = AppButtonKind.Secondary,
                    onClick = { vm.downloadModel() },
                )
                ManualPlacementHint(vm)
            }
        }

        is ModelStore.State.Corrupt -> TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                Text("模型校验失败", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Text(state.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppButton(
                    label = "重新下载",
                    busy = false,
                    kind = AppButtonKind.Secondary,
                    onClick = { vm.downloadModel() },
                )
                ManualPlacementHint(vm)
            }
        }
    }
}

// ── 实时录音区（add-voice-live-recording-vad 任务 3.2——VAD 伪流式） ──

/**
 * VAD 伪流式录音面：
 * - 双就绪门（ASR + VAD）——VAD 缺失时展示独立下载引导（单文件 629KB）；
 * - 录音中：时长 mm:ss + 音量条（RMS 归一）+ 分段结果区尾部跟随（零动画铁律）；
 * - 停止后：「整理纪要」入口（段全文 → 既有 VoiceSummarizeSession 链）。
 * 全 token 化零白名单。
 */
@Composable
private fun LiveRecordingSection(vm: VoiceViewModel, aiEnabled: Boolean) {
    val recording by vm.recording.collectAsState()
    val level by vm.liveLevel.collectAsState()
    val elapsed by vm.elapsedSeconds.collectAsState()
    val segments by vm.liveSegments.collectAsState()
    val recordError by vm.recordError.collectAsState()
    val vadState by vm.vadState.collectAsState()
    // 整理会话态（整理纪要钮 busy 接线——Running 期禁再点）
    val summarizeState by vm.summarizeState.collectAsState()
    val asrReady = vm.modelState.collectAsState().value is ModelStore.State.Ready

    SectionCard(
        title = "实时录音",
        description = "边说边出字：麦克风经 VAD 切段，逐段离线转写（段粒度伪流式——不联网可用）",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
            // VAD 引导已上移至模型准备区（ModelGuideSection）——此处只留
            // 录音钮未就绪的就近原因线索（审计：禁用无线索）
            if (!asrReady || vadState !is ModelStore.State.Ready) {
                Text(
                    if (!asrReady) "转写模型未就绪——见上方模型准备区" else "VAD 模型未就绪——见上方模型准备区",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            recordError?.let { err ->
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
            ) {
                // 录音中「停止」= 稿 :689 .abtn.dangertx（error tint）；非录音「开始录音」= 主钮 Primary
                AppButton(
                    label = if (recording) "停止" else "开始录音",
                    busy = false,
                    kind = if (recording) AppButtonKind.Destructive else AppButtonKind.Primary,
                    onClick = { if (recording) vm.stopRecording() else vm.startRecording() },
                    enabled = asrReady && vadState is ModelStore.State.Ready,
                )
                if (recording) {
                    Text(
                        "%02d:%02d".format(elapsed / 60, elapsed % 60),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LinearProgressIndicator(
                        progress = { level },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 分段结果区（尾部跟随——scrollToItem 零动画铁律）
            // BottomFollowScrollEffect 接线（ChatMessageList 同款配方；
            // 视口贴底新段自动可见，上翻即停不劫持视点）。
            if (segments.isNotEmpty()) {
                val segmentsState = rememberLazyListState()
                androidx.compose.foundation.lazy.LazyColumn(
                    state = segmentsState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = Dimens.consoleHeightMax)
                        // 分段结果区 = 恒深控制台容器（consoleBg + consoleText mono）——
                        // ShellOutputPanel/LogConsolePanel 同构先例
                        .background(
                            com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current.consoleBg,
                            AppShapes.small,
                        ),
                ) {
                    itemsIndexed(segments) { _, segment ->
                        Text(
                            "#${segment.index}  ${segment.text}",
                            style = DesktopTypography.mono,
                            color = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current.consoleText,
                            modifier = Modifier.padding(vertical = Dimens.space2, horizontal = Dimens.space8),
                        )
                    }
                }
                BottomFollowScrollEffect(
                    listState = segmentsState,
                    itemCount = segments.size,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                    if (!recording && aiEnabled) {
                        // 稿 :691——「整理纪要」次级形态，AppButton Secondary；
                        // busy 接整理会话态（硬编码 false 可再点——Running 守卫静默吞点击）
                        AppButton(
                            label = "整理纪要",
                            busy = summarizeState is VoiceSummarizeSession.State.Running,
                            busyLabel = "整理中…",
                            kind = AppButtonKind.Secondary,
                            onClick = vm::summarizeRecording,
                        )
                    }
                    if (!recording && !aiEnabled) {
                        Text(
                            "配置模型服务后可整理纪要",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // .abtn——清空为次级形态，AppButton Secondary
                    AppButton(
                        label = "清空",
                        busy = false,
                        kind = AppButtonKind.Secondary,
                        onClick = vm::clearRecording,
                        enabled = !recording,
                    )
                }
            }
        }
    }
}

/**
 * 下载失败/校验失败卡手动放置指引（PathConfirmCard 式 mono 小字
 * ——完整绝对路径 + 「打开模型目录」钮；国内网络兜底——手动放置 = 双文件在位即跳过）。
 * Unknown 态既有文案不动；VAD 下载面不扩（单文件 629KB 秒级无 ETA 价值）。
 */
@Composable
private fun ManualPlacementHint(vm: VoiceViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        Text(
            "也可手动放置 model.int8.onnx 与 tokens.txt 到\n${ModelStore.modelDir.absolutePath}\n后重启应用",
            style = DesktopTypography.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = vm::openModelDir) { Text("打开模型目录") }
    }
}

/**
 * 下载剩余时间估计（约数取整分——网络抖动不做精确倒计时）。
 * null = 不可估（speed ≤ 0 除零坑 / remaining < 0——totalBytes=-1 未知总长坑）；
 * 不足 1 分按 1 分起估（「剩余约 0 分」无信息量）。
 */
internal fun etaMinutes(remainingBytes: Long, speedBps: Long): Int? {
    if (speedBps <= 0 || remainingBytes < 0) return null
    val seconds = (remainingBytes + speedBps - 1) / speedBps
    return (seconds / 60).coerceAtLeast(1).toInt()
}

/** VAD 模型下载引导行（单文件 629KB——独立于 ASR 引导） */
@Composable
private fun VadGuideRow(state: ModelStore.State, onDownload: () -> Unit, onCancel: () -> Unit) {
    when (state) {
        is ModelStore.State.Downloading -> {
            // 字节进度 + 取消钮（同页 ASR 下载卡同款语言——下载是长任务，必带取消；
            // totalBytes 未知 = 不定圈 + 已读字节裸显）
            Text("VAD 模型下载中…", style = MaterialTheme.typography.bodySmall)
            if (state.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { state.downloadedBytes.toFloat() / state.totalBytes },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${state.downloadedBytes / 1_000_000} / ${state.totalBytes / 1_000_000} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "${state.downloadedBytes / 1_000_000} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCancel) { Text("取消下载") }
        }

        is ModelStore.State.Failed -> {
            Text(
                "VAD 模型下载失败：${state.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDownload) { Text("重试") }
        }

        is ModelStore.State.Corrupt -> {
            Text(
                "VAD 模型校验失败：${state.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDownload) { Text("重新下载") }
        }

        else -> {
            Text(
                "实时录音需 VAD 切段模型（silero_vad.onnx，约 629KB，一次下载长期复用）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDownload) { Text("下载 VAD 模型") }
        }
    }
}
