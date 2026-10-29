package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DS-10 守护（重设计批 3 · 2026-10-01）：SearchField 单行矮搜索框新件——
 * **新件值断言 + 迁移点源码锚**。
 *
 * 依据（DL-M09 / design-language §3.3 新增规格 C）：单行矮搜索框 shape 必须
 * capsule、高度 = controlStandard 34dp、focus 态 borderEmphasis 2dp、禁用态
 * 不可聚焦。四处搜索框（三种形态：表单带 label / 裸框 / 手工 DecorationBox
 * 紧凑档）收编为统一 [com.jiaoay.easy_dev_toolbox.widget.SearchField]。
 *
 * ## 断言面
 * 1. **新件值断言**（SearchField.kt 源码锚）：capsule / controlStandard 34dp /
 *    focus 2dp（strokeThin × borderEmphasis）/ 禁用 canFocus=false /
 *    8×16 内边距（space8/space16）/ 发丝描边（glassStrokeHairline）；
 * 2. **迁移点源码锚**：四处消费方文件含 SearchField 调用
 *    （ConfigFieldRenderer 工具条 / LogConsolePanel / ShellOutputPanel
 *    OutputFilterBar / ShellToolsPage）——🔴 迁移点实为四处非卡面三处
 *    （第四处 ShellToolsPage = 被吸收卡 SH-08 真宿主，scope 扩展如实上报）；
 *    含过滤回归等价断言（@modified/@restart 保留字管线 + onValueChange 直连）；
 * 3. **旧形态清零**：CompactOutlinedFilterField 生产代码面（src/jvmMain）清零
 *    （注释提及允许——收编史实记录）；迁移文件不再裸用旧搜索形态。
 *
 * 🔴 验收纪律（扫盘型同款）：`--rerun-tasks --no-build-cache` 防 build cache 假绿。
 */
class SearchFieldGuardTest {

    // ── ① 新件值断言：形态规格全 token 在位 ──

    @Test
    fun `search field shape is capsule`() {
        val text = file(UI_SEARCH_FIELD).readText()
        assertTrue(
            "AppShapes.capsule" in text,
            "SearchField 形状必须 AppShapes.capsule（DL-M09：单行矮搜索框 shape 必须 capsule）。",
        )
    }

    @Test
    fun `search field height is control standard`() {
        val text = file(UI_SEARCH_FIELD).readText()
        assertTrue(
            "height(Dimens.controlStandard)" in text,
            "SearchField 高度必须 Dimens.controlStandard（DL-M09：34dp 控件标准档）——" +
                "不得裸写 34.dp 或改档。",
        )
    }

    @Test
    fun `search field focused border is stroke thin emphasis`() {
        val text = file(UI_SEARCH_FIELD).readText()
        assertTrue(
            "BorderStroke(Dimens.strokeThin, surface.borderEmphasis)" in text,
            "SearchField focus 态描边必须 strokeThin(2dp) × borderEmphasis（规格 C：" +
                "focus 态 borderEmphasis 2dp）。",
        )
    }

    @Test
    fun `search field disabled state is unfocusable`() {
        val text = file(UI_SEARCH_FIELD).readText()
        assertTrue(
            "focusProperties { canFocus = enabled }" in text,
            "SearchField 禁用态必须 focusProperties { canFocus = enabled }（规格 C：" +
                "「禁用必须不可聚焦」——禁用仍可聚焦会弹空 caret 误导可输入）。",
        )
        assertTrue(
            "AppAlpha.contentDisabled" in text,
            "SearchField 禁用态内容必须洗 AppAlpha.contentDisabled 0.38（token 承载非字面量）。",
        )
    }

    @Test
    fun `search field content padding is eight by sixteen`() {
        val text = file(UI_SEARCH_FIELD).readText()
        assertTrue(
            "start = Dimens.space16" in text && "top = Dimens.space8" in text,
            "SearchField 内边距必须 8×16（contentPadding 上下 space8 / 左右 space16）。",
        )
        assertTrue(
            "BorderStroke(Dimens.glassStrokeHairline, surface.borderHairline)" in text,
            "SearchField 默认描边必须 glassStrokeHairline(1dp) × borderHairline（发丝档）。",
        )
    }

    // ── ② 迁移点源码锚：四处消费 SearchField ──

    @Test
    fun `log console panel filter row consumes search field`() {
        val text = file(FIELD_LOG_CONSOLE).readText()
        assertTrue(
            "SearchField(" in text,
            "LogConsolePanel 过滤行必须消费 SearchField（DS-10 迁移②；" +
                "退回裸 OutlinedTextField 即违 DL-M09 搜索 affordance 统一）。",
        )
        assertTrue(
            "textStyle = DesktopTypography.mono" in text,
            "LogConsolePanel 搜索框 mono 语域必须保留（textStyle 槽传 mono——值等价迁移）。",
        )
    }

    @Test
    fun `shell output filter bar consumes search field`() {
        val text = file(FIELD_SHELL_OUTPUT).readText()
        assertTrue(
            "SearchField(" in text,
            "ShellOutputPanel OutputFilterBar 必须消费 SearchField（DS-10 迁移③）。",
        )
        assertTrue(
            "trailingIcon = {" in text,
            "OutputFilterBar 清除关键字钮必须保留（trailingIcon 槽——SH-08 吸收要件）。",
        )
    }

    @Test
    fun `shell tools page search bar consumes search field`() {
        val text = file(FIELD_SHELL_TOOLS_PAGE).readText()
        assertTrue(
            "SearchField(" in text,
            "ShellToolsPage 页面搜索栏必须消费 SearchField（DS-10 迁移④——被吸收卡 SH-08 " +
                "真宿主，卡面三处之外的第四处 scope 扩展）。",
        )
        assertTrue(
            "搜索工具名或命令内容" in text,
            "ShellToolsPage 搜索 placeholder「搜索工具名或命令内容...」必须保留（SH-08 吸收裁定）。",
        )
    }

    @Test
    fun `config toolbar consumes search field with mention pipeline intact`() {
        val text = file(FIELD_CONFIG_RENDERER).readText()
        // 迁移①：工具条搜索框 CommitTextField 表单形态 → SearchField
        val toolbarAnchor = text.indexOf("private fun ConfigToolbar(")
        assertTrue(
            toolbarAnchor >= 0,
            "ConfigToolbar 函数锚漂移——工具条改写后须同步本守护锚。",
        )
        val toolbarBlock = text.substring(toolbarAnchor, toolbarAnchor + 2000)
        assertTrue(
            "SearchField(" in toolbarBlock,
            "ConfigToolbar 搜索框必须消费 SearchField（DS-10 迁移①；" +
                "退回 CommitTextField 表单形态即违 DL-M09 搜索 affordance 统一）。",
        )
        assertTrue(
            "搜索 \${BridgeFields.ALL.size} 项配置，或输入 @modified / @restart" in toolbarBlock,
            "ConfigToolbar placeholder「搜索 N 项配置，或输入 @modified / @restart」必须保留" +
                "（LB-13 吸收裁定：label 降 placeholder 文案不变）。",
        )
        // 🔴 过滤回归等价断言（卡面 acceptance「@modified/@restart 保留字照常写入 query」）：
        // chip 点击写入保留字的管线在 ConfigToolbar 块内且 onQueryChange 直连 SearchField
        assertTrue(
            "ConfigFilter.MENTION_MODIFIED" in toolbarBlock && "ConfigFilter.MENTION_RESTART" in toolbarBlock,
            "@modified/@restart 保留字 chip 逻辑必须在 ConfigToolbar 块内保留" +
                "（DS-10 迁移零变化面：chip 点击 = 写入 query / 再点 = 清空退出过滤）。",
        )
        assertTrue(
            "onValueChange = onQueryChange" in toolbarBlock,
            "SearchField onValueChange 必须直连 onQueryChange（每键上抛——与原 CommitTextField " +
                "onValueChange 通道等价，chip 点击写入 query 的管线才不断链）。",
        )
    }

    // ── ③ 旧形态清零：CompactOutlinedFilterField 全仓代码面清零 ──

    @Test
    fun `compact outlined filter field is deleted repo wide`() {
        val root = locateProjectRoot()
        val hits = root.walkTopDown()
            // 生产代码面（src/jvmMain）清零语义——测试文件自身的断言字符串字面量
            // 剥注释后仍存活（字符串内容保留是既有剥注释语义），不入扫描面
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("/build/") }
            .filter { it.path.contains("src/jvmMain/") }
            .filter { file ->
                // 剥注释后含 CompactOutlinedFilterField = 代码面残留（注释提及是收编史实，允许）
                stripComments(file.readText()).contains("CompactOutlinedFilterField")
            }
            .map { it.relativeTo(root).path }
            .toList()
        assertEquals(
            emptyList(),
            hits,
            "CompactOutlinedFilterField 生产代码面（src/jvmMain）必须全仓清零" +
                "（DS-10：SearchField 收编其整块删除）——命中文件即残留（注释提及允许，本断言已剥注释）。",
        )
    }

    @Test
    fun `migrated files have no bare outlined text field in search rows`() {
        // LogConsolePanel：搜索行不再裸用 OutlinedTextField（旧 import 已删）
        val console = file(FIELD_LOG_CONSOLE).readText()
        assertFalse(
            "import androidx.compose.material3.OutlinedTextField" in console,
            "LogConsolePanel 不得再 import OutlinedTextField（搜索行已迁 SearchField，" +
                "残留 import 说明旧形态回潮）。",
        )
        // ShellOutputPanel：过滤行不再有手工 DecorationBox 紧凑档（ShellInputBar 手动
        // 输入栏的 OutlinedTextField 是命令输入框非搜索框，不在 DS-10 收编面）
        val output = file(FIELD_SHELL_OUTPUT).readText()
        assertFalse(
            "OutlinedTextFieldDefaults.DecorationBox(" in stripComments(output),
            "ShellOutputPanel 不得再手工装配 DecorationBox（紧凑档已被 SearchField 收编）。",
        )
    }

    // ── 锚文件与工具（缺失即红——重命名/移动后须同步本守护路径）──

    private fun file(relPath: String): File {
        val file = File(locateProjectRoot(), relPath)
        assertTrue(file.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return file
    }

    /** 剥除 // 行注释与 /* */ 块注释（含 KDoc），字符串内容保留——UiArchitectureGuardTest 同款语义 */
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

    private companion object {
        const val UI_SEARCH_FIELD =
            "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/SearchField.kt"
        const val FIELD_LOG_CONSOLE =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/log/LogConsolePanel.kt"
        const val FIELD_SHELL_OUTPUT =
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/output/ShellOutputPanel.kt"
        const val FIELD_SHELL_TOOLS_PAGE =
            "feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/shelltools/ShellToolsPage.kt"
        const val FIELD_CONFIG_RENDERER =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/field/ConfigFieldRenderer.kt"
    }
}
