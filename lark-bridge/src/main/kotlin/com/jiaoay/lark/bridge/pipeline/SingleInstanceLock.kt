// 同 appId 单实例互斥（App 与 CLI 共享同一把锁）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.config.ConfigPaths
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference

/**
 * 单实例锁的获取结果（三态，与网关契约的 `GatewayConnectResult` 三态一一对应）。
 */
sealed interface LockAcquisition {
    /**
     * 拿到锁。🔴 [lock] 实例**必须**由 [SingleInstanceLock] 持有并在 [SingleInstanceLock.close] 里释放——调用方直接 release 会让 close 的幂等释放抛错、锁提前失效放第二实例进来；
     * 这里暴露出来只为让调用方能断言 `isValid`（排障 / 测试）。
     */
    data class Acquired(val lockFile: Path, val appId: String, val lock: java.nio.channels.FileLock) : LockAcquisition

    /**
     * 被同 appId 的另一个实例占用（🔴 核心场景：App 与 CLI 同时跑会双份消费事件、双份起 agent、
     * 双份写 audit.jsonl 造成 JSONL 交错损坏）。调用方须：
     * 1. 把网关状态置 [com.jiaoay.lark.gateway.GatewayConnectionState.BlockedByPeerInstance]；
     * 2. 把错误映射成 [BridgeErrorCode.BlockedByPeerInstance]（UI 文案「同 App ID 已有实例运行」）；
     * 3. **不要**重连、不要退避重试（对端不退出就永远拿不到）。
     */
    data object BlockedByPeerInstance : LockAcquisition

    /** 文件系统层面的失败（无权限 / 磁盘满 / appId 为空）。 */
    data class Failed(val error: BridgeErrorCode, val message: String) : LockAcquisition
}

/**
 * 同 appId 单实例互斥锁。
 *
 * ## 锁文件路径
 * `<dataRoot>/locks/<sha256(appId).take(16)>.lock`，由 [ConfigPaths.lockFile] 派生（**不重复实现** sha256）。
 * 🔴 **App 与 CLI 共享同一把锁**：两者的 `dataRoot` 都是 `~/.toolbelt/lark`，
 *    appId 相同 ⇒ 路径相同 ⇒ 天然互斥，无需任何跨进程协议或 IPC。
 * 🔴 用 sha256 前 16 位而非 appId 明文命名：避免把 appId 写进文件名（泄露面缓解）。
 *
 * ## 为什么选 FileLock 而不是 pid 文件（已定案，此处记录理由以免被「优化」掉）
 * | 方案 | 进程崩溃时 | 需要的额外代码 |
 * |---|---|---|
 * | pid 文件 + 存活探测 | 锁**残留**，须判 pid 是否存活、pid 是否被复用、跨平台探测 | 陈旧锁清理（约 60 行且易错） |
 * | **`FileChannel.tryLock()`** | **OS 自动释放**（fcntl POSIX record lock 随进程退出由内核回收） | **零** |
 * ⇒ 选 FileLock：崩溃恢复是内核行为，不是应用代码行为 ⇒ 没有「陈旧锁」这个状态，也就没有清理代码。
 *
 * ⚠️ **实测注意（跨进程验证时的坑）**：Linux 上 `flock(2)` 与 Java `FileChannel.tryLock()` 用的
 *    fcntl POSIX record lock 是**两套互不感知**的锁 ⇒ 用 `flock(1)` 命令持锁**测不出**互斥。
 *    验证必须用**真实 JVM 子进程**（`SingleInstanceLockTest` 用 `ProgramLauncher` 起一个持锁 main）。
 *
 * ## 同 JVM 内的重入语义
 * `FileChannel.tryLock()` 在**同一 JVM** 里对同一文件第二次加锁会抛 [OverlappingFileLockException]
 * （POSIX record lock 是 per-process 的，JDK 用一张全局表模拟 per-file 语义）。本类把它**归一为**
 * [LockAcquisition.BlockedByPeerInstance] ⇒ 同 JVM 双实例与跨进程双实例的返回态一致（测试因此可在单 JVM 内断言）。
 *
 * ## 释放幂等
 * [close] / [releaseLock] 可被调用任意多次：`AtomicReference.getAndSet(null)` 保证只有第一次真正执行 release+close，
 * 之后全是 no-op（优雅关停里 stop() 与 shutdown hook 都可能调它）。
 */
class SingleInstanceLock(
    private val paths: ConfigPaths,
    private val log: BridgeLog = BridgeLog.Silent,
) : AutoCloseable {

    private val channelRef = AtomicReference<FileChannel?>(null)
    private val lockRef = AtomicReference<java.nio.channels.FileLock?>(null)
    private val fileRef = AtomicReference<Path?>(null)

    /** 当前是否持锁。 */
    val isHeld: Boolean get() = lockRef.get()?.isValid == true

    /** 当前锁文件路径（未持锁时为 null）。 */
    val lockFile: Path? get() = fileRef.get()

    /**
     * 尝试获取锁。**非挂起**（`tryLock` 立即返回，绝不阻塞）；**幂等**：已持锁时直接返回 Acquired。
     *
     * @param appId `connection.appId`；空白 ⇒ [LockAcquisition.Failed]（[BridgeErrorCode.ConfigInvalid]）。
     */
    fun tryAcquire(appId: String): LockAcquisition {
        if (appId.isBlank()) {
            return LockAcquisition.Failed(BridgeErrorCode.ConfigInvalid, "appId 为空，无法派生单实例锁文件")
        }
        // 幂等：重复 acquire 同一实例直接返回成功（stop→start 循环里不必先 close）
        lockRef.get()?.let { existing ->
            if (existing.isValid) {
                val file = fileRef.get() ?: paths.lockFile(appId)
                return LockAcquisition.Acquired(file, appId, existing)
            }
        }

        val target = paths.lockFile(appId)
        return try {
            Files.createDirectories(target.parent)
            val channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (overlap: OverlappingFileLockException) {
                // 同 JVM 内已有实例持锁 ⇒ 归一为 BlockedByPeerInstance（见类 KDoc）
                runCatching { channel.close() }
                log.warn("单实例锁被同 JVM 的另一实例占用：$target")
                return LockAcquisition.BlockedByPeerInstance
            }
            if (lock == null) {
                runCatching { channel.close() }
                log.warn("单实例锁被另一进程占用：$target")
                return LockAcquisition.BlockedByPeerInstance
            }
            channelRef.set(channel)
            lockRef.set(lock)
            fileRef.set(target)
            log.info("已取得单实例锁：$target")
            LockAcquisition.Acquired(target, appId, lock)
        } catch (io: IOException) {
            log.error("单实例锁获取失败：$target", io)
            LockAcquisition.Failed(BridgeErrorCode.ConfigWriteFailed, "锁文件不可写：${io.message}")
        } catch (security: SecurityException) {
            log.error("单实例锁权限不足：$target", security)
            LockAcquisition.Failed(BridgeErrorCode.ConfigWriteFailed, "锁文件权限不足：${security.message}")
        }
    }

    /**
     * 释放锁（幂等，`AutoCloseable` 实现）。释放后同一 appId 可被任意实例（含本实例）重新获取。
     */
    override fun close() {
        releaseLock()
    }

    /**
     * 释放锁并回报是否真的做了事。
     * @return true 表示本次调用真的执行了 release+close；false 表示本来就没持锁（no-op ⇒ 幂等证据）。
     */
    fun releaseLock(): Boolean {
        val lock = lockRef.getAndSet(null) ?: return false
        val channel = channelRef.getAndSet(null)
        fileRef.set(null)
        runCatching { if (lock.isValid) lock.release() }
            .onFailure { log.warn("释放单实例锁失败（进程可能已在退出）：${it.message}") }
        runCatching { channel?.close() }
        log.info("已释放单实例锁")
        return true
    }

    /** [releaseLock] 的 Unit 形态别名（与 [close] 等价，便于调用侧读语义）。 */
    fun release() {
        releaseLock()
    }
}
