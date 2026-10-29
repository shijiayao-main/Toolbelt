package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * DOC-05 报告页正文只读渲染验收（重设计批 4 · 2026-10-01）：
 *
 * 卡面 acceptance 两项 + 勘误四交互重定义配方源钉死：
 * 1. **报告卡可展开只读正文（markdown 源文本态）**：渲染面无 Compose 场景基建的
 *    直测缝 = 配方源断言（PageReport.kt 必须钉住「整卡点击 = 展开/收起 +
 *    rememberSaveable 随卡重组保持 + mono 源文本渲染 + 惰性拉取」），配
 *    [readReportBody] 行为直测（IO 拉取成功/失败两路径——runCatching 失败返
 *    null 不抛，失败文案由消费面呈现不静默）；
 * 2. **报告量 >50 时列表尾有截断说明行**：[truncationNotice] 纯函数直测（附真实
 *    总量 + MAX 值 + 工作台回看指引），配方源断言钉死「拉取块保留 totalCount +
 *    列表尾条件渲染」结构；
 * 3. **勘误四交互重定义**：整卡 clickable 的语义必须从 openInFolder 改为展开/
 *    收起，openInFolder 只出现在「打开所在目录」钮——配方源断言双向钉死（防回潮）；
 * 4. **DOC-02 查看钮语义迁移**（重设计批 7 · 2026-10-01）：「查看」从 openInFolder
 *    （开目录——文案与行为不符）迁移到 openFile（系统默认程序打开文件本体，失败
 *    降级所在目录）；openInFolder 正名为「打开所在目录」弱动作独立钮。
 * 5. **DOC-06 meta 行 pill 形态**（重设计批 7.5 · 2026-10-02 补卡）：报告卡 meta 行
 *    状态与相对时间包 PillLabel（批 3 现成件）——状态 pill 四态色对裁量 +
 *    时间 pill mute 档；DOC-04 色点断言随形态迁移（色点退役，四态语义经
 *    statusPillColors 延续）。
 */
class PageReportTest {

    // ── 1. 截断说明行（acceptance：报告量 >50 时列表尾有截断说明行）──

    @Test
    fun `truncation notice carries max total and workbench hint`() {
        val notice = truncationNotice(totalCount = 73)
        assertTrue(
            "$MAX_REPORT_CARDS" in notice && "73" in notice,
            "截断说明必须同时带 MAX 值（$MAX_REPORT_CARDS）与真实总量，实际：$notice",
        )
        assertTrue(
            "工作台" in notice,
            "截断说明必须指引工作台分析历史回看旧报告（可达性锚——旧报告不是丢失）",
        )
    }

    @Test
    fun `truncation notice echoes max value exactly at boundary plus one`() {
        // 边界口径：恰好 50 份不触发（= MAX 不是超量）；51 份起触发
        assertEquals(
            "仅显示最近 $MAX_REPORT_CARDS 份（共 51 份）——旧报告经工作台分析历史回看",
            truncationNotice(totalCount = MAX_REPORT_CARDS + 1),
            "截断说明文案形态钉死（总量插值 + 分析历史回看指引——XA-22 报告页侧译名联动）",
        )
    }

    // ── 2. 正文只读拉取（acceptance：报告卡可展开只读正文）──

    @Test
    fun `read report body loads existing file content`() = runTest {
        // 真实 IO 临时文件（非 mock——配方 = File.readText 原样，行数小秒回）
        val tmp = File.createTempFile("doc05-report", ".md")
        try {
            tmp.writeText("# 纪要\n\n## 结论\n- 正文拉取直测")
            assertEquals(
                "# 纪要\n\n## 结论\n- 正文拉取直测",
                readReportBody(tmp.absolutePath),
                "正文拉取必须返回文件全文（markdown 源文本态——消费面 mono 渲染）",
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `read report body returns null on missing file instead of throwing`() = runTest {
        // runCatching 失败返 null 不抛（配方源 WorkbenchViewModel.readReportFile 同款）——
        // 失败显错误文案由消费面承接，本函数不静默吞异常语义外的事
        assertNull(
            readReportBody("/nonexistent/doc05-missing-report.md"),
            "缺失文件必须返 null（runCatching.getOrNull）——不得向 LaunchedEffect 抛",
        )
    }

    @Test
    fun `read report body signature stays single param suspend`() {
        // 签名契约：单参 path（不引入第二参——无状态拉取，无新 VM）。Kotlin 反射对
        // suspend 函数不暴露 continuation 形参（KCallable 剥离），故 suspend 修饰由
        // 消费面 LaunchedEffect 编译钉死（非 suspend 改写即 PageReport.kt 编译失败），
        // 此处只钉显式形参面 = 恰 1 个 path。
        val parameterNames = ::readReportBody.parameters.map { it.name }
        assertEquals(
            listOf("path"),
            parameterNames.filterNotNull(),
            "readReportBody 签名必须单参 path（不引入第二参——无状态拉取）",
        )
    }

    // ── 3. 配方源断言（勘误四交互重定义 + 惰性拉取结构）──

    @Test
    fun `report card click semantics redefine to expand with view button keeping open in folder`() {
        val source = pageReportSource()
        // 整卡 clickable = 展开/收起（勘误四：原 openInFolder 点击让位）
        assertTrue(
            ".clickable { expanded = !expanded }" in source,
            "整卡点击必须改展开/收起（DOC-05 勘误四交互重定义）——不得再是 openInFolder",
        )
        // clickable 行本身不得挂 openInFolder（勘误四——点击语义已让位给展开）
        val clickableLine = source.lines().firstOrNull { ".clickable" in it }
        assertTrue(
            clickableLine != null && "openInFolder" !in clickableLine,
            "整卡 clickable 行不得再挂 openInFolder（勘误四——点击语义已让位给展开），实际：$clickableLine",
        )
        // DOC-02（重设计批 7）+ 终验 review P2 按钮分层：pri 主钮「查看报告」= openFile
        // （打开文件本体，失败降级所在目录）——原 TextButton 等权形态随分层退役
        assertTrue(
            "onClick = { DesktopUtils.openFile(path) }" in source,
            "主钮必须打开文件本体（DOC-02 语义迁移——openFile，失败降级所在目录）",
        )
        // openInFolder 正名为「打开所在目录」弱动作独立 gh 次钮（文件管理器诉求保留）
        assertTrue(
            "label = \"打开所在目录\"" in source &&
                "onClick = { DesktopUtils.openInFolder(path) }" in source,
            "「打开所在目录」弱动作 gh 钮必须承接 openInFolder（DOC-02：目录诉求正名独立钮）",
        )
        // 终验 P2 防回潮：报告卡不得再出现 TextButton 等权按钮组（pri/gh 分层钉死）
        assertFalse(
            "TextButton(onClick = { DesktopUtils.openFile(path) }) { Text(\"查看\") }" in source,
            "报告卡按钮组不得回潮 TextButton 等权形态（终验 P2：pri 主钮 AppButton + gh 次钮分层）",
        )
    }

    @Test
    fun `view button opens report file itself with open file semantics`() {
        // DOC-02 · 重设计批 7：「查看」钮语义迁移的三文件链守护（PageReport + HUD chip + VM 消费点）
        val source = pageReportSource()
        // 「查看」钮不得再是 openInFolder（语义迁移防回潮）
        assertTrue(
            "TextButton(onClick = { DesktopUtils.openInFolder(path) }) { Text(\"查看\") }" !in source,
            "「查看」钮不得再开目录（DOC-02：文案「查看」承诺打开报告本体——openInFolder 已正名给「打开所在目录」）",
        )
        // bodyError 失败文案随语义改写（正文读取失败引导经「查看」开本体 + 目录弱动作）
        assertTrue(
            "或「打开所在目录」用文件管理器查看" in source,
            "正文读取失败文案必须引导「查看」开本体 + 「打开所在目录」文件管理器兜底（DOC-02 随手改面）",
        )
        // 头注 KDoc 随语义同步（失真注释清理——:50 区域「文件管理器打开」宣称已随迁移改写）
        assertTrue(
            "「查看」经系统默认程序打开报告文件本体" in source,
            "头注 KDoc 必须宣称文件本体语义（DOC-02 KDoc 四处同步——防失真回潮）",
        )
    }

    @Test
    fun `palette hud artifact chip routes text log artifacts to viewer with open file fallback`() {
        // 产物 chip 动作分流：文本日志形态产物写 LogViewerRequest 槽（请求路径 =
        // 产物 path 单源，宿主挂载应用内查看器）；非文本产物零回归仍走 openFile
        val source = paletteHudSource()
        assertTrue(
            "LogViewerRequest.offer(path)" in source,
            "HUD 文本日志产物 chip 必须写查看器请求槽（请求路径 = 产物 path——漏接 = 点击仍出应用进系统编辑器）",
        )
        assertTrue(
            "isTextLogArtifact(path)" in source,
            "HUD chip 动作分流必须经 isTextLogArtifact 判定表（产物类型→动作收敛单张表，勿散落扩展名判断）",
        )
        assertTrue(
            "DesktopUtils.openFile(path)" in source,
            "HUD 非文本产物 chip 必须保留 openFile（零回归——打开文件本体，失败降级所在目录）",
        )
        assertTrue(
            "contentDescription = \"打开产物\"" in source,
            "chip 的 contentDescription 必须保持「打开产物」（无障碍语义随行为同步——视觉文案保持文件名）",
        )
        assertTrue(
            "DesktopUtils.openInFolder" !in source,
            "HUD chip 不得残留 openInFolder 消费点（目录诉求已由 openFile 内部降级承接）",
        )
    }

    @Test
    fun `workbench view model open current report consumes open file`() {
        // DOC-02 ④（WB-17 并入消费点）：LogWorkbenchPage「打开报告文件」钮的 VM 侧
        // 改调 openFile——文案「打开报告文件」承诺打开文件本体
        val source = workbenchViewModelSource()
        assertTrue(
            "DesktopUtils.openFile(path)" in source,
            "openCurrentReport 必须改调 openFile（DOC-02/WB-17 合并消费点——打开报告文件本体）",
        )
        assertTrue(
            "DesktopUtils.openInFolder" !in source,
            "WorkbenchViewModel 不得残留 openInFolder 消费点（openCurrentReport 已迁移 openFile）",
        )
    }

    // ── 4. DOC-06 meta 行 pill 形态（重设计批 7.5 补卡；DOC-04 四态语义随形态迁移）──

    @Test
    fun `report card meta row renders status and time pills via pill label`() {
        val source = pageReportSource()
        // 两 pill 经共享件 PillLabel（DS-11 现成件——卡面 proposal：状态与相对时间包 PillLabel）
        assertTrue(
            "import com.jiaoay.easy_dev_toolbox.widget.PillLabel" in source,
            "报告卡 meta 行必须经共享件 PillLabel 渲染（DOC-06：批 3 现成件收编，禁私有散写 pill）",
        )
        // 状态 pill：text = statusText(run.status)，色对来自 statusPillColors 四态裁量
        assertTrue(
            "statusPillColors(run.status)" in source,
            "状态 pill 色对必须经 statusPillColors 四态 when 裁量（DOC-06：色语义与 DOC-04 四态一致）",
        )
        // 时间 pill：mute 档 surfaceDeep 底（卡面 proposal：时间 pill mute 档）
        assertTrue(
            "surfaceDeep" in source,
            "时间 pill 必须 mute 档 surfaceDeep 底（DOC-06：.pill.mute——onSurfaceVariant 字/surfaceDeep 底）",
        )
        assertTrue(
            "formatRelativeTime(run.updatedAt)" in source,
            "时间 pill 必须显相对时间（DOC-06：稿面「3 小时前」相对语言，WB-12 meta 同款口径）",
        )
        // 旧 6dp 色点形态随 pill 化退役（DOC-04 色点断言随形态迁移——色点不再出现在 meta 行）
        assertFalse(
            ".size(Dimens.dotIndicatorSize)" in source,
            "状态色点形态必须随 pill 化退役（DOC-06：DOC-04 色点断言随形态迁移——色点不再出现在报告卡）",
        )
        // run 标题信息保留（pill 化只换形态不丢内容——原 meta 串「title · time · status」的 title）
        assertTrue(
            "run.title" in source,
            "meta 行必须保留 run 标题文本（pill 化只换形态不丢内容——title 不得随形态迁移丢失）",
        )
    }

    @Test
    fun `status pill colors maps four states to semantic palette`() {
        val source = pageReportSource()
        // statusPillColors 函数体切片：四态 when 全枚举（RUNNING=primary 微洗底 / COMPLETED=
        // success 语义对 / FAILED=error 容器对 / ABORTED=surfaceDeep mute 档）
        val whenBody = source.substringAfter("private fun statusPillColors", "")
        assertTrue(whenBody.isNotEmpty(), "statusPillColors 函数锚缺失（重命名须同步本守护）")
        listOf(
            "RunStore.RunStatus.RUNNING -> MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.selectedWash) to" to
                "RUNNING 态 = primary×selectedWash 微洗底（DL-M11 selected 档——进行时态，ConfigFieldRenderer「已改 N」同配方）",
            "RunStore.RunStatus.COMPLETED -> LocalEasyToolboxColors.current.successContainer to" to
                "COMPLETED 态 = successContainer 直出（DL-M10 语义 container 对）",
            "RunStore.RunStatus.ABORTED -> LocalToolboxSurfaceColors.current.surfaceDeep to" to
                "ABORTED 态 = surfaceDeep mute 档（用户主动中止非异常——弱化呈现）",
            "RunStore.RunStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to" to
                "FAILED 态 = errorContainer 直出（DL-M10 语义 container 对）",
        ).forEach { (needle, why) ->
            assertTrue(needle in whenBody, "statusPillColors 四态映射缺「$needle」——$why（DOC-06）")
        }
    }

    // （原「statusPillColors 与 LogWorkbenchPage.statusColor 双写互锚」用例随 statusColor
    //   死代码删除退役——工作台侧色点四态无渲染消费点，pill 四态映射由上一用例全分支钉死。）

    // ── 5. DOC-06 相对时间纯函数（meta pill 时间档直测缝）──

    @Test
    fun `relative time buckets match design language meta wording`() {
        val now = 1_000_000_000_000L
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        assertEquals("刚刚", formatRelativeTime(now - 30_000L, now), "1 分钟内显「刚刚」")
        assertEquals("5 分钟前", formatRelativeTime(now - 5 * minute, now), "1 小时内显「N 分钟前」")
        assertEquals("3 小时前", formatRelativeTime(now - 3 * hour, now), "1 天内显「N 小时前」（稿面 meta 语言）")
        assertEquals("2 天前", formatRelativeTime(now - 2 * day, now), "7 天内显「N 天前」")
        // 7 天外回落绝对日期（相对语言失效——不可辨近远时精确优先）；
        // now=1e12 epoch ≈ 2001-09-09，30 天前 = 2001-08-10（任意时区偏移不跨日）
        assertEquals(
            "2001-08-10",
            formatRelativeTime(now - 30 * day, now),
            "7 天外回落 yyyy-MM-dd 绝对日期（相对语言失效时精确优先）",
        )
    }

    // ── 5b. 状态显示名与绝对时间纯函数（报告卡卡头行口径）──

    @Test
    fun `report page status and time formatting match workbench`() {
        // 纯函数直测缝（PageReport.kt 同包 internal）：状态显示名与 LogWorkbench
        // 左栏列表口径一致（statusLabel 同款映射——statusText 单源委托 RunStatus.label）
        assertEquals("运行中", statusText(RunStore.RunStatus.RUNNING))
        assertEquals("已完成", statusText(RunStore.RunStatus.COMPLETED))
        assertEquals("已中止", statusText(RunStore.RunStatus.ABORTED))
        assertEquals("失败", statusText(RunStore.RunStatus.FAILED))
        // 时间格式固定形态（yyyy-MM-dd HH:mm——epoch 0 = 1970-01-01 00:00 起步，时区偏移不破形态）
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(formatTime(0L)),
            "formatTime 应为 yyyy-MM-dd HH:mm 形态，实际：${formatTime(0L)}",
        )
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(
                formatTime(1_758_000_000_000L),
            ),
            "formatTime 任意时刻均为 yyyy-MM-dd HH:mm 形态",
        )
    }

    // ── 6. XA-22 报告页侧（truncationNotice「run 列表」→「分析历史」译名联动）──

    @Test
    fun `truncation notice uses analysis history term without raw run word`() {
        // XA-22（重设计批 7 · 报告页侧）：报告页用户可见面「run 列表」与工作台侧译名
        // 「分析历史」单源联动（LogWorkbenchPage RunHistoryList 标题同名）——防两页两名
        val source = pageReportSource()
        // truncationNotice 函数体切片：用户可见文案不得再含「run 列表」裸串
        val noticeBody = source.substringAfter("internal fun truncationNotice", "")
        assertTrue(noticeBody.isNotEmpty(), "truncationNotice 函数锚缺失（重命名须同步本测试）")
        assertTrue(
            "工作台分析历史回看" in noticeBody,
            "截断说明必须指引「工作台分析历史」（XA-22 报告页侧：与工作台 RunHistoryList 标题译名单源联动）",
        )
        assertFalse(
            "run 列表" in noticeBody,
            "truncationNotice 不得残留「run 列表」中英混排裸串（XA-22：报告页消费面与工作台译名一致）",
        )
    }

    @Test
    fun `expand state survives recomposition via remember saveable`() {
        val source = pageReportSource()
        assertTrue(
            "rememberSaveable(run.id)" in source,
            "折叠/展开态必须 rememberSaveable 承载（卡面 acceptance：随卡重组保持）",
        )
        assertTrue(
            "LaunchedEffect(path)" in source,
            "正文必须惰性拉取（LaunchedEffect 挂展开分支——首次展开才读文件）",
        )
        assertTrue(
            "runCatching { File(path).readText() }.getOrNull()" in source,
            "正文拉取必须是 runCatching + readText 配方（WorkbenchViewModel 同款三行复刻）",
        )
        assertTrue(
            "DesktopTypography.mono" in source,
            "正文渲染必须 mono 档（markdown 源文本态——不做 md 完整解析）",
        )
    }

    @Test
    fun `report list keeps total count and renders truncation notice at tail`() {
        val source = pageReportSource()
        // 拉取块保留 totalCount（勘误：现 take 后原始量丢弃是本卡修复对象）
        assertTrue(
            "totalCount = all.size" in source && "all.take(MAX_REPORT_CARDS)" in source,
            "拉取块必须先保留原始量再截取（take 后 totalCount 不丢）",
        )
        assertTrue(
            "if (totalCount > MAX_REPORT_CARDS)" in source,
            "列表尾必须条件渲染截断说明（>MAX 才出现——恰好 50 份不触发）",
        )
        assertTrue(
            "truncationNotice(totalCount)" in source,
            "截断说明行文案必须单源 truncationNotice 纯函数（本测试直测缝）",
        )
    }

    // ── 7. 聚焦槽一次性语义（palette 未读产物行 → 报告卡直达载荷面）──

    @Test
    fun `report focus request is a one shot payload slot`() {
        ReportFocusRequest.clearForTest()
        try {
            assertNull(ReportFocusRequest.pending.value, "空槽初值 = null（无待处理聚焦）")
            ReportFocusRequest.offer("run-focus-1")
            assertEquals("run-focus-1", ReportFocusRequest.pending.value, "offer 后 pending 载荷 = runId")
            assertEquals("run-focus-1", ReportFocusRequest.consume(), "consume 取走载荷")
            assertNull(ReportFocusRequest.pending.value, "consume 读即清（一次性语义——重开不重放旧载荷）")
            assertNull(ReportFocusRequest.consume(), "重复 consume 落空（槽已清）")
        } finally {
            ReportFocusRequest.clearForTest()
        }
    }

    // ── 源码锚 ──

    private fun pageReportSource(): String {
        val file = File(locateProjectRoot(), PAGE_REPORT_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$PAGE_REPORT_PATH（重命名/移动后请同步本测试）")
        return file.readText()
    }

    private companion object {
        const val PAGE_REPORT_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PageReport.kt"
        const val PALETTE_HUD_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteHud.kt"
        const val WORKBENCH_VIEW_MODEL_PATH =
            "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/WorkbenchViewModel.kt"
    }

    private fun paletteHudSource(): String {
        val file = File(locateProjectRoot(), PALETTE_HUD_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$PALETTE_HUD_PATH（重命名/移动后请同步本测试）")
        return file.readText()
    }

    private fun workbenchViewModelSource(): String {
        val file = File(locateProjectRoot(), WORKBENCH_VIEW_MODEL_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$WORKBENCH_VIEW_MODEL_PATH（重命名/移动后请同步本测试）")
        return file.readText()
    }
}
