package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

/**
 * 投屏 VM 回归：前置检查不静默（无设备 / 检测失败 / 多设备未选定 → 落提示态且引擎
 * 零启动）+ 前置通过时 adb 路径与目标串号透传引擎（Multi 用会话级选择、Single 注入
 * 其串号）+ 提示态手动收起 + 停止转发 + 单步直达独立窗（Streaming 落定即开窗、
 * 等窗超时/失败终态不开——超时落显式回执态，终态通道不叠加超时行）。设备态经
 * [FakeDeviceSource] 直驱、引擎经全 fake 接缝构造（不真跑 adb），hint 口径与
 * logcat 抓取面板逐句对齐。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreencastViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val createdScopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        createdScopes.forEach { it.cancel() }
        // 控制器 visible/windowHandle 是进程级状态——开窗用例驱动的翻真不跨用例残留
        // （先清窗关即停投外呼再 recall——复位路径不得驱动 fake 外呼）
        ScreencastDetachedWindowController.onSessionEnd = null
        ScreencastDetachedWindowController.recall()
        ScreencastDetachedWindowController.windowHandle = null
        // 连接段超时回执同进程级状态——超时用例置位不跨用例残留
        ScreencastDetachedWindowController.connectionTimedOut = false
        // 档位档存同进程级单例——改档用例不跨用例残留（默认档断言面确定性）
        ScreencastQualityTierStore.clearForTest()
        Dispatchers.resetMain()
    }

    /** 全 fake 接缝引擎（push 必失败——快落失败终态，证明 VM→引擎调用链） */
    private fun newVm(devices: FakeDeviceSource): Pair<ScreencastHarness, ScreencastViewModel> {
        val harness = ScreencastHarness(failOn = Regex("adb push"))
        createdScopes += harness.scope
        val vm = ScreencastViewModel(
            devices,
            harness.engine,
            adbPathReader = { "custom-adb" },
        )
        return harness to vm
    }

    @Test
    fun `detect failure surfaces hint with go-settings action and no engine start`(): Unit = runBlocking {
        val (harness, vm) = newVm(FakeDeviceSource(error = "设备检测失败：adb not found"))

        vm.startCast()

        val hint = vm.hint.value
        assertTrue(hint != null, "检测失败不得静默——必须落提示态")
        assertTrue(hint.message.contains("设备检测失败"), "提示沿用检测失败原文（含原因）")
        assertEquals(ScreencastHint.Action.GO_SETTINGS, hint.action)
        assertTrue(
            harness.gatewayCalls.isEmpty(),
            "前置检查不通过时引擎零启动（adb 工厂零调用）",
        )
        assertIs<ScreencastStatus.Idle>(harness.engine.status.value)
    }

    @Test
    fun `no device surfaces hint and no engine start`(): Unit = runBlocking {
        val (harness, vm) = newVm(FakeDeviceSource(state = DeviceState.None))

        vm.startCast()

        val hint = vm.hint.value
        assertTrue(hint != null, "无设备不得静默——必须落提示态")
        assertEquals(ScreencastHint.Action.GO_SETTINGS, hint.action)
        assertTrue(harness.gatewayCalls.isEmpty(), "无设备时引擎零启动")
    }

    @Test
    fun `multi device without selection surfaces hint and no engine start`(): Unit = runBlocking {
        val devices = FakeDeviceSource(
            state = DeviceState.Multi(
                listOf(
                    com.jiaoay.toolbelt.device.DeviceInfo("S1", "M1"),
                    com.jiaoay.toolbelt.device.DeviceInfo("S2", "M2"),
                ),
            ),
            selected = null,
        )
        val (harness, vm) = newVm(devices)

        vm.startCast()

        val hint = vm.hint.value
        assertTrue(hint != null, "多设备未选定不得静默——必须落提示态")
        assertEquals(ScreencastHint.Action.SELECT_DEVICE, hint.action)
        assertTrue(harness.gatewayCalls.isEmpty(), "未选定时引擎零启动")
    }

    @Test
    fun `single device start passes adb path and serial to engine`(): Unit = runBlocking {
        val (harness, vm) = newVm(FakeDeviceSource(state = DeviceState.Single("SINGLE-SERIAL", "M1")))

        vm.startCast()
        // 引擎会话异步起——等终态落定（push 必失败）后核工厂调用参数
        withTimeout(5_000) {
            harness.engine.status.first { it is ScreencastStatus.Stopped }
        }

        assertNull(vm.hint.value, "前置通过时提示态清空")
        assertTrue(
            harness.gatewayCalls.contains("custom-adb" to "SINGLE-SERIAL"),
            "adb 路径与 Single 串号必须透传引擎（实际 ${harness.gatewayCalls}）",
        )
    }

    @Test
    fun `multi device with selection starts against selected serial`(): Unit = runBlocking {
        val devices = FakeDeviceSource(
            state = DeviceState.Multi(
                listOf(
                    com.jiaoay.toolbelt.device.DeviceInfo("S1", "M1"),
                    com.jiaoay.toolbelt.device.DeviceInfo("S2", "M2"),
                ),
            ),
            selected = "S2",
        )
        val (harness, vm) = newVm(devices)

        vm.startCast()
        withTimeout(5_000) {
            harness.engine.status.first { it is ScreencastStatus.Stopped }
        }

        assertTrue(
            harness.gatewayCalls.contains("custom-adb" to "S2"),
            "Multi 已选定时按会话级选择串号启动（实际 ${harness.gatewayCalls}）",
        )
    }

    @Test
    fun `dismiss hint clears it and idle stop cast is a no-op`(): Unit = runBlocking {
        val (harness, vm) = newVm(FakeDeviceSource(state = DeviceState.None))

        vm.startCast()
        assertTrue(vm.hint.value != null)
        vm.dismissHint()
        assertNull(vm.hint.value)

        // Idle 态停止转发：幂等零副作用（引擎无会话在跑）
        vm.stopCast()
        assertNull(vm.hint.value)
        assertIs<ScreencastStatus.Idle>(harness.engine.status.value)
    }

    @Test
    fun `reentrant start while connecting shows wait toast not conflict wording`(): Unit = runBlocking {
        // 不注入 push 失败——首会话须停在 Connecting 占住重入位（failOn 面会话快落终态）
        val harness = ScreencastHarness()
        createdScopes += harness.scope
        val vm = ScreencastViewModel(
            FakeDeviceSource(state = DeviceState.Single("S1", "M1")),
            harness.engine,
            adbPathReader = { "custom-adb" },
        )
        try {
            vm.startCast()
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Connecting } }
            // 二连点：第二击见到的「进行中」是自己刚起的会话，非外部冲突——回执措辞须如实
            vm.startCast()
            withTimeout(5_000) {
                StatusSignals.toast.first { entries -> entries.any { it.text == "正在开始投屏，请稍候" } }
            }
            assertTrue(
                StatusSignals.toast.value.none { "已有投屏在进行中" in it.text },
                "「已有投屏在进行中」是外部冲突措辞，对二连点误导",
            )
        } finally {
            // StatusSignals 是进程级单例——本用例落的 toast 清掉，不漏给同 JVM 后续用例
            StatusSignals.toast.value
                .filter { it.text == "正在开始投屏，请稍候" }
                .forEach { StatusSignals.dismiss(it.id) }
            harness.close()
        }
    }

    @Test
    fun `failure dismissal resets per session so same-message failure reappears`(): Unit = runBlocking {
        val devices = FakeDeviceSource(state = DeviceState.Single("S1", "M1"))
        val harness = ScreencastHarness(failOn = Regex("adb push"))
        createdScopes += harness.scope
        val vm = ScreencastViewModel(devices, harness.engine, adbPathReader = { "custom-adb" })
        try {
            // 第一次会话：push 失败落显式失败终态
            vm.startCast()
            val first = withTimeout(5_000) {
                harness.engine.status.first { it is ScreencastStatus.Stopped } as ScreencastStatus.Stopped
            }
            assertTrue(first.failed)
            assertNull(vm.dismissedFailure.value, "未收读前已读标记恒 null")
            vm.dismissFailure()
            assertEquals(first.message, vm.dismissedFailure.value, "收读后已读标记承载失败文案")

            // 第二次会话（同因同文案失败）：新会话起点必须清零已读——同文案失败重新可见。
            // 观察者先于启动挂上（否则 first 会吃到上一会话残留的 Stopped 终态）
            val secondSession = launch {
                withTimeout(5_000) {
                    harness.engine.status.first { it is ScreencastStatus.Connecting }
                    harness.engine.status.first { it is ScreencastStatus.Stopped }
                }
            }
            vm.startCast()
            secondSession.join()
            assertNull(
                vm.dismissedFailure.value,
                "新会话必须重置已读标记（同文案二次失败被旧会话已读吞掉即红）",
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `hint action set matches precheck producible set with no dead member`() {
        // 死分支守护：Action 枚举新增 precheckHint 永不产出的成员即红（成员与产出面一致）
        val harness = ScreencastHarness()
        createdScopes += harness.scope
        try {
            val producible = listOf(
                FakeDeviceSource(error = "检测失败"),
                FakeDeviceSource(state = DeviceState.None),
                FakeDeviceSource(
                    state = DeviceState.Multi(
                        listOf(
                            com.jiaoay.toolbelt.device.DeviceInfo("S1", "M1"),
                            com.jiaoay.toolbelt.device.DeviceInfo("S2", "M2"),
                        ),
                    ),
                    selected = null,
                ),
                FakeDeviceSource(state = DeviceState.Single("S1", "M1")),
            ).map { devices ->
                ScreencastViewModel(devices, harness.engine, adbPathReader = { "" }).precheckHint()?.action
            }.filterNotNull().toSet()
            assertEquals(
                ScreencastHint.Action.values().toSet(),
                producible,
                "Action 枚举不得含 precheck 永不产出的死成员（实际产出面 $producible）",
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `control forwarding reaches engine control channel as wire bytes`(): Unit = runBlocking {
        val harness = ScreencastHarness()
        createdScopes += harness.scope
        val devices = FakeDeviceSource(state = DeviceState.Single("S1", "M1"))
        val vm = ScreencastViewModel(devices, harness.engine, adbPathReader = { "" })
        try {
            harness.startAndHandshake()
            withTimeout(5_000) {
                harness.engine.status.first { it is ScreencastStatus.Streaming }
            }

            // 触摸透传（坐标已由调用方换算完成，VM 零加工）
            vm.injectTouch(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 10, 20, 800, 600)
            assertContentEquals(
                ScrcpyControlEncoder.injectTouch(
                    ScrcpyControlProtocol.MOTION_ACTION_DOWN,
                    ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER,
                    10, 20, 800, 600, 1f,
                ),
                harness.readControlBytes(32),
            )
            // 虚拟指 id 透传（显式 pointerId 原样落 wire；缺省 = 上方 -2 恒等）
            vm.injectTouch(
                ScrcpyControlProtocol.MOTION_ACTION_MOVE, 30, 40, 800, 600,
                ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND,
            )
            assertContentEquals(
                ScrcpyControlEncoder.injectTouch(
                    ScrcpyControlProtocol.MOTION_ACTION_MOVE,
                    ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND,
                    30, 40, 800, 600, 1f,
                ),
                harness.readControlBytes(32),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `quality tier selection lands in serve command and defaults match engine behavior`(): Unit = runBlocking {
        val harness = ScreencastHarness()
        createdScopes += harness.scope
        val devices = FakeDeviceSource(state = DeviceState.Single("S1", "M1"))
        val vm = ScreencastViewModel(devices, harness.engine, adbPathReader = { "custom-adb" })
        try {
            // 档位转发链：窗内档位弹层 → 档位档存 → start 时刻参数组 → serve 命令逐字
            // （追加序 max_size → max_fps → video_bit_rate）
            ScreencastQualityTierStore.onBitrateTierChange(16_000_000L)
            ScreencastQualityTierStore.onFpsTierChange(60f)
            ScreencastQualityTierStore.onMaxSizeTierChange(720)
            // startCast 异步起会话——server 角色由测试侧复用 harness 承载（VM 面零改动）
            vm.startCast()
            harness.serverRoleHandshake()
            withTimeout(5_000) {
                harness.engine.status.first { it is ScreencastStatus.Streaming }
            }
            val command = harness.serveCommandOfSession(1)
            assertTrue(
                " max_size=720 max_fps=60.0 video_bit_rate=16000000" in command,
                "档位未透传引擎 serve 命令（实际 $command）",
            )

            // 默认档产物：max_size=1080 预览档（预览窗 2x 密度只需 ~840px，原始档的
            // 满幅解码/纹理链是观测页 jank 头号杠杆；原始档仍可手选——档位 0）。
            // 档位档存是进程级单例——复位后新 VM 才断言默认档（残留档不跨断言面）
            ScreencastQualityTierStore.clearForTest()
            assertEquals(
                ScreencastOptions(maxSize = 1080),
                ScreencastViewModel(FakeDeviceSource(), harness.engine).currentOptions(),
                "默认档必须映射 max_size=1080 预览档（回归原始档即红）",
            )
        } finally {
            ScreencastQualityTierStore.clearForTest()
            harness.close()
        }
    }

    // ── 受理即开窗（startCast 受理 → 立即开窗，先窗后画） ────────────────────────

    @Test
    fun `start cast opens detached window immediately once session accepted`(): Unit = runBlocking {
        val harness = ScreencastHarness()
        createdScopes += harness.scope
        val vm = ScreencastViewModel(
            FakeDeviceSource(state = DeviceState.Single("S1", "M1")),
            harness.engine,
            adbPathReader = { "custom-adb" },
        )
        try {
            vm.startCast()
            // 受理即开窗：不等 Streaming 落定——Connecting 段窗已在（先窗后画，窗内
            // 连接段占位承接预算期反馈）
            withTimeout(5_000) {
                while (!ScreencastDetachedWindowController.visible) delay(50)
            }
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Connecting } }
            harness.serverRoleHandshake()
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Streaming } }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `connect budget timeout keeps accepted window open and flags controller receipt`(): Unit = runBlocking {
        // 不做 server 角色握手——会话停在 Connecting，Streaming 永不到；受理即开窗后
        // 窗保持开（取消语义在窗内），短预算（注入 200ms）走完置控制器单源超时回执
        // （窗内超时态为主呈现面，主窗兜底细行同源）
        val harness = ScreencastHarness(connectTimeoutMs = 60_000, acceptSoTimeoutMs = 65_000)
        createdScopes += harness.scope
        val vm = ScreencastViewModel(
            FakeDeviceSource(state = DeviceState.Single("S1", "M1")),
            harness.engine,
            adbPathReader = { "custom-adb" },
            detachedWindowOpenTimeoutMs = 200,
        )
        try {
            vm.startCast()
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Connecting } }
            withTimeout(5_000) {
                while (!ScreencastDetachedWindowController.connectionTimedOut) delay(50)
            }
            assertTrue(
                ScreencastDetachedWindowController.visible,
                "受理即开窗后连接段超时窗保持开——取消/重试语义在窗内（X/取消钮），不闪关",
            )
            assertTrue(
                ScreencastDetachedWindowController.connectionTimedOut,
                "连接预算走完仍悬在连接段须置单源超时回执（静默零回执即红）",
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `connect timeout receipt is status gated and cleared on next session acceptance`(): Unit = runBlocking {
        // 超时回执位是显示态门控单源（显示面按 status 为 Connecting 门控——落终态后
        // 不再显示，位不清）；下一会话受理（重试起点）清位——旧超时不吞新会话
        val harness = ScreencastHarness(connectTimeoutMs = 60_000, acceptSoTimeoutMs = 65_000)
        createdScopes += harness.scope
        val vm = ScreencastViewModel(
            FakeDeviceSource(state = DeviceState.Single("S1", "M1")),
            harness.engine,
            adbPathReader = { "custom-adb" },
            detachedWindowOpenTimeoutMs = 200,
        )
        try {
            vm.startCast()
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Connecting } }
            withTimeout(5_000) {
                while (!ScreencastDetachedWindowController.connectionTimedOut) delay(50)
            }

            // 停投落终态：回执位不清（显示面由 status 门控接管——终态回执由终态通道
            // 承载，不叠加超时行）
            vm.stopCast()
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Stopped } }
            delay(200)
            assertTrue(
                ScreencastDetachedWindowController.connectionTimedOut,
                "落终态后回执位保持（显示面按 status 门控，位由下一会话受理清）",
            )

            // 下一会话受理（重试起点）：清上一会话残留的连接超时回执——受理点清位
            // 写在 startCast 的 IO 协程内（晚于 Connecting 可观察），快照断言会抢跑，
            // 轮询等清位落定
            vm.startCast()
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Connecting } }
            withTimeout(5_000) {
                while (ScreencastDetachedWindowController.connectionTimedOut) delay(20)
            }
            assertFalse(
                ScreencastDetachedWindowController.connectionTimedOut,
                "新会话被受理须清上一会话残留的超时回执（旧超时不吞新会话）",
            )
            // 预算协程用例内排干：本会话引擎悬在连接段（60s harness），本 startCast 的
            // 预算到期会在用例结束后写单例旗标——不排干即跨用例污染（单例写入时序与
            // tearDown 复位竞态）
            delay(300)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `failed terminal session skips timeout receipt and defers window close to composition`(): Unit = runBlocking {
        val harness = ScreencastHarness(failOn = Regex("adb push"))
        createdScopes += harness.scope
        val vm = ScreencastViewModel(
            FakeDeviceSource(state = DeviceState.Single("S1", "M1")),
            harness.engine,
            adbPathReader = { "custom-adb" },
            detachedWindowOpenTimeoutMs = 200,
        )
        try {
            vm.startCast()
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Stopped && it.failed } }
            delay(300)
            // 受理即开窗：失败终态时窗已开过（受理点翻真）——收窗由窗内容 auto-close
            // collect 驱动（Content 组合面用例覆盖），VM 层不再断言 visible
            assertFalse(
                ScreencastDetachedWindowController.connectionTimedOut,
                "已落 Stopped 终态时不得叠加超时回执（失败回执由终态通道承载）",
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `pointer to screen mapping scales proportionally and clamps out of bounds`() {
        // 等比换算：画面区 400x300 → 设备屏 800x600（2 倍）
        assertEquals(400 to 300, mapPointerToScreen(200f, 150f, 400, 300, 800, 600))
        // 原点与右下角边界
        assertEquals(0 to 0, mapPointerToScreen(0f, 0f, 400, 300, 800, 600))
        assertEquals(799 to 599, mapPointerToScreen(400f, 300f, 400, 300, 800, 600))
        // 越界收口（指针掠出画面边缘不得产屏外坐标）
        assertEquals(799 to 0, mapPointerToScreen(999f, -50f, 400, 300, 800, 600))
        // 非等比画面区（宽 200 高 100）仍按各自维度换算
        assertEquals(400 to 300, mapPointerToScreen(100f, 50f, 200, 100, 800, 600))
        // 退化输入守卫：零尺寸返回原点（不得除零）
        assertEquals(0 to 0, mapPointerToScreen(50f, 50f, 0, 0, 800, 600))
        assertEquals(0 to 0, mapPointerToScreen(50f, 50f, 100, 100, 0, 0))
    }
}
