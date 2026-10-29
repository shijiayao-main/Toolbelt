package com.jiaoay.easy_dev_toolbox.page.deviceobs

import com.jiaoay.easy_dev_toolbox.arch.assertBandsSeparated
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.isContentPx
import com.jiaoay.easy_dev_toolbox.arch.renderPixels
import com.jiaoay.toolbelt.device.ShellResult
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceKey
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.device.KeyEventInjector
import com.jiaoay.toolbelt.device.KeyInjectionBatch
import com.jiaoay.toolbelt.device.KeyInjectionReceipt
import com.jiaoay.toolbelt.adb.AdbEndpoint

/**
 * 按键遥控面板场景渲染像素带守护：[SceneGuardSupport] 单源配方。
 * 状态注入走生产链——真实 [KeyEventInjector] +
 * fake gateway（真分发内核跑通，免真 adb），设备域单源置单设备后渲染。
 *
 * 验证面：
 * - 默认展开态内容带在场且带间不粘连（头部行/方向十字+常用键网格/自定义
 *   keycode 行各自进渲染面，按键矩阵空壳即红）；
 * - 回执面：注入缝（[KeyEventInjector.applyBatch]）预置回执后逐台成功/失败
 *   行进渲染面（回执链断裂即红）；
 * - 回执行文本映射（[keyPadReceiptLines]/[keyPadBatchAllOk]）纯函数断言。
 *
 * 批量勾选交互态（FilterChip 点击）不在零显示器像素面覆盖——由注入器分发内核
 * 单测（:core KeyEventInjectorTest）+ 注入缝预置承销。
 */
class KeyPadPanelSceneGuardTest {

    private val homeIsolation = UserHomeIsolation("edt-keypad-scene")
    private val createdScopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
    }

    @AfterTest
    fun tearDown() {
        createdScopes.forEach { it.cancel() }
        homeIsolation.restoreOnly()
    }

    /** 带内内容像素计数（单源 [isContentPx] 判定；bandContentPx 属本面板私有几何工具非单源配方） */
    private fun bandContentPx(pixels: Array<IntArray>, band: IntRange): Int {
        var cnt = 0
        for (y in band) {
            for (x in pixels[y].indices) {
                if (isContentPx(pixels[y][x])) cnt++
            }
        }
        return cnt
    }

    /** 面板载体：设备域单源（fake gateway + 注入缝置单设备，门控默认关零轮询）+ 注入器 */
    private fun newPanelState(): Pair<DeviceMonitor, KeyEventInjector> {
        val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it }
        val monitor = DeviceMonitor(
            scope = monitorScope,
            runner = { Result.success(ShellResult(0, "", "")) },

            endpointReader = { AdbEndpoint("") },
        )
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "Demo-1")))
        val injector = KeyEventInjector(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it },
            devices = monitor,
            runner = { Result.success(ShellResult(0, "", "")) },

            endpointReader = { AdbEndpoint("") },
        )
        return monitor to injector
    }

    @Test
    fun `expanded panel renders header keypad and custom key bands without overlap`() {
        val (monitor, injector) = newPanelState()

        val pixels = renderPixels(SCENE_W, SCENE_H) {
            KeyPadPanel(devices = monitor, injector = injector)
        }
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(
            bands.size >= 4,
            "默认展开态应渲染 ≥4 内容带（头部/键网格多行/自定义行），实际 ${bands.size}：$bands" +
                "——展开链断裂或按键矩阵未进渲染面",
        )
        // 胶囊描边控件（SearchField）的边框弧线行在带检测下呈 <20px 的零碎带，与相邻
        // 带间距 3-4px——是控件内部解剖非内容叠放；带间隙断言只对非零碎带生效
        val contentBands = bands.filter { bandContentPx(pixels, it) >= 20 }
        assertBandsSeparated(contentBands)
        // 按键矩阵非空壳：方向十字 + 常用键网格文字进渲染面（整面内容像素下限）
        val totalContentPx = bands.sumOf { band -> bandContentPx(pixels, band) }
        assertTrue(
            totalContentPx >= 400,
            "内容像素 $totalContentPx < 400——按键网格/十字空壳（bands=$bands）",
        )
    }

    @Test
    fun `preset receipts render per-device lines into panel`() {
        val (monitor, injector) = newPanelState()
        // 注入缝预置：两台部分失败回执（回执面免真注入）
        injector.applyBatch(
            KeyInjectionBatch(
                key = DeviceKey(24, "音量+"),
                receipts = listOf(
                    KeyInjectionReceipt("S1", DeviceKey(24, "音量+"), success = true, message = null),
                    KeyInjectionReceipt(
                        "S2", DeviceKey(24, "音量+"),
                        success = false, message = "adb 退出码 1：error: device offline",
                    ),
                ),
            ),
        )

        val pixels = renderPixels(SCENE_W, SCENE_H) {
            KeyPadPanel(devices = monitor, injector = injector)
        }
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(bands.isNotEmpty(), "预置回执态应渲染内容带")
        // 回执行（等宽字体、面板底部）进渲染面：末带内容像素下限（成功/失败两行）
        val lastBand = bands.last()
        val receiptPx = bandContentPx(pixels, lastBand)
        assertTrue(
            receiptPx >= 30,
            "回执行未进渲染面（末带 $lastBand 内容像素 $receiptPx < 30）——回执链断裂",
        )
    }

    @Test
    fun `active repeat renders composition without error and keeps band floor`() {
        val (monitor, injector) = newPanelState()
        // 连发真实起动（repeatingKey 同步入态，fake gateway 免真 adb）——在途行进组合面
        assertTrue(injector.startRepeat(DeviceKey(24, "音量+")), "单设备 fake 态连发须可起")

        val pixels = renderPixels(SCENE_W, SCENE_H) {
            KeyPadPanel(devices = monitor, injector = injector)
        }
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(bands.size >= 4, "连发在途态内容带不得塌陷（实际 ${bands.size}）——在途行/回执行渲染链断裂")

        injector.stopRepeat()
        assertNull(injector.state.value.repeatingKey, "场景收尾停发（不留活环给后续用例）")
    }

    // ── 回执行文本映射纯函数 ─────────────────────────────────────

    @Test
    fun `receipt lines split success and per-device failure reasons`() {
        val key = DeviceKey(24, "音量+")
        val lines = keyPadReceiptLines(
            KeyInjectionBatch(
                key = key,
                receipts = listOf(
                    KeyInjectionReceipt("S1", key, success = true, message = null),
                    KeyInjectionReceipt("S2", key, success = false, message = "adb 退出码 1：offline"),
                ),
            ),
        )
        assertEquals(2, lines.size, "部分失败 = 成功一行 + 失败一行")
        assertTrue(lines[0].contains("S1") && lines[0].contains("注入成功"), "成功行含串号（实际：${lines[0]}）")
        assertTrue(lines[1].contains("S2") && lines[1].contains("adb 退出码 1"), "失败行含串号与原因（实际：${lines[1]}）")
        val partial = KeyInjectionBatch(
            key = key,
            receipts = listOf(
                KeyInjectionReceipt("S1", key, success = true, message = null),
                KeyInjectionReceipt("S2", key, success = false, message = "adb 退出码 1：offline"),
            ),
        )
        assertFalse(keyPadBatchAllOk(partial), "部分失败整体色调非全成功")
    }

    @Test
    fun `all-ok batch renders single success line and empty batch renders defensive line`() {
        val key = DeviceKey(3, "主页")
        val allOk = KeyInjectionBatch(
            key = key,
            receipts = listOf(
                KeyInjectionReceipt("S1", key, success = true, message = null),
                KeyInjectionReceipt("S2", key, success = true, message = null),
            ),
        )
        assertEquals(listOf("「主页」注入成功：S1、S2"), keyPadReceiptLines(allOk))
        assertTrue(keyPadBatchAllOk(allOk))

        val empty = KeyInjectionBatch(key, emptyList())
        assertEquals(listOf("「主页」无回执"), keyPadReceiptLines(empty), "空回执集防御行不静默")
        assertFalse(keyPadBatchAllOk(empty), "空回执集不得判全成功")
    }

    @Test
    fun `repeat line carries key label and single-source interval`() {
        val line = keyPadRepeatLine(DeviceKey(25, "音量−"), KeyEventInjector.REPEAT_INTERVAL_MS)
        assertTrue(line.contains("音量−"), "在途行须含连发键人话名（实际：$line）")
        assertTrue(
            line.contains("${KeyEventInjector.REPEAT_INTERVAL_MS} ms"),
            "在途行须含单源节流间隔（实际：$line）——文案与实际节流失联即误读",
        )
        assertTrue(line.contains("长按连发中"), "在途行须明示连发在途（实际：$line）")
    }

    @Test
    fun `query result hint states total and shown limit honestly`() {
        assertNull(keyPadQueryResultHint(0, 8), "零命中不产提示（空态行另有承载）")
        assertEquals("命中 3 项", keyPadQueryResultHint(3, 8), "未超上限如实报总数")
        assertEquals("命中 20 项，显示前 8 项", keyPadQueryResultHint(20, 8), "超上限须标注总数不静默截断")
    }

    companion object {
        private const val SCENE_W = 1096
        private const val SCENE_H = 700
    }
}
