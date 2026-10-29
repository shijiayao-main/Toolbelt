// observe：UI 日志台的环形缓冲实现（有界内存链路第 5 行）。
// 接口契约在 `ObserveSeams.kt` 的 [LogBus]（环形 + version + 覆盖最旧 + droppedTotal 五条）。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.PrintStreamBridgeLog
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * [LogBus] 的零锁环形实现（`ObserveSeams.kt` 的 5 条契约逐条落地）。
 *
 * ## 契约对照
 * | # | 契约 | 本实现的落点 |
 * |---|---|---|
 * | 1 | 环形 + version，容量 1000，满则**覆盖最旧** + `droppedTotal` | [ring] 按 `writeIndex % capacity` 定位；覆盖时 [filled] 已满 ⇒ `droppedTotal++` |
 * | 2 | version 单调递增（Compose `derivedStateOf` 依赖） | [versionCounter] 每次 publish / clear 都 +1，**clear 不归零** |
 * | 3 | `publish` O(1) 非挂起 | 一次 `getAndIncrement` + 一次 `getAndSet` + 一次 `incrementAndGet`，**无遍历、无分配**（除 LogLine 本身） |
 * | 4 | **零锁** | 全部 `java.util.concurrent.atomic`（CAS/volatile 槽位），**零 `synchronized`、零 `Mutex`** |
 * | 5 | `snapshot()` 返回不可变副本 | 每次新建 `ArrayList` 并按 `seq` 升序排序后返回（O(capacity)，UI 以 ticker 频率调用，非热路径） |
 *
 * ## 与契约措辞的一处实现级偏差（已在报告登记）
 * 契约写的是「环形 `ArrayDeque`」，本实现用 [AtomicReferenceArray]。理由：`ArrayDeque` **不是**线程安全的，
 * 要在多协程下用它就必须加监视器锁或 `Mutex`，这与契约第 4 条「零锁」直接冲突。
 * `AtomicReferenceArray` 是「环形数组」的零锁等价物——每个槽位是独立的 volatile 引用，
 * 写者 `getAndSet` 原子替换、读者 `get` 原子读取，语义与 ArrayDeque 的 `addLast`/`removeFirst` 一致。
 *
 * ## 为什么 snapshot 靠 `seq` 排序而不是靠槽位顺序
 * 并发写入下 `writeIndex` 与槽位内容不是原子配对的：读者可能看到「新槽位已写入、旧槽位尚未写入」的中间态。
 * 由于 [LogLine.seq] 由本类**单调发放**，按 seq 升序排序即可得到确定的时间顺序，与槽位物理位置解耦。
 *
 * @param capacity 环形容量（默认 1000，来自 `observability.logConsole.capacity`）
 */
class RingLogBus(
    override val capacity: Int = DEFAULT_CAPACITY,
) : LogBus {

    private val slots: Int = capacity.coerceAtLeast(1)
    private val ring = AtomicReferenceArray<LogLine?>(slots)

    /** 下一个写入槽位的游标（只增不减，取模定位；`clear()` 也**不**归零，避免与在途 publish 抢同一槽位）。 */
    private val writeIndex = AtomicInteger(0)

    /** 已填充槽位数（`<= slots`；达到 slots 后再写入即为「覆盖最旧」）。 */
    private val filled = AtomicInteger(0)

    private val seqCounter = AtomicLong(0)
    private val versionCounter = AtomicLong(0)
    private val publishedCounter = AtomicLong(0)
    private val droppedCounter = AtomicLong(0)

    override val version: Long get() = versionCounter.get()
    override val droppedTotal: Long get() = droppedCounter.get()
    override val publishedTotal: Long get() = publishedCounter.get()
    override val size: Int get() = filled.get().coerceAtMost(slots)

    /**
     * 发布一行。🔴 O(1) 非挂起、恒不阻塞、恒不抛。
     *
     * [LogLine.seq] 由本类**统一发放**（传入值被覆盖）：seq 是 [snapshot] 排序的唯一依据，
     * 若允许调用方自带 seq，两个调用方给出相同/倒序的 seq 就会让 UI 时间线错乱。
     */
    override fun publish(line: LogLine) {
        val seq = seqCounter.incrementAndGet()
        val ordered = if (line.seq == seq) line else line.copy(seq = seq)
        val index = writeIndex.getAndIncrement().mod(slots)
        val previous = ring.getAndSet(index, ordered)
        publishedCounter.incrementAndGet()
        // 🔴 覆盖最旧 = 一次丢弃，必须计数。判据是「该槽位原本有内容」——这就是覆盖的精确信号。
        if (previous != null) droppedCounter.incrementAndGet()
        if (filled.get() < slots) filled.incrementAndGet()
        versionCounter.incrementAndGet()
    }

    /** 便捷发布（造 [LogLine]；`atMillis` 取当前时钟）。 */
    override fun publish(level: String, tag: String, message: String) {
        publish(LogLine(seq = 0L, level = level, tag = tag, message = message, atMillis = System.currentTimeMillis()))
    }

    /** 当前缓冲的不可变快照（按 [LogLine.seq] 升序）。 */
    override fun snapshot(): List<LogLine> {
        val count = size
        if (count == 0) return emptyList()
        val collected = ArrayList<LogLine>(count)
        var i = 0
        while (i < slots) {
            ring.get(i)?.let { collected.add(it) }
            i++
        }
        collected.sortBy { it.seq }
        // 🔴 契约第 5 条：返回**不可变**副本，UI 直接渲染不会读到正在被覆盖的行
        return collected.toList()
    }

    /**
     * 快照版本号（= 已发布总行数，单调递增）。
     *    UI 轮询侧用它做「有无新行」短路：版本未动 ⇒ 不做全量收集 + 排序
     *    （1000 槽收集 + sortBy 在空闲时也按 10Hz 跑，白烧 CPU）。语义：两次调用间
     *    `version` 相同 ⇒ 中间必然无新发布行（clear 也会使 published 不减，但 lines 由
     *    UI 侧自行清理——本短路只关心「有没有新内容要拉」）。
     */
    override fun snapshotVersion(): Long = publishedCounter.get()

    /**
     * 清空（切换 appId / stop 时）。幂等；[version] 与 [droppedTotal] **不**归零
     * （version 反而 +1，让 UI 的 `derivedStateOf` 察觉「清空过」并重组一次）。
     */
    override fun clear() {
        var i = 0
        while (i < slots) {
            ring.set(i, null)
            i++
        }
        filled.set(0)
        versionCounter.incrementAndGet()
    }

    override fun toString(): String =
        "RingLogBus(size=$size/$slots, version=$version, published=$publishedTotal, dropped=$droppedTotal)"

    companion object {
        /** 环形容量默认值（方案表逐字：1000；对应 `observability.logConsole.capacity`）。 */
        const val DEFAULT_CAPACITY: Int = 1_000

        /** 级别字面值（枚举名逐字：DEBUG/INFO/WARN/ERROR）。 */
        const val LEVEL_DEBUG: String = "DEBUG"
        const val LEVEL_INFO: String = "INFO"
        const val LEVEL_WARN: String = "WARN"
        const val LEVEL_ERROR: String = "ERROR"

        /** [BridgeLog] 四档 → [LogLine.level] 字面值（🔴 穷尽 `when` 无 `else`）。 */
        fun levelLiteral(level: PrintStreamBridgeLogLevel): String = when (level) {
            PrintStreamBridgeLogLevel.DEBUG -> LEVEL_DEBUG
            PrintStreamBridgeLogLevel.INFO -> LEVEL_INFO
            PrintStreamBridgeLogLevel.WARN -> LEVEL_WARN
            PrintStreamBridgeLogLevel.ERROR -> LEVEL_ERROR
        }
    }
}

/**
 * [PrintStreamBridgeLog.Level] 的类型别名引用位。
 *
 * 为什么需要：`Level` 是 [PrintStreamBridgeLog] 的**嵌套** enum（根包 `BridgeLog.kt:35`），
 * 在 observe 包里直接写 `PrintStreamBridgeLog.Level` 会让 [RingLogBus.levelLiteral] 的签名很长且易错；
 * 这里用 `typealias` 起一个短名，**不新增任何类型**、不改变 [Level] 的定义位置。
 */
typealias PrintStreamBridgeLogLevel = PrintStreamBridgeLog.Level
