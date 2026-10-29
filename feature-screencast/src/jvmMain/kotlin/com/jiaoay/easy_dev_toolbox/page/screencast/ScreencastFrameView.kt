package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 投屏画面渲染共享件（帧 Image 渲染 + 坐标换算 + 触摸/滚轮回传手势链 + 手势反馈叠层的
 * 单源宿主）：深底圆角面承载最新帧（[ContentScale.Fit] + 精确贴合测量，节点纵横比恒与
 * 帧位图一致——触摸坐标换算不被黑边带偏），Streaming 态挂触摸/滚动/修饰键徽标/泊指
 * 四通道 pointerInput（主键手势 → 触摸注入（[screencastTouchPointerInput]，pointerId
 * 随通道透传）、滚轮 → 滚动注入、Ctrl+滚轮 → 离散档捏合注入、右键 → 泊指手势链
 * （[screencastParkPointerInput]）、任意指针事件 → 修饰键徽标态刷新，坐标经
 * [mapPointerToScreen] 换算为设备屏幕像素坐标）。
 *
 * 宿主契约：[heightCap] 必须显式给定——内嵌面板传 [Dimens.screencastFrameHeightMax]
 * 档（观测页视口高度守卫），独立视口窗传 null（不封顶，高度只受窗约束，即满幅）；
 * 任何调用方不得用 `fillMaxWidth + heightIn(max) + aspectRatio` 链替代贴合测量
 * （施工禁令依据见 [screencastFrameFitSize] KDoc）。[streaming] 为 null 时仅渲染
 * 不挂手势（非 Streaming 态画面不可交互）；非 null 时以其为 pointerInput key——
 * 新会话（新 Streaming 实例）重启手势链与反馈件状态。
 *
 * 手势反馈叠层（把不可见注入量化成可见反馈）：与 Image 同贴合测量的兄弟层（同尺寸
 * 同位对齐、无 pointerInput 不拦手势链）——激活手势的虚拟指环 + 双指连线 + mono 实时
 * 读数、泊指的常驻实心圆 + 编号角标 + 左下「泊 N/3」计数胶囊、修饰键按住的左下模式
 * 徽标（随键起灭）；[gestureHelpVisible] = 顶栏「手势」钮驱动的映射图例开关（内嵌
 * 面板不接线恒 false）。
 *
 * 泊指通道（[screencastParkPointerInput]）与清场：泊指状态随（[streaming] +
 * [sessionGeneration]）重建——世代并入键，同参数重连也因世代变化重建，旧世代泊指
 * 不跨会话存活；状态宿主离开组合（关窗/收起/帧区退场）或随世代重建时经
 * [onParkedRelease] 把在泊清单交宿主清场（是否补发 UP 由宿主按清场时刻的存活世代
 * 裁决——世代守卫契约见 [emitParkedFingerUps]）。[onParkedCountChange] 把泊指数
 * 变化上抛宿主（状态行「已泊 N 指」计数消费面）；[onTransientNotice] 把泊满拒绝等
 * 人话提示上抛宿主（独立窗瞬态文本行 / 主窗 toast——不静默）。
 *
 * @param gestureHelpVisible 帧区右上映射图例卡的显隐（独立窗顶栏「手势」钮驱动）
 * @param sessionGeneration 会话世代号（宿主传帧载荷 [ScreencastFrame.generation]——
 *   泊指状态键与清场世代守卫的令牌；0 = 无世代信息）
 * @param onParkedCountChange 泊指数变化通知（null = 宿主不消费计数）
 * @param onParkedRelease 清场回调（携带在泊清单——宿主按存活世代裁决补发，见
 *   [emitParkedFingerUps]；null = 宿主不清场，泊指只做状态清空）
 * @param onTransientNotice 行内瞬态提示通道（泊满拒绝等；null = 提示静默丢弃）
 */
@Composable
internal fun ScreencastFrameView(
    frame: ImageBitmap,
    heightCap: Dp?,
    streaming: ScreencastStatus.Streaming?,
    onEvent: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit,
    onScroll: (x: Int, y: Int, screenWidth: Int, screenHeight: Int, hScroll: Float, vScroll: Float) -> Unit,
    modifier: Modifier = Modifier,
    gestureHelpVisible: Boolean = false,
    sessionGeneration: Long = 0L,
    onParkedCountChange: ((Int) -> Unit)? = null,
    onParkedRelease: ((List<ScreencastParkedFinger>) -> Unit)? = null,
    onTransientNotice: ((String) -> Unit)? = null,
) {
    // 反馈件状态随会话+世代重建（与泊指宿主同构的双键）：世代并入后，同设备同分辨率的
    // 重连（Streaming 数据等值、equals 判等）也会因世代号变化重建——旧会话指环/读数
    // 不跨会话残留；下方手势链 pointerInput 同步带世代键，防反馈实例换代而手势链
    // 持旧实例的分裂
    val gestureFeedback = remember(streaming, sessionGeneration) {
        if (streaming == null) null else ScreencastGestureFeedbackState()
    }
    // 泊指状态与反馈件同位、随会话+世代重建：世代并入键后，同设备同分辨率的重连（Streaming
    // 数据等值、equals 判等）也会因世代号变化重建——旧世代泊指不跨会话存活
    val parkedFingers = remember(streaming, sessionGeneration) {
        if (streaming == null) null else ScreencastParkedFingersState()
    }
    // 泊指清场收口：状态宿主离开组合（关窗/收起/停投帧区退场）或随世代重建时，在泊清单经
    // onParkedRelease 交宿主按存活世代裁决补发（世代守卫见 emitParkedFingerUps）——本面
    // 恒清空状态与计数（幂等：无在泊即零动作）
    DisposableEffect(parkedFingers) {
        onDispose {
            val fingers = parkedFingers?.takeAll().orEmpty()
            onParkedCountChange?.invoke(0)
            if (fingers.isNotEmpty()) onParkedRelease?.invoke(fingers)
        }
    }
    Surface(
        color = LocalEasyToolboxColors.current.consoleBg,
        shape = AppShapes.medium,
        modifier = modifier.onPreviewKeyEvent { event ->
            // 帧区持焦形态（独立窗）的修饰键徽标真源：键事件自带当下修饰键态，随键起灭；
            // 恒不消费——裸修饰键零旁效（键直通/剪贴板桥/文本条输入不受影响）。
            // 无焦形态（内嵌面板）收不到键事件，徽标由指针事件携带的修饰键态兜底。
            gestureFeedback?.setArmed(ctrl = event.isCtrlPressed, shift = event.isShiftPressed)
            false
        },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = remember(frame) { ScreencastPooledFramePainter(frame) },
                contentDescription = "设备画面",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .screencastFrameFitSize(
                        aspectRatio = frame.width.toFloat() / frame.height,
                        heightCap = heightCap,
                    )
                    .then(
                        if (streaming == null) {
                            Modifier
                        } else {
                            Modifier
                                .pointerInput(streaming, sessionGeneration) {
                                    screencastTouchPointerInput(
                                        screenWidth = streaming.width,
                                        screenHeight = streaming.height,
                                        onEvent = onEvent,
                                        feedback = gestureFeedback,
                                    )
                                }
                                .pointerInput(streaming, sessionGeneration) {
                                    screencastScrollPointerInput(
                                        screenWidth = streaming.width,
                                        screenHeight = streaming.height,
                                        onEvent = onEvent,
                                        onScroll = onScroll,
                                    )
                                }
                                .pointerInput(streaming, sessionGeneration) {
                                    screencastModifierBadgePointerInput(gestureFeedback)
                                }
                                .pointerInput(parkedFingers) {
                                    screencastParkPointerInput(
                                        screenWidth = streaming.width,
                                        screenHeight = streaming.height,
                                        onEvent = onEvent,
                                        parked = parkedFingers,
                                        sessionGeneration = sessionGeneration,
                                        onParkedCountChange = onParkedCountChange,
                                        onTransientNotice = onTransientNotice,
                                    )
                                }
                        },
                    ),
            )
            // 手势反馈叠层：与 Image 同贴合测量（同尺寸同位对齐），无 pointerInput 不拦手势链
            if (gestureFeedback != null) {
                Box(
                    modifier = Modifier.screencastFrameFitSize(
                        aspectRatio = frame.width.toFloat() / frame.height,
                        heightCap = heightCap,
                    ),
                ) {
                    ScreencastGestureOverlay(
                        state = gestureFeedback,
                        parked = parkedFingers,
                        helpVisible = gestureHelpVisible,
                    )
                }
            }
            // 布局子页选中节点高亮叠层（兄弟层同贴合测量，无 pointerInput 不拦手势链）：
            // 串号守卫判定在 [screencastLayoutHighlightVisible]（纯函数供单测），
            // 停投/换机/串号失配自动无框——旧会话残留结构性不可能
            val layoutHighlight by ScreencastLayoutHighlight.state.collectAsState()
            val highlightFrame = layoutHighlight
            if (highlightFrame != null && screencastLayoutHighlightVisible(streaming, highlightFrame)) {
                Box(
                    modifier = Modifier.screencastFrameFitSize(
                        aspectRatio = frame.width.toFloat() / frame.height,
                        heightCap = heightCap,
                    ),
                ) {
                    ScreencastLayoutHighlightOverlay(frame = highlightFrame)
                }
            }
        }
    }
}

/**
 * 池化帧渲染 painter：makeFromBitmap 与解码线程装帧同锁（锁 = 槽 Bitmap 包装对象，
 * 见 [ScreencastFrameBitmapPool] 并发契约）。
 *
 * 不走 [androidx.compose.ui.graphics.painter.BitmapPainter]——它在每次绘制内裸调
 * `Image.makeFromBitmap`：Compose 绘制相对 StateFlow 发布异步（首帧突发期发布领先
 * AWT 首绘数帧；窗口暴露/resize 的 AWT 重绘可在任意发布周期后重画最后帧），裸调与
 * 解码线程装帧覆写窗相交即读到半帧覆写内容（错误帧面）。锁内 makeFromBitmap 得到
 * 当帧像素快照（可变位图 → raster 拷贝），锁外绘制不持槽引用——后续装帧不影响
 * 已拷出的快照。
 */
private class ScreencastPooledFramePainter(
    private val frame: ImageBitmap,
) : Painter() {

    override val intrinsicSize: Size
        get() = Size(frame.width.toFloat(), frame.height.toFloat())

    protected override fun DrawScope.onDraw() {
        val bitmap = frame.asSkiaBitmap()
        // 快照闭包契约：makeFromBitmap 产生整帧 native 光栅拷贝（可变位图绘制的固有代价，
        // 框架 SkiaBackedCanvas.drawImage 同款路径），绘制后必须确定性 close——泄漏到
        // phantom Cleaner 即随 GC 节奏滞留释放（高分辨率档两次 GC 间隙可积压 GB 级）
        synchronized(bitmap) { org.jetbrains.skia.Image.makeFromBitmap(bitmap) }.use { image ->
            // 采样档契约：按缩放方向选档——缩小走 mipmap 双线性（双线性直缩在 >2x 缩小时
            // 丢样发闪）；放大走三次 Mitchell（CubicResampler(1/3,1/3)，比双线性锐、
            // 无 Catmull-Rom 振铃）
            val sampling = if (size.width < image.width || size.height < image.height) {
                org.jetbrains.skia.FilterMipmap(
                    org.jetbrains.skia.FilterMode.LINEAR,
                    org.jetbrains.skia.MipmapMode.LINEAR,
                )
            } else {
                org.jetbrains.skia.SamplingMode.MITCHELL
            }
            drawContext.canvas.nativeCanvas.drawImageRect(
                image = image,
                src = org.jetbrains.skia.Rect.makeLTRB(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                dst = org.jetbrains.skia.Rect.makeLTRB(0f, 0f, size.width, size.height),
                samplingMode = sampling,
                paint = null,
                strict = true,
            )
        }
    }
}

/** 修饰键双指手势模式（按下时刻经 PointerEvent.keyboardModifiers 锁定，拖动中途按放不切模式——可预测优先）。 */
internal enum class ScreencastGestureMode {

    /** Ctrl：对称双指（捏合 + 旋转统一原语） */
    PINCH,

    /** Shift：双指同向平移 */
    PAN,
}

/**
 * 修饰键 → 双指手势模式判定（单一真源：手势分支、徽标、图例三面同表）：
 * Ctrl = 对称双指、Shift = 双指平移、无修饰 = null（单指现状）；双修饰并按 Ctrl 优先
 * （与滚轮捏合档的 Ctrl 判定同键优先）。
 */
internal fun screencastGestureModeFor(ctrl: Boolean, shift: Boolean): ScreencastGestureMode? = when {
    ctrl -> ScreencastGestureMode.PINCH
    shift -> ScreencastGestureMode.PAN
    else -> null
}

/**
 * 手势反馈件状态宿主（手势链协程写、反馈叠层读；读写恒在 UI 线程——指针派发与组合同线程）：
 * [activeMode]/[fingerFirst]/[fingerSecond]/[readout] = 手势激活期的可见反馈（虚拟指环 +
 * 连线 + mono 读数，视图 px 坐标）；[armedMode] = 修饰键按住徽标（指针事件与帧区键事件
 * 双源刷新，随键起灭）。
 */
internal class ScreencastGestureFeedbackState {

    /** 激活中的手势（null = 无手势——指环/连线/读数不呈现） */
    var activeMode by mutableStateOf<ScreencastGestureMode?>(null)

    /** 虚拟指位（视图 px，第一/第二指；仅 [activeMode] 非 null 时被消费） */
    var fingerFirst by mutableStateOf(Offset.Zero)

    /** 虚拟指位（视图 px，第二指） */
    var fingerSecond by mutableStateOf(Offset.Zero)

    /** mono 实时读数（捏合倍率/旋转角或平移量；null = 不呈现） */
    var readout by mutableStateOf<String?>(null)

    /** 修饰键按住时即将进入的手势模式（null = 徽标退场） */
    var armedMode by mutableStateOf<ScreencastGestureMode?>(null)

    /**
     * 最近一次按住的修饰模式（徽标内容源）：松开修饰键后 armedMode 归 null 驱动渐隐，
     * 但渐隐期内容不塌空——徽标文案取本字段（恒为最后一次按住的模式）。
     */
    var badgeMode by mutableStateOf<ScreencastGestureMode?>(null)
        private set

    /** 修饰键态刷新（判定单一真源见 [screencastGestureModeFor]） */
    fun setArmed(ctrl: Boolean, shift: Boolean) {
        val mode = screencastGestureModeFor(ctrl, shift)
        armedMode = mode
        if (mode != null) badgeMode = mode
    }

    /** 手势激活（进入注入态——指环/连线/读数呈现） */
    fun activate(
        mode: ScreencastGestureMode,
        first: Offset,
        second: Offset,
        readoutText: String,
    ) {
        activeMode = mode
        fingerFirst = first
        fingerSecond = second
        readout = readoutText
    }

    /** 手势进行中反馈推进（每折算一笔 MOVE 同步；读数/指位同帧更新） */
    fun updateGesture(first: Offset, second: Offset, readoutText: String) {
        fingerFirst = first
        fingerSecond = second
        readout = readoutText
    }

    /** 手势收束（抬指——指环/读数退场；徽标随修饰键态独立存续） */
    fun clearGesture() {
        activeMode = null
        readout = null
    }
}

/**
 * 手势反馈叠层（帧区兄弟层）：激活手势绘虚拟指环（primary×[AppAlpha.hotWash] 洗底 +
 * primary 描边——恒主题色，昼战紫底与夜战暗底对比度同源）+ 双指连线 + 中点；泊指绘
 * 常驻实心圆（primary 实底——区别于激活手势的洗底指环）+ onPrimary 编号角标（①②③，
 * 泊入序即 [ScreencastParkedFinger.pointerId] 分配序）；右上信息栈 = mono 实时读数
 * （激活态）+ 映射图例卡（[helpVisible]，[AppMotion.fast] 渐入渐出）；左下栈 =
 * 「泊 N/3」计数胶囊（有泊指在场时，[AppShapes.capsule]）+ 模式徽标（修饰键按住的
 * 「即将进入的手势模式」提示，[AppMotion.fast] 随键起灭）。
 *
 * 泊指圆位按设备坐标对画面节点现场换算（不在泊入时定格视图坐标）——窗体缩放后圆位
 * 恒与设备端触点对齐；指位在 draw lambda 内读，泊入/清空只失效绘制相位不触发重组。
 */
@Composable
private fun ScreencastGestureOverlay(
    state: ScreencastGestureFeedbackState,
    parked: ScreencastParkedFingersState?,
    helpVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val numberStyle = MaterialTheme.typography.labelSmall
    val numberMeasurer = rememberTextMeasurer()
    Box(modifier = modifier.fillMaxSize()) {
        if (parked != null && parked.fingers.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = Dimens.screencastGestureRingRadius.toPx()
                parked.fingers.forEach { finger ->
                    if (finger.screenWidth <= 0 || finger.screenHeight <= 0) return@forEach
                    val centerX = finger.x * size.width.toFloat() / finger.screenWidth
                    val centerY = finger.y * size.height.toFloat() / finger.screenHeight
                    drawCircle(color = primary, radius = radius, center = Offset(centerX, centerY))
                    parkedFingerNumberLabel(finger.pointerId)?.let { label ->
                        val text = numberMeasurer.measure(label, numberStyle)
                        drawText(
                            textLayoutResult = text,
                            color = onPrimary,
                            topLeft = Offset(
                                centerX - text.size.width / 2f,
                                centerY - text.size.height / 2f,
                            ),
                        )
                    }
                }
            }
        }
        if (state.activeMode != null) {
            // 指位在 draw lambda 内读——MOVE 推进只失效绘制相位，不触发重组
            Canvas(modifier = Modifier.fillMaxSize()) {
                val first = state.fingerFirst
                val second = state.fingerSecond
                val ringRadius = Dimens.screencastGestureRingRadius.toPx()
                val stroke = Dimens.strokeThin.toPx()
                listOf(first, second).forEach { center ->
                    drawCircle(
                        color = primary.copy(alpha = AppAlpha.hotWash),
                        radius = ringRadius,
                        center = center,
                    )
                    drawCircle(
                        color = primary,
                        radius = ringRadius,
                        center = center,
                        style = Stroke(stroke),
                    )
                }
                drawLine(color = primary, start = first, end = second, strokeWidth = stroke)
                drawCircle(
                    color = primary,
                    radius = Dimens.dotIndicatorSize.toPx() / 2f,
                    center = Offset((first.x + second.x) / 2f, (first.y + second.y) / 2f),
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Dimens.space8),
            horizontalAlignment = Alignment.End,
        ) {
            state.readout?.let { readoutText ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = AppShapes.small,
                ) {
                    Text(
                        text = readoutText,
                        style = DesktopTypography.mono,
                        color = primary,
                        modifier = Modifier.padding(
                            horizontal = Dimens.space8,
                            vertical = Dimens.space4,
                        ),
                    )
                }
            }
            AnimatedVisibility(
                visible = helpVisible,
                enter = fadeIn(animationSpec = AppMotion.fast()),
                exit = fadeOut(animationSpec = AppMotion.fast()),
            ) {
                ScreencastGestureLegendCard()
            }
        }
        // 左下栈：泊指计数胶囊（上）+ 修饰键模式徽标（下）——两小件各自独立起灭
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Dimens.space8),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            AnimatedVisibility(
                visible = parked != null && parked.fingers.isNotEmpty(),
                enter = fadeIn(animationSpec = AppMotion.fast()),
                exit = fadeOut(animationSpec = AppMotion.fast()),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = AppShapes.capsule,
                ) {
                    Text(
                        text = parkedCountLabel(parked?.fingers?.size ?: 0),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Dimens.space8,
                            vertical = Dimens.space4,
                        ),
                    )
                }
            }
            AnimatedVisibility(
                visible = state.armedMode != null,
                enter = fadeIn(animationSpec = AppMotion.fast()),
                exit = fadeOut(animationSpec = AppMotion.fast()),
            ) {
            val armed = state.badgeMode
            if (armed != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = AppShapes.capsule,
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = Dimens.space8,
                            vertical = Dimens.space4,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                    ) {
                        Text(
                            text = armed.badgeKeyLabel(),
                            style = DesktopTypography.mono,
                            color = primary,
                        )
                        Text(
                            text = armed.badgeModeLabel(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            }
        }
    }
}

/** 徽标键名（修饰键 → 按键名） */
private fun ScreencastGestureMode.badgeKeyLabel(): String = when (this) {
    ScreencastGestureMode.PINCH -> "Ctrl"
    ScreencastGestureMode.PAN -> "Shift"
}

/**
 * 设备屏 rect → 视图 rect 换算（纯函数，internal 供单测）：[mapPointerToScreen] 的逆
 * ——`x_view = x_device × viewWidth / screenWidth`，横纵同比（贴合测量先例保证纵横比
 * 一致）。屏维/视图维任一非正返回 null 零绘制（不产病态 rect）。
 */
internal fun mapScreenRectToView(
    frame: ScreencastLayoutHighlightFrame,
    viewWidth: Int,
    viewHeight: Int,
): Rect? {
    if (frame.screenWidth <= 0 || frame.screenHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
        return null
    }
    val scaleX = viewWidth.toFloat() / frame.screenWidth
    val scaleY = viewHeight.toFloat() / frame.screenHeight
    return Rect(
        frame.left * scaleX,
        frame.top * scaleY,
        frame.right * scaleX,
        frame.bottom * scaleY,
    )
}

/**
 * 高亮叠层渲染守卫（纯函数，internal 供单测）：帧侧独立串号守卫——payload 非 null、
 * serial 非 null 且与 Streaming 会话串号一致才画。停投/换机/串号失配/来源不明
 * （serial null）自动无框；[ScreencastStatus.Streaming.serial] null 是引擎 API 层
 * 可空防御面（生产面恒非 null），该面恒不画——与「投屏点亮」注入放行的口径有意
 * 不对称（注入是用户显式动作有事回执，帧上框是持续显示态，来源不可证不持续画框）。
 */
internal fun screencastLayoutHighlightVisible(
    streaming: ScreencastStatus.Streaming?,
    frame: ScreencastLayoutHighlightFrame?,
): Boolean {
    if (frame == null || frame.serial == null) return false
    return streaming?.serial != null && streaming.serial == frame.serial
}

/**
 * 布局子页选中节点高亮叠层（帧区兄弟层）：primary × [Dimens.strokeThin] 描边 +
 * primary×[AppAlpha.selectedWash] 洗底，零标签零角标（帧是设备画面，叠加物最小化
 * ——选中语义由布局子页承担，帧上不重复）。坐标换算在 draw 内按当下画布尺寸现场
 * 折算（窗体缩放后框位恒与设备坐标对齐）。
 */
@Composable
private fun ScreencastLayoutHighlightOverlay(
    frame: ScreencastLayoutHighlightFrame,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.fillMaxSize()) {
        val rect = mapScreenRectToView(
            frame = frame,
            viewWidth = size.width.toInt(),
            viewHeight = size.height.toInt(),
        ) ?: return@Canvas
        drawRect(
            color = primary.copy(alpha = AppAlpha.selectedWash),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
        )
        drawRect(
            color = primary,
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            style = Stroke(Dimens.strokeThin.toPx()),
        )
    }
}

/** 徽标人话模式名（与 [GESTURE_LEGEND_ROWS] 文案同口径） */
private fun ScreencastGestureMode.badgeModeLabel(): String = when (this) {
    ScreencastGestureMode.PINCH -> "对称双指"
    ScreencastGestureMode.PAN -> "双指平移"
}

/** 手势映射图例行（输入 → 人话动作；与手势链实现面一一对应——泊指三行同 [screencastParkPointerInput]） */
internal val GESTURE_LEGEND_ROWS: List<Pair<String, String>> = listOf(
    "Ctrl + 左键拖" to "双指捏合 / 旋转",
    "Ctrl + 单击" to "双指同点轻点",
    "Ctrl + 滚轮" to "捏合缩放（细调）",
    "Shift + 左键拖" to "双指同向平移",
    "右键单击" to "泊一根手指（最多 3 根）",
    "右键拖动" to "取消本次泊入",
    "双击右键" to "清空全部泊指",
    "左键" to "单指（现状不变）",
)

/**
 * 首开一次性手势引导文案（独立窗 transientNotice 行承载——可被下一动作覆盖跳过，
 * 不阻塞不锁界面）。
 */
internal const val GESTURE_INTRO_NOTICE =
    "多指手势：Ctrl+左键拖 = 双指捏合/旋转，Shift+左键拖 = 双指平移，Ctrl+滚轮 = 缩放细调；点顶栏「手势」看完整映射"

/** 手势映射图例卡（人话键位/动作对照；行内容单一真源 [GESTURE_LEGEND_ROWS]） */
@Composable
private fun ScreencastGestureLegendCard(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = AppShapes.medium,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(Dimens.space12),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            Text(
                text = "手势映射",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            GESTURE_LEGEND_ROWS.forEach { (keys, action) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    Text(text = keys, style = DesktopTypography.mono)
                    Text(
                        text = action,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 画面触摸回传手势（仅主键参与；修饰键在按下时刻锁定分支）：Ctrl 按下 → 对称双指拖
 * （[twoFingerDragGesture] 的捏合/旋转原语）、Shift 按下 → 双指同向平移、无修饰 →
 * 单指现状（[singleFingerDragGesture]，[pointerId] 默认
 * [ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER] = 既有单指行为恒等，分配表见
 * [ScreencastEngine.controlInjectTouch]）。
 *
 * 主键过滤是杂散触摸的修死位：非主键按下（右/中键）整只手势零注入——右键拖动若不过滤
 * 会向设备注入一串杂散 DOWN+MOVE+UP（设备端表现为点击/拖拽误触）。非主键手势期间的
 * 事件全部忽略（手势块即时让位，[awaitEachGesture] 兜全部指针抬起后再启下手势）。
 *
 * MOVE 合并是注入链路的硬约束而非可选优化：设备端对注入触摸事件的派发消费能力有限
 * （真机实测上限 ~190 笔/秒），而宿主指针事件率跟随鼠标上报率（125-1000Hz），无合并的
 * 逐笔直发一旦超限即在设备端派发队列积压成橡皮筋滞后——拖动越久滞后越大，抬指后仍需
 * 数秒排水（真机实测 500Hz 发送时派发滞后累积至秒级，125Hz 恰好贴线）。合并语义 =
 * 每 [MOVE_COALESCE_INTERVAL_MS] 窗至多一笔、恒带当下最新位置（被合并的中间点不丢
 * 终态：下一笔 MOVE 与手势收尾 UP 恒取最新位置）；DOWN/UP 语义事件不经合并（按下/
 * 抬起不可丢）。
 */
internal suspend fun PointerInputScope.screencastTouchPointerInput(
    screenWidth: Int,
    screenHeight: Int,
    onEvent: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit,
    pointerId: Long = ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER,
    feedback: ScreencastGestureFeedbackState? = null,
) {
    awaitEachGesture {
        val down = awaitPrimaryDown() ?: return@awaitEachGesture
        when (
            screencastGestureModeFor(
                ctrl = down.modifiers.isCtrlPressed,
                shift = down.modifiers.isShiftPressed,
            )
        ) {
            ScreencastGestureMode.PINCH ->
                twoFingerDragGesture(down.change, screenWidth, screenHeight, onEvent, feedback, ScreencastGestureMode.PINCH)

            ScreencastGestureMode.PAN ->
                twoFingerDragGesture(down.change, screenWidth, screenHeight, onEvent, feedback, ScreencastGestureMode.PAN)

            null -> singleFingerDragGesture(down.change, screenWidth, screenHeight, onEvent, pointerId)
        }
    }
}

/**
 * 修饰键徽标观察链（零消费）：帧区内任意指针事件自带的 keyboardModifiers 即时刷新
 * 徽标态——按住 Ctrl/Shift 移入画面即见「即将进入的手势模式」；无焦形态（内嵌面板，
 * 收不到键事件）这是徽标的唯一真源（修饰键松开后徽标随下一次指针事件清除）。
 */
internal suspend fun PointerInputScope.screencastModifierBadgePointerInput(
    feedback: ScreencastGestureFeedbackState?,
) {
    if (feedback == null) return
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            feedback.setArmed(
                ctrl = event.keyboardModifiers.isCtrlPressed,
                shift = event.keyboardModifiers.isShiftPressed,
            )
        }
    }
}

// ── 泊指（右键通道：常驻按住的虚拟指——「一指固定一指动」的姿态源） ──────────────

/**
 * 单根泊指（发一次 DOWN 后保持按住不发的常驻虚拟指）：坐标 = 设备屏幕像素（泊入时刻
 * 经 [mapPointerToScreen] 换算）；[generation] = 泊入时会话世代（清场补发的世代守卫
 * 令牌）；设备端以按下状态持有本触点——泊指期间设备长按语义会触发（地图长按置目的地/
 * 列表长按弹菜单等），这是泊指的固有代价，使用者按场景取舍。
 */
internal data class ScreencastParkedFinger(
    val pointerId: Long,
    val x: Int,
    val y: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val generation: Long,
)

/**
 * 泊指状态宿主（泊指手势链写、反馈叠层与宿主计数读；读写恒在 UI 线程——指针派发与
 * 组合同线程）。生命周期由 [ScreencastFrameView] 按会话+世代重建，离开组合时经
 * [ScreencastFrameView] 的清场 DisposableEffect 收口（[takeAll] 单出口——并发路径
 * 下重复清场第二路必得空表，幂等）。
 */
internal class ScreencastParkedFingersState {

    /** 在泊清单（泊入序即 id 分配序 -11→-12→-13；空 = 无泊指） */
    var fingers by mutableStateOf<List<ScreencastParkedFinger>>(emptyList())
        private set

    /** 泊入（追加序恒与分配序一致——id 见 [nextParkedPointerId]） */
    fun add(finger: ScreencastParkedFinger) {
        fingers = fingers + finger
    }

    /** 取走全部在泊清单（单出口幂等清空——重复调用第二路得空表） */
    fun takeAll(): List<ScreencastParkedFinger> {
        val all = fingers
        fingers = emptyList()
        return all
    }
}

/**
 * 泊指 id 分配表（-11/-12/-13 依序；与主单指 -2、双指对 -3/-4 互不撞面，
 * 避 -1 mouse 分支——全表契约见 [ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER]）。
 */
internal val PARKED_POINTER_IDS: List<Long> = List(ScrcpyControlProtocol.PARKED_FINGERS_MAX) {
    ScrcpyControlProtocol.POINTER_ID_PARKED_FIRST - it
}

/**
 * 下一根泊指 id（纯函数）：取分配表中首个未占用号；null = 已泊满
 * [ScrcpyControlProtocol.PARKED_FINGERS_MAX] 根（调用方拒绝泊入并显式提示）。
 */
internal fun nextParkedPointerId(occupiedIds: Set<Long>): Long? =
    PARKED_POINTER_IDS.firstOrNull { it !in occupiedIds }

/** 泊指编号角标（①②③ = 泊入序；未知 id 返回 null 不绘角标） */
internal fun parkedFingerNumberLabel(pointerId: Long): String? = when (pointerId) {
    PARKED_POINTER_IDS.elementAtOrNull(0) -> "①"
    PARKED_POINTER_IDS.elementAtOrNull(1) -> "②"
    PARKED_POINTER_IDS.elementAtOrNull(2) -> "③"
    else -> null
}

/** 左下计数胶囊文案（「泊 N/3」——N 为在泊数） */
internal fun parkedCountLabel(count: Int): String =
    "泊 $count/${ScrcpyControlProtocol.PARKED_FINGERS_MAX}"

/**
 * 右键双击判定窗（毫秒）：上一记右键单击完成后 [PARK_DOUBLE_CLICK_INTERVAL_MS] 内再
 * 完成一记 = 双击（清全部泊指）。值 + 单位 + 依据：400ms = 桌面双击间隔常用档
 * （AWT awt.multiClickInterval 量级 400-500ms）取下沿——双击与「连点泊两根」的分界。
 */
internal const val PARK_DOUBLE_CLICK_INTERVAL_MS = 400L

/** 泊满拒绝的行内瞬态提示（[onTransientNotice] 通道承载；清空后再泊——不静默不泊入） */
internal const val PARK_FULL_NOTICE =
    "已泊满 ${ScrcpyControlProtocol.PARKED_FINGERS_MAX} 根手指——双击右键清空后再泊"

/**
 * 泊指手势链（右键通道——右键从杂散误触源收编为泊指键位）：右键单击（无拖动位移）=
 * 在按下位泊一根常驻虚拟指（发一次 DOWN 保持按住，id 依序 -11/-12/-13，最多
 * [ScrcpyControlProtocol.PARKED_FINGERS_MAX] 根）；右键拖动（位移过
 * [GESTURE_ACTIVATE_HYSTERESIS_PX] 迟滞）= 取消本次泊入，零注入；双击右键（上一记
 * 完成后 [PARK_DOUBLE_CLICK_INTERVAL_MS] 内再一记）= 清全部泊指（逐根补发 UP）；
 * 泊满后单击 = 行内瞬态提示不泊入（[onTransientNotice]）。
 *
 * 与左键活动指（[screencastTouchPointerInput] 的主单指 -2）正交组合：泊指常驻按住 + 左键
 * 拖动 = 设备端双指手势「一指固定一指动」；多根泊指 + 左键 = 多指姿态。
 *
 * 行为契约：中键不参与（保持零注入）；单击判定取按下位（迟滞内位移不改泊点）；双击
 * 清空把首击刚泊入的指一并补发 UP（设备端表现为一次快速点按——固有代价）；清空/泊入
 * 后计数经 [onParkedCountChange] 上抛宿主。屏维非法/视图坍缩时零泊入（不向设备注入
 * 越界指位——与双指拖手势同纪律）。
 */
internal suspend fun PointerInputScope.screencastParkPointerInput(
    screenWidth: Int,
    screenHeight: Int,
    onEvent: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit,
    parked: ScreencastParkedFingersState?,
    sessionGeneration: Long,
    onParkedCountChange: ((Int) -> Unit)?,
    onTransientNotice: ((String) -> Unit)?,
) {
    if (parked == null) return
    var lastRightClickMs = Long.MIN_VALUE
    awaitEachGesture {
        val down = awaitSecondaryDown() ?: return@awaitEachGesture
        var cancelledByDrag = false
        val downX = down.position.x
        val downY = down.position.y
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
            if (!change.pressed) {
                if (!cancelledByDrag) {
                    val nowMs = System.nanoTime() / 1_000_000
                    val doubleClick = lastRightClickMs != Long.MIN_VALUE &&
                        nowMs - lastRightClickMs < PARK_DOUBLE_CLICK_INTERVAL_MS
                    lastRightClickMs = nowMs
                    if (doubleClick) {
                        clearParkedFingers(parked, onEvent, onParkedCountChange)
                    } else {
                        parkRightClick(
                            position = down.position,
                            viewWidth = size.width,
                            viewHeight = size.height,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            sessionGeneration = sessionGeneration,
                            onEvent = onEvent,
                            parked = parked,
                            onParkedCountChange = onParkedCountChange,
                            onTransientNotice = onTransientNotice,
                        )
                    }
                }
                break
            }
            change.consume()
            if (!cancelledByDrag &&
                gestureHysteresisSatisfied(change.position.x - downX, change.position.y - downY)
            ) {
                cancelledByDrag = true
            }
        }
    }
}

/**
 * 等待右键（secondary）按下：非右键按下（左/中键）返回 null（调用方手势块即时让位
 * ——中键维持零注入，左键归 [screencastTouchPointerInput] 通道）。
 */
private suspend fun AwaitPointerEventScope.awaitSecondaryDown(): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
        if (!event.buttons.isSecondaryPressed) return null
        return down
    }
}

/**
 * 右键单击泊入：换算设备坐标 → 发一次 DOWN（保持按住不随本手势发 UP）→ 入泊清单
 * → 计数上抛。泊满时零注入零入清单，仅提示（不静默）。
 */
private fun parkRightClick(
    position: Offset,
    viewWidth: Int,
    viewHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
    sessionGeneration: Long,
    onEvent: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit,
    parked: ScreencastParkedFingersState,
    onParkedCountChange: ((Int) -> Unit)?,
    onTransientNotice: ((String) -> Unit)?,
) {
    if (screenWidth <= 0 || screenHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return
    val pointerId = nextParkedPointerId(parked.fingers.map { it.pointerId }.toSet())
    if (pointerId == null) {
        onTransientNotice?.invoke(PARK_FULL_NOTICE)
        return
    }
    val (x, y) = mapPointerToScreen(position.x, position.y, viewWidth, viewHeight, screenWidth, screenHeight)
    onEvent(ScrcpyControlProtocol.MOTION_ACTION_DOWN, x, y, screenWidth, screenHeight, pointerId)
    parked.add(
        ScreencastParkedFinger(
            pointerId = pointerId,
            x = x,
            y = y,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            generation = sessionGeneration,
        ),
    )
    onParkedCountChange?.invoke(parked.fingers.size)
}

/**
 * 清全部泊指（双击右键路径）：逐根补发 UP（设备坐标 = 泊入时刻指位）→ 清单清空 →
 * 计数上抛。本路径运行于 Streaming 态手势链内（世代守卫不适用——会话定义存活）；
 * 关窗/停投等清场路径经 [emitParkedFingerUps] 的世代守卫裁决。
 */
private fun clearParkedFingers(
    parked: ScreencastParkedFingersState,
    onEvent: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit,
    onParkedCountChange: ((Int) -> Unit)?,
) {
    parked.takeAll().forEach { finger ->
        onEvent(
            ScrcpyControlProtocol.MOTION_ACTION_UP,
            finger.x,
            finger.y,
            finger.screenWidth,
            finger.screenHeight,
            finger.pointerId,
        )
    }
    onParkedCountChange?.invoke(0)
}

/**
 * 泊指清场补发（纯发射面——帧区退场/世代重建经 [ScreencastFrameView] 清场回调用）：
 * 仅向「清场时刻仍存活的同世代会话」逐根补发 UP。
 *
 * 世代守卫（防已断会话补发乱注入）：[currentGeneration] = 清场时刻的存活会话世代
 * （null = 无存活会话）。null 或异世代的泊指只清状态不发射——停投/断连时 Stopped 在
 * 控制通道关闭之后才落定，此时补发必然被引擎丢弃，直发反而有「跨帧合并漏观察终态」
 * 时把旧世代 UP 注入新会话的乱射窗（服务端按流序合成多点 MotionEvent，陌生 id 的
 * UP 不该出现）；同世代（关窗/收起时会话仍存活）则补发必达且语义正确。
 */
internal fun emitParkedFingerUps(
    fingers: List<ScreencastParkedFinger>,
    currentGeneration: Long?,
    onEvent: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit,
) {
    if (currentGeneration == null) return
    fingers.forEach { finger ->
        if (finger.generation == currentGeneration) {
            onEvent(
                ScrcpyControlProtocol.MOTION_ACTION_UP,
                finger.x,
                finger.y,
                finger.screenWidth,
                finger.screenHeight,
                finger.pointerId,
            )
        }
    }
}

/** 主键按下采样（change + 按下时刻修饰键快照——修饰模式锁定的唯一真源）。 */
private data class PrimaryDown(
    val change: PointerInputChange,
    val modifiers: PointerKeyboardModifiers,
)

/**
 * 等待主键按下：悬停移动等非按下事件继续等；非主键按下返回 null（调用方手势块即时
 * 让位——整只手势零注入，主键过滤契约见 [screencastTouchPointerInput]）。
 */
private suspend fun AwaitPointerEventScope.awaitPrimaryDown(): PrimaryDown? {
    while (true) {
        val event = awaitPointerEvent()
        val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
        if (!event.buttons.isPrimaryPressed) return null
        return PrimaryDown(down, event.keyboardModifiers)
    }
}

/**
 * 单指拖手势（无修饰左键——现状行为恒等）：按下 → DOWN 注入；移动 → MOVE 注入（经
 * [shouldSendCoalescedMove] 合并节流）；抬指 → UP 注入（原地轻点 = DOWN 后直接 UP，
 * 无移动也成手势）。手势期间移动事件全消费——画面拖动不冒泡拖动观测页滚动。坐标经
 * [mapPointerToScreen] 换算为设备屏幕像素坐标（越界收口）。
 */
private suspend fun AwaitPointerEventScope.singleFingerDragGesture(
    down: PointerInputChange,
    screenWidth: Int,
    screenHeight: Int,
    onEvent: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit,
    pointerId: Long,
) {
    val (startX, startY) = mapPointerToScreen(
        down.position.x, down.position.y, size.width, size.height, screenWidth, screenHeight,
    )
    onEvent(ScrcpyControlProtocol.MOTION_ACTION_DOWN, startX, startY, screenWidth, screenHeight, pointerId)
    var lastMoveSentMs = Long.MIN_VALUE
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
        val (x, y) = mapPointerToScreen(
            change.position.x, change.position.y, size.width, size.height, screenWidth, screenHeight,
        )
        if (!change.pressed) {
            onEvent(ScrcpyControlProtocol.MOTION_ACTION_UP, x, y, screenWidth, screenHeight, pointerId)
            break
        }
        change.consume()
        val nowMs = System.nanoTime() / 1_000_000
        if (!shouldSendCoalescedMove(lastMoveSentMs, nowMs)) continue
        lastMoveSentMs = nowMs
        onEvent(ScrcpyControlProtocol.MOTION_ACTION_MOVE, x, y, screenWidth, screenHeight, pointerId)
    }
}

/**
 * 修饰键双指拖手势（对称捏合/同向平移共用车架；模式按下时刻锁定——拖动中途按放修饰键
 * 不切模式）：
 * - 迟滞：位移过 [GESTURE_ACTIVATE_HYSTERESIS_PX]（视图 px）才激活——迟滞内抬指 =
 *   双指同点 DOWN+UP 轻点（修饰键单击的双指点按语义），手抖不误启双指注入；
 * - 激活即注入双指 DOWN 对（[ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST/SECOND]，
 *   初始分离半径 = [pinchNotchBaseRadius] 设备 px）：PINCH 沿当下拖向对称分离（指距 =
 *   初始半径 + |位移|，绕按下点画圈即旋转分量——两指连线方向随拖向）；PAN 沿垂直于
 *   首位移方向的轴分离后两指随全量位移同向平移（轴激活时刻锁定）；
 * - 激活后 MOVE 对按指各持 [MOVE_COALESCE_INTERVAL_MS] 合并窗（[shouldSendCoalescedMove]）
 *   节流注入，UP 对恒带抬指时刻最新指位（合并窗不丢终态——2 指 16ms 窗 ≈125 笔/秒，
 *   低于设备注入上限）；
 * - 中点/锚点恒不漂移（两模式的几何都以按下点为锚）；
 * - 屏维非法/视图坍缩：整只手势吞事件零注入（离散捏合档同纪律——不向设备注入越界指位）。
 *
 * 反馈推进：激活与每笔 MOVE 折算同步写 [ScreencastGestureFeedbackState]（指位视图 px
 * + mono 读数），抬指清场。
 */
private suspend fun AwaitPointerEventScope.twoFingerDragGesture(
    down: PointerInputChange,
    screenWidth: Int,
    screenHeight: Int,
    onEvent: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit,
    feedback: ScreencastGestureFeedbackState?,
    mode: ScreencastGestureMode,
) {
    val viewWidth = size.width
    val viewHeight = size.height
    val baseRadiusPx = pinchNotchBaseRadius(screenWidth, screenHeight).toFloat()
    // 视图 px → 设备 px 换算比例（贴合测量保证纵横比一致——横纵同比）
    val scale = if (viewWidth > 0) screenWidth.toFloat() / viewWidth else 0f
    if (baseRadiusPx <= 0f || scale <= 0f || viewHeight <= 0) {
        drainUntilRelease(down.id)
        return
    }
    val (centerX, centerY) = mapPointerToScreen(
        down.position.x, down.position.y, viewWidth, viewHeight, screenWidth, screenHeight,
    )
    val centerXF = centerX.toFloat()
    val centerYF = centerY.toFloat()
    val downX = down.position.x
    val downY = down.position.y
    var activated = false
    // PAN 分离轴（垂直于首位移方向，激活时刻锁定）；PINCH 旋转基准角（激活时刻拖向角）
    var panAxisRad = 0f
    var pinchBaseAngleRad = 0f
    var lastFirstSentMs = Long.MIN_VALUE
    var lastSecondSentMs = Long.MIN_VALUE

    fun fingersDevice(dragXView: Float, dragYView: Float): ScreencastDragFingers {
        val dragX = dragXView * scale
        val dragY = dragYView * scale
        return when (mode) {
            ScreencastGestureMode.PINCH ->
                symmetricPinchFingers(centerXF, centerYF, baseRadiusPx, dragX, dragY, screenWidth, screenHeight)
            ScreencastGestureMode.PAN ->
                panTranslateFingers(centerXF, centerYF, baseRadiusPx, panAxisRad, dragX, dragY, screenWidth, screenHeight)
        }
    }

    fun fingersView(dragXView: Float, dragYView: Float): ScreencastDragFingers = when (mode) {
        ScreencastGestureMode.PINCH ->
            symmetricPinchFingers(downX, downY, baseRadiusPx / scale, dragXView, dragYView, viewWidth, viewHeight)
        ScreencastGestureMode.PAN ->
            panTranslateFingers(downX, downY, baseRadiusPx / scale, panAxisRad, dragXView, dragYView, viewWidth, viewHeight)
    }

    fun readoutText(dragXView: Float, dragYView: Float): String {
        val dragX = dragXView * scale
        val dragY = dragYView * scale
        return when (mode) {
            ScreencastGestureMode.PINCH -> pinchReadoutText(
                radiusPx = baseRadiusPx + hypot(dragX, dragY),
                baseRadiusPx = baseRadiusPx,
                angleRad = atan2(dragYView, dragXView),
                baseAngleRad = pinchBaseAngleRad,
            )

            ScreencastGestureMode.PAN -> panReadoutText(dragX, dragY)
        }
    }

    fun emit(action: Int, x: Float, y: Float, pointerId: Long) {
        // 指位收口在折算纯函数内完成（边界 = 屏宽高/视图宽高），取整不越界
        onEvent(action, x.roundToInt(), y.roundToInt(), screenWidth, screenHeight, pointerId)
    }

    val first = ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST
    val second = ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND
    val actionDown = ScrcpyControlProtocol.MOTION_ACTION_DOWN
    val actionMove = ScrcpyControlProtocol.MOTION_ACTION_MOVE
    val actionUp = ScrcpyControlProtocol.MOTION_ACTION_UP
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
        val dragXView = change.position.x - downX
        val dragYView = change.position.y - downY
        if (!change.pressed) {
            if (activated) {
                val fingers = fingersDevice(dragXView, dragYView)
                emit(actionUp, fingers.firstX, fingers.firstY, first)
                emit(actionUp, fingers.secondX, fingers.secondY, second)
            } else {
                // 双指同点轻点（迟滞内修饰键单击）：两根虚拟指在按下点 DOWN+UP
                emit(actionDown, centerXF, centerYF, first)
                emit(actionDown, centerXF, centerYF, second)
                emit(actionUp, centerXF, centerYF, first)
                emit(actionUp, centerXF, centerYF, second)
            }
            feedback?.clearGesture()
            break
        }
        change.consume()
        if (!activated) {
            if (!gestureHysteresisSatisfied(dragXView, dragYView)) continue
            activated = true
            when (mode) {
                ScreencastGestureMode.PINCH -> pinchBaseAngleRad = atan2(dragYView, dragXView)
                ScreencastGestureMode.PAN ->
                    panAxisRad = atan2(dragYView, dragXView) + (PI / 2).toFloat()
            }
            // DOWN 对落在初始分离半径（PINCH 轴 = 拖向、PAN 轴 = 垂直首位移方向）——真实
            // 捏合的物理序：两指先以初始指距落下，后续位移才折算指距增量（离散捏合档同序）
            val axisAngle = when (mode) {
                ScreencastGestureMode.PINCH -> pinchBaseAngleRad
                ScreencastGestureMode.PAN -> panAxisRad
            }
            val downFingers = panTranslateFingers(
                centerXF, centerYF, baseRadiusPx, axisAngle, 0f, 0f, screenWidth, screenHeight,
            )
            emit(actionDown, downFingers.firstX, downFingers.firstY, first)
            emit(actionDown, downFingers.secondX, downFingers.secondY, second)
            // DOWN 语义事件不经合并；合并窗自 DOWN 时刻起算
            val nowMs = System.nanoTime() / 1_000_000
            lastFirstSentMs = nowMs
            lastSecondSentMs = nowMs
            val overlay = panTranslateFingers(
                downX, downY, baseRadiusPx / scale, axisAngle, 0f, 0f, viewWidth, viewHeight,
            )
            feedback?.activate(
                mode,
                Offset(overlay.firstX, overlay.firstY),
                Offset(overlay.secondX, overlay.secondY),
                readoutText(dragXView, dragYView),
            )
            continue
        }
        val nowMs = System.nanoTime() / 1_000_000
        val fingers = fingersDevice(dragXView, dragYView)
        // 每虚拟指各持合并窗（两指同源事件驱动——窗同步放行，独立记账防单指漂发）
        if (shouldSendCoalescedMove(lastFirstSentMs, nowMs)) {
            emit(actionMove, fingers.firstX, fingers.firstY, first)
            lastFirstSentMs = nowMs
        }
        if (shouldSendCoalescedMove(lastSecondSentMs, nowMs)) {
            emit(actionMove, fingers.secondX, fingers.secondY, second)
            lastSecondSentMs = nowMs
        }
        val view = fingersView(dragXView, dragYView)
        feedback?.updateGesture(
            Offset(view.firstX, view.firstY),
            Offset(view.secondX, view.secondY),
            readoutText(dragXView, dragYView),
        )
    }
}

/**
 * 零注入吞事件至抬指（屏维非法/视图坍缩路径）：手势期间事件全消费不冒泡
 * （与主键过滤的整只手势吞事件同纪律）。
 */
private suspend fun AwaitPointerEventScope.drainUntilRelease(downId: PointerId) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == downId } ?: continue
        if (!change.pressed) break
        change.consume()
    }
}

/**
 * 修饰键双指拖激活迟滞（视图 px）：位移小于此值不激活（修饰键单击与拖动的分界死区，
 * 防手抖误启双指注入）。值 + 单位 + 依据：4px = 桌面鼠标单事件抖动量级上限。
 */
internal const val GESTURE_ACTIVATE_HYSTERESIS_PX = 4f

/** 迟滞判定（纯函数）：位移量（视图 px）达到 [GESTURE_ACTIVATE_HYSTERESIS_PX] 即激活。 */
internal fun gestureHysteresisSatisfied(dragXViewPx: Float, dragYViewPx: Float): Boolean =
    hypot(dragXViewPx, dragYViewPx) >= GESTURE_ACTIVATE_HYSTERESIS_PX

/** 双指指位对（Float 坐标——注入（设备 px）与叠层（视图 px）两坐标空间通用）。 */
internal data class ScreencastDragFingers(
    val firstX: Float,
    val firstY: Float,
    val secondX: Float,
    val secondY: Float,
)

/**
 * 对称双指指位（纯函数）：按下点为中点、指位沿拖向角对称分离，分离半径 =
 * [baseRadiusPx] + |拖动位移|（拖得越远指距越大 = 外扩/捏合，绕中点画圈 = 旋转分量）。
 * 坐标空间由调用方定（注入 = 设备 px、叠层 = 视图 px，比例换算在调用方），指位恒收口
 * [boundWidth]×[boundHeight] 内（越界收口与 [mapPointerToScreen] 同纪律）。
 */
internal fun symmetricPinchFingers(
    centerX: Float,
    centerY: Float,
    baseRadiusPx: Float,
    dragX: Float,
    dragY: Float,
    boundWidth: Int,
    boundHeight: Int,
): ScreencastDragFingers {
    val radius = baseRadiusPx + hypot(dragX, dragY)
    val angle = atan2(dragY, dragX)
    return axisSeparatedFingers(centerX, centerY, radius, angle, 0f, 0f, boundWidth, boundHeight)
}

/**
 * 双指同向平移指位（纯函数）：分离轴 = [axisAngleRad]（垂直于首位移方向，激活时刻
 * 锁定），两指整体随拖动位移全量平移（同向同距——鼠标到哪两指到哪）。
 */
internal fun panTranslateFingers(
    centerX: Float,
    centerY: Float,
    baseRadiusPx: Float,
    axisAngleRad: Float,
    dragX: Float,
    dragY: Float,
    boundWidth: Int,
    boundHeight: Int,
): ScreencastDragFingers =
    axisSeparatedFingers(centerX, centerY, baseRadiusPx, axisAngleRad, dragX, dragY, boundWidth, boundHeight)

/**
 * 轴对称双指通用折算：first = 中点 + r·axis + 平移、second = 中点 - r·axis + 平移，
 * 恒收口边界内（半径/平移越界时钉在边界——不向设备注入越界指位）。
 */
private fun axisSeparatedFingers(
    centerX: Float,
    centerY: Float,
    radiusPx: Float,
    axisAngleRad: Float,
    translateX: Float,
    translateY: Float,
    boundWidth: Int,
    boundHeight: Int,
): ScreencastDragFingers {
    val ax = radiusPx * cos(axisAngleRad)
    val ay = radiusPx * sin(axisAngleRad)
    val maxX = (boundWidth - 1).coerceAtLeast(0).toFloat()
    val maxY = (boundHeight - 1).coerceAtLeast(0).toFloat()
    fun clampX(x: Float) = x.coerceIn(0f, maxX)
    fun clampY(y: Float) = y.coerceIn(0f, maxY)
    return ScreencastDragFingers(
        firstX = clampX(centerX + ax + translateX),
        firstY = clampY(centerY + ay + translateY),
        secondX = clampX(centerX - ax + translateX),
        secondY = clampY(centerY - ay + translateY),
    )
}

/**
 * 捏合读数（mono 实时读数）：倍率 = 当前分离半径 / 初始分离半径；旋转角 = 拖向角相对
 * 激活时刻基准角的偏转（屏幕 y 向下坐标系——正值 = 顺时针 ↻、负值 = 逆时针 ↺）。
 * Locale.ROOT 锁小数点形态（本地化逗号会破坏 mono 读数）。
 */
internal fun pinchReadoutText(
    radiusPx: Float,
    baseRadiusPx: Float,
    angleRad: Float,
    baseAngleRad: Float,
): String {
    val ratio = if (baseRadiusPx > 0f) radiusPx / baseRadiusPx else 1f
    val rotationDeg = Math.toDegrees((angleRad - baseAngleRad).toDouble())
    val arrow = if (rotationDeg >= 0) "↻" else "↺"
    return "×" + String.format(Locale.ROOT, "%.2f", ratio) +
        " " + arrow + String.format(Locale.ROOT, "%.0f", abs(rotationDeg)) + "°"
}

/** 平移读数（mono 实时读数）：双指整体位移量（设备 px）。 */
internal fun panReadoutText(translateXPx: Float, translateYPx: Float): String =
    "平移 " + hypot(translateXPx, translateYPx).roundToInt() + "px"

/**
 * 画面区精确贴合测量：节点尺寸 = 可用宽与帧高上限（[heightCap] 与视口约束取小；
 * null = 无档位 cap，高度只受视口约束）内帧纵横比的最大内接矩形——节点纵横比恒
 * 与帧位图一致，[ContentScale.Fit] 满铺无 letterbox，触摸坐标换算
 * （[mapPointerToScreen] 以节点尺寸为基准）不被黑边带偏。
 *
 * 不可用 `fillMaxWidth + heightIn(max) + aspectRatio` 链替代：fillMaxWidth 钉死
 * minWidth 后，宽超「cap × 纵横比」的场景下 aspectRatio 各向拟合全败（按入射约束
 * 原样测量），节点退化为「满宽 × cap 高」的 letterbox 面——触摸基准宽与画面实际宽
 * 失配，注入坐标横向偏移最大可达画面宽的三成（视口越宽偏得越多）。
 */
internal fun Modifier.screencastFrameFitSize(
    aspectRatio: Float,
    heightCap: Dp? = Dimens.screencastFrameHeightMax,
): Modifier =
    this.layout { measurable, constraints ->
        // ratio 防御兜底按 1 处理（位图维度恒正——解码器产物；0/负值不产病态约束）
        val ratio = if (aspectRatio > 0f) aspectRatio else 1f
        // null = 无档位 cap（高度上限只取视口约束）；非 null = min(档位, 视口约束)。
        // cap=null 时至少一维约束必须有界（窗口根约束恒有界），双无界入射不产合法 fixed 尺寸
        val heightCapPx = heightCap?.toPx() ?: Float.POSITIVE_INFINITY
        val heightLimitPx = minOf(heightCapPx, constraints.maxHeight.toFloat())
        // 可用宽无界（Infinity）时高度上限仍把宽度钉在有限值，fixed 约束恒合法
        val widthPx = minOf(constraints.maxWidth.toFloat(), heightLimitPx * ratio)
            .roundToInt()
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val heightPx = (widthPx / ratio).roundToInt()
            .coerceAtMost(constraints.maxHeight)
            .coerceAtMost(heightCapPx.roundToInt())
        val placeable = measurable.measure(Constraints.fixed(widthPx, heightPx))
        layout(widthPx, heightPx) { placeable.place(0, 0) }
    }

/**
 * MOVE 注入合并窗（毫秒）：上限 ≈60Hz，与设备端渲染帧率同档——更密的注入不产生可感知
 * 的轨迹增益，只会放大设备端派发队列的积压（见 [screencastTouchPointerInput] 的合并契约）。
 */
internal const val MOVE_COALESCE_INTERVAL_MS = 16L

/**
 * MOVE 合并闸纯函数：[lastSentAtMs] 为本手势上一笔 MOVE 的发送时刻
 * （[Long.MIN_VALUE] = 本手势尚未发过，首笔恒放行）；距上一笔不足
 * [MOVE_COALESCE_INTERVAL_MS] 即抑制（该事件的位置由下一笔放行事件以最新坐标承载）。
 */
internal fun shouldSendCoalescedMove(lastSentAtMs: Long, nowMs: Long): Boolean =
    lastSentAtMs == Long.MIN_VALUE || nowMs - lastSentAtMs >= MOVE_COALESCE_INTERVAL_MS

/**
 * 画面滚轮回传：Scroll 型指针事件 → 滚动注入；Ctrl 修饰态（滚轮事件携带的
 * keyboardModifiers 含 Ctrl）且垂直分量非零 → 离散档捏合注入（判定见
 * [discretePinchNotchZoomIn]，事件序见 [pinchNotchEvents]）。符号约定：Compose
 * scrollDelta 正 y = 滚轮向下；控制协议正 vScroll = 滚轮向上（官方客户端同语义）
 * ——两约定相反，注入前取反。
 */
internal suspend fun PointerInputScope.screencastScrollPointerInput(
    screenWidth: Int,
    screenHeight: Int,
    onEvent: (action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pointerId: Long) -> Unit,
    onScroll: (x: Int, y: Int, screenWidth: Int, screenHeight: Int, hScroll: Float, vScroll: Float) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            when (val zoomIn = discretePinchNotchZoomIn(event.keyboardModifiers, change.scrollDelta.y)) {
                null -> {
                    val (x, y) = mapPointerToScreen(
                        change.position.x, change.position.y, size.width, size.height, screenWidth, screenHeight,
                    )
                    // Compose scrollDelta（PointerInputChange 载荷）正 y = 滚轮向下；控制协议正
                    // vScroll = 滚轮向上（官方客户端同语义）——两约定相反，注入前取反
                    onScroll(x, y, screenWidth, screenHeight, -change.scrollDelta.x, -change.scrollDelta.y)
                }

                else -> {
                    // Ctrl+滚轮每档一组自洽离散捏合（双指虚拟对经 onEvent 注入，零跨档状态）
                    pinchNotchEvents(screenWidth, screenHeight, zoomIn).forEach { touch ->
                        onEvent(touch.action, touch.x, touch.y, screenWidth, screenHeight, touch.pointerId)
                    }
                }
            }
        }
    }
}

/**
 * Ctrl+滚轮的离散捏合档判定（纯函数）：null = 非捏合档（无 Ctrl 修饰或垂直分量为零
 * ——横向滚轮滚动维持滚动注入）→ 走滚动通道；true = 放大档（滚轮向上）；false =
 * 缩小档（滚轮向下）。修饰键真源 = 滚轮事件自带的 keyboardModifiers（事件级快照，
 * 按住中途松开只影响后续事件——每档独立判定，无跨档锁存）。
 */
internal fun discretePinchNotchZoomIn(
    keyboardModifiers: PointerKeyboardModifiers,
    scrollDeltaY: Float,
): Boolean? {
    if (!keyboardModifiers.isCtrlPressed) return null
    if (scrollDeltaY == 0f) return null
    return scrollDeltaY < 0f
}

/**
 * 初始分离半径比例（每根虚拟指到对称中心的距离 = 0.08 × min(屏宽,屏高)，再收口
 * [PINCH_NOTCH_RADIUS_MIN_PX]..[PINCH_NOTCH_RADIUS_MAX_PX]）：半径过小则双指落点
 * 与中点的偏差被屏幕坐标取整吞掉（捏合比例失真），过大则矮屏上指位越界。
 */
internal const val PINCH_NOTCH_RADIUS_FRACTION = 0.08f

/** 分离半径下限（设备 px）：双指落点过近时档间比例差落进制粒度不可辨 */
internal const val PINCH_NOTCH_RADIUS_MIN_PX = 40

/** 分离半径上限（设备 px）：短边矮屏（如 240px）上指位不越屏 */
internal const val PINCH_NOTCH_RADIUS_MAX_PX = 120

/** 每档步长比例（= 初始分离半径 × 1/8）：每档约 1.125 倍档间缩放步进，细调量级 */
internal const val PINCH_NOTCH_STEP_FRACTION = 1f / 8f

/** 初始分离半径（设备 px；0 = 屏维非法，捏合档零发射）。 */
internal fun pinchNotchBaseRadius(screenWidth: Int, screenHeight: Int): Int {
    val minDim = minOf(screenWidth, screenHeight)
    if (minDim <= 0) return 0
    return (PINCH_NOTCH_RADIUS_FRACTION * minDim).toInt()
        .coerceIn(PINCH_NOTCH_RADIUS_MIN_PX, PINCH_NOTCH_RADIUS_MAX_PX)
}

/** 每档步长（设备 px；下限 1px——小屏档半径下取整归零即档间无差）。 */
internal fun pinchNotchStepPx(baseRadiusPx: Int): Int =
    (baseRadiusPx * PINCH_NOTCH_STEP_FRACTION).toInt().coerceAtLeast(1)

/**
 * 双指对称坐标对：屏幕中心为对称中心、竖直轴上下分离（first = 上指、second = 下指），
 * 坐标恒收口屏内（越界收口与 [mapPointerToScreen] 同纪律）。
 */
internal data class ScreencastPinchFingers(
    val firstX: Int,
    val firstY: Int,
    val secondX: Int,
    val secondY: Int,
)

/** 双指对称坐标（半径 = 各指到屏幕中心的距离）。 */
internal fun pinchNotchFingers(screenWidth: Int, screenHeight: Int, radiusPx: Int): ScreencastPinchFingers {
    val centerX = (screenWidth / 2).coerceIn(0, (screenWidth - 1).coerceAtLeast(0))
    val centerY = (screenHeight / 2).coerceIn(0, (screenHeight - 1).coerceAtLeast(0))
    return ScreencastPinchFingers(
        firstX = centerX,
        firstY = (centerY - radiusPx).coerceIn(0, (screenHeight - 1).coerceAtLeast(0)),
        secondX = centerX,
        secondY = (centerY + radiusPx).coerceIn(0, (screenHeight - 1).coerceAtLeast(0)),
    )
}

/**
 * 离散捏合档的虚拟触摸事件（注入通道消费面：action + 指位 + 虚拟指 id）。
 */
internal data class ScreencastPinchTouch(
    val action: Int,
    val x: Int,
    val y: Int,
    val pointerId: Long,
)

/**
 * 单档离散捏合事件序（纯函数）：每滚一档一组自洽手势——DOWN 对（初始分离半径）→
 * MOVE 对（半径 ± [pinchNotchStepPx]：放大档外扩、缩小档内收）→ UP 对；两根对称
 * 虚拟指 = [ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST/SECOND]，档与档之间不发
 * 常驻指（设备端每档回到无指态，比例失真不跨档累积）。发射序恒「同 action 先 first
 * 后 second」：服务端按流序合成多点 MotionEvent（第二指 DOWN 自动派生
 * ACTION_POINTER_DOWN，首指 UP 后末指 UP 收口 ACTION_UP）。屏维非法时零发射
 * （[pinchNotchBaseRadius] = 0）。
 */
internal fun pinchNotchEvents(
    screenWidth: Int,
    screenHeight: Int,
    zoomIn: Boolean,
): List<ScreencastPinchTouch> {
    val baseRadiusPx = pinchNotchBaseRadius(screenWidth, screenHeight)
    if (baseRadiusPx <= 0) return emptyList()
    val stepPx = pinchNotchStepPx(baseRadiusPx)
    val movedRadiusPx = if (zoomIn) {
        baseRadiusPx + stepPx
    } else {
        (baseRadiusPx - stepPx).coerceAtLeast(1)
    }
    val start = pinchNotchFingers(screenWidth, screenHeight, baseRadiusPx)
    val moved = pinchNotchFingers(screenWidth, screenHeight, movedRadiusPx)
    val down = ScrcpyControlProtocol.MOTION_ACTION_DOWN
    val move = ScrcpyControlProtocol.MOTION_ACTION_MOVE
    val up = ScrcpyControlProtocol.MOTION_ACTION_UP
    val first = ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST
    val second = ScrcpyControlProtocol.POINTER_ID_PINCH_SECOND
    return listOf(
        ScreencastPinchTouch(down, start.firstX, start.firstY, first),
        ScreencastPinchTouch(down, start.secondX, start.secondY, second),
        ScreencastPinchTouch(move, moved.firstX, moved.firstY, first),
        ScreencastPinchTouch(move, moved.secondX, moved.secondY, second),
        ScreencastPinchTouch(up, moved.firstX, moved.firstY, first),
        ScreencastPinchTouch(up, moved.secondX, moved.secondY, second),
    )
}
