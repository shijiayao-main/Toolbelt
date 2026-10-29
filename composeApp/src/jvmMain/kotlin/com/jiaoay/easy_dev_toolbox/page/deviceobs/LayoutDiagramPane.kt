package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.interactions.TooltipIconButton
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlin.math.hypot
import kotlin.math.roundToInt
import com.jiaoay.toolbelt.device.LayoutTreeNode
import com.jiaoay.toolbelt.device.WindowViewNode

/**
 * 缩放档位下限（比例值，非 dp 不进 Dimens）：0.2 = 千节点全貌下限。
 */
internal const val DIAGRAM_SCALE_MIN = 0.2f

/**
 * 缩放档位上限（比例值）：10 = 深层 1px 线可辨放大上限。
 */
internal const val DIAGRAM_SCALE_MAX = 10f

/**
 * 滚轮单档缩放步进（比例值）：1.1 倍/档——细粒度档，常滚轮一档不失焦。
 */
internal const val DIAGRAM_ZOOM_STEP = 1.1f

/**
 * 点击/拖拽消歧迟滞（视图 px）：按下后位移小于此值内抬起 = 点击命中，过迟滞 =
 * 平移手势（抬指零命中）。值 + 单位 + 依据：4px = 桌面鼠标单事件抖动量级上限
 * （与投屏手势迟滞同源同值）。
 */
internal const val DIAGRAM_CLICK_HYSTERESIS_PX = 4f

/**
 * 双击判定窗（毫秒）：上一记点击完成后 [DIAGRAM_DOUBLE_TAP_INTERVAL_MS] 内再完成
 * 一记且落点位移在 [DIAGRAM_DOUBLE_TAP_SLOP_PX] 内 = 双击。值 + 单位 + 依据：
 * 400ms = 桌面双击间隔常用档下沿（投屏泊指双击判定同值）。
 */
internal const val DIAGRAM_DOUBLE_TAP_INTERVAL_MS = 400L

/**
 * 双击位置容差（视图 px）：两记落点位移 ≥ 此值不算双击（判定窗纯时间无位置容差
 * 时，连点两个相邻节点会误入双击分支）。值 + 单位 + 依据：与
 * [DIAGRAM_CLICK_HYSTERESIS_PX] 同值同源（桌面鼠标单事件抖动量级上限）；容差
 * 取迟滞门同值即两门不打架——过迟滞的位移已是平移手势，根本不进点击/双击判定。
 */
internal const val DIAGRAM_DOUBLE_TAP_SLOP_PX = DIAGRAM_CLICK_HYSTERESIS_PX

/**
 * 双击判定纯函数（internal 供单测）：时间窗内 + 落点位移在容差内才算双击；
 * [lastTapMs] 为 [Long.MIN_VALUE] = 无前置点击（恒非双击）。
 */
internal fun diagramIsDoubleTap(
    nowMs: Long,
    lastTapMs: Long,
    nowPos: Offset,
    lastTapPos: Offset,
): Boolean =
    lastTapMs != Long.MIN_VALUE &&
        nowMs - lastTapMs in 0 until DIAGRAM_DOUBLE_TAP_INTERVAL_MS &&
        hypot(nowPos.x - lastTapPos.x, nowPos.y - lastTapPos.y) < DIAGRAM_DOUBLE_TAP_SLOP_PX

/**
 * 视口纯状态：[scale] ∈ [DIAGRAM_SCALE_MIN]..[DIAGRAM_SCALE_MAX]；[offsetX]/[offsetY]
 * 为世界坐标系平移（屏幕坐标 = 世界坐标 × scale + offset）。
 */
internal data class DiagramViewport(val scale: Float, val offsetX: Float, val offsetY: Float)

/**
 * 预览类族（示意渲染的着色分档）：色板槽位经 [paletteSlot] 显式映射（
 * [LocalEasyToolboxColors] 的 previewPalette 只承载色值，序由本映射钉死——不走
 * ordinal 隐式对齐，enum 插序/扩员不得静默错色/越界）；CONTAINER 无填充
 * （嵌套容器的视觉穿透靠叶子色块读出）。判定按 class 简名后缀封闭匹配 + 有子节点
 * 兜底——未知类名的叶子归 CONTENT、带子树的归 CONTAINER，不实锤不虚标。
 */
internal enum class LayoutNodeFamily { TEXT, BUTTON, INPUT, IMAGE, LIST, CONTENT, CONTAINER }

/**
 * 类族 → 色板槽位的显式映射（internal 供单测，跨模块隐契约的收口点）：
 * [LayoutNodeFamily] 在本模块、previewPalette 色值在 `:core`（亮暗两套同序）——
 * 槽位序必须与 :core 色板值注释序一致（文本/按钮/输入/图像/列表/内容），由
 * DeviceLayoutDiagramTest 的槽位守护断言钉死；CONTAINER 无槽返回 null（不填充）。
 */
internal fun LayoutNodeFamily.paletteSlot(): Int? = when (this) {
    LayoutNodeFamily.TEXT -> 0
    LayoutNodeFamily.BUTTON -> 1
    LayoutNodeFamily.INPUT -> 2
    LayoutNodeFamily.IMAGE -> 3
    LayoutNodeFamily.LIST -> 4
    LayoutNodeFamily.CONTENT -> 5
    LayoutNodeFamily.CONTAINER -> null
}

/**
 * dvwv 流的节点实色属性键（真机实测在列：每节点带 `drawing:solidColor` = View 侧
 * getSolidColor() 的 ARGB 整值）。归因契约：getSolidColor() 是 fading-edge 绘制
 * 优化钩子——AOSP View 基类恒返 0、标准控件不覆写，**非背景色导出面**（0 = 基类
 * 值/未覆写）；消费面按 gist 近似辅助档使用：非 0 值直读为近似底色，缺席/0/不可
 * 解析回落类族洗底。
 */
internal const val LAYOUT_SOLID_COLOR_KEY = "drawing:solidColor"

/**
 * dvwv 流的子裁剪属性键（真机实测在列：`drawing:clipChildren`，View 体系默认 true）。
 * a11y 源无此面恒按默认 true（保守裁剪——越界子节点不截胡命中）。
 */
internal const val LAYOUT_CLIP_CHILDREN_KEY = "drawing:clipChildren"

/**
 * dvwv 流的自绘放弃属性键（真机实测在列：`drawing:willNotDraw`，View 体系默认
 * false）。绘制面级联的输入之一（缺席恒按会自绘——不实锤不虚标）。
 */
internal const val LAYOUT_WILL_NOT_DRAW_KEY = "drawing:willNotDraw"

/**
 * 背景色深浅判定（internal 供单测）：ITU-R BT.601 感知亮度（r×299 + g×587 +
 * b×114）/1000 < 128 为深底——实色底上的标签/骨架墨色自适应依据（深底配浅墨）。
 */
internal fun layoutIsDarkColor(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000 < 128
}

/**
 * 类族判定纯函数（internal 供单测）：先 INPUT 后 TEXT（EditText 是 TextView 子类，
 * 后缀序即优先级）；交互控件族含进度/拖条（ProgressBar/SeekBar/RatingBar/Spinner）；
 * 引擎面（SurfaceView/TextureView）与动画面（Lottie 系 AnimationView）归 IMAGE——
 * 内容由渲染引擎自绘，示意渲染只给占位色。后缀表封闭，未命中按 [hasChildren]
 * 兜底两档；内部类（`Outer$Inner`）按 `$` 再取末段判型。
 */
internal fun layoutNodeFamily(className: String, hasChildren: Boolean): LayoutNodeFamily {
    val short = className.substringAfterLast('.').substringAfterLast('$').ifEmpty { className }
    return when {
        short.endsWith("EditText") -> LayoutNodeFamily.INPUT
        short.endsWith("TextView") -> LayoutNodeFamily.TEXT
        short.endsWith("Button") || short.endsWith("CheckBox") || short.endsWith("RadioButton") ||
            short.endsWith("Switch") || short.endsWith("ToggleButton") || short.endsWith("Chip") ||
            short.endsWith("SeekBar") || short.endsWith("RatingBar") || short.endsWith("ProgressBar") ||
            short.endsWith("Spinner") -> LayoutNodeFamily.BUTTON
        short.endsWith("ImageView") || short.endsWith("SurfaceView") ||
            short.endsWith("TextureView") || short.endsWith("VideoView") ||
            short.endsWith("AnimationView") -> LayoutNodeFamily.IMAGE
        short.endsWith("RecyclerView") || short.endsWith("ListView") || short.endsWith("GridView") ||
            short.endsWith("ScrollView") || short.endsWith("ViewPager") ||
            short.endsWith("WebView") -> LayoutNodeFamily.LIST
        short.endsWith("Layout") || short == "ViewGroup" ||
            short.endsWith("FragmentContainerView") -> LayoutNodeFamily.CONTAINER
        hasChildren -> LayoutNodeFamily.CONTAINER
        else -> LayoutNodeFamily.CONTENT
    }
}

/**
 * 线框图扁平化矩形（[layoutPreviewRects] 一次成型产物；draw 内零重建零分配）：
 * [rect] = 节点 [LayoutTreeNode.globalBounds]（点亮/画框/图世界域同源）；
 * [visibility] 派生自 View 内部源可见性读数（null = 源无可见性面恒按可见渲染——
 * 源无关接口不扩员，a11y 源零假数据）；[text] = a11y 源文本（View 内部源恒空串，
 * 有文本画文本、无文本画 class 标签）；[matchesQuery] = 过滤词命中态（自由浏览态
 * 恒 true）；[backgroundColor] = dvwv 源 `drawing:solidColor` 实色（getSolidColor()
 * fading-edge 钩子值——非背景色导出面，作 gist 近似底色档；ARGB
 * 整值；缺席/透明(0)/不可解析为 null = 回落类族洗底）；[hittable] = 命中面（祖先
 * clipChildren 裁剪区与自身框求交——渲染面恒画全框线框，命中面按裁剪语义收窄）；
 * [drawn] = 绘制面级联产物（`!willNotDraw && 祖先可见 && 自身可见`，父被任一
 * 「实际绘制且可见」的子拉回 true——AOSP Layout Inspector isDrawn 同语义；缺
 * willNotDraw 面（a11y 源）恒按会自绘）。
 */
internal data class DiagramRect(
    val id: String,
    val rect: Rect,
    val depth: Int,
    val classLabel: String,
    val idLabel: String?,
    val text: String,
    val family: LayoutNodeFamily,
    val visibility: Int?,
    val matchesQuery: Boolean,
    val backgroundColor: Int?,
    val hittable: Rect,
    val drawn: Boolean,
)

/**
 * 两矩形求交纯函数（internal 供单测）：[clip] null = 无裁剪恒等原矩形；无重叠产
 * 退化矩形（宽/高 ≤ 0，命中面判退化即跳过——如实呈现空交，不编默认值）。
 */
internal fun intersectRects(clip: Rect?, rect: Rect): Rect =
    if (clip == null) {
        rect
    } else {
        Rect(
            maxOf(clip.left, rect.left),
            maxOf(clip.top, rect.top),
            minOf(clip.right, rect.right),
            minOf(clip.bottom, rect.bottom),
        )
    }

/**
 * 根树 → 预览扁平化（纯函数，internal 供单测）：DFS 全展开语义——收起子树同样
 * 入图（预览是层级数据的全量示意，与右侧树的懒展开投影解耦；路径 id 与树投影
 * 同构 `0/1/2`，选中/揭示单源不漂移）。[query] 非空时按 [layoutNodeMatches]
 * 标记命中态（未命中节点降档呈现，不剔除——图上位置仍可查）。
 *
 * 命中面级联：祖先 clipChildren=true 的节点框是子树命中边界（[intersectRects]
 * 逐层求交下传）——越出裁剪父框的子节点屏上不可见，命中即误报；clipChildren
 * 缺席按 View 体系默认 true（保守裁剪）。渲染面不受裁剪（线框恒全框，调试语义）。
 */
internal fun layoutPreviewRects(root: LayoutTreeNode, query: String): List<DiagramRect> {
    val q = query.trim()
    val queryActive = q.isNotEmpty()
    val rects = mutableListOf<DiagramRect>()

    // 绘制面级联的递归回执：自身可见性 + 级联终值（父 consumed 或回拉用）
    data class DrawnState(val selfVisible: Boolean, val drawnFinal: Boolean)

    fun emit(node: LayoutTreeNode, id: String, depth: Int, parentClip: Rect?, ancestorsVisible: Boolean): DrawnState {
        val bounds = node.globalBounds
        val viewNode = node as? WindowViewNode
        val rect = Rect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
        )
        val hittable = intersectRects(parentClip, rect)
        val selfVisible = viewNode?.visibility?.let { it == 0 } ?: true
        val visibleHere = ancestorsVisible && selfVisible
        // willNotDraw 缺席恒按会自绘（a11y 源无此面零假数据）；不可见态压过自绘面
        val willNotDraw = viewNode?.properties
            ?.get(LAYOUT_WILL_NOT_DRAW_KEY)
            ?.toBooleanStrictOrNull()
            ?: false
        val drawsSelf = visibleHere && !willNotDraw
        val ownIndex = rects.size
        rects += DiagramRect(
            id = id,
            rect = rect,
            depth = depth,
            classLabel = layoutClassShortName(node.className),
            idLabel = layoutResourceIdShort(node.resourceId),
            text = node.text,
            family = layoutNodeFamily(node.className, node.children.isNotEmpty()),
            visibility = viewNode?.visibility,
            matchesQuery = !queryActive || layoutNodeMatches(node, q),
            backgroundColor = viewNode?.properties
                ?.get(LAYOUT_SOLID_COLOR_KEY)
                ?.toIntOrNull()
                ?.takeIf { it != 0 },
            hittable = hittable,
            drawn = drawsSelf,
        )
        val clipChildren = viewNode?.properties
            ?.get(LAYOUT_CLIP_CHILDREN_KEY)
            ?.toBooleanStrictOrNull()
            ?: true
        val childClip = if (clipChildren) hittable else parentClip
        // 后序回拉：任一「实际绘制且可见」的子把纯容器拉回 drawn（isDrawn 同语义）——
        // 自身条目先入表（文档序渲染），子树回执后再回写本条
        var pulledByChild = false
        node.children.forEachIndexed { index, child ->
            val childState = emit(child, "$id/$index", depth + 1, childClip, visibleHere)
            pulledByChild = pulledByChild || (childState.drawnFinal && childState.selfVisible)
        }
        val drawnFinal = drawsSelf || pulledByChild
        if (drawnFinal && !drawsSelf) {
            rects[ownIndex] = rects[ownIndex].copy(drawn = true)
        }
        return DrawnState(selfVisible, drawnFinal)
    }

    emit(root, LAYOUT_ROOT_ID, 0, null, ancestorsVisible = true)
    return rects
}

/**
 * 标签避让候选（屏幕坐标系，[diagramLabelSelection] 的输入）：[rect] = 节点屏上矩形
 * （视口变换后）；[labelWidth]/[labelHeight] = measure 后的标签盒尺寸（文本节点 =
 * 文本，无文本节点 = class 简名 + id 简名合一行）；[emphasized] = 选中/hover 优先档；
 * [index] = 文档序（同优先级平局的确定性锚）。
 */
internal data class DiagramLabelCandidate(
    val id: String,
    val rect: Rect,
    val labelWidth: Float,
    val labelHeight: Float,
    val emphasized: Boolean,
    val index: Int,
)

/**
 * 标签避让选择（纯函数，internal 供单测）：候选按「强调档（选中/hover）优先 → 节点
 * 面积降序 → 文档序」贪心放置（文档序 = [DiagramLabelCandidate.index] 调用方树位，
 * 只作同优先级平局的确定性锚——候选只占节点的子集，树位会越过候选表界，**不可**
 * 当候选列表位置用）。保留条件：
 * - 标签盒完整落在自身节点矩形内——装不下名的节点不画（放大到装得下才出现，类地图
 *   缩放的渐进披露；越界溢写邻居正是标签重叠的来源）；
 * - 不与已放置标签盒相交——重叠名互相压字不可读，是排查噪音而非信息。
 * 返回保留候选在**传入列表中的位置**（升序），调用方按下标直取候选。
 */
internal fun diagramLabelSelection(candidates: List<DiagramLabelCandidate>): List<Int> {
    val order = candidates.withIndex().sortedWith(
        compareByDescending<IndexedValue<DiagramLabelCandidate>> { it.value.emphasized }
            .thenByDescending { it.value.rect.width * it.value.rect.height }
            .thenBy { it.value.index },
    )
    val placed = ArrayList<Rect>(candidates.size)
    val kept = sortedSetOf<Int>()
    for ((position, candidate) in order) {
        if (candidate.labelWidth <= 0f || candidate.labelHeight <= 0f) continue
        if (candidate.labelWidth > candidate.rect.width || candidate.labelHeight > candidate.rect.height) {
            continue
        }
        val box = Rect(
            candidate.rect.left,
            candidate.rect.top,
            candidate.rect.left + candidate.labelWidth,
            candidate.rect.top + candidate.labelHeight,
        )
        if (placed.any { it.overlaps(box) }) continue
        placed += box
        kept += position
    }
    return kept.toList()
}

/**
 * 标签避让的绘制阶段 measure 结果持有（与 [DiagramLabelCandidate] 平行下标——
 * 避让选择只吃尺寸不吃布局产物，绘制段按保留索引直取，免二次 measure）：
 * [primary] = 文本（有文本节点）或 class 简名；[secondary] = id 简名（可空）。
 */
private class DiagramLabelHolder(
    val primary: TextLayoutResult,
    val secondary: TextLayoutResult?,
)

/**
 * fit 视口纯函数（internal 供单测）：scale = 世界域在画布内的最大内接比，世界域
 * 居中。世界域/画布任一维非正（空层级零绘制面）返回单位视口。
 */
internal fun diagramFitViewport(world: Rect, canvas: Size): DiagramViewport {
    if (canvas.width <= 0f || canvas.height <= 0f || world.width <= 0f || world.height <= 0f) {
        return DiagramViewport(1f, 0f, 0f)
    }
    val scale = (canvas.width / world.width)
        .coerceAtMost(canvas.height / world.height)
        .coerceIn(DIAGRAM_SCALE_MIN, DIAGRAM_SCALE_MAX)
    return DiagramViewport(
        scale = scale,
        offsetX = (canvas.width - world.width * scale) / 2f - world.left * scale,
        offsetY = (canvas.height - world.height * scale) / 2f - world.top * scale,
    )
}

/**
 * 命中检测纯函数（internal 供单测）：包含点的候选中取**命中面**（[DiagramRect.hittable]
 * = 祖先 clipChildren 裁剪区收窄后的框——越界子节点屏上不可见不截胡）面积最小者
 * （Layout Inspector 同语义——深嵌套时点到最内层控件而非父容器）；逆序遍历使
 * 文档序后者（视觉上层）在等面积时优先。退化命中面（宽/高 ≤ 0 设备侧合法形态，
 * 含被裁剪空的子节点）命中跳过——「如实呈现不编默认值」；不可见节点可命中可选中
 * （点选屏上「不存在」的框正是「为什么屏上没这个控件」的排查入口）。无命中返回
 * null（空白处零动作）。
 */
internal fun diagramHitTest(rects: List<DiagramRect>, worldPoint: Offset): String? {
    var bestId: String? = null
    var bestArea = Float.MAX_VALUE
    for (index in rects.indices.reversed()) {
        val item = rects[index]
        val r = item.hittable
        if (r.width <= 0f || r.height <= 0f) continue
        if (worldPoint.x < r.left || worldPoint.x > r.right ||
            worldPoint.y < r.top || worldPoint.y > r.bottom
        ) {
            continue
        }
        val area = r.width * r.height
        if (area < bestArea) {
            bestArea = area
            bestId = item.id
        }
    }
    return bestId
}

/**
 * 视口跟随纯函数（internal 供单测）：世界 rect 经视口变换后与画布有交 → 原视口
 * 零动作（不劫持用户视点）；无交 → 平移使 rect 居中（保持 scale，即时无动画）。
 */
internal fun diagramEnsureVisible(
    viewport: DiagramViewport,
    world: Rect,
    canvas: Size,
): DiagramViewport {
    if (canvas.width <= 0f || canvas.height <= 0f) return viewport
    val screen = Rect(
        world.left * viewport.scale + viewport.offsetX,
        world.top * viewport.scale + viewport.offsetY,
        world.right * viewport.scale + viewport.offsetX,
        world.bottom * viewport.scale + viewport.offsetY,
    )
    val intersects = screen.right >= 0f && screen.left <= canvas.width &&
        screen.bottom >= 0f && screen.top <= canvas.height
    if (intersects) return viewport
    val centerX = (screen.left + screen.right) / 2f
    val centerY = (screen.top + screen.bottom) / 2f
    return DiagramViewport(
        scale = viewport.scale,
        offsetX = viewport.offsetX + (canvas.width / 2f - centerX),
        offsetY = viewport.offsetY + (canvas.height / 2f - centerY),
    )
}

/**
 * 层级预览舱（布局子页双栏左舱）：消费整棵 [root]（全层级直出——与右侧树的懒展开
 * 解耦）按 globalBounds 示意渲染：根窗 bounds 铺预览纸（恒浅底「文档」语义），节点
 * 优先画设备侧直读的实色（dvwv `drawing:solidColor` = getSolidColor()——fading-edge
 * 钩子值作 gist 近似底色档：叶子实底、容器洗底，缺席回落类族洗底着色；类 Android
 * Studio Preview 的合成预览——不取设备截图、不载
 * 图片资源）；a11y 源节点有文本画文本，View 内部源无文本面画 class 标签 + 文本占位
 * 骨架条（源无内容如实占位不编造）。实色深底上墨色自适应（纸色浅墨防黑吃黑）。
 * 标签避让（[diagramLabelSelection]）：标签装进自身节点框才显示、与已放标签
 * 相交即让位（选中/hover 优先 → 面积大优先）——放大即渐进披露，缩略图态不叠字。
 * 选中/hover 走 previewAccent 描边洗底；不可见节点（INVISIBLE/GONE 及 ROM 非零
 * 读数）默认零绘制——描边/标签/填充/骨架全不画，选中/hover 强调态才显形（点树内
 * 隐藏节点→图上定位其应在位，「屏上为什么没这个控件」的排查语义经选中保留；
 * 虚线不进本域，DASH_SCOPE 守护白名单不为破例）。
 *
 * 视口契约（换树复位 + 尺寸自适应的单一驱动 = LaunchedEffect）：fit 只发生在「根
 * 变化（重抓/切窗换树 → 复位缩放平移）」与「视口缺席（首次拿到画布尺寸）」两态——
 * onSizeChanged 在画布尺寸不变时不触发，换根后的复位不能依赖它（换根后无人 fit =
 * viewport null 死区，绘制/命中/缩放全静默）；纯画布尺寸变化不复位（不劫持用户
 * 视点）。世界域 = 根节点 bounds，fit 初始 + 滚轮缩放（指针锚定）+ 左键拖拽平移 +
 * 双击空白/工具行重置。
 *
 * 点击命中（迟滞消歧）走 [onReveal]（揭示语义：展开祖先路径 + 选中，与树行选中
 * 单源）；双击按命中分流（[diagramIsDoubleTap] 判定窗 = 时间 + 位置容差）：命中
 * 节点 → [onEnterSubtree] 进入该节点子树（数据面恒全窗不重根，重定的是宿主侧树
 * 投影），空白 → refit 重置视口。快照坐标语义与点亮/画框同源——dump 后设备界面
 * 变了图即陈旧，重抓刷新（不冒充实时）。
 *
 * 零动画纪律：缩放/平移/命中/选中全部即时无动画（静态快照图）。
 *
 * @param diagramLinked 投屏帧联动开关的呈现态（选中节点上帧画框，宿主 wiring 消费）
 * @param onToggleDiagramLinked 联动开关钮动作（VM 纯投影态，会话级偏好不落盘）
 * @param drawnOnly 绘制面开关呈现态（true = 只画实际绘制到屏幕的节点——isDrawn
 *   级联收窄；默认关 = 全层级直出（隐藏节点默认不画，选中/hover 才显形——「为什么
 *   屏上没这个控件」的排查入口经选中保留））
 * @param onToggleDrawnOnly 绘制面开关钮动作（VM 纯投影态，会话级偏好不落盘）
 * @param onReveal 命中揭示动作（路径 id；展开祖先 + 选中详情卡）
 * @param onEnterSubtree 双击命中节点的进子树动作（路径 id；宿主侧树投影重定，
 *   本舱数据面恒全窗零重根）
 */
@Composable
internal fun LayoutDiagramPane(
    root: LayoutTreeNode?,
    query: String,
    selectedId: String?,
    onReveal: (String) -> Unit,
    onEnterSubtree: (String) -> Unit,
    diagramLinked: Boolean,
    onToggleDiagramLinked: () -> Unit,
    drawnOnly: Boolean,
    onToggleDrawnOnly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rects = remember(root, query) { root?.let { layoutPreviewRects(it, query) } ?: emptyList() }
    // 命中面（绘制面开关门控后的子集）：渲染与命中同门（图上不存在的框不截胡点击）
    val hitRects = remember(rects, drawnOnly) {
        if (drawnOnly) rects.filter { it.drawn } else rects
    }
    val world = remember(root) {
        root?.globalBounds?.let {
            Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        } ?: Rect.Zero
    }
    val textMeasurer = rememberTextMeasurer()
    var viewport by remember { mutableStateOf<DiagramViewport?>(null) }
    // 已 fit 的根：换根检测锚（换根 → 复位重 fit；同根尺寸变化不复位）
    var fittedRoot by remember { mutableStateOf<LayoutTreeNode?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    // hover 目标 id：移动命中检测写、draw 内读（只失效绘制相位不触发重组）
    var hoverId by remember { mutableStateOf<String?>(null) }
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    // 画布底色恒深（consoleBg），预览内容在纸槽上呈现：预览墨水/强调/类族色板是
    // 亮暗同值恒深值族（纸上可辨），不随主题漂移
    val toolboxColors = LocalEasyToolboxColors.current
    val previewPaper = toolboxColors.previewPaper
    val previewInk = toolboxColors.previewInk
    val previewAccent = toolboxColors.previewAccent
    val previewPalette = toolboxColors.previewPalette
    val labelStyle = DesktopTypography.mono
    // 标签避让的绘制相位复用缓冲（draw 内 clear + 重填，两列表平行下标）
    val labelCandidates = remember { ArrayList<DiagramLabelCandidate>() }
    val labelHolders = remember { ArrayList<DiagramLabelHolder>() }

    // 视口单一驱动（契约见类头 KDoc）：换根复位 + 首尺寸 fit；同根纯尺寸变化零动作
    LaunchedEffect(root, canvasSize) {
        if (root == null || canvasSize == Size.Zero) return@LaunchedEffect
        if (fittedRoot != root || viewport == null) {
            fittedRoot = root
            viewport = diagramFitViewport(world, canvasSize)
        }
    }

    fun refit() {
        if (canvasSize != Size.Zero) viewport = diagramFitViewport(world, canvasSize)
    }

    fun worldPointOf(screen: Offset, vp: DiagramViewport): Offset =
        Offset((screen.x - vp.offsetX) / vp.scale, (screen.y - vp.offsetY) / vp.scale)

    // 图视口跟随：选中 rect 不在视口内 → 平移使居中（树侧点选/过滤态挤出均可达）
    LaunchedEffect(selectedId, canvasSize) {
        val id = selectedId ?: return@LaunchedEffect
        val target = rects.firstOrNull { it.id == id } ?: return@LaunchedEffect
        val vp = viewport ?: return@LaunchedEffect
        viewport = diagramEnsureVisible(vp, target.rect, canvasSize)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        // 工具行：联动开关钮（选中态 = primary tint + selectedWash 圆底）+ fit 重置钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.background(
                    color = if (diagramLinked) primary.copy(alpha = AppAlpha.selectedWash) else Color.Transparent,
                    shape = AppShapes.small,
                ),
            ) {
                TooltipIconButton(
                    icon = if (diagramLinked) MaterialSymbols.LINK else MaterialSymbols.LINK_OFF,
                    contentDescription = if (diagramLinked) "关闭投屏联动" else "开启投屏联动",
                    tip = "选中节点上投屏帧画框的联动开关（关 = 选中不上帧）",
                    onClick = onToggleDiagramLinked,
                    tint = if (diagramLinked) primary else onSurfaceVariant,
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                    // 双栏左舱贴窗左缘：长 tip 居中定位约 −100px 越缘，Right 定位弹向右侧
                    positioning = TooltipAnchorPosition.Right,
                )
            }
            Box(
                modifier = Modifier.background(
                    color = if (drawnOnly) primary.copy(alpha = AppAlpha.selectedWash) else Color.Transparent,
                    shape = AppShapes.small,
                ),
            ) {
                TooltipIconButton(
                    icon = MaterialSymbols.VISIBILITY,
                    contentDescription = if (drawnOnly) "显示全部层级" else "只看绘制面",
                    tip = "绘制面开关：开 = 只画实际绘制到屏幕的节点（willNotDraw/" +
                        "visibility 级联，千节点减噪）；关 = 全层级直出（隐藏节点默认" +
                        "不画，选中/hover 才显形）",
                    onClick = onToggleDrawnOnly,
                    tint = if (drawnOnly) primary else onSurfaceVariant,
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                    // 同上：左舱贴窗左缘，Right 定位防长 tip 越左缘
                    positioning = TooltipAnchorPosition.Right,
                )
            }
            TooltipIconButton(
                icon = MaterialSymbols.FIT_SCREEN,
                contentDescription = "重置预览视口",
                tip = "重置缩放/平移（双击画布空白处同效；双击命中节点 = 进入该节点子树" +
                    "视图，树列「返回」钮退出）；预览按布局快照示意绘制，非实时",
                onClick = { refit() },
                modifier = Modifier.size(Dimens.minimumInteractiveSize),
                // 同上：左舱贴窗左缘，Right 定位防长 tip 越左缘
                positioning = TooltipAnchorPosition.Right,
            )
        }

        Surface(
            color = toolboxColors.consoleBg,
            shape = AppShapes.medium,
            modifier = Modifier.weight(1f),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        canvasSize = Size(it.width.toFloat(), it.height.toFloat())
                    }
                    .pointerInput(hitRects) {
                        // 点击/拖拽消歧：迟滞内抬指 = 点击命中，过迟滞 = 平移手势；
                        // 双击 = 时间窗 + 位置容差判定（纯时间判定下连点两个不同节点
                        // 会误入双击分支），按命中分流：命中节点 → 进子树，空白 →
                        // 重置 fit
                        var lastTapMs = Long.MIN_VALUE
                        var lastTapPos = Offset.Zero
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dragging = false
                            val downPos = down.position
                            var lastPos = downPos
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                if (!change.pressed) {
                                    if (!dragging) {
                                        val vp = viewport
                                        val hitId = vp?.let {
                                            diagramHitTest(hitRects, worldPointOf(change.position, it))
                                        }
                                        if (diagramIsDoubleTap(
                                                nowMs = System.nanoTime() / 1_000_000,
                                                lastTapMs = lastTapMs,
                                                nowPos = change.position,
                                                lastTapPos = lastTapPos,
                                            )
                                        ) {
                                            // 双击消费后重置前置点击锚：三连击不连锁成
                                            // 「再双击」
                                            lastTapMs = Long.MIN_VALUE
                                            if (hitId != null) {
                                                onEnterSubtree(hitId)
                                            } else {
                                                refit()
                                            }
                                        } else {
                                            lastTapMs = System.nanoTime() / 1_000_000
                                            lastTapPos = change.position
                                            hitId?.let(onReveal)
                                        }
                                    }
                                    break
                                }
                                val delta = change.position - lastPos
                                lastPos = change.position
                                if (!dragging &&
                                    hypot(
                                        downPos.x - lastPos.x,
                                        downPos.y - lastPos.y,
                                    ) >= DIAGRAM_CLICK_HYSTERESIS_PX
                                ) {
                                    dragging = true
                                }
                                if (dragging) {
                                    change.consume()
                                    viewport = viewport?.let {
                                        DiagramViewport(it.scale, it.offsetX + delta.x, it.offsetY + delta.y)
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(hitRects) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Scroll -> {
                                        val change = event.changes.firstOrNull() ?: continue
                                        if (change.scrollDelta.y == 0f) continue
                                        val vp = viewport ?: continue
                                        val anchor = change.position
                                        val worldPoint = worldPointOf(anchor, vp)
                                        val newScale = (
                                            if (change.scrollDelta.y < 0f) {
                                                vp.scale * DIAGRAM_ZOOM_STEP
                                            } else {
                                                vp.scale / DIAGRAM_ZOOM_STEP
                                            }
                                            ).coerceIn(DIAGRAM_SCALE_MIN, DIAGRAM_SCALE_MAX)
                                        viewport = DiagramViewport(
                                            scale = newScale,
                                            offsetX = anchor.x - worldPoint.x * newScale,
                                            offsetY = anchor.y - worldPoint.y * newScale,
                                        )
                                    }

                                    PointerEventType.Move -> {
                                        val vp = viewport
                                        val pos = event.changes.firstOrNull()?.position
                                        hoverId = if (vp == null || pos == null) {
                                            null
                                        } else {
                                            diagramHitTest(hitRects, worldPointOf(pos, vp))
                                        }
                                    }

                                    PointerEventType.Exit -> hoverId = null
                                }
                            }
                        }
                    },
            ) {
                val vp = viewport ?: return@Canvas
                clipRect(0f, 0f, size.width, size.height) {
                    // 预览纸：根窗 bounds 铺恒浅底（设备界面的示意画布；退化 bounds
                    // 不铺纸——零面铺底无意义）
                    if (world.width > 0f && world.height > 0f) {
                        drawRect(
                            color = previewPaper,
                            topLeft = Offset(
                                world.left * vp.scale + vp.offsetX,
                                world.top * vp.scale + vp.offsetY,
                            ),
                            size = Size(world.width * vp.scale, world.height * vp.scale),
                        )
                    }
                    // 标签避让两段式：先收集候选（节点视口高过阈值才 measure——全量
                    // measure 是绘制主成本），避让选择后再绘制保留集
                    labelCandidates.clear()
                    labelHolders.clear()
                    rects.forEachIndexed { index, item ->
                        val left = item.rect.left * vp.scale + vp.offsetX
                        val top = item.rect.top * vp.scale + vp.offsetY
                        val right = item.rect.right * vp.scale + vp.offsetX
                        val bottom = item.rect.bottom * vp.scale + vp.offsetY
                        // 视口裁剪：与画布 AABB 不相交跳过（O(n) 单帧收口）
                        if (right < 0f || left > size.width || bottom < 0f || top > size.height) {
                            return@forEachIndexed
                        }
                        // 绘制面门控：开关开时只画实际绘制节点（渲染与命中同门）
                        if (drawnOnly && !item.drawn) {
                            return@forEachIndexed
                        }
                        val selected = item.id == selectedId
                        val hovered = item.id == hoverId
                        val emphasized = selected || hovered
                        // 退化矩形（宽/高 ≤ 0 设备侧合法形态）：描边收口到 1px 可辨，
                        // 命中检测已跳过——「如实呈现不编默认值」
                        val rectSize = Size(
                            maxOf(right - left, 1f),
                            maxOf(bottom - top, 1f),
                        )
                        val topLeft = Offset(left, top)
                        val visible = item.visibility == null || item.visibility == 0
                        // 隐藏节点（INVISIBLE/GONE 及 ROM 非零读数）默认零绘制：
                        // 描边/标签/填充/骨架全不画——屏幕上无它们的像素面，
                        // 画框即伪错位；选中/hover 强调态显形（点树内隐藏节点→图上
                        // 定位其应在位，「屏上为什么没这个控件」的排查语义经选中
                        // 保留）；命中检测对隐藏节点保持可点可选中。
                        // 底色三档：实色（dvwv `drawing:solidColor`，设备侧直读的
                        // gist 近似底色档）叶子实底直绘（文档序后绘的子层/标签在其上，无遮挡面）；容器
                        // 洗底（实底会盖掉嵌套视觉穿透，0.3 档保留）；缺席回落类族洗底。
                        // 类族洗底的容器族不填充（嵌套视觉穿透）；未命中过滤词不填充
                        // （降档只留淡描边，文字/标签同步不画——弱文字不裸乘 alpha）
                        if (visible && item.matchesQuery) {
                            val background = item.backgroundColor
                            when {
                                background != null && item.family != LayoutNodeFamily.CONTAINER ->
                                    drawRect(
                                        color = Color(background),
                                        topLeft = topLeft,
                                        size = rectSize,
                                    )

                                background != null ->
                                    drawRect(
                                        color = Color(background).copy(alpha = AppAlpha.bgSubtle),
                                        topLeft = topLeft,
                                        size = rectSize,
                                    )

                                item.family != LayoutNodeFamily.CONTAINER ->
                                    drawRect(
                                        // 槽位走 paletteSlot 显式映射（CONTAINER 已被
                                        // 分支排除，槽位恒非 null）
                                        color = previewPalette[item.family.paletteSlot()!!]
                                            .copy(alpha = AppAlpha.bgSubtle),
                                        topLeft = topLeft,
                                        size = rectSize,
                                    )
                            }
                        }
                        // 文本占位骨架：TEXT 类族无文本面（dvwv 源恒空）时画线框骨架条
                        // ——如实占位不编造文字（源无内容不猜），深底配纸色可读
                        if (visible && item.matchesQuery &&
                            item.family == LayoutNodeFamily.TEXT && item.text.isEmpty()
                        ) {
                            val skeletonHeight = Dimens.layoutPreviewSkeleton.toPx()
                            val inset = Dimens.space4.toPx()
                            if (rectSize.height >= Dimens.layoutDiagramLabelMinHeight.toPx() &&
                                rectSize.width > inset * 2 + skeletonHeight
                            ) {
                                drawRect(
                                    color = if (item.backgroundColor != null &&
                                        layoutIsDarkColor(item.backgroundColor)
                                    ) {
                                        previewPaper
                                    } else {
                                        previewInk.copy(alpha = AppAlpha.borderFaint)
                                    },
                                    topLeft = Offset(
                                        left + inset,
                                        top + rectSize.height * 0.4f,
                                    ),
                                    size = Size(
                                        (rectSize.width - inset * 2) * 0.6f,
                                        skeletonHeight,
                                    ),
                                )
                            }
                        }
                        if (emphasized) {
                            drawRect(
                                color = previewAccent.copy(
                                    alpha = if (selected) AppAlpha.scrimSoft else AppAlpha.bgSubtle,
                                ),
                                topLeft = topLeft,
                                size = rectSize,
                            )
                        }
                        if (emphasized || visible) {
                            drawRect(
                                color = if (emphasized) {
                                    previewAccent
                                } else {
                                    previewInk.copy(
                                        alpha = AppAlpha.borderFaint *
                                            if (item.matchesQuery) 1f else AppAlpha.contentDisabled,
                                    )
                                },
                                topLeft = topLeft,
                                size = rectSize,
                                style = Stroke(
                                    if (selected) Dimens.strokeThin.toPx() else Dimens.layoutDiagramStroke.toPx(),
                                ),
                            )
                        }
                        // 候选收集：有文本画文本，无文本画 class 简名 + id 简名合一行
                        //（View 内部源无文本面如实不画文本）；未命中过滤词/隐藏节点
                        // 零候选（隐藏无像素面，标签即伪错位）
                        if (visible && item.matchesQuery &&
                            rectSize.height >= Dimens.layoutDiagramLabelMinHeight.toPx()
                        ) {
                            val primary: TextLayoutResult
                            val secondary: TextLayoutResult?
                            if (item.text.isNotEmpty()) {
                                primary = textMeasurer.measure(item.text, labelStyle)
                                secondary = null
                            } else {
                                primary = textMeasurer.measure(item.classLabel, labelStyle)
                                secondary = item.idLabel?.let { textMeasurer.measure(it, labelStyle) }
                            }
                            labelCandidates += DiagramLabelCandidate(
                                id = item.id,
                                rect = Rect(left, top, right, bottom),
                                labelWidth = primary.size.width.toFloat() +
                                    (
                                        secondary?.let { Dimens.space4.toPx() + it.size.width.toFloat() }
                                            ?: 0f
                                        ),
                                labelHeight = primary.size.height.toFloat(),
                                emphasized = emphasized,
                                index = index,
                            )
                            labelHolders += DiagramLabelHolder(primary, secondary)
                        }
                    }
                    // 避让后的保留集绘制：装进自身框 + 不与已放标签相交（契约见
                    // [diagramLabelSelection]）；墨色自适应——实色深底（
                    // [layoutIsDarkColor]）配纸色浅墨防黑吃黑，浅底/无底色用预览墨；
                    // 文字颜色恒全值不乘透明度——弱文字不裸乘 alpha
                    // （ui-style-guide.md 弱文字纪律）
                    diagramLabelSelection(labelCandidates).forEach { keptIndex ->
                        val candidate = labelCandidates[keptIndex]
                        val item = rects[candidate.index]
                        val holder = labelHolders[keptIndex]
                        val ink = when {
                            candidate.emphasized -> previewAccent
                            item.backgroundColor != null && layoutIsDarkColor(item.backgroundColor) -> previewPaper
                            else -> previewInk
                        }
                        val topLeft = Offset(candidate.rect.left, candidate.rect.top)
                        drawText(
                            textLayoutResult = holder.primary,
                            color = ink,
                            topLeft = topLeft,
                        )
                        holder.secondary?.let { secondary ->
                            drawText(
                                textLayoutResult = secondary,
                                color = ink,
                                topLeft = Offset(
                                    topLeft.x + holder.primary.size.width + Dimens.space4.toPx(),
                                    topLeft.y,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
