package com.jiaoay.easy_dev_toolbox.page.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 设置页文案/形态卡簇源码锚守护（SYS-17/18/19/22/XA-09 · 重设计批 6 · 2026-10-01）。
 *
 * 卡簇按所在源文件分三源锚定（PageSettings.kt 装配段 / ThemeShowcase.kt 主题橱窗族 /
 * AdbPathSetting.kt ADB 检测卡）——源码锚配方与 McpExposePortConfirmTest / PillLabelTest
 * 同款（归一化文本断言，缺失即红，重命名/移动后须同步本守护）。
 *
 * 验收面 = 卡面 acceptance 可代码级断言项：
 * - SYS-17：label 恒定「记录到日志文件」（不随开关态翻转）+ UI 无反引号字面量 +
 *   路径 mono 行 + CopyIconButton 一键复制；
 * - SYS-18：ADB 检测结果收编 StatusBanner（Success/Error 两槽），行内裸彩字退役。
 * - SYS-19：预设分组双列网格 ThemeCard（非 Compact 档）+ Compact 按组单列行式回落。
 * - SYS-22：开发者选项文案对齐 dock 词汇 + 开启后直达行。
 * - XA-09：设置页检测钮 AppButton 化（busy 态行内圈 + 「…中」措辞 + 置灰）。
 * - SYS-01 双栏消费锚在 :ui 侧 SettingsTwoPaneTest（组件本体 + 设置页挂入验收）。
 */
class SettingsPageCopyGuardTest {

    // ── SYS-17：日志存储行文案修正 ──

    @Test
    fun `sys17 log label is constant and never flips with switch state`() {
        val text = pageSource()
        assertTrue(
            "label = \"记录到日志文件\"" in text,
            "SYS-17：日志存储行 label 必须恒定「记录到日志文件」——状态由 switch 本身表达，" +
                "「开启/关闭」随态翻转是双重冗余。",
        )
        assertTrue(
            "label = if (enableWriteFile)" !in text,
            "SYS-17：label 不得随 enableWriteFile 翻转（旧配方回潮即红）。",
        )
    }

    @Test
    fun `sys17 no markdown backtick leaks into settings page ui strings`() {
        val text = pageSource()
        assertTrue(
            "desc = \"开启后在下方目录生成日志文件\"" in text,
            "SYS-17：desc 必须为「开启后在下方目录生成日志文件」（路径独立成行）。",
        )
        // 反引号字面量在 Kotlin 模板串经 \` 转义直出 UI——剥注释后的代码区不得再出现
        val code = stripComments(text)
        assertTrue(
            "\\`" !in code,
            "SYS-17：代码区不得出现反引号转义字面量（markdown 残留不得直出 UI）。",
        )
    }

    @Test
    fun `sys17 log directory path renders mono with copy affordance`() {
        val text = pageSource()
        assertTrue(
            "text = FileUtils.getLogDir().absolutePath," in text,
            "SYS-17：路径行必须直出 FileUtils.getLogDir().absolutePath（mono 数据行）。",
        )
        assertTrue(
            "style = DesktopTypography.mono" in text,
            "SYS-17：路径行字体必须 DesktopTypography.mono（路径/日志等数据密集场景等宽档）。",
        )
        val copyAnchor = text.indexOf("CopyIconButton(")
        assertTrue(copyAnchor >= 0, "SYS-17：日志目录必须挂 CopyIconButton（一键复制）。")
        val copyBlock = text.substring(copyAnchor, minOf(copyAnchor + 300, text.length))
        assertTrue(
            "textProvider = { FileUtils.getLogDir().absolutePath }" in copyBlock,
            "SYS-17：复制内容必须单源 FileUtils.getLogDir().absolutePath。",
        )
        assertTrue(
            "contentDescription = \"复制日志目录\"" in copyBlock,
            "SYS-17：CopyIconButton 无文字标签，contentDescription 必须「复制日志目录」。",
        )
    }

    // ── SYS-18：ADB 检测结果 StatusBanner 化 ──

    @Test
    fun `sys18 adb check result renders via status banner success error pair`() {
        val text = adbSource()
        val adbAnchor = text.indexOf("checkResult?.let { result ->")
        assertTrue(adbAnchor >= 0, "SYS-18：checkResult 渲染分支必须保留（检测结果对 UI 可见）。")
        val bannerBlock = text.substring(adbAnchor, minOf(adbAnchor + 400, text.length))
        assertTrue(
            "StatusBanner(" in bannerBlock,
            "SYS-18：ADB 检测结果必须经 StatusBanner 容器条呈现（AiSettingsPage 连通性探测同款形态）。",
        )
        assertTrue(
            "if (result.success) StatusBannerStatus.Success else StatusBannerStatus.Error" in bannerBlock,
            "SYS-18：success → Success / 失败 → Error 两槽映射（AdbCheckResult 字段直映射）。",
        )
        assertTrue(
            "text = result.message" in bannerBlock,
            "SYS-18：banner 文本必须单源 result.message。",
        )
    }

    @Test
    fun `sys18 inline colored text recipe is retired`() {
        val code = stripComments(adbSource())
        assertTrue(
            "toolboxColors.success" !in code,
            "SYS-18：行内彩字配方（toolboxColors.success 裸 Text）必须退役——" +
                "检测结果唯一渲染形态是 StatusBanner（旧配方回潮即红）。",
        )
        assertTrue(
            "LocalEasyToolboxColors" !in code,
            "SYS-18：局部 toolboxColors 变量已无消费点，import/局部变量须一并清退。",
        )
    }

    // ── SYS-19：配色预设分组双列网格 ThemeCard ──

    @Test
    fun `sys19 theme presets render as two column grid outside compact window`() {
        val text = pageSource()
        val theme = themeSource()
        // 分组数据源单源锚：UI 走 groupedPresets（组序/组内序 = AppThemeSpec.presets 列表序，
        // 防 UI 与核心清单漂移），不自持清单
        assertTrue(
            "AppThemeSpec.groupedPresets.forEach" in text,
            "SYS-19：预设区必须按 AppThemeSpec.groupedPresets 分组渲染（组序与核心清单同源）。",
        )
        assertTrue(
            "internal fun ThemeCard(" in theme,
            "SYS-19：ThemeCard 网格卡必须存在于主题橱窗族（ThemeShowcase.kt——三块构成样张+名称行竖排）。",
        )
        // 非 Compact 门控 + Compact 回落双分支（回落与 SYS-01 策略同源、独立成立）
        assertTrue(
            "currentWindowSizeClass() != WindowSizeClass.Compact" in text,
            "SYS-19：两列网格门控必须 currentWindowSizeClass() != WindowSizeClass.Compact" +
                "（Medium/Expanded/Wide 均网格，仅 Compact 单列回落）。",
        )
        // 网格分支：组循环内 chunked(2) 拆对 + ThemeCard 渲染
        val gridAnchor = text.indexOf("AppThemeSpec.groupedPresets.forEach")
        assertTrue(gridAnchor >= 0, "SYS-19：网格分支锚缺失。")
        val gridBlock = text.substring(gridAnchor, minOf(gridAnchor + 1600, text.length))
        assertTrue(
            "chunked(2)" in gridBlock,
            "SYS-19：组内预设必须 chunked(2) 两列拆对（孤行兜底配方组内化，不依赖总数奇偶）。",
        )
        assertTrue(
            "ThemeCard(" in gridBlock,
            "SYS-19：网格档组内预设必须经 ThemeCard 网格卡渲染。",
        )
        // Compact 回落分支独立断言（回落与网格分支分离）：按组重复单列行式
        val fallbackAnchor = text.indexOf("specs.forEach")
        assertTrue(fallbackAnchor > gridAnchor, "SYS-19：Compact 回落分支锚缺失（specs.forEach 按组单列）。")
        val fallbackBlock = text.substring(fallbackAnchor, minOf(fallbackAnchor + 600, text.length))
        assertTrue(
            "ThemeOptionItem(" in fallbackBlock,
            "SYS-19：Compact 档必须回落按组单列行式（ThemeOptionItem 复用）。",
        )
        assertTrue(
            "ThemeColorDots(spec)" in fallbackBlock,
            "SYS-19：回落行 leading 必须复用 ThemeColorDots(spec)（预览与实机同源）。",
        )
    }

    @Test
    fun `sys19 theme card keeps selection border recipe unchanged`() {
        val text = themeSource()
        // 选中态 border 实况 2dp（白名单值 "1"/"2" 双登记）——沿用零值改动，勿追卡面「1dp」勘误
        val cardAnchor = text.indexOf("internal fun ThemeCard(")
        assertTrue(cardAnchor >= 0, "SYS-19：ThemeCard 定义缺失。")
        val cardBlock = text.substring(cardAnchor, minOf(cardAnchor + 1400, text.length))
        assertTrue(
            "width = if (selected) 2.dp else 1.dp" in cardBlock,
            "SYS-19：选中态 border 必须沿用现 1/2dp 配方零值改动（选中 2dp primary）。",
        )
        assertTrue(
            "ThemeSpecimenBand(spec = spec)" in cardBlock,
            "SYS-19：ThemeCard 必须复用 ThemeSpecimenBand 三块构成样张" +
                "（底/第二声部/强调取声部色域——骨架段对白纸卡不可分，声部样张才承载第一触点信息量）。",
        )
    }

    @Test
    fun `sys19 theme specimen band derives from core specimen triad single source`() {
        val text = themeSource()
        val bandAnchor = text.indexOf("private fun ThemeSpecimenBand(")
        assertTrue(bandAnchor >= 0, "SYS-19：三块构成样张带 ThemeSpecimenBand 定义缺失。")
        val bandBlock = text.substring(bandAnchor, minOf(bandAnchor + 900, text.length))
        assertTrue(
            "spec.specimenTriad(dark = false)" in bandBlock &&
                "spec.specimenTriad(dark = true)" in bandBlock,
            "SYS-19：样张带取色必须单源 AppThemeSpec.specimenTriad 且亮/暗两态各取一次" +
                "（与实机 colorSet 同源——直读 seed 漏掉染色/覆写套实值；缺 dark 取色 = " +
                "pageBaseDark 主权套的暗态身份在橱窗零像素在场）。",
        )
        assertTrue(
            "ThemeSpecimenStrip(triad = lightTriad, pattern = lightPattern)" in bandBlock &&
                "ThemeSpecimenStrip(triad = darkTriad, pattern = darkPattern)" in bandBlock,
            "SYS-19：亮/暗两条样张都必须组合渲染（darkTriad 只算不画 = 双态样张退回单亮条）。",
        )
    }

    // ── 橱窗选中条高稳态：CHECK 恒渲染占位 ──

    /**
     * 选中条高稳态守护：ThemeCard/ThemeOptionItem 的 CHECK 必须恒渲染占位——未选中
     * Color.Transparent tint + contentDescription=null，选中才上色/上读屏语义。
     * 为什么：vicart Symbol 经 BasicText 渲染且无 lineHeight，行盒回落字体自然 metrics
     * 24sp（iconLarge 20sp×1.2em）；条件插入会令选中卡名称行 +6px，经 chunked(2) 网格
     * 行取 max 传导为选中行以下整体位移（ThemeOptionItem 同病灶 +2px）。
     * 验收判据：选中切换前后整页像素 diff 零纵向位移（人工/截图管线验收，本源码锚为
     * 机器可判面）。
     */
    @Test
    fun `theme check icon is an always rendered placeholder pinning glyph line box`() {
        val text = themeSource()
        // ThemeCard：恒渲染 + 未选中透明 tint + 无读屏语义（两态同占 CHECK 字形自然行盒）
        val cardAnchor = text.indexOf("internal fun ThemeCard(")
        assertTrue(cardAnchor >= 0, "CHECK 占位：ThemeCard 定义缺失。")
        val cardBlock = text.substring(cardAnchor, minOf(cardAnchor + 3000, text.length))
        assertTrue(
            "contentDescription = if (selected) \"已选\" else null" in cardBlock,
            "CHECK 占位：contentDescription 必须随态条件化（未选卡不得携带「已选」读屏语义）。",
        )
        assertTrue(
            "tint = if (selected) surfaceColors.onSelectedContainer else Color.Transparent" in cardBlock,
            "CHECK 占位：未选中必须 Color.Transparent 恒渲染占位（钉住 24sp 行盒，选中态走" +
                " onSelectedContainer 配套字链）。",
        )
        // ThemeOptionItem（Compact 单列回落 + 自定义项第二现场）同款恒渲染
        val itemAnchor = text.indexOf("internal fun ThemeOptionItem(")
        assertTrue(itemAnchor >= 0, "CHECK 占位：ThemeOptionItem 定义缺失。")
        val itemBlock = text.substring(itemAnchor, minOf(itemAnchor + 2000, text.length))
        assertTrue(
            "contentDescription = if (selected) \"已选\" else null" in itemBlock,
            "CHECK 占位：ThemeOptionItem 必须同款条件化 contentDescription。",
        )
        assertTrue(
            "tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent" in itemBlock,
            "CHECK 占位：ThemeOptionItem 未选中必须 Color.Transparent 恒渲染占位。",
        )
        // 条件插入回潮即红：if (selected) { 不得再包住 AppSymbolIcon（占位塌缩回条件
        // 渲染，行盒 24sp 消失 → 选中卡 +6px → 网格行高迁移）
        val flat = text.replace('\n', ' ')
        assertTrue(
            !Regex("""if \(selected\) \{\s*AppSymbolIcon""").containsMatchIn(flat),
            "CHECK 占位：if (selected) 不得包住 AppSymbolIcon（条件插入回潮 = 选中条高跳动回归）。",
        )
    }

    // ── SYS-22：开发者选项文案对齐 dock 词汇 + 直达行 ──

    @Test
    fun `sys22 developer option desc aligns with dock vocabulary`() {
        val text = pageSource()
        assertTrue(
            "desc = \"开启后在左侧 dock 系统区与命令面板显示 Shell 调试页\"" in text,
            "SYS-22：desc 必须对齐现状导航词汇（dock 系统区 + 命令面板）。",
        )
        // 「侧边导航」残留扫描剥注释（施工注释引述旧称谓说明勘误属合法；UI 字符串不得残留）
        val code = stripComments(text)
        assertTrue(
            "\"侧边导航" !in code,
            "SYS-22：「侧边导航」是已退役布局（R1 dock 化前）的残留称谓，UI 字符串不得再出现。",
        )
    }

    @Test
    fun `sys22 debug entry direct row wires to ToolPage Debug when enabled`() {
        val text = pageSource()
        assertTrue(
            "if (debugPageEnabled) {" in text,
            "SYS-22：直达行必须 debugPageEnabled 门控（开启开关后组内即时出现）。",
        )
        val rowAnchor = text.indexOf("\"前往 Shell 调试 →\"")
        assertTrue(rowAnchor >= 0, "SYS-22：直达行文案必须「前往 Shell 调试 →」。")
        val rowBlock = text.substring(maxOf(0, rowAnchor - 700), minOf(rowAnchor + 100, text.length))
        assertTrue(
            "appViewModel.setCurrentPage(ToolPage.Debug)" in rowBlock,
            "SYS-22：直达行点击必须 setCurrentPage(ToolPage.Debug)（PageHomeMenu dock 入口同款调用）。",
        )
        // HoverableRow 无 onClick 参数（纯 hover 壳）——行点击经 modifier.clickable 组装
        assertTrue(
            ".clickable { appViewModel.setCurrentPage(ToolPage.Debug) }" in rowBlock,
            "SYS-22：行点击必须 modifier 内 clickable（ThemeOptionItem 同配方，卡面「HoverableRow onClick=」不可直译）。",
        )
    }

    // ── XA-09（设置侧）：检测钮 AppButton 化 ──

    @Test
    fun `xa09 adb check button uses AppButton busy recipe`() {
        val text = adbSource()
        val btnAnchor = text.indexOf("label = \"检测可用性\",")
        assertTrue(btnAnchor >= 0, "XA-09：检测钮 label 必须保留「检测可用性」常态措辞。")
        val btnBlock = text.substring(maxOf(0, btnAnchor - 200), minOf(btnAnchor + 400, text.length))
        assertTrue(
            "AppButton(" in btnBlock,
            "XA-09：检测钮必须 AppButton 化（§4.7h：100ms–1s 忙态唯一形态表）。",
        )
        assertTrue(
            "busy = checking" in btnBlock,
            "XA-09：busy 必须接 checking 态（busy 自动行内 progressInline 圈 + 置灰）。",
        )
        assertTrue(
            "busyLabel = \"检测中…\"" in btnBlock,
            "XA-09：进行时措辞必须「检测中…」（单字符省略号——文案修正面）。",
        )
        assertTrue(
            "kind = AppButtonKind.Secondary" in btnBlock,
            "XA-09：kind 必须 Secondary（描边形态对齐原 OutlinedButton 视觉）。",
        )
        // 旧「检测中...」三点字面量回潮即红（本卡就是文案修正卡）
        assertTrue(
            "\"检测中...\"" !in text,
            "XA-09：旧「检测中...」三点写法必须退役（省略号单字符化）。",
        )
    }

    // ── 源码锚工具（缺失即红——重命名/移动后须同步本守护） ──

    private fun pageSource(): String = anchorSource(PAGE_REL_PATH)

    /** 主题橱窗族源（ThemeShowcase.kt——ThemeCard/ThemeOptionItem/样张条带定义所在） */
    private fun themeSource(): String = anchorSource(THEME_REL_PATH)

    /** ADB 检测卡源（AdbPathSetting.kt——检测钮/结果反馈所在） */
    private fun adbSource(): String = anchorSource(ADB_REL_PATH)

    private fun anchorSource(relPath: String): String {
        val file = File(locateProjectRoot(), relPath)
        assertTrue(file.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return file.readText()
    }

    /** 剥 KDoc/行注释/块注释（字符串内容保留——本断言面只关心 API 调用与字面量） */
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
        error("无法定位项目根（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val PAGE_REL_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/settings/PageSettings.kt"
        const val THEME_REL_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/settings/ThemeShowcase.kt"
        const val ADB_REL_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/settings/AdbPathSetting.kt"
    }
}
