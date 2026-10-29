package com.jiaoay.easy_dev_toolbox.core.data.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 日志工作台 run 仓储门面。
 *
 * ## 定位
 * run 对象（一次日志分析的元数据）的单一读写面：runs 表 CRUD + JSON 列编解码收口。
 * 时间线不复用本门面（messages 经 [SessionStore.append]，sessionId = runId——设计裁定
 * 时间线与 run 一一对应）；级联删除在此收口（🔴 SQLDelight 多语句命名查询只执行首条——
 * run 删除三步进事务：deleteRunMessages / deleteRunBookmarks / deleteRun——run 级
 * 书签载荷（[RunBookmarkStore]）随 run 同收，不留孤儿书签）。
 *
 * ## 管理面模式
 * [listRuns] 倒序 + [renameRun]/[deleteRun] 照 SqliteSessionStore 管理面先例
 * （listSummaries/rename/delete 同构）。
 */
class RunStore(private val db: ToolbeltDb) {

    private val json = Json
    private val pathListSerializer = ListSerializer(String.serializer())

    /** run 元数据行模型（UI 消费面——log_files 已解码为路径列表） */
    data class RunSummary(
        val id: String,
        val title: String,
        val logFiles: List<String>,
        val targetTime: String?,
        val rangeMinutes: Long?,
        val status: RunStatus,
        val reportPath: String?,
        val createdAt: Long,
        val updatedAt: Long,
    )

    /** run 状态（DB 存字符串；解码失败兜底 FAILED——前向兼容） */
    enum class RunStatus { RUNNING, COMPLETED, ABORTED, FAILED;

        companion object {
            fun from(raw: String?): RunStatus =
                runCatching { valueOf(raw!!.uppercase()) }.getOrDefault(FAILED)

            fun label(status: RunStatus): String = when (status) {
                RUNNING -> "运行中"
                COMPLETED -> "已完成"
                ABORTED -> "已中止"
                FAILED -> "失败"
            }
        }
    }

    /** 新建 run（同 id 覆写——幂等 upsert 语义与 insertRun ON CONFLICT 对齐） */
    fun create(
        id: String,
        title: String,
        logFiles: List<String>,
        targetTime: String? = null,
        rangeMinutes: Long? = null,
        status: RunStatus = RunStatus.RUNNING,
        now: Long = System.currentTimeMillis(),
    ) {
        db.runsQueries.insertRun(
            id = id,
            title = title,
            log_files = encodePaths(logFiles),
            target_time = targetTime,
            range_minutes = rangeMinutes,
            status = status.name.lowercase(),
            report_path = null,
            created_at = now,
            updated_at = now,
        )
    }

    /** 全量 run（updated_at 倒序——左栏列表消费面） */
    fun listRuns(): List<RunSummary> =
        db.runsQueries.allRuns().executeAsList().map { it.toSummary() }

    /** 单 run 查询（回看入口；不存在返回 null） */
    fun runById(id: String): RunSummary? =
        db.runsQueries.runById(id).executeAsOneOrNull()?.toSummary()

    /** 状态迁移（运行中 → 完成/中止/失败；updated_at 恒刷新） */
    fun updateStatus(id: String, status: RunStatus, now: Long = System.currentTimeMillis()) {
        db.runsQueries.updateRunStatus(status = status.name.lowercase(), updatedAt = now, id = id)
    }

    /** 报告落盘后回填路径 */
    fun updateReportPath(id: String, reportPath: String, now: Long = System.currentTimeMillis()) {
        db.runsQueries.updateRunReport(reportPath = reportPath, updatedAt = now, id = id)
    }

    /** 管理面重命名 */
    fun rename(id: String, title: String, now: Long = System.currentTimeMillis()) {
        db.runsQueries.renameRun(title = title, updatedAt = now, id = id)
    }

    /** 级联删除：时间线消息 + run 级行 + run 级书签（[RunBookmarkStore] 载荷同收，不留孤儿书签） */
    fun delete(id: String) {
        db.transaction {
            db.runsQueries.deleteRunMessages(id)
            db.runBookmarksQueries.deleteBookmarksForRun(id)
            db.runsQueries.deleteRun(id)
        }
    }

    // ── 编解码 ──

    private fun encodePaths(paths: List<String>): String = json.encodeToString(pathListSerializer, paths)

    private fun decodePaths(raw: String?): List<String> =
        runCatching { json.decodeFromString(pathListSerializer, raw ?: "[]") }.getOrDefault(emptyList())

    private fun Runs.toSummary() = RunSummary(
        id = id,
        title = title,
        logFiles = decodePaths(log_files),
        targetTime = target_time,
        rangeMinutes = range_minutes,
        status = RunStatus.from(status),
        reportPath = report_path,
        createdAt = created_at,
        updatedAt = updated_at,
    )
}
