package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle
import com.jiaoay.easy_dev_toolbox.arch.countColor
import com.jiaoay.easy_dev_toolbox.arch.probeSchemeColor
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodes
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 多指手势链守护（fake 回调消费面——SceneGuard 场景注入真实指针事件，不触真引擎）：
 * - 主键过滤行为：仅左键手势产单指注入，右键拖动/中键零注入（杂散触摸修死位——
 *   右键单击已被泊指通道收编，见下）；
 * - Ctrl+滚轮 = 离散档捏合：每档一组自洽双指事件序（-3/-4 对，屏幕中心对称、
 *   竖直轴分离），档与档零跨档状态；
 * - Ctrl+左键拖 = 对称双指（按下点为中点、方向随拖向、指距随拖距）、Shift+左键拖 =
 *   双指同向平移（轴垂直首位移方向、全量跟随）、迟滞内修饰键单击 = 双指同点轻点、
 *   拖动中途松修饰键不切模式（按下时刻锁定）；
 * - 泊指（右键通道）：单击泊入（-11/-12/-13 依序、按下位坐标、DOWN 保持按住）、
 *   连点泊多根、拖动取消（零注入且不毒化双击锚）、双击清全部（逐根补发 UP）、
 *   泊满拒绝显式提示、与左键活动指的正交组合（一指固定一指动）；
 * - 反馈件渲染存在性：激活手势的指环/连线/mono 读数、修饰键徽标随键起灭、泊指
 *   常驻实心圆 + 「泊 N/3」计数胶囊随泊入/清空起灭；
 * - 世代清场：世代变化把在泊清单经清场回调交宿主、宿主侧世代守卫只对同世代补发；
 * - 非 Ctrl 滚轮维持滚动注入（符号取反口径不动）；
 * - 折算纯函数：半径收口档 / 步长 / 判定表 / 对称指位 / 平移指位 / 迟滞 /
 *   泊指 id 分配与清场补发逐例钉死。
 *
 * 场景几何恒 480×360 且帧位图纵横比与设备屏一致——画面节点满幅铺满场景，
 * 控件坐标 → 屏幕坐标换算恒等（注入坐标断言零换算噪声）。
 */
class ScreencastMultitouchGestureTest {

    private val handles = mutableListOf<SceneGuardHandle>()

    @AfterTest
    fun tearDown() {
        handles.forEach { it.close() }
    }

    // ── 场景装配（fake onEvent/onScroll 回调记录——不触真引擎） ─────────────────

    private data class TouchCall(val action: Int, val x: Int, val y: Int, val pointerId: Long)

    private data class ScrollCall(
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val hScroll: Float,
        val vScroll: Float,
    )

    private class Recording {
        val touches = mutableListOf<TouchCall>()
        val scrolls = mutableListOf<ScrollCall>()
    }

    /** 泊指宿主回调记录面（计数变化 / 清场清单 / 瞬态提示——fake 隔离，零真引擎触达） */
    private class ParkHooks {
        val counts = mutableListOf<Int>()
        val notices = mutableListOf<String>()
        var released: List<ScreencastParkedFinger> = emptyList()
    }

    private fun mount(
        parkHooks: ParkHooks? = null,
        sessionGeneration: Long = 0L,
    ): Pair<SceneGuardHandle, Recording> {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        val recording = Recording()
        handle.setContent {
            GestureScene(recording, parkHooks, sessionGeneration)
        }
        handle.renderPixels()
        return handle to recording
    }

    @Composable
    private fun GestureScene(
        recording: Recording,
        parkHooks: ParkHooks? = null,
        sessionGeneration: Long = 0L,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ScreencastFrameView(
                frame = frameBitmap(SCENE_W, SCENE_H),
                heightCap = null,
                streaming = ScreencastStatus.Streaming("设备", "S1", SCENE_W, SCENE_H),
                onEvent = { action, x, y, screenWidth, screenHeight, pointerId ->
                    recording.touches += TouchCall(action, x, y, pointerId)
                },
                onScroll = { x, y, screenWidth, screenHeight, hScroll, vScroll ->
                    recording.scrolls += ScrollCall(x, y, screenWidth, screenHeight, hScroll, vScroll)
                },
                sessionGeneration = sessionGeneration,
                onParkedCountChange = parkHooks?.let { hooks -> { count -> hooks.counts += count } },
                onParkedRelease = parkHooks?.let { hooks -> { fingers -> hooks.released = fingers } },
                onTransientNotice = parkHooks?.let { hooks -> { text -> hooks.notices += text } },
            )
        }
    }

    /** 纯色帧位图（与场景同纵横比——满幅无黑边；alpha 须 OR 不透明段） */
    private fun frameBitmap(width: Int, height: Int): ImageBitmap {
        val image = ImageBitmap(width, height)
        val canvas = Canvas(image)
        val paint = Paint().apply { color = Color((0xFF shl 24) or 0x336699) }
        canvas.drawRect(Rect(0f, 0f, width.toFloat(), height.toFloat()), paint)
        return image
    }

    // ── 指针注入（场景事件口：按钮/修饰键/滚动分量全可控） ─────────────────────

    private fun modifiers(ctrl: Boolean = false, shift: Boolean = false) = PointerKeyboardModifiers(
        isCtrlPressed = ctrl,
        isShiftPressed = shift,
    )

    private fun SceneGuardHandle.pressAt(
        x: Float,
        y: Float,
        buttons: PointerButtons,
        button: PointerButton,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) {
        scene.sendPointerEvent(
            eventType = PointerEventType.Press,
            position = Offset(x, y),
            buttons = buttons,
            button = button,
            keyboardModifiers = modifiers(ctrl, shift),
        )
    }

    private fun SceneGuardHandle.moveTo(
        x: Float,
        y: Float,
        buttons: PointerButtons,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) {
        scene.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(x, y),
            buttons = buttons,
            keyboardModifiers = modifiers(ctrl, shift),
        )
    }

    private fun SceneGuardHandle.releaseAt(x: Float, y: Float, button: PointerButton) {
        scene.sendPointerEvent(
            eventType = PointerEventType.Release,
            position = Offset(x, y),
            buttons = PointerButtons(),
            button = button,
        )
    }

    /** 右键单击（press + release 同位——泊指/双击清空的动作单元） */
    private fun SceneGuardHandle.rightClickAt(x: Float, y: Float) {
        pressAt(x, y, PointerButtons(isSecondaryPressed = true), PointerButton.Secondary)
        releaseAt(x, y, PointerButton.Secondary)
    }

    /** 悬停移动（无按键）——修饰键徽标的指针事件驱动面 */
    private fun SceneGuardHandle.hoverAt(x: Float, y: Float, ctrl: Boolean = false, shift: Boolean = false) {
        scene.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(x, y),
            buttons = PointerButtons(),
            keyboardModifiers = modifiers(ctrl, shift),
        )
    }

    /** 语义树文本节点匹配（反馈件读数/徽标文案的在场断言面） */
    private fun nodesWithText(handle: SceneGuardHandle, snippet: String): List<SemanticsNode> =
        handle.semanticsNodes { node ->
            node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text.contains(snippet) } == true
        }

    private fun SceneGuardHandle.scrollAt(x: Float, y: Float, scrollX: Float, scrollY: Float, ctrl: Boolean) {
        scene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset(x, y),
            scrollDelta = Offset(scrollX, scrollY),
            keyboardModifiers = if (ctrl) {
                PointerKeyboardModifiers(isCtrlPressed = true)
            } else {
                PointerKeyboardModifiers()
            },
        )
    }

    // ── 主键过滤行为（左键注入 / 右中键零注入） ───────────────────────────────

    @Test
    fun `left button tap injects down up pair with generic finger id`() {
        val (handle, recording) = mount()
        handle.pressAt(120f, 60f, PointerButtons(isPrimaryPressed = true), PointerButton.Primary)
        handle.releaseAt(120f, 60f, PointerButton.Primary)
        assertEquals(
            listOf(
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 120, 60, ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 120, 60, ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER),
            ),
            recording.touches,
            "左键轻点须注入 DOWN+UP 对（主单指恒 -2，坐标换算恒等）",
        )
    }

    @Test
    fun `left button drag injects down move up along the pointer trace`() {
        val (handle, recording) = mount()
        handle.pressAt(100f, 100f, PointerButtons(isPrimaryPressed = true), PointerButton.Primary)
        handle.moveTo(150f, 120f, PointerButtons(isPrimaryPressed = true))
        handle.releaseAt(150f, 120f, PointerButton.Primary)
        assertEquals(
            listOf(
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 100, 100, ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_MOVE, 150, 120, ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 150, 120, ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER),
            ),
            recording.touches,
            "左键拖动须按轨迹注入 DOWN/MOVE/UP（手势首笔 MOVE 恒放行——合并窗不吞首笔）",
        )
    }

    @Test
    fun `right and middle button gestures inject nothing`() {
        val (handle, recording) = mount()
        // 右键拖动（press→move→release 全程）：泊指取消路径——零注入零滚动
        handle.pressAt(200f, 100f, PointerButtons(isSecondaryPressed = true), PointerButton.Secondary)
        handle.moveTo(230f, 130f, PointerButtons(isSecondaryPressed = true))
        handle.releaseAt(230f, 130f, PointerButton.Secondary)
        // 中键轻点：同纪律（中键不参与任何触摸回传）
        handle.pressAt(300f, 200f, PointerButtons(isTertiaryPressed = true), PointerButton.Tertiary)
        handle.releaseAt(300f, 200f, PointerButton.Tertiary)
        assertEquals(
            emptyList(),
            recording.touches,
            "右键拖动取消泊入零注入（位移超迟滞即弃）；中键恒零注入（不参与触摸回传）",
        )
        assertEquals(emptyList(), recording.scrolls, "按钮手势不产滚动注入")
    }

    // ── Ctrl+滚轮 = 离散档捏合（fake onEvent 消费面） ──────────────────────────

    @Test
    fun `ctrl wheel up notch emits self contained pinch pair zooming in`() {
        val (handle, recording) = mount()
        handle.scrollAt(200f, 150f, scrollX = 0f, scrollY = -1f, ctrl = true)
        // 480×360 屏：初始分离半径 = 0.08×360 = 28.8 → 收口下限 40；步长 = 40/8 = 5。
        // 屏幕中心 (240,180)：上指 240,140 / 下指 240,220；放大档外扩到 135/225。
        val expectedZoomIn = listOf(
            TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 240, 140, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
            TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 240, 220, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
            TouchCall(ScrcpyControlProtocol.MOTION_ACTION_MOVE, 240, 135, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
            TouchCall(ScrcpyControlProtocol.MOTION_ACTION_MOVE, 240, 225, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
            TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 240, 135, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
            TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 240, 225, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
        )
        assertEquals(expectedZoomIn, recording.touches, "Ctrl+滚轮向上须发一组自洽离散捏合（双指对 -3/-4）")
        // 第二档与第一档逐位相同：每档独立自洽，零跨档常驻指（比例失真不跨档累积）
        handle.scrollAt(200f, 150f, scrollX = 0f, scrollY = -1f, ctrl = true)
        assertEquals(expectedZoomIn + expectedZoomIn, recording.touches, "连续两档捏合须各自自洽（档间无残留状态）")
        assertEquals(emptyList(), recording.scrolls, "Ctrl 捏合档不产滚动注入")
    }

    @Test
    fun `ctrl wheel down notch pinches zoom out`() {
        val (handle, recording) = mount()
        handle.scrollAt(100f, 100f, scrollX = 0f, scrollY = 1f, ctrl = true)
        // 缩小档内收：半径 40 → 35（上指 145 / 下指 215）
        assertEquals(
            listOf(
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 240, 140, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 240, 220, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_MOVE, 240, 145, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_MOVE, 240, 215, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 240, 145, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 240, 215, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
            ),
            recording.touches,
            "Ctrl+滚轮向下须发内收捏合对",
        )
    }

    @Test
    fun `plain wheel keeps scroll injection with sign flip and emits no touch`() {
        val (handle, recording) = mount()
        handle.scrollAt(200f, 150f, scrollX = 0.5f, scrollY = -1f, ctrl = false)
        assertEquals(
            listOf(ScrollCall(200, 150, SCENE_W, SCENE_H, hScroll = -0.5f, vScroll = 1f)),
            recording.scrolls,
            "非 Ctrl 滚轮维持滚动注入（Compose 正 y 向下 → 协议正 vScroll 向上，注入前取反）",
        )
        assertEquals(emptyList(), recording.touches, "滚动注入不得串触摸通道")
        // Ctrl 但垂直分量为零（纯横向滚轮）→ 非捏合档，回退滚动注入
        handle.scrollAt(200f, 150f, scrollX = 2f, scrollY = 0f, ctrl = true)
        assertEquals(
            listOf(
                ScrollCall(200, 150, SCENE_W, SCENE_H, hScroll = -0.5f, vScroll = 1f),
                ScrollCall(200, 150, SCENE_W, SCENE_H, hScroll = -2f, vScroll = -0f),
            ),
            recording.scrolls,
            "Ctrl+纯横向滚轮须回退滚动注入（垂直分量为零不构成捏合档）",
        )
        assertEquals(emptyList(), recording.touches, "横向滚轮回退不得产捏合触摸")
    }

    // ── 修饰键双指拖（Ctrl 对称捏合 / Shift 同向平移 / 迟滞内轻点） ─────────────

    @Test
    fun `ctrl drag injects symmetric pinch pair around press point`() {
        val (handle, recording) = mount()
        handle.pressAt(240f, 180f, PointerButtons(isPrimaryPressed = true), PointerButton.Primary, ctrl = true)
        handle.moveTo(280f, 180f, PointerButtons(isPrimaryPressed = true), ctrl = true)
        handle.releaseAt(280f, 180f, PointerButton.Primary)
        // 屏维 = 场景维（480×360）：换算恒等；初始分离半径 = 40（0.08×360 = 28.8 收口下限）。
        // 激活 DOWN 对沿拖向（+x）对称分离：(240±40,180)；抬指 UP 对外扩到半径 40+40：(240±80,180)
        assertEquals(
            listOf(
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 280, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 200, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 320, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 160, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
            ),
            recording.touches,
            "Ctrl+左键拖须注入对称双指对（按下点为中点、方向随拖向、指距随拖距增长、UP 恒带最新指位）",
        )
        assertEquals(emptyList(), recording.scrolls, "修饰键拖动不产滚动注入")
    }

    @Test
    fun `ctrl click inside hysteresis taps both fingers at press point`() {
        val (handle, recording) = mount()
        handle.pressAt(240f, 180f, PointerButtons(isPrimaryPressed = true), PointerButton.Primary, ctrl = true)
        // 迟滞内小位移（2px < 4px）：不激活拖手势——抬指走双指同点轻点
        handle.moveTo(242f, 180f, PointerButtons(isPrimaryPressed = true), ctrl = true)
        handle.releaseAt(242f, 180f, PointerButton.Primary)
        assertEquals(
            listOf(
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 240, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 240, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 240, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 240, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
            ),
            recording.touches,
            "Ctrl+单击（迟滞内）须注入双指同点 DOWN+UP 轻点（按下点为锚，非抬指点）",
        )
    }

    @Test
    fun `shift drag injects same direction pan pair with locked axis`() {
        val (handle, recording) = mount()
        handle.pressAt(240f, 180f, PointerButtons(isPrimaryPressed = true), PointerButton.Primary, shift = true)
        handle.moveTo(280f, 180f, PointerButtons(isPrimaryPressed = true), shift = true)
        handle.releaseAt(280f, 180f, PointerButton.Primary)
        // 首位移 +x → 分离轴 +y（垂直于首位移方向，激活时刻锁定）：DOWN 对 (240, 180±40)；
        // 抬指两指随全量位移 (40,0) 平移：UP 对 (280, 180±40)
        assertEquals(
            listOf(
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 240, 220, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 240, 140, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 280, 220, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 280, 140, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
            ),
            recording.touches,
            "Shift+左键拖须注入双指同向平移对（轴垂直首位移方向锁定、两指随鼠标全量平移）",
        )
    }

    @Test
    fun `modifier released mid drag keeps locked pinch mode`() {
        val (handle, recording) = mount()
        handle.pressAt(240f, 180f, PointerButtons(isPrimaryPressed = true), PointerButton.Primary, ctrl = true)
        // 拖动中途松开 Ctrl（move 事件修饰键态为空）：模式按下时刻已锁定，不切回单指
        handle.moveTo(280f, 180f, PointerButtons(isPrimaryPressed = true))
        handle.releaseAt(280f, 180f, PointerButton.Primary)
        assertEquals(
            listOf(
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 280, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 200, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 320, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 160, 180, ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND),
            ),
            recording.touches,
            "修饰键拖动中途松开不切模式（按下时刻锁定——可预测优先）",
        )
    }

    @Test
    fun `right button with ctrl held still injects nothing`() {
        val (handle, recording) = mount()
        handle.pressAt(200f, 100f, PointerButtons(isSecondaryPressed = true), PointerButton.Secondary, ctrl = true)
        handle.moveTo(240f, 140f, PointerButtons(isSecondaryPressed = true), ctrl = true)
        handle.releaseAt(240f, 140f, PointerButton.Secondary)
        assertEquals(
            emptyList(),
            recording.touches,
            "修饰键不越过主键过滤（右键拖动恒取消泊入零注入——修饰键态不改变右键通道纪律）",
        )
    }

    // ── 泊指（右键通道：单击泊入 / 连点泊多根 / 拖动取消 / 双击清空 / 泊满提示） ────

    @Test
    fun `right click tap parks a held finger at press point with first parked id`() {
        val hooks = ParkHooks()
        val (handle, recording) = mount(parkHooks = hooks, sessionGeneration = 7L)
        // 释放位与按下位偏差 2px（迟滞内）——泊点取按下位（迟滞内位移不改泊点）
        handle.pressAt(100f, 80f, PointerButtons(isSecondaryPressed = true), PointerButton.Secondary)
        handle.releaseAt(102f, 81f, PointerButton.Secondary)
        assertEquals(
            listOf(
                TouchCall(
                    ScrcpyControlProtocol.MOTION_ACTION_DOWN,
                    100,
                    80,
                    ScrcpyControlProtocol.POINTER_ID_PARKED_FIRST,
                ),
            ),
            recording.touches,
            "右键单击泊入：发一次 DOWN 保持按住（id 首号 -11，坐标 = 按下位设备坐标）",
        )
        assertEquals(listOf(1), hooks.counts, "泊入后计数须上抛宿主（状态行消费面）")
    }

    @Test
    fun `sequential right clicks park fingers in id order up to capacity`() {
        val hooks = ParkHooks()
        val (handle, recording) = mount(parkHooks = hooks, sessionGeneration = 7L)
        handle.rightClickAt(100f, 80f)
        Thread.sleep(SLOW_CLICK_GAP_MS)
        handle.rightClickAt(200f, 120f)
        Thread.sleep(SLOW_CLICK_GAP_MS)
        handle.rightClickAt(300f, 200f)
        val down = ScrcpyControlProtocol.MOTION_ACTION_DOWN
        assertEquals(
            listOf(
                TouchCall(down, 100, 80, -11L),
                TouchCall(down, 200, 120, -12L),
                TouchCall(down, 300, 200, -13L),
            ),
            recording.touches,
            "连点（双击判定窗外）依序泊入 -11/-12/-13，各发一次 DOWN 保持按住",
        )
        assertEquals(listOf(1, 2, 3), hooks.counts)
        // 泊满后单击：行内瞬态提示 + 零注入（不静默不泊入）
        Thread.sleep(SLOW_CLICK_GAP_MS)
        handle.rightClickAt(150f, 150f)
        assertEquals(listOf(PARK_FULL_NOTICE), hooks.notices, "泊满拒绝须显式提示（不静默）")
        assertEquals(3, recording.touches.size, "泊满后单击零注入（不泊入第四根）")
    }

    @Test
    fun `right button drag cancels park intent without poisoning click anchor`() {
        val (handle, recording) = mount()
        handle.pressAt(100f, 80f, PointerButtons(isSecondaryPressed = true), PointerButton.Secondary)
        handle.moveTo(130f, 110f, PointerButtons(isSecondaryPressed = true))
        handle.releaseAt(130f, 110f, PointerButton.Secondary)
        assertEquals(
            emptyList(),
            recording.touches,
            "右键拖动（位移超迟滞）= 取消本次泊入，零注入",
        )
        // 取消的拖动不落双击锚：紧跟的单击正常泊入（拖动取消不毒化后续单击）
        handle.rightClickAt(120f, 60f)
        assertEquals(
            listOf(TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 120, 60, -11L)),
            recording.touches,
            "拖动取消后的单击须恢复泊入（取消不计入双击判定锚）",
        )
    }

    @Test
    fun `double right click clears all parked fingers with per finger ups`() {
        val hooks = ParkHooks()
        val (handle, recording) = mount(parkHooks = hooks, sessionGeneration = 7L)
        // 双击判定窗外连点泊两根
        handle.rightClickAt(100f, 80f)
        Thread.sleep(SLOW_CLICK_GAP_MS)
        handle.rightClickAt(200f, 120f)
        Thread.sleep(SLOW_CLICK_GAP_MS)
        // 双击判定窗内的连击：第二击清全部（含首击刚泊入的第三根——固有代价）
        handle.rightClickAt(300f, 200f)
        handle.rightClickAt(150f, 150f)
        val down = ScrcpyControlProtocol.MOTION_ACTION_DOWN
        val up = ScrcpyControlProtocol.MOTION_ACTION_UP
        assertEquals(
            listOf(
                TouchCall(down, 100, 80, -11L),
                TouchCall(down, 200, 120, -12L),
                TouchCall(down, 300, 200, -13L),
                TouchCall(up, 100, 80, -11L),
                TouchCall(up, 200, 120, -12L),
                TouchCall(up, 300, 200, -13L),
            ),
            recording.touches,
            "双击右键清全部泊指：逐根补发 UP（坐标 = 各指泊入位），清空后第四击不再泊入",
        )
        assertEquals(listOf(1, 2, 3, 0), hooks.counts, "清空后计数须归零上抛")
    }

    @Test
    fun `parked finger plus left drag forms one fixed one moving posture`() {
        val (handle, recording) = mount()
        handle.rightClickAt(100f, 80f)
        // 左键拖动 = 活动指（-2）：设备端同时见 -11 常驻按住 + -2 移动（一指固定一指动）
        handle.pressAt(240f, 180f, PointerButtons(isPrimaryPressed = true), PointerButton.Primary)
        handle.moveTo(280f, 180f, PointerButtons(isPrimaryPressed = true))
        handle.releaseAt(280f, 180f, PointerButton.Primary)
        val generic = ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER
        assertEquals(
            listOf(
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 100, 80, -11L),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 240, 180, generic),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_MOVE, 280, 180, generic),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 280, 180, generic),
            ),
            recording.touches,
            "泊指常驻按住 + 左键活动指拖动 = 双指姿态（-11 按住不被左键手势触碰）",
        )
    }

    @Test
    fun `parked finger renders solid circle count capsule and clears on double click`() {
        val (handle, recording) = mount()
        val primary = probeSchemeColor { MaterialTheme.colorScheme.primary }
        handle.rightClickAt(100f, 80f)
        val duringPixels = handle.renderPixels()
        assertTrue(
            countColor(duringPixels, primary) > 0,
            "泊指常驻实心圆（primary 实底）须进入渲染面——泊入动作必须有可见反馈",
        )
        assertTrue(
            nodesWithText(handle, "泊 1/3").isNotEmpty(),
            "左下「泊 1/3」计数胶囊须随泊指在场",
        )
        // 双击判定窗（[PARK_DOUBLE_CLICK_INTERVAL_MS]）按真实墙钟计时，而中间的整帧渲染
        // 与断言耗时不定——配对与否须靠显式 sleep 关窗 + 零间隔连击取得时序确定性，
        // 勿把整帧渲染夹进双击对中间
        Thread.sleep(SLOW_CLICK_GAP_MS)
        handle.rightClickAt(200f, 120f)
        handle.rightClickAt(300f, 200f)
        val afterPixels = handle.renderPixels()
        assertEquals(
            0,
            countColor(afterPixels, primary),
            "清空后泊指圆须退场（实心圆不残留）",
        )
        assertTrue(nodesWithText(handle, "泊 1/3").isEmpty(), "清空后计数胶囊须退场")
        assertEquals(
            listOf(
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 100, 80, -11L),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_DOWN, 200, 120, -12L),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 100, 80, -11L),
                TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 200, 120, -12L),
            ),
            recording.touches,
            "窗外单击泊入两根 + 窗内连击清全部（清空补发逐根 UP——固有代价）",
        )
    }

    @Test
    fun `session generation change releases parked fingers through host with generation guard`() {
        val hooks = ParkHooks()
        val handle = SceneGuardHandle(SCENE_W, SCENE_H).also { handles += it }
        val recording = Recording()
        handle.setContent { GestureScene(recording, hooks, sessionGeneration = 7L) }
        handle.renderPixels()
        handle.rightClickAt(100f, 80f)
        assertEquals(1, hooks.counts.last(), "泊入后计数已上抛")
        // 世代变化（同参数重连：Streaming 数据等值、世代号不同）——泊指状态随世代重建，
        // 旧世代在泊清单经清场回调交宿主（帧区退场/世代重建共用同一条清场收口）
        handle.setContent { GestureScene(recording, hooks, sessionGeneration = 8L) }
        handle.renderPixels()
        assertEquals(1, hooks.released.size, "世代重建须把旧世代在泊清单交宿主清场")
        assertEquals(7L, hooks.released.first().generation, "清场清单须携带泊入世代令牌")
        assertEquals(0, hooks.counts.last(), "清场后计数须归零上抛")
        assertTrue(nodesWithText(handle, "泊 1/3").isEmpty(), "新世代场景不得残留旧世代泊指可视化")
        // 宿主侧世代守卫：清场时刻存活世代为 8——旧世代（7）泊指不补发（不向新会话乱注入）；
        // 同世代存活（关窗路径）则补发必达
        val ups = mutableListOf<TouchCall>()
        fun recordUp(action: Int, x: Int, y: Int, pointerId: Long) {
            ups += TouchCall(action, x, y, pointerId)
        }
        emitParkedFingerUps(hooks.released, currentGeneration = 8L) { action, x, y, _, _, id ->
            recordUp(action, x, y, id)
        }
        assertEquals(emptyList(), ups, "异世代泊指零补发（世代守卫防已断会话乱注入）")
        emitParkedFingerUps(hooks.released, currentGeneration = 7L) { action, x, y, _, _, id ->
            recordUp(action, x, y, id)
        }
        assertEquals(
            listOf(TouchCall(ScrcpyControlProtocol.MOTION_ACTION_UP, 100, 80, -11L)),
            ups,
            "清场时刻同世代仍存活（关窗/收起路径）须逐根补发 UP",
        )
    }

    @Test
    fun `status line appends parked count while streaming only`() {
        val streaming = ScreencastStatus.Streaming("设备", "S1", 480, 360)
        assertEquals(
            "设备 · 480x360 · 30 fps · 已泊 2 指",
            statusLine(streaming, 30, parkedCount = 2),
            "状态行尾追加「已泊 N 指」（泊指在场时的可见计数面）",
        )
        assertEquals(
            "设备 · 480x360 · 30 fps",
            statusLine(streaming, 30, parkedCount = 0),
            "无泊指不追加计数（零泊指零噪音）",
        )
        assertEquals(
            "已停止",
            statusLine(ScreencastStatus.Stopped("已停止", failed = false), 0, parkedCount = 2),
            "终态不带泊指计数（清场后计数恒归零，缺省参数同语义）",
        )
    }

    @Test
    fun `parked pointer id allocation and label decision table`() {
        assertEquals(-11L, nextParkedPointerId(emptySet()), "空占用取首号")
        assertEquals(-12L, nextParkedPointerId(setOf(-11L)))
        assertEquals(-13L, nextParkedPointerId(setOf(-11L, -12L)))
        assertEquals(
            null,
            nextParkedPointerId(setOf(-11L, -12L, -13L)),
            "泊满即拒绝（null = 调用方显式提示不泊入）",
        )
        assertEquals(
            listOf(-11L, -12L, -13L),
            PARKED_POINTER_IDS,
            "泊指 id 表恒 -11/-12/-13（与主单指 -2、双指对 -3/-4 互不撞面，避 -1 mouse 分支）",
        )
        assertEquals("①", parkedFingerNumberLabel(-11L), "编号角标按泊入序")
        assertEquals("②", parkedFingerNumberLabel(-12L))
        assertEquals("③", parkedFingerNumberLabel(-13L))
        assertEquals(null, parkedFingerNumberLabel(-2L), "非泊指 id 不产角标")
        assertEquals("泊 0/3", parkedCountLabel(0))
        assertEquals("泊 3/3", parkedCountLabel(3))
        assertEquals(3, ScrcpyControlProtocol.PARKED_FINGERS_MAX, "泊指容量档 = 3")
    }

    // ── 反馈件渲染存在性（指环/读数/徽标——SceneGuard 像素 + 语义树双锚） ─────────

    @Test
    fun `active pinch renders finger rings readout and clears on release`() {
        val (handle, recording) = mount()
        val primary = probeSchemeColor { MaterialTheme.colorScheme.primary }
        handle.pressAt(240f, 180f, PointerButtons(isPrimaryPressed = true), PointerButton.Primary, ctrl = true)
        handle.moveTo(280f, 180f, PointerButtons(isPrimaryPressed = true), ctrl = true)
        val duringPixels = handle.renderPixels()
        assertTrue(
            countColor(duringPixels, primary) > 0,
            "手势激活期须渲染虚拟指环/连线（primary 实色像素在场——动作后必须有可见反馈）",
        )
        assertTrue(
            nodesWithText(handle, "×2.00").isNotEmpty(),
            "手势激活期须渲染 mono 实时读数（倍率 = (40+40)/40 = ×2.00）",
        )
        handle.releaseAt(280f, 180f, PointerButton.Primary)
        val afterPixels = handle.renderPixels()
        assertEquals(
            0,
            countColor(afterPixels, primary),
            "抬指后指环/连线/读数须全部退场（反馈不残留）",
        )
        assertTrue(nodesWithText(handle, "×2.00").isEmpty(), "抬指后读数文案须退场")
    }

    @Test
    fun `modifier hover shows mode badge and clears when released`() {
        val (handle, recording) = mount()
        handle.hoverAt(240f, 180f, ctrl = true)
        handle.renderPixels()
        assertTrue(
            nodesWithText(handle, "对称双指").isNotEmpty(),
            "按住 Ctrl 悬停画面须浮左下模式徽标（随键起灭的预备提示）",
        )
        // 清除悬停换位注入：同位置零变化事件被输入派发层去重，不达手势链
        handle.hoverAt(250f, 180f)
        handle.renderPixels()
        assertTrue(nodesWithText(handle, "对称双指").isEmpty(), "松开修饰键后徽标须随指针事件退场")
        handle.hoverAt(260f, 180f, shift = true)
        handle.renderPixels()
        assertTrue(
            nodesWithText(handle, "双指平移").isNotEmpty(),
            "按住 Shift 悬停须浮平移模式徽标（两模式文案分叉）",
        )
        assertEquals(emptyList(), recording.touches, "悬停不产任何触摸注入")
    }

    // ── 折算纯函数（档位/步长/判定表） ────────────────────────────────────────

    @Test
    fun `pinch notch radius clamps to fixed device pixel band`() {
        // 比例档 0.08×min(屏宽,屏高)：中屏命中比例原值，大屏/小屏双向收口
        assertEquals(48, pinchNotchBaseRadius(800, 600), "0.08×600 = 48 命中比例原值")
        assertEquals(120, pinchNotchBaseRadius(2880, 1620), "0.08×1620 = 129.6 → 收口上限 120")
        assertEquals(40, pinchNotchBaseRadius(480, 360), "0.08×360 = 28.8 → 收口下限 40")
        assertEquals(0, pinchNotchBaseRadius(0, 0), "屏维非法零发射（0 = 捏合档禁用）")
        // 档位锚：改档须连同 ScreencastFrameView 的分离半径契约注释一起重审
        assertEquals(0.08f, PINCH_NOTCH_RADIUS_FRACTION)
        assertEquals(40, PINCH_NOTCH_RADIUS_MIN_PX)
        assertEquals(120, PINCH_NOTCH_RADIUS_MAX_PX)
    }

    @Test
    fun `pinch notch step is one eighth of base radius with unit floor`() {
        assertEquals(15, pinchNotchStepPx(120), "步长 = 半径 × 1/8（细调量级档）")
        assertEquals(5, pinchNotchStepPx(40))
        assertEquals(1, pinchNotchStepPx(1), "小半径档步长下限 1px（归零即档间无差）")
        assertEquals(1f / 8f, PINCH_NOTCH_STEP_FRACTION)
    }

    @Test
    fun `ctrl notch zoom direction decision table`() {
        val noCtrl = PointerKeyboardModifiers()
        val ctrl = PointerKeyboardModifiers(isCtrlPressed = true)
        assertEquals(null, discretePinchNotchZoomIn(noCtrl, scrollDeltaY = -1f), "无 Ctrl = 滚动通道")
        assertEquals(null, discretePinchNotchZoomIn(ctrl, scrollDeltaY = 0f), "垂直分量为零 = 滚动通道")
        assertEquals(true, discretePinchNotchZoomIn(ctrl, scrollDeltaY = -1f), "Ctrl+滚轮向上 = 放大档")
        assertEquals(false, discretePinchNotchZoomIn(ctrl, scrollDeltaY = 1f), "Ctrl+滚轮向下 = 缩小档")
    }

    @Test
    fun `pinch notch events are symmetric around screen center and screen bounded`() {
        val events = pinchNotchEvents(2880, 1620, zoomIn = true)
        assertEquals(6, events.size, "每档恒 6 笔（DOWN/MOVE/UP × 双指对）")
        assertEquals(
            listOf(
                ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST,
                ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND,
            ),
            events.take(2).map { it.pointerId },
            "DOWN 对恒先 first(-3) 后 second(-4)（服务端流序合成多点 MotionEvent）",
        )
        val radius = pinchNotchBaseRadius(2880, 1620)
        val step = pinchNotchStepPx(radius)
        // 对称中心 = 屏幕中心 (1440,810)，竖直轴分离
        val downPair = events.take(2)
        assertEquals(1440, downPair[0].x)
        assertEquals(810 - radius, downPair[0].y)
        assertEquals(1440, downPair[1].x)
        assertEquals(810 + radius, downPair[1].y)
        // 放大档 MOVE 对外扩一个步长；UP 恒带 MOVE 终位（与单指手势同收口纪律）
        val movePair = events.drop(2).take(2)
        assertEquals(810 - radius - step, movePair[0].y)
        assertEquals(810 + radius + step, movePair[1].y)
        assertEquals(
            listOf(events[2].x, events[2].y, events[3].x, events[3].y),
            listOf(events[4].x, events[4].y, events[5].x, events[5].y),
            "UP 对恒带 MOVE 对终位（UP 与 MOVE 同位，仅 action 不同）",
        )
        assertTrue(events.all { it.x in 0..2879 && it.y in 0..1619 }, "虚拟指位恒收口屏内")
    }

    @Test
    fun `pinch notch events emit nothing on invalid screen dimensions`() {
        assertEquals(emptyList(), pinchNotchEvents(0, 600, zoomIn = true), "屏维非法零发射（不向设备注入越界指位）")
        assertEquals(emptyList(), pinchNotchEvents(480, 0, zoomIn = false))
    }

    // ── 折算纯函数（对称指位 / 平移指位 / 迟滞 / 模式判定 / 读数） ───────────────

    @Test
    fun `symmetric pinch fingers follow drag direction distance and clamp to bounds`() {
        // 拖向 +x、拖距 40：指距 = 初始半径 40 + 拖距 40 = 80——指位沿拖向对称分离
        val along = symmetricPinchFingers(240f, 180f, 40f, 40f, 0f, 480, 360)
        assertEquals(320, along.firstX.roundToInt())
        assertEquals(180, along.firstY.roundToInt())
        assertEquals(160, along.secondX.roundToInt())
        assertEquals(180, along.secondY.roundToInt())
        // 指距随拖距增长：拖 80 → 半径 120
        val farther = symmetricPinchFingers(240f, 180f, 40f, 80f, 0f, 480, 360)
        assertEquals(360, farther.firstX.roundToInt())
        assertEquals(120, farther.secondX.roundToInt())
        // 拖向 +y（拖距 40 → 半径 80）：方向随拖向偏转（旋转分量来源）
        val downward = symmetricPinchFingers(240f, 180f, 40f, 0f, 40f, 480, 360)
        assertEquals(240, downward.firstX.roundToInt())
        assertEquals(260, downward.firstY.roundToInt())
        assertEquals(100, downward.secondY.roundToInt())
        // 越界收口：指位钉在边界内（不向设备注入越界指位）
        val clamped = symmetricPinchFingers(240f, 340f, 40f, 0f, 500f, 480, 360)
        assertEquals(359, clamped.firstY.roundToInt())
        assertEquals(0, clamped.secondY.roundToInt())
    }

    @Test
    fun `pan translate fingers separate perpendicular to first drag and follow fully`() {
        // 轴 = π/2（垂直于 +x 首位移）：first = 中点 + 40·(0,1) + 全量位移，second 对称
        val axis = panTranslateFingers(240f, 180f, 40f, (Math.PI / 2).toFloat(), 40f, 0f, 480, 360)
        assertEquals(280, axis.firstX.roundToInt())
        assertEquals(220, axis.firstY.roundToInt())
        assertEquals(280, axis.secondX.roundToInt())
        assertEquals(140, axis.secondY.roundToInt())
        // 全量跟随：两指整体随位移平移（同向同距）
        val moved = panTranslateFingers(240f, 180f, 40f, (Math.PI / 2).toFloat(), 100f, -60f, 480, 360)
        assertEquals(340, moved.firstX.roundToInt())
        assertEquals(160, moved.firstY.roundToInt())
        assertEquals(340, moved.secondX.roundToInt())
        assertEquals(80, moved.secondY.roundToInt())
        // 越界收口：指位钉在边界内
        val clamped = panTranslateFingers(10f, 180f, 40f, 0f, 0f, 500f, 480, 360)
        assertEquals(50, clamped.firstX.roundToInt())
        assertEquals(359, clamped.firstY.roundToInt())
        assertEquals(0, clamped.secondX.roundToInt())
        assertEquals(359, clamped.secondY.roundToInt())
    }

    @Test
    fun `two finger drag activates only past view pixel hysteresis`() {
        assertEquals(4f, GESTURE_ACTIVATE_HYSTERESIS_PX, "迟滞档 = 4 视图 px（设计推荐档锚）")
        assertFalse(gestureHysteresisSatisfied(3.9f, 0f), "边界内不激活")
        assertTrue(gestureHysteresisSatisfied(4f, 0f), "达阈值即激活（含边界）")
        assertFalse(gestureHysteresisSatisfied(2f, 2f), "对角 2.83px 仍在死区内")
        assertTrue(gestureHysteresisSatisfied(3f, 3f), "对角 4.24px 过阈值")
    }

    @Test
    fun `modifier to gesture mode decision table`() {
        assertEquals(ScreencastGestureMode.PINCH, screencastGestureModeFor(ctrl = true, shift = false))
        assertEquals(ScreencastGestureMode.PAN, screencastGestureModeFor(ctrl = false, shift = true))
        assertEquals(
            ScreencastGestureMode.PINCH,
            screencastGestureModeFor(ctrl = true, shift = true),
            "双修饰并按 Ctrl 优先（与滚轮捏合档同键优先）",
        )
        assertEquals(null, screencastGestureModeFor(ctrl = false, shift = false), "无修饰 = 单指现状")
    }

    @Test
    fun `pinch readout formats ratio and rotation`() {
        assertEquals("×2.00 ↻0°", pinchReadoutText(radiusPx = 80f, baseRadiusPx = 40f, angleRad = 0f, baseAngleRad = 0f))
        assertEquals(
            "×2.00 ↻90°",
            pinchReadoutText(80f, 40f, (Math.PI / 2).toFloat(), 0f),
            "屏幕 y 向下坐标系：拖向角偏转 +90° = 顺时针 ↻",
        )
        assertEquals(
            "×0.50 ↺45°",
            pinchReadoutText(20f, 40f, (-Math.PI / 4).toFloat(), 0f),
            "内收减倍率、逆时针 ↺（符号随偏转方向）",
        )
    }

    @Test
    fun `pan readout formats device pixel distance`() {
        assertEquals("平移 57px", panReadoutText(40f, 40f), "位移量设备 px（hypot(40,40) = 56.6 取整）")
        assertEquals("平移 100px", panReadoutText(100f, 0f))
    }

    @Test
    fun `emit parked ups skips dead session without any emission`() {
        // 停投/断连路径：帧流已清（currentGeneration = null = 无存活会话）——控制通道已关，
        // 补发必被引擎丢弃且直发有把旧世代 UP 注入新会话的乱射窗，恒跳越
        val finger = ScreencastParkedFinger(-11L, 100, 80, 480, 360, generation = 7L)
        val ups = mutableListOf<TouchCall>()
        emitParkedFingerUps(listOf(finger), currentGeneration = null) { action, x, y, _, _, id ->
            ups += TouchCall(action, x, y, id)
        }
        assertEquals(emptyList(), ups, "无存活会话零补发（世代守卫的 null 档）")
    }

    private companion object {
        const val SCENE_W = 480
        const val SCENE_H = 360

        /** 双击判定窗（[PARK_DOUBLE_CLICK_INTERVAL_MS] = 400ms）外的慢击间隔：600ms（余量 200ms 抗抖） */
        const val SLOW_CLICK_GAP_MS = 600L
    }
}
