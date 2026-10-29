package com.jiaoay.lark.bridge.config

import com.jiaoay.lark.bridge.BridgeLog
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桥接域密钥文件权限硬化的单源（主配置写路径与导入导出通道共用）。
 *
 * `BridgeConfig.appSecret` 是**明文字段**，凡承载它的文件（`bridge-config.json`、
 * `.bak` 备份、用户导出的 JSON 副本）同属密钥文件族——硬化口径必须在同一处定义，
 * 否则新增落盘通道时极易旁路（导出曾以裸 `writeText` 落盘，按进程 umask 建文件
 * 即是真实缺口）。
 *
 * 行为契约：
 * - POSIX 下设 `rw-------`(600)；非 POSIX（Windows）跳过，明文按平台默认权限落盘
 *   ——已知边界，留痕但不宣称收口（ACL 收紧属独立课题）。
 * - **绝不向上抛**：调用点都在写入路径上，权限收紧失败不得放大为保存失败。
 * - 告警每进程至多一次（逐次刷屏会淹没真实信号）。
 */
object SecretFilePermissions {

    private val warned = AtomicBoolean(false)

    /**
     * 尽可能把 [file] 收紧为仅属主可读写。
     *
     * @param log 告警出口（与调用方既有 [`BridgeLog`] 同源，便于汇入桥接日志台）
     * @return true = 硬化已生效；false = 非 POSIX 平台或设置失败（原因已留痕）。
     */
    fun harden600(file: File, log: BridgeLog = BridgeLog.Silent): Boolean = runCatching {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    }.fold(
        onSuccess = { true },
        onFailure = { e ->
            if (warned.compareAndSet(false, true)) {
                runCatching {
                    log.warn("POSIX 600 未生效（非 POSIX 平台或失败，每进程至多告警一次）：${file.name}", e)
                }
            }
            false
        },
    )
}
