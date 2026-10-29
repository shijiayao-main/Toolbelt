package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.easy_dev_toolbox.core.system.ArgvOutcome
import com.jiaoay.easy_dev_toolbox.core.system.ArgvProcessRunner
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * sys.probe 守护：
 * 1. 目录只读性静态校验：全量条目 argv 零 shell 元字符（白名单字符集形态断言）、
 *    可执行文件零黑名单命中、systemctl/journalctl 恒带 --user、条目量级 10..15；
 * 2. 注册面：sys.probe 目录注册 + Risk=READ_ONLY + uiHint=null（纯 agent 工具）+
 *    names 单源 + resolve 可解析；
 * 3. 会话门控（P1c）：门控面缺席恒 DENIED（fail-closed）、授权拒绝恒 DENIED、
 *    审计记会话授权拒绝；
 * 4. 非法 key 拒收人话（回执列可用条目）；
 * 5. 带参槽白名单/钳制/形态校验矩阵：服务名白名单外拒收、schema.key 白名单外拒收、
 *    uuid 形态外拒收、行数越界钳到边界、未约定槽传值拒收、必填槽缺失拒收；
 * 6. fake 执行器回执形态：argv 组装正确性、key/exit/output 回执、非零退出码按数据
 *    回执并带解读提示、结构化负载字段、8K capAt 输出上界独立断言；
 * 7. 失败面：超时 FAILED、命令不可用 NOT_FOUND（fake 抛 IOException）。
 */
class SysProbeToolsTest {

    /** 测试门控面（approved=false = 授权卡拒绝/超时形态） */
    private class FakeSysGate(private val approved: Boolean = true) : SysSessionGate {
        var asked = 0
        override suspend fun requireSessionAuthorization(toolName: String): Boolean {
            asked++
            return approved
        }
        override val familyEscapeGate: ToolExecutionContext.PathEscapeGate =
            ToolExecutionContext.PathEscapeGate { _ -> approved }
        override val trigger: String? = "chatId=oc_test sender=ou_test"
    }

    // ── 目录只读性静态校验（机械断言：每条 argv 的只读性不靠人工自觉） ──

    /** 全量条目的代表性合法 argv（必填槽用白名单首项/形态合法值填充） */
    private fun canonicalArgv(key: SysProbeTools.ProbeKey): List<String> {
        val params = buildMap {
            if (SysProbeTools.ParamKind.SERVICE in key.required) {
                put(SysProbeTools.ParamKind.SERVICE, SysProbeTools.SERVICE_WHITELIST.first())
            }
            if (SysProbeTools.ParamKind.SCHEMA_KEY in key.required) {
                put(SysProbeTools.ParamKind.SCHEMA_KEY, SysProbeTools.GSETTINGS_KEYS.first())
            }
            if (SysProbeTools.ParamKind.UUID in key.required) {
                put(SysProbeTools.ParamKind.UUID, "ubuntu-dock@ubuntu.com")
            }
        }
        return key.buildArgv(params)
    }

    @Test
    fun `目录条目 argv 零 shell 元字符且零黑名单命中`() {
        // argv 纯语义下元字符本就无解释面，本断言守的是「目录条目绝不向 shell 语义漂移」：
        // 任何含管道/重定向/命令替换/引号/路径分隔的元素混入目录即红（加条目走提案评审）
        val argvElementRegex = Regex("""^[A-Za-z0-9-][A-Za-z0-9._@:=/-]*$""")
        for (key in SysProbeTools.ProbeKey.entries) {
            val argv = canonicalArgv(key)
            assertTrue(argv.isNotEmpty(), "${key.name} argv 不能为空")
            for (element in argv) {
                assertTrue(
                    argvElementRegex.matches(element),
                    "${key.name} 的 argv 元素「$element」含白名单字符集外字符（shell 语义漂移）",
                )
            }
            val executable = argv.first()
            assertTrue(
                executable !in ArgvProcessRunner.BLOCKED_EXECUTABLES,
                "${key.name} 可执行文件「$executable」命中提权/包管理黑名单",
            )
            assertTrue("/" !in executable, "${key.name} 可执行文件须是命令名非路径形态")
        }
    }

    @Test
    fun `systemctl 与 journalctl 条目恒带 user`() {
        for (key in SysProbeTools.ProbeKey.entries) {
            val argv = canonicalArgv(key)
            when (argv.first()) {
                "systemctl" -> assertTrue(
                    "--user" in argv,
                    "${key.name} 的 systemctl 形态必须恒带 --user（系统级服务面泄漏）",
                )
                "journalctl" -> assertTrue(
                    "--user" in argv,
                    "${key.name} 的 journalctl 形态必须恒带 --user（系统日志面泄漏）",
                )
            }
        }
    }

    @Test
    fun `首批目录量级在 10 到 15 条`() {
        val size = SysProbeTools.ProbeKey.entries.size
        assertTrue(
            size in 10..15,
            "首批目录 10~15 条（实际 $size）——加条目走提案评审并同步本断言",
        )
    }

    // ── 注册面 ──

    @Test
    fun `sys probe 注册且风险只读、不进命令面板、names 单源`() {
        val descriptor = AgentToolCatalog.all.firstOrNull { it.name == "sys.probe" }
            ?: error("sys.probe 未注册进 AgentToolCatalog")
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, descriptor.risk, "只读零产物，必须 READ_ONLY")
        assertNull(descriptor.uiHint, "纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertEquals(descriptor.aliases, emptySet<String>(), "无别名（撞面零容忍）")
        assertEquals(
            setOf(
                "sys.probe", "sys.read_config", "sys.repair", "sys.write_config",
                "sys.service", "sys.verify", "sys.handoff",
            ),
            SysProbeTools.SYS_NAMES,
            "names 集单源（消费方选择集引用此集合；repair/write/service/verify/handoff 随各自动作域并入）",
        )
        assertEquals(
            "sys.probe",
            AgentToolCatalog.resolve("sys.probe"),
            "目录实名必须可经 resolve 解析（「列了没注册」零容忍）",
        )
        assertEquals(
            8192,
            SysProbeTools.MAX_OUTPUT_CHARS,
            "输出上界字面值独立断言（与实现常量漂移解耦，防静默放宽）",
        )
    }

    // ── 会话门控（P1c 装配门控：执行前必过授权；fail-closed） ──

    @Test
    fun `门控面缺席恒 DENIED 且不触执行器（fail-closed）`() = runBlocking {
        val tool = SysProbeTools.SysProbeTool(
            executor = SysProbeTools.ProbeExecutor { argv -> fail("门控面缺席不得触执行器（argv=$argv）") },
            audit = SysAuditLog.NOOP,
            sysGate = null,
        )
        val err = tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "im_config_mode"))
            as? ToolResult.Err ?: fail("门控面缺席必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("门控面" in err.message, "回执须指明门控面缺席（装配缺陷）：${err.message}")
    }

    @Test
    fun `会话授权拒绝恒 DENIED 且提示引导批准、不触执行器`() = runBlocking {
        val tool = SysProbeTools.SysProbeTool(
            executor = SysProbeTools.ProbeExecutor { argv -> fail("授权拒绝不得触执行器（argv=$argv）") },
            audit = SysAuditLog.NOOP,
            sysGate = FakeSysGate(approved = false),
        )
        val err = tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "im_config_mode"))
            as? ToolResult.Err ?: fail("授权拒绝必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("信任用户" in err.message, "回执须引导用户加入信任用户名单：${err.message}")
        assertTrue("禁止编造" in err.message, "回执须钉禁止编造诊断结果：${err.message}")
    }

    @Test
    fun `授权拒绝与门控缺席落审计记会话授权拒绝`() = runBlocking {
        val auditDir = Files.createTempDirectory("sys-probe-gate-audit").toFile()
        val tool = SysProbeTools.SysProbeTool(
            executor = SysProbeTools.ProbeExecutor { fail("拒绝路径不得触执行器") },
            audit = SysAuditLog(auditDir),
            sysGate = FakeSysGate(approved = false),
        )
        tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "im_config_mode"))
        val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
        val records = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(1, records.size)
        assertEquals(
            SysAuditLog.VERDICT_SESSION_DENIED,
            records[0]["verdict"]!!.jsonPrimitive.content,
            "授权拒绝须记会话授权拒绝（区别于围栏拒绝）",
        )
        auditDir.deleteRecursively()
        Unit
    }

    // ── 非法 key 拒收 ──

    @Test
    fun `未知 key 拒收并回执可用条目`() = runBlocking {
        val tool = fakeTool { argv -> fail("非法 key 不得触执行器（argv=$argv）") }
        for (bad in listOf("rm -rf /tmp", "shell: ls -la", "no_such_key", "")) {
            val err = tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = bad)) as? ToolResult.Err
                ?: fail("未知 key 必须 BAD_ARGS，实际输入「$bad」")
            assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
            assertTrue("可用条目：im_config_mode" in err.message, "回执须列可用条目：${err.message}")
            assertTrue("可用条目：no_such_key" !in err.message, "回执不得把非法 key 混进可用条目清单：${err.message}")
        }
    }

    // ── 带参槽校验矩阵 ──

    @Test
    fun `服务名白名单外拒收人话`() = runBlocking {
        val tool = fakeTool { argv -> fail("白名单外服务名不得触执行器（argv=$argv）") }
        val err = tool.executeTyped(
            SysProbeTools.SysProbeTool.Args(key = "systemd_user_status", service = "evil-unit"),
        ) as? ToolResult.Err ?: fail("白名单外服务名必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
        assertTrue("evil-unit" in err.message, "回执须指明被拒值：${err.message}")
        assertTrue("fcitx5" in err.message, "回执须列白名单（LLM 可自行调整）：${err.message}")
        assertTrue("SERVICE_WHITELIST" in err.message, "回执须给出加白名单路径（改常量）：${err.message}")
    }

    @Test
    fun `schema key 白名单外拒收人话`() = runBlocking {
        val tool = fakeTool { argv -> fail("白名单外 schema.key 不得触执行器（argv=$argv）") }
        val err = tool.executeTyped(
            SysProbeTools.SysProbeTool.Args(key = "gsettings_get", schema_key = "org.evil.secret"),
        ) as? ToolResult.Err ?: fail("白名单外 schema.key 必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
        assertTrue("org.evil.secret" in err.message, "回执须指明被拒值：${err.message}")
        assertTrue("org.gnome.shell.enabled-extensions" in err.message, "回执须列白名单：${err.message}")
    }

    @Test
    fun `uuid 形态外拒收人话`() = runBlocking {
        val tool = fakeTool { argv -> fail("形态外 uuid 不得触执行器（argv=$argv）") }
        for (bad in listOf("not-a-uuid", "a b@c", "@domain.only", "x@")) {
            val err = tool.executeTyped(
                SysProbeTools.SysProbeTool.Args(key = "gnome_extensions_info", uuid = bad),
            ) as? ToolResult.Err ?: fail("形态外 uuid「$bad」必须 BAD_ARGS")
            assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
            assertTrue("uuid@domain" in err.message, "回执须给出合法形态：${err.message}")
        }
    }

    @Test
    fun `行数越界钳到边界且缺省走 50`() = runBlocking {
        val recorded = mutableListOf<List<String>>()
        val canned = ArgvOutcome(exitCode = 0, output = "ok", outputTruncated = false, timedOut = false)
        val tool = fakeTool(recorded, canned)
        tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "journalctl_user_tail"))
        tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "journalctl_user_tail", lines = 5))
        tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "journalctl_user_tail", lines = 999))
        tool.executeTyped(
            SysProbeTools.SysProbeTool.Args(key = "journalctl_user_unit", service = "fcitx5", lines = 300),
        )
        val flags = recorded.map { argv -> argv[argv.indexOf("-n") + 1] }
        assertEquals(listOf("50", "10", "200", "200"), flags, "行数须钳制到 10..200（缺省 50）：$flags")
        assertEquals(
            listOf("fcitx5"),
            recorded.last().let { argv -> argv.subList(argv.indexOf("-u") + 1, argv.size) },
            "journalctl_user_unit 的 -u 槽须取服务名白名单值",
        )
    }

    @Test
    fun `未约定槽传值拒收且必填槽缺失拒收`() = runBlocking {
        val tool = fakeTool { argv -> fail("槽纪律违规不得触执行器（argv=$argv）") }
        val unexpected = tool.executeTyped(
            SysProbeTools.SysProbeTool.Args(key = "im_config_mode", service = "fcitx5"),
        ) as? ToolResult.Err ?: fail("无槽条目带参必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, unexpected.code)
        assertTrue("service" in unexpected.message, "回执须指明未约定的槽：${unexpected.message}")

        val missing = tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "systemd_user_status"))
            as? ToolResult.Err ?: fail("必填槽缺失必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, missing.code)
        assertTrue("service" in missing.message, "回执须指明缺失的必填槽：${missing.message}")
    }

    // ── fake 执行器回执形态 ──

    @Test
    fun `回执含 key argv exit 与输出且结构化负载同源`() = runBlocking {
        val recorded = mutableListOf<List<String>>()
        val canned = ArgvOutcome(exitCode = 1, output = "default\nfcitx5\n", outputTruncated = false, timedOut = false)
        val tool = fakeTool(recorded, canned)
        val result = tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "im_config_mode"))
        assertEquals(listOf("im-config", "-m"), recorded.single(), "argv 组装须与目录模板一致")
        val ok = result as? ToolResult.Ok ?: fail("正常探查须 Ok 回执，实际 $result")
        assertTrue("key=im_config_mode" in ok.summary, "回执须含 key：${ok.summary}")
        assertTrue("argv: im-config -m" in ok.summary, "回执须含 argv：${ok.summary}")
        assertTrue("exit=1" in ok.summary, "回执须含退出码：${ok.summary}")
        assertTrue("fcitx5" in ok.summary, "回执须含输出：${ok.summary}")
        assertTrue("非零" in ok.summary, "非零退出码须带「按命令语义解读」提示：${ok.summary}")
        val payload = ok.data as? ToolPayload.ProbePayload ?: fail("结构化负载须为 ProbePayload")
        assertEquals("im_config_mode", payload.key)
        assertEquals(1, payload.exitCode, "退出码是数据非失败信号")
        assertEquals(false, payload.truncated)
    }

    @Test
    fun `零退出码回执不带非零解读提示`() = runBlocking {
        val canned = ArgvOutcome(exitCode = 0, output = "ubuntu-dock@ubuntu.com\n", outputTruncated = false, timedOut = false)
        val tool = fakeTool(canned = canned)
        val ok = tool.executeTyped(
            SysProbeTools.SysProbeTool.Args(key = "gnome_extensions_list"),
        ) as? ToolResult.Ok ?: fail("exit=0 须 Ok 回执")
        assertTrue("非零" !in ok.summary, "零退出码不得带非零解读提示：${ok.summary}")
    }

    @Test
    fun `gsettings 的 schema key 槽拆为 schema 与 key 两段`() = runBlocking {
        val recorded = mutableListOf<List<String>>()
        val tool = fakeTool(recorded, canned = ArgvOutcome(0, "'Yaru'", false, false))
        tool.executeTyped(
            SysProbeTools.SysProbeTool.Args(
                key = "gsettings_get",
                schema_key = "org.gnome.desktop.interface.gtk-theme",
            ),
        )
        assertEquals(
            listOf("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"),
            recorded.single(),
            "schema.key 连写槽须拆为两段 argv",
        )
    }

    @Test
    fun `超长输出恒 8K capAt 收口且标记截断`() = runBlocking {
        val huge = "x".repeat(50_000)
        val tool = fakeTool(canned = ArgvOutcome(0, huge, outputTruncated = false, timedOut = false))
        val ok = tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "kernel_version"))
            as? ToolResult.Ok ?: fail("超长输出仍须 Ok 回执（截断是渲染面不是失败）")
        assertTrue(ok.summary.length <= 8192, "回执总长恒 ≤ 8K（实际 ${ok.summary.length}）")
        assertTrue("截断" in ok.summary, "超上界须带截断标记：${ok.summary.takeLast(60)}")
        assertTrue((ok.data as? ToolPayload.ProbePayload)?.truncated == true, "负载须标记截断")
    }

    @Test
    fun `空输出回执占位不静默`() = runBlocking {
        val tool = fakeTool(canned = ArgvOutcome(0, "", false, false))
        val ok = tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "im_launch_print"))
            as? ToolResult.Ok ?: fail("无输出探查（im-launch --print 不拉起即空）须 Ok 回执")
        assertTrue("（无输出）" in ok.summary, "空输出须显式占位（LLM 禁止编造输出）：${ok.summary}")
    }

    // ── 输出脱敏（sys.probe 输出管道统一过脱敏器） ──

    @Test
    fun `探查输出过脱敏器：token 行遮蔽、IM 变量明文`() = runBlocking {
        val canned = ArgvOutcome(
            exitCode = 0,
            output = "GTK_IM_MODULE=fcitx\nGITHUB_TOKEN=ghp_probe_leak\nfcitx5 running\n",
            outputTruncated = false,
            timedOut = false,
        )
        val tool = fakeTool(canned = canned)
        val ok = tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "im_env_vars"))
            as? ToolResult.Ok ?: fail("正常探查须 Ok 回执")
        assertTrue("GTK_IM_MODULE=fcitx" in ok.summary, "IM 变量保持明文（诊断对象本体）：${ok.summary}")
        assertTrue("GITHUB_TOKEN=***" in ok.summary, "token 行须遮蔽：${ok.summary}")
        assertTrue("ghp_probe_leak" !in ok.summary, "遮蔽后不得残留原值")
        assertTrue("fcitx5 running" in ok.summary, "普通输出行保持原样")
    }

    // ── 审计落盘（每次执行含被拒落审计；输出全文不落） ──

    @Test
    fun `每次执行落审计：成功记 argv 与会话门控放行、拒收记参数拒收`() = runBlocking {
        val auditDir = Files.createTempDirectory("sys-probe-audit").toFile()
        val tool = SysProbeTools.SysProbeTool(
            executor = SysProbeTools.ProbeExecutor { ArgvOutcome(1, "default\nfcitx5\n", false, false) },
            audit = SysAuditLog(auditDir),
            sysGate = FakeSysGate(),
        )
        tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "im_config_mode"))
        tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "no_such_key"))
        val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
        assertTrue(file.isFile, "审计必须落盘：${file.path}")
        val records = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(2, records.size, "成功与拒收各一行（含被拒）")
        fun field(i: Int, name: String) = records[i][name]!!.jsonPrimitive.content
        assertEquals("sys.probe", field(0, "tool"))
        assertEquals("im-config -m", field(0, "action"), "argv 全文入审计")
        assertEquals(SysAuditLog.VERDICT_SESSION_GATED, field(0, "verdict"), "门控放行后的执行记会话门控放行")
        assertEquals("1", records[0]["exitCode"]?.jsonPrimitive?.content, "退出码入审计（数据非失败信号）")
        assertTrue("fcitx5" in field(0, "outputSummary"), "摘要含输出前段")
        assertEquals(SysAuditLog.VERDICT_BAD_ARGS, field(1, "verdict"), "参数拒收也入审计")
        assertTrue(field(1, "action").startsWith("key=no_such_key"), "未成 argv 的拒收记 key 形态：${field(1, "action")}")
        records.forEach { record ->
            val summary = record["outputSummary"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(summary.length <= SysAuditLog.SUMMARY_MAX_CHARS, "输出摘要恒 ≤ 200 字符（全文不落）")
        }
        auditDir.deleteRecursively()
        Unit
    }

    // ── 失败面 ──

    @Test
    fun `超时按 FAILED 回执且提示收窄重试`() = runBlocking {
        val tool = fakeTool { ArgvOutcome(-1, "", false, timedOut = true) }
        val err = tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "fcitx5_diagnose"))
            as? ToolResult.Err ?: fail("超时必须 FAILED")
        assertEquals(ToolResult.ErrCode.FAILED, err.code)
        assertTrue("超时" in err.message, "回执须明示超时：${err.message}")
        assertTrue("fcitx5-diagnose" in err.message, "回执须指明挂起的命令：${err.message}")
    }

    @Test
    fun `命令不可用按 NOT_FOUND 回执且禁止编造输出`() = runBlocking {
        val tool = fakeTool { throw IOException("Cannot run program xyz") }
        val err = tool.executeTyped(SysProbeTools.SysProbeTool.Args(key = "fcitx5_diagnose"))
            as? ToolResult.Err ?: fail("启动失败必须 NOT_FOUND")
        assertEquals(ToolResult.ErrCode.NOT_FOUND, err.code)
        assertTrue("不可用" in err.message || "启动失败" in err.message, "回执须人话说明命令不可用：${err.message}")
        assertTrue(err.hint.orEmpty().contains("禁止编造"), "提示须含禁止编造输出：${err.hint}")
    }

    // ── fake 执行器（单测不触真实进程；失败契约走异常，与真实执行件同款） ──

    private fun fakeTool(
        recorded: MutableList<List<String>> = mutableListOf(),
        canned: ArgvOutcome = ArgvOutcome(0, "ok", false, false),
        sysGate: SysSessionGate = FakeSysGate(),
    ): SysProbeTools.SysProbeTool {
        val executor = SysProbeTools.ProbeExecutor { argv ->
            recorded.add(argv)
            canned
        }
        return SysProbeTools.SysProbeTool(executor = executor, audit = SysAuditLog.NOOP, sysGate = sysGate)
    }

    private fun fakeTool(
        run: suspend (List<String>) -> ArgvOutcome,
    ): SysProbeTools.SysProbeTool =
        SysProbeTools.SysProbeTool(
            executor = SysProbeTools.ProbeExecutor(run),
            audit = SysAuditLog.NOOP,
            sysGate = FakeSysGate(),
        )
}
