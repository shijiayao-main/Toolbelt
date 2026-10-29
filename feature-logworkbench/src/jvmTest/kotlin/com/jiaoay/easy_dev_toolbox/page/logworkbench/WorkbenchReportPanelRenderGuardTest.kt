package com.jiaoay.easy_dev_toolbox.page.logworkbench

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工作台报告面板渲染接线面源码守护：
 *
 * VM/解析行为面已由各卡专测背书（:core EvidenceModelsTest 等）——本守护钉
 * **LogWorkbenchPage 渲染接线点**（解析面 → 渲染件的消费锚，误删/改动即红；
 * 功能面无 Compose 运行时，源码切片是渲染接线的一等守护形态——PillLabelTest/
 * NavTest substring 同族配方）。
 *
 * 守护面 = wb/xa 系全部用例（见下方 @Test 列表），逐用例锚与验收口径随用例注释。
 */
class WorkbenchReportPanelRenderGuardTest {

    @Test
    fun `xa22 workbench consumer face has no bare run literals`() {
        val source = logWorkbenchPageSource()
        listOf(
            "分析历史" to "列表语境唯一译名（XA-22：原「历史 Run」）",
            "重命名分析记录" to "重命名对话框译名（原「重命名 Run」）",
            "删除分析记录" to "删除确认译名（原「删除 Run」）",
            "切换分析记录" to "切换确认译名（原「切换 Run 视图」——同批扩面第四处）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 XA-22 译名「$needle」——$why")
        }
        listOf(
            // 「历史 Run」入禁串表：区块题已与确认卡「分析记录」语系统一为「分析历史」
            // （同页两名——Run 是内部表名泄漏，新用户需自猜两者同物）
            "历史 Run" to "区块题译名（同页五处均用「分析记录」语系——Run 裸串不得回潮）",
            "重命名 Run" to "「重命名 Run」",
            "删除 Run" to "「删除 Run」",
            "切换 Run 视图" to "「切换 Run 视图」",
            "Run「" to "确认文案前缀「Run「…」」",
        ).forEach { (needle, why) ->
            assertTrue(
                needle !in source,
                "LogWorkbenchPage 残留 Run 裸串「" + needle + "」——XA-22 acceptance：工作台消费面无 Run 裸串（" + why + "）",
            )
        }
    }

    @Test
    fun `xa22 glossary registers the translated term`() {
        // 术语表登记锚改指 docs/ 真源（style guide 的译名节）——原概念区稿面
        // 未入库且不可解析，新克隆环境必红；译名语义在 docs 真源同等成立
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = dir?.let { File(it, GLOSSARY_PATH) }
        assertTrue(file?.isFile == true, "术语表文件缺失：$GLOSSARY_PATH")
        val glossary = file.readText()
        assertTrue(
            "**分析记录**" in glossary && "XA-22" in glossary,
            "style guide XA-22 译名节必须登记「分析记录」条目（XA-22 acceptance）",
        )
    }

    @Test
    fun `wb06 file buffer chip tooltip and consume feedback`() {
        val source = logWorkbenchPageSource()
        assertTrue(
            "enableUserInput = false" !in source,
            "FileBufferRow TooltipBox 不得再设 enableUserInput=false（WB-06：hover Tooltip " +
                "哑火回潮红——KDoc 宣称 hover 显全路径须成立）",
        )
        listOf(
            "点击载入并移出暂存" to "「点击即消费出暂存」强语义必须前置说明（防误触）",
            "StatusSignals.show(\"已载入分析输入\")" to "chip 点击消费必须有 toast 回执（WB-06 acceptance）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 WB-06 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb19 empty target time shows full-scan hint via dynamic supporting text`() {
        val source = logWorkbenchPageSource()
        listOf(
            "targetTime.isBlank()" to "空时间点分支判定（动态 supportingText 接线）",
            "未填时间点——将不过滤时间范围，全量分析（较慢）" to "空值后果必须前置提示（WB-19 acceptance：全量分析提示）",
            "支持空格/T 分隔与省略毫秒，自动归一" to "非空时恢复常规说明（acceptance 第二项）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 WB-19 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb11 empty report branch has no follow-up box`() {
        val source = reportPanelSource()
        // 追问框 label 锚（label = { Text(...) } 完整形态——不受 :842「配置模型服务后
        // 可对报告追问」提示文案的子串干扰）删除后须恰 1 处（有报告分支独占——
        // 空报告分支零追问框防回潮）
        assertEquals(
            1,
            source.split("label = { Text(\"对报告追问（增量研判）\"").size - 1,
            "追问框 label 锚须恰出现 1 次（WB-11 删空分支后——有报告分支独占）",
        )
        assertTrue(
            "完成一次分析后，五部分结构报告在此呈现；可对报告继续追问" in source,
            "空报告态引导语并入 learningHint（WB-11 acceptance：引导保留）",
        )
    }

    @Test
    fun `xa11 followup button uses appbutton with disabled reason`() {
        val source = reportPanelSource()
        listOf(
            "label = \"追问\"" to "追问钮必须换 AppButton（XA-11：置灰原因 tooltip 走 disabledReason）",
            "研判运行中，结束后可追问" to "running 置灰原因（acceptance 第一项）",
            "先输入追问内容" to "空文本置灰原因（acceptance 第二项）",
            "AppButtonKind.Secondary" to "追问钮 Secondary 档（卡面配方——非主操作）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "WorkbenchReportPanel 丢失 XA-11 要素「$needle」——$why")
        }
        // 追问 OutlinedButton 面清零（Text("追问") 只允许在 AppButton label 内）——
        // 切片：整个追问钮块（label = "追问" 前 60 字符内不得有 OutlinedButton( 调用面
        val followUpBtnIdx = source.indexOf("label = \"追问\"")
        assertTrue(followUpBtnIdx > 0, "追问钮锚缺失")
        val preceding = source.substring((followUpBtnIdx - 200).coerceAtLeast(0), followUpBtnIdx)
        assertTrue(
            "OutlinedButton(" !in preceding,
            "追问钮不得回退 OutlinedButton 形态（XA-11 后 AppButton 独占）",
        )
    }

    @Test
    fun `wb21 report panel widens on wide window class`() {
        val source = logWorkbenchPageSource()
        listOf(
            "workbenchReportPanelWidthWide" to "加宽档 token 必须消费（WB-21：死档复活——400dp）",
            "currentWindowSizeClass() == WindowSizeClass.Wide" to "Wide 档判定门控（>1760dp；currentWindowSizeClass 批 6 SYS-01 同款消费先例）",
            "Dimens.workbenchReportPanelWidth" to "常规档 token 保留（非 Wide 时 320dp）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 WB-21 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb08 increment cards render one per followup section`() {
        val source = reportPanelSource()
        listOf(
            "sections.increments.forEachIndexed" to "增量段必须逐段成卡（每次追问追加一张）",
            "IncrementCard(index = index, text = increment)" to "IncrementCard 消费点（卡头时间序号）",
            "增量研判 · 追问 " to "卡头须显增量时间序号（「增量研判 · 追问 N」——真实渲染串）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "WorkbenchReportPanel 丢失 WB-08 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb09 timeline table renders via markdown table block with jump anchor`() {
        val source = reportPanelSource()
        listOf(
            "MarkdownTableBlock(" to "时间线表格必须经共享渲染件（WB-09：管道符裸文本的对读面）",
            "rows = sections.timelineRows" to "数据源 = parseReport 提取的 timelineRows",
            "onJumpToTimestamp = onJumpToTimestamp" to "行时间戳点击必须复用 onJumpToTimestamp（卡面 acceptance）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "WorkbenchReportPanel 丢失 WB-09 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb10 evidence refs render two lines with snippet readback`() {
        val source = reportPanelSource()
        listOf(
            "evidenceSnippetOf(" to "引用摘要必须经 EvidenceModels 回读纯函数（WB-10）",
            "withContext(kotlinx.coroutines.Dispatchers.IO)" to "回读文件 IO 必须下放 IO 线程（不卡主线程）",
            "ref.copy(tag = snippet.first, snippet = snippet.second)" to "回读后拼装 EvidenceRef（tag/snippet）",
            "ref.timestamp" to "每条引用两行——时间戳行（点击跳转语义不变）",
            "MaterialTheme.typography.labelSmall" to "摘要行 labelSmall（卡面配方——tag/snippet 行样式档）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "WorkbenchReportPanel 丢失 WB-10 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb02 todo board wiring collects items and passes todos to timeline`() {
        val source = logWorkbenchPageSource()
        listOf(
            "vm.todoBoard.items.collectAsState()" to "TodoBoard collect 面 = .items（todoBoard 是 TodoBoard 对象非 StateFlow）",
            "todos = todos" to "EvidenceTimeline todos 透传（空时组件内零渲染——write_todos 后即时出现）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 WB-02 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb22 empty state pick files action wires pickOpenFiles to addTargetFile`() {
        val source = logWorkbenchPageSource()
        listOf(
            "onPickFiles = {" to "空态动作锚接线（EvidenceTimeline 增参透传）",
            "pickOpenFiles(" to "选择器走共享挂起件（FileDropZone 同款消费配方——WB-20 pickLogFile 删除后此处承接）",
            "vm.addTargetFile(it.absolutePath)" to "选文件后进表单文件列表（与拖入一致——WB-22 acceptance）",
            "jumpScope.launch" to "pickOpenFiles 是 suspend fun，页面侧协程包装（复用既有 scope 无泄漏面）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 WB-22 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb22 evidence timeline empty state action label pairs with onPickFiles`() {
        // 组件面（:ui）：EmptyStateView actionLabel/onAction 成对契约——onPickFiles
        // null 时 actionLabel 同步不传（防渲染残钮）。行内搜索落地后门控扩入
        // searchQuery.isBlank()（过滤零命中的空态是「无匹配行」语义，选文件钮
        // 让位——两卡同块真实形态）
        val source = evidenceTimelineSource()
        listOf(
            "onPickFiles: (() -> Unit)? = null" to "增参带默认值（既有消费面零破坏）",
            "actionLabel = if (onPickFiles != null && searchQuery.isBlank()) \"选择日志文件\" else null" to "actionLabel 与 onPickFiles 成对契约（null 时空态零动作钮；WB-13 过滤态让位）",
            "onAction = onPickFiles" to "动作回调透传",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "EvidenceTimeline 丢失 WB-22 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb14 histogram pick and highlight self-clear wiring`() {
        // 页面侧：桶点击复用 jumpToTimestamp（滚动+高亮链）+ 高亮自清置 null
        val source = logWorkbenchPageSource()
        listOf(
            "onPickTimestamp = ::jumpToTimestamp" to "直方图桶点击必须复用 jumpToTimestamp 滚动+高亮链（WB-14 acceptance 第一项）",
            "onHighlightConsumed = { highlightTimestamp = null }" to "高亮行被点击后高亮清除（WB-14 acceptance 第二项——防引用残留误判）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 WB-14 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb14 time histogram gains tap gesture and line row gains highlight consumed hook`() {
        // 组件面（:ui）：TimeHistogram pointerInput 桶换算 + EvidenceLineRow 消费钩——
        // 直方图子系统与行渲染件自 EvidenceTimeline 拆出独立成文件，锚随迁
        val histogram = source("ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/evidence/TimeHistogram.kt")
        listOf(
            "onPickTimestamp: (String) -> Unit = {}" to "TimeHistogram 增参带默认值（MiniHistogram 纯展示面零破坏）",
            "detectTapGestures { offset ->" to "Canvas 必须追加 tap 手势（点位 → 桶索引换算）",
            "offset.x / size.width * HISTOGRAM_BUCKET_COUNT" to "点位 x → 桶索引换算（60 桶等距）",
            "bucketFirstTimestamps(lines)" to "桶 → 首条时间戳映射预构（点击时零重复扫描）",
        ).forEach { (needle, why) ->
            assertTrue(needle in histogram, "TimeHistogram 丢失 WB-14 要素「$needle」——$why")
        }
        // 桶→时间换算与 :core histogramBuckets 同源锚定（分桶参数变化须同步镜像）
        assertTrue(
            "HISTOGRAM_BUCKET_COUNT = 60" in histogram,
            "桶数常量须与 :core histogramBuckets 默认分桶数同源锚定（60 等距）",
        )
        val lineRow = source("ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/evidence/EvidenceLineRow.kt")
        listOf(
            "onHighlightConsumed: () -> Unit = {}" to "EvidenceLineRow 增参带默认值（既有消费面零破坏）",
            "if (highlighted) onHighlightConsumed()" to "clickable 展开动作内高亮消费钩（卡面配方：clickable 块内 if(highlighted) 清）",
        ).forEach { (needle, why) ->
            assertTrue(needle in lineRow, "EvidenceLineRow 丢失 WB-14 要素「$needle」——$why")
        }
    }

    @Test
    fun `xa07 evidence loading flag set around all three read coroutines`() {
        val source = logWorkbenchPageSource()
        // 三处置位对称：「= true」与「= false」各恰 3 处（初载/重读定位/证据卡点击）
        assertEquals(
            3,
            source.split("evidenceLoading = true").size - 1,
            "evidenceLoading = true 须恰 3 处（XA-07：三处读取协程前置置位——LaunchedEffect 初载/jumpToTimestamp 重读/onPickEvidence 点击）",
        )
        assertEquals(
            3,
            source.split("evidenceLoading = false").size - 1,
            "evidenceLoading = false 须恰 3 处（XA-07：readLogLinesResult 返回即置 false——对称三处）",
        )
        // loading 透传接线
        assertTrue(
            "loading = evidenceLoading" in source,
            "EvidenceTimeline loading 透传（XA-07 acceptance：切换大产物时行流顶部出现读取指示）",
        )
    }

    @Test
    fun `xa07 loading indicator renders outside lazy column`() {
        val source = evidenceTimelineSource()
        listOf(
            "loading: Boolean = false" to "增参带默认值（既有消费面零破坏）",
            "正在读取产物…" to "指示行文案（卡面原文）",
            "Dimens.progressInline" to "行内进度圈尺寸 token（14dp）",
            "Dimens.strokeThin" to "strokeWidth token（2dp）",
            "CircularProgressIndicator(" to "指示件形态（单点指示非流式内容动画——零动画铁律不受影响）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "EvidenceTimeline 丢失 XA-07 要素「$needle」——$why")
        }
        // 🔴 落位契约：指示行必须在 LazyColumn 之外（卡面「item(key="ev-loading")
        // 进 LazyColumn」会破坏 offset 物理索引契约——滚动定位全部 +1 错位）
        val loadingIdx = source.indexOf("if (loading) {")
        val lazyIdx = source.indexOf("LazyColumn(")
        assertTrue(
            loadingIdx in 0 until lazyIdx,
            "loading 指示行必须落在 LazyColumn 之外的 Column 区（标题行下方视觉等效）——插 LazyColumn 内破坏 offset 物理索引契约（XA-07 排障配方）",
        )
    }

    @Test
    fun `wb13 inline search wiring with offset-aware scroll skip`() {
        val source = logWorkbenchPageSource()
        listOf(
            "searchQuery = searchQuery" to "EvidenceTimeline 双参透传（过滤内聚组件——offset 函数签名零改）",
            "onSearchQueryChange = { searchQuery = it }" to "过滤词页面态回写（页面局部态持有）",
            "if (searchQuery.isNotBlank()) return" to "过滤态跳过滚动定位（🔴 offset 契约错位裁量：渲染行≠全量索引——高亮仍设行在过滤集内即显）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 WB-13 要素「$needle」——$why")
        }
        // 组件面：SearchField 消费 + 命中数徽标 + 清除钮 + 渲染面吃过滤纯函数
        val timeline = evidenceTimelineSource()
        listOf(
            "SearchField(" to "行内搜索复用 DS-10 批 3 统一件（卡面 notes「尚未落地」已过时——现状说明）",
            "filterEvidenceLines(evidenceLines, searchQuery)" to "过滤经纯函数内聚组件（数据面全量保留——offset 函数消费全量索引零改）",
            "items(shownLines" to "LazyColumn 渲染面吃命中行（LazyColumn 结构零新增 item——offset 契约保持）",
            "onSearchQueryChange(\"\")" to "清除钮恢复全量（acceptance 第二项）",
            "shownLines.size}/\${evidenceLines.size} 行" to "命中数徽标「N/M 行」（渲染命中数/全量数）",
            "在当前产物中搜索" to "placeholder 卡面原文（WB-13 acceptance——DS-10 批 3 统一件）",
            "无匹配行" to "过滤零命中空态文案（不误报「证据流为空」）",
        ).forEach { (needle, why) ->
            assertTrue(needle in timeline, "EvidenceTimeline 丢失 WB-13 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb15 hitl card anchors bottom right with focus and waiting hint`() {
        val source = logWorkbenchPageSource()
        listOf(
            "Box(modifier = Modifier.fillMaxSize())" to "整页 Box 包（HITL 卡叠加域——PageScaffold 变 Box 子）",
            "Alignment.BottomEnd" to "确认卡固定右下（WB-15 acceptance：不遮页头，非模态——真实落位是页底通栏非卡面所述「叠左上」）",
            // 重锚：HITL 卡宽 280→360（.wb-float
            // width 360px 逐字——hitlCardWidth 独立 token 替换 sideBarWidthDefault 借用档）
            "Dimens.hitlCardWidth" to "限宽 token（fillMaxWidth + BottomEnd 仍通栏——右下角卡须限宽；360dp 稿 .wb-float 逐字 WB-D4）",
            "Dimens.space16" to "BottomEnd padding 让位（卡面配方）",
            "gateWaiting = pendingGate != null" to "HITL 等待指示透传（紫块头等待文案门控）",
            "focusRequester = gateFocus" to "焦点宿主由调用方持有并传 GateConfirmOverlay（透传 PathConfirmCard——TonalCard 默认不可聚焦）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 WB-15 要素「$needle」——$why")
        }
        // 组件面（:ui）：PathConfirmCard focusRequester/focusable 双件 + 等待文案分支
        val card = source("ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/PathConfirmCard.kt")
        listOf(
            "focusRequester: FocusRequester? = null" to "可选焦点参数（TagsField.kt:62 同款先例；null = 既有行为零破坏）",
            ".focusRequester(focusRequester).focusable()" to "焦点双件——TonalCard 默认不可聚焦，onPreviewKeyEvent 需卡本身聚焦",
        ).forEach { (needle, why) ->
            assertTrue(needle in card, "PathConfirmCard 丢失 WB-15 要素「$needle」——$why")
        }
        // 组件面（:ui）：GateConfirmOverlay 组合期抢焦——上提后 requestFocus 内聚共享件
        val overlay = source("ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/GateConfirmOverlay.kt")
        listOf(
            "runCatching { focusRequester.requestFocus() }" to "FocusRequester 卡组合主动聚焦（Enter/Esc 即刻可用——requestFocus 未挂载抛错须 runCatching）",
            "PathConfirmCard(" to "卡本体装配（焦点宿主经共享件透传）",
        ).forEach { (needle, why) ->
            assertTrue(needle in overlay, "GateConfirmOverlay 丢失 WB-15 要素「$needle」——$why")
        }
        val timeline = evidenceTimelineSource()
        listOf(
            "gateWaiting: Boolean = false" to "EvidenceTimeline 增参带默认值（既有消费面零破坏）",
            "AiBlockHeader(lines = shownLines, gateWaiting = gateWaiting)" to "紫块头透传",
            "等待路径授权——请在确认卡裁决" to "等待授权文案（WB-15 acceptance 第二项：agent 挂起等裁决可见化）",
        ).forEach { (needle, why) ->
            assertTrue(needle in timeline, "EvidenceTimeline 丢失 WB-15 要素「$needle」——$why")
        }
    }

    @Test
    fun `wb24 ai block subtitle condensed to short constant-purple phrase`() {
        val source = evidenceTimelineSource()
        // 常驻教学长句已死——防回潮
        assertFalse(
            "关掉 AI 开关此块停更" in source,
            "教学长句不得回潮（WB-24：恒显成噪音——「关掉 AI 开关」是配置语义非本页动作）",
        )
        assertTrue(
            "AI 产物 · 恒紫标识" in source,
            "副文案必须精简为「AI 产物 · 恒紫标识」（8 字 ≤10 ✓——WB-24 acceptance 第一项；DL-M12 紫色自明）",
        )
    }

    @Test
    fun `xa21 gate card transitions animate on both consumer pages`() {
        // 上提后：过渡/末值缓存/抢焦装配收敛进 :ui 共享件 GateConfirmOverlay，
        // 两消费页只保留落位差异（工作台右下限宽浮层 / FlowPage Composer 上方裸落位）
        val overlay = source("ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/GateConfirmOverlay.kt")
        listOf(
            "fun GateConfirmOverlay(" to "共享件本体（HITL 确认覆盖层唯一装配点）",
            "AnimatedVisibility(" to "进出场过渡容器（高频注意抢占件——突现突消反差突兀）",
            "fadeIn(animationSpec = AppMotion.fast())" to "渐入走 fast 档（120ms——卡面真源；先例 ToolsPage 用 standard 档不同不冲突）",
            "slideInVertically(" to "入场滑动（AgentCenterPage/ToolsPage 先例配方 it / 16）",
            "fadeOut(animationSpec = AppMotion.fast())" to "渐出同 fast 档（120ms 渐入渐出——XA-21 acceptance 第一项）",
            "var lastGate by remember { mutableStateOf<File?>(null) }" to "末值缓存 remember（卡面 notes 原文配方）",
            "val shown = path ?: lastGate" to "退场帧渲染末值缓存（path 清 null 与动画退场时序错位防闪空）",
            "runCatching { focusRequester.requestFocus() }" to "组合期主动抢焦（Enter/Esc 即刻可用）",
        ).forEach { (needle, why) ->
            assertTrue(needle in overlay, "GateConfirmOverlay 丢失 XA-21 要素「$needle」——$why")
        }
        // 工作台侧：右下落位经 modifier 注入（BottomEnd + 限宽 + 浮层阴影——落位差异留调用方）
        val source = logWorkbenchPageSource()
        listOf(
            "GateConfirmOverlay(" to "工作台经共享件消费（过渡装配上提）",
            "visible = pendingGate != null" to "过渡可见性 = pendingGate 门控",
            "path = pendingGate" to "门路径透传（末值缓存内聚共享件）",
            "focusRequester = gateFocus" to "焦点宿主由调用方持有传入（抢焦装配内聚共享件）",
            "Alignment.BottomEnd" to "右下落位（WB-15 acceptance）留调用方 modifier",
            "Dimens.hitlCardWidth" to "限宽 token 留调用方 modifier（右下角卡须限宽）",
        ).forEach { (needle, why) ->
            assertTrue(needle in source, "LogWorkbenchPage 丢失 XA-21 要素「$needle」——$why")
        }
        // FlowPage 侧（feature-quest 模块——文件路径相对项目根可达）：经共享件消费，
        // 🔴 不迁右下（右下落位只限工作台页——FlowPage 保持 Composer 上方）
        val flow = source("feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/FlowPage.kt")
        listOf(
            "GateConfirmOverlay(" to "FlowPage 经共享件消费（两消费点均走统一过渡——卡面 proposal）",
            "visible = pendingGate != null" to "同款门控",
            "path = pendingGate" to "同款末值缓存防闪空（内聚共享件）",
            "focusRequester = gateFocus" to "同款焦点宿主传入",
        ).forEach { (needle, why) ->
            assertTrue(needle in flow, "FlowPage 丢失 XA-21 要素「$needle」——$why")
        }
        assertFalse(
            "Alignment.BottomEnd" in flow,
            "FlowPage 不得迁右下落位（WB-15 落位只限 LogWorkbenchPage——XA-21 对 FlowPage 只加过渡）",
        )
    }

    @Test
    fun `wb23 evidence title row drops duplicate filename block`() {
        // 产物名一屏三现收敛两现——标题行文件名块删除后
        // `pathFileName(activeEvidence)` 恰现 1 次（块头副行独占；文件名提取单源
        // core/data/PathDisplay——Windows 反斜杠路径也剥，锚随单源化改形不改意图）
        val source = evidenceTimelineSource()
        assertEquals(
            1,
            source.split("pathFileName(activeEvidence)").size - 1,
            "pathFileName(activeEvidence) 须恰现 1 次（WB-23：块头副行独占——标题行文件名块不得回潮）",
        )
        // 标题行剩「证据流」+ 搜索 + 徽标 + 中止（中止钮可达性不变——acceptance 第二项）
        assertTrue(
            "Text(\"证据流\"" in source,
            "证据流标题行文本保留（WB-23：标题行只留「证据流」+徽标+中止）",
        )
        // 中止钮形态回正为共享件 GhostChipButton（块头内嵌小钮档——label「中止」
        // + onClick 透传；原 .ic-btn 形态锚随共享件收敛批改形，锚随形态回正同步），
        // 可达性不变
        assertTrue(
            "GhostChipButton(" in source,
            "中止钮必须仍经共享件 GhostChipButton 渲染（形态锚）",
        )
        assertTrue(
            "onClick = onCancel" in source,
            "中止钮可达性不变（acceptance 第二项——onClick 透传锚）",
        )
    }

    @Test
    fun `d2 workbench page kdoc describes actual scaffold layout`() {
        val source = logWorkbenchPageSource()
        // 旧失真描述零残留：头注「不套 PageScaffold…Column(padding space16) +
        // PageHeader」两行与实际布局不符（实为 PageScaffold(Dense)）
        assertFalse(
            "不套 PageScaffold" in source,
            "头注旧失真字串「不套 PageScaffold」不得残留（D2：实际已套 PageScaffold density=Dense）",
        )
        assertFalse(
            "Column(padding space16) + PageHeader" in source,
            "头注旧失真字串「Column(padding space16) + PageHeader」不得残留（D2：实际骨架是 PageScaffold content）",
        )
        // 新描述锚：三栏 + PageScaffold(density=Dense)
        assertTrue(
            "density = PageDensity.Dense" in source,
            "头注必须描述 PageScaffold(density=Dense) 实际形态（D2）",
        )
        assertTrue(
            "PageScaffold" in source && "Dimens.workbenchSidePanelWidth" in source &&
                "Dimens.workbenchReportPanelWidth" in source,
            "头注必须描述三栏 token（左栏/右栏——D2 重写面）",
        )
    }

    private fun logWorkbenchPageSource(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, PAGE_PATH)
        assertTrue(file?.isFile() == true, "源码锚文件缺失：$PAGE_PATH")
        return file.readText()
    }

    private fun source(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, relativePath)
        assertTrue(file?.isFile == true, "源码锚文件缺失：$relativePath")
        return file.readText()
    }

    private fun evidenceTimelineSource(): String =
        source("ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/evidence/EvidenceTimeline.kt")

    /** 报告面板渲染簇独立成文件——渲染接线类用例锚随迁（页面装配面用例仍锚 PAGE_PATH） */
    private fun reportPanelSource(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, REPORT_PANEL_PATH)
        assertTrue(file?.isFile == true, "源码锚文件缺失：$REPORT_PANEL_PATH")
        return file.readText()
    }

    private companion object {
        const val PAGE_PATH =
            "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt"
        const val REPORT_PANEL_PATH =
            "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchReportPanel.kt"
        const val GLOSSARY_PATH =
            "docs/ui-style-guide.md"
    }
}
