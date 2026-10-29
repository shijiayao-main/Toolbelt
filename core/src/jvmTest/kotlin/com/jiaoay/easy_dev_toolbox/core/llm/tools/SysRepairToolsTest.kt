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
 * sys.repair 守护：
 * 1. 目录静态断言：四动词、可执行文件全在结构性白名单、canonical argv 零 shell 元字符、
 *    im_config/fcitx5 argv 字面钉死（严格单参形态）、回滚配方/语义/风险徽标/验证提示在位；
 * 2. 值形态校验矩阵：布尔字面/字符串数组（GVariant 形态 + 元素 uuid 形态）合法与非法双面；
 * 3. 授权分级：门控缺席/会话授权拒绝/动作卡拒绝三面恒 DENIED（fail-closed）且不触执行器；
 *    会话授权不自动覆盖修复动作（L0 放行 ≠ L1b 放行）；
 * 4. 非法入参拒收：未知 key / 槽纪律 / uuid 形态 / schema.key 白名单外；
 * 5. fake 执行器：先快照后执行、argv 组装、回执含回滚信息与验证提示、写动作非零退出码
 *    = 未生效语义、必需快照失败拒执、可选快照失败降级、审计含 rollback 字段。
 */
class SysRepairToolsTest {

    /** 测试授权面（sessionApproved/actionApproved 可编程；记录动作卡供卡面断言） */
    private class FakeRepairGate(
        private val sessionApproved: Boolean = true,
        private val actionApproved: Boolean = true,
    ) : SysRepairTools.SysRepairGate {
        var sessionAsked = 0
        var actionAsked = 0
        val cards = mutableListOf<SysRepairTools.RepairActionCard>()

        override suspend fun requireSessionAuthorization(toolName: String): Boolean {
            sessionAsked++
            return sessionApproved
        }

        override suspend fun requireActionApproval(card: SysRepairTools.RepairActionCard): Boolean {
            actionAsked++
            cards += card
            return actionApproved
        }

        override val familyEscapeGate: ToolExecutionContext.PathEscapeGate =
            ToolExecutionContext.PathEscapeGate { _ -> false }

        override val trigger: String? = "chatId=oc_test sender=ou_test"
    }

    // ── 目录静态断言（机械断言：破坏面收口不靠人工自觉） ──

    /** 目录条目的代表性合法槽值（schema_key 取白名单首键，值形态按该键类型采样） */
    private fun canonicalSlots(key: SysRepairTools.RepairKey): Map<SysRepairTools.RepairSlot, String> {
        val schemaKey = SysRepairTools.GSETTINGS_WRITE_KEYS.keys.first()
        return buildMap {
            if (SysRepairTools.RepairSlot.UUID in key.required) put(SysRepairTools.RepairSlot.UUID, "ubuntu-dock@ubuntu.com")
            if (SysRepairTools.RepairSlot.SCHEMA_KEY in key.required) put(SysRepairTools.RepairSlot.SCHEMA_KEY, schemaKey)
            if (SysRepairTools.RepairSlot.VALUE in key.required) {
                put(SysRepairTools.RepairSlot.VALUE, sampleValueFor(schemaKey))
            }
        }
    }

    private fun sampleValueFor(schemaKey: String): String =
        when (SysRepairTools.GSETTINGS_WRITE_KEYS.getValue(schemaKey)) {
            SysRepairTools.GsettingsValueType.BOOL -> "false"
            SysRepairTools.GsettingsValueType.STRING_ARRAY -> "['ubuntu-dock@ubuntu.com']"
        }

    @Test
    fun `首批目录四动词且可执行文件全在结构性白名单`() {
        assertEquals(4, SysRepairTools.RepairKey.entries.size, "首批四动词（加条目走提案评审并同步本断言）")
        for (key in SysRepairTools.RepairKey.entries) {
            val argv = key.buildArgv(canonicalSlots(key))
            assertTrue(argv.isNotEmpty(), "${key.name} argv 不能为空")
            val executable = argv.first()
            assertTrue(
                executable in SysRepairTools.REPAIR_ALLOWED_EXECUTABLES,
                "${key.name} 可执行文件「$executable」不在修复动作结构性白名单内（删除/磁盘/网络/包管理/提权面无法入目录）",
            )
            assertTrue(executable !in ArgvProcessRunner.BLOCKED_EXECUTABLES, "${key.name} 命中提权/包管理黑名单")
            assertTrue("/" !in executable, "${key.name} 可执行文件须是命令名非路径形态")
        }
    }

    @Test
    fun `目录条目 argv 零 shell 元字符（安全字符集断言）`() {
        // argv 纯语义下元字符本就无解释面，本断言守「目录条目绝不向 shell 语义漂移」：
        // 值槽是 LLM 供给且运行时经形态校验，此处 canonical 采样走合法值
        val argvElementRegex = Regex("""^[A-Za-z0-9@\[\]'.:,_/-]+$""")
        val shellMetachars = charArrayOf(';', '|', '&', '$', '`', '(', ')', '<', '>', '\\', '"')
        for (key in SysRepairTools.RepairKey.entries) {
            for (element in key.buildArgv(canonicalSlots(key))) {
                assertTrue(
                    argvElementRegex.matches(element),
                    "${key.name} 的 argv 元素「$element」含白名单字符集外字符（shell 语义漂移）",
                )
                for (meta in shellMetachars) {
                    assertTrue(meta !in element, "${key.name} 的 argv 元素「$element」含 shell 元字符 $meta")
                }
            }
        }
    }

    @Test
    fun `im_config 与 fcitx5 动作 argv 字面钉死（严格单参形态）`() {
        assertEquals(
            listOf("im-config", "-n", "fcitx5"),
            SysRepairTools.RepairKey.IM_CONFIG_SET_FCITX5.buildArgv(emptyMap()),
            "im_config 动作 argv 严格钉死（无自由参数）",
        )
        assertEquals(
            listOf("fcitx5", "-d"),
            SysRepairTools.RepairKey.FCITX5_RESTART_DAEMON.buildArgv(emptyMap()),
            "fcitx5 动作 argv 严格钉死为 fcitx5 -d 单参形态（-r/--enable 等旗标永不入目录）",
        )
    }

    @Test
    fun `回滚配方与语义说明风险徽标验证提示字段在位`() {
        for (key in SysRepairTools.RepairKey.entries) {
            assertTrue(key.label.isNotBlank(), "${key.name} 语义化动作名缺失")
            assertTrue(key.semantics.isNotBlank(), "${key.name} 语义说明缺失")
            assertTrue(key.verifyHint.isNotBlank(), "${key.name} 验证提示缺失")
            assertTrue(
                key.riskBadge in setOf("MEDIUM", "HIGH"),
                "${key.name} 风险徽标必须是 MEDIUM/HIGH 档（实际 ${key.riskBadge}）",
            )
            val rollback = key.rollbackOf(canonicalSlots(key), "")
            assertTrue(rollback.isNotBlank(), "${key.name} 回滚配方缺失（快照未捕获形态也要有降级配方）")
            assertTrue(
                rollback.length <= SysRepairTools.ROLLBACK_MAX_CHARS,
                "${key.name} 回滚配方须收口在 ${SysRepairTools.ROLLBACK_MAX_CHARS} 内",
            )
        }
    }

    @Test
    fun `gsettings 写键白名单与 schema 实测类型一一对应`() {
        assertEquals(
            mapOf(
                "org.gnome.shell.enabled-extensions" to SysRepairTools.GsettingsValueType.STRING_ARRAY,
                "org.gnome.shell.disabled-extensions" to SysRepairTools.GsettingsValueType.STRING_ARRAY,
                "org.gnome.shell.disable-user-extensions" to SysRepairTools.GsettingsValueType.BOOL,
            ),
            SysRepairTools.GSETTINGS_WRITE_KEYS,
            "写键白名单 = GNOME 扩展启停相关三键（as/as/b，gsettings range 实测类型）；加键同步本断言",
        )
    }

    // ── 值形态校验矩阵 ──

    @Test
    fun `布尔键只收字面 true false`() {
        val key = "org.gnome.shell.disable-user-extensions"
        for (good in listOf("true", "false")) {
            val check = SysRepairTools.checkSchemaValue(key, good)
            assertTrue(check is SysProbeTools.SlotCheck.Accepted, "布尔键应放行「$good」")
        }
        for (bad in listOf("TRUE", "True", "1", "0", "yes", "'true'", "")) {
            val check = SysRepairTools.checkSchemaValue(key, bad)
            assertTrue(check is SysProbeTools.SlotCheck.Rejected, "布尔键应拒收「$bad」")
            if (check is SysProbeTools.SlotCheck.Rejected) {
                assertTrue("布尔" in check.reason, "拒收文案须说明布尔类型：${check.reason}")
            }
        }
    }

    @Test
    fun `字符串数组键收 GVariant 形态且元素须 uuid 形态`() {
        val key = "org.gnome.shell.enabled-extensions"
        for (good in listOf("[]", "['ubuntu-dock@ubuntu.com']", "['a@b.c', 'd@e.f']")) {
            val check = SysRepairTools.checkSchemaValue(key, good)
            assertTrue(check is SysProbeTools.SlotCheck.Accepted, "字符串数组键应放行「$good」")
        }
        for (bad in listOf(
            "ubuntu-dock@ubuntu.com",       // 缺数组包裹
            "['not-a-uuid']",               // 元素非 uuid 形态
            "[ubuntu-dock@ubuntu.com]",     // 缺引号
            "['a@b.c\", \"d@e.f']",         // 双引号形态
            "@as []",                       // 带类型前缀的 GVariant 形态
            "['a@b.c' 'd@e.f']",            // 缺逗号分隔
        )) {
            val check = SysRepairTools.checkSchemaValue(key, bad)
            assertTrue(check is SysProbeTools.SlotCheck.Rejected, "字符串数组键应拒收「$bad」")
        }
    }

    @Test
    fun `写键白名单外 schema key 拒收并给加键路径`() {
        val check = SysRepairTools.checkSchemaValue("org.gnome.desktop.interface.gtk-theme", "Yaru")
        assertTrue(check is SysProbeTools.SlotCheck.Rejected, "只读白名单键不自动进写白名单")
        if (check is SysProbeTools.SlotCheck.Rejected) {
            assertTrue("GSETTINGS_WRITE_KEYS" in check.reason)
            assertTrue("org.gnome.shell.enabled-extensions" in check.reason, "拒收文案须列允许键")
        }
    }

    // ── 授权分级（L1b：域准入 + 逐动作卡，fail-closed） ──

    private fun fakeTool(
        recorded: MutableList<List<String>> = mutableListOf(),
        gate: SysRepairTools.SysRepairGate? = FakeRepairGate(),
        run: suspend (argv: List<String>) -> ArgvOutcome = { ArgvOutcome(0, "", false, false) },
    ): SysRepairTools.SysRepairTool =
        SysRepairTools.SysRepairTool(
            executor = SysProbeTools.ProbeExecutor { argv ->
                recorded.add(argv)
                run(argv)
            },
            audit = SysAuditLog.NOOP,
            repairGate = gate,
        )

    @Test
    fun `门控面缺席恒 DENIED 且不触执行器`() = runBlocking {
        val tool = fakeTool(gate = null) { fail("门控面缺席不得触执行器") }
        val err = tool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "fcitx5_restart_daemon")) as? ToolResult.Err
            ?: fail("门控面缺席必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertTrue("确认门" in err.message, "回执须指明逐动作确认门缺席（装配缺陷）：${err.message}")
    }

    @Test
    fun `会话授权拒绝恒 DENIED 且不弹动作卡不触执行器`() = runBlocking {
        val gate = FakeRepairGate(sessionApproved = false)
        val tool = fakeTool(gate = gate) { fail("会话未授权不得触执行器") }
        val err = tool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "fcitx5_restart_daemon")) as? ToolResult.Err
            ?: fail("会话授权拒绝必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertEquals(0, gate.actionAsked, "会话未授权不得弹动作卡（域准入前置）")
        assertTrue("禁止编造" in err.message, "回执须钉禁止编造执行结果：${err.message}")
    }

    @Test
    fun `动作卡拒绝恒 DENIED 不触执行器且会话授权不自动覆盖修复动作`() = runBlocking {
        val gate = FakeRepairGate(sessionApproved = true, actionApproved = false)
        val recorded = mutableListOf<List<String>>()
        val tool = fakeTool(recorded, gate = gate) { argv ->
            if (argv.first() == "pgrep") ArgvOutcome(0, "", false, false) else fail("动作卡拒绝不得触执行器（主命令）")
        }
        val err = tool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "fcitx5_restart_daemon")) as? ToolResult.Err
            ?: fail("动作卡拒绝必须 DENIED")
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        assertEquals(1, gate.sessionAsked, "会话授权被请求（域准入）")
        assertEquals(1, gate.actionAsked, "逐动作确认卡被请求（会话授权不自动覆盖修复动作）")
        assertEquals(1, recorded.size, "只有原值快照命令触达执行器，主命令不得执行：$recorded")
        assertTrue("未经用户确认" in err.message, "回执须明示未执行：${err.message}")
    }

    @Test
    fun `动作卡面含完整 argv 语义风险徽标与回滚配方`() = runBlocking {
        val gate = FakeRepairGate()
        val tool = fakeTool(
            run = { argv -> if (argv.first() == "pgrep") ArgvOutcome(0, "86723 fcitx5 -r -d\n", false, false) else ArgvOutcome(0, "", false, false) },
            gate = gate,
        )
        tool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "fcitx5_restart_daemon"))
        val card = gate.cards.single()
        assertEquals("fcitx5_restart_daemon", card.actionKey)
        assertEquals(listOf("fcitx5", "-d"), card.argv, "卡面完整 argv")
        assertTrue(card.label.contains("fcitx5"), "语义化动作名：${card.label}")
        assertEquals("HIGH", card.riskBadge, "重启守护 = HIGH 徽标")
        assertTrue("回滚" in card.rollback && "pkill" in card.rollback, "卡面回滚配方：${card.rollback}")
        assertTrue("此前已有 fcitx5 进程" in card.rollback, "快照捕获到在跑进程时回滚配方须反映：${card.rollback}")
    }

    // ── 非法入参拒收 ──

    @Test
    fun `未知 key 拒收并回执可用动作`() = runBlocking {
        val gate = FakeRepairGate()
        val tool = fakeTool(gate = gate)
        for (bad in listOf("rm -rf /", "sudo apt install", "no_such_action", "")) {
            val err = tool.executeTyped(SysRepairTools.SysRepairTool.Args(key = bad)) as? ToolResult.Err
                ?: fail("未知 key 必须 BAD_ARGS，实际输入「$bad」")
            assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
            assertTrue("可用动作：im_config_set_fcitx5" in err.message, "回执须列可用动作：${err.message}")
        }
        assertEquals(4, gate.sessionAsked, "每次调用都先过会话授权（域准入前置，4 次拒收累计）")
        assertEquals(0, gate.actionAsked, "未知 key 不弹动作卡")
    }

    @Test
    fun `槽纪律：未约定槽传值与必填槽缺失都拒收`() = runBlocking {
        val tool = fakeTool()
        val unexpected = tool.executeTyped(
            SysRepairTools.SysRepairTool.Args(key = "fcitx5_restart_daemon", uuid = "a@b.c"),
        ) as? ToolResult.Err ?: fail("无槽动作带参必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, unexpected.code)
        assertTrue("uuid" in unexpected.message, "回执须指明未约定的槽：${unexpected.message}")

        val missing = tool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "gnome_extension_enable"))
            as? ToolResult.Err ?: fail("必填槽缺失必须 BAD_ARGS")
        assertEquals(ToolResult.ErrCode.BAD_ARGS, missing.code)
        assertTrue("uuid" in missing.message, "回执须指明缺失的必填槽：${missing.message}")

        val noValue = tool.executeTyped(
            SysRepairTools.SysRepairTool.Args(key = "gsettings_set", schema_key = "org.gnome.shell.enabled-extensions"),
        ) as? ToolResult.Err ?: fail("缺 value 槽必须 BAD_ARGS")
        assertTrue("value" in noValue.message, "回执须指明缺失的 value 槽：${noValue.message}")
    }

    @Test
    fun `uuid 形态外拒收（sys probe 同款校验）`() = runBlocking {
        val tool = fakeTool()
        for (bad in listOf("not-a-uuid", "a b@c", "@domain.only", "x@")) {
            val err = tool.executeTyped(
                SysRepairTools.SysRepairTool.Args(key = "gnome_extension_enable", uuid = bad),
            ) as? ToolResult.Err ?: fail("形态外 uuid「$bad」必须 BAD_ARGS")
            assertEquals(ToolResult.ErrCode.BAD_ARGS, err.code)
            assertTrue("uuid@domain" in err.message, "回执须给出合法形态：${err.message}")
        }
    }

    // ── fake 执行器：快照/回执/审计 ──

    @Test
    fun `先快照后执行且 gsettings argv 组装正确`() = runBlocking {
        val recorded = mutableListOf<List<String>>()
        val tool = fakeTool(recorded) { argv ->
            when (argv.first()) {
                "gsettings" -> if (argv[1] == "get") ArgvOutcome(0, "['ding@rastersoft.com']\n", false, false) else ArgvOutcome(0, "", false, false)
                else -> fail("不应有其他命令：$argv")
            }
        }
        val result = tool.executeTyped(
            SysRepairTools.SysRepairTool.Args(
                key = "gsettings_set",
                schema_key = "org.gnome.shell.enabled-extensions",
                value = "['ubuntu-dock@ubuntu.com']",
            ),
        )
        assertEquals(
            listOf(
                listOf("gsettings", "get", "org.gnome.shell", "enabled-extensions"),
                listOf("gsettings", "set", "org.gnome.shell", "enabled-extensions", "['ubuntu-dock@ubuntu.com']"),
            ),
            recorded,
            "先 get 快照后 set 写入；schema.key 拆两段；value 原样单元素传递",
        )
        val ok = result as? ToolResult.Ok ?: fail("正常执行须 Ok 回执，实际 $result")
        val payload = ok.data as? ToolPayload.RepairPayload ?: fail("结构化负载须为 RepairPayload")
        assertEquals("gsettings_set", payload.key)
        assertEquals(0, payload.exitCode)
        assertTrue(
            "gsettings set org.gnome.shell enabled-extensions ['ding@rastersoft.com']" in payload.rollback,
            "回滚配方须含执行前捕获的原值：${payload.rollback}",
        )
        assertTrue("回滚：gsettings set" in ok.summary, "回执须含回滚信息：${ok.summary}")
        assertTrue("验证" in ok.summary, "回执须含验证提示：${ok.summary}")
    }

    @Test
    fun `im_config 快照解析第 2 行且回滚配方含原值`() = runBlocking {
        val recorded = mutableListOf<List<String>>()
        val tool = fakeTool(recorded) { argv ->
            when (argv.first()) {
                "im-config" -> if ("-m" in argv) ArgvOutcome(0, "default\nfcitx5\nnone\n\nibus\n", false, false) else ArgvOutcome(0, "", false, false)
                else -> fail("不应有其他命令：$argv")
            }
        }
        val ok = tool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "im_config_set_fcitx5")) as? ToolResult.Ok
            ?: fail("正常执行须 Ok 回执")
        assertEquals(2, recorded.size, "快照 + 主命令各一次：$recorded")
        assertEquals(listOf("im-config", "-n", "fcitx5"), recorded.last(), "主命令 argv")
        assertTrue(
            "回滚：im-config -n fcitx5（原值=fcitx5" in ok.summary,
            "回滚配方取 im-config -m 第 2 行（用户级 active IM）：${ok.summary}",
        )
        assertTrue("（无输出）" in ok.summary, "im-config -n 无输出须显式占位：${ok.summary}")
    }

    @Test
    fun `必需快照失败拒执且可选快照失败降级继续`() = runBlocking {
        // im_config（preReadRequired=true）：快照命令失败 ⇒ 拒绝执行
        val required = fakeTool { throw IOException("no im-config") }
        val err = required.executeTyped(SysRepairTools.SysRepairTool.Args(key = "im_config_set_fcitx5")) as? ToolResult.Err
            ?: fail("必需快照失败必须 FAILED")
        assertEquals(ToolResult.ErrCode.FAILED, err.code)
        assertTrue("无回滚保障" in err.message, "拒执文案须明示无回滚保障：${err.message}")

        // fcitx5（可选快照）：快照失败降级为未捕获形态，主命令照常走卡执行
        val optional = fakeTool { argv ->
            if (argv.first() == "pgrep") throw IOException("no pgrep") else ArgvOutcome(0, "", false, false)
        }
        val ok = optional.executeTyped(SysRepairTools.SysRepairTool.Args(key = "fcitx5_restart_daemon")) as? ToolResult.Ok
            ?: fail("可选快照失败仍须执行主命令")
        assertTrue(
            "此前无 fcitx5 进程" in ok.summary,
            "快照未捕获时回滚配方按「未捕获」降级：${ok.summary}",
        )
        assertTrue("pkill" in ok.summary, "降级配方仍须给出回滚路径：${ok.summary}")
    }

    @Test
    fun `写动作非零退出码按未生效语义回执且提示验证`() = runBlocking {
        val tool = fakeTool { argv ->
            when {
                argv[1] == "get" -> ArgvOutcome(0, "['ding@rastersoft.com']\n", false, false)
                else -> ArgvOutcome(1, "error: key not found\n", false, false)
            }
        }
        val ok = tool.executeTyped(
            SysRepairTools.SysRepairTool.Args(
                key = "gsettings_set",
                schema_key = "org.gnome.shell.enabled-extensions",
                value = "[]",
            ),
        ) as? ToolResult.Ok ?: fail("非零退出码仍是 Ok 回执（退出码是数据）")
        assertTrue("未生效" in ok.summary, "写动作非零语义与只读探查不同，回执须明示：${ok.summary}")
        assertTrue("exit=1" in ok.summary, "回执须含退出码：${ok.summary}")
        val payload = ok.data as? ToolPayload.RepairPayload ?: fail("结构化负载须为 RepairPayload")
        assertEquals(1, payload.exitCode)
    }

    @Test
    fun `超时按 FAILED 回执且提示核实实际状态`() = runBlocking {
        val tool = fakeTool { argv ->
            if (argv.first() == "pgrep") ArgvOutcome(0, "", false, false) else ArgvOutcome(-1, "", false, timedOut = true)
        }
        val err = tool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "fcitx5_restart_daemon")) as? ToolResult.Err
            ?: fail("超时必须 FAILED")
        assertEquals(ToolResult.ErrCode.FAILED, err.code)
        assertTrue("超时" in err.message && "核实实际状态" in err.message, "写动作超时=结果未知，须提示核实：${err.message}")
    }

    @Test
    fun `命令不可用按 NOT_FOUND 回执且禁止编造`() = runBlocking {
        val tool = fakeTool { argv ->
            if (argv.first() == "gsettings") ArgvOutcome(0, "false\n", false, false) else throw IOException("Cannot run program fcitx5")
        }
        val err = tool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "fcitx5_restart_daemon")) as? ToolResult.Err
            ?: fail("启动失败必须 NOT_FOUND")
        assertEquals(ToolResult.ErrCode.NOT_FOUND, err.code)
        assertTrue(err.hint.orEmpty().contains("禁止编造"), "提示须含禁止编造执行结果：${err.hint}")
    }

    @Test
    fun `超长输出恒 8K capAt 收口且负载标记截断`() = runBlocking {
        val huge = "y".repeat(50_000)
        val tool = fakeTool { argv ->
            if ("-m" in argv) ArgvOutcome(0, "default\nfcitx5\nnone\n\nibus\n", false, false) else ArgvOutcome(0, huge, false, false)
        }
        val ok = tool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "im_config_set_fcitx5")) as? ToolResult.Ok
            ?: fail("超长输出仍须 Ok 回执")
        assertTrue(ok.summary.length <= 8192, "回执总长恒 ≤ 8K（实际 ${ok.summary.length}）")
        assertTrue("截断" in ok.summary, "超上界须带截断标记：${ok.summary.takeLast(80)}")
        assertTrue((ok.data as? ToolPayload.RepairPayload)?.truncated == true, "负载须标记截断")
    }

    @Test
    fun `审计落盘含 rollback 字段且拒绝面分记动作确认拒绝`() = runBlocking {
        val auditDir = Files.createTempDirectory("sys-repair-audit").toFile()
        try {
            val runGate = FakeRepairGate()
            val runTool = SysRepairTools.SysRepairTool(
                executor = SysProbeTools.ProbeExecutor { argv ->
                    if (argv.first() == "pgrep") ArgvOutcome(0, "1 fcitx5\n", false, false) else ArgvOutcome(0, "", false, false)
                },
                audit = SysAuditLog(auditDir),
                repairGate = runGate,
            )
            runTool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "fcitx5_restart_daemon"))

            val deniedGate = FakeRepairGate(actionApproved = false)
            val deniedTool = SysRepairTools.SysRepairTool(
                executor = SysProbeTools.ProbeExecutor { argv ->
                    if (argv.first() == "pgrep") ArgvOutcome(0, "1 fcitx5\n", false, false) else fail("动作卡拒绝不得执行主命令")
                },
                audit = SysAuditLog(auditDir),
                repairGate = deniedGate,
            )
            deniedTool.executeTyped(SysRepairTools.SysRepairTool.Args(key = "fcitx5_restart_daemon"))

            val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
            assertTrue(file.isFile, "审计必须落盘：${file.path}")
            val records = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(2, records.size, "成功与动作卡拒绝各一行（含被拒）")
            fun field(i: Int, name: String) = records[i][name]!!.jsonPrimitive.content
            assertEquals("sys.repair", field(0, "tool"))
            assertEquals("fcitx5 -d", field(0, "action"), "argv 全文入审计")
            assertEquals(SysAuditLog.VERDICT_SESSION_GATED, field(0, "verdict"))
            assertEquals("0", records[0]["exitCode"]?.jsonPrimitive?.content)
            assertTrue(
                (records[0]["rollback"]?.jsonPrimitive?.content ?: "").contains("pkill"),
                "成功行 rollback 字段须含回滚配方：${records[0]["rollback"]}",
            )
            assertEquals(SysAuditLog.VERDICT_ACTION_DENIED, field(1, "verdict"), "动作卡拒绝须分记动作确认拒绝")
            assertEquals("fcitx5 -d", field(1, "action"), "拒绝行也记完整 argv")
            records.forEach { record ->
                val summary = record["outputSummary"]?.jsonPrimitive?.content.orEmpty()
                assertTrue(summary.length <= SysAuditLog.SUMMARY_MAX_CHARS, "输出摘要恒 ≤ 200 字符（全文不落）")
            }
        } finally {
            auditDir.deleteRecursively()
        }
    }

    @Test
    fun `sys repair 注册进目录且风险写面、不进命令面板、names 单源含三件`() {
        val descriptor = AgentToolCatalog.all.firstOrNull { it.name == "sys.repair" }
            ?: error("sys.repair 未注册进 AgentToolCatalog")
        assertEquals(AgentToolCatalog.Risk.DESTRUCTIVE, descriptor.risk, "写系统状态面，必须 DESTRUCTIVE")
        assertNull(descriptor.uiHint, "纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertEquals(descriptor.aliases, emptySet<String>(), "无别名（撞面零容忍）")
        assertEquals(
            setOf(
                "sys.probe", "sys.read_config", "sys.repair", "sys.write_config",
                "sys.service", "sys.verify", "sys.handoff",
            ),
            SysProbeTools.SYS_NAMES,
            "sys.* 家族名集单源（消费方选择集引用此集合）",
        )
        assertEquals("sys.repair", AgentToolCatalog.resolve("sys.repair"), "目录实名必须可经 resolve 解析")
    }

    @Test
    fun `sys repair 工厂门控（sysRepairGate 缺席不注册）`() {
        val context = ToolExecutionContext(emptyList<File>())
        val absent = AgentToolCatalog.all.first { it.name == "sys.repair" }
            .factory(context, ToolResultSink.NOOP, AgentToolCatalog.ToolContextConfig())
        assertNull(absent, "sysRepairGate 缺席（fail-closed）⇒ 工厂返 null 不注册")
        val present = AgentToolCatalog.all.first { it.name == "sys.repair" }
            .factory(
                context,
                ToolResultSink.NOOP,
                AgentToolCatalog.ToolContextConfig(sysRepairGate = FakeRepairGate()),
            )
        assertTrue(present != null && present.name == "sys.repair", "sysRepairGate 在位 ⇒ 注册")
    }
}
