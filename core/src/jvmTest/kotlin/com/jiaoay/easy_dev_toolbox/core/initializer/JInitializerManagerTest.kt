package com.jiaoay.easy_dev_toolbox.core.initializer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D3 回归（虚拟时间）：
 * - 仅高优 initializer 时 startInit 不产生空级别固定 delay（原为 10 个空级别各 1s 空转）；
 * - 低优多级别间仅保留 1 秒间隔（有任务级别之间）；
 * - 抛异常的高优 initializer 被 capture 为 Failed 结果，不向调用方传播。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JInitializerManagerTest {

    @AfterTest
    fun tearDown() {
        JInitializerManager.resetForTest()
    }

    private class FakeInitializer(
        override val isHighLevel: Boolean,
        private val fakeLevel: Int,
        private val result: Boolean = true,
        private val error: Throwable? = null,
    ) : JInitializer {
        override fun level(): Int = fakeLevel
        override suspend fun init(): Boolean {
            error?.let { throw it }
            return result
        }
    }

    @Test
    fun `startInit with only high level initializer produces no idle delay`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        JInitializerManager.ioDispatcher = dispatcher
        JInitializerManager.initializersProvider = {
            listOf(FakeInitializer(isHighLevel = true, fakeLevel = 1))
        }

        JInitializerManager.startInit()

        // 虚拟时间零推进：不含空级别固定 delay（旧逻辑低优空链路会空转 10 秒）
        assertEquals(0L, testScheduler.currentTime, "不应存在空级别空转 delay")
        val state = JInitializerManager.initStateFlow.value
        assertIs<InitResult.Success>(state)
    }

    @Test
    fun `low level gap only between levels carrying tasks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        JInitializerManager.ioDispatcher = dispatcher
        // 低优 level 1 与 level 3 有任务，level 2 为空：仅 1 次间隔（1s），空级别不等待
        JInitializerManager.initializersProvider = {
            listOf(
                FakeInitializer(isHighLevel = false, fakeLevel = 1),
                FakeInitializer(isHighLevel = false, fakeLevel = 3),
            )
        }

        JInitializerManager.startInit()

        // 旧逻辑：level1 后 delay + level2 空 delay + level3 后 delay = 3000ms；
        // 新逻辑：仅有任务级别之间 1 次间隔 = 1000ms
        assertEquals(1000L, testScheduler.currentTime, "仅应在有任务级别之间保留 1 秒间隔")
        val state = JInitializerManager.initStateFlow.value
        assertIs<InitResult.Success>(state)
    }

    @Test
    fun `throwing high level initializer yields Failed result without propagation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        JInitializerManager.ioDispatcher = dispatcher
        JInitializerManager.initializersProvider = {
            listOf(FakeInitializer(isHighLevel = true, fakeLevel = 1, error = IllegalStateException("init-boom")))
        }

        // 不应向调用方抛出异常（旧逻辑经组合期 runBlocking 直接崩溃）
        JInitializerManager.startInit()

        val state = JInitializerManager.initStateFlow.value
        val failed = assertIs<InitResult.Failed>(state, "initStateFlow 应变为 Failed")
        assertTrue(failed.isHighLevel)
        assertEquals(listOf(FakeInitializer::class.java), failed.failedList, "failedList 应含该 initializer 类")
    }

    @Test
    fun `high level failure short-circuits low level execution`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        JInitializerManager.ioDispatcher = dispatcher
        val executed = mutableListOf<String>()
        JInitializerManager.initializersProvider = {
            listOf(
                FakeInitializer(isHighLevel = true, fakeLevel = 1, error = IllegalStateException("boom")),
                object : JInitializer {
                    override val isHighLevel: Boolean = false
                    override fun level(): Int = 2
                    override suspend fun init(): Boolean {
                        executed.add("low-level-ran")
                        return true
                    }
                },
            )
        }

        JInitializerManager.startInit()

        assertTrue(executed.isEmpty(), "高优失败后低优任务不应执行")
        assertIs<InitResult.Failed>(JInitializerManager.initStateFlow.value)
    }

    @Test
    fun `timing records cover every initializer including failures`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        JInitializerManager.ioDispatcher = dispatcher
        JInitializerManager.initializersProvider = {
            listOf(
                FakeInitializer(isHighLevel = true, fakeLevel = 1),
                FakeInitializer(isHighLevel = false, fakeLevel = 1, result = false),
            )
        }

        JInitializerManager.startInit()

        val timings = JInitializerManager.initTimingsFlow.value
        assertEquals(2, timings.size, "每个初始化器都应有耗时记录")
        val high = timings.single { it.isHighLevel }
        assertEquals(FakeInitializer::class.java, high.initializerClass)
        assertEquals(1, high.level)
        assertTrue(high.isSuccess)
        assertTrue(high.durationNanos >= 0, "不断言具体毫秒值，只断言非负有效计时")
        val low = timings.single { it.isHighLevel.not() }
        assertEquals(FakeInitializer::class.java, low.initializerClass)
        assertEquals(1, low.level)
        assertFalse(low.isSuccess, "返回 false 的失败项也应记录耗时且 isSuccess=false")
        assertTrue(low.durationNanos >= 0)
        // 失败语义零回归：低优失败不影响既有终态
        val state = assertIs<InitResult.Failed>(JInitializerManager.initStateFlow.value)
        assertFalse(state.isHighLevel)
    }

    @Test
    fun `timing emission aligned with init state stages`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        JInitializerManager.ioDispatcher = dispatcher
        JInitializerManager.initializersProvider = {
            listOf(
                FakeInitializer(isHighLevel = true, fakeLevel = 1),
                FakeInitializer(isHighLevel = false, fakeLevel = 1),
                FakeInitializer(isHighLevel = false, fakeLevel = 2),
            )
        }

        val job = launch { JInitializerManager.startInit() }
        // 推进虚拟时间到高优组完成、低优组仍在 1 秒错峰 delay 中间
        advanceTimeBy(500)

        val midTimings = JInitializerManager.initTimingsFlow.value
        assertEquals(1, midTimings.size, "高优阶段只应发射高优记录")
        assertTrue(midTimings.single().isHighLevel)
        val midState = assertIs<InitResult.Success>(JInitializerManager.initStateFlow.value)
        assertTrue(midState.isHighLevel, "中间点应为高优成功的分阶段态")

        advanceUntilIdle()
        job.join()
        assertEquals(3, JInitializerManager.initTimingsFlow.value.size, "终态应含高优+低优全量记录")
        assertIs<InitResult.Success>(JInitializerManager.initStateFlow.value)
    }

    @Test
    fun `high level failure still publishes timings for awaited initializers`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        JInitializerManager.ioDispatcher = dispatcher
        JInitializerManager.initializersProvider = {
            listOf(
                FakeInitializer(isHighLevel = true, fakeLevel = 2),
                FakeInitializer(isHighLevel = true, fakeLevel = 2, result = false),
                FakeInitializer(isHighLevel = false, fakeLevel = 1),
            )
        }

        JInitializerManager.startInit()

        val timings = JInitializerManager.initTimingsFlow.value
        assertEquals(2, timings.size, "短路前已被 await 的两项应记录，低优组不执行故无低优记录")
        assertTrue(timings.all { it.isHighLevel })
        assertTrue(timings[0].isSuccess)
        assertFalse(timings[1].isSuccess, "失败项自身耗时也应被记录")
        assertIs<InitResult.Failed>(JInitializerManager.initStateFlow.value)
    }
}
