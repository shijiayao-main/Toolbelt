package com.jiaoay.easy_dev_toolbox.core.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import java.io.File
import java.util.Properties

/**
 * Toolbelt 进程唯一 SQLite 数据库（P1 存储层）。
 *
 * ## 定位与纪律
 * - **进程唯一实例**（对齐 [com.jiaoay.easy_dev_toolbox.core.data.AppDataStore] 的同款纪律：
 *   SQLite 同文件多连接写会锁竞争，全进程经 [database] 单点取用）；
 * - **WAL 模式**：`PRAGMA journal_mode=WAL`（读不阻塞写、崩溃恢复快；桌面单机多连接
 *   ——未来 CLI 与 App 并行访问同目录时受益；不支持网络文件系统——桌面场景无碍）；
 * - **宿主数据根注入**：库文件落 `~/.easy_dev_toolbox/db/toolbelt.db`（[FileUtils.getDbDir]
 *   对齐 getLarkDir 先例；模块不做路径决策）；
 * - schema 由 SQLDelight 生成器从 v1 起步（`ToolbeltDb.Schema`——新真源，
 *   与 bridge-config schemaVersion 旧账互不干扰）；迁移经 `Schema.migrate(AfterVersion)` 扩展。
 *
 * ## 测试接缝
 * [forFile] 可注入任意路径；[inMemory] 供单测（SqlSchema 直建、零文件）。
 */
object Database {

    private const val DB_FILE_NAME = "toolbelt.db"

    @Volatile
    private var instance: ToolbeltDb? = null

    // ── 连接收敛：chat_history 共享 JDBC 连接 ──

    /**
     * chat_history 域的进程唯一 JDBC 连接（[SqliteChatHistoryProvider.forFile] 经此取用）。
     *
     * 旧形态每 provider 新开 DriverManager 长连接且永不关（FlowViewModel 重建即泄漏一条，
     * 三消费点 = 桥接/Quest 历史/SessionStore 同库三连接）。收敛为同路径进程单条——
     * WAL 下与 SQLDelight 驱动连接并发安全（读不阻塞写）。
     * ConcurrentHashMap.computeIfAbsent 原子建连（同 [database] 双检锁等价的并发安全）；
     * 按路径区分（测试临时库多路径并存）。
     */
    private val sharedConnections = java.util.concurrent.ConcurrentHashMap<String, java.sql.Connection>()

    fun chatHistoryConnection(dbPath: String): java.sql.Connection =
        sharedConnections.computeIfAbsent(dbPath) {
            java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath").also { conn ->
                // WAL：与 SQLDelight 连接并发读写不互斥（同 [forFile] props 语义）
                runCatching { conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") } }
            }
        }

    /** 进程唯一数据库（首次调用懒初始化；线程安全——双检锁经 @Volatile 幂等） */
    fun database(): ToolbeltDb = instance ?: synchronized(this) {
        instance ?: forFile(File(FileUtils.getDbDir(), DB_FILE_NAME)).also { instance = it }
    }

    /** 测试/自定义落点：直接以指定文件建库（不进进程单例池） */
    fun forFile(dbFile: File): ToolbeltDb {
        dbFile.parentFile?.mkdirs()
        val props = Properties().apply {
            // WAL：桌面多连接并发（会话写 + 列表读）不互斥
            put("journal_mode", "WAL")
            // 外键约束（messages.session_id REFERENCES sessions）默认关——显式开
            put("foreign_keys", "true")
        }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", props)
        val schema: SqlSchema<QueryResult.Value<Unit>> = ToolbeltDb.Schema
        // 🔴 建表语义：SQLDelight 无 .sqm 迁移文件时 migrate() 只回放迁移、不建表——
        //    首次建库必须显式 create()；老库凭 user_version 判断（< schema.version 时
        //    create 幂等——已存在表 CREATE IF NOT EXISTS 语义 + migrate 回放增量）。
        //    🔴 user_version 回写在两分支**之后**统一执行（修复）：
        //    migrate() 自身不落 user_version——若只在 create 分支回写，v1 存量库升 v2 后
        //    版本号仍停在 1，下次启动重复回放 1.sqm 撞「table already exists」。
        val currentVersion = queryUserVersion(driver)
        if (currentVersion < schema.version) {
            if (currentVersion == 0L) {
                schema.create(driver).value
            } else {
                schema.migrate(driver, currentVersion, schema.version).value
            }
            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0, null).value
        }
        return ToolbeltDb(driver)
    }

    /** 内存库（单测用——零文件零清理） */
    fun inMemory(): ToolbeltDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ToolbeltDb.Schema.create(driver).value
        return ToolbeltDb(driver)
    }

    /** 当前 schema 版本（迁移断言测试缝——「回写当前版本」语义免写死数字） */
    fun schemaVersionForTest(): Long = ToolbeltDb.Schema.version

    /** SQLite 的 user_version 页头读当前 schema 版本（0 = 空库/新建——migrate 从 0 起即建表） */
    private fun queryUserVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            QueryResult.Value(if (cursor.next().value) (cursor.getLong(0) ?: 0L) else 0L)
        }, 0, null).value

    /** 进程内重置（仅测试/守护场景用；生产不调） */
    fun resetForTest() {
        synchronized(this) { instance = null }
    }
}
