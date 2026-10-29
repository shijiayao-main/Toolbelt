package com.jiaoay.easy_dev_toolbox.page.logcat

import com.jiaoay.easy_dev_toolbox.core.extensions.StreamingShell
import com.jiaoay.easy_dev_toolbox.core.extensions.StreamingShellHandle
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * logcat 级别（单字母档），ordinal 即严重度序：V < D < I < W < E < F。
 */
enum class LogcatLevel(val letter: String) {
    VERBOSE("V"),
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E"),
    FATAL("F"),
}

/**
 * logcat 行过滤规格：统一过滤模型——内部承载行级谓词树 [LogcatLinePredicate]。
 * 两类输入面编译到同一模型、同一消费通道 [LogcatLineMatcher.matches]：查询语法
 * （[LogcatQueryParser.parse]）与本类兼容构造。武装捕获正则是独立判定通道，
 * 不经本模型——查询只进过滤，永不触发武装。
 *
 * 匹配语义（[LogcatLineMatcher.matches] 的契约，由 [LogcatLinePredicate.matchesParsed]
 * 承载）：
 * - 谓词树不含任何行级原子 → 恒命中；
 * - 行不可解析（解析不出级别与 tag）且树内含任一原子 → 不命中（过滤语义 = 只留
 *   可确认命中的行，不可确认即丢弃；msg 原子同理——不可解析行无消息体可言，
 *   拒收是「宁拒不猜」的自然延伸，不是遗漏）；
 * - tag 原子按包含/正则判定，级别原子按严重度 ≥ 档位判定，msg 原子按消息体包含/
 *   正则判定，AND/OR/非按树形合成。
 *
 * 兼容构造形态保留（存量调用零改，旧语义向后兼容）：`tagQuery` 空白/中英文逗号
 * 分隔多词 contains-any（忽略大小写）+ `minLevel` 最低级别档（null = 不过滤级别），
 * 两条件按 AND 合并——与历史过滤语义逐字等价。
 */
class LogcatFilterSpec internal constructor(internal val predicate: LogcatLinePredicate) {

    constructor(tagQuery: String = "", minLevel: LogcatLevel? = null) : this(
        LogcatQueryParser.legacyTagPredicate(tagQuery, minLevel),
    )
}

/**
 * logcat 行匹配器：从 threadtime（`MM-DD hh:mm:ss.mmm pid tid L TAG: msg`）
 * 与 brief / time（`L/TAG(pid): msg`，行首或日期时间前缀后）两种主流形态解析级别与 tag。
 *
 * 纯函数、线程安全；两种形态都不匹配的行返回 null（不可确认，交由谓词树按
 * [LogcatFilterSpec] 契约裁决）。对外 [matches] 契约不变：消费面（引擎行过滤）零改。
 */
class LogcatLineMatcher(spec: LogcatFilterSpec) {

    private val predicate = spec.predicate

    /** 单行命中判定；不可解析行在有过滤时恒不命中（见 [LogcatFilterSpec] 契约） */
    fun matches(line: String): Boolean {
        // 无原子恒命中快路径（无过滤抓取的默认规格）：免逐行 parse，语义与
        // matchesParsed 的零原子分支逐字等价（恒 true，含不可解析行）
        if (predicate === MatchAll) return true
        val parsed = parse(line)
        return predicate.matchesParsed(parsed?.level, parsed?.tag, parsed?.message)
    }

    /**
     * 行首解析结果：级别 + tag + message 余部。
     *
     * message 余部边界（两形态逐形态钉死，引擎级测试守护）：
     * - threadtime（`MM-DD hh:mm:ss.mmm pid tid L TAG: msg`）：`TAG: ` 分隔符后整段
     *   原文（含消息内部空白；末 token 按 split limit 并入余部不重切）。tag token
     *   无冒号或行尾即 tag token ⇒ 空——无消息体；
     * - brief / time（`L/TAG(pid): msg`）：头 token 后首个 `)` 的 `): ` 分隔符后整段
     *   原文。无 `)` 或 `)` 后不以 `:` 起头（分隔符不成形）⇒ 空（宁拒不猜：不成形
     *   头不猜消息起点，msg 原子对该行恒不命中）。
     * 空串与「无消息」同形态：msg 包含词/正则对空消息体恒按 contains 结果裁决。
     */
    internal data class ParsedLine(val level: LogcatLevel, val tag: String, val message: String)

    internal companion object {
        /** threadtime 列序：0=日期 1=时间 2=pid 3=tid 4=级别单字母 5=tag（`: msg` 余部并入 token 6） */
        private const val THREADTIME_LEVEL_TOKEN = 4
        private const val THREADTIME_TAG_TOKEN = 5

        /** brief/time 形态头：`L/TAG(`——TAG 不得含空白与左括号 */
        private val BRIEF_HEAD = Regex("""^([VDIWEF])/([^(\s]*)""")

        /** 列切分空白（parse 对每条到达行调用——突发速率下逐行内联 Regex = 逐行重复编译） */
        private val WHITESPACE_SPLIT = Regex("\\s+")

        internal fun parse(line: String): ParsedLine? {
            val trimmed = line.trim()
            val tokens = trimmed.split(WHITESPACE_SPLIT, limit = THREADTIME_TAG_TOKEN + 2)
            tokens.getOrNull(THREADTIME_LEVEL_TOKEN)
                ?.takeIf { it.length == 1 }
                ?.let { levelLetter -> LogcatLevel.entries.firstOrNull { it.letter == levelLetter } }
                ?.let { level ->
                    val tag = tokens.getOrNull(THREADTIME_TAG_TOKEN)
                        ?.substringBefore(":")
                        .orEmpty()
                    if (tag.isNotEmpty()) {
                        return ParsedLine(level, tag, threadtimeMessage(tokens))
                    }
                }
            // brief 形态行首直起；time 形态日期时间前缀后（第 3 token）起
            for (headIndex in intArrayOf(0, 2)) {
                val head = tokens.getOrNull(headIndex) ?: continue
                val brief = BRIEF_HEAD.find(head) ?: continue
                val level = LogcatLevel.entries.firstOrNull { it.letter == brief.groupValues[1] } ?: continue
                val tag = brief.groupValues[2]
                if (tag.isNotEmpty()) return ParsedLine(level, tag, briefMessage(trimmed, brief.value))
            }
            return null
        }

        /** threadtime message 余部：tag token（`: msg`）后的末 token 整段（原文含内部空白） */
        private fun threadtimeMessage(tokens: List<String>): String =
            tokens.getOrNull(THREADTIME_TAG_TOKEN + 1).orEmpty()

        /**
         * brief/time message 余部：头 token 原文位置后首个 `)` 起的 `): ` 分隔符后整段。
         * 分隔符不成形（无 `)` 或 `)` 后非 `:` 起头）⇒ 空（宁拒不猜——见 [ParsedLine] 契约）。
         */
        private fun briefMessage(trimmed: String, headText: String): String {
            val headStart = trimmed.indexOf(headText)
            if (headStart < 0) return ""
            val parenEnd = trimmed.indexOf(')', startIndex = headStart + headText.length)
            if (parenEnd < 0) return ""
            val afterParen = trimmed.substring(parenEnd + 1)
            if (!afterParen.startsWith(":")) return ""
            return afterParen.removePrefix(":").removePrefix(" ")
        }
    }
}

/**
 * logcat 行流运行器：引擎与具体进程执行之间的接缝。
 *
 * 生产实现 [StreamingShellLogcatRunner] 走 [StreamingShell]；单测注入 fake（不真跑 adb）。
 * 契约：[run] 挂起至流终止（进程退出或经 [stop] 终止）后返回；进程启动失败返回 null。
 * [onLine] 回调可能在 [run] 返回前后由读流协程短暂并发到达（StreamingShell 强杀放行路径），
 * 引擎按迟到行丢弃处置。
 */
interface LogcatStreamRunner {
    suspend fun run(command: String, onLine: suspend (String) -> Unit): Int?
    fun stop()
}

/**
 * 生产运行器：[StreamingShell.executeStreaming] 直跑 adb logcat。
 *
 * 无超时看门狗——实时流由用户停止按钮经 [stop] 唯一终止（内存有界性由
 * [LogcatCaptureEngine] 的滚动窗 + 流式落盘保证，与执行队列的「超时封顶 + 滚动窗」
 * 口径互补）。
 */
object StreamingShellLogcatRunner : LogcatStreamRunner {

    @Volatile
    private var currentHandle: StreamingShellHandle? = null

    override suspend fun run(command: String, onLine: suspend (String) -> Unit): Int? {
        currentHandle = null
        val result = StreamingShell.executeStreaming(
            command = command,
            onStarted = { handle -> currentHandle = handle },
            onLine = { output -> onLine(output.line) },
        )
        return result.fold(
            onSuccess = { it.exitCode },
            onFailure = { e ->
                JLog.e(TAG) { "logcat stream failed to start: ${e.message}" }
                null
            },
        ).also { currentHandle = null }
    }

    override fun stop() {
        currentHandle?.kill()
    }

    private const val TAG = "LogcatRunner"
}

/**
 * 实时抓取的滚动窗快照。
 *
 * @param lines 最近 [LogcatCaptureEngine.MAX_RING_LINES] 行（超限丢最旧）
 * @param rows [lines] 的折叠行组视图（崩溃堆栈折叠，[LogcatConsoleFoldTracker] 收集线程
 *   增量维护、随每窗快照发布；对同一 lines 恒等于 foldConsoleRows(lines)——消费面免
 *   主线程全窗正则重扫；纯显示派生面，落盘契约零承载）
 * @param totalCount 已落盘总行数（全量流口径，不受滚动窗截断影响；[LogcatCaptureEngine.clearDisplay] 清屏亦不触碰）
 * @param droppedCount 滚动窗溢出累计（超 [LogcatCaptureEngine.MAX_RING_LINES] 截断的最旧行数；只影响回看，落盘不受影响；清屏不是溢出截断，不追加也不撤销）
 */
data class LogcatWindow(
    val lines: List<String> = emptyList(),
    val totalCount: Long = 0,
    val droppedCount: Int = 0,
    val rows: List<LogcatConsoleRow> = emptyList(),
)

/**
 * 崩溃堆栈折叠增量追踪器：[foldConsoleRows] 全量参考实现的增量等价形态——同一窗口
 * 行列表下 [rows] 与参考实现逐组逐字段一致（单测守护），每步成本只与新增行数相关。
 *
 * 维护方式（对照参考实现的整窗重扫）：
 * - 已定稿行组只追加不重扫；尾段连续帧行延迟定稿（后续帧行继续并入同组，非帧行到达
 *   才落定）——尾段在窗截面上按当前阈值临时呈现（≥ 阈值呈现为折叠组，否则平铺），
 *   与参考实现对该截面的判定一致；
 * - 溢出截断只修头部：整组被截的行组出列；跨界的堆栈组按可见段重算（可见段
 *   ≥ [MIN_FOLDABLE_STACK_FRAMES] 仍折叠，跌破阈值平铺——与参考实现按截断后
 *   窗口重扫的口径一致）；余组下标整体前移。
 *
 * [LogcatConsoleRow.firstIndex] 语义与参考实现一致 = 滚动窗行相对下标；渲染 key 由
 * 消费面按「滚动窗首行绝对序号 + firstIndex」组合（存活组跨 commit 恒定——展开记账
 * 不漂移；组头行被截断的组 key 随组头行移位，参考实现同口径）。
 *
 * 线程模型：实例非线程安全——由单一持有方在其锁内串行调用（引擎 = 收集线程的
 * [LogcatCaptureEngine] bufferLock 路径）。
 */
internal class LogcatConsoleFoldTracker {

    /** 已定稿行组（ArrayDeque：头部出列 + 尾部追加均摊还 O(1)） */
    private val finalized = ArrayDeque<LogcatConsoleRow>()

    /** 尾段未闭帧行（定稿延后：后续帧行继续并入，非帧行到达即定稿） */
    private val pendingFrames = mutableListOf<String>()

    /** 已定稿行组覆盖的滚动窗行数 = 尾段帧行起始下标（不变量：本值 + 尾段行数 == 窗行数） */
    private var finalizedLineCount = 0

    fun reset() {
        finalized.clear()
        pendingFrames.clear()
        finalizedLineCount = 0
    }

    /** 滚动窗尾追加一行（调用方须已把行入窗尾；非帧行先定稿前段尾组再平铺自身） */
    fun append(line: String) {
        if (STACK_FRAME_LINE.containsMatchIn(line)) {
            pendingFrames.add(line)
            return
        }
        flushPending()
        finalized.addLast(LogcatConsoleRow.Plain(line, finalizedLineCount))
        finalizedLineCount++
    }

    /**
     * 滚动窗头截断 count 行（调用方须已完成截断；0 无副作用）。
     * 契约：count ≤ 当前窗行数（超出即前置不变量破坏，subList 越界显式炸出）。
     */
    fun evict(count: Int) {
        if (count <= 0) return
        if (count >= finalizedLineCount) {
            // 已定稿行组全部出窗：整组清空，余量从尾段帧行头修剪
            finalized.clear()
            pendingFrames.subList(0, count - finalizedLineCount).clear()
            finalizedLineCount = 0
            return
        }
        var crossing: LogcatConsoleRow.Stack? = null
        while (finalized.isNotEmpty()) {
            val row = finalized.first()
            val covered = if (row is LogcatConsoleRow.Stack) row.frames.size else 1
            if (row.firstIndex + covered > count) {
                // 跨界组只可能是 Stack（Plain 单行组：firstIndex < count 即整组被截）
                if (row.firstIndex < count) crossing = row as LogcatConsoleRow.Stack
                break
            }
            finalized.removeFirst()
        }
        crossing?.let { finalized.removeFirst() }
        // 余组（含跨界组之后）下标整体前移 count
        for (i in finalized.indices) {
            finalized[i] = when (val row = finalized[i]) {
                is LogcatConsoleRow.Plain -> row.copy(firstIndex = row.firstIndex - count)
                is LogcatConsoleRow.Stack -> row.copy(firstIndex = row.firstIndex - count)
            }
        }
        // 跨界组可见段重算（下标已按截断后窗口）：≥ 阈值仍折叠、跌破阈值平铺，
        // 逆序 addFirst 保序
        crossing?.let { stack ->
            val visible = stack.frames.drop(count - stack.firstIndex)
            if (visible.size >= MIN_FOLDABLE_STACK_FRAMES) {
                finalized.addFirst(LogcatConsoleRow.Stack(visible, 0))
            } else {
                visible.asReversed().forEachIndexed { offset, frame ->
                    finalized.addFirst(LogcatConsoleRow.Plain(frame, visible.size - 1 - offset))
                }
            }
        }
        finalizedLineCount -= count
    }

    /** 当前折叠行组快照（与 [foldConsoleRows] 对同一窗口行列表的输出恒等） */
    fun rows(): List<LogcatConsoleRow> = when {
        pendingFrames.isEmpty() -> finalized.toList()
        pendingFrames.size >= MIN_FOLDABLE_STACK_FRAMES ->
            finalized.toList() + LogcatConsoleRow.Stack(pendingFrames.toList(), finalizedLineCount)

        else -> finalized.toList() + pendingFrames.mapIndexed { offset, frame ->
            LogcatConsoleRow.Plain(frame, finalizedLineCount + offset)
        }
    }

    /** 尾段帧组定稿（非帧行到达时调用；短栈平铺不折叠——阈值语义同参考实现） */
    private fun flushPending() {
        if (pendingFrames.isEmpty()) return
        if (pendingFrames.size >= MIN_FOLDABLE_STACK_FRAMES) {
            finalized.addLast(LogcatConsoleRow.Stack(pendingFrames.toList(), finalizedLineCount))
        } else {
            pendingFrames.forEachIndexed { offset, frame ->
                finalized.addLast(LogcatConsoleRow.Plain(frame, finalizedLineCount + offset))
            }
        }
        finalizedLineCount += pendingFrames.size
        pendingFrames.clear()
    }
}

/**
 * 抓取中的标记锚点：标记时刻屏上最新带时间戳行的行首时间戳。
 *
 * @property timestamp 行首时间戳原文（threadtime 头 `MM-DD hh:mm:ss.mmm`，年份形态同纳）
 *   ——与查看器时间戳定位档同格式，查看侧原样透传即直达；两形态恒零触碰改写
 * @property markedYear 标记时刻本机年（[markAnchor] 内 [java.time.LocalDate.now].year）——
 *   裸月日形态（threadtime 头默认无年份）的年份补全唯一来源
 */
data class LogcatAnchor(
    val timestamp: String,
    val markedYear: Int,
) {
    /** 回显时钟段（`hh:mm:ss`）——「已标记 HH:mm:ss」回显与回执用；两形态同纳（取首个空格后段） */
    val clockText: String get() = timestamp.substringAfter(' ').substringBefore('.')

    /**
     * 含年完整时间点（`yyyy-MM-dd hh:mm:ss.mmm`）：工作台问题时间点预填的载荷形态——
     * 工作台归一面接受空格分隔并归一到 `yyyy-MM-dd_HH:mm:ss.SSS`。
     *
     * 形态判定契约：timestamp 只承诺 [LogcatCaptureEngine.ANCHOR_LINE_HEAD] 纳入的
     * 两形态——第 5 字符为 `-` 即 4 位年形态（`-v year` 抓取，原文透传不改写），
     * 否则视为裸月日形态，以 [markedYear] 拼前缀补全年份。
     */
    val fullTimestamp: String
        get() = if (timestamp.length > 4 && timestamp[4] == '-') timestamp else "$markedYear-$timestamp"
}

/**
 * 武装命中回执载荷：命中行原文 + 收网落定的锚点（null = 命中行无行首时间戳且窗内
 * 无可锚定行——命中照常回执，只是无处可锚）。
 */
data class LogcatArmedHit(
    val line: String,
    val anchor: LogcatAnchor?,
)

/** 实时抓取状态机：Idle → Running → Stopped（产物路径与终止原因随终态携带） */
sealed class LogcatCaptureStatus {
    data object Idle : LogcatCaptureStatus()
    data class Running(val outputFile: File) : LogcatCaptureStatus()
    data class Stopped(val outputFile: File?, val totalCount: Long, val message: String) : LogcatCaptureStatus()
}

/**
 * logcat 实时抓取引擎：StreamingShell 流式消费 + 按行落盘 + 有界滚动窗。
 *
 * 终止语义：**不入任何串行执行队列**——队列是「单条命令限时完成」的事务语义
 * （限时看门狗 + 完成信号 = 进程退出），实时抓取是「用户停止按钮唯一终止」的
 * 长跑流，无超时看门狗。内存有界口径：滚动窗 + 全量流按行落盘
 * （滚动窗只保最近 [MAX_RING_LINES] 行供实时回看，落盘文件承载全量）。
 *
 * 落盘契约（单测钉死）：
 * - 每条命中行整行 + '\n' 一次写入（StreamingShell 按 '\n' 分行只回调完整行），文件无半行残留；
 * - 写入句柄在流终止后必须关闭——终态 [LogcatCaptureStatus.Stopped] 落定即已关闭；
 * - 进程启动失败且零命中时清理空产物文件。
 *
 * 线程模型：[start] 任意线程可调；行回调来自 StreamingShell 的 stdout/stderr 双读协程
 * （并发到达，落盘写经 [writeLock] 互斥）；状态以 [status] / [window] StateFlow 发布——
 * 行快照经「首行直通 + 100ms 窗口批量」提交（ShellOutputAggregator 同配方：单行单快照
 * 在 logcat 突发速率下重组成本不可控）。
 *
 * 武装捕获（[arm]/[disarm]）：特征正则命中自动标记锚点的独立判定通道，与行过滤
 * matcher 两语义两通道，契约见 [arm] KDoc。
 */
class LogcatCaptureEngine(
    private val scope: CoroutineScope,
    private val runner: LogcatStreamRunner = StreamingShellLogcatRunner,
) {

    companion object {
        /** 滚动窗上限（值真源 = Dimens.listBoundedMax，与执行队列 MAX_OUTPUT_ITEMS 同档） */
        const val MAX_RING_LINES: Int = Dimens.listBoundedMax

        /** 行快照聚合窗口与单批上限（ShellOutputAggregator 同配方） */
        internal const val AGGREGATION_WINDOW_MS = 100L
        internal const val MAX_BATCH = 1000

        /** 武装命中回执流的有界缓冲（DROP_OLDEST——回执瞬时可弃，洪峰只丢回执不丢锚点） */
        private const val ARMED_HIT_BUFFER = 16

        /**
         * 行通道容量：**有界 + 发送端可挂起**，把背压传回 adb 读协程。
         *
         * 不能用 DROP_OLDEST/DROP_LATEST：`clearDisplay` 与 `flushPending` 都靠排空
         * 全部在途行来维持「totalCount 恒与落盘行数一致」的不变量，丢行即破坏该账目。
         * 容量取 [MAX_BATCH] 的 8 倍，给聚合窗口留出一次批量提交的余量。
         */
        internal const val LINE_CHANNEL_CAPACITY = MAX_BATCH * 8

        /** 行入队的最长等待（ms）：超时说明收集协程已异常停止，此时只落盘不再阻塞读链路。 */
        private const val CHANNEL_SEND_TIMEOUT_MS = 30_000L

        private const val TAG = "LogcatEngine"

        /**
         * 标记锚点承载的行首时间戳形态：与查看器时间戳 seek 的行首解析
         * （log-tools LINE_TIME，`MM-DD hh:mm:ss.mmm` / 年份形态）同口径——
         * 锚点字符串在产物文件内必有同形行头，查看侧原样透传即直达。
         */
        private val ANCHOR_LINE_HEAD =
            Regex("""^(?:\d{4}-\d{2}-\d{2}|\d{2}-\d{2}) \d{2}:\d{2}:\d{2}\.\d{3}""")
    }

    /** channel 事件：命中行（携带发射时的显示世代号）或冲刷信号（流终止时排序冲刷，先于 ack 的行必已提交） */
    private sealed interface LineEvent {
        data class Line(val generation: Long, val text: String) : LineEvent

        class FlushSignal(val ack: CompletableDeferred<Unit>) : LineEvent
    }

    private val _status = MutableStateFlow<LogcatCaptureStatus>(LogcatCaptureStatus.Idle)
    val status: StateFlow<LogcatCaptureStatus> = _status.asStateFlow()

    private val _window = MutableStateFlow(LogcatWindow())
    val window: StateFlow<LogcatWindow> = _window.asStateFlow()

    private val _anchor = MutableStateFlow<LogcatAnchor?>(null)
    val anchor: StateFlow<LogcatAnchor?> = _anchor.asStateFlow()

    private val _armed = MutableStateFlow(false)

    /** 武装等待态（true = 特征正则已挂、等待命中）：命中收网 / [disarm] / 抓取终态 / 下次 [start] 均回落 false */
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _armedHit = MutableSharedFlow<LogcatArmedHit>(
        extraBufferCapacity = ARMED_HIT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 武装命中事件流（回执载荷）：消费面据此弹「特征已命中」回执。无 replay——命中
     * 事件不为晚到的订阅者补发（重开面板不重弹历史命中；锚点结果由 [anchor] StateFlow
     * 承载，不经本流）。DROP_OLDEST 有界：回执是瞬时可弃载荷，洪峰只丢回执不丢锚点。
     */
    val armedHit: SharedFlow<LogcatArmedHit> = _armedHit.asSharedFlow()

    private val lineChannel = Channel<LineEvent>(LINE_CHANNEL_CAPACITY)

    /** 滚动窗缓冲与全量计数（[bufferLock] 保护） */
    private val ring = ArrayDeque<String>()
    private var totalCount = 0L
    private var droppedCount = 0
    private val bufferLock = Any()

    /** 折叠行组增量追踪（[bufferLock] 保护，收集线程串行调用；非线程安全） */
    private val foldTracker = LogcatConsoleFoldTracker()

    /** 落盘写入器（[writeLock] 保护；首条命中行惰性开启，[captureClosed] 后迟到行丢弃） */
    private var writer: BufferedWriter? = null
    private var captureClosed = false
    private val writeLock = Any()

    /**
     * 当前生效 matcher（[writeLock] 与 start/finishCapture 同锁保护，[Volatile] 保证行回调
     * 协程无锁读到完整一致的实例）。生命周期 = 一次抓取：start 定型 → 运行中可经
     * [updateMatcher] 原地换 → finishCapture 清空。
     */
    @Volatile
    private var activeMatcher: LogcatLineMatcher? = null

    /**
     * 武装特征正则：[writeLock] 保护写（与 [activeMatcher] 同锁同配方），[Volatile]
     * 供行回调协程无锁快读——未武装或未命中的行零锁开销，命中才进写锁二次判定收网
     * （收网判定以写锁内当前正则为准：无锁读与换防并发时以最新正则裁决）。
     * 生命周期 = 一次抓取：[start] 清零 → 运行中经 [arm]/[disarm] 挂撤 → 首个命中
     * 自动撤销（one-shot 收网）→ 终态收尾撤销。
     */
    @Volatile
    private var armedRegex: Regex? = null

    /**
     * 武装延迟自动停止倒计时 job（[writeLock] 保护）：[arm] 按延迟档启动的可取消
     * job，到点经 [runner] 停止抓取（与手动 [stop] 同一路径）。三路取消：
     * [disarm]（撤埋伏连带撤倒计时）/ 手动 [stop]（人工停止即终局，迟到的自动
     * 停止不得触达，尤其不得误停下一轮抓取）/ 流终止收尾（进程自行退出后无对象
     * 可停）。再武装以新档覆盖旧 job；下次 [start] 清零。
     */
    private var autoStopJob: Job? = null

    /**
     * 到点自动停止标记（[Volatile]）：倒计时 job 在触发停止前置位，供终态收尾
     * 区分人话回执（「武装延迟到点」vs「已停止抓取」——自动与手动同路径，回执
     * 文案必须可区分防误读）。[start] 清零。
     */
    @Volatile
    private var autoStopPending = false

    /**
     * 显示世代号（[bufferLock] 写 / [Volatile] 读）：[clearDisplay] 递增后，清屏时刻前
     * 已在途的行提交时按世代过滤，不得复活进滚动窗（ShellOutputAggregator.clearOutput
     * 同配方）。
     */
    @Volatile
    private var displayGeneration = 0L

    private var collectorJob: Job? = null

    init {
        startCollector()
    }

    /**
     * 开始一次抓取：已在抓取中返回 false（不重入——两路流会互踩同一落盘句柄）。
     * 每次开始重置滚动窗、计数与标记锚点（锚点随抓取世代清零——上次抓取的锚点
     * 指向上次产物，跨入新产物会误导「查看」直达）；命中行流式写入 [outputFile]
     * （父目录须已存在）。
     */
    fun start(command: String, outputFile: File, matcher: LogcatLineMatcher): Boolean {
        if (_status.value is LogcatCaptureStatus.Running) return false
        synchronized(bufferLock) {
            ring.clear()
            foldTracker.reset()
            totalCount = 0
            droppedCount = 0
            _window.value = LogcatWindow()
        }
        synchronized(writeLock) {
            writer = null
            captureClosed = false
            activeMatcher = matcher
            armedRegex = null
            _armed.value = false
            autoStopJob?.cancel()
            autoStopJob = null
            autoStopPending = false
        }
        _anchor.value = null
        _status.value = LogcatCaptureStatus.Running(outputFile)
        JLog.d(TAG) { "capture start: $command -> ${outputFile.absolutePath}" }
        scope.launch {
            var launchFailed = false
            try {
                val exitCode = runner.run(command) { line -> onMatchedLine(line, outputFile) }
                launchFailed = exitCode == null
            } finally {
                finishCapture(outputFile, launchFailed)
            }
        }
        return true
    }

    /**
     * 请求停止当前抓取（幂等；无抓取在跑时无副作用）。终态由收尾协程异步落定。
     * 手动停止同时取消武装延迟自动停止倒计时（人工停止即终局）。
     */
    fun stop() {
        synchronized(writeLock) {
            autoStopJob?.cancel()
            autoStopJob = null
        }
        runner.stop()
    }

    /**
     * 抓取进行中换过滤（写锁下原子替换，行回调并发到达亦只见一个一致 matcher）。
     *
     * 回溯语义：**只影响后续到达的行**——已进滚动窗与已落盘的行不回溯重算
     * （回溯重算需全量流驻留内存，违背滚动窗有界内存契约）；落盘口径不变：
     * 每条行按**到达时刻**生效的 matcher 判定命中，命中行全量落盘。
     *
     * @return false = 当前无进行中的抓取（无副作用——非运行态的过滤由 [start] 的 matcher 定型）
     */
    fun updateMatcher(matcher: LogcatLineMatcher): Boolean {
        if (_status.value !is LogcatCaptureStatus.Running) return false
        synchronized(writeLock) { activeMatcher = matcher }
        return true
    }

    /**
     * 武装捕获：把特征正则挂进当前进行中的抓取，对每条活流到达行的原文做 grep 语义
     * 命中判定（`Regex.containsMatchIn` 子串搜索，非整行匹配——用户给的是特征片段，
     * 不是全行模板）。**两语义两通道**：武装判定只决定「是否触发自动标记」，对行
     * 过滤/落盘/显示口径零触碰——哪些行进产物与滚动窗完全由过滤 matcher 决定，被
     * 过滤挡下的行同样过武装眼（特征行不必挤进过滤条件才被埋伏）。迟到行（流终止
     * 收尾后到达）随丢弃处置，不触发武装。
     *
     * 命中即收网（one-shot）：首个命中行自动撤销武装并标记锚点，之后的同特征行不再
     * 触发——复现等待语义是「等复现开始」，首个命中即复现起点；再埋伏由用户重新
     * [arm]。并发到达行同时命中只认首条（写锁内判定 + 原子收网）。
     *
     * 锚点语义与 [markAnchor] 的差异（合一覆盖，同一 [anchor] StateFlow 承载）：
     * - 武装命中锚点 = **命中行自身**的行首时间戳（原文解析，零滚动窗依赖——命中行
     *   此刻尚在批量提交在途、未上屏，取自身行头比屏上回扫更精准）；命中行无行首
     *   时间戳（brief 等形态）时退回 [markAnchor] 屏上语义，窗内无可锚定行则本次
     *   事件锚点为 null（命中照常回执，只是无处可锚）；
     * - 手动 [markAnchor] 锚点 = 标记时刻**屏上最新**带时间戳行。
     * - 合一不分两份锚点的理由：锚点消费面唯一且单点（终态「查看」初始定位 + 送
     *   工作台时间点预填都只吃单锚点），两份锚点需新增选择面而无对应收益；复现点
     *   是单点事件，后到的锚点恒更接近复现时刻（武装锚点更是命中行自身，精度只升
     *   不降），覆盖方向 = 新胜旧，与手动标记的覆盖语义一致；[anchor] 跨 Stopped
     *   保留 / 下次 [start] 清零契约不变，消费面零改线。
     *
     * 命中行若被过滤挡下（未落盘），锚点时间戳在产物内可能无同形行头——查看侧时间戳
     * seek 语义 = 「首个行首时间戳 ≥ 目标的行」，仍直达复现时刻之后的首个产物行。
     *
     * 延迟自动停止（[autoStopDelaySeconds] > 0 时）：武装时刻起倒计时 N 秒，到点
     * 自动停止抓取（时间盒埋伏——布好雷区走人，到点回来产物已封口）。倒计时与
     * 命中无关：命中只管标记收网，不提前也不顺延到点；到点停止与手动 [stop] 同
     * 路径，终态回执人话区分。倒计时 job 三路取消：[disarm]（撤埋伏连带撤倒计时）、
     * 手动 [stop]、流终止收尾；已武装时再 [arm] 以新正则 + 新延迟档覆盖旧埋伏与
     * 旧倒计时。默认 0 = 不延迟：武装保持纯等待语义（现状口径零回归）。
     *
     * @return false = 当前无进行中的抓取（非 Running 态拒绝、无副作用——武装随下次
     *   [start] 从零定型）
     */
    fun arm(pattern: Regex, autoStopDelaySeconds: Int = 0): Boolean {
        if (_status.value !is LogcatCaptureStatus.Running) return false
        synchronized(writeLock) {
            armedRegex = pattern
            _armed.value = true
            autoStopJob?.cancel()
            autoStopJob = if (autoStopDelaySeconds > 0) {
                scope.launch {
                    delay(autoStopDelaySeconds * 1000L)
                    // 置位先于停止（volatile）：终态收尾读到的回执口径以本标记区分
                    autoStopPending = true
                    runner.stop()
                }
            } else {
                null
            }
        }
        return true
    }

    /**
     * 解除武装（幂等）：撤销特征埋伏与延迟自动停止倒计时，等待态回落 false。
     * 未武装 / 非运行态调用均为无副作用 no-op。
     */
    fun disarm() {
        synchronized(writeLock) {
            armedRegex = null
            _armed.value = false
            autoStopJob?.cancel()
            autoStopJob = null
        }
    }

    /**
     * 清屏（清显示不清落盘）。两条契约（单测钉死，防误读）：
     * - **恒全量落盘**：只清滚动窗（ring → [window]），落盘句柄、[LogcatWindow.totalCount]
     *   与 matcher 零触碰——清屏前后落盘文件内容与全量计数零变化；
     * - **只影响后续行**：过滤语义不变，清屏后到达的命中行照常追加显示与落盘。
     *
     * 防复活（ShellOutputAggregator.clearOutput 同配方）：递增显示世代号 + drain
     * channel——清屏时刻前已落盘、未提交上屏的在途行提交时按世代过滤不进滚动窗，
     * 但照常计入 totalCount（全量计数不吃显示过滤，恒与落盘行数一致）。
     *
     * [LogcatWindow.droppedCount] 保留不清零：口径 = 滚动窗溢出累计（超 [MAX_RING_LINES]
     * 截断的最旧行数），清屏不是溢出截断，不追加也不撤销该计数；同族聚合器清空归零
     * 的理由（无全量计数、清空即清账）在本引擎不成立——全量账由 totalCount 承载，
     * 清屏不清账。
     *
     * 线程模型：与 [commitBatch] / [start] 同走 [bufferLock]（行提交与清屏互斥），
     * 任意线程可调。
     *
     * @return false = 当前无进行中的抓取（非 Running 态滚动窗已随终态定格，清显示无意义）
     */
    fun clearDisplay(): Boolean {
        if (_status.value !is LogcatCaptureStatus.Running) return false
        synchronized(bufferLock) {
            displayGeneration++
            // drain channel：清空尚未进入收集协程的在途行（已落盘，仅不上屏，但须计入
            // totalCount——否则全量计数漏账，背离「totalCount 恒与落盘行数一致」）；
            // 已被收集协程取进本地批次的行走 commitBatch 的世代过滤，两条路径同口径；
            // 冲刷信号就地确认（flushPending 的 1s 超时兜底不依赖本路径）
            while (true) {
                when (val event = lineChannel.tryReceive().getOrNull()) {
                    null -> break
                    is LineEvent.FlushSignal -> event.ack.complete(Unit)
                    is LineEvent.Line -> totalCount += 1 // 旧世代在途行：不上屏但计入全量
                }
            }
            ring.clear()
            foldTracker.reset()
            _window.value = LogcatWindow(lines = emptyList(), totalCount = totalCount, droppedCount = droppedCount)
        }
        return true
    }

    /**
     * 标记复现点：把屏上（滚动窗快照）最新的带时间戳行记为查看锚点。锚点跨
     * Stopped 终态保留、下次 [start] 清零——消费面是终态「查看」的初始定位。
     * 多次标记覆盖前标记：复现瞬间是单点事件，用户语义 = 「记住我现在看到的位置」，
     * 无多锚点选择面。
     *
     * 承载形态 = 行首时间戳（[LogcatAnchor.timestamp] 契约）：查看器初始定位的
     * 时间戳档是唯一现成直达消费面；brief 等无时间戳行不承载（行内容在查看器
     * 无定位档，记了无处直达），回扫自动跳过。
     *
     * 锚定源 = 滚动窗回扫（[bufferLock] 下自末行回扫首个形态命中行）：标记语义 =
     * 锚定用户正看到的位置；channel 在途行最多滞后 [AGGREGATION_WINDOW_MS] 提交，
     * 精度口径 = 屏上所见。
     *
     * @return 记入的锚点；null = 非 Running 态，或窗内无带时间戳行可锚定
     *   （清屏后未有新行到达等——拒绝零副作用，宁拒不猜）
     */
    fun markAnchor(): LogcatAnchor? {
        if (_status.value !is LogcatCaptureStatus.Running) return null
        val head = synchronized(bufferLock) {
            ring.asReversed().firstNotNullOfOrNull { ANCHOR_LINE_HEAD.find(it)?.value }
        } ?: return null
        return LogcatAnchor(head, java.time.LocalDate.now().year).also { _anchor.value = it }
    }

    private suspend fun onMatchedLine(line: String, outputFile: File) {
        val matcher = activeMatcher ?: return
        // 武装判定先于过滤（两通道契约见 [arm]）：只读命中、零触碰后续过滤与落盘
        checkArmedHit(line)
        if (!matcher.matches(line)) return
        synchronized(writeLock) {
            if (captureClosed) return
            // 产物编码恒 UTF-8（不随平台默认编码）：logcat 行按管道 UTF-8 解码后落盘，
            // 消费端 log-tools 日志查看器与 F 组文本工具恒按 UTF-8 读；按平台默认编码写
            // 会让 Windows（GBK 默认）产物在查看器与跨机分享时全量乱码。
            val w = writer ?: BufferedWriter(OutputStreamWriter(FileOutputStream(outputFile), Charsets.UTF_8))
                .also { writer = it }
            w.write(line)
            w.newLine()
        }
        val generation = displayGeneration // volatile 读：清屏之后的行用新世代
        // 有界通道 + 可挂起发送：通道满时本协程阻塞，背压经 StreamingShell 读协程
        // 传回 adb 管道——行不丢（totalCount 与落盘行数恒一致），代价是突发期 adb
        // 侧被自然限速。超时兜底防收集协程异常死亡导致读协程永久挂起。
        withTimeoutOrNull(CHANNEL_SEND_TIMEOUT_MS) { lineChannel.send(LineEvent.Line(generation, line)) }
            ?: JLog.w(TAG) { "行通道 30s 未腾出空位（收集协程可能已停止），当前行仅落盘不上屏" }
    }

    /**
     * 武装命中判定（行回调协程调用）：快路径无锁（未武装或未命中零锁开销），命中才
     * 进写锁以当前正则二次判定并原子收网——收网判定以写锁内正则为准（换防并发以
     * 最新正则裁决），并发命中行只认首条。
     */
    private fun checkArmedHit(line: String) {
        if (armedRegex?.containsMatchIn(line) != true) return
        val fire = synchronized(writeLock) {
            (armedRegex?.containsMatchIn(line) == true).also { hit ->
                if (hit) {
                    armedRegex = null
                    _armed.value = false
                }
            }
        }
        if (!fire) return
        val head = ANCHOR_LINE_HEAD.find(line)?.value
        val anchor = if (head != null) {
            LogcatAnchor(head, java.time.LocalDate.now().year).also { _anchor.value = it }
        } else {
            // 命中行无行首时间戳：退回 markAnchor 屏上语义——滚动窗此刻尚未含命中行
            // （批量提交在途），锚定命中前一屏上行，精度口径同手动标记
            markAnchor()
        }
        _armedHit.tryEmit(LogcatArmedHit(line, anchor))
    }

    /**
     * 流终止收尾：冲刷窗口内未提交行 → 关写句柄（置 [captureClosed] 先行，迟到行不再
     * 落盘）→ 落定终态。终态落定 = 句柄已关闭 + 全部命中行已计入 [LogcatWindow.totalCount]。
     * 收尾同时取消武装延迟自动停止倒计时（流终止取消路——进程自行退出后无对象可停，
     * 迟到的 job 不得触达下一轮抓取）。
     *
     * 取消语义（写盘收口不可中断）：本收尾经 [start] 的 finally 到达——scope teardown
     * 恰逢抓取中时运行于已取消协程，冲刷到终态落定全程包 `NonCancellable`：teardown
     * 时刻的落盘完整性（在途行清空 + writer 关闭 + 终态落定）优先于取消响应性；不包
     * 则 [flushPending] 的可取消挂起点（冲刷 ack 等待）抛取消，writer 不关、状态卡
     * Running。收集协程已随 scope 取消时，冲刷走 [flushPending] 的防御 drain 收口
     * （ack 等待 1s 超时后 tryReceive 清空），行为有界不挂死。终态日志在收口段外
     * （边界 = 在途行清空 + writer 关闭 + 状态落定，仅此三事不可中断）。
     */
    private suspend fun finishCapture(outputFile: File, launchFailed: Boolean) {
        val settled = withContext(NonCancellable) {
            flushPending()
            val autoStopped: Boolean
            synchronized(writeLock) {
                captureClosed = true
                writer?.let { runCatching { it.close() } }
                writer = null
                activeMatcher = null
                armedRegex = null
                _armed.value = false
                autoStopJob?.cancel()
                autoStopJob = null
                autoStopped = autoStopPending
            }
            val finalCount = synchronized(bufferLock) { totalCount }
            val file = outputFile.takeIf { finalCount > 0 || (it.isFile && !launchFailed) }
            if (launchFailed && finalCount == 0L) {
                runCatching { outputFile.delete() }
            }
            val message = when {
                launchFailed -> "命令启动失败（adb 路径或设备不可用）"
                autoStopped -> "武装延迟到点，已自动停止抓取"
                else -> "已停止抓取"
            }
            LogcatCaptureStatus.Stopped(file, finalCount, message).also { _status.value = it }
        }
        JLog.d(TAG) { "capture finished: lines=${settled.totalCount}, file=${settled.outputFile?.absolutePath}" }
    }

    /** 收集协程：首行直通 + 窗口批量提交 + 冲刷信号排序（ShellOutputAggregator 同配方） */
    private fun startCollector() {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            while (isActive) {
                val first = lineChannel.receive()
                if (first is LineEvent.FlushSignal) {
                    first.ack.complete(Unit)
                    continue
                }
                val batch = ArrayList<LineEvent.Line>(MAX_BATCH)
                batch.add(first as LineEvent.Line)
                val deadline = System.currentTimeMillis() + AGGREGATION_WINDOW_MS
                while (batch.size < MAX_BATCH) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val next = withTimeoutOrNull(remaining) { lineChannel.receive() } ?: break
                    if (next is LineEvent.FlushSignal) {
                        commitBatch(batch)
                        batch.clear()
                        next.ack.complete(Unit)
                        break
                    }
                    batch.add(next as LineEvent.Line)
                }
                commitBatch(batch)
            }
        }
    }

    /** 批量提交进滚动窗并发布快照（世代号校验：clearDisplay 前的在途行不得复活进滚动窗；
     *  折叠行组随窗增量发布——消费面免主线程全窗正则重扫） */
    private fun commitBatch(lines: List<LineEvent.Line>) {
        if (lines.isEmpty()) return
        synchronized(bufferLock) {
            // 全量计数不吃世代过滤：每条事件行都已先落盘后入 channel，totalCount 恒与落盘行数一致
            totalCount += lines.size
            val gen = displayGeneration
            for (line in lines) {
                if (line.generation == gen) {
                    ring.addLast(line.text)
                    foldTracker.append(line.text)
                }
            }
            val overflow = ring.size - MAX_RING_LINES
            if (overflow > 0) {
                repeat(overflow) { ring.removeFirst() }
                droppedCount += overflow
                foldTracker.evict(overflow)
            }
            _window.value = LogcatWindow(
                lines = ring.toList(),
                totalCount = totalCount,
                droppedCount = droppedCount,
                rows = foldTracker.rows(),
            )
        }
    }

    /** 流终止时冲刷：冲刷信号经 channel 排序（ack 返回即先于信号的行已全部提交） */
    private suspend fun flushPending() {
        val ack = CompletableDeferred<Unit>()
        lineChannel.send(LineEvent.FlushSignal(ack))
        withTimeoutOrNull(1_000) { ack.await() }
        // 防御 drain：收集协程无响应时行不丢（ack 信号本身留在 channel 由防御 drain 确认）
        val batch = ArrayList<LineEvent.Line>(MAX_BATCH)
        while (true) {
            when (val event = lineChannel.tryReceive().getOrNull()) {
                null -> break
                is LineEvent.FlushSignal -> event.ack.complete(Unit)
                is LineEvent.Line -> batch.add(event)
            }
            if (batch.size >= MAX_BATCH) {
                commitBatch(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) commitBatch(batch)
    }
}
