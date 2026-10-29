package com.jiaoay.easy_dev_toolbox.page.logcat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.DesktopUtils
import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.PatternSyntaxException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 抓取前置检查的可操作提示：message + 建议动作（面板据此渲染对应入口钮；
 * null = 无前置问题）。无设备 / adb 路径失效时**不静默**——开始按钮照常可点，
 * 点击后落提示态指路（与设备状态栏「去设置配置 adb 路径」同文案风格）。
 */
data class LogcatHint(
    val message: String,
    val action: Action?,
) {
    enum class Action { REFRESH_DEVICES, SELECT_DEVICE, GO_SETTINGS }
}

/**
 * 武装捕获常见特征预设（单条 = 面板展示名 + 特征正则）。
 *
 * 通道纪律：预设特征只服务**武装通道**（[LogcatCaptureEngine.arm] 的原始行
 * 正则——对整行日志做 containsMatchIn，命中即标记锚点收网）；渲染层的崩溃
 * 判定（[isCrashLine] 的堆栈帧行形态）是另一条**渲染通道**，两语义两通道
 * 不合流——预设不得参与渲染折叠/高亮判定，渲染判定也不得借预设特征。
 *
 * 条目保守起步四条；命中精度以真机日志反馈为准增补（真机端到端验收归维护者）。
 */
internal data class ArmFeaturePreset(val label: String, val pattern: String)

/**
 * logcat 实时抓取面板的 ViewModel：过滤参数 / 运行态转发 / 前置提示态；另有武装
 * 捕获（特征正则埋伏、命中自动标记锚点）的挂撤转发与命中回执——引擎契约见
 * [LogcatCaptureEngine.arm]。
 *
 * 设备与 adb 路径**复用 :core 设备域既有检测态**（[DeviceStateSource] 三流——
 * App 级单源 [com.jiaoay.toolbelt.device.DeviceMonitor] 的检测与
 * 5s 轮询），本 VM 不再自起检测链路；抓取引擎复用 App 级单实例
 * [LogcatRuntime.engine]（切页不中断，退出序随 Runtime 收口）。
 *
 * 过滤参数（查询串与级别档）经 [LogcatConfigStore] 持久化：init 恢复上次值、
 * 变更 debounce 落盘（级别档 null ↔ 空串 name 双向还原；查询串载荷从裸词串泛化
 * 为查询串——旧值是合法新值子集，零迁移）。查询串经 [LogcatQueryParser] 编译为
 * 统一过滤模型（无算子裸串按旧语义回退）；解析失败拒收——引擎保持上一有效过滤，
 * 错误态经 [queryError] 即时发布 + 去抖 toast 回执。**只记忆参数不恢复
 * 行为**——抓取开关态/运行态不持久化，重启后恒 Idle。
 */
class LogcatViewModel(
    private val devices: DeviceStateSource,
    private val engine: LogcatCaptureEngine = LogcatRuntime.engine,
    private val adbPathReader: suspend () -> String = { DeviceHub.currentEndpoint().executablePath },
) : ViewModel() {

    private val _tagQuery = MutableStateFlow("")
    val tagQuery: StateFlow<String> = _tagQuery.asStateFlow()

    private val _minLevel = MutableStateFlow<LogcatLevel?>(null)
    val minLevel: StateFlow<LogcatLevel?> = _minLevel.asStateFlow()

    /** 查询解析错误态（null = 当前输入是有效过滤）：面板输入框的即时回执载荷 */
    private val _queryError = MutableStateFlow<String?>(null)
    val queryError: StateFlow<String?> = _queryError.asStateFlow()

    /**
     * 上一有效过滤模型：解析失败拒收时保持的谓词——[startCapture] 定型与运行中
     * 换过滤（[applyFilterInputs]）共用同一单源（两处 matcher 构造点收敛为
     * [buildMatcher]）。初值 = 无过滤；init 恢复值解析成功后覆盖。
     */
    private var lastValidSpec: LogcatFilterSpec = LogcatFilterSpec()

    init {
        viewModelScope.launch {
            _tagQuery.value = LogcatConfigStore.getLogcatTagQuery()
            _minLevel.value = restoreMinLevel(LogcatConfigStore.getLogcatMinLevelName())
            // 启动期静默编译（不弹回执）：恢复值解析失败保持无过滤默认，错误态照实发布
            when (val parsed = LogcatQueryParser.parse(_tagQuery.value, _minLevel.value)) {
                is LogcatQueryParseResult.Ok -> lastValidSpec = parsed.spec
                is LogcatQueryParseResult.Err -> _queryError.value = parsed.reason
            }
        }
    }

    /**
     * 过滤参数持久化通道：Pair 汇聚 tag 串与级别 name，一次 debounce 落两 key
     * （tag 逐键输入不逐键重写 preferences_pb）。drop(1) 弃初始重放值——启动期
     * 不发写，存量持久值只被 init 恢复读消费、不被空默认覆盖。
     */
    private val filterPersistChanges = MutableStateFlow("" to "")

    init {
        viewModelScope.launch {
            filterPersistChanges
                .drop(1)
                .debounce(FILTER_PERSIST_DEBOUNCE_MS.milliseconds)
                .collect { (tag, levelName) ->
                    LogcatConfigStore.setLogcatTagQuery(tag)
                    LogcatConfigStore.setLogcatMinLevelName(levelName)
                }
        }
    }

    private val _hint = MutableStateFlow<LogcatHint?>(null)
    val hint: StateFlow<LogcatHint?> = _hint.asStateFlow()

    val status: StateFlow<LogcatCaptureStatus> = engine.status
    val window: StateFlow<LogcatWindow> = engine.window

    /** 标记锚点（null = 本轮抓取未标记）：终态「查看」的初始定位载荷——面板点击时取值透传查看器 */
    val anchor: StateFlow<LogcatAnchor?> = engine.anchor

    /** 武装等待态（引擎单源转发）：true = 特征已挂等待命中；命中收网/解除/终态均回落 false */
    val armed: StateFlow<Boolean> = engine.armed

    /**
     * 特征正则输入（武装捕获用）：会话级不持久化——埋伏是当次盯屏动作，重启后恒空
     * （与过滤参数持久化的取舍相反：过滤是每次抓取都复用的口径，特征是临场设的圈套）。
     */
    private val _armPattern = MutableStateFlow("")
    val armPattern: StateFlow<String> = _armPattern.asStateFlow()

    /**
     * 武装延迟自动停止档（秒，预设档见 [ARM_DELAY_PRESET_SECONDS]）：武装时刻起
     * 倒计时到点自动停止抓取（时间盒埋伏）。会话级不持久化——与 [_armPattern] 同
     * 口径：埋伏是临场动作，重启回默认 0（不延迟，武装纯等待）。
     */
    private val _armDelaySeconds = MutableStateFlow(0)
    val armDelaySeconds: StateFlow<Int> = _armDelaySeconds.asStateFlow()

    /** 延迟档切换（面板预设档选择；0 = 不延迟） */
    fun onArmDelayChange(seconds: Int) {
        _armDelaySeconds.value = seconds
    }

    /**
     * 武装命中回执订阅：引擎事件 →「特征已命中」toast（挂撤与锚点契约见
     * [LogcatCaptureEngine.arm]）。锚点为 null = 命中行无行首时间戳且窗内无可锚定
     * 行，回执须明示「未能自动标记」——可见回执不得报喜不报忧。
     */
    init {
        viewModelScope.launch {
            engine.armedHit.collect { hit ->
                StatusSignals.show(
                    when (val hitAnchor = hit.anchor) {
                        null -> "特征已命中（命中行无时间戳，未能自动标记）"
                        else -> "特征已命中，标记 @ ${hitAnchor.clockText}"
                    },
                )
            }
        }
    }

    fun onTagQueryChange(query: String) {
        _tagQuery.value = query
        queueFilterPersist()
        applyFilterInputs()
    }

    fun onMinLevelChange(level: LogcatLevel?) {
        _minLevel.value = level
        queueFilterPersist()
        applyFilterInputs()
    }

    /** 特征正则输入变更（仅更新输入态；挂防在 [armCapture] 显式动作时发生） */
    fun onArmPatternChange(pattern: String) {
        _armPattern.value = pattern
    }

    private fun queueFilterPersist() {
        filterPersistChanges.value = _tagQuery.value to (_minLevel.value?.name ?: "")
    }

    /**
     * 级别档还原：name 非法或空串（= null，无级别过滤）均回落 null——
     * 持久层损坏不炸恢复链路，宁丢过滤条件不丢抓取。
     */
    private fun restoreMinLevel(name: String): LogcatLevel? =
        LogcatLevel.entries.firstOrNull { it.name == name }

    /**
     * 过滤输入编译（查询串 + 级别 chips → [LogcatQueryParser.parse]，合成规则见其
     * KDoc——chips 与查询按顶层 AND）：成功即更新 [lastValidSpec] 并在抓取进行中
     * 原地换引擎 matcher（回溯语义见 [LogcatCaptureEngine.updateMatcher]——只影响
     * 后续行，原地收窄免停止重抓丢流）；失败即拒收——不更新 [lastValidSpec]、
     * 不触引擎，上一有效过滤继续生效，错误态即时发布给面板 + 去抖 toast 回执。
     * 非运行态引擎拒绝换 matcher（无副作用——过滤由下次 [startCapture] 按当时
     * [lastValidSpec] 定型）。
     */
    private fun applyFilterInputs() {
        when (val parsed = LogcatQueryParser.parse(_tagQuery.value, _minLevel.value)) {
            is LogcatQueryParseResult.Ok -> {
                _queryError.value = null
                lastValidSpec = parsed.spec
                if (engine.updateMatcher(buildMatcher())) maybeShowFilterReceipt("过滤已更新，只影响后续行")
            }
            is LogcatQueryParseResult.Err -> {
                _queryError.value = parsed.reason
                maybeShowFilterReceipt("查询无效（${parsed.reason}）——已保持上一有效过滤")
            }
        }
    }

    /** 两处 matcher 构造点收敛的单源编译：start 定型与运行中换过滤共用 [lastValidSpec] */
    private fun buildMatcher(): LogcatLineMatcher = LogcatLineMatcher(lastValidSpec)

    /** 回执去抖锚：上一条过滤回执的发出时刻（0 = 从未发出；成功与解析失败共用同一窗） */
    private var lastFilterReceiptAtMs = 0L

    /**
     * 过滤回执（带文案参数：成功走「只影响后续行」配方，解析失败走「保持上一有效
     * 过滤」拒收回执）。去抖——[FILTER_RECEIPT_SUPPRESS_MS] 窗口内不重复弹，
     * 查询逐键输入不逐键堆栈。
     */
    private fun maybeShowFilterReceipt(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastFilterReceiptAtMs < FILTER_RECEIPT_SUPPRESS_MS) return
        lastFilterReceiptAtMs = now
        StatusSignals.show(message)
    }

    /** 清除提示态（前置问题被用户自行解决后手动收起） */
    fun dismissHint() {
        _hint.value = null
    }

    /** 停止当前抓取（无抓取在跑时无副作用）；产物路径随终态在面板回显 */
    fun stopCapture() {
        _hint.value = null
        engine.stop()
    }

    /**
     * 清屏：只清实时滚动显示窗，不清落盘（落盘文件、全量计数、过滤语义零触碰——
     * 契约见 [LogcatCaptureEngine.clearDisplay]）。非 Running 态引擎拒绝（清屏钮与
     * 停止钮同款运行态门控，非运行态不渲染；转发为防御兜底）。
     */
    fun clearDisplay() {
        engine.clearDisplay()
    }

    /**
     * 标记复现点（转发）：把屏上最新带时间戳行记为查看锚点（承载契约见
     * [LogcatCaptureEngine.markAnchor]），停止后「查看」带锚点直达标记处。
     * 非 Running 态引擎拒绝（标记钮与清屏钮同款运行态门控，非运行态不渲染；
     * 转发为防御兜底）；窗内无可锚定行回执人话拒绝——可见按钮不得静默死点。
     */
    fun markAnchor() {
        if (engine.status.value !is LogcatCaptureStatus.Running) return
        when (val marked = engine.markAnchor()) {
            null -> StatusSignals.show("暂无可标记的带时间戳行（清屏后等新日志到达再标记）")
            else -> StatusSignals.show("已标记 ${marked.clockText}，停止后点「查看」直达标记处")
        }
    }

    /**
     * 武装捕获（转发）：特征正则先编译校验——无效正则与空白输入回执人话指路不静默
     * （可见按钮不得静默死点；空正则恒命中任何行，武装即秒收网，必是误操作）。
     * 非 Running 态防御兜底零动静（武装钮运行态才渲染，同标记/清屏转发口径）。
     * 校验通过后挂防进引擎（携带当前延迟档——到点自动停止语义见
     * [LogcatCaptureEngine.arm]）；命中语义与锚点契约同见 [LogcatCaptureEngine.arm]。
     */
    fun armCapture() {
        if (engine.status.value !is LogcatCaptureStatus.Running) return
        val pattern = _armPattern.value
        if (pattern.isBlank()) {
            StatusSignals.show("特征正则为空——先输入特征再武装")
            return
        }
        val regex = try {
            Regex(pattern)
        } catch (e: PatternSyntaxException) {
            StatusSignals.show("特征正则无效（${e.description ?: "语法错误"}）——请修正后再武装")
            return
        }
        engine.arm(regex, _armDelaySeconds.value)
    }

    /** 解除武装（转发）：引擎幂等，未武装 / 非运行态均零副作用 */
    fun disarmCapture() {
        engine.disarm()
    }

    /**
     * 开始抓取：前置检查不通过时落 [hint] 提示态并返回（不启动）；
     * 通过后按当前 adb 路径 + 目标串号重写 `adb logcat` 启动。
     */
    fun startCapture() {
        val precheck = precheckHint()
        if (precheck != null) {
            _hint.value = precheck
            return
        }
        _hint.value = null
        viewModelScope.launch(Dispatchers.IO) {
            // 每次启动重读 adb 路径（设置页变更后回到本页即生效，与设备检测同口径）
            val adbPath = adbPathReader()
            val command = AdbCommandRewriter.rewrite(
                "adb logcat",
                adbPath,
                resolveTargetSerial(),
            )
            val started = engine.start(
                command = command,
                outputFile = resolveOutputFile(),
                matcher = buildMatcher(),
            )
            if (!started) {
                StatusSignals.show("已有抓取在进行中")
            }
        }
    }

    /**
     * 前置检查（纯同步读 :core 设备域三流；null = 可启动）：
     * - 检测失败（adb 路径缺失/无效等）→ 去设置入口；
     * - 无设备 → 刷新设备入口；
     * - 多设备未选定 → 选择设备入口（抓取必须钉死目标设备，不选定会让
     *   `adb logcat` 在多台设备上报错，提示先行而非静默失败）。
     */
    internal fun precheckHint(): LogcatHint? {
        val detectError = devices.detectError.value
        if (detectError != null) {
            return LogcatHint("$detectError——请检查设置页 ADB 路径", LogcatHint.Action.GO_SETTINGS)
        }
        return when (val device = devices.deviceState.value) {
            is DeviceState.None ->
                LogcatHint("未检测到设备——连接设备后将自动检测", LogcatHint.Action.GO_SETTINGS)

            is DeviceState.Multi ->
                if (devices.selectedSerial.value == null) {
                    LogcatHint("检测到多台设备，请先在上方设备栏选择目标设备", LogcatHint.Action.SELECT_DEVICE)
                } else {
                    null
                }

            is DeviceState.Single -> null
        }
    }

    /** 目标串号解析：Multi 用会话级选择；Single 注入其串号；None 已被前置检查拦截（null 兜底） */
    private fun resolveTargetSerial(): String? = when (val device = devices.deviceState.value) {
        is DeviceState.Single -> device.serial
        is DeviceState.Multi -> devices.selectedSerial.value
        is DeviceState.None -> null
    }

    /** 打开产物所在文件夹（文件管理器），供拖拽进 Tag 过滤/解密等下游工具 */
    fun openOutputFolder() {
        val file = (engine.status.value as? LogcatCaptureStatus.Stopped)?.outputFile
            ?: (engine.status.value as? LogcatCaptureStatus.Running)?.outputFile
            ?: return
        DesktopUtils.openInFolder(file.absolutePath)
    }

    /**
     * 抓取产物「送往 Tag 过滤」（管线衔接）：目标查 [TaskPipelineHandoff] 单张表，
     * 产物路径经同一 :core 槽投递进命令面板参数直填态。
     * @return false = 终态无产物或产物无映射（衔接条目渲染已门控产物存在态，
     *         false 为防御性兜底——不动作、不残留载荷）
     */
    fun sendToNextTool(): Boolean {
        val file = (engine.status.value as? LogcatCaptureStatus.Stopped)?.outputFile
            ?: return false
        val target = TaskPipelineHandoff.nextToolTargetOf(
            TaskPipelineHandoff.SourcePageId.LOGCAT_CAPTURE,
        ) ?: return false
        return TaskPipelineHandoff.offer(target, file.absolutePath)
    }

    /**
     * 抓取产物「送工作台分析」（深链直投）：产物路径经 [DeepAnalyzeHook] 中立槽
     * 投递——宿主接线后跳工作台并预填产物。与 [sendToNextTool] 不同链路：工作台
     * 入口不是带参命令（不经命令面板参数直填态），深链槽直投。
     *
     * 问题时间点载荷 = [engine.anchor] 派生（[LogcatAnchor.fullTimestamp]——
     * 标记过则工作台自动聚焦复现时刻）；未标记（锚点 null）送 null——工作台
     * 不触碰时间域（用户手填值原样保留，现行为零回归）。时间点不进 prefillNote
     * （症状域是用户必填可编辑域，不得被机器载荷占用）。
     *
     * @return false = 终态无产物或宿主未接线（不动作、零残留）
     */
    fun sendToWorkbench(): Boolean {
        val file = (engine.status.value as? LogcatCaptureStatus.Stopped)?.outputFile
            ?: return false
        return DeepAnalyzeHook.offer(
            listOf(file.absolutePath),
            "logcat 抓取产物深度分析",
            prefillTargetTime = engine.anchor.value?.fullTimestamp,
        )
    }

    /**
     * 抓取产物「送飞书」：Stopped 终态产物文件经 :core [ReportDelivery] 中立槽
     * 直发桥接接收会话（工作台报告/统计导出/diff 导出同款先例——产物人工搬运
     * 断点的收口）。
     *
     * - 产物来源 = [LogcatCaptureStatus.Stopped] 终态产物（句柄已关、文件完整）；
     *   Running 中途产物不送（终态衔接钮行仅 Stopped 态组合，本守卫只兜底）；
     *   Stopped 且产物为 null（零命中产物已回收）或非 Stopped 态静默 no-op；
     * - [ReportDelivery.Channel.deliver] 的 null = 已送出**成功**语义——不可 elvis
     *   兜底（会把成功吞成失败），先收 reason 再判空分叉回执；
     * - 失败人话由通道归一（未接线/未运行/未配置会话/出站拒绝），此处只做回执
     *   toast——置灰判据在面板（真源 = ReportDelivery.ready 单源）。
     */
    fun sendOutputToLark() {
        val file = (engine.status.value as? LogcatCaptureStatus.Stopped)?.outputFile ?: return
        viewModelScope.launch {
            val reason = ReportDelivery.deliver(file)
            if (reason == null) {
                StatusSignals.show("已送出至飞书会话：${file.name}")
            } else {
                StatusSignals.show(reason)
            }
        }
    }

    companion object {
        /**
         * 产物目录：`~/Documents/Toolbelt/logcat/`——用户主目录下可见的工具产物目录
         * （非 `~/.toolbelt` 内部数据目录：产物要可见可拖拽进下游工具，
         * 内部目录藏路径会断掉「抓取 → 过滤/解密」工具链）。
         * 父目录不存在则创建；创建失败时产物无处可写，引擎落失败终态。
         * 路径真源 = [com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getLogcatExportDir]。
         */
        internal fun outputDir(): File =
            com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getLogcatExportDir()

        /**
         * 过滤变更回执去抖窗（ms）：1_500——与复制反馈驻留
         * [com.jiaoay.easy_dev_toolbox.core.theme.TimingConstants.COPY_FEEDBACK_MS]
         * 同档的短反馈间隔。internal 供单测锚。
         */
        internal const val FILTER_RECEIPT_SUPPRESS_MS = 1_500L

        /**
         * 武装延迟自动停止预设档（秒）：0 = 不延迟（默认——武装纯等待语义零回归）。
         * 单一真源供面板预设选择渲染；档位保守（最短 30 秒起——过短倒计时与
         * 手动停止无差，长档 5 分钟覆盖「布好雷区走人」的复现等待窗口）。
         */
        internal val ARM_DELAY_PRESET_SECONDS = listOf(0, 30, 60, 300)

        /**
         * 武装捕获常见特征预设（单一真源供面板预设行渲染）。预设点击**只填入**
         * [onArmPatternChange] 的输入态、可改后再武装——预填不自动执行，武装仍是
         * 用户显式动作。条目语义与通道纪律见 [ArmFeaturePreset]。
         */
        internal val ARM_FEATURE_PRESETS: List<ArmFeaturePreset> = listOf(
            ArmFeaturePreset(label = "Java 崩溃", pattern = "FATAL EXCEPTION"),
            ArmFeaturePreset(label = "ANR", pattern = "ANR in "),
            ArmFeaturePreset(label = "native 崩溃", pattern = "SIGSEGV|Fatal signal"),
            ArmFeaturePreset(label = "应用被杀", pattern = "am_kill|Force stopping"),
        )

        /**
         * 过滤参数持久化 debounce 窗（ms）：400——对齐 feature-logkit
         * LogTagFilterViewModel tagFieldChanges 通道的 trailing debounce 档。
         * internal 供单测锚。
         */
        internal const val FILTER_PERSIST_DEBOUNCE_MS = 400L

        /**
         * 产物文件名：`logcat-<yyyyMMdd-HHmmss>.txt`（同秒冲突时 `-N` 后缀避让）。
         * internal 供单测锚。
         */
        internal fun resolveOutputFile(dir: File = outputDir(), millis: Long = System.currentTimeMillis()): File {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(millis))
            var candidate = File(dir, "logcat-$stamp.txt")
            var suffix = 1
            while (candidate.exists()) {
                candidate = File(dir, "logcat-$stamp-$suffix.txt")
                suffix++
            }
            return candidate
        }
    }
}
