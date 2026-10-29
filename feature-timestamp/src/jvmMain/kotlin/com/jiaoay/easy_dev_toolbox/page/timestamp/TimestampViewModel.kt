package com.jiaoay.easy_dev_toolbox.page.timestamp

import com.jiaoay.easy_dev_toolbox.core.extensions.ConversionResult
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 时间戳转换：输入与输出状态收敛，转换逻辑委托 [TimestampConverter] 纯函数。
 *
 * - 输出格式预设经 TimestampConfigStore 持久化（存枚举 name），init 恢复；
 *   非法存量值（如手工改坏的 DataStore）经 runCatching valueOf 回退 [TimestampFormat.LOG]
 * - [convert] 由页面防抖流调用：空输入置 [ConversionResult.Empty]（清空输出，不报错）
 * - [onFormatChange] 切换即持久化，并对已有输入立即按新格式重算（单行与批量同语义）
 * - [nowMillis] 500ms 心跳当前时间（页面「当前时间」卡常显用，纯文本刷新无动效）
 * - 区间差值：起始/结束两输入同步求值（[TimestampConverter.diff] 纯函数，单行字段
 *   逐键开销可忽略，不走防抖）；非法输入走既有 Validation 路径
 * - 批量转换：多行输入由页面防抖流调 [convertBatch]（逐行走既有单行转换路径），
 *   输出格式与单行共享同一 [format] 预设；结果列可整体复制（行对齐口径见
 *   [TimestampConverter.batchOutputText]）
 * - [recent] 最近转换记录：转换成功（非 Empty/Invalid）时
 *   trim 净值去重插首、cap [RECENT_CAP] 条；会话内存不持久（排查日志反复对时场景
 *   只需当天回填，持久化收益存疑宁缺）
 */
class TimestampViewModel : ViewModel() {

    private val _timestamp = MutableStateFlow("")
    val timestamp: StateFlow<String> = _timestamp.asStateFlow()

    private val _format = MutableStateFlow(TimestampFormat.LOG)
    val format: StateFlow<TimestampFormat> = _format.asStateFlow()

    private val _output = MutableStateFlow<ConversionResult>(ConversionResult.Empty)
    val output: StateFlow<ConversionResult> = _output.asStateFlow()

    private val _nowMillis = MutableStateFlow(System.currentTimeMillis())
    val nowMillis: StateFlow<Long> = _nowMillis.asStateFlow()

    /** 最近转换记录：新值插首，最近在前（chips 行渲染序 = 列表序） */
    private val _recent = MutableStateFlow<List<String>>(emptyList())

    /** 最近转换记录（只读面；页面 chips 行渲染 + 点击回填） */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private val _diffStart = MutableStateFlow("")
    val diffStart: StateFlow<String> = _diffStart.asStateFlow()

    private val _diffEnd = MutableStateFlow("")
    val diffEnd: StateFlow<String> = _diffEnd.asStateFlow()

    private val _diffResult = MutableStateFlow<DiffResult>(DiffResult.Empty)
    val diffResult: StateFlow<DiffResult> = _diffResult.asStateFlow()

    private val _batchInput = MutableStateFlow("")
    val batchInput: StateFlow<String> = _batchInput.asStateFlow()

    private val _batchOutput = MutableStateFlow<List<BatchLine>>(emptyList())
    val batchOutput: StateFlow<List<BatchLine>> = _batchOutput.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = TimestampConfigStore.getTimestampFormat()
            _format.value = runCatching { TimestampFormat.valueOf(saved) }
                .getOrDefault(TimestampFormat.LOG)
        }
        // 当前时间心跳：500ms 粒度覆盖秒级显示（整秒跳变不闪烁），纯文本刷新零动效
        viewModelScope.launch {
            while (isActive) {
                _nowMillis.value = System.currentTimeMillis()
                delay(500)
            }
        }
    }

    /** 日志格式预编译器（2Hz 心跳热路径——DateTimeFormatter 不可变可复用；Locale.ROOT
     * 防本地化数字变形：日志格式时间戳须为 ASCII 数字，与日志检索/解析面口径一致） */
    private val logTimeFormatter = java.time.format.DateTimeFormatter
        .ofPattern(TimestampFormat.LOG.pattern, java.util.Locale.ROOT)
        .withZone(java.time.ZoneId.systemDefault())

    /** 当前时间的日志格式文本（「当前时间」卡显示值；格式化收进 VM，页面零内联逻辑） */
    fun nowLogText(nowMillis: Long = _nowMillis.value): String =
        logTimeFormatter.format(java.time.Instant.ofEpochMilli(nowMillis))

    fun onTimestampChange(value: String) {
        _timestamp.value = value
    }

    fun onFormatChange(format: TimestampFormat) {
        _format.value = format
        viewModelScope.launch {
            TimestampConfigStore.setTimestampFormat(format.name)
        }
        // 切换格式后已有输入立即按新格式重算（单行与批量同语义）
        _output.value = TimestampConverter.convert(_timestamp.value, format.pattern)
        if (_batchInput.value.isNotBlank()) {
            _batchOutput.value = TimestampConverter.convertBatch(_batchInput.value, format.pattern)
        }
    }

    /** 防抖后的转换入口（输入为空时输出 Empty，清空结果区） */
    fun convert(input: String) {
        _output.value = TimestampConverter.convert(input, _format.value.pattern)
        // 使用计数口径：转换出有效结果 = 一次真实使用
        //（Empty/Invalid 不计——只认成功转换）
        if (_output.value !is ConversionResult.Empty && _output.value !is ConversionResult.Invalid) {
            ToolUsageSignal.report(ToolUsageSignal.PageId.TIMESTAMP)
            // 同一判定分支记最近转换（与使用计数同口径——只认成功转换；
            // trim 净值去重插首防「 123 」与「123」双占位，cap 挤最旧）
            val trimmed = input.trim()
            _recent.update { current ->
                (listOf(trimmed) + current.filterNot { it == trimmed }).take(RECENT_CAP)
            }
        }
    }

    /** 差值起始输入：同步重算（另一端保持现值参与混算；空/非法走 [DiffResult] 既有语义） */
    fun onDiffStartChange(value: String) {
        _diffStart.value = value
        _diffResult.value = TimestampConverter.diff(value, _diffEnd.value)
    }

    /** 差值结束输入：同步重算（口径同 [onDiffStartChange]） */
    fun onDiffEndChange(value: String) {
        _diffEnd.value = value
        _diffResult.value = TimestampConverter.diff(_diffStart.value, value)
    }

    fun onBatchInputChange(value: String) {
        _batchInput.value = value
    }

    /** 防抖后的批量转换入口（输入全空白时清空输出区，不报错） */
    fun convertBatch(input: String) {
        _batchOutput.value = if (input.isBlank()) {
            emptyList()
        } else {
            TimestampConverter.convertBatch(input, _format.value.pattern)
        }
    }

    /** 批量结果列的可复制文本（行对齐口径见 [TimestampConverter.batchOutputText]） */
    fun batchOutputText(): String = TimestampConverter.batchOutputText(_batchOutput.value)

    companion object {
        /** 最近转换记录容量（cap 8；internal 供测试断言锚） */
        internal const val RECENT_CAP = 8

        @Composable
        fun getViewModel(): TimestampViewModel {
            return viewModel { TimestampViewModel() }
        }
    }
}
