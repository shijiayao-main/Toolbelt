// M4-T8 第一批 · observe 组测试（3/8）：发送者统计域（schema + 采集器）+ 用户名解析。
// 🔴 移植自旧 `stats/SenderStatsStoreTest.kt`（8 个测试）——**8 条逐条移植**。
// 🔴 BC 对应：BC-313..BC-322（统计 schema 与容错）+ BC §4.11（三个字面值大写、WHITELIST/BLACKLIST 刻意保留旧拼写）
//    + D5（零 `runBlocking`）+ D18（`nameUnresolvedTotal` 可观测）。
// 🔴 用户存量 `~/.toolbelt/lark/sender_stats.jsonl` 就是这个 schema，改字段名即破坏存量。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.config.AuditJson
import com.jiaoay.lark.bridge.pipeline.AccessDecision
import com.jiaoay.lark.bridge.pipeline.stripCommentsAndStrings
import com.jiaoay.lark.bridge.pipeline.testRepoRoot
import com.jiaoay.lark.gateway.SenderProfile
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SenderStatsTest {

    private fun tempDir(tag: String = "stats"): File =
        Files.createTempDirectory("$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    /** 关通道 → 跑统计 sink → 回读（旧 `SenderStatsStore` 是同步写盘，新架构是通道 + sink 协程）。 */
    private fun flush(channels: SinkChannels, dir: String): List<SenderStatsEntry> = runBlocking {
        channels.close()
        JsonlSink.stats(channels, dir).run()
        JsonlTailReader.readStatsAll(dir).records
    }

    // ── 旧 SenderStatsStoreTest 8 条逐条移植 ──

    @Test
    fun `onMessage 追加 MESSAGE 记录并解析用户名`() = runBlocking {
        val dir = tempDir()
        val channels = SinkChannels()
        val recorder = SenderStatsRecorder(channels, userNameResolver = { openId -> "用户-$openId" }, clock = { 1_000L })
        recorder.onMessage("ou_a", "oc_1", "ai 分析日志")

        val entries = flush(channels, dir.path)
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("ou_a", entry.senderOpenId)
        assertEquals("oc_1", entry.chatId)
        assertEquals(SenderStatsRecords.TYPE_MESSAGE, entry.type)
        assertEquals("ai 分析日志", entry.content)
        assertEquals("用户-ou_a", entry.userName)
        assertEquals(1_000L, entry.ts)
        assertEquals(1L, recorder.collectedTotal)
        assertEquals(0L, recorder.nameUnresolvedTotal)
    }

    @Test
    fun `onRejected 按判定结果区分类型`() = runBlocking {
        val dir = tempDir()
        val channels = SinkChannels()
        val recorder = SenderStatsRecorder(channels, clock = { 2_000L })
        recorder.onRejected("ou_x", AccessDecision.NOT_IN_ALLOWLIST)
        recorder.onRejected("ou_bad", AccessDecision.BLOCKED)

        val entries = flush(channels, dir.path)
        assertEquals(2, entries.size)
        // 🔴 BC §4.11：字面值是**大写**且保留旧拼写 WHITELIST/BLACKLIST
        assertEquals("REJECTED_WHITELIST", entries[0].type)
        assertEquals("REJECTED_BLACKLIST", entries[1].type)
        assertEquals(SenderStatsRecords.TYPE_REJECTED_WHITELIST, entries[0].type)
        assertEquals(SenderStatsRecords.TYPE_REJECTED_BLACKLIST, entries[1].type)
        // 🔒 旧行为保留：拒绝事件的 chatId 与 content 都是空串（旧实现两处都没传）
        assertEquals("", entries[0].chatId)
        assertEquals("", entries[0].content)
        assertEquals("", entries[1].chatId)
        // 未注入 resolver ⇒ 两次都算「解析不出用户名」（D18 可观测）
        assertEquals(2L, recorder.nameUnresolvedTotal)
    }

    @Test
    fun `typeOf 对三个判定值穷尽且无 else`() {
        assertEquals(SenderStatsRecords.TYPE_MESSAGE, SenderStatsRecords.typeOf(AccessDecision.ALLOWED))
        assertEquals(SenderStatsRecords.TYPE_REJECTED_WHITELIST, SenderStatsRecords.typeOf(AccessDecision.NOT_IN_ALLOWLIST))
        assertEquals(SenderStatsRecords.TYPE_REJECTED_BLACKLIST, SenderStatsRecords.typeOf(AccessDecision.BLOCKED))
        // 🔴 三个值两两不同（宿主侧按 type 分组）
        assertEquals(
            3,
            setOf(
                SenderStatsRecords.TYPE_MESSAGE,
                SenderStatsRecords.TYPE_REJECTED_WHITELIST,
                SenderStatsRecords.TYPE_REJECTED_BLACKLIST,
            ).size,
        )
        assertEquals(3, AccessDecision.entries.size, "新增第 4 个判定值时 typeOf 的穷尽 when 会编译失败")
    }

    @Test
    fun `用户名解析失败留空不抛异常`() = runBlocking {
        val dir = tempDir()
        val channels = SinkChannels()
        val recorder = SenderStatsRecorder(channels, userNameResolver = { error("resolver boom") })
        recorder.onMessage("ou_a", "oc_1", "hi") // 🔴 不得抛

        val entries = flush(channels, dir.path)
        assertEquals(1, entries.size)
        assertEquals("", entries.first().userName)
        assertEquals(1L, recorder.nameUnresolvedTotal, "🔴 D18：解析失败必须计数")
    }

    @Test
    fun `禁用态（空目录）不写入不抛异常`() = runBlocking {
        val dir = tempDir()
        val channels = SinkChannels()
        val recorder = SenderStatsRecorder(channels, enabled = false, userNameResolver = { error("不该被调用") })
        recorder.onMessage("ou_a", "oc_1", "hi")
        recorder.onRejected("ou_a", AccessDecision.NOT_IN_ALLOWLIST)
        // 🔴 enabled=false ⇒ 连解析都不做、连通道都不投
        assertEquals(0L, recorder.collectedTotal)
        assertEquals(0L, recorder.nameUnresolvedTotal)
        val entries = flush(channels, dir.path)
        assertTrue(entries.isEmpty())
        assertFalse(File(dir, SenderStatsRecords.FILE_NAME).exists(), "禁用态不得建文件")
    }

    @Test
    fun `readAll 文件不存在返回空列表`() {
        assertEquals(emptyList(), JsonlTailReader.readStatsAll("").records)
        assertEquals(emptyList(), JsonlTailReader.readStatsAll(tempDir().path).records)
        // blank 目录 ⇒ resolveFile 返回 null（= 禁用统计）
        assertNull(SenderStatsRecords.resolveFile(""))
        assertNull(SenderStatsRecords.resolveFile("   "))
        assertNotNull(SenderStatsRecords.resolveFile("/tmp/x"))
        assertEquals(SenderStatsRecords.FILE_NAME, SenderStatsRecords.resolveFile("/tmp/x")?.name)
    }

    @Test
    fun `readAll 跳过损坏行`() {
        val dir = tempDir("damaged")
        File(dir, SenderStatsRecords.FILE_NAME).writeText(
            """{"ts":1,"senderOpenId":"ou_a","chatId":"oc_1","type":"MESSAGE"}""" + "\n" +
                "not-json-line\n" +
                """{"ts":2,"senderOpenId":"ou_b","chatId":"oc_2","type":"MESSAGE"}""" + "\n",
        )
        val result = JsonlTailReader.readStatsAll(dir.path)
        assertEquals(2, result.records.size)
        assertEquals("ou_a", result.records[0].senderOpenId)
        assertEquals("ou_b", result.records[1].senderOpenId)
        assertEquals(1, result.damagedLines, "🔴 损坏行必须计数（D18），不得静默吞掉")
        assertEquals(3, result.linesScanned)
        // content/userName 缺键 ⇒ 默认空串（BC-306：`encodeDefaults=false` 的对称面）
        assertEquals("", result.records[0].content)
        assertEquals("", result.records[0].userName)
    }

    @Test
    fun `readAll 含未知键的 JSON 行不丢弃`() {
        // 模拟未来 SenderStatsEntry 加新字段后，旧版本读取新写入的文件：
        // 必须用 ignoreUnknownKeys=true 的配方（`AuditJson`），否则整行被静默丢弃
        val dir = tempDir("unknown")
        File(dir, SenderStatsRecords.FILE_NAME).writeText(
            """{"ts":1,"senderOpenId":"ou_a","chatId":"oc_1","type":"MESSAGE","futureField":1}""" + "\n",
        )
        val result = JsonlTailReader.readStatsAll(dir.path)
        assertEquals(1, result.records.size)
        assertEquals("ou_a", result.records[0].senderOpenId)
        assertEquals(SenderStatsRecords.TYPE_MESSAGE, result.records[0].type)
        assertEquals(0, result.damagedLines)
    }

    @Test
    fun `内容摘要截断`() = runBlocking {
        val dir = tempDir()
        val channels = SinkChannels()
        SenderStatsRecorder(channels).onMessage("ou_a", "oc_1", "x".repeat(500))
        val entries = flush(channels, dir.path)
        assertEquals(SenderStatsRecords.CONTENT_SUMMARY_CHARS, entries.first().content.length)
        // 🔴 BC 校正：统计侧摘要长度是 **120**（不是审计的 200 / 命令的 60）
        assertEquals(120, SenderStatsRecords.CONTENT_SUMMARY_CHARS)
        assertEquals(120, SinkRecord.CONTENT_SUMMARY_CHARS)
        // 🔒 旧实现用 `content.take(120)` ⇒ **不带省略号**（与 `StringTruncation` 的默认 `…` 不同）
        assertEquals("x".repeat(120), SenderStatsRecords.summarizeContent("x".repeat(500)))
        assertEquals("短文本", SenderStatsRecords.summarizeContent("短文本"))
        assertEquals("", SenderStatsRecords.summarizeContent(""))
    }

    // ── schema / 编码 ──

    @Test
    fun `统计 entry 的 6 个字段名与旧 schema 逐字一致`() {
        val names = SenderStatsEntry.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
        // 🔒 旧 `stats/SenderStatsStore.SenderStatsEntry`（源 :17-30）的字段名与顺序；
        //    2026-09-14 用户身份链批尾部追加 `avatarUrl`（后加字段带默认值——encodeDefaults=false ⇒ 空串缺键，
        //    存量旧行读回 ""，双向兼容；与 audit 的 backend/kind/attachments 同惯例）
        assertEquals(listOf("ts", "senderOpenId", "chatId", "type", "content", "userName", "avatarUrl"), names)
        // 🔴 单行紧凑；`AuditJson` 的 `encodeDefaults=false` ⇒ **取默认值的字段缺键**。
        //    实测用户存量 `sender_stats.jsonl`（10 行）的键集就是
        //    {ts, senderOpenId, chatId, type, content}——`userName` 为空时**不出现**。
        val line = SenderStatsRecords.encoder().encode(
            SinkRecord.SenderStat(ts = 7L, senderOpenId = "ou", userName = "", chatId = "", type = "MESSAGE", contentSummary = ""),
        )
        assertNotNull(line)
        assertFalse(line.contains("\n"))
        assertFalse(line.contains("userName"), "🔴 默认值字段缺键（存量双向兼容：旧行同样没有 userName）：$line")
        assertFalse(line.contains("\"content\""), "content 也是默认值字段，同样缺键：$line")
        // chatId 无默认值（必填字段）⇒ 空串也要落键
        assertTrue(line.contains("\"chatId\":\"\""), "无默认值的必填字段即使空串也要落键：$line")
        assertTrue(line.contains("\"ts\":7"))
        // 类型不匹配 ⇒ null（sink 据此计入 typeMismatchDropped）
        assertNull(
            SenderStatsRecords.encoder().encode(
                SinkRecord.Audit(
                    ts = 1L, chatId = "", senderOpenId = "", prompt = "", command = "",
                    status = "SUCCESS", errorCode = null, exitCode = 0, elapsedMillis = 0L, backend = null,
                ),
            ),
        )
    }

    @Test
    fun `SinkRecord SenderStat 到 entry 的映射逐字段对齐`() {
        val record = SinkRecord.SenderStat(
            ts = 42L, senderOpenId = "ou_z", userName = "张三", chatId = "oc_z",
            type = SenderStatsRecords.TYPE_MESSAGE, contentSummary = "hello",
        )
        val entry = record.toEntry()
        assertEquals(42L, entry.ts)
        assertEquals("ou_z", entry.senderOpenId)
        assertEquals("张三", entry.userName)
        assertEquals("oc_z", entry.chatId)
        assertEquals("MESSAGE", entry.type)
        assertEquals("hello", entry.content, "🔴 磁盘字段叫 content，内存 seam 叫 contentSummary")
        assertEquals("", entry.avatarUrl, "未显式传 avatarUrl 时默认空串（后加字段缺键语义）")
        // 往返一致
        assertEquals(entry, AuditJson.decodeFromString(SenderStatsEntry.serializer(), AuditJson.encodeToString(SenderStatsEntry.serializer(), entry)))
    }

    // ── profile 双产出（2026-09-14 用户身份链批）──

    @Test
    fun `profileResolver 命中时统计记录含头像且不调 userNameResolver`() = runBlocking {
        val dir = tempDir()
        val channels = SinkChannels()
        var fallback = 0
        val recorder = SenderStatsRecorder(
            channels,
            userNameResolver = { fallback++; null },
            profileResolver = { openId -> SenderProfile("名字-$openId", "https://a.example/240.png") },
            clock = { 1_000L },
        )
        recorder.onMessage("ou_a", "oc_1", "hi")
        val entries = flush(channels, dir.path)
        assertEquals(0, fallback, "profileResolver 主路命中时不应回落 userNameResolver（单路化）")
        assertEquals("名字-ou_a", entries.first().userName)
        assertEquals("https://a.example/240.png", entries.first().avatarUrl)
    }

    @Test
    fun `profileResolver 为 null 时降级 userNameResolver 名字-only`() = runBlocking {
        val dir = tempDir()
        val channels = SinkChannels()
        val recorder = SenderStatsRecorder(
            channels,
            userNameResolver = { openId -> "降级-$openId" },
            profileResolver = { null },
            clock = { 2_000L },
        )
        recorder.onMessage("ou_b", "oc_2", "hi")
        val entries = flush(channels, dir.path)
        assertEquals("降级-ou_b", entries.first().userName)
        assertEquals("", entries.first().avatarUrl, "降级路头像空串")
        assertEquals(0L, recorder.nameUnresolvedTotal, "降级路解析出名字不计失败")
    }

    @Test
    fun `profileResolver 抛异常时静默降级且计数失败`() = runBlocking {
        val dir = tempDir()
        val channels = SinkChannels()
        val recorder = SenderStatsRecorder(
            channels,
            userNameResolver = { null },
            profileResolver = { throw IllegalStateException("resolver boom") },
            clock = { 3_000L },
        )
        recorder.onMessage("ou_c", "oc_3", "hi")
        val entries = flush(channels, dir.path)
        assertEquals("", entries.first().userName, "两路全挂 ⇒ 名字空串（旁路语义）")
        assertEquals("", entries.first().avatarUrl)
        assertEquals(1L, recorder.nameUnresolvedTotal, "两路都解析不出必须计一次 D18 失败")
    }

    @Test
    fun `onRejected 分型记录含头像与分型字面值`() = runBlocking {
        val dir = tempDir()
        val channels = SinkChannels()
        val recorder = SenderStatsRecorder(
            channels,
            profileResolver = { openId -> SenderProfile("名字-$openId", "https://a.example/reject.png") },
            clock = { 4_000L },
        )
        recorder.onRejected("ou_d", AccessDecision.NOT_IN_ALLOWLIST, "let me in")
        val entries = flush(channels, dir.path)
        assertEquals("REJECTED_WHITELIST", entries.first().type)
        assertEquals("名字-ou_d", entries.first().userName)
        assertEquals("https://a.example/reject.png", entries.first().avatarUrl, "拒绝事件同样双产出（本批 onRejected 接线修复配套）")
    }

    @Test
    fun `avatarUrl 非空落键且旧行无键读回空串`() {
        // encodeDefaults=false ⇒ avatarUrl 空串缺键、非空落键
        val withAvatar = SenderStatsRecords.encoder().encode(
            SinkRecord.SenderStat(ts = 1L, senderOpenId = "ou", userName = "n", chatId = "c", type = "MESSAGE", contentSummary = "x", avatarUrl = "https://a.example/u.png"),
        )
        assertNotNull(withAvatar)
        assertTrue(withAvatar.contains("\"avatarUrl\":\"https://a.example/u.png\""), "非空头像必须落键：$withAvatar")
        // 旧格式行（无 avatarUrl 键）decode 读回默认空串——存量双向兼容
        val legacy = """{"ts":1,"senderOpenId":"ou","chatId":"c","type":"MESSAGE","content":"x","userName":"n"}"""
        val decoded = AuditJson.decodeFromString(SenderStatsEntry.serializer(), legacy)
        assertEquals("", decoded.avatarUrl, "旧行无 avatarUrl 键必须读回空串默认值")
    }

    // ── 🔴 D5：零 runBlocking ──

    @Test
    fun `D5 采集器两个回调都是 suspend（源文件代码级零 runBlocking）`() {
        val clazz = SenderStatsRecorder::class.java
        // suspend fun 的 JVM 形态：最后一个参数是 Continuation
        listOf("onMessage", "onRejected").forEach { name ->
            val method = clazz.methods.first { it.name == name }
            assertEquals(
                Continuation::class.java,
                method.parameterTypes.last(),
                "🔴 D5：`$name` 必须是 suspend（旧 `SenderStatsListener` 是同步接口，故写路径内只能 runBlocking）",
            )
        }
        // 构造参数里的 userNameResolver 也必须是 suspend lambda（Function2<String, Continuation, Object>）
        val ctor = clazz.constructors.first()
        assertTrue(
            ctor.parameterTypes.any { it.name == "kotlin.coroutines.jvm.internal.SuspendFunction2" || it.name.startsWith("kotlin.jvm.functions.Function2") },
            "resolver seam 形态异常：${ctor.parameterTypes.toList()}",
        )

        // 🔴 代码级扫描：剥离注释与字符串后，observe 全组零 `runBlocking`
        val dir = File(testRepoRoot(), "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe")
        assertTrue(dir.isDirectory, "observe 源目录必须存在（否则本守护是空过）：$dir")
        val offenders = dir.listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { it.isFile }
            .filter { stripCommentsAndStrings(it.readText(Charsets.UTF_8)).contains("runBlocking") }
            .map { it.name }
        assertEquals(emptyList(), offenders, "🔴 D5：observe 组代码级不得出现 runBlocking（KDoc 提及允许）")
    }

    @Test
    fun `D5 挂起的 resolver 被正常等待而非阻塞线程`() = runBlocking {
        val dir = tempDir("suspend")
        val channels = SinkChannels()
        val recorder = SenderStatsRecorder(
            channels,
            userNameResolver = { openId ->
                delay(30) // 🔴 真挂起：若是 runBlocking 桥接，这里会把调用线程钉住 30ms
                "名字-$openId"
            },
        )
        recorder.onMessage("ou_a", "oc_1", "hi")
        val entries = flush(channels, dir.path)
        assertEquals("名字-ou_a", entries.first().userName)
        assertEquals(0L, recorder.nameUnresolvedTotal)
    }

    // ── UserNameResolver（旧 openapi/UserNameResolver.kt 58 行平移）──

    @Test
    fun `nameOf 缓存命中零网络且失败缓存空串占位`() = runBlocking {
        var calls = 0
        val resolver = UserNameResolver(fetch = { openId ->
            calls++
            if (openId == "ou_ok") """{"data":{"user":{"name":"张三"}}}""" else null
        })
        // 🔒 旧 :56 逐字
        assertEquals("/contact/v3/users/", UserNameResolver.USER_PATH)
        assertEquals("?user_id_type=open_id", UserNameResolver.USER_ID_TYPE_QUERY)
        assertEquals("", UserNameResolver.MISSING)

        assertEquals("张三", resolver.nameOf("ou_ok"))
        assertEquals(1, calls)
        // 第二次命中缓存 ⇒ 不再调 fetch（旧 :41-42 同语义）
        assertEquals("张三", resolver.nameOf("ou_ok"))
        assertEquals(1, calls)
        assertEquals(1L, resolver.cacheHits)
        assertEquals(1L, resolver.fetchTotal)
        assertEquals(0L, resolver.fetchFailures)
        assertEquals(1, resolver.cacheSize)

        // 🔒 旧 :52-54：失败缓存**空串占位**，避免无权限场景反复请求（下次仍返回 null）
        assertNull(resolver.nameOf("ou_no_perm"))
        assertEquals(2, calls)
        assertNull(resolver.nameOf("ou_no_perm"))
        assertEquals(2, calls, "失败占位必须生效：不得再发一次网络请求")
        assertEquals(1L, resolver.fetchFailures)
        assertEquals(2, resolver.cacheSize)

        // blank openId 直接返回 null，不占缓存不计数
        assertNull(resolver.nameOf(""))
        assertNull(resolver.nameOf("   "))
        assertEquals(2, calls)
        assertEquals(2L, resolver.fetchTotal)
    }

    @Test
    fun `nameOf 抛异常时返回 null 且不影响后续调用`() = runBlocking {
        var fail = true
        val resolver = UserNameResolver(fetch = {
            if (fail) throw IllegalStateException("网络炸了") else """{"data":{"user":{"name":"李四"}}}"""
        })
        assertNull(resolver.nameOf("ou_a"))
        assertEquals(1L, resolver.fetchFailures)
        // 🔴 clear() 清掉失败占位（宿主开通 contact 权限后可重试），但**不**归零计数器
        resolver.clear()
        assertEquals(0, resolver.cacheSize)
        assertEquals(1L, resolver.fetchFailures, "clear 不得归零计数器（否则 D18 指标丢失）")
        fail = false
        assertEquals("李四", resolver.nameOf("ou_a"))
        assertEquals(2L, resolver.fetchTotal)
    }

    @Test
    fun `extractUserName 逐层容错（旧 43-44 的 runCatching 语义）`() {
        assertEquals("张三", UserNameResolver.extractUserName("""{"data":{"user":{"name":"张三"}}}"""))
        assertEquals("张三", UserNameResolver.extractUserName("""{"data":{"user":{"name":"张三","extra":1}},"more":true}"""))
        // 各层缺失/类型不符 ⇒ 空串（不抛）
        assertEquals("", UserNameResolver.extractUserName("{}"))
        assertEquals("", UserNameResolver.extractUserName("""{"data":{}}"""))
        assertEquals("", UserNameResolver.extractUserName("""{"data":{"user":{}}}"""))
        // 🔒 旧 :44 用 `jsonPrimitive.content`，**不校验**是字符串还是数字 ⇒ 数字名也原样返回（逐字平移）
        assertEquals("123", UserNameResolver.extractUserName("""{"data":{"user":{"name":123}}}"""))
        assertEquals("", UserNameResolver.extractUserName("""{"data":[]}"""))
        assertEquals("", UserNameResolver.extractUserName("not json"))
        assertEquals("", UserNameResolver.extractUserName(""))
        // JsonElement 重载
        assertEquals("", UserNameResolver.extractUserName(JsonObject(emptyMap())))
        assertEquals(
            "王五",
            UserNameResolver.extractUserName(
                UserNameResolver.ResponseJson.parseToJsonElement("""{"data":{"user":{"name":"王五"}}}"""),
            ),
        )
        assertEquals("", UserNameResolver.extractUserName(JsonPrimitive("张三")))
    }

    @Test
    fun `UserNameResolver 可作为 SenderStatsRecorder 的 seam 直接注入`() = runBlocking {
        val dir = tempDir("wire")
        val channels = SinkChannels()
        val resolver = UserNameResolver(fetch = { """{"data":{"user":{"name":"王五"}}}""" })
        // 🔴 装配形态：T9 直接把 `resolver::resolve` 传进去，无需适配层
        val recorder = SenderStatsRecorder(channels, userNameResolver = resolver::nameOf)
        recorder.onMessage("ou_a", "oc_1", "hi")
        recorder.onMessage("ou_b", "oc_1", "hi")
        val entries = flush(channels, dir.path)
        assertEquals(listOf("王五", "王五"), entries.map { it.userName })
        // 第二个 openId 命中不了缓存 ⇒ 2 次 fetch
        assertEquals(2L, resolver.fetchTotal)
        assertEquals(0L, resolver.fetchFailures)
        assertEquals(0L, recorder.nameUnresolvedTotal)
    }
}
