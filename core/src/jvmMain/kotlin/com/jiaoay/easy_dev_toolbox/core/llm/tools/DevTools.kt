package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.easy_dev_toolbox.core.system.ArgvOutcome
import com.jiaoay.easy_dev_toolbox.core.system.ArgvProcessRunner
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import com.jiaoay.easy_dev_toolbox.core.system.SysOutputSanitizer
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

/**
 * 只读设备观测工具域：`dev.*`——设备清单与设备日志快照的封闭白名单家族（agent 设备域）。
 *
 * ## 域边界（负面清单真源）
 * dev.* 是只读**观测域**：目录封闭于 [DEV_NAMES] 两件，设备侧的安装/卸载/push/截屏/隐私
 * provider/输入注入/无线配对/root 等面永不入域——负面清单逐条定案见
 * koog 开发域边界定案（四层保险：工具面硬缺席 + 执行面固定模板
 * + prompt 固化 + docs 契约）。加域内条目走提案流程并逐条对照负面清单。
 *
 * ## 形态：固定 argv 模板（LLM 只能填白名单槽，不能造命令）
 * [SnaplogTool] 的命令恒为编译期模板 `adb -s <serial> logcat -d -t <N>`：可执行名来自
 * 设备检测单源的 adb 路径缓存，`-s` 槽走**当前检测列表白名单**（serial 必须在检测单源
 * 最新列表内，不在=拒收人话——防任意 serial 探测），`-t` 行数槽钳制
 * [LINES_MIN]..[LINES_MAX]（缺省 [DEFAULT_LINES]，恒带 `-t`——无 `-t` 形态 dump 整个
 * 缓冲不受钳制，不开放）。LLM 无任何命令串入参，注入面结构性不存在。
 *
 * ## 设备检测单源
 * 设备列表唯一来源 = 设备检测单源（DeviceMonitor 检测链，经 [DeviceSource] 缝注入）——
 * 每次工具调用触发一次新检测（adb devices 毫秒级），白名单校验对象恒是当前列表，
 * 不读陈旧状态。serial 不遮蔽：dev.snaplog 的白名单要求 LLM 回传 dev.devices 报出的
 * 串号（round-trip），且串号非凭据（设备选择 UI 同样明文展示）——遮蔽是零安全增益的断链。
 *
 * ## 快照落盘与围栏登记（核心价值：快照即入既有分析链）
 * 快照输出落 [FileUtils.getDevlogsDir]（应用数据根内，文件名 = 时刻 + 串号；同刻同串号
 * 避让不顶替）并经 [ToolExecutionContext.register] 登记进本任务文件围栏——登记后
 * log.stats / log.cluster / log.view / log.filter_by_tag 全链直接消费。落点钉死数据根：
 * SensitiveFilePolicy 判据 1（数据根整树）恒拒外发，快照本体永不出去；LLM 可指定输出
 * 路径的形态永不引入（那变任意写面）。
 *
 * ## 输出上界（真门禁）与脱敏
 * 回执恒经 [LogStatsDiffTools.capAt] 收口 ≤ [MAX_OUTPUT_CHARS] = 8K 字符；回执预览面恒过
 * [SysOutputSanitizer]（凭据形态行级遮蔽——回灌 LLM 后可经免确认直述面转述外泄，读取-转述
 * 通道的收窄）。快照**文件本体不脱敏**（日志分析底稿，行级遮蔽会篡改证据；文件在数据根整树
 * 拒发射程内，转述外泄面由回执脱敏收窄）。执行捕获上界 [CAPTURE_LIMIT_CHARS]（快照文件
 * 完整性上界，超限如实标记截断）。
 *
 * ## 域准入（独立 devGate 形态）
 * 执行前必过 [DevSessionGate.requireSessionAuthorization]（信任用户名单判定：名单内放行、
 * 名单外进不了域；与 sys.* 共用同一份 `access.trustedUsers`，无独立授权状态与卡面）；
 * **门控面缺席即 DENIED**（fail-closed——裸执行属装配缺陷，工具层兜底拒绝）。
 *
 * ## 装配纪律
 * 工具本体与目录注册即收口三张守护网（围栏覆盖/结构化纪律/目录撞面）；装配门控 =
 * [AgentToolCatalog.ToolContextConfig.devSessionGate] 与 [AgentToolCatalog.ToolContextConfig.devDeviceSource]
 * 双非 null（工厂返 null 不注册）。
 */
object DevTools {

    const val DEVICES_NAME = "dev.devices"

    const val SNAPLOG_NAME = "dev.snaplog"

    /** dev.* 家族名集——消费方 names 选择集单源引用（[SysProbeTools.SYS_NAMES] 先例）；封闭集合，加条目须先过负面清单评审 */
    val DEV_NAMES: Set<String> = setOf(DEVICES_NAME, SNAPLOG_NAME)

    /** 回执长度结构性上界（字符）——真门禁，见对象 KDoc「输出上界」 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /** 行数槽钳制下限（行）：再小没有分析价值 */
    const val LINES_MIN = 10

    /** 行数槽钳制上限（行）：快照完整性上界的语义侧配合（配合执行捕获上界与 8K 回执上界） */
    const val LINES_MAX = 5000

    /** 行数槽缺省值（行）——恒带 -t 的结构性上界 */
    const val DEFAULT_LINES = 1000

    /**
     * 快照执行捕获上界（字符）：快照文件完整性上界（超限如实标记截断）。
     * 5000 行 × 长行（堆栈数百字符）量级的最坏覆盖； ArgvProcessRunner 缺省 256K 对
     * 本量级不够，构造时显式放宽（仅放宽捕获面，回执仍 8K 收口）。
     */
    const val CAPTURE_LIMIT_CHARS = 2_000_000

    // ── 槽校验（非法值拒收人话：回执列允许集，LLM 可自行调整） ──

    /**
     * serial 槽：当前检测列表白名单。白名单的意义是**防任意 serial 探测**
     * （LLM 只能回传检测单源刚报出的串号，不能编造或探测未接入的设备），非防注入
     * （argv 纯语义下槽值只进数组元素，注入面结构上不存在）。
     */
    internal fun checkSerial(raw: String, devices: List<DeviceInfo>): SysProbeTools.SlotCheck {
        val serial = raw.trim()
        return if (devices.any { it.serial == serial }) {
            SysProbeTools.SlotCheck.Accepted(serial)
        } else {
            SysProbeTools.SlotCheck.Rejected(
                if (devices.isEmpty()) {
                    "当前未检测到任何已连接设备——serial「$serial」无处校验；请确认设备已连接 USB 调试后再试"
                } else {
                    "serial「$serial」不在当前检测到的设备列表内（当前列表：" +
                        devices.joinToString("、") { it.serial } + "）——" +
                        "本工具只接受检测单源最新报出的串号（防任意 serial 探测），先用 dev.devices 列设备再取串号"
                },
            )
        }
    }

    /** 行数槽：整数钳制 [LINES_MIN]..[LINES_MAX]（越界钳到边界，非整数拒收人话） */
    internal fun checkLines(raw: Int): SysProbeTools.SlotCheck =
        SysProbeTools.SlotCheck.Accepted(raw.coerceIn(LINES_MIN, LINES_MAX).toString())

    /**
     * 固定 argv 模板：`adb -s <serial> logcat -d -t <lines>`。
     * 可执行名取设备检测单源的 adb 路径缓存（空 = 系统 PATH 的 adb）；恒带 `-d`（dump 后退出）
     * 与 `-t`（行数上界，无 -t 形态不受钳制不开放）。
     */
    internal fun buildArgv(adbExecutablePath: String, serial: String, lines: Int): List<String> =
        listOf(adbExecutablePath.trim().ifEmpty { "adb" }, "-s", serial, "logcat", "-d", "-t", lines.toString())

    // ── 设备源缝（检测单源注入缝；测试注入 fake，生产装配接设备检测单源） ──

    /**
     * 一次设备检测的快照：检测列表 + adb 可执行路径缓存 + 检测失败信息。
     * [detectError] 非 null = 检测链路失败（如 adb 缺席）——与「检测成功但无设备」二分，
     * 前者白名单无处校验须拒收，后者是正常空答案。
     */
    data class DeviceSnapshot(
        val devices: List<DeviceInfo>,
        val adbExecutablePath: String,
        val detectError: String?,
    )

    /**
     * 设备源缝：每次调用触发一次新检测（白名单校验对象恒是当前列表，不读陈旧状态）。
     * 生产装配接设备检测单源（DeviceMonitor 检测链）；测试注入 fake 免真子进程。
     */
    fun interface DeviceSource {
        suspend fun snapshot(): DeviceSnapshot
    }

    // ── 域准入面（独立 devGate 形态；与 SysSessionGate 同一信任名单判据，无独立授权档） ──

    /**
     * dev.* 域准入面（设备域装配门控的工具侧契约）。
     *
     * 与 [SysSessionGate] 的关系：同一信任名单判据（按消息发送者 openId），名单内三域同时
     * 放行、名单外同时拒绝——不存在按域的独立授权档；接口独立仅为工具侧依赖收口（sys 的
     * 族根登记缝是主机文件系统形态，dev 不读主机路径）。实现契约见 KoogDevSessionGate
     * （桥接链装配侧）。
     */
    interface DevSessionGate {

        /**
         * 要求发送者通过 dev.* 域准入（名单内即时返回 true）。
         *
         * @param toolName 触发工具名（审计归因用）
         * @return true = 发送者在信任名单内；false = 不在名单
         */
        suspend fun requireSessionAuthorization(toolName: String): Boolean

        /** 触发者标识（发送者 openId——审计 trigger 字段单源；测试形态可 null） */
        val trigger: String?
    }

    // ── 执行缝（测试注入 fake；生产恒走 ArgvProcessRunner） ──

    /**
     * 执行缝：固定模板 argv → 回执（单测注入 fake 断言 argv 组装与回执形态）。
     * 失败契约与 [ArgvProcessRunner.run] 同款（异常而非 Result 返回——suspend 函数返回
     * kotlin.Result 会被协程完成包装机制二次包箱，运行期 ClassCastException）：
     *
     * @throws IllegalArgumentException 执行面策略拒绝
     * @throws IOException 可执行文件不存在/启动失败
     * @throws kotlinx.coroutines.CancellationException 协程取消原样上抛
     */
    fun interface SnaplogExecutor {
        suspend fun run(argv: List<String>): ArgvOutcome

        companion object {
            /**
             * 生产执行件：argv 纯语义原语，捕获上界放宽到 [CAPTURE_LIMIT_CHARS]
             * （快照文件完整性上界，见对象 KDoc）；总超时维持原语缺省 20s（logcat -d 秒级完成）。
             */
            val DEFAULT: SnaplogExecutor = SnaplogExecutor { argv ->
                ArgvProcessRunner(captureLimitChars = CAPTURE_LIMIT_CHARS).run(argv)
            }
        }
    }

    // ── 工具：dev.devices ──

    /** 只读设备清单工具：检测单源列表 → 串号+型号回执（serial 原样回显，round-trip 契约） */
    class DeviceListTool(
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val source: DeviceSource,
        private val audit: SysAuditLog = SysAuditLog(),
        private val trigger: String? = null,
        /** 会话级门控面（设备域装配门控；null = 门控面缺席 ⇒ 恒 DENIED，fail-closed） */
        private val devGate: DevSessionGate? = null,
    ) : StructuredSimpleTool<DeviceListTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = DEVICES_NAME,
        description = "List currently connected Android devices (READ-ONLY). Returns each device's " +
            "serial and model from a fresh detection run. Pass the serial VERBATIM as the serial " +
            "argument of dev.snaplog (it is validated against this list). Zero devices is a normal " +
            "answer — tell the user to connect a device with USB debugging enabled.",
    ) {

        @Serializable
        class Args

        /** 审计包装体：执行结果 + 审计动作面 + 裁决覆写（会话授权拒绝等非执行裁决） */
        private data class Listed(
            val result: ToolResult,
            val action: String,
            val verdictOverride: String? = null,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val listed = executeListed()
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = DEVICES_NAME,
                    action = listed.action,
                    verdict = listed.verdictOverride ?: sysAuditVerdict(listed.result),
                    exitCode = null,
                    trigger = trigger,
                    outputSummary = sysAuditSummary(listed.result),
                ),
            )
            return listed.result
        }

        private suspend fun executeListed(): Listed {
            val gate = devGate ?: return Listed(
                ToolResult.Err(
                    ToolResult.ErrCode.DENIED,
                    "dev.* 设备观测未装配会话门控面（fail-closed 拒绝）——本链不支持设备读取。",
                ),
                action = "dev.devices（门控面缺席）",
                verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
            )
            if (!gate.requireSessionAuthorization(DEVICES_NAME)) {
                return Listed(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本会话未获 dev.* 设备读取授权（用户未批准或确认超时）——请如实告知用户" +
                            "需先由用户在桥接设置页把其 openId 加入「信任用户」，禁止编造设备列表。",
                    ),
                    action = "dev.devices（会话授权拒绝）",
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val snapshot = source.snapshot()
            snapshot.detectError?.let { error ->
                return Listed(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "设备检测失败：$error——请先在应用设备栏确认 adb 配置后再试（禁止编造设备列表）",
                    ),
                    action = "dev.devices（检测失败）",
                    verdictOverride = SysAuditLog.VERDICT_FAILED,
                )
            }
            return Listed(renderDevices(snapshot.devices), action = "adb devices -l")
        }

        private fun renderDevices(devices: List<DeviceInfo>): ToolResult {
            val body = buildString {
                if (devices.isEmpty()) {
                    appendLine("devices=0（当前无已连接设备——检测成功但列表为空，这是正常答案不是错误）")
                    append("提示用户连接设备并开启 USB 调试后再试。")
                } else {
                    appendLine("devices=${devices.size}")
                    devices.forEach { device ->
                        appendLine("- serial=${device.serial}  model=${device.model.ifEmpty { "（无型号信息）" }}")
                    }
                    append("把上面 serial 的值原样作为 dev.snaplog 的 serial 参数（白名单按此列表校验）。")
                }
            }
            val sanitized = SysOutputSanitizer.sanitize(body)
            val capped = capAt(sanitized)
            return ToolResult.Ok(
                summary = capped,
                data = ToolPayload.DeviceListPayload(
                    deviceCount = devices.size,
                    truncated = capped != sanitized,
                ),
            )
        }

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)
    }

    // ── 工具：dev.snaplog ──

    /**
     * 设备日志快照工具：固定 argv 模板抓取 logcat 缓冲 → 落数据根 devlogs 并登记进
     * 文件围栏（登记后 log.* 分析链直接消费）。risk=DESTRUCTIVE（写产物面——落点钉死
     * 数据根非 LLM 可指定；读取面由信任用户域准入收窄）。
     */
    class SnaplogTool(
        /** 快照产物登记落点（与 log.* 分析链共用的围栏 context——登记后分析链可直接读） */
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val source: DeviceSource,
        private val executor: SnaplogExecutor = SnaplogExecutor.DEFAULT,
        private val audit: SysAuditLog = SysAuditLog(),
        private val trigger: String? = null,
        /** 会话级门控面（null = 门控面缺席 ⇒ 恒 DENIED，fail-closed） */
        private val devGate: DevSessionGate? = null,
        /** 快照目录（生产 = FileUtils.getDevlogsDir()；测试注入临时目录真读写） */
        private val snapshotDir: java.io.File = FileUtils.getDevlogsDir(),
    ) : StructuredSimpleTool<SnaplogTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = SNAPLOG_NAME,
        description = "Capture a READ-ONLY logcat snapshot from a connected Android device into a " +
            "local file and register it for the log analysis tools (log.stats / log.cluster / " +
            "log.view / log.filter_by_tag). Fixed command template adb -s <serial> logcat -d -t <N>; " +
            "you can only supply a serial from the current detected device list (run dev.devices " +
            "first) and optional lines 10..5000 (default 1000, clamped). The snapshot file never " +
            "leaves this machine — report analysis conclusions, not raw dumps.",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "Device serial, VERBATIM from dev.devices output (validated against the current " +
                    "detection list — invented serials are rejected).",
            )
            val serial: String,
            @property:LLMDescription(
                "Snapshot line count, 10..5000, default 1000. Out-of-range values are clamped. " +
                    "Start with the default and raise it only when the target window is older.",
            )
            val lines: Int? = null,
        )

        /** 审计包装体：执行结果 + 审计动作面（argv 全文；未成 argv 的拒收记槽形态）+ 退出码 + 裁决覆写 */
        private data class Snapped(
            val result: ToolResult,
            val action: String,
            val exitCode: Int?,
            val verdictOverride: String? = null,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val snapped = executeSnapped(args)
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = SNAPLOG_NAME,
                    action = snapped.action,
                    verdict = snapped.verdictOverride ?: sysAuditVerdict(snapped.result),
                    exitCode = snapped.exitCode,
                    trigger = trigger,
                    outputSummary = sysAuditSummary(snapped.result),
                ),
            )
            return snapped.result
        }

        private suspend fun executeSnapped(args: Args): Snapped {
            // 会话级总授权前置：门控面缺席 = 装配缺陷 fail-closed；拒绝/超时如实回 DENIED
            val gate = devGate ?: return Snapped(
                ToolResult.Err(
                    ToolResult.ErrCode.DENIED,
                    "dev.* 设备观测未装配会话门控面（fail-closed 拒绝）——本链不支持设备读取。",
                ),
                action = "serial=${args.serial}（门控面缺席）",
                exitCode = null,
                verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
            )
            if (!gate.requireSessionAuthorization(SNAPLOG_NAME)) {
                return Snapped(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本会话未获 dev.* 设备读取授权（用户未批准或确认超时）——请如实告知用户" +
                            "需先由用户在桥接设置页把其 openId 加入「信任用户」，禁止编造快照内容。",
                    ),
                    action = "serial=${args.serial}（会话授权拒绝）",
                    exitCode = null,
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val snapshot = source.snapshot()
            snapshot.detectError?.let { error ->
                return Snapped(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "设备检测失败：$error——serial 白名单无法校验，请先在应用设备栏确认 adb 配置后再试",
                    ),
                    action = "serial=${args.serial}（检测失败）",
                    exitCode = null,
                    verdictOverride = SysAuditLog.VERDICT_FAILED,
                )
            }
            val serial = when (val check = checkSerial(args.serial, snapshot.devices)) {
                is SysProbeTools.SlotCheck.Accepted -> check.value
                is SysProbeTools.SlotCheck.Rejected -> return Snapped(
                    ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, check.reason),
                    action = "serial=${args.serial}（白名单拒收）",
                    exitCode = null,
                )
            }
            val lines = when (val check = checkLines(args.lines ?: DEFAULT_LINES)) {
                is SysProbeTools.SlotCheck.Accepted -> check.value
                is SysProbeTools.SlotCheck.Rejected -> return Snapped(
                    ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, check.reason),
                    action = "serial=$serial（槽校验拒收）",
                    exitCode = null,
                )
            }
            val argv = buildArgv(snapshot.adbExecutablePath, serial, lines.toInt())
            val action = argv.joinToString(" ")
            // 失败契约见 SnaplogExecutor：只翻译声明的异常（CE 穿透；其余异常上抛 = 契约破口，不吞）
            val outcome = try {
                executor.run(argv)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // 执行面策略拒绝（黑名单等）：固定模板结构上不会触发，此分支是防线兜底
                return Snapped(
                    ToolResult.Err(ToolResult.ErrCode.DENIED, e.message ?: "argv 被执行面策略拒绝"),
                    action = action,
                    exitCode = null,
                )
            } catch (e: IOException) {
                return Snapped(
                    ToolResult.Err(
                        ToolResult.ErrCode.NOT_FOUND,
                        "adb 启动失败（本机未安装或路径不可执行）：${e.message ?: e.javaClass.simpleName}",
                        hint = "可在应用设置页配置 adb 路径后重试；如实告知用户，禁止编造快照内容",
                    ),
                    action = action,
                    exitCode = null,
                )
            }
            if (outcome.timedOut) {
                return Snapped(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "logcat 快照超时未完成，已被终止（dump 应在秒级完成）",
                        hint = "可用更小行数重试，或如实告知用户设备日志响应异常",
                    ),
                    action = action,
                    exitCode = null,
                )
            }
            return Snapped(writeSnapshotAndRender(serial, argv, outcome), action = action, exitCode = outcome.exitCode)
        }

        /**
         * 快照落盘（数据根 devlogs，同名避让不顶替）→ 围栏登记 → 渲染回执。
         * 文件本体不脱敏（分析底稿保真，见对象 KDoc）；写失败如实报 FAILED（不静默丢快照）。
         */
        private suspend fun writeSnapshotAndRender(serial: String, argv: List<String>, outcome: ArgvOutcome): ToolResult {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                snapshotDir.mkdirs()
                allocateFile(serial).apply { writeText(outcome.output) }
            }
            context.register(file)
            return renderReceipt(serial, argv, outcome, file)
        }

        /** 产物文件名：时刻 + 串号（文件名安全字符投影）；同刻同串号追加序号避让，不顶替既有快照 */
        private fun allocateFile(serial: String): java.io.File {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val safeSerial = buildString {
                for (c in serial) append(if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_')
            }
            var candidate = java.io.File(snapshotDir, "snaplog-$stamp-$safeSerial.log")
            var index = 1
            while (candidate.exists() && index < 100) {
                candidate = java.io.File(snapshotDir, "snaplog-$stamp-$safeSerial-$index.log")
                index++
            }
            return candidate
        }

        /** 结构化回执：serial/argv/exit + 快照路径 + 围栏登记提示 + 输出预览（恒脱敏 + 8K capAt） */
        private fun renderReceipt(serial: String, argv: List<String>, outcome: ArgvOutcome, file: java.io.File): ToolResult {
            val previewSource = SysOutputSanitizer.sanitize(outcome.output).ifBlank { "（无输出）" }
            val preview = capAt(previewSource)
            val truncated = outcome.outputTruncated || preview != previewSource
            val body = buildString {
                appendLine("serial=$serial")
                appendLine("argv: ${argv.joinToString(" ")}")
                appendLine("exit=${outcome.exitCode}")
                appendLine("snapshot: ${file.absolutePath}（${outcome.output.length} 字符，已登记进文件围栏）")
                if (outcome.outputTruncated) {
                    appendLine("（执行捕获达到上界，快照文件只含前段——需要更早日志时改用更小行数分段抓取）")
                }
                appendLine("下一步：用 log.stats / log.cluster / log.view / log.filter_by_tag 直接分析快照（传上面的 snapshot 路径）")
                appendLine("--- output preview ---")
                append(preview)
                if (outcome.exitCode != 0) {
                    appendLine()
                    appendLine(
                        "（退出码 ${outcome.exitCode} 非零——快照可能不完整，如实告知用户，不要基于残缺快照下确定性结论）",
                    )
                }
            }
            val cappedBody = capAt(body)
            return ToolResult.Ok(
                summary = cappedBody.trim(),
                artifacts = listOf(
                    ArtifactRef(path = file.absolutePath, kind = ArtifactKind.FILE, sizeBytes = file.length()),
                ),
                data = ToolPayload.SnaplogPayload(
                    serial = serial,
                    exitCode = outcome.exitCode,
                    snapshotPath = file.absolutePath,
                    truncated = truncated || cappedBody != body,
                ),
            )
        }

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `dev.devices` 目录描述符。uiHint = null：纯 agent 工具（观测链自用），不进命令面板。
     * 装配门控：devSessionGate + devDeviceSource 双非 null 才注册（工厂返 null，fail-closed）。
     */
    val DEV_DEVICES: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = DEVICES_NAME,
        description = "List currently connected Android devices (read-only; serial + model from a " +
            "fresh detection run; serials are the whitelist for dev.snaplog)",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        factory = { _, sink, config ->
            val gate = config.devSessionGate
            val source = config.devDeviceSource
            if (gate != null && source != null) {
                DeviceListTool(sink, source = source, trigger = gate.trigger, devGate = gate)
            } else {
                null
            }
        },
    )

    /**
     * `dev.snaplog` 目录描述符。uiHint = null；risk=DESTRUCTIVE（写产物面——落点钉死数据根
     * devlogs 目录，非 LLM 可指定）。装配门控同 dev.devices（双非 null 才注册，fail-closed）。
     */
    val DEV_SNAPLOG: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = SNAPLOG_NAME,
        description = "Capture a read-only logcat snapshot from a detected device into the local " +
            "devlogs directory and register it for log analysis (fixed argv template; serial " +
            "whitelist against the current detection list; lines clamped 10..5000)",
        risk = AgentToolCatalog.Risk.DESTRUCTIVE,
        factory = { context, sink, config ->
            val gate = config.devSessionGate
            val source = config.devDeviceSource
            if (gate != null && source != null) {
                SnaplogTool(
                    context = context,
                    sink = sink,
                    source = source,
                    trigger = gate.trigger,
                    devGate = gate,
                )
            } else {
                null
            }
        },
    )
}
