// :lark-gateway 测试（M3-T3，§11.9 Handler3SecondTest = BC NF-01，结构性约束由 T3 落地）。
// 下线批（2026-09-12）改写：SDK websocket 入站路径（registerHandlers + capture + captureNormalizedMessage）
// 已整体删除，3 秒铁律的结构性保证锚点迁移到自研路径——`handleWsInboundEvent`（:lark-ws 事件回调，
// 翻译层 WsInboundTranslator 纯函数语义 + offerInbound O(1) 非阻塞）。
// 🔴 ①的「真实 SDK NormalizedMessage 样本」计时随之退役：翻译层纯函数的逐字节对齐由 WsInboundTranslatorTest
//    30 例背书（转换模板/mention 语义/闸门/fallback），回调纪律的 grep 断言在本文件兑现。
package com.jiaoay.lark.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §11.9：飞书 handler 3 秒铁律的**结构性**保证——回调内只允许 `runCatching { offerInbound(...) }`
 * （WsInboundTranslator 纯函数翻译 + 非阻塞 trySend；WsDispatcher Channel 背压，慢消费不阻塞 reader）。
 *
 * 🔴 下线批后的分工：
 *  - ②③（grep 回调函数体零锁/零日志/零 IO/零网络/零 JSON）= 本文件对 handleWsInboundEvent + offerInbound 兑现。
 *  - ①（整条回调路径 <1ms）：翻译层（WsInboundTranslator.translate）+ mapper（LarkInboundMapper.fromMessage）
 *    两段纯函数的计时归 WsInboundTranslatorTest 同域；capture（SDK getter 复制）段已随 SDK 入站删除。
 *  - ④（handler 阻塞期间飞书重推不被去重拦住的因果链）是文档级说明，不做断言。
 */
class Handler3SecondTest {

    // ② 回调路径 grep：handleWsInboundEvent 函数体零 synchronized / 零 Mutex / 零 launch / 零日志格式化 / 零阻塞睡眠
    @Test
    fun `handler body has no blocking no logging and no coroutine launch`() {
        // 🔴 归一化（剥离注释+字符串）：回调注释里写有「不 launch/不加锁」等字样，未归一化会假红
        val body = stripCommentsAndStrings(handlerBody())
        assertFalse("synchronized" in body, "回调内禁止 synchronized（WsDispatcher 消费协程内加锁会阻塞背压）")
        assertFalse("Mutex" in body, "回调内禁止 Mutex")
        assertFalse(".lock(" in body, "回调内禁止任何显式锁")
        assertFalse("launch" in body, "回调内禁止 launch（回调已跑在分发协程内，不得再派生协程，§3.4）")
        assertFalse("Thread.sleep" in body, "回调内禁止阻塞睡眠")
        // 🔴 日志格式化：任何 log.xxx( 或字符串模板插值都会触发 SLF4J 格式化 = 回调内重活（§11.9 ②）
        assertFalse(Regex("""\blog\.""").containsMatchIn(body), "回调内禁止任何日志调用（决策 9）")
        assertFalse(Regex("""\.(info|warn|error|debug|trace)\(""").containsMatchIn(body),
            "回调内禁止日志级别方法调用")
    }

    // ③ 回调内不得出现任何 IO / 网络 / 锁 / JSON 序列化（翻译层是纯函数解析，offerInbound 是原子入队）
    @Test
    fun `handler body does no io no network and no json serialization`() {
        val body = stripCommentsAndStrings(handlerBody())
        assertFalse("Files." in body, "回调内禁止文件 IO")
        assertFalse("FileWriter" in body || "FileOutputStream" in body, "回调内禁止落盘")
        assertFalse("Gson" in body || "writeValue" in body, "回调内禁止 JSON 序列化（决策 9；翻译层是解析非序列化）")
        assertFalse("rawClient" in body, "回调内禁止再发起 SDK 调用（rawClient 是出站面）")
        assertFalse("download" in body, "回调内禁止触发下载（resources 只透传 record，下载由引用流懒触发）")
        assertFalse("Transport.send" in body, "回调内禁止发起 HTTP")
    }

    // 🔴 SDK 入站路径已删（下线批）：registerHandlers/capture/captureNormalizedMessage 不得复活
    @Test
    fun `sdk websocket inbound path is gone for good`() {
        val src = readGatewaySource()
        // 🔴 墓碑注释（解释删除原因的注释块）含这些字样 ⇒ 归一化后扫描代码区
        val codeOnly = stripCommentsAndStrings(src)
        assertFalse("private fun registerHandlers(" in codeOnly, "registerHandlers 已随 SDK websocket 入站下线删除")
        assertFalse("captureNormalizedMessage" in codeOnly, "captureNormalizedMessage 顶层函数已删")
        assertFalse("ChannelEventHandler" in codeOnly, "SDK ChannelEventHandler 注册面已删（ChannelEventBus 无事件来源）")
    }

    // ①' 回调路径的非翻译部分 offerInbound 只做 AtomicInteger CAS + trySend（O(1) 非阻塞，无锁）
    @Test
    fun `offerInbound is non blocking atomic enqueue only`() {
        val src = readGatewaySource()
        val raw = src.substringAfter("private fun offerInbound(").substringBefore("private fun offerSignal(")
        assertTrue(raw.isNotEmpty(), "定位不到 offerInbound 函数体")
        // 🔴 归一化：offerInbound 的 KDoc 里写有「无锁」等字样；trySend/getAndIncrement 是真实代码 token，归一化后仍在
        val body = stripCommentsAndStrings(raw)
        assertFalse("synchronized" in body || "Mutex" in body || ".lock(" in body, "offerInbound 禁止加锁（决策 9）")
        assertFalse(Regex("""\blog\.""").containsMatchIn(body), "offerInbound 禁止日志")
        assertTrue("trySend" in body, "offerInbound 只经 trySend 非阻塞入队")
        // DROP_OLDEST 下用占用计数推断丢弃（裁定 2/B-2 可观测）：getAndIncrement 是原子 CAS，非阻塞
        assertTrue("getAndIncrement" in body, "丢弃计数须用原子 CAS（非阻塞）")
    }

    /** 提取 handleWsInboundEvent 函数体（含三分支），供 ②③ grep。 */
    private fun handlerBody(): String {
        val src = readGatewaySource()
        val body = src.substringAfter("private suspend fun handleWsInboundEvent(")
            .substringBefore("private fun wsStateListener()")
        assertTrue(body.isNotEmpty(), "定位不到 handleWsInboundEvent 函数体")
        return body
    }

    private fun readGatewaySource(): String {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val f = File(dir, "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt")
        assertTrue(f.exists(), "定位不到 SdkLarkGateway.kt：${f.absolutePath}")
        return f.readText()
    }
}
