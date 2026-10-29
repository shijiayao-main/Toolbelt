package com.jiaoay.lark.bridge.config

import java.nio.file.Path
import java.security.MessageDigest

/**
 * 配置域路径派生（[CDS §1.2]）。🔴 纯数据类，数据根 [dataRoot] 由宿主**注入**（[CDS §1.3]）：
 * - App（:feature-larkbridge）：FileUtils.getLarkDir().toPath()；
 * - CLI（:lark-bridge shadowJar）：--data-dir 旗标，缺省 File(user.home, ".toolbelt/lark").toPath()。
 *
 * lib 侧只接受 Path/File 参数，**不引用 :core FileUtils**（jvmTarget 方向约束）。
 * 新旧产物落同一目录，audit 字段 schema 与旧完全一致（现实约束：用户已有真实 audit.jsonl 数据）。
 */
data class ConfigPaths(val dataRoot: Path) {

    /** 配置文档唯一真源：<dataRoot>/bridge-config.json */
    val configFile: Path get() = dataRoot.resolve("bridge-config.json")

    /** 每次覆写前备份：<dataRoot>/bridge-config.json.bak（save 落盘恒先备份现有主文件） */
    val configBackup: Path get() = dataRoot.resolve("bridge-config.json.bak")

    /** 审计日志（JSONL，schema 与存量旧格式一致）：<dataRoot>/audit.jsonl */
    val auditFile: Path get() = dataRoot.resolve("audit.jsonl")

    /** 发送者统计（JSONL，schema 与旧一致）：<dataRoot>/sender_stats.jsonl */
    val senderStatsFile: Path get() = dataRoot.resolve("sender_stats.jsonl")

    // 存量磁盘上的 upload-key-memo.jsonl 不再被读取（无消费方），用户可自行删除。

    /**
     * 机器人回复账本：<dataRoot>/card-reply-ledger.jsonl。
     * 🔴 运行时账本定位（同 audit.jsonl 先例，不是配置）：若纯内存（进程重启即丢），
     * 重启后引用机器人回复必 miss，持久化后跨重启可命中。
     */
    val cardReplyLedgerFile: Path get() = dataRoot.resolve("card-reply-ledger.jsonl")

    /** 单实例互斥锁目录：<dataRoot>/locks/ */
    val locksDir: Path get() = dataRoot.resolve("locks")

    /**
     * 同 appId 单实例互斥锁文件：<dataRoot>/locks/<sha256(appId).take(16)>.lock。
     * 用 appId 的 sha256 前 16 位命名，避免把 appId 明文写进文件名（泄露面缓解）。
     */
    fun lockFile(appId: String): Path = locksDir.resolve(sha256Hex(appId).take(16) + ".lock")

    companion object {
        /**
         * 文件消息下载临时目录前缀（legacy 残留清理用）：<workspaceRoot>/downloads/tmp-*。
         * 依赖 workspaceRoot（非 dataRoot），故为独立纯函数，不占 dataRoot 派生。
         * ⚠️ 当前生产侧无写入方（旧模式遗留形态），仅 DownloadStore.rescan 清理 legacy 残留时按同前缀匹配。
         */
        fun downloadsTmpPrefix(workspaceRoot: Path): Path = workspaceRoot.resolve("downloads").resolve("tmp-")

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
