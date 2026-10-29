package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * 设备文件推送进程句柄（推送取消面）：[AdbPushGateway] 实现在进程启动后经
 * [AdbPushGateway.execute] 的 onStarted 回调交出本句柄，调用方 [kill] 即终止
 * 在途推送。与 进程句柄 分离是测试缝——fake runner 零真子进程即可
 * 模拟「kill 后退出码非零」的取消语义。
 */
fun interface AdbPushHandle {
    /** 请求终止在途推送进程（幂等，可安全多次调用） */
    fun kill()
}

/**
 * 推送执行网关（注入缝）：执行一条 adb push 命令全文，进程启动后回调句柄、
 * 退出前行输出经 onLine 逐行回调，返回真实退出码（进程启动真失败 = Failure）。
 */
fun interface AdbPushGateway {
    suspend fun execute(
        command: String,
        onStarted: (AdbPushHandle) -> Unit,
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
class StreamingAdbPushGateway(private val runner: AdbProcessRunner) : AdbPushGateway {
    override suspend fun execute(
        command: String,
        onStarted: (AdbPushHandle) -> Unit,
        onLine: suspend (ShellOutput) -> Unit,
    ): Result<Int> = runner.executeStreaming(
        command,
        onStarted = { handle -> onStarted(AdbPushHandle { handle.kill() }) },
        onLine = onLine,
    ).map { it.exitCode }
}

/**
 * 推送目标已存在的拒绝回执判别类型：[pushFile] 在 overwrite=false 且设备目标
 * 已存在时以本异常拒绝推送。调用面按本类型出覆盖确认对话（类型判别优于
 * message 字符串匹配——message 是人话呈现面，不是机器判别面）。
 */
class DevicePushTargetExistsException(
    val devicePath: String,
) : IllegalStateException("设备目标已存在：$devicePath——未推送（确认覆盖后重推）")

/**
 * 推送完成回执（成功态）。
 *
 * 契约：[sizeBytes] 是**校验通过**的大小（推送后设备侧 stat 实际值与本地期望值
 * 相等才回执，两者同值故单字段承载）；[summaryBytes]/[speedText] 来自 adb 完成
 * 摘要行（`N file(s) pushed, 0 skipped. X MB/s (N bytes in Ys)`，括号尾巴与拉取
 * 侧摘要同构），格式变化或缺失时为 null（摘要行是信息面不是校验面，缺席不
 * 否定推送）。
 */
data class DeviceFilePushReceipt(
    val devicePath: String,
    val sizeBytes: Long,
    val summaryBytes: Long? = null,
    val speedText: String? = null,
)

/**
 * 设备文件推送内核（设备观测域）：`adb push` 单文件「本地文件 → 设备目录」
 * （设备文件名 = 本地文件名，目标 = `<deviceDir>/<本地名>`）。
 *
 * 通道纪律（与 [DeviceFilePuller]/[DeviceFileBrowser] 同链口径）：命令全文经
 * [pushCommand] 构造，双路径按本机执行 shell 语义引号包裹（Unix 单引号零展开
 * ——路径含空格/`$`/反引号必须字面化），再经 [AdbCommandRewriter] 注入 adb 路径
 * 与 `-s <serial>`；执行走 [AdbPushGateway]（默认流式通道），
 * fake 注入即可单测。
 *
 * 不静默覆盖契约（推送方向的安全语义）：
 * - [pushFile] 默认 overwrite=false：推送前经 [checkTargetExists] 预检设备目标，
 *   已存在（任何类型——同名目录同样拦）即以 [DevicePushTargetExistsException]
 *   拒绝推送、零 adb push spawn——是否覆盖由调用面（用户确认对话）裁决；
 * - overwrite=true 跳过预检直接推送（调用面已拿到用户确认）；
 * - [checkTargetExists] 是独立预检入口（「不存在」的判别 = 设备侧 stat 失败
 *   原文含 `No such file or directory`——toybox stat 恒英文原文，其余失败原样
 *   透传不猜）。
 *
 * 无 .part 语义（adb 协议限制，如实登记）：设备侧无法预写临时名再原子转正，
 * push 直接落最终设备路径——中断（取消/异常退出）会在设备侧留下无标记的部分
 * 文件；取消/失败回执明示「设备侧可能残留部分文件」，残余清理是用户动作而非
 * 本内核动作（与拉取侧的 .part 硬需求互为镜像，差异根源在协议方向）。
 *
 * 校验面：退出码 0 不豁免——推送后对设备目标再 stat 一次，设备侧实际大小必须
 * 等于本地期望大小，不等即失败回执（退出码 0 + 半截写入是校验面兜底的对象）。
 *
 * [pushFile] 单飞行（每实例同时至多一个在途推送，[requestCancel] 只作用于当前
 * 在途进程）；协程被取消时收割进程后放行取消异常。
 */
class DeviceFilePusher(
    private val runner: AdbProcessRunner,
    private val gateway: AdbPushGateway = StreamingAdbPushGateway(runner),
    private val statEntry: suspend (path: String, serial: String) -> Result<DeviceFileStat> =
        Companion::defaultStatEntry,
    private val endpointReader: () -> AdbEndpoint,
) {

    /** 在途推送进程句柄（null = 无在途；仅 pushFile 执行期非空） */
    @Volatile
    private var activeHandle: AdbPushHandle? = null

    /** 取消已请求标记：区分「用户取消」与「设备侧错误」两种非零退出回执 */
    @Volatile
    private var cancelRequested = false

    /**
     * 请求取消当前在途推送（幂等；无在途时为无动作）。取消后 [pushFile] 落
     * 取消人话回执；设备侧可能残留部分文件（无 .part 语义，见类头）。
     */
    fun requestCancel() {
        cancelRequested = true
        activeHandle?.kill()
    }

    /**
     * 设备目标已存在预检（覆盖确认对话的数据源 + 独立只读探测入口）：stat
     * `<deviceDir>/<fileName>`——成功 = 已存在（true）；失败原文含
     * `No such file or directory` = 不存在（false）；其余失败原样透传（不猜）。
     * 空参数零 spawn 显式失败。
     */
    suspend fun checkTargetExists(deviceDir: String, fileName: String, serial: String): Result<Boolean> {
        if (deviceDir.isEmpty() || fileName.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("设备目录、文件名或串号为空，未执行目标预检"))
        }
        return statEntry(joinDeviceTarget(deviceDir, fileName), serial).fold(
            onSuccess = { Result.success(true) },
            onFailure = { e ->
                if ((e.message ?: "").contains(NO_SUCH_FILE_SIGNAL)) {
                    Result.success(false)
                } else {
                    Result.failure(e)
                }
            },
        )
    }

    /**
     * 推送单个本地文件到设备目录（设备文件名 = 本地文件名）。[localPath] 必须是
     * 存在的本地普通文件（推送前校验，不存在零 spawn 拒绝）。
     *
     * overwrite=false（缺省）且设备目标已存在 → 以 [DevicePushTargetExistsException]
     * 拒绝（零 adb push spawn，是否覆盖归调用面裁决）；overwrite=true 直接推送。
     */
    suspend fun pushFile(
        localPath: String,
        deviceDir: String,
        serial: String,
        overwrite: Boolean = false,
    ): Result<DeviceFilePushReceipt> {
        if (localPath.isEmpty() || deviceDir.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("本地路径、设备目标目录或串号为空，未执行推送"))
        }
        val local = File(localPath)
        if (!local.exists() || !local.isFile) {
            return Result.failure(IllegalStateException("本地文件不存在或不是普通文件：$localPath，未执行推送"))
        }
        val deviceTarget = joinDeviceTarget(deviceDir, local.name)
        if (!overwrite) {
            val exists = checkTargetExists(deviceDir, local.name, serial).getOrElse { e ->
                return Result.failure(
                    IllegalStateException("推送前置预检失败（未读取到设备侧目标状态）：${e.message ?: "未知异常"}"),
                )
            }
            if (exists) return Result.failure(DevicePushTargetExistsException(deviceTarget))
        }
        cancelRequested = false
        try {
            return executePush(local, deviceTarget, serial)
        } catch (t: Throwable) {
            activeHandle?.kill()
            if (t is CancellationException) throw t
            return Result.failure(IllegalStateException("推送异常：${t.message ?: "未知异常"}"))
        } finally {
            activeHandle = null
        }
    }

    /**
     * 单次推送执行体（前置校验已过）：spawn → 收割 → 设备侧校验 → 回执。
     * 设备侧无清理面（无 .part 语义），失败路径不再回查设备——回执明示
     * 「可能残留部分文件」交用户处置。
     */
    private suspend fun executePush(
        local: File,
        deviceTarget: String,
        serial: String,
    ): Result<DeviceFilePushReceipt> {
        val endpoint = endpointReader()
        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()
        val exitCode = gateway.execute(
            pushCommand(local.absolutePath, deviceTarget, endpoint.executablePath, serial),
            onStarted = { activeHandle = it },
            onLine = { output ->
                if (output.source == ShellOutputSource.STDOUT) stdoutLines += output.line else stderrLines += output.line
            },
        ).getOrElse { e ->
            return Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}"))
        }
        if (exitCode != 0) {
            val reason = when {
                cancelRequested -> "推送已取消（设备侧可能残留部分文件——adb push 无临时名原子转正协议，无法回滚半截写入）"
                else -> "推送失败（adb 退出码 $exitCode）：${firstLine(stderrLines, stdoutLines)}——设备侧可能残留部分文件"
            }
            return Result.failure(IllegalStateException(reason))
        }
        // 校验面：退出码 0 不豁免，推送后 stat 设备侧真值（与拉取侧大小比对同从严口径）
        val deviceStat = statEntry(deviceTarget, serial).getOrElse { e ->
            return Result.failure(
                IllegalStateException(
                    "推送后校验失败（无法读取设备侧文件大小）：${e.message ?: "未知异常"}——推送结果未确认，请刷新设备目录后人工核对",
                ),
            )
        }
        if (deviceStat.type != DeviceFileType.FILE) {
            return Result.failure(
                IllegalStateException("推送后校验失败（设备侧目标不是普通文件：$deviceTarget）——推送结果未确认，请刷新设备目录后人工核对"),
            )
        }
        if (deviceStat.sizeBytes != local.length()) {
            return Result.failure(
                IllegalStateException(
                    "推送后校验不匹配（设备侧 ${deviceStat.sizeBytes}B，本地 ${local.length()}B）——设备侧可能残留部分文件，请删除设备侧文件后重推",
                ),
            )
        }
        val summary = parsePushedSummary(stdoutLines)
        return Result.success(
            DeviceFilePushReceipt(
                devicePath = deviceTarget,
                sizeBytes = local.length(),
                summaryBytes = summary?.bytes,
                speedText = summary?.speedText,
            ),
        )
    }

    /** 首个非空错误行（stderr 优先，缺席回看 stdout，再缺席兜底）——设备侧人话原文透传 */
    private fun firstLine(stderrLines: List<String>, stdoutLines: List<String>): String =
        stderrLines.firstOrNull { it.isNotBlank() }
            ?: stdoutLines.firstOrNull { it.isNotBlank() }
            ?: "无错误输出"

    companion object {
        /** 「不存在」判别信号：设备侧 stat 失败原文的固定片段（toybox 恒英文） */
        internal const val NO_SUCH_FILE_SIGNAL = "No such file or directory"

        /** 推送前置/后置 stat 的默认实现（[DeviceFileBrowser] App 级共事实例外的轻量私有实例） */
        private suspend fun defaultStatEntry(path: String, serial: String): Result<DeviceFileStat> =
            error("必须由实例注入 statEntry：默认实现需要 runner 与 endpointReader")

        /** 设备目标全路径 = 设备目录去尾斜杠 + `/` + 文件名（POSIX 文件名不含 `/`） */
        internal fun joinDeviceTarget(deviceDir: String, fileName: String): String =
            deviceDir.trimEnd('/') + "/" + fileName

        /**
         * adb push 命令全文（internal 供单测逐字节断言通道纪律）：双路径按本机执行
         * shell 语义包裹（Unix 单引号零展开，内嵌单引号 `'\''` 重开闭转义；Windows
         * cmd.exe 双引号——payload 内单引号安全），再经 [AdbCommandRewriter] 注入
         * adb 路径与 `-s <serial>`。两侧路径是 adb sync 协议参数，引号只防本机执行
         * shell 二次分词（[AdbCommandRewriter] 只包可执行 token，参数引号是本
         * 构造方的责任）。
         */
        internal fun pushCommand(localPath: String, deviceTargetPath: String, adbPath: String, serial: String): String {
            val payload = "adb push ${hostShellQuote(localPath)} ${hostShellQuote(deviceTargetPath)}"
            return AdbCommandRewriter.rewrite(payload, adbPath, serial)
        }

        /**
         * adb 完成摘要行解析（internal 供单测断言解析锚）：括号尾巴
         * `... (N bytes in Xs)` 是解析锚（与拉取侧摘要同构形态——push 的实测形态
         * 未做真机验证，按 pull 同构实现缺席容忍），N 为传输字节数、X 为耗时秒；
         * 速度文本取同一行内的 `N MB/s` 形态 token（缺席容忍）。多行输出取**末个**
         * 命中行；全 miss 返回 null（信息面缺席不否定推送，校验面在设备侧大小比对）。
         */
        internal fun parsePushedSummary(lines: List<String>): PushedSummary? {
            val tailRegex = Regex("""\((\d+) bytes in ([\d.]+)s\)\s*$""")
            val speedRegex = Regex("""(\d+(?:\.\d+)?\s*[KMGT]?B/s)""")
            for (line in lines.reversed()) {
                val tail = tailRegex.find(line.trim()) ?: continue
                return PushedSummary(
                    bytes = tail.groupValues[1].toLong(),
                    secondsText = tail.groupValues[2],
                    speedText = speedRegex.find(line)?.value,
                )
            }
            return null
        }
    }
}

/** 摘要行解析产物（信息面：不参与校验，仅进完成回执） */
internal data class PushedSummary(
    val bytes: Long,
    val secondsText: String,
    val speedText: String?,
)
