package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 投屏独立视口窗控制器（App 级单例 object，[ScreencastRuntime] 同包同配方）：独立窗
 * 存在性的唯一收口——「防重复窗口」硬需求由此结构性达成：窗口组合
 * （[ScreencastDetachedWindowHost]）由唯一 [visible] 快照态驱动，结构上第二扇窗
 * 不可表达。全仓一切「独立展示」点选面（面板行内入口钮、未来 palette
 * 命令）只准汇入 [openOrFocus]，任何调用点不得自行组合 Window（全仓 Window 组合
 * 点锚=2 由 composeApp arch 守护 WindowCompositionAnchorGuardTest 钉死）。
 *
 * [openOrFocus] 三分支（施工真源 = 投屏独立窗设计档案reencast-window-design.md §4.3）：
 * 1. `!visible` → 置 true（下一帧条件组合挂载新窗）；
 * 2. 已开且窗口活着（[ScreencastDetachedWindowHandle.isShowing]）→ 恢复聚焦
 *    （最小化还原 + toFront + requestFocus）；
 * 3. 已开但句柄未回填 = 挂载间隙（visible 已置真、窗口组合首帧未完成）→ 记
 *    focusPending，窗口内容根挂载时经 [onWindowAttached] 消费聚焦；句柄在但
 *    `!isShowing` = 死窗（组合外 dispose 的平台异常路径）→ [recall] 重稳——
 *    聚焦退化为「关悬挂态，用户再点=重开」，不变式「visible=true ⟺ 窗口在组合中」恢复。
 *
 * 同帧 close→open = last-write-wins 已定义语义（先收后开=窗保持开）：[recall] 置
 * false 与 [openOrFocus] 置 true 落同一快照帧时重组只见 true，窗口从未离开组合；
 * 用户感知一次「没关掉」无结构危害，不引入帧对齐复杂度。
 *
 * 引擎无关：停投自动关窗的观察面在窗口组合内（内容件 collect engine.status，窗口
 * 不在=无 collect=零泄漏），落点只调 [recall]——本单例不持引擎引用、不自建 scope；
 * 窗关即停投的外呼经 [onSessionEnd] 注入（窗口组合层注册），recall 汇点统一调用。
 */
object ScreencastDetachedWindowController {

    /**
     * 条件组合挂载锚：true = 独立窗应在组合中。生产面只经 [openOrFocus]/[recall]
     * 变更；setter internal 仅供同模块测试播种与 @AfterTest 复位。
     */
    var visible by mutableStateOf(false)
        internal set

    /**
     * 连接段超时回执（true = startCast 的连接预算走完仍悬在连接段、未出画也未落终态）。
     * 受理即开窗后的单源回执位：窗内连接段超时态（重试/取消钮）为主呈现面，主窗行卡
     * 超时细行为兜底——显示面按「status 为 Connecting」门控（离开连接段即不再显示），
     * 清理点 = 下一会话受理（VM）与窗内重试（Content）。
     */
    var connectionTimedOut by mutableStateOf(false)
        internal set

    /**
     * 窗口句柄：窗口内容根挂载时经 [onWindowAttached] 回填，组合销毁经
     * [onWindowDisposed] 清。null = 窗口不在组合中（或挂载间隙）。聚焦收口抽象为
     * [ScreencastDetachedWindowHandle]——java.awt.Window 在 headless 测试 JVM 无法
     * 构造，抽象面使三分支状态机纯 JVM 可测（生产实现包 ComposeWindow）。
     */
    internal var windowHandle: ScreencastDetachedWindowHandle? = null

    /** 挂载间隙再点标记：窗口内容根挂载时消费一次后清零 */
    private var focusPending = false

    /**
     * 窗关即停投外呼：由窗口组合层（Host 内容根挂载）注册、销毁清零——本单例不持引擎
     * 引用，停投路由经组合层注入（生产 = 引擎 stop，幂等）。null = 无窗在组合（或测试
     * 未注册）——recall 只收窗不停投。
     */
    internal var onSessionEnd: (() -> Unit)? = null

    /** 唯一开窗入口（三分支语义见类 KDoc） */
    fun openOrFocus() {
        when {
            !visible -> visible = true
            windowHandle?.isShowing == true -> focusCurrent()
            windowHandle == null -> focusPending = true
            else -> recall()
        }
    }

    /**
     * 唯一关闭路径（窗 onCloseRequest / 停投自关 / 死窗重稳三汇点共汇于此；键盘 Esc
     * 不关窗——转发区 Esc=设备返回、放行区 Esc=退出文本模式）：窗关即停投——所有用户
     * 可见的关窗路径统一经 [onSessionEnd] 外呼停投（停投自关路径引擎已停，幂等
     * no-op）。
     */
    fun recall() {
        focusPending = false
        onSessionEnd?.invoke()
        visible = false
    }

    /**
     * 窗口内容根挂载（窗口组合内 DisposableEffect 调用）：回填句柄并消费挂载间隙的
     * focusPending（分支 3 收口）；重复 attach（同窗重复挂载）不重复聚焦。
     */
    internal fun onWindowAttached(handle: ScreencastDetachedWindowHandle) {
        windowHandle = handle
        if (focusPending) {
            focusPending = false
            focusCurrent()
        }
    }

    /**
     * 窗口组合销毁兜底（onDispose 路径，与 onCloseRequest→[recall] 双路）：清句柄；
     * visible 仍为 true（平台异常路径下窗口先于状态离开组合）时回写 false——isShowing
     * 兜底语义。独立窗开关是离散动作非连续拖拽，无需主窗几何记忆的防抖。
     */
    internal fun onWindowDisposed() {
        windowHandle = null
        if (visible) visible = false
    }

    /** 聚焦当前窗口（runCatching 包裹：X11 focus-stealing-prevention 降级为任务栏闪烁，不得上抛） */
    private fun focusCurrent() {
        val handle = windowHandle ?: return
        runCatching { handle.focusAndRaise() }
    }
}

/**
 * 独立窗句柄最小抽象（isShowing 探测 + 恢复聚焦）：生产实现包 ComposeWindow
 * （extendedState 清 ICONIFIED + toFront + requestFocus AWT 双调）。
 */
internal interface ScreencastDetachedWindowHandle {
    val isShowing: Boolean

    /** 最小化还原 + toFront + requestFocus（AWT 双调——toFront 单调在 X11 WM 下可能只闪任务栏） */
    fun focusAndRaise()
}
