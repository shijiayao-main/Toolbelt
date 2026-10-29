// ① Intake 第 1 步——陈旧消息过滤。
// 🔴 本文件属 intake 零锁清单（IntakeGuardTest 守护）：纯 CPU 判定 + CAS 计数，零锁、零 IO、零挂起——违反即挂死 intake 单消费者协程、全链路停摆。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.GatewaySdkSettings
import com.jiaoay.lark.gateway.InboundMessage
import java.util.concurrent.atomic.AtomicLong

/**
 * 陈旧消息过滤器（方案：`createTime` 早于窗口 ⇒ drop + 计数）。
 *
 * ## 窗口值来源：不硬编码
 * 默认值取 :lark-gateway **公开**的 [GatewaySdkSettings.DEFAULT].staleMessageWindowMs（= 1_800_000L，30min，
 * 与 SDK `SafetyConfig.staleMessageWindowMs` 同值）。
 * ⚠️ 网关的 `Tuning.staleMessageWindowMs` 是 `internal`，:lark-bridge 读不到（实测可见性边界）；
 * `GatewaySdkSettings` 是 public data class 且带同名同值字段 ⇒ 用它，语义等价且不复制数值。
 * 宿主亦可经构造参数覆写（装配点可把它接到配置域，若将来新增字段）。
 *
 * ## 🔴 NF-A7 的三条边界（必须全部满足，否则误杀真实消息）
 * | 输入 | 处置 | 理由 |
 * |---|---|---|
 * | `createTimeMillis == 0L`（SDK 未给时间戳） | **放行** | 0 不是「1970 年的旧消息」，是「无时间戳」；按陈旧丢弃会静默吃掉全部此类消息 |
 * | `createTimeMillis` 为负 | **放行** | 同上，非法值不足以判定陈旧 |
 * | 未来时间戳（`createTime > now`，飞书服务端时钟快于本机） | **放行** | age 为负 ⇒ 不陈旧 |
 * | 本机时钟回拨（`now` 突然变小） | **放行** | age 变负 ⇒ 不陈旧；🔴 绝不因回拨把新消息当旧消息丢 |
 * | 恰好等于窗口边界（`age == window`） | **放行** | 「早于窗口」是严格小于语义，边界含等号会误杀 |
 *
 * @param clock 可注入（测试用虚拟时间；默认 `System.currentTimeMillis()`）。
 */
class StaleFilter(
    private val windowMillis: Long = GatewaySdkSettings.DEFAULT.staleMessageWindowMs,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val droppedTotal = AtomicLong(0)
    private val passedTotal = AtomicLong(0)

    /** 生效窗口（毫秒）；`StaleFilterTest` 断言「阈值可配置有默认」。 */
    val window: Long get() = windowMillis

    /** 累计因陈旧被丢弃的条数（丢弃必须可观测）。 */
    val dropped: Long get() = droppedTotal.get()

    /** 累计放行条数。 */
    val passed: Long get() = passedTotal.get()

    /**
     * 判定并计数。
     * @return true ⇒ 放行（继续走去重）；false ⇒ 陈旧，调用方丢弃。
     * 纯 CPU、不挂起、零锁 ⇒ 可安全地在 intake 单协程里逐条调用。
     */
    fun test(message: InboundMessage): Boolean {
        val fresh = isFresh(message.createTimeMillis)
        if (fresh) passedTotal.incrementAndGet() else droppedTotal.incrementAndGet()
        return fresh
    }

    /** 只做判定不动计数（`StaleFilterTest` 的边界三态用它，避免计数干扰断言）。 */
    fun isFresh(createTimeMillis: Long): Boolean {
        if (createTimeMillis <= 0L) return true          // 无时间戳 / 非法值 ⇒ 不判陈旧
        val age = clock() - createTimeMillis
        if (age <= 0L) return true                       // 未来时间戳 / 时钟回拨 ⇒ 不判陈旧
        return age <= windowMillis                       // 边界含等号 ⇒ 恰好 window 仍放行
    }

    /** 一条消息的陈旧度（毫秒）；负值表示未来时间戳。供日志与排障。 */
    fun ageOf(message: InboundMessage): Long = clock() - message.createTimeMillis
}
