// ③ Execute 的超时策略与归因：总超时三档取值 + 空闲看门狗 + 引擎缓冲（[Admission] 消费）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.RouteKind

/**
 * 超时策略（③ Execute 的两个旋钮 + handler 覆盖）。
 *
 * ## 三档取值
 * | 情形 | 总超时 |
 * |---|---|
 * | handler 声明 `timeoutSeconds != null` | **该值**（🔒 `timeoutSeconds=1` **覆盖**全局 60，测试锚定） |
 * | agent 类（[RouteKind.AGENT] / [RouteKind.FILE]，后者本质是「文件消息→agent」） | `runtime.agentTimeoutMinutes * 60_000`（默认 10min） |
 * | 其余（system / reply / command） | `runtime.taskTimeoutSeconds * 1000`（默认 60s） |
 *
 * 空闲看门狗取 `runtime.agentIdleTimeoutSeconds`（默认 120s）；🔴 **与总超时严格可区分**：
 * 触发时 [TimeoutCause] 置 [TimeoutCause.IDLE]，产出 `HandlerOutcome.idleTimeout`（summary 含「无输出」），
 * 而总超时产出 `HandlerOutcome.totalTimeout`（🔒 summary = `任务超时（>Ns）`）。
 *
 * @param configProvider 读当前 `runtime` 域（全 HOT ⇒ 每条任务都读最新值）。
 */
class TimeoutPolicy(private val configProvider: () -> BridgeConfig) {

    /**
     * 有效总超时（毫秒），下限 [MIN_TOTAL_MILLIS]（防用户把 `taskTimeoutSeconds` 调到 0 造成永久立即超时）。
     */
    fun totalMillis(handlerTimeoutSeconds: Long?, kind: RouteKind): Long {
        val runtime = configProvider().runtime
        val seconds = handlerTimeoutSeconds
            ?: if (isAgentLike(kind)) runtime.agentTimeoutMinutes.toLong() * 60L else runtime.taskTimeoutSeconds.toLong()
        return (seconds * 1000L).coerceAtLeast(MIN_TOTAL_MILLIS)
    }

    /** 有效空闲看门狗（毫秒）；`<= 0` ⇒ **关闭**看门狗（用户显式把 `agentIdleTimeoutSeconds` 调成 0）。历史行号锚 `Admission.kt:53`（IdleWatchdogTest/docs）已漂移——引用方以符号搜索定位。 */
    fun idleMillis(): Long = configProvider().runtime.agentIdleTimeoutSeconds.toLong() * 1000L

    /**
     * 引擎侧对 agent 自报超时的**缓冲**（🔒 实测定稿值）。
     * `AgentHandler` 把它加进喂给 agent 后端的 `--timeout` 类参数，确保**引擎的 kill 永远晚于
     * agent 自己的超时退出** ⇒ 用户看到的是 agent 的优雅错误信息，而不是被 SIGKILL 截断的半句话。
     *
     * 仅 headless `-p` 形态消费：app-server 长驻形态无「引擎 kill 竞速」面（超时收场 =
     * 回合判失败 + session/stop 中止在途回合，长驻进程不受牵连），不消费本档——
     * 两形态超时语义不同源，不得并轨。
     */
    fun engineBufferMillis(): Long = ENGINE_TIMEOUT_BUFFER_SECONDS * 1000L

    companion object {
        /** 🔒 实测定稿值，不可改。 */
        const val ENGINE_TIMEOUT_BUFFER_SECONDS: Long = 30L

        /** 总超时下限（毫秒）。 */
        const val MIN_TOTAL_MILLIS: Long = 1_000L

        /** 空闲看门狗的轮询片长（毫秒）：足够细以贴近阈值，又不至于空转吃 CPU。 */
        const val IDLE_CHECK_SLICE_MILLIS: Long = 250L

        /** 「agent 类」判定：文件消息本质是「下载→抽取→交给 agent」，与 AGENT 同档超时。 */
        fun isAgentLike(kind: RouteKind): Boolean = kind == RouteKind.AGENT || kind == RouteKind.FILE
    }
}

/** 超时归因（总超时 vs 空闲看门狗，两者必须可区分）。 */
enum class TimeoutCause { TOTAL, IDLE }
