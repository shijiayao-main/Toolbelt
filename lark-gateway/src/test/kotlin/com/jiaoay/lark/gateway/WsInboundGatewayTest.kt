// :lark-gateway —— SdkLarkGateway 自研 ws 入站单测（接线批 2026-09-12 / 下线批收口）。
// connect 需要真实 SDK connectSync（identity fetch 走 HTTP）——本文件不真连，用**源码守护断言**钉死
// 「入站恒自研」的结构不变量：恒 webhook 选项、wsInboundFactory 必传无分叉、SDK 事件注册整体删除、
// close 收口与反射路径删除。行为层（翻译/投递/状态）由 WsInboundTranslatorTest 与 :lark-ws 47 测分层背书。
package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

class WsInboundGatewayTest {

    private fun testCredential() = LarkCredential(appId = "cli_test", appSecret = "sec_test", region = "feishu")

    // connect 需要 SDK connectSync（identity fetch 走 HTTP）——注入缝测试用 credentialOverride 语义
    // 无法绕过 connectSync。本组用例不真连：只验证「工厂被调用 + 选项构造正确」的前置行为，
    // 完整 connect 链路归真机双跑对账。改为直接断言源码结构 + Fake 工厂非空语义。

    @Test
    fun `入站恒自研 恒 webhook 选项 恒启传输（下线批源码守护）`() {
        val src = java.io.File(
            "src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt"
        ).readText()
        // 恒 webhook：buildOptions 唯一调用点带 inboundWebhook = true（SDK 侧无第二条 ws 长连接）
        assertTrue(
            src.contains("buildOptions(cred, merged, inboundWebhook = true)"),
            "connect 步 2 恒以 inboundWebhook=true 构造 SDK 选项（transport(\"webhook\")，防双长连接互踢）",
        )
        assertFalse(
            src.contains("buildOptions(cred, merged)"),
            "下线批后不得残留两参调用（SDK websocket 原链路已删）",
        )
        // 恒启传输：connect 无 if (wsFactory != null) 分叉——工厂是必传构造参数
        assertFalse(
            "wsInboundFactory?" in src.substringAfter("class SdkLarkGateway"),
            "wsInboundFactory 必须是非空必传构造参数（编译期不变量，不留 null 分叉）",
        )
        // SDK 事件注册已整体删除（webhook 模式 ChannelEventBus 无事件来源）
        assertFalse(
            "private fun registerHandlers(" in src,
            "registerHandlers 已随 SDK websocket 入站下线删除",
        )
    }

    @Test
    fun `close 关闭自研传输且反射路径已删（下线批收口守护）`() {
        val src = java.io.File(
            "src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt"
        ).readText()
        val closeBody = src.substringAfter("override fun close()").substringBefore("private fun buildOptions(")
        assertTrue(
            "wsInboundTransportRef.getAndSet(null)?.close()" in closeBody,
            "close 必须关闭自研入站传输（步 1.5，getAndSet 清空防二次关）",
        )
        // 🔴 墓碑注释含函数名字样 ⇒ 归一化后扫描代码区
        val codeOnly = stripCommentsAndStrings(src)
        assertFalse(
            "reflectivelyShutdownWsExecutor" in codeOnly,
            "反射 shutdown 路径已随下线批删除（webhook 模式 rawWsClient 恒 null，无池可关）",
        )
        assertFalse(
            "wsExecutorReleased" in codeOnly,
            "wsExecutorReleased 字段已删（反射路径不复存在）",
        )
    }

    @Test
    fun `WsClientTransportFactory create 传凭据与域（工厂语义）`() {
        // 生产工厂的实装验证：create 的四实参正确透传（WsClient 构造不可直测——用 Fake 连接工厂的
        // 路径已被 :lark-ws 46 测覆盖；本用例钉死 gateway 侧签名：凭据随 create 传入）。
        val factory = WsClientTransportFactory(Dispatchers.Default)
        val created = runCatching {
            factory.create(
                credential = testCredential(),
                eventHandler = { null },
                stateListener = object : WsStateListener {
                    override fun onReconnecting() = Unit
                    override fun onReconnected() = Unit
                },
                domain = "https://open.feishu.cn",
            )
        }.getOrNull()
        // create 本身零 IO（WsClient.start 才 bootstrap）⇒ 必成功
        assertTrue(created != null, "create 只构造不连接，必须成功")
        runCatching { created.close() }
    }

    @Test
    fun `handleWsInboundEvent 源码守护（三分支 + 决策 9 纪律）`() {
        val src = java.io.File(
            "src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt"
        ).readText()
        val handler = src.substringAfter("private suspend fun handleWsInboundEvent(").substringBefore("private fun wsStateListener")
        assertTrue(handler.isNotEmpty(), "定位不到 handleWsInboundEvent")
        // MessageReceived → translate → fromMessage → offerInbound
        assertTrue(
            "offerInbound(LarkInboundMapper.fromMessage(wsTranslator.get().translate(ev), clock()))" in handler,
            "消息分支必须 translate + fromMessage + offerInbound（与 SDK 路径 capture→mapper→offer 同形）",
        )
        // CardAction → translateCardAction 闸门 → fromCardAction
        assertTrue("translateCardAction(ev.payload)?.let" in handler, "卡回传分支必须经闸门（null 丢弃）")
        assertTrue("LarkInboundMapper.fromCardAction(it, clock())" in handler, "卡回传翻译后投递")
        // Unknown 零消费
        assertTrue("WsInboundEvent.Unknown -> Unit" in handler, "未知类型透传零消费")
        // 回执载荷恒 null
        assertTrue("return null" in handler, "回执载荷恒 null（处理结果异步产出，与 SDK ChannelEventHandler void 等价）")
    }

    @Test
    fun `wsStateListener 翻译两态并发信号（源码守护 照 ch-on 现状纪律）`() {
        val src = java.io.File(
            "src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt"
        ).readText()
        val listener = src.substringAfter("private fun wsStateListener(): WsStateListener")
        assertTrue("GatewayConnectionState.Reconnecting" in listener, "onReconnecting 必须改状态机")
        assertTrue("GatewaySignal.Reconnecting" in listener, "onReconnecting 必须发信号（BridgeAssembler 具名分支依赖）")
        assertTrue("GatewayConnectionState.Connected(botOpenId, botName)" in listener, "onReconnected 沿用原 identity")
        assertTrue("GatewaySignal.Reconnected" in listener, "onReconnected 必须发信号")
    }
}
