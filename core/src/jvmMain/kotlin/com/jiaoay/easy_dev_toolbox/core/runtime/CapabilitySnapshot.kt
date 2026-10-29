// S2 · 能力四态模型（四态判断优于布尔——「可用/不可用」压扁了降级链，不引入 7×9 嵌套形状）。
// 为什么单渠道（只支持飞书）也值得四态：现状 isConfigured 把「未配置」与「配了调不通」压成一态，
//    而「端点不可用 ⇒ 回落本地规则/bigram」是真实存在的降级链（语义保留描述）——
//    Degraded 让临时降级可见，PermissionRequired 让「配了但缺凭据」可分。
package com.jiaoay.easy_dev_toolbox.core.runtime

/** App 级能力标识（CLI_AGENT 已随 CLI 委托链退役）；新增前先论证它是 App 级（而非页面作用域）能力源。 */
enum class CapabilityId { LLM, GATEWAY }

/**
 * 能力四态。
 *
 * 四态判断（verified / permission-required / degraded / unavailable），
 * 单渠道下单层 2×4 已覆盖本仓全部真实降级链；不引入按渠道嵌套的第二层。
 */
sealed interface CapabilityState {

    /** 探针通过（当前可用）。 */
    data class Verified(val detail: String = "") : CapabilityState

    /** 已配置但被拒绝（缺凭据 / 未授权）——与「未配置」必须可分。 */
    data class PermissionRequired(val reason: String) : CapabilityState

    /** 曾可用、现临时降级（如端点配了但调不通 ⇒ 回落本地规则）。 */
    data class Degraded(val reason: String, val sinceMillis: Long) : CapabilityState

    /** 从未配置或确定性不可用。 */
    data class Unavailable(val reason: String) : CapabilityState
}

/**
 * 能力注册表：**纯 sink**。
 *
 * 🔴 本接口刻意**不认识任何数据源**——:core 不得依赖 :lark-bridge(jvm11) 或 :feature-*（成环）。
 * 数据源提取一律发生在源所在模块，经 [AppLifecycleParticipant.capabilities] / [report] 上报；
 * :core 只存快照。
 */
interface CapabilityRegistry {

    /** 当前全量快照（缺省判定见 [of]）。 */
    val snapshot: kotlinx.coroutines.flow.StateFlow<Map<CapabilityId, CapabilityState>>

    /** 就地覆盖单能力（无锁热替换，对齐 PipelineSnapshot 先例）。 */
    fun report(id: CapabilityId, state: CapabilityState)

    /** 单能力判定：未上报 ⇒ [CapabilityState.Unavailable]（「未探测」），不返回 null。 */
    fun of(id: CapabilityId): CapabilityState
}

/** 进程内默认实现（:core 自持，宿主注入 participants 用）。 */
class InMemoryCapabilityRegistry : CapabilityRegistry {

    private val ref = java.util.concurrent.atomic.AtomicReference<Map<CapabilityId, CapabilityState>>(emptyMap())

    override val snapshot: kotlinx.coroutines.flow.StateFlow<Map<CapabilityId, CapabilityState>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyMap())

    override fun report(id: CapabilityId, state: CapabilityState) {
        while (true) {
            val cur = ref.get()
            val next = cur + (id to state)
            if (ref.compareAndSet(cur, next)) {
                (snapshot as kotlinx.coroutines.flow.MutableStateFlow).value = next
                return
            }
        }
    }

    override fun of(id: CapabilityId): CapabilityState =
        ref.get()[id] ?: CapabilityState.Unavailable("未探测")
}
