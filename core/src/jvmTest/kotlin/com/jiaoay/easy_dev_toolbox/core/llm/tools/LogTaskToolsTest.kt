// 2026-09-19 P1 平台地基批 A1：LogTaskTools（桥接五件下沉 :core 后的结构化平台工具）守护。
//
// 契约（从 BridgeLogToolsTest 平移，断言逐字兼容 + 新增结构化断言）：
// 1. 围栏：只允许操作初始允许集（引用文件链）内的路径 + 工具产物自动登记；
//    未命中围栏返回错误文案（任务不崩——文案是 LLM 的工具反馈）；
// 2. decrypt_log 私钥缺省：如实告知（不抛、不伪造）；
// 3. filter_by_tag / split_by_size：ToolsResult 语义透传（产物路径返回 + 登记）；
// 4. probe_file：判型摘要（非 xlog ⇒ 否）；
// 5. 结构化三段式：ToolResult.Ok.artifacts 精确产物路径（P1 新增能力面）；
// 6. fenceHint 参数化：桥接文案变体注入后拒绝文案逐字等于桥接旧 DENIED_HINT。
package com.jiaoay.easy_dev_toolbox.core.llm.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LogTaskToolsTest {

    private val workDir = File(System.getProperty("java.io.tmpdir"), "log-task-tools-${System.nanoTime()}")
        .apply { mkdirs(); deleteOnExit() }

    private fun fenceWith(vararg files: File): ToolExecutionContext = ToolExecutionContext(files.toList())

    /** sink 收集器：结构化结果断言用 */
    private class CollectingSink : ToolResultSink {
        val results = mutableListOf<Pair<String, ToolResult>>()
        override fun onResult(toolName: String, result: ToolResult) {
            results += toolName to result
        }
    }

    // ── 围栏（文案逐字兼容旧 BridgeLogToolsTest） ──

    @Test
    fun `围栏拒绝未登记路径`() = runBlocking {
        val inside = File(workDir, "inside.log").apply { writeText("x") }
        val outside = File(workDir, "outside.log").apply { writeText("x") }
        val context = fenceWith(inside)
        val probe = LogTaskTools.ProbeFileTool(context)
        val result = probe.execute(LogTaskTools.ProbeFileTool.Args(outside.absolutePath))
        // 桌面默认围栏文案（下沉后无 fenceHint 时的默认——旧桥接测试断言「围栏」，
        // 桌面文案关键词是「允许范围」；桥接变体经 fenceHint 注入，见下方专测）
        assertTrue(result.contains("允许范围"), "围栏外路径应被拒（桌面默认围栏文案）：$result")
    }

    @Test
    fun `工具产物登记进围栏（过滤输出可继续操作）`() = runBlocking {
        val input = File(workDir, "plain.txt").apply { writeText("2026-09-14_10:00:00.000 hello\nworld") }
        val context = fenceWith(input)
        val filter = LogTaskTools.FilterByTagTool(context)
        val result = filter.execute(
            LogTaskTools.FilterByTagTool.Args(input.absolutePath, "hello", "hello"),
        )
        assertTrue(result.startsWith("过滤完成"), "过滤应成功：$result")
        // 产物已登记：紧接的 probe 应放行（围栏内）。
        // summary 尾部可能带降级形态提示（无时间戳文件 → ToolsResult.Success.warning
        // 并入 summary）——路径提取须止于「；」
        val probe = LogTaskTools.ProbeFileTool(context)
        val probeResult = probe.execute(
            LogTaskTools.ProbeFileTool.Args(
                result.removePrefix("过滤完成，输出文件: ").substringBefore("；").trim(),
            ),
        )
        assertFalse(probeResult.contains("围栏"), "产物登记后应可继续操作：$probeResult")
    }

    // ── decrypt_log 私钥语义（文案逐字兼容） ──

    @Test
    fun `decrypt_log 私钥缺省-如实告知不抛`() = runBlocking {
        val input = File(workDir, "fake.xlog").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val context = fenceWith(input)
        val decrypt = LogTaskTools.DecryptLogTool(context, privateKeyProvider = { "" })
        val result = decrypt.execute(LogTaskTools.DecryptLogTool.Args(input.absolutePath))
        assertTrue(result.contains("缺少 xlog 私钥"), "私钥缺省应如实告知：$result")
    }

    // ── probe_file 判型（文案逐字兼容） ──

    @Test
    fun `probe_file 非 xlog 文件判型为否`() = runBlocking {
        val input = File(workDir, "plain2.txt").apply { writeText("just text") }
        val context = fenceWith(input)
        val probe = LogTaskTools.ProbeFileTool(context)
        val result = probe.execute(LogTaskTools.ProbeFileTool.Args(input.absolutePath))
        assertTrue(result.contains("是否 xlog/alog 加密日志: 否"), "普通文本判型应为否：$result")
    }

    // ── split_by_size 多卷登记（文案逐字兼容） ──

    @Test
    fun `split_by_size 多卷全部登记进围栏`() = runBlocking {
        val input = File(workDir, "big.txt").apply {
            writeText((1..2000).joinToString("\n") { "line $it ".repeat(20) })
        }
        val context = fenceWith(input)
        val split = LogTaskTools.SplitBySizeTool(context)
        val result = split.execute(LogTaskTools.SplitBySizeTool.Args(input.absolutePath, size_mb = 1))
        assertTrue(result.startsWith("分割完成"), "分割应成功：$result")
        // 首卷已登记：probe 放行
        val firstPart = File(workDir, "big_000.txt")
        assertTrue(firstPart.isFile, "分割应产出分卷文件")
        val probe = LogTaskTools.ProbeFileTool(context)
        assertFalse(
            probe.execute(LogTaskTools.ProbeFileTool.Args(firstPart.absolutePath)).contains("围栏"),
            "分卷应全部登记进围栏",
        )
    }

    // ── P1 新增：结构化三段式（artifacts 精确产物） ──

    @Test
    fun `filter_by_tag 结构化结果-artifacts 精确产物路径`() = runBlocking {
        val input = File(workDir, "structured.txt").apply { writeText("2026-09-14_10:00:00.000 hello") }
        val context = fenceWith(input)
        val sink = CollectingSink()
        val filter = LogTaskTools.FilterByTagTool(context, sink)
        filter.execute(LogTaskTools.FilterByTagTool.Args(input.absolutePath, "hello", "hello"))
        val (_, result) = sink.results.single()
        assertTrue(result is ToolResult.Ok, "应为 Ok：$result")
        val ok = result as ToolResult.Ok
        assertEquals(1, ok.artifacts.size, "过滤应有恰好一个产物")
        assertTrue(ok.artifacts.single().path.endsWith("hello.txt"), "产物路径应精确：${ok.artifacts}")
        assertEquals(ArtifactKind.FILE, ok.artifacts.single().kind)
    }

    // ── P1 新增：fenceHint 参数化（桥接文案变体） ──

    @Test
    fun `fenceHint 覆写-桥接拒绝文案逐字保留`() = runBlocking {
        val inside = File(workDir, "fence-in.txt").apply { writeText("x") }
        val outside = File(workDir, "fence-out.txt").apply { writeText("x") }
        val context = fenceWith(inside)
        val bridgeHint = "路径不在本消息的文件围栏内（只能操作用户引用发送的文件及其产物），请检查路径或改用已登记文件"
        val probe = LogTaskTools.ProbeFileTool(context, fenceHint = bridgeHint)
        val result = probe.execute(LogTaskTools.ProbeFileTool.Args(outside.absolutePath))
        assertEquals(bridgeHint, result, "桥接围栏文案应逐字保留（LLM 存量反馈语义不变）")
    }

    // ── P1 新增：split_by_size 结构化（多卷 artifacts） ──

    @Test
    fun `split_by_size 结构化结果-多卷 artifacts`() = runBlocking {
        val input = File(workDir, "structured-big.txt").apply {
            writeText((1..2000).joinToString("\n") { "line $it ".repeat(20) })
        }
        val context = fenceWith(input)
        val sink = CollectingSink()
        val split = LogTaskTools.SplitBySizeTool(context, sink)
        split.execute(LogTaskTools.SplitBySizeTool.Args(input.absolutePath, size_mb = 1))
        val (_, result) = sink.results.single()
        assertTrue(result is ToolResult.Ok, "应为 Ok：$result")
        val ok = result as ToolResult.Ok
        assertTrue(ok.artifacts.isNotEmpty(), "分割产物应进 artifacts：${ok.artifacts.size}")
        assertTrue(
            ok.artifacts.all { it.kind == ArtifactKind.FILE },
            "分卷产物 kind 应为 FILE",
        )
    }

    // ── filter_by_range：与引擎同核 + 宁拒不猜文案透传 ──

    /** 区间日志样本：5 行时间戳横跨 10:04→10:35 区间内外（31 分钟奇数跨度——对称窗口整数分钟口径表达不了的形状） */
    private fun rangeSampleLines(): List<String> = listOf(
        "2026-09-29 09:59:00.000 D/Main: 区间前",
        "2026-09-29 10:04:00.000 D/Main: 恰为起始（含）",
        "2026-09-29 10:20:00.000 D/Main: 区间中",
        "2026-09-29 10:35:00.000 D/Main: 恰为结束（含）",
        "2026-09-29 10:40:00.000 D/Main: 区间后",
    )

    @Test
    fun `filter_by_range 与引擎同核-31 分钟奇数区间`() = runBlocking {
        // 同核断言：同一份输入分别过工具与引擎，产物逐行一致（工具只是围栏+结构化包装，过滤口径不得另造）
        val toolInput = File(workDir, "range-tool.txt").apply { writeText(rangeSampleLines().joinToString("\n")) }
        val engineInput = File(workDir, "range-engine.txt").apply { writeText(rangeSampleLines().joinToString("\n")) }
        val context = fenceWith(toolInput)
        val sink = CollectingSink()
        val tool = LogTaskTools.FilterByRangeTool(context, sink)
        val rendered = tool.execute(
            LogTaskTools.FilterByRangeTool.Args(
                file_path = toolInput.absolutePath,
                start_time = "2026-09-29_10:04:00.000",
                end_time = "2026-09-29_10:35:00.000",
            ),
        )
        assertTrue(rendered.startsWith("过滤完成"), "区间截取应成功：$rendered")
        val engineResult = com.jiaoay.log.LogTimeFilter.filterByRange(
            engineInput, "2026-09-29_10:04:00.000", "2026-09-29_10:35:00.000",
        )
        assertTrue(engineResult is com.jiaoay.log.ToolsResult.Success, "引擎侧应成功（同核前提）")
        val toolOut = File(
            rendered.removePrefix("过滤完成，输出文件: ").trim(),
        ).readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val engineOut = (engineResult as com.jiaoay.log.ToolsResult.Success)
            .outputFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(engineOut, toolOut, "工具输出须与引擎 filterByRange 同核（围栏/渲染之外零口径漂移）")
        assertEquals(rangeSampleLines().slice(1..3), toolOut, "闭区间 [10:04, 10:35] 含两端，区间外两行不进产物")
        // 结构化三段式：artifacts 恰一件精确产物
        val (_, result) = sink.results.single()
        assertTrue(result is ToolResult.Ok, "应为 Ok：$result")
        val ok = result as ToolResult.Ok
        assertEquals(1, ok.artifacts.size, "区间截取应有恰好一个产物")
        assertTrue(
            ok.artifacts.single().path.endsWith("range-tool.filter-time_1004-1035.txt"),
            "产物路径应精确（HHmm-HHmm 命名与对称窗口同款）：${ok.artifacts}",
        )
    }

    @Test
    fun `filter_by_range 起止倒挂与解析失败宁拒不猜`() = runBlocking {
        val input = File(workDir, "range-refuse.txt").apply { writeText(rangeSampleLines().joinToString("\n")) }
        val context = fenceWith(input)
        val tool = LogTaskTools.FilterByRangeTool(context)
        // 起止倒挂：引擎拒绝文案透传（不静默反转区间、不猜用户意图）
        val reversed = tool.execute(
            LogTaskTools.FilterByRangeTool.Args(
                file_path = input.absolutePath,
                start_time = "2026-09-29_10:35:00.000",
                end_time = "2026-09-29_10:04:00.000",
            ),
        )
        assertTrue("结束时间早于起始时间" in reversed, "倒挂须透传引擎拒绝：$reversed")
        // 时间无法解析：同样透传（端点各自指明，不做隐式补全）
        val badStart = tool.execute(
            LogTaskTools.FilterByRangeTool.Args(
                file_path = input.absolutePath,
                start_time = "garbage",
                end_time = "2026-09-29_10:35:00.000",
            ),
        )
        assertTrue("起始时间无法解析" in badStart, "起始端解析失败须透传引擎拒绝：$badStart")
        val badEnd = tool.execute(
            LogTaskTools.FilterByRangeTool.Args(
                file_path = input.absolutePath,
                start_time = "2026-09-29_10:04:00.000",
                end_time = "garbage",
            ),
        )
        assertTrue("结束时间无法解析" in badEnd, "结束端解析失败须透传引擎拒绝：$badEnd")
    }
}
