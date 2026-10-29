// :lark-gateway 测试 —— 共享零行为 ws 工厂：供只测出站/close 面、不触发 connect 的用例满足必传参数。
// create 只构造不连接（start no-op、awaitConnected 恒 false、close no-op）——不产生任何协程与网络。
package com.jiaoay.lark.gateway

import com.jiaoay.lark.ws.WsInboundEvent

/** 零行为 [WsInboundTransportFactory]（测试专用；生产实装见 WsClientTransportFactory）。 */
internal object NoopWsInboundFactory : WsInboundTransportFactory {
    override fun create(
        credential: LarkCredential,
        eventHandler: suspend (WsInboundEvent) -> String?,
        stateListener: WsStateListener,
        domain: String,
    ): WsInboundTransport = object : WsInboundTransport {
        override fun start() = Unit
        override suspend fun awaitConnected(timeoutMs: Long): Boolean = false
        override fun close() = Unit
    }
}
