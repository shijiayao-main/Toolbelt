// :lark-ws —— 事件分发器：Channel 背压 + 挂起回调（解决 SDK 缺陷②「固定池无背压无生命周期」）。
// 对照 SDK：Listener.onMessage → executor.execute(handleMessage)——池无界无取消。
// 本实现：reader 回调 → Channel(capacity) trySend；专职消费协程 per frame 挂起处理；
//          满时计数 + WARN（不静默不丢据）；close = scope.cancel（全回收，零泄漏）。
package com.jiaoay.lark.ws

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * DATA 帧分发器：把「待处理事件」从 reader 线程转到消费协程。
 * 🔴 回调是挂起函数（对比 SDK ChannelEventHandler.handle 同步 void）——处理链可背压在
 *    Channel 上，慢消费不阻塞 reader 线程（reader 只 trySend，O(1) 非阻塞——阻塞 reader 即
 *    帧读取停摆、连接假死）。
 * F 组纪律：顶层 class，无 object 可变态；计数用 AtomicInteger/AtomicLong。
 */
class WsDispatcher(
    dispatcher: CoroutineDispatcher,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val log: (level: WsLogLevel, message: String) -> Unit = { _, _ -> },
) {
    /** 消费 scope 由本类自持（SupervisorJob：单帧处理失败不掀翻整个分发器）。 */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pending = Channel<DispatchTask>(capacity)
    private val dropped = AtomicLong(0)
    private val processed = AtomicLong(0)
    private val consumerJob: Job

    init {
        consumerJob = scope.launch {
            for (task in pending) {
                runCatching { task.block() }
                    .onFailure { t -> log(WsLogLevel.WARN, "事件处理异常（已吞，回执 500）: ${t.javaClass.simpleName} ${t.message}") }
                processed.incrementAndGet()
            }
        }
    }

    /**
     * 提交一个待处理任务（reader 线程调用；非阻塞）。
     * @return true=入队成功；false=队列满（已计数——调用方回执 503/丢弃由其决策）
     */
    fun submit(frameId: String, block: suspend () -> Unit): Boolean =
        pending.trySend(DispatchTask(frameId, block)).isSuccess.also { ok ->
            if (!ok) {
                val total = dropped.incrementAndGet()
                log(WsLogLevel.WARN, "事件队列已满（capacity=$capacity），丢弃 frameId=$frameId 累计=$total")
            }
        }

    /** 关闭分发器（幂等；scope.cancel 全回收）。 */
    fun close() {
        pending.close()
        consumerJob.cancel()
        scope.cancel()
    }

    val droppedCount: Long get() = dropped.get()
    val processedCount: Long get() = processed.get()

    private class DispatchTask(val frameId: String, val block: suspend () -> Unit)

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

/** 日志级别（传输层自带轻量日志缝；不依赖宿主 JLog——本模块独立零依赖）。 */
enum class WsLogLevel { DEBUG, INFO, WARN, ERROR }
