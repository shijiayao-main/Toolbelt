package com.jiaoay.easy_dev_toolbox.page.voice

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.io.Closeable
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 麦克风采集层（add-voice-live-recording-vad 任务 2.3——全仓首例 javax.sound.sampled）。
 *
 * 16kHz / mono / 16bit PCM 帧循环读 → float[] 窗口流（[onWindow] 采集线程内联
 * 回调——消费方 VadSegmenter.feed 零中转零拷贝）。
 *
 * ## 生命周期
 * - [start]：打开 TargetDataLine（系统混音器选择默认输入）+ IO 线程帧循环；
 * - [stop]：cancel 采集协程 + finally 内 line.stop/close（🔴 资源必收——混音器
 *   句柄泄漏会占死系统录音设备）；
 * - 麦克风缺失/被占：start 抛 LineUnavailableException——调用方降级文案。
 *
 * 不单测（TargetDataLine 系统依赖——真机验收面）。
 */
class MicCapture(
    private val scope: CoroutineScope,
    private val onWindow: (FloatArray) -> Unit,
    private val onError: (String) -> Unit,
) : Closeable {

    private var line: TargetDataLine? = null
    private var captureJob: Job? = null

    val isRunning: Boolean get() = captureJob?.isActive == true

    /** 开始采集（幂等——运行中重复 start no-op） */
    fun start() {
        if (isRunning) return
        val audioLine = try {
            val format = AudioFormat(
                SherpaVadEngine.SAMPLE_RATE.toFloat(),
                16, // 16bit PCM signed little-endian
                1, // mono
                true,
                false,
            )
            val info = DataLine.Info(TargetDataLine::class.java, format)
            AudioSystem.getLine(info) as TargetDataLine
        } catch (e: Exception) {
            onError("无法打开麦克风输入：${e.message ?: e::class.simpleName}")
            return
        }
        try {
            audioLine.open(audioLine.format)
            audioLine.start()
        } catch (e: Exception) {
            audioLine.close()
            onError("麦克风被占用或权限不足：${e.message ?: e::class.simpleName}")
            return
        }
        line = audioLine
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                val windowBytes = ByteArray(SherpaVadEngine.WINDOW_SIZE * 2) // 16bit → ×2 字节
                val window = FloatArray(SherpaVadEngine.WINDOW_SIZE)
                while (true) {
                    // 阻塞读满一窗（read 返回 -1 = line 关闭——退出循环）
                    var off = 0
                    while (off < windowBytes.size) {
                        val n = audioLine.read(windowBytes, off, windowBytes.size - off)
                        if (n < 0) return@launch
                        off += n
                    }
                    // 16bit LE → float [-1,1]（32768 归一）
                    for (i in window.indices) {
                        val lo = windowBytes[i * 2].toInt() and 0xFF
                        val hi = windowBytes[i * 2 + 1].toInt()
                        window[i] = ((hi shl 8) or lo) / 32768f
                    }
                    onWindow(window.copyOf())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("采集异常终止：${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** 停止采集（cancel 协程 + finally 收线——🔴 资源必收；混音器句柄泄漏会占死系统录音设备） */
    fun stop() {
        captureJob?.cancel()
        captureJob = null
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
    }

    override fun close() = stop()

    companion object {
        private const val TAG = "MicCapture"

        /** 采集面调试日志开关（release 恒关——避免每 32ms 一行） */
        internal var debugLog = false

        internal fun log(msg: () -> String) {
            if (debugLog) JLog.d(TAG, msg)
        }
    }
}
