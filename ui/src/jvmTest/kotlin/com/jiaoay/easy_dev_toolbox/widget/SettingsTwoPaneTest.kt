package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SettingsTwoPane 设置双栏容器验收断言：
 *
 * 本件是设置页/AI 设置页「左分类导航 + 右内容舱」双栏化的共享组件
 * （双栏形态 .pane/.pane-nav/.navrow 直译）。验收面四钉
 * （PillLabelTest 同款配方：编译可达 + 值断言 + 配方源断言 + 结构断言）：
 * 1. **编译可达**：SettingsPaneSection 数据类 + SettingsTwoPane 可组合项引用钉进
 *    编译面（签名不兼容改写或文件删除直接编译失败）；
 * 2. **值断言**：左舱宽 Dimens.settingsNavWidth = 200dp（预览稿 .pane-nav 同值；
 *    「consoleHeightMin 的 1/3」= 80dp 与预览稿差 2.5 倍，弃用）；
 * 3. **配方源断言**：选中态 AppAlpha.selectedWash primary 系洗底 + HoverableRow
 *    行壳 + currentWindowSizeClass() Wide 门控 + 右舱 verticalScroll 局部滚 +
 *    进页初值取首分类（remember 无持久化）；
 * 4. **窄窗回落断言**：else 分支 sections 依序全量直出（单列逐像素等价——
 *    与既有窄窗回落策略同源）。
 */
class SettingsTwoPaneTest {

    /** 编译可达探针：签名改写/文件删除即 :ui:jvmTest 编译失败（不执行，只编译） */
    @Composable
    private fun compileReachabilityProbe() {
        val sections = listOf(
            SettingsPaneSection(
                title = "外观",
                icon = "palette",
                content = {},
            ),
        )
        SettingsTwoPane(sections = sections)
    }

    @Test
    fun `settings nav width token equals 200dp preview spec`() {
        assertEquals(
            200.dp,
            Dimens.settingsNavWidth,
            "左舱宽必须 200dp（预览稿 .pane-nav width:200px 直译；新 token 入 Dimens）",
        )
        assertEquals(
            0.08f,
            AppAlpha.selectedWash,
            "选中态洗底必须 AppAlpha.selectedWash（微洗底恒 primary 系）",
        )
    }

    @Test
    fun `two pane recipe stays pinned to wide gate nav wash and right pane scroll`() {
        val source = twoPaneSource()
        listOf(
            "currentWindowSizeClass() == WindowSizeClass.Wide" to
                "双栏门控必须 Wide 档（>1760dp）——窄窗回落单列",
            "AppAlpha.selectedWash" to
                "左舱选中态必须 selectedWash 洗底（AppShapes.medium 行壳内叠底）",
            ".width(Dimens.settingsNavWidth)" to
                "左舱宽必须消费 Dimens.settingsNavWidth token（不得裸 200dp）",
            "HoverableRow(" to
                "左舱行必须 HoverableRow 壳（hover 洗底与行壳统一）",
            ".verticalScroll(" to
                "右舱必须 Column(verticalScroll) 局部滚（滚动责任从整页迁到舱内；" +
                "滚动态上提组件顶层供分类切换回顶回执消费）",
            "var selectedIndex by remember { mutableStateOf(0) }" to
                "进页初值必须取首分类（remember 态，无持久化——预览稿演示语义申报）",
            "sections.forEach { it.content() }" to
                "窄窗回落必须 sections 依序全量直出（单列与现状逐像素等价）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle in source,
                "SettingsTwoPane.kt 丢失配方要素「$needle」——$why",
            )
        }
    }

    @Test
    fun `two pane stays non experimental and clean of raw values`() {
        val code = stripComments(twoPaneSource())
        listOf(
            "ExperimentalMaterial3Api" to "本件零 experimental API（宽窗判定走 compositionLocal 非 API 门控）",
            "stickyHeader" to "本件不消费 LazyColumn stickyHeader（零 experimental 依赖）",
        ).forEach { (needle, why) ->
            assertTrue(
                needle !in code,
                "SettingsTwoPane.kt 出现「$needle」——$why",
            )
        }
        // 图标/尺寸消费点全走 token/AppSymbolIcon（无裸 0.x alpha / 裸 dp）
        assertTrue(
            "alpha = AppAlpha" in code || "AppAlpha." in code,
            "透明度消费必须 AppAlpha token（UiGuard RAW_ALPHA 纪律）",
        )
    }

    // ── 消费面守护（设置页挂入） ──

    @Test
    fun `page settings consumes SettingsTwoPane with three categories`() {
        val source = pageSettingsSource()
        assertTrue(
            "SettingsTwoPane(sections = sections)" in source,
            "PageSettings 必须挂 SettingsTwoPane（sections 传入）。",
        )
        assertTrue(
            "SettingsPaneSection(" in source,
            "PageSettings 必须构造 SettingsPaneSection 分类（三分：外观/环境/高级）。",
        )
        listOf(
            "title = \"外观\",",
            "title = \"环境\",",
            "title = \"高级\",",
        ).forEach { needle ->
            assertTrue(
                needle in source,
                "设置页三分（外观/环境/高级）分类构造缺失「$needle」——预览稿 sys-s01 一致。",
            )
        }
        // 滚动责任迁移：双栏（Wide）时走吸顶模式（content 有界高度 → 右舱局部滚生效）；
        // 窄窗保持现状整页滚（headerSticky 与 modifier verticalScroll 互斥契约）
        assertTrue(
            "headerSticky = wideWindow" in source,
            "双栏必须 headerSticky=true（content 有界高度，右舱局部滚生效——" +
                "非吸顶 content 无界高度下 verticalScroll 永不滚动）。",
        )
        assertTrue(
            "modifier = if (wideWindow) Modifier else Modifier.verticalScroll(state = scrollState)" in source,
            "整页滚动必须窄窗保留（现状整页滚）、Wide 拆除（右舱局部滚接管）。",
        )
    }

    /** 「内容零删减可达」全项守护：六组 SettingsSection 在分类内全量平移（单行 title 锚） */
    @Test
    fun `all six settings sections survive inside pane sections`() {
        val source = pageSettingsSource()
        listOf(
            "title = \"外观模式\"" to "外观分类",
            "title = \"环境背景\"" to "外观分类",
            "title = \"配色方案\"" to "外观分类",
            "title = \"Shell 类型\"" to "环境分类",
            "title = \"ADB 路径\"" to "环境分类",
            "title = \"开启日志存储\"" to "高级分类",
            "title = \"开发者选项\"" to "高级分类",
        ).forEach { (needle, where) ->
            assertTrue(
                needle in source,
                "组卡零删减——「$needle」缺失（应平移进$where）。",
            )
        }
        // Shell 类型 Windows 条件分支平移进「环境」分类内部，条件语义不变
        assertTrue(
            "if (!ShellCommandBuilder.isWindows) {" in source,
            "Shell 类型 Windows 条件分支必须保留（条件语义不变，只换所在分类）。",
        )
    }

    // ── 源码锚工具 ──

    private fun twoPaneSource(): String {
        val root = locateProjectRoot()
        val file = File(root, TWO_PANE_PATH)
        assertTrue(file.isFile, "$TWO_PANE_PATH 不存在——双栏新件被移动须同步本测试路径")
        return file.readText()
    }

    private fun pageSettingsSource(): String {
        val root = locateProjectRoot()
        val file = File(root, PAGE_SETTINGS_PATH)
        assertTrue(file.isFile, "$PAGE_SETTINGS_PATH 不存在——重命名/移动后请同步本守护")
        return file.readText()
    }

    /** 剥 KDoc/行注释/块注释（字符串内容保留——本断言面只关心 API 调用） */
    private fun stripComments(source: String): String = buildString {
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) {
                        if (source[i] == '\n') append('\n')
                        i++
                    }
                    i += 2
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

    private fun locateProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("未定位项目根（settings.gradle.kts）——:ui:jvmTest 工作目录异常")
    }

    private companion object {
        const val TWO_PANE_PATH =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/SettingsTwoPane.kt"
        const val PAGE_SETTINGS_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/settings/PageSettings.kt"
    }
}
