package com.jiaoay.easy_dev_toolbox.core.data

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.nio.file.Files

/**
 * 数据根一次性改名迁移：旧根（`~/.easy_dev_toolbox`）存在且新根（`~/.toolbelt`）不存在 ⇒
 * 整树改名（目录 rename，零拷贝）。发版前定名批的存量承接——迁移后新根成为唯一真源。
 *
 * 调用时序契约：必须在任何数据根访问（DataStore 首读写、logback
 * 文件 appender、SQLite 打开、桥接装配都会创建/占用数据根文件）之前同步执行，
 * 唯一调用点 = 宿主 main() 入口段（`application {}` 组合块之前）——晚于任何
 * 数据根访问即新旧双写。
 *
 * 幂等：新根已在 ⇒ no-op（旧根若同时残留则原样保留，不合并不删除——用户自行处理）。
 * 失败不阻塞启动：旧目录原样保留，用户可手动改名后重启（数据不丢）。
 * 纯 CLI 形态（lark-bridge / log-tools fat-jar）不走本迁移——文档写手动 mv 指引。
 */
object LegacyDataRootMigration {
    private const val TAG = "LegacyDataRootMigration"

    fun migrateIfNeeded() {
        val legacy = FileUtils.getLegacyAppDataDir()
        val current = FileUtils.getAppDataDir()
        if (!legacy.isDirectory || current.exists()) return
        runCatching {
            Files.move(legacy.toPath(), current.toPath())
        }.fold(
            onSuccess = {
                JLog.i(TAG) { "数据根已迁移：${legacy.path} → ${current.path}" }
            },
            onFailure = { e ->
                JLog.e(TAG, e) { "数据根迁移失败，保留旧目录（可手动改名后重启）：${legacy.path}" }
            },
        )
    }
}
