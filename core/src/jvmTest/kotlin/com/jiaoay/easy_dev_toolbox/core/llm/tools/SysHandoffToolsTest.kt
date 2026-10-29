package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * sys.handoff 守护：
 * 1. 结构化清单形态：合法输入 → Ok 回执含未经执行声明、转交话术、逐条作用/风险/回退、
 *    executed=false 负载（零执行面——清单交用户本人裁决）；
 * 2. 拒收面：空 purpose/空 steps/步数超上限/空 command/command 超长/空 why/说明字段超长；
 * 3. 上界：满配步数回执 8K capAt 收口（truncated 标记）；
 * 4. 审计：放行记「外委已交用户」、拒收记「参数拒收」，命令全文不进审计动作面；
 * 5. 目录：READ_ONLY、名集在册、工厂门控（sysSessionGate 缺席不注册，fail-closed）。
 */
class SysHandoffToolsTest {

    private fun tool(audit: SysAuditLog = SysAuditLog()): SysHandoffTools.SysHandoffTool =
        SysHandoffTools.SysHandoffTool(audit = audit, trigger = "chatId=oc_test sender=ou_test")

    private fun step(
        command: String = "sudo apt install --reinstall fcitx5",
        why: String = "重装输入法框架主程序（需要 root，我不会代执行）",
        risk: String = "包管理器可能升级连带依赖",
        rollback: String = "sudo apt 卸载回原版本（记下原版本号再操作）",
    ) = SysHandoffTools.HandoffStep(command = command, why = why, risk = risk, rollback = rollback)

    // ── 结构化清单形态 ──

    @Test
    fun `合法清单产出未经执行声明与转交话术`() = runBlocking {
        val result = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(
                purpose = "重装 fcitx5 需要 root，超出我的能力边界",
                steps = listOf(step()),
            ),
        )
        val ok = assertIs<ToolResult.Ok>(result)
        val text = ok.summary
        assertTrue("handoff=外委清单" in text, "回执首行须标形态：$text")
        assertTrue("未经执行" in text, "回执必须明示未经执行：$text")
        assertTrue("需要您本人在终端执行" in text, "回执必须带转交话术：$text")
        assertTrue("我未执行、也不会代为执行" in text, "话术必须含不代执行声明：$text")
        assertTrue("sudo apt install --reinstall fcitx5" in text, "命令原文必须逐字保留（复制粘贴保真）：$text")
        assertTrue("作用：" in text && "风险：" in text && "回退：" in text, "逐条三段（作用/风险/回退）必须渲染：$text")
        assertTrue("未提供" !in text, "已提供 risk/rollback 时不得出现未提供占位：$text")
        val payload = assertIs<ToolPayload.HandoffPayload>(ok.data)
        assertEquals(1, payload.stepCount)
        assertFalse(payload.executed, "executed 恒 false（零执行面契约）")
        assertFalse(payload.truncated)
    }

    @Test
    fun `risk 与 rollback 缺省渲染未提供占位（只读检查类无需回退）`() = runBlocking {
        val result = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(
                purpose = "需要用户亲自查看桌面会话日志",
                steps = listOf(step(command = "loginctl list-sessions", why = "列出当前会话", risk = "", rollback = "")),
            ),
        )
        val ok = assertIs<ToolResult.Ok>(result)
        val text = ok.summary
        assertTrue("未提供——请向用户说明确认后再执行" in text, "空 risk 须渲染占位提示：$text")
        assertTrue("未提供（只读检查类命令通常无需回退）" in text, "空 rollback 须渲染占位提示：$text")
    }

    // ── 拒收面 ──

    @Test
    fun `空 purpose 与空 steps 拒收`() = runBlocking {
        val noPurpose = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(purpose = "  ", steps = listOf(step())),
        )
        val err1 = assertIs<ToolResult.Err>(noPurpose)
        assertEquals(ToolResult.ErrCode.BAD_ARGS, err1.code)

        val noSteps = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(purpose = "root 动作", steps = emptyList()),
        )
        val err2 = assertIs<ToolResult.Err>(noSteps)
        assertEquals(ToolResult.ErrCode.BAD_ARGS, err2.code)
        assertTrue("没有可外委步骤" in err2.message)
    }

    @Test
    fun `步数超上限拒收并引导拆分`() = runBlocking {
        val steps = (1..(SysHandoffTools.MAX_STEPS + 1)).map { step(command = "cmd$it", why = "w$it") }
        val result = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(purpose = "太多步", steps = steps),
        )
        val err = assertIs<ToolResult.Err>(result)
        assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
        assertTrue("拆分" in err.message, "超上限文案须引导拆分：${err.message}")
    }

    @Test
    fun `空 command 空 why 与超长字段拒收且指出步序`() = runBlocking {
        val emptyCommand = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(purpose = "p", steps = listOf(step(command = "  "))),
        )
        val err1 = assertIs<ToolResult.Err>(emptyCommand)
        assertTrue("第 1 步 command 不能为空" in err1.message, "${err1.message}")

        val emptyWhy = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(purpose = "p", steps = listOf(step(why = " "))),
        )
        val err2 = assertIs<ToolResult.Err>(emptyWhy)
        assertTrue("第 1 步 why 不能为空" in err2.message, "${err2.message}")

        val longCommand = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(
                purpose = "p",
                steps = listOf(step(command = "x".repeat(SysHandoffTools.MAX_COMMAND_CHARS + 1))),
            ),
        )
        val err3 = assertIs<ToolResult.Err>(longCommand)
        assertTrue("command 超长" in err3.message, "${err3.message}")

        val longRisk = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(
                purpose = "p",
                steps = listOf(step(risk = "x".repeat(SysHandoffTools.MAX_TEXT_CHARS + 1))),
            ),
        )
        val err4 = assertIs<ToolResult.Err>(longRisk)
        assertTrue("risk/rollback 超长" in err4.message, "${err4.message}")

        // 第 2 步的错也须报对步序
        val secondStepBad = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(
                purpose = "p",
                steps = listOf(step(), step(command = "")),
            ),
        )
        val err5 = assertIs<ToolResult.Err>(secondStepBad)
        assertTrue("第 2 步" in err5.message, "${err5.message}")
    }

    // ── 上界 ──

    @Test
    fun `满配步数回执经 8K 上界收口并标记截断`() = runBlocking {
        val steps = (1..SysHandoffTools.MAX_STEPS).map {
            step(
                command = "cmd-${it}-${"x".repeat(SysHandoffTools.MAX_COMMAND_CHARS - 10)}",
                why = "why-${"y".repeat(SysHandoffTools.MAX_TEXT_CHARS - 10)}",
                risk = "risk-${"r".repeat(SysHandoffTools.MAX_TEXT_CHARS - 10)}",
                rollback = "rb-${"z".repeat(SysHandoffTools.MAX_TEXT_CHARS - 10)}",
            )
        }
        val result = tool().executeTyped(
            SysHandoffTools.SysHandoffTool.Args(purpose = "满配清单", steps = steps),
        )
        val ok = assertIs<ToolResult.Ok>(result)
        assertTrue(ok.summary.length <= SysHandoffTools.MAX_OUTPUT_CHARS, "回执须收口在 8K 内：${ok.summary.length}")
        val payload = assertIs<ToolPayload.HandoffPayload>(ok.data)
        assertTrue(payload.truncated, "满配清单应触发截断标记")
    }

    // ── 审计 ──

    @Test
    fun `审计落盘放行与拒收分记`() = runBlocking {
        val auditDir = Files.createTempDirectory("sys-handoff-audit").toFile()
        try {
            val audit = SysAuditLog(auditDir)
            tool(audit).executeTyped(
                SysHandoffTools.SysHandoffTool.Args(purpose = "root 动作", steps = listOf(step())),
            )
            tool(audit).executeTyped(
                SysHandoffTools.SysHandoffTool.Args(purpose = "p", steps = emptyList()),
            )
            val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
            assertTrue(file.isFile, "审计必须落盘：${file.path}")
            val records = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(2, records.size, "放行与拒收各一行（含被拒）")
            fun field(i: Int, name: String) = records[i][name]!!.jsonPrimitive.content
            assertEquals(SysHandoffTools.HANDOFF_NAME, field(0, "tool"))
            assertEquals(SysAuditLog.VERDICT_HANDOFF, field(0, "verdict"))
            assertEquals("chatId=oc_test sender=ou_test", field(0, "trigger"))
            assertTrue(field(0, "action").startsWith("steps=1:"), "动作面记步数与命令摘要：${field(0, "action")}")
            assertTrue(
                field(0, "action").length <= "steps=1: ".length + 80,
                "审计动作面命令摘要须截断：${field(0, "action")}",
            )
            assertEquals(SysAuditLog.VERDICT_BAD_ARGS, field(1, "verdict"))
            records.forEach { record ->
                val summary = record["outputSummary"]?.jsonPrimitive?.content.orEmpty()
                assertTrue(summary.length <= SysAuditLog.SUMMARY_MAX_CHARS, "输出摘要恒 ≤ 200 字符（全文不落）")
            }
        } finally {
            auditDir.deleteRecursively()
        }
    }

    // ── 目录 ──

    @Test
    fun `目录名在 sys 家族名集且 risk 只读`() {
        assertTrue(SysHandoffTools.HANDOFF_NAME in SysProbeTools.SYS_NAMES, "sys.handoff 须在 sys 家族名集")
        val descriptor = AgentToolCatalog.all.first { it.name == SysHandoffTools.HANDOFF_NAME }
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, descriptor.risk, "零执行面须标 READ_ONLY")
    }

    @Test
    fun `工厂门控 sysSessionGate 缺席不注册`() {
        val descriptor = AgentToolCatalog.all.first { it.name == SysHandoffTools.HANDOFF_NAME }
        val context = ToolExecutionContext(emptyList())
        assertNull(
            descriptor.factory(context, ToolResultSink.NOOP, AgentToolCatalog.ToolContextConfig()),
            "门控面缺席 ⇒ 工厂返 null 不注册（fail-closed）",
        )
        val gate = object : SysSessionGate {
            override suspend fun requireSessionAuthorization(toolName: String) = true
            override val familyEscapeGate = ToolExecutionContext.PathEscapeGate { _ -> false }
            override val trigger: String? = "chatId=oc_test sender=ou_test"
        }
        val registered = descriptor.factory(
            context,
            ToolResultSink.NOOP,
            AgentToolCatalog.ToolContextConfig(sysSessionGate = gate),
        )
        assertIs<SysHandoffTools.SysHandoffTool>(registered, "门控面在位须注册（trigger 归因来源）")
    }

    // ── Args 序列化形态（steps 嵌套结构可由 JSON 解码——LLM 传参面契约） ──

    @Test
    fun `steps 嵌套清单可从 JSON 解码`() {
        val json = Json.decodeFromString(
            SysHandoffTools.SysHandoffTool.Args.serializer(),
            """{"purpose":"root 动作","steps":[{"command":"sudo apt update","why":"刷新索引","risk":"低","rollback":""}]}""",
        )
        assertEquals(1, json.steps.size)
        assertEquals("sudo apt update", json.steps[0].command)
    }
}
