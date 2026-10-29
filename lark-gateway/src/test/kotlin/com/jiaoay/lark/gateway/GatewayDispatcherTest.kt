// :lark-gateway 测试（M3-T3，§11.11 GatewayDispatcherTest = BC NF-A4，断言已按裁定 4/B-4 收窄）。
// 🔴 零 SDK / 零真机：②④ 用 grep + 常量断言兑现；①⑤（真实线程名 bridge-net / bridge-register 记录）需注入 fake SDK，归 T11。
// 🔴 ③（commonPool 不可完全消解的豁免）：ch.send() / addReaction() / removeReactionByEmoji() 只有 CompletableFuture 变体，
//    SDK 内部 supplyAsync 落 commonPool，我方无法改（附录 B 第 4 条）。断言形态**不覆盖**这三处；本文件注释即为该豁免的登记。
package com.jiaoay.lark.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §11.11 / NF-A4：所有 SDK 调用的**发起点**必须在注入的 `net` dispatcher 上（`withContext(netDispatcher)` 包裹）；
 * `await()` 续体恢复线程不由我方控制，但续体内只做非阻塞映射与 trySend（裁定 4/B-4）。
 *
 * 🔴 分工（记入报告）：①⑤ 需真机注入 fake SDK 记录 `Thread.currentThread().name`，归 **T11**；本轮兑现 ②④ 的 SDK-free 结构断言，
 *    并用 grep 锁定「四类同步 SDK 调用点全部包在 withContext(netDispatcher)」这一 ① 的结构前提。
 */
class GatewayDispatcherTest {

    // ② RetryConfig.maxAttempts == 1（关掉 SDK 盲指数退避的 Thread.sleep 路径，决策 6 / [SF:597]）——常量 + 装配双证
    @Test
    fun `retry is disabled so the sdk never sleeps on common pool`() {
        assertEquals(1, GatewaySdkSettings.DEFAULT.retryMaxAttempts, "SDK 默认 3，必须覆写为 1（决策 6）")
        assertEquals(1, Tuning.retryMaxAttempts, "Tuning.retryMaxAttempts 恒 1")
        // 装配点：buildOptions 里 maxAttempts = s.retryMaxAttempts（真正下推到 SDK RetryConfig）
        val src = readGatewaySource()
        val buildOptions = src.substringAfter("private fun buildOptions(").substringBefore("private fun policyConfig(")
        assertTrue("maxAttempts = s.retryMaxAttempts" in buildOptions,
            "buildOptions 须把 retryMaxAttempts 下推到 SDK RetryConfig.maxAttempts")
        assertTrue("RetryConfig()" in buildOptions, "应显式构造 SDK RetryConfig 而非用默认")
    }

    // ④ net dispatcher 由构造参数注入，网关内不自建：SdkLarkGateway.kt 零 Dispatchers.IO、零 limitedParallelism
    @Test
    fun `gateway does not build its own io dispatcher`() {
        val src = readGatewaySource()
        // 🔴 归一化：SdkLarkGateway.kt 抬头注释写「本文件零 Dispatchers.IO / 零 limitedParallelism」，是对禁用项的提及而非使用
        val norm = stripCommentsAndStrings(src)
        assertFalse("Dispatchers.IO" in norm, "SdkLarkGateway 禁止自建 Dispatchers.IO（net 由引擎注入，§9.2/D16）")
        assertFalse("limitedParallelism" in norm, "SdkLarkGateway 禁止 limitedParallelism（唯一例外在 RegisterAppFlow.kt）")
        // net dispatcher 是构造参数，且四类同步 SDK 调用点都包在 withContext(netDispatcher)
        assertTrue("private val netDispatcher: CoroutineDispatcher" in src, "netDispatcher 须为构造注入参数")
        assertTrue("withContext(netDispatcher)" in src, "阻塞 SDK 调用须包在 withContext(netDispatcher)")
        // ① 的结构前提：connect / disconnect / updatePolicy / send / setReaction / doDownload 全部 withContext(netDispatcher)
        val netWraps = Regex("""withContext\(netDispatcher\)""").findAll(src).count()
        assertTrue(netWraps >= 5, "四类同步 SDK 调用点应全部包在 netDispatcher 上，实测 $netWraps 处")
    }

    // ④' 唯一例外：RegisterAppFlow.kt 的 bridge-register（专用单线程，不占 net 的 8 槽，决策 12 / §9.2）
    @Test
    fun `register app runs on a dedicated single thread dispatcher not on net`() {
        val reg = File(repoRoot(), "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/RegisterAppFlow.kt").readText()
        assertTrue("bridge-register" in reg, "registerApp 须跑在专用 bridge-register dispatcher（决策 12）")
        assertTrue("limitedParallelism(1" in reg, "bridge-register 须是单线程（limitedParallelism(1)）")
        assertTrue("Dispatchers.IO" in reg, "bridge-register 是 Dispatchers.IO 的视图（不持有线程，无需 shutdown）")
        // ⑤ 的结构前提：SdkLarkGateway.registerApp 委托给 RegisterAppFlow.run（不自建线程、不占 net）
        val src = readGatewaySource()
        val registerBody = src.substringAfter("override suspend fun registerApp(").substringBefore("override fun close()")
        assertTrue("RegisterAppFlow.run(" in registerBody, "registerApp 须委托 RegisterAppFlow.run（跑在 bridge-register）")
        assertFalse("bridge-net" in registerBody, "registerApp 绝不占 net 的 bridge-net 槽（决策 12）")
    }

    // ③ commonPool 豁免登记 + await() 续体纪律：send/setReaction 用 await() 挂起，续体内只做映射 + offerSignal（非阻塞）
    @Test
    fun `await continuations only map and enqueue never block or reissue sdk calls`() {
        val src = readGatewaySource()
        val sendBody = src.substringAfter("override suspend fun send(").substringBefore("override fun openCardStream(")
        // await() 是挂起调用 ⇒ 用 try/catch（不能放进非挂起的 runCatching）；续体内只 mapThrowable + offerSignal
        assertTrue(".await()" in sendBody, "send 须用 CompletableFuture.await()（kotlinx-coroutines-core-jvm 的 future.await）")
        assertFalse("Thread.sleep" in sendBody, "await 续体禁止阻塞睡眠")
        assertFalse("runBlocking" in sendBody, "await 续体禁止 runBlocking")
        // 🔴 决策 6：send 永不重试，且禁止调用 isRetryable（§6.3.4）
        assertFalse("isRetryable" in sendBody, "send 禁止调用 isRetryable（永不重试，决策 6 / §11.8）")
        assertFalse(Regex("""\bretry""", RegexOption.IGNORE_CASE).containsMatchIn(sendBody), "send 内禁止任何重试逻辑")
    }

    // §11.8 网关侧约束：RateLimited 只发信号不自己退避（退避决策交 RateGovernor，M4-T7）
    @Test
    fun `gateway emits rate limited signal without backing off itself`() {
        val src = readGatewaySource()
        // 网关不实现退避循环：无 rateLimitBackoff 的 delay 退避逻辑（Tuning 里的常量供 RateGovernor 读，网关自身不退避）
        assertFalse("rateLimitBackoffMs" in src, "网关侧不得自己退避（退避归 RateGovernor，§11.8 / §3.5）")
        // 收到限流由 SdkCardStreamPort 的 findRateLimitReset 提取 reset 头 → outcome.rateLimitResetSec → sink 发 RateLimited 信号
        //（定义在 SdkCardStreamPort.kt；网关各出站/补查成员与本处共用同一提取口径）
        val portSrc = File(repoRoot(), "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkCardStreamPort.kt").readText()
        assertTrue("findRateLimitReset" in portSrc, "限流 reset 头提取应存在（供信号携带 resetSec）")
    }

    private fun readGatewaySource(): String =
        File(repoRoot(), "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt").readText()

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }
}
