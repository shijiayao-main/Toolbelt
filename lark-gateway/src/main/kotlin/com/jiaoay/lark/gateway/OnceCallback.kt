package com.jiaoay.lark.gateway

/**
 * 「恰一次」回调容器（卡片流摘除的公共构件）。
 *
 * 给 [CardStreamSink.onFinished] 的实现用：注册（[set]）与触发（[fire]）两路经同一把锁串行，
 * 保证「流终结」事件对**当前已注册的回调**恰触发一次：
 *  - 注册先于终结：set 正常存好，fire 时 invoke；
 *  - 终结先于注册（真实竞态：[LarkCardStreamSink] 的主循环 job 在构造期即启动，建卡秒败时
 *    可能早于消费方 `.also { onFinished(...) }` 注册就终结）：fire 消耗触发点时读到 null，
 *    随后 set 锁内发现已 fired，对晚到回调**立即补触发**（它从未被消费过，不违反恰一次）；
 *  - fire 重复调用（close 与 job completion 双路汇合）：第二次起锁内直接返回，幂等。
 *
 * 回调在锁外 invoke（不持锁执行用户代码）。[set] 传 null = 注销（纯赋值，永不触发）；
 * fire 前的重复 set 为后写覆盖（前一个回调被替换、不会被触发）。
 */
class OnceCallback {

    private val lock = Any()
    private var callback: (() -> Unit)? = null
    private var fired = false

    /** 注册（或覆盖）回调。若「流终结」已发生过且新回调非空，立即补触发一次。 */
    fun set(callback: (() -> Unit)?) {
        val late: (() -> Unit)? = synchronized(lock) {
            this.callback = callback
            if (fired && callback != null) callback else null
        }
        late?.invoke()
    }

    /** 触发「终结」：invoke 当前已注册的回调（至多一次）；重复调用幂等 no-op。 */
    fun fire() {
        val cb: (() -> Unit)? = synchronized(lock) {
            if (fired) null
            else {
                fired = true
                callback
            }
        }
        cb?.invoke()
    }
}
