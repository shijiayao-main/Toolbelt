package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.page.ToolPage
import dev.vicart.compose.material.symbols.MaterialSymbols
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
/**
 * M6-T14 导航回填的 headless 守护：不启动 Compose，只断言注册表的**纯数据**产出。
 *
 * 为什么可行：`registrations` 的 `content` 是惰性 `@Composable` 工厂（本测试从不调用它），
 * `navItem` 是纯数据（`ImageVector` 为纯 Kotlin 矢量描述，不触及 Skia native）。
 *
 * 2026-09-22 S7 枚举收敛终态（调查工作台 S7——枚举收敛 + docs 同步 + 全量验收）：
 * 1. **Default 欢迎页枚举值已删**（S2 退役、S7 收敛）——启动直达工作台家；
 * 2. 五件套（XLog/Tag/分割/时间戳/解压）**枚举值保留**：S3/S4 终态下深入模式仍以
 *    「页 content」形态渲染（ParamCommandSpec.openPage → WorkspaceTabs 临时 tab →
 *    PageHomeContent 分发），content 注册即可达性锚——「退役页不可达」断言钉死
 *    导航面（navItems/顺序表/分组视图）零泄漏；
 * 3. 结构性页面（设置/应用中心/调试）content 注册保留、navItem = null；
 * 4. 全枚举注册覆盖不变量：ToolPage 每个枚举值必须有注册项（运行时锚——枚举与
 *    注册表同步演进，缺项时 PageHomeContent 兜底工作台家）。
 *
 * 2026-09-23 AI 聚合批（批一组 C）：Flow/Distill/VoiceTranscribe navItem 三段式退役
 *（AppCenter 先例——枚举值保留、content 注册保留、只退 navItem），AI 三页聚合为
 * AgentCenter 智能体中心（宿主自有聚合页，破例带 navItem 进 featureRegistrations
 * 拼接序自动获得全部消费链）。
 *
 * 2026-09-22 S3 后不变量（调查工作台命令面板升格——五件套 navItem 退役第一拨）：
 * 1. navItems 顺序 = featureRegistrations 拼接顺序——**五件套 navItem 退役**（XLog /
 *    Tag / 分割 / 时间戳 / 解压不再出现在 tab 目录/dock/应用中心；palette 命令 +
 *    openPage 深入模式可达）；
 * 2. **应用中心退役**（反馈 6，2026-09-23）：页/注册项/dock 入口/navItemsWithAppCenter
 *    全删——palette（Ctrl+K）是全量工具浏览/搜索/直达的唯一承接面；AppCenter 枚举值
 *    三段式保留（恒不可达断言钉死）；
 * 3. AI 置灰面 = 知识蒸馏 + 工作台 + 语音转写 + 流程（聚合入口 AgentCenter 不上送拍板
 *    ——子页各自表达 AI 依赖）；
 * 4. 分组完整覆盖 + 无空分组不变量保留（三类分组数据驱动）；
 * 5. 桥接页文案守卫保留（不得回潮旧「CLI / OpenAPI」说法）；
 * 6. **paramCommands 聚合面**：五件套命令 id 集合 + openPage 映射（B7 动作集不变——
 *    ParamCommand 走独立聚合不进 toolActions）。
 */class ToolPageRegistryNavTest {

    @Test
    fun `lark bridge is backfilled as the last sidebar item`() {
        val pages = ToolPageRegistry.navItems().map { it.page }

        assertEquals(
            listOf(
                ToolPage.Tools,
                ToolPage.ShellTools,
                ToolPage.DeviceObservation,
                ToolPage.LogWorkbench,
                ToolPage.AiSettings,
                ToolPage.AgentCenter,
                ToolPage.LarkBridge,
                ToolPage.McpExpose,
            ),
            pages,
            "navItems = 8 页（Tools 聚合页插在 LogKit 拼接段后；工具组 = Tools + ShellTools + " +
                "设备观测；Flow/Distill/VoiceTranscribe navItem 三段式退役，" +
                "AI 三页聚合为 AgentCenter）；navItems 顺序 = featureRegistrations 拼接序" +
                "（agentCenterRegistration 插在 flowRegistrations 后、LarkBridge 前）；不含应用中心",
        )
    }

    // ── 反馈 6 · 应用中心退役（2026-09-23）──

    @Test
    fun `app center is unreachable from every navigation surface`() {
        // 不可达面：navItems / navSections / 注册表 navItem 三路全不含 AppCenter——
        // 页已删（PageAppCenter.kt 文件删除 + 注册项删除 + dock Apps 入口删除），
        // 浏览/搜索/直达全部经命令面板（palette）承接
        val navSurfaces = ToolPageRegistry.navItems().map { it.page } +
            ToolPageRegistry.navSections().flatMap { it.items.map { item -> item.page } }
        assertFalse(
            ToolPage.AppCenter in navSurfaces,
            "应用中心已退役（反馈 6）：不得出现在任何导航面（navItems/分组视图）",
        )
        val registrations = registrationsAndCancel()
        val appCenterReg = registrations.firstOrNull { it.page == ToolPage.AppCenter }
        assertTrue(
            appCenterReg == null,
            "应用中心注册项已删（content/navItem 双删）——palette 是唯一浏览面",
        )
        // 枚举值三段式第二段：ToolPage.AppCenter 值保留（本批不删，后续批统一收敛删除）
        assertTrue(
            ToolPage.entries.contains(ToolPage.AppCenter),
            "AppCenter 枚举值三段式保留（反馈 6：值不删，只删页面/注册/入口）",
        )
    }

    @Test
    fun `lark bridge shows up in the welcome page tool section`() {
        val sections = ToolPageRegistry.navSections()

        // 2026-09-19 P4-C1 三类分组：工具 → 智能体 → 入口（registration.group 聚合，
        // 宿主零硬编码——新增页面免宿主改动）
        assertEquals(listOf("工具", "智能体", "入口"), sections.map { it.title })

        val agents = sections.first { it.title == "智能体" }
        assertEquals(
            listOf(ToolPage.LogWorkbench, ToolPage.AiSettings, ToolPage.AgentCenter),
            agents.items.map { it.page },
            "智能体组 = 工作台 + AI 设置 + 智能体中心（2026-09-23 AI 聚合批：知识蒸馏/" +
                "语音转写/流程 navItem 退役聚合入 AgentCenter 子页签；组内序 = 注册拼接序——" +
                "工作台是日志域主力排首位）",
        )
        val tools = sections.first { it.title == "工具" }
        assertEquals(
            listOf(ToolPage.Tools, ToolPage.ShellTools, ToolPage.DeviceObservation),
            tools.items.map { it.page },
            "工具组 = 工具聚合页 + ShellTools + 设备观测——五件套收拢为 Tools 页" +
                "子签（自身 navItem 仍退役，mapNotNull 自动跳过 null）；组内序 = 注册拼接序",
        )
        val entry = sections.first { it.title == "入口" }
        assertEquals(
            listOf(ToolPage.LarkBridge, ToolPage.McpExpose),
            entry.items.map { it.page },
            "入口组 = 飞书桥接 + MCP 服务（P5 外放第二成员——组内序 = 注册拼接序）",
        )
    }

    @Test
    fun `every nav item lands in exactly one section`() {
        val items = ToolPageRegistry.navItems()
        val sectioned = ToolPageRegistry.navSections().flatMap { it.items }

        assertEquals(
            items.map { it.page }.sorted(),
            sectioned.map { it.page }.sorted(),
            "分组必须完整覆盖全部导航项：漏项意味着该页在欢迎页不可见",
        )
        assertTrue(ToolPageRegistry.navSections().all { it.items.isNotEmpty() }, "不允许出现空分组")
    }

    @Test
    fun `lark bridge nav item carries the rewritten description`() {
        val item = ToolPageRegistry.navItems().firstOrNull { it.page == ToolPage.LarkBridge }

        assertNotNull(item, "飞书桥接页必须已回填进侧栏")
        assertEquals("飞书桥接", item.label)
        assertTrue(item.desc.isNotBlank(), "欢迎页提示卡文案不得为空")
        assertFalse("CLI" in item.desc, "旧传输层文案已过期：desc 不得再提 CLI")
        assertFalse("OpenAPI" in item.desc, "旧传输层文案已过期：desc 不得再提 OpenAPI")
    }

    // ── GLB-01 · 页面称谓单源清账（重设计批 2 · 2026-09-30）──

    @Test
    fun `nav item labels match the ToolPageLabels single source`() {
        // navItem.label 与 ToolPageLabels.label 双源并行是 9 处称谓漂移的根因
        //（「日志工作台」vs「工作台」、「Shell」vs「Shell 工具」、「桥接」vs「飞书桥接」）。
        // palette 候选直出 navItem.label，dock tooltip/tab/面包屑/窗口标题走
        // ToolPageLabels 单源——本断言钉死两源逐字同名，防称谓再漂移。
        ToolPageRegistry.navItems().forEach { item ->
            assertEquals(
                ToolPageLabels.label(item.page),
                item.label,
                "navItem [${item.page}] label 与 ToolPageLabels 规范名漂移——" +
                    "navItem.label 必须与单源规范名逐字相同（DL-M23/DL-F15）",
            )
        }
    }

    // ── GLB-02 · 导航图标语义一致批（重设计批 2 · 2026-09-30 · 撞标/同页异标清零）──

    @Test
    fun `nav item icons match the dock entries glyph for glyph`() {
        // 图标=页面指纹（DL-F16/§4.3 锚表）：五空间 navItem.icon 必须与 dock
        // DOCK_ENTRIES 逐项同字形——palette 候选直出 navItem.icon，dock 是空间
        // 唯一视觉锚，两面异标 = 同页两图标。
        val dockIcons = DOCK_ENTRIES.associate { it.page to it.icon }
        dockIcons.forEach { (page, dockIcon) ->
            val navItem = ToolPageRegistry.navItems().firstOrNull { it.page == page }
            assertNotNull(navItem, "空间页 [$page] 必须有 navItem（palette 面可达性锚）")
            assertEquals(
                dockIcon,
                navItem.icon,
                "空间页 [$page] navItem.icon 与 dock 图标漂移——图标=页面指纹，" +
                    "palette/dock 两面必须同字形（GLB-02）",
            )
        }
    }

    // ── 终验蜂群 P2 · GLB-01 dock label 面防漂移（2026-10-02）──

    @Test
    fun `dock entry labels match the ToolPageLabels single source`() {
        // dock DOCK_ENTRIES 的 label（tooltip + Ctrl+1..5 面板）与 ToolPageLabels
        // 单源曾是三处之一双源面（GLB-01 同族）——navItem.label 面已有逐字断言
        //（nav item labels match 用例），本断言补齐 dock 面同款，防「改单源漏 dock」
        // 称谓再漂移。
        DOCK_ENTRIES.forEach { entry ->
            assertEquals(
                ToolPageLabels.label(entry.page),
                entry.label,
                "dock DOCK_ENTRIES [${entry.page}] label 与 ToolPageLabels 规范名漂移——" +
                    "dock label 必须与单源规范名逐字相同（GLB-01）",
            )
        }
    }

    // ── AG-13 · dock/palette desc 双源统一（重设计批 8 · 2026-10-02）──

    @Test
    fun `agent center desc is verbatim identical across nav item and dock entry`() {
        // 同一页面两副面孔曾是漂移根因（注册项漏「调查空间」——批二迁入后未回改）。
        // 统一模板收敛到 PageHomeMenu.AGENT_CENTER_DESC 单源（internal const），
        // 本断言钉死 palette 面（navItem.desc）与 dock 面（DOCK_ENTRIES.hint）逐字同文
        // 且含四签规范名「调查空间」（design-language §4.2 裁决 #3）。
        val navItem = ToolPageRegistry.navItems().firstOrNull { it.page == ToolPage.AgentCenter }
        assertNotNull(navItem, "智能体中心必须有 navItem（palette 候选可达性锚）")
        val dockHint = DOCK_ENTRIES.first { it.page == ToolPage.AgentCenter }.hint
        assertEquals(
            AGENT_CENTER_DESC,
            navItem.desc,
            "navItem.desc 必须与 AGENT_CENTER_DESC 单源逐字相同（AG-13：desc 双源统一）",
        )
        assertEquals(
            AGENT_CENTER_DESC,
            dockHint,
            "dock DOCK_ENTRIES.hint 必须与 AGENT_CENTER_DESC 单源逐字相同（AG-13）",
        )
        listOf("会话", "知识蒸馏", "语音转写", "调查空间", "一页内切换").forEach { word ->
            assertTrue(
                word in AGENT_CENTER_DESC,
                "统一模板缺「$word」——四签名 + 一页内切换语义必须齐（AG-13）",
            )
        }
        assertFalse(
            "AI 会话" in AGENT_CENTER_DESC,
            "统一模板去「AI」前缀——页内签已是规范名（design-language §4.2 行 2）",
        )
    }

    @Test
    fun `mcp nav item icon matches the dock glyph`() {
        // GLB-02 改③：MCP 服务 navItem SHARE → HUB——与 dock 系统区同字形。
        val mcpNav = ToolPageRegistry.navItems().first { it.page == ToolPage.McpExpose }
        assertEquals(
            MaterialSymbols.HUB,
            mcpNav.icon,
            "MCP 服务 navItem.icon 必须为 HUB（GLB-02 改③：dock 面 HUB 同字形）",
        )
        assertEquals(
            MaterialSymbols.HUB,
            hostPageIcon(ToolPage.McpExpose),
            "MCP 服务 tab 兜底必须为 HUB（同字形防回潮）",
        )
    }

    // ── TL-02 · palette 深入模式路由聚合签（重设计批 4 · 五件套散页退役）──

    @Test
    fun `hostIndexOf maps five retired pages onto tools tab indexes`() {
        // 子签映射单源：五件套 → Tools 聚合页 SegmentedControl index（与 ToolsPage
        // 五签序逐字对齐：0=XLog 解密 … 4=解压）。palette 深入模式跳聚合页时经
        // 本表换算预选子签——映射漂移 = 深入模式落错签。
        assertEquals(0, ToolPageLabels.hostIndexOf(ToolPage.XLogDecoder), "XLog 解密 → 第 1 签")
        assertEquals(1, ToolPageLabels.hostIndexOf(ToolPage.LogTagFilter), "Tag 过滤 → 第 2 签")
        assertEquals(2, ToolPageLabels.hostIndexOf(ToolPage.FileSplitter), "文件分割 → 第 3 签")
        assertEquals(3, ToolPageLabels.hostIndexOf(ToolPage.Timestamp), "时间戳 → 第 4 签")
        assertEquals(4, ToolPageLabels.hostIndexOf(ToolPage.Archive), "解压 → 第 5 签")
        // 非五件套页（聚合页自身/空间页/系统页/僵尸枚举）恒无预选
        listOf(ToolPage.Tools, ToolPage.LogWorkbench, ToolPage.Setting, ToolPage.AppCenter).forEach { page ->
            assertEquals(
                null,
                ToolPageLabels.hostIndexOf(page),
                "非五件套页 [$page] 不得有子签映射（palette 深入模式跳它无预选载荷）",
            )
        }
    }

    // ── AG-15 · 聚合页子签深链（重设计批 5 · 三退役 AI 散页）──

    @Test
    fun `agentCenterIndexOf maps three retired ai pages onto agent center tab indexes`() {
        // 子签映射单源（AG-15）：三退役 AI 散页 → AgentCenter 聚合页 SegmentedControl
        // index（与四签序逐字对齐：0=会话 / 1=知识蒸馏 / 2=语音转写 / 3=调查空间）。
        // palette 深链候选跳聚合页时经本表换算预选子签——映射漂移 = 深链落错签。
        assertEquals(0, ToolPageLabels.agentCenterIndexOf(ToolPage.Flow), "会话（Flow）→ 第 1 签")
        assertEquals(1, ToolPageLabels.agentCenterIndexOf(ToolPage.Distill), "知识蒸馏 → 第 2 签")
        assertEquals(2, ToolPageLabels.agentCenterIndexOf(ToolPage.VoiceTranscribe), "语音转写 → 第 3 签")
        // 非三退役页（五件套/聚合页自身/空间页/系统页/僵尸枚举）恒无预选——第四签
        // 调查空间无枚举承载，不映射（卡面 proposal 原文）
        listOf(
            ToolPage.AgentCenter, ToolPage.Tools, ToolPage.LogWorkbench,
            ToolPage.Setting, ToolPage.AiSettings, ToolPage.XLogDecoder, ToolPage.AppCenter,
        ).forEach { page ->
            assertEquals(
                null,
                ToolPageLabels.agentCenterIndexOf(page),
                "非三退役 AI 散页 [$page] 不得有聚合页子签映射（跳聚合页无预选载荷）",
            )
        }
        // 值域守护（卡面 acceptance「新签增删时映射暴露」）：映射值域 ⊆ 0..2，
        // 与 AgentCenterPage options 四签（index 0..3）对齐——第四签调查空间无枚举。
        val mapped = listOf(ToolPage.Flow, ToolPage.Distill, ToolPage.VoiceTranscribe)
            .mapNotNull { ToolPageLabels.agentCenterIndexOf(it) }
        assertTrue(
            mapped.size == 3 && mapped.all { it in 0..2 },
            "agentCenterIndexOf 值域必须 ⊆ 0..2（三退役页恰三映射、无越界签）",
        )
    }

    // ── 运行时隐藏（navVisible 契约）──

    @Test
    fun `empty hidden set matches the default no hidden behavior`() {
        assertEquals(
            ToolPageRegistry.navItems().map { it.page },
            ToolPageRegistry.navItems(emptySet()).map { it.page },
        )
        assertEquals(
            ToolPageRegistry.navSections().map { it.title },
            ToolPageRegistry.navSections(emptySet()).map { it.title },
        )
    }


    @Test
    fun `hiding a page never leaves an empty section header`() {
        val allPages = ToolPageRegistry.navItems().map { it.page }
        allPages.forEach { page ->
            val sections = ToolPageRegistry.navSections(setOf(page))
            assertTrue(
                sections.all { it.items.isNotEmpty() },
                "隐藏 $page 后会留下空分组：${sections.filter { it.items.isEmpty() }.map { it.title }}",
            )
            assertEquals(
                sections.flatMap { it.items }.map { it.page }.sorted(),
                ToolPageRegistry.navItems(setOf(page)).map { it.page }.sorted(),
                "隐藏 $page 后分组仍须完整覆盖全部导航项",
            )
        }
    }

    @Test
    fun `all registrations are visible by default`() {
        // 批 4c 后 navVisible 恒可见（置灰维度迁往 aiEnabled）：默认无隐藏项
        assertEquals(emptySet(), ToolPageRegistry.hiddenPagesOf(), "默认不应有隐藏项")
    }

    // ── AI 置灰（批 4c：置灰替代隐藏）──



    // ── S3 · 侧栏活动指示（2026-09-15 架构升级批）──

    @Test
    fun `S3 default navActivity is null for registrations that do not declare it`() {
        // 默认 null ⇒ 全部注册项零视觉变化（改造零侵入的契约锚）
        // 2026-09-20 P4-C2 起日志工作台页声明活动流（占位恒 Idle——WorkbenchSession 3.4 接线）；
        // P1 交互重设计起 Shell 工具页声明活动流（ShellToolsRuntime.activity）
        // 2026-09-22 S5a 起流程页声明活动流（QuestSession companion 镜像——dock RUN 徽标）
        // 2026-09-23 AI 聚合批起智能体中心声明活动流（直引 QuestSession.activity——
        // 会话是聚合默认签，会话在跑 ⇒ 聚合入口点亮；Flow 注册项 navActivity 保留）
        val withActivity = ToolPageRegistry.navActivitySources().map { it.page }.toSet()
        assertEquals(
            setOf(ToolPage.LarkBridge, ToolPage.Distill, ToolPage.LogWorkbench, ToolPage.ShellTools, ToolPage.Flow, ToolPage.AgentCenter),
            withActivity,
            "活动流 = 桥接 + 蒸馏 + 工作台 + Shell + 流程 + 智能体中心",
        )
    }

    @Test
    fun `S3 bridge nav activity source is wired and defaults to idle before start`() {
        val source = ToolPageRegistry.navActivitySources().single { it.page == ToolPage.LarkBridge }
        assertEquals(
            com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity.Idle,
            source.navActivity?.value,
            "未启动桥接 ⇒ 活动指示 Idle（侧栏无点）",
        )
    }


    @Test
    fun `S3 navActivity sources never overlap with hidden or aiDisabled semantics`() {
        // 三维度独立：活动流存在不影响 navVisible/aiEnabled 归集（防双轨漂移的等价性锚）
        // 2026-09-20 P4-C2 起：aiEnabled 经宿主拼接点注入（feature 互不依赖铁律）；
        // 2026-09-20 P4-S 起：aiEnabled = 蒸馏 + 工作台 + 语音转写、navActivity = 桥接 + 蒸馏 + 工作台。
        // 2026-09-23 AI 聚合批：navActivity +AgentCenter（会话运行态直引）；aiEnabled 拍板
        // 聚合入口不上送——置灰面不因聚合收窄（三退役页 aiEnabled 上送保留，子页自表达）。
        val activityPages = ToolPageRegistry.navActivitySources().map { it.page }.toSet()
        val aiPages = ToolPageRegistry.aiEnabledSources().map { it.page }.toSet()
        assertEquals(
            setOf(ToolPage.Distill, ToolPage.LogWorkbench, ToolPage.VoiceTranscribe, ToolPage.Flow),
            aiPages,
            "aiEnabled 置灰面 = 蒸馏 + 工作台 + 语音转写 + 流程（设置页恒可见不置灰——防死锁钉保留；聚合入口 AgentCenter 不上送拍板）",
        )
        assertEquals(
            setOf(ToolPage.LarkBridge, ToolPage.Distill, ToolPage.LogWorkbench, ToolPage.ShellTools, ToolPage.Flow, ToolPage.AgentCenter),
            activityPages,
            "navActivity = 桥接 + 蒸馏 + 工作台 + Shell + 流程 + 智能体中心（AI 聚合批：QuestSession.activity 双 key——Flow 供 dock 会话槽，AgentCenter 供聚合入口）",
        )
    }

    // ── B7 · 命令面板动作项（P1 交互重设计激活：原「动作面恒空」断言随注册重建）──

    @Test
    fun `B7 tool actions registered from shell and bridge domains`() {
        val actionIds = ToolPageRegistry.toolActions().map { it.id }.toSet()
        assertEquals(
            setOf("shell.stop-current", "bridge.stop"),
            actionIds,
            "动作面 = Shell 停止当前命令 + 桥接停止（P1 激活首批；后续域按同契约追加）",
        )
    }

    // ── S3 · 带参命令聚合面（五件套退役第一拨的验收载体）──

    @Test
    fun `S3 param commands registered from five retired pages with openPage mapping`() {
        val commands = ToolPageRegistry.paramCommands()
        val byId = commands.associateBy { it.id }
        // 五件套命令 id 全集（其余投影命令不进本聚合——ParamCommandCatalog 另有投影面）
        assertEquals(
            setOf(
                "logkit.xlog-decrypt",
                "logkit.tag-filter",
                "logkit.file-split",
                "timestamp.convert",
                "archive.extract",
            ),
            byId.keys,
            "paramCommands = 五件套退役页各一件（palette 直执行主通道）",
        )
        // openPage 深入模式映射：五件套全指向原页面（content 保留注册的可达性锚）
        assertEquals(ToolPage.XLogDecoder, byId["logkit.xlog-decrypt"]?.openPage)
        assertEquals(ToolPage.LogTagFilter, byId["logkit.tag-filter"]?.openPage)
        assertEquals(ToolPage.FileSplitter, byId["logkit.file-split"]?.openPage)
        assertEquals(ToolPage.Timestamp, byId["timestamp.convert"]?.openPage)
        assertEquals(ToolPage.Archive, byId["archive.extract"]?.openPage)
        // params schema 非空（时间戳命令单可选参；其余必填面见 ToolCatalogDisciplineTest 对照）
        assertTrue(
            commands.all { it.params.isNotEmpty() },
            "五件套命令必须带参数 schema（PaletteParamForm 渲染依据）",
        )
    }

    @Test
    fun `S3 retired pages keep content registration reachable via openPage`() {
        // content 注册保留（退役页经 palette 深入模式可达——registrations 表完整覆盖）
        val pages = registrationsAndCancel().map { it.page }
        listOf(
            ToolPage.XLogDecoder,
            ToolPage.LogTagFilter,
            ToolPage.FileSplitter,
            ToolPage.Timestamp,
            ToolPage.Archive,
        ).forEach { page ->
            assertTrue(
                page in pages,
                "退役页 [$page] content 注册必须保留（palette openPage 深入模式可达性锚）",
            )
        }
    }

    // ── S7 · 枚举收敛终态（Default 欢迎页删除；五件套按 S3/S4 终态保留 content 形态）──

    @Test
    fun `S7 retired pages are unreachable from nav surfaces but reachable via palette deep mode`() {
        val retiredPages = setOf(
            ToolPage.XLogDecoder,
            ToolPage.LogTagFilter,
            ToolPage.FileSplitter,
            ToolPage.Timestamp,
            ToolPage.Archive,
        )
        // 不可达面：navItems / navSections 两路导航产出全不含退役页
        // （navItemsWithAppCenter 已随应用中心退役删除——反馈 6）
        val navSurfaces = ToolPageRegistry.navItems().map { it.page } +
            ToolPageRegistry.navSections().flatMap { it.items.map { item -> item.page } }
        retiredPages.forEach { page ->
            assertFalse(
                page in navSurfaces,
                "退役页 [$page] 不得出现在任何导航面（navItems/顺序表/分组视图）——只经 palette 深入模式可达",
            )
        }
        // 可达面：palette 深入模式映射（openPage）逐一指向原页面
        val openPages = ToolPageRegistry.paramCommands().mapNotNull { it.openPage }.toSet()
        assertEquals(retiredPages, openPages, "五件套 openPage 深入模式映射必须完整覆盖退役页集")
    }

    @Test
    fun `S7 structural pages register content but stay out of nav items`() {
        // 结构性页面 content 注册保留、navItem = null（设置/报告/调试 = 纯宿主页，
        // 不进任何导航面）。应用中心注册项已随页退役删除（反馈 6，2026-09-23——
        // 枚举值三段式保留，不可达断言见 app center is unreachable 用例）
        val registrations = registrationsAndCancel()
        listOf(ToolPage.Setting, ToolPage.Report, ToolPage.Debug).forEach { page ->
            val reg = registrations.first { it.page == page }
            assertEquals(null, reg.navItem, "宿主自有页 [$page] navItem 必须为 null（不进工具分组）")
        }
        // 🔴 反馈 6 起枚举覆盖不变量收窄：AppCenter 无注册项（页退役），其余枚举值
        //    必须有注册项（编译器穷尽性之外的运行时锚）
        val registered = registrations.map { it.page }.toSet()
        ToolPage.entries.forEach { page ->
            if (page == ToolPage.AppCenter) return@forEach
            assertTrue(
                page in registered,
                "ToolPage 枚举值 [$page] 缺注册项——枚举与注册表必须同步演进（否则 PageHomeContent 兜底工作台家）",
            )
        }
    }

    // ── AI 聚合批 2026-09-23 · 三页 navItem 退役 + AgentCenter 聚合入口 ──

    @Test
    fun `ai aggregated pages are unreachable from nav surfaces but keep content registration`() {
        val retiredPages = setOf(ToolPage.Flow, ToolPage.Distill, ToolPage.VoiceTranscribe)
        // 不可达面：navItems / navSections 两路导航产出全不含三退役页
        //（dock「智能体」槽已改道 AgentCenter——组 B 同步；深入模式经动态 tab
        // 仍可触达 Flow content，本断言只钉导航产出面零泄漏）
        val navSurfaces = ToolPageRegistry.navItems().map { it.page } +
            ToolPageRegistry.navSections().flatMap { it.items.map { item -> item.page } }
        retiredPages.forEach { page ->
            assertFalse(
                page in navSurfaces,
                "AI 聚合退役页 [$page] 不得出现在任何导航面（navItems/顺序表/分组视图）——聚合页子页签承接",
            )
        }
        // 可达面：content 注册保留（AgentCenterPage 子页签渲染三 feature 公开页 Composable；
        // 存量 last_page 改道与动态 tab 深达由宿主承接——组 B）
        val registrations = registrationsAndCancel()
        retiredPages.forEach { page ->
            val reg = registrations.firstOrNull { it.page == page }
            assertNotNull(reg, "AI 聚合退役页 [$page] content 注册必须保留（聚合页子页签可达性锚）")
            assertEquals(null, reg.navItem, "AI 聚合退役页 [$page] navItem 必须为 null（三段式退役第一段）")
        }
    }

    @Test
    fun `agent center is registered in featureRegistrations order and carries flow sideBar`() {
        // 聚合入口 navItems 顺序位（插在 flowRegistrations 后、LarkBridge 前）——
        // palette 候选/Ctrl+Tab 顺序表/活动指示全部自动获得的位置锚
        val pages = ToolPageRegistry.navItems().map { it.page }
        assertTrue(
            pages.indexOf(ToolPage.AgentCenter) < pages.indexOf(ToolPage.LarkBridge),
            "AgentCenter 必须插在 LarkBridge 之前（featureRegistrations 拼接序）",
        )
        // 历史会话侧栏槽位平移锚：sideBarOf(AgentCenter) 查到 Flow 平移的贡献
        //（Flow 注册项 sideBar 已删，槽位 key 跟着页迁——避免双份）
        val flowReg = registrationsAndCancel().first { it.page == ToolPage.Flow }
        assertEquals(0, flowReg.sideBar.size, "Flow 注册项不再自带 sideBar（槽位已平移到 AgentCenter）")
        val agentSideBar = ToolPageRegistry.sideBarOf(ToolPage.AgentCenter)
        assertEquals(1, agentSideBar.size, "AgentCenter 槽位 = 历史会话侧栏（Flow 平移）恰一份")
        assertEquals("历史会话", agentSideBar.first().label)
    }

    // ── 评审修复批 2026-09-23 · 报告占位 tab 补齐（plan S2 第四成员）──

    @Test
    fun `report page registers content but stays out of nav surfaces`() {
        val registrations = registrationsAndCancel()
        // content 注册存在（PageHomeContent 分发可达性锚——tab 容器成员）
        assertTrue(
            registrations.any { it.page == ToolPage.Report },
            "报告页 content 注册必须存在（WorkspaceTabs tab 集第四成员）",
        )
        // 不进任何导航面（navItems/分组视图——tab 集直达是唯一出现点；
        // navItemsWithAppCenter 已随应用中心退役删除——反馈 6）
        val navSurfaces = ToolPageRegistry.navItems().map { it.page } +
            ToolPageRegistry.navSections().flatMap { it.items.map { item -> item.page } }
        assertFalse(
            ToolPage.Report in navSurfaces,
            "报告页不得出现在任何导航面（tab 容器成员同应用中心语义，非等权导航页）",
        )
    }

    // ── 反馈 6 · 存量 last_page=AppCenter 恢复兜底（页退役后不落空白页）──

    @Test
    fun `retired app center name in last_page falls back to workbench home`() = kotlinx.coroutines.test.runTest {
        // 存量用户 last_page 存 "AppCenter"：枚举值三段式保留故 loadEnum 能解析，
        // 但注册表已无 content（页退役）——AppViewModel init 显式改道 LogWorkbench
        //（家）。本断言钉死「解析成功 ≠ 可直接恢复」的兜底语义：AppCenter 必须
        // 被改道，与 Default（枚举已删，loadEnum 兜底落家）殊途同归。
        val restored = com.jiaoay.easy_dev_toolbox.core.data.loadEnum<ToolPage>("AppCenter", ToolPage.LogWorkbench)
        assertEquals(ToolPage.AppCenter, restored, "AppCenter 枚举值保留——loadEnum 可解析（防误判已删）")
        val redirected = if (restored == ToolPage.AppCenter) ToolPage.LogWorkbench else restored
        assertEquals(
            ToolPage.LogWorkbench,
            redirected,
            "AppViewModel init 兜底分支：恢复值为 AppCenter 时必须改道工作台家（页已退役，无 content 可渲染）",
        )
    }
    /**
     * 构造 AppViewModel→取 registrations→立即 cancel（批 4 收口：裸构造的 VM 订阅
     * ToolUsageSignal 永不销毁，滞留订阅者会把后续测试的信号重复落盘到彼时全局
     * store——PageUsageTrackerTest 顺序型 flaky 根因「expected 1 was 13」）。
     */
    private fun registrationsAndCancel() = com.jiaoay.easy_dev_toolbox.core.AppViewModel().let { vm ->
        try {
            ToolPageRegistry.registrations(appViewModel = vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
