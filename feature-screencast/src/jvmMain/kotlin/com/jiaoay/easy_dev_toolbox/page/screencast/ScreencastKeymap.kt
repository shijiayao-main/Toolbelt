package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.input.key.Key
import com.jiaoay.toolbelt.device.AndroidKeyCodes

/**
 * 独立视口窗键直通动作（[ScreencastKeymap.resolve] 的产物）：
 * - [InjectKey]：keycode 轨——单键注入（引擎 controlInjectKey 同语义：DOWN+UP 成对一笔完成）；
 * - [InjectText]：字符轨——文本注入（设备端 IME 语义，引擎 controlInjectText 同语义）；
 * - [ClipboardCopy]/[ClipboardPaste]：剪贴板桥（本地语义动作，经 [ScreencastClipboardBridge]）。
 */
internal sealed interface ScreencastKeyAction {

    /** keycode 轨：[keycode] 为 Android `KEYCODE_*` 整值（真源 = :core AndroidKeyCodes），[metaState] 为 Android META_* 位或集 */
    data class InjectKey(val keycode: Int, val metaState: Int) : ScreencastKeyAction

    /** 字符轨：注入文本（宿主键盘布局的 Shift/AltGr 产物天然成文，大小写与符号零换算） */
    data class InjectText(val text: String) : ScreencastKeyAction

    /** Ctrl+C 剪贴板桥：请求读回设备剪贴板写本机（GET_CLIPBOARD 读回型协议） */
    data object ClipboardCopy : ScreencastKeyAction

    /** Ctrl+V 剪贴板桥：本机剪贴板 → set_clipboard → 注入 PASTE 键 */
    data object ClipboardPaste : ScreencastKeyAction
}

/**
 * 独立视口窗键直通映射表（纯函数/纯数据，施工真源 = 投屏独立窗设计档案（键映射表自足于本文件）
 * screencast-window-design.md §4.2 双轨直通模型）：
 *
 * 1. 字符轨：宿主键事件携带可打印 unicode（KeyChar 派生）→ [ScreencastKeyAction.InjectText]；
 *    修饰组合语义（Ctrl 无 Alt / Meta）下不走字符轨——组合键的 metaState 需原样转发，
 *    字符产物会吞掉修饰语义（X11 上 Ctrl+数字的 KeyChar 仍是可打印数字，必须排除）；
 *    Ctrl+Alt 组合除外（AltGr 的字符产物正是可打印 unicode，排除即废掉 AltGr 布局）。
 * 2. keycode 轨：无字符功能键与修饰组合经 [KEYCODE_TABLE] 映射为 Android 键值 +
 *    [metaStateOf] 转发。键值零新数字——全部按常量名查 :core AndroidKeyCodes 全量表。
 * 3. 剪贴板桥：裸 Ctrl+C/V 为本地动作（设备键值转发不覆盖）。
 *
 * 本表只回答「这个 KeyDown 注入什么」；KeyEvents 的类型过滤（只处理 KeyDown）、焦点
 * 放行区判定与放行区的 Esc/Enter 本地语义在窗口接线层（ScreencastDetachedWindow.kt
 * dispatch）。[ScreencastKeyAction] 为 null = 未映射键，放行（独立窗无本地消费面，放行不有害）。
 *
 * 长按连发语义：宿主 auto-repeat = 多次 KeyDown = 多次判定注入，无需表外处理。
 */
internal object ScreencastKeymap {

    /** 表真源（常量名形态，键值经 :core AndroidKeyCodes 单点换算——本文件零键值数字） */
    private val TABLE_SPEC: List<Pair<Key, String>> = listOf(
        // 功能键族
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
        // 修饰组合族（裸键被字符轨截获，不会走到本表）
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

    /**
     * keycode 轨映射表（Compose Key → Android 键值）。条目分两族：
     * - 功能键：Enter/退格/前删/Tab/方向十字/Home/End/PgUp/PgDn——裸键直达（无字符产物）；
     * - 字母/数字/符号/空格：裸键恒走字符轨，仅修饰组合（Ctrl+K 等）落本表并转发 metaState。
     *
     * Esc 映射 KEYCODE_BACK（`DeviceKeys.BACK` 语义锚，scrcpy 官方同映射）：转发区
     * （画面节点持焦）Esc=设备返回，与设备端 UI 的返回反射同构；文本条放行区的
     * Esc=退出文本模式是本地语义，在分发层处理不经本表。
     */
    internal val KEYCODE_TABLE: Map<Key, Int> =
        TABLE_SPEC.associate { (key, androidConstantName) -> key to androidKeyCodeOf(androidConstantName) }

    /**
     * 双轨判定（纯函数，KeyDown 语义——调用方须先过滤事件类型）：
     * [key] = Compose 键面，[codePoint] = 宿主键事件字符载荷（desktop 上由 AWT KeyChar
     * 派生；无字符键为 CHAR_UNDEFINED = 0xFFFF），ctrl/shift/alt/meta = 修饰键按下态。
     * null = 未映射，放行。
     */
    internal fun resolve(
        key: Key,
        codePoint: Int,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        meta: Boolean,
    ): ScreencastKeyAction? {
        // 剪贴板桥先于双轨（裸 Ctrl+C/V 不作为设备键值转发；带其余修饰的组合落 keycode 轨）
        if (ctrl && !shift && !alt && !meta) {
            when (key) {
                Key.C -> return ScreencastKeyAction.ClipboardCopy
                Key.V -> return ScreencastKeyAction.ClipboardPaste
                else -> Unit
            }
        }
        // 字符轨：可打印且非组合语义（见类 KDoc 的 Ctrl/Meta 排除与 AltGr 豁免）
        if (!isModifierCombo(ctrl, alt, meta)) {
            printableInjectText(codePoint)?.let { return ScreencastKeyAction.InjectText(it) }
        }
        // keycode 轨：转发修饰态（Android 端无义无害——语义裁决归设备侧焦点应用）
        val keycode = KEYCODE_TABLE[key] ?: return null
        return ScreencastKeyAction.InjectKey(keycode, metaStateOf(ctrl, shift, alt, meta))
    }

    /** 字符轨的组合语义排除：Ctrl（无 Alt，AltGr 即 Ctrl+Alt 形态）或 Meta 按下即组合键 */
    private fun isModifierCombo(ctrl: Boolean, alt: Boolean, meta: Boolean): Boolean =
        (ctrl && !alt) || meta

    /**
     * Android metaState 位或集（android.view.KeyEvent `META_*` 公开 API 值——scrcpy
     * inject_keycode 的 metaState 字段直收该语义）：SHIFT=0x1 / ALT=0x2 / CTRL=0x1000 /
     * META=0x10000。:core AndroidKeyCodes 只收录键值目录不含修饰态位，位值在本文件登记
     * （公开 API 常量，同 AndroidKeyCodes 的许可边界）。
     */
    internal fun metaStateOf(ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): Int {
        var state = 0
        if (shift) state = state or META_SHIFT_ON
        if (alt) state = state or META_ALT_ON
        if (ctrl) state = state or META_CTRL_ON
        if (meta) state = state or META_META_ON
        return state
    }

    /**
     * 字符轨可打印判定与成文（纯函数）：null = 非可打印（控制码 < 0x20、DEL 0x7F、
     * AWT CHAR_UNDEFINED 0xFFFF、孤立代理区、越界码点）→ 不走字符轨。可打印值恒成
     * 单字符文本（含空格 0x20）。
     */
    internal fun printableInjectText(codePoint: Int): String? {
        if (codePoint < PRINTABLE_CODE_POINT_MIN) return null
        if (codePoint > Character.MAX_CODE_POINT) return null
        if (codePoint == CHAR_UNDEFINED) return null
        if (codePoint == DEL_CODE_POINT) return null
        if (codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) return null
        return String(Character.toChars(codePoint))
    }

    /** Android 键值单点换算：按常量名查 :core 全量表，缺名即 fail-fast（表与全量表失配） */
    internal fun androidKeyCodeOf(androidConstantName: String): Int =
        requireNotNull(AndroidKeyCodes.byName(androidConstantName)) {
            "AndroidKeyCodes 缺少常量 $androidConstantName（键直通映射表与 :core 全量表失配）"
        }.code

    private const val PRINTABLE_CODE_POINT_MIN = 32

    private const val DEL_CODE_POINT = 0x7F

    /** AWT 无字符键哨兵（KeyEvent.CHAR_UNDEFINED） */
    private const val CHAR_UNDEFINED = 0xFFFF

    private const val META_SHIFT_ON = 0x1

    private const val META_ALT_ON = 0x2

    private const val META_CTRL_ON = 0x1000

    private const val META_META_ON = 0x10000
}

/**
 * Ctrl+C/V 本机剪贴板桥的纯判定面（协议面 = 引擎 controlSetClipboard/controlRequestClipboard
 * 既有五型控制注入；本对象只做值裁决，连接面在窗口接线层）。
 */
internal object ScreencastClipboardBridge {

    /** 粘贴键值（KEYCODE_PASTE，:core 全量表单源）：set_clipboard 落地后注入本键触发设备端粘贴 */
    internal val PASTE_KEYCODE: Int = ScreencastKeymap.androidKeyCodeOf("KEYCODE_PASTE")

    /**
     * Ctrl+V 粘贴载荷：null = 本机剪贴板无文本（null 或空串，零动作不注空串）；
     * 空白串（如单个空格）是合法内容照常粘贴。
     */
    internal fun pasteText(localText: String?): String? = localText?.takeIf { it.isNotEmpty() }

    /**
     * Ctrl+C 读回裁决（写本机剪贴板的值）：[received] = 读回等待窗内收到的设备剪贴板
     * 新值（null = 超时未等到与请求前不同的新值——StateFlow 同值不重发）。超时回退
     * [before]（请求前流内旧值）：设备剪贴板与上次读回一致时 before 即当前真值，重复
     * 复制同内容不因「无新值可等」而失灵；before 亦为 null = 本会话从未读到过设备剪贴板
     * （请求未响应），零动作不写入。
     */
    internal fun copyWriteValue(before: String?, received: String?): String? = received ?: before
}
