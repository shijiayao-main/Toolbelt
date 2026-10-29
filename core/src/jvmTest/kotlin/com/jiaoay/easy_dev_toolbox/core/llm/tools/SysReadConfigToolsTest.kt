package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.testfixtures.UserHomeIsolation
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
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
 * sys.read_config 守护：
 * 1. 注册面：目录注册 + Risk=READ_ONLY + uiHint=null + resolve 可解析 + 上界字面值断言；
 * 2. 会话门控（P1c）：门控面缺席恒 DENIED（fail-closed）、授权拒绝恒 DENIED、
 *    族根助手（familyRoots/isInsideAnyFamilyRoot）+ 审计目录与族根不相交（负面对照锚）；
 * 3. 族纪律：未知族拒收（回执列可用族）、槽纪律（fcitx5_config 缺/多余 relative_path、
 *    相对路径形态校验：绝对路径/~/.. /空段拒收）；
 * 4. 围栏双闸：无门/门拒绝 → DENIED；门批准 + 族根 canonical 包含性（符号链接穿越拒收）；
 * 5. 读取上界：超 256KB 整体拒绝不读头部；回执恒 8K capAt；
 * 6. NOT_FOUND 语义（不存在 = 诊断答案）+ 内容脱敏（token 行遮蔽）+ manifest 摘要形态；
 * 7. 审计：每次执行（含被拒）落审计记录（裁决/动作/摘要形态，输出全文不落）。
 */
class SysReadConfigToolsTest {

    private val home = UserHomeIsolation("sys-read-config-test")
    private val auditDir: File = Files.createTempDirectory("sys-read-config-audit").toFile()

    /** 测试门控面（approved=false = 授权卡拒绝/超时形态） */
    private class FakeSysGate(private val approved: Boolean = true) : SysSessionGate {
        override suspend fun requireSessionAuthorization(toolName: String): Boolean = approved
        override val familyEscapeGate: ToolExecutionContext.PathEscapeGate =
            ToolExecutionContext.PathEscapeGate { _ -> approved }
        override val trigger: String? = "chatId=oc_test sender=ou_test"
    }

    @BeforeTest
    fun setUp() {
        home.redirect()
    }

    @AfterTest
    fun tearDown() {
        home.restore()
        auditDir.deleteRecursively()
    }

    // ── 注册面 ──

    @Test
    fun `sys read config 注册且风险只读、不进命令面板、resolve 可解析`() {
        val descriptor = AgentToolCatalog.all.firstOrNull { it.name == "sys.read_config" }
            ?: error("sys.read_config 未注册进 AgentToolCatalog")
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, descriptor.risk, "只读零产物，必须 READ_ONLY")
        assertNull(descriptor.uiHint, "纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertEquals(descriptor.aliases, emptySet<String>(), "无别名（撞面零容忍）")
        assertEquals(
            "sys.read_config",
            AgentToolCatalog.resolve("sys.read_config"),
            "目录实名必须可经 resolve 解析（「列了没注册」零容忍）",
        )
        assertEquals(
            262144,
            SysReadConfigTools.MAX_FILE_BYTES,
            "单文件读取上界字面值独立断言（256KB——拒绝只读头部，与实现常量漂移解耦）",
        )
        assertEquals(
            8192,
            SysReadConfigTools.MAX_OUTPUT_CHARS,
            "回执上界字面值独立断言（与 sys.probe 同款 8K 真门禁）",
        )
    }

    // ── 会话门控与族根助手（P1c） ──

    @Test
    fun `门控面缺席恒 DENIED 且不触族读取（fail-closed）`() = runBlocking {
        val tool = tool(approveGateContext(), sysGate = null)
        val err = tool.executeTyped(args(family = "fcitx5_config", relative_path = "config"))
            as? ToolResult.Err ?: fail("门控面缺席必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("门控面" in err.message, "回执须指明门控面缺席（装配缺陷）：${err.message}")
    }

    @Test
    fun `会话授权拒绝恒 DENIED 且提示引导批准、不触族读取`() = runBlocking {
        val tool = tool(approveGateContext(), sysGate = FakeSysGate(approved = false))
        val err = tool.executeTyped(args(family = "xprofile")) as? ToolResult.Err
            ?: fail("授权拒绝必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("信任用户" in err.message, "回执须引导用户加入信任用户名单：${err.message}")
        assertTrue("禁止编造" in err.message, "回执须钉禁止编造配置内容：${err.message}")
    }

    @Test
    fun `族根助手：familyRoots 与族根包含性判定同源`() {
        val roots = SysReadConfigTools.familyRoots(home.home)
        assertEquals(3, roots.size, "三族三根（加族走提案评审并同步本断言）")
        // 族内包含
        val fcitx5Root = roots.first { it.path.endsWith("fcitx5") }
        assertTrue(SysReadConfigTools.isInsideAnyFamilyRoot(home.home, fcitx5Root), "族根自身在内")
        assertTrue(
            SysReadConfigTools.isInsideAnyFamilyRoot(home.home, File(fcitx5Root, "conf/pinyin.conf")),
            "族内子路径在内（HITL 缝放行判据）",
        )
        // 族外不含（分隔符边界——/home/u/.config/fcitx5-other 不算 fcitx5 族内）
        assertTrue(
            !SysReadConfigTools.isInsideAnyFamilyRoot(home.home, File(home.home, ".ssh/id_rsa")),
            "凭据路径不在任何族根内（L3 负面清单 4）",
        )
        assertTrue(
            !SysReadConfigTools.isInsideAnyFamilyRoot(home.home, File(home.home, ".config")),
            "整个 ~/.config 粗根不入族（M3 硬约束）",
        )
    }

    @Test
    fun `审计目录不落在任何路径族根内（负面对照锚）`() {
        val auditDir = com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getAuditDir()
        assertTrue(
            !SysReadConfigTools.isInsideAnyFamilyRoot(home.home, auditDir),
            "审计文件永不进 sys.read_config 路径族（能力文档负面清单 13）：${auditDir.path}",
        )
    }

    // ── 族纪律 ──

    @Test
    fun `未知族拒收并回执可用族`() = runBlocking {
        val tool = tool(approveGateContext())
        val err = tool.executeTyped(args(family = "/etc/passwd")) as? ToolResult.Err
            ?: fail("未知族必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
        assertTrue("可用路径族：fcitx5_config" in err.message, "回执须列可用族：${err.message}")
        assertTrue("xprofile" in err.message && "gnome_extensions_manifest" in err.message)
    }

    @Test
    fun `槽纪律：fcitx5 缺相对路径拒收、固定族带相对路径拒收`() = runBlocking {
        val tool = tool(approveGateContext())
        val missing = tool.executeTyped(args(family = "fcitx5_config")) as? ToolResult.Err
            ?: fail("fcitx5_config 缺 relative_path 必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, missing.code)
        assertTrue("relative_path" in missing.message, "回执须指明缺的槽：${missing.message}")

        val unexpected = tool.executeTyped(args(family = "xprofile", relative_path = "config"))
            as? ToolResult.Err ?: fail("固定族带 relative_path 必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, unexpected.code)
        assertTrue("xprofile" in unexpected.message, "回执须指明拒绝的族：${unexpected.message}")
    }

    @Test
    fun `相对路径形态校验：绝对路径、波浪号、点段、空段拒收`() = runBlocking {
        val tool = tool(approveGateContext())
        for (bad in listOf("/etc/passwd", "~/.ssh/id_rsa", "../outside.txt", "a//b", "conf/")) {
            val err = tool.executeTyped(args(family = "fcitx5_config", relative_path = bad))
                as? ToolResult.Err ?: fail("形态外相对路径「$bad」必须 BAD_ARGS")
            assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code, "形态外拒收：$bad")
        }
    }

    // ── 围栏双闸 ──

    @Test
    fun `无门且未命中允许集即 DENIED（装配面前不可达的防线兜底）`() = runBlocking {
        // 空允许集 + 无 HITL 门 = 桥接默认装配形态：路径族读取恒拒
        val tool = tool(ToolExecutionContext())
        val err = tool.executeTyped(args(family = "fcitx5_config", relative_path = "config"))
            as? ToolResult.Err ?: fail("围栏未放行必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("围栏" in err.message, "回执须人话说明围栏语义：${err.message}")
    }

    @Test
    fun `HITL 门拒绝即 DENIED（批准挂起语义由门承载）`() = runBlocking {
        val tool = tool(ToolExecutionContext(escapeGate = { false }))
        val err = tool.executeTyped(args(family = "xprofile")) as? ToolResult.Err
            ?: fail("门拒绝必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
    }

    @Test
    fun `门批准即读取：回执含 family path size 与脱敏内容`() = runBlocking {
        val configDir = File(File(File(home.home, ".config"), "fcitx5").apply { mkdirs() }, "config")
        configDir.writeText(
            "[Hotkey]\nTriggerKeys=Control+Space\n" +
                "GITHUB_TOKEN=ghp_leak_me\n" +
                "DefaultIM=rime\n",
        )
        val tool = tool(approveGateContext())
        val ok = tool.executeTyped(args(family = "fcitx5_config", relative_path = "config"))
            as? ToolResult.Ok ?: fail("门批准的族内读取须 Ok")
        assertTrue("family=fcitx5_config" in ok.summary, "回执须含族名：${ok.summary}")
        assertTrue("path=" in ok.summary, "回执须含路径：${ok.summary}")
        assertTrue("size=" in ok.summary, "回执须含大小：${ok.summary}")
        assertTrue("TriggerKeys=Control+Space" in ok.summary, "热键行保持明文（诊断价值）：${ok.summary}")
        assertTrue("GITHUB_TOKEN=***" in ok.summary, "token 行须遮蔽：${ok.summary}")
        assertTrue("ghp_leak_me" !in ok.summary, "遮蔽后不得残留原值")
        assertTrue("DefaultIM=rime" in ok.summary, "普通配置行保持明文")
        val payload = ok.data as? ToolPayload.ReadConfigPayload ?: fail("结构化负载须为 ReadConfigPayload")
        assertEquals("fcitx5_config", payload.family)
        assertEquals(false, payload.truncated)
    }

    @Test
    fun `族内符号链接穿越拒收（canonical 包含性 fail-safe）`() = runBlocking {
        val outside = File(home.home, "secret-outside.txt").apply { writeText("GITHUB_TOKEN=leak") }
        val fcitx5 = File(File(home.home, ".config"), "fcitx5").apply { mkdirs() }
        val linked = runCatching {
            java.nio.file.Files.createSymbolicLink(
                File(fcitx5, "linked").toPath(),
                outside.toPath(),
            )
        }.isSuccess
        if (!linked) return@runBlocking // 平台无软链权限——跳过，不误报
        val tool = tool(approveGateContext())
        // 门即使批准（模拟批准绕过），canonical 包含性仍须拒
        val err = tool.executeTyped(args(family = "fcitx5_config", relative_path = "linked"))
            as? ToolResult.Err ?: fail("符号链接穿越必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("族根之外" in err.message, "回执须说明穿越拒收：${err.message}")
    }

    // ── 读取上界 ──

    @Test
    fun `超 256KB 文件整体拒绝不读头部`() = runBlocking {
        val configDir = File(File(File(home.home, ".config"), "fcitx5").apply { mkdirs() }, "config")
        configDir.writeText("x".repeat(SysReadConfigTools.MAX_FILE_BYTES + 1))
        val tool = tool(approveGateContext())
        val err = tool.executeTyped(args(family = "fcitx5_config", relative_path = "config"))
            as? ToolResult.Err ?: fail("超上界必须拒绝")
        assertEquals(ToolResult.ErrCode.FAILED, err.code)
        assertTrue("超读取上界" in err.message, "回执须明示上界拒绝：${err.message}")
        assertTrue("x" !in err.message, "拒绝即不读头部（无内容残段）：${err.message}")
    }

    @Test
    fun `超长回执恒 8K capAt 收口且标记截断`() = runBlocking {
        val configDir = File(File(File(home.home, ".config"), "fcitx5").apply { mkdirs() }, "config")
        configDir.writeText("y".repeat(50_000))
        val tool = tool(approveGateContext())
        val ok = tool.executeTyped(args(family = "fcitx5_config", relative_path = "config"))
            as? ToolResult.Ok ?: fail("超长内容仍须 Ok（截断是渲染面不是失败）")
        assertTrue(ok.summary.length <= 8192, "回执总长恒 ≤ 8K（实际 ${ok.summary.length}）")
        assertTrue((ok.data as? ToolPayload.ReadConfigPayload)?.truncated == true, "负载须标记截断")
    }

    // ── NOT_FOUND 语义 ──

    @Test
    fun `xprofile 不存在按 NOT_FOUND 回执且提示是诊断答案`() = runBlocking {
        val tool = tool(approveGateContext())
        val err = tool.executeTyped(args(family = "xprofile")) as? ToolResult.Err
            ?: fail("文件不存在必须 NOT_FOUND")
        assertEquals(ToolResult.ErrCode.NOT_FOUND, err.code)
        assertTrue("不存在" in err.message && "诊断答案" in err.message, "不存在 = 诊断答案语义：${err.message}")
        assertTrue("im_env_vars" in err.hint.orEmpty(), "提示须给运行态替代条目：${err.hint}")
    }

    @Test
    fun `xprofile 存在即读取（export 行同受脱敏与放行）`() = runBlocking {
        File(home.home, ".xprofile").writeText(
            "export GTK_IM_MODULE=fcitx\nexport XMODIFIERS=@im=fcitx\nexport MY_API_KEY=leak\n",
        )
        val tool = tool(approveGateContext())
        val ok = tool.executeTyped(args(family = "xprofile")) as? ToolResult.Ok
            ?: fail("存在的族固定文件须 Ok")
        assertTrue("export GTK_IM_MODULE=fcitx" in ok.summary, "IM export 行保持明文：${ok.summary}")
        assertTrue("export MY_API_KEY=***" in ok.summary, "key export 行须遮蔽：${ok.summary}")
        assertTrue("leak" !in ok.summary, "遮蔽后不得残留原值")
    }

    // ── manifest 摘要形态 ──

    @Test
    fun `gnome 扩展清单走 manifest 摘要形态（逐扩展字段行，不回灌全文）`() = runBlocking {
        val extRoot = File(File(File(home.home, ".local"), "share").apply { mkdirs() }, "gnome-shell/extensions")
        val extA = File(extRoot.apply { mkdirs() }, "tophat@fflewddur.github.io").apply { mkdirs() }
        File(extA, "metadata.json").writeText(
            """
            {
              "name": "TopHat",
              "uuid": "tophat@fflewddur.github.io",
              "version": 24,
              "shell-version": ["45", "48"],
              "description": "View CPU, memory, disk, and network activity.",
              "url": "https://github.com/fflewddur/tophat",
              "extra-noise": "not-in-digest"
            }
            """.trimIndent(),
        )
        val extB = File(extRoot, "broken@example.com").apply { mkdirs() } // 无 metadata.json
        val tool = tool(approveGateContext())
        val ok = tool.executeTyped(args(family = "gnome_extensions_manifest")) as? ToolResult.Ok
            ?: fail("清单族读取须 Ok")
        assertTrue("extensions=2" in ok.summary, "回执须含扩展计数：${ok.summary}")
        assertTrue("uuid=tophat@fflewddur.github.io name=TopHat version=24 shell-version=45..48" in ok.summary)
        assertTrue("description=View CPU, memory, disk, and network activity." in ok.summary)
        assertTrue("url=https://github.com/fflewddur/tophat" in ok.summary)
        assertTrue("extra-noise" !in ok.summary, "摘要形态不回灌 JSON 全文")
        assertTrue("uuid=broken@example.com metadata.json 缺失" in ok.summary, "缺失不静默：${ok.summary}")
        val payload = ok.data as? ToolPayload.ReadConfigPayload ?: fail("负载须为 ReadConfigPayload")
        assertEquals("gnome_extensions_manifest", payload.family)
    }

    @Test
    fun `gnome 扩展目录不存在按 NOT_FOUND 回执`() = runBlocking {
        val tool = tool(approveGateContext())
        val err = tool.executeTyped(args(family = "gnome_extensions_manifest")) as? ToolResult.Err
            ?: fail("目录不存在必须 NOT_FOUND")
        assertEquals(ToolResult.ErrCode.NOT_FOUND, err.code)
        assertTrue("诊断答案" in err.message, "不存在 = 诊断答案语义：${err.message}")
    }

    // ── 审计 ──

    @Test
    fun `每次执行落审计：成功记会话门控放行、拒绝记围栏拒绝`() = runBlocking {
        val auditLog = SysAuditLog(auditDir)
        File(File(File(home.home, ".config"), "fcitx5").apply { mkdirs() }, "profile").writeText("DefaultIM=rime\n")

        val ok = tool(approveGateContext(), auditLog)
            .executeTyped(args(family = "fcitx5_config", relative_path = "profile"))
        assertTrue(ok is ToolResult.Ok)

        val denied = tool(ToolExecutionContext(), auditLog)
            .executeTyped(args(family = "fcitx5_config", relative_path = "profile"))
        assertTrue(denied is ToolResult.Err)

        val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
        assertTrue(file.isFile, "审计必须落盘：${file.path}")
        val records = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(2, records.size, "成功与拒绝各一行")
        fun field(i: Int, name: String) = records[i][name]!!.jsonPrimitive.content
        assertEquals("sys.read_config", field(0, "tool"))
        assertEquals("read fcitx5_config:profile", field(0, "action"), "动作面 = 族:相对路径")
        assertEquals(SysAuditLog.VERDICT_SESSION_GATED, field(0, "verdict"))
        assertTrue("DefaultIM=rime" in field(0, "outputSummary"), "摘要含脱敏后回执前段")
        assertEquals("sys.read_config", field(1, "tool"))
        assertEquals(SysAuditLog.VERDICT_FENCE_DENIED, field(1, "verdict"), "围栏拒绝入审计（含被拒）")
        records.forEach { record ->
            val summary = record["outputSummary"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(summary.length <= SysAuditLog.SUMMARY_MAX_CHARS, "输出摘要恒 ≤ 200 字符（全文不落）")
        }
    }

    // ── 工具与上下文装配 ──

    private fun args(family: String, relative_path: String? = null): SysReadConfigTools.SysReadConfigTool.Args =
        SysReadConfigTools.SysReadConfigTool.Args(family = family, relative_path = relative_path)

    private fun tool(
        context: ToolExecutionContext,
        audit: SysAuditLog = SysAuditLog.NOOP,
        sysGate: SysSessionGate? = FakeSysGate(),
    ): SysReadConfigTools.SysReadConfigTool =
        SysReadConfigTools.SysReadConfigTool(context = context, audit = audit, sysGate = sysGate)

    /** 恒批准 HITL 门（围栏批准缝的测试形态；生产由装配面供门） */
    private fun approveGateContext(): ToolExecutionContext = ToolExecutionContext(escapeGate = { true })
}
