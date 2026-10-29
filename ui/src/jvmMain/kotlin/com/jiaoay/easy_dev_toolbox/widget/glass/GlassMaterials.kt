package com.jiaoay.easy_dev_toolbox.widget.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalIsDarkTheme
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxGlassColors
import com.jiaoay.easy_dev_toolbox.core.theme.ToolboxGlassColors
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

/**
 * 液态玻璃材质层——
 * 全仓唯一 haze import 落点（UiArchitectureGuardTest GLASS_SCOPE 规则钉死）：
 * 消费方（宿主壳/feature 页）一律经本文件的包装修饰符消费，不直接引 dev.chrisbanes.haze 类型
 * （复刻 coil3「图片库零四处引入」隔离先例——升级/换库只动本文件）。
 *
 * ## 材质档位：材料服从交互
 * blur 的唯一设计语义 = **此层之下有需保留觉知的变化内容**。据此分档：
 * - **浮层档（sheet/thin）保留真玻璃**：palette/快捷键帮助卡/toast/导览卡浮在内容之上，
 *   下层内容实时变化且需保留觉知——blur 语义真实兑现；
 * - **chrome 档（dock/tab 条/状态栏/inspector）恒实底**：背后是静态 aurora
 *   （blur 静态渐变 = 另一个渐变，无从表达），且内容被 contentPadding 刻意挡在
 *   玻璃带外——chrome 玻璃零交互功能，纯装饰；
 * - **形态语言全保留**：浮动胶囊轮廓、1dp 发丝 rim、四边定缘、（浮层）三层软影
 *   ——设计身份在「形」不在「质」。
 *
 * ## 开关语义
 * 全部包装修饰符不接受玻璃开关参数：chrome/panel 恒实底、sheet/thin 恒真玻璃。
 * 真玻璃的降级语义只剩「玻璃根缺失 fail-fast」（App 级浮层如快捷键帮助卡走 opaqueSlot
 * 直接铺底，不走本文件）。
 */
object GlassMaterials {

    // chrome/panel 档恒实底——blur 只属于浮层档。消费方
    // appGlassChrome/appGlassPanel 直接走 opaqueSlot 实底
    // + 发丝 rim + 定缘（形态语言保留，材质诚实）。
    /** sheet 玻璃（命令面板/导览卡浮层）——blur 30dp + tintSheet（唯一投影档） */
    @Composable
    fun sheetStyle(shape: RoundedCornerShape): GlassStyle = glassStyle(
        tint = LocalToolboxGlassColors.current.tintSheet,
        shape = shape,
        blurRadius = Dimens.glassBlurSheet,
    )

    /** thin 玻璃（toast 浮层语义）——blur 18dp + tintThin */
    @Composable
    fun thinStyle(shape: RoundedCornerShape): GlassStyle = glassStyle(
        tint = LocalToolboxGlassColors.current.tintThin,
        shape = shape,
        blurRadius = Dimens.glassBlurThin,
    )

    @Composable
    private fun glassStyle(tint: Color, shape: RoundedCornerShape, blurRadius: Dp): GlassStyle {
        val chroma = if (LocalIsDarkTheme.current) 1.45f else 1.15f
        return GlassStyle {
            shape(shape)
            backgroundColor(tint)
            tint(tint)
            optics(blurRadius = blurRadius)
            edgeShadow(Color.Transparent)
            specularIntensity(0f)
            ambientResponse(0f)
            chromaMultiplier(chroma)
        }
    }
}

interface ToolboxGlassRoot

val LocalToolboxGlassRoot = staticCompositionLocalOf<ToolboxGlassRoot> {
    error("LocalToolboxGlassRoot 未装配——必须在 PageHome 根 provides（rememberToolboxGlassRoot）")
}

/** 窗口根玻璃状态（App 级不挂防卸载空引用；随窗口内容存续）——返回不透明句柄 */
@Composable
fun rememberToolboxGlassRoot(): ToolboxGlassRoot {
    val state = rememberHazeState()
    return remember(state) { ToolboxGlassRootImpl(state) }
}

/** [ToolboxGlassRoot] 的唯一实现（私有——宿主拿不到 HazeState 本体） */
private class ToolboxGlassRootImpl(internal val state: HazeState) : ToolboxGlassRoot

/** 挂窗口根内容包装层（aurora + 主内容）——单一 source 点，全部玻璃的采样底 */
fun Modifier.appHazeSource(root: ToolboxGlassRoot): Modifier =
    hazeSource(
        requireNotNull(root as? ToolboxGlassRootImpl) {
            "ToolboxGlassRoot 非本文件实现（句柄契约破坏）"
        }.state,
    )

@Composable
fun Modifier.appGlassChrome(shape: Shape, backgroundColor: Color? = null): Modifier {
    val colors = LocalToolboxGlassColors.current
    // 哑光语义：真哑光实底非半透明罩。[backgroundColor] = 结构带换装口（null = chrome
    // 档原底——非覆写套零漂移）；定缘/rim 形态语言恒保留。
    return background(backgroundColor ?: colors.opaqueSlotChrome, shape)
        .glassBorder(colors, shape)
        .glassRim(colors, shape, colors.rimHighlight)
}

@Composable
fun Modifier.appGlassPanel(shape: Shape): Modifier {
    val colors = LocalToolboxGlassColors.current
    return background(colors.opaqueSlotPanel, shape)
        .glassBorder(colors, shape)
        .glassRim(colors, shape, colors.rimPanel)
}

/** thin 档 rim 色（chrome/sheet/thin 同 .50/.22 档——Glass.kt rimHighlight 直用） */
private val ToolboxGlassColors.rimPanel: Color
    get() {
        val dark = rimHighlight.alpha <= AppAlpha.glassRimDark
        return Color.White.copy(alpha = if (dark) AppAlpha.glassRimPanelDark else AppAlpha.glassRimPanelLight)
    }

/** sheet 玻璃（命令面板/导览卡——浮层档，唯一允许投影；blur 语义真实兑现） */
@Composable
fun Modifier.appGlassSheet(shape: RoundedCornerShape): Modifier =
    appGlass(
        style = GlassMaterials.sheetStyle(shape),
        shape = shape,
    )

@Composable
fun Modifier.appGlassThin(shape: RoundedCornerShape): Modifier =
    appGlass(
        style = GlassMaterials.thinStyle(shape),
        shape = shape,
    )

@Composable
fun Modifier.appGlassChromeStatusbar(shape: Shape): Modifier {
    val colors = LocalToolboxGlassColors.current
    return background(colors.opaqueSlotChrome, shape)
        .glassBorder(colors, shape)
        .glassRim(colors, shape, colors.rimHighlight)
}

/** 浮层真玻璃公共构造（浮层恒玻璃——blur + tint + 定缘 + 发丝 rim） */
@Composable
private fun Modifier.appGlass(
    style: GlassStyle,
    shape: RoundedCornerShape,
): Modifier {
    val colors = LocalToolboxGlassColors.current
    val root = requireNotNull(LocalToolboxGlassRoot.current as? ToolboxGlassRootImpl) {
        "ToolboxGlassRoot 非本文件实现（句柄契约破坏）"
    }
    return hazeGlass(
        input = HazeInput.Backdrop(root.state),
        style = style,
    ).glassBorder(colors, shape).glassRim(colors, shape)
}

private fun Modifier.glassRim(
    colors: ToolboxGlassColors,
    shape: Shape,
    rimColor: Color = colors.rimHighlight,
): Modifier = drawBehind {
    // CSS box-shadow inset 语义 = 线整体在**内侧**；drawRoundRect stroke
    // 中心线在路径上 = 半条出界（左缘 rim 会被裁）。内缩 rimPx/2 修正。
    val rimPx = Dimens.glassStrokeHairline.toPx()
    val inset = rimPx / 2f
    val topRadius = shape.cornerPx(size, this)
    clipRect(left = 0f, top = 0f, right = size.width, bottom = topRadius + rimPx) {
        drawRoundRect(
            color = rimColor,
            topLeft = Offset(inset, inset),
            size = Size(size.width - rimPx, size.height - rimPx),
            style = Stroke(width = rimPx),
            cornerRadius = CornerRadius(topRadius, topRadius),
        )
    }
}

/** Shape 的顶角圆角半径（RoundedCornerShape 取 topStart；RectangleShape 等直角 shape = 0——贴边直角承板无圆角弯折） */
private fun Shape.cornerPx(shapeSize: Size, density: Density): Float =
    (this as? RoundedCornerShape)?.topStart?.toPx(shapeSize, density) ?: 0f

private fun Modifier.glassRimStart(
    colors: ToolboxGlassColors,
    shape: Shape,
    rimColor: Color = colors.rimHighlight,
): Modifier =
    drawBehind {
        // 内缩语义同 glassRim——左缘白线整体在内侧
        val rimPx = Dimens.glassStrokeHairline.toPx()
        val inset = rimPx / 2f
        val radius = shape.cornerPx(size, this)
        clipRect(left = 0f, top = 0f, right = radius + rimPx, bottom = size.height) {
            drawRoundRect(
                color = rimColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - rimPx, size.height - rimPx),
                style = Stroke(width = rimPx),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }

@Composable
fun Modifier.appGlassRail(shape: Shape, backgroundColor: Color? = null): Modifier {
    val colors = LocalToolboxGlassColors.current
    // .rail 描边仅 border-right 单边发丝线——非全周边；rim 用 panel 档
    //（.rail box-shadow inset 1px var(--panel-rim)——panel 档 .45/.20，非 chrome 档 .50/.22）。
    // [backgroundColor] = 结构带换装口（null = panel 档原底——非覆写套零漂移）；
    // 描边/rim 形态语言恒保留（形不变质变）。
    return background(backgroundColor ?: colors.opaqueSlotPanel, shape)
        .glassRailBorderRight(colors)
        .glassRimStart(colors, shape, colors.rimPanel)
}

/** rail 单边右缘发丝线（.rail border-right 1px solid var(--glass-brd) 直译） */
private fun Modifier.glassRailBorderRight(colors: ToolboxGlassColors): Modifier =
    drawBehind {
        val rimPx = Dimens.glassStrokeHairline.toPx()
        drawLine(
            color = colors.borderGlass,
            start = Offset(size.width - rimPx / 2, 0f),
            end = Offset(size.width - rimPx / 2, size.height),
            strokeWidth = rimPx,
        )
    }

private fun Modifier.glassBorder(colors: ToolboxGlassColors, shape: Shape): Modifier =
    drawBehind {
        val rimPx = Dimens.glassStrokeHairline.toPx()
        val topRadius = shape.cornerPx(size, this)
        // 外缘定缘：外扩半像素让整线在组件外缘完整可见（与 rim 的内缩相对——
        // border 在盒外缘 1px、rim 在盒内 1px，两条线物理分离）
        val half = rimPx / 2f
        drawRoundRect(
            color = colors.borderGlass,
            topLeft = Offset(-half, -half),
            size = Size(size.width + rimPx, size.height + rimPx),
            style = Stroke(width = rimPx),
            cornerRadius = CornerRadius(topRadius, topRadius),
        )
    }

/**
 * 内容渐隐边带：挂全屏铺的内容视口壳——
 * 滚动内容在视口顶/底边淡出（[Dimens.contentFadeBand] 垂直双带，`.content-fade`
 * mask-image 同构，iOS 主屏 dock 同款「内容→淡出→背景色」非截断）。
 *
 * 实现 = Offscreen 合成层 + drawWithContent + BlendMode.DstIn（[AuroraBackdrop]
 * 四边遮罩同配方）：DstIn 只削本层 alpha，不误伤底下玻璃 chrome。
 * 全部页面零改动免费获得（fade 挂统一视口壳，不挂各页滚动容器——多栏页
 * 几何各异，统一壳是唯一不迁各页的方案）。
 */
fun Modifier.appContentFade(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = Dimens.contentFadeBand.toPx()
            // 顶带：0 → band 线性淡入
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    (band / size.height) to Color.Black,
                ),
                size = size,
                blendMode = BlendMode.DstIn,
            )
            // 底带：底部 band 线性淡出
            drawRect(
                brush = Brush.verticalGradient(
                    (1f - band / size.height) to Color.Black,
                    1f to Color.Transparent,
                ),
                size = size,
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * 环境背景层（玻璃的折射底料）：双 radialGradient 光斑 + 四边软过渡遮罩。
 *
 * 光斑只属于内容区：
 * 四边 linearGradient 遮罩（BlendMode.DstIn）裁掉 chrome 带内光斑（光斑落进
 * 玻璃条显「廉价贴纸」）；Offscreen 合成层隔离 DstIn 不误伤底下内容。
 * 恒静态零动画。基底色不在此绘制（根 Surface 的 colorScheme.background 铺底）。
 */
@Composable
fun AuroraBackdrop(
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolboxGlassColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                if (!enabled) return@drawWithContent
                drawContent()
                // 光斑是**椭圆**（radial-gradient ellipse——两轴独立占窗宽/高百分比），
                // 正圆直译会在宽窗下丢「铺满感」。drawOval 双轴：
                // 主 46%W×40%H at 20%,26%；副 52%W×46%H at 84%,76%。
                // 主光斑（primary 系——Aurum 下即鎏金环境光）
                val pRx = size.width * 0.46f
                val pRy = size.height * 0.40f
                val pCx = size.width * 0.20f
                val pCy = size.height * 0.26f
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.auroraPrimary, Color.Transparent),
                        center = Offset(pCx, pCy),
                        radius = maxOf(pRx, pRy),
                    ),
                    size = Size(pRx * 2f, pRy * 2f),
                    topLeft = Offset(pCx - pRx, pCy - pRy),
                )
                // 副光斑（tertiary 系）
                val sRx = size.width * 0.52f
                val sRy = size.height * 0.46f
                val sCx = size.width * 0.84f
                val sCy = size.height * 0.76f
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.auroraTertiary, Color.Transparent),
                        center = Offset(sCx, sCy),
                        radius = maxOf(sRx, sRy),
                    ),
                    size = Size(sRx * 2f, sRy * 2f),
                    topLeft = Offset(sCx - sRx, sCy - sRy),
                )
                // ── 四边软过渡遮罩（DstIn：光斑在 chrome 带内 alpha→0）──
                val topB = Dimens.auroraMaskTopBegin.toPx()
                val topE = Dimens.auroraMaskTopEnd.toPx()
                val botB = Dimens.auroraMaskBottomBegin.toPx()
                val botE = Dimens.auroraMaskBottomEnd.toPx()
                val leftB = Dimens.auroraMaskLeftBegin.toPx()
                val leftE = Dimens.auroraMaskLeftEnd.toPx()
                val rightB = Dimens.auroraMaskRightBegin.toPx()
                val rightE = Dimens.auroraMaskRightEnd.toPx()
                // 顶边（0..topB 透明 → topE 不透明）
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        (topB / size.height) to Color.Transparent,
                        (topE / size.height) to Color.Black,
                    ),
                    size = size,
                    blendMode = BlendMode.DstIn,
                )
                // 底边
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        (1f - botE / size.height) to Color.Black,
                        (1f - botB / size.height) to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    size = size,
                    blendMode = BlendMode.DstIn,
                )
                // 左边
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        (leftB / size.width) to Color.Transparent,
                        (leftE / size.width) to Color.Black,
                    ),
                    size = size,
                    blendMode = BlendMode.DstIn,
                )
                // 右边
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Black,
                        (1f - rightE / size.width) to Color.Black,
                        (1f - rightB / size.width) to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    size = size,
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/**
 * 哑光内容卡顶缘受光线（.card/.zone 双影的第二层
 * box-shadow `inset 0 1px 0 0 var(--panel-rim)`）：panel 档 rim（亮 .45/暗 .20），
 * 内缩 rimPx/2 绘制；随 shape 圆角弯折。TonalCard/SectionCard/GhostCard
 * 三消费点单源（勿第三处复制）。
 */
@Composable
fun Modifier.appPanelRimTop(shape: Shape): Modifier {
    val dark = LocalIsDarkTheme.current
    val rimColor = Color.White.copy(
        alpha = if (dark) AppAlpha.glassRimPanelDark else AppAlpha.glassRimPanelLight,
    )
    return drawBehind {
        val rimPx = Dimens.glassStrokeHairline.toPx()
        val inset = rimPx / 2f
        val radius = shape.cornerPxPrivate(size, this)
        clipRect(left = 0f, top = 0f, right = size.width, bottom = radius + rimPx) {
            drawRoundRect(
                color = rimColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - rimPx, size.height - rimPx),
                style = Stroke(width = rimPx),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

/** [cornerPx] 的文件内别名（private 复用） */
private fun Shape.cornerPxPrivate(shapeSize: Size, density: Density): Float =
    (this as? RoundedCornerShape)?.topStart?.toPx(shapeSize, density) ?: 0f
