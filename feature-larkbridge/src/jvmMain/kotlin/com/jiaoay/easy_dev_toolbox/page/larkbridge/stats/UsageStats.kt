package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.bridge.observe.AuditEntryJson
import com.jiaoay.lark.bridge.observe.SenderStatsEntry

/**
 * 用量统计**纯聚合逻辑**。
 *
 * 本文件全是纯函数与不可变数据类，**零 Compose、零 IO、零 .dp**——读取与绘制分别在
 * [UsageStatsPanel]（IO 走 `JsonlTailReader`）与图表 Composable（尺寸走 `Dimens.chart*` token）里。
 * 纯函数化是为了可被 `UsageStatsByDayTest` / `UsageStatsKindTest` 以内存输入直接断言。
 *
 * ## 契约要点
 * - **按天分桶**用 UTC epoch-day（`millis.floorDiv(DAY_MILLIS)`）而非 `Calendar` 本地时区——
 *   跨时区确定、可被测试以固定 `nowMillis` 精确锁定；
 * - 状态字面值 `SUCCESS`/`FAILURE`/`SKIPPED` = `AuditEntryJson.status`（= `TaskStatus.name`，大写）；
 * - 类型字面值 `COMMAND`/`SKILL`/`AGENT` = `AuditEntryJson.kind`（= `TaskKind.name`），
 *   `null` = 审计行无此键 → 归「未知」（向后兼容）。
 */
object UsageStats {

    /** 近 N 天窗口默认值（7 桶，末位今日、首位今日-6）。 */
    const val DEFAULT_DAYS: Int = 7

    /** 一天的毫秒数（UTC epoch-day 分桶基数）。 */
    const val DAY_MILLIS: Long = 86_400_000L

    // ── AuditEntryJson.status 字面值（🔒 大写，= TaskStatus.name） ──
    const val STATUS_SUCCESS: String = "SUCCESS"
    const val STATUS_FAILURE: String = "FAILURE"
    const val STATUS_SKIPPED: String = "SKIPPED"

    // ── AuditEntryJson.kind 字面值（🔒 = TaskKind.name）→ 友好标签 ──
    const val KIND_COMMAND: String = "COMMAND"
    const val KIND_SKILL: String = "SKILL"
    const val KIND_AGENT: String = "AGENT"

    // ── SenderStatsEntry.type 字面值（🔒 大写；WHITELIST/BLACKLIST 是刻意保留的旧拼写，见 SenderStatsRecords） ──
    const val SENDER_TYPE_MESSAGE: String = "MESSAGE"

    /** 柱状图数据量上限（方案 :325：柱 ≤31 根，聚合层截断）。 */
    const val MAX_BARS: Int = 31

    /** 环形/类型分组上限（方案 :325：环 ≤8 扇）。 */
    const val MAX_KINDS: Int = 8

    /** 发送者横向条上限（方案 :325：横向条 ≤10）。 */
    const val MAX_SENDERS: Int = 10

    /** 二级页对话记录上限（用户详情只显最近 50 条，防大文件全量渲染）。 */
    const val SENDER_DETAIL_MAX_AUDIT: Int = 50

    /** epoch millis → UTC epoch-day 序号（负数毫秒也正确向下取整）。 */
    fun epochDay(millis: Long): Long = millis.floorDiv(DAY_MILLIS)

    /**
     * `AuditEntryJson.kind` → 友好标签。
     * 🔒 `null`（审计行无 kind 键）与未知字面值一律归「未知」（向后兼容）。
     */
    fun kindLabel(kind: String?): String = when (kind) {
        KIND_COMMAND -> "指令"
        KIND_SKILL -> "快捷指令"
        KIND_AGENT -> "Agent"
        else -> "未知"
    }

    /** `AuditEntryJson.backend` → 友好标签；`null`（旧行）归「未知」。 */
    fun backendLabel(backend: String?): String = when (backend) {
        "claude" -> "Claude"
        "qoder" -> "Qoder"
        "copilot" -> "Copilot"
        "zcode" -> "ZCode"
        "custom" -> "自定义"
        else -> "未知"
    }

    /**
     * 按天分桶。
     *
     * 产出恰好 [days] 个桶：末位 = 今日（`epochDay(nowMillis)`）、首位 = 今日-([days]-1)；
     * 窗口外的条目被丢弃；中间无事件的天保持全零；空输入 → [days] 个全零桶。
     */
    fun aggregateByDay(entries: List<AuditEntryJson>, nowMillis: Long, days: Int = DEFAULT_DAYS): List<DayBucket> {
        val span = days.coerceAtLeast(1)
        val today = epochDay(nowMillis)
        val firstDay = today - (span - 1)
        val buckets = ArrayList<DayBucket>(span)
        // forEachIndexed：i=0 → 首位（今日-span+1），i=span-1 → 末位（今日）
        (0 until span).forEachIndexed { i, _ ->
            val day = firstDay + i
            val dayEntries = entries.filter { epochDay(it.ts) == day }
            buckets.add(
                DayBucket(
                    dayIndex = day,
                    dayStartMillis = day * DAY_MILLIS,
                    success = countStatus(dayEntries, STATUS_SUCCESS),
                    failure = countStatus(dayEntries, STATUS_FAILURE),
                    skipped = countStatus(dayEntries, STATUS_SKIPPED),
                ),
            )
        }
        return buckets
    }

    /**
     * 按路由类型分组。🔒 `total` **降序**；空输入 → 空列表；
     * `kind == null` 归「未知」桶。超出 [MAX_KINDS] 的尾部类型合并进「其他」。
     */
    fun aggregateByKind(entries: List<AuditEntryJson>): List<KindUsage> {
        if (entries.isEmpty()) return emptyList()
        val grouped = entries.groupBy { it.kind }
        val list = grouped.map { (kind, group) ->
            KindUsage(
                kind = kind,
                label = kindLabel(kind),
                total = group.size,
                success = countStatus(group, STATUS_SUCCESS),
                failure = countStatus(group, STATUS_FAILURE),
                skipped = countStatus(group, STATUS_SKIPPED),
            )
        }.sortedByDescending { it.total }
        return if (list.size <= MAX_KINDS) list else collapseTail(list, MAX_KINDS)
    }

    /** 按 agent 后端分组（O8）。🔒 `total` 降序；空输入 → 空列表。 */
    fun aggregateByBackend(entries: List<AuditEntryJson>): List<BackendUsage> {
        if (entries.isEmpty()) return emptyList()
        return entries.groupBy { it.backend }.map { (backend, group) ->
            BackendUsage(
                backend = backend,
                label = backendLabel(backend),
                total = group.size,
                success = countStatus(group, STATUS_SUCCESS),
                failure = countStatus(group, STATUS_FAILURE),
                skipped = countStatus(group, STATUS_SKIPPED),
            )
        }.sortedByDescending { it.total }
    }

    /** 任务总览（O5：总数 / 成功 / 失败 / 跳过 / 成功率 / 平均耗时）。 */
    fun summarize(entries: List<AuditEntryJson>): UsageSummary {
        val total = entries.size
        val success = countStatus(entries, STATUS_SUCCESS)
        val failure = countStatus(entries, STATUS_FAILURE)
        val skipped = countStatus(entries, STATUS_SKIPPED)
        val avgElapsed = if (total == 0) 0L else entries.sumOf { it.elapsedMillis } / total
        val rate = if (total == 0) 0.0 else success.toDouble() / total.toDouble()
        return UsageSummary(total, success, failure, skipped, avgElapsed, rate)
    }

    /** 今日子集总览（O9：今日 / 今日成功 / 今日失败 / 今日跳过）。 */
    fun summarizeToday(entries: List<AuditEntryJson>, nowMillis: Long): UsageSummary =
        summarize(entries.filter { epochDay(it.ts) == epochDay(nowMillis) })

    /**
     * 发送者统计聚合（O10..O12）：按 `senderOpenId` 分组，统计对话量（`MESSAGE`）与被拒量（其余 type），
     * 用户名/头像取该发送者**最后一条非空** `userName`/`avatarUrl`（旧行可能空，后续补齐）。
     * 🔒 对话量降序；[limit] 参数化——默认 [MAX_SENDERS]=10，
     * 用户列表态传 `Int.MAX_VALUE` 去截断（二级页要看**全部**用户）。
     */
    fun aggregateSenders(entries: List<SenderStatsEntry>, limit: Int = MAX_SENDERS): List<SenderUsage> {
        if (entries.isEmpty()) return emptyList()
        return entries.groupBy { it.senderOpenId }.map { (openId, group) ->
            val messages = group.count { it.type == SENDER_TYPE_MESSAGE }
            val rejected = group.size - messages
            val name = group.lastOrNull { it.userName.isNotBlank() }?.userName.orEmpty()
            val avatar = group.lastOrNull { it.avatarUrl.isNotBlank() }?.avatarUrl.orEmpty()
            SenderUsage(
                senderOpenId = openId,
                userName = name,
                avatarUrl = avatar,
                messageCount = messages,
                rejectedCount = rejected,
            )
        }.sortedByDescending { it.messageCount }.take(limit.coerceAtLeast(0))
    }

    /**
     * 单个用户的详情聚合（二级页「对话记录/用户信息」数据源）。
     * O(n) 单遍聚合（audit + stats 双流按 openId 过滤）；[maxAudit] 截断对话记录尾部（UI 只显最近 50 条）。
     *
     * @return 该 openId 无任何记录时 null（调用方显空态）
     */
    fun buildSenderDetail(
        openId: String,
        stats: List<SenderStatsEntry>,
        audit: List<AuditEntryJson>,
        maxAudit: Int = SENDER_DETAIL_MAX_AUDIT,
    ): SenderDetail? {
        val statGroup = stats.filter { it.senderOpenId == openId }
        val auditGroup = audit.filter { it.senderOpenId == openId }
        if (statGroup.isEmpty() && auditGroup.isEmpty()) return null
        val messages = statGroup.count { it.type == SENDER_TYPE_MESSAGE }
        return SenderDetail(
            senderOpenId = openId,
            userName = statGroup.lastOrNull { it.userName.isNotBlank() }?.userName.orEmpty(),
            avatarUrl = statGroup.lastOrNull { it.avatarUrl.isNotBlank() }?.avatarUrl.orEmpty(),
            messageCount = messages,
            rejectedCount = statGroup.size - messages,
            firstActiveMillis = (statGroup.firstOrNull()?.ts ?: auditGroup.firstOrNull()?.ts),
            lastActiveMillis = (statGroup.lastOrNull()?.ts ?: auditGroup.lastOrNull()?.ts),
            auditTruncated = auditGroup.size > maxAudit,
            records = auditGroup.takeLast(maxAudit).map { entry ->
                SenderAuditRecord(
                    ts = entry.ts,
                    kindLabel = kindLabel(entry.kind),
                    prompt = entry.prompt,
                    status = entry.status,
                    elapsedMillis = entry.elapsedMillis,
                    backendLabel = backendLabel(entry.backend),
                )
            },
        )
    }

    /**
     * 耗时人类可读（自 UsageStatsPanel 私有版上提共享——
     * 明细行 / O5「平均耗时」/ 发送者对话记录同配方）：`<1s → "Nms"`、`<1min → "12.3s"`、
     * 否则 `"1m2s"`（秒不补零）。
     */
    fun formatElapsed(millis: Long): String = when {
        millis < 1000L -> "${millis}ms"
        millis < 60_000L -> "${millis / 1000}.${(millis % 1000) / 100}s"
        else -> "${millis / 60_000}m${(millis % 60_000) / 1000}s"
    }

    /**
     * 信任名单快捷动作：详情页两按钮的纯函数实现（distinct 去重）。
     * 准入已恒放行，此处只维护「谁可免确认」。
     *
     * 两侧一律 trim 归一：判定面（[com.jiaoay.lark.bridge.confirm.TrustPolicy.isTrusted]
     * 与 `SenderDetailPanel` 的「已信任」胶囊）都按去空白比较，此处若精确比较，
     * 配置里写成 `" ou_a "` 时点「移出信任名单」会毫无效果（胶囊仍显示已信任）。
     */
    fun applySenderAccess(access: AccessConfig, openId: String, action: SenderAccessAction): AccessConfig {
        val target = openId.trim()
        val current = access.trustedUsers.map { it.trim() }.filter { it.isNotEmpty() }
        return when (action) {
            SenderAccessAction.ADD_TO_TRUSTED -> access.copy(trustedUsers = (current + target).distinct())
            SenderAccessAction.REMOVE_FROM_TRUSTED -> access.copy(trustedUsers = current.filter { it != target })
        }
    }

    private fun countStatus(entries: List<AuditEntryJson>, status: String): Int = entries.count { it.status == status }

    /** 把超出 [keep] 的尾部分组合并进一条「其他」（保持降序、总量守恒）。 */
    private fun collapseTail(list: List<KindUsage>, keep: Int): List<KindUsage> {
        val head = list.take(keep - 1)
        val tail = list.drop(keep - 1)
        val merged = KindUsage(
            kind = null,
            label = "其他",
            total = tail.sumOf { it.total },
            success = tail.sumOf { it.success },
            failure = tail.sumOf { it.failure },
            skipped = tail.sumOf { it.skipped },
        )
        return head + merged
    }
}

/** 单日桶（[dayIndex] = UTC epoch-day 序号，[dayStartMillis] = 该日 00:00 UTC 的毫秒）。 */
data class DayBucket(
    val dayIndex: Long,
    val dayStartMillis: Long,
    val success: Int,
    val failure: Int,
    val skipped: Int,
) {
    val total: Int get() = success + failure + skipped
}

/** 按路由类型的使用量（[kind] 为原始字面值，`null` = 旧行；[label] 为友好名）。 */
data class KindUsage(
    val kind: String?,
    val label: String,
    val total: Int,
    val success: Int,
    val failure: Int,
    val skipped: Int,
)

/** 按 agent 后端的使用量。 */
data class BackendUsage(
    val backend: String?,
    val label: String,
    val total: Int,
    val success: Int,
    val failure: Int,
    val skipped: Int,
)

/** 任务总览（[successRate] ∈ [0,1]；[avgElapsedMillis] = 平均耗时）。 */
data class UsageSummary(
    val total: Int,
    val success: Int,
    val failure: Int,
    val skipped: Int,
    val avgElapsedMillis: Long,
    val successRate: Double,
)

/** 单个发送者的对话量 / 被拒量 / 用户名（[avatarUrl]：最后一条非空聚合）。 */
data class SenderUsage(
    val senderOpenId: String,
    val userName: String,
    val avatarUrl: String = "",
    val messageCount: Int,
    val rejectedCount: Int,
) {
    val total: Int get() = messageCount + rejectedCount
}

/** 白黑名单快捷动作（用户详情页访问状态卡四按钮）。 */
enum class SenderAccessAction {
    ADD_TO_TRUSTED,
    REMOVE_FROM_TRUSTED,
}

/**
 * 单个用户的详情聚合。
 * [firstActiveMillis]/[lastActiveMillis] 任一流为空时取另一流，双空（纯 audit 或纯 stats 场景）仍能给出已知端点；
 * 双流全空时 null（buildSenderDetail 已在组空时短路）。
 */
data class SenderDetail(
    val senderOpenId: String,
    val userName: String,
    val avatarUrl: String,
    val messageCount: Int,
    val rejectedCount: Int,
    /** 首次活跃（epoch millis；null = 无统计与审计记录）。 */
    val firstActiveMillis: Long?,
    /** 最近活跃（epoch millis）。 */
    val lastActiveMillis: Long?,
    /** 对话记录是否被 [UsageStats.SENDER_DETAIL_MAX_AUDIT] 截断。 */
    val auditTruncated: Boolean,
    /** 最近的对话记录（时间升序，尾部 [maxAudit] 条）。 */
    val records: List<SenderAuditRecord>,
)

/** 对话记录单条（audit 派生；🔴 对话原文不落盘——一旦落盘即不可逆的隐私暴露；prompt 是产出侧截断的 200 字摘要）。 */
data class SenderAuditRecord(
    val ts: Long,
    val kindLabel: String,
    val prompt: String,
    val status: String,
    val elapsedMillis: Long,
    val backendLabel: String,
)
