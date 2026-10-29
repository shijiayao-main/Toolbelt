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
 * sys.write_config 守护（临时 user.home 真读写）：
 * 1. 注册面：目录注册 + Risk=DESTRUCTIVE + uiHint=null + resolve 可解析 + 上界字面值断言 +
 *    写族唯一（fcitx5_config）；
 * 2. 授权分级（L1a）：门控缺席/会话授权拒绝/写卡拒绝三面恒 DENIED（fail-closed）且不写文件；
 *    会话授权不自动覆盖写动作；写卡面含完整 diff/快照/回滚配方；
 * 3. 写族纪律：xprofile（持久化面）/gnome manifest/未知族拒收；相对路径形态校验；
 *    父目录缺失拒收（不代建目录）；
 * 4. 围栏双闸：无门/门拒绝 → DENIED；族内符号链接穿越拒收（canonical 包含性 fail-safe）；
 * 5. 快照-diff 回写：写前 .bak 落盘且内容=旧内容、写入后目标=新内容；新建文件无快照；
 *    内容无变化不弹卡不写；diff 恒过脱敏（token 遮蔽）；
 * 6. 可完整展示性：diff 超卡面预算 / 超行数上界整体拒执（防注入载荷藏尾）；
 * 7. 审计：每次执行（含被拒）落审计记录，成功行 rollback 含 .bak 路径，拒绝行分记动作确认拒绝。
 */
class SysWriteToolsTest {

    private val home = UserHomeIsolation("sys-write-config-test")
    private val auditDir: File = Files.createTempDirectory("sys-write-config-audit").toFile()

    /** 测试写门控面（sessionApproved/actionApproved 可编程；记录写卡供卡面断言） */
    private class FakeWriteGate(
        private val sessionApproved: Boolean = true,
        private val actionApproved: Boolean = true,
    ) : SysWriteTools.SysWriteGate {
        var sessionAsked = 0
        var actionAsked = 0
        val cards = mutableListOf<SysWriteTools.WriteActionCard>()

        override suspend fun requireSessionAuthorization(toolName: String): Boolean {
            sessionAsked++
            return sessionApproved
        }

        override suspend fun requireActionApproval(card: SysWriteTools.WriteActionCard): Boolean {
            actionAsked++
            cards += card
            return actionApproved
        }

        override val familyEscapeGate: ToolExecutionContext.PathEscapeGate =
            ToolExecutionContext.PathEscapeGate { _ -> true }

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
    fun `sys write config 注册且风险写面、不进命令面板、写族唯一`() {
        val descriptor = AgentToolCatalog.all.firstOrNull { it.name == "sys.write_config" }
            ?: error("sys.write_config 未注册进 AgentToolCatalog")
        assertEquals(AgentToolCatalog.Risk.DESTRUCTIVE, descriptor.risk, "写盘面，必须 DESTRUCTIVE")
        assertNull(descriptor.uiHint, "纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertEquals(descriptor.aliases, emptySet<String>(), "无别名（撞面零容忍）")
        assertEquals("sys.write_config", AgentToolCatalog.resolve("sys.write_config"), "目录实名必须可经 resolve 解析")
        assertEquals(
            listOf(SysWriteTools.WriteFamily.FCITX5_CONFIG),
            SysWriteTools.WriteFamily.entries.toList(),
            "写族唯一 = fcitx5_config（登录启动脚本类文件永不入写族；加族走提案评审并同步本断言）",
        )
        assertEquals(
            File(home.home, ".config/fcitx5").path,
            SysWriteTools.writeFamilyRoot(home.home).path,
            "写族根与 sys.read_config 的 fcitx5 族同根",
        )
        assertEquals(262144, SysWriteTools.MAX_FILE_BYTES, "单文件上界字面值独立断言（与 read_config 同款 256KB）")
        assertEquals(4096, SysWriteTools.DIFF_MAX_CHARS, "diff 卡面展示预算字面值独立断言（超预算拒执不截断）")
        assertEquals(1000, SysWriteTools.MAX_DIFF_LINES, "diff 行数上界字面值独立断言")
    }

    // ── 授权分级（L1a：域准入 + 逐写卡，fail-closed） ──

    private fun fakeTool(gate: SysWriteTools.SysWriteGate? = FakeWriteGate()): SysWriteTools.SysWriteConfigTool =
        SysWriteTools.SysWriteConfigTool(
            context = ToolExecutionContext(escapeGate = { true }),
            audit = SysAuditLog.NOOP,
            writeGate = gate,
        )

    @Test
    fun `门控面缺席恒 DENIED 且不写文件`() = runBlocking {
        val tool = SysWriteTools.SysWriteConfigTool(
            context = ToolExecutionContext(escapeGate = { true }),
            audit = SysAuditLog.NOOP,
            writeGate = null,
        )
        val err = tool.executeTyped(args("config", "DefaultIM=fcitx\n")) as? ToolResult.Err
            ?: fail("门控面缺席必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("确认门" in err.message, "回执须指明逐写确认门缺席（装配缺陷）：${err.message}")
    }

    @Test
    fun `会话授权拒绝恒 DENIED 且不弹写卡不写文件`() = runBlocking {
        val gate = FakeWriteGate(sessionApproved = false)
        val tool = fakeTool(gate)
        val err = tool.executeTyped(args("config", "DefaultIM=fcitx\n")) as? ToolResult.Err
            ?: fail("会话授权拒绝必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertEquals(0, gate.actionAsked, "会话未授权不得弹写卡（域准入前置）")
        assertTrue("禁止编造" in err.message, "回执须钉禁止编造写入结果：${err.message}")
    }

    @Test
    fun `写卡拒绝恒 DENIED 不写文件且会话授权不自动覆盖写动作`() = runBlocking {
        val fcitx5 = File(home.home, ".config/fcitx5").apply { mkdirs() }
        val config = File(fcitx5, "config").apply { writeText("old=1\n") }
        val gate = FakeWriteGate(sessionApproved = true, actionApproved = false)
        val tool = fakeTool(gate)
        val err = tool.executeTyped(args("config", "new=2\n")) as? ToolResult.Err
            ?: fail("写卡拒绝必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertEquals(1, gate.sessionAsked, "会话授权被请求（域准入）")
        assertEquals(1, gate.actionAsked, "逐写确认卡被请求（会话授权不自动覆盖写动作）")
        assertEquals("old=1\n", config.readText(), "写卡拒绝 = 目标文件原样（未写入）")
        assertTrue("未经用户确认" in err.message, "回执须明示未写入：${err.message}")
    }

    @Test
    fun `写卡面含完整脱敏 diff 快照路径与回滚配方`() = runBlocking {
        val fcitx5 = File(home.home, ".config/fcitx5").apply { mkdirs() }
        File(fcitx5, "config").writeText("DefaultIM=rime\nMY_TOKEN=secret\n")
        val gate = FakeWriteGate()
        val tool = fakeTool(gate)
        tool.executeTyped(args("config", "DefaultIM=fcitx\nMY_TOKEN=rotated\n"))
        val card = gate.cards.single()
        assertEquals("fcitx5_config", card.family)
        assertTrue(card.targetPath.endsWith(".config/fcitx5/config"), "卡面目标路径：${card.targetPath}")
        assertTrue("- DefaultIM=rime" in card.diff, "卡面 diff 含移除行：${card.diff}")
        assertTrue("+ DefaultIM=fcitx" in card.diff, "卡面 diff 含新增行：${card.diff}")
        assertTrue("+ MY_TOKEN=***" in card.diff, "卡面 diff 恒过脱敏：${card.diff}")
        assertTrue("secret" !in card.diff && "rotated" !in card.diff, "脱敏后不得残留原值")
        assertTrue(card.backupPath != null && File(card.backupPath!!).isFile, "卡面快照路径对应真实落盘的 .bak")
        assertTrue("cp " in card.rollback && card.backupPath!! in card.rollback, "卡面回滚配方含 cp 恢复快照：${card.rollback}")
    }

    // ── 写族纪律与相对路径 ──

    @Test
    fun `登录启动脚本与只读清单族拒收且话术点名持久化面`() = runBlocking {
        val tool = fakeTool()
        for (bad in listOf("xprofile", "gnome_extensions_manifest", "unknown_family")) {
            val err = tool.executeTyped(args("config", "x", family = bad)) as? ToolResult.Err
                ?: fail("不可写族「$bad」必须 BAD_ARGS")
            assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
            assertTrue("fcitx5_config" in err.message, "回执须列唯一写族：${err.message}")
        }
        val xprofile = tool.executeTyped(args("config", "x", family = "xprofile")) as? ToolResult.Err
            ?: fail("xprofile 拒收")
        assertTrue("持久化面" in xprofile.message, "拒收文案须点名登录启动脚本持久化面：${xprofile.message}")
    }

    @Test
    fun `相对路径形态校验与父目录缺失拒收`() = runBlocking {
        val tool = fakeTool()
        for (bad in listOf("/etc/passwd", "~/.ssh/id_rsa", "../outside.txt", "a//b", "conf/")) {
            val err = tool.executeTyped(args(bad, "x")) as? ToolResult.Err
                ?: fail("形态外相对路径「$bad」必须 BAD_ARGS")
            assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code, "形态外拒收：$bad")
        }
        val missingParent = tool.executeTyped(args("conf/not-exist/new.conf", "x")) as? ToolResult.Err
            ?: fail("父目录缺失必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, missingParent.code)
        assertTrue("父目录不存在" in missingParent.message, "回执须明示不代建目录：${missingParent.message}")
    }

    // ── 围栏双闸 ──

    @Test
    fun `无门且未命中允许集即 DENIED`() = runBlocking {
        val tool = SysWriteTools.SysWriteConfigTool(
            context = ToolExecutionContext(),
            audit = SysAuditLog.NOOP,
            writeGate = FakeWriteGate(),
        )
        val err = tool.executeTyped(args("config", "x")) as? ToolResult.Err ?: fail("围栏未放行必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("围栏" in err.message, "回执须人话说明围栏语义：${err.message}")
    }

    @Test
    fun `族内符号链接穿越拒收（canonical 包含性 fail-safe）`() = runBlocking {
        val outside = File(home.home, "outside.txt").apply { writeText("old") }
        val fcitx5 = File(home.home, ".config/fcitx5").apply { mkdirs() }
        val linked = runCatching {
            java.nio.file.Files.createSymbolicLink(File(fcitx5, "linked").toPath(), outside.toPath())
        }.isSuccess
        if (!linked) return@runBlocking // 平台无软链权限——跳过，不误报
        val tool = fakeTool()
        val err = tool.executeTyped(args("linked", "new")) as? ToolResult.Err ?: fail("符号链接穿越必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("族根之外" in err.message, "回执须说明穿越拒收：${err.message}")
        assertEquals("old", outside.readText(), "族外文件不被写入")
    }

    // ── 快照-diff 回写（临时目录真读写） ──

    @Test
    fun `写前 bak 快照落盘且内容为旧内容 写入后目标为新内容`() = runBlocking {
        val fcitx5 = File(home.home, ".config/fcitx5").apply { mkdirs() }
        val config = File(fcitx5, "config").apply { writeText("DefaultIM=rime\n") }
        val gate = FakeWriteGate()
        val tool = fakeTool(gate)
        val ok = tool.executeTyped(args("config", "DefaultIM=fcitx\n")) as? ToolResult.Ok
            ?: fail("正常写入须 Ok 回执")
        assertEquals("DefaultIM=fcitx\n", config.readText(), "写入后目标内容 = 新内容")
        val backupPath = (ok.data as? ToolPayload.WriteConfigPayload)?.backupPath
            ?: fail("负载须含 .bak 快照路径")
        val backup = File(backupPath)
        assertTrue(backup.isFile && backup.parentFile == fcitx5, "快照落同目录：${backup.path}")
        assertTrue(backup.name.startsWith("config.bak-"), "快照名带时间戳后缀：${backup.name}")
        assertEquals("DefaultIM=rime\n", backup.readText(), "快照内容 = 写前旧内容")
        assertTrue("changed=+1/-1" in ok.summary, "回执含 diff 统计：${ok.summary}")
        assertTrue("sys.read_config" in ok.summary, "回执含验证提示：${ok.summary}")
    }

    @Test
    fun `新建文件无快照且回滚配方含删除指引`() = runBlocking {
        val fcitx5 = File(home.home, ".config/fcitx5/conf").apply { mkdirs() }
        val target = File(fcitx5, "new.conf")
        val gate = FakeWriteGate()
        val tool = fakeTool(gate)
        val ok = tool.executeTyped(args("conf/new.conf", "section=1\n")) as? ToolResult.Ok
            ?: fail("新建写入须 Ok 回执")
        assertEquals("section=1\n", target.readText(), "新文件写入成功")
        assertNull((ok.data as? ToolPayload.WriteConfigPayload)?.backupPath, "新建文件无写前快照")
        val card = gate.cards.single()
        assertNull(card.backupPath, "卡面明示无快照（新建文件）")
        assertTrue("删除" in card.rollback, "回滚配方含删除指引：${card.rollback}")
        assertTrue("+ section=1" in card.diff, "新建文件 diff = 全量新增行：${card.diff}")
    }

    @Test
    fun `内容无变化不弹卡不写不落快照`() = runBlocking {
        val fcitx5 = File(home.home, ".config/fcitx5").apply { mkdirs() }
        val config = File(fcitx5, "profile").apply { writeText("DefaultIM=rime\n") }
        val gate = FakeWriteGate()
        val tool = fakeTool(gate)
        val ok = tool.executeTyped(args("profile", "DefaultIM=rime\n")) as? ToolResult.Ok
            ?: fail("无变化仍须 Ok 回执")
        assertEquals(0, gate.actionAsked, "内容无变化不弹确认卡")
        assertEquals("DefaultIM=rime\n", config.readText())
        assertTrue("内容无变化，未写入" in ok.summary, "回执明示未写入：${ok.summary}")
        assertTrue("未写入" in ok.summary)
        assertTrue(
            fcitx5.listFiles { f -> f.name.startsWith("profile.bak-") }.isNullOrEmpty(),
            "无变化不落 .bak 快照",
        )
        assertTrue((ok.data as? ToolPayload.WriteConfigPayload)?.changed == false, "负载标记 changed=false")
    }

    @Test
    fun `diff 恒过脱敏（凭据形态遮蔽后才进卡与回执）`() = runBlocking {
        val fcitx5 = File(home.home, ".config/fcitx5").apply { mkdirs() }
        File(fcitx5, "config").writeText("a=1\n")
        val tool = fakeTool()
        val ok = tool.executeTyped(args("config", "api_key=leak-me\n")) as? ToolResult.Ok
            ?: fail("写入须 Ok")
        assertTrue("api_key=***" in ok.summary, "回执 diff 凭据键遮蔽：${ok.summary}")
        assertTrue("leak-me" !in ok.summary, "遮蔽后不得残留原值")
    }

    @Test
    fun `目标是目录拒写`() = runBlocking {
        File(home.home, ".config/fcitx5/conf").apply { mkdirs() }
        val tool = fakeTool()
        val err = tool.executeTyped(args("conf", "x")) as? ToolResult.Err ?: fail("目录目标必须 FAILED")
        assertEquals(ToolResult.ErrCode.FAILED, err.code)
        assertTrue("目录" in err.message, "回执须明示目标是目录：${err.message}")
    }

    // ── 可完整展示性（防注入载荷藏尾） ──

    @Test
    fun `diff 超卡面展示预算整体拒执不弹卡不写`() = runBlocking {
        val fcitx5 = File(home.home, ".config/fcitx5").apply { mkdirs() }
        val config = File(fcitx5, "config").apply { writeText("a=1\n") }
        val gate = FakeWriteGate()
        val tool = fakeTool(gate)
        val big = buildString { repeat(200) { i -> appendLine("line$i=${"x".repeat(40)}") } }
        val err = tool.executeTyped(args("config", big)) as? ToolResult.Err
            ?: fail("diff 超预算必须 FAILED")
        assertEquals(ToolResult.ErrCode.FAILED, err.code)
        assertEquals(0, gate.actionAsked, "不可完整展示的写入不弹确认卡")
        assertEquals("a=1\n", config.readText(), "拒执 = 目标原样")
        assertTrue("不可完整展示" in err.message || "展示预算" in err.message, "拒执文案须点名藏尾防线：${err.message}")
    }

    @Test
    fun `超行数上界整体拒执`() = runBlocking {
        File(home.home, ".config/fcitx5").apply { mkdirs() }
        val tool = fakeTool()
        val manyLines = (1..(SysWriteTools.MAX_DIFF_LINES + 1)).joinToString("\n") { "l$it" }
        val err = tool.executeTyped(args("config", manyLines)) as? ToolResult.Err
            ?: fail("超行数上界必须 FAILED")
        assertEquals(ToolResult.ErrCode.FAILED, err.code)
        assertTrue("行数上界" in err.message, "拒执文案须点名行数上界：${err.message}")
    }

    // ── 审计 ──

    @Test
    fun `每次执行落审计 成功行含 bak 路径 拒绝行分记动作确认拒绝`() = runBlocking {
        val fcitx5 = File(home.home, ".config/fcitx5").apply { mkdirs() }
        File(fcitx5, "config").writeText("a=1\n")
        val auditLog = SysAuditLog(auditDir)

        val runTool = SysWriteTools.SysWriteConfigTool(
            context = ToolExecutionContext(escapeGate = { true }),
            audit = auditLog,
            writeGate = FakeWriteGate(),
        )
        runTool.executeTyped(args("config", "a=2\n"))

        val deniedTool = SysWriteTools.SysWriteConfigTool(
            context = ToolExecutionContext(escapeGate = { true }),
            audit = auditLog,
            writeGate = FakeWriteGate(actionApproved = false),
        )
        deniedTool.executeTyped(args("config", "a=3\n"))

        val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
        assertTrue(file.isFile, "审计必须落盘：${file.path}")
        val records = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(2, records.size, "成功与写卡拒绝各一行（含被拒）")
        fun field(i: Int, name: String) = records[i][name]!!.jsonPrimitive.content
        assertEquals("sys.write_config", field(0, "tool"))
        assertEquals("write fcitx5_config:config", field(0, "action"), "动作面 = write 族:相对路径")
        assertEquals(SysAuditLog.VERDICT_SESSION_GATED, field(0, "verdict"))
        val rollback = records[0]["rollback"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(".bak-" in rollback, "成功行 rollback 字段含 .bak 快照路径：$rollback")
        assertTrue("cp " in rollback, "rollback 含恢复配方：$rollback")
        assertEquals(SysAuditLog.VERDICT_ACTION_DENIED, field(1, "verdict"), "写卡拒绝须分记动作确认拒绝")
        assertEquals("write fcitx5_config:config", field(1, "action"), "拒绝行也记完整动作面")
        records.forEach { record ->
            val summary = record["outputSummary"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(summary.length <= SysAuditLog.SUMMARY_MAX_CHARS, "输出摘要恒 ≤ 200 字符（全文不落）")
        }
    }

    @Test
    fun `bak 快照命名碰撞安全（同毫秒连写不重名）`() {
        val fcitx5 = File(home.home, ".config/fcitx5").apply { mkdirs() }
        val target = File(fcitx5, "config")
        val sameNow = java.time.LocalDateTime.of(2020, 1, 1, 0, 0, 0, 0)
        val first = SysWriteTools.collisionSafeBackup(target, sameNow)
        first.writeText("old")
        val second = SysWriteTools.collisionSafeBackup(target, sameNow)
        assertTrue(first != second, "同毫秒第二次取点不得与已占用快照重名：${first.name} vs ${second.name}")
        assertTrue(second.name.startsWith("config.bak-") && second.name.contains("-1"), "碰撞追加递增序号：${second.name}")
        first.delete()
        second.delete()
    }

    // ── 工具与上下文装配 ──

    private fun args(relative: String, content: String, family: String = "fcitx5_config") =
        SysWriteTools.SysWriteConfigTool.Args(family = family, relative_path = relative, content = content)
}
