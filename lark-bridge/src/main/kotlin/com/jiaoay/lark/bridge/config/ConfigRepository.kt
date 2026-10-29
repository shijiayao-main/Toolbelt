package com.jiaoay.lark.bridge.config

import com.jiaoay.lark.bridge.BridgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference

/**
 * 配置文档读取结果（lib 侧，不依赖 :core JResult）。
 * - [Loaded]：成功解析（已应用迁移 + 未知顶层键折入 experimental）。
 * - [Failed]：文件缺失（[BridgeErrorCode.ConfigNotFound]）或解析失败（[BridgeErrorCode.ConfigParseFailed]）；
 *   调用方据此回退 [DefaultBridgeConfig.default] 或提示用户（🔴 损坏文件不覆写，保留现场，[CDS §8.3]）。
 */
sealed interface ConfigLoadResult {
    data class Loaded(val config: BridgeConfig) : ConfigLoadResult
    data class Failed(val code: BridgeErrorCode, val message: String) : ConfigLoadResult
}

/**
 * 单一 JSON 配置文档仓库（[CDS §9]）。写入路径严格照 config-domain-spec.md §9.1 序列：
 * ```
 * save(draft) → 400ms trailing debounce → Mutex 单写者 → 覆写前备份 .bak
 *   → 写 bridge-config.json.tmp-<nanoTime>（同目录同文件系统）→ FileChannel.force(true)
 *   → POSIX 600 → Files.move(ATOMIC_MOVE, REPLACE_EXISTING)（不支持则降级 REPLACE_EXISTING）
 *   → 失败：删 tmp 保旧文件不动
 * ```
 * [scope] 由宿主注入（构造注入，无全局单例）；[flushNow] 供 stop()/onAppExit() 在 [NonCancellable] 内调用。
 * 🔴 数据根经 [paths] 注入，lib 侧不引用 :core FileUtils（jvmTarget 方向约束）。
 */
class ConfigRepository(
    private val paths: ConfigPaths,
    private val scope: CoroutineScope,
    private val json: Json = BridgeJson,
    private val log: BridgeLog = BridgeLog.Silent,
    private val debounceMillis: Long = 400L,
) {

    /** 单写者锁：sink dispatcher limitedParallelism(1) 天然有序，Mutex 是第二道保险（[CDS §9.2]）。 */
    private val writeMutex = Mutex()

    /**
     * 待落盘的最新 draft（trailing debounce 只保留最后一次）。
     * 用 [AtomicReference] 而非 `@Volatile var`：实例级可变状态统一走原子引用——
     * LarkSdkIsolationTest F 组守护的是 **object/companion/顶层** 静态全局可变状态
     * 零命中（作用域感知：类级 `@Volatile var` 合法不误伤；静态全局可变状态曾致
     * stale 实例 bug 故禁——lib 侧一律构造注入）。
     */
    private val pendingConfig = AtomicReference<BridgeConfig?>(null)

    /** 当前 debounce 计时任务；新 save 取消旧任务实现 trailing 语义。 */
    private val debounceJob = AtomicReference<Job?>(null)

    /**
     * 读取并解析配置文档（同步）：应用迁移链 + 未知顶层键折入 experimental，再 decode。
     * 文件缺失 → [ConfigLoadResult.Failed]（ConfigNotFound）；损坏 → Failed（ConfigParseFailed），🔴 不覆写、不抛。
     */
    fun load(): ConfigLoadResult {
        val file = paths.configFile.toFile()
        if (!file.exists()) {
            return ConfigLoadResult.Failed(BridgeErrorCode.ConfigNotFound, "配置文件不存在：${file.path}")
        }
        return try {
            // 🔴 剥离 UTF-8 BOM——Windows 记事本保存的 JSON 恒带 BOM，
            //    解析器遇 BOM 直接失败（用户只是用记事本改了个值，配置就整个读不出来）。
            //    :lark-bridge 不依赖 :core，自带同一语义（BomStripContractTest 防漂移）。
            val raw = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
            val doc = json.parseToJsonElement(raw).jsonObject
            val migrated = ConfigMigrations.foldUnknownIntoExperimental(ConfigMigrations.migrate(doc))
            ConfigLoadResult.Loaded(json.decodeFromJsonElement(BridgeConfig.serializer(), migrated))
        } catch (e: Throwable) {
            log.warn("配置解析失败，保留现场不覆写：${file.path}", e)
            ConfigLoadResult.Failed(BridgeErrorCode.ConfigParseFailed, "配置解析失败：${e.message}")
        }
    }

    /**
     * 提交 draft：400ms trailing debounce 后落盘（高频键击合并为一次写）。
     * 非阻塞——立即返回，实际写入在 [scope] 上调度。
     */
    fun save(config: BridgeConfig) {
        pendingConfig.set(config)
        debounceJob.getAndSet(null)?.cancel()
        debounceJob.set(
            scope.launch {
                delay(debounceMillis)
                flushPending()
            },
        )
    }

    /**
     * 立即落盘（跳过 debounce），供 stop()/onAppExit() 调用。
     * 🔴 调用方应在 [NonCancellable] 上下文内调用本方法以确保退出前写完；此处再包一层双保险。
     */
    suspend fun flushNow() {
        debounceJob.getAndSet(null)?.cancel()
        withContext(NonCancellable) { flushPending() }
    }

    private suspend fun flushPending() {
        if (pendingConfig.get() == null) return
        writeMutex.withLock {
            // 双检：加锁后取最新 draft（getAndSet 原子取出并清空）
            val current = pendingConfig.getAndSet(null) ?: return@withLock
            runCatching { writeAtomic(current) }
                .onFailure { e ->
                    log.error("配置写入失败，保留旧文件", e)
                    // 写失败：把 draft 放回 pending 以便重试（不丢用户编辑）
                    pendingConfig.set(current)
                }
        }
    }

    /** config-domain-spec.md §9.1 原子写序列（阻塞 IO，跑 [Dispatchers.IO]）。失败抛出由上层记录并保旧文件。 */
    private suspend fun writeAtomic(config: BridgeConfig): Unit = withContext(Dispatchers.IO) {
        val target = paths.configFile.toFile()
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        try {
            // 覆写前备份现有文件到 .bak（后续覆写 .bak；[CDS §9.1]）
            if (target.exists()) {
                runCatching { target.copyTo(paths.configBackup.toFile(), overwrite = true) }
                    .onFailure { log.warn("备份 .bak 失败（继续写入）", it) }
                // .bak 与主文件同为明文 appSecret 落盘处，copyTo 按进程 umask 建文件——
                // 备份必须同调 600，否则旁路主文件硬化
                val backup = paths.configBackup.toFile()
                if (backup.isFile) setPosix600(backup)
            }
            tmp.writeText(json.encodeToString(BridgeConfig.serializer(), config), Charsets.UTF_8)
            // fsync：确保数据落盘再 rename（断电一致性）
            FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            setPosix600(tmp)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (atomicUnsupported: AtomicMoveNotSupportedException) {
                // 降级：非原子替换（跨文件系统 / 平台不支持 ATOMIC_MOVE）
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            runCatching { if (tmp.exists()) tmp.delete() } // 失败删 tmp，保留旧文件不动
            throw e
        }
    }

    /**
     * POSIX 600 硬化转调 [SecretFilePermissions] 单源（导出通道共用同一实现，
     * 口径不得在此私写一份）。
     */
    private fun setPosix600(file: File) {
        SecretFilePermissions.harden600(file, log)
    }
}
