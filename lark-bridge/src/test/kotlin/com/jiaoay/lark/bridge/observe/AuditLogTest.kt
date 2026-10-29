// M4-T8 第一批 · observe 组测试（1/8）：审计 JSONL 落盘（schema + D4 常驻句柄 + 批量 flush + 轮转）。
// 🔴 移植自旧 `audit/AuditLoggerTest.kt`（176 行，11 个测试）——**11 条逐条移植**。
// 🔴 BC 对应：BC-300..BC-312（审计 schema 与容错）+ BC-306/BC-308（可空字段缺键）
//    + BC-467..BC-471（Idle 零资源）+ NF-09（丢弃可观测，另见 `JsonlSinkDropCountTest`）。
// 🔴 CDS §16.7：必须用独立 [AuditJson]（`encodeDefaults=false; prettyPrint=false`），严禁复用 `BridgeJson`。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.config.AuditJson
import com.jiaoay.lark.bridge.config.BridgeJson
import com.jiaoay.lark.bridge.pipeline.TaskKind
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AuditLogTest {

    private fun tempDir(tag: String = "audit"): File =
        Files.createTempDirectory("$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    /** 旧测试 `entry(...)` 的等价构造（🔴 字段顺序/取值逐字对齐旧 `AuditEntry`）。 */
    private fun entry(ts: Long = 1_000L, status: String = "SUCCESS") = AuditEntryJson(
        ts = ts,
        chatId = "oc_test",
        senderOpenId = "ou_user",
        prompt = "分析崩溃日志",
        command = "claude -p 分析崩溃日志",
        status = status,
        errorCode = if (status == "FAILURE") "agent_timeout" else null,
        exitCode = if (status == "SUCCESS") 0 else null,
        elapsedMillis = 1_234L,
    )

    private fun record(ts: Long = 1_000L, status: TaskStatus = TaskStatus.SUCCESS) = SinkRecord.Audit(
        ts = ts,
        chatId = "oc_test",
        senderOpenId = "ou_user",
        prompt = "分析崩溃日志",
        command = "claude -p 分析崩溃日志",
        status = SinkRecord.statusLiteral(status),
        errorCode = if (status == TaskStatus.FAILURE) "agent_timeout" else null,
        exitCode = if (status == TaskStatus.SUCCESS) 0 else null,
        elapsedMillis = 1_234L,
        backend = null,
        kind = null,
    )

    /** 跑一遍完整链路：offer → close → sink.run()（模拟 T9 的 `auditSinkScope.launch { run() }` + `stop()`）。 */
    private fun runSink(dir: String, records: List<SinkRecord.Audit>, rotationMb: Int = 8): JsonlSink = runBlocking {
        val channels = SinkChannels()
        records.forEach { channels.offerAudit(it) }
        channels.close()
        val sink = JsonlSink.audit(channels, dir, rotationMb = rotationMb)
        sink.run()
        sink
    }

    private fun lines(dir: String): List<String> =
        File(dir, SinkRecord.AUDIT_FILE_NAME).readLines().filter { it.isNotBlank() }

    // ── 旧 AuditLoggerTest 11 条逐条移植 ──

    @Test
    fun `追加写入 JSONL 每行一个对象`() {
        val dir = tempDir()
        runSink(dir.path, listOf(record(ts = 1L), record(ts = 2L, status = TaskStatus.FAILURE)))

        val all = lines(dir.path)
        assertEquals(2, all.size)
        val first = AuditJson.decodeFromString(AuditEntryJson.serializer(), all[0])
        assertEquals(1L, first.ts)
        assertEquals("SUCCESS", first.status)
        val second = AuditJson.decodeFromString(AuditEntryJson.serializer(), all[1])
        assertEquals("agent_timeout", second.errorCode)
        // 🔴 单行紧凑（CDS §16.7）：不得出现 prettyPrint 的换行/缩进
        assertTrue(all.all { !it.contains("\n") && !it.contains("  ") }, "审计行必须单行紧凑：$all")
    }

    @Test
    fun `目录不存在自动创建`() {
        val dir = File(tempDir(), "nested/deeper")
        runSink(dir.path, listOf(record()))
        assertTrue(File(dir, SinkRecord.AUDIT_FILE_NAME).exists())
    }

    @Test
    fun `禁用态（空目录）不写入不抛异常`() {
        val sink = runSink("", listOf(record()))
        assertFalse(sink.enabled, "目录 blank ⇒ 禁用（旧 AuditLogger(\"\") 同语义）")
        // 禁用态下 writeLine 返回 false ⇒ 记录计入 writeDropped，但**不抛**（旁路语义）
        assertEquals(0L, sink.writtenTotal)
        assertEquals(1L, sink.writeDropped)
        assertTrue(sink.finished)
    }

    @Test
    fun `写入失败仅警告不影响主链路`() {
        // 目录路径是一个文件 → mkdirs/FileOutputStream 必然失败
        val fileAsDir = File(tempDir(), "not-a-dir").apply { createNewFile(); deleteOnExit() }
        val warnings = mutableListOf<String>()
        val channels = SinkChannels()
        channels.offerAudit(record())
        channels.close()
        val sink = runBlocking {
            val s = JsonlSink.audit(
                channels,
                fileAsDir.path,
                log = object : com.jiaoay.lark.bridge.BridgeLog {
                    override fun debug(message: String) = Unit
                    override fun info(message: String) = Unit
                    override fun warn(message: String, error: Throwable?) {
                        warnings.add(message)
                    }
                    override fun error(message: String, error: Throwable?) = Unit
                },
            )
            s.run() // 🔴 不应抛异常
            s
        }
        assertTrue(warnings.isNotEmpty(), "IO 失败必须 warn（旧测试同断言）")
        assertEquals(1L, sink.writeFailures)
        assertEquals(0L, sink.writtenTotal)
        assertTrue(sink.finished)
    }

    @Test
    fun `命令摘要截断与换行压平`() {
        val summary = AuditRecords.summarizeCommand(listOf("claude", "-p", "a\nb", "x".repeat(100)))
        assertFalse(summary.contains("\n"))
        assertTrue(summary.split(" ").all { it.length <= AuditRecords.CMD_SUMMARY_CHARS })
        // 🔴 BC 校正：`CMD_SUMMARY_CHARS` 必须是 **60**（按 200 实现会让命令摘要超长 4 倍）
        assertEquals(60, AuditRecords.CMD_SUMMARY_CHARS)
        assertEquals(60, SinkRecord.CMD_SUMMARY_CHARS)
        assertEquals(200, AuditRecords.PROMPT_SUMMARY_CHARS)
        // 与旧实现逐字等价：空格连接 + 每段截断到 60 + 换行压平为空格
        assertEquals(
            "claude -p a b " + "x".repeat(60),
            summary,
        )
    }

    @Test
    fun `prompt 摘要截断`() {
        val long = "x".repeat(500)
        val summarized = AuditRecords.summarizePrompt(long)
        assertEquals(200, summarized.length)
        // 🔴 审计侧截断**不带省略号**（旧 `StringTruncation.truncate(it, 200, ellipsis = "")`；
        //    实测存量 audit.jsonl 的字段结尾无 `…`）
        assertEquals(entry().copy(prompt = long.take(AuditRecords.PROMPT_SUMMARY_CHARS)).prompt.length, 200)
    }

    @Test
    fun `带 backend 的记录 encode 后 decode 保持 backend`() {
        val dir = tempDir("backend")
        runSink(dir.path, listOf(record().copy(backend = "claude")))
        val line = lines(dir.path).single()
        assertEquals("claude", AuditJson.decodeFromString(AuditEntryJson.serializer(), line).backend)
    }

    @Test
    fun `旧格式 JSON 行（无 backend 键）decode 为 null`() {
        // 模拟历史记录：backend 字段尚未存在时写入的 JSON 行（无 backend 键）
        val legacyLine = """{"ts":1000,"chatId":"oc_test","senderOpenId":"ou_user","prompt":"p","command":"claude -p p","status":"SUCCESS","exitCode":0,"elapsedMillis":1234}"""
        val decoded = AuditJson.decodeFromString(AuditEntryJson.serializer(), legacyLine)
        assertNull(decoded.backend)
        assertNull(decoded.kind)
        // 🔴 BC-306：`AuditJson` 的 `explicitNulls=false` + `encodeDefaults=false` ⇒ null 字段**缺键**
        val encoded = AuditJson.encodeToString(AuditEntryJson.serializer(), decoded)
        assertFalse(encoded.contains("backend"), "null 字段必须缺键（存量双向兼容）：$encoded")
        assertFalse(encoded.contains("kind"), "null 字段必须缺键：$encoded")
        assertFalse(encoded.contains("errorCode"))
        assertTrue(encoded.contains("\"exitCode\":0"))
    }

    @Test
    fun `带 kind 的记录 encode 后 decode 保持 kind`() {
        val dir = tempDir("kind")
        runSink(dir.path, listOf(record().copy(kind = "SKILL")))
        val line = lines(dir.path).single()
        assertEquals("SKILL", AuditJson.decodeFromString(AuditEntryJson.serializer(), line).kind)
    }

    @Test
    fun `旧格式 JSON 行（无 kind 键）decode 为 null`() {
        val legacyLine = """{"ts":1000,"chatId":"oc_test","senderOpenId":"ou_user","prompt":"p","command":"claude -p p","status":"SUCCESS","exitCode":0,"elapsedMillis":1234,"backend":"claude"}"""
        val decoded = AuditJson.decodeFromString(AuditEntryJson.serializer(), legacyLine)
        assertNull(decoded.kind)
        assertEquals("claude", decoded.backend)
    }

    @Test
    fun `readAll 展开 tilde 并按行解析`() {
        // 🔴 旧测试会往**真实用户家目录**写临时文件（旧 :141-156）；新测试拆为两半，
        //    既保留「写入侧与读取侧都走 ~ 展开」的回归语义（缺 expandTilde 会读空），又不污染家目录。
        // (a) ~ 展开是纯路径语义（零 IO）：写入侧 resolveFile 与读取侧 expand 必须得到同一路径
        val home = System.getProperty("user.home")
        assertEquals(File(home, "lark").path, JsonlTailReader.expand("~/lark"))
        assertEquals(File(home, "lark").path, JsonlTailReader.expand("  ~/lark  "))
        assertEquals(
            File(home, "lark/audit.jsonl").path,
            JsonlTailReader.resolveFile("~/lark", AuditRecords.FILE_NAME)?.path,
        )
        assertFalse(JsonlTailReader.expand("/abs/lark").startsWith("~"), "非 ~ 前缀不得被改写")
        assertEquals("/abs/lark", JsonlTailReader.expand("/abs/lark"))

        // (b) 写入侧 + 读取侧走**同一**目录串的端到端回读（临时目录）
        val dir = tempDir("readall")
        runSink(dir.path, listOf(record(ts = 1L), record(ts = 2L, status = TaskStatus.FAILURE)))
        val all = JsonlTailReader.readAuditAll(dir.path).records
        assertEquals(2, all.size)
        // 🔴 D6 尾部反向读，但 `readSince` 最后整体反转 ⇒ 对外仍是**旧 → 新**
        //    （与旧 `AuditLogger.readAll` 的顺序一致，宿主聚合展示不必改排序逻辑）
        assertEquals(1L, all[0].ts)
        assertEquals("SUCCESS", all[0].status)
        assertEquals(2L, all[1].ts)
        assertEquals("FAILURE", all[1].status)
    }

    @Test
    fun `readAll 含未知键的 JSON 行不丢弃`() {
        val dir = tempDir("unknown")
        File(dir, AuditRecords.FILE_NAME).writeText(
            """{"ts":1000,"chatId":"oc_test","senderOpenId":"ou_user","prompt":"p","command":"claude -p p","status":"SUCCESS","elapsedMillis":1234,"futureField":1}""" + "\n",
        )
        val all = JsonlTailReader.readAuditAll(dir.path).records
        assertEquals(1, all.size)
        assertEquals(1000L, all[0].ts)
        assertEquals("oc_test", all[0].chatId)
        assertEquals("SUCCESS", all[0].status)
    }

    // ── 新增：🔴 CDS §16.7 严禁复用 BridgeJson ──

    @Test
    fun `AuditJson 与 BridgeJson 严格分离`() {
        val e = entry()
        val auditLine = AuditJson.encodeToString(AuditEntryJson.serializer(), e)
        // 单行紧凑：无换行、无缩进
        assertFalse(auditLine.contains("\n"))
        assertFalse(auditLine.contains("\n  "))
        // 🔴 null 字段缺键（BridgeJson 的 encodeDefaults=true 会把默认值全写出来）
        assertFalse(auditLine.contains("\"backend\""))
        assertFalse(auditLine.contains("\"kind\""))
        assertFalse(auditLine.contains("\"errorCode\""))
        // 🔴 对照：BridgeJson 的 prettyPrint=true ⇒ 同一对象必然多行
        val bridgeLine = BridgeJson.encodeToString(AuditEntryJson.serializer(), e)
        assertTrue(bridgeLine.contains("\n"), "BridgeJson 是 prettyPrint，绝不可用于 jsonl：$bridgeLine")
        // 🔴 classDiscriminator 不得出现在审计行里（BridgeJson 的 "kind" 判别字段会撞审计的 kind 字段名）
        assertFalse(auditLine.startsWith("{\"kind\":\""))
        assertEquals(AuditRecords.FILE_NAME, SinkRecord.AUDIT_FILE_NAME)
        assertEquals("audit.jsonl", AuditRecords.FILE_NAME)
    }

    // ── 新增：statusLiteral / TaskKind 字面值（BC §4.11 + BC-525） ──

    @Test
    fun `statusLiteral 与 TaskKind 字面值锁死`() {
        assertEquals("SUCCESS", SinkRecord.statusLiteral(TaskStatus.SUCCESS))
        assertEquals("FAILURE", SinkRecord.statusLiteral(TaskStatus.FAILURE))
        assertEquals("SKIPPED", SinkRecord.statusLiteral(TaskStatus.SKIPPED))
        // 🔴 审计落的是 `TaskKind.name`（大写枚举名），**不是** `TaskKind.label`（带 emoji 的中文）
        assertEquals(listOf("COMMAND", "SKILL", "AGENT"), TaskKind.entries.map { it.name })
        assertTrue(TaskKind.entries.any { it.name == "SKILL" }, "🔴 TaskKind 必须含 SKILL（存量 audit.jsonl 有 \"kind\":\"SKILL\"）")
        assertEquals("⚡ 指令", TaskKind.COMMAND.label)
        assertEquals("🧩 快捷指令", TaskKind.SKILL.label)
        assertEquals("🤖 Agent", TaskKind.AGENT.label)
    }

    // ── 新增：🔴 D4 消解证据（常驻句柄 + 批量 flush + 轮转） ──

    @Test
    fun `D4 常驻句柄而非每条 open-write-close`() {
        val dir = tempDir("d4")
        val file = File(dir, AuditRecords.FILE_NAME)
        val writer = JsonlRotatingWriter(file)
        try {
            // 写 5 行不 flush：文件此刻**尚未**有内容（证明缓冲生效 = 句柄常驻，而非每条落盘）
            repeat(5) { assertTrue(writer.writeLine("""{"n":$it}""")) }
            assertEquals(0L, writer.flushCount)
            assertEquals(0L, file.length(), "未 flush 前不应落盘（旧实现每条 appendText 会立刻落盘）")
            assertTrue(writer.flush())
            assertEquals(1L, writer.flushCount)
            assertEquals(5, file.readLines().filter { it.isNotBlank() }.size)
            // 🔴 再写 5 行：句柄未重开（flushCount 只由显式 flush 驱动）
            repeat(5) { assertTrue(writer.writeLine("""{"n":$it}""")) }
            assertEquals(1L, writer.flushCount, "writeLine 不得隐式 flush（那等于退回 D4 的每条落盘）")
            writer.flush()
            assertEquals(2L, writer.flushCount)
            assertEquals(10, file.readLines().filter { it.isNotBlank() }.size)
        } finally {
            writer.close()
        }
        // close 幂等
        writer.close()
        writer.close()
    }

    @Test
    fun `D4 批量 flush 阈值为 64 条与 500ms`() {
        assertEquals(64, JsonlSink.FLUSH_BATCH_SIZE)
        assertEquals(500L, JsonlSink.FLUSH_INTERVAL_MILLIS)
        val dir = tempDir("batch")
        // flushBatchSize=2 验证「满 N 条即 flush」的阈值语义（等价放大 64 的行为）
        val channels = SinkChannels()
        repeat(5) { channels.offerAudit(record(ts = it.toLong())) }
        channels.close()
        val sink = runBlocking {
            val s = JsonlSink(
                writer = JsonlRotatingWriter(File(dir, AuditRecords.FILE_NAME)),
                encoder = AuditRecords.encoder(),
                receive = { channels.receiveAudit() },
                flushBatchSize = 2,
                where = JsonlSink.WHERE_AUDIT,
            )
            s.run()
            s
        }
        assertEquals(5L, sink.writtenTotal)
        // 5 条 / 阈值 2 ⇒ 批量 flush 2 次（第 2、第 4 条）+ 窗口到期 1 次（第 5 条）= 3
        assertEquals(3L, sink.flushCount, "批量 flush 次数应符合「满 2 条 flush 一次」：${sink.flushCount}")
        assertEquals(5, lines(dir.path).size)
        assertTrue(sink.finished)
    }

    @Test
    fun `BC-467 Idle 态零唤醒（无待写数据时不定时 flush）`() {
        val dir = tempDir("idle")
        runBlocking {
            val channels = SinkChannels()
            val sink = JsonlSink(
                writer = JsonlRotatingWriter(File(dir, AuditRecords.FILE_NAME)),
                encoder = AuditRecords.encoder(),
                receive = { channels.receiveAudit() },
                flushIntervalMillis = 20L, // 🔴 故意调到 20ms：若 Idle 态带超时轮询，300ms 内必产生十几次 flush
            )
            val job = launch { sink.run() }
            delay(300)
            // BC-467..471：Idle 态零资源 ⇒ 无记录时既不开句柄也不 flush
            assertEquals(0L, sink.flushCount, "Idle 态不得有定时唤醒（BC-467..471）")
            assertEquals(0L, sink.writtenTotal)
            assertFalse(File(dir, AuditRecords.FILE_NAME).exists(), "Idle 态不得创建文件（零磁盘副作用）")
            channels.close()
            job.join()
            assertTrue(sink.finished)
            assertEquals(0L, sink.flushCount)
        }
    }

    @Test
    fun `8MB 与 3 文件的轮转默认值锁死`() {
        assertEquals(8L * 1024 * 1024, JsonlRotatingWriter.DEFAULT_ROTATION_BYTES)
        assertEquals(3, JsonlRotatingWriter.DEFAULT_KEEP_FILES)
        assertEquals(8, JsonlSink.DEFAULT_ROTATION_MB)
        assertEquals(8L * 1024 * 1024, JsonlSink.rotationBytes(8))
        // `<= 0` 视为不轮转
        assertEquals(Long.MAX_VALUE, JsonlSink.rotationBytes(0))
        assertEquals(Long.MAX_VALUE, JsonlSink.rotationBytes(-1))
        assertEquals(File("/x/y", "audit.jsonl.1"), JsonlRotatingWriter.rotationFile(File("/x/y", "audit.jsonl"), 1))
    }

    @Test
    fun `体积轮转按 keepFiles 挪动并删最旧`() {
        val dir = tempDir("rotate")
        val file = File(dir, AuditRecords.FILE_NAME)
        val writer = JsonlRotatingWriter(file, rotationBytes = 100L, keepFiles = 3)
        try {
            // 每轮 51 字节；写满 2 轮（102B）后下一次 writeLine 触发轮转（详见 `JsonlRotatingWriter.rotate`）
            repeat(10) { i ->
                assertTrue(writer.writeLine("x".repeat(49) + i)) // 50 字符 + \n = 51 字节
                writer.flush()
            }
            assertEquals(3L, writer.rotationCount)
        } finally {
            writer.close()
        }
        val base = File(dir, AuditRecords.FILE_NAME)
        val r1 = JsonlRotatingWriter.rotationFile(base, 1)
        val r2 = JsonlRotatingWriter.rotationFile(base, 2)
        val r3 = JsonlRotatingWriter.rotationFile(base, 3)
        assertTrue(base.exists())
        assertTrue(r1.exists(), "第 1 个备份应存在")
        assertTrue(r2.exists(), "第 2 个备份应存在")
        assertFalse(r3.exists(), "keepFiles=3 ⇒ 只保留 base/.1/.2，第 4 个不得存在")
        // 🔴 磁盘上限有界：三个文件之和 <= 3 * (阈值 + 单行)
        val total = listOf(base, r1, r2).sumOf { it.length() }
        assertTrue(total < 3 * (100L + 60L), "轮转后总占用必须有界：$total")
        // 🔴 keepFiles=3 的精确语义：第 1 轮转出的那批（i0..i2）在**第 3 次**轮转时被删除，
        //    存活的是 .2={i3,i4,i5}、.1={i6,i7,i8}、base={i9}（共 7 行）。
        //    轮转前 rotate() 会先 flush+close ⇒ 在途缓冲不丢（每行内容完整）。
        val inBase = base.readLines().filter { it.isNotBlank() }
        val inR1 = r1.readLines().filter { it.isNotBlank() }
        val inR2 = r2.readLines().filter { it.isNotBlank() }
        assertEquals(1, inBase.size, "base 只剩最后一轮未达阈值的那行：$inBase")
        assertEquals(3, inR1.size, ".1 = 上一次轮转出的批：$inR1")
        assertEquals(3, inR2.size, ".2 = 上上次轮转出的批：$inR2")
        assertEquals(7, inBase.size + inR1.size + inR2.size)
        // 最旧的一批（i0..i2）必须已被删除，不得残留在任何存活文件里
        val survived = (inBase + inR1 + inR2).map { it.last() }
        assertFalse(survived.contains('0'), "最旧批已删：$survived")
        assertFalse(survived.contains('1'))
        assertFalse(survived.contains('2'))
        assertEquals(listOf('9'), inBase.map { it.last() })
        assertEquals(listOf('6', '7', '8'), inR1.map { it.last() })
        assertEquals(listOf('3', '4', '5'), inR2.map { it.last() })
    }

    @Test
    fun `轮转判定用真实 UTF-8 字节数而非字符数`() {
        val dir = tempDir("utf8")
        val file = File(dir, AuditRecords.FILE_NAME)
        val writer = JsonlRotatingWriter(file, rotationBytes = 100L, keepFiles = 3)
        try {
            // 中文 3 字节/字：40 个中文字 = 120 字节 > 100 ⇒ flush 后下一次 writeLine 必轮转
            assertTrue(writer.writeLine("中".repeat(40)))
            writer.flush()
            assertTrue(writer.bytesSinceRotation >= 120L, "字节计数必须按 UTF-8 实字节：${writer.bytesSinceRotation}")
            assertTrue(writer.writeLine("中".repeat(40)))
            assertEquals(1L, writer.rotationCount)
        } finally {
            writer.close()
        }
    }

    @Test
    fun `续写时把文件既有字节计入轮转判定`() {
        val dir = tempDir("resume")
        val file = File(dir, AuditRecords.FILE_NAME)
        file.writeText("y".repeat(120) + "\n")
        val writer = JsonlRotatingWriter(file, rotationBytes = 100L, keepFiles = 3)
        try {
            // 🔴 开句柄时 existingBytes = 121 >= 100 ⇒ 第一条 writeLine 就触发轮转（轮转周期不被重启重置）
            assertTrue(writer.writeLine("z".repeat(10)))
            assertEquals(1L, writer.rotationCount)
        } finally {
            writer.close()
        }
    }

    @Test
    fun `IO 失败后丢弃坏句柄并自愈`() {
        val dir = tempDir("heal")
        val file = File(dir, AuditRecords.FILE_NAME)
        val writer = JsonlRotatingWriter(file)
        try {
            assertTrue(writer.writeLine("""{"n":1}"""))
            writer.flush()
            // 模拟「文件被外部删除」（常驻句柄在 Linux 下仍指向已删 inode ⇒ 后续写入静默丢失）
            assertTrue(file.delete())
            // 🔴 自愈：onFailure 只在**抛异常**时触发；Linux 下删除后写入不抛，故这里直接验证重开路径——
            //    close 后再 writeLine 必须重新建文件（ensureWriter 重开）
            writer.close()
            assertTrue(writer.writeLine("""{"n":2}"""))
            writer.flush()
            assertTrue(file.exists(), "close 后重新 writeLine 必须重开句柄并重建文件")
            assertEquals(1, file.readLines().filter { it.isNotBlank() }.size)
        } finally {
            writer.close()
        }
    }

    @Test
    fun `resolveFile 展开 tilde 且 blank 目录返回 null`() {
        assertNull(JsonlSink.resolveFile("", SinkRecord.AUDIT_FILE_NAME))
        assertNull(JsonlSink.resolveFile("   ", SinkRecord.AUDIT_FILE_NAME))
        assertNull(AuditRecords.resolveFile(""))
        val home = System.getProperty("user.home")
        assertEquals(File(home, "lark/audit.jsonl"), AuditRecords.resolveFile("~/lark"))
        assertEquals(File(home, "lark/sender_stats.jsonl"), SenderStatsRecords.resolveFile(" ~/lark "))
        assertNotNull(JsonlTailReader.resolveFile("~/lark", SinkRecord.AUDIT_FILE_NAME))
    }

    @Test
    fun `encoder 对不匹配类型返回 null 而非抛异常`() {
        val auditEncoder = AuditRecords.encoder()
        val statEncoder = SenderStatsRecords.encoder()
        val audit = record()
        val stat = SinkRecord.SenderStat(
            ts = 1L, senderOpenId = "ou", userName = "n", chatId = "oc",
            type = SinkRecord.TYPE_MESSAGE, contentSummary = "c",
        )
        assertNotNull(auditEncoder.encode(audit))
        assertNull(auditEncoder.encode(stat), "审计编码器遇统计记录必须返回 null（sink 计入 typeMismatchDropped）")
        assertNotNull(statEncoder.encode(stat))
        assertNull(statEncoder.encode(audit))
        // 🔴 编码出的行可被 AuditJson 原样读回（双向兼容的一半）
        val line = auditEncoder.encode(audit)!!
        assertEquals(audit.ts, AuditJson.decodeFromString(AuditEntryJson.serializer(), line).ts)
    }

    @Test
    fun `SinkRecord Audit 到 AuditEntryJson 的字段映射逐一对齐`() {
        val r = SinkRecord.Audit(
            ts = 7L, chatId = "oc_1", senderOpenId = "ou_1", prompt = "p", command = "c",
            status = "FAILURE", errorCode = "agent_timeout", exitCode = 124,
            elapsedMillis = 99L, backend = "claude", kind = "AGENT",
            attachments = listOf("report.md <- /ws/report.md"),
            rejectedArtifacts = listOf("[产物] /ws/big.zip  <!-- 超出大小上限 25MB -->"),
        )
        val e = r.toEntry()
        assertEquals(7L, e.ts)
        assertEquals("oc_1", e.chatId)
        assertEquals("ou_1", e.senderOpenId)
        assertEquals("p", e.prompt)
        assertEquals("c", e.command)
        assertEquals("FAILURE", e.status)
        assertEquals("agent_timeout", e.errorCode)
        assertEquals(124, e.exitCode)
        assertEquals(99L, e.elapsedMillis)
        assertEquals("claude", e.backend)
        assertEquals("AGENT", e.kind)
        assertEquals(listOf("report.md <- /ws/report.md"), e.attachments, "产物回传清单须透传（2026-09-12）")
        assertEquals(
            listOf("[产物] /ws/big.zip  <!-- 超出大小上限 25MB -->"),
            e.rejectedArtifacts,
            "被拒清单须透传（2026-09-12 取证盲区补齐）",
        )
        // 16 个字段一个不多一个不少（schema 锁死；attachments/rejectedArtifacts 为 2026-09-12 产物回传域新增，
        // userName 为 2026-09-14 用户身份链批后加；userMessageId 为 2026-09-27 批⑤屏⑤后加——表情回显锚点；
        // delegatedFromBackend 为委托审计嵌套标记后加）
        assertEquals(
            setOf("ts", "chatId", "senderOpenId", "prompt", "command", "status", "errorCode", "exitCode", "elapsedMillis", "backend", "kind", "attachments", "rejectedArtifacts", "userName", "userMessageId", "delegatedFromBackend"),
            AuditEntryJson.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) }.toSet() },
        )
        assertEquals(16, AuditEntryJson.serializer().descriptor.elementsCount)
    }

    // 🔴 对抗评审补测（2026-09-12）：产物两清单的「空值 → 缺键」契约此前零守护（同款断言只覆盖 backend/kind）。
    //    生产侧靠 `takeIf { isNotEmpty() }` 把空清单落 null，再靠 encodeDefaults=false 落缺键——两层任一被改
    //    就会让每条无产物的审计行凭空多出 `"attachments":[]`，存量比对工具（jq/pandas）口径漂移。
    @Test
    fun `产物清单为空时 encode 不产键（BC-306 契约）`() {
        val decoded = SinkRecord.Audit(
            ts = 1L, chatId = "oc_1", senderOpenId = "ou_1", prompt = "p", command = "c",
            status = "SUCCESS", errorCode = null, exitCode = null,
            elapsedMillis = 5L, backend = "claude", kind = "AGENT",
        ).toEntry()
        assertNull(decoded.attachments, "无产物 ⇒ null（不产空键）")
        assertNull(decoded.rejectedArtifacts)

        val encoded = AuditJson.encodeToString(AuditEntryJson.serializer(), decoded)
        assertFalse(encoded.contains("attachments"), "空清单必须缺键：$encoded")
        assertFalse(encoded.contains("rejectedArtifacts"), "空清单必须缺键：$encoded")

        // 非空清单则必须出现（防止「一律不序列化」式的反向破坏）
        val withArtifacts = decoded.copy(attachments = listOf("a.md <- /ws/a.md"))
        val encodedWith = AuditJson.encodeToString(AuditEntryJson.serializer(), withArtifacts)
        assertTrue(encodedWith.contains("attachments"), "非空清单必须落盘：$encodedWith")
    }

    @Test
    fun `userName 后加字段旧行无键读回 null 且非空落键`() {
        // 2026-09-14 用户身份链批：AuditEntryJson 尾部后加 userName（可空默认 null）。
        // 🔴 旧格式行（无 userName 键）decode 读回 null——存量 audit.jsonl 双向兼容（与 backend/kind/attachments 同惯例）
        val legacy = """{"ts":1,"chatId":"oc","senderOpenId":"ou","prompt":"p","command":"c","status":"SUCCESS","exitCode":0,"elapsedMillis":9}"""
        val decoded = AuditJson.decodeFromString(AuditEntryJson.serializer(), legacy)
        assertNull(decoded.userName, "旧行无 userName 键必须读回 null")
        assertEquals(9L, decoded.elapsedMillis)

        // 空串落 null 不产空键（C4 语义：ctx.senderName 空串经 takeIf 落 null）
        val fromBlank = AuditEntryJson(
            ts = 1L, chatId = "oc", senderOpenId = "ou", prompt = "", command = "",
            status = "SUCCESS", errorCode = null, exitCode = 0, elapsedMillis = 1L, userName = null,
        )
        val encodedBlank = AuditJson.encodeToString(AuditEntryJson.serializer(), fromBlank)
        assertFalse(encodedBlank.contains("userName"), "null 缺键不产空键：$encodedBlank")

        // 非空则必须落键且 round-trip 保持
        val withName = decoded.copy(userName = "张三")
        val encodedWith = AuditJson.encodeToString(AuditEntryJson.serializer(), withName)
        assertTrue(encodedWith.contains("\"userName\":\"张三\""), "非空用户名必须落键：$encodedWith")
        assertEquals("张三", AuditJson.decodeFromString(AuditEntryJson.serializer(), encodedWith).userName)
    }

    @Test
    fun `批⑤ userMessageId 后加字段旧行无键读回 null 且非空落键`() {
        // 批 ⑤（2026-09-27 屏⑤）：AuditEntryJson 尾部后加 userMessageId（表情回显查询锚点）。
        // 🔴 旧格式行（无 userMessageId 键）decode 读回 null——存量 audit.jsonl 双向兼容
        //    （与 userName/attachments 同款后加字段三件套：默认 null + 空值不产键 + ignoreUnknownKeys）
        val legacy = """{"ts":1,"chatId":"oc","senderOpenId":"ou","prompt":"p","command":"c","status":"SUCCESS","exitCode":0,"elapsedMillis":9}"""
        val decoded = AuditJson.decodeFromString(AuditEntryJson.serializer(), legacy)
        assertNull(decoded.userMessageId, "旧行无 userMessageId 键必须读回 null（历史行无锚点 ⇒ 徽标诚实缺席）")

        // 空串落 null 不产空键：转换发生在**生产写入点**（takeIf 惯例，BridgeAssembler onOutcome）——
        // schema 层只钉「null 缺键」契约（空串形态的 takeIf 转换由装配点承载，schema 拿到的已是 null）
        val fromNull = decoded.copy(userMessageId = null)
        val encodedNull = AuditJson.encodeToString(AuditEntryJson.serializer(), fromNull)
        assertFalse(encodedNull.contains("userMessageId"), "null 缺键不产空键：$encodedNull")

        // 非空则必须落键且 round-trip 保持（表情回显查询用）
        val withId = decoded.copy(userMessageId = "om_user_msg_1")
        val encodedWith = AuditJson.encodeToString(AuditEntryJson.serializer(), withId)
        assertTrue(encodedWith.contains("\"userMessageId\":\"om_user_msg_1\""), "非空锚点必须落键：$encodedWith")
        assertEquals(
            "om_user_msg_1",
            AuditJson.decodeFromString(AuditEntryJson.serializer(), encodedWith).userMessageId,
        )
    }

    @Test
    fun `批⑤ SinkRecord Audit 的 userMessageId 传导到磁盘 schema`() {
        // 内存 seam → 磁盘 schema 的 toEntry 传导（漏传导 = 生产行恒无锚点、表情回显全哑）
        val e = record().copy(userMessageId = "om_anchor_42").toEntry()
        assertEquals("om_anchor_42", e.userMessageId, "toEntry 须传导 userMessageId")
        val encoded = AuditJson.encodeToString(AuditEntryJson.serializer(), e)
        assertTrue(encoded.contains("\"userMessageId\":\"om_anchor_42\""), "编码行须含锚点键：$encoded")
    }

    @Test
    fun `delegatedFromBackend 后加字段旧行无键读回 null 且非空落键`() {
        // 委托审计嵌套标记（后加可空字段三件套：默认 null + 空值不产键 + ignoreUnknownKeys 旧行兼容）
        val legacy = """{"ts":1,"chatId":"oc","senderOpenId":"ou","prompt":"p","command":"c","status":"SUCCESS","exitCode":0,"elapsedMillis":9}"""
        val decoded = AuditJson.decodeFromString(AuditEntryJson.serializer(), legacy)
        assertNull(decoded.delegatedFromBackend, "旧行无 delegatedFromBackend 键必须读回 null（存量双向兼容）")

        // null 缺键不产空键
        val encodedNull = AuditJson.encodeToString(AuditEntryJson.serializer(), decoded)
        assertFalse(encodedNull.contains("delegatedFromBackend"), "null 缺键不产空键：$encodedNull")

        // 非空则必须落键且 round-trip 保持
        val withDelegator = decoded.copy(delegatedFromBackend = "koog")
        val encodedWith = AuditJson.encodeToString(AuditEntryJson.serializer(), withDelegator)
        assertTrue(encodedWith.contains("\"delegatedFromBackend\":\"koog\""), "非空委托标记必须落键：$encodedWith")
        assertEquals("koog", AuditJson.decodeFromString(AuditEntryJson.serializer(), encodedWith).delegatedFromBackend)
    }

    @Test
    fun `SinkRecord Audit 的 delegatedFromBackend 传导到磁盘 schema`() {
        // 内存 seam → 磁盘 schema 的 toEntry 传导（漏传导 = 委托嵌套关系在生产行里恒缺席）
        val e = record().copy(delegatedFromBackend = "koog").toEntry()
        assertEquals("koog", e.delegatedFromBackend, "toEntry 须传导 delegatedFromBackend")
        val encoded = AuditJson.encodeToString(AuditEntryJson.serializer(), e)
        assertTrue(encoded.contains("\"delegatedFromBackend\":\"koog\""), "编码行须含委托标记键：$encoded")
    }
}
