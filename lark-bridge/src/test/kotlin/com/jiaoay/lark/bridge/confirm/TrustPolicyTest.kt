package com.jiaoay.lark.bridge.confirm

import com.jiaoay.lark.bridge.config.AccessConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [TrustPolicy]：信任判定的边界语义（高风险授权唯一真源）。 */
class TrustPolicyTest {

    private fun access(vararg users: String) = AccessConfig(trustedUsers = users.toList())

    @Test
    fun `名单内放行、名单外拒绝`() {
        val cfg = access("ou_alice", "ou_bob")
        assertTrue(TrustPolicy.isTrusted(cfg, "ou_alice"))
        assertTrue(TrustPolicy.isTrusted(cfg, "ou_bob"))
        assertFalse(TrustPolicy.isTrusted(cfg, "ou_mallory"))
    }

    @Test
    fun `空名单 = 无人信任（安全默认，不是放行全部）`() {
        assertFalse(TrustPolicy.isTrusted(AccessConfig(), "ou_anyone"))
        assertFalse(TrustPolicy.isTrusted(access(), ""))
    }

    @Test
    fun `空 openId 恒不信任（防空白项意外放行）`() {
        assertFalse(TrustPolicy.isTrusted(access(""), ""), "空串项不得授权空发送者")
        assertFalse(TrustPolicy.isTrusted(access("   "), "   "), "全空白项不得授权全空白发送者")
        assertFalse(TrustPolicy.isTrusted(access("ou_alice"), ""))
    }

    @Test
    fun `空白项不构成授权`() {
        val cfg = access("", "   ", "ou_alice")
        assertFalse(TrustPolicy.isTrusted(cfg, ""))
        assertFalse(TrustPolicy.isTrusted(cfg, "   "))
        assertTrue(TrustPolicy.isTrusted(cfg, "ou_alice"))
    }

    @Test
    fun `两侧去空白后精确比较（容忍配置里的前后空格）`() {
        assertTrue(TrustPolicy.isTrusted(access(" ou_alice "), "ou_alice"))
        assertTrue(TrustPolicy.isTrusted(access("ou_alice"), " ou_alice "))
    }

    @Test
    fun `精确匹配不是子串匹配（防前后缀碰撞）`() {
        val cfg = access("ou_alice")
        assertFalse(TrustPolicy.isTrusted(cfg, "ou_alic"), "前缀不得命中")
        assertFalse(TrustPolicy.isTrusted(cfg, "ou_alice2"), "后缀不得命中")
        assertFalse(TrustPolicy.isTrusted(cfg, "OU_ALICE"), "大小写敏感（openId 区分大小写）")
    }
}
