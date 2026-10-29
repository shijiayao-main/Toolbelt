package com.jiaoay.easy_dev_toolbox.widget.task

import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.AppContextMenu
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.ContextMenuEntry
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.GhostChipButton
import com.jiaoay.easy_dev_toolbox.widget.LogViewerDialog
import com.jiaoay.easy_dev_toolbox.widget.TaskCard
import com.jiaoay.easy_dev_toolbox.widget.TaskUi
import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.io.File

/**
 * 任务列表段：xlog / logtag / filesplit / archive 四胞胎页
 * 逐字同构的「任务头（标题 + 计数 + 清空钮）+ 任务卡 LazyColumn」段。
 *
 * - 空态收口 [EmptyStateView] 四要素（[emptyHint] 作为状态文案，learningHint 固定
 *   「拖入文件即建卡」——要素④ processing 由列表非空即不渲染天然满足）；
 * - 任务卡 Running 态补**取消钮**（行尾 CANCEL 图标钮——cancel 圆环内 X 语义，
 *   与 AppButton 的 cancel 同源语言）；
 * - 清空 = L1 撤销（[StatusSignals.showUndo] 撤销条 + [FileTaskViewModel.restoreTasks]
 *   快照回填直连；撤销条经 :core 状态面下发、渲染壳仍在宿主 StatusHost，模块边界不变）；
 * - 菜单条目构建抽 [buildTaskContextMenuEntries] 顶层单源——
 *   右键（本件 AppContextMenu）与 TaskCard ⋯ 溢出钮（M3 DropdownMenu 直弹）
 *   同源同序，VM 动作经 [TaskListSectionActions] 组合期注入；
 * - 「送往下一步」条目（[SendToNextMenuEntry]，管线衔接）：来源页按产物
 *   类型注入目标（null = 该页产物无下一级映射，条目不出现）——与
 *   深度分析同走 [TaskListSectionActions] 单槽注入，TaskCard ⋯ 钮零签名改动。
 * - 「送飞书」条目（[ReportDelivery] 消费面）：成功产物经中立槽直发桥接收
 *   收会话。置灰不隐藏（enabled 形态——条目 HIG 可发现性优先），未就绪恒在
 *   菜单明示可发现性；目录产物不出现（桥接收发按文件消息语义）。
 *
 * 清空钮 = [GhostChipButton] 单源（leadingIcon 承载 CLEAR_ALL 带字形态；
 * 图标尺寸 = progressInline 行内档，经 ChipButton 内部单源）。
 */
@Composable
fun TaskListSection(
    viewModel: FileTaskViewModel,
    title: String,
    emptyHint: String? = null,
    /**
     * 卡头常驻行尾 hint（稿「转写任务」卡头 hint 常驻）：
     * null = 无（四签既有形态零改动）；VoicePage 传拖入提示。
     */
    headerHint: String? = null,
    onDeepAnalyze: ((String) -> Unit)? = null,
    onAiSummarize: ((TaskUi) -> Unit)? = null,
    /** 「送往下一步」条目配置（管线衔接；null = 不出现——产物无下一级映射或宿主未接线） */
    sendToNext: SendToNextMenuEntry? = null,
) {
    val tasks by viewModel.tasks.collectAsState()

    // 应用内查看器挂载态：「查看」条目（viewFile 槽承接）置入的产物路径；
    // null = 无查看器在台。Dialog 是独立窗口，挂载点在列表段内不影响布局。
    var pendingViewPath by remember { mutableStateOf<String?>(null) }

    // 菜单条目执行体动作承接注入（组合期 set——TaskListSection
    // 是 FileTaskViewModel 渲染的唯一 shell，⋯ 钮与右键条目执行体同源取此）
    TaskListSectionActions.openFolder = viewModel::openFolder
    TaskListSectionActions.openPath = viewModel::openFolderPath
    TaskListSectionActions.cancelTask = viewModel::cancelTask
    TaskListSectionActions.retryTask = viewModel::retryTask
    TaskListSectionActions.sendToNext = sendToNext
    TaskListSectionActions.sendToLark = viewModel::sendProductToLark
    // 「查看」默认承接 = 本段挂载的共享查看器 Dialog（isFile 门控在条目构建侧）
    TaskListSectionActions.viewFile = { pendingViewPath = it }

    pendingViewPath?.let { path ->
        LogViewerDialog(filePath = path, onDismiss = { pendingViewPath = null })
    }

    if (tasks.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$title (${tasks.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (headerHint != null) {
                Text(
                    text = headerHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                    modifier = Modifier.padding(start = Dimens.space8),
                )
            }
            // 清空钮 = .ic-btn 带字形态收口 GhostChipButton（raised 底 + 发丝边 +
            // leadingIcon 承载 CLEAR_ALL，图标随字同色）
            GhostChipButton(
                label = "清空列表",
                onClick = {
                    // L1 撤销：清空 = 内存态可恢复 → 直接执行 + 撤销条（clearTasks 内部
                    // 先 cancelAll——运行中任务走协作取消 + 产物收口，阻塞实现不被强停，
                    // 见 FileTaskViewModel.cancelTask 契约）；撤销 = restoreTasks 快照回填
                    val cleared = viewModel.clearTasks()
                    StatusSignals.showUndo("已清空 ${cleared.size} 个任务") {
                        viewModel.restoreTasks(cleared)
                    }
                },
                leadingIcon = MaterialSymbols.CLEAR_ALL,
            )
        }
        // P0：heightIn 有界化——TaskListSection 被 verticalScroll 父容器消费时
        // （VoicePage 整页滚动形态），LazyColumn 无界约束直接抛 IllegalStateException；
        // max 取 consoleHeightMax（日志/输出控制台同档），非滚动页不受影响
        // （有界高度内自然展开，不改变现状视觉）。
        LazyColumn(
            modifier = Modifier.heightIn(max = Dimens.consoleHeightMax),
            // 卡间 margin-bottom sp-6
            verticalArrangement = Arrangement.spacedBy(Dimens.space6),
        ) {
            items(tasks, key = { it.id }) { task ->
                // 右键菜单 = 打开文件夹/深度分析/AI 整理/取消（Success/Running 态条件
                // 条目；快照语义防闪现）。条目构建走 [buildTaskContextMenuEntries]——
                // 右键菜单与 TaskCard ⋯ 溢出钮同源同序（两处入口冗余但语义一致）。
                AppContextMenu(
                    items = { buildTaskContextMenuEntries(task, onDeepAnalyze, onAiSummarize) },
                ) {
                    TaskCard(
                        task = task,
                        onOpenFolder = viewModel::openFolder,
                        onDeepAnalyze = onDeepAnalyze,
                        onAiSummarize = onAiSummarize,
                        onCancel = viewModel::cancelTask,
                    )
                }
            }
        }
    } else if (emptyHint != null) {
        // 空态四要素：状态文案（页各异）+ 学习提示；要素④ processing 由
        // 「列表非空即不渲染本分支」天然满足（拖入即有卡）
        EmptyStateView(
            text = emptyHint,
            fillParent = false,
            learningHint = "拖入文件后自动开始处理，完成的产物会列在这里",
            icon = MaterialSymbols.INBOX,
            // ghost 卡容器（dashed 虚线盒=ghost 语义）+ 主文案 fs-14/600
            dashed = true,
            emphasized = true,
        )
    }
}

/**
 * 任务卡情境菜单条目（单源；internal = 同包 TaskCard.kt
 * ⋯ 钮调用点共享）：
 * - Success 态：打开文件夹 +（产物是普通文件）查看 +（onDeepAnalyze≠null）深度分析
 *   +（onAiSummarize≠null）AI 整理 +（[TaskListSectionActions.sendToNext]≠null）送往下一步（管线衔接）
 *   +（产物是普通文件）送飞书（[ReportDelivery.ready] 置灰判据单源——未就绪置灰不隐藏）；
 * - Running 态：取消任务（danger）；
 * - Error 态：打开文件夹（产物目录在盘——失败也有出路）+ 重试（原输入重新入队）；
 * - Cancelled 态：无条目（空列表——右键不弹、⋯ 钮不渲染，两入口同判）。
 *
 * 右键（TaskListSection AppContextMenu）与左键（TaskCard ⋯ 钮 DropdownMenu）
 * 共享本函数——条目序/文案/danger 标记单源，防两处漂移。
 */
internal fun buildTaskContextMenuEntries(
    task: TaskUi,
    onDeepAnalyze: ((String) -> Unit)?,
    onAiSummarize: ((TaskUi) -> Unit)?,
): List<ContextMenuEntry> = buildList {
    val success = task.status as? TaskUiStatus.Success
    if (success != null) {
        add(
            ContextMenuEntry("打开文件夹") {
                TaskListSectionActions.openFolder(task.id)
            },
        )
        // 应用内查看：产物是普通文件才出现（目录产物自动缺席——查看器只读单文件）；
        // 承接动作走 viewFile 槽（TaskListSection 挂共享查看器 Dialog）
        if (File(success.outputPath).isFile) {
            add(ContextMenuEntry("查看") {
                TaskListSectionActions.viewFile?.invoke(success.outputPath)
            })
        }
        onDeepAnalyze?.let { deep ->
            add(ContextMenuEntry("深度分析") { deep(success.outputPath) })
        }
        onAiSummarize?.let { ai ->
            add(ContextMenuEntry("AI 整理") { ai(task) })
        }
        TaskListSectionActions.sendToNext?.let { next ->
            add(ContextMenuEntry(next.label) { next.onSend(success.outputPath) })
        }
        // 送飞书：产物直发桥接接收会话（ReportDelivery 中立槽，VM 动作经
        // sendToLark 槽注入）。置灰判据 = [ReportDelivery.ready] 单源——enabled
        // 形态置灰不隐藏（菜单条目无 tooltip 能力，未就绪原因的明示交给回执 toast：
        // 强行点击无路可走，恒由 enabled 拦下；条目恒在场保可发现性）。ready 在
        // 条目求值瞬间读取——右键是打开瞬间快照、⋯ 钮在展开重组时重算，两入口
        // 均取当下值，菜单开着时不闪现切换（与 AppContextMenu 快照语义一致）。
        // isFile 门控同「查看」：目录产物不可作文件消息外发，条目自动缺席。
        if (File(success.outputPath).isFile) {
            add(
                ContextMenuEntry("送飞书", enabled = ReportDelivery.ready.value) {
                    TaskListSectionActions.sendToLark(task.id)
                },
            )
        }
    }
    val error = task.status as? TaskUiStatus.Error
    if (error != null) {
        // 失败卡出路两件：目录可达（folderPath = 输入文件所在目录——产物同目录约定）
        // + 重试（修正输入后原地重跑，不必重新拖拽）
        error.folderPath?.let { path ->
            add(ContextMenuEntry("打开文件夹") {
                TaskListSectionActions.openPath(path)
            })
        }
        add(ContextMenuEntry("重试") {
            TaskListSectionActions.retryTask(task.id)
        })
    }
    if (task.status is TaskUiStatus.Running) {
        add(
            ContextMenuEntry("取消任务", danger = true) {
                TaskListSectionActions.cancelTask(task.id)
            },
        )
    }
}

/**
 * 「送往下一步」菜单条目配置（管线衔接）：来源页按产物类型注入——
 * [label] 含目标工具名（如「送往 Tag 过滤」，动作动词开头对齐既有条目风格），
 * [onSend] 承接成功产物路径投递。null（条目缺席）由来源页按
 * [com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff.nextToolTargetOf]
 * 的映射缺席（产物无下一级）或宿主未接线决定。
 */
class SendToNextMenuEntry(
    val label: String,
    val onSend: (productPath: String) -> Unit,
)

/**
 * 菜单条目执行体的动作承接（internal 同包共享）：[buildTaskContextMenuEntries]
 * 是顶层纯函数（TaskCard 与 TaskListSection 两处调用点同源），VM 动作经本
 * object 在组合时注入——文件级单槽（FileTaskViewModel 每 shell 组一个，
 * TaskListSection 渲染 shell 即注入点，组合期 set 先于任何菜单点击，无竞态面）。
 */
internal object TaskListSectionActions {
    var openFolder: (String) -> Unit = {}
    var openPath: (String) -> Unit = {}
    var cancelTask: (String) -> Unit = {}
    var retryTask: (String) -> Unit = {}
    var sendToNext: SendToNextMenuEntry? = null

    /** 「送飞书」条目承接槽（任务 id → 产物外发）：TaskListSection 组合期注入 VM 动作 */
    var sendToLark: (String) -> Unit = {}

    /** 「查看」条目承接槽（产物路径 → 应用内查看器）：TaskListSection 组合期注入共享 Dialog 挂载 */
    var viewFile: ((String) -> Unit)? = null
}
