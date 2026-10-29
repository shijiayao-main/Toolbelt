// 任务 15：SdkLarkGateway.resolveSenderName 的旁路安全测试。
// 🔴 全部在**未连接**网关上做（rawClient() == null），绝不发起真实网络请求、绝不创建凭据文件。
//    验证点：resolveSenderName 是「失败/无客户端/空 openId 一律返回 null 且绝不抛异常」的旁路语义。
package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class ResolveSenderNameTest {

    private fun newUnconnectedGateway(): Pair<SdkLarkGateway, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return SdkLarkGateway(netDispatcher = Dispatchers.IO, scope = scope, wsInboundFactory = NoopWsInboundFactory) to scope
    }

    @Test
    fun `空 openId 短路返回 null（不触碰 client）`() = runBlocking {
        val (gw, scope) = newUnconnectedGateway()
        try {
            assertNull(gw.resolveSenderName(""), "空串 openId 必须短路返回 null")
            assertNull(gw.resolveSenderName("   "), "空白 openId 必须短路返回 null")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `未连接网关（rawClient 为 null）返回 null 且绝不抛异常`() = runBlocking {
        val (gw, scope) = newUnconnectedGateway()
        try {
            // 未连接 ⇒ channel == null ⇒ rawClient() == null ⇒ runCatching 内 return@runCatching null。
            // 🔴 关键：绝不抛异常（旁路语义），绝不发起网络请求。
            val result = gw.resolveSenderName("ou_some_user")
            assertNull(result, "未连接网关无 SDK client，resolveSenderName 应返回 null 而非抛异常")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolveSenderName 不改变连接状态（旁路，不触发连接）`() = runBlocking {
        val (gw, scope) = newUnconnectedGateway()
        try {
            assertEquals(GatewayConnectionState.Idle, gw.connectionState.value, "初始态应为 Idle")
            gw.resolveSenderName("ou_x")
            assertEquals(
                GatewayConnectionState.Idle,
                gw.connectionState.value,
                "resolveSenderName 是旁路查询，绝不应触发连接或改变状态机",
            )
        } finally {
            scope.cancel()
        }
    }

    // 🔴「新增成员带默认实现 ⇒ 既有实现者零改动」的证明由 :lark-bridge 的 SyntheticGatewayTest 承担
    //   （SyntheticGateway 未 override resolveSenderName 仍编译通过、27 tests 全绿）+ MessageGateway.kt:64 源码 `= null`。
    //   本模块不重复该证明（无最小实现替身可用）。
}
