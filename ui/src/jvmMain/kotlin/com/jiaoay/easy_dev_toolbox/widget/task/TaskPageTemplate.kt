package com.jiaoay.easy_dev_toolbox.widget.task

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.FileDropZone
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TaskUi

/**
 * 文件任务四胞胎页模板：文件任务型工具页的参数化骨架——
 * 「参数区 + 拖放区 + 整批预校验错误提示 + 任务列表」四段结构，供
 * XLog 解析 / Log Tag 过滤 / 文件分割 / 解压四页收敛复用。
 *
 * 四页均为 PageScaffold + 参数 slot（0~2 个输入控件）
 * + FileDropZone（文案/图标/accepts 逐页差异）+ inputError 渲染（三页同配方）
 * + TaskListSection（title/emptyHint 差异）。差异全部参数化：
 *
 * - inputError 裸文本 → [StatusBanner]（Error + 行尾「清除该提示」可关）——
 *   「就地横幅一律可手动关闭」规范的落地；
 * - 清空 = L1 撤销（TaskListSection 内直连 :core StatusSignals 撤销条，无需上送）。
 *
 * @param title 页面标题（PageScaffold header）
 * @param paramsSlot 参数区（XLog 私钥框 / LogTag 双框 / FileSplit 数字框 / Archive 无=不传）
 * @param dropZone 拖放区配置（图标/文案/accepts——见 [FileDropZoneSpec]）
 * @param viewModel 文件任务 VM（TaskListSection 消费）
 * @param taskTitle 任务列表段标题
 * @param emptyHint 任务列表空态提示（默认「还没有任务」——模板级
 *        兜底防新页漏传；四签调用点显式传页别文案；null = 不渲染空态。组件层
 *        [TaskListSection] 保持 `String? = null` opt-in 不动——组件默认会波及
 *        VoicePage 等其它调用方）
 */
@Composable
fun TaskPageTemplate(
    title: String,
    viewModel: FileTaskViewModel,
    dropZone: FileDropZoneSpec,
    paramsSlot: (@Composable () -> Unit)? = null,
    taskTitle: String = "任务",
    // 模板默认兜底「还没有任务」——新页漏传也见四要素空态；
    // 四签（XLog/LogTag/FileSplit/Archive）显式传页别文案覆盖；null = 不渲染。
    emptyHint: String? = "还没有任务",
    /** 卡头常驻 hint（透传 TaskListSection——VoicePage 用） */
    headerHint: String? = null,
    onDeepAnalyze: ((String) -> Unit)? = null,
    onAiSummarize: ((TaskUi) -> Unit)? = null,
    /** 「送往下一步」条目配置（管线衔接——透传 TaskListSection；null = 不出现） */
    sendToNext: SendToNextMenuEntry? = null,
    // 无头形态——宿主聚合页（AgentCenterPage）内嵌时
    // 消除双页头（聚合页自有 PageScaffold）。默认 false = 现状整页形态，存量调用零改动。
    embedded: Boolean = false,
) {
    // 无头形态直接渲染内容段（调用方自带骨架/页头）；整页形态走 PageScaffold。
    if (embedded) {
        TaskPageTemplateBody(
            viewModel = viewModel,
            dropZone = dropZone,
            paramsSlot = paramsSlot,
            taskTitle = taskTitle,
            emptyHint = emptyHint,
            headerHint = headerHint,
            onDeepAnalyze = onDeepAnalyze,
            onAiSummarize = onAiSummarize,
            sendToNext = sendToNext,
        )
        return
    }
    PageScaffold(title = title) {
        TaskPageTemplateBody(
            viewModel = viewModel,
            dropZone = dropZone,
            paramsSlot = paramsSlot,
            taskTitle = taskTitle,
            emptyHint = emptyHint,
            headerHint = headerHint,
            onDeepAnalyze = onDeepAnalyze,
            onAiSummarize = onAiSummarize,
            sendToNext = sendToNext,
        )
    }
}

/** 内容段（embedded 拆分）：拖放区 + 预校验错误条 + 任务列表 */
@Composable
private fun TaskPageTemplateBody(
    viewModel: FileTaskViewModel,
    dropZone: FileDropZoneSpec,
    paramsSlot: (@Composable () -> Unit)?,
    taskTitle: String,
    emptyHint: String?,
    headerHint: String? = null,
    onDeepAnalyze: ((String) -> Unit)?,
    onAiSummarize: ((TaskUi) -> Unit)?,
    sendToNext: SendToNextMenuEntry? = null,
) {
    paramsSlot?.invoke()

    // busy 门禁：有任务在途时拖放区拒收新提交（就近防重入层；VM 侧另有
    // 按产物目标的单飞去重兜底程序化提交路径），拒绝原因由门禁文案明示
    val busy by viewModel.hasRunningTasks.collectAsState()
    FileDropZone(
        icon = dropZone.icon,
        title = dropZone.title,
        dragTitle = dropZone.dragTitle,
        desc = dropZone.desc,
        onFilesSelected = viewModel::addFiles,
    multiple = dropZone.multiple,
    accepts = dropZone.accepts,
    acceptsDroppedFiles = dropZone.acceptsDroppedFiles,
    acceptsDirectories = dropZone.acceptsDirectories,
    busy = busy,
    )

    // P3 · 整批预校验错误：StatusBanner(Error) + 行尾「清除该提示」（可手动关闭；
    // 再拖新批次时 addFiles 内部重置 inputError 自然复显）。按钮措辞是动作动词
    //（「清除该提示」），不用「知道了」类泛化词。
    val inputError by viewModel.inputError.collectAsState()
    inputError?.let { error ->
        StatusBanner(
            status = StatusBannerStatus.Error,
            text = error,
            trailing = {
                TextButton(onClick = viewModel::dismissInputError) { Text("清除该提示") }
            },
        )
    }

    // 重跑 affordance：产物在盘 → 覆盖确认卡（库层 exists 幂等硬拒是死端面，
    // 确认卡是唯一出路；确认 = 清旧产物后重新入队，取消 = 旧产物原样保留）
    val overwriteRequest by viewModel.overwriteRequest.collectAsState()
    overwriteRequest?.let { request ->
        AppConfirmDialog(
            title = "产物已存在",
            text = "以下产物已存在，覆盖重跑将先清除旧产物：\n${request.conflicts.joinToString("\n")}",
            confirmLabel = "覆盖重跑",
            onConfirm = viewModel::confirmOverwrite,
            onDismiss = viewModel::dismissOverwrite,
        )
    }

    TaskListSection(
        viewModel = viewModel,
        title = taskTitle,
        emptyHint = emptyHint,
        onDeepAnalyze = onDeepAnalyze,
        onAiSummarize = onAiSummarize,
        sendToNext = sendToNext,
    )
}

/** 拖放区配置（[TaskPageTemplate] 的参数化差异点） */
data class FileDropZoneSpec(
    val icon: String,
    val title: String,
    val dragTitle: String,
    val desc: String,
    val multiple: Boolean = true,
    val accepts: (java.io.File) -> Boolean,
    /**
     * 直拖顶层条目与点击选择器（用户点名文件入口）的过滤通道；null 回落 [accepts]
     * （既有 spec 零改动）。目录展开产物恒走 [accepts]——双轨语义见 [FileDropZone]
     * 与 [expandDroppedEntries] 契约。
     */
    val acceptsDroppedFiles: ((java.io.File) -> Boolean)? = null,
    /** true = 拖入目录递归枚举内部支持文件并入输入批（产物目录一等输入，枚举契约见 [expandDroppedEntries]）；false = 目录按类型不符跳过 */
    val acceptsDirectories: Boolean = false,
)
