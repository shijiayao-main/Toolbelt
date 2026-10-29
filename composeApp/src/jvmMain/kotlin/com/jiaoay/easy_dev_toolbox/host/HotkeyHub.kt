package com.jiaoay.easy_dev_toolbox.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.home.DOCK_ENTRIES

/**
 * 全局热键单点分发表（热键表唯一真源，单测直接覆盖）。
 *
 * 结构：键匹配收口 [match] 纯函数；动作执行经 @Volatile 可注入 handler——
 * Window 级在 application 作用域执行、不在 Compose 组合内，拿不到 ViewModel，
 * 只能回调桥接（注册点配对：main.kt 注入面板动作 / App() 注入导航动作）。
 *
 * 动作面：
 * - Ctrl+K：打开命令面板（main.kt 注入 [openCommandPalette]）
 * - Ctrl+Shift+P：命令面板命令模式（同上，`>` 前缀预填——VS Code 迁移用户第一反射）
 * - Ctrl+Tab / Ctrl+Shift+Tab：前/后循环**已打开 tab**（App() 注入 [cyclePage]；
 *   order = openTabs 现值——不扫全量页序）
 * - Ctrl+W：关闭当前文档 tab（App() 注入 [closeCurrentTab]——桌面惯例可关 tab 键盘通路，
 *   此前仅 tab 条 × 钮鼠标可达；当前页非文档 tab 时 toast 反馈，恒 consumed）
 * - Ctrl+,：打开设置页（HIG 惯例）
 * - Ctrl+1..6：跳转 dock 第 N 项（跳转序与 DOCK_ENTRIES 同源——单点数据；
 *   键面随 dock 空间集同步扩表，dock 不足 N 项的键不虚标）
 * - Esc：关闭顶层浮层（LIFO：HotkeyHelp 参考卡 → 命令面板，实现见 main.kt
 *   closeOverlays 注入点）
 * - Ctrl+M：标记 logcat 复现点（main.kt 注入 [markLogcatAnchor]——转发
 *   LogcatRuntime.engine.markAnchor()，仅抓取中消费返回 true，未抓取落空不抢键）
 *
 * [HOTKEY_TABLE] 以常量形式暴露本表供渲染（快捷键参考卡 / 设置页快捷键小节 /
 *    命令面板提示）——禁止文档与代码二次维护（键面提示与 [match] 必须同源）。
 */
object HotkeyHub {

    /** 热键动作（[match] 纯函数的产物；执行经下方可注入 handler） */
    internal enum class Action {
        OpenCommandPalette,
        OpenCommandPaletteCommandMode,
        CyclePage,
        CloseCurrentTab,
        OpenSettings,
        JumpDockItem,
        OpenHotkeyHelp,
        CloseOverlays,
        MarkLogcatAnchor,
    }

    val HOTKEY_TABLE: List<Pair<String, String>> = listOf(
        "Ctrl+K" to "搜索工具（命令面板）",
        "Ctrl+Shift+P" to "命令面板（仅命令）",
        "Ctrl+Tab" to "下一个已打开文档",
        "Ctrl+Shift+Tab" to "上一个已打开文档",
        "Ctrl+W" to "关闭当前文档 tab",
        "Ctrl+M" to "标记复现点（logcat 抓取中有效）",
        "Ctrl+," to "打开设置",
        "Ctrl+1..6" to "切换空间（工作台/智能体中心/Shell 工具/飞书桥接/工具/设备观测）",
        "F1" to "快捷键参考卡",
        "Esc" to "关闭浮层",
    )

    /** 打开命令面板；返回 consumed */
    @Volatile var openCommandPalette: (() -> Boolean)? = null

    /** 打开命令面板并预填 `>` 命令模式；返回 consumed */
    @Volatile var openCommandPaletteCommandMode: (() -> Boolean)? = null

    /** 关闭顶层浮层（全局 Esc）；返回 consumed */
    @Volatile var closeOverlays: (() -> Boolean)? = null

    /** 循环切页（backward = Ctrl+Shift+Tab）；恒 consumed（防裸 Tab 焦点副作用外漏） */
    @Volatile var cyclePage: ((backward: Boolean) -> Boolean)? = null

    /** 关闭当前文档 tab（当前页非文档 tab 时由 handler toast 反馈；恒 consumed） */
    @Volatile var closeCurrentTab: (() -> Boolean)? = null

    /**
     * 标记 logcat 复现点（Ctrl+M，M = Mark——复现瞬间抢时间动作的键盘通路）。
     * 仅抓取中消费返回 true；未抓取返回 false 落空不抢键（M 键让位其他语义）。
     * 注入点 = main.kt（App 级单例引擎直取，不依赖页面组合）。
     */
    @Volatile var markLogcatAnchor: (() -> Boolean)? = null

    /** 打开设置页；返回 consumed */
    @Volatile var openSettings: (() -> Boolean)? = null

    /** 跳转 dock 第 N 项（1..9）；返回 consumed（dock 不足 N 项时调用方返回 false） */
    @Volatile var jumpDockItem: ((index: Int) -> Boolean)? = null

    /** 快捷键参考卡开合（键位见 [HOTKEY_TABLE] 帮助行）；返回 consumed */
    @Volatile var openHotkeyHelp: (() -> Boolean)? = null

    /** 数字键 1..9 集（JumpDockItem 匹配面） */
    /**
     * 键面提示表（渲染唯一真源）：key = 显示形式（平台无关文字，如 "Ctrl+K"），
     * label = 动作中文名。新增热键 = [match] 加行 + 本表加行，两处同文件防漂移。
     * 空间/文档分离模型：Ctrl+Tab 只在已打开文档 tab 间
     * 循环；Ctrl+1..6 跳转序 = dock 六空间（工作台/智能体中心/Shell 工具/飞书桥接/
     * 工具/设备观测）——dock 集之外的键不虚标（跳转序与 DOCK_ENTRIES 同源）。
     */
    private val digitKeys: Map<Key, Int> = mapOf(
        Key.One to 1,
        Key.Two to 2,
        Key.Three to 3,
        Key.Four to 4,
        Key.Five to 5,
        Key.Six to 6,
        Key.Seven to 7,
        Key.Eight to 8,
        Key.Nine to 9,
    )

    /**
     * 键匹配纯函数（热键表唯一真源）：修饰键 + 主键 → 动作；无匹配返回 null。
     * 与 [dispatch] 分离是为了单测——[KeyEvent] 依赖平台事件构造，纯函数面才可直测。
     *
     * [match] 只看 ctrl/shift 两个修饰键（无 Alt 分流例外）。
     */
    internal fun match(ctrl: Boolean, shift: Boolean, key: Key): Action? = when {
        ctrl && shift && key == Key.P -> Action.OpenCommandPaletteCommandMode
        ctrl && key == Key.K -> Action.OpenCommandPalette
        ctrl && key == Key.Tab -> Action.CyclePage
        ctrl && key == Key.W -> Action.CloseCurrentTab
        ctrl && key == Key.M -> Action.MarkLogcatAnchor
        ctrl && key == Key.Comma -> Action.OpenSettings
        ctrl && digitKeys.containsKey(key) -> Action.JumpDockItem
        // 帮助键无修饰键惯例（HIG——help key 单键直触发，Ctrl 组合不占）
        !ctrl && key == Key.F1 -> Action.OpenHotkeyHelp
        !ctrl && key == Key.Escape -> Action.CloseOverlays
        else -> null
    }

    /**
     * Window 级单点分发入口（main.kt 的 Window.onPreviewKeyEvent 只调本函数）。
     * 🔴 必须 KeyDown 过滤：一次物理按键派发 KeyDown + KeyUp 两个事件，
     *    不过滤会触发动作两次（沿自 main.kt 原实现的红线注释）。
     */
    fun dispatch(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val action = match(event.isCtrlPressed, event.isShiftPressed, event.key)
        return when (action) {
            Action.OpenCommandPalette -> openCommandPalette?.invoke() ?: false
            Action.OpenCommandPaletteCommandMode -> openCommandPaletteCommandMode?.invoke() ?: false
            Action.CyclePage -> cyclePage?.invoke(event.isShiftPressed) ?: false
            Action.CloseCurrentTab -> closeCurrentTab?.invoke() ?: false
            Action.MarkLogcatAnchor -> markLogcatAnchor?.invoke() ?: false
            Action.OpenSettings -> openSettings?.invoke() ?: false
            Action.JumpDockItem -> jumpDockItem?.invoke(digitKeys[event.key] ?: 1) ?: false
            Action.OpenHotkeyHelp -> openHotkeyHelp?.invoke() ?: false
            Action.CloseOverlays -> closeOverlays?.invoke() ?: false
            null -> false
        }
    }
}

/**
 * 循环切页纯函数：在顺序表 [order] 中定位 [current] 并前/后移一位（环形取模）。
 * [current] 不在表内时返回 null（兜底语义由调用方决定，测试直接覆盖本函数）。
 * order 的生产侧语义 = openTabs 现值（已打开 tab 集，
 *  保序列表）——本函数仍是任意 List<ToolPage> 纯函数，签名不动。
 */
internal fun stepPage(order: List<ToolPage>, current: ToolPage, backward: Boolean): ToolPage? {
    val index = order.indexOf(current)
    if (index < 0) return null
    val direction = if (backward) -1 else 1
    return order[(index + direction).mod(order.size)]
}

@Composable
internal fun rememberHotkeyActions(
    currentPage: () -> ToolPage,
    navigate: (ToolPage) -> Unit,
    openTabs: () -> List<ToolPage>,
) {
    DisposableEffect(Unit) {
        HotkeyHub.cyclePage = { backward ->
            // Ctrl+Tab 循环表收窄为
            // openTabs 现值（已打开 tab 间循环）——不扫全量页序。固定集只剩三 tab
            // 也照常循环（环形取模天然单元素自返，无需特判）。
            val order = openTabs()
            val next = stepPage(order, currentPage(), backward)
            if (next != null) {
                navigate(next)
            } else {
                // 空集/当前页不在表内 = 死键无反馈——补 toast
                // 告知出路。空间页（工作台等六空间）切页不进 openTabs（VM 分流语义），
                // 本分支对「停在空间页按 Ctrl+Tab」是常态可达路径，非纯防御。
                StatusSignals.show("没有已打开的文档 tab——Ctrl+K 打开报告或深入模式")
            }
            // 恒 consumed（与 main.kt 原行为一致）：Ctrl+Tab 放行会让裸 Tab
            // 触发 Compose 焦点遍历，焦点跳转比"无 tab 可循环不出反馈"更违和
            true
        }
        onDispose { HotkeyHub.cyclePage = null }
    }
}