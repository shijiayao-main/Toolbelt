// :lark-gateway —— 自研 WS 传输注入面。
// SdkLarkGateway 消费 :lark-ws 的窄接口——只暴露「启动 + 状态流 + 关闭」，事件回调经构造器注入；
// 生产实装见 BridgeGateways（WsClient 适配本接口），测试用 Fake 实现。
// 置于 :lark-gateway 而非 :lark-ws：接口属网关消费面（gateway 依赖 ws，反向 ws 不知 gateway）。
package com.jiaoay.lark.gateway

import com.jiaoay.lark.ws.WsInboundEvent

/**
 * 自研 WS 传输的注入面。**工厂语义**（非单实例）：[SdkLarkGateway] 的 connect 可重入，
 * 而 `WsClient.close()` 是终态——每次 connect 必须新建传输实例（与 WsClient 自身
 * connectionFactory「连接实例不跨重连复用」同构）。
 *
 * 凭据随 create 传入（非构造工厂时注入）：凭据属于「一次 connect」的生命周期而非网关
 *    构造期——[SdkLarkGateway.connect] 的实参 [LarkCredential] 就是本函数的 [credential] 来源
 *    （与 SDK 路径 buildOptions(cred, …) 同形）。
 *
 * 🔴 事件回调签名是挂起函数（WsClient 的 WsEventHandler 形态）——但回调纪律不变：
 * 实现方在回调内只允许 capture 等价物 + 纯 mapper + offerInbound（O(1) 非阻塞），不做重活——阻塞分发协程即整条入站停摆；
 * 回执由传输层框架自管，本回调的返回值恒 null（无回执载荷）。
 */
fun interface WsInboundTransportFactory {

    /**
     * 创建一次连接生命周期的传输实例。
     *
     * @param credential 本次 connect 的凭据（appId/appSecret/domain——WsClient bootstrap 需要）
     * @param eventHandler 事件回调（网关注入：翻译 + offerInbound）
     * @param stateListener 状态回调（网关注入：WsState → GatewayConnectionState 翻译 + 信号投递；
     *   与 SDK 路径 ch.on("reconnecting"/"reconnected") 等价——连接态事件允许改状态机 + trySend）
     * @param domain 飞书域（`https://open.feishu.cn` / lark 域；与 SDK 路径同源解析，见 SdkLarkGateway.domainOf）
     */
    fun create(
        credential: LarkCredential,
        eventHandler: suspend (WsInboundEvent) -> String?,
        stateListener: WsStateListener,
        domain: String,
    ): WsInboundTransport
}

/** 传输状态回调（WsClient.stateFlow 的回调化形态——工厂缝不暴露 Flow 类型给 SdkLarkGateway 以外的面）。 */
interface WsStateListener {
    /** 转入重连（WsState.Reconnecting）——翻译为 GatewayConnectionState.Reconnecting + GatewaySignal.Reconnecting。 */
    fun onReconnecting()

    /** 重连成功（Reconnecting 之后回到 Connected）——翻译为 Connected(原 identity) + GatewaySignal.Reconnected。 */
    fun onReconnected()
}

/** 一次连接生命周期的传输实例（WsClient 的窄投影：start/close/状态查询）。 */
interface WsInboundTransport {
    /**
     * 启动（bootstrap → 连接 → 心跳；首连失败内部转重连——与 SDK start() 行为一致）。
     * 非阻塞：立即返回，结果经 awaitConnected / stateListener 送达。
     */
    fun start()

    /**
     * 阻塞等待首个 Connected（供 connect 六步的超时判定——WsClient 首连失败不立刻终态，
     * 由网关侧 withTimeout 判定失败并 close 本实例）。
     * @return true=已连接；false=超时/终态（调用方应 close 本实例）
     */
    suspend fun awaitConnected(timeoutMs: Long): Boolean

    /** 关闭（幂等；终态——每次 connect 需新实例）。 */
    fun close()
}
