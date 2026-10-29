package com.jiaoay.easy_dev_toolbox.core.system

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * sys-audit 按日聚合计数器（**只聚合不落原文**——审计拒外发纪律的唯一读形态）。
 *
 * ## 为什么聚合是独立读形态
 * 审计文件本体（`sys-audit-<yyyy-MM-dd>.jsonl`，见 [SysAuditLog]）承载动作全文/输出摘要/
 * 触发者等可复核明细，且被 SensitiveFilePolicy 拒发清单整树钉死（防擦痕迹式回读）——
 * 任何消费面若把明细读出去，等于绕过拒发判据把审计内容送出围栏。自观测域只需要
 * 「今日 probe N 次、拒绝 M 次」级聚合数：本对象逐行折叠 [SysAuditRecord] 为计数，
 * **action/outputSummary/trigger/rollback 等原文字段在计数后即弃**，聚合产物
 * [DailyAggregate] 只含计数面（结构上不可能携带原文——字段不存在）。
 *
 * ## 形态
 * - 只读当日单文件（按日切文件即天然的时间窗），纯追加读零写入；
 * - IO 失败 best-effort：返回 null（观察面不炸消费方——审计是追责前提不是执行闸门，
 *   [SysAuditLog] 写侧同款纪律）；文件不存在 = 今日无记录，返回全零聚合；
 * - 单行解析失败跳过不计数（写侧同进程同序列化器，坏行属残迹——不阻塞其余行聚合）；
 * - 「拒绝」= 被裁决挡下的执行尝试（会话授权拒绝/动作确认拒绝/围栏拒绝/参数拒收），
 *   执行失败/目标不属拒绝语义（那是诊断答案），不混入。
 */
object SysAuditAggregator {

    /** 按日聚合计数（只含计数面——原文字段结构性不存在，见类 KDoc）。 */
    data class DailyAggregate(
        /** 聚合窗口日（本地时区） */
        val date: LocalDate,
        /** 当日审计记录总数（含授权动作行） */
        val total: Int,
        /** 按工具名聚合的执行/授权记录计数（tool 原值——工具名非敏感面） */
        val byTool: Map<String, Int>,
        /** 被裁决挡下的尝试数（拒绝裁决集见 [DENIED_VERDICTS]） */
        val denied: Int,
    ) {
        override fun toString(): String =
            "DailyAggregate(date=$date, total=$total, denied=$denied, " +
                "byTool=${byTool.entries.sortedByDescending { it.value }.joinToString("/") { "${it.key}=${it.value}" }})"
    }

    /** 「拒绝」裁决集：会话授权拒绝 / 动作确认拒绝 / 围栏拒绝 / 参数拒收（裁决语义见 [SysAuditLog]） */
    val DENIED_VERDICTS: Set<String> = setOf(
        SysAuditLog.VERDICT_SESSION_DENIED,
        SysAuditLog.VERDICT_ACTION_DENIED,
        SysAuditLog.VERDICT_FENCE_DENIED,
        SysAuditLog.VERDICT_BAD_ARGS,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 聚合 [date]（缺省今天）的审计文件。
     *
     * @return 全零聚合 = 审计文件不存在（今日无记录——目录缺席属同义：尚无任何写入）；
     *   null = 文件在而读取失败（best-effort 观察面，消费方如实报「暂不可得」，不编造零）。
     */
    suspend fun aggregate(date: LocalDate = LocalDate.now(), dir: File = FileUtils.getAuditDir()): DailyAggregate? {
        val file = File(dir, SysAuditLog.fileNameFor(date))
        if (!file.isFile) return DailyAggregate(date, total = 0, byTool = emptyMap(), denied = 0)
        return withContext(Dispatchers.IO) {
            runCatching { aggregateFile(file, date) }
                .onFailure {
                    JLog.w("SysAuditAggregator") {
                        "audit aggregate failed: ${it.javaClass.simpleName}: ${it.message}"
                    }
                }
                .getOrNull()
        }
    }

    /** 单文件折叠（公开给单测同步直测；生产走 [aggregate] 的 IO 派发与容错包装） */
    fun aggregateFile(file: File, date: LocalDate): DailyAggregate {
        var total = 0
        var denied = 0
        val byTool = HashMap<String, Int>()
        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val record = runCatching { json.decodeFromString(SysAuditRecord.serializer(), line) }
                    .getOrNull() ?: continue
                total++
                byTool.merge(record.tool, 1, Int::plus)
                if (record.verdict in DENIED_VERDICTS) denied++
            }
        }
        return DailyAggregate(date = date, total = total, byTool = byTool, denied = denied)
    }
}
