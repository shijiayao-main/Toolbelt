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
 * 启动自检探查器（StartupProber）守护：
 * 1. 正常态静默：全部探查项执行完成且未命中确定性异常态 ⇒ Normal（调用方零消息）；
 * 2. 异常态（fake 执行器逼真造态）⇒ Anomaly 模板消息（含条目人话 + argv + 脱敏输出 +
 *    机器环境语境 + 非定时任务脚注，整体 8K 收口）；
 * 3. 异常判则保守矩阵：Active: failed 才判异常；inactive (dead)/单元未安装不判；
 *    输入法期望 armed（im-config 用户级配置 = fcitx5）且进程缺失才判，期望未武装不跑 pgrep；
 * 4. 探查失败如实报告：命令缺失（IOException）/超时 ⇒ ProbeFailed（含条目与原因），不静默；
 * 5. 异常与失败并存 ⇒ Anomaly 消息随附失败清单；
 * 6. 输出脱敏：探查输出中的凭据形态行不进模板消息；
 * 7. 审计落盘：每探查项一条 startup.probe 记录，裁决按 执行放行/执行失败/围栏拒绝 分类。
 */
class StartupProberTest {

    /** 可编程 fake 执行器：按可执行文件名分发回执，记录全部调用序（探查子集与条件执行断言面） */
    private class FakeExecutor : SysProbeTools.ProbeExecutor {
        val calls = mutableListOf<List<String>>()
        val outcomes = mutableMapOf<String, ArgvOutcome>()
        val throws = mutableMapOf<String, Throwable>()

        override suspend fun run(argv: List<String>): ArgvOutcome {
            calls += argv
            throws[argv.first()]?.let { throw it }
            return outcomes.getValue(argv.first())
        }

        fun argvOf(executable: String): List<String> =
            calls.firstOrNull { it.first() == executable } ?: fail("未调用过 $executable")
    }

    // ── 正常机器态基线（与真实命令输出同构；systemctl 按服务名分发） ──

    private fun FakeExecutor.normalMachine(pgrepExit: Int = 0, pgrepOutput: String = "86723 fcitx5 -r -d") {
        outcomes["lsb_release"] = ArgvOutcome(
            exitCode = 0,
            output = "Distributor ID:\tUbuntu\nDescription:\tUbuntu 26.04 LTS\nRelease:\t26.04\nCodename:\tresolute\n",
            outputTruncated = false,
            timedOut = false,
        )
        outcomes["printenv"] = ArgvOutcome(
            exitCode = 0,
            output = "XDG_CURRENT_DESKTOP=ubuntu:GNOME\nXDG_SESSION_TYPE=wayland\nLANG=zh_CN.UTF-8\n",
            outputTruncated = false,
            timedOut = false,
        )
        outcomes["systemctl"] = ArgvOutcome(
            exitCode = 0,
            output = "● user.service - service\n     Active: active (running) since Mon 2025-01-01; 1h ago\n",
            outputTruncated = false,
            timedOut = false,
        )
        outcomes["im-config"] = ArgvOutcome(
            exitCode = 0,
            output = "default\nfcitx5\nnone\n\nibus\n",
            outputTruncated = false,
            timedOut = false,
        )
        outcomes["pgrep"] = ArgvOutcome(
            exitCode = pgrepExit,
            output = pgrepOutput,
            outputTruncated = false,
            timedOut = false,
        )
    }

    private fun proberWith(executor: FakeExecutor, auditDir: File? = null): StartupProber =
        StartupProber(executor = executor, audit = auditDir?.let { SysAuditLog(it) } ?: SysAuditLog.NOOP)

    // ── 正常态静默 ──

    @Test
    fun `全部探查项正常且 IM 进程在跑 ⇒ Normal 静默`() = runBlocking {
        val exec = FakeExecutor().apply { normalMachine() }
        val outcome = proberWith(exec).runStartupProbe()
        assertIs<StartupProbeOutcome.Normal>(outcome)
        // 探查子集断言：5 个目录条目全部触达（os/桌面/服务×N/im-config/pgrep——pgrep 因期望已武装必跑）
        val executables = exec.calls.map { it.first() }.toSet()
        assertTrue("lsb_release" in executables && "printenv" in executables, "语境两件必跑：$executables")
        assertTrue("im-config" in executables && "pgrep" in executables, "输入法两件必跑：$executables")
        assertEquals(
            StartupProber.STARTUP_PROBE_SERVICES.size,
            exec.calls.count { it.first() == "systemctl" },
            "服务探查数 = STARTUP_PROBE_SERVICES",
        )
        Unit
    }

    @Test
    fun `探查 argv 由 sys probe 目录模板单源组装且 systemctl 恒带 user`() = runBlocking {
        val exec = FakeExecutor().apply { normalMachine() }
        proberWith(exec).runStartupProbe()
        val statusArgv = exec.argvOf("systemctl")
        assertEquals("status", statusArgv[statusArgv.size - 2], "服务名是 argv 末元素（目录模板形态）")
        assertTrue("--user" in statusArgv, "systemctl 恒带 --user（执行面硬防线同源）")
        assertEquals(listOf("im-config", "-m"), exec.argvOf("im-config"), "im_config_mode 目录 argv 原样复用")
        assertEquals(listOf("pgrep", "-a", "fcitx5"), exec.argvOf("pgrep"), "fcitx5_processes 目录 argv 原样复用")
        Unit
    }

    // ── 异常态（模板消息） ──

    @Test
    fun `服务 Active failed ⇒ Anomaly 模板消息含服务名 argv 语境与脚注`() = runBlocking {
        val exec = FakeExecutor().apply {
            normalMachine()
            outcomes["systemctl"] = ArgvOutcome(
                exitCode = 3,
                output = "● pipewire.service - PipeWire\n     Active: failed (Result: exit-code) since 08:00; 2h ago\n",
                outputTruncated = false,
                timedOut = false,
            )
        }
        val outcome = proberWith(exec).runStartupProbe()
        val anomaly = assertIs<StartupProbeOutcome.Anomaly>(outcome)
        val msg = anomaly.message
        assertTrue("pipewire" in msg, "异常条目含服务名：$msg")
        assertTrue("failed" in msg, "异常判据人话：$msg")
        assertTrue("argv: " in msg && "systemctl --user" in msg, "证据块含完整 argv：$msg")
        assertTrue("Ubuntu" in msg, "模板消息含发行版语境：$msg")
        assertTrue("非定时任务" in msg, "启动触发非定时任务脚注钉死在消息面：$msg")
        assertTrue(msg.length <= StartupProber.MESSAGE_MAX_CHARS, "模板消息 8K 收口：${msg.length}")
    }

    @Test
    fun `IM 期望武装且进程缺失 ⇒ Anomaly（pgrep 无匹配）`() = runBlocking {
        val exec = FakeExecutor().apply { normalMachine(pgrepExit = 1, pgrepOutput = "") }
        val outcome = proberWith(exec).runStartupProbe()
        val anomaly = assertIs<StartupProbeOutcome.Anomaly>(outcome)
        assertTrue("fcitx5 进程未在运行" in anomaly.message, "异常人话：${anomaly.message}")
        assertTrue("pgrep" in anomaly.message, "证据块含 pgrep argv：${anomaly.message}")
    }

    @Test
    fun `异常与探查失败并存 ⇒ Anomaly 消息随附失败清单`() = runBlocking {
        val exec = FakeExecutor().apply {
            normalMachine(pgrepExit = 1, pgrepOutput = "")
            throws["lsb_release"] = IOException("not found")
        }
        val outcome = proberWith(exec).runStartupProbe()
        val anomaly = assertIs<StartupProbeOutcome.Anomaly>(outcome)
        assertTrue("探查未完成" in anomaly.message && "lsb_release" in anomaly.message, "失败清单随附：${anomaly.message}")
        assertTrue("fcitx5 进程未在运行" in anomaly.message, "异常段仍在：${anomaly.message}")
    }

    // ── 异常判则保守矩阵 ──

    @Test
    fun `inactive 与单元未安装都不判异常 ⇒ Normal`() = runBlocking {
        val exec = FakeExecutor().apply {
            normalMachine()
            outcomes["systemctl"] = ArgvOutcome(
                exitCode = 3,
                output = "● fcitx5.service - fcitx5\n     Active: inactive (dead)\n",
                outputTruncated = false,
                timedOut = false,
            )
        }
        assertIs<StartupProbeOutcome.Normal>(proberWith(exec).runStartupProbe())

        val notInstalled = FakeExecutor().apply {
            normalMachine()
            outcomes["systemctl"] = ArgvOutcome(
                exitCode = 4,
                output = "Unit wireplumber.service could not be found.\n",
                outputTruncated = false,
                timedOut = false,
            )
        }
        assertIs<StartupProbeOutcome.Normal>(proberWith(notInstalled).runStartupProbe())
        Unit
    }

    @Test
    fun `IM 期望未武装（用户级配置非 fcitx5）⇒ 不跑 pgrep 且 Normal`() = runBlocking {
        val exec = FakeExecutor().apply {
            normalMachine()
            outcomes["im-config"] = ArgvOutcome(
                exitCode = 0,
                output = "default\nnone\nnone\n\nibus\n",
                outputTruncated = false,
                timedOut = false,
            )
        }
        assertIs<StartupProbeOutcome.Normal>(proberWith(exec).runStartupProbe())
        assertTrue(
            exec.calls.none { it.first() == "pgrep" },
            "期望未武装不得跑进程核对（calls=${exec.calls}）",
        )
        Unit
    }

    @Test
    fun `im-config 输出形态不符 ⇒ 期望不武装不跑 pgrep`() = runBlocking {
        val exec = FakeExecutor().apply {
            normalMachine()
            outcomes["im-config"] = ArgvOutcome(
                exitCode = 0,
                output = "default\n",
                outputTruncated = false,
                timedOut = false,
            )
        }
        assertIs<StartupProbeOutcome.Normal>(proberWith(exec).runStartupProbe())
        assertTrue(exec.calls.none { it.first() == "pgrep" }, "解析不出配置行不武装：calls=${exec.calls}")
        Unit
    }

    // ── 探查失败如实报告 ──

    @Test
    fun `命令缺失 IOException ⇒ ProbeFailed 如实含条目与原因`() = runBlocking {
        val exec = FakeExecutor().apply {
            normalMachine()
            throws["lsb_release"] = IOException("Cannot run program")
        }
        val outcome = proberWith(exec).runStartupProbe()
        val failed = assertIs<StartupProbeOutcome.ProbeFailed>(outcome)
        assertTrue("lsb_release" in failed.message, "失败条目如实：${failed.message}")
        assertTrue("命令启动失败" in failed.message, "失败原因如实：${failed.message}")
        assertTrue("非定时任务" in failed.message, "脚注仍在：${failed.message}")
    }

    @Test
    fun `探查超时 ⇒ ProbeFailed 如实`() = runBlocking {
        val exec = FakeExecutor().apply {
            normalMachine()
            outcomes["systemctl"] = ArgvOutcome(
                exitCode = -1,
                output = "",
                outputTruncated = false,
                timedOut = true,
            )
        }
        val outcome = proberWith(exec).runStartupProbe()
        val failed = assertIs<StartupProbeOutcome.ProbeFailed>(outcome)
        assertTrue("超时" in failed.message, "超时原因如实：${failed.message}")
    }

    @Test
    fun `探查器整体异常折叠为 ProbeFailed 不抛业务异常`() = runBlocking {
        val exec = FakeExecutor().apply {
            normalMachine()
            // 撤掉 printenv 回执 ⇒ getValue 抛 NoSuchElementException（模拟实现缺陷兜底面）
            outcomes.remove("printenv")
        }
        val outcome = proberWith(exec).runStartupProbe()
        assertIs<StartupProbeOutcome.ProbeFailed>(outcome)
        Unit
    }

    // ── 输出脱敏 ──

    @Test
    fun `异常证据与语境经脱敏后出站（凭据形态行不进消息）`() = runBlocking {
        val exec = FakeExecutor().apply {
            normalMachine()
            outcomes["systemctl"] = ArgvOutcome(
                exitCode = 3,
                output = "● pipewire.service\n     Active: failed (Result: exit-code)\n  token=supersecret-value\n",
                outputTruncated = false,
                timedOut = false,
            )
        }
        val anomaly = assertIs<StartupProbeOutcome.Anomaly>(proberWith(exec).runStartupProbe())
        assertTrue("supersecret-value" !in anomaly.message, "凭据值不得进模板消息：${anomaly.message}")
    }

    // ── 审计落盘 ──

    @Test
    fun `每探查项落一条 startup probe 审计且裁决按形态分类`() = runBlocking {
        val auditDir = Files.createTempDirectory("startup-probe-audit").toFile()
        try {
            val exec = FakeExecutor().apply {
                normalMachine()
                throws["lsb_release"] = IOException("Cannot run program")
            }
            proberWith(exec, auditDir).runStartupProbe()
            val file = File(auditDir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
            val records = file.readLines().filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(
                exec.calls.size,
                records.size,
                "每探查项一条审计（执行触达数=${exec.calls.size}）",
            )
            assertTrue(records.all { it["tool"]!!.jsonPrimitive.content == "startup.probe" }, "tool 单源")
            assertTrue(records.all { it["trigger"]!!.jsonPrimitive.content == "startup" }, "trigger 单源")
            assertEquals(
                SysAuditLog.VERDICT_FAILED,
                records.first { it["action"]!!.jsonPrimitive.content.startsWith("lsb_release") }["verdict"]!!.jsonPrimitive.content,
                "执行面失败记执行失败",
            )
            assertEquals(
                SysAuditLog.VERDICT_STARTUP_PROBE,
                records.first { it["action"]!!.jsonPrimitive.content.startsWith("systemctl") }["verdict"]!!.jsonPrimitive.content,
                "正常执行记启动自检放行",
            )
            // 输出全文不落审计（只有摘要字段）——审计明细永不外发纪律的写侧底线
            assertTrue(records.all { it["outputSummary"]!!.jsonPrimitive.content.length <= SysAuditLog.SUMMARY_MAX_CHARS })
        } finally {
            auditDir.deleteRecursively()
        }
        Unit
    }
}
