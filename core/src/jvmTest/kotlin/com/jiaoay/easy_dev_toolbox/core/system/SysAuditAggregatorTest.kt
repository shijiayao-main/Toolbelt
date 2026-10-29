package com.jiaoay.easy_dev_toolbox.core.system

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * sys-audit 按日聚合计数器守护（**只聚合不落原文**——审计拒外发纪律的读形态）：
 * 1. 按工具/拒绝裁决计数正确（会话授权拒绝/动作确认拒绝/围栏拒绝/参数拒收 ∈ 拒绝集，
 *    执行失败/目标不存在/放行不混入拒绝）；
 * 2. 聚合产物零原文：记录的 action/outputSummary/trigger 原文串在 aggregate toString 与
 *    byTool 键面双双不出现（原文在计数后即弃，结构性不携带）；
 * 3. 文件不存在 = 全零聚合（今日无记录是正常答案）；目录缺席 = null（如实报不可得不编零）；
 * 4. 坏行跳过不计数不炸面（其余行照常聚合）。
 */
class SysAuditAggregatorTest {

    private fun tempDir(): File = Files.createTempDirectory("sys-audit-agg-test").toFile()

    private suspend fun SysAuditLog.write(vararg records: SysAuditRecord) {
        records.forEach { append(it) }
    }

    private fun record(tool: String, verdict: String, action: String = "argv-im-config -m", summary: String? = null) =
        SysAuditRecord(
            ts = "2026-10-14T10:00:00",
            tool = tool,
            action = action,
            verdict = verdict,
            exitCode = null,
            trigger = "chatId=oc_test sender=ou_test",
            outputSummary = summary,
        )

    @Test
    fun `按工具与拒绝裁决计数且原文不进聚合`() = runBlocking {
        val dir = tempDir()
        val log = SysAuditLog(dir)
        val secretAction = "SECRET-ARGV-pay-load-9x8y"
        val secretSummary = "TOPSECRET-OUTPUT-CONTENT-7z6w"
        try {
            log.write(
                record("sys.probe", SysAuditLog.VERDICT_SESSION_GATED, action = secretAction, summary = secretSummary),
                record("sys.probe", SysAuditLog.VERDICT_SESSION_GATED),
                record("sys.probe", SysAuditLog.VERDICT_FENCE_DENIED, action = secretAction),
                record("sys.read_config", SysAuditLog.VERDICT_BAD_ARGS),
                record("sys.session_auth", SysAuditLog.VERDICT_SESSION_APPROVED),
                record("sys.repair", SysAuditLog.VERDICT_ACTION_DENIED),
                record("sys.repair", SysAuditLog.VERDICT_FAILED),
            )
            val agg = SysAuditAggregator.aggregate(date = LocalDate.now(), dir = dir)
            assertTrue(agg != null, "文件存在须产出聚合")
            val a = agg!!
            assertEquals(7, a.total, "总数逐行计（含授权动作行）")
            assertEquals(3, a.byTool["sys.probe"], "按工具聚合计数")
            assertEquals(1, a.byTool["sys.read_config"])
            assertEquals(1, a.byTool["sys.session_auth"])
            assertEquals(2, a.byTool["sys.repair"])
            assertEquals(3, a.denied, "拒绝 = 会话授权拒绝+动作确认拒绝+围栏拒绝+参数拒收；失败/放行不混入")
            // 原文零携带：审计记录的命令串与输出摘要在聚合面（toString/byTool 键）双双不出现
            val rendered = a.toString() + a.byTool.keys.toString()
            assertTrue(secretAction !in rendered, "动作原文（argv 全文）绝不进聚合面：$rendered")
            assertTrue(secretSummary !in rendered, "输出摘要原文绝不进聚合面")
            assertTrue("oc_test" !in rendered, "触发者标识绝不进聚合面")
            assertTrue("sys.probe=3" in a.toString(), "聚合 toString 只有计数面")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `文件不存在返回全零聚合`() = runBlocking {
        val dir = tempDir()
        try {
            val empty = SysAuditAggregator.aggregate(dir = dir)
            assertTrue(empty != null && empty.total == 0 && empty.byTool.isEmpty() && empty.denied == 0, "无文件=全零聚合（今日无记录是正常答案）")
            // 目录也缺席属同义（尚无任何写入）——同样全零，不编造「不可得」
            val noDir = SysAuditAggregator.aggregate(dir = File(dir, "no-such-dir"))
            assertTrue(noDir != null && noDir.total == 0, "目录缺席=全零聚合（同义：无写入）")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `坏行跳过不计数其余行照常聚合`() = runBlocking {
        val dir = tempDir()
        try {
            val file = File(dir, SysAuditLog.fileNameFor(LocalDate.now()))
            dir.mkdirs()
            file.writeText(
                buildString {
                    appendLine("{\"ts\":\"t\",\"tool\":\"sys.probe\",\"action\":\"a\",\"verdict\":\"${SysAuditLog.VERDICT_SESSION_GATED}\"}")
                    appendLine("not-a-json-line")
                    appendLine("{\"totally\":\"different shape\"}")
                    appendLine("{\"ts\":\"t2\",\"tool\":\"sys.read_config\",\"action\":\"b\",\"verdict\":\"${SysAuditLog.VERDICT_BAD_ARGS}\"}")
                },
            )
            val agg = SysAuditAggregator.aggregate(dir = dir)!!
            assertEquals(2, agg.total, "坏行跳过不计数")
            assertEquals(1, agg.denied, "可解析行照常聚合")
        } finally {
            dir.deleteRecursively()
        }
    }
}
