package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import java.util.Collections

/**
 * 测试共享假件（全量捕获 RecordingLog 与 awaitUntil 的单源收敛件）。
 *
 * 此前 4 个测试文件各持一份逐字相同的全量捕获 RecordingLog（ConfirmBusTest/
 * DownloadPolicyTest/PreprocessPipelineTest/RejectDiagnosticsMaskingTest）、7 个文件
 * 各持一份逐字相同的 awaitUntil——收敛为单源。瘦版假件（只记单级、断言语义依赖
 * 空集合语义的，如 SystemHandlerStatusTest.RecordingBridgeLog）**有意不合并**。
 */
internal class RecordingLog : BridgeLog {
    val lines = Collections.synchronizedList(mutableListOf<String>())
    override fun debug(message: String) { lines.add("D:$message") }
    override fun info(message: String) { lines.add("I:$message") }
    override fun warn(message: String, error: Throwable?) { lines.add("W:$message") }
    override fun error(message: String, error: Throwable?) { lines.add("E:$message") }
}

/**
 * 轮询谓词直至超时（原 7 份 private 拷贝逐字相同单源化——deadline + 5ms sleep 循环）。
 * 与 [PipelineTestSupport.Harness.awaitUntil] 的成员版语义一致（默认超时 10s）。
 */
internal fun awaitUntil(timeoutMillis: Long = 10_000L, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(5L)
    }
    return condition()
}
