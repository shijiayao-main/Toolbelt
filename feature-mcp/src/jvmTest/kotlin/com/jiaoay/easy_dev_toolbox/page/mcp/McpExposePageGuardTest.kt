package com.jiaoay.easy_dev_toolbox.page.mcp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * McpExposePage/McpServerManager 源码锚守护（SYS-06 · 重设计批 6）：
 * McpExposePortConfirmTest 同款配方——页面是纯 Compose（模块无 UI 组合测试基建），
 * 验收面落源码锚（源码裸文本 + 常量直断言），防旧配方回潮。
 *
 * 断言锚刻意选「代码特有形态」（含括号调用/参数赋值），与注释书写形态错开；
 * 后续批改写时同步维护锚（文件重命名/移动须同步 REL_PATH）。
 *
 * - SYS-06 验收面：
 * 1. 运行徽章语义 pill 化（PillLabel successContainer/surfaceDeep 双态）——
 *    状态卡切片零 M3 Badge（primary 底状态徽章回潮即红）；
 * 2. 「已停止」徽章判定排除启动中态（STARTING_DETAIL 常量双侧同源——
 *    running=false + detail=启动中… 时不得闪灰徽章）；
 * 3. 启动失败 detail 走 Error StatusBanner（内联红字回潮即红）；
 * - SYS-09 验收面：接入示例块（CLI/JSON 两行可复制——endpointUrl() 单源派生、
 *   显示与复制同串、status.running 门控）；
 * - SYS-20 验收面：工具清单四族分节（groupBy + ToolFamily.entries 显式族序、
 *   总数标题保留）——VM 数据面直测见 McpExposeViewModelToolFamilyTest。
 * - D4 验收面（重设计批 7.5 · 终验蜂群 D4 · DL-M22）：服务日志列表区恒深底
 *   consoleBg + mono consoleText 行；空态留在深底外；onSurfaceVariant 行色不得
 *   回潮（深底对比度反转不可读，SH-02/SYS-11 深底修正表先例）。
 */
class McpExposePageGuardTest {

    // ── SYS-06 ①：运行徽章语义 pill 双态 ──

    @Test
    fun `running badge uses semantic pills not primary container`() {
        val text = pageSource()
        assertTrue(
            "container = toolboxColors.successContainer" in text,
            "运行中徽章 = PillLabel successContainer 底（DL-M10 container 直出，SYS-06 ①）",
        )
        assertTrue(
            "container = surfaceColors.surfaceDeep" in text,
            "已停止徽章 = PillLabel surfaceDeep 灰档（.pill.mute，SYS-06 ①）",
        )
    }

    /** 状态卡切片锚：写盘 Badge（工具清单卡）是另一语义，须排除在扫描面外。 */
    @Test
    fun `status card no longer renders m3 badge`() {
        val text = pageSource()
        val start = text.indexOf("服务状态")
        val end = text.indexOf("服务配置")
        assertTrue(
            start >= 0 && end > start,
            "状态卡切片锚漂移（「服务状态/服务配置」标题改动须同步本守护）",
        )
        val statusCard = text.substring(start, end)
        assertTrue(
            "Badge(" !in statusCard,
            "状态卡内 M3 Badge 徽章不得回潮（SYS-06：状态徽章走 PillLabel 语义 pill；" +
                "工具清单卡的写盘 Badge 是另一语义不在此切片）",
        )
    }

    // ── SYS-06 隐藏施工点：启动中态排除（常量双侧同源） ──

    @Test
    fun `stopped badge excludes starting transitional state`() {
        val text = pageSource()
        assertTrue(
            "status.detail != McpServerManager.STARTING_DETAIL" in text,
            "「已停止」徽章判定必须排除启动中态（SYS-06 隐藏施工点：running=false + " +
                "detail=启动中… 时不得闪灰徽章）",
        )
        val manager = managerSource()
        assertTrue(
            "const val STARTING_DETAIL" in manager,
            "STARTING_DETAIL 常量必须落 McpServerManager（双侧同源锚，防页面裸串比对漂移）",
        )
        assertTrue(
            "detail = STARTING_DETAIL" in manager,
            "startNow 启动中态必须消费常量（裸串回潮即红）",
        )
        assertEquals(
            "启动中…",
            McpServerManager.STARTING_DETAIL,
            "启动中 detail 文案值锚（页面徽章判定与 manager 状态机的共同契约）",
        )
    }

    // ── SYS-06 ②：失败 detail 走 Error StatusBanner ──

    @Test
    fun `failure detail renders error status banner`() {
        val text = pageSource()
        assertTrue(
            "StatusBanner(status = StatusBannerStatus.Error, text = detail)" in text,
            "失败 detail 必须走 Error StatusBanner 容器条（SYS-06 ②：内联红字回潮即红）",
        )
    }

    // ── SYS-09：接入示例块（CLI/JSON 两行可复制，单源端点 + running 门控） ──

    @Test
    fun `onboarding snippets render cli and json with copy buttons`() {
        val text = pageSource()
        assertTrue(
            "接入示例" in text,
            "状态卡尾部必须有「接入示例」区（SYS-09：首次接入零文档外查）",
        )
        assertTrue(
            "val cliCommand = \"claude mcp add toolbelt --transport http \$endpoint\"" in text,
            "CLI 示例必须是 claude mcp add 命令行（SYS-09 ①——预览稿 sys-s05 真源）",
        )
        assertTrue(
            "val jsonSnippet =" in text && "mcpServers" in text &&
                "\\\"url\\\":\\\"\$endpoint\\\"" in text,
            "JSON 示例必须是 mcpServers 片段（SYS-09 ②——url 与 CLI 同端点插值）",
        )
        assertTrue(
            "CopyButton(text = cliCommand)" in text && "CopyButton(text = jsonSnippet)" in text,
            "两行必须各带 CopyButton（带文字形态）且复制内容与显示同串（单源不重复拼）",
        )
    }

    @Test
    fun `onboarding snippets share single endpoint source behind running gate`() {
        val text = pageSource()
        assertTrue(
            "val endpoint = vm.endpointUrl()" in text,
            "端点必须经 endpointUrl() 单源派生 local 值（SYS-09 验收：端口号与当前端口一致）",
        )
        assertTrue(
            Regex("""if \(status\.running\) \{\s*val endpoint = vm\.endpointUrl\(\)""").containsMatchIn(text),
            "接入示例必须以 status.running 门控且紧邻单源端点声明（与端点复制钮同门控）",
        )
    }

    // ── SYS-20：工具清单四族分节（显式族序渲染 + 总数标题保留） ──

    @Test
    fun `tool list renders four family sections in explicit order`() {
        val text = pageSource()
        assertTrue(
            "tools.groupBy { it.family }" in text,
            "清单必须显式 groupBy 族字段（SYS-20：拼接序 = 描述符 16 项在前 agent 3 项在后 ≠ 渲染序）",
        )
        assertTrue(
            "ToolFamily.entries.forEach" in text,
            "渲染序必须显式走 ToolFamily.entries 四族序（不能依赖拼接序）",
        )
        assertTrue(
            "外放工具清单（\${tools.size} 项）" in text ||
                ("title = \"外放工具清单\"" in text && "text = \"\${tools.size} 项\"" in text),
            "「外放工具清单」标题总数保留（SYS-20 验收：总计数不变；蜂群回归 2026-10-06 " +
                "system.html:951 chead 逐字——计数从标题内联改卡头行尾 pill.mute 承载）",
        )
        assertTrue(
            "family.sectionTitle}（\${familyTools.size} 件）" in text,
            "族节头文案走 sectionTitle + 动态计数（tools 面增删零页面散改）",
        )
    }

    // ── D4（重设计批 7.5 · 终验蜂群 D4 · DL-M22）：服务日志恒深底 ──

    @Test
    fun `service log list renders constant dark console background`() {
        val text = pageSource()
        // 深底容器锚：consoleBg + AppShapes.medium（DL-M22 恒深底——亮 #1E1E1E/暗 #141416 明暗分治在 token 侧）
        assertTrue(
            ".background(color = toolboxColors.consoleBg, shape = AppShapes.medium)" in text,
            "服务日志列表区必须恒深底 consoleBg（D4 · DL-M22：控制台语义面；缺深底 = 回潮）",
        )
        // 行色修正表锚：深底上 onSurfaceVariant 反转不可读——行文本必须 consoleText
        val logSlice = text.substring(text.indexOf("服务日志"))
        assertTrue(
            "color = toolboxColors.consoleText" in logSlice,
            "日志行字色必须 consoleText（D4 深底修正表：onSurfaceVariant 原值深底不可读，SH-02/SYS-11 先例）",
        )
        // 空态门控锚：空态 Text（降级态语言）必须在深底分支之外（SYS-11 同款 if/else 门控）
        val emptyIndex = logSlice.indexOf("暂无事件")
        val bgIndex = logSlice.indexOf(".background(color = toolboxColors.consoleBg")
        val elseAnchor = logSlice.indexOf("} else {", emptyIndex)
        assertTrue(
            emptyIndex >= 0 && bgIndex > emptyIndex && elseAnchor in emptyIndex..bgIndex,
            "空态必须留在深底容器外（SYS-11 门控先例：if 空态浅底 / else 深底列表）",
        )
        // XA-18③（批 8 复核 P1-1 追认）：空态必须 EmptyStateView 形态（裸 Text 回潮即红）
        assertTrue(
            "EmptyStateView(" in logSlice && "暂无事件" in logSlice,
            "服务日志空态必须 EmptyStateView（XA-18③：与 SpaceBar 空态同语言；裸 Text 回潮即红）",
        )
    }

    // ── 源码锚工具（缺失即红——重命名后须同步） ──

    private fun pageSource(): String = source(PAGE_REL_PATH)

    private fun managerSource(): String = source(MANAGER_REL_PATH)

    private fun source(relPath: String): String {
        val file = File(locateProjectRoot(), relPath)
        assertTrue(file.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private fun locateProjectRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
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
            "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpExposePage.kt"
        const val MANAGER_REL_PATH =
            "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpServerManager.kt"
    }
}
