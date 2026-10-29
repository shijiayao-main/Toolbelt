package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.core.buffer.FileBuffer
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResult
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandResult
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 投影执行链单测（调查工作台评审修复批 2026-09-23）：
 * S3 只锁了 params id 与 Args 字段名（ToolCatalogDisciplineTest——schema 层），
 * 执行行为零回归网。本批补齐四路锚：
 * 1. [ParamCommandCatalog.argsToJson] 编码（NUMBER 裸数值 / TEXT JSON 字符串 /
 *    空值省略走默认值）；
 * 2. 装配门控（buildRegistry factory null 不注册 → performArgs 回「命令不可用」）；
 * 3. executeUnsafe + sink 旁路收集（ToolResult.Ok → HUD summary + 产物路径）；
 * 4. sink-null 兜底映射面（ToolResult 映射纯函数）+ buffer.offer 命令行为。
 */
class ParamCommandCatalogTest {

    @AfterTest
    fun tearDown() {
        FileBuffer.clearForTest()
        LogViewerRequest.clearForTest()
    }

    private fun numberSpec(id: String, default: String? = null) =
        ParamSpec(id = id, label = id, type = ParamType.NUMBER, default = default)
    private fun textSpec(id: String, required: Boolean = true) =
        ParamSpec(id = id, label = id, type = ParamType.TEXT, required = required)

    // ── 1 · argsToJson 编码 ──

    @Test
    fun `argsToJson emits bare number for NUMBER type`() {
        val json = ParamCommandCatalog.argsToJson(
            args = mapOf("size_mb" to "10"),
            specs = listOf(numberSpec("size_mb")),
        )
        assertEquals("""{"size_mb":10}""", json, "NUMBER 输出裸数值（非 JSON 字符串）")
    }

    @Test
    fun `argsToJson emits bare decimal and negative numbers for NUMBER type`() {
        // GLB-08：表单层放开小数/负数后执行编码层须如实透传
        // （先 toLongOrNull 失败落 toDoubleOrNull——执行层零改，此用例锚兼容性）
        val decimal = ParamCommandCatalog.argsToJson(
            args = mapOf("ratio" to "0.25"),
            specs = listOf(numberSpec("ratio")),
        )
        assertEquals("""{"ratio":0.25}""", decimal, "正小数输出裸小数")
        val negativeDecimal = ParamCommandCatalog.argsToJson(
            args = mapOf("window" to "-3.5"),
            specs = listOf(numberSpec("window")),
        )
        assertEquals("""{"window":-3.5}""", negativeDecimal, "负小数输出裸负小数")
        val negativeInt = ParamCommandCatalog.argsToJson(
            args = mapOf("offset" to "-60"),
            specs = listOf(numberSpec("offset")),
        )
        assertEquals("""{"offset":-60}""", negativeInt, "负整数经 toLongOrNull 输出裸整数")
    }

    @Test
    fun `argsToJson emits JSON string for TEXT type and escapes quotes`() {
        val json = ParamCommandCatalog.argsToJson(
            args = mapOf("target_time" to "2026-09-23_12:00:00.000"),
            specs = listOf(textSpec("target_time")),
        )
        assertEquals(
            """{"target_time":"2026-09-23_12:00:00.000"}""",
            json,
            "TEXT 输出 JSON 字符串（kotlinx 序列化转义）",
        )
        // 引号转义：值含引号不产出非法 JSON
        val escaped = ParamCommandCatalog.argsToJson(
            args = mapOf("tag_name" to "a\"b"),
            specs = listOf(textSpec("tag_name")),
        )
        assertEquals("""{"tag_name":"a\"b"}""", escaped, "引号经 JSON 转义（防注入坏 JSON）")
    }

    @Test
    fun `argsToJson omits blank values so defaults take over`() {
        // 空值字段不传——由 @Serializable 默认值兜底（过滤类工具 range_minutes=5 先例）
        val json = ParamCommandCatalog.argsToJson(
            args = mapOf("file_path" to "/tmp/x.txt", "range_minutes" to ""),
            specs = listOf(textSpec("file_path"), numberSpec("range_minutes", default = "5")),
        )
        assertEquals(
            """{"file_path":"/tmp/x.txt"}""",
            json,
            "空白值字段不编码（默认值兜底语义）",
        )
        // 全空 = 空 JSON 对象
        assertEquals("{}", ParamCommandCatalog.argsToJson(emptyMap(), listOf(textSpec("a"))))
    }

    @Test
    fun `argsToJson only encodes declared specs and keeps spec order`() {
        // 未声明的 args 乱入不编码（围栏面：编码以 specs 为准）；声明序即 JSON 字段序
        val json = ParamCommandCatalog.argsToJson(
            args = mapOf("b" to "2", "a" to "1", "intruder" to "x"),
            specs = listOf(textSpec("a"), textSpec("b")),
        )
        assertEquals("""{"a":"1","b":"2"}""", json, "以 specs 声明序编码，未声明字段丢弃")
    }

    // ── 2 · 装配门控（buildRegistry factory null → 「命令不可用」）──

    @Test
    fun `registry gate returns no tool when factory config is absent`() {
        // user.ask_user 的 factory 凭 config.userQuestionGate 非 null 才注册——
        // executeProjection 用同款 buildRegistry 装配：gate null 点名也不注册
        // （AskUserToolTest「catalog gates ask user by explicit names with gate」同款先例，
        // 此处锚定 palette 投影链消费的是同一门控行为）
        val registry = AgentToolCatalog.buildRegistry(
            names = setOf("user.ask_user"),
            context = ToolExecutionContext(),
            config = AgentToolCatalog.ToolContextConfig(userQuestionGate = null),
        )
        assertEquals(null, registry.getToolOrNull("user.ask_user"), "gate null ⇒ 点名也不注册")
    }

    @Test
    fun `registry returns probe tool when named without extra config`() {
        // log.probe_file 无 config 门控（工厂恒注册）——投影面可执行性锚。
        // 🔴 双名域事实（McpToolRegistrar.toolOf 同款）：buildRegistry 按目录名
        //    选择装配，registry 内注册名 = 工具实现名（probe_file）——S4 起按目录名
        //    直查 getToolOrNull 恒落空的存量缺陷即此，目录名断言改双名域查表序。
        val registry = AgentToolCatalog.buildRegistry(
            names = setOf("log.probe_file"),
            context = ToolExecutionContext(),
        )
        assertTrue(registry.getToolOrNull("probe_file") != null, "probe 点名即注册（registry 内注册名 = 实现名 probe_file）")
    }

    @Test
    fun `projection face excludes page-duplicated tools and hitl`() {
        // PROJECTION_EXCLUDED = 与页面命令同能力的四件 + HITL 会话内件
        assertEquals(
            setOf("log.decrypt", "fs.decrypt_file", "log.filter_by_tag", "log.split_by_size", "user.ask_user"),
            ParamCommandCatalog.PROJECTION_EXCLUDED,
            "排除集漂移即双条目回潮（palette 撞面）",
        )
        // 聚合面不含排除集成员 + 含页面命令（页面侧优先存活）
        val allIds = ParamCommandCatalog.all().map { it.id }.toSet()
        assertTrue("logkit.xlog-decrypt" in allIds, "页面解密命令在聚合面（feature 注册序优先）")
        assertTrue("log.decrypt" !in allIds, "agent log.decrypt 被投影排除（同能力双入口去重）")
        assertTrue("user.ask_user" !in allIds, "HITL 件被投影排除（会话内形态）")
        assertTrue("buffer.offer" in allIds, "Buffer 暂存命令在聚合面（裁决 8 消费面）")
    }

    // ── 3 · executeUnsafe + sink 旁路收集（端到端：投影 spec performArgs）──

    @Test
    fun `probe projection executes via performArgs and collects sink result`() = runTest {
        // 端到端：all() 里的 log.probe_file 投影 spec → performArgs →
        // buildRegistry 装配 → argsToJson 解码 → executeUnsafe → sink 旁路 → Ok 映射
        val probe = ParamCommandCatalog.all().first { it.id == "log.probe_file" }
        val temp = File.createTempFile("palette-probe", ".txt").apply {
            writeText("plain text head")
            deleteOnExit()
        }
        val result = probe.performArgs(mapOf("file_path" to temp.absolutePath))
        assertTrue(result.success, "probe 端到端应成功：${result.summary}")
        assertTrue(
            result.summary.contains("判型结果"),
            "sink 旁路收集的 Ok.summary 透传 HUD（实测：${result.summary}）",
        )
        assertTrue(
            result.summary.contains("是否 xlog/alog 加密日志: 否"),
            "普通文本判型为否（executeTyped 真实执行，非 render 文案兜底）",
        )
    }

    // ── 4 · ToolResult → HUD 映射面（sink-null 兜底语义的映射层锚）──

    @Test
    fun `tool result mapping carries ok artifacts and err text`() {
        val ok: ToolResult = ToolResult.Ok(
            summary = "解密成功: /tmp/x.log",
            artifacts = listOf(
                com.jiaoay.easy_dev_toolbox.core.llm.tools.ArtifactRef("/tmp/x.log", com.jiaoay.easy_dev_toolbox.core.llm.tools.ArtifactKind.FILE),
            ),
        )
        with(ParamCommandCatalog) {
            val mapped = ok.toCommandResult()
            assertTrue(mapped.success)
            assertEquals("解密成功: /tmp/x.log", mapped.summary)
            assertEquals(listOf("/tmp/x.log"), mapped.artifacts, "产物路径透传 HUD chips")
        }
        val err: ToolResult = ToolResult.Err(ToolResult.ErrCode.DENIED, "拒绝", hint = "提示")
        with(ParamCommandCatalog) {
            val mapped = err.toCommandResult()
            assertEquals(false, mapped.success, "Err 映射恒失败（HUD error 色）")
            assertEquals("拒绝\n提示", mapped.summary, "renderText = message + hint")
        }
    }

    // ── 5 · buffer.offer 命令行为（纯内存 FileBuffer——零 DB 依赖直测） ──

    @Test
    fun `buffer offer command rejects blank and missing file then offers real file`() = runTest {
        val command = ParamCommandCatalog.bufferOfferCommand
        // 空路径拒绝
        val blank = command.performArgs(mapOf("file_path" to ""))
        assertEquals(false, blank.success, "空路径拒绝")
        // 不存在文件拒绝
        val missing = command.performArgs(mapOf("file_path" to "/nonexistent/palette-buffer-test.bin"))
        assertEquals(false, missing.success, "不存在文件拒绝")
        // 真实文件暂存成功 + 计数文案
        val temp = File.createTempFile("palette-buffer", ".txt").apply {
            writeText("x")
            deleteOnExit()
        }
        val offered = command.performArgs(mapOf("file_path" to temp.absolutePath))
        assertTrue(offered.success, "真实文件暂存成功：${offered.summary}")
        assertTrue(
            offered.summary.contains("已暂存") && offered.summary.contains("1 件"),
            "HUD 文案带 Buffer 现有计数（实测：${offered.summary}）",
        )
        assertEquals(1, FileBuffer.files.value.size, "FileBuffer 收到路径")
        assertEquals(temp.absolutePath, FileBuffer.files.value.first().path)
        // tearDown 清场（clearForTest）——单测隔离
    }

    // ── 6 · knowledge.* 可见性快照（组合期零 SQL 的判定面锚） ──

    @Test
    fun `knowledge projection visibility reads memory snapshot not live db`() {
        // 快照 false：knowledge.search 投影不可见；非 knowledge 件恒可见
        ParamCommandCatalog.setKnowledgeVisibilityForTest(false)
        val hidden = ParamCommandCatalog.all().filter { it.visibleWhen() }.map { it.id }
        assertTrue("knowledge.search" !in hidden, "快照 false ⇒ knowledge.* 不可见")
        assertTrue("log.probe_file" in hidden, "非 knowledge 件不受快照影响（恒可见）")
        // 快照 true：knowledge.search 投影可见
        ParamCommandCatalog.setKnowledgeVisibilityForTest(true)
        val shown = ParamCommandCatalog.all().filter { it.visibleWhen() }.map { it.id }
        assertTrue("knowledge.search" in shown, "快照 true ⇒ knowledge.* 可见")
        ParamCommandCatalog.setKnowledgeVisibilityForTest(false)
    }

    // ── 7 · tools.log-diff 命令（宿主直登记——双 FILE 承载 + 产物落盘语义）──

    @Test
    fun `log diff command is host registered with two file params and no open page`() {
        val command = ParamCommandCatalog.all().first { it.id == "tools.log-diff" }
        // 双 FILE 经 params 列表原生承载（ParamCommandSpec 无需结构扩展）
        assertEquals(
            listOf("old_path", "new_path"),
            command.params.filter { it.type == ParamType.FILE }.map { it.id },
            "双 FILE 参数承载（基准 + 对比两槽）",
        )
        assertEquals(null, command.openPage, "palette 直出语义——openPage null（交互式双槽在「工具」页日志对比签）")
        val normalize = command.params.first { it.id == "normalize" }
        assertEquals("关", normalize.default, "归一化开关保守默认关（对齐页面初值）")
    }

    @Test
    fun `log diff command rejects missing slot with slot naming and missing file`() = runTest {
        val command = ParamCommandCatalog.all().first { it.id == "tools.log-diff" }
        // 缺一明示缺哪个槽（对齐页面「缺一拒收」口径）
        val noOld = command.performArgs(mapOf("new_path" to "/tmp/x.txt"))
        assertEquals(false, noOld.success, "缺基准路径拒收")
        assertTrue(noOld.summary.contains("基准"), "拒收文案指明缺基准侧（实测：${noOld.summary}）")
        val noNew = command.performArgs(mapOf("old_path" to "/tmp/x.txt"))
        assertTrue(noNew.summary.contains("对比"), "拒收文案指明缺对比侧（实测：${noNew.summary}）")
        // 不存在文件拒收
        val missing = command.performArgs(
            mapOf("old_path" to "/nonexistent/palette-diff-old.txt", "new_path" to "/nonexistent/palette-diff-new.txt"),
        )
        assertEquals(false, missing.success, "基准文件不存在拒收")
    }

    @Test
    fun `log diff identical files report equality without artifact`() = runTest {
        val command = ParamCommandCatalog.all().first { it.id == "tools.log-diff" }
        val old = File.createTempFile("palette-diff-old", ".txt").apply {
            writeText("a\nb\nc\n"); deleteOnExit()
        }
        val new = File.createTempFile("palette-diff-new", ".txt").apply {
            writeText("a\nb\nc\n"); deleteOnExit()
        }
        val result = command.performArgs(
            mapOf("old_path" to old.absolutePath, "new_path" to new.absolutePath, "normalize" to "关"),
        )
        assertTrue(result.success, "全等文件对比应成功：${result.summary}")
        assertTrue(result.summary.contains("内容一致"), "全等文案对齐页面横幅（实测：${result.summary}）")
        assertEquals(emptyList(), result.artifacts, "全等不落产物（页面同口径：全等早退无导出面）")
    }

    @Test
    fun `log diff differing files write unified artifact beside new file`() = runTest {
        val command = ParamCommandCatalog.all().first { it.id == "tools.log-diff" }
        val old = File.createTempFile("palette-diff-old", ".txt").apply {
            writeText("a\nb\nc\n"); deleteOnExit()
        }
        val new = File.createTempFile("palette-diff-new", ".txt").apply {
            writeText("a\nx\nc\n"); deleteOnExit()
        }
        val result = command.performArgs(
            mapOf("old_path" to old.absolutePath, "new_path" to new.absolutePath),
        )
        assertTrue(result.success, "差异文件对比应成功：${result.summary}")
        assertTrue(result.summary.contains("1 处差异"), "HUD 文案带差异处数（实测：${result.summary}）")
        assertEquals(1, result.artifacts.size, "产物 chips 单件（.diff 落对比文件旁）")
        val artifact = File(result.artifacts.first())
        artifact.deleteOnExit()
        assertEquals("${new.name}.diff", artifact.name, "产物名 = <对比文件名>.diff（对齐页面导出语义）")
        assertEquals(new.parentFile, artifact.parentFile, "产物落对比文件旁")
        val text = artifact.readText()
        assertTrue(text.contains("-b") && text.contains("+x"), "unified 产物含增删行（实测：$text）")
    }

    // ── 8 · tools.view-log 命令（宿主直登记——FILE 单参 + 查看器槽写入门控）──

    @Test
    fun `log view command is host registered with single file param and no open page`() {
        val command = ParamCommandCatalog.all().first { it.id == "tools.view-log" }
        assertEquals(
            listOf("file_path"),
            command.params.filter { it.type == ParamType.FILE }.map { it.id },
            "FILE 单参承载（查看目标文件）",
        )
        assertEquals(null, command.openPage, "palette 直达查看器语义——openPage null（Dialog 形态无页面）")
        assertTrue(command.label.contains("查看"), "命令文案含「查看」——与任务卡「查看」条目同语义（防两动线口径漂移）")
    }

    @Test
    fun `log view command rejects blank and missing file with two receipts`() = runTest {
        val command = ParamCommandCatalog.all().first { it.id == "tools.view-log" }
        // 两道回执对齐 buffer.offer 口径：缺参 + 不存在，均带原因
        val blank = command.performArgs(mapOf("file_path" to ""))
        assertEquals(false, blank.success, "空路径拒收")
        assertEquals("未填写文件路径", blank.summary, "缺参回执与 buffer.offer 逐字对齐")
        val missing = command.performArgs(mapOf("file_path" to "/nonexistent/palette-view-log.txt"))
        assertEquals(false, missing.success, "不存在文件拒收")
        assertTrue(missing.summary.contains("文件不存在"), "第二道回执带原因（实测：${missing.summary}）")
        assertEquals(null, LogViewerRequest.pending.value, "拒收不写查看器槽")
    }

    @Test
    fun `log view command offers real file to viewer slot`() = runTest {
        val command = ParamCommandCatalog.all().first { it.id == "tools.view-log" }
        val temp = File.createTempFile("palette-view-log", ".txt").apply {
            writeText("hello viewer\n")
            deleteOnExit()
        }
        val result = command.performArgs(mapOf("file_path" to temp.absolutePath))
        assertTrue(result.success, "真实文件打开成功：${result.summary}")
        val firstRequest = assertNotNull(
            LogViewerRequest.pending.value,
            "路径写入查看器槽（宿主 collect 后挂 Dialog）",
        )
        assertEquals(temp.absolutePath, firstRequest.path, "请求携带目标文件路径")
        assertEquals(null, firstRequest.initialLine, "palette 单文件打开不带初始定位行")
        assertTrue(result.summary.contains("查看"), "成功文案含「查看」语义（实测：${result.summary}）")
        // 覆盖语义：在台请求被新请求替换
        val second = File.createTempFile("palette-view-log-2", ".txt").apply {
            writeText("second\n")
            deleteOnExit()
        }
        command.performArgs(mapOf("file_path" to second.absolutePath))
        assertEquals(
            second.absolutePath,
            LogViewerRequest.pending.value?.path,
            "新请求覆盖在台请求（Dialog 按 filePath 键重组）",
        )
        // consume = 宿主关闭侧清槽
        LogViewerRequest.consume()
        assertEquals(null, LogViewerRequest.pending.value, "consume 清槽（关闭后可再次打开）")
    }

    // ── 9 · tools.slice-time 命令（宿主直登记——FILE + 起止时刻双 TEXT + 产物落盘语义）──

    @Test
    fun `time slice handoff constant resolves in catalog with three prefilled slots`() {
        // 查看器「送时间截取」横向直投锚（目标 = TaskPipelineHandoff 表外常量，
        // 不走 nextToolTargetOf 映射表）：commandId 须在 all() 解析（id 漂移即
        // palette 解析落空 → 衔接静默失效），参数表含三槽——file_path/start_time
        // 是 offer 预填落点（槽 id 漂移即静默不预填），end_time 是留白必填槽
        val constant = TaskPipelineHandoff.VIEWER_TIME_SLICE
        val command = ParamCommandCatalog.all().firstOrNull { it.id == constant.commandId }
        assertNotNull(command, "「送时间截取」目标 id=${constant.commandId} 须在 catalog all() 内（改名后同步常量）")
        command!!
        val paramIds = command.params.map { it.id }
        assertTrue(
            listOf("file_path", "start_time", "end_time").all { it in paramIds },
            "目标命令参数表须含 file_path/start_time/end_time（实测：$paramIds）",
        )
        assertEquals("送时间截取", constant.label, "条目文案 = 动作动词 + 目标语义（横向直投条目）")
    }

    @Test
    fun `time slice command is host registered with file and time range params`() {
        val command = ParamCommandCatalog.all().first { it.id == "tools.slice-time" }
        assertEquals(
            listOf("file_path"),
            command.params.filter { it.type == ParamType.FILE }.map { it.id },
            "FILE 单参承载（截取目标文件）",
        )
        assertEquals(
            listOf("start_time", "end_time"),
            command.params.filter { it.type == ParamType.TEXT }.map { it.id },
            "起止时刻双 TEXT 参数（容忍裸 MM-dd 行首同款形态，年份格式/跨年日志请补全年份）",
        )
        assertEquals(null, command.openPage, "palette 直出语义——openPage null（纯命令形态）")
        assertTrue(
            command.desc.contains("按时间点过滤日志"),
            "desc 反向指引时间点±窗口场景（实测：${command.desc}）",
        )
    }

    @Test
    fun `time slice command rejects blank args slot by slot and missing file`() = runTest {
        val command = ParamCommandCatalog.all().first { it.id == "tools.slice-time" }
        // 缺参逐槽回执（对齐 buffer.offer 口径逐字）
        val blankPath = command.performArgs(
            mapOf("start_time" to "2026-05-09_10:00:00.000", "end_time" to "2026-05-09_11:00:00.000"),
        )
        assertEquals(false, blankPath.success, "缺文件路径拒收")
        assertEquals("未填写文件路径", blankPath.summary, "缺参回执与 buffer.offer 逐字对齐")
        val blankStart = command.performArgs(
            mapOf("file_path" to "/tmp/x.txt", "end_time" to "2026-05-09_11:00:00.000"),
        )
        assertEquals("未填写起始时刻", blankStart.summary, "缺参回执指明缺起始槽（实测：${blankStart.summary}）")
        val blankEnd = command.performArgs(
            mapOf("file_path" to "/tmp/x.txt", "start_time" to "2026-05-09_10:00:00.000"),
        )
        assertEquals("未填写结束时刻", blankEnd.summary, "缺参回执指明缺结束槽（实测：${blankEnd.summary}）")
        // 不存在文件拒收
        val missing = command.performArgs(
            mapOf(
                "file_path" to "/nonexistent/palette-slice-time.txt",
                "start_time" to "2026-05-09_10:00:00.000",
                "end_time" to "2026-05-09_11:00:00.000",
            ),
        )
        assertEquals(false, missing.success, "不存在文件拒收")
        assertTrue(missing.summary.contains("文件不存在"), "第二道回执带原因（实测：${missing.summary}）")
    }

    @Test
    fun `time slice command slices range and writes artifact beside input`() = runTest {
        val command = ParamCommandCatalog.all().first { it.id == "tools.slice-time" }
        val input = File.createTempFile("palette-slice-time", ".txt").apply {
            writeText(
                "2026-05-09 09:00:00.000 D/Main: 区间前\n" +
                    "2026-05-09 10:00:00.000 D/Main: 区间内\n" +
                    "2026-05-09 11:00:00.000 D/Main: 区间后\n",
            )
            deleteOnExit()
        }
        val result = command.performArgs(
            mapOf(
                "file_path" to input.absolutePath,
                "start_time" to "2026-05-09 09:30:00.000",
                "end_time" to "2026-05-09 10:30:00.000",
            ),
        )
        assertTrue(result.success, "区间截取应成功：${result.summary}")
        assertEquals(1, result.artifacts.size, "产物 chips 单件（.filter-time_*.txt 落原文件旁）")
        val artifact = File(result.artifacts.first())
        artifact.deleteOnExit()
        assertEquals(input.parentFile, artifact.parentFile, "产物落原文件旁")
        assertTrue(
            "filter-time_0930-1030" in artifact.name,
            "产物名带起止 HHmm-HHmm（实测：${artifact.name}）",
        )
        assertEquals(listOf("2026-05-09 10:00:00.000 D/Main: 区间内"), artifact.readLines().filter { it.isNotBlank() })
        // 区间反向报失败（引擎薄包装契约透传 HUD）
        val reversed = command.performArgs(
            mapOf(
                "file_path" to input.absolutePath,
                "start_time" to "2026-05-09 10:30:00.000",
                "end_time" to "2026-05-09 09:30:00.000",
            ),
        )
        assertEquals(false, reversed.success, "结束早于起始报失败")
        assertTrue(reversed.summary.contains("截取失败"), "失败回执带引擎原因（实测：${reversed.summary}）")
    }

    // ── 10 · catalog id 唯一性与文案非空不变量（palette 解析面的零成本守护）──

    /**
     * catalog 不变量断言（唯一入口，真目录与人造样例共用同一断言路径）：
     * 命令 id 全局唯一 / 各 spec 内 params id 唯一 / 文案非空。
     */
    private fun assertCatalogInvariants(specs: List<ParamCommandSpec>) {
        // palette 按 id 解析（firstOrNull 命中即止）：两条命令撞 id 时后者静默不可达
        // （解析落空无任何报错，衔接/表单均静默回落），故 id 唯一是硬不变量
        val duplicatedIds = specs.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(
            duplicatedIds.isEmpty(),
            "命令 id 撞面：$duplicatedIds（palette 按 id 查找，撞面者静默不可达）",
        )
        for (spec in specs) {
            assertTrue(spec.id.isNotBlank(), "命令 id 不得空白（palette 解析锚）")
            assertTrue(spec.label.isNotBlank(), "命令 ${spec.id} label 不得空白（palette 主文案）")
            // 断言粒度：仅 spec 内 params id 唯一——同名参数跨命令属正常（如 file_path 通用槽）
            val duplicatedParams = spec.params.groupBy { it.id }.filterValues { it.size > 1 }.keys
            assertTrue(
                duplicatedParams.isEmpty(),
                "命令 ${spec.id} 内参数 id 撞面：$duplicatedParams（表单按 id 取值，撞面参数互覆）",
            )
            for (param in spec.params) {
                assertTrue(param.id.isNotBlank(), "命令 ${spec.id} 参数 id 不得空白")
                assertTrue(param.label.isNotBlank(), "命令 ${spec.id} 参数 ${param.id} label 不得空白（表单标签）")
                // placeholder 契约可空（CHOICE/带默认值槽可无提示），但非空时禁空白串
                // （空白串经 PaletteParamForm 渲染为不可见提示——表单无提示静默劣化）
                param.placeholder?.let {
                    assertTrue(it.isNotBlank(), "命令 ${spec.id} 参数 ${param.id} placeholder 为空白串（提示静默劣化）")
                }
            }
        }
    }

    @Test
    fun `catalog command ids are unique and copy is non-blank across full face`() {
        // all() = palette 全量消费面（feature 命令 + agent 投影 + 宿主直登记四件），
        // 与 CommandPalette 候选构建同一入口——断言面即消费面，无子集缩水
        val catalog = ParamCommandCatalog.all()
        assertTrue(catalog.isNotEmpty(), "catalog 非空（palette 第三类候选缺席即面坍缩）")
        assertCatalogInvariants(catalog)
    }

    @Test
    fun `invariant assertions reject synthetic duplicate ids and blank copy`() {
        // 有牙自证：all() 公开入口不可注入，构造撞面样例走同一断言路径验证必红
        val stub = ParamCommandSpec(
            id = "synthetic.command",
            label = "人造样例",
            desc = "断言有牙自证用",
            icon = "icon",
            performArgs = { ParamCommandResult(summary = "stub") },
        )
        // 命令 id 撞面必红
        assertFailsWith<AssertionError>("撞 id 样例必须被断言路径抓住") {
            assertCatalogInvariants(listOf(stub, stub.copy(id = "synthetic.command")))
        }
        // spec 内参数 id 撞面必红
        assertFailsWith<AssertionError>("spec 内参数撞 id 样例必须被断言路径抓住") {
            assertCatalogInvariants(listOf(stub.copy(id = "dup.params", params = listOf(textSpec("a"), textSpec("a")))))
        }
        // 空白命令 label 必红
        assertFailsWith<AssertionError>("空白 label 样例必须被断言路径抓住") {
            assertCatalogInvariants(listOf(stub.copy(label = " ")))
        }
        // 空白参数 label 必红
        assertFailsWith<AssertionError>("空白参数 label 样例必须被断言路径抓住") {
            assertCatalogInvariants(
                listOf(stub.copy(id = "blank.param", params = listOf(ParamSpec(id = "p", label = " ", type = ParamType.TEXT)))),
            )
        }
        // 空白串 placeholder 必红
        assertFailsWith<AssertionError>("空白串 placeholder 样例必须被断言路径抓住") {
            assertCatalogInvariants(
                listOf(stub.copy(id = "blank.placeholder", params = listOf(ParamSpec(id = "p", label = "p", type = ParamType.TEXT, placeholder = " ")))),
            )
        }
    }
}
