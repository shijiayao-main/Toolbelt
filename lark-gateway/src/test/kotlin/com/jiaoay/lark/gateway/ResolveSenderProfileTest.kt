// 2026-09-14 用户身份链批：SdkLarkGateway.resolveSenderProfile 的旁路安全测试。
// 🔴 与 ResolveSenderNameTest 同款纪律：全部在**未连接**网关上做（rawClient() == null），
//    绝不发真实网络请求、绝不创建凭据文件。验证点：旁路三连——空 openId 短路 / 未连接不抛 / 不改连接态。
package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class ResolveSenderProfileTest {

    private fun newUnconnectedGateway(): Pair<SdkLarkGateway, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return SdkLarkGateway(netDispatcher = Dispatchers.IO, scope = scope, wsInboundFactory = NoopWsInboundFactory) to scope
    }

    @Test
    fun `空 openId 短路返回 null（不触碰 client）`() = runBlocking {
        val (gw, scope) = newUnconnectedGateway()
        try {
            assertNull(gw.resolveSenderProfile(""), "空串 openId 必须短路返回 null")
            assertNull(gw.resolveSenderProfile("   "), "空白 openId 必须短路返回 null")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `未连接网关（rawClient 为 null）返回 null 且绝不抛异常`() = runBlocking {
        val (gw, scope) = newUnconnectedGateway()
        try {
            val result = gw.resolveSenderProfile("ou_some_user")
            assertNull(result, "未连接网关无 SDK client，resolveSenderProfile 应返回 null 而非抛异常")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolveSenderProfile 不改变连接状态（旁路，不触发连接）`() = runBlocking {
        val (gw, scope) = newUnconnectedGateway()
        try {
            assertEquals(GatewayConnectionState.Idle, gw.connectionState.value, "初始态应为 Idle")
            gw.resolveSenderProfile("ou_x")
            assertEquals(
                GatewayConnectionState.Idle,
                gw.connectionState.value,
                "resolveSenderProfile 是旁路查询，绝不应触发连接或改变状态机",
            )
        } finally {
            scope.cancel()
        }
    }
}
