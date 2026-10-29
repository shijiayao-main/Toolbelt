package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 设备负载波采样器（多会话制调度层）：解析纯函数在 [PerfParsers]，本类只做
 * 调度 / 门控 / 状态发布。
 *
 * 采样通道决策（与设备检测轮询的关系）：
 * - **通道合流、调度不合流**——命令执行走设备域既有 [AdbProcessRunner] 接缝
 *   （宿主机注入的执行通道 + [AdbCommandRewriter] 注入 `-s <serial>`），
 *   不新造 gateway 类型；但采样环不并入 [DeviceMonitor] 的 5s 轮询环——检测环
 *   答「设备在不在」，采样环答「设备负载如何」，且 CPU 差值需要基线 + 窗口
 *   两次读取，节奏语义不同。每拍都是一次性 exec（无常驻进程），会话不活跃/
 *   页面不可见期间零 spawn。
 * - CPU 占用 = 同一拍内两次 `cat /proc/stat` 差值（窗口 [cpuWindowMs] 参数化），
 *   每拍自含基线，会话随时起停不依赖跨拍状态。FPS 同款差值形态：flips 计数在
 *   基线拍与组合拍各读一次（SurfaceFlinger dump 体积大，是采样期固定成本——
 *   仅页面可见的活跃会话期发生），差值 / 两采样点实际间隔 = 全屏合成帧率。
 * - 六域一次组合 exec 采齐（段标记框架契约在 [PerfParsers.COMBINED_PAYLOAD]），
 *   常态每拍 2 次 exec（差值基线 + 组合）；电池域主链 `dumpsys battery` 失败时
 *   追加 `dumpsys power` 回退 exec——常电设备（无电池服务）多花一次。
 *
 * 多会话表与竞态收口（每 serial 一会话，收口纪律与投屏引擎 stateLock/会话句柄包/
 * 世代闸配方同源平移，不另造形态）：
 * - [sessions] 会话表 keyed by serial；[startSamplingToTargets] 对目标集逐 serial
 *   各起一个独立会话（各自采样环 job + 各自环形历史）。选独立环而非单环合拍
 *   轮询：整拍连续失败收会话是 per-device 语义（一台 USB 抖动只收自己，合拍环
 *   会把该语义耦合进环内子状态机）；起停/换选是 per-serial 生命周期，独立环
 *   天然表达「停 A 不停 B」；每拍每台 2 次 exec 的 spawn 总量与环形态无关，
 *   合拍的调度对齐收益（exec 时长不等，相位本就漂移）不抵生命周期耦合代价；
 * - **stateLock 单一串行收口**：会话表的全部 check-then-act（起会话幂等检查、
 *   停止移除、换选迁移、会话收束落态、世代分配）与对外状态发布都在
 *   [stateLock] 下原子完成——并发起停不可能互踩表项；
 * - **世代闸（迟到样本不复活）**：每个会话在 [stateLock] 下分配单调递增世代号
 *   （[SessionContext.generation]），采样环的每拍写入（[commitSample]/
 *   [recordTickError]/[endSession]）先在锁内核对「表内该 serial 的会话仍是本
 *   会话」才落——用户停止/换选迁移后被替换或移除的旧会话，其迟到的在途拍
 *   不得写入历史、不得更新任何会话态；
 * - **句柄包纪律**：会话的 job/环形历史/失败计数全部封在 [SessionContext] 内
 *   （旧会话收尾只触达自己的句柄包，不碰表内其它会话的任何资源——旧会话的
 *   收尾不存在误杀新会话的通道）。
 *
 * 目标集语义（与按键注入器选定+勾选先例同口径）：
 * - [startSampling]（默认路径）目标 = 当前选定设备（未选定时单设备回退，解析在
 *   [resolveTargetSerial]），保守默认只采一台；会话跟随选定态——换选时旧会话
 *   收束、按新目标起新会话（曲线历史按 serial 各自续存，不跨设备混线）；
 * - [startSamplingToTargets]（批量路径）目标集由调用方显式给出，本方法不做任何
 *   隐式扩集；已在采的 serial 幂等跳过；
 * - 会话中目标设备消失：默认路径收会话（[PerfSessionState.Ended] 带人话
 *   reason），批量路径按在场检查同样只收该 serial 的会话，其余会话不受影响。
 *
 * 会话与门控语义（其余沿用单会话制既有契约）：
 * - [startSampling] 时无可用目标设备（无设备 / 检测失败 / 多设备未选定）不启动，
 *   保持 Idle——「为什么不能开」由消费面经 [DeviceStateSource] 三流自判（同
 *   logcat 面板 hint 配方），采样器不复制该文案；
 * - 域级解析失败（[PerfField.error]）不断会话；整拍失败（exec 异常/非零退出码）
 *   连续 [MAX_CONSECUTIVE_FAILURES] 次才收会话（瞬时 USB 抖动容差）；
 * - 页面可见性门控沿用 [DeviceMonitor.setPageVisible] 计数归并语义：不可见期间
 *   该拍静默跳过（零 spawn），会话保持、回页即恢复。
 *
 * [applySample] 是宿主/测试注入缝（环追加 + 最近一拍发布，不经世代闸——注入缝
 * 语义是宿主显式预置状态，永不触碰会话表）；采样环自身写入走世代闸内的
 * [commitSample]。scope 由持有方注入（App 形态从 AppHost.scope 派生，测试注入
 * 测试 scope 用 runTest 虚拟时钟驱动 [delay]——采样间隔与 CPU 窗口全参数化）。
 */
class PerfSampler(
    private val scope: CoroutineScope,
    private val devices: DeviceStateSource,
    private val runner: AdbProcessRunner,
    private val endpointReader: () -> AdbEndpoint,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
    private val cpuWindowMs: Long = DEFAULT_CPU_WINDOW_MS,
    initialPageVisible: Boolean = false,
    private val log: AdbLog = AdbLog.NONE,
) {

    private val historyCapacity = ((HISTORY_WINDOW_MS + sampleIntervalMs - 1) / sampleIntervalMs)
        .toInt().coerceAtLeast(2)

    private val _state = MutableStateFlow(PerfSamplerState())
    val state: StateFlow<PerfSamplerState> = _state.asStateFlow()

    /**
     * 页面可见性门控（计数归并语义与 [DeviceMonitor] 同配方）：
     * true = 消费页进入组合（+1）、false = 退出组合（−1，下限 0）；计数归零
     * 才停采。默认 false——无页面接线期间与「页面未打开」同频（零 spawn）。
     */
    private val pageVisibleCount = AtomicInteger(if (initialPageVisible) 1 else 0)
    private val pageVisible = MutableStateFlow(initialPageVisible)

    /**
     * 会话表 / 环形历史 / 最近一拍 / 整拍错误的单一串行收口：上列全部字段的
     * 读写（含 check-then-act 与对外状态发布）都在本锁下完成；锁内不做任何
     * suspend（采样 exec 在环协程内、锁外执行）。
     */
    private val stateLock = Any()

    /** 会话世代号分配源（[stateLock] 下读改写；防旧会话迟到写入复活） */
    private val generationSource = AtomicLong(0)

    /** 会话表（keyed by serial；只含 Active/Ended 会话，Idle = 不在表） */
    private val sessions = LinkedHashMap<String, SessionContext>()

    /**
     * 各 serial 环形历史（会话结束/停止后保留——滚动窗跨会话续存）；
     * [DEFAULT_RING_KEY] 为无会话期单参注入缝的兜底环（adb 串号非空，不冲突）。
     */
    private val rings = HashMap<String, PerfHistory>()

    private val latestBySerial = HashMap<String, DevicePerfSample>()

    private val tickErrors = HashMap<String, String>()

    /** 主 serial（投影锚；无会话期常为 [DEFAULT_RING_KEY]） */
    private var primarySerial: String = DEFAULT_RING_KEY

    /**
     * 会话句柄包：一个采样会话一份（世代 + 环形历史 + 会话局部状态），会话
     * 收尾只触达本包——不存在旧会话收尾误杀新会话资源的通道（同源句柄包纪律
     * 见 [sessions] 头注释）。[consecutiveFailures] 只由本会话协程读写。
     */
    private inner class SessionContext(
        val serial: String,
        val generation: Long,
        val ring: PerfHistory,
        val followsSelection: Boolean,
    ) {
        @Volatile
        var job: Job? = null

        /** null = Active；非 null = Ended 人话 reason（表内留存承载收束横幅） */
        var ended: String? = null

        var consecutiveFailures = 0
    }

    // ── 会话控制 ──

    /**
     * 开始采样（默认路径）：目标 = 当前选定设备（未选定时单设备回退），无可用
     * 目标保持 Idle 不启动；目标 serial 已有活跃会话则幂等（Ended 残留则替换
     * 起新会话）。会话跟随选定态（换选迁移语义见类头）。
     */
    fun startSampling() {
        val target = resolveTargetSerial() ?: return
        synchronized(stateLock) {
            val existing = sessions[target]
            if (existing != null && existing.ended == null) return
            startSessionLocked(target, followsSelection = true)
            republishLocked()
        }
    }

    /**
     * 开始批量采样（显式目标集路径）：目标集由调用方显式给出（面板多选勾选
     * 集），本方法不做隐式扩集；空集零 spawn 不启动；已在采的 serial 幂等跳过
     * （Ended 残留则替换）。批量会话不跟随选定态（目标固定，设备消失时只收
     * 自己的会话）。
     */
    fun startSamplingToTargets(serials: List<String>) {
        if (serials.isEmpty()) return
        synchronized(stateLock) {
            val fresh = serials.distinct().filter { serial ->
                val existing = sessions[serial]
                existing == null || existing.ended != null
            }
            fresh.forEach { serial -> startSessionLocked(serial, followsSelection = false) }
            if (fresh.isNotEmpty()) republishLocked()
        }
    }

    /**
     * 停止采样（用户入口）：[serial] = null 停全部会话，非 null 只停该 serial 的
     * 会话（其余会话不受影响）。活跃会话收束回 Idle（移出会话表，曲线历史与
     * 最近一拍保留）；Ended 会话保持 Ended（收束原因横幅不被吞）。幂等。
     */
    fun stopSampling(serial: String? = null) {
        val jobs = mutableListOf<Job>()
        var changed = false
        synchronized(stateLock) {
            val keys: List<String> = serial?.let { listOf(it) } ?: sessions.keys.toList()
            keys.forEach { key ->
                val ctx = sessions[key] ?: return@forEach
                if (ctx.ended == null) {
                    sessions.remove(key)
                    changed = true
                }
                ctx.job?.let { jobs += it }
            }
            if (changed) republishLocked()
        }
        // job 收割在锁外（cancel 不触达表内其它会话的任何资源——只收本会话句柄）
        jobs.forEach { it.cancel() }
    }

    /**
     * 页面可见性门控接线（计数语义见 [pageVisible] 头注释）：true = 消费页进入
     * 组合、false = 退出组合。
     */
    fun setPageVisible(visible: Boolean) {
        val next = if (visible) {
            pageVisibleCount.incrementAndGet()
        } else {
            pageVisibleCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        pageVisible.value = next > 0
    }

    /**
     * 注入缝（单参形态，会话表无活跃会话时写主 serial 的兜底环）：环追加 +
     * 最近一拍发布，不清会话表（注入样本永不改变会话态）。单会话消费面的
     * 预置路径，多会话预置走 [applySample] keyed 形态。
     */
    fun applySample(sample: DevicePerfSample) {
        val serial = synchronized(stateLock) {
            sessions.entries.firstOrNull { it.value.ended == null }?.key ?: primarySerial
        }
        applySample(serial, sample)
    }

    /**
     * 注入缝（keyed 形态）：向 [serial] 的环形历史追加一拍并发布最近一拍，不经
     * 世代闸（注入缝语义是宿主显式预置，永不触碰会话表——见 [commitSample] 的
     * 对照闸语义）。同 [DeviceMonitor.applyDeviceList] 注入缝语义——宿主/测试
     * 预置「有曲线」状态免真采样。
     */
    fun applySample(serial: String, sample: DevicePerfSample) {
        synchronized(stateLock) {
            ringForLocked(serial).append(historyPointOf(sample))
            latestBySerial[serial] = sample
            tickErrors.remove(serial)
            republishLocked()
        }
    }

    /** 清空全部曲线历史（不清各 serial 最近一拍读数；采样中调用则曲线从此重新生长） */
    fun clearHistory() {
        synchronized(stateLock) {
            rings.values.forEach { it.clear() }
            republishLocked()
        }
    }

    // ── 会话执行内核 ──

    /** 起会话（调用方持 [stateLock]）：分配世代 + 建句柄包 + 起采样环，全部在锁内原子完成；同 serial 的整拍错误残留随新会话清零 */
    private fun startSessionLocked(serial: String, followsSelection: Boolean) {
        val ctx = SessionContext(
            serial = serial,
            generation = generationSource.incrementAndGet(),
            ring = ringForLocked(serial),
            followsSelection = followsSelection,
        )
        sessions[serial] = ctx
        primarySerial = serial
        tickErrors.remove(serial)
        log.debug(TAG) { "perf session start: serial=$serial generation=${ctx.generation} followSelection=$followsSelection" }
        ctx.job = scope.launch { runSession(ctx) }
    }

    /**
     * 单会话采样环（每会话一个独立环，语义见类头「多会话表与竞态收口」）。
     * 整拍异常沿用 catch Exception 全吞形态（取消异常也走 [recordTickError] 的
     * 世代闸——闸丢弃停止/被替换后的写入，环再经 isActive 退出）。
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.runSession(ctx: SessionContext) {
        var target = ctx.serial
        while (isActive) {
            if (pageVisible.value) {
                if (ctx.followsSelection) {
                    val current = resolveTargetSerial()
                    when {
                        current == null -> {
                            endSession(ctx, "目标设备已断开或未选定")
                            return
                        }

                        current != target -> {
                            migrateSession(ctx, current)
                            return
                        }
                    }
                    target = current
                } else if (!serialPresent(ctx.serial)) {
                    endSession(ctx, "目标设备已断开")
                    return
                }
                try {
                    val sample = sampleOnce(target, endpointReader().executablePath)
                    commitSample(ctx, sample)
                    ctx.consecutiveFailures = 0
                    log.debug(TAG) {
                        "Perf sample @${sample.timestampMs}: serial=${ctx.serial} cpu=${sample.cpuPercent.value}, " +
                            "mem=${sample.memory.value?.usedPercent}, fps=${sample.fps.value}"
                    }
                } catch (e: Exception) {
                    ctx.consecutiveFailures++
                    recordTickError(ctx, "采样失败：${e.message ?: "未知异常"}")
                    if (ctx.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        endSession(ctx, "连续 ${ctx.consecutiveFailures} 次采样失败：${e.message ?: "未知异常"}")
                        return
                    }
                }
            }
            delay(sampleIntervalMs)
        }
    }

    /**
     * 换选迁移（默认路径会话专用）：旧会话自移出表，按新目标另起新会话；新目标
     * 已有活跃会话时旧会话只让位（不顶替、不重复起环）。锁内核对表项身份后才
     * 迁移——本会话已被停止/替换时不做任何表操作。
     */
    private fun migrateSession(ctx: SessionContext, newTarget: String) {
        synchronized(stateLock) {
            if (sessions[ctx.serial] !== ctx) return
            sessions.remove(ctx.serial)
            primarySerial = newTarget
            val existing = sessions[newTarget]
            if (existing == null || existing.ended != null) {
                startSessionLocked(newTarget, followsSelection = true)
            }
            republishLocked()
        }
    }

    /**
     * 采样环写入（世代闸）：锁内核对「表内该 serial 的会话仍是本会话」才落——
     * 用户停止/迁移替换后的旧会话，其迟到的在途拍整笔丢弃（不写环、不发态，
     * 会话不复活）。与注入缝 [applySample] 的对照：注入缝永不被闸（宿主显式
     * 预置），本闸只拦采样环自身。
     */
    private fun commitSample(ctx: SessionContext, sample: DevicePerfSample) {
        synchronized(stateLock) {
            if (sessions[ctx.serial] !== ctx) return
            ctx.ring.append(historyPointOf(sample))
            latestBySerial[ctx.serial] = sample
            tickErrors.remove(ctx.serial)
            republishLocked()
        }
    }

    /** 采样环整拍失败回执（同 [commitSample] 世代闸：迟到写不落表） */
    private fun recordTickError(ctx: SessionContext, message: String) {
        synchronized(stateLock) {
            if (sessions[ctx.serial] !== ctx) return
            tickErrors[ctx.serial] = message
            republishLocked()
        }
    }

    /** 会话收束落态（Ended 人话 reason 留存表内；同 [commitSample] 世代闸） */
    private fun endSession(ctx: SessionContext, reason: String) {
        synchronized(stateLock) {
            if (sessions[ctx.serial] !== ctx) return
            ctx.ended = reason
            republishLocked()
        }
    }

    /** 取（或建）该 serial 的环形历史（调用方持 [stateLock]） */
    private fun ringForLocked(serial: String): PerfHistory =
        rings.getOrPut(serial) { PerfHistory(historyCapacity) }

    /** 由锁内字段重建对外快照发布（调用方持 [stateLock]；快照全量重建，值语义发布） */
    private fun republishLocked() {
        _state.value = PerfSamplerState(
            primarySerial = primarySerial,
            sessions = LinkedHashMap<String, PerfSessionState>().apply {
                sessions.forEach { (serial, ctx) ->
                    put(
                        serial,
                        ctx.ended?.let { PerfSessionState.Ended(it) }
                            ?: PerfSessionState.Active(serial),
                    )
                }
            },
            latestBySerial = latestBySerial.toMap(),
            historyBySerial = rings.mapValues { it.value.snapshot() }.toMap(),
            tickErrorBySerial = tickErrors.toMap(),
        )
    }

    private fun historyPointOf(sample: DevicePerfSample) = PerfHistoryPoint(
        timestampMs = sample.timestampMs,
        cpuPercent = sample.cpuPercent.value,
        memUsedPercent = sample.memory.value?.usedPercent,
        fps = sample.fps.value,
    )

    /**
     * 目标设备解析（默认路径）：会话选定优先（须与当前设备态对得上——选定残留
     * 而设备已消失时按未选定处理，防对空串号注入 `-s`），其次单设备；无设备/
     * 检测失败/多设备未选定 → null。
     */
    private fun resolveTargetSerial(): String? {
        if (devices.detectError.value != null) return null
        val selected = devices.selectedSerial.value
        if (selected != null) {
            return when (val s = devices.deviceState.value) {
                is DeviceState.Single -> s.serial
                is DeviceState.Multi ->
                    if (s.devices.any { it.serial == selected }) selected else null

                DeviceState.None -> null
            }
        }
        val single = devices.deviceState.value as? DeviceState.Single ?: return null
        return single.serial
    }

    /** 批量会话目标在场检查（设备检测失败时设备态坍缩 None，同样按离场收会话） */
    private fun serialPresent(serial: String): Boolean = when (val s = devices.deviceState.value) {
        is DeviceState.Single -> s.serial == serial
        is DeviceState.Multi -> s.devices.any { it.serial == serial }
        DeviceState.None -> false
    }

    // ── 采样执行 ──

    /**
     * 单拍采样（internal 供单测直接驱动，虚拟时钟下 [cpuWindowMs] 窗口即
     * [delay]）：差值基线读取失败抛异常（整拍失败）；六域各自 [PerfField.of]
     * 独立成败，单域坏输出只落该域 error。FPS 的 flips 基线是域级失败面——
     * 基线拍 flips 缺席（ROM 不打印/格式不符）只落 fps 域 error，不拖垮整拍。
     */
    internal suspend fun sampleOnce(serial: String, adbPath: String): DevicePerfSample {
        val windowStartMs = clock()
        val baselineSections = PerfParsers.splitCombined(execShell(adbPath, serial, PerfParsers.BASELINE_PAYLOAD))
        val baseline = try {
            PerfParsers.parseProcStatTotal(baselineSections.stat)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("读取 /proc/stat 差值基线失败：${e.message}")
        }
        val baselineFlips = PerfField.of { PerfParsers.parseSurfaceFlingerFlips(baselineSections.sf) }
        delay(cpuWindowMs)
        val sections = PerfParsers.splitCombined(execShell(adbPath, serial, PerfParsers.COMBINED_PAYLOAD))
        val elapsedMs = clock() - windowStartMs
        val cpu = PerfField.of {
            PerfParsers.cpuBusyPercentBetween(baseline, PerfParsers.parseProcStatTotal(sections.stat))
        }
        val memory = PerfField.of { PerfParsers.parseMeminfo(sections.mem) }
        val temperature = PerfField.of { PerfParsers.parseThermalservice(sections.thermal) }
        // 电池双链：主链 dumpsys battery 失败（服务缺席/格式不符）→ dumpsys power 回退
        // 链；回退链 exec 异常也只落电池域 error，不拖垮整拍
        var battery = PerfField.of { PerfParsers.parseBatteryLevel(sections.battery) }
        if (battery.error != null) {
            battery = try {
                val powerOutput = execShell(adbPath, serial, "dumpsys power")
                PerfField.of { PerfParsers.parsePowerBatteryLevel(powerOutput) }
            } catch (e: IllegalStateException) {
                PerfField.fail("电池读数不可用：${e.message}")
            }
        }
        val processes = PerfField.of { PerfParsers.parseTopProcesses(sections.top) }
        val fps = PerfField.of {
            val prevFlips = baselineFlips.value
                ?: throw IllegalArgumentException("flips 基线采样点不可用（${baselineFlips.error}）")
            PerfParsers.fpsBetween(prevFlips, PerfParsers.parseSurfaceFlingerFlips(sections.sf), elapsedMs)
        }
        return DevicePerfSample(
            timestampMs = clock(),
            cpuPercent = cpu,
            memory = memory,
            temperatureC = temperature,
            batteryPercent = battery,
            processes = processes,
            fps = fps,
        )
    }

    /**
     * 单条 adb shell 执行：payload 经 shell 语义包裹（Unix 单引号 / Windows 双引号
     * ——cmd 不解析单引号，与 [AdbCommandRewriter] 的路径包裹同规则），再经
     * [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`，执行通道 onFailure 与
     * 非零退出码（设备拔出时 adb 报错退出）都抛异常（整拍失败语义）。
     */
    private suspend fun execShell(adbPath: String, serial: String, payload: String): String {
        val command = AdbCommandRewriter.rewrite(
            "adb shell ${shellQuote(payload)}",
            adbPath,
            serial,
        )
        return runner.execute(command).getOrElse { e ->
            throw IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}")
        }.let { shellResult ->
            if (shellResult.exitCode != 0) {
                throw IllegalStateException(
                    "adb 退出码 ${shellResult.exitCode}：${shellResult.stderr.trim().take(120)}",
                )
            }
            shellResult.stdout
        }
    }

    private fun shellQuote(payload: String): String =
        DeviceShellSupport.quotePayload(payload)

    companion object {
        private const val TAG = "PerfSampler"

        /** 无会话期单参注入缝的兜底环键（adb 串号非空，恒不冲突） */
        internal const val DEFAULT_RING_KEY = ""

        /** 默认采样间隔（曲线档：30 分钟窗 ≈ 360 点，环形缓冲 ~8.6 KB/曲线） */
        internal const val DEFAULT_SAMPLE_INTERVAL_MS = 5_000L

        /** CPU 差值窗口（两次 /proc/stat 读取间隔；窗口即占用率的分母时段） */
        internal const val DEFAULT_CPU_WINDOW_MS = 1_000L

        /** 历史滚动窗口档（环形缓冲容量 = 窗口 / 采样间隔） */
        internal const val HISTORY_WINDOW_MS = 30 * 60_000L

        /** 整拍连续失败收会话阈值（瞬时 USB 抖动容差，域级失败不计入） */
        internal const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
