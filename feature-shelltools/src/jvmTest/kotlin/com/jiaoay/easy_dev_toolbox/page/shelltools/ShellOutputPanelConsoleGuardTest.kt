package com.jiaoay.easy_dev_toolbox.page.shelltools

import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import androidx.compose.ui.graphics.Color

/**
 * SH-02 · 重设计批 10 源码锚守护（2026-10-02 建锚）：输出台恒深底（DL-M22，⚖️ 2026-09-29
 * 裁决书 CONDITIONAL 拍板——方向采纳，卡面 proposal 色表**不可照抄**）。
 *
 * 裁决硬约束（adjudication-report.md S-SH02 + proposals.yaml notes 拍板）：
 * 1. 输出台整台恒深底：consoleBg 明暗分治（亮 #1E1E1E / 暗 #141416，EasyToolboxColors
 *    既有值，卡面旧文「亮暗同值」作废）——形态 = 根 Column 整台承载（R-6 2026-10-03
 *    维护者总裁定「按稿面」：头部/过滤栏/输入栏与空闲态同在深底上，稿面 .console 整块
 *    恒深、圆角 AppShapes.medium；2026-09-29 裁决「只深输出列表区」系被 R-6 覆盖的旧代际）；
 * 2. 行色修正表（五类行全量）：Command=consoleText 加粗 / Stdout=consoleText /
 *    Stderr=logError / StepMarker=consoleText 加粗（卡面整类漏列，裁决补与 Command
 *    同族）/ Status=logDebug——🔴 卡面色表（onSurfaceFaint 深底 1.02:1 隐身 /
 *    error 亮档 #B3261E 深底 2.55 FAIL / primary 主题派生不可控）三条全部禁用；
 * 3. 深底 AA 对比度 ≥ 4.5:1（裁决(5)：从「三行色」扩为五类行全量断言）——本测试
 *    以 WCAG 相对亮度实算（LogConsolePanel 同款恒深值 token，双态同值同判）；
 * 4. consoleBg 单点消费（R-6 整台恒深）：根 Column 承载后，面板其余区块不得再
 *    消费 consoleBg（第 2 处 = 双叠深面）；
 * 5. XA-15 半边吸收：ShellOutputPanel 剥注释后 contentSoft 清零（Status 行改
 *    logDebug 恒深值后 onSurfaceVariant.copy(contentSoft) 原式消亡；CockpitPanel
 *    半边由 XA-15 收官卡自验，本锚只钉 ShellOutputPanel 侧）。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache`。
 */
class ShellOutputPanelConsoleGuardTest {

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

    private fun panelFile(): File {
        val file = File(
            projectRoot(),
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/output/ShellOutputPanel.kt",
        )
        assertTrue(file.isFile, "源码锚文件缺失：${file.path}（重命名/移动后请同步本守护）")
        return file
    }

    // ── ① 恒深底形态锚 ──

    @Test
    fun `panel root is always deep console background`() {
        val src = panelFile().readText()
        assertTrue(
            Regex("""\.background\(\s*color\s*=\s*\S*consoleBg""").containsMatchIn(src),
            "SH-02 守护：输出台必须消费 consoleBg 恒深底（DL-M22：控制台/日志台材质归一——" +
                "浅底回潮即违桥接日志台同语义异材质禁令）",
        )
        // 整台恒深形态（R-6）：深底挂根 Column、圆角 AppShapes.medium（对稿 .console r-md）——
        // 深底圆角真锚（不得以别处 AppShapes.small 剪裁误命中代锚）
        assertTrue(
            Regex("""\.background\(\s*color\s*=\s*\S*consoleBg,\s*shape\s*=\s*AppShapes\.medium""").containsMatchIn(src),
            "SH-02 守护：整台深底必须挂根 Column 且圆角 AppShapes.medium（R-6 按稿面 .console r-md）",
        )
    }

    @Test
    fun `console bg consumed exactly once by panel root`() {
        // 整台恒深（R-6 代际）：深底由根 Column 单点承载；头部/过滤栏/输入栏/列表区
        // 均在深底之上，不得再消费 consoleBg（第 2 处 = 双叠深面）。
        // consoleBg 消费点计数锚（剥注释后）恰 1 处。
        val src = panelFile().readText()
        val codeOnly = src.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val hits = Regex("""consoleBg""").findAll(codeOnly).count()
        assertEquals(
            1,
            hits,
            "SH-02 守护：consoleBg 消费点必须恰 1 处（根 Column 整台承载，剥注释后计数）——" +
                "整台恒深下其余区块再挂深底即双叠深面",
        )
    }

    // ── ② 行色修正表锚（五类行全量）──

    @Test
    fun `output row colors follow adjudicated mapping table`() {
        val lines = panelFile().readText().split('\n')
        val rowBlock = lines
            .dropWhile { !it.contains("private fun ExecOutputItemRow") }
            .takeWhile { !it.startsWith("}") || !it.contains("fun ExecOutputItemRow") }
            .joinToString("\n")
            .let { block ->
                // 取到函数体结束（下一个顶格 }）：ExecOutputItemRow 是 private fun，
                // 函数体结束于「^}」行
                val start = lines.indexOfFirst { it.contains("private fun ExecOutputItemRow") }
                val end = lines.drop(start).indexOfFirst { it.trim() == "}" && it.startsWith("}") }
                lines.drop(start).take(end + 1).joinToString("\n")
            }
        assertTrue(rowBlock.contains("ExecOutputItemRow"), "ExecOutputItemRow 函数切片锚失效——函数签名漂移后请同步本守护")

        // 修正表五锚（🔴 裁决书：卡面色表 onSurfaceFaint/error 亮档/primary 三条全部禁用）
        val required = listOf(
            "consoleText" to "Command/Stdout/StepMarker 行色（consoleText 恒深值）",
            "FontWeight.Bold" to "Command/StepMarker 行加粗（裁决修正表：consoleText 加粗）",
            "logError" to "Stderr 行色（#EF5350 恒深值——error 亮档 #B3261E 深底 2.55 FAIL 禁用）",
            "logDebug" to "Status 行色（#90A4AE 恒深值——onSurfaceFaint 深底 1.02:1 隐身禁用）",
        )
        val missing = required.filter { (anchor, why) -> anchor !in rowBlock }
        if (missing.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("SH-02 守护：ExecOutputItemRow 行色修正表锚缺失：")
                    missing.forEach { (anchor, why) -> appendLine("  - 缺「$anchor」——$why") }
                },
            )
        }

        // 禁用色表三锚（卡面原荐深底不可读/不可控档）
        val banned = listOf(
            "onSurfaceFaint" to "Status 行（深底合成 1.02:1 隐身——裁决书点名禁用）",
            "colorScheme.error" to "Stderr 行（error 亮档 #B3261E 深底 2.55 FAIL——须 logError 恒深值）",
            "colorScheme.tertiary" to "StepMarker 行（主题派生色深底不可控——须 consoleText 加粗）",
        )
        val violations = banned.filter { (anchor, _) -> rowBlock.contains(anchor) }
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("SH-02 守护：ExecOutputItemRow 出现裁决禁用的行色（深底不可读回潮）：")
                    violations.forEach { (anchor, why) -> appendLine("  - 「$anchor」——$why") }
                },
            )
        }
    }

    @Test
    fun `status row no longer softens content alpha`() {
        // XA-15 半边吸收锚：Status 行改 logDebug 恒深值后，contentSoft 透明合成式在
        // ShellOutputPanel 代码区清零（CockpitPanel 半边由 XA-15 收官卡自验）
        val src = panelFile().readText()
        val codeOnly = src.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            "contentSoft" !in codeOnly,
            "SH-02/XA-15 守护：ShellOutputPanel 代码区 contentSoft 必须清零" +
                "（Status 行已改 logDebug 恒深值——contentSoft 透明合成在深底不可控，残留即弱字回潮）",
        )
    }

    // ── ③ 深底 AA 对比度断言（裁决(5)：五类行全量）──

    /** WCAG 2.x 相对亮度（sRGB 线性化） */
    private fun relativeLuminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /** WCAG 对比度公式：(L1 + 0.05) / (L2 + 0.05)，L1 为较亮者 */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `console fg token pairs meet WCAG AA on console bg in both modes`() {
        // 修正表全部行色 token × consoleBg（亮 #1E1E1E / 暗 #141416）双态 AA（4.5:1）实算。
        // token 值双态恒深同值（EasyToolboxColors.kt 亮 :63-70 / 暗 :101-106）——同值同判，
        // 这里逐态断言防止将来某态单独改值后深底可读性回潮。
        // 消费面套级化（consoleBg 解析 = 套级直配 > 暗态染色派生 > 静态基值）：全目录套 ×
        // 双态对 resolvedConsoleBg 实算——无覆写套落回本测试的静态基值面（覆写套读直配值）。
        val consoleText = Color(0xFFB0BEC5)
        val logError = Color(0xFFEF5350)
        val logDebug = Color(0xFF90A4AE)
        val consoleSuccess = Color(0xFF81C784)
        val consoleInfo = Color(0xFF64B5F6)
        val lightBg = Color(0xFF1E1E1E)
        val darkBg = Color(0xFF141416)
        val rows = listOf(
            "consoleText" to consoleText,
            "logError" to logError,
            "logDebug" to logDebug,
            "consoleSuccess" to consoleSuccess,
            "consoleInfo" to consoleInfo,
        )
        listOf("亮色" to lightBg, "暗色" to darkBg).forEach { (modeName, bg) ->
            rows.forEach { (tokenName, fg) ->
                val ratio = contrastRatio(fg, bg)
                assertTrue(
                    ratio >= 4.5,
                    "SH-02 守护：${modeName}consoleBg 上 $tokenName 对比度 ${"%.2f".format(ratio)}:1 < AA 4.5:1 " +
                        "（裁决(5) 五类行全量断言——改 token 值前先过本断言）",
                )
            }
        }
        // 消费面套级化锚：全目录 × 双态 × resolvedConsoleBg（AppTheme 装配同源解析）——
        // 直配套的深井色值同样必须扛住五类行恒深值
        com.jiaoay.easy_dev_toolbox.core.theme.AppThemeSpec.presets.forEach { spec ->
            listOf(false, true).forEach { dark ->
                val resolved = com.jiaoay.easy_dev_toolbox.core.theme.resolvedConsoleBg(spec, dark)
                val modeName = "${spec.id}[${if (dark) "暗" else "亮"}]"
                rows.forEach { (tokenName, fg) ->
                    val ratio = contrastRatio(fg, resolved)
                    assertTrue(
                        ratio >= 4.5,
                        "SH-02 守护：$modeName resolvedConsoleBg 上 $tokenName 对比度 ${"%.2f".format(ratio)}:1 < AA 4.5:1 " +
                            "（消费面套级化——套级直配/染色派生底同承恒深值 AA）",
                    )
                }
            }
        }
        // token 值与 EasyToolboxColors 双态定义对拍（防测试常量与 token 漂移；
        // Color 无 override equals 语义可直接比较——assertEquals 用 expected: Color 重载
        // 需显式标注泛型避免 Double 重载误匹配）
        assertEquals<Color>(
            consoleText, com.jiaoay.easy_dev_toolbox.core.theme.LightEasyToolboxColors.consoleText,
            "本测试 consoleText 常量与 LightEasyToolboxColors 漂移——以 token 真源为准同步",
        )
        assertEquals<Color>(
            logDebug, com.jiaoay.easy_dev_toolbox.core.theme.DarkEasyToolboxColors.logDebug,
            "本测试 logDebug 常量与 DarkEasyToolboxColors 漂移——以 token 真源为准同步",
        )
        assertEquals<Color>(
            logError, com.jiaoay.easy_dev_toolbox.core.theme.LightEasyToolboxColors.logError,
            "本测试 logError 常量与 LightEasyToolboxColors 漂移——以 token 真源为准同步",
        )
        assertEquals<Color>(
            lightBg, com.jiaoay.easy_dev_toolbox.core.theme.LightEasyToolboxColors.consoleBg,
            "本测试浅色 consoleBg 常量与 LightEasyToolboxColors 漂移",
        )
        assertEquals<Color>(
            darkBg, com.jiaoay.easy_dev_toolbox.core.theme.DarkEasyToolboxColors.consoleBg,
            "本测试暗色 consoleBg 常量与 DarkEasyToolboxColors 漂移",
        )
        assertEquals<Color>(
            consoleSuccess, com.jiaoay.easy_dev_toolbox.core.theme.LightEasyToolboxColors.consoleSuccess,
            "本测试 consoleSuccess 常量与 LightEasyToolboxColors 漂移",
        )
        assertEquals<Color>(
            consoleSuccess, com.jiaoay.easy_dev_toolbox.core.theme.DarkEasyToolboxColors.consoleSuccess,
            "本测试 consoleSuccess 常量与 DarkEasyToolboxColors 漂移（恒深值——亮暗必须同值）",
        )
        assertEquals<Color>(
            consoleInfo, com.jiaoay.easy_dev_toolbox.core.theme.LightEasyToolboxColors.consoleInfo,
            "本测试 consoleInfo 常量与 LightEasyToolboxColors 漂移",
        )
        assertEquals<Color>(
            consoleInfo, com.jiaoay.easy_dev_toolbox.core.theme.DarkEasyToolboxColors.consoleInfo,
            "本测试 consoleInfo 常量与 DarkEasyToolboxColors 漂移（恒深值——亮暗必须同值）",
        )
    }

    // ── ④ 头部 chrome 恒深值锚（对比度守护覆盖差集的机械化：头部曾整层漏出守打名单） ──

    @Test
    fun `output panel header consumes only deep console tokens`() {
        // 头部是收起态唯一常驻反馈面——主题派生色（error/success/onSurfaceVariant/
        // onSurfaceFaint/info）在浅色 consoleBg 上全线不达 AA，头部禁用；剥行注释后
        // 扫描（rationale 注释可提及禁用色名不触发）
        val lines = panelFile().readText().split('\n')
        val start = lines.indexOfFirst { it.contains("private fun OutputPanelHeader") }
        // end 为 drop(start) 后的相对下标
        val end = lines.drop(start).indexOfFirst { it.trim() == "}" && it.startsWith("}") }
        assertTrue(start >= 0 && end >= 0, "OutputPanelHeader 函数切片锚失效——签名漂移后请同步本守护")
        val headerBlock = lines
            .drop(start).take(end + 1)
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

        val banned = listOf(
            "colorScheme.error" to "失败态（浅色档对深底 2.55:1——走 logError）",
            "colorScheme.success" to "完成态（浅色亮档 2.61:1——走 consoleSuccess）",
            "colorScheme.onSurfaceVariant" to "图标/Idle 标题（1.78:1——走 consoleText/logDebug）",
            "onSurfaceFaint" to "计数（2.66:1——走 logDebug）",
            "current.info" to "运行进度圈（2.61:1——走 consoleInfo）",
        )
        val violations = banned.filter { (anchor, _) -> headerBlock.contains(anchor) }
        assertTrue(
            violations.isEmpty(),
            "SH-02 守护：输出台头部 chrome 出现主题派生色（深底 AA 盲区回潮）：\n" +
                violations.joinToString("\n") { (anchor, why) -> "  - 「$anchor」——$why" },
        )
        val required = listOf(
            "logError" to "失败图标/文案",
            "consoleSuccess" to "完成勾/文案",
            "consoleInfo" to "Running 进度圈",
            "logDebug" to "Idle 标题/计数",
            "consoleText" to "折叠钮/导出/清空图标",
        )
        val missing = required.filter { (anchor, _) -> !headerBlock.contains(anchor) }
        assertTrue(
            missing.isEmpty(),
            "SH-02 守护：输出台头部缺恒深值 token 消费：\n" +
                missing.joinToString("\n") { (anchor, why) -> "  - 缺「$anchor」——$why" },
        )
    }
}
