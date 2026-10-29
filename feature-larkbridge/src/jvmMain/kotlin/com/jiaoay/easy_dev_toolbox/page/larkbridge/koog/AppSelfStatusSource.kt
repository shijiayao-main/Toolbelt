package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.tools.SelfStatusTools
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditAggregator
import com.jiaoay.easy_dev_toolbox.page.larkbridge.status.label
import com.jiaoay.lark.bridge.config.ConfigValidator
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * self.status 状态源缝的 App 形态装配件：桥接内部态适配——连接态/计数读门面 1Hz 快照
 * （[com.jiaoay.easy_dev_toolbox.page.larkbridge.LarkBridgeManager.status]，与状态页
 * BridgeStatusPanel 同一数据面），配置校验走 [ConfigValidator.validate] 纯函数（CLI check
 * 同源），sys-audit 聚合走 [SysAuditAggregator]（只聚合不落原文）。仅 App 进程内使用
 * （桥接 CLI 形态无自观测域）；单测注入 fake 缝，不触本件。
 *
 * ## 重连计数与最近异常时刻的派生（仅有的两个非直接读数）
 * [BridgeStatusSnapshot] 无「重连计数」与「异常时刻」自然字段（快照域明确不伪造）——本
 * 适配器在宿主侧派生：宿主状态采集回路（装配点挂载的 1Hz collect）逐帧喂 [observe]，
 * ①重连计数 = 连接态进入 Reconnecting 的次数（重连态以秒级持续，1Hz 采样不漏计；
 * 进程生命周期语义，重启归零）；②最近异常时刻 = 异常文本变化时打点（异常发生早于
 * 采集挂载时，该值为首次观察到它的时刻——如实近似，非精确发生时刻）。
 *
 * ## 线程模型
 * observe（采集协程）与 snapshot（agent run 协程）并发——全部状态走原子量，O(1) CAS
 * 不阻塞采集回路（采集回路还承载活动流/报告就绪态发布，挂起会拖累两者）。
 */
internal object AppSelfStatusSource : SelfStatusTools.SelfStatusSource {

    private val reconnectCount = AtomicLong(0)

    /** 上一帧连接态类名（重连计数的进入判据；null = 采集刚挂载，首帧不计） */
    private val lastConnectionName = AtomicReference<String?>(null)

    /** 上一帧异常文本（变化检测——同文本持续不重复打点） */
    private val lastErrorSeen = AtomicReference<String?>(null)

    private val lastErrorAtMillis = AtomicReference<Long?>(null)

    /**
     * 宿主状态采集回路逐帧喂入（LarkBridgeManager 装配点挂载的 collect；O(1) CAS 恒不挂起）。
     * 门面未接线时采集回路本就不存在，本方法不会被调——无装配判据负担。
     */
    fun observe(snapshot: BridgeStatusSnapshot) {
        val name = snapshot.connection::class.java.simpleName
        val prev = lastConnectionName.getAndSet(name)
        if (prev != null && name != prev && name == "Reconnecting") {
            reconnectCount.incrementAndGet()
        }
        val err = snapshot.lastError
        if (err != null && err != lastErrorSeen.getAndSet(err)) {
            lastErrorAtMillis.set(System.currentTimeMillis())
        }
    }

    override suspend fun snapshot(): SelfStatusTools.Snapshot? {
        val bridge = com.jiaoay.easy_dev_toolbox.page.larkbridge.LarkBridgeManager.bridge ?: return null
        val status = bridge.status.value
        val c = status.counters
        return SelfStatusTools.Snapshot(
            running = status.running,
            connectionState = status.connection.label(),
            connectedSinceMillis = status.connectedSinceMillis,
            lastSignalAtMillis = status.lastSignalAtMillis,
            sampledAtMillis = status.sampledAtMillis,
            reconnectCount = reconnectCount.get(),
            lastError = status.lastError,
            lastErrorAtMillis = lastErrorAtMillis.get(),
            counters = SelfStatusTools.CounterRows(
                intakeReceived = c.intakeReceived,
                submitted = c.submitted,
                completed = c.completed,
                failed = c.failed,
                accessRejected = c.accessRejected,
                unmatched = c.unmatched,
                policyRejected = c.policyRejected,
                duplicatesDropped = c.duplicatesDropped,
                queueTimeouts = c.queueTimeouts,
                droppedTotal = c.droppedTotal,
            ),
            liveProcesses = status.liveProcesses,
            logSize = status.logSize,
            configIssues = ConfigValidator.validate(bridge.config).map {
                SelfStatusTools.ConfigIssueLine(fieldId = it.fieldId, message = it.message)
            },
            audit = SysAuditAggregator.aggregate(),
        )
    }
}
