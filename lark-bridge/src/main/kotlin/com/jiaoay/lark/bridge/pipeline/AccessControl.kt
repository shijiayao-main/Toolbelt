// ① Intake 第 3 步——发送者准入（前置于路由）。
// 🔴 恒放行：sender 级 allow/block 已退役（为多租户防护付出的复杂度，非目标用户收益为零）。
//    服务面收口靠飞书后台的机器人可用范围；高风险操作的授权由 TrustPolicy 按用户判定。
// 🔴 本文件属 intake 零锁清单（IntakeGuardTest 守护）：纯 CPU 判定 + CAS 计数，零锁、零 IO、零挂起——违反即挂死 intake 单消费者协程、全链路停摆。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.AccessConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * 准入判定结果。
 *
 * 三态枚举保留是为**计数与审计面契约**（[PipelineCounters.accessRejected] / Rejected 信号
 * 消费方零改动）；[AccessControl.decide] 当前恒产 [ALLOWED]。
 */
enum class AccessDecision { ALLOWED, NOT_IN_ALLOWLIST, BLOCKED }

/**
 * 发送者准入：**恒放行**。
 *
 * ## 为什么保留这个类
 * [EventIntake] 的五步判定链（StaleFilter → Deduper → AccessControl → PolicyGate → Router）
 * 与 [PipelineCounters] 的计数面都以本类为锚。整类删除会让计数与审计面失去语义位置，
 * 而保留一个恒放行的判定点是零成本的（纯 CPU、无 IO）。
 *
 * ## 为什么恒放行
 * sender 级 allow/block 服务的是「多人共用一台机器 / 防止他人误用」场景——本项目的目标用户
 * 是单人开发者把机器人当自己的工具，白名单空名单拒绝所有人只会把自己锁在门外（须先发
 * `/whoami` 取 open_id 回填）。服务面收口改由**飞书后台的机器人可用范围**承担（限群/限部门），
 * 那是平台能力，比桥接侧自己维护名单更可靠。
 *
 * ## 高风险操作仍分级
 * 放行 ≠ 无防护：[TrustPolicy] 按 [AccessConfig.trustedUsers] 判定谁可免确认，
 * 名单外用户遇到越界/破坏性操作直接拒绝。
 *
 * @param configProvider 读当前 `access` 域（HOT：配置热替换后下一条消息立即生效）。
 *   🔴 不得在实现里做 IO / 加锁 / 挂起（它跑在 intake 单协程里，IntakeGuardTest 零锁守护）。
 */
class AccessControl(
    private val configProvider: () -> AccessConfig,
) {

    private val allowed = AtomicLong(0)

    /** 累计放行次数（恒增长；保留供计数面消费）。 */
    val allowedCount: Long get() = allowed.get()

    /** 累计拒绝次数——恒 0（准入无拒绝路径），保留签名供计数面零改动。 */
    val allowlistRejections: Long get() = 0L

    /** 累计拒绝次数——恒 0（同上）。 */
    val blocklistRejections: Long get() = 0L

    /** 累计未知 mode 回退次数——恒 0（mode 字段已退役）。 */
    val unknownModeFallbackCount: Long get() = 0L

    /**
     * 判定一个发送者：恒 [AccessDecision.ALLOWED]。
     *
     * 读一次 [configProvider] 是为保持「每消息现读配置」的既有语义（配置热替换后
     * 下一条消息即生效）——本类当前不消费任何字段，但保留读取点让未来分级判定有落点。
     */
    fun decide(@Suppress("UNUSED_PARAMETER") senderOpenId: String): AccessDecision {
        configProvider()
        allowed.incrementAndGet()
        return AccessDecision.ALLOWED
    }

    /** 便捷布尔形态（「静默拒绝不 spawn」的调用点）；恒 true。 */
    fun isAllowed(@Suppress("UNUSED_PARAMETER") senderOpenId: String): Boolean {
        decide(senderOpenId)
        return true
    }

    /** mode 概念已退役；保留签名（PolicyGate/UI 调用点零改动），恒 false。 */
    fun isKnownMode(@Suppress("UNUSED_PARAMETER") mode: String): Boolean = false

    /**
     * **恒静默**：拒绝路径已不存在，恒返回 null。
     * 保留签名（[EventIntake] 调用点零改动）。
     */
    fun rejectionReply(@Suppress("UNUSED_PARAMETER") senderOpenId: String): String? = null
}
