// 🔴 T11 可观测性缺陷修复（设计裁定 2026-09-08 · 方案 b）：`--verbose` 把桥接侧 console 下限降到 DEBUG 的单测。
// 钉死四件事：
//   ① [consoleLevelFor] 纯函数：verbose ⇒ DEBUG、非 verbose ⇒ INFO；
//   ② 🔴 不加 `--verbose`（INFO 下限）时 DEBUG 被吞、INFO 及以上照出（**回归保护，最重要**：否则控制台被刷屏）；
//   ③ 加 `--verbose`（DEBUG 下限）时既有拒绝 DEBUG 浮现（补上第五轮真机「4 条被拒消息零痕迹」的盲区）；
//   ④ App 侧（`consoleLog=false` ⇒ 无控制台委托）不受本次改动影响。
package com.jiaoay.lark.bridge.cli

import com.jiaoay.lark.bridge.PrintStreamBridgeLog
import com.jiaoay.lark.bridge.observe.ObservingBridgeLog
import com.jiaoay.lark.bridge.observe.RingLogBus
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerboseConsoleLevelTest {

    /** 按给定 console 下限建一个把控制台输出捕获到 [ByteArrayOutputStream] 的双写日志（对齐生产 withConsole 形态）。 */
    private fun captureConsole(level: PrintStreamBridgeLog.Level): Pair<ByteArrayOutputStream, ObservingBridgeLog> {
        val buf = ByteArrayOutputStream()
        val out = PrintStream(buf, true, Charsets.UTF_8)
        val log = ObservingBridgeLog.withConsole(RingLogBus(), out = out, consoleMinLevel = level)
        return buf to log
    }

    @Test
    fun `consoleLevelFor——verbose 翻译成 DEBUG、非 verbose 维持 INFO`() {
        assertEquals(
            PrintStreamBridgeLog.Level.DEBUG,
            consoleLevelFor(verbose = true),
            "--verbose ⇒ 桥接侧 console 下限降到 DEBUG",
        )
        assertEquals(
            PrintStreamBridgeLog.Level.INFO,
            consoleLevelFor(verbose = false),
            "🔴 不加 --verbose ⇒ 维持 INFO（行为逐字不变，控制台绝不被 DEBUG 刷屏）",
        )
    }

    @Test
    fun `不加 verbose（INFO 下限）——DEBUG 被吞、INFO 及以上照出（回归保护）`() {
        val (buf, log) = captureConsole(consoleLevelFor(verbose = false))
        log.debug("策略门控拒绝 reason=group_require_mention chat=oc_a05****c230 sender=ou_048****003c")
        log.info("intake 单消费者协程已启动")
        log.warn("连接抖动")
        val text = buf.toString(Charsets.UTF_8)
        assertFalse(text.contains("策略门控拒绝"), "🔴 INFO 下限下 DEBUG 拒绝诊断必须被吞（否则不加 --verbose 也刷屏 = 回归）")
        assertFalse(text.contains("[DEBUG]"), "INFO 下限下不得出现任何 [DEBUG] 行")
        assertTrue(text.contains("intake 单消费者协程已启动"), "INFO 照出")
        assertTrue(text.contains("连接抖动"), "WARN 照出")
    }

    @Test
    fun `加 verbose（DEBUG 下限）——既有拒绝 DEBUG 浮现`() {
        val (buf, log) = captureConsole(consoleLevelFor(verbose = true))
        log.debug("策略门控拒绝 reason=group_require_mention chat=oc_a05****c230 sender=ou_048****003c")
        log.info("intake 单消费者协程已启动")
        val text = buf.toString(Charsets.UTF_8)
        assertTrue(text.contains("[DEBUG]"), "🔴 DEBUG 下限下 DEBUG 行必须浮现")
        assertTrue(text.contains("策略门控拒绝"), "既有拒绝诊断在 --verbose 下可见（T11 盲区补上）")
        assertTrue(text.contains("intake 单消费者协程已启动"), "INFO 仍照出")
    }

    @Test
    fun `App 侧 consoleLog=false 形态——无控制台委托，DEBUG 只进日志台不落控制台（不受本次改动影响）`() {
        // 🔴 BridgeAssembler 的 consoleLog=false 分支逐字：`ObservingBridgeLog(logBus, logConsoleEnabled = ...)`
        //    ⇒ delegate 缺省 BridgeLog.Silent，consoleMinLevel 参数**根本不参与构造**（它只喂 consoleLog=true 的 withConsole）。
        //    故 App 侧不存在控制台输出路径，本次 --verbose→consoleMinLevel 改动对 App 零影响。
        val bus = RingLogBus()
        val appLog = ObservingBridgeLog(bus, logConsoleEnabled = { true })
        appLog.debug("策略门控拒绝 reason=group_require_mention chat=oc_a05****c230 sender=ou_048****003c")
        appLog.info("app info")
        // App 侧 delegate=Silent ⇒ 不写任何 PrintStream；但日志台（busMinLevel 缺省 DEBUG）照常收到 DEBUG 与 INFO（观测未退化）
        val snapshot = bus.snapshot()
        assertTrue(snapshot.any { it.message.startsWith("策略门控拒绝") }, "App 侧 DEBUG 仍进日志台（UI 可回看）")
        assertTrue(snapshot.any { it.message == "app info" }, "App 侧 INFO 仍进日志台")

        // 🔴 对照：同一条 DEBUG 走 consoleLog=true 且 DEBUG 下限时**才**落控制台 ⇒ 差异只在「有无 console 委托」，App 侧无此委托
        val (buf, consoleLog) = captureConsole(PrintStreamBridgeLog.Level.DEBUG)
        consoleLog.debug("策略门控拒绝 reason=group_require_mention")
        assertTrue(buf.toString(Charsets.UTF_8).contains("策略门控拒绝"), "存在 console 委托时才落控制台")
    }
}
