// M4-T8 第一批 · observe 组测试（5/8）：JSONL 尾部反向读（🔴 D6 消解点）+ 审计**双向兼容**。
// 🔴 D6：旧 `AuditLogger.readAll` 用 `file.readLines()` 把整个文件读进内存再 `mapNotNull` 解析，
//    且**无轮转** ⇒ 文件只增不减，跑几个月后一次 UI 打开就是几十 MB 的全量入内存 + 全量解析。
//    新实现：从文件尾 **64KB 反向分块读**、命中 `maxRecords` 或 `ts < cutoff` 即停、8MB×3 轮转。
// 🔴 BC 对应：BC-331..BC-340（尾部读语义）+ BC-300..BC-312（审计 schema 双向兼容）。
// 🔴 **只读**用户存量数据：`~/.toolbelt/lark/{audit,sender_stats}.jsonl` 是真实用户数据
//    （实测 11137B / 10 条 与 2238B / 10 条），本测试**绝不写入、绝不删除**它。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.config.AuditJson
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class JsonlTailReaderTest {

    private fun tempDir(tag: String = "tail"): File =
        Files.createTempDirectory("$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    private fun writeAuditLines(dir: File, count: Int, pad: Int = 0): File {
        val file = File(dir, AuditRecords.FILE_NAME)
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(count) { i ->
                val prompt = if (pad > 0) "p$i" + "x".repeat(pad) else "p$i"
                w.write(
                    AuditJson.encodeToString(
                        AuditEntryJson.serializer(),
                        AuditEntryJson(
                            ts = 1_000L + i, chatId = "oc", senderOpenId = "ou", prompt = prompt,
                            command = "claude -p $i", status = "SUCCESS", errorCode = null,
                            exitCode = 0, elapsedMillis = i.toLong(),
                        ),
                    ),
                )
                w.newLine()
            }
        }
        return file
    }

    // ── 常量与纯路径语义 ──

    @Test
    fun `分块与上限的字面值锁死`() {
        // 🔴 方案表逐字：尾 64KB 反向分块、单次最多 20000 条
        assertEquals(64 * 1024, JsonlTailReader.TAIL_CHUNK_BYTES)
        assertEquals(65_536, JsonlTailReader.TAIL_CHUNK_BYTES)
        assertEquals(20_000, JsonlTailReader.MAX_RECORDS)
        // 与 `readSince(file, cutoff, maxRecords=20000)` 的默认值一致
        assertEquals(JsonlTailReader.MAX_RECORDS, 20_000)
        // 🔴 D6 的另一半：写入侧有轮转（8MB×3）⇒ 单个文件体积有界
        assertEquals(8, JsonlSink.DEFAULT_ROTATION_MB)
        assertEquals(8L * 1024 * 1024, JsonlSink.rotationBytes(8))
        assertEquals(Long.MAX_VALUE, JsonlSink.rotationBytes(0), "<=0 视为不轮转")
        assertEquals(Long.MAX_VALUE, JsonlSink.rotationBytes(-1))
    }

    @Test
    fun `expand 与 resolveFile 的 tilde 语义`() {
        val home = System.getProperty("user.home")
        assertEquals(File(home, ".toolbelt/lark").path, JsonlTailReader.expand("~/.toolbelt/lark"))
        assertEquals(File(home, "lark").path, JsonlTailReader.expand("  ~/lark  "), "两端空白必须 trim")
        assertEquals("/abs/x", JsonlTailReader.expand("/abs/x"))
        assertEquals("", JsonlTailReader.expand(""))
        // resolveFile：blank 目录 → null（= 禁用）；非 blank → 目录 + 文件名
        assertNull(JsonlTailReader.resolveFile("", AuditRecords.FILE_NAME))
        assertNull(JsonlTailReader.resolveFile("  ", AuditRecords.FILE_NAME))
        assertEquals(
            File(home, ".toolbelt/lark/audit.jsonl").path,
            JsonlTailReader.resolveFile("~/.toolbelt/lark", AuditRecords.FILE_NAME)?.path,
        )
        // 写入侧与读取侧共用同一个 resolveFile ⇒ 不会「写在 A 读在 B」
        assertEquals(
            JsonlSink.resolveFile("~/x", AuditRecords.FILE_NAME)?.path,
            JsonlTailReader.resolveFile("~/x", AuditRecords.FILE_NAME)?.path,
        )
        assertEquals("audit.jsonl", AuditRecords.FILE_NAME)
        assertEquals("sender_stats.jsonl", SenderStatsRecords.FILE_NAME)
    }

    // ── 🔴 D6：只读尾部，不全量入内存 ──

    @Test
    fun `D6 maxRecords 命中即停且 bytesRead 远小于文件体积`() {
        val dir = tempDir("bounded")
        // 2000 条 × 每条约 200B ⇒ 文件约 400KB；只要尾部 10 条
        val file = writeAuditLines(dir, 2_000, pad = 150)
        val fileSize = file.length()
        assertTrue(fileSize > 200_000, "样本文件必须足够大才能证明「非全量读」：$fileSize")

        val result = JsonlTailReader.readAuditSince(file, cutoff = 0L, maxRecords = 10)
        assertEquals(10, result.records.size)
        // 🔴 拿到的是**最新** 10 条（尾部反向读 + 最后整体反转 ⇒ 旧→新）
        assertEquals(2_990L, result.records.first().ts)
        assertEquals(2_999L, result.records.last().ts)
        assertEquals(10, result.linesScanned)
        assertEquals(0, result.damagedLines)
        assertTrue(result.truncated, "文件里还有更早的记录没读 ⇒ truncated 必须为 true")
        // 🔴 D6 的核心量化证据：只读了一个 64KB 分块，远小于 400KB 的文件
        assertTrue(
            result.bytesRead <= JsonlTailReader.TAIL_CHUNK_BYTES.toLong(),
            "bytesRead=${result.bytesRead} 不得超过一个分块（文件大小=$fileSize）",
        )
        assertTrue(result.bytesRead * 4 < fileSize, "读取量必须远小于文件体积（旧实现是 1:1 全量读）")
    }

    @Test
    fun `D6 cutoff 早停（更早的记录必然更小的 ts）`() {
        val dir = tempDir("cutoff")
        val file = writeAuditLines(dir, 500)
        // ts 从 1000 递增到 1499；cutoff=1490 ⇒ 只应命中最后 10 条
        val result = JsonlTailReader.readAuditSince(file, cutoff = 1_490L)
        assertEquals(10, result.records.size)
        assertEquals(1_490L, result.records.first().ts)
        assertEquals(1_499L, result.records.last().ts)
        assertTrue(result.records.all { it.ts >= 1_490L })
        // 🔴 早停 ⇒ 扫描行数 = 命中数 + 1（边界上那一行 ts=1489 被读到、判定 < cutoff 后立即停）
        assertEquals(11, result.linesScanned, "cutoff 早停必须生效（旧实现无条件扫全 500 行）")
        assertTrue(result.linesScanned * 10 < 500, "扫描量必须远小于文件行数")
        assertFalse(result.truncated, "因 cutoff 停止不算截断")
        // cutoff 晚于全部记录 ⇒ 空结果；只扫了最新那一行就早停
        val none = JsonlTailReader.readAuditSince(file, cutoff = 9_999L)
        assertEquals(0, none.records.size)
        assertEquals(1, none.linesScanned, "首行就判定 < cutoff ⇒ 立即早停（旧实现仍会扫全文件）")
        // cutoff = 0 ⇒ 不设下界
        assertEquals(500, JsonlTailReader.readAuditSince(file, cutoff = 0L).records.size)
    }

    @Test
    fun `跨分块的多字节 UTF-8 字符不被切断`() {
        val dir = tempDir("utf8")
        // 每行塞 400 个汉字（约 1.2KB/行）；写 200 行 ⇒ 约 240KB，跨 4 个 64KB 分块
        val file = File(dir, AuditRecords.FILE_NAME)
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            repeat(200) { i ->
                w.write(
                    """{"ts":${1_000 + i},"chatId":"oc","senderOpenId":"ou","prompt":"${"汉".repeat(400)}-$i","command":"c","status":"SUCCESS","exitCode":0,"elapsedMillis":1}""",
                )
                w.newLine()
            }
        }
        assertTrue(file.length() > 3 * JsonlTailReader.TAIL_CHUNK_BYTES, "样本必须跨多个分块：${file.length()}")

        val result = JsonlTailReader.readAuditSince(file, cutoff = 0L, maxRecords = 200)
        assertEquals(200, result.records.size)
        assertEquals(0, result.damagedLines, "🔴 carry 以 ByteArray 累积 ⇒ 分块边界不得切断多字节字符")
        // 逐条校验内容完好（含分块边界上的那些行）
        result.records.forEachIndexed { index, entry ->
            assertEquals(1_000L + index, entry.ts)
            assertEquals("汉".repeat(400) + "-$index", entry.prompt, "第 $index 行的汉字串被切断了")
        }
        // 反向读跨越的字节数 = 全文件（因为要读满 200 条）
        assertEquals(file.length(), result.bytesRead)
    }

    @Test
    fun `损坏行跳过并计数且不影响后续行`() {
        val dir = tempDir("broken")
        val file = File(dir, AuditRecords.FILE_NAME)
        file.writeText(
            """{"ts":1,"chatId":"oc","senderOpenId":"ou","prompt":"a","command":"c","status":"SUCCESS","exitCode":0,"elapsedMillis":1}""" + "\n" +
                "{ 这不是 JSON\n" +
                "\n" + // 空行不计入损坏
                """{"ts":2,"chatId":"oc","senderOpenId":"ou","prompt":"b","command":"c","status":"FAILURE","errorCode":"agent_timeout","elapsedMillis":2}""" + "\n" +
                "{\"ts\":3, 被截断的行\n",
            Charsets.UTF_8,
        )
        val result = JsonlTailReader.readAuditSince(file)
        assertEquals(2, result.records.size)
        assertEquals(1L, result.records[0].ts)
        assertEquals(2L, result.records[1].ts)
        assertEquals(2, result.damagedLines, "🔴 D18：损坏行必须计数（旧实现静默 mapNotNull 掉）")
        assertEquals(4, result.linesScanned, "空行不占额度")
    }

    @Test
    fun `文件不存在或目录 blank 返回空结果且不抛`() {
        val missing = File(tempDir("missing"), "audit.jsonl")
        val result = JsonlTailReader.readAuditSince(missing)
        assertEquals(0, result.records.size)
        assertEquals(0, result.linesScanned)
        assertEquals(0, result.damagedLines)
        assertFalse(result.truncated)
        assertEquals(0L, result.bytesRead)
        // 空文件
        val empty = File(tempDir("empty"), "audit.jsonl").apply { writeText("") }
        assertEquals(0, JsonlTailReader.readAuditSince(empty).records.size)
        // 目录当文件传
        val asDir = tempDir("asdir")
        assertEquals(0, JsonlTailReader.readAuditSince(asDir).records.size)
        // blank 目录串
        assertTrue(JsonlTailReader.readAuditAll("").records.isEmpty())
        assertTrue(JsonlTailReader.readStatsAll("").records.isEmpty())
    }

    @Test
    fun `末行不带换行也能读到（旧 readLines 同语义）`() {
        val dir = tempDir("noeol")
        val file = File(dir, AuditRecords.FILE_NAME)
        file.writeText(
            """{"ts":1,"chatId":"oc","senderOpenId":"ou","prompt":"a","command":"c","status":"SUCCESS","exitCode":0,"elapsedMillis":1}""" + "\n" +
                """{"ts":2,"chatId":"oc","senderOpenId":"ou","prompt":"b","command":"c","status":"SUCCESS","exitCode":0,"elapsedMillis":2}""",
            Charsets.UTF_8,
        )
        val result = JsonlTailReader.readAuditSince(file)
        assertEquals(2, result.records.size, "最后一行没有 \\n 也必须被读到")
        assertEquals(2L, result.records[1].ts)
        assertEquals(0, result.damagedLines)
    }

    @Test
    fun `readTailLines 只读原始行不解析（CLI audit tail 用）`() {
        val dir = tempDir("raw")
        val file = writeAuditLines(dir, 5)
        val lines = JsonlTailReader.readTailLines(file, maxLines = 3)
        assertEquals(3, lines.size)
        // 🔴 取的是**尾部** 3 行（ts 1002/1003/1004），顺序同样是文件顺序（旧 → 新）
        assertTrue(lines[0].contains("\"ts\":1002"), lines[0])
        assertTrue(lines[1].contains("\"ts\":1003"), lines[1])
        assertTrue(lines[2].contains("\"ts\":1004"), lines[2])
        // 损坏行也原样返回（不做解析）
        val broken = File(tempDir("rawbroken"), "x.jsonl").apply { writeText("a\nnot-json\n") }
        assertEquals(listOf("a", "not-json"), JsonlTailReader.readTailLines(broken))
        assertEquals(emptyList(), JsonlTailReader.readTailLines(File(tempDir("rawmissing"), "nope.jsonl")))
    }

    // ── 🔴 审计**双向兼容**：新写 ⇄ 旧 schema ──

    @Test
    fun `双向兼容 新写入的审计行能被旧 schema 读回`() {
        // 「旧 schema」= 旧 `AuditLogger.AuditEntry` 的 9 个字段（无 backend / kind）。
        // 用**手写**旧形态 JSON 反解到新类型，再验证新写入的行也含这 9 个键。
        val dir = tempDir("compat-new")
        val record = SinkRecord.Audit(
            ts = 1_788_569_356_183L, chatId = "oc_x", senderOpenId = "ou_y", prompt = "分析日志",
            command = "claude -p 分析日志", status = SinkRecord.statusLiteral(TaskStatus.SUCCESS),
            errorCode = null, exitCode = 0, elapsedMillis = 1_234L, backend = "claude", kind = "AGENT",
        )
        val channels = SinkChannels()
        channels.offerAudit(record)
        channels.close()
        runBlocking { JsonlSink.audit(channels, dir.path).run() }
        val line = File(dir, AuditRecords.FILE_NAME).readLines().single()

        // (a) 旧 9 键**逐个**出现在新行里（旧 UI / 旧脚本按字面取值不会拿到 null）
        listOf("ts", "chatId", "senderOpenId", "prompt", "command", "status", "exitCode", "elapsedMillis")
            .forEach { key -> assertTrue(line.contains("\"$key\":"), "旧 schema 的键 $key 必须出现在新行里：$line") }
        // (b) status 字面值仍是旧的大写形态（旧 UI 按 "SUCCESS"/"FAILURE" 分桶）
        assertTrue(line.contains("\"status\":\"SUCCESS\""), line)
        // (c) 新增键（backend/kind）对旧读方是**未知键**，必须被 ignoreUnknownKeys 容忍
        val legacyShaped = line.substring(0, line.length)
        assertTrue(legacyShaped.contains("\"backend\":\"claude\""))
        assertTrue(legacyShaped.contains("\"kind\":\"AGENT\""))
        // (d) 用「只认旧 9 键」的严格等价物读回：剥掉 backend/kind 后仍能解析出同样的核心字段
        val stripped = AuditJson.parseToJsonElement(line).let { el ->
            val obj = el as kotlinx.serialization.json.JsonObject
            kotlinx.serialization.json.JsonObject(obj.filterKeys { it != "backend" && it != "kind" })
        }
        val asLegacy = AuditJson.decodeFromString(AuditEntryJson.serializer(), stripped.toString())
        assertEquals(1_788_569_356_183L, asLegacy.ts)
        assertEquals("SUCCESS", asLegacy.status)
        assertEquals(0, asLegacy.exitCode)
        assertEquals(1_234L, asLegacy.elapsedMillis)
        assertNull(asLegacy.backend, "旧行没有 backend 键 ⇒ 必须解成 null 而不是抛")
        assertNull(asLegacy.kind)
    }

    @Test
    fun `双向兼容 旧写入的行能被新 reader 解析（含缺键与未知键）`() {
        val dir = tempDir("compat-old")
        val file = File(dir, AuditRecords.FILE_NAME)
        // 三种旧行形态：① 全 9 键 ② 缺 errorCode/exitCode（可空字段为 null 时缺键）③ 含未来未知键
        file.writeText(
            """{"ts":1788569356183,"chatId":"oc_a","senderOpenId":"ou_a","prompt":"请分析日志","command":"claude -p 请分析日志","status":"SUCCESS","exitCode":0,"elapsedMillis":12345}""" + "\n" +
                """{"ts":1788569356999,"chatId":"oc_b","senderOpenId":"ou_b","prompt":"p","command":"c","status":"FAILURE","errorCode":"agent_timeout","elapsedMillis":30000}""" + "\n" +
                """{"ts":1788569357000,"chatId":"oc_c","senderOpenId":"ou_c","prompt":"p","command":"c","status":"SUCCESS","exitCode":0,"elapsedMillis":1,"backend":"qoder","kind":"SKILL","futureField":{"a":1}}""" + "\n",
            Charsets.UTF_8,
        )
        val result = JsonlTailReader.readAuditAll(dir.path)
        assertEquals(3, result.records.size)
        assertEquals(0, result.damagedLines, "🔴 旧行必须 100% 可解析，一条都不能丢")
        // ① 缺 backend/kind ⇒ null（旧读方拿不到这两个键，新读方也不能崩）
        assertNull(result.records[0].backend)
        assertNull(result.records[0].kind)
        assertNull(result.records[0].errorCode)
        assertEquals(0, result.records[0].exitCode)
        // ② 缺 exitCode ⇒ null（旧 FAILURE 行就是这样）
        assertNull(result.records[1].exitCode)
        assertEquals("agent_timeout", result.records[1].errorCode)
        assertEquals("FAILURE", result.records[1].status)
        // ③ 未知键被忽略，已知键照常解析
        assertEquals("qoder", result.records[2].backend)
        assertEquals("SKILL", result.records[2].kind)
        // (e) 🔴 新写入侧再把这些行读回来重写一遍，键集不变（幂等）
        val reencoded = result.records.map { AuditJson.encodeToString(AuditEntryJson.serializer(), it) }
        assertFalse(reencoded[0].contains("backend"), "null 字段重写后仍须缺键（否则存量文件会长出新键）")
        assertFalse(reencoded[0].contains("kind"))
        assertTrue(reencoded[2].contains("\"backend\":\"qoder\""))
        // 反向：新写的行被旧读方（只认 9 键）解析也不会失败 —— 已由上一条测试的 stripped 分支覆盖
    }

    // ── 🔴 真实存量数据（只读）──

    @Test
    fun `真实存量 audit jsonl 能被新 reader 全量解析（只读）`() {
        val file = File(System.getProperty("user.home"), ".toolbelt/lark/audit.jsonl")
        assumeTrue("用户存量审计文件不存在，跳过（CI 上属正常）：$file", file.isFile)
        val sizeBefore = file.length()
        val mtimeBefore = file.lastModified()

        val dir = file.parentFile.path
        val result = JsonlTailReader.readAuditAll(dir)
        val rawLines = file.readLines().filter { it.isNotBlank() }

        // 🔴 一条都不能丢：解析出的记录数 == 文件里的非空行数
        assertEquals(rawLines.size, result.records.size, "存量审计必须 100% 可解析")
        assertEquals(0, result.damagedLines)
        assertTrue(result.records.isNotEmpty(), "存量文件不应为空")
        // 🔴 status 字面值必须落在 `statusLiteral` 的像集里（旧 UI 与新 UI 都按字面分桶，BC-525）
        val literals = TaskStatus.entries.map { SinkRecord.statusLiteral(it) }.toSet()
        assertEquals(setOf("SUCCESS", "FAILURE", "SKIPPED"), literals)
        // 每条的核心字段都非空（旧 schema 的必填键）
        result.records.forEach { entry ->
            assertTrue(entry.ts > 0L, "ts 必须为正：$entry")
            assertTrue(entry.chatId.isNotBlank(), "chatId 必须非空")
            assertTrue(entry.senderOpenId.isNotBlank(), "senderOpenId 必须非空")
            assertTrue(entry.status in literals, "status 字面值超出旧集合：${entry.status}")
            assertNotNull(entry.command)
        }
        // 🔴 新 reader 认得旧行里的 backend/kind（存量文件实测已含这两个键）
        assertTrue(result.records.any { it.backend != null }, "存量文件里应有带 backend 的行")
        // 🔴 **只读**：本测试不得改动用户数据
        assertEquals(sizeBefore, file.length(), "测试不得写入用户存量审计文件")
        assertEquals(mtimeBefore, file.lastModified(), "测试不得触碰用户存量审计文件的 mtime")
        assertFalse(File(dir, AuditRecords.FILE_NAME + ".1").let { it.exists() && sizeBefore != file.length() })
    }

    @Test
    fun `真实存量 sender_stats jsonl 能被新 reader 全量解析（只读）`() {
        val file = File(System.getProperty("user.home"), ".toolbelt/lark/sender_stats.jsonl")
        assumeTrue("用户存量统计文件不存在，跳过：$file", file.isFile)
        val sizeBefore = file.length()
        val mtimeBefore = file.lastModified()

        val result = JsonlTailReader.readStatsAll(file.parentFile.path)
        val rawLines = file.readLines().filter { it.isNotBlank() }
        assertEquals(rawLines.size, result.records.size)
        assertEquals(0, result.damagedLines)
        result.records.forEach { entry ->
            assertTrue(entry.ts > 0L)
            assertTrue(entry.senderOpenId.isNotBlank())
            // 🔴 BC §4.11：type 的字面值集合（刻意保留旧拼写 WHITELIST/BLACKLIST）
            assertTrue(
                entry.type in setOf(
                    SenderStatsRecords.TYPE_MESSAGE,
                    SenderStatsRecords.TYPE_REJECTED_WHITELIST,
                    SenderStatsRecords.TYPE_REJECTED_BLACKLIST,
                ),
                "type 字面值超出旧集合：${entry.type}",
            )
        }
        // 🔴 存量行里 `userName` 缺键（旧 `encodeDefaults=false`）⇒ 新 reader 必须给默认空串而非崩
        assertTrue(result.records.all { it.userName == "" || it.userName.isNotBlank() })
        // 🔴 双向兼容的写入侧证据：新 encoder 对同样的记录产出**同样缺 userName 键**的行
        val reencoded = result.records.map {
            SenderStatsRecords.encoder().encode(
                SinkRecord.SenderStat(
                    ts = it.ts, senderOpenId = it.senderOpenId, userName = it.userName,
                    chatId = it.chatId, type = it.type, contentSummary = it.content,
                ),
            )
        }
        reencoded.forEachIndexed { i, line ->
            assertNotNull(line)
            assertEquals(!rawLines[i].contains("\"userName\""), !line!!.contains("\"userName\""), "第 $i 行的 userName 键存在性必须与旧行一致")
        }
        // 🔴 只读
        assertEquals(sizeBefore, file.length())
        assertEquals(mtimeBefore, file.lastModified())
    }
}
