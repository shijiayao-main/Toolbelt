package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.ui.unit.dp

/**
 * 尺寸设计 token：间距阶梯与图标尺寸档位（数值自解释命名 + 少量语义别名）。
 * 取值表/三舱断点组/玻璃尺寸组全量定义见 docs/core/theme.md「尺寸设计 token」章节。
 *
 * 新页面/新组件必须引用本 token，禁止新写 `xx.dp` 间距字面量；需要新档位时先在此
 * 扩充定义再使用。本文件是 RAW_DP 守护整文件豁免本体（dp 字面量只许住在 token 定义内）。
 */
object Dimens {

    // ── 间距阶梯（4dp 网格为主，2/6/10 为半步档） ──
    val space2 = 2.dp
    val space4 = 4.dp
    val space6 = 6.dp
    val space8 = 8.dp
    val space10 = 10.dp
    val space12 = 12.dp

    /** 卡距档：卡与卡的纵向间距；PageSettings 14dp 散写白名单由本档消解。 */
    val space14 = 14.dp
    val space16 = 16.dp

    /** 卡内边距纵向档（卡内边距 16dp×20dp 的纵向档）；与 space16（横向）分档——近值不归并。 */
    val space20 = 20.dp
    val space24 = 24.dp

    // ── 语义别名（页面级高频场景，直接映射阶梯） ──

    /** 各功能页统一内边距（= space24） */
    val screenPadding = space24

    /** 页面顶层元素间距（= space16） */
    val screenSpacing = space16

    // ── 图标尺寸档位（对应现状：16 列表/状态图标、18 任务卡状态、20 按钮内、40 拖放区主图标、64 欢迎页 hero） ──

    /** 列表/状态类小图标 */
    val iconSmall = 16.dp

    /** 任务卡状态等中图标 */
    val iconMedium = 18.dp

    /** 按钮内大图标 */
    val iconLarge = 20.dp

    val iconDefault = 24.dp

    /** 拖放区主图标 */
    val iconHero = 40.dp

    /** Cockpit 面板 hero 状态图标（Cockpit hero 位统一 28dp 档，高于通用 iconLarge 20） */
    val heroIcon = 28.dp

    /** 欢迎页 hero 图标（PageDefault 居中主视觉） */
    val iconDisplay = 64.dp

    // ── 图表尺寸（bridge 统计图：柱状/环形/横向条形） ──

    /** 图表主图高度（柱状图/环形图统一高度；环形图基准 120dp） */
    val chartHeight = 120.dp

    /** 近 7 天柱图区高（4 行柱位 × space24 = 96dp） */
    val barChartHeight = 96.dp

    val chartCompactHeight = 56.dp

    /** 柱状图单柱宽度 */
    val chartBarWidth = 24.dp

    /** 柱状图柱间距（4dp 档；命名语义独立保留） */
    val chartBarGap = 4.dp

    /** 环形图环厚 / 横向条形图条高 */
    val chartStroke = 24.dp

    /** 发送者卡内嵌迷你条形高度（6dp 细条档） */
    val chartMiniBarHeight = 6.dp

    /** 发送者卡内嵌迷你条形宽度 */
    val chartMiniBarWidth = 96.dp

    // ── 控制台/日志台高度范围（整页滚动场景 weight 不可用，少则收缩、多则封顶） ──

    /** 日志/输出控制台最小高度 */
    val consoleHeightMin = 240.dp

    /** MCP 服务日志台最小高度（日志区从 96dp 起步，行多时向上扩到 mcpLogHeightMax） */
    val mcpLogHeightMin = 96.dp

    /**
     * MCP 服务日志台封顶高度：200 行环形事件流全量渲染过长会把页面滚动视口整段吃掉，
     * 新行恒落在视口外（追加后视口不贴底）——封顶后列表内部自滚 + 贴底跟随承接。
     * 取值 = consoleHeightMin 同档（日志台语义面的常规可视高度）。
     */
    val mcpLogHeightMax = 240.dp

    // ── 自绘开关（36×20 自绘件）──

    /** 开关轨宽（36dp） */
    val switchTrackWidth = 36.dp

    /** 开关轨高（20dp） */
    val switchTrackHeight = 20.dp

    /** 开关滑块直径（16dp） */
    val switchThumbSize = 16.dp

    /** 开关滑块 off 位（2dp；on 位 = trackWidth - thumb - off） */
    val switchThumbInset = 2.dp

    /** 空态图标尺寸（22dp 空态档；iconHero 40 保留给 hero 场景） */
    val emptyStateIcon = 22.dp

    /** 报告表首列宽（96dp；mdtable 特化列宽 96/60/1fr 三段） */
    val reportTableColTs = 96.dp

    /** 报告表次列宽（≥3 列时生效，60dp 档） */
    val reportTableColLvl = 60.dp

    /** HITL 确认卡宽（浮层卡 360dp；语义独立不借 sideBar 档） */
    val hitlCardWidth = 360.dp

    /** 页头带字小钮垂直 padding（5dp——4dp 网格外半步，无既有档） */
    val headerBtnPaddingV = 5.dp

    /** 左缘「已修改」竖条宽（3dp primary 竖条） */
    val configModifiedBarWidth = 3.dp

    /** 状态行标签定宽（120dp 对齐槽） */
    val statusLabelWidth = 120.dp

    /** 胶囊小件垂直 padding 半步档（3dp：pill/scope/seg-mini 三类小件统一；4dp 网格外半步） */
    val pillPaddingVertical = 3.dp

    /** dock 图标字形尺寸（22dp；iconMedium 18dp 是通用档，dock 单点提档） */
    val dockIconSize = 22.dp

    /** 日志/输出控制台最大高度 */
    val consoleHeightMax = 420.dp

    /** 投屏帧区高度上限（16:9 满宽帧高必超观测页视口——无上限时帧底缘被裁，看不全画面） */
    val screencastFrameHeightMax = 420.dp

    /** 投屏独立视口窗最小宽（窗口下限——防画面节点坍缩触发触摸坐标 (0,0) 注入；与 consoleSearchWidth 同值语义独立） */
    val screencastDetachedWindowMinWidth = 320.dp

    /** 投屏独立视口窗最小高（状态条 + 满幅帧的最小可视承载；与 consoleHeightMin 同值语义独立） */
    val screencastDetachedWindowMinHeight = 240.dp

    /**
     * 投屏独立窗顶栏恒高（状态行 + 停止/快照/录像操作钮的单行恒高槽——操作面迁窗后
     * 顶栏不随状态行内容塌缩；与 undoBarHeight/titleBarHeight 同 40dp 档语义独立）。
     */
    val screencastWindowBarHeight = 40.dp

    /**
     * 投屏独立窗键排 FlowRow 形态的窗内容高下限：maxHeight 低于此值 = 高度饥饿——
     * 键排切单行横向滚动（FlowRow 三行会在最小窗 320×240 吃光画面区权重段，帧区
     * 0 高 = 图像不可见回潮）；高于此值保持 FlowRow 换行。按高度判不按宽度判
     * （宽而矮的窗同样高度饥饿）。
     */
    val screencastWindowKeyGridFlowMinHeight = 320.dp

    /**
     * 投屏独立窗帧区最小高度（帧区 weight 权重段的保底下限）：回执行区（快照/录像
     * 回执 + 瞬态文本）恒高无上限，密集堆叠可把权重段挤到 0 高——下限保住帧区可见
     * 性；保底引发的总高超窗由底部键排/文本条被裁承担（预期取舍）。
     */
    val screencastWindowFrameMinHeight = 120.dp

    /**
     * 投屏手势反馈件虚拟指环半径（17dp = 环直径 34px 档半值——指尖接触面量级；
     * 反馈叠层绘制基准：hotWash 洗底 + primary 描边，真机手感调参留异步反馈通道）。
     */
    val screencastGestureRingRadius = 17.dp

    /** base64 候选图片预览高度上限（4K 截图等大图按 Fit 缩到此高内预览，不原尺寸铺开撑破页面） */
    val imagePreviewHeightMax = 360.dp

    /** 日志台过滤行搜索框定宽（320dp 定宽不伸缩；工作台报告面板 320dp 同值但语义独立——近值不归并） */
    val consoleSearchWidth = 320.dp

    val shellTreeHeightMin = 320.dp

    /** 会话输入区最大高度（chat Composer 多行扩展上限；最小值 = consoleHeightMin/4） */
    val composerHeightMax = 160.dp

    val memoryDialogHeightMax = 320.dp

    /** 流程页附件拖放区高度（聊天区上方紧凑态——FileDropZone 默认 140dp 的矮版） */
    val questDropZoneHeight = 80.dp

    /** 日志查看对话框行号跳转输入框定宽（120dp：容纳 6 位行号与占位提示的紧凑定宽，行内余宽留给统计行） */
    val lineJumpFieldWidth = 120.dp

    /**
     * 日志查看对话框右侧命中小地图列宽（16dp：点击命中区与刻度可见宽同宽；
     * 列高复用 consoleHeightMax、刻度线宽复用 strokeThin，不另设新档）。
     */
    val viewerMinimapWidth = 16.dp

    // ── 细粒度装饰 token ──

    /** 细描边宽度（行内进度圈 strokeWidth、边框线宽） */
    val strokeThin = 2.dp

    val fieldLabelWidth = 190.dp

    /** 表单 ctl 右缘输入框最小宽（220dp 最小宽；AI 设置页 frow 化 ctl 段定宽） */
    val fieldControlMinWidth = 220.dp

    /**
     * frow hint 中段宽上限（320dp）：hint 在横排 Row 中无权重，软换行 Text 会吃满
     * 剩余可用宽、把 ctl 权重段压到零宽——上限兜住编辑器段的最小可用宽。
     */
    val fieldHintMaxWidth = 320.dp

    /**
     * frow 三段行窄行纵排降级阈值（620dp）：行可用宽低于此值时 lab/hint/ctl 由横排
     * 降级为纵排（hint/ctl 各占整行）——横排三段加重启 pill 的宽度和约 790dp
     * （lab 190 + hint 320 + ctl 最小 220 + pill 60），窄于此值横排必然互相挤压破版。
     */
    val fieldRowStackBelowWidth = 620.dp

    /** 行内进度圈直径（按钮内 loading 等紧凑场景） */
    val progressInline = 14.dp

    /** 状态指示圆点直径（S3 侧栏活动指示：dock 图标右下角） */
    val dotIndicatorSize = 6.dp

    // ── 布局子页线框图（图舱几何与绘制档位，全 token 化） ──

    /** 双栏图舱最小宽（防被树舱挤死；workbenchSidePanelWidth 同量级独立语义） */
    val layoutDiagramMinWidth = 280.dp

    /** Compact 档线框图回落高（consoleHeightMin 同量级独立语义——图固定高、树独立滚动） */
    val layoutDiagramHeightCompact = 200.dp

    /** 线框图普通线框描边宽（选中描边走 strokeThin） */
    val layoutDiagramStroke = 1.dp

    /** 线框图节点标签绘制阈值（节点视口高 ≥ 此值才绘 label——全量 measure 是绘制主成本） */
    val layoutDiagramLabelMinHeight = 12.dp

    /** 预览文本占位骨架条高（TEXT 类族无文本节点的线框占位——源无文本内容时如实画骨架不编造文字） */
    val layoutPreviewSkeleton = 2.dp

    // ── 头像（统计页发送者行与用户详情卡） ──

    /** 列表行内头像直径（统计页发送者行首） */
    val avatarInline = 24.dp

    /** 详情卡头像直径（用户详情页信息卡） */
    val avatarProfile = 48.dp

    /** 桌面滚动条宽度（VerticalScrollbar 细杆，对齐 tonal 扁平语言的低存在感导航件） */
    val scrollBarWidth = 8.dp

    // ── 侧栏 dock（贴边实底整列形态）──
    /**
     * dock 轨宽度 = 68dp（贴边实底整列，非悬浮胶囊形态）。
     */
    val railWidthCompact = 68.dp

    /** dock 项图标槽尺寸（IconButton 触摸目标 + tonal 圆底视觉直径） */
    val dockItemSize = 40.dp

    /** dock 项 tonal 圆底尺寸（小于触摸目标，视觉内收） */
    val dockItemBackgroundSize = 32.dp

    /** 主区 tab 条高度（调查工作台 S2：WorkspaceTabs 页签行——iconMedium 18 + 上下 space8） */
    val tabBarHeight = 34.dp


    /** 应用中心网格卡最小列宽（LazyVerticalGrid 自适应列宽下限：窗口越宽列越多） */
    val gridCardMinColumnWidth = 180.dp

    // ── 控件高度三档（Carbon 密度体系：紧凑/标准/宽松） ──

    /** 紧凑档：表格行/工具栏/紧凑列表行（24-28dp 带内） */
    val controlCompact = 26.dp

    /** 标准档：表单控件/按钮/下拉（32-36dp 带内） */
    val controlStandard = 34.dp

    /** 宽松档：首页卡片/拖放区等强调场景（40dp+） */
    val controlSpacious = 40.dp

    val minimumInteractiveSize = 32.dp

    /** 管理行动作钮等宽基线（按最长钮「新增自定义命令」7 字文案 + 前导图标 + 按钮内边距推导，≥最长钮取最近偶数档）；取值登记 docs/core/theme.md「有据保留」清单 */
    val manageActionMinWidth = 164.dp

    // ── 命令面板 omnibar ──

    /** 面板宽度（Ctrl+K 唤起的命令面板/omnibar）= 560dp 恒值；通用候选弹层基类是 460dp，命令面板本体勿取 460 */
    val paletteWidth = 560.dp

    /** 面板距窗口顶部偏移（顶部弹出形态，非 AlertDialog 居中） */
    val paletteTopOffset = 120.dp

    /** 候选列表最大高度（超出内部滚动——面板不随条目数无限长） */
    val paletteListMaxHeight = 420.dp

    val settingsNavWidth = 200.dp

    // ── 日志工作台三栏（三栏布局全 token 化，零白名单目标） ──

    /** 工作台左栏宽（结构化输入表单 + run 列表；ShellToolsPage 280dp 右栏同量级） */
    val workbenchSidePanelWidth = 280.dp

    /** 工作台右栏宽（报告面板：因果链三段卡 + 假设徽章 + 依据引用块） */
    val workbenchReportPanelWidth = 320.dp

    /** 证据流直方图高度（中栏顶部时间分布）= 16dp；mini 缩略档 14dp（evidenceBlockHistogramHeight）同构自洽，非笔误 */
    val workbenchHistogramHeight = 16.dp

    /** 阶段锚点条高度（中栏管线阶段分界锚——解密→时间过滤→环境扫描→Tag 过滤→研判） */
    val workbenchStageBarHeight = 36.dp

    // ── 调查工作台 S4：证据流块化（琥珀块=工具产物 / 紫块=AI 研判）──

    /** 证据流块头行内色条宽（块头左侧语义色条——对齐 chat BarRow 色条语言）= 4dp */
    val evidenceBlockBarWidth = 4.dp

    /** 主题卡色点直径（10dp） */
    val themeDotSize = 10.dp

    /** 记忆管理正文控制台最小高度（150dp 起步） */
    val memoryConsoleMinHeight = 150.dp

    /** 计划投影行内状态图标（13dp 微型档） */
    val iconTiny = 13.dp

    /** 拖拽分隔条 grip 把手（46×3dp） */
    val dragbarGripWidth = 46.dp
    val dragbarGripHeight = 3.dp

    /** 工作台空态图标（40dp hero 档；EmptyStateView iconSize 域覆写） */
    val workbenchEmptyIcon = 40.dp

    /** 表单数字窄控宽（110dp inline 窄档，窄于 fieldControlMinWidth 220） */
    val fieldControlNarrowWidth = 110.dp

    /** 证据流块头左侧直方图缩略高度 = 14dp（主图 16dp 的 mini 同构档） */
    val evidenceBlockHistogramHeight = 14.dp

    // ── 三舱/焦点/断点/反馈容量 token ──

    /** 侧栏槽位宽下限（ContextSideBar 拖拽边界下限；HIG NavigationSplitView min 180 同量级再宽裕） */
    val sideBarWidthMin = 220.dp

    /** 侧栏槽位默认宽（340dp——shell-tools 域内特化档，宽于全局归一档 280dp 量级） */
    val sideBarWidthDefault = 340.dp

    /** 侧栏槽位宽上限（ContextSideBar 拖拽边界上限） */
    val sideBarWidthMax = 360.dp

    val sidebarRailWidth = 48.dp

    /** 分隔条视觉宽（侧栏槽位拖拽把手——细存在感，视觉语言对齐滚动条） */
    val splitterWidth = 4.dp

    /** 分隔条命中区宽（> 视觉宽，Fitts 定律：命中面大于可见面） */
    val splitterHitWidth = 12.dp

    /** 分隔条可感知行程下限：有效行程低于此值即死 affordance——宿主隐藏把手诚实化 */
    val splitterMinTravel = 24.dp

    /** 焦点环偏移（appFocusRing 描边与控件边缘的间隙；宽度复用 strokeThin 不新增值） */
    val focusRingOffset = 1.dp

    /** Compact 档主区内边距（窗口 < 1024 时的降级收窄档；= space16） */
    val screenPaddingCompact = 16.dp

    /** 快捷键参考卡最大高度（帮助卡对话框内容上限） */
    val helpCardMaxHeight = 480.dp

    /** 窗口断点：Compact 档上限（< 此值为 Compact——侧栏转浮层、主区 padding 收窄） */
    val breakpointCompactMax = 1024.dp

    /** 窗口断点：Medium 档上限（Compact..Medium 为常规桌面形态） */
    val breakpointMediumMax = 1440.dp

    /** 窗口断点：Wide 档上限（Medium..Expanded 可拖宽侧栏；> 此值为 Wide——内容限宽生效） */
    val breakpointWideMax = 1760.dp

    /** Expanded 档报告面板加宽档（日志工作台右栏在宽屏下的扩展值） */
    val workbenchReportPanelWidthWide = 400.dp

    /** 浮层提示条宽（StatusHost toast 通道） */
    val toastWidth = 360.dp

    val toastBottomOffset = 46.dp

    val inspectorTopClearance = 54.dp
    val inspectorBottomClearance = 50.dp

    // ── SegmentedControl 分段控件 ──

    /** 分段控件容器内边距（3dp） */
    val segmentedPadding = 3.dp

    /** 分段项行内边距（6dp × 18dp） */
    val segmentedItemPaddingVertical = 6.dp
    val segmentedItemPaddingHorizontal = 18.dp

    /** 分段项与容器壁间隙（2dp） */
    val segmentedItemGap = 2.dp

    /** 撤销条高度（StatusHost 撤销条的实际渲染高度参考；controlSpacious 同量级容器语义独立） */
    val undoBarHeight = 40.dp

    // ── 列表容量三档（容量值的域内真源：Shell 输出 5000 / 桥接日志 1000 / 会话 30）──

    /** 有界封顶容量（流式输出/日志：超限丢弃最旧 + 必显「已丢弃 N 条」） */
    const val listBoundedMax = 5000

    /** 环形缓冲容量（桥接日志台 10Hz 增量拉取场景） */
    const val listRingMax = 1000

    /** 领域列表容量（会话/run/流程等业务实体列表封顶） */
    const val listDomainMax = 30

    /**
     * toast 堆叠槽位数：连续反馈纵排封顶，第 4 条挤掉最旧。
     * Int 容量档（非 dp）——StatusSignals 挤压逻辑消费。
     */
    const val toastMaxStack = 3

    val onboardingCardMaxWidth = 560.dp

    // ── 液态玻璃（材质尺寸档；blur 档系实现自留）──
    // chrome 档玻璃恒实底；blur 只属于浮层档（sheet/thin）。

    /** 浮层玻璃 blur 半径（命令面板/导览卡——大面深 blur 分离感） */
    val glassBlurSheet = 30.dp

    /** 轻玻璃 blur 半径（状态栏/toast——小件半径大于自身无意义） */
    val glassBlurThin = 18.dp

    val glassStrokeHairline = 1.dp

    /** 浮动玻璃件距窗缘边距（浮动 chrome 的悬浮间隙；投影零档——chrome 永远零投影） */
    val glassFloatInset = 8.dp

    // ── 自绘标题栏（edge-to-edge）──

    /** 标题栏高（40dp） */
    val titleBarHeight = 40.dp

    /** 窗口控制圆点直径（12dp；命中区 = minimumInteractiveSize） */
    val trafficLightDot = 12.dp

    /**
     * 窗口控制圆点昼战底衬直径（design-doc.md §5.3#1：红绿灯三色直落昼底破图形线，圆点外加
     * 近黑底衬圆承载；12dp 点 + 每侧 2dp 边距——相邻点距 space8 下底衬圆不重叠）。
     */
    val trafficLightBackdrop = 16.dp

    /** 标题栏水平内边距（20dp） */
    val titleBarPaddingH = 20.dp

    val resizerThickness = 8.dp

    val statusSlotHeight = 42.dp

    val glassShadowElevation = 12.dp

    /** 浮层玻璃圆角值（glassSheet 20dp——自绘软影的圆角基准，与 AppShapes.glassSheet 同源） */
    val glassSheetRadius = 20.dp

    // ── aurora 四边软过渡遮罩（linear-gradient mask）──
    // 「光斑只属于内容区」的实现事实层：chrome 带（tab/状态栏/dock/inspector 边带）内零光斑。
    // begin = 透明起点（距边距离，光斑在此以内被裁）/ end = 不透明终点（软过渡带终）。

    /** 顶边遮罩：透明起点 52dp（tab 条带下）→ 不透明 76dp（24dp 软过渡） */
    val auroraMaskTopBegin = 52.dp
    val auroraMaskTopEnd = 76.dp

    /** 底边遮罩：透明起点 36dp（状态栏带上）→ 不透明 60dp */
    val auroraMaskBottomBegin = 36.dp
    val auroraMaskBottomEnd = 60.dp

    /** 左边遮罩：透明起点 80dp（dock 带右，rail 宽 68dp 贴边）→ 不透明 104dp */
    val auroraMaskLeftBegin = 80.dp
    val auroraMaskLeftEnd = 104.dp

    /** 右边遮罩：透明起点 8dp → 不透明 32dp */
    val auroraMaskRightBegin = 8.dp
    val auroraMaskRightEnd = 32.dp

    // ── 内容视口（轻量版全屏铺）──
    // 全屏铺后滚动视口延伸到玻璃 chrome 之下（玻璃才有东西可折射），
    // 内容可见区经四边 padding 让出玻璃带——穿透玻璃下的部分被 blur 折射。

    val contentViewportPaddingTop = 50.dp

    val contentViewportPaddingTopNoTabs = 8.dp

    val contentViewportPaddingStart = 80.dp

    /** 内容视口右让位：窗缘隙 8dp（inspector 悬浮形态下与窗缘语言统一，不取 16dp 通铺值） */
    val contentViewportPaddingEnd = 8.dp

    /** 内容视口底让位（space8 + 26dp 状态栏带 + space8 = 42dp） */
    val contentViewportPaddingBottom = 42.dp

    val contentFadeBand = 34.dp
}