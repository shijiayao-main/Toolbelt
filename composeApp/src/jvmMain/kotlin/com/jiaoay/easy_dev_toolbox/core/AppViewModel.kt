package com.jiaoay.easy_dev_toolbox.core

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.data.loadEnum
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellCommandBuilder
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellType
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.theme.AppThemeSpec
import com.jiaoay.easy_dev_toolbox.core.theme.ThemeMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.jiaoay.easy_dev_toolbox.core.log.LogbackHelper
import com.jiaoay.easy_dev_toolbox.core.navigation.GoAiSettingsHook
import com.jiaoay.easy_dev_toolbox.core.navigation.GoReportHook
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.navigation.GoSettingsHook
import com.jiaoay.easy_dev_toolbox.core.navigation.GoXLogDecryptHook
import com.jiaoay.easy_dev_toolbox.core.navigation.SpaceAttribution
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.host.EasyDataManager
import com.jiaoay.easy_dev_toolbox.host.PageUsageTracker
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.home.ToolPageLabels
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.page.logworkbench.LogWorkbenchDeepLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class AppViewModel(
    /**
     * 启动视觉态同步预载：「启动亮色闪图」修复——main.kt runBlocking 预读
     * （与窗口几何同款纪律——首组合求值前就绪），种子化主题/材质/页面流——否则首帧
     * 画的是硬编码默认值（Default 主题 + System 模式 = 本机浅色系统 ⇒ 每次启动闪
     * 「亮色 + 布局不同」的画面再切回保存值）。null = 测试/无预载路径（异步恢复块
     * 仍为权威）。
     */
    private val preload: UiPreloadState? = null,
) : ViewModel() {

    private val _colorScheme = MutableStateFlow(preload?.colorScheme ?: AppThemeSpec.Default)
    val colorScheme: StateFlow<AppThemeSpec> = _colorScheme.asStateFlow()

    private val _themeMode = MutableStateFlow(preload?.themeMode ?: ThemeMode.System)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // currentPage = 主区内容 key（dock 是空间，tab 是打开的东西）；默认家=工作台
    private val _currentPage = MutableStateFlow(ToolPage.LogWorkbench)
    val currentPage: StateFlow<ToolPage> = _currentPage.asStateFlow()

    /**
     * 动态打开 tab 集（空间/文档分离模型：
     * **dock 是空间，tab 是你打开的东西；不会多开的页不占 tab 位**）。
     *
     * - 纯「打开的文档」集：空间页（工作台/智能体中心/Shell 工具/飞书桥接/工具）与
     *   单开系统页（设置/AI 设置/MCP/调试）切换**不进本集**；文档页（报告 + 五件套
     *   深入模式页）经 [setCurrentPage] 的 openDocument 语义或深链打开时追加；
     * - 空集 = tab 条整体隐藏（WorkspaceTabBar Auto-hide——回收纵向空间）；
     * - **不持久化**：重启回空集（空间态由 last_page 承载），openTabs 不入 DataStore。
     */
    private val _openTabs = MutableStateFlow<List<ToolPage>>(emptyList())
    val openTabs: StateFlow<List<ToolPage>> = _openTabs.asStateFlow()

    /**
     * 当前调查空间选中态（原只在 SpaceBarState 局部 remember
     * 自持，App 级零消费源，故升格 App 级）。SpaceBar select 回写；深链泛化（offer 带 spaceId）、
     * FileBuffer → 空间写入、空间自动归属（SpaceAttribution wire，见 init）均以此为
     * 「当前调查」判定源。null = 尚未选中（空间栏首次拉取前的窗口期）。
     */
    private val _currentSpaceId = MutableStateFlow<String?>(null)
    val currentSpaceId: StateFlow<String?> = _currentSpaceId.asStateFlow()

    /**
     * 工具聚合页预选子签槽：palette 深入模式五件套命令经
     * [ToolPageLabels.hostOf] 归并跳「工具」聚合页时，经本槽捎带「预选哪个子签」。
     *
     * - 写入：宿主 onPaletteNavigate 第二参（原散页枚举）→ App.kt 接线换算
     *   [ToolPageLabels.hostIndexOf]（0..4）；
     * - 消费：[ToolsPage] 顶层 LaunchedEffect 读**一次**设 tab 初值（一次性载荷，
     *   读后清槽——与持久初值合成：槽值优先、槽空回落持久值）；
     * - 越界防御：非 0..4 值消费侧回 0（App.kt 接线层 hostIndexOf 已 null 兜底，
     *   槽层再兜一层防手工注入）。
     *
     * 中立槽形态而非扩 [setCurrentPage] 参数：setCurrentPage 全仓 60+ 调用点，
     * 预选是 palette 单一来源的一次性载荷，不值得牵动全局签名。
     */
    private val _pendingToolsTab = MutableStateFlow<Int?>(null)
    val pendingToolsTab: StateFlow<Int?> = _pendingToolsTab.asStateFlow()

    /** 预选子签写入（null = 无预选，跳聚合页不带子签载荷） */
    fun setPendingToolsTab(index: Int?) {
        _pendingToolsTab.value = index
    }

    /** 预选子签一次性取用：读即清槽——payload 只被首个 ToolsPage 消费 */
    fun consumePendingToolsTab(): Int? {
        val value = _pendingToolsTab.value
        _pendingToolsTab.value = null
        return value
    }

    /**
     * 智能体中心聚合页预选子签槽（[pendingToolsTab] 三件套
     * 逐字同构）：palette 深链候选（会话/知识蒸馏/语音转写）与存量 last_page
     * 恢复（Flow/Distill/VoiceTranscribe 改道）跳聚合页时，经本槽捎带「预选哪个
     * 子签」。
     *
     * - 写入：三路——① App.kt onPaletteNavigate 接线经 [ToolPageLabels.agentCenterIndexOf]
     *   换算（palette Navigate 行第二参捎带原散页枚举）；② [setCurrentPage] 内置
     *   防御性置槽（任何以散页枚举直调的路径自动带签）；③ init 异步恢复块 raw 归并
     *   处（restoredPageTarget 改道时 raw 仍在手）；
     * - 消费：AgentCenterPage 顶层 LaunchedEffect 订阅槽流读即清（一次性载荷，
     *   ToolsPage 双 effect 配方同款——手动切签不受深链残留影响）；
     * - 越界防御：非 0..3 值消费侧回 0（接线层 agentCenterIndexOf 已 null 兜底，
     *   槽层再兜一层防手工注入）。
     *
     * 中立槽形态而非扩 [setCurrentPage] 参数：DeepAnalyzeHook 中立槽同款消费模式，
     * 避免 feature（AgentCenterPage）反向依赖宿主导航签名。
     */
    private val _pendingAgentCenterTab = MutableStateFlow<Int?>(null)
    val pendingAgentCenterTab: StateFlow<Int?> = _pendingAgentCenterTab.asStateFlow()

    /** 预选子签写入（null = 无预选，跳聚合页不带子签载荷）。
     *  深链达面板（导航深链行「知识库管理 · 蒸馏签 · 库面板」直达）：
     *  库面板展开载荷不经本槽——tab index 只达签，载荷走 :core 姊妹槽
     *  [com.jiaoay.easy_dev_toolbox.core.navigation.KbManagePanelHook]
     * （palette 候选行点击 offer → KbManagePanelContent 组合时 consume 后展开首仓库；
     *  本 VM 不经手——消费方在 feature 域，AppViewModel 实例不可达） */
    fun setPendingAgentCenterTab(index: Int?) {
        _pendingAgentCenterTab.value = index
    }

    /** 预选子签一次性取用：读即清槽——payload 只被首个 AgentCenterPage 消费 */
    fun consumePendingAgentCenterTab(): Int? {
        val value = _pendingAgentCenterTab.value
        _pendingAgentCenterTab.value = null
        return value
    }

    /** 空间选中回写（SpaceBar select 调用——SpaceBarState 镜像的宿主侧真源） */
    fun setCurrentSpace(id: String?) {
        _currentSpaceId.value = id
    }

    private val _shellType = MutableStateFlow(ShellCommandBuilder.shellType)
    val shellType: StateFlow<ShellType> = _shellType.asStateFlow()

    private val _debugPageEnabled = MutableStateFlow(false)
    val debugPageEnabled: StateFlow<Boolean> = _debugPageEnabled.asStateFlow()

    // ── 材质开关（EasyDataManager 持久化，默认开）──

    /**
     * 环境背景 aurora 开关（false = 停绘光斑）。
     * blur 只属于浮层（palette/toast/导览卡）且恒真玻璃，无「全局玻璃」语义
     * 可关（glassEnabled 开关已退役）；本字段是双开关仅存的一个。
     */
    private val _auroraEnabled = MutableStateFlow(preload?.auroraEnabled ?: true)
    val auroraEnabled: StateFlow<Boolean> = _auroraEnabled.asStateFlow()

    /** 可见产物树根的用户自定义路径（空串 = 默认 ~/Documents/Toolbelt），供设置页编辑 */
    private val _visibleExportRoot = MutableStateFlow("")
    val visibleExportRoot: StateFlow<String> = _visibleExportRoot.asStateFlow()

    /**
     * 工具页使用统计：计数只由「核心动作完成/发起」
     * 信号驱动（ToolUsageSignal 流订阅，见 init），点击页面/切页不再计数。
     */
    private val usageTracker = PageUsageTracker()

    /** 使用计数快照流（palette frecency 排序 + 推荐首页「常用入口」消费） */
    val pageUsageCounts: StateFlow<Map<String, Int>> = usageTracker.countsFlow

    init {
        // 启动页面态预载（先补 _openTabs 再写 _currentPage，同异步块的折叠语义——
        // 防首帧闪默认家页再跳保存页）：重定向语义与异步恢复共用 [restoredPageTarget] 纯函数。
        // 恢复页若是文档页（非空间）则以打开文档语义进 tab 集，
        // 空间页/单开系统页则只切页不产生 tab（TOOL_SPACES + SINGLE_SESSION_PAGES 判定）。
        preload?.let { p ->
            if (p.restoredPage !in TOOL_SPACES && p.restoredPage !in SINGLE_SESSION_PAGES &&
                p.restoredPage !in _openTabs.value
            ) {
                _openTabs.update { tabs -> tabs + p.restoredPage }
            }
            _currentPage.value = p.restoredPage
        }

        // 深链挂钩接线（宿主接线）：工具页产物「深度分析」→
        // 工作台深链槽投递 + 切页。wire 在 init 顶层（非协程）——首个 AppViewModel
        // 构造即接线，先于任何产物卡渲染。
        // 载荷经 :core 中立槽单点过路，宿主侧无自派生路径：
        // - targetTime 必须由生产侧经槽显式携带——数据源（logcat 标记锚点的
        //   markedYear 派生）只存在于生产侧，宿主不 import 引擎类型、无从自行派生；
        // - spaceId 是宿主特例——当前空间是宿主会话态（feature 不可见），由 wire
        //   处补进 offer；其余载荷一律生产侧定型、宿主原样转发。
        DeepAnalyzeHook.wire { outputFiles, prefillNote, prefillTargetTime ->
            val spaceId = _currentSpaceId.value
            LogWorkbenchDeepLink.offer(
                logFiles = outputFiles,
                prefillNote = prefillNote,
                targetTime = prefillTargetTime,
                spaceId = spaceId,
            )
            setCurrentPage(ToolPage.LogWorkbench)
        }

        // 空间归属接线：会话/run/报告创建成功时经
        // :core 中立槽 SpaceAttribution 自动记入当前调查空间（null 现值 = 无选中
        // 空间——槽内静默跳过，非错误）。wire 在 init 顶层（非协程，DeepAnalyzeHook
        // wire 同款）：读 _currentSpaceId 现值，不订阅流（归属语义 = 创建那一刻的
        // 「当前调查」，切空间不追溯改写既有成员）。
        SpaceAttribution.currentSpaceIdProvider = { _currentSpaceId.value }

        // 设备页「去设置配置 adb」CTA 接线：feature 侧经 :core
        // 中立槽 GoSettingsHook offer，宿主 wire 切设置页（GoSettingsHook KDoc）。
        GoSettingsHook.wire { setCurrentPage(ToolPage.Setting) }

        // 桥接驾驶舱「去配置 AI」CTA 接线：feature-larkbridge
        // 快速开始第 0 步与缺端点提示条经 :core 中立槽 GoAiSettingsHook offer，
        // 宿主 wire 切 AI 设置页（GoAiSettingsHook KDoc——GoSettingsHook 同款配方）。
        GoAiSettingsHook.wire { setCurrentPage(ToolPage.AiSettings) }

        // 工作台 run 侧栏「全部报告」CTA 接线：feature-logworkbench
        // run 历史侧栏顶部行钮经 :core 中立槽 GoReportHook offer，宿主 wire 切报告页
        //（GoReportHook KDoc——报告页第二入口：run 列表与报告页浏览面互指）。
        GoReportHook.wire { setCurrentPage(ToolPage.Report) }

        // 解压产物多文件「送 XLog 解密」跳页槽接线：目录路径留在 :core 槽 pending
        // 态由 XLog 解密页组合期 consumePendingDirectory 取走，本 wire 只负责切页
        // ——跳「工具」聚合页并预选 XLog 解密子签（palette 深入模式 Tools 跳转的
        // 槽位配方同款：setCurrentPage + setPendingToolsTab 双写）。wire 在本 init
        // 顶层（DeepAnalyzeHook 同款纪律）——首个 AppViewModel 构造即接线，先于
        // 任何解压产物卡渲染。
        GoXLogDecryptHook.wire {
            setPendingToolsTab(ToolPageLabels.hostIndexOf(ToolPage.XLogDecoder))
            setCurrentPage(ToolPage.Tools)
        }

        // 使用信号订阅：feature 动作点发信号 →
        // loadEnum 映射回枚举 → tracker 计数 + DataStore 落盘。未知 id（拼写漂移/
        // 已删枚举名如 Default）经 loadEnum 兜底后与 pageId 不符记 JLog 忽略——
        // 防静默丢信号又防误计数。丢流无害契约见 ToolUsageSignal KDoc。
        // 🔴 订阅在 init 顶层独立 launch（瞬时就位）：挂进恢复协程尾部的话，测试
        //    调度器推不动前置的真实 IO，collect 挂上前信号被丢（replay=0）——生产
        //    上「App 启动慢时用户先点了功能」也会同样丢。空窗期计数不丢由
        //    PageUsageTracker.load 的 max 合并回填兜底。
        viewModelScope.launch {
            ToolUsageSignal.signals.collect { pageId ->
                loadEnum<ToolPage>(pageId, ToolPage.LogWorkbench)
                    .takeIf { it.name == pageId }
                    ?.let { page ->
                        val countsJson = usageTracker.incrementAndSerialize(page)
                        if (countsJson != null) {
                            EasyDataManager.setUsageCounts(countsJson)
                        }
                    }
                    ?: JLog.d(TAG) { "忽略未知使用信号 pageId=$pageId（拼写漂移或已删枚举名）" }
            }
        }

        viewModelScope.launch {
            val customSeed = EasyDataManager.getCustomSeedColor().takeIf { it != -1L }?.let { Color(it.toInt()) }
            _colorScheme.value = AppThemeSpec.fromId(EasyDataManager.getColorSchemeName(), customSeed)

            _themeMode.value = loadEnum(EasyDataManager.getThemeModeName(), ThemeMode.System)

            // last_page 语义 = 主区内容 key（枚举无「欢迎页」值——loadEnum 兜底落家）。
            // 存量用户可能存着 "AppCenter"：
            // 枚举值三段式保留，但注册表已无 content，恢复即空白页；
            // 与 Default 欢迎页同款兜底：改道工作台家（LogWorkbench）。
            // 存量 last_page ∈ {Flow/Distill/VoiceTranscribe}（三散页已并入
            // 智能体中心聚合页——ToolPage.AgentCenter，页内子页签承载会话/蒸馏/语音）
            // 一律改道 AgentCenter。🔴 恢复落定走显式
            // seeds（先补 _openTabs 再写 _currentPage）——
            // 直写 _currentPage 会绕过 setCurrentPage 的折叠语义，造成 currentPage 在
            // openTabs 外的不变量破口。
            // 恢复路径带签置槽：raw 枚举在归并前先换算子签载荷
            // ——存量 last_page ∈ {Flow/Distill/VoiceTranscribe} 改道聚合页时不再恒
            // 落第一签（会话），AgentCenterPage 订阅槽流消费落对应子签。preload
            // 路径只存归并后页无 raw（不扩 UiPreloadState 字段——首帧闪第一签一瞬
            // 可接受），异步恢复块随后以本槽校正。
            val rawRestoredPage = loadEnum(EasyDataManager.getLastPageName(), ToolPage.LogWorkbench)
            val restoredTarget = restoredPageTarget(rawRestoredPage)
            ToolPageLabels.agentCenterIndexOf(rawRestoredPage)?.let { _pendingAgentCenterTab.value = it }
            // 空间/文档分离：恢复页是文档页才进 tab 集；空间页与
            // 单开系统页（设置/AI 设置/MCP/调试）只切页不产生 tab
            if (restoredTarget !in TOOL_SPACES && restoredTarget !in SINGLE_SESSION_PAGES) {
                _openTabs.update { tabs -> if (restoredTarget !in tabs) tabs + restoredTarget else tabs }
            }
            _currentPage.value = restoredTarget

            _debugPageEnabled.value = EasyDataManager.getDebugPageEnabled()

            // aurora 开关启动恢复（默认开；glassEnabled 已退役）
            _auroraEnabled.value = EasyDataManager.getAuroraEnabled()

            _visibleExportRoot.value = EasyDataManager.getVisibleExportRoot()

            val shellTypeName = EasyDataManager.getShellTypeName()
            val shellType = loadEnum(shellTypeName, ShellCommandBuilder.shellType)
            _shellType.value = shellType
            ShellCommandBuilder.shellType = shellType

            usageTracker.load()
        }
    }

    fun setColorScheme(scheme: AppThemeSpec) {
        _colorScheme.value = scheme
        viewModelScope.launch {
            EasyDataManager.setColorSchemeName(scheme.id)
            if (scheme is AppThemeSpec.Custom) {
                EasyDataManager.setCustomSeedColor(scheme.seed.toArgb().toLong() and 0xFFFFFFFFL)
            }
        }
    }

    /** 设置自定义种子色（色轮挑选 / 图片提取共用入口） */
    fun setCustomColorScheme(seed: Color) {
        setColorScheme(AppThemeSpec.Custom(seed))
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        viewModelScope.launch {
            EasyDataManager.setThemeModeName(mode.name)
        }
    }

        /**
     * 切页（空间/文档分离模型）：
     * - **空间页**（工作台/智能体中心/Shell 工具/飞书桥接/工具，[TOOL_SPACES] 单源）
     *   点击 dock/Ctrl+N 切换——只切 [currentPage]，**不产生 tab**；
     * - **单开系统页**（设置/AI 设置/MCP/调试，[SINGLE_SESSION_PAGES] 单源）同空间
     *   切换语义——切页不产生 tab（不会多开的页不占 tab 位）；
     * - **文档页**（报告/五件套深入模式）首次切入时追加进 [openTabs]（保序 append），
     *   成为可关闭文档 tab。
     *
     * last_page 持久化语义不变（切页只持久化不计数）。
     */
    fun setCurrentPage(page: ToolPage) {
        if (page !in TOOL_SPACES && page !in SINGLE_SESSION_PAGES) {
            _openTabs.update { tabs -> if (page !in tabs) tabs + page else tabs }
        }
        // 防御性置槽：任何以三退役 AI 散页枚举直调 setCurrentPage
        // 的路径自动带聚合页子签载荷（现状生产调用零命中——palette 归并在 App.kt 接线
        // 与 CommandPalette 两分支；本点是散页直调漏网时的兜底单点）。
        ToolPageLabels.agentCenterIndexOf(page)?.let { _pendingAgentCenterTab.value = it }
        // Debug 返回链（backFromDebug/previousPage）已退役——
        // 单开系统页导航语义对齐（切走即离开），setCurrentPage 不再维护隐式页面栈
        _currentPage.value = page
        viewModelScope.launch {
            EasyDataManager.setLastPageWithUsageCounts(page.name, null)
        }
    }

    /**
     * 关闭文档 tab：从 [openTabs] 移除；若关的是当前页则回工作台家
     * （顺带持久化 last_page）。空间页不在 tab 集内，关闭请求天然无效——不再需要
     * LogWorkbench no-op 特判。
     */
    fun closeTab(page: ToolPage) {
        _openTabs.update { tabs -> tabs - page }
        if (_currentPage.value == page) {
            setCurrentPage(ToolPage.LogWorkbench)
        }
    }

    // backFromDebug 与 previousPage 随调试页返回钮一并退役
    //（唯一调用链 = 页头返回钮；死代码守恒清退，非 deprecated 保留）

    fun setShellType(type: ShellType) {
        _shellType.value = type
        ShellCommandBuilder.shellType = type
        viewModelScope.launch {
            EasyDataManager.setShellTypeName(type.name)
        }
    }

    fun setDebugPageEnabled(enabled: Boolean) {
        _debugPageEnabled.value = enabled
        viewModelScope.launch {
            EasyDataManager.setDebugPageEnabled(enabled)
        }
    }

    /** 环境背景 aurora 开关回写（设置页「环境背景」行消费） */
    fun setAuroraEnabled(enabled: Boolean) {
        _auroraEnabled.value = enabled
        viewModelScope.launch {
            EasyDataManager.setAuroraEnabled(enabled)
        }
    }

    /**
     * 设置可见产物树根（空串 = 回默认根）：同步写 FileUtils 进程单源（本 setter 是
     * 生产面第二个合法写点，另一个是启动期 initializer）——改后即时生效，无需重启。
     */
    fun setVisibleExportRoot(path: String) {
        val trimmed = path.trim()
        _visibleExportRoot.value = trimmed
        FileUtils.applyVisibleExportRoot(trimmed)
        viewModelScope.launch {
            EasyDataManager.setVisibleExportRoot(trimmed)
        }
    }


    companion object {
        @Composable
        fun getViewModel(preload: UiPreloadState? = null): AppViewModel =
            viewModel { AppViewModel(preload) }

        /**
         * 空间页集（空间/文档分离模型）：dock 常驻六空间。
         * 空间页切换不产生 tab。单源常量——dock 图标集（DOCK_ENTRIES）与此集
         * 语义同构，但 dock 是视觉序数据（PageHomeMenu 持有），此集是行为语义集，
         * 两处不得合并（合会让 core 依赖 composeApp 页面文件）。
         */
        val TOOL_SPACES: Set<ToolPage> = setOf(
            ToolPage.LogWorkbench,
            ToolPage.AgentCenter,
            ToolPage.ShellTools,
            ToolPage.LarkBridge,
            ToolPage.Tools,
            ToolPage.DeviceObservation,
        )

        /**
         * 单开系统页集（设置 / AI 设置 / MCP 服务 / Shell 调试）：
         * **切页不产生 tab**（同空间页的切换语义；不会多开的页不占 tab 位），
         * 经 dock 系统区 / Ctrl+, / palette 直达、切走即离开。
         * 与 [TOOL_SPACES] 的区别：空间页 = dock 主区六图标（可多开工作面），
         * 本集 = 系统区单开配置页（无多开心智，tab 位是语义污染）。
         * tab 集剩余成员 = 报告 + 五件套深入模式页（真「打开的文档」）。
         */
        val SINGLE_SESSION_PAGES: Set<ToolPage> = setOf(
            ToolPage.Setting,
            ToolPage.AiSettings,
            ToolPage.McpExpose,
            ToolPage.Debug,
        )

        /**
         * last_page 恢复重定向（纯函数，同步预载与异步恢复双路共用单一真源）：
         * AppCenter / Flow / Distill / VoiceTranscribe 已退役 → 改道现行承接页。
         */
        fun restoredPageTarget(raw: ToolPage): ToolPage = when (raw) {
            ToolPage.AppCenter -> ToolPage.LogWorkbench
            ToolPage.Flow, ToolPage.Distill, ToolPage.VoiceTranscribe -> ToolPage.AgentCenter
            else -> raw
        }

        private const val TAG = "AppViewModel"
    }
}

/**
 * 启动视觉态预载快照（main.kt runBlocking 同步读取，首帧前种子化 AppViewModel）：
 * 覆盖「首帧即视觉可辨」的全部设置——主题（含自定义种子）/深浅模式/aurora 开关/
 * 恢复页。字段全部为已解析终值（无原始字符串）。
 * glassEnabled 字段已随玻璃开关退役删除。
 */
data class UiPreloadState(
    val colorScheme: AppThemeSpec,
    val themeMode: ThemeMode,
    val auroraEnabled: Boolean,
    val restoredPage: ToolPage,
)
