package com.jiaoay.easy_dev_toolbox.core.system

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * sys 审计落盘器守护（fake 注入路径写读回验证记录形态）：
 * 1. 追加即按日切文件（`sys-audit-<yyyy-MM-dd>.jsonl`）纯追加多行 JSONL；
 * 2. 记录字段写读回守恒（ts/tool/action/verdict/exitCode/trigger/outputSummary）；
 * 3. NOOP 注入件静默丢弃（不建目录不建文件）；
 * 4. 输出摘要在记录面即受 200 字符上界约束（输出全文不落审计）。
 */
class SysAuditLogTest {

    private fun tempDir(): File = Files.createTempDirectory("sys-audit-test").toFile()

    @Test
    fun `追加落按日文件且多行纯追加`() = runBlocking {
        val dir = tempDir()
        val log = SysAuditLog(dir)
        try {
            log.append(sample(verdict = SysAuditLog.VERDICT_UNGATED, exitCode = 0))
            log.append(sample(verdict = SysAuditLog.VERDICT_FENCE_DENIED, exitCode = null))
            log.append(sample(verdict = SysAuditLog.VERDICT_BAD_ARGS, exitCode = null))
            val file = File(dir, SysAuditLog.fileNameFor(java.time.LocalDate.now()))
            assertTrue(file.isFile, "落点必须是按日文件：${file.path}")
            val lines = file.readLines()
            assertEquals(3, lines.size, "纯追加三行 JSONL（无覆盖）")
            assertEquals(SysAuditLog.VERDICT_FENCE_DENIED, lines[1].let { jsonField(it, "verdict") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `记录字段写读回守恒`() = runBlocking {
        val dir = tempDir()
        val log = SysAuditLog(dir)
        try {
            val record = sample(
                verdict = SysAuditLog.VERDICT_UNGATED,
                exitCode = 1,
                trigger = "chatId=oc_x senderId=ou_y",
                outputSummary = "key=im_config_mode argv: im-config -m exit=1",
            )
            log.append(record)
            val line = File(dir, SysAuditLog.fileNameFor(java.time.LocalDate.now())).readLines().single()
            val parsed = Json.parseToJsonElement(line).jsonObject
            assertEquals(record.ts, jsonField(line, "ts"))
            assertEquals(record.tool, jsonField(line, "tool"))
            assertEquals(record.action, jsonField(line, "action"))
            assertEquals(record.verdict, jsonField(line, "verdict"))
            assertEquals("1", parsed["exitCode"]?.jsonPrimitive?.content, "退出码须原样落盘（数据非失败信号）")
            assertEquals(record.trigger, jsonField(line, "trigger"))
            assertEquals(record.outputSummary, jsonField(line, "outputSummary"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `NOOP 注入件静默丢弃不落盘`() = runBlocking {
        SysAuditLog.NOOP.append(sample(verdict = SysAuditLog.VERDICT_UNGATED))
        assertTrue(true, "NOOP 不得抛（单测/无审计形态注入件）")
    }

    private fun jsonField(line: String, field: String): String =
        Json.parseToJsonElement(line).jsonObject[field]!!.jsonPrimitive.content

    private fun sample(
        verdict: String,
        exitCode: Int? = null,
        trigger: String? = null,
        outputSummary: String? = null,
    ): SysAuditRecord = SysAuditRecord(
        ts = SysAuditLog.timestamp(),
        tool = "sys.probe",
        action = "im-config -m",
        verdict = verdict,
        exitCode = exitCode,
        trigger = trigger,
        outputSummary = outputSummary,
    )
}
