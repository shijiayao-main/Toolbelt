package com.jiaoay.easy_dev_toolbox.core.system

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * sys.* 工具单次执行审计记录（JSONL 单行）。字段语义与落点见 [SysAuditLog] 类 KDoc。
 */
@Serializable
data class SysAuditRecord(
    /** 时刻（ISO-8601 本地时间字符串） */
    val ts: String,
    /** 工具名（sys.probe / sys.read_config / startup.probe——startup.probe 是启动自检探查非 agent 工具） */
    val tool: String,
    /** 动作全文：argv 以空格连接 / 路径族读取动作（family:relative）——审计可复核的完整动作面 */
    val action: String,
    /** 裁决：[SysAuditLog.VERDICT_UNGATED] / 围栏拒绝 / 参数拒收 / 目标不存在 / 执行失败 */
    val verdict: String,
    /** 子进程退出码（sys.probe；数据非失败信号——非零常见于无匹配/单元不存在） */
    val exitCode: Int? = null,
    /** 触发者标识（装配链注入：桥接形态为 chatId+senderOpenId）；门控装配未落地前恒 null */
    val trigger: String? = null,
    /** 输出摘要：脱敏后回执的前 [SysAuditLog.SUMMARY_MAX_CHARS] 字符、换行压平——输出全文永不落审计 */
    val outputSummary: String? = null,
    /**
     * 回滚信息（修复动作专用）：执行前捕获的原值快照 + 回滚配方一行——审计可复核
     * 「改了什么、怎么改回去」；只读域/拒收行恒 null。
     */
    val rollback: String? = null,
)

/**
 * 只读诊断域审计落盘器：sys.* 工具每次执行（含被拒）追加一行 JSONL。
 *
 * ## 落点与文件形态
 * 落点 = [FileUtils.getAuditDir]（应用数据根下 `audit/`），按日切文件
 * `sys-audit-<yyyy-MM-dd>.jsonl`，纯追加、无滚动清理（按日分文件即天然滚动，
 * 清理策略留消费面）。审计树在应用数据根内 ⇒ 桥接出站排除表判据一整树拒发，
 * 且审计文件名进排除表名形判据（root 未知也不放行）——防擦痕迹式回读：
 * 把审计发出去群即可在群内确认哪些操作未被追踪。
 *
 * ## 记录面（四件 + 摘要）
 * 动作全文（argv / family:relative）、触发者、裁决、退出码、输出摘要。
 * 输出全文永不落审计（审计文件自身是二次泄密面）——摘要取**脱敏后**回执的
 * 前 [SUMMARY_MAX_CHARS] = 200 字符。
 *
 * ## 裁决语义
 * 桥接链上 sys.* 执行前先过信任用户域准入（名单外拒记 [VERDICT_SESSION_DENIED]）；
 * 放行后执行记 [SysAuditLog.VERDICT_SESSION_GATED]；拒绝态分记围栏拒绝/参数拒收/目标不存在/执行失败。
 * 无门控装配面（工具直造测试形态）仍记 [SysAuditLog.VERDICT_UNGATED]——历史记录按当时语义解读。
 *
 * ## 写失败语义（观察面 best-effort）
 * 目录不可写等 IO 失败经 JLog 告警后放行工具回执——诊断不因审计挂；
 * 审计是追责前提不是执行闸门（fail-closed 在白名单/围栏/确认门，不在观察面）。
 */
class SysAuditLog(private val dir: File? = FileUtils.getAuditDir()) {

    private val tag = "SysAuditLog"

    /** 追加一条审计记录；[dir] 为 null（[NOOP]）时静默丢弃（单测注入形态）。 */
    suspend fun append(record: SysAuditRecord) {
        val dir = dir ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                File(dir, fileNameFor(LocalDate.now())).appendText(
                    json.encodeToString(SysAuditRecord.serializer(), record) + "\n",
                )
            }.onFailure {
                JLog.w(tag) { "audit append failed: ${it.javaClass.simpleName}: ${it.message}" }
            }
        }
    }

    companion object {
        /** 裁决：工具面无门控装配，直接执行（历史语义——会话门控装配后由会话门控两值接替） */
        const val VERDICT_UNGATED = "未门控直执"

        /** 裁决：域准入放行后的执行（信任用户名单内的工具执行成功态） */
        const val VERDICT_SESSION_GATED = "会话门控放行"

        /** 裁决：域准入拒绝（发送者不在信任用户名单 / 门控面缺席 fail-closed） */
        const val VERDICT_SESSION_DENIED = "会话授权拒绝"

        /** 预留：当前无生产写入点（生产只写 [VERDICT_SESSION_GATED] 与 [VERDICT_SESSION_DENIED]） */
        const val VERDICT_SESSION_APPROVED = "会话授权批准"

        /** 裁决：修复动作确认卡拒绝（L1b 逐动作卡的用户拒绝/超时/说明卡发送失败——区别于会话授权拒绝） */
        const val VERDICT_ACTION_DENIED = "动作确认拒绝"

        /** 裁决：围栏（允许集/允许根/HITL 门）拒绝 */
        const val VERDICT_FENCE_DENIED = "围栏拒绝"

        /** 裁决：参数校验拒收（未成 argv / 路径族校验失败） */
        const val VERDICT_BAD_ARGS = "参数拒收"

        /** 裁决：目标不存在（不存在本身是诊断答案，仍入审计——记录读取尝试） */
        const val VERDICT_NOT_FOUND = "目标不存在"

        /** 裁决：外委清单已产出（sys.handoff 零执行面——清单交用户，是否执行由用户裁决） */
        const val VERDICT_HANDOFF = "外委已交用户"

        /**
         * 裁决：启动自检探查项放行执行（startup.probe 专用——桥接长连接建立后的一次性
         * 确定性自检，config 门控 + 宿主缝在位双闸后触发；非会话授权形态，无授权卡）。
         */
        const val VERDICT_STARTUP_PROBE = "启动自检放行"

        /** 裁决：执行失败（超时/超读取上界/启动失败） */
        const val VERDICT_FAILED = "执行失败"

        /** 输出摘要上界（字符）：超界截断（输出全文不落审计） */
        const val SUMMARY_MAX_CHARS = 200

        /** 单测/无审计形态注入件：记录静默丢弃（生产装配恒用真实落点件） */
        val NOOP: SysAuditLog = SysAuditLog(dir = null)

        private val json = Json { encodeDefaults = true }

        /** 审计时刻（ISO-8601 本地时间，无时区后缀——本机审计单机解读） */
        fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        /** 按日文件名（`sys-audit-<yyyy-MM-dd>.jsonl`——LocalDate.toString 即该格式） */
        internal fun fileNameFor(date: LocalDate): String = "sys-audit-${date}.jsonl"
    }
}
