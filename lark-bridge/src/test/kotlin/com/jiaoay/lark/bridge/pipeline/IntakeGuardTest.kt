// M4-T7 测试清单 ⑪ + ⑫：Handler3SecondTest 的**管线侧**断言（NF-01）与 intake 零锁 grep 守护（CDS §14.3）。
//
// 🔴 分工说明（避免与 :lark-gateway 的同名测试重复/漏测）：
//   - SDK 侧：`SdkLarkGateway` 的事件回调只做 `runCatching { inboundChannel.trySend(msg) }`，由 :lark-gateway
//     的 `Handler3SecondTest` 覆盖（那里的 handler 线程 == OkHttp WS reader 线程，ACK 同步等待）。
//   - 管线侧（本文件）：① `InboundQueue.trySend` 在**队列满**时仍 O(1) 返回（DROP_OLDEST 恒不阻塞）；
//     ② ① Intake 单消费者协程处理 1 万条合成消息的吞吐在合理上限内；
//     ③ 🔴 intake 路径 8 个源文件**剥离注释与字符串字面量后**零 `synchronized`、零 `Mutex`（CDS §14.3 首次生效）；
//     ④ 🔴 NF-01 ② 的「零 suspend 调用 / 零 launch」按**作用域**落地：约束的是「SDK 回调 → 入队」这一段生产者路径
//        （`InboundQueue.trySend`）与「单条消息的五步判定」（`EventIntake.processOne`），不是整个文件——
//        `EventIntake.start(scope)` 里的 `scope.launch` 是**启动期一次性**建协程，`InboundQueue.receive()` 的
//        `suspend` 是**单消费者自己**的挂起点（它正是「用协程替代锁」的那个并发原语），两者都在热路径之外。
// 🔴 归一化的必要性（Jason 在 :lark-gateway 已踩坑）：生产源的抬头注释与 KDoc 里对 `Mutex`/`synchronized` 的
//    **提及**（例如「本路径全程无锁」）会造成假红 ⇒ 一律用 `stripCommentsAndStrings` 后的源做「禁止出现」型断言。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.RouteRule
import com.jiaoay.lark.gateway.GatewaySignal
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class IntakeGuardTest {

    // ================================================================== 源码扫描基础设施

    /** 🔴 intake 路径的源文件清单（任务书 ⑫ 的 8 个 + 同路径的 `PipelineRouter` / `PipelineCounters`）。 */
    private val intakePathFiles = listOf(
        "EventIntake.kt",
        "MessageDeduper.kt",
        "StaleFilter.kt",
        "AccessControl.kt",
        "PolicyGate.kt",
        "PipelineSnapshot.kt",
        "ChatLanes.kt",
        "InboundQueue.kt",
        "PipelineRouter.kt",
        "PipelineCounters.kt",
    )

    private fun pipelineDir(): File =
        File(testRepoRoot(), "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline")

    private fun source(name: String): String {
        val file = File(pipelineDir(), name)
        assertTrue(file.isFile, "被扫描的源文件必须存在（否则本守护是空过）：$file")
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.lines().size > 20, "$name 只有 ${text.lines().size} 行 ⇒ 疑似扫到了空壳文件")
        return text
    }

    /** 从归一化源里按花括号配对抠出一个函数体（用于「作用域内」的结构性断言）。 */
    private fun functionBody(normalized: String, owner: String, signatureFragment: String): String {
        val start = normalized.indexOf(signatureFragment)
        assertTrue(start >= 0, "$owner 里找不到 $signatureFragment ⇒ 守护是空过")
        val open = normalized.indexOf('{', start)
        assertTrue(open >= 0, "$signatureFragment 不是块体函数，无法做作用域断言")
        var depth = 1
        var i = open + 1
        while (i < normalized.length && depth > 0) {
            when (normalized[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        assertEquals(0, depth, "$signatureFragment 的花括号不配对 ⇒ 归一化器或抠取逻辑有问题")
        return normalized.substring(open + 1, i - 1)
    }

    private fun assertZero(body: String, token: String, scope: String) {
        assertFalse(
            body.contains(token),
            "🔴 $scope 内不得出现 `$token`（归一化后仍命中 ⇒ 是真代码而非注释提及）：\n" +
                body.lines().filter { it.contains(token) }.joinToString("\n"),
        )
    }

    // ================================================================== ⑫ CDS §14.3：intake 路径零锁

    @Test
    fun `🔴 CDS 14-3 intake 路径 10 个源文件归一化后零 synchronized 零 Mutex（本轮首次生效）`() {
        // 🔴 禁字清单：监视器锁、协程互斥量、JUC 显式锁、以及任何「阻塞式等待」原语。
        //    intake 是单消费者协程 ⇒ 它**本身就是**并发原语，去重 LRU / 会话 LRU / ChatLane 表 / 计数器全部无锁。
        val forbidden = listOf(
            "synchronized",
            "Mutex",
            "ReentrantLock",
            "ReentrantReadWriteLock",
            "withLock",
            "runBlocking",
            "Thread.sleep",
            "CountDownLatch",
            "Semaphore",
            ".wait(",
            ".notify(",
        )
        val hits = mutableListOf<String>()
        var scannedLines = 0
        intakePathFiles.forEach { name ->
            val normalized = stripCommentsAndStrings(source(name))
            scannedLines += normalized.lines().size
            forbidden.forEach { token ->
                normalized.lines().forEachIndexed { index, line ->
                    if (line.contains(token)) hits.add("$name:${index + 1}: $token → ${line.trim()}")
                }
            }
        }
        assertTrue(scannedLines > 500, "扫描量异常小（$scannedLines 行）⇒ 守护可能是空过")
        assertEquals(emptyList(), hits, "🔴 intake 路径必须零锁零阻塞原语（CDS §14.3）")
    }

    @Test
    fun `🔴 归一化器自证：KDoc 与字符串里提及 synchronized 不算命中，代码里才算（守护非空过）`() {
        // ① 负向自证：注释与字符串里的提及必须被剥掉（否则生产源的「本路径全程无锁」文案会造成假红）
        val commented = """
            // synchronized 出现在行注释里
            /* Mutex 出现在块注释里，还嵌套 /* 一层 */ 继续 */
            class Demo {
                /** KDoc 里也提到 synchronized 与 Mutex */
                val label = "synchronized Mutex"
                val triple = ${'"'}${'"'}${'"'}里面也有 synchronized${'"'}${'"'}${'"'}
                fun ok(): Int = 1
            }
        """.trimIndent()
        val normalized = stripCommentsAndStrings(commented)
        assertFalse(normalized.contains("synchronized"), "行/块注释与 KDoc 里的提及必须被剥掉")
        assertFalse(normalized.contains("Mutex"), "字符串字面量里的提及必须被剥掉")
        assertTrue(normalized.contains("class Demo"), "🔴 代码本体必须保留（否则守护变成『什么都不扫』的假绿）")
        assertTrue(normalized.contains("fun ok"), "代码本体必须保留")

        // ② 正向自证：真的写了锁就必须被扫出来
        val locked = "class Bad { fun f() = synchronized(this) { 1 } }"
        assertTrue(stripCommentsAndStrings(locked).contains("synchronized"), "真代码里的锁必须命中")
    }

    @Test
    fun `🔴 NF-01 作用域断言：InboundQueue-trySend 与 EventIntake-processOne 体内零 suspend 零 launch 零 delay`() {
        val queueBody = functionBody(stripCommentsAndStrings(source("InboundQueue.kt")), "InboundQueue", "fun trySend(")
        listOf("suspend", "launch", "delay(", "synchronized", "Mutex", "runBlocking", "Thread.sleep").forEach {
            assertZero(queueBody, it, "InboundQueue.trySend（SDK 回调线程唯一入口）")
        }
        // 🔴 trySend 体内只允许「原子计数 + channel.trySend + 溢出时的 WARN/回调」，不得有任何 IO / 网络 / JSON
        assertTrue(queueBody.contains("channel.trySend(message)"), "必须真的走非阻塞的 channel.trySend")
        listOf("Files.", "readText", "writeText", "Socket", "Http", "toJson", "fromJson", "Json.").forEach {
            assertZero(queueBody, it, "InboundQueue.trySend（NF-01 ③：回调内零 IO / 网络 / JSON 序列化）")
        }

        val intakeBody = functionBody(stripCommentsAndStrings(source("EventIntake.kt")), "EventIntake", "fun processOne(")
        listOf("suspend", "launch", "delay(", "synchronized", "Mutex", "runBlocking", "Thread.sleep", "withTimeout").forEach {
            assertZero(intakeBody, it, "EventIntake.processOne（单条消息的五步判定）")
        }
        // 🔴 五步判定必须齐全，且顺序是 StaleFilter → Deduper → AccessControl → PolicyGate → Router → Admission
        val order = listOf("staleFilter.test", "deduper.decide", "accessControl.decide", "policyGate.test", "router.route", "admission.submit(ctx")
        var cursor = -1
        order.forEach { step ->
            val at = intakeBody.indexOf(step)
            assertTrue(at > cursor, "🔴 五阶段顺序被打乱或缺失：$step（位置 $at，上一个 $cursor）")
            cursor = at
        }
        listOf("Files.", "Socket", "Http", "toJson", "fromJson").forEach {
            assertZero(intakeBody, it, "EventIntake.processOne（NF-01 ③：零 IO / 网络 / JSON 序列化）")
        }
    }

    @Test
    fun `🔴 SdkLarkGateway 的入站回调只做 runCatching trySend（NF-01 ①，跨模块源码交叉校验）`() {
        val gatewaySource = File(
            testRepoRoot(),
            "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt",
        )
        assertTrue(gatewaySource.isFile, "网关源文件必须存在：$gatewaySource")
        val raw = gatewaySource.readText(Charsets.UTF_8)
        // ① 存在性断言用**原始源**（要看的就是那行字面代码）
        assertTrue(
            raw.contains("runCatching { inboundChannel.trySend(msg) }"),
            "🔴 NF-01 ①：SDK 回调内只允许 runCatching { inboundChannel.trySend(msg) }",
        )
        // ② 该回调函数体内不得有协程派发、挂起、日志格式化、JSON、IO
        val body = functionBody(stripCommentsAndStrings(raw), "SdkLarkGateway", "fun offerInbound(")
        listOf("launch", "suspend", "delay(", "synchronized", "Mutex", "runBlocking", "log.", "toJson", "fromJson", "Files.", "Socket").forEach {
            assertZero(body, it, "SdkLarkGateway.offerInbound（跑在 OkHttp WS reader 线程上，ACK 同步等待）")
        }
        assertTrue(body.contains("inboundChannel.trySend(msg)"), "回调必须真的入队")
    }

    // ================================================================== ⑫ 反射：非挂起是**编译进字节码**的结构事实

    @Test
    fun `🔴 intake 单条处理链的每个原语都是非挂起函数（无 Continuation 参数），唯一挂起点是 receive`() {
        val continuation = Class.forName("kotlin.coroutines.Continuation")

        fun assertNonSuspend(owner: Class<*>, vararg names: String) {
            names.forEach { name ->
                val methods = owner.methods.filter { it.name == name }
                assertTrue(methods.isNotEmpty(), "${owner.simpleName} 缺少方法 $name ⇒ 守护是空过")
                methods.forEach { m ->
                    assertFalse(
                        m.parameterTypes.any { it == continuation },
                        "🔴 ${owner.simpleName}.$name 必须是**非挂起**函数（挂起 ⇒ 调用方可能被拖住，侵蚀 3 秒铁律）：$m",
                    )
                }
            }
        }

        assertNonSuspend(EventIntake::class.java, "processOne")
        assertNonSuspend(InboundQueue::class.java, "trySend", "drainNow", "close")
        assertNonSuspend(StaleFilter::class.java, "test", "isFresh", "ageOf")
        assertNonSuspend(MessageDeduper::class.java, "decide", "firstTime", "contains", "clear")
        assertNonSuspend(AccessControl::class.java, "decide", "isAllowed", "rejectionReply", "isKnownMode")
        assertNonSuspend(PolicyGate::class.java, "test", "isAllowed")
        assertNonSuspend(PipelineRouter::class.java, "route", "publish")
        assertNonSuspend(PipelineSnapshot::class.java, "resolve")
        assertNonSuspend(PipelineSnapshotFactory::class.java, "build")
        assertNonSuspend(ChatLanes::class.java, "laneOf", "tryAcquire", "release", "register", "unregister", "cancelAll", "claimBusyReply", "snapshot")
        assertNonSuspend(Admission::class.java, "submit", "submitRejection")
        assertNonSuspend(PipelineCounters::class.java, "recordReceived", "recordStaleDropped", "recordDuplicateDropped", "recordAccessRejected", "recordPolicyRejected", "recordSubmitted", "recordIntakeQueueDropped")

        // 🔴 唯一允许的挂起点：单消费者自己的等待处（它正是「用协程替代锁」的那个并发原语）
        val receive = InboundQueue::class.java.methods.filter { it.name == "receive" }
        assertEquals(1, receive.size, "InboundQueue.receive 必须存在且唯一")
        assertTrue(
            receive.single().parameterTypes.any { it == continuation },
            "🔴 receive 必须是 suspend（intake 协程在此挂起等消息，而不是自旋或加锁）",
        )
    }

    // ================================================================== ⑪ 管线侧 3 秒铁律

    @Test
    fun `🔴 InboundQueue 满时 trySend 仍 O(1) 返回（DROP_OLDEST 不阻塞，2 万次累计在秒级内）`() {
        val drops = mutableListOf<Pair<String, Long>>()
        val queue = InboundQueue(capacity = 512, log = BridgeLog.Silent, where = InboundQueue.WHERE) { where, total ->
            drops.add(where to total)
        }
        val total = 20_000
        val samples = LongArray(total)

        // 🔴 无消费者 ⇒ 第 513 条起每次都触发 DROP_OLDEST，全程都在「队列满」这个最坏情形下测
        val started = System.nanoTime()
        var accepted = 0
        for (i in 0 until total) {
            val at = System.nanoTime()
            if (queue.trySend(msg(messageId = "om_flood_$i"))) accepted++
            samples[i] = System.nanoTime() - at
        }
        val elapsedNanos = System.nanoTime() - started

        assertEquals(512, accepted, "只有前 512 条未被挤掉")
        assertEquals(total.toLong(), queue.offeredCount, "投递计数统计全部调用")
        assertEquals((total - 512).toLong(), queue.dropped, "🔴 D18：每一次丢弃都被精确计数")
        assertEquals(512L, queue.pending, "在途恒等于容量（DROP_OLDEST 的稳态）")
        assertEquals(0L, queue.consumedCount)
        assertEquals(total - 512, drops.size, "每次丢弃都触发了可观测回调")
        assertEquals(InboundQueue.WHERE, drops.first().first, "where 标签必须是 intake（第二个有界队列）")
        assertEquals((1..(total - 512)).map { it.toLong() }, drops.map { it.second }, "droppedTotal 必须单调递增")

        // 🔴 「不阻塞」的结构性证据：若 trySend 会在队满时挂起/自旋，2 万次的累计耗时就不可能停在秒级。
        assertTrue(
            elapsedNanos < 2_000_000_000L,
            "🔴 2 万次满队 trySend 累计必须 < 2s（恒不阻塞），实际 ${elapsedNanos / 1_000_000}ms",
        )
        samples.sort()
        val avgNanos = elapsedNanos / total
        assertTrue(avgNanos < 50_000L, "平均耗时必须 < 50µs，实际 ${avgNanos}ns")
        assertTrue(samples[(total * 0.99).toInt()] < 500_000L, "p99 必须 < 500µs，实际 ${samples[(total * 0.99).toInt()] / 1000}µs")
        // 🔴 单次 < 1ms 的硬阈值在下一个用例的「正常路径」上断言：溢出路径每次都要格式化一条 WARN
        //    （D18 要求的可观测成本，且只在异常路径发生），不属于 reader 线程的常态预算。
        queue.close()
    }

    @Test
    fun `🔴 NF-01 阈值：正常路径单次 trySend 耗时小于 1ms（reader 线程预算）`() {
        // 容量足够大 ⇒ 全程走「未溢出」的常态路径（SDK 回调的 99.99% 情形）
        val queue = InboundQueue(capacity = 65_536, log = BridgeLog.Silent)
        // 🔴 先预热：类加载 / JIT / 首次分配的成本不属于 trySend 本身，否则量到的是 JVM 启动而不是管线
        repeat(5_000) { queue.trySend(msg(messageId = "om_warm_$it")) }

        val total = 20_000
        val samples = LongArray(total)
        val started = System.nanoTime()
        for (i in 0 until total) {
            val at = System.nanoTime()
            assertTrue(queue.trySend(msg(messageId = "om_hot_$i")), "未溢出时不得丢")
            samples[i] = System.nanoTime() - at
        }
        val elapsedNanos = System.nanoTime() - started
        assertEquals(0L, queue.dropped, "常态路径零丢弃")
        assertEquals(25_000L, queue.offeredCount)
        assertEquals(25_000L, queue.pending)

        samples.sort()
        val avg = samples.average()
        // 🔴 NF-01：SDK 回调内只做 trySend，单次 < 1ms（reader 线程被占用期间整条长连接的所有后续事件都无法读取）。
        //    「无真阻塞」的结构性证据 = 累计耗时（同款口径见溢出路径用例）：共享 CI runner 上
        //    单样本绝对 max 会被抢占/GC 停顿打出 >10ms 尖刺，不可归因于 trySend（O(1) 无分配
        //    的原子计数 + 入队）；任何真阻塞结构（锁/挂起/自旋）都会把 2 万次累计爆到秒级以上。
        assertTrue(
            elapsedNanos < 2_000_000_000L,
            "🔴 NF-01：2 万次常态 trySend 累计必须 < 2s（恒不阻塞），实际 ${elapsedNanos / 1_000_000}ms",
        )
        assertTrue(
            samples[(total * 0.999).toInt()] < 1_000_000L,
            "🔴 NF-01：p99.9 单次耗时必须 < 1ms，实际 ${samples[(total * 0.999).toInt()] / 1000}µs",
        )
        assertTrue(avg < 20_000.0, "🔴 平均耗时必须 < 20µs（µs 级预算），实际 ${avg}ns")
        queue.close()
    }

    @Test
    fun `🔴 队列已关闭后 trySend 不抛异常、返回 false，且丢弃同样计数（D18 的关停窗口）`() {
        val drops = mutableListOf<Long>()
        val queue = InboundQueue(capacity = 8, log = BridgeLog.Silent) { _, total -> drops.add(total) }
        assertTrue(queue.trySend(msg(messageId = "om_open")))
        assertEquals(0L, queue.dropped)

        queue.close()
        queue.close() // 🔴 close 幂等（D20 优雅关停里 stop() 与 shutdown hook 都可能调）
        assertFalse(queue.trySend(msg(messageId = "om_after_close")), "关停窗口内的消息必须被拒收而不是静默吞掉")
        assertEquals(1L, queue.dropped, "🔴 关停丢弃也必须可观测（否则违反 D18「所有丢弃点都有计数器」）")
        assertEquals(listOf(1L), drops)
        assertFalse(queue.trySend(msg(messageId = "om_after_close_2")))
        assertEquals(2L, queue.dropped)
        assertEquals(listOf(1L, 2L), drops, "droppedTotal 累计递增")
        // 🔴 close 前已成功入队的那条仍在途（`receive` 排空时会取走，随后返回 null 令 intake 收尾）
        assertEquals(1L, queue.pending, "pending 只算「已入队未消费」，关停丢弃已计入 dropped 不重复扣")
        assertEquals(1, queue.drainNow(), "排空能取走那一条")
        assertEquals(0L, queue.pending)
        assertEquals(1L, queue.consumedCount)
    }

    @Test
    fun `🔴 intake 单协程处理 1 万条合成消息：生产者零阻塞、零丢弃，平均耗时在上限内`() = runBlocking {
        // 路由表刻意留空 ⇒ 全部走「未命中兜底」，且 TEXT 未命中恒静默（P3：模板已删）不出站。
        // 🔴 这样量到的就是**① Intake 本身**的五步判定 + 一次 launch 的成本，不被出站频控与卡片流污染。
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = emptyList<RouteRule>()),
            kinds = HandlerKinds.EMPTY,
            replyMode = ReplyMode.GATEWAY,
            queueCapacity = 16_384,
        ).use { h ->
            h.startIntake()
            val total = 10_000
            val sendSamples = LongArray(total)
            val produceStarted = System.nanoTime()
            for (i in 0 until total) {
                val at = System.nanoTime()
                assertTrue(h.queue.trySend(msg(messageId = "om_perf_$i", text = "perf $i")), "第 $i 条不得被丢弃")
                sendSamples[i] = System.nanoTime() - at
            }
            val produceNanos = System.nanoTime() - produceStarted

            // 🔴 生产者侧：SDK 回调只 trySend，1 万条累计必须在秒级内完成（若 intake 慢到会回压生产者，这里就会爆）
            assertTrue(produceNanos < 3_000_000_000L, "1 万条投递累计必须 < 3s，实际 ${produceNanos / 1_000_000}ms")
            sendSamples.sort()
            assertTrue(
                sendSamples[(total * 0.999).toInt()] < 1_000_000L,
                "🔴 NF-01：投递的 p99.9 单次耗时必须 < 1ms，实际 ${sendSamples[(total * 0.999).toInt()] / 1000}µs",
            )

            // 🔴 消费者侧：intake 单协程必须在合理上限内把 1 万条全部处理完
            val drained = h.awaitUntil(60_000L) { h.intake.processedCount >= total.toLong() }
            val elapsedMillis = (System.nanoTime() - produceStarted) / 1_000_000L
            assertTrue(drained, "intake 单协程必须在 60s 内处理完 1 万条，实际只到 ${h.intake.processedCount}（${elapsedMillis}ms）")

            val avgMicros = elapsedMillis * 1000L / total
            assertTrue(avgMicros < 3_000L, "🔴 单条平均耗时必须 < 3ms，实际 ${avgMicros}µs（总 ${elapsedMillis}ms）")
            assertTrue(elapsedMillis < 30_000L, "1 万条总耗时必须 < 30s，实际 ${elapsedMillis}ms")

            assertEquals(total.toLong(), h.intake.processedCount)
            assertEquals(total.toLong(), h.counters.intakeReceived)
            assertEquals(total.toLong(), h.queue.consumedCount)
            assertEquals(0L, h.queue.dropped, "🔴 容量 16384 + 单消费者 ⇒ 全程零丢弃")
            assertEquals(0L, h.counters.intakeQueueDropped)
            assertEquals(0L, h.counters.staleDropped)
            assertEquals(0L, h.counters.duplicatesDropped, "1 万个互不相同的 messageId ⇒ 零去重丢弃")
            assertEquals(0L, h.counters.blankIdsDropped)
            assertEquals(0L, h.counters.accessRejected, "默认 blocklist 且黑名单为空 ⇒ 全放行")
            assertEquals(0L, h.counters.policyRejected, "P2P 恒放行 ⇒ 门控不触发")
            assertEquals(total.toLong(), h.counters.submitted, "全部进了 ② 准入（未命中兜底也算提交）")
            assertEquals(total.toLong(), h.router.unmatchedCount, "路由表为空 ⇒ 全部未命中")
            assertEquals(0L, h.counters.handlerErrors, "🔴 BC-146：1 万条里没有一条让 intake 记错")
            assertTrue(h.allReplyBodies().isEmpty(), "TEXT 未命中恒静默（P3：模板已删；BC-151/BC-194 语义延续）")
        }
    }

    @Test
    fun `🔴 入站队列溢出转发网关 Dropped(intake, n) 信号（D18 第二级有界队列的可观测性）`() = runBlocking {
        Harness(dispatcher = Dispatchers.Default, queueCapacity = 4).use { h ->
            val signals = async(Dispatchers.Default) { h.gateway.signals.take(2).toList() }
            delay(50L)
            // 🔴 不启动 intake ⇒ 队列必然填满并溢出（这一级队列的 where 标签是 intake，不是 inbound）
            val accepted = (1..6).map { i -> h.queue.trySend(msg(messageId = "om_ov_$i")) }
            assertEquals(listOf(true, true, true, true, false, false), accepted)
            assertEquals(2L, h.queue.dropped)
            assertEquals(2L, h.counters.intakeQueueDropped)

            val got = withTimeoutOrNull(5_000L) { signals.await() }
            assertNotNull(got, "溢出必须转发成网关信号")
            assertEquals(
                listOf(GatewaySignal.Dropped("intake", 1L), GatewaySignal.Dropped("intake", 2L)),
                got,
                "🔴 where 必须是 intake（桥接侧第二级队列），与网关侧的 inbound 区分开",
            )
        }
    }

    @Test
    fun `🔴 单消费者结构：start 只建一个协程，close 后排空即正常收尾（D20 优雅排空）`() = runBlocking {
        Harness(dispatcher = Dispatchers.Default, queueCapacity = 64).use { h ->
            (1..5).forEach { h.queue.trySend(msg(messageId = "om_drain_$it")) }
            val job = h.startIntake()
            assertTrue(h.awaitUntil(10_000L) { h.intake.processedCount >= 5L }, "5 条必须被处理")

            // 🔴 close 后 receive 排空即返回 null ⇒ intake 协程**正常结束**（不是被 cancel）
            h.queue.close()
            val finished = withTimeoutOrNull(10_000L) { job.join(); true } ?: false
            assertTrue(finished, "队列关闭后 intake 协程必须自行收尾（D20），不得悬挂")
            assertTrue(job.isCompleted)
            assertFalse(job.isCancelled, "🔴 优雅排空是正常完成，不是取消")
            assertEquals(5L, h.queue.consumedCount)
        }
    }
}
