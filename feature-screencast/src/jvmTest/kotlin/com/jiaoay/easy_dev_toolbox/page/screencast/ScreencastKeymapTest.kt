package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.jiaoay.toolbelt.device.AndroidKeyCodes

/**
 * 键直通映射表键位矩阵 + 双轨判定 + 剪贴板桥纯函数 + 窗口分发接线单测：
 * 映射表全条目钉死（值锚 = 字面量，真源换算 = :core AndroidKeyCodes 按名查）；
 * dispatch 用例经桌面真实转换链构造（AWT 事件 → Compose KeyEvent——与生产输入管线
 * 同一条 toComposeEvent 转换路径，非手工拼装 Compose 态）。
 */
class ScreencastKeymapTest {

    // ── 映射表键位矩阵 ────────────────────────────────────────────────────

    /** 期望全表（Key → Android 常量名）：与生产 TABLE_SPEC 逐条对钉——缺失/多出/换算错全红 */
    private val expectedTableSpec: List<Pair<Key, String>> = listOf(
        Key.Enter to "KEYCODE_ENTER",
        Key.NumPadEnter to "KEYCODE_NUMPAD_ENTER",
        Key.Backspace to "KEYCODE_DEL",
        Key.Delete to "KEYCODE_FORWARD_DEL",
        Key.Tab to "KEYCODE_TAB",
        Key.Escape to "KEYCODE_BACK",
        Key.DirectionUp to "KEYCODE_DPAD_UP",
        Key.DirectionDown to "KEYCODE_DPAD_DOWN",
        Key.DirectionLeft to "KEYCODE_DPAD_LEFT",
        Key.DirectionRight to "KEYCODE_DPAD_RIGHT",
        Key.DirectionCenter to "KEYCODE_DPAD_CENTER",
        Key.MoveHome to "KEYCODE_MOVE_HOME",
        Key.MoveEnd to "KEYCODE_MOVE_END",
        Key.PageUp to "KEYCODE_PAGE_UP",
        Key.PageDown to "KEYCODE_PAGE_DOWN",
        Key.Spacebar to "KEYCODE_SPACE",
        Key.A to "KEYCODE_A",
        Key.B to "KEYCODE_B",
        Key.C to "KEYCODE_C",
        Key.D to "KEYCODE_D",
        Key.E to "KEYCODE_E",
        Key.F to "KEYCODE_F",
        Key.G to "KEYCODE_G",
        Key.H to "KEYCODE_H",
        Key.I to "KEYCODE_I",
        Key.J to "KEYCODE_J",
        Key.K to "KEYCODE_K",
        Key.L to "KEYCODE_L",
        Key.M to "KEYCODE_M",
        Key.N to "KEYCODE_N",
        Key.O to "KEYCODE_O",
        Key.P to "KEYCODE_P",
        Key.Q to "KEYCODE_Q",
        Key.R to "KEYCODE_R",
        Key.S to "KEYCODE_S",
        Key.T to "KEYCODE_T",
        Key.U to "KEYCODE_U",
        Key.V to "KEYCODE_V",
        Key.W to "KEYCODE_W",
        Key.X to "KEYCODE_X",
        Key.Y to "KEYCODE_Y",
        Key.Z to "KEYCODE_Z",
        Key.Zero to "KEYCODE_0",
        Key.One to "KEYCODE_1",
        Key.Two to "KEYCODE_2",
        Key.Three to "KEYCODE_3",
        Key.Four to "KEYCODE_4",
        Key.Five to "KEYCODE_5",
        Key.Six to "KEYCODE_6",
        Key.Seven to "KEYCODE_7",
        Key.Eight to "KEYCODE_8",
        Key.Nine to "KEYCODE_9",
        Key.Grave to "KEYCODE_GRAVE",
        Key.Minus to "KEYCODE_MINUS",
        Key.Equals to "KEYCODE_EQUALS",
        Key.LeftBracket to "KEYCODE_LEFT_BRACKET",
        Key.RightBracket to "KEYCODE_RIGHT_BRACKET",
        Key.Backslash to "KEYCODE_BACKSLASH",
        Key.Semicolon to "KEYCODE_SEMICOLON",
        Key.Apostrophe to "KEYCODE_APOSTROPHE",
        Key.Comma to "KEYCODE_COMMA",
        Key.Period to "KEYCODE_PERIOD",
        Key.Slash to "KEYCODE_SLASH",
    )

    @Test
    fun `keycode table matches expected spec via core name lookup`() {
        val expected = expectedTableSpec.associate { (key, name) ->
            key to requireNotNull(AndroidKeyCodes.byName(name)) { "全量表缺 $name" }.code
        }
        assertEquals(expected, ScreencastKeymap.KEYCODE_TABLE, "键位矩阵与期望表失配（缺失/多出/换算错）")
    }

    @Test
    fun `keycode table pins literal android values`() {
        val table = ScreencastKeymap.KEYCODE_TABLE
        // Android KeyEvent 公开常量字面量锚（键值是公开 API 事实数据——换算链再验一道）
        assertEquals(66, table[Key.Enter], "Enter→AKEYCODE_ENTER")
        assertEquals(160, table[Key.NumPadEnter], "小键盘 Enter→AKEYCODE_NUMPAD_ENTER")
        assertEquals(67, table[Key.Backspace], "Backspace→AKEYCODE_DEL")
        assertEquals(112, table[Key.Delete], "Delete→AKEYCODE_FORWARD_DEL")
        assertEquals(61, table[Key.Tab], "Tab→AKEYCODE_TAB")
        assertEquals(4, table[Key.Escape], "Esc→AKEYCODE_BACK（DeviceKeys.BACK 语义锚）")
        assertEquals(19, table[Key.DirectionUp], "↑→DPAD_UP")
        assertEquals(20, table[Key.DirectionDown], "↓→DPAD_DOWN")
        assertEquals(21, table[Key.DirectionLeft], "←→DPAD_LEFT")
        assertEquals(22, table[Key.DirectionRight], "→→DPAD_RIGHT")
        assertEquals(23, table[Key.DirectionCenter], "中央→DPAD_CENTER")
        assertEquals(122, table[Key.MoveHome], "Home→AKEYCODE_MOVE_HOME")
        assertEquals(123, table[Key.MoveEnd], "End→AKEYCODE_MOVE_END")
        assertEquals(92, table[Key.PageUp], "PgUp→AKEYCODE_PAGE_UP")
        assertEquals(93, table[Key.PageDown], "PgDn→AKEYCODE_PAGE_DOWN")
        assertEquals(62, table[Key.Spacebar], "Space→AKEYCODE_SPACE")
        assertEquals(29, table[Key.A], "A→AKEYCODE_A")
        assertEquals(54, table[Key.Z], "Z→AKEYCODE_Z")
        assertEquals(7, table[Key.Zero], "0→AKEYCODE_0")
        assertEquals(16, table[Key.Nine], "9→AKEYCODE_9")
        assertEquals(55, table[Key.Comma], "逗号→AKEYCODE_COMMA")
        assertEquals(56, table[Key.Period], "句号→AKEYCODE_PERIOD")
    }

    // ── 双轨判定 ──────────────────────────────────────────────────────────

    private fun resolve(
        key: Key,
        codePoint: Int,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
    ): ScreencastKeyAction? = ScreencastKeymap.resolve(key, codePoint, ctrl, shift, alt, meta)

    @Test
    fun `character track injects printable text for letters digits symbols`() {
        assertEquals(ScreencastKeyAction.InjectText("a"), resolve(Key.A, 'a'.code), "小写字母走字符轨")
        assertEquals(ScreencastKeyAction.InjectText("A"), resolve(Key.A, 'A'.code, shift = true), "Shift+字母大写天然成文")
        assertEquals(ScreencastKeyAction.InjectText("1"), resolve(Key.One, '1'.code), "数字走字符轨")
        assertEquals(ScreencastKeyAction.InjectText(" "), resolve(Key.Spacebar, ' '.code), "空格走字符轨")
        assertEquals(ScreencastKeyAction.InjectText(";"), resolve(Key.Semicolon, ';'.code), "符号走字符轨")
        assertEquals(ScreencastKeyAction.InjectText("{"), resolve(Key.LeftBracket, '{'.code, shift = true), "Shift+符号走字符轨")
    }

    @Test
    fun `clipboard bridge intercepts bare ctrl c v before tracks`() {
        assertEquals(ScreencastKeyAction.ClipboardCopy, resolve(Key.C, 0x03, ctrl = true), "Ctrl+C=读回桥")
        assertEquals(ScreencastKeyAction.ClipboardPaste, resolve(Key.V, 0x16, ctrl = true), "Ctrl+V=粘贴桥")
        assertEquals(
            ScreencastKeyAction.InjectKey(AndroidKeyCodes.byName("KEYCODE_C")!!.code, 0x1001),
            resolve(Key.C, 0x03, ctrl = true, shift = true),
            "Ctrl+Shift+C 非裸组合→keycode 轨转发 metaState",
        )
    }

    @Test
    fun `modifier combos route to keycode track with meta state`() {
        assertEquals(
            ScreencastKeyAction.InjectKey(39, 0x1000),
            resolve(Key.K, 0x0B, ctrl = true),
            "Ctrl+K→KEYCODE_K+CTRL（主窗热键不达独立窗，键直达设备）",
        )
        assertEquals(
            ScreencastKeyAction.InjectKey(8, 0x1000),
            resolve(Key.One, '1'.code, ctrl = true),
            "Ctrl+数字不走字符轨（X11 上 Ctrl+数字 KeyChar 仍可打印——组合语义排除）",
        )
        assertEquals(
            ScreencastKeyAction.InjectKey(62, 0x1000),
            resolve(Key.Spacebar, ' '.code, ctrl = true),
            "Ctrl+Space→KEYCODE_SPACE+CTRL（设备端 IME 切换）",
        )
        assertEquals(
            ScreencastKeyAction.InjectKey(29, 0x10000),
            resolve(Key.A, 'a'.code, meta = true),
            "Meta+字母不走字符轨（修饰语义转发）",
        )
        assertEquals(
            ScreencastKeyAction.InjectKey(29, 0x1001),
            resolve(Key.A, 0x01, ctrl = true, shift = true),
            "Ctrl+Shift+字母→keycode 轨带双修饰",
        )
    }

    @Test
    fun `altgr printable survives ctrl plus alt exclusion`() {
        assertEquals(
            ScreencastKeyAction.InjectText("a"),
            resolve(Key.A, 'a'.code, ctrl = true, alt = true),
            "AltGr（Ctrl+Alt 形态）可打印产物仍走字符轨",
        )
    }

    @Test
    fun `function keys route via table and unmapped keys pass through`() {
        assertEquals(ScreencastKeyAction.InjectKey(19, 0), resolve(Key.DirectionUp, 0xFFFF), "方向键 keycode 轨")
        assertEquals(ScreencastKeyAction.InjectKey(23, 0), resolve(Key.DirectionCenter, 0xFFFF), "中央确认键")
        assertEquals(ScreencastKeyAction.InjectKey(4, 0), resolve(Key.Escape, 0xFFFF), "Esc 表内条目（转发区直发设备 BACK）")
        assertEquals(ScreencastKeyAction.InjectKey(66, 0), resolve(Key.Enter, '\r'.code), "Enter 控制码不入字符轨")
        assertEquals(ScreencastKeyAction.InjectKey(67, 0), resolve(Key.Backspace, '\b'.code), "退格控制码不入字符轨")
        assertEquals(ScreencastKeyAction.InjectKey(112, 0), resolve(Key.Delete, 0xFFFF), "前删键")
        assertEquals(ScreencastKeyAction.InjectKey(61, 0), resolve(Key.Tab, '\t'.code), "Tab 控制码不入字符轨")
        assertNull(resolve(Key.F5, 0xFFFF), "F 键未映射→放行")
        assertNull(resolve(Key.MediaPlay, 0xFFFF), "媒体键未映射→放行")
        assertNull(resolve(Key.NumLock, 0xFFFF), "NumLock 未映射→放行")
    }

    // ── metaState 位与字符轨纯函数 ────────────────────────────────────────

    @Test
    fun `meta state bits compose android meta flags`() {
        assertEquals(0, ScreencastKeymap.metaStateOf(false, false, false, false), "无修饰=0")
        assertEquals(0x1000, ScreencastKeymap.metaStateOf(ctrl = true, shift = false, alt = false, meta = false), "META_CTRL_ON")
        assertEquals(0x1, ScreencastKeymap.metaStateOf(ctrl = false, shift = true, alt = false, meta = false), "META_SHIFT_ON")
        assertEquals(0x2, ScreencastKeymap.metaStateOf(ctrl = false, shift = false, alt = true, meta = false), "META_ALT_ON")
        assertEquals(0x10000, ScreencastKeymap.metaStateOf(ctrl = false, shift = false, alt = false, meta = true), "META_META_ON")
        assertEquals(0x11003, ScreencastKeymap.metaStateOf(ctrl = true, shift = true, alt = true, meta = true), "四修饰位或集")
    }

    @Test
    fun `printable text boundary matrix`() {
        assertNull(ScreencastKeymap.printableInjectText(0x1F), "控制码不入字符轨")
        assertNull(ScreencastKeymap.printableInjectText(0x7F), "DEL 不入字符轨")
        assertNull(ScreencastKeymap.printableInjectText(0xFFFF), "CHAR_UNDEFINED 不入字符轨")
        assertNull(ScreencastKeymap.printableInjectText(0xD800), "孤立代理不成文")
        assertNull(ScreencastKeymap.printableInjectText(-1), "负码点不成文")
        assertNull(ScreencastKeymap.printableInjectText(Character.MAX_CODE_POINT + 1), "越界码点不成文")
        assertEquals(" ", ScreencastKeymap.printableInjectText(0x20), "空格 0x20 可打印下界")
        assertEquals("a", ScreencastKeymap.printableInjectText('a'.code))
        assertEquals("好", ScreencastKeymap.printableInjectText('好'.code), "BMP 汉字成文（键 events 侧罕见，纯函数完备性）")
        assertTrue(ScreencastKeymap.printableInjectText(Character.MAX_CODE_POINT) != null, "合法上界码点成文")
    }

    // ── 剪贴板桥纯函数 ────────────────────────────────────────────────────

    @Test
    fun `clipboard bridge paste plan and paste keycode`() {
        assertEquals(279, ScreencastClipboardBridge.PASTE_KEYCODE, "PASTE 键值=KEYCODE_PASTE")
        assertNull(ScreencastClipboardBridge.pasteText(null), "本机无剪贴板文本=零动作")
        assertNull(ScreencastClipboardBridge.pasteText(""), "空串零动作（不注空串）")
        assertEquals("hi", ScreencastClipboardBridge.pasteText("hi"), "非空文本原样粘贴")
        assertEquals(" ", ScreencastClipboardBridge.pasteText(" "), "空白串是合法内容照常粘贴")
    }

    @Test
    fun `clipboard bridge copy readback verdict`() {
        assertNull(ScreencastClipboardBridge.copyWriteValue(before = null, received = null), "从未读到且无新值=不写")
        assertEquals("y", ScreencastClipboardBridge.copyWriteValue(before = "x", received = "y"), "读回新值直写")
        assertEquals("", ScreencastClipboardBridge.copyWriteValue(before = "x", received = ""), "读回空串照写（清空本机语义）")
        assertEquals("x", ScreencastClipboardBridge.copyWriteValue(before = "x", received = null), "超时回退旧值（同值不重发——未变即真值）")
    }

    // ── 窗口分发接线（桌面真实转换链：AWT 事件 → Compose KeyEvent） ────────

    private class RecordingActions : ScreencastKeyDirectActions {
        val calls = mutableListOf<String>()
        var injectKeyResult = true

        override fun injectKey(keycode: Int, metaState: Int): Boolean {
            calls += "key($keycode,$metaState)"
            return injectKeyResult
        }

        override fun injectText(text: String): Boolean {
            calls += "text($text)"
            return true
        }

        override fun clipboardCopy() {
            calls += "copy"
        }

        override fun clipboardPaste() {
            calls += "paste"
        }

        override fun sendTextBar() {
            calls += "sendTextBar"
        }

        override fun exitTextBar() {
            calls += "exitTextBar"
        }
    }

    /**
     * AWT 事件源占位（EventObject 契约要求非空 source；裸 Component 免窗口 peer）。
     */
    private val awtSource: Component = object : Component() {}

    /**
     * 桌面转换函数（AWT 事件 → Compose KeyEvent）：Kotlin-internal（compose 未对
     * 消费方模块开放），JVM 面 public——经反射调用与生产输入管线同一条转换路径，
     * 非手工拼装 Compose 态。toComposeEvent 返回值是 value class 的未装箱载荷
     * （InternalKeyEvent），须经 box-impl 装回 [KeyEvent] 值。
     */
    private val toComposeEvent: java.lang.reflect.Method by lazy {
        Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            .methods.first { it.name == "toComposeEvent" }
    }

    private val keyEventBox: java.lang.reflect.Method by lazy {
        Class.forName("androidx.compose.ui.input.key.KeyEvent")
            .methods.first { it.name == "box-impl" }
    }

    private fun composeKeyEvent(awtId: Int, awtKeyCode: Int, keyChar: Char, modifiers: Int = 0): KeyEvent =
        keyEventBox.invoke(
            null,
            toComposeEvent.invoke(null, AwtKeyEvent(awtSource, awtId, 0L, modifiers, awtKeyCode, keyChar)),
        ) as KeyEvent

    private fun keyDown(awtKeyCode: Int, keyChar: Char, modifiers: Int = 0): KeyEvent =
        composeKeyEvent(AwtKeyEvent.KEY_PRESSED, awtKeyCode, keyChar, modifiers)

    private fun keyUp(awtKeyCode: Int, keyChar: Char): KeyEvent =
        composeKeyEvent(AwtKeyEvent.KEY_RELEASED, awtKeyCode, keyChar)

    @AfterTest
    fun resetController() {
        ScreencastDetachedWindowController.recall()
        ScreencastDetachedWindowController.windowHandle = null
    }

    @Test
    fun `dispatch sends printable char to text injection and consumes key down only`() {
        val actions = RecordingActions()
        val state = ScreencastKeyDirectState()
        assertTrue(dispatchDetachedWindowKey(keyDown(AwtKeyEvent.VK_A, 'a'), state, actions), "字符轨消费")
        assertEquals(listOf("text(a)"), actions.calls, "物理键 a→字符轨注入")
        actions.calls.clear()
        assertFalse(dispatchDetachedWindowKey(keyUp(AwtKeyEvent.VK_A, 0xFFFF.toChar()), state, actions), "KeyUp 不处理（双发纪律）")
        assertTrue(actions.calls.isEmpty(), "KeyUp 零动作")
    }

    @Test
    fun `dispatch esc in forward zone injects device back`() {
        val actions = RecordingActions()
        val state = ScreencastKeyDirectState()
        assertTrue(dispatchDetachedWindowKey(keyDown(AwtKeyEvent.VK_ESCAPE, 0xFFFF.toChar()), state, actions), "Esc 消费")
        assertEquals(listOf("key(4,0)"), actions.calls, "转发区 Esc=设备 BACK 注入（表内条目直达，不再关窗）")
        actions.calls.clear()
        assertFalse(dispatchDetachedWindowKey(keyUp(AwtKeyEvent.VK_ESCAPE, 0xFFFF.toChar()), state, actions), "Esc KeyUp 不处理（双发纪律）")
        assertTrue(actions.calls.isEmpty(), "KeyUp 零动作")
    }

    @Test
    fun `dispatch esc in text bar exits text mode without closing`() {
        val actions = RecordingActions()
        val state = ScreencastKeyDirectState()
        state.textBarHasFocus = true
        state.textBarText = "草稿保留"
        assertTrue(dispatchDetachedWindowKey(keyDown(AwtKeyEvent.VK_ESCAPE, 0xFFFF.toChar()), state, actions), "Esc 消费")
        assertEquals(listOf("exitTextBar"), actions.calls, "放行区 Esc=退出文本模式（blur+回焦画面），不关窗不发草稿")
        assertTrue(state.textBarText == "草稿保留", "草稿保留不发送（清空只属 sendTextBar）")
        actions.calls.clear()
        assertFalse(dispatchDetachedWindowKey(keyUp(AwtKeyEvent.VK_ESCAPE, 0xFFFF.toChar()), state, actions), "Esc KeyUp 放行不重复触发")
        assertTrue(actions.calls.isEmpty(), "KeyUp 零动作")
    }

    @Test
    fun `dispatch release zone passes keys through and enter sends text bar`() {
        val actions = RecordingActions()
        val state = ScreencastKeyDirectState()
        state.textBarHasFocus = true
        state.textBarText = "正在打字"
        assertFalse(dispatchDetachedWindowKey(keyDown(AwtKeyEvent.VK_A, 'a'), state, actions), "放行区字符放行本地输入框")
        assertTrue(actions.calls.isEmpty(), "放行区零注入")
        assertTrue(dispatchDetachedWindowKey(keyDown(AwtKeyEvent.VK_ENTER, '\r'), state, actions), "放行区 Enter 消费")
        assertEquals(listOf("sendTextBar"), actions.calls, "框内 Enter 直发文本条")
    }

    @Test
    fun `dispatch forward zone routes keymap and clipboard bridge`() {
        val actions = RecordingActions()
        val state = ScreencastKeyDirectState()
        assertTrue(dispatchDetachedWindowKey(keyDown(AwtKeyEvent.VK_UP, 0xFFFF.toChar()), state, actions), "方向键消费")
        assertEquals(listOf("key(19,0)"), actions.calls, "↑→DPAD_UP 注入")
        actions.calls.clear()
        assertTrue(
            dispatchDetachedWindowKey(
                keyDown(AwtKeyEvent.VK_C, 0x03.toChar(), InputEvent.CTRL_DOWN_MASK),
                state,
                actions,
            ),
            "Ctrl+C 消费",
        )
        assertEquals(listOf("copy"), actions.calls, "Ctrl+C=读回桥")
        actions.calls.clear()
        assertTrue(
            dispatchDetachedWindowKey(
                keyDown(AwtKeyEvent.VK_V, 0x16.toChar(), InputEvent.CTRL_DOWN_MASK),
                state,
                actions,
            ),
            "Ctrl+V 消费",
        )
        assertEquals(listOf("paste"), actions.calls, "Ctrl+V=粘贴桥")
        actions.calls.clear()
        assertTrue(
            dispatchDetachedWindowKey(
                keyDown(AwtKeyEvent.VK_K, 0x0B.toChar(), InputEvent.CTRL_DOWN_MASK),
                state,
                actions,
            ),
            "Ctrl+K 消费",
        )
        assertEquals(listOf("key(39,4096)"), actions.calls, "Ctrl+K 直达设备（主窗热键不达独立窗）")
        actions.calls.clear()
        assertFalse(dispatchDetachedWindowKey(keyDown(AwtKeyEvent.VK_F5, 0xFFFF.toChar()), state, actions), "未映射键放行")
        assertTrue(actions.calls.isEmpty(), "未映射键零动作")
    }

    @Test
    fun `dispatch unmapped key release does not fire actions`() {
        val actions = RecordingActions()
        val state = ScreencastKeyDirectState()
        assertFalse(
            dispatchDetachedWindowKey(keyDown(AwtKeyEvent.VK_NUM_LOCK, 0xFFFF.toChar()), state, actions),
            "NumLock 未映射→放行",
        )
        assertTrue(actions.calls.isEmpty())
    }
}
