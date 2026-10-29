package com.jiaoay.easy_dev_toolbox.widget.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.DesktopUtils
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.widget.TaskUi
import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 文件任务流基类（xlog / logtag / filesplit / archive 四胞胎 VM 的收敛）：
 * 任务登记（taskFiles map）、任务卡状态机、openFolder、clearTasks 全部收敛于此；
 * 子类只实现 [process]（IO 内执行，返回成功输出路径或失败原因）与可选 [validate]（整批判校验）。
 *
 * 行为逐点对齐三个原 VM：
 * - [addFiles] 先走 [validate]，再逐文件建卡（Running 态）并逐个异步执行；
 * - [process] 在 [ioDispatcher] 执行；异常兜底为 Error(e.message ?: "未知错误")；
 * - 成功任务 [openFolder]（IO 上下文 DesktopUtils.openInFolder），列表可清空。
 *
 * ## 取消语义（协作式 + 收口，非强停）
 * [cancelTask] 无法强停阻塞实现：任务在下一个挂起点（withContext 边界或
 * [ensureTaskActive] 检查点）观察到取消，基类于该点经 [onTaskCancelled] 收口产物
 * 并落 Cancelled 态——卡面与磁盘终态一致（不谎报「未产生产物」）。带内循环的
 * process 实现须逐天然边界调用 [ensureTaskActive]，才能做到「取消即停止写产物」。
 * 提交按产物目标单飞去重（[outputTargetsOf] + 输入文件路径）：同目标的在途任务
 * 未结束时拒绝重复提交，跳过经 StatusSignals 明示原因。
 *
 * 席位：原在 `:feature-logkit` 的 `page.logkit` 包下，但依赖只有
 *    `:core`（DesktopUtils）与 `:ui`（TaskUi/TaskUiStatus）——**零 :log-tools、零 feature
 *    内部依赖**。解压页（`:feature-archive`）需复用同一任务流，而 feature 隔离守护禁止
 *    跨 feature import 页面域包，故上提至 `:ui` 成为共享组件（纯移动，零依赖成本）。
 */
abstract class FileTaskViewModel(
    /**
     * 任务 IO 线程域（[process] 执行、openFolder/openFolderPath 的桌面调用承载）。
     * 契约：默认 [Dispatchers.IO]，宿主装配点零改动；测试注入与 Main 共享 TestScheduler
     * 的受控调度器后，任务续体与 Main 队列同源确定性推进。子类自有 IO 段复用同一缝。
     */
    protected val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _tasks = MutableStateFlow<List<TaskUi>>(emptyList())
    val tasks: StateFlow<List<TaskUi>> = _tasks.asStateFlow()

    /** 是否有任务在途（Running）——拖放区 busy 门禁与提交防抖消费 */
    val hasRunningTasks: StateFlow<Boolean> = _tasks
        .map { list -> list.any { it.status is TaskUiStatus.Running } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 页面级输入错误（整批判校验失败时的提示；输入变化时由子类清空） */
    protected val _inputError = MutableStateFlow<String?>(null)
    val inputError: StateFlow<String?> = _inputError.asStateFlow()

    /** 产物在盘冲突的挂起裁决批（确认卡消费；null = 无挂起） */
    data class OverwriteConfirmRequest(
        /** 待裁决输入文件（确认后按原批次提交） */
        val files: List<File>,
        /** 在盘冲突产物名（确认卡文案——用户须知覆盖会清掉什么） */
        val conflicts: List<String>,
    )

    private val _overwriteRequest = MutableStateFlow<OverwriteConfirmRequest?>(null)
    val overwriteRequest: StateFlow<OverwriteConfirmRequest?> = _overwriteRequest.asStateFlow()

    fun dismissInputError() {
        _inputError.value = null
    }

    private val taskFiles = mutableMapOf<String, File>()

    /** 在途任务的产物目标路径集合（任务 id → 目标集；单飞去重键，终态清理与 taskFiles 同点） */
    private val taskTargets = mutableMapOf<String, Set<String>>()

    /** 终态任务的输入文件留档（Error 卡「重试」数据源——taskFiles 在 coroutine finally 已清） */
    private val retryableInputs = mutableMapOf<String, File>()

    private val runningJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** 整批判校验（logtag 的 regex/name 校验 / filesplit 的 sizeMb 下限用） */
    protected open fun validate(files: List<File>): Validation = Validation.Passed

    /**
     * // OPEN(任务基座): 长任务零进度反馈——process 无进度回调缝，任务卡 Running 态
     * 无进度面（大文件解码/解压期间只有旋转语义无量化推进）。re-entry 条件 =
     * 「大文件疑似卡死」的用户反馈成立时开缝：process 增加可选进度回调参数 +
     * [TaskUiStatus.Running] 进度面渲染，同处改动不单独立项。
     */
    protected abstract suspend fun process(file: File): ProcessResult

    /**
     * 任务产物目标路径集合（在途去重键）。子类按各自产物命名规则声明；
     * 空集 = 产物路径不可预先计算，此时退化到输入文件路径去重（基类恒并入输入路径）。
     */
    protected open fun outputTargetsOf(file: File): Set<String> = emptySet()

    /**
     * 协作式取消检查点：process 实现的内循环在天然边界（逐条目/逐块）调用——
     * 任务被用户取消后由此抛出，实现「取消即停止写产物」。阻塞实现（无挂起点的
     * 单次库调用）无法被强停，取消后在 withContext 边界被观察到（见 [cancelTask] 契约）。
     */
    protected suspend fun ensureTaskActive() {
        currentCoroutineContext().ensureActive()
    }

    /**
     * 提交前冲突检测：产物目标已在盘（库层 exists 幂等契约会把重跑同文件硬拒成死端——
     * feature 层重跑 affordance 的检测半边；删除/跳过由确认卡收口）。默认按
     * [outputTargetsOf] 比对在盘；产物名不可预计算的子类（前缀命名族）按各自规则覆写。
     */
    protected open fun existingOutputConflictsOf(file: File): List<File> =
        outputTargetsOf(file).map(::File).filter { it.exists() }

    /**
     * 用户确认「覆盖重跑」后的旧产物清理（提交前调用）。默认删 [existingOutputConflictsOf]
     * 命中面；进程内可安全覆写的子类（原子替换/overwrite 写）覆写为 no-op——预删会
     * 破坏「失败保旧产物」语义。
     */
    protected open fun deleteStaleOutputsOf(file: File) {
        existingOutputConflictsOf(file).forEach { it.delete() }
    }

    /**
     * 用户取消的产物收口：返回在盘的部分产物路径（卡面「已取消 · 部分产物已保留」
     * 渲染）或 null（无产物在盘）。默认不删产物——部分产物是派生物（可重跑再生），
     * 是否删除由子类按产物语义定夺；🔴 不得删除提交时已存在的产物目标（可能是历史
     * 运行的结果，删除即破坏他次产物）。
     */
    protected open fun onTaskCancelled(file: File): String? = null

    open fun addFiles(files: List<File>) {
        when (val verdict = validate(files)) {
            is Validation.SilentReject -> return
            is Validation.Error -> {
                _inputError.value = verdict.message
                return
            }
            Validation.Passed -> _inputError.value = null
        }
        // 重跑 affordance：产物在盘的文件不入队，挂起等用户裁决（覆盖/取消）——
        // 直接提交会被库层 exists 幂等硬拒成 Error 死端（与「修正后重新拖入」的
        // 自家文案自相矛盾）。无冲突文件照常立即处理，不被同批挂起项阻断。
        val clean = mutableListOf<File>()
        val conflicts = mutableListOf<Pair<File, List<File>>>()
        files.forEach { file ->
            val stale = existingOutputConflictsOf(file)
            if (stale.isEmpty()) clean += file else conflicts += file to stale
        }
        clean.forEach { file -> submitTask(fileName = file.name, file = file) }
        if (conflicts.isNotEmpty()) {
            _overwriteRequest.update { current ->
                if (current == null) {
                    OverwriteConfirmRequest(
                        files = conflicts.map { it.first },
                        conflicts = conflicts.flatMap { (_, stale) -> stale.map { it.name } }.distinct(),
                    )
                } else {
                    // 挂起裁决未结再拖同冲突文件：合并（先前的待裁意图不丢——单槽覆写会静默吞意图）
                    OverwriteConfirmRequest(
                        files = (current.files + conflicts.map { it.first }).distinctBy { it.canonicalPath },
                        conflicts = (current.conflicts + conflicts.flatMap { (_, stale) -> stale.map { it.name } })
                            .distinct(),
                    )
                }
            }
        }
    }

    /** 覆盖重跑确认：清理旧产物（[deleteStaleOutputsOf] 口径）后照常提交 */
    fun confirmOverwrite() {
        val request = _overwriteRequest.value ?: return
        _overwriteRequest.value = null
        request.files.forEach { file ->
            runCatching { deleteStaleOutputsOf(file) }
                .onFailure {
                    JLog.w(TAG) { "覆盖前清理旧产物失败：file=${file.name} err=${it.message?.take(200)}" }
                }
            submitTask(fileName = file.name, file = file)
        }
    }

    /** 覆盖重跑取消：丢弃挂起批（旧产物原样保留） */
    fun dismissOverwrite() {
        _overwriteRequest.value = null
    }

    protected fun submitTask(fileName: String, file: File, groupBadge: String? = null) {
        // 单飞去重：同产物目标（或同输入文件）的在途任务未结束时拒绝重复提交——
        // 两个并发写者交错写同一产物会互相损坏。拒绝必带原因（StatusSignals 明示），
        // 跳过不阻断同批次其余文件。去重判定读 taskTargets/_tasks（主线程单写点），
        // 与 runTask 的建卡同线程，无并发窗口。
        val targets = taskTargetsOf(file)
        val runningIds = _tasks.value
            .filter { it.status is TaskUiStatus.Running }
            .mapTo(mutableSetOf()) { it.id }
        if (runningIds.any { id -> taskTargets[id].orEmpty().any { it in targets } }) {
            StatusSignals.show("已跳过「$fileName」——同一产物的任务正在处理中")
            return
        }
        val task = TaskUi(
            id = UUID.randomUUID().toString(),
            fileName = fileName,
            status = TaskUiStatus.Running,
            groupBadge = groupBadge,
        )
        taskFiles[task.id] = file
        taskTargets[task.id] = targets
        retryableInputs[task.id] = file
        _tasks.update { it + task }
        runTask(task)
    }

    private fun taskTargetsOf(file: File): Set<String> =
        outputTargetsOf(file) + file.canonicalPath

    private fun runTask(task: TaskUi) {
        val file = taskFiles[task.id] ?: return
        val job = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { process(file) }
                val status = when (result) {
                    is ProcessResult.Success -> TaskUiStatus.Success(result.outputPath, result.note)
                    is ProcessResult.Failure -> TaskUiStatus.Error(
                        message = result.reason,
                        // Error 卡「打开文件夹」路径锚：产物与输入同目录（四胞胎页产物命名
                        // 全部落输入文件旁）——失败也给出路
                        folderPath = file.parent,
                    )
                }
                updateTaskStatus(task.id, status)
            } catch (e: Throwable) {
                // 四胞胎页（xlog/logtag/filesplit/archive）共享本基类：异常必须 JLog
                // 落 app.log（任务卡只在进程内可见，事后排障无凭据）。
                // CancellationException 是结构化取消：用户主动取消 → Cancelled 态（不记失败）；
                // 页面销毁（VM onCleared）走 VM scope 整体取消 → 静默重抛（不写任务卡）。
                if (e is kotlinx.coroutines.CancellationException) {
                    if (runningJobs[task.id] == null || e.message == TASK_CANCEL_FLAG) {
                        // 用户取消：产物收口（回填 partialOutput——卡面与磁盘一致）后如实置卡
                        val partialOutput = runCatching { onTaskCancelled(file) }
                            .onFailure {
                                JLog.w(TAG) { "取消产物收口失败：file=${file.name} err=${it.message?.take(200)}" }
                            }
                            .getOrNull()
                        updateTaskStatus(task.id, TaskUiStatus.Cancelled(partialOutput))
                        throw e
                    }
                    updateTaskStatus(task.id, TaskUiStatus.Cancelled())
                    throw e
                }
                JLog.w(TAG) { "任务处理异常：file=${file.name} err=${e.message?.take(200) ?: e::class.simpleName}" }
                updateTaskStatus(
                    task.id,
                    TaskUiStatus.Error(
                        message = describeFileTaskError(e) ?: (e.message ?: "未知错误"),
                        folderPath = file.parent,
                    ),
                )
            } finally {
                runningJobs.remove(task.id)
                // C2-#4 · 终态清理 File 映射：process 已完成（含取消/异常），文件句柄
                // 引用不再需要——与 runningJobs 同点收口，防长会话 taskFiles 单调累积
                taskFiles.remove(task.id)
                taskTargets.remove(task.id)
            }
        }
        runningJobs[task.id] = job
    }

    /**
     * 取消单个任务：状态切 Cancelled + Job 取消 + 产物收口（[onTaskCancelled]）。
     *
     * 收口双写点：本函数在取消点同步收口一次——启动前取消时 coroutine 体不会执行
     * （取消先于激活，catch 分支收口不可达），此处是唯一必达收口点；已启动任务的
     * catch 分支在取消观察点重算同一结果幂等覆写（阻塞实现取消后仍会写完产物，
     * 覆写取到更完整的在盘终态）。
     *
     * 取消是「协作式 + 收口」，非「强停」：process 的阻塞实现（无挂起点）无法被
     *    强停，取消后会跑完当次调用，在下一个挂起点（withContext 边界或
     *    [ensureTaskActive] 检查点）观察到取消——基类于该点收口产物并落 Cancelled，
     *    卡面与磁盘终态一致；「取消即停止写产物」仅在实现提供了检查点的内循环中成立。
     * 非运行态任务为幂等 no-op。
     */
    fun cancelTask(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        if (task.status != TaskUiStatus.Running) return
        // 先移除映射再 cancel：catch 分支据 runningJobs 缺席判定「已由用户取消」，
        // 不覆盖 Cancelled 态为 Error（双保险——cancel 后 catch 再跑的窗口）
        runningJobs.remove(taskId)?.cancel(
            kotlinx.coroutines.CancellationException(TASK_CANCEL_FLAG),
        )
        // taskFiles 仅在 coroutine 的 finally 清理（晚于置卡）——Running 态下映射必在；
        // 缺席兜底 null（收口降级为无回执），不阻断取消本身
        val partialOutput = taskFiles[taskId]?.let { file ->
            runCatching { onTaskCancelled(file) }
                .onFailure {
                    JLog.w(TAG) { "取消产物收口失败：file=${file.name} err=${it.message?.take(200)}" }
                }
                .getOrNull()
        }
        updateTaskStatus(taskId, TaskUiStatus.Cancelled(partialOutput))
    }

    /** P3 · 取消全部运行中任务（clearTasks 前置步骤；逐个走 [cancelTask]） */
    fun cancelAll() {
        _tasks.value
            .filter { it.status == TaskUiStatus.Running }
            .forEach { cancelTask(it.id) }
    }

    fun clearTasks(): List<TaskUi> {
        cancelAll()
        val cleared = _tasks.value
        _tasks.value = emptyList()
        taskFiles.clear()
        taskTargets.clear()
        retryableInputs.clear()
        _overwriteRequest.value = null
        return cleared
    }

    /** P3 · 撤销恢复：把 [clearTasks] 返回的快照放回列表（卡片记录恢复；产物不回滚） */
    fun restoreTasks(cleared: List<TaskUi>) {
        if (cleared.isEmpty()) return
        _tasks.value = cleared
    }

    private fun updateTaskStatus(taskId: String, status: TaskUiStatus) {
        _tasks.update { list ->
            list.map { if (it.id == taskId) it.copy(status = status) else it }
        }
    }

    fun openFolder(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        val outputPath = (task.status as? TaskUiStatus.Success)?.outputPath ?: return
        viewModelScope.launch(ioDispatcher) {
            DesktopUtils.openInFolder(outputPath)
        }
    }

    /** 按路径打开所在文件夹（Error 卡「打开文件夹」条目——产物目录即使任务失败也存在） */
    fun openFolderPath(path: String) {
        viewModelScope.launch(ioDispatcher) {
            DesktopUtils.openInFolder(path)
        }
    }

    /**
     * 成功产物「送飞书」：经 :core [ReportDelivery] 中立槽以文件消息直发桥接接收
     * 会话（任务卡产物人工搬运断点的收口，菜单条目在 [buildTaskContextMenuEntries]）。
     *
     * - 产物来源 = 终态 [TaskUiStatus.Success] 的 [TaskUiStatus.Success.outputPath]；
     *   非 Success 态或产物非普通文件（目录不可作文件消息外发）静默 no-op——
     *   菜单条目同口径门控，本守卫只兜底非菜单调用面；
     * - [ReportDelivery.Channel.deliver] 的 null = 已送出**成功**语义——不可 elvis
     *   兜底（会把成功吞成失败），先收 reason 再判空分叉回执；
     * - 失败人话由通道归一（未接线/未运行/未配置会话/出站拒绝/传输失败），
     *   此处只做回执 toast——置灰判据在菜单（真源 = [ReportDelivery.ready] 单源）。
     */
    fun sendProductToLark(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        val outputPath = (task.status as? TaskUiStatus.Success)?.outputPath ?: return
        val file = File(outputPath)
        if (!file.isFile) return
        viewModelScope.launch {
            val reason = ReportDelivery.deliver(file)
            if (reason == null) {
                StatusSignals.show("已送出至飞书会话：${file.name}")
            } else {
                StatusSignals.show(reason)
            }
        }
    }

    /** 失败任务重试：原输入文件重新入队（新任务卡；旧 Error 卡留作历史） */
    fun retryTask(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        val file = retryableInputs[taskId] ?: run {
            StatusSignals.show("无法重试「${task.fileName}」——原文件引用已失效（清空列表后不可恢复）")
            return
        }
        submitTask(fileName = task.fileName, file = file, groupBadge = task.groupBadge)
    }

    companion object {
        private const val TAG = "FileTask"

        /** 取消标记：cancel(cause) 的 message——catch 分支据此区分用户取消与页面销毁 */
        internal const val TASK_CANCEL_FLAG = "user-cancel"
    }
}

/** 整批判校验结果：[Passed] 放行 / [SilentReject] 静默拒绝（无提示）/ [Error] 拒绝并置 inputError */
sealed class Validation {
    data object Passed : Validation()
    data object SilentReject : Validation()
    data class Error(val message: String) : Validation()
}

/** 单文件处理结果（工具方法直接返回的 outputFile 或解码类自拼 outFile 统一为此） */
sealed class ProcessResult {
    /**
     * [note] 非空 = 成功但带须用户可知的降级标注（如转写超长截断——静默丢数据
     * 零容忍，任务卡随产物路径一并呈现）。
     */
    data class Success(val outputPath: String, val note: String? = null) : ProcessResult()
    data class Failure(val reason: String) : ProcessResult()
}

/**
 * 解密/解析异常的中文分类（任务卡与 palette 直执行共用的四要素文案——英文原始
 * 异常直上不可读）。lib 层保持英文原文零依赖纪律，映射在宿主层收口。
 *
 * 分类判据（xlog 域两类可检 + 格式兜底）：
 * - **密钥缺失**：TeaKeyResolver.resolve 哨兵消息（含加密段而无私钥）；
 * - **私钥格式错误**：hex 解析（NumberFormatException）/ EC 密钥规格族异常——
 *   64 位 hex 粘贴错误（缺位/非 hex 字符）的典型形态；
 * - **格式错误**：其余 IllegalArgumentException（段头/长度/点解码）——文件非完整
 *   xlog/alog 结构。
 * 「密钥值错误」（hex 合法但不是该日志的钥）解码期不可检——ECDH 协商恒成功、
 * TEA 解密恒产出乱码不抛错，如实不设该分类（见任务卡文案指引重配私钥）。
 * 返回 null = 未识别形态（调用方回退 e.message）。
 */
fun describeFileTaskError(e: Throwable): String? {
    val message = e.message ?: return null
    return when {
        "private key needs to be entered" in message ->
            "解密失败：私钥缺失——该日志含加密段，请先粘贴 64 位 hex 私钥后重试"
        e is NumberFormatException ->
            "解密失败：私钥格式错误——私钥须为 64 位 hex 字符（0-9/a-f），请检查后重试"
        e is java.security.spec.InvalidKeySpecException ->
            "解密失败：私钥格式错误——私钥须为 64 位 hex 字符（0-9/a-f），请检查后重试"
        e is IllegalArgumentException ->
            "解密失败：日志格式错误——文件可能不完整或非 xlog/alog 结构（$message）"
        else -> null
    }
}
