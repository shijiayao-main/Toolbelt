package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.jiaoay.easy_dev_toolbox.core.theme.DarkEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LightEasyToolboxColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.jiaoay.toolbelt.device.DeviceLayoutBounds
import com.jiaoay.toolbelt.device.DeviceLayoutNode
import com.jiaoay.toolbelt.device.WindowViewNode

/**
 * 预览图纯函数面（渲染/交互换算零 UI 依赖，纯 JVM 单测）：
 * - fit 视口：世界域最大内接比 + 居中偏移；世界域/画布任一维非正返回单位视口；
 * - 命中检测：包含点候选中取面积最小者（深嵌套点到最内层）、文档序逆序等面积取
 *   视觉上层、退化矩形命中跳过、空白处 null、不可见节点可命中可选中；
 * - 类族判定：class 简名后缀封闭匹配 + 有子节点兜底（INPUT 先于 TEXT——EditText
 *   是 TextView 子类）；
 * - 全量扁平化：DFS 全展开语义（收起子树同样入图）、路径 id 与树投影同构、
 *   visibility 派生（View 内部源两档/a11y 源恒 null）、a11y 文本面、过滤词命中
 *   态逐节点标记（与树行 matchesQuery 同语义）；
 * - 视口跟随：选中 rect 与视口有交零动作（不劫持用户视点）、无交平移使居中；
 * - 双击判定：时间窗内 + 落点位移在容差内才算双击（纯时间判定下连点两个相邻
 *   节点误入双击分支）、无前置点击恒非双击；
 * - 类族色板槽位守护：paletteSlot 显式映射槽位唯一且在亮暗两套 previewPalette
 *   界内（跨模块隐契约钉死——enum 插序/扩员或色板缩员即红）。
 */
class DeviceLayoutDiagramTest {

    private fun wnode(
        viewId: String = "NO_ID",
        bounds: DeviceLayoutBounds = DeviceLayoutBounds(0, 0, 100, 100),
        visibility: Int = 0,
        children: List<WindowViewNode> = emptyList(),
        properties: Map<String, String> = emptyMap(),
    ) = WindowViewNode(
        className = "android.widget.FrameLayout",
        viewHash = "",
        viewId = viewId,
        bounds = bounds,
        windowLeft = null,
        windowTop = null,
        visibility = visibility,
        clickable = false,
        enabled = true,
        selected = false,
        activated = false,
        padding = null,
        childCount = children.size,
        children = children,
        properties = properties,
    )

    private fun anode(
        text: String = "",
        bounds: DeviceLayoutBounds = DeviceLayoutBounds(0, 0, 100, 100),
        children: List<DeviceLayoutNode> = emptyList(),
    ) = DeviceLayoutNode(
        index = 0,
        text = text,
        resourceId = "com.demo:id/x",
        className = "android.widget.TextView",
        packageName = "com.demo",
        contentDesc = "",
        checkable = false,
        checked = false,
        clickable = false,
        enabled = true,
        focusable = false,
        focused = false,
        scrollable = false,
        longClickable = false,
        password = false,
        selected = false,
        bounds = bounds,
        children = children,
    )

    private fun drect(
        id: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        visibility: Int? = null,
    ) = DiagramRect(
        id = id,
        rect = Rect(left, top, right, bottom),
        depth = 0,
        classLabel = id,
        idLabel = null,
        text = "",
        family = LayoutNodeFamily.CONTENT,
        visibility = visibility,
        matchesQuery = true,
        backgroundColor = null,
        hittable = Rect(left, top, right, bottom),
        drawn = true,
    )

    // ── fit 视口 ──

    @Test
    fun `fit viewport scales world to canvas and centers it`() {
        // 世界域 1000×500 画布 500×500：scale = min(0.5, 1.0) = 0.5，居中
        val vp = diagramFitViewport(Rect(0f, 0f, 1000f, 500f), Size(500f, 500f))
        assertEquals(0.5f, vp.scale)
        assertEquals(0f, vp.offsetX, "世界域宽恰好铺满画布（500×0.5=500），X 居中偏移 0")
        assertEquals(125f, vp.offsetY, "世界域高 250 居中于画布 500（(500-250)/2）")
    }

    @Test
    fun `fit viewport respects non zero world origin and scale bounds`() {
        val vp = diagramFitViewport(Rect(100f, 50f, 200f, 100f), Size(300f, 300f))
        assertEquals(3f, vp.scale, "scale = min(300/100, 300/50) 取小")
        assertEquals(
            -300f,
            vp.offsetX,
            "世界域宽恰铺满画布（100×3=300）：偏移 = -世界域原点×scale，屏上从 0 铺到 300",
        )
        assertEquals(-75f, vp.offsetY, "世界域高 150 在 300 画布居中：屏上从 75 到 225")
        // 画布远大于世界域时 scale 封顶 DIAGRAM_SCALE_MAX
        assertEquals(
            DIAGRAM_SCALE_MAX,
            diagramFitViewport(Rect(0f, 0f, 10f, 10f), Size(2000f, 2000f)).scale,
        )
        // 世界域远大于画布时 scale 兜底 DIAGRAM_SCALE_MIN
        assertEquals(
            DIAGRAM_SCALE_MIN,
            diagramFitViewport(Rect(0f, 0f, 100_000f, 100_000f), Size(100f, 100f)).scale,
        )
    }

    @Test
    fun `fit viewport degenerate world or canvas yields unit viewport`() {
        assertEquals(DiagramViewport(1f, 0f, 0f), diagramFitViewport(Rect.Zero, Size(100f, 100f)))
        assertEquals(DiagramViewport(1f, 0f, 0f), diagramFitViewport(Rect(0f, 0f, 10f, 10f), Size.Zero))
    }

    // ── 命中检测 ──

    @Test
    fun `hit test picks smallest containing rect over parents`() {
        val rects = listOf(
            drect("parent", 0f, 0f, 100f, 100f),
            drect("child", 10f, 10f, 50f, 50f),
        )
        assertEquals("child", diagramHitTest(rects, Offset(20f, 20f)), "包含点候选中取面积最小者")
        assertEquals("parent", diagramHitTest(rects, Offset(80f, 80f)), "点在父区子区外命父")
        assertNull(diagramHitTest(rects, Offset(200f, 200f)), "空白处 null（零动作）")
    }

    @Test
    fun `hit test skips degenerate rects and hits invisible nodes`() {
        val rects = listOf(
            drect("degenerate", 10f, 10f, 10f, 50f), // 宽 0 退化矩形：命中跳过
            drect("gone", 0f, 0f, 100f, 100f, visibility = 8), // GONE 不可见：可命中可选中
            drect("invisible", 40f, 40f, 60f, 60f, visibility = 4),
        )
        assertEquals(
            "invisible",
            diagramHitTest(rects, Offset(50f, 50f)),
            "不可见节点可命中（屏上「不存在」的框是排查入口）；退化矩形命中跳过",
        )
        assertEquals("gone", diagramHitTest(rects, Offset(5f, 5f)))
        assertNull(
            diagramHitTest(listOf(drect("degenerate", 10f, 10f, 10f, 50f)), Offset(10f, 20f)),
            "只含退化矩形的命中点返回 null（如实呈现不编默认值）",
        )
    }

    @Test
    fun `hit test prefers later document order on equal area`() {
        val rects = listOf(
            drect("first", 0f, 0f, 50f, 50f),
            drect("second", 0f, 0f, 50f, 50f),
        )
        assertEquals(
            "second",
            diagramHitTest(rects, Offset(10f, 10f)),
            "等面积候选取文档序后者（视觉上层优先）",
        )
    }

    // ── 类族判定 ──

    @Test
    fun `layout node family classifies closed suffixes with children fallback`() {
        assertEquals(LayoutNodeFamily.INPUT, layoutNodeFamily("android.widget.EditText", false))
        assertEquals(
            LayoutNodeFamily.INPUT,
            layoutNodeFamily("androidx.appcompat.widget.AppCompatEditText", false),
            "EditText 是 TextView 子类：INPUT 先于 TEXT（后缀序即优先级）",
        )
        assertEquals(LayoutNodeFamily.TEXT, layoutNodeFamily("android.widget.TextView", false))
        assertEquals(
            LayoutNodeFamily.BUTTON,
            layoutNodeFamily("com.google.android.material.button.MaterialButton", false),
        )
        assertEquals(LayoutNodeFamily.BUTTON, layoutNodeFamily("android.widget.Switch", false))
        assertEquals(LayoutNodeFamily.BUTTON, layoutNodeFamily("android.widget.ProgressBar", false), "进度/拖条归交互控件族")
        assertEquals(LayoutNodeFamily.IMAGE, layoutNodeFamily("android.widget.ImageView", false))
        assertEquals(LayoutNodeFamily.IMAGE, layoutNodeFamily("android.view.SurfaceView", false), "引擎面归 IMAGE 占位")
        assertEquals(LayoutNodeFamily.LIST, layoutNodeFamily("androidx.recyclerview.widget.RecyclerView", true))
        assertEquals(LayoutNodeFamily.LIST, layoutNodeFamily("android.webkit.WebView", true))
        assertEquals(LayoutNodeFamily.CONTAINER, layoutNodeFamily("android.widget.FrameLayout", true))
        assertEquals(
            LayoutNodeFamily.CONTAINER,
            layoutNodeFamily("androidx.constraintlayout.widget.ConstraintLayout", true),
        )
        assertEquals(
            LayoutNodeFamily.CONTAINER,
            layoutNodeFamily("android.view.ViewGroup", false),
            "ViewGroup 恒容器族（空子树同族）",
        )
        assertEquals(LayoutNodeFamily.CONTAINER, layoutNodeFamily("com.demo.CustomThing", true), "未知类名带子树兜底容器族")
        assertEquals(LayoutNodeFamily.CONTENT, layoutNodeFamily("com.demo.CustomThing", false), "未知类名叶子兜底内容族")
        assertEquals(LayoutNodeFamily.CONTENT, layoutNodeFamily("android.view.View", false))
        // OEM 定制类名形态（厂定制族按后缀照判）：
        // Lottie 动画面归 IMAGE 占位、内部类按 $ 取末段
        assertEquals(LayoutNodeFamily.TEXT, layoutNodeFamily("com.demo.oem.widget.OemTextView", false))
        assertEquals(LayoutNodeFamily.IMAGE, layoutNodeFamily("com.demo.oem.widget.OemImageView", false))
        assertEquals(LayoutNodeFamily.CONTAINER, layoutNodeFamily("com.demo.oem.widget.OemLinearLayout", true))
        assertEquals(
            LayoutNodeFamily.IMAGE,
            layoutNodeFamily("com.demo.oem.widget.OemLottieAnimationView", false),
            "Lottie 动画面归 IMAGE（内容引擎自绘，占位色）",
        )
        assertEquals(
            LayoutNodeFamily.CONTENT,
            layoutNodeFamily("com.google.android.material.tabs.TabLayout\$TabView", false),
            "内部类按 $ 取末段判型（TabView 无封闭后缀命中 → 叶子内容族兜底）",
        )
    }

    // ── 全量扁平化 ──

    @Test
    fun `layout preview rects flatten full tree with path ids and per source faces`() {
        val root = wnode(
            viewId = "id/root",
            children = listOf(
                wnode(viewId = "id/inv", visibility = 4),
                wnode(viewId = "id/b"),
            ),
        )
        val rects = layoutPreviewRects(root, "")
        assertEquals(3, rects.size, "全层级直出（DFS 全展开语义，与树懒展开态解耦）")
        assertEquals(listOf("0", "0/0", "0/1"), rects.map { it.id }, "路径 id 与树投影同构")
        assertEquals(0, rects[0].visibility, "可见节点读数 0（正常态）")
        assertEquals(4, rects[1].visibility, "View 内部源 INVISIBLE 读数进淡化档")
        assertEquals("FrameLayout", rects[0].classLabel, "class 简名派生")
        assertEquals("root", rects[0].idLabel, "resource-id 简名派生")
        assertEquals(100f, rects[0].rect.width, "rect = globalBounds")
        assertEquals("", rects[1].text, "View 内部源无文本面恒空串（如实，不编默认值）")
        assertEquals(LayoutNodeFamily.CONTAINER, rects[0].family)
        assertTrue(rects.all { it.matchesQuery }, "空词恒命中（自由浏览态）")

        val a11y = layoutPreviewRects(anode(text = "hi"), "")
        assertNull(a11y[0].visibility, "a11y 源无可见性面恒 null 按可见渲染（零假数据）")
        assertEquals("hi", a11y[0].text, "a11y 源文本入预览文本面")
        assertEquals(LayoutNodeFamily.TEXT, a11y[0].family)
    }

    @Test
    fun `layout preview rects keep full tree under query with per node match flags`() {
        val root = anode(children = listOf(anode(text = "Settings"), anode(text = "Other")))
        val rects = layoutPreviewRects(root, "settings")
        assertEquals(3, rects.size, "查询态全量保留（未命中降档呈现，不剔除——图上位置仍可查）")
        assertEquals(
            listOf(false, true, false),
            rects.map { it.matchesQuery },
            "命中态逐节点标记（与树行 matchesQuery 同语义——祖先是路径节点非命中节点）",
        )
    }

    @Test
    fun `layout preview rects parse device solid color with transparent fallback`() {
        fun bgOf(properties: Map<String, String>) =
            layoutPreviewRects(wnode(properties = properties), "")[0].backgroundColor

        // 真机实测形态：ARGB 有符号整值（-16777216 = 0xFF000000 黑）
        assertEquals(-16777216, bgOf(mapOf(LAYOUT_SOLID_COLOR_KEY to "-16777216")))
        assertEquals(-1, bgOf(mapOf(LAYOUT_SOLID_COLOR_KEY to "-1")), "白底 ARGB 直传")
        assertNull(bgOf(mapOf(LAYOUT_SOLID_COLOR_KEY to "0")), "0 = 未设/透明（回落类族洗底）")
        assertNull(bgOf(emptyMap()), "键缺席 = null（a11y 源无此面恒回落）")
        assertNull(bgOf(mapOf(LAYOUT_SOLID_COLOR_KEY to "not-a-number")), "不可解析如实回落不猜")
    }

    @Test
    fun `layout dark color check uses bt601 perceptual luma`() {
        assertTrue(layoutIsDarkColor(-16777216), "纯黑 #FF000000 深底")
        assertTrue(layoutIsDarkColor(0xFF102A43.toInt()), "深蓝 #FF102A43 深底")
        assertFalse(layoutIsDarkColor(-1), "纯白 #FFFFFFFF 浅底")
        assertFalse(layoutIsDarkColor(0xFFEF5350.toInt()), "亮红 #FFEF5350 浅底")
    }

    // ── 标签避让 ──

    private fun lcand(
        id: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        labelWidth: Float,
        labelHeight: Float,
        emphasized: Boolean = false,
        index: Int = 0,
    ) = DiagramLabelCandidate(
        id = id,
        rect = Rect(left, top, right, bottom),
        labelWidth = labelWidth,
        labelHeight = labelHeight,
        emphasized = emphasized,
        index = index,
    )

    /** 保留索引 → 候选 id 集（断言面；索引界内由 selection 平行下标契约保证） */
    private fun keptIds(candidates: List<DiagramLabelCandidate>): Set<String> =
        diagramLabelSelection(candidates).map { candidates[it].id }.toSet()

    @Test
    fun `label selection keeps disjoint fitting labels and returns ascending indices`() {
        val candidates = listOf(
            lcand("a", 0f, 0f, 100f, 20f, 40f, 10f, index = 0),
            lcand("b", 200f, 0f, 300f, 20f, 40f, 10f, index = 1),
        )
        val kept = diagramLabelSelection(candidates)
        assertEquals(listOf(0, 1), kept, "互不相交且都装得下：全保留，索引升序")
    }

    @Test
    fun `label selection skips labels that do not fit inside their node`() {
        val candidates = listOf(
            // 标签宽 80 > 节点宽 60：装不下跳过（放大到装得下才显示）
            lcand("wide-label", 0f, 0f, 60f, 50f, 80f, 10f, index = 0),
            // 标签高 30 > 节点高 20：同跳过
            lcand("tall-label", 100f, 0f, 200f, 20f, 40f, 30f, index = 1),
            // 零尺寸标签（空文本面）：跳过
            lcand("empty-label", 200f, 0f, 300f, 50f, 0f, 0f, index = 2),
            // 装得下的保留
            lcand("fits", 400f, 0f, 500f, 50f, 40f, 10f, index = 3),
        )
        val kept = keptIds(candidates)
        assertEquals(setOf("fits"), kept, "装不进自身框的标签不画（渐进披露）")
    }

    @Test
    fun `label selection yields to overlapping labels by priority`() {
        // 两标签盒同位相交（都装得下）：面积大的优先
        val byArea = listOf(
            lcand("small", 0f, 0f, 60f, 30f, 50f, 10f, index = 0),
            lcand("big", 0f, 0f, 200f, 100f, 50f, 10f, index = 1),
        )
        assertEquals(
            setOf("big"),
            keptIds(byArea),
            "同强调档相交时面积大的节点让位胜出（缩略图态标大区域）",
        )

        // 面积同档相交：文档序前者胜（确定性平局锚）
        val byOrder = listOf(
            lcand("first", 0f, 0f, 100f, 100f, 50f, 10f, index = 0),
            lcand("second", 0f, 0f, 100f, 100f, 50f, 10f, index = 1),
        )
        assertEquals(setOf("first"), keptIds(byOrder), "等面积相交取文档序前者")

        // 强调档（选中/hover）压过面积档
        val byEmphasis = listOf(
            lcand("big", 0f, 0f, 400f, 200f, 50f, 10f, index = 0),
            lcand("hovered", 0f, 0f, 60f, 30f, 50f, 10f, emphasized = true, index = 1),
        )
        assertEquals(
            setOf("hovered"),
            keptIds(byEmphasis),
            "选中/hover 标签优先保留（显式动作反馈）",
        )
    }

    @Test
    fun `label selection chain culls cascade overlaps`() {
        // a 与 b 相交（b 面积大胜），b 又与 c 相交但 c 与 a 不相交：a 被让位后，
        // c 只对已放置的 b 让位——让位是逐个贪心不是全局互斥
        val candidates = listOf(
            lcand("a", 0f, 0f, 100f, 20f, 90f, 10f, index = 0),
            lcand("b", 10f, 0f, 300f, 200f, 280f, 10f, index = 1),
            lcand("c", 400f, 0f, 500f, 200f, 90f, 10f, index = 2),
        )
        assertEquals(
            setOf("b", "c"),
            keptIds(candidates),
            "b 胜 a；c 与 b 不交照常保留",
        )
    }

    @Test
    fun `label selection returns list positions not document order indices`() {
        // 候选只占节点的子集：列表位置 0/1 的文档序是 5/9——返回值必须是列表位置
        // （文档序越候选表界 = 绘制段越界崩溃的真因锚，防回退）
        val candidates = listOf(
            lcand("doc-5", 0f, 0f, 100f, 20f, 40f, 10f, index = 5),
            lcand("doc-9", 200f, 0f, 300f, 20f, 40f, 10f, index = 9),
        )
        val kept = diagramLabelSelection(candidates)
        assertEquals(listOf(0, 1), kept, "返回的是候选列表位置（升序），非 candidate.index 文档序")
        assertEquals(setOf("doc-5", "doc-9"), kept.map { candidates[it].id }.toSet())
    }

    // ── 命中面级联（clipChildren）与绘制面级联（isDrawn） ──

    @Test
    fun `clip children narrows hittable area beyond parent box`() {
        // 父框 0..100（clipChildren 默认 true），子框 120..200 越出父框——命中面被
        // 裁空：点子框区不命中任何节点（屏上不可见的越界子节点不截胡）
        val root = wnode(
            bounds = DeviceLayoutBounds(0, 0, 100, 100),
            children = listOf(wnode(viewId = "id/out", bounds = DeviceLayoutBounds(120, 0, 200, 100))),
        )
        val rects = layoutPreviewRects(root, "")
        assertNull(diagramHitTest(rects, Offset(150f, 50f)), "越出裁剪父框的子节点命中面为空")
        assertEquals(
            Rect(120f, 0f, 100f, 100f),
            rects[1].hittable,
            "命中面 = 祖先裁剪区与自身框求交（负宽退化矩形如实呈现）",
        )
    }

    @Test
    fun `clip children false keeps out of bounds child hittable`() {
        // clipChildren=false：子框越出父框仍可命中（不裁剪语义）
        val root = wnode(
            bounds = DeviceLayoutBounds(0, 0, 100, 100),
            properties = mapOf("drawing:clipChildren" to "false"),
            children = listOf(wnode(viewId = "id/out", bounds = DeviceLayoutBounds(120, 0, 200, 100))),
        )
        val rects = layoutPreviewRects(root, "")
        assertEquals("0/0", diagramHitTest(rects, Offset(150f, 50f)), "关裁剪的父不收窄子命中面")
        assertEquals(Rect(120f, 0f, 200f, 100f), rects[1].hittable, "无裁剪命中面恒等全框")
    }

    @Test
    fun `nested clip cascades through non clipping parent`() {
        // 祖父裁剪（默认 true）→ 父不裁剪（false）→ 裁剪区穿透下传：孙框越出祖父
        // 框仍被裁空（父的不裁剪只豁免自身框，不豁免祖先裁剪区）
        val grandchild = wnode(viewId = "id/gc", bounds = DeviceLayoutBounds(120, 0, 200, 100))
        val parent = wnode(
            bounds = DeviceLayoutBounds(0, 0, 300, 100),
            properties = mapOf("drawing:clipChildren" to "false"),
            children = listOf(grandchild),
        )
        val root = wnode(bounds = DeviceLayoutBounds(0, 0, 100, 100), children = listOf(parent))
        val rects = layoutPreviewRects(root, "")
        assertNull(diagramHitTest(rects, Offset(150f, 50f)), "祖先裁剪区经不裁剪中层穿透下传")
    }

    @Test
    fun `drawn cascade marks willNotDraw containers pulled by visible drawn child`() {
        // 父 willNotDraw=true 但子可见且自绘：父被拉回 drawn（isDrawn 同语义）；
        // 独立的 willNotDraw 无可见绘制子的分支不拉回
        val drawnLeaf = wnode(viewId = "id/leaf", bounds = DeviceLayoutBounds(0, 0, 50, 50))
        val emptyContainer = wnode(
            viewId = "id/empty",
            bounds = DeviceLayoutBounds(60, 0, 90, 50),
            properties = mapOf("drawing:willNotDraw" to "true"),
        )
        val root = wnode(
            bounds = DeviceLayoutBounds(0, 0, 100, 100),
            properties = mapOf("drawing:willNotDraw" to "true"),
            children = listOf(drawnLeaf, emptyContainer),
        )
        val rects = layoutPreviewRects(root, "")
        assertTrue(rects[0].drawn, "willNotDraw 容器被可见绘制子拉回 drawn")
        assertTrue(rects[1].drawn, "可见自绘叶子恒 drawn")
        assertFalse(rects[2].drawn, "无可见绘制子的 willNotDraw 分支不拉回")
    }

    @Test
    fun `drawn cascade propagates invisible ancestors`() {
        // 父 visibility=4（INVISIBLE）：子的祖先可见链断裂——可见自绘的子也不
        // drawn；父自身因「父可见链 + 自身可见」不成立也不 drawn
        val leaf = wnode(viewId = "id/leaf", bounds = DeviceLayoutBounds(0, 0, 50, 50))
        val invisibleParent = wnode(
            viewId = "id/inv",
            bounds = DeviceLayoutBounds(0, 0, 100, 100),
            visibility = 4,
            children = listOf(leaf),
        )
        val root = wnode(bounds = DeviceLayoutBounds(0, 0, 100, 100), children = listOf(invisibleParent))
        val rects = layoutPreviewRects(root, "")
        assertFalse(rects[1].drawn, "不可见父自身不 drawn")
        assertFalse(rects[2].drawn, "祖先可见链断裂：可见自绘子也不 drawn")
        // a11y 源无可见性面：恒按可见参与级联（零假数据）
        val a11yRoot = layoutPreviewRects(anode(), "")
        assertTrue(a11yRoot[0].drawn, "a11y 源无可见性/willNotDraw 面：恒 drawn")
    }

    // ── 视口跟随 ──

    @Test
    fun `ensure visible keeps viewport when rect intersects and pans to center when not`() {
        val canvas = Size(500f, 500f)
        val viewport = DiagramViewport(scale = 1f, offsetX = 0f, offsetY = 0f)
        // 有交（rect 在画布内）：零动作不劫持用户视点
        assertEquals(
            viewport,
            diagramEnsureVisible(viewport, Rect(10f, 10f, 100f, 100f), canvas),
        )
        // 无交（rect 在视口右外侧）：平移使 rect 居中（保持 scale）
        val panned = diagramEnsureVisible(viewport, Rect(1000f, 1000f, 1100f, 1100f), canvas)
        assertEquals(1f, panned.scale, "平移不缩放")
        assertEquals(-800f, panned.offsetX, "rect 中心 1050 平移至画布中心 250（250-1050）")
        assertEquals(-800f, panned.offsetY)
    }

    // ── 双击判定（时间窗 + 位置容差） ──

    @Test
    fun `double tap requires time window and position slop`() {
        val last = Offset(100f, 100f)
        assertTrue(
            diagramIsDoubleTap(
                nowMs = 300,
                lastTapMs = 100,
                nowPos = Offset(102f, 101f),
                lastTapPos = last,
            ),
            "判定窗内 + 落点位移 < 容差 = 双击",
        )
        assertFalse(
            diagramIsDoubleTap(
                nowMs = 300,
                lastTapMs = 100,
                nowPos = Offset(120f, 100f),
                lastTapPos = last,
            ),
            "判定窗内但位移超容差（连点两个不同节点）不误入双击分支",
        )
        assertFalse(
            diagramIsDoubleTap(nowMs = 600, lastTapMs = 100, nowPos = last, lastTapPos = last),
            "同点位但超出判定窗非双击",
        )
        assertFalse(
            diagramIsDoubleTap(nowMs = 100, lastTapMs = Long.MIN_VALUE, nowPos = last, lastTapPos = last),
            "无前置点击（首击）恒非双击",
        )
    }

    // ── 类族色板槽位守护（跨模块隐契约） ──

    @Test
    fun `family palette slots are unique in bounds for both palettes and container has none`() {
        assertNull(LayoutNodeFamily.CONTAINER.paletteSlot(), "容器族不填充无槽位")
        val nonContainer = LayoutNodeFamily.entries.filter { it != LayoutNodeFamily.CONTAINER }
        val slots = nonContainer.mapNotNull { it.paletteSlot() }
        assertEquals(nonContainer.size, slots.distinct().size, "槽位唯一（enum 插序/扩员不得静默错色）")
        for (family in nonContainer) {
            val slot = family.paletteSlot()!!
            assertTrue(
                slot in 0 until LightEasyToolboxColors.previewPalette.size,
                "$family 槽位 $slot 须在亮色 previewPalette 界内（core 色板缩员即红）",
            )
            assertTrue(
                slot in 0 until DarkEasyToolboxColors.previewPalette.size,
                "$family 槽位 $slot 须在暗色 previewPalette 界内（亮暗两套同序承载）",
            )
        }
    }
}
