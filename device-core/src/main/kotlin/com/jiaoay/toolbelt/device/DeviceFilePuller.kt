package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 设备文件拉取进程句柄（拉取取消面）：[AdbPullGateway] 实现在进程启动后经
 * [AdbPullGateway.execute] 的 onStarted 回调交出本句柄，调用方 [kill] 即终止
 * 在途拉取。与 进程句柄 分离是测试缝——fake runner 零真子进程即可
 * 模拟「kill 后退出码非零」的取消语义。
 */
fun interface AdbPullHandle {
    /** 请求终止在途拉取进程（幂等，可安全多次调用） */
    fun kill()
}

/**
 * 拉取执行网关（注入缝）：执行一条 adb pull 命令全文，进程启动后回调句柄、
 * 退出前行输出经 onLine 逐行回调，返回真实退出码（进程启动真失败 = Failure）。
 */
fun interface AdbPullGateway {
    suspend fun execute(
        command: String,
        onStarted: (AdbPullHandle) -> Unit,
        onLine: suspend (ShellOutput) -> Unit,
    ): Result<Int>
}

/**
 * 默认网关：委托宿主机注入的 [AdbProcessRunner] 流式通道。
 *
 * 走流式而非一次性通道的唯一理由是**中途取消**：adb 无原生进度协议，两种通道承载
 * 一行完成回执等价；但用户中断需要进程句柄，流式通道的 onStarted 句柄 + 收割升级链
 * 是唯一现成取消通道。
 */
class StreamingAdbPullGateway(private val runner: AdbProcessRunner) : AdbPullGateway {
    override suspend fun execute(
        command: String,
        onStarted: (AdbPullHandle) -> Unit,
        onLine: suspend (ShellOutput) -> Unit,
    ): Result<Int> = runner.executeStreaming(
        command,
        onStarted = { handle -> onStarted(AdbPullHandle { handle.kill() }) },
        onLine = onLine,
    ).map { it.exitCode }
}

/**
 * 拉取完成回执的公共面（成功态回执的载体选型锚）：单文件与目录两条拉取链的
 * 回执共读本接口，消费面（VM 产物会话缓存/衔接动作门）按实现类型分派——
 * 衔接/预览语义只对 [DeviceFilePullReceipt] 成立，目录回执无单文件级语义。
 */
sealed interface DevicePullReceipt {
    /** 本地终态产物路径（目录链为目录，文件链为文件） */
    val localPath: String
}

/**
 * 单文件拉取完成回执（成功态）。
 *
 * 契约：[sizeBytes] 是**校验通过**的本地实际字节数（拉取前设备侧 stat 期望值与
 * 本地实际相等才转正，两者同值故单字段承载）；[summaryBytes]/[speedText] 来自
 * adb 完成摘要行（`1 file pulled, 0 skipped. 38.4 MB/s (N bytes in Xs)`），格式
 * 变化或缺失时为 null（摘要行是信息面不是校验面，缺席不否定拉取）。
 */
data class DeviceFilePullReceipt(
    override val localPath: String,
    val sizeBytes: Long,
    val summaryBytes: Long? = null,
    val speedText: String? = null,
) : DevicePullReceipt

/**
 * 目录拉取完成回执（成功态）。
 *
 * 契约：[fileCount]/[totalBytes]/[speedText] 全部来自 adb 完成摘要行
 * （`N files pulled, 0 skipped. X MB/s (M bytes in Ys)`），是**信息面**——
 * 目录链没有单文件链的 stat 期望尺寸校验载体（目录无单值期望 size，
 * 逐文件比对的协议面不存在），本回执**不冒充单文件级完整性校验**：退出码 0
 * + 本地临时目录成形即转正，字段缺席为 null（摘要行格式变化或缺失不否定
 * 拉取）。中断半途目录由 .part 临时目录语义兜底（见 [pullDirectory]）。
 */
data class DeviceDirectoryPullReceipt(
    override val localPath: String,
    val fileCount: Long? = null,
    val totalBytes: Long? = null,
    val speedText: String? = null,
) : DevicePullReceipt

/**
 * 设备文件拉取内核（设备观测域）：`adb pull` 的先落 .part 临时态 → 校验 →
 * 原子转正管线，承载两条链——单文件（[pullFile]，校验面 = 设备侧 stat 期望
 * 尺寸 vs 本地实际）与目录递归（[pullDirectory]，校验面弱化契约与其 KDoc）。
 *
 * 通道纪律（与 [DeviceFileBrowser] 同链口径）：命令全文经 [pullCommand] 构造，
 * 双路径按本机执行 shell 语义引号包裹（Unix 单引号零展开——路径含空格/`$`/
 * 反引号必须字面化），再经 [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`；
 * 执行走 [AdbPullGateway]（默认流式通道），fake 注入即可单测。
 *
 * 完整性契约（中断的 adb pull 会在最终路径留下无标记部分产物冒充完整——
 * .part 语义是硬需求，不是优化）：
 * - 两条链的拉取恒写 `<目标>.part` 临时态（文件或目录），成功转正后才
 *   rename 到最终路径——任何时刻中断，最终路径上要么是上一次的完整产物、
 *   要么不存在，绝无半截新产物；
 * - 单文件链校验 = 拉取前设备侧 stat 期望大小 vs 本地实际大小，不等即失败：
 *   删 .part、不落终态文件，人话回执「传输中断不完整已清理」；
 * - 退出码非零（含 [requestCancel] 触发的 kill）删临时态落失败回执（取消态
 *   回执区别于设备侧错误透传）；
 * - 期望值取拉取开始时刻的设备侧快照：传输期间设备侧仍在增长的文件（设备
 *   写入中的日志）会因实际 > 期望判不匹配清理——校验从严是取舍，登记残余；
 *   静止文件（常规场景：回放/离线日志）不受影响；
 * - 两条链各自单飞行（每实例同时至多一个在途拉取，[requestCancel] 只作用于
 *   当前在途进程）；取消/异常路径同样走临时态清理（finally 兜底，协程被取消
 *   时收割进程后清临时产物再放行取消异常）。
 */
class DeviceFilePuller(
    private val runner: AdbProcessRunner,
    private val gateway: AdbPullGateway = StreamingAdbPullGateway(runner),
    private val statEntry: suspend (path: String, serial: String) -> Result<DeviceFileStat> =
        Companion::defaultStatEntry,
    private val endpointReader: () -> AdbEndpoint,
) {

    /** 在途拉取进程句柄（null = 无在途；仅 pullFile 执行期非空） */
    @Volatile
    private var activeHandle: AdbPullHandle? = null

    /** 取消已请求标记：区分「用户取消」与「设备侧错误」两种非零退出回执 */
    @Volatile
    private var cancelRequested = false

    /**
     * 请求取消当前在途拉取（幂等；无在途时为无动作；对文件/目录两条链通用）。
     * 取消后 [pullFile]/[pullDirectory] 落取消人话回执并清理 .part 临时态，
     * 不落终态产物。
     */
    fun requestCancel() {
        cancelRequested = true
        activeHandle?.kill()
    }

    /**
     * 拉取单个设备文件到本地目标路径（不存在的父目录按需创建；同名终态文件
     * 被本次拉取结果原子覆盖）。[localTargetPath] 为最终路径，.part 派生自它。
     */
    suspend fun pullFile(devicePath: String, localTargetPath: String, serial: String): Result<DeviceFilePullReceipt> {
        if (devicePath.isEmpty() || localTargetPath.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("设备路径、本地目标路径或串号为空，未执行拉取"))
        }
        cancelRequested = false
        val stat = statEntry(devicePath, serial).getOrElse { e ->
            return Result.failure(
                IllegalStateException("拉取前置校验失败（未读取到设备侧文件大小）：${e.message ?: "未知异常"}"),
            )
        }
        if (stat.type != DeviceFileType.FILE) {
            val typeText = when (stat.type) {
                DeviceFileType.DIRECTORY -> "目录"
                DeviceFileType.SYMLINK -> "symlink"
                DeviceFileType.FILE -> "普通文件"
            }
            return Result.failure(IllegalStateException("仅支持拉取普通文件（$devicePath 是$typeText），未执行拉取"))
        }
        val expectedSize = stat.sizeBytes
        val part = File(localTargetPath + PART_SUFFIX)
        try {
            return executePull(devicePath, part, expectedSize, serial)
        } catch (t: Throwable) {
            activeHandle?.kill()
            if (t is CancellationException) throw t
            return Result.failure(IllegalStateException("拉取异常：${t.message ?: "未知异常"}"))
        } finally {
            activeHandle = null
            cleanupPart(part)
        }
    }

    /**
     * 拉取设备目录（递归）到本地目标目录路径（不存在的父目录按需创建）。
     * [localTargetPath] 为最终目录路径，`.part` 临时目录派生自它。
     *
     * 与单文件链（[pullFile]）的语义差异（如实登记，不冒充单文件级校验）：
     * - **校验面弱化**：目录无单值期望 size——单文件链的「设备侧 stat 期望 vs
     *   本地实际」逐字节校验对目录无载体，本链的完整性面 = adb 退出码 0 + 本地
     *   `.part` 临时目录成形（非目录形态即失败清理）；摘要行的 N 个文件/M 字节
     *   是信息面不参与校验；
     * - 前置 stat 缺席 by design：类型门由调用面（VM 按 ls 读数只对目录条目
     *   放行）承载，设备侧条目失效由退出码人话兜底；
     * - 同名终态目录覆盖语义与单文件一致（终态被本次结果替换），但目录形态
     *   **无单步原子替换**：`Files.move` REPLACE_EXISTING 对非空目标目录必败
     *   （DirectoryNotEmptyException），覆盖 = 旧终态先让位（同盘 rename 至
     *   `.part-old` 旁路）→ 新目录转正 → 旁路清理；让位与转正之间存在无终态
     *   窗口（弱于单文件链的单步原子替换，如上登记）——转正失败时旁路归位
     *   回滚，旧终态不无端丢失；
     * - `.part` 残留自愈：拉取前先递归清理上次中断残留的 `.part` 临时目录
     *   （残留即不完整产物，绝不冒充终态）；
     * - 中断（退出码非零，含 [requestCancel] 的 kill）/异常/协程取消同走
     *   `.part` 递归清理（finally 兜底），不落终态目录。
     */
    suspend fun pullDirectory(devicePath: String, localTargetPath: String, serial: String): Result<DeviceDirectoryPullReceipt> {
        if (devicePath.isEmpty() || localTargetPath.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("设备路径、本地目标路径或串号为空，未执行拉取"))
        }
        cancelRequested = false
        val partDir = File(localTargetPath + PART_SUFFIX)
        try {
            return executeDirectoryPull(devicePath, partDir, serial)
        } catch (t: Throwable) {
            activeHandle?.kill()
            if (t is CancellationException) throw t
            return Result.failure(IllegalStateException("拉取异常：${t.message ?: "未知异常"}"))
        } finally {
            activeHandle = null
            cleanupPartDirectory(partDir)
        }
    }

    /**
     * 单次拉取执行体（前置校验已过）：spawn → 收割 → 校验 → 转正。
     * .part 的清理由调用方 finally 兜底（本函数成功转正后 .part 已不存在，清理为无操作）。
     */
    private suspend fun executePull(
        devicePath: String,
        part: File,
        expectedSize: Long,
        serial: String,
    ): Result<DeviceFilePullReceipt> {
        part.parentFile?.takeIf { !it.exists() }?.mkdirs()
        cleanupPart(part)
        val endpoint = endpointReader()
        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()
        val exitCode = gateway.execute(
            pullCommand(devicePath, part.absolutePath, endpoint.executablePath, serial),
            onStarted = { activeHandle = it },
            onLine = { output ->
                if (output.source == ShellOutputSource.STDOUT) stdoutLines += output.line else stderrLines += output.line
            },
        ).getOrElse { e ->
            return Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}"))
        }
        if (exitCode != 0) {
            val reason = when {
                cancelRequested -> "拉取已取消（传输未完成，临时文件已清理）"
                else -> "拉取失败（adb 退出码 $exitCode）：${firstLine(stderrLines, stdoutLines)}——临时文件已清理"
            }
            return Result.failure(IllegalStateException(reason))
        }
        val localSize = part.length()
        if (localSize != expectedSize) {
            return Result.failure(
                IllegalStateException(
                    "传输中断，文件不完整已清理（本地 ${localSize}B，设备侧期望 ${expectedSize}B）" +
                        "——若文件在设备侧仍在写入（日志增长中），请在写入停止后重试",
                ),
            )
        }
        val summary = parsePulledSummary(stdoutLines)
        val target = File(part.parent, part.nameWithoutExtension)
        runCatching {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { e ->
            return Result.failure(IllegalStateException("转正失败（临时文件已清理）：${e.message ?: "未知异常"}"))
        }
        return Result.success(
            DeviceFilePullReceipt(
                localPath = target.absolutePath,
                sizeBytes = localSize,
                summaryBytes = summary?.bytes,
                speedText = summary?.speedText,
            ),
        )
    }

    /**
     * 目录单次拉取执行体：spawn → 收割 → 临时目录成形校验 → 覆盖转正。
     * 校验面弱化契约见 [pullDirectory]；.part 目录清理由调用方 finally 兜底。
     */
    private suspend fun executeDirectoryPull(
        devicePath: String,
        partDir: File,
        serial: String,
    ): Result<DeviceDirectoryPullReceipt> {
        partDir.parentFile?.takeIf { !it.exists() }?.mkdirs()
        cleanupPartDirectory(partDir)
        val endpoint = endpointReader()
        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()
        val exitCode = gateway.execute(
            pullCommand(devicePath, partDir.absolutePath, endpoint.executablePath, serial),
            onStarted = { activeHandle = it },
            onLine = { output ->
                if (output.source == ShellOutputSource.STDOUT) stdoutLines += output.line else stderrLines += output.line
            },
        ).getOrElse { e ->
            return Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}"))
        }
        if (exitCode != 0) {
            val reason = when {
                cancelRequested -> "拉取已取消（传输未完成，临时目录已清理）"
                else -> "拉取失败（adb 退出码 $exitCode）：${firstLine(stderrLines, stdoutLines)}——临时目录已清理"
            }
            return Result.failure(IllegalStateException(reason))
        }
        if (!partDir.isDirectory) {
            return Result.failure(
                IllegalStateException("拉取结果异常（本地临时目录未成形，可能设备侧非目录），临时目录已清理"),
            )
        }
        val summary = parsePulledSummary(stdoutLines)
        val target = File(partDir.parent, partDir.nameWithoutExtension)
        promotePartDirectory(partDir, target).getOrElse { e ->
            return Result.failure(IllegalStateException("转正失败（临时目录已清理）：${e.message ?: "未知异常"}"))
        }
        return Result.success(
            DeviceDirectoryPullReceipt(
                localPath = target.absolutePath,
                fileCount = summary?.fileCount,
                totalBytes = summary?.bytes,
                speedText = summary?.speedText,
            ),
        )
    }

    /**
     * 目录覆盖转正（与单文件「终态被本次结果替换」语义一致，非单步原子——
     * 契约与回滚见 [pullDirectory]）：旧终态让位 → 新目录转正 → 让位副本清理；
     * 转正失败时让位副本归位回滚。让位副本名 = 终态名 + [STALE_ASIDE_SUFFIX]，
     * 残留（上次中断）先行清理（残留只可能是旧终态数据，同 .part 自愈口径）。
     */
    private fun promotePartDirectory(partDir: File, target: File): Result<Unit> = runCatching {
        if (target.exists()) {
            val aside = File(target.parentFile, target.name + STALE_ASIDE_SUFFIX)
            cleanupStale(aside)
            Files.move(target.toPath(), aside.toPath())
            try {
                Files.move(partDir.toPath(), target.toPath())
            } catch (e: Throwable) {
                runCatching { Files.move(aside.toPath(), target.toPath()) }
                throw e
            }
            cleanupStale(aside)
        } else {
            Files.move(partDir.toPath(), target.toPath())
        }
    }

    /**
     * 删除 .part 临时目录（递归自底向上；缺席/非目录为无操作；删除失败不掩盖
     * 主回执）。中断残留的 .part 目录是不完整产物，清理防冒充完整。
     */
    private fun cleanupPartDirectory(dir: File) {
        runCatching { dir.walkBottomUp().forEach { it.delete() } }
    }

    /** 删除让位副本（缺席为无操作；best-effort，失败不掩盖主回执/回滚） */
    private fun cleanupStale(aside: File) {
        runCatching { aside.walkBottomUp().forEach { it.delete() } }
    }

    /** 首个非空错误行（stderr 优先，缺席回看 stdout，再缺席兜底）——设备侧人话原文透传 */    private fun firstLine(stderrLines: List<String>, stdoutLines: List<String>): String =
        stderrLines.firstOrNull { it.isNotBlank() }
            ?: stdoutLines.firstOrNull { it.isNotBlank() }
            ?: "无错误输出"

    /** 删除 .part 临时文件（缺席为无操作；删除失败不掩盖主回执） */
    private fun cleanupPart(part: File) {
        runCatching { part.delete() }
    }

    companion object {
        /** .part 临时文件/目录后缀（拉取中间态标记——存在即不完整，绝不冒充终态产物） */
        internal const val PART_SUFFIX = ".part"

        /**
         * 目录覆盖转正的让位副本后缀：旧终态目录转正前让位至此（残留 = 上次
         * 转正中断的旧终态数据，自愈口径同 .part——拉取前先行清理）。
         */
        internal const val STALE_ASIDE_SUFFIX = ".part-old"

        /**
         * 拉取前置 stat 的默认实现（[DeviceFileBrowser] App 级共事实例外的轻量私有
         * 实例）。恒走解引用档（`stat -L`）：adb pull 对 symlink 条目传输**目标**
         * 内容（真机实测：symlink→文件拉回字节数 = `stat -L` 尺寸），期望尺寸
         * 必须取目标真值——普通 stat 的链接路径字节数会让 .part 校验恒失败。
         * 断链 symlink 在解引用档下前置校验即失败，人话回执不静默。
         */
        private suspend fun defaultStatEntry(path: String, serial: String): Result<DeviceFileStat> =
            error("必须由实例注入 statEntry：默认实现需要 runner 与 endpointReader")

        /**
         * adb pull 命令全文（internal 供单测逐字节断言通道纪律）：双路径按本机执行
         * shell 语义包裹（Unix 单引号零展开，内嵌单引号 `'\''` 重开闭转义；Windows
         * cmd.exe 双引号——payload 内单引号安全），再经 [AdbCommandRewriter] 注入
         * adb 路径与 `-s <serial>`。设备路径是 adbd sync 协议参数不经设备 shell
         * 分词，引号只防本机执行 shell 二次分词（[AdbCommandRewriter] 只包可执行
         * token，参数引号是本构造方的责任）。
         */
        internal fun pullCommand(devicePath: String, partPath: String, adbPath: String, serial: String): String {
            val payload = "adb pull ${hostShellQuote(devicePath)} ${hostShellQuote(partPath)}"
            return AdbCommandRewriter.rewrite(payload, adbPath, serial)
        }

        /**
         * adb 完成摘要行解析（internal 供单测断言解析锚）：行尾括号尾巴
         * `... (N bytes in Xs)` 是解析锚（真机实测格式稳定），N 为传输字节数、
         * X 为耗时秒；速度文本取同一行内的 `N MB/s` 形态 token（缺席容忍）。
         * 拉取条目数取同一行 `N file(s) pulled` token（单文件与目录摘要同格式，
         * 目录回执的信息面字段）。多行输出取**末个**命中行（摘要恒为末行）；
         * 全 miss 返回 null（信息面缺席不否定拉取，校验面在大小比对）。
         */
        internal fun parsePulledSummary(lines: List<String>): PullSummary? {
            val tailRegex = Regex("""\((\d+) bytes in ([\d.]+)s\)\s*$""")
            val speedRegex = Regex("""(\d+(?:\.\d+)?\s*[KMGT]?B/s)""")
            val countRegex = Regex("""(\d+) files? pulled""")
            for (line in lines.reversed()) {
                val tail = tailRegex.find(line.trim()) ?: continue
                return PullSummary(
                    bytes = tail.groupValues[1].toLong(),
                    secondsText = tail.groupValues[2],
                    speedText = speedRegex.find(line)?.value,
                    fileCount = countRegex.find(line)?.groupValues?.get(1)?.toLong(),
                )
            }
            return null
        }
    }
}

/** 摘要行解析产物（信息面：不参与校验，仅进完成回执） */
internal data class PullSummary(
    val bytes: Long,
    val secondsText: String,
    val speedText: String?,
    val fileCount: Long? = null,
)
