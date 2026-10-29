package com.jiaoay.easy_dev_toolbox.core.data

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 密钥文件权限硬化的单源（判定谓词 + 600 落盘助手）。
 *
 * [PREFERENCES_MARKER] 是「文件名含 .preferences_pb 即密钥文件」的**包含**匹配标记，
 * 供启动期扫描（composeApp 的 SecretFilePermHardeningInitializer）、写路径挂钩
 * （LocalDataManager 的 post-edit 硬化）与守护测试三处共用——谓词不得在各调用点私写：
 * 后缀变体（如 xxx.preferences_pb.bak-forensic）不以 marker 结尾，endsWith 匹配必漏。
 *
 * 明文密钥按平台默认权限落盘的暴露面在非 POSIX 平台（Windows）依旧——本对象跳过时
 * 留 warn 痕，不宣称跨平台已收口。
 */
object SecretFilePerms {

    /** 密钥 preferences 文件名标记（DataStore 要求扩展名 preferences_pb；备份/取证后缀变体仍包含此标记） */
    const val PREFERENCES_MARKER = ".preferences_pb"

    /** 密钥文件判定：文件名包含 [PREFERENCES_MARKER]（覆盖 .bak-forensic 等后缀变体）。 */
    fun isSecretPreferencesFile(name: String): Boolean = name.contains(PREFERENCES_MARKER)

    /** POSIX 文件系统支持（构造期一次判定；非 POSIX 平台硬化整体跳过并留痕） */
    val posixSupported: Boolean =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private const val TAG = "SecretFilePerms"

    // 每会话至多一次的告警限频：挂钩在每次配置写后经过，逐次刷屏会淹没真实信号
    private val skipWarned = AtomicBoolean(false)
    private val failureWarned = AtomicBoolean(false)

    /**
     * 设 rw-------(600)。绝不向上抛——调用点在 DataStore 写路径上，权限收紧失败
     * 不得放大为配置保存失败。非 POSIX 平台跳过、其余失败均仅告警，且每会话至多一次。
     */
    fun harden600(file: File) {
        if (!posixSupported) {
            if (skipWarned.compareAndSet(false, true)) {
                JLog.w(TAG) { "非 POSIX 文件系统，密钥文件 600 硬化跳过（明文按平台默认权限落盘）：${file.name}" }
            }
            return
        }
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        }.onFailure {
            if (failureWarned.compareAndSet(false, true)) {
                JLog.w(TAG) { "密钥文件 600 硬化失败（每会话至多告警一次）：${file.name} err=${it.message?.take(200)}" }
            }
        }
    }
}
