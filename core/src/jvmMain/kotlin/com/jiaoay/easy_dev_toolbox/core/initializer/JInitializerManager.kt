package com.jiaoay.easy_dev_toolbox.core.initializer

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.log.JLogInitializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * 由于总会有一些不需要立即加载的数据, 所以写了这个对象来完成所需的功能,
 *
 * io线程中执行
 *
 * 优先级越高执行越早, 反之越晚（level 数字越小越早）。执行模型由 [JInitializer.isHighLevel]
 * 布尔决定（与 level 数字无关——level 只控制同组内的执行序）：
 * - 高优组（isHighLevel=true）：先于低优组执行；同 level 并发（async），任一失败即中断整组
 *   （后续低优组不再执行——高优失败 = 环境不可用）；
 * - 低优组：按 level 串行、组内也串行（forEach 逐个），相邻有任务的 level 之间等 1s
 *   （维持低优分级错峰的原意）；单个失败只记录，不中断其他。
 */
object JInitializerManager {
    private const val TAG = "AppInitializerManager"

    /**
     * 宿主初始化器注册入口（公开装配 API）：宿主（main 流程）在 startInit 前注册
     * 自身域初始化器（如文件日志恢复）。与默认的 :core 日志引导合并后按 level 调度。
     */
    private val hostInitializers = mutableListOf<JInitializer>()

    fun registerInitializer(initializer: JInitializer) {
        hostInitializers.add(initializer)
    }

    /**
     * 初始化器列表 provider；internal 供 jvmTest 注入。
     * 注意：生产代码不得重新赋值，[resetForTest] 会将其还原为默认值。
     */
    internal var initializersProvider: () -> List<JInitializer> = {
        listOf(JLogInitializer()) + hostInitializers
    }
    private val initializers: List<JInitializer>
        get() = initializersProvider()

    /**
     * 初始化执行调度器；internal 供 jvmTest 注入 test dispatcher 以使用虚拟时间。
     * 注意：生产代码不得重新赋值。
     */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val isInit: AtomicBoolean = AtomicBoolean(false)

    val initStateFlow: MutableStateFlow<InitResult?> = MutableStateFlow(null)

    /**
     * 启动耗时观测面（纯观测，不参与 [initStateFlow] 的成败语义，零执行行为差异）：
     * 已完成初始化器的耗时记录，按组聚合发射，恒先于同阶段 [initStateFlow] 写入——
     * - 高优组结束后首次发射（此时低优组尚未执行）；
     * - 低优组结束后终态发射（高优 + 低优全量记录）。
     * 高优组失败短路时仍发射（失败排障更需要耗时证据），但仅含已被 await 的记录，
     * 被短路时仍在跑的并发兄弟项不补记。低优相邻级别间的 1 秒错峰 delay 不计入
     * [InitializerTiming.durationNanos]——耗时只度量 init() 本体。
     */
    val initTimingsFlow: MutableStateFlow<List<InitializerTiming>> = MutableStateFlow(emptyList())

    /** 仅供 jvmTest 重置幂等标记与状态流并还原注入项 */
    internal fun resetForTest() {
        initializersProvider = { listOf(JLogInitializer()) }
        ioDispatcher = Dispatchers.IO
        isInit.set(false)
        initStateFlow.value = null
        initTimingsFlow.value = emptyList()
    }

    suspend fun startInit() = coroutineScope {
        JLog.d(TAG) { "startInit: " }
        val list = initializers

        val highLevelMap = list.filter {
            it.isHighLevel
        }.sortByLevel()

        val lowLevelMap = list.filter {
            it.isHighLevel.not()
        }.sortByLevel()

        withContext(ioDispatcher) {
            if (isInit.getAndSet(true)) {
                return@withContext
            }
            // 每次实际执行从空表起步，防任何前置状态残留进观测面
            initTimingsFlow.value = emptyList()

            val highLevelRun = runInitTask(
                initializerMap = highLevelMap,
                isHighLevel = true,
            ) { level, initializers ->
                highLevelTaskRun(
                    level = level,
                    initializers = initializers,
                )
            }

            publishTimings(group = "high", timings = highLevelRun.timings)

            if (highLevelRun.failedList.isNotEmpty()) {
                initStateFlow.value = InitResult.Failed(
                    isHighLevel = true,
                    failedList = highLevelRun.failedList,
                )
                return@withContext
            }
            initStateFlow.value = InitResult.Success(isHighLevel = true)

            val lowLevelRun = runInitTask(
                initializerMap = lowLevelMap,
                isHighLevel = false,
            ) { level, initializers ->
                lowLevelTaskRun(
                    level = level,
                    initializers = initializers,
                )
            }

            publishTimings(
                group = "low",
                timings = highLevelRun.timings + lowLevelRun.timings,
            )

            if (lowLevelRun.failedList.isEmpty()) {
                initStateFlow.value = InitResult.Success(isHighLevel = false)
            } else {
                initStateFlow.value = InitResult.Failed(
                    isHighLevel = false,
                    failedList = lowLevelRun.failedList,
                )
            }
        }
    }

    private suspend fun runInitTask(
        initializerMap: Map<Int, List<JInitializer>>,
        isHighLevel: Boolean,
        task: suspend (level: Int, initializers: List<JInitializer>) -> GroupRunResult,
    ): GroupRunResult {
        val failedList: MutableList<Class<*>> = ArrayList()
        val timings: MutableList<InitializerTiming> = ArrayList()
        var hasRunTask = false
        for (level in 1..10) {
            val initializers: List<JInitializer>? = initializerMap[level]
            if (initializers == null) {
                // 空级别直接跳过，不产生固定时长的空转延迟
                continue
            }
            // 仅在存在待执行任务的相邻级别之间施加间隔等待（维持低优任务分级串行的原意）
            if (isHighLevel.not() && hasRunTask) {
                delay(1_000L.milliseconds)
            }
            val result = task.invoke(level, initializers)
            if (isHighLevel && result.failedList.isNotEmpty()) {
                return GroupRunResult(
                    failedList = result.failedList,
                    timings = timings + result.timings,
                )
            } else {
                failedList.addAll(result.failedList)
                timings.addAll(result.timings)
            }
            hasRunTask = true
        }
        return GroupRunResult(failedList = failedList, timings = timings)
    }

    private suspend fun lowLevelTaskRun(
        level: Int,
        initializers: List<JInitializer>,
    ): GroupRunResult {
        val failedList: MutableList<Class<*>> = ArrayList()
        val timings: MutableList<InitializerTiming> = ArrayList()
        initializers.forEach {
            val startNanos = System.nanoTime()
            val isSuccess = try {
                it.init()
            } catch (e: Throwable) {
                JLog.e(TAG, e) { "low level initializer failed: ${it.javaClass.name}" }
                false
            }
            timings.add(
                InitializerTiming(
                    initializerClass = it.javaClass,
                    isHighLevel = false,
                    level = level,
                    isSuccess = isSuccess,
                    durationNanos = System.nanoTime() - startNanos,
                ),
            )
            if (isSuccess.not()) {
                failedList.add(it.javaClass)
            }
        }
        return GroupRunResult(failedList = failedList, timings = timings)
    }

    private suspend fun highLevelTaskRun(
        level: Int,
        initializers: List<JInitializer>,
    ) = coroutineScope {
        val initializerDeferredList: MutableList<Deferred<Pair<InitializerTiming, Class<*>?>>> = ArrayList()
        initializers.forEach {
            val initDeferred: Deferred<Pair<InitializerTiming, Class<*>?>> = async {
                val startNanos = System.nanoTime()
                val failedClass: Class<*>? = try {
                    if (it.init()) null else it.javaClass
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 高优初始化器异常不再在组合期直接崩溃，转为失败结果（InitFailedView 可达）
                    JLog.e(TAG, e) { "high level initializer failed: ${it.javaClass.name}" }
                    it.javaClass
                }
                InitializerTiming(
                    initializerClass = it.javaClass,
                    isHighLevel = true,
                    level = level,
                    isSuccess = failedClass == null,
                    durationNanos = System.nanoTime() - startNanos,
                ) to failedClass
            }
            initializerDeferredList.add(initDeferred)
        }
        val timings: MutableList<InitializerTiming> = ArrayList()
        initializerDeferredList.forEach {
            val (timing, failedClass) = it.await()
            timings.add(timing)
            if (failedClass != null) {
                return@coroutineScope GroupRunResult(
                    failedList = listOf(failedClass),
                    timings = timings,
                )
            }
        }
        return@coroutineScope GroupRunResult(failedList = emptyList(), timings = timings)
    }

    /**
     * 耗时观测面唯一发射点：写 [initTimingsFlow] 并逐条打 JLog——
     * 日志是本轮的既有可读露出面（UI 露出面另立项），打点不参与成败语义。
     */
    private fun publishTimings(group: String, timings: List<InitializerTiming>) {
        initTimingsFlow.value = timings
        val totalNanos = timings.fold(initial = 0L) { acc, timing ->
            acc + timing.durationNanos
        }
        JLog.d(TAG) {
            String.format(
                Locale.ROOT,
                "init timing [%s] total %.3f ms across %d initializer(s)",
                group,
                totalNanos / 1_000_000.0,
                timings.size,
            )
        }
        timings.forEach { timing ->
            JLog.d(TAG) {
                String.format(
                    Locale.ROOT,
                    "init timing [%s] %s took %.3f ms (level=%d, success=%b)",
                    group,
                    timing.initializerClass.name,
                    timing.durationNanos / 1_000_000.0,
                    timing.level,
                    timing.isSuccess,
                )
            }
        }
    }

    private fun List<JInitializer>.sortByLevel(): Map<Int, List<JInitializer>> {
        if (isEmpty()) {
            return emptyMap()
        }

        val map: MutableMap<Int, MutableList<JInitializer>> = HashMap()

        forEach { initializer ->
            val level = initializer.level()
            map[level]?.add(initializer) ?: let {
                map[level] = mutableListOf(initializer)
            }
        }

        return map
    }
}

/** 单组执行结果的内部聚合体：failedList 语义与 [InitResult.Failed] 同源，timings 供耗时观测面发射 */
private data class GroupRunResult(
    val failedList: List<Class<*>>,
    val timings: List<InitializerTiming>,
)
