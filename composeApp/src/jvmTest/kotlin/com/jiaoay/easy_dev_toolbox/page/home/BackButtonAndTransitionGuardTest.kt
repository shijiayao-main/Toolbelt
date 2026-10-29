package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 返回钮退役 + 切页动画移除的扫盘守护（维护者真机反馈批 2026-09-23 · 反馈 2/5）。
 * 扫盘型测试自检纪律（ScanSurfaceSelfCheckTest C 组）：自带扫描面规模下限。
 *
 * ## 反馈 2 · 返回钮全清零
 * - LocalAppCenterBack ambient 文件已删（原 :core/navigation/LocalAppCenterBack.kt）；
 * - PageHeader 渲染分支与 PageHomeContent provide 链同批删除；
 * - 🔴 死代码清退防回潮：批 4h 的注入链是「ambient + Local 读」两件套，回潮任意
 *   一件都会复活血钮。本守护钉死三处零残留：符号名、ARROW_BACK in PageHeader、
 *   「返回应用中心」contentDescription。
 *
 * ## 反馈 5 · 切页动画移除
 * - PageHomeContent 的 AnimatedContent 方向感知过渡与 navPageOrder 顺序表退役
 *   （currentPage 变化直接重组渲染）；
 * - 🔴 范围钉死：仅「切页内容过渡」退役。App.kt 的启动 splash fadeIn（Wave 2
 *   唯一允许的入场动画）与 palette 弹出/tooltip 动效**不在**退役面——本守护
 *   断言切页分发面零 AnimatedContent，同时放行 splash。
 */
class BackButtonAndTransitionGuardTest {

    // ── 反馈 2 · LocalAppCenterBack 全清零 ──

    @Test
    fun `LocalAppCenterBack ambient is fully retired`() {
        val guardSelf = File(
            root,
            "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/BackButtonAndTransitionGuardTest.kt",
        )
        val ktFiles = allKtFiles().filterNot { it.canonicalPath == guardSelf.canonicalPath }
        // 归一化（LarkSdkIsolationTest 同款配方）剥注释/字符串字面量——KDoc 里的
        // 退役历史记录（~~LocalAppCenterBack provide 链~~）不算回潮引用。
        val offenders = ktFiles.filter { normalizeKotlinSource(it.readText()).contains("LocalAppCenterBack") }
        assertEquals(
            emptyList(),
            offenders.map { it.relativeTo(root).path },
            "LocalAppCenterBack 已随反馈 2 退役（ambient 文件 + provide 链 + PageHeader 读取" +
                "三处同批删除）——以上文件归一化后仍引用该符号，回潮即本断言红。修复：删引用或恢复退役决议。",
        )
    }

    @Test
    fun `PageHeader has no back button and no stale ARROW_BACK`() {
        val pageHeader = File(root, "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/PageHeader.kt")
        assertTrue(pageHeader.isFile, "PageHeader.kt 缺失——路径漂移须同步更新本测试")
        val text = pageHeader.readText()
        assertFalse(
            "ARROW_BACK" in text,
            "PageHeader 不得再渲染返回钮图标（返回钮已退役，标题栏只余标题 + actions）",
        )
        assertFalse(
            "返回应用中心" in text,
            "PageHeader 不得残留「返回应用中心」contentDescription",
        )
        assertFalse(
            "IconButton" in text,
            "PageHeader 不得残留返回钮载体（IconButton import/调用一并清退）",
        )
    }

    // ── SYS-15 · 调试页返回钮退役（重设计批 6 · 单开系统页导航对齐）──

    @Test
    fun `ShellDebugPage has no back button and backFromDebug chain is fully retired`() {
        val shellDebugPage = File(
            root,
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/debug/ShellDebugPage.kt",
        )
        assertTrue(shellDebugPage.isFile, "ShellDebugPage.kt 缺失——路径漂移须同步更新本测试")
        // 归一化后匹配：KDoc/注释里的退役历史记录不算回潮引用
        val text = normalizeKotlinSource(shellDebugPage.readText())
        assertFalse(
            "ARROW_BACK" in text,
            "ShellDebugPage 不得再渲染返回钮图标（SYS-15 退役——单开系统页 dock/palette 切走即离开）",
        )
        assertFalse(
            "onBack" in text,
            "ShellDebugPage 不得残留 onBack 参数/回调（SYS-15 返回链注入点已整体拆除）",
        )

        // backFromDebug 调用链全域零残留（含 AppViewModel 定义点与 ToolPageRegistry 注入点；
        // 测试自身豁免——本断言的字符串字面量会被归一化剥除，须排除守卫文件自身）
        val guardSelf = File(
            root,
            "composeApp/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/page/home/BackButtonAndTransitionGuardTest.kt",
        )
        val offenders = allKtFiles().filterNot { it.canonicalPath == guardSelf.canonicalPath }
            .filter { normalizeKotlinSource(it.readText()).contains("backFromDebug") }
        assertEquals(
            emptyList(),
            offenders.map { it.relativeTo(root).path },
            "backFromDebug 已随 SYS-15 退役（页头返回钮 + ToolPageRegistry 注入 + AppViewModel" +
                " 方法/previousPage 字段/setCurrentPage Debug 特判全链删除）——以上文件归一化后" +
                "仍引用该符号，回潮即本断言红。修复：删引用或恢复退役决议。",
        )
    }

    // ── SYS-10 · 调试页模式切换 SegmentedControl 化（重设计批 6 · Switch 退役）──

    @Test
    fun `ShellDebugPage mode switch uses SegmentedControl and no Switch`() {
        val shellDebugPage = File(
            root,
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/debug/ShellDebugPage.kt",
        )
        assertTrue(shellDebugPage.isFile, "ShellDebugPage.kt 缺失——路径漂移须同步更新本测试")
        val text = normalizeKotlinSource(shellDebugPage.readText())
        assertFalse(
            "Switch(" in text,
            "ShellDebugPage 不得残留 Switch 控件（SYS-10：单次/保持会话是二选一互斥语义，" +
                "开/关设置态控件语义错位——模式切换已迁 SegmentedControl 双段胶囊）",
        )
        assertTrue(
            "SegmentedControl(" in text,
            "ShellDebugPage 模式切换必须走 SegmentedControl（options=单次执行/保持会话，" +
                "onSelect 经 index→枚举映射调 switchMode）",
        )
        // 选项文案是字符串字面量——归一化会剥内容，用原始文本断言
        val raw = shellDebugPage.readText()
        assertTrue(
            "单次执行" in raw && "保持会话" in raw,
            "SegmentedControl 选项文案（单次执行/保持会话）必须在——页头文字对随 Switch 迁 content",
        )
    }

    @Test
    fun `page dispatch has no page transition animation`() {
        val pageHomeContent = File(
            root,
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PageHomeContent.kt",
        )
        assertTrue(pageHomeContent.isFile, "PageHomeContent.kt 缺失——路径漂移须同步更新本测试")
        // 归一化后匹配：KDoc 里的退役历史记录（~~切页动画~~）不算残留
        val text = normalizeKotlinSource(pageHomeContent.readText())
        // 切页内容过渡面零残留：AnimatedContent / togetherWith / slide / 方向表
        listOf("AnimatedContent", "togetherWith", "slideIn", "slideOut", "navPageOrder").forEach { token ->
            assertFalse(
                token in text,
                "PageHomeContent 残留切页动画痕迹「$token」——反馈 5 移除的是全部过渡（即时切换）",
            )
        }
        // 分发面仍在（退役的是过渡不是分发本身）
        assertTrue(
            "registrations.firstOrNull" in text,
            "注册表分发逻辑必须在（退役的只是过渡动画）",
        )
    }

    @Test
    fun `splash entrance animation is out of retirement scope`() {
        // 范围钉死（反馈 5 只删切页过渡）：App.kt 启动 splash 的 fadeIn 入场保留
        val app = File(root, "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/App.kt")
        assertTrue(app.isFile, "App.kt 缺失——路径漂移须同步更新本测试")
        val text = app.readText()
        assertTrue(
            "启动态入场淡入" in text && "fadeIn(animationSpec = AppMotion.standard())" in text,
            "App.kt 启动 splash 入场淡入（Wave 2 唯一允许的动画）必须在——切页动画退役不得误伤",
        )
    }

    @Test
    fun `window title word order is toolbelt first with em dash`() {
        // implements GLB-11（终验蜂群修复批 7.5 · 2026-10-02 维护者拍板）：窗口标题
        // 词序归一——「Toolbelt — 页名」品牌先行 + em dash（原
        // 「页名 - Toolbelt」退役），分隔符 em dash；页名恒 ToolPageLabels 单源。
        // 词序/分隔符回潮即红（字符串字面量断言，不经归一化）。
        val app = File(root, "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/App.kt")
        assertTrue(app.isFile, "App.kt 缺失——路径漂移须同步更新本测试")
        val text = app.readText()
        // 收窄到 windowTitle 赋值块（全文件口径会误伤注释里的旧词序历史记录）
        val titleBlock = text.substringAfter("val windowTitle = remember")
            .substringBefore("LaunchedEffect(windowTitle")
        assertTrue(
            "\"Toolbelt — \$it\"" in titleBlock,
            "窗口标题必须为「Toolbelt — 页名」（GLB-11：品牌先行 + em dash，与 shell.html " +
                "titlebar 同构；页名 ToolPageLabels 单源不变）——旧词序「页名 - Toolbelt」回潮即红。",
        )
        assertFalse(
            "Toolbelt\"" in titleBlock && "\"Toolbelt\"" in titleBlock && " - " in titleBlock,
            "windowTitle 赋值块不得出现旧词序「页名 - Toolbelt」拼接（GLB-11：词序已归一品牌先行）。",
        )
        assertTrue(
            "ToolPageLabels.label(currentPage)" in text,
            "窗题页名必须经 ToolPageLabels 单源取（R1 命名单源契约不变——GLB-11 只改词序不改源）。",
        )
    }

    // ── 扫描面自检（规模下限——防扫描根丢失导致的永绿空转）──

    @Test
    fun `scan surface self check`() {
        val ktFiles = allKtFiles()
        assertTrue(
            ktFiles.size >= MIN_KT_FILES,
            "扫描面规模异常（${ktFiles.size} < $MIN_KT_FILES）——扫描根可能丢失，本守护在空转",
        )
    }

    /** 自项目根遍历全部 `.kt`——LarkSdkIsolationTest.walkKotlinFiles 同款配方：
     *  onEnter 按名剪枝（build 产物不进树）+ openspec 归档区/docs 排除（历史记录
     *  按当时名称保留，不算回潮面）。 */
    private fun allKtFiles(): List<File> = root.walkTopDown()
        .onEnter { dir ->
            dir.name !in EXCLUDED_DIR_NAMES &&
                dir.relativeTo(root).path.let { path ->
                    !path.startsWith("openspec") && !path.startsWith("docs")
                }
        }
        .filter { it.isFile && it.extension == "kt" }
        .toList()


    /**
     * 源码轻量归一化（LarkSdkIsolationTest 同款配方，逐字对齐）：去掉行注释、
     * 块注释内容（Kotlin 块注释可嵌套，按深度配对）与字符串字面量内容（普通串
     * 处理 `\"` 转义、原始串 `"""` 整段跳），保留首尾引号与换行。
     * 用途：KDoc 里的退役历史记录（~~LocalAppCenterBack~~ / ~~切页动画~~）不算回潮引用。
     */
    private fun normalizeKotlinSource(source: String): String = buildString {
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
            when {
                // 行注释：跳到行尾（换行由后续迭代原样保留）
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                // 块注释：丢弃内容保留内部换行（按深度配对——本文件 KDoc 会提及符号示例）
                c == '/' && next == '*' -> {
                    i += 2
                    var depth = 1
                    while (i < source.length && depth > 0) {
                        when {
                            source[i] == '/' && i + 1 < source.length && source[i + 1] == '*' -> { depth++; i += 2 }
                            source[i] == '*' && i + 1 < source.length && source[i + 1] == '/' -> { depth--; i += 2 }
                            else -> { if (source[i] == '\n') append('\n'); i++ }
                        }
                    }
                }
                // 原始字符串 """..."""：保留首尾三引号，丢弃内容，保留内部换行
                c == '"' && next == '"' && i + 2 < source.length && source[i + 2] == '"' -> {
                    append("\"\"\"")
                    i += 3
                    while (i < source.length) {
                        if (source[i] == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"') {
                            append("\"\"\""); i += 3; break
                        }
                        if (source[i] == '\n') append('\n')
                        i++
                    }
                }
                // 普通字符串 "..."：保留首尾引号，丢弃内容；处理 \ 转义；遇换行保守回退代码态
                c == '"' -> {
                    append('"'); i++
                    while (i < source.length) {
                        val sc = source[i]
                        if (sc == '\\' && i + 1 < source.length) { i += 2; continue }
                        if (sc == '"') { append('"'); i++; break }
                        if (sc == '\n') { append('\n'); i++; break }
                        i++
                    }
                }
                else -> { append(c); i++ }
            }
        }
    }

    private val root: File by lazy { locateProjectRoot() }

    private companion object {
        /** 全仓 .kt 规模下限（实测 ~760；对齐仓例 LarkSdkIsolationTest 的宽余量口径——
         *  下限只防「扫描根丢失永绿空转」，不追精确数） */
        const val MIN_KT_FILES = 400

        /** 扫盘剪枝目录（LarkSdkIsolationTest.EXCLUDED_DIR_NAMES 同款） */
        val EXCLUDED_DIR_NAMES = setOf(
            "build", ".gradle", ".kotlin", ".clikt_tmp", ".review_tmp", ".idea", "node_modules", ".git",
        )
    }
}
