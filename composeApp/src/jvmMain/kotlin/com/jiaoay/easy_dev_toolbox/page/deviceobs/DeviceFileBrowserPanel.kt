package com.jiaoay.easy_dev_toolbox.page.deviceobs

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.page.tools.base64.Base64ImageFormat
import com.jiaoay.easy_dev_toolbox.page.tools.base64.sniffImageFormat
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.easy_dev_toolbox.widget.SearchField
import com.jiaoay.easy_dev_toolbox.widget.pickOpenFiles
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jiaoay.toolbelt.device.DeviceDirectoryPullReceipt
import com.jiaoay.toolbelt.device.DeviceFileEntry
import com.jiaoay.toolbelt.device.DeviceFilePullReceipt
import com.jiaoay.toolbelt.device.DeviceFileType
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource

/**
 * 条目尺寸文本：B/KB/MB/GB 一位小数（不足 1KB 取整数 B）——与进程排行表的
 * 内存人话文本同风格（本函数吃字节数，kb 版是 rssHumanText，两口径不互用）。
 */
internal fun deviceFileSizeText(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        bytes < 1024 -> "${bytes}B"
        kb < 1024 -> String.format(Locale.ROOT, "%.1fKB", kb)
        mb < 1024 -> String.format(Locale.ROOT, "%.1fMB", mb)
        else -> String.format(Locale.ROOT, "%.1fGB", gb)
    }
}

/**
 * 图片预览动作的后缀门（internal 供单测）：设备侧条目没有本地字节可嗅魔数，
 * 后缀是设备侧唯一零成本信号——仅图片后缀的文件条目出预览动作，其余不出。
 */
internal val IMAGE_PREVIEW_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

/** 条目文件名是否图片后缀（大小写不敏感；无后缀 = false） */
internal fun isImageFileName(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in IMAGE_PREVIEW_EXTENSIONS

/**
 * 产物行「查看」动作门（internal 供单测）：仅文件回执出查看钮——目录产物无
 * 查看动作（目录不是应用内查看器的单文件输入载体，与衔接查表同口径）。动作门
 * 不判文件内容形态：加密件（.alog/.xlog）的解密引导由查看器 Failed 态自带。
 */
internal fun isViewablePullProduct(product: DeviceFilePullProduct): Boolean =
    product.receipt is DeviceFilePullReceipt

/** 文件头魔数嗅探的读取上限：魔数最长锚位是 WebP 的 12 字节（RIFF+4+WEBP），16 留余量 */
internal const val IMAGE_HEADER_SNIFF_BYTES = 16

/**
 * 本地文件头魔数嗅探（internal 供单测）：只读文件头 [IMAGE_HEADER_SNIFF_BYTES]
 * 字节进 [sniffImageFormat]（PNG/JPEG/GIF/WebP 四档魔数表与本仓 base64 提取器
 * 同源——两消费面各管各的解码层）。非图片/空文件/读取失败均为 null（不出预览面）。
 */
internal fun sniffLocalImageHeader(file: File): Base64ImageFormat? = runCatching {
    file.inputStream().use { input ->
        val head = ByteArray(IMAGE_HEADER_SNIFF_BYTES)
        var off = 0
        while (off < head.size) {
            val read = input.read(head, off, head.size - off)
            if (read <= 0) break
            off += read
        }
        if (off > 0) sniffImageFormat(head.copyOf(off)) else null
    }
}.getOrNull()

/**
 * 设备文件浏览面板（设备观测空间「文件」子页承载，宿主层内容组件——只服务
 * 观测页，不入 feature 模块）：路径行（返回上级 + 当前路径 + 推送文件）+ 条目列表
 * （名称/类型/尺寸/修改时间 + 文件条目拉取/图片预览动作）+ 失败显式回执，目录点击
 * 进入、文件条目可拉取到本地、本地文件可推送到设备当前目录（覆盖前确认）。
 *
 * - VM/面板分离（性能面板同配方）：导航与读数状态在 [DeviceFileBrowserViewModel]
 *   （请求-回执模型无轮询环，契约见其类头），本面板只做呈现与动作转发；
 * - 子页独立承载全幅：条目列表 weight(1f) 占满子页剩余高度（工作区型面板——
 *   目录列表持续可视，不与观测台成员争同屏空间，有界高度由页面骨架吸顶分支
 *   供给）；
 * - 目标设备 = 设备域选定串号（与抓取/采样同源）；无可采目标时按 [perfPanelHint]
 *   横幅提示并解释动作（去设置/选设备/刷新——动作入口在页级设备栏，选设备与
 *   刷新不出面板内按钮，与性能面板同口径）；
 * - 失败回执显式呈现（设备侧错误原文人话不翻译不吞）+ 原地重试入口；装载期
 *   禁一切导航动作（防对同目录双发 ls）；
 * - 条目列表带名称搜索过滤（[SearchField] 统一件；纯显示层投影零 spawn，翻目录
 *   不清词）；
 * - 条目带「拉取到本地」动作（行尾下载图标；目录条目为递归拉取——校验面
 *   弱化契约见 [DeviceFileBrowserViewModel.startDirectoryPull]），拉取
 *   busy/失败回执区与目录读数回执分立；成功回执进拉回产物**会话列表**（最近
 *   在上、有界淘汰最旧、会话级不落盘——拉 B 不丢 A），每行含「查看」直达应用
 *   内查看器（文件产物专属，加密件解密引导由查看器 Failed 态自带；目录产物行
 *   无查看钮 by design）、「打开所在文件夹」（文件/目录产物均可用）与「送分析」
 *   衔接（产物语义按后缀分立查表；目录产物行无衔接钮——目录名后缀不承载文件
 *   语义，by design）与图片预览（产物侧魔数嗅探真值门，默认展开、
 *   可收起），产物被外部删除时产物行动作落人话回执不静默；symlink 条目行可点、
 *   拉取动作放行（点击/拉取先经解引用 stat 判
 *   真值：真值目录尾斜杠穿透进入、真值文件可拉、断链人话回执不静默——分支
 *   契约见 [DeviceFileBrowserViewModel.enterDirectory] 与
 *   [DeviceFileBrowserViewModel.pullEntry]）；普通文件
 *   条目行不可点；
 * - 推送动作（路径行「推送文件」）：系统文件选择器选本地文件 → 设备当前目录；
 *   设备目标已存在时先出覆盖确认对话（确认才推，不静默覆盖），推送
 *   busy/完成/失败回执与拉取/目录读数分立。
 *
 * 视觉层为保守默认（等宽条目行 + 令牌化图标色），真机手感由维护者终裁。
 */
@Composable
fun DeviceFileBrowserPanel(
    devices: DeviceStateSource,
    sharedViewModel: DeviceFileBrowserViewModel? = null,
    onGoSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 观测页页级持有并传入（与 logcat/投屏 VM 同配方）；独立组合形态下自建同 key 实例
    val viewModel = sharedViewModel ?: viewModel(key = "device-file-browser") {
        DeviceFileBrowserViewModel(devices, DeviceHub.fileBrowser::listDirectory)
    }
    val state by viewModel.state.collectAsState()
    val deviceState by devices.deviceState.collectAsState()
    val detectError by devices.detectError.collectAsState()
    val selectedSerial by devices.selectedSerial.collectAsState()
    val scope = rememberCoroutineScope()

    val hint = perfPanelHint(deviceState, detectError, selectedSerial)
    val targetSerial = when (val ds = deviceState) {
        is DeviceState.Single -> ds.serial
        is DeviceState.Multi -> selectedSerial
        is DeviceState.None -> null
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        TonalCard(
            contentPaddingVertical = Dimens.space12,
            contentPaddingHorizontal = Dimens.space16,
            contentSpacing = Dimens.space8,
        ) {
            // 头部行：图标 + 标题 + 目标尾号 + 刷新
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSymbolIcon(
                    icon = MaterialSymbols.FOLDER_OPEN,
                    contentDescription = null,
                    size = Dimens.iconMedium,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(Dimens.space8))
                // 标题恒内容宽——勿与行尾 Spacer 同挂 weight（双 weight 配额平分，
                // 尾件堆行中部、右缘悬空）
                Text(
                    text = "设备文件",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.weight(1f))
                targetSerial?.let { serial ->
                    Text(
                        text = "目标 ${serialTailLabel(serial)}",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(Dimens.space8))
                }
                IconButton(
                    onClick = { viewModel.refresh() },
                    enabled = !state.loading && targetSerial != null,
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.REFRESH,
                        contentDescription = "刷新当前目录",
                        size = Dimens.iconLarge,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 无可采目标提示（动作入口与面板外资源对齐——去设置经中立槽接线，
            // 选设备/刷新在页级设备栏，消息文案自指引）
            hint?.let { h ->
                StatusBanner(
                    status = StatusBannerStatus.Error,
                    text = h.message,
                    trailing = {
                        if (h.action == PerfPanelHintAction.GO_SETTINGS) {
                            onGoSettings?.let { go ->
                                TextButton(onClick = go) { Text("去设置配置 adb 路径") }
                            }
                        }
                    },
                )
            }

            // 失败回执：设备侧错误原文 + 原地重试（重试不换目录，导航栈不动）
            state.error?.let { error ->
                StatusBanner(
                    status = StatusBannerStatus.Warning,
                    text = error,
                    trailing = {
                        TextButton(
                            onClick = { viewModel.refresh() },
                            enabled = targetSerial != null && !state.loading,
                        ) { Text("重试") }
                    },
                )
            }

            // 拉取动作状态区（busy/完成/失败三态，与目录读数回执分立）
            when (val pull = state.pull) {
                DeviceFilePullUiState.Idle -> Unit

                is DeviceFilePullUiState.Busy -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(Dimens.iconSmall))
                    Text(
                        // 目录链无尺寸校验面（弱化契约见内核）——期望尺寸展示对目录无意义
                        text = if (pull.isDirectory) {
                            "拉取中 ${pull.fileName}（目录递归）"
                        } else {
                            "拉取中 ${pull.fileName}（期望 ${deviceFileSizeText(pull.expectedSizeBytes)}）"
                        },
                        style = DesktopTypography.mono,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = viewModel::cancelPull) { Text("取消") }
                }

                // 完成态无独立渲染面：回执与衔接入口由下方产物会话列表承载（列表头
                // 即本回执——拉 B 不丢 A，历史产物行全部保留衔接动作）
                is DeviceFilePullUiState.Done -> Unit

                is DeviceFilePullUiState.Failed -> StatusBanner(
                    status = StatusBannerStatus.Warning,
                    text = pull.message,
                )
            }

            // 拉回产物会话列表（最近在上；会话级缓存不落盘）：每行保留「送分析」
            // 衔接（产物语义按后缀分立查表）与图片预览（产物侧魔数嗅探真值门）；
            // 产物被外部删除时衔接动作落人话回执不静默
            state.pullProducts.forEach { product ->
                PulledProductRow(
                    product = product,
                    // 目录产物行无衔接钮（by design——目录名后缀不承载文件语义，
                    // 衔接查表只对单文件产物成立）
                    handoffTarget = (product.receipt as? DeviceFilePullReceipt)
                        ?.let { viewModel.nextToolTargetForPullProduct(product.fileName) },
                    onView = viewModel::viewPullProduct,
                    onOpenFolder = viewModel::openPullProductFolder,
                    onHandoff = viewModel::handoffPullProduct,
                    onPreviewToggle = viewModel::requestPullProductPreview,
                )
            }
            state.pullProductError?.let { receipt ->
                StatusBanner(status = StatusBannerStatus.Warning, text = receipt)
            }

            // 推送动作状态区（busy/完成/失败三态 + 覆盖确认对话，与拉取/目录读数回执分立）
            when (val push = state.push) {
                DeviceFilePushUiState.Idle -> Unit

                // 等待用户裁决的态由面板根部的确认对话框承载（此处无渲染面）
                is DeviceFilePushUiState.ConfirmPending -> Unit

                is DeviceFilePushUiState.Busy -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(Dimens.iconSmall))
                    Text(
                        text = "推送中 ${push.fileName}（期望 ${deviceFileSizeText(push.expectedSizeBytes)}）",
                        style = DesktopTypography.mono,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = viewModel::cancelPush) { Text("取消") }
                }

                is DeviceFilePushUiState.Done -> StatusBanner(
                    status = StatusBannerStatus.Success,
                    text = buildString {
                        append("已推送到设备：${push.receipt.devicePath}")
                        append("（${deviceFileSizeText(push.receipt.sizeBytes)}")
                        push.receipt.speedText?.let { append("，$it") }
                        append("）")
                    },
                )

                is DeviceFilePushUiState.Failed -> StatusBanner(
                    status = StatusBannerStatus.Warning,
                    text = push.message,
                )
            }

            // 路径行：返回上级 + 当前路径 + 推送（本地文件 → 设备当前目录）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                OutlinedButton(
                    onClick = { viewModel.navigateUp() },
                    enabled = state.canNavigateUp && !state.loading,
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.ARROW_UPWARD,
                        contentDescription = null,
                        size = Dimens.iconSmall,
                    )
                    Text("返回上级")
                }
                Text(
                    text = state.path,
                    style = DesktopTypography.mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        // AWT FileDialog 模态阻塞，pickOpenFiles 内部已切 IO 协程
                        //（全仓文件选择统一先例，见 widget/FilePicker.kt）
                        scope.launch {
                            pickOpenFiles(title = "选择要推送到设备的文件")
                                .firstOrNull()
                                ?.let { file -> viewModel.requestPush(file.absolutePath) }
                        }
                    },
                    enabled = targetSerial != null && !state.loading &&
                        state.push.let { it !is DeviceFilePushUiState.Busy },
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.FILE_UPLOAD,
                        contentDescription = null,
                        size = Dimens.iconSmall,
                    )
                    Text("推送文件")
                }
            }

            // 名称过滤行（应用子页同款 SearchField 形态；纯投影零内核请求，
            // 翻目录/刷新不清词）
            SearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = "按名称过滤（如 log、Movies）",
                enabled = state.entries != null,
            )

            // 局部锚定防委托属性 smart cast 失效（state 是 collectAsState 委托值）
            val entries = state.entries
            when {
                entries == null -> if (state.loading) {
                    Text(
                        "读取中…",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                entries.isEmpty() -> Text(
                    "（空目录）",
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.filtered.isEmpty() -> Text(
                    "（无匹配条目——调整过滤词）",
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    // 条目列表：工作区型占满子页剩余高度（weight 链由吸顶骨架供有界高度）
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                    ) {
                        items(state.filtered, key = { it.name }) { entry ->
                            DeviceFileEntryRow(
                                entry = entry,
                                enabled = !state.loading,
                                pullEnabled = !state.loading && state.pull.let {
                                    it !is DeviceFilePullUiState.Busy
                                } && targetSerial != null,
                                onOpenDirectory = viewModel::enterDirectory,
                                onPull = viewModel::pullEntry,
                            )
                        }
                    }
                }
            }
        }
    }

    // 推送覆盖确认对话（ConfirmPending 态承载）：确认才覆盖推送，取消零动作——
    // 不静默覆盖是推送方向的安全语义
    (state.push as? DeviceFilePushUiState.ConfirmPending)?.let { pending ->
        AppConfirmDialog(
            title = "覆盖推送确认",
            text = "设备目标已存在：${pending.deviceDir.trimEnd('/')}/${pending.fileName}" +
                "，覆盖后设备侧原文件不可恢复。是否覆盖推送？",
            onConfirm = viewModel::confirmPushOverwrite,
            onDismiss = viewModel::dismissPushConfirm,
            confirmLabel = "覆盖推送",
            isDangerous = true,
        )
    }
}

/**
 * 拉回产物会话列表单行（回执卡形态复用）：文件名 + 尺寸/速度（目录回执为
 * 文件数/总字节/速度信息面）+ 本地路径 + 「查看」直达应用内查看器（文件产物
 * 专属，[isViewablePullProduct] 门——目录产物无查看钮 by design）+「打开所在
 * 文件夹」（文件/目录产物均可用，父目录语义）+ 「送分析」衔接
 * （[handoffTarget] 查表复用；null = 无衔接不出条目——目录产物行恒 null，
 * by design）+ 图片预览（产物侧魔数嗅探真值门，默认展开可收起；目录回执嗅探
 * 恒 null 不出预览）。产物被外部删除时点查看/打开所在文件夹/预览/衔接均落
 * 人话回执不静默（存在性闸在 VM）。
 */
@Composable
private fun PulledProductRow(
    product: DeviceFilePullProduct,
    handoffTarget: TaskPipelineHandoff.NextToolTarget?,
    onView: (DeviceFilePullProduct) -> Unit,
    onOpenFolder: (DeviceFilePullProduct) -> Unit,
    onHandoff: (DeviceFilePullProduct) -> Unit,
    onPreviewToggle: (DeviceFilePullProduct) -> Boolean,
) {
    val viewable = isViewablePullProduct(product)
    // 拉回产物按魔数嗅探决定是否出内联图片预览（后缀会撒谎，魔数不会）
    val imageFormat = remember(product.receipt.localPath) {
        sniffLocalImageHeader(File(product.receipt.localPath))
    }
    var previewExpanded by remember(product.receipt.localPath) {
        mutableStateOf(imageFormat != null)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        StatusBanner(
            status = StatusBannerStatus.Success,
            // 回执文本按拉取链形态分立：文件 = 校验尺寸 + 速度；目录 = 文件数/总字节/
            // 速度（信息面，无单文件级校验——契约见 DeviceDirectoryPullReceipt）
            text = when (val receipt = product.receipt) {
                is DeviceFilePullReceipt -> buildString {
                    append("已拉取 ${product.fileName}")
                    append("（${deviceFileSizeText(receipt.sizeBytes)}")
                    receipt.speedText?.let { append("，$it") }
                    append("）：${receipt.localPath}")
                }

                is DeviceDirectoryPullReceipt -> buildString {
                    append("已拉取目录 ${product.fileName}")
                    val details = buildList {
                        receipt.fileCount?.let { add("$it 个文件") }
                        receipt.totalBytes?.let { add("共 ${deviceFileSizeText(it)}") }
                        receipt.speedText?.let { add(it) }
                    }
                    if (details.isNotEmpty()) append("（${details.joinToString("，")}）")
                    append("）：${receipt.localPath}")
                }
            },
            trailing = {
                if (viewable) {
                    TextButton(onClick = { onView(product) }) { Text("查看") }
                }
                TextButton(onClick = { onOpenFolder(product) }) { Text("打开所在文件夹") }
                if (handoffTarget != null && TaskPipelineHandoff.isWired) {
                    TextButton(onClick = { onHandoff(product) }) { Text(handoffTarget.label) }
                }
                if (imageFormat != null) {
                    TextButton(onClick = {
                        if (onPreviewToggle(product)) previewExpanded = !previewExpanded
                    }) {
                        Text(if (previewExpanded) "收起预览" else "预览图片")
                    }
                }
            },
        )
        if (previewExpanded && imageFormat != null) {
            PulledImagePreview(
                localPath = product.receipt.localPath,
                format = imageFormat,
            )
        }
    }
}

/**
 * 拉回图片产物的内联预览（面板内 Image + Fit 缩放 + 可关闭——与 base64 提取器的
 * 候选图片预览同族形态）：整图解码在后台线程（skia 解码不可上 UI 线程），解码失败
 * 显式呈现不静默（魔数嗅探只看文件头，数据中段截断/损坏由本解码层接住）。
 */
@Composable
private fun PulledImagePreview(localPath: String, format: Base64ImageFormat) {
    var outcome by remember(localPath) { mutableStateOf<ImagePreviewOutcome?>(null) }
    LaunchedEffect(localPath) {
        outcome = withContext(Dispatchers.Default) { decodePreviewWithinBudget(File(localPath)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        Text(
            text = "${format.label} 图片预览（已按高度上限缩放）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val current = outcome) {
            is ImagePreviewOutcome.Ok -> Image(
                bitmap = current.bitmap,
                contentDescription = "拉回图片预览（${format.label}）",
                contentScale = ContentScale.Fit,
                // 大图缩放进上限高度内预览，不原尺寸铺开
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = Dimens.imagePreviewHeightMax),
            )

            ImagePreviewOutcome.TooLarge -> Text(
                text = "图片过大（超过 ${IMAGE_PREVIEW_MAX_BYTES / 1024 / 1024}MB 或 " +
                    "${IMAGE_PREVIEW_MAX_PIXELS / 1_000_000} 百万像素）——不内联预览，文件已完整拉回，" +
                    "可直接用本地图形工具打开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            ImagePreviewOutcome.DecodeFailed -> Text(
                text = "图像解码失败——数据可能截断或损坏（文件已完整拉回，可直接用本地图形工具打开）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            null -> Text(
                text = "解码中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 内联预览的字节上限：超过即不读入内存（设备拉回的大图很常见）。 */
internal const val IMAGE_PREVIEW_MAX_BYTES: Long = 64L * 1024 * 1024

/**
 * 内联预览的像素上限。与字节闸互补——只查字节会漏「小字节、巨大尺寸」的解压炸弹
 * （如高度 65535 的 PNG 可远小于 64MB 却要 4 亿字节位图）。
 */
internal const val IMAGE_PREVIEW_MAX_PIXELS: Long = 64_000_000L

/** 内联预览解码结果三态（UI 按态呈现，不静默吞失败）。 */
internal sealed interface ImagePreviewOutcome {
    class Ok(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : ImagePreviewOutcome

    data object TooLarge : ImagePreviewOutcome

    data object DecodeFailed : ImagePreviewOutcome
}

/**
 * 双闸解码：先按文件字节数拒绝（不读入），再用 skia Codec 只探尺寸（不解码）做像素预算，
 * 通过后才整图解码。`Codec`/`Bitmap` 经 `use` 释放——var 与 native 缓冲不走 GC 回收，
 * 在此泄漏会累积在进程 RSS 里。
 */
internal fun decodePreviewWithinBudget(file: File): ImagePreviewOutcome {
    if (!file.isFile) return ImagePreviewOutcome.DecodeFailed
    if (file.length() > IMAGE_PREVIEW_MAX_BYTES) return ImagePreviewOutcome.TooLarge
    return runCatching {
        val bytes = file.readBytes()
        val codec = org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes))
        codec.use {
            val pixels = it.width.toLong() * it.height.toLong()
            if (pixels > IMAGE_PREVIEW_MAX_PIXELS) return@runCatching ImagePreviewOutcome.TooLarge
        }
        ImagePreviewOutcome.Ok(
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap(),
        )
    }.getOrDefault(ImagePreviewOutcome.DecodeFailed)
}

/**
 * 单条目行：目录/symlink 可点（primary 主体色——symlink 真值目标未知，点击由 VM
 * 解引用 stat 分派：真值目录进入/真值文件人话回执，契约见 VM），普通文件只读
 * （不可点）；尺寸/时间等宽右缘随行。
 *
 * 拉取动作（行尾下载图标）：文件/symlink/目录条目全启用——symlink 先判真值再
 * 放行（真值目录回人话回执，不在目录拉取面内）；目录条目为递归拉取（校验面
 * 弱化契约见 [DeviceFileBrowserViewModel.startDirectoryPull]）。图片预览动作
 * （预览图标）：图片后缀的文件/symlink 条目可用，复用拉取链（symlink 真值为
 * 目录时落人话回执；预览展开由产物侧魔数嗅探决定）。
 */
@Composable
private fun DeviceFileEntryRow(
    entry: DeviceFileEntry,
    enabled: Boolean,
    pullEnabled: Boolean,
    onOpenDirectory: (DeviceFileEntry) -> Unit,
    onPull: (DeviceFileEntry) -> Unit,
) {
    val clickable = entry.type != DeviceFileType.FILE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clickable) {
                    Modifier.clickable(enabled = enabled) { onOpenDirectory(entry) }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        AppSymbolIcon(
            icon = when (entry.type) {
                DeviceFileType.DIRECTORY -> MaterialSymbols.FOLDER
                DeviceFileType.SYMLINK -> MaterialSymbols.LINK
                DeviceFileType.FILE -> MaterialSymbols.DESCRIPTION
            },
            contentDescription = null,
            size = Dimens.iconSmall,
            tint = if (clickable) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        val symlinkSuffix = entry.symlinkTarget?.takeIf { it.isNotEmpty() }?.let { " -> $it" } ?: ""
        Text(
            text = entry.name + symlinkSuffix,
            style = DesktopTypography.mono,
            color = if (clickable) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 吃满剩余宽：尾件（大小/时间/动作钮）恒贴行尾；长文件名配额内 ellipsis
            //（勿用 fill=false + 尾部 Spacer 双 weight：配额平分致尾件堆行中部）
            modifier = Modifier.weight(1f),
        )
        Text(
            text = deviceFileSizeText(entry.sizeBytes),
            style = DesktopTypography.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = entry.modifiedTime,
            style = DesktopTypography.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = { onPull(entry) },
            enabled = pullEnabled,
            modifier = Modifier.size(Dimens.minimumInteractiveSize),
        ) {
            AppSymbolIcon(
                icon = MaterialSymbols.DOWNLOAD,
                contentDescription = "拉取到本地",
                size = Dimens.iconSmall,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 图片条目预览动作：设备侧无本地字节可嗅，预览 = 先拉回（产物侧魔数嗅探
        // 决定预览展开——后缀只是动作门不是真值）。
        IconButton(
            onClick = { onPull(entry) },
            enabled = pullEnabled && entry.type != DeviceFileType.DIRECTORY && isImageFileName(entry.name),
            modifier = Modifier.size(Dimens.minimumInteractiveSize),
        ) {
            AppSymbolIcon(
                icon = MaterialSymbols.IMAGE,
                contentDescription = "拉取并预览图片",
                size = Dimens.iconSmall,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
