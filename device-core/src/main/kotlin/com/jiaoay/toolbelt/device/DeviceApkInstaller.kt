package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform
import kotlinx.coroutines.CancellationException
import java.io.File

// OPEN(真机门禁): 真机 adb install 回显形态（Success/Failure 标记行、老 adb 版本
// Failure 仍退出 0）与差集升档链（pm list packages 前后差集 + dumpsys package
// versionName）端到端验证归维护者——本层 fixture 按 adb 文档化契约实现，真机校准
// 后如形态不符显式失败不静默。

/**
 * 设备 APK 安装进程句柄（安装取消面）：[AdbInstallGateway] 实现在进程启动后经
 * onStarted 回调交出本句柄，调用方 [kill] 即终止在途安装。与 进程句柄
 * 分离是测试缝——fake runner 零真子进程即可断言「协程取消后在途进程被收割」。
 */
fun interface AdbInstallHandle {
    /** 请求终止在途安装进程（幂等，可安全多次调用） */
    fun kill()
}

/**
 * 安装执行网关（注入缝）：执行一条 adb install 命令全文，进程启动后回调句柄、
 * 退出前行输出经 onLine 逐行回调，返回真实退出码（进程启动真失败 = Failure）。
 * 与 [AdbPushGateway] 同构——同一流式通道配方，fake 注入即可单测。
 */
fun interface AdbInstallGateway {
    suspend fun execute(
        command: String,
        onStarted: (AdbInstallHandle) -> Unit,
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
class StreamingAdbInstallGateway(private val runner: AdbProcessRunner) : AdbInstallGateway {
    override suspend fun execute(
        command: String,
        onStarted: (AdbInstallHandle) -> Unit,
        onLine: suspend (ShellOutput) -> Unit,
    ): Result<Int> = runner.executeStreaming(
        command,
        onStarted = { handle -> onStarted(AdbInstallHandle { handle.kill() }) },
        onLine = onLine,
    ).map { it.exitCode }
}

/**
 * APK 安装完成回执（成功态）。
 *
 * 追溯档位契约（装了什么必须可追溯，升档尽力而为不造数据）：
 * - 基线档（恒在场）：[localFileName] 本地文件名 + [sizeBytes] 本地大小 +
 *   [adbOutput] adb 回显原文（Success 标记行及其前后行，不翻译不截断）；
 * - 升档档（尽力而为，缺席如实注明不猜）：[packageName]/[versionName] 来自
 *   安装前后包列表差集 + 差集包的 dumpsys 详情——升级覆盖形态（`-r` 装已有包，
 *   差集为空）或列举/详情失败时为 null，[identityNote] 如实注明缺席原因
 *   （如「升级覆盖，包名未识别」）；**禁止从文件名猜包名**。
 */
data class DeviceApkInstallReceipt(
    val localFileName: String,
    val sizeBytes: Long,
    val adbOutput: String,
    val packageName: String? = null,
    val versionName: String? = null,
    val identityNote: String? = null,
)

/**
 * 设备 APK 安装内核（设备观测域）：`adb install -r <本地APK>` 单文件「本地 APK →
 * 设备侧同名升级覆盖安装」。`-r` 旗标恒在（升级覆盖已有包；全新包同样可装）。
 *
 * 通道纪律（与 [DeviceFilePusher]/[DevicePackageBrowser] 同链口径）：命令全文经
 * [installCommand] 构造——本地路径按本机执行 shell 语义引号包裹（Unix 单引号零
 * 展开，路径含空格/`$`/反引号必须字面化），再经 [AdbCommandRewriter] 注入 adb
 * 路径与 `-s <serial>`；安装执行走 [AdbInstallGateway]（默认流式通道），追溯升档的包列举/详情走 [DevicePackageBrowser]（既有命令
 * 面，fake 注入即可单测零真 adb）。
 *
 * 结果解析契约（回显标记行优先于退出码）：
 * - adb install 成功恒回显 `Success` 行，失败恒回显 `Failure [原因]` 行；老 adb
 *   版本失败仍退出 0，故**标记行优先**——`Failure` 行在场即失败回执（原因原文
 *   透传），`Success` 行在场即成功回执；
 * - 标记行缺席时按退出码兜底：非零 = 失败；零 = 成功且回显原文原样进回执
 *   （兜底向「已安装」侧倾斜——安装已实际发生而回执失败，比回执成功更危险：
 *   用户会误信设备未被改动而带着被改的基线机继续排障）。
 *
 * 取消收割契约：[installApk] 执行期协程被取消（窗关/停投 scope 取消）时先
 * [AdbInstallHandle.kill] 收割在途 adb 进程再放行取消异常——pm 会话式安装未
 * 提交即失败，被杀安装不会半装落机；已确认的安装不得脱离监督继续执行。
 *
 * 本地路径不存在/非普通文件/空参数零 spawn 显式拒绝（与推送侧同口径）。
 */
class DeviceApkInstaller(
    private val runner: AdbProcessRunner,
    private val gateway: AdbInstallGateway = StreamingAdbInstallGateway(runner),
    private val packageBrowser: DevicePackageBrowser,
    private val endpointReader: () -> AdbEndpoint,
) {

    /** 在途安装进程句柄（null = 无在途；仅 installApk 执行期非空） */
    @Volatile
    private var activeHandle: AdbInstallHandle? = null

    /**
     * 安装单个本地 APK 到设备（`-r` 升级覆盖）。[localPath] 必须是存在的本地
     * 普通文件（安装前校验，不存在零 spawn 拒绝）。失败回执 message 面向用户，
     * adb 回显原文透传。
     */
    suspend fun installApk(localPath: String, serial: String): Result<DeviceApkInstallReceipt> {
        if (localPath.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("本地 APK 路径或设备串号为空，未执行安装"))
        }
        val local = File(localPath)
        if (!local.exists() || !local.isFile) {
            return Result.failure(IllegalStateException("本地 APK 不存在或不是普通文件：$localPath，未执行安装"))
        }
        try {
            // 追溯基线的「安装前」包名集（升档尽力而为面：失败即降档，不阻断安装）
            val beforeNames = installedPackageNames(serial)
            return executeInstall(local, serial, beforeNames)
        } catch (t: Throwable) {
            activeHandle?.kill()
            if (t is CancellationException) throw t
            return Result.failure(IllegalStateException("安装异常：${t.message ?: "未知异常"}"))
        } finally {
            activeHandle = null
        }
    }

    /**
     * 单次安装执行体（前置校验已过）：spawn → 收割 → 标记行解析 → 追溯升档 →
     * 回执。失败路径不回查设备（安装失败即无副作用；取消收割见类头契约）。
     */
    private suspend fun executeInstall(
        local: File,
        serial: String,
        beforeNames: Set<String>?,
    ): Result<DeviceApkInstallReceipt> {
        val endpoint = endpointReader()
        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()
        val exitCode = gateway.execute(
            installCommand(local.absolutePath, endpoint.executablePath, serial),
            onStarted = { activeHandle = it },
            onLine = { output ->
                if (output.source == ShellOutputSource.STDOUT) stdoutLines += output.line else stderrLines += output.line
            },
        ).getOrElse { e ->
            return Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}"))
        }
        val echoText = echoText(stdoutLines, stderrLines)
        val marker = parseInstallEcho(stdoutLines) ?: parseInstallEcho(stderrLines)
        val installed = marker == SUCCESS_MARKER || (marker == null && exitCode == 0)
        if (!installed) {
            val reason = marker
                ?: "adb 退出码 $exitCode：${firstLine(stderrLines, stdoutLines)}"
            return Result.failure(IllegalStateException("安装失败：$reason（adb 回显原文：$echoText）"))
        }
        // 追溯升档（尽力而为面）：安装后包名集差集 → 新装包名 → dumpsys versionName
        val afterNames = installedPackageNames(serial)
        val identity = resolveIdentity(beforeNames, afterNames, serial)
        return Result.success(
            DeviceApkInstallReceipt(
                localFileName = local.name,
                sizeBytes = local.length(),
                adbOutput = echoText,
                packageName = identity.packageName,
                versionName = identity.versionName,
                identityNote = identity.note,
            ),
        )
    }

    /**
     * 设备侧已装包名集（追溯差集的数据源）：[DevicePackageBrowser.listPackages]
     * 全部包视角。返回 null = 列举失败（升档降档处理，不阻断安装主流程）。
     */
    private suspend fun installedPackageNames(serial: String): Set<String>? =
        packageBrowser.listPackages(serial).fold(
            onSuccess = { list -> list.map { it.packageName }.toSet() },
            onFailure = { null },
        )

    /**
     * 追溯升档收口（尽力而为不造数据——禁止从文件名猜包名）：
     * - 前后任一侧列举失败 → 包名缺席，[ApkInstallIdentity.note] 如实注明；
     * - 差集为空（`-r` 升级覆盖已有包形态）→ 注明「升级覆盖，包名未识别」；
     * - 差集恰一新包 → 该包名实证可采，再拉 dumpsys 详情取 versionName（详情
     *   失败时包名保留、版本名缺席注明原因）；
     * - 差集多于一（一次安装引入多个新包的异常形态）→ 不猜不采，注明缺席。
     */
    private suspend fun resolveIdentity(
        beforeNames: Set<String>?,
        afterNames: Set<String>?,
        serial: String,
    ): ApkInstallIdentity {
        if (beforeNames == null) {
            return ApkInstallIdentity(note = "包名未识别（安装前包列举失败）")
        }
        if (afterNames == null) {
            return ApkInstallIdentity(note = "包名未识别（安装后包列举失败）")
        }
        val newPackages = (afterNames - beforeNames).sorted()
        if (newPackages.isEmpty()) {
            return ApkInstallIdentity(note = "升级覆盖，包名未识别")
        }
        if (newPackages.size > 1) {
            return ApkInstallIdentity(note = "包名未识别（一次安装引入 ${newPackages.size} 个新包，无法唯一判别）")
        }
        val packageName = newPackages.single()
        return packageBrowser.packageDetails(packageName, serial).fold(
            onSuccess = { details ->
                ApkInstallIdentity(
                    packageName = packageName,
                    versionName = details.versionName,
                    note = if (details.versionName == null) "versionName 未设置（设备侧 versionName=null 字面量）" else null,
                )
            },
            onFailure = { e ->
                ApkInstallIdentity(
                    packageName = packageName,
                    note = "版本名读取失败：${e.message ?: "未知异常"}",
                )
            },
        )
    }

    /** adb 回显原文（stdout 全量，stdout 空白时回看 stderr；信息面不截断不翻译） */
    private fun echoText(stdoutLines: List<String>, stderrLines: List<String>): String =
        stdoutLines.joinToString("\n").trim().ifEmpty {
            stderrLines.joinToString("\n").trim().ifEmpty { "无回显输出" }
        }

    /** 首个非空错误行（stderr 优先，缺席回看 stdout，再缺席兜底） */
    private fun firstLine(stderrLines: List<String>, stdoutLines: List<String>): String =
        stderrLines.firstOrNull { it.isNotBlank() }
            ?: stdoutLines.firstOrNull { it.isNotBlank() }
            ?: "无错误输出"

    companion object {
        /** 成功标记行（adb install 成功恒回显整行 `Success`） */
        internal const val SUCCESS_MARKER = "Success"

        /** 失败标记行前缀（`Failure [原因]` 或 `Failure` 后跟自由文本） */
        internal const val FAILURE_MARKER = "Failure"

        /**
         * adb install 回显标记行提取（internal 供单测断言解析锚）：逐行扫描取首个
         * `Success` 整行或 `Failure `/`Failure[` 词边界行（adb 契约形态 =
         * `Failure [原因]` 或 `Failure 原因`；裸 `Failure` 词干后跟其他字词
         * ——如 `Failure-ish`——不是标记行，防止无关输出误判成败；pty `\r` 与
         * 行尾空白剥除）。无标记行返回 null——成败判定由调用方按退出码兜底
         * （见类头结果解析契约）。标记行原文原样返回不翻译（原因是回执面）。
         */
        internal fun parseInstallEcho(lines: List<String>): String? {
            for (raw in lines) {
                val line = raw.trim()
                if (line == SUCCESS_MARKER) return line
                if (line == FAILURE_MARKER || line.startsWith("$FAILURE_MARKER ") || line.startsWith("$FAILURE_MARKER[")) {
                    return line
                }
            }
            return null
        }

        /**
         * adb install 命令全文（internal 供单测逐字节断言通道纪律）：`adb install
         * -r <本地路径>` 本地路径按本机执行 shell 语义包裹（Unix 单引号零展开，
         * 内嵌单引号 `'\''` 重开闭转义；Windows cmd.exe 双引号），再经
         * [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`。本地路径是 adb 传输
         * 参数，引号只防本机执行 shell 二次分词（与 [DeviceFilePusher.pushCommand]
         * 同配方）。
         */
        internal fun installCommand(localPath: String, adbPath: String, serial: String): String {
            val payload = "adb install -r ${hostShellQuote(localPath)}"
            return AdbCommandRewriter.rewrite(payload, adbPath, serial)
        }
    }
}

/** 追溯升档解析产物（[DeviceApkInstaller] 内部收口形态，回执字段面） */
private data class ApkInstallIdentity(
    val packageName: String? = null,
    val versionName: String? = null,
    val note: String? = null,
)
