package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.easy_dev_toolbox.core.system.ArgvOutcome
import com.jiaoay.easy_dev_toolbox.core.system.ArgvProcessRunner
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * dev.* 设备观测域守护：
 * 1. 域封闭（负面清单锚）：catalog 中 dev. 前缀工具精确等于两件（多一件即红——负面清单
 *    四层保险的工具面硬缺席层）；
 * 2. 注册面：risk 分级（devices 只读 / snaplog 写产物面 DESTRUCTIVE）+ uiHint=null + names 单源；
 * 3. argv 固定模板静态断言：形态恒为 `<adb> -s <serial> logcat -d -t <N>`、可执行名零黑名单命中；
 * 4. serial 白名单拒收矩阵（不在检测列表=拒收人话；空列表拒收；trim 归一）；
 * 5. 行数槽钳制 10..5000 缺省 1000（恒带 -t）；
 * 6. 会话门控 fail-closed：门控面缺席/授权拒绝恒 DENIED 且不触执行器，审计记会话授权拒绝；
 * 7. fake 执行器回执：argv 组装、快照落盘（临时目录真读写）+ 围栏 register 链
 *    （登记后 isAllowed/authorize 放行、围栏外路径仍恒拒）+ 同刻同串号避让不顶替；
 * 8. 脱敏分界：回执预览面遮蔽凭据形态、快照文件本体不脱敏（分析底稿保真）；
 * 9. 失败面：检测失败 FAILED、adb 不可用 NOT_FOUND（禁止编造）、超时 FAILED。
 */
class DevToolsTest {

    /** 测试门控面（approved=false = 授权卡拒绝/超时形态） */
    private class FakeDevGate(private val approved: Boolean = true) : DevTools.DevSessionGate {
        var asked = 0
        override suspend fun requireSessionAuthorization(toolName: String): Boolean {
            asked++
            return approved
        }
        override val trigger: String? = "chatId=oc_test sender=ou_test"
    }

    /** 测试设备源（检测单源 fake——免真子进程） */
    private class FakeSource(
        private val devices: List<DeviceInfo> = listOf(DeviceInfo("ABC123", "Pixel 7")),
        private val adbPath: String = "",
        private val detectError: String? = null,
    ) : DevTools.DeviceSource {
        var asked = 0
        override suspend fun snapshot(): DevTools.DeviceSnapshot {
            asked++
            return DevTools.DeviceSnapshot(devices, adbPath, detectError)
        }
    }

    // ── 域封闭（负面清单锚）与注册面 ──

    @Test
    fun `dev 前缀工具封闭于两件（负面清单锚——多一件即红）`() {
        val devNames = AgentToolCatalog.all.map { it.name }.filter { it.startsWith("dev.") }.toSet()
        assertEquals(
            setOf("dev.devices", "dev.snaplog"),
            devNames,
            "dev.* 域工具面封闭于两件（设备侧安装/卸载/push/截屏/隐私面/输入注入/无线配对/root " +
                "永不入域——负面清单为 koog 开发域边界定案）；" +
                "新条目须先过负面清单评审再改本锚",
        )
        assertEquals(
            setOf("dev.devices", "dev.snaplog"),
            DevTools.DEV_NAMES,
            "names 集单源（消费方选择集引用此集合）",
        )
    }

    @Test
    fun `注册面 risk 分级与纯 agent 形态`() {
        val devices = AgentToolCatalog.all.firstOrNull { it.name == "dev.devices" }
            ?: error("dev.devices 未注册进 AgentToolCatalog")
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, devices.risk, "只读零产物，必须 READ_ONLY")
        assertNull(devices.uiHint, "纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertEquals(emptySet<String>(), devices.aliases, "无别名（撞面零容忍）")
        val snaplog = AgentToolCatalog.all.firstOrNull { it.name == "dev.snaplog" }
            ?: error("dev.snaplog 未注册进 AgentToolCatalog")
        assertEquals(AgentToolCatalog.Risk.DESTRUCTIVE, snaplog.risk, "写产物面（落 devlogs 快照），必须显式 DESTRUCTIVE")
        assertNull(snaplog.uiHint, "纯 agent 工具")
        assertEquals("dev.snaplog", AgentToolCatalog.resolve("dev.snaplog"), "实名必须可经 resolve 解析")
        assertEquals(
            8192,
            DevTools.MAX_OUTPUT_CHARS,
            "输出上界字面值独立断言（与实现常量漂移解耦，防静默放宽）",
        )
    }

    // ── argv 固定模板静态断言 ──

    @Test
    fun `argv 模板形态固定且可执行名零黑名单命中`() {
        for (adbPath in listOf("", "adb", "/opt/android-sdk/platform-tools/adb", "~/Android/Sdk/platform-tools/adb")) {
            for (lines in listOf(DevTools.LINES_MIN, DevTools.DEFAULT_LINES, DevTools.LINES_MAX)) {
                val argv = DevTools.buildArgv(adbPath, "ABC123", lines)
                assertEquals(
                    listOf(adbPath.trim().ifEmpty { "adb" }, "-s", "ABC123", "logcat", "-d", "-t", lines.toString()),
                    argv,
                    "argv 模板必须恒为 <adb> -s <serial> logcat -d -t <N>（adbPath=$adbPath）",
                )
                assertTrue("/" !in argv[0] || adbPath.contains('/'), "可执行名只来自 adb 路径缓存或裸 adb")
                assertEquals("-d", argv[4], "恒带 -d（dump 后退出）")
                assertEquals("-t", argv[5], "恒带 -t（无 -t 形态 dump 整个缓冲不受钳制，不开放）")
                val executable = argv[0].substringAfterLast('/')
                assertTrue(
                    executable !in ArgvProcessRunner.BLOCKED_EXECUTABLES,
                    "可执行文件「$executable」命中提权/包管理黑名单",
                )
            }
        }
    }

    @Test
    fun `行数槽钳制 10 到 5000 缺省 1000`() {
        assertEquals("10", (DevTools.checkLines(5) as SysProbeTools.SlotCheck.Accepted).value, "下限钳制")
        assertEquals("5000", (DevTools.checkLines(99_999) as SysProbeTools.SlotCheck.Accepted).value, "上限钳制")
        assertEquals("1000", (DevTools.checkLines(DevTools.DEFAULT_LINES) as SysProbeTools.SlotCheck.Accepted).value)
        assertEquals(1000, DevTools.DEFAULT_LINES, "缺省值独立断言")
    }

    // ── serial 白名单拒收矩阵 ──

    @Test
    fun `serial 白名单外拒收人话且不触执行器`() = runBlocking {
        val devices = listOf(DeviceInfo("ABC123", "Pixel 7"), DeviceInfo("192.168.1.5:5555", ""))
        val tool = snaplogTool(
            source = FakeSource(devices),
            executor = { argv -> fail("白名单外 serial 不得触执行器（argv=$argv）") },
        )
        for (bad in listOf("EMU99999", "rm -rf /tmp", "", "abc123")) {
            val err = tool.executeTyped(DevTools.SnaplogTool.Args(serial = bad)) as? ToolResult.Err
                ?: fail("白名单外 serial「$bad」必须 BAD_ARGS")
            assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
            assertTrue(bad.trim() in err.message, "回执须指明被拒值：${err.message}")
            assertTrue("ABC123" in err.message, "回执须列当前检测列表（LLM 可自行调整）：${err.message}")
        }
        Unit
    }

    @Test
    fun `serial trim 归一后命中白名单`() = runBlocking {
        val recorded = mutableListOf<List<String>>()
        val tool = snaplogTool(
            recorded = recorded,
            source = FakeSource(listOf(DeviceInfo("ABC123", ""))),
            executor = { ArgvOutcome(0, "ok", outputTruncated = false, timedOut = false) },
        )
        val ok = tool.executeTyped(DevTools.SnaplogTool.Args(serial = "  ABC123  ")) as? ToolResult.Ok
            ?: fail("trim 后命中白名单须放行")
        assertTrue("ABC123" in (ok.data as ToolPayload.SnaplogPayload).serial)
        assertEquals(1, recorded.size, "放行须触执行器一次")
    }

    @Test
    fun `空检测列表拒收并指引先列设备`() = runBlocking {
        val tool = snaplogTool(
            source = FakeSource(devices = emptyList()),
            executor = { argv -> fail("空列表不得触执行器（argv=$argv）") },
        )
        val err = tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Err
            ?: fail("空检测列表必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
        assertTrue("未检测到任何已连接设备" in err.message, "回执须区分「无设备」与「白名单外」：${err.message}")
    }

    // ── 会话门控（fail-closed） ──

    @Test
    fun `门控面缺席恒 DENIED 且不触执行器与设备源（fail-closed）`() = runBlocking {
        val source = FakeSource()
        val snaplog = DevTools.SnaplogTool(
            context = ToolExecutionContext(emptyList()),
            source = source,
            executor = { argv -> fail("门控面缺席不得触执行器（argv=$argv）") },
            audit = SysAuditLog.NOOP,
            devGate = null,
            snapshotDir = Files.createTempDirectory("dev-snaplog-gate").toFile(),
        )
        val err = snaplog.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Err
            ?: fail("门控面缺席必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("门控面" in err.message, "回执须指明门控面缺席（装配缺陷）：${err.message}")
        assertEquals(0, source.asked, "门控缺席不得触设备源")
        val devices = DevTools.DeviceListTool(
            source = FakeSource(),
            audit = SysAuditLog.NOOP,
            devGate = null,
        )
        val err2 = devices.executeTyped(DevTools.DeviceListTool.Args()) as? ToolResult.Err
            ?: fail("清单工具门控面缺席必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err2.code)
    }

    @Test
    fun `会话授权拒绝恒 DENIED 且不触执行器、审计记会话授权拒绝`() = runBlocking {
        val auditDir = Files.createTempDirectory("dev-snaplog-gate-audit").toFile()
        val tool = DevTools.SnaplogTool(
            context = ToolExecutionContext(emptyList()),
            source = FakeSource(),
            executor = { argv -> fail("授权拒绝不得触执行器（argv=$argv）") },
            audit = SysAuditLog(auditDir),
            devGate = FakeDevGate(approved = false),
            snapshotDir = Files.createTempDirectory("dev-snaplog-gate2").toFile(),
        )
        val err = tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Err
            ?: fail("授权拒绝必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("信任用户" in err.message, "回执须引导用户加入信任用户名单：${err.message}")
        assertTrue("禁止编造" in err.message, "回执须钉禁止编造快照内容：${err.message}")
        val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
        val records = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(1, records.size)
        assertEquals(
            SysAuditLog.VERDICT_SESSION_DENIED,
            records[0]["verdict"]!!.jsonPrimitive.content,
            "授权拒绝须记会话授权拒绝",
        )
        auditDir.deleteRecursively()
        Unit
    }

    // ── fake 执行器：argv 组装 + 落盘 register 链（临时目录真读写） ──

    @Test
    fun `快照落盘 register 链：临时目录真写、围栏登记放行、围栏外恒拒`() = runBlocking {
        val snapshotDir = Files.createTempDirectory("dev-snaplog-real").toFile()
        val context = ToolExecutionContext(emptyList())
        val recorded = mutableListOf<List<String>>()
        val canned = "10-14 12:00:00.000  1000  2000 D BootTag : hello\n"
        val tool = snaplogTool(
            recorded = recorded,
            source = FakeSource(adbPath = "/opt/sdk/platform-tools/adb"),
            executor = { ArgvOutcome(0, canned, outputTruncated = false, timedOut = false) },
            snapshotDir = snapshotDir,
            context = context,
        )
        val ok = tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Ok
            ?: fail("正常快照须 Ok 回执")
        assertEquals(
            listOf("/opt/sdk/platform-tools/adb", "-s", "ABC123", "logcat", "-d", "-t", "1000"),
            recorded.single(),
            "argv 组装须与固定模板一致（自定义 adb 路径进 argv[0]，行数走缺省 1000）",
        )
        // 落盘真读写：文件存在、内容 = 原样输出（不脱敏——分析底稿保真）
        val payload = ok.data as ToolPayload.SnaplogPayload
        val snapshot = File(payload.snapshotPath)
        assertTrue(snapshot.isFile, "快照必须真实落盘：${payload.snapshotPath}")
        assertTrue(snapshot.canonicalFile.parentFile == snapshotDir.canonicalFile, "快照必须落在注入的临时目录")
        assertEquals(canned, snapshot.readText(), "快照文件本体 = 原样输出（不脱敏）")
        // 围栏 register 链：登记后分析链语义（isAllowed/authorize）放行
        assertTrue(context.isAllowed(snapshot), "快照必须登记进文件围栏（log.* 分析链的消费前提）")
        assertEquals(snapshot.canonicalFile, context.authorize(payload.snapshotPath), "authorize 须放行已登记快照")
        assertFalse(context.isAllowed(File("/etc/passwd")), "围栏外路径恒拒（登记只放宽产物本体）")
        assertEquals(1, context.allowedCount, "允许集只含本快照（空初始围栏 + 产物登记）")
        // artifacts 与 payload 同源
        assertEquals(payload.snapshotPath, ok.artifacts.single().path, "产物引用须与落盘路径同源")
        assertEquals(0, payload.exitCode)
        assertFalse(payload.truncated)
        snapshotDir.deleteRecursively()
        Unit
    }

    @Test
    fun `同刻同串号落盘避让不顶替`() = runBlocking {
        val snapshotDir = Files.createTempDirectory("dev-snaplog-avoid").toFile()
        val context = ToolExecutionContext(emptyList())
        val tool = snaplogTool(
            source = FakeSource(),
            executor = { ArgvOutcome(0, "line\n", outputTruncated = false, timedOut = false) },
            snapshotDir = snapshotDir,
            context = context,
        )
        val first = (tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as ToolResult.Ok)
        val second = (tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as ToolResult.Ok)
        val p1 = first.data as ToolPayload.SnaplogPayload
        val p2 = second.data as ToolPayload.SnaplogPayload
        assertTrue(p1.snapshotPath != p2.snapshotPath, "同刻同串号的两次快照必须避让不顶替")
        assertTrue(File(p1.snapshotPath).isFile && File(p2.snapshotPath).isFile, "两次快照都必须留存")
        snapshotDir.deleteRecursively()
        Unit
    }

    @Test
    fun `回执含 serial argv exit 快照路径与下一步指引`() = runBlocking {
        val snapshotDir = Files.createTempDirectory("dev-snaplog-receipt").toFile()
        val tool = snaplogTool(
            source = FakeSource(),
            executor = { ArgvOutcome(0, "hello logcat", outputTruncated = false, timedOut = false) },
            snapshotDir = snapshotDir,
        )
        val ok = tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Ok
            ?: fail("正常快照须 Ok 回执")
        assertTrue("serial=ABC123" in ok.summary, "回执须含 serial：${ok.summary}")
        assertTrue("argv: adb -s ABC123 logcat -d -t 1000" in ok.summary, "回执须含 argv：${ok.summary}")
        assertTrue("exit=0" in ok.summary, "回执须含退出码：${ok.summary}")
        assertTrue("snapshot: " in ok.summary, "回执须含快照落盘路径：${ok.summary}")
        assertTrue("log.stats" in ok.summary, "回执须含分析链指引：${ok.summary}")
        assertTrue("hello logcat" in ok.summary, "回执须含输出预览：${ok.summary}")
        snapshotDir.deleteRecursively()
        Unit
    }

    @Test
    fun `非零退出码按数据回执并提示快照可能不完整`() = runBlocking {
        val snapshotDir = Files.createTempDirectory("dev-snaplog-exit").toFile()
        val tool = snaplogTool(
            source = FakeSource(),
            executor = { ArgvOutcome(1, "partial", outputTruncated = false, timedOut = false) },
            snapshotDir = snapshotDir,
        )
        val ok = tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Ok
            ?: fail("非零退出码是数据非失败信号，仍须 Ok 回执")
        assertTrue("exit=1" in ok.summary, "回执须含退出码")
        assertTrue("非零" in ok.summary && "不完整" in ok.summary, "非零须带残缺提示：${ok.summary}")
        assertEquals(1, (ok.data as ToolPayload.SnaplogPayload).exitCode)
        snapshotDir.deleteRecursively()
        Unit
    }

    // ── 脱敏分界：回执遮蔽、文件本体不脱敏 ──

    @Test
    fun `回执预览面遮蔽凭据形态而快照文件保真`() = runBlocking {
        val snapshotDir = Files.createTempDirectory("dev-snaplog-sanitize").toFile()
        val output = "GET /auth?token=super_secret_value ok\nplain log line\n"
        val tool = snaplogTool(
            source = FakeSource(),
            executor = { ArgvOutcome(0, output, outputTruncated = false, timedOut = false) },
            snapshotDir = snapshotDir,
        )
        val ok = tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Ok
            ?: fail("正常快照须 Ok 回执")
        assertTrue("super_secret_value" !in ok.summary, "回执预览须遮蔽凭据形态：${ok.summary}")
        val file = File((ok.data as ToolPayload.SnaplogPayload).snapshotPath)
        assertTrue("super_secret_value" in file.readText(), "快照文件本体不脱敏（分析底稿保真）：${file.readText()}")
        snapshotDir.deleteRecursively()
        Unit
    }

    @Test
    fun `超长输出回执恒 8K 收口且快照文件保留捕获上限内全文`() = runBlocking {
        val snapshotDir = Files.createTempDirectory("dev-snaplog-cap").toFile()
        val huge = "x".repeat(100_000)
        val tool = snaplogTool(
            source = FakeSource(),
            executor = { ArgvOutcome(0, huge, outputTruncated = false, timedOut = false) },
            snapshotDir = snapshotDir,
        )
        val ok = tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Ok
            ?: fail("超长输出仍须 Ok 回执（截断是渲染面不是失败）")
        assertTrue(ok.summary.length <= 8192, "回执总长恒 ≤ 8K（实际 ${ok.summary.length}）")
        assertTrue((ok.data as ToolPayload.SnaplogPayload).truncated, "负载须标记截断")
        assertEquals(100_000, File((ok.data as ToolPayload.SnaplogPayload).snapshotPath).length().toInt(), "快照文件不受 8K 回执上界影响")
        snapshotDir.deleteRecursively()
        Unit
    }

    // ── 设备清单工具回执 ──

    @Test
    fun `dev devices 回执列出串号与型号且 serial 原样回显`() = runBlocking {
        val tool = DevTools.DeviceListTool(
            source = FakeSource(devices = listOf(DeviceInfo("ABC123", "Pixel 7"), DeviceInfo("192.168.1.5:5555", ""))),
            audit = SysAuditLog.NOOP,
            devGate = FakeDevGate(),
        )
        val ok = tool.executeTyped(DevTools.DeviceListTool.Args()) as? ToolResult.Ok ?: fail("清单须 Ok 回执")
        assertTrue("devices=2" in ok.summary, "回执须含设备数：${ok.summary}")
        assertTrue("serial=ABC123" in ok.summary, "回执须含串号：${ok.summary}")
        assertTrue("model=Pixel 7" in ok.summary, "回执须含型号：${ok.summary}")
        assertTrue("serial=192.168.1.5:5555" in ok.summary, "串号原样回显（round-trip 契约，不遮蔽）：${ok.summary}")
        assertTrue("dev.snaplog" in ok.summary, "回执须指引 serial 用途：${ok.summary}")
        assertEquals(2, (ok.data as ToolPayload.DeviceListPayload).deviceCount)
    }

    @Test
    fun `dev devices 零设备是正常答案非失败`() = runBlocking {
        val tool = DevTools.DeviceListTool(
            source = FakeSource(devices = emptyList()),
            audit = SysAuditLog.NOOP,
            devGate = FakeDevGate(),
        )
        val ok = tool.executeTyped(DevTools.DeviceListTool.Args()) as? ToolResult.Ok ?: fail("零设备须 Ok 回执")
        assertTrue("devices=0" in ok.summary, "零设备须显式计数：${ok.summary}")
        assertTrue("USB 调试" in ok.summary, "回执须给用户指引：${ok.summary}")
        assertEquals(0, (ok.data as ToolPayload.DeviceListPayload).deviceCount)
    }

    // ── 检测失败面（与「检测成功但无设备」二分） ──

    @Test
    fun `检测失败按 FAILED 拒收且两件都不触执行器`() = runBlocking {
        val snapshotDir = Files.createTempDirectory("dev-snaplog-detecterr").toFile()
        val source = FakeSource(detectError = "设备检测失败：adb 不存在")
        val snaplog = snaplogTool(
            source = source,
            executor = { argv -> fail("检测失败不得触执行器（argv=$argv）") },
            snapshotDir = snapshotDir,
        )
        val err = snaplog.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Err
            ?: fail("检测失败必须 FAILED")
        assertEquals(ToolResult.ErrCode.FAILED, err.code)
        assertTrue("检测失败" in err.message, "回执须携带检测失败信息：${err.message}")
        val devices = DevTools.DeviceListTool(source = source, audit = SysAuditLog.NOOP, devGate = FakeDevGate())
        val err2 = devices.executeTyped(DevTools.DeviceListTool.Args()) as? ToolResult.Err
            ?: fail("检测失败必须 FAILED")
        assertEquals(ToolResult.ErrCode.FAILED, err2.code)
        snapshotDir.deleteRecursively()
        Unit
    }

    // ── 执行失败面 ──

    @Test
    fun `adb 不可用按 NOT_FOUND 且禁止编造`() = runBlocking {
        val snapshotDir = Files.createTempDirectory("dev-snaplog-io").toFile()
        val tool = snaplogTool(
            source = FakeSource(),
            executor = { throw IOException("Cannot run program adb") },
            snapshotDir = snapshotDir,
        )
        val err = tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Err
            ?: fail("启动失败必须 NOT_FOUND")
        assertEquals(ToolResult.ErrCode.NOT_FOUND, err.code)
        assertTrue("启动失败" in err.message, "回执须人话说明 adb 不可用：${err.message}")
        assertTrue(err.hint.orEmpty().contains("禁止编造"), "提示须含禁止编造：${err.hint}")
        snapshotDir.deleteRecursively()
        Unit
    }

    @Test
    fun `超时按 FAILED 且提示收窄重试`() = runBlocking {
        val snapshotDir = Files.createTempDirectory("dev-snaplog-timeout").toFile()
        val tool = snaplogTool(
            source = FakeSource(),
            executor = { ArgvOutcome(-1, "", outputTruncated = false, timedOut = true) },
            snapshotDir = snapshotDir,
        )
        val err = tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123")) as? ToolResult.Err
            ?: fail("超时必须 FAILED")
        assertEquals(ToolResult.ErrCode.FAILED, err.code)
        assertTrue("超时" in err.message, "回执须明示超时：${err.message}")
        snapshotDir.deleteRecursively()
        Unit
    }

    // ── 审计落盘（每次执行含被拒落审计；输出全文不落） ──

    @Test
    fun `每次执行落审计：成功记 argv 全文、白名单拒收记槽形态`() = runBlocking {
        val auditDir = Files.createTempDirectory("dev-snaplog-audit").toFile()
        val snapshotDir = Files.createTempDirectory("dev-snaplog-audit2").toFile()
        val tool = DevTools.SnaplogTool(
            context = ToolExecutionContext(emptyList()),
            source = FakeSource(),
            executor = { ArgvOutcome(0, "ok", outputTruncated = false, timedOut = false) },
            audit = SysAuditLog(auditDir),
            trigger = "chatId=oc_x sender=ou_y",
            devGate = FakeDevGate(),
            snapshotDir = snapshotDir,
        )
        tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123"))
        tool.executeTyped(DevTools.SnaplogTool.Args(serial = "EVIL999"))
        val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
        assertTrue(file.isFile, "审计必须落盘：${file.path}")
        val records = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(2, records.size, "成功与拒收各一行（含被拒）")
        fun field(i: Int, name: String) = records[i][name]!!.jsonPrimitive.content
        assertEquals("dev.snaplog", field(0, "tool"))
        assertEquals("adb -s ABC123 logcat -d -t 1000", field(0, "action"), "argv 全文入审计")
        assertEquals(SysAuditLog.VERDICT_SESSION_GATED, field(0, "verdict"), "门控放行后的执行记会话门控放行")
        assertEquals("chatId=oc_x sender=ou_y", field(0, "trigger"), "触发者入审计")
        assertEquals(SysAuditLog.VERDICT_BAD_ARGS, field(1, "verdict"), "白名单拒收也入审计")
        assertTrue(field(1, "action").startsWith("serial=EVIL999"), "未成 argv 的拒收记 serial 形态：${field(1, "action")}")
        records.forEach { record ->
            val summary = record["outputSummary"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(summary.length <= SysAuditLog.SUMMARY_MAX_CHARS, "输出摘要恒 ≤ 200 字符（全文不落）")
        }
        auditDir.deleteRecursively()
        snapshotDir.deleteRecursively()
        Unit
    }

    // ── 装配缝 ──

    @Test
    fun `snaplog 快照目录按需创建`() = runBlocking {
        val parent = Files.createTempDirectory("dev-snaplog-mkdir").toFile()
        val snapshotDir = File(parent, "devlogs")
        assertFalse(snapshotDir.exists(), "前置：目录不存在（按需创建契约）")
        val tool = snaplogTool(
            source = FakeSource(),
            executor = { ArgvOutcome(0, "ok", outputTruncated = false, timedOut = false) },
            snapshotDir = snapshotDir,
        )
        tool.executeTyped(DevTools.SnaplogTool.Args(serial = "ABC123"))
        assertTrue(snapshotDir.isDirectory, "落盘时必须按需 mkdirs")
        parent.deleteRecursively()
        Unit
    }

    // ── fake 装配（单测不触真实进程与真实检测） ──

    private fun snaplogTool(
        recorded: MutableList<List<String>> = mutableListOf(),
        source: FakeSource = FakeSource(),
        executor: suspend (List<String>) -> ArgvOutcome,
        snapshotDir: File = Files.createTempDirectory("dev-snaplog").toFile(),
        context: ToolExecutionContext = ToolExecutionContext(emptyList()),
        devGate: DevTools.DevSessionGate = FakeDevGate(),
    ): DevTools.SnaplogTool =
        DevTools.SnaplogTool(
            context = context,
            source = source,
            executor = DevTools.SnaplogExecutor { argv ->
                recorded.add(argv)
                executor(argv)
            },
            audit = SysAuditLog.NOOP,
            devGate = devGate,
            snapshotDir = snapshotDir,
        )
}
