package com.jiaoay.easy_dev_toolbox.page.xlog

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.widget.task.FileTaskViewModel
import com.jiaoay.easy_dev_toolbox.page.logkit.LogKitConfigStore
import com.jiaoay.easy_dev_toolbox.widget.task.ProcessResult
import com.jiaoay.easy_dev_toolbox.widget.TaskUi
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.xlog.LogDecode
import com.jiaoay.xlog.LogFormatDetector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class XLogDecoderViewModel(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileTaskViewModel(ioDispatcher) {

    private val _privateKey = MutableStateFlow("")
    val privateKey: StateFlow<String> = _privateKey.asStateFlow()

    init {
        viewModelScope.launch {
            _privateKey.value = LogKitConfigStore.getXLogPrivateKey()
        }
    }

    // 私钥输入变更事件流：Channel 而非 StateFlow——StateFlow 的初值重放会在 debounce
    //    超时后落盘空串，把已配置的私钥静默擦空（会话内 UI 读内存态无感，重启后才暴露
    //    为丢失）；等值合并还会吞掉「清空回初值」的显式变更。Channel 无初值无合并，
    //    仅用户真实变更（含清空）会送达。400ms debounce——粘贴 64 位 hex 会触发几十次
    //    preferences_pb 整文件重写（桥接页 PERSIST_DEBOUNCE_MILLIS 同档 trailing 语义）。
    private val privateKeyChanges = Channel<String>(Channel.BUFFERED)

    init {
        viewModelScope.launch {
            privateKeyChanges
                .receiveAsFlow()
                .debounce(400.milliseconds)
                .collect { key -> LogKitConfigStore.setXLogPrivateKey(key) }
        }
    }

    fun onPrivateKeyChange(key: String) {
        _privateKey.value = key
        privateKeyChanges.trySend(key)
    }

    /**
     * 产物文件（同目录同名 + .txt 后缀）——仅解密路产出；明文路不再产出文件，
     * 产物即源文件自身（[process] 返回源路径，不落新文件）。
     */
    private fun outputFileOf(file: File): File {
        val parentPath = file.parent
        return if (parentPath.isNullOrEmpty()) File("${file.name}.txt") else File(parentPath, "${file.name}.txt")
    }

    // 目标集按解密路上界声明（明文路不产文件）：多报的目标只让单飞去重键偏宽
    // （不同输入文件的目标名互异，误拦面为零），不影响正确性；目标名计算是同步
    // 纯函数，不做内容嗅探（嗅探是阻塞 IO，只允许在 [process] 的 IO 上下文内发生）
    override fun outputTargetsOf(file: File): Set<String> = setOf(outputFileOf(file).absolutePath)

    /**
     * 重跑冲突检测：产物已在盘（库层不再存在幂等硬拒面——重跑走覆盖确认卡）。
     * 源文件后缀为明文嫌疑后缀时短路空集——明文路（内容判型 PLAINTEXT）不产新
     * 文件，重跑不该弹覆盖确认。
     *
     * 边界登记：明文内容误挂加密后缀的文件仍按解密路产物名弹一次确认，确认后
     * 明文路不写盘（多一次确认点击，无误删风险——覆盖清理恒 no-op）；真段结构
     * 内容挂明文嫌疑后缀走解密路时，短路漏报冲突，产物由解密路原子替换写
     * （temp + ATOMIC_MOVE，失败保旧产物），同样无误删风险。
     */
    override fun existingOutputConflictsOf(file: File): List<File> {
        if (file.extension.lowercase() in PLAINTEXT_SUSPECT_EXTENSIONS) return emptyList()
        return outputFileOf(file).takeIf { it.isFile }?.let(::listOf) ?: emptyList()
    }

    /**
     * 覆盖不预删：decode 是原子替换写（temp + ATOMIC_MOVE，失败保旧产物），预删
     * 反而丢「失败保旧产物」语义；明文路不写盘，无可预删面。
     */
    override fun deleteStaleOutputsOf(file: File) = Unit

    /**
     * 取消收口：产物文件在盘即如实回填（卡面「已取消 · 部分产物已保留」）。
     * 不删：阻塞 decode 不被强停——取消被观察到时产物可能已被整文件原子替换
     * （LogDecode temp 全量成功才 ATOMIC_MOVE），重写后的产物与提交前已存在的
     * 历史产物在盘上不可区分，删除有破坏历史产物风险。明文路取消窗口极小
     * （process 无挂起点），且其产物目标（源文件 + .txt）通常不在盘——回执
     * 恒 null（明文路不产新文件，无可回填面），语义自洽。
     */
    override fun onTaskCancelled(file: File): String? {
        val out = outputFileOf(file)
        return if (out.isFile) out.absolutePath else null
    }

    override suspend fun process(file: File): ProcessResult {
        // 内容判型三态路由（后缀不可信，统一以内容嗅探为准——LogFormatDetector 门面契约）
        val result = when (LogFormatDetector.detect(file)) {
            LogFormatDetector.Format.XLOG_SEGMENTS -> decodeToProduct(file)
            LogFormatDetector.Format.PLAINTEXT ->
                // 明文路不拷贝：产物即源文件自身，note 明示未生成新文件（静默无产物
                // 变化会让「解密页跑了个寂寞」不可知）
                ProcessResult.Success(file.absolutePath, note = PLAINTEXT_SUCCESS_NOTE)
            LogFormatDetector.Format.UNKNOWN ->
                // 加密后缀 = 用户解密意图（LogFormatDetector.routeFor 口径）：仍走
                // decode，失败由解密器如实报错；无意图后缀则明示拒绝，不猜
                if (file.extension.lowercase() in DECODE_INTENT_EXTENSIONS) decodeToProduct(file)
                else ProcessResult.Failure(UNKNOWN_FORMAT_REASON)
        }
        // 使用计数口径：任务成功 = 一次真实使用（非页面浏览；失败不计）
        if (result is ProcessResult.Success) {
            ToolUsageSignal.report(ToolUsageSignal.PageId.XLOG_DECODER)
        }
        return result
    }

    /** 解密路：阻塞解码到 [outputFileOf] 目标（调用方保证处于 IO 上下文） */
    private fun decodeToProduct(file: File): ProcessResult {
        val out = outputFileOf(file)
        LogDecode().decodeFile(
            privateKey = _privateKey.value,
            logFile = file,
            outFile = out,
        )
        return ProcessResult.Success(out.absolutePath)
    }

    companion object {
        @Composable
        fun getViewModel(): XLogDecoderViewModel = viewModel { XLogDecoderViewModel() }

        private val PLAINTEXT_SUSPECT_EXTENSIONS = setOf("txt", "log")

        private val DECODE_INTENT_EXTENSIONS = setOf("xlog", "alog")

        private const val PLAINTEXT_SUCCESS_NOTE = "已是明文日志，无需解密（未生成新文件）"

        private const val UNKNOWN_FORMAT_REASON =
            "无法识别的文件格式——既非加密日志（xlog/alog 段结构）也非明文日志，已跳过"
    }
}
