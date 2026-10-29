package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.easy_dev_toolbox.core.system.ArgvOutcome
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * sys.verify 守护：
 * 1. 纯函数快照矩阵（三态）：四条修复动作 × 生效/未生效/无法判定——前后快照 diff 判定
 *    （before 缺失降级「此前状态未提供」、幂等重申形态、GVariant 空白归一、State 行大小写）；
 * 2. 探查映射：verifyProbeArgv / requiredSlots 单源（LLM 传修复动作 key，探查由映射推导）；
 * 3. 工具流程：会话门控缺席/拒绝恒 DENIED（fail-closed）且不触执行器；槽纪律多传/缺传/
 *    非法值拒收；未生效自动复跑一次（attempts=2），仍失败降级外委（回执含 handoff 要素）；
 * 4. 执行面异常翻译：策略拒绝 DENIED / 命令不可用 NOT_FOUND / 超时 INDETERMINATE；
 * 5. 审计落盘（tool=sys.verify）与目录注册（READ_ONLY、工厂门控、names 单源）。
 */
class SysVerifyToolsTest {

    /** 测试会话门控（可编程；记录触发工具名） */
    private class FakeGate(private val approved: Boolean = true) : SysSessionGate {
        val asked = mutableListOf<String>()
        override suspend fun requireSessionAuthorization(toolName: String): Boolean {
            asked += toolName
            return approved
        }
        override val familyEscapeGate = ToolExecutionContext.PathEscapeGate { _ -> false }
        override val trigger: String? = "chatId=oc_test sender=ou_test"
    }

    /** 可编程序列执行器：按调用序弹出回执并记录 argv（队列耗尽即红——复跑次数超预期） */
    private class FakeExecutor(private val outcomes: List<ArgvOutcome>) : SysProbeTools.ProbeExecutor {
        val calls = mutableListOf<List<String>>()
        private var index = 0
        override suspend fun run(argv: List<String>): ArgvOutcome {
            calls += argv
            if (index >= outcomes.size) fail("执行器被调用 ${index + 1} 次，超出预期序列 ${outcomes.size}")
            return outcomes[index++]
        }
    }

    private fun outcome(exitCode: Int = 0, output: String = "", timedOut: Boolean = false) =
        ArgvOutcome(exitCode, output, false, timedOut)

    private fun tool(
        gate: SysSessionGate? = FakeGate(),
        executor: SysProbeTools.ProbeExecutor = FakeExecutor(listOf(outcome(output = ""))),
    ): SysVerifyTools.SysVerifyTool = SysVerifyTools.SysVerifyTool(executor = executor, verifyGate = gate)

    // ── 纯函数快照矩阵（三态） ──

    @Test
    fun `im_config 矩阵-生效-未生效-无法判定`() {
        val key = SysRepairTools.RepairKey.IM_CONFIG_SET_FCITX5
        // 前后 diff 生效：ibus → fcitx5
        val changed = SysVerifyTools.evaluate(key, emptyMap(), "display /bin/true\nibus\ndefault", "display /bin/true\nfcitx5\ndefault")
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, changed.verdict)
        assertTrue("由「ibus」变更而来" in changed.detail, "生效细节须记录变更来源：${changed.detail}")
        // before 缺失：状态在位仍可判（此前状态未提供）
        val noBefore = SysVerifyTools.evaluate(key, emptyMap(), null, "display /bin/true\nfcitx5\ndefault")
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, noBefore.verdict)
        assertTrue("此前状态未提供" in noBefore.detail)
        // 幂等重申：前已是 fcitx5
        val idempotent = SysVerifyTools.evaluate(key, emptyMap(), "x\nfcitx5\ny", "x\nfcitx5\ny")
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, idempotent.verdict)
        assertTrue("幂等重申" in idempotent.detail)
        // 未生效：前后一致非目标态
        val notEffective = SysVerifyTools.evaluate(key, emptyMap(), "x\nibus\ny", "x\nibus\ny")
        assertEquals(SysVerifyTools.Verdict.NOT_EFFECTIVE, notEffective.verdict)
        // 无法判定：第 2 行缺失/形态异常
        val unparseable = SysVerifyTools.evaluate(key, emptyMap(), null, "只有一行")
        assertEquals(SysVerifyTools.Verdict.INDETERMINATE, unparseable.verdict)
        val emptyOut = SysVerifyTools.evaluate(key, emptyMap(), null, "")
        assertEquals(SysVerifyTools.Verdict.INDETERMINATE, emptyOut.verdict)
    }

    @Test
    fun `fcitx5 守护矩阵-进程在位与无进程`() {
        val key = SysRepairTools.RepairKey.FCITX5_RESTART_DAEMON
        val effective = SysVerifyTools.evaluate(key, emptyMap(), "", "1234 fcitx5\n")
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, effective.verdict)
        assertTrue("本动作拉起" in effective.detail)
        // 前已在跑 = 幂等重申
        val idempotent = SysVerifyTools.evaluate(key, emptyMap(), "900 fcitx5\n", "1234 fcitx5\n")
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, idempotent.verdict)
        assertTrue("幂等重申" in idempotent.detail)
        // pgrep exit 1 + 空输出 = 确定性未拉起
        val notEffective = SysVerifyTools.evaluate(key, emptyMap(), "", "")
        assertEquals(SysVerifyTools.Verdict.NOT_EFFECTIVE, notEffective.verdict)
        assertTrue("未拉起" in notEffective.detail)
    }

    @Test
    fun `gnome_extension 矩阵-State 行三态与大小写归一`() {
        val key = SysRepairTools.RepairKey.GNOME_EXTENSION_ENABLE
        val slots = mapOf(SysRepairTools.RepairSlot.UUID to "ubuntu-dock@ubuntu.com")
        val effective = SysVerifyTools.evaluate(key, slots, null, "ubuntu-dock@ubuntu.com\n  Name: Ubuntu Dock\n  State: ENABLED\n")
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, effective.verdict)
        // 小写标签形态同判（不同 shell 版本标签大小写有差异）
        val lowerCase = SysVerifyTools.evaluate(key, slots, null, "  state: enabled\n")
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, lowerCase.verdict)
        val notEffective = SysVerifyTools.evaluate(key, slots, null, "ubuntu-dock@ubuntu.com\n  State: DISABLED\n")
        assertEquals(SysVerifyTools.Verdict.NOT_EFFECTIVE, notEffective.verdict)
        assertTrue("DISABLED" in notEffective.detail)
        val indeterminate = SysVerifyTools.evaluate(key, slots, null, "扩展不存在：No extension with id")
        assertEquals(SysVerifyTools.Verdict.INDETERMINATE, indeterminate.verdict)
    }

    @Test
    fun `gsettings 矩阵-键值比对-空白归一与 as 注解剥离`() {
        val key = SysRepairTools.RepairKey.GSETTINGS_SET
        val slots = mapOf(
            SysRepairTools.RepairSlot.SCHEMA_KEY to "org.gnome.shell.enabled-extensions",
            SysRepairTools.RepairSlot.VALUE to "['ubuntu-dock@ubuntu.com', 'ding@rastersoft.com']",
        )
        val effective = SysVerifyTools.evaluate(key, slots, "@as []", "['ubuntu-dock@ubuntu.com', 'ding@rastersoft.com']")
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, effective.verdict)
        // 空白差异归一（GVariant 文本的不同排版同值）
        val whitespace = SysVerifyTools.evaluate(key, slots, null, "['ubuntu-dock@ubuntu.com','ding@rastersoft.com']")
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, whitespace.verdict)
        // 空数组：gsettings get 对空默认值打印 @as [] 注解——与写入值 [] 同值
        val emptyArray = SysVerifyTools.evaluate(
            key,
            mapOf(
                SysRepairTools.RepairSlot.SCHEMA_KEY to "org.gnome.shell.enabled-extensions",
                SysRepairTools.RepairSlot.VALUE to "[]",
            ),
            null,
            "@as []",
        )
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, emptyArray.verdict)
        // 布尔键字面比对
        val boolKey = SysVerifyTools.evaluate(
            key,
            mapOf(
                SysRepairTools.RepairSlot.SCHEMA_KEY to "org.gnome.shell.disable-user-extensions",
                SysRepairTools.RepairSlot.VALUE to "false",
            ),
            null,
            "false",
        )
        assertEquals(SysVerifyTools.Verdict.EFFECTIVE, boolKey.verdict)
        val notEffective = SysVerifyTools.evaluate(key, slots, "@as []", "['other@x.y']")
        assertEquals(SysVerifyTools.Verdict.NOT_EFFECTIVE, notEffective.verdict)
        assertTrue("写入未生效" in notEffective.detail)
        val indeterminate = SysVerifyTools.evaluate(key, slots, null, "  ")
        assertEquals(SysVerifyTools.Verdict.INDETERMINATE, indeterminate.verdict)
    }

    // ── 探查映射（修复动作 → 复跑探查单源） ──

    @Test
    fun `四条修复动作的验证探查 argv 与槽需求映射`() {
        assertEquals(
            listOf("im-config", "-m"),
            SysVerifyTools.verifyProbeArgv(SysRepairTools.RepairKey.IM_CONFIG_SET_FCITX5, emptyMap()),
        )
        assertEquals(
            listOf("pgrep", "-a", "fcitx5"),
            SysVerifyTools.verifyProbeArgv(SysRepairTools.RepairKey.FCITX5_RESTART_DAEMON, emptyMap()),
        )
        assertEquals(
            listOf("gnome-extensions", "info", "ubuntu-dock@ubuntu.com"),
            SysVerifyTools.verifyProbeArgv(
                SysRepairTools.RepairKey.GNOME_EXTENSION_ENABLE,
                mapOf(SysRepairTools.RepairSlot.UUID to "ubuntu-dock@ubuntu.com"),
            ),
        )
        assertEquals(
            listOf("gsettings", "get", "org.gnome.shell", "disable-user-extensions"),
            SysVerifyTools.verifyProbeArgv(
                SysRepairTools.RepairKey.GSETTINGS_SET,
                mapOf(SysRepairTools.RepairSlot.SCHEMA_KEY to "org.gnome.shell.disable-user-extensions"),
            ),
        )
        // 槽需求：uuid 类要 uuid，gsettings 类要 schema_key+value，其余无槽
        assertEquals(emptySet(), SysVerifyTools.requiredSlots.getValue(SysRepairTools.RepairKey.IM_CONFIG_SET_FCITX5))
        assertEquals(emptySet(), SysVerifyTools.requiredSlots.getValue(SysRepairTools.RepairKey.FCITX5_RESTART_DAEMON))
        assertEquals(
            setOf(SysRepairTools.RepairSlot.UUID),
            SysVerifyTools.requiredSlots.getValue(SysRepairTools.RepairKey.GNOME_EXTENSION_ENABLE),
        )
        assertEquals(
            setOf(SysRepairTools.RepairSlot.SCHEMA_KEY, SysRepairTools.RepairSlot.VALUE),
            SysVerifyTools.requiredSlots.getValue(SysRepairTools.RepairKey.GSETTINGS_SET),
        )
    }

    // ── 工具流程 ──

    @Test
    fun `门控缺席与授权拒绝恒 DENIED 且不触执行器`() = runBlocking {
        val untouched = FakeExecutor(emptyList())
        val noGate = tool(gate = null, executor = untouched)
        val err1 = assertIs<ToolResult.Err>(
            noGate.executeTyped(SysVerifyTools.SysVerifyTool.Args(key = "im_config_set_fcitx5")),
        )
        assertEquals(ToolResult.ErrCode.DENIED, err1.code)
        val deniedGate = FakeGate(approved = false)
        val err2 = assertIs<ToolResult.Err>(
            tool(gate = deniedGate, executor = untouched)
                .executeTyped(SysVerifyTools.SysVerifyTool.Args(key = "im_config_set_fcitx5")),
        )
        assertEquals(ToolResult.ErrCode.DENIED, err2.code)
        assertEquals(0, untouched.calls.size, "门控拒绝不得触执行器")
        assertEquals(listOf(SysVerifyTools.VERIFY_NAME), deniedGate.asked, "拒绝前须先过会话授权（审计口径同源）")
    }

    @Test
    fun `未知 key 与槽纪律拒收`() = runBlocking {
        val unknown = assertIs<ToolResult.Err>(
            tool().executeTyped(SysVerifyTools.SysVerifyTool.Args(key = "not_a_repair_key")),
        )
        assertEquals(ToolResult.ErrCode.BAD_ARGS, unknown.code)
        assertTrue("可验证动作" in unknown.message, "拒收文案列可验证动作：${unknown.message}")

        // gnome_extension_enable 验证缺 uuid
        val missingUuid = assertIs<ToolResult.Err>(
            tool().executeTyped(SysVerifyTools.SysVerifyTool.Args(key = "gnome_extension_enable")),
        )
        assertTrue("缺必填参数 uuid" in missingUuid.message, "${missingUuid.message}")

        // im_config 验证多传 uuid（无槽动作不接受参数）
        val unexpected = assertIs<ToolResult.Err>(
            tool().executeTyped(
                SysVerifyTools.SysVerifyTool.Args(key = "im_config_set_fcitx5", uuid = "a@b.c"),
            ),
        )
        assertTrue("不接受参数 uuid" in unexpected.message, "${unexpected.message}")

        // uuid 形态非法
        val badUuid = assertIs<ToolResult.Err>(
            tool().executeTyped(
                SysVerifyTools.SysVerifyTool.Args(key = "gnome_extension_enable", uuid = "空 格 不合法"),
            ),
        )
        assertTrue("形态非法" in badUuid.message, "${badUuid.message}")

        // gsettings 验证缺 value / 键不在白名单
        val missingValue = assertIs<ToolResult.Err>(
            tool().executeTyped(
                SysVerifyTools.SysVerifyTool.Args(
                    key = "gsettings_set",
                    schema_key = "org.gnome.shell.disable-user-extensions",
                ),
            ),
        )
        assertTrue("缺必填参数 value" in missingValue.message, "${missingValue.message}")
        val badSchemaKey = assertIs<ToolResult.Err>(
            tool().executeTyped(
                SysVerifyTools.SysVerifyTool.Args(
                    key = "gsettings_set",
                    schema_key = "org.gnome.shell.free-form-key",
                    value = "true",
                ),
            ),
        )
        assertTrue("不在修复白名单" in badSchemaKey.message, "${badSchemaKey.message}")
    }

    @Test
    fun `首跑即生效-单次探查-attempts 一`() = runBlocking {
        val executor = FakeExecutor(listOf(outcome(output = "display /bin/true\nfcitx5\ndefault")))
        val result = tool(executor = executor).executeTyped(
            SysVerifyTools.SysVerifyTool.Args(
                key = "im_config_set_fcitx5",
                before = "display /bin/true\nibus\ndefault",
            ),
        )
        val ok = assertIs<ToolResult.Ok>(result)
        assertTrue("verify=effective" in ok.summary, "回执首行标三态：${ok.summary}")
        assertTrue("由「ibus」变更而来" in ok.summary, "before 快照参与 diff 判定：${ok.summary}")
        val payload = assertIs<ToolPayload.VerifyPayload>(ok.data)
        assertEquals("effective", payload.verdict)
        assertEquals(1, payload.attempts)
        assertEquals(1, executor.calls.size)
        assertEquals(listOf("im-config", "-m"), executor.calls[0])
    }

    @Test
    fun `首跑未生效自动复跑-第二次生效`() = runBlocking {
        val executor = FakeExecutor(
            listOf(
                outcome(output = "display /bin/true\nibus\ndefault"),
                outcome(output = "display /bin/true\nfcitx5\ndefault"),
            ),
        )
        val result = tool(executor = executor).executeTyped(
            SysVerifyTools.SysVerifyTool.Args(key = "im_config_set_fcitx5", before = "display /bin/true\nibus\ndefault"),
        )
        val ok = assertIs<ToolResult.Ok>(result)
        assertTrue("verify=effective" in ok.summary)
        assertEquals(2, executor.calls.size, "未生效须复跑一次")
        assertEquals(2, (ok.data as ToolPayload.VerifyPayload).attempts)
    }

    @Test
    fun `复跑仍失败降级外委-回执含 handoff 要素且不再重试`() = runBlocking {
        val executor = FakeExecutor(
            listOf(
                outcome(output = "display /bin/true\nibus\ndefault"),
                outcome(output = "display /bin/true\nibus\ndefault"),
            ),
        )
        val result = tool(executor = executor).executeTyped(
            SysVerifyTools.SysVerifyTool.Args(key = "im_config_set_fcitx5", before = "display /bin/true\nibus\ndefault"),
        )
        val ok = assertIs<ToolResult.Ok>(result)
        val text = ok.summary
        assertTrue("verify=not_effective" in text, "三态回执：$text")
        assertTrue("降级外委" in text, "复跑仍失败须明示降级：$text")
        assertTrue("sys.handoff" in text, "降级指引 handoff 件：$text")
        assertTrue("手动复查命令：im-config -m" in text, "降级要素含手动检查命令：$text")
        assertTrue("回滚配方" in text, "降级要素含回滚配方指引：$text")
        assertTrue("未经执行" in text, "外委话术纪律：未经执行声明")
        val payload = assertIs<ToolPayload.VerifyPayload>(ok.data)
        assertEquals("not_effective", payload.verdict)
        assertEquals(SysVerifyTools.MAX_ATTEMPTS, payload.attempts)
        assertEquals(SysVerifyTools.MAX_ATTEMPTS, executor.calls.size, "降级后不再重试")
    }

    @Test
    fun `无法判定-探查超时与空输出`() = runBlocking {
        val timedOut = tool(
            executor = FakeExecutor(listOf(outcome(timedOut = true), outcome(timedOut = true))),
        ).executeTyped(SysVerifyTools.SysVerifyTool.Args(key = "fcitx5_restart_daemon"))
        val ok1 = assertIs<ToolResult.Ok>(timedOut)
        assertTrue("verify=indeterminate" in ok1.summary, "超时属无法判定：${ok1.summary}")
        assertTrue("臆断" in ok1.summary, "无法判定须禁臆断提示")
        val blank = tool(
            executor = FakeExecutor(listOf(outcome(output = ""), outcome(output = "  "))),
        ).executeTyped(
            SysVerifyTools.SysVerifyTool.Args(
                key = "gsettings_set",
                schema_key = "org.gnome.shell.disable-user-extensions",
                value = "true",
            ),
        )
        val ok2 = assertIs<ToolResult.Ok>(blank)
        assertTrue("verify=indeterminate" in ok2.summary, "探查输出空白属无法判定：${ok2.summary}")
    }

    @Test
    fun `执行面异常翻译-策略拒绝与命令不可用`() = runBlocking {
        val policyDenied = tool(
            executor = SysProbeTools.ProbeExecutor { throw IllegalArgumentException("sudo 在黑名单内") },
        ).executeTyped(SysVerifyTools.SysVerifyTool.Args(key = "fcitx5_restart_daemon"))
        val err1 = assertIs<ToolResult.Err>(policyDenied)
        assertEquals(ToolResult.ErrCode.DENIED, err1.code)

        val notFound = tool(
            executor = SysProbeTools.ProbeExecutor { throw IOException("No such file") },
        ).executeTyped(SysVerifyTools.SysVerifyTool.Args(key = "fcitx5_restart_daemon"))
        val err2 = assertIs<ToolResult.Err>(notFound)
        assertEquals(ToolResult.ErrCode.NOT_FOUND, err2.code)
        assertTrue("sys.probe" in err2.renderText(), "不可用时提示回退人工复查路径")
    }

    @Test
    fun `审计落盘与目录注册`() = runBlocking {
        val auditDir = Files.createTempDirectory("sys-verify-audit").toFile()
        try {
            val runTool = SysVerifyTools.SysVerifyTool(
                executor = FakeExecutor(listOf(outcome(output = "display /bin/true\nfcitx5\ndefault"))),
                audit = SysAuditLog(auditDir),
                verifyGate = FakeGate(),
            )
            runTool.executeTyped(SysVerifyTools.SysVerifyTool.Args(key = "im_config_set_fcitx5"))
            val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
            assertTrue(file.isFile, "审计必须落盘：${file.path}")
            val records = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(1, records.size)
            assertEquals(SysVerifyTools.VERIFY_NAME, records[0]["tool"]!!.jsonPrimitive.content)
            assertEquals(SysAuditLog.VERDICT_SESSION_GATED, records[0]["verdict"]!!.jsonPrimitive.content)
        } finally {
            auditDir.deleteRecursively()
        }

        // 目录：READ_ONLY、sys 家族名集在册、工厂门控 fail-closed
        assertTrue(SysVerifyTools.VERIFY_NAME in SysProbeTools.SYS_NAMES, "sys.verify 须在 sys 家族名集")
        val descriptor = AgentToolCatalog.all.first { it.name == SysVerifyTools.VERIFY_NAME }
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, descriptor.risk, "只读探查复跑须标 READ_ONLY")
        val context = ToolExecutionContext(emptyList())
        assertIs<SysVerifyTools.SysVerifyTool>(
            descriptor.factory(
                context,
                ToolResultSink.NOOP,
                AgentToolCatalog.ToolContextConfig(sysSessionGate = FakeGate()),
            ),
            "门控面在位须注册",
        )
        kotlin.test.assertNull(
            descriptor.factory(context, ToolResultSink.NOOP, AgentToolCatalog.ToolContextConfig()),
            "门控面缺席 ⇒ 工厂返 null 不注册（fail-closed）",
        )
    }
}
