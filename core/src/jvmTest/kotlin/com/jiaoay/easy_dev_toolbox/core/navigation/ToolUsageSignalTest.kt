package com.jiaoay.easy_dev_toolbox.core.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 使用信号契约单测（反馈 4 口径反转 2026-09-23——ToolUsageSignal 的 round-trip 锚）。
 *
 * 🔴 :core 引不到宿主 ToolPage 枚举（依赖方向 composeApp → core），故本测试只钉
 * 信号流自身的投递语义；「pageId 与枚举 name 一致」由宿主侧
 * PageUsageTrackerTest 的 round-trip 用例（report → 订阅链计数）背书。
 *
 * 🔴 不用 runTest：全量跑中本类曾被 GlobalUncaughtExceptionHandlerTest 异步泄漏的
 *    「coroutine-boom」撞上 runTest 的未捕获异常追踪（UncaughtExceptionsBeforeTest，
 *    时序性串扰）。runBlocking + Unconfined 是 SharedFlow 测试的确定性配方——
 *    Unconfined collector 在 launch 处同步订阅、tryEmit 投递同步直达，零调度时序。
 */
class ToolUsageSignalTest {

    @Test
    fun `report emits pageId to active collector`() = runBlocking {
        val received = mutableListOf<String>()
        val job = launch(Dispatchers.Unconfined) {
            ToolUsageSignal.signals.collect { received.add(it) }
        }
        // Unconfined：collector 已同步挂上（launch 返回即完成订阅）
        ToolUsageSignal.report(ToolUsageSignal.PageId.XLOG_DECODER)
        ToolUsageSignal.report(ToolUsageSignal.PageId.SHELL_TOOLS)
        ToolUsageSignal.report(ToolUsageSignal.PageId.TIMESTAMP)
        job.cancel()
        assertEquals(
            listOf("XLogDecoder", "ShellTools", "Timestamp"),
            received,
            "report → 订阅者按序收到 pageId（round-trip 投递契约）",
        )
    }

    @Test
    fun `report without collector is silently dropped`() = runBlocking {
        // 无订阅者时 tryEmit 落空不抛——丢流无害契约：
        // 计数是 UX 排序数据非关键账目；replay=0 保证后续订阅者不收历史
        ToolUsageSignal.report(ToolUsageSignal.PageId.DISTILL)

        var received: String? = null
        val job = launch(Dispatchers.Unconfined) {
            ToolUsageSignal.signals.collect { received = it }
        }
        // 只有订阅挂上之后的新信号才被收到（历史不回放）
        ToolUsageSignal.report(ToolUsageSignal.PageId.FLOW)
        job.cancel()
        assertEquals(
            "Flow",
            received,
            "replay=0：订阅前的历史信号不回放，订阅者只收新信号；无订阅者 report 不抛",
        )
    }

    @Test
    fun `page id constants match documented enum name spellings`() {
        // 防拼写漂移的静态锚：常量集即宿主 ToolPage 枚举 name 的子集投影——
        // 结构页（Setting/AppCenter/Report/Debug）刻意缺席（不计数语义）
        val ids = listOf(
            ToolUsageSignal.PageId.XLOG_DECODER,
            ToolUsageSignal.PageId.LOG_TAG_FILTER,
            ToolUsageSignal.PageId.FILE_SPLITTER,
            ToolUsageSignal.PageId.SHELL_TOOLS,
            ToolUsageSignal.PageId.TIMESTAMP,
            ToolUsageSignal.PageId.ARCHIVE,
            ToolUsageSignal.PageId.LARK_BRIDGE,
            ToolUsageSignal.PageId.MCP_EXPOSE,
            ToolUsageSignal.PageId.AI_SETTINGS,
            ToolUsageSignal.PageId.DISTILL,
            ToolUsageSignal.PageId.LOG_WORKBENCH,
            ToolUsageSignal.PageId.VOICE_TRANSCRIBE,
            ToolUsageSignal.PageId.FLOW,
        )
        assertEquals(ids.size, ids.toSet().size, "PageId 常量不得重复")
        assertTrue(ids.all { it.first().isUpperCase() }, "PageId 常量 = 枚举 name 拼写（大驼峰约定）")
    }
}
