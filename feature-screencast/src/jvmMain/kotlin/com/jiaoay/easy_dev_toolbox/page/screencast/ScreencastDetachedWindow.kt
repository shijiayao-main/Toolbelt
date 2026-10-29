package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.foundation.focusable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.jiaoay.easy_dev_toolbox.core.data.DesktopUtils
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.toolbelt.device.DeviceFilePusher
import com.jiaoay.toolbelt.device.DeviceKey
import com.jiaoay.toolbelt.device.DeviceKeys
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.theme.AppTheme
import com.jiaoay.easy_dev_toolbox.core.theme.AppThemeSpec
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.ThemeMode
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.ChipTextButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverTooltipBox
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 首开手势引导已示标记（进程级真源——一次性引导不跨开窗重示、不落盘）。 */
private var screencastGestureIntroShown = false

/**
 * 显示触点开关的 hover 契约注记（常驻承载两义）：
 * 1. 注入输入不显示触点是 scrcpy 系统语义非开关失效——AI 代操/注入的输入看不到触点
 *    属预期，防误判开关坏了；
 * 2. 会话结束自动恢复设备原值（恢复语义三件的用户可见面）。
 */
internal const val SHOW_TOUCHES_HOVER_NOTE =
    "开启后设备显示物理触摸轨迹——仅物理触摸可见，注入输入不显示；投屏结束自动恢复原值"

/**
 * 测试复位：首开手势引导的一次性标记（进程级标记跨用例/跨组合残留——与
 * ReportDelivery.clearForTest 同纪律，用例收尾复位保断言确定性）。
 */
internal fun resetScreencastGestureIntroForTest() {
    screencastGestureIntroShown = false
}

/**
 * 独立窗初始几何（AWT 物理像素值，宿主经参传入——feature 不感知持久化与屏外防呆，
 * 合法性裁决在宿主读链）：null = 无记忆/非法/屏外 → 计算位兜底。x/y 允许负值
 * （多显示器副屏坐标系）。
 */
data class ScreencastDetachedInitialGeometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * 投屏独立视口窗宿主（条件组合挂载点）：存在性由 [ScreencastDetachedWindowController]
 * .visible 唯一驱动——「防重复窗口」的结构收口在本控制器，本组合件只做挂载表达
 * （全仓 Window 组合点锚=2 的第二锚，arch 守护 WindowCompositionAnchorGuardTest）。
 *
 * 窗口形态 = 系统装饰默认（undecorated/transparent 不设异值显式写出——transparent+
 * 30fps 视频流每帧过透明合成混合通道的成本账，
 * screencast-window-design.md §3.2）+ 恒置顶（与主窗并用：主窗操作其他工具时画面
 * 不被盖没，可缩可关）：WM 驱动几何，不挂 AppTitleBar/自绘缩放面。
 *
 * 几何记忆（宿主经参注入，feature 不感知持久化）：开窗初始几何优先读 [initialGeometry]
 * 记忆值（宿主读链已做屏外/非法值防呆；尺寸健全闸见
 * [isSoundDetachedSavedGeometry]——巨窗/碎窗记忆判无效），无记忆或记忆不健全回退
 * 计算位（右缘垂直居中——与主窗并用，不居中盖主窗）；关窗时经
 * [onPersistGeometry] 写回当前 bounds（onCloseRequest 直接写 + 组件 onDispose 兜底
 * 双覆盖，不复制主窗拖拽防抖——独立窗开关是离散动作）。
 *
 * 窗口内容 = 会话操作面（[ScreencastDetachedWindowContent]：停止投屏/开始投屏/快照/
 * 录像起停/画质档位弹层/设备键排/回执行，流与动作生产默认直连引擎单源）——主窗面板是纯启动器
 * 单行卡（开始/重试入口、聚焦窗口入口、提示与失败回执细行，不放投屏展示与控制）。
 * 窗内无 StatusSignals/toast 依赖（主窗独挂）——回执以持态行 + 行内瞬态文本双承载；
 * 窗关即停投（onCloseRequest → recall 汇点统一经注册外呼停投，见
 * [ScreencastDetachedWindowController.recall]）。
 *
 * 停止与关闭语义分离：正常停止（用户显式停投，含改档停投）后**窗持住**——主钮翻
 * 「开始投屏」、画质档位解锁可选（停止后选档是改档动线的自然一环，设置面不得随停止
 * 消失）；失败停止仍走驻留一瞥后自动关（原因短驻呈现）；**关窗只由窗关闭钮承载**
 * （窗 X = 关窗即停投）。双入口合流：Stopped 持窗期间从主窗面板行再点「开始投屏」
 * 也聚焦本窗（openOrFocus，不双窗）。
 *
 * @param icon 窗口图标（宿主经参传入：composeApp 资源对 feature 域不可达）
 * @param initialGeometry 初始几何提供者（每次开窗当帧同步调用——受理即开窗后开窗时刻
 *   不再与 Streaming 落定重合；返回 null = 回退计算位，Streaming 落定后按会话宽高
 *   做一次性校正，用户手动改过几何则跳过）
 * @param onPersistGeometry 几何写回外呼（AWT 物理像素四元组；落盘语义由宿主实现承载）
 * @param onSessionEnd 窗关即停投路由（挂载期注册进 [ScreencastDetachedWindowController]
 *   .onSessionEnd，recall 汇点统一调用）：生产默认 = 引擎 stop（幂等——停投自关路径
 *   引擎已停为 no-op）；注入供测试换 fake
 */
@Composable
fun ScreencastDetachedWindowHost(
    icon: Painter?,
    initialGeometry: () -> ScreencastDetachedInitialGeometry? = { null },
    onPersistGeometry: (x: Int, y: Int, width: Int, height: Int) -> Unit = { _, _, _, _ -> },
    onSessionEnd: () -> Unit = ScreencastRuntime.engine::stop,
    scheme: AppThemeSpec = AppThemeSpec.Default,
    themeMode: ThemeMode = ThemeMode.Dark,
) {
    // 恒组合 + visible 参数（GC 根治契约）：CMP 新窗恒继承「最后聚焦窗」的
    // GraphicsConfiguration（SwingWindow create 取 lastActiveGraphicsConfiguration），
    // 开投屏时聚焦窗必为透明主窗（32 位 ARGB GC）→ 窗被创建成 Depth 32 逐像素
    // 半透明面，在本机栈上从首帧起无法上屏（透底根因，取证见 realWindowDiag 与
    // 投屏透底根因档案ot-cause.md）。本窗改为应用
    // 启动即组合（此时尚无聚焦窗 → 默认 24 位 GC）、显示只切 visible——窗对象与
    // GC 在启动期定死，开窗时机不再影响 visual。AwtWindow 的 create 在组合入场即
    // 执行、visible 由 show tick 异步切 isVisible（前置探针实测：启动即组合 +
    // 聚焦宿主后显示 → GC=0x23 且内容在铺）。
    val visible = ScreencastDetachedWindowController.visible
    val workareaSizePx = remember {
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.let { it.width to it.height }
        }.getOrDefault(1_440 to 800)
    }
    val density = LocalDensity.current
    val minSizePx = remember(density) {
        with(density) {
            Dimens.screencastDetachedWindowMinWidth.roundToPx() to
                Dimens.screencastDetachedWindowMinHeight.roundToPx()
        }
    }
    // 开窗会话快照（标题与首帧几何的当帧真源；恒组合后 engine 快照不能再按组合期
    // 一次性读取——非 Streaming 期窗从不显示，此值仅在开窗当帧有意义）
    var openStreaming by remember { mutableStateOf<ScreencastStatus.Streaming?>(null) }
    // windowState 唯一实例（CMP 的窗监听器在 create 期捕获 state 引用，换实例会
    // 断开用户拖拽/缩放的回写）——初始参数留默认（窗显示前必经开窗当帧写入）
    val windowState = rememberWindowState()
    // 无记忆首开的一次性几何校正载荷（开窗当帧写入的计算位兜底几何——Streaming
    // 落定时若用户未手动改几何则按会话宽高校正一次；null = 无需校正/已校正）
    var pendingGeometryCorrection by remember {
        mutableStateOf<Pair<DpSize, WindowPosition>?>(null)
    }
    // AWT 窗体引用（内容挂载时回填）：开/关窗当帧处理与 onCloseRequest 经此取窗实例
    val attachedFrame = remember { mutableStateOf<java.awt.Frame?>(null) }
    // 开/关窗当帧处理（恒组合后组合期快照不再可用）：开窗 = 最新会话 + 几何记忆
    // 写入 windowState（组合期写 → 本帧 UpdateEffect 消费，先于 show tick 生效不
    // 闪跳）；关窗 = 几何持久化承接（onDispose 仅应用退出兜底，关闭路径的几何
    // 记忆由本处收口）
    var prevVisible by remember { mutableStateOf(false) }
    if (visible != prevVisible) {
        if (visible) {
            openStreaming = ScreencastRuntime.engine.status.value as? ScreencastStatus.Streaming
            // 几何记忆读回（每次开窗同步读——闭窗当帧已持久化，重开等值幂等；null =
            // 无记忆/非法/屏外 → 计算位兜底。记忆值以 AWT 物理像素存取，桌面密度
            // 1.0 下 Dp 与像素等值）。尺寸健全闸：陈旧/退化记忆（超工作区 0.85 的
            // 巨窗、低于最小窗 token 的碎窗）判无效回退计算位
            val savedGeometry = initialGeometry()
            val soundGeometry = savedGeometry?.takeIf {
                isSoundDetachedSavedGeometry(it, workareaSizePx.first, workareaSizePx.second, minSizePx.first, minSizePx.second)
            }
            val defaultSizePx = computeDetachedInitialSizePx(openStreaming, workareaSizePx.second)
            windowState.size = soundGeometry?.let { DpSize(it.width.dp, it.height.dp) }
                ?: with(density) { DpSize(defaultSizePx.first.toDp(), defaultSizePx.second.toDp()) }
            // 计算位默认形态 = 右缘垂直居中（与主窗并用：开窗后主窗左半可用，不再
            // 居中盖主窗）；px→dp 经密度换算与 size 同口径
            windowState.position = soundGeometry?.let { WindowPosition(it.x.dp, it.y.dp) }
                ?: with(density) {
                    WindowPosition(
                        (workareaSizePx.first - defaultSizePx.first).toDp(),
                        ((workareaSizePx.second - defaultSizePx.second) / 2).toDp(),
                    )
                }
            // 受理即开窗的伴生载荷：Connecting 期开窗走计算位兜底几何（无会话宽高，
            // 16:9 形态）——记录当帧写入值，Streaming 落定后按「用户未手动改几何」
            // 前置做一次性校正；有记忆开窗无需校正（null 保持）
            pendingGeometryCorrection = if (soundGeometry == null) {
                windowState.size to windowState.position
            } else {
                null
            }
        } else {
            attachedFrame.value?.let { persistDetachedWindowGeometry(it, onPersistGeometry) }
        }
        prevVisible = visible
    }
    // Streaming 落定补写（受理即开窗的伴生机制，App 期常驻单收集器）：Connecting 期
    // 开窗时 openStreaming 为空载（标题「投屏」、几何走计算位兜底），落定时补写会话
    // 快照；一次性几何校正以「windowState 仍等于开窗当帧写入值」为前置（用户改尺寸
    // 优先跳过）。写入经常规状态流（不经 show-tick 重放——U29 不闪跳不变式保持）；
    // SessionMeta 重发布（分辨率变化）不重入（openStreaming 非空即跳过）
    LaunchedEffect(Unit) {
        ScreencastRuntime.engine.status.collect { current ->
            if (current is ScreencastStatus.Streaming && openStreaming == null) {
                openStreaming = current
                val correction = pendingGeometryCorrection
                if (correction != null &&
                    windowState.size == correction.first &&
                    windowState.position == correction.second
                ) {
                    val fitSizePx = computeDetachedInitialSizePx(current, workareaSizePx.second)
                    windowState.size = with(density) {
                        DpSize(fitSizePx.first.toDp(), fitSizePx.second.toDp())
                    }
                    windowState.position = with(density) {
                        WindowPosition(
                            (workareaSizePx.first - fitSizePx.first).toDp(),
                            ((workareaSizePx.second - fitSizePx.second) / 2).toDp(),
                        )
                    }
                }
                pendingGeometryCorrection = null
            }
        }
    }
    val windowTitle = openStreaming?.let { "${it.deviceName} · 投屏" } ?: "投屏"

    // 键直通分区态 + 画面节点焦点句柄（放行区标志/草稿由内容件回写，预览 handler 消费）
    val keyDirect = remember { ScreencastKeyDirectState() }
    val frameFocus = remember { FocusRequester() }
    val keyActions = rememberEngineKeyDirectActions(keyDirect, frameFocus)
    // 置顶档会话态（不落盘——与会话级画质档同口径）：默认普通窗语义
    var windowPinned by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = { onDetachedWindowCloseRequested(attachedFrame.value, onPersistGeometry) },
        visible = visible,
        state = windowState,
        title = windowTitle,
        icon = icon,
        // 系统装饰形态：默认值显式写出（施工真源 §4.1 形态参数）
        undecorated = false,
        transparent = false,
        resizable = true,
        // 置顶可切：默认普通窗（不恒占前台，其他应用窗口可覆盖本窗）；置顶档供边操作
        // 主窗/其他工具边观察画面的形态（顶栏图钉钮切换，状态由内容件回写）
        alwaysOnTop = windowPinned,
        onPreviewKeyEvent = { event -> dispatchDetachedWindowKey(event, keyDirect, keyActions) },
    ) {
        // 窗口最小尺寸下限（防画面节点坍缩触发 mapPointerToScreen 的 (0,0) 注入）；
        // Dimens token 经密度换算（主窗 main.kt 裸 px 先例的 token 化）
        val contentDensity = LocalDensity.current
        val minSize = remember(contentDensity) {
            with(contentDensity) {
                java.awt.Dimension(
                    Dimens.screencastDetachedWindowMinWidth.roundToPx(),
                    Dimens.screencastDetachedWindowMinHeight.roundToPx(),
                )
            }
        }
        window.minimumSize = minSize

        // 句柄回填/清兜底（内容根 DisposableEffect——挂载间隙 focusPending 消费 +
        // isShowing 兜底回写，双路收口在控制器）；窗关即停投外呼同点注册/清零（挂载期
        // 恰是窗存在期，recall 汇点外呼可达）
        DisposableEffect(Unit) {
            attachedFrame.value = window
            ScreencastDetachedWindowController.onWindowAttached(FrameWindowHandle(window))
            ScreencastDetachedWindowController.onSessionEnd = onSessionEnd
            onDispose {
                // 组件销毁兜底写回（覆盖不经 onCloseRequest 的路径——停投自关 recall、
                // 平台 dispose；与 onCloseRequest 直接写同值幂等双覆盖）
                persistDetachedWindowGeometry(window, onPersistGeometry)
                ScreencastDetachedWindowController.onSessionEnd = null
                ScreencastDetachedWindowController.onWindowDisposed()
            }
        }

        // 独立窗主题自装配（主题 CompositionLocal 不跨 Window 传播，窗内无法继承主窗
        // 主题态——经参注入：宿主传主窗外观设置实时值，本窗随设置页/系统明暗翻转）
        AppTheme(scheme = scheme, themeMode = themeMode) {
            // AWT 层兜底背景（维护者真机四报 U16/U17/U25×2 实证：Compose 内容未上帧时
            // 窗透底——Compose 层背景链完整也不防「内容没画」的透底，此行使 Skia 未上帧/
            // 上帧失败时窗显示主题暗底而非透明白/透桌面；色值从本组合 colorScheme 实取，
            // 涂装/模式变更时随主题走不写死）
            val awtFallbackBg = java.awt.Color(MaterialTheme.colorScheme.background.toArgb())
            SideEffect {
                // AWT 兜底：Window.isOpaque() 是 final 派生值（由背景 Color 的 alpha 派生）
                // ——本窗透底症状=窗处于逐像素半透明面。alpha=FF 的暗底背景即把窗拉回
                // 不透明面（维护者真机实证内容仅纯黑背景上可见=半透明合成泄漏）；opacity
                // 复位兜住运行态被降不透明的路径
                window.setOpacity(1.0f)
                window.background = awtFallbackBg
            }
            ScreencastDetachedWindowContent(
                keyDirect = keyDirect,
                frameFocus = frameFocus,
                keyActions = keyActions,
                pinned = windowPinned,
                onPinnedChange = { windowPinned = it },
            )
        }
    }
}

/**
 * 独立窗会话操作面内容：直连引擎流（默认 = [ScreencastRuntime.engine] 单源）——顶栏
 * 恒高行（状态条 + 停止投屏/保存快照/录像起停/显示触点开关 + 画质档位弹层）+ 录像进行中提示行 +
 * 快照/录像回执行（Saved/Failed 持态双形态 + 行内瞬态文本）+ 满幅帧
 * （[ScreencastFrameView] heightCap=null，触摸/滚轮回传手势随共享件，注入直连引擎
 * 控制通道）+ 设备键排（[DeviceKeys.COMMON_KEYS]，BACK 鼠标一键返回——Esc→BACK
 * 键直通并存不冲突）+ 底部文本注入条（IME 提交到输入框、Enter 经窗口级预览直发设备
 * ——CJK 安全路径，逐字符直通对 CJK 不可达）+ 文件拖拽推送面（drop 目标限定本窗根层
 * ——全仓 drop 面唯一新增点在独立窗，主窗零 drop 改动；拖入文件经
 * [ScreencastDropPushRouter] 走 :core [DeviceFilePusher] 既有覆盖确认管线推送，
 * `.apk` 经 :core [com.jiaoay.toolbelt.device.DeviceApkInstaller] 安装
 * 管线恒过安装确认对话、逐文件回执经持态行呈现——与主窗其他 drop 消费点互不抢
 * 文件）。
 * 无 StatusSignals/toast 依赖（主窗独挂，窗内不可见）——回执持态行 + 行内瞬态文本
 * 双承载。
 *
 * 控制权分布（窗=会话操作面，主窗行卡=纯启动器）：画质档位弹层在窗内（起点生效——
 * server 无中途改档通道，running 态档位置灰 + 停投解锁引导注记；档位真源 =
 * [ScreencastQualityTierStore]，窗是投屏唯一使用场景故档位入口随操作面在窗内）；
 * 开始投屏入口/设备选择/失败回执细行仍在主窗（开始投屏依赖
 * [com.jiaoay.toolbelt.device.DeviceStateSource] 三流，窗不持设备域；
 * 失败终态窗自动关，回执由主窗行卡的失败细行承接）。
 *
 * 显式焦点分区（键直通放行区的结构基础）：画面节点 focusable 为焦点靶——开窗即聚焦，
 * 文本条聚焦态经 [ScreencastKeyDirectState.textBarHasFocus] 回写为放行区标志，窗内
 * 全部动作钮（顶栏/键排/回执行）onClick 收尾统一回焦画面节点，焦点漂移面在结构上
 * 收口。物理键注入本身不依赖焦点（窗口级 preview 在焦点派发前执行）。
 *
 * 停投自动关窗：观察面挂本组合内（[LaunchedEffect] collect）——窗口不在=无
 * collect=零泄漏，collect 存在期间恰是窗口存在期间；StateFlow.collect 首发当前值，
 * 挂载竞态不丢终态。离开 Connecting/Streaming（落 Stopped/Idle）即 [onAutoClose]。
 *
 * 全部流与动作为参数（生产默认直连引擎单源/档位档存单源）——组合单测以 fake 流与
 * fake [ScreencastDetachedWindowOps] 免真窗真引擎。
 *
 * @param keyDirect 键直通分区态（文本条草稿/放行区标志的宿主；Host 预览 handler 共享同一实例）
 * @param frameFocus 画面节点焦点句柄（回焦画面/开窗初聚焦；Host 与内容件共享同一实例）
 * @param keyActions 键直通动作面（null = 生产默认直连引擎；测试注入 fake）
 * @param ops 操作面动作（null = 生产默认直连引擎；测试注入 fake 记录调用/驱动回调）
 * @param dropPushRouter 拖拽推送路由器（null = 生产默认——UI scope + 默认推送内核；
 *   测试注入 fake 内核构造的 router 驱动回执行渲染）
 * @param pinned 窗置顶档会话态（false = 普通窗可被覆盖；true = 恒浮于其他窗口之上）
 * @param onPinnedChange 置顶档切换回写（顶栏图钉钮驱动；Host 把回写接到 Window alwaysOnTop）
 */
/**
 * 安装完成回执行文案（追溯档位呈现面）：基线档（文件名 + 大小 + adb 回显原文）
 * 恒在场，升档（包名 + 版本名）在场即拼接；升档缺席（升级覆盖/列举失败形态）按
 * [ScreencastDropPushLine.Installed] 的注明原文追加——不猜不补不翻译。
 */
private fun installedReceiptText(line: ScreencastDropPushLine.Installed): String {
    val identity = when {
        line.packageName != null -> "，${line.packageName}${line.versionName?.let { " v$it" } ?: ""}"
        else -> ""
    }
    val note = line.identityNote?.let { "——$it" } ?: ""
    return "已安装：${line.fileName}（${line.sizeBytes} B$identity）$note｜adb：${line.adbEcho}"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun ScreencastDetachedWindowContent(
    status: StateFlow<ScreencastStatus> = ScreencastRuntime.engine.status,
    frame: StateFlow<ScreencastFrame?> = ScreencastRuntime.engine.frame,
    fps: StateFlow<Int> = ScreencastRuntime.engine.fps,
    recordingState: StateFlow<ScreencastRecordingState> = ScreencastRuntime.engine.recordingState,
    recordingResult: StateFlow<ScreencastRecordingOutcome?> = ScreencastRuntime.engine.recordingResult,
    snapshotResult: StateFlow<ScreencastSnapshotOutcome?> = ScreencastRuntime.engine.snapshotResult,
    showTouchesEnabled: StateFlow<Boolean> = ScreencastRuntime.engine.showTouchesEnabled,
    onFrameTouch: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit =
        ScreencastRuntime.engine::controlInjectTouch,
    onFrameScroll: (
        x: Int, y: Int, screenWidth: Int, screenHeight: Int, hScroll: Float, vScroll: Float,
    ) -> Unit = ScreencastRuntime.engine::controlInjectScroll,
    onAutoClose: () -> Unit = ScreencastDetachedWindowController::recall,
    bitrateTierBits: StateFlow<Long?> = ScreencastQualityTierStore.bitrateTierBits,
    fpsTier: StateFlow<Float> = ScreencastQualityTierStore.fpsTier,
    maxSizeTier: StateFlow<Int> = ScreencastQualityTierStore.maxSizeTier,
    onBitrateTierChange: (Long?) -> Unit = ScreencastQualityTierStore::onBitrateTierChange,
    onFpsTierChange: (Float) -> Unit = ScreencastQualityTierStore::onFpsTierChange,
    onMaxSizeTierChange: (Int) -> Unit = ScreencastQualityTierStore::onMaxSizeTierChange,
    keyDirect: ScreencastKeyDirectState = remember { ScreencastKeyDirectState() },
    frameFocus: FocusRequester = remember { FocusRequester() },
    keyActions: ScreencastKeyDirectActions? = null,
    ops: ScreencastDetachedWindowOps? = null,
    dropPushRouter: ScreencastDropPushRouter? = null,
    connectionTimedOut: Boolean = ScreencastDetachedWindowController.connectionTimedOut,
    onClearConnectionTimeout: () -> Unit = { ScreencastDetachedWindowController.connectionTimedOut = false },
    pinned: Boolean = false,
    onPinnedChange: (Boolean) -> Unit = {},
) {
    val actions = keyActions ?: rememberEngineKeyDirectActions(keyDirect, frameFocus)
    val windowOps = ops ?: rememberEngineDetachedWindowOps()
    // 拖拽推送/安装路由器（生产默认 = 本窗 UI scope + 默认推送/安装内核；窗关
    // scope 取消即在途推送/安装收割——会话期限定的结构面，见 router 类头契约）
    val uiScope = rememberCoroutineScope()
    val dropPush = dropPushRouter ?: remember(uiScope) {
        ScreencastDropPushRouter(
            scope = uiScope,
            runner = com.jiaoay.easy_dev_toolbox.core.device.SystemAdbProcessRunner,
            endpointReader = com.jiaoay.easy_dev_toolbox.core.device.DeviceHub::currentEndpoint,
        )
    }
    val dropPushLines by dropPush.lines.collectAsState()
    val dropPushConfirm by dropPush.confirm.collectAsState()
    val apkInstallConfirm by dropPush.installConfirm.collectAsState()
    val statusValue by status.collectAsState()
    val currentFrame by frame.collectAsState()
    val fpsValue by fps.collectAsState()
    val recordingStateValue by recordingState.collectAsState()
    val recordingResultValue by recordingResult.collectAsState()
    val snapshotResultValue by snapshotResult.collectAsState()
    val showTouchesActive by showTouchesEnabled.collectAsState()
    val bitrateTierBitsValue by bitrateTierBits.collectAsState()
    val fpsTierValue by fpsTier.collectAsState()
    val maxSizeTierValue by maxSizeTier.collectAsState()
    // 画质档位起点生效的置灰判据：会话在跑（Connecting/Streaming）= server 无中途改档通道
    val qualityTiersLocked = statusValue is ScreencastStatus.Connecting ||
        statusValue is ScreencastStatus.Streaming
    // 快照「送飞书」置灰判据：真源 = ReportDelivery.ready 单源（与主窗面板同判据单源）
    val larkDeliveryReady by ReportDelivery.ready.collectAsState()

    // 行内瞬态文本（起录立即失败/送飞书结果/首开手势引导——窗无 toast 通道，回执人话
    // 经 ops 回调写此行，下次动作覆盖；ops 生产实现归 UI 线程投递）
    var transientNotice by remember { mutableStateOf<String?>(null) }

    // 停投重开/连接段真重试的自动关窗抑制编排标志：restart 链 = stop → Stopped →
    // start 的 StateFlow 合流路径，auto-close collect 见 Stopped 即关窗 = 编排期闪关
    // （竞态非确定）——编排期挂起该路径；Streaming 落定清（新会话接管常规生命周期）
    var restartInFlight by remember { mutableStateOf(false) }
    // 失败一瞥驻留消息（null = 无失败驻留）：引擎失败终态的窗内原因呈现——短驻留后
    // 自动关 + 「立即关闭」可点；正常停止即时关（两条关窗路径节奏一致）
    var failureLingerMessage by remember { mutableStateOf<String?>(null) }

    // 快照实拍在途（按钮置忙——adb 往返秒级，无忙态即「点了没反应」）：ops 回调收口
    // （UI 线程投递），回执本身另经 snapshotResult 流驱动回执行
    var snapshotInFlight by remember { mutableStateOf(false) }

    // 停投重开/连接段真重试（引擎 restart：停会话→等终态→按上次受理参数重启）。
    // 超时回执随重试起点清（[onClearConnectionTimeout]）；失败回执不静默——引擎
    // 未受理（无历史参数/等终态超时）时窗关（此时窗内 start 通道恒死，持窗无义，
    // 用户回主窗重新受理）
    fun restartSession(options: ScreencastOptions?) {
        restartInFlight = true
        onClearConnectionTimeout()
        windowOps.restart(options) { ok ->
            if (!ok) {
                restartInFlight = false
                transientNotice = "重新开始投屏失败——引擎未受理，请从主窗重新开始"
                val current = status.value
                if (current !is ScreencastStatus.Connecting && current !is ScreencastStatus.Streaming) {
                    onAutoClose()
                }
            }
            // ok = true：新会话已受理（Connecting）——Streaming 落定由状态收集器清
            // restartInFlight，常规生命周期接管
        }
    }

    // 按当前档位开始投屏（档位单源归一化快照）：Stopped 持窗主钮与弹层「按新档开始
    // 投屏」同动作同源，不各写
    fun startWithCurrentTiers() {
        restartSession(
            screencastOptionsFromTiers(
                bitrateTierBits = bitrateTierBitsValue,
                fps = fpsTierValue,
                maxSize = maxSizeTierValue,
            ),
        )
    }

    // 手势映射图例开关（顶栏「手势」图标钮驱动，帧区反馈叠层消费）
    var gestureHelpVisible by remember { mutableStateOf(false) }

    // 泊指计数（帧区泊指手势链经 onParkedCountChange 上抛；状态行「已泊 N 指」消费面）
    var parkedCount by remember { mutableStateOf(0) }

    // 首开一次性手势引导（进程级一次，不落盘：键位/手感是推荐档、真机终裁后仍可能调整，
    // 重启重引导成本可接受；经 transientNotice 行承载——可被下一动作覆盖跳过，不锁界面）
    val hasFrame = currentFrame != null
    LaunchedEffect(hasFrame) {
        if (hasFrame && !screencastGestureIntroShown) {
            screencastGestureIntroShown = true
            transientNotice = GESTURE_INTRO_NOTICE
        }
    }

    LaunchedEffect(status) {
        status.collect { current ->
            if (current is ScreencastStatus.Streaming) {
                // 新会话出画：重开编排交还常规生命周期，失败驻留若残留一并清
                restartInFlight = false
                failureLingerMessage = null
            }
            if (current is ScreencastStatus.Connecting) {
                failureLingerMessage = null
            }
            when {
                // 失败一瞥（仅失败停止）：窗不立即关——失败原因窗内呈现，短驻留后自动关
                // + 「立即关闭」可点。驻留期内重开编排启动时跳过收窗（restartInFlight
                // 接管生命周期）
                current is ScreencastStatus.Stopped && current.failed && !restartInFlight -> {
                    failureLingerMessage = current.message
                    delay(FAILURE_LINGER_MS)
                    failureLingerMessage = null
                    if (!restartInFlight) onAutoClose()
                }

                // 正常停止持窗（停止与关闭语义分离）：主钮翻「开始投屏」、档位弹层
                // 解锁——设置面不随停止消失；退出走窗关闭钮（关窗即停投）
                current is ScreencastStatus.Stopped && !current.failed -> Unit

                // 其余终态（Idle 等——窗开着但无会话可操作）：保持既有自动关
                current !is ScreencastStatus.Connecting &&
                    current !is ScreencastStatus.Streaming &&
                    !restartInFlight -> onAutoClose()

                else -> Unit
            }
        }
    }

    // 开窗即聚焦画面节点（放行区缺省关闭——物理键直达设备是独立窗的缺省语义；
    // 帧未到达时 FocusRequester 未挂靶，runCatching 吞弃，帧到达后可点画面回焦）
    LaunchedEffect(Unit) {
        runCatching { frameFocus.requestFocus() }
    }

    // 窗内动作钮 onClick 收尾回焦画面节点（配方同 EngineKeyDirectActions.refocusFrame
    // ——按钮持焦不驻留，Enter/空格注入设备不触发按钮的放行区边界不变：唯一放行区
    // = 文本条）
    fun refocusFrame() {
        runCatching { frameFocus.requestFocus() }
    }

    // 设备键排单键（FlowRow 换行 / 高度饥饿单行滚动两形态共用；[modifier] 由滚动
    // 形态传 widthIn 下限——AppButton 内部 Row 的 weight(1f, fill=false) 在横向滚动
    // 的无界宽约束下测 0（weight 需有界主轴），widthIn 供给下界后分配恢复，两形态
    // 同钮同几何）
    @Composable
    fun deviceKeyButton(key: DeviceKey, modifier: Modifier = Modifier) {
        AppButton(
            label = key.label,
            busy = false,
            onClick = {
                windowOps.injectKey(key.keyCode)
                refocusFrame()
            },
            kind = AppButtonKind.Secondary,
            modifier = modifier,
        )
    }

    // 内容根兜底背景：背景链自首建由 AppTheme 根层 Surface 提供（colorScheme.background
    // 铺满窗面），本 Column.background 为冗余双保险——帧未铺满/文本条周边不透出桌面
    //
    // 文件拖拽推送/安装的 drop 目标（窗根层——drop 面限定独立窗，主窗零 drop 改动）：
    // 拖入即经 [ScreencastDropPushRouter] 走推送/安装双管线，受理拒批人话落窗内
    // 瞬态文本行（窗无 toast 通道）
    val dropPushFilesTarget = remember(dropPush) {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) = Unit

            override fun onEnded(event: DragAndDropEvent) = Unit

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = (event.dragData() as? DragData.FilesList)
                    ?.readFiles()
                    ?.mapNotNull { runCatching { File(URI.create(it)) }.getOrNull() }
                    ?.filter { it.exists() && it.isFile }
                    .orEmpty()
                val serial = (statusValue as? ScreencastStatus.Streaming)?.serial
                val reject = dropPush.offer(files, serial)
                if (reject != null) transientNotice = reject
                // 消费判定 = 已受理本次拖入（拒批/拒收交回系统默认拖放形态）
                return reject == null
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
                target = dropPushFilesTarget,
            ),
    ) {
        // 窗面空帧自愈看门狗：独立窗是 32 位 ARGB visual（CMP 新窗继承最后聚焦窗
        // 的 GC——开投屏时聚焦主窗恒为透明窗），空帧被合成器混上桌面即透底，且窗
        // 内容静止/设备画面静止时无失效即永不重绘；本件周期制造失效驱动全窗重绘，
        // 空窗面在自愈周期内恢复（契约见件 KDoc）
        com.jiaoay.easy_dev_toolbox.widget.SurfacePaintKeepAlive()

        // 顶栏恒高行：状态条 + 会话操作三钮（停止/开始随会话态分派——Stopped 持窗态翻
        // 「开始投屏」，Streaming/Connecting 为「停止投屏」；快照/录像
        // 回执经引擎落流回本组合回执行）。vertical 走 space4 紧凑档——AppButton 32 +
        // 8 = 40 恰 [Dimens.screencastWindowBarHeight] 恒高档（space6 会顶到 44 且在
        // 最小窗 320×240 下挤压画面区权重段）。
        // 按钮排合计下限 ~540dp 超最小窗宽 320dp：窄窗普通 Row 会把右端钮（置顶/
        // 手势/画质）推出窗缘不可达——FlowRow 换行兜底（宽窗单行，键排同款形态契约）
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.screencastWindowBarHeight)
                .padding(horizontal = Dimens.space12, vertical = Dimens.space4),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4, Alignment.CenterVertically),
        ) {
            Text(
                text = statusLine(statusValue, fpsValue, parkedCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val recordingActive = recordingStateValue is ScreencastRecordingState.Pending ||
                recordingStateValue is ScreencastRecordingState.Active
            val stoppedHeld = statusValue is ScreencastStatus.Stopped &&
                !(statusValue as ScreencastStatus.Stopped).failed
            if (stoppedHeld) {
                // Stopped 持窗态：主钮翻「开始投屏」（按当前档位快照重开——与弹层
                // 「按新档开始投屏」同源同动作）；busy 吞重试在途的重入（引擎侧另拒）
                AppButton(
                    label = "开始投屏",
                    busy = restartInFlight,
                    onClick = {
                        startWithCurrentTiers()
                        refocusFrame()
                    },
                    kind = AppButtonKind.Primary,
                    leadingIcon = MaterialSymbols.PLAY_ARROW,
                    // 顶栏贴窗顶/右缘：busy 气泡 Below 带钳制防越缘
                    tooltipPositioning = TooltipAnchorPosition.Below,
                )
            } else {
                AppButton(
                    label = "停止投屏",
                    busy = false,
                    onClick = {
                        windowOps.stop()
                        refocusFrame()
                    },
                    kind = AppButtonKind.Destructive,
                    leadingIcon = MaterialSymbols.STOP_CIRCLE,
                )
            }
            AppButton(
                label = "保存快照",
                busy = snapshotInFlight,
                onClick = {
                    snapshotInFlight = true
                    windowOps.snapshot { snapshotInFlight = false }
                    refocusFrame()
                },
                kind = AppButtonKind.Secondary,
                leadingIcon = MaterialSymbols.SCREENSHOT,
            )
            // 录像起停三槽映射同主窗面板（label/kind/leadingIcon）；停止录像图标随字
            // content 色（AppButton leadingIcon 无 tint 通道——停止语义由文案承载），
            // 流程级停止字形 = STOP_CIRCLE（ui-style-guide.md 6.1a）
            AppButton(
                label = if (recordingActive) "停止录像" else "开始录像",
                busy = false,
                onClick = {
                    windowOps.toggleRecording(recordingActive) { reason ->
                        transientNotice = "无法开始录像：$reason"
                    }
                    refocusFrame()
                },
                kind = AppButtonKind.Secondary,
                leadingIcon = if (recordingActive) MaterialSymbols.STOP_CIRCLE
                else MaterialSymbols.FIBER_MANUAL_RECORD,
            )
            // 显示触点开关（会话期临时打开设备系统「显示点按操作」——复现录像自证操作
            // 时序，与日志时间轴对齐）：图标钮形态（手势图标钮同款紧凑档——顶栏宽度压力
            // 下不新增文本钮占位），点击切换开/关，开态图标随 primary 翻面（手势图标钮
            // 同款开态辨识配方）；hover 契约注记常驻（注入输入不显示触点是 scrcpy 系统
            // 语义，防误判开关失效）
            HoverTooltipBox(
                tooltip = {
                    Text(
                        text = SHOW_TOUCHES_HOVER_NOTE,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                // 顶栏贴窗顶缘：Below 带水平钳制 + 垂直翻转，防长注记越出窗缘
                positioning = TooltipAnchorPosition.Below,
            ) { interactionSource ->
                IconButton(
                    onClick = {
                        windowOps.toggleShowTouches(showTouchesActive) { reason ->
                            transientNotice = "无法切换显示触点：$reason"
                        }
                        refocusFrame()
                    },
                    modifier = Modifier
                        .size(Dimens.minimumInteractiveSize)
                        .hoverable(interactionSource),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.TOUCH_APP,
                        contentDescription = if (showTouchesActive) "关闭触点显示" else "开启触点显示",
                        size = Dimens.iconLarge,
                        tint = if (showTouchesActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            // 画质档位弹层入口（窗是投屏唯一使用场景，档位控制随会话操作面在窗内）：
            // 入口恒可点（置灰语义在弹层内收口，见 QualityTierMenu KDoc），不开弹层
            // 不抢焦点（DropdownMenu 是焦点面，关弹层自然回落）
            QualityTierMenu(
                running = qualityTiersLocked,
                bitrateTierBits = bitrateTierBitsValue,
                fpsTier = fpsTierValue,
                maxSizeTier = maxSizeTierValue,
                onBitrateTierChange = onBitrateTierChange,
                onFpsTierChange = onFpsTierChange,
                onMaxSizeTierChange = onMaxSizeTierChange,
                onHoldForRestart = {
                    // 一键停投：Stopped 恒持窗（停止与关闭语义分离），无需编排标志——
                    // 停投落定后 chips 随 running 判据自动解锁、弹层翻待选态
                    windowOps.stop()
                    refocusFrame()
                },
                onStartWithTiers = { startWithCurrentTiers() },
            )
            // 手势映射入口（图标钮）：弹/收帧区图例卡——多指手势的可发现性入口；图标钮
            // 命中区统一 minimumInteractiveSize 档，开态图标随 primary 翻面
            IconButton(
                onClick = {
                    gestureHelpVisible = !gestureHelpVisible
                    refocusFrame()
                },
                modifier = Modifier.size(Dimens.minimumInteractiveSize),
            ) {
                AppSymbolIcon(
                    icon = MaterialSymbols.GESTURE,
                    contentDescription = if (gestureHelpVisible) "收起手势映射" else "展开手势映射",
                    size = Dimens.iconLarge,
                    tint = if (gestureHelpVisible) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // 置顶开关（图标钮）：窗系统置顶档的会话级切换——默认普通窗（不恒占前台，
            // 其他应用窗口可覆盖本窗）；置顶档供边操作其他工具边观察画面的形态（开态
            // 图标随 primary 翻面，手势图标钮同款配方；hover 常驻两态语义注记，防误判）
            HoverTooltipBox(
                tooltip = {
                    Text(
                        text = if (pinned) {
                            "置顶中：恒浮于其他窗口之上（点按切回普通窗）"
                        } else {
                            "普通窗：可被其他窗口覆盖（点按切置顶）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                // 顶栏末位贴窗右缘：Below 水平钳制防两态长文案右半越出窗缘
                positioning = TooltipAnchorPosition.Below,
            ) { interactionSource ->
                IconButton(
                    onClick = {
                        onPinnedChange(!pinned)
                        refocusFrame()
                    },
                    modifier = Modifier
                        .size(Dimens.minimumInteractiveSize)
                        .hoverable(interactionSource),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.PUSH_PIN,
                        contentDescription = if (pinned) "取消置顶" else "窗口置顶",
                        size = Dimens.iconLarge,
                        tint = if (pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        // 回执区成段（六段回执行的单一容器）：与顶栏/键排/文本条同格的垂直节奏
        // （h12 外环 + v4 空气 + 段距 space4），行级 horizontal padding 由段容器统一
        // 承载；空态整体不组合——静息窗零垂直占位
        val hasReceiptRows = recordingStateValue !is ScreencastRecordingState.Idle ||
            failureLingerMessage != null ||
            snapshotResultValue != null ||
            recordingResultValue != null ||
            dropPushLines.isNotEmpty() ||
            transientNotice != null
        if (hasReceiptRows) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.space12, vertical = Dimens.space4),
                verticalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                // 录像进行中提示行（Pending = 等起点关键帧——静止画面不发帧，如实给预期）
                when (val recording = recordingStateValue) {
                    is ScreencastRecordingState.Pending -> Text(
                        text = "正在开始录像…（等待画面变化或关键帧，静止画面不发帧）",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    is ScreencastRecordingState.Active -> Text(
                        text = "录像中：${recording.file.name}",
                        style = DesktopTypography.mono,
                        color = LocalEasyToolboxColors.current.consoleSuccess,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    ScreencastRecordingState.Idle -> Unit
                }

                // 失败一瞥驻留行（仅引擎失败终态——正常停止即时关窗无此行）：失败原因
                // 窗内呈现，短驻留后自动关；「立即关闭」不等驻留（主窗失败细行同因
                // 同文案兜底）
                failureLingerMessage?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "投屏失败：$message（即将自动关闭）",
                            style = DesktopTypography.mono,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onAutoClose) {
                            Text("立即关闭")
                        }
                    }
                }

                // 快照回执行（引擎落流持态——停投终态保留）：Saved = 单行承载（路径
                // weight(1f) + 打开所在文件夹 + 送飞书；超宽路径 Ellipsis 截断——最小
                // 窗下路径可读性让位动作钮可见性，截断观感归真机走查）；Failed = 只读
                // 错误行零动作
                when (val receipt = snapshotResultValue) {
                    is ScreencastSnapshotOutcome.Saved -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "快照已保存：${receipt.file.absolutePath}",
                            style = DesktopTypography.mono,
                            color = LocalEasyToolboxColors.current.consoleSuccess,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                DesktopUtils.openInFolder(receipt.file.absolutePath)
                                refocusFrame()
                            },
                        ) {
                            Text("打开所在文件夹")
                        }
                        // 置灰判据 = screencastLarkSendDisabledReason（真源 =
                        // ReportDelivery.ready 单源，主窗面板同源）；禁用态 hover 明示原因
                        val larkSendDisabledReason = screencastLarkSendDisabledReason(larkDeliveryReady)
                        if (larkSendDisabledReason == null) {
                            TextButton(
                                onClick = {
                                    windowOps.sendSnapshotToLark { message -> transientNotice = message }
                                    refocusFrame()
                                },
                            ) {
                                Text("送飞书")
                            }
                        } else {
                            HoverTooltipBox(
                                tooltip = {
                                    Text(
                                        text = larkSendDisabledReason,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                // 回执行贴窗底缘：Above 翻转到锚点上方 + 水平钳制
                                positioning = TooltipAnchorPosition.Above,
                            ) { interactionSource ->
                                TextButton(
                                    onClick = {
                                        windowOps.sendSnapshotToLark { message -> transientNotice = message }
                                        refocusFrame()
                                    },
                                    enabled = false,
                                    modifier = Modifier.hoverable(interactionSource),
                                ) {
                                    Text("送飞书")
                                }
                            }
                        }
                    }

                    is ScreencastSnapshotOutcome.Failed -> Text(
                        text = "画面快照未保存：${receipt.reason}",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    null -> Unit
                }

                // 录像回执行（引擎落流持态；与快照回执行同构——窗内不新增录像送飞书，
                // 与主窗面板动作面同口径）：Saved = 单行承载（路径 weight(1f) + 打开
                // 所在文件夹）；Failed = 只读错误行零动作
                when (val receipt = recordingResultValue) {
                    is ScreencastRecordingOutcome.Saved -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = listOfNotNull(
                                "录像已保存：${receipt.file.absolutePath}",
                                receipt.note,
                            ).joinToString("——"),
                            style = DesktopTypography.mono,
                            color = LocalEasyToolboxColors.current.consoleSuccess,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                DesktopUtils.openInFolder(receipt.file.absolutePath)
                                refocusFrame()
                            },
                        ) {
                            Text("打开所在文件夹")
                        }
                    }

                    is ScreencastRecordingOutcome.Failed -> Text(
                        text = "录像未保存：${receipt.reason}",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    null -> Unit
                }

                // 拖拽推送/安装回执行（逐文件持态行——[ScreencastDropPushLine] 五形态与
                // 快照/录像回执行同构：在途/完成/拒收，拒收人话原文不翻译；不折叠
                // 不裁剪——逐文件回执均在场是数据面纪律）
                dropPushLines.forEach { line ->
                    when (line) {
                        is ScreencastDropPushLine.Pushing -> Text(
                            text = "推送中：${line.fileName} → $SCREENCAST_DROP_PUSH_DEVICE_DIR",
                            style = DesktopTypography.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        is ScreencastDropPushLine.Pushed -> Text(
                            text = "已推送：${line.fileName} → ${line.devicePath}（${line.sizeBytes} B）",
                            style = DesktopTypography.mono,
                            color = LocalEasyToolboxColors.current.consoleSuccess,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        is ScreencastDropPushLine.Installing -> Text(
                            text = "安装中：${line.fileName}",
                            style = DesktopTypography.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        is ScreencastDropPushLine.Installed -> Text(
                            text = installedReceiptText(line),
                            style = DesktopTypography.mono,
                            color = LocalEasyToolboxColors.current.consoleSuccess,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        is ScreencastDropPushLine.Rejected -> Text(
                            text = "未处理：${line.fileName}——${line.message}",
                            style = DesktopTypography.mono,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 行内瞬态文本（起录立即失败/送飞书结果——onImmediateFailure/onResult
                // 回调写入、下次动作覆盖；引擎起录立即失败不落 recordingResult，本行是
                // 窗内唯一呈现通道，静默即违显式回执纪律）
                transientNotice?.let { notice ->
                    Text(
                        text = notice,
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 覆盖确认对话（设备目标已存在的用户裁决面）：确认 = 覆盖推送（危险主钮——
        // 覆盖设备侧文件不可逆），取消 = 零动作跳过该文件（对话数据/裁决语义见
        // [ScreencastDropPushRouter]）
        dropPushConfirm?.let { confirm ->
            AppConfirmDialog(
                title = "覆盖设备侧既有文件？",
                text = "设备侧已存在 ${confirm.devicePath}。确认后将覆盖推送；取消则不改动设备侧文件、跳过该文件。",
                isDangerous = true,
                confirmLabel = "覆盖推送",
                onConfirm = dropPush::confirmOverwrite,
                onDismiss = dropPush::dismissConfirm,
            )
        }

        // 安装确认对话（.apk 安装的用户裁决面，与覆盖确认两道独立分层）：确认前
        // 零 adb install spawn——安装不可逆（基线机被装未受控版本即被测环境被毁），
        // 文案写明本地文件名 + 大小；取消 = 零动作跳过该 APK（数据/裁决语义见
        // [ScreencastDropPushRouter]）
        apkInstallConfirm?.let { confirm ->
            AppConfirmDialog(
                title = "安装 APK 到设备？",
                text = "将安装 ${confirm.fileName}（${confirm.sizeBytes} B）到设备——安装后设备环境不可自动回退，请确认文件来源可信。取消则不改动设备、跳过该文件。",
                isDangerous = true,
                confirmLabel = "安装",
                onConfirm = dropPush::confirmInstall,
                onDismiss = dropPush::dismissInstall,
            )
        }

        val bitmap = currentFrame?.image
        if (bitmap != null) {
            // 帧区保底（screencastWindowFrameMinHeight）：回执行区（快照/录像回执 +
            // 瞬态文本）恒高无上限，密集堆叠可把 weight 段挤到 0 高；保底后总高超窗
            // 由底部键排/文本条被裁承担（根 Column 无滚动，溢出段让位的预期取舍）
            ScreencastFrameView(
                frame = bitmap,
                heightCap = null,
                streaming = statusValue as? ScreencastStatus.Streaming,
                onEvent = onFrameTouch,
                onScroll = onFrameScroll,
                gestureHelpVisible = gestureHelpVisible,
                sessionGeneration = currentFrame?.generation ?: 0L,
                onParkedCountChange = { parkedCount = it },
                // 清场补发按清场时刻的存活世代裁决（世代守卫）：关窗（本组合 dispose）时
                // 会话仍存活则逐根补发 UP；停投自关先落终态、帧流已清（null 世代）零补发
                onParkedRelease = { fingers ->
                    emitParkedFingerUps(
                        fingers = fingers,
                        currentGeneration = frame.value?.generation,
                        onEvent = onFrameTouch,
                    )
                },
                // 泊满拒绝等提示落窗内瞬态文本行（窗无 toast 通道）
                onTransientNotice = { transientNotice = it },
                // 焦点靶（回焦画面的落点；focusable 点击即取焦——点画面 = 本地聚焦 +
                // 设备触摸注入并行，与手势链无冲突）
                modifier = Modifier
                    .weight(1f)
                    .screencastFrameRegionFloor(Dimens.screencastWindowFrameMinHeight)
                    .focusRequester(frameFocus)
                    .focusable(),
            )
        } else {
            // 连接段/出画前占位（受理即开窗的窗内反馈面）：连接中…（预算注记次行）/
            // 已连接·等待画面；连接超时态承载重试/取消双动作（取消 = 停投收窗，与
            // 窗 X 同语义）。Stopped 终态分支：引擎终态清帧 → 占位组合出停止态（失败
            // 驻留期与停投重开间隙不谎报「已连接」——重开编排期的 Stopped 是活状态）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val timedOut = connectionTimedOut && statusValue is ScreencastStatus.Connecting
                val stopped = statusValue as? ScreencastStatus.Stopped
                val stoppedFailed = stopped != null && stopped.failed
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    // 两级分层：主行承载「窗内此刻发生什么」的第一眼回答，预算注记
                    // 次行降档；主次组内距 space4，与超时双钮行保持 space8 分组距
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                    ) {
                        Text(
                            text = when {
                                timedOut -> "连接超时——画面未在等待预算内就绪"
                                statusValue is ScreencastStatus.Connecting -> "连接中…"
                                stoppedFailed -> "投屏已停止（失败）——原因见上方回执行"
                                stopped != null -> "已停止——「开始投屏」重开，关窗即退出"
                                else -> "已连接 · 等待画面…"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (timedOut || stoppedFailed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (statusValue is ScreencastStatus.Connecting && !timedOut) {
                            Text(
                                text = "最长等待约 ${SCREENCAST_CONNECT_BUDGET_MS / 1_000} 秒",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (timedOut) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                            AppButton(
                                label = "重试",
                                busy = false,
                                onClick = { restartSession(null) },
                            )
                            AppButton(
                                label = "取消",
                                busy = false,
                                onClick = {
                                    windowOps.stop()
                                    refocusFrame()
                                },
                                kind = AppButtonKind.Destructive,
                                leadingIcon = MaterialSymbols.STOP_CIRCLE,
                            )
                        }
                    }
                }
            }
        }
        // 设备键排（COMMON_KEYS 13 键含 BACK——鼠标主通道一键返回）：置于画面区与
        // 文本注入条之间，输入热区（文本条）位置恒在最底。形态按窗内容高度分档：
        // 高度饥饿（maxHeight < 键排 FlowRow 下限，含最小窗 320×240）切单行横向滚动
        // ——FlowRow 三行会在最小窗吃光画面区权重段（帧区 0 高 = 图像不可见回潮）；
        // 高度充裕保持 FlowRow 换行。padding/间距走 space4 紧凑档
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.space12, vertical = Dimens.space4),
        ) {
            if (maxHeight < Dimens.screencastWindowKeyGridFlowMinHeight) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                ) {
                    DeviceKeys.COMMON_KEYS.forEach { key ->
                        deviceKeyButton(key, Modifier.width(IntrinsicSize.Min))
                    }
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                ) {
                    DeviceKeys.COMMON_KEYS.forEach { key -> deviceKeyButton(key) }
                }
            }
        }
        // 文本注入条（Enter 直发，窗口级预览消费；草稿直绑分区态——发送后清空即时可见）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.space12, vertical = Dimens.space4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            OutlinedTextField(
                value = keyDirect.textBarText,
                onValueChange = { keyDirect.textBarText = it },
                label = { Text(text = "输入文本（Enter 直发设备）", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { keyDirect.textBarHasFocus = it.hasFocus },
            )
            TextButton(
                onClick = actions::sendTextBar,
                enabled = keyDirect.textBarText.isNotEmpty(),
            ) {
                Text("发送")
            }
        }
    }
}

/**
 * 画质档位弹层（顶栏「画质」钮驱动，三组单选档：码率 / 帧率上限 / 画面上限）：
 * 起点生效——server 无中途改档通道，重开会话才生效；running 态档位置灰 + 引导注记
 * （停投即解锁——窗恒持住，停投不带走设置面）。档位清单单一真源见
 * [ScreencastQualityTierStore]；档位会话级不落盘，重启回默认档。
 *
 * 入口钮恒可点（点击消费隔离）：disabled 按钮不消费指针事件，点击会透传到可点的
 * 帧区（触摸注入——误触打进设备）；置灰语义整体移入弹层（running 态档位 chips
 * 禁选 + 注记引导）；弹层是独立 popup 窗，层内禁用控件的点击不回落帧区。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun QualityTierMenu(
    running: Boolean,
    bitrateTierBits: Long?,
    fpsTier: Float,
    maxSizeTier: Int,
    onBitrateTierChange: (Long?) -> Unit,
    onFpsTierChange: (Float) -> Unit,
    onMaxSizeTierChange: (Int) -> Unit,
    onHoldForRestart: (() -> Unit)? = null,
    onStartWithTiers: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            AppSymbolIcon(
                icon = MaterialSymbols.TUNE,
                contentDescription = null,
                size = Dimens.iconSmall,
            )
            Text("画质")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // 弹层几何契约（设计师规格）：DropdownMenu 容器自带 8dp 垂直内边距、水平为零
            //（水平内边距由标准 DropdownMenuItem 自带——本弹层是自定义 Column，须自挂
            // 横向 padding）；组内（标题→chips）space6 紧、组间 space16 松（分组层级）、
            // footer（注记+动作）经 Divider 斩开血缘——注记与组标题同字排会误读成第四组
            Column(modifier = Modifier.padding(horizontal = Dimens.space16)) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space16)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space6)) {
                        Text(
                            text = "码率",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            ScreencastQualityTierStore.VIDEO_BITRATE_TIERS_BITS.forEach { tier ->
                                FilterChip(
                                    selected = bitrateTierBits == tier,
                                    onClick = { onBitrateTierChange(tier) },
                                    enabled = !running,
                                    // 单选档位唯一形态（与 logcat 级别 chips 同配方）：capsule +
                                    // primary×hotWash 选中色——M3 默认圆角/高亮不混用
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary
                                            .copy(alpha = AppAlpha.hotWash),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                    ),
                                    shape = AppShapes.capsule,
                                    label = {
                                        Text(
                                            text = bitrateTierLabel(tier),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space6)) {
                        Text(
                            text = "帧率上限",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            ScreencastQualityTierStore.MAX_FPS_TIERS.forEach { tier ->
                                FilterChip(
                                    selected = fpsTier == tier,
                                    onClick = { onFpsTierChange(tier) },
                                    enabled = !running,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary
                                            .copy(alpha = AppAlpha.hotWash),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                    ),
                                    shape = AppShapes.capsule,
                                    label = {
                                        Text(
                                            text = "${tier.toInt()} fps",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space6)) {
                        Text(
                            text = "画面上限",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            ScreencastQualityTierStore.MAX_SIZE_TIERS.forEach { tier ->
                                FilterChip(
                                    selected = maxSizeTier == tier,
                                    onClick = { onMaxSizeTierChange(tier) },
                                    enabled = !running,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary
                                            .copy(alpha = AppAlpha.hotWash),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                    ),
                                    shape = AppShapes.capsule,
                                    label = {
                                        Text(
                                            text = if (tier == 0) "原始" else "${tier}p",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                if (running) {
                    // running 态一键通道：停投后窗恒持住、chips 解锁——改档的最短路径
                    //（与主停止钮殊途同归，不关窗）
                    Column(Modifier.padding(top = Dimens.space12)) {
                        HorizontalDivider(color = LocalToolboxSurfaceColors.current.borderSubtle)
                        Column(
                            modifier = Modifier.padding(top = Dimens.space12),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            Text(
                                text = "投屏中不可改档——停投后解锁（窗口保持），重开时生效",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                            )
                            if (onHoldForRestart != null) {
                                ChipTextButton(
                                    label = "停投并按新档重开",
                                    onClick = onHoldForRestart,
                                )
                            }
                        }
                    }
                } else if (onStartWithTiers != null) {
                    // 已停投（窗恒持住）：chips 已解锁，本次选择即时生效于重开——与
                    // running 态的引导注记是两段不同语义，文案分野不混
                    Column(Modifier.padding(top = Dimens.space12)) {
                        HorizontalDivider(color = LocalToolboxSurfaceColors.current.borderSubtle)
                        Column(
                            modifier = Modifier.padding(top = Dimens.space12),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            Text(
                                text = "已停投——档位已解锁，选好后即可开始（本次重开即时生效）",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                            )
                            ChipTextButton(
                                label = "按新档开始投屏",
                                onClick = onStartWithTiers,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 码率档标签：null 档 = 不追加 key=value，server 端默认码率即 8M（见 [ScreencastQualityTierStore.VIDEO_BITRATE_TIERS_BITS]） */
private fun bitrateTierLabel(bits: Long?): String = bits?.let { "${it / 1_000_000}M" } ?: "8M·默认"

/**
 * 帧区权重段高度保底：入射约束抬 min 后测量、按「实测高与 min 取大」上报。weight(fill)
 * 高度饥饿时入射为固定 0..0——heightIn/defaultMinSize 的入射收敛在该形态下均为无
 * 操作，只有绕开入射收敛的上报能把下限真正写进 Column 排版（溢出代价 = 本 Column
 * 底部键排/文本条被裁）；常规路径（入射 ≥ min）测量与上报均与无此 modifier 等值。
 */
private fun Modifier.screencastFrameRegionFloor(minHeight: Dp): Modifier =
    this.layout { measurable, constraints ->
        val minPx = minHeight.roundToPx()
        val placeable = measurable.measure(
            constraints.copy(
                minHeight = constraints.minHeight.coerceAtLeast(minPx),
                maxHeight = constraints.maxHeight.coerceAtLeast(minPx),
            ),
        )
        layout(placeable.width, placeable.height.coerceAtLeast(minPx)) {
            placeable.placeRelative(0, 0)
        }
    }

/**
 * 开窗初始几何（纯 px float 数学，零 dp 字面量）：内容高 = 工作区高 ×
 * [DETACHED_INITIAL_HEIGHT_FRACTION]，宽 = 高 × 画面纵横比（竖屏设备自然得窄高窗），
 * px→dp 经调用方密度进 WindowState。纵横比仅 Streaming 携带（Connecting 零载荷）；
 * 非 Streaming 回退 16:9（开窗门禁 Streaming-only 的防御兜底，正常路径不可达）。
 * px 双元组形态供 Host 计算默认 position（右缘偏移需同源 px），dp 形态见
 * [computeDetachedInitialSize]。
 */
internal fun computeDetachedInitialSizePx(
    streaming: ScreencastStatus.Streaming?,
    workareaHeightPx: Int,
): Pair<Int, Int> {
    val ratio = if (streaming != null && streaming.width > 0 && streaming.height > 0) {
        streaming.width.toFloat() / streaming.height
    } else {
        16f / 9f
    }
    val heightPx = (workareaHeightPx * DETACHED_INITIAL_HEIGHT_FRACTION).roundToInt()
    val widthPx = (heightPx * ratio).roundToInt()
    return widthPx to heightPx
}

/** [computeDetachedInitialSizePx] 的 dp 形态（px→dp 经调用方密度） */
internal fun computeDetachedInitialSize(
    streaming: ScreencastStatus.Streaming?,
    workareaHeightPx: Int,
    density: Density,
): DpSize {
    val (widthPx, heightPx) = computeDetachedInitialSizePx(streaming, workareaHeightPx)
    return with(density) { DpSize(widthPx.toDp(), heightPx.toDp()) }
}

/**
 * 内容高占工作区比例（纯 float 非 dp——RAW_DP 守护不触）：0.55 是与主窗并存的
 * 紧凑档（右缘垂直居中开窗后主窗工作区另一半可用），用户可自由缩放，几何记忆
 * 照常持久化。
 */
private const val DETACHED_INITIAL_HEIGHT_FRACTION = 0.55f

/**
 * 几何记忆尺寸健全闸：width/height 超工作区 [DETACHED_SAVED_GEOMETRY_WORKAREA_RATIO]
 * （陈旧分辨率/误存的铺满几何会把窗开成巨窗）或低于最小窗 token（退化记忆碎窗）
 * → 判无效，开窗回退计算位。position 的屏外防呆由宿主读链承担，尺寸闸在此收口。
 */
internal fun isSoundDetachedSavedGeometry(
    geometry: ScreencastDetachedInitialGeometry,
    workareaWidthPx: Int,
    workareaHeightPx: Int,
    minWidthPx: Int,
    minHeightPx: Int,
): Boolean {
    if (workareaWidthPx <= 0 || workareaHeightPx <= 0) return false
    if (geometry.width <= 0 || geometry.height <= 0) return false
    val oversized = geometry.width > workareaWidthPx * DETACHED_SAVED_GEOMETRY_WORKAREA_RATIO ||
        geometry.height > workareaHeightPx * DETACHED_SAVED_GEOMETRY_WORKAREA_RATIO
    val undersized = geometry.width < minWidthPx || geometry.height < minHeightPx
    return !oversized && !undersized
}

/** 记忆尺寸对工作区的上限比例（超过视为陈旧/铺满几何——非 dp 值，纯 float） */
private const val DETACHED_SAVED_GEOMETRY_WORKAREA_RATIO = 0.85f

/**
 * 独立窗几何写回（值 = AWT 物理像素，落盘语义由宿主写回实现承载）：最大化/图标化
 * 期间的 bounds 是铺满/最小化几何，不写；width/height ≤ 0 视为无效，不写。写回配方
 * = 关窗离散动作直接写 + 组件 onDispose 兜底双覆盖，不复制主窗拖拽防抖（独立窗开关
 * 是离散动作非连续拖拽，控制器 KDoc 同口径）。
 */
private fun persistDetachedWindowGeometry(
    frame: java.awt.Frame,
    onPersistGeometry: (x: Int, y: Int, width: Int, height: Int) -> Unit,
) {
    val state = frame.extendedState
    if (state and java.awt.Frame.MAXIMIZED_BOTH != 0 || state and java.awt.Frame.ICONIFIED != 0) return
    val bounds = frame.bounds
    if (bounds.width <= 0 || bounds.height <= 0) return
    onPersistGeometry(bounds.x, bounds.y, bounds.width, bounds.height)
}

/**
 * 关窗请求收口（标题栏 X / WM 原生关闭组合键汇入，Host onCloseRequest 调用）：
 * 先写回几何（frame 尚在，bounds 为终值；null = 挂载间隙无窗体，跳过写回），再经
 * 控制器 [ScreencastDetachedWindowController.recall] 关窗——recall 是全窗关闭路径
 * 唯一汇点，窗关即停投（经组合层注册的 onSessionEnd 外呼，引擎幂等）。
 * headless 测试以 frame=null 驱动（java.awt.Frame headless 不可构造）。
 */
internal fun onDetachedWindowCloseRequested(
    frame: java.awt.Frame?,
    onPersistGeometry: (x: Int, y: Int, width: Int, height: Int) -> Unit,
) {
    frame?.let { persistDetachedWindowGeometry(it, onPersistGeometry) }
    ScreencastDetachedWindowController.recall()
}

/**
 * 键直通分区态（Host 窗口级预览 handler 与内容件共享的宿主）：[textBarHasFocus] =
 * 放行区标志（文本注入条聚焦，内容件 onFocusChanged 回写——放行区物理键全部放行
 * 本地，打字/选字/框内复制粘贴语义不被直通截获）；[textBarText] = 文本条草稿（Enter
 * 直发取材，直绑输入框值——发送清空即时可见）。读写恒在 UI 线程（输入派发与焦点
 * 回调同线程），无跨线程可见性诉求。
 */
internal class ScreencastKeyDirectState {

    /** 放行区标志：文本注入条（含其内部焦点靶）持焦时真 */
    var textBarHasFocus: Boolean = false

    /** 文本条草稿（输入框值直绑；Enter 直发后由动作面清空） */
    var textBarText: String by mutableStateOf("")
}

/**
 * 键直通动作面（窗口级预览 handler 的外呼收口）：生产实现直连引擎控制通道 +
 * 控制器关窗；测试以 fake 记录调用。注入返回 false = 控制通道不可用（不重试不炸
 * UI——注入失败不进会话失败单槽的引擎既有纪律）。
 */
internal interface ScreencastKeyDirectActions {

    /** keycode 轨（引擎内 DOWN+UP 成对一笔完成） */
    fun injectKey(keycode: Int, metaState: Int): Boolean

    /** 字符轨文本注入（设备端 IME 语义） */
    fun injectText(text: String): Boolean

    /** Ctrl+C 剪贴板桥：请求读回设备剪贴板并写本机（读回异步闭环） */
    fun clipboardCopy()

    /** Ctrl+V 剪贴板桥：本机剪贴板 → set_clipboard → 注入 PASTE 键 */
    fun clipboardPaste()

    /** 文本条 Enter 直发：注入草稿 + 清空 + 回焦画面（草稿空时仅回焦） */
    fun sendTextBar()

    /** 文本条 Esc 退出文本模式：焦点回画面靶（文本条 blur、草稿保留不发送） */
    fun exitTextBar()
}

/**
 * 窗口级按键直通分发（纯函数，主窗 HotkeyHub.dispatch 同机制同双发纪律——窗口级
 * preview 在 Compose 焦点派发前执行、不依赖焦点链；只处理 KeyDown，一次物理键派发
 * Down+Up 双事件，不过滤会双发）：
 *
 * 1. 放行区（文本条聚焦）：Esc=退出文本模式（blur+回焦画面，草稿保留不发送）；
 *    Enter/小键盘 Enter=直发文本条；其余恒放行（本地派发）；
 * 2. 转发区：按 [ScreencastKeymap] 双轨直连设备注入（Esc 经表内 BACK 条目=设备返回，
 *    与设备端 UI 的返回反射同构），动作恒消费（该键已归属设备会话，通道断开的注入
 *    失败不放行成无义的本地键——返回 false 只会落进无焦点消费面）；
 * 3. 未映射键放行（独立窗无本地消费面，放行不有害）。
 *
 * 关窗不走键盘：标题栏 X / WM 原生关闭组合键（系统装饰窗内建，零代码）+ 停投自动
 * 关窗（recall）——Esc 语义两层各自自洽（文本层→回画面层；画面层→设备返回层），无
 * 「有时关窗有时不是」的不可解释态。语义契约：Esc 两层各归其层，关闭恒走标题栏 X / WM 关闭键 / 停投自动关窗。
 */
internal fun dispatchDetachedWindowKey(
    event: KeyEvent,
    state: ScreencastKeyDirectState,
    actions: ScreencastKeyDirectActions,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    // 放行区：文本条聚焦——本地语义优先（打字/选字/框内复制粘贴），Esc/Enter 单独收口
    if (state.textBarHasFocus) {
        return when (event.key) {
            Key.Escape -> {
                actions.exitTextBar()
                true
            }
            Key.Enter, Key.NumPadEnter -> {
                actions.sendTextBar()
                true
            }
            else -> false
        }
    }
    return when (
        val action = ScreencastKeymap.resolve(
            key = event.key,
            codePoint = event.utf16CodePoint,
            ctrl = event.isCtrlPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
            meta = event.isMetaPressed,
        )
    ) {
        null -> false
        is ScreencastKeyAction.InjectKey -> {
            actions.injectKey(action.keycode, action.metaState)
            true
        }

        is ScreencastKeyAction.InjectText -> {
            actions.injectText(action.text)
            true
        }

        ScreencastKeyAction.ClipboardCopy -> {
            actions.clipboardCopy()
            true
        }

        ScreencastKeyAction.ClipboardPaste -> {
            actions.clipboardPaste()
            true
        }
    }
}

/**
 * 生产动作面（直连引擎控制通道 + 控制器）：文本条 Enter 直发 = injectText + 清空 +
 * 回焦画面（一次动作三收口）；Ctrl+C 读回 = 请求 + 等待读回新值写本机（StateFlow
 * 同值不重发——等待「与请求前不同的值」，超时回退旧值语义见
 * [ScreencastClipboardBridge.copyWriteValue]）；Ctrl+V = set_clipboard 落地后注入
 * PASTE 键（控制消息同 socket 单写序，设备端按序消费——先落剪贴板后收粘贴键）。
 *
 * 本机剪贴板读写走 AWT systemClipboard（runCatching 收口——X11 剪贴板管理器缺席/
 * 头面不可用等平台异常降级为零动作，不炸输入派发线程）。
 */
private class EngineKeyDirectActions(
    private val engine: ScreencastEngine,
    private val scope: CoroutineScope,
    private val keyDirect: ScreencastKeyDirectState,
    private val frameFocus: FocusRequester,
) : ScreencastKeyDirectActions {

    override fun injectKey(keycode: Int, metaState: Int): Boolean =
        engine.controlInjectKey(keycode, metaState)

    override fun injectText(text: String): Boolean = engine.controlInjectText(text)

    override fun clipboardCopy() {
        val before = engine.deviceClipboard.value
        if (!engine.controlRequestClipboard()) return
        scope.launch {
            val received = withTimeoutOrNull(DEVICE_CLIPBOARD_READBACK_TIMEOUT_MS) {
                engine.deviceClipboard.first { it != before }
            }
            val text = ScreencastClipboardBridge.copyWriteValue(before, received)
            if (text != null) writeLocalClipboardText(text)
        }
    }

    override fun clipboardPaste() {
        val payload = ScreencastClipboardBridge.pasteText(readLocalClipboardText()) ?: return
        if (!engine.controlSetClipboard(payload)) return
        engine.controlInjectKey(ScreencastClipboardBridge.PASTE_KEYCODE)
    }

    override fun sendTextBar() {
        val draft = keyDirect.textBarText
        if (draft.isNotEmpty()) engine.controlInjectText(draft)
        keyDirect.textBarText = ""
        refocusFrame()
    }

    override fun exitTextBar() {
        // 退出文本模式 = 焦点回画面靶（blur 收口配方同 sendTextBar；草稿保留不发送）
        refocusFrame()
    }

    /** 回焦画面节点（统一收口：FocusRequester 未挂靶等组合时序异常降级为零动作） */
    private fun refocusFrame() {
        runCatching { frameFocus.requestFocus() }
    }
}

@Composable
private fun rememberEngineKeyDirectActions(
    keyDirect: ScreencastKeyDirectState,
    frameFocus: FocusRequester,
): ScreencastKeyDirectActions {
    val scope = rememberCoroutineScope()
    return remember(scope) { EngineKeyDirectActions(ScreencastRuntime.engine, scope, keyDirect, frameFocus) }
}

/**
 * 独立窗操作面动作（顶栏/键排/回执行的外呼收口，[ScreencastKeyDirectActions] 同构
 * 形态）：生产实现直连引擎单源 + :core 产物目录/外发槽；测试以 fake 记录调用并同步
 * 驱动回调。窗内零 StatusSignals 依赖——结果人话一律经回调回本组合瞬态文本。
 */
internal interface ScreencastDetachedWindowOps {

    /** 停止当前投屏会话（引擎幂等；Stopped 终态经 status 流驱动窗自动关） */
    fun stop()

    /**
     * 停投重开/连接段真重试（引擎 [ScreencastEngine.restart]：停会话→等终态→按上次
     * 受理参数重启；[options] 非 null 覆盖原参数）。结果经 [onResult]（false = 无历史
     * 参数/等终态超时/重入被拒）回执——窗内重开编排按它决定关窗抑制的释放——静默
     * 即违显式回执纪律。
     */
    fun restart(options: ScreencastOptions?, onResult: (Boolean) -> Unit)

    /**
     * 保存画面快照（设备端实拍，adb 往返秒级——IO 协程转发；回执经引擎
     * [ScreencastEngine.snapshotResult] 落流呈现）。[onResult] 与回执同源同值，
     * 供窗内 busy 态收口（实拍挂起期按钮置忙，回调在 UI 线程投递）。
     */
    fun snapshot(onResult: (ScreencastSnapshotOutcome) -> Unit)

    /**
     * 录像起停：[active] = 真则停止落盘，假则开始受理（状态经 recordingState 流推进）。
     * 开始的立即失败（未在投屏/已在录像/目录不可用）经 [onImmediateFailure] 写窗内
     * 瞬态文本——引擎立即失败不落 recordingResult 流，窗内无 toast，静默即违显式
     * 回执纪律。
     */
    fun toggleRecording(active: Boolean, onImmediateFailure: (String) -> Unit)

    /**
     * 显示触点起停（会话期临时开关）：[active] = 真则请求关闭，假则请求开启。立即失败
     * （无会话/adb 命令失败）经 [onImmediateFailure] 写窗内瞬态文本——静默即违显式回执
     * 纪律；成功的态翻转经引擎 [ScreencastEngine.showTouchesEnabled] 落流呈现。
     */
    fun toggleShowTouches(active: Boolean, onImmediateFailure: (String) -> Unit)

    /**
     * 快照 Saved 产物送飞书（结果人话经 [onResult] 写窗内瞬态文本）：
     * [ReportDelivery.deliver] 的 null = 已送出成功——先收 reason 再判空分叉，
     * elvis 兜底会把成功吞成失败。
     */
    fun sendSnapshotToLark(onResult: (String) -> Unit)

    /** 注入单键（keycode 语义真源 = :core DeviceKeys；DOWN+UP 成对一笔完成） */
    fun injectKey(keycode: Int): Boolean
}

/**
 * 生产动作面（直连引擎 + :core 产物槽）：文件/目录面转发 IO 协程（文件命名/目录创建/
 * 外发不阻塞 UI）；结果回调统一经 UI scope 归位投递——瞬态文本是 Compose 态，IO 线程
 * 直写有 composition 应用窗并发写风险。
 */
private class EngineDetachedWindowOps(
    private val engine: ScreencastEngine,
    private val scope: CoroutineScope,
) : ScreencastDetachedWindowOps {

    override fun stop() {
        engine.stop()
    }

    override fun restart(options: ScreencastOptions?, onResult: (Boolean) -> Unit) {
        // restart 挂起（等终态带超时）——IO 调度器承载体；结果经 UI scope 归位投递
        // （类契约：Compose 态回调不 IO 线程直写）
        scope.launch(Dispatchers.IO) {
            val ok = engine.restart(options)
            scope.launch { onResult(ok) }
        }
    }

    override fun snapshot(onResult: (ScreencastSnapshotOutcome) -> Unit) {
        scope.launch(Dispatchers.IO) {
            // 快照是用户交付物（与录屏同根的可见产物树）；旧落点（机密根 screenshots/）
            // 的存量 PNG 不迁移不删除
            val outcome = engine.snapshot(FileUtils.getSnapshotsExportDir())
            scope.launch { onResult(outcome) }
        }
    }

    override fun toggleRecording(active: Boolean, onImmediateFailure: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            if (active) {
                engine.stopRecording()
            } else {
                engine.startRecording(ScreencastRecordings.dir())?.let { failed ->
                    scope.launch { onImmediateFailure(failed.reason) }
                }
            }
        }
    }

    override fun toggleShowTouches(active: Boolean, onImmediateFailure: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val reason = engine.setShowTouches(!active)
            if (reason != null) scope.launch { onImmediateFailure(reason) }
        }
    }

    override fun sendSnapshotToLark(onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val file = (engine.snapshotResult.value as? ScreencastSnapshotOutcome.Saved)?.file
            val message = when {
                // 回执行只对 Saved 态渲染送飞书钮——本分支守回执竞态（Saved 渲染期间
                // 被新回执覆盖/清空），人话指路不静默
                file == null -> "无已保存快照——先保存快照再送出"
                else -> {
                    val reason = ReportDelivery.deliver(file)
                    if (reason == null) "已送出至飞书会话：${file.name}" else reason
                }
            }
            scope.launch { onResult(message) }
        }
    }

    override fun injectKey(keycode: Int): Boolean = engine.controlInjectKey(keycode)
}

@Composable
private fun rememberEngineDetachedWindowOps(): ScreencastDetachedWindowOps {
    val scope = rememberCoroutineScope()
    return remember(scope) { EngineDetachedWindowOps(ScreencastRuntime.engine, scope) }
}

/** 读本机剪贴板文本（null = 无文本/非文本数据/平台异常） */
private fun readLocalClipboardText(): String? = runCatching {
    val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null
    contents.getTransferData(DataFlavor.stringFlavor) as? String
}.getOrNull()

/** 写本机剪贴板文本（平台异常零动作——剪贴板桥尽力而为语义） */
private fun writeLocalClipboardText(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/** Ctrl+C 读回等待窗（毫秒）：本地 socket 读回为亚秒路径，超窗按「无响应」收口 */
private const val DEVICE_CLIPBOARD_READBACK_TIMEOUT_MS = 2_000L

/** 失败一瞥驻留时长（毫秒）：引擎失败终态的窗内原因呈现窗——正常停止即时关不受影响 */
private const val FAILURE_LINGER_MS = 4_000L

/** 生产句柄：包 AWT Frame（ComposeWindow 即 Frame 子类——extendedState 可用） */
private class FrameWindowHandle(private val frame: java.awt.Frame) : ScreencastDetachedWindowHandle {
    override val isShowing: Boolean get() = frame.isShowing

    override fun focusAndRaise() {
        if (frame.extendedState and java.awt.Frame.ICONIFIED != 0) {
            frame.extendedState = frame.extendedState and java.awt.Frame.ICONIFIED.inv()
        }
        frame.toFront()
        frame.requestFocus()
    }
}
