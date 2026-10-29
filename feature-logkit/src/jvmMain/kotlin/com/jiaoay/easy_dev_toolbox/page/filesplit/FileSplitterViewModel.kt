package com.jiaoay.easy_dev_toolbox.page.filesplit

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.widget.task.FileTaskViewModel
import com.jiaoay.easy_dev_toolbox.page.logkit.LogKitConfigStore
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.widget.task.ProcessResult
import com.jiaoay.easy_dev_toolbox.widget.task.Validation
import com.jiaoay.log.LogSplitter
import com.jiaoay.log.ToolsResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class FileSplitterViewModel(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileTaskViewModel(ioDispatcher) {

    private val _sizeMb = MutableStateFlow(10)
    val sizeMb: StateFlow<Int> = _sizeMb.asStateFlow()

    init {
        viewModelScope.launch {
            _sizeMb.value = LogKitConfigStore.getFileSplitSizeMb()
        }
    }

    fun onSizeMbChange(sizeMb: Int) {
        if (sizeMb < 1) return // 下限保护：非法终值不落生效值（页面 CommitNumberField 非法输入不提交，双保险）
        _sizeMb.value = sizeMb
        viewModelScope.launch {
            LogKitConfigStore.setFileSplitSizeMb(sizeMb)
        }
    }

    override fun validate(files: List<File>): Validation {
        // sizeMb < 1 属非法配置：拒绝整批并置 inputError 明示（旧 SilentReject 静默丢弃拖入，
        // 用户以为已按废除的旧值执行——显示值与生效值不一致时尤其误导）
        return if (_sizeMb.value < 1) {
            Validation.Error("分割大小需 ≥ 1 MB，请修正后重新拖入")
        } else {
            Validation.Passed
        }
    }

    /**
     * 取消收口：按分卷名形扫同目录，在盘即如实回填。不删：总卷数不可预知，
     * 前缀命中的分卷无法区分本次运行产出与提交前已存在的旧卷，删除有破坏风险。
     */
    override fun onTaskCancelled(file: File): String? {
        val pattern = volumePatternOf(file)
        val first = (file.parentFile ?: File("."))
            .listFiles { f -> f.isFile && pattern.matches(f.name) }
            ?.firstOrNull() ?: return null
        return first.absolutePath
    }

    override fun outputTargetsOf(file: File): Set<String> = emptySet()

    /** 分卷名形（LogSplitter 同源：`<名>_NNN.txt`，NNN 三位零填充；Regex.escape 防文件名元字符） */
    private fun volumePatternOf(file: File): Regex =
        Regex("^${Regex.escape(file.nameWithoutExtension)}_\\d{3}\\.txt$")

    /** 重跑冲突检测：首卷在盘（库层只查首片即判存在——与库判定同口径的死端面） */
    override fun existingOutputConflictsOf(file: File): List<File> {
        val first = File(file.parent ?: ".", "${file.nameWithoutExtension}_${(0).toString().padStart(3, '0')}.txt")
        return if (first.isFile) listOf(first) else emptyList()
    }

    /**
     * 覆盖清理：按分卷名形删净全部旧卷（不只首片——残留旧卷会与新卷序号混编，
     * 按前缀枚举卷数的消费面会数出错误集合）。
     */
    override fun deleteStaleOutputsOf(file: File) {
        val pattern = volumePatternOf(file)
        (file.parentFile ?: File(".")).listFiles { f -> f.isFile && pattern.matches(f.name) }?.forEach { it.delete() }
    }

    override suspend fun process(file: File): ProcessResult {
        val result = LogSplitter.splitBySize(file, _sizeMb.value)
        return when (result) {
            is ToolsResult.Success -> {
                // 使用计数口径：分割执行成功 = 一次真实使用
                ToolUsageSignal.report(ToolUsageSignal.PageId.FILE_SPLITTER)
                // 成功卡标注卷数（兜底——「深度分析」已投全卷，卡面仍让 N 卷规模可辨）
                val volumePattern = volumePatternOf(file)
                val volumeCount = (file.parentFile ?: File("."))
                    .listFiles { f -> f.isFile && volumePattern.matches(f.name) }
                    ?.size ?: 1
                ProcessResult.Success(
                    result.outputFile.absolutePath,
                    note = if (volumeCount > 1) "共 $volumeCount 卷" else null,
                )
            }
            is ToolsResult.Failure -> ProcessResult.Failure(result.reason)
        }
    }

    companion object {
        @Composable
        fun getViewModel(): FileSplitterViewModel = viewModel { FileSplitterViewModel() }
    }
}
