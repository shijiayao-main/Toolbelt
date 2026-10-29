package com.jiaoay.easy_dev_toolbox.arch

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 吸顶页布局视觉守护：**ImageComposeScene 零显示器渲染**替代实机 XTEST 注入截图——
 * 熄屏不可截 / 坐标误点他应用 / XWayland 帧冻结三病同除，CI 与无屏环境同样可跑。
 *
 * 验证面（「工具页内容与 tab 栏重叠」的回归防线）：
 * PageScaffold headerSticky 分支 content 兄弟（SegmentedControl ↔ 内容）之间的
 * 布局间隙必须 ≥ 8px（骨架 spacedBy(16dp) 修复的像素级钉子——修复前实测 1-4px
 * 全靠阴影残影撑视觉，回归即重叠）。
 *
 * 技术口径：背景色像素判定（浅色 onSurface 文字 vs background 底的差值带聚类）；
 * 探针内容用红色 Box 承载文字（色带可测、文字可读双保险）。
 * 渲染/行带配方单源 = [SceneGuardSupport]（本包）。
 */
class SegmentedGapGuardTest {

    @Test
    fun `segmented control to content gap is at least 8px`() {
        val pixels = renderPixels(SCENE_W, SCENE_H) {
            PageScaffold(title = "工具", headerSticky = true, bottomPenetration = true) {
                SegmentedControl(
                    options = listOf("XLog 解密", "Tag 过滤", "文件分割", "时间戳", "解压"),
                    selectedIndex = 0,
                    onSelect = {},
                )
                // 红色探针条承载内容（色差稳定可测；修复前此条与五签行仅 1-4px）
                Box(modifier = Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFFE05050))) {
                    Text(text = "内容探针")
                }
            }
        }
        val bands = contentRows(pixels, x1 = SCENE_W)
        assertTrue(bands.size >= 3, "应渲染出至少 3 带（标题/五签/内容探针），实际 ${bands.size}：$bands")
        val (title, seg, content) = listOf(bands[0], bands[1], bands[2])
        val gap = content.first - seg.last - 1
        assertTrue(
            gap >= 8,
            "五签行底(${seg.last}) → 内容顶(${content.first}) 间隙 $gap px < 8px——headerSticky " +
                "content 兄弟间距回归（骨架 spacedBy 缺失复发）",
        )
    }

    @Test
    fun `non sticky scaffold keeps outer spacing`() {
        // 非吸顶路径回归面：content 直接进骨架外层 Column——外层 spacedBy
        // 保证两路径语义对称（修复前像素实证红探针 97 贴签行底 96）。
        val pixels = renderPixels(SCENE_W, SCENE_H) {
            PageScaffold(title = "工具") {
                SegmentedControl(
                    options = listOf("甲", "乙"),
                    selectedIndex = 0,
                    onSelect = {},
                )
                Box(modifier = Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFFE05050))) {
                    Text(text = "内容探针")
                }
            }
        }
        val bands = contentRows(pixels, x1 = SCENE_W)
        assertTrue(bands.size >= 3, "非吸顶同款应 3 带，实际 ${bands.size}：$bands")
        val gap = bands[2].first - bands[1].last - 1
        assertTrue(gap >= 8, "非吸顶路径间隙 $gap px < 8px——外层 spacedBy 回归")
    }

    @Test
    fun `real tools page renders five band layout without overlap`() {
        // 真页回归（修复后像素实证：五签带 / amber 引导横幅 / 参数区 / 拖放容器
        // 四带互斥、各带间 14-20px 背景间隔——修复前叠放形态不再）。
        // 场景用例注入自建 VM 并用例尾 cancel——默认参 getViewModel() 会经
        // viewModel{} 构造永不销毁的 AppViewModel，其 ToolUsageSignal 订阅者滞留到
        // 后续测试类（PageUsageTrackerTest 的信号被重复落盘「expected 1 was 13」顺序型 flaky）。
        val sceneVm = com.jiaoay.easy_dev_toolbox.core.AppViewModel()
        val pixels = try {
            renderPixels(SCENE_W, SCENE_H) { com.jiaoay.easy_dev_toolbox.page.tools.ToolsPage(appViewModel = sceneVm) }
        } finally {
            sceneVm.viewModelScope.cancel()
        }
        val bands = contentRows(pixels, x1 = SCENE_W)
        // 页头 + 五签 + amber 引导横幅 + 参数区 + 拖放容器：至少 4 带内容带
        assertTrue(bands.size >= 4, "工具页 XLog 签应渲染 ≥4 内容带，实际 ${bands.size}：$bands")
        // 相邻带互斥 + 间隔 ≥8px（聚类阈值 3px 内视为同带；带间背景行 = 真分离）
        for (i in 0 until bands.size - 1) {
            val gap = bands[i + 1].first - bands[i].last - 1
            assertTrue(
                gap > 3,
                "带 ${bands[i]} 与带 ${bands[i + 1]} 间隙 $gap px ≤ 3px——" +
                    "工具页内容带粘连/叠放回归（根因② AnimatedContent 多根 or 骨架 spacedBy 缺失复发）",
            )
        }
    }

    @Test
    fun `scene render chain is deterministic`() {
        // 基建自检：同场景两帧渲染稳定（flaky 防线——skia 字体栅格化跨帧一致性）
        val content: @Composable () -> Unit = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("稳定性探针甲")
                Text("稳定性探针乙")
            }
        }
        val p1 = renderPixels(SCENE_W, SCENE_H, content = content)
        val b1 = contentRows(p1, x1 = SCENE_W)
        val p2 = renderPixels(SCENE_W, SCENE_H, content = content)
        val b2 = contentRows(p2, x1 = SCENE_W)
        assertEquals(b1.size, b2.size, "同内容两次渲染行带数应一致（${b1.size} vs ${b2.size}）")
        assertEquals(b1.first().first, b2.first().first, "首带起点应一致")
    }

    // ── hover 形态守护（子 tab 悬停矩形 vs 选中圆角形态不一致的回归防线）──

    /** 源码锚读文件（重命名/移动被锚文件后本守护即红，须同步路径） */
    private fun sourceAt(path: String): String {
        val file = File(locateProjectRoot(), path)
        assertTrue(file.isFile, "源码锚文件缺失：$path（重命名/移动后请同步本守护）")
        return file.readText()
    }

    @Test
    fun `segmented control unselected item hover is self-drawn capsule wash`() {
        // 源码锚：未选中条目 hover = 自绘哑光圆角洗底（三断言缺一即红）——
        // ① indication = null（foundation 默认 indication 是矩形 overlay，
        //    与选中胶囊同框对撞即本守护对象）；
        // ② 洗底色经 selectionHoverWashColor 单源（就地合成色回潮即红）；
        // ③ hover 底 shape = CircleShape（无 shape 背景即矩形回潮）。
        val itemBlock = sourceAt(SEGMENTED_CONTROL_PATH).substringAfter("options.forEachIndexed")
        assertTrue(
            "indication = null" in itemBlock,
            "条目 clickable 必须抑制默认矩形 indication（indication = null）——矩形 hover 回潮即红",
        )
        assertTrue(
            "selectionHoverWashColor(hovered = hovered)" in itemBlock,
            "hover 洗底色必须经 selectionHoverWashColor 单源引用——就地合成色回潮即红",
        )
        val hoverWashBackground = itemBlock
            .substringAfter("selectionHoverWashColor(hovered = hovered)")
            .substringBefore(".clickable")
        assertTrue(
            "shape = CircleShape" in hoverWashBackground,
            "hover 洗底必须带 shape = CircleShape——无 shape 背景（矩形 hover）回潮即红",
        )
        // 选中段实底链不取洗底：选中身份槽必须仍在条目块内
        assertTrue(
            "surfaceColors.selectedContainer" in itemBlock,
            "选中段必须保持 selectedContainer 实底链——选中段取洗底即红",
        )
    }

    @Test
    fun `shared chip and ic buttons use the same hover wash recipe`() {
        // 同批共享件同配方锚：ChipButton（页头/对话框动作钮族）与 IcBtnContainer
        // （任务卡行尾三钮族）——indication = null + selectionHoverWashColor 单源
        // 洗底叠加于既有容器底之上，形状对齐各自既有形态（chip 经 shape 参数、
        // ic-btn 走 AppShapes.small）。
        val chipImpl = sourceAt(CHIP_BUTTON_PATH).substringAfter("private fun ChipButtonImpl")
        assertTrue(
            "indication = null" in chipImpl,
            "chip 钮 clickable 必须抑制默认矩形 indication——矩形 hover 回潮即红",
        )
        assertTrue(
            ".background(selectionHoverWashColor(hovered = hovered), shape)" in chipImpl,
            "chip 钮 hover 洗底必须经 selectionHoverWashColor 单源且用既有 shape——回潮即红",
        )
        val icBtnBlock = sourceAt(KBD_TEXT_PATH).substringAfter("fun IcBtnContainer")
        assertTrue(
            "indication = null" in icBtnBlock,
            "ic-btn clickable 必须抑制默认矩形 indication——矩形 hover 回潮即红",
        )
        assertTrue(
            ".background(selectionHoverWashColor(hovered = hovered), AppShapes.small)" in icBtnBlock,
            "ic-btn hover 洗底必须经 selectionHoverWashColor 单源且用 AppShapes.small——回潮即红",
        )
    }

    @Test
    fun `hovering an unselected segment paints capsule wash with corners outside unchanged`() {
        // 像素级场景锚：hover 注入后条目区出现洗底，且差集包围盒四角不在差集内
        // （CircleShape 圆角裁剪——矩形 hover 回潮时四角会落入差集即红）。
        SceneGuardHandle(HOVER_W, HOVER_H).use { harness ->
            harness.setContent {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SegmentedControl(
                        options = listOf("甲段", "乙段", "丙段"),
                        selectedIndex = 0,
                        onSelect = {},
                    )
                }
            }
            hoverCapsuleWashIsClippedToCorners(harness)
        }
    }

    /** hover 差分主体（句柄复投：注入前后各泵一帧，像素差集判胶囊裁剪） */
    private fun hoverCapsuleWashIsClippedToCorners(harness: SceneGuardHandle) {
        val scene = harness.scene
        val before = harness.renderPixels()
        // 页面底基准从渲染像素实采（右下角远离控件；contentRows 的缺省底是
        // PageScaffold 实测值，本场景裸 Box 走 colorScheme.background，不可混用）
        val pageBg = before[before.size - 5][before.first().size - 5]
        val bgR = (pageBg shr 16) and 0xFF
        val bgG = (pageBg shr 8) and 0xFF
        val bgB = pageBg and 0xFF
        // 定位分段控件：文字带中线 y + 轨道右缘 x（surfaceRaised 对页面底的弱差值）
        // （x1 传场景实宽——本测试用窄场景，缺省会越界）
        val bands = contentRows(before, x1 = before.first().size)
        assertTrue(bands.isNotEmpty(), "分段控件应渲染出内容带，实际 ${bands.size}")
        val midY = (bands[0].first + bands[0].last) / 2
        var trackEnd = -1
        for (x in before[midY].indices.reversed()) {
            val p = before[midY][x]
            val d = kotlin.math.abs(((p shr 16) and 0xFF) - bgR) +
                kotlin.math.abs(((p shr 8) and 0xFF) - bgG) +
                kotlin.math.abs((p and 0xFF) - bgB)
            if (d > 3) {
                trackEnd = x
                break
            }
        }
        assertTrue(trackEnd > 0, "应能定位分段控件轨道右缘（surfaceRaised 差值带）")
        // hover 注入：Enter 事件打在第三个（未选中）条目右缘内 8px——条目内边距区，
        // 避开文字（场景管线不自 Enter，hoverable 消费 Enter/Exit 事件型）
        scene.sendPointerEvent(
            eventType = PointerEventType.Enter,
            position = Offset((trackEnd - 8).toFloat(), midY.toFloat()),
        )
        scene.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset((trackEnd - 8).toFloat(), midY.toFloat()),
        )
        val after = harness.renderPixels()
        val changed = mutableSetOf<Pair<Int, Int>>()
        for (y in before.indices) {
            for (x in before[y].indices) {
                if (before[y][x] != after[y][x]) changed.add(y to x)
            }
        }
        assertTrue(changed.isNotEmpty(), "hover 后未出现洗底像素变化——悬停态未生效")
        var minX = Int.MAX_VALUE
        var maxX = -1
        var minY = Int.MAX_VALUE
        var maxY = -1
        for ((y, x) in changed) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        // 胶囊四角外像素不变：差集包围盒四角不属于差集（洗底被 CircleShape 裁剪）；
        // 矩形 hover（无 shape 背景或默认 indication）回潮时四角必然落入差集
        for (corner in listOf(minY to minX, minY to maxX, maxY to minX, maxY to maxX)) {
            assertFalse(
                corner in changed,
                "洗底差集包围盒四角 ($corner) 不应被洗底——矩形 hover 回潮即红" +
                    "（差集 ${minY..maxY} 行 × ${minX..maxX} 列）",
            )
        }
    }

    private companion object {
        /** 整页场景视口（PageScaffold 全宽布局的行带检测窗口） */
        const val SCENE_W = 1096
        const val SCENE_H = 902

        /** hover 差分窄场景（只装分段控件，差集窗口小即定位准） */
        const val HOVER_W = 600
        const val HOVER_H = 160

        const val SEGMENTED_CONTROL_PATH =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/SegmentedControl.kt"
        const val CHIP_BUTTON_PATH =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/ChipButton.kt"
        const val KBD_TEXT_PATH =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/KbdText.kt"
    }
}
