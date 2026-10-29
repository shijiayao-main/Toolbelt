package com.jiaoay.easy_dev_toolbox.arch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppTheme
import com.jiaoay.easy_dev_toolbox.core.theme.AppThemeSpec
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.ThemeMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * 像素级场景守护渲染底座（配方真源）。
 *
 * 新增 ImageComposeScene 像素守护时必须 import 本文件单源消费，禁止在测试类内
 * 私有复刻 renderPixels/contentRows 等配方——复刻即漂移（泵形态/背景锚/解码路径
 * 三处曾各自分叉）。私有拷贝由 [SceneGuardCopyRatchetTest] 棘轮守护，全仓零豁免。
 *
 * 宿主位 = :test-support 模块（jvmTarget 25 测试支撑件），对 composeApp 与全部
 * :feature-* 的测试源集可见——消费方 jvmTest 声明 testImplementation(project(":test-support"))。
 * 包名恒 com.jiaoay.easy_dev_toolbox.arch：composeApp 侧 arch 守护测试同包免 import 消费。
 *
 * 配方要点（各条均为实测取舍，改动前先读）：
 * - 泵循环恒走「帧时间 33ms 步进至动画结算预算」形态：enter 类动画在恒 0 帧时间下
 *   永不完成（内容停在不可见初值）；无动画场景继续推进若干帧为无害空转——统一
 *   配方免逐页特判。
 * - 组合期异常经 [CoroutineExceptionHandler] 捕获后转测试失败：ImageComposeScene
 *   对组合异常的默认行为是静默跳过应用（渲染成整版空白），不捕获会把「组合崩了」
 *   误报成「带数不足」。
 * - 协程上下文缺省 Dispatchers.Unconfined（FrameRecomposer 硬要求 ContinuationInterceptor
 *   承接方）；渲染期间有活跃后台协程持续写状态流的场景（抓取中引擎收集流、投屏解码流）
 *   必须传 dispatcher 单线程钉扎——场景上下文与泵循环整体钉在同一线程，组合/重组/
 *   measure/draw 全串行，消除 SnapshotStateObserver 跨线程误报。
 * - 密度恒 Density(1f)：像素断言全部以 1px = 1dp 口径书写。
 * - 像素解码恒 PNG 编码 → javax.imageio 读回：skia Bitmap.readPixels 直读在测试堆下 OOM。
 * - 锚配方（断言口径取舍）：
 *   - 描边钮计在场用内容像素差分：OutlinedButton 等描边钮的内容着色不落语义色
 *     精确色（描边/洗底合成路径），[countColor] 语义色对钮恒见底——同场景摘除/
 *     清场前后帧的 [countContentPx] 差分才是钮在场的可靠锚；
 *   - 带数等式断言只对 8px+ 排距区块成立：输入框（label/边框/正文行距 < 8px）与
 *     密排文本区内带会粘连漂移，含输入框场景的带数比较一律用 ≥/差分口径，禁用等值；
 *   - alpha 洗底/合成色内容的在场断言用 [probeWashColor] 实渲探针 + [countColor]
 *     精确计数（合成色无手算混合公式，skia src-over 实渲为准）。
 *   - 场景根不得裸挂内容件：Material3 Surface 恒 propagateMinConstraints = true，
 *     AppTheme 根 Surface 会把场景根的 min 尺寸（恒 = 场景全幅）传给直系子件——
 *     直系内容被强制满幅（胶囊/卡撑满全帧）；宿主面（Row/Column）先行吸收 min 再
 *     下发，场景内容镜像生产宿主形态包一层 Row/Column。
 *   - 输入框聚焦点击的坐标源用 [semanticsNodeBounds]（语义树定位）：从行带几何猜
 *     坐标会被 label 档位/多行高度/回执带形污染（单行框内隙 ~18px 与回执带隙同量级）。
 */
public const val SCENE_FRAME_STEP_NS = 33_000_000L

/** 动画结算预算 1.2s（AppMotion 标准档 spring 在此预算内结算） */
public const val SCENE_FRAME_BUDGET_NS = 1_200_000_000L

/**
 * 字素容差（逐通道差上限）：抗锯齿把小号字素笔画混成「字色×背景」过渡色，
 * 细笔画字体下字素像素覆盖度普遍 ≤50%（50% 混色的通道偏移 ≈ 0.5×|背景-字色| ≈ 80），
 * 容差须覆盖半覆盖像素；同时小于灰阶 UI 色/洗底色（字色×背景 alpha 混合，距纯
 * 字色 ≥ 0.85×|背景-字色| ≥ 100）的通道距——只用于限定区域内的小号字素计数。
 */
public const val GLYPH_COLOR_TOLERANCE: Int = 80

/**
 * 浅色主题 background 实取锚（行带检测默认底色锚；暗色场景 bg 参数传 [SCENE_DARK_BG]）。
 *
 * 惰性探针：空内容场景经 [renderPixels] 渲染后取众数像素——AppTheme 根 Surface
 * 恒以 colorScheme.background 铺满，空帧众数即 background 实际渲染值，锚与实测
 * 恒零差。与页面渲染共用同一 AppTheme 派生链，主题 background 调整锚随之漂移；
 * 若退回硬编码色值，主题 background 再深 ≥4/通道即越过内容差值阈、整帧误判内容。
 * 首次访问触发一次 32×32 空场景渲染（每测试 JVM 仅一次）。探针内部不得消费本锚
 * （[isContentPx]/[probeSchemeColor] 等）——lazy 初始化内的递归依赖会自锁。
 */
public val SCENE_LIGHT_BG: IntArray by lazy {
    val pixels = renderPixels(32, 32) { }
    val counts = HashMap<Int, Int>()
    for (row in pixels) {
        for (p in row) counts[p] = (counts[p] ?: 0) + 1
    }
    val mode = counts.maxByOrNull { it.value }?.key
        ?: error("背景探针未检出像素（空场景渲染失败）")
    intArrayOf((mode shr 16) and 0xFF, (mode shr 8) and 0xFF, mode and 0xFF)
}

/**
 * 暗色主题 background 实取锚（ThemeMode.Dark 空场景众数实取；暗色行带检测 bg 参数传入）。
 *
 * 配方与 [SCENE_LIGHT_BG] 同款惰性探针：空内容场景以 ThemeMode.Dark 经 [renderPixels]
 * 渲染后取众数像素即暗色 background 实际渲染值，锚与实测恒零差；主题暗底调整锚随之
 * 漂移。首次访问触发一次 32×32 空场景渲染（每测试 JVM 仅一次）。
 */
public val SCENE_DARK_BG: IntArray by lazy {
    val pixels = renderPixels(32, 32, themeMode = ThemeMode.Dark) { }
    val counts = HashMap<Int, Int>()
    for (row in pixels) {
        for (p in row) counts[p] = (counts[p] ?: 0) + 1
    }
    val mode = counts.maxByOrNull { it.value }?.key
        ?: error("暗态背景探针未检出像素（空场景渲染失败）")
    intArrayOf((mode shr 16) and 0xFF, (mode shr 8) and 0xFF, mode and 0xFF)
}

/**
 * 可复投场景句柄：同一场景多帧渲染（指针注入前后差分等交互形态）专用——
 * [scene] 暴露给调用方注入事件，[renderPixels] 可重复调用；一次性渲染走顶层
 * [renderPixels]（渲染完即关场景，调用方无需自理生命周期）。
 *
 * [dispatcher]：场景单线程钉扎（可选）。静止态页面（渲染期无后台协程写状态流）
 * 不传——Unconfined 即线程安全；渲染期间引擎/收集协程持续写 StateFlow 的场景
 * （抓取中、投屏 Streaming）必须传专用单线程 dispatcher，泵循环整体在该线程上
 * 执行（runBlocking + withContext 钉扎）。
 */
public class SceneGuardHandle(
    width: Int,
    height: Int,
    private val dispatcher: CoroutineDispatcher? = null,
) : AutoCloseable {

    private val compositionErrors = mutableListOf<Throwable>()

    /** 场景本体：sendPointerEvent 等事件注入口 */
    public val scene: ImageComposeScene = ImageComposeScene(
        width = width,
        height = height,
        density = Density(1f),
        coroutineContext = (dispatcher ?: Dispatchers.Unconfined) +
            CoroutineExceptionHandler { _, throwable -> compositionErrors += throwable },
    )

    /** 挂主题包裹内容（主题派生链恒 AppThemeSpec.Default，themeMode 参数化深浅态） */
    public fun setContent(themeMode: ThemeMode = ThemeMode.Light, content: @Composable () -> Unit) {
        scene.setContent {
            AppTheme(scheme = AppThemeSpec.Default, themeMode = themeMode) { content() }
        }
    }

    /**
     * 泵至 [frameBudgetNanos] 并解码像素，返回 (宽, 高, 像素RGB行数组)。
     * 重复调用各自从 t=0 重泵——前后两帧同配方，差分公平。
     *
     * [onFrame]：每帧 render 后回调。Main dispatcher 状态驱动内容的测试在此推进
     * scheduler——泵与状态推进交替，失效循环才不饿死。
     */
    public fun renderPixels(
        frameBudgetNanos: Long = SCENE_FRAME_BUDGET_NS,
        onFrame: () -> Unit = {},
    ): Array<IntArray> {
        fun pump(): org.jetbrains.skia.Image {
            var t = 0L
            var image = scene.render(t)
            var guard = 0
            while ((scene.hasInvalidations() || t < frameBudgetNanos) && guard < 200) {
                t += SCENE_FRAME_STEP_NS
                image = scene.render(t)
                onFrame()
                guard++
            }
            return image
        }

        val image = if (dispatcher == null) {
            pump()
        } else {
            runBlocking { withContext(dispatcher) { pump() } }
        }
        compositionErrors.firstOrNull()?.let {
            throw AssertionError("场景组合期异常（渲染面为空白的部分真因）：$it", it)
        }
        val data = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
            ?: error("PNG encode 失败")
        val buffered = ImageIO.read(ByteArrayInputStream(data.bytes))
        return Array(buffered.height) { y ->
            IntArray(buffered.width) { x -> buffered.getRGB(x, y) and 0xFFFFFF }
        }
    }

    override fun close() {
        scene.close()
    }
}

/**
 * 一次性场景渲染：建句柄 → 挂内容 → 泵至预算 → 关场景 → 返回像素。
 * 像素抽出后场景即弃（无下游消费者），调用方无需 scenes 清单与 tearDown 兜底。
 * 渲染期有活跃后台协程的场景不适用（场景关闭时序须由调用方掌控）——改持
 * [SceneGuardHandle] 自理生命周期。
 */
public fun renderPixels(
    width: Int,
    height: Int,
    themeMode: ThemeMode = ThemeMode.Light,
    frameBudgetNanos: Long = SCENE_FRAME_BUDGET_NS,
    dispatcher: CoroutineDispatcher? = null,
    onFrame: () -> Unit = {},
    content: @Composable () -> Unit,
): Array<IntArray> = SceneGuardHandle(width, height, dispatcher).use {
    it.setContent(themeMode, content)
    it.renderPixels(frameBudgetNanos, onFrame)
}

/** 与背景差值超阈的像素判定（RGB 曼哈顿距离；阈值 25 为 AA 可见判据） */
public fun isContentPx(p: Int, bg: IntArray = SCENE_LIGHT_BG, threshold: Int = 25): Boolean {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    return abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2]) > threshold
}

/**
 * 行带检测：与背景差值超阈的像素行聚类为「内容行」（x1 = 行内采样列上界，不含）。
 * 采样口径：横向 step 3px、行命中 >2 像素计入、带间隙 >3px 才分组（≤3px 归并同带）。
 */
public fun contentRows(
    pixels: Array<IntArray>,
    x1: Int,
    bg: IntArray = SCENE_LIGHT_BG,
    threshold: Int = 25,
): List<IntRange> {
    val hits = mutableListOf<Int>()
    for (y in pixels.indices) {
        var cnt = 0
        for (x in 0 until x1 step 3) {
            if (isContentPx(pixels[y][x], bg, threshold)) cnt++
        }
        if (cnt > 2) hits.add(y)
    }
    if (hits.isEmpty()) return emptyList()
    val groups = mutableListOf<IntRange>()
    var start = hits[0]
    var prev = hits[0]
    for (y in hits.drop(1)) {
        if (y - prev > 3) {
            groups.add(start..prev)
            start = y
        }
        prev = y
    }
    groups.add(start..prev)
    return groups
}

/**
 * 色值计数：与 [color] 逐通道差 ≤ [tolerance] 的像素入计（[tolerance] = 0 即精确匹配）。
 *
 * 跨字体环境（CI runner 与本地的西文字体面不同）的小号字素着色断言必须传
 * [GLYPH_COLOR_TOLERANCE]：细笔画字体下小号文本无纯色核心像素——笔画全部被
 * 抗锯齿混成「字色×背景」过渡色，精确计数随字体漂移到 0（同断言同代码本地绿、CI 红）。
 */
public fun countColor(pixels: Array<IntArray>, color: Int, tolerance: Int = 0): Int {
    var cnt = 0
    if (tolerance == 0) {
        for (row in pixels) {
            for (p in row) {
                if (p == color) cnt++
            }
        }
        return cnt
    }
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    for (row in pixels) {
        for (p in row) {
            if (abs(((p shr 16) and 0xFF) - r) <= tolerance &&
                abs(((p shr 8) and 0xFF) - g) <= tolerance &&
                abs((p and 0xFF) - b) <= tolerance
            ) cnt++
        }
    }
    return cnt
}

/**
 * 主题取色探针：小场景渲染大字号着色 Text，取出现次数最多的非背景色值——
 * 与页面渲染共用同一 AppTheme 派生链，主题色值变化探针随之漂移，无需硬编码。
 * [selector] 恒末位：调用面统一尾随 lambda 形态。
 */
public fun probeSchemeColor(bg: IntArray = SCENE_LIGHT_BG, selector: @Composable () -> Color): Int {
    val pixels = renderPixels(160, 90) {
        val c = selector()
        Text(text = "探针探针", color = c, fontSize = 40.sp)
    }
    val counts = HashMap<Int, Int>()
    for (row in pixels) {
        for (p in row) {
            if (!isContentPx(p, bg)) continue
            counts[p] = (counts[p] ?: 0) + 1
        }
    }
    return counts.maxByOrNull { it.value }?.key
        ?: error("探针场景未检出着色像素（取色失败）")
}

/** 各内容带互斥断言：相邻带间必须有真背景行（叠放/粘连回归即红） */
public fun assertBandsSeparated(bands: List<IntRange>) {
    for (i in 0 until bands.size - 1) {
        val gap = bands[i + 1].first - bands[i].last - 1
        assertTrue(
            gap > 3,
            "带 ${bands[i]} 与带 ${bands[i + 1]} 间隙 $gap px ≤ 3px——内容带叠放/粘连回归",
        )
    }
}

/** 带序不倒挂断言：后带首行必须在前带末行之后（聚类回归即红；弱于 [assertBandsSeparated]，无间隙下限） */
public fun assertBandsNotOverlapping(bands: List<IntRange>) {
    for (i in 0 until bands.size - 1) {
        assertTrue(
            bands[i].last < bands[i + 1].first,
            "带 ${bands[i]} 与带 ${bands[i + 1]} 倒挂——内容带聚类回归",
        )
    }
}

/** AWT 键入事件源（AWT KeyEvent 构造要求非 null source；JPanel 轻量组件 headless 安全） */
private val awtEventSource by lazy { JPanel() }

/**
 * 场景内左键点击注入（Press + Release 成对）。坐标 = 场图像素（密度恒 1f——1px = 1dp），
 * 点击目标坐标由调用方自行从渲染帧行带几何定位。点击链全真（指针命中 → 可点组件
 * onClick / 文本框聚焦），不旁路状态。
 */
public fun SceneGuardHandle.clickAt(x: Float, y: Float) {
    val position = Offset(x, y)
    scene.sendPointerEvent(
        eventType = PointerEventType.Press,
        position = position,
        buttons = PointerButtons(isPrimaryPressed = true),
        button = PointerButton.Primary,
    )
    scene.sendPointerEvent(
        eventType = PointerEventType.Release,
        position = position,
        buttons = PointerButtons(),
        button = PointerButton.Primary,
    )
}

/**
 * 场景内文本键入：逐字符造 AWT KEY_TYPED 包进 compose KeyEvent 经 [SceneGuardHandle.scene]
 * 注入（生产键盘输入链全真）。桌面文本框的 typed 判定要求真 AWT native event——合成
 * KeyEvent 工厂（nativeEvent = null）不满足 isTypedEvent 会被静默丢弃；故经
 * [InternalComposeUiApi] 工厂挂真 AWT 事件承载 codePoint。目标输入框须先 [clickAt]
 * 聚焦；不可打印字符（AWT keyChar 非打印）被文本框静默丢弃，调用方自查载荷。
 */
@OptIn(InternalComposeUiApi::class)
public fun SceneGuardHandle.typeText(text: String) {
    for (c in text) {
        val awt = java.awt.event.KeyEvent(
            awtEventSource,
            java.awt.event.KeyEvent.KEY_TYPED,
            System.currentTimeMillis(),
            0,
            java.awt.event.KeyEvent.VK_UNDEFINED,
            c,
        )
        scene.sendKeyEvent(
            ComposeKeyEvent(
                // KeyEventType 无 KeyTyped 档：typed 判定只看 AWT native event 的 id，
                // Compose 侧事件型恒 KeyDown（两条文本框键入路径均以此为 typed 承载型）
                key = Key(java.awt.event.KeyEvent.VK_UNDEFINED),
                type = KeyEventType.KeyDown,
                codePoint = c.code,
                nativeEvent = awt,
            ),
        )
    }
}

/** 全帧内容像素计数（单源 [isContentPx] 判定；摘除/清场前后帧差分锚——不依赖语义色精确匹配） */
public fun countContentPx(
    pixels: Array<IntArray>,
    bg: IntArray = SCENE_LIGHT_BG,
    threshold: Int = 25,
): Int {
    var cnt = 0
    for (row in pixels) {
        for (p in row) {
            if (isContentPx(p, bg, threshold)) cnt++
        }
    }
    return cnt
}

/**
 * 场景内语义节点收集（semantics 树序 = 布局序）。[matcher] 由调用方带语义键判定
 * （如 [androidx.compose.ui.semantics.SemanticsActions.SetText] 命中文本框、OnClick
 * 命中可点行）。场景内容必须先渲染至少一帧（semantics 树随组合生成）。
 */
public fun SceneGuardHandle.semanticsNodes(
    matcher: (androidx.compose.ui.semantics.SemanticsNode) -> Boolean,
): List<androidx.compose.ui.semantics.SemanticsNode> {
    val matched = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
    fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
        if (matcher(node)) matched += node
        node.children.forEach(::walk)
    }
    scene.semanticsOwners.forEach { walk(it.rootSemanticsNode) }
    return matched
}

/**
 * 场景内语义节点边界定位（[semanticsNodes] 的坐标面；[index] 0 起）。返回节点在
 * 场图像素坐标的边界（positionInRoot + size，1px = 1dp）——输入框聚焦点击、可点行
 * 点击的坐标源；场景内容必须先渲染至少一帧。
 */
public fun SceneGuardHandle.semanticsNodeBounds(
    index: Int = 0,
    matcher: (androidx.compose.ui.semantics.SemanticsNode) -> Boolean,
): androidx.compose.ui.geometry.Rect {
    val node = semanticsNodes(matcher).getOrNull(index)
        ?: error("语义树未命中第 $index 个匹配节点——组合未完成或语义键不匹配")
    return androidx.compose.ui.geometry.Rect(
        offset = node.positionInRoot,
        size = node.size.toSize(),
    )
}

/**
 * alpha 洗底合成色探针：[base] 底上叠 [wash]×[AppAlpha.hotWash] 实渲取众数色——
 * 选中 chip / 命中高亮等 alpha 合成色无手算混合公式可用（skia src-over 实渲为准），
 * 在场断言用 [countColor] 对本探针返回值精确计数。两取色均为 @Composable lambda
 * （与页面渲染共用同一 AppTheme 派生链，主题漂移探针随之漂移）。
 */
public fun probeWashColor(
    base: @Composable () -> Color,
    wash: @Composable () -> Color,
): Int {
    val pixels = renderPixels(120, 60) {
        Box(modifier = Modifier.fillMaxSize().background(base())) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Dimens.space4)
                    .background(wash().copy(alpha = AppAlpha.hotWash)),
            )
        }
    }
    val counts = HashMap<Int, Int>()
    for (row in pixels) {
        for (p in row) counts[p] = (counts[p] ?: 0) + 1
    }
    return counts.maxByOrNull { it.value }?.key
        ?: error("洗底探针未检出像素（合成色探针失败）")
}
