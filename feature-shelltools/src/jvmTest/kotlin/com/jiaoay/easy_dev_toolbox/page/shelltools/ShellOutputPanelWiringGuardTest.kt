package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import androidx.compose.ui.unit.dp

/**
 * SH-01 · 重设计批 1 源码锚守护（2026-09-30）：手动输入栏接回统一执行链。
 *
 * 修复前 ShellOutputPanelContent 的 ShellInputBar.onSubmit 内联直连
 * ShellToolsRuntime.engine.submit（写历史 + 提交两段均在面板内自拼），
 * 绕过 VM 的 rewriteCommands——选定设备后手动输入 `adb devices -l`
 * 不注入 `-s <serial>`，与 docs「-s 串号注入」节宣称的「所有提交路径」
 * 相矛盾，且 ShellToolsViewModel.submitManualCommand 全仓零调用成死代码。
 *
 * 本测试扫源码钉死接回后的接线形态（行为由 submitManualCommand 链路
 * 既有单测与 rewriteCommands 覆盖，此处只防 UI 装配面再脱钩）：
 * 1. [ShellOutputPanel.kt][panelFile] 不再直接 import QueuedCommand /
 *    ShellExecutionEngine / ShellHistoryItem（引擎提交与历史写入收敛进 VM
 *    ——面板内出现这些类型即说明绕过统一执行链的内联块回来了）；
 * 2. ShellInputBar 提交回调指向 VM.submitManualCommand；
 * 3. ShellToolsPage 装配点把 viewModel 传入 ShellOutputPanelContent
 *    （漏传即编译红，本断言防的是将来再被改回无参重载的口子）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellOutputPanelWiringGuardTest {

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun shelltoolsFile(relativePath: String): File {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/$relativePath",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file
    }

    /** 剥除 // 行注释与 /* */ 块注释（含 KDoc），字符串内容保留——见 UiArchitectureGuardTest 同款语义说明 */
    private fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var inLine = false
        var inBlock = false
        var inString = false
        var inRawString = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inLine -> if (c == '\n') {
                    inLine = false
                    sb.append(c)
                }

                inBlock -> if (c == '*' && next == '/') {
                    inBlock = false
                    i++
                }

                inRawString -> when {
                    c == '"' && source.getOrNull(i + 1) == '"' && source.getOrNull(i + 2) == '"' -> {
                        sb.append("\"\"\"")
                        i += 2
                        inRawString = false
                    }

                    else -> sb.append(c)
                }

                inString -> when {
                    c == '\\' && next != null -> {
                        sb.append(c).append(next)
                        i++
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = false
                    }

                    else -> sb.append(c)
                }

                else -> when {
                    c == '/' && next == '/' -> {
                        inLine = true
                        i++
                    }

                    c == '/' && next == '*' -> {
                        inBlock = true
                        i++
                    }

                    c == '"' && source.getOrNull(i + 1) == '"' && source.getOrNull(i + 2) == '"' -> {
                        sb.append("\"\"\"")
                        i += 2
                        inRawString = true
                    }

                    c == '"' -> {
                        sb.append(c)
                        inString = true
                    }

                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    /** 面板文件剥注释后按行（行号与原文件对齐，报错可定位） */
    private fun codeLines(file: File): List<String> = stripComments(file.readText()).split('\n')

    @Test
    fun `panel no longer bypasses engine with inline submit block`() {
        val lines = codeLines(shelltoolsFile("output/ShellOutputPanel.kt"))
        val banned = listOf(
            "QueuedCommand" to "引擎任务构造须收敛 VM.submitManualCommand（直连 submit = 绕过 rewriteCommands，选定设备不注入 -s）",
            "ShellExecutionEngine" to "引擎类型不得出现在面板（提交路径唯一入口 = VM）",
            "ShellHistoryItem" to "历史写入须收敛 VM.submitManualCommand（面板自拼 history.add = 双源写历史）",
        )
        val violations = banned.flatMap { (symbol, why) ->
            lines.mapIndexedNotNull { index, line ->
                if (Regex("""\b$symbol\b""").containsMatchIn(line)) {
                    "output/ShellOutputPanel.kt:${index + 1} 命中 $symbol —— $why"
                } else {
                    null
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("SH-01 守护：ShellOutputPanel.kt 出现引擎/历史直连类型（统一执行链被绕过）：")
                    violations.forEach { appendLine("  - $it") }
                },
            )
        }
    }

    @Test
    fun `input bar submit routes through view model submitManualCommand`() {
        val lines = codeLines(shelltoolsFile("output/ShellOutputPanel.kt"))
        assertTrue(
            lines.any { it.contains("viewModel::submitManualCommand") },
            "SH-01 守护：ShellInputBar 提交回调必须指向 viewModel::submitManualCommand" +
                "（当前 ShellOutputPanel.kt 无该锚——内联直连块疑似回归）",
        )
    }

    @Test
    fun `page assembles panel content with view model`() {
        val lines = codeLines(shelltoolsFile("ShellToolsPage.kt"))
        // 装配点首行锚（maxHeight 参数在后续行——SH-17 扩参后单行断言不再成立）
        val assembly = lines.firstOrNull { it.contains("ShellOutputPanelContent(") && !it.trimStart().startsWith("*") && !it.contains("import") }
        assertEquals(
            "ShellOutputPanelContent(",
            assembly?.trim(),
            "SH-01 守护：ShellToolsPage 装配点必须传 viewModel（无参形态 = 面板拿不到 VM 执行链）",
        )
        // maxHeight 参数透传（SH-17：受控封顶）
        val assemblyBlock = lines.dropWhile { !it.contains("ShellOutputPanelContent(") }
            .takeWhile { !it.trim().startsWith("}") }
            .joinToString("\n")
        assertTrue(
            assemblyBlock.contains("viewModel = viewModel") &&
                assemblyBlock.contains("maxHeight = outputMaxHeight"),
            "SH-17 守护：装配点须传 viewModel + maxHeight = outputMaxHeight（缺一 = 受控高度链断）",
        )
    }

    // ── SH-17 · 重设计批 5（2026-10-01）：输出台高度可调（拖拽分隔条）源码锚守护 ──

    /**
     * SH-17 钳制纯函数直测：直调源码 consoleCapUpperBound（同包 internal）钉
     * 公式输出基线——复制式值级用例在测试内自算自证，对源公式结构性漂移
     * （如 space12 * 4 误改 * 2）全盲；直调后公式结构与出厂形态一次双钉。
     * 基线随 token 变值须同 commit 校准（DimensTest 同款纪律）。
     */
    @Test
    fun `drag clamp bounds output height between console min and tree floor`() {
        // 宽窗场景：800dp 可用高 → 上界恰等 consoleHeightMax 420dp
        //（拖拽可拉满默认封顶，钳制不咬合）
        assertEquals(
            Dimens.consoleHeightMax,
            consoleCapUpperBound(800.dp),
            "SH-17：宽窗 800dp 上界应=consoleHeightMax 420dp（token/公式漂移即红，须同 commit 校准）",
        )

        // 默认窗实测场景：1280×860 默认窗内容列可用高 633px → 上界 253dp
        //（像素级咬合：封顶含台内纵向 padding，树区实得 = 硬底 320 + padding 余量 24 = 344）
        val defaultWindowBound = consoleCapUpperBound(633.dp)
        assertEquals(
            253.dp,
            defaultWindowBound,
            "SH-17：默认窗 633dp 可用高上界应=253dp（=633−树硬底320−间隙48−分隔条12；封顶含台内边距，台面实高 ≤ 封顶）",
        )
        assertTrue(
            defaultWindowBound > Dimens.consoleHeightMin,
            "SH-17：默认窗上界须高于下限（非死区；真死区阈值=可用高 620dp）",
        )

        // coerceIn 行为断言：拖拽越界钳回两端
        val clampedLow = (Dimens.consoleHeightMin - 100.dp)
            .coerceIn(Dimens.consoleHeightMin, defaultWindowBound)
        assertEquals(
            Dimens.consoleHeightMin,
            clampedLow,
            "SH-17：向下拖越过下限必须钳回 consoleHeightMin 240dp",
        )
        val overDragged = (Dimens.consoleHeightMax + 100.dp)
            .coerceIn(Dimens.consoleHeightMin, defaultWindowBound)
        assertEquals(
            defaultWindowBound,
            overDragged,
            "SH-17：向上拖越界必须钳到可用高上界（树区保底 shellTreeHeightMin 不被饿死）",
        )
    }

    /**
     * SH-17 装配面源码锚（扫盘防回归；行为公式直测见
     * [drag clamp bounds output height between console min and tree floor]）：
     * 1. ShellOutputPanelContent 签名含 maxHeight 参数（受控封顶口子）；
     * 2. heightIn 消费 maxHeight 而非 consoleHeightMax 字面（固定封顶回归即红）；
     * 3. ShellToolsPage 持有 rememberSaveable 活状态（会话级记忆）；
     * 4. OutputHeightDivider 装配 + 钳制消费 consoleHeightMin/shellTreeHeightMin；
     * 5. 树区 heightIn(min = shellTreeHeightMin) 硬底（S2 事故红线）；
     * 6. 不持久化：两文件零 DataStore/EasyDataManager/ConfigStore 写调用
     *    （新 key 即违反「重启回默认 420dp」acceptance）。
     */
    @Test
    fun `output max height drag clamp wiring`() {
        val panelLines = codeLines(shelltoolsFile("output/ShellOutputPanel.kt"))
        val pageLines = codeLines(shelltoolsFile("ShellToolsPage.kt"))
        val dividerLines = codeLines(shelltoolsFile("output/OutputHeightDivider.kt"))

        // 1. 签名含 maxHeight（joinToString 后跨行匹配）
        val panelText = panelLines.joinToString("\n")
        assertTrue(
            Regex("""fun ShellOutputPanelContent\([\s\S]*?maxHeight:\s*Dp""").containsMatchIn(panelText),
            "SH-17 守护：ShellOutputPanelContent 签名必须含 maxHeight: Dp 参数（受控封顶口子）",
        )

        // 2. heightIn 消费 maxHeight
        assertTrue(
            panelLines.any { it.contains("heightIn(max = maxHeight)") },
            "SH-17 守护：面板 heightIn 必须消费 maxHeight 参数（consoleHeightMax 字面 = 固定封顶回归）",
        )

        // 3. 封顶高度进程级持有（ShellToolsRuntime.consoleCapHeight）——切页保持、
        //    重启随进程回默认 consoleHeightMax 420dp（不持久化）；rememberSaveable
        //    形态在无 SaveableStateHolder 的即时切页下会随组合销毁丢值（切页失忆回归即红）
        val runtimeLines = codeLines(shelltoolsFile("ShellToolsRuntime.kt"))
        assertTrue(
            pageLines.any { it.contains("ShellToolsRuntime.consoleCapHeight.collectAsState()") },
            "SH-17 守护：ShellToolsPage 须从 ShellToolsRuntime.consoleCapHeight 收集封顶高度（进程级会话记忆）",
        )
        assertTrue(
            runtimeLines.any { it.contains("MutableStateFlow(Dimens.consoleHeightMax)") },
            "SH-17 守护：ShellToolsRuntime 须以进程级 MutableStateFlow 持有封顶（初始 consoleHeightMax，重启回默认，不持久化）",
        )

        // 4. 分隔条装配 + 双 token 钳制（装配与回调分两行——跨行块匹配）
        assertTrue(
            pageLines.any { it.contains("OutputHeightDivider(") },
            "SH-17 守护：ShellToolsPage 须装配 OutputHeightDivider",
        )
        val pageText = pageLines.joinToString("\n")
        assertTrue(
            Regex("""OutputHeightDivider\([\s\S]*?onDragDelta""").containsMatchIn(pageText),
            "SH-17 守护：OutputHeightDivider 装配须接 onDragDelta 回调",
        )
        val dragBlock = pageLines.dropWhile { !it.contains("OutputHeightDivider(") }
            .takeWhile { !it.contains("ShellOutputPanelContent(") }
            .joinToString("\n")
        assertTrue(
            dragBlock.contains("coerceIn(") &&
                dragBlock.contains("Dimens.consoleHeightMin") &&
                dragBlock.contains("consoleCapUpperBound"),
            "SH-17 守护：拖拽块必须 coerceIn 钳制、下限走 consoleHeightMin、上限走 consoleCapUpperBound 单源公式",
        )

        // 封顶上界公式单源：树硬底 + 两道间隙 + 分隔条命中区 + 输出台自身纵向
        // padding 全额扣除（少扣任一项，树区实得都会跌破硬底）
        assertTrue(
            pageLines.any { it.contains("internal fun consoleCapUpperBound") },
            "SH-17 守护：封顶上界公式须收敛为 consoleCapUpperBound 单源（internal 直测口，拖拽与初始收缩共用）",
        )
        val boundBlock = pageLines.dropWhile { !it.contains("fun consoleCapUpperBound") }
            .takeWhile { !it.contains("coerceAtLeast(Dimens.consoleHeightMin)") }
            .joinToString("\n")
        assertTrue(
            boundBlock.contains("Dimens.shellTreeHeightMin") &&
                boundBlock.contains("Dimens.splitterHitWidth") &&
                boundBlock.contains("Dimens.space12"),
            "SH-17 守护：consoleCapUpperBound 须全额扣除树硬底/分隔条命中区/间隙与输出台纵向 padding",
        )

        // 初始封顶随可用高单向收缩（小窗下固定子项挤破树硬底的防线）
        val onSizeChangedBlock = pageLines.dropWhile { !it.contains("onSizeChanged { size ->") }
            .take(10).joinToString("\n")
        assertTrue(
            onSizeChangedBlock.contains("consoleCapUpperBound"),
            "SH-17 守护：onSizeChanged 须按 consoleCapUpperBound 收缩初始封顶（小窗树区饿死防线）",
        )

        // 5. 树区硬底（S2 红线）
        assertTrue(
            pageLines.any { it.contains("heightIn(min = Dimens.shellTreeHeightMin)") },
            "SH-17 守护：ShellToolsListSection 须带 heightIn(min = shellTreeHeightMin) 树区硬底（S2 事故红线：树不可饿成 0）",
        )

        // 6. 不持久化断言：两源文件零持久层写调用（rememberSaveable 进程内记忆，非落盘）
        val persistenceSymbols = listOf(
            "ShellToolsDataStore",
            "EasyDataManager",
            "AppDataStore",
            "ConfigStore",
        )
        val violations = (pageLines + panelLines).flatMap { line ->
            persistenceSymbols.mapNotNull { symbol ->
                if (Regex("""\b$symbol\b""").containsMatchIn(line)) {
                    "「$symbol」出现在输出台高度路径 —— SH-17 明确不持久化（会话级记忆即可，避免死 key 复发 F-12）"
                } else {
                    null
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "SH-17 守护：输出台高度不得持久化（violations=${violations}）",
        )

        // 7. 分隔条本体形态锚：增量回调 + 无 clickable（J2 ripple 抖动配方）+ 双 splitter token
        assertTrue(
            dividerLines.any { it.contains("onDragDelta: (Dp) -> Unit") },
            "SH-17 守护：OutputHeightDivider 必须回传增量（Dp）——终值模式在 CMP 1.12 下像素跳变（J1 教训）",
        )
        assertTrue(
            dividerLines.none { Regex("""\.clickable\b""").containsMatchIn(it) },
            "SH-17 守护：OutputHeightDivider 不得用 .clickable（ripple 猝灭闪烁，J2 抖动批配方）",
        )
        assertTrue(
            dividerLines.any { it.contains("Dimens.splitterHitWidth") } &&
                dividerLines.any { it.contains("Dimens.splitterWidth") },
            "SH-17 守护：分隔条须消费 splitterHitWidth（命中区）/splitterWidth（视觉宽）token（Fitts）",
        )
    }

    @Test
    fun `clear output button is gated by hasOutput`() {
        // 清空空态禁用锚：输出是会话级内存窗口（不持久化）故不加 L2 确认，但空态
        // 必须禁用——「0 条」时恒可点且无任何反馈，与复制/导出门控不一致
        val lines = codeLines(shelltoolsFile("output/ShellOutputPanel.kt"))
        val clearIndex = lines.indexOfFirst {
            it.contains("onClick = onClear") && !it.contains("private fun")
        }
        assertTrue(clearIndex >= 0, "清空钮调用点锚失效——调用点漂移后请同步本守护")
        val block = lines.drop(maxOf(0, clearIndex - 4)).take(8).joinToString("\n")
        assertTrue(
            "enabled = hasOutput" in block,
            "SH-12 守护：清空钮必须 enabled = hasOutput（空态禁用——Idle 态「0 条」恒可点且无反馈）",
        )
    }

    /**
     * 输出台「随内容增长至封顶」装配面源码锚（维护者定音口径：短输出不占满封顶）：
     * 1. OutputList 调用点不带 weight——列内 weight 语义 min=max=分配空间，
     *    一行输出也会把面板钉满封顶（满额回归即红）；
     * 2. 列表区经 ListScrollbarLayout 承载——通用 VerticalScrollbarSlot 自带
     *    fillMaxHeight（min=max=传入最大高），在内容自适应容器里会把整体钉满
     *    可用高，「随内容增长」失效；该节点把滚动条高度钉到列表实测高。
     */
    @Test
    fun `output list grows with content instead of filling cap`() {
        val panelText = codeLines(shelltoolsFile("output/ShellOutputPanel.kt")).joinToString("\n")

        assertTrue(
            Regex("""OutputList\(\s*visibleItems = visibleItems,\s*modifier = Modifier\.fillMaxWidth\(\),""").containsMatchIn(panelText),
            "输出台列表调用点必须为 fillMaxWidth 无 weight 形态（weight = 一行输出钉满封顶回归）",
        )
        assertFalse(
            Regex("""OutputList\([\s\S]{0,160}?weight\(1f\)""").containsMatchIn(panelText),
            "输出台列表调用点不得出现 weight(1f)（列内 weight 语义 min=max=分配空间，随内容增长失效）",
        )
        assertTrue(
            panelText.contains("ListScrollbarLayout("),
            "输出台列表须由 ListScrollbarLayout 承载（滚动条高度钉到列表实测高，防 fillMaxHeight 钉满回潮）",
        )
    }
}
