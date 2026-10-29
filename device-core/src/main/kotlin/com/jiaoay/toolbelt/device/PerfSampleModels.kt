package com.jiaoay.toolbelt.device

/**
 * 性能单项读数：[value] 与 [error] 互斥且必居其一（坏输出显式失败，禁静默给 0）。
 * 解析失败/服务缺席/格式不符均落 [error] 人话原因，由消费面如实呈现。
 */
data class PerfField<out T : Any>(val value: T?, val error: String?) {
    init {
        require((value == null) != (error == null)) { "PerfField 的 value 与 error 必须二选一" }
    }

    /** 值存在则映射，失败原样透传（错误链不断裂） */
    fun <R : Any> map(transform: (T) -> R): PerfField<R> =
        if (value != null) PerfField(transform(value), null) else PerfField(null, error)

    companion object {
        fun <T : Any> ok(value: T): PerfField<T> = PerfField(value, null)
        fun <T : Any> fail(error: String): PerfField<T> = PerfField(null, error)

        /** 显式失败不静默求值语义的真门禁：解析块抛 [IllegalArgumentException]（人话 message）转 [fail] */
        fun <T : Any> of(block: () -> T): PerfField<T> = try {
            ok(block())
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "解析失败")
        }
    }
}

/** 内存读数（`/proc/meminfo` 关键项，单位 kB）：usedPercent = (total − available) / total */
data class MemoryStats(
    val totalKb: Long,
    val availableKb: Long,
    val freeKb: Long,
) {
    val usedPercent: Float
        get() = if (totalKb > 0) (totalKb - availableKb) * 100f / totalKb else 0f
}

/**
 * 进程排行条目（`top -n 1 -b` 行解析）：[rssKb] 由 RES 列的 G/M/K 缩写按 1024 进制
 * 折算（toybox human_readable 缩写口径，排序够用非精确认证）。
 */
data class ProcessEntry(
    val pid: Int,
    val user: String,
    val cpuPercent: Float,
    val memPercent: Float,
    val rssKb: Long,
    val name: String,
)

/**
 * 单次设备负载波快照：六域各自独立成败（电池缺席不得拖垮整拍）。[fps] 是全屏
 * 合成帧率（flips 差值 / 两采样点实际间隔——语义与防误读口径见
 * [PerfParsers.parseSurfaceFlingerFlips]），非单应用帧率。
 */
data class DevicePerfSample(
    val timestampMs: Long,
    val cpuPercent: PerfField<Float>,
    val memory: PerfField<MemoryStats>,
    val temperatureC: PerfField<Float>,
    val batteryPercent: PerfField<Int>,
    val processes: PerfField<List<ProcessEntry>>,
    val fps: PerfField<Float>,
)

/** 曲线历史点：该拍采样失败的域为 null（折线在 null 处断段，不伪装成 0） */
data class PerfHistoryPoint(
    val timestampMs: Long,
    val cpuPercent: Float?,
    val memUsedPercent: Float?,
    val fps: Float?,
)

/** 环形缓冲快照（oldest→newest 定序）：[overflowed] = 已丢最旧（超出 30 分钟档） */
data class PerfHistorySnapshot(
    val points: List<PerfHistoryPoint>,
    val capacity: Int,
    val overflowed: Boolean,
)

/**
 * 历史环形缓冲（固定容量、线程经持有方串行访问——写入只发生在采样协程）：
 * 满后追加丢最旧（30 分钟滚动窗口），[snapshot] 产不可变列表供 StateFlow 发布
 * （值语义发布，避免同实例跳过订阅通知）。
 */
class PerfHistory(private val capacity: Int) {

    private val points = ArrayDeque<PerfHistoryPoint>(capacity.coerceAtLeast(1))
    private var overflowed = false

    fun append(point: PerfHistoryPoint) {
        if (points.size >= capacity) {
            points.removeFirst()
            overflowed = true
        }
        points.addLast(point)
    }

    fun snapshot(): PerfHistorySnapshot = PerfHistorySnapshot(points.toList(), capacity, overflowed)

    fun clear() {
        points.clear()
        overflowed = false
    }
}

/** 采样会话状态机：无会话（未开始/已结束）与有会话两态的面板契约 */
sealed interface PerfSessionState {
    /** 无会话：未开始（历史可仍在——上一会话曲线未清） */
    data object Idle : PerfSessionState

    /** 有会话：[serial] 为本会话绑定目标（默认路径换选设备时旧会话收束、按新目标另起新会话） */
    data class Active(val serial: String) : PerfSessionState

    /** 无会话：已结束（目标设备断开 / 连续失败），[reason] 人话呈现 */
    data class Ended(val reason: String) : PerfSessionState
}

/**
 * 采样器对外快照（StateFlow 值）：多会话表 + 各 serial 的最近一拍 / 曲线历史 /
 * 最近整拍级错误（[DevicePerfSample] 域级错误在各自 PerfField 内，两处错误语义
 * 分层：域级失败不断会话，整拍失败连续 [PerfSampler.MAX_CONSECUTIVE_FAILURES]
 * 次才收会话）。
 *
 * 表语义：[sessions] 只含 Active/Ended 会话（Idle = 无会话，不入表，键为
 * serial）；曲线历史与最近一拍按 serial 续存（会话结束/用户停止后仍保留——
 * 30 分钟滚动窗跨会话续存，[historyBySerial] 的键恒 ⊇ 曾采样过的 serial）。
 *
 * 单会话投影（[session]/[latest]/[history]/[tickError]）：锚定 [primarySerial]
 * （默认采样路径的目标锚），缺席时回退表内首项——单设备/单会话消费面读投影
 * 即得旧语义，不感知多会话表；多会话消费面（多曲线面板）须直读四个 BySerial
 * 表，禁用投影（多表下投影取哪一台是未定义偏好）。
 */
data class PerfSamplerState(
    /** 主 serial：默认采样路径与单参注入缝 [PerfSampler.applySample] 的投影锚 */
    val primarySerial: String = "",
    /** 会话表（keyed by serial；LinkedHashMap 保持起会话顺序，值只含 Active/Ended） */
    val sessions: Map<String, PerfSessionState> = emptyMap(),
    /** 各 serial 最近一拍（会话结束后保留） */
    val latestBySerial: Map<String, DevicePerfSample> = emptyMap(),
    /** 各 serial 曲线历史快照（无会话期也保留——滚动窗跨会话续存） */
    val historyBySerial: Map<String, PerfHistorySnapshot> = emptyMap(),
    /** 各 serial 最近整拍级错误（成功拍/注入缝写入即清该 serial 项） */
    val tickErrorBySerial: Map<String, String> = emptyMap(),
) {
    private val emptyHistory: PerfHistorySnapshot = PerfHistorySnapshot(emptyList(), 0, false)

    /** 单会话投影：主 serial 的会话态；表空 = Idle，主 serial 无会话时回退表内首项 */
    val session: PerfSessionState
        get() = sessions[primarySerial] ?: sessions.values.firstOrNull() ?: PerfSessionState.Idle

    /** 单会话投影：主 serial 的最近一拍 */
    val latest: DevicePerfSample?
        get() = latestBySerial[primarySerial] ?: latestBySerial.values.firstOrNull()

    /** 单会话投影：主 serial 的曲线历史 */
    val history: PerfHistorySnapshot
        get() = historyBySerial[primarySerial] ?: historyBySerial.values.firstOrNull() ?: emptyHistory

    /** 单会话投影：主 serial 的最近整拍错误 */
    val tickError: String?
        get() = tickErrorBySerial[primarySerial] ?: tickErrorBySerial.values.firstOrNull()
}
