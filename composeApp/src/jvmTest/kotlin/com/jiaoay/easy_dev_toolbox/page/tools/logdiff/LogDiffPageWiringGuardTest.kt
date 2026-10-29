package com.jiaoay.easy_dev_toolbox.page.tools.logdiff

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import com.jiaoay.log.LogDiff
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 日志对比页装配面源码锚守护：VM 行为由 [LogDiffViewModelTest] 断言；本测试扫
 * 源码钉死「工具」聚合页第 6 签的 UI 装配契约（防接线被改回静默/无门禁形态）：
 * 1. 双槽拖放区均挂 busy 门禁 + 双文件接收回调（FileDropZone 两道回执口径）；
 * 2. 归一化开关消费 VM 状态（checked/onCheckedChange 双锚）；
 * 3. 缺一拒收横幅可手动清除（StatusBanner Error + 清除该提示）；
 * 4. degraded 明示横幅（整体替换语义 + 归一化建议 + 引擎上限常量单源引用）；
 * 5. hunk 渲染消费 [com.jiaoay.log.LogDiff.LineKind] 且 LazyColumn 有界
 *    （本页处于宿主 verticalScroll 容器内，无界 LazyColumn 直接抛异常）；
 * 6. 导出按钮消费 VM 导出动作；
 * 7. ToolsPage 第 6 签接线（when 分支 + LogDiffPageContent 直达）；
 * 8. 差异行可点且接 VM 查看器跳转动作（漏 wire = 点击静默 no-op）+
 *    [rowsOf] 双侧行号推进纯函数直测（行号语义见 LogDiffPage.kt KDoc）；
 * 9. hunk 巡检钮接 VM 推进/回退动作 + 定位走现有结果区 LazyColumn 的即时
 *    scrollToItem（定位容器不得另起新容器、滚动零动画配方）；
 * 10. [hunkFirstRowIndices] 扁平映射纯函数直测（hunk 首行下标规律漂移即巡检滚错行）；
 * 11. 两向模式选择器走 SegmentedControl 并接 VM setMode（页内域切换唯一形态）；
 * 12. 归一化开关仅行级模式可见（[normalizeSwitchVisible] 纯函数直测 + 模式门源码锚——
 *     分布模式无归一化开关）；
 * 13. 两模式共用同一双槽 busy 门禁（running 真源 = 行级 Running ∥ 分布 Running）；
 * 14. 分布对照面板消费段 1 [com.jiaoay.log.LogStatsCompare] 纯函数结果 + 未识别差值
 *     两侧分列（禁归因）+ E/F 增量单列 + 差值带符号呈现走 [formatDelta] 单源。
 *
 * 扫盘型测试会被 build cache 假绿：验收须
 * `./gradlew :composeApp:jvmTest --rerun-tasks --no-build-cache`。
 */
class LogDiffPageWiringGuardTest {

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

    private fun anchorSource(relPath: String): String {
        val file = File(locateProjectRoot(), relPath)
        assertTrue(file.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return stripComments(file.readText())
    }

    private fun pageCode(): String = anchorSource(PAGE_PATH)

    @Test
    fun `both drop slots carry busy gate and files callback`() {
        val code = pageCode()
        val missing = buildList {
            if ("busy = running" !in code) add("双槽未挂 busy 门禁（对比进行中拖放区须拒收换文件）")
            if ("onFilesSelected = viewModel::onFilesSelected" !in code) {
                add("拖放区未接 VM 双文件接收回调")
            }
            if ("slot = LogDiffViewModel.Slot.OLD" !in code) {
                add("基准槽（OLD）拖放区缺失")
            }
            if ("slot = LogDiffViewModel.Slot.NEW" !in code) {
                add("对比槽（NEW）拖放区缺失")
            }
        }
        if (missing.isNotEmpty()) {
            fail("日志对比页双槽装配残缺：\n${missing.joinToString("\n")}")
        }
    }

    @Test
    fun `normalize switch consumes view model state`() {
        val code = pageCode()
        val missing = buildList {
            if ("checked = normalize" !in code) add("归一化开关未消费 VM normalize 状态")
            if ("onCheckedChange = viewModel::setNormalize" !in code) add("归一化开关拨动未接 VM")
            if ("时间戳归一化对齐" !in code) add("归一化开关文案缺失（与 degraded 建议文案同术语系）")
        }
        if (missing.isNotEmpty()) {
            fail("归一化开关接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    @Test
    fun `missing-file rejection banner is dismissible and start button wired`() {
        val code = pageCode()
        val missing = buildList {
            if ("onClick = viewModel::startDiff" !in code) add("「开始对比」未接 VM 发起动作")
            if ("viewModel::dismissInputError" !in code) add("缺一拒收横幅缺「清除该提示」钮（就地横幅一律可手动关闭）")
        }
        if (missing.isNotEmpty()) {
            fail("缺一拒收面接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    @Test
    fun `degraded banner states replacement semantics and normalize advice`() {
        val code = pageCode()
        val missing = buildList {
            if ("result.degraded" !in code) add("结果区未消费 degraded 标记（引擎超限退化契约必须明示）")
            if ("整体替换展示" !in code) add("degraded 横幅缺「整体替换展示」语义")
            if ("建议开启「时间戳归一化对齐」后重新对比" !in code) {
                add("degraded 横幅缺归一化建议（未开归一化时的下一步指引）")
            }
            if ("LogDiff.MAX_ALIGN_EDITS" !in code) {
                add("对齐上限数值未引用引擎公开常量（上限单源，硬编码即漂移）")
            }
        }
        if (missing.isNotEmpty()) {
            fail("degraded 明示面接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    @Test
    fun `hunk rendering consumes line kinds in bounded lazy column`() {
        val code = pageCode()
        val missing = buildList {
            if ("LogDiff.LineKind.REMOVED" !in code || "LogDiff.LineKind.ADDED" !in code) {
                add("行级渲染未消费引擎 LineKind（增删行必须有着色区分）")
            }
            if ("heightIn(max = Dimens.consoleHeightMax)" !in code) {
                add("hunk LazyColumn 须 heightIn 有界（宿主 verticalScroll 容器内无界即抛异常）")
            }
            if ("TonalCard" !in code) add("结果容器未走 TonalCard（卡片统一配方）")
        }
        if (missing.isNotEmpty()) {
            fail("hunk 渲染面接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    @Test
    fun `export button consumes view model export action`() {
        val code = pageCode()
        val missing = buildList {
            if ("onClick = viewModel::exportDiff" !in code) add("导出按钮未接 VM 导出动作")
            if ("导出 diff 文件" !in code) add("导出按钮文案缺失")
            if ("exportReceipt" !in code) add("导出回执未渲染（产物路径须可见）")
        }
        if (missing.isNotEmpty()) {
            fail("导出面接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    /** 「送工作台分析」直投接线（Tag 过滤/XLog 页 DeepAnalyzeHook 同构——五产线同链后 diff 不再是缺口） */
    @Test
    fun `workbench analyze button is wired via sendDiffToWorkbench action`() {
        val code = pageCode()
        val missing = buildList {
            if ("onClick = viewModel::sendDiffToWorkbench" !in code) {
                add("「送工作台分析」未接 VM 动作（漏 wire = 点击静默 no-op）")
            }
            if ("送工作台分析" !in code) add("「送工作台分析」按钮文案缺失")
        }
        if (missing.isNotEmpty()) {
            fail("送工作台分析面接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    @Test
    fun `tools page sixth tab dispatches to diff content`() {
        val toolsSource = anchorSource(TOOLS_PAGE_PATH)
        val missing = buildList {
            if ("LogDiffPageContent()" !in toolsSource) {
                add("ToolsPage when 分发缺日志对比签（LogDiffPageContent 未接线）")
            }
            if ("4 -> ArchivePage(embedded = true)" !in toolsSource) {
                add("解压签必须保持显式分支 4（第 6 签不得再靠 else 兜底承接既有页）")
            }
        }
        if (missing.isNotEmpty()) {
            fail("ToolsPage 第 6 签接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    @Test
    fun `diff line rows are clickable and wired to the viewer jump action`() {
        val code = pageCode()
        val missing = buildList {
            if (".clickable(" !in code) add("差异行未挂 clickable（点击跳查看器不可达）")
            if ("viewModel.openDiffLineInViewer" !in code) {
                add("行级点击未接 VM 查看器跳转动作（漏 wire = 点击静默 no-op）")
            }
            if ("LogDiffViewModel.Slot.OLD" !in code || "LogDiffViewModel.Slot.NEW" !in code) {
                add("侧别解析未覆盖双槽（删/上下文行跳基准侧、增行跳对比侧）")
            }
        }
        if (missing.isNotEmpty()) {
            fail("差异行点击接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    /**
     * [rowsOf] 双侧行号推进直测（unified 双坐标推进语义）：
     * 自窗口 0 基起点起，上下文行双侧同增、删行只增基准侧（对比侧为 null）、
     * 增行只增对比侧（基准侧为 null）；行号为 1 基原文件行号（hunk 起点加一）。
     */
    @Test
    fun `hunk rows advance both-side 1-based line numbers per line kind`() {
        val hunk = LogDiff.Hunk(
            oldStart = 4,
            oldLines = 5,
            newStart = 4,
            newLines = 5,
            lines = listOf(
                LogDiff.HunkLine(LogDiff.LineKind.CONTEXT, "ctx-head"),
                LogDiff.HunkLine(LogDiff.LineKind.REMOVED, "gone"),
                LogDiff.HunkLine(LogDiff.LineKind.ADDED, "fresh"),
                LogDiff.HunkLine(LogDiff.LineKind.CONTEXT, "ctx-tail"),
            ),
        )
        val lines = rowsOf(hunk).filterIsInstance<DiffRow.Line>()
        assertEquals(4, lines.size, "hunk 行须逐行展开为渲染行")
        assertEquals(5 to 5, lines[0].oldLineNumber to lines[0].newLineNumber, "首行行号 = 窗口起点 + 1")
        assertEquals(6 to null, lines[1].oldLineNumber to lines[1].newLineNumber, "删行只占基准侧")
        assertEquals(null to 6, lines[2].oldLineNumber to lines[2].newLineNumber, "增行只占对比侧")
        assertEquals(7 to 7, lines[3].oldLineNumber to lines[3].newLineNumber, "上下文行双侧行号同增推进")
    }

    @Test
    fun `hunk navigation buttons are wired with immediate scroll positioning`() {
        val code = pageCode()
        val missing = buildList {
            if ("viewModel::goToPrevHunk" !in code) add("「上一处」未接 VM 巡检回退动作（漏 wire = 点击静默 no-op）")
            if ("viewModel::goToNextHunk" !in code) add("「下一处」未接 VM 巡检推进动作")
            if ("rememberLazyListState()" !in code) add("结果区未挂 LazyListState（巡检定位无状态可滚）")
            if ("listState.scrollToItem(" !in code) {
                add("巡检定位未走结果区 LazyColumn 即时 scrollToItem（滚动零动画配方；定位容器须为现有结果区而非新容器）")
            }
            if ("hunkFirstRowIndices(" !in code) add("巡检定位未消费 hunk 首行下标映射（映射漂移即滚错行）")
        }
        if (missing.isNotEmpty()) {
            fail("hunk 巡检接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    /**
     * [hunkFirstRowIndices] 扁平映射直测（巡检定位锚）：hunk i 首行下标 = 前序
     * hunk 展开行数累计和（每 hunk 恒「1 头行 + lines.size 行」），且映射指向的
     * 扁平行恰为该 hunk 的头行——与 [rowsOf] 展开产物交叉验证，两头对拍防漂移。
     */
    @Test
    fun `hunk first row indices map to each hunk header in flattened rows`() {
        val hunks = listOf(
            LogDiff.Hunk(
                oldStart = 0,
                oldLines = 1,
                newStart = 0,
                newLines = 1,
                lines = listOf(LogDiff.HunkLine(LogDiff.LineKind.ADDED, "fresh")),
            ),
            LogDiff.Hunk(
                oldStart = 5,
                oldLines = 3,
                newStart = 5,
                newLines = 3,
                lines = listOf(
                    LogDiff.HunkLine(LogDiff.LineKind.CONTEXT, "ctx"),
                    LogDiff.HunkLine(LogDiff.LineKind.REMOVED, "gone"),
                    LogDiff.HunkLine(LogDiff.LineKind.ADDED, "new"),
                    LogDiff.HunkLine(LogDiff.LineKind.CONTEXT, "ctx2"),
                ),
            ),
            LogDiff.Hunk(
                oldStart = 12,
                oldLines = 2,
                newStart = 12,
                newLines = 0,
                lines = listOf(
                    LogDiff.HunkLine(LogDiff.LineKind.REMOVED, "old-1"),
                    LogDiff.HunkLine(LogDiff.LineKind.REMOVED, "old-2"),
                ),
            ),
        )
        val indices = hunkFirstRowIndices(hunks)
        assertEquals(hunks.size, indices.size, "映射表须与 hunk 序列逐项对应")

        val flat = hunks.flatMap(::rowsOf)
        hunks.forEachIndexed { i, hunk ->
            // 规律直断：首行下标 = 前序 hunk 展开行数累计和（1 头行 + lines 数）
            assertEquals(
                hunks.take(i).sumOf { 1 + it.lines.size },
                indices[i],
                "hunk $i 首行下标须为前序 hunk 展开行数累计和",
            )
            // 交叉对拍：映射指向的扁平行恰为该 hunk 的头行（头文本与 rowsOf 产头同源）
            val row = flat[indices[i]]
            assertTrue(row is DiffRow.Header, "hunk $i 映射指向行须是头行（实际 ${row::class.simpleName}）")
            assertEquals(
                "@@ -${hunk.oldStart + 1},${hunk.oldLines} +${hunk.newStart + 1},${hunk.newLines} @@",
                row.text,
                "hunk $i 头行文本须与 rowsOf 展开头一致",
            )
        }
    }

    // ── 两向模式（分布对照）装配面 ─────────────────────────────

    @Test
    fun `mode selector is a segmented control wired to vm mode action`() {
        val code = pageCode()
        val missing = buildList {
            if ("SegmentedControl(" !in code) add("两向模式选择器须走 SegmentedControl（页内域切换唯一形态）")
            if ("行级对比" !in code || "分布对照" !in code) add("模式选项文案缺失（行级对比/分布对照）")
            if ("viewModel.setMode(" !in code) add("模式选择器未接 VM setMode 动作（漏 wire = 切换静默 no-op）")
            if ("LogDiffViewModel.DiffMode.LINE" !in code || "LogDiffViewModel.DiffMode.DISTRIBUTION" !in code) {
                add("结果区未按两向模式分流渲染")
            }
            if ("viewModel.compareState.collectAsState()" !in code) {
                add("页面未收集分布对照态（分立存储的消费面缺席）")
            }
        }
        if (missing.isNotEmpty()) {
            fail("两向模式选择器接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    /** 「分布模式无归一化开关」断言：纯函数直测 + 页面开关挂模式门内的源码锚 */
    @Test
    fun `normalize switch is visible only in line mode`() {
        assertTrue(normalizeSwitchVisible(LogDiffViewModel.DiffMode.LINE), "行级模式须可见归一化开关")
        assertFalse(
            normalizeSwitchVisible(LogDiffViewModel.DiffMode.DISTRIBUTION),
            "分布模式不得出现归一化开关（归一化是行级比对键开关，块级统计不比行）",
        )
        val code = pageCode()
        val gate = "if (normalizeSwitchVisible(mode)) {"
        assertTrue(gate in code, "归一化开关须挂在 normalizeSwitchVisible 模式门内")
        assertTrue(
            code.indexOf(gate) < code.indexOf("AppSwitchRow("),
            "AppSwitchRow 须在模式门内渲染（门后组合）",
        )
    }

    @Test
    fun `both modes share the dual-slot busy gate fed by both running states`() {
        val code = pageCode()
        val missing = buildList {
            if ("busy = running" !in code) add("双槽 busy 门禁缺失（在途拒收换文件）")
            if ("diffState is LogDiffViewModel.DiffUiState.Running" !in code) {
                add("busy 真源缺行级 Running 判据")
            }
            if ("compareState is LogDiffViewModel.CompareUiState.Running" !in code) {
                add("busy 真源缺分布 Running 判据（两模式须共用同一双槽门禁）")
            }
        }
        if (missing.isNotEmpty()) {
            fail("双槽门禁两向共用接线残缺：\n${missing.joinToString("\n")}")
        }
    }

    @Test
    fun `distribution panel renders compare result with unparsed deltas per side`() {
        val code = pageCode()
        val missing = buildList {
            if ("LogStatsCompare.CompareResult" !in code) {
                add("分布面板未消费段 1 对照纯函数结果（后续工具面须复用同一函数，防第三套对照逻辑）")
            }
            if ("TonalCard" !in code) add("分布对照面板未走 TonalCard（卡片统一配方）")
            if ("E/F 增量" !in code) add("E/F 增量未单列（「环境变坏」第一信号不得混排其余档）")
            if ("baselineUnparsedTagBlocks" !in code || "currentUnparsedTagBlocks" !in code) {
                add("未识别 Tag 差值未两侧分列呈现（禁归因契约）")
            }
            if ("（不归因）" !in code) add("未识别差值行缺「不归因」口径标注")
            if ("formatDelta(" !in code) add("差值带符号呈现未走 formatDelta 单源")
        }
        if (missing.isNotEmpty()) {
            fail("分布对照面板装配残缺：\n${missing.joinToString("\n")}")
        }
    }

    /** [formatDelta] 带符号差值呈现直测（正带 +、负原样、零平文不猜方向） */
    @Test
    fun `formatDelta signs positive deltas and keeps zero and negatives plain`() {
        assertEquals("+2", formatDelta(2))
        assertEquals("-4", formatDelta(-4))
        assertEquals("0", formatDelta(0))
    }

    private companion object {
        const val PAGE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/logdiff/LogDiffPage.kt"
        const val TOOLS_PAGE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/ToolsPage.kt"
    }
}
