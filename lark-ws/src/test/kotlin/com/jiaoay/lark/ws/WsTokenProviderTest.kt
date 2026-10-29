// :lark-ws —— token 提供者单测：缓存命中 / 过期重取 / 失败旁路 / 主动作废（注入缝，零网络）。
package com.jiaoay.lark.ws

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WsTokenProviderTest {

    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(sec: Long) { now += sec * 1000 }
    }

    private fun okResp(token: String, expireSec: Int) =
        """{"code":0,"expire":$expireSec,"tenant_access_token":"$token"}"""

    @Test
    fun `首次取 token 发起请求并缓存`() = runTest {
        var calls = 0
        val clock = FakeClock()
        val provider = WsTokenProvider(
            domain = "https://open.feishu.cn", appId = "cli_a", appSecret = "sec",
            httpPost = { url, body ->
                calls++
                assertTrue(url.endsWith("/open-apis/auth/v3/tenant_access_token/internal"))
                assertTrue(body.contains("\"app_id\":\"cli_a\""))
                assertTrue(body.contains("\"app_secret\":\"sec\""))
                okResp("t-1", 7200)
            },
            clock = { clock.now },
        )
        assertEquals("t-1", provider.tenantTokenOrNull())
        assertEquals(1, calls)
        // 二次取：命中缓存零请求
        assertEquals("t-1", provider.tenantTokenOrNull())
        assertEquals(1, calls)
    }

    @Test
    fun `缓存过期重取（expire-300s 前置失效语义）`() = runTest {
        var calls = 0
        val clock = FakeClock()
        val provider = WsTokenProvider(
            domain = "", appId = "a", appSecret = "s",
            httpPost = { _, _ -> calls++; okResp("t-${calls}", 7200) },
            clock = { clock.now },
        )
        assertEquals("t-1", provider.tenantTokenOrNull())
        clock.advance(6900) // 7200-300=6900 后刚失效
        assertEquals("t-2", provider.tenantTokenOrNull())
        assertEquals(2, calls)
    }

    @Test
    fun `边界内不重取`() = runTest {
        var calls = 0
        val clock = FakeClock()
        val provider = WsTokenProvider(
            domain = "", appId = "a", appSecret = "s",
            httpPost = { _, _ -> calls++; okResp("t", 7200) },
            clock = { clock.now },
        )
        provider.tenantTokenOrNull()
        clock.advance(6899) // 差 1s 到前置失效点
        provider.tenantTokenOrNull()
        assertEquals(1, calls)
    }

    @Test
    fun `业务码非零返回 null 不缓存`() = runTest {
        var calls = 0
        val provider = WsTokenProvider(
            domain = "", appId = "a", appSecret = "s",
            httpPost = { _, _ -> calls++; """{"code":99991672,"msg":"app secret invalid"}""" },
            clock = { 0L },
        )
        assertNull(provider.tenantTokenOrNull())
        assertNull(provider.tenantTokenOrNull()) // 未缓存失败值：每次都重试
        assertEquals(2, calls)
    }

    @Test
    fun `响应缺 token 字段返回 null`() = runTest {
        val provider = WsTokenProvider(
            domain = "", appId = "a", appSecret = "s",
            httpPost = { _, _ -> """{"code":0,"expire":7200}""" },
            clock = { 0L },
        )
        assertNull(provider.tenantTokenOrNull())
    }

    @Test
    fun `非 JSON 响应吞异常返回 null（旁路语义）`() = runTest {
        val provider = WsTokenProvider(
            domain = "", appId = "a", appSecret = "s",
            httpPost = { _, _ -> "not-json{{" },
            clock = { 0L },
        )
        assertNull(provider.tenantTokenOrNull())
    }

    @Test
    fun `invalidate 后重取`() = runTest {
        var calls = 0
        val provider = WsTokenProvider(
            domain = "", appId = "a", appSecret = "s",
            httpPost = { _, _ -> calls++; okResp("t", 7200) },
            clock = { 0L },
        )
        provider.tenantTokenOrNull()
        provider.invalidate()
        provider.tenantTokenOrNull()
        assertEquals(2, calls)
    }

    @Test
    fun `短 expire 走 60s 下限防负 TTL`() = runTest {
        var calls = 0
        val clock = FakeClock()
        val provider = WsTokenProvider(
            domain = "", appId = "a", appSecret = "s",
            httpPost = { _, _ -> calls++; okResp("t", 100) }, // 100-300=-200 → 钳到 60
            clock = { clock.now },
        )
        assertEquals("t", provider.tenantTokenOrNull())
        clock.advance(30) // 30s < 60s 下限：仍有效
        assertEquals("t", provider.tenantTokenOrNull())
        assertEquals(1, calls)
        clock.advance(31) // 61s > 60s：过期
        assertEquals("t", provider.tenantTokenOrNull())
        assertEquals(2, calls)
    }
}
