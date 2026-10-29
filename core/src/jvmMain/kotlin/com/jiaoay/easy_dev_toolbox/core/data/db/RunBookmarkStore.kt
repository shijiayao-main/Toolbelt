package com.jiaoay.easy_dev_toolbox.core.data.db

/**
 * run 级查看器书签仓储门面（行号 + 行首时间戳指纹双锚载荷的单一读写面）。
 *
 * ## 定位
 * 查看器书签锚定会话行偏移索引（漂移重开即失效），持久化跨会话换锚为
 * (run, 文件路径, 1 基行号) + 行首时间戳指纹——恢复侧（查看器编排层）回读
 * 行文校验指纹一致才复用，不一致拒绝复活旧签。指纹算法单源在 log-tools
 * （`String.parseLogLineTimestamp`），本仓储只存取不校验。
 *
 * ## 写入语义
 * 快照覆写（[saveSnapshot]）：先清该 (run, 文件) 既有行再写当前快照——书签是
 * 全量集语义，无增量合并面；写入时机由编排层定（标记/清空动作时随快照持久化）。
 */
class RunBookmarkStore(private val db: ToolbeltDb) {

    /** 持久化书签行（[lineNumber] 1 基——与查看器行号口径同基；[lineFingerprint] 行首时间戳指纹） */
    data class StoredBookmark(val lineNumber: Int, val lineFingerprint: String)

    /** 快照覆写：(runId, filePath) 既有书签全量替换为 [bookmarks]（空列表 = 清空该文件书签） */
    fun saveSnapshot(
        runId: String,
        filePath: String,
        bookmarks: List<StoredBookmark>,
        now: Long = System.currentTimeMillis(),
    ) {
        db.transaction {
            db.runBookmarksQueries.deleteBookmarksForFile(runId, filePath)
            bookmarks.forEach {
                db.runBookmarksQueries.insertBookmark(
                    run_id = runId,
                    file_path = filePath,
                    line_number = it.lineNumber.toLong(),
                    line_fingerprint = it.lineFingerprint,
                    created_at = now,
                )
            }
        }
    }

    /** 读 (runId, filePath) 全部书签（行号升序；无行恒空） */
    fun bookmarksFor(runId: String, filePath: String): List<StoredBookmark> =
        db.runBookmarksQueries.bookmarksForFile(runId, filePath).executeAsList().map {
            StoredBookmark(lineNumber = it.line_number.toInt(), lineFingerprint = it.line_fingerprint)
        }

    /** 清空该 run 全部书签（run 删除级联面——[RunStore.delete] 事务承载） */
    fun deleteForRun(runId: String) {
        db.runBookmarksQueries.deleteBookmarksForRun(runId)
    }
}
