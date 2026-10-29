package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell
import com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * 控制回传真机 E2E runner（门控：`SCRCPY_E2E` 未设置时 assume 跳过，本地/CI 恒绿）。
 * 零注入接缝驱动生产引擎本体：出图 → 控制通道注入（触摸/滚动/按键/剪贴板）→
 * 设备端到达实证（`dumpsys input` 的 RecentQueue——设备端 InputDispatcher 的事实行，
 * 含注入坐标与 keyCode）→ 剪贴板跨会话回读（ack + 重启会话的启动快照）→ 生命周期收口。
 *
 * 无害注入纪律：触摸点取屏幕中心空白区（只验到达不验 UI 反应）；按键只用音量
 * ±（成对注入抵消音量变化）；不注入返回/主页等导航键。
 *
 * 环境变量：`SCRCPY_SERIAL`（默认 adb devices 首个 device）、`SCRCPY_ADB`（默认裸 adb）。
 * 判据核对表见本仓投屏 spike 判据档案（控制面追加节）。
 */
class ScreencastControlE2eRunner {

    @Test
    fun `device e2e - control injections reach device input dispatcher and clipboard`() {
        assumeTrue(
            "SCRCPY_E2E 未设置——真机 E2E 按门控执行，本地默认跳过",
            System.getenv("SCRCPY_E2E") != null,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ScreencastEngine(scope)
        try {
            runBlocking {
                val serial = System.getenv("SCRCPY_SERIAL") ?: firstDeviceSerial()
                val adbPath = System.getenv("SCRCPY_ADB") ?: ""
                val adb = SimpleShellScrcpyAdbGateway(adbPath = adbPath, serial = serial)

                // 会话一：出图 + 全型注入
                assertTrue(engine.start(adbPath, serial), "引擎必须接受启动")
                val streaming = withTimeout(30_000) {
                    engine.status.first { it is ScreencastStatus.Streaming }
                } as ScreencastStatus.Streaming
                withTimeout(15_000) { engine.frame.first { it != null } }
                println("[e2e-ctrl] Streaming: '${streaming.deviceName}' ${streaming.width}x${streaming.height}")
                val screenW = streaming.width
                val screenH = streaming.height
                // 注入点避开屏幕正中（存量测试残留事件的常见坐标），保持空白区量级
                val tapX = screenW / 2
                val tapY = screenH / 2 + 60

                // ① 触摸注入（点按中心空白区：DOWN + UP）→ dumpsys RecentQueue 必须出现
                //    携带注入坐标的新鲜 MotionEvent
                val touchBefore = recentEventLines(adb)
                assertTrue(engine.controlInjectTouch(ScrcpyControlProtocol.MOTION_ACTION_DOWN, tapX, tapY, screenW, screenH))
                assertTrue(engine.controlInjectTouch(ScrcpyControlProtocol.MOTION_ACTION_UP, tapX, tapY, screenW, screenH))
                val touchEvidence = awaitFreshEvents(adb, touchBefore) { line ->
                    "MotionEvent" in line && "($tapX.0, $tapY.0)" in line
                }
                assertTrue(
                    touchEvidence != null,
                    "触摸注入未到达设备端 InputDispatcher（期望坐标 ($tapX.0, $tapY.0) 的新鲜 MotionEvent）",
                )
                println("[e2e-ctrl] 触摸到达实证: ${touchEvidence!!.take(120)}…")

                // ② 滚动注入（中心一格向上）→ 新鲜 MotionEvent（action=SCROLL）到达
                val scrollBefore = recentEventLines(adb)
                assertTrue(engine.controlInjectScroll(tapX, tapY, screenW, screenH, 0f, 1f))
                val scrollEvidence = awaitFreshEvents(adb, scrollBefore) { line ->
                    "MotionEvent" in line && "action=SCROLL" in line
                }
                assertTrue(scrollEvidence != null, "滚动注入未到达设备端 InputDispatcher（期望新鲜 SCROLL MotionEvent）")
                println("[e2e-ctrl] 滚动到达实证: ${scrollEvidence!!.take(120)}…")

                // ③ 按键注入（音量+ 与 音量- 成对——音量净变化为零）；导航键不注入（无害纪律）
                val keyBefore = recentEventLines(adb)
                assertTrue(engine.controlInjectKey(24)) // KEYCODE_VOLUME_UP
                assertTrue(engine.controlInjectKey(25)) // KEYCODE_VOLUME_DOWN
                val upEvidence = awaitFreshEvents(adb, keyBefore) { line ->
                    "KeyEvent" in line && "keyCode=24" in line
                }
                val downEvidence = awaitFreshEvents(adb, keyBefore) { line ->
                    "KeyEvent" in line && "keyCode=25" in line
                }
                assertTrue(upEvidence != null, "音量+ 注入未到达设备端（期望 keyCode=24 新鲜 KeyEvent）")
                assertTrue(downEvidence != null, "音量- 注入未到达设备端（期望 keyCode=25 新鲜 KeyEvent）")
                println("[e2e-ctrl] 按键到达实证: ${upEvidence!!.take(90)}… / ${downEvidence!!.take(90)}…")

                // ④ 剪贴板：set → ack 回执（协议级）；文本注入（IME）需设备端焦点输入框，
                //    设备桌面无输入框可聚焦——本面不验，登记为未覆盖项
                val marker = "toolbelt-ctrl-e2e-${System.currentTimeMillis()}"
                assertTrue(engine.controlSetClipboard(marker))
                withTimeout(10_000) { engine.controlAck.first { it >= 1L } }
                println("[e2e-ctrl] set_clipboard ack 回执: sequence=${engine.controlAck.value}")

                // 停会话一（生命周期收口）后重起会话二：经 GET_CLIPBOARD 读回设备剪贴板——
                // 标记文本在响应中即「已实际落上设备剪贴板」的跨会话实证；
                // 文本注入（IME）需设备端焦点输入框，设备桌面无输入框可聚焦——本面不验，登记为未覆盖项
                engine.stop()
                withTimeout(10_000) { engine.status.first { it is ScreencastStatus.Stopped } }
                assertTrue(engine.start(adbPath, serial), "会话二必须接受启动")
                withTimeout(30_000) {
                    engine.status.first { it is ScreencastStatus.Streaming }
                }
                assertTrue(engine.controlRequestClipboard(), "会话二读回请求必须发出")
                withTimeout(10_000) { engine.deviceClipboard.first { it != null } }
                assertEquals(marker, engine.deviceClipboard.value, "跨会话读回：标记文本必须出现在设备剪贴板响应中")
                println("[e2e-ctrl] 剪贴板跨会话读回实证: ${engine.deviceClipboard.value}")

                // 收口：生命周期三点（进程消失 + 隧道规则清空）
                engine.stop()
                withTimeout(10_000) { engine.status.first { it is ScreencastStatus.Stopped } }
                val ps = adb.execute("adb shell ps -A").getOrNull()?.stdout.orEmpty()
                assertTrue("com.genymobile.scrcpy.Server" !in ps, "设备端 app_process 残留")
                val forwardList = adb.execute("adb forward --list").getOrNull()?.stdout.orEmpty()
                val reverseList = adb.execute("adb reverse --list").getOrNull()?.stdout.orEmpty()
                assertTrue("scrcpy_" !in forwardList, "forward 规则残留: $forwardList")
                assertTrue("scrcpy_" !in reverseList, "reverse 规则残留: $reverseList")
                println("[e2e-ctrl] 生命周期收口通过：进程消失、隧道规则清空")
            }
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    // ── dumpsys input RecentQueue 证据面 ────────────────────────────────

    /** 取 RecentQueue 段的事件行 */
    private suspend fun recentEventLines(adb: ScrcpyAdbGateway): List<String> {
        val dump = adb.execute("adb shell dumpsys input").getOrNull()?.stdout.orEmpty()
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.contains("RecentQueue:") }
        if (start == -1) return emptyList()
        return lines.drop(start + 1).takeWhile { it.contains("age=") }
    }

    /**
     * 事件行的稳定键：eventTime（设备端单调时钟，逐事件唯一）。不得用行文本或 age 判
     * 新鲜——age 是活计时（同一事件每次 dump 都变），基线比对会误把存量事件当新事件。
     */
    private fun eventTimes(lines: List<String>): Set<Long> =
        lines.mapNotNull { line ->
            Regex("eventTime=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
        }.toSet()

    /**
     * 等待一条注入后的**新事件**出现：eventTime 不在基线集合（与 age 无关），且匹配
     * [matcher]。轮询 3s（注入 → dispatcher 入队存在跨进程延迟）。
     */
    private suspend fun awaitFreshEvents(
        adb: ScrcpyAdbGateway,
        baseline: List<String>,
        matcher: (String) -> Boolean,
    ): String? {
        val baselineTimes = eventTimes(baseline)
        repeat(15) {
            delay(200)
            val fresh = recentEventLines(adb).firstOrNull {
                val time = Regex("eventTime=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull()
                time != null && time !in baselineTimes && matcher(it)
            }
            if (fresh != null) return fresh
        }
        return null
    }

    private suspend fun firstDeviceSerial(): String {
        val command = AdbCommandRewriter.rewrite("adb devices", "", serial = null)
        val stdout = SimpleShell.execute(command).getOrNull()?.stdout.orEmpty()
        return stdout.lineSequence()
            .filter { "\tdevice" in it }
            .map { it.substringBefore('\t').trim() }
            .firstOrNull()
            ?: throw IllegalStateException("adb devices 无在线设备——真机 E2E 需设备在线")
    }
}
