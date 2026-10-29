package com.jiaoay.toolbelt.device

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

import com.jiaoay.toolbelt.adb.AdbEndpoint
/**
 * 测试用执行通道：恒返回成功 + 空输出。
 *
 * 给「测试注入专用 gateway、用不到底层通道」的构造点做占位——内核同时需要通道与
 * 专用 gateway 两个参数，只关心后者时不必再编一份 fake。
 */
internal val NoopRunner: AdbProcessRunner = AdbProcessRunner { Result.success(ShellResult(0, "", "")) }

/** 恒定端点读取缝（多数测试不关心端点差异） */
internal fun fixedEndpoint(path: String = ""): () -> com.jiaoay.toolbelt.adb.AdbEndpoint =
    { com.jiaoay.toolbelt.adb.AdbEndpoint(path) }

/**
 * 在虚拟时钟上推进直到 [predicate] 成立（最多 100 拍，每拍推进一个轮询间隔）。
 *
 * 用 fake 执行通道后检测是纯协程调度、没有真子进程，阻塞式等待等不到结果——
 * 必须显式推进调度器。上限拍数防断言写错时测试挂死到超时。
 */
internal fun kotlinx.coroutines.test.TestScope.advanceUntil(predicate: () -> Boolean) {
    repeat(100) {
        if (predicate()) return
        // 用 testScheduler（而非 TestScope.advanceTimeBy 的默认 scheduler）：被测对象
        // 的 scope 用的是同一个 scheduler 实例，两边时钟必须一致
        testScheduler.advanceTimeBy(DeviceMonitor.DEVICE_POLL_INTERVAL_MS)
        testScheduler.runCurrent()
    }
    throw AssertionError("推进 100 拍后条件仍未成立（当前设备态可能未按预期落地）")
}
