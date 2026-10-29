package com.jiaoay.testfixtures

/**
 * deadline 有界轮询等待：跨模块共享收敛件（替代 per-file 私有等待与固定时长 sleep 宽限）。
 *
 * 契约：timeoutMillis 内每 intervalMillis 轮询一次 condition，成立即返回 true；
 * 到期做最后一次求值并返回其结果（false 时由调用方断言消费——报错上下文的责任在调用侧，
 * 这里只回答「条件是否在期限内成立」）。两参数须为正数，违者 IllegalArgumentException。
 *
 * 为什么轮询而非固定 sleep：固定宽限值在慢机/负载下系统性不足（假红），加长宽限只会
 * 把时序债推迟到未来；deadline 有界 + 高频轮询把「等到条件成立」与「等固定时长」解耦，
 * 快机器零空转、慢机器给足收敛窗口。
 *
 * 适用边界：真实 IO/子进程/真定时器等待不得改造成协程虚拟时间——虚拟时钟只跳过
 * TestDispatcher 上的 delay，真实等待不受益；本形态是这类等待的确定性下界。
 */
fun awaitUntil(
    timeoutMillis: Long = 10_000L,
    intervalMillis: Long = 2L,
    condition: () -> Boolean,
): Boolean {
    require(timeoutMillis > 0) { "timeoutMillis 必须为正：$timeoutMillis" }
    require(intervalMillis > 0) { "intervalMillis 必须为正：$intervalMillis" }
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(intervalMillis)
    }
    return condition()
}
