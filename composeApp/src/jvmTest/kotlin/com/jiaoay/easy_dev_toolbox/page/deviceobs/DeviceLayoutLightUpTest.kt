package com.jiaoay.easy_dev_toolbox.page.deviceobs

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.jiaoay.toolbelt.device.DeviceLayoutBounds
import com.jiaoay.toolbelt.device.DeviceLayoutNode
import com.jiaoay.toolbelt.device.WindowViewNode

/**
 * 布局子页「投屏点亮」守护面（行为单测 + 宿主 wiring 源码锚，源码切片钉死形态与
 * GoReportHookWiringGuardTest 同族——判定/注入经 [layoutLightUpDecision] 行为单测
 * 背书，接线面经源码切片钉死）：
 * - 判定纯函数：Streaming 态同串号注入载荷坐标 = 节点**全局**中心（a11y 源恒等
 *   原生 bounds；全窗口视图源 = 父相对 bounds + 祖先累加 + window:left/top
 *   偏移的合成面，双源各一面）、屏幕尺寸 = Streaming 快照自带 width/height；串号失配拒绝零注入且人话
 *   指明两台设备并指路；快照来源串号缺席拒绝指路不静默；会话串号 null（引擎 API
 *   层可空）不拦截维持注入；Idle/Connecting/Stopped 三态各落拒绝人话零注入；
 * - DeviceObsPage 必须向 DeviceLayoutPanel 传非 null 点亮回调（漏传 = 详情卡按钮
 *   静默消失，UI 层不可见不可测）且注入走 DOWN+UP 成对（单边注入 = 设备端按下
 *   不抬起，点按手势损坏）。
 */
class DeviceLayoutLightUpTest {

    /** 详情卡节点样例（bounds [10,20][200,80]：中心点 (105,50) 可心算核对） */
    private fun sampleNode(): DeviceLayoutNode = DeviceLayoutNode(
        index = 0,
        text = "设置",
        resourceId = "com.demo:id/settings",
        className = "android.widget.TextView",
        packageName = "com.demo",
        contentDesc = "",
        checkable = false,
        checked = false,
        clickable = true,
        enabled = true,
        focusable = false,
        focused = false,
        scrollable = false,
        longClickable = false,
        password = false,
        selected = false,
        bounds = DeviceLayoutBounds(left = 10, top = 20, right = 200, bottom = 80),
    )

    /** Streaming 样例（serial 可注入：null 用例 = 引擎 API 层可空路径） */
    private fun streaming(serial: String?, deviceName: String = "Demo-1") =
        ScreencastStatus.Streaming(deviceName = deviceName, serial = serial, width = 1280, height = 720)

    @Test
    fun `same serial streaming yields unchanged injection payload at node center`() {
        val decision = layoutLightUpDecision(
            status = streaming(serial = "S1"),
            dumpSerial = "S1",
            dumpDeviceLabel = "Demo-1",
            node = sampleNode(),
        )
        val payload = decision as? LayoutLightUpDecision.Inject
            ?: error("同串号 Streaming 态应产注入载荷，实际 $decision")
        assertEquals(105, payload.x, "注入坐标 X 应为节点中心（(10+200)/2）")
        assertEquals(50, payload.y, "注入坐标 Y 应为节点中心（(20+80)/2）")
        assertEquals(1280, payload.screenWidth, "屏幕尺寸应取自 Streaming 快照 width")
        assertEquals(720, payload.screenHeight, "屏幕尺寸应取自 Streaming 快照 height")
    }

    @Test
    fun `serial mismatch rejects with both device names and zero injection`() {
        val decision = layoutLightUpDecision(
            status = streaming(serial = "SERIAL-A", deviceName = "主屏Demo-1"),
            dumpSerial = "SERIAL-B",
            dumpDeviceLabel = "副屏Demo-1",
            node = sampleNode(),
        )
        val reject = decision as? LayoutLightUpDecision.Reject
            ?: error("跨串号失配应拒绝零注入（快照坐标属另一台设备，注入必点错屏），实际 $decision")
        assertTrue(
            "副屏Demo-1" in reject.message && "主屏Demo-1" in reject.message,
            "失配人话应指明两台设备（快照来源 + 在投设备），实际：${reject.message}",
        )
        assertTrue(
            "重抓层级或切回投屏设备" in reject.message,
            "失配人话应指路（重抓层级或切回投屏设备），实际：${reject.message}",
        )
    }

    @Test
    fun `mismatch without resolvable device label falls back to dump serial`() {
        val decision = layoutLightUpDecision(
            status = streaming(serial = "SERIAL-A"),
            dumpSerial = "SERIAL-B",
            dumpDeviceLabel = null,
            node = sampleNode(),
        )
        val reject = decision as? LayoutLightUpDecision.Reject
            ?: error("失配应拒绝，实际 $decision")
        assertTrue(
            "SERIAL-B" in reject.message,
            "设备标识解析缺席（来源设备已断开/不在场）应回退串号原文，实际：${reject.message}",
        )
    }

    @Test
    fun `null dump serial rejects with guidance instead of silent injection`() {
        val decision = layoutLightUpDecision(
            status = streaming(serial = "S1"),
            dumpSerial = null,
            dumpDeviceLabel = null,
            node = sampleNode(),
        )
        val reject = decision as? LayoutLightUpDecision.Reject
            ?: error("快照来源串号缺席应拒绝指路不静默，实际 $decision")
        assertTrue(
            "重新抓取层级" in reject.message,
            "来源不明拒绝应指路重抓，实际：${reject.message}",
        )
    }

    @Test
    fun `null streaming serial keeps injection without gating`() {
        val decision = layoutLightUpDecision(
            status = streaming(serial = null),
            dumpSerial = "S1",
            dumpDeviceLabel = "Demo-1",
            node = sampleNode(),
        )
        val payload = decision as? LayoutLightUpDecision.Inject
            ?: error("会话串号 null = 来源不可证不拦截（引擎 API 层可空），实际 $decision")
        assertEquals(105, payload.x)
        assertEquals(50, payload.y)
    }

    @Test
    fun `idle connecting and stopped statuses reject with distinct human messages`() {
        val rejections = listOf(
            ScreencastStatus.Idle,
            ScreencastStatus.Connecting,
            ScreencastStatus.Stopped(message = "已停止", failed = false),
        ).map { status ->
            layoutLightUpDecision(status, dumpSerial = "S1", dumpDeviceLabel = "Demo-1", sampleNode())
                as? LayoutLightUpDecision.Reject
                ?: error("$status 态应产拒绝人话，实际产注入载荷（非 Streaming 零注入）")
        }
        assertTrue(
            rejections.all { it.message.isNotBlank() },
            "三态各应落非空白人话回执，实际 $rejections",
        )
        assertTrue(
            rejections.map { it.message }.toSet().size == rejections.size,
            "三态人话应可区分（各态指路不同），实际 $rejections",
        )
    }

    @Test
    fun `window views node injection applies window offset to node center`() {
        // 全窗口视图源样例（调研报告 MapMainActivity 实测锚：窗口 left=1932/top=102，
        // 窗口内 912x1362）——点亮落点 = 根节点 bounds（无祖先面）+ 窗口全局偏移的全局中心
        val node = WindowViewNode(
            className = "android.widget.FrameLayout",
            viewHash = "3ac1a74",
            viewId = "NO_ID",
            bounds = DeviceLayoutBounds(0, 0, 912, 1362),
            windowLeft = 1932,
            windowTop = 102,
            visibility = 0,
            clickable = false,
            enabled = true,
            selected = false,
            activated = false,
            padding = null,
            childCount = 0,
            children = emptyList(),
            properties = emptyMap(),
        )
        val decision = layoutLightUpDecision(
            status = streaming(serial = "S1"),
            dumpSerial = "S1",
            dumpDeviceLabel = "Demo-1",
            node = node,
        )
        val payload = decision as? LayoutLightUpDecision.Inject
            ?: error("全窗口视图源同串号 Streaming 态应产注入载荷，实际 $decision")
        assertEquals(2388, payload.x, "注入坐标 X 应为全局中心（(1932+2844)/2）")
        assertEquals(783, payload.y, "注入坐标 Y 应为全局中心（(102+1464)/2）")
        assertEquals(1280, payload.screenWidth)
        assertEquals(720, payload.screenHeight)
    }

    @Test
    fun `obs page wires non-null light-up callback into layout panel`() {
        val source = deviceObsPageSource()
        assertTrue(
            "onLightUp = { node -> lightUpLayoutNode(screencastViewModel, layoutViewModel, monitor, node) }" in source,
            "DeviceObsPage 必须向 DeviceLayoutPanel 传非 null 点亮回调（漏传 = 详情卡" +
                "「投屏点亮」钮静默消失，UI 层不可见不可测）",
        )
        assertTrue(
            "ScrcpyControlProtocol.MOTION_ACTION_DOWN" in source &&
                "ScrcpyControlProtocol.MOTION_ACTION_UP" in source,
            "点亮注入必须 DOWN+UP 成对（单边注入 = 设备端按下不抬起）",
        )
    }

    // ── 选中节点上投屏帧画框联动（wiring 切片 + 资格判定） ──

    @Test
    fun `obs page wires screencast layout highlight linkage`() {
        val source = deviceObsPageSource()
        assertTrue(
            "ScreencastLayoutHighlight.set(" in source && "ScreencastLayoutHighlight.clear()" in source,
            "DeviceObsPage 布局子页必须接线 ScreencastLayoutHighlight 联动" +
                "（漏接线 = 帧上框静默失效，UI 不可见不可测——与 onLightUp 漏传同罪型）",
        )
        assertTrue(
            "layoutFrameHighlightEligible(" in source,
            "门控必须经 layoutFrameHighlightEligible 纯函数（可单测的门控面）",
        )
        assertTrue(
            "DisposableEffect(Unit) { onDispose { ScreencastLayoutHighlight.clear() } }" in source,
            "布局子页离开组合必须清帧上高亮（切子页/切页不残留）",
        )
    }

    @Test
    fun `frame highlight eligibility mirrors light-up gate with stricter null serial`() {
        // Streaming + 串号一致 + dumpSerial 非 null：可上帧
        assertTrue(
            layoutFrameHighlightEligible(streaming(serial = "S1"), dumpSerial = "S1"),
            "同串号 Streaming 态可上帧",
        )
        // 串号失配 / dumpSerial 缺席 / 非 Streaming：不上帧（与点亮 Inject 前置条件同口径）
        assertFalse(
            layoutFrameHighlightEligible(streaming(serial = "SERIAL-A"), dumpSerial = "SERIAL-B"),
            "跨串号失配不上帧（快照坐标属另一台设备）",
        )
        assertFalse(
            layoutFrameHighlightEligible(streaming(serial = "S1"), dumpSerial = null),
            "快照来源缺席不上帧",
        )
        assertFalse(
            layoutFrameHighlightEligible(ScreencastStatus.Idle, dumpSerial = "S1"),
            "非 Streaming 态不上帧",
        )
        assertFalse(
            layoutFrameHighlightEligible(ScreencastStatus.Connecting, dumpSerial = "S1"),
        )
        assertFalse(
            layoutFrameHighlightEligible(
                ScreencastStatus.Stopped(message = "已停止", failed = false),
                dumpSerial = "S1",
            ),
        )
        // serial null 防御面有意不对称：点亮走「不证伪即放行注入」而帧上框恒不上
        // （持续显示态的保守面更严；生产面 serial 恒非 null，登记见 KDoc）
        assertFalse(
            layoutFrameHighlightEligible(streaming(serial = null), dumpSerial = "S1"),
            "会话串号 null（引擎 API 层可空防御面）不上帧——与点亮放行口径有意不对称",
        )
    }

    private fun deviceObsPageSource(): String {
        val file = File(
            locateProjectRoot(),
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/deviceobs/DeviceObsPage.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：DeviceObsPage.kt（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
