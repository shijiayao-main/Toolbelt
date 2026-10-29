package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.easy_dev_toolbox.core.system.ArgvOutcome
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import java.io.IOException
import java.nio.file.Files
import java.io.File
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
 * sys.service 守护：
 * 1. 目录静态断言：三动作（restart/start/stop）、argv 恒 `systemctl --user <action> <service>`
 *    （--user 恒在——执行件黑名单外的第一层防线）、is-active 只读快照 argv、风险徽标恒
 *    DESTRUCTIVE、回滚配方在位且收口；
 * 2. 白名单：只读服务白名单剔除 gnome-shell（恰七项，桌面会话级单元结构性排除）；
 * 3. 授权分级（L2）：门控缺席/会话授权拒绝/动作卡拒绝三面恒 DENIED（fail-closed）且不触
 *    执行器；会话授权不自动覆盖服务动作；卡面含完整 argv/DESTRUCTIVE 徽标/回滚配方；
 * 4. 拒绝矩阵：白名单外服务、gnome-shell 专属话术、未知动作（status 提示走 sys.probe）；
 * 5. fake 执行器：先 is-active 快照后动作、回滚配方矩阵（stop→start / start→stop /
 *    restart→再 restart，原状态注记）、非零退出码=未生效语义、快照失败降级、超时 FAILED、
 *    命令不可用 NOT_FOUND、8K capAt；
 * 6. 审计：每次执行（含被拒）落审计，成功行含 exitCode 与 rollback，拒绝行分记动作确认拒绝。
 */
class SysServiceToolsTest {

    /** 测试授权面（sessionApproved/actionApproved 可编程；记录动作卡供卡面断言） */
    private class FakeServiceGate(
        private val sessionApproved: Boolean = true,
        private val actionApproved: Boolean = true,
    ) : SysServiceTools.SysServiceGate {
        var sessionAsked = 0
        var actionAsked = 0
        val cards = mutableListOf<SysServiceTools.ServiceActionCard>()

        override suspend fun requireSessionAuthorization(toolName: String): Boolean {
            sessionAsked++
            return sessionApproved
        }

        override suspend fun requireActionApproval(card: SysServiceTools.ServiceActionCard): Boolean {
            actionAsked++
            cards += card
            return actionApproved
        }

        override val familyEscapeGate: ToolExecutionContext.PathEscapeGate =
            ToolExecutionContext.PathEscapeGate { _ -> false }

        override val trigger: String? = "chatId=oc_test sender=ou_test"
    }

    // ── 目录静态断言 ──

    @Test
    fun `首批三动作且 argv 恒 systemctl user 形态`() {
        assertEquals(3, SysServiceTools.ServiceAction.entries.size, "首批三动作（加动作走提案评审并同步本断言）")
        for (action in SysServiceTools.ServiceAction.entries) {
            val argv = action.buildArgv("fcitx5")
            assertEquals(
                listOf("systemctl", "--user", action.id, "fcitx5"),
                argv,
                "${action.name} argv 恒 systemctl --user 形态（无自由参数）",
            )
            assertTrue("--user" in argv, "${action.name} 恒带 --user")
            assertTrue(
                action.riskBadge == "DESTRUCTIVE",
                "${action.name} 风险徽标恒 DESTRUCTIVE（L2 必标）",
            )
            val rollback = action.rollbackOf("fcitx5", "active")
            assertTrue(rollback.isNotBlank(), "${action.name} 回滚配方缺失")
            assertTrue(rollback.length <= SysServiceTools.ROLLBACK_MAX_CHARS, "${action.name} 回滚配方收口")
            assertTrue("systemctl --user" in rollback, "${action.name} 回滚配方同走 --user 通道：$rollback")
        }
        assertEquals(
            listOf("systemctl", "--user", "is-active", "fcitx5"),
            SysServiceTools.ServiceAction.RESTART.preReadArgv("fcitx5"),
            "原状态快照 = is-active 只读命令（恒带 --user）",
        )
    }

    @Test
    fun `服务白名单 = 只读白名单剔除 gnome-shell（恰七项）`() {
        assertEquals(
            SysProbeTools.SERVICE_WHITELIST - "gnome-shell",
            SysServiceTools.SERVICE_WHITELIST,
            "动作白名单与只读白名单同源（剔除会话级单元）",
        )
        assertEquals(7, SysServiceTools.SERVICE_WHITELIST.size, "首批七服务（加服务须先核会话级排除并同步本断言）")
        assertTrue(
            "gnome-shell" !in SysServiceTools.SERVICE_WHITELIST,
            "桌面会话级单元永不入动作目录（重启等同重启整个桌面会话）",
        )
    }

    @Test
    fun `sys service 注册进目录且风险写面、不进命令面板、names 单源含五件`() {
        val descriptor = AgentToolCatalog.all.firstOrNull { it.name == "sys.service" }
            ?: error("sys.service 未注册进 AgentToolCatalog")
        assertEquals(AgentToolCatalog.Risk.DESTRUCTIVE, descriptor.risk, "服务状态写面，必须 DESTRUCTIVE")
        assertNull(descriptor.uiHint, "纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertEquals(descriptor.aliases, emptySet<String>(), "无别名（撞面零容忍）")
        assertEquals("sys.service", AgentToolCatalog.resolve("sys.service"), "目录实名必须可经 resolve 解析")
        assertTrue("sys.service" in SysProbeTools.SYS_NAMES, "sys.* 家族名集单源含五件")
    }

    @Test
    fun `sys service 工厂门控（sysServiceGate 缺席不注册）`() {
        val context = ToolExecutionContext(emptyList<File>())
        val absent = AgentToolCatalog.all.first { it.name == "sys.service" }
            .factory(context, ToolResultSink.NOOP, AgentToolCatalog.ToolContextConfig())
        assertNull(absent, "sysServiceGate 缺席（fail-closed）⇒ 工厂返 null 不注册")
        val present = AgentToolCatalog.all.first { it.name == "sys.service" }
            .factory(
                context,
                ToolResultSink.NOOP,
                AgentToolCatalog.ToolContextConfig(sysServiceGate = FakeServiceGate()),
            )
        assertTrue(present != null && present.name == "sys.service", "sysServiceGate 在位 ⇒ 注册")
    }

    // ── 授权分级（L2：域准入 + 逐动作卡，fail-closed） ──

    private fun fakeTool(
        recorded: MutableList<List<String>> = mutableListOf(),
        gate: SysServiceTools.SysServiceGate? = FakeServiceGate(),
        run: suspend (argv: List<String>) -> ArgvOutcome = { ArgvOutcome(0, "", false, false) },
    ): SysServiceTools.SysServiceTool =
        SysServiceTools.SysServiceTool(
            executor = SysProbeTools.ProbeExecutor { argv ->
                recorded.add(argv)
                run(argv)
            },
            audit = SysAuditLog.NOOP,
            serviceGate = gate,
        )

    @Test
    fun `门控面缺席恒 DENIED 且不触执行器`() = runBlocking {
        val tool = fakeTool(gate = null) { fail("门控面缺席不得触执行器") }
        val err = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "restart"))
            as? ToolResult.Err ?: fail("门控面缺席必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("确认门" in err.message, "回执须指明逐动作确认门缺席（装配缺陷）：${err.message}")
    }

    @Test
    fun `会话授权拒绝恒 DENIED 且不弹动作卡不触执行器`() = runBlocking {
        val gate = FakeServiceGate(sessionApproved = false)
        val tool = fakeTool(gate = gate) { fail("会话未授权不得触执行器") }
        val err = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "restart"))
            as? ToolResult.Err ?: fail("会话授权拒绝必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertEquals(0, gate.actionAsked, "会话未授权不得弹动作卡（域准入前置）")
        assertTrue("禁止编造" in err.message, "回执须钉禁止编造执行结果：${err.message}")
    }

    @Test
    fun `动作卡拒绝恒 DENIED 不触主命令且会话授权不自动覆盖服务动作`() = runBlocking {
        val gate = FakeServiceGate(sessionApproved = true, actionApproved = false)
        val recorded = mutableListOf<List<String>>()
        val tool = fakeTool(recorded, gate = gate) { argv ->
            if ("is-active" in argv) ArgvOutcome(0, "active\n", false, false) else fail("动作卡拒绝不得执行主命令")
        }
        val err = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "restart"))
            as? ToolResult.Err ?: fail("动作卡拒绝必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertEquals(1, gate.sessionAsked, "会话授权被请求（域准入）")
        assertEquals(1, gate.actionAsked, "逐动作确认卡被请求（会话授权不自动覆盖服务动作）")
        assertEquals(1, recorded.size, "只有 is-active 快照触达执行器，主命令不得执行：$recorded")
        assertTrue("未经用户确认" in err.message, "回执须明示未执行：${err.message}")
    }

    @Test
    fun `动作卡面含完整 argv DESTRUCTIVE 徽标原状态与回滚配方`() = runBlocking {
        val gate = FakeServiceGate()
        val tool = fakeTool(gate = gate) { argv ->
            if ("is-active" in argv) ArgvOutcome(0, "active\n", false, false) else ArgvOutcome(0, "", false, false)
        }
        tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "stop"))
        val card = gate.cards.single()
        assertEquals("stop", card.actionKey)
        assertEquals(listOf("systemctl", "--user", "stop", "fcitx5"), card.argv, "卡面完整 argv")
        assertEquals("DESTRUCTIVE", card.riskBadge, "L2 徽标恒 DESTRUCTIVE")
        assertEquals("active", card.preState, "卡面含执行前原状态")
        assertTrue("回滚：systemctl --user start fcitx5" in card.rollback, "stop 的回滚 = start：${card.rollback}")
    }

    // ── 拒绝矩阵 ──

    @Test
    fun `白名单外服务与 gnome-shell 专属话术拒收`() = runBlocking {
        val tool = fakeTool()
        val unknown = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "my-own-unit", action = "restart"))
            as? ToolResult.Err ?: fail("白名单外服务必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, unknown.code)
        assertTrue("白名单" in unknown.message && "fcitx5" in unknown.message, "回执须列允许服务：${unknown.message}")

        val sessionLevel = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "gnome-shell", action = "restart"))
            as? ToolResult.Err ?: fail("gnome-shell 必须拒收")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, sessionLevel.code)
        assertTrue("桌面会话级" in sessionLevel.message, "回执须点名会话级单元出处：${sessionLevel.message}")
        assertTrue("交用户本人" in sessionLevel.message, "回执须给降级路径：${sessionLevel.message}")
    }

    @Test
    fun `未知动作拒收且 status 提示走 sys probe`() = runBlocking {
        val tool = fakeTool()
        for (bad in listOf("status", "mask", "disable", "kill", "")) {
            val err = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = bad))
                as? ToolResult.Err ?: fail("未知动作「$bad」必须 BAD_ARGS")
            assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
            assertTrue("systemd_user_status" in err.message, "status 查询提示走只读探查条目：${err.message}")
        }
    }

    // ── fake 执行器：快照/回执/回滚矩阵 ──

    @Test
    fun `先 is-active 快照后主命令且 argv 组装正确`() = runBlocking {
        val recorded = mutableListOf<List<String>>()
        val tool = fakeTool(recorded) { argv ->
            when {
                "is-active" in argv -> ArgvOutcome(0, "inactive\n", false, false)
                else -> ArgvOutcome(0, "", false, false)
            }
        }
        val ok = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "pipewire", action = "start"))
            as? ToolResult.Ok ?: fail("正常执行须 Ok 回执")
        assertEquals(
            listOf(
                listOf("systemctl", "--user", "is-active", "pipewire"),
                listOf("systemctl", "--user", "start", "pipewire"),
            ),
            recorded,
            "先 is-active 快照后主命令",
        )
        val payload = ok.data as? ToolPayload.ServicePayload ?: fail("结构化负载须为 ServicePayload")
        assertEquals("start", payload.action)
        assertEquals("pipewire", payload.service)
        assertEquals(0, payload.exitCode)
        assertTrue("sys.probe" in ok.summary, "回执含验证提示：${ok.summary}")
    }

    @Test
    fun `回滚配方矩阵 stop 走 start start 走 stop restart 幂等`() = runBlocking {
        // stop + 原状态 active → 回滚 = start（恢复运行）
        val stopGate = FakeServiceGate()
        fakeTool(gate = stopGate) { argv ->
            if ("is-active" in argv) ArgvOutcome(0, "active\n", false, false) else ArgvOutcome(0, "", false, false)
        }.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "stop"))
        assertTrue("回滚：systemctl --user start fcitx5" in stopGate.cards.single().rollback)

        // stop + 原状态 inactive → 本动作无实际影响，回滚无需操作
        val stopInactiveGate = FakeServiceGate()
        fakeTool(gate = stopInactiveGate) { argv ->
            if ("is-active" in argv) ArgvOutcome(3, "inactive\n", false, false) else ArgvOutcome(0, "", false, false)
        }.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "stop"))
        assertTrue("无需操作" in stopInactiveGate.cards.single().rollback, "原已停止的 stop 回滚：${stopInactiveGate.cards.single().rollback}")

        // start + 原状态 inactive → 回滚 = stop
        val startGate = FakeServiceGate()
        fakeTool(gate = startGate) { argv ->
            if ("is-active" in argv) ArgvOutcome(3, "inactive\n", false, false) else ArgvOutcome(0, "", false, false)
        }.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "start"))
        assertTrue("回滚：systemctl --user stop fcitx5" in startGate.cards.single().rollback)

        // restart → 回滚 = 再 restart（幂等）
        val restartGate = FakeServiceGate()
        fakeTool(gate = restartGate) { argv ->
            if ("is-active" in argv) ArgvOutcome(0, "active\n", false, false) else ArgvOutcome(0, "", false, false)
        }.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "restart"))
        assertTrue(
            "回滚：systemctl --user restart fcitx5" in restartGate.cards.single().rollback &&
                "幂等" in restartGate.cards.single().rollback,
            "restart 回滚 = 再 restart：${restartGate.cards.single().rollback}",
        )
    }

    @Test
    fun `is-active 非零退出码是状态数据 快照失败降级不拒执`() = runBlocking {
        // is-active 退出码 3（inactive）= 有效状态数据，进卡面与回滚注记
        val gate = FakeServiceGate()
        val tool = fakeTool(gate = gate) { argv ->
            if ("is-active" in argv) ArgvOutcome(3, "inactive\n", false, false) else ArgvOutcome(0, "", false, false)
        }
        tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "start"))
        assertEquals("inactive", gate.cards.single().preState, "非零退出码的状态输出仍是快照数据")

        // 快照命令不可用 → 未捕获降级，主命令照常走卡执行
        val degradedGate = FakeServiceGate()
        val degraded = fakeTool(gate = degradedGate) { argv ->
            if ("is-active" in argv) throw IOException("no systemctl") else ArgvOutcome(0, "", false, false)
        }
        val ok = degraded.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "restart"))
            as? ToolResult.Ok ?: fail("快照失败仍须执行主命令（服务动作回滚不依赖捕获值）")
        assertTrue("未捕获" in ok.summary, "快照未捕获时回滚配方按「未捕获」降级：${ok.summary}")
    }

    @Test
    fun `写动作非零退出码按未生效语义回执且提示验证`() = runBlocking {
        val tool = fakeTool { argv ->
            when {
                "is-active" in argv -> ArgvOutcome(0, "active\n", false, false)
                else -> ArgvOutcome(1, "Job failed.\n", false, false)
            }
        }
        val ok = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "restart"))
            as? ToolResult.Ok ?: fail("非零退出码仍是 Ok 回执（退出码是数据）")
        assertTrue("未生效" in ok.summary, "服务动作非零语义与只读探查不同，回执须明示：${ok.summary}")
        assertTrue("exit=1" in ok.summary, "回执须含退出码：${ok.summary}")
        assertTrue("Job failed." in ok.summary, "服务输出保持明文回灌（已脱敏）：${ok.summary}")
        assertEquals(1, (ok.data as? ToolPayload.ServicePayload)?.exitCode)
    }

    @Test
    fun `超时按 FAILED 回执且提示核实实际状态`() = runBlocking {
        val tool = fakeTool { argv ->
            if ("is-active" in argv) ArgvOutcome(0, "active\n", false, false) else ArgvOutcome(-1, "", false, timedOut = true)
        }
        val err = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "restart"))
            as? ToolResult.Err ?: fail("超时必须 FAILED")
        assertEquals(ToolResult.ErrCode.FAILED, err.code)
        assertTrue("超时" in err.message && "核实实际状态" in err.message, "服务动作超时=结果未知，须提示核实：${err.message}")
    }

    @Test
    fun `命令不可用按 NOT_FOUND 回执且禁止编造`() = runBlocking {
        val tool = fakeTool { argv ->
            if ("is-active" in argv) ArgvOutcome(0, "active\n", false, false) else throw IOException("Cannot run program systemctl")
        }
        val err = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "stop"))
            as? ToolResult.Err ?: fail("启动失败必须 NOT_FOUND")
        assertEquals(ToolResult.ErrCode.NOT_FOUND, err.code)
        assertTrue(err.hint.orEmpty().contains("禁止编造"), "提示须含禁止编造执行结果：${err.hint}")
    }

    @Test
    fun `超长输出恒 8K capAt 收口且负载标记截断`() = runBlocking {
        val huge = "y".repeat(50_000)
        val tool = fakeTool { argv ->
            if ("is-active" in argv) ArgvOutcome(0, "active\n", false, false) else ArgvOutcome(0, huge, false, false)
        }
        val ok = tool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "restart"))
            as? ToolResult.Ok ?: fail("超长输出仍须 Ok 回执")
        assertTrue(ok.summary.length <= 8192, "回执总长恒 ≤ 8K（实际 ${ok.summary.length}）")
        assertTrue("截断" in ok.summary, "超上界须带截断标记：${ok.summary.takeLast(80)}")
        assertTrue((ok.data as? ToolPayload.ServicePayload)?.truncated == true, "负载须标记截断")
    }

    // ── 审计 ──

    @Test
    fun `审计落盘含 exitCode 与 rollback 且拒绝面分记动作确认拒绝`() = runBlocking {
        val auditDir = Files.createTempDirectory("sys-service-audit").toFile()
        try {
            val runGate = FakeServiceGate()
            val runTool = SysServiceTools.SysServiceTool(
                executor = SysProbeTools.ProbeExecutor { argv ->
                    if ("is-active" in argv) ArgvOutcome(0, "active\n", false, false) else ArgvOutcome(0, "", false, false)
                },
                audit = SysAuditLog(auditDir),
                serviceGate = runGate,
            )
            runTool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "restart"))

            val deniedGate = FakeServiceGate(actionApproved = false)
            val deniedTool = SysServiceTools.SysServiceTool(
                executor = SysProbeTools.ProbeExecutor { argv ->
                    if ("is-active" in argv) ArgvOutcome(0, "active\n", false, false) else fail("动作卡拒绝不得执行主命令")
                },
                audit = SysAuditLog(auditDir),
                serviceGate = deniedGate,
            )
            deniedTool.executeTyped(SysServiceTools.SysServiceTool.Args(service = "fcitx5", action = "restart"))

            val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
            assertTrue(file.isFile, "审计必须落盘：${file.path}")
            val records = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(2, records.size, "成功与动作卡拒绝各一行（含被拒）")
            fun field(i: Int, name: String) = records[i][name]!!.jsonPrimitive.content
            assertEquals("sys.service", field(0, "tool"))
            assertEquals("systemctl --user restart fcitx5", field(0, "action"), "argv 全文入审计")
            assertEquals(SysAuditLog.VERDICT_SESSION_GATED, field(0, "verdict"))
            assertEquals("0", records[0]["exitCode"]?.jsonPrimitive?.content)
            assertTrue(
                (records[0]["rollback"]?.jsonPrimitive?.content ?: "").contains("restart fcitx5"),
                "成功行 rollback 字段须含回滚配方：${records[0]["rollback"]}",
            )
            assertEquals(SysAuditLog.VERDICT_ACTION_DENIED, field(1, "verdict"), "动作卡拒绝须分记动作确认拒绝")
            assertEquals("systemctl --user restart fcitx5", field(1, "action"), "拒绝行也记完整 argv")
            records.forEach { record ->
                val summary = record["outputSummary"]?.jsonPrimitive?.content.orEmpty()
                assertTrue(summary.length <= SysAuditLog.SUMMARY_MAX_CHARS, "输出摘要恒 ≤ 200 字符（全文不落）")
            }
        } finally {
            auditDir.deleteRecursively()
        }
    }
}
